# FEEDBACK 2026-07-06 — Layers / Timeline UX (JoyRaptor, triaged by Fable)

> **EXECUTION PLAN:** `tasks/PLAN_LAYERS_UX_EXECUTION.md` turns this triage into 7 always-green slices
> (A–G) grounded in the confirmed data-flow (both renderers run in `onDraw`; text/audio double-render;
> captions/visualizers have no TrackKind). T8 (sprite-per-lane, the first slice of #2) DONE = 02b51c2.

Spurred by the pangolin sprite work. This is a coherent timeline/layers overhaul, not scattered
nitpicks. **Root cause of most of it:** two row-rendering systems run at once — the OLD read-only
rows in `timeline/EditorTimelineView.java#drawLayers` (thin teal text, thin purple image, thin yellow
CC) and the NEW schema-v8 Track-model rows in `layers/LayerRowRenderer.java` (thick purple text, thick
gold sprites, blue PiP). Different code → different heights, colors, and the duplicate "Enter text"
rows. **Consolidating onto ONE renderer is the prerequisite for nearly every item below.**

Gesture design (G) is DESIGN-FIRST — co-design with JoyRaptor before building; she asked for help thinking
it through.

---

## Triaged items (recommended order)

### 1. Consolidate the two row-render systems → ONE  [root cause; do right after T8]
The old `EditorTimelineView#drawLayers` VIZ/CC/overlay/text rows and the new `LayerRowRenderer` rows
must become a single renderer with **one consistent row height, one color-per-type language, one header
layout**. Fixes: inconsistent bar thicknesses, the two different-looking "Enter text" rows, the two
different-looking "extract from video" audio bars.
**Files:** `timeline/EditorTimelineView.java`, `layers/LayerRowRenderer.java`, `model/Timeline.java`
(getLayers/getAudioTracks banding).

### 2. Every item type is its own lane; NO overlaps ever  [generalizes T8]
Text, image, sprite, video-overlay, PiP — each on its own bar; it must be IMPOSSIBLE for two items to
overlap on one lane. Requires: per-lane no-overlap enforcement (reuse the audio-overlap pattern just
landed in `Timeline.resolveAudioOverlap` — generalize it), plus move-between-layers / create-new-layer
(above or below). **T8 (sprites each get their own layerId) is the concrete first slice of this.**
**Files:** `layers/LayerGestureController.java` (already has `resolveNoOverlapStart`), `model/Timeline.java`,
`FaditorEditorActivity.java` (placement assigns unique layerId).

### 3. Vertical re-layout — industry standard  [after 1]
JoyRaptor's explicit target order, top→bottom:
```
minimap
time markers            (already present)
overlays & CC           (attached-to-video overlays that should NOT inherit video fades — e.g. CC)
LAYERS                  (images, sprites, text, video overlays — transcript across bottom inside tape)
MAIN EDITING LAYER      (LARGER; magnetic clumping; transcript in a space BELOW the tape)
audio layers            (BELOW main — transcript across bottom inside tape)
tool drawer             (bottom)
```
Today the main timeline is at the TOP with overlay layers below it that are visually ABOVE it →
inverted/confusing. Main editing layer should be centered + slightly larger; overlays above; audio below.
CC/visualizers: CC belongs in the "overlays & CC" band (a video fade-out shouldn't fade the captions);
visualizers probably sit in the normal LAYERS band.
**Files:** `timeline/EditorTimelineView.java` layout, `layers/LayerRowRenderer.java`, `Timeline` banding.

### 4. Caption-style chooser (Pop/Zoom/Bounce/Boxed/Hot/Meme/Bright) auto-hide  [quick-ish win]
It's permanently visible even when no captioned clip is under the playhead. Options (pick with JoyRaptor):
(a) fade in only when a clip WITH captions is under the playhead, hide otherwise; or (b) only show it
(at the bottom of the preview) after tapping a CC bar in the layers. JoyRaptor leans (b).
**Files:** `FaditorEditorActivity.java` (caption style bar / drawer show-hide).

### 5. Layer-header hit zones (name / visibility / lock / ? / twirl)  [part of #1]
Header icons are tiny and overlap the item bar — tapping a sprite keyframe near the corner can hit
"visibility" instead. Header controls need clear, non-overlapping, adequately-sized hit zones that the
item bar/keyframes never draw over. (Also: label the unknown 4th icon.)
**Files:** `layers/LayerRowRenderer.java` (header icon layout + hit-test), `LayerGestureController.java`.

### 6. Universal gesture language + preview manipulation handles  [✅ DESIGN LOCKED 2026-07-06]
**Co-designed with JoyRaptor + FINALIZED → `tasks/PLAN_GESTURE_CONTRACT_FINAL_20260706.md` (authoritative).**
Outcome: tap=select+preview handles; double-tap=type power-tools drawer; hold→drag=move; hold→release=
general advanced menu (peek/expand bottom sheet + on-demand top keyframe ribbon); per-property keyframe
diamonds; overlay piggyback/stratified tether model; resizable timeline w/ grab bar → fullscreen+PiP.
Original proposal (now historical):
- **Tap** = select → outline in the layers row AND spawn manipulation handles in the PREVIEW
  (corner + mid-edge handles: move / scale / rotate / aspect) for image, video, text, CC/title, sprite.
- **Double-tap** = advanced menu/editor for that type: CC → captions drawer; sprite → advanced editor;
  plus rename / lock / set color / replace source / properties. (Design the exact set with JoyRaptor.)
- **Long-press** = pick-up-for-move (NOT delete). Delete is too destructive as a layer-area long-press
  now that people reposition + pause to think. Move delete into the double-tap menu (a trash option),
  OR relocate destructive gestures to the PREVIEW window (long-press a visualizer/caption in the
  preview to delete/hide can be fine; long-press in the layer area should not delete).
Tension to resolve with JoyRaptor: per-layer dialog vs. drawer-comes-up. Likely: preview gestures for
manipulate/destroy; layer-area gestures for select/move/reorder; double-tap for advanced/props.
**Files:** `LayerGestureController.java`, `EditorTimelineView.java`, preview overlays
(`overlay/TextOverlayLayer.java`, `sprite/SpriteOverlayView.java`, a new preview-handles overlay).

---

## Suggested sequencing
T8 (sprite per-layer) → #1 consolidate renderers → #5 header hit zones (falls out of #1) →
#4 caption-chooser autohide (quick) → #3 vertical re-layout → #2 no-overlap-all-types + move-between-
layers → #6 gesture language (design pass with JoyRaptor, THEN build). Existing sprite fast-follows
(FF-A presets, FF-B AI) come after the layers overhaul is stable, since they add more layer items.
