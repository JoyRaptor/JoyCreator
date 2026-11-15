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
#   .\tools\phone.ps1 install [apk]       install the current debug APK (or the APK given)
#   .\tools\phone.ps1 launch              force-stop + launch the app
#   .\tools\phone.ps1 shot out.png        screenshot to that path
#   .\tools\phone.ps1 tap 628 2110        tap (device pixels, per `size`)
#   .\tools\phone.ps1 swipe 500 1200 500 600 [ms]
#   .\tools\phone.ps1 key KEYCODE_HOME    key event(s)
#   .\tools\phone.ps1 text hello there    type into the focused field
#   .\tools\phone.ps1 start <pkg/activity | pkg>   am start (never monkey)
#   .\tools\phone.ps1 shell-write <cmd>   any shell command that changes the device
#   .\tools\phone.ps1 holds               exit 0 if you hold this device, else REFUSED + exit 3
#   .\tools\phone.ps1 adb <args>          any adb call on this slot's device; looking is open, changes need the lock
#   .\tools\phone.ps1 log AudioLayerSync  dump logcat for one tag
#   .\tools\phone.ps1 audio               our app's AudioTracks + allocation count
#   .\tools\phone.ps1 build               last BUILD line from build.log, with its date
#   .\tools\phone.ps1 who                 who holds the phone right now (the shared phone lock)
#   .\tools\phone.ps1 claim <name> <why>  take the phone; refresh it the same way while you work
#   .\tools\phone.ps1 release <name>      give it back
#
# ONE AGENT DRIVES THE PHONE AT A TIME (owner, 2026-10-14). The lock is the file
# %TEMP%\jb-phone.lock (beside Codex's jb-gradle.lock): line 1 the holder, line 2 when, line 3 why.
# install / launch / start / tap / swipe / key / text / shell-write / restore run ONLY for the lock's
# holder: set $env:JB_PHONE_HOLDER (or the older $env:PHONE_OWNER) to the name you claimed with.
# Anyone else gets "REFUSED, nothing sent" and exit 3. Each such action refreshes your claim.
# A lock untouched for 30 minutes is stale and may be taken over (by claiming it).
# Screenshots, logcat and dumpsys only look, so they don't need the lock.
#
# Multi-device: the Note 9 (the sandbox phone) is ALWAYS the default, never "the first device".
# $env:PHONE = "<serial>" pins another one. `.\tools\phone.ps1 serial` prints the serial this
# tool would use, for scripts that call raw adb: `adb -s (.\tools\phone.ps1 serial) ...`.
#
# SECOND PHONE (owner, 2026-11-13): the Note 20. It holds the owner's REAL projects.
# On USB it shares the adb server with the Note 9, so RAW adb MUST name a serial: `adb -s <serial>`.
# Bare `adb install` / `adb shell` with both phones attached fails or, worse, hits the wrong one.
# To use the Note 20 through this tool:
#   $env:PHONE_SLOT = "note20"     (finds it by model on the one adb server, and uses its own
#                                   lock, %TEMP%\jb-phone2.lock; who/claim/release work the same)
#
# TEST TABLET (owner, 2026-11-13): a Tab S8 Ultra with nothing of the owner's on it, for app
# checks and tablet layouts (1848x2960, landscape, Android 15). To use it:
#   $env:PHONE_SLOT = "tablet"     (finds it by model, USB or Wi-Fi; lock %TEMP%\jb-phone3.lock)
# Over Wi-Fi its address changes when wireless debugging restarts: `adb mdns services` shows the
# current one, then `adb connect <address>`.
# Rules there: throwaway projects and drawings only, named "TEST ..."; never open, edit, move or
# delete anything that was already on it; never uninstall or clear data. See tasks/BOARD.md.
#
# TEST FOLD (owner, 2026-11-14): a Galaxy Z Fold 4, free to test (install, create, delete your own
# test files). Its screen is broken, so the owner cannot touch it: drive it only by adb input, and
# never leave it needing a tap to recover. Wi-Fi debugging, paired with this PC. To use it:
#   $env:PHONE_SLOT = "fold"       (finds it by model; lock %TEMP%\jb-phone4.lock)

# Arguments are read by hand, not through param(): PowerShell would otherwise take adb flags such as
# `-c` (logcat -c) or `-r` (install -r) as abbreviations of this script's own parameter names.
$Cmd = if ($args.Count -ge 1) { [string]$args[0] } else { "help" }
$Rest = [string[]]@($args | Select-Object -Skip 1)

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

# Phones are told apart by MODEL, read live from `adb devices -l`. No serial is written in this
# file or any tracked file (tasks/AGENT_START.md rule 1; the commit hook refuses them).
$modelNote9 = "SM_N960U"      # the sandbox phone: the default for everything
$modelNote20 = "SM_N986U"     # the owner's REAL projects: only ever by PHONE_SLOT = "note20"
$modelTablet = "SM_X900"      # the Tab S8 Ultra, a blank test tablet: PHONE_SLOT = "tablet"
$modelFold = "SM_F936U"       # the Galaxy Z Fold 4, a test device with a broken screen: PHONE_SLOT = "fold"

# Serials of attached, ready devices of one model ("" = any model).
function Find-ByModel([string]$model) {
    $rx = if ($model) { "^\S+\s+device\s.*\bmodel:$model\b" } else { "^\S+\s+device\s" }
    @(& $adb devices -l | Select-String $rx | ForEach-Object { ($_.ToString() -split "\s+")[0] })
}

# Never "the first device": with both phones on USB the first one may be the Note 20.
function Get-Serial {
    if ($env:PHONE) { return $env:PHONE }
    # @(...) at every call: PowerShell hands back ONE match as a bare string, and [0] of a string
    # is its first character, not the serial.
    $n9 = @(Find-ByModel $modelNote9)
    if ($n9.Count -ge 1) { return $n9[0] }
    # No Note 9: use a lone unknown device, but never fall through to the Note 20 or the tablet.
    $named = @(Find-ByModel $modelNote20) + @(Find-ByModel $modelTablet) + @(Find-ByModel $modelFold)
    $others = @(Find-ByModel "" | Where-Object { $named -notcontains $_ })
    if ($others.Count -eq 1) { return $others[0] }
    return $null
}

function Adb {
    $s = Get-Serial
    if ($null -eq $s) { Write-Output "no device attached"; exit 1 }
    & $adb -s $s @args
}

$phoneLock = Join-Path $env:TEMP "jb-phone.lock"
if ($env:PHONE_SLOT -eq "note20") {
    if (-not $env:PHONE) {
        # ONE adb server only (the default). A second server, as this tool briefly started on
        # 11-13, grabs every USB device and hides the Note 9 and the tablet from everyone.
        $n20 = @(Find-ByModel $modelNote20)
        # Not attached: pin a name adb cannot find, so nothing falls through to the Note 9.
        $env:PHONE = if ($n20.Count -ge 1) { $n20[0] } else { "note20-not-attached" }
    }
}
if ($env:PHONE_SLOT -eq "tablet") {
    if (-not $env:PHONE) {
        $tab = @(Find-ByModel $modelTablet)           # USB or Wi-Fi, same adb server as the Note 9
        $env:PHONE = if ($tab.Count -ge 1) { $tab[0] } else { "tablet-not-attached" }
    }
}
# The Note 20 and the tablet each use their own lock, however they were reached.
if ($env:PHONE_SLOT -eq "note20" -or (@(Find-ByModel $modelNote20) -contains (Get-Serial))) {
    $phoneLock = Join-Path $env:TEMP "jb-phone2.lock"
}
if ($env:PHONE_SLOT -eq "tablet" -or (@(Find-ByModel $modelTablet) -contains (Get-Serial))) {
    $phoneLock = Join-Path $env:TEMP "jb-phone3.lock"
}
if ($env:PHONE_SLOT -eq "fold") {
    if (-not $env:PHONE) {
        $fold = @(Find-ByModel $modelFold)            # Wi-Fi, same adb server as the Note 9
        $env:PHONE = if ($fold.Count -ge 1) { $fold[0] } else { "fold-not-attached" }
    }
}
if ($env:PHONE_SLOT -eq "fold" -or (@(Find-ByModel $modelFold) -contains (Get-Serial))) {
    $phoneLock = Join-Path $env:TEMP "jb-phone4.lock"
}

function Get-PhoneLock {
    if (-not (Test-Path $phoneLock)) { return $null }
    $age = (Get-Date) - (Get-Item $phoneLock).LastWriteTime
    [pscustomobject]@{
        Holder = (Get-Content $phoneLock -TotalCount 1)
        Text   = (Get-Content $phoneLock -Raw)
        Stale  = $age.TotalMinutes -gt 30
    }
}

$deviceName = switch -Wildcard (Split-Path $phoneLock -Leaf) {
    "jb-phone2.lock" { "the Note 20" }
    "jb-phone3.lock" { "the test tablet" }
    "jb-phone4.lock" { "the test Fold" }
    default { "the Note 9" }
}

# True only for adb calls that just LOOK: screenshots to the PC, logcat without -c, pull, and shell reads
# (dumpsys, getprop, ls, cat ... optionally piped into grep/head/tail/wc/sort/uniq). Anything chained or
# redirected, `wm size <value>`, and run-as other than cat/ls count as changes and need the lock.
function Test-ReadOnly([string[]]$a) {
    $all = " " + ($a -join " ") + " "
    switch ($a[0]) {
        "exec-out" { return ($a.Count -ge 2 -and $a[1] -eq "screencap") }
        "logcat" { return ($all -notmatch ' -c ' -and $all -notmatch ' --clear ') }
        "pull" { return $true }
        "shell" {
            $line = ($a | Select-Object -Skip 1) -join " "
            if (-not $line -or $line -match ';|&&|\|\||>|<|`|\$\(') { return $false }
            $look = @("dumpsys", "getprop", "getevent", "ls", "cat", "pidof", "ps", "df", "uptime", "date", "id")
            $filters = @("grep", "head", "tail", "wc", "sort", "uniq")
            $parts = $line -split '\|'
            for ($i = 0; $i -lt $parts.Count; $i++) {
                $w = @($parts[$i].Trim() -split '\s+')
                if ($i -gt 0) { if ($filters -notcontains $w[0]) { return $false }; continue }
                if ($look -contains $w[0]) { continue }
                if ($w[0] -eq "wm" -and $w.Count -le 2) { continue }                         # wm size / wm density: read
                if ($w[0] -eq "run-as" -and $w.Count -ge 3 -and @("cat", "ls") -contains $w[2]) { continue }
                return $false
            }
            return $true
        }
    }
    return $false
}

# Who is calling: the name they claimed with.
function Get-Caller { if ($env:JB_PHONE_HOLDER) { $env:JB_PHONE_HOLDER } else { $env:PHONE_OWNER } }

# Anything that CHANGES a device (taps, keys, text, installs, am start, shell writes) needs the caller
# to HOLD that device's lock: not merely "nobody else has it". Three times lanes typed into a phone
# another lane was driving (11-13, 11-14) because a free or stale lock let them through.
function Assert-Holder {
    $me = Get-Caller
    $l = Get-PhoneLock
    $why = $null
    if (-not $me) { $why = "no caller name: claim $deviceName, then set `$env:JB_PHONE_HOLDER to the name you claimed with" }
    elseif ($null -eq $l) { $why = "$deviceName is not claimed: run phone.ps1 claim $me `"<why>`" first" }
    elseif ($l.Holder -ne $me) {
        $why = "$deviceName is held by $($l.Holder), not $me"
        if ($l.Stale) { $why += " (their claim is stale: claim it yourself to take over)" }
    }
    if ($why) { Write-Output "REFUSED, nothing sent: $why"; exit 3 }
    (Get-Item $phoneLock).LastWriteTime = Get-Date   # every action refreshes the holder's claim
}

switch ($Cmd) {
    "who" {
        $l = Get-PhoneLock
        if ($null -eq $l) { Write-Output "free" }
        elseif ($l.Stale) { Write-Output "free (stale lock by $($l.Holder), over 30 minutes old)" }
        else { Write-Output $l.Text }
    }

    "claim" {
        $name = $Rest[0]
        if (-not $name) { Write-Output "usage: phone.ps1 claim <name> <why>"; exit 1 }
        $l = Get-PhoneLock
        if ($l -and -not $l.Stale -and $l.Holder -ne $name) {
            Write-Output "PHONE IN USE by $($l.Holder):"; Write-Output $l.Text; exit 3
        }
        $why = ($Rest | Select-Object -Skip 1) -join " "
        Set-Content -Path $phoneLock -Encoding utf8 -Value @($name, (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $why)
        Write-Output "$deviceName claimed by $name. Set `$env:JB_PHONE_HOLDER = '$name' for install/launch/start/tap/swipe/key/text/shell-write."
    }

    "release" {
        $name = $Rest[0]
        $l = Get-PhoneLock
        if ($null -eq $l) { Write-Output "already free"; break }
        if ($l.Holder -ne $name -and -not $l.Stale) { Write-Output "held by $($l.Holder), not $name; not released"; exit 3 }
        Remove-Item $phoneLock
        Write-Output "phone released"
    }

    "devices" { & $adb devices -l }

    # The serial this tool would act on (the Note 9, or the Note 20 with PHONE_SLOT = "note20").
    # For scripts that call raw adb. Prints nothing and exits 1 when that phone is not attached.
    "serial" {
        $s = Get-Serial
        if ($null -eq $s -or $s -like "*-not-attached") { exit 1 }
        Write-Output $s
    }

    "size" {
        Adb shell wm size
        Write-Output "NOTE: if an Override size is printed, compute ALL taps against the OVERRIDE."
    }

    # Exit 0 when the caller holds this device, else the REFUSED line and exit 3 (for scripts such as phone.sh deploy).
    "holds" { Assert-Holder; Write-Output "$(Get-Caller) holds $deviceName" }

    "install" {
        Assert-Holder
        # Optional path: a cloud build fetched by tools\cloud-build.ps1 lands under build\cloud\<sha>\.
        if ($Rest.Count -ge 1) { $apk = $Rest[0] }
        if (-not (Test-Path $apk)) { Write-Output "no APK at $apk - has the watcher built yet?"; exit 1 }
        Adb install -r $apk
        Adb shell dumpsys package $pkg | Select-String "lastUpdateTime" | Select-Object -First 1
    }

    "launch" {
        Assert-Holder
        Adb shell am force-stop $pkg
        # SplashActivity is the ONLY launchable entry point; the editor is not exported.
        Adb shell am start -n "$pkg/com.fadcam.SplashActivity" | Out-Null
        Write-Output "launched; wait ~8s before the first screenshot"
    }

    "shot" {
        $out = if ($Rest.Count -ge 1) { $Rest[0] } else { "shot.png" }
        $s = Get-Serial
        if ($null -eq $s) { Write-Output "no device attached"; exit 1 }
        # PowerShell's `>` is a TEXT redirect: it re-encodes the stream and prepends a BOM,
        # so `exec-out screencap -p > file.png` produces a corrupt PNG whose first bytes are
        # EF BB BF. It looks like it worked - the file is ~600KB - and every image tool then
        # rejects it. Bounce through the device instead: screencap to /sdcard, then pull,
        # which is byte-exact on Windows.
        $tmp = "/sdcard/.phone_ps1_shot.png"
        & $adb -s $s shell screencap -p $tmp | Out-Null
        & $adb -s $s pull $tmp $out | Out-Null
        & $adb -s $s shell rm -f $tmp | Out-Null
        if (-not (Test-Path $out)) { Write-Output "screenshot failed - is the device locked?"; exit 1 }
        $f = Get-Item $out
        $sig = [System.IO.File]::ReadAllBytes($f.FullName)[0..3]
        if ($sig[0] -eq 0x89 -and $sig[1] -eq 0x50) {
            Write-Output "wrote $out ($($f.Length) bytes)"
        } else {
            Write-Output "wrote $out but it is NOT a PNG (first bytes $($sig -join ',')) - corrupt"
            exit 1
        }
    }

    "tap" { Assert-Holder; Adb shell input tap $Rest[0] $Rest[1] }

    "swipe" {
        Assert-Holder
        $ms = if ($Rest.Count -ge 5) { $Rest[4] } else { "300" }
        Adb shell input swipe $Rest[0] $Rest[1] $Rest[2] $Rest[3] $ms
    }

    # key KEYCODE_HOME | 3 | KEYCODE_BACK ...   (one or more key events)
    "key" {
        if ($Rest.Count -lt 1) { Write-Output "usage: phone.ps1 key <keycode> [more keycodes]"; exit 1 }
        Assert-Holder
        Adb shell input keyevent @Rest
    }

    # text <words>: typed into the focused field (spaces become %s, which `input text` needs).
    "text" {
        $t = $Rest -join " "
        if (-not $t) { Write-Output "usage: phone.ps1 text <words>"; exit 1 }
        if ($t -match "'") { Write-Output "REFUSED, nothing sent: a single quote cannot pass through adb's shell intact"; exit 1 }
        Assert-Holder
        Adb shell "input text '$($t -replace ' ', '%s')'"
    }

    # start <package/activity | package> [am start options]: am start, never monkey (monkey turns the
    # owner's rotation lock off). A bare package is resolved to its launcher activity.
    "start" {
        $c = if ($Rest.Count -ge 1) { $Rest[0] } else { "" }
        if (-not $c) { Write-Output "usage: phone.ps1 start <package/activity | package> [am start options]"; exit 1 }
        Assert-Holder
        if ($c -notlike "*/*") {
            $c = ([string](Adb shell cmd package resolve-activity --brief $c | Select-Object -Last 1)).Trim()
            if ($c -notlike "*/*") { Write-Output "no launchable activity for $($Rest[0])"; exit 1 }
        }
        Adb shell am start -n $c @($Rest | Select-Object -Skip 1)
    }

    # adb <anything>: any adb call on THIS slot's device. Looking runs for everyone; everything else needs
    # the lock (allowlist after the JoyBrush screens lane's nadb.sh).
    "adb" {
        if ($Rest.Count -lt 1) { Write-Output "usage: phone.ps1 adb <adb arguments>"; exit 1 }
        if (-not (Test-ReadOnly $Rest)) { Assert-Holder }
        Adb @Rest
    }

    # shell-write <command>: any adb shell command that changes the device (settings, pm, wm, setprop,
    # run-as writes, rm ...). Looking (dumpsys, getprop, logcat, screencap) needs no lock: use raw adb -s.
    "shell-write" {
        if ($Rest.Count -lt 1) { Write-Output "usage: phone.ps1 shell-write <command>"; exit 1 }
        Assert-Holder
        Adb shell @Rest
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

    "backup" {
        # Projects live in app-private storage, which Android WIPES on uninstall with no
        # prompt and no recovery. On 2026-08-29 an acceptance check did exactly that and
        # took 23 of JoyRaptor's 24 projects. Run this before anything that touches the app.
        $out = if ($Rest.Count -ge 1) { $Rest[0] } else {
            "projects_backup_" + (Get-Date -Format "yyyyMMdd_HHmmss") + ".tar" }
        $s = Get-Serial
        if ($null -eq $s) { Write-Output "no device attached"; exit 1 }

        Write-Output "--- projects on device ---"
        & $adb -s $s shell "run-as $pkg ls files/faditor/projects"

        # cmd /c for the redirect, NOT PowerShell's `>`. PowerShell re-encodes the stream and
        # prepends a BOM, which produced a 31MB 'backup' that tar read as ZERO entries -
        # a file that looks like a backup and restores nothing. Verified failure, not theory.
        $cmdLine = '"' + $adb + '" -s ' + $s + ' exec-out "run-as ' + $pkg + ' tar c files/faditor 2>/dev/null" > "' + $out + '"'
        cmd /c $cmdLine

        if (-not (Test-Path $out)) { Write-Output "BACKUP FAILED - no file written"; exit 1 }
        $f = Get-Item $out
        # A backup nobody checked is not a backup. Prove it parses and count what is inside.
        $sig = [System.IO.File]::ReadAllBytes($f.FullName)[0..2]
        if ($sig[0] -eq 0xEF -and $sig[1] -eq 0xBB -and $sig[2] -eq 0xBF) {
            Write-Output "BACKUP CORRUPT - BOM at the start, the redirect re-encoded it. DO NOT TRUST."
            exit 1
        }
        $entries = & tar tf $out 2>$null
        $count = ($entries | Select-String "project.json").Count
        Write-Output ("wrote {0} ({1:N0} bytes)" -f $out, $f.Length)
        if ($count -lt 1) {
            Write-Output "BACKUP CORRUPT - tar found no project.json. DO NOT TRUST."
            exit 1
        }
        Write-Output "VERIFIED: $count project file(s) inside, archive parses cleanly."
    }

    "restore" {
        # Push a verified backup back onto a device. Deliberately NOT automatic - read the
        # tar listing first and be sure it is the one you want.
        $in = $Rest[0]
        if (-not (Test-Path $in)) { Write-Output "no such file: $in"; exit 1 }
        Assert-Holder
        $s = Get-Serial
        if ($null -eq $s) { Write-Output "no device attached"; exit 1 }
        & $adb -s $s push $in /data/local/tmp/restore.tar | Out-Null
        & $adb -s $s shell "run-as $pkg tar x -f /data/local/tmp/restore.tar"
        & $adb -s $s shell "rm -f /data/local/tmp/restore.tar"
        Write-Output "--- projects after restore ---"
        & $adb -s $s shell "run-as $pkg ls files/faditor/projects"
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
        Get-Content $PSCommandPath | Select-Object -Skip 1 -First 31 | ForEach-Object { $_ -replace '^# ?', '' }
    }
}
