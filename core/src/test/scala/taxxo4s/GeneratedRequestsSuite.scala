package taxxo4s

import sttp.tapir.{EndpointInput, EndpointIO}
import sttp.tapir.internal.*
import taxxo4s.generated.EndpointFixtures

class GeneratedRequestsSuite extends munit.FunSuite {

  test("every request builder passes its inputs in the order of the endpoint's inputs") {
    val actual = EndpointFixtures.endpoints.map { e =>
      e.showShort -> e.input.asVectorOfBasicInputs().toList.collect {
        case EndpointInput.PathCapture(Some(name), _, _) => name
        case q: EndpointInput.Query[?]                   => q.name
        case _: EndpointIO.Body[?, ?]                    => "body"
      }
    }.toMap
    assertEquals(actual, EndpointFixtures.fixtures.map(f => f.endpoint.showShort -> f.inputNames).toMap)
  }

  test("every generated endpoint is public, and no two share a method and path") {
    val names = EndpointFixtures.endpoints.map(_.showShort)
    assertEquals(names.distinct.size, names.size)
    assert(names.size > 300, names.size)
    val secured = EndpointFixtures.endpoints.filter(_.securityInput.asVectorOfBasicInputs().exists(!_.isInstanceOf[EndpointIO.Empty[?]]))
    assertEquals(secured.map(_.showShort), Nil)
  }
}
