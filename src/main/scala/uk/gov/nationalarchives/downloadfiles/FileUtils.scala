package uk.gov.nationalarchives.downloadfiles

import java.net.URLDecoder
import java.nio.file.Paths
import java.util.UUID

import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3EventNotificationRecord
import com.typesafe.config.{Config, ConfigFactory}
import graphql.codegen.GetOriginalPath.getOriginalPath.{Data, Variables, document}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import sttp.client.{Identity, NothingT, SttpBackend}
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

  def getFilePath(keycloakUtils: KeycloakUtils, client: GraphQLClient[Data, Variables], fileId: UUID, lambdaConfig: Map[String, String])(implicit backend: SttpBackend[Identity, Nothing, NothingT]): Future[String] =
    for {
      token <- keycloakUtils.serviceAccountToken(lambdaConfig("auth.client.id"), lambdaConfig("auth.client.secret"))
      response <- client.getResult(token, document, Option(Variables(fileId)))
      filePath <- pathFromResponse(response, fileId)
    } yield filePath

  def writeFileFromS3(path: String, fileId: UUID, record: S3EventNotificationRecord, s3: S3Client): Try[String] = {
    val s3Obj = record.getS3
    val key = s3Obj.getObject.getKey
    val request = GetObjectRequest
      .builder
      .bucket(s3Obj.getBucket.getName)
      .key(URLDecoder.decode(key, "utf-8"))
      .build
    Try {
      s3.getObject(request, Paths.get(path))
      key
    }
  }
}

object FileUtils {
  def apply()(implicit executionContext: ExecutionContext, keycloakDeployment: TdrKeycloakDeployment): FileUtils = new FileUtils()(executionContext, keycloakDeployment)
}
