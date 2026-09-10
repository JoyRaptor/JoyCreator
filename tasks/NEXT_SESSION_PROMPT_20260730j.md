FadCam / Joy Creator — C:\+Projects\Screenrecorder\FadCam, branch joy-creator.
Working dir may be the PARENT, in which case docs are at FadCam/tasks/…

Read FadCam/tasks/LEDGER.md FIRST. It is the single record of what is fixed (with the
evidence that proved it), what is open, and what has been promised. Keep it current —
never delete an entry to shorten the list, strike through and correct instead.

**Supersedes NEXT_SESSION_PROMPT_20260730i.md.** Tree clean at `c5304b9`.
**Harness 326 passed / 0 failed — MEASURED, not copied** (was 303; the caret px maths is now
covered). Note 9 attached, rotation lock 0, sandbox `bb2a9deb` verified at md5 **`82d8342d`**.

---

## THE CARETS ARE DONE. §3h is closed — see LEDGER §1f.

The longest-open item shipped and was proved on the phone with SIX scripted drags, which is the
only instrument that could answer it. Three caret drags landed on PREDICTED stored values, undo
moved by exactly one per gesture, and two grabs at the trim cap — including its exact drawn
centre — took the TRIM, not the caret. Full numbers in §1f. Do not re-verify it; do not redesign
it. The one thing a future session should know is the trade-off, stated there honestly: the band
~18px immediately right of the trim cap's centre belongs to the caret, because the caret is
hit-tested first with the tighter zone. That is the same relationship the caption carets had, and
the user drove that version and approved it.

---

## ⚠ THE JOB: ODOMETER, and FIX THE SPEC BEFORE YOU BUILD IT

The last unimplemented preset. Designed in `SPEC_TEXT_ANIMATION.md`, and its old blocker is gone
— it needed a canvas renderer for text boxes in preview, which `a247b5c` shipped.

**Fix the spec first. The user caught a real flaw: the fillers must roll a SEQUENCE, not MATRIX's
scramble.** An odometer's digits step through adjacent values on their way to the answer; picking
random glyphs is a different effect that already exists. The scope question the user once declined
to answer ("skip odometer for now i dont know how to answer") is moot now, so it can simply be
built.

Model it before you write code — `tasks/matrix2_predict.py` is the pattern, and on MATRIX that
approach caught a design flaw in the MODEL before any code existed. Predict a frame, then look.

## THEN, IN ORDER

1. **The rig-driven sprite drift** — opening and closing a project silently moves sprite
   `8850f07c` (centerX/centerY/sizeFraction), and it ACCUMULATES. The control is in the same save:
   sprite `81563c97` does not move, and the difference is that `8850f07c`'s sheet is driven by
   `a6-smoke-rig` with `dangle: true`. **It is the only open item that quietly corrupts a user's
   project**, so it outranks polish.
   **NEW, and it belongs with this one: LEDGER §2c — audio clip IDs are regenerated on EVERY
   load/save.** Found by deep-diffing the whole project file across the caret test rather than
   grepping the keys under test. Same class (opening a project mutates untouched data), and it
   makes "did this edit change only what I meant" unanswerable by diff unless you know to ignore
   those fields — which is exactly how a real mutation would hide. Diff whole files.
2. **The picker thumbnail under-advertises GHOST.** `TextAnimPickerPopover` deliberately omits
   blur "to match" the fact that nothing drew it. Text boxes now DO. Small, and it keeps the tile
   honest — which is the whole reason the tile is generated from the evaluator.
3. **Captions still ignore `blurPx`** — a separate decision from GHOST-on-text-boxes, because the
   caption preview is ONE shared view for all words, so the cost profile differs. Do not assume
   the text-box decision settled it.
4. Baseline editor jank 33.7%; `9d7fef2b`'s `startMs` = 2^61−1; six irrecoverable emoji in
   `values/strings.xml` (they hold U+FFFD — guessing them would be inventing UI copy).

---

## HOW TO WORK HERE — rules each paid for in a bug

 - Prove it, never assert it. Pair every check with a positive control **and check the control can
   discriminate.** This session: the four new negative-travel checks were confirmed to fail when
   the old `Math.max(1f, …)` floor was put back, then the file was restored. A check nobody has
   seen fail is not evidence.
 - **PREDICT, THEN LOOK.** Every animated channel here is a closed-form function of the playhead,
   and the caret is a closed-form function of the tape. `tasks/matrix2_predict.py`,
   `matrix_predict.py`, `maskwipe_predict.py` are the models.
 - **A DRAG cannot be replaced by a screenshot.** The caret-vs-trim question sat open for two days
   because screenshots kept being offered as the answer. `adb shell input swipe`, then read the
   value off DISK and the undo line out of logcat — three independent channels per gesture.
 - **Undo is observable:** `adb logcat -s UndoManager | grep Recorded:` prints the description and
   the stack depth. One gesture must produce exactly one line.
 - **A green build proves nothing — scan the APK**, control first:
   `cat classes*.dex | grep -a -o -F -- "sym" | wc -l` with `FadCamApplication` (=3) as control.
   **Scan for a DELETED symbol too** — its absence is a freshness control a stale dex cannot fake.
 - **Gradle reports `UP-TO-DATE` for tasks it actually ran.** It did again this session, for
   `compileDefaultDebugJavaWithJavac`, on a build that had recompiled. Trust the artifact: APK
   mtime + the symbols in the dex.
 - **A green harness proves nothing if javac failed** — `java` will happily run STALE `.class`
   files and print the old pass count. `rm -rf` the out dir, then check the class file exists.
 - **Literal non-ASCII in a Java CHAR/STRING literal breaks the harness build** (windows-1252 vs
   UTF-8). Build such constants from `\uXXXX` escapes. Comments are fine — only literals matter.
 - **The console is cp1252 and is NOT evidence about encodings.** To print katakana:
   `python -c "import sys;sys.stdout.reconfigure(encoding='utf-8');exec(open('tasks/matrix2_predict.py').read())"`
 - `MSYS_NO_PATHCONV=1` is needed for `/sdcard` args but breaks LOCAL `/c/...` args in the same
   command — use WINDOWS-style local paths there. Windows Python needs `C:/…`, never `/c/…`.
 - **To restore a project.json, DO NOT `adb push`.** A failed push still runs the `cat >` half and
   TRUNCATES the file to zero bytes. Instead keep a byte-exact copy ON THE DEVICE before you
   start — `run-as com.fadcam.beta cp …/project.json files/backup.json` — and restore with `cp`
   inside `run-as`. That path has no truncation hazard at all. Verify by md5 (`82d8342d`) and
   force-stop the app BEFORE restoring so it cannot write over you.
 - Guard every project edit with a deep-equality diff of the WHOLE file, not a grep of the keys
   you meant to change. That is how §2c was found.
 - **A JSON-injected preset is INERT unless you also set `textAnimInPct`/`textAnimOutPct`** —
   `TextBoxRenderer` requires `inZone>0||outZone>0`, and the 25% zones are seeded by the PICKER.
 - **Picking a preset in a picker WRITES project.json immediately.** Dismiss with BACK, re-check md5.
 - ⚠ **The project list has a MULTI-SELECT MODE WITH A DELETE BUTTON under coordinates you
   routinely tap.** Screenshot before tapping a list you have not just looked at; re-verify 11
   projects. The sandbox is "P0 control no image" — the list shows a DIFFERENT name for it, so
   identify it by opening it and reading the `Editor loaded saved project: bb2a9deb…` logcat line,
   not by the label.
 - Route to a text box's editor: long-press it in the PREVIEW (not the timeline chip) → sheet in
   PEEK → drag the handle up → scroll the action list → **More…** → Edit text → MOTION row.
   To reach its CARETS instead, just tap the item on its layer row — carets appear on a selected
   text box whose preset is not NONE.
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
