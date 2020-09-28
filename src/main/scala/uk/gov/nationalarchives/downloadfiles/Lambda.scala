package uk.gov.nationalarchives.downloadfiles

import java.util.UUID

import cats.effect.IO
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.events.SQSEvent
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.Logger
import example.Client
import graphql.codegen.GetOriginalPath.getOriginalPath.{Data, Variables}
import io.circe.generic.auto._
import io.circe.syntax._
import software.amazon.awssdk.services.sqs.model.{DeleteMessageResponse, SendMessageResponse}
import uk.gov.nationalarchives.aws.utils.Clients.{s3, sqs}
import uk.gov.nationalarchives.aws.utils.S3EventDecoder._
import uk.gov.nationalarchives.aws.utils.SQSUtils
import uk.gov.nationalarchives.downloadfiles.Lambda.DownloadOutput
import uk.gov.nationalarchives.tdr.keycloak.KeycloakUtils

import scala.concurrent.ExecutionContext.Implicits.global
import scala.jdk.CollectionConverters._
import scala.language.postfixOps
import scala.sys.process._

class Lambda {
  val config: Config = ConfigFactory.load
  val sqsUtils: SQSUtils = SQSUtils(sqs)
  val deleteMessage: String => DeleteMessageResponse = sqsUtils.delete(config.getString("sqs.queue.input"), _)
  val sendMessage: String => SendMessageResponse = sqsUtils.send(config.getString("sqs.queue.fileformat"), _)
  val logger: Logger = Logger[Lambda]

  def process(event: SQSEvent, context: Context): List[String] = {
    val efsRootLocation = ConfigFactory.load.getString("efs.root.location")
    val keycloakUtils = KeycloakUtils(config.getString("url.auth"))
    val eventsWithErrors = decodeS3EventFromSqs(event)
    val fileUtils = FileUtils()

    val client = new Client[Data,Variables](config.getString("url.api"))

    val results: List[IO[String]] = for {
      event <- eventsWithErrors.events
      record <- event.event.getRecords.asScala.toList
    } yield {
      val s3KeyArr = record.getS3.getObject.getKey.split("/")
      val fileId = UUID.fromString(s3KeyArr.last)
      val consignmentId = UUID.fromString(s3KeyArr.init.tail(0))
      for {
        originalPath <- fileUtils.getFilePath(keycloakUtils, client, fileId)
        _ <- {
          val writeDirectory = originalPath.split("/").init.mkString("/")
          s"mkdir -p $efsRootLocation/$consignmentId/$writeDirectory".!!
          val writePath = s"$efsRootLocation/$consignmentId/$originalPath"
          fileUtils.writeFileFromS3(writePath, fileId, record, s3)
        }
        sendMessageResponse <- {
          sendMessage(DownloadOutput(consignmentId, fileId, originalPath).asJson.noSpaces)
          IO(event.receiptHandle)
        }
      } yield sendMessageResponse
    }

    val (downloadFileFailed, downloadFileSucceeded) = results.map(_.attempt.unsafeRunSync()).partitionMap(identity)
    val allErrors: List[Throwable] = downloadFileFailed ++ eventsWithErrors.errors.map(_.getCause)
    if (allErrors.nonEmpty) {
      allErrors.foreach(e => logger.error(e.getMessage, e))
      downloadFileSucceeded.map(deleteMessage)
      throw new RuntimeException(allErrors.mkString("\n"))
    } else {
      downloadFileSucceeded
    }
  }
}

object Lambda {
  case class DownloadOutput(consignmentId: UUID, fileId: UUID, originalPath: String)
}
