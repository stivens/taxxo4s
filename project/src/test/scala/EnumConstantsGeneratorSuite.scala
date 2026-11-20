import Specs.*

class EnumConstantsGeneratorSuite extends munit.FunSuite {

  private def render(schemas: (String, String)*): String =
    EnumConstantsGenerator.render(document(Nil, schemas.toList.map((n, s) => n -> json(s))), "my.enums", "my.Models")

  test("an integer enum with names becomes an object of typed constants, values and names") {
    val out = render("Status" -> """{"type": "integer", "enum": [0, 1, 2], "x-enumNames": ["OK", "Error", "Warning"]}""")
    assertNoDiff(
      out,
      """package my.enums
        |
        |object Status {
        |  final val OK: _root_.my.Models.Status = 0
        |  final val Error: _root_.my.Models.Status = 1
        |  final val Warning: _root_.my.Models.Status = 2
        |  val values: List[_root_.my.Models.Status] = _root_.scala.List(OK, Error, Warning)
        |  val names: Map[_root_.my.Models.Status, String] = _root_.scala.Predef.Map(OK -> "OK", Error -> "Error", Warning -> "Warning")
        |}
        |""".stripMargin
    )
  }

  test("negative values stay Int literals, values outside Int range become Long literals") {
    val out = render("Big" -> """{"type": "integer", "enum": [-1, 2147483648], "x-enumNames": ["None", "Huge"]}""")
    assert(out.contains("final val None: _root_.my.Models.Big = -1\n"), out)
    assert(out.contains("final val Huge: _root_.my.Models.Big = 2147483648L\n"), out)
  }

  test("keyword and non-identifier names are backticked, and names keeps the original text quoted") {
    val out = render("Kind" -> """{"type": "integer", "enum": [1, 2], "x-enumNames": ["type", "Not \"ok\""]}""")
    assert(out.contains("final val `type`: _root_.my.Models.Kind = 1"), out)
    assert(out.contains("final val `Not \"ok\"`: _root_.my.Models.Kind = 2"), out)
    assert(out.contains("""`type` -> "type", `Not "ok"` -> "Not \"ok\"""""), out)
  }

  test("string enums and enums without one name per value are skipped") {
    val out = render(
      "Str" -> """{"type": "string", "enum": ["a"], "x-enumNames": ["A"]}""",
      "NoNames" -> """{"type": "integer", "enum": [1]}""",
      "TooFewNames" -> """{"type": "integer", "enum": [1, 2], "x-enumNames": ["One"]}"""
    )
    assertNoDiff(out, "package my.enums")
  }

  test("a member named like a generated member is rejected") {
    List("values", "names").foreach { member =>
      val e = intercept[RuntimeException](render("Clash" -> s"""{"type": "integer", "enum": [1], "x-enumNames": ["$member"]}"""))
      assert(e.getMessage.contains(s"Enum Clash has a member named `$member`"), e.getMessage)
    }
  }
}
