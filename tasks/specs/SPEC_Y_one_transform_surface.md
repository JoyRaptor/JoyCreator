# SPEC Y — ONE transform host, four adapters

**Difficulty: LARGE, but almost entirely subtraction. Do this BEFORE any new object type.**
**Read `_RULES_READ_FIRST.md`, then `SPEC_J_wiring_staleness_audit.md` §1 — it already caught this
drifting. Then `tasks/GIT_PRACTICE.md` for the identity rule.**

JoyRaptor, 2026-09-13:

> "Make sure that the transform tool and all the holders and behavior being developed is uniform
> across the board and all referencing the same things. So if I need to change something about the
> transform tool, that'll happen to all of them uniformly instead of having everything hard coded
> per situation."

That is a design instruction, and it is the right one. This spec is how it gets met.

---

## What is there now

`TransformOverlayView.Host` is ALREADY the uniform contract — 18 methods, with the bend half
`default`-implemented so an affine type needs none of it. The interface is not the problem.

The IMPLEMENTATIONS are. Four classes, each re-deriving the same geometry against a different
model:

| | lines |
|---|---|
| `CornerPinTransformHost` (image) | 1078 |
| `SpineTransformHost` | 345 |
| `TextAffineTransformHost` | 242 |
| `PipAffineTransformHost` | 217 |

**They have already drifted, and it is already on the record.** SPEC J §1 found the four disagree
about when to call `onChanged`, and found `TextAffineTransformHost.writeSimilarity` ending in a
commented-out call and an unfinished sentence:

```java
// onChanged is already triggered via target's refresh, but ensure
// onChanged.run();
```

That is what "hard coded per situation" produces: a fix lands in one host, and the other three
keep the bug. Adding sprites to this would mean writing a FIFTH, and every future change to the
transform tool would cost five edits and four chances to forget one.

## The change

**One host implementation. Per type, an adapter that supplies ONLY what is genuinely different.**

Everything about a transform that is the same for a sprite, a PiP, a text box, an image and a
spine clip — which is nearly all of it — lives once. `CornerPinTransformHost` is already the most
complete of the four; this is mostly moving its general half behind an adapter and deleting three
partial reimplementations.

### What the adapter owns (and nothing more)

The genuinely type-specific facts, each a small read/write:

- **Where the pose lives** — centre, size, rotation, per-axis scale, mirror. Four types store these
  on four models; that difference is real and irreducible.
- **Where the pin lives**, for a type that has one.
- **Where the mesh lives**, for a type that has one.
- **The item's natural aspect and on-canvas size**, so the quad can be built.
- **How to tell the renderers something changed** — the `onChanged` contract, stated ONCE here and
  obeyed by every adapter, with `FaditorEditorActivity.requestGlPreviewResync()`'s comment as the
  authority on what it exists to prevent.
- **Capabilities:** `canPin()` and `canWarp()`.

### What must NEVER be per-type again

Quad maths, gesture bookkeeping, the fold pivot, the commit-time bake, the bend net, escape/clamp
handling, undo naming, HUD text. If a change to any of these needs touching more than one file,
this spec has not landed.

### Capabilities replace classes

The parity rule stays exactly as it is — **the UI offers a gesture only when BOTH preview and
export can draw it** (`FaditorEditorActivity` ~25544 states it). Today that rule is enforced by
picking a different HOST CLASS per type. After this it is enforced by the target answering
`canPin()` / `canWarp()`, which the one host already consults via `supportsBend()`. Same rule, one
place, and a type gains a capability by flipping a boolean once its renderers are ready — which is
what makes SPEC Z cheap.

**A type whose renderers cannot draw a pin must still answer `canPin() == false`.** Do not "unlock
everything and see". Authoring a distortion nothing can draw is the bug this rule exists to stop.

## Acceptance criteria

1. ONE class implements `TransformOverlayView.Host`. `CornerPinTransformHost`,
   `TextAffineTransformHost`, `PipAffineTransformHost` and `SpineTransformHost` are GONE, replaced
   by four adapters. State each adapter's line count in the report — if any is over ~120 lines,
   something general is still trapped inside it, and say what.
2. **Behaviour is unchanged for all four existing types.** This is a refactor and it must be
   invisible. Image keeps pin + bend; text, PiP and spine stay affine-only; every gesture behaves
   as it does today. Drive all four on the phone and say so per type.
3. The `onChanged` contract is stated once and obeyed by every adapter. Say what it is, and
   confirm SPEC J §1's finding is now structurally impossible rather than merely fixed.
4. A new object type can be added by writing an adapter and nothing else. **Prove it**: state
   exactly which file a fifth type would need, and confirm it is one.
5. `./gradlew assembleDefaultDebug --console=plain` → BUILD SUCCESSFUL, and spect 11/11,
   specr 13/13, specq 7/7, mesh 66/66, spech 36/36, adjust 38/38, matte 25/25, bakepop, escape,
   pinbudget, speck, flip, rotation, preview-parity, frame-parity, persist-lint all stay green.

## Boundaries

- You own `ui/faditor/transform/**` and the `enter*TransformMode` wiring in
  `FaditorEditorActivity`.
- Do **not** change `TransformQuad`'s maths, `transform/mesh/**`, or any renderer. This spec moves
  code; it does not change what is drawn. If a picture looks different afterwards, you have
  changed something you should not have.
- Do **not** add capabilities to types that lack them — that is SPEC Z's job, and it is gated on
  renderer work this spec does not do.
- **The sandbox phone is the test device. NEVER install to or write on the Note 20.**

## Deliver

The adapter interface, in full. The four adapters with line counts. The `onChanged` contract. The
one-file answer for acceptance 4. Per-type device confirmation that nothing changed. Build verdict;
compile-verified vs device-verified, stated plainly per item.
