import Dependencies._

ThisBuild / scalaVersion     := "2.13.8"
ThisBuild / version          := "0.1.0-SNAPSHOT"
ThisBuild / organization     := "com.example"
ThisBuild / organizationName := "example"

resolvers ++= Seq[Resolver](
  "TDR Releases" at "s3://tdr-releases-mgmt"
)

lazy val root = (project in file("."))
  .settings(
    name := "tdr-download-files",
    libraryDependencies ++=
      Seq(
        circeCore,
        circeGeneric,
        circeParser,
        lambdaJavaCore,
        lambdaJavaEvents,
        awsUtils,
        authUtils,
        generatedGraphql,
        graphqlClient,
        scalaLogging,
        logback,
        logstashLogbackEncoder,
        scalaTest % Test,
        mockito % Test,
        s3Mock % Test,
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