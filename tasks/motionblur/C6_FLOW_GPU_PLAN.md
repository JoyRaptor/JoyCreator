# C6 plan: porting the flow core to the GPU (STU-7, §9 step 5)

**Author:** Claude (Opus 5.5), 2026-11-22. For Codex. The Java oracle is built and tested:
`studiokit/.../motion/flow/` with the `run-motionflow` suite. This page says how its GPU twin
(`GpuPyramidFlow`, ES 2, fragment-only) should be built and judged. Change any of it by posting why
in the channel.

## What exists (branch `claude/motionblur-flow-20261122`)
| Class | What it fixes in place |
|---|---|
| `FlowField` | The vector convention (`p_to = p + (u, v)`), units (this grid's px), the PTS pair, `scaledTo`, and `displacementScale(windowUs, speed)`: the only conversion from source-pair flow to shutter smear (2x, reverse, freeze). |
| `LumaImage` | Rec. 709 luma in coded space, and the `[1 3 3 1]` half-res kernel with centre convention. |
| `PyramidFlowEstimator` | DIS-style coarse-to-fine patch inverse search with mean normalisation, never-worse steps and error-weighted densification. `Settings.DEFAULT` = 6 levels max, 8 px patch, stride 4, 12 iterations, coarsest level ≥ 12 px. **Same inputs give the same field; identical frames give exactly zero.** |
| `FlowConfidence` | Forward/backward consistency (the Sundaram threshold), photometric agreement and texture (min eigenvalue, τ = 1e-4). Multiplied. |
| `GlobalMotionFit` | Deterministic RANSAC affine plus weighted refinement, for "Camera move only" and the fallback. CPU only, and it stays CPU (a few hundred samples). |
| `SourceFrameRing` | Which decoded source frames may be paired, flushing on seek, cut, gap, shape change and GL loss, plus a generation for stale async work. Host-agnostic. The GL host owns the textures. |

Measured on the synthetic suite (160×120):
- identity is exactly 0
- 3.4 px translation: mean error < 0.15 px
- 17 px: < 0.3 px (fails without the pyramid)
- zoom 1.05 + roll 2.5°: < 0.3 px
- a +0.12 flash: < 0.2 px (fails without mean normalisation)
- covered background: mean confidence < 0.3 (fails without the consistency check)
- open background: > 0.6
- the camera fit ignores a 35 % crossing region (fails with plain least squares)

## GPU passes (preview: 1/8 res, e.g. 240×135 for 1080p; export: 1/4)
Every pass is a small `#version 100` fragment program (Note 9 rule: no uber-shader). All targets
are RGBA8: velocity packed with `VelocityPack` (12+12 bit at 1/16 px, ±128 px), which is plenty at
1/8 res.
1. **Luma + pyramid.** One pass from the latched OES/2D source writes level 0 luma (R8, or the R
   channel of RGBA8). One `[1 3 3 1]` half pass per level, at most 6. Copy the oracle's kernel and
   centre convention exactly.
2. **Patch search, per level, coarse to fine.** Render target = the **patch grid** (one fragment per
   patch: `ceil((w-8)/4)+1` × `ceil((h-8)/4)+1`, about 59×33 at the finest 1/8 level). Each
   fragment:
   - reads its initial vector from the upsampled previous level;
   - loops 8×8 over the template (fixed loop bounds);
   - builds H and runs up to 12 Gauss-Newton steps, with the "never worse" rule.

   That is 12 × 64 warped taps per patch at most. Fixed iteration count with an early freeze (keep
   the value, keep looping), because the driver does not skip per-pixel branches. Expect this to
   be the cost centre: measure it first.
3. **Densify.** Render target = the level's pixel grid. Each pixel gathers the at most 4 (stride 4,
   patch 8 → 2×2) patches covering it and computes the error-weighted average, exactly the
   oracle's weight `1 / max(1/255, |I1(x+u) - I0(x)|)`.
4. **Upsample to the next level** (`scaledTo`: bilinear, vectors ×2).
5. **Backward field** (same passes, frames swapped). Preview only needs backward tracking (Previous):
   one direction live. Export runs both.
6. **Confidence.** One pass: consistency (sample the other field at the landing point), photometric
   term, and texture from a 5×5 structure tensor. Write confidence into the B channel of a second
   RGBA8 target, or alongside the velocity if the packing allows.
7. **Readback for the camera fit**, only when "Camera move only" or the fallback is on: read a
   small subsampled grid (every 4th pixel) with `glReadPixels`, off the render thread's critical
   path (PBOs are ES 3 only, so on ES 2 read one frame late). Fit on the CPU.

## How it is judged

**The footage reference tool is built** (`tools/motionref/MotionRef.java`, self test `tools/motionref/run-motionref.sh`): it matches an analytic smear exactly (0/255). Its negative controls are averaging in coded sRGB and whole frames only. Recipe: shoot a sharp 240 fps clip (fast shutter), `ffmpeg -i clip.mp4 in/%06d.png`, then `java MotionRef expose in 240 30 180 -90 out`, which writes `true_*` (the reference blur) and `sharp_*` (the 30 fps input the app blurs). Render the app's blur of `sharp_*`, then `java MotionRef compare out app true_ blur_` for MAE and PSNR. The 20-shot blind A/B and the estimator bake-off both use these pairs.

- **Oracle parity (JVM + device).** Feed the same two luma frames (synthetic scenes from
  `FlowCoreTest`, plus 10 real 1/8-res frame pairs from the high-speed reference shoot) to the oracle
  and the GPU version. Read back the GPU field. Pass: mean |Δ| ≤ 0.1 px and the 99th percentile
  ≤ 0.5 px on textured, confident pixels. Confidence differs by ≤ 0.05 on average.
  - **Negative control:** the GPU version with its mean normalisation removed must fail the flash
    pair.
- **Cost on the Note 9** (MotionBench, GPU timer queries):
  - the live backward field at 240×135 has a budget of **≤ 3 ms P95**, confidence included;
  - report each pass separately;
  - if the patch search is over budget, try in this order: 8 iterations, then stride 6, then 5
    levels. Re-run parity each time; never ship a setting the oracle has not also been run with.
- **Bake-off** (spec §6): OpenCV `DISOpticalFlow` (preset FAST) on the same pairs, timed including
  the GPU→CPU copy. Same parity metric against the high-speed ground truth, not against the oracle.
  The winner ships for export analysis; the live preview path stays GPU.

## Wiring (later C6 steps; not started)
- **Live preview:**
  - `FxPreviewTextureView` copies each latched source frame (before any FX) into a texture owned by
    a `SourceFrameRing` (capacity 2), keyed by decoder PTS and clip id.
  - Flush on the events listed in the ring's javadoc.
  - The flow runs when a frame has a valid previous; otherwise the frame is drawn sharp.
- **Export:** a capacity-3 ring fed by the sequential reader (C1 report: copy the OES frame;
  three 1080p RGBA8 copies are about 23.7 MiB, but at 1/4 luma it is about 1.5 MiB per frame), so
  tracking can be centred.
- **Into the blur:** at a clip, the flow is converted by `displacementScale` (shutter window,
  `TimeMap` speed), pushed through the clip's transform, and written into the velocity target the
  reconstruction reads (spec §7.1). Low confidence goes toward the camera fit, then toward sharp.
