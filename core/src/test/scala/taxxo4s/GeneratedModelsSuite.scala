package taxxo4s

import sttp.tapir.{Codec, CodecFormat, DecodeResult, EndpointIO, Schema, SchemaType}
import sttp.tapir.internal.*
import taxxo4s.FakeTaxxo.{apiError, error}
import taxxo4s.generated.EndpointFixtures
import taxxo4s.generated.EndpointFixtures.EndpointFixture

class GeneratedModelsSuite extends munit.FunSuite {

  private def bodyCodec[R](e: EndpointFixture[R]): Codec[String, R, CodecFormat] =
    e.endpoint.output
      .traverseOutputs { case b: EndpointIO.Body[?, ?] => Vector(b.codec.asInstanceOf[Codec[String, R, CodecFormat]]) }
      .headOption
      .getOrElse(fail(s"${e.endpoint.showShort} has no body output"))

  private def failures(
      json: EndpointFixture[?] => String,
      check: Option[TaxxoError.Api] => Boolean = _ => true
  ): List[String] =
    EndpointFixtures.fixtures.flatMap { case e: EndpointFixture[r] =>
      val body = json(e)
      bodyCodec(e).decode(body) match {
        case DecodeResult.Value(v) => Option.unless(check(e.envelope.error(v)))(s"${e.endpoint.showShort}: unexpected $v")
        case failure               => Some(s"${e.endpoint.showShort}: $failure\n  for $body")
      }
    }

  private def sample(schema: Schema[?]): String = schema.schemaType match {
    case SchemaType.SOption(element) => sample(element)
    case SchemaType.SArray(element)  => s"[${sample(element)}]"
    case p: SchemaType.SProduct[?]   =>
      (p.fields.map(f => s""""${f.name.encodedName}":${sample(f.schema)}""") :+ "\"NotInTheSpec\":[1]").mkString("{", ",", "}")
    case _: SchemaType.SRef[?] | _: SchemaType.SOpenProduct[?, ?] => "{}"
    case SchemaType.SString()                                     => "\"\""
    case SchemaType.SInteger() | SchemaType.SNumber()             => "0"
    case SchemaType.SBoolean()                                    => "false"
    case SchemaType.SBinary()                                     => "\"AQID\""
    case SchemaType.SDate()                                       => "\"2026-11-02\""
    // Schema.any, which codegen gives free-form JSON fields.
    case SchemaType.SCoproduct(Nil, None) => "{\"free\":[\"form\"]}"
    case other                            => fail(s"no sample for $other")
  }

  private def dataSchema(e: EndpointFixture[?]): Schema[?] = bodyCodec(e).schema.schemaType match {
    case p: SchemaType.SProduct[?] => p.fields.find(_.name.name == "Data").fold(Schema.schemaForUnit: Schema[?])(_.schema)
    case other                     => fail(s"${e.endpoint.showShort} response is not an object: $other")
  }

  test("every response decodes an empty object") {
    assertEquals(failures(_ => "{}"), Nil)
  }

  test("every response decodes an envelope whose fields are all null, plus a field the spec doesn't know") {
    val body = """{"Data":null,"Status":null,"Message":null,"ErrorCode":null,"AdditionalData":null,"IsStatusOK":null,"NotInTheSpec":{}}"""
    assertEquals(failures(_ => body), Nil)
  }

  test("every response decodes Data with every field filled in, plus fields the spec doesn't know") {
    assertEquals(failures(e => s"""{"Data":${sample(dataSchema(e))},"Status":0}"""), Nil)
  }

  test("every response's envelope reads a Status=Error response as an Api error, and Status=OK as none") {
    assertEquals(failures(_ => error(5, "No access"), _.contains(apiError(5, "No access"))), Nil)
    assertEquals(failures(_ => """{"Status":0}""", _.isEmpty), Nil)
  }
}
