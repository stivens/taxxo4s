package taxxo4s

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}

import scala.jdk.CollectionConverters.*

import sttp.client4.{basicRequest, Backend, GenericRequest, Response, SttpClientException}
import sttp.client4.testing.{BackendStub, RecordingBackend, ResponseStub, StubBody}
import sttp.model.StatusCode
import sttp.model.Uri.UriContext
import sttp.monad.{IdentityMonad, MonadError}
import taxxo4s.generated.Documents

final class FakeTaxxo[F[_]](monad: MonadError[F], latency: F[Unit], config: TaxxoConfig = FakeTaxxo.config)(handler: FakeTaxxo.Handler) {
  import FakeTaxxo.*

  private val logins = new AtomicInteger
  private val sessions = new ConcurrentHashMap[String, Session]
  private val loggedOutUses = new AtomicInteger
  private val loginHook = new AtomicReference[Option[() => Any]](None)
  private val nextReplies = new ConcurrentHashMap[String, () => String | Reply]

  private val recording = RecordingBackend(
    BackendStub(monad).whenAnyRequest.thenRespondF(req => monad.flatMap(latency)(_ => monad.eval(toResponse(respond(req))))): Backend[F]
  )
  val client: TaxxoClient[F] = TaxxoClient(config, recording)

  def fetch = client.call(fetchDocument)

  def onLogin(hook: => Any): Unit = loginHook.set(Some(() => hook))
  def replyOnce(endpoint: String)(reply: => String | Reply): Unit =
    require(nextReplies.put(endpoint, () => reply) == null, s"the previous one-off reply for $endpoint was never used")
  def expire(token: String): Unit = sessions.put(token, Session.Expired)

  def requests: List[GenericRequest[?, ?]] = recording.allInteractions.map(_._1)
  def calls: List[(String, Option[String])] = requests.map(r => (r.uri.path.last, r.header("Authorization")))
  def tokensSentTo(endpoint: String): List[Option[String]] = calls.collect { case (`endpoint`, token) => token }
  def authCalls: Int = tokensSentTo("authenticate").size
  def loggedOutTokenUses: Int = loggedOutUses.get
  def openSessions: Set[String] = sessions.asScala.collect { case (token, Session.Open) => token }.toSet

  private def respond(req: GenericRequest[?, ?]): String | Reply =
    Option(nextReplies.remove(req.uri.path.last)).fold(defaultReply(req))(_())

  private def defaultReply(req: GenericRequest[?, ?]): String | Reply = {
    val token = req.header("Authorization").getOrElse("")
    req.uri.path.last match {
      case "authenticate" =>
        val issued = s"token-${logins.incrementAndGet()}"
        sessions.put(issued, Session.Open)
        loginHook.getAndSet(None).foreach(_())
        ok(s"\"$issued\"")
      // The real API accepts logout even for an ended or expired session.
      case "logout" =>
        sessions.put(token, Session.LoggedOut)
        """{"Status":0}"""
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
  def apiError(code: Int, message: String): TaxxoError.Api = TaxxoError.Api(Some(code), Some(message), Some("extra"))

  val fetchDocument = Documents.ApiPublicDocumentByDocumentIdGet(1)
  val document: String = ok("""{"DocumentId":1}""")

  def backendClosed: IllegalStateException = new IllegalStateException("backend closed")

  def unreachable: SttpClientException =
    new SttpClientException.ConnectException(basicRequest.get(uri"https://taxxo"), new java.net.ConnectException)

  def apply(handler: Handler = PartialFunction.empty, config: TaxxoConfig = FakeTaxxo.config): FakeTaxxo[sttp.shared.Identity] =
    new FakeTaxxo(IdentityMonad, (), config)(handler)
}
