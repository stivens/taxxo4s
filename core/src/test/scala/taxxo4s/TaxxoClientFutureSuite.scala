package taxxo4s

import scala.concurrent.{ExecutionContext, Future}

import sttp.monad.FutureMonad
import taxxo4s.FakeTaxxo.{backendClosed, document}

class TaxxoClientFutureSuite extends munit.FunSuite {

  private given ExecutionContext = ExecutionContext.global

  private def server(handler: FakeTaxxo.Handler) = new FakeTaxxo(new FutureMonad(), Future.unit)(handler)

  test("a Future backend logs in lazily, re-authenticates on a rejected token and logs out") {
    val fake = server { case _ => document }
    for {
      first <- fake.fetch
      _ = fake.expire("token-1")
      second <- fake.fetch
      logout <- fake.client.logout
    } yield {
      assertEquals(List(first, second).map(_.map(_.DocumentId)), List.fill(2)(Right(Some(1))))
      assertEquals(logout, Right(()))
      assertEquals(
        fake.calls,
        List(
          ("authenticate", None),
          ("1", Some("token-1")),
          ("1", Some("token-1")),
          ("authenticate", None),
          ("1", Some("token-2")),
          ("logout", Some("token-2"))
        )
      )
    }
  }

  test("a Future backend fails the Future on other backend exceptions") {
    val fake = server { case _ => throw backendClosed }
    fake.fetch.failed.map(e => assert(e.isInstanceOf[IllegalStateException], e))
  }
}
