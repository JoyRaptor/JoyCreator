FadCam / Joy Creator — C:\+Projects\Screenrecorder\FadCam, branch joy-creator.
Working dir may be the PARENT, in which case docs are at FadCam/tasks/…

Read FadCam/tasks/LEDGER.md FIRST. It is the single record of what is fixed (with the
evidence that proved it), what is open, and what has been promised. Keep it current —
never delete an entry to shorten the list, strike through and correct instead.

Last good commit: 73967d1. Tree clean, builds, APK installed on the Note 9 and verified.
Sandbox project bb2a9deb restored to md5 eb3d16b8 (byte-identical to its pre-session state).

## WHAT LANDED LAST SESSION

1. `04d959b` — the MATRIX mid-scramble photograph, the gap that had been open. Done better
   than a photograph: MATRIX is deterministic, so `tasks/matrix_predict.py` (written BEFORE
   capturing) predicts the exact string, and each screencap was checked character-for-character
   against the on-screen time chip in the same framebuffer grab. **11/11 matched** — 6 paused,
   5 during real playback, both phrases, entrance and exit, seven ticks. Two frames are the
   built-in control: progress past 1, prediction is the real text, render is the real text.
2. `5d753b0` — **UNSCRAMBLE shipped.** Its blocker note was WRONG: it needed no third channel,
   only a `unitIndex` parameter on `presetTransform`, because at LETTER granularity every glyph
   is already its own unit with its own Transform in both renderers. Harness 192 → 209.
3. `bdbdab3` — ODOMETER designed, not built. See the OPEN DECISION below.
4. `849b63d` — **the auto-rotate alarm was our own `adb shell monkey`**, which calls
   `thawRotation()` on teardown. It cost three sessions and was blamed on the user, then a
   package event, then Tasker. **LAUNCH WITH `am start -n`, NEVER `monkey`.**
5. `73967d1` — **MATRIX now churns in halfwidth katakana + digits** (the user asked). Harness
   209 → 213, proved on device against the predictor.

## OPEN DECISION THAT BELONGS TO THE USER — ODOMETER's scope

ODOMETER is next in the agreed build order but was deliberately NOT started, because it forks:
- **(a) Captions-only, behind a new `allowedPresets` gate on the picker.** Cheap — the picker
  already takes `allowedGrans` for exactly this reason, so it is one argument away.
- **(b) Give text boxes a canvas renderer in preview first.** Their preview is a `TextView`
  (`TextOverlayLayer:211`) — one view, one string, so it cannot draw two clipped glyph rows
  while the export could, which is the preview/export divergence this area exists to prevent.
  **This is NOT new work: it is the same item already recorded as the reason text boxes are
  BLOCK-only**, so doing it unlocks ODOMETER-on-text-boxes AND WORD/LETTER granularity together.
- **(c) Defer ODOMETER; take MASK_WIPE or NEON_FLICKER instead.**

The user was asked and said *"skip odometer for now i dont know how to answer"*. **So do NOT
start ODOMETER.** Take MASK_WIPE next unless the user says otherwise. Recommendation on record
if they revisit it: (a) now, (b) later as its own funded piece.

## WHAT TO DO NEXT, IF NOT TOLD OTHERWISE

**MASK_WIPE** — "needs a per-unit clip rect". Re-derive that against the drawing loops before
believing it (UNSCRAMBLE's note was materially wrong; ODOMETER's was right). Both caption
renderers already have `x`/`baseY`/`w` at the draw site and already `save()`/`restore()` per
unit, so a clip rect is likely cheap on the caption path — and it will hit the SAME text-box
`TextView` wall as ODOMETER, so check that first and say so early rather than discovering it
at the end. NEON_FLICKER (stroke/glow) is the one preset that may not touch that wall at all,
since it modulates paint rather than geometry — consider it if MASK_WIPE forks the same way.

## DECISIONS THAT BELONG TO THE USER — do not guess
 - Whether GHOST should get its blur. `Transform.blurPx` is computed and consumed by NO
   renderer; the field's javadoc explains why blurring the preview alone would do nothing while
   export really would. Do not implement without asking.
 - Whether emphasis should be suppressed during an entrance rather than multiplied in.
 - Retrigger-on-value-change: a timer wants a pop on each TICK — an EVENT, not a function of
   elapsed time. `CaptionAnimator` has no notion of it.
 - ODOMETER's scope (above).

## INSTRUMENTS LEFT IN THE BUILD ON PURPOSE — do not remove
 - SEEKRANGE, ENDEDNET, KFALIGN/KFPROBE, PHDIAG, ROWGESTURE_DEBUG — see LEDGER.
 - `tasks/matrix_predict.py` — the predictor that closed the MATRIX proof. Reusable for any
   deterministic-channel preset. Read its docstring: it names the two things that will give you
   a wrong prediction.

## HOW TO WORK HERE — rules each paid for in a bug
 - Prove it, never assert it. Pair every check with a positive control.
 - **A FAILING `javac` LEAVES THE OLD .class FILES AND THE HARNESS THEN REPORTS A CONFIDENT PASS
   ON STALE BYTECODE.** This happened last session: "209 passed, 0 failed" for a change that had
   not compiled. Delete the output dir or check the compiler exit before believing the runner.
 - A green build / gradle UP-TO-DATE proves nothing. `javap -constants` or a dex scan on the
   actual artifact is the truth. Gradle reported UP-TO-DATE twice last session on real changes.
 - THE STRONGEST FRESHNESS CONTROL IS A SYMBOL YOU DELETED — but verify a "still present" hit
   before concluding staleness (a substring collision once faked a stale dex).
 - **CHECK YOUR POSITIVE CONTROL FIRST.** A dex scan reported 0 hits for everything including
   `FadCamApplication`; the build was fine and the scan was broken (a Windows drive-letter colon
   ate an awk field split). A scan whose control also reads zero is a broken scan.
 - Two occurrences of a CONFOUNDED observation are not two pieces of evidence, they are the same
   one twice. Vary the factors independently — that is what finally caught the `monkey` bug.
 - Literal non-ASCII in a Java CHAR/STRING literal breaks the harness build (windows-1252 vs
   UTF-8 files). Build such constants from code points. Comments are fine — only literals matter.
 - Do not pipe gradle through Select-String (exit 255 on success). Capture to a variable.
 - Screenshots via Bash (adb exec-out screencap -p > f.png), never PowerShell >.
 - export MSYS_NO_PATHCONV=1 in Git Bash for /sdcard paths — **but note it also stops `/tmp`
   being translated for git.exe, so `git commit -F /tmp/msg` fails. Use a Windows path.**
 - When looping over project ids with adb, redirect stdin (< /dev/null).
 - To edit a project.json directly: adb push to /sdcard, then
   adb shell "cat /sdcard/f.json | run-as com.fadcam.beta sh -c 'cat > files/.../project.json'"
   — run-as cannot read /sdcard directly. Force-stop the app first. Strip CR from run-as cat.
 - project.json.bak is the APP's own backup. Do not "clean it up".
 - All three audioClips ids regenerate on EVERY save — background noise in any diff.
 - adb: C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe
   Gradle needs $env:TEMP='C:\Users\JoyRaptor\gtmp'; $env:TMP=$env:TEMP
   Build task is **assembleDefaultDebug** (there is no assembleBetaDebug).
 - Commit messages: git commit -F <file>, written WITHOUT a BOM.
 - Strings are HARDCODED with // TODO(strings) — frozen behind the rebrand, don't "fix" it.

## DEVICE RULES — NON-NEGOTIABLE
 - The Note 9 <note9-serial> is the sandbox and must be the ONLY phone attached.
 - If the Note 20 <note20-serial> appears, STOP all device work — it holds the user's real
   45-minute project.
 - **LAUNCH WITH `adb shell am start -n com.fadcam.beta/com.fadcam.SplashActivity`. NEVER
   `monkey` — it calls `thawRotation()` and turns the user's rotation lock OFF**, manufacturing
   the very "a human may be on the phone" alarm the next rule exists for. See LEDGER §5.
 - The human tripwire still stands, and is meaningful again now the false trigger is gone: if
   `accelerometer_rotation` goes to 1 when YOU did not cause it, or `dumpsys power`'s
   `mLastUserActivityTime` shows input you did not inject, STOP and ask.
 - Installing the app kills whatever session is running — tell the user.
 - If adb drops the device: adb kill-server; adb start-server.

Work as autonomously as you can. Prove things rather than asserting them. Update the
ledger as you go — it is the only thing that survives between sessions.
