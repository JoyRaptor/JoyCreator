# SPEC K — A move or a fold throws ONE corner across the frame

**Difficulty: HIGH. This is the top bug in the transform tool. Read `_RULES_READ_FIRST.md` first.**
**Nothing else in the transform surface should be worked on until this is fixed.**

## What JoyRaptor sees (device test, 2026-09-06, Note 20)

> "The image object is at right angles, default, as if you would load it in. And when I fold it,
> it goes from having right angles to having one corner dragged really low... the right corner is
> dragged really low, causing it to have a sharp angle while the top is unaffected. This is
> aberrant and unpredictable behavior."
>
> "Now I am uploading an entirely new image. The image is coming in, and I'm trying to move it.
> And it's giving the same sort of distortion that the fold did except without the mirror. It is
> pulling down the left side."
>
> "That distortion happened just by me MOVING it, not by me clicking on it. I did not have
> anything selected as free for corner behavior. It was all set just to even scale."

Reproducible. Survives Reset object. Happens on a freshly imported, never-distorted image.

## HARD EVIDENCE — read this before touching any code

Pulled from JoyRaptor's live working project on the Note 20 (`project.json`, overlay index 100 — the
image spanning the whole timeline). These are the stored corner-pin offsets, in units of the
picture's own size:

```
pinTLdx -0.0899   pinTLdy +0.0202
pinTRdx -0.0014   pinTRdy +0.0202
pinBRdx -0.0014   pinBRdy +0.0209
pinBLdx +0.1844   pinBLdy +1.3749     <-- BOTTOM-LEFT thrown 1.37 picture-heights down
```

Three corners agree to two decimal places and form a near-perfect rectangle. **One corner is
displaced by more than a full picture height.** That is the "sharp angle while the top is
unaffected" exactly. `rotationDeg` on that item is `365.24` (SPEC A winding, expected, not a bug).

**A single wrong corner out of four is the signature of an index or ordering slip, not of a
formula that is uniformly wrong.** Look for that first.

## What has already been RULED OUT (do not re-investigate)

**SPEC G's `normalizePin` does not create this shape.** Walk the code: with BL displaced by 1.37h
the opposite-edge parallelogram test (`mis` vs `PIN_BAKE_PARALLELOGRAM_TOL`) fails, so it returns
`valid=true, baked=false` and copies the authored pin back **bit-for-bit**. Normalization
*preserves* the corruption; it is not the source. Verified 2026-09-06.

Therefore the bad corner is already in the pin **before** commit-time normalization runs — it was
written by `CornerPinTransformHost.writeQuad`, from `TransformOverlayView.quad`.

## Where to look, in order

1. **`TransformOverlayView.quad` staleness.** `writeQuad` is only called when `shapeChanged`. Find
   every path that mutates `quad` and every path that refreshes it (`syncFromHost`,
   `quadLastGood`). **A pure body MOVE should never call `writeQuad` at all** — if a move is
   reaching it, that alone is the bug. JoyRaptor's clearest reproduction is a plain move.
2. **Corner ordering.** `readQuad` builds `baseX = {left, right, right, left}`,
   `baseY = {top, top, bottom, bottom}` — TL, TR, BR, BL. `CornerPin.TL/TR/BR/BL = 0/1/2/3`.
   Confirm every producer and consumer agrees, especially `TransformQuad.foldOverEdge`'s
   `EDGE_CORNERS` and the fold's reflection loop.
3. **The pose/fold conversion in `writeQuad`.** It un-folds each dragged corner about the pivot by
   −θ and measures against the pose box. It was changed on 2026-09-06 so `folded` is now true for a
   pinned picture at a CENTRE pivot (`isRotationPivotNeutral`). Check that `readBox` is handing it
   the frame it expects — the frame is now folded for pinned centre-pivot pictures, and the
   conversion assumes a specific one.
4. **The fold itself.** `foldOverEdge` reflects all four corners across an edge line. If it is
   reflecting across the wrong edge, or the quad handed to it is stale by one corner, you get this.

## Also in scope — the staleness JoyRaptor hit in the same session

> "I resized the preview drawer, and that is causing a lot of staleness bugs with the transform
> tool. Moving it, dragging it does not fix the relative staleness. Staleness moves under my finger
> with the object until mouse up. At that point, the staleness refreshes and it goes back to how it
> should be."

A drawer resize changes the preview rect. The transform overlay and the picture must both be
re-read from the new rect. Find what recomputes on a resize and what does not.
`FaditorEditorActivity.requestGlPreviewResync()` and its comment describe this exact family.

Related report from the same session, probably the same root cause as the corner bug:

> "I changed the aberrant corner to free and tried to move it a little bit, and it ended up moving
> in a lot more than I wanted... it's following with my finger, but the image itself is not
> distorting as much... if I were to move it in fifty percent, it might move in ten or twenty
> percent. And letting go, the focal corner snaps back to the larger corner."

Handle and picture disagreeing during a drag, then snapping on release, is the same
presented-frame-vs-pose-frame confusion as item 3 above. Fix them together if they share a cause;
say so if they do not.

## How to reproduce without a phone

You do not need the device to start. Use the numbers above: construct a flat unit quad, run it
through `readQuad` → the gesture path → `writeQuad`, and assert all four residual offsets stay at
zero. **`TransformQuad` has ZERO imports**, so this belongs in the JVM harness
(`tools/jvm-harness/`, see `run-pinbudget.sh` for the pattern). A test that reproduces the single
bad corner off-device is worth more than any amount of reasoning, and it is the deliverable that
stops this from coming back.

To confirm the fix against JoyRaptor's real data, read the runbook's FASTEST PATH §4 — pull
`project.json` off the phone and check that a move leaves all eight offsets at zero.

## Acceptance criteria

1. A freshly imported image, moved, still has all eight pin offsets at exactly 0.
2. The same after a rotate, after a scale from any corner, and after a two-finger pinch.
3. A fold over an edge produces a quad whose four corners are all where the fold puts them — no
   single corner displaced relative to the other three.
4. A harness test that FAILS on today's code and passes after the fix. Name it in your report.
5. During a drag, the handles and the picture stay together — no snap on release.
6. Resizing the preview drawer leaves the handles on the picture.
7. `./gradlew assembleDefaultDebug --console=plain` → BUILD SUCCESSFUL, and `run-pinbudget`,
   `run-mesh`, `run-rotation`, `run-preview-parity`, `run-frame-parity` stay green.

## Deliver

The root cause in one sentence, with `file:line`; the harness test that proves it; what you fixed
for the drag-disagreement and the drawer-resize staleness, or why they are separate; the build
verdict; compile-verified vs device-verified.

**Do not report this as fixed on reasoning alone.** It has survived one round of confident
reports already.
