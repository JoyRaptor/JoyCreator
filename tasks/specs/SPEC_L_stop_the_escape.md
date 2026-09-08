# SPEC L — Why objects escape the canvas, and how to stop it at the source

**Difficulty: HIGH — this is an architecture fix, not a patch. Read `_RULES_READ_FIRST.md` first.**
**Read this whole file before writing code. The fix is small; the reasoning is the hard part.**

## JoyRaptor's verdict, 2026-09-07

> "'Pin limit' shouldn't happen. Nor should there be a stupid chip to tell you it can't do
> something. **The architecture must be wrong.** In every drawing program I have used I have never
> seen a limit on how many times you can mirror... I am not stretching the image THAT much,
> nowhere near what limits should actually be. I see lots greater distortion ability in After
> Effects."

He is right. Six rounds of symptom-fixing (raising the wall ±2 → ±8, drift guards, rebase,
walk-away caps, recovery pills) have not stopped objects vanishing. This spec names the cause.

## THE CAUSE — measured on JoyRaptor's live Note 20 project, 2026-09-07

The problem image (`abaef109`), read straight out of `project.json`:

```
centerX  -0.624        <- the BOX centre is already 0.62 frame-widths off the left edge
centerY   0.591
rotationDeg 196.37
flipH    true
sizeFraction 0.353
pinTLdx -1.019  pinTLdy  1.145
pinTRdx -3.416  pinTRdy  0.675     <- THREE AND A HALF PICTURE-WIDTHS
pinBRdx -2.397  pinBRdy -1.243
pinBLdx -0.659  pinBLdy -1.406
```

Work out where the picture actually is. Corner x, in units of the picture's own width, measured
from the box centre:

```
xTL = -0.5 + (-1.019) = -1.52
xTR = +0.5 + (-3.416) = -2.92
xBR = +0.5 + (-2.397) = -1.90
xBL = -0.5 + (-0.659) = -1.16
```

**Every corner is negative.** The drawn picture is roughly two picture-widths to the LEFT of the
box that supposedly holds it. Add the box centre already sitting at −0.62, and the picture is
multiple frame-widths off screen — while the model thinks the object is only slightly out.

### Why the travel clamp did not stop it

`TextOverlayItem.setCenterTravelLimit` guards **centerX/centerY**. It works, and it is why the
centre only reached −0.62 instead of −10.

**But a corner pin can translate the drawn picture arbitrarily far from its centre, and no clamp
anywhere looks at that.** The pin is a free translation channel that bypasses the only guard the
system has. That is the architecture bug. Everything else — the vanishing, the "pin limit", the
unreachable handles, the recovery pills — is downstream of it.

### Why the pin accumulates translation instead of shedding it

SPEC G's `TransformQuad.normalizePin` exists to solve exactly this: bake the affine part
(translation, rotation, scale, mirror) into the object's own fields and keep only genuine
distortion in the pin. **It is all-or-nothing, and it gives up on the case that matters:**

```java
if (!(mis <= PIN_BAKE_PARALLELOGRAM_TOL)) {
    out.valid = true;
    out.baked = false;              // keep the authored pin bit-for-bit
    System.arraycopy(off8, 0, out.residual, 0, 8);
    return out;
}
```

A parallelogram bakes. **Anything with real perspective in it bakes NOTHING** — so its
translation, rotation and scale stay locked in the pin and accumulate, gesture after gesture,
until the picture is three widths from its own box and every corner is against the wall.

JoyRaptor's shape is a trapezoid. It has never once been baked.

## THE FIX — two parts, in this order

### Part 1: always bake the affine part, even for a non-parallelogram

Replace the all-or-nothing gate with an unconditional affine fit:

1. Least-squares-fit an affine transform (translation, rotation, scale, mirror) to the four
   corners. The code **already computes this** — `tx/ty`, `exx/exy/eyx/eyy`, `thx`, `a`, `b` — it
   just throws the result away when the shape is not a parallelogram.
2. Write that affine into centre / size / rotation / flip flags, exactly as the parallelogram path
   does today.
3. Keep **only the residual** — the corner-by-corner deviation from that best-fit affine — in the
   pin. For a parallelogram the residual is zero (today's behaviour, unchanged). For a trapezoid
   the residual is the *keystone only*, which is small: **typically well under 0.5**, versus the
   3.4 now sitting in JoyRaptor's file.

The existing `verifyBake` (recompute the pose, roll back whole if it drifts over 1px) must still
guard it. If a fit ever fails to reproduce the picture, roll back and keep the gesture — the
existing contract.

**The consequence, and it is the point:** with translation living in the centre where it belongs,
the travel clamp starts guarding the picture again, and the pin only ever carries shape. The
"pin limit" becomes unreachable in normal work — not because the number was raised, but because
nothing is spending it any more.

### Part 2: clamp the DRAWN QUAD, not the box centre

Even with Part 1, one gesture could still fling a picture out. Add a final guard at commit:
**at least ~15% of the drawn quad's bounding box must remain inside the canvas rect.** If a commit
would leave less, translate the pose (not the pin) just enough to satisfy it.

Notes:
- Clamp the **quad**, not the centre — the centre is not where the picture is.
- Translate, never scale or reshape: the user's authored shape is not yours to change.
- This is a backstop. If Part 1 is right, it should almost never fire. Log it when it does
  (`TransformDiag`) so we can tell whether it is papering over something.

## What NOT to do

- **Do not raise the pin wall again.** ±8 is already four times JoyRaptor's heaviest authored work,
  and it did not help, because the budget was being spent on translation rather than shape.
- **Do not add another chip, pill or dialog.** JoyRaptor: *"nor should there be a stupid chip to tell
  you it can't do something."* The tool should not need to explain a refusal, because with Part 1
  there is nothing left to refuse.
- **Do not touch flips or folds.** They are proven exact (`run-flip`) and they already clear to
  mirror flags for free. This spec is about what happens to everything else.

## Acceptance criteria

1. Load a project whose image carries a trapezoid pin. After one gesture, the pin's largest offset
   is under ~0.5 and the picture has not moved on screen by more than a pixel.
2. JoyRaptor's exact stored numbers above, run through the new bake, put the picture back in frame
   with a small residual. **Use them as a fixture in the harness** — they are the real bug.
3. Fold / flip / move / scale, in any order, forty times: no refusal, no growth in the pin, no
   drift off canvas.
4. A genuine keystone still renders as a keystone in preview AND export — this is a change of
   representation, never of appearance. Prove parity.
5. `run-pinbudget`, `run-speck`, `run-flip`, `run-mesh`, `run-rotation`, `run-preview-parity`,
   `run-frame-parity` all stay green.
6. `./gradlew assembleDefaultDebug --console=plain` → BUILD SUCCESSFUL.

## Deliver

The affine fit and where the residual is computed; before/after pin numbers for JoyRaptor's fixture;
proof the picture does not move when a bake happens; whether the Part 2 backstop ever fires in
your tests; build verdict; compile-verified vs device-verified.

**Do not report this done on reasoning alone. It has to be watched on a phone.**
