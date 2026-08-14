# Verifies operation-intent persistence and crash recovery.
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$ssaRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$ssaEvidenceDirectory = Join-Path $ssaRoot 'docs\spikes\S4'
$ssaGradle = Join-Path $ssaRoot 'gradlew.bat'
$ssaJdkHome = $env:JAVA_HOME

if (-not $ssaJdkHome -or -not (Test-Path -LiteralPath (Join-Path $ssaJdkHome 'bin\java.exe'))) {
    $ssaJdk = Get-ChildItem -LiteralPath 'C:\Program Files\Eclipse Adoptium' -Directory -ErrorAction SilentlyContinue |
        Where-Object Name -Like 'jdk-25*' |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if (-not $ssaJdk) {
        throw 'Temurin JDK 25 was not found.'
    }
    $ssaJdkHome = $ssaJdk.FullName
}

New-Item -ItemType Directory -Force -Path $ssaEvidenceDirectory | Out-Null

function Write-SsaUtf8Lines {
    param(
        [string]$Path,
        [string[]]$Lines
    )

    $ssaText = [string]::Join("`n", $Lines) + "`n"
    [System.IO.File]::WriteAllText($Path, $ssaText, (New-Object System.Text.UTF8Encoding($false)))
}

function Invoke-SsaGradle {
    param(
        [string]$Name,
        [string]$Arguments
    )

    $ssaStartedAt = Get-Date
    $ssaStartInfo = New-Object System.Diagnostics.ProcessStartInfo
    $ssaStartInfo.FileName = $ssaGradle
    $ssaStartInfo.Arguments = $Arguments
    $ssaStartInfo.WorkingDirectory = $ssaRoot
    $ssaStartInfo.UseShellExecute = $false
    $ssaStartInfo.CreateNoWindow = $true
    $ssaStartInfo.RedirectStandardOutput = $true
    $ssaStartInfo.RedirectStandardError = $true
    $ssaStartInfo.EnvironmentVariables['JAVA_HOME'] = $ssaJdkHome
    $ssaStartInfo.EnvironmentVariables['PATH'] = (Join-Path $ssaJdkHome 'bin') + ';' + $env:Path

    $ssaProcess = New-Object System.Diagnostics.Process
    $ssaProcess.StartInfo = $ssaStartInfo
    if (-not $ssaProcess.Start()) {
        throw "Failed to start the S4 $Name process."
    }
    $ssaStdoutTask = $ssaProcess.StandardOutput.ReadToEndAsync()
    $ssaStderrTask = $ssaProcess.StandardError.ReadToEndAsync()
    $ssaProcess.WaitForExit()
    $ssaOutput = $ssaStdoutTask.GetAwaiter().GetResult() + [Environment]::NewLine +
        $ssaStderrTask.GetAwaiter().GetResult()
    $ssaExitCode = $ssaProcess.ExitCode
    $ssaProcess.Dispose()

    $ssaLogPath = Join-Path $ssaEvidenceDirectory "$Name.log"
    $ssaHeader = @(
        "COMMAND=$Arguments",
        "STARTED=$($ssaStartedAt.ToString('o'))",
        "FINISHED=$((Get-Date).ToString('o'))",
        "EXIT_CODE=$ssaExitCode",
        "RESULT=$(if ($ssaExitCode -eq 0) { 'PASS' } else { 'FAIL' })",
        ''
    )
    $ssaLines = $ssaOutput.TrimEnd() -split '\r?\n' | ForEach-Object { $_.TrimEnd() }
    Write-SsaUtf8Lines -Path $ssaLogPath -Lines ([string[]]($ssaHeader + $ssaLines))

    if ($ssaExitCode -ne 0 -or $ssaOutput -notmatch 'BUILD SUCCESSFUL') {
        throw "S4 $Name failed. Evidence: $ssaLogPath"
    }
    return $ssaOutput
}

$null = Invoke-SsaGradle `
    -Name 'build' `
    -Arguments 'clean test build --no-daemon'

$ssaTestOutput = Invoke-SsaGradle `
    -Name 'test' `
    -Arguments ':construction-core:test :platform-fabric:test --rerun-tasks --info --no-daemon'

$ssaCrashPoints = @(
    @{ Name = 'before_prepared_append'; Decision = 'NO_ACTIVE_INTENT' },
    @{ Name = 'after_append_before_fsync_ack'; Decision = '(?:ABORTED|NO_ACTIVE_INTENT)' },
    @{ Name = 'after_durable_prepared'; Decision = 'ABORTED' },
    @{ Name = 'after_all_deltas_before_commit'; Decision = 'COMMITTED' },
    @{ Name = 'after_journal_commit_before_wal_commit'; Decision = 'COMMITTED' },
    @{ Name = 'after_wal_commit_before_clear'; Decision = 'COMMITTED' },
    @{ Name = 'after_clear_checkpoint'; Decision = 'NO_ACTIVE_INTENT' }
)
$ssaOperations = @(
    @{ Name = 'material_transfer'; Deltas = 2 },
    @{ Name = 'placement_consumption'; Deltas = 2 },
    @{ Name = 'atomic_multi_block'; Deltas = 3 }
)
foreach ($ssaOperation in $ssaOperations) {
    foreach ($ssaPoint in $ssaCrashPoints) {
        $ssaPattern = "SSA_S4_CRASH operation=$($ssaOperation.Name) point=$($ssaPoint.Name) decision=$($ssaPoint.Decision) exact=true idempotent=true"
        if ($ssaTestOutput -notmatch $ssaPattern) {
            throw "Missing S4 crash marker: $($ssaOperation.Name)/$($ssaPoint.Name)"
        }
    }
    for ($ssaIndex = 1; $ssaIndex -le $ssaOperation.Deltas; $ssaIndex++) {
        $ssaPattern = "SSA_S4_CRASH operation=$($ssaOperation.Name) point=after_delta_$ssaIndex decision=COMMITTED exact=true idempotent=true"
        if ($ssaTestOutput -notmatch $ssaPattern) {
            throw "Missing S4 per-delta crash marker: $($ssaOperation.Name)/$ssaIndex"
        }
    }
}

if ($ssaTestOutput -notmatch 'SSA_S4_CRASH operation=material_transfer point=foreign_evidence decision=QUARANTINED exact=true idempotent=true') {
    throw 'Missing S4 foreign-evidence quarantine marker.'
}
if ($ssaTestOutput -notmatch 'SSA_S4_THREADS fsync=ssa-persistence-\d+ mutation=ssa-server-[^ ]+ server_heartbeat=responsive') {
    throw 'Missing S4 persistence/server thread-handoff marker.'
}
$ssaLatency = [regex]::Match($ssaTestOutput, 'SSA_S4_FSYNC samples=200 p50_us=(?<p50>\d+) p95_us=(?<p95>\d+) io_thread=ssa-persistence-')
if (-not $ssaLatency.Success) {
    throw 'Missing S4 durable acknowledgement latency marker.'
}

$ssaGameTestOutput = Invoke-SsaGradle `
    -Name 'gametest' `
    -Arguments ':platform-fabric:runGameTest --no-daemon'
if ($ssaGameTestOutput -notmatch 'SSA_S4_CODEC stack_item=minecraft:diamond_pickaxe count=1 components=2 foreign_component=detected empty=roundtrip') {
    throw 'Missing S4 exact item-component codec marker.'
}
if ($ssaGameTestOutput -notmatch 'SSA_S4_CODEC block=minecraft:oak_stairs facing=east half=top foreign_property=detected roundtrip=exact') {
    throw 'Missing S4 exact block-state codec marker.'
}
if ($ssaGameTestOutput -notmatch 'All \d+ required tests passed') {
    throw 'Server GameTests did not report all tests passed.'
}

$ssaVerificationLog = Join-Path $ssaEvidenceDirectory 'verification.log'
$ssaVerification = New-Object System.Collections.Generic.List[string]
$ssaVerification.Add("STARTED=$((Get-Date).ToString('o'))")

$ssaLayoutOutput = & (Join-Path $PSScriptRoot 'Test-ProjectLayout.ps1') 2>&1 | Out-String
if ($ssaLayoutOutput -notmatch 'PASS S1 module layout') {
    throw 'S1 layout verification failed.'
}
$ssaVerification.Add('LAYOUT=PASS')
$ssaVerification.Add($ssaLayoutOutput.Trim())

$ssaPreviousErrorAction = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$ssaDiffOutput = & git -c core.safecrlf=false -c core.autocrlf=false -C $ssaRoot diff --check 2>&1 | Out-String
$ssaDiffExitCode = $LASTEXITCODE
$ErrorActionPreference = $ssaPreviousErrorAction
if ($ssaDiffExitCode -ne 0) {
    throw 'git diff --check failed.'
}
$ssaVerification.Add('DIFF_CHECK=PASS')
$ssaVerification.Add($ssaDiffOutput.Trim())
$ssaVerification.Add("FINISHED=$((Get-Date).ToString('o'))")
Write-SsaUtf8Lines -Path $ssaVerificationLog -Lines ([string[]]$ssaVerification)

Write-Output 'PASS S4 clean test build, layout, and diff verification'
Write-Output "PASS S4 crash matrix: 3 operations, all required boundaries, exact/idempotent recovery"
Write-Output "PASS S4 durable acknowledgement: samples=200 p50=$($ssaLatency.Groups['p50'].Value) us p95=$($ssaLatency.Groups['p95'].Value) us"
Write-Output 'PASS S4 thread handoff and Minecraft component/block codecs'
