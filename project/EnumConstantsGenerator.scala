import io.circe.*

/** tapir codegen only supports string enums, so integer enums become `type X = Int`; this restores their names. */
object EnumConstantsGenerator {

  def render(spec: Json, pkg: String, modelsObject: String): String = {
    val enums = SpecNormalizer.schemas(spec).toList.flatMap { case (name, s) =>
      val c = s.hcursor
      for {
        values <- c.get[List[Long]]("enum").toOption if c.get[String]("type").contains("integer")
        names <- c.get[List[String]]("x-enumNames").toOption if names.size == values.size
      } yield renderEnum(name, s"_root_.$pkg.$modelsObject", names.zip(values))
    }
    s"package $pkg.enums\n\n${enums.mkString("\n")}"
  }

  private val generatedMembers = Set("values", "names")

  // A member is a val in the same scope, so every term the body names must be fully qualified.
  private def renderEnum(name: String, models: String, members: List[(String, Long)]): String = {
    members.map(_._1).find(generatedMembers).foreach { n =>
      sys.error(s"Enum $name has a member named `$n`, which clashes with the generated `$n`")
    }
    val vals = members.map { case (n, v) => s"  final val ${ident(n)}: $models.$name = ${literal(v)}" }
    val all = members.map { case (n, _) => ident(n) }.mkString(", ")
    val names = members.map { case (n, _) => s"${ident(n)} -> ${Json.fromString(n).noSpaces}" }.mkString(", ")
    s"""object $name {
       |${vals.mkString("\n")}
       |  val values: List[$models.$name] = _root_.scala.List($all)
       |  val names: Map[$models.$name, String] = _root_.scala.Predef.Map($names)
       |}
       |""".stripMargin
  }

  private def literal(v: Long): String = if (v.isValidInt) v.toString else s"${v}L"

  // Scala keywords are all lowercase, so a capitalized plain identifier can never clash with one.
  private def ident(n: String): String =
    if (n.matches("[A-Z][A-Za-z0-9_]*")) n else s"`$n`"
}
