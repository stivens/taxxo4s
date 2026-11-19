import io.circe.Json
import sttp.tapir.codegen.endpoints.SimpleTypes.mapSchemaSimpleTypeToType
import sttp.tapir.codegen.openapi.models.OpenapiModels.*
import sttp.tapir.codegen.openapi.models.OpenapiSchemaType
import sttp.tapir.codegen.openapi.models.OpenapiSchemaType.*
import sttp.tapir.codegen.util.JavaEscape.quote
import sttp.tapir.codegen.util.NameHelpers.safeVariableName

/** One object per tag whose members build a `TaxxoRequest` from named parameters instead of tapir's input tuple. */
object RequestsGenerator {

  private final case class Input(name: String, tpe: String, default: Option[String], description: Option[String]) {
    def ident: String = safeVariableName(name)
  }

  private final case class Operation(tag: String, name: String, summary: Option[String], inputs: List[Input])

  private final class Unsupported(reason: String) extends RuntimeException(reason)

  def render(spec: Json, pkg: String, rawPkg: String, models: String): Map[String, String] =
    operations(spec, models).groupBy(_.tag).map((tag, ops) => tag -> renderObject(pkg, rawPkg, tag, ops))

  /** Why no builder can be generated, by path and method. */
  def unsupported(spec: Json): Map[(String, String), String] =
    attempts(spec, models = "").collect { case (url, m, Left(reason)) => (url, m.methodType) -> reason }.toMap

  // Lets a test check that each tuple is built in tapir's input order, which the types alone can't catch.
  def renderInputOrder(spec: Json, pkg: String, rawPkg: String, models: String): String = {
    val ops = operations(spec, models)
    val endpoints = ops.map(_.tag).distinct.sorted.map(t => s"    _root_.$rawPkg.$t.generatedEndpoints").mkString(",\n")
    val names = ops.map { op =>
      s"    _root_.$rawPkg.${op.tag}.${op.name} -> List(${op.inputs.map(i => quote(i.name)).mkString(", ")})"
    }
    s"""package $pkg
       |
       |private[taxxo4s] object RequestInputOrder {
       |  val endpoints: List[_root_.sttp.tapir.AnyEndpoint] = List(
       |$endpoints
       |  ).flatten
       |
       |  val names: Map[_root_.sttp.tapir.AnyEndpoint, List[String]] = Map(
       |${names.mkString(",\n")}
       |  )
       |}
       |""".stripMargin
  }

  private def operations(spec: Json, models: String): List[Operation] =
    attempts(spec, models).map((url, m, result) => result.fold(reason => sys.error(s"${m.name(url)}: $reason"), identity))

  private def attempts(spec: Json, models: String): List[(String, OpenapiPathMethod, Either[String, Operation])] = {
    val doc = spec.as[OpenapiDocument].fold(throw _, identity)
    val shared = doc.components.map(_.parameters).getOrElse(Map.empty)
    for {
      path   <- doc.paths.toList
      method <- path.methods.toList.map(_.withResolvedParentParameters(shared, path.parameters))
    } yield {
      val result = try Right(operation(path.url, method, doc, models))
      catch { case u: Unsupported => Left(u.getMessage) }
      (path.url, method, result)
    }
  }

  private def operation(url: String, m: OpenapiPathMethod, doc: OpenapiDocument, models: String): Operation = {
    def fail(msg: String): Nothing = throw new Unsupported(msg)

    // Must agree with tapir codegen's mapping; a disagreement fails compiling the generated `TaxxoRequest(…)` call.
    def scalaType(schema: OpenapiSchemaType): String = schema match {
      case OpenapiSchemaArray(items, _, _, _) => s"List[${scalaType(items)}]"
      case ref: OpenapiSchemaRef              => s"_root_.$models.${ref.stripped}"
      case simple: OpenapiSchemaSimpleType    => mapSchemaSimpleTypeToType(simple)._1
      case other                              => fail(s"unsupported schema $other")
    }

    val tag = m.tags.flatMap(_.headOption).getOrElse(fail("no tag"))
    val (pathParams, otherParams) = m.resolvedParameters.toList.partition(_.in == "path")
    otherParams.filterNot(_.in == "query").foreach(p => fail(s"unsupported ${p.in} parameter ${p.name}"))

    // tapir's input order: path captures as they appear in the URL, then the other parameters, then the body.
    val pathInputs = url.split('/').toList.filter(_.startsWith("{")).map(_.stripPrefix("{").stripSuffix("}")).map { n =>
      val p = pathParams.find(_.name == n).getOrElse(fail(s"no parameter for {$n}"))
      Input(p.name, scalaType(p.schema.`type`), None, text(p.description))
    }

    val queryInputs = otherParams.map { p =>
      val tpe = scalaType(p.schema.`type`)
      val (wrapped, default) = p.schema.`type` match {
        case _ if p.required.contains(true)        => (tpe, None)
        case _: OpenapiSchemaArray if p.isExploded => (tpe, Some("Nil"))
        case _                                     => optional(tpe)
      }
      Input(p.name, wrapped, default, text(p.description))
    }

    val bodyInput = m.requestBody.map(_.resolve(doc)).map { b =>
      val json = b.content.find(_.contentType == "application/json").getOrElse(fail("request body is not JSON"))
      val tpe = scalaType(json.schema)
      // tapir codegen also makes a required body optional when its schema is nullable.
      val nullable = json.schema.nullable || (json.schema match {
        case ref: OpenapiSchemaRef => doc.components.flatMap(_.schemas.get(ref.stripped)).exists(_.nullable)
        case _                     => false
      })
      val (wrapped, default) = if (b.required && !nullable) (tpe, None) else optional(tpe)
      Input("body", wrapped, default, text(b.description))
    }

    val inputs = pathInputs ++ queryInputs ++ bodyInput
    inputs.groupBy(_.name).collect { case (n, dups) if dups.size > 1 => n }.foreach(n => fail(s"two inputs named `$n`"))
    Operation(tag, m.name(url), text(m.summary), inputs)
  }

  private def optional(tpe: String): (String, Option[String]) = (s"Option[$tpe]", Some("None"))

  private def text(s: Option[String]): Option[String] = s.map(_.trim).filter(_.nonEmpty)

  private def renderObject(pkg: String, rawPkg: String, tag: String, ops: List[Operation]): String =
    s"""package $pkg
       |
       |import taxxo4s.TaxxoRequest
       |
       |object $tag {
       |
       |${ops.map(renderOperation(s"_root_.$rawPkg.$tag", _)).mkString("\n")}}
       |""".stripMargin

  private def renderOperation(rawObject: String, op: Operation): String = {
    val endpoint = s"$rawObject.${op.name}"
    val doc = scaladoc(op.summary, op.inputs.flatMap(i => i.description.map(i.ident -> _)))
    val request = s"TaxxoRequest($endpoint, ${op.inputs.map(_.ident).mkString("(", ", ", ")")})"
    op.inputs match {
      case Nil    => s"$doc  lazy val ${op.name} = $request\n"
      case inputs =>
        val params = inputs.sortBy(_.default.isDefined).map { i =>
          s"${i.ident}: ${i.tpe}${i.default.fold("")(d => s" = $d")}"
        }
        s"$doc  def ${op.name}(${params.mkString(", ")}) = $request\n"
    }
  }

  private def scaladoc(summary: Option[String], params: List[(String, String)]): String = {
    def lines(s: String) = s.replace("*/", "*&#47;").replace("/*", "/&#42;").split("\\r?\\n").map(_.trim).toList
    val paramLines = params.map((n, d) => s"@param $n ${lines(d).filter(_.nonEmpty).mkString(" ")}")
    val body = summary.toList.flatMap(lines) ++ Option.when(summary.nonEmpty && paramLines.nonEmpty)("") ++ paramLines
    if (body.isEmpty) ""
    else body.map(l => if (l.isEmpty) "    *" else s"    * $l").mkString("  /**\n", "\n", "\n    */\n")
  }
}
