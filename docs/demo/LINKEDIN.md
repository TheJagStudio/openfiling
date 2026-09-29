# LinkedIn — "Add project" form

**Project name**
OpenFiling — Secure Patent Application Intake & AI Document Redaction

**Associated with**
(your current role / "Personal project")

**Start date – End date**
Sep 2026 – Sep 2026

**Skills** (LinkedIn allows up to 5)
Spring Boot · Angular · Amazon Web Services (AWS) · Spring Security · PostgreSQL

**Project URL**
https://github.com/<your-username>/openfiling

**Media**
Upload `docs/demo/openfiling-demo.mp4` (or a YouTube link) and `docs/demo/landing.png`.

**Description** (≈1,600 characters; the limit is 2,000)

A free, open SaaS-style portal modelled on USPTO Patent Center and TSDR, demonstrating cloud-native document handling
and Public Trust security requirements.

• Secure filing: the Angular 22 (ZardUI + Tailwind) app requests an AWS S3 pre-signed URL from a Spring Boot 4 API and
uploads large PDFs directly to S3. The API never proxies file bytes; it verifies size and PDF magic bytes afterwards.
Built on AWS SDK v2, with a SigV4-verifying S3 emulator for fully local development.

• Role-based status tracker: Spring Security with RS256 JWTs, rotating refresh tokens with reuse detection, account
lockout and rate limiting. Inventors see only their own applications; examiners work a queue of all filings through a
12-status prosecution workflow stored in PostgreSQL (RDS), with notifications and an append-only audit log.

• Automated PII/CUI redaction: an S3 ObjectCreated event triggers a Java 21 Lambda that extracts text with PDFBox,
detects PII and Controlled Unclassified Information with Claude (structured output) plus deterministic rules, and
publishes a rasterized, metadata-free "Public Copy" to a separate bucket.

Engineering: Flyway, MapStruct, RFC 7807 errors, OpenAPI, Actuator/Prometheus, Docker, GitHub Actions CI, and a
Terraform AWS reference (KMS, S3 events → Lambda, least-privilege IAM, RDS). Over 2,200 automated tests (JUnit 5,
Mockito, MockMvc, Vitest), with 94–99% coverage across the Java components.

**Short post to share it**

I built OpenFiling, a patent application portal showing how federal-style systems can handle sensitive PDFs safely:
direct-to-S3 uploads via pre-signed URLs, JWT role-based access for inventors and examiners, and an S3-triggered
Lambda that uses Claude to redact PII and CUI before anything becomes public. Spring Boot 4 · Angular · PostgreSQL ·
AWS. 60-second demo below 👇
#SpringBoot #Angular #AWS #Java #CloudSecurity #GenAI
