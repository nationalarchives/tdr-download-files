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
import scala.util.Try

class FileUtils()(implicit val executionContext: ExecutionContext) {
  val config: Config = ConfigFactory.load

  def getFilePath(keycloakUtils: KeycloakUtils, client: GraphQLClient[Data, Variables], fileId: UUID)(implicit backend: SttpBackend[Identity, Nothing, NothingT]): Future[Either[String, String]] = {

    val queryResult: Future[Either[String, GraphQlResponse[Data]]] = (for {
      token <- keycloakUtils.serviceAccountToken(config.getString("auth.client.id"), config.getString("auth.client.secret"))
      result <- client.getResult(token, document, Option(Variables(fileId)))
    } yield Right(result)) recover(e => {
      Left(e.getMessage)
    })

    val result = queryResult.map {
      case Right(response) => response.errors match {
        case Nil => Right(response.data.get)
        case List(authError: NotAuthorisedError) => Left(authError.message)
        case errors => Left(s"GraphQL response contained errors: ${errors.map(e => e.message).mkString}")
      }
      case Left(e) => Left(e)
    }
    implicit class OptFunction(strOpt: Option[String]) {
      def toOptEmpty: Option[String] = if(strOpt.getOrElse("").isEmpty) Option.empty else strOpt
    }
    result.map(_.map(_.getClientFileMetadata.originalPath.toOptEmpty.toRight("The original path is missing or empty")).flatten)
  }



  def writeFileFromS3(path: String, fileId: UUID, record: S3EventNotificationRecord, s3: S3Client): Either[String, String] = {
    val s3Obj = record.getS3
    val key = s3Obj.getObject.getKey
    val request = GetObjectRequest
      .builder
      .bucket(s3Obj.getBucket.getName)
      .key(URLDecoder.decode(key, "utf-8"))
      .build
    Try{
      s3.getObject(request, Paths.get(path))
      key
    }.toEither.left.map(e => {
      e.printStackTrace()
      e.getMessage
    })
  }

}

object FileUtils {
  def apply()(implicit executionContext: ExecutionContext): FileUtils = new FileUtils()(executionContext)
}
