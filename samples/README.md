# OpenFiling demo documents

Generated PDFs for trying the upload → redaction → public-copy pipeline end to end.

> **All personal data in these files is fictitious.** The people, addresses, e-mail addresses (`example.com`),
> phone numbers (the reserved `555-01xx` range), the SSN `078-05-1120` (a well-known specimen number that was never
> validly issued to a person), the bank routing number `021000021` (a textbook example), account and passport
> numbers were invented for testing. The CUI / ITAR / EAR markings are illustrative only and do not mark real
> controlled information.

| File | Upload as | Pages | What it demonstrates |
|---|---|---|---|
| `specification-with-pii.pdf` | `SPEC`, **PUBLIC** | 4 | Title page with inventor names/residence (public record — must **not** be redacted), background citing prior art; page 2 carries a `CUI//SP-EXPT` banner (top and bottom of pages 2–3), an ITAR / ECCN 3A001 / EAR99 export notice with a DoD distribution statement, and a "personal correspondence" block: home street address, home and mobile phone, personal e-mail, date of birth, SSN, routing and checking-account number, an emergency contact and a medical note (the last two are only caught by Claude); the detailed description also contains a test-rig IPv4 address. |
| `claims.pdf` | `CLM`, PUBLIC | 2 | 20 numbered claims, no sensitive data — the public copy should report 0 redactions. |
| `abstract.pdf` | `ABST`, PUBLIC | 1 | Clean abstract (0 redactions). |
| `drawings.pdf` | `DRW`, PUBLIC | 3 | Vector figures (FIG. 1 pack layout, FIG. 2 curves, FIG. 3 flow chart) with reference numerals only. |
| `oath-declaration.pdf` | `OATH`, **RESTRICTED** | 1 | Inventor declaration with residence address, phone, e-mail, date of birth and a passport number. RESTRICTED documents are never redacted or published — use it to check that no public copy is produced. |

Expected result for `specification-with-pii.pdf` with rules only (no `ANTHROPIC_API_KEY`): a 4-page public copy where
the CUI banners, the export-control notice terms, the distribution statement, the street address, both phone
numbers, the e-mail, the date of birth, the SSN, the routing and account numbers and the IP address are covered by
black boxes. With Claude enabled, the emergency contact's name and the medical accommodation are redacted too, while
the inventor names on page 1 stay visible.

Regenerate (overwrites the files):

```powershell
. D:\JavaSpring\tools\env.ps1
cd D:\JavaSpring\projects\openfiling\redaction-lambda
.\gradlew.bat generateSamples
```
