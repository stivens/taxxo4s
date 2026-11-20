import io.circe.*
import io.circe.parser.parse

object Specs {

  type PathOp = (String, (String, Json))

  def json(s: String): Json = parse(s).fold(throw _, identity)

  def ref(schema: String): Json = Json.obj("$ref" -> Json.fromString(s"#/components/schemas/$schema"))

  def content(schema: Json, contentType: String = "application/json"): Json =
    Json.obj(contentType -> Json.obj("schema" -> schema))

  def responses(schema: Json): Json =
    Json.obj("200" -> Json.obj("description" -> Json.fromString("OK"), "content" -> content(schema)))

  def param(name: String, in: String, schema: Json, required: Boolean = false, description: String = ""): Json =
    Json.obj(
      "name" -> Json.fromString(name),
      "in" -> Json.fromString(in),
      "required" -> Json.fromBoolean(required),
      "schema" -> schema
    ).deepMerge(if (description.isEmpty) Json.obj() else Json.obj("description" -> Json.fromString(description)))

  def query(name: String, schema: Json, required: Boolean = false, description: String = ""): Json =
    param(name, "query", schema, required, description)

  def path(name: String): Json = param(name, "path", int, required = true)

  def body(schema: Json, required: Boolean = true, contentType: String = "application/json"): Json =
    Json.obj("required" -> Json.fromBoolean(required), "content" -> content(schema, contentType))

  val int: Json = json("""{"type": "integer", "format": "int32"}""")
  val long: Json = json("""{"type": "integer", "format": "int64"}""")
  val double: Json = json("""{"type": "number", "format": "double"}""")
  val boolean: Json = json("""{"type": "boolean"}""")
  val string: Json = json("""{"type": "string"}""")
  val obj: Json = json("""{"type": "object"}""")
  val date: Json = json("""{"type": "string", "format": "date"}""")
  val dateTime: Json = json("""{"type": "string", "format": "date-time"}""")
  def array(items: Json): Json = Json.obj("type" -> Json.fromString("array"), "items" -> items)

  def operation(
      id: String,
      tag: String = "General",
      parameters: List[Json] = Nil,
      requestBody: Option[Json] = None,
      response: Json = ref("APIResponse"),
      summary: String = ""
  ): Json =
    Json.obj(
      "operationId" -> Json.fromString(id),
      "tags" -> Json.arr(Json.fromString(tag)),
      "parameters" -> Json.fromValues(parameters),
      "responses" -> responses(response)
    ).deepMerge(
      Json.fromFields(
        requestBody.map("requestBody" -> _).toList ++
          Option.when(summary.nonEmpty)("summary" -> Json.fromString(summary))
      )
    )

  private val baseSchemas: List[(String, Json)] = List(
    "APIResponse" -> json("""{"type": "object", "properties": {"Status": {"type": "integer"}, "Message": {"type": "string"}}}"""),
    "StringAPIDataResponse" -> json("""{"type": "object", "properties": {"Data": {"type": "string"}, "Status": {"type": "integer"}}}""")
  )

  private val sessionPaths: List[(String, Json)] = List(
    "/api/public/authenticate" -> Json.obj(
      "post" -> operation(
        "ApiPublicAuthenticatePost",
        tag = "Authentication",
        parameters = List(query("culture", string)),
        requestBody = Some(body(string)),
        response = ref("StringAPIDataResponse")
      )
    ),
    "/api/public/logout" -> Json.obj("get" -> operation("ApiPublicLogoutGet", tag = "Authentication"))
  )

  def document(paths: List[PathOp], schemas: List[(String, Json)] = Nil): Json = {
    val grouped = paths.groupMap(_._1)(_._2).toList.map((url, ops) => url -> Json.fromFields(ops))
    Json.obj(
      "openapi" -> Json.fromString("3.0.1"),
      "info" -> Json.obj("title" -> Json.fromString("Test"), "version" -> Json.fromString("v1")),
      "paths" -> Json.fromFields(grouped),
      "components" -> Json.obj("schemas" -> Json.fromFields(baseSchemas ++ schemas))
    )
  }

  def withSession(paths: List[PathOp], schemas: List[(String, Json)] = Nil): Json =
    document(paths, schemas).deepMerge(Json.obj("paths" -> Json.fromFields(sessionPaths)))

  def route(url: String, method: String, op: Json): PathOp = url -> (method -> op)
  def get(url: String, op: Json): PathOp = route(url, "get", op)
  def post(url: String, op: Json): PathOp = route(url, "post", op)
  def put(url: String, op: Json): PathOp = route(url, "put", op)

  extension (spec: Json) {
    def at(keys: String*): ACursor = keys.foldLeft[ACursor](spec.hcursor)(_.downField(_))
  }
}
