# build.ps1 -- build the "Synaxis Camera" DirectShow virtual camera DLL.
#
# Requirements: Visual Studio 2022 (Desktop C++ workload) + Windows 10/11 SDK.
# Output: <repo>/.build/native/synaxis_cam_filter.dll (X64 only).
#
# This script is intentionally standalone: it does not use gradle and does not
# touch the repository's generated resources. The Gradle build (see
# BUILD_GRADLE_SNIPPET.md) invokes it and copies the DLL into
# src/main/resources/native/ for release jars. When Visual Studio is not
# installed the script detects that and exits 0 with a warning, so a plain
# `gradlew build` still works on machines without a C++ toolchain.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File native/virtual_camera/build.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File native/virtual_camera/build.ps1 -Clean
param(
    [switch]$Clean
)

$ErrorActionPreference = 'Continue'

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$outDir = Join-Path $repoRoot '.build\native'
$source = Join-Path $PSScriptRoot 'synaxis_cam_filter.cpp'
$defFile = Join-Path $PSScriptRoot 'synaxis_cam_filter.def'
$outputDll = Join-Path $outDir 'synaxis_cam_filter.dll'

# Create the output directory up-front: the optional Gradle task copies from it
# even when this run decides to skip the build (no Visual Studio installed).
New-Item -ItemType Directory -Force $outDir | Out-Null

if ($Clean -and (Test-Path $outDir)) {
    Remove-Item -Recurse -Force $outDir
    New-Item -ItemType Directory -Force $outDir | Out-Null
    Write-Host "[native] cleaned $outDir"
}

function Find-VisualStudio {
    $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio\Installer\vswhere.exe'
    if (-not (Test-Path $vswhere)) {
        return $null
    }
    $path = & $vswhere -latest -products '*' -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath 2>$null
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($path)) {
        return $null
    }
    return $path.Trim()
}

$vsPath = Find-VisualStudio
if (-not $vsPath) {
    $legacy = 'C:\Program Files\Microsoft Visual Studio\2022\Community'
    if (Test-Path $legacy) {
        $vsPath = $legacy
    }
}
if (-not $vsPath) {
    Write-Warning '[native] Visual Studio 2022 with the C++ toolset was not found; skipping DirectShow filter build.'
    exit 0
}

$msvcRoot = Join-Path $vsPath 'VC\Tools\MSVC'
if (-not (Test-Path $msvcRoot)) {
    Write-Warning "[native] MSVC tools missing under $msvcRoot; skipping build."
    exit 0
}
$msvcVersion = Get-ChildItem $msvcRoot -Directory | Sort-Object Name -Descending | Select-Object -First 1
$msvcDir = $msvcVersion.FullName

$sdkRoot = 'C:\Program Files (x86)\Windows Kits\10'
if (-not (Test-Path (Join-Path $sdkRoot 'Include'))) {
    $sdkRoot = 'C:\Program Files\Windows Kits\10'
}
if (-not (Test-Path (Join-Path $sdkRoot 'Include'))) {
    Write-Warning '[native] Windows SDK 10 was not found; skipping DirectShow filter build.'
    exit 0
}
$sdkVersion = Get-ChildItem (Join-Path $sdkRoot 'Include') -Directory |
        Where-Object { $_.Name -match '^10\.' } |
        Sort-Object Name -Descending |
        Select-Object -First 1
if (-not $sdkVersion) {
    Write-Warning '[native] No 10.x Windows SDK include directory found; skipping build.'
    exit 0
}
$sdkInc = $sdkVersion.FullName
$sdkLib = Join-Path (Join-Path $sdkRoot 'Lib') $sdkVersion.Name

$cl = Join-Path $msvcDir 'bin\Hostx64\x64\cl.exe'
$lib = Join-Path $msvcDir 'bin\Hostx64\x64\lib.exe'
if (-not (Test-Path $cl)) {
    Write-Warning "[native] cl.exe not found at $cl; skipping build."
    exit 0
}

New-Item -ItemType Directory -Force $outDir | Out-Null

$env:INCLUDE = (@(
        (Join-Path $msvcDir 'include'),
        (Join-Path $sdkInc 'ucrt'),
        (Join-Path $sdkInc 'um'),
        (Join-Path $sdkInc 'shared'),
        (Join-Path $sdkInc 'winrt')
    ) -join ';')
$env:LIB = (@(
        (Join-Path $msvcDir 'lib\x64'),
        (Join-Path $sdkLib 'ucrt\x64'),
        (Join-Path $sdkLib 'um\x64')
    ) -join ';')
$env:PATH = (Join-Path $msvcDir 'bin\Hostx64\x64') + ';' + $env:PATH

Write-Host "[native] Visual Studio: $vsPath"
Write-Host "[native] MSVC: $($msvcVersion.Name)"
Write-Host "[native] Windows SDK: $($sdkVersion.Name)"
Write-Host "[native] building $outputDll"

Push-Location $outDir
try {
    & $cl /nologo /LD /EHsc /W3 /O2 /DUNICODE /D_UNICODE /D_WIN32_WINNT=0x0A00 `
        /Fe:synaxis_cam_filter.dll /Fo:synaxis_cam_filter.obj $source `
        /link /MACHINE:X64 /DEF:$defFile `
        ws2_32.lib strmiids.lib quartz.lib ole32.lib oleaut32.lib uuid.lib advapi32.lib user32.lib gdi32.lib
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0) {
        Write-Error "[native] DirectShow filter build failed (cl exit $exitCode)."
        exit $exitCode
    }
    if (-not (Test-Path $outputDll)) {
        Write-Error "[native] build reported success but $outputDll is missing."
        exit 1
    }
    $hash = (Get-FileHash $outputDll -Algorithm SHA256).Hash
    $size = (Get-Item $outputDll).Length
    Write-Host "[native] OK $outputDll ($size bytes, sha256 $hash)"

    # Development self-test harness (not part of the shipped mod).
    $testSource = Join-Path $PSScriptRoot 'filter_smoke_test.cpp'
    if (Test-Path $testSource) {
        Write-Host '[native] building filter_smoke_test.exe'
        & $cl /nologo /EHsc /W3 /O2 /DUNICODE /D_UNICODE /D_WIN32_WINNT=0x0A00 `
            /Fe:filter_smoke_test.exe /Fo:filter_smoke_test.obj $testSource `
            /link /MACHINE:X64 /SUBSYSTEM:CONSOLE `
            strmiids.lib quartz.lib ole32.lib oleaut32.lib uuid.lib
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "[native] filter_smoke_test.exe build failed ($LASTEXITCODE); the DLL itself is fine."
        } else {
            Write-Host "[native] OK $outDir\filter_smoke_test.exe"
        }
    }
    Write-Output $outputDll
} finally {
    Pop-Location
}
