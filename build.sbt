ThisBuild / scalaVersion := "3.8.4"
ThisBuild / organization := "io.konsume"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalacOptions ++= Seq("-deprecation", "-feature", "-Wunused:all")

val circeVersion = "0.14.15"

lazy val root = (project in file("."))
  .aggregate(core, classfile, bench)
  .settings(publish / skip := true)

lazy val core = (project in file("core"))
  .settings(
    name := "konsume-core",
    libraryDependencies ++= Seq(
      // The staging compiler must be exactly the same version as the Scala compiler.
      "org.scala-lang" %% "scala3-staging" % scalaVersion.value,
      "io.circe"       %% "circe-core"     % circeVersion,
      "io.circe"       %% "circe-parser"   % circeVersion,
      "org.apache.avro" % "avro"           % "1.12.1",
      "org.scalameta"  %% "munit"          % "1.2.4" % Test
    ),
    // The staging compiler builds its classpath from java.class.path: it does not
    // see sbt's layered class loaders, so anything that runs staged code must fork.
    Test / fork := true,
    run / fork  := true
  )

// A Java-only artifact: no scala-library, staging compiler, Kafka client or JMH dependency.
lazy val classfile = (project in file("classfile"))
  .settings(
    name := "konsume-classfile",
    autoScalaLibrary := false,
    crossPaths := false,
    javacOptions ++= Seq("--release", "24", "-Xlint:all"),
    libraryDependencies ++= Seq(
      "org.apache.avro" % "avro" % "1.12.1",
      "junit" % "junit" % "4.13.2" % Test,
      "com.github.sbt" % "junit-interface" % "0.13.3" % Test
    ),
    Test / fork := true
  )

lazy val bench = (project in file("bench"))
  .dependsOn(core, classfile)
  .enablePlugins(JmhPlugin)
  .settings(
    publish / skip := true,
    libraryDependencies += "org.scalameta" %% "munit" % "1.2.4" % Test,
    Test / fork := true
  )
