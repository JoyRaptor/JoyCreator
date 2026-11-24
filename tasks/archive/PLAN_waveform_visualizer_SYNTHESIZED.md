# Waveform Visualizer Studio — Synthesized Plan (LOCKED 2026-06-19)

Reconciles `feature-visualizer-studio-spec.md`, the "Waveform Visualizer Studio" proposal, and the
critique. **This doc wins** where they conflict.

## Locked decisions
- **Render path:** Canvas/Paint → software `Bitmap` (ARGB_8888) → Media3 `OverlayEffect` — the SAME
  pipeline as text overlays. No third-party waveform libs, no GL/EGL in the draw path (avoids
  context-ownership bugs). Zero APK bloat.
- **Animated / audio-reactive** (user choice): `render()` takes a timestamp and is called per frame.
  Bars pulse with the audio. Cache the *extracted data*, NOT a single output bitmap.
- **Frequency spectrum INCLUDED in v1** (user choice): extraction computes BOTH peak amplitude per
  time bucket AND FFT magnitude per frequency band per bucket. Styles can use either.
- **Dedicated model + cache:** `WaveformOverlayInstance` + cached `WaveformData` (per source, in
  cache dir). Do NOT reuse the coarse `AudioClip.waveform` field (wrong resolution).
- **Shared preset format + live reuse:** preset JSON works in both editor and (later) live recording.
  Live recording reuse is Phase 4 and needs the recording pipeline documented first.

## Components
- `model/WaveformData.java` — `float[] amplitudes` (0..1), `float[][] spectrum` (bucket × band, 0..1),
  `long durationMs`, `long bucketMs`. One extracted, cacheable structure.
- `ui/faditor/waveform/Fft.java` — tiny radix-2 FFT (no dependency), used by the extractor.
- `ui/faditor/waveform/WaveformExtractor.java` — MediaExtractor+MediaCodec decode → PCM → amplitude
  buckets + per-band FFT magnitudes; background ExecutorService + callback; caches to
  `<cacheDir>/waveform/<hash>.bin`. **(Phase 1 — not needed for Phase 0.)**
- `model/WaveformStyle.java` — Gson POJO (see JSON below).
- `ui/faditor/waveform/WaveformStyleRenderer.java` —
  `Bitmap render(WaveformData data, WaveformStyle style, int w, int h, long atMs)`. Software canvas.
  Types: `bars`, `line`, `mirror_bars`, `filled_wave`, `spectrum_bars`. Glow via `BlurMaskFilter`.
- `assets/waveform_styles/*.json` — built-in presets.
- `ui/faditor/waveform/WaveformStyleIO.java` — load built-ins from assets; SAF import/export (Phase 3).
- `model/WaveformOverlayInstance.java` — `startMs,endMs,xPercent,yPercent,widthPercent,heightPercent,
  rotationDeg,styleId,audioSourceRef`. Lives in `Timeline.waveformOverlays`.
- Schema: bump `SCHEMA_VERSION` to 7; `Timeline` gains `waveformOverlays`; `ProjectStorage` serializes it.
- Export: `ExportManager` composites each waveform overlay per-frame via the overlay-bitmap path.
- Studio UI: template gallery (live mini-renders), custom sliders/color pickers, import/export.

## Style JSON
```json
{ "id":"neon_bars_v1","displayName":"Neon Bars","type":"bars",
  "color":"#00E676","gradientStart":"#00E676","gradientEnd":"#00BFA5",
  "barWidthDp":3,"barGapDp":1,"cornerRadiusDp":2,
  "glowColor":"#CC00E676","glowRadiusDp":8,
  "bandCount":48,"sensitivity":1.0,"useSpectrum":false }
```
`type:"spectrum_bars"` (or `useSpectrum:true`) draws from `spectrum`, else from `amplitudes`.

## Progress (2026-06-19, via restored watcher loop)
- **Phase 0 — DONE, visually verified** (adb screenshot: all 5 presets render distinctly, glow/gradient/
  mirror/spectrum all correct). `WaveformData`, `WaveformStyle`, 5 preset JSONs, `WaveformStyleRenderer`
  (animated, takes `atMs`), `WaveformStyleIO`, `WaveformDebugActivity` (manifest-registered, exported).
- **Phase 1 engine — DONE, compiles.** `Fft` (radix-2) + `WaveformExtractor` (MediaCodec→PCM→streaming
  amplitude+FFT bands, memory-flat, disk cache in `<cacheDir>/waveform/`).
- **Phase 1 data layer — DONE, compiles.** `WaveformOverlayInstance`, `Timeline.waveformOverlays`,
  `SCHEMA_VERSION=7` + `ProjectStorage` ser/deser, `ADD_VISUALIZER` EditScript op (validate+apply).
- **Phase 1 editor preview — BUILT & compiles, pending visual verify.** `WaveformOverlayView` in
  `player_container` (sized to canvas, driven by `updateCurrentTimeDisplay`'s playhead);
  `refreshWaveformOverlays()` binds overlays + kicks off cached extraction on load/undo;
  a **"Visualizer" tool button** (`tool_visualizer`, graphic_eq icon) places a `spectrum_neon`
  visualizer over the selected clip via `addWaveformVisualizer()`. **To verify:** open a project,
  select a clip with audio, tap Visualizer, scrub/play → spectrum should animate over the audio.
  (Source-time mapping is currently local `playhead - overlay.start`; refine for trim/speed in Phase 2.)
- **Phase 1 — VISUALLY CONFIRMED on device (2026-06-20).** User sees the visualizer; it animates.
  Added: faint placeholder while extracting; **drag-to-move, pinch-resize, tap-select (blue dashed
  box), long-press-delete** in `WaveformOverlayView` (view brought to front when overlays exist so it
  gets touches); extraction in-flight guard + completion logging; primed playhead on refresh.
- **Bug fixed (2026-06-20):** schema-v6 `project://` URI was handed raw to the project-list thumbnail
  extractor (`ProjectStorage.listProjects` now resolves it via `fromStorageUri`).
- **Known follow-ups:** (a) ~~extraction of long clips is slow with no progress~~ — **DONE**: progress
  feedback (placeholder fill) shipped earlier, and **covered-span extraction shipped 2026-06-20** (see
  below); (b) ~~source-time mapping is approximate~~ — **DONE** (`mapToSourceMs`, exact for trim/speed,
  preview + export); (c) `WaveformOverlayView` allocates a bitmap per frame — cache per bucket (still
  open, but `renderReusable` already avoids per-frame alloc in practice); (d) webcam doesn't rotate in
  landscape (recording pipeline — deferred to the recording/dual-stream work).
- **Covered-span extraction — DONE, compile-green 2026-06-20. VISUAL VERIFY.** `WaveformExtractor`
  gained `extract(uri,bands,startMs,endMs,progress)`: seeks `SEEK_TO_PREVIOUS_SYNC` to the span start
  and decodes only `[startMs,endMs]`, stopping early once the decoder passes `endMs`. `WaveformData`
  carries `startOffsetMs` + exact `bucketMs`; `bucketAt` offsets by `startOffsetMs`. Cache →
  **VERSION 3**, span-aware key (`<hash>_<bands>_<start>-<end>`/`…_full`). Preview + export both extract
  the clip's used window `[inPoint-1200, outPoint+200]` (shared span = shared cache + visual parity).
  Full-source path unchanged/backward-compatible. Verify: trim a clip deep into a long source, add a
  visualizer, scrub → bars track the trimmed audio; `logcat | grep "Waveform extract"` shows `span=` +
  a small `buckets=` count.
- **Phase 2 export compositing — DONE, compiles (2026-06-20). VISUAL VERIFY pending.** In
  `ExportManager`: `prepareWaveformBindings()` runs on the export PREP background thread (audio decode
  is heavy; `buildComposition` runs on the main Looper) — it resolves each `WaveformOverlayInstance`
  to its `WaveformStyle` (from `WaveformStyleIO.loadBuiltins`) + extracted `WaveformData`
  (`WaveformExtractor.extract`, 64 bands = **same band count + disk cache as the editor preview**,
  using `getExportUri(clip)` so remuxed FadRec sources are read). `WaveformExportOverlay extends
  BitmapOverlay` re-renders per frame via `WaveformStyleRenderer.renderReusable` (reused-bitmap
  pattern proven by `CaptionExportRenderer`; the patched Media3 re-uploads each frame), placed via a
  background-frame anchor like the text overlays, alpha 0 outside its output time range. Hooked into
  `buildOverlaysForItem` so visualizers bake on every clip range, fade-in head, and transition tail —
  exactly where text overlays do. `isSimpleTrim` now also excludes `hasWaveformOverlays()` so a
  visualizer project always re-encodes. **Source-time mapping is the same approximate
  `outputTime - startMs` as the preview** (see Phase 2 bug-fix below to make it exact for trim/speed).
- **Phase 3 Studio UI — STARTED (2026-06-20, compile-green + installed. VISUAL VERIFY).** The style
  picker (`showVisualizerStylePicker`) already had architecture toggles + a gradient/preset list; now adds
  a **per-visualizer colour override**: a swatch row (8 colours; the first hollow ring clears back to the
  preset). New `WaveformOverlayInstance.colorOverride` (persisted via `ProjectStorage`, backward-compatible
  — no schema bump, `has()`-guarded), `WaveformStyle.copy()` + `withColorOverride()` (solid colour, clears
  gradient + sets glow), applied in BOTH the preview (`WaveformOverlayView.onDraw`) and export
  (`prepareWaveformBindings`) so they stay in parity. A **sensitivity slider** (0.5×–3×) is also wired,
  and override application is centralized in `WaveformOverlayInstance.applyOverrides(base)` (used by both
  preview + export, so new per-visualizer params drop in there). **Still TODO for Phase 3:** bar-width/gap
  sliders (same pattern), template gallery, SAF import/export of custom styles.
- **NEXT:** Phase 3 remaining (bar-width/gap sliders + gallery + SAF import/export); the
  covered-span/source-mapping/export-bake items above are done. VISUAL VERIFY colour + sensitivity in
  preview + a baked export.

## Phases (each ends with a compile via the watcher + adb/screenshot verify)
- **Phase 0 — renderer + presets + debug screen (NO extraction/editor/export).** Build `WaveformData`,
  `WaveformStyle`, built-in JSON presets, `WaveformStyleRenderer` (animated, takes `atMs`), and a
  `WaveformDebugActivity` that animates each preset against SYNTHETIC sample data. Done when each
  preset renders correctly and distinctly (verify via screenshot over adb).
- **Phase 1 — real extraction + editor placement + scrubbable preview.** `Fft`, `WaveformExtractor`
  (+cache), `WaveformOverlayInstance`, schema v7 + persistence, place via hand-written EditScript /
  action, live preview ImageView in `player_view` updated on scrub.
- **Phase 2 — export compositing** (per-frame overlay bitmaps; mirror text-overlay export path).
- **Phase 3 — Studio UI** (gallery, custom editor, SAF import/export, "save to my presets").
- **Phase 4 — live recording reuse** (after `RECORDING_HANDOFF.md` exists; perf-validate).

## Master build order (all queued work)
1. Verify current perf + transcript-cut batch.
2. Transcript windowing (staged).
3. **Waveform Visualizer (this doc) — IN PROGRESS.**
4. GL transitions + filters/color/text styling.
5. Missing-media relink + Consolidate.
6. Dual-stream recording (after `RECORDING_HANDOFF.md`).
7. Layers (multi-track) — unblocks floating webcam/PiP, overlay b-roll, floating visualizers.
8. Interleave UX: Asset Browser v2, control row, narrative/b-roll confirm UI.
