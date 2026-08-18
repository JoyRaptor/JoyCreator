# FINDING 2026-08-18 — lane Z-order is ignored BETWEEN object kinds (preview AND export)

**Reported by JoyRaptor, Note 20, live:** a text box with seven candlestick emojis paints over an
image, although the image sits 3–4 lanes ABOVE the text. Not a state bug — reopening changes
nothing. There is nothing to reproduce; it is unconditional.

## The mechanism

Ordering is by KIND, not by lane. Both surfaces agree on the wrong order:

| | order |
|---|---|
| Preview | `activity_faditor_editor.xml` child order: `player_view` → `sprite_overlay_layer_below` → `overlay_layer_below` → **`layer_image_overlay`** → `sprite_overlay_layer` → **`overlay_layer` (text)** → `audio_caption_overlay`. Later child paints on top. Reinforced at runtime by `overlayLayer.bringToFront()` (FaditorEditorActivity ~:9521, ~:10281). |
| Export | `CompositeExportOverlay` ~:522 draws sprites "FIRST so they sit above the video but BELOW text + captions, **matching the preview stack**". |

So a lane's Z-index only orders items of the SAME kind. Text beats images always; images beat
nothing they should not. `partitionAroundVideo` splits only around the PiP plane, which is a
different axis and does not help here.

**Preview and export agree with each other** — so this is not a preview/export divergence, it is
one shared wrong rule. That is mildly good news: fixing the rule fixes both, and there is no
hidden second bug where the file differs from the editor.

## The fix (NOT started)

`LayerPreviewController.orderedVisualItems(timeline)` ALREADY does the right thing — sorts lanes
by `Track::getZIndex`, skips hidden lanes and per-object-hidden items, and is documented as the
shared authority. **Neither the export compositor nor the preview stack calls it.** The fix is to
drive both from it instead of from a hardcoded kind sequence:

- **Export** — `CompositeExportOverlay` currently runs one pass per kind. It needs to walk
  `orderedVisualItems` once and dispatch per item to the existing per-kind draw code. The
  rasterisers (`TextOverlayRenderer`, `ImageOverlayDraw`, sprite `drawCell`) do not change.
- **Preview** — three sibling Views cannot express interleaving (image lane 2, text lane 1, image
  lane 3). Either host all overlay items as views in ONE container ordered by lane, or give each
  item view a `Z` from its lane. The `bringToFront()` calls must go; they hardcode the wrong
  answer.

**Do NOT "fix" this by re-ordering the three surfaces relative to each other.** That happens to
fix JoyRaptor's case (all images above all text) and silently keeps the bug for any interleaved
project — the worst outcome, because it looks fixed.

## Verification when it lands
JoyRaptor's own case is the fixture: image on a high lane, text on a low one, and the image must
cover the text — checked in the editor AND in an exported file, numerically per §5 of
HANDOFF_20260813 (two renders, one setting changed, compared pixel-wise), not by eye.
