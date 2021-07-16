package uk.gov.nationalarchives.downloadfiles

case class FailedDownloadException(sqsReceiptHandle: String, message: String, cause: Throwable)
  extends RuntimeException(message, cause)
