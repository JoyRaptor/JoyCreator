# SPEC Q — A bent picture renders offset from its own helper frame

**Difficulty: MEDIUM-HIGH. A placement/coordinate bug in the mesh stamp only.**
**Read `_RULES_READ_FIRST.md` first, then `SPEC_E_mesh_gl_renderer.md`.**

## What JoyRaptor sees, 2026-09-08 (Note 9)

> "I tested the free drag points as well as the bend points and the scaling and moving and rotating
> all work. But what is not working quite right is the alignment of the helper frame and all of its
> components to the actual bending object. Right now it is rotating at forty-five degrees, and the
> actual bent image is about a centimetre down and to the right... And rotating puts that offset
> different. So if it's nearly vertical, it's almost aligned, but not quite. But it is most
> unaligned at ninety degrees clockwise."

So: **the offset is real, it is roughly a centimetre, and its size and direction change with the
rotation angle.**

## What I proved on the device, before theorising

Two screenshots of the *same object*, seconds apart, at the same 44.5° rotation:

- **Bend applied** → the photograph is clearly displaced down-and-right of the blue helper quad.
- **One undo, bend removed** → the helper quad hugs the photograph **exactly**, handles on its
  corners, no offset anywhere.

**Therefore the misalignment belongs to the MESH RENDER PATH ALONE.** The ordinary (unbent)
placement is correct, and so is the helper frame. Do not go looking in `TransformOverlayView`,
`foldRotationPivotIntoBox`, or the transform hosts — they are demonstrably right.

## What I already ruled out — do not re-investigate

- **The centre-pivot rule is consistent.** `isRotationPivotNeutral(pins)` now ignores pins and
  returns `isRotationPivotCentre()` (`TextOverlayItem.java:800`), and `ImageOverlayDraw` uses the
  box centre at a centre pivot. Frame and renderer agree. Verified in source 2026-09-08.
- **The mesh caller passes picture-sized dims**, not inflated ones:
  `wNorm = sizeFrac * imageAspect * sx / frameAspect`, `hNorm = sizeFrac * sy`
  (`FxLivePreviewController.java:856-857`) — same shape as the flat path.

## SECOND SYMPTOM, 2026-09-08 — and it names the cause

> "I can transform, but when I turn mesh on and move a mesh point, then the image flips UPSIDE
> DOWN."

**An upside-down picture is a vertical flip applied an ODD number of times.** The mesh path
y-flips for GL's y-up convention in exactly one place: `MeshPlacement.buildPlace` —
`float cyGl = 1f - cyTop;` (line 133), the negated rotation `Math.toRadians(-rotTopDeg)`, and the
`+ 2f * cyGl - 1f` term in `r12` (line 147).

**Both of JoyRaptor's reports are almost certainly ONE bug:**

| symptom | what it means |
|---|---|
| picture inverted | the flip happens an odd number of times, or reaches the geometry but not the point the rotation turns about |
| offset grows with angle, worst at 90°, ~0 when vertical | a rotation about an UNflipped centre while the geometry IS flipped gives exactly `(I − R(θ))·δ` |

Both fall out of the same mistake. Check the flip is applied **exactly once, end to end**, to all
three of:

1. the vertex positions,
2. the centre the rotation turns about,
3. the source/UV coordinate the shader samples (`MeshGlSource` — re-read its "source flip matched
   to the still path" note against what the flat path actually does).

The identity-bend measurement below now settles it in one look: **an identity bend that renders
upside down cannot be mistaken for anything else.**

## Where to look

`transform/mesh/MeshPlacement.fold(...)` and `MeshPlacement.buildPlace(...)`, plus the two callers
that feed them: `FxLivePreviewController.withMeshInputs` (preview, ~line 850) and
`ImageBlendGlEffect` (export, ~line 532).

`fold()` is a transcription of the flat `TextOverlayLayer.buildPip` fold. **Diff those two
line-by-line.** The flat one is known correct — it is what draws the picture the helper frame
matches. Anything the mesh copy does differently is your suspect list. Pay particular attention to:

1. **`boxInsetPx`.** A corner-pinned image's View is inflated by the largest corner excursion, and
   `frame()` subtracts it (`outRect.inset(in, in)`). The flat pivot-offset calls use
   `w - boxInset * 2f`. Confirm the mesh path is measuring the same rectangle the flat path is.
2. **The y-flip.** `buildPlace` does `cyGl = 1f - cyTop` and negates the rotation for GL's y-up.
   A rotation applied about a point that was **not** flipped along with the geometry produces an
   offset that is zero at 0° and grows with the angle — exactly JoyRaptor's symptom. Check that the
   flip and the rotation are about the same origin.
3. **Mirror.** The caller pre-multiplies `pivOffX/Y` by `mirrorSignX/Y` and the shader applies the
   mirror separately. Confirm it is applied once, not twice or zero times.

## The measurement that will settle it in one run

Do not eyeball this. Render the SAME object twice at the same pose — once through the flat path,
once through the mesh stamp with an **identity** bend (no handle moved) — and difference the two
frames. An identity bend must be pixel-identical to the flat draw. **Any offset you see there IS
the bug, with the mesh's own deformation removed from the picture.** Sweep the rotation from 0° to
360° in 15° steps and plot the offset; JoyRaptor says it peaks near 90°, which should be visible
immediately.

## Acceptance criteria

1. An identity-bend render is pixel-identical to the flat render at every rotation angle
   (0°–360°, 15° steps), both mirror states, and with and without a corner pin.
2. A real bend renders inside its helper quad: the picture and the blue frame agree at 0°, 45°,
   90°, 135° and 180°. **Device screenshots at a minimum of three of those angles.**
3. Preview and export agree — the same fix applied to both callers from one shared definition.
4. `run-mesh` stays 66/66, `run-spech` 36/36, and bakepop, escape, pinbudget, speck, flip,
   rotation, preview-parity, frame-parity, persist-lint all stay green.
5. `./gradlew assembleDefaultDebug --console=plain` → BUILD SUCCESSFUL.

## Boundaries

- You own `transform/mesh/MeshPlacement.java`, `compositor/MeshStampGl.java`, and the mesh input
  blocks in `FxLivePreviewController` and `ImageBlendGlEffect`.
- Do **not** touch `TransformQuad`, `CornerPinTransformHost`, the bake path, `TransformOverlayView`,
  `foldRotationPivotIntoBox`, or `timeline/**`. All of those are verified correct.
- Note 9 (`<note9-serial>`) is the test device. **Never write to the Note 20.**
- Test project: `BundlingFontTest`. Turning Bend on: select the image, tap the "Bend" pill top-right.
  Net dots are locatable by colour-sampling a screenshot with python+PIL.

## Deliver

The line that differs from the flat fold, and why it produces an angle-dependent offset. The
identity-bend difference results before and after. The device screenshots. Build verdict;
compile-verified vs device-verified.
