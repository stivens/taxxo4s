package taxxo4s

import java.util.concurrent.atomic.AtomicReference

import scala.util.Try
import scala.util.control.NonFatal

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, JsonValueCodec}
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import sttp.client4.{Backend, SttpClientException}
import sttp.model.{HeaderNames, StatusCode}
import sttp.monad.MonadError
import sttp.monad.syntax.*
import sttp.tapir.*
import sttp.tapir.client.sttp4.SttpClientInterpreter
import sttp.tapir.json.jsoniter.*
import taxxo4s.generated.TaxxoEndpoints.{APIResponse, StringAPIDataResponse}
import taxxo4s.generated.TaxxoEndpointsJsonSerdes.*

final class TaxxoClient[F[_]](config: TaxxoConfig, backend: Backend[F]) {
  import TaxxoClient.*

  private given monad: MonadError[F] = backend.monad
  private val interpreter = SttpClientInterpreter()
  private val session = new AtomicReference[Option[String]](None)

  def call[I, R](e: PublicEndpoint[I, Unit, R, Any])(input: I)(using env: Envelope[R]): F[Either[TaxxoError, env.Data]] =
    callOpt(e)(input).map(_.flatMap(_.toRight(TaxxoError.MissingData)))

  def call[R](e: PublicEndpoint[Unit, Unit, R, Any])(using env: Envelope[R]): F[Either[TaxxoError, env.Data]] =
    call[Unit, R](e)(())

  def callOpt[I, R](e: PublicEndpoint[I, Unit, R, Any])(input: I)(using env: Envelope[R]): F[Either[TaxxoError, Option[env.Data]]] =
    send(e)(input).map(_.flatMap(r => checkStatus(r).map(env.data)))

  def callOpt[R](e: PublicEndpoint[Unit, Unit, R, Any])(using env: Envelope[R]): F[Either[TaxxoError, Option[env.Data]]] =
    callOpt[Unit, R](e)(())

  def send[I, R](e: PublicEndpoint[I, Unit, R, Any])(input: I)(using Envelope[R]): F[Either[TaxxoError, R]] =
    currentToken.flatMapRight { token =>
      sendWithToken(e, input, Some(token)).flatMap { result =>
        if (tokenRejected(result)) {
          forget(token)
          currentToken.flatMapRight(fresh => sendWithToken(e, input, Some(fresh)))
        } else monad.unit(result)
      }
    }

  def send[R](e: PublicEndpoint[Unit, Unit, R, Any])(using Envelope[R]): F[Either[TaxxoError, R]] =
    send[Unit, R](e)(())

  /** Starts a new session and logs out the one it replaces; failing to log that one out does not fail this call. */
  def authenticate: F[Either[TaxxoError, String]] =
    login.flatMapRight { token =>
      session.getAndSet(Some(token)) match {
        case Some(replaced) if replaced != token => discard(replaced).map(_ => Right(token))
        case _                                   => monad.unit(Right(token))
      }
    }

  def logout: F[Either[TaxxoError, Unit]] =
    monad.suspend(session.get.fold(monad.unit(Right(())))(token => endSession(token).map(_.map(_ => forget(token)))))

  private def forget(token: String): Unit =
    session.updateAndGet(_.filterNot(_ == token))

  private def login: F[Either[TaxxoError, String]] =
    sendWithToken(Login, (config.culture, config.apiKey), token = None)
      .map(_.flatMap(checkStatus).flatMap(_.Data.filter(_.nonEmpty).toRight(TaxxoError.MissingData)))

  private def currentToken: F[Either[TaxxoError, String]] =
    monad.suspend(session.get.fold(login.flatMapRight(adopt))(t => monad.unit(Right(t))))

  // Another fiber may be using the session that won, so a losing implicit login ends its own instead of replacing it.
  private def adopt(token: String): F[Either[TaxxoError, String]] =
    session.compareAndExchange(None, Some(token)) match {
      case None         => monad.unit(Right(token))
      case Some(winner) => discard(token).map(_ => Right(winner))
    }

  private def discard(token: String): F[Unit] =
    endSession(token).map(_ => ()).handleError { case NonFatal(_) => monad.unit(()) }

  // A token the server already rejects is as logged out as it gets.
  private def endSession(token: String): F[Either[TaxxoError, Unit]] =
    sendWithToken(Logout, (), Some(token)).map { result =>
      if (tokenRejected(result)) Right(()) else result.flatMap(checkStatus).map(_ => ())
    }

  private def sendWithToken[I, R](e: PublicEndpoint[I, Unit, R, Any], input: I, token: Option[String]): F[Either[TaxxoError, R]] = {
    val request = interpreter.toRequest(e.errorOut(stringBody), Some(config.baseUri)).apply(input)
    token.fold(request)(request.header(HeaderNames.Authorization, _)).send(backend).map { response =>
      response.body match {
        case DecodeResult.Value(Right(r))   => Right(r)
        case DecodeResult.Value(Left(body)) => Left(TaxxoError.Http(response.code.code, body))
        case DecodeResult.Error(_, failure) =>
          // tapir's sttp interpreter wraps the parser error in an exception whose message is the whole body.
          val parserError = Option(failure.getCause).getOrElse(failure)
          Left(TaxxoError.Decode(s"${e.showShort}: ${parserError.getMessage}", Some(parserError)))
        case f: DecodeResult.Failure        => Left(TaxxoError.Decode(s"${e.showShort}: $f", None))
      }
    }.handleError { case e: SttpClientException => monad.unit(Left(TaxxoError.Transport(e))) }
  }

  private def checkStatus[R](r: R)(using env: Envelope[R]): Either[TaxxoError, R] =
    env.error(r).toLeft(r)

  private def tokenRejected[R](result: Either[TaxxoError, R])(using Envelope[R]): Boolean =
    result.flatMap(checkStatus) match {
      case Left(TaxxoError.Http(code, body)) =>
        code == StatusCode.Unauthorized.code || Try(readFromString[APIResponse](body)).toOption.exists(r => tokenRejected(Right(r)))
      case Left(TaxxoError.Api(errorCode, _, _)) => errorCode.contains(TokenExpiredOrInvalid)
      case _                                     => false
    }

  extension [A](fa: F[Either[TaxxoError, A]]) {
    private def flatMapRight[B](f: A => F[Either[TaxxoError, B]]): F[Either[TaxxoError, B]] =
      fa.flatMap(_.fold(err => monad.unit(Left(err)), f))
  }
}

object TaxxoClient {
  private val TokenExpiredOrInvalid = 10

  private given JsonValueCodec[String] = JsonCodecMaker.make
  // Codegen spreads schemas over numbered files by count, so importing them would break when a spec refresh moves one.
  private given Schema[APIResponse] = Schema.derived
  private given Schema[StringAPIDataResponse] = Schema.derived

  private val Login =
    endpoint.post
      .in("api" / "public" / "authenticate")
      .in(query[Option[String]]("culture"))
      .in(jsonBody[String])
      .out(jsonBody[StringAPIDataResponse])

  private val Logout = endpoint.get.in("api" / "public" / "logout").out(jsonBody[APIResponse])
}
