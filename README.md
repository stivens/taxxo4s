# taxxo4s

A Scala 3 client for the [Taxxo API](https://platforma2.taxxo.pl/api/documentation/index.html). All models and endpoints are generated at build time from the vendored OpenAPI spec using [tapir's OpenAPI codegen](https://tapir.softwaremill.com/en/latest/generator/sbt-openapi-codegen.html). The client is effect-agnostic: pass any sttp4 `Backend[F]` (sync, `Future`, cats-effect, ZIO).

## Usage

```scala
import sttp.client4.DefaultSyncBackend
import taxxo4s.*
import taxxo4s.generated.{Documents, Personnel}
import taxxo4s.generated.enums.APIAbsenceRequestStatusEnum

val taxxo = TaxxoClient(TaxxoConfig(apiKey = sys.env("TAXXO_API_KEY")), DefaultSyncBackend())

val document = taxxo.call(Documents.ApiPublicDocumentByDocumentIdGet)(42)   // Either[TaxxoError, APIDocumentResultOne]
val drafts = taxxo.call(Personnel.ApiPublicAbsenceRequestsGet)(()).map(
  _.filter(_.General.flatMap(_.RequestStatus).contains(APIAbsenceRequestStatusEnum.Draft))
)
```

Each API tag maps to one object in `taxxo4s.generated` (`Documents`, `Invoices`, `Personnel`, …), and each endpoint is named after its `operationId`. Models live in `taxxo4s.generated.TaxxoEndpoints`.

- **`call`** returns the response's `Data`. A `Status = Error` response becomes `TaxxoError.Api`, and a missing `Data` becomes `TaxxoError.MissingData`.
- **`callOpt`** is like `call`, but returns `Option` for endpoints that may legitimately return no data.
- **`send`** returns the whole response envelope (`Status`, `Message`, `ErrorCode`, …) without interpreting it.

The client authenticates on first use and caches the session token. If the token is rejected (HTTP 401, or `ErrorCode = 10`), it re-authenticates once and retries the call.

## Differences from the raw spec

`project/SpecNormalizer.scala` adapts the spec before generation:

- **Tag names** are converted to identifiers (e.g. `Tags and Allocations` becomes `TagsAndAllocations`).
- **`allOf: [$ref]` wrappers** are replaced by a plain `$ref`.
- **Content types:** only `application/json` request and response bodies are kept.
- **`date-time` fields in models are `String`.** The server emits ASP.NET timestamps, which may have no offset, and `java.time.Instant` would reject those. Query parameters stay `java.time.Instant`.
- **Unsupported operations are skipped,** with a warning at build time. These are operations taking a list of objects as a query parameter, currently only `ApiPublicAccountingDataBatchAccountValueByMonthGet`.

Integer enums are generated as `Int` aliases, because tapir only supports string enums. Their named values are generated separately into `taxxo4s.generated.enums` (e.g. `APIResponseStatusType.OK`).

## Development

```sh
sbt core/test         # generate sources, compile and run tests (sbt 2 skips unchanged suites)
sbt core/testFull     # run every suite regardless of the test cache
sbt core/fetchSpec    # refresh spec/taxxo-v1.raw.json from the live API
```

The generated sources go to `target/.../src_managed` and are not committed. Compiling the ~550 generated JSON codecs needs the larger heap configured in `.jvmopts`.
