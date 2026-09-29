# OpenFiling — architecture & API contracts (source of truth)

OpenFiling is a free, open, SaaS-style **patent application intake & document management** portal
(USPTO Patent Center / TSDR equivalents). Anyone can sign up (no payments). Everything runs locally;
every component is shaped so it maps 1:1 onto AWS (S3 pre-signed URLs, S3 event → Lambda, RDS).

Three objectives:

1. **Secure IP document filing portal** — Angular asks the Spring Boot API for an **S3 pre-signed PUT URL**
   and uploads large PDFs **directly to S3** (the API never proxies file bytes).
2. **Role-based application status tracker (TSDR-like)** — Spring Security + JWT: `INVENTOR`s see only their
   own applications; `EXAMINER`s see a queue of all filed applications. Statuses + prosecution history in
   PostgreSQL (RDS stand-in).
3. **Automated PII/CUI redaction** — an `ObjectCreated` event for a *public* document triggers a Lambda that
   extracts the PDF text, asks **Claude** (plus deterministic rules) to find PII / CUI, burns redactions into a
   sanitized **Public Copy** and writes it to a separate bucket.

Java 21, Spring Boot 4.1.1 (Jackson 3 → `tools.jackson.*`), Gradle Groovy DSL with its own wrapper per
component, package root `gov.openfiling.*`. Toolchain: `. D:\JavaSpring\tools\env.ps1` (JDK 21, Node 24,
Postgres 18). Style reference: `D:\JavaSpring\projects\openpto` (same conventions; copy its patterns).

## Components & ports

| Component | Folder | Port | AWS equivalent |
|---|---|---|---|
| Filing API (Spring Boot) | `filing-api/` | 8080 | ECS Fargate behind ALB + RDS PostgreSQL |
| Local S3 (SigV4-verifying emulator, Spring Boot) | `s3-emulator/` | 9000 | Amazon S3 (2 buckets) + S3 event notifications |
| Redaction function (plain Java, no Spring) + local Lambda runtime | `redaction-lambda/` | 9001 | AWS Lambda (Java 21, SnapStart) |
| Web (Angular 22 + ZardUI + Tailwind 4 + Vitest) | `web/` | 4200 | S3 + CloudFront |
| PostgreSQL 18 | `D:\JavaSpring\data\pg` | 5433 | Amazon RDS for PostgreSQL |

```
browser ── /api/* (dev proxy) ──► filing-api :8080 ──► PostgreSQL :5433 (schema filing)
   │                                   │  AWS SDK v2 S3Presigner / S3Client (endpoint override in dev)
   │  PUT/GET pre-signed URL           ▼
   └──────────────────────────────► s3-emulator :9000  buckets on disk: data/buckets/<bucket>/<key>
                                       │ ObjectCreated (S3 event JSON)
                     ┌─────────────────┴──────────────────┐
                     ▼ prefix public/, suffix .pdf        ▼ all ObjectCreated
      redaction-lambda :9001 (Lambda Invoke API)     filing-api POST /internal/v1/storage/events
        GetObject → PDFBox text → rules + Claude → raster redaction → PutObject (public-copies bucket)
        └─► filing-api POST /internal/v1/redactions/events (status callback)
```

## Database (filing-api only)

- DB `openfiling` on localhost:5433, user/password `openfiling`/`openfiling`; tests use DB `openfiling_test`.
- Schema `filing`; Flyway (`spring.flyway.schemas=filing`, `default-schema=filing`),
  `hibernate.default_schema=filing`, `ddl-auto: validate`, `open-in-view: false`.
- Env overrides with dev defaults: `DB_URL` (`jdbc:postgresql://localhost:5433/openfiling`), `DB_USER`, `DB_PASSWORD`.

Tables (UUID PKs unless noted, `timestamptz` for instants, `created_at/updated_at` everywhere relevant):

| Table | Key columns |
|---|---|
| `users` | `id`, `email` (unique, lower-cased), `password_hash`, `display_name`, `art_unit` (nullable, e.g. `2100`), `enabled`, `failed_logins`, `locked_until`, `last_login_at`, `version` |
| `user_roles` | `user_id` FK, `role` (`INVENTOR`/`EXAMINER`/`ADMIN`), PK(user_id, role) |
| `refresh_tokens` | `id`, `user_id`, `family_id`, `token_hash` (SHA-256 hex, unique), `expires_at`, `revoked_at`, `replaced_by`, `last_used_at`, `user_agent`, `ip` |
| `applications` | `id`, `application_number` (unique, nullable until filed, 8 digits e.g. `19000123`), `confirmation_number` (4 digits), `title`, `application_type`, `entity_status`, `abstract_text`, `technology_field`, `attorney_docket_number`, `status`, `owner_id` FK, `examiner_id` FK null, `art_unit`, `filing_date` (date), `submitted_at`, `signature`, `published`, `publication_number`, `publication_date`, `patent_number`, `issue_date`, `version` |
| `application_inventors` | `id`, `application_id` FK (cascade), `sequence`, `given_name`, `family_name`, `city`, `state`, `country` (ISO-3166 alpha-2), `citizenship` |
| `documents` | `id`, `application_id` FK, `doc_code`, `description`, `visibility` (`PUBLIC`/`RESTRICTED`), `file_name`, `content_type`, `size_bytes`, `status` (`PENDING_UPLOAD`/`UPLOADED`/`REJECTED`/`EXPIRED`), `source` (`APPLICANT`/`EXAMINER`), `storage_bucket`, `storage_key` (unique), `etag`, `uploaded_by` FK, `upload_expires_at`, `uploaded_at`, `rejection_reason` |
| `redactions` | `document_id` PK/FK, `status` (`PENDING`/`PROCESSING`/`COMPLETED`/`FAILED`), `engine`, `model`, `page_count`, `redaction_count`, `categories` (jsonb `[{category,count}]`), `public_copy_bucket`, `public_copy_key`, `report_key`, `error`, `duration_ms`, `attempts`, `started_at`, `completed_at` |
| `status_events` | `id` bigserial, `application_id`, `event_code`, `from_status`, `to_status`, `description`, `note`, `actor_id`, `actor_role`, `created_at` |
| `notifications` | `id`, `user_id`, `application_id` null, `type`, `title`, `message`, `read_at`, `created_at` |
| `audit_events` | `id` bigserial, `occurred_at`, `actor_id`, `actor_email`, `action`, `resource_type`, `resource_id`, `ip`, `user_agent`, `request_id`, `details` jsonb |

Sequence `application_serial_seq` → application number = series + zero-padded 6-digit serial
(series `19` UTILITY/PLANT, `29` DESIGN, `63` PROVISIONAL). Display form `19/000,123`.
Indexes on every FK and filter column (`applications(status)`, `(owner_id)`, `(examiner_id)`, `(published)`, `(filing_date)`, trigram/`lower(title)` optional, `audit_events(occurred_at)`, `notifications(user_id, read_at)`).

## Cross-cutting conventions

- JSON camelCase; `LocalDate` `"2026-09-29"`, `Instant` ISO-8601 UTC.
- Errors: RFC 7807 `application/problem+json`
  `{ "type", "title", "status", "detail", "instance", "requestId", "errors": [{ "field", "message" }] }`
  (`errors` for validation / business-rule failures). No stack traces. 400 validation, 401, 403, 404 (also for
  resources the caller may not see — never leak existence), 409 conflicts & optimistic locking (`version` mismatch),
  422 business rule failures (e.g. submit with missing documents → `errors: [{field:"documents", message:"Claims (CLM) is required"}]`),
  429 rate limit / lockout with `Retry-After`.
- Pagination (every list): `{ "content": [...], "page": 0, "size": 20, "totalElements": 123, "totalPages": 7 }`,
  params `page` (0-based), `size` (max 100), `sort=field,asc|desc`.
- `X-Request-Id` read/generated, put in MDC, echoed on the response, included in problems and audit rows.
- Actuator `health` (liveness/readiness), `info`, `metrics`, `prometheus`. Graceful shutdown. Virtual threads.
- OpenAPI: springdoc `/v3/api-docs`, `/swagger-ui.html`, tags + `@Schema` examples.
- Internal calls: header `X-Internal-Token`, env `INTERNAL_TOKEN` (dev default `dev-internal-token-change-me`), `/internal/**` only.

## Identity & security (filing-api)

- Self-issued **RS256 JWT** (Nimbus via `spring-security-oauth2-jose`, like openpto `JwtKeyProvider`), issuer `openfiling`,
  access token TTL **15 min**; claims `sub` (user UUID), `email`, `name`, `roles` (e.g. `["INVENTOR"]`) → `ROLE_*`.
  Key pair persisted at `${app.jwt.key-dir:../data/keys}` (`openfiling-jwt-private.pem`).
- **Refresh tokens**: opaque 256-bit random, only the SHA-256 stored, TTL 7 days, **rotated on every use**; reuse of
  a rotated token revokes the whole family. Logout revokes; "sign out everywhere" revokes all.
- Passwords: delegating encoder (bcrypt). Policy: 10–128 chars, at least one letter and one digit.
  Lockout after 5 failed logins for 15 min (429 + Retry-After). Auth endpoints rate-limited per IP (Bucket4j, 20/min).
- Roles: sign-up always yields `INVENTOR`. `EXAMINER`/`ADMIN` granted by an admin. Admin may perform examiner actions.
- Visibility rules (enforced in services, not just URLs):
  - Owner sees own applications (any status). Examiners/admins see every application **except other users' DRAFTs**.
  - Anyone else → 404.
  - Public (anonymous) sees only `published = true` applications, `PUBLIC` documents, and only their **public copy**.
- Every sensitive action writes an `audit_events` row: `AUTH_LOGIN_SUCCESS`, `AUTH_LOGIN_FAILED`, `AUTH_LOCKED`,
  `AUTH_REFRESH_REUSE`, `AUTH_LOGOUT`, `USER_REGISTERED`, `PASSWORD_CHANGED`, `APPLICATION_CREATED`, `APPLICATION_UPDATED`,
  `APPLICATION_DELETED`, `APPLICATION_SUBMITTED`, `WORKFLOW_ACTION`, `DOCUMENT_UPLOAD_URL_ISSUED`, `DOCUMENT_UPLOADED`,
  `DOCUMENT_REJECTED`, `DOCUMENT_DOWNLOAD` (original vs public copy in details), `DOCUMENT_DELETED`, `REDACTION_COMPLETED`,
  `REDACTION_FAILED`, `REDACTION_RETRIED`, `ADMIN_USER_UPDATED`, `ADMIN_USER_CREATED`.
- Dev seed (profile `dev` only): `admin@openfiling.local` / `Admin#12345` (ADMIN+EXAMINER),
  `examiner@openfiling.local` / `Examiner#12345` (EXAMINER, art unit 2100), `inventor@openfiling.local` / `Inventor#12345`
  (INVENTOR) + ~12 demo applications for the inventor in varied statuses with history (no files).

## Domain enums

`ApplicationType`: `UTILITY`, `DESIGN`, `PLANT`, `PROVISIONAL`. `EntityStatus`: `LARGE`, `SMALL`, `MICRO`.

`ApplicationStatus` (label / category — categories: `DRAFT`, `PENDING`, `ACTION_REQUIRED`, `POSITIVE`, `NEGATIVE`):

| Code | Label | Category |
|---|---|---|
| `DRAFT` | Draft — not yet filed | DRAFT |
| `SUBMITTED` | New application — awaiting pre-examination processing | PENDING |
| `INCOMPLETE` | Notice to file missing parts mailed | ACTION_REQUIRED |
| `PRE_EXAM` | Application undergoing pre-exam processing | PENDING |
| `DOCKETED` | Docketed new case — ready for examination | PENDING |
| `UNDER_EXAMINATION` | Under examination | PENDING |
| `NON_FINAL_REJECTION` | Non-final rejection mailed | ACTION_REQUIRED |
| `RESPONSE_FILED` | Response to office action entered | PENDING |
| `FINAL_REJECTION` | Final rejection mailed | ACTION_REQUIRED |
| `ALLOWED` | Notice of allowance mailed | POSITIVE |
| `ISSUED` | Patented case | POSITIVE (terminal) |
| `ABANDONED` | Abandoned | NEGATIVE (terminal) |

`WorkflowAction` (POST `/api/v1/applications/{id}/actions`). "Examiner" = EXAMINER or ADMIN; "assigned" = the
application's `examiner_id` is the actor (ADMIN bypasses). Every action appends a `status_events` row and notifies
the other party (owner ↔ assigned examiner).

| Action | Who | From → To | Notes |
|---|---|---|---|
| `CLAIM` | Examiner | any filed non-terminal, unassigned | sets examiner = actor (art unit = actor's) — no status change |
| `RELEASE` | assigned | any filed non-terminal | clears examiner — no status change |
| `START_PRE_EXAM` | Examiner | SUBMITTED → PRE_EXAM | |
| `NOTICE_MISSING_PARTS` | Examiner | SUBMITTED, PRE_EXAM → INCOMPLETE | note required |
| `CURE_MISSING_PARTS` | Owner | INCOMPLETE → PRE_EXAM | re-checks required documents |
| `DOCKET` | Examiner | PRE_EXAM → DOCKETED | optional `artUnit`; assigns actor if unassigned |
| `START_EXAMINATION` | assigned | DOCKETED, RESPONSE_FILED → UNDER_EXAMINATION | |
| `ISSUE_NON_FINAL_REJECTION` | assigned | UNDER_EXAMINATION → NON_FINAL_REJECTION | note required; optional `documentIds` (CTNF) |
| `ISSUE_FINAL_REJECTION` | assigned | UNDER_EXAMINATION → FINAL_REJECTION | note required; optional docs (CTFR) |
| `FILE_RESPONSE` | Owner | NON_FINAL_REJECTION, FINAL_REJECTION → RESPONSE_FILED | note required; needs ≥1 `RESP` uploaded after the rejection |
| `ALLOW` | assigned | UNDER_EXAMINATION → ALLOWED | optional docs (NOA) |
| `ISSUE_PATENT` | Examiner | ALLOWED → ISSUED | assigns `patent_number` (`US12500001B2`, B1 if never published) + issue date, auto-publishes |
| `PUBLISH` | Examiner | PRE_EXAM … ALLOWED and not published | assigns `publication_number` (`US20260012345A1`) + date — no status change |
| `ABANDON` | Owner (express) or Examiner (from INCOMPLETE / NON_FINAL / FINAL) | any filed non-terminal → ABANDONED | note required |

Submission (`POST /applications/{id}/submit`) is separate: DRAFT → SUBMITTED, assigns application number,
confirmation number, filing date; requires all documents `UPLOADED` and required codes present —
UTILITY/PLANT: `SPEC`, `CLM`, `ABST`; DESIGN: `SPEC`, `DRW`; PROVISIONAL: `SPEC`.

`DocumentCode` (label, source, default visibility):
`SPEC` Specification (APPLICANT, PUBLIC) · `CLM` Claims (APPLICANT, PUBLIC) · `ABST` Abstract (APPLICANT, PUBLIC) ·
`DRW` Drawings (APPLICANT, PUBLIC) · `ADS` Application Data Sheet (APPLICANT, RESTRICTED) · `OATH` Oath or Declaration
(APPLICANT, RESTRICTED) · `IDS` Information Disclosure Statement (APPLICANT, PUBLIC) · `TRNA` Transmittal Letter
(APPLICANT, PUBLIC) · `RESP` Response to Office Action (APPLICANT, PUBLIC) · `MISC` Miscellaneous Incoming Letter
(APPLICANT, PUBLIC) · `CTNF` Non-Final Rejection (EXAMINER, PUBLIC) · `CTFR` Final Rejection (EXAMINER, PUBLIC) ·
`NOA` Notice of Allowance (EXAMINER, PUBLIC) · `NTMP` Notice to File Missing Parts (EXAMINER, PUBLIC) ·
`EXMISC` Examiner Miscellaneous (EXAMINER, PUBLIC).

Upload permissions: owner — DRAFT/INCOMPLETE: any APPLICANT code; NON_FINAL/FINAL_REJECTION: `RESP, CLM, SPEC, DRW, IDS, MISC`;
other pending statuses: `IDS, MISC`; terminal: none. Examiner (assigned or admin) — EXAMINER codes on any filed non-terminal.
Delete: owner, when the application is DRAFT, or the document is `PENDING_UPLOAD`/`REJECTED`/`EXPIRED`.
Limits: PDF only (`application/pdf`, name ends `.pdf`), `app.storage.max-upload-bytes` (default 500 MiB), max 60 documents per application.

## REST API — filing-api (`/api/v1`)

Shared DTOs:

```jsonc
// UserResponse
{ "id": "uuid", "email": "inventor@openfiling.local", "displayName": "Ada Inventor", "roles": ["INVENTOR"],
  "artUnit": null, "createdAt": "…", "lastLoginAt": "…" }
// AuthResponse
{ "accessToken": "jwt", "expiresIn": 900, "refreshToken": "opaque", "refreshExpiresIn": 604800, "tokenType": "Bearer", "user": UserResponse }
// PartyRef
{ "id": "uuid", "displayName": "Ada Inventor" }
// InventorDto (request & response)
{ "givenName": "Ada", "familyName": "Lovelace", "city": "Alexandria", "state": "VA", "country": "US", "citizenship": "US" }
// ApplicationSummary
{ "id", "applicationNumber": "19000123" | null, "applicationNumberDisplay": "19/000,123" | null, "title",
  "applicationType", "entityStatus", "status", "statusLabel", "statusCategory", "filingDate", "createdAt", "updatedAt",
  "owner": PartyRef, "examiner": PartyRef | null, "artUnit", "documentCount", "published", "daysPending": 12 | null,
  "actionRequired": true }            // true when the *viewer* has something to do
// ApplicationDetail = ApplicationSummary + 
{ "confirmationNumber", "abstractText", "technologyField", "attorneyDocketNumber", "firstNamedInventor": "Ada Lovelace",
  "inventors": [InventorDto], "submittedAt", "signature", "publicationNumber", "publicationDate", "patentNumber", "issueDate",
  "version": 3,
  "permissions": { "canEdit", "canDelete", "canSubmit", "canUpload", "uploadableCodes": ["RESP", …] },
  "availableActions": [ { "action": "FILE_RESPONSE", "label": "File response", "toStatus": "RESPONSE_FILED" | null,
                          "requiresNote": true, "acceptsDocuments": false, "destructive": false } ] }
// DocumentResponse
{ "id", "applicationId", "docCode", "docCodeLabel", "description", "visibility", "fileName", "contentType", "sizeBytes",
  "status", "source", "uploadedBy": PartyRef, "createdAt", "uploadedAt", "rejectionReason",
  "redaction": RedactionResponse | null }       // null for RESTRICTED documents
// RedactionResponse
{ "status": "PENDING|PROCESSING|COMPLETED|FAILED", "engine": "CLAUDE+RULES" | "RULES" | null, "model": "claude-opus-5-5" | null,
  "pageCount", "redactionCount", "categories": [{ "category": "SSN", "count": 2 }], "startedAt", "completedAt", "error",
  "publicCopyAvailable": true }
// StatusEventResponse
{ "id", "eventCode": "ISSUE_NON_FINAL_REJECTION" | "SUBMIT" | "CREATED" | "DOCUMENT_UPLOADED" | …, "fromStatus", "toStatus",
  "description": "Non-final rejection mailed", "note", "actor": { "displayName", "role" }, "createdAt" }
// DownloadResponse
{ "url": "http://localhost:9000/…?X-Amz-…", "expiresAt": "…", "fileName": "spec.pdf" }
```

### Auth & account
| Method & path | Auth | Description |
|---|---|---|
| `POST /auth/register` `{email, password, displayName}` | public | → 201 `AuthResponse` (role INVENTOR); 409 if email taken |
| `POST /auth/login` `{email, password}` | public | → `AuthResponse`; 401 bad credentials; 429 locked |
| `POST /auth/refresh` `{refreshToken}` | public | → `AuthResponse` (rotated); 401 invalid/expired/reused |
| `POST /auth/logout` `{refreshToken}` | public | → 204 (revokes that token) |
| `GET /auth/me` | user | `UserResponse` |
| `PATCH /account/profile` `{displayName}` | user | `UserResponse` |
| `POST /account/password` `{currentPassword, newPassword}` | user | 204, revokes all refresh tokens |
| `GET /account/sessions` | user | `[{id, createdAt, lastUsedAt, expiresAt, userAgent, ip}]` active refresh-token families |
| `DELETE /account/sessions/{id}` | user | 204 revoke that session family |
| `POST /account/sessions/revoke-all` | user | 204 |

### Applications (owner-scoped)
| Method & path | Description |
|---|---|
| `GET /applications?q&status=A,B&type&page&size&sort` | caller's own applications (any role); sort `updatedAt` (default desc), `createdAt`, `filingDate`, `title`, `status` → page of `ApplicationSummary` |
| `POST /applications` `CreateApplicationRequest` | → 201 `ApplicationDetail` (DRAFT). `{title (5–500), applicationType, entityStatus, abstractText (≤5000), technologyField (≤200), attorneyDocketNumber (≤50), inventors (1–20 InventorDto: givenName/familyName 1–100, city ≤100, state ≤50, country/citizenship 2 letters)}` |
| `GET /applications/{id}` | `ApplicationDetail` (visibility rules) |
| `PUT /applications/{id}` `UpdateApplicationRequest` (= create + `version`) | owner; DRAFT or INCOMPLETE; 409 on stale version |
| `DELETE /applications/{id}` | owner; DRAFT only; deletes stored objects → 204 |
| `POST /applications/{id}/submit` `{version, certify: true, signature: "/Ada Lovelace/"}` | owner; DRAFT → SUBMITTED; 422 with `errors` when incomplete; signature must match `^/[^/]{2,100}/$` |
| `POST /applications/{id}/actions` `{action, note?, artUnit?, documentIds?, version}` | workflow (table above) → `ApplicationDetail`; 403 not allowed for role/state; 409 stale |
| `GET /applications/{id}/history` | `[StatusEventResponse]` oldest first |
| `GET /applications/{id}/receipt` | filing receipt `{applicationNumberDisplay, confirmationNumber, title, applicationType, entityStatus, filingDate, submittedAt, signature, applicant: PartyRef+email, inventors, documents: [{docCode, docCodeLabel, fileName, sizeBytes, uploadedAt}]}`; 409 if not filed |
| `GET /dashboard` | `{ totals: {all, drafts, pending, actionRequired, issued, abandoned}, byStatus: [{status, count}], actionRequired: [ApplicationSummary ≤5], recent: [ApplicationSummary ≤6], examiner: null | {unassigned, myDocket, awaitingMyAction, avgPendencyDays, byStatus:[{status,count}]} }` |

### Documents (direct-to-S3 uploads)
| Method & path | Description |
|---|---|
| `GET /applications/{id}/documents` | `[DocumentResponse]` (non-EXPIRED, newest first) |
| `POST /applications/{id}/documents` `{docCode, fileName, contentType, sizeBytes, visibility?, description?}` | → 201 `{ document: DocumentResponse, upload: { url, method: "PUT", headers: {"Content-Type": "application/pdf"}, expiresAt } }` (pre-signed PUT, TTL 15 min). Visibility defaults per code. |
| `POST /documents/{docId}/complete` | caller who created it; HEADs the object, checks size ≤ limit and == declared, ranged-GETs first 5 bytes == `%PDF-`; → `DocumentResponse` UPLOADED (idempotent), or 422 + REJECTED (object deleted); PUBLIC docs get redaction `PENDING` |
| `GET /documents/{docId}` | `DocumentResponse` |
| `GET /documents/{docId}/download?copy=original|public` | pre-signed GET (TTL 5 min, `response-content-disposition=attachment; filename=…`) → `DownloadResponse`; original: owner/examiner/admin; public: same + public rules; audited |
| `DELETE /documents/{docId}` | rules above → 204 |
| `POST /documents/{docId}/redaction/retry` | Examiner; PUBLIC + UPLOADED; re-invokes the Lambda asynchronously → `DocumentResponse` |

Background: every 10 min `PENDING_UPLOAD` documents older than `upload_expires_at + 1h` become `EXPIRED` (object deleted if present).

### Examiner (EXAMINER or ADMIN)
| Method & path | Description |
|---|---|
| `GET /examiner/queue?q&status&assigned=me|unassigned|any&artUnit&type&filedFrom&filedTo&page&size&sort` | every filed (non-DRAFT) application; `q` matches title / application number / owner name; sort `filingDate` (default asc = oldest first), `updatedAt`, `applicationNumber`, `status` → page of `ApplicationSummary` |
| `GET /examiner/stats` | `{ byStatus:[{status,count}], unassigned, myDocket, awaitingMyAction, avgPendencyDays, redactions: {pending, processing, completed, failed} }` |

### Notifications
`GET /notifications?unreadOnly&page&size` → page of `{id, type, title, message, applicationId, applicationNumber, read, createdAt}` ·
`GET /notifications/unread-count` → `{count}` · `POST /notifications/{id}/read` → 204 · `POST /notifications/read-all` → 204.

### Admin (ADMIN)
| Method & path | Description |
|---|---|
| `GET /admin/users?q&role&page&size` | page of `{id, email, displayName, roles, artUnit, enabled, locked, lastLoginAt, createdAt, applicationCount}` |
| `POST /admin/users` `{email, displayName, password, roles, artUnit?}` | → 201 |
| `PATCH /admin/users/{id}` `{roles?, enabled?, artUnit?, unlock?}` | cannot remove own ADMIN role or disable self (422) |
| `GET /admin/audit?actor&action&resourceType&resourceId&from&to&page&size` | page of `{id, occurredAt, actorId, actorEmail, action, resourceType, resourceId, ip, userAgent, requestId, details}` newest first |
| `GET /admin/system` | `{ components: [{name: "database"|"storage"|"redaction-lambda", status: "UP"|"DOWN", detail}], counts: {users, applications, filedApplications, documents, storageBytes}, redactions: {pending, processing, completed, failed, totalRedactions, byCategory:[{category,count}]} }` |

### Public (no auth)
| Method & path | Description |
|---|---|
| `GET /public/applications?q&type&page&size` | published applications → page of `{applicationNumber, applicationNumberDisplay, title, applicationType, status, statusLabel, statusCategory, filingDate, publicationNumber, publicationDate, patentNumber, issueDate, firstNamedInventor, abstractSnippet}` |
| `GET /public/applications/{applicationNumber}` | accepts `19000123` or `19/000,123`; 404 unless published → summary + `{abstractText, technologyField, inventors: [{name, city, state, country}], examinerName, artUnit, history: [{eventCode, description, toStatus, createdAt}], documents: [{id, docCode, docCodeLabel, pageCount, redactionCount, uploadedAt, publicCopyAvailable}]}` (PUBLIC documents with a COMPLETED redaction only) |
| `GET /public/documents/{docId}/download` | public copy pre-signed GET (published + PUBLIC + COMPLETED only), audited |
| `GET /public/stats` | `{applicationsFiled, published, issued, documentsProcessed, redactionsApplied}` |

### Reference (no auth)
`GET /reference` → `{ statuses: [{code, label, category, terminal, description}], documentCodes: [{code, label, source, defaultVisibility, description}], applicationTypes: [{code, label}], entityStatuses: [{code, label}], limits: {maxUploadBytes, maxDocumentsPerApplication, allowedContentTypes: ["application/pdf"]} }`

### Internal (`X-Internal-Token`)
| Method & path | Description |
|---|---|
| `POST /internal/v1/storage/events` | S3 event notification JSON (below). For every `ObjectCreated:*` on the documents bucket whose key matches a `PENDING_UPLOAD` document, run the same verification as `/complete`. Idempotent → 204 |
| `POST /internal/v1/redactions/events` | Lambda status callback `{documentId, status: "PROCESSING"|"COMPLETED"|"FAILED", engine, model, pageCount, redactionCount, categories:[{category,count}], publicCopyBucket, publicCopyKey, reportKey, error, durationMs}` → upsert `redactions` (callbacks may arrive before `/complete`), notify owner, audit → 204. Unknown documentId → 404 |

## Storage protocol (s3-emulator ⇄ AWS SDK v2)

- Buckets: `openfiling-documents` (originals, private) and `openfiling-public-copies` (sanitized). Emulator creates them at startup
  (config list) under `${STORAGE_ROOT:../data/buckets}/<bucket>/<key>`; metadata in a sidecar `<key>.meta.json`
  (`contentType`, `contentLength`, `etag`, `lastModified`, `userMetadata`). Sidecar names can never collide with object keys
  (reject object keys ending `.meta.json`). Keys validated: 1–1024 bytes, no `..` segments, no leading `/`, no backslash, no control chars.
- Object keys: originals `{public|restricted}/{applicationId}/{documentId}/{sanitized-file-name}.pdf`;
  public copies `public/{applicationId}/{documentId}/public-copy.pdf` and `.../redaction-report.json`.
- Path-style URLs: `http://localhost:9000/{bucket}/{key}` (URL-encoded key segments). Region `us-east-1`.
  Dev credentials: access key `openfiling-local`, secret `openfiling-local-secret` (env `S3_ACCESS_KEY` / `S3_SECRET_KEY`).
- **Every request must carry a valid AWS SigV4 signature** — either query-string (pre-signed: `X-Amz-Algorithm=AWS4-HMAC-SHA256`,
  `X-Amz-Credential`, `X-Amz-Date`, `X-Amz-Expires` ≤ 604800, `X-Amz-SignedHeaders`, `X-Amz-Signature`, payload `UNSIGNED-PAYLOAD`)
  or `Authorization: AWS4-HMAC-SHA256 Credential=…, SignedHeaders=…, Signature=…` with `x-amz-date` and
  `x-amz-content-sha256` (hex SHA-256 → verified against the body; `UNSIGNED-PAYLOAD` accepted; `STREAMING-*` → 501
  `NotImplemented`). Clock skew ±15 min. Expired → 403 `AccessDenied` ("Request has expired"). Bad signature → 403
  `SignatureDoesNotMatch`. Unknown key id → 403 `InvalidAccessKeyId`. Constant-time comparison.
- Operations: `PutObject` (streamed to a temp file, atomic move; ETag = quoted MD5 hex; `Content-Type` and `x-amz-meta-*` kept;
  max `${app.max-object-bytes:1GiB}` → 400 `EntityTooLarge`), `GetObject` (supports `Range: bytes=a-b` → 206,
  `response-content-type` / `response-content-disposition` overrides), `HeadObject`, `DeleteObject` (204, idempotent),
  `ListObjectsV2` (`GET /{bucket}?list-type=2&prefix=&max-keys=`), `HeadBucket`. Missing → 404 `NoSuchKey` / `NoSuchBucket`.
  Errors are S3 XML: `<Error><Code>…</Code><Message>…</Message><Resource>…</Resource><RequestId>…</RequestId></Error>`.
- CORS for the browser: allowed origins `${CORS_ORIGINS:http://localhost:4200}`, methods `GET, PUT, HEAD`, any request header,
  expose `ETag, x-amz-request-id`, max-age 3600. `OPTIONS` preflight needs no signature.
- **Event notifications** after each successful PUT (async, virtual threads, 3 attempts with backoff, logged): POST the S3 event
  JSON to every matching target in config `app.notifications`:
  1. `redaction`: bucket `openfiling-documents`, prefix `public/`, suffix `.pdf` →
     `POST ${REDACTION_INVOKE_URL:http://localhost:9001/2015-03-31/functions/openfiling-redaction/invocations}`
     with header `X-Amz-Invocation-Type: Event`.
  2. `filing-api`: bucket `openfiling-documents` (all keys) → `POST ${FILING_API_EVENTS_URL:http://localhost:8080/internal/v1/storage/events}`
     with header `X-Internal-Token`.

```json
{ "Records": [ { "eventVersion": "2.1", "eventSource": "aws:s3", "awsRegion": "us-east-1",
  "eventTime": "2026-09-29T12:00:00.000Z", "eventName": "ObjectCreated:Put",
  "s3": { "s3SchemaVersion": "1.0", "configurationId": "redaction",
          "bucket": { "name": "openfiling-documents", "arn": "arn:aws:s3:::openfiling-documents" },
          "object": { "key": "public/1b2c…/9f8e…/specification.pdf", "size": 48213, "eTag": "0f343b0931126a20f133d67c2b018a3b",
                      "sequencer": "0066F9A1B2C3D4E5F6" } } } ] }
```
Keys in events are URL-encoded exactly like S3 (`URLEncoder` with space → `+`); consumers must decode.

## Redaction function (redaction-lambda)

- Handler `gov.openfiling.redaction.RedactionHandler implements com.amazonaws.services.lambda.runtime.RequestStreamHandler`
  (input: the S3 event JSON above; output: `{ "processed": n, "results": [{documentId, status, redactionCount, error}] }`).
  Plain Java 21, no Spring; deps: `aws-lambda-java-core`, AWS SDK v2 `s3`, PDFBox 3, `com.anthropic:anthropic-java`, Jackson 3.
- Per record (bucket = documents bucket, key starts `public/`): parse `applicationId`/`documentId` from the key → callback
  `PROCESSING` → `GetObject` → PDFBox extract text per page (with glyph positions) → detect → render redacted Public Copy →
  `PutObject` public copy + report → callback `COMPLETED` (or `FAILED` with a short error; never leak document text in errors/logs).
- Detection = **rules** (always) ∪ **Claude** (when an API key is configured and `REDACTION_AI_ENABLED` ≠ false):
  - Rules: US SSN, ITIN/EIN (labelled), phone numbers, e-mail addresses, dates of birth (labelled), credit-card numbers (Luhn),
    bank account/routing numbers (labelled), passport / driver's licence numbers (labelled), street addresses, IPv4 addresses,
    CUI banner/portion markings (`CUI`, `CUI//SP-…`, `CONTROLLED UNCLASSIFIED INFORMATION`, `(CUI)`), export-control markings
    (`ITAR`, `EAR99`, `ECCN 5A002`-style), `FOUO`/`FOR OFFICIAL USE ONLY`, DoD distribution statements.
  - Claude: model `${CLAUDE_MODEL:claude-opus-5-5}`, structured output (typed record via `outputConfig(Class)`), effort
    `${CLAUDE_EFFORT:medium}`, text chunked by page ranges (≤ ~60k chars per request), returns findings
    `{page, text (exact substring), category, reason}`; server-side refusal fallback enabled; refusals / API errors degrade to
    rules-only for that chunk and set `engine` to `RULES` + note in the report. Categories:
    `PERSON_NAME`, `SSN`, `TAX_ID`, `PHONE`, `EMAIL`, `ADDRESS`, `DATE_OF_BIRTH`, `FINANCIAL_ACCOUNT`, `GOVERNMENT_ID`,
    `IP_ADDRESS`, `MEDICAL`, `CUI_MARKING`, `EXPORT_CONTROLLED`, `OTHER_SENSITIVE`.
    Inventor names on a patent are public record — the prompt tells Claude **not** to redact inventor/applicant names
    appearing as named inventors, assignees, attorneys or cited authors; only private-individual PII.
- Public Copy = every page rasterized (default 150 DPI) with opaque black boxes over every finding (plus a small category label),
  rebuilt as a new image-only PDF (no hidden text layer survives), document info + XMP metadata removed, footer on each page
  `PUBLIC COPY — sanitized by OpenFiling — n redactions`. Report JSON `{documentId, engine, model, pageCount, redactionCount,
  categories, findings: [{page, category, source: "RULES"|"CLAUDE", reason, length}] , generatedAt}` — **never** the redacted values.
- Env: `DOCUMENTS_BUCKET` (`openfiling-documents`), `PUBLIC_BUCKET` (`openfiling-public-copies`), `S3_ENDPOINT`
  (`http://localhost:9000`; unset in AWS), `S3_REGION` (`us-east-1`), `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `S3_PATH_STYLE` (true),
  `ANTHROPIC_API_KEY` (optional), `CLAUDE_MODEL`, `CLAUDE_EFFORT`, `REDACTION_AI_ENABLED`, `CALLBACK_URL`
  (`http://localhost:8080/internal/v1/redactions/events`), `INTERNAL_TOKEN`, `RENDER_DPI` (150), `MAX_PAGES` (500).
- Local runtime `gov.openfiling.redaction.local.LocalLambdaRuntime` (JDK `HttpServer`, port `${PORT:9001}`):
  `POST /2015-03-31/functions/openfiling-redaction/invocations` — `X-Amz-Invocation-Type: Event` → 202 and run async
  (bounded executor, max 4 concurrent), otherwise synchronous 200 with the handler output; `GET /health` → `{"status":"UP","ai":true|false}`.
- filing-api retry (`POST /documents/{id}/redaction/retry`) posts a synthetic single-record S3 event to the same invoke URL
  (`app.redaction.invoke-url`).
- `samples/` (repo root) contains generated demo PDFs with **fictitious** PII/CUI (e.g. SSN `078-05-1120`-style specimen numbers) to try the pipeline.

## Web (Angular 22 + ZardUI + Tailwind 4)

- Dev server :4200 proxies `/api`, `/v3`, `/swagger-ui*`, `/actuator` → `http://localhost:8080`.
- Pre-signed uploads go **directly** from the browser to the URL returned by the API (`http://localhost:9000/...`) using
  `XMLHttpRequest` (upload progress + abort), sending exactly the returned headers and **no** `Authorization` header; then
  `POST /documents/{id}/complete`; then poll `GET /documents/{id}` every 3 s while redaction is `PENDING`/`PROCESSING`.
- Auth: access token in memory + localStorage session; on 401 the interceptor performs **one** refresh (`/auth/refresh`, single-flight)
  and retries, else logs out → `/login?returnUrl=…`.
- Routes: `/` landing · `/login` · `/register` · `/dashboard` (auth; role-aware) · `/applications` (my applications) ·
  `/applications/new` (wizard: details → inventors → review; creates DRAFT then goes to detail/documents) ·
  `/applications/:id` (tabs: Status & history | Documents | Bibliographic data | Inventors; action panel from `availableActions`) ·
  `/applications/:id/receipt` (printable filing receipt) · `/examiner` (queue, EXAMINER/ADMIN) · `/search` (public published
  applications) · `/status` + `/status/:applicationNumber` (public TSDR-style status & public copies) · `/notifications` ·
  `/account` (profile, password, sessions) · `/admin/users`, `/admin/audit`, `/admin/system` (ADMIN) · `**` not found.
