<#
.SYNOPSIS
  Regression check for the SVN Enhance Merge plugin against a specific IntelliJ Platform version.

.DESCRIPTION
  存在的原因：svn4idea 的 API 不在 IDEA 平台兼容承诺内，升级 IDE 基线前必须实测。
  手工"改 gradle.properties → runIde → 翻 idea.log"既慢又容易漏看异常，这个脚本把四步固化。

  1. Kills any running sandbox IDEA (idea-sandbox) and clears its log.
  2. Starts `gradlew runIde -PideaVersion=<Version> --args=<Project>` in the background,
     redirecting gradle output to build/tmp/runide-<version>.log.
  3. Waits until the plugin has been loaded (the "EnhanceMerge i18n" log line) or times out.
     That line is a better readiness signal than "process exists": the sandbox process can be
     up while the plugin is still loading, and startup errors would be missed.
  4. Scans idea.log for plugin-related errors and prints a PASS/FAIL summary.
  5. By default keeps the sandbox running so the main flow can be walked manually.

  Exit codes: 0 = PASS; 1 = launcher exited early (build/download failure);
  2 = timeout; 3 = plugin errors found; 64 = bad argument.

.PARAMETER Version
  IntelliJ Platform version, e.g. 2022.1, 2023.3, 2025.2.

.PARAMETER Project
  Project path passed to the sandbox as the startup argument.

.PARAMETER TimeoutSec
  Max seconds to wait for the sandbox to become ready (default 420). The first run of a new
  version has to download that IDE, so keep it generous.

.PARAMETER CloseWhenDone
  Shut the sandbox down after the log scan.

.EXAMPLE
  powershell -File scripts\verify-plugin.ps1 -Version 2025.2
.EXAMPLE
  powershell -File scripts\verify-plugin.ps1 -Version 2022.1 -CloseWhenDone
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)][string]$Version,
  [string]$Project = 'D:\WorkSpace\ideaProjects\drools_learn',
  [int]$TimeoutSec = 420,
  [switch]$CloseWhenDone
)

$ErrorActionPreference = 'Continue'

$repoRoot = Split-Path -Parent $PSScriptRoot
if ([string]::IsNullOrWhiteSpace($env:GRADLE_USER_HOME)) { $env:GRADLE_USER_HOME = 'G:\GradleRepository' }

$logFile = Join-Path $repoRoot 'build\idea-sandbox\system\log\idea.log'
$tmpDir = Join-Path $repoRoot 'build\tmp'
if (-not (Test-Path $tmpDir)) { New-Item -ItemType Directory -Path $tmpDir | Out-Null }
$gradleLog = Join-Path $tmpDir ("runide-{0}.log" -f $Version)
$launcher = Join-Path $tmpDir ("runide-{0}.bat" -f $Version)

function Stop-Sandbox {
  Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -like '*idea-sandbox*' } |
    ForEach-Object {
      Write-Host ("[verify] stopping sandbox pid {0}" -f $_.ProcessId)
      Stop-Process -Id $_.ProcessId -Force
    }
}

function Test-SandboxRunning {
  return [bool](Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
    Where-Object { $_.CommandLine -like '*idea-sandbox*' })
}

function Show-GradleTail {
  if (Test-Path $gradleLog) {
    Write-Host '[verify] ---- tail of gradle output ----'
    Get-Content $gradleLog -Tail 30 | ForEach-Object { Write-Host ("  | " + $_) }
  }
  else {
    Write-Host '[verify] (no gradle output file; run `gradlew runIde` manually to see why)'
  }
}

# --- 1. clean slate -------------------------------------------------------
Stop-Sandbox
Start-Sleep -Seconds 2
if (Test-Path $logFile) { Remove-Item $logFile -Force }

# --- 2. launch ------------------------------------------------------------
$batLines = @(
  "set GRADLE_USER_HOME=$env:GRADLE_USER_HOME",
  "cd /d `"$repoRoot`"",
  "gradlew.bat runIde -PideaVersion=$Version --args=`"$Project`" > `"$gradleLog`" 2>&1"
)
$batLines -join "`r`n" | Set-Content -Path $launcher -Encoding ASCII
Write-Host "[verify] launching sandbox for IDEA $Version"
Write-Host "[verify] gradle output -> $gradleLog"
Start-Process -FilePath $launcher -WorkingDirectory $repoRoot -WindowStyle Minimized

# --- 3. wait until the plugin has been loaded ------------------------------
$ready = $false
$elapsed = 0
while ($elapsed -lt $TimeoutSec) {
  Start-Sleep -Seconds 5
  $elapsed += 5
  if ($elapsed % 15 -eq 0) { Write-Host "[verify] waiting for the sandbox... ${elapsed}s / ${TimeoutSec}s" }

  if (Test-Path $logFile) {
    $text = Get-Content -Path $logFile -Raw -ErrorAction SilentlyContinue
    if ($text -and $text -match 'EnhanceMerge i18n') { $ready = $true; break }
  }

  if (-not (Test-SandboxRunning)) {
    # gradle may still be compiling or downloading the IDE - only give up once no java is left
    $gradleAlive = [bool](Get-CimInstance Win32_Process -Filter "Name='java.exe'" |
      Where-Object { $_.CommandLine -like '*gradle*' -or $_.CommandLine -like '*GradleDaemon*' })
    if (-not $gradleAlive) {
      Write-Host '[verify] ERROR: the sandbox process exited before the plugin was loaded.'
      Show-GradleTail
      exit 1
    }
  }
}

if (-not $ready) {
  Write-Host "[verify] TIMEOUT after ${TimeoutSec}s waiting for the plugin to load."
  Show-GradleTail
  exit 2
}

Write-Host "[verify] plugin loaded after ~${elapsed}s"

# --- 4. scan the startup log for plugin-related errors ---------------------
Start-Sleep -Seconds 10
$lines = @()
if (Test-Path $logFile) { $lines = @(Get-Content $logFile -ErrorAction SilentlyContinue) }

$platformNoise = 'OSProcessHandler|MainPassesRunner'
$issues = @($lines | Where-Object {
  ($_ -match 'SEVERE' -and $_ -notmatch $platformNoise) -or
  ($_ -match 'EnhanceMerge' -and $_ -notmatch 'i18n') -or
  ($_ -match '^\s*(com\.svn|org\.jetbrains\.idea\.svn)')
})

Write-Host ''
Write-Host '[verify] ==================== summary ===================='
Write-Host ("[verify] target      : IDEA {0}" -f $Version)
Write-Host ("[verify] sandbox log : {0}" -f $logFile)
Write-Host ("[verify] log lines   : {0}" -f $lines.Count)
Write-Host ("[verify] issues      : {0}" -f $issues.Count)

if ($issues.Count -gt 0) {
  Write-Host '[verify] ---------- first 20 issue lines ----------'
  $issues | Select-Object -First 20 | ForEach-Object {
    $t = $_
    if ($t.Length -gt 180) { $t = $t.Substring(0, 180) }
    Write-Host ("  ! " + $t)
  }
  Write-Host '[verify] RESULT: FAIL'
  if ($CloseWhenDone) { Stop-Sandbox }
  exit 3
}

Write-Host '[verify] RESULT: PASS (no plugin-related errors in the startup log)'
Write-Host '[verify] manual checklist: branch popup -> revision list (status icon / checkbox click /'
Write-Host '[verify]   speed search / Ctrl+D diff) -> Merge Selected (local changes prompt -> progress -> conflicts)'

if ($CloseWhenDone) {
  Write-Host '[verify] closing sandbox (-CloseWhenDone)'
  Stop-Sandbox
}
else {
  Write-Host '[verify] sandbox left running for manual verification.'
  Write-Host ('[verify] close it with: powershell -File "{0}" -Version {1} -CloseWhenDone' -f $PSCommandPath, $Version)
}
