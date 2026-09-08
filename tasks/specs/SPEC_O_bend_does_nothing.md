# SPEC O — Bend is reachable now, but dragging a dot still does nothing

**Difficulty: MEDIUM-HIGH. A diagnosis job, not a design job. Read `_RULES_READ_FIRST.md` first.**

## Status

SPEC M fixed **access** to Bend: the net's dots are pulled inboard off the corner glyphs, and a
persistent "Bend" pill turns the mode on and off. Both verified on the Note 9.

It did **not** fix the thing JoyRaptor actually complained about:

> "Bend landed, doesn't work."

**That is still true.** Dragging a net dot deforms nothing.

## The device repro — done 2026-09-08 on the Note 9, reproduce it before theorising

App → Faditor → project `BundlingFontTest` → tap the image in the preview (it selects; the
transform surface comes up) → tap the **Bend** pill (it turns blue and reads "Bend · on"; the net
appears, dots correctly inboard of the amber handles).

Then drag a dot. The dot centres were located by colour-sampling the screenshot, so these are real
coordinates, not estimates:

```
adb -s SANDBOX_SERIAL shell input swipe 693 337 560 480 1200
```

`(693, 337)` is the centre of a net dot to within 4 px, and the hit radius is 18dp (~54 px on this
device), so the grab cannot have missed.

**Result: the picture and the net are pixel-identical before and after.** Two screenshots, no
difference anywhere in the frame.

## What the logs say

`files/faditor/transform-diag.log` after the drag:

```
1788890504502 rollback id=3dfa9d32-… rot0=-27.251049 tx=0.0 ty=0.0 dRot=0.0 …
```

So a gesture **did** run and commit — the bake found nothing to change and rolled back, which is
correct for a gesture that changed nothing. There is **no bend line of any kind**, and
`project.json` contains **no `"mesh"` key** afterwards.

So: the touch reaches the surface, a gesture begins and commits, and the deformation never happens.

## The chain to walk, with line numbers

`CornerPinTransformHost.bendDragTo` (~line 926). It can return false at **eight** different points
and every one of them is silent:

1. non-finite input
2. `bendEnsureSpec()` returns null or has no topology (~929)
3. handle index out of range (~932)
4. `bendDeformerFor(topo)` null, or `!deformer.supports(topo)` (~935)
5. arity out of range (~937)
6. **`MeshProjection.dragToHandle(...)` returns false (~941)**
7. **`MeshGuard.accepts(...)` refuses (~950)**
8. `s.handles()` null or wrong length (~953)

...and then the whole method is wrapped in:

```java
} catch (Exception ignored) {
    return false;
}
```

**Start by deleting that silence.** Log which of the eight it is — `TransformDiag` already writes
to a file you can pull off the phone, and the recorder pattern is established. One line naming the
branch turns this from a mystery into a one-line fix. Do that FIRST, reproduce on the Note 9, read
the log, and only then write the real fix.

## Two things worth checking early

**`hasMesh()` is `mesh != null && mesh.hasWarp()`.** A freshly created identity lattice has no
warp, so `hasMesh()` stays false until handles actually move. Confirm nothing in the render or
routing path needs `hasMesh()` to be true *before* the first deformation can be applied — if it
does, that is a chicken-and-egg deadlock and it is your bug.

**`wantsGlExport() |= hasMesh()` routes a bent image to the GL chain.** Check what happens on the
very first drag, when the item is mid-transition between the Canvas path and the GL path. A bend
that is stored but drawn by the Canvas path would look exactly like this: nothing happens.

## Acceptance criteria

1. Dragging a net dot visibly bends the picture in the preview, **proven with before/after
   screenshots from the Note 9**. This is the entire point of the spec; nothing else counts.
2. `project.json` gains a `"mesh"` key after a bend, and the bend survives save/reload.
3. The bend renders the same in preview and export. State the shared source of truth.
4. One dot-drag = one undo press, and the first undo restores "no bend at all".
5. No silent failure path remains in `bendDragTo` — every refusal is logged with its reason.
6. An unbent project still saves byte-identically and costs nothing.
7. `./gradlew assembleDefaultDebug --console=plain` → BUILD SUCCESSFUL; `run-mesh` (66/66),
   `run-spech`, `run-pinbudget`, `run-speck`, `run-flip`, `run-escape`, `run-rotation`,
   `run-preview-parity`, `run-frame-parity`, `run-persist-lint` all green.

## Boundaries

- You own `transform/CornerPinTransformHost.java` (the bend methods only), `transform/mesh/**`,
  and the mesh render paths in `compositor/` and `export/`.
- Do **not** change `TransformQuad.normalizePin`, flip/fold, the pasteboard dim, the reframe pill,
  or anything in `timeline/`.
- Do not touch the Note 20. The Note 9 (`SANDBOX_SERIAL`) is the test device.

## Deliver

Which of the eight branches was actually failing, and the log line that proved it. The fix. The
before/after screenshots. Build verdict; compile-verified vs device-verified.

**A report that says "fixed" without a screenshot of a bent picture will be rejected.** This
feature has now been reported complete twice while doing nothing.
