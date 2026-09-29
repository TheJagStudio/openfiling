# OpenFiling web

Angular front end for **OpenFiling**, a free, open patent application intake and document management portal
(a USPTO Patent Center / TSDR equivalent). Inventors file applications and upload large PDFs **straight to S3**
through pre-signed URLs. Examiners work a queue and move applications through the prosecution workflow. Public
documents are sanitized automatically (PII/CUI redacted by Claude plus rules) into a **Public Copy**.

The browser talks to **filing-api** (`:8080`) with relative URLs, and PUTs/GETs files directly to the S3 endpoint the
API signs (`http://localhost:9000` in dev). The REST contract is `../docs/CONTRACTS.md`.

| | |
|---|---|
| Framework | Angular **22.2** (standalone, zoneless, signals, `rxResource`, `linkedSignal`, OnPush everywhere) |
| UI kit | **ZardUI** (shadcn-style, copied into `src/app/shared/`), generated with `zard-cli` (see `components.json`) |
| Styling | **Tailwind CSS 4** via `@tailwindcss/postcss` (`.postcssrc.json`), design tokens in `src/styles.css` |
| Icons | `@ng-icons/lucide` |
| Tests | Vitest 5 + jsdom through `@angular/build:unit-test`, 89 specs |

## Run

```powershell
. D:\JavaSpring\tools\env.ps1        # local Node 24 + npm prefix/cache
cd D:\JavaSpring\projects\openfiling\web
npm install
npm start                            # ng serve on http://localhost:4200 (proxy → filing-api :8080)
npm run build                        # production build → dist/web/browser
npm test -- --watch=false            # headless unit tests (jsdom, no browser needed)
```

Or from Git Bash:

```bash
export PATH=/d/JavaSpring/tools/node:/d/JavaSpring/tools/npm-global:$PATH \
       npm_config_cache=/d/JavaSpring/tools/npm-cache npm_config_prefix=/d/JavaSpring/tools/npm-global
npm install && npm run build && npm test -- --watch=false
```

`proxy.conf.json` forwards `/api`, `/v3`, `/swagger-ui`, `/swagger-ui.html` and `/actuator` to `http://localhost:8080`.
Pre-signed S3 URLs are absolute (`http://localhost:9000/...`), so they bypass the proxy. The S3 emulator allows CORS
from `http://localhost:4200`.

Dev seed accounts (filing-api `dev` profile): `inventor@openfiling.local` / `Inventor#12345`,
`examiner@openfiling.local` / `Examiner#12345`, `admin@openfiling.local` / `Admin#12345`.

Docker: `Dockerfile` builds the app and serves it with nginx on `:8080`. `nginx.conf` proxies `/api`, `/v3`,
`/swagger-ui*` and `/actuator` to an upstream `filing-api:8080`.

## Structure

```
src/app/
  core/
    models/         DTOs + enums mirroring CONTRACTS.md (statuses with label/category/explanation, document codes,
                    workflow actions, roles, limits, required documents per type, signature regex)
    api/            one HttpClient service per resource: AuthApi, AccountApi, ApplicationsApi (+ dashboard),
                    DocumentsApi, ExaminerApi, NotificationsApi, AdminApi, PublicApi, ReferenceApi/ReferenceStore
    auth/           AuthStore (access + refresh tokens, cross-tab sync), SessionService (single-flight refresh,
                    proactive refresh, logout), guards: authGuard, guestGuard, examinerGuard, adminGuard
    http/           interceptors (X-Request-Id, bearer + refresh-and-retry, errors), toHttpParams/toPage/isOwnApi,
                    ProblemDetail helpers
    upload/         DirectUploadService (XHR PUT to the pre-signed URL, S3 XML error mapping, /complete, polling),
                    UploadQueue (validation, concurrency 2, cancel/retry), upload-validation (PDF checks, %PDF- magic)
    notifications/  UnreadStore (unread badge, polled every 30 s while signed in)
    notify/         Notifier (toast facade over ZardUI sonner)
    application-number.ts, format.ts, status-tone.ts, title.strategy.ts
  layout/           shell (official banner, role-aware nav, admin menu, notification bell, user menu, theme toggle,
                    mobile sheet nav, footer), nav.ts
  ui/               status badge, redaction chip, stat card, sort header, pager, bar chart, application list,
                    breadcrumbs, page header, error state, copy button, brand mark, download helper
  features/
    landing/        hero, 3 capabilities, how-it-works, live /public/stats, CTAs
    auth/           login (lockout-aware), register (live password policy)
    dashboard/      inventor totals, action-required, recent, status chart, examiner workload block
    applications/   my-applications (URL-state filters/sort/paging), new-application wizard, receipt (printable),
                    detail/ (status timeline, documents + upload panel, bibliographic + inventors editors,
                    workflow action dialog, submit dialog, workflow-forms.ts pure logic)
    examiner/       queue with KPI cards, filters, sortable table, quick Claim
    public/         search, status lookup, TSDR-style public status page
    notifications/  list, mark read / all read
    account/        profile, change password, active sessions (revoke, sign out everywhere)
    admin/          users (+ create/edit dialog), audit log (JSON details sheet), system (health, counts, redaction stats)
    not-found/
  shared/           ZardUI components, core and utils (generated; lightly patched, see below)
src/testing/        spec fixtures (never imported by application code)
```

### Routes

| Route | Page | Guard |
|---|---|---|
| `/` | Landing | |
| `/login`, `/register` | Auth forms | guest |
| `/dashboard` | Role-aware dashboard | auth |
| `/applications` | My applications `?q&status=A,B&type&sort&page` | auth |
| `/applications/new` | Wizard: bibliographic data, inventors, review, then creates a DRAFT and opens `?tab=documents` | auth |
| `/applications/:id` | Detail `?tab=status\|documents\|biblio\|inventors` with an action panel | auth |
| `/applications/:id/receipt` | Electronic Acknowledgement Receipt (print styles) | auth |
| `/examiner` | Queue `?q&status&assigned&type&artUnit&filedFrom&filedTo&sort&page` | examiner (EXAMINER or ADMIN) |
| `/search` | Published applications `?q&type&page` | |
| `/status`, `/status/:applicationNumber` | Public status lookup / TSDR-style page | |
| `/notifications`, `/account` | Notifications, account & security | auth |
| `/admin/users`, `/admin/audit`, `/admin/system` | Administration | admin |
| `**` | 404 | |

## Design notes

- **Visual identity.** It's meant to feel like a trustworthy federal portal without copying one: a deep navy/indigo
  primary, cool slate neutrals, and one restrained seal-gold accent (`--seal`, used for the banner dot, the first
  named inventor and CTAs on navy). Typography is Public Sans, the USWDS typeface, with system fallbacks. There is an
  official-style banner strip, an engraved "paper grid" texture on hero and official panels, and the brand mark is a
  filed document with a verification seal. Light, dark and system themes all keep AA contrast.
- **Tokens and refresh.**
  - The access token (15 min) and the rotating refresh token (7 days) are persisted together in localStorage.
    The session lives as long as the refresh token.
  - `authInterceptor` adds the bearer token only for our own API, never for the pre-signed S3 URL. If the access
    token already expired, it refreshes first. On a 401 it runs one single-flight `POST /auth/refresh` and retries
    once.
  - If the refresh is rejected, the original 401 reaches `errorInterceptor`, which signs the user out and redirects
    to `/login?returnUrl=…`. `errorInterceptor` wraps the auth interceptor, so it only ever sees final outcomes.
  - `SessionService` refreshes proactively 60 s before expiry, with jitter. Tabs share rotations through `storage`
    events, so two tabs never replay the same refresh token (the API treats that as reuse and revokes the family).
  - Logout revokes the refresh token server-side (best effort).
- **Direct-to-S3 uploads.**
  1. `POST /applications/{id}/documents` returns the pre-signed PUT.
  2. `XMLHttpRequest` PUTs the file with exactly the returned headers and no `Authorization`, reporting progress.
     Unsubscribing calls `xhr.abort()`.
  3. `POST /documents/{id}/complete` verifies the upload.
  4. The client polls `GET /documents/{id}` every 3 s while redaction is `PENDING`/`PROCESSING`, and stops on
     destroy.

  S3 XML errors (`AccessDenied`/expired, `SignatureDoesNotMatch`, `EntityTooLarge`…) become readable messages.
  Before any request, the client checks the name (`.pdf`), the MIME type, the size against `/reference`
  `limits.maxUploadBytes`, and that `File.slice(0, 5)` is `%PDF-`. The queue runs 2 uploads at a time, with per-file
  document code and visibility (defaults per code from `/reference`), progress bars, and cancel/retry. The drop zone
  is a keyboard-operable button.
- **Workflow.** The action panel is built only from `availableActions`, so the server stays the authority. Each
  action opens a dialog:
  - a note when `requiresNote`
  - an art unit for `DOCKET`
  - a picker of uploaded examiner documents when `acceptsDocuments`
  - destructive styling for `ABANDON`

  Every POST carries `version` for optimistic locking. A 409 explains the conflict and reloads the page behind the
  dialog. Submitting shows the required-documents checklist (UTILITY/PLANT: SPEC+CLM+ABST, DESIGN: SPEC+DRW,
  PROVISIONAL: SPEC), a certification checkbox and an S-signature matching `^/[^/]{2,100}/$`. 422 `errors[]` are
  listed inline.
- **The URL is the state** for lists (my applications, examiner queue, public search) and for the detail tab.
  Back/forward and shared links work.
- **Errors are ProblemDetail everywhere.** Forms and not-found pages opt out of toasts (`SKIP_ERROR_TOAST`) and render
  errors inline. Unpublished applications return 404 on the public status page. That page explains the application is
  confidential rather than showing a generic error.
- **Accessibility.**
  - Skip link, landmarks, labelled controls, and `aria-invalid` + `aria-describedby` on every field.
  - Focus moves to the first invalid field on submit and to the step heading in the wizard.
  - Dialogs trap and restore focus (CDK).
  - `aria-sort` on sortable headers, `aria-live` result counts and upload status, and screen-reader tables behind charts.
  - Visible focus rings, reduced-motion support, and tab strips that scroll on narrow screens.

### Local patches to generated ZardUI code

- `tabs`: `zTabChange`, `zDeselect` and `activeTabIndex` are public, so the detail page can sync tabs with `?tab=`.
- `select`: `zAriaLabel` input (inherited from the sibling project). `card`/`breadcrumb` barrels re-export their
  imports. The breadcrumb is wrapped (`ui/breadcrumbs.component.ts`) to avoid a `RouterLink` selector clash.

## Contract assumptions

These are places where `CONTRACTS.md` is silent, and the web app assumes the following:

- `StatusEventResponse.actor` may be `null` for system events, and `actor.role` may be `SYSTEM`.
- `RedactionResponse.categories[].category` uses the redaction-lambda category names (`SSN`, `CUI_MARKING`, …).
  Unknown categories are shown verbatim.
- `/admin/audit` `from`/`to` are sent as ISO instants: start and end of the chosen local day.
- `/admin/audit` `details` may be a JSON object or a JSON string. Both are pretty-printed.
- `GET /notifications` items carry `applicationNumber` in 8-digit form. It is shown formatted.
- The contract doesn't define a note length limit, so the client caps notes at 4000 characters.
- Art unit input is validated as 4 digits.
- Examiner "Claim" from the queue first fetches the detail, because `ApplicationSummary` has no `version`.

## Known gaps

- No end-to-end tests against a running API, S3 emulator or Lambda. HTTP is tested with `HttpTestingController` and
  uploads with a mock XHR. The UI was checked in a browser with stubbed responses.
- Uploads are single PUTs, as the contract specifies. There is no multipart/resumable upload for very large files.
- Only the application detail page and the public status page get data-driven document titles.
