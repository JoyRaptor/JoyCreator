# Feature Spec: Waveform Visualizer Studio — Live + Editor (for Claude Code)

**Status:** Ready to implement
**Depends on:** `tasks/HANDOFF.md`, `docs/project-schema.md`, and the **`generatedSource` model + EditScript pattern from `feature-ai-generated-slides-spec.md`** — this spec extends that scaffolding rather than building a parallel one. Implement that spec's Phase 0–1 first, or pull `generatedSource` forward standalone.
**Read first:** Section 0 of `feature-ai-generated-slides-spec.md` applies here too. **Additionally:** this spec touches FadCam's live recording pipeline (camera capture, audio capture, encoder setup, the existing webcam-bubble compositing), which isn't covered in `tasks/HANDOFF.md` (that doc is editor-focused). Before Phase 4, locate and read the actual recording-side classes — camera capture session setup, the audio capture loop, encoder/muxer wiring, pause/resume handling, and wherever the webcam bubble is currently composited onto the recording. If no equivalent handoff doc exists for that part of the codebase, write one (`tasks/RECORDING_HANDOFF.md`) once you've found it, matching the existing documentation convention, so this doesn't have to be rediscovered next session.

---

## 1. Goal

One visualizer design system, two places it shows up:
- **FadCam (recording-time):** a waveform icon next to the record button. Tap to arm it — it's drawn live into the recording itself. Long-press for a preset picker, including your own saved presets.
- **Faditor (editor-time):** a placed, freely rotated/scaled overlay element. Long-press → **Edit** opens the full Visualizer Studio for that instance — pick any built-in template, tweak it, or design something fully custom.

The two contexts can't share a renderer — a live recording session can't afford to run a WebView frame-capture loop without dropping frames or burning battery — so this is genuinely **one preset format, rendered by different engines depending on where it's used.**

---

## 2. Architecture Decisions (Locked)

1. **Three tiers, two of which can run live, one of which never can:**
   - **Tier 1 — native parametric.** A small set of knobs (shape, mirror, anchor, color, sensitivity, bar count) drawn with plain Canvas/OpenGL math from an amplitude value. Cheap enough to run **live** during recording and to reuse, unchanged, for editor preview and export compositing.
   - **Tier 2 — ffmpeg filter templates** (`showwaves`, `showcqt`, `avectorscope`, etc.). Richer, real audio-analysis-driven looks. Editor/export only — never live.
   - **Tier 3 — fully custom** (AI-authored or hand-coded HTML/Canvas via the slides pipeline). Maximum creative range. Editor/export only — never live.
   - The Visualizer Studio is the one designer for all three tiers. The FadCam live picker only ever lists Tier 1 presets — that's the entire mechanism that keeps "two engines" honest without confusing the user about what's available where.

2. **The live engine reuses the existing webcam-bubble compositing path, not a new one.** Whatever mechanism currently draws the round camera preview onto the screen recording is where the Tier 1 `VisualizerRenderer.draw()` call gets added — same surface, same compositing pass, one more draw call. Don't build a second compositing pipeline.

3. **Live amplitude comes from the audio buffer already feeding the recording encoder, not from `android.media.audiofx.Visualizer`.** That system API only works on actively-playing audio sessions and can't be used during export or for arbitrary microphone capture in this context. Tap the existing PCM buffer (wherever it's read before being handed to the audio encoder), compute peak/RMS per chunk (and, for spectrum-style Tier 1 presets, a small real-time FFT — cheap, normal on any modern phone), and feed that into the renderer on the compositing thread.

4. **One renderer class, two data sources.** `VisualizerRenderer.draw(canvas, amplitudeSample, preset, bounds)` is pure rendering logic with no I/O. Live recording feeds it `LiveAmplitudeSampler` output (real-time PCM). The editor feeds it `PeaksAmplitudeSource` output (precomputed peaks, seekable to any timestamp). Same code path, same visual result in both places — this is what makes a Tier 1 preset designed in the editor look identical when armed live in FadCam.

5. **Peaks data reuses the schema field you already have.** `AudioClip.waveform` ("downsampled waveform peaks, 0–255") is the data source for `PeaksAmplitudeSource`. Populate it via ffmpeg decode-to-PCM + downsampling (no new dependency, ffmpeg's already bundled) or `lincollincol/Amplituda` (MIT, JitPack) if hand-rolled PCM windowing proves fiddly across formats. Don't invent a second waveform-data field.

6. **Presets live in a shared, app-level library — not inside one project.** A Tier 1 preset you design needs to show up in both FadCam's recording picker and every Faditor project's Studio, so it can't be project-scoped. Store presets as one JSON file each under app internal storage (`visualizer_presets/`), with a small index. Tier 3 custom designs default to living with the project they were made in (same convention as generated slides, under `<project>/visualizers/`) but get an explicit **"Save to my presets"** action to promote them to the shared library.

7. **Template gallery thumbnails are live mini-renders, not static images.** Since `VisualizerRenderer` is cheap, render each gallery tile by running the real renderer against a stock sample amplitude sequence rather than maintaining a separate set of preview assets that can drift out of sync with the actual look.

---

## 3. Schema Additions

### 3.1 `Visualizer Preset` object (new, lives in the shared preset library, not project JSON)

| Field | Type | Description |
|---|---|---|
| `id` | string | Preset id. |
| `displayName` | string | Shown in pickers. |
| `tier` | int | 1, 2, or 3. |
| `renderer` | string | `"native_parametric"` (tier 1), `"ffmpeg_filter"` (tier 2), `"webview_html"` (tier 3). |
| `shape` | string\|null | Tier 1 only: `"bars"`, `"line"`, `"ring"`. |
| `mirror` | string\|null | Tier 1 only: `"none"`, `"mirrored"`, `"one_sided"`. |
| `anchor` | string\|null | Tier 1 only: `"freeform"`, `"edge_top"`/`"edge_bottom"`/`"edge_left"`/`"edge_right"`, `"circle_wrap"` (rings around a circular element, e.g. the webcam bubble). |
| `barCount`, `sensitivity`, `colorPrimary`, `colorSecondary`, `style` | various | Tier 1 styling knobs. |
| `filterName`, `filterParams` | string, object\|null | Tier 2 only: ffmpeg filter + its parameters. |
| `htmlUri` | string\|null | Tier 3 only: same shape as `generatedSource.htmlUri` from the slides spec. |

### 3.2 `generatedSource` (extends the object from `feature-ai-generated-slides-spec.md` §3.2)

Add:

| Field | Type | Description |
|---|---|---|
| `kind` | string | Now also accepts `"waveform_visualizer"` alongside the existing `"html_slide"`. |
| `renderer` | string | `"native_parametric"` \| `"ffmpeg_filter"` \| `"webview_html"`. New field; for slides this is always `"webview_html"`. |
| `presetRef` | string\|null | Which library preset this instance started from, if any (enables "Edit" to reopen the right starting point). |
| `audioSourceRef` | string\|null | The `AudioClip`/`Clip` id whose `waveform` data drives this visualizer. |

`Clip.generatedSource` and `TextOverlay.generatedSource` both accept `kind: "waveform_visualizer"` the same way they already accept `kind: "html_slide"` — fullscreen vs. overlay placement works identically to slides.

### 3.3 New EditScript operation

```json
{
  "type": "ADD_VISUALIZER",
  "mode": "fullscreen" | "overlay",
  "presetId": "halo-ring-01",
  "audioSourceRef": "audioClipId-or-clipId",
  "startMs": 0,
  "durationMs": 8000
}
```
Mirrors `ADD_GENERATED_SLIDE`'s shape on purpose — same applier family, same caching/regeneration behavior, different `kind`.

---

## 4. New / Touched Code

| File | Responsibility |
|---|---|
| `com.fadcam.visualizer.VisualizerPreset` | Data class for Section 3.1. |
| `com.fadcam.visualizer.VisualizerRenderer` | Pure Tier 1 drawing logic: `draw(Canvas, AmplitudeSample, VisualizerPreset, RectF bounds)`. No I/O. Shared by both the recording pipeline and the editor. |
| `com.fadcam.visualizer.LiveAmplitudeSampler` | Recording-side: taps the existing PCM buffer, computes peak/RMS (+ small FFT for spectrum-style presets), exposes the latest `AmplitudeSample` to the compositing thread. |
| `com.fadcam.visualizer.PeaksAmplitudeSource` | Editor-side: reads `AudioClip.waveform`, exposes `AmplitudeSample` for an arbitrary timestamp (seekable, mirrors the slide system's `seek(ms)` contract). |
| `com.fadcam.visualizer.PresetLibrary` | Read/write the shared preset library under app internal storage; list/save/delete. |
| **Recording-side touch point** | Wherever the webcam bubble is currently composited onto the recording surface (locate per Section 0): add a `VisualizerRenderer.draw()` call when a preset is armed, fed by `LiveAmplitudeSampler`. |
| **Recording-side touch point** | Record-row UI: waveform icon (tap = arm/disarm current preset, long-press = Tier-1-only picker from `PresetLibrary`). |
| `EditScript.java` / `EditScriptApplier.java` | Add `ADD_VISUALIZER`, sharing the existing `generatedSource` cache/regenerate machinery from the slides spec, dispatching to one of three render paths by `renderer`. |
| `ExportManager.java` | Extend `ensureGeneratedSlidesRendered()` (or rename to reflect both uses) to also handle `kind: "waveform_visualizer"`: tier 1 renders via `VisualizerRenderer` to a bitmap sequence (same overlay-compositing path already built for text/slide overlays — no WebView needed), tier 2 shells out to ffmpeg, tier 3 reuses the slides pipeline unchanged. |
| **New: Visualizer Studio screen** | Template gallery (live mini-renders per Decision 7), parameter editor for tier 1, opens the ffmpeg-template picker for tier 2, opens the slide-authoring flow (manual or AI) for tier 3. Entry points: a "place visualizer" action in the editor, and long-press → Edit on an already-placed instance. |

---

## 5. Implementation Phases

### Phase 0 — Renderer + preset format, standalone
- Build `VisualizerRenderer`, `VisualizerPreset`, and 4–5 built-in tier-1 presets covering bars/line/ring × mirrored/one-sided × freeform/edge/circle-wrap.
- Add a debug screen that draws each preset against a fixed sample amplitude sequence — no recording or editor integration yet.
- **Done when:** all built-in presets render correctly and visually distinctly against the sample data.

### Phase 1 — Editor integration (tier 1 only)
- Extend `generatedSource`/EditScript per Section 3. Wire `PeaksAmplitudeSource` from `AudioClip.waveform` (populate that field if not already populated — ffmpeg decode + downsample, or Amplituda).
- Wire `ADD_VISUALIZER` → live scrubbable editor preview (same renderer, real-time) → export compositing via the existing overlay-bitmap path.
- **Done when:** placing a tier-1 visualizer via a hand-written EditScript through `ApplyEditsActivity` produces correct live preview and a correct export with the visualizer baked in at the right position/duration.

### Phase 2 — Visualizer Studio UI
- Template gallery, parameter tweaking, "Save to my presets," long-press → Edit entry point on placed overlay instances, rotate/scale via the overlay's existing position fields.
- **Done when:** a user can place a tier-1 visualizer, customize it without touching code, and reopen it later via Edit with its settings intact.

### Phase 3 — Tiers 2 and 3
- ffmpeg filter template gallery entries (Section 3.1 `renderer: "ffmpeg_filter"`), and tier 3 wired straight into the existing slide-authoring flow (manual HTML or `generate_slide`-style AI authoring, fed `audioSourceRef`'s peaks data as additional context).
- **Done when:** both tiers are selectable from the Studio and render correctly at export; both are correctly absent from FadCam's live picker.

> **Phase 4 DEVICE-VERIFIED 2026-07-19 07:21 (Fable, Note 9):** armed Fire Mirror via the new
> visual picker → recorded 15s with a 330Hz pulse tone playing → pulled mp4 frames show the
> bottom-strip visualizer BAKED INTO the recording, bars pulsing with the tone (mic path,
> max −17.7dB). Arm path proven by the renderer's own self-check log: "Live visualizer draw is
> hot: avg 6.07ms/frame (budget ~4ms)" — ⚠️ perf follow-up: Fire Mirror (glow blur at 1080w)
> exceeds the 4ms soft budget; no visible jank at 30fps, but the battery/dropped-frame A/B is
> still owed and a glow-downscale or strip-render-at-half-res optimization is the likely fix.
>
> **PERF FIX APPLIED 2026-07-19 (Fable): strip-render-at-half-res.** `LiveVisualizer.renderFrame`
> now renders the strip bitmap at `1/LIVE_RENDER_SCALE` (=2) per axis and scales density to match;
> the GL quad already stretches the texture to the full strip rect (fixed NDC rect + normalized
> [0,1] texcoords — dimension-agnostic, verified), so a 2× upscale of the soft glow is visually
> free with full editor parity (no features stripped). Software-fill cost scales with pixel count,
> so ¼ the pixels ⇒ expected ~6.07ms → ~1.5ms, back under the 4ms budget. The `>4ms`
> `recordVisualizerFrameTiming` self-check is unchanged and is the proof — it should now stay
> quiet. **RE-MEASURE OWED:** re-run the armed Fire Mirror session on the Note 9 and confirm the
> "Live visualizer draw is hot" warning no longer fires; the battery/dropped-frame A/B is still owed.
> (Arm-log note: "Live visualizer armed: style=" fires once, synchronously, at recording start
> under tag `ScreenRecordingService`; a logcat `-t` window attached after start can miss it while
> still catching the periodic hot-draw warning — benign, no ordering bug. See the comment at the
> log site in `ScreenRecordingService.armLiveVisualizerIfEnabled`.)
>
> **PERF FIX ROUND 2 APPLIED 2026-07-19 (Fable): split-timing + texSubImage2D upload.** The round-1
> re-measure on the Note 9 still showed the warning ("avg 4.5–6.25ms/frame"), so the cost is NOT
> Canvas-fill-dominated — the suspect is the per-frame `GLUtils.texImage2D` full texture re-spec in
> `GLWatermarkRenderer.drawVisualizerLayer` (the driver re-allocates texture storage every frame).
> Two changes, `GLWatermarkRenderer.java` only:
> 1. **Split timing.** `drawVisualizerLayer` now times three stages — (a) `renderFrame` Canvas
>    render, (b) texture upload, (c) quad draw/state — and `recordVisualizerFrameTiming(render,
>    upload, draw)` logs all three: "Live visualizer draw is hot: total X ms (render A, upload B,
>    draw C) (budget ~4ms)". Same 30-frame averaging + 5s throttle; reuses three accumulator fields,
>    no per-frame allocation.
> 2. **Upload optimization.** The strip texture storage is now allocated ONCE via
>    `glTexImage2D(...null)` at the strip render size (re-spec'd only when `bw/bh` change, tracked by
>    `visualizerTexW/visualizerTexH`, reset to -1 on release); each frame uploads pixels with
>    `GLUtils.texSubImage2D` into that fixed storage instead of re-spec'ing. Removes the per-frame
>    driver storage re-allocation — the standard fix for this exact profile.
> **Sync-hazard conclusion:** NO bitmap double-buffering added. `WaveformStyleRenderer.renderReusable`
> reuses one bitmap, but `GLUtils.texSubImage2D`/`texImage2D` copy the bitmap's pixels synchronously
> into GL-owned memory before returning, so the next frame's Canvas render into the same bitmap
> cannot race a pending GL read — there is no hazard. (Comment recorded at the upload site.)
> **Budget kept at 4ms** (not raised to 6ms): the remaining cost is not concluded to be irreducible
> upload bandwidth — moving off the full re-spec should cut the upload stage materially, so the
> tighter bar stays and the split-stage log is the proof. **RE-MEASURE OWED:** re-run the armed Fire
> Mirror session on the Note 9 and read the new split-timing warning (render/upload/draw breakdown)
> to confirm which stage dominates and whether the total is now under 4ms; battery/dropped-frame A/B
> still owed.
> Driving notes: the viz toggle is the 4th record-row button (amber pill ~x906/y1998 @1080x2220);
> 3rd (x802) is Audio Source — Microphone must be on for PCM; "Device Audio (Internal)" also works.

### Phase 4 — Live recording integration
- Per Section 0: locate the actual recording pipeline first. Add `LiveAmplitudeSampler`, the record-row icon + Tier-1-only long-press picker, and the `VisualizerRenderer.draw()` call into the existing bubble-compositing pass.
- **Performance validation required before calling this done:** record a representative session (several minutes, visualizer on) and compare dropped-frame count and battery drain against an equivalent session with it off. This is the one phase in this spec where "it compiles and runs once" isn't sufficient — sustained performance under real recording conditions is the actual requirement.
- **Done when:** the visualizer renders live with no perceptible dropped frames, and the same preset, applied later to the same recording in Faditor, looks the same.

#### Phase 4 — STATUS: BUILT (2026-07-19), pending device perf validation

**Renderer-reuse decision (supersedes the spec's planned `com.fadcam.visualizer.VisualizerRenderer`, which was never built):** the editor evolved `com.fadcam.ui.faditor.waveform.WaveformStyleRenderer` (Canvas, stateless-at-time-`t`) + `WaveformStyle` presets (`assets/waveform_styles/*.json`) into the tier-1 renderer. Phase 4 does NOT build a parallel renderer — it feeds that EXISTING renderer live PCM. Decisions 2/3/4 hold in current terms: same compositing pass (the GL watermark pass), tap the PCM already feeding the audio encoder, one renderer / two data sources.

Wiring as implemented:
- **PCM tap:** `ScreenRecordingPipeline.queueAudioData()` (`fadrec/encoding/ScreenRecordingPipeline.java` ~1040) — a SECOND `AudioTap` slot (`visualizerAudioTap`), independent of the dual-stream webcam tap, so both coexist. Never a second `AudioRecord`.
- **Sampler:** `com.fadcam.visualizer.LiveAmplitudeSampler` — single-writer (audio thread `onPcm`) / single-reader (GL thread `snapshot`), lock-free publish via a volatile bucket counter, no per-audio-chunk allocation, steady-state snapshot reuses scratch arrays. Rolling peak buckets (16 ms) + optional coarse 64-pt FFT (only when the armed style `drawsSpectrum()`). Exposes a live, continuously-appended `WaveformData` with `startOffsetMs` aligned so `atMs` (audio-committed time) lands on the newest bucket.
- **Draw call:** `GLWatermarkRenderer.drawVisualizerLayer()` — one more draw in the SAME pass as `drawWatermark()`, added to BOTH the encoder path (`renderToEncoder` after `drawWatermark`) and the preview path. Uploads the strip bitmap as a `GL_TEXTURE_2D` and blends it over a bottom-strip quad (full width, 18% height, hardcoded `VISUALIZER_STRIP_FRACTION` — TODO(prefs)). Uses `renderReusable` (no per-frame bitmap alloc); a T-inverted texcoord buffer avoids a per-frame bitmap-flip allocation. `LiveVisualizer` implements the new `GLWatermarkRenderer.OverlayFrameSource`.
- **Record-row UI:** `buttonFadRecViz` in `fragment_home.xml` (next to `buttonFadRecMute`), wired in `FadRecHomeFragment` — tap = arm/disarm (`fadrec_live_viz`), long-press = cheap-style picker (persists `fadrec_live_viz_style`, also arms).
- **Tier-1-only filter (`LiveVisualizer.isLiveEligible`):** a style is eligible iff its layer stack (or legacy single-shape) contains NO `particles` emitter and NO `trailCount > 0` layer — those emitters resample the audio at several past instants per frame (§3 look-back), too hot for the recording path. Legacy styles (`layers == null`) are always eligible. The picker lists only eligible built-ins; the service re-resolves the armed style id against that set so a stale pref can never arm a hot style.
- **Perf self-check:** `GLWatermarkRenderer.recordVisualizerFrameTiming()` averages the viz draw over 30 frames and `FLog.w`s (throttled to 5 s) when it exceeds ~4 ms/frame.

**VERIFY (device errand for the orchestrator):** on the SM-N960U, record two ~3–5 min FadRec screen sessions with mic audio — (A) visualizer armed via the waveform toggle, (B) toggle off. Compare: (1) dropped-frame count / stutter (logcat `Choreographer` skipped-frames + visual), (2) battery drain (`dumpsys batterystats` delta), (3) grep logcat for `ScreenRecPipeline`/`GLWatermarkRenderer` "Live visualizer draw is hot" warnings. Confirm the bottom strip renders full-width and audio-reactive in the recorded MP4, and that arming the same-named `WaveformStyle` on that clip in Faditor looks the same (Decision 4 parity). Note: if audio is disabled for the session the strip is present but flat (no PCM) — expected.

---

## 6. Constraints Carried Over

Same as the other specs: validated EditScripts only, no unsolicited comments/commits, compile check after each phase, update `HANDOFF.md`/`project-schema.md` (and the new recording handoff doc, if created) after each phase.

## 7. Out of Scope Here

- Tier 3 (fully custom) live during recording — not happening, not attempted, by design (Decision 1).
- Anything requiring multi-layer video tracks for the visualizer itself doesn't apply here (visualizers are overlays/fullscreen clips, not picture-in-picture layers) — no dependency on that roadmap item.
