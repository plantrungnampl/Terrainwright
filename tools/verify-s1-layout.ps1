$ErrorActionPreference = 'Stop'

$ssaRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$ssaRequired = @(
    'settings.gradle.kts',
    'build.gradle.kts',
    'gradle.properties',
    'architect-core/build.gradle.kts',
    'construction-core/build.gradle.kts',
    'minecraft-common/build.gradle.kts',
    'platform-fabric/build.gradle.kts'
)

foreach ($ssaPath in $ssaRequired) {
    if (-not (Test-Path -LiteralPath (Join-Path $ssaRoot $ssaPath))) {
        throw "Missing required S1 file: $ssaPath"
    }
}

$ssaSettings = Get-Content -Raw -LiteralPath (Join-Path $ssaRoot 'settings.gradle.kts')
foreach ($ssaModule in @('architect-core', 'construction-core', 'minecraft-common', 'platform-fabric')) {
    if ($ssaSettings -notmatch [regex]::Escape(":$ssaModule")) {
        throw "Module not included: $ssaModule"
    }
}

Write-Output 'PASS S1 module layout'
