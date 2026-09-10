# SPEC T — The same non-square fold bug, in the FX/blend preview path

**Difficulty: LOW. One line, already diagnosed. Read `_RULES_READ_FIRST.md` first.**

## What this is

SPEC Q fixed a rotation that was applied to **non-square units**. The mesh fold turned a pivot
vector with a plain `cos/sin` pair, but its two components are fractions of *different* lengths —
x of the frame's width, y of its height. Rotating those is a shear, not a rotation. The error
carries a factor of `sin(rot)`: zero at 0° and 180°, largest at 90°. On JoyRaptor's picture it measured
**674 px at 90°**.

The SPEC Q lane found **the identical arithmetic in a second place** and correctly did not touch it,
because it was outside its ownership:

> `TextOverlayLayer.fxPipFor` (~line 1578) still has the identical anisotropic fold.

## Who it affects

Narrower than the mesh case, which is why nobody has reported it yet. It bites an image that has
**FX, a chroma key, or a blend mode** (so it is routed through the GL Pip path) **AND** a
non-centre rotation pivot. Such an image previews offset from its own helper frame, and from its
own export.

At a **centre pivot the whole block is skipped**, so the overwhelming majority of objects are
unaffected — the same reason the mesh version hid for so long.

## The fix

Exactly the shape SPEC Q used, and the reference implementation is right there in
`MeshPlacement.fold`:

```java
// Into square units (x fraction * aspect == x in units of frame HEIGHT), turn, back.
fx = pvx + vx * c - (vy * s) / a;
fy = pvy + (vx * a) * s + vy * c;
```

where `a` is `frameWidth / frameHeight`. Guard it the same way: a non-finite or non-positive
aspect falls back to `1` (the old arithmetic), so a degenerate frame cannot make things worse.

**Do not copy the code — call the shared one if you can.** `MeshPlacement` has no Android imports.
If `fxPipFor` can reach it, that is strictly better than a second transcription, and it is the rule
this project keeps relearning: the mesh version of this fold existed *because* someone transcribed
the flat one, and the transcription is what drifted. Say in your report which you did and why.

## How to prove it

`tools/jvm-harness/run-specq.sh` already builds an independent pixel-space model of the flat
placement and sweeps 5400 poses. Extend it, or follow its pattern, to cover this call site:
rotation 0–360° in 15° steps, all nine pivot anchors, both mirror states, portrait and landscape.
It must fail on the current code first. A test that does not fail before the fix has not found the
bug.

## Acceptance criteria

1. An FX/keyed/blended image with a non-centre pivot previews aligned with its helper frame at
   every angle, and matches its export.
2. A centre-pivot object is bit-identical to today — prove the block is skipped.
3. The new sweep fails before the fix and passes after.
4. `./gradlew assembleDefaultDebug --console=plain` → BUILD SUCCESSFUL, and specr 13/13,
   specq 7/7, mesh 66/66, spech 36/36, bakepop, escape, pinbudget, speck, flip, rotation,
   preview-parity, frame-parity, persist-lint all stay green.

## Boundaries

- You own `overlay/TextOverlayLayer.java` (the `fxPipFor` fold only) and may **read**
  `transform/mesh/MeshPlacement.java`.
- Do **not** touch `TransformQuad`, `CornerPinTransformHost`, the bake path,
  `TransformOverlayView` (SPEC S is running there), `transform/mesh/**`, or `timeline/**`.
- Note 9 (`<note9-serial>`) is the test device. **Never write to the Note 20.**

## Deliver

Whether you shared the definition or transcribed it, and why. The sweep results before and after.
The centre-pivot no-op proof. Build verdict; compile-verified vs device-verified.
