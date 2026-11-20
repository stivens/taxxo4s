package taxxo4s

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import sttp.client4.SttpClientException
import sttp.model.{Method, StatusCode}
import sttp.tapir.*
import sttp.tapir.json.jsoniter.*
import taxxo4s.FakeTaxxo.{apiError, backendClosed, config, document, error, fetchDocument, ok, Reply}
import taxxo4s.generated.{Documents, Invoices, Personnel}
import taxxo4s.generated.enums.APIAbsenceRequestStatusEnum
import taxxo4s.generated.raw.TaxxoEndpoints.*

class TaxxoClientSuite extends munit.FunSuite {

  private val absenceRequests =
    """[{"AbsenceRequestId":7,"General":{"Flag":0,"AbsenceType":1,"RequestStatus":-1},
      |  "Periods":[{"DateFrom":"2026-11-02T00:00:00","DateTo":"2026-11-03T00:00:00"}]}]""".stripMargin

  private def decodeError(result: Either[TaxxoError, Any]): TaxxoError.Decode = result match {
    case Left(error: TaxxoError.Decode) => error
    case other                          => fail(s"expected a Decode error, got $other")
  }

  test("call authenticates lazily, sends the token and returns decoded Data") {
    val fake = FakeTaxxo { case (req, "token-1") if req.uri.path.last == "absenceRequests" => ok(absenceRequests) }
    val result = fake.client.call(Personnel.ApiPublicAbsenceRequestsGet)
    val absence = result.toOption.get.head
    assertEquals(absence.AbsenceRequestId, Some(7))
    assertEquals(absence.General.flatMap(_.RequestStatus), Some(APIAbsenceRequestStatusEnum.Draft))
    assertEquals(absence.Periods.flatMap(_.headOption).flatMap(_.DateFrom), Some("2026-11-02T00:00:00"))
    assertEquals(fake.authCalls, 1)
  }

  test("Status=Error becomes TaxxoError.Api") {
    val fake = FakeTaxxo { case _ => error(7, "Incorrect data") }
    assertEquals(fake.fetch, Left(apiError(7, "Incorrect data")))
  }

  test("Status=Warning is a success for call, and send keeps its message") {
    val warning = """{"Data":{"DocumentId":1},"Status":2,"Message":"Document is locked","ErrorCode":null}"""
    val fake = FakeTaxxo { case _ => warning }
    assertEquals(fake.fetch.map(_.DocumentId), Right(Some(1)))
    assertEquals(fake.client.send(fetchDocument).map(r => (r.Status, r.Message)), Right((Some(2), Some("Document is locked"))))
  }

  test("send returns a Status=Error envelope as a value instead of a TaxxoError") {
    val fake = FakeTaxxo { case _ => error(7, "Incorrect data") }
    assertEquals(
      fake.client.send(fetchDocument).map(r => (r.Status, r.ErrorCode, r.Message)),
      Right((Some(1), Some(7), Some("Incorrect data")))
    )
  }

  test("send still re-authenticates when the token is rejected") {
    val fake = FakeTaxxo { case _ => document }
    fake.client.authenticate
    fake.expire("token-1")
    assertEquals(fake.client.send(fetchDocument).map(_.Data.flatMap(_.DocumentId)), Right(Some(1)))
    assertEquals(fake.authCalls, 2)
  }

  test("HTTP error responses keep their body and, unless the token was rejected, are not retried") {
    val cases = List(
      StatusCode.BadRequest -> error(7, "DateFrom is required"),
      StatusCode.Forbidden -> "<html>Forbidden</html>",
      StatusCode.InternalServerError -> "x" * 10000
    )
    cases.foreach { (code, body) =>
      val fake = FakeTaxxo { case _ => Reply(code, body) }
      assertEquals(fake.fetch, Left(TaxxoError.Http(code.code, body)))
      assertEquals(fake.authCalls, 1, code)
    }
  }

  test("transport failures become TaxxoError.Transport instead of being thrown") {
    val io = new java.io.IOException("connection reset")
    val fake = FakeTaxxo { case (req, _) => throw new SttpClientException.ReadException(req, io) }
    assertEquals(fake.fetch.left.map(_.getCause.getCause), Left(io))
  }

  test("other backend exceptions are not swallowed") {
    val fake = FakeTaxxo { case _ => throw backendClosed }
    intercept[IllegalStateException](fake.fetch)
  }

  test("a response type without the envelope fields is rejected at compile time") {
    assert(compileErrors("summon[Envelope[NoStatus]]").contains("missing field Status"))
    assert(compileErrors("summon[Envelope[PlainData]]").contains("field Data has an unexpected type"))
    assert(compileErrors("summon[Envelope[StringStatus]]").contains("field Status has an unexpected type"))
  }

  test("status-only responses (APIResponse) yield Unit") {
    val fake = FakeTaxxo { case (req, _) if req.method == Method.DELETE => """{"Status":0,"Message":"Deleted"}""" }
    assertEquals(fake.client.call(Documents.ApiPublicDocumentByDocumentIdDelete(1)), Right(()))
  }

  test("OK without Data is MissingData for call, None for callOpt") {
    val fake = FakeTaxxo { case _ => ok("null") }
    assertEquals(fake.fetch, Left(TaxxoError.MissingData))
    assertEquals(fake.client.callOpt(fetchDocument), Right(None))
  }

  test("callOpt returns present Data in Some, and still reports Status=Error") {
    val fake = FakeTaxxo {
      case (req, _) if req.uri.path.last == "1" => document
      case _                                    => error(7, "Incorrect data")
    }
    assertEquals(fake.client.callOpt(fetchDocument).map(_.map(_.DocumentId)), Right(Some(Some(1))))
    assert(fake.client.callOpt(Documents.ApiPublicDocumentByDocumentIdGet(2)).left.exists(_.isInstanceOf[TaxxoError.Api]))
  }

  test("malformed JSON becomes TaxxoError.Decode, keeping the parser error as the cause but not the body") {
    val sensitive = "PESEL-12345"
    val fake = FakeTaxxo { case _ => s"""{"Data": {"DocumentId": "not-a-number"}, "Message": "$sensitive"}""" }
    val error = decodeError(fake.fetch)
    assert(error.getCause.isInstanceOf[DecodeResult.Error.JsonDecodeException], error.getCause)
    assert(!error.getMessage.contains(sensitive), error.getMessage)
    assert(error.getMessage.contains("ApiPublicDocumentByDocumentIdGet"), error.getMessage)
  }

  test("a successful response that is not JSON, or is empty, becomes TaxxoError.Decode") {
    List("<html><body>Maintenance</body></html>", "").foreach { body =>
      val fake = FakeTaxxo { case _ => body }
      assert(fake.fetch.left.exists(_.isInstanceOf[TaxxoError.Decode]), body)
    }
  }

  test("a decode error's details stay short even for a huge response") {
    val fake = FakeTaxxo { case _ => s"""{"Data": {"DocumentId": 1, "Notes": "${"y" * 100000}"""" }
    val details = decodeError(fake.fetch).details
    assert(details.length < 2000, details.length)
  }

  test("an enum value the spec doesn't list still decodes") {
    val fake = FakeTaxxo { case _ => ok("""[{"AbsenceRequestId":7,"General":{"RequestStatus":99}}]""") }
    assertEquals(
      fake.client.call(Personnel.ApiPublicAbsenceRequestsGet).map(_.flatMap(_.General.flatMap(_.RequestStatus))),
      Right(List(99))
    )
  }

  test("required fields of response models decode a null, request models still demand them") {
    val fake = FakeTaxxo { case (req, _) if req.uri.path.last == "last" => ok("""[{"ContractorId":3,"Name":null}]""") }
    val result = fake.client.call(Invoices.ApiPublicInvoicesContractorsLastGet())
    assertEquals(result.map(_.map(c => (c.ContractorId, c.Name))), Right(List((Some(3), None))))
    assert(compileErrors("APIContractor(Name = None)").contains("Found:    None.type"))
  }

  test("a hand-written endpoint with its own envelope type works through TaxxoRequest") {
    val fake = FakeTaxxo { case (req, _) if req.uri.path.last == "hello" => """{"Data":"hi","Status":0}""" }
    assertEquals(fake.client.call(TaxxoRequest(TaxxoClientSuite.hello, ())), Right("hi"))
  }

  test("an Error envelope without code, message or details is still an Api error") {
    val env = summon[Envelope[Greeting]]
    assertEquals(env.error(Greeting(None, Some(1), None, None, None)), Some(TaxxoError.Api(None, None, None): TaxxoError.Api))
    assertEquals(env.error(Greeting(Some("hi"), Some(0), None, None, None)), None)
    assertEquals(env.error(Greeting(Some("hi"), None, None, None, None)), None)
  }

  test("the config's toString does not reveal the API key") {
    assert(!config.toString.contains("secret-key"), config.toString)
    assert(config.toString.contains("https://platforma2.taxxo.pl"), config.toString)
  }
}

object TaxxoClientSuite {
  private given JsonValueCodec[Greeting] = JsonCodecMaker.make
  private given Schema[Greeting] = Schema.derived
  private val hello = endpoint.get.in("api" / "public" / "hello").out(jsonBody[Greeting])
}

final case class Greeting(
    Data: Option[String],
    Status: Option[Int],
    Message: Option[String],
    ErrorCode: Option[Int],
    AdditionalData: Option[String]
)
final case class NoStatus(Data: Option[Int], Message: Option[String], ErrorCode: Option[Int], AdditionalData: Option[String])
final case class PlainData(Data: Int, Status: Option[Int], Message: Option[String], ErrorCode: Option[Int], AdditionalData: Option[String])
final case class StringStatus(
    Data: Option[Int],
    Status: Option[String],
    Message: Option[String],
    ErrorCode: Option[Int],
    AdditionalData: Option[String]
)
