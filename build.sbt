import Dependencies._

ThisBuild / scalaVersion     := "2.13.8"
ThisBuild / version          := "0.1.0-SNAPSHOT"
ThisBuild / organization     := "com.example"
ThisBuild / organizationName := "example"

lazy val root = (project in file("."))
  .settings(
    name := "tdr-download-files",
    libraryDependencies ++=
      Seq(
        awsSsm,
        circeCore,
        circeGeneric,
        circeParser,
        lambdaJavaCore,
        lambdaJavaEvents,
        s3Utils,
        kmsUtils,
        sqsUtils,
        decoderUtils,
        authUtils,
        generatedGraphql,
        graphqlClient,
        scalaLogging,
        logback,
        logstashLogbackEncoder,
        typesafe,
        scalaTest % Test,
        mockito % Test,
        elasticMq % Test,
        elasticMqSqs % Test
      )
  )

(assembly / assemblyJarName) := "download-files.jar"

(assembly / assemblyMergeStrategy) := {
  case PathList("META-INF", xs@_*) => MergeStrategy.discard
  case _ => MergeStrategy.first
}

(Test / fork) := true
(Test / javaOptions) += s"-Dconfig.file=${sourceDirectory.value}/test/resources/application.conf"
(Test / envVars) := Map("AWS_ACCESS_KEY_ID" -> "test", "AWS_SECRET_ACCESS_KEY" -> "test")
