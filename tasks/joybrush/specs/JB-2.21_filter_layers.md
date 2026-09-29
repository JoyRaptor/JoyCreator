# JB-2.21 — FILTER layers: the Studio's adjustment layer, over everything below

| | |
|---|---|
| **Tier** | T1 engine + T2 UI (the engine half is the T1 part) |
| **Status** | 📝 Draft spec — see ROADMAP.md |
| **Needs** | D.05 (fx, gradients, `FxStack`, `GradientRamp` moved into `:studiokit`), JB-2.20b (GL layer compositing) |
| **Owner area** | EDIT `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/DocModel.kt` (`LayerKind.FILTER`, `DOC_VERSION = 3`, `DocOps.validate`); EDIT `.../commonTest/.../doc/EnumFreezeTest.kt`; NEW `.../render/FxCpu.kt`; NEW `.../commonTest/.../render/FxCpuTest.kt`; EDIT `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt` (a filter pass in the composite stack); NEW `.../gl/FxProgram.kt`; NEW `joybrush-android/.../layers/FxPanelHost.kt` (the `Host` seam for the Studio's `FxPanel`) |
| **Estimated size** | ~400 lines of core + ~250 lines of engine; ~200 lines of tests; ~150 lines of the host |

## Goal

Owner's words, which are why this row exists (D.05's Goal): *"masks, blend modes, colour effects,
**effect layers like gradient ramps for remapping colours**, a gradient editor … and if these are
built modular, as we improve them in one place the other places get the enhancements."*

So a FILTER layer is **the Studio's adjustment layer, in Joy Brush**: a layer that holds an
`FxStack` (blur, levels, colour grade, gradient MAP, posterize, duotone, and the rest of the
seventeen) and applies it to **everything below it in the stack**, with the Studio's own `FxPanel`
as its editor.

The blueprint's Phase 2 names it too: *"layers panel with blend modes"* and the effects that ride
along with them.

## 🔴 The thing that must be settled before any of this is built

**Preview = export is a two-sided promise, and this row is where the second side appears.**

JB-2.13a made the CPU (`RegionRenderer`) the one renderer every exporter reads. An `FxStack` in the
Studio compiles to **GLSL ES 1.00 + AGSL** (`FxCompiler`), and there is **no JVM reference
implementation of the effects the way there is a Java mirror of the blend modes.** So:

- The **preview** would be the Studio's compiled GLSL on the GPU.
- The **export** would have to be a Kotlin/CPU implementation — a **second** implementation, written
  by this project, of seventeen effects.

That is the exact structure that produced every "the export doesn't match the preview" bug in this
project's own research (R5, and `BlendModes`' class note: *"a second hand-written copy of OVERLAY's
per-channel branch is how the export and the preview end up disagreeing"*). **So this spec adopts
JB-2.20a's method wholesale rather than inventing a second one**, and the rule that follows from it
is the load-bearing decision:

> **An effect is offered only when it has a proven-equal CPU twin and a golden check.** Until a
> given effect has one, the `FxPanel` shows it **greyed with the reason in words** — exactly what
> JB-2.20a Decision 6 says about blend modes, and for exactly the same reason.

## Contract

```kotlin
// In core, DocModel.kt (R3: a new serialised enum constant ⇒ a version bump)
@Serializable enum class LayerKind { PAINT, INK, FILTER }        // APPEND-ONLY, DOC_VERSION = 3

/** A FILTER layer's payload. Stored in the document, one entry per FILTER layer, keyed by layer id. */
@Serializable data class FilterSpec(
    val fx: List<FxCard> = emptyList(),      // the Studio's effect list, in order
    val enabled: Boolean = true,
    val opacity: Float = 1f,
)

/** The CPU twin. One function per effect, and it is ONLY allowed to exist with a golden check. */
object FxCpu {
    /** The effects this build may OFFER, by the Studio's registry id. */
    fun available(): List<String>
    /** Applies [stack] to a premultiplied RGBA region in place. Refuses an unavailable effect. */
    fun apply(stack: List<FxCard>, px: FloatArray, w: Int, h: Int)
    fun refusalFor(effectId: String): String?
}
```

The `FxStack` / `FxCard` / `GradientRamp` types are **D.05's** and are Java, so `FxCpu` takes the
values it needs (numbers and an ordered list of ramp stops) rather than a Java object — core is pure
Kotlin and cannot call Java (R23, the same constraint JB-2.20a solved with a generated table).

## Decisions

1. **A FILTER layer has NO PIXELS and NO CELS.** It is a list of effects applied to everything below
   it. `DocOps.validate` is amended: a `FILTER` layer must have `cels == emptyList()`, an `INK`
   layer must have exactly one, a `PAINT` layer exactly one (`Decisions` on the current rules are
   in JB-0.02; this row changes the validator and nothing else about layers). **An empty cels list
   on a PAINT layer is still a validation failure**, so "no pixels" is a property of the KIND and
   never a state a PAINT layer can be in.
2. **`DOC_VERSION = 3` and the enum is APPEND-ONLY** (R3). `DocJson.decode` accepts 1, 2 and 3;
   `encode` writes 3. **`EnumFreezeTest` is updated deliberately** and asserts the three-name list
   and version 3 — the same shape JB-2.20a used for its own bump, and the freeze test exists to
   make a rename or a reorder a red test.
3. **The effect is applied to the COMPOSITED STACK BELOW, bottom-first, in order** — the Studio's
   `FxStack` semantics, and the reason a filter is a layer and not a property. The composite
   produced by the layers below is the input; the filter layer's opacity scales the result; the
   filter layer itself has a blend mode (NORMAL or anything else, once 2.20b's picker offers them).
4. **The GPU pass is a pass in JB-2.20b's composite stack, and it uses the Studio's compiled GLSL.**
   `FxCompiler` produces the source; the engine compiles and runs it as one full-viewport pass
   between layer passes. **Not reimplemented in Joy Brush's shader folder** (R23: share, don't
   copy — the one improvement lands once).
5. **The CPU twin is written effect by effect, and each one carries a golden check against the
   Studio's own arithmetic — the JB-2.20a method, applied to effects.** `FxCpu.available()` is the
   list of effects that HAVE a twin AND a golden; `FxCpu.apply` **refuses in words** for anything
   else. `FxPanel` greys the rest. **This is the decision that keeps the promise, and it means this
   row ships in waves, not all at once.** Wave 1 is whatever `FxCpu` proves first; the panel opens
   up one effect at a time as each lands.
6. **Which effects are cheapest to make honest first, and therefore the order:** `invert`,
   `brightness`, `levels`, `posterize`, `threshold` (pure per-pixel functions over a colour — a
   direct transcription of the Studio's equation and a golden table is almost free); then `duotone`
   and `gradient map` (per-pixel, but they need `GradientRamp`'s exact stop/bias/mirror semantics);
   then `solid colour`, `noise`, `offset`; and **last** `gaussian blur` / `directional blur` /
   `pixelate`, which are the two-dimensional ones and are the only place the CPU twin is real work.
   Stated as an order, not as a promise: the Lead may re-rank.
7. **`gradient MAP` is the owner's named example and it is per-pixel** — it remaps a colour to
   another by its position on a ramp. It is wave 1 or 2 by Decision 6, and it is the one effect
   JB-2.22b and JB-2.23 build on ("set a shape as a gradient map"), so if you only fund one effect
   from this row, fund that one.
8. **UI is the Studio's `FxPanel`, unmodified, behind a `Host` interface** — the same pattern as
   `TransformOverlayView`'s `Host` (D.02's file: *"everything app-specific is behind its Host
   interface"*, which is why the transform tool was shareable in the first place). `FxPanelHost`
   supplies: the stack, an add/remove/reorder callback, a preview bitmap, and a change callback.
   **Joy Brush's panel and the Studio's are then the same panel**, which is R23's whole point.
9. **Live preview is mandatory and comes free with the GPU pass** (Decision 4): moving a slider
   re-runs the pass on the existing composite, so dragging a `levels` curve is at frame rate without
   a re-render. If it is slow on the Note 9, that is JB-0.10's measurement, not a reason to remove
   the preview.
10. **A filter applies to "everything below" and NOT to what is above it, and NOT to the paper.**
    The paper is the floor of the stack (`RegionRenderer`'s own decision) and is composited before
    the first layer, so a filter never touches it — which is right, because a blur must not blur the
    page. Stated because a builder will otherwise put the filter pass first.
11. **Export runs `FxCpu` over `RegionRenderer`'s composite**, inside the same `render` call, so
    every exporter gets filters for free — and gets them **wrong in exactly the same way** in all of
    them, which is the only kind of wrong this project can detect. One door, one implementation.
12. **An unavailable effect in a document opened from disk is not silently dropped.** The filter
    layer loads, the unavailable effect is **kept in the file**, the layer is drawn as though that
    effect were off, and the panel shows the effect greyed with the reason. Exactly JB-2.04
    Decision 3's posture, for the same reason: quietly rewriting a painting is worse than a visible
    gap.
13. **No adjustment layer can be the bottom of the stack** (it would apply to nothing) — that is a
    `DocOps.validate` refusal in words, at open, not a runtime shrug.

## Tests

`FxCpuTest` (JVM, `:core:jvmTest`):
1. **`theEightOriginalModesStillAgreeWithTheRenderer`** — JB-2.20a test 3, still green and still
   in its own file. **Nothing in this row may change it** (JB-2.20a's own "Do not").
2. **Per-effect identity, in the shape `BlendRgbIdentityTest` uses:** for each effect in
   `FxCpu.available()`, an identity case derived from the **definition** of the effect and **not**
   from a golden row (invert of every colour is its complement; posterize of an already-quantised
   colour is itself; levels with a linear input ramp is itself; a duotone of a greyscale is the
   ramp) — plus a case that a mid-grey survives a levels curve whose midpoint is that grey to
   within 1/255. **Derived from the definition, never from the table**, so a wrong golden does not
   make the test agree with it.
3. **A golden per effect, exactly as JB-2.20a's `BlendGolden.kt`**: the Studio's own arithmetic is
   run over a fixed-seed set of inputs and the results committed, plus a `--check` drift script. **If
   the Studio has no Java reference for a given effect, that effect does not enter
   `FxCpu.available()` at all** — the test asserts the available list is exactly the effects with a
   golden, so "available" can never outrun "proven".
4. **`anUnavailableEffectIsRefusedInWords`**: `FxCpu.apply` with it throws a message naming the
   effect and saying it is not available on this build; `refusalFor` returns the same sentence.
5. **Alpha is carried correctly**: every effect is applied to **premultiplied** data and returns
   premultiplied data with `colour ≤ alpha` preserved — asserted for every effect in
   `available()` (a straight/un-premultiplied mix-up shows up as a dark halo on soft edges and
   nowhere else).
6. **Out-of-range inputs are clamped, not refused** (a `levels` black point above the white point,
   a `posterize` of 1 level, a `gradient map` position outside 0..1) — because these come off
   sliders and a slider that overshoots by one must not take the render down. Contrast with
   `refusalFor`, which is for a capability, not a value.
7. **Enum and version:** `EnumFreezeTest` updated to `["PAINT", "INK", "FILTER"]` and
   `DOC_VERSION == 3`; a v2 document decodes unchanged; a v3 document with a FILTER layer round-trips
   and **re-encodes at version 3**; a FILTER layer with a cel is a validation problem naming the
   layer (Decision 1); a FILTER layer at the bottom of the stack is a validation problem (Decision
   13).
8. **Filters reach every exporter** by construction: a test that renders a region with a filter
   through `RegionRenderer` and compares against `RegionRenderer` + `FxCpu` applied by hand — the
   same shape as JB-2.14c's test 5, and the reason the PNG, the PSD, the ORA and the sheet cannot
   disagree about a filter.

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures. Then the watcher green.

## Owner check (Note 9)

Add a FILTER layer, put a `gradient map` on it, drag the ramp → the picture below remaps live.
Put a `levels` curve on a second one above it → the two stack in order. Add a blur → the art blurs
and **the paper does not** (Decision 10). Export PNG → the exported file has the effects, and
matches the screen. Open the same drawing in the Studio's own toolchain (or Krita, which reads ORA)
→ the effects are there as a layer.

## Do not

- **Do not implement an effect's CPU maths without a golden check** (Decision 5). This is the whole
  spec. A second unproven implementation of a filter is how preview and export stop agreeing, and
  this project has the receipts.
- Do not edit `FxStack`, `FxCompiler`, `GradientRamp` or `FxRegistry` — they are D.05's, moved with
  their packages, and they are the authority (R23).
- Do not write a Joy Brush effect UI. `FxPanel` (D.05b) is the editor; `FxPanelHost` is the seam.
- Do not touch `Blend.kt` / `BlendRgb.kt` / the golden table / `theEightOriginalModesStillAgreeWith-
  TheRenderer`.
- Do not add a 16-bit or deep-colour path, per-effect masks, or per-effect blend modes.
- Never run gradle on the owner's PC.

## Definition of done

- [ ] tests pass (paste)
- [ ] `git status --short` shows only owner-area files
- [ ] watcher green
- [ ] owner check noted
- [ ] committed `JB-2.21: filter layers`
- [ ] ROADMAP row → 🟧 Built

## Questions

_(Spec writer, `openrouter/stealth/space-bunny-alpha`, 2026-09-29. This is the spec I am least able
to finish honestly, and the reason is the second half of the title — see Decision 5.)_

### 🔴 For the Lead — the CPU twin is a project, not a task, and I need you to size it

1. **Does `FxRegistry` have a Java reference per effect, the way `BlendModes.blend` does?** I have
   assumed **no** — `BlendModes` is documented as *the* case where the Java mirror exists and exists
   because the JVM harness needs one, and I have read the D.05 inventory (which lists `FxRegistry`,
   `FxCompiler`, `FxGlSource` — all GLSL-producing, none described as arithmetic). **If the Studio
   already has CPU references for some effects, this row is dramatically smaller and I should know
   before anyone sizes it.** If it does not, then Decision 5's "an effect ships only with a proven
   twin" means this row's first delivery is maybe four effects, not seventeen, and each subsequent
   batch is its own work.
2. **Decision 5 says the panel opens one effect at a time. Is that acceptable as a shape of ship?**
   The alternative — write all seventeen CPU twins first and open the panel all at once — is a
   long dark period in which a built capability is unreachable, which is the state JB-2.04's blend
   chip is in right now. I chose waves. **It does mean `FxPanel` shows a greyed list on day one,
   which looks broken to a person who does not know why.**
3. **A FILTER layer is a new `LayerKind`, so `DOC_VERSION = 3` and every reader is a version behind
   after this row** (Decision 2). R3's own text says that is the intended trade ("an old app shown a
   new file says 'can't open, newer version'"). **Confirm you want the cost** — a drawing saved
   after this row will not open in the build that is on the phone today, and the Note 9 owner will
   notice that the moment he draws with a filter and then reinstalls an older build.
4. **Decision 4 puts the Studio's compiled GLSL into Joy Brush's GL pass.** `FxCompiler` emits GLSL
   ES **1.00** and AGSL; Joy Brush's shaders are ES **3.00** and the composite pass is a 3.00
   program. The Studio must therefore emit a 3.00 variant, or the engine compiles the filter as its
   own 1.00 program (which ES 3.0 context can still run), or `FxCompiler` grows an ES 3.00 target.
   **I have specified the middle option — a separate program for the filter pass — because it needs
   nothing from the Studio. Is that acceptable, or is "improve it in one place" (owner's phrase, via
   D.05) an argument for making `FxCompiler` emit 3.00?**
5. **A filter over an ANIMATION board's moving frames** (JB-3.01) is a per-frame pass, and a blur
   over a 24 fps animation at 2.6 M fragments per frame is a different budget question from a still.
   **Out of scope here, or do you want a stated answer now** so the design does not foreclose it?

### Low-risk, ruled provisionally

6. **A FILTER layer has no cels, and a PAINT layer with no cels is still invalid** (Decision 1) —
   "no pixels" is a property of the kind, never a state.
7. **Filters never touch the paper** (Decision 10) — a blur must not blur the page.
8. **An unavailable effect is kept in the file and shown greyed, never dropped** (Decision 12).
