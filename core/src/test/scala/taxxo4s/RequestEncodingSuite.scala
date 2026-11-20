package taxxo4s

import java.time.{Instant, LocalDate}

import sttp.client4.GenericRequest
import sttp.client4.testing.*
import sttp.model.{HeaderNames, Method, Uri}
import taxxo4s.FakeTaxxo.{fetchDocument, ok}
import taxxo4s.generated.{Documents, InterestNotes, Invoices, Personnel, TagsAndAllocations, Tasks, Taxes, Users}
import taxxo4s.generated.enums.APITaskStatus
import taxxo4s.generated.raw.TaxxoEndpoints.APITask

class RequestEncodingSuite extends munit.FunSuite {

  private def lastRequest[R](request: TaxxoRequest[R])(using Envelope[R]): GenericRequest[?, ?] = {
    val fake = FakeTaxxo { case _ => ok("null") }
    fake.client.send(request)
    fake.requests.last
  }

  private def onTheWire(req: GenericRequest[?, ?]): Uri = Uri.unsafeParse(req.uri.toString)

  test("path parameters are encoded into the URI") {
    val req = lastRequest(Documents.ApiPublicDocumentByDocumentIdGet(42))
    assertEquals(req.method, Method.GET)
    assertEquals(req.uri.path, List("api", "public", "document", "42"))
  }

  test("a string path parameter reaches the server as a single segment, whatever characters it has") {
    val req = lastRequest(Invoices.ApiPublicInvoicesByContractorTaxIdByContractorTaxIdGet("PL 123/45 Łódź?x"))
    assertEquals(onTheWire(req).path, List("api", "public", "invoices", "byContractorTaxId", "PL 123/45 Łódź?x"))
  }

  test("named parameters are sent as their query parameters in the endpoint's order, omitted ones are not sent") {
    val req = lastRequest(Documents.ApiPublicDocumentsCostsGet(month = Some(11), year = Some(2026)))
    assertEquals(req.uri.params.toSeq, Seq("year" -> "2026", "month" -> "11"))
  }

  test("query values with reserved characters reach the server intact") {
    val req = lastRequest(Invoices.ApiPublicInvoicesByContractorTaxIdByContractorTaxIdGet("1", recieverExternalId = Some("a&b=c d")))
    assertEquals(onTheWire(req).params.toSeq, Seq("recieverExternalId" -> "a&b=c d"))
  }

  test("list query parameters are repeated, and an empty list is not sent") {
    val tagged = lastRequest(TagsAndAllocations.ApiPublicTagsAllocationGet(tags = List("project-x", "project y"), year = Some(2026)))
    assertEquals(tagged.uri.params.toMultiSeq, Seq("tags" -> Seq("project-x", "project y"), "year" -> Seq("2026")))
    val untagged = lastRequest(TagsAndAllocations.ApiPublicTagsAllocationGet(year = Some(2026)))
    assertEquals(untagged.uri.params.toSeq, Seq("year" -> "2026"))
  }

  test("booleans and doubles are sent in their plain form") {
    val flag = lastRequest(Invoices.ApiPublicInvoicesByContractorTaxIdByContractorTaxIdGet("1", excludeProform = Some(true)))
    assertEquals(flag.uri.params.get("excludeProform"), Some("true"))
    val amount = lastRequest(InterestNotes.ApiPublicInterestNoteByInterestNoteIdSetPaidAmountPut(3, paidAmountForNow = Some(1234.5)))
    assertEquals((amount.method, amount.uri.params.get("paidAmountForNow")), (Method.PUT, Some("1234.5")))
  }

  test("date-only query parameters are sent as yyyy-MM-dd") {
    val req = lastRequest(Personnel.ApiPublicCalendarsGet(dateFrom = Some(LocalDate.of(2026, 11, 2))))
    assertEquals(req.uri.params.toSeq, Seq("dateFrom" -> "2026-11-02"))
  }

  test("date-time query parameters are sent as UTC instants") {
    val req = lastRequest(Taxes.ApiPublicTaxesZusRatesGet(periodStartDate = Instant.parse("2026-11-02T10:00:00Z")))
    assertEquals(req.uri.params.get("periodStartDate"), Some("2026-11-02T10:00:00Z"))
  }

  test("a request body is sent as JSON") {
    val req = lastRequest(Tasks.ApiPublicTaskPost(APITask(Name = "Send the VAT report", Status = Some(APITaskStatus.Open))))
    assertEquals(req.method, Method.POST)
    assertEquals(req.header(HeaderNames.ContentType).map(_.takeWhile(_ != ';')), Some("application/json"))
    val body = req.forceBodyAsString
    assert(body.contains("\"Name\":\"Send the VAT report\""), body)
    assert(body.contains(s"\"Status\":${APITaskStatus.Open}"), body)
  }

  // Pins what tapir sends today; whether Taxxo accepts it has not been checked against the live API.
  test("an optional body that is left out is sent as an empty body labelled application/json") {
    val req = lastRequest(Users.ApiPublicUserByUserIdPermissionsDocumentAccessRulesPost(5))
    assertEquals(req.method, Method.POST)
    assertEquals((req.forceBodyAsString, req.header(HeaderNames.ContentType)), ("", Some("application/json")))
  }

  test("the session token is sent as the raw Authorization header, without a scheme") {
    val req = lastRequest(fetchDocument)
    assertEquals(req.header(HeaderNames.Authorization), Some("token-1"))
  }
}
