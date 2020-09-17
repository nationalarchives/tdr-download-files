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
import uk.gov.nationalarchives.tdr.{GraphQLClient, GraphQlResponse}
import uk.gov.nationalarchives.tdr.error.NotAuthorisedError
import uk.gov.nationalarchives.tdr.keycloak.KeycloakUtils

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class FileUtils()(implicit val executionContext: ExecutionContext) {
  val config: Config = ConfigFactory.load

  case class DownloadFilesException(msg: String) extends RuntimeException

  implicit class OptFunction(strOpt: Option[String]) {
    def failIfPathEmpty(fileId: UUID): Try[String] = if ((strOpt.isDefined && strOpt.get.isEmpty) || strOpt.isEmpty) {
      Failure(new RuntimeException(s"The original path for fileId $fileId is missing or empty"))
    } else {
      Success(strOpt.get)
    }
  }

  def getFilePath(keycloakUtils: KeycloakUtils, client: GraphQLClient[Data, Variables], fileId: UUID)(implicit backend: SttpBackend[Identity, Nothing, NothingT]): Future[Try[String]] = {

    val queryResult: Future[Try[GraphQlResponse[Data]]] = (for {
      token <- keycloakUtils.serviceAccountToken(config.getString("auth.client.id"), config.getString("auth.client.secret"))
      result <- client.getResult(token, document, Option(Variables(fileId)))
    } yield Success(result)) recover (e => {
      Failure(e)
    })

    queryResult.map(_.map(
      response => {
        response.errors match {
          case Nil => response.data.get.getClientFileMetadata.originalPath.failIfPathEmpty(fileId)
          case List(authorisedError: NotAuthorisedError) => Failure(new RuntimeException(authorisedError.message))
          case errors => Failure(new RuntimeException(s"GraphQL response contained errors: ${errors.map(e => e.message).mkString}"))
        }
      }
    ).flatten
    )
  }


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
  def apply()(implicit executionContext: ExecutionContext): FileUtils = new FileUtils()(executionContext)
}
