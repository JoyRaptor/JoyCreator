# C2 SDR spine export checkpoint

STU-7 · 2026-11-22 · Development branch `codex/motionblur-20261122`

The main video clip's authored position, scale and rotation drive a six-sample, centered 180° exposure. Each export frame supplies the isolated post-FX texture. The shutter evaluates six placements of that texture before clip opacity and overlying content. The shader uses inverse UVs and a swept footprint. The saved clip setting defaults off. Stationary transforms use the existing sharp pass. This checkpoint uses the nominal 30 fps SDR shutter clock.

## Snapdragon Note 9 · first checkpoint

Exact Android source `fee56ef34a958075736b77328a030032f07970bb`. [Android APK build](https://github.com/JoyRaptor/JoyCreator/actions/runs/45071795614). Installed in place with the local debug key. A debug activity saved each fixture to Studio project storage and launched the normal export service.

| Check | Result |
|---|---|
| Animated sharp and blur | Both exported 30 H.264 frames at 30 fps, 960 × 540. Six moving frames had a median luma difference of 1.701 on the 8-bit scale. The 17 idle frames had a median of 0.005 and a maximum of 0.038. |
| Static motion-only project | Disabled chose near-lossless trim. Enabled chose full re-encode. All 30 decoded frame hashes matched. |
| First HOLD jump | The 750 ms jump fell between the sampled exposure windows. The 735 ms fixture on the follow-up SHA exercises the fence. |
| Thermal observations | Battery temperature 38.0–38.2 °C. Android status reached 3 after installation, then was 2 on the final three exports. These were output checks. |
| Focused inverse-sampler test | Passed. The intentionally forward-mapped rotation failed the UV assertion. |

Local evidence: `C:/workspace/motion-c2-evidence`. Originals are `sharp.mp4`, `blur.mp4`, `static-disabled.mp4`, `static-enabled.mp4` and their export logs. `compare-fee56ef34.json` records frame-by-frame decoded luma analysis. FFmpeg `framemd5` verified the static pair.

## Follow-up SHA

`20ae2c5e7482d23e6d61181d06c207dad1070cca` passes the edited piece duration to the shutter. Repeated, frozen and transition pieces keep the sharp placement path in this slice. The 735 ms HOLD jump should straddle the frame at 733.333 ms. [Android APK build](https://github.com/JoyRaptor/JoyCreator/actions/runs/45072384922) · [Studio CI](https://github.com/JoyRaptor/JoyCreator/actions/runs/45072398360).

Still open in C2: use the actual frame cadence, integrate text, pictures, sprites and PiP, add item controls and Inspect Frame, and compare the GPU accumulation with the CPU reference. Preview, effect layers, puppet motion, filmed-pixel tracking, sustained mobile performance and Exynos testing have separate gates.
