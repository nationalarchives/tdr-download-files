package uk.gov.nationalarchives.downloadfiles

import java.io.File
import java.util.UUID

import com.amazonaws.services.lambda.runtime.events.SQSEvent
import com.amazonaws.services.lambda.runtime.events.SQSEvent.SQSMessage
import com.typesafe.config.ConfigFactory

import scala.jdk.CollectionConverters._
import scala.reflect.io.Directory

// An entry point you can use to run the Lambda code locally
object LambdaRunner extends App {
  val fileId: UUID = UUID.fromString("1b93cc1a-38eb-4176-acfc-38e6ea50cc41")
  val consignmentId: UUID = UUID.fromString("fca0aaba-7234-4bc6-8399-db034d76baba")
  val userId: String = "8d2caf26-7163-42ea-96a9-8460ad60db22"
  val bucketName: String = "tdr-upload-files-cloudfront-dirty-intg"
  val downloadPath = ConfigFactory.load.getString("efs.root.location")
  val receiptHandle = "fake-receipt-handle"

  wipeConsignmentDirectory(downloadPath, consignmentId)

  val fakeSqsEvent: SQSEvent = buildSqsEvent(fileId, consignmentId, userId, bucketName, receiptHandle)

  // Context is not used, so it's safe to pass null
  val emptyLambdaContext = null


  new Lambda().process(fakeSqsEvent, emptyLambdaContext)

  private def wipeConsignmentDirectory(downloadRootPath: String, consignmentId: UUID): Unit = {
    val consignmentDirectory = new Directory(new File(s"$downloadRootPath/$consignmentId"))
    consignmentDirectory.deleteRecursively()
  }

  private def buildSqsEvent(fileId: UUID, consignmentId: UUID, userId: String,
                            bucketName: String, receiptHandle: String) = {
    val body: String = buildMessageBody(fileId, consignmentId, userId, bucketName)
    val snsMessage: String = buildSnsMessage(body)

    val sqsMessage = new SQSMessage
    sqsMessage.setBody(snsMessage)
    sqsMessage.setReceiptHandle(receiptHandle)

    val fakeSqsEvent = new SQSEvent
    fakeSqsEvent.setRecords(List(sqsMessage).asJava)

    fakeSqsEvent
  }

  private def buildMessageBody(fileId: UUID, consignmentId: UUID, userId: String, bucketName: String): String = {
    val s3ObjectKey = s"$userId/$consignmentId/$fileId"

    s"""{
      |   "Records": [
      |       {
      |           "eventVersion": "",
      |           "eventSource": "",
      |           "awsRegion": "eu-west-2",
      |           "eventTime": "2020-06-04T03:10:00.000Z",
      |           "eventName": "",
      |           "userIdentity": {"principalId": ""},
      |           "requestParameters": {"sourceIPAddress": ""},
      |           "responseElements": {"x-amz-request-id":"","x-amz-id-2":""},
      |           "s3": {
      |               "s3SchemaVersion": "",
      |               "configurationId": "",
      |               "bucket": {"name": "$bucketName","ownerIdentity": {"principalId":""},"arn": ""},
      |               "object": {
      |                   "key": "$s3ObjectKey",
      |                   "size":10,
      |                   "eTag":"",
      |                   "versionId":"",
      |                   "sequencer":""
      |               }
      |           }
      |       }
      |   ]
      |}""".stripMargin
  }

  private def buildSnsMessage(body: String): String = {
    val escapedMessageBody = body.replace(""""""", """\"""").replace("\n", "")

    s"""{
       |    "Type": "Notification",
       |    "MessageId": "",
       |    "TopicArn": "",
       |    "Subject": "",
       |    "Message": "$escapedMessageBody",
       |    "Timestamp" : "2020-06-04T03:10:00.000Z",
       |    "SignatureVersion": "1",
       |    "Signature": "",
       |    "SigningCertURL" : "",
       |    "UnsubscribeURL" : ""
       |}""".stripMargin
  }
}
