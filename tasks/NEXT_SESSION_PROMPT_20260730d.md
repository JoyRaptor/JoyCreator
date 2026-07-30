FadCam / Joy Creator — C:\+Projects\Screenrecorder\FadCam, branch joy-creator.
Working dir may be the PARENT, in which case docs are at FadCam/tasks/…

Read FadCam/tasks/LEDGER.md FIRST. It is the single record of what is fixed (with the
evidence that proved it), what is open, and what has been promised. Keep it current —
never delete an entry to shorten the list, strike through and correct instead.

Last good commit: a247b5c. Tree clean, builds, APK installed on the Note 9.
Sandbox project bb2a9deb restored to md5 eb3d16b8 (byte-identical to its pre-session state).
Harness: 298 passed, 0 failed. Matte harness: ALL PASS.

## WHAT LANDED LAST SESSION

1. `438ed73` — **MASK_WIPE shipped** (third preset). Its big finding: it does NOT hit the
   text-box `TextView` wall, because that wall is about drawing TWO things, not about clipping —
   `View.setClipBounds` clips one body fine.
2. `7093877` — **a correction that doubled an estimate.** The recorded reason text boxes were
   BLOCK-only read as "preview can't, export already does". Neither side did it: the export
   rasterised the whole box and animated the bitmap.
3. `a247b5c` — **text boxes now animate per LETTER / WORD / SENTENCE.** This is what the user
   actually wanted: *"really that was the only point in doing animate text in the first place."*

**The shape of (3), because it is the load-bearing decision.** Captions keep preview and export
in step with TWO hand-maintained mirrors. Text boxes got the stronger version — **ONE renderer,
two callers.** `TextBoxRenderer` owns measurement, line layout, unit splitting, all three animated
channels and the three paint passes. The preview's new `TextBoxView.onDraw` calls it; the export's
`CompositeExportOverlay` calls it too and no longer rasterises to a bitmap. **Do not add a second
text-box drawing path.** If something needs to change about how a text box looks, it changes in
one file or it is wrong.

## WHAT TO DO FIRST — PROVE THE EXPORT

**This is the one thing that must happen before anything else, and it is not optional.** The whole
argument for a shared renderer is that both surfaces agree. Right now that is an argument from
construction, not a measurement — **no file has been exported since the rewrite.** The project has
NEVER pixel-proved an export for any preset, so this closes the oldest gap in §3g at the same time.

Suggested shape: set `PICKERTEST` (or a fresh box) to RISE + LETTER, export the sandbox project,
pull the file, extract a frame at a known media time, and compare it against a preview screenshot
at the SAME time. `tasks/maskwipe_predict.py` is the model for computing the expected state from
the on-screen time chip; a text box's reveal/progress is a closed-form function of the playhead,
so you can predict rather than merely eyeball. **A per-letter export that agrees with the preview
is the receipt for the whole session's work. A disagreement is a bug you want to find now.**

Watch specifically for: the box's CENTRE (preview centres the box inside a larger view; export
centres it on the frame), the 0.35em pad, and rotation composition order.

## THEN, IN ORDER

2. **Confirm the text-box picker really offers all four granularities.** The gate is open in code
   (`TextOverlayItem.textAnimGranularitySupported` returns true; the picker is passed `null`) but
   the deep route in was not completed before the session ended, so it is unverified on a phone.
   Route: long-press the overlay **in the PREVIEW** (not the timeline chip) → object sheet opens in
   PEEK → drag its handle up to EXPAND → scroll the action list to the bottom → **"More…"** → the
   Edit text dialog → the MOTION row → the picker.
3. **Measure the text-box frame rate at LETTER.** Captions were measured and hold; this is a
   different renderer and is unmeasured. `dumpsys gfxinfo`, matched runs, against the known 33.7%
   editor baseline.
4. **GHOST's blur — the user has DECIDED, and the decision has a condition.** They authorised an
   export-only divergence: *"if ghost preview would cause noticeable lag in working but look much
   better in export i think the divergence in this specific instance is warranted."* So **measure
   the preview cost first** — `BlurMaskFilter` is ignored on a hardware canvas, so blurring the
   preview needs `LAYER_TYPE_SOFTWARE`. If that turns out cheap, blur BOTH and no divergence is
   needed (their intent, met more cheaply). If it is expensive, blur export only. This is the ONLY
   sanctioned divergence in the project — do not treat it as a precedent for anything else.
5. **NEON_FLICKER**, the last unbuilt preset. Prediction on record: it modulates paint, so it
   should need neither the text-box canvas renderer nor a clip. Its glow half may walk into the
   GHOST-blur question above — if it does, that is item 4, not a new decision.
6. **ODOMETER.** Still the user's scope call, but MUCH narrower now: text boxes have a canvas
   renderer, so the thing that blocked it is gone. Re-derive it. **And its design has a known
   flaw the user caught: the spec says filler characters come from MATRIX's random scramble, which
   is a slot machine, not an odometer — and makes ODOMETER into "MATRIX but sliding".** The user's
   point: a real odometer rolls a SEQUENCE. Agreed replacement, not yet written into the spec:
   a slot rolls through its own character's neighbours (`3 4 5 → 6`, `c d e → f`, case preserved,
   punctuation and spaces do not roll, wrap at `a`/`0`). Fix the spec before building.

## FOUND AND NOT FIXED — verify before acting
- **IMAGE OVERLAYS APPEAR NOT TO EXPORT AT ALL.** `CompositeExportOverlay:541` calls
  `frameOverlay.setImageUri(...)` and nothing reads it — `TextOverlayRenderer.render` is text-only
  and `getImageUri` has no other reader in the export package. So a Sticker-tool image shows in the
  preview and is absent from the file. **This contradicts §3a item 2 of the ledger**, which
  inferred function from that setter call. Found by reading, NOT by exporting — so confirm it with
  a real export (which item 1 above is already doing) before spending a session on it.

## DECISIONS THAT BELONG TO THE USER — do not guess
 - ODOMETER's scope (narrower now — see 6).
 - Whether emphasis should be suppressed during an entrance rather than multiplied in.
 - Retrigger-on-value-change: a timer wants a pop on each TICK — an EVENT, not a function of
   elapsed time. `CaptionAnimator` has no notion of it.
 - **The text-box TIMING control is still missing entirely** — the user can pick a preset but
   cannot change its 25% seeded in/out zone. The carets are built and parked
   (`EditorTimelineView.setCaptionAnimHandlesVisible` has NO CALLER); they are bound to
   MASTER-CLIP geometry, so pointing them at a layer item is a SECOND GEOMETRY, not a re-pointed
   target. Budget accordingly. This is arguably the most user-visible gap left.

## HOW TO WORK HERE — rules each paid for in a bug
 - Prove it, never assert it. Pair every check with a positive control.
 - **GRADLE REPORTS `compileDefaultDebugJavaWithJavac UP-TO-DATE` ON EDITED AND EVEN BRAND-NEW
   SOURCE FILES IN THIS WORKSPACE.** It happened three times last session. **`rm -rf
   app/build/intermediates/javac/defaultDebug` before building forces a real compile** — verify by
   checking the task line has no UP-TO-DATE and the `.class` timestamps are seconds old. A
   BUILD SUCCESSFUL here means nothing on its own.
 - Gradle also failed once with 100 cascading `cannot find symbol` errors and then succeeded on an
   immediate re-run with no source change, and once with a packaging state error fixed by deleting
   `app/build/outputs/apk`. Re-run before debugging.
 - **CHECK YOUR POSITIVE CONTROL FIRST.** A dex scan has read zero-for-everything three times for
   three different reasons; last session's was `strings` not being installed. Use
   `cat classes*.dex | grep -a -o -F -- "sym" | wc -l`.
 - **A FAILING `javac` LEAVES THE OLD .class FILES AND THE HARNESS REPORTS A CONFIDENT PASS ON
   STALE BYTECODE.** Delete the output dir or check the exit code.
 - Measure the harness count by RUNNING it. It is 298 at `a247b5c`.
 - The strongest DEVICE freshness control is behavioural: each new preset makes the picker grow a
   tile (six → seven → eight → nine so far). No symbol scan can fake that.
 - Literal non-ASCII in a Java CHAR/STRING literal breaks the harness build (windows-1252 vs
   UTF-8). Build such constants from code points. Comments are fine — only literals matter.
 - Do not pipe gradle through Select-String (exit 255 on success). Capture to a variable.
 - Screenshots via Bash (`adb exec-out screencap -p > f.png`), never PowerShell `>`.
 - **`MSYS_NO_PATHCONV=1` is needed for the `/sdcard` argument but then breaks the `/c/...` LOCAL
   argument, so `adb push` needs a WINDOWS-style local path in the same command.** Getting this
   wrong truncated `project.json` to 0 bytes; `project.json.bak` (the app's own pre-write backup,
   and the byte-exact `eb3d16b8` restore target) is what made it a non-event. Do not "clean it up".
 - To edit a project.json: `adb push` to /sdcard, then
   `adb shell "cat /sdcard/f.json | run-as com.fadcam.beta sh -c 'cat > files/.../project.json'"`
   — `run-as` cannot read /sdcard. Force-stop the app first. Strip CR from `run-as cat`.
 - **Picking a preset in the caption drawer WRITES project.json immediately.** Scrubbing and
   playback do not.
 - All three `audioClips` ids regenerate on EVERY save. A structural deep-diff that ignores them
   turns "did my edit do anything else?" into "exactly one difference in the file" — worth writing.
 - **Recent Projects rows show the SOURCE MEDIA FILENAME, not the project name** — four rows read
   "FadCam_20260621_145132". Identify the row by matching its timestamp to a dumped `lastModified`.
 - When looping over project ids with adb, redirect stdin (`< /dev/null`).
 - adb: C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe
   Gradle needs `$env:TEMP='C:\Users\JoyRaptor\gtmp'; $env:TMP=$env:TEMP`
   Build task is **assembleDefaultDebug** (there is no assembleBetaDebug).
 - Commit messages: `git commit -F <file>`, written WITHOUT a BOM.
 - Strings are HARDCODED with `// TODO(strings)` — frozen behind the rebrand, don't "fix" it.

## DEVICE RULES — NON-NEGOTIABLE
 - The Note 9 SANDBOX_SERIAL is the sandbox and must be the ONLY phone attached.
 - If the Note 20 REAL_SERIAL appears, STOP all device work — it holds the user's real
   45-minute project.
 - **LAUNCH WITH `adb shell am start -n com.fadcam.beta/com.fadcam.SplashActivity`. NEVER
   `monkey` — it calls `thawRotation()` and turns the user's rotation lock OFF.** See LEDGER §5.
 - The human tripwire still stands: if `accelerometer_rotation` goes to 1 when YOU did not cause
   it, or `dumpsys power`'s `mLastUserActivityTime` shows input you did not inject, STOP and ask.
 - Installing the app kills whatever session is running — tell the user.
 - If adb drops the device: `adb kill-server; adb start-server`.

Work as autonomously as you can. Prove things rather than asserting them. Update the
ledger as you go — it is the only thing that survives between sessions.
