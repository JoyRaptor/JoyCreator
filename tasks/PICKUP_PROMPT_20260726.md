# PICKUP PROMPT — paste this into a fresh session on another account

This file IS the prompt. Paste the fenced block below verbatim. Everything a new session
needs is reachable from it; nothing depends on the previous conversation's context.

The seam is `tasks/HANDOFF_20260726_CONTEXT_SWITCH.md` §0 "CURRENT STATE", which is kept
updated as work lands. Whatever point the previous session was cut off at is recorded
there and in `git log`. Trust those two, not this file's date.

---

```
FadCam / Joy Creator, C:\+Projects\Screenrecorder\FadCam, branch joy-creator.
Continuing an autonomous audit-and-verify run from a previous account that ran out of credits.

FIRST, IN THIS ORDER:
1. Read tasks/HANDOFF_20260726_CONTEXT_SWITCH.md — start at §0 "CURRENT STATE", which is
   kept current as work lands and tells you exactly where the last session stopped.
2. Read tasks/AUDIT_UNFINISHED_20260726.md — the STATUS BOARD near the top says what is
   closed and what is open. That board is your work queue; work down it in its stated order.
3. Set a one-shot wakeup ~5h out pointing back at this same prompt, so work continues while
   the user sleeps. Reschedule it each time before it lapses.

VERIFY BEFORE TRUSTING ANYTHING:
- `adb dumpsys package com.fadcam.beta | grep lastUpdateTime` and compare to `git log` AND
  to the newest mtime under app/src. "Compile-green" without an APK newer than the newest
  source file means nothing — a previous session found ~35 commits that had never once
  compiled because stale javac intermediates made every build fail before type-checking.
  Fix for that: Remove-Item app/build/intermediates/javac/<variant> -Recurse.
- Confirm the build watcher is alive: tail build.log (UTF-16 — PowerShell
  `Get-Content -Encoding Unicode`, not iconv) and look for "Waiting for changes to input
  files". Confirm java processes exist.
- Never `gradlew --rerun-tasks` (corrupts the media3-patched jars).
- Gradle needs $env:TEMP='C:\Users\JoyRaptor\gtmp'; $env:TMP=$env:TEMP.

WORK: keep going down the audit's STATUS BOARD, committing each item SEPARATELY with a
message that says what was proved and how. When the board is exhausted: audit -> build ->
adversarially test what exists.

RULES THAT WERE PAID FOR IN BUGS:
- Do NOT ship data-touching changes on assumptions. Pull the real project file, run the
  algorithm offline in Python, print before/after, THEN write the Java. A "safe" first-wins
  transcript merge would have destroyed part of the user's live transcript; only a
  word-count guard caught it.
- Prove claims with a harness or a simulation, not assertion. Adversarially review your own
  changes before building on them. Always include a POSITIVE CONTROL: a proof that reports
  "no difference" is equally consistent with an instrument that cannot see anything.
- Report honestly. If something is unverified, SAY unverified. Distinguish "my fixture was
  wrong" from "the app is wrong" — three times in this run a confident finding turned out to
  be the harness.

DEVICES:
- Note 9, serial SANDBOX_SERIAL (SM-N960U, Android 10, 1080x2220) is the SANDBOX and must
  be the ONLY phone attached. Its projects are disposable.
- Pristine copies: C:\Users\JoyRaptor\fadcam-safety-2026-07-26\note9-projects\ — restore
  anything you modify and verify by sha256. All 10 were verified identical at the last
  handoff update; re-verify at the end of your stretch.
- The user's REAL phone is the Note 20 (REAL_SERIAL). If it appears, STOP device work and do
  code + offline validation only — the build watcher auto-installs to whatever single device
  is attached, so saving a source file would push a build to their live phone.
- adb is NOT on PATH: C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe
- Never `adb logcat -c` — clearing the buffer once destroyed the only capture of a bug.

REUSABLE VERIFICATION TOOLING already in tasks/ — reuse, do not rebuild:
- export_ab_diff.py     decode two exports and diff PIXELS. --check-asym refuses a fixture
                        symmetric enough to hide a flip.
- export_audio_probe.py fit a source's amplitude inside an export. 1.0 = mixed in once,
                        2.0 = doubled. --expect-absent for a not-present control.
- schema_layer_stamp.py schema-stamp survey + corruption repro + source tripwires.
- getlayers_equiv.py, visible_equiv.py  re-run these after any change they cover, plus
                        TransitionIndexTest / TimerTextTest in tools/jvm-harness.

HARNESS TRAPS (each of these cost real time or produced a wrong answer):
- Capture screenshots with the BASH tool: `adb exec-out screencap -p > f.png`. PowerShell's
  `>` corrupts binaries with a BOM. Same for `adb shell cat` of JSON — read with utf-8-sig.
- Use POWERSHELL for `adb push`: from Bash, /data/local/tmp gets MSYS-mangled to
  "C:/Program Files/Git/data/local/tmp". Or prefix the path with `//`.
- `touch` does NOT trigger Gradle's continuous build (it hashes content, not mtime).
- Foreground `sleep` is blocked by the harness. Wait on a condition with a BACKGROUNDED
  bash `until [ "$APK" -nt "$SRC" ]; do sleep 3; done`.
- An export of a 4-second project takes ~55s, NOT the ~15s the progress dialog suggests, and
  `am force-stop` during muxing truncates the file (no moov atom). Poll for output size
  stability before touching the app.
- Do NOT compare mp4 hashes: separate encodes of the same composite differ in container
  bytes while every frame is identical. Do NOT threshold a cross-encode pixel diff below
  ~40/255 — lossy residual reaches 8/255 on high-contrast edges.
- A correlation probe nearly as long as the export leaves no lag headroom and fits noise.
- Windows Python cannot read /c/... paths; pass C:/... . Guard scripts MUST print the matched
  file count — one "passed" silently over zero files.
- The `Glob` tool is broken on this repo's C:\+Projects path — use Grep or ls.
- FaditorEditorActivity is NOT exported, so `am start` cannot open a project directly. Use
  the UI: Faditor nav icon (627,2108), then project row 1 (538,705) on the Note 9.

DEVICE FIXTURE RECIPE (this is how every proof in this run was made):
  adb shell run-as com.fadcam.beta cp -r <projects>/<real-id> <projects>/<fake-uuid>
  patch the JSON on the host, then
  adb push x.json /data/local/tmp/  (PowerShell)
  adb shell "cat /data/local/tmp/x.json | run-as com.fadcam.beta sh -c 'cat > .../project.json'"
  Set lastModified to now so it sorts to row 1 of the project list. Delete the clone after.
  Projects live at /data/data/com.fadcam.beta/files/faditor/projects/<id>/project.json
  Exports land in /storage/emulated/0/Android/data/com.fadcam.beta/files/FadCam/Faditor/

Leave the tree clean and update tasks/HANDOFF_20260726_CONTEXT_SWITCH.md §0 before any
wakeup lapses, so the next account has the same seam you were handed.
```
