[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$ssaRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$ssaGradle = Join-Path $ssaRoot 'gradlew.bat'
$ssaEvidenceDirectory = Join-Path $ssaRoot 'docs\spikes\S5'
$ssaFixturesDirectory = Join-Path $ssaEvidenceDirectory 'fixtures'
$ssaRuntime = [System.IO.Path]::GetFullPath((Join-Path $ssaRoot 'run\s5-restart'))
$ssaAllowedRuntimeRoot = [System.IO.Path]::GetFullPath((Join-Path $ssaRoot 'run')) +
    [System.IO.Path]::DirectorySeparatorChar
$ssaJdkHome = $env:JAVA_HOME

if (-not $ssaRuntime.StartsWith($ssaAllowedRuntimeRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Unsafe S5 runtime path: $ssaRuntime"
}
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

if (Test-Path -LiteralPath $ssaRuntime) {
    Remove-Item -LiteralPath $ssaRuntime -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $ssaRuntime | Out-Null
New-Item -ItemType Directory -Force -Path $ssaFixturesDirectory | Out-Null

$ssaProcessLog = New-Object System.Collections.Generic.List[string]
$ssaProcessLogPath = Join-Path $ssaEvidenceDirectory 'process.log'

function Save-SsaProcessLog {
    [System.IO.File]::WriteAllLines(
        $ssaProcessLogPath,
        [string[]]$ssaProcessLog,
        (New-Object System.Text.UTF8Encoding($false)))
}

function Invoke-SsaBuild {
    $ssaStartedAt = Get-Date
    $ssaStartInfo = New-Object System.Diagnostics.ProcessStartInfo
    $ssaStartInfo.FileName = $ssaGradle
    $ssaStartInfo.Arguments = 'clean test build --no-daemon'
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
        throw 'Failed to start the S5 clean build.'
    }
    $ssaStdout = $ssaProcess.StandardOutput.ReadToEndAsync()
    $ssaStderr = $ssaProcess.StandardError.ReadToEndAsync()
    $ssaProcess.WaitForExit()
    $ssaOutput = $ssaStdout.GetAwaiter().GetResult() + [Environment]::NewLine +
        $ssaStderr.GetAwaiter().GetResult()
    $ssaExitCode = $ssaProcess.ExitCode
    $ssaProcess.Dispose()

    $ssaBuildLog = @(
        'COMMAND=clean test build --no-daemon',
        "STARTED=$($ssaStartedAt.ToString('o'))",
        "FINISHED=$((Get-Date).ToString('o'))",
        "EXIT_CODE=$ssaExitCode",
        "RESULT=$(if ($ssaExitCode -eq 0) { 'PASS' } else { 'FAIL' })",
        ''
    ) + ($ssaOutput.TrimEnd() -split '\r?\n')
    [System.IO.File]::WriteAllLines(
        (Join-Path $ssaEvidenceDirectory 'build.log'),
        [string[]]$ssaBuildLog,
        (New-Object System.Text.UTF8Encoding($false)))
    if ($ssaExitCode -ne 0 -or $ssaOutput -notmatch 'BUILD SUCCESSFUL') {
        throw 'S5 clean test build failed.'
    }
}

function Invoke-SsaServer {
    param(
        [string]$Mode,
        [string]$FixtureId,
        [string]$Boundary
    )

    $ssaStartedAt = Get-Date
    $ssaStartInfo = New-Object System.Diagnostics.ProcessStartInfo
    $ssaStartInfo.FileName = $ssaGradle
    $ssaStartInfo.Arguments = ":platform-fabric:runServer --args=`"--nogui --port 0 --universe $ssaRuntime --world world`" --no-daemon"
    $ssaStartInfo.WorkingDirectory = $ssaRoot
    $ssaStartInfo.UseShellExecute = $false
    $ssaStartInfo.CreateNoWindow = $true
    $ssaStartInfo.RedirectStandardOutput = $true
    $ssaStartInfo.RedirectStandardError = $true
    $ssaStartInfo.EnvironmentVariables['JAVA_HOME'] = $ssaJdkHome
    $ssaStartInfo.EnvironmentVariables['PATH'] = (Join-Path $ssaJdkHome 'bin') + ';' + $env:Path
    $ssaStartInfo.EnvironmentVariables['SSA_S5_MODE'] = $Mode
    $ssaStartInfo.EnvironmentVariables['SSA_S5_FIXTURE_ID'] = $FixtureId
    $ssaStartInfo.EnvironmentVariables['SSA_S5_BOUNDARY'] = $Boundary

    $ssaProcess = New-Object System.Diagnostics.Process
    $ssaProcess.StartInfo = $ssaStartInfo
    if (-not $ssaProcess.Start()) {
        throw "Failed to start S5 server process for $FixtureId/$Mode."
    }
    $ssaStdout = $ssaProcess.StandardOutput.ReadToEndAsync()
    $ssaStderr = $ssaProcess.StandardError.ReadToEndAsync()
    $ssaTimedOut = -not $ssaProcess.WaitForExit(90000)
    if ($ssaTimedOut) {
        & taskkill.exe /PID $ssaProcess.Id /T /F | Out-Null
        $ssaProcess.WaitForExit()
    }
    $ssaOutput = $ssaStdout.GetAwaiter().GetResult() + [Environment]::NewLine +
        $ssaStderr.GetAwaiter().GetResult()
    $ssaExitCode = $ssaProcess.ExitCode
    $ssaProcess.Dispose()

    $ssaProcessLog.Add("CASE=$FixtureId MODE=$Mode BOUNDARY=$Boundary")
    $ssaProcessLog.Add("STARTED=$($ssaStartedAt.ToString('o'))")
    $ssaProcessLog.Add("FINISHED=$((Get-Date).ToString('o'))")
    $ssaProcessLog.Add("GRADLE_EXIT_CODE=$ssaExitCode")
    $ssaProcessLog.Add("TIMED_OUT=$ssaTimedOut")
    foreach ($ssaLine in ($ssaOutput.TrimEnd() -split '\r?\n')) {
        $ssaProcessLog.Add($ssaLine.TrimEnd())
    }
    $ssaProcessLog.Add('')
    Save-SsaProcessLog

    if ($ssaTimedOut) {
        throw "S5 server timed out for $FixtureId/$Mode. Evidence: $ssaProcessLogPath"
    }

    return @{
        ExitCode = $ssaExitCode
        Output = $ssaOutput
    }
}

function Get-SsaProperty {
    param(
        [string]$Path,
        [string]$Name
    )

    $ssaPrefix = "$Name="
    $ssaLine = Get-Content -LiteralPath $Path |
        Where-Object { $_.StartsWith($ssaPrefix, [System.StringComparison]::Ordinal) } |
        Select-Object -First 1
    if ($null -eq $ssaLine) {
        throw "Missing property $Name in $Path"
    }
    return $ssaLine.Substring($ssaPrefix.Length)
}

Invoke-SsaBuild

$ssaCases = @(
    @{ Boundary = 'before_prepared_append'; First = @('NO_ACTIVE_INTENT'); Commit = 0; Schedule = 1 },
    @{ Boundary = 'after_append_before_fsync_ack'; First = @('NO_ACTIVE_INTENT', 'ABORTED'); Commit = 0; Schedule = 1 },
    @{ Boundary = 'after_durable_prepared'; First = @('ABORTED'); Commit = 0; Schedule = 1 },
    @{ Boundary = 'after_delta_1'; First = @('COMMITTED'); Commit = 1; Schedule = 1 },
    @{ Boundary = 'after_delta_2'; First = @('COMMITTED'); Commit = 1; Schedule = 1 },
    @{ Boundary = 'after_delta_3'; First = @('COMMITTED'); Commit = 1; Schedule = 1 },
    @{ Boundary = 'after_all_deltas_before_commit'; First = @('COMMITTED'); Commit = 1; Schedule = 1 },
    @{ Boundary = 'after_journal_commit_before_wal_commit'; First = @('COMMITTED'); Commit = 1; Schedule = 1 },
    @{ Boundary = 'after_wal_commit_before_clear'; First = @('COMMITTED'); Commit = 1; Schedule = 1 },
    @{ Boundary = 'after_clear_checkpoint'; First = @('NO_ACTIVE_INTENT'); Commit = 1; Schedule = 1 },
    @{ Boundary = 'foreign_evidence'; First = @('QUARANTINED'); Commit = 0; Schedule = 0 }
)

$ssaWorldIdentity = $null
foreach ($ssaCase in $ssaCases) {
    $ssaBoundary = $ssaCase.Boundary
    $ssaFixtureId = "s5-$($ssaBoundary.Replace('_', '-'))"
    Write-Output "S5 crash/restart: $ssaBoundary"

    $ssaCrash = Invoke-SsaServer -Mode 'crash' -FixtureId $ssaFixtureId -Boundary $ssaBoundary
    $ssaCrashMarker = "SSA_S5_CRASH fixture=$ssaFixtureId boundary=$ssaBoundary exit=70"
    if ($ssaCrash.ExitCode -eq 0 -or
            $ssaCrash.Output -notmatch [regex]::Escape($ssaCrashMarker) -or
            $ssaCrash.Output -notmatch 'finished with non-zero exit value 70') {
        throw "S5 crash process did not halt at $ssaBoundary with game exit 70."
    }

    $ssaRecovery = Invoke-SsaServer -Mode 'recover' -FixtureId $ssaFixtureId -Boundary $ssaBoundary
    $ssaRecoveryMarker = "SSA_S5_RECOVERY fixture=$ssaFixtureId boundary=$ssaBoundary"
    if ($ssaRecovery.ExitCode -ne 0 -or
            $ssaRecovery.Output -notmatch [regex]::Escape($ssaRecoveryMarker) -or
            $ssaRecovery.Output -notmatch 'BUILD SUCCESSFUL') {
        throw "S5 recovery process failed for $ssaBoundary."
    }

    $ssaCaseDirectory = Join-Path $ssaRuntime "world\data\smart_survival_architect\s5\$ssaFixtureId"
    $ssaResultPath = Join-Path $ssaCaseDirectory 'result.properties'
    $ssaFixturePath = Join-Path $ssaCaseDirectory 'fixture.properties'
    if (-not (Test-Path -LiteralPath $ssaResultPath) -or -not (Test-Path -LiteralPath $ssaFixturePath)) {
        throw "S5 persisted fixture/result is missing for $ssaBoundary."
    }

    $ssaFirst = Get-SsaProperty -Path $ssaResultPath -Name 'first_outcome'
    $ssaSecond = Get-SsaProperty -Path $ssaResultPath -Name 'second_outcome'
    $ssaCommit = [int](Get-SsaProperty -Path $ssaResultPath -Name 'commit_count')
    $ssaSchedule = [int](Get-SsaProperty -Path $ssaResultPath -Name 'schedule_count')
    $ssaBlockedTicks = [int](Get-SsaProperty -Path $ssaResultPath -Name 'blocked_scheduling_ticks')
    $ssaIdentity = Get-SsaProperty -Path $ssaResultPath -Name 'world_identity'
    if ($ssaCase.First -notcontains $ssaFirst -or
            $ssaSecond -ne $(if ($ssaBoundary -eq 'foreign_evidence') { 'QUARANTINED' } else { 'NO_ACTIVE_INTENT' }) -or
            $ssaCommit -ne $ssaCase.Commit -or
            $ssaSchedule -ne $ssaCase.Schedule -or
            $ssaBlockedTicks -lt 1 -or
            (Get-SsaProperty -Path $ssaResultPath -Name 'metadata_verified') -ne 'true' -or
            (Get-SsaProperty -Path $ssaResultPath -Name 'recovery_before_scheduling') -ne 'true') {
        throw "S5 persisted assertions failed for $ssaBoundary."
    }
    if ($null -eq $ssaWorldIdentity) {
        $ssaWorldIdentity = $ssaIdentity
    } elseif ($ssaWorldIdentity -ne $ssaIdentity) {
        throw "S5 process pair changed world identity at $ssaBoundary."
    }
    if ($ssaBoundary -eq 'foreign_evidence') {
        $ssaBeforeHash = Get-SsaProperty -Path $ssaResultPath -Name 'evidence_sha256_before'
        $ssaAfterHash = Get-SsaProperty -Path $ssaResultPath -Name 'evidence_sha256_after'
        if ($ssaBeforeHash -ne $ssaAfterHash -or
                (Get-SsaProperty -Path $ssaResultPath -Name 'active_status') -ne 'QUARANTINED') {
            throw 'S5 foreign evidence changed or did not remain durably quarantined.'
        }
    }

    Copy-Item -LiteralPath $ssaFixturePath -Destination (Join-Path $ssaFixturesDirectory "$ssaBoundary-fixture.properties") -Force
    Copy-Item -LiteralPath $ssaResultPath -Destination (Join-Path $ssaFixturesDirectory "$ssaBoundary-result.properties") -Force
}

$ssaLayoutOutput = & (Join-Path $ssaRoot 'tools\verify-s1-layout.ps1') 2>&1 | Out-String
if ($ssaLayoutOutput -notmatch 'PASS S1 module layout') {
    throw 'S5 layout verification failed.'
}
$ssaPreviousErrorAction = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$ssaDiffOutput = & git -c core.safecrlf=false -c core.autocrlf=false -C $ssaRoot diff --check 2>&1 | Out-String
$ssaDiffExitCode = $LASTEXITCODE
$ErrorActionPreference = $ssaPreviousErrorAction
if ($ssaDiffExitCode -ne 0) {
    throw 'S5 git diff --check failed.'
}

Write-Output "PASS S5 real-process restart matrix: cases=$($ssaCases.Count) world=$ssaWorldIdentity"
Write-Output 'PASS S5 recovery gate: reconciliation precedes scheduling; foreign evidence stays quarantined'
