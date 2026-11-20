val tapirVersion = "1.14.1"
val sttpVersion = "4.1.3"
val jsoniterVersion = "2.43.0"
val repoUrl = uri("https://github.com/stivens/taxxo4s")

ThisBuild / scalaVersion := "3.9.0"
ThisBuild / organization := "io.github.stivens"
ThisBuild / version := "0.1.0"
ThisBuild / semanticdbEnabled := true
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / publishTo := localStaging.value
ThisBuild / homepage := Some(repoUrl)
ThisBuild / scmInfo := Some(ScmInfo(repoUrl, "scm:git@github.com:stivens/taxxo4s.git"))
ThisBuild / licenses := List(License.MIT)
ThisBuild / developers := List(
  Developer(
    id = "stivens",
    name = "Jacek Bizub",
    email = "jacekbizub@gmail.com",
    url = uri("https://github.com/stivens")
  )
)

// The root's scalafmtRepo already covers every subproject's files.
scalafmtRepo / aggregate := false
scalafmtCheckRepo / aggregate := false

val rawSpec = settingKey[File]("Vendored Taxxo OpenAPI spec, as downloaded")
val fetchSpec = taskKey[Unit]("Download the latest Taxxo OpenAPI spec into spec/")
val normalizeSpec = taskKey[File]("Rewrite the raw spec into a form tapir codegen accepts")
val generateEnumConstants = taskKey[Seq[File]]("Generate named constants for the spec's integer enums")
val generateRequests = taskKey[Seq[File]]("Generate named-parameter request builders for every endpoint")
val generateTestFixtures = taskKey[Seq[File]]("Generate the per-endpoint fixtures the generated-code tests check")
val publicPackage = "taxxo4s.generated"
val rawPackage = s"$publicPackage.raw"
val enumsPackage = s"$publicPackage.enums"
val modelsObject = "TaxxoEndpoints"
val models = s"$rawPackage.$modelsObject"

lazy val core = project
  .enablePlugins(OpenapiCodegenPlugin)
  .settings(
    name := "taxxo4s",
    description := "Scala client for the Taxxo API, generated from its OpenAPI spec",
    scalacOptions ++= Seq("-feature", "-deprecation", "-Wunused:all", "-Wconf:src=src_managed/.*:s"),
    rawSpec := (ThisBuild / baseDirectory).value / "spec" / "taxxo-v1.raw.json",
    openapiSwaggerFile := target.value / "openapi" / "taxxo-v1.json",
    normalizeSpec := Def.uncached {
      val normalized = SpecNormalizer.normalize(SpecNormalizer.load(rawSpec.value.toPath))
      normalized.droppedOperations.foreach(op => streams.value.log.warn(s"Skipping unsupported operation $op"))
      IO.write(openapiSwaggerFile.value, normalized.spec.spaces2)
      openapiSwaggerFile.value
    },
    generateEnumConstants := Def.uncached {
      val spec = SpecNormalizer.load(normalizeSpec.value.toPath)
      val out = (Compile / sourceManaged).value / "taxxo-enums" / "TaxxoEnums.scala"
      IO.write(out, EnumConstantsGenerator.render(spec, enumsPackage, models))
      Seq(out)
    },
    generateRequests := Def.uncached {
      val spec = SpecNormalizer.load(normalizeSpec.value.toPath)
      val dir = (Compile / sourceManaged).value / "taxxo-requests"
      IO.delete(dir)
      RequestsGenerator.render(spec, publicPackage, rawPackage, models).toList.map { (name, source) =>
        val out = dir / s"$name.scala"
        IO.write(out, source)
        out
      }
    },
    generateTestFixtures := Def.uncached {
      val spec = SpecNormalizer.load(normalizeSpec.value.toPath)
      val out = (Test / sourceManaged).value / "taxxo-requests" / "EndpointFixtures.scala"
      IO.write(out, RequestsGenerator.renderTestFixtures(spec, publicPackage, rawPackage, models))
      Seq(out)
    },
    Compile / generateTapirDefinitions := (Compile / generateTapirDefinitions).dependsOn(normalizeSpec).value,
    openapiPackage := rawPackage,
    openapiObject := modelsObject,
    openapiJsonSerdeLib := "jsoniter",
    openapiUseHeadTagForObjectName := true,
    // Generated validators run while decoding responses, so server data breaking a spec length limit would fail the call.
    openapiDisableValidatorGeneration := true,
    Compile / sourceGenerators += generateEnumConstants.taskValue,
    Compile / sourceGenerators += generateRequests.taskValue,
    Test / sourceGenerators += generateTestFixtures.taskValue,
    fetchSpec := Def.uncached {
      IO.writeFileAtomically(rawSpec.value) { staging =>
        sbt.io.Using.urlInputStream(uri("https://platforma2.taxxo.pl/swagger/v1/swagger.json").toURL)(IO.transfer(_, staging))
        // Throws on a download that doesn't parse, normalize or yield enum constants, before it replaces the vendored spec.
        val normalized = SpecNormalizer.normalize(SpecNormalizer.load(staging.toPath)).spec
        EnumConstantsGenerator.render(normalized, enumsPackage, models)
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
