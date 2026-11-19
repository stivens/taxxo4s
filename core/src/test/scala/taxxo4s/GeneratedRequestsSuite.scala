package taxxo4s

import sttp.tapir.{EndpointInput, EndpointIO}
import sttp.tapir.internal.*
import taxxo4s.FakeTaxxo.ok
import taxxo4s.generated.{Documents, RequestInputOrder}

class GeneratedRequestsSuite extends munit.FunSuite {

  test("every request builder passes its inputs in the order of the endpoint's inputs") {
    val actual = RequestInputOrder.endpoints.map { e =>
      e.showShort -> e.input.asVectorOfBasicInputs().toList.collect {
        case EndpointInput.PathCapture(Some(name), _, _) => name
        case q: EndpointInput.Query[?]                   => q.name
        case _: EndpointIO.Body[?, ?]                    => "body"
      }
    }.toMap
    assertEquals(actual, RequestInputOrder.names.map((e, names) => e.showShort -> names))
  }

  test("named parameters are sent as their query parameters, omitted ones are not sent") {
    val fake = FakeTaxxo { case (req, _) if req.uri.path.last == "costs" => ok("[]") }
    val result = fake.client.call(Documents.ApiPublicDocumentsCostsGet(month = Some(11), year = Some(2026)))
    assertEquals(result, Right(Nil))
    assertEquals(fake.requests.last.uri.params.toSeq, Seq("year" -> "2026", "month" -> "11"))
  }
}
