import sbt._

object Dependencies {
  private val awsUtilsVersion = "0.1.55"

  lazy val authUtils = "uk.gov.nationalarchives" %% "tdr-auth-utils" % "0.0.60"
  lazy val awsSsm = "software.amazon.awssdk" % "ssm" % "2.17.233"
  lazy val generatedGraphql =  "uk.gov.nationalarchives" %% "tdr-generated-graphql" % "0.0.255"
  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.2.12"
  lazy val circeCore = "io.circe" %% "circe-core" % "0.14.2"
  lazy val circeGeneric = "io.circe" %% "circe-generic" % "0.14.2"
  lazy val circeParser = "io.circe" %% "circe-parser" % "0.14.2"
  lazy val lambdaJavaCore = "com.amazonaws" % "aws-lambda-java-core" % "1.2.1"
  lazy val lambdaJavaEvents = "com.amazonaws" % "aws-lambda-java-events" % "3.11.0"
  lazy val s3Utils =  "uk.gov.nationalarchives" %% "s3-utils" % awsUtilsVersion
  lazy val sqsUtils =  "uk.gov.nationalarchives" %% "sqs-utils" % awsUtilsVersion
  lazy val kmsUtils =  "uk.gov.nationalarchives" %% "kms-utils" % awsUtilsVersion
  lazy val decoderUtils =  "uk.gov.nationalarchives" %% "decoders-utils" % awsUtilsVersion
  lazy val graphqlClient = "uk.gov.nationalarchives" %% "tdr-graphql-client" % "0.0.73"
  lazy val scalaLogging = "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5"
  lazy val logback = "ch.qos.logback" % "logback-classic" % "1.2.11"
  lazy val logstashLogbackEncoder = "net.logstash.logback" % "logstash-logback-encoder" % "7.2"
  lazy val mockito = "org.mockito" %% "mockito-scala" % "1.17.7"
  lazy val typesafe = "com.typesafe" % "config" % "1.4.2"
  lazy val elasticMq = "org.elasticmq" %% "elasticmq-server" % "1.3.7"
  lazy val elasticMqSqs = "org.elasticmq" %% "elasticmq-rest-sqs" % "1.3.7"
}
