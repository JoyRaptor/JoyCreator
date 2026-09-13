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

**They have already drifted, and the drift was measured on 2026-09-13** by reading all four
side by side. A correction first, because this spec originally cited SPEC J §1's finding that
`TextAffineTransformHost.writeSimilarity` ended in a commented-out `onChanged.run();` — **that was
already fixed.** All five of its write paths now end in a live call (TX:181, 194, 205, 211, 235).
Citing a stale finding as current is exactly the kind of thing this spec is supposed to stop, so
here is what is actually there instead. Every line is a real asymmetry between four
implementations of one contract:

| | Drift | Evidence | Odd one out |
|---|---|---|---|
| D1 | Minimum size floor is `0.01` / `0.02` / `0.02` / none | CP:329, TX:202, PIP:178, SP:184 | **all four differ** |
| D2 | The `[-2,3]` position clamp exists only in the affine `writeQuad` | TX:173, PIP:156 | CP, SP lack it |
| D3 | `TransformQuad.isValid` re-checked inside the host | TX:115, PIP:109 | CP omits it |
| D4 | Degenerate-rect threshold is `0f`, `1f` and `0.5f` | CP:313, SP:171, CP:105 | three thresholds, one predicate |
| D5 | Diagnostics | CP has ~11 `FLog`/`TransformDiag` sites | TX, PIP, SP have **zero** |
| D6 | PiP's reset wipes SCALE, against the convention CP:681 and TX:219 both state and SP:305 argues for explicitly | PIP:203, 208 | PIP, undocumented |
| D7 | PiP's reset writes keyframes DIRECTLY, reaching around its own target | PIP:195-209 vs ACT:26347 | PIP |
| D8 | **An inert flip still burns an undo step** | VIEW:1551/1558/1564 call begin/flip/commit unconditionally; TX:239 and PIP:214 are empty bodies; the targets record a LambdaAction anyway (ACT:26447) | TX, PIP |
| D9 | Spine's commit is a silent no-op when its snapshot is null | SP:296 | SP |
| D10 | A spine handle drag never refreshes an open drawer's rows | ACT:25146 omits `refreshOpenDrawerRows()` | SP |
| D11 | A text or PiP handle drag never re-syncs the transform overlay | ACT:26063, ACT:26468 omit `transformOverlay.refresh()` | TX, PIP |
| D12 | Dead locals and a thinking-out-loud comment shipped as code | TX:59, TX:104, TX:134-137 | TX |

D8, D10 and D11 are **user-visible today**, not merely untidy: flipping a text box or a PiP does
nothing and costs an undo press; a spine drag leaves the drawer's numbers stale; a text or PiP drag
leaves the transform overlay stale. Fix those three as part of this spec and say so separately —
they are behaviour changes, and acceptance 2 below otherwise forbids behaviour changes.

D1 is the trap for the unification: collapsing four floors into one constant would silently change
the image floor by 2x. It needs a per-type value, not a chosen winner.

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

### The adapter contract, derived rather than guessed

Read out of the four implementations on 2026-09-13. `PreviewHandlesOverlay.Target` already serves
three of them and is most of the answer; `SpineTransformHost.Bridge` is a second shape doing the
same job and folds in as follows:

| Bridge | Target equivalent | Verdict |
|---|---|---|
| `clip()` | none | Not interface at all — the adapter closes over its own model, as the other three do. Drop it. |
| `canvasRect()` | `videoRect()` | Same thing; keep one. |
| `basePictureHalfExtent(float[])` | none | **Genuinely needed**, but expressible: state it as "the unrotated picture rect in overlay pixels", which `Target.frame` answers directly and the spine answers as `canvasRect x halfExtent`. This is the single unification point. |
| `clipLocalMs()` | none (the other three take a `Playhead`) | One `long timeMs()` whose MEANING the adapter defines — the three affine hosts use the overlay clock, the spine converts to clip-local and divides by speed. |
| `onSpineTransformChanged()` | none (the other three take a `Runnable`) | Collapse to one `onChanged()`. See D10/D11 — the two do different work today and must not. |
| `commitSpineTransform(snapshot, what)` | `commit(what)` | Use the Target convention: the ADAPTER owns its snapshot. That removes a spine-only type from the interface. |

**Scale needs two methods, not one.** CP/TX/PIP write one opaque `sizeFraction`; the spine writes a
uniform `SC` for a pinch and per-axis `SX`/`SY` for a corner drag. The split already exists inside
CornerPin — its commit bake writes `scaleTo(uniform)` plus `setScaleX/Y` plus `setScaleLinked(false)`
(CP:498-514), which is structurally the spine's pair. So:

- `scaleTo(float uniform, long t)` — every type
- `scaleAxesTo(float sx, float sy, long t)` — default no-op; CP and SP implement it
- `float minSizeFraction()` — so D1 is per-type data instead of a magic number

**The unit stays opaque.** `sizeFraction` is a fraction of frame height for text and images, a
native multiplier for PiP, and a multiplier against the fit-centred half-extent for the spine.
These unify ONLY because the view never interprets the number — it computes `startSize * factor`
and hands it back. The unified host must preserve that and must never compare a size against a
pixel value.

**Also adapter hooks, because the four bodies are genuinely different:** `reset()`, `flip(boolean)`,
`supportsPin()`, `supportsBend()`, `supportsFlip()`, `readFoldPivot()` (defaulting to `readPivot`,
which is what three of the four already do).

---

## STATUS, 2026-09-13 (autonomous session)

**Stage 1 DONE.** `TextAffineTransformHost` and `PipAffineTransformHost` are gone, replaced by one
`AffineTransformHost`. Net −135 lines. The two things a type may differ about are now data: the
size floor is a parameter (it was 0.02/0.02/0.01 — collapsing to one constant would have changed
the image floor by 2x) and the reset policy is a hook (text keeps its size, PiP returns to its
creation pose — a divergence that used to be silent). Verified the old classes are actually gone
from the packaged dex, not merely unreferenced: they survived two rebuilds as leftover `$Playhead`
entries because javac does not delete outputs for deleted sources.

**Acceptance 4 is already demonstrated.** Sprites became the fifth object type on the surface and
cost an adapter and a mode entry — no new class in `transform/` at all.

**The optional `PinChannel` landed with them**, which is the pattern stages 2 and 3 should follow:
a capability is a narrow interface a type supplies once its renderers can draw the result, not a
subclass.

**Stages 2 and 3 remain**, and they are the hard half:

- **The spine** needs per-axis scale on the adapter. It writes a uniform `SC` for a pinch and
  per-axis `SX`/`SY` for a corner drag; the shared host writes one uniform factor. Merging naively
  would cost the spine its non-uniform scaling, which acceptance 2 forbids. The design is in this
  spec already (`scaleAxesTo`, defaulting to a no-op).
- **The image host** is ~1078 lines of which roughly 740 are pin/mesh-specific — the commit-time
  bake, `verifyBake`, the escape clamp, the armed-keyframe surgery and the whole bend seam. Its
  general third is what `AffineTransformHost` now holds. Moving the rest behind the channel pattern
  is the biggest single piece of remaining work on the transform surface, and it touches the most
  device-verified code in the app, so it wants its own session and its own device pass rather than
  the tail of somebody else's.

**Drift items D8, D10 and D11 were fixed separately** (they were user-visible), and a FOURTH
instance of the same refresh drift turned up in the sprite write path when SPEC Z forced it to do
what its siblings do. Four write paths had four different subsets of the same four calls.

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
