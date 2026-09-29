# End-to-end smoke test against a running stack (.\dev.ps1 up).
# Exercises all three objectives through the real HTTP surfaces:
#   1. pre-signed PUT straight to S3 (API never sees the bytes)
#   2. role-based tracking: inventor files, examiner works the queue, inventor cannot see others' data
#   3. public document -> S3 event -> redaction Lambda -> sanitized public copy downloadable anonymously
param(
  [string]$Api = 'http://localhost:8080',
  [int]$RedactionTimeoutSec = 180
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot
$samples = Join-Path $root 'samples'
$failures = 0

function Step($msg) { Write-Host "`n== $msg" -ForegroundColor Cyan }
function Ok($msg) { Write-Host "   ok  $msg" -ForegroundColor Green }
function Fail($msg) { Write-Host "   FAIL $msg" -ForegroundColor Red; $script:failures++ }

function Call($method, $path, $token = $null, $body = $null) {
  $headers = @{ 'X-Request-Id' = "smoke-$([guid]::NewGuid().ToString('N').Substring(0, 12))" }
  if ($token) { $headers['Authorization'] = "Bearer $token" }
  $args = @{ Method = $method; Uri = "$Api$path"; Headers = $headers; UseBasicParsing = $true }
  if ($null -ne $body) { $args['Body'] = ($body | ConvertTo-Json -Depth 10); $args['ContentType'] = 'application/json' }
  return Invoke-RestMethod @args
}

function Status-Of($method, $path, $token = $null) {
  try { Call $method $path $token | Out-Null; return 200 } catch { return [int]$_.Exception.Response.StatusCode }
}

function Login($email, $password) { (Call POST '/api/v1/auth/login' $null @{ email = $email; password = $password }).accessToken }

function Upload($token, $appId, $docCode, $file, $visibility) {
  $info = Get-Item $file
  $created = Call POST "/api/v1/applications/$appId/documents" $token @{
    docCode = $docCode; fileName = $info.Name; contentType = 'application/pdf'; sizeBytes = $info.Length; visibility = $visibility
  }
  # Direct-to-S3: no Authorization header, exactly the signed Content-Type.
  Invoke-WebRequest -Method Put -Uri $created.upload.url -InFile $file -ContentType 'application/pdf' -UseBasicParsing | Out-Null
  $done = Call POST "/api/v1/documents/$($created.document.id)/complete" $token
  if ($done.status -ne 'UPLOADED') { throw "document $($info.Name) is $($done.status)" }
  return $done
}

Step 'Health'
$health = Invoke-RestMethod "$Api/actuator/health"
if ($health.status -eq 'UP') { Ok 'filing-api UP' } else { Fail "filing-api $($health.status)" }
foreach ($u in 'http://localhost:9000/_emulator/health', 'http://localhost:9001/health') {
  try { Invoke-RestMethod $u | Out-Null; Ok $u } catch { Fail "$u unreachable" }
}

Step 'Inventor signs up and files an application'
$email = "smoke-$([guid]::NewGuid().ToString('N').Substring(0, 8))@example.test"
$reg = Call POST '/api/v1/auth/register' $null @{ email = $email; password = 'Smoke-test-2026'; displayName = 'Smoke Inventor' }
$inv = $reg.accessToken
Ok "registered $email as $($reg.user.roles -join ',')"
$app = Call POST '/api/v1/applications' $inv @{
  title = 'Smoke test: self-healing polymer coating'; applicationType = 'UTILITY'; entityStatus = 'MICRO'
  abstractText = 'A coating that repairs micro-cracks using encapsulated monomers.'; technologyField = 'Materials'
  attorneyDocketNumber = 'SMK-001'
  inventors = @(@{ givenName = 'Smoke'; familyName = 'Inventor'; city = 'Alexandria'; state = 'VA'; country = 'US'; citizenship = 'US' })
}
Ok "draft $($app.id)"

$spec = Upload $inv $app.id 'SPEC' (Join-Path $samples 'specification-with-pii.pdf') 'PUBLIC'
Upload $inv $app.id 'CLM' (Join-Path $samples 'claims.pdf') 'PUBLIC' | Out-Null
Upload $inv $app.id 'ABST' (Join-Path $samples 'abstract.pdf') 'PUBLIC' | Out-Null
Upload $inv $app.id 'OATH' (Join-Path $samples 'oath-declaration.pdf') 'RESTRICTED' | Out-Null
Ok '4 documents uploaded directly to S3 via pre-signed URLs'

$detail = Call GET "/api/v1/applications/$($app.id)" $inv
$filed = Call POST "/api/v1/applications/$($app.id)/submit" $inv @{ version = $detail.version; certify = $true; signature = '/Smoke Inventor/' }
if ($filed.status -eq 'SUBMITTED' -and $filed.applicationNumber) { Ok "filed as $($filed.applicationNumberDisplay)" } else { Fail "submit -> $($filed.status)" }

Step 'Redaction pipeline (S3 event -> Lambda -> public copy)'
$sw = [Diagnostics.Stopwatch]::StartNew(); $red = $null
while ($sw.Elapsed.TotalSeconds -lt $RedactionTimeoutSec) {
  $red = (Call GET "/api/v1/documents/$($spec.id)" $inv).redaction
  if ($red -and $red.status -in 'COMPLETED', 'FAILED') { break }
  Start-Sleep -Seconds 2
}
if ($red.status -eq 'COMPLETED' -and $red.redactionCount -gt 0) {
  Ok "public copy ready: $($red.redactionCount) redactions by $($red.engine) ($(($red.categories | ForEach-Object { "$($_.category)=$($_.count)" }) -join ', '))"
} else { Fail "redaction status $($red.status) $($red.error)" }

Step 'Role-based access'
$other = (Call POST '/api/v1/auth/register' $null @{ email = "smoke-other-$([guid]::NewGuid().ToString('N').Substring(0, 8))@example.test"; password = 'Smoke-test-2026'; displayName = 'Other Inventor' }).accessToken
$code = Status-Of GET "/api/v1/applications/$($app.id)" $other
if ($code -eq 404) { Ok "another inventor gets 404 for this application" } else { Fail "another inventor got $code" }
$code = Status-Of GET '/api/v1/examiner/queue' $other
if ($code -eq 403) { Ok 'inventor gets 403 on the examiner queue' } else { Fail "inventor queue -> $code" }
$code = Status-Of GET "/api/v1/documents/$($spec.id)/download?copy=original" $other
if ($code -eq 404) { Ok 'another inventor cannot download the original' } else { Fail "download by other -> $code" }

Step 'Examiner works the case'
$ex = Login 'examiner@openfiling.local' 'Examiner#12345'
$queue = Call GET "/api/v1/examiner/queue?q=$($filed.applicationNumber)&assigned=any" $ex
if ($queue.totalElements -ge 1) { Ok "found in the examiner queue" } else { Fail 'not in queue' }
$v = (Call GET "/api/v1/applications/$($app.id)" $ex).version
foreach ($a in @(@{ action = 'CLAIM' }, @{ action = 'START_PRE_EXAM' }, @{ action = 'DOCKET'; artUnit = '1700' }, @{ action = 'START_EXAMINATION' }, @{ action = 'PUBLISH' },
                 @{ action = 'ISSUE_NON_FINAL_REJECTION'; note = 'Claims 1-3 rejected under 35 U.S.C. 103 over Smith in view of Jones.' })) {
  $a['version'] = $v
  $r = Call POST "/api/v1/applications/$($app.id)/actions" $ex $a
  $v = $r.version
  Ok "$($a.action) -> $($r.status)"
}

Step 'Inventor responds'
$resp = Upload $inv $app.id 'RESP' (Join-Path $samples 'claims.pdf') 'PUBLIC'
$v = (Call GET "/api/v1/applications/$($app.id)" $inv).version
$r = Call POST "/api/v1/applications/$($app.id)/actions" $inv @{ action = 'FILE_RESPONSE'; note = 'Amended claim 1 to recite the ceramic interlayer.'; version = $v }
Ok "FILE_RESPONSE -> $($r.status)"
$unread = (Call GET '/api/v1/notifications/unread-count' $inv).count
if ($unread -ge 1) { Ok "inventor has $unread unread notification(s)" } else { Fail 'no notifications' }
$history = Call GET "/api/v1/applications/$($app.id)/history" $inv
Ok "prosecution history has $($history.Count) events"

Step 'Public (anonymous) view'
$pub = Invoke-RestMethod "$Api/api/v1/public/applications/$($filed.applicationNumber)"
Ok "public status: $($pub.statusLabel); public documents: $($pub.documents.Count)"
$pubDoc = $pub.documents | Where-Object { $_.docCode -eq 'SPEC' } | Select-Object -First 1
if ($pubDoc) {
  $dl = Invoke-RestMethod "$Api/api/v1/public/documents/$($pubDoc.id)/download"
  $out = Join-Path $env:TEMP "openfiling-public-copy.pdf"
  Invoke-WebRequest $dl.url -OutFile $out -UseBasicParsing
  Ok "downloaded public copy ($((Get-Item $out).Length) bytes) -> $out"
} else { Fail 'specification public copy not listed' }
if (-not ($pub.documents | Where-Object { $_.docCode -eq 'OATH' })) { Ok 'RESTRICTED oath/declaration is not public' } else { Fail 'restricted document leaked' }

Write-Host ""
if ($failures) { Write-Host "$failures check(s) failed" -ForegroundColor Red; exit 1 }
Write-Host 'All smoke checks passed' -ForegroundColor Green
