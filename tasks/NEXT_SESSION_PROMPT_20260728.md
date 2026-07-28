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

## WHAT THE LAST SESSION DID (2026-07-28 evening)

**§3g text animation now RUNS end to end.** Three commits, each with its evidence:

- `5cc34fd` — **presets, granularity and unit splitting land on the one evaluator**, plus a JVM
  harness. The handoff asked for the unification to be verified before building on it: it holds
  BY CONSTRUCTION, because preview (`FaditorEditorActivity:8121`) and export
  (`CompositeExportOverlay:576,590`) compute source time with the same formula and apply the
  speed multiplier on both sides before the animator sees anything. A frame-diff proves one
  sample; what makes them agree everywhere is that the animation is a pure function of ELAPSED
  media time, so that is pinned instead (`CaptionAnimatorTest.clockInvariant`).
- `c7b6359` — **the animation actually runs.** Four fields on `Clip`, round-tripped through
  `ProjectStorage`, driving BOTH renderers. `CaptionPhrases` extracted, because the phrase
  grouping was a second identical-and-independent copy across the same preview/export boundary
  that WAS §3g — and it became load-bearing the moment the phrase span became the animation's
  tape.
- Docs kept current: LEDGER §3g is now a step table with the evidence and the limits.

**131 harness checks, off-device, seconds to run:**
```
javac -nowarn -d tools/jvm-harness/out-caption tools/jvm-harness/stubs/androidx/annotation/*.java tools/jvm-harness/stubs-caption/com/fadcam/ui/faditor/transcript/CaptionStyle.java app/src/main/java/com/fadcam/ui/faditor/transcript/CaptionAnimator.java tools/jvm-harness/CaptionAnimatorTest.java && java -cp tools/jvm-harness/out-caption CaptionAnimatorTest
```

Earlier the same day: `d3e3a63` §2a playhead↔clip mapping (FIXED, re-verified against the code
this session — all four claims hold), `a19ee53` §3b lane mute icon, `7b3edff`+`bd2bd58` §3d built
then deleted, `95dc7e2` §3g step 1.

## UPDATE 2026-07-28 (late) — the §3g AUTHORING UI IS DONE. Two commits.

`dbc6ab0` (carets + picker + drawer row + docs) and `bbfbcf0` (popover UX). The section below is
superseded and kept only for its line-number references. Read `SPEC_TEXT_ANIMATION.md` and
LEDGER §3g for the current state.

**What shipped:** amber `▶` `◀` tape carets on a captioned clip while the caption drawer is open;
a preset grid whose tiles draw their thumbnails from `presetTransform` itself, filtered on
`Preset.implemented`; a granularity row; an "A in motion" entry point; undo on all three. Both
formerly-open questions are answered in the spec (v1 = the six implemented presets; composition
order was undefined in prose but already identical in both renderers, and is now written down).

**YOUR FIRST JOB IS NOW THE EVIDENCE, not more building.** Only one thing is unproven:

1. **Capture the large-amplitude device frame** (LEDGER §3g step 6). No device was attached when
   the UI landed — `adb devices` was empty — so *nothing about this UI has been seen by a human
   or a phone.* It is verified by build and by 145 harness checks, and that is all. This is now
   easy: open the caption drawer, drag the `▶` caret well in, and the entrance is a known span at
   a known place instead of something to hunt for.
2. **Walk the UI once** for what a harness cannot see: are the amber carets grabbable with a real
   thumb without stealing edge grabs from the trim handles; do the tiles read as distinct at
   60dp; does LETTER granularity on a long phrase hold frame rate (it is the cost centre —
   per-glyph layout in BOTH paths).
3. Then §3g moves to LEDGER §1. Not before.

**Do not guess:** which of the five unimplemented presets to build next, and whether GHOST should
get its blur (a real decision with a per-frame performance price — see the spec's "KNOWN GAP").

**Build note learned here:** gradle printed `compileDefaultDebugJavaWithJavac UP-TO-DATE` on runs
that had in fact just compiled. The console is not the signal — compare the `.class` and `.apk`
mtimes against the source, then dex-scan. Also, piping gradle through `Select-String` returns
exit 255 on a run that succeeded; capture the output to a variable instead.

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
- **THE RE-RUN CAN LIE — paid for on 2026-07-28.** A gradle run failed with
  `Unable to delete directory ...javac/defaultDebug/...classes` (a Windows file lock). The plain
  re-run reported BUILD SUCCESSFUL and packaged a PARTIAL dex set: the APK was missing
  `FadCamApplication` entirely and the app crash-looped with `ClassNotFoundException` before any
  of my code ran. **A dex scan for the symbol you just added does not catch this** — the new
  symbol was present, because the new file compiled fine. Always scan for something that must
  ALWAYS be there (`FadCamApplication`, `FaditorEditorActivity`) as the positive control, and if
  a build ever fails on a file lock, delete `app/build/intermediates/javac/<variant>` and
  `.../dex/<variant>` before believing the next one.
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
