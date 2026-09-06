# 🔵 WIRELESS ADB — how to reach the phone WITHOUT USB

> **Agents: if `adb devices` shows nothing, DO NOT assume the phone is unreachable or ask the
> user to plug in USB. The phone is already PAIRED for wireless debugging. Reconnecting is one
> command.** Full device/interaction details live in `tasks/DEVICE_CONTROL_RUNBOOK.md`.

## Facts
- `adb` is **not on PATH**: `C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- Device: **REAL_SERIAL** (SM-N986U, Android 13, screen 1440×3088)
- Already paired with this PC — but pairing persists while the **connection does not**.
  The IP:port changes whenever wireless debugging restarts, so re-discover it each session.

## Reconnect (PowerShell tool is reliable for adb — see runbook §7e)
```powershell
$adb = "C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb mdns services                     # discovers phone on the LAN, e.g.:
# adb-REAL_SERIAL-Q8aCqY  _adb-tls-connect._tcp  192.168.1.151:35445
& $adb connect <ip-from-above>:<port>    # no pairing code needed — pairing is remembered
& $adb devices -l                        # must list `192.168.1.x:port  device`
```
Then run every command with `-s 192.168.1.x:<port>` (IP:port IS the serial now — not `REAL_SERIAL`).

## If mDNS finds nothing
1. Phone and PC must be on the same WiFi network.
2. Ask the user to open **Settings > Developer Options > Wireless Debugging** and read the
   `IP address & Port` shown at the top — `adb connect` that directly.
3. Still failing → `adb kill-server; adb start-server`, retry `mdns services`.

## Install / update the app over WiFi
Nothing changes — same commands as the runbook (§2), just with `-s <ip:port>`:
```powershell
& $adb -s 192.168.1.151:<port> install -r -d app/build/outputs/apk/default/debug/app-default-universal-debug.apk
```

*Verified working 2026-08-31 (disconnect → mdns → connect round-trip).*

## Field lessons 2026-08-31 (live session — read before you start)
- **USB is NOT an option**: JoyRaptor's connector is damaged. Wireless is the only path until repaired.
- **The Wireless Debugging toggle turns itself OFF** (observed twice: toast pops, switch flips).
  If discovery comes up empty, ask JoyRaptor to re-check the toggle and STAY on that page — leaving
  the page / locking the screen stops the mDNS advertisement.
- **mDNS serves STALE records** after the phone's listener restarts: the old IP:port stays listed
  (connect → "actively refused (10061)") while a NEW record appears suffixed `(2)`. Parse EVERY
  matching line and try each endpoint; the newest wins.
- **The port changes on every toggle/reboot** (41229 → 46821 → 45703 in one day). Never reuse.
- `adb kill-server` can interrupt the harness mid-command — run kill/start/mdns as separate
  small steps, and retry once if a step dies.
- **The debug install package is `com.fadcam.beta`** (applicationIdSuffix), not `com.fadcam`.
  Verify with `dumpsys package com.fadcam.beta | grep lastUpdate`.
- `pm list packages` throws `SecurityException (user 150)` (Samsung Secure Folder) — use
  `pm list packages --user 0 <filter>`.
- **Read-only app data works via `run-as com.fadcam.beta`**: projects at
  `files/faditor/projects/<id>/project.json`; per-binding style drafts live in
  `shared_prefs/caption_styles.xml` (key `caption_custom_styles_v1`). NEVER write through run-as.
- A 134MB `install -r -d` streams over WiFi in ~30–60s and prints `Success` on completion.
