package uk.gov.nationalarchives.downloadfiles

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder
import com.github.tomakehurst.wiremock.client.WireMock.{okJson, post, urlEqualTo}
import com.github.tomakehurst.wiremock.common.FileSource
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.extension.{Parameters, ResponseDefinitionTransformer}
import com.github.tomakehurst.wiremock.http.{Request, ResponseDefinition}
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import io.circe.generic.auto._
import io.circe.parser.decode
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import uk.gov.nationalarchives.downloadfiles.AWSUtils._

import java.nio.ByteBuffer
import java.nio.charset.Charset
import scala.concurrent.ExecutionContext
import scala.io.Source.fromResource
import scala.sys.process._

class ExternalServicesTest extends AnyFlatSpec with BeforeAndAfterEach with BeforeAndAfterAll with ScalaFutures {

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(timeout = scaled(Span(5, Seconds)), interval = scaled(Span(100, Millis)))

  val wiremockGraphqlServer = new WireMockServer(9001)
  val wiremockAuthServer = new WireMockServer(9002)
  val wiremockKmsEndpoint = new WireMockServer(new WireMockConfiguration().port(9004).extensions(new ResponseDefinitionTransformer {
    override def transform(request: Request, responseDefinition: ResponseDefinition, files: FileSource, parameters: Parameters): ResponseDefinition = {
      case class KMSRequest(CiphertextBlob: String)
      decode[KMSRequest](request.getBodyAsString) match {
        case Left(err) => throw err
        case Right(req) =>
          val charset = Charset.defaultCharset()
          val plainText = charset.newDecoder.decode(ByteBuffer.wrap(req.CiphertextBlob.getBytes(charset))).toString
          ResponseDefinitionBuilder
            .like(responseDefinition)
            .withBody(s"""{"Plaintext": "$plainText"}""")
            .build()
      }
    }
    override def getName: String = ""
  }))

  implicit val ec: ExecutionContext = ExecutionContext.global

  val graphQlPath = "/graphql"
  val authPath = "/auth/realms/tdr/protocol/openid-connect/token"

  def stubKmsResponse: StubMapping = wiremockKmsEndpoint.stubFor(post(urlEqualTo("/")))

  def graphQlUrl: String = wiremockGraphqlServer.url(graphQlPath)

  // This only needs to return the original file path query data
  def graphqlOriginalPath: StubMapping = wiremockGraphqlServer.stubFor(post(urlEqualTo(graphQlPath))
    .willReturn(okJson(fromResource(s"json/original_path_response.json").mkString)))

  def graphqlOriginalPathWithSpace: StubMapping = wiremockGraphqlServer.stubFor(post(urlEqualTo(graphQlPath))
    .willReturn(okJson(fromResource(s"json/original_path_with_space_response.json").mkString)))

  def graphqlOriginalPathWithQuotes: StubMapping = wiremockGraphqlServer.stubFor(post(urlEqualTo(graphQlPath))
    .willReturn(okJson(fromResource(s"json/original_path_with_quotes_response.json").mkString)))

  def authOk: StubMapping = wiremockAuthServer.stubFor(post(urlEqualTo(authPath))
    .willReturn(okJson(fromResource(s"json/access_token.json").mkString)))

  override def beforeAll(): Unit = {
    s3Api.start
    wiremockGraphqlServer.start()
    wiremockAuthServer.start()
    wiremockKmsEndpoint.start()
    api.start()
    inputQueueHelper.createQueue
    avOutputQueueHelper.createQueue
    ffOutputQueueHelper.createQueue
    checksumOutputQueueHelper.createQueue
  }

  override def beforeEach(): Unit = {
    stubKmsResponse
    graphqlOriginalPath
    authOk
    createBucket
  }

  override def afterAll(): Unit = {
    wiremockGraphqlServer.stop()
    wiremockAuthServer.stop()
    wiremockKmsEndpoint.stop()
    api.shutdown()
  }

  override def afterEach(): Unit = {
    deleteBucket()
    wiremockAuthServer.resetAll()
    wiremockGraphqlServer.resetAll()
    wiremockKmsEndpoint.resetAll()
    "rm -rf ./src/test/resources/testfiles/f0a73877-6057-4bbb-a1eb-7c7b73cab586".!
  }
}
