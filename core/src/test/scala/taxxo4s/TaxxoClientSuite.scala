package taxxo4s

import java.time.LocalDate

import scala.collection.mutable.ListBuffer

import sttp.client4.{Backend, GenericRequest, SttpClientException, StringBody}
import sttp.client4.testing.{BackendStub, ResponseStub}
import sttp.model.{Method, StatusCode}
import sttp.monad.{IdentityMonad, MonadError}
import sttp.monad.syntax.*
import sttp.tapir.DecodeResult
import taxxo4s.generated.{Documents, Invoices, Personnel}
import taxxo4s.generated.TaxxoEndpoints.*
import taxxo4s.generated.enums.APIAbsenceRequestStatusEnum

class TaxxoClientSuite extends munit.FunSuite {

  private val config = TaxxoConfig(apiKey = "secret-key")

  private def ok(data: String) = s"""{"Data":$data,"Status":0,"Message":null,"ErrorCode":null,"AdditionalData":null,"IsStatusOK":true}"""
  private def error(code: Int, message: String) =
    s"""{"Data":null,"Status":1,"Message":"$message","ErrorCode":$code,"AdditionalData":"extra","IsStatusOK":false}"""

  private val absenceRequests =
    """[{"AbsenceRequestId":7,"General":{"Flag":0,"AbsenceType":1,"RequestStatus":-1},
      |  "Periods":[{"DateFrom":"2026-11-02T00:00:00","DateTo":"2026-11-03T00:00:00"}]}]""".stripMargin

  private type Handler = PartialFunction[(GenericRequest[?, ?], String), String]

  private class FakeTaxxo[F[_]](monad: MonadError[F], status: StatusCode)(handler: Handler) {
    val requests = ListBuffer.empty[GenericRequest[?, ?]]
    var raceLogin = false
    def authCalls: Int = requests.count(_.uri.path.last == "authenticate")
    def logoutCalls: Int = requests.count(_.uri.path.last == "logout")
    def calls: List[(String, Option[String])] = requests.map(r => (r.uri.path.last, r.header("Authorization"))).toList
    val backend: Backend[F] = BackendStub(monad).whenRequestMatchesPartial { req =>
      requests += req
      if (req.uri.path.last == "authenticate") {
        val token = s"token-$authCalls"
        if (raceLogin) { raceLogin = false; client.authenticate }
        ResponseStub.adjust(ok(s"\"$token\""))
      } else {
        val token = req.header("Authorization").getOrElse("")
        handler.lift((req, token)) match {
          case Some(body) => ResponseStub.adjust(body, status)
          case None       => ResponseStub.adjust("", StatusCode.Unauthorized)
        }
      }
    }
    val client = TaxxoClient(config, backend)
  }

  private object FakeTaxxo {
    def apply(handler: Handler, status: StatusCode = StatusCode.Ok) = new FakeTaxxo(IdentityMonad, status)(handler)
    def lazily(handler: Handler) = new FakeTaxxo(Lazy.monad, StatusCode.Ok)(handler)
  }

  test("authenticate posts the API key as a JSON string and stores the session token") {
    val fake = FakeTaxxo(PartialFunction.empty)
    assertEquals(fake.client.authenticate, Right("token-1"))
    val auth = fake.requests.head
    assertEquals(auth.method, Method.POST)
    assertEquals(auth.body.asInstanceOf[StringBody].s, "\"secret-key\"")
  }

  test("call authenticates lazily, sends the token and returns decoded Data") {
    val fake = FakeTaxxo { case (req, "token-1") if req.uri.path.last == "absenceRequests" => ok(absenceRequests) }
    val result = fake.client.call(Personnel.ApiPublicAbsenceRequestsGet)(())
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
    val fake = FakeTaxxo {
      case (_, "token-1") => error(10, "Token expired")
      case (_, "token-2") => ok("""{"DocumentId":1}""")
    }
    val result = fake.client.call(Documents.ApiPublicDocumentByDocumentIdGet)(1)
    assertEquals(result.map(_.DocumentId), Right(Some(1)))
    assertEquals(fake.authCalls, 2)
    assertEquals(fake.logoutCalls, 0, "the rejected token must not be logged out")
  }

  test("HTTP 401 triggers re-authentication, but a second rejection is returned, not retried forever") {
    val fake = FakeTaxxo(PartialFunction.empty)
    val result = fake.client.call(Documents.ApiPublicDocumentByDocumentIdGet)(1)
    assertEquals(result, Left(TaxxoError.Http(401, "")))
    assertEquals(fake.authCalls, 2)
  }

  test("HTTP error responses keep their body") {
    val body = error(7, "DateFrom is required")
    val fake = FakeTaxxo({ case _ => body }, StatusCode.BadRequest)
    assertEquals(fake.client.call(Documents.ApiPublicDocumentByDocumentIdGet)(1), Left(TaxxoError.Http(400, body)))
  }

  test("HTTP error messages include only an excerpt of a large body") {
    val body = "x" * 10000
    val fake = FakeTaxxo({ case _ => body }, StatusCode.InternalServerError)
    assertEquals(fake.client.call(Documents.ApiPublicDocumentByDocumentIdGet)(1).left.map(_.getMessage),
      Left(s"Taxxo API returned HTTP 500: ${"x" * 500}… (9500 more characters)"))
  }

  test("transport failures become TaxxoError.Transport instead of being thrown") {
    val io = new java.io.IOException("connection reset")
    val fake = FakeTaxxo { case (req, _) => throw new SttpClientException.ReadException(req, io) }
    assertEquals(fake.client.call(Documents.ApiPublicDocumentByDocumentIdGet)(1).left.map(_.getCause.getCause), Left(io))
  }

  test("on a lazy backend, logout built before authenticate ran still logs out") {
    val fake = FakeTaxxo.lazily { case (req, _) if req.uri.path.last == "logout" => """{"Status":0}""" }
    val logout = fake.client.logout
    assertEquals(fake.client.authenticate.flatMap(_ => logout).run(), Right(()))
    assertEquals(fake.calls, List(("authenticate", None), ("logout", Some("token-1"))))
  }

  test("on a lazy backend, a reused call description authenticates once") {
    val fake = FakeTaxxo.lazily { case _ => ok("""{"DocumentId":1}""") }
    val fetch = fake.client.call(Documents.ApiPublicDocumentByDocumentIdGet)(1)
    fetch.run()
    fetch.run()
    assertEquals((fake.authCalls, fake.requests.size), (1, 3))
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

  test("authenticating again logs out the session it replaces, and succeeds even if that logout fails") {
    val fake = FakeTaxxo(PartialFunction.empty)
    assertEquals(fake.client.authenticate, Right("token-1"))
    assertEquals(fake.client.authenticate, Right("token-2"))
    assertEquals(fake.calls, List(("authenticate", None), ("authenticate", None), ("logout", Some("token-1"))))
  }

  test("a first call whose login loses a race logs out the session it overwrote, then uses its own") {
    val fake = FakeTaxxo { case (_, "token-1") => ok("""{"DocumentId":1}""") }
    fake.raceLogin = true
    val result = fake.client.call(Documents.ApiPublicDocumentByDocumentIdGet)(1)
    assertEquals(result.map(_.DocumentId), Right(Some(1)))
    assertEquals(fake.calls, List(("authenticate", None), ("authenticate", None), ("logout", Some("token-2")), ("1", Some("token-1"))))
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
    val fake = FakeTaxxo { case (req, "token-1") if req.uri.path.last == "logout" => """{"Status":0}""" }
    fake.client.authenticate
    assertEquals(fake.client.logout, Right(()))
    assertEquals(fake.client.logout, Right(()))
    assertEquals(fake.logoutCalls, 1)
  }

  test("logging out a session the server already rejects succeeds") {
    val fake = FakeTaxxo { case (req, "token-1") if req.uri.path.last == "logout" => error(10, "Token expired") }
    fake.client.authenticate
    assertEquals(fake.client.logout, Right(()))
  }

  test("logout reports other API errors") {
    val fake = FakeTaxxo { case (req, "token-1") if req.uri.path.last == "logout" => error(6, "Action failed") }
    fake.client.authenticate
    assertEquals(fake.client.logout, Left(TaxxoError.Api(Some(6), Some("Action failed"), Some("extra"))))
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
