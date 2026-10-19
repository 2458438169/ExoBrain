<#
Unified check entry point for Windows (task 08). This file runs the same steps with the
same switches as scripts/check.sh, which GitHub Actions calls on Linux.

Usage: powershell -ExecutionPolicy Bypass -File scripts\check.ps1 [-Unit] [-PostgresIt]
                     [-Frontend] [-Build] [-All] [-RequireDatabase]

  -Unit            default deterministic backend unit profile:  jchatmind\mvnw.cmd -Punit test
  -PostgresIt      real PostgreSQL 17 + pgvector integration:   jchatmind\mvnw.cmd -Ppostgres-it test
                   (reads EXOBRAIN_IT_JDBC_URL / EXOBRAIN_IT_USERNAME / EXOBRAIN_IT_PASSWORD;
                   without them the integration checks report as skipped, never as passed)
  -Frontend        front-end lint:                              ui: npm run lint
  -Build           both front-end builds (lint runs only with -Frontend):
                     guest   VITE_PUBLIC_DEMO=true  /api/demo  /sse/demo  --base=/       --outDir=dist-public
                     account VITE_PUBLIC_DEMO=false /admin/api /admin/sse --base=/admin/ --outDir=dist-admin
  -All             -Unit -PostgresIt -Frontend -Build
  -RequireDatabase turn a missing or unreachable test database into a failure (CI uses this)

No switch means -Unit. Every switch maps to a command that really runs: no -DskipTests, no
ignored failures and no dynamic exclusions. A missing optional environment (no test database)
is reported as skipped with its reason. No credential value is ever printed.

Exit code 0 only when every selected step passed.
#>
[CmdletBinding()]
param(
    [switch]$Unit,
    [switch]$PostgresIt,
    [switch]$Frontend,
    [switch]$Build,
    [switch]$All,
    [switch]$RequireDatabase
)

$ErrorActionPreference = 'Stop'
$RootDir = Split-Path -Parent $PSScriptRoot
$BackendDir = Join-Path $RootDir 'jchatmind'
$UiDir = Join-Path $RootDir 'ui'

function Write-Info([string]$Message) { Write-Host $Message }

function Fail-Hard([string]$Message) {
    Write-Host "error: $Message" -ForegroundColor Red
    exit 1
}

# ---------------------------------------------------------------- switches

if ($All) { $Unit = $true; $PostgresIt = $true; $Frontend = $true; $Build = $true }

if (-not ($Unit -or $PostgresIt -or $Frontend -or $Build)) {
    $Unit = $true
    Write-Info 'info: no step selected, running the default unit profile (-Unit); use -All for every step'
}

# ---------------------------------------------------------------- environment

if ($env:JAVA_HOME) { $env:PATH = "$env:JAVA_HOME\bin;$env:PATH" }

function Assert-Java21 {
    $java = Get-Command java -ErrorAction SilentlyContinue
    if (-not $java) { Fail-Hard 'java is not on PATH; install JDK 21 and set JAVA_HOME' }
    $version = (cmd /c 'java -version 2>&1' | Select-Object -First 1)
    if ($version -notmatch '"21\.' -and $version -notmatch 'version 21') {
        Fail-Hard "JDK 21 is required (pom java.version=21) but java reports: $version"
    }
    Write-Info "java: $version"
}

function Assert-Node {
    if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
        Fail-Hard 'npm is not on PATH; install Node.js 22 or newer'
    }
    Write-Info "node: $(& node --version) / npm: $(& npm --version)"
}

function Invoke-Maven {
    param([string[]]$Arguments)
    Push-Location $BackendDir
    try { & .\mvnw.cmd @Arguments } finally { Pop-Location }
}

function Invoke-Npm {
    param([string[]]$Arguments)
    Push-Location $UiDir
    try { & npm @Arguments } finally { Pop-Location }
}

function Assert-UiDependencies {
    $modules = Join-Path $UiDir 'node_modules'
    if (Test-Path $modules) {
        Write-Info 'ui/node_modules exists; not running npm ci (CI installs it explicitly)'
    } else {
        Write-Info 'ui/node_modules is missing; running npm ci'
        Invoke-Npm @('ci')
        if ($LASTEXITCODE -ne 0) { throw "npm ci failed with exit code $LASTEXITCODE" }
    }
}

if ($Unit -or $PostgresIt) { Assert-Java21 }
if ($Frontend -or $Build) { Assert-Node }
if ($RequireDatabase -and $PostgresIt -and -not $env:EXOBRAIN_IT_JDBC_URL) {
    Fail-Hard '-RequireDatabase was given but EXOBRAIN_IT_JDBC_URL is not set'
}

# ---------------------------------------------------------------- steps

function Step-Unit {
    Invoke-Maven @('-Punit', 'test')
}

function Step-PostgresIt {
    if ($env:EXOBRAIN_IT_JDBC_URL) {
        Write-Info "EXOBRAIN_IT_JDBC_URL=$env:EXOBRAIN_IT_JDBC_URL"
        if ($env:EXOBRAIN_IT_USERNAME) { Write-Info "EXOBRAIN_IT_USERNAME=$env:EXOBRAIN_IT_USERNAME" }
        else { Write-Info 'EXOBRAIN_IT_USERNAME: unset' }
        if ($env:EXOBRAIN_IT_PASSWORD) { Write-Info 'EXOBRAIN_IT_PASSWORD: set (value not printed)' }
        else { Write-Info 'EXOBRAIN_IT_PASSWORD: unset' }
    } else {
        Write-Info 'EXOBRAIN_IT_JDBC_URL is not set: the integration class reports every check as skipped'
    }
    if ($RequireDatabase) {
        $env:EXOBRAIN_IT_REQUIRE_DATABASE = 'true'
        Write-Info 'EXOBRAIN_IT_REQUIRE_DATABASE=true (a missing database fails this step)'
    }
    Invoke-Maven @('-Ppostgres-it', 'test')
}

function Step-FrontendLint {
    Assert-UiDependencies
    Invoke-Npm @('run', 'lint')
    if ($LASTEXITCODE -ne 0) { throw "frontend lint failed with exit code $LASTEXITCODE" }
    Invoke-Npm @('run', 'test:state')
}

function Step-FrontendBuild {
    Assert-UiDependencies

    Write-Info 'guest build: VITE_PUBLIC_DEMO=true VITE_API_BASE_URL=/api/demo VITE_SSE_BASE_URL=/sse/demo --base=/ --outDir=dist-public'
    $env:VITE_PUBLIC_DEMO = 'true'
    $env:VITE_API_BASE_URL = '/api/demo'
    $env:VITE_SSE_BASE_URL = '/sse/demo'
    Invoke-Npm @('run', 'build', '--', '--base=/', '--outDir=dist-public')
    if ($LASTEXITCODE -ne 0) { throw "guest build failed with exit code $LASTEXITCODE" }
    if (-not (Test-Path (Join-Path $UiDir 'dist-public\index.html'))) {
        throw 'guest build did not produce ui/dist-public/index.html'
    }

    Write-Info 'account build: VITE_PUBLIC_DEMO=false VITE_API_BASE_URL=/admin/api VITE_SSE_BASE_URL=/admin/sse --base=/admin/ --outDir=dist-admin'
    $env:VITE_PUBLIC_DEMO = 'false'
    $env:VITE_API_BASE_URL = '/admin/api'
    $env:VITE_SSE_BASE_URL = '/admin/sse'
    Invoke-Npm @('run', 'build', '--', '--base=/admin/', '--outDir=dist-admin')
    if ($LASTEXITCODE -ne 0) { throw "account build failed with exit code $LASTEXITCODE" }
    if (-not (Test-Path (Join-Path $UiDir 'dist-admin\index.html'))) {
        throw 'account build did not produce ui/dist-admin/index.html'
    }

    Write-Info 'build outputs:'
    foreach ($name in 'dist-public', 'dist-admin') {
        $dir = Join-Path $UiDir $name
        $files = Get-ChildItem -Recurse -File $dir
        $size = ($files | Measure-Object -Property Length -Sum).Sum
        Write-Info ("  {0}: {1} files, {2:N1} KiB" -f $name, $files.Count, ($size / 1KB))
    }
}

$Summary = @()
$Failures = 0

function Invoke-Step([string]$Name, [scriptblock]$Action) {
    Write-Info ''
    Write-Info "==> $Name"
    $global:LASTEXITCODE = 0
    $ok = $true
    try {
        & $Action
        if ($LASTEXITCODE -ne 0) { throw "step failed with exit code $LASTEXITCODE" }
    } catch {
        $ok = $false
        Write-Host "error: $($_.Exception.Message)" -ForegroundColor Red
    }
    if ($ok) { $script:Summary += "PASS  $Name" }
    else { $script:Summary += "FAIL  $Name"; $script:Failures++ }
}

if ($Unit) { Invoke-Step 'backend unit profile (-Punit)' { Step-Unit } }
if ($PostgresIt) { Invoke-Step 'real PostgreSQL integration (-Ppostgres-it)' { Step-PostgresIt } }
if ($Frontend) { Invoke-Step 'frontend lint (npm run lint)' { Step-FrontendLint } }
if ($Build) { Invoke-Step 'frontend guest + account builds' { Step-FrontendBuild } }

Write-Info ''
Write-Info '==================== check summary ===================='
foreach ($line in $Summary) { Write-Info $line }
Write-Info "exit: $Failures failing step(s) of $($Summary.Count)"
if ($Failures -gt 0) { exit 1 }
exit 0
