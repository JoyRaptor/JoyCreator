# JB-2.13a — RegionRenderer: flatten any rectangle of the document to pixels (CPU)

| | |
|---|---|
| **Tier** | T2 |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-0.02 (Built) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/render/RegionRenderer.kt`, NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/render/Blend.kt`, NEW `.../commonTest/.../render/RegionRendererTest.kt` |
| **Estimated size** | ~250 lines + ~200 lines of tests |

## Goal
Every export (PNG, OpenRaster, GIF, video, sprite sheet, thumbnails) needs "the picture inside this
rectangle, at this frame, with or without paper". One tested CPU function does it, so every export
agrees with every other.

## Contract
```kotlin
package cc.joycreator.joybrush.core.render

/** Supplies a tile's premultiplied RGBA8 pixels (262,144 bytes, row 0 = top) or null if empty. */
fun interface TileSource { fun tile(layerId: String, celId: String, tx: Int, ty: Int): ByteArray? }

object RegionRenderer {
    /**
     * Composites the visible layers of [doc] (bottom → top, each through its cel for [frameId] via
     * DocOps.celFor) inside [rect], using each layer's opacity and blend mode.
     * @param paper if non-null (#RRGGBB) the result is composited over that opaque colour first.
     * @return STRAIGHT (un-premultiplied) RGBA8, rect.w × rect.h × 4 bytes, row 0 = top.
     */
    fun render(doc: JbDocument, tiles: TileSource, rect: RectPx, frameId: String?, paper: String?): ByteArray

    /** Same, but premultiplied float RGBA (for further compositing / tests). */
    fun renderPremultiplied(doc: JbDocument, tiles: TileSource, rect: RectPx, frameId: String?, paper: String?): FloatArray
}
```
`Blend.kt`: one function per `BlendMode` on premultiplied colour `(s, d) → out`, the standard
separable formulas (W3C Compositing Level 1), all with source-over alpha `a = sa + da(1 − sa)`:
NORMAL, MULTIPLY, SCREEN, OVERLAY, ADD (clamped), DARKEN, LIGHTEN. `ERASE_BELOW` = destination-out
(`out = d × (1 − sa)`).

## Decisions
1. Work in float, 0..1. Convert bytes /255; output bytes = round(clamp(v) × 255).
2. Layer opacity multiplies the layer's premultiplied pixel before blending.
3. Invisible layers are skipped. Missing tiles are transparent.
4. Un-premultiply at the end: straight = premult / alpha (alpha 0 → rgb 0).
5. Rects may cross tile boundaries and may have negative coordinates.

## Tests
1. One NORMAL layer, one opaque red tile, rect inside → all red, alpha 255.
2. Rect straddling 4 tiles incl. negative coordinates → correct pixels at each tile edge.
3. Paper `#FFFFFF` under a 50%-alpha black layer → mid grey, alpha 255; paper null → black at alpha 128.
4. Each blend mode against a known pair of colours matches the W3C formula (compute expected by hand).
5. Layer opacity 0.5 halves alpha. Invisible layer ignored.
6. Animated layer: frame 1 vs frame 2 pick different cels.

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures.

## Definition of done
Tests pass (paste) · commit `JB-2.13a: region renderer` · ROADMAP row → 🟧 Built.

## Questions

### Q1 — LEAD RULING NEEDED: the pixel budget `MAX_REGION_PX = 8,388,608` (2²³)

Found by review (muse-spark, BLOCKER) and fixed, but **the number is mine, not the Lead's**, and it
is a product decision in disguise. Escalating rather than burying it.

The arithmetic, verified and reproduced in `RegionRendererTest`, not taken on trust:

| | pixels (`w*h`) | × 4 bytes | what the old code did |
|---|---|---|---|
| 30,000 × 30,000 | 900,000,000 | 3,600,000,000 | wraps in `Int` to **−694,967,296** → `NegativeArraySizeException` |
| 20,000 × 20,000 | 400,000,000 | 1,600,000,000 (legal `Int`!) | no wrap; asks for 6.4 GB of floats → `OutOfMemoryError` |

Both are reachable from `render(doc, tiles, board.rect, …)`, because `DocOps.validate` accepts any
board rect with `w > 0, h > 0` and does not validate at all.

**What I chose and why.** `2^23` px. One `render` holds the result and the float scratch at once, so
the live footprint is 20 B/px → **exactly 160 MiB** (167,772,160 B), which is ~3/5 of the 256 MB
per-app heap a **Note 9** (the ruled floor: SD 845, 6 GB, Android 8) reports to
`ActivityManager.getMemoryClass()`. The power of two is deliberate — every derived size then lands on
a round number of MiB, so the KDoc's claim is arithmetic rather than an estimate, and a test pins it.

**The property that makes it defensible rather than merely plausible:** 3840 × 2160 = 8,294,400 px is
**94,208 under the cap**, so no 4K export — the largest thing this app is realistically asked to do —
is refused. A test enforces that sentence, so lowering the constant later breaks a build instead of
shipping a regression.

**What I need ruled:**
1. Is 2²³ px the right ceiling, or should it track a different floor device? One edit; the tests
   guard the invariant either way.
2. Should a board larger than the budget be **refused**, or should it be a **document-level validation
   problem** (`DocOps.validate` flagging an unexportable board at open time)? Today it is a refusal at
   export time, which is correct but late — the person finds out when they press Export. I did not
   touch `core/doc/`, so this is a question, not a change.
3. Is "render it in strips and stitch" an acceptable answer we want to *document* for exporters, or
   should the budget be high enough that no real board needs it? I have assumed yes, and said so in
   the constant's KDoc.

### Q2 — LEAD RULING NEEDED: the GPU parity gap is real and latent (6 of 8 modes)

Review Finding 2 (MAJOR) is **confirmed, and the implementer is the one who found it** —
`GlPaintEngine` implements no blend modes at all:

- `jb_tile.frag:13` is `o_color = texture(u_layer, v_uv) * u_layerOpacity;` — no mode uniform, no
  mode branch, no second program.
- `GlPaintEngine.kt:332` calls `glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)` **once, before the layer
  loop**, and never changes it. That is source-over, i.e. `NORMAL` only.

So **`NORMAL` and `ERASE_BELOW` agree with the GPU** (the latter via `jb_commit.frag:21`,
`dst * (1.0 - a)` on the whole `vec4`). **`MULTIPLY`, `SCREEN`, `OVERLAY`, `ADD`, `DARKEN` and
`LIGHTEN` do not**: they composite source-over on the phone and per W3C in every export. That is a
different *picture*, not a rounding difference.

**Code is unchanged; only the KDoc was corrected** to say exactly this, with the shader line as
evidence. The old wording ("the GPU composites with the same rules") was false and would have misled
JB-2.04 into assuming parity was already done.

**What I need ruled:** does **JB-2.04** (layers into the view) own adding the missing modes to the
GPU, or is that a separate task that should be opened now? Nothing in `commonMain` can currently
produce a non-NORMAL layer, so nothing diverges *today* — but the moment JB-2.04 lands, a person
with a MULTIPLY layer sees one thing and exports another. I have recorded it as latent, and I have
deliberately **not** written a "parity" test that would pin the wrong behaviour as correct.

### Q3 — NOT FIXED, ESCALATED: coordinate overflow gives a silent wrong answer

Review Finding 1.5. I fixed the **allocation** half of the BLOCKER and deliberately left this half
alone; it is a wrong-answer bug rather than a crash, it is outside the briefed fix, and the only cure
changes behaviour for rects that currently render (wrongly). Flagging rather than quietly widening
scope.

`RegionRenderer.kt:228,231` — `rect.x + rect.w - 1` overflows near `Int.MAX_VALUE`. With
`rect = RectPx(Int.MAX_VALUE, 0, 8, 8)`, `xLast` comes out at **−2,147,483,642**, so the tile walk
gets `tx0 = 8,388,607` and `tx1 = −8,388,608` — a range whose start is past its end, so the loops
never run and the renderer returns a **fully transparent region with no error at all**. Silent, and
it is exactly the shape of hostile input the budget was added to survive.

Needs a ruling: refuse an extent that will not fit `Int` (a one-line `Long` check next to the budget),
or is a board coordinate near `Int.MAX` considered out of scope for an unbounded canvas?

### Q4 — ANSWERED, NOT ASKED: the parity gap is 26 modes wide, not six, and only ONE is on the GPU

Re-review pass, after **JB-2.20a** landed. Q2 above is the right finding and the wrong number, and
the wrongness is worse than staleness: it was naming **JB-2.04** as the task that owes the GPU the
modes, and **JB-2.04 is not that task.** The GL layer compositing row is **JB-2.20b** ("GL layer
compositing with the Studio's `GLSL_BLEND_FN` — preview = export", T1, `⚪ Outline`, depends on
2.20a), and JB-2.20a decision 6 already says so and forbids any UI offering a non-NORMAL mode until
it lands. A reader who trusted Q2 would have gone to look in the wrong row and found nothing.

So the corrected claim, and the counts that make it a claim rather than an opinion:

| | CPU (`RegionRenderer` / `Blend`) | GPU layer path (`GlPaintEngine`) |
|---|---|---|
| modes implemented | **27 of 27** | **1 of 27** — `NORMAL` only |

The GPU's one is source-over by **fixed function**, which is why nothing in the shader source can be
grepped to find the absence: `jb_tile.frag:13` is `o_color = texture(u_layer, v_uv) * u_layerOpacity`
— no mode uniform, no mode branch, no second program — and `GlPaintEngine.kt:367` sets
`glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)` once, before the layer loop, and never changes it.
Decisively: `GlPaintEngine`'s own layer record (`:58-62`) holds `id`, `opacity`, `visible` and `tiles`
and **no blend field at all**, so there is nothing for a mode to arrive in. Not a missing branch — a
missing field.

**The twenty-six CPU-only modes**, now in the KDoc by name: MULTIPLY, SCREEN, OVERLAY, ADD, DARKEN,
LIGHTEN, ERASE_BELOW, and the nineteen JB-2.20a appended — DIFFERENCE, COLOR, COLOR_DODGE,
COLOR_BURN, LINEAR_BURN, HARD_LIGHT, SOFT_LIGHT, VIVID_LIGHT, LINEAR_LIGHT, PIN_LIGHT, HARD_MIX,
EXCLUSION, SUBTRACT, DIVIDE, DARKER_COLOR, LIGHTER_COLOR, HUE, SATURATION, LUMINOSITY.

**One correction to Q2 that matters more than the count.** Q2 called `ERASE_BELOW` an agreement with
the GPU. It is not one. `jb_commit.frag:21`'s `dst * (1.0 - a)` is driven by the **`u_erase` uniform**
— that is the eraser TOOL writing into a tile, and it does make an erased pixel agree screen-versus-
export, which is why the grey-smear failure mode is structurally impossible. But the GL engine
compositing an `ERASE_BELOW` **layer** would do it source-over like any other. So `ERASE_BELOW` is
one of the twenty-six, and the eraser-tool fact is recorded separately and explicitly NOT counted as
layer parity. Getting this wrong in the other direction is how a real divergence gets excused as a
known agreement.

**Still latent, still not a parity test.** Nothing in `commonMain` can produce a non-NORMAL layer and
JB-2.20a decision 6 forbids offering one, so nothing diverges *today*. I have deliberately not
written a test that pins source-over as the correct GPU answer — that is the wrong behaviour, and a
test would make it expensive to fix. What I did write is
`theParityClaimNamesExactlyTheModesEachSideHas`, which pins the **claim**: both halves named, and
required to partition the enum. A twenty-eighth mode now breaks a test rather than quietly making
the KDoc a lie — which is the whole point of finding 2, since a KDoc that lies about wrong pixels is
the defect rather than the documentation of it.

### Q5 — ANSWERED IN THE SOURCE, NO RULING NEEDED: the paper precondition, and the "non-finite" half of Finding 1

Review Finding 3, and a piece of the test brief that cannot be satisfied as written and is better
said out loud than faked.

**Finding 3 is fixed at the door.** Invalid `paper` still throws — `IllegalArgumentException`, never a
guessed colour, for the reason `parsePaper` already gave: defaulting would silently export a black
background, the one answer nobody mistakes for a bug. What changed is that the check is now its own
function (`requirePaperIfAny`) called by **both** public doors *before* the allocation, instead of
being buried in a helper `render` only reached indirectly. The observable difference is the empty
region: both doors return early for a 0 × 5 rect, and that return happens before the paper is read,
so with the check buried in the helper `render` of an empty region with a nonsense paper colour
returned an empty array and said nothing. It is a small thing and it is exactly the shape of Finding
3 — a precondition enforced somewhere other than where the request is made. The class KDoc now also
states the half that was missing outright: **these functions do not validate the document.** They
read it as given; `DocOps.validate` belongs at the open door, where there is a person to tell, and a
renderer that re-validated per call would make every export pay for a check that is not its job.

**On "non-finite dimensions", which I did not fake.** `RectPx` is four `Int`s. A NaN or an infinity
is not a value these functions can be handed, so there is no non-finite dimension to refuse and
inventing a door for one would be theatre. The nearest thing in the request that CAN be non-finite is
a layer's `opacity`, and that is neutralised rather than refused on purpose — a document that means
0% should render nothing, not fail — which was already tested. The dimensional arithmetic is exact at
the extremes instead, and that IS tested: `theLargestRectTwoIntsCanDescribeIsRefusedInWordsRatherThanWrapping`
pins that `Int.MAX_VALUE` squared is 2^62 − 2^32 + 1, that truncating it to 32 bits gives **1**, and
that the request is refused anyway. A guard doing `w * h` in `Int` would believe it had been asked
for a single pixel and hand back a 4-byte image for the largest rectangle that can be written down —
silent, and a caller would blame the export rather than the guard. The size arithmetic is `Int` and
`Long` end to end and never touches a float, so there is no float to go non-finite in the first place.

### Q6 — the Review's §5b evidence is now stale in a way that would hide a regression

Not a finding; a process note for whoever re-reviews. The suite is **642 tests** and
`RegionRendererTest` is **47**. A reviewer who reads "34/34" from the original build and does not
re-run will not see that the budget tests, the parity-claim tests and the paper tests are new work
rather than the original six spec tests, and cannot tell a silently-deleted assertion from one that
was never written. The command and the counts belong in this file, not only in a build log.


