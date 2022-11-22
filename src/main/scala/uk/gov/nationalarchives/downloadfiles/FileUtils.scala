package uk.gov.nationalarchives.downloadfiles

import cats.effect.unsafe.implicits.global

import java.net.URLDecoder
import java.nio.file.Paths
import java.util.UUID
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3EventNotificationRecord
import graphql.codegen.AddFileStatus.{addFileStatus => afs}
import graphql.codegen.AddFileStatus.addFileStatus.AddFileStatus
import graphql.codegen.GetOriginalPath.getOriginalPath.{Data, Variables, document}
import graphql.codegen.types.AddFileStatusInput
import software.amazon.awssdk.services.s3.{S3AsyncClient, S3Client}
import uk.gov.nationalarchives.aws.utils.s3.S3Clients.s3
import uk.gov.nationalarchives.aws.utils.s3.S3Utils
import sttp.client3.{Identity, SttpBackend}
import uk.gov.nationalarchives.tdr.error.NotAuthorisedError
import uk.gov.nationalarchives.tdr.keycloak.{KeycloakUtils, TdrKeycloakDeployment}
import uk.gov.nationalarchives.tdr.{GraphQLClient, GraphQlResponse}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class FileUtils()(implicit val executionContext: ExecutionContext, keycloakDeployment: TdrKeycloakDeployment) {
  case class DownloadFilesException(msg: String) extends RuntimeException

  private def failed(msg: String) = Future.failed(new RuntimeException(msg))

  implicit class OptFunction(strOpt: Option[String]) {
    def failIfPathEmpty(fileId: UUID): Future[String] =
      strOpt match {
        case Some(path) if path.nonEmpty => Future(path)
        case _ => failed(s"The original path for fileId $fileId is missing or empty")
      }
  }

  private def pathFromResponse(response: GraphQlResponse[Data], fileId: UUID): Future[String] = response.errors match {
    case Nil => response.data.get.getClientFileMetadata.originalPath.failIfPathEmpty(fileId)
    case List(authorisedError: NotAuthorisedError) => failed(authorisedError.message)
    case errors => failed(s"GraphQL response contained errors: ${errors.map(e => e.message).mkString}")
  }

  def getFilePath(keycloakUtils: KeycloakUtils, client: GraphQLClient[Data, Variables], fileId: UUID, lambdaConfig: Map[String, String])(implicit backend: SttpBackend[Identity, Any]): Future[String] =
    for {
      token <- keycloakUtils.serviceAccountToken(lambdaConfig("auth.client.id"), lambdaConfig("auth.client.secret"))
      response <- client.getResult(token, document, Option(Variables(fileId)))
      filePath <- pathFromResponse(response, fileId)
    } yield filePath

  def addFileStatus(keycloakUtils: KeycloakUtils, client: GraphQLClient[afs.Data, afs.Variables], fileId: UUID, lambdaConfig: Map[String, String])(implicit backend: SttpBackend[Identity, Any]): Future[AddFileStatus] = {

    val statusType = "Upload"
    val statusValue = "Success"
    for {
      token <- keycloakUtils.serviceAccountToken(lambdaConfig("auth.client.id"), lambdaConfig("auth.client.secret"))
      response <- client.getResult(token, document, Option(afs.Variables(AddFileStatusInput(fileId, statusType, statusValue))))
      fileStatus <- getFileStatusResponse(response, fileId, statusType, statusValue)
    } yield fileStatus
  }

  private def getFileStatusResponse(response: GraphQlResponse[afs.Data], fileId: UUID, statusType: String, statusValue: String): Future[AddFileStatus] = response.errors match {
    case Nil => Future(response.data.get.addFileStatus)
    case List(authorisedError: NotAuthorisedError) => failed(authorisedError.message)
    case errors => failed(s"Unable to add file status with statusType '$statusType' and value '$statusValue' for file '$fileId'. Errors: ${errors.map(e => e.message).mkString}")
  }

  def writeFileFromS3(path: String, fileId: UUID, record: S3EventNotificationRecord, s3: S3AsyncClient): Try[String] = {
    val s3Utils = S3Utils(s3)
    val s3Obj = record.getS3
    val key = s3Obj.getObject.getKey
    Try{
      s3Utils.downloadFiles(s3Obj.getBucket.getName, key, Option(Paths.get(path))).unsafeRunSync()
      key
    }
  }
}

object FileUtils {
  def apply()(implicit executionContext: ExecutionContext, keycloakDeployment: TdrKeycloakDeployment): FileUtils = new FileUtils()(executionContext, keycloakDeployment)
}
