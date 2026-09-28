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
