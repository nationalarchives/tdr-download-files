package uk.gov.nationalarchives.downloadfiles

import com.amazonaws.services.lambda.runtime.events.SQSEvent
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.{aResponse, get, urlEqualTo}
import org.elasticmq.rest.sqs.SQSRestServerBuilder
import org.mockito.MockitoSugar
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model._
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model._

import java.net.URI
import java.nio.file.{Files, Paths}
import scala.io.Source.fromResource
import scala.jdk.CollectionConverters._

object AWSUtils extends MockitoSugar {

  val wiremockS3 = new WireMockServer(8003)

  def putFile(location: String): Unit = {
    val path = getClass.getResource(s"/testfiles/$location").getPath
    val bytes = Files.readAllBytes(Paths.get(path))
    wiremockS3.stubFor(get(urlEqualTo(s"/6c6fbea8-4925-402c-92a6-f5d625b7eca1/f0a73877-6057-4bbb-a1eb-7c7b73cab586/acea5919-25a3-4c6b-8908-fa47cc77878f"))
      .willReturn(aResponse().withStatus(200).withBody(bytes))
    )
  }

  val port = 8001

  val inputQueueName = "testqueueinput"
  val avOutputQueueName = "testavqueueoutput"
  val ffOutputQueueName = "testffqueueoutput"
  val checksumOutputQueueName = "testchecksumqueueoutput"

  val api = SQSRestServerBuilder.withPort(port).withAWSRegion(Region.EU_WEST_2.toString).start()
  val inputQueueUrl = s"http://localhost:$port/queue/$inputQueueName"
  val avOutputQueueUrl = s"http://localhost:$port/queue/$avOutputQueueName"
  val ffOutputQueueUrl = s"http://localhost:$port/queue/$ffOutputQueueName"
  val checksumOutputQueueUrl = s"http://localhost:$port/queue/$checksumOutputQueueName"

  val inputQueueHelper: QueueHelper = QueueHelper(inputQueueUrl)
  val avOutputQueueHelper: QueueHelper = QueueHelper(avOutputQueueUrl)
  val ffOutputQueueHelper: QueueHelper = QueueHelper(ffOutputQueueUrl)
  val checksumOutputQueueHelper: QueueHelper = QueueHelper(checksumOutputQueueUrl)

  def createEvent(locations: String*): SQSEvent = {
    val event = new SQSEvent()

    val records = locations.map(location => {
      val record = new SQSMessage()
      val body = fromResource(s"json/$location.json").mkString
      record.setBody(body)
      val sendResponse = inputQueueHelper.send(body)
      record.setMessageId(sendResponse.messageId)
      record
    })

    val inputQueueMessages = inputQueueHelper.receive

    records.foreach(record => {
      val receiptHandle = inputQueueMessages.filter(_.messageId == record.getMessageId).head.receiptHandle
      record.setReceiptHandle(receiptHandle)
    })

    event.setRecords(records.asJava)
    event
  }

  case class QueueHelper(queueUrl: String) {
    val sqsClient: SqsClient = SqsClient.builder()
      .region(Region.EU_WEST_2)
      .endpointOverride(URI.create("http://localhost:8001"))
      .build()

    def send(body: String): SendMessageResponse = sqsClient.sendMessage(SendMessageRequest
      .builder.messageBody(body).queueUrl(queueUrl).build())

    def receive: List[Message] = sqsClient.receiveMessage(ReceiveMessageRequest
      .builder
      .maxNumberOfMessages(10)
      .queueUrl(queueUrl)
      .build).messages.asScala.toList

    def availableMessageCount: Int = attribute(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES).toInt

    def notVisibleMessageCount: Int = attribute(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE).toInt

    def createQueue: CreateQueueResponse = sqsClient.createQueue(CreateQueueRequest.builder.queueName(queueUrl.split("/")(4)).build())

    def deleteQueue: DeleteQueueResponse = sqsClient.deleteQueue(DeleteQueueRequest.builder.queueUrl(queueUrl).build)

    def delete(msg: Message): DeleteMessageResponse = sqsClient.deleteMessage(DeleteMessageRequest
      .builder.queueUrl(queueUrl).receiptHandle(msg.receiptHandle()).build)

    private def attribute(name: QueueAttributeName): String = sqsClient
      .getQueueAttributes(
        GetQueueAttributesRequest
          .builder
          .queueUrl(queueUrl)
          .attributeNames(name)
          .build
      ).attributes.get(name)
  }
}
