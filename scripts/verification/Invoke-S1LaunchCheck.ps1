[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('Client', 'Server')]
    [string]$Mode,

    [ValidateRange(1, 600)]
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = 'Stop'

function Stop-SsaProcessTree {
    param([int]$ProcessId)

    $children = Get-CimInstance Win32_Process -Filter "ParentProcessId = $ProcessId" -ErrorAction SilentlyContinue
    foreach ($child in $children) {
        Stop-SsaProcessTree -ProcessId $child.ProcessId
    }

    Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
}

function Receive-SsaOutput {
    param(
        [object[]]$Streams,
        [System.Collections.Generic.List[string]]$Lines
    )

    foreach ($stream in $Streams) {
        while (-not $stream.Closed -and $stream.Pending.IsCompleted) {
            $line = $stream.Pending.GetAwaiter().GetResult()
            if ($null -eq $line) {
                $stream.Closed = $true
            } else {
                $line = $line.TrimEnd()
                if ($line) {
                    $Lines.Add("$($stream.Prefix) $line")
                } else {
                    $Lines.Add($stream.Prefix)
                }
                $stream.Pending = $stream.Reader.ReadLineAsync()
            }
        }
    }
}

$ssaRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$ssaEvidenceDirectory = Join-Path $ssaRoot 'docs\spikes\S1'
$ssaLogPath = Join-Path $ssaEvidenceDirectory ($Mode.ToLowerInvariant() + '-launch.log')
$ssaTask = if ($Mode -eq 'Server') { 'runServer' } else { 'runClient' }
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

$ssaStartInfo = New-Object System.Diagnostics.ProcessStartInfo
$ssaStartInfo.FileName = $ssaGradle
$ssaStartInfo.Arguments = ":platform-fabric:$ssaTask --no-daemon"
$ssaStartInfo.WorkingDirectory = $ssaRoot
$ssaStartInfo.UseShellExecute = $false
$ssaStartInfo.CreateNoWindow = $true
$ssaStartInfo.RedirectStandardInput = $true
$ssaStartInfo.RedirectStandardOutput = $true
$ssaStartInfo.RedirectStandardError = $true
$ssaStartInfo.EnvironmentVariables['JAVA_HOME'] = $ssaJdkHome
$ssaStartInfo.EnvironmentVariables['PATH'] = (Join-Path $ssaJdkHome 'bin') + ';' + $env:Path

$ssaProcess = New-Object System.Diagnostics.Process
$ssaProcess.StartInfo = $ssaStartInfo
$ssaLines = New-Object 'System.Collections.Generic.List[string]'
$ssaStartedAt = Get-Date
$ssaSucceeded = $false
$ssaFailure = $null

try {
    if (-not $ssaProcess.Start()) {
        throw "Failed to start $ssaTask."
    }

    $ssaStreams = @(
        [pscustomobject]@{
            Prefix = 'OUT'
            Reader = $ssaProcess.StandardOutput
            Pending = $ssaProcess.StandardOutput.ReadLineAsync()
            Closed = $false
        },
        [pscustomobject]@{
            Prefix = 'ERR'
            Reader = $ssaProcess.StandardError
            Pending = $ssaProcess.StandardError.ReadLineAsync()
            Closed = $false
        }
    )

    $ssaDeadline = $ssaStartedAt.AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $ssaDeadline) {
        Receive-SsaOutput -Streams $ssaStreams -Lines $ssaLines
        $ssaOutput = $ssaLines -join [Environment]::NewLine

        $ssaCommonReady = $ssaOutput -match 'SSA_S1_COMMON_READY'
        if ($Mode -eq 'Server') {
            $ssaEnvironmentReady = $ssaOutput -match 'Done \([0-9.]+s\)!'
        } else {
            $ssaEnvironmentReady = $ssaOutput -match 'SSA_S1_CLIENT_READY' -and
                    $ssaOutput -match 'SSA_S1_CLIENT_STARTED'
        }

        if ($ssaCommonReady -and $ssaEnvironmentReady) {
            break
        }
        if ($ssaProcess.HasExited) {
            $ssaFailure = "$ssaTask exited before its readiness markers (exit $($ssaProcess.ExitCode))."
            break
        }

        Start-Sleep -Milliseconds 100
    }

    Receive-SsaOutput -Streams $ssaStreams -Lines $ssaLines
    $ssaOutput = $ssaLines -join [Environment]::NewLine
    $ssaCommonReady = $ssaOutput -match 'SSA_S1_COMMON_READY'
    if ($Mode -eq 'Server') {
        $ssaEnvironmentReady = $ssaOutput -match 'Done \([0-9.]+s\)!'
    } else {
        $ssaEnvironmentReady = $ssaOutput -match 'SSA_S1_CLIENT_READY' -and
                $ssaOutput -match 'SSA_S1_CLIENT_STARTED'
    }

    if (-not $ssaFailure -and -not ($ssaCommonReady -and $ssaEnvironmentReady)) {
        $ssaFailure = "$ssaTask timed out after $TimeoutSeconds seconds waiting for readiness markers."
    }

    if (-not $ssaFailure -and $Mode -eq 'Server') {
        $ssaProcess.StandardInput.WriteLine('stop')
        $ssaProcess.StandardInput.Flush()
        $ssaStopDeadline = (Get-Date).AddSeconds(30)
        while (-not $ssaProcess.HasExited -and (Get-Date) -lt $ssaStopDeadline) {
            Receive-SsaOutput -Streams $ssaStreams -Lines $ssaLines
            Start-Sleep -Milliseconds 100
        }
        if (-not $ssaProcess.HasExited) {
            $ssaFailure = 'runServer did not stop cleanly within 30 seconds.'
        } elseif ($ssaProcess.ExitCode -ne 0) {
            $ssaFailure = "runServer stopped with exit code $($ssaProcess.ExitCode)."
        }
    }

    if (-not $ssaFailure -and $Mode -eq 'Client') {
        Stop-SsaProcessTree -ProcessId $ssaProcess.Id
        [void]$ssaProcess.WaitForExit(10000)
    }

    $ssaSucceeded = -not $ssaFailure
} finally {
    if ($ssaProcess.Id -and -not $ssaProcess.HasExited) {
        Stop-SsaProcessTree -ProcessId $ssaProcess.Id
        [void]$ssaProcess.WaitForExit(10000)
    }

    if ($ssaStreams) {
        $ssaDrainDeadline = (Get-Date).AddSeconds(5)
        while (($ssaStreams | Where-Object { -not $_.Closed }) -and (Get-Date) -lt $ssaDrainDeadline) {
            Receive-SsaOutput -Streams $ssaStreams -Lines $ssaLines
            Start-Sleep -Milliseconds 50
        }
    }

    $ssaHeader = @(
        "MODE=$Mode",
        "COMMAND=.\\gradlew.bat :platform-fabric:$ssaTask --no-daemon",
        "STARTED=$($ssaStartedAt.ToString('o'))",
        "FINISHED=$((Get-Date).ToString('o'))",
        "RESULT=$(if ($ssaSucceeded) { 'PASS' } else { 'FAIL' })",
        "DETAIL=$(if ($ssaFailure) { $ssaFailure } else { 'Readiness markers observed.' })",
        ''
    )
    [System.IO.File]::WriteAllLines(
        $ssaLogPath,
        [string[]]($ssaHeader + $ssaLines),
        (New-Object System.Text.UTF8Encoding($false)))
    $ssaProcess.Dispose()
}

if (-not $ssaSucceeded) {
    throw "$ssaFailure Evidence: $ssaLogPath"
}

Write-Output "PASS S1 $Mode launch: $ssaLogPath"
