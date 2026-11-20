package taxxo4s

import scala.concurrent.duration.*

import cats.effect.IO
import munit.CatsEffectSuite
import sttp.client4.impl.cats.CatsMonadAsyncError
import taxxo4s.FakeTaxxo.{backendClosed, document, unreachable}

class TaxxoClientCatsEffectSuite extends CatsEffectSuite {

  private def server(handler: FakeTaxxo.Handler) = new FakeTaxxo(new CatsMonadAsyncError[IO], IO.sleep(10.millis))(handler)

  test("a reused call description authenticates once") {
    val fake = server { case _ => document }
    fake.fetch.replicateA(2).map { results =>
      assertEquals(results.map(_.map(_.DocumentId)), List.fill(2)(Right(Some(1))))
      assertEquals(fake.authCalls, 1)
    }
  }

  test("a logout built before authenticate ran logs out the session authenticate started") {
    val fake = server { case _ => document }
    val logout = fake.client.logout
    (fake.client.authenticate >> logout).map { result =>
      assertEquals(result, Right(()))
      assertEquals(fake.calls, List(("authenticate", None), ("logout", Some("token-1"))))
    }
  }

  test("parallel first calls never use a logged-out session, and leave one open") {
    val fake = server { case _ => document }
    fake.fetch.parReplicateA(20).map { results =>
      assert(results.forall(_.isRight), results)
      assertEquals(fake.loggedOutTokenUses, 0)
      assertEquals(fake.openSessions.size, 1)
    }
  }

  test("parallel calls on an expired session re-authenticate without using a logged-out one") {
    val fake = server { case _ => document }
    for {
      _       <- fake.client.authenticate.map(_.foreach(fake.expire))
      results <- fake.fetch.parReplicateA(20)
    } yield {
      assert(results.forall(_.isRight), results)
      assertEquals(fake.loggedOutTokenUses, 0)
      assertEquals(fake.openSessions.size, 1)
    }
  }

  test("transport failures are values, other backend exceptions fail the IO") {
    val offline = server { case _ => throw unreachable }
    val broken = server { case _ => throw backendClosed }
    for {
      result <- offline.fetch
      _      <- interceptIO[IllegalStateException](broken.fetch)
    } yield assert(result.left.exists(_.isInstanceOf[TaxxoError.Transport]), result)
  }
}
