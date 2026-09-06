# SPEC D — Widen the transform handles to PiP video and text

**Difficulty: MEDIUM. The pattern already exists and ships — this is applying it to two more types.**
**Read `_RULES_READ_FIRST.md` first.**

## Background — what already works

JoyRaptor approved a transform UI through six prototype rounds, and it is built and shipping for IMAGE
overlays. Selecting an image overlay puts eight smart handles on it directly (no menu). The pieces:

- `ui/faditor/transform/TransformQuad.java` — pure geometry, ZERO imports. scale/tilt/free/rotate/
  pinch/fold, the convexity guard.
- `ui/faditor/transform/HandleModel.java` — roles, shapes, colours, hit priority. ZERO imports.
- `ui/faditor/transform/TransformOverlayView.java` — the View: handles, the long-press role ring,
  the gesture HUD, the loupe. Android UI only, no app-specific types.
- `ui/faditor/transform/CornerPinTransformHost.java` — the adapter for image overlays.
- `ui/faditor/transform/SpineTransformHost.java` — the adapter for spine clips (built later; read it,
  it is the closest model for a NEW host).
- Selection wiring: `FaditorEditorActivity.updatePreviewHandlesForSelection()` (~line 24535).

The vocabulary, for consistency: every handle defaults to **Scale** (amber square on corners, amber
inward triangle on edges); **Tilt** is a green diamond/square; **Free** is a red circle; **Bend** is a
blue net and is currently greyed and inert. Long-press a corner or edge for the role ring. One finger
pans, two fingers scale+rotate about the finger midpoint, and a separate amber spin-arc does pure
rotation.

## The task

Give the same handles to **PiP video clips** and to **text overlays**, so selecting one puts the
transform surface on it instead of the old box.

## THE HARD CONSTRAINT — read this before deciding what to enable

A handle may only offer a gesture that BOTH renderers can actually draw. Corner-pin distortion is
drawn by `overlay/CornerPinImageView` in the preview and `export/ImageOverlayDraw` in the export —
**both are image paths.**

- **Text has no pinned render path in either surface.** Giving text corner-pin/tilt/free would author
  a distortion neither renderer can draw. Do NOT enable it unless you genuinely build both halves.
- **PiP video** — investigate. A PiP is drawn as a GL quad in the preview
  (`FxPreviewTextureView.Pip`) and via `export/PipFrameOverlay` in the export. Determine honestly
  whether a pinned PiP can be drawn in BOTH. If not, restrict it.

`TransformOverlayView.setAffineOnly(true)` already exists for exactly this: it greys Tilt, Free and
the edge-fold in the ring and makes every handle behave as plain Scale. The spine host uses it.
**Use it rather than inventing a new restriction mechanism**, and prefer restricting to shipping a
gesture nothing can render.

## What to build

1. A host per type, modelled on `SpineTransformHost` / `CornerPinTransformHost` — they read a quad
   from the item and write gestures back to its model, with ONE undo step per gesture.
2. Route selection of a PiP and of a text overlay to `TransformOverlayView` instead of the old
   `PreviewHandlesOverlay` target.
3. **Do not lose anything the old handles did.** When image overlays were rewired, double-tap-to-open
   -the-drawer was nearly dropped and had to be restored. Enumerate what the old text and PiP handles
   support (double-tap, delete affordance, resize corners, anything) and preserve every one of them.
   List them in your report.
4. **Mutual exclusivity.** `PreviewHandlesOverlay` must not be set to GONE — it is the only view that
   answers "what did I just tap" for selection. The existing solution is to null its *target* so it
   draws and grabs nothing but still routes selection. Follow that; do not regress it.

## Acceptance criteria

1. Selecting a PiP or a text overlay shows the new handles, with no menu and no extra tap.
2. Sprites, captions and waveform overlays are UNCHANGED.
3. Every capability the old handles had for these types still works — list them and say so.
4. No gesture is offered that the export cannot reproduce. State per type what you enabled and why.
5. An item that has not been transformed previews and exports exactly as today.
6. `./gradlew assembleDefaultDebug --console=plain` → BUILD SUCCESSFUL after your last edit.

## Deliver

Per type: what you enabled, what you restricted and the evidence for the restriction; the list of
preserved old capabilities; how mutual exclusivity is maintained; the build verdict; compile-verified
vs device-verified.
