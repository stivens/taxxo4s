package taxxo4s

class TaxxoErrorSuite extends munit.FunSuite {

  test("Api errors show the code and message, or placeholders when the API leaves them out") {
    assertEquals(TaxxoError.Api(Some(7), Some("Incorrect data"), None).getMessage, "Taxxo API returned an error (code 7): Incorrect data")
    assertEquals(TaxxoError.Api(None, None, Some("details")).getMessage, "Taxxo API returned an error (code -): ")
  }

  test("Http errors show the status, and the body only when there is one") {
    assertEquals(TaxxoError.Http(502, "").getMessage, "Taxxo API returned HTTP 502")
    assertEquals(TaxxoError.Http(400, "bad").getMessage, "Taxxo API returned HTTP 400: bad")
  }

  test("a body is excerpted only past 500 characters") {
    assertEquals(TaxxoError.Http(500, "x" * 500).getMessage, s"Taxxo API returned HTTP 500: ${"x" * 500}")
    assertEquals(TaxxoError.Http(500, "x" * 501).getMessage, s"Taxxo API returned HTTP 500: ${"x" * 500}… (1 more characters)")
    assertEquals(TaxxoError.Http(500, "x" * 501).body.length, 501)
  }

  test("Decode errors excerpt their details like Http errors do, and keep the cause") {
    val cause = new IllegalArgumentException("parser")
    val error = TaxxoError.Decode("d" * 600, Some(cause))
    assert(error.getMessage.endsWith(s"${"d" * 500}… (100 more characters)"), error.getMessage)
    assertEquals(error.getCause, cause)
    assertEquals(TaxxoError.Decode("details", None).getCause, null)
  }

  test("Transport errors carry the sttp exception as their cause") {
    val cause = FakeTaxxo.unreachable
    val error = TaxxoError.Transport(cause)
    assertEquals(error.getCause, cause)
    assert(error.getMessage.startsWith("Could not reach the Taxxo API: "), error.getMessage)
  }

  test("errors are cheap values: no stack trace is captured") {
    assertEquals(TaxxoError.Http(500, "").getStackTrace.toList, Nil)
    assertEquals(TaxxoError.MissingData.getStackTrace.toList, Nil)
  }
}
