# FINDING 2026-08-18 — one text box pinned above its lane by leaked editing state

**Reported by JoyRaptor, Note 20, live:** a text box with seven candlestick emojis paints over an
image, although the image sits 3–4 lanes ABOVE the text. Not a state bug — reopening changes
nothing. There is nothing to reproduce; it is unconditional.

> **SUPERSEDED FIRST DIAGNOSIS — kept deliberately.** My first answer was "ordering is by
> KIND, not by lane, in preview and export". JoyRaptor rejected it from what he could see: *"several
> of the other text boxes behave appropriately being under the image... it's not like all text
> boxes are over all images."* He was right and the kind theory was wrong. It also could not have
> been right: in this project EVERY lane item is a `textOverlay` payload — an "image object" IS a
> text-overlay item carrying an image — so there are no competing kinds to mis-order. The correct
> diagnosis is below. Recording the wrong turn because the tempting fix it implied (re-ordering
> the three overlay surfaces) would have been a large change that fixed nothing here.

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

---

# ACTUAL CAUSE (device-diagnosed from JoyRaptor's project.json) — FIXED

`TextOverlayLayer.rebuild()` hoists the box being edited above every other box:

```java
if (editing != null) {
    bringChildToFront(editing);   // "Putting the box you are typing in on top is also simply right."
```

That is the ONLY thing on this surface that overrides lane order, and it was keyed on
`editingItemId` alone. `editingItemId` is cleared in exactly ONE place — `endTextStyleSession`
— and only when the style drawer closes cleanly AND `isEditingItem(id)` still matches. Every
other exit leaks it, and a leaked id pins that one box on top for the rest of the session while
every other box still obeys its lane. Hence "inconsistent", which was the clue that broke it.

**Evidence from `project.json` (project a32d24e2):**
- the 7-candle box `c430b838` is on `laneZ=0` — the BOTTOM lane — yet paints over images on lanes 8–10
- it is `flat#79 of 80`, the most recently touched item: the last box JoyRaptor edited
- 748 inversions between flat-list order and lane order, so flat order is NOT what the renderer
  uses — `setData` is fed by `visibleTextOverlaysAboveVideo` -> `partitionAroundVideo` ->
  `orderedVisualItems`, which DOES sort by `Track::getZIndex`. Lane ordering was working; one
  box was being lifted out of it afterwards.
- `pipZ` is `Integer.MIN_VALUE` here (no overlay-clip payloads), so all items land in the single
  ABOVE bucket and the Z3 split is not involved.

**Fix:** the hoist now requires a LIVE editor (`editingItemId != null && textEditor != null`),
so the override lasts exactly as long as the thing that justifies it. `endTextEditing()` nulls
`textEditor`, so the next rebuild restores lane order by itself — self-healing rather than
requiring every exit path to clean up.

**Verification still owed.** A restart also clears `editingItemId`, so seeing correct z-order
after installing proves NOTHING. The honest test is to reproduce the leak: edit a text box on a
low lane, dismiss the drawer by a path other than a clean `endTextStyleSession` (back gesture /
tapping away), and confirm the box drops back under the higher-lane images without restarting.

**JoyRaptor's rule, to hold the line:** anything on a lower lane is lower depth, with closed
captioning as the sole deliberate exception.
