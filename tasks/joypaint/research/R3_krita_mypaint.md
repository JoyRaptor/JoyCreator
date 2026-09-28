# R3: Krita brush engines and libmypaint, what to learn and what to port

**For:** Joy Paint (the painting and 2D animation wing of Joy Creator, Android, GPL-3.0)
**Date:** 2026-09-28
**Method:** I read the source directly from shallow clones:
- Krita `master` @ `7588b7e5989d992fa9b4f5ab517fd3dbed178a3c` (2026-09-28), from the GitHub mirror of invent.kde.org/graphics/krita.
- libmypaint `master` @ `d5a88fbe6649d5ec776bc42ec8c1f4bb29d7fd7f` (2026-04-14), API 2.0.0-beta.
- mypaint-brushes `master`.

I also read a few doc pages. The clones were deleted afterwards.

**Legend**
- **[SRC]**: confirmed by reading source code (the file path is given).
- **[DOC]**: from official documentation or release notes.
- **[INF]**: my own inference, judgement or recommendation.

---

## 0. TL;DR

1. **Adopt MyPaint's model for dynamics.** Each brush parameter is a *base value plus per-input response curves* (`mypaint_mapping_calculate`). The whole brush fits in one small JSON document, the `.myb` file. That is the right shape for a portable "brush module". [SRC][INF]
2. **Adopt Krita's stroke pipeline.** The pieces are:
   - `KisPaintInformation` as the per-sample record;
   - `KisDistanceInformation` for spacing, with isotropic, elliptical (anisotropic) and timed (airbrush) dab placement;
   - `KisToolFreehandHelper` for smoothing: weighted Gaussian, bezier and stabilizer;
   - "wash" mode, which paints into a stroke buffer with **alpha-darken** so that overlapping dabs don't build up.

   Krita does this better than MyPaint. [SRC][INF]
3. **Krita's texture "Height" family is exactly the owner's dry-brush idea.** Paint coverage is the dab alpha, *raised by a pressure-driven strength*, minus a texture height, then clamped. "Hard Mix" is the hard-threshold limit case. Section 6 gives the unified formula and a better, parametric version for Joy Paint. [SRC]
4. **Keep two smudge models, because they behave differently:**
   - Krita "smearing" copies pixel patches from the previous dab position. It keeps texture and costs more.
   - MyPaint "smudge" carries a *single running colour* per dab, with a smudge length, and optionally mixes it spectrally. It is cheap and produces watercolour and gouache pickup. [SRC]
5. **libmypaint is ISC** and about 6.4k lines of C including headers. It depends only on json-c (for parsing brush files). It ports easily to Java/JS, or builds with the NDK. Krita as a whole is GPL-3.0, and almost all brush-engine files are GPL-2.0-or-later, some LGPL. Both are compatible with a GPL-3.0 app. [SRC]
6. **Avoid Krita's combinatorial explosion:**
   - 16 sensors × ~20 curve options × 5 curve-combine modes;
   - 16 texture modes;
   - more than 15 engines with overlapping features;
   - legacy flags.

   Also avoid MyPaint's 60+ cryptic settings. Expose 3–6 artist macro sliders per brush over a small internal parameter set. [INF]

---

## 1. Krita paintop plugins: overview and verdict

All engines live in `plugins/paintops/`. Shared option code is in `plugins/paintops/libpaintop/`. The core engine classes are in `libs/image/brushengine/`.

Approximate size (`.cpp` + `.h` lines) [SRC, counted]:

| Engine (dir) | Lines | What it does | Use to artists | For Joy Paint |
|---|---:|---|---|---|
| `defaultpaintops/brush`: **Pixel brush** `KisBrushOp` | 3,686 (with duplicate op) | Classic dab stamper: tip mask × colour, with dynamics, texture and masked brush | **Most used.** Nearly all default presets (inking, sketching, airbrush, textured) [INF] | **Core model to replicate** |
| `colorsmudge`: **Color Smudge** `KisColorSmudgeOp` | 3,640 | Smudge (smear/dull) plus colour rate, overlay mode, lightness "paint thickness" | Second most used, for painterly blending and oil looks [INF] | **Core; simplify** |
| `libpaintop` (shared) | 16,842 | Options, sensors, curves, texture, masking, dab cache | n/a | Concepts only |
| `mypaint`: **MyPaint** engine | 3,661 | Wraps libmypaint and reimplements its surface (`MyPaintSurface.cpp::drawDabImpl`) | Popular since 5.0 with MyPaint users [DOC][INF] | Proof that libmypaint embeds cleanly |
| `hairy`: **Bristle** | 2,036 | Simulated bristles: each bristle is a polyline, with ink depletion and shear | Niche (sumi-e) [INF] | Idea source for dry brush; don't port |
| `spray` | 3,415 | Scatters particles or shapes in a radius (uniform, gaussian, cluster), metaballs | Foliage and FX, niche | Skip. A scatter option on the dab engine covers it |
| `deform` | 1,665 | Liquify-like warp: grow, shrink, swirl, move, lens, colour deform | Niche (liquify tool is better) | Skip (Joy has mesh warp in Avatar Studio) |
| `sketch` | 994 | Harmony/"shaded" style: connects current point to history points within the tip mask, by probability | Fun, niche | Maybe later as an FX brush |
| `particle` | 1,026 | Particles attracted to the cursor with gravity, bilinear splats | Niche | Skip |
| `hatching` | 1,755 | Parallel and cross hatch lines clipped by the tip | Comic toning, niche | Skip (better as a fill/pattern tool) |
| `tangentnormal` | 1,015 | Paints normal-map RGB from tilt, direction or rotation | Game artists, niche | Skip. The tilt→vector math is a nice reference |
| `curvebrush` | 830 | Curves through history points | Niche | Skip |
| `roundmarker`: **Quick brush** | 789 | Fills swept circles directly (`fillCirclesDiff`). No dab cache or texture; size only | Fast inking and blocking | **Worth replicating**: a hard-edge swept-circle marker via SDF of capsules |
| `filterop` | 500 | Applies an image filter (blur and so on) within the dab | Blur and sharpen brushes | Later: "blur/soften" brush as a GPU filter dab |
| `gridbrush`, `experiment` (Shape) | 1,307 / 1,131 | Grid cells / fills the polygon of the stroke path | Niche | Skip. Shape fill = Joy's lasso-fill tool |

**Verdict [INF].** In practice artists use:
- Pixel brush, about 80%+ of presets;
- Color Smudge for blending;
- sometimes Quick Brush and MyPaint.

Everything else is a novelty. Joy Paint needs **one dab engine** with a small number of *composition modes* (normal/buildup, wash, smudge-smear, smudge-carry, erase). It also needs one special fast path, the capsule marker. Special engines don't earn their UI cost.

---

## 2. Brush tips (`libs/brush`, `libs/image/*mask_generator*`)

### 2.1 Auto brush (procedural) mask generators [SRC]

The base class is `KisMaskGenerator` (`libs/image/kis_base_mask_generator.{h,cpp}`). Its parameters are:
- `diameter`
- `ratio` (y/x)
- `fh`, `fv` (horizontal and vertical fade, which is softness as a fraction of the radius)
- `spikes`
- `antialiasEdges`
- type CIRCLE or RECTANGLE
- id Default, Soft or Gauss

**Krita mask convention:** `valueAt()` returns **255 = transparent, 0 = opaque**. It is an inverted mask.

**Circle "Default"** (`kis_circle_mask_generator.cpp::valueAt`):
```
xcoef = 2/width; ycoef = 2/height
xfadecoef = 2/(fh*width) ; yfadecoef = 2/(fv*height)      // fade = fraction of radius
transformedFade = fadecoef / max(0.01, softness)          // softness option scales fade
(xr, yr) = rotate/“spike-fold”(x, |y|)
n  = (xr*xcoef)^2 + (yr*ycoef)^2           // NOTE: squared normalized radius (norme = a²+b²)
if n > 1: return 255 (transparent)
if AA: xr = |xr|+1; yr = |yr|+1             // grow by 1px for AA
nf = (xr*txFade)^2 + (yr*tyFade)^2
if nf < 1: return 0 (opaque core)
return 255 * n*(nf-1)/(nf-n)                // smooth falloff between core and edge
```

**Rectangle "Default"** (`kis_rect_mask_generator.cpp`) uses the same idea per axis. It takes the max of the per-axis falloffs `fxnorm` and `fynorm`.

**Gaussian** (`kis_gauss_circle_mask_generator.cpp`) is an analytic "disk convolved with a Gaussian" profile using `erf`:
```
fade   = 1 - (fh+fv)/2
center = 2.5*(6761*fade - 10000)/(√2*6761*fade)
alphafactor = 255 / (2*erf(center))
distfactor  = √2*12500/(6761*fade*width/2)
d = sqrt(xr² + (yr*ycoef)²) * distfactor
alpha = alphafactor * (erf(d + center) - erf(d - center))   // value = 255 - alpha
```
Edges get extra antialiasing from `KisAntialiasingFadeMaker1D` (`kis_antialiasing_fade_maker.h`). It forces a linear ramp over the last 1 px of the radius.

**Soft / "curve"** (`kis_curve_circle_mask_generator.cpp`): the alpha is a user curve over the normalized radius, as a lookup table plus linear interpolation (`curveData`, `curveResolution`). This is the **softness curve**.

**Spikes** (`KisMaskGenerator::fixRotation`): folds the angle into one spike sector by repeatedly rotating by −2π/spikes until `atan2(y,x) < spikeAngle`. With spikes = 2 it is a no-op.

**Density and randomness** (`kis_brush_mask_scalar_applicator.h`):
```
for each pixel (supersampled 3x3 or 6x6 for tiny dabs):
    v = avg(valueAt(rotate(px - center)))          // rotation via cos/sin of dab angle
    rnd = randomness ? (1-randomness) + randomness*U(0,1) : 1
    a = (255 - v) * rnd
    if density < 1 and a > 0 and U(0,1) > density: a = 0   // “salt” dropout
```
There are also vectorized (xsimd) variants. The `shouldSupersample` flag is set for dabs under about 10 px.

**Sharpness** (`KisSharpnessOption::applyThreshold`) runs after the mask is built. Alpha above a threshold becomes opaque. Alpha below `(100-softness)%` of the threshold becomes transparent. This gives pixel-art or hard-ink edges, and the threshold is pressure-driveable.

**Port recommendation [INF].** On the GPU, one fragment shader does the whole tip. Compute `r = length(R(θ)·(p−c) / (w/2, h/2))`, then `alpha = profile(r)`. The profile is one of:
- (a) MyPaint two-segment hardness (see §8.4), the cheapest;
- (b) erf-Gaussian;
- (c) a 1D LUT texture holding the softness curve.

Antialias with `fwidth(r)`. Spikes and rectangles are just other SDFs. Density and randomness become hash noise per pixel **seeded per dab**, so replays are deterministic.

### 2.2 Predefined tips [SRC]
`libs/brush/`:
- `kis_gbr_brush` (GIMP `.gbr`, greyscale or colour)
- `kis_imagepipe_brush` (`.gih` animated/pipe tips, chosen by angle, pressure, random, incremental…, via `kis_pipebrush_parasite`)
- `kis_abr_brush*` (Photoshop `.abr` sampled tips only, not dynamics)
- `kis_png_brush`
- `kis_svg_brush` (vector tip, rasterized per scale)
- `kis_text_brush` (a text string as the tip, with pipe mode per letter)
- `KisColorfulBrush` (colour tips with brightness/contrast; "lightness map" and "gradient map" application)
- `kis_qimage_pyramid` (a mip pyramid for scaling tips smoothly)

Tip application modes are `ALPHAMASK`, `IMAGESTAMP`, `LIGHTNESSMAP` and `GRADIENTMAP`.

**Port recommendation [INF].**
- Support **greyscale PNG tips with mipmaps**, since GPU mips replace `KisQImagePyramid`.
- Add optional tip "variants" (a small atlas plus a random or angle picker, which is the `.gih` idea).
- Skip ABR, GIH parasite parsing and text brushes for v1.
- Lightness-map tips are a valuable later feature for painterly impasto (see §4.3).

### 2.3 Masked brush (dual brush) [SRC]
`plugins/paintops/libpaintop/KisMaskingBrushOption*`, `libs/ui/tool/strokes/KisMaskingBrushRenderer`, `KisMaskingBrushCompositeOp.h`.

A **second tip** is stroked into a separate alpha mask device *for the whole stroke*. Its size can follow the master brush (`useMasterSize`, `masterSizeCoeff`). The mask is then combined with the main stroke's alpha through one of the masking composite ops: MULT, DARKEN, OVERLAY, DODGE, BURN, LINEAR_*, HARD_MIX*, SUBTRACT, HEIGHT*. These are the same ops the texture option uses (§6).

It is essentially the Photoshop "Dual Brush". It works at stroke level, not dab level.

**[INF]** This is powerful for dry and broken media. However, a procedural stroke-space texture (§6.4) gets most of the effect at lower cost.

---

## 3. Dynamics: sensors → curves → options

### 3.1 Sensors [SRC]
`plugins/paintops/libpaintop/KisDynamicSensorIds.h` and `sensors/KisDynamicSensors.h`:

| Id | Value in [0,1] (or additive [-1,1]) | Source |
|---|---|---|
| `pressure` | `info.pressure()` | tablet |
| `pressurein` | `maxPressure` so far in the stroke ("pressure in"; it never decreases) | derived |
| `xtilt` / `ytilt` | `1 - |tilt|/60` (so 1 = upright) | tablet, degrees ±60 |
| `ascension` (Tilt direction) | `atan2(-xTilt, yTilt)` normalized to 0..1; **additive** (rotation-like) | derived |
| `declination` (Tilt elevation) | `acos(|t|/e)/(π/2)`, see `KisPaintInformation::tiltElevation` | derived |
| `rotation` | barrel rotation / 180, **additive** | tablet (Wacom Art Pen) |
| `tangentialpressure` | airbrush wheel | tablet |
| `speed` | smoothed `px/ms / maxAllowedSpeed(30)`, clamped to 1 (`KisPaintingInformationBuilder`, `KisSpeedSmoother`) | derived |
| `drawingangle` | `0.5 + angle/2π + offset`, **absolute rotation**; optional "lock angle" | derived |
| `fuzzy` (Fuzzy Dab) | new uniform random per dab, **additive** `2U-1` | RNG |
| `fuzzystroke` | one random value per stroke per option key | RNG |
| `fade` | `dabSeqNo / length` (clamped or periodic) | counter |
| `distance` | `strokeLength / length` (clamped or periodic) | derived |
| `time` | `t / length` (clamped or periodic) | clock |
| `perspective` | distance on the perspective grid | assistant |

Sensors come in three kinds:
- **scaling** (multiplied);
- **additive** (fuzzy, rotation, tilt direction; summed in [-1, 1]);
- **absolute rotation** (drawing angle, which sets the base angle).

### 3.2 Curves and how they combine [SRC]
Each sensor has a `KisCubicCurve` (`libs/image/kis_cubic_curve.{h,cpp}`, spline in `kis_cubic_curve_spline.h`).

- **Storage:** a string `"x0,y0;x1,y1[,is_corner];..."` (`KisCubicCurve::toString`). Presets (`.kpp` = PNG with an embedded XML settings blob) store it per sensor, plus `useSameCurve` / `commonCurve`.
- **Interpolation:** a *natural cubic spline* through the points (tridiagonal solve). The newer path uses an Eigen sparse solve to support "corner" points. The output is clamped to [0,1]. For speed it is sampled into a 256-entry `floatTransfer` table, and evaluated with `interpolateLinear` (lerp between table entries).
- **Evaluation** (`KisDynamicSensor::parameter`): `curve(value)`. Additive sensors are mapped through `(1+x)/2` before the curve and back after. Absolute-rotation sensors are wrapped with +0.5.
- **Combining sensors inside one option** (`KisCurveOption::computeValueComponents`):
  ```
  scaling = combine(curves of scaling sensors)   // curveMode: 0 multiply (default), 1 add, 2 max, 3 min, 4 difference
  additive = Σ additive sensors
  sizeLike  = clamp(strength * (absoluteOffset|1) * scaling * (1+additive)/2, min, max)
  rotationLike = wrap(2*offset + strength*(coeff*(2*scaling-1) + additive), -1, 1)
  ```
- **Global pressure curve:** `KisPaintingInformationBuilder` applies the user's tablet pressure curve (`KisCubicCurve`, 1025-sample LUT) *before* any brush sees the pressure.

### 3.3 Options (outputs) [SRC]
All are curve options (`KisCurveOption` subclasses in `libpaintop/`):
- Size
- Opacity
- Flow (`KisFlowOpacityOption`)
- Rotation (`KisRotationOption`, with "fan corners")
- Ratio
- Scatter (`KisScatterOption`: random jitter along or across the stroke, in units of dab diameter)
- Softness (scales fade)
- Spacing (`KisSpacingOption`)
- Mirror (random H/V flip)
- Sharpness (threshold)
- Darken (`KisDarkenOption`)
- Mix (colour source)
- Hue, Saturation, Value (`KisHSVOption`)
- Texture strength (`KisTextureOption` strength)
- Lightness strength
- Rate (airbrush)

In Color Smudge: Smudge length, Smudge radius, Color rate, Paint thickness.

**[INF] Lesson.** The model is expressive, but the UI is where Krita loses people. Every option is a checkbox, then a list of 16 sensor checkboxes, then a curve per sensor, a combine mode, a strength slider and min/max. MyPaint's model (§8.2) has equivalent power and is simpler to *store*. Neither is simple to *present*.

---

## 4. Color Smudge engine (Krita 5 "new engine")

Files: `plugins/paintops/colorsmudge/`. Merge request 756 rewrote it for 5.0 [DOC]. Strategies are chosen in `KisColorSmudgeOp::KisColorSmudgeOp` [SRC]:
- `KisColorSmudgeStrategyLightness`: when the tip is a LIGHTNESSMAP ("paint thickness" and heightmap).
- `KisColorSmudgeStrategyMask`: new engine with an alpha-mask tip.
- `KisColorSmudgeStrategyStamp`: colour image or gradient-map tips.
- `KisColorSmudgeStrategyMaskLegacy`: pre-5.0 behaviour.

### 4.1 Per-dab algorithm (new engine) [SRC]
From `kis_colorsmudgeop.cpp::paintAt` and `KisColorSmudgeStrategyBase::blendBrush`:
```
dstRect = rect of the current dab (subpixel disabled in smearing mode)
srcRect = dstRect translated by (lastDabCenter - thisDabCenter)   // where the brush WAS
if first dab: remember center, paint nothing.
colorRateOpacity = colorRate² * opacity
if DULLING:
    dullColor = weighted average of pixels under srcRect, weighted by the dab mask
                (KisColorSmudgeSampleUtils::WeightedSampleWrapper; smudgeRadius enlarges sample)
    blend = canvas(dstRect)  OVER  fill(dullColor) at 0.8*smudgeRate*opacity
else SMEARING:
    blend = canvas(dstRect); blend = blend  (COPY if smearAlpha else OVER)  canvas(srcRect) at smudgeRate*opacity
blend = blend  OVER/current-op  paintColor at colorRateOpacity
layer(dstRect) = COPY(blend) through selection = dab mask   // final painter opacity = 1
```

- **Smearing** drags the actual pixels (with their texture) forward by one dab step, so low spacing is needed.
- **Dulling** replaces the dab with one averaged colour, like picking up paint; higher spacing is fine.

Both descriptions are confirmed by the docs [DOC].

**Overlay mode** (`KisColorSmudgeStrategyWithOverlay`) reads from the **image projection** (all visible layers) through `KisOverlayPaintDeviceWrapper` and writes only to the current layer. So you can smudge on an empty layer over a lineart or underpainting stack [SRC][DOC].

### 4.2 Legacy vs new [SRC/DOC]
The legacy engine coupled colour rate with smudge length. The new one separates them, runs in a "precise" colour space (`preciseColorSpace()`, higher bit depth overlay), and adds lightness and gradient tips.

### 4.3 Lightness / "paint thickness" [SRC]
`KisColorSmudgeStrategyLightness` keeps a **separate heightmap device** plus a colour-only device.
- Each dab smears colour, and also paints the tip's lightness into the heightmap with opacity `lerp(smudgeRate-0.01, 1, thickness)`. In OVERWRITE mode the multiplier is 1.
- The final pixels are `modulateLightnessByGrayBrush(color, heightmap)`.

This is a cheap impasto: colour and relief are stored separately and relief shades the colour.

**[INF]** It is a good idea for a later "oil/gouache" brush in Joy. The pattern is to keep a per-layer height channel and shade at composite time.

---

## 5. Stroke pipeline

### 5.1 Data flow [SRC]
```
Pointer event
 → KisPaintingInformationBuilder (libs/ui/tool/kis_painting_information_builder.cpp)
      pressure = pressureCurveLUT(raw)   (1025 samples)
      speed    = KisSpeedSmoother → min(1, v/30)
      perspective, tilt, rotation, time
 → KisToolFreehandHelper::paint(info)      smoothing / stabilizer (§5.4)
 → paintBezierSegment / paintLine / paintAt  (on the stroke job queue)
 → KisPaintOp::paintBezierCurve → recursive midpoint subdivision until flat (BEZIER_FLATNESS_THRESHOLD), then paintLine
 → KisPaintOpUtils::paintLine:
      while (t = distanceInfo.getNextPointPosition(pi.pos, end, pi.time, endTime)) >= 0:
          pi = KisPaintInformation::mix(t, pi, pi2)     // lerp all sensors; rotation along shortest arc
          [fan corners: fill gaps on sharp turns]
          pi.paintAt(op, distanceInfo)  → op.paintAt(info) returns KisSpacingInformation for the NEXT dab
      if needsSpacingUpdate/TimingUpdate: op.updateSpacing / updateTiming
```

**`KisPaintInformation`** (`libs/image/brushengine/kis_paint_information.{h,cc}`) holds:
- pos, pressure, xTilt, yTilt, rotation, tangentialPressure, perspective, time, speed;
- canvas rotation/mirroring;
- per-dab and per-stroke random sources;
- a `DirectionHistoryInfo` registered with the distance info, giving lastPosition, lastAngle, dab sequence number, total stroke length and max pressure.

`drawingAngle()` is the direction from the last *dab* position to this one. Fuzzy sensors use the random sources attached to the info.

### 5.2 Spacing [SRC]
`libs/image/kis_distance_information.cpp`:
- **Isotropic:** accumulate distance. The next dab is at `t = (spacing - accum)/segmentLength` when it falls inside the segment.
- **Anisotropic (elliptical):** spacing is `(sx, sy)` in the dab's rotated frame. Solve for `t` in `(x+t·dx)²/sx² + (y+t·dy)²/sy² = 1`, a quadratic (`getNextPointPositionAnisotropic`). Flat or elliptical tips then get dabs spaced correctly along and across their axis.
- **Timed (airbrush):** `KisTimingInformation`. The next dab is due after `interval / rate` ms even if the pen is still. The earlier of the distance and time triggers wins.
- **Effective spacing** (`kis_paintop_utils.cpp::effectiveSpacing`): either `spacing × dab size`, or **auto spacing** `coeff × sqrt(size)` (`calcAutoSpacing`, linear below 1 px). Large brushes therefore get relatively *denser* dabs.
- Spacing is recomputed from each dab's own size and rotation, which `paintAt` returns.

### 5.3 Mirror / symmetry [SRC]
`KisPainter::mirrorDab`, `mirrorRect`, `calculateAllMirroredPoints/Rects`. The dab is rendered **once**, then flipped in pixel space and blitted at the mirrored position (`KisBrushOp::addMirroringJobs`; H, V or both). MyPaint instead has `mypaint-symmetry.c`, with vertical, horizontal, rotational and snowflake modes, which **re-draws each dab** at the transformed positions (`draw_dab` loop in `mypaint-tiled-surface.c`).

**[INF]** On the GPU, emit N instanced dabs with transformed centres and angles. That is trivially cheap and supports radial symmetry.

### 5.4 Smoothing (`libs/ui/tool/kis_tool_freehand_helper.cpp`) [SRC]
Types (`KisSmoothingOptions::SmoothingType`): `NO_SMOOTHING`, `SIMPLE_SMOOTHING`, `WEIGHTED_SMOOTHING`, `STABILIZER`, `PIXEL_PERFECT`.

- **Simple:** no position filtering, but consecutive samples are joined by a **cubic Bezier** (`paintBezierSegment`). Tangents are `(p_i - p_{i-2})/Δt`. Control points aim at the intersection of the tangent lines, capped at half the chord, with coeff 0.8 scaled by the velocity similarity.

- **Weighted** (the default in many setups; a Gaussian kernel over *arc length*, not time):
  ```
  sigma = lerp(distMax, distMin, speed)/3        // effectiveSmoothnessDistance; optional zoom scaling
  walk history backwards accumulating distanceSum (+ pressure-drop penalty = tailAggressiveness*40*(1-p)*Δp*3σ)
  w_i = exp(-distanceSum²/(2σ²)); stop when w_last/w_i > 100
  pos = Σ w_i p_i / Σ w_i ; optionally pressure likewise
  ```
  The "tail aggressiveness" term shortens the kernel when pressure drops, so line ends don't lag or curl. The result is then joined with the Bezier as above.

- **Stabilizer:**
  - A timer polls every `stabilizerSampleSize` ms (and also on each event).
  - A FIFO holds N = `max(3, smoothnessDistance)` samples, seeded with the first point.
  - The output is the **uniform mean of the queue** (position only, or all sensors when `stabilizeSensors`).
  - **Delay distance R** (a rope/"lazy nozzle", zoom-corrected): if the pen is within R of the last painted point, don't paint and freeze the queue.
  - `finishStabilizedCurve` flushes the queue at pen-up so the line reaches the cursor.
  - Optional "delayed paint" helper.

- **Pixel perfect:** snaps to pixel centres and drops "L-corner" pixels (tentative-pixel logic) for 1-px pixel-art lines.

**MyPaint equivalents** [SRC]:
- `slow_tracking`: exponential smoothing of x, y with time constant, `fac = 1 - exp(-100·dt/T)`.
- `slow_tracking_per_dab`: the same per dab.
- `tracking_noise`: jitter, with input skipping to make it frequency independent.

**[INF]** Offer three: **Off**, **Smooth** (Krita weighted + Bezier, with a single strength slider driving both distMin and distMax, plus tail aggressiveness fixed ≈0.15), and **Stabilize** (rope/delay distance + mean queue, with finish-to-cursor). Samsung S Pen gives ~120–240 Hz samples via `MotionEvent.getHistorical*`. Feed *every* historical sample to the smoother.

### 5.5 Opacity vs flow, buildup vs wash [SRC]
`KisPaintingModeOptionData` has `BUILDUP` and `WASH`.

- **Wash:** dabs go into a temporary stroke device with `COMPOSITE_ALPHA_DARKEN` (`libs/pigment/compositeops/KoCompositeOpAlphaDarken.h`). The alpha formula, with flow f and opacity O:
  ```
  srcA = mask*opacity
  fullFlowA = (O > dstA) ? lerp(dstA, O, mask) : dstA      // alpha rises toward O but never exceeds it
  zeroFlowA = union(srcA, dstA) (Hard) | dstA (Creamy)
  dstA' = lerp(zeroFlowA, fullFlowA, flow)
  ```
  The stroke then composites onto the layer once. Result: **self-overlap doesn't darken past opacity**, but separate strokes do layer. This is what makes ink, markers and glazes look right.
- **Buildup:** each dab composites straight onto the layer with normal OVER, so it accumulates.

**[INF] This is the single most important compositing concept to port.** Every Joy Paint stroke should paint into a per-stroke scratch texture. It then commits to the layer at pen-up (and is shown live via a composite preview). That also gives undo and animation-frame isolation for free.

---

## 6. Texture / pattern option and "height" texturing (the dry-brush question)

### 6.1 Where the texture is applied [SRC]
`KisDabCacheUtils::postProcessDab`:
1. `sharpness.applyThreshold(dab)`
2. `texture.apply(dab, dabTopLeft, info)`

`KisTextureOption::apply` (`libpaintop/kis_texture_option.cpp`, LGPL-2.0+):
- Takes the pattern patch at `(dabTopLeft % patternSize) - offset`. **The texture is anchored to canvas coordinates** ("paper grain"). Random offset is chosen once per stroke (`perStrokeRandomSource`), so it does not move with the dab.
- Computes `strength = m_strengthOption.apply(info)`. This is a curve option, so it can be driven by **pressure or tilt**.
- Combines per pixel: `dabAlpha' = CompositeFunction(src = textureValue, dst = dabAlpha)`, using `KisMaskingBrushCompositeOp` (`libs/ui/tool/strokes/KisMaskingBrushCompositeOp.h`).

**Texture preprocessing** (`KisTextureMaskInfo::recalculateMask`), per texel:
```
g = (11R+16G+5B)/32/255 * a + (1-a)          // transparent = white
g = clamp((g - brightness - 0.5)*contrast + 0.5)
if invert: g = 1-g
g = neutral-point piecewise-linear remap (0..n → 0..0.5, n..1 → 0.5..1)
cutoff policy: outside [cutoffL, cutoffR] → force transparent (policy 1) or opaque (policy 2)
```
The texture is prescaled (`scale × LoD`) and cached (`KisTextureMaskInfoCache`).

Special modes:
- `LIGHTNESS` modulates *colour* lightness (`fillGrayBrushWithColorAndLightnessWithStrength`).
- `GRADIENT` maps the texture grey to the current gradient and mixes by strength.

### 6.2 The alpha formulas (d = dab alpha, t = processed texture value, s = strength; all in [0,1]) [SRC]

| Mode | "Hard" (soft texturing off) | "Soft texturing" on |
|---|---|---|
| Multiply | `t·d·s` (strength variant) / `t·d` | uses inverted strength |
| Subtract | `max(0, d − (t + (1−s)))` | `max(0, d − s·t)` |
| Darken | `min(t, d·s)` | soft variant |
| Overlay / Dodge / Burn / Linear Dodge/Burn | Photoshop blend of t onto `d·s` | soft variants |
| **Hard Mix (PS)** | `1 if t + s·d > 1 else 0`: **a hard threshold** | `mul(hardmix(union(t,1−s), d), union(d,s))` |
| **Hard Mix Softer (PS)** | `clamp(3·(s·d) − 2·(1−t))`: **a linear-ramp threshold** (antialiased) | `clamp(3d − 2(1−s·t))` |
| **Height** (s' = 0.99 s) | `clamp(d/(1−s') − t − (1−s'))` | `clamp(d/(1−s') − s'·t)` |
| **Linear Height** | `m = d/(1−s') − (1−s')`; `clamp(max(m·(1−t), m − t))` | soft analogue |
| Height (PS) | `clamp(10s·d − t)` | `clamp(d + 9s·d − s·t)` |
| Linear Height (PS) | `m = 10s·d`; `clamp(max(m(1−t), m − t))` | soft analogue |

Docs [DOC]:
- Height is "similar to subtract but with a higher range … allows full coverage with one stroke".
- Hard Mix gives "very hard (aliased)" edges.
- Linear Height is "height combined with multiply for softer transitions".

### 6.3 What "Height" means mathematically [SRC-derived, INF interpretation]
Treat **t as a height field**. Here t is the depth of paper valleys: after subtraction, *low t* receives paint first. Use Invert to swap peaks and valleys.

Treat `L(d,s) = d/(1−s) − (1−s)` as a **"paint level"**. It rises with the dab's own alpha d (centre > edge) and very steeply with strength s. The output is

> **coverage = clamp(L − t, 0, 1)**: the paint fills everywhere the paint level exceeds the paper height, with a *unit-slope* (1-texture-unit wide) soft edge.

- At s → 0, `L = d − 1 ≤ 0`, so there is no paint.
- At s = 0.5 in the dab centre (d = 1), `L = 1.5`: texels with t < 0.5 are fully covered and t in [0.5, 1.5] partially.
- At s → 1, `d/(1−s)` explodes, so the whole dab is covered.

With `s = curve(pressure)` you get: **light pressure paints only on the "high points" of the grain, heavy pressure fills in.** That is dry brush on rough paper.

**Hard Mix** is the zero-width-edge limit with a linear level: `coverage = step(s·d − (1−t))`.

**Hard Mix Softer** is a finite-slope ramp: `clamp(3(s·d − (1−t)) + (1−t))`, roughly slope 3 around the same threshold.

**Linear Height** takes the max with a multiply term, so low-strength areas keep a faint wash instead of dropping to zero.

**Cutoff** clips the *texture's* range, so some texels never or always paint. It is not a stroke-level threshold.

**Owner's proposal:** "threshold a procedural cloud texture against a pressure/tilt-driven gradient to get clumpy dry-brush marks". This **is** Height / Hard-Mix-Softer, generalized. A clean, parametric version for Joy Paint [INF]:

```glsl
// per fragment, inside the dab
float d  = tipAlpha(uvDab);                          // 0..1 tip profile
float n  = grain(texSpace(p));                       // fbm/cloud or bitmap, 0..1, after brightness/contrast/invert
float lvl = level(d, s);                             // e.g. lvl = d * (0.25 + 1.5*s)  or Krita's d/(1-s)-(1-s)
float w  = mix(wMin, wMax, softness) + fwidth(n);    // edge width in texture units (0 → hard mix)
float cov = clamp((lvl - n) / w + 0.5, 0.0, 1.0);    // or smoothstep(n - w/2, n + w/2, lvl)
cov = mix(d, cov, texStrength);                      // blend untextured vs textured (Krita "soft texturing" analogue)
```

Where:
- `s` = strength from pressure (and possibly tilt elevation). Low tilt means the side of the brush, so it is drier and broader.
- `w` gives Hard Mix at 0, Hard Mix Softer at about 1/3, and Height at 1.
- `level()` is a *gradient across the dab*. Make it anisotropic for dry-brush streaks, for example `lvl = d_across^γ · s`, so the dab's edges run dry before the centre.
- **Tilt drives the grain scale or anisotropy**: stretch the noise along the tilt direction (`texSpace = R(tiltDir)·diag(1, 1+k·(1−elevation))·p`).

### 6.4 Canvas-anchored vs stroke-anchored texture [SRC + INF]
Krita's texture is canvas-anchored (`offset = dabTopLeft % patternSize`). MyPaint's `gridmap_x/y` inputs are also canvas-anchored, on a 256 px grid.

That is correct for **paper grain**, where the same valleys stay empty across strokes. It is *not* how bristle streaks work. Those need **stroke-space coordinates**: `u = arc length s`, `v = signed offset across the stroke / radius`.

Krita reaches bristle streaks only with the Hairy engine or with tips plus rotation by drawing angle.

**Recommendation [INF].** The brush module declares `texture.space ∈ {canvas, stroke, dab}` and blends two grains:
- `canvas` = paper;
- `stroke` = bristle streaks, 1D noise in v stretched along u, with slow "ink depletion" by `u`.

Together they give convincing dry brush with no simulation. Pass `u, v` per dab instance, where `u` is the cumulative distance from the spacing engine and `v` comes from the dab's local frame aligned with the drawing angle.

---

## 7. Other Krita components worth knowing [SRC]

- **Dab cache and precision** (`kis_dab_cache.cpp`, `kis_precision_option.cpp`): reuses the last dab when size, angle and subpixel offset are nearly the same. Subpixel precision levels (1–5). A GPU renderer makes this unnecessary.
- **Multithreaded dab rendering** (`KisDabRenderingQueue/Executor/Job`, `KisBrushOp::doAsynchronousUpdate`): dabs render in parallel and are blitted in rects split by `splitDabsIntoRects`. The update period adapts between 10 and 100 ms. This is a CPU-era complexity; skip it.
- **Level of detail** (`KisLodTransform::lodToScale`, used throughout): Krita "Instant Preview" paints at 1/2ⁿ resolution first for responsiveness, then recomputes at full resolution. **[INF]** A GPU design at phone canvas sizes (≤4k²) shouldn't need it. Keep it in mind for very large canvases.
- **Per-stroke random sources** (`KisPerStrokeRandomSource`, `KisRandomSource`): **seeded RNG** makes strokes reproducible. That matters for Joy's animation onion-skin replay and for web↔Android parity tests.

---

## 8. libmypaint (ISC)

Repository: https://github.com/mypaint/libmypaint (API 2.0.0-beta).

Sizes [SRC, `wc -l`]:

| File | Lines |
|---|---:|
| `mypaint-brush.c` | 1,698 |
| `mypaint-tiled-surface.c` | 974 |
| `brushmodes.c` | 626 |
| `helpers.c` | 608 |
| `operationqueue.c` | 255 |
| `mypaint-mapping.c` | 196 |
| `mypaint-symmetry.c` | 161 |
| rest | small |

Total: **6,364 lines of `.c`/`.h`**, plus `brushsettings.json`, which generates the settings and inputs enums via `generate.py`. It also vendors `fastapprox/` headers.

Dependencies: **json-c** (only to parse `.myb` in `mypaint_brush_from_string`). GLib and GObject-Introspection are optional.

### 8.1 Inputs [SRC]
From `brushsettings.json` and `update_states_and_setting_values`:

| Input | Range | Computed as |
|---|---|---|
| `pressure` | 0..1+ | `pressure·exp(pressure_gain_log)` |
| `speed1` / `speed2` | ~0..4 | `log(γ + v_slow)·m + q`. v is `|Δpos|/Δt × viewzoom`, low-passed with `speed{1,2}_slowness`. γ = `exp(speed_gamma)`. m and q are fixed so that v = 45 maps to 0.5 with slope 0.015 (`settings_base_values_have_changed`) |
| `random` | 0..1 | new value per dab |
| `stroke` | 0..1 | grows by `normDist·exp(−stroke_duration_log)`, holds or wraps with `stroke_holdtime`, restarts at `stroke_threshold` pressure |
| `direction` | 0..180 | low-passed direction vector with 180° ambiguity folded; view-rotation corrected |
| `direction_angle` | 0..360 | full direction |
| `tilt_declination` | 0..90 | `90 − 60·|tilt|` (90 = upright) |
| `tilt_ascension` | −180..180 | `atan2(−xtilt, ytilt)`, view-rotation corrected |
| `tilt_declinationx/y` | ±90 | `60·xtilt`, `60·ytilt` |
| `attack_angle` | ±180 | angle between tilt ascension and stroke direction (+90) |
| `custom` | ±10 | low-passed `custom_input` setting (a user-programmable feedback register) |
| `gridmap_x/y` | 0..256 | canvas position mod a 256 px grid, scalable |
| `viewzoom` | log | makes the brush size independent of zoom |
| `brush_radius` | log | base radius, so dynamics can depend on size |
| `barrel_rotation` | ±180 | pen twist |

### 8.2 Settings × inputs model [SRC]
Each of the ~64 settings is a `MyPaintMapping`: a **base value** plus, **for each input, a piecewise-linear curve of up to 64 points** (`mypaint-mapping.c`). The curves are *additive*:

```c
value = base_value;
for each input j with points: value += piecewise_linear(points_j, input[j]);   // additive offsets
```

Radius is stored as `radius_logarithmic`, so an additive offset is a multiplicative scale. Opacity has both `opaque` (additive) and `opaque_multiply` (a multiplier, usually pressure). This keeps the model purely additive, yet it can express multiplication where it matters. It is elegant.

**Setting families** (all in `brushsettings.json`):
- **Opacity:** `opaque`, `opaque_multiply`, `opaque_linearize`
- **Shape:** `radius_logarithmic`, `radius_by_random`, `hardness`, `softness`, `anti_aliasing`, `elliptical_dab_ratio`, `elliptical_dab_angle`, `snap_to_pixel`
- **Dab placement:** `dabs_per_basic_radius`, `dabs_per_actual_radius`, `dabs_per_second`
- **Position:** `offset_by_random`, `offset_by_speed(+slowness)`, `offset_x/y`, `offset_angle*` (directional offsets, with `flip` alternating sides), `offset_multiplier`, `slow_tracking`, `slow_tracking_per_dab`, `tracking_noise`
- **Colour:** `color_h/s/v`, `change_color_h/l/hsl_s/v/hsv_s`, `restore_color`, `colorize`, `posterize(_num)`, `lock_alpha`, `eraser`
- **Smudge:** `smudge`, `smudge_length`, `smudge_length_log`, `smudge_radius_log`, `smudge_transparency`, `smudge_bucket`, `paint_mode` (spectral)
- **Input helpers:** `speed1/2_slowness`, `speed1/2_gamma`, `direction_filter`, `stroke_*`, `custom_input(_slowness)`, `gridmap_scale*`, `pressure_gain_log`

### 8.3 Stroke to dabs [SRC]
`mypaint_brush_stroke_to(brush, surface, x, y, pressure, xtilt, ytilt, dtime, viewzoom, viewrotation, barrel_rotation, linear)`:

```
convert tilt → ascension/declination; apply tracking_noise (with input skipping) and slow_tracking (exp low-pass)
if dtime > 5 s or reset: reset states; return
dabs_moved = PARTIAL_DABS; dabs_todo = count_dabs_to(x, y, dtime)
while dabs_moved + dabs_todo >= 1:
    step = (dabs_moved>0 ? 1 - dabs_moved : 1); frac = step / dabs_todo
    update_states_and_setting_values(Δx=frac·(x−X), Δp=frac·(p−P), Δt=frac·dt_left, …)   // “simulation step”
    FLIP *= -1
    prepare_and_draw_dab()
    random_input = rng()
    dt_left -= step_dt; dabs_todo = count_dabs_to(x, y, dt_left)
update_states(...remainder...); PARTIAL_DABS = dabs_todo      // carry the fractional dab
```

`count_dabs_to` gives the number of dabs:

```
dabs = dist/actual_radius·dabs_per_actual_radius + dist/base_radius·dabs_per_basic_radius + dt·dabs_per_second
```

Here `dist` is measured in the *elliptical* metric when the ratio is above 1. That is MyPaint's anisotropic spacing.

**Key idea [INF]:** settings are evaluated **per dab from interpolated inputs**, and stateful filters (speed, direction, stroke, custom) advance by per-dab steps. The results don't depend on event rate, which is essential for identical results on web and Android.

### 8.4 Dab preparation and rendering [SRC]
`prepare_and_draw_dab`:

- **Opacity linearization** (worth porting). The target opacity α per *pixel* after N overlapping dabs, where `N ≈ (dabs_per_actual + dabs_per_basic)·2` blended by `opaque_linearize`, gives:
  ```
  α_dab = 1 − (1 − α)^(1/N)
  ```
- Adds offsets (directional, speed, gaussian random) and radius jitter with alpha compensation `(r0/r)²`.
- Updates the smudge colour (§8.5) and mixes it into the brush colour.
- Applies eraser, HSV/HSL colour dynamics (with linear↔gamma handling).
- **Anti-aliasing**: if `radius·(1−hardness) < anti_aliasing px`, it solves for a new hardness and radius that keep the *optical radius* but guarantee a minimum fade width.
- Snaps to pixel.
- Calls `surface->draw_dab(x, y, radius, rgb, opaque, hardness, softness, alpha_eraser, ratio, angle, lock_alpha, colorize, posterize, posterize_num, paint)`.

**Dab mask** (`mypaint-tiled-surface.c::render_dab_mask`):
```
rr = ((dy·cs − dx·sn)·ratio)² + (dy·sn + dx·cs)²) / r²        // squared elliptical radius, 0..1
opa = rr <= hardness ? (1−soft)·(1 − rr·(1/hardness − 1))       // segment 1
                     : (1−soft)·hardness/(1−hardness)·(1 − rr) // segment 2 (to 0 at rr=1)
```
For radius < 3 there is a special antialiased rr (`calculate_rr_antialiased`). The mask is RLE-encoded as uint16 in 1.15 fixed point.

- **Surface:** 64×64 tiles, RGBA uint16 **premultiplied** in 1.15 fixed point. `draw_dab` enqueues the op per touched tile (`operationqueue.c`). Tiles are processed on `end_atomic`, which is OpenMP-parallel.
- **Blend** (`brushmodes.c::draw_dab_pixels_BlendMode_Normal`): premultiplied OVER, `rgba = opa·color + (1−opa)·rgba`.
  - `_and_Eraser` multiplies top alpha by `color_a`, so smudging toward transparency erases.
  - `_LockAlpha`, `_Color` (colorize), `_Posterize`.
  - `_Paint` variants do **spectral (WGM) pigment mixing**.
- **`get_color`**: a weighted average of pixels under a soft circle (hardness 0.5), randomly subsampled for large radii. It flushes pending dab ops first.
- **Symmetry**: the dab is re-drawn per symmetry transform.

### 8.5 Smudge model [SRC]
`update_smudge_color` and `apply_smudge`:
```
bucket = smudge_buckets[round(smudge_bucket)] (256 buckets; default single state)
update_factor = max(0.01, smudge_length)
// resample canvas only when "recentness" decays below (0.5·update_factor)^smudge_length_log
if resample: (r,g,b,a) = get_color(x, y, radius·exp(smudge_radius_log))
              if smudge_transparency gate fails: skip dab
smudge = legacy ? update_factor·smudge + (1−update_factor)·a·sample        // premultiplied running average
                : mix_colors(smudge, sample, update_factor, paint_mode)     // spectral WGM
brush_color = mix(smudge, brush_color, smudge_value) ;  eraser_target_alpha = (1−smudge)+smudge·smudge.a
```

So MyPaint smudge is a **"loaded brush" with one colour of memory**, decaying with smudge length. It produces watercolour and gouache pickup, and "blending by painting". It costs one `get_color` every few dabs, not per-pixel patch copies.

**Spectral mixing** (`helpers.c`): `rgb_to_spectral` projects to 10 spectral bands using precomputed primaries. The mix is a **weighted geometric mean** per band, `spec = a^w · b^(1−w)`, then converted back through a 3×10 matrix. It is small and fast. Blue + yellow gives green.

### 8.6 `.myb` format [SRC]
It is JSON with version 3 (mypaint-brushes 2.x):
```json
{ "version": 3, "comment": "MyPaint brush file", "parent_brush_name": "...", "group": "", "description": "", "notes": "",
  "settings": {
    "radius_logarithmic": { "base_value": 2.6, "inputs": { "pressure": [[0.0,-0.653],[1.0,0.98]] } },
    "opaque_multiply":    { "base_value": 0.0, "inputs": { "pressure": [[0,0],[0.0586,0.604],[1,1]] } },
    "smudge": { "base_value": 0.9, "inputs": {} }, ... } }
```
The example is `mypaint-brushes/brushes/deevad/watercolor_glazing.myb`: 10:1 elliptical dabs with random angle, ratio reduced by speed, smudge 0.9, and pressure-driven opacity and radius.

The mypaint-brushes repo has 196 `.myb` files. By `Licenses.dep5`, the brushes are **CC0-1.0** per author directory, and the repo scaffolding is GPL-2+.

### 8.7 Existing ports and integrations
- Krita's MyPaint paintop (`plugins/paintops/mypaint/`) implements `MyPaintSurface` on `KisPaintDevice` (`KisMyPaintSurface::drawDabImpl`, `getColorImpl`). This proves a host can supply its own surface with just two callbacks. [SRC]
- **libmypaint.js** (vitalipe): an Emscripten build, ISC. Hosts implement `drawDab()` and `getColor()` callbacks, with a Painter class on a canvas. [DOC] https://github.com/vitalipe/libmypaint.js
- Older JS port by Yap Cheah Shen, mentioned in the MyPaint wiki. [DOC]
- OpenToonz, Tahoma2D and Pencil2D (experimental) integrate libmypaint on desktop. [DOC]
- Android: only an experimental OpenGL ES port attempt (libmypaint issue #103, unanswered, with performance complaints on heavy brushes). **No maintained Android port exists.** [DOC]

### 8.8 Porting effort [INF]
- **C via NDK:** straightforward. Drop json-c (parse JSON in Java/Kotlin and set mappings through the API) or vendor it. You must provide `MyPaintSurface` (`draw_dab`, `get_color`) over your GPU canvas.
- **The mismatch** is that libmypaint is CPU-centric: `get_color` needs pixel readback, and dabs are CPU tile writes. On a GPU canvas:
  - `draw_dab` → append an instance to a dab batch (a trivial shader);
  - `get_color` → a GPU reduction plus readback, which stalls. Mitigate by reading back the previous frame asynchronously, or by computing smudge sampling on the GPU (keep "smudge colour" in a 1×1 float texture updated by a compute or fragment pass).
- **Hand port to Java and JS** of the *brush logic only* (`mypaint-brush.c` state machine, mappings, helpers: about 2.5k LOC). This is the cleanest route for a shared "module" spec. The JS prototype and Android then run the same algorithm in the same float order. Render dabs with **your own** shader in both.

---

## 9. Licensing [SRC + DOC; not legal advice]

- **Krita:** top-level `COPYING` is GPL-3.0. `README.md`: "Krita as a whole is licensed under the GNU Public License, Version 3. Individual files may have a different, but compatible license."
  - SPDX scan of `plugins/paintops`, `libs/brush`, `libs/image`, `libs/ui/tool`: **1175 × GPL-2.0-or-later**, 49 × GPL-3.0-or-later (mostly `.ui` forms and a few headers), 47 × LGPL-2.0-or-later (e.g. `kis_texture_option.cpp`), 8 × LGPL-2.1-or-later, 1 × CC0. **No GPL-2.0-only files found** in these dirs.
  - All are compatible with GPL-3.0. Joy Creator is GPL-3.0 and a FadCam fork, so porting Krita algorithms or code (translated to Java, GLSL or JS) is allowed. The derived files must keep the upstream copyright notices, e.g. "Based on Krita's `kis_distance_information.cpp`, © Krita developers, GPL-2.0-or-later" in file headers and NOTICE.
  - **Caveat [INF]:** the *desktop web prototype* must also be GPL-3 if it contains ported Krita code and you distribute it (serving JS to users counts). Brush **module data** (JSON parameters) is not code and can use its own license (recommend CC0 or CC-BY). If modules can contain executable snippets (GLSL, JS) derived from Krita, those snippets are GPL too.
- **libmypaint:** ISC (permissive; `COPYING` © 2008-2011 Martin Renold and contributors). It is GPL-compatible, and shipping it inside a GPL-3 app is fine. Keep the notice.
- **MyPaint the application** is GPL-2+, not ISC. Only libmypaint is ISC.
- **mypaint-brushes:** `.myb` files CC0-1.0; the repo is GPL-2+. Bundling the brushes is fine.
- **Krita's bundled presets and patterns:** licenses vary by resource. Verify per file before shipping any of them. [INF]
- **Spectral mixing IP [INF]:** MyPaint's WGM 10-band approach is ISC. Avoid "Mixbox" (CC BY-NC, non-commercial) unless licensed. Spectral.js (MIT) is another permissive option. Verify both before use.

---

## 10. Design lessons for Joy Paint's brush engine

### 10.1 What to adopt [INF, grounded in the above]

1. **One dab engine, instanced on the GPU.** It is data-driven. Per dab it takes: `(pos, radius, ratio, angle, opacity, flow, color, hardness/softness or profile, texture params, u/v stroke coords, seed)`.
2. **Dynamics = MyPaint mappings, with Krita's clarity.** Each output parameter has a base value plus **a list of (input, curve)** pairs, each with an explicit combine op:
   - `add` in log domain for size (MyPaint style);
   - `multiply` for opacity and flow (Krita default);
   - `add` for angle.

   Curves are point lists (≤8 points), monotone cubic (Fritsch–Carlson) or linear. Evaluate them through a 64–256 entry LUT so JS and Java produce the same numbers.
3. **Inputs (v1, stylus-first):**
   - pressure (after the global per-device pressure curve);
   - tilt elevation (declination);
   - tilt direction (ascension);
   - attack angle (tilt vs stroke direction; great for chisel and calligraphy);
   - barrel rotation (S Pen doesn't report it; Wacom EMR on Android sometimes does via `AXIS_ORIENTATION`, so treat it as optional);
   - speed (MyPaint's log-mapped, low-passed version);
   - direction;
   - stroke distance (normalized, for tapers and "ink depletion");
   - random per dab;
   - random per stroke.

   That is 10, versus Krita's 16 and MyPaint's 18. **Skip:** perspective, gridmap, custom, fade (distance covers it), time (only for airbrush), viewzoom (handle zoom-invariance in the engine).
4. **Spacing:** Krita's elliptical `getNextPointPositionAnisotropic` plus a timed fallback for airbrush. Spacing is expressed as MyPaint "dabs per radius" (clearer to artists as *density*). Carry fractional dabs between events. Advance stateful filters per dab (MyPaint's simulation step) so results don't depend on sample rate.
5. **Opacity linearization** (MyPaint §8.4): opacity means the same thing whatever the spacing.
6. **Stroke buffer + wash (alpha-darken)** vs buildup (§5.5). Commit per stroke.
7. **Grain = parametric height threshold** (§6.3), with `space: canvas | stroke`. It covers Krita's multiply, subtract, height, linear-height and hard-mix modes with **three numbers** (level gain, edge width, strength) instead of 16 enums.
8. **Two smudge flavours:**
   - "Carry" (MyPaint smudge colour with smudge length, optional spectral mix): cheap, for watercolour and gouache.
   - "Smear" (Krita smearing: copy the previous dab's pixel patch from a ping-pong texture): for oil and blending.

   Plus "Overlay" sampling (sample from the composite of visible layers).
9. **Smoothing:** Krita weighted Gaussian-by-arclength + Bezier joins, and the stabilizer with delay distance + finish-to-cursor. Use every `MotionEvent` historical sample and androidx `MotionEventPredictor` for display latency. Never *commit* predicted points.
10. **Deterministic RNG** seeded per stroke, with a documented float pipeline, gives cross-platform golden-image tests (web prototype vs Android).

### 10.2 Proposed "brush module" shape [INF]
```json
{
  "format": "joypaint.brush/1",
  "id": "ink.pen", "name": "Ink Pen", "license": "CC0-1.0",
  "tip": { "shape": "ellipse", "profile": { "type": "hardness", "hardness": 0.95 }, "ratio": 1.0, "aa_px": 1.0 },
  "spacing": { "dabs_per_radius": 4.0, "timed_hz": 0 },
  "compose": { "mode": "wash", "opacity_linearize": 0.9 },
  "params": {
    "radius":  { "base": 6.0, "domain": "log", "inputs": { "pressure": [[0,-1.2],[1,0]] } },
    "opacity": { "base": 1.0, "combine": "multiply", "inputs": { "pressure": [[0,0.6],[1,1]] } },
    "angle":   { "base": 0,   "inputs": { "tilt_direction": "identity" } },
    "ratio":   { "base": 1.0, "inputs": { "tilt_elevation": [[0,3],[1,1]] } }
  },
  "grain": null,
  "smudge": null,
  "macros": [ { "label": "Size", "drives": ["params.radius.base"] }, { "label": "Taper", "drives": ["params.radius.inputs.pressure"] } ],
  "kernel": null
}
```
A `kernel` is an optional GLSL function name from a **whitelisted library in the app**, not arbitrary code. That keeps modules data-only and license-clean.

### 10.3 The five target brushes mapped to mechanisms [INF]

| Brush | Mechanism |
|---|---|
| **Inking** (pen, brush pen) | Hard ellipse tip, wash mode, pressure → log radius, tilt → ratio and angle, smoothing on, AA 1 px, opacity linearize. Optional capsule "marker" fast path (Krita Quick Brush idea: SDF of the swept circle between consecutive samples, so no spacing artefacts at any speed). |
| **Wash / watercolour** | Soft profile, wash mode with low opacity, MyPaint-style smudge "carry" (length about 0.5–0.9), spectral mix, canvas grain via linear-height at low strength. Edge darkening (fringe) as a stroke-commit post-pass: alpha gradient magnitude → darken. Krita's "WaterC Fringe" presets fake this with textures. |
| **Fill** (flat, gouache) | Large, nearly hard tip, buildup or wash at full opacity, low smudge carry, pressure → opacity. Plus a separate lasso or flood-fill tool (not a brush). |
| **Smudge / nudge** | Krita smearing (patch copy from the previous dab) at dense spacing; smudge rate from pressure; smear alpha option. "Nudge" = smearing with colour rate 0 and a small radius. Overlay (all-layers) toggle. |
| **Variable texture** (dry brush, chalk) | Height-threshold grain (§6.3). Level driven by pressure, edge width by softness, grain scale and anisotropy by tilt. Canvas grain plus stroke-space streaks, depletion by stroke distance. |

### 10.4 Complexity to avoid [INF]
- **Per-option sensor matrices** in the UI. Krita shows 16 sensors × N options, each with a curve editor, combine mode, strength, min and max. Hide it behind an "Advanced" editor used by *brush authors* (the desktop web lab), not painters.
- **Many engines with overlapping features.** Krita has 15+ engines; bristle, spray, sketch, particle and curve are rarely used. Implement their *effects* as parameters (scatter, stroke-space grain) where valuable.
- **16 texture blend modes:** replace them with the three-number threshold model.
- **Legacy compatibility flags** (Krita's legacy smudge, `useNewEngine`; MyPaint's legacy vs spectral smudge). Version the module format from day one and migrate data, not code paths.
- **MyPaint's 60+ setting names** (`offset_angle_2_asc_view`…) and inputs that exist only for power users (custom, gridmap).
- **CPU tile queues, dab caches, multithreaded dab executors and LoD previews** are artefacts of CPU rendering. A GPU instanced-dab renderer with a per-stroke texture avoids them.
- **ABR, GIH and text brushes** in v1.

### 10.5 Risks [INF]
1. **Smudge on the GPU needs read-after-write within a stroke.** Smear reads pixels the previous dab just wrote. You need ping-pong render targets or `EXT_shader_framebuffer_fetch` (widely available on Mali and Adreno, but verify on target devices). Batch dabs per frame only when they don't overlap. Otherwise serialize, or accept 1-dab-per-draw for smudge brushes. There is a performance risk at dense spacing on large brushes.
2. **`get_color` readback stalls** if libmypaint is embedded literally. Keep the smudge state on the GPU.
3. **Precision:** premultiplied RGBA8 bands and loses tiny-opacity accumulation (watercolour glazes). Use RGBA16F stroke and layer textures (supported on GLES 3.x; check that `EXT_color_buffer_half_float` is renderable on target GPUs).
4. **Cross-platform determinism:** JS doubles vs Java float vs GLSL mediump. Specify `highp` in dab shaders, do CPU dynamics in float64 on both sides (or float32 consistently), and use the same RNG (e.g. PCG32 or xorshift) and LUT curves.
5. **Stylus data variance:** S Pen tilt is available on recent Galaxy devices, but ranges and calibration vary, and barrel rotation is usually absent. Offer a global per-device pressure curve (Krita style) and graceful fallbacks, e.g. tilt missing → use drawing direction for angle.
6. **Licensing hygiene:** track which files are Krita-derived (GPL notices) and which are libmypaint-derived (ISC notice). Keep brush modules data-only, and state the licenses of any bundled brushes and grains.
7. **Scope creep:** the temptation to replicate Krita's option panel. Commit to macro sliders for painters.

---

## 11. Key pseudo-code to port (condensed)

**Dab emission (Krita elliptical spacing + MyPaint per-dab simulation):**
```
onSample(s):                         // s: pos, p, tiltX, tiltY, rot, t
  s = smooth(s)                      // weighted gaussian / stabilizer
  seg = (prev, s)
  loop:
    t = nextDabT(seg, accum, spacingXY(prevDab), angle(prevDab), timedInterval)
    if t < 0: break
    di = lerpInputs(prev, s, t)      // pressure, tilt, time; shortest-arc for angles
    advanceFilters(di, step)         // speed low-pass, direction low-pass, strokeDist += step
    P  = evalParams(module, inputs(di))   // base + curves
    P.opacity = 1 - (1 - P.opacity)^(1/N_overlap)
    emitDab(P, u=strokeDist, seed=rng.next())
    prev = di
```

**Weighted smoothing:** see §5.4. **Height grain:** see §6.3. **Alpha-darken wash:** see §5.5. **MyPaint smudge carry:** see §8.5. **Tip profile:** see §2.1 / §8.4.

---

## Sources

**Source code (read directly)**
- Krita (GitHub mirror of https://invent.kde.org/graphics/krita), commit `7588b7e5`: https://github.com/KDE/krita
  - `plugins/paintops/defaultpaintops/brush/kis_brushop.cpp`
  - `plugins/paintops/colorsmudge/kis_colorsmudgeop.cpp`, `KisColorSmudgeStrategyBase.cpp`, `KisColorSmudgeStrategyWithOverlay.cpp`, `KisColorSmudgeStrategyMask.cpp`, `KisColorSmudgeStrategyLightness.cpp`
  - `plugins/paintops/libpaintop/KisCurveOption.cpp`, `KisDynamicSensorIds.h`, `sensors/*.cpp|h`, `kis_texture_option.cpp`, `KisTextureMaskInfo.cpp`, `KisTextureOptionData.h`, `KisDabCacheUtils.cpp`, `KisSharpnessOption.cpp`, `KisScatterOption.cpp`, `KisFlowOpacityOption.cpp`, `KisMaskingBrushOptionProperties.cpp`, `KisPaintingModeOptionData.h`
  - `plugins/paintops/{hairy/hairy_brush.cpp, sketch/kis_sketch_paintop.cpp, roundmarker/kis_roundmarkerop.cpp, tangentnormal/KisTangentTiltOption.cpp, deform/deform_brush.cpp, particle/particle_brush.cpp, mypaint/MyPaintSurface.cpp, mypaint/MyPaintPaintOp.cpp}`
  - `libs/image/brushengine/{kis_paint_information.cc, kis_paintop.cc, kis_paintop_utils.h, kis_paintop_utils.cpp, KisStrokeSpeedMeasurer.cpp}`
  - `libs/image/{kis_distance_information.cpp, kis_cubic_curve.cpp, kis_cubic_curve_spline.h, kis_base_mask_generator.cpp, kis_circle_mask_generator.cpp, kis_gauss_circle_mask_generator.cpp, kis_curve_circle_mask_generator.cpp, kis_rect_mask_generator.cpp, kis_antialiasing_fade_maker.h, kis_brush_mask_scalar_applicator.h}`
  - `libs/ui/tool/{kis_tool_freehand_helper.cpp, kis_painting_information_builder.cpp, kis_smoothing_options.h/.cpp}`, `libs/ui/tool/strokes/{KisMaskingBrushCompositeOp.h, KisMaskingBrushCompositeOpFactory.cpp}`
  - `libs/pigment/compositeops/{KoCompositeOpAlphaDarken.h, KoAlphaDarkenParamsWrapper.h, KoCompositeOpFunctions.h}`
  - `COPYING`, `README.md` (license), `LICENSES/`
- libmypaint, commit `d5a88fbe`: https://github.com/mypaint/libmypaint
  - `mypaint-brush.c`, `mypaint-mapping.c`, `mypaint-tiled-surface.c`, `brushmodes.c`, `helpers.c`, `brushsettings.json`, `COPYING`, `README.md`, `configure.ac`
- mypaint-brushes: https://github.com/mypaint/mypaint-brushes (`brushes/deevad/watercolor_glazing.myb`, `Licenses.dep5`, `Licenses.md`)

**Documentation / web**
- Krita Manual, Texture settings: https://docs.krita.org/en/reference_manual/brushes/brush_settings/texture.html
- Krita Manual, Color Smudge engine: https://docs.krita.org/en/reference_manual/brushes/brush_engines/color_smudge_engine.html
- Krita 5.0 release notes: https://krita.org/en/release-notes/krita-5-0-release-notes/
- New colorsmudge engine MR !756: https://invent.kde.org/graphics/krita/-/merge_requests/756
- libmypaint.js (Emscripten port, ISC): https://github.com/vitalipe/libmypaint.js
- libmypaint wiki, Using Brushlib: https://github.com/mypaint/libmypaint/wiki/Using-Brushlib
- libmypaint issue #103 (Android port attempt): https://github.com/mypaint/libmypaint/issues/103
- OpenToonz libmypaint integration: https://github.com/opentoonz/opentoonz/pull/1124 ; Tahoma2D: https://github.com/tahoma2d/tahoma2d/issues/554 ; Pencil2D: https://discuss.pencil2d.org/t/implementing-libmypaint/930
