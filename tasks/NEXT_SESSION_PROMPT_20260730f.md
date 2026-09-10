FadCam / Joy Creator — C:\+Projects\Screenrecorder\FadCam, branch joy-creator.
Working dir may be the PARENT, in which case docs are at FadCam/tasks/…

Read FadCam/tasks/LEDGER.md FIRST. It is the single record of what is fixed (with the
evidence that proved it), what is open, and what has been promised. Keep it current —
never delete an entry to shorten the list, strike through and correct instead.

**This supersedes NEXT_SESSION_PROMPT_20260730e.md.**

## ⚠ READ THIS BEFORE YOU BUILD — the tree is clean, the BUILD DIR IS NOT

Last commit is `d29e8bf`, and it is **NOT COMPILED AND NOT TESTED**. The session ran out of
budget mid-build. It is committed rather than dropped so the reasoning survives, but nothing
about it is proved. Its own commit message is the long version of this section.

1. **`app/build/intermediates` (javac, dex, project_dex_archive, merged_dex) and
   `app/build/outputs/apk` were DELETED ON PURPOSE.** A full rebuild is required; expect CMake
   to re-run and take a while. `assembleDefaultDebug` failed twice around this — once with a
   path error about the very directory that had just been deleted — so **re-run before
   debugging**, which is a standing rule here.
2. **WHY they were deleted, and it is the sharpest instance of the artifact rule yet.** A build
   of `d29e8bf` produced an APK **missing `FadCamApplication`** — the manifest's Application
   class — while happily containing the change's new symbol `ensureBlackFillerUri`. Dex scan:
   3 hits in the previous APK, **0** in that one. That is a partial/corrupt dex from a failed
   compile followed by an `UP-TO-DATE` rerun, and it would have crashed on launch. **It was
   never installed. Do not install any APK from before your own clean rebuild.**
   It was caught only because the positive control read zero. Keep doing that.
3. **The phone is safe.** It still runs the last GOOD build (the strings fix). The sandbox
   project `bb2a9deb` is intact at md5 **`eb3d16b8`**.

**First three things to do, in this order:**
1. Clean-build and confirm `d29e8bf` compiles at all.
2. Dex-scan for `FadCamApplication` **and** `ensureBlackFillerUri` **together** before installing.
3. Export the sandbox and check `ffprobe` reports a VIDEO duration near 30.9s instead of 5.7s,
   with the PiP clip visible around t=10s.

## THE BUG d29e8bf IS TRYING TO FIX (bug A)

The export builds its video sequence from **master clips only**, so the video stream ends when
they do. Measured: video `duration=5.743844` / 177 frames against audio `duration=30.912` / 1449
frames. **Everything past the master track is silently dropped from the file while the editor
happily shows it** — on this project a 5.6s PiP clip (7948–13507), a text overlay (20556–25117),
two image overlays, a sprite from 12645, and the tail of PICKERTEST. 25 of 30.9 seconds.

Overlays are composited **per host clip** (`assembleClipVideoEffects` runs off the item the frame
belongs to), so a span with no clip under it has nothing to draw them onto — a muxer duration hint
will not do, it needs a real item. `d29e8bf` appends a black image clip for the remainder and
pushes it through the SAME `buildClipItem` path so it inherits the overlay pipeline, reusing the
black spacer PNG the editor's "Gap" feature already writes (that feature is the existing proof
that overlays render over a synthetic image clip). If the spacer cannot be created it logs loudly
rather than silently shipping a short file.

**Related, and probably falls out of the same fix — bug A2:** the export dialog announces `00:05`
for a file that is 30.9s. The dialog reports video length, the muxer writes audio length.

## THE OTHER TWO BUGS — measured, documented, untouched

- **C. Image overlays do not export.** *The cheapest real win still on the table: exact root cause,
  settled geometry.* Confirmed by measurement — an image overlay moved into the rendered window
  changed **0 of 409,920 pixels** at 2.5s and 3.5s, while the preview plainly draws it and the
  differ sees 383,976 differing pixels between two times of one file.
  Root cause: `CompositeExportOverlay:524` routes NON-image overlays to `TextBoxRenderer` and
  `continue`s; images fall through to the old path, which calls `setImageUri` at `:570` and hands
  the item to `TextOverlayRenderer.render` — which has **zero** references to images and
  substitutes `" "` for empty text at `:68`. Image overlays have empty text, so they render one
  blank space. `setImageUri` is a call into a void. The fix is an image branch in the export that
  loads the URI and draws it; **the preview already does it, so the geometry is settled.**
- **B. An open-ended text box paces its animation against the PROJECT duration**, which a trailing
  AUDIO clip stretches. `animSpanMs` resolves a missing `endMs` to the timeline duration — 30.9s
  here *because of audio* — so a 25% entrance is 7.7s while only 5.7s of video exists and the
  exported file never shows the finished word. Preview and export agree, so it is not a
  divergence. **Likely resolves itself once A lands** — check before treating it as separate work.

## THE REMAINING PRESETS — both DESIGNED, neither built, and both notes were wrong

- **NEON_FLICKER.** Blocker ("modulate stroke/glow") is misleading: stroke and glow are OPTIONAL
  per-object properties most objects lack, and captions have no per-object glow at all, so
  modulating what is there ships a tile that does nothing. **It must supply its own glow.** It does
  NOT hit the GHOST-blur wall — that wall is `BlurMaskFilter`, which a hardware canvas ignores;
  `setShadowLayer` is honoured for text and `TextBoxRenderer` already relies on it in the live
  preview. Full design in `SPEC_TEXT_ANIMATION.md`.
- **ODOMETER.** Blocker is correct (two characters per slot at once + a clip rect). Its spec has a
  flaw the user caught — fillers must roll a SEQUENCE, not MATRIX's scramble. **The user was asked
  its scope and said "skip odometer for now i dont know how to answer". Do not start it.**

## DECISIONS THAT BELONG TO THE USER — do not guess
 - ODOMETER's scope (explicitly deferred).
 - **GHOST's blur** — decided WITH A CONDITION: *"if ghost preview would cause noticeable lag in
   working but look much better in export i think the divergence in this specific instance is
   warranted."* MEASURE the preview cost first. If cheap, blur both and no divergence is needed.
   The ONLY sanctioned divergence in the project — not a precedent.
 - Whether NEON_FLICKER may ship as alpha-flicker only (cheap, works everywhere, reads as a
   stutter rather than as neon).
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
 - **CHECK YOUR POSITIVE CONTROL FIRST — it is what caught the corrupt APK above.** Use
   `cat classes*.dex | grep -a -o -F -- "sym" | wc -l` with `FadCamApplication` as the control.
   A scan whose control reads zero is a broken scan OR a broken artifact; find out which.
 - **A green build proves nothing here.** Gradle reports `UP-TO-DATE` on edited and brand-new
   files, and did so again for a RESOURCE change. Trust the artifact: APK mtime, plus the symbol
   in the dex or the value in `resources.arsc`.
 - **A FAILING `javac` LEAVES THE OLD .class FILES**, and the harness then passes on stale
   bytecode, and the dexer then ships a partial APK. Delete the output dir or check the exit code.
 - Gradle sometimes fails spuriously and succeeds on an immediate re-run with no source change.
   **Re-run before debugging** — but if the re-run says UP-TO-DATE, suspect a partial artifact.
 - **PREDICT, THEN LOOK.** Every animated channel here is a closed-form function of the playhead,
   so the screen can be checked character-by-character. `tasks/matrix_predict.py` and
   `tasks/maskwipe_predict.py` are the models. This is what turned "the export looks right" into a
   receipt.
 - **A CONSOLE IS NOT EVIDENCE ABOUT ENCODINGS.** A git diff appeared to show the strings fix
   corrupting a line; a codepoint dump proved the opposite.
 - **ffprobe's FORMAT duration is the MAX of the streams and will mislead you** (30.9s vs 5.7s of
   video). Always ask per stream: `ffprobe -show_entries stream=index,codec_type,duration,nb_frames`.
   ffmpeg and opencv are both installed and on PATH.
 - `MSYS_NO_PATHCONV=1` is needed for `/sdcard` args but breaks `/tmp` and `/c/...` LOCAL args in
   the same command — `adb push` and `git commit -F` need WINDOWS-style local paths there.
 - To edit a project.json: `adb push` to /sdcard, then
   `adb shell "cat /sdcard/f.json | run-as com.fadcam.beta sh -c 'cat > files/.../project.json'"`.
   Force-stop first. Strip CR from `run-as cat`. Guard every edit with a deep-equality check that
   nothing outside the intended keys changed, and restore to `eb3d16b8` when done.
 - **Picking a preset in a picker WRITES project.json immediately.** Observing must not become
   editing — dismiss with BACK and re-check the md5.
 - Recent Projects rows show the SOURCE MEDIA FILENAME, not the project name. `bb2a9deb` is the
   top row; confirm by its `lastModified`.
 - Literal non-ASCII in a Java CHAR/STRING literal breaks the harness build (windows-1252 vs
   UTF-8). Build such constants from code points. Comments are fine — only literals matter.
 - Do not pipe gradle through Select-String (exit 255 on success). Capture to a variable.
 - Screenshots via Bash (`adb exec-out screencap -p > f.png`), never PowerShell `>`.
 - Harness: 298 passed, 0 failed at `b01ad53`. Measure it by RUNNING it, from a CLEAN compile.
 - adb: C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe
   Gradle needs `$env:TEMP='C:\Users\JoyRaptor\gtmp'; $env:TMP=$env:TEMP`
   Build task is **assembleDefaultDebug** (there is no assembleBetaDebug).
 - Commit messages: `git commit -F <file>`, written WITHOUT a BOM.

## DEVICE RULES — NON-NEGOTIABLE
 - The Note 9 <note9-serial> is the sandbox and must be the ONLY phone attached.
 - If the Note 20 <note20-serial> appears, STOP all device work — it holds the user's real
   45-minute project.
 - **LAUNCH WITH `adb shell am start -n com.fadcam.beta/com.fadcam.SplashActivity`. NEVER `monkey`
   — it calls `thawRotation()` and turns the user's rotation lock OFF.** Verified twice: every
   `am start` left `accelerometer_rotation` at 0.
 - The human tripwire stands and is meaningful now the false trigger is gone: if
   `accelerometer_rotation` goes to 1 when YOU did not cause it, or `dumpsys power`'s
   `mLastUserActivityTime` shows input you did not inject, STOP and ask.
 - Installing the app kills whatever session is running — tell the user.
 - Route to a text box's editor: long-press it in the PREVIEW (not the timeline chip) → sheet opens
   in PEEK → drag the handle up → scroll the action list → **More…** → Edit text → MOTION row.
 - Export: the ⬆ icon top-right → "Export Now". Output lands in
   `/storage/emulated/0/Android/data/com.fadcam.beta/files/FadCam/Faditor/`.

Work as autonomously as you can. Prove things rather than asserting them. Update the
ledger as you go — it is the only thing that survives between sessions.
