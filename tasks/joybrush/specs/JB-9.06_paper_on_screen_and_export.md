# JB-9.06 — The paper you SEE: drawn behind every layer, swappable, exported or left out, right at every zoom

| | |
|---|---|
| **Tier** | T1 + T3 phone check |
| **Status** | 🟦 Ready (paper specialist, 2026-10-01) |
| **Builder** | Codex |
| **Depends on** | JB-9.03 + JB-9.03b, JB-9.04, JB-9.05, all LANDED first (no building other rows; see the answer under Questions) |
| **Owner area** | NEW `joybrush/shaders/jb_paper_bg.frag` (+ `.vert` if needed); EDIT `GlPaintEngine.kt` (`draw`/`drawComposited` paper pass, paper cache, surface per document); EDIT `JbCanvasView.kt` (`refusalFor` texture clause removed; `paper` state replaces `paperArgb` as the source of truth, keeping `paperArgb` working for callers); EDIT `GrainTextures.kt` (looks); NEW `joybrush/core/.../paper/PaperRaster.kt` (CPU paper for export); EDIT `androidkit/.../io/CanvasPng.kt` (compose paper under the render; also `AnimExport.kt`/`OraExport.kt` if they take a paper colour today); tests for each. **Hot files: same rule as JB-9.03 (log check, rebase, LEAD_DESK line).** |
| **Estimated size** | ~450 lines + ~250 lines of tests |

## Goal
Owner P1/P2/P5/P7: the paper shows behind the drawing as a convincing analogue material, never shows a seam or loop,
looks right zoomed out (0.05×) and in (64×), swaps instantly, and exports with the drawing or leaves it transparent.
Every grained brush feels the DOCUMENT's surface from now on (JB-9.03 used one fixed surface).

## Contract (verbatim)
```kotlin
// GlPaintEngine
fun setPaper(p: ResolvedPaper)          // GL thread. Loads look + surface textures on first use; invalidates the paper cache
fun draw(viewportW: Int, viewportH: Int, docToClip: FloatArray, paperArgb: Int)   // unchanged signature; paperArgb now
                                        // only matters when no ResolvedPaper has been set (old callers, tests)
// JbCanvasView
var paper: ResolvedPaper                // replaces paperArgb as the source of truth; paperArgb getter = paper.baseArgb
// core
object PaperRaster {
    /** Straight RGBA8 of [rect] (doc px) of the paper alone, opaque, the same maths as jb_paper_bg.frag at zoom 1
     *  with NO screen-space detail fade (export is resolution-exact). [light] uses the screen-default light direction. */
    fun render(p: ResolvedPaper, look: PaperTexture?, surface: PaperTexture?, rect: RectPx): ByteArray
}
```

### The paper pass (screen and export share these lines)
```
t_look = docPx / (look.texelPx · scale)        t_surf = docPx / (surface.texelPx · scale)
look   = look ? HexTile.sampleLook(t_look) : base          // tinted: base · look / look.mean  (per channel), when tint set
s      = surface ? HexTile.sampleSurface(t_surf) : 0       // slopes per texel → per doc px: / (texelPx·scale)
n      = normalize(−s.x·RELIEF_GAIN·relief, −s.y·RELIEF_GAIN·relief, 1)
L      = normalize(−0.45, −0.55, 0.70)  in SCREEN space (upper-left desk lamp), turned into doc space by the view rotation
shade  = light ? mix(1, clamp(dot(n, L) / L.z, 0.6, 1.4), show) : 1
colour = mix(base, look, show) · shade
```
Constants: `RELIEF_GAIN = 6.0` (owner tunes). The light stays fixed to the SCREEN like a lamp over the desk, so rotating the canvas
moves the shading on the bumps, as it would on a real sheet.

## Decisions already made
1. **Paper is a pass, not a layer** (Blueprint: "paper is a setting, not a layer"). It replaces the flat clear colour in both
   `draw` and `drawComposited`. Layers composite over it exactly as they composite over the clear colour today.
2. **Cache.** Render the paper into a viewport-sized texture only when the view transform, viewport or paper changes. Otherwise blit
   it (one fetch per pixel). While a pinch is in progress, re-render every frame. That is acceptable; measure and report ms on the Note 9 if a Claude session installs it.
3. **Zoom out (to 0.05×):** mipmaps do the work. The look is linear, so mips are right. The relief's shading uses the mip-averaged
   slopes; it fades naturally. No threshold runs in this pass, so it cannot shimmer.
4. **Zoom in (to 64×): the detail octave** (owner, 2026-10-01: the canvas is a lot of zoom, not truly infinite, so detail fades
   in as you zoom, Mischief-style: a finer, offset copy fading in gradually so it never reads as a loop). A second
   `sampleSurface` of the SAME surface at `t_surf · DETAIL_RATIO` (= 8), hash k offset by +10 (a different arrangement), its slopes
   (per doc px) ADDED with weight `DETAIL_STRENGTH · smoothstep(4, 16, zoom)`, `DETAIL_STRENGTH = 0.35`. Display only:
   brushes deposit at doc px, so the layer resolution, not the view, bounds their detail.
5. **Precision on an endless canvas.** The hex lattice + hash is NOT periodic, so you cannot just reduce the origin modulo the
   texture size. Instead, per octave, on the CPU in Double: find the lattice vertex `(iBase, jBase)` at the viewport's corner
   (JB-9.02 `lattice`). Pass `ivec2 u_hexBase`, `vec2 u_localOrigin` (corner minus that vertex's centre, in texels: small)
   and `vec2 u_baseCentreMod` (that vertex's centre mod `size`). The shader runs the lattice on LOCAL coordinates (exact,
   because the base is a lattice point), hashes `(iBase + di, jBase + dj, k)` with ints, and reads the texture at
   `R(θ)(p_local − c_local) + c_local + u_baseCentreMod + offset`. This is the same result as the global formula, with only small floats.
   Put the CPU half in one function `PaperRaster.localFrame(...)` with a test. Brushes' dab shaders keep plain doc-px floats (fine
   to ±1e6 doc px; note it in a comment).
6. **Brushes feel the document surface**: `beginStroke` binds the document's surface (or OFF when `textureId == null`), multiplies
   paper depth by `bite`, and uses `scale`. Sable's tooth follows the same rule.
7. **Swapping** the paper is ONE undo step and does not touch any tile (raster strokes keep the grain they were laid with; P5).
   Undo of a paper change is the Activity's/Lead's wiring (JB-9.07). This row exposes `setPaper` only.
8. **Export:** `CanvasPng` renders layers with paper = null (transparent), then composes them OVER `PaperRaster.render(...)` when
   Include paper is on. Off → transparent PNG. A look/surface that fails to load → the flat base colour, and a toast-able warning string.
9. **Open:** `refusalFor` no longer refuses `textureId`. An unknown id loads as smooth/flat (JB-9.05 problems → log line).
10. AMOLED black (`base #000000`, `light false`) must render pure 0,0,0 on screen and in export (test it).

## Tests
- `PaperRasterTest` (core): a flat paper → every pixel = base. A look of constant colour C with tint T → T·C/mean. With light off,
  the surface has no effect on colour. AMOLED → exact zeros. No repeat: correlation across one look period < 0.3.
- Local frame: at doc points near ±1e7, the local-frame evaluation equals the global Double evaluation to 1/255. In the shader
  (`shader_check.js`), a 64×64 render whose corner is at doc (1e7, −1e7) shows no staircase: adjacent pixels on a smooth test
  texture differ by < 3/255.
- `shader_check.js`: `jb_paper_bg.frag` compiles and links; a 256×256 render equals `PaperRaster` (from a JSON fixture the jvmTest writes) within 3/255 per channel at zoom 1.
- androidkit (`CanvasPngTest`): Include paper on/off → opaque/transparent; layer pixels identical in both.
- Every existing paper/export test still passes; `:app:assembleDefaultDebug` builds. Paste counts.

## T3 (phone; do NOT install yourself)
Owner judges: paper visible behind strokes, no seam at 0.05×/1×/8×/64×, light on/off, swapping papers, export with and without paper.

## Do not
- Do not bake the paper into any tile.
- Do not install. Do not run Gradle in the main folder.
- Do not build the Paper sheet UI (JB-9.07) beyond a temporary debug toggle, and if you add one, remove it before your last commit.

## Definition of done
- [ ] all tests pass (paste) · [ ] LEAD_DESK line · [ ] commit(s) "JB-9.06: …", rebased, pushed · [ ] ROADMAP row → 🟧 Built — awaiting the Note 9

## Questions

2026-10-01 Codex: Paused per the owner's rule for incorrect/unclear specs. JB-9.04's strict parser rejects the shipped `off_white.detailStrength` key: it is absent from LookEntry, while JB-9.04's owner area excludes catalogue.json. Should the specialist remove that obsolete key, or should LookEntry support it? This prevents its required ShippedCatalogueTest from passing without guessing.

The CPU HexTile/PaperTexture dependency (JB-9.02) has not landed and is not listed here, nor is it in the instruction to build missing dependencies first. May this row build JB-9.01/9.02 first, or should it wait for the OpenCode lane? Decision 4 also requires a hash-k offset for the detail octave, which the canonical sampleSurface API cannot express; which shared API extension should both CPU and GPU use? Continuing to JB-9.08 while these contracts are clarified.

**Specialist answers (2026-10-01):**
1. `detailStrength` is deleted from `catalogue.json`. `LookEntry` is as JB-9.04's contract says.
2. **Do not build other rows.** The "build them first" sentence in this spec's Depends-on is WITHDRAWN. This row starts when JB-9.03b,
   9.04 and 9.05 have landed (PAPER_DISPATCH.md gates). JB-9.01/9.02 have landed.
3. Detail-octave seed: JB-9.03b adds `seed: Int = 0` to `HexTile.sampleSurface/sampleLook` and `jb_paperSurface(docPx, seed)`. Use `seed = 10`.
4. Surface folders come from the catalogue (JB-9.03b Decision 10). Load every look/surface by its catalogue entry.
