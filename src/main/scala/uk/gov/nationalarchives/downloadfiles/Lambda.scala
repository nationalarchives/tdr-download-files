package uk.gov.nationalarchives.downloadfiles

import java.util.UUID

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.events.SQSEvent
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.Logger
import graphql.codegen.GetOriginalPath.getOriginalPath.{Data, Variables}
import software.amazon.awssdk.services.sqs.model.{DeleteMessageResponse, SendMessageResponse}
import sttp.client.{HttpURLConnectionBackend, Identity, NothingT, SttpBackend}
import uk.gov.nationalarchives.aws.utils.Clients.{s3, sqs}
import uk.gov.nationalarchives.aws.utils.S3EventDecoder._
import uk.gov.nationalarchives.aws.utils.SQSUtils
import uk.gov.nationalarchives.tdr.GraphQLClient
import uk.gov.nationalarchives.tdr.keycloak.KeycloakUtils

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.jdk.CollectionConverters._
import scala.language.postfixOps
import scala.sys.process._
import io.circe.syntax._
import io.circe.generic.auto._
import uk.gov.nationalarchives.downloadfiles.Lambda.DownloadOutput

import scala.util.{Failure, Success, Try}

class Lambda {
  val config: Config = ConfigFactory.load
  val sqsUtils: SQSUtils = SQSUtils(sqs)
  val deleteMessage: String => DeleteMessageResponse = sqsUtils.delete(config.getString("sqs.queue.input"), _)
  val fileFormatSendMessage: String => SendMessageResponse = sqsUtils.send(config.getString("sqs.queue.fileformat"), _)
  val antivirusSendMessage: String => SendMessageResponse = sqsUtils.send(config.getString("sqs.queue.antivirus"), _)
  val logger: Logger = Logger[Lambda]

  def process(event: SQSEvent, context: Context): List[String] = {
    val efsRootLocation = ConfigFactory.load.getString("efs.root.location")
    val keycloakUtils = KeycloakUtils(config.getString("url.auth"))
    val client: GraphQLClient[Data, Variables] = new GraphQLClient[Data, Variables](config.getString("url.api"))
    implicit val backend: SttpBackend[Identity, Nothing, NothingT] = HttpURLConnectionBackend()

    val eventsWithErrors = decodeS3EventFromSqs(event)
    val fileUtils = FileUtils()

    val result: List[Future[String]] = eventsWithErrors.events
        .flatMap(e => e.event.getRecords.asScala
        .map(record => {
          val s3KeyArr = record.getS3.getObject.getKey.split("/")
          val cognitoId = s3KeyArr.head
          val fileId = UUID.fromString(s3KeyArr.last)
          val consignmentId = UUID.fromString(s3KeyArr.init.tail(0))
          fileUtils.getFilePath(keycloakUtils, client, fileId).flatMap(originalPath => {
            val writeDirectory = originalPath.split("/").init.mkString("/")
            s"mkdir -p $efsRootLocation/$consignmentId/$writeDirectory".!!
            val writePath = s"$efsRootLocation/$consignmentId/$originalPath"
            val s3Response = fileUtils.writeFileFromS3(writePath, fileId, record, s3).map(_ => {
              fileFormatSendMessage(DownloadOutput(cognitoId, consignmentId, fileId, originalPath).asJson.noSpaces)
              antivirusSendMessage(DownloadOutput(cognitoId, consignmentId, fileId, originalPath).asJson.noSpaces)
              e.receiptHandle
            })
            Future.fromTry(s3Response)
          })
        }))

    val results: List[Try[String]] = Await.result(Future.sequence(result.map(r => r.map(Success(_)).recover(Failure(_)))), 10 seconds)

    val (downloadFileFailed: List[Throwable], downloadFileSucceeded) = results.partitionMap(_.toEither)
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
  case class DownloadOutput(cognitoId: String, consignmentId: UUID, fileId: UUID, originalPath: String)
}
