package taxxo4s

import java.util.concurrent.atomic.AtomicReference

import sttp.client4.{Backend, SttpClientException}
import sttp.model.{HeaderNames, StatusCode}
import sttp.monad.MonadError
import sttp.monad.syntax.*
import sttp.tapir.{stringBody, DecodeResult, PublicEndpoint}
import sttp.tapir.client.sttp4.SttpClientInterpreter
import taxxo4s.generated.General

final class TaxxoClient[F[_]](config: TaxxoConfig, backend: Backend[F]) {
  private given monad: MonadError[F] = backend.monad
  private val interpreter = SttpClientInterpreter()
  private val session = new AtomicReference[Option[String]](None)

  def call[I, R](e: PublicEndpoint[I, Unit, R, Any])(input: I)(using env: Envelope[R]): F[Either[TaxxoError, env.Data]] =
    callOpt(e)(input).map(_.flatMap(_.toRight(TaxxoError.MissingData)))

  def callOpt[I, R](e: PublicEndpoint[I, Unit, R, Any])(input: I)(using env: Envelope[R]): F[Either[TaxxoError, Option[env.Data]]] =
    send(e)(input).map(_.flatMap(r => checkStatus(r).map(env.data)))

  def send[I, R](e: PublicEndpoint[I, Unit, R, Any])(input: I)(using Envelope[R]): F[Either[TaxxoError, R]] =
    currentToken.flatMapRight { token =>
      sendWithToken(e, input, Some(token)).flatMap { result =>
        if (tokenRejected(result)) refreshToken(token).flatMapRight(newToken => sendWithToken(e, input, Some(newToken)))
        else monad.unit(result)
      }
    }

  def authenticate: F[Either[TaxxoError, String]] =
    sendWithToken(General.ApiPublicAuthenticatePost, (config.culture, Some(config.apiKey)), token = None).map { result =>
      val token = result.flatMap(checkStatus).flatMap(r => r.Data.filter(_.nonEmpty).toRight(TaxxoError.MissingData))
      token.foreach(t => session.set(Some(t)))
      token
    }

  def logout: F[Either[TaxxoError, Unit]] =
    monad.suspend {
      session.getAndSet(None) match {
        case None        => monad.unit(Right(()))
        case Some(token) => sendWithToken(General.ApiPublicLogoutGet, (), Some(token)).map(_.flatMap(checkStatus).map(_ => ()))
      }
    }

  private def currentToken: F[Either[TaxxoError, String]] =
    monad.suspend(session.get.fold(authenticate)(t => monad.unit(Right(t))))

  private def refreshToken(rejected: String): F[Either[TaxxoError, String]] =
    session.get match {
      case Some(t) if t != rejected => monad.unit(Right(t))
      case _                        => authenticate
    }

  private def sendWithToken[I, R](e: PublicEndpoint[I, Unit, R, Any], input: I, token: Option[String]): F[Either[TaxxoError, R]] = {
    val request = interpreter.toRequest(e.errorOut(stringBody), Some(config.baseUri)).apply(input)
    token.fold(request)(request.header(HeaderNames.Authorization, _)).send(backend).map { response =>
      response.body match {
        case DecodeResult.Value(Right(r))   => Right(r)
        case DecodeResult.Value(Left(body)) => Left(TaxxoError.Http(response.code.code, body))
        case f: DecodeResult.Failure        => Left(TaxxoError.Decode(f.toString))
      }
    }.handleError { case e: SttpClientException => monad.unit(Left(TaxxoError.Transport(e))) }
  }

  private def checkStatus[R](r: R)(using env: Envelope[R]): Either[TaxxoError, R] =
    env.error(r).toLeft(r)

  private def tokenRejected[R](result: Either[TaxxoError, R])(using Envelope[R]): Boolean =
    result.flatMap(checkStatus) match {
      case Left(TaxxoError.Http(code, _))        => code == StatusCode.Unauthorized.code
      case Left(TaxxoError.Api(errorCode, _, _)) => errorCode.contains(TokenExpiredOrInvalid)
      case _                                     => false
    }

  extension [A](fa: F[Either[TaxxoError, A]]) {
    private def flatMapRight[B](f: A => F[Either[TaxxoError, B]]): F[Either[TaxxoError, B]] =
      fa.flatMap(_.fold(err => monad.unit(Left(err)), f))
  }

  private val TokenExpiredOrInvalid = 10
}
