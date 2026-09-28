# JB-1.20 — PC Brush Lab (one HTML file, runs the shared brush shaders)

| | |
|---|---|
| **Tier** | T2 (vision helpful for checking screenshots, not required) |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-1.01, JB-1.02 (done: `joybrush/shaders/jb_tip.glsl`, `jb_grain.glsl`). Uses JB-1.03's PNG if present, else generates a fallback noise in JS. |
| **Owner area** | `tools/brushlab/BrushLab.html` (new), `tools/brushlab/README.md` (new) |
| **Estimated size** | ~700 lines (HTML + JS + GLSL wrapper) |

## Goal
The owner tunes the LOOK of brushes on his PC with his pen: sliders, instant redraw, pressure and
tilt from the browser. It must run the **exact same shader files** as the phone, loaded from
`joybrush/shaders/`, never a copy. Feel and speed are judged on the phone, not here.

## How it runs
From the repo root: `python -m http.server 8777`, then open
`http://127.0.0.1:8777/tools/brushlab/BrushLab.html`. The page `fetch`es
`/joybrush/shaders/jb_tip.glsl` and `/joybrush/shaders/jb_grain.glsl` and concatenates them into
its fragment shader source after its own `#version 300 es` + precision header. If the fetch fails,
show a clear message telling the user to start the server — do not embed copies.

## Decisions
1. **WebGL2** only. Stroke buffer = RGBA16F texture if `EXT_color_buffer_float` exists, else RGBA8.
2. **Pen input** via Pointer Events (`pointerType === "pen"`), using `getCoalescedEvents()` for every
   sample. Convert to PenSample fields:
   - pressure = `e.pressure` (mouse: 0.5 while pressed)
   - if `e.altitudeAngle` is defined: tilt = π/2 − altitudeAngle, azimuth = `e.azimuthAngle`
     (convert to our convention: 0 = +x, y down — azimuthAngle already uses 0 = +x, clockwise with y
     down, so use it as is, wrapped to −π..π)
   - else from tiltX/tiltY (degrees): `tx = tan(tiltX°)`, `ty = tan(tiltY°)`,
     tilt = atan(sqrt(tx²+ty²)), azimuth = atan2(ty, tx)
   - barrel = `e.twist` in radians if the pen reports it (non-zero ever), else the **Fake rotation**
     slider's value when enabled, else NaN.
3. **Dab placement:** spacing = brush spacing × diameter, carrying the leftover distance between
   events (so spacing is exact regardless of event rate). Linear-interpolate all channels between
   samples. (Smoothing is not reimplemented here — raw input is fine for judging the mark.)
4. **Tip direction:** follow-direction = stroke direction with distance damping
   (`k = 1 − exp(−step/6px)`, blend unit vectors, as `DirectionTracker.kt` does); when tilt > 0.17 use
   the lean direction.
5. **Dab shader:** instanced quads, one per dab, size = diameter + 2px. Per-dab attributes: centre,
   radius, angle, pressure, tilt, lean dir, flow. Fragment: `cov = jb_tipCoverage(...)`; if tip
   texture on: sample the grain texture in dab space (rotate with angle, scale), `lvl =
   jb_grainLevel(depth, cov, localN, leanDirLocal, sin(tilt), tiltGradient, radial)`, `cov =
   jb_grainedCoverage(cov, jb_heightCoverage(h, lvl, edge))`; if paper grain on: sample in canvas
   space and multiply its `jb_heightCoverage` (level = paper depth × pressure). Output alpha =
   cov × flow.
6. **Accumulation into the stroke buffer:**
   - wash (default): `dst.a = max(dst.a, min(src.a, opacity))`-style "alpha darken": use blend
     `MAX` on alpha with src.a pre-multiplied by opacity cap — implement as
     `gl.blendEquationSeparate(FUNC_ADD, MAX)` for colour/alpha with colour written only where alpha
     increases (simple approach acceptable: colour is the brush colour, constant within a stroke).
   - buildup: normal over-blending, then capped at opacity on commit.
   On pointer-up, composite the stroke buffer onto the canvas texture (normal blend), clear it.
7. **Controls (left panel, scrubbable number fields):** size, flow, opacity, spacing (%), hardness,
   corner exponent (0.5–16), taper (0–1), aspect (−1…1), angle, follow direction (checkbox), min px;
   tip texture: on/off, scale, depth, edge, tilt gradient (−2…2), radial; paper grain: on/off, scale,
   depth, edge; wash/buildup; brush colour; paper colour; Fake rotation (checkbox + angle slider);
   Clear; **Save brush.json / Load brush.json** in the JB-0.03 format (only the fields this lab uses;
   keep unknown fields of a loaded file and write them back unchanged).
8. **Phone budget meter** (top bar): dabs per second and average dab pixel area over the last
   second; turns amber above 20,000 dabs/s or above 60 million dab-pixels/s, with the text "may be
   slow on a Note 9". It is a warning, not a limit.
9. Canvas 1600×1000 CSS px at devicePixelRatio. Dark UI using Joy Creator colours: ground `#000000`,
   panel `#111114`, line `#2C2C35`, text `#E4E4E7`, dim `#A1A1AA`; fonts IBM Plex Sans / Plex Mono
   from Google Fonts with system fallbacks.

## Verification
- Open it via the http server in Chromium (Playwright is fine: `tools/brushlab/README.md` gives the
  command) and take screenshots of: a pressure ramp stroke with the default ink, a tilted stroke with
  tip texture on (edge 0), and the same with edge 1. Attach the screenshots to your report.
- Check the browser console has no errors.

## Do not
- Do not copy the shader code into the HTML. Do not modify `joybrush/shaders/*` — if they need a
  change, write a Question.
- No frameworks, no build step, no npm. One HTML file (+ README).

## Definition of done
Screenshots + clean console · only owner-area files · commit `JB-1.20: PC Brush Lab` · ROADMAP row →
🟧 Built.

## Questions

*Note: an earlier draft of this section asked for JB-0.03's key names. That is **answered** — the
lab now mirrors `BrushPreset.kt` field for field (see 1 below), and the provisional stamp is gone
from both the page and the README.*

### 1. RESOLVED — Save/Load now conforms to `BrushPreset.kt`
The lab holds a complete mirror of `BrushPreset` and writes it whole, so `BrushJson.decode()`
reads it and `encodeDefaults` means no key is missing. The mapping that mattered, because my
provisional guesses were wrong in *shape*, not just in spelling:

| Panel | Real field | My earlier guess was |
|---|---|---|
| Size | `size: Param` (`base`/`inputs`/`combine`) | a flat number — **would have been rejected** |
| Flow, Opacity | `flow` / `opacity`: `Param` | flat numbers — rejected |
| Hardness, Angle | `tip.hardness`, `tip.angle`: `Param`, **angle in degrees** | flat numbers; I had invented `angleRadians` |
| Spacing | `spacing: Float`, a **fraction** (0.04), not a percent | `spacingPercent: 10` |
| Wash / buildup | `accumulate: "wash" \| "buildup"` | a `buildup: Boolean` |
| — | `format: "joybrush.brush"`, `version: 1` | `brushSchemaVersion: 1` |
| — | **`id` and `name` are required, no Kotlin default** | I emitted neither — the phone would have thrown on decode |
| Tip / paper grain | `tipTexture` / `paperGrain`: `GrainSpec` | flat keys |

Defaults now come from `BrushPreset`, not from my taste: spacing 4 % (was 10), hardness 0.9
(was 0.85), flow 1 (was 0.6), `followDirection` **false** (was true), grain `edge` 0.3 and
`depth` 1. Note depth 1 is nearly solid — that is the contract default, so grain only becomes
visible once you drag depth down. `TipSpec.source`/`image` and `GrainSpec.source`/`image` are
always written `procedural`/`cloud` + `null`, because those are what the lab renders.

### 2. Still open — the lab cannot evaluate input curves, only write them
Reading `BrushPreset` resolved *how* pressure reaches size: `Param.inputs = [{input:"pressure",
curve:[[0,0],[1,1]]}]`. The lab therefore writes that curve when **Size by pressure** is `linear`
(default) and `[]` when it is `off`, and it draws the matching behaviour on screen — so the file
and the picture always agree. **What remains open:** a phone brush carrying a *shaped* curve
(a hard S-curve, say) will draw differently in the lab, because the lab has no curve evaluator and
reads every param as its flat `base`. Loading reports which curves it found, and every curve
except `size.inputs` is written back untouched — so nothing is lost, it is just not previewed.
Does the lab need a curve editor to be useful to you, or is flat-base plus the `linear` shortcut
enough for judging a look?

### 3. Still open — what did pressure drive *before* the contract told me?
Decision 5 lists `pressure` as a per-dab attribute and never says what it affects. I have it doing
**both** `radiusPx = pressure × size / 2` and `jb_grainLevel(depth × pressure, ...)`. Size is
needed or the required "pressure ramp with the default ink" screenshot is a flat ribbon, since
with tip texture off grain depth is invisible. Question 2 covers the size half; what is still
unconfirmed is whether **grain depth is also meant to follow pressure** on the phone, or whether
`tipTexture.depth.inputs` is supposed to carry that instead.

### 4. NEW — `BrushPreset` has no ink colour, so brush/paper colour have nowhere to go.
`color: ColorJitter` is a *jitter* (hue/saturation/value/perStroke), not a colour. The lab's
**brush colour** and **paper colour** are therefore parked in `extensions` (which the contract
defines as `Map<String,String>` for exactly this — lossless storage outside the preset), alongside
**fake rotation**. Is that the right home, or should `BrushPreset` grow a real `colour` field?
Fake rotation is lab-only by nature (most S Pens report no barrel) — the contract expresses barrel
the other way round, as `angle.inputs = [{input:"barrel", …}]`, which the lab does not write.

### 5. NEW — `blend: "erase"` has no lab representation.
The lab always renders `normal` blend. An erase brush loads and round-trips intact but is
previewed as if it painted. Not a data problem; a fidelity gap. Does the lab need an eraser mode
to be useful, or is erasing out of scope for a look-tuning tool?

### 6. NEW — preserved but never previewed: `scatter`, `sizeJitter`, `angleJitter`, `color`,
`smoothing`, `license`, `author`, `sourceFormat`, `engine`.
These round-trip byte-identically (the lab keeps the whole preset object and only writes the
fields it drives), so nothing is lost. But if you tune a brush that leans on scatter or jitter,
the lab will not show you what it looks like. Flagging rather than guessing: out of scope for
JB-1.20?

### 7. The wash blend writes colour with `MAX`, which is only right for a constant colour.
Decision 6 sanctions this ("simple approach acceptable: colour is the brush colour, constant
within a stroke"), and the colour is constant per stroke here, so it is correct as built. Worth
recording that the phone's wash cannot do this trick if it ever wants per-dab colour.

### 8. Minor, not blocking: colour space.
Both surfaces premultiply and over-blend in raw sRGB values with no linearisation. That matches as
long as the phone does the same. If the phone blends in linear space, washes will look slightly
heavier in the lab than on the phone.

### 9. Verification I could NOT do — the visual check is OUTSTANDING, not done.
The orchestrator ran `node --check` on the extracted `<script>` block and **it parses**, so the
syntax is sound. Everything below is still unverified: the three screenshots and the clean-console
check in the Verification section. No browser was available to me and no GLSL in this file has ever
been compiled by a driver. Treat this task as built-but-unverified until someone draws on it.
