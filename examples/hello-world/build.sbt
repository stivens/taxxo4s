scalaVersion := "3.9.0"
name := "taxxo4s-hello-world"

libraryDependencies ++= Seq(
  // CI points this at the build it just published locally, so a missed version bump can't silently test the last release.
  "io.github.stivens" %% "taxxo4s" % sys.env.getOrElse("TAXXO4S_VERSION", "0.1.0"),
  "org.scalameta" %% "munit" % "1.3.6" % Test
)
