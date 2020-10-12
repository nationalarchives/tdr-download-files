package uk.gov.nationalarchives.downloadfiles

import java.util.UUID

import io.circe.generic.auto._
import io.circe.parser.decode
import org.scalatest.matchers.should.Matchers.{equal, _}
import uk.gov.nationalarchives.downloadfiles.AWSUtils._
import uk.gov.nationalarchives.downloadfiles.Lambda.DownloadOutput

import scala.io.Source
import scala.util.Try

class LambdaTest extends ExternalServicesTest {

  "The process method" should "put messages in the output queues if the messages are successful " in {
    putFile("testfile")
    new Lambda().process(createEvent("sns_s3_event"), null)
    val avMsgs = avOutputQueueHelper.receive
    val ffMsgs = ffOutputQueueHelper.receive
    val checksumMsgs = checksumOutputQueueHelper.receive

    avMsgs.size should equal(1)
    ffMsgs.size should equal(1)
    checksumMsgs.size should equal(1)
  }

  "The process method" should "put messages in the output queues, delete the successful messages and leave the key error message" in {
    putFile("testfile")
    intercept[RuntimeException] {
      new Lambda().process(createEvent("sns_s3_event", "sns_s3_no_key"), null)
    }
    val avMsgs = avOutputQueueHelper.receive
    val ffMsgs = ffOutputQueueHelper.receive
    val checksumMsgs = checksumOutputQueueHelper.receive
    val inputMessages = inputQueueHelper.receive

    avMsgs.size should equal(1)
    ffMsgs.size should equal(1)
    checksumMsgs.size should equal(1)
    inputMessages.size should equal(1)
  }

  "The process method" should "leave the queues unchanged if there are no successful messages" in {
    intercept[RuntimeException] {
      new Lambda().process(createEvent("sns_s3_no_key"), null)
    }
    val avMsgs = avOutputQueueHelper.receive
    val ffMsgs = ffOutputQueueHelper.receive
    val checksumMsgs = checksumOutputQueueHelper.receive
    val inputMessages = inputQueueHelper.receive

    avMsgs.size should equal(0)
    ffMsgs.size should equal(0)
    checksumMsgs.size should equal(0)
    inputMessages.size should equal(1)
  }

  "The process method" should "return the receipt handle for a successful message" in {
    putFile("testfile")
    val event = createEvent("sns_s3_event")
    val response = new Lambda().process(event, null)
    response.head should equal(receiptHandle(event.getRecords.get(0).getBody))
  }

  "The process method" should "throw an exception for a no key error" in {
    val event = createEvent("sns_s3_no_key")
    val exception = intercept[RuntimeException] {
      new Lambda().process(event, null)
    }
    exception.getMessage should equal("software.amazon.awssdk.services.s3.model.NoSuchKeyException: The resource you requested does not exist (Service: S3, Status Code: 404, Request ID: null, Extended Request ID: null)")

  }

  "The process method" should "send the correct output to the queues" in {
    putFile("testfile")
    new Lambda().process(createEvent("sns_s3_event"), null)

    val avMsgs = avOutputQueueHelper.receive
    val ffMsgs = ffOutputQueueHelper.receive
    val checksumMsgs = checksumOutputQueueHelper.receive
    val avOutput: DownloadOutput = decode[DownloadOutput](avMsgs.head.body) match {
      case Right(metadata) => metadata
      case Left(error) => throw error
    }
    val ffOutput: DownloadOutput = decode[DownloadOutput](ffMsgs.head.body()) match {
      case Right(metadata) => metadata
      case Left(error) => throw error
    }

    val checksumOutput: DownloadOutput = decode[DownloadOutput](checksumMsgs.head.body()) match {
      case Right(metadata) => metadata
      case Left(error) => throw error
    }

    checkOutput(avOutput)
    checkOutput(ffOutput)
    checkOutput(checksumOutput)
  }

  "The process method" should "write the file to the correct path" in {
    putFile("testfile")
    new Lambda().process(createEvent("sns_s3_event"), null)
    val fileAttempt = Try(Source.fromFile("./src/test/resources/testfiles/f0a73877-6057-4bbb-a1eb-7c7b73cab586/originalPath"))
    fileAttempt.isSuccess should be(true)
  }

  "The process method" should "write the file to the correct path if the original path is nested with spaces" in {
    wiremockGraphqlServer.resetAll()
    graphqlOriginalPathWithSpace
    putFile("testfile")
    new Lambda().process(createEvent("sns_s3_event"), null)
    val fileAttempt = Try(Source.fromFile("./src/test/resources/testfiles/f0a73877-6057-4bbb-a1eb-7c7b73cab586/a nested/path with/spaces"))
    fileAttempt.isSuccess should be(true)
  }

  private def checkOutput(output: DownloadOutput): Unit = {
    output.consignmentId should equal(UUID.fromString("f0a73877-6057-4bbb-a1eb-7c7b73cab586"))
    output.fileId should equal(UUID.fromString("acea5919-25a3-4c6b-8908-fa47cc77878f"))
    output.originalPath should equal("originalPath")
  }
}
