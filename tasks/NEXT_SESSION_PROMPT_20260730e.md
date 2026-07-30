FadCam / Joy Creator — C:\+Projects\Screenrecorder\FadCam, branch joy-creator.
Working dir may be the PARENT, in which case docs are at FadCam/tasks/…

Read FadCam/tasks/LEDGER.md FIRST. It is the single record of what is fixed (with the
evidence that proved it), what is open, and what has been promised. Keep it current —
never delete an entry to shorten the list, strike through and correct instead.

Last good commit: the tip of joy-creator. Tree clean, builds, APK installed on the Note 9.
Sandbox project bb2a9deb restored to md5 eb3d16b8 (byte-identical to its pre-session state).
Harness: 298 passed, 0 failed (unchanged — this session added no engine code).

## WHAT LANDED LAST SESSION — all measurement, no new engine

1. **THE EXPORT IS PROVED.** The oldest gap in §3g. `PICKERTEST` set to RISE + LETTER, exported
   through the real UI, frames pulled with ffmpeg and checked against a CLOSED-FORM prediction
   made first: 0 letters at 0.000s, 2 at 1.318s, 4 at 2.500s, 6 at 4.000s, with the newest letter
   at alpha 0.88 / 0.56 / 0.69. The export shows exactly that, and the preview at 1.318s shows the
   same `PI`. **The one-renderer-two-callers design does what it claimed.** Captions, sticker and
   waveform are in the exported frames too — also never pixel-confirmed before.
2. **The text-box picker is confirmed on a phone:** nine tiles, and "Animate by" offers all four
   granularities where a text box used to get Block alone.
3. **Text boxes hold frame rate at LETTER** (32.60% janky vs a BLOCK control's 35.61%; the
   difference is noise and points the wrong way to be a cost).
4. **Image overlays do NOT export — confirmed, with the root cause.** See below.
5. **The auto-rotate mystery from two sessions ago is closed** (it was `adb shell monkey`), and
   this session re-confirmed it: `am start` launches left the lock at 0 all night.
6. **120 mojibake characters repaired in the English UI.**

## FOUR REAL BUGS FOUND BY EXPORTING — none fixed, all measured

- **A. The exported file has 5.7s of VIDEO and 30.9s of AUDIO.** `ffprobe`: video
  `duration=5.743844` / 177 frames / last packet 5.710; audio `duration=30.912` / 1449 frames.
  5743ms is exactly the master track. A player shows ~25s of frozen or blank picture while audio
  continues. Audio clips sit at `offsetMs` 20608 and 13709, past the last video clip.
  **This is the biggest one and probably where to start.**
- **A2. The export dialog announces `00:05` for a file that is 30.9s.** Same disagreement, other
  side: the dialog reports video length, the muxer writes audio length.
- **B. An open-ended text box paces its animation against the PROJECT duration, which a trailing
  AUDIO clip stretches.** `animSpanMs` resolves a missing `endMs` to the timeline duration — 30.9s
  here *because of audio* — so a 25% entrance is 7.7s while only 5.7s of video exists, and the
  exported file never shows the finished word. Preview and export agree, so it is not a divergence;
  it is just not what a user means by "animate this title in". Likely downstream of A.
- **C. Image overlays do not export.** Confirmed by measurement, not reading: an image overlay was
  moved into the rendered window (1000–5000ms) and re-exported — **0 of 409,920 pixels differ** at
  2.5s and 3.5s, while the preview plainly draws it and the differ reports 383,976 differing pixels
  between two times of one file. Root cause exact: `CompositeExportOverlay:524` routes NON-image
  overlays to `TextBoxRenderer` and `continue`s; images fall through to the old path, which calls
  `setImageUri` at `:570` and hands the item to `TextOverlayRenderer.render` — which has **zero**
  references to images and substitutes `" "` for empty text at `:68`. Image overlays have empty
  text, so they render one blank space. The fix is an image branch in the export that loads the URI
  and draws it; the preview already does it, so the geometry is settled.

## THE REMAINING PRESET WORK — both DESIGNED, neither built, and both notes were wrong

- **NEON_FLICKER.** Its blocker ("modulate stroke/glow") is misleading: stroke and glow are optional
  per-object properties most objects lack, and captions have no per-object glow at all, so
  modulating what is there is a tile that does nothing. **It must supply its own glow.** It does
  NOT hit the GHOST-blur wall (`setShadowLayer` is honoured for text on a hardware canvas;
  `TextBoxRenderer` already relies on it). Full design in `SPEC_TEXT_ANIMATION.md`.
- **ODOMETER.** Blocker is correct (two characters per slot at once + a clip rect). Its spec has a
  flaw the user caught — filler characters must roll a SEQUENCE, not MATRIX's scramble. **The user
  was asked about its scope and said "skip odometer for now i dont know how to answer". Do not
  start it.**

## DECISIONS THAT BELONG TO THE USER — do not guess
 - ODOMETER's scope (explicitly deferred by the user).
 - **GHOST's blur** — decided WITH A CONDITION: *"if ghost preview would cause noticeable lag in
   working but look much better in export i think the divergence in this specific instance is
   warranted."* So MEASURE the preview cost first. If cheap, blur both and no divergence is needed.
   Only the ONE sanctioned divergence in the project — not a precedent.
 - Whether NEON_FLICKER may ship as alpha-flicker only (cheap, works everywhere, reads as a stutter
   rather than as neon).
 - Whether emphasis should be suppressed during an entrance rather than multiplied in.
 - Retrigger-on-value-change (a timer wants a pop on each TICK — an event, not elapsed time).
 - **The text-box TIMING control is still missing entirely** — a preset can be picked but its 25%
   seeded zone cannot be changed. Carets are built and parked, bound to MASTER-CLIP geometry, so
   pointing them at a layer item is a SECOND GEOMETRY. Arguably the most user-visible gap left.
 - Six emoji in `values/strings.xml` are corrupted beyond recovery (they hold U+FFFD):
   `watch_status_recording`, `shape_picker_title`, `rename_dialog_toast_success`,
   `stream_notes_cellular`, `stream_notes_android14_screen`, `remote_battery_low_warning`.
   Guessing which emoji belonged there would be inventing UI copy.

## HOW TO WORK HERE — rules each paid for in a bug
 - Prove it, never assert it. Pair every check with a positive control.
 - **PREDICT, THEN LOOK.** Every animated channel in this project is a closed-form function of the
   playhead, so the screen can be checked character-by-character instead of eyeballed.
   `tasks/matrix_predict.py` and `tasks/maskwipe_predict.py` are the models. This is what turned
   "the export looks right" into a receipt.
 - **A CONSOLE IS NOT EVIDENCE ABOUT ENCODINGS.** A git diff appeared to show the strings fix
   corrupting a line; a codepoint dump proved the opposite. Dump code points before believing text.
 - **A FAILING `javac` LEAVES THE OLD .class FILES AND THE HARNESS PASSES ON STALE BYTECODE.**
   Delete the output dir or check the exit code.
 - **Gradle reports UP-TO-DATE on real changes in this workspace.** It did again this session for a
   RESOURCE change, and had still rebuilt. Trust the artifact: check the APK mtime, and read the
   value out of `resources.arsc` / the dex rather than the build log.
 - **CHECK YOUR POSITIVE CONTROL FIRST.** Use `cat classes*.dex | grep -a -o -F -- "sym" | wc -l`
   with `FadCamApplication` as the control.
 - `MSYS_NO_PATHCONV=1` is needed for `/sdcard` args but breaks `/tmp` and `/c/...` LOCAL args in
   the same command — `adb push` and `git commit -F` need WINDOWS-style local paths there.
 - ffmpeg and opencv are both installed and on PATH. `ffprobe -show_entries stream=...` per stream:
   the FORMAT duration is the max of the streams and will mislead you (30.9s vs 5.7s of video).
 - To edit a project.json: `adb push` to /sdcard, then
   `adb shell "cat /sdcard/f.json | run-as com.fadcam.beta sh -c 'cat > files/.../project.json'"`.
   Force-stop first. Strip CR from `run-as cat`. Always guard the edit with a deep-equality check
   that nothing outside the intended keys changed, and restore to `eb3d16b8` when done.
 - **Picking a preset in a picker WRITES project.json immediately.** Observing must not become
   editing — dismiss with BACK and re-check the md5.
 - Recent Projects rows show the SOURCE MEDIA FILENAME, not the project name. bb2a9deb is the top
   row; confirm by its `lastModified`.
 - Literal non-ASCII in a Java CHAR/STRING literal breaks the harness build (windows-1252 vs UTF-8).
   Build such constants from code points. Comments are fine — only literals matter.
 - Do not pipe gradle through Select-String (exit 255 on success). Capture to a variable.
 - Screenshots via Bash (`adb exec-out screencap -p > f.png`), never PowerShell `>`.
 - adb: C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe
   Gradle needs `$env:TEMP='C:\Users\JoyRaptor\gtmp'; $env:TMP=$env:TEMP`
   Build task is **assembleDefaultDebug** (there is no assembleBetaDebug).
 - Commit messages: `git commit -F <file>`, written WITHOUT a BOM.

## DEVICE RULES — NON-NEGOTIABLE
 - The Note 9 SANDBOX_SERIAL is the sandbox and must be the ONLY phone attached.
 - If the Note 20 REAL_SERIAL appears, STOP all device work — it holds the user's real
   45-minute project.
 - **LAUNCH WITH `adb shell am start -n com.fadcam.beta/com.fadcam.SplashActivity`. NEVER `monkey`
   — it calls `thawRotation()` and turns the user's rotation lock OFF.** Verified again this
   session: every `am start` left `accelerometer_rotation` at 0.
 - The human tripwire still stands and is meaningful now the false trigger is gone: if
   `accelerometer_rotation` goes to 1 when YOU did not cause it, or `dumpsys power`'s
   `mLastUserActivityTime` shows input you did not inject, STOP and ask.
 - Installing the app kills whatever session is running — tell the user.
 - Route to a text box's editor: long-press it in the PREVIEW (not the timeline chip) → sheet opens
   in PEEK → drag the handle up → scroll the action list → **More…** → Edit text → MOTION row.

Work as autonomously as you can. Prove things rather than asserting them. Update the
ledger as you go — it is the only thing that survives between sessions.
