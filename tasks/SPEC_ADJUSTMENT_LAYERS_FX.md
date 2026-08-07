# SPEC — Adjustment layers + the stackable FX system

**Status: DESIGNED, NOT STARTED.** Designed with the user 2026-08-06; plan approved by the user
the same day. Nothing in this document is built. No file listed here has been touched.

**Origin.** The user's request, 2026-08-06, verbatim in §0.1. The design pass explored the render
pipeline, asked two rounds of questions, and had an architect verify the load-bearing claims
against the code. The approved plan lives at
`~/.claude/plans/i-m-thinking-of-a-dapper-jellyfish.md`; **this file supersedes it** and is the
one that gets kept current. The session was cut off at the moment implementation would have
started, so this spec is the whole of the work so far.

**Why this is a SPEC and not a commit:** it touches both render paths, adds a schema version, adds
a `TrackKind`, and rewrites a shipping feature (color grade). Every one of those is a documented
regression source in this repo. The milestones below are ordered so each lands verifiable and
alone.

---

## 0. READ THIS FIRST

### 0.1 What the user asked for (2026-08-06, verbatim excerpts)

> *"adding an 'adjustment layer' as an object type… that's basically a layer that covers the whole
> screen that affects anything underneath it. So for instance, if I do color grading to it, it
> color grades everything beneath it. If I put an effect like a blur on it, it blurs everything
> beneath it."*

> *"if I applied a Gaussian noise to it as well as a Gaussian blur and a color shift and I set the
> opacity of the blur and all the parameters of these three things that are stacked, those would
> ripple as other objects pass and animate underneath it. This is an important key element for
> compositing."*

> *"a new icon in the video overlay menu, an FX icon 🪄 like this magic wand or simply 'FX', that
> would be its own tab just like how masking and chroma key is… a simple add button. Add would
> bring up a selection, and that selection, once you've selected it, would bring up a collapsible
> box with all the parameters of that effect… a carrot that would collapse it into something
> narrow, and it would also have a little move icon so that your finger could click on it and drag
> its order in the stack… so I could generate a bubble texture and then put a blur on that and then
> do a gradient ramp on top of that and get something that looks like psychedelic bubble shapes."*

> *"perhaps having the ability to add a second shape and a third, all with the same parameters, but
> then have their interaction be additive, subtractive or boolean… if I have a video that has two
> people's faces and I just want two circles around those faces, I can do all of that in masking
> without having to duplicate the video."*

> *"an adjustment layer would be able to have masks and chroma keys and be keyframeable just like a
> video layer."*

> *"This feature will add a lot of benefit — taking it from something that's behind CapCut and
> KineMaster into something more like a poor man's After Effects for Android."*

### 0.2 Decisions the user already made — do not re-ask these

| Question | Answer (verbatim where given) |
|---|---|
| When do you want to SEE the effect? | **"Live on modern phones now."** → RenderEffect/AGSL preview, tiered by API level, GL compositor deferred. |
| How ambitious is v1? | **"Adjustment layer first."** → per-object FX on PiP/text is M7, not v1. |
| Multi-shape masks — where in the sequence? | **"Ship first, as a quick win."** → M0. |
| Which effect families? | All four selected — blur, colour/tone, generators, distort — with the standing instruction below. |
| Existing colour work | *"we have already **got** some tone and color grading features so make sure we are incorporating what we got before re-inventing the wheel. some of these features may just need to be moved around or repackaged."* → this is **M2**, and it is why M2 comes before the new object type. |

The user also said, on effort: *"I don't want to prompt something twice that could be built
correctly from the start… the only real risks are regressions and breaking things."* That sentence
is the design constraint behind every "one authority" rule below.

### 0.3 What already EXISTS and must be reused, not reinvented

The user's instruction above is not a preference, it is the thing this table enforces. Read these
before writing anything.

| Thing | Where | Why it matters here |
|---|---|---|
| **Chain order IS z-order** | `export/ExportManager.java` — `assembleClipVideoEffects` (:2479), invariant stated in the comment at :2669, PiP loop at :2711 | The adjustment-layer semantic is **already** how export works. At position *k* the accumulated frame contains the master video plus every PiP below *k*. An adjustment layer is an entry in that same loop that **transforms** instead of **composites over**. No compositor work. |
| **`BlendModeGlEffect`** | `export/BlendModeGlEffect.java` | The proven template for `AdjustmentLayerGlEffect` — FBO handling, time gating, matte resolution. Its `Program.blendPix` is the blend authority the FX fold needs. |
| **`ChromaKey`** | `model/ChromaKey.java` | The **single-authority pattern**: `GLSL_KEY_FN` string + a Java mirror + a packer, concatenated by whoever needs it. `MaskSdf`, `BlendModes` and the FX compiler all copy this shape. |
| **`CompositingSpec.masks`** | `model/CompositingSpec.java` | Already a `List<MaskShape>` with a `subtract` flag. **The multi-shape model exists**; only `masks.get(0)` is reachable from the UI. M0 is mostly authoring UI. |
| **`MaskPathBuilder.buildVisiblePath`** | `model/MaskPathBuilder.java` | Already computes `union(add) − union(subtract)`. Only `INTERSECT` is new. |
| **`MaskAnimator`** | `model/MaskAnimator.java` | The keyframe-resolve discipline, including the **identity return when nothing animates**. `FxStack.resolveAt` copies it exactly. `KeyframeCodec` round-trips arbitrary track names, so shapes 1..n need **no migration**. |
| **`RenderEffect.createChainEffect` + AGSL** | `FaditorEditorActivity.applyPreviewColorGrade` | Already in use, already applied to a `FrameLayout` containing a `TextureView`. The preview strategy is **proven in this app**, not speculative. |
| **`GlTransitionShaderLoader`** | `gltransitions/GlTransitionShaderLoader.java` | The in-repo template-token + `sanitize()` + `uniformsFor` precedent the FX compiler generalizes. |
| **`GLTransitionCatalog`** | `gltransitions/` | The static-catalog shape `FxRegistry` copies. |
| **`FaditorToolsAdapter`** | `tools/` | Long-press pickup, lift/elevation, drop-line, `commitDrop` index math, edge autoscroll — **transpose it to vertical** for the FX card list. There is no `ItemTouchHelper` anywhere in this repo; do not introduce one. |
| **`ObjectMenuSheet.Prop`** | `tools/ObjectMenuSheet.java` | `PipDrawerTabs` already renders `Prop`s from outside the package. FX parameter rows are `Prop`s. Also the source of the `buildGripRow`/`applyState` collapse animation. |
| **`GradePresetStore`** | `effects/` | Own SharedPreferences file, JSON blob, **outside the project schema**. `FxPresetStore` copies it verbatim — no schema bump for presets. |
| **`MaskKeyPanel` session-snapshot undo** | `tools/MaskKeyPanel.java:371` | The idiom for drawer-scoped undo, including *why* you must re-parse into a detached copy rather than hold the live reference. |
| **`run-key.sh` JVM harness** | `tools/jvm-harness/` | gson-only classpath, `@argfile`, positive control. `run-fx.sh` copies it. This is why the `fx/` package must be android-free. |
| **`tasks/schema_layer_stamp.py`** | | Reproduces the `TrackKind` coercion data-loss bug offline. Run it before adding `ADJUSTMENT`. |

### 0.4 Two facts that decided the whole architecture

**Export is nearly free.** See the first row of §0.3. This is the finding that made the feature
worth doing now.

**Preview is the whole problem.** Preview is an Android View z-stack, not a GL compositor:
`PlayerView` for the master video, separate Canvas overlays above it. There is nothing that holds
"everything beneath layer *k*" as a texture. The chosen answer is `View.setRenderEffect` on a
wrapper `FrameLayout` containing the stable lower subset — full AGSL chain on API 33+, partial on
31–32, an honest badge below 31 (**minSdk is 24**). Effect bodies are authored **once** and
compiled to both AGSL and GLSL ES, so a real GL preview compositor can be dropped in later
without rewriting a single effect. That is the answer to *"I don't want to prompt something twice."*

### 0.5 Two shipping bugs found while verifying — both fixed by M2

**Bug 1 — export double-applies exposure and temperature/tint.** `EffectStack.toEffects()`
([EffectStack.java:161](app/src/main/java/com/fadcam/ui/faditor/effects/EffectStack.java:161))
adds `Brightness(exposure)` *and* `RgbAdjustment(temp/tint)`, and then — whenever any of
highlights / shadows / fade / vignette / grain is non-zero — **also** adds
`ColorGradeShaderProgram`, whose shader independently does `color += uExposure;
color.r += uTemperature*0.08; color.b -= uTemperature*0.08; color.g += uTint*0.04`. Both land,
with **different math each time** (`Brightness` additive, `RgbAdjustment` multiplicative).
Confirmed by reading both files.

**Bug 2 — the preview vignette is a no-op and preview grain is at the wrong scale.**
`applyPreviewColorGrade` uses `distance(co, float2(0.5))` and `co * 100.0`, but in
`createRuntimeShaderEffect` `co` is in **local pixel space**, not 0..1. The vignette evaluates
`smoothstep(0.72, 0.28, ~700.0)` = 0 across the entire frame. **This is the single most important
lesson in the document** and it is why §2's compiler emits a mandatory normalization prologue that
no authored body can bypass.

(The 100× saturation bug documented at `EffectStack.java:170` is already **fixed**. What remains is
a subtler drift: export uses HSL saturation, preview uses a luma-lerp. Collapsing to one body
resolves it.)

---

## 1. M0 — Multi-shape masks

Independent of all FX work. **Ship alone, first** — the user asked for it as the quick win.

### 1.1 `model/CompositingSpec.java`

- `MaskShape.mode` ∈ `{MODE_ADD, MODE_SUBTRACT, MODE_INTERSECT}`. Keep the existing `subtract`
  boolean as a **wire-compatibility shim only**: `toJson` always writes `subtract`, and writes
  `"mode":2` *only* for intersect, so add/subtract shapes produce **byte-identical JSON to today**.
  Make `subtract` a derived accessor (`isSubtract()`) and delete the ~3 direct field reads —
  leaving both writable is how they drift.
- `MaskShape.slot` — a **stable** int assigned at creation, **never reused, never renumbered on
  delete**. Keyframe tracks are namespaced by slot, not list index. Renumbering keyframe tracks on
  delete is a classic data-loss bug and it is the same rule §3 applies to FX cards.
- `addShape()` / `removeShape(int)`, keyframe-aware.
- `copyFrom(CompositingSpec)` — undo must mutate **in place** because `Clip` holds the reference.
- Shape presets as a data-only helper (`applyPreset`): **Square / Rect / Circle / Pill** via
  `w`/`h`/`corner`. Ship these four. A true ellipse is *not* expressible via `addRoundRect`, and a
  second geometry path buys little now — the SDF work in §5 makes ellipse cheap later.

### 1.2 `model/MaskPathBuilder.java`

- **Gate the new algorithm.** If no shape uses `INTERSECT`, run **exactly today's two-bucket code,
  untouched**. Otherwise run an ordered fold
  (`acc.op(shapePath(m), UNION|DIFFERENCE|INTERSECT)`). The gate guarantees *by construction* that
  no existing project can take a different code path, preserving the byte-identical feather-0 clip
  path.
- Extract the fold decision into `foldOps(spec)` returning op ordinals + a `sequential` flag, so it
  is JVM-testable without `Path`.
- `signature(spec)`: append `mode` and `slot`.
- **Replace the one-entry feather cache with a 4-entry LRU.** Today "a frame belongs to one clip"
  holds. The moment an adjustment layer with a mask coexists with a masked PiP, export alternates
  between two specs per frame and rebuilds a software `BlurMaskFilter` bitmap **every frame**.
  This must land in M0, before anything can produce a second masked object.

### 1.3 `model/MaskAnimator.java`

- Keep `CX`…`FEATHER` and `KEYS` exactly as-is — **shape 0 keeps the flat names forever.**
  Shapes 1..n use `mask<slot>.cx` etc. `KeyframeCodec` round-trips arbitrary track names, so
  **no migration is needed**, and an old build still animates shape 0 correctly.
- `resolve()` loops all shapes instead of `masks.get(0)`. `maskFeather` stays spec-level (one
  feather for the stack — matches how `featherBitmap` works).
- Preserve the identity return when nothing animates or links, so existing projects still allocate
  nothing per frame.

### 1.4 UI — `tools/PipDrawerTabs.maskTab`

Shape chip row (`● 1  ● 2  ● 3   +`) → mode segmented control (Add / Subtract / Intersect) →
preset row → the existing 6 per-shape sliders → spec-level Soften / "Only inside" / link / key row.

Reuse the private `slider(...)` helper unchanged. Rebuild the slider column in place on chip
switch; `PipOverlayDrawer.switchTo` already tweens `contentHost` height then restores
`WRAP_CONTENT`, so a height change needs no extra plumbing. "◆ Key at playhead" writes the 7 tracks
for the **selected shape's slot**.

### 1.5 Undo — closes an existing gap

`PipDrawerTabs.Host.recordUndo` is **declared and never called**. Mask and chroma sliders have no
undo at all today. Fix with the `MaskKeyPanel` session-snapshot idiom: capture
`spec.toJson().toString()` on drawer open; on close record one `EditActions.LambdaAction` if it
differs. Re-parse into a **detached** copy before capturing in the lambda
([MaskKeyPanel.java:371](app/src/main/java/com/fadcam/ui/faditor/tools/MaskKeyPanel.java:371)
explains why holding the live reference is wrong). This retroactively gives chroma-key sliders
undo too.

### 1.6 Schema

Bump `FaditorProject.SCHEMA_VERSION` to **13**, stamped **conditionally** — only projects that
actually use `INTERSECT` get v13. Add/subtract-only projects keep their existing stamp and
byte-identical JSON. Update the already-stale `docs/project-schema.md` §compositing (missing
`feather`, `maskKeys`, `link*`) in the same pass.

---

## 2. M1 — FX foundation (pure model; no renderer, no UI)

New package `com.fadcam.ui.faditor.fx`, **android-free** (annotations + gson only) so it typechecks
and runs off-device — the same rule `run-key.sh` states for `ChromaKey`.

### 2.1 The dialect

**Author once in GLSL ES 1.00. Export emits the bytes verbatim; the preview goes through a
translation table.** The asymmetry is deliberate: export is ground truth, so a translation mistake
produces a wrong *preview*, never a wrong file.

Three constraints shape the format:
- AGSL has **no preprocessor** → all macro expansion happens in Java for both backends, keeping the
  two emits structurally identical.
- AGSL requires **compile-time-bounded loops** → kernels emit literal trip counts. This is exactly
  why we can ship our own blur (§5.4).
- AGSL has no `vecN` type names → a word-boundary rewrite table handles it.

An effect body is one of three shapes, declared by its capability:

```glsl
vec4 fx_main(vec2 uv, vec4 src)   // POINTWISE / GENERATOR
vec4 fx_main(vec2 uv, vec4 src)   // SAMPLER — uses FX_SAMPLE, forces its own pass
vec2 fx_uv(vec2 uv)               // UV_REMAP — folds into the next sampler's coordinate
```

Macros, expanded in Java for both backends:

| Macro | GLSL | AGSL |
|---|---|---|
| `FX_SAMPLE(uv)` | `texture2D(uTexSampler, fxClamp(uv))` | `inputShader.eval(uOrigin + fxClamp(uv) * uSize)` |
| `FX_UV` | `vFxUv` | `fxUv` (from the prologue) |
| `FX_TEXEL` / `FX_ASPECT` / `FX_TIME` | identical both sides | identical both sides |
| `FX_P(name)` | `u<slot>_name` | `u<slot>_name` |

`fxClamp(uv)` is a shared prelude clamping to `[0,1]` — this is what stops a blur sampling
transparent black outside the content rect, the classic RenderEffect edge artifact.

**The compiler emits a normalization prologue; no authored body ever touches raw fragment coords.**
This is the direct fix for §0.5 Bug 2. AGSL entry:

```glsl
uniform shader inputShader;
uniform float2 uOrigin;   // content rect origin, view pixels
uniform float2 uSize;     // content rect size, view pixels
half4 main(float2 co) { float2 fxUv = (co - uOrigin) / uSize; ... }
```

Uniform declarations are **emitted from parameter descriptors, never authored** — generalizing
`GlTransitionShaderLoader.uniformsFor`. Namespacing by stack slot (`u3_radius`) makes fusion
collision-free.

### 2.2 Pass planning (fusion)

`FxCompiler.plan(FxStack)` walks bottom→top:

- Consecutive `POINTWISE`/`GENERATOR` effects **fuse into one pass** — bodies renamed `fx0_main`,
  `fx1_main`, concatenated, with the blend/opacity fold emitted between them.
- `UV_REMAP` opens no pass; it accumulates into a `fxRemap(vec2)` composition consumed by the next
  `FX_SAMPLE` in the same pass. A trailing remap run emits one 1-tap resample pass.
- `SAMPLER` closes the current pass and opens its own. A separable blur declares 2 passes; the
  compiler emits H and V variants with a `uDir` uniform.

The per-card fold `c = fxBlendOver(cPrev, fxN_main(uv,c), uN_opacity, uN_blend)` requires a new
shared authority: **extract `BlendModeGlEffect.Program.blendPix` verbatim into
`model/BlendModes.java`** as `GLSL_BLEND_FN` + the existing `modeCode(String)` + a Java mirror for
the harness. `BlendModeGlEffect` then concatenates the constant, exactly as it already does for
`ChromaKey.GLSL_KEY_FN`. ~30 lines of pure extraction; the largest reuse win in this spec.

### 2.3 Classes

| File | Role |
|---|---|
| `fx/FxParam.java` | Typed descriptor: name, label, kind ∈ {FLOAT, COLOR, BOOL, ENUM, POINT}, min/max/default, keyable. BOOL/ENUM pack to `float` — ES2 int uniforms are patchy, already documented at [BlendModeGlEffect.java:119](app/src/main/java/com/fadcam/ui/faditor/export/BlendModeGlEffect.java:119). |
| `fx/FxEffectDef.java` | id, displayName, family ∈ {BLUR, COLOR, GENERATE, DISTORT}, params, capability, passes, costWeight, glslBody. |
| `fx/FxRegistry.java` | Static catalog, modeled on `GLTransitionCatalog`. Bodies are **Java constants, not assets**, so typecheck sees them and preview does no asset I/O. |
| `fx/FxInstance.java` | effectId, values, enabled, opacity, blendMode (same wire values as `Clip.getOverlayBlendMode()`), collapsed, stable `slot`. Self-serializing. |
| `fx/FxStack.java` | Ordered list + **one** `KeyframeSet` for the whole stack. `resolveAt(t)` returns **this by identity** when nothing animates. |
| `fx/FxCompiler.java` | `plan()`, `emitGlsl()`, `emitAgsl()`. Pure string work, JVM-testable. |
| `fx/FxUniforms.java` | One packer, both renderers — the `ChromaKey.packParams` role. |
| `fx/FxCost.java` | Pass estimation and the "this stack is heavy" predicate. |

### 2.4 Keyframing

Track name = `"fx" + slot + "." + paramName` (e.g. `fx2.radius`). **Slot is stable, so reordering a
card never renumbers its keyframes.** One `KeyframeSet` on the stack, not per-instance — one
`isAnimated()` check per frame, and `KeyframeCodec` handles it free. Time base is **absolute
timeline ms**, matching `overlayTransform` and `maskKeys`, so the drawer's `playheadMs()` needs no
conversion.

### 2.5 Registry validation

A static self-check rejects any body containing raw `texture2D`, `uniform`, `varying`, `precision`,
`#`, `gl_FragColor`, or `main(` — those are the compiler's job, and that class of mistake is
exactly what `GlTransitionShaderLoader.sanitize()` exists to catch. Also reject undeclared `FX_P`
references and unreferenced declared params (dead uniforms are silently-broken UI).

---

## 3. M2 — Repackage the existing colour grade

**This is the milestone the user asked for directly** (§0.2, last row) and it is where the compiler
earns trust: it reproduces a shipping feature and fixes both bugs from §0.5 doing it.

**One grade, one body.** Add registry effect `"color_grade"` with the 10 scalars, canonicalized on
**the matrix semantics the preview already showed**: multiplicative exposure; media3's `Contrast`
curve `(1+c)/(1.0001-c)`; luma-lerp saturation; multiplicative temp/tint; highlights / shadows /
fade / vignette / grain as the existing shader lines — with the vignette now actually working
because `FX_UV` is normalized.

**Wire both renderers.**
- *Export:* `EffectStack.toEffects()` returns one `FxChainGlEffect` plus the `ColorLut`. Delete the
  `Brightness` / `Contrast` / `HslAdjustment` / `RgbAdjustment` construction; delete
  `ColorGradeShaderProgram.java` (body moves to the registry). **One pass replaces up to five.**
- *Preview:* `applyPreviewColorGrade` calls the shared AGSL builder. Delete the hand-written
  ColorMatrix and AGSL string. Keep an API-31/32 fallback as `FxColorMatrixApprox.forGrade(stack)`,
  **explicitly labelled an approximation** of the canonical body, so it stops being a third
  independent implementation.

**Migration: don't migrate.** Leave `clip.effectStack` exactly as persisted.
`FxStack.fromLegacyGrade(EffectStack)` is a **read-only adapter**; in the FX tab it surfaces as a
**pinned, non-removable "Color grade" card at the bottom of the stack**, writing straight back
through `EffectStack`'s setters. Zero schema bump, zero migration risk, zero chance of two
representations disagreeing. A *second* grade card added by the user is a real `FxInstance` in the
new serialization; both coexist, legacy stays at the bottom where it always applied.

---

## 4. M3 — The adjustment-layer object (model + z + timeline; renders nothing)

`model/AdjustmentLayer.java` — self-serializing: `id`, `layerId` (never null, same rule as
overlayClips), `startMs` / `durationMs` in editor time, `name`, `CompositingSpec compositing`
(masks + key + matte, reused wholesale — this is the user's *"masks and chroma keys… just like a
video layer"*), `FxStack fx`, `KeyframeSet transform` (opacity in v1), `hidden`, `locked`.

`Timeline` gains `adjustmentLayers` mirroring `overlayClips`. `TimedItem` gains the payload,
`ofAdjustment()`, `getAdjustment()`, `payloadKind() → "adjustment"`.

### 4.1 `TrackKind.ADJUSTMENT` — the data-loss trap

`isLane()` → true. **`minSchemaVersion()` → 13, as a literal.**

An unknown kind falls back to `VIDEO` on an old build, and that build's next autosave
**re-serializes the coercion** — permanently changing paint order, with no error and no way back.
This already happened once for `LAYER`; the javadoc at
[TrackKind.java:63](app/src/main/java/com/fadcam/ui/faditor/layers/TrackKind.java:63) documents it
and `tasks/schema_layer_stamp.py` reproduces it. **Reproduce the failure offline before writing the
fix.**

### 4.2 Lane emission

Add a fourth map to `LaneBuckets` in `Timeline.getLayers()`. Emit the adjustment phase **after** the
video/PiP phase, so a new adjustment lane defaults **above** PiPs — the AE-intuitive "grades
everything I've built so far." Every existing project has an empty map, so the emitted band is
provably identical.

### 4.3 Z-ordering — `compositor/LayerPreviewController.java`

Adjustment layers join the same authority PiPs use. `partitionAroundVideo` must exclude them the
way it already excludes overlay clips. Add **`orderedCompositedItems(Timeline)`** returning PiP
clips *and* adjustment layers in `orderedVisualItems` order — **one call consumed by both the
export loop and the preview wrapper builder**, for the reason already stated on
`partitionAroundVideo`: giving each side its own helper is how they drift. Plus
`visibleAdjustmentLayers` mirroring `visibleOverlayVideoClips`.

### 4.4 Creation UI and persistence

New carousel tool in `FaditorToolRegistry` — a Material Symbols **ligature string** (the carousel
convention; do **not** mix it with the drawer's vector-drawable convention). Creates a layer
spanning the selection or the whole timeline, in a fresh lane above the topmost PiP lane.
`LayerRowRenderer` gets a chip renderer reusing the sprite/text path. `EditActions` gains
Add/Remove actions modeled on `ReorderClipAction`.

Serialize under `timeline` via `AdjustmentLayer.toJson()` (the self-serializing idiom, preferred
over the serializer-side `serializeEffectStack` idiom). **Stamp v13 when the array is non-empty —
non-negotiable.**

> At the end of M3 you have a layer you can create, name, move, hide, trim, save, reload and undo
> — **that renders nothing.** A deliberately shippable, verifiable increment.

---

## 5. M4 — Export rendering (ground truth first)

### 5.1 Insertion — one merged loop

**Replace the PiP loop at
[ExportManager.java:2711](app/src/main/java/com/fadcam/ui/faditor/export/ExportManager.java:2711)**
with a single merged iteration over `orderedCompositedItems`, branching on payload:
PiP → `BlendModeGlEffect` (existing logic, including matte resolution and the `servingMatteIds`
skip); adjustment → `AdjustmentLayerGlEffect`.

Merging into **one** loop is what preserves chain-order-is-z-order. A separate loop would z-invert
any project that interleaved them — precisely the bug the PiP z-unification fix was written to
repair.

Extract the time correction to a single local:

```java
final long editorOffset = editorTimeOffsetFor(timeline, clip, timelineCursorMs)
        - (isLoopBeforeItem ? headTransitionMsFor(timeline, clip) : 0L);
```

It is currently hand-repeated at four call sites — exactly the shape of the bug the
`isLoopBeforeItem` javadoc at `ExportManager.java:2471` documents.

### 5.2 No per-clip slicing needed

`PipFrameOverlay` already solves this: it converts composition→editor time internally and exposes
`activeAt(ptsUs)`, which `BlendModeGlEffect.drawFrame` gates on. An adjustment layer emitted into
every clip's chain with the same offset **self-gates by time**. Add a *conservative*
`overlapsClipEditorSpan` skip as pure optimization — when in doubt, emit; a wasted pass is cheaper
than a missing effect.

### 5.3 `export/AdjustmentLayerGlEffect.java`

Structurally a clone of `BlendModeGlEffect`. `drawFrame`: gate on time → `fx.resolveAt(editorMs)` →
reuse cached compiled programs unless `sourceKey` changed → run passes 1..N-1 through ping-pong
FBOs → **final pass composites back over the original input**:

```glsl
out = mix(base, blendPix(base, graded), coverage * uLayerOpacity)
```

**That line *is* the adjustment-layer semantic.** Masks and chroma key restrict **where the effect
applies**, not what is drawn — so they modulate the mix factor, not an alpha.

Use media3's `GlUtil` for FBOs (already on the classpath, already used by
`ColorGradeShaderProgram`), **not** the vendored Grafika helpers; mixing two GL utility layers in
one program invites state bugs.

**On driver compile failure, degrade to passthrough with an `FLog.w` — never throw.**
`BlendModeGlEffect` throws; for an adjustment layer a lost grade is far better than a lost export.

### 5.4 `model/MaskSdf.java` — the new shared mask authority

Shaped exactly like `ChromaKey`: `GLSL_MASK_FN` + a Java mirror + a packer.

`sdRoundBox(p,b,r) = length(max(abs(p)-b+r,0.0)) - r` is **geometrically exact** against
`Path.addRoundRect` with equal x/y radii — which is exactly what `MaskPathBuilder.shapePath` emits.
Hard edges match to the pixel. Booleans fold as `min` / `max` / `max(a,-b)` in the same order
`MaskPathBuilder` folds `Path.Op`; the **sign is exact**, so hard edges are exact, while feather
falloff differs slightly near concave joins — **document that, don't hide it.**

Rotate the sample point in **pixel space** to avoid shear (the same lesson `MaskAnimator.applyLink`
documents). Adjustment-layer feather is a smoothstep approximation of `BlurMaskFilter`'s Gaussian —
accept it, because **both renderers use the SDF and therefore agree with each other**, which is the
property that matters. Leave the PiP Canvas path alone.

### 5.5 Blur: build our own; do NOT use media3 `GaussianBlur`

`RenderEffect.createBlurEffect` (Skia) and media3's `GaussianBlur` produce **visibly different
results at the same nominal radius** — using them in preview and export respectively guarantees
exactly the drift this architecture exists to prevent. AGSL's bounded-loop requirement makes source
generation mandatory anyway, so writing the kernel once in the dialect costs nothing and gives
parity by construction.

`taps = clamp(2*ceil(2σ)+1, 3, 33)`; above σ≈8, downsample to half res — decided by `FxCompiler`,
**not independently by each renderer**. Quantize σ into buckets for the source key so an animated
radius doesn't recompile every frame.

---

## 6. M5 — Preview rendering

### 6.1 The wrapper — an XML change, not a runtime reparent

Add `@+id/fx_below_group` (`FrameLayout`, `match_parent`) in `activity_faditor_editor.xml` and move
the stable subset into it **in the layout file**: `CanvasFrameView`, `PlayerView`,
`TransitionPreviewOverlayView`, `image_preview`, `slide_preview`, `sprite_overlay_layer_below`,
`overlay_layer_below`, `overlay_video_layer`. Everything from `waveform_overlay` up stays a sibling
after it. `findViewById` searches the whole tree, so nothing else changes.

De-risking precedent: `applyPreviewColorGrade` **already** calls `setRenderEffect` on `playerView`,
a `FrameLayout` containing a `TextureView`. RenderEffect on a ViewGroup containing a TextureView is
**proven in this app**.

This static wrapper covers the common case — the layer above the PiP plane and below the
text/sprite surfaces, which is M3's default emission position. For the general case (a layer
*between* two PiPs), v1 does **not** attempt per-plane wrapping; it applies the effect at the
wrapper boundary and badges the layer chip *"preview approximates z; export is exact."* Honest,
free, and consistent with the tiering precedent already set at
[BlendModeGlEffect.java:54](app/src/main/java/com/fadcam/ui/faditor/export/BlendModeGlEffect.java:54).

### 6.2 `compositor/AdjustmentPreviewController.java`

`sync(timeline, playheadMs)` called from the existing preview tick, main thread, **no new thread**:

1. Pick the topmost visible, time-active adjustment layer (v1 previews one at a time).
2. `resolveAt(playheadMs)`; **if nothing changed since last sync, return** — `setRenderEffect`
   invalidates the whole subtree, so with an unanimated stack this is **zero calls per frame**
   during playback.
3. `FxCompiler.plan()` → one `RenderEffect` per pass → fold with `createChainEffect` →
   `wrapper.setRenderEffect(chain)`.
4. Pass `uOrigin` / `uSize` in wrapper-local pixels from `CanvasFrameView`'s content rect, so
   generators and vignettes stop at the canvas edge instead of spilling into the letterbox.
   Recompute on layout and canvas-size change.
5. The final pass concatenates `MaskSdf.GLSL_MASK_FN` and `ChromaKey.GLSL_KEY_FN` — **the same
   strings export compiles.** That is what makes them agree.

### 6.3 Tiering — decided in one place, `fx/FxPreviewTier.java`

So the UI can never claim a capability the renderer lacks.

| Tier | Condition | Behaviour |
|---|---|---|
| A | SDK ≥ 33 | Full AGSL chain |
| B | SDK 31–32 | ColorMatrix approximation for matrix-expressible effects + `createBlurEffect` for blur, both marked *approximate*; others skipped |
| C | SDK < 31 | "Applies on export" badge on the layer chip and FX tab header |

### 6.4 The SurfaceView problem

`GlTransitionPreviewView` is a `GLSurfaceView`; a parent RenderEffect **will not apply to it**. For
v1, leave it outside the wrapper and accept that FX vanish for the ~600 ms transition segment — but
make the pop **deliberate**: cross-fade the wrapper's RenderEffect out over ~120 ms rather than
dropping it in one frame, and document it on the layer chip. Converting it to a TextureView-backed
GL view is the correct long-term fix (`ChromaKeyTextureView` is the template) but it risks a
shipping feature for a seam artifact.

### 6.5 Performance

Preview pass budget **4**, gated by `FxCost`; beyond that, preview the first 4 and badge the rest
export-only. Each RenderEffect chain link is one offscreen at full view resolution.

---

## 7. M6 — The FX tab

This is the user's 🪄 request (§0.1, third quote).

### 7.1 Adding the tab

`PipOverlayDrawer.buildIconRow()` iterates `for (int i = 1; ...)` and `refreshIcons()` hard-codes
`activeTab == i + 1`; both are correct only because tab 0 is icon-less and icons are dense from
index 1. **Appending FX as index 4 is safe with no drawer change.** Harden it anyway (record
`tabIconIndex` during `buildIconRow`, compare against it in `refreshIcons`) — three lines that
remove a landmine.

New `res/drawable/ic_pip_fx_24.xml` — 24dp, `?attr/colorControlNormal` tint, header comment
justifying the wand-with-sparkles glyph, matching `ic_pip_mask_24.xml` exactly. **Vector drawable,
not a ligature.**

A parallel `showAdjustmentDrawer(AdjustmentLayer)` reuses the **same** `PipOverlayDrawer` instance
with tabs `[Layer, FX, Mask, Chroma, Blend]`.

### 7.2 `tools/FxPanel.java` — the card list

**Not a `RecyclerView`** — there is no `ItemTouchHelper` anywhere in the repo. A plain
`LinearLayout` of cards inside the drawer's existing `ScrollView` works with the height tween
already in place. Build vertical drag on the `FaditorToolsAdapter` structure **transposed**:
long-press pickup with lift/elevation, drop-line indicator, `commitDrop` index math (simpler than
the original, which also handles pinned/divider cases), edge autoscroll.

```
┌──────────────────────────────────────────┐
│ ⠿  ▸  Gaussian Blur          ◉ 100%  ⋯   │
├──────────────────────────────────────────┤
│ Radius     [────●──────]  12.0     ◆     │
│ Blend      [ Normal ▾ ]   Opacity [──●─] │
└──────────────────────────────────────────┘
```

Caret collapse persists to `FxInstance.collapsed`, animated with the
`ObjectMenuSheet.buildGripRow`/`applyState` pattern. **Long-press anywhere on the header picks up**
— a 24dp grip alone is not finger-sized. `⋯` → Duplicate / Delete / Reset / Move to top-bottom.

### 7.3 Parameter rows

Build each `FxParam` into an `ObjectMenuSheet.Prop` — `PipDrawerTabs` already renders `Prop`s from
outside the package. Key = `"fx"+slot+"."+name`, **the same string the keyframe track uses**, so
`KeyframeDiamondControl` needs no translation. `withSpan(...)` = the layer's own span, so a key
can't be dropped outside it. COLOR reuses the existing eyedropper via `Host.pickColorFromPreview`;
ENUM uses the font-chip carousel pattern; BOOL a checkbox.

Row refresh: set **one** tag on the panel root with a Runnable that fans out to every card.
`PipDrawerTabs.refreshRows` walks exactly one ScrollView level and the FX panel is deeper — one tag
with one contract beats generalizing the walker.

### 7.4 Picker, presets, undo

- **Effect picker** — family chip row + grid, each entry carrying a **tier badge** from
  `FxPreviewTier.canPreview(def)` so the user knows *before* adding whether they'll see it live.
- **Presets** — `fx/FxPresetStore.java` copying `GradePresetStore` verbatim: own SharedPreferences
  file, JSON blob, **outside the project schema**. No bump, no migration.
- **Undo** — session snapshot on drawer open/close (one `FxStackAction`, modeled on
  `EffectStackAction`), plus immediate entries for structural add/delete/reorder, coalescing a
  drag's intermediate states with `mergeNextIntoTop()`.

---

## 8. v1 registry and cost budget

At 1080p on Adreno 6xx / Mali-G5x: a full-screen pass with 20–40 ALU ops ≈ 0.4–0.9 ms; a 17-tap
separable Gaussian (2 passes) ≈ 2.5–4 ms; a 3-octave FBM ≈ 1.5–2.5 ms. Export must stay above
realtime, so budget **≤8 ms/frame added**: soft cap 6 cards (warn), hard cap 12, ≤2 sampler-class
effects before a cost warning.

Fusion means 6 pointwise cards cost **one** pass, so the caps only bite on sampler/generator-heavy
stacks. Surface it as a cost meter in the tab header (*"2 passes · moderate"*), **never as a
refusal** — refusing an edit is worse than a slow preview.

### Ship these 12

| Family | Effect | Capability | Why v1 |
|---|---|---|---|
| Blur | Gaussian Blur | SAMPLER (2 pass) | The one everyone expects; proves multi-pass |
| Blur | Directional Blur | SAMPLER | Same kernel, one axis — nearly free once Gaussian exists |
| Blur | Pixelate / Mosaic | UV_REMAP | Zero extra pass; proves the remap fold |
| Colour | Invert | POINTWISE | 1 line; the perfect first pixel-exact A/B test |
| Colour | Levels | POINTWISE | The workhorse |
| Colour | Gradient Map | POINTWISE | The effect the user named first (2-stop in v1) |
| Colour | Threshold | POINTWISE | Pairs with generators |
| Colour | Posterize | POINTWISE | 2 lines |
| Colour | Duotone | POINTWISE | High perceived value |
| Colour | RGB / Chroma Shift | POINTWISE | The glitch look people actually want |
| Generate | Noise / FBM | GENERATOR | **One** body with a mode enum covering value noise, clouds *and* difference clouds |
| Distort | Offset (wrap) | UV_REMAP | Free, and it makes tiling generators usable |

Deferred to the first post-v1 drop — each is **only a new `FxEffectDef`** once M1–M6 land, which is
the whole point: radial/zoom blur, Voronoi/bubbles, checkerboard, dots/halftone, grid,
brick/masonry, gradient ramps, twirl, ripple, polar, bulge/pinch, set-colour.

The user's *"psychedelic bubble shapes"* demo (bubble texture → blur → gradient ramp) needs Voronoi
from that deferred list; **Noise/FBM → blur → gradient map** is the v1 stand-in for the same demo.

---

## 9. Risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | **M2 changes existing exports** (certain — every graded project) | It makes export match what preview always showed; strictly a fix. Prove via A/B on exposure-only, exposure+vignette, saturation. Consider a one-time in-app note. |
| R2 | **`TrackKind.ADJUSTMENT` coerced to VIDEO on old builds** → permanent z-order data loss | `minSchemaVersion() == 13` literal + array-presence stamp. **Reproduce the failure offline before fixing it** — the `schema_layer_stamp.py` drill. |
| R3 | **Merged z-loop inverts PiP order** — it touches the code the z-unification fix wrote | Byte-compare a 3-PiP export before/after; harness test pinning `orderedCompositedItems`. |
| R4 | **Feather-cache thrash** once two masked objects coexist | LRU lands in M0, before anything can produce a second masked object. |
| R5 | **AGSL/GLSL emit divergence** reintroduced by a careless table edit | Golden-string tests for both backends — they fail loudly on any emit change. That friction is the point. |
| R7 | **RenderEffect on the wrapper breaks touch / hit-testing** | Manual: drag a PiP, edit text, scrub with a layer active, on API 33 **and** 31. |
| R10 | **Shader compile failure on a specific driver takes the export down** | `AdjustmentLayerGlEffect` catches and degrades to passthrough; never throws. |
| R11 | **Drag-reorder loses keyframes** | Stable `slot` (never index) for both mask shapes and FX cards; harness test reorders a 3-card stack with keys on card 2. |
| R12 | **`FaditorEditorActivity` (26k lines) grows again** | All FX UI in `tools/FxPanel.java`; the activity gets ~60 lines of wiring, matching what `showPipDrawer` cost. |

---

## 10. Verification

**JVM harness** — new `tools/jvm-harness/run-fx.sh`, copying `run-key.sh` (gson-only classpath,
`@argfile`, positive control):

- `FxCompilerTest` — every registry effect compiles to both backends; **golden strings** for a fixed
  3-effect stack (the drift alarm); AGSL output contains no `vec2`/`texture2D`/`#`; GLSL contains no
  `float2`/`half4`; fusion counts (4 pointwise → 1 pass; pointwise+blur+pointwise → 3; twirl+blur →
  1); collision-free uniforms when the same effect appears twice; `resolveAt` identity when
  unanimated; round-trip with keyframes.
- `BlendModesTest` · `MaskSdfTest` (sign vs. hand-computed containment at ~40 points per config,
  plus boolean folds and 45° rotation where shear bugs are maximal) · `GradeParityTest` (Java
  mirror, `ChromaKey.keepFactor` pattern, including "all zeros = exact identity") ·
  `MaskAnimatorTest` · `CompositingSpecTest` · `AdjustmentSchemaTest`.

**Offline schema drills** (`tasks/*.py`):
- `schema_mask_stamp.py` — intersect forces v13; add/subtract-only JSON is **byte-identical to
  today**.
- `schema_adjust_stamp.py` — load v13 in a simulated v12 parser, autosave, **prove the layer
  vanishes**; then prove the stamp makes that build refuse the file.
- `getlayers_equiv.py` — band order unchanged for every project without an adjustment layer.

**Export A/B** (`tasks/export_ab_diff.py`):
- Adjustment layer with a single Invert over a solid-colour clip (pixel-exact expectation).
- Over a PiP stack — grades the ones beneath it and **only** those.
- Two layers with a PiP between them — chain-order-is-z survived the merge.
- A layer spanning a clip boundary; then the same with a transition and a loop-before extension.
- **Regression: export a PiP project with NO adjustment layer and byte-compare against pre-change
  output — the merged loop must be a no-op.**

**Manual.** The most convincing single demo: preview-vs-export A/B with vignette at 1.0. Before M2,
preview shows nothing and export shows a heavy vignette. After, they match.

---

## 11. Order of work

```
M0  Multi-shape masks               ← independent, ship alone; also fixes the missing-undo gap + feather LRU
M1  fx/ model + compiler + registry ← pure JVM; includes the BlendModes extraction
M2  Repackage the colour grade      ← proves the compiler against a shipping feature; fixes both §0.5 bugs
M3  AdjustmentLayer + TrackKind + z ← persists and exists; renders nothing. Schema v13 + stamp drill
M4  Export rendering                ← ground truth first. MaskSdf, AdjustmentLayerGlEffect, merged z-loop
M5  Preview rendering               ← wrapper + RenderEffect tiering, badge below API 31
M6  FX tab UI                       ← picker, cards, drag, params, keyframes, presets
M7  Registry expansion + per-object FX on PiP/text
```

M0–M3 each land as a self-contained shippable change with its own harness test. **M4+M5 together
are the first user-visible adjustment layer.** M6 is the first *pleasant* one.

**The one sequencing choice worth stating explicitly: M2 before M3.** It is tempting to build the
new object first, but M2 is the only milestone where the compiler's output can be checked against a
known-good shipping feature. If `FxCompiler` is wrong, you want to find that out while reproducing
the colour grade — not while debugging a brand-new layer type through two renderers at once.

---

## 12. Critical files

- [ExportManager.java](app/src/main/java/com/fadcam/ui/faditor/export/ExportManager.java) — the
  merged z-loop at :2711, the time correction, the canonical order doc at :2440
- [BlendModeGlEffect.java](app/src/main/java/com/fadcam/ui/faditor/export/BlendModeGlEffect.java) —
  template for `AdjustmentLayerGlEffect`; source of `blendPix` for the new `BlendModes` authority
- [CompositingSpec.java](app/src/main/java/com/fadcam/ui/faditor/model/CompositingSpec.java) — mask
  model, per-shape mode/slot; the self-serializing idiom the FX stack copies
- [MaskPathBuilder.java](app/src/main/java/com/fadcam/ui/faditor/model/MaskPathBuilder.java) — the
  gated intersect fold, the signature, the cache that must become an LRU
- [LayerPreviewController.java](app/src/main/java/com/fadcam/ui/faditor/compositor/LayerPreviewController.java)
  — the shared z authority; `orderedCompositedItems` is the new seam both renderers consume
- [ChromaKey.java](app/src/main/java/com/fadcam/ui/faditor/model/ChromaKey.java) — the
  single-authority pattern (`GLSL_KEY_FN` + Java mirror + packer) that `MaskSdf`, `BlendModes` and
  the FX compiler all follow
- [EffectStack.java](app/src/main/java/com/fadcam/ui/faditor/effects/EffectStack.java) +
  [ColorGradeShaderProgram.java](app/src/main/java/com/fadcam/ui/faditor/effects/ColorGradeShaderProgram.java)
  — the double-apply bug and what M2 collapses
- [GlTransitionShaderLoader.java](app/src/main/java/com/fadcam/ui/faditor/gltransitions/GlTransitionShaderLoader.java)
  — the in-repo template-token precedent the FX compiler generalizes
- [PipOverlayDrawer.java](app/src/main/java/com/fadcam/ui/faditor/tools/PipOverlayDrawer.java) +
  [PipDrawerTabs.java](app/src/main/java/com/fadcam/ui/faditor/tools/PipDrawerTabs.java) — the tab
  contract and the mask/chroma tab bodies
- [TrackKind.java](app/src/main/java/com/fadcam/ui/faditor/layers/TrackKind.java) — **read
  `minSchemaVersion()`'s javadoc before adding `ADJUSTMENT`**

---

## 13. Open questions for the user

None are blocking — M0 can start immediately.

1. **Layer naming.** Adjustment layers get a `name` field. Do you want them named on creation
   (a dialog), or auto-named "Adjustment 1/2/3" with rename via the object menu? (Note: §0.2 of
   `SPEC_OBJECT_TIME_SCRUBBER` records that the *lane* rename idea was dropped — this is about the
   object, which is a different thing.)
2. **Default span.** New adjustment layer spans the current selection, or the whole timeline?
   The plan assumes "selection if there is one, else whole timeline."
3. **Two adjustment layers in preview.** v1 previews only the topmost one live; the second renders
   correctly on export but shows a badge. Acceptable for v1, or should preview refuse to stack at
   all so the discrepancy is never silent?
