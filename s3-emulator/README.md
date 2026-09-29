# s3-emulator

Local stand-in for **Amazon S3** used by OpenFiling (port **9000**). It speaks the real S3 REST protocol
(path-style), verifies every request's **AWS Signature Version 4**, stores objects on the local file system and
emits **S3 event notifications** to the redaction Lambda and to filing-api. The real AWS SDK v2 (`S3Client`,
`S3Presigner`) and browsers using pre-signed URLs work against it unchanged.

Spring Boot 4.1.1 (`spring-boot-starter-webmvc` + actuator), Java 21, virtual threads. No database, no Spring Security.

## Run

```powershell
. D:\JavaSpring\tools\env.ps1
.\gradlew.bat bootRun          # http://localhost:9000, buckets under ..\data\buckets
.\gradlew.bat build            # tests + JaCoCo gate (>= 80 % lines)
```

| Env var | Default | Meaning |
|---|---|---|
| `PORT` | `9000` | HTTP port |
| `STORAGE_ROOT` | `../data/buckets` | one folder per bucket (relative to the working directory) |
| `S3_ACCESS_KEY` / `S3_SECRET_KEY` | `openfiling-local` / `openfiling-local-secret` | the only accepted credentials |
| `S3_REGION` | `us-east-1` | the only accepted credential-scope region |
| `MAX_OBJECT_BYTES` | `1GB` | PutObject limit (`400 EntityTooLarge`) |
| `CORS_ORIGINS` | `http://localhost:4200` | comma-separated allowed browser origins |
| `REDACTION_INVOKE_URL` | `http://localhost:9001/2015-03-31/functions/openfiling-redaction/invocations` | target `redaction` |
| `FILING_API_EVENTS_URL` | `http://localhost:8080/internal/v1/storage/events` | target `filing-api` |
| `INTERNAL_TOKEN` | `dev-internal-token-change-me` | sent as `X-Internal-Token` to filing-api |

Buckets (`app.buckets`): `openfiling-documents`, `openfiling-public-copies` — created at startup.

## Supported API

| Operation | Request | Notes |
|---|---|---|
| PutObject | `PUT /{bucket}/{key}` | streamed to `<root>/.tmp`, MD5 (+ SHA-256 when declared) computed on the fly, verified, then atomic move; `Content-Type`, `x-amz-meta-*`, `Content-MD5` honoured; ETag = quoted MD5 hex |
| GetObject | `GET /{bucket}/{key}` | `Range: bytes=a-b` / `a-` / `-n` → 206 + `Content-Range`; `response-content-type`, `response-content-disposition` (and the other `response-*`) overrides; `If-Match` / `If-None-Match` |
| HeadObject | `HEAD /{bucket}/{key}` | same headers, no body |
| DeleteObject | `DELETE /{bucket}/{key}` | 204, idempotent, prunes empty folders |
| ListObjectsV2 | `GET /{bucket}?list-type=2&prefix=&delimiter=&max-keys=&continuation-token=&start-after=&encoding-type=url` | V1 (`marker`) answered too |
| HeadBucket / ListBuckets | `HEAD /{bucket}` / `GET /` | |
| anything else | | `501 NotImplemented` (multipart, copy, ACLs, tagging, versioning, bucket create/delete, `aws-chunked` bodies) |

Errors are S3 XML documents (`<Error><Code/><Message/><Resource/><RequestId/></Error>`); every response carries
`x-amz-request-id` (also in the log MDC).

Admin (no signature): `GET /_emulator/health`, `GET /_emulator/buckets` (`[{name, objectCount, totalBytes}]`),
plus actuator `health`, `info`, `metrics`.

## Design notes

- **SigV4 (`auth/SigV4`, `auth/SigV4Verifier`)** — both the `Authorization: AWS4-HMAC-SHA256 …` header form and the
  query-string (pre-signed) form. Canonical URI = the raw path percent-decoded once and re-encoded with SigV4 rules
  (S3 does **not** double-encode and keeps `/`); canonical query = all parameters (minus `X-Amz-Signature` for
  pre-signed URLs) encoded and sorted; canonical headers = exactly the `SignedHeaders`, lower-cased, trimmed, inner
  whitespace collapsed — `host` includes the port (`localhost:9000`), and `host` must be signed. Payload hash is
  `UNSIGNED-PAYLOAD` for pre-signed URLs or the `x-amz-content-sha256` value; a hex hash is verified against the
  streamed body (`400 XAmzContentSHA256Mismatch`), `STREAMING-*` → `501`. Credential scope must be
  `<date of X-Amz-Date>/us-east-1/s3/aws4_request`; `X-Amz-Expires` ≤ 604800; ±15 min clock skew; the signature
  is compared with `MessageDigest.isEqual`. Unit tests replay AWS's published vectors (SigV4 test suite
  `get-vanilla`, signing-key derivation, and the five S3 API-reference examples incl. the pre-signed URL).
- **Pre-signed PUT from the browser** — filing-api signs `content-type;host`, so the upload must send exactly the
  `Content-Type` it was signed with (anything else → `403 SignatureDoesNotMatch`) and no `Authorization` header.
- **Storage (`storage/FileSystemObjectStore`)** — `<root>/<bucket>/<key>` + `<key>.meta.json`
  (`contentType`, `contentLength`, `etag`, `lastModified`, `userMetadata`). Keys: 1–1024 bytes, no leading `/`,
  no backslash, no control characters, no empty / `.` / `..` segments, never ending in `.meta.json`, and the
  resolved path must stay inside the bucket folder. Object move + sidecar write happen under a striped per-key
  lock; reads open the file under the same lock so metadata and bytes always match. Bodies are never buffered.
- **CORS (`web/CorsFilter`)** — runs before everything else, so preflights need no signature and error responses
  also carry `Access-Control-Allow-Origin` (+ `Access-Control-Expose-Headers: ETag, x-amz-request-id`).
- **Event notifications (`notify/*`)** — after each successful PUT, one S3 `ObjectCreated:Put` event (`eventVersion
  2.1`, key URL-encoded like S3: `URLEncoder`, space → `+`, `/` kept) per matching `app.notifications` target, posted
  asynchronously on virtual threads with 3 attempts and exponential back-off (500 ms, 1 s). Failures are logged only.

## AWS mapping

| Emulator | AWS |
|---|---|
| `openfiling-documents`, `openfiling-public-copies` folders | two S3 buckets (Block Public Access on, SSE-S3/KMS, versioning) |
| SigV4 verification with the static dev key | IAM (task role for filing-api, Lambda execution role) |
| `app.cors` | bucket CORS configuration on `openfiling-documents` |
| `app.notifications[redaction]` (prefix `public/`, suffix `.pdf`) | S3 → Lambda event notification (`s3:ObjectCreated:*`) |
| `app.notifications[filing-api]` | S3 → SQS/EventBridge → filing-api consumer (or Lambda → internal endpoint) |
| `/_emulator/*` | CloudWatch S3 storage metrics |

## Known limitations

A key cannot be both an object and a "folder" prefix of another key (file-system limitation → `400
InvalidArgument`); keys containing characters Windows cannot store (`: * ? " < > |`) are rejected; no multipart
upload, copy, versioning, ACLs, SSE headers or `aws-chunked` encoding (the SDK is configured with
`chunkedEncodingEnabled(false)`).
