# Opencode-work.md — DeepSeek autonomous work queue (written by Fable/JoyRaptor, 2026-07-05)

You are DeepSeek running on the opencode harness, working on **Joy Creator** (repo
`C:\+Projects\Screenrecorder\FadCam`, git branch `joy-creator`, Android video editor,
package `com.fadcam.beta`). The user CANNOT code — you are their hands. JoyRaptor (Fable) and
Basil are on cooldown; your job is to work through the numbered tasks below IN ORDER for
as long as you can, then leave an honest progress log so the next AI picks up cleanly.

Read `tasks/handoff.md` (top 3 blocks) before starting. This file gives you everything
else you need — do not improvise beyond it.

---
## ABSOLUTE RULES (violating these destroys other sessions' work)

**Git**
- Local commits only. **NEVER push, never rebase, never reset --hard, never touch any stash.**
- Commit after EVERY task that builds green — small honest commits, one task each.
- Never `git add -A` blindly: add ONLY the files you touched for the current task
  (another AI's in-flight files may be dirty in the tree — see TASK 0).

**Build**
- You are on the host, so gradle should work for you:
  `cd C:\+Projects\Screenrecorder\FadCam` then `.\gradlew.bat installDefaultDebug`
  (compile only: `.\gradlew.bat compileDefaultDebugJavaWithJavac`).
- "BUILD FAILED" lines caused by install/device problems still mean COMPILE SUCCESS —
  read the actual error. If gradle itself won't run, STOP coding UI work and only do
  TASK 2 + the log, and say so in the progress log.
- **Never claim "build green" without quoting the tail of the build output you just ran.**
  (An earlier session trusted a 6-hour-old build.log and shipped a false claim.)

**Device**
- Sandbox test phone ONLY: adb serial `SANDBOX_SERIAL` (Note 9). **NEVER touch device
  `REAL_SERIAL` or project `27221664…`** (the user's real phone/project).
- Ground truth = `adb -s SANDBOX_SERIAL shell run-as com.fadcam.beta cat files/faditor/projects/<id>/project.json`.
- Gestures (drags/pinches) CANNOT be scripted reliably on this device. ONE scripted
  attempt max, then write a numbered hand-test list for the user instead. Taps are fine.
- If you flip `android:exported` on an activity to launch it directly, you MUST revert
  that before committing — temp test flips never enter git history.

**Scope — DO NOT TOUCH (Fable/Opus-tier, hands off even if you see a bug):**
- `avatar/PuppetPoseResolver.java`, `avatar/AvatarRig.java` (blend/hysteresis math)
- `layers/LayerGestureController.java`, `timeline/EditorTimelineView.java` gesture code
- `compositor/MasterPlaybackEngine.java`, playback/ping-pong/loop engine code
- `export/ExportManager.java` beyond what a task explicitly specifies
- No schema/version changes, no new dependencies, no refactors, no lib upgrades,
  no renaming, no "cleanup" of code you didn't write.
- If a task fights back after 2 honest attempts: record what you tried in the progress
  log and MOVE ON to the next task. Never leave the tree non-compiling — revert your
  own uncommitted changes for a failed task (`git checkout -- <only-your-files>`).

---
## STATE OF THE WORLD (2026-07-05 morning)

Landed today (all local): avatar resolver fixes + Avatar Studio matrix editor
(`avatar/AvatarStudioActivity`), sprite preview over video (`sprite/SpriteOverlayView`),
sprite palette panel (`sprite/SpritePalettePanel`, Sprites tool button opens it),
sheet-editor Auto-detect/bg-key/sidecar-export, sprite export compositing, S7 relink,
plus pure-math avatar cores (`OneEuroFilter`, `LifeSignals`, `FabrikSolver`,
`AudioLevelViseme`). All Java was verified by javac only — **the XML (manifest entry,
`sprite_overlay_layer` in activity_faditor_editor.xml, new strings) has never been
through a real build**, and none of it has run on the device yet. That's your TASK 0/1.

~~Another session left M-COMP-2 (overlay-video PiP) work IN FLIGHT~~ **SUPERSEDED
2026-07-05 ~16:45 (Fable, M-COMP-2 lane): that in-flight work is COMMITTED as 0453db9
(M-COMP-2b+2c, device-verified, manifest exported flips already reverted). The tree is
CLEAN. TASK 0 reduces to: verify `git status --short` is clean + one green
`compileDefaultDebugJavaWithJavac`, then go straight to TASK 1.** Two additions to the
DO-NOT-TOUCH list: `compositor/OverlayVideoPreviewView.java` and
`compositor/DecoderBudgetProbeActivity.java` (M-COMP-2 lane, Fable-tier). One known
non-bug: the bdd51919 sandbox project ends master playback ~1.4s after pressing play —
CONTROL-PROVEN pre-existing (reproduces with zero overlay clips); do not chase it as a
PiP regression. The sandbox project currently contains one injected PiP overlay clip
(backup at `project.json.bak-mcomp2-20260705` on-device) — leave both in place.

---
## TASK 0 — Recover the tree + first green build  (do this FIRST, carefully)

1. `git status --short` and `git log --oneline -6`. If the M-COMP-2 files above are
   still dirty/untracked, DO NOT revert them.
2. Run `.\gradlew.bat compileDefaultDebugJavaWithJavac` and read the tail.
   - **GREEN:** run `git diff` on the dirty files; if the diff is one coherent feature
     (PiP overlay video), commit it honestly:
     `git add <exactly those files>` then commit message
     `feat(layers): M-COMP-2 WIP recovered by opencode - compiles green (diff-reviewed, another session's in-flight work)`.
   - **RED with errors in the M-COMP-2 files:** stash ONLY those files with a clear name:
     `git stash push -m "opencode: M-COMP-2 in-flight, did not compile" -- <files>`,
     then rebuild; record what you did.
   - **RED with errors in sprite/avatar XML or today's files:** these are likely small
     typos (a string name, a layout tag). Fix the MINIMAL thing the error names, rebuild,
     and commit the fix alone: `fix(build): <what> - opencode`.
3. Check `AndroidManifest.xml`: `FaditorEditorActivity` currently has
   `android:exported="true"`. If the M-COMP-2 diff does not obviously need it, treat it
   as a temp test flip: set it back to `false` BEFORE any commit that includes the
   manifest, and note it in the log.
4. `.\gradlew.bat installDefaultDebug` with the sandbox phone connected. Record the tail.

## TASK 1 — Device smoke-verification of today's features (taps only, ~20 min)

Use `adb -s SANDBOX_SERIAL` for everything. Evidence = screenshots
(`adb exec-out screencap -p > shot.png`) + project.json pulls. Record each result.

1. Launch the app, open the sandbox project (id starts `bdd51919`), let it settle.
2. **Sprites panel:** tap the Sprites tool in the bottom carousel → the bottom PANEL
   should open (not the old dialog). Empty state shows "+ Load sprite sheet" OR, if
   star-guy sheets exist, instance/cell chips. Screenshot.
3. **Place a sprite:** panel ⚙ → tap an existing sheet → "Place on video" → toast →
   star-guy visible over the video. Screenshot. Pull project.json → confirm a
   `spriteOverlays` entry with `sheetId`, `frameTrack` with one key.
4. **Swap drop:** with the panel open, tap a DIFFERENT cell chip → toast "Swap dropped"
   → pull project.json → frameTrack now has 2 keys. Scrub the timeline across the swap
   time → the visible cell should change. Screenshots before/after.
5. **Avatar Studio:** panel ⚙ → "🎭 Avatar Studio…" → "+ New avatar" → screen opens with
   3×3 grid + Yaw/Pitch sliders. Tap "+ Part" → pick a sheet → name it "head" → part
   chip appears + art at canvas center. Move the Yaw slider (tap positions on the bar,
   don't drag) → no crash. Back (autosaves) → pull project.json → `avatarRigs` array
   exists with your rig + `schemaVersion` 10. Screenshots.
6. **Export with sprite:** export the project (lowest-effort path), pull the mp4
   (`run-as` copy out), extract 2 frames with ffmpeg at times where the sprite shows
   different cells, confirm the sprite is IN the export and matches preview. If ffmpeg
   is not on PATH, note it and skip extraction — but still confirm export completes.
7. **Any crash:** `adb logcat -d -s AndroidRuntime:E > crash.txt`, quote the top frames
   in the progress log, DO NOT attempt to fix resolver/gesture/engine code — only fix
   crashes whose stack points at TODAY's sprite/avatar UI files, and only if the fix is
   ≤10 lines and obvious. Otherwise record and continue with unaffected tasks.

## TASK 2 — Run the JVM regression harnesses (10 min, no device needed)

Sources: `tools/jvm-harness/*.java`. They compile against the app's last-built classes:

```
cd C:\+Projects\Screenrecorder\FadCam
set CP=app\build\intermediates\javac\defaultDebug\compileDefaultDebugJavaWithJavac\classes
javac -encoding UTF-8 -cp "%CP%;<gson-jar>;<annotation-jar>" -d tools\jvm-harness\out tools\jvm-harness\ResolverGateTest.java tools\jvm-harness\OneEuroTest.java tools\jvm-harness\LifeMathTest.java tools\jvm-harness\DetectorTest.java
java -cp "tools\jvm-harness\out;%CP%;<gson-jar>" ResolverGateTest   (repeat per test)
```
gson jar: search `%USERPROFILE%\.gradle\caches\modules-2\files-2.1\com.google.code.gson`
(any 2.1x), annotation jar under `androidx.annotation\annotation-jvm`. DetectorTest and
LifeMathTest need android.jar on the run classpath too:
`%LOCALAPPDATA%\Android\Sdk\platforms\android-36\android.jar`.
All four must print ALL GREEN. If one fails after a fresh build, that's a REAL
regression — record exactly which check failed and stop touching related code.
Commit the harness folder if TASK 0 didn't already:
`test(harness): JVM regression harnesses for resolver/detector/one-euro/life (relocated from session scratchpad)`.

## TASK 3 — One-line fix: totalEffectiveMs undercounts loop extensions

Documented latent bug (handoff 2026-07-02 L1 notes). In
`app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java`, method
`totalEffectiveMs()` (~line 590): it sums `getTrimmedDurationMs()` per clip and ignores
loop extensions, which breaks pause→resume on looped projects (audio-tail engages
wrongly). Fix: for each clip use exactly the pattern already used elsewhere in the file:
`c.hasLoopExtension() ? c.getVisualDurationMs() : c.getTrimmedDurationMs()`.
Change ONLY that summation. Build green → commit
`fix(playback): totalEffectiveMs counts loop extensions (documented latent audio-tail bug)`.
Device check (nice-to-have): sandbox project has a looped clip — press play from a
pause point past mid-timeline; video should keep playing rather than freeze-with-timer.

## TASK 4 — S5 lane visuals: sprite items get color, label, and swap diamonds

File: `app/src/main/java/com/fadcam/ui/faditor/layers/LayerRowRenderer.java` — ADDITIVE
edits only, do not restructure anything:
1. Next to the other color constants (~line 61):
   `private static final int COLOR_ITEM_SPRITE = 0xDDFFB74D;  // amber (SPRITE, S5)`
2. In `baseColorFor(TrackKind)` add `case SPRITE: return COLOR_ITEM_SPRITE;` before `default`.
3. In `labelFor(TimedItem)` add, before the `getClip()` line:
   ```java
   if (item.getSprite() != null) {
       int keys = item.getSprite().getFrameTrack().size();
       return keys > 1 ? "✦ " + keys : "✦";
   }
   ```
4. In `drawExpandedItems(...)`, after the item body roundRect is drawn and BEFORE the
   selection stroke: if `item.getSprite() != null`, draw a small diamond for each frame
   key: for each `FrameTrack.Key k : item.getSprite().getFrameTrack().keys()`, compute
   `float dx = timeToX.map(item.getTimelineStartMs() + k.timeMs);` skip if `dx < x0+3 ||
   dx > x1-3`; draw a 4dp-radius rotated square (Path: moveTo(dx, cy-r) → lineTo(dx+r,
   cy) → lineTo(dx, cy+r) → lineTo(dx-r, cy) → close) filled white 0xE6FFFFFF, where
   `cy = row.bodyRect.centerY()` and `r = 4f * density`. Reuse one Path field, `rewind()`
   it per diamond. Respect `ghosted` by dropping alpha to 0x66FFFFFF.
Build green → screenshot the sprite row on device (place a sprite + drop a swap first)
→ commit `feat(sprite): S5 lane visuals - amber sprite items, key-count label, frame-swap diamonds`.

## TASK 5 — Palette keyframe context chip (S3 spec leftover)

File: `app/src/main/java/com/fadcam/ui/faditor/sprite/SpritePalettePanel.java`.
In the transport row, after `cellIndicator`, add two chips `◄k` and `k►` plus `✕k`:
- New Callback methods (add to the interface WITH bodies in the activity):
  `void onNudgeKey(SpriteOverlayItem item, int direction)` and
  `void onDeleteKeyAtPlayhead(SpriteOverlayItem item)`.
- Panel shows ✕k only when the playhead sits ON a key (within ±120ms): compute in
  `setPlayheadMs` — `item.getFrameTrack()` keys, local time = `item.toLocalMs(playheadMs)`.
- Activity implementations in `FaditorEditorActivity` (put them next to the existing
  `onCellChipTapped`): nudge = find the key within ±120ms of local playhead, remove it
  (`removeAt`), re-add at `timeMs + direction*100` (100ms steps), record undo with the
  SAME snapshot pattern `onCellChipTapped` uses (copy the before/after key-list
  LambdaAction code); delete = `removeAt` that key with the same undo pattern. Both end
  with `scheduleAutoSave()`, `spriteOverlayView.invalidate()`, panel `setPlayheadMs`.
Build green → device: drop a swap, nudge it twice, delete it, undo all three → commit
`feat(sprite): S3 keyframe context chip - nudge/delete swap at playhead, one undo step each`.

## TASK 6 — Sheet editor onion skin (S2b leftover)

File: `app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheetEditorActivity.java`
(+ `SpriteGridEditorView` if needed). Behavior: a new "Onion" toggle chip next to
"Pivot". When ON and a cell is selected, the CellCyclePreview (bottom-left preview box)
draws the PREVIOUS enabled cell at 35% alpha UNDER the selected cell (use a
`Paint.setAlpha(90)` overload of `renderer.drawCell`). Selected cell index comes from
`gridView.getSelectedCell()`; previous enabled = scan backward, wrap around. Keep it to
the preview box — do NOT overlay ghosts on the main grid canvas (that needs design).
Add string `sprite_editor_onion` ("Onion"). Build green → commit
`feat(sprite): S2b onion skin in the cell preview (previous enabled cell ghosted)`.

## TASK 7 — Avatar Studio: mirror-pose button (MINED authoring win)

File: `app/src/main/java/com/fadcam/ui/faditor/avatar/AvatarStudioActivity.java`.
Add a "Mirror ⇋" chip to the pose-controls strip, enabled only while a cell is ARMED.
On tap: for the ARMED cell (col,row) in the first domain, compute the horizontally
OPPOSITE cell: `mirrorCol = (domain.cols - 1) - armedCol`, same row. If mirrorCol ==
armedCol do nothing (center column). Otherwise REPLACE the opposite cell's poses with a
mirrored copy of the armed cell's poses: for each `PartPose p` — copy all fields, then
`x = -x`, `rotationDeg = -rotationDeg`, `flipH = !flipH`, and mirror pins
(`pin[0] = 1f - pin[0]`). Create the opposite Cell if absent (`domain.cells.add`).
Then `refreshMatrix(); resolveNow();` and toast "Mirrored to the opposite cell".
Add strings `avatar_studio_mirror` ("Mirror ⇋") and `avatar_studio_mirrored`.
DO NOT modify PuppetPoseResolver or AvatarRig. Build green → commit
`feat(avatar): mirror-pose button - armed cell copies flipped to the opposite extreme`.

## TASK 8 — Sidecar IMPORT (S2b completion; export already ships)

Files: `sprite/SpriteSheetEditorActivity.java` (+ strings). Next to the existing
"⇪ .json" chip add "⇩ .json": launches an OpenDocument picker for `application/json`,
reads the file, `SpriteSheet.fromJson(JsonParser.parseString(text).getAsJsonObject())`,
then copies ONLY the slicing metadata onto the CURRENT sheet: cols/rows/margins/spacing
(`setGrid/setMargins/setSpacing`), fps, pivot (`setPivot`), bgKey (`setBgKey`), and cell
names/enabled (clear `getCells()`, add copies). Do NOT overwrite `id`, `name`, or
`sheetUri`. Wrap the whole handler in try/catch → toast failure, never crash. Then
`gridChanged(); reloadRenderer();`. Strings: `sprite_editor_import_sidecar` ("⇩ .json"),
`sprite_editor_sidecar_imported`, `sprite_editor_sidecar_import_failed`. Build green →
device round-trip: export a sidecar (existing chip), change the grid, import it back →
grid restored → commit `feat(sprite): S2b sidecar import - slicing metadata round-trip`.

## TASK 9 (stretch) — sw600dp two-pane sheet editor

Only if everything above is done and green. `SpriteSheetEditorActivity.buildUi()`:
when `getResources().getConfiguration().smallestScreenWidthDp >= 600`, use a HORIZONTAL
root: grid canvas left (weight 0.6), right column (weight 0.4) = cell panel + controls
stacked vertically (move the same views; do not duplicate them). Phone layout unchanged.
Build green + rotate-safe (configChanges already set) → commit
`feat(sprite): S2b two-pane sheet editor on sw600dp`.

---
## PROGRESS LOG (append below — one dated entry per task, honest status)

Format per entry:
```
### <date time> — TASK N: <name> — DONE | PARTIAL | BLOCKED | SKIPPED
Build: <tail line of the gradle run you actually executed>
Commits: <hashes>
Evidence: <project.json fields / screenshot names / harness output lines>
Notes for next AI: <anything surprising, anything owed>
```

(entries start here)
