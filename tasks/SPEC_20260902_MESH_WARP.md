# SPEC_20260902_MESH_WARP — Bendy mesh warp for image overlays

Status: DESIGN. No code written. Research pass verified against the tree at `joy-creator` @ `98c031c8`.

Companion doc: `tasks/design/SKEW_WARP_OPTIONS.html` (Option C, "Corners first, grid later", is the
chosen UI). This spec designs what the **"+ Finer"** button opens into.

---

## 0. Summary for JoyRaptor — plain language

**What you asked for.** Bend a photo like cloth, cheaply, easily, and — the important bit — bend it
*inside* the graphics chain, so a blend mode on the picture and an adjustment layer sitting above it
both actually see the bent version.

**What I found.** The app already has two different ways of putting a picture on screen, and only one
of them can bend. On **export**, a picture is painted with Android's ordinary drawing tools into a
full-size transparent sheet, and that sheet is then handed to the shader that does blend modes. That
route can bend today — the Avatar puppet already uses the exact drawing call. On the **live preview**,
a picture is drawn by a piece of shader maths that assumes the picture is a rectangle. That route
*cannot* bend, at all, ever. It is not a matter of effort; the maths runs backwards (it asks "which
bit of the photo is at this screen pixel?", and a bent photo has no cheap answer to that question).

So the two halves of the app would bend differently, and preview-vs-export disagreement is the single
worst bug family in this project.

**The decision.** Both halves get rebuilt the same way: the bent picture is drawn **as real geometry
on the graphics chip** — the photo is cut into a fine net of triangles and each corner of the net is
moved — into a full-frame transparent sheet, and *then* that sheet goes into the blend/effects/mask
machinery exactly as an unbent picture does today. Nothing downstream changes. Blend modes work.
Adjustment layers work. Chroma key works. Masks work. And the preview and the export run the *same*
maths from the *same* file, so they cannot drift.

**Why not just make it an effect.** Confirmed, with the code in front of me: an effect can only
re-shuffle pixels *inside* the picture's existing rectangle. Every sample it takes is clamped to the
edges of that rectangle (`FxCompiler.java:268`, `:201`, `:714`). Pull a corner outward and it is
simply cut off. Warp has to be part of the object's *shape*, not an effect painted on it. Your options
page already said this; it is correct.

**Honest cost.** Per bent picture, per frame: one extra full-frame drawing pass and one extra
full-frame scratch image. On your Note 20 at 1080p that is roughly **1.5–2 milliseconds and 8 MB**
per bent picture. You have 33 ms per frame. So: one to three bent pictures on screen at once is
free-feeling; eight at once will stutter. Export gets slower by about **3.5 seconds per minute of
video, per bent picture**. Nothing changes at all for a picture you never bend — the whole path is
skipped, and your 19 existing projects open and export byte-identically.

**Biggest risk.** Not speed. It is the *keyframes*. A 5×5 net is 50 numbers, and this app animates
one number per track with one diamond per track. Fifty diamonds and fifty undo steps per drag would
be unusable. The fix is designed in below (§3.3): the whole net is **one** keyframe and **one**
diamond, exactly as your options page promised.

---

## 1. Render architecture

### 1.1 The decision

**GL geometry. A tessellated triangle grid with per-vertex UVs, rendered into a frame-sized RGBA
"stamp" texture, which then enters the existing full-frame composite unchanged.**

Not `Canvas.drawBitmapMesh`. Not a UV-remap effect.

### 1.2 Why `Canvas.drawBitmapMesh` cannot be the answer — with evidence

It is not that Canvas mesh output can't reach GL. On the **export** path it demonstrably can, and
this is worth stating precisely because it is the reason a naive reading says "just use Canvas":

- `ImageOverlayFrameOverlay.getBitmap()` (`export/ImageOverlayFrameOverlay.java:57–90`) rasterises
  one image overlay into a **frame-sized ARGB_8888 bitmap** on a plain `Canvas`.
- `ImageOverlayDraw.draw()` (`export/ImageOverlayDraw.java:180`) is the single `canvas.drawBitmap`
  call that puts the photo into that sheet.
- `ImageBlendGlEffect.Program.drawFrame` (`export/ImageBlendGlEffect.java:229–239`) uploads that sheet
  as `uOverlayTexSampler0` and composites it with a **full-frame quad** (`glDrawArrays(GL_TRIANGLE_STRIP,
  0, 4)`, attribute `aFramePosition` bound to `GlUtil.getNormalizedCoordinateBounds()` at `:208`).
- Downstream, `ExportManager.assembleClipVideoEffects` appends `AdjustmentLayerGlEffect` **after**
  the image blend effects (`export/ExportManager.java:3296` then `:3347`), and chain order is paint
  order, so an adjustment layer already grades whatever those effects produced.

So on export, swapping `canvas.drawBitmap` for `canvas.drawBitmapMesh` **would** give a bent picture
that blend modes and adjustment layers see. That is real and it is tempting.

The reason it is still wrong is the **preview**, and the reason is structural:

- A preview image overlay that carries FX/blend/key/mask leaves the `ImageView` path and becomes a
  `FxPreviewTextureView.Pip` (`overlay/TextOverlayLayer.java:1264 fxPipFor`, gated by
  `model/TextOverlayItem.java:753 wantsGlExport`).
- `drawPip` (`compositor/FxPreviewTextureView.java:1758`) draws a **full-frame quad** (`:1834`) and
  locates the picture by an **inverse map in the fragment shader**: `PIP_FRAGMENT`
  (`:158`, body at `:190–200`) takes the screen uv, subtracts `uPipCentre`, aspect-corrects, rotates
  by `uPipCos/uPipSin`, divides by `uPipHalf`, and tests `abs(q) <= 1.0`.
- The only vertex shader in the whole preview chain is `FxGlSource.VERTEX_SHADER`
  (`fx/FxGlSource.java:44–51`), which hard-codes `vFxUv = aFramePosition.xy * 0.5 + 0.5`. It has no
  concept of geometry other than the full-frame quad `QUAD` at `FxPreviewTextureView.java:305`.

An inverse map is trivial for a rotated rectangle (invert a 2×2 rotation) and **has no closed-form
cheap inverse for a piecewise-bilinear or spline mesh** — inverting it per fragment means a Newton
iteration per pixel, per frame. That is the wall. The preview cannot express a mesh in the shape its
PiP path is written in.

Therefore the two paths would diverge by construction: export bends via Canvas, preview cannot bend
at all. That is precisely the top-severity bug class in this repo, and the design must not create it.

**Second, independent reason to prefer geometry even on export:** `Canvas.drawBitmapMesh` is a CPU
rasterisation of an `N×M` mesh into an 8.3 MB ARGB bitmap **per frame**, followed by a full texture
re-upload per frame (`ImageOverlayFrameOverlay` deliberately returns a fresh `Bitmap.createBitmap`
copy each frame — see its note at `:83–88`). At 1080p that is a CPU fill of ~2 Mpix plus an 8.3 MB
PCIe/bus upload every frame. The GL geometry path does the same work on the GPU with a ~5 KB vertex
buffer upload. The Canvas route is the *more* expensive one, not the cheap one.

### 1.3 Why UV_REMAP is not a home for warp — the owner's question answered

`FxEffectDef.Capability.UV_REMAP` declares `vec2 fx_uv(vec2 uv)` (`fx/FxEffectDef.java:48`), used by
the `offset` DISTORT effect (`fx/FxRegistry.java:394`). The compiler folds a UV_REMAP into the *next*
sample, and every sample is clamped:

- `FxCompiler.java:201` — the pass entry reads `texture2D(uTexSampler, fxClamp(uv))`
- `FxCompiler.java:714–716` — `FX_SAMPLE` expands to `texture2D(uTexSampler, fxClamp(fxRemap(%s)))`
- `FxCompiler.java:268` — `vec2 fxClamp(vec2 p) { return clamp(p, vec2(0.0), vec2(1.0)); }`

The clamp exists for a good reason (its note at `:259`: it stops a blur sampling transparent black
outside the content rect). But its consequence is absolute: **a UV remap can only ever re-fetch
pixels that are already inside the layer's rectangle.** It moves pixels *within* the box. Nothing can
be pushed *outside* the box, because the box is where the fragments are — there are no fragments
outside it to write to.

Plain-language version for JoyRaptor: an effect is a set of instructions applied *to each pixel that
already exists*. Bending a photo means creating pixels where the photo previously wasn't. You cannot
paint outside the canvas by changing how you mix the paint. Warp is a change of *shape*; effects are
changes of *colour at a fixed shape*.

Also note `FxEffectDef.foldsColor()` (`:91`) already excludes UV_REMAP from the per-card
blend/opacity fold, on the same "a coordinate has no colour" reasoning. The architecture is already
telling us warp does not live there.

### 1.4 The chosen architecture: the "stamp"

One new pure-Java authority and two thin renderer adapters.

```
                       shared, android-free
        ┌───────────────────────────────────────────────┐
        │ faditor/warp/MeshWarp.java                    │
        │  · lattice → tessellated vertex + uv arrays   │
        │  · Catmull–Rom evaluation                     │
        │  · refinement (4→9→25)                        │
        │  · JVM-harness testable (MeshWarpTest)        │
        └───────────────────────────────────────────────┘
                    │                        │
        preview     │                        │  export
                    ▼                        ▼
   compositor/MeshStampGl.java     export/MeshStampGl (same class,
   (owns FBO + program, on the      instantiated from the media3
    preview GL thread)              effect thread)
                    │                        │
                    ▼                        ▼
   full-frame stamp texture (RGBA8, frame-sized, alpha-premultiplied? NO — straight alpha,
   matching what ImageBlendGlEffect already expects: it unpremultiplies at :113)
                    │                        │
                    ▼                        ▼
   drawPip() with the rect          ImageBlendGlEffect.drawFrame()
   inverse-map replaced by          with uOverlayTexSampler0 = the
   identity (uv = vFxUv)            stamp instead of the Canvas sheet
                    │                        │
                    ▼                        ▼
        blend mode · chroma key · mask · per-object FX · opacity
                    │                        │
                    ▼                        ▼
        adjustment layers above it (chain order = z order)
```

**The one-sentence contract: the stamp is a drop-in replacement for the frame-sized bitmap
`ImageOverlayFrameOverlay` produces today.** Everything downstream is untouched — which is exactly
why blend modes and adjustment layers work, and why the change is auditable.

The precedent for this shape is already in the tree and works: `export/GlPipFrameOverlay.java` creates
its own texture + FBO + program on the effect thread (`:110–182`), renders positioned geometry into
the frame-sized FBO (`:244–260`), and returns the texture id for `BlendModeGlEffect` to composite
full-frame. `MeshStampGl` is the same class of object with a mesh instead of a quad. Copy its
`ensureGlInitialized` / `release` discipline verbatim, including the `degraded` latch.

### 1.5 The vertex shader (write it once, share it)

```glsl
#version 100
attribute vec2 aLocal;    // deformed unit-square position, D(u,v), in OBJECT-LOCAL space
attribute vec2 aUv;       // source texture coordinate, the UNdeformed (u,v)
uniform mat3 uHomography; // corner-pin (identity when the other agent's feature is off)
uniform mat3 uPlace;      // local → NDC: size/aspect/scale/rotation/centre, incl. y-flip
varying vec2 vUv;
void main() {
  vec3 h = uHomography * vec3(aLocal, 1.0);
  vec3 p = uPlace * h;                 // still NOT divided
  gl_Position = vec4(p.x, p.y, 0.0, p.z);
  vUv = aUv;
}
```

**Do not divide by `p.z` yourself.** Handing the rasteriser a real `w` makes it interpolate `vUv`
with correct 1/w weighting, which is what makes a perspective corner-pin sample correctly instead of
showing the classic diagonal seam across each quad. Dividing in the vertex shader and writing `w=1`
gives an affine-per-triangle approximation and the seam comes back. This is the single easiest thing
to get wrong here, and it is invisible until someone pins a corner hard.

Fragment shader for the stamp is trivially `gl_FragColor = texture2D(uImage, vUv) * uAlpha;` with
`GL_CLAMP_TO_EDGE` — no wrap, so a UV that lands outside 0..1 (it never should) smears the edge
rather than tiling.

**Mipmaps are mandatory, not an optimisation.** Call `glGenerateMipmap` once when the decoded photo
is uploaded, and use `GL_LINEAR_MIPMAP_LINEAR` for minification. Without them, any region of the mesh
that *compresses* the photo (a pinched corner, a fold) aliases into shimmering noise the moment it
animates. This is the difference between "looks great" and "looks like a cheap plugin".

### 1.6 Preview/export parity — designed in, not retrofitted

Three enforced invariants:

1. **One vertex authority.** `MeshWarp.tessellate(lattice, level, subdiv)` returns
   `float[] local, float[] uv, short[] indices`. Both renderers call *this* and nothing else.
   Precedent: `PinWarpStrip` is deliberately pure-Java for the same reason (its class note, `:29`).
2. **One shader source.** The stamp vertex/fragment strings live in one constant, in the same package
   as `FxGlSource`, for the reason `FxGlSource`'s own note gives at `:12–20` ("Two renderers
   assembling their own source is precisely how a preview starts lying").
3. **One placement authority.** `uPlace` is built from the *same* numbers `fxPipFor`
   (`overlay/TextOverlayLayer.java:1264`) and `ImageOverlayDraw.draw` (`:122`) already agree on —
   `animatedCenterX/Y`, `animatedSizeFraction`, `animatedScaleX/Y`, `animatedRotation`, the
   `CaptionAnimator.Transform` preset fold, and the y/rotation flip into GL space. Extract that
   arithmetic into `MeshWarp.placeMatrix(...)` so there is exactly one copy. Today there are two
   copies kept in lockstep by comments (`TextOverlayLayer.java:1193` explicitly says so); do not add
   a third.

Parity tests, in the tree's existing idiom:
- `tools/jvm-harness/MeshWarpTest.java` + `run-mesh.sh` — pins tessellation, refinement exactness,
  and `placeMatrix` against hand-computed values. Follows `PinWarpTest.java`.
- Extend `run-preview-parity.sh` / `run-frame-parity.sh` with a warped-image fixture.
- **A device A/B that measures, not eyeballs.** Dump a preview frame and the exported frame at the
  same timestamp; assert the four corner positions of the warped photo agree within 1 px. The note in
  `ImageBlendGlEffect.java:29–34` records what happens when this class of feature is verified by eye
  instead: a wrong conclusion and a needless revert.

---

## 2. Interpolation — what makes it look great

### 2.1 The candidates

| Scheme | Passes through the handles? | Smooth? | Cost per frame | Verdict |
|---|---|---|---|---|
| Piecewise **bilinear** over the lattice | yes | **no** — C0 only, visible crease along every interior lattice line | ~zero | Right for 2×2, wrong for 3×3+ |
| **Coons patch** | boundary only | yes | low | **Rejected** — a Coons patch derives the interior *from the boundary*. It structurally cannot express an interior bulge, which is the whole point of a 3×3+ grid. It is a boundary-curve tool, not a grid tool. |
| **Catmull–Rom tensor product** (uniform cubic, cardinal) | **yes** — interpolates every control point exactly | yes, C1 | 16 taps/axis per evaluated vertex, on ≤25 controls | **RECOMMENDED** |
| **Thin-plate spline / MLS** | yes | yes, C2, gorgeous | an `n×n` dense solve whenever any point moves; scattered-point, no lattice | Rejected for v1 — see below |

### 2.2 Why Catmull–Rom

- **It interpolates.** Direct manipulation demands that the picture goes *exactly* where the thumb
  put the dot. A B-spline approximates its control points and the image lags the handle; users read
  that as broken. Catmull–Rom passes through every control point by construction.
- **It is separable and local.** Evaluate once per axis; a moved point affects only the 4×4 cell
  neighbourhood. That means dragging one dot needs only a local re-tessellation, and a 25-point
  lattice costs the same per output vertex as a 9-point one.
- **It has linear precision.** This is the property that makes §3.4's "subdividing must not change
  the shape" claim true, and it is not incidental — it is the reason to choose this interpolant over,
  say, a uniform cubic B-spline.
- **The cost is CPU-side and tiny.** 289 tessellation vertices × 2 axes × 16 multiply-adds ≈ 9,000
  flops per frame per warped object. Sub-0.1 ms on any phone from the last decade. The GPU sees only
  the finished vertex buffer.

TPS/MLS is the right answer *if* the tool ever becomes free-scattered "puppet pins" (After Effects'
Puppet tool). It is the wrong answer for a lattice: it throws away the grid structure that makes
locality, refinement and keyframing tractable, and it needs a linear solve per pose. Note it as the
door for a future "Puppet" tool; do not build it now. (This is also the After Effects lesson the
options page records: Corner Pin and Puppet Pin are deliberately *separate* tools.)

### 2.3 Tessellation density and triangle counts

Tessellation is **decoupled from the lattice**. The lattice is what the user drags; the tessellation
is how finely we chop the picture to hide the maths.

| Lattice level | Control points | Tessellation | Vertices | Triangles | When |
|---|---|---|---|---|---|
| L1 — 2×2 | 4 | 1×1 quad | 4 | **2** | Corner-pin only / warp off. Exact: the map is a homography, and `w` handles it. |
| L2 — 3×3 | 9 | 16×16 | 289 | **512** | First "+ Finer" |
| L3 — 5×5 | 25 | 24×24 | 625 | **1,152** | Second "+ Finer" |

For scale: a mid-range 2020 phone GPU processes well over 100,000 triangles per frame at 60 fps.
1,152 triangles is **not a cost**. Every real cost in this feature is fill rate and bandwidth (§4),
and those are set by the frame size, not by the triangle count. Do not let anyone "optimise" the
tessellation down — that trades the one free thing for visible faceting.

Guard: adjacent tessellation levels must share edge sample positions, or a warped photo sitting next
to another at a different level shows a hairline seam. Since the tessellation is per-object and each
object stamps into its own frame-sized sheet, this cannot arise here — but state it so nobody later
"optimises" by sharing a stamp buffer *without* clearing between objects.

---

## 3. Data model

### 3.1 Where it lives

On `model/TextOverlayItem` (the class that already carries images, per its note at `:323`), as a new
nullable field:

```java
@Nullable private MeshWarpSpec warp;   // null == no warp, the state of all 19 existing projects
```

`MeshWarpSpec` is a small pure-Java class in `faditor/warp/`, self-serialising like
`CompositingSpec` (which is the in-tree precedent for a model class that owns its own JSON and reads
tolerantly).

### 3.2 The lattice

```java
public final class MeshWarpSpec {
    public static final int L1 = 1, L2 = 2, L3 = 3;   // 2x2, 3x3, 5x5
    int level;            // L1..L3
    float[] pts;          // (side*side*2) floats, row-major, OBJECT-LOCAL unit square
    @Nullable MeshPoseTrack track;   // null == static
}
```

- `side = (1 << level) + 1` → 2, 3, 5. One rule, no table.
- Coordinates are in the object's **local unit square**, `(0,0)` top-left to `(1,1)` bottom-right,
  *before* placement. Identity lattice is `pts[i] == (col/(side-1), row/(side-1))`.
- Local, not canvas-normalised, because the object's own move/scale/rotate keyframes must be able to
  animate *underneath* the warp without the warp fighting them. Store the bend, not the placement.
- Range: allow ±0.5 beyond the unit square (a point may be dragged to `-0.5 … 1.5`). Clamp there so a
  stray drag cannot send a vertex a thousand frames away — the same reasoning
  `KeyframeSet.POS_ABS` (`keyframe/KeyframeSet.java:59`) already applies to positions.

### 3.3 Keyframing — the part the prior review was right about

`KeyframeSet` is one float per named track (`keyframe/KeyframeTrack.java`). A 5×5 lattice is 50
floats. Putting 50 tracks into a `KeyframeSet` would technically *serialise* fine —
`KeyframeCodec.toJson/fromJson` (`keyframe/KeyframeCodec.java:36–75`) is a generic
`property → [{t,v,e}]` map and does not care about names. The wire format is not the problem.

The problems are the **UI** and the **undo**:
- `KeyframeDiamondControl` is per-property. 50 diamonds is not a feature, it is a punishment.
- One drag of one dot with 50 tracks armed would write 50 track edits. `PreviewHandlesOverlay`'s
  contract is explicitly one undo step per gesture (`commitPointDrag`, `:148`, and the class note at
  `:36`), and JoyRaptor's standing ruling is one press = one step.

**Design: `MeshPoseTrack` — a keyframe is a whole lattice.**

```java
public final class MeshPoseTrack {
    static final class Pose { long timeMs; float[] pts; Easing easing; boolean presetOwned; }
    final List<Pose> poses;          // sorted by timeMs
    float[] valueAt(long timeMs, float[] fallback);   // per-component lerp under the SAME Easing
}
```

- `valueAt` is component-wise interpolation of the two bracketing poses under
  `keyframe/Easing.java` — the identical easing enum and the identical hold-outside-range semantics
  as `KeyframeTrack.valueAt` (`:64–82`). Copy those semantics exactly; do not invent new ones.
- **One diamond** in the drawer, labelled "Shape", exactly as the options page promised
  ("one diamond covers all four pins at once… at the finer grid levels the diamond still covers the
  whole net — you never key one dot at a time, which is what makes it survivable").
- **One undo step** per gesture, taken for free by living inside `PointHandles.commitPointDrag`.
- `shiftAll(deltaMs)` mirroring `KeyframeSet.shiftAll` (`:139`), including keeping negative times
  rather than clamping, so trimming stays exactly reversible.
- Poses at different levels: **forbidden.** A track's poses all carry the same `level` as the spec.
  Changing level rewrites every pose through §3.4's refinement. This removes a whole family of
  "keyframe A is 9 points and keyframe B is 25" bugs before it can exist.

### 3.4 Refinement: 4 → 9 → 25 must not change the picture

**The rule.** To refine, place every new control point at the value the *current* deformation map
gives at that point's parameter location:

```
p'[i][j] = D_current( i/(side'-1), j/(side'-1) )
```

Old points land on themselves (the map interpolates them), so they do not move. New points land
exactly on the surface that is currently being drawn.

**4 → 9 is exact, provably.**
At level 1 the map is bilinear (2 control points per axis; a Catmull–Rom with two clamped controls
degenerates to the chord). Sampling a bilinear map at `{0, ½, 1}²` and re-fitting a 3×3 Catmull–Rom
lattice reproduces the original map exactly, because the tensor-product Catmull–Rom kernel has
**linear precision**: fed samples of a linear function it reproduces that linear function, and a
bilinear map is linear per axis. Zero error, not "small error". Pin it in `MeshWarpTest` by asserting
`D_before(u,v) == D_after(u,v)` to 1e-6 over a 65×65 parameter sweep.

**9 → 25 is exact to O(h⁴), which is sub-pixel — and then corrected to exact.**
The 3×3 map is a piecewise cubic; Catmull–Rom has quadratic precision, not cubic, so a plain resample
leaves a residual of order `h⁴ · |D''''|`. At 1080p with the largest deformation the UI permits this
is well under half a pixel — but "well under" is a claim, and this repo has been burned by claims.
So do it properly and cheaply:

```
p' = resample(D_current)                       // the O(h⁴) starting guess
repeat 3 times:
    for each control point (i,j):
        p'[i][j] += D_current(u_ij, v_ij) - D_new(u_ij, v_ij)
```

This is Gauss–Seidel against the interpolation operator, which is strictly diagonally dominant for
uniform Catmull–Rom (the kernel's centre weight is 1 at the knot and the neighbours contribute 0
there — so it converges in 2–3 sweeps). Cost: 25 points × 3 sweeps × one map evaluation ≈ 1,200
flops, **once, at the moment "+ Finer" is pressed.** Not per frame.
`MeshWarpTest` asserts max deviation < 1e-4 of the unit square (≈ 0.1 px at 1080p) over a 65×65 sweep.

**Coarsening (undo of "+ Finer") is lossy and must say so.** Going 25 → 9 cannot preserve a bend that
only the fine points express. Rule: "+ Finer" is a one-way button *within a gesture history* — the
step is undoable via the normal undo stack (which restores the whole spec), but there is no
"– Coarser" button. If one is ever added, it must confirm.

### 3.5 JSON round-trip

On the image overlay object, one new optional field:

```json
"warp": {
  "lvl": 2,
  "pts": [0,0, 0.5,-0.06, 1,0,  0,0.5, 0.5,0.5, 1,0.5,  0,1, 0.5,1.06, 1,1],
  "keys": [
    { "t": 0,    "e": "EASE_OUT", "pts": [ ... 18 floats ... ] },
    { "t": 1200, "e": "LINEAR",   "pts": [ ... 18 floats ... ] }
  ]
}
```

Tolerance rules — these are the rules that keep JoyRaptor's 19 projects working:

1. **Field absent → `warp = null`.** No behaviour change, no code path entered, nothing to break.
   Every existing project takes this branch.
2. **Write-side gate: omit the field entirely when the lattice is identity and there is no track.**
   So a project that opens the Shape tool, changes nothing, and saves writes a **byte-identical**
   file. Same discipline `KeyframeCodec.toJson` uses (null for an empty set, `:36`).
3. `lvl` outside 1..3 → clamp to the nearest supported level and re-derive `side`.
4. `pts` length ≠ `side*side*2` → **drop the warp, keep the object.** Log at warn. Never throw.
   Precedent: `KeyframeCodec.fromJson`'s per-track `catch (RuntimeException)` (`:70`) — "one bad
   track does not cost the others".
5. Any pose in `keys` whose `pts` length disagrees with `lvl` → drop that pose, keep the rest.
6. Unknown easing name → `Easing.fromName` already falls back; reuse it, do not re-implement.
7. A `warp` on a non-image overlay (text, slide) → ignored on read and dropped on write. v1 is images
   only; leaving stale data in the file for a future text-warp is fine, silently *rendering* it is not.

**File-size cost.** 50 floats ≈ 450 bytes of JSON per pose. A 10-pose animated 5×5 warp ≈ 4.5 KB per
object. Twenty such objects in one project ≈ 90 KB. Project files are already comfortably past that;
this is not a concern. Say so plainly rather than "optimising" it into a binary blob that no longer
diffs or repairs.

---

## 4. Cost — honestly

### 4.1 What is actually expensive

Not the triangles. Not the spline. **The extra frame-sized pass and the extra frame-sized texture,
per warped object.**

Per warped object, per frame:

| Item | 1080×1920 | 720×1280 |
|---|---|---|
| Stamp texture (RGBA8) | 8.29 MB | 3.69 MB |
| Stamp render pass (fill) | 2.07 Mpix | 0.92 Mpix |
| Composite pass (already exists today) | 2.07 Mpix | 0.92 Mpix |
| Vertex buffer upload (L3) | 625 verts × 16 B = **10 KB** | same |
| CPU: Catmull–Rom evaluation (L3) | ~9,000 flops, **< 0.1 ms** | same |

The composite pass is **not new** — a blended/effected image already runs it today
(`ImageBlendGlEffect`, or `drawPip` in preview). The genuinely new cost is **one clear + one geometry
fill of a frame-sized FBO**, plus the 8.29 MB of VRAM.

### 4.2 On JoyRaptor's Note 20 specifically

Galaxy Note 20 — either Exynos 990 (Mali-G77 MP11) or Snapdragon 865+ (Adreno 650) depending on
region. Both are 2020 flagships with roughly 8–13 Gpix/s theoretical fill and, more importantly,
~50 GB/s of shared LPDDR5 bandwidth that the display, the decoder and the encoder are also using.

Realistic, bandwidth-bound estimate for one extra 1080p RGBA pass (write 8.3 MB + read the photo
texture): **≈ 0.7–1.0 ms**. Round up and call it **1.5–2 ms per warped object per frame**, because
the FBO bind and the clear are not free and thermal throttling is real on this phone.

| Warped objects on screen | Added per frame | Verdict at 30 fps (33.3 ms budget) |
|---|---|---|
| 1 | ~2 ms | Invisible |
| 2 | ~4 ms | Invisible |
| 3 | ~6 ms | Fine |
| 5 | ~10 ms | Noticeable on a busy project; still 30 fps |
| 8 | ~16 ms | **Will drop frames** once anything else is happening |

VRAM: 8.29 MB × N. §7's guard makes this **one** shared 8.29 MB buffer regardless of N.

**Thermals matter more than the arithmetic.** The Note 20's sustained clocks after ~5 minutes of
scrubbing are materially below its burst clocks. Budget for the throttled number: assume the 5-object
row is the practical ceiling, not the 8-object row.

### 4.3 Export

Export runs the same passes single-file through the media3 effect chain, with no frame budget to hide
behind — every millisecond is added to wall-clock export time.

**≈ 2 ms/frame × 30 fps × 60 s = ~3.6 s of extra export time per minute of video, per warped object.**

A 3-minute video with two warped photos across the whole timeline: **~21 s slower**. If the warped
photos only appear for 10 seconds, the cost is only paid for those 10 seconds — the effect is emitted
per host clip, and `isVisibleAt` already gates the draw.

Set expectations in these terms, not as a percentage.

### 4.4 What costs nothing

- A project with no warp: **zero**. The field is absent, the spec is null, no FBO is created, no
  program is compiled, no effect is emitted. Byte-identical exports for all 19 existing projects.
- An image whose lattice is identity: same — treat identity as "no warp" at the gate, not just at the
  draw. Cheapest correct thing.
- Triangle count: irrelevant at these magnitudes, as established in §2.3.
- Keyframe evaluation: 50 component lerps per frame. Noise.

---

## 5. Interaction with the rest of the pipeline

### 5.1 Corner-pin / skew (the agent building `setPolyToPoly` right now)

**They are separate stages, and this is forced by maths, not preference.**

A 4-point `Matrix.setPolyToPoly` is a **projective homography** — straight lines stay straight, and
the interior compresses toward the far edge. A 2×2 bilinear mesh over the same four corners is a
**different map**: it also keeps the outline, but its interior is linearly interpolated, so a
"perspective" corner-pin done bilinearly looks flat and wrong. **The mesh is therefore NOT the 2×2
degenerate case of corner-pin.** Anyone who assumes it is will ship a corner-pin that stops looking
like perspective the moment warp is enabled.

**Composition order: mesh first (local), homography second.**

```
(u,v) in unit square
   → D(u,v)            mesh warp, object-local          ← this spec
   → H · [D, 1]        corner-pin homography            ← the other agent
   → P · [·]           placement: size, aspect, scale, rotate, centre
   → gl_Position with w = the homography's z, undivided
```

Reasons, in order of force:
1. **It is what the user means.** "Bend the picture, then tilt the bent picture away from me." The
   reverse ("tilt it, then bend the tilted thing") makes the bend handles move non-uniformly as the
   perspective changes, which is unusable.
2. **Perspective correctness comes free.** Doing `H` last means one `w` per vertex and the rasteriser
   interpolates UVs correctly. Doing `H` first would require baking the projective divide into the
   lattice, which loses it.
3. **The handles stay meaningful.** Mesh handles are drawn by projecting local points forward through
   `H` and `P`. Corner handles are the four `H` outputs. Two handle sets, one projection function,
   no ambiguity about which one a drag hits.

**Contract for the other agent:** publish `float[9] cornerPinMatrix(TextOverlayItem, long timeMs)`
returning a row-major 3×3 in the same **object-local unit square** this spec uses, identity when the
feature is off. That single method is the whole seam between the two features. If corner-pin ships
first as an `android.graphics.Matrix`, `Matrix.getValues()` gives exactly that array.

**Keep-square toggle** (the options page's skew mode) is a constraint on `H`'s four corners, not on
the mesh. Unaffected by anything here.

### 5.2 Masks — `MaskPathBuilder` / `MaskSdf`

Masks evaluate in **frame space**: `MaskSdf.packShapes(spec, frameW, frameH)`
(`FxPreviewTextureView.java:560`) packs frame-sized geometry, and `PIP_FRAGMENT` evaluates
`fxShapeSd(vFxUv, frame, …)` at the *screen* uv (`:207–213`). The stamp arrives at the composite as a
frame-sized texture sampled at `vFxUv`. Therefore:

**A mask cuts the warped result, in frame space. The mask does not bend with the picture.**

This is the correct and the expected behaviour ("mask off the top-left corner of the screen"), it
matches how a mask on a PiP already behaves, and it costs nothing. State it in the UI copy if anyone
asks for a mask that follows the bend — that would be a different feature (a mask in object-local
space), and it is cheap to add later by evaluating the SDF at `vUv` in the stamp shader instead. Do
not build both in v1.

Note the export Canvas path currently opens the mask *inside* the item's save/restore
(`ImageOverlayDraw.java:172–182`). When a warped image moves to the stamp, its mask must move to the
composite (frame space) so preview and export agree. **This is a real divergence trap**: a warped
image would be masked in local space on export and frame space in preview if this is missed.
Explicit slice-3 acceptance test.

### 5.3 Opacity, fades, entrance presets

Unchanged, and deliberately so. `animatedOpacity × CaptionAnimator.Transform.alpha` already lands as
`uPipAlpha` in preview and as the Canvas paint alpha in export. In the stamp architecture it becomes
the stamp shader's `uAlpha`. The preset's `scaleX/scaleY/dx/dy` fold into `uPlace`, exactly as
`fxPipFor` already folds them (`TextOverlayLayer.java:1255–1259`). Master fades ride
`OpacityExportEffect` downstream (`ExportManager.java:3381`) and never see the mesh.

`revealFrac` (MASK_WIPE) is currently `if (uv.x > uPipReveal) src.a = 0.0;`
(`FxPreviewTextureView.java:1700`) in *object* uv. In the stamp it moves into the stamp fragment
shader and tests `vUv.x` — which is the **undeformed** u, so the wipe travels across the *picture*,
not across the screen. That is the right reading and it is what the Canvas `clipRect` does today.

### 5.4 Blend modes, adjustment layers, chroma key, track mattes

All unchanged, and that is the entire point of §1.4. The stamp is handed to exactly the code that
handles a Canvas-rasterised image today:

- **Blend mode**: `blendPix(base.rgb, src.rgb, uPipBlend)` — preview `:224`; export
  `ImageBlendGlEffect.java:120`. Sees the warped pixels because they are in the stamp.
- **Adjustment layers**: appended after the image blend effects
  (`ExportManager.java:3296` → `:3347`); in preview they are rungs above the PiP in the same chain.
  Chain order is z order. They grade the warped picture.
- **Chroma key**: `fadKeyAlpha` on the **un-premultiplied source colour, before FX**
  (`ImageBlendGlEffect.java:111–117`, preview `:1701–1704`). Keep it there — the key must measure
  distance from the *source* colour. A warp only moves the pixel; it does not change its colour, so
  key-then-warp and warp-then-key are identical, which means we get to keep the existing order with
  no argument.
- **Per-object FX**: the fused pass is spliced into the composite shader
  (`ImageBlendGlEffect.fragmentFor`, `FxPreviewTextureView.pipFragment` at `:1683`). It runs on the
  stamp's colour at `vFxUv`. A `SAMPLER` card is still skipped on an object (`FxPreviewTier.java:71`)
  — warp does not change that, and must not be sold as changing it.
- **Track mattes**: PiP-only today (`ImageBlendGlEffect`'s note at `:35`). Unchanged.

### 5.5 The `+ Finer` UI, and the handles

`PreviewHandlesOverlay.PointHandles` (`overlay/PreviewHandlesOverlay.java:116–149`) is the substrate
and needs **no interface change**:

| `PointHandles` member | Mesh binding |
|---|---|
| `count()` | `side*side` (4 / 9 / 25) |
| `pointX/pointY(i)` | control point `i` projected through `D → H → P` into the video rect, normalised |
| `tetherTo(i)` | `-1` for every mesh point — they are all anchors, none are bezier handles |
| `guide()` | the lattice's row and column polylines, sampled along the *actual* Catmull–Rom curve (not straight chords — the interface note at `:129` says exactly why: "joining the anchors with straight lines would draw a shape the shader is not rendering") |
| `beginPointDrag(i)` | snapshot the whole `MeshWarpSpec` |
| `pointDragTo(i, nx, ny)` | inverse-project through `P⁻¹ → H⁻¹` into local space, clamp to ±0.5, write `pts[2i], pts[2i+1]`, invalidate the tessellation |
| `commitPointDrag()` | one undo step, one persist — already the contract |

`pointAt` is nearest-within-grab-radius (`:404`), which is what makes a 5×5 lattice usable at all when
dots crowd. The options page's loupe requirement is a `PreviewHandlesOverlay` drawing concern and is
independent of this spec.

**Corner points at L2/L3 are the same four points corner-pin owns.** Decide once and enforce: while
the Shape tool is in mesh mode, the four lattice corners are **mesh** points; corner-pin's handles are
hidden. Two tools claiming the same four dots is how a drag becomes ambiguous.

---

## 6. Build plan — riskiest unknown first

Each slice ships and is testable on its own.

### Slice 0 — Prove the wall (½ day, no product change)
Confirm on a device that a `Pip` cannot express a mesh, by attempting the cheap alternative first:
feed `drawPip` a frame-sized "stamp" texture with the rect map set to identity
(`uPipCentre = (0.5,0.5)`, `uPipHalf = (0.5,0.5)`, `uPipCos = 1`, `uPipSin = 0`) and check the
composite, blend and mask still land correctly. **This is the riskiest unknown**: if identity-mapping
the PiP rect breaks the mask (which is evaluated at `vFxUv`, so it should not) or the aspect
correction, the whole stamp architecture needs rethinking before a line of mesh code exists.
*Exit test:* a normal, unwarped image rendered through the identity stamp path is pixel-identical to
the same image through the existing rect path.

### Slice 1 — `MeshWarp` maths, headless (1 day)
`warp/MeshWarp.java` + `warp/MeshWarpSpec.java`, pure Java. Tessellation, Catmull–Rom, `placeMatrix`,
refinement + Gauss–Seidel correction. `tools/jvm-harness/MeshWarpTest.java` + `run-mesh.sh`.
*Exit test:* identity lattice reproduces the unit square to 1e-6; 4→9 exact; 9→25 within 1e-4;
`placeMatrix` matches hand-computed placements for rotation/scale/preset cases.
**Nothing user-visible ships. This is the file both renderers will depend on, so it goes first.**

### Slice 2 — The stamp, preview only, static warp (2 days)
`compositor/MeshStampGl.java` modelled on `GlPipFrameOverlay`. Wire `fxPipFor` to route a warped
image through it. Shape tool at L1 only (four corners, bilinear) so the maths is trivial and the
plumbing is what is under test. No keyframes, no export.
*Exit test:* dragging a corner in the editor bends the picture live; a blend mode set on it visibly
blends the bent version; an adjustment layer above it visibly grades the bent version.
**This is the slice that proves the owner's hard constraint.** Do not proceed past it until it is
demonstrated on the Note 20.

### Slice 3 — Export parity (1–2 days)
Route the same warped image through `ImageBlendGlEffect` with the stamp replacing
`ImageOverlayFrameOverlay`'s bitmap. Move the mask from `ImageOverlayDraw`'s local-space `beginMask`
to frame space (§5.2). Extend `run-preview-parity.sh`.
*Exit test:* the measured device A/B — preview frame vs exported frame, corner positions within 1 px,
at three warp poses. **Measured, not eyeballed.**

### Slice 4 — "+ Finer": L2 and L3 (2 days)
Catmull–Rom tessellation on the stamp, refinement wired to the button, guide curves in
`PointHandles.guide()`, mipmaps on the source texture.
*Exit test:* pressing "+ Finer" does not visibly change the picture (the §3.4 property, verified on
device against a screenshot diff, not just in the harness); a 5×5 bend has no visible creases.

### Slice 5 — Keyframing (2 days)
`MeshPoseTrack`, one "Shape" diamond, `shiftAll`, JSON `keys` array, undo integration.
*Exit test:* a two-pose warp animates smoothly; trimming the object and trimming back restores the
poses exactly; one drag = one undo step.

### Slice 6 — Compose with corner-pin (1 day, gated on the other agent landing)
Consume `cornerPinMatrix`, insert `H` between `D` and `P`, hide corner-pin handles in mesh mode.
*Exit test:* a perspective corner-pin plus a bend reads correctly and has no diagonal seam (the `w`
check from §1.5).

### Slice 7 — Guards and budget (1 day)
Shared stamp buffer, warped-object cap + user-visible note, identity fast-path, `degraded` latch.
See §7.

---

## 7. What could go wrong — the three that matter

### Risk 1 — Preview and export bend differently
**Why it is likely.** They are different renderers today with two hand-synced copies of the placement
arithmetic (`TextOverlayLayer.imageHeightPx/imageWidthPx` vs `ImageOverlayDraw.draw`, kept in step by
a comment: `TextOverlayLayer.java:1193`). Adding 50 more numbers to that is how a divergence becomes
permanent. The related trap is §5.2's mask-space change, which is silent and easy to miss.
**Guard.**
1. `MeshWarp` is the *only* producer of vertices, UVs and `placeMatrix`, and it is pure Java on the
   JVM harness classpath (the discipline `PinWarpStrip.java:29` and `FxGlSource.java:12–20` already
   establish).
2. The stamp shader strings exist once.
3. `run-mesh.sh` in CI, plus a warped fixture added to `run-preview-parity.sh`.
4. A device A/B that **measures corner pixel positions numerically**. `ImageBlendGlEffect.java:29–34`
   records what reading frames by eye cost last time: a wrong conclusion and a needless revert.

### Risk 2 — Pass and memory explosion at N warped objects
**Why it is likely.** Nothing in the design stops a user warping ten photos. Ten frame-sized FBOs is
83 MB of VRAM and ~20 ms/frame added on the Note 20 — a stutter that looks like the whole app broke,
not like "warp is expensive".
**Guard.**
1. **One shared stamp FBO, reused.** Render object A's mesh into it, composite, clear, render B.
   Memory is 8.29 MB **total**, not per object, forever. The pass cost still scales with N but the
   memory does not — and memory is what turns a slowdown into a crash.
2. **Identity fast-path at the gate.** An untouched lattice is treated as "no warp" before any GL
   object is created. Provably inert for the 19 existing projects, which is the same gate discipline
   `wantsGlExport` (`TextOverlayItem.java:753`) already uses.
3. **A cap with an honest message.** Beyond N=5 simultaneously warped objects, the preview shows the
   6th+ unwarped with a visible "too many warps to preview live — export is correct" note. Never
   silently render something the export will not produce; say which one is lying.
4. `degraded` latch copied from `GlPipFrameOverlay.java:52` — a failed FBO or compile falls back to
   the unwarped draw and logs once, never retries per frame.

### Risk 3 — The keyframe/undo model collapses under 50 floats
**Why it is likely.** The path of least resistance is 50 `KeyframeTrack`s. The wire format accepts it
(`KeyframeCodec` is a generic name→track map), so nothing *fails* — it just produces 50 diamonds in
the drawer, 50 undo steps per drag, and a keyframe UI nobody can use. It would ship and then need
unpicking.
**Guard.**
1. `MeshPoseTrack` is a **separate type** from `KeyframeSet`, so the 50-track shape is not merely
   discouraged, it is unrepresentable.
2. All poses in a track share the spec's `level` (§3.3) — mixed-arity poses cannot exist.
3. One diamond, wired to the whole spec, and one `commitPointDrag` per gesture.
4. Harness test: one simulated drag produces exactly one undo entry and one pose write.

**Honourable mentions** (real, but not top three): aliasing without mipmaps (§1.5 — fix is one call);
the perspective-divide seam if someone writes `w = 1` (§1.5); and the Note 20's sustained-clock
throttling making the 5-object ceiling nearer 3 in a long session (§4.2).

---

## 8. File manifest for the implementing agent

**New**
- `app/src/main/java/com/fadcam/ui/faditor/warp/MeshWarp.java` — pure Java. Tessellation,
  Catmull–Rom, `placeMatrix`, refinement.
- `app/src/main/java/com/fadcam/ui/faditor/warp/MeshWarpSpec.java` — model + self-serialising JSON.
- `app/src/main/java/com/fadcam/ui/faditor/warp/MeshPoseTrack.java` — whole-lattice keyframes.
- `app/src/main/java/com/fadcam/ui/faditor/warp/MeshStampGl.java` — FBO + program + draw. Modelled
  line-for-line on `export/GlPipFrameOverlay.java`; used by **both** renderers.
- `app/src/main/java/com/fadcam/ui/faditor/warp/MeshGlSource.java` — the two shader strings, once.
- `tools/jvm-harness/MeshWarpTest.java`, `tools/jvm-harness/run-mesh.sh`.

**Modified (small, gated edits only)**
- `model/TextOverlayItem.java` — nullable `warp` field, `hasWarp()`, and `wantsGlExport()` |= it.
- `project/ProjectStorage.java` — read/write the `warp` object, per §3.5's tolerance rules.
- `overlay/TextOverlayLayer.java` — `fxPipFor` routes a warped image through the stamp.
- `compositor/FxPreviewTextureView.java` — a stamp variant of `drawPip` with the rect map at identity.
- `export/ImageBlendGlEffect.java` — take the stamp texture instead of the Canvas bitmap when warped.
- `export/ImageOverlayDraw.java` — skip a warped image (it is drawn by the stamp), so it is not
  drawn twice. Same "filter it out of the canvas path" discipline
  `CompositeExportOverlay.filterTextOverlays` already uses for blended images.
- `overlay/PreviewHandlesOverlay.java` — **no interface change**; a new `PointHandles` implementation
  in the editor.
- `ui/faditor/tools/ObjectDrawer.java` — the "+ Finer" button and the single "Shape" diamond.

**Explicitly NOT modified**
`fx/FxRegistry.java`, `fx/FxCompiler.java`, `fx/FxEffectDef.java` — warp is not an effect (§1.3).
`export/AdjustmentLayerGlEffect.java`, `export/BlendModeGlEffect.java`, `model/BlendModes.java`,
`model/MaskSdf.java`, `model/ChromaKey.java` — the stamp is a drop-in for the bitmap they already
consume, which is the whole design.
