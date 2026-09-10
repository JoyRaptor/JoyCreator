# SPEC P — The object pops half a centimetre when you let go of a corner

**Difficulty: HIGH. This is SPEC L's own commit-time bake, and it is wrong on device.**
**Read `_RULES_READ_FIRST.md` first, then `SPEC_L_stop_the_escape.md`.**

## What JoyRaptor sees, 2026-09-08 (Note 9, build 13:57)

> "I am dragging one of the corners and letting go. And as soon as I let go, the whole object pops
> over to the right a bit. It's a close to forty-five degree rotation, and it seems to happen more
> when I set the rotation so that there isn't as much of a rotation. It seems now to be popping to
> the left. It seems to jump between a half centimetre to a centimetre upon letting go."

**The direction and size of the pop depend on the rotation angle.** Remember that — it is the
strongest clue in this file.

## The evidence, straight off his phone

`files/faditor/transform-diag.log`, consecutive corner drags. 28 bakes, 19 rollbacks, and every
single bake looks like this:

```
baked rot -18.925->-18.925  centre 486.90,577.75->441.48,676.49  size 139.81x169.81->248.23x301.49
baked rot -18.925->-18.925  centre 470.94,590.29->431.68,675.67  size 248.33x301.33->341.44x414.31
baked rot -18.925->-18.925  centre 645.02,455.04->606.07,449.97  size 489.56x592.56->533.51x645.75
baked rot -18.925->-18.925  centre 807.52,351.15->940.63,370.10  size 456.60x552.60->303.97x367.88
```

Rotation never changes. The centre moves **50–130 px** every time, and the size changes a lot —
that part is SPEC L working as designed (a corner drag IS a scale, so the scale belongs in `size`,
not in the pin).

**The bug is that this is supposed to be appearance-neutral and on the device it is not.** 50–130
px on a 1080-px-wide screen is about half a centimetre to a centimetre — exactly the pop JoyRaptor
measured by eye.

Note also: `walkaway 0`, `escape-clamp 0`, `drift 0`. None of the existing guards fired. The bake
believes it did no harm.

## Where to look — the pivot-anchor compensation

`CornerPinTransformHost.tryNormalizeOnCommit`, the block that computes the new centre:

```java
float ncx = bcx0 + c0 * fit.tx - s0 * fit.ty;
float ncy = bcy0 + s0 * fit.tx + c0 * fit.ty;
float o0x = item.pivotOffsetFromCentreX(w, h, pins0);          // OLD pin
float o0y = item.pivotOffsetFromCentreY(w, h, pins0);
float o1x = item.pivotOffsetFromCentreX(fit.newW, fit.newH, fit.residual);   // NEW pin
float o1y = item.pivotOffsetFromCentreY(fit.newW, fit.newH, fit.residual);
ncx += (o0x - (c0 * o0x - s0 * o0y)) - (o1x - (c1 * o1x - s1 * o1y));
ncy += (o0y - (s0 * o0x + c0 * o0y)) - (o1y - (s1 * o1x + c1 * o1y));
```

**The pivot offset is pin-aware.** The bake changes the pin, so the pivot moves, so the centre has
to absorb the difference. That last pair of lines is that correction — and a correction built from
`(I − R(θ))·δ` terms is exactly the kind of expression whose **error depends on the rotation
angle**, which is precisely what JoyRaptor reports.

Check, in this order:

1. **The signs and the rotation used on each term.** `o0` is folded with `c0/s0` (the old
   rotation) and `o1` with `c1/s1` (the new one). Confirm each δ is folded with the rotation that
   actually applies to it, and that the subtraction is the right way round. Derive it on paper
   first, then compare with the code.
2. **`w`/`h` vs `fit.newW`/`fit.newH`.** `o0` uses the OLD box size, `o1` the NEW one. The size is
   changing by a factor of ~1.8 in these logs, so any mix-up here is large, not subtle.
3. **Why `verifyBake` passes.** There is a 1 px self-check with full rollback and it is NOT firing
   (`rollback` lines all show `tx=0 ty=0 dRot=0` — those are no-op gestures, not caught pops). So
   the verify's model of where the picture lands disagrees with the renderer. **Find out which one
   is wrong before changing either.** Prime suspects: the verify not accounting for
   `target.scaleTo`'s clamp, `moveTo`'s travel clamp, or the aspect chain splitting into
   `scaleX/scaleY`.

## The test that would have caught this

The existing harness proves the bake is appearance-neutral at **one** pose. JoyRaptor's report says the
error is **angle-dependent**. So:

Sweep rotation from −180° to +180° in 5° steps; at each angle, apply a corner-scale drag, run the
bake, and assert the drawn quad's four corners move by **less than 1 px**. Do the same at every
pivot (all nine) and both mirror states. `TransformQuad` has zero imports, so this belongs in the
JVM harness. **A test that only checks one angle cannot see this bug**, which is why it shipped.

## Acceptance criteria

1. Drag a corner and release, at 0°, ±20°, ±45°, ±90°, ±135°: the picture does not move on
   release. **Before/after screenshots from the Note 9 at a minimum of three angles.**
2. The angle sweep harness above passes at every angle, pivot and mirror state.
3. `verifyBake` still catches a genuinely bad bake — prove it by feeding it one.
4. Everything SPEC L achieved still holds: run `run-escape.sh` and confirm JoyRaptor's fixture still
   goes 3.416 → ~0.44 with the picture not moving.
5. `./gradlew assembleDefaultDebug --console=plain` → BUILD SUCCESSFUL; escape, pinbudget, speck,
   flip, mesh, rotation, preview-parity, frame-parity, persist-lint all green.

## Boundaries

- You own `transform/CornerPinTransformHost.java` and `transform/TransformQuad.java`.
- Do **not** touch the bend path (SPEC O owns it), the pasteboard dim, the reframe pill, or
  anything in `timeline/`.
- Note 9 (`<note9-serial>`) is the test device. **Never write to the Note 20.**

## Deliver

The wrong term, with the derivation that shows it is wrong. The angle sweep results. The device
screenshots. Whether `verifyBake` or the renderer was the one with the wrong model. Build verdict;
compile-verified vs device-verified.

**This is the most damaging bug currently in the tool** — it corrupts the result of every single
corner drag. Nothing else in the transform surface matters until it is fixed.
