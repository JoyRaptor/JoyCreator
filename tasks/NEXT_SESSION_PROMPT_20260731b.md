FadCam / Joy Creator — C:\+Projects\Screenrecorder\FadCam, branch joy-creator.
Working dir may be the PARENT, in which case docs are at FadCam/tasks/…

(Note: `NEXT_SESSION_PROMPT_20260731.md` is a STALE file misnamed on 2026-07-29. This is the
current one.)

Read FadCam/tasks/LEDGER.md FIRST. It is the single record of what is fixed (with the
evidence that proved it), what is open, and what has been promised. Keep it current —
never delete an entry to shorten the list, strike through and correct instead.

**Supersedes NEXT_SESSION_PROMPT_20260730j.md.** Tree clean.
**Harness 350 passed / 0 failed — MEASURED, not copied.** Note 9 attached, rotation lock 0,
sandbox `bb2a9deb` verified at md5 **`82d8342d`**, 11 projects.

---

## ⚠ FIRST JOB: PROVE ODOMETER'S RENDER. It is BUILT but its pixels are UNVERIFIED.

**All five presets now exist** — ODOMETER shipped this session (LEDGER §1g), and with it the
user's spec correction: the fillers step a RING (`0-9`, `a-z`, `A-Z`), counting UP into place, so
the roll can be READ. A scramble would have been MATRIX with vertical motion.

The AUTHORING half is proved on the phone: ODOMETER appears in the picker (it was filtered out by
`!p.implemented`), **its tile rolls**, "Animate by" offers all four granularities, selecting it
records exactly ONE undo step, the MOTION row reads *"Odometer · 25% in / 0% out of this box"*,
and `"textAnimPreset": "ODOMETER"` lands on disk.

**What is missing is the character-for-character proof MATRIX got.** `tasks/odometer_predict.py`
already exists — written BEFORE any device work — and models `unitProgress` → `decelerate` →
wheel → ring. One frame WAS captured showing the box mid-roll as `HAUCWJLW…`, which is exactly the
model's **progress-0** row (`HAUCWJLWKL`). **That is deliberately NOT claimed as evidence:** the
time chip in the same grab read 72ms, so the preview render was STALE rather than agreeing, and a
match against the wrong clock proves nothing.

**So the job is one careful capture** — a frame whose playhead and glyphs provably come from the
same instant. Notes that cost real time this session:

 - **Use the PLAY button to move the playhead, never a scripted swipe.** Play cannot edit
   anything. Two of my scrub swipes landed on the open bottom sheet and recorded real edits —
   `Add text overlay` and a master-clip `Trim`. Both were caught and reverted.
 - **⚠ Undoing that `Add text overlay` step DELETED PICKERTEST ITSELF.** An undo label is not a
   promise about what the step inverts once the stack has been disturbed. Recovery was the
   device-local byte-exact backup, not undo. Take that backup FIRST, every time.
 - The preview overlay only re-renders when something drives `setTextOverlayPlayhead`, so a paused
   playhead can show a frame from an earlier time. That is exactly the trap above.
 - Set the preset with the PICKER (proved to work) or, with zero UI risk, entirely on-device:
   `run-as com.fadcam.beta sh -c 'sed "s/\"textAnimPreset\": \"RISE\"/\"textAnimPreset\": \"ODOMETER\"/" <P>/project.json > files/edit.json'`
   then `cp` it over — no `adb push` anywhere in the path. PICKERTEST already carries
   `textAnimInPct: 0.25`, so an injected preset is NOT inert.
 - **The item's SPAN was never pinned, and the predictor assumes 5820ms.** The editor header says
   `00:30` while the item's tape measures ~5.8s at ~97px/s, and that contradiction is unresolved.
   Settle it before trusting any in-zone number; the discriminator is cheap — at span 5.8s the box
   is settled by ~1.5s, at 30s it is still rolling at 2.3s.
 - Try **LETTER** granularity too. BLOCK rolls the whole word as one wheel (correct, and what the
   spec says), but LETTER is the classic per-slot odometer and is now available on text boxes.

## THEN, IN ORDER

1. **The rig-driven sprite drift** — opening and closing a project silently moves sprite
   `8850f07c` (centerX/centerY/sizeFraction), and it ACCUMULATES. The control is in the same save:
   sprite `81563c97` does not move, and the difference is that `8850f07c`'s sheet is driven by
   `a6-smoke-rig` with `dangle: true`. **It is the only open item that quietly corrupts a user's
   project**, so it outranks polish.
   Its sibling is **LEDGER §2c — audio clip IDs are regenerated on EVERY load/save**, found by
   deep-diffing a whole project file rather than grepping the keys under test. Same class.
2. **The picker thumbnail under-advertises GHOST.** `TextAnimPickerPopover` omits blur "to match"
   the fact that nothing drew it — but text boxes DO now (LEDGER §1e). **While you are there,
   check a contradiction I spotted and did not chase:** `TextBoxRenderer.drawUnit`'s javadoc says
   *"blurPx is not applied, and that is a decision"* while the method body sets a
   `BlurMaskFilter`. One of the two is stale. A doc that contradicts its own method is exactly how
   the ODOMETER blocker note went wrong.
3. **Captions still ignore `blurPx`** — a separate decision from GHOST-on-text-boxes, because the
   caption preview is ONE shared view for all words, so the cost profile differs.
4. Baseline editor jank 33.7%; `9d7fef2b`'s `startMs` = 2^61−1; six irrecoverable emoji in
   `values/strings.xml` (they hold U+FFFD — guessing them would be inventing UI copy).

---

## HOW TO WORK HERE — rules each paid for in a bug

 - Prove it, never assert it. Pair every check with a positive control **and check the control can
   discriminate.** This session that rule caught a test of MY OWN: the first "counts up" assertion
   could only fail for a single character value and passed happily against a deliberately injected
   scramble. Injecting the defect you claim to detect is the only way to find that out.
 - **PREDICT, THEN LOOK.** `tasks/odometer_predict.py`, `matrix2_predict.py`, `matrix_predict.py`,
   `maskwipe_predict.py`. Write the model BEFORE the capture.
 - **Re-derive a blocker against the code before believing it — or before escalating it.**
   ODOMETER's scope question was escalated to the user twice and declined once, and the answer was
   never a preference: the `TextView` wall it described had already been demolished by other work.
   Four notes in `SPEC_TEXT_ANIMATION.md` have now been wrong in four different directions.
 - **A DRAG cannot be replaced by a screenshot** (the caret work), and **a screenshot cannot be
   trusted to be live** (this one). Pair every visual claim with the clock in the same framebuffer.
 - **Undo is observable:** `adb logcat -s UndoManager | grep Recorded:` gives description + depth.
 - **A green build proves nothing — scan the APK**, control first:
   `cat classes*.dex | grep -a -o -F -- "sym" | wc -l` with `FadCamApplication` (=3) as control.
   Scan for a DELETED symbol too — its absence is a freshness control a stale dex cannot fake.
 - **Gradle reports `UP-TO-DATE` for tasks it actually ran.** It did again this session. Trust the
   artifact: APK mtime + the symbols in the dex.
 - **A green harness proves nothing if javac failed** — `rm -rf` the out dir, then check the class
   file exists before believing the pass count.
 - **Literal non-ASCII in a Java STRING literal breaks the harness build** (it compiles without
   `-encoding`). Use `\uXXXX`, and remember Java's escape is a UTF-16 CODE UNIT: an emoji needs a
   surrogate PAIR (`\uD83C\uDF1F`), not a 5-hex-digit `\u1F31F`, which does not compile.
 - **The console is cp1252 and is NOT evidence about encodings.** Printing a non-ASCII character
   from Python raises rather than lying — force UTF-8:
   `python -c "import sys;sys.stdout.reconfigure(encoding='utf-8');exec(open('tasks/odometer_predict.py').read())"`
 - Python file writes: `io.open(...,'w')` TRUNCATES before it encodes, so a mid-write encode error
   leaves the file EMPTY. Encode to bytes first, then write, when content may hold surrogates.
 - **To restore a project.json, DO NOT `adb push`.** A failed push still runs the `cat >` half and
   truncates the file to zero. Keep a byte-exact copy ON THE DEVICE first
   (`run-as com.fadcam.beta cp …/project.json files/backup.json`) and restore with `cp` inside
   `run-as`. Force-stop the app BEFORE restoring so it cannot write over you. Verify by md5.
 - Guard every project edit with a deep-equality diff of the WHOLE file, not a grep of the keys you
   meant to change. That is how §2c was found.
 - **Scripted swipes aimed at the timeline are unsafe while a bottom sheet is open** — the sheet
   owns that region and will interpret them as edits.
 - **Picking a preset in a picker WRITES project.json immediately.** Dismiss with BACK, re-check md5.
 - ⚠ **The project list has a MULTI-SELECT MODE WITH A DELETE BUTTON under coordinates you
   routinely tap.** Screenshot before tapping a list you have not just looked at; re-verify 11
   projects. The sandbox is "P0 control no image" but the LIST SHOWS A DIFFERENT NAME for it —
   identify it by opening it and reading the `Editor loaded saved project: bb2a9deb…` logcat line.
 - Route to a text box's motion picker: long-press it in the PREVIEW (not the timeline chip) →
   sheet in PEEK → drag the handle up → scroll the action list → **More…** → the MOTION row's
   "A in motion" icon. To reach its CARETS instead, just tap the item on its layer row.
 - Do not pipe gradle through Select-String (exit 255 on success). Build task is
   **assembleDefaultDebug**. Gradle needs `$env:TEMP='C:\Users\JoyRaptor\gtmp'; $env:TMP=$env:TEMP`.
 - Harness: `rm -rf tools/jvm-harness/out-caption && javac -nowarn -d tools/jvm-harness/out-caption
   tools/jvm-harness/stubs/androidx/annotation/*.java
   tools/jvm-harness/stubs-caption/com/fadcam/ui/faditor/transcript/CaptionStyle.java
   app/src/main/java/com/fadcam/ui/faditor/transcript/{CaptionAnimator,CaptionPhrases,Transcript,TranscriptWord}.java
   tools/jvm-harness/CaptionAnimatorTest.java && java -cp tools/jvm-harness/out-caption CaptionAnimatorTest`
 - Screenshots via Bash (`adb exec-out screencap -p > f.png`), never PowerShell `>`.
 - adb: C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe
 - Commit messages: `git commit -F <file>`, written WITHOUT a BOM.

## DEVICE RULES — NON-NEGOTIABLE
 - The Note 9 `<note9-serial>` is the sandbox and must be the ONLY phone attached.
 - If the Note 20 `<note20-serial>` appears, STOP all device work — it holds the user's real
   45-minute project.
 - **LAUNCH WITH `adb shell am start -n com.fadcam.beta/com.fadcam.SplashActivity`. NEVER `monkey`**
   — it calls `thawRotation()` and turns the user's rotation lock OFF.
 - The human tripwire stands: if `accelerometer_rotation` goes to 1 when YOU did not cause it, or
   `dumpsys power` shows input you did not inject, STOP and ask.
 - Installing the app kills whatever session is running — tell the user.
 - Export: the ⬆ icon top-right → "Export Now". Output lands in
   `/storage/emulated/0/Android/data/com.fadcam.beta/files/FadCam/Faditor/`.
   **`ffprobe`'s FORMAT duration is the MAX of the streams and will mislead you** — always ask per
   stream: `ffprobe -show_entries stream=index,codec_type,duration,nb_frames`.

Work as autonomously as you can. Prove things rather than asserting them. Update the ledger as you
go — it is the only thing that survives between sessions.
