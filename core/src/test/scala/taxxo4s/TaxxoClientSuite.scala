package taxxo4s

import java.time.LocalDate

import sttp.client4.{SttpClientException, StringBody}
import sttp.model.{Method, StatusCode}
import sttp.tapir.DecodeResult
import taxxo4s.generated.{Documents, Invoices, Personnel}
import taxxo4s.generated.TaxxoEndpoints.*
import taxxo4s.generated.enums.APIAbsenceRequestStatusEnum
import taxxo4s.FakeTaxxo.{config, error, ok, Reply}

class TaxxoClientSuite extends munit.FunSuite {

  private val absenceRequests =
    """[{"AbsenceRequestId":7,"General":{"Flag":0,"AbsenceType":1,"RequestStatus":-1},
      |  "Periods":[{"DateFrom":"2026-11-02T00:00:00","DateTo":"2026-11-03T00:00:00"}]}]""".stripMargin

  test("authenticate posts the API key as a JSON string and stores the session token") {
    val fake = FakeTaxxo(PartialFunction.empty)
    assertEquals(fake.client.authenticate, Right("token-1"))
    val auth = fake.requests.head
    assertEquals(auth.method, Method.POST)
    assertEquals(auth.body.asInstanceOf[StringBody].s, "\"secret-key\"")
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

  test("path parameters are encoded into the URI") {
    val fake = FakeTaxxo { case (req, _) if req.uri.path == List("api", "public", "document", "42") => ok("""{"DocumentId":42}""") }
    val doc = fake.client.call(Documents.ApiPublicDocumentByDocumentIdGet)(42)
    assertEquals(doc.map(_.DocumentId), Right(Some(42)))
  }

  test("Status=Error becomes TaxxoError.Api") {
    val fake = FakeTaxxo { case _ => error(7, "Incorrect data") }
    val result = fake.client.call(Documents.ApiPublicDocumentByDocumentIdGet)(1)
    assertEquals(result, Left(TaxxoError.Api(Some(7), Some("Incorrect data"), Some("extra"))))
  }

  test("expired token (ErrorCode=10) triggers exactly one re-authentication and a retry") {
    val fake = FakeTaxxo { case _ => ok("""{"DocumentId":1}""") }
    fake.client.authenticate
    fake.expire("token-1")
    val result = fake.client.call(Documents.ApiPublicDocumentByDocumentIdGet)(1)
    assertEquals(result.map(_.DocumentId), Right(Some(1)))
    assertEquals(fake.calls, List(("authenticate", None), ("1", Some("token-1")), ("authenticate", None), ("1", Some("token-2"))))
  }

  test("HTTP 401 triggers re-authentication, but a second rejection is returned, not retried forever") {
    val fake = FakeTaxxo(PartialFunction.empty)
    val result = fake.client.call(Documents.ApiPublicDocumentByDocumentIdGet)(1)
    assertEquals(result, Left(TaxxoError.Http(401, "")))
    assertEquals(fake.authCalls, 2)
  }

  test("a non-2xx response carrying ErrorCode=10 also triggers re-authentication") {
    val fake = FakeTaxxo {
      case (_, "token-1") => Reply(StatusCode.BadRequest, error(10, "Token expired"))
      case (_, "token-2") => ok("""{"DocumentId":1}""")
    }
    assertEquals(fake.client.call(Documents.ApiPublicDocumentByDocumentIdGet)(1).map(_.DocumentId), Right(Some(1)))
    assertEquals(fake.authCalls, 2)
  }

  test("HTTP error responses keep their body, and other error codes are not retried") {
    val body = error(7, "DateFrom is required")
    val fake = FakeTaxxo { case _ => Reply(StatusCode.BadRequest, body) }
    assertEquals(fake.client.call(Documents.ApiPublicDocumentByDocumentIdGet)(1), Left(TaxxoError.Http(400, body)))
    assertEquals(fake.authCalls, 1)
  }

  test("HTTP error messages include only an excerpt of a large body") {
    val body = "x" * 10000
    val fake = FakeTaxxo { case _ => Reply(StatusCode.InternalServerError, body) }
    assertEquals(fake.client.call(Documents.ApiPublicDocumentByDocumentIdGet)(1).left.map(_.getMessage),
      Left(s"Taxxo API returned HTTP 500: ${"x" * 500}… (9500 more characters)"))
  }

  test("transport failures become TaxxoError.Transport instead of being thrown") {
    val io = new java.io.IOException("connection reset")
    val fake = FakeTaxxo { case (req, _) => throw new SttpClientException.ReadException(req, io) }
    assertEquals(fake.client.call(Documents.ApiPublicDocumentByDocumentIdGet)(1).left.map(_.getCause.getCause), Left(io))
  }

  test("a response type without the envelope fields is rejected at compile time") {
    assert(compileErrors("summon[Envelope[NoStatus]]").contains("missing field Status"))
    assert(compileErrors("summon[Envelope[PlainData]]").contains("field Data has an unexpected type"))
  }

  test("status-only responses (APIResponse) yield Unit") {
    val fake = FakeTaxxo { case (req, _) if req.method == Method.DELETE => """{"Status":0,"Message":"Deleted"}""" }
    assertEquals(fake.client.call(Documents.ApiPublicDocumentByDocumentIdDelete)(1), Right(()))
  }

  test("OK without Data is MissingData for call, None for callOpt") {
    val fake = FakeTaxxo { case _ => ok("null") }
    assertEquals(fake.client.call(Documents.ApiPublicDocumentByDocumentIdGet)(1), Left(TaxxoError.MissingData))
    assertEquals(fake.client.callOpt(Documents.ApiPublicDocumentByDocumentIdGet)(1), Right(None))
  }

  test("malformed JSON becomes TaxxoError.Decode, keeping the parser error as the cause but not the body") {
    val sensitive = "PESEL-12345"
    val fake = FakeTaxxo { case _ => s"""{"Data": {"DocumentId": "not-a-number"}, "Message": "$sensitive"}""" }
    fake.client.call(Documents.ApiPublicDocumentByDocumentIdGet)(1) match {
      case Left(error: TaxxoError.Decode) =>
        assert(error.getCause.isInstanceOf[DecodeResult.Error.JsonDecodeException], error.getCause)
        assert(!error.getMessage.contains(sensitive), error.getMessage)
        assert(error.getMessage.contains("ApiPublicDocumentByDocumentIdGet"), error.getMessage)
      case other => fail(s"expected a decode error, got $other")
    }
  }

  test("authenticating again logs out the session it replaces") {
    val fake = FakeTaxxo(PartialFunction.empty)
    assertEquals(fake.client.authenticate, Right("token-1"))
    assertEquals(fake.client.authenticate, Right("token-2"))
    assertEquals(fake.calls, List(("authenticate", None), ("authenticate", None), ("logout", Some("token-1"))))
  }

  test("authenticating again succeeds even if logging out the replaced session throws") {
    val fake = FakeTaxxo(PartialFunction.empty)
    fake.client.authenticate
    fake.failNextLogout(throw new IllegalStateException("backend closed"))
    assertEquals(fake.client.authenticate, Right("token-2"))
    fake.failNextLogout(throw new IllegalStateException("backend closed"))
    intercept[IllegalStateException](fake.client.logout)
  }

  test("concurrent implicit logins never log out a session another call is using") {
    val fake = FakeTaxxo { case (_, "token-2") => ok("""{"DocumentId":1}""") }
    def fetch() = fake.client.call(Documents.ApiPublicDocumentByDocumentIdGet)(1).map(_.DocumentId)
    fake.onLogin(assertEquals(fetch(), Right(Some(1))))
    assertEquals(fetch(), Right(Some(1)))
    assertEquals(fake.calls.filter(_._1 != "authenticate"), List(("1", Some("token-2")), ("logout", Some("token-1")), ("1", Some("token-2"))))
  }

  test("date-only query parameters are sent as yyyy-MM-dd") {
    val fake = FakeTaxxo { case (req, _) if req.uri.path.last == "calendars" => ok("[]") }
    val result = fake.client.call(Personnel.ApiPublicCalendarsGet)((Some(LocalDate.of(2026, 11, 2)), None))
    assertEquals(result, Right(Nil))
    assertEquals(fake.requests.last.uri.params.get("dateFrom"), Some("2026-11-02"))
  }

  test("the config's toString does not reveal the API key") {
    assert(!config.toString.contains("secret-key"), config.toString)
  }

  test("logout calls the API with the session token and clears it") {
    val fake = FakeTaxxo(PartialFunction.empty)
    fake.client.authenticate
    assertEquals(fake.client.logout, Right(()))
    assertEquals(fake.client.logout, Right(()))
    assertEquals(fake.calls.filter(_._1 == "logout"), List(("logout", Some("token-1"))))
  }

  test("after logout, the next call starts a new session") {
    val fake = FakeTaxxo { case _ => ok("""{"DocumentId":1}""") }
    fake.client.authenticate
    fake.client.logout
    assertEquals(fake.client.call(Documents.ApiPublicDocumentByDocumentIdGet)(1).map(_.DocumentId), Right(Some(1)))
    assertEquals(fake.calls, List(("authenticate", None), ("logout", Some("token-1")), ("authenticate", None), ("1", Some("token-2"))))
  }

  test("logging out a session the server already rejects succeeds") {
    val fake = FakeTaxxo(PartialFunction.empty)
    fake.client.authenticate
    fake.failNextLogout(error(10, "Token expired"))
    assertEquals(fake.client.logout, Right(()))
  }

  test("a failed logout reports the error and keeps the session, so it can be retried") {
    val fake = FakeTaxxo(PartialFunction.empty)
    fake.client.authenticate
    fake.failNextLogout(error(6, "Action failed"))
    assertEquals(fake.client.logout, Left(TaxxoError.Api(Some(6), Some("Action failed"), Some("extra"))))
    assertEquals(fake.client.logout, Right(()))
    assertEquals(fake.calls.filter(_._1 == "logout"), List.fill(2)(("logout", Some("token-1"))))
  }

  test("required fields of response models decode a null, request models still demand them") {
    val fake = FakeTaxxo { case (req, _) if req.uri.path.last == "last" => ok("""[{"ContractorId":3,"Name":null}]""") }
    val result = fake.client.call(Invoices.ApiPublicInvoicesContractorsLastGet)((None, None))
    assertEquals(result.map(_.map(c => (c.ContractorId, c.Name))), Right(List((Some(3), None))))
    assert(compileErrors("APIContractor(Name = None)").contains("Found:    None.type"))
  }
}

final case class NoStatus(Data: Option[Int], Message: Option[String], ErrorCode: Option[Int], AdditionalData: Option[String])
final case class PlainData(Data: Int, Status: Option[Int], Message: Option[String], ErrorCode: Option[Int], AdditionalData: Option[String])
