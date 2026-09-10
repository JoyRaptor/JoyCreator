# NEXT SESSION — paste everything below the rule into a fresh conversation

---

FadCam / Joy Creator — `C:\+Projects\Screenrecorder\FadCam`, branch `joy-creator`.
Note the working directory may be the PARENT (`C:\+Projects\Screenrecorder`), in which case task
docs are at `FadCam/tasks/…`.

**Read `FadCam/tasks/LEDGER.md` FIRST.** It is the single record of what is fixed (with the
evidence that proved it), what is open, and what has been promised. It exists because a whole
feature — masking/chroma-key — was built, shipped as an engine, and then LOST for weeks because
no checklist item was ever left unticked. Keep it current: when something lands, move it to §1
with its proof; never delete an entry to shorten the list.

Work as autonomously as you can. Give me one message confirming you understand the state and
what you intend to do, then get to work. Prove things rather than asserting them.

## WHERE §3g TEXT ANIMATION ACTUALLY IS

**Engine, persistence, preview, export AND the authoring UI are all DONE.** Spec:
`SPEC_TEXT_ANIMATION.md`. Ledger: §3g. Recent commits:

- `2ddd80d` — the authoring UI: amber `▶` `◀` tape carets, the preset grid, granularity row,
  "A in motion" entry point, undo on all three, plus the schema docs.
- `18e7733` — picking a granularity no longer closes the popover.
- `611616c` — handoff pointed at the evidence instead of the finished build.
- Earlier: `afa1f78` (one evaluator, one clock), `8618a46` (presets + harness), `08499cc` (runs
  end to end).

Both formerly-open questions are ANSWERED in the spec — v1 is the six implemented presets
(the picker filters on `Preset.implemented`; the other five return identity), and the composition
order was undefined in prose but already identical in both renderers, and is now written down.

**145 harness checks, off-device, seconds to run:**
```
javac -nowarn -d tools/jvm-harness/out-caption tools/jvm-harness/stubs/androidx/annotation/*.java tools/jvm-harness/stubs-caption/com/fadcam/ui/faditor/transcript/CaptionStyle.java app/src/main/java/com/fadcam/ui/faditor/transcript/CaptionAnimator.java app/src/main/java/com/fadcam/ui/faditor/transcript/CaptionPhrases.java app/src/main/java/com/fadcam/ui/faditor/transcript/Transcript.java app/src/main/java/com/fadcam/ui/faditor/transcript/TranscriptWord.java tools/jvm-harness/CaptionAnimatorTest.java && java -cp tools/jvm-harness/out-caption CaptionAnimatorTest
```

## YOUR FIRST JOB — the EVIDENCE §3g is missing. Do not build more of it first.

`adb devices` was EMPTY for the whole session that built the UI. So **nothing about this UI has
been seen by a human or a phone.** It is verified by build, by dex scan, and by 145 harness
checks, and by nothing else. That is why §3g is still in LEDGER §3 and not §1.

1. **Capture the large-amplitude device frame** (LEDGER §3g step 6). The existing proof — 2901 px
   changed, bounding box exactly the caption text, zero pixels elsewhere — shows the PLUMBING,
   not the look: the sampled frame sat at the zone saturation point where progress is 1 by
   construction. This is now easy: select a captioned clip, open the caption drawer (the carets
   only show while it is open), drag the `▶` caret well in, and the entrance is a known span at a
   known place instead of something to hunt for.
2. **Walk the UI once for what a harness cannot see:**
   - are the amber carets grabbable with a real thumb WITHOUT stealing edge grabs from the trim
     handles? (their hit-test is deliberately tight and runs BEFORE the trim handles, copying the
     slide freeze-caret precedent);
   - do the six preset tiles read as distinct at 60dp?
   - does LETTER granularity on a long phrase hold frame rate? It is the cost centre — per-glyph
     layout and transforms in BOTH the preview and the export path.
3. **Only then move §3g to LEDGER §1**, with the evidence attached.

## AFTER §3g

Remaining order: **§3a (masking/chroma-key UI)** → §3e (two-stage AI reorder).

**§3a — SCOPE IS DECIDED, design drafted, build not started.** The four binding answers and the
proposed interaction are in LEDGER §3a. Summary: live preview of key AND matte is in v1; PiP
**and image** overlays only (text/sprites wait); capsule corners are fine (no ellipse); a
soft-edges/feather slider **is** in v1 — engine work, and `MaskPathBuilder:22-23` records the
method (render the mask to an ALPHA_8 bitmap and `DST_OUT` it, because `clipPath` is not
antialiased). Fix the two preview/export divergences listed there FIRST, including the one where
**matte-peer AUDIO still exports** while its picture is hidden.

**§3d is CLOSED — measured, then deleted on the user's call (`14e07ee`). Do not rebuild it.**

## DECISIONS THAT BELONG TO THE USER — do not guess

- **Which of the five unimplemented presets to build next** (MATRIX, UNSCRAMBLE, ODOMETER,
  MASK_WIPE, NEON_FLICKER). Each names its blocker in `CaptionAnimator.unsupportedReason` and
  returns identity, never an approximation.
- **Whether GHOST should get its blur.** `Transform.blurPx` is computed and consumed by NO
  renderer, so GHOST ships as slide + shrink + fade, and the picker's tile deliberately omits the
  blur to match. The obvious fix is a trap: `BlurMaskFilter` is ignored on a hardware-accelerated
  canvas, so blurring the preview alone does nothing on screen while the export — drawing into a
  `Bitmap`, i.e. software — really would blur. Blurring the preview needs `LAYER_TYPE_SOFTWARE`
  on the overlay, which costs every frame of playback. Full reasoning is on the field's javadoc.
- **Whether the emphasis should be suppressed during an entrance** rather than multiplied into
  it. The ORDER is settled and identical in both paths; this is a question about feel.

## STILL NOT ADDRESSED

**Retrigger-on-value-change.** A timer wants a pop on each TICK, which is an EVENT ("the
displayed string changed"), not a function of elapsed time. `CaptionAnimator` has no notion of it,
and the spec warns it is painful to retrofit and is what makes a timer feel alive rather than
merely correct. Untouched by the animation work.

## INSTRUMENTS LEFT IN THE BUILD ON PURPOSE — do not remove yet

- **`SEEKRANGE`** (`FaditorPlayerManager.seekTo` / `seekInClip`) — logs EVERY clip-relative seek
  against the loaded window's real length. `ok=false` means a position was computed in one clip's
  coordinate space and applied to another; that is the §2a defect returning. The `ok=true` lines
  are the probe's own positive control — if they vanish, the probe went blind, which is not the
  same as the bug being gone.
- **`ENDEDNET`** — fires when the player is parked at ENDED with play still switched on. **If it
  shows up with `next=N/M` where N < M, a park was caught in the wild — capture the surrounding
  log.**
- **`KFALIGN` / `KFPROBE`** — the §3d hit rate, per project.
- **`PHDIAG`** + the `lastUp:` snapshot — still needed for §2b (a stranded drag-latch path that
  self-heals, so it is invisible, but fired 5 times in one session; every capture shows EVERY
  gesture flag false, which eliminates the whole original suspect list including pinch-zoom).

## HOW TO WORK HERE — rules that were each paid for in a bug

- **Prove it, never assert it.** Every fix in LEDGER §1 has a measured before/after.
- **Pair every check with a positive control.** Instruments that have lied here: a raw-bytes scan
  of an APK for a string (dex is COMPRESSED — extract and scan `classes*.dex`), and
  `uiautomator dump`, which fails with "could not get idle state" on an animating screen and
  leaves you reading a STALE `/sdcard/ui.xml`. Delete the file first and check the dump landed.
- **Two data points minimum** before believing a pattern. **Fix at the funnel, not the call site.**
- **A green build proves nothing, and neither does gradle.** Paid for twice:
  - A run that FAILED on a Windows file lock (`Unable to delete directory …javac/defaultDebug…`)
    was followed by a plain re-run reporting BUILD SUCCESSFUL that packaged a PARTIAL dex set —
    the APK was missing `FadCamApplication` entirely and the app crash-looped before any new code
    ran. **A dex scan for the symbol you just added does NOT catch this**, because that symbol
    compiled fine. Always also scan for something that must ALWAYS be there (`FadCamApplication`,
    `FaditorEditorActivity`). If a build ever fails on a file lock, delete
    `app/build/intermediates/javac/<variant>` and `.../dex/<variant>` before believing the next.
  - Gradle printed `compileDefaultDebugJavaWithJavac UP-TO-DATE` on runs that had in fact just
    compiled. **The console is not the signal** — compare `.class` and `.apk` mtimes against the
    source, then dex-scan.
- **Do not pipe gradle through `Select-String`** — it returns exit 255 on a run that succeeded.
  Capture to a variable (`$out = .\gradlew.bat … 2>&1`) and slice that.
- `adb logcat` without `-T` replays the whole ring buffer. Never `logcat -c`.
- Navigate the UI with `uiautomator dump` and match `resource-id`/`text` + bounds. The project
  list reorders every time a project is opened. Screenshots must be captured through the **Bash**
  tool (`adb exec-out screencap -p > f.png`) — PowerShell `>` corrupts binary.
- For a DETERMINISTIC playhead, use the editor's own "Jump to time" dialog (tap `time_current`,
  MOVE_END, 8x DEL, `input text 0012`, OK). `input swipe` distances are not repeatable — they
  fling — and landing at the timeline end silently turns a play tap into an auto-rewind.
- `adb` is at `C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe`.
  Gradle needs `$env:TEMP='C:\Users\JoyRaptor\gtmp'; $env:TMP=$env:TEMP`.
- Commit messages: `git commit -F <file>`, written WITHOUT a BOM
  (`[System.IO.File]::WriteAllText($p,$m,(New-Object System.Text.UTF8Encoding($false)))`).
  Do NOT rewrite a doc with `Get-Content | Set-Content -Encoding utf8` — it double-encodes every
  em dash in the file.
- Strings are HARDCODED with `// TODO(strings)`. The extraction is frozen behind the rebrand
  (`road_map.md:49`). Follow that, do not "fix" it.

## USEFUL SANDBOX PROJECTS ON THE NOTE 9

| Project | Why it is useful |
|---|---|
| `AudioExportVerify` (`aeb0517e…`) | 5 clips, 2 transitions → **legacy** (non-gapless) path, an image clip, a 2x clip. The §2a repro project. |
| `bisect B 1x clip0` (`74e36000…`) | 4 video clips, no transitions → **gapless** path. in-points 0/1406/4457/4384. |
| `bisect C long 2x` (`129d8643…`) | 4 clips, gapless, in-points 98/1406/4457/4171 — none keyframe-aligned. |

For §3g you need a project with a CAPTIONED clip (a transcript with words). Project files:
`run-as com.fadcam.beta cat files/faditor/projects/<id>/project.json`. If you modify a sandbox
project to set up a test, **restore its original bytes afterwards.**

## DEVICE RULES — NON-NEGOTIABLE

- The Note 9 `<note9-serial>` is the sandbox and must be the ONLY phone attached.
- **If the Note 20 `<note20-serial>` appears, STOP all device work.** It holds the user's real
  45-minute project. Installing force-stops whatever is running.
- Installing the app kills the user's session — tell them, they may be mid-test.
- If adb drops the device mid-run, `adb kill-server; adb start-server` recovers it — and
  **re-check orientation afterwards**, because a rotation invalidates every tapped coordinate
  (`settings put system user_rotation 0`).
- **Outstanding from 2026-07-28:** the Note 20 was installed at 15:06 from a build that was
  dex-scanned for the new symbol but NOT for a positive control. That build did not hit a lock
  failure so it is probably fine, but it is unproven. Either the user opens the app there and
  says, or it is reconnected and the verified APK is installed.
