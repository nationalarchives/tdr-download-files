package uk.gov.nationalarchives.downloadfiles

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.{equalToJson, okJson, post, urlEqualTo}
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.time.{Millis, Seconds, Span}
import AWSUtils._

import scala.concurrent.ExecutionContext
import scala.io.Source.fromResource
import scala.sys.process._

class ExternalServicesTest extends AnyFlatSpec with BeforeAndAfterEach with BeforeAndAfterAll with ScalaFutures {

  override implicit def patienceConfig: PatienceConfig = PatienceConfig(timeout = scaled(Span(5, Seconds)), interval = scaled(Span(100, Millis)))

  val wiremockGraphqlServer = new WireMockServer(9001)
  val wiremockAuthServer = new WireMockServer(9002)
  val wiremockKmsEndpoint = new WireMockServer(9004)

  def stubKmsResponse(cipherText: String): StubMapping =
    wiremockKmsEndpoint.stubFor(post(urlEqualTo("/"))
      .withRequestBody(equalToJson(s"""{"CiphertextBlob":"$cipherText","EncryptionContext":{"LambdaFunctionName":"test-lambda-function"}}"""))
      .willReturn(okJson(s"""{"Plaintext": "$cipherText"}"""))
    )
  def stubKmsResponses = {
    stubKmsResponse("Y2xpZW50X2lk")
    stubKmsResponse("c2VjcmV0")
    stubKmsResponse("aHR0cDovL2xvY2FsaG9zdDo4MDAxLzEvdGVzdHF1ZXVlaW5wdXQ=")
    stubKmsResponse("aHR0cDovL2xvY2FsaG9zdDo4MDAxLzEvdGVzdGZmcXVldWVvdXRwdXQ=")
    stubKmsResponse("aHR0cDovL2xvY2FsaG9zdDo4MDAxLzEvdGVzdGF2cXVldWVvdXRwdXQ=")
    stubKmsResponse("aHR0cDovL2xvY2FsaG9zdDo4MDAxLzEvdGVzdGNoZWNrc3VtcXVldWVvdXRwdXQ=")
    stubKmsResponse("aHR0cDovL2xvY2FsaG9zdDo5MDAyL2F1dGg=")
    stubKmsResponse("aHR0cDovL2xvY2FsaG9zdDo5MDAxL2dyYXBocWw=")
    stubKmsResponse("Li9zcmMvdGVzdC9yZXNvdXJjZXMvdGVzdGZpbGVz")
  }

  implicit val ec: ExecutionContext = ExecutionContext.global

  val graphQlPath = "/graphql"
  val authPath = "/auth/realms/tdr/protocol/openid-connect/token"

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
    stubKmsResponses
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
