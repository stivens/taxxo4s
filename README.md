# taxxo4s

A Scala 3 client for the [Taxxo API](https://platforma2.taxxo.pl/api/documentation/index.html). All models and endpoints are generated at build time from the vendored OpenAPI spec using [tapir's OpenAPI codegen](https://tapir.softwaremill.com/en/latest/generator/sbt-openapi-codegen.html). The client is effect-agnostic: pass any sttp4 `Backend[F]` (sync, `Future`, cats-effect, ZIO).

## Usage

```scala
import sttp.client4.DefaultSyncBackend
import taxxo4s.*
import taxxo4s.generated.{Documents, General, Personnel}
import taxxo4s.generated.enums.APIAbsenceRequestStatusEnum

val taxxo = TaxxoClient(TaxxoConfig(apiKey = sys.env("TAXXO_API_KEY")), DefaultSyncBackend())

val document = taxxo.call(Documents.ApiPublicDocumentByDocumentIdGet)(42)   // Either[TaxxoError, APIDocumentResultOne]

val summary = for {
  ctx      <- taxxo.call(General.ApiPublicContextinfoGet)
  requests <- taxxo.call(Personnel.ApiPublicAbsenceRequestsGet)
  drafts    = requests.filter(_.General.exists(_.RequestStatus.contains(APIAbsenceRequestStatusEnum.Draft)))
} yield s"${ctx.UserName.getOrElse("?")} has ${drafts.size} draft absence requests"   // Either[TaxxoError, String]
```

With the sync backend every call returns an `Either`, so calls compose in a `for` comprehension and the first failure short-circuits. Any other sttp backend wraps the same `Either` in its effect type.

Each API tag maps to one object in `taxxo4s.generated` (`Documents`, `Invoices`, `Personnel`, …), and each endpoint is named after its `operationId`. Models live in `taxxo4s.generated.TaxxoEndpoints`.

- **`call`** returns the response's `Data`. A `Status = Error` response becomes `TaxxoError.Api`, and a missing `Data` becomes `TaxxoError.MissingData`. A `Status = Warning` response counts as success, and its `Message` is dropped; use `send` if you need it.
- **`callOpt`** is like `call`, but returns `Option` for endpoints that may legitimately return no data.
- **`send`** returns the whole response envelope (`Status`, `Message`, `ErrorCode`, …); `Status = Error` is not turned into a `Left`, but the token-rejection retry below still applies.

Non-2xx responses become `TaxxoError.Http`, which keeps the response body in `body`. Error messages include at most the first 500 characters of a body, so logging an error does not dump a whole response. A response that can't be decoded becomes `TaxxoError.Decode`, with the parser's exception as its cause. Connection and timeout failures (`SttpClientException`) become `TaxxoError.Transport`. Any other exception propagates in the backend's error channel.

The client authenticates on first use and caches the session token. If the token is rejected (HTTP 401, or `ErrorCode = 10`), it re-authenticates once and retries the call. Calling `authenticate` yourself starts a new session and logs out the one it replaces. Authentication is not serialized across fibers, so concurrent first calls on a fresh client may each authenticate once; the client keeps one of those sessions and logs out the others. `logout` ends the current session, but the client stays usable: the next call authenticates again. Logging out a session the server has already expired succeeds.

## Differences from the raw spec

`project/SpecNormalizer.scala` adapts the spec before generation:

- **Tag names** are converted to identifiers (e.g. `Tags and Allocations` becomes `TagsAndAllocations`).
- **`allOf: [$ref]` wrappers** are replaced by a plain `$ref`.
- **Models that appear in responses have no `required` fields,** so all their fields are `Option`. The spec's `required` describes what the server validates on input, and a `null` in a non-`Option` field would fail decoding the whole response. Request-only models keep `required`.
- **Content types:** only `application/json` request and response bodies are kept.
- **`date-time` fields in models are `String`.** The server emits ASP.NET timestamps, which may have no offset, and `java.time.Instant` would reject those.
- **Date-only parameters are `java.time.LocalDate`.** These are `date-time` parameters whose description says `yyyy-MM-dd`, currently `dateFrom` and `dateTo` of `Personnel.ApiPublicCalendarsGet`. They are sent as `2026-11-02`. Other `date-time` query parameters stay `java.time.Instant` and are sent in UTC (`2026-11-02T10:00:00Z`). The spec doesn't say how the server maps that to its local time, so near midnight the server may read a different date than the UTC one.
- **Unsupported operations are skipped,** with a warning at build time. These are operations taking a list of objects as a query parameter, currently only `ApiPublicAccountingDataBatchAccountValueByMonthGet`.

Integer enums are generated as `Int` aliases, because tapir only supports string enums. Their named values are generated separately into `taxxo4s.generated.enums` (e.g. `APIResponseStatusType.OK`).

## Development

```sh
sbt core/test         # generate sources, compile and run tests (sbt 2 skips unchanged suites)
sbt core/testFull     # run every suite regardless of the test cache
sbt core/fetchSpec    # refresh spec/taxxo-v1.raw.json from the live API
sbt core/publishLocal # needed before `sbt test` inside examples/hello-world, a separate build
```

The generated sources go to `target/.../src_managed` and are not committed. Compiling the ~550 generated JSON codecs needs the larger heap configured in `.jvmopts`.
