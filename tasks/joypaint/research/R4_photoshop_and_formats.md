# R4: The Photoshop brush engine, brush-pack formats, and the owner's tip model

Research report for Joy Paint (Joy Creator's painting and 2D animation wing). Written 2026-09-28.

**How claims are tagged**

| Tag | Meaning |
|---|---|
| **[C]** | Confirmed. Read in source code or official documentation (file path or URL given). |
| **[O]** | Observed. A descriptor key or layout that at least one open-source reverse-engineered parser reads from real files. Two or more agreeing independent parsers are noted as **[O2]**. The key name is confirmed; its meaning may still be inferred. |
| **[I]** | Inferred. My reasoning, community tutorials, or behaviour nobody has published. Check it against Photoshop before relying on it. |

Adobe's help pages (helpx.adobe.com) returned HTTP 403 to this environment, and so did web.archive.org. Adobe's own statements are therefore cited through search snippets, Adobe's Substance 3D Painter compatibility page (experienceleague.adobe.com, which loaded), and descriptor key names that the parsers read from real files.

---

## 0. Executive summary

* **The Photoshop engine is a stamp ("dab") engine.** A tip mask (computed ellipse, sampled bitmap, or one of three special tip families) is placed along the stroke at a spacing given as a percentage of the diameter. Every scalar is set per dab as `base × control(sensor) × (1 − jitter·rand)`, clamped at a minimum. Texture is a grayscale pattern combined with the dab or stroke alpha through a blend mode, with a "depth". In the Height modes (and with pressure-controlled depth) this amounts to **thresholding the pattern against a fill level driven by pressure**. That is the owner's cloud-threshold idea, so it is sound.
* **ABR is the format worth importing first.** The tip bitmaps (`samp`) and patterns (`patt`) are easy to read. The dynamics (`desc`) are a standard Photoshop Action Descriptor tree, which Adobe *does* document generically, although the brush key schema is undocumented. Several MIT-licensed parsers exist, including one in JS (**ag-psd**) and one in Dart (**abrkit**) that read essentially every panel. Procreate, Clip Studio, Affinity, ArtRage, Krita and GIMP all import ABR, so it is the de facto interchange format.
* **Procreate `.brush`/`.brushset` comes second.** It is a zip holding a binary plist (NSKeyedArchiver) plus `Shape.png`/`Grain.png`. A GPL-3.0 reference converter exists (freyalupen's Procreate-to-Krita). All the key names are known, but many value scales are not, and many brushes reference Procreate's *built-in* shapes and grains, which are not in the file and cannot legally be shipped.
* **MyPaint `.myb` (JSON, CC0 content)** and **Krita `.kpp`/`.bundle` (PNG+XML)** are open, easy to read, and have openly licensed content you can bundle. GIMP `.gbr`/`.gih`/`.vbr` are trivial. Clip Studio `.sut` is readable (SQLite) apart from its proprietary tip-bitmap container. Infinite Painter `.prbr`, ArtRage, Concepts and ibisPaint are closed and not worth attempting.
* **Verdict on the owner's tip model.** It covers everything a Photoshop *computed* tip can do, plus shapes Photoshop can only fake with bitmaps (square, rounded square, trapezoid, triangle). There is one catch: a corner-rounding slider on a stretched square gives a **capsule, not an ellipse**, so it does not reproduce Photoshop roundness below 100% unless the corner control is a superellipse exponent. Its procedural texture pipeline reproduces the Texture panel's "Height/threshold" behaviour. It is missing:
  * image tips and multi-image tips
  * a canvas-locked (paper) grain as distinct from a dab-locked, rotating grain
  * the opacity/flow split and a stroke buffer (needed for opacity, wet edges and dual brush)
  * a dual (mask) brush
  * count and both-axes scatter
  * speed, fade and initial-direction inputs
  * saturation and foreground/background jitter
  * colour pickup (smudge, mixer, wet mix)
  * bristle and erodible tips

  Section C lists the nine smallest additions that close these gaps.

---

## A. The Photoshop brush engine, parameter by parameter

### A.1 Pipeline (what happens per stroke and per dab)

```
input events (x, y, pressure, tiltX, tiltY, rotation, wheel, t)
  → Smoothing (stabiliser; tool option, not part of the brush tip)            [C key names, I math]
  → path resampling at Spacing% × diameter (or event-driven if Spacing off)  [C UI, I exact rule]
  → for each step:
       repeat Count times (Scattering):
          compute dynamic size / angle / roundness / flip        (Shape Dynamics)
          compute offset                                          (Scattering)
          rasterise tip mask (computed / sampled / bristle / erodible / airbrush)
          × Texture (if Texture Each Tip) using mode + depth(pressure) 
          × Dual Brush mask (secondary tip stream, blend mode)
          × Noise (edge noise on soft areas)
          colour = f(FG, BG, hue/sat/bri jitter, purity)          (Color Dynamics)
          deposit with Flow (+ flow jitter/control)               (Transfer)
  → stroke buffer capped at Opacity (+ opacity jitter/control); Wet Edges remap;
    Texture (if NOT each tip) applied to the whole stroke; composite with tool blend mode
```

The ordering above is **[I]**. Adobe does not publish it. It agrees with Photoshop's documented behaviour: opacity is a per-stroke cap and flow is per-dab accumulation; Texture Each Tip changes whether texture is applied per dab; Wet Edges acts on the stroke. It also matches Krita's documented "Photoshop-compatible" implementation of texture and masking brushes.

### A.2 ABR descriptor keys (for reference throughout)

These keys are read from real `.abr` files by ag-psd `src/abr.ts` (MIT), abrkit `lib/src/codec/abr_descriptor_mapper.dart` (MIT) and abr-to-krita `abr_convert/semantic.py` (MIT). They are **[O2]** unless marked otherwise.

| Panel | Descriptor keys |
|---|---|
| Preset | `Nm  ` name; `Brsh` tip object (class `computedBrush` / `sampledBrush` / `dBrush` bristle / `dTips` erodible and airbrush); `Spcn` spacing; `useBrushSize`; `interpretation` [meaning unknown] |
| Tip (computed) | `Dmtr` diameter px, `Hrdn` %, `Angl` deg, `Rndn` %, `Spcn` %, `Intr` (spacing on/off; ag-psd and abrkit call it "spacingOn"), `flipX`, `flipY` |
| Tip (sampled) | `sampledData` (UUID into `samp`), `Dmtr`, `Angl`, `Rndn`, `Spcn`, `Intr`, `flipX`, `flipY` |
| Tip (bristle `dBrush`) | `Shp ` (0–9: round point/blunt/curve/angle/fan, flat point/blunt/curve/angle/fan), `Dmtr`, `Angl`, `Dnst` (Bristles), `Lngt` (Length), `thickness`, `stiffness`, `clumping`, `physics`, `Spcn` |
| Tip (erodible/airbrush `dTips`) | `dtipsType`, `Shp ` (0 point, 1 flat, 2 round, 3 square, 4 triangle, 5 custom; brushkit found that `Shp `=5 under `dTips` is the *airbrush* tip), `dtipsLengthRatio`, `dtipsHardness` (Softness), `dtipsGridSize`, `dtipsErodibleTipHeightMap` (raw height-map bytes for custom tips), `dtipsAirbrushCutoffAngle`, `…Granularity`, `…Streakiness`, `…SplatSize`, `…SplatCount` [O, abrkit only] |
| Shape Dynamics | `useTipDynamics`, `szVr` (size), `minimumDiameter`, `tiltScale`, `angleDynamics`, `roundnessDynamics`, `minimumRoundness`, `flipX`/`flipY` (jitter), `brushProjection` |
| Scattering | `useScatter`, `scatterDynamics`, `bothAxes`, `Cnt ` (count), `countDynamics` |
| Texture | `useTexture`, `Txtr{Idnt (pattern UUID), Nm  }`, `InvT`, `textureScale`, `textureBrightness`, `textureContrast`, `textureBlendMode`, `textureDepth`, `minimumDepth`, `textureDepthDynamics`, `TxtC` (Texture Each Tip), `protectTexture` |
| Dual Brush | `dualBrush{useDualBrush, Brsh, BlnM, Flip, Spcn, useScatter, bothAxes, scatterDynamics, Cnt , countDynamics}` (dual-brush size is inside its `Brsh.Dmtr`) |
| Color Dynamics | `useColorDynamics`, `clVr` (FG/BG jitter), `H   `, `Strt`, `Brgh`, `purity`, `colorDynamicsPerTip` |
| Transfer | `usePaintDynamics`, `opVr` (opacity), `prVr` (flow), `wtVr` (wetness), `mxVr` (mix) |
| Brush Pose | `useBrushPose`, `overridePoseAngle`/`TiltX`/`TiltY`/`Pressure`, `brushPoseAngle`, `brushPoseTiltX`, `brushPoseTiltY`, `brushPosePressure` [O, ag-psd + abrkit] |
| Toggles | `Nose` (Noise), `Wtdg` (Wet Edges), `Rpt ` (Build-up) |
| Tool options (`toolOptions`) | class `PbTl`/`MixB`/`SmTl`/`ErTl`…; `Opct`, `flow`, `Md  ` (blend), `smoothing`, `smoothingValue`, `smoothingRadiusMode` (Pulled String), `smoothingCatchup`, `smoothingCatchupAtEnd`, `smoothingZoomCompensation`, `pressureSmoothing`, `usePressureOverridesSize/Opacity`, `wetness`, `dryness`, `mix`, `autoFill` (load after stroke), `autoClean`, `loadSolidColorOnly`, `sampleAllLayers`, `SmdF` (finger painting), `Prs ` (smudge strength) |

**Every dynamics object has the same shape:** `bVTy` control (int), `fStp` fade steps, `jitter` %, `Mnm ` minimum %.

**`bVTy` codes.** 0 Off, 1 Fade, 2 Pen Pressure, 3 Pen Tilt, 4 Stylus Wheel are **[O2]**; every parser agrees. Codes 5–8 are disputed:

| Source | 5 | 6 | 7 | 8 |
|---|---|---|---|---|
| ag-psd + abrkit (both MIT) | Initial Direction | Direction | Initial Rotation | Rotation |
| abr-to-krita | Initial Direction | Initial Rotation | Direction | Rotation (tagged "likely") |
| brushConverter | "Rotation" | Initial Direction or Direction | Initial Direction or Direction | — |

The ag-psd/abrkit table is the most likely **[I]**. Settle it with three test presets saved from Photoshop.

### A.3 Brush Tip Shape

| Parameter | Behaviour and range | Math |
|---|---|---|
| **Size** | 1–5000 px. `Dmtr`. For sampled tips, "Use Sample Size" resets it to the bitmap's size. | Dab scale = `Dmtr / max(tipW, tipH)` for sampled tips [O, brushConverter uses exactly this]. |
| **Angle** | −180…180°, counter-clockwise positive in canvas y-up orientation. | Rotation of the tip's major axis. brushkit (`crates/preview/src/synth.rs`) negates the angle for y-down bitmaps. [O] |
| **Roundness** | 0–100%. | Scales the tip along its minor axis: `b = a·Rndn/100`. Computed tips become ellipses; sampled tips are squashed. [O brushkit synth.rs; C-ish via Adobe's Substance compatibility page, which lists it as supported] |
| **Hardness** | 0–100%, *computed tips only*. Sampled tips carry their softness in the bitmap. | See the hardness section below. |
| **Spacing** | 1–1000% of diameter; checkbox `Intr`. Unchecked, dab placement depends on event rate or speed. | Step = `Spcn/100 × D`. Whether D is the *current* (pressure-scaled) or the *base* diameter is **[I]**. Most engines, and Photoshop's visible behaviour, use the current dab size, so thin pressure tails are stamped more densely in pixels. MyPaint exposes both (`dabs_per_basic_radius`, `dabs_per_actual_radius`) [C libmypaint/brushsettings.json]. |
| **Flip X / Flip Y** | Static mirror of the tip. | Negates the axis before rotation. |

**Hardness: how it produces falloff.** Adobe has never published the curve. Four known implementations, three of them confirmed from source:

* **GIMP**, `app/core/gimpbrushgenerated.c` **[C]**:
  * `exponent = 0.4 / (1 − hardness)`
  * `alpha(d) = gauss((d/R)^exponent)`, where `gauss(f) = 1 − 2f²` for f < 0.5 and `2(1−f)²` otherwise (a piecewise-quadratic "smoothstep").
  * As hardness → 1 the exponent → ∞, so the plateau fills the disc.
* **MyPaint**, `libmypaint/mypaint-tiled-surface.c` `render_dab_mask` **[C]**: two linear segments in `rr = (d/R)²`. `opa = 1 + rr·(1 − 1/h)` for `rr ≤ h`, and `h/(1−h)·(1 − rr)` beyond, scaled by `(1 − softness)`.
* **Krita** default circle mask, `libs/image/kis_circle_mask_generator.cpp` **[C]**: a fade ratio `f` (Krita writes Photoshop hardness straight into `hfade`/`vfade` as `Hrdn/100`, per brushConverter's mapping doc). `alpha = n·(nf−1)/(nf−n)` between the fade ellipse and the edge. Krita also has an erf-based "Gaussian" generator, `kis_gauss_circle_mask_generator.cpp`.
* **brushkit's Photoshop approximation**, `crates/preview/src/synth.rs` **[O, uncalibrated]**:
  * solid core out to `r = h`
  * then `alpha = exp(−k·t²)` with `t = (r−h)/(1−h)`
  * `k` = 2.29 at 0% hardness and 1.14 at ≥50%, linearly interpolated.

**Practical advice [I].** Photoshop's soft round looks like "solid core of radius h·R, then a smooth bell falling to ≈0 at R". Implement the falloff as a function of *signed distance*, so it works for every tip shape:

`alpha = 1 − S((sdf/R + 1 − h')/(1 − h'))`

S is a selectable curve (smoothstep, Gaussian tail, or linear) and h' is hardness. Calibrate S once against a Photoshop screenshot of a 500 px 0% round dab by sampling its radial profile.

### A.4 Shape Dynamics

**The general per-dab form [I, from UI semantics]:**

```
control c ∈ [0,1] from the Control dropdown:
    Off → 1
    Fade(n) → max(0, 1 − i/n) for dab i
    Pen Pressure → p
    Pen Tilt → tilt magnitude (for size) or tilt direction (for angle)
    Stylus Wheel → wheel
    Rotation → barrel angle
    Initial Direction / Direction → stroke heading (angle only)

size      = D · lerp(minDiameter, 1, c) · (1 − sizeJitter · u)          u ~ U[0,1) per dab
angle     = Angl + [c-derived angle] + angleJitter · 360° · (u − 0.5)
roundness = Rndn · lerp(minRoundness, 1, c) · (1 − roundJitter · u)
flip      = flipXJitter ? (u < 0.5) : static
```

* **Size Jitter / Control / Minimum Diameter.** Jitter is 0–100% random reduction. The control scales between Minimum Diameter and 100%. **Tilt Scale** is the size factor applied at full tilt when Control = Pen Tilt.
* **Angle Jitter / Control.** Controls are Off, Fade, Pen Pressure, Pen Tilt, Stylus Wheel, Rotation, Initial Direction and Direction.
  * *Direction* makes the tip follow the path heading. This is essential for calligraphy and ribbon tips.
  * *Initial Direction* locks the heading from the first segment.
  * *Rotation* uses barrel rotation (Wacom Art Pen; on Android, only some styluses report `AXIS_ORIENTATION` as barrel twist, and the S Pen does not **[I]**).
* **Roundness Jitter / Control / Minimum Roundness.** Same pattern as size.
* **Flip X / Flip Y Jitter.** Random mirroring per dab.
* **Brush Projection.** Applies tilt *and* rotation to the tip shape itself. The tip foreshortens along the tilt azimuth, like a real pencil laid on its side. **[I math]** A good approximation:

  `roundness_eff = roundness · cos(tiltAltitudeFromVertical)`, `angle_eff = azimuth`

**Scattering**

| Parameter | Behaviour |
|---|---|
| **Scatter** | 0–1000% of diameter, with Control. By default the offset is perpendicular to the stroke. **Both Axes** also offsets along it. brushConverter's empirical calibration suggests the distribution is centre-weighted (Gaussian-like) rather than uniform **[O, empirical]**. |
| **Count** | 1–16 dabs per spacing step, each independently scattered and jittered. |
| **Count Jitter** | Random reduction of Count, with Control. |

### A.5 Texture

**Parameters:** Pattern (from `patt`, matched by UUID), Invert, Scale 1–1000%, Brightness −150…150, Contrast −50…100 **[C, ranges per Adobe help as quoted in brushConverter/docs/photoshop-texture-brightness-contrast-research.md]**.

**Texture Each Tip.**
* Off: the texture is applied to the finished stroke, like paper grain under a whole stroke.
* On: it is applied to every dab. Overlapping dabs then build up through the texture, and the depth controls unlock.

Adobe states that *Minimum Depth* and *Depth Jitter* only work with Texture Each Tip [C per photoshopessentials quoting Adobe]. In both cases the pattern coordinates are **canvas-locked**: the grain does not rotate with the tip **[I, consistent with all tutorials and Photoshop's "Protect Texture"]**.

**Mode:** Multiply, Subtract, Darken, Overlay, Color Dodge, Color Burn, Linear Burn, Hard Mix, Linear Height, Height.

**Depth / Minimum Depth / Depth Jitter + Control.** Photoshop says "at 100% low points in the texture receive no paint; at 0% all points receive the same amount of paint, hiding the pattern".

**The threshold math: how depth and pressure combine.** Take:

* `a` = dab alpha
* `t` ∈ [0,1] = texture "valley-ness" after invert, brightness and contrast
* `d` = effective depth = `depth · lerp(minDepth, 1, c(pressure)) · (1 − jitter·u)`

Krita's `libs/ui/tool/strokes/KisMaskingBrushCompositeOp.h` implements Photoshop-compatible modes. Its docs say Height (Photoshop) "mimics Photoshop's height mode". The exact code, in normalised form **[C for Krita; I that it equals Photoshop]**:

| Mode | Formula | Behaviour |
|---|---|---|
| Subtract | `a' = max(0, a − (t + 1 − d))` | At d = 1 texture valleys are cut out; lower d cuts more. |
| Height (PS) | `a' = clamp(10·d·a − t, 0, 1)` | **A threshold.** Paint exists where `t < 10·d·a`, with a soft ramp one texture-unit wide. |
| Linear Height (PS) | `a' = clamp(max((1−t)·10·d·a, 10·d·a − t), 0, 1)` | Height and Multiply blended, so the valleys are softer. |
| Hard Mix (PS) | Burn-like with binarised output | Aliased, very "grainy pencil". |

So in the Height modes the product `d·a` is a **fill level L**, and the output is `clamp(L − t)`: the pattern is thresholded against a level. When Depth's control is Pen Pressure, pressing harder raises L, filling more of the paper's valleys. Light pressure catches only the peaks. **This is exactly "threshold a pattern against pressure."** A generic formulation worth adopting (it contains the owner's model, see C.3):

```
L   = fillLevel(pressure, tilt, …)            // 0…1, from a curve
cov = saturate( (L − t) · gain + bias )       // gain = threshold hardness (contrast), bias ≈ 0.5/gain
a'  = a · lerp(1, cov, depthMix)
```

**Brightness and contrast.** Krita applies `t −= brightness; t = (t − 0.5)·contrast + 0.5` (`KisTextureMaskInfo::recalculateMask()`) **[C Krita]**. How Photoshop's own brightness and contrast curves work is unknown [brushConverter research note].

**Protect Texture.** A preset-level flag. It applies the same pattern and scale to *all* textured brushes, so switching brushes keeps one consistent "paper" [I from Adobe's description].

### A.6 Dual Brush

A second tip, which can be any computed or sampled tip, with its own **Size**, **Spacing**, **Scatter** (Both Axes), **Count** and **Flip**. It is combined with the primary through a **Mode** (Multiply, Darken, Overlay, Color Dodge, Color Burn, Linear Burn, Hard Mix, Linear Height) [O2 keys; C modes list from UI]. Paint appears only where both brushes overlap.

Math **[I]**:
* The secondary dabs are stamped along the same path into a mask buffer M (stroke-level).
* The primary stroke alpha is then `A' = blend(A, M)`.

Krita implements this as a "masking brush": a separate stroke-level alpha device composited with the modes above (the same file `KisMaskingBrushCompositeOp.h`) **[C Krita]**.

### A.7 Color Dynamics

| Parameter | Behaviour |
|---|---|
| **Foreground/Background Jitter** + Control | Colour = `lerp(FG, BG, c)` for control, plus random. With Pen Pressure, light = FG and heavy = BG, or the reverse [I direction]. |
| **Hue / Saturation / Brightness Jitter** | 0–100% random offsets in HSB. |
| **Purity** | −100…+100. A saturation shift applied to all dabs: −100 fully desaturates. |
| **Apply Per Tip** | On: jitter per dab. Off: per stroke (`colorDynamicsPerTip`). |

### A.8 Transfer

| Parameter | Behaviour |
|---|---|
| **Opacity Jitter** + Control + Minimum | Modulates the *stroke opacity cap* (per dab in practice: each dab's contribution is limited to the current opacity) [I]. |
| **Flow Jitter** + Control + Minimum | Modulates per-dab deposit. |
| **Wetness Jitter / Mix Jitter** | Mixer Brush only; modulates Wet and Mix per dab. |

**Opacity vs flow math [I, standard across engines].** Per dab, `S = S + (1 − S)·flow·a` inside the stroke. The stroke is then capped: `stroke = min(S, opacity)`, or equivalently composited with opacity. Photoshop's "Build-up" airbrush option keeps adding dabs while the pen is held still.

### A.9 Brush Pose, Noise, Wet Edges, Build-up, Smoothing, Protect Texture

* **Brush Pose.** Default tilt X/Y, rotation and pressure values, each with an **Override** checkbox. Lets mouse users, or a stylus lacking an axis, drive tilt- or rotation-dependent tips (important for bristle and erodible tips) [O2 keys].
* **Noise.** Adds per-pixel random noise to the *soft* (partially transparent) parts of the tip, and does nothing on hard edges. Implement as `a' = a · (1 + noiseAmp·(n − 0.5)·(1 − |2a − 1|))` with n = high-frequency noise [I].
* **Wet Edges.** Paint pools at the stroke edges, like watercolour. Stroke-level remap: reduce the interior alpha (≈ 50–60%) and increase it near the stroke's alpha gradient. Implement on the stroke buffer: `out = opacityCap · (k + (1−k)·edge(S))`, with `edge` from `1 − S` or the gradient magnitude [I].
* **Build-up (airbrush).** Emits dabs over *time* (dabs per second) while the pen is stationary, not only over distance [C UI; `Rpt ` key O2].
* **Smoothing (0–100%), a tool option** [keys O2, behaviour from Adobe help snippets and tutorials]:
  * **Pulled String Mode.** A lazy-mouse radius. Paint only moves when the cursor leaves a circle of radius R ∝ smoothing, and the brush point follows at distance R: `if |c − b| > R: b += (c − b)·(1 − R/|c − b|)`.
  * **Stroke Catch-Up.** When the pen slows or stops, the lagging paint point continues to converge on the cursor instead of stopping short.
  * **Catch-Up On Stroke End.** On pen-up, the stroke is completed to the release point.
  * **Adjust For Zoom.** Scales smoothing with zoom: less when zoomed in, more when zoomed out.
  * The non-pulled-string mode is a weighted moving average/exponential filter of recent input positions [I].
  * Smoothing is saved in the *tool preset* (`toolOptions`), so ABR files from Photoshop CC can carry it.
* **Protect Texture.** See A.5.

### A.10 Tip kinds

| Kind | What it is | Notes |
|---|---|---|
| **Computed round** (`computedBrush`) | An analytic ellipse: `Dmtr`, `Hrdn`, `Angl`, `Rndn` | The only kind with Hardness. |
| **Sampled** (`sampledBrush`) | An 8-bit or 16-bit grayscale bitmap from `samp` (raw or PackBits; zlib in some newer files per brushkit). ABR v1/v2 also store 1-bit and legacy records. | Grayscale only; colour is never stored in ABR tips. |
| **Bristle** (`dBrush`, CS5+) | A 3D strand simulation based on DiVerdi, Krishnaswamy & Hadap, "Industrial-strength painting with a virtual bristle brush" (VRST 2010, Adobe Research). Bristles are strands in the brush's non-inertial frame that bend against the canvas. | Qualities: **Shape** (10), **Bristles** (density), **Length** (relative to diameter), **Thickness**, **Stiffness**, **Angle**, **Spacing**; plus `clumping` and `physics` in the file [O2 keys]. The Brush Preview shows the live 3D brush. The strand dynamics can't be recovered from the file; only the parameters. |
| **Erodible** (`dTips`, CS6+) | Pencil, pastel or chalk tips modelled as a 3D height field that **wears down** with distance and pressure. | Shapes: Point, Flat, Round, Square, Triangle, Custom. **Softness** sets the wear rate. **Sharpen Tip** resets it. Custom tips store a height map (`dtipsErodibleTipHeightMap`) [O abrkit]. |
| **Airbrush** (`dTips` shape 5, CS6+) | A spray-cone simulation. | **Hardness**, **Distortion** (streakiness), **Granularity**, **Spatter Size**, **Spatter Amount**; tilt elongates the spray (cut-off angle) [O abrkit keys; C UI names per Adobe/CS6 tutorials]. |

### A.11 Mixer Brush (the `MixB` tool; settings live in tool options)

Photoshop options [C UI names; O2 keys `wetness`, `dryness`, `mix`, `flow`, `autoFill`, `autoClean`, `loadSolidColorOnly`, `sampleAllLayers`]:

* **Current brush load swatch.** A reservoir, which can hold a textured image loaded from the canvas unless "Load Solid Colors Only" is set.
* **Load the brush after each stroke** and **Clean the brush after each stroke**.
* **Wet.** How much paint the brush *picks up from the canvas*. High values give long smears.
* **Load.** Reservoir quantity. Low values dry out quickly.
* **Mix.** Canvas-to-reservoir ratio. At 100% all the paint comes from the canvas; at 0% all from the reservoir.
* **Flow.**
* **Sample All Layers.**

Plausible dab model **[I]**. It matches the described behaviour and the Krita "Color Smudge" and MyPaint smudge models [C libmypaint `smudge`, `smudge_length`]:

```
canvasCol = average(canvas under dab)                    // or per-pixel sample for textured pickup
pickup    = lerp(pickup, canvasCol, wet)                 // brush absorbs canvas paint
paintCol  = lerp(reservoir, pickup, mix)
reservoirAmt *= (1 − depositRate/load)                   // dries out; Load=0 → fully dry quickly
deposit     paintCol with alpha = flow · a · f(reservoirAmt, wet)
```

This needs a canvas read per dab: a GPU ping-pong copy of the dab's bounding box, or a sampled average read from a mip level.

---

## B. Brush-pack formats and importability

### B.1 Adobe `.abr`

**Structure** [C Krita `libs/brush/kis_abr_brush_collection.cpp`, GIMP `app/core/gimpbrush-load.c`; O2 ag-psd, abrkit `docs/ABR.md`, brushkit `crates/abr/src/parser.rs`]. Everything is big-endian.

* **v1 / v2** (Photoshop ≤ 7/CS):
  * Header: `u16 version, u16 count`.
  * Then `count` records of `u16 type` (1 = computed, 2 = sampled) and `u32 len`.
  * A computed record has misc, spacing, diameter, roundness, angle, hardness (legacy layout documented in Adobe's Photoshop 6 file-format spec, per abrkit).
  * A sampled record has misc, spacing, [v2: UCS-2 name], antialias, 4×i16 bounds, 4×i32 bounds, depth, compression (0 raw, 1 PackBits with per-row u16 lengths), then pixels.
  * **Krita and GIMP both skip computed v1/v2 brushes** ("computed brush unsupported").
* **v6 / v7 / v9 / v10** (Photoshop CS+; GIMP accepts 6 and 10, ag-psd, abrkit and brushkit accept 6/7/9/10):
  * Header: `u16 version, u16 subversion (1|2)`.
  * Then `8BIM` sections: `"8BIM" key[4] u32 len payload` (4-byte aligned; the last section may be unpadded).
  * `samp`: length-prefixed tip entries. Each starts with a Pascal UUID and a fixed preamble (47 bytes for sub 1, 301 for sub 2, counted from entry start), then `top,left,bottom,right,depth,compression`, then pixels. Some versions append 8 trailing bytes.
  * `desc`: `u32 16` then one Action Descriptor whose `Brsh` list holds the presets (key table in A.2).
  * `patt`: pattern records (PSD Pattern / Virtual Memory Array List, colour mode 1 gray or 3 RGB; the ID is a u8-length ASCII UUID), matched to `Txtr.Idnt`.
  * `phry`: the preset group hierarchy (another descriptor).
* **Computed tips in v6+ have no `samp` entry.** They are just `computedBrush` descriptors, so a parser must synthesise the ellipse (brushkit does, `synth.rs`).
* **Adobe documentation status [C].** Adobe's public *Photoshop File Formats Specification* documents Action Descriptors, patterns and VMA lists, and (in the PS6-era edition) the legacy brush records. It does **not** document the modern ABR container or the brush-key schema. Adobe community answers state this plainly (community.adobe.com "Where to find .abr file format specification"). Everything in the key table is reverse-engineered.

**Open-source parsers (licence, what they read)**

| Project | Lang | Licence | Reads | Notes |
|---|---|---|---|---|
| **ag-psd** `src/abr.ts` (Agamnentzar) | TS/JS | **MIT** | v6/7/9/10: tips, full descriptor → typed `Brush` (all panels incl. bristle/erodible, pose, tool options, smoothing), patterns | Throws on v1/v2. **Directly usable in the desktop WebGL prototype.** Best reference. |
| **abrkit** (focale-editor, 2026) | Dart | **MIT** | v1/2/6/7/9/10: tips (1/8/16-bit, raw/RLE), computed, bristle, erodible, airbrush, patterns, hierarchy; also **encodes** | Most complete key coverage; `docs/ABR.md` is a good spec. |
| **brushkit** (pmwoz) | Rust | **MIT** | v1/2/6/7/9/10 tips incl. zlib, computed synthesis, patterns; Procreate names + Shape.png | Heavily fuzzed; good for robustness ideas. |
| **abr-to-krita** (Pawel-9215) | Python | **MIT** | v6+ tips/desc → Krita | Logs what it skipped. |
| **brushConverter** (yxm1122) | Python | **MIT** | v1/2/6.1/6.2 tips, desc, **patt** → Krita incl. texture | Has calibration notes (scatter ÷4, texture brightness). |
| Krita `kis_abr_brush_collection.cpp` | C++ | GPL-2.0+ | Tips only (v1/v2 sampled, v6.1/6.2) | Ignores all dynamics. |
| GIMP `gimpbrush-load.c` (from abr2gbr) | C | GPL-3.0+ | Tips only (v1/2, v6/v10 sub 1/2) | Ignores dynamics. |
| freyalupen *procreate-to-krita-brush-converter* `abr/abr_parser.py` | Python | **GPL-3.0+** | Descriptor dump + tips/patterns → kpp | Experimental. |
| PSBrushExtract (MorrowShore) | Python | AGPL-3.0 | Tips + params | Avoid (AGPL). |
| tohsakrat/Brush-Converter | Python | **CC BY-NC 4.0** | abr/brushset/sut unpacking | **Do not use code** (non-commercial, not a software licence). |

All the MIT and GPL-2+/3+ items are licence-compatible with Joy Creator's GPL-3.0.

**What maps cleanly to a generic stamp engine**
* Sampled tip bitmap, diameter, angle, roundness, spacing, flip.
* Computed ellipse plus hardness.
* Size, angle and roundness jitter with pressure, tilt or direction control, plus minimums.
* Scatter (+ both axes), count, count jitter.
* Texture: pattern image, scale, invert, brightness, contrast, depth, min depth, depth jitter, each-tip, and the main modes.
* Opacity/flow jitter and control.
* Hue, saturation, brightness jitter, purity, per-tip.
* Dual brush, *if* the engine has a mask-brush stream.
* Noise, wet edges and build-up as flags.
* Smoothing values.

**What maps approximately or not at all**
* Bristle tips: parameters only, so approximate with a multi-bristle or sampled fallback (see C.5).
* Erodible tips: approximate with a wear model or a static shape.
* Airbrush tip: approximate as soft tip + noise + tilt stretch.
* Mixer-brush physics: only if the engine has pickup.
* Photoshop's exact hardness and texture brightness/contrast curves: calibrate.
* Fade units, which are dab counts, not pixels.
* Stylus Wheel: few Android pens have it; map to barrel or pressure.

### B.2 Procreate `.brush` / `.brushset`

**Structure** [C by reading freyalupen's GPL-3.0 converter `procreate/procreate_brush_parser.py` and `procreate_to_kpp.py`; O brushkit].

* `.brush` = a zip with:
  * `Brush.archive`: a **binary plist, NSKeyedArchiver** (`$objects` array, UIDs; the root dictionary is `$objects[1]`).
  * `Shape.png` (tip) and `Grain.png` (texture).
  * Optional `QuickLook/Thumbnail.png`, author picture and signature.
  * Optional `Sub01/` folder: the **dual brush**, with its own Brush.archive, Shape and Grain.
  * Procreate 5.2+ 3D material images, referenced by `bundledHeightPath`/`bundledMetallicPath`/`bundledRoughnessPath`.
* `.brushset` = a zip of UUID folders (one `.brush` layout each) plus `brushset.plist` (set name and order).
* Some Brush.archives are a flat XML plist with only name, identifier and author (brushkit issue #152). Parsers must tolerate this.
* **Built-in resources.** When `bundledShapePath`/`bundledGrainPath` is `$null`, the embedded PNG is used. Otherwise the brush references a **Procreate built-in** shape or grain that is *not* in the file. p2k warns "requires external resource". Those images are Savage Interactive's property, so the brush must fall back to a lookalike. p2k reports recreating a CC0 lookalike library (Krita Artists thread).
* **Curves** are arrays of `"{x, y}"` strings. `*Curve` keys pair with a scalar amount.
* The key `importedFromABR` shows that Procreate imports ABR itself.

**Parameter names** [C from p2k's key switch]. Mapping to UI names is per the Procreate Handbook [C]; the correspondences marked "?" are **[I]**.

| Section | Key names |
|---|---|
| Stroke path | `plotSpacing` (Spacing; non-linear, p2k uses `sqrt(v·100)/10` as a guess), `plotJitter` (Jitter lateral?), `plotJitterTilt*`, `plotJitterRoll*`, `dynamicsFalloff` (Fall off) |
| Stabilisation | `plotSmoothing` (StreamLine), `dynamicsPressureSmoothing` (StreamLine pressure), `plotMovingAverageStabilization` (Stabilization), `plotFFTSmoothingAmount` / `plotFFTSmoothingBias` (Motion filtering amount / expression) |
| Taper | `pencilTaperStartLength`, `pencilTaperEndLength`, `pencilTaperSize`, `pencilTaperOpacity`, `pencilTaperShape` (tip), `pencilTaperSizeLinked`, `taperPressure`; touch taper: `taperStartLength`, `taperEndLength`, `taperSize`, `taperOpacity`, `taperShape`, `taperSizeLinked`; `taperVersion` (classic) |
| Shape | `bundledShapePath`, `shapeInverted`, `shapeRotation` (Touch rotation / follow stroke?), `shapeScatter` (per-stamp rotation jitter per Handbook), `shapeCount`, `shapeCountJitter` (+Tilt/Roll variants), `shapeRandomise` (Randomized), `shapeFlipXJitter`, `shapeFlipYJitter`, `shapeAngle`, `shapeRoundness` (Roundness graph), `dynamicsPressureShapeRoundness(+Curve, Minimum)`, `dynamicsTiltShapeRoundness(+Minimum)`, `shapeRoundnessTiltAngle`, `shapeAzimuth` (Input style: azimuth), `shapeRoll`/`shapeRollMode` (barrel roll), `shapeOrientation`, `shapeFilter`/`shapeFilterMode` |
| Grain | `bundledGrainPath`, `textureMovement` (Moving vs Texturized?), `textureApplication`?, `textureScale`, `textureZoom` (Cropped/Follow size), `textureRotation`, `textureOrientation`, `grainOrientation`, `grainDepth`, `grainDepthMinimum`, `grainDepthJitter`, `textureDepthTilt(+Angle)`, `textureOffsetJitter`, `grainBlendMode`, `textureBrightness`, `textureContrast` (−1…1), `textureInverted`, `textureFilter(+Mode)`, `texturizedGrainFollowsCamera` |
| Rendering | `renderingModulatedTransfer`, `renderingRecursiveMixing`, `renderingMaxTransfer` (the six glaze/blending modes are combinations of these?), `dynamicsGlazedFlow` (Flow), `wetEdgesAmount`, `burntEdgesAmount`, `burntEdgesBlendMode`, `blendMode`, `extendedBlend`, `blendGammaCorrect` |
| Wet mix | `dynamicsMix` (Dilution?), `dynamicsLoad` (Charge), `dynamicsWetAccumulation` (Attack?), `dynamicsSmudgeAccumulation` (Pull?), `dynamicsMixSoftening` (Grade?), `dynamicsBlur`, `dynamicsBlurJitter`, `dynamicsWetnessJitter`, `attackTilt(+Angle)`, `attackRoll(+Parameters)` |
| Colour dynamics | Stamp: `dynamicsJitterHue`/`Saturation`/`Lightness`/`Darkness`, `jitterSecondary`. Stroke: `dynamicsJitterStrokeHue…`, `jitterStrokeSecondary`. Pressure: `dynamicsPressureHue/Saturation/Brightness/SecondaryColor(+Curve)`. Tilt: `dynamicsTiltHue…` (+`*TiltAngle`). Barrel roll: `dynamicsRollHue…(+Parameters)` |
| Dynamics | `dynamicsSpeedSize`, `dynamicsSpeedOpacity`, `dynamicsJitterSize`, `dynamicsJitterOpacity`, `dynamicsPressureSizeSpeed`, `dynamicsPressureOpacitySpeed` |
| Apple Pencil | `dynamicsPressureSize(+Curve)`, `dynamicsPressureOpacity(+Curve)`, `dynamicsPressureBleed(+Curve)`, `dynamicsPressureResponse`, `dynamicsPressureOpacityTransfer`, `dynamicsTiltAngle` (tilt graph), `dynamicsTiltOpacity`, `dynamicsTiltGradation`, `dynamicsTiltBleed`, `dynamicsTiltSize`, `dynamicsTiltCompression` (size compression), `dynamicsRollSize/Opacity/Bleed(+Parameters{activationFalloff, activationOrigin})`, `hoverPressure/Fill/Outline` |
| Properties | `maxSize`, `minSize`, `maxOpacity`, `minOpacity`, `oriented` (Orient to screen), `previewSize`, `paintSize`, `paintOpacity`, `smudgeSize`, `eraseSize`, … |
| Dual | `Sub01/`, `dualBlendMode` |
| Materials | `metallicAmount/Scale`, `roughnessAmount/Scale`, `heightAmount/Scale` |

**Parsers.**

* **freyalupen / procreate-to-krita-brush-converter** (invent.kde.org), **GPL-3.0-or-later**. The best reference. It self-describes as "very unfinished and broken", but its key list is complete and it also parses ABR and SUT.
* **brushkit** (Rust, MIT) for names and tips.
* tohsakrat/Brush-Converter: reference only, because it is NC-licensed.

Libraries: bplist + NSKeyedArchiver unarchiving is available in Java through **dd-plist** (MIT, widely used [I licence from memory, verify]) and in JS through `bplist-parser` (MIT [I]) + JSZip (MIT/GPLv3 dual [I]). You then resolve `$objects` UIDs yourself (about 40 lines, see p2k `resolve_uids`).

**Mapping quality.**
* Clean: shape/grain PNGs, spacing, scatter, count, rotation-follows-stroke, roundness, size/opacity pressure curves, tilt size and opacity, colour jitter (stamp and stroke), secondary colour, grain scale, depth and min depth, brightness/contrast/invert, blend mode, dual brush, taper, speed dynamics, stabilisation.
* Unclear: several value scales (spacing, jitter, `shapeRotation`), which are guessed.
* Hard: the rendering modes (glaze vs blending; needs a stroke accumulation model), wet mix (needs pickup), moving grain ("rolling" texture that drags with the stroke), and built-in resources.

### B.3 Krita `.kpp` and `.bundle`

* **`.kpp`.** A PNG thumbnail with text chunks `version` and `preset` (the XML paint-op settings) [C `libs/image/brushengine/kis_paintop_preset.cpp` lines ~172, 396]. The XML carries the engine id (`paintopid`), a `brush_definition` (auto mask, or predefined PNG/GBR/GIH/ABR tip, often base64-embedded), and sensor curves: `PressureSize`, `SizeSensor`, `…commonCurve "x,y;x,y;"`, and sensors `pressure`, `xtilt`, `ytilt`, `ascension`, `declination`, `rotation`, `speed`, `drawingangle`, `fuzzy`, `fuzzystroke`, `fade`, `distance`, `time`, `perspective`, `tangentialpressure`.
* **`.bundle`.** An ODF-style zip: `mimetype`, `meta.xml`, `META-INF/manifest.xml`, `preview.png`, `brushes/`, `patterns/`, `paintoppresets/`… [O abr-to-krita / brushConverter writers].
* **Parsers.** Krita itself (GPL-2+) and p2k `kpp/kpp_brush_parser.py` (GPL-3). The format is simple enough to write from scratch.
* **Maps well** for the **Pixel ("paintbrush") engine**, which is a stamp engine with texture (the PS-like modes), a masking brush (= dual), scatter, sensors and a colour smudge engine. It does **not** map for Krita's non-stamp engines: Hairy/Bristle, Sketch, Deform, Particle, Spray (partially), Curve, Hatching, Grid, Shape, Filter, Tangent Normal, Quick. Import only the paintbrush and colorsmudge engines and skip the rest with a message.
* **Content licences.** David Revoy's `deevad-krita-brushpresets` are **CC-BY 4.0** (README) and some newer bundles are CC0 per his site. Ramón Miranda's hairy presets are WTFPL. Krita's default bundle is mixed, so check it per resource.

### B.4 MyPaint `.myb`

* **Structure** [C `libmypaint/brushsettings.json`, `mypaint-brushes/brushes/*`]. JSON v3: `{"version":3, "settings": {name: {"base_value": v, "inputs": {input: [[x,y],…]}}}, "parent_brush_name", "notes", …}` plus a `_prev.png` preview.
* **Inputs (18):** pressure, random, stroke, direction, tilt_declination, tilt_ascension, speed1, speed2, custom, direction_angle, attack_angle, tilt_declinationx/y, gridmap_x/y, viewzoom, brush_radius, barrel_rotation.
* **Settings (~70):** opaque, opaque_multiply, radius_logarithmic, hardness, softness, anti_aliasing, dabs_per_basic_radius, dabs_per_actual_radius, dabs_per_second, offset_*, speed filters, slow_tracking (smoothing), tracking_noise, color_h/s/v, change_color_*, smudge, smudge_length, smudge_radius_log, paint_mode (spectral pigment), eraser, elliptical_dab_ratio/angle, direction_filter, lock_alpha, colorize, posterize, snap_to_pixel, pressure_gain_log, …
* **Semantics.** A *modulation matrix*: each setting is base plus the sum of its piecewise-linear input curves. Classic MyPaint has **no bitmap tips**, only parametric round or elliptical dabs with hardness.
* **Maps well** to a procedural tip plus a sensor matrix, and is the cleanest model to copy for Joy Paint's dynamics. **Content:** the whole `mypaint-brushes` set is **CC0-1.0** (`Licenses.dep5`: brushes/*, deevad, ramon, tanda, kaerhon_v1, Dieterle). libmypaint (ISC) could even be embedded.

### B.5 GIMP `.gbr` / `.gih` / `.vbr`

All confirmed from `app/core/gimpbrush-load.c`, `gimpbrushpipe-load.c`, `gimpbrushgenerated-load.c` [C].

* **`.gbr`.**
  * Header: `u32 header_size, version (1/2/3), width, height, bytes (1 = gray, 4 = RGBA colour brush), magic "GIMP", spacing`, then a UTF-8 name, then pixels.
  * Supports **colour tips**.
* **`.gih`** (image hose / animated brush).
  * A text name line, then a parameter line `ncells cellwidth cellheight step dim rank0.. selection0..`.
  * Selection per dimension is one of incremental, angular, random, velocity, pressure, xtilt, ytilt.
  * Then *N* `.gbr` records.
  * This is **multi-image tip selection**, which Photoshop lacks.
* **`.vbr`.**
  * Text `GIMP-VBR` 1.0/1.5: name, spacing, radius, [spikes, shape = circle/square/diamond], hardness, aspect, angle.
  * Parametric tips including **square and diamond**, close to the owner's model.
* **Dynamics** are separate `.gdyn` files and are rarely shared.
* **Maps:** trivially to tip (image/array/procedural) + spacing. Low value, low cost.

### B.6 Clip Studio Paint `.sut`

* **Structure** [C from p2k `sut/sut_parser.py` (GPL-3)].
  * An **SQLite** database with tables `Manager`, `Node`, `Variant` (one row of parameters), and `MaterialFile`.
  * `MaterialFile.FileData` is a tar whose `data/material(_0).layer` is CSP's proprietary layer container for the tip or texture image. p2k decodes it partly (`loadC2F`).
* **Variant columns** include `BrushSize`, `BrushSizeEffector`, `BrushHardness`, `BrushInterval` (+Effector), `BrushThickness` (+Effector; = roundness), `BrushVerticalThicknes`, `BrushRotation` (+Effector, RandomScale, InSpray), `BrushFlow` (+Effector), `BrushUsePatternImage`, `BrushPatternImageArray` (**multi-tip**), `BrushPatternOrderType`, `TextureImage`, `TextureCompositeMode`, `TextureScale2`, `TextureRotate`, `TextureDensity` (+Effector), `TextureForPlot` (per-tip), `BrushUseWaterColor`, `BrushWaterColor`, `Stickness`, `FlickerReduction` (stabiliser), `CompositeMode`, `Opacity`, `AntiAlias`, …
* The `*Effector` blobs encode pressure, tilt, velocity and random curves.
* **Maps well** for parameters. Tip images are the weak point: the `.layer` decoding is partial and some materials reference CSP's installed library.
* **Priority:** medium. CSP has a large user base in Asia (Clip Studio Assets); licences on Assets vary.

### B.7 Others

| App / format | Status | Verdict |
|---|---|---|
| **Infinite Painter `.prbr`** | Proprietary. The official docs say it is "not compatible with other applications". No public parser found. | Skip. |
| **ArtRage** | Custom brush presets and `.stk` Sticker Spray presets. ArtRage itself imports ABR (sampled only; fails on "vector heads"). No public parser. | Skip. |
| **Concepts** (vector) | Closed brush model; no brush-file exchange. | Skip. |
| **ibisPaint** | Brushes are shared as **QR-code images** that encode a proprietary, likely compressed or encrypted payload [I]. No parser. | Skip. |
| **Photoshop `.tpl`** (tool presets) | Holds tool presets, including brush presets with tool options, colours and blend mode. Its container is not publicly documented; it is **likely** the same descriptor family as ABR (8BIM sections + Action Descriptors) [I]. | Later. Add after ABR by probing sample files with an ABR/descriptor reader. |
| **Affinity `.afbrushes`** | Proprietary; Affinity imports ABR. | Skip. |

### B.8 Legal notes (not legal advice)

1. **Reading a file format for interoperability is broadly lawful.** File formats and functionality are not protected by copyright (CJEU *SAS Institute v World Programming*, C-406/10, 2012). The EU Software Directive art. 6 and US 17 U.S.C. §1201(f) (*Sega v. Accolade*) protect reverse engineering for interoperability. Krita, GIMP, Affinity, Procreate, CSP and ArtRage all ship ABR importers. Adobe publishes no ABR licence restricting third-party readers. Use nominative wording ("imports Photoshop® .abr brushes") and no Adobe or Procreate logos.
2. **The user's own files.** Importing packs the user has bought or downloaded, for their own use, is the normal expectation of most brush licences, which license *use* by the purchaser. Some commercial licences say "for use in [app] only". Show a one-line notice that the pack's licence still applies.
3. **Redistribution is not fine.** Do not host, sync to a public gallery, re-export or "share" converted third-party brushes unless the source licence allows it. Keep provenance (`meta.author`, `meta.license`, `meta.source`) in every imported brush. Consider disabling public sharing of brushes whose licence is unknown.
4. **Procreate built-in resources.** Never extract or ship the Shape/Grain images from Procreate's default library. Substitute original or CC0 lookalikes, as p2k did.
5. **Parser code licences.** Joy Creator is GPL-3.0.
   * Compatible: MIT (ag-psd, abrkit, brushkit, abr-to-krita, brushConverter), GPL-2.0+ (Krita), GPL-3.0+ (GIMP, p2k).
   * Avoid: CC BY-NC (Brush-Converter) and AGPL (PSBrushExtract), to keep things simple.

### B.9 Openly licensed packs that could ship bundled

| Pack | Licence | Format | Notes |
|---|---|---|---|
| **MyPaint brushes 2.x** (classic, experimental, deevad, ramon, tanda, kaerhon_v1, Dieterle) | **CC0-1.0** [C Licenses.dep5] | `.myb` | Best procedural base set. |
| **David Revoy – deevad-krita-brushpresets** | **CC-BY 4.0** [C README] | `.kpp` + png tips/patterns | Attribution in the About screen; high quality. Newer Revoy bundles may be CC0 [I per his site]. |
| Ramón Miranda hairy presets (Krita) | WTFPL [per krita.org post] | `.kpp` | Bristle-engine presets; would need mapping. |
| **K. M. Alexander map/cartography ABR sets** | **CC0** [per author's site] | `.abr` | Stamp brushes; good test corpus too. |
| Catherine's Basic Procreate Brushes (cOborski, itch.io) | Public domain / CC0 [per listing] | `.brushset` | A small test corpus for the Procreate importer. |
| p2k's CC0 recreation of Procreate default resources | CC0 [per Krita-Artists thread; verify] | png | Fallback for built-in `bundledShapePath`s. |
| GIMP bundled brushes | Distributed with GPL-3 GIMP; verify per file [I] | gbr/gih/vbr | Low priority. |

Brusheezy "CC0" filters and similar aggregators are **not** reliable. Verify every licence at the author's own page.

---

## C. The owner's simplified tip model vs Photoshop

### C.1 The model, formalised

This is a sketch of the math the owner described, so the comparison is exact. `p` is in dab-local coordinates after rotation by `angle`, with unit radius R = size/2.

```
A ∈ [−1,1]  aspect:  sx = 1 − max(0, A)·(1 − ε),   sy = 1 − max(0, −A)·(1 − ε)   // +1 tall-thin, −1 short-wide
c ∈ [0,1]   corner rounding (0 = square, 1 = circle)
τ ∈ [0,1]   taper: half-width at v = sx·(1 − τ·(v+1)/2)  → square → trapezoid → triangle (τ = 1)
d = sdTaperedRoundedBox(p; sx, sy, τ, cornerRadius = c·min(sx, sy))
a = 1 − S( (d/R + 1 − h')/(1 − h') )        // softness / falloff on the signed distance
grain:  g = cloud(fBm, in dab space rotated by stroke direction or barrel, with lag/damping)
        L = gradient(linear: direction & slope from tilt, level from pressure | radial for tapered tips, scaled by pressure)
        cov = saturate((L·g − θ)·gain)        // threshold → clumpy marks
a_final = threshold/softness(a · cov)
placement: spacing (typ. ≤ 4 %), scatter, rotation variance; colour: hue & value jitter
```

### C.2 Coverage of Photoshop's permutations

| Photoshop feature | Covered? | How |
|---|---|---|
| Computed round tip, 100% roundness, any hardness | **Yes** | c = 1, A = 0, softness ↔ hardness. Calibrate S. |
| Computed tip with roundness < 100% (ellipse) | **Partly** | A ≠ 0 with c = 1 gives a **capsule/stadium** (rounded rectangle), not an ellipse. Soft tips look nearly identical; hard tips show flat sides. **Fix:** make c a **superellipse exponent** `|x/sx|^n + |y/sy|^n = 1`, with n = 2 an ellipse and n → ∞ a square. That reproduces Photoshop roundness exactly and still reaches the square. |
| Angle, Flip X/Y | Angle yes; **flip missing** | Flip only matters for asymmetric shapes: taper, image tips. Add flipX, flipY and flip jitter (cheap). |
| Square, rounded-square, trapezoid, triangle, chisel and razor tips | **Yes, better than Photoshop** | Photoshop can only do these as sampled bitmaps, which lose resolution and have no per-shape hardness. Softness on the SDF is a real advantage. The razor extreme must clamp to ≥ ~1 device px and fade alpha instead of thinning further, or it aliases or vanishes. |
| Sampled (bitmap) tip | **Only if "texture loaded as a stamp" means the image *is* the tip alpha** | Make it explicit: `tip.source = procedural | image`. An image tip replaces the SDF (its alpha channel or luminance). It is not a texture multiplied inside the square. |
| Multi-image tips (GIMP .gih, CSP pattern arrays, Krita animated) | **No** | Photoshop lacks these too, but they matter for leaves, foliage and stamps. Add `image[]` + selection (random / sequential / pressure / angle). |
| Colour (RGBA) tips | **No** | ABR and Procreate tips are gray, but GIMP, Krita and CSP support colour. Optional flag. |
| Size, angle and roundness dynamics (jitter + pressure/tilt/direction + minimum) | **Partly** | Rotation variance ≈ angle jitter; direction and barrel covered. Missing: size jitter + minimum, roundness/aspect jitter, and a *generic* control curve per parameter. |
| Scattering | **Partly** | Scatter amount yes. Missing: **Count** (dabs per step), count jitter, **both axes** vs perpendicular-only. Needed for "leaves". |
| Texture Each Tip (per-dab grain) | **Yes** | The cloud × gradient → threshold *is* Height-mode texture with depth driven by pressure (see A.5). |
| Texture with a canvas-locked pattern (paper grain) | **No, and this is the key gap** | The owner's grain rotates with stroke direction or barrel, so it lives in **dab space**. That suits bristle clumps and dry-brush streaks. Photoshop texture and Procreate "Texturized grain" live in **canvas space**: the same paper tooth shows through every stroke, which is what makes pencil and charcoal read as "on paper". Add `grain.space = canvas | dab | rolling`, where rolling is Procreate's "moving grain" that drags with the stroke. |
| Texture pattern from an image file | **Yes** | "Loaded" option; also needed for ABR `patt` and Procreate `Grain.png`. |
| Texture modes (Multiply, Subtract, Height, Linear Height, Hard Mix, …) | **Partly** | Threshold ≈ Height/Hard Mix. Add at least Multiply (soft grain), Subtract, Height (threshold), Linear Height. That covers ~95% of real ABR/Procreate brushes [I]. |
| Texture brightness, contrast, invert, scale, min depth, depth jitter | **Mostly** | Contrast = threshold gain. Brightness and invert are trivial. Add min depth and depth jitter as part of the generic dynamics. |
| Dual Brush | **No** | The cloud × gradient is a *procedural* second channel but not an independently stamped second tip. Add a **mask-tip stream** (see C.4). |
| Color Dynamics | **Partly** | Hue and value jitter yes. Missing: saturation jitter, FG/BG (secondary-colour) jitter with control, purity, per-dab vs per-stroke. |
| Transfer: opacity vs flow | **No** | Not mentioned. Essential: flow = per-dab deposit; opacity = stroke cap. Requires a **stroke buffer**. |
| Noise | **Covered** | High-frequency cloud with no threshold. |
| Wet Edges | **No** | Needs the stroke buffer + an edge remap. |
| Build-up | **No** | Needs time-based dab emission. |
| Smoothing | **Out of the tip model** | Must exist in the stroke pipeline: pulled string, moving average, catch-up. Import the values. |
| Brush Pose | **No** (minor) | A default tilt/rotation for pens lacking axes. Cheap. |
| Bristle tips | **No** | "Damping for bristle length/stiffness" on the grain is a nice *look*, but not per-bristle strokes. See C.5. |
| Erodible tips | **No** | Needs tip wear over distance. See C.5. |
| Airbrush tip | **Approx.** | Soft tip + fine noise + tilt elongation (projection) + scatter count. |
| Mixer brush / smudge / wet mix | **No** | Needs canvas pickup. See C.4. |
| Spacing | **Yes** | But imports need 1–1000% and a speed-based mode. Photoshop brushes are authored at 10–25%. Keep the imported spacing, or flow will look ~6× heavier at 4%. |

### C.3 Why the cloud-threshold is right (and how to state it generally)

The owner's `saturate((gradient(pressure, tilt) · cloud − θ)·gain)` has the same shape as Photoshop's Height-mode `clamp(10·depth·a − t)`, and so does Procreate's grain depth with minimum and pressure. Only the "fill level" differs: Photoshop gets it from depth × dab alpha, the owner from a pressure/tilt gradient.

Write it once, generically, and every format maps into it:

```
t   = grainSample(space, transform)         // image or procedural fBm, after invert/brightness/contrast
L   = level(dab alpha, depth·dyn(pressure…), gradient(tilt, pressure) optional)
cov = mode(L, t)   // multiply | subtract | height (= saturate((L − t)·gain)) | linearHeight | hardMix
```

The owner's "linear gradient whose direction and slope follow tilt" is a strict *superset* of Photoshop, which has no tilt-directed fill gradient. It is a good signature feature for S Pen side-shading. The "radial gradient for tapered tips scaled by pressure" is the same as `L = dab alpha × depth(pressure)`, which is exactly Photoshop/Krita Height mode.

### C.4 Smallest additions that close the gaps (in priority order)

1. **Tip source switch.** `procedural` (the owner's SDF, with a **superellipse** corner option so the round end is a true ellipse) | `image` | `image[]` with a selection mode. Add `flipX`, `flipY` and flip jitter.
   *Unlocks:* all sampled ABR tips, Procreate Shape.png, GIMP gbr/gih, CSP pattern arrays.
2. **Separate grain layer.**
   * Source: image or procedural.
   * `space`: canvas | dab | rolling. Canvas is paper grain; dab is the owner's rotating cloud; rolling is Procreate moving grain, with `movement` 0…1.
   * `scale`, `rotation`, `zoomWithSize`, `invert`, `brightness`, `contrast`, `mode`, `depth` (+ dynamics), `minDepth`, `depthJitter`, `offsetJitter`, `perDab` flag.
   *Unlocks:* Photoshop Texture and Procreate Grain.
3. **Generic dynamics ("sensor matrix").**
   * Every scalar = `base · Π curves(sensor)` (or +), then `· (1 − jitter·rand)`, clamped at `min`.
   * Sensors: pressure, tiltMagnitude, tiltAzimuth, barrelRotation, speed, direction, initialDirection, fade (dab count or distance), strokeTime, random-per-dab, random-per-stroke, wheel (optional).
   * Replaces the owner's hard-wired links (tilt→gradient direction and so on), which become default presets of the matrix.
   *Unlocks:* Shape Dynamics, Transfer, the MyPaint model, Procreate curves.
4. **Stroke buffer + opacity/flow split.**
   * Dabs accumulate into a per-stroke buffer at `flow`.
   * On commit, the buffer is capped at `opacity` and composited with the brush **blend mode**.
   * Wet edges and burnt edges become remaps on that buffer.
   * **Build-up** = time-emitted dabs.
   *Unlocks:* Transfer, Wet Edges, Procreate glaze modes (approximately), eraser.
5. **Count + both-axes scatter.** N dabs per step, count jitter, scatter perpendicular or both.
   *Unlocks:* Scattering, Procreate Count, "leaves".
6. **Mask-tip stream (dual brush).** A second tip (any tip source) with its own size ratio, spacing, scatter and count, stamped into a stroke-level mask buffer and combined with a mode (multiply / subtract / height / hard mix).
   *Unlocks:* Photoshop Dual Brush, Procreate Sub01, Krita masking brush.
7. **Colour dynamics completion.** Saturation jitter, secondary-colour (FG/BG) mix with control, purity, per-dab vs per-stroke, pressure/tilt→HSV.
   *Unlocks:* Color Dynamics, Procreate colour.
8. **Pickup stage (optional v2).** Parameters `wet` (pickup), `load` (reservoir, drying), `mix`, `length`/`pull`, `blur`, `sampleAllLayers`. Implemented as a per-dab canvas read (FBO ping-pong) with the reservoir model in A.11.
   *Unlocks:* Mixer Brush, Procreate Wet Mix, MyPaint smudge, Krita colorsmudge.
9. **Bristle and erodible approximations (optional v2).**
   * *Bristle:* K bristles sampled once per stroke inside the tip SDF (density = Photoshop Bristles). Each bristle is a small dab of radius ∝ Thickness that follows the stroke with a spring lag (Length and Stiffness → lag and damping) and lifts off with low pressure. The owner's damping idea extends naturally here.
   * *Erodible:* a `wear` accumulator (distance × pressure × softness) that interpolates the tip from sharp (τ → 1, small corner) to blunt (τ → 0, larger size).
   *Unlocks:* approximate import of Photoshop bristle and erodible tips and Krita Hairy.

Items 1–5 are small: a shader uniform or two each, plus a stroke buffer the engine will need anyway. They cover roughly 90% of real ABR and Procreate brushes [I]. Items 6–7 cover almost all the rest; items 8–9 are the physically based remainder.

### C.5 Notes on the owner's specifics

* **Spacing ≤ 4%.** Fine for the owner's own brushes, and GPU-instanced stamping makes it cheap. Always store spacing per brush, though. Consider an optional *spacing-compensated flow* (`flow_eff = 1 − (1 − flow)^(spacing/0.25)`), so changing spacing does not change the darkness [I technique].
* **Aspect −100…+100.** Equivalent to Photoshop roundness + a 90° angle swap. Import `Rndn` as `A = −(1 − Rndn/100)` with the angle unchanged. For razor extremes, use `ε ≥ 1px/R`.
* **Taper.** Photoshop has no taper *tip shape*. Procreate "taper" means *stroke-end size and opacity taper*, a different thing. Keep both: `tip.taper` (shape) and `stroke.taper{start, end, size, opacity}` (stroke envelope, which Procreate and CSP need).
* **Grain rotating with barrel rotation, with damping.** Keep it. It corresponds to Procreate `textureRotation`/`grainOrientation` and has no Photoshop equivalent. Photoshop imports should default to `space = canvas`.

---

## D. Proposed interchange model ("JoyBrush v1")

Design goals:
* One JSON document plus referenced images, so the desktop WebGL prototype and the Android GLES engine read the same file.
* All values normalised (0…1 or degrees or ×diameter), with units stated.
* A lossless `extensions` block that keeps the raw source parameters, so a better mapper can re-derive them later.
* Zipped as `.joybrush`: `brush.json`, `tip*.png`, `grain.png`, `mask_tip.png`, `preview.png`.

```jsonc
{
  "format": "joybrush", "version": 1,
  "meta": { "name": "", "author": "", "license": "CC0-1.0|CC-BY-4.0|proprietary-user-owned|unknown",
            "source": { "format": "abr|procreate|kpp|myb|gbr|gih|vbr|sut|native", "file": "", "index": 0 } },

  "tip": {
    "source": "procedural",                 // procedural | image | imageArray
    "procedural": {
      "corner": 1.0,                        // 0 square … 1 fully round
      "cornerMode": "superellipse",         // superellipse (PS-exact ellipses) | roundedRect (capsule)
      "taper": 0.0,                         // 0 … 1 (1 = triangle)
      "aspect": 0.0,                        // −1 short/wide … +1 tall/thin
      "minThicknessPx": 1.0
    },
    "images": ["tip0.png"],                 // gray (alpha = luminance) or RGBA
    "imageChannel": "luminance",            // luminance | alpha | rgba (colour tip)
    "imageInvert": false,
    "arraySelect": "random",                // random | sequential | pressure | angle | speed | tiltX | tiltY
    "hardness": 1.0,                        // 0 … 1, applies to procedural (and optionally softens images)
    "falloff": "ps",                        // ps | gauss | linear | smoothstep | curve
    "falloffCurve": null,                   // [[x,y],…] if falloff = curve
    "size": 30.0,                           // px (canvas units)
    "sizeRange": [1, 500],
    "angle": 0.0,                           // degrees, CCW, canvas y-up
    "flipX": false, "flipY": false,
    "spacing": 0.25,                        // × current diameter
    "spacingMode": "actualDiameter",        // actualDiameter | baseDiameter | fixedPx | speed
    "projection": false                     // tilt/rotation foreshortens tip (PS Brush Projection)
  },

  "dynamics": {                             // every entry uses the same Param shape (below)
    "size":      { "base": 1.0, "min": 0.0, "jitter": 0.0, "sensors": [ { "in": "pressure", "curve": [[0,0],[1,1]] } ] },
    "angle":     { "base": 0.0, "jitter": 0.0, "follow": "none", "sensors": [] },   // follow: none|direction|initialDirection|barrel|tiltAzimuth
    "aspect":    { "base": 0.0, "min": 0.0, "jitter": 0.0, "sensors": [] },
    "flow":      { "base": 1.0, "min": 0.0, "jitter": 0.0, "sensors": [] },
    "opacity":   { "base": 1.0, "min": 0.0, "jitter": 0.0, "sensors": [] },
    "flipJitterX": 0.0, "flipJitterY": 0.0
  },
  // Param = { base, min, jitter (0…1), jitterMode: "perDab"|"perStroke", combine: "multiply"|"add",
  //           sensors: [ { in: pressure|tilt|tiltAzimuth|barrel|wheel|speed|direction|initialDirection|
  //                            fade|distance|time|random|strokeRandom, curve: [[x,y]…], fadeLength? } ] }

  "scatter": { "amount": 0.0, "bothAxes": false, "distribution": "gaussian",   // amount × diameter
               "count": 1, "countJitter": 0.0, "amountSensors": [] },

  "grain": {
    "enabled": false,
    "source": "image",                      // image | procedural
    "image": "grain.png",
    "procedural": { "type": "fbm", "octaves": 4, "lacunarity": 2.0, "gain": 0.5, "seed": 0 },
    "space": "canvas",                      // canvas | dab | rolling
    "movement": 0.0,                        // rolling only (Procreate)
    "scale": 1.0, "rotation": 0.0, "followsSize": false, "offsetJitter": false,
    "rotateWith": "none",                   // none | direction | barrel   (dab space)
    "damping": 0.0,                         // lag of grain orientation (bristle-ish)
    "invert": false, "brightness": 0.0, "contrast": 1.0,    // brightness −1…1; contrast = gain
    "mode": "multiply",                     // multiply | subtract | darken | overlay | colorDodge | colorBurn |
                                            // linearBurn | hardMix | height | linearHeight
    "depth": { "base": 1.0, "min": 0.0, "jitter": 0.0, "sensors": [] },
    "levelGradient": { "type": "none", "tiltDirected": true, "slopeFromTilt": 1.0, "levelFromPressure": 1.0 },  // none|linear|radial (owner's model)
    "threshold": { "enabled": false, "level": 0.5, "softness": 0.1 },
    "perDab": true                          // PS "Texture Each Tip"
  },

  "maskTip": {                              // PS Dual Brush / Procreate Sub01 / Krita masking brush
    "enabled": false, "tip": { /* same shape as "tip" */ }, "sizeRatio": 1.0,
    "scatter": { /* same as scatter */ }, "mode": "multiply", "grain": null
  },

  "color": {
    "hueJitter": 0.0, "satJitter": 0.0, "valJitter": 0.0, "darkJitter": 0.0,
    "secondaryMix": { "base": 0.0, "jitter": 0.0, "sensors": [] },   // FG↔BG / secondary colour
    "purity": 0.0,                          // −1 … +1
    "perDab": true,
    "hsvSensors": { "hue": [], "sat": [], "val": [] }
  },

  "stroke": {
    "blendMode": "normal", "eraser": false,
    "accumulate": "buildUpToOpacity",       // buildUpToOpacity (PS) | glazeLight | glazeUniform | glazeIntense | glazeHeavy | max
    "wetEdges": 0.0, "burntEdges": 0.0, "burntEdgesMode": "multiply",
    "buildUp": { "enabled": false, "dabsPerSecond": 0 },
    "noise": 0.0,
    "taper": { "start": 0.0, "end": 0.0, "size": 0.0, "opacity": 0.0, "pressureTaper": false },
    "falloffLength": 0.0                    // Procreate Fall off (opacity fade over length)
  },

  "smoothing": { "amount": 0.0, "mode": "average", // average | pulledString | streamline | fft
                 "catchUp": false, "catchUpOnEnd": true, "zoomAdjust": true, "pressureSmoothing": 0.0 },

  "pickup": {                               // Mixer brush / smudge / Procreate wet mix / MyPaint smudge
    "enabled": false, "wet": 0.0, "load": 1.0, "mix": 0.0, "length": 0.5, "blur": 0.0,
    "dilution": 0.0, "charge": 1.0, "attack": 0.0, "grade": 0.0,
    "sampleAllLayers": false, "loadAfterStroke": true, "cleanAfterStroke": false
  },

  "bristle": { "enabled": false, "density": 0.5, "length": 1.0, "thickness": 0.1, "stiffness": 0.5,
               "clumping": 0.0, "shape": "roundPoint" },
  "wear":    { "enabled": false, "rate": 0.0, "shape": "point", "sharpenOnSelect": true },   // erodible
  "pose":    { "tiltX": null, "tiltY": null, "rotation": null, "pressure": null },          // override defaults

  "extensions": { "abr": {}, "procreate": {}, "kpp": {}, "myb": {} }   // raw source params, lossless
}
```

**Rendering contract.**
* Per dab: `alpha = tipMask(tip) · grainCov (if perDab) · noise`, deposited at `flow` into the stroke buffer, coloured by `color`.
* The mask tip is stamped into a mask buffer, and the two are combined with `maskTip.mode`.
* On commit:
  * `out = min(strokeBuf, opacity)`
  * `→ wetEdges/burntEdges → grain (if !perDab, canvas space) → blendMode`

This matches Photoshop's model and is a superset of Procreate's "light glaze" and MyPaint's. Procreate's heavier glaze and blending modes are approximated by `stroke.accumulate` [I].

---

## E. Mapping tables

Fidelity: **=** exact or near-exact, **≈** approximate or needs calibration, **✗** dropped (kept in `extensions`).

### E.1 Photoshop (ABR descriptor) → JoyBrush

**Brush Tip Shape**

| Photoshop | ABR key | JoyBrush | Fid. | Notes |
|---|---|---|---|---|
| Sampled tip bitmap | `samp` + `Brsh.sampledData` | `tip.source=image`, `images[0]`, `imageChannel=luminance` | = | 16-bit → 8-bit or float. |
| Computed tip | `Brsh` class `computedBrush` | `tip.source=procedural`, `corner=1`, `cornerMode=superellipse`, `taper=0` | = | |
| Size | `Brsh.Dmtr` | `tip.size` | = | For image tips, scale = Dmtr / max(w, h). |
| Angle | `Brsh.Angl` | `tip.angle` | = | CCW. |
| Roundness | `Brsh.Rndn` | `tip.procedural.aspect = −(1−Rndn/100)` (procedural); image: pre-squash or `aspect` | = | |
| Hardness | `Brsh.Hrdn` | `tip.hardness`, `falloff=ps` | ≈ | Calibrate the curve. |
| Spacing / on | `Brsh.Spcn`, `Intr` | `tip.spacing`, `spacingMode` (`speed` if off) | = / ≈ | |
| Flip X/Y | `Brsh.flipX/flipY` | `tip.flipX/flipY` | = | |

**Shape Dynamics**

| Photoshop | ABR key | JoyBrush | Fid. | Notes |
|---|---|---|---|---|
| Size Jitter + Control + Min Diameter | `szVr{bVTy,jitter,fStp}`, `minimumDiameter` | `dynamics.size{jitter, sensors, min}` | = | Fade → `fade` sensor, fadeLength = fStp dabs. |
| Tilt Scale | `tiltScale` | `size.sensors[tilt]` curve endpoint | ≈ | |
| Angle Jitter + Control | `angleDynamics` | `dynamics.angle{jitter·360°, follow}` | = | Direction/InitialDirection/Rotation → `follow`. |
| Roundness Jitter + Control + Min | `roundnessDynamics`, `minimumRoundness` | `dynamics.aspect` | = | |
| Flip X/Y Jitter | `flipX`, `flipY` (top-level) | `dynamics.flipJitterX/Y = 0.5` | = | |
| Brush Projection | `brushProjection` | `tip.projection` | ≈ | |

**Scattering**

| Photoshop | ABR key | JoyBrush | Fid. | Notes |
|---|---|---|---|---|
| Scatter + Control | `scatterDynamics` | `scatter.amount = jitter/100`, `amountSensors` | ≈ | Distribution Gaussian-like. |
| Both Axes | `bothAxes` | `scatter.bothAxes` | = | |
| Count | `Cnt ` | `scatter.count` | = | |
| Count Jitter + Control | `countDynamics` | `scatter.countJitter` (+sensor) | = | |

**Texture**

| Photoshop | ABR key | JoyBrush | Fid. | Notes |
|---|---|---|---|---|
| Pattern | `Txtr.Idnt` → `patt` | `grain.image`, `space=canvas` | = | RGB patterns → luminance. |
| Invert | `InvT` | `grain.invert` | = | |
| Scale | `textureScale` | `grain.scale` | = | |
| Brightness | `textureBrightness` | `grain.brightness` | ≈ | Curve unknown. |
| Contrast | `textureContrast` | `grain.contrast` | ≈ | |
| Texture Each Tip | `TxtC` | `grain.perDab` | = | |
| Mode | `textureBlendMode` | `grain.mode` | = / ≈ | Height/LinearHeight per the Krita formulas. |
| Depth, Min Depth, Depth Jitter + Control | `textureDepth`, `minimumDepth`, `textureDepthDynamics` | `grain.depth{base,min,jitter,sensors}` | = | |
| Protect Texture | `protectTexture` | app-level "shared paper grain" flag | ≈ | |

**Dual Brush**

| Photoshop | ABR key | JoyBrush | Fid. | Notes |
|---|---|---|---|---|
| Dual Brush on | `dualBrush.useDualBrush` | `maskTip.enabled` | = | |
| Dual tip / size | `dualBrush.Brsh` (+Dmtr) | `maskTip.tip`, `sizeRatio = Dmtr₂/Dmtr₁` | = | |
| Dual mode | `dualBrush.BlnM` | `maskTip.mode` | ≈ | |
| Dual spacing, scatter, both axes, count, flip | `Spcn`, `useScatter`, `scatterDynamics`, `bothAxes`, `Cnt `, `countDynamics`, `Flip` | `maskTip.tip.spacing`, `maskTip.scatter.*`, `flipJitter` | = | |

**Color Dynamics**

| Photoshop | ABR key | JoyBrush | Fid. | Notes |
|---|---|---|---|---|
| FG/BG Jitter + Control | `clVr` | `color.secondaryMix` | = | |
| Hue / Saturation / Brightness Jitter | `H   `, `Strt`, `Brgh` | `color.hueJitter/satJitter/valJitter` | ≈ | Distribution unknown. |
| Purity | `purity` | `color.purity` | = | |
| Apply Per Tip | `colorDynamicsPerTip` | `color.perDab` | = | |

**Transfer**

| Photoshop | ABR key | JoyBrush | Fid. | Notes |
|---|---|---|---|---|
| Opacity Jitter + Control + Min | `opVr` | `dynamics.opacity` | = | |
| Flow Jitter + Control + Min | `prVr` | `dynamics.flow` | = | |
| Wetness / Mix Jitter | `wtVr`, `mxVr` | `pickup.wet/mix` jitter (extensions if no pickup) | ≈ / ✗ | |

**Brush Pose and toggles**

| Photoshop | ABR key | JoyBrush | Fid. | Notes |
|---|---|---|---|---|
| Brush Pose | `overridePose*`, `brushPose*` | `pose.*` | = | |
| Noise | `Nose` | `stroke.noise = 0.5` (default amount) | ≈ | |
| Wet Edges | `Wtdg` | `stroke.wetEdges = 0.5` | ≈ | |
| Build-up | `Rpt ` | `stroke.buildUp.enabled` | ≈ | Rate unknown; default ~30 dabs/s. |

**Smoothing (tool options)**

| Photoshop | ABR key | JoyBrush | Fid. | Notes |
|---|---|---|---|---|
| Smoothing % | `toolOptions.smoothingValue` | `smoothing.amount` | ≈ | |
| Pulled String | `smoothingRadiusMode` | `smoothing.mode=pulledString` | = | |
| Catch-up / on end / zoom | `smoothingCatchup`, `…AtEnd`, `…ZoomCompensation` | `smoothing.catchUp/catchUpOnEnd/zoomAdjust` | = | |

**Mixer Brush (tool options)**

| Photoshop | ABR key | JoyBrush | Fid. | Notes |
|---|---|---|---|---|
| Wet, Load, Mix, Flow | `wetness`, `dryness`, `mix`, `flow` | `pickup.wet`, `pickup.load (= 1 − dryness?)`, `pickup.mix`, `dynamics.flow.base` | ≈ | dryness ↔ load relation [I]. |
| Load / Clean after stroke; Sample All Layers | `autoFill`, `autoClean`, `sampleAllLayers` | `pickup.*` | = | |

**Special tips**

| Photoshop | ABR key | JoyBrush | Fid. | Notes |
|---|---|---|---|---|
| Bristle tip | `dBrush{Shp ,Dnst,Lngt,thickness,stiffness,clumping,Angl}` | `bristle.*` (+ fallback procedural tip) | ≈ | |
| Erodible tip | `dTips{Shp ,dtipsHardness,…}` | `wear.*` + procedural shape (point → taper=1, flat → aspect, square → corner=0, triangle → taper=1 corner=0) | ≈ | |
| Airbrush tip | `dTips` Shp=5, `dtipsAirbrush*` | soft procedural + `stroke.noise` + `scatter.count` + `projection` | ≈ | |
| Tool opacity, flow, blend | `toolOptions.Opct/flow/Md  ` | `dynamics.opacity.base`, `dynamics.flow.base`, `stroke.blendMode` | = | |

### E.2 Procreate (Brush.archive) → JoyBrush

Value scales marked ≈ are unverified. Build a calibration set by exporting test brushes from Procreate with single sliders at known values.

**Stroke path and stabilisation**

| Procreate UI | Key | JoyBrush | Fid. |
|---|---|---|---|
| Spacing | `plotSpacing` | `tip.spacing` (non-linear map) | ≈ |
| Jitter (lateral/linear) | `plotJitter` (+Tilt/Roll) | `scatter.amount` (+sensors) | ≈ |
| Fall off | `dynamicsFalloff` | `stroke.falloffLength` | ≈ |
| StreamLine / pressure | `plotSmoothing`, `dynamicsPressureSmoothing` | `smoothing.mode=streamline`, `amount`, `pressureSmoothing` | ≈ |
| Stabilization / Motion filtering | `plotMovingAverageStabilization`, `plotFFTSmoothingAmount/Bias` | `smoothing.mode=average|fft` | ≈ |

**Taper**

| Procreate UI | Key | JoyBrush | Fid. |
|---|---|---|---|
| Pressure taper | `pencilTaperStart/EndLength`, `pencilTaperSize/Opacity/Shape`, `taperPressure` | `stroke.taper.*` | ≈ |
| Touch taper | `taperStartLength` … | `stroke.taper.*` (touch variant in extensions) | ≈ |

**Shape**

| Procreate UI | Key | JoyBrush | Fid. |
|---|---|---|---|
| Shape source | `Shape.png` / `bundledShapePath` | `tip.source=image` (built-ins → substitute) | = / ✗ |
| Shape inverted | `shapeInverted` | `tip.imageInvert` | = |
| Touch rotation / follow stroke | `shapeRotation` | `dynamics.angle.follow=direction` (sign/amount ≈) | ≈ |
| Shape scatter (rotation jitter) | `shapeScatter` | `dynamics.angle.jitter` | ≈ |
| Randomized | `shapeRandomise` | `dynamics.angle.jitterMode=perStroke` | ≈ |
| Count / count jitter | `shapeCount`, `shapeCountJitter` | `scatter.count/countJitter` | = |
| Flip X/Y | `shapeFlipXJitter/YJitter` | `dynamics.flipJitterX/Y` | = |
| Roundness graph | `shapeAngle`, `shapeRoundness` | `tip.angle`, `procedural.aspect` / image squash | = |
| Pressure / tilt roundness | `dynamicsPressureShapeRoundness(+Min)`, `dynamicsTiltShapeRoundness(+Min)` | `dynamics.aspect.sensors` | ≈ |
| Azimuth / barrel roll | `shapeAzimuth`, `shapeRoll(+Mode)` | `dynamics.angle.follow = tiltAzimuth | barrel` | ≈ |
| Orient to screen | `oriented` | app-level | ≈ |

**Grain**

| Procreate UI | Key | JoyBrush | Fid. |
|---|---|---|---|
| Grain source | `Grain.png` / `bundledGrainPath` | `grain.image` | = / ✗ |
| Moving vs Texturized | `textureMovement` (and/or `textureApplication`) | `grain.space = rolling | canvas`, `movement` | ≈ |
| Scale / Zoom | `textureScale`, `textureZoom` | `grain.scale`, `followsSize` | ≈ |
| Rotation | `textureRotation`, `textureOrientation`, `grainOrientation` | `grain.rotateWith`, `rotation` | ≈ |
| Depth / min / jitter | `grainDepth`, `grainDepthMinimum`, `grainDepthJitter` (+`textureDepthTilt`) | `grain.depth{base,min,jitter,sensors}` | = |
| Offset jitter | `textureOffsetJitter` | `grain.offsetJitter` | = |
| Blend mode | `grainBlendMode` | `grain.mode` | ≈ |
| Brightness / Contrast / Invert | `textureBrightness`, `textureContrast`, `textureInverted` | `grain.brightness`, `grain.contrast = 1 + v`, `grain.invert` | ≈ |

**Rendering**

| Procreate UI | Key | JoyBrush | Fid. |
|---|---|---|---|
| Rendering mode | `renderingModulatedTransfer`, `renderingRecursiveMixing`, `renderingMaxTransfer` | `stroke.accumulate` | ≈ |
| Flow | `dynamicsGlazedFlow` | `dynamics.flow.base` | ≈ |
| Wet edges / Burnt edges | `wetEdgesAmount`, `burntEdgesAmount`, `burntEdgesBlendMode` | `stroke.wetEdges`, `stroke.burntEdges/Mode` | ≈ |
| Blend mode | `blendMode`, `extendedBlend` | `stroke.blendMode` | = |

**Wet mix** (all ✗ if the pickup stage is not built)

| Procreate UI | Key | JoyBrush | Fid. |
|---|---|---|---|
| Dilution | `dynamicsMix` (?) | `pickup.dilution` | ≈ |
| Charge | `dynamicsLoad` | `pickup.charge` | ≈ |
| Attack | `dynamicsWetAccumulation` (?) | `pickup.attack` | ≈ |
| Pull | `dynamicsSmudgeAccumulation` (?) | `pickup.length` | ≈ |
| Grade | `dynamicsMixSoftening` (?) | `pickup.grade` | ≈ |
| Blur / jitter, Wetness jitter | `dynamicsBlur`, `dynamicsBlurJitter`, `dynamicsWetnessJitter` | `pickup.blur` (+jitter) | ≈ |

**Colour**

| Procreate UI | Key | JoyBrush | Fid. |
|---|---|---|---|
| Stamp colour jitter | `dynamicsJitterHue/Saturation/Lightness/Darkness`, `jitterSecondary` | `color.*Jitter`, `perDab=true`, `secondaryMix.jitter` | ≈ |
| Stroke colour jitter | `dynamicsJitterStroke*`, `jitterStrokeSecondary` | same with `jitterMode=perStroke` | ≈ |
| Colour pressure / tilt / roll | `dynamicsPressureHue…`, `dynamicsTiltHue…`, `dynamicsRollHue…` | `color.hsvSensors`, `secondaryMix.sensors` | ≈ |

**Dynamics and Apple Pencil**

| Procreate UI | Key | JoyBrush | Fid. |
|---|---|---|---|
| Speed size / opacity | `dynamicsSpeedSize`, `dynamicsSpeedOpacity` | `size/opacity.sensors[speed]` | ≈ |
| Jitter size / opacity | `dynamicsJitterSize`, `dynamicsJitterOpacity` | `size/opacity.jitter` | = |
| Pressure size / opacity / flow | `dynamicsPressureSize(+Curve)`, `dynamicsPressureOpacity(+Curve)`, `dynamicsPressureOpacityTransfer` | `size/opacity/flow.sensors[pressure]` | = |
| Pressure bleed | `dynamicsPressureBleed(+Curve)` | `stroke.wetEdges` sensor / extensions | ≈ |
| Tilt graph | `dynamicsTiltAngle` | tilt curve breakpoint | ≈ |
| Tilt opacity / size / gradation / bleed | `dynamicsTiltOpacity/Size/Gradation/Bleed` | `*.sensors[tilt]`; gradation → `grain.levelGradient` | ≈ |
| Size compression | `dynamicsTiltCompression` | `grain.followsSize=false` | ≈ |
| Barrel roll size / opacity / bleed | `dynamicsRollSize/Opacity/Bleed(+Parameters)` | `*.sensors[barrel]` | ≈ |

**Properties, dual brush, materials, metadata**

| Procreate UI | Key | JoyBrush | Fid. |
|---|---|---|---|
| Max/min size and opacity | `maxSize`, `minSize`, `maxOpacity`, `minOpacity` | `tip.sizeRange`, opacity range | = |
| Dual brush | `Sub01/…`, `dualBlendMode` | `maskTip.*`, `maskTip.mode` | ≈ |
| Materials (3D) | `metallic*`, `roughness*`, `height*` | extensions | ✗ |
| Author | `authorName`, `creationDate` | `meta.author` | = |

### E.3 Other formats → JoyBrush (summary)

| Source | Maps to |
|---|---|
| MyPaint `.myb` | `tip.procedural` (hardness, `elliptical_dab_ratio/angle` → aspect/angle, `radius_logarithmic` → size) + `dynamics.*` from `inputs` (pressure, speed1/2, random, direction, tilt, barrel_rotation, stroke → fade) + `scatter.amount` (`offset_by_random`) + `color.*` (`change_color_*`) + `pickup` (`smudge`, `smudge_length`) + `smoothing` (`slow_tracking`). Exact-ish except spectral `paint_mode` and `posterize`. |
| Krita `.kpp` (paintbrush) | `brush_definition` → tip; sensors → dynamics (near 1:1); `Texture/*` → grain (Krita's PS modes); `MaskingBrush/*` → maskTip; Scatter, Rotation, Mirror → scatter/angle/flip; `colorsmudge` engine → pickup. Other engines ✗. |
| GIMP `.gbr` / `.gih` / `.vbr` | tip.image (grey or RGBA) / imageArray + `arraySelect` / procedural (circle, square, diamond ≈ corner=0 + 45° angle). |
| CSP `.sut` | `Brush*` columns → tip/dynamics; `*Effector` blobs → sensors; `BrushPatternImageArray` → imageArray; `Texture*` → grain; `BrushWaterColor` → pickup/wetEdges; tip images from `.layer` (partial). |

---

## F. Recommended import order (by value ÷ effort)

1. **ABR v6–v10 (sampled + computed + patterns + full dynamics).** The largest ecosystem, and the format every competitor imports.
   * Port ag-psd's `abr.ts` + `descriptor.ts` (MIT) to Java for Android; use it unmodified in the web prototype.
   * Add v1/v2 from abrkit's documented layout.
   * Map per E.1.
   * Effort: ~1.5–2 k lines of Java.
2. **Procreate `.brush` / `.brushset`.** A huge marketplace, and most iPad artists moving to Android own packs.
   * zip + dd-plist + UID resolver.
   * Map per E.2 using p2k (GPL-3) as the key reference.
   * Ship CC0 substitutes for missing built-ins.
   * Build a calibration corpus for the ≈ scales.
3. **MyPaint `.myb`.** Trivial JSON. The bundled CC0 set is an instant library of good procedural brushes, and the model validates the sensor matrix.
4. **Krita `.kpp` / `.bundle` (paintbrush and colorsmudge engines only).** Open format with CC-BY/CC0 content (Revoy).
5. **GIMP `.gbr` / `.gih` / `.vbr`.** An afternoon's work; gives multi-image tips.
6. **Clip Studio `.sut`.** SQLite parameters are easy; tip `.layer` decoding is the risk. Do it after demand is proven.
7. **Photoshop `.tpl`.** Probe it once ABR works; it is probably the same descriptor machinery.
8. **Not feasible:** Infinite Painter `.prbr`, ArtRage, Concepts, ibisPaint, Affinity.

---

## Sources

**Source code read (cloned or fetched into the scratchpad)**
- Krita (GPL-2.0+), invent.kde.org/graphics/krita:
  - `libs/brush/kis_abr_brush_collection.cpp` (ABR tip loader, v1/v2 and v6.1/6.2, dynamics ignored)
  - `libs/image/brushengine/kis_paintop_preset.cpp` (kpp = PNG text chunks `version` and `preset`)
  - `plugins/paintops/libpaintop/kis_texture_option.cpp`, `KisTextureMaskInfo.cpp` (texture modes, brightness and contrast)
  - `libs/ui/tool/strokes/KisMaskingBrushCompositeOp.h` (Subtract, Height, Linear Height, Height (Photoshop), Linear Height (Photoshop), Hard Mix formulas)
  - `libs/image/kis_circle_mask_generator.cpp`, `kis_gauss_circle_mask_generator.cpp` (hardness and fade)
- GIMP (GPL-3.0+), gitlab.gnome.org/GNOME/gimp:
  - `app/core/gimpbrush-load.c` (gbr + ABR v1/2/6/10 tips)
  - `app/core/gimpbrushgenerated.c` (hardness exponent + `gauss()`)
  - `app/core/gimpbrushgenerated-load.c` (vbr)
  - `app/core/gimpbrushpipe.c`, `gimpbrushpipe-load.c` (gih selection modes)
- libmypaint (ISC): `mypaint-tiled-surface.c` (`render_dab_mask` hardness segments), `brushsettings.json` (inputs and settings).
- mypaint-brushes: `Licenses.dep5` (CC0-1.0 for brushes/*).
- ag-psd (MIT), https://github.com/Agamnentzar/ag-psd, `src/abr.ts` (ABR v6/7/9/10; full typed brush model; `bVTy` table; bristle and erodible keys; tool options and smoothing keys).
- abrkit (MIT), https://github.com/focale-editor/abrkit: `docs/ABR.md`, `lib/src/codec/abr_descriptor_mapper.dart`, `lib/src/model/abr_brush.dart`; https://pub.dev/packages/abrkit
- brushkit (MIT), https://github.com/pmwoz/brushkit: `crates/abr/src/parser.rs`, `crates/abr/src/lib.rs` (tip families, measured Shp table), `crates/preview/src/synth.rs` (hardness approximation); issues #16 and #152 (XML Brush.archive).
- abr-to-krita (MIT), https://github.com/Pawel-9215/abr-to-krita: `abr_convert/reader.py`, `semantic.py`, README.
- brushConverter (MIT), https://github.com/yxm1122/brushConverter: `docs/parameter-mapping.md`, `docs/developer-guide.md`, `docs/photoshop-texture-brightness-contrast-research.md`.
- ABR-Viewer / abr-parser (MIT), https://github.com/SonyStone/ABR-Viewer: `research.md`.
- freyalupen, Procreate to Krita Brush Converter (GPL-3.0+), https://invent.kde.org/freyalupen/procreate-to-krita-brush-converter: `procreate_to_kpp.py` (full Procreate key list), `procreate/procreate_brush_parser.py`, `abr/abr_parser.py`, `sut/sut_parser.py`, `readme.md`.
- tohsakrat/Brush-Converter (CC BY-NC 4.0; read for structure only), https://github.com/tohsakrat/Brush-Converter
- PSBrushExtract (AGPL-3.0), https://github.com/MorrowShore/PSBrushExtract
- Deevad/deevad-krita-brushpresets (CC-BY 4.0), https://github.com/Deevad/deevad-krita-brushpresets

**Documentation and web**
- Adobe Substance 3D Painter, "Photoshop Brush Parameters Compatibility": https://experienceleague.adobe.com/en/docs/substance-3d-painter/using/painting/presets/photoshop-brush-presets/photoshop-brush-parameters-compatibility
- Adobe Photoshop help pages cited via search snippets (direct fetch blocked, HTTP 403):
  - https://helpx.adobe.com/photoshop/using/brush-settings.html
  - https://helpx.adobe.com/photoshop/using/creating-textured-brushes.html
  - https://helpx.adobe.com/photoshop/using/painting-mixer-brush.html
  - https://helpx.adobe.com/photoshop/desktop/repair-retouch/clean-restore-images/create-smoother-more-polished-brush-strokes-with-stroke-smoothing.html
- Photoshop Essentials, Texture options: https://www.photoshopessentials.com/basics/photoshop-brushes/brush-dynamics/texture/
- Adobe community, "Where to find .abr (brushes) file format specification": https://community.adobe.com/t5/photoshop/where-to-find-abr-brushes-file-format-specification/td-p/3537426
- Just Solve the File Format Problem, "Photoshop brush": http://justsolve.archiveteam.org/wiki/Photoshop_brush (503 at fetch time; cited by the tohsakrat and ABR-Viewer docs)
- DiVerdi, Krishnaswamy, Hadap, "Industrial-strength painting with a virtual bristle brush", VRST 2010: https://research.adobe.com/publication/industrial-strength-painting-with-a-virtual-bristle-brush ; PDF: http://www.expresii.com/files/theme/IndustrialStrengthp119-diverdi.pdf
- CS6 erodible and airbrush tips: https://tinytutorials.wordpress.com/2013/01/14/photoshop-cs6-erodible-brush-tips/ ; https://www.agitraining.com/adobe/photoshop/tutorials/using-the-new-brush-tips-in-photoshop-cs6
- Procreate Handbook, Brush Studio Settings: https://help.procreate.com/procreate/handbook/brushes/brush-studio-settings
- Krita Artists, "Procreate brush import plugin prototype": https://krita-artists.org/t/procreate-brush-import-plugin-prototype/104894
- Krita manual, Texture: https://docs.krita.org/en/reference_manual/brushes/brush_settings/texture.html
- Infinite Painter import/export: https://docs.infinitestudio.art/painter/data/import-export/
- ArtRage customizable brushes: https://www.artrage.com/manuals/customizable-brushes/ ; ArtRage ABR import thread: https://forums.artrage.com/showthread.php?54215-Error-on-Abr-Brushes-import-on-ArtRage-5-abr-brushes-version-or-format
- ibisPaint brush QR export/import: https://ibispaint.com/lecture/index.jsp?no=137
- David Revoy brush licences: https://www.davidrevoy.com/article742/krita-4-extras-brush-presets-pack ; https://www.davidrevoy.com/article1060/krita-brushes-2025-01-bundle
- Krita hairy presets (Ramón Miranda): https://krita.org/en/posts/2013/hairy-brushes/
- K. M. Alexander CC0 brushes: https://kmalexander.com/2020/10/21/homann-a-free-18th-century-cartography-brush-set-for-fantasy-maps/
- Catherine's Basic Procreate Brushes (CC0): https://coborski.itch.io/catherines-basic-procreate-brushes
- CJEU C-406/10 *SAS Institute v World Programming* (functionality and file formats not protected by copyright); 17 U.S.C. §1201(f); EU Directive 2009/24/EC art. 6. General legal background, not verified in this session.
