# FadCam/Faditor — Device Control Runbook (how to see & drive the phone over adb)

**Audience:** any AI/coding agent (Claude, Copilot, Cursor, etc.) working on this app that needs to
**install/update the app, look at what's on the phone, and interact with the app to troubleshoot** — even when
nothing but the launcher home screen is showing. This is platform-agnostic: it's all plain `adb` shell
commands. If you can run a terminal, you can do everything here. **Read this top-to-bottom once; then keep it
open as a reference.**

Golden rule: **you cannot see the screen unless you take a screenshot, and you cannot tap unless you compute
coordinates from that screenshot.** There is no accessibility tree on these devices (`uiautomator dump`
returns a null root — see Troubleshooting). So the core loop is always: **screenshot → read pixels → tap by
coordinate → screenshot again to confirm.**

---

## 0. Facts you need (this project)
- **Debug package (what you install/test):** `com.fadcam.beta`  (release is `com.fadcam`; you almost always
  want `.beta`).
- **Launcher activity:** `com.fadcam.SplashActivity` (this is the ONLY launchable entry point; it routes to
  `MainActivity`). The video editor `FaditorEditorActivity` is **not exported** — you cannot `am start` it
  directly; you must navigate to it through the UI.
- **Gradle flavor/buildtype:** flavor `default`, type `debug` → tasks `assembleDefaultDebug` /
  `app:installDefaultDebug`. minSdk 24.
- **Installable APK (after a build):** `app/build/outputs/apk/default/debug/app-default-universal-debug.apk`
  (also per-ABI `app-default-arm64-v8a-debug.apk`; **use the `universal` one** for `adb install` so you don't
  have to match the device ABI).
- **Build log:** `build.log` at the project root, **UTF-16 encoded** — read it with
  `tr -d '\000' < build.log | tail -n 40` (the `tr` strips the null bytes so normal tools can read it).
- **Known devices:** work phone `REAL_SERIAL` (SM-N986U, screen **1440×3088**); backup `SANDBOX_SERIAL`
  (SM-N960U, **1440×2960**). Only one is usually plugged in. Screenshots come back at full device resolution,
  so all tap coordinates below are in **device pixels**, not dp.

---

## 1. Find adb and confirm the device is alive (do this FIRST, every session)

`adb` is **not on PATH** in this environment. It lives at:
```
C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe
```

Pick the form for your shell and put it at the front of your commands:

- **Git-Bash / Bash tool:**
  ```bash
  export PATH="$PATH:/c/Users/JoyRaptor/AppData/Local/Android/Sdk/platform-tools"
  adb devices -l
  ```
- **PowerShell:**
  ```powershell
  $adb = "C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe"
  & $adb devices -l
  ```

Expected healthy output: a serial followed by `device` (e.g. `REAL_SERIAL   device ...`).
- `unauthorized` → unlock the phone and tap **Allow USB debugging** on its screen.
- `offline` or nothing listed → see Troubleshooting (kill-server/start-server).

If more than one device is connected, target one explicitly with `-s <serial>` on every command, e.g.
`adb -s REAL_SERIAL shell ...`. The examples below assume exactly one device; add `-s` if needed.

---

## 2. Install / update the app

### 2a. The normal path (the user runs a file-watcher)
In day-to-day work the **user runs a PowerShell watcher** that rebuilds and reinstalls automatically every time
a source file is saved. So usually **you don't build or install at all** — you just edit code and wait. After
saving, confirm the rebuild finished by checking the build log:
```bash
tr -d '\000' < build.log | tail -n 40
```
The watcher rebuilds **mid-edit**, so intermediate `BUILD FAILED` lines are normal. **Only trust the FINAL
tail line.** Wait until it says `BUILD SUCCESSFUL`, then the new APK is already on the phone — proceed to
verify. **Do not start your own Gradle build while the watcher is running** (two builds collide on the lock).

### 2b. Manual build + install (no watcher, or you need a clean install)
> NOTE: In some sandboxes Gradle fails with a network/loopback error when run by the agent. If
> `./gradlew` errors that way, ask the user to run the build/watcher, or just `adb install` the last-built APK
> (it's still on disk at the path in §0).

```bash
# Build + install + launch in one go (flavor=default, type=debug):
./gradlew app:installDefaultDebug

# OR build the APK then push it yourself (works even if installDefaultDebug can't reach the device):
./gradlew assembleDefaultDebug
adb install -r -d app/build/outputs/apk/default/debug/app-default-universal-debug.apk
#   -r = reinstall/keep data (update in place)   -d = allow version downgrade
```
If `adb install` fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` (signature/version mismatch), uninstall first
(this wipes the app's data/projects — only do it if you must):
```bash
adb uninstall com.fadcam.beta
adb install app/build/outputs/apk/default/debug/app-default-universal-debug.apk
```

### 2c. Confirm what's actually installed
```bash
adb shell dumpsys package com.fadcam.beta | grep -E "versionName|lastUpdateTime"
```

---

## 3. Wake the phone and get to the app (even from a cold launcher home screen)

Do these in order; each is safe to run blind.
```bash
# 1) Wake the screen (no-op if already on)
adb shell input keyevent KEYCODE_WAKEUP

# 2) Dismiss the lock screen / ambient. Swipe up from bottom-center.
#    (coords are for 1440x3088; scale Y to your device height if different)
adb shell input swipe 720 2800 720 1200 200

# 3) If there's a PIN/lockscreen the user must unlock it physically — adb can't.

# 4) Launch the app fresh (brings com.fadcam.beta to the foreground from anywhere):
adb shell am start -n com.fadcam.beta/com.fadcam.SplashActivity
```
`am start` is the reliable way in — it works whether the app is closed, backgrounded, or you're staring at the
launcher. You do **not** need to find the app icon on the home screen. (If you ever do want the icon, that's
fragile — prefer `am start`.)

> ### ⚠ NEVER launch with `adb shell monkey`. It silently unlocks screen rotation.
> `monkey` calls `IWindowManager.thawRotation()` on teardown, which writes
> `accelerometer_rotation = 1` — i.e. it turns the user's rotation LOCK OFF, on their phone, as a
> side effect of launching an app. There is a standing device rule that treats that setting going
> to 1 as "a human may have picked the phone up, STOP", so using `monkey` **manufactures the exact
> alarm that is supposed to protect the user.** It cost at least three sessions of device work
> before it was diagnosed on 2026-07-29 (LEDGER §5): the flip was blamed on the user, then on a
> package event, then on Tasker, and it was the launch command every time.
> Proved by a crossed 2×2 — `monkey` flips it for FadCam **and** for Settings; `am start` flips it
> for neither. Recognise it in logcat as `WindowManagerService.thawRotation` via
> `IWindowManager$Stub.onTransact`, a second or two after launch.
> `monkey` is attractive because it needs no activity name. Use `am start -n` and pay the extra
> word.

To force-restart cleanly (clears a stuck state):
```bash
adb shell am force-stop com.fadcam.beta
adb shell am start -n com.fadcam.beta/com.fadcam.SplashActivity
```

---

## 4. LOOK at the phone (you are blind until you do this)

### 4a. Screenshot (your eyes)
```bash
# Pull a PNG to a temp file, then open/Read it. exec-out streams binary straight to a file:
adb exec-out screencap -p > "/c/Users/JoyRaptor/AppData/Local/Temp/screen.png"
```
Then **Read that PNG** (the Read tool renders images). The image is full device resolution (e.g. 1440×3088), so
every pixel you see maps 1:1 to a tap coordinate.
- ⚠️ Do **not** use `adb shell screencap -p /sdcard/x.png` and then `cat` it through the shell on Windows — the
  shell mangles binary/CRLF. `exec-out ... > file.png` is the clean way.
- To crop/zoom a region for detail, read the PNG with an image tool (PIL/Pillow) and inspect the area you care
  about; coordinates are unchanged.

### 4b. What activity/screen is in front (cheap, no screenshot)
```bash
adb shell dumpsys activity activities | grep -E "ResumedActivity|topResumedActivity"
```
Tells you e.g. `FaditorEditorActivity` vs `MainActivity` vs the launcher — useful to confirm a tap actually
navigated.

### 4c. App logs (crashes, your own FLog lines, exceptions)
```bash
adb logcat -d -t 400 | grep -iE "fadcam|faditor|AndroidRuntime|FATAL"
# live tail while you reproduce a bug:
adb logcat -c            # clear first
adb logcat | grep -iE "fadcam|faditor|AndroidRuntime"
```
`AndroidRuntime`/`FATAL EXCEPTION` lines are crashes with a stack trace — read the trace to find the file/line.

### 4d. Audio engine state (you cannot HEAR audio over adb)
To verify playback/pause of ExoPlayer + MediaPlayers, inspect the audio service instead of listening:
```bash
PID=$(adb shell pidof com.fadcam.beta)
adb shell dumpsys audio | grep "/$PID"
```
Each player line shows `state:started` / `state:paused` — that's how you confirm e.g. "music stopped when
video stopped" without ears.

---

## 5. INTERACT with the app (drive it like a finger)

All coordinates are device pixels read off your latest screenshot. **After every interaction, take another
screenshot (or check §4b) to confirm what happened — never assume.**

```bash
# Tap
adb shell input tap <x> <y>

# Swipe / scroll / drag  (last number = duration ms; longer = slower/more controllable)
adb shell input swipe <x1> <y1> <x2> <y2> <ms>

# Type text into the focused field (use %s for spaces, escape special chars)
adb shell input text "hello%sworld"

# Hardware keys
adb shell input keyevent KEYCODE_BACK        # back
adb shell input keyevent KEYCODE_ENTER       # confirm
adb shell input keyevent KEYCODE_DEL         # backspace
adb shell input keyevent KEYCODE_WAKEUP      # wake
```

### 5a. Capturing a transient overlay (HUD that only shows mid-gesture)
Some UI (e.g. a volume HUD, a drag fader) only appears **while your finger is down**. A normal `swipe` finishes
too fast to screenshot. Use low-level motion events to hold the gesture, screenshot mid-hold, then release:
```bash
adb shell input motionevent DOWN 720 1500
adb shell input motionevent MOVE 720 1200
adb exec-out screencap -p > "/c/Users/JoyRaptor/AppData/Local/Temp/mid.png"   # capture while held
adb shell input motionevent UP   720 1200
```

### 5b. Long-press (arms keyframe modes, opens delete confirms, etc.)
A long-press is just a `swipe` that doesn't move, held ~600ms+:
```bash
adb shell input swipe 720 2560 720 2560 700
```

---

## 6. App-specific navigation (Faditor video editor)

Coordinates below are for the **work phone (1440×3088)**. Re-screenshot and adjust for other devices — never
hard-trust coordinates, the layout shifts with project content.

1. **Open the editor:** from the app's main screen, tap the **Faditor** bottom-nav icon ~`(840, 2915)`.
2. **"Welcome Back / Continue" is BROKEN** — if you see it, tap **New Project** to clear it, or `am start` the
   app fresh. Don't tap Continue.
3. **Open a project:** tap a project row ~`(720, 1150)`.
4. **Play / pause:** ~`(720, 2050)`.
5. **The bottom tool row (~y2950) SCROLLS horizontally.** Swipe LEFT *on that row* to reach
   Split / Delete / Duplicate / Add / **Text** / Visualizer, and further for Transcript / Transitions / Volume.
   - ⚠️ Swipe the tool row at **~y2950**, NOT at ~y2560 — y2560 is the timeline and swiping there scrubs the
     playhead instead of scrolling the tools.
6. **Timeline layer rows** (audio lanes, VISUALIZER, CAPTION) sit just **below** the main clip track; tap a row
   to select/open it, long-press to delete.
7. **New dialogs must be built with bare `new MaterialAlertDialogBuilder(this)`** (no theme-overlay argument) —
   otherwise they crash with `InflateException` because the editor theme lacks the M3 dialog attributes. (This
   is a coding note for when you add UI, not a navigation step.)

**Backup phone (1440×2960):** Faditor icon ~`(835, 2755)`; project ~`(720, 1170)`; Volume tool ~`(375, 2895)`.

---

## 7. Headless edits via ADB (advanced — apply AI EditScripts without the UI)
The app exposes an **exported** headless activity for applying validated EditScripts from the command line:
```bash
adb shell am start -a com.fadcam.APPLY_EDITS -n com.fadcam.beta/com.fadcam.ui.faditor.ai.ApplyEditsActivity \
  --es payload '<edit-script-json>'
```
This is how the AI mutates a project programmatically (it validates the script before applying). Use it only if
you understand the EditScript schema (see `ChatAssistantActivity` / `EditScriptApplier`); otherwise drive the
UI as in §5–6.

---

## 7b. Read the LIVE project state (ground truth for diagnosis — do this before guessing)
The debug build is debuggable, and the app is installed under **user 0** (NOT Secure Folder — a
`user 150 / Secure Folder` entry may show `installed=false`; ignore it). So `run-as` works and you can read
the exact project JSON the app is editing — clips, transcripts, keyframes, caption flags/positions, transitions:
```bash
adb -s REAL_SERIAL shell run-as com.fadcam.beta ls -t files/faditor/projects   # newest dir = active project
adb -s REAL_SERIAL shell run-as com.fadcam.beta cat files/faditor/projects/<id>/project.json > proj.json
```
Then parse `timeline.clips[]` / `timeline.audioClips[]` / `timeline.transitions[]`. Key JSON fields:
`inPointMs/outPointMs/sourceDurationMs`, `loopMode/loopAfterMs`, `captionsEnabled/captionStyleId/
captionCenterX,Y/captionSizeFraction/activeTranscript`, `volumeKeyframes(t,v)`, `opacityKeyframes(t,o)`,
`captionStyleKeyframes(t,s)` (`s:"hidden"` hides), `transcripts[].words[](t,s,e,x=struck,b=forceBreak)`.
This is how you confirm what's actually on the timeline instead of guessing from the screen.
NOTE: audio-clip `captionSizeFraction` is NOT persisted (uses the AudioClip default), unlike video clips.

## 7c. Probe a source clip's resolution / color (canvas-size & "washed out" bugs)
Export composes at a 9:16 canvas inferred from the first decodable clip; a clip whose native res/aspect or
color metadata differs explains mis-sized overlays and desaturated output. `ffprobe` is on the host:
```bash
FF="C:/Users/JoyRaptor/AppData/Local/Microsoft/WinGet/Packages/Gyan.FFmpeg_*/ffmpeg-*/bin/ffprobe.exe"
adb -s REAL_SERIAL pull "/storage/0000-0000/+Projects/Fadcam video assets/<file>.mp4" clip.mp4
"$FF" -v error -select_streams v:0 -show_entries stream=width,height,pix_fmt,color_space,color_transfer,color_primaries -of default=noprint_wrappers=1 clip.mp4
```
(`color_*=unknown` on a clip is a red flag for export color shifts vs the ExoPlayer preview.)

## 7d. Capture an EXPORT failure (errors scroll off the noisy main buffer)
Filter to the app pid and save to a file, THEN export, THEN grep:
```bash
PID=$(adb -s REAL_SERIAL shell pidof com.fadcam.beta)
adb -s REAL_SERIAL logcat --pid=$PID -v threadtime > export_cap.txt   # run, then tap Export, then stop
grep -nE "ExportManager|CompositeExportOverlay|GlTransition|ExoPlaybackException|ExportException|Source error|overlay summary" export_cap.txt
```
The real Media3 failure shows as `ExoPlaybackException: Source error` / `UnrecognizedInputFormatException` /
`ExportException: Video frame processing error` — NOT as any "asset loading" string. `CompositeExportOverlay`'s
`getBitmap ... canvas=WxH` line tells you each clip's true frame size; `Export overlay summary` prints per-clip
caption/text/waveform frame counts.

## 7e. adb reliability
The **Bash tool's** adb intermittently prints `no devices/emulators found` mid-script. The **PowerShell tool**
with the full adb path and `-s REAL_SERIAL` is reliable; prefer it for adb. `adb kill-server; adb start-server`
recovers a wedged server.

## 7f. Building from inside the agent
Gradle fails here with `Unable to establish loopback connection` (client↔daemon socket) — this persists even
with the sandbox disabled, `--no-daemon`, matched jvmargs, and `preferIPv4Stack`. **Do not rely on the agent
building.** Use the user's file-watcher (§2a): edit source, then poll `build.log` for the FINAL
`BUILD SUCCESSFUL`. If the watcher is off, ask the user to start it (its log is `build.log`, UTF-16). Until it's
running, your source edits are NOT on the phone.

## 8. Troubleshooting (the things that get agents stuck)

| Symptom | Fix |
|---|---|
| `adb` not found | It's not on PATH — use the full path in §1. |
| Device `offline` / not listed | `adb kill-server && adb start-server`, then replug USB and re-run `adb devices`. |
| Device `unauthorized` | Unlock phone, tap **Allow USB debugging** (check "always") on its screen. |
| `uiautomator dump` returns null root / "could not get hierarchy" | Expected on these devices. **Don't rely on it.** Navigate by screenshot coordinates instead (§4a). |
| Screenshot file is corrupt / 0 bytes | You used `shell screencap` + `cat`. Use `adb exec-out screencap -p > file.png` (§4a). |
| Build log looks like garbage / null chars | It's UTF-16. Read with `tr -d '\000' < build.log | tail -n 40`. |
| Saw `BUILD FAILED` but app seems fine | The watcher rebuilds mid-edit; that line was an intermediate save. Only the FINAL tail line matters — wait for `BUILD SUCCESSFUL`. |
| Screen is black / asleep / ambient charging clock | `input keyevent KEYCODE_WAKEUP` then swipe up (§3). |
| App is in landscape and coords are wrong | Force portrait: `adb shell settings put system user_rotation 0`. |
| Can't get into the editor (stuck on Welcome Back) | Tap **New Project**, or `am force-stop` + `am start` fresh (§3). |
| Tapped but nothing changed | Re-screenshot; your coordinate was off (layout shifts with content). Recompute from the fresh PNG. Confirm the front activity with §4b. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Signature/version mismatch — `adb uninstall com.fadcam.beta` then install (wipes app data). |
| Can't verify audio | You can't hear it — inspect `dumpsys audio` player states (§4d). |
| Gradle errors with a network/loopback error when YOU run it | The sandbox blocks it; let the user's watcher build, or just `adb install` the last APK (§2b). |

---

## 9. The autonomous "build → verify on device" loop (how this all fits together)
For each change you make:
1. **Edit source** (keep the tree compiling — always-green).
2. **Wait for the build** — `tr -d '\000' < build.log | tail -n 40` until the FINAL line is `BUILD SUCCESSFUL`
   (the watcher already installed it). If no watcher, build+install per §2b.
3. **Get on screen** — `am start` the app, wake/unlock if needed (§3), navigate to the feature (§6).
4. **Look** — screenshot (§4a), and for non-visual state use logcat (§4c) / dumpsys audio (§4d) / front
   activity (§4b).
5. **Interact** — tap/swipe/type to exercise the change (§5), screenshot again to confirm the result.
6. **Diagnose failures** from logcat stack traces; fix; repeat from step 1.
7. **Report what you actually observed** (with the screenshot/log evidence), not what you assume happened.

That's the whole game: **install → wake → `am start` → screenshot → tap by coordinate → screenshot → confirm.**
Everything else is detail. When in doubt, take another screenshot.
