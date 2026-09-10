# SPEC R — A meshed picture renders VERTICALLY INVERTED

**Difficulty: MEDIUM. One flip, applied an odd number of times. Read `_RULES_READ_FIRST.md` first.**
**This is the last known bug in the transform tool. Nothing else is outstanding.**

## The symptom

JoyRaptor, 2026-09-08:

> "I can transform, but when I turn mesh on and move a mesh point, then the image flips **upside
> down**."

## Confirmed by me on the Note 9, AFTER the SPEC Q fix landed

This is **not** SPEC Q resurfacing. Q fixed a genuine, separate bug — the mesh fold rotated
non-square units and sheared instead of turning, producing a displacement that vanished at 0° and
peaked at 90°. That fix is verified over 5400 poses and is not in question here.

State when I reproduced it (read from `project.json`, not eyeballed):

```
rot 7.61°   size 0.684   mesh yes   pins yes
```

**At 7.6° of rotation the picture renders clearly upside down** — the subject's legs at the top,
the shirt's lettering mirrored. At that angle Q's displacement is essentially nil, so the two are
unrelated. Screenshots: `scratchpad/z3.png`, `scratchpad/z4.png`.

The picture is upright the moment the mesh is not in play — earlier in this same session, an
undo that removed the mesh restored it immediately (`scratchpad/undo.png`).

**So: the inversion belongs to the mesh render path alone, and it is present whenever a mesh
exists — not only while dragging.**

## The cause is almost certainly an odd number of y-flips

An inverted picture is a vertical flip applied an odd number of times. The mesh path deals with
GL's y-up convention in these places, and they must compose to **exactly one** flip end to end:

1. `MeshPlacement.buildPlace` — `float cyGl = 1f - cyTop;`, `Math.toRadians(-rotTopDeg)`, and the
   `+ 2f * cyGl - 1f` term in `r12`.
2. `MeshGlSource` — the vertex/fragment pair. Its own comment claims the "source flip matched to
   the still path"; **check that claim against what the flat path actually does** rather than
   trusting it. SPEC E's contract also says the stamp is sampled straight while a bitmap upload is
   sampled flipped — one of those two rules is being applied where the other belongs.
3. `MeshStampGl` — the stamp is rendered into an FBO and then composited. **An FBO is bottom-up
   and a bitmap is top-down.** If the stamp is written with one convention and read with the
   other, exactly this happens.

Number 3 is the strongest suspect: it is the one place two different conventions physically meet.

## How to find it in one run, without guessing

Render an **identity** mesh — a spec that exists but with no handle moved — and difference it
against the flat draw of the same object. `run-specq.sh` already builds an independent pixel-space
model of the flat placement, so the harness half is there.

**An identity mesh that comes out inverted cannot be mistaken for anything else**, and it takes the
deformation out of the picture so you are looking at the flip alone. If the geometry proves upright
in the harness but the device still inverts, the flip is in the sampling (item 2 or 3), not the
placement.

## Acceptance criteria

1. A meshed picture renders **upright** — device screenshot, subject the right way up, readable
   text the right way round.
2. An identity mesh is pixel-identical to the flat draw, including orientation, at 0°, 45°, 90°
   and 180°.
3. **The export matches the preview.** A bent image must not be upright in one and inverted in the
   other — if the flip is in the stamp, both surfaces share it and both must be checked. State
   how you verified the export, not just the preview.
4. A real bend still bends in the direction the finger dragged (an inverted picture also inverts
   the *sense* of the drag — confirm dragging a dot down moves that part of the picture down).
5. `run-specq` 7/7, `run-mesh` 66/66, `run-spech` 36/36, and bakepop, escape, pinbudget, speck,
   flip, rotation, preview-parity, frame-parity, persist-lint all stay green.
6. `./gradlew assembleDefaultDebug --console=plain` → BUILD SUCCESSFUL.

## Boundaries

- You own `transform/mesh/MeshGlSource.java`, `transform/mesh/MeshPlacement.java`,
  `compositor/MeshStampGl.java`, and the mesh blocks in `FxLivePreviewController` /
  `ImageBlendGlEffect`.
- Do **not** touch `TransformQuad`, `CornerPinTransformHost`, the bake path,
  `TransformOverlayView`, `foldRotationPivotIntoBox`, or `timeline/**`.
- **Do not undo SPEC Q.** The square-units fold in `MeshPlacement.fold` is verified over 5400
  poses; if you think it is wrong, say so in your report rather than changing it.
- Note 9 (`<note9-serial>`) is the test device. **Never write to the Note 20.**

## Test-bed note

`BundlingFontTest`'s image has been dragged around a great deal and currently carries a mesh, a
corner pin and ~7.6° of rotation. **Reset object** (long-press a handle → ring → Reset) gives a
clean start; it keeps the size and clears everything else.

## Deliver

Which of the three places was flipping an odd number of times, and the evidence. Device
screenshots of an upright meshed picture. How you checked the export as well as the preview. Build
verdict; compile-verified vs device-verified.
