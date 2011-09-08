import sbt._

class Project(info: ProjectInfo) extends DefaultProject(info) { 
  val (scalacheckVersion, specsVersion) = buildScalaVersion match {
    case "2.9.1" => ("1.9", "1.6.9")
    case "2.8.1" => ("1.8", "1.6.8")
    case x => error("Unsupported Scala version " + x)
  }

  val joda       = "joda-time" % "joda-time" % "1.6.2"
  val slf4j_api  = "org.slf4j" % "slf4j-api" % "1.6.1"
  val slf4j      = "org.slf4j" % "slf4j-log4j12" % "1.6.1"
  val json       = "net.liftweb" %% "lift-json" % "2.4-M4"
  val http       = "com.ning" % "async-http-client" % "1.4.0"
  val junit      = "junit" % "junit" % "4.8.2" % "test"
  val scalacheck = "org.scala-tools.testing" %% "scalacheck" % scalacheckVersion % "test"
  val specs      = "org.scala-tools.testing" %% "specs" % specsVersion % "test"
  val mock       = "org.mockito" % "mockito-all" % "1.8.5" % "test"
}
