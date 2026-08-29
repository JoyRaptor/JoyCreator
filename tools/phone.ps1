# phone.ps1 — PowerShell twin of tools/phone.sh, for driving the test phones over adb.
#
# WHY BOTH EXIST: phone.sh needs bash. JoyRaptor's terminal is PowerShell, and at least one
# agent reported "bash tools/phone.sh needs WSL" and fell back to raw adb paths. A tool
# that only works in one of the two shells people actually use here is half a tool.
# Keep the two in step: if you add a command to one, add it to the other.
#
# Usage:
#   .\tools\phone.ps1 devices             list attached devices
#   .\tools\phone.ps1 size                physical AND override size (tap against OVERRIDE)
#   .\tools\phone.ps1 install             install the current debug APK
#   .\tools\phone.ps1 launch              force-stop + launch the app
#   .\tools\phone.ps1 shot out.png        screenshot to that path
#   .\tools\phone.ps1 tap 628 2110        tap (device pixels, per `size`)
#   .\tools\phone.ps1 swipe 500 1200 500 600 [ms]
#   .\tools\phone.ps1 log AudioLayerSync  dump logcat for one tag
#   .\tools\phone.ps1 audio               our app's AudioTracks + allocation count
#   .\tools\phone.ps1 build               last BUILD line from build.log, with its date
#
# Multi-device: $env:PHONE = "<serial>" to pin one. Otherwise the first is used.

param(
    [Parameter(Position = 0)][string]$Cmd = "help",
    [Parameter(Position = 1, ValueFromRemainingArguments = $true)][string[]]$Rest
)

$ErrorActionPreference = "Continue"
Set-Location (Split-Path $PSScriptRoot -Parent)

$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = Join-Path $env:USERPROFILE "AppData\Local\Android\Sdk\platform-tools\adb.exe" }
if (-not (Test-Path $adb)) {
    Write-Output "adb not found. Looked under `$env:LOCALAPPDATA and `$env:USERPROFILE for Android\Sdk\platform-tools."
    exit 1
}

$pkg = "com.fadcam.beta"
$apk = "app\build\outputs\apk\default\debug\app-default-arm64-v8a-debug.apk"

function Get-Serial {
    if ($env:PHONE) { return $env:PHONE }
    $line = & $adb devices | Select-String "`tdevice$" | Select-Object -First 1
    if ($null -eq $line) { return $null }
    return ($line.ToString() -split "`t")[0]
}

function Adb {
    $s = Get-Serial
    if ($null -eq $s) { Write-Output "no device attached"; exit 1 }
    & $adb -s $s @args
}

switch ($Cmd) {
    "devices" { & $adb devices -l }

    "size" {
        Adb shell wm size
        Write-Output "NOTE: if an Override size is printed, compute ALL taps against the OVERRIDE."
    }

    "install" {
        if (-not (Test-Path $apk)) { Write-Output "no APK at $apk - has the watcher built yet?"; exit 1 }
        Adb install -r $apk
        Adb shell dumpsys package $pkg | Select-String "lastUpdateTime" | Select-Object -First 1
    }

    "launch" {
        Adb shell am force-stop $pkg
        # SplashActivity is the ONLY launchable entry point; the editor is not exported.
        Adb shell am start -n "$pkg/com.fadcam.SplashActivity" | Out-Null
        Write-Output "launched; wait ~8s before the first screenshot"
    }

    "shot" {
        $out = if ($Rest.Count -ge 1) { $Rest[0] } else { "shot.png" }
        # exec-out, not shell+pull: shell mangles the PNG on some Windows adb builds.
        $s = Get-Serial
        & $adb -s $s exec-out screencap -p > $out
        if ((Get-Item $out).Length -gt 0) { Write-Output "wrote $out ($((Get-Item $out).Length) bytes)" }
        else { Write-Output "screenshot came back EMPTY - the device may be locked" }
    }

    "tap" { Adb shell input tap $Rest[0] $Rest[1] }

    "swipe" {
        $ms = if ($Rest.Count -ge 5) { $Rest[4] } else { "300" }
        Adb shell input swipe $Rest[0] $Rest[1] $Rest[2] $Rest[3] $ms
    }

    "log" { Adb logcat -d -s $Rest[0] }

    "audio" {
        # pidof returns NOTHING when the app is not running, and .Trim() on that throws a
        # null-reference wall of red that reads like a broken tool rather than "open the app".
        $raw = Adb shell pidof $pkg
        $p = if ($raw) { ([string]$raw).Trim() } else { "" }
        if (-not $p) {
            Write-Output "$pkg is not running - launch it first (.\tools\phone.ps1 launch)"
            break
        }
        Write-Output "pid=$p"
        Write-Output "--- AudioTracks (want state:started while playing) ---"
        Adb shell "dumpsys audio | grep $p" | Select-String "AudioTrack"
        Write-Output "--- AudioTracks allocated (churn is a bug even if it sounds fine) ---"
        # NOTE: "new player piid:" lines live in dumpsys audio's own event log, NOT logcat.
        # Counting them from logcat returns 0 and reads as "no churn" - a false green.
        Adb shell "dumpsys audio | grep -c 'new player piid'"
    }

    "build" {
        if (-not (Test-Path "build.log")) { Write-Output "no build.log"; exit 1 }
        $f = Get-Item "build.log"
        Write-Output ("build.log  {0}  ({1:N0} bytes)" -f $f.LastWriteTime, $f.Length)
        # build.log is UTF-16. Get-Content -Encoding Unicode reads it correctly; without
        # that you get one character per two bytes and every grep silently misses.
        $last = Get-Content "build.log" -Encoding Unicode -Tail 400 |
                Select-String "^BUILD (SUCCESSFUL|FAILED)" | Select-Object -Last 1
        if ($last) { Write-Output $last.ToString().Trim() } else { Write-Output "no BUILD line in the last 400 lines - a build may be running" }
    }

    default {
        Get-Content $PSCommandPath | Select-Object -Skip 1 -First 20 | ForEach-Object { $_ -replace '^# ?', '' }
    }
}
