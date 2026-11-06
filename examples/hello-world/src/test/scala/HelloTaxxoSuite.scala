import scala.collection.mutable.ListBuffer

import sttp.client4.GenericRequest
import sttp.client4.testing.{ResponseStub, SyncBackendStub}

class HelloTaxxoSuite extends munit.FunSuite {

  private def envelope(data: String, status: Int = 0, errorCode: String = "null", message: String = "null") =
    s"""{"Data":$data,"Status":$status,"Message":$message,"ErrorCode":$errorCode,"AdditionalData":null,"IsStatusOK":${status == 0}}"""

  private class MockTaxxo(contextInfo: String, validKey: String = "good-key") {
    val requests = ListBuffer.empty[GenericRequest[?, ?]]
    val backend = SyncBackendStub.whenRequestMatchesPartial { req =>
      requests += req
      req.uri.path.last match {
        case "authenticate" if req.body.toString.contains(validKey) => ResponseStub.adjust(envelope("\"session-1\""))
        case "authenticate"                                          => ResponseStub.adjust(envelope("null", 1, "1", "\"Invalid API key\""))
        case "contextinfo"                                           => ResponseStub.adjust(contextInfo)
        case "logout"                                                => ResponseStub.adjust(envelope("null"))
      }
    }
    def paths: List[String] = requests.map(_.uri.path.last).toList
  }

  test("greets the user and company from the session's context info") {
    val taxxo = MockTaxxo(envelope("""{"UserName":"Anna","CustomerName":"ACME sp. z o.o.","IDCustomer":42}"""))
    assertEquals(greeting("good-key", taxxo.backend), "Hello, Anna! Connected to ACME sp. z o.o..")
    assertEquals(taxxo.paths, List("authenticate", "contextinfo", "logout"))
    assertEquals(taxxo.requests(1).header("Authorization"), Some("session-1"))
  }

  test("falls back to defaults when the context info has no names") {
    val taxxo = MockTaxxo(envelope("{}"))
    assertEquals(greeting("good-key", taxxo.backend), "Hello, Taxxo user! Connected to an unnamed customer.")
  }

  test("reports an API error returned for the context info") {
    val taxxo = MockTaxxo(envelope("null", 1, "5", "\"No access\""))
    assertEquals(greeting("good-key", taxxo.backend), "Taxxo call failed: Taxxo API returned an error (code 5): No access")
  }

  test("reports a rejected API key without calling further endpoints") {
    val taxxo = MockTaxxo(envelope("{}"))
    assertEquals(greeting("bad-key", taxxo.backend), "Taxxo call failed: Taxxo API returned an error (code 1): Invalid API key")
    assertEquals(taxxo.paths, List("authenticate"))
  }
}
