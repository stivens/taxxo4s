package taxxo4s

import scala.concurrent.duration.*

import cats.effect.IO
import munit.CatsEffectSuite
import sttp.client4.SttpClientException
import sttp.client4.impl.cats.CatsMonadAsyncError
import taxxo4s.FakeTaxxo.ok
import taxxo4s.generated.Documents

class TaxxoClientCatsEffectSuite extends CatsEffectSuite {

  private def server(handler: FakeTaxxo.Handler) = new FakeTaxxo(new CatsMonadAsyncError[IO], IO.sleep(10.millis))(handler)
  private def fetch(fake: FakeTaxxo[IO]) = fake.client.call(Documents.ApiPublicDocumentByDocumentIdGet)(1)
  private val document = ok("""{"DocumentId":1}""")

  test("a reused call description authenticates once") {
    val fake = server { case _ => document }
    fetch(fake).replicateA(2).map { results =>
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
    fetch(fake).parReplicateA(20).map { results =>
      assert(results.forall(_.isRight), results)
      assertEquals(fake.loggedOutTokenUses, 0)
      assertEquals(fake.openSessions.size, 1)
    }
  }

  test("parallel calls on an expired session re-authenticate without using a logged-out one") {
    val fake = server { case _ => document }
    for {
      _       <- fake.client.authenticate.map(_.foreach(fake.expire))
      results <- fetch(fake).parReplicateA(20)
    } yield {
      assert(results.forall(_.isRight), results)
      assertEquals(fake.loggedOutTokenUses, 0)
      assertEquals(fake.openSessions.size, 1)
    }
  }

  test("transport failures are values, other backend exceptions fail the IO") {
    val unreachable = server { case (req, _) => throw new SttpClientException.ConnectException(req, new java.net.ConnectException) }
    val broken = server { case _ => throw new IllegalStateException("backend closed") }
    for {
      result <- fetch(unreachable)
      _      <- interceptIO[IllegalStateException](fetch(broken))
    } yield assert(result.left.exists(_.isInstanceOf[TaxxoError.Transport]), result)
  }
}
