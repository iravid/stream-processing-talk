scalaVersion := "2.11.8"

organization := "bigpanda"

name := "stream-processing-talk"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-stream" % "2.4.10",
  "com.sksamuel.elastic4s" %% "elastic4s-core" % "1.7.5",
  "com.typesafe.akka" %% "akka-stream-kafka" % "0.12",
  "com.typesafe.play" %% "play-json" % "2.5.8",
  "nl.grons" %% "metrics-scala" % "3.5.4_a2.3"
)
