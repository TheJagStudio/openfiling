# OpenFiling local dev runner.
#   .\dev.ps1 up        start Postgres + S3 emulator + redaction Lambda runtime + filing-api + Angular (each in its own window)
#   .\dev.ps1 down      stop everything started by `up` (only those PIDs) and Postgres
#   .\dev.ps1 build     build + test every component and the web app
#   .\dev.ps1 status    show what is listening
#   .\dev.ps1 samples   regenerate the demo PDFs in .\samples
#
# Optional: put ANTHROPIC_API_KEY=... (and CLAUDE_MODEL=..., CLAUDE_EFFORT=...) in a git-ignored .env file next to
# this script to enable Claude-based PII/CUI detection. Without a key the redaction Lambda runs rules-only.
param([Parameter(Position=0)][ValidateSet('up','down','build','status','samples')][string]$Cmd = 'status')
$ErrorActionPreference = 'Stop'
$root  = $PSScriptRoot
$tools = 'D:\JavaSpring\tools'
. "$tools\env.ps1" | Out-Null
$data = Join-Path $root 'data'
$pidFile = Join-Path $data 'dev-pids.txt'
New-Item -ItemType Directory -Force $data | Out-Null

# .env → process environment (KEY=VALUE lines, # comments). Child windows inherit it.
$envFile = Join-Path $root '.env'
if (Test-Path $envFile) {
  foreach ($line in Get-Content $envFile) {
    if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)\s*$' -and -not $line.TrimStart().StartsWith('#')) {
      [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim('"', "'"), 'Process')
    }
  }
}

# Order matters: storage and the Lambda runtime first (the API health-checks them), web last.
$components = @(
  @{ name='s3-emulator';      port=9000; cmd=".\gradlew.bat bootRun" },
  @{ name='redaction-lambda'; port=9001; cmd=".\gradlew.bat run" },
  @{ name='filing-api';       port=8080; cmd=".\gradlew.bat bootRun --args='--spring.profiles.active=dev'" }
)

function Wait-Port($port, $seconds = 180) {
  $sw = [Diagnostics.Stopwatch]::StartNew()
  while ($sw.Elapsed.TotalSeconds -lt $seconds) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { return $true }
    Start-Sleep -Milliseconds 700
  }
  return $false
}

switch ($Cmd) {
  'up' {
    & "$tools\pg.ps1" start
    & "$tools\pg.ps1" createdb openfiling openfiling openfiling
    $pids = @()
    foreach ($c in $components) {
      $dir = Join-Path $root $c.name
      $cmdline = ". '$tools\env.ps1'; `$host.UI.RawUI.WindowTitle='$($c.name) :$($c.port)'; Set-Location '$dir'; $($c.cmd)"
      $p = Start-Process powershell -ArgumentList '-NoExit','-Command',$cmdline -PassThru
      $pids += $p.Id
      Write-Host "starting $($c.name) on :$($c.port) (pid $($p.Id))"
      if (-not (Wait-Port $c.port)) { Write-Warning "$($c.name) did not open :$($c.port) in time — check its window" }
    }
    $web = Join-Path $root 'web'
    if (-not (Test-Path (Join-Path $web 'node_modules'))) { Push-Location $web; npm install; Pop-Location }
    $p = Start-Process powershell -ArgumentList '-NoExit','-Command',". '$tools\env.ps1'; `$host.UI.RawUI.WindowTitle='web :4200'; Set-Location '$web'; npm start" -PassThru
    $pids += $p.Id
    $pids | Set-Content $pidFile
    Wait-Port 4200 | Out-Null
    $ai = if ($env:ANTHROPIC_API_KEY) { "Claude ($(if ($env:CLAUDE_MODEL) { $env:CLAUDE_MODEL } else { 'claude-opus-5-5' })) + rules" } else { 'rules only (set ANTHROPIC_API_KEY in .env for Claude)' }
    Write-Host ""
    Write-Host "Web app        http://localhost:4200"
    Write-Host "API docs       http://localhost:8080/swagger-ui.html"
    Write-Host "S3 emulator    http://localhost:9000/_emulator/buckets"
    Write-Host "Redaction      http://localhost:9001/health   ($ai)"
    Write-Host "Dev logins     inventor@openfiling.local / Inventor#12345"
    Write-Host "               examiner@openfiling.local / Examiner#12345"
    Write-Host "               admin@openfiling.local    / Admin#12345"
  }
  'down' {
    if (Test-Path $pidFile) {
      foreach ($id in Get-Content $pidFile) {
        # kill the window's process tree (gradle forks a java child)
        taskkill.exe /PID $id /T /F 2>$null | Out-Null
      }
      Remove-Item $pidFile
    }
    & "$tools\pg.ps1" stop
  }
  'build' {
    foreach ($c in $components) {
      Write-Host "== $($c.name)"; Push-Location (Join-Path $root $c.name)
      .\gradlew.bat build; if ($LASTEXITCODE) { Pop-Location; throw "$($c.name) build failed" }
      Pop-Location
    }
    Write-Host "== web"; Push-Location (Join-Path $root 'web')
    npm ci; if ($LASTEXITCODE) { Pop-Location; throw 'npm ci failed' }
    npm run build; if ($LASTEXITCODE) { Pop-Location; throw 'web build failed' }
    npm test -- --watch=false; if ($LASTEXITCODE) { Pop-Location; throw 'web tests failed' }
    Pop-Location
  }
  'samples' {
    Push-Location (Join-Path $root 'redaction-lambda'); .\gradlew.bat generateSamples; Pop-Location
  }
  'status' {
    foreach ($s in @(@{p=5433;n='postgres'}, @{p=9000;n='s3-emulator'}, @{p=9001;n='redaction-lambda'}, @{p=8080;n='filing-api'}, @{p=4200;n='web'})) {
      $up = [bool](Get-NetTCPConnection -State Listen -LocalPort $s.p -ErrorAction SilentlyContinue)
      "{0,-6} {1,-17} {2}" -f $s.p, $s.n, ($(if ($up) { 'UP' } else { '-' }))
    }
  }
}
