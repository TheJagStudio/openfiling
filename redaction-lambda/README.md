# redaction-lambda

Automated **PII / CUI redaction** for OpenFiling. When a *public* original lands in `openfiling-documents`
(`public/{applicationId}/{documentId}/{file}.pdf`), the S3 event invokes this function, which extracts the PDF text
with glyph positions, finds sensitive spans with deterministic **rules** plus **Claude**, burns black boxes into a
freshly rasterised **Public Copy**, stores it (and a redaction report) in `openfiling-public-copies`, and reports the
status to filing-api.

Plain Java 21 — **no Spring**. Packaged as an AWS Lambda `RequestStreamHandler` (`lambdaZip`) plus a tiny local
Lambda runtime (port **9001**) for development.

## Run

```powershell
. D:\JavaSpring\tools\env.ps1
.\gradlew.bat build              # tests + JaCoCo gate (>= 80 % lines) + build\distributions\openfiling-redaction-lambda-*.zip
.\gradlew.bat run                # local Lambda runtime on http://localhost:9001 (reads the env vars below)
.\gradlew.bat generateSamples    # writes the demo PDFs to ..\samples
.\gradlew.bat lambdaZip          # AWS deployment package only
```

For local use against the emulator set `S3_ENDPOINT=http://localhost:9000` (and optionally `ANTHROPIC_API_KEY`):

```powershell
$env:S3_ENDPOINT = 'http://localhost:9000'; $env:ANTHROPIC_API_KEY = '<key>'   # key optional
.\gradlew.bat run
```

| Env var | Default | Meaning |
|---|---|---|
| `DOCUMENTS_BUCKET` | `openfiling-documents` | only records from this bucket (and under `public/`) are processed |
| `PUBLIC_BUCKET` | `openfiling-public-copies` | destination of `public-copy.pdf` + `redaction-report.json` |
| `S3_ENDPOINT` | unset | e.g. `http://localhost:9000`; unset in AWS (default endpoint + execution-role credentials) |
| `S3_REGION` | `us-east-1` | |
| `S3_ACCESS_KEY` / `S3_SECRET_KEY` | `openfiling-local` / `openfiling-local-secret` | only used with `S3_ENDPOINT` |
| `S3_PATH_STYLE` | `true` | path-style addressing |
| `ANTHROPIC_API_KEY` | unset | enables Claude (never logged) |
| `REDACTION_AI_ENABLED` | `true` | `false` forces rules-only even with a key |
| `CLAUDE_MODEL` | `claude-opus-5-5` | |
| `CLAUDE_EFFORT` | `medium` | `low` / `medium` / `high` (also `xhigh`, `max`) → `output_config.effort` |
| `CLAUDE_FALLBACKS` | `true` | server-side refusal fallback (`anthropic-beta: server-side-fallback-2026-07-01`, `fallbacks: "default"`) |
| `CLAUDE_MAX_CHUNK_CHARS` | `60000` | max characters of document text per Claude request |
| `CALLBACK_URL` | `http://localhost:8080/internal/v1/redactions/events` | filing-api status callback |
| `INTERNAL_TOKEN` | `dev-internal-token-change-me` | sent as `X-Internal-Token` |
| `RENDER_DPI` | `150` | rasterisation resolution of the public copy (36–600) |
| `MAX_PAGES` | `500` | larger documents → `FAILED` |
| `MAX_OBJECT_BYTES` | `524288000` (500 MiB) | larger originals → `FAILED` (checked with HeadObject before download) |
| `PORT` | `9001` | local runtime only |

## Local Lambda runtime

`gov.openfiling.redaction.local.LocalLambdaRuntime` (JDK `HttpServer`):

- `POST /2015-03-31/functions/openfiling-redaction/invocations` — `X-Amz-Invocation-Type: Event` → `202`, queued on a
  bounded executor (4 concurrent, queue 100, `429 TooManyRequestsException` when full); `DryRun` → `204`; otherwise
  synchronous `200` with the handler output (handler exceptions → `200` + `X-Amz-Function-Error: Unhandled`, like Lambda).
- `GET /health` → `{"status":"UP","ai":true|false}`.

The s3-emulator's `redaction` notification target posts here; filing-api's "retry redaction" posts a synthetic
single-record event to the same URL.

## Pipeline

Handler input: S3 event JSON (keys URL-decoded: `+` = space). Output:
`{"processed": n, "results": [{"documentId", "status", "redactionCount", "error"}]}` — records outside
`DOCUMENTS_BUCKET`, not under `public/`, not `ObjectCreated:*` or not shaped `public/<appId>/<docId>/<file>` are
skipped and not counted.

Per record:

1. callback `PROCESSING` (`engine` = `CLAUDE+RULES` when Claude is configured, else `RULES`)
2. HeadObject (size ≤ `MAX_OBJECT_BYTES`) → GetObject streamed to `/tmp`; PDFBox loads it with a temp-file stream cache
3. encrypted / malformed / 0 pages / > `MAX_PAGES` / no extractable text at all → `FAILED` with a short error
4. `PdfTextExtractor` — a `PDFTextStripper` subclass that records, for every extracted character, the box of its glyph
   in display space (rotation applied, top-left origin = `PDFRenderer` space); vertical extent from the font size
   (0.9 em up, 0.3 em down) so boxes are generous. Pages without a content stream are kept as empty pages.
5. detection = `RuleBasedDetector` ∪ `ClaudeDetector` → `FindingMerger` (overlaps merged into their union; rules win
   the category, `source` = `RULES` if a rule contributed)
6. `PdfRedactor` — each page rendered at `RENDER_DPI` (capped at 25 MP per page), opaque black boxes (+1.5 pt padding)
   with a small white category label, JPEG-encoded into a **new** PDF with the same page sizes; no text layer, fonts,
   annotations, attachments, document info or XMP survive. The only text is the vector footer
   `PUBLIC COPY — sanitized by OpenFiling — n redactions` in a white band at the bottom of every page.
7. PutObject `public/{app}/{doc}/public-copy.pdf` and `.../redaction-report.json` → callback `COMPLETED`

Logs contain ids, counts and categories only — never document text or detected values. Errors sent in callbacks are
fixed short strings (e.g. `PDF is encrypted (password protected)`).

### Rules (`detect/RuleBasedDetector`)

| Category | Rule |
|---|---|
| `SSN` | `ddd-dd-dddd` / `ddd dd dddd` with area ≠ 000/666/9xx, group ≠ 00, serial ≠ 0000; labelled 9 digits |
| `TAX_ID` | ITIN pattern (9xx-7x/8x…); labelled EIN / ITIN / TIN numbers |
| `PHONE` | US formats (`(555) 010-4477`, `555.010.9876`, `+1 555-010-2233`, ext.), international `+CC …` |
| `EMAIL` | RFC-ish address |
| `ADDRESS` | house number + capitalised street name + suffix (+ unit, city, state ZIP); P.O. Box |
| `DATE_OF_BIRTH` | date next to `DOB` / `Date of Birth` / `born (on)` |
| `FINANCIAL_ACCOUNT` | card numbers (13–19 digits, known prefix, **Luhn**); labelled routing / account numbers; IBAN (mod-97) |
| `GOVERNMENT_ID` | labelled passport / driver's licence numbers (must contain a digit) |
| `IP_ADDRESS` | IPv4 with octet validation |
| `CUI_MARKING` | `CUI`, `CUI//SP-…`, `(CUI)`, `CONTROLLED UNCLASSIFIED INFORMATION`, `FOUO` / `FOR OFFICIAL USE ONLY`, DoD distribution statements B–F |
| `EXPORT_CONTROLLED` | `ITAR`, `EAR99`, `ECCN 5A002…`, "export-controlled", ITAR / EAR / AECA legends |

### Claude (`detect/ClaudeDetector`)

Official Java SDK `com.anthropic:anthropic-java:2.66.0` (`AnthropicOkHttpClient`, 3 retries, 5 min timeout).

- Request: `model` = `CLAUDE_MODEL`, `max_tokens` 16000, a **stable system prompt** (task, what to flag, what never to
  flag — inventors, applicants, assignees, attorneys, examiners, cited authors, companies, technical content; cache
  friendly), the document text only in the user message, chunked by page ranges ≤ `CLAUDE_MAX_CHUNK_CHARS` with
  `=== PAGE n ===` markers.
- Structured output through the typed API: `outputConfig(StructuredOutputConfig.<ClaudeFindings>builder()
  .effort(effort).format(ClaudeFindings.class))` — the SDK derives the JSON schema `{findings: [{page, text, category
  (enum), reason}]}` (all required, `additionalProperties: false`). No thinking config (adaptive is implicit on Opus
  5.5), no temperature/top_p. Server-side refusal fallback via `putAdditionalHeader("anthropic-beta",
  "server-side-fallback-2026-07-01")` + `putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))`.
- `stop_reason` is checked before any content is read; `refusal`, `max_tokens` or any SDK error
  (`RateLimitException`, `UnauthorizedException`/`PermissionDeniedException`, `BadRequestException`,
  `InternalServerException`, `AnthropicServiceException`, `AnthropicIoException`, `AnthropicInvalidDataException`)
  degrades **that chunk** to rules-only, adds a note to the report and reports `engine: RULES`.
- Every returned finding is validated: its `text` must occur on the named page (whitespace-tolerant, whole-word);
  otherwise it is dropped (counted in the notes). Reasons are scrubbed of the value and of anything rule-detectable.

### Report (`redaction-report.json`)

```json
{ "documentId": "…", "engine": "RULES", "model": null, "pageCount": 4, "redactionCount": 21,
  "categories": [{"category": "SSN", "count": 1}, …],
  "findings": [{"page": 2, "category": "SSN", "source": "RULES", "reason": "US Social Security number pattern …", "length": 11}, …],
  "notes": ["AI detection disabled (no ANTHROPIC_API_KEY or REDACTION_AI_ENABLED=false): rules only"],
  "generatedAt": "2026-09-29T06:26:21.82Z" }
```

Never contains the redacted values (only their length). `notes` is an addition to the contract format.

## AWS mapping

| Local | AWS |
|---|---|
| `LocalLambdaRuntime` (9001) | Lambda function `openfiling-redaction`, Java 21 runtime, handler `gov.openfiling.redaction.RedactionHandler::handleRequest`, 2–3 GB memory, 5–15 min timeout, ephemeral storage ≥ 2 GB, SnapStart |
| emulator notification `redaction` | S3 event notification (`s3:ObjectCreated:*`, prefix `public/`, suffix `.pdf`) → Lambda (async invoke, DLQ / on-failure destination) |
| `S3_ENDPOINT` + static keys | execution role with `s3:GetObject` on `openfiling-documents/public/*`, `s3:PutObject` on `openfiling-public-copies/public/*` |
| `ANTHROPIC_API_KEY`, `INTERNAL_TOKEN` env | Secrets Manager (read at init) |
| `CALLBACK_URL` | filing-api internal endpoint behind the internal ALB (VPC) |
| `lambdaZip` / `Dockerfile` | zip deployment package (≈ 49 MB) or container image on `public.ecr.aws/lambda/java:21` |

## Known limitations

- No OCR: a PDF without any extractable text fails; pages without text inside an otherwise textual document are
  re-rendered unchanged and listed in the report notes (e.g. scanned signature pages are **not** analysed).
- Text drawn in vector outlines or embedded in images is not detected (same reason).
- Glyph boxes assume horizontal text; rotated/vertical text runs may get imprecise boxes.
- Category labels use AWT fonts; on minimal Lambda images without fonts the labels are silently skipped (boxes are
  always drawn).
- Rule false positives are possible for unlabelled patterns (phones, IPv4, street addresses); a false positive only
  over-redacts.
