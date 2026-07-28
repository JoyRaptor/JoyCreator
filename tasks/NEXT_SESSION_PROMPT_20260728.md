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
- `7b3edff` then `bd2bd58` — **§3d cut smoothness: built, measured, and DELETED** on the user's
  call. The measurement is the keeper; see LEDGER §3d.
- `95dc7e2` — **§3g step 1: `CaptionAnimator`, the one authority for text animation.** Preview
  and export shared an easing curve but not a CLOCK. See `SPEC_TEXT_ANIMATION.md`.
- Plus `docs:` commits keeping the LEDGER honest.

## YOUR FIRST JOB — continue §3g TEXT ANIMATION. Read its spec first.

**`FadCam/tasks/SPEC_TEXT_ANIMATION.md` is current and detailed. Read it before touching code.**
It has a "Where this actually is" section at the top separating what is DONE from what is not,
and a "Suggested next steps" section at the bottom.

Short version: **step 1 (unification) is done and in the build** (`95dc7e2`). The three shipping
caption animations were implemented twice — preview on a wall-clock `ValueAnimator`, export on
media time — and now both call one `CaptionAnimator`. The presets, the picker UI, the tape
handles and per-glyph layout are **not** started.

The user's binding decisions from 2026-07-28, all captured in the spec:
- the tape's in/out handles **ARE** the timing, for every preset at every granularity — handles
  at the ends = no animation, handles to the centre = animates in then straight back out;
- four granularities: **LETTER / WORD / SENTENCE / BLOCK**, orthogonal to the preset;
- LTR-only v1; zones live on the item (mirror `GeneratedSource.freezeStartMs`); time-based, not
  frame-based.

Two things still need the user: **which of the ten presets are v1**, and the **composition order**
against existing keyframes.

**Before building on the unification, verify it held** — same `(word, sourceMs)` must give the
same transform in preview and export. It was smoke-tested (opens, plays, renders, no exceptions)
but NOT frame-diffed, and the easing formulas were identical before and after, so what changed is
*when* the value is sampled, not the curve. An A/B export frame-diff on a captioned clip with a
**speed multiplier** is the sharpest test, since speed is where the two clocks disagreed most.

## AFTER §3g — two decisions belong to the user

**§3d is CLOSED — measured, then deleted on the user's call (`bd2bd58`). Do not rebuild it.**
The number is in LEDGER §3d so nobody re-derives it: 0 of 7 real window starts keyframe-aligned,
keyframes ~1.0s apart, so a millisecond-precision trim has ~1-in-1000 odds. User: *"170 nearly
useless lines? … lets not bloat the codebase."*

**§3a masking/chroma-key — SCOPE IS DECIDED, design is drafted, build not started.** The four
binding answers and the proposed interaction are in LEDGER §3a. Summary: live preview of key AND
matte is in v1; PiP **and image** overlays only (text/sprites wait); capsule corners are fine (no
ellipse); a soft-edges/feather slider **is** in v1 (engine work — `MaskPathBuilder:22-23` records
the method: render the mask to an ALPHA_8 bitmap and `DST_OUT` it, because `clipPath` is not
antialiased). Fix the two preview/export divergences listed there first, including the newly
found one where **matte-peer AUDIO still exports** while its picture is hidden.

Remaining order: **§3g (in progress)** → §3a (masking UI) → §3e (two-stage AI reorder).

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
