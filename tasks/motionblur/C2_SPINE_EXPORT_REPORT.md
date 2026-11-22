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

### Follow-up device proof · `20ae2c5e7`

- All four 960 × 540 exports repeated successfully. Moving median luma difference was 1.701/255 and idle was 0.005/255. All 30 decoded no-transform frame hashes matched across full re-encode and near-lossless trim.
- At 733.333 ms, the exposure overlaps the 735 ms HOLD jump. Luma gain was zero across 18,366 pixels occupied solely by the new pose. In a negative control, adding 40% of the following pose to that frame triggered the ghost check on 100% of those pixels. This control manipulates the decoded fixture; the device APK uses its normal shutter.
- On the three frames at 300.000, 366.667 and 433.333 ms, an independent CPU six-sample reconstruction had 2.44–2.50/255 luma mean absolute error over the active image region. Using the sharp frame as the prediction had 9.18–12.26/255 error. This reconstruction works in encoded SDR luma and is a spatial check. Linear-light premultiplied accumulation remains an open C2 requirement.
- Battery temperature 36.2–36.5 °C. Android thermal status 0. Release-performance testing remains pending.
- Evidence in the same local directory: `hold-*.mp4`, `hold-*.log`, `compare-20ae2c5e7.json`, `oracle-20ae2c5e7.json` and `negative-hold.json`.

### Persistence lint

The first Studio run completed 61/62 suites. The lint matches private field names to JSON key literals and reported a mismatch for `rigidMotionBlurEnabled`. The Note 9 fixtures confirmed the existing key already round-tripped. `82899e6be42f298f4e2b0d5d0226cfffaf6e7a2d` aligns the private field name with the saved `rigidMotionBlur` key and passes the focused lint with its deliberate missing-key control. [Exact Studio run](https://github.com/JoyRaptor/JoyCreator/actions/runs/45073080665) · [Exact APK run](https://github.com/JoyRaptor/JoyCreator/actions/runs/45073231068).
