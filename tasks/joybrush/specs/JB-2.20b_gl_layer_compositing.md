# JB-2.20b — GL layer compositing with the Studio's `GLSL_BLEND_FN`: **preview = export**

| | |
|---|---|
| **Tier** | **T1** (shader, a whole-stack render pass, and the file that makes the app's largest claim true) |
| **Status** | 📝 Draft spec — see ROADMAP.md |
| **Needs** | JB-2.20a (the CPU blend maths + the golden table — Built 🟧) |
| **Owner area** | NEW `joybrush/shaders/jb_blend.glsl` (GENERATED, committed); NEW `tools/blend-glsl/gen_blend_glsl.sh` + `tools/blend-glsl/GenBlendGlsl.java`; EDIT `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt` (the composite pass, `Layer.blend`, the layer list); NEW `.../gl/BlendProgram.kt`; NEW `.../test/.../gl/BlendGlslShapeTest.kt`; EDIT `joybrush/core/src/commonTest/.../render/RegionRendererTest.kt` (the parity-claim test ONLY, named in Decision 7) |
| **Estimated size** | ~200 lines of Kotlin; ~90 lines of generated GLSL; ~60 lines of tooling; ~200 lines of tests |

## 🎯 What this row is worth

It closes the single largest correctness gap in Joy Brush, and it is the row that makes the
project's central promise — **"what you see is what you export"** — true for layers.

**The gap, stated exactly.** `RegionRendererTest.theParityClaimNamesExactlyTheModesEachSideHas`
currently asserts, by partitioning the enum, that:

| | modes |
|---|---|
| CPU (`RegionRenderer` / `Blend` / `BlendRgb`) | **27 of 27** |
| GPU layer path (`GlPaintEngine`) | **1 of 27** — `NORMAL` only |

and `GlPaintEngine`'s own layer record holds `id`, `tiles`, `opacity`, `visible` — **no blend field
at all**. `jb_tile.frag:13` is `o_color = texture(u_layer, v_uv) * u_layerOpacity`, and
`glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)` is set **once, before the layer loop**.

Today nothing diverges, because **JB-2.20a Decision 6 forbids any UI from offering a non-NORMAL
mode**, and JB-2.04's Decision 2 (written 2026-09-29) greys the blend chip with the reason.
**The moment this row lands and the picker opens, a mode that composites one way on the phone and
another way in every export is a shipped bug.** This row is the one that makes the picker honest.

## Contract

```kotlin
package cc.joycreator.joybrush.androidkit.gl

import cc.joycreator.joybrush.core.doc.BlendMode
import cc.joycreator.joybrush.core.blend.BlendRgb

/**
 * Compiles the Studio's blend function into the engine and owns the mode → code mapping.
 *
 * R23: the equations are NOT transcribed into Joy Brush. They are GENERATED from
 * `BlendModes.GLSL_BLEND_FN` (the Java string the Studio's own shaders concatenate) by
 * `tools/blend-glsl/gen_blend_glsl.sh`, and the generated `joybrush/shaders/jb_blend.glsl` is
 * COMMITTED. A drift check regenerates and byte-compares, exactly like
 * `tools/gen_blend_golden.sh` does for the table.
 */
internal class BlendProgram(private val shaders: ShaderLibrary) {
    /** The `#version 300 es` source of jb_blend.frag, with the function body from the Studio. */
    fun source(): String
    /** Joy Brush's mode name → the Studio's shader code (0..25). The table is `BlendRgb`'s. */
    fun codeOf(mode: BlendMode): Int
}
```

## Decisions

1. **The GLSL is generated, checked in, and byte-compared — and this row is where the JB-2.20a
   review's referred gap gets closed.** `JB-2.20a__claude.md` Finding 1 is explicit: the existing
   `glsl_model.py` "never opens `BlendModes.java`; its only file read is the generated table", so
   editing the shader the GPU runs leaves every check green. That is a false claim about a *proof*,
   and the honest fix named in the review is to make the tooling read the real GLSL string.
   **Here it does, by construction**: `gen_blend_glsl.sh` compiles a tiny Java program that prints
   `BlendModes.GLSL_BLEND_FN` and writes it into a `#version 300 es` file with a header comment
   naming the generator; `--check` regenerates to a temp file and `cmp`s it against the committed
   file. **A one-character edit to the Studio's shader now turns a build red.** That is the
   difference between this row and the hand-transcription it must not be.
2. **The Studio's GLSL is GLSL ES 1.00; Joy Brush's shaders are ES 3.00.** The generator emits
   `#version 300 es`, `precision highp float;` and the function with the mode as a **parameter** —
   `BlendModes.glslBlendFnWithModeParam()` exists for exactly this and rewrites only the signature
   line, so the equations are still the Studio's characters (R23: share, don't copy). The ES-1.00 →
   ES-3.00 differences (`gl_FragColor` → `out vec4`, `texture2D` → `texture`) do not appear inside
   the function body, and **the generator does not edit the body.** If a future Studio edit uses a
   1.00-only construct, the compile fails loudly — which is the right failure.
3. **The engine composites the whole stack in ONE pass, not one pass per layer.** This is the
   decision that makes 27 modes affordable and it is the reason this row is T1.
   Today `draw()` loops layers and draws each tile with fixed-function blending. That is cheap
   because a fixed-function blend needs no read-back. **A shader-side blend needs the backdrop
   colour**, and in GLES 3.0 a fragment shader cannot read the framebuffer it is writing. So the
   stack is composited into an **offscreen ping-pong pair of RGBA8 FBO textures** sized to the
   viewport, **one full-viewport pass per layer** (each sampling the previous result and the layer's
   tiles), and then one blit to the default framebuffer. Decision 4's arithmetic is the cost.
4. **The cost, counted, because a T1 row must not hide it.** Today, for L layers and T tiles the
   engine issues **T draw calls** (one per tile, 256² = 65 536 fragments each) and relies on
   fixed-function. After: **L draw calls** (one per layer, W×H fragments) + 1 blit + L/2 buffer
   swaps. A fully-painted 3000 × 2000 canvas is 12 × 6.8 = **82 tiles** → 5.4 M fragments today;
   at 1080 × 2400 it is **L passes of 2.6 M fragments** — for a 6-layer drawing, 15.5 M. **So the
   fragment count is roughly 3× for a typical drawing, and it drops sharply as the canvas gets
   larger** (the tile loop is proportional to painted area; the layer loop is proportional to screen
   area). Two offscreen RGBA8 textures at 1080 × 2400 are **2 × 9.9 MB**. This is a real cost and
   **the Note 9 measurement is JB-0.10's and JB-0.12's job, not this row's** — but the numbers are
   here so nobody is surprised.
5. **`NORMAL` stays on the fixed-function fast path.** The engine keeps `glBlendFunc(GL_ONE,
   GL_ONE_MINUS_SRC_ALPHA)` and the existing per-tile path for a stack in which **every** visible
   layer is `NORMAL`; the moment one layer is not, the whole stack goes down the composite path.
   **The two paths must produce the same picture for an all-NORMAL stack, and a test says so**
   (Decision 6). This is not a "two implementations" hazard, because a blend function's
   `uBlendMode < 0.5 → return s` is the Studio's own NORMAL and the composite reduces to source-over
   by construction.
6. **The pins are the two ends of the promise, and both are checked.**
   * **Shape:** every mode the GPU composites is a code in `0..25` from `BlendRgb`'s name-keyed
     `studioCodeOf` — which `BlendParityTest` already pins against the Studio's `ALL` — plus the
     27th, `ERASE_BELOW`, which has **no Studio code** and is handled as its own case (destination
     -out, the same as `Blend.kt:54-61`). A test asserts the GPU's set and the CPU's set are the
     same set of names, so a mode cannot be implemented on one side only.
   * **Value:** a **device test** renders one layer per mode over a known backdrop and compares the
     framebuffer against `Blend.apply` on the same numbers. That comparison is the only honest
     proof of preview = export, and it belongs in the owner check, not in a JVM test that cannot
     run a shader.
7. **`RegionRendererTest.theParityClaimNamesExactlyTheModesEachSideHas` is edited, and that is
   deliberately in this row's owner area.** It exists to make the *claim* true rather than to be
   true — and when this row lands, the claim it pins ("1 on the GPU") becomes false. **The edit is
   to change the named sets from `{NORMAL}` to all 27, and to keep the partition assertion.** The
   test's whole point is that a 28th mode breaks it; leaving it saying "1 of 27" would be a lie
   that a future reader would act on. **Do not delete the test; do not weaken it to
   `assertTrue`.**
8. **Tiles are premultiplied; the blend function wants straight.** `GLSL_BLEND_FN` is documented as
   taking **straight** (un-premultiplied) rgb and its callers clamp and un-premultiply. So the
   composite pass un-premultiplies the source (÷ alpha, and 0 where alpha is 0 — the exact guard
   `RegionRenderer.render` uses at `:212-213`), blends, composites with the W3C alpha rule
   `a = sa + da(1 − sa)`, and re-premultiplies. **The Studio's own renderers already do exactly
   this**, so it is a port, not a design — but it is the part most likely to be got wrong, so the
   device check in Decision 6's value pin is over a **half-transparent** layer as well as an opaque
   one.
9. **`ERASE_BELOW` is a layer-level destination-out against the accumulated stack**, not a blend
   term (`Blend.kt`'s KDoc gives the reason: erase cannot be a blend term because the alpha rule
   differs). The Studio's shader has no band for it, so it is a separate branch in the composite
   pass and a **separate case in the code table**. The export maths and the preview maths must take
   the same branch — asserted by the same name-set test in Decision 6.
10. **A layer's blend mode is read from the document, not from a second source.** `GlPaintEngine`
    gains `setLayerBlend(id, BlendMode)` (JB-2.04 adds the field and the refusal; this row makes
    the field mean something) and the engine never invents a mode. A mode the CPU knows and the GPU
    does not is a **compile-time refusal** at `setLayerBlend` (a `require` naming the mode), not a
    runtime surprise on the phone.
11. **The GL context-loss policy is unchanged and must stay that way.** JB-0.07's ruling: after a
    loss, `init` empties everything and reports `lostContent` — it does not restore. The two new
    FBOs and the new program join `release()` and `forgetEverythingFromTheLastContext()`, and the
    existing test (`heldTextureNames() == 0` after a loss) is **extended to count them**. A
    rebound dead texture name is precisely how JB-0.07's BLOCKER happened.

## Tests

`BlendGlslShapeTest` (JVM — no GL needed for any of this):
1. **`theGeneratedShaderIsInSync`**: runs `gen_blend_glsl.sh --check` and asserts exit 0. This is
   the drift check as a test, so an edit to the Studio's `GLSL_BLEND_FN` fails the suite. **This is
   the JB-2.20a Finding-1 claim, made true.**
2. **`theGeneratedFileCarriesTheStudiosEquationsByteForByte`**: reads `BlendModes.GLSL_BLEND_FN`
   (via the same Java generator) and asserts the committed `jb_blend.glsl` **contains that string
   verbatim** — so a future generator "cleanup" that reformats the body is a red test.
3. **`theModeIsAParameterNotAUniform`**: the generated file declares
   `vec3 blendPix(vec3 b, vec3 s, float uBlendMode)` — because a per-layer uniform would need 27
   programs.
4. **`everyBlendModeHasExactlyOneGpuCode`**: an exhaustive `when` over all 27 (the shape
   `Blend.kt`'s `term` already uses), so a 28th mode is a compile error. `ERASE_BELOW` maps to a
   named constant that is NOT a Studio code, and the test asserts it is the only such one.
5. **`theGpuAndCpuAgreeOnWhichModesTheyImplement`**: the GPU's name set == the CPU's name set ==
   `BlendMode.entries.toSet()`, asserted as sets of names. (JB-2.20a §5b Finding 3: the proof is
   name-keyed, never ordinal-keyed — `ordinal == modeCode` is FALSE for 22 of 27 and the KDoc says
   so.)
6. **The two paths agree for an all-NORMAL stack**, at the maths level: `Blend.apply` with
   `mode = NORMAL` equals the source-over composite the fixed-function path performs, for a set of
   (src, backdrop) pairs including a half-transparent source. This is the JVM half of Decision 5;
   the framebuffer half is the device check.
7. **`theContextLossLeavesNothingHeld`**: the extended `heldTextureNames()` test from JB-0.07,
   extended to the two FBO textures and the blend program, with the assertion it already has (zero
   names, empty layers, undo refuses to step).
8. **`RegionRendererTest.theParityClaimNamesExactlyTheModesEachSideHas` is updated to 27-and-27**
   and **keeps its partition assertion** (Decision 7) — so a 28th mode still turns the suite red.

**Command:** `bash tools/blend-glsl/gen_blend_glsl.sh --check` exits 0, and
`./gradlew -p joybrush :core:jvmTest :androidkit:test` — 0 failures.

**Device check (the value pin — this is the one that matters, and it is a screenshot):**
Build a document with **one layer per mode** at 50 % over a mid-grey, on both an opaque and a
half-transparent backdrop; screenshot the phone; and compare each swatch against the same composite
computed by `RegionRenderer` on the JVM. **Every swatch must match.** Any mismatch is a BLOCKER and
goes back here, not into a KDoc.

## Do not

- **Do not hand-transcribe, reformat, or "tidy" the generated GLSL.** It is generated, committed and
  byte-compared. An edit to it is an edit to the Studio's shader, made in the wrong place.
- Do not edit `BlendModes.java`. R23: the Studio is the authority. (JB-2.20a Q4's two errors in the
  Studio's own comments are pinned, not corrected — that ruling still stands.)
- Do not edit `Blend.kt`, `BlendRgb.kt` or `BlendGolden.kt`. The CPU half is Built and reviewed; the
  only test in it this row touches is the parity-claim test, by name.
- Do not weaken `theParityClaimNamesExactlyTheModesEachSideHas` to make it pass. It is supposed to
  change; it is not supposed to stop checking.
- Do not add a mode. This row implements the 27 that exist.
- Do not restore anything after a context loss. JB-0.07's ruling.
- Never run gradle on the owner's PC.

## Definition of done

- [ ] `gen_blend_glsl.sh --check` exits 0 (paste)
- [ ] tests pass (paste)
- [ ] `git status --short` shows only owner-area files
- [ ] watcher green
- [ ] **the 27-swatch device comparison done and pasted** — not done without it
- [ ] committed `JB-2.20b: GL layer compositing`
- [ ] ROADMAP row → 🟧 Built, and **JB-2.04's blend chip un-greyed in the same session** (it is a
      one-line `LayerRules.gpuComposite()` change, and leaving it greyed would be the row shipping a
      capability nobody can reach)

## Questions

_(Spec writer, `openrouter/stealth/space-bunny-alpha`, 2026-09-29. This is the highest-value row in
the list and I have made four T1 decisions in it that need a human eye before a builder starts.)_

### 🔴 For the Lead — the composite pass is a real cost and I have costed it, not designed around it

1. **Decision 3 changes the shape of the renderer.** The fixed-function per-tile loop becomes an
   offscreen ping-pong with one full-viewport pass per layer, because a shader-side blend needs to
   read the backdrop and ES 3.0 cannot read the framebuffer it writes. **≈3× the fragments for a
   typical 6-layer drawing at 1080 × 2400, 2 × 9.9 MB of offscreen memory, and a large win for a big
   canvas** (the tile loop scales with painted area, the new one with screen area). The blueprint's
   whole performance story is "the Note 9 is the floor", and JB-0.12 owns the low-latency front
   buffer. **Three options:** (a) ship it and measure (my recommendation — the arithmetic is in
   Decision 4 and the note is the judge); (b) composite only the layers below the first non-NORMAL
   one into the offscreen pair and let the NORMAL ones above stay on the fixed-function path — **I
   do not recommend this, it is exactly the two-implementations hazard this project keeps filing**;
   (c) composite on a **half-resolution offscreen pair for the NORMAL part and full only where a
   blend needs it** — clever, and a second path. I have written (a).
2. **JB-0.12 (low-latency front buffer + prediction) has not been written.** It changes what the
   display path looks like, and this row changes the same path. **Should JB-2.20b wait for 0.12, or
   should 0.12 be written against this row's structure?** My reading: 0.12 is about *when* a frame
   is presented, not about *how the stack is composited*, so they compose. But I would rather be
   told than find out.
3. **The generated-GLSL tooling is new, and JB-2.20a's review says the existing "independent GLSL
   check" is decorative.** Decision 1 is my answer to that finding and it is a genuinely better
   mechanism (`--check` reads the real string). **But it adds a second generator** next to
   `tools/gen_blend_golden.sh`, with a similar shape and different output. Would you rather have
   **one** tool that emits both the golden table and the shader? That is a smaller, cleaner system
   and it is `tools/`, so it is not in anyone's owner area.
4. **Is `GLSL_BLEND_FN` really the right source, or should the FX path win?** `glslBlendFnWithModeParam`
   exists because `FxCompiler` needs the mode as an argument for fused passes. This row is a
   single-stack pass with one mode per pass, so a **uniform** would do and `GLSL_BLEND_FN` is the
   literal. I chose the param variant anyway (Decision 3) so JB-2.21's adjustment layers can fold
   into the same program. **Confirm**, because it is the difference between the file on disk being
   the Studio's literal and being a rewrite of it — and R23 is specific about not copying.
5. **Decision 9 — `ERASE_BELOW` on the GPU is a branch, not a band.** That is the only mode with no
   Studio code, and it means the GPU's shader is not literally the Studio's shader: it is the
   Studio's function plus one extra case. **Is that acceptable under R23, or does it need the
   Studio to grow a band for it?** A band in the Studio would change every Studio export, which is
   the thing `BlendModes`'s own class note warns about.

### Low-risk, ruled provisionally

6. **The parity-claim test is edited, not deleted** (Decision 7). It is the only file outside this
   row's own new code that this row touches, and it is named in the owner area on purpose.
7. **The context-loss test is extended, not replaced** (Decision 11) — JB-0.07's BLOCKER was
   exactly this, and a new FBO that survives a loss would be the same bug wearing a new hat.
8. **Half-transparent backdrops are in the device check** (Decision 8), because the un-premultiply
   is where a shader port goes wrong and an opaque layer would not show it.
