import io.circe.*

/** tapir codegen only supports string enums, so integer enums become `type X = Int`; this restores their names. */
object EnumConstantsGenerator {

  def render(spec: Json, pkg: String, modelsObject: String): String = {
    val enums = SpecNormalizer.schemas(spec).toList.flatMap { case (name, s) =>
      val c = s.hcursor
      for {
        values <- c.get[List[Long]]("enum").toOption if c.get[String]("type").contains("integer")
        names <- c.get[List[String]]("x-enumNames").toOption if names.size == values.size
      } yield renderEnum(name, modelsObject, names.zip(values))
    }
    s"package $pkg.enums\n\nimport $pkg.$modelsObject\n\n${enums.mkString("\n")}"
  }

  private def renderEnum(name: String, modelsObject: String, members: List[(String, Long)]): String = {
    val vals = members.map { case (n, v) => s"  final val ${ident(n)}: $modelsObject.$name = $v" }
    val all = members.map { case (n, _) => ident(n) }.mkString(", ")
    val names = members.map { case (n, v) => s"$v -> ${Json.fromString(n).noSpaces}" }.mkString(", ")
    s"""object $name {
       |${vals.mkString("\n")}
       |  val values: List[$modelsObject.$name] = List($all)
       |  val names: Map[$modelsObject.$name, String] = Map($names)
       |}
       |""".stripMargin
  }

  // Scala keywords are all lowercase, so a capitalized plain identifier can never clash with one.
  private def ident(n: String): String =
    if (n.matches("[A-Z][A-Za-z0-9_]*")) n else s"`$n`"
}
