package taxxo4s

import sttp.client4.SttpClientException
import sttp.client4.impl.zio.RIOMonadAsyncError
import taxxo4s.FakeTaxxo.ok
import taxxo4s.generated.Documents
import zio.*
import zio.test.*

object TaxxoClientZioSpec extends ZIOSpecDefault {

  private def server(handler: FakeTaxxo.Handler) = new FakeTaxxo[Task](new RIOMonadAsyncError[Any], ZIO.sleep(10.millis))(handler)
  private def fetch(fake: FakeTaxxo[Task]) = fake.client.call(Documents.ApiPublicDocumentByDocumentIdGet)(1)
  private val document = ok("""{"DocumentId":1}""")

  def spec = suite("TaxxoClient on ZIO")(
    test("a reused call description authenticates once") {
      val fake = server { case _ => document }
      for {
        results <- ZIO.replicateZIO(2)(fetch(fake))
      } yield assertTrue(
        results.toList.map(_.map(_.DocumentId)) == List.fill(2)(Right(Some(1))),
        fake.authCalls == 1
      )
    },
    test("a logout built before authenticate ran logs out the session authenticate started") {
      val fake = server { case _ => document }
      val logout = fake.client.logout
      for {
        result <- fake.client.authenticate *> logout
      } yield assertTrue(
        result == Right(()),
        fake.calls == List(("authenticate", None), ("logout", Some("token-1")))
      )
    },
    test("parallel first calls never use a logged-out session, and leave one open") {
      val fake = server { case _ => document }
      for {
        results <- ZIO.foreachPar(1 to 20)(_ => fetch(fake))
      } yield assertTrue(
        results.forall(_.isRight),
        fake.loggedOutTokenUses == 0,
        fake.openSessions.size == 1
      )
    },
    test("parallel calls on an expired session re-authenticate without using a logged-out one") {
      val fake = server { case _ => document }
      for {
        _       <- fake.client.authenticate.map(_.foreach(fake.expire))
        results <- ZIO.foreachPar(1 to 20)(_ => fetch(fake))
      } yield assertTrue(
        results.forall(_.isRight),
        fake.loggedOutTokenUses == 0,
        fake.openSessions.size == 1
      )
    },
    test("transport failures are values, other backend exceptions fail the effect") {
      val unreachable = server { case (req, _) => throw new SttpClientException.ConnectException(req, new java.net.ConnectException) }
      val broken = server { case _ => throw new IllegalStateException("backend closed") }
      for {
        result <- fetch(unreachable)
        failure <- fetch(broken).flip
      } yield assertTrue(
        result.left.exists(_.isInstanceOf[TaxxoError.Transport]),
        failure.isInstanceOf[IllegalStateException]
      )
    }
  ) @@ TestAspect.withLiveClock
}
