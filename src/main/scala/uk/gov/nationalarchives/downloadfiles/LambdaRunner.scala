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
  val fileId: UUID = UUID.fromString("686181e5-8379-4b39-8681-7e71aab2e98d")
  val consignmentId: UUID = UUID.fromString("750aa09a-1254-4e2f-b9b9-c838ed091dd6")
  val cognitoUserId: String = "eu-west-2:56f64881-67ca-4657-87dc-3065a6ce3b20"
  val bucketName: String = "tdr-upload-files-dirty-intg"
  val downloadPath = ConfigFactory.load.getString("efs.root.location")

  wipeConsignmentDirectory(downloadPath, consignmentId)

  val fakeSqsEvent = buildSqsEvent(fileId, consignmentId, cognitoUserId, bucketName)

  // Context is not used, so it's safe to pass null
  val emptyLambdaContext = null


  new Lambda().process(fakeSqsEvent, emptyLambdaContext)

  private def wipeConsignmentDirectory(downloadRootPath: String, consignmentId: UUID): Unit = {
    val consignmentDirectory = new Directory(new File(s"$downloadRootPath/$consignmentId"))
    consignmentDirectory.deleteRecursively()
  }

  private def buildSqsEvent(fileId: UUID, consignmentId: UUID, cognitoUserId: String, bucketName: String) = {
    val body: String = buildMessageBody(fileId, consignmentId, cognitoUserId, bucketName)
    val snsMessage: String = buildSnsMessage(body)

    val sqsMessage = new SQSMessage
    sqsMessage.setBody(snsMessage)

    val fakeSqsEvent = new SQSEvent
    fakeSqsEvent.setRecords(List(sqsMessage).asJava)

    fakeSqsEvent
  }

  private def buildMessageBody(fileId: UUID, consignmentId: UUID, cognitoUserId: String, bucketName: String): String = {
    val s3ObjectKey = s"$cognitoUserId/$consignmentId/$fileId"

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
