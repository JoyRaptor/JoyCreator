FadCam / Joy Creator — C:\+Projects\Screenrecorder\FadCam, branch joy-creator.
Working dir may be the PARENT, in which case docs are at FadCam/tasks/…

Read FadCam/tasks/LEDGER.md FIRST. It is the single record of what is fixed (with the
evidence that proved it), what is open, and what has been promised. Keep it current —
never delete an entry to shorten the list, strike through and correct instead.

**Supersedes NEXT_SESSION_PROMPT_20260730f.md.** Tree clean, build good, device good.
Sandbox `bb2a9deb` restored to md5 **`82d8342d`**, 11 projects intact, rotation lock 0.
Harness 298 / 0 at last run. **No build-state hazards this time** — unlike `f`, the build
directory is healthy and the installed APK is fresh and complete (dex-scanned with
`FadCamApplication`=3 as the control).

## WHAT LANDED THIS SESSION

1. `72739dd` — **diagnosed** the preview-side gap the previous session left open.
2. `026b302` — **fixed it.** See below; it was bigger than it looked.
3. Ledger — **the export path's per-glyph cost, measured for the first time.**

**The preview fix, because it is the interesting one.** The previous session recorded: past the
master track the preview didn't draw an image overlay while the export did, "yet it does draw the
PICKERTEST text box at that same time, so it is not a blanket rule". **That inference was the
trap.** PICKERTEST has no `startMs`/`endMs`, so its span is `0…Long.MAX_VALUE` and it draws at
EVERY clock value including a frozen one — it looked like a control and was not one.

The discriminating test: at 20.872s, inside `LayerOne`'s 20556–25117 span, the preview drew
PICKERTEST and **not** `LayerOne`. So it was never image-specific — **the preview drew nothing
whose span began past the last video clip**: text, images, sprites and the PiP panel alike.

Mechanism: the overlay clock was segment-derived (`getAbsolutePlayheadMs` = clips-before +
position-within-clip, which cannot exceed the master total by construction), and the timeline's
scrub emitter makes it worse — `updatePlayheadFromX` clamps `targetSegment` to the last segment
AND `posInSegmentMs` to its end, so overlays were actively told **5743ms** on every drag frame
while the on-screen chip (drawn by the timeline from its own `playheadPositionMs`) said 20872.
Fix is one helper, `overlayClockMs`: past the master track use the timeline's absolute ms, inside
it return the segment-derived value **unchanged** — so the §2a-hardened path is untouched where it
matters. The audio tail was a second, separate hole (the only path that advances past the last clip
during playback, and it never ticked any overlay surface).
Proved with four positives and two controls — see LEDGER §1 and
`tasks/screenshots/preview_past_master_before_after.png`.

**Also confirmed live, independently:** the previous session's tail filler logs
`appended a 25028ms black filler`, and the export dialog now reads **`00:30`** where it used to
say `00:05` — bugs A/A2 working on a real device.

## HOW MUCH IS LEFT ON TEXT ANIMATION — the user asked; this is the answer

**9 of 11 presets ship.** Remaining:

1. **The text-box TIMING control — the bulk of what is left, and the most user-visible gap.**
   A user can pick a preset on a text box but cannot change its seeded 25% in/out zone. The
   `▶ ◀` carets are built and parked (`EditorTimelineView.setCaptionAnimHandlesVisible` has no
   caller) but are bound to MASTER-CLIP geometry, so pointing them at a layer item is a SECOND
   GEOMETRY, not a re-target. **The user's stated instrument for text boxes is the carets, not a
   dialog row** — a slider pair in the Edit-text dialog would be cheaper and is strictly better
   than today's nothing, but it substitutes your judgement for a recorded decision. Ask.
2. **NEON_FLICKER** — designed, not built. Its recorded blocker is misleading: stroke and glow are
   OPTIONAL per-object properties most objects lack, and captions have no per-object glow at all,
   so modulating what is there ships a tile that does nothing. **It must supply its own glow.** It
   does NOT hit the GHOST-blur wall (`setShadowLayer` is honoured for text on a hardware canvas;
   `TextBoxRenderer` already relies on it). Design in `SPEC_TEXT_ANIMATION.md`.
3. **ODOMETER** — designed, not built. **The user deferred its scope call, but the thing that
   blocked it is GONE** (text boxes got a canvas renderer in `651359b`), so it is materially
   narrower than when they were asked — worth re-offering. Its spec still carries the flaw the user
   caught: fillers must roll a SEQUENCE, not MATRIX's scramble. Fix the spec before building.
4. **GHOST's blur** — the user DECIDED with a condition: *"if ghost preview would cause noticeable
   lag in working but look much better in export i think the divergence in this specific instance
   is warranted."* So **measure the preview cost first** (`LAYER_TYPE_SOFTWARE`). If cheap, blur
   both and no divergence is needed. The ONLY sanctioned divergence in the project.

Everything else in §3g is closed, including — new this session — the export per-glyph cost.

## THE NEW NUMBER, AND ITS LIMITS

**Export at LETTER costs +44.5%**: 81.05s vs 56.08s for BLOCK on the same project, timed from the
exporter's own `Export started`/`Export completed` log lines. **The exact opposite of the preview**,
where LETTER was free on both renderers. Limits, which are real: single runs; the arms move caption
AND text-box granularity together so it is a combined cost, not an attribution; and it is closer to
a worst case than a typical one, because the export is now 30.9s and `PICKERTEST` is open-ended, so
per-glyph work runs the whole file while captions occupy only the first 5.7s. **Not a reason to
discourage LETTER.**

## STILL OPEN, NOT §3g's FAULT
- **A preview-side sprite drift**: opening and closing a project silently moves a rig-driven
  sprite, and it accumulates. Recorded by an earlier session, still unfixed.
- Baseline editor jank 33.7% during playback (identical in every arm ever measured).
- `9d7fef2b` in the sandbox has `startMs` = 2^61−1 and is unreachable by any UI path. May be
  sandbox-only damage; check whether any code path can still produce it before spending time.
- Six emoji in `values/strings.xml` are corrupted beyond recovery (they hold U+FFFD) —
  `watch_status_recording`, `shape_picker_title`, `rename_dialog_toast_success`,
  `stream_notes_cellular`, `stream_notes_android14_screen`, `remote_battery_low_warning`.
  Guessing which emoji belonged there would be inventing UI copy.

## HOW TO WORK HERE — rules each paid for in a bug
 - Prove it, never assert it. Pair every check with a positive control — **and check that the
   control can actually discriminate.** This session's headline bug hid for a session behind a
   "control" (PICKERTEST) that was visible at every possible clock value.
 - **CHECK YOUR POSITIVE CONTROL FIRST.** `cat classes*.dex | grep -a -o -F -- "sym" | wc -l`
   with `FadCamApplication` as the control. A scan whose control reads zero is a broken scan OR a
   broken artifact — find out which.
 - **A green build proves nothing here.** This session saw `compileDefaultDebugJavaWithJavac`
   EXECUTE while `dexBuilder` and `packageDefaultDebug` said UP-TO-DATE — the exact corrupt-APK
   signature — and the artifact was nevertheless fine. **The labels are noise; scan the APK.**
 - Gradle sometimes fails spuriously and succeeds on an immediate re-run with no source change.
   Re-run before debugging, then scan the artifact anyway.
 - **⚠ THE PROJECT LIST HAS A MULTI-SELECT MODE WITH A DELETE BUTTON UNDER THE COORDINATES YOU
   ROUTINELY TAP.** A stray tap put it there this session; `1 selected` appeared with a red trash
   icon and my next taps were toggling checkboxes, not opening projects. Exit with the `X`
   (~990,632) and re-verify `ls files/faditor/projects | wc -l` **= 11**. Always screenshot before
   tapping a list you have not just looked at.
 - **Verify focus between navigation steps** (`dumpsys window | grep mCurrentFocus`). Three export
   attempts were wasted this session on taps that landed on the wrong screen, and one landed while
   a dialog was still animating in — give dialogs ~4s and confirm with a screenshot.
 - **PREDICT, THEN LOOK.** Every animated channel here is closed-form in the playhead.
   `tasks/matrix_predict.py` / `maskwipe_predict.py` are the models.
 - **ffprobe's FORMAT duration is the MAX of the streams** and will mislead you. Ask per stream.
 - `MSYS_NO_PATHCONV=1` is needed for `/sdcard` args but breaks `/tmp` and `/c/...` LOCAL args in
   the same command — `adb push` and `git commit -F` need WINDOWS-style local paths there.
 - To edit a project.json: `adb push` to /sdcard, then
   `adb shell "cat /sdcard/f.json | run-as com.fadcam.beta sh -c 'cat > files/.../project.json'"`.
   Force-stop first. Strip CR from `run-as cat`. Guard every edit with a deep-equality check, and
   restore to `82d8342d` when done. `project.json.bak` is the app's own byte-exact backup.
 - **Picking a preset in a picker WRITES project.json immediately.** Scrubbing and playback do not.
 - Recent Projects rows show the SOURCE MEDIA FILENAME, not the project name. `bb2a9deb` is the top
   row ("Jul 30, 8:31 AM"); confirm by matching a dumped `lastModified`.
 - `bc` is NOT installed. Time things from device log timestamps, not shell arithmetic.
 - Literal non-ASCII in a Java CHAR/STRING literal breaks the harness build (windows-1252 vs UTF-8).
   Build such constants from code points. Comments are fine — only literals matter.
 - Do not pipe gradle through Select-String (exit 255 on success). Capture to a variable.
 - Screenshots via Bash (`adb exec-out screencap -p > f.png`), never PowerShell `>`.
 - adb: C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe
   Gradle needs `$env:TEMP='C:\Users\JoyRaptor\gtmp'; $env:TMP=$env:TEMP`
   Build task is **assembleDefaultDebug** (there is no assembleBetaDebug).
 - Commit messages: `git commit -F <file>`, written WITHOUT a BOM.

## DEVICE RULES — NON-NEGOTIABLE
 - The Note 9 <note9-serial> is the sandbox and must be the ONLY phone attached.
 - If the Note 20 <note20-serial> appears, STOP all device work — it holds the user's real
   45-minute project.
 - **LAUNCH WITH `adb shell am start -n com.fadcam.beta/com.fadcam.SplashActivity`. NEVER `monkey`
   — it calls `thawRotation()` and turns the user's rotation lock OFF.** Held at 0 all session.
 - The human tripwire stands: if `accelerometer_rotation` goes to 1 when YOU did not cause it, or
   `dumpsys power`'s `mLastUserActivityTime` shows input you did not inject, STOP and ask.
 - Installing the app kills whatever session is running — tell the user.
 - Route to a text box's editor: long-press it in the PREVIEW (not the timeline chip) → sheet opens
   in PEEK → drag the handle up → scroll the action list → **More…** → Edit text → MOTION row.
 - Export: ⬆ top-right → wait ~4s → "Export Now" (~758,1625). Output lands in
   `/storage/emulated/0/Android/data/com.fadcam.beta/files/FadCam/Faditor/`. Time it from the
   `ExportManager: Export started` / `Export completed` log lines.

Work as autonomously as you can. Prove things rather than asserting them. Update the
ledger as you go — it is the only thing that survives between sessions.
