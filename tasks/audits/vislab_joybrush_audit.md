# VisLab and Joy Brush: read-only audit

Date: 2026-10-15. Branch: joy-creator. Method: static read of the main tree only. Nothing was built, run, or edited. The `.claude/worktrees/` folders hold older copies of the same files and were excluded from every search. Counts marked "rough" come from simple text matches and are approximate.

---

## 1. VisLab (the visualiser)

### Summary
VisLab has no room or screen of its own. Its real working parts are the visualiser overlay inside the Studio editor, a set of style files, and a live visualiser in the recorder. The lobby "Viz Lab" card looks like a room but sends the user to the Studio project list.

### Entry points (how a user gets in)
| Route | Where | What happens |
|---|---|---|
| Lobby "Viz Lab" card | `app/src/main/java/com/fadcam/ui/lobby/LobbyFragment.java` (room built around line 470; `enterRoom()` around line 1137) | Card says "LAST VISUALISER" with action "Start". It has no screen. `enterRoom()` falls through to `routeTab(TAB_STUDIO)` (line ~1182). The code comment says "Viz Lab has no room yet". |
| Studio editor, Visualizer tool | `FaditorEditorActivity.java` line 3575 calls `addWaveformVisualizer()` (line ~20430) | Needs a selected clip. Adds a `WaveformOverlayInstance` (default style "spectrum_mirror") to the timeline. If the clip is silent or an image, it reads the audio track instead. |
| Studio, per-visualiser drawer and style picker | `FaditorEditorActivity.java`, `showVisualizerStylePicker()` (around line 20530) | Lists built-in styles plus user styles from the project's pinned folder. Save, export and import of styles live near lines 24530-24600 and 21217-21234. |
| Recorder, live visualiser | `FadRecHomeFragment.java` (around line 1076); `ScreenRecordingService.java` (lines 974-998) | Lets the user pick a live style. Only built-in styles are offered. |
| Debug screen | `app/src/main/java/com/fadcam/ui/faditor/waveform/WaveformDebugActivity.java` | Synthetic data only. Opened with `adb`. See "Unfinished" below. |

Joybot cannot route to VisLab. The `Room` enum in `app/src/main/java/com/fadcam/ui/faditor/ai/joybot/IdeaEngine.java` (line 38) has no VisLab entry.

### Screens and activities
- No VisLab activity exists. The visualiser UI is built in code, not XML. There are no waveform or visualiser layout files in `app/src/main/res/layout`.
- `WaveformVisualizerSettingsSheet.java` is the settings sheet. It uses Studio tokens for its text and borders.
- `TapeWaveformRenderer`, `WaveformStyleRenderer` and `WaveformOverlayView` draw the visualiser on the timeline and in the preview.

### Save and load
- **Inside a Studio project (main path).** Each visualiser is saved in the project file as part of `timeline.waveformOverlays` (schema v7). The save code is in `app/src/main/java/com/fadcam/ui/faditor/project/ProjectStorage.java` around lines 2569-2645. The load code is around lines 3534-3624. Saved fields include the style id, the audio source, the time range, position and size, and an optional custom layer stack.
- **Styles, built in.** Loaded by `WaveformStyleIO.loadBuiltins()`.
- **Styles, user-saved.** Each style is a human-editable `<id>.waveform.json` file written to the project's pinned folder (`WaveformStyleIO.saveToPinned`, line ~75). The pinned folder is chosen per project by the user (`FaditorProject.setPinnedAssetDir`). It is not one shared library and it is not the Joy Creator folder.
- **Analysis caches.** Waveform analysis is kept with `DurableCache` under the app's files folder (`app/src/main/java/com/fadcam/ui/faditor/util/DurableCache.java`, line ~33).
- **Export.** `ExportManager.java` resolves the effective style (line ~5340), loads built-in styles (line ~3706), and runs waveform analysis in the background before rendering (lines ~2009-2041). Visualisers are rendered into the exported video.

### Can output move between VisLab and Studio?
- **Into Studio:** Yes. A visualiser is a Studio overlay. The "Save" action writes a style file that can be picked again in any project that has the same pinned folder.
- **Out of Studio to the recorder:** Partly. Only built-in styles can be armed for live recording. `LiveVisualizer.loadLiveEligibleBuiltins()` (line ~101) and `resolveArmedStyle()` (line ~145) fall back to the first built-in if the chosen id is not a built-in. A style saved from Studio cannot be used live.
- **Out of Studio as a file:** Yes. Export writes the style JSON through the file picker. Import can apply it to an overlay for the session.

### What looks unfinished
1. **The lobby card has no destination.** A visible room card ("LAST VISUALISER", "Start") lands on the Studio project list. The visualiser library described in the navigation specs (`tasks/NAV_SPEC.md`, row 21: "VizLab (no code)") has not been built.
2. **`WaveformDebugActivity` ships in release builds.** It is declared in the main manifest, `app/src/main/AndroidManifest.xml` line 187, with `android:exported="true"`. Other debug tools are declared only in `app/src/debug/AndroidManifest.xml`, so this one is inconsistent. The activity is a "Phase 0" debug screen that uses synthetic data. Its toasts and buttons are hard-coded English (for example "Export failed", "Import style JSON"), and it uses `Color.WHITE` at line 145. The other references to it (`FadRecHomeFragment.java` line 1093, `FaditorEditorActivity.java` around lines 326 and 20655, `LiveVisualizer.java` line 112) are comments only, so moving the activity out of release looks safe. Confirm with a build.
3. **No VisLab tests.** A text search of `app/src/test` and `app/src/androidTest` found no test touching `WaveformStyleIO`, `VizLayer`, `LiveVisualizer`, `TapeWaveformStyle`, `BeatDetector` or `TrackAligner`.
4. **Joybot has no VisLab room.** See the entry points table.

### Not unfinished (checked)
- `BeatDetector` is used by `EditorTimelineView.java` (line ~6606) for beat snapping.
- `TrackAligner` is used by `FaditorEditorActivity.java` (line ~44494) for clap sync.
- `LiveAmplitudeSampler` is used by `LiveVisualizer`.
- `LiveVisualizer.sampleWaveformData()` is synthetic data, but it is used only for previews.

### Old-look UI (where it departs from Studio tokens)
Studio tokens are defined in `studiokit/src/main/java/com/fadcam/ui/faditor/Studio.java`.
- `FaditorEditorActivity.java` uses Studio tokens about 264 times. In the visualiser region only 3 hard-coded colours remain (lines ~686-689), and those come from the style JSON.
- `WaveformVisualizerSettingsSheet.java`: text and borders use Studio tokens. The swatch palette at lines 70-72 is hard-coded (red, orange, grey and so on, including `0xFF555555`). Line 111 uses the drawable `picker_bottom_sheet_dark_gradient_bg`, which is not a Studio token. Check it on the phone.
- `TapeWaveformStyle.java` lines 25-28 hard-code three band colours (red, sky blue, yellow). These are preset style data, so they are probably acceptable.
- `WaveformDebugActivity.java` uses `Color.WHITE` and English strings. It is a debug screen, so this matters less.

---

## 2. Joy Brush (paint and animate)

### Summary
Joy Brush is the most complete of the two. It has its own module (`joybrush/` for the engine and `joybrush-android/` for the Android screens), about 316 test files, and a working drawing library. Its one-way hand-off into Studio creates a brand-new project every time, and nothing flows back from Studio into a drawing.

### Entry points
| Route | Where | What happens |
|---|---|---|
| Lobby "Joy Brush" room | `LobbyFragment.java` (room built around line 480; `enterRoom()` around line 1152) | Opens the most recent drawing. If there is none, it creates a new one. Card action "NEW DRAWING". The room is appended last so the other room indexes do not move. |
| Drawing library | `DrawingsActivity.kt` (`joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/library/`). Opened from `LobbyFragment.java` line ~2032, `ChatAssistantActivity.java` line ~2009, and the Joybot help topics in `HelpBook.java`. | Grid of drawings with thumbnails. |
| Drawing screen | `JoyBrushActivity.kt`. Not exported (`app/src/main/AndroidManifest.xml` lines 143-146). Opened through `DrawingLibrary.openIntent()`. | The painting screen. |
| Benchmark | `BenchRunner.kt` and `app/src/debug/java/com/fadcam/joybrush/JoyBrushBenchActivity.java` | Gated on the debuggable flag (`BenchRunner.spec()`, line 276-277) and declared only in the debug manifest. Correct as built. |

### Screens and activities
- `DrawingsActivity` (library), `JoyBrushActivity` (drawing screen), `JoyBrushBoardImportActivity` (in the app, receives a board from the drawing screen).
- Board and export UI: `board/` (`BoardChromeView`, `BoardExportCoordinator`, `ExportChoiceSheet`, `BoardRuntimeController`, `BoardPanels`).
- Tool UI: `chrome/`, `select/`, `library/`.

### Save and load
From `DrawingLibrary.kt`:
- **Working copies:** `<external files>/joybrush/`. `current.joybrush` is the first drawing; new ones are `drawing-<time>.joybrush`. Private to the app and removed on uninstall.
- **The person's copy:** `<Joy Creator folder>/<Name>.joybrush`, written shortly after each save. If the person moves or renames it, it is found again by the drawing's id.
- **Index and thumbnails:** `library.json` and `thumbs/<file>.png` beside the working copies.
- **Deleting:** goes to the Joy Creator folder's single Trash.
- **Recovery, backups and history:** handled by the drawing screen itself (`JoyBrushActivity` and `joybrush/core/.../io/SaveQueue.kt`).

### Export formats
From `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/chrome/BoardExportLayout.kt` (line 10): GIF, PNG frames, sprite sheet, PNG, "Send to Studio", "Open in SpriteLab". The four chip formats are GIF, PNG frames, sprite sheet and Send to Studio.

### Can output move between Joy Brush and Studio?
- **Joy Brush to Studio: yes, but only as a new project.** "Send to Studio" (or "Open in SpriteLab") packs a sprite-sheet PNG and a JSON sidecar. `JoyBrushBoardImportActivity.java` then creates a new `FaditorProject` every time. With Studio chosen, it adds the sheet as a sprite overlay, adds a black canvas clip, and opens the new project. With SpriteLab chosen, it opens the sprite-sheet editor instead. There is no option to add the sheet to an existing Studio project.
- **Studio to Joy Brush: no path exists.** Nothing in the app opens a Studio clip, frame or image inside a drawing. The only app code that opens Joy Brush screens is the lobby and the chat assistant.

### What looks unfinished
1. **Send to Studio always creates a new project.** See above. This is the largest integration gap in Joy Brush.
2. **No way into Joy Brush from Studio** (see above).
3. **Untracked, unwired work.** `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/chrome/TuftTuningView.kt` (new, not in git) is not referenced by any screen. `joybrush/core/.../chrome/TuftKnobs.kt` (new, not in git) is used only by `TuftTuningTest.kt`. Per the "save the orphans" rule in the memory notes, these should be finished or parked, not deleted. The owner decides which.
4. **English strings in code.** `joybrush-android` has no `strings.xml`; its only values file is `jb_tokens.xml`. A rough text match found about 166 literal UI strings in the Kotlin source. They cannot be translated or changed in one place.
5. **Not a problem, checked:** the bench and the debug lifecycle probe are both gated on the debuggable flag. Joy Brush's activities are not exported.

### Old-look UI (departures from Studio tokens)
Joy Brush keeps its own token file, `joybrush-android/src/main/res/values/jb_tokens.xml`. Its header says the room pair is owned by the owner and the rest mirror `studio_tokens.xml`. `tools/check_joybrush_tokens.py` enforces this for the XML only.
- `library/DrawingsActivity.kt` lines 220-223 define a private palette: `GROUND 0xFF050507`, `PANEL 0xFF1C1C22`, `INK 0xFFE9E9EE`, `MUTED 0xFFA1A1AC`. These do not match Studio. Studio's `PANEL` is `0xFF111114`, Studio's `INK` is `0xFFE4E4E7`, and `0xFF1C1C22` is Studio's `RAISED`. This is the most visible mismatch because it is the first screen people see.
- `board/BoardChromeView.kt` lines 353-480 hard-code the board and chrome colour table (for example `0xfff2f2f5`, `0xff22d3ee`, `0xfff43f8e`). Some values match Studio's state colours but are not referenced from the tokens.
- `chrome/LayerColumnView.kt` lines 229-230, `chrome/PaperSheetView.kt` lines 136-279 (paper greys; probably intentional), `select/SelectBarView.kt` line 113, `select/SelectRowView.kt` lines 51 and 147, `select/SelectCountView.kt` lines 38 and 313, `select/SelectToast.kt` lines 140 and 221. These use Joy Brush's own ink pair (`0xFF121214` and `0xFFF7F7F9`), which differs from Studio's ink.
- `JoyBrushActivity.kt` line 2258 and `board/BoardExportCoordinator.kt` line 53 use `Color.WHITE`.
- Rough total: 48 hard-coded colour lines across 14 Joy Brush Kotlin files.

---

## 3. Eight highest-value tasks for launch

Ordered by value to a launch. Each is a task for a later session; none has been started.

1. **Keep the debug activity out of release builds.**
   Move the `WaveformDebugActivity` declaration from `app/src/main/AndroidManifest.xml` (line 187) into `app/src/debug/AndroidManifest.xml`, and move the class to `app/src/debug/java/com/fadcam/ui/faditor/waveform/`. Confirm with a release build that nothing else refers to it.
   Files: `app/src/main/AndroidManifest.xml`, `app/src/debug/AndroidManifest.xml`, `app/src/main/java/com/fadcam/ui/faditor/waveform/WaveformDebugActivity.java`.

2. **Give the lobby "Viz Lab" card an honest destination.**
   Either open the last Studio project and add a visualiser directly (the minimal path: call `addWaveformVisualizer()`), or hide the card until a real room exists. Do not leave a card that says "LAST VISUALISER" and opens a list.
   Files: `app/src/main/java/com/fadcam/ui/lobby/LobbyFragment.java` (`buildRooms()` around line 470, `enterRoom()` around line 1137), `app/src/main/res/values/strings.xml` (`lobby_room_viz`, `lobby_empty_viz`), `app/src/main/java/com/fadcam/ui/faditor/ai/joybot/IdeaEngine.java`.

3. **Make Joy Brush's "Send to Studio" add to a project, not always create one.**
   Let the person choose between a new project and an existing one. This needs a project picker in the hand-off, and the existing project must be reloaded and saved.
   Files: `app/src/main/java/com/fadcam/ui/faditor/sprite/JoyBrushBoardImportActivity.java` (the `new FaditorProject(...)` path), `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/JoyBrushActivity.kt` (lines ~298-321 and ~1821), `joybrush/core/.../chrome/BoardExportLayout.kt`, `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/board/BoardExportCoordinator.kt`.

4. **Make visualiser styles one shared library.**
   Today a style lives only in the pinned folder of the project that saved it, and the recorder reads built-ins only. Store user styles in one place (the Joy Creator folder is the natural home, per the folder design) and read them from the Studio picker and the recorder's live list.
   Files: `app/src/main/java/com/fadcam/ui/faditor/waveform/WaveformStyleIO.java` (`loadUserStyles` line ~46, `saveToPinned` line ~75), `app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java` (`showVisualizerStylePicker` around line 20530, save around line 24530), `app/src/main/java/com/fadcam/ui/faditor/model/FaditorProject.java` (`setPinnedAssetDir` line ~387), `app/src/main/java/com/fadcam/visualizer/LiveVisualizer.java` (lines ~101-155), `app/src/main/java/com/fadcam/fadrec/services/ScreenRecordingService.java` (line ~989), `app/src/main/java/com/fadcam/fadrec/ui/FadRecHomeFragment.java` (line ~1076).

5. **Move Joy Brush's library screen onto the shared Studio tokens, then the rest of Joy Brush.**
   Start with `DrawingsActivity`, whose private palette does not match Studio. Then move the board and select colours onto `jb_tokens.xml`. Extend `tools/check_joybrush_tokens.py` to check Kotlin colour literals, not just XML, so drift is caught.
   Files: `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/library/DrawingsActivity.kt` (lines 220-223), `board/BoardChromeView.kt`, `chrome/LayerColumnView.kt`, `select/*.kt`, `joybrush-android/src/main/res/values/jb_tokens.xml`, `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/JbColors.kt`, `tools/check_joybrush_tokens.py`.

6. **Add the missing Studio palette drift check.**
   `Studio.java` says `tools/check_palette.py` "fails if they ever drift". That file does not exist in the repo, so the "one place of truth" claim is not enforced. Create the check (it can follow `check_joybrush_tokens.py`) so `Studio.java` and `studio_tokens.xml` are compared on every change.
   Files: `studiokit/src/main/java/com/fadcam/ui/faditor/Studio.java` (header comment), `app/src/main/res/values/studio_tokens.xml`, `tools/` (new script).

7. **Add tests for the visualiser before launch.**
   Cover: a style JSON round-trip through `WaveformStyleIO`, the tolerant read of a broken or hand-edited style, `saveToPinned` and `loadUserStyles`, a `ProjectStorage` save-and-reload of `waveformOverlays`, `TrackAligner.align`, and `BeatDetector.snap`. Joy Brush already has about 316 test files, so it needs no equivalent work now.
   Files: new tests under `app/src/test/java/com/fadcam/ui/faditor/waveform/`; `model/VizLayer.java`, `waveform/WaveformStyleIO.java`, `project/ProjectStorage.java` (save around lines 2569-2645, load around lines 3534-3624), `waveform/TrackAligner.java`, `waveform/BeatDetector.java`.

8. **Give Joy Brush its own string resources.**
   Move the roughly 166 hard-coded English strings into a `strings.xml` in `joybrush-android`, so they can be translated and changed in one place, and so the wording matches the rest of the app. This is also the place to decide the untracked `TuftTuningView.kt` and `TuftKnobs.kt` work: finish it or park it under `tasks/`, but do not delete it.
   Files: new `joybrush-android/src/main/res/values/strings.xml`; `DrawingsActivity.kt`, `JoyBrushActivity.kt`, `select/*.kt`, `board/*.kt`, `chrome/TuftTuningView.kt` (untracked), `joybrush/core/.../chrome/TuftKnobs.kt` (untracked).

---

## 4. Other notes
- `tasks/joybot/WINGS_INVENTORY_20261014.md` is accurate on the points checked: VisLab has no room, Joy Brush opens through `DrawingLibrary.openIntent`, and Joybot has no room navigation.
- The `joybrush-android` JbColors comment says the mirrored colours are "copied rather than" read from the app. That is the intended design, and the drift check covers it for XML only.
- Git status at the start of the session showed many untracked files, including the Tuft files above. This audit did not commit or move anything.
