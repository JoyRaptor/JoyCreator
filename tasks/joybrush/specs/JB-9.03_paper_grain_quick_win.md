# JB-9.03 — Quick win: pencil and Sable feel a real paper that never shows its grid

| | |
|---|---|
| **Tier** | T1 + T3 phone check |
| **Status** | 🟦 Ready (paper specialist, 2026-10-01) |
| **Builder** | Codex |
| **Depends on** | none to start (JB-9.02's maths is pasted below; its CPU twin may land in parallel; reconcile at the end) |
| **Owner area** | NEW `joybrush/shaders/jb_paper.glsl`; EDIT `joybrush/shaders/jb_grain_sample.glsl`, `jb_dab.frag`, `jb_tuft.frag` (paper reads only); EDIT `joybrush/androidkit/.../gl/GrainTextures.kt`, `GlPaintEngine.kt` (paper texture binding + uniforms only); EDIT `joybrush/core/.../grain/GrainMath.kt` (paper pitch → surface constants); EDIT `core/brush/TuftStroke.kt` (`PAPER_TOOTH_SCALE` only); packaging of `joybrush/assets/paper/` the same way `assets/grain/` is packaged; tests that pin these (`GrainWiringTest`, `DefaultPresetsTest`, `ShippedBrushFilesTest`, `PencilOnAFingerTest`, `TuftTuningTest`) only where a pinned number changes |
| **Estimated size** | ~200 lines + test updates |

**🔴 Hot files.** `GlPaintEngine.kt` and `shaders/*` belong to the Joy Brush Lead (LEAD_DESK order 3). The owner has authorised
other harnesses to work while the Claude sessions are on cooldown. Before editing: `git log -5 --format='%h %an %s' -- <file>`
for each hot file, rebase on `origin/joy-creator`, keep your commit to the paper lines, and write one line under
"Questions for the Lead" in `tasks/joybrush/LEAD_DESK.md` saying what you changed and why.

## Goal
The owner's verdict: "a really good pencil with a bad paper texture". Cause: one blocky 256-px value-noise picture repeated
every ~43 px. After this row, the pencil and Sable (paper tooth + stray-hair stutter) feel ONE real paper surface
(`assets/paper/surface_pulp_artisan.png`, 512² RGBA, made by `tools/paper/pack.py`). It is read through the hex sampler, so no
grid shows anywhere. Same brushes, a better paper. Nothing about the threshold maths changes.

## Contract (verbatim)
Asset and its numbers (`joybrush/assets/paper/catalogue.json`, entry `pulp_artisan`):
`size 512 · texelPx 2.0 (doc px per texel) · slopeRange 0.099 · hexTexels 180 · rotatable true`.
Layout (JB-9.01): R = encodeSlope(dh/dx), G = encodeSlope(dh/dy), **B = height**, A = height².

NEW `jb_paper.glsl` (GLSL ES 3.00, `#include`-able, no `#version`/`main`). It is the twin of JB-9.02's maths, line for line:
```glsl
uniform sampler2D u_paperSurface;   // RGBA8, REPEAT, mipmapped
uniform float u_paperTexelPx;       // doc px per texel
uniform float u_paperSize;          // texels across (square)
uniform float u_paperHexTexels;
uniform float u_paperSlopeRange;
uniform bool  u_paperRotatable;

float jb_hash(int i, int j, int k);                 // lowbias32, uint maths, (x >> 8u) / 16777216.0
// Returns (dh/dx, dh/dy) per DOC PX, height, height² at a document point. Uses textureGrad with the
// derivatives of the UN-rotated texel coordinate, rotated by θ, so mips stay correct.
vec4 jb_paperSurface(vec2 docPx);
```
`jb_grain_sample.glsl`: `jb_paperGrainHeight(docPx)` returns `jb_paperSurface(docPx).z` when the paper is on. The
`u_paperGrainPitchPx > 0` switch remains the on/off test (set it to `u_paperTexelPx` when on, 0 when off).

## Decisions already made
1. **Every brush's PAPER grain reads the one surface** (`GrainMath.DEFAULT_SURFACE = "surface_pulp_artisan.png"`) until
   JB-9.06 makes it per document. The brush's `paperGrain.image` / `scale` are no longer used for the PAPER read (the tip
   texture is untouched). Ruling R10 P4: the paper is universal.
2. **Physical size comes from the paper** (`texelPx`), not the brush's `scale`. Pencil was tuned at a ~1.5-px clump with
   `cloud_fine_256`. The new pulp's features are ~2–10 doc px. Pencil's `depth`/`edge` stay; the owner judges on the Note 9.
3. **Sable's tooth** reads the same surface (`TuftShading.paperAsset/paperPitchPx` → the surface + its texelPx).
4. **Height is B, not R** (R now holds a slope). Every `.r` paper read becomes `.z` of `jb_paperSurface`.
5. **The R45.4 rule stands:** a missing or undecodable surface = paper OFF for that stroke (pitch 0), never white. The 1×1 placeholder still binds.
6. `GrainTextures` loads RGBA (no `.r`-only assumption). Mipmaps on (`LINEAR_MIPMAP_LINEAR`), REPEAT, unchanged.
7. The CPU twin `GrainMath` gets the surface constants. Where a CPU test computes paper height, it calls JB-9.02's
   `HexTile.sampleSurface` if that has landed; otherwise leave a `TODO(JB-9.02)` and note it in your report.

## Steps
1. `jb_paper.glsl` from the maths in JB-9.02. 2. Wire it into `jb_grain_sample.glsl`, `jb_dab.frag`, `jb_tuft.frag`.
3. Package `assets/paper/`, load the surface in `GrainTextures`, bind it in `GlPaintEngine` (dab and tuft programs, unit 1, every batch).
4. Update pinned tests honestly (each changed number with its reason in the test). 5. `tools/shader_check.js`: add a check that
the new paper compiles and links in headless Chromium/Edge (WebGL2), as JB-1.20 did. 6. Build the APK (do NOT install; §T3).

## Tests
- `shader_check.js`: `jb_dab.frag` and `jb_tuft.frag` with `jb_paper.glsl` compile + link, `gl.getError() == 0`. Paper-on dabs filling
  a 2048×256 doc-px strip show NO repeat: the correlation of the strip's left half with its right half (a shift of 1024 doc px,
  exactly one texture period) is < 0.3. With the hex read disabled, it would be ≈ 1. Show that control too.
- `:core:jvmTest --rerun-tasks` and `:joybrush-android:compileDebugKotlin` green; `:app:assembleDefaultDebug` builds. Paste counts.
- If JB-9.02 has landed: a jvmTest that evaluates `HexTile.sampleSurface` at 20 seeded doc points and a GL readback of the same
  points (via `shader_check.js` fixtures) agree within 2/255 in height.

## T3 (phone): do NOT install yourself
Installing kills an unsaved drawing, and the owner may be drawing. Leave the APK path in your report. The owner or a Claude session
installs it after the owner presses Home. The owner then judges: pencil light→hard, Sable dry strokes, no grid at 1× and at 4×.

## Do not
- Do not change `jb_heightCoverage`, `jb_grainLevel` or the tip texture path.
- Do not delete `cloud_*.png` (tip textures and old brush files still name them).
- Do not install on any phone. Never on the Note 20.
- Do not run Gradle in the main folder (R43). Worktree under `$TEMP/jb-9.03`, copy `local.properties` (root and `joybrush/`) and `tools/devices.local.sh`.

## Definition of done
- [x] shader check + suites pass (paste) · [x] LEAD_DESK line written · [x] commit "JB-9.03: …", rebased, pushed
- [x] ROADMAP row → 🟧 Built — **awaiting the owner on the Note 9**

## Verification (2026-10-01)

- `.\gradlew.bat --no-watch-fs -p joybrush :core:jvmTest --rerun-tasks`: XML 1328 tests, 0 failures, 0 errors, 0 skipped.
- `.\gradlew.bat --no-watch-fs :joybrush-android:compileDebugKotlin :app:assembleDefaultDebug`: passed; no installation.
- `node joybrush/tools/shader_check.js`: headless Edge compiles/links dab + tuft, GL error 0; production dab correlation 0.029826 versus plain-repeat control 1.0.
- Packaged library contains the surface PNG, catalogue and `jb_paper.glsl`.
- APK: `%TEMP%/jb-9.03/app/build/outputs/apk/default/debug/app-default-arm64-v8a-debug.apk`; awaiting the owner on the Note 9.
- JB-9.02 has not landed: `PencilOnAFingerTest` records `TODO(JB-9.02)` for CPU hex sampling; shader/CPU seeded-point parity awaits that twin.

## Questions
