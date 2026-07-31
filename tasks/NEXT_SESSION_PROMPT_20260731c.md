FadCam / Joy Creator — C:\+Projects\Screenrecorder\FadCam, branch joy-creator.
Working dir may be the PARENT, in which case docs are at FadCam/tasks/…

**Supersedes NEXT_SESSION_PROMPT_20260731b.md.** Tree clean.
Read `FadCam/tasks/LEDGER.md` FIRST. Never delete an entry to shorten it — strike through and
correct in place.

**Harness 350 passed / 0 failed — MEASURED this session, not copied.** Matte harness ALL PASS.
Note 9 attached, rotation lock 0, sandbox `bb2a9deb` verified at md5 **`82d8342d`**, 11 projects.

---

## WHAT CLOSED LAST SESSION (do not redo)

- **§1h — ODOMETER's PREVIEW render is proved**, ten frames, character for character, each against
  the clock in its own framebuffer, with controls that discriminate (scramble scores 0/10). The
  blocker was a wrong span in my own predictor, not the phone: it is **30771ms**, not 5820.
- **§1j — the picker tile advertises GHOST's blur**, and two javadocs that contradicted their own
  method body are corrected.
- **§1k — captions blur too.** Measured GHOST-with-blur vs without over six playback runs; the
  difference is inside the noise floor, so the sanctioned divergence was not taken.
- **§1l — the 2^61−1 stranding is a LIVE bug, diagnosed and fixed** (two paths).
- **§1i — the rig-driven sprite drift did NOT reproduce.** A clean open → play → Close & Save left
  sprite `8850f07c` at exactly §1c's recorded BEFORE values.

---

## ⚠ FIRST JOB: DEVICE-PROVE THE 2^61−1 FIX. It is fixed but its behaviour is UNVERIFIED.

`§1l` traced the mechanism completely by reading and the fix compiles, but **no drag has been done
on a phone.** The ledger's own rule is that a drag cannot be replaced by reasoning.

**⚠ THE OBVIOUS REPRO DOES NOT WORK — I tried it four times across two builds on 2026-07-31 and
the BEFORE-arm produced sane values too, so it discriminated nothing. Read §1l's probe table
before designing another one.** The five conditions that must ALL hold:

1. The dragged item's **pre-drag** `startMs` must already overlap the open-ended sibling's block —
   the guard resolves `cur`, the position *before* the drag, not where you dropped it.
2. The drop must leave the item on a row that **still contains the sibling**. My drags kept landing
   it on a row where it was the only member (`items=1`), so there was no sibling and no block.
3. They must not overlap **at the grab point**, or the long-press takes the sibling instead.
4. Release with the finger in the RIGHT half of the panel, or the resolver escapes to the *before*
   side and returns a sane value legitimately.
5. The drop must not land in the new-layer zone.

**Put a `STRANDPROBE`-style log in the guard FIRST and assert `items >= 2` before believing any
result** — that one line is what turned four blind failures into a diagnosis.

- **Before the fix:** exactly `2305843009213693951` (= `Long.MAX_VALUE / 4`), and the item becomes
  permanently unreachable — no playhead, no long-press, no timeline chip.
- **After:** a sane millisecond value near where it was dropped.

**Take the device-local byte-exact backup FIRST** (`run-as com.fadcam.beta cp …/project.json
files/backup.json`) — recovery is that copy, never undo.

**Also still owed on §1l:** the three projects that already carry the damage (`bdd51919`,
`a2025388`, `aeb0517e`) are NOT repaired. The fix stops new stranding only. Repair needs a decision,
because the original `startMs` is unrecoverable — resetting to 0 is honest, guessing is not.

## THEN, IN ORDER

1. **ODOMETER's EXPORT half is unproved.** The preview is proved (§1h) and the shared renderer says
   the export must agree, but that is an argument from construction — exactly what this ledger does
   not accept. Export the sandbox with `textAnimPreset: ODOMETER` and check frames against
   `tasks/odometer_predict.py` (span **30771**, in-zone 7693ms; 1000ms → `JCWEYLNYMN`,
   3000ms → `MFZHBOQBPQ`).
2. **The rig-driven sprite drift needs RE-SCOPING, not re-hunting.** §1i rules out the plain
   load/save path, so §1c's wording ("opening and closing a project silently moves…") is too strong.
   Something else in that session was a necessary condition. **Do not go looking in the load/save
   path — that run is the control that rules it out.**
3. **§2c — audio clip IDs regenerate on every load/save.** Now has a MINIMAL repro (§1i): a no-edit
   open → play → close cycle produces 10 diffs, 9 of them regenerated ids and 1 `lastModified`.
   Nothing else in the file moves. Undiagnosed.
4. Remaining audit items: baseline editor jank 33.7%; six irrecoverable emoji in
   `values/strings.xml` (they hold U+FFFD — guessing them would be inventing UI copy).

---

## HOW TO WORK HERE — rules each paid for in a bug

 - **Prove it, never assert it, and CHECK THE CONTROL CAN DISCRIMINATE.** Injecting the defect you
   claim to detect is the only way to find out. This session that rule caught my own instrument
   twice (see the next two lines).
 - **A single frame of an ANIMATING thing is not a control.** Comparing GHOST's blur across two
   pickers gave "22 vs 162" — worthless, because both tiles run their own loop clock and the blur
   ramps; the same tile also produced 331. Match the clocks, or use a measure that is invariant to
   the phase (grad/peak-contrast was, and was verified invariant before being trusted).
 - **A measurement of a no-op costs nothing too.** Before believing "the blur is free", prove the
   blur RENDERS. Pair every performance number with evidence the thing happened.
 - **PREDICT, THEN LOOK.** `tasks/odometer_predict.py`, `matrix2_predict.py`, `matrix_predict.py`,
   `maskwipe_predict.py`. Commit the model BEFORE the capture so it is provably frozen.
 - **Re-derive a blocker against the code before believing it.** Four notes in
   `SPEC_TEXT_ANIMATION.md` have been wrong in four directions; this session two javadocs
   contradicted their own method bodies, and the caption-blur note named the wrong asymmetry
   ("one shared view" — it is DURATION).
 - **Check the OTHER call sites of a bad constant.** §1l's second stranding path was found only by
   grepping every use of `Long.MAX_VALUE / 4` instead of stopping at the first hit.
 - **A DRAG cannot be replaced by a screenshot, and a screenshot cannot be trusted to be live.**
   Pair every visual claim with the clock in the same framebuffer.
 - **Undo is observable:** `adb logcat -s UndoManager | grep Recorded:` gives description + depth.
 - **A green build proves nothing — scan the artifact.** Every `.class` must postdate its source and
   the APK must postdate every `.class`. **Gradle reported `UP-TO-DATE` on builds that had in fact
   recompiled SIX times this session.** Best freshness control is a DELETED symbol's absence.
 - **A green harness proves nothing if javac failed** — `rm -rf` the out dir, then check the class
   file exists before believing the pass count.
 - **Literal non-ASCII in a Java STRING literal breaks the harness build.** Use `\uXXXX`, and note
   Java's escape is a UTF-16 CODE UNIT (an emoji needs a surrogate PAIR).
 - **The console is cp1252 and is NOT evidence about encodings** — force UTF-8 in Python.
 - **Do not grep a single-line JSON file without `-o` or a byte cap** — `project.json` is one line
   in some projects and a match dumps the whole file.
 - **To restore a project.json, DO NOT `adb push`** — a failed push still runs the `cat >` half and
   truncates the file. Keep a byte-exact copy ON THE DEVICE and restore with `cp` inside `run-as`,
   app force-stopped first. Verify by md5.
 - Guard every project edit with a deep-equality diff of the WHOLE file, not a grep of the keys you
   meant to change.
 - **A sheet-relative gesture is unsafe whenever the sheet is not actually there** — the coordinates
   still look right. A replayed "drag the sheet handle up" with no sheet open went straight to the
   timeline and recorded a `Trim`. Screenshot between steps instead of chaining swipes.
 - **Picking a preset in a picker WRITES project.json immediately.** Dismiss with BACK, re-check md5.
 - ⚠ **The project list has a MULTI-SELECT MODE WITH A DELETE BUTTON under coordinates you routinely
   tap.** The sandbox is "P0 control no image" but the LIST SHOWS A DIFFERENT NAME. Identify it by
   sorting projects on `lastModified` (it is the most recent, top row) and confirm with the
   `Editor loaded saved project: bb2a9deb…` logcat line.
 - Route to a text box's motion picker: long-press it in the PREVIEW (not the timeline chip) → sheet
   in PEEK → drag the handle up → scroll the action list → **More…** → the MOTION row's `≡A`.
   Caption picker: bottom tool row → **Captions** → the Motion row's `≡A`.
 - Do not pipe gradle through Select-String (exit 255 on success). Build task is
   **assembleDefaultDebug**. Gradle needs `$env:TEMP='C:\Users\JoyRaptor\gtmp'; $env:TMP=$env:TEMP`.
 - Harness: `rm -rf tools/jvm-harness/out-caption && javac -nowarn -d tools/jvm-harness/out-caption
   tools/jvm-harness/stubs/androidx/annotation/*.java
   tools/jvm-harness/stubs-caption/com/fadcam/ui/faditor/transcript/CaptionStyle.java
   app/src/main/java/com/fadcam/ui/faditor/transcript/{CaptionAnimator,CaptionPhrases,Transcript,TranscriptWord}.java
   tools/jvm-harness/CaptionAnimatorTest.java && java -cp tools/jvm-harness/out-caption CaptionAnimatorTest`
   Matte: `bash tools/jvm-harness/run-matte.sh`
 - Screenshots via Bash (`adb exec-out screencap -p > f.png`), never PowerShell `>`.
 - adb: C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe
 - Commit messages: `git commit -F <file>`, written WITHOUT a BOM.

## DEVICE RULES — NON-NEGOTIABLE
 - The Note 9 `SANDBOX_SERIAL` is the sandbox and must be the ONLY phone attached.
 - If the Note 20 `REAL_SERIAL` appears, STOP all device work — it holds the user's real
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
