package uk.gov.nationalarchives.downloadfiles

import java.io.File
import java.util.UUID
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.events.SQSEvent
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.Logger
import graphql.codegen.GetOriginalPath.getOriginalPath.{Data, Variables}
import io.circe.generic.auto._
import io.circe.syntax._
import net.logstash.logback.argument.StructuredArguments.value
import software.amazon.awssdk.services.sqs.model.{DeleteMessageResponse, SendMessageResponse}
import sttp.client.{HttpURLConnectionBackend, Identity, NothingT, SttpBackend}
import uk.gov.nationalarchives.aws.utils.Clients.{kms, s3, sqs}
import uk.gov.nationalarchives.aws.utils.S3EventDecoder._
import uk.gov.nationalarchives.aws.utils.{KMSUtils, SQSUtils}
import uk.gov.nationalarchives.downloadfiles.Lambda.{AntivirusDownloadOutput, DownloadOutput}
import uk.gov.nationalarchives.tdr.GraphQLClient
import uk.gov.nationalarchives.tdr.keycloak.KeycloakUtils

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.jdk.CollectionConverters._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

class Lambda {
  val config: Config = ConfigFactory.load
  val kmsUtils: KMSUtils = KMSUtils(kms(config.getString("kms.endpoint")), Map("LambdaFunctionName" -> config.getString("function.name")))
  val lambdaConfig: Map[String, String] = kmsUtils.decryptValuesFromConfig(
    List(
      "sqs.queue.input",
      "sqs.queue.fileformat",
      "sqs.queue.antivirus",
      "sqs.queue.checksum",
      "efs.root.location",
      "url.auth",
      "url.api",
      "auth.client.id",
      "auth.client.secret"
    ))
  val downloadFilesQueueUrl: String = lambdaConfig("sqs.queue.input")
  val sqsUtils: SQSUtils = SQSUtils(sqs)
  val deleteMessage: String => DeleteMessageResponse = sqsUtils.delete(downloadFilesQueueUrl, _)
  val fileFormatSendMessage: String => SendMessageResponse = sqsUtils.send(lambdaConfig("sqs.queue.fileformat"), _)
  val antivirusSendMessage: String => SendMessageResponse = sqsUtils.send(lambdaConfig("sqs.queue.antivirus"), _)
  val checksumSendMessage: String => SendMessageResponse = sqsUtils.send(lambdaConfig("sqs.queue.checksum"), _)
  val logger: Logger = Logger[Lambda]


  def process(event: SQSEvent, context: Context): List[String] = {
    val efsRootLocation = lambdaConfig("efs.root.location")
    val keycloakUtils = KeycloakUtils(lambdaConfig("url.auth"))
    val client: GraphQLClient[Data, Variables] = new GraphQLClient[Data, Variables](lambdaConfig("url.api"))
    implicit val backend: SttpBackend[Identity, Nothing, NothingT] = HttpURLConnectionBackend()

    val eventsWithErrors = decodeS3EventFromSqs(event)
    val fileUtils = FileUtils()

    val result: List[Future[String]] = eventsWithErrors.events
      .flatMap(eventWithReceiptHandle => eventWithReceiptHandle.event.getRecords.asScala
        .map(record => {
          val s3KeyArr = record.getS3.getObject.getKey.split("/")
          val userId = s3KeyArr.head
          // This is the same value as the userId as it's the first part of the path. We need both for now so the antivirus doesn't break when deploying.
          // Once this and the antivirus changes are merged, the cognitoId variable can be deleted and removed from the outgoing json.
          val cognitoId = s3KeyArr.head
          val fileId = UUID.fromString(s3KeyArr.last)
          val consignmentId = UUID.fromString(s3KeyArr.init.tail(0))
          logger.info(
            "Downloading files for file ID '{}' and consignment ID '{}'",
            value("fileId", fileId),
            value("consignmentId", consignmentId)
          )
          fileUtils.getFilePath(keycloakUtils, client, fileId, lambdaConfig).flatMap(originalPath => {
            val prefix = s"$efsRootLocation/$consignmentId"
            val writeDirectory = originalPath.split("/").init.mkString("/")
            new File(s"$prefix/$writeDirectory").mkdirs()
            val writePath = s"$efsRootLocation/$consignmentId/$originalPath"
            val dirtyBucketName = record.getS3.getBucket.getName
            val s3Response = fileUtils.writeFileFromS3(writePath, fileId, record, s3).map(_ => {
              val output = DownloadOutput(consignmentId, fileId, originalPath).asJson.noSpaces
              fileFormatSendMessage(output)
              antivirusSendMessage(AntivirusDownloadOutput(consignmentId, fileId, originalPath, cognitoId, userId, dirtyBucketName).asJson.noSpaces)
              checksumSendMessage(output)
              eventWithReceiptHandle.receiptHandle
            })
            Future.fromTry(s3Response)
          }).recover(e => {
            throw FailedDownloadException(
              eventWithReceiptHandle.receiptHandle,
              s"Failed to run download file step for file ID '$fileId'",
              e
            )
          })
        }))

    val results: List[Try[String]] = Await.result(
      Future.sequence(result.map(r => r.map(Success(_)).recover(Failure(_)))),
      // Allow enough time to download large files, but time out before the Lambda reaches it own timeout
      2.5 minutes
    )

    val (downloadFileFailed: List[Throwable], downloadFileSucceeded) = results.partitionMap(_.toEither)
    val allErrors: List[Throwable] = downloadFileFailed ++ eventsWithErrors.errors
    if (allErrors.nonEmpty) {
      allErrors.foreach(e => {
        logger.error(e.getMessage, e)

        e match {
          case FailedDownloadException(receiptHandle, _, _) => {
            // Reset message visibility so that it can be retried immediately
            sqsUtils.makeMessageVisible(downloadFilesQueueUrl, receiptHandle)
          }
          case _ => // Allow message to expire and be retried at its original expiry time
        }
      })
      downloadFileSucceeded.map(deleteMessage)
      throw new RuntimeException(allErrors.mkString("\n"))
    } else {
      downloadFileSucceeded
    }
  }
}

object Lambda {
  case class DownloadOutput(consignmentId: UUID, fileId: UUID, originalPath: String)
  case class AntivirusDownloadOutput(consignmentId: UUID, fileId: UUID, originalPath: String, cognitoId: String, userId: String, dirtyBucketName: String)
}
