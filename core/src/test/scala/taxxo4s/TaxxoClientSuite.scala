package taxxo4s

import scala.collection.mutable.ListBuffer

import sttp.client4.{GenericRequest, StringBody}
import sttp.client4.testing.{ResponseStub, SyncBackendStub}
import sttp.model.{Method, StatusCode}
import taxxo4s.generated.{Documents, Personnel}
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

  private class FakeTaxxo(handler: PartialFunction[(GenericRequest[?, ?], String), String]) {
    val requests = ListBuffer.empty[GenericRequest[?, ?]]
    def authCalls: Int = requests.count(_.uri.path.last == "authenticate")
    val backend = SyncBackendStub.whenRequestMatchesPartial { req =>
      requests += req
      if (req.uri.path.last == "authenticate") {
        ResponseStub.adjust(ok(s"\"token-$authCalls\""))
      } else {
        val token = req.header("Authorization").getOrElse("")
        handler.lift((req, token)) match {
          case Some(body) => ResponseStub.adjust(body)
          case None       => ResponseStub.adjust("", StatusCode.Unauthorized)
        }
      }
    }
    val client = TaxxoClient(config, backend)
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
    assertEquals(absence.Periods.head.DateFrom, Some("2026-11-02T00:00:00"))
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
  }

  test("HTTP 401 triggers re-authentication, but a second rejection is returned, not retried forever") {
    val fake = FakeTaxxo(PartialFunction.empty)
    val result = fake.client.call(Documents.ApiPublicDocumentByDocumentIdGet)(1)
    assertEquals(result, Left(TaxxoError.Http(401)))
    assertEquals(fake.authCalls, 2)
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

  test("malformed JSON becomes TaxxoError.Decode") {
    val fake = FakeTaxxo { case _ => """{"Data": {"DocumentId": "not-a-number"}}""" }
    assert(fake.client.call(Documents.ApiPublicDocumentByDocumentIdGet)(1).left.exists(_.isInstanceOf[TaxxoError.Decode]))
  }

  test("logout calls the API with the session token and clears it") {
    val fake = FakeTaxxo { case (req, "token-1") if req.uri.path.last == "logout" => """{"Status":0}""" }
    fake.client.authenticate
    assertEquals(fake.client.logout, Right(()))
    assertEquals(fake.client.logout, Right(()))
    assertEquals(fake.requests.count(_.uri.path.last == "logout"), 1)
  }
}
