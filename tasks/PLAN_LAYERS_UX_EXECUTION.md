# PLAN — Layers/Timeline UX overhaul, EXECUTION (Fable, 2026-07-06)

Companion to `tasks/FEEDBACK_20260706_layers_ux.md` (the WHAT/WHY/order, JoyRaptor's gripes triaged).
This doc is the HOW: confirmed code facts + an incremental, ALWAYS-GREEN execution sequence, so the
renderer consolidation lands without a broken tree. Written after T8 (02b51c2) closed the sprite-per-
lane bug; the layers-UX overhaul is the next priority track.

Model policy: Fable 5 builds this (UI/UX overhaul — vision-heavy tier). Sequential, checkpoint commit
per green slice, device-verify per `DEVICE_CONTROL_RUNBOOK.md`.

---

## CONFIRMED ROOT CAUSE — two row-render systems both run in `onDraw`

`EditorTimelineView.onDraw` (6022-line god-class) calls BOTH:
- `drawLayers(canvas)` (line ~1918) — OLD: thin inline bars WITHOUT headers, at `getLayerTopPx()`.
  Renders `overlays` (text=teal `0xDD26A69A`/image=purple `0xDD7E57C2`), `waveformLayers`
  (cyan `0xDF4DD0E1`, "VIZ"), `captionSpans` (amber `0xDFFFC107`, "CC", or per-keyframe style color).
  Plus `drawAudioTrack(canvas)` (line ~1711) — OLD audio bars.
- `layerRowRenderer.layout(...)` (line ~1730) — NEW: thick Track-model rows WITH headers
  (name/caret/visibility/lock/mute), at `getM6RowsTopPx()`. Renders the `layerTracks` +
  `audioLayerTracks` built from `Timeline.getLayers()` / `getAudioTracks()`.

**The Activity feeds BOTH** at `FaditorEditorActivity.syncTimelineOverlays()` (lines 8844-8864):
```
editorTimeline.setOverlays(tl.getTextOverlays());          // → OLD drawLayers (text/image)
editorTimeline.setWaveformLayers(tl.getWaveformOverlays()); // → OLD drawLayers (visualizer)
editorTimeline.setCaptionSpans(capSpans);                   // → OLD drawLayers (caption)
editorTimeline.setLayerTracks(tl.getLayers(), tl.getAudioTracks()); // → NEW LayerRowRenderer
```
Consequences (exactly JoyRaptor's FEEDBACK):
- **Text & audio render TWICE** (old thin bar + new headered row) → duplicate "Enter text" rows,
  two "extract from video" audio bars, inconsistent heights/colors.
- **Captions & visualizers render ONLY in the old path** — `TrackKind` has MASTER/VIDEO/IMAGE/TEXT/
  STICKER/SPRITE/AUDIO but **no CAPTION, no VISUALIZER**. So they cannot yet be headered Track rows.

Old-path hit-testing uses `selectedLayerKind`/`selectedLayerValue` (0=overlay,1=viz,2=caption).
New-path hit-testing uses `LayerGestureController` + `LayerRowRenderer.hitTestHeader/hitTestItem`.
Height math that must move in lockstep: `overlays.size()+waveformLayers.size()+(captionSpans?1:0)`
appears in the content-height / `measureExtraHeightPx` calcs (lines ~1621, ~2224) and in
`getLayerTopPx()`/`getM6RowsTopPx()` region math.

---

## TARGET END STATE (unifies #1 + #3 from FEEDBACK)

ONE renderer (`LayerRowRenderer`) draws every non-master row, each a Track with a header, one
row-height language, one color-per-kind language. Vertical band order (JoyRaptor's industry-standard):
```
minimap · time markers                         (already present)
overlays & CC band   (attached-to-video: captions + video overlays that must NOT inherit video fades)
LAYERS band          (images, sprites, text, video overlays; transcript across the tape bottom)
MASTER editing layer (LARGER, centered; magnetic; transcript in a space BELOW the tape)
AUDIO band           (below master; transcript across the tape bottom)
tool drawer          (bottom)
```
Captions and visualizers become first-class Track kinds so they get headers + unified rendering.

---

## INCREMENTAL, ALWAYS-GREEN SEQUENCE (each slice compiles + device-verifies before the next)

### Slice A — make captions & visualizers Track-model citizens (no visual change yet)
1. Add `TrackKind.CAPTION`, `TrackKind.VISUALIZER` (additive enum; `fromName` default stays VIDEO).
2. `TimedItem`: add caption-span + visualizer payload sockets (mirror the sprite 4th-payload pattern:
   factory `ofCaptionSpan`/`ofWaveform`, `payloadKind()`, `getDisplayDurationMs` branch).
3. `Timeline`: new banding getters `getCaptionTracks()` / add a VISUALIZER branch to `getLayers()`
   (mirror the SPRITE grouping — default bucket + defs + defensive leftover). Caption spans are
   currently derived (per-clip) not a flat list; decide: wrap the existing `capSpans` computation into
   a single-track view WITHOUT changing persistence (captions stay clip-owned; the Track is a VIEW).
   *Green gate:* getters return correct tracks; nothing renders them yet; existing UI unchanged.

### Slice B — render caption/visualizer rows in `LayerRowRenderer`; feed them via `setLayerTracks`
4. `LayerRowRenderer.baseColorFor` + `drawItemBody`: add CAPTION (amber, per-keyframe style segments)
   and VISUALIZER (cyan) cases; label "CC"/"VIZ"; keep the per-keyframe caption coloring from the old
   `drawLayers` caption branch.
5. Activity: include caption/visualizer tracks in the `setLayerTracks(...)` band(s).
   *Green gate on device:* captions & visualizers now appear as HEADERED rows (in addition to the old
   bars — temporary double-render, acceptable for ONE checkpoint only).

### Slice C — delete the OLD `drawLayers` row rendering + old hit-testing (removes ALL duplication)
6. Stop feeding the old system: `setOverlays([])`, `setWaveformLayers([])`, `setCaptionSpans([])`
   (or delete the calls). Delete `drawLayers()` text/viz/caption rendering and the audio-row half of
   `drawAudioTrack()` that duplicates `getAudioTracks()`. Remove `selectedLayerKind`/`selectedLayerValue`
   and their hit-test/selection code paths (migrate any remaining selection to `LayerGestureController`).
7. Fix the height math: content height / `measureExtraHeightPx` now derive purely from the Track bands.
   *Green gate on device:* exactly ONE row per item; consistent heights/colors/headers; text/audio/CC/
   viz all selectable via the header/body gesture path; no dead tap zones; no reserved empty bands.

### Slice D — #5 header hit zones (falls out of C) + #4 caption-chooser auto-hide
8. `LayerRowRenderer` header icon layout + `hitTestHeader`: give name/visibility/lock/?/twirl clear,
   non-overlapping, ≥44dp zones the item bar/keyframes never draw over; label the unknown 4th icon.
9. Caption style bar (Pop/Zoom/…): show only when a CC clip is under the playhead OR after tapping a
   CC bar (JoyRaptor leans the tap-a-CC-bar option). File: `FaditorEditorActivity` caption-bar show/hide.

### Slice E — #3 vertical re-layout (band order above)
10. Reorder the bands: overlays+CC on top, MASTER centered + slightly larger, AUDIO below. Touches
    `EditorTimelineView` layout + `LayerRowRenderer` band grouping + `Timeline` band assignment.

### Slice F — #2 no-overlap ALL item types + move-between-layers
11. Generalize the audio-overlap resolver (`Timeline.resolveAudioOverlap` /
    `LayerGestureController.resolveNoOverlapStart`) to every kind; wire create-new-layer (above/below)
    and move-between-layers for text/image/sprite/video/PiP. (T8 was the first slice of this for sprites.)

### Slice G — gesture language + menu + preview handles + resizable timeline  ✅ DESIGN LOCKED
**Design co-designed with JoyRaptor and FINALIZED 2026-07-06 → see `tasks/PLAN_GESTURE_CONTRACT_FINAL_20260706.md`.**
That doc is authoritative and expands this slice into build sub-slices G1–G7:
- **Tap** = select + preview manipulation handles; **Double-tap** = the type's power-tools drawer;
  **Hold→drag** = move; **Hold→release** = general advanced menu.
- General advanced menu = general controls then object-specific section (general→specific,
  most-used→least), rendered as a peek/expand bottom sheet + on-demand top keyframe ribbon.
- Keyframe diamonds per property (drop/swipe-nav/long-press-delete); auto-record when armed.
- Overlay piggyback vs stratified tether model (depends on Slice-A CAPTION/VISUALIZER kinds).
- Resizable timeline via a grab bar, up to fullscreen timeline + draggable PiP preview.
Files: `LayerGestureController`, `EditorTimelineView`, preview overlays (`TextOverlayLayer`,
`sprite/SpriteOverlayView`, a new preview-handles overlay), Faditor preview container.

---

## RISK NOTES
- The god-class hit-testing is the sharp edge: removing the old `selectedLayerKind` path (Slice C)
  must not orphan a selection route. Trace every reader of `selectedLayerKind`/`selectedLayerValue`
  before deleting; device-verify text/CC/viz selection after.
- Captions are CLIP-OWNED (per-clip keyframes), not a flat overlay list — the caption Track must be a
  VIEW over the clip data, never a second source of truth (single-authority rule, as sprites/text do).
- Keep each slice independently shippable + device-verified; never leave both renderers drawing the
  same item except at the single Slice-B→C checkpoint.
