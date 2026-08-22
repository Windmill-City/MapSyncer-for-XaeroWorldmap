# MapSyncer 开发环境依赖一键部署（Forge 1.20.1 单版本）
#
# 安装/检测 JDK 17、引导 Gradle Wrapper（8.9，ForgeGradle 需要 Gradle 8.x）、
# 预拉取 Forge 1.20.1 构建所需的 Maven / MC 工件。
#
# 用法:
#   .\scripts\setup-deps.ps1              # 完整部署
#   .\scripts\setup-deps.ps1 -Quick       # 仅 JDK + Wrapper，不预拉 Maven
#   .\scripts\setup-deps.ps1 -SkipJdk     # 跳过 JDK 安装（仅检测）
#   .\scripts\setup-deps.ps1 -SkipMaven   # 跳过 Maven 预拉取
#
# 也可双击 scripts\setup-deps.bat

param(
    [switch]$Quick,
    [switch]$SkipJdk,
    [switch]$SkipMaven,
    [switch]$SkipPropsUpdate
)

$ErrorActionPreference = "Stop"

$ScriptDir = $PSScriptRoot
$ProjectRoot = Split-Path -Parent $ScriptDir
$GradleWrapper = Join-Path $ProjectRoot "gradlew.bat"
$PropsFile = Join-Path $ProjectRoot "gradle.properties"

$RequiredJdks = @(17)

function Write-Step([string]$Message) {
    Write-Host ""
    Write-Host "==> $Message" -ForegroundColor Cyan
}

function Write-Ok([string]$Message) {
    Write-Host "    [OK] $Message" -ForegroundColor Green
}

function Write-Warn([string]$Message) {
    Write-Host "    [WARN] $Message" -ForegroundColor Yellow
}

function Write-Fail([string]$Message) {
    Write-Host "    [FAIL] $Message" -ForegroundColor Red
}

function Find-JdkInstallations {
    $roots = @(
        "${env:ProgramFiles}\Eclipse Adoptium",
        "${env:ProgramFiles}\Java",
        "${env:ProgramFiles(x86)}\Eclipse Adoptium",
        "${env:ProgramFiles}\Zulu"
    )

    $found = @{}
    foreach ($root in $roots) {
        if (-not (Test-Path $root)) { continue }
        Get-ChildItem $root -Directory -ErrorAction SilentlyContinue | ForEach-Object {
            if ($_.Name -match 'jdk-(\d+)' -or $_.Name -match 'zulu-(\d+)') {
                $major = [int]$Matches[1]
                $javaExe = Join-Path $_.FullName "bin\java.exe"
                if (Test-Path $javaExe) {
                    if (-not $found.ContainsKey($major)) {
                        $found[$major] = $_
                    }
                }
            }
        }
    }
    return $found
}

function Install-JdkViaWinget([int]$Major) {
    $package = "EclipseAdoptium.Temurin.17.JDK"
    Write-Host "    Installing Temurin JDK $Major via winget ($package)..." -ForegroundColor DarkGray
    winget install --id $package `
        --accept-package-agreements `
        --accept-source-agreements `
        --disable-interactivity `
        --silent
}

function Ensure-Jdks {
    Write-Step "JDK 17 (Eclipse Temurin)"

    if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
        Write-Warn "winget not found; will only detect existing JDK installations"
    }

    foreach ($major in $RequiredJdks) {
        $installations = Find-JdkInstallations
        if ($installations.ContainsKey($major)) {
            Write-Ok "JDK $major -> $($installations[$major].FullName)"
            continue
        }

        if ($SkipJdk) {
            Write-Warn "JDK $major missing (SkipJdk set)"
            continue
        }

        if (Get-Command winget -ErrorAction SilentlyContinue) {
            try {
                Install-JdkViaWinget $major
            } catch {
                Write-Warn "winget install JDK $major failed: $_"
            }
        } else {
            Write-Warn "JDK $major missing; install Temurin $major manually"
        }
    }

    $final = Find-JdkInstallations
    foreach ($major in $RequiredJdks) {
        if ($final.ContainsKey($major)) {
            Write-Ok "JDK $major ready"
        } else {
            Write-Fail "JDK $major still missing"
        }
    }
    return $final
}

function Update-GradleProperties([hashtable]$Jdks) {
    if ($SkipPropsUpdate) { return }
    if (-not (Test-Path $PropsFile)) {
        Write-Warn "gradle.properties not found, skip path update"
        return
    }

    Write-Step "Update gradle.properties JDK paths"

    $jdk17 = if ($Jdks.ContainsKey(17)) { ($Jdks[17].FullName -replace '\\', '/') } else { $null }
    if (-not $jdk17) {
        Write-Warn "JDK 17 not detected, keeping existing gradle.properties"
        return
    }

    $content = Get-Content $PropsFile -Raw
    $content = $content -replace 'org\.gradle\.java\.home=.*', "org.gradle.java.home=$jdk17"
    $content = $content -replace 'org\.gradle\.java\.installations\.paths=.*', "org.gradle.java.installations.paths=$jdk17"
    $utf8NoBom = New-Object System.Text.UTF8Encoding $false
    [System.IO.File]::WriteAllText($PropsFile, $content, $utf8NoBom)
    Write-Ok "gradle.properties updated"
}

function Invoke-GradleWrapper([string[]]$Tasks) {
    if (-not (Test-Path $GradleWrapper)) {
        throw "gradlew.bat not found at $GradleWrapper"
    }
    Push-Location $ProjectRoot
    try {
        & $GradleWrapper @Tasks
        if ($LASTEXITCODE -ne 0) {
            throw "gradlew failed (exit $LASTEXITCODE): $($Tasks -join ' ')"
        }
    } finally {
        Pop-Location
    }
}

function Prefetch-ForgeDependencies([hashtable]$Jdks) {
    Write-Step "Prefetch Forge 1.20.1 dependencies (Gradle Wrapper 8.9)"

    if ($Jdks.ContainsKey(17)) {
        $env:JAVA_HOME = $Jdks[17].FullName
        $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
    }

    Invoke-GradleWrapper @(
        "--no-daemon", "-x", "test",
        ":libs:lz4-relocated:shadowJar",
        ":libs:core:compileJava",
        ":libs:platform-api:compileJava",
        ":mc-1.20.1:forge:compileJava"
    )
    Write-Ok "Forge 1.20.1 dependencies prefetched"
}

Write-Host "============================================" -ForegroundColor White
Write-Host " MapSyncer - Setup Development Dependencies" -ForegroundColor White
Write-Host "============================================" -ForegroundColor White
Write-Host "Project: $ProjectRoot"

$jdks = Ensure-Jdks
Update-GradleProperties $jdks

Write-Step "Gradle Wrapper (8.9 via gradlew)"
Invoke-GradleWrapper @("--version")
Write-Ok "Gradle Wrapper ready"

if ($Quick) {
    Write-Warn "Quick mode: skipped Maven / MC artifact prefetch"
} elseif (-not $SkipMaven) {
    Prefetch-ForgeDependencies $jdks
} else {
    Write-Warn "SkipMaven set"
}

Write-Host ""
Write-Host "============================================" -ForegroundColor Green
Write-Host " Setup complete" -ForegroundColor Green
Write-Host "============================================" -ForegroundColor Green
Write-Host ""
Write-Host "Next steps:"
Write-Host "  .\gradlew.bat build -x test              # build Forge 1.20.1 mod"
Write-Host ""
