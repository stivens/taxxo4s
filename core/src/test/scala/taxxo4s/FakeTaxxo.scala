package taxxo4s

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import scala.jdk.CollectionConverters.*

import sttp.client4.{Backend, GenericRequest, Response}
import sttp.client4.testing.{BackendStub, RecordingBackend, ResponseStub, StubBody}
import sttp.model.StatusCode
import sttp.monad.{IdentityMonad, MonadError}

final class FakeTaxxo[F[_]](monad: MonadError[F], latency: F[Unit])(handler: FakeTaxxo.Handler) {
  import FakeTaxxo.*

  private val logins = new AtomicInteger
  private val sessions = new ConcurrentHashMap[String, Session]
  private val loggedOutUses = new AtomicInteger
  private val loginHook = new AtomicReference[Option[() => Any]](None)
  private val logoutFailure = new AtomicReference[Option[() => String | Reply]](None)

  private val recording = RecordingBackend(
    BackendStub(monad).whenAnyRequest.thenRespondF(req => monad.flatMap(latency)(_ => monad.eval(toResponse(respond(req))))): Backend[F]
  )
  val client: TaxxoClient[F] = TaxxoClient(config, recording)

  def onLogin(hook: => Any): Unit = loginHook.set(Some(() => hook))
  def failNextLogout(reply: => String | Reply): Unit = logoutFailure.set(Some(() => reply))
  def expire(token: String): Unit = sessions.put(token, Session.Expired)

  def requests: List[GenericRequest[?, ?]] = recording.allInteractions.map(_._1)
  def calls: List[(String, Option[String])] = requests.map(r => (r.uri.path.last, r.header("Authorization")))
  def authCalls: Int = requests.count(_.uri.path.last == "authenticate")
  def loggedOutTokenUses: Int = loggedOutUses.get
  def openSessions: Set[String] = sessions.asScala.collect { case (token, Session.Open) => token }.toSet

  private def respond(req: GenericRequest[?, ?]): String | Reply = {
    val token = req.header("Authorization").getOrElse("")
    req.uri.path.last match {
      case "authenticate" =>
        val issued = s"token-${logins.incrementAndGet()}"
        sessions.put(issued, Session.Open)
        loginHook.getAndSet(None).foreach(_())
        ok(s"\"$issued\"")
      // The real API accepts logout even for an ended or expired session.
      case "logout" =>
        logoutFailure.getAndSet(None) match {
          case Some(failure) => failure()
          case None =>
            sessions.put(token, Session.LoggedOut)
            """{"Status":0}"""
        }
      case _ =>
        sessions.get(token) match {
          case Session.LoggedOut => loggedOutUses.incrementAndGet(); error(10, "Token expired")
          case Session.Expired   => error(10, "Token expired")
          case _                 => handler.lift((req, token)).getOrElse(Reply(StatusCode.Unauthorized, ""))
        }
    }
  }

  private def toResponse(reply: String | Reply): Response[StubBody] = reply match {
    case Reply(code, body) => ResponseStub.adjust(body, code)
    case body: String      => ResponseStub.adjust(body)
  }
}

object FakeTaxxo {
  final case class Reply(code: StatusCode, body: String)
  type Handler = PartialFunction[(GenericRequest[?, ?], String), String | Reply]

  private enum Session { case Open, LoggedOut, Expired }

  val config: TaxxoConfig = TaxxoConfig(apiKey = "secret-key")

  def ok(data: String): String = s"""{"Data":$data,"Status":0,"Message":null,"ErrorCode":null,"AdditionalData":null,"IsStatusOK":true}"""
  def error(code: Int, message: String): String =
    s"""{"Data":null,"Status":1,"Message":"$message","ErrorCode":$code,"AdditionalData":"extra","IsStatusOK":false}"""

  def apply(handler: Handler): FakeTaxxo[sttp.shared.Identity] = new FakeTaxxo(IdentityMonad, ())(handler)
}
