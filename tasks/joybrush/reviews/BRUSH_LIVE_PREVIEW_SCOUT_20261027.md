# Brush live preview scout (2026-10-27)

Read-only scout. No gradle, adb or git writes. Tags: **[verified in code]** = read in the file named; **[doc only]** = taken from a doc, not checked against code or a device.

Paths are relative to `C:/Temp/jb-select-reconcile-20261024/` unless absolute.

## 1. The web version (Codex watercolour POC)

The web reference is **not in the repo**. It is the owner's read-only POC at `C:/Users/JoyRaptor/Downloads/Watercolor-POC/index.html` (single file, ~5.3 KB of minified-style JS). The repo only points at it:
- `tasks/joybrush/specs/WATERCOLOUR_LINE_BRUSH_PLAN_20261012.md:4-5` says `index.html` is authoritative. [doc only for the pointer; the file itself read in code]
- `tools/watercolour_fixture.js:8,15,35` reads the POC's `index.html` and `approved-watercolor-settings.json` at run time. [verified in code]

What the POC does for sample strokes [verified in code, POC `index.html`]:
- Toolbar button `#demo` labelled "Sample strokes", off by default (`index.html:29`; toggle at line 95, which calls `queuePreview()` when turned on and `render()` when turned off).
- Sample set is three fixed procedural paths (`index.html:92`):
  `samplePaths = Array.from({length:3}, (_,j) => ({seed:78234+j, points: 161 pts ...
  x = 140 + 1100*t`, `y = 180 + j*250 + sin(2πt)*55`, `p = .08 + .92*sin(πt)` (pressure swell), and tilt `t = j===1 ? 30+15*sin(2πt) : j*30` (only the middle path varies tilt).
- Every slider's `input` event goes through `labels()` (line 42: `for(const k in specs) $(k).oninput=labels;` and `labels()` calls `queuePreview()`). Colour, invert, blend and compensate fire `queuePreview` on `change` (line 96).
- Redraw: `queuePreview()` (line 93) sets a 50 ms `setTimeout` plus one `requestAnimationFrame` (coalesced). `redrawSamples()` (line 94) clears the `samples` canvas, scales pressure by `#pressure/65` and offsets tilt by `#tilt-25`, runs `begin/append/composite` for each path, then calls `render()`.
- Composite: `render()` (line 83) draws `committed`, then the `samples` canvas (with the blend mode), then the active stroke. So the sample strokes sit under the person's own strokes.
- Clear (line 90) also sets `samplesVisible=false`, so Clear hides the samples too. Undo (line 91) rebuilds from `strokes` and does not touch samples.
- README: 50 ms coalescing (`README.md:49`); about 46 ms to rebuild all three samples on desktop Edge, not a phone number (`README.md:53`). [doc only for the README numbers]

The Media Lab in the repo has a weaker analogue:
- `joybrush/lab/media/index.html` and `joybrush/lab/media/js/main.js`. Sliders `reach`, `mp`, `mt`, `relief` only set state (`main.js:529-532`). Nothing replays strokes on a slider move. The `Sheet` button (`main.js:547`, `runSheet(SHEETS.proto)`) replays `SHEETS.proto` (`js/tests.js:534`) on demand only. [verified in code]
- `tools/brushlab/BrushLab.html` (+ `README.md`) is a WebGL2 brush lab that runs the phone's own `jb_tip.glsl` / `jb_grain.glsl`. It has sliders that apply to the next stroke, and there are no auto-play sample strokes. The owner draws his own marks. [verified in code: the panel is built from `ctl`/`buildPanel`, `BrushLab.html:1368-1390`; README "What the panel does"] [doc only for the "no samples" conclusion, which follows from the absence of any sample-stroke code]

Nothing in the repo contains the word "swoosh" in code or `.md` (grep, excluding build and held). No stroke is called a swoosh in any version. [verified in code]

## 2. The Android app

### 2a. Hold-brush settings drawer and its preview (the one the owner means)

Entry and wiring:
- Opened by holding a brush in the drawer (`BrushDrawerView`, long click `onSettings`, `BrushDrawerView.kt:182`) or from the menu row "…settings…" (`JoyBrushActivity.kt:1061-1063`). [verified in code]
- `JoyBrushActivity.openSettings(brush)` at `JoyBrushActivity.kt:1078`:
  - Creates a second `JbCanvasView` as the preview (`:1079-1085`): `setZOrderMediaOverlay(true)`, `longPressEyedropper=false`, paper and colour copied from the main canvas.
  - `previewBrush()` (`:1086-1091`) applies `BrushTuning` and the tool's size.
  - `redraw` (`:1092-1097`): `preview.preset = previewBrush()` then `preview.replaceWithStrokes(BrushPreviewStrokes.strokes(w, h))`.
  - `redrawSoon()` (`:1098-1100`) posts `redraw` after `PREVIEW_DEBOUNCE_MS = 40L` (`JoyBrushActivity.kt:166`).
  - `preview.onReady = { redrawSoon() }` (`:1102`). **So the preview auto-plays once the surface exists, as soon as the sheet opens.**
  - `BrushSettingsView(...)` (`:1104`) gets `onChange` (every slider move: `tuning` updated, `applyTool()`, `redrawSoon()`, save on finger-up), `onReset`, `onTest`, `onDone`, and `editableLines`.
- `BrushSettingsView.kt` (`joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/chrome/`):
  - Header: Reset (`:85-89`), **Test** (`:90`, draws the tuft test sheet on the MAIN drawing, not the preview), Done (`:91`). **No Clear and no Undo button for the preview.**
  - `kit.label(preview, "Live preview of … You can draw on it too")` and the preview is added at height `PREVIEW_DP = 130f` (`:94-95`, constant at `:228`).
  - Every knob from `BrushKnobs.forBrush(base)` becomes a SeekBar (`:120-121`). `row()` (`:146-180`) calls `positions += …` then `onChange(positions, false)` on each move, and `onChange(positions, true)` on release (`:170-172`). Checkboxes (`checkRow`, `:182-`) call `onChange(…, true)`.
  - Pressure and tilt curve editors (`:51-52`, `curveMoved` `:64-75`) also feed `onChange`.
- The preview is a real `JbCanvasView`, so the person's own touch input goes through its normal `onTouchEvent` (`JbCanvasView.kt:1459`). Nothing disables it. [verified in code]

What the preview draws (the settings preview) [verified in code]:
- `BrushPreviewStrokes.strokes(w, h)` (`joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/chrome/BrushPreviewStrokes.kt:31-107`). Positions are screen px, times in ms, pure and fixed. About 13 strokes:
  - one long stroke (`:37-48`) walking hairline → line weight → pressed flat, tilt up to 1.15, speed up to 1.6 px/ms, then a quick lift;
  - three hair spikes (`:54-59`), a switchback run of four legs at pressure 0.62 (`:61-70`);
  - two "owner's circles" (`:74-91`): one pressed point with the pen turned round it (azimuth sweeps), one point walked round a rim with the base over the centre;
  - a heavy and a light shadow pass at the same angle (`:93-96`);
  - a jolt, pressure alternating 0.15 / 1 at speed 2.4 (`:98-99`);
  - three whiskers, light and fast (`:101-105`).
  - Pen lean is fixed up-left (`LEAN = -0.75π`, `:29`).
- Those samples go through `drawStrokes` (`JbCanvasView.kt:2269-2288`). Each stroke gets `startStroke(eraser=false, watercolourPreviewSeed=…)` (watercolour seeds per index from `preset.watercolour.sampleSeeds`, `:2275-2277`), then the real smoother and response curves (`feedOne` at `:2320`, `strokePreset?.response?.apply(raw)` at `:2340`). **So the brush's own pressure/tilt curves are applied to the preview.**
- `replaceWithStrokes` (`JbCanvasView.kt:2302-2313`) makes a fresh single layer and calls `engine.resetDocument()`, then `drawStrokes`. The doc comment says it "empties this canvas WITHOUT an undo step and lays [strokes] afresh." **Anything the person drew on the preview is wiped on the next slider move.**
- `drawStrokes` is one undo step (`:2285`, `mergeNewest`), but the preview has no undo control in the sheet.
- The preview's pen dot and curve markers follow the pen (`openSettings` lines `:1134-1136`, `onPenReading`, `JbCanvasView.kt:2356`).

### 2b. The brush drawer thumbnails (separate from the settings preview)

- `BrushDrawerView.kt:201-205` `sample()`: first a stand-in (`SampleStroke.dabs`, soft discs, `joybrush/core/.../chrome/SampleStroke.kt`, 64 samples, one S, no grain or media). [verified in code]
- The real picture: `JoyBrushActivity.brushPreviews` (`:251-262`) is a `BrushPreviewCache` that calls `canvas.renderBrushPreview(preset, w, h, style, done)`. Requests are made per visible row (`askVisible`, `BrushDrawerView.kt:117-135`). Files are stored in `cacheDir/brush_previews`, keyed by `BrushPreviewKey` (`joybrush-android/.../chrome/BrushPreviewKey.kt`, `RENDERER_VERSION = 1`).
- Renderer: `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/preview/BrushPreviewRenderer.kt`.
  - `start()` (`:168-176`) routes by `PreviewKind`: FILL → `renderFill` (`:271`), MEDIA_DRY/WET/PASTE → `startMedia` (`:284`, `MediaPreviewJob`), everything else → `renderStroke` (`:206`).
  - `renderStroke` (`:206-267`) covers STAMP (`StampPreview.build`), TUFT (`TuftStroke`), SMUDGE (two colour bands, `:217-219`), WATERCOLOUR line (`WatercolourDabber`, cold-press paper, `:229-240`), and erase brushes (`:220-222`: a strip the eraser cuts, `erase = PreviewPlan.erases`).
  - Each row draws ONE stroke: `CannedStroke.pen(w, h, shown.size.base)` (`CannedStroke.kt:40-60`), a single S from left to right, pressure 0.25→1→0.25, tilt 0.4→0.85 rad, lean up-left. The fill pen uses `CannedStroke.loop` (`:66`).
  - `PreviewPlan.kindOf` (`PreviewPlan.kt:37-49`) decides the engine path. Media brushes with no media section give `NONE` (nothing drawn).
- Thumbnails never consult `editableLines` (`BrushPreviewRenderer.kt` has no reference). [verified in code by reading the renderer]

### 2c. Engine coverage, settings preview vs thumbnails

| Engine | Thumbnail (BrushPreviewRenderer) | Settings live preview (drawStrokes on JbCanvasView) |
|---|---|---|
| Stamp / push / wet | yes, `StampPreview` | yes, canvas stamp path |
| Tuft (Sable) | yes, `TuftStroke` | yes, canvas tuft path (`paintTuft`) |
| Smudge | yes, two bands | yes, but `drawStrokes` always passes `eraser=false` and paints ink (`:2279`) |
| Fill pen | yes, closed blob | yes via canvas, but the preview strokes are OPEN curves, so the fill result is unverified [not checked] |
| Watercolour line | yes, on cold-press | yes, watercolour seeds per stroke (`:2275-2277`) |
| Media pencil / watercolour / oil | yes, `MediaPreviewJob`, MEDIA_* kinds | routes to canvas media path (`JbCanvasView.kt:1935`, `:1983` `startMediaStroke`); pixels not checked |
| Erase brushes (blend "erase") | yes, strip cut | **no**: preview is painted as ink (`eraser=false`) [verified in code] |
| Ink / vector "lines" (editable lines) | no separate path; thumbnails are pixels | **no**: `canvas.editableLines` is a per-canvas flag (`JbCanvasView.kt:607`), the preview's flag is never set. `openSettings` changes only the main canvas (`JoyBrushActivity.kt:1130`, `setEditableLines` `:2660-2667` writes `canvas.editableLines`). So the preview always shows pixels. [verified in code] |

## 3. Gaps: web POC vs the Android settings preview

Ordered by what the owner asked for.

1. **Clear for the preview: missing.** The app has no clear control in the settings sheet (`BrushSettingsView.kt:85-91`). The web POC has Clear and hides samples (`index.html:90`).
2. **Undo for the person's own preview strokes: missing.** The web has Undo stroke (`index.html:91`). The app's preview has none.
3. **Person's strokes on the preview are wiped by any slider move.** `replaceWithStrokes` resets the document (`JbCanvasView.kt:2307`). The web keeps the person's strokes on top and only redraws the samples layer (`index.html:83, 94`).
4. **Erase brushes do not show erasing in the settings preview.** The thumbnail does (`BrushPreviewRenderer.kt:220-222`); the live preview paints ink (`JbCanvasView.kt:2279`).
5. **Editable-lines (vector) mode is never previewed.** The preview canvas's `editableLines` is never set (`JoyBrushActivity.kt:1130` only updates the main canvas).
6. **Two different stroke sets.** Thumbnails: one `CannedStroke.pen` S. Settings: `BrushPreviewStrokes` (13 strokes). The owner sees different marks in the row and in the sheet. This is a design choice that should be stated, not a bug.
7. **Auto-play on open is in the app, opt-in in the web.** The app auto-plays on open (`JoyBrushActivity.kt:1102`). The web needs "Sample strokes" pressed (`index.html:29, 95`). Parity question for the owner, not a defect.
8. **Mouse-style pressure and tilt scalers are missing.** The web scales the samples by its "Mouse p" and "Mouse tilt" sliders (`index.html:94`, the "Mouse / touch test inputs" block at `index.html:20`). The app's samples are fixed (`BrushPreviewStrokes`), which is correct for the S Pen, but there is no way to test a range of pressures without the pen.
9. **Redraw cost is not measured on the phone.** The web reports rebuild ms and stamp counts (`index.html:94` status line). The app reports nothing and redraws all ~13 strokes on every slider tick (40 ms debounce, `JoyBrushActivity.kt:166`). Phone timing is an open question [not verified].
10. **Web is watercolour-only; the app is every brush.** The web POC only has watercolour knobs. The app's knobs come from `BrushKnobs.forBrush` (`joybrush/core/.../chrome/BrushKnobs.kt:74-83`) per engine. No gap in coverage, but any web-side testing does not cover the other engines.
11. **Fill pen with open preview strokes: unverified.** The fill pen needs closed shapes and the settings preview draws open curves. Not checked on device or in a test.

Not gaps: the app's preview does re-render from the real response curves and real brush settings (`feedOne`, `JbCanvasView.kt:2320-2340`), the same thing the web POC does through its own pressure and tilt curves (`index.html:52-56`).

## 4. Other facts

- The owner's request for a settings preview with tilt/pressure/speed is recorded: `tasks/joybrush/research/R9_watterson_sable_ink.md:337` and `tasks/joybrush/specs/DIGITAL_PEN_SPEC_20261016.md:99`. [doc only]
- `tasks/joybrush/research/HANDOFF_paper_texture.md:46,63` says every new knob must show in the live preview. [doc only]
- Existing tests: `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/chrome/BrushTuningTest.kt:216-225` checks `BrushPreviewStrokes`; `joybrush-android/src/test/.../chrome/BrushLineModeUiTest.kt` and `WatercolourBrushSettingsTest.kt` construct the sheet. Test results not run (no gradle per brief). [verified in code: tests exist]

## 5. Not checked

- Nothing was built or run. No phone or emulator run.
- No device timing or visual result for the settings preview.
- Web POC not opened in a browser; read as source only.
