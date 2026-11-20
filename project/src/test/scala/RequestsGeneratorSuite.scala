import io.circe.*
import Specs.*

class RequestsGeneratorSuite extends munit.FunSuite {

  private def render(spec: Json): Map[String, String] = RequestsGenerator.render(spec, "my", "my.raw", "my.raw.Models")
  private def render(paths: List[PathOp]): Map[String, String] = render(document(paths))

  private def member(spec: Json): String =
    render(spec).values.flatMap(_.linesIterator).collect {
      case l if l.startsWith("  def ") || l.startsWith("  lazy val ") => l.trim
    }.toList match {
      case List(line) => line
      case other      => fail(s"expected one member, got $other")
    }

  private def member(paths: List[PathOp], schemas: List[(String, Json)] = Nil): String = member(document(paths, schemas))

  private def unsupported(op: PathOp): Option[String] = RequestsGenerator.unsupported(document(List(op))).values.headOption

  test("each tag becomes one object in the public package") {
    val out = render(
      List(
        get("/api/public/a", operation("ApiPublicAGet", tag = "Documents")),
        get("/api/public/b", operation("ApiPublicBGet", tag = "Documents")),
        get("/api/public/c", operation("ApiPublicCGet", tag = "Invoices"))
      )
    )
    assertEquals(out.keySet, Set("Documents", "Invoices"))
    assert(out("Documents").startsWith("package my\n\nimport taxxo4s.TaxxoRequest\n\nobject Documents {\n"), out("Documents"))
    assertEquals(out("Documents").linesIterator.count(_.startsWith("  lazy val ")), 2)
  }

  test("an operation without inputs is a request value") {
    assertEquals(
      member(List(get("/api/public/status", operation("ApiPublicStatusGet")))),
      "lazy val ApiPublicStatusGet = TaxxoRequest(_root_.my.raw.General.ApiPublicStatusGet, ())"
    )
  }

  test("required parameters come first, but the input tuple keeps tapir's order: path, query, body") {
    val op = operation(
      "ApiPublicThingPut",
      parameters = List(query("note", string), path("b"), query("year", int, required = true), path("a")),
      requestBody = Some(body(ref("Thing")))
    )
    assertEquals(
      member(List(put("/api/public/{a}/x/{b}", op)), List("Thing" -> obj)),
      "def ApiPublicThingPut(a: Int, b: Int, year: Int, body: _root_.my.raw.Models.Thing, note: Option[String] = None) = " +
        "TaxxoRequest(_root_.my.raw.General.ApiPublicThingPut, (a, b, note, year, body))"
    )
  }

  test("query parameter types follow requiredness and array explosion") {
    val params = List(
      query("req", string, required = true),
      query("opt", string),
      query("tags", array(string)),
      query("ids", array(int), required = true),
      query("csv", array(string)).deepMerge(Json.obj("explode" -> Json.False))
    )
    assertEquals(
      member(List(get("/api/public/q", operation("ApiPublicQGet", parameters = params)))),
      "def ApiPublicQGet(req: String, ids: List[Int], opt: Option[String] = None, tags: List[String] = Nil, csv: Option[List[String]] = None) = " +
        "TaxxoRequest(_root_.my.raw.General.ApiPublicQGet, (req, opt, tags, ids, csv))"
    )
  }

  test("scalar types map like tapir codegen maps them") {
    val params = List(
      query("i", int, required = true),
      query("l", long, required = true),
      query("d", double, required = true),
      query("b", boolean, required = true),
      query("day", date, required = true),
      query("at", dateTime, required = true),
      query("kind", ref("Kind"), required = true),
      query("kinds", array(ref("Kind")), required = true)
    )
    val signature = member(
      List(get("/api/public/t", operation("ApiPublicTGet", parameters = params))),
      List("Kind" -> json("""{"type": "integer", "enum": [1]}"""))
    )
    assert(
      signature.startsWith(
        "def ApiPublicTGet(i: Int, l: Long, d: Double, b: Boolean, day: java.time.LocalDate, at: java.time.Instant, " +
          "kind: _root_.my.raw.Models.Kind, kinds: List[_root_.my.raw.Models.Kind])"
      ),
      signature
    )
  }

  test("a body is Option only when it is optional or nullable") {
    val thing = "Thing" -> obj
    val nullableThing = "NullableThing" -> json("""{"type": "object", "nullable": true}""")
    def signature(b: Json) =
      member(List(post("/api/public/t", operation("ApiPublicTPost", requestBody = Some(b)))), List(thing, nullableThing))
    assert(signature(body(ref("Thing"))).startsWith("def ApiPublicTPost(body: _root_.my.raw.Models.Thing)"))
    assert(
      signature(body(ref("Thing"), required = false)).startsWith("def ApiPublicTPost(body: Option[_root_.my.raw.Models.Thing] = None)")
    )
    assert(signature(body(ref("NullableThing"))).startsWith("def ApiPublicTPost(body: Option[_root_.my.raw.Models.NullableThing] = None)"))
    assert(signature(body(json("""{"type": "string", "nullable": true}"""))).startsWith("def ApiPublicTPost(body: Option[String] = None)"))
    assert(signature(body(array(ref("Thing")))).startsWith("def ApiPublicTPost(body: List[_root_.my.raw.Models.Thing])"))
  }

  test("parameter names that are not Scala identifiers are backticked") {
    val params = List(query("type", string, required = true), query("page-size", int, required = true))
    assertEquals(
      member(List(get("/api/public/k", operation("ApiPublicKGet", parameters = params)))),
      "def ApiPublicKGet(`type`: String, `page-size`: Int) = TaxxoRequest(_root_.my.raw.General.ApiPublicKGet, (`type`, `page-size`))"
    )
  }

  test("parameters declared on the path or in components are inputs too") {
    val op = operation("ApiPublicItemGet").deepMerge(Json.obj("parameters" -> json("""[{"$ref": "#/components/parameters/Lang"}]""")))
    val spec = document(List(get("/api/public/item/{id}", op)))
      .deepMerge(Json.obj("paths" -> Json.obj("/api/public/item/{id}" -> Json.obj("parameters" -> Json.arr(path("id"))))))
      .deepMerge(Json.obj("components" -> Json.obj("parameters" -> Json.obj("Lang" -> query("lang", string)))))
    assertEquals(
      member(spec),
      "def ApiPublicItemGet(id: Int, lang: Option[String] = None) = TaxxoRequest(_root_.my.raw.General.ApiPublicItemGet, (id, lang))"
    )
  }

  test("an operation it cannot build is reported with the reason") {
    val cases = List(
      "no tag" -> get("/api/public/a", operation("A").mapObject(_.remove("tags"))),
      "unsupported header parameter X-Id" -> get("/api/public/a", operation("A", parameters = List(param("X-Id", "header", string)))),
      "unsupported cookie parameter c" -> get("/api/public/a", operation("A", parameters = List(param("c", "cookie", string)))),
      "no parameter for {id}" -> get("/api/public/a/{id}", operation("A")),
      "request body is not JSON" -> post("/api/public/a", operation("A", requestBody = Some(body(string, contentType = "text/plain")))),
      "two inputs named `body`" -> post(
        "/api/public/a",
        operation("A", parameters = List(query("body", string)), requestBody = Some(body(string)))
      )
    )
    cases.foreach((reason, op) => assertEquals(unsupported(op), Some(reason)))
    val inlineObject = json("""{"type": "object", "properties": {"a": {"type": "string"}}}""")
    assert(
      unsupported(get("/api/public/a", operation("A", parameters = List(query("o", inlineObject))))).exists(
        _.startsWith("unsupported schema")
      )
    )
  }

  test("render refuses an operation it cannot build, naming it") {
    val e = intercept[RuntimeException](render(List(get("/api/public/a", operation("ApiPublicAGet").mapObject(_.remove("tags"))))))
    assertEquals(e.getMessage, "ApiPublicAGet: no tag")
  }

  test("buildable operations are not reported as unsupported") {
    assertEquals(unsupported(get("/api/public/a", operation("A", parameters = List(query("x", array(int)))))), None)
  }

  test("the summary and parameter descriptions become Scaladoc") {
    val op = operation(
      "ApiPublicDocGet",
      summary = "  Get documents\nof a month  ",
      parameters = List(query("month", int, description = "Month\r\n1-12"), query("plain", int), query("blank", int, description = "  "))
    )
    val source = render(List(get("/api/public/doc", op)))("General")
    assert(
      source.contains(
        """  /**
          |    * Get documents
          |    * of a month
          |    *
          |    * @param month Month 1-12
          |    */
          |  def ApiPublicDocGet(""".stripMargin
      ),
      source
    )
  }

  test("Scaladoc cannot be closed or nested early by spec text") {
    val op = operation("ApiPublicDocGet", summary = "Ends */ here and /* nests", parameters = List(query("x", int, description = "a */ b")))
    val source = render(List(get("/api/public/doc", op)))("General")
    assert(source.contains("* Ends *&#47; here and /&#42; nests"), source)
    assert(source.contains("* @param x a *&#47; b"), source)
    assertEquals(source.sliding(2).count(_ == "*/"), 1)
  }

  test("parameter docs without a summary, and no Scaladoc at all without text") {
    val withParam = render(List(get("/api/public/a", operation("ApiPublicAGet", parameters = List(query("x", int, description = "X"))))))
    assert(withParam("General").contains("  /**\n    * @param x X\n    */\n  def ApiPublicAGet("), withParam("General"))
    val bare = render(List(get("/api/public/a", operation("ApiPublicAGet", summary = " "))))
    assert(!bare("General").contains("/**"), bare("General"))
  }
}
