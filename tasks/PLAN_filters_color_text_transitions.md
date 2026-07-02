# Faditor — Filters, Color Grading, Text Styling & GL Transitions

**Supersedes:** `PLAN_filters_color_text.md`. Same goals, corrected to use Media3's real built-in effect classes where they exist (verified against current docs/source rather than assumed), plus a full integration plan for filling the Transitions menu with the GL Transitions library.

**Audience:** an AI coding agent executing against the existing FadCam/Faditor codebase. Read alongside `HANDOFF.md`, `docs/project-schema.md`, and the existing transition files: `Transition.java`, `TransitionPreviewOverlayView.java`, `ExportManager.java` (`buildVideoEffects()`, `TransitionExportOverlay`, `addTransitionTailItems()`).

---

## 0. Architecture Recap — and the One Real Wrinkle

The standing rule: color grade, LUT filters, and text overlays all attach to a **single MediaItem's effect list**, which is handed to both `ExoPlayer.setVideoEffects()` (preview) and the Transformer `EditedMediaItem`'s `Effects` (export). One list, two consumers, guaranteed parity. This part doesn't change.

**Transitions are different, and it's worth understanding why**, because it changes how "preview/export parity" gets implemented for them specifically:

- **Export** already processes real decoded video frames as GL textures (that's how Transformer works internally), and the handoff shows you're already injecting "the next clip's frame" as an overlay texture during the overlap window (`TextureOverlay`/`TransitionExportOverlay`). So for export, both textures a GL Transition shader needs (the outgoing frame, the incoming frame) are *already available* in your existing pipeline. Low risk.
- **Preview** is harder, because ExoPlayer's normal playback surface doesn't expose its live video frame as a texture you can feed into a custom shader unless you specifically opt into that. Your existing preview approach (decode a "next frame" bitmap via `MediaMetadataRetriever`, fade/mask it over the live `PlayerView`) works great for simple alpha-fades and rectangular masks, but a real per-pixel shader effect (zoom-burn, kaleidoscope, warp) needs *both* images sampled together inside one shader call — fading transparency over a live view isn't enough.

So: **the underlying shader code is shared between preview and export (same `.glsl` file, same wrapper), but the harness that feeds it frames is necessarily different** — Transformer already has GL frames flowing through it for export; preview needs a small dedicated piece of plumbing to get there. This doc gives you the low-risk version of that plumbing first, and notes the fancier upgrade as optional.

---

## 1. Color Grading — use Media3's built-in classes, not a fully custom shader

Verified against current Media3 docs: `androidx.media3.effect` ships `Brightness`, `Contrast`, `RgbAdjustment`, `HslAdjustment`, `RgbMatrix` as real, usable classes (part of `media3-effect`, the same module already in your dependency tree for transitions). Use these directly instead of writing your own brightness/contrast/saturation math — Google has already built and device-tested them.

| Slider | Implementation |
|---|---|
| Exposure / Brightness | `Brightness` effect — confirm whether its constructor takes an additive or multiplicative parameter before wiring the slider range; don't assume, check the actual method signature in your pinned Media3 version |
| Contrast | `Contrast` effect |
| Saturation | `HslAdjustment` effect (adjust the S channel) |
| Temperature/Tint | No built-in class for this — small custom `GlShaderProgram` doing a simple R/B channel multiply, same as originally planned |
| Highlights/Shadows | No built-in class (these need luma-based selective masking, not a global adjustment) — custom shader |
| Fade (black lift), Vignette, Grain | No built-ins — custom shader, same formulas as before |

**Recommended chain order in `EffectStack`:** `Brightness → Contrast → HslAdjustment → [custom: temperature/tint/highlights/shadows/fade/vignette/grain] → ColorLut (see §2) → TextOverlay(s) (see §3)`. Each is its own small `GlEffect` added to the same list — Media3 composes a chain of effects efficiently, you don't need to hand-merge the built-in ones into your custom shader.

**Caveat to flag for the agent:** most of `media3-effect` is annotated `@UnstableApi` (meaning the *shape* of the API can change between Media3 versions, not that it doesn't work). Pin your Media3 version in `build.gradle` and don't auto-bump it without re-testing this feature.

`ColorGradePanel.java` UI stays as previously planned (Basic: Exposure/Contrast/Saturation/Temperature, Advanced: collapsed section for the rest).

---

## 2. Filters / LUTs — use `ColorLut`/`SingleColorLut` first, custom shader only if you need intensity blending

Verified: `androidx.media3.effect.SingleColorLut` and the `ColorLut` interface are real, shipped classes specifically for this. Likely implementation detail (consistent with how every other Android LUT library does it, e.g. the long-standing `easyLUT` library): the LUT is represented as a single 2D bitmap with the 3D cube "unrolled" into tiles side by side (a 64³ LUT becomes a 512×512 image, a 16³ LUT becomes a 256×16 image) — not a literal `GL_TEXTURE_3D`, which keeps it compatible with older/weaker GPUs.

**v1 (recommended starting point): use `SingleColorLut` directly, filters are on/off, no intensity slider yet.** This is the least code, most reliable path — let Google's class do the GPU work.

1. `LutManager.java` — parses a `.cube` text file into the packed-2D-bitmap layout `SingleColorLut` expects (or, simpler: if your chosen Media3 version's `SingleColorLut` factory accepts a `Bitmap` directly, do the `.cube`→`Bitmap` conversion once at import time and cache the result; don't re-parse on every preview frame).
2. `LutPreset.java` — `id`, `displayName`, `assetPath`/`sourceUri`, `thumbnailPath`.
3. `LutLibraryPanel.java` — same visual pattern as `AssetBrowserPanel`: top-drop panel, grid of cached thumbnails (render each LUT against one fixed reference photo once, cache the bitmap, never re-render live), SAF import for user `.cube` files.

**v2 (only if you want an intensity slider):** confirm whether your Media3 version's LUT effect supports a blend/intensity parameter directly. If not, wrap it: write one small custom `GlShaderProgram` that samples both the original frame and the `SingleColorLut`-processed frame and linearly blends them by a uniform `intensity` (0–1) — this reuses the heavy lifting (the LUT lookup itself) while adding the one missing knob yourself.

**Licensing note (same caution as before):** check the license of any third-party `.cube` pack before bundling it as a built-in filter — many "free" packs are personal-use-only. Build 4-5 of your own to start (warm, cool, punchy, desaturated, teal-orange) and treat *import* as how users add licensed packs they've personally obtained.

---

## 3. Text Styling — same plan as before, now naming the real classes

Verified: `TextOverlay` and `TextureOverlay` are real classes in `androidx.media3.effect`, alongside `OverlayEffect` and `StaticOverlaySettings` for positioning/alpha. Use these for the overlay compositing step instead of a fully hand-rolled wrapper:

1. `TextStyle.java`, `TextOverlayInstance.java` — same JSON schema as before (see prior plan doc for the full field list and example).
2. `TextStyleRenderer.java` — render the styled text to a **software-backed** `Bitmap` (this is the one non-negotiable detail: `Paint.setShadowLayer()` and `BlurMaskFilter`, needed for shadow/glow, only work reliably on a software `Canvas`, not a hardware-accelerated `View` canvas). Cache the result; only re-render when style or text changes.
3. Wrap the cached bitmap as a `TextureOverlay`/`OverlayEffect`, add it to the same `EffectStack` effect list from §0 — same list feeds both preview and export, this one doesn't have the transitions wrinkle since it's single-clip compositing, not a blend between two clips.
4. Animate entrance/exit by interpolating the overlay's `StaticOverlaySettings` (position/scale/alpha) over time — no shader needed for simple fade/slide/scale.
5. For wipe-style text reveals, reuse the pixel-mask math you already built for `Transition.Type.WIPE`/`RADIAL`/`LINEAR_MIRROR_WIPE`, applied to the text overlay's alpha instead of a clip transition — direct port, no new math.
6. `TextStyleLibraryPanel.java`, `TextStyleIO.java` — same as before: panel pattern matches `AssetBrowserPanel`, import/export via SAF.

---

## 4. GL Transitions — filling the Transitions menu

### 4.1 What you're actually reusing

Every GL Transition is a tiny `.glsl` file containing one function:

```glsl
vec4 transition(vec2 uv) {
  return mix(getFromColor(uv), getToColor(uv), progress);
}
```

(That's the literal simplest example from the project's own spec — a plain crossfade — included here just to show the shape.) The spec guarantees every file in the collection follows this exact contract: a `progress` float (0→1), a `ratio` float (viewport aspect ratio), and two helper functions `getFromColor(uv)`/`getToColor(uv)` instead of raw texture reads. Some files also declare their own extra tunable constants as `uniform float someName /* = 1.0 */;` — the commented `= value` is their convention for a default, since old GLSL can't initialize uniforms inline.

This maps cleanly onto what a seam transition in your app already is: an outgoing clip, an incoming clip, and a progress value driven by the playhead/duration. That's why this is worth doing rather than hand-rolling more shaders yourselves.

### 4.2 Getting the files
**DONE (2026-06-19):** all 26 `.glsl` files fetched into `app/src/main/assets/gl_transitions/`
(verified: each contains the `vec4 transition(...)` contract; MIT headers present). The Java side
(loader/program/catalog/preview) is NOT built yet — that's the risky part needing a compile+device
loop. Original instructions below.


The transitions live in `github.com/gl-transitions/gl-transitions`, folder `transitions/`, one file per effect — and conveniently, the URL slug on the editor pages you linked **is** the filename. `.../editor/cube` → `transitions/cube.glsl`, `.../editor/heart` → `transitions/heart.glsl`, etc. Pull each of the 26 raw files from `https://raw.githubusercontent.com/gl-transitions/gl-transitions/master/transitions/<Name>.glsl` and save into `app/src/main/assets/gl_transitions/<Name>.glsl`. This is a one-time fetch, not something to automate at runtime.

**Licensing:** the project states its 123 transitions are "released under a Free License" and many individual files carry their own `// license: MIT` comment header. It's explicitly built for embedding in software (it's already used inside ffmpeg's transition filter). Still, glance at the repo's current `LICENSE` file before shipping, same as any third-party source.

### 4.3 The wrapper template (this is the actual integration mechanism)

Don't write a Java class per shader. Write **one** template, and every `.glsl` file gets pasted into the middle of it at load time:

```glsl
precision mediump float;
varying vec2 vUv;
uniform sampler2D fromTex;
uniform sampler2D toTex;
uniform float progress;
uniform float ratio;
/* EXTRA_PARAM_UNIFORMS_GO_HERE */

vec4 getFromColor(vec2 uv) { return texture2D(fromTex, uv); }
vec4 getToColor(vec2 uv) { return texture2D(toTex, uv); }

/* TRANSITION_BODY_GOES_HERE */

void main() {
  gl_FragColor = transition(vUv);
}
```

`GlTransitionShaderLoader.java`: reads the template once, string-concatenates each `.glsl` file's raw content into the marked spot, compiles the result into a GL program, and **caches the compiled program** (compiling is the expensive part — never recompile per frame, only once per transition the first time it's used).

`GlTransitionShaderProgram.java`: implements the Media3 `GlShaderProgram` interface (the same interface family your existing transition shaders should already target per the handoff's recommended direction), binds `fromTex`/`toTex`/`progress`/`ratio` plus any extra params each frame.

**On extra parameters:** don't bother auto-parsing the `// = value` comments for this curated set of 26 — just hand-declare each shader's known extra parameters (name + default + a reasonable slider range) in the catalog entry below. It's a one-time, bounded list; manual is more reliable than a regex parser for 26 files. If you later import many more community shaders beyond this curated set, *then* build the auto-parser as a nice-to-have.

### 4.4 Data model changes

Don't add a new `Transition.Type` enum value per shader — that doesn't scale past a handful. Add **one** new type:

```
Transition.Type.GL_SHADER
```

plus two new fields on `Transition.java`:
- `String glTransitionId` — references a catalog entry (§4.5) by filename/id.
- `Map<String, Float> paramOverrides` — nullable; lets a user's inspector tweaks override a shader's declared defaults.

Everything else about how transitions are stored, shifted on insert/split/delete, and selected on the timeline stays exactly as already built.

### 4.5 Catalog — filling the menu with the 26 you picked

`GLTransitionCatalog.java` — a small static list (or a bundled JSON, your call) with one entry per shader. Cost-tier and category below are best-guess from the name/visible behavior on the editor pages, not verified against every file's internals — confirm visually once each is wired in, and re-tag if a "Light" one turns out sluggish on your test device.

| Slug (= filename) | Display name idea | Likely category | Cost tier (confirm on device) |
|---|---|---|---|
| `zoomInOut` | Zoom Punch | zoom | Light |
| `CrossZoom` | Cross Zoom | zoom + dissolve | Light |
| `DefocusBlur` | Defocus | blur | **Heavy** (likely multi-sample blur) |
| `tangentMotionBlur` | Motion Blur | blur | **Heavy** (likely multi-sample blur) |
| `burn0` | Burn (Soft) | color flash | Light |
| `burn` | Burn | color flash | Light |
| `FilmBurn` | Film Burn | color flash | Light |
| `Overexposure` | Overexposure | flash | Light |
| `HSVfade` | HSV Fade | color-space dissolve | Light |
| `colorphase` | Color Phase | color-space dissolve | Light |
| `powerKaleido` | Kaleidoscope | symmetry warp | Medium |
| `polar_function` | Polar Warp | radial warp | Medium |
| `Dreamy` | Dreamy Wave | soft wave warp | Light |
| `swap` | Swap | slide/swap | Light |
| `crosswarp` | Cross Warp | warp dissolve | Light |
| `ripple` | Ripple | wave | Light |
| `Radial` | Radial Wipe | wipe (you may already have this) | Light |
| `heart` | Heart Reveal | shaped mask | Light |
| `Drop_Zone_Flicker` | Flicker Drop | strobe/flicker | Light–Medium |
| `cube` | Cube Rotate | fake-3D perspective | Medium |
| `BookFlip` | Book Flip | fake-3D page flip | Medium |
| `InvertedPageCurl` | Page Curl | fake-3D curl | Medium |
| `GridFlip` | Grid Flip | fake-3D tiled flip | Medium |
| `Fold` | Fold | fake-3D fold | Medium |
| `SimpleFlip` | Simple Flip | fake-3D flip | Light |
| `StereoViewer` | Split Viewer | split/zoom/mask sequence | Medium |

"Fake-3D" ones are all still plain 2D fragment-shader math (clever UV remapping, not real 3D geometry) — no 3D engine needed, they're just a bit more involved to read/tune than a flat dissolve.

### 4.6 Export integration (low risk — extend what already exists)

Your `ExportManager`'s `TextureOverlay`-based mechanism for injecting "the next clip's frame" during the overlap window already exists for wipe/radial/mirror. Generalize it: instead of compositing with a hand-coded mask formula, run the compiled `GlTransitionShaderProgram` for that seam, with `fromTex` = the current frame in the normal processing chain and `toTex` = the next clip's frame (already being injected via your existing overlay mechanism). Plug this into the same `buildVideoEffects()`/`addTransitionTailItems()` path. No new rendering surface needed — Transformer's pipeline is already GL-based for every frame regardless of effect.

### 4.7 Preview integration (the genuinely new piece — two options, start with the simple one)

Your current `TransitionPreviewOverlayView` is Canvas/bitmap-based — fine for alpha-fades and rectangular masks, not enough for a true two-texture shader effect. Two ways forward:

**Option A — pre-baked burst (recommended starting point, low risk, reuses what you already built):**
When the playhead approaches a `GL_SHADER` transition, decode a short burst of frames near the seam from *both* clips using the `MediaMetadataRetriever` mechanism you already have (the handoff mentions "cached retriever handling" — reuse it), e.g. 8–10 sample points across the transition's duration from each side. Run the compiled shader against each from/to frame pair at the matching progress value, producing a short sequence of already-blended preview frames. Play that sequence back as a small looping animation in the overlay during scrubbing. This is not perfectly live-accurate frame-by-frame, but for a sub-1-second transition that's a fine trade, and it needs zero new Android plumbing beyond what's already in the codebase.

**Option B — true live preview (upgrade, more advanced, only if Option A isn't smooth/accurate enough):**
Have ExoPlayer render to a `TextureView` via `player.setVideoTextureView(textureView)` (a standard, stable ExoPlayer API) instead of the default `SurfaceView`. This gives you a `SurfaceTexture` you can bind as a `GL_TEXTURE_EXTERNAL_OES` texture and sample directly inside your shader alongside the decoded "next clip" texture — true live, frame-accurate preview. This is real OpenGL plumbing (EGL context, external texture binding) — the most technically involved single piece in this whole plan. Don't start here; only reach for it if Option A visibly isn't good enough once you've tried it.

Either way: add a **new, separate** GL-capable preview view (`GlTransitionPreviewView`) used only when `Transition.Type == GL_SHADER`, shown alongside your existing `TransitionPreviewOverlayView` which keeps handling the already-working crossfade/wipe/radial/mirror cases untouched. Don't risk what's already passing device tests by rewriting it — add a sibling, not a replacement.

### 4.8 Menu UI — nice previews without a live GL context per card

For the transitions browser panel itself (cards in the carousel, not the live seam preview above): pre-render a small sprite sheet per shader, once, off the main thread — apply the shader to one fixed pair of sample images (bundle two small generic reference frames as assets) at 6–10 progress steps, cache as a single small bitmap atlas per shader. Animate each visible card by cycling through the atlas frames on a timer (cheap `ImageView`/custom-draw cycling, no live GL surface running per card). This matches the "keep it lite" goal — dozens of cards, zero ongoing GPU cost while just browsing the menu.

---

## 5. New Files Summary (cumulative, supersedes the prior list)

```
effects/
  EffectStack.java, ColorGradeParams.java
  ColorGradeShaderProgram.java        (custom: temp/tint/highlights/shadows/fade/vignette/grain)
  LutManager.java, LutPreset.java, LutLibraryPanel.java
  ColorGradePanel.java

text/
  TextStyle.java, TextOverlayInstance.java
  TextStyleRenderer.java, TextStyleIO.java, TextStyleLibraryPanel.java

gltransitions/
  GlTransitionShaderLoader.java   (template wrap + compile + cache)
  GlTransitionShaderProgram.java  (Media3 GlShaderProgram impl, used in export)
  GlTransitionPreviewView.java    (new GL-capable preview surface, used in preview, sibling to existing view)
  GLTransitionCatalog.java        (the 26-entry table from §4.5)

assets/
  shaders/color_grade.frag
  luts/*.cube
  text_styles/*.json
  gl_transitions/*.glsl           (26 files fetched per §4.2)
  gl_transitions/sample_from.jpg, sample_to.jpg  (reference images for §4.8 thumbnails)
```

Existing files touched: `Transition.java` (`GL_SHADER` type, `glTransitionId`, `paramOverrides`), `EditorTimelineView.java` (render GL-shader transition bands same as existing ones), `ExportManager.java` (`buildVideoEffects()` generalized per §4.6), `FaditorEditorActivity.java` (instantiate/show `GlTransitionPreviewView` alongside existing overlay), `FaditorProject.java`/`ProjectStorage.java` (schema v4 as previously planned).

---

## 6. Build Order

1. **Foundation:** schema v4 + empty effect list plumbing (as before).
2. **Color grade:** `Brightness`/`Contrast`/`HslAdjustment` chain + custom shader for the gaps + `ColorGradePanel`.
3. **LUT filters:** `SingleColorLut` v1 (on/off) + `LutLibraryPanel`, built-in LUT set.
4. **Text styling:** schema, renderer, `TextOverlay` compositing, library panel.
5. **GL Transitions — export first:** fetch the 26 files, build the wrapper/loader/catalog, wire export via §4.6 (lower risk, reuses existing GL pipeline). Validate exported output for at least 5 transitions across the cost tiers before moving on.
6. **GL Transitions — preview:** Option A (pre-baked burst) per §4.7. Only attempt Option B if A proves insufficient.
7. **Transitions menu UI:** populate the panel from `GLTransitionCatalog`, add sprite-sheet thumbnail caching per §4.8.
8. **Polish:** thumbnail/GL-program caching audits, dispose GL resources on lifecycle, perf pass on the "Heavy" tier shaders specifically.

## 7. Testing Checklist

- Compile check after each numbered step.
- Device-test exported output for: one Light, one Medium, one Heavy-tier GL Transition — confirm visual correctness and check for frame drops/encode slowdown on the Heavy ones specifically.
- Device-test the menu carousel scrolled through all 26 cards — confirm sprite-sheet thumbnails animate without stutter and without spinning up a live GL context per card.
- Device-test scrubbing across a GL Transition seam (Option A) — confirm the pre-baked burst looks reasonably smooth, not a single static frame.
- Confirm a GL Transition with custom parameters (if any of the 26 turn out to have them) exposes working sliders in the existing transition inspector.
- Re-run the existing crossfade/wipe/radial/mirror device tests from the original transition work to confirm the new `GlTransitionPreviewView` sibling view didn't disturb the already-working Canvas-based overlay path.
