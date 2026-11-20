package taxxo4s

import sttp.client4.testing.*
import sttp.model.{Method, StatusCode}
import sttp.model.Uri.UriContext
import taxxo4s.FakeTaxxo.{apiError, backendClosed, config, document, error, ok, unreachable, Reply}

class TaxxoSessionSuite extends munit.FunSuite {

  test("authenticate posts the API key as a JSON string and stores the session token") {
    val fake = FakeTaxxo()
    assertEquals(fake.client.authenticate, Right("token-1"))
    val auth = fake.requests.head
    assertEquals(auth.method, Method.POST)
    assertEquals(auth.forceBodyAsString, "\"secret-key\"")
    assertEquals(auth.uri.params.toSeq, Nil)
  }

  test("a configured culture is sent with the login") {
    val fake = FakeTaxxo(config = config.copy(culture = Some("pl-PL")))
    fake.client.authenticate
    assertEquals(fake.requests.head.uri.params.toSeq, Seq("culture" -> "pl-PL"))
  }

  test("every request goes to the configured base URI") {
    val fake = FakeTaxxo({ case _ => document }, config.copy(baseUri = uri"https://sandbox.example:8443/taxxo"))
    assertEquals(fake.fetch.map(_.DocumentId), Right(Some(1)))
    fake.client.logout
    assertEquals(
      fake.requests.map(_.uri.toString),
      List(
        "https://sandbox.example:8443/taxxo/api/public/authenticate",
        "https://sandbox.example:8443/taxxo/api/public/document/1",
        "https://sandbox.example:8443/taxxo/api/public/logout"
      )
    )
  }

  test("a login the API refuses is returned without calling the endpoint, and is not cached") {
    val fake = FakeTaxxo { case _ => document }
    fake.replyOnce("authenticate")(error(1, "Invalid API key"))
    assertEquals(fake.fetch, Left(apiError(1, "Invalid API key")))
    assertEquals(fake.fetch.map(_.DocumentId), Right(Some(1)))
    assertEquals(fake.calls, List(("authenticate", None), ("authenticate", None), ("1", Some("token-1"))))
  }

  test("a login without a token is MissingData") {
    List(ok("null"), ok("\"\"")).foreach { reply =>
      val fake = FakeTaxxo { case _ => document }
      fake.replyOnce("authenticate")(reply)
      assertEquals(fake.fetch, Left(TaxxoError.MissingData), reply)
      assertEquals(fake.authCalls, 1)
    }
  }

  test("a login that fails over HTTP or the network is returned as such") {
    val http = FakeTaxxo { case _ => document }
    http.replyOnce("authenticate")(Reply(StatusCode.ServiceUnavailable, "down"))
    assertEquals(http.fetch, Left(TaxxoError.Http(503, "down")))

    val network = FakeTaxxo { case _ => document }
    network.replyOnce("authenticate")(throw unreachable)
    assert(network.client.authenticate.left.exists(_.isInstanceOf[TaxxoError.Transport]))
  }

  test("a failed explicit authenticate keeps the current session") {
    val fake = FakeTaxxo { case _ => document }
    fake.client.authenticate
    fake.replyOnce("authenticate")(error(1, "Invalid API key"))
    assert(fake.client.authenticate.isLeft)
    assertEquals(fake.fetch.map(_.DocumentId), Right(Some(1)))
    assertEquals(fake.calls, List(("authenticate", None), ("authenticate", None), ("1", Some("token-1"))))
  }

  test("authenticating again logs out the session it replaces") {
    val fake = FakeTaxxo()
    assertEquals(fake.client.authenticate, Right("token-1"))
    assertEquals(fake.client.authenticate, Right("token-2"))
    assertEquals(fake.calls, List(("authenticate", None), ("authenticate", None), ("logout", Some("token-1"))))
  }

  test("authenticating again does not log out a session the API hands out again") {
    val fake = FakeTaxxo()
    fake.client.authenticate
    fake.replyOnce("authenticate")(ok("\"token-1\""))
    assertEquals(fake.client.authenticate, Right("token-1"))
    assertEquals(fake.calls, List(("authenticate", None), ("authenticate", None)))
  }

  test("authenticating again succeeds even if logging out the replaced session throws") {
    val fake = FakeTaxxo()
    fake.client.authenticate
    fake.replyOnce("logout")(throw backendClosed)
    assertEquals(fake.client.authenticate, Right("token-2"))
    assertEquals(fake.tokensSentTo("logout"), List(Some("token-1")))
    fake.replyOnce("logout")(throw backendClosed)
    intercept[IllegalStateException](fake.client.logout)
  }

  test("an expired token (ErrorCode=10) triggers exactly one re-authentication and a retry") {
    val fake = FakeTaxxo { case _ => document }
    fake.client.authenticate
    fake.expire("token-1")
    assertEquals(fake.fetch.map(_.DocumentId), Right(Some(1)))
    assertEquals(fake.calls, List(("authenticate", None), ("1", Some("token-1")), ("authenticate", None), ("1", Some("token-2"))))
  }

  test("HTTP 401 triggers re-authentication, but a second rejection is returned, not retried forever") {
    val fake = FakeTaxxo()
    assertEquals(fake.fetch, Left(TaxxoError.Http(401, "")))
    assertEquals(fake.authCalls, 2)
  }

  test("ErrorCode=10 on the retry is returned, not retried forever") {
    val fake = FakeTaxxo { case _ => error(10, "Token expired") }
    assertEquals(fake.fetch, Left(apiError(10, "Token expired")))
    assertEquals(fake.authCalls, 2)
  }

  test("a non-2xx response carrying ErrorCode=10 also triggers re-authentication") {
    val fake = FakeTaxxo {
      case (_, "token-1") => Reply(StatusCode.BadRequest, error(10, "Token expired"))
      case (_, "token-2") => document
    }
    assertEquals(fake.fetch.map(_.DocumentId), Right(Some(1)))
    assertEquals(fake.authCalls, 2)
  }

  test("if logging in again after a rejected token fails, that failure is returned") {
    val fake = FakeTaxxo { case _ => document }
    fake.client.authenticate
    fake.expire("token-1")
    fake.replyOnce("authenticate")(error(1, "Invalid API key"))
    assertEquals(fake.fetch, Left(apiError(1, "Invalid API key")))
    assertEquals(fake.fetch.map(_.DocumentId), Right(Some(1)))
    assertEquals(fake.tokensSentTo("1"), List(Some("token-1"), Some("token-2")))
  }

  test("concurrent implicit logins never log out a session another call is using") {
    val fake = FakeTaxxo { case (_, "token-2") => document }
    def fetch() = fake.fetch.map(_.DocumentId)
    fake.onLogin(assertEquals(fetch(), Right(Some(1))))
    assertEquals(fetch(), Right(Some(1)))
    assertEquals(
      fake.calls.filter(_._1 != "authenticate"),
      List(("1", Some("token-2")), ("logout", Some("token-1")), ("1", Some("token-2")))
    )
  }

  test("logout without a session sends nothing") {
    val fake = FakeTaxxo()
    assertEquals(fake.client.logout, Right(()))
    assertEquals(fake.requests, Nil)
  }

  test("logout calls the API with the session token and clears it") {
    val fake = FakeTaxxo()
    fake.client.authenticate
    assertEquals(fake.client.logout, Right(()))
    assertEquals(fake.client.logout, Right(()))
    assertEquals(fake.tokensSentTo("logout"), List(Some("token-1")))
  }

  test("after logout, the next call starts a new session") {
    val fake = FakeTaxxo { case _ => document }
    fake.client.authenticate
    fake.client.logout
    assertEquals(fake.fetch.map(_.DocumentId), Right(Some(1)))
    assertEquals(fake.calls, List(("authenticate", None), ("logout", Some("token-1")), ("authenticate", None), ("1", Some("token-2"))))
  }

  test("logging out a session the server already rejects succeeds and clears it") {
    List[String | Reply](error(10, "Token expired"), Reply(StatusCode.Unauthorized, "")).foreach { rejection =>
      val fake = FakeTaxxo()
      fake.client.authenticate
      fake.replyOnce("logout")(rejection)
      assertEquals(fake.client.logout, Right(()), rejection)
      assertEquals(fake.client.logout, Right(()))
      assertEquals(fake.tokensSentTo("logout"), List(Some("token-1")))
    }
  }

  test("a failed logout reports the error and keeps the session, so it can be retried") {
    val fake = FakeTaxxo()
    fake.client.authenticate
    fake.replyOnce("logout")(error(6, "Action failed"))
    assertEquals(fake.client.logout, Left(apiError(6, "Action failed")))
    assertEquals(fake.client.logout, Right(()))
    assertEquals(fake.tokensSentTo("logout"), List.fill(2)(Some("token-1")))
  }

  test("a logout that can't reach the API is a Transport error, and keeps the session") {
    val fake = FakeTaxxo()
    fake.client.authenticate
    fake.replyOnce("logout")(throw unreachable)
    assert(fake.client.logout.left.exists(_.isInstanceOf[TaxxoError.Transport]))
    assertEquals(fake.client.logout, Right(()))
    assertEquals(fake.tokensSentTo("logout"), List.fill(2)(Some("token-1")))
  }
}
