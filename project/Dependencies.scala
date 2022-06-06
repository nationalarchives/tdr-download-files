import sbt._

object Dependencies {
  lazy val authUtils = "uk.gov.nationalarchives" %% "tdr-auth-utils" % "0.0.59"
  lazy val generatedGraphql =  "uk.gov.nationalarchives" %% "tdr-generated-graphql" % "0.0.243"
  lazy val scalaTest = "org.scalatest" %% "scalatest" % "3.2.12"
  lazy val circeCore = "io.circe" %% "circe-core" % "0.14.2"
  lazy val circeGeneric = "io.circe" %% "circe-generic" % "0.14.2"
  lazy val circeParser = "io.circe" %% "circe-parser" % "0.14.2"
  lazy val lambdaJavaCore = "com.amazonaws" % "aws-lambda-java-core" % "1.2.1"
  lazy val lambdaJavaEvents = "com.amazonaws" % "aws-lambda-java-events" % "3.1.0"
  lazy val awsUtils =  "uk.gov.nationalarchives" %% "tdr-aws-utils" % "0.1.31"
  lazy val graphqlClient = "uk.gov.nationalarchives" %% "tdr-graphql-client" % "0.0.37"
  lazy val scalaLogging = "com.typesafe.scala-logging" %% "scala-logging" % "3.9.5"
  lazy val logback = "ch.qos.logback" % "logback-classic" % "1.2.11"
  lazy val logstashLogbackEncoder = "net.logstash.logback" % "logstash-logback-encoder" % "7.2"
  lazy val mockito = "org.mockito" %% "mockito-scala" % "1.17.7"
  lazy val s3Mock = "io.findify" %% "s3mock" % "0.2.6"
  lazy val elasticMq = "org.elasticmq" %% "elasticmq-server" % "1.3.7"
  lazy val elasticMqSqs = "org.elasticmq" %% "elasticmq-rest-sqs" % "1.3.7"
}
