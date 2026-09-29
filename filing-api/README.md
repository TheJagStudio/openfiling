# filing-api — OpenFiling Filing API (port 8080)

Patent application intake and document management (USPTO Patent Center style), a TSDR-like status
tracker with an examiner workflow, notifications, audit log, and the control plane for automated
PII/CUI redaction. Contract: [`../docs/CONTRACTS.md`](../docs/CONTRACTS.md) (filing-api sections).

Java 21 · Spring Boot 4.1.1 (Spring Framework 7, Security 7, Hibernate 7, Jackson 3) · Flyway ·
MapStruct · AWS SDK v2 (S3 + S3Presigner) · Bucket4j · springdoc 3.1.

## Run

```powershell
. D:\JavaSpring\tools\env.ps1
cd D:\JavaSpring\projects\openfiling\filing-api
.\gradlew.bat bootRun --args="--spring.profiles.active=dev"   # or:
.\gradlew.bat bootJar; java -jar build\libs\filing-api-0.0.1-SNAPSHOT.jar --spring.profiles.active=dev
.\gradlew.bat build        # compile + unit/slice/integration tests + ≥80 % line coverage gate on the service package
```

Needs PostgreSQL 18 on `localhost:5433` (DB `openfiling`, tests use `openfiling_test`; user/password
`openfiling`). The API starts without the storage emulator (`:9000`) or the redaction runtime (`:9001`);
they are only needed for real uploads/downloads and redaction (`/admin/system` shows them DOWN otherwise).

The `dev` profile seeds (once; skipped when the users exist):

| User | Password | Roles |
|---|---|---|
| `admin@openfiling.local` | `Admin#12345` | ADMIN, EXAMINER (art unit 2100) |
| `examiner@openfiling.local` | `Examiner#12345` | EXAMINER (art unit 2100) |
| `inventor@openfiling.local` | `Inventor#12345` | INVENTOR — 13 demo applications, DRAFT → ISSUED/ABANDONED, with history |

- Swagger UI: http://localhost:8080/swagger-ui.html · OpenAPI: http://localhost:8080/v3/api-docs
- Probes: `/actuator/health/liveness`, `/actuator/health/readiness`, `/actuator/info` (public);
  `/actuator/metrics`, `/actuator/prometheus` need ROLE_ADMIN.

### Configuration (environment variables)

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` / `DB_USER` / `DB_PASSWORD` | `jdbc:postgresql://localhost:5433/openfiling` / `openfiling` / `openfiling` | Database (schema `filing`, created by Flyway) |
| `PORT` | `8080` | HTTP port |
| `JWT_KEY_DIR` | `../data/keys` | RSA key (`openfiling-jwt-private.pem`, generated on first start) |
| `INTERNAL_TOKEN` | `dev-internal-token-change-me` | `X-Internal-Token` for `/internal/**` |
| `CORS_ORIGINS` | `http://localhost:4200` | Allowed browser origins for `/api/**` |
| `S3_ENDPOINT` | `http://localhost:9000` | S3 endpoint for server-side calls; **blank = real AWS** |
| `S3_PUBLIC_ENDPOINT` | (= `S3_ENDPOINT`) | Host used **only** in pre-signed URLs (must be browser-reachable, e.g. in Docker) |
| `S3_REGION` / `S3_ACCESS_KEY` / `S3_SECRET_KEY` | `us-east-1` / `openfiling-local` / `openfiling-local-secret` | Blank keys = default credentials chain (task role) |
| `DOCUMENTS_BUCKET` / `PUBLIC_BUCKET` | `openfiling-documents` / `openfiling-public-copies` | Buckets |
| `REDACTION_INVOKE_URL` | `http://localhost:9001/2015-03-31/functions/openfiling-redaction/invocations` | Lambda Invoke API (retry) |
| `REDACTION_HEALTH_URL` | `http://localhost:9001/health` | Lambda runtime health (admin system page) |
| `AUTH_RATE_LIMIT_PER_MINUTE` | `20` | Bucket4j limit per client IP on `POST /api/v1/auth/**` |
| `SEED_ENABLED` | `false` (`true` in `dev`) | Demo data |

## Design notes

- **Direct-to-S3 uploads.** `POST /applications/{id}/documents` stores a `PENDING_UPLOAD` row and returns an
  `S3Presigner` PUT URL (15 min) whose signature covers `Content-Type: application/pdf` — the browser must send exactly
  that header. The API never proxies bytes. Verification happens on `POST /documents/{id}/complete` **or** the S3
  `ObjectCreated` notification (`/internal/v1/storage/events`), whichever comes first (row lock, idempotent):
  `HeadObject` (size ≤ limit and == declared, content type), ranged `GetObject` of 5 bytes (`%PDF-`). Failures mark the
  document `REJECTED`, delete the object, and answer 422. Downloads are pre-signed GETs (5 min) with
  `response-content-disposition=attachment`. Stale `PENDING_UPLOAD` rows expire every 10 minutes.
- **Keys**: `{public|restricted}/{applicationId}/{documentId}/{sanitized}.pdf`; only `public/` triggers redaction.
  Public copies live in the separate public-copies bucket and are the only thing anonymous users can download.
- **Workflow** lives in one pure class, `WorkflowPolicy` (who may do what in which status; also produces
  `availableActions`, `permissions`, `actionRequired`), exhaustively tested (every action × status × role ×
  assignment). `WorkflowService` validates notes/attachments/version, applies side effects (numbers, assignment,
  publication, patent grant), appends `status_events`, notifies the other party, audits.
- **Visibility** is enforced in services (`ApplicationAccess`): owners see their applications, examiners/admins every
  filed one, everyone else gets **404** (existence never leaks); visible-but-not-allowed is 403.
- **Identity**: RS256 JWT (15 min) + opaque 256-bit refresh tokens (SHA-256 stored, 7 days) rotated on every use;
  replaying a rotated token revokes the whole family (session) and writes `AUTH_REFRESH_REUSE`. Lockout after 5
  failures (429 + `Retry-After`). Failed-login audit rows use `REQUIRES_NEW` so they survive the failing request.
- **Consistency**: `@Version` on applications/users with explicit `version` checks (409); object deletions run after
  commit; redaction callbacks upsert (they may arrive before `/complete`).
- **Errors**: RFC 7807 everywhere (`requestId`, `errors[]`), no stack traces. Logs never contain tokens, passwords,
  pre-signed URLs or document text.

## Endpoints (all under `/api/v1` unless noted)

| Area | Endpoints |
|---|---|
| Auth | `POST /auth/register`, `/auth/login`, `/auth/refresh`, `/auth/logout`, `GET /auth/me` |
| Account | `PATCH /account/profile`, `POST /account/password`, `GET /account/sessions`, `DELETE /account/sessions/{id}`, `POST /account/sessions/revoke-all` |
| Applications | `GET/POST /applications`, `GET/PUT/DELETE /applications/{id}`, `POST /applications/{id}/submit`, `POST /applications/{id}/actions`, `GET /applications/{id}/history`, `GET /applications/{id}/receipt`, `GET /dashboard` |
| Documents | `GET/POST /applications/{id}/documents`, `POST /documents/{id}/complete`, `GET/DELETE /documents/{id}`, `GET /documents/{id}/download?copy=original\|public`, `POST /documents/{id}/redaction/retry` |
| Examiner | `GET /examiner/queue`, `GET /examiner/stats` |
| Notifications | `GET /notifications`, `GET /notifications/unread-count`, `POST /notifications/{id}/read`, `POST /notifications/read-all` |
| Admin | `GET/POST /admin/users`, `PATCH /admin/users/{id}`, `GET /admin/audit`, `GET /admin/system` |
| Public | `GET /public/applications`, `GET /public/applications/{number}` (also `/public/applications/19/000,123`), `GET /public/documents/{id}/download`, `GET /public/stats` |
| Reference | `GET /reference` |
| Internal (`X-Internal-Token`) | `POST /internal/v1/storage/events`, `POST /internal/v1/redactions/events` |

## Layout

```
gov.openfiling.api
├── config       AppProperties (typed app.*), SecurityConfig (2 chains + CORS), JwtConfig, StorageConfig (S3Client/S3Presigner),
│                OpenApiConfig, RequestIdFilter, SchedulingConfig, Clock
├── controller   Auth, Account, Application, Document, Examiner, Dashboard, Notification, Admin, Public, Reference, Internal
├── dto          request/response records (@Schema examples), PageResponse (contract page shape)
├── exception    domain exceptions + GlobalExceptionHandler (RFC 7807 + requestId + errors[])
├── mapper       MapStruct AccountMapper / InventorMapper, RedactionJson (jsonb columns)
├── model        JPA entities + enums (ApplicationStatus, WorkflowAction, DocumentCode, …)
├── repository   Spring Data repos (entity graphs, grouped counts) + spec/ (Criteria filters, sort allow-lists)
├── security     JwtKeyProvider, InternalTokenFilter, AuthRateLimitFilter (Bucket4j), ProblemResponseWriter, Viewer
├── service      WorkflowPolicy, Workflow/Application/Document/Redaction/StorageEvent/Examiner/Dashboard/Admin/Public/…
├── storage      DocumentStorage port, S3DocumentStorage (AWS SDK v2), StorageKeys, RedactionInvoker (RestClient)
├── scheduling   UploadExpiryJob
└── seed         DevSeedRunner (profile dev)
```

## AWS mapping

| Local | AWS |
|---|---|
| filing-api jar / Docker image | ECS Fargate service behind an ALB (`/api/*`), task role for S3 + Lambda invoke |
| PostgreSQL :5433 | Amazon RDS for PostgreSQL (credentials from Secrets Manager → `DB_*`) |
| s3-emulator :9000 | Amazon S3: `openfiling-documents` (private, SSE-KMS, CORS for PUT/GET) + `openfiling-public-copies`; unset `S3_ENDPOINT`/keys |
| emulator → `/internal/v1/storage/events` | S3 event notification → SQS/EventBridge → a small forwarder (or the API polling SQS) |
| redaction runtime :9001 | AWS Lambda (Java 21, SnapStart) triggered by `ObjectCreated` on `public/*.pdf`; retry uses `lambda:InvokeFunction` (async) |
| `INTERNAL_TOKEN`, JWT key dir | Secrets Manager / a mounted secret; internal routes only reachable inside the VPC |
| UploadExpiryJob | EventBridge Scheduler or keep in-process (idempotent) |
| logs / `/actuator/prometheus` | CloudWatch Logs (+ request id), Amazon Managed Prometheus / CloudWatch metrics |
