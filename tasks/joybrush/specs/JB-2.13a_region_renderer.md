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

