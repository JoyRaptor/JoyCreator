FadCam / Joy Creator — C:\+Projects\Screenrecorder\FadCam, branch joy-creator.
Working dir may be the PARENT, in which case docs are at FadCam/tasks/…

Read FadCam/tasks/LEDGER.md FIRST. It is the single record of what is fixed (with the
evidence that proved it), what is open, and what has been promised. Keep it current —
never delete an entry to shorten the list, strike through and correct instead.

**Supersedes NEXT_SESSION_PROMPT_20260730h.md.** Tree clean at `850652e`.
**Harness 303 passed / 0 failed — MEASURED, not copied.** Note 9 attached, rotation lock 0.
Sandbox `bb2a9deb` restored and verified at md5 **`82d8342d`** (NOT `eb3d16b8` — that figure in
older handoffs is stale and must not be used as a restore target).

---

## ⚠ THE JOB: BUILD THE TEXT-BOX TIMING CARETS. The user has decided, twice.

This is the longest-open item in the ledger and it is now unblocked by a direct answer:

> *"The carets should work just exactly like they did on the very first iteration with the closed
> captions before we decided to change it for the closed captions. That actually worked really well
> as far as text is concerned. Closed captions was a different story, and it needed something
> different. But originally, those carets were expected to be put on text in the first place. And
> how they were operating in the closed captions worked perfectly well for text."*

**Revive the PARKED caret behaviour verbatim, aimed at a TEXT BOX.** Drag `▶` / `◀` inward on the
item's tape to set the in/out zones, zone tinted — the exact interaction that shipped for captions
in `665d543` and was retired there. **Do NOT redesign it. Do NOT substitute a slider pair in the
Edit-text dialog** — that was offered as the cheap alternative and the user has now chosen the
carets explicitly, twice. Full decision record: LEDGER §3h.

### What is already done for you

The machinery is complete, harness-covered, and its MATH is already target-agnostic — it takes a
`RectF` and a fraction, not a clip: `CaptionAnimator.caretFractionForZone` / `zoneFromCaretFraction`,
`captionAnimTravelPx`, the `CAPTION_ANIM_MIN_TRAVEL_PX` floor, and the **already-fixed negative-travel
trap** (LEDGER §3g records it: the old `Math.max(1f, …)` silently ERASED a zone on every touch of the
exit caret; it was fixed in parked code precisely so this build could not inherit it).

`EditorTimelineView` line ~6540 already routes DOWN through `hitTestCaptionAnimHandle`, so the touch
path exists. `setCaptionAnimHandlesVisible` has no caller — that is the switch.

### The three things that are genuinely clip-shaped and must be replaced

1. `selectedCaptionAnimClip()` resolves from `segments.get(selectedIndex).clip` and demands
   `hasTranscript()`. A text box is a `TimedItem` on a layer row.
2. The draw site is `segRects.get(selectedIndex)` — a MASTER-CLIP rect.
3. The zone getters read `Clip.getCaptionAnimInPct()`; a text box stores `textAnimInPct` /
   `textAnimOutPct` on `TextOverlayItem` (clamped 0…0.5 by `clampTextZonePct`, already proven to
   round-trip through `ProjectStorage` — LEDGER §3g "THE SERIALIZER IS NOW PROVEN").

### THE REAL OBSTACLE — read this before you estimate, it is not what the old note said

The old note called it "a second geometry" and stopped. Reading it out properly:

**An item's rect is content-x for left/right but SCREEN-y for top/bottom**
(`LayerRowRenderer.hitTestItem` maps x through `timeToX` and y through `bandLocalY`), **and the layer
band carries its OWN vertical scroll**, independent of the timeline's horizontal one
(`LayerRowRenderer.scrollOffsetPx` is a different field from the view's `scrollOffsetPx` — same name,
different axis; this WILL bite you).

So step one is a public `itemBodyRect(itemId, topPx, totalMs, timeToX)` on `LayerRowRenderer` that
**MIRRORS `hitTestItem`'s geometry** — x0 = `timeToX(start)`, x1 = `max(x0 + 6dp, timeToX(start+dur))`,
top/bottom from `row.bodyRect.top + 3dp` / `row.itemsBottom() - 3dp` put back through the inverse of
`bandLocalY`. **One derivation, or the caret draws where the finger cannot grab it** — the
two-derivations bug this project has paid for repeatedly.

### The acceptance test, and it needs a DRAG

The carets sit ~10px from the green trim handles. **"Are the carets grabbable without stealing
trim-handle grabs" has been an open question since 2026-07-29 and CANNOT be answered by a
screenshot.** Script a real drag (`adb shell input swipe`) and check: the zone tint moves, the trim
does NOT, `textAnimInPct` on disk changes, and the undo count moves by exactly ONE per gesture
(the caption sliders' discipline: `previewCaptionAnimZones` during the drag, one
`applyCaptionAnimZones`-style undo entry on release, recorded against the value from BEFORE the
gesture — LEDGER §3g "the slider previews live but records one undo step per GESTURE").

**Do not start this without the phone attached.** It is the one open item whose correctness cannot
be established off-device at all. The previous session declined to blind-build it for that reason.

---

## WHAT LANDED THIS SESSION

1. `debaf60` — **MATRIX reworked**: it RESOLVES across the message instead of starting as noise.
2. `850652e` / `57431fa` / `d413877` — ledger, spec model, caret decision.
3. **Glyph pool weighted to ASCII** (user direction) — 85% katakana → **75% ASCII**.

**MATRIX is PROVED ON DEVICE, character-for-character.** Predicted from `tasks/matrix2_predict.py`
before looking: at media 2556ms (progress 0.3323) → `cmV8Vﾓ` + 4 blanks. On screen: identical,
including the drawn count and the blank tail. The time chip and the text are in the SAME framebuffer
grab, so they cannot disagree. Screenshots `matrix2_resolve_wave_full.png` / `_zoom.png`.

**The user has signed off on NEON_FLICKER and GHOST blur** — *"I can confirm Neon Flicker works and
looks good… Ghost Blur looks good as well."* Both close.

---

## ⚠ THREE CORRECTIONS TO WHAT EARLIER HANDOFFS TOLD YOU

1. **The harness count in `…h.md` was WRONG.** It said 298/0; it was actually **295 passed / 1
   FAILED** at `06dc5f2`. NEON_FLICKER shipped a deliberately unit-keyed flicker which broke a
   control asserting only UNSCRAMBLE varies with `unitIndex`, and that session had REVERTED its own
   harness test over an encoding problem, so nobody re-ran it. Fixed here; the control is now a
   **pinned SET**. **This is the third time a harness number in a doc has been wrong — run it.**
2. **The sandbox md5 is `82d8342d`, not `eb3d16b8`.**
3. **A failed `adb push` still runs the `cat >` half of the pipeline** and will TRUNCATE
   `project.json` to zero bytes. Verify the push line before trusting it, or split it in two.

---

## THEN, IN ORDER

1. **ODOMETER** — the last unimplemented preset. Designed in `SPEC_TEXT_ANIMATION.md`, and its
   blocker is GONE: it needed a canvas renderer for text boxes in preview, which `a247b5c` shipped.
   **Fix the spec first — the user caught a real flaw: fillers must roll a SEQUENCE, not MATRIX's
   scramble.** The user previously declined to answer its scope question ("skip odometer for now i
   dont know how to answer"); that question is now moot, so it can simply be built.
2. **The picker thumbnail under-advertises GHOST.** `TextAnimPickerPopover` deliberately omits blur
   "to match" the fact that nothing drew it. Text boxes now DO. Small, and it keeps the tile honest —
   which is the whole reason the tile is generated from the evaluator.
3. **The rig-driven sprite drift** — opening and closing a project silently moves sprite `8850f07c`
   (centerX/centerY/sizeFraction), and it ACCUMULATES. The control is in the same save: sprite
   `81563c97` does not move, and the difference is that `8850f07c`'s sheet is driven by
   `a6-smoke-rig` with `dangle: true`. **It is the only open item that quietly corrupts a user's
   project**, so it outranks polish.
4. **Captions still ignore `blurPx`** — a separate decision from GHOST-on-text-boxes, because the
   caption preview is ONE shared view for all words, so the cost profile differs. Do not assume the
   text-box decision settled it.
5. Baseline editor jank 33.7%; `9d7fef2b`'s `startMs` = 2^61−1; six irrecoverable emoji in
   `values/strings.xml` (they hold U+FFFD — guessing them would be inventing UI copy).

---

## HOW TO WORK HERE — rules each paid for in a bug

 - Prove it, never assert it. Pair every check with a positive control **and check the control can
   discriminate.**
 - **PREDICT, THEN LOOK.** Every animated channel here is a closed-form function of the playhead.
   `tasks/matrix2_predict.py`, `matrix_predict.py`, `maskwipe_predict.py` are the models. This is
   what turned "it looks right" into a receipt — and this session it caught a design flaw (holes
   opening mid-message) in the MODEL, before any code was written.
 - **A green build proves nothing — scan the APK**, control first:
   `cat classes*.dex | grep -a -o -F -- "sym" | wc -l` with `FadCamApplication` (=3) as control.
 - **Gradle reports `UP-TO-DATE` for tasks it actually ran, and also for tasks it should have run.**
   Both happened this session. Trust the artifact: APK mtime + the symbol in the dex.
 - **A green harness proves nothing if javac failed** — `java` will happily run STALE `.class` files
   and print the old pass count. Check javac produced a class file.
 - **Literal non-ASCII in a Java CHAR/STRING literal breaks the harness build** (windows-1252 vs
   UTF-8). Build such constants from `\uXXXX` escapes. Comments are fine — only literals matter.
 - **The console is cp1252 and is NOT evidence about encodings.** To print katakana:
   `python -c "import sys;sys.stdout.reconfigure(encoding='utf-8');exec(open('tasks/matrix2_predict.py').read())"`
 - `MSYS_NO_PATHCONV=1` is needed for `/sdcard` args but breaks LOCAL `/c/...` args in the same
   command — use WINDOWS-style local paths there. Windows Python needs `C:/…`, never `/c/…`.
 - To edit a project.json: force-stop, `adb push` to `/sdcard`, then
   `adb shell "cat /sdcard/f.json | run-as com.fadcam.beta sh -c 'cat > files/.../project.json'"`.
   Guard every edit with a deep-equality check that nothing outside the intended keys changed.
   **Restore to `82d8342d` and verify by md5 when done.**
 - **A JSON-injected preset is INERT unless you also set `textAnimInPct`/`textAnimOutPct`** —
   `TextBoxRenderer` requires `inZone>0||outZone>0`, and the 25% zones are seeded by the PICKER.
 - **Picking a preset in a picker WRITES project.json immediately.** Dismiss with BACK, re-check md5.
 - ⚠ **The project list has a MULTI-SELECT MODE WITH A DELETE BUTTON under coordinates you routinely
   tap.** Screenshot before tapping a list you have not just looked at; re-verify 11 projects.
 - Route to a text box's editor: long-press it in the PREVIEW (not the timeline chip) → sheet in
   PEEK → drag the handle up → scroll the action list → **More…** → Edit text → MOTION row.
 - Do not pipe gradle through Select-String (exit 255 on success). Build task is
   **assembleDefaultDebug**. Gradle needs `$env:TEMP='C:\Users\JoyRaptor\gtmp'; $env:TMP=$env:TEMP`.
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
