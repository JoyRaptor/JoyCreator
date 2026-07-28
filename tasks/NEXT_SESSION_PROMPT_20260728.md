# NEXT SESSION — paste everything below the rule into a fresh conversation

---

FadCam / Joy Creator — `C:\+Projects\Screenrecorder\FadCam`, branch `joy-creator`.
Note the working directory is the PARENT (`C:\+Projects\Screenrecorder`), so task docs are at
`FadCam/tasks/…`, not `tasks/…`.

**Read `FadCam/tasks/LEDGER.md` FIRST.** It is the single record of what is fixed (with the
evidence that proved it), what is open, and what has been promised. It exists because a whole
feature — masking/chroma-key — was built, shipped as an engine, and then LOST for weeks because
no checklist item was ever left unticked. Keep it current: when something lands, move it to §1
with its proof; never delete an entry to shorten the list.

## WHAT THE LAST SESSION DID (2026-07-28 afternoon)

Four code commits, each measured on the Note 9 before being believed:

- `d3e3a63` — **§2a, the playhead↔clip mapping bug, FIXED.** Root cause: the drag computed its
  seek against the segment under the playhead but handed it to the player holding the clip the
  drag STARTED on, because `selectedClipIndex` was frozen for the whole gesture. 40 out-of-range
  seeks → 0 (two runs). Also fixed `effectiveTrimEnd()` (tested `Long.MIN_VALUE`, but media3
  reports `C.TIME_UNSET` = MIN_VALUE+1 → the window collapsed to 0 and every seek clamped to the
  clip start), the GL-transition handoff seeking past a short clip B, and added the layer-(ii)
  `ENDED`-with-clips-ahead advance.
- `a19ee53` — **§3b lane mute icon, DONE.** Absent (not greyed) with no audio, real
  `volume_up`/`volume_off` glyphs, flush against the caret, gutter 92dp → 34.4dp, touch box
  enlarged from the 12dp glyph to the full row height.
- `7b3edff` — **§3d cut smoothness, BUILT AND MEASURED. Verdict: almost a waste.** See below.
- Plus `docs:` commits keeping the LEDGER honest.

## YOUR FIRST JOB — two decisions belong to the user

**Decision 1 — §3d, keep or delete?** The hybrid works and is safe, but the measurement says it
buys nothing on a normally-trimmed timeline: 0 of 7 real window starts were keyframe-aligned,
keyframes are ~1.0s apart, and a millisecond-precision trim has ~1-in-1000 odds of hitting one.
Only an in-point of 0 earns the flag. It is ~170 lines (`KeyframeAlignment`) for a benefit that
is zero unless the timeline is assembled from whole clips or the source has a short GOP.
The `KFALIGN` log line reports the hit rate per project, so the user can check their own
projects before deciding. **Do not delete it unilaterally — the user asked for the number, and
the number is now on the table.**

**Decision 2 — §3a masking/chroma-key AUTHORING UI.** The ledger is explicit: **bring a proposed
interaction design to the user BEFORE building.** This feature already failed once by being
technically complete and practically invisible. Do not open an editor on it until that
conversation has happened.

Then the remaining order is §3e (two-stage AI reorder), §3a (masking UI), §3g (text animation).

## INSTRUMENTS LEFT IN THE BUILD ON PURPOSE — do not remove yet

- **`SEEKRANGE`** (`FaditorPlayerManager.seekTo` / `seekInClip`) — logs EVERY clip-relative seek
  against the loaded window's real length. `ok=false` means a position was computed in one
  clip's coordinate space and applied to another; that is the §2a defect returning. The
  `ok=true` lines are the probe's own positive control — if they vanish, the probe went blind,
  which is not the same as the bug being gone.
- **`ENDEDNET`** — fires when the player is parked at ENDED with play still switched on. Its
  trigger is a race that could NOT be manufactured on demand (a poll kept catching READY+playing
  on the way to ENDED, so the ordinary advance handled it); only its recovery action was proved.
  It has since been seen firing on a real park at the last clip. **If this line shows up with
  `next=N/M` where N < M, a park was caught in the wild — capture the surrounding log.**
- **`KFALIGN` / `KFPROBE`** — the §3d hit rate, per project.
- **`PHDIAG`** + the `lastUp:` snapshot — still needed for §2b.

## HOW TO WORK HERE — rules that were each paid for in a bug

- **Prove it, never assert it.** Every fix in LEDGER §1 has a measured before/after.
- **Pair every check with a positive control.** Two instruments lied this session: a raw-bytes
  scan of an APK for a string (dex is COMPRESSED — extract and scan `classes*.dex`), and
  `uiautomator dump`, which fails with "could not get idle state" on an animating screen and
  leaves you reading a STALE `/sdcard/ui.xml`. Delete the file first and check the dump landed.
- **Two data points minimum** before believing a pattern.
- **Fix at the funnel, not the call site.**
- **A green build proves nothing, and neither does gradle.** `BUILD SUCCESSFUL in 2s` with
  `compileJavaWithJavac UP-TO-DATE` happens constantly here; and a first invocation after an edit
  sometimes FAILS on CMake/packaging and succeeds on a plain re-run. Always check the APK's
  mtime against the source's, and dex-scan for a symbol you just added.
- `adb logcat` without `-T` replays the whole ring buffer. Never `logcat -c`.
- Navigate the UI with `uiautomator dump` and match `resource-id`/`text` + bounds. The project
  list reorders every time a project is opened. Screenshots must be captured through the **Bash**
  tool (`adb exec-out screencap -p > f.png`) — PowerShell `>` corrupts binary.
- For a DETERMINISTIC playhead position, use the editor's own "Jump to time" dialog (tap
  `time_current`, MOVE_END, 8x DEL, `input text 0012`, OK). `input swipe` distances are not
  repeatable — they fling — and landing at the timeline end silently turns a play tap into an
  auto-rewind, which is usually not the case under test.
- `adb` is at `C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe`.
  Gradle needs `$env:TEMP='C:\Users\JoyRaptor\gtmp'; $env:TMP=$env:TEMP`.
- Commit messages: `git commit -F <file>`, written WITHOUT a BOM
  (`[System.IO.File]::WriteAllText($p,$m,(New-Object System.Text.UTF8Encoding($false)))`).
  Do NOT rewrite a doc with `Get-Content | Set-Content -Encoding utf8` — it double-encodes every
  em dash in the file.

## USEFUL SANDBOX PROJECTS ON THE NOTE 9

| Project | Why it is useful |
|---|---|
| `AudioExportVerify` (`aeb0517e…`) | 5 clips, 2 transitions → **legacy** (non-gapless) path, an image clip, a 2x clip. The §2a repro project. |
| `bisect B 1x clip0` (`74e36000…`) | 4 video clips, no transitions → **gapless** path. in-points 0/1406/4457/4384. |
| `bisect C long 2x` (`129d8643…`) | 4 clips, gapless, in-points 98/1406/4457/4171 — none keyframe-aligned. |

Project files: `run-as com.fadcam.beta cat files/faditor/projects/<id>/project.json`.

## DEVICE RULES — NON-NEGOTIABLE

- The Note 9 `SANDBOX_SERIAL` is the sandbox and must be the ONLY phone attached.
- **If the Note 20 `REAL_SERIAL` appears, STOP all device work.** It holds the user's real
  45-minute project. Installing force-stops whatever is running.
- Installing the app kills the user's session — tell them, they may be mid-test.
- If adb drops the device mid-run, `adb kill-server; adb start-server` recovers it — and
  **re-check orientation afterwards**, because a rotation invalidates every tapped coordinate
  (`settings put system user_rotation 0`).

## STILL OPEN, LOW URGENCY

The second stranded drag-latch path (§2b) self-heals, so it is invisible, but it fired 5 times
in one testing session. Every capture shows EVERY gesture flag false, which eliminates the whole
original suspect list including pinch-zoom. Keep `PHDIAG` and the `lastUp:` snapshot in the build
until it is closed.
