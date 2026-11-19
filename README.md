# taxxo4s [![Maven Central](https://maven-badges.sml.io/sonatype-central/io.github.stivens/taxxo4s_3/badge.svg?style=social)](https://maven-badges.sml.io/sonatype-central/io.github.stivens/taxxo4s_3)

A Scala 3 client for the [Taxxo API](https://platforma2.taxxo.pl/api/documentation/index.html). All models and endpoints are generated at build time from the vendored OpenAPI spec using [tapir's OpenAPI codegen](https://tapir.softwaremill.com/en/latest/generator/sbt-openapi-codegen.html). The client is effect-agnostic: pass any sttp4 `Backend[F]` (sync, `Future`, cats-effect, ZIO).

taxxo4s is an unofficial client, not affiliated with or endorsed by Taxxo or Columb Technologies S.A.

## Installation

Requires Scala 3.9 or newer, on the JVM.

`build.sbt`:

```scala
libraryDependencies += "io.github.stivens" %% "taxxo4s" % "0.1.0"
```

`scala-cli`:

```scala
//> using dep "io.github.stivens::taxxo4s:0.1.0"
```

`scala-cli REPL`:

```bash
scala-cli repl --dep io.github.stivens::taxxo4s:0.1.0
```

## Usage

```scala
import sttp.client4.DefaultSyncBackend
import taxxo4s.*
import taxxo4s.generated.{Documents, General, Personnel}
import taxxo4s.generated.enums.APIAbsenceRequestStatusEnum

val taxxo = TaxxoClient(TaxxoConfig(apiKey = sys.env("TAXXO_API_KEY")), DefaultSyncBackend())

val document = taxxo.call(Documents.ApiPublicDocumentByDocumentIdGet(42))   // Either[TaxxoError, APIDocumentResultOne]

val summary = for {
  ctx      <- taxxo.call(General.ApiPublicContextinfoGet)
  requests <- taxxo.call(Personnel.ApiPublicAbsenceRequestsGet)
  drafts    = requests.filter(_.General.exists(_.RequestStatus.contains(APIAbsenceRequestStatusEnum.Draft)))
} yield s"${ctx.UserName.getOrElse("?")} has ${drafts.size} draft absence requests"   // Either[TaxxoError, String]
```

With the sync backend every call returns an `Either`, so calls compose in a `for` comprehension and the first failure short-circuits. Any other sttp backend wraps the same `Either` in its effect type.

Each API tag maps to one object in `taxxo4s.generated` (`Documents`, `Invoices`, `Personnel`, …), and each endpoint is named after its `operationId`. Models live in `taxxo4s.generated.raw.TaxxoEndpoints`.

### Passing parameters

An endpoint with inputs is a method that takes them as named parameters and returns a `TaxxoRequest`. One without inputs, like `General.ApiPublicContextinfoGet`, is a `TaxxoRequest` value. Required parameters come first, so they can also be passed by position. Optional ones default to `None`, or `Nil` for list query parameters, and are then left out of the request. Parameter descriptions from the spec, such as what `-1`, `0` and `1` mean for `isFromKsef`, are in each method's Scaladoc.

```scala
import java.time.LocalDate
import taxxo4s.generated.{Documents, Invoices, Personnel, TagsAndAllocations, Tasks}
import taxxo4s.generated.enums.{APIInvoiceType, APITaskStatus}
import taxxo4s.generated.raw.TaxxoEndpoints.APITask

taxxo.call(Documents.ApiPublicDocumentsCostsGet(year = Some(2026), month = Some(11), isFromKsef = Some(1)))
taxxo.call(Documents.ApiPublicDocumentByDocumentIdSetNotesPut(42, notes = Some("Paid in cash")))
taxxo.call(Personnel.ApiPublicCalendarsGet(dateFrom = Some(LocalDate.of(2026, 11, 1)), dateTo = Some(LocalDate.of(2026, 11, 30))))
taxxo.call(TagsAndAllocations.ApiPublicTagsAllocationGet(tags = List("project-x"), year = Some(2026)))
taxxo.call(Invoices.ApiPublicInvoiceGetNextInvoiceNumberGet(APIInvoiceType.Invoice))

// A request body is the `body` parameter.
taxxo.call(Tasks.ApiPublicTaskPost(APITask(Name = "Send the VAT report", Status = Some(APITaskStatus.Open))))   // id of the new task
```

### Results and errors

`call`, `callOpt` and `send` all take a `TaxxoRequest`:

- **`call`** returns the response's `Data`. A `Status = Error` response becomes `TaxxoError.Api`, and a missing `Data` becomes `TaxxoError.MissingData`. A `Status = Warning` response counts as success, and its `Message` is dropped; use `send` if you need it.
- **`callOpt`** is like `call`, but returns `Option` for endpoints that may legitimately return no data.
- **`send`** returns the whole response envelope (`Status`, `Message`, `ErrorCode`, …); `Status = Error` is not turned into a `Left`, but the token-rejection retry below still applies.

```scala
taxxo.call(Documents.ApiPublicDocumentByDocumentIdGet(42)) match {
  case Right(doc)                             => s"Document ${doc.DocumentId.getOrElse("?")}"
  case Left(TaxxoError.Api(code, message, _)) => s"Taxxo refused (code ${code.getOrElse("-")}): ${message.getOrElse("")}"
  case Left(TaxxoError.Http(status, _))       => s"HTTP $status"
  case Left(other)                            => other.getMessage
}

taxxo.send(Documents.ApiPublicDocumentByDocumentIdAcceptPut(42)).map(_.Message)   // keeps a warning's message
```

Non-2xx responses become `TaxxoError.Http`, which keeps the response body in `body`. Error messages include at most the first 500 characters of a body, so logging an error does not dump a whole response. A response that can't be decoded becomes `TaxxoError.Decode`, with the parser's exception as its cause. Connection and timeout failures (`SttpClientException`) become `TaxxoError.Transport`. Any other exception propagates in the backend's error channel.

### Sessions

The client authenticates on first use and caches the session token. If the token is rejected (HTTP 401, or `ErrorCode = 10` under any HTTP status), it re-authenticates once and retries the call. Calling `authenticate` yourself starts a new session and logs out the one it replaces. Authentication is not serialized across fibers, so concurrent first calls on a fresh client may each authenticate. The first session stored is shared by all of them, and the others are logged out right away, so no call loses a session it is using. `logout` ends the current session, but the client stays usable: the next call authenticates again. If the logout request fails, the session is kept, so `logout` can be retried. Logging out a session the server has already expired succeeds.

### Other effect types

```scala
import cats.effect.IO
import sttp.client4.httpclient.cats.HttpClientCatsBackend

val userName: IO[Either[TaxxoError, String]] = HttpClientCatsBackend.resource[IO]().use { backend =>
  val taxxo = TaxxoClient(TaxxoConfig(apiKey = sys.env("TAXXO_API_KEY")), backend)
  taxxo.call(General.ApiPublicContextinfoGet).map(_.map(_.UserName.getOrElse("?")))
}
```

ZIO and `Future` backends work the same way.

### Raw and hand-written endpoints

The tapir endpoints behind the generated methods are in `taxxo4s.generated.raw`, and take their inputs positionally: a tuple, a single value, or `()`. `TaxxoRequest(endpoint, input)` turns any tapir endpoint without error output into a request:

```scala
taxxo.send(TaxxoRequest(taxxo4s.generated.raw.General.ApiPublicStatusGet, ()))
```

An endpoint you write yourself works the same way, e.g. for an operation the generator skips. This one mirrors `General.ApiPublicStatusGet`:

```scala
import sttp.tapir.*
import sttp.tapir.json.jsoniter.*
import taxxo4s.generated.raw.TaxxoEndpoints.APIResponse
import taxxo4s.generated.raw.TaxxoEndpointsJsonSerdes.*

given Schema[APIResponse] = Schema.derived
val status = endpoint.get.in("api" / "public" / "status").out(jsonBody[APIResponse])

taxxo.send(TaxxoRequest(status, ()))
```

## Differences from the raw spec

`project/SpecNormalizer.scala` adapts the spec before generation:

- **Tag names** are converted to identifiers (e.g. `Tags and Allocations` becomes `TagsAndAllocations`).
- **`allOf: [$ref]` wrappers** are replaced by a plain `$ref`.
- **Models that appear in responses have no `required` fields,** so all their fields are `Option`. The spec's `required` describes what the server validates on input, and a `null` in a non-`Option` field would fail decoding the whole response. Request-only models keep `required`.
- **Content types:** only `application/json` request and response bodies are kept.
- **`date-time` fields in models are `String`.** The server emits ASP.NET timestamps, which may have no offset, and `java.time.Instant` would reject those.
- **Date-only parameters are `java.time.LocalDate`.** These are `date-time` parameters whose description says `yyyy-MM-dd`, currently `dateFrom` and `dateTo` of `Personnel.ApiPublicCalendarsGet`. They are sent as `2026-11-02`. Other `date-time` query parameters stay `java.time.Instant` and are sent in UTC (`2026-11-02T10:00:00Z`). The spec doesn't say how the server maps that to its local time, so near midnight the server may read a different date than the UTC one.
- **Login and logout operations are not generated** (`/api/public/authenticate*` and `/api/public/logout`). The client manages the session through `authenticate` and `logout`, and calling these operations directly would bypass that. The client only logs in with an API key; the user, accounting-office and query-string login variants are not supported.
- **Unsupported operations are skipped,** with a warning at build time. Currently that is only `ApiPublicAccountingDataBatchAccountValueByMonthGet`, which takes a list of objects as a query parameter. An operation is also skipped if it has no tag, a header or cookie parameter, a request body that isn't JSON, or a parameter named `body` next to a request body.

Integer enums are generated as `Int` aliases, because tapir only supports string enums. Their named values are generated separately into `taxxo4s.generated.enums` (e.g. `APIResponseStatusType.OK`).

## Development

```sh
sbt core/test                                # generate sources, compile and run tests (sbt 2 skips unchanged suites)
sbt core/testFull                            # run every suite regardless of the test cache
sbt core/fetchSpec                           # refresh spec/taxxo-v1.raw.json from the live API
sbt core/publishLocal                        # needed before `sbt test` inside examples/hello-world, a separate build
sbt 'scalafixAll; scalafmtRepo'              # remove unused code, then format the whole repo
sbt 'scalafixAll --check; scalafmtCheckRepo' # verify the same without changing files
```

The generated sources go to `target/.../src_managed` and are not committed. Compiling the ~550 generated JSON codecs needs the larger heap configured in `.jvmopts`.

## License

MIT, except `spec/`; see [LICENSE](LICENSE).
