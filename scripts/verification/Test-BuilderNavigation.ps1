# Verifies bounded Builder navigation and placement behavior.
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$ssaRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$ssaEvidenceDirectory = Join-Path $ssaRoot 'docs\spikes\S2'
$ssaLogPath = Join-Path $ssaEvidenceDirectory 'gametest.log'
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
$ssaStartedAt = Get-Date
$ssaStartInfo = New-Object System.Diagnostics.ProcessStartInfo
$ssaStartInfo.FileName = $ssaGradle
$ssaStartInfo.Arguments = ':platform-fabric:runGameTest --no-daemon'
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
    throw 'Failed to start the S2 GameTest process.'
}

$ssaStdoutTask = $ssaProcess.StandardOutput.ReadToEndAsync()
$ssaStderrTask = $ssaProcess.StandardError.ReadToEndAsync()
$ssaProcess.WaitForExit()
$ssaStdout = $ssaStdoutTask.GetAwaiter().GetResult()
$ssaStderr = $ssaStderrTask.GetAwaiter().GetResult()
$ssaExitCode = $ssaProcess.ExitCode
$ssaProcess.Dispose()

$ssaOutput = $ssaStdout + [Environment]::NewLine + $ssaStderr
$ssaLines = $ssaOutput.TrimEnd() -split '\r?\n' | ForEach-Object { $_.TrimEnd() }
$ssaFailure = $null

if ($ssaExitCode -ne 0) {
    $ssaFailure = "GameTest Gradle process exited with code $ssaExitCode."
}

$ssaFixtures = @(
    'flat',
    'step',
    'doorway',
    'fence_obstruction',
    'upper_floor',
    'scaffold_required',
    'impossible_target',
    'chunk_unload',
    'stuck_timeout'
)
foreach ($ssaFixture in $ssaFixtures) {
    if ($ssaOutput -notmatch "SSA_S2_FIXTURE name=$([regex]::Escape($ssaFixture)) outcome=") {
        $ssaFailure = "Missing S2 fixture marker: $ssaFixture"
        break
    }
}

$ssaProfile = [regex]::Match(
    $ssaOutput,
    'SSA_S2_PROFILE count=100 p50_us=(?<p50>\d+) p95_us=(?<p95>\d+) max_us=(?<max>\d+)')
if (-not $ssaProfile.Success) {
    $ssaFailure = 'Missing S2 path profile marker.'
} else {
    $ssaP50 = [long]$ssaProfile.Groups['p50'].Value
    $ssaP95 = [long]$ssaProfile.Groups['p95'].Value
    $ssaMaximum = [long]$ssaProfile.Groups['max'].Value
    if ($ssaP95 -ge 10000) {
        $ssaFailure = "Path-attempt p95 exceeded 10,000 us: $ssaP95"
    } elseif ($ssaMaximum -ge 50000) {
        $ssaFailure = "Path-attempt max exceeded 50,000 us: $ssaMaximum"
    }
}

if ($ssaOutput -notmatch 'All \d+ required tests passed') {
    $ssaFailure = 'GameTest summary did not report all required tests passed.'
}

$ssaSpikeSource = Get-Content -Raw -LiteralPath (
    Join-Path $ssaRoot 'platform-fabric\src\main\java\dev\ssa\fabric\spike\navigation\SpikeBuilderEntity.java')
if ($ssaSpikeSource -match '\b(setPos|setPosRaw|teleportTo|teleportSetPosition)\s*\(') {
    $ssaFailure = 'A teleport/position-forcing API is present in the S2 navigation entity.'
}

$ssaSucceeded = -not $ssaFailure
$ssaHeader = @(
    'MODE=S2_NAVIGATION_GAMETEST',
    'COMMAND=.\\gradlew.bat :platform-fabric:runGameTest --no-daemon',
    "STARTED=$($ssaStartedAt.ToString('o'))",
    "FINISHED=$((Get-Date).ToString('o'))",
    "EXIT_CODE=$ssaExitCode",
    "RESULT=$(if ($ssaSucceeded) { 'PASS' } else { 'FAIL' })",
    "DETAIL=$(if ($ssaFailure) { $ssaFailure } else { 'All fixtures and profile thresholds passed.' })",
    ''
)
[System.IO.File]::WriteAllLines(
    $ssaLogPath,
    [string[]]($ssaHeader + $ssaLines),
    (New-Object System.Text.UTF8Encoding($false)))

if (-not $ssaSucceeded) {
    throw "$ssaFailure Evidence: $ssaLogPath"
}

Write-Output "PASS S2 navigation: p50=$ssaP50 us, p95=$ssaP95 us, max=$ssaMaximum us"
