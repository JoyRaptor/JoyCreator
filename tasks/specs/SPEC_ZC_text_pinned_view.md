# SPEC ZC — A text box that can be corner-pinned

**Dispatchable. Difficulty: MEDIUM — mirrors `CornerPinImageView` closely, plus one layout subtlety.**
**Read `_DISPATCH_RULES_20260913.md` first.**

---

## The situation, and a correction to `SPEC_Z_warpable_objects.md`

That spec says text is nearly free because the model already holds a pin. **That is true of the
model and false of the renderer**, and the difference is this whole sheet:

- `TextOverlayItem` already carries `cornerPin` and `mesh` — text and images share that class.
- Persistence is gated by one `if (o.isImage())` at `ProjectStorage` ~line 2449.
- But **text does not draw on a canvas. It draws through child Views** (`TextBoxView` inside
  `TextOverlayLayer`). So there is no place to concat a matrix around a draw call, which is how the
  sprite got its pin.

A pinned text box needs what a pinned image already has: a view that applies the matrix in its own
`onDraw`, plus the layout inflation that stops a pulled corner being clipped by its parent.

**Good news, and it changes the risk:** a pin on text is a matrix on **glyph outlines**, not a
warped raster. So the sharpness problem `SPEC_Z` flags for text applies only to the MESH. A pinned
text box stays vector-sharp, and you should say so in your report after checking it on the device.

## What to build

### 1. `CornerPinTextView`, mirroring `CornerPinImageView`

`app/src/main/java/com/fadcam/ui/faditor/overlay/CornerPinImageView.java` is the model. Note
especially:

- Line 29-30: **"The fast path is literally the old path."** With no pin and no inset, `onDraw`
  calls `super.onDraw` and the view IS an ordinary `ImageView`. Your version must have the same
  property with `TextBoxView` — an unpinned text box must take the byte-identical path it takes
  today, down to the same draw calls.
- `setCornerPin(float[] off8, float insetPx)` (line 74) — the entry point.
- Line 54: **"Reused, never allocated in onDraw: this view is redrawn on every playhead tick."**
  Allocate nothing per frame.

The matrix itself comes from `TextOverlayItem.cornerPinMatrix(...)` — **the one method the image
preview and the image export both already call.** Do not build a matrix; call that.

### 2. The layout inflation in `TextOverlayLayer`

`overlay/TextOverlayLayer.java` ~line 1206-1240 already does this for images, and the comment there
explains the whole problem:

> "A pulled corner is drawn outside the picture's own rectangle, and a child View's drawing is
> clipped by its parent — so the view is inflated by the largest excursion on every side and the
> picture is drawn into the inset. This reuses `boxInset`, which the layout below already subtracts
> so it is the BOX that ends up centred on cx/cy, not the view plus its margin."

Do the same for the `TextBoxView` branch. `boxInset` already exists in that method and is already
subtracted by the layout, so the machinery is there — the text branch simply never fed it a pin.

**`padPx` is 0 and the pin call is a no-op for an unpinned item**, which is what keeps every
existing text box laid out bit-for-bit as it is now. Preserve that.

### 3. The export half

`overlay/TextOverlayRenderer.java` rasterises text for the export. It must concat the SAME matrix
from the SAME method, at the equivalent point in its canvas stack. Rule 7: one method, both
surfaces.

### 4. Ungate persistence

`ProjectStorage` ~line 2449 writes the mesh only `if (o.isImage())`. Once text can be pinned, the
PIN must persist for text too. Check whether the pin block (~line 2430) is already ungated — it may
be — and say what you found either way. The read side is already tolerant.

### 5. Do NOT open the transform gate

Text is on `AffineTransformHost` with no `PinChannel`, so it cannot author a pin yet. **Leave it
that way.** Opening it is a one-line change and it belongs in the sheet that can prove the whole
loop; this sheet ends when both renderers can DRAW a pinned text box. Data first, renderers second,
capability last — and never a window where a user can author a distortion nothing draws.

Verify your work by setting a pin on a text item directly in `project.json` and opening it.

## Acceptance criteria

1. A text box with a pin set in `project.json` renders distorted in the preview and exports
   identically. **Screenshot both.**
2. An UNPINNED text box is untouched — same layout, same draw path, same pixels. State how you
   confirmed the fast path is taken.
3. **Sharpness:** photograph a pinned and an unpinned text box at the same point size on the
   device. A pinned box must stay vector-sharp; if it is visibly softer, something is rasterising
   that should not be, and the sheet is not done.
4. Nothing allocates per frame in `onDraw`.
5. The transform surface still refuses to author a pin on text (gate stays shut).
6. `bash tools/build-verify.sh CornerPinTextView` → VERIFIED, and the standing harnesses stay green.

## Boundaries

- You own `overlay/CornerPinTextView.java` (new), the text branch of `overlay/TextOverlayLayer.java`,
  `overlay/TextOverlayRenderer.java`, and the text gate in `project/ProjectStorage.java`.
- Do **not** touch `CornerPinImageView`, the image branches, `transform/**`, or `transform/mesh/**`.
- Do **not** change what any text ANIMATION does. This is geometry only.

## Deliver

The new view, with the fast-path guarantee stated. The layout change and how `boxInset` carries it.
The export concat point. The two sharpness photographs. Build verdict via the dex. Compile-verified
vs device-verified, per item.
