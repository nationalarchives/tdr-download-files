package uk.gov.nationalarchives.downloadfiles

import java.net.URLDecoder
import java.nio.file.Paths
import java.util.UUID

import cats.effect.{ContextShift, IO}
import com.amazonaws.services.lambda.runtime.events.models.s3.S3EventNotification.S3EventNotificationRecord
import com.typesafe.config.{Config, ConfigFactory}
import example.{Client, GraphQlResponse}
import graphql.codegen.GetOriginalPath.getOriginalPath.{Data, Variables, document}
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import sttp.client.{HttpURLConnectionBackend, Identity, NothingT, SttpBackend}
import uk.gov.nationalarchives.tdr.error.NotAuthorisedError
import uk.gov.nationalarchives.tdr.keycloak.KeycloakUtils

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.util.Try

class FileUtils()(implicit val executionContext: ExecutionContext) {
  val config: Config = ConfigFactory.load
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

  case class DownloadFilesException(msg: String) extends RuntimeException

  implicit class OptFunction(strOpt: Option[String]) {
    def failIfPathEmpty(fileId: UUID): IO[String] =
      strOpt match {
        case Some(path) if path.nonEmpty => IO(path)
        case _ => IO.raiseError(new RuntimeException(s"The original path for fileId $fileId is missing or empty"))
      }
  }

  private def filePath(response: GraphQlResponse[Data], fileId: UUID): IO[String] = response.errors match {
    case Nil => response.data.get.getClientFileMetadata.originalPath.failIfPathEmpty(fileId)
    case List(authorisedError: NotAuthorisedError) => IO.raiseError(new RuntimeException(authorisedError.message))
    case errors => IO.raiseError(new RuntimeException(s"GraphQL response contained errors: ${errors.map(e => e.message).mkString}"))
  }

  def pretendAuthUtilsReturnsIO(keycloakUtils: KeycloakUtils) = {
    implicit val backend: SttpBackend[Identity, Nothing, NothingT] = HttpURLConnectionBackend()
    val f = keycloakUtils.serviceAccountToken(config.getString("auth.client.id"), config.getString("auth.client.secret"))
    IO(Await.result(f, 10.seconds))
  }

  def getFilePath(keycloakUtils: KeycloakUtils, client: Client[Data, Variables], fileId: UUID): IO[String] = {
    for {
      token <- pretendAuthUtilsReturnsIO(keycloakUtils)
      response <- client.getResult(token, document, Option(Variables(fileId)))
      filePath <- filePath(response, fileId)
    } yield filePath
  }

  def writeFileFromS3(path: String, fileId: UUID, record: S3EventNotificationRecord, s3: S3Client): IO[String] = {
    val s3Obj = record.getS3
    val key = s3Obj.getObject.getKey
    val request = GetObjectRequest
      .builder
      .bucket(s3Obj.getBucket.getName)
      .key(URLDecoder.decode(key, "utf-8"))
      .build
    IO.fromTry(Try {
      s3.getObject(request, Paths.get(path))
      key
    })
  }
}

object FileUtils {
  def apply()(implicit executionContext: ExecutionContext): FileUtils = new FileUtils()(executionContext)
}
