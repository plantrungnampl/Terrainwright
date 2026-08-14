[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'

$ssaRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$ssaEvidenceDirectory = Join-Path $ssaRoot 'docs\spikes\S3'
$ssaScreenshotDirectory = Join-Path $ssaEvidenceDirectory 'screenshots'
$ssaRunScreenshotDirectory = Join-Path $ssaRoot 'platform-fabric\build\run\clientGameTest\screenshots'
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

New-Item -ItemType Directory -Force -Path $ssaEvidenceDirectory, $ssaScreenshotDirectory | Out-Null

$ssaRawApiMatches = Get-ChildItem -LiteralPath (Join-Path $ssaRoot 'platform-fabric\src\client') -Recurse -File -Filter '*.java' |
    Select-String -Pattern 'org\.lwjgl\.opengl|\bGL(?:11|12|13|14|15|20|21|30|31|32|33|40|41|42|43|44|45|46)\b|RenderSystem\.gl'
if ($ssaRawApiMatches) {
    throw 'Raw OpenGL API usage is present in client sources.'
}

function Write-SsaLog {
    param(
        [string]$Path,
        [string]$Mode,
        [string]$Command,
        [datetime]$StartedAt,
        [int]$ExitCode,
        [string]$Result,
        [string]$Detail,
        [string]$Output
    )

    $ssaHeader = @(
        "MODE=$Mode",
        "COMMAND=$Command",
        "STARTED=$($StartedAt.ToString('o'))",
        "FINISHED=$((Get-Date).ToString('o'))",
        "EXIT_CODE=$ExitCode",
        "RESULT=$Result",
        "DETAIL=$Detail",
        ''
    )
    $ssaLines = $Output.TrimEnd() -split '\r?\n' | ForEach-Object { $_.TrimEnd() }
    [System.IO.File]::WriteAllLines(
        $Path,
        [string[]]($ssaHeader + $ssaLines),
        (New-Object System.Text.UTF8Encoding($false)))
}

function Invoke-SsaBackend {
    param(
        [string]$Name,
        [string]$Arguments,
        [string]$ExpectedBackend,
        [switch]$AllowUnavailable
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
        throw "Failed to start the S3 $Name client GameTest process."
    }

    $ssaStdoutTask = $ssaProcess.StandardOutput.ReadToEndAsync()
    $ssaStderrTask = $ssaProcess.StandardError.ReadToEndAsync()
    $ssaProcess.WaitForExit()
    $ssaStdout = $ssaStdoutTask.GetAwaiter().GetResult()
    $ssaStderr = $ssaStderrTask.GetAwaiter().GetResult()
    $ssaExitCode = $ssaProcess.ExitCode
    $ssaProcess.Dispose()

    $ssaOutput = $ssaStdout + [Environment]::NewLine + $ssaStderr
    $ssaLogPath = Join-Path $ssaEvidenceDirectory "$Name-client.log"
    if ($ssaExitCode -ne 0) {
        $ssaUnavailable = $AllowUnavailable -and $ssaOutput -match '(?i)(vulkan.+(?:unavailable|unsupported|failed)|BackendCreationException)'
        $ssaResult = if ($ssaUnavailable) { 'UNAVAILABLE' } else { 'FAIL' }
        $ssaDetail = if ($ssaUnavailable) { 'Vulkan backend could not be created on this runtime.' } else { "Client GameTest exited with code $ssaExitCode." }
        Write-SsaLog $ssaLogPath "S3_$($Name.ToUpperInvariant())" $Arguments $ssaStartedAt $ssaExitCode $ssaResult $ssaDetail $ssaOutput
        if ($ssaUnavailable) {
            return [pscustomobject]@{ Name = $Name; Available = $false; LogPath = $ssaLogPath }
        }
        throw "$ssaDetail Evidence: $ssaLogPath"
    }

    $ssaFailure = $null
    $ssaSmallMarker = [regex]::Match(
        $ssaOutput,
        'SSA_S3_SCREENSHOT blocks=1000 revision=1 rotation=0 backend=(?<backend>.+?) layers=REQUIRED:200,OPTIONAL:200,TERRAIN_FILL:200,TERRAIN_REMOVAL:200,CONFLICT:200 path=')
    $ssaLargeMarker = [regex]::Match(
        $ssaOutput,
        'SSA_S3_SCREENSHOT blocks=5000 revision=2 rotation=0 backend=(?<backend>.+?) layers=REQUIRED:1000,OPTIONAL:1000,TERRAIN_FILL:1000,TERRAIN_REMOVAL:1000,CONFLICT:1000 path=')
    $ssaProfile = [regex]::Match(
        $ssaOutput,
        'SSA_S3_PROFILE blocks=5000 frames=120 p50_us=(?<p50>\d+) p95_us=(?<p95>\d+) max_us=(?<max>\d+) p95_alloc_bytes=(?<p95alloc>\d+) max_alloc_bytes=(?<maxalloc>\d+)')

    if ($ssaOutput -notmatch 'SSA_S3_CAMERA position=') {
        $ssaFailure = 'Missing S3 camera marker.'
    } elseif (-not $ssaSmallMarker.Success -or -not $ssaLargeMarker.Success) {
        $ssaFailure = 'Missing a required S3 screenshot/layer marker.'
    } elseif ($ssaSmallMarker.Groups['backend'].Value -notmatch [regex]::Escape($ExpectedBackend) -or
            $ssaLargeMarker.Groups['backend'].Value -notmatch [regex]::Escape($ExpectedBackend)) {
        $ssaFailure = "Expected $ExpectedBackend backend markers."
    } elseif (-not $ssaProfile.Success) {
        $ssaFailure = 'Missing S3 profile marker.'
    } elseif ([long]$ssaProfile.Groups['p95'].Value -ge 8000) {
        $ssaFailure = "Renderer p95 exceeded 8,000 us: $($ssaProfile.Groups['p95'].Value)"
    } elseif ([long]$ssaProfile.Groups['max'].Value -ge 16667) {
        $ssaFailure = "Renderer max exceeded 16,667 us: $($ssaProfile.Groups['max'].Value)"
    } elseif ([long]$ssaProfile.Groups['p95alloc'].Value -ge 524288) {
        $ssaFailure = "Renderer allocation p95 exceeded 524,288 bytes: $($ssaProfile.Groups['p95alloc'].Value)"
    } elseif ($ssaOutput -notmatch 'SSA_S3_LIFECYCLE active=none created=4 closed=4 live=0') {
        $ssaFailure = 'S3 buffer lifecycle did not return to zero live buffers.'
    } elseif ($ssaOutput -notmatch 'BUILD SUCCESSFUL') {
        $ssaFailure = 'Gradle did not report a successful client GameTest build.'
    }

    $ssaSmallScreenshot = Join-Path $ssaRunScreenshotDirectory '0000_ssa-s3-preview-1000.png'
    $ssaLargeScreenshot = Join-Path $ssaRunScreenshotDirectory '0001_ssa-s3-preview-5000.png'
    if (-not $ssaFailure -and (-not (Test-Path -LiteralPath $ssaSmallScreenshot) -or -not (Test-Path -LiteralPath $ssaLargeScreenshot))) {
        $ssaFailure = 'Expected S3 screenshot files were not produced.'
    }

    if ($ssaFailure) {
        Write-SsaLog $ssaLogPath "S3_$($Name.ToUpperInvariant())" $Arguments $ssaStartedAt $ssaExitCode 'FAIL' $ssaFailure $ssaOutput
        throw "$ssaFailure Evidence: $ssaLogPath"
    }

    Copy-Item -LiteralPath $ssaSmallScreenshot -Destination (Join-Path $ssaScreenshotDirectory "$Name-preview-1000.png") -Force
    Copy-Item -LiteralPath $ssaLargeScreenshot -Destination (Join-Path $ssaScreenshotDirectory "$Name-preview-5000.png") -Force
    Write-SsaLog $ssaLogPath "S3_$($Name.ToUpperInvariant())" $Arguments $ssaStartedAt $ssaExitCode 'PASS' 'Screenshots, lifecycle and budgets passed.' $ssaOutput

    return [pscustomobject]@{
        Name = $Name
        Available = $true
        Backend = $ssaLargeMarker.Groups['backend'].Value
        P50 = [long]$ssaProfile.Groups['p50'].Value
        P95 = [long]$ssaProfile.Groups['p95'].Value
        Maximum = [long]$ssaProfile.Groups['max'].Value
        P95Allocation = [long]$ssaProfile.Groups['p95alloc'].Value
        MaximumAllocation = [long]$ssaProfile.Groups['maxalloc'].Value
        LogPath = $ssaLogPath
    }
}

$ssaDefault = Invoke-SsaBackend `
    -Name 'default' `
    -Arguments ':platform-fabric:runClientGameTest --no-daemon' `
    -ExpectedBackend 'OpenGL'
$ssaVulkan = Invoke-SsaBackend `
    -Name 'vulkan' `
    -Arguments ':platform-fabric:runClientGameTest --args="--graphicsBackend vulkan" --no-daemon' `
    -ExpectedBackend 'Vulkan' `
    -AllowUnavailable

Write-Output "PASS S3 default: p95=$($ssaDefault.P95) us, max=$($ssaDefault.Maximum) us, p95_alloc=$($ssaDefault.P95Allocation) bytes"
if ($ssaVulkan.Available) {
    Write-Output "PASS S3 Vulkan: p95=$($ssaVulkan.P95) us, max=$($ssaVulkan.Maximum) us, p95_alloc=$($ssaVulkan.P95Allocation) bytes"
} else {
    Write-Output "S3 Vulkan unavailable; evidence: $($ssaVulkan.LogPath)"
}
