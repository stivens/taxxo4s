import java.nio.file.{Files, Path, Paths}

import io.circe.*
import Specs.*
import SpecNormalizer.normalize

class SpecNormalizerSuite extends munit.FunSuite {

  private def rejection(spec: Json): String = intercept[IllegalArgumentException](normalize(spec)).getMessage

  private def schema(spec: Json, name: String): Json =
    SpecNormalizer.schemas(spec)(name).getOrElse(fail(s"no schema $name"))

  private val status = get("/api/public/status", operation("ApiPublicStatusGet"))
  private val minimal = withSession(List(status))

  test("a document without an openapi version is rejected") {
    val spec = minimal.mapObject(_.remove("openapi"))
    assert(rejection(spec).contains("not an OpenAPI document"))
  }

  test("a document without component schemas is rejected") {
    val spec = minimal.mapObject(_.remove("components"))
    assert(rejection(spec).contains("no component schemas"))
  }

  test("a session operation that no longer matches what TaxxoClient sends or decodes is rejected") {
    val changes = List(
      ("/api/public/authenticate", "post", "requestBody", body(int)),
      ("/api/public/authenticate", "post", "responses", responses(ref("APIResponse"))),
      ("/api/public/authenticate", "post", "parameters", Json.arr()),
      ("/api/public/logout", "get", "responses", responses(ref("StringAPIDataResponse")))
    )
    changes.foreach { (url, method, key, value) =>
      val spec = minimal.deepMerge(Json.obj("paths" -> Json.obj(url -> Json.obj(method -> Json.obj(key -> value)))))
      assert(rejection(spec).contains(s"the spec's $url no longer matches"), s"$url $key")
    }
  }

  test("a session operation that only gained parameters or responses is accepted") {
    val unauthorized = Json.obj("responses" -> Json.obj("401" -> Json.obj("description" -> Json.fromString("Unauthorized"))))
    val extra = Json.obj(
      "paths" -> Json.obj(
        "/api/public/authenticate" -> Json.obj(
          "post" -> Json.obj("parameters" -> Json.arr(query("culture", string), query("extra", string))).deepMerge(unauthorized)
        ),
        "/api/public/logout" -> Json.obj("get" -> unauthorized)
      )
    )
    normalize(minimal.deepMerge(extra))
  }

  test("login and logout operations are dropped, but their response models still count as responses") {
    val spec = withSession(
      List(get("/api/public/authenticateWithUser", operation("ApiPublicAuthenticateWithUserGet", response = ref("UserLogin"))), status),
      List("UserLogin" -> json("""{"type": "object", "required": ["Login"], "properties": {"Login": {"type": "string"}}}"""))
    )
    val normalized = normalize(spec)
    assertEquals(normalized.spec.at("paths").keyList, List("/api/public/status"))
    assertEquals(normalized.droppedOperations, Nil)
    assert(schema(normalized.spec, "UserLogin").at("required").failed)
  }

  test("operations with an object-typed query parameter are dropped and reported, whatever shape the object takes") {
    val shapes = List(
      "inline object" -> obj,
      "ref to object" -> ref("Filter"),
      "array of refs" -> array(ref("Filter")),
      "allOf ref" -> Json.obj("allOf" -> Json.arr(ref("Filter"))),
      "ref to ref" -> ref("FilterAlias")
    )
    val schemas = List(
      "Filter" -> json("""{"type": "object", "properties": {"Field": {"type": "string"}}}"""),
      "FilterAlias" -> json("""{"allOf": [{"$ref": "#/components/schemas/Filter"}]}""")
    )
    shapes.foreach { (shape, s) =>
      val spec = withSession(
        List(
          get("/api/public/filtered", operation("ApiPublicFilteredGet", parameters = List(query("filter", s)))),
          post("/api/public/filtered", operation("ApiPublicFilteredPost"))
        ),
        schemas
      )
      val normalized = normalize(spec)
      assertEquals(normalized.droppedOperations, List("ApiPublicFilteredGet (object-typed query parameter)"), shape)
      assertEquals(normalized.spec.at("paths", "/api/public/filtered").keyList, List("post"), shape)
    }
  }

  test("a path whose only operation is dropped disappears") {
    val spec = withSession(
      List(get("/api/public/filtered", operation("ApiPublicFilteredGet", parameters = List(query("f", obj)))))
    )
    assert(normalize(spec).spec.at("paths", "/api/public/filtered").failed)
  }

  test("scalar and scalar-array query parameters are kept") {
    val params = List(query("a", int), query("b", array(string)), query("c", ref("Kind")))
    val spec = withSession(
      List(get("/api/public/scalars", operation("ApiPublicScalarsGet", parameters = params))),
      List("Kind" -> json("""{"type": "integer", "enum": [1]}"""))
    )
    assertEquals(normalize(spec).droppedOperations, Nil)
  }

  test("operations the request builder generator cannot build are dropped and reported with its reason") {
    val header = get("/api/public/header", operation("ApiPublicHeaderGet", parameters = List(param("X-Trace", "header", string))))
    val normalized = normalize(withSession(List(header, status)))
    assertEquals(normalized.droppedOperations, List("ApiPublicHeaderGet (unsupported header parameter X-Trace)"))
    assert(normalized.spec.at("paths", "/api/public/header").failed)
  }

  test("tags become identifiers") {
    val tags = List("Tags and Allocations", "Documents - with KSeF", "accountant/XAI", "Bank")
    val spec = withSession(tags.zipWithIndex.map((t, i) => get(s"/api/public/t$i", operation(s"Op$i", tag = t))))
    val normalized = normalize(spec).spec
    val actual = tags.indices.toList.flatMap(i => normalized.at("paths", s"/api/public/t$i", "get", "tags").as[List[String]].toOption.get)
    assertEquals(actual, List("TagsAndAllocations", "Documents", "AccountantXAI", "Bank"))
  }

  test("only date-time query parameters documented as yyyy-MM-dd become dates") {
    val params = List(
      query("day", dateTime, description = "Day, format yyyy-MM-dd"),
      query("moment", dateTime, description = "Exact moment"),
      query("label", string, description = "Free text, format yyyy-MM-dd")
    )
    val spec = withSession(List(get("/api/public/dates", operation("ApiPublicDatesGet", parameters = params))))
    val formats = normalize(spec).spec.at("paths", "/api/public/dates", "get", "parameters").values.toList.flatten.map { p =>
      p.at("schema", "format").as[String].toOption
    }
    assertEquals(formats, List(Some("date"), Some("date-time"), None))
  }

  test("only JSON content is kept in request and response bodies") {
    val multi = Json.obj(
      "application/json" -> Json.obj("schema" -> ref("APIResponse")),
      "text/json" -> Json.obj("schema" -> ref("APIResponse")),
      "application/xml" -> Json.obj("schema" -> ref("APIResponse"))
    )
    val op = operation("ApiPublicThingPost").deepMerge(
      Json.obj(
        "requestBody" -> Json.obj("required" -> Json.True, "content" -> multi),
        "responses" -> Json.obj("200" -> Json.obj("description" -> Json.fromString("OK"), "content" -> multi))
      )
    )
    val normalized = normalize(withSession(List(post("/api/public/thing", op)))).spec.at("paths", "/api/public/thing", "post")
    assertEquals(normalized.downFields("requestBody", "content").keyList, List("application/json"))
    assertEquals(normalized.downFields("responses", "200", "content").keyList, List("application/json"))
  }

  test("security requirements are removed from operations and the document, and so are document tags") {
    val secured = operation("ApiPublicStatusGet").deepMerge(Json.obj("security" -> json("""[{"Bearer": []}]""")))
    val spec = withSession(List(get("/api/public/status", secured)))
      .deepMerge(Json.obj("security" -> json("""[{"Bearer": []}]"""), "tags" -> json("""[{"name": "General"}]""")))
    val normalized = normalize(spec).spec
    assertEquals(normalized.findAllByKey("security"), Nil)
    assert(normalized.at("tags").failed)
  }

  test("required is dropped from every model a response reaches, and kept on request-only models") {
    val model = (props: String) => json(s"""{"type": "object", "required": ["Name"], "properties": {"Name": {"type": "string"}$props}}""")
    val spec = withSession(
      List(
        get("/api/public/out", operation("ApiPublicOutGet", response = ref("Outer"))),
        post("/api/public/in", operation("ApiPublicInPost", requestBody = Some(body(ref("Input")))))
      ),
      List(
        "Outer" -> model(""", "Inner": {"$ref": "#/components/schemas/Middle"}"""),
        "Middle" -> model(""", "Items": {"type": "array", "items": {"allOf": [{"$ref": "#/components/schemas/Leaf"}]}}"""),
        "Leaf" -> model(""),
        "Input" -> model(""", "Shared": {"$ref": "#/components/schemas/Leaf"}""")
      )
    )
    val normalized = normalize(spec).spec
    List("Outer", "Middle", "Leaf").foreach(n => assert(schema(normalized, n).at("required").failed, n))
    assertEquals(schema(normalized, "Input").hcursor.get[List[String]]("required"), Right(List("Name")))
  }

  test("a model property named required is a field, not the keyword, and survives") {
    val spec = withSession(
      List(get("/api/public/rules", operation("ApiPublicRulesGet", response = ref("Rule")))),
      List("Rule" -> json("""{"type": "object", "properties": {"required": {"type": "boolean"}}}"""))
    )
    assert(schema(normalize(spec).spec, "Rule").at("properties", "required").succeeded)
  }

  test("date-time model fields lose their format, in response and request models alike") {
    val model = json(
      """{"type": "object", "properties": {"At": {"type": "string", "format": "date-time"}, "On": {"type": "string", "format": "date"}}}"""
    )
    val spec = withSession(
      List(
        get("/api/public/out", operation("ApiPublicOutGet", response = ref("Out"))),
        post("/api/public/in", operation("ApiPublicInPost", requestBody = Some(body(ref("In")))))
      ),
      List("Out" -> model, "In" -> model)
    )
    val normalized = normalize(spec).spec
    List("Out", "In").foreach { n =>
      assertEquals(schema(normalized, n).at("properties", "At").focus, Some(json("""{"type": "string"}""")), n)
      assertEquals(schema(normalized, n).at("properties", "On", "format").as[String], Right("date"), n)
    }
  }

  test("an allOf wrapping a single $ref becomes that $ref; other allOfs are kept") {
    val single = json("""{"allOf": [{"$ref": "#/components/schemas/Leaf"}], "description": "A leaf"}""")
    val double = json("""{"allOf": [{"$ref": "#/components/schemas/Leaf"}, {"$ref": "#/components/schemas/Leaf"}]}""")
    val inline = json("""{"allOf": [{"type": "string"}]}""")
    val spec = withSession(
      List(get("/api/public/out", operation("ApiPublicOutGet", response = ref("Holder")))),
      List(
        "Leaf" -> obj,
        "Holder" -> Json.obj("type" -> Json.fromString("object"), "properties" -> Json.obj("S" -> single, "D" -> double, "I" -> inline))
      )
    )
    val props = schema(normalize(spec).spec, "Holder").at("properties")
    assertEquals(props.downField("S").focus, Some(ref("Leaf")))
    assertEquals(props.downField("D").focus, Some(double))
    assertEquals(props.downField("I").focus, Some(inline))
  }

  private lazy val vendoredSpec: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve("spec/taxxo-v1.raw.json"))
      .find(Files.exists(_))
      .getOrElse(sys.error("spec/taxxo-v1.raw.json not found above the working directory"))

  private lazy val vendored: SpecNormalizer.Normalized = normalize(SpecNormalizer.load(vendoredSpec))

  test("vendored spec: the only skipped operation is the one documented in the README") {
    assertEquals(vendored.droppedOperations, List("ApiPublicAccountingDataBatchAccountValueByMonthGet (object-typed query parameter)"))
  }

  test("vendored spec: no login or logout operations remain") {
    val urls = vendored.spec.at("paths").keyList
    assertEquals(urls.filter(_.matches("/api/public/(authenticate.*|logout)")), Nil)
  }

  test("vendored spec: no model reachable from a response keeps required, and no model field is a date-time") {
    val schemas = SpecNormalizer.schemas(vendored.spec)
    def refs(j: Json) = j.findAllByKey("$ref").flatMap(_.asString).map(_.stripPrefix("#/components/schemas/"))
    def closure(seen: Set[String], name: String): Set[String] =
      if (seen(name)) seen else schemas(name).toList.flatMap(refs).foldLeft(seen + name)(closure)
    val roots = vendored.spec.at("paths").focus.toList.flatMap(_.findAllByKey("responses")).flatMap(refs)
    val reachable = roots.foldLeft(Set.empty[String])(closure)
    assert(reachable.size > 100, reachable.size)
    assertEquals(reachable.filter(n => schemas(n).exists(_.at("required").focus.exists(_.isArray))), Set.empty[String])
    assertEquals(Json.fromJsonObject(schemas).findAllByKey("format").filter(_ == Json.fromString("date-time")), Nil)
  }
}
