import Dependencies._

ThisBuild / scalaVersion     := "2.13.3"
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
        scalaTest % Test,
        mockito % Test,
        s3Mock % Test,
        sqsMock % Test
      )
  )

assemblyJarName in assembly := "download-files.jar"

assemblyMergeStrategy in assembly := {
  case PathList("META-INF", xs@_*) => MergeStrategy.discard
  case _ => MergeStrategy.first
}

fork in Test := true
javaOptions in Test += s"-Dconfig.file=${sourceDirectory.value}/test/resources/application.conf"