val tapirVersion = "1.14.1"
val sttpVersion = "4.1.2"
val jsoniterVersion = "2.42.3"

ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "io.github.stivens"
ThisBuild / version := "0.0.1"
ThisBuild / semanticdbEnabled := true

// The root's scalafmtRepo already covers every subproject's files.
scalafmtRepo / aggregate := false
scalafmtCheckRepo / aggregate := false

val rawSpec = settingKey[File]("Vendored Taxxo OpenAPI spec, as downloaded")
val fetchSpec = taskKey[Unit]("Download the latest Taxxo OpenAPI spec into spec/")
val normalizeSpec = taskKey[File]("Rewrite the raw spec into a form tapir codegen accepts")
val generateEnumConstants = taskKey[Seq[File]]("Generate named constants for the spec's integer enums")

lazy val core = project
  .enablePlugins(OpenapiCodegenPlugin)
  .settings(
    name := "taxxo4s",
    scalacOptions ++= Seq("-feature", "-deprecation", "-Wunused:all", "-Wconf:src=src_managed/.*:s"),
    rawSpec := (ThisBuild / baseDirectory).value / "spec" / "taxxo-v1.raw.json",
    openapiSwaggerFile := target.value / "openapi" / "taxxo-v1.json",
    normalizeSpec := Def.uncached {
      val normalized = SpecNormalizer.normalize(SpecNormalizer.load(rawSpec.value.toPath))
      normalized.droppedOperations.foreach(op =>
        streams.value.log.warn(s"Skipping unsupported operation $op (object-typed query parameter)")
      )
      IO.write(openapiSwaggerFile.value, normalized.spec.spaces2)
      openapiSwaggerFile.value
    },
    generateEnumConstants := Def.uncached {
      val spec = SpecNormalizer.load(normalizeSpec.value.toPath)
      val out = (Compile / sourceManaged).value / "taxxo-enums" / "TaxxoEnums.scala"
      IO.write(out, EnumConstantsGenerator.render(spec, openapiPackage.value, openapiObject.value))
      Seq(out)
    },
    Compile / generateTapirDefinitions := (Compile / generateTapirDefinitions).dependsOn(normalizeSpec).value,
    openapiPackage := "taxxo4s.generated",
    openapiObject := "TaxxoEndpoints",
    openapiJsonSerdeLib := "jsoniter",
    openapiUseHeadTagForObjectName := true,
    // Generated validators run while decoding responses, so server data breaking a spec length limit would fail the call.
    openapiDisableValidatorGeneration := true,
    Compile / sourceGenerators += generateEnumConstants.taskValue,
    fetchSpec := Def.uncached {
      IO.writeFileAtomically(rawSpec.value) { staging =>
        sbt.io.Using.urlInputStream(uri("https://platforma2.taxxo.pl/swagger/v1/swagger.json").toURL)(IO.transfer(_, staging))
        // Throws on a download that doesn't parse or normalize, so it never replaces the vendored spec.
        SpecNormalizer.normalize(SpecNormalizer.load(staging.toPath))
      }
      streams.value.log.info(s"Saved ${rawSpec.value}")
    },
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.tapir" %% "tapir-core" % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-jsoniter-scala" % tapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-sttp-client4" % tapirVersion,
      "com.softwaremill.sttp.client4" %% "core" % sttpVersion,
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % jsoniterVersion,
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % jsoniterVersion % "provided",
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-circe" % jsoniterVersion,
      "org.scalameta" %% "munit" % "1.3.6" % Test,
      "com.softwaremill.sttp.client4" %% "cats" % sttpVersion % Test,
      "org.typelevel" %% "cats-effect" % "3.7.1" % Test,
      "org.typelevel" %% "munit-cats-effect" % "2.2.1" % Test,
      "com.softwaremill.sttp.client4" %% "zio" % sttpVersion % Test,
      "dev.zio" %% "zio-test" % "2.1.27" % Test,
      "dev.zio" %% "zio-test-sbt" % "2.1.27" % Test
    )
  )
