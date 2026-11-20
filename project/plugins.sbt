addSbtPlugin("com.softwaremill.sttp.tapir" % "sbt-openapi-codegen" % "1.14.1")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.10")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.2")
addSbtPlugin("com.github.sbt" % "sbt-pgp" % "2.4.0")
libraryDependencies += "io.circe" %% "circe-parser" % "0.14.17"
libraryDependencies += "org.scalameta" %% "munit" % "1.3.6" % Test
