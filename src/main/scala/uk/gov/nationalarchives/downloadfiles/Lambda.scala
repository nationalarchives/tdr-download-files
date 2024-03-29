package uk.gov.nationalarchives.downloadfiles

import java.io.File
import java.util.UUID
import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.events.SQSEvent
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.Logger
import graphql.codegen.GetOriginalPath.getOriginalPath.{Data, Variables}
import graphql.codegen.AddFileStatus.{addFileStatus => afs}
import io.circe.generic.auto._
import io.circe.syntax._
import net.logstash.logback.argument.StructuredArguments.value
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.model.{DeleteMessageResponse, SendMessageResponse}
import software.amazon.awssdk.services.ssm.SsmClient
import software.amazon.awssdk.services.ssm.model.GetParameterRequest
import sttp.client3.{HttpURLConnectionBackend, Identity, SttpBackend}
import uk.gov.nationalarchives.aws.utils.kms.KMSClients.kms
import uk.gov.nationalarchives.aws.utils.s3.S3Clients.{s3, s3Async}
import uk.gov.nationalarchives.aws.utils.sqs.SQSClients.sqs
import uk.gov.nationalarchives.aws.utils.decoders.S3EventDecoder._
import uk.gov.nationalarchives.aws.utils.kms.KMSUtils
import uk.gov.nationalarchives.aws.utils.sqs.SQSUtils
import uk.gov.nationalarchives.downloadfiles.Lambda.{AntivirusDownloadOutput, DownloadOutput, DownloadOutputWithReceiptHandle}
import uk.gov.nationalarchives.tdr.GraphQLClient
import uk.gov.nationalarchives.tdr.keycloak.{KeycloakUtils, TdrKeycloakDeployment}

import java.net.URI
import java.time.Instant
import scala.annotation.unused
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.jdk.CollectionConverters._
import scala.language.postfixOps
import scala.util.{Failure, Success, Try}

class Lambda {
  val config: Config = ConfigFactory.load
  val kmsUtils: KMSUtils = KMSUtils(kms(config.getString("kms.endpoint")), Map("LambdaFunctionName" -> config.getString("function.name")))
  val clientSecret: String = getClientSecret(config.getString("auth.client.secret_path"), config.getString("ssm.endpoint"))
  val lambdaConfig: Map[String, String] = List(
    "sqs.queue.input",
    "sqs.queue.fileformat",
    "sqs.queue.antivirus",
    "sqs.queue.checksum",
    "efs.root.location",
    "url.auth",
    "url.api",
    "auth.client.id"
  ).map(configName => configName -> kmsUtils.decryptValue(config.getString(configName))).toMap + ("auth.client.secret" -> clientSecret)



  val downloadFilesQueueUrl: String = lambdaConfig("sqs.queue.input")
  val sqsUtils: SQSUtils = SQSUtils(sqs(config.getString("sqs.endpoint")))
  val deleteMessage: String => DeleteMessageResponse = sqsUtils.delete(downloadFilesQueueUrl, _)
  val fileFormatSendMessage: String => SendMessageResponse = sqsUtils.send(lambdaConfig("sqs.queue.fileformat"), _)
  val antivirusSendMessage: String => SendMessageResponse = sqsUtils.send(lambdaConfig("sqs.queue.antivirus"), _)
  val checksumSendMessage: String => SendMessageResponse = sqsUtils.send(lambdaConfig("sqs.queue.checksum"), _)
  val logger: Logger = Logger[Lambda]

  implicit val tdrKeycloakDeployment: TdrKeycloakDeployment = TdrKeycloakDeployment(lambdaConfig("url.auth"), "tdr", 3600)

  def getClientSecret(secretPath: String, endpoint: String): String = {
    val httpClient = ApacheHttpClient.builder.build
    val ssmClient: SsmClient = SsmClient.builder()
      .endpointOverride(URI.create(endpoint))
      .httpClient(httpClient)
      .region(Region.EU_WEST_2)
      .build()
    val getParameterRequest = GetParameterRequest.builder.name(secretPath).withDecryption(true).build
    ssmClient.getParameter(getParameterRequest).parameter().value()
  }

  def process(event: SQSEvent, @unused context: Context): List[String] = {
    val startTime = Instant.now
    val efsRootLocation = lambdaConfig("efs.root.location")
    val keycloakUtils = KeycloakUtils()
    val client: GraphQLClient[Data, Variables] = new GraphQLClient[Data, Variables](lambdaConfig("url.api"))
    val addFileStatusClient: GraphQLClient[afs.Data, afs.Variables] = new GraphQLClient[afs.Data, afs.Variables](lambdaConfig("url.api"))

    implicit val backend: SttpBackend[Identity, Any] = HttpURLConnectionBackend()

    val eventsWithErrors = decodeS3EventFromSqs(event)
    val fileUtils = FileUtils()

    val result: List[Future[DownloadOutputWithReceiptHandle]] = eventsWithErrors.events
      .flatMap(eventWithReceiptHandle => eventWithReceiptHandle.event.getRecords.asScala
        .map(record => {
          val s3KeyArr = record.getS3.getObject.getKey.split("/")
          val userId = s3KeyArr.head
          val fileId = UUID.fromString(s3KeyArr.last)
          val consignmentId = UUID.fromString(s3KeyArr.init.tail(0))
          logger.info(
            "Downloading files for file ID '{}' and consignment ID '{}'",
            value("fileId", fileId),
            value("consignmentId", consignmentId)
          )
          fileUtils.addFileStatus(keycloakUtils, addFileStatusClient, fileId, lambdaConfig)
          fileUtils.getFilePath(keycloakUtils, client, fileId, lambdaConfig).flatMap(originalPath => {
            val prefix = s"$efsRootLocation/$consignmentId"
            val writeDirectory = originalPath.split("/").init.mkString("/")
            new File(s"$prefix/$writeDirectory").mkdirs()
            val writePath = s"$efsRootLocation/$consignmentId/$originalPath"
            val dirtyBucketName = record.getS3.getBucket.getName
            val s3Response = fileUtils.writeFileFromS3(writePath, fileId, record, s3Async(config.getString("s3.endpoint"))).map(_ => {
              val output = DownloadOutput(consignmentId, fileId, originalPath, UUID.fromString(userId))
              val outputString = output.asJson.noSpaces
              fileFormatSendMessage(outputString)
              antivirusSendMessage(AntivirusDownloadOutput(consignmentId, fileId, originalPath, userId, dirtyBucketName).asJson.noSpaces)
              checksumSendMessage(outputString)
              DownloadOutputWithReceiptHandle(output, eventWithReceiptHandle.receiptHandle)
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

    val results: List[Try[DownloadOutputWithReceiptHandle]] = Await.result(
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
          case FailedDownloadException(receiptHandle, _, _) =>
            // Reset message visibility so that it can be retried immediately
            sqsUtils.makeMessageVisible(downloadFilesQueueUrl, receiptHandle)
          case _ => // Allow message to expire and be retried at its original expiry time
        }
      })

      downloadFileSucceeded.map(download => deleteMessage(download.receiptHandle))
      throw new RuntimeException(allErrors.mkString("\n"))
    } else {
      val timeTaken = java.time.Duration.between(startTime, Instant.now).toMillis.toDouble / 1000
      downloadFileSucceeded.map(success => {
        logger.info(
          s"Lambda complete in {} seconds for file ID '{}' and consignment ID '{}'",
          value("timeTaken", timeTaken),
          value("fileId", success.downloadOutput.fileId),
          value("consignmentId", success.downloadOutput.consignmentId)
        )
        success.receiptHandle
      })
    }
  }
}

object Lambda {
  case class DownloadOutputWithReceiptHandle(downloadOutput: DownloadOutput, receiptHandle: String)
  case class DownloadOutput(consignmentId: UUID, fileId: UUID, originalPath: String, userId: UUID)
  case class AntivirusDownloadOutput(consignmentId: UUID, fileId: UUID, originalPath: String, userId: String, dirtyBucketName: String)
}
