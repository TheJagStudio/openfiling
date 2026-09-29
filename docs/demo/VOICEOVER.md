# OpenFiling — 60-second voice-over script

About 150 words at a relaxed ~150 wpm. Each section starts when the on-screen caption changes.

| Time | On screen | Voice-over |
|---|---|---|
| 0:00–0:07 | Landing page | Meet OpenFiling, a free, open patent filing portal modelled on USPTO Patent Center, built with Spring Boot 4, Angular and PostgreSQL, and designed to map one-to-one onto AWS. |
| 0:07–0:11 | Inventor dashboard | Inventors sign in with RS256 JWTs and rotating refresh tokens, and only ever see their own filings. |
| 0:11–0:13 | New application wizard | A guided wizard captures bibliographic data and inventors. |
| 0:13–0:22 | Direct-to-S3 upload, redaction chips | Uploads go straight to S3 using pre-signed URLs, so large PDFs never touch the API server. Each public upload fires an S3 event into a Lambda that uses Claude plus rules to find PII and controlled unclassified information, and burns the redactions into a sanitized public copy. |
| 0:22–0:28 | Submit dialog, filed | Filing checks the required documents, captures an S-signature, and issues an application number and receipt. |
| 0:28–0:37 | Examiner queue and workflow | Examiners get a TSDR-style queue of every filed case and move it through a role-checked prosecution workflow. |
| 0:37–0:44 | Public status page | The public sees published applications, and only the redacted copies. |
| 0:44–0:56 | Admin system and audit log | Admins monitor the database, buckets and Lambda, and every sensitive action is audited. |
| 0:56–1:00 | Outro | OpenFiling: secure intake, transparent status, safer public records. |

## Plain read-through

Meet OpenFiling, a free, open patent filing portal modelled on USPTO Patent Center, built with Spring Boot 4, Angular and
PostgreSQL, and designed to map one-to-one onto AWS.
Inventors sign in with RS256 JWTs and rotating refresh tokens, and only ever see their own filings.
A guided wizard captures bibliographic data and inventors.
Uploads go straight to S3 using pre-signed URLs, so large PDFs never touch the API server.
Each public upload fires an S3 event into a Lambda that uses Claude plus rules to find PII and controlled unclassified
information, and burns the redactions into a sanitized public copy.
Filing checks the required documents, captures an S-signature, and issues an application number and receipt.
Examiners get a TSDR-style queue of every filed case and move it through a role-checked prosecution workflow.
The public sees published applications, and only the redacted copies.
Admins monitor the database, buckets and Lambda, and every sensitive action is audited.
OpenFiling: secure intake, transparent status, safer public records.
