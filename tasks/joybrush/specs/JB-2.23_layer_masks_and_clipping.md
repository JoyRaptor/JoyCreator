# JB-2.23 — Layer masks and clipping: a shape as a mask, and a shape as a gradient map

| | |
|---|---|
| **Tier** | T1 |
| **Status** | 📝 Draft spec — see ROADMAP.md |
| **Needs** | JB-2.21 (FILTER layers, the `FxCpu` discipline) |
| **Owner area** | EDIT `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/DocModel.kt` (`Layer.mask`, `Layer.clip`, `DOC_VERSION = 4`); EDIT `.../doc/DocOps.kt` (validate); EDIT `.../commonTest/.../doc/EnumFreezeTest.kt`, `DocModelTest.kt`; NEW `.../render/LayerMask.kt`; NEW `.../commonTest/.../render/LayerMaskTest.kt`; EDIT `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt` (the masked / clipped pass in the composite stack) |
| **Estimated size** | ~280 lines of core + ~240 lines of tests; ~200 lines of engine |

## Goal

The ROADMAP row, verbatim, and it is two features in one row because the owner said so:

> *"Layer masks and clipping — a fill-pen shape **can be a mask**; an adjustment layer **clipped to
> a shape** = 'set a shape as a gradient map' (owner)."*

So:

1. **Every layer can carry a mask**, and a **fill-pen shape can become one** — which is the whole
   point of the fill pen in this role. R20 makes the shape a *recording*; R21 makes it a brush. A
   shape that is a mask is a shape that is also an editable boundary.
2. **A FILTER layer can be CLIPPED to a layer below** (Photoshop's "clipped layer"), and when the
   filter is a **gradient map**, "clipped to a shape" is the owner's **"set a shape as a gradient
   map"**: the shape decides *where* the remap applies, so the colour outside the shape is
   untouched.

## 🔴 What makes this row T1 rather than T2

The document half is small and ordinary. The expensive half is that **masks and clipping are
compositing rules, and this project has one compositor** (`RegionRenderer`, every exporter) and one
GPU compositor (JB-2.20b's composite stack, every preview). **A mask that works in the preview and
not in an export is the single worst bug this project can ship**, and the rule that prevents it is
the same one JB-2.21 inherits: **an effect is offered only when its CPU twin is proven equal**.

So this row is specified as **one rule, defined once in core, called by both sides** — not as a
mask in the renderer and a mask in the engine.

## Contract

```kotlin
package cc.joycreator.joybrush.core.doc

@Serializable data class Layer(
    // … every existing field …
    val mask: String? = null,       // a CEL id on THIS layer, or null
    val clip: Boolean = false,      // Photoshop's clipped layer: composite ONLY into the layer below
)
```

`DOC_VERSION = 4`. `DocJson.decode` accepts 1, 2, 3 and 4; `encode` writes 4.

```kotlin
package cc.joycreator.joybrush.core.render

/**
 * The masking and clipping RULES, as pure functions over the document's layers — so the CPU
 * renderer and the GPU pass cannot each have one.
 */
object LayerMask {
    /**
     * Coverage 0..255 at a document pixel for [layer]'s mask, or 255 where there is no mask.
     * A mask cel holds ONE BYTE PER PIXEL in the R channel; alpha is ignored.
     */
    fun coverageAt(maskCel: Cel?, tiles: TileSource, x: Int, y: Int): Int

    /**
     * Whether [layer] is composited into the layer below it, or into the running stack.
     * A clipped layer with NO layer below it (i.e. it is the bottom) is not clipped — validate
     * refuses that at open, and this function agrees rather than guessing.
     */
    fun isClippedInto(layerIndex: Int, layers: List<Layer>): Boolean

    /**
     * The GPU's per-pass uniform for a clipped FILTER layer: which layer's composite it reads.
     * Null = the running stack. Named so the two sides ask the same question.
     */
    fun clipBaseOf(layerIndex: Int, layers: List<Layer>): Int?
}
```

## Decisions

1. **A mask is a CEL on the layer, one byte per pixel, in the R channel.** Not a new file type, not
   a new tile format, not a new `Cel.kind`. A `Cel` already exists and already means "the pixels of
   this layer at this point in time", and a greyscale mask is exactly that. `Cel.tiles` holds the
   keys; the tile bytes are premultiplied RGBA8 like every other tile, with **R = coverage, G = B = 0,
   A = 255** so the tile is a valid tile for the archive, the exporter and the GPU with no special
   case anywhere. **The alternative — a single-channel tile — would need a second tile format, a
   second archive entry name and a second GPU upload path**, for a mask that is one channel of
   something that already exists.
2. **A mask's coverage is `R`, and alpha is ignored — stated in the mask's own KDoc and in the
   archive's.** A mask tile whose alpha is 128 is fully opaque coverage 128, not half. A builder who
   averages alpha in produces a mask nobody asked for.
3. **A layer with no mask is fully covered (255) everywhere** — `coverageAt` returns 255, not 0.
   The polarity is the one that makes "no mask" the identity, and getting it backwards makes every
   unmasked layer invisible, which is loud; getting it *half* right is what the tests are for.
4. **Clipping is Photoshop's: a clipped layer composites ONLY into the nearest opaque layer below
   it, and is itself clipped.** Not "the layer directly below" (a run of clipped layers all target
   the same base) and not "everything below". The run is resolved by `clipBaseOf`, once, and the
   resolved index is what the GPU pass binds and the CPU renderer walks. **The resolution is a
   function, not a loop inside each compositor** — two implementations of "which layer do I clip
   into" is precisely how a preview and an export diverge.
5. **A clipped layer whose resolved base is itself hidden or fully transparent composites to
   nothing** and says so once (in the layer panel's row marker), not per frame.
6. **A layer's mask multiplies its OWN pixels, before its opacity and before its blend** — the
   Photoshop order, and the order the arithmetic below states: `contribution = pixels × mask`, then
   `× opacity`, then `blend` into the backdrop. A mask applied after the blend would clip the
   *result*, which is a different picture.
7. **"Set a shape as a gradient map" is exactly: a FILTER layer with a `gradient map` effect,
   clipped to a layer that carries the shape as its mask.** No new effect, no new storage, no new
   format. The owner's two sentences compose: the shape becomes a mask (feature 1) and the filter
   is clipped to it (feature 2). **This is the row's central simplification and I want it checked
   before a builder starts** — see Question 1.
8. **A fill-pen shape becomes a mask by being rasterised into a mask cel: `SelectionMask` →
   `GradientPaint`-style bytes → a new cel on the target layer.** The coverage is the mask's
   coverage, byte for byte — no anti-aliasing change, no resampling, so a soft lasso makes a soft
   mask. **The shape itself is NOT retained** unless it is an ink layer's recorded stroke, in which
   case re-brushing (R20) can change it and the mask does not follow. Stated plainly, because it is
   a limitation: **a mask made from a fill-pen shape is a snapshot, not a live link.** Making it
   live needs the mask to reference a stroke id, which is a Phase 5 question (JB-5.03).
9. **The mask is editable by painting on it**, which is what makes it a mask and not a stencil: a
   stroke begun on the mask channel paints greyscale, and a stroke begun on the layer paints colour.
   **The rule for which one a stroke goes to is: the layer is MASK SELECTED, and that is a state a
   person sets on purpose** (one tap on the mask thumbnail). It is never guessed from where the pen
   landed, because a pen landing on a mask and a pen landing on the art are the same event.
10. **A layer with a mask whose cel is missing is a validation refusal in words at open** — never a
    silently unmasked layer, and never a crash in the exporter. This is the same posture as
    `JbCanvasView.refusalFor`'s "the drawing lists tile X, and this file does not have it".
11. **`ERASE_BELOW` and a mask interact in the only way that is not ambiguous:** the mask is applied
    to the layer's own contribution first (Decision 6), and then the blend applies. So a masked
    `ERASE_BELOW` erases only inside its mask, which is the useful and unsurprising answer.
12. **The GPU pass is a multiply in the composite shader, not a second render target.** JB-2.20b's
    composite already samples each layer's tiles; a mask is a second sample and a multiply, in the
    same pass. Clipping is a **change of input** (bind the base's composite instead of the running
    stack), which is a uniform, not a pass. **So neither feature costs an extra full-viewport
    pass**, and this is worth stating because a builder's first instinct is a stencil buffer.

## Tests

`LayerMaskTest` (JVM, `:core:jvmTest`):
1. **`coverageAt` with no mask is 255 everywhere**, at three coordinates including a negative one.
2. **A mask cel's coverage is its R channel, and alpha is ignored** — a tile with R=200, A=0 gives
   **200**, not 0 and not 100. (This is Decision 2, and it is the test that stops the average.)
3. **A mask across a tile seam** (the mask's own tiles are sparse and keyed like every other tile)
   gives the same coverage as a single-tile render shifted — the JB-2.05a seam test, applied to the
   mask.
4. **Clipping resolution:** a stack `[A, B, C]` where C is clipped and B is not → C's base is A.
   Where `[A, B, C]` with B and C both clipped → **both** resolve to A (Decision 4's "run", the case
   that is easy to get wrong). Where the clipped layer is the bottom → not clipped, and
   `DocOps.validate` refuses the document in words (the test asserts both agree).
5. **A mask multiplies BEFORE opacity and before the blend** (Decision 6), and this is tested on
   the *composite*: a layer at 50 % opacity with a 50 % mask over an opaque backdrop gives a
   specific value, and the three orders (mask-then-opacity, opacity-then-mask,
   mask-after-blend) give **three different numbers** — the test asserts the chosen one and names
   the other two in a comment, so a reorder is a visible change rather than a silent one.
6. **`RegionRenderer` honours both** and the result is asserted against a hand-computed pixel for a
   document with (a) a masked layer, (b) a clipped layer, (c) both, over an opaque and over a
   transparent backdrop.
7. **A fill-pen shape becomes a mask byte-for-byte:** a soft lasso's `SelectionMask` coverage and the
   resulting mask cel's R channel are **equal at every pixel**, including the anti-aliased edge
   (coverage 96, 160, etc.), and the mask is a single cel on the target layer with the shape's
   `bounds()` as its extent.
8. **The doc round-trip:** a document with a mask and a clip decodes and re-encodes with both
   intact; a **v3 document decodes unchanged** and a v4 one round-trips; `EnumFreezeTest` pins
   `DOC_VERSION == 4`.
9. **Validation, in words:** `mask` naming a cel the layer does not have; a mask on a FILTER layer
   (a filter has no pixels to mask — the mask would go on its clip base, and that is a different
   operation, so it is refused rather than quietly ignored); a clipped FILTER layer with no base.
10. **A source-level check** that `GlPaintEngine.kt` and `RegionRenderer.kt` both call
    `LayerMask` and that **neither contains its own copy** of the "which layer do I clip into" or
    "what is a mask's coverage" logic. **This is the test that makes Decision 4's "one function"
    structural**, and it is the test that would fail if a builder wrote the GPU version twice.
11. **The CPU twin rule is inherited, not re-invented:** `LayerMask` is pure, so `RegionRenderer`
    and the GPU pass get it for free — and test 6 is the assertion that they do.

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures. Then the watcher green.

## Owner check (Note 9)

Draw a lasso, set it as the mask on the layer below → art outside the lasso disappears, the lasso
edge is soft. Paint on the mask thumbnail → the paint removes rather than adds. Add a FILTER layer
with a gradient map, lasso a shape, clip the filter to the layer carrying that shape → **the colour
inside the shape is remapped and outside it is untouched** (the owner's "set a shape as a gradient
map"). Unclip → the remap applies everywhere. Export PNG and PSD → both match the screen, and the
PSD's layers carry their masks as PSD layer masks (the format has them; that mapping is the one
place this row touches another exporter, and it is named here so it is not a surprise).

## Do not

- **Do not implement the mask or the clip resolution in the engine.** `LayerMask` is the one place
  (Decision 4, test 10).
- Do not make a fill-pen shape a LIVE mask (Decision 8) — that needs a stroke reference and is
  JB-5.03's to rule on.
- Do not add per-effect masks, vector masks, or a mask's own blend modes.
- Do not guess which channel a stroke belongs to from where the pen landed (Decision 9).
- Do not touch `FxCpu` or `FxRegistry` — JB-2.21's, and the "offered only with a proven twin" rule
  applies to gradient map exactly as it does to every other effect.
- Never run gradle on the owner's PC.

## Definition of done

- [ ] tests pass (paste)
- [ ] `git status --short` shows only owner-area files
- [ ] watcher green
- [ ] owner check noted
- [ ] committed `JB-2.23: layer masks and clipping`
- [ ] ROADMAP row → 🟧 Built

## Questions

_(Spec writer, `openrouter/stealth/space-bunny-alpha`, 2026-09-29.)_

### 🔴 For the Lead

1. **Decision 7 — "set a shape as a gradient map" composed out of the two features is my reading of
   the owner's sentence, and I want it confirmed before anyone builds it.** The row says *"a
   fill-pen shape can be a mask; an adjustment layer clipped to a shape = 'set a shape as a gradient
   map'"*. I have implemented that as: a shape → a mask on a layer; a FILTER layer with a gradient
   map clipped to that layer. **So the two clauses are one mechanism, and no new effect is
   introduced.** The alternative is a first-class "shape" object that a gradient map takes directly
   — a third thing, with its own storage, its own version and its own export story. **My version is
   a composition; the other is a feature.** If the owner meant the feature, this row is a third of
   the size it should be.
2. **A fill-pen shape as a mask is a SNAPSHOT, not a live link (Decision 8).** Draw a shape, mask
   with it, then re-brush that shape to a pencil (R20's gift) → **the mask does not move**, because
   the mask is pixels and the shape is a recording. On an INK layer the shape IS the recording and
   one could imagine the mask following it, which would be lovely and is a Phase 5 question
   (JB-5.03 re-brushes strokes; does re-brushing update masks that came from them?). **I have ruled
   it out of this row. Confirm.**
3. **Which channel a stroke goes to is a person-made choice (Decision 9), not a hit test.** The
   alternative — a pen landing on the mask thumbnail's *area* paints the mask — is what Krita does
   and it is a nice trick, and it is also a way to paint greyscale by accident. **I chose the
   explicit selection because a surprise greyscale stroke is unrecoverable-feeling and this project
   does not like surprises.** Cheap to change.
4. **A mask on a FILTER layer is REFUSED (test 9), not ignored.** The alternative is a filter's mask
   meaning "restrict this effect to this shape", which is arguably what the owner wants from the
   gradient-map sentence and is **not** the same as clipping to a layer that carries the shape.
   **Are these two things the owner would want separately?** If yes, this row should grow a
   per-filter shape constraint, and that is a bigger row.
5. **The mask is a cel's R channel, with G=B=0 and A=255 (Decision 1).** This reuses every existing
   tile path — the archive, the GPU upload, the exporters — at the cost of 3 wasted bytes per pixel
   and one documented convention. The alternative is a one-channel tile format, which touches the
   archive's entry naming, the GPU's texture internal format and every exporter. **I am confident
   the reuse is right and I want it on the record that a mask tile is 262 144 bytes for one byte of
   information** — a drawing with fifty masked layers pays 12 MB of file for masks that are 64 KB
   each in any sane format. If the owner cares about file size on the artist's phone, say so and it
   becomes a real design question rather than a convention.

### Low-risk, ruled provisionally

6. **No mask means full coverage, not none** (Decision 3).
7. **A run of clipped layers all target the same base** (Decision 4), resolved once by a function.
8. **Neither feature costs an extra full-viewport pass on the GPU** (Decision 12) — a mask is a
   multiply in the existing pass and a clip is a uniform.
9. **A missing mask cel is refused at open in words** (Decision 10), never silently unmasked.

## Lead build 2026-09-30 — masks and clipping, built by the Lead (see LEAD_RULINGS R48)

Owner: *"build the layer column … compact and can take into account masks."* Built together with the column (JB-2.04).

**Built, and how it is proved.**
- `core/render/LayerMask`: coverage, clip base and clip factor. `RegionRenderer` applies them (pixel × mask × clip, then
  opacity, then blend). `LayerMaskTest` has 10 checks with hand-worked pixels: R-not-alpha, an unpainted mask, a run of clipped
  layers, a clip over an empty base, a hidden base, the base's own mask, base opacity, and file round trip plus validation.
- Document: `Layer.mask: Cel?` and `Layer.clip`, `DOC_VERSION` 3, validation in words.
- Archive: mask tiles are stored and checked (`JbArchiveTest.roundTripKeepsAMaskAndAClip`).
- OpenRaster bakes the clip (`OraExportTest.aClippedLayerIsWrittenAlreadyCutToItsBase`).
- GPU:
  - `jb_composite.frag` multiplies by `u_mask.r`, and when clipped by `u_clipBase.a × u_clipMask.r`.
  - The engine keeps a mask as a second tile store (`id#mask`), whose unpainted tiles are white.
  - Strokes, undo, delete (the mask goes with its layer), duplicate and thumbnails all know it.
  - Any mask or clip sends the stack down the composite path.
  - `blend_gpu_check.js` mask run: 9,032 values, 0 mismatches. It fails at 5,212 when the shader drops a term.
- Screen:
  - The mask's thumbnail sits on its layer's cell. Tap it to paint on the mask; the cyan ring moves to it.
  - A clipped layer steps in with ↳.
  - The panel offers Add mask, Paint on the mask / on the layer, Delete mask (red dot), and Clip to layer below / Unclip
    (not offered for the bottom layer).
  - A move or delete that would leave the bottom layer clipped unclips it (`LayerStack.normalized`), so such a drawing never
    exists.

**Owner check (Note 9), owed: the phone locked before these were tried.**
1. Add a layer, paint on it, then Add mask and paint black on the mask: the paint disappears there. Paint white: it comes back.
2. Add a layer above, paint over its edge, then Clip to layer below: the new paint shows only where the layer below has paint.
3. Hide the layer below: the clipped paint hides too.
4. Undo each step.
5. Close the app and reopen: the mask and the clip are still there.
