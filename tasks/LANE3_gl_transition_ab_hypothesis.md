# Lane 3 — Export-side GL transition A/B proof: targeted hypothesis (Opus, 2026-07-17)

Read-only code analysis done BEFORE any device export, so the A/B proof tests a
specific hypothesis instead of blindly diffing (per memory: absolute-geometry, not
symmetric).

## What the code already handles (so a naive flip regression is UNLIKELY)
- `GlTransitionShaderLoader.exportTemplate(ratio)` (line ~177): samples the video
  texture `uVideoTexSampler0` UNFLIPPED (`texture2D(sampler, uv)`) — correct because
  media3 GlEffect frame textures are GL bottom-origin. Contrast `previewTemplate()`
  which DOES `1.0 - uv.y` because preview uploads Android bitmaps top-row-first.
  → the two paths deliberately diverge; each matches its own texture origin.
- `GlTransitionFrameOverlay.drawFrame` (line ~188): PRE-FLIPS the "to"/overlay card
  on the Android canvas (`canvas.scale(1f,-1f,...)`) so the uploaded overlay texture
  is GL-correct. This was the original "flips vertical" fix. So the to-clip is upright.
- Export bakes `const float ratio = outW/outH` into the shader (directional/aspect
  effects get the real canvas ratio); preview forces ratio=1.

## The REMAINING plausible asymmetry to prove/disprove
The "from" clip (video sampler) FILLS the full output quad (media3 scales the decoded
frame to the output surface). The "to" clip (overlay bitmap) is aspect-**letterboxed**
INSIDE the output via `GlTransitionFrameOverlay.fitRect` (line ~211: min-scale fit,
centered, black bars). So when a source clip's aspect ≠ the export canvas aspect:
  - from-clip: stretched/cropped to fill (whatever media3's scaleType does)
  - to-clip:   letterbox-fit with bars
→ mid-transition the two halves can be at DIFFERENT scale/position. A cross-fade would
  show the incoming clip smaller (with bars) than the outgoing clip. Directional wipes
  would reveal a size jump at the seam.

## A/B recipe when device is free (after Lane 1 build/install)
1. Sandbox project.json: two clips of DIFFERENT aspect than the export canvas (e.g. a
   9:16 clip + a 16:9 clip, export at 1080x1920), one GL transition (e.g. `crosswarp`
   or a plain `fade`) at the seam, duration ~1000ms. Back up project.json first
   (run-as cp), inject, export.
2. Extract a frame at progress≈0.5 (mid-transition) via ffmpeg at the exact seam ts.
3. Measure the from-region vs to-region absolute normalized geometry (cx/cy/w/h). If
   the to-region is letterboxed (w/h < 1, bars) while the from-region fills, that's the
   confirmed bug. Compare against AUTHORED expectation: both clips should composite at
   the SAME fit convention (whatever the non-transition export uses for a single clip).
4. Cross-check: export the SAME two clips with NO transition (hard cut) and see how each
   clip is fit when shown alone. The transition to-clip must match that single-clip fit.
   If single-clip export letterboxes too, then fitRect is CORRECT and there's no bug.

## Fix direction IF confirmed
Make `fitRect` match the single-clip export fit (probably fill/crop, not letterbox), or
letterbox both sides identically. Do NOT touch media3-patched. The overlay baker is app
code (`GlTransitionFrameOverlay`) so the fix is app-side.
