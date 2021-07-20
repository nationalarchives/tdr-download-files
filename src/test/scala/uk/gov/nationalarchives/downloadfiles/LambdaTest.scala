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

    avOutputQueueHelper.availableMessageCount should equal(1)
    ffOutputQueueHelper.availableMessageCount should equal(1)
    checksumOutputQueueHelper.availableMessageCount should equal(1)
  }

  "The process method" should "not send any messages to file check queues if there are no successful messages" in {
    intercept[RuntimeException] {
      new Lambda().process(createEvent("sns_s3_no_key", "sns_empty_message"), null)
    }

    avOutputQueueHelper.availableMessageCount should equal(0)
    ffOutputQueueHelper.availableMessageCount should equal(0)
    checksumOutputQueueHelper.availableMessageCount should equal(0)
  }

  "The process method" should "make the original message available again if the download step fails" in {
    intercept[RuntimeException] {
      new Lambda().process(createEvent("sns_s3_no_key"), null)
    }

    inputQueueHelper.availableMessageCount should equal(1)
  }

  "The process method" should "leave the original message if it cannot be parsed" in {
    intercept[RuntimeException] {
      new Lambda().process(createEvent("sns_empty_message"), null)
    }

    // Expect malformed JSON message to not be visible, because the receipt handle cannot be extracted in order to reset
    // the message visibility
    inputQueueHelper.notVisibleMessageCount should equal(1)
  }

  "The process method" should "put messages in the output queues, delete the successful messages and leave the key error message" in {
    putFile("testfile")
    intercept[RuntimeException] {
      new Lambda().process(createEvent("sns_empty_message", "sns_s3_event", "sns_s3_no_key"), null)
    }

    // Check available messages on output queues because a newly-added message is immediately visible
    avOutputQueueHelper.availableMessageCount should equal(1)
    ffOutputQueueHelper.availableMessageCount should equal(1)
    checksumOutputQueueHelper.availableMessageCount should equal(1)

    // Expect message which fails the S3 step to be available so it can be retried immediately
    inputQueueHelper.availableMessageCount should equal(1)
    // Expect malformed JSON message to not be visible, because the receipt handle cannot be extracted in order to reset
    // the message visibility
    inputQueueHelper.notVisibleMessageCount should equal(1)
  }

  "The process method" should "return the receipt handle for a successful message" in {
    putFile("testfile")
    val event = createEvent("sns_s3_event")
    val originalReceiptHandle = event.getRecords.get(0).getReceiptHandle

    val response = new Lambda().process(event, null)

    response.head should equal(originalReceiptHandle)
  }

  "The process method" should "throw an exception for a no key error" in {
    val event = createEvent("sns_s3_no_key")
    val exception = intercept[RuntimeException] {
      new Lambda().process(event, null)
    }
    exception.getMessage should include("Failed to run download file step for file ID 'e3641944-a302-4df2-a746-2f56c2698d8f'")
  }

  "The process method" should "throw an exception if a message is not a valid S3 event" in {
    val event = createEvent("sns_empty_message")
    val exception = intercept[RuntimeException] {
      new Lambda().process(event, null)
    }
    exception.getMessage should include("Attempt to decode value")
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

  "The process method" should "write the file to the correct path if the original path contains quotes" in {
    wiremockGraphqlServer.resetAll()
    graphqlOriginalPathWithQuotes
    putFile("testfile")
    new Lambda().process(createEvent("sns_s3_event"), null)
    val fileAttempt = Try(Source.fromFile("./src/test/resources/testfiles/f0a73877-6057-4bbb-a1eb-7c7b73cab586/a\"path'with/quo'tes\"in"))
    fileAttempt.isSuccess should be(true)
  }

  private def checkOutput(output: DownloadOutput): Unit = {
    output.consignmentId should equal(UUID.fromString("f0a73877-6057-4bbb-a1eb-7c7b73cab586"))
    output.fileId should equal(UUID.fromString("acea5919-25a3-4c6b-8908-fa47cc77878f"))
    output.originalPath should equal("originalPath")
  }
}
