# C6 GPU footage flow checkpoint

The isolated shader branch contains the luma pyramid, coarse-to-fine patch search, densification and confidence passes. It has no editor or export caller yet.

## Desktop shader comparison

Mesa 25.0.7 llvmpipe ran the shaders in a surfaceless OpenGL ES 2.0 context and read back RGBA8 outputs. The Rec. 709 and half-luma passes agreed with their CPU equations within 1/255 at odd sizes, 240×135 and single-pixel edges. Moving the half-luma sample offset from 0.75 to 0.5 source pixels produced an error of up to 51/255.

The flow tests used the Studio Java estimator as the oracle and the same quantized multi-octave texture in both paths. The table reports endpoint differences inside a 24-pixel margin. Confidence used the same quantized forward and backward fields in the Java and GPU comparison.

| Test | Mean vs Java (px) | P99 vs Java (px) | Mean vs analytic motion (px) | Confidence mean absolute error |
| --- | ---: | ---: | ---: | ---: |
| Identity | 0.00000 | 0.00000 | 0.00000 | 0.00120 |
| 3.4, −1.7 px shift (161×121) | 0.05395 | 0.15845 | 0.05975 | 0.00118 |
| 17, 9 px shift (160×120) | 0.05575 | 0.12757 | 0.05555 | 0.00096 |
| 2.5, 1.25 px shift with +0.12 flash (161×121) | 0.04979 | 0.13215 | 0.04730 | 0.00663 |

Disabling mean normalisation raised the flash scene's mean analytic error to 16.86835 px. The desktop controls do not establish Android compilation, the 3 ms live budget, real-footage parity or export quality. The Android pass runner, device benchmark, production integration and comparison with DIS remain open.

## Isolated Android pass runner

`GpuPyramidFlow` allocates size-checked targets once per GL context. It builds the luma pyramids, reuses the coarse flow workspace for the optional reverse estimate, and outputs packed flow and confidence without a readback. A JVM stub run exercised one- and two-direction pass order, multiple frames without target reallocation, framebuffer/viewport/dither restoration and rejected framebuffer allocation cleanup. The stub run does not compile against the Android SDK. Obtain an exact GitHub APK and Studio suite before wiring the runner into the device bench. Then measure per-pass cost on the Snapdragon Note 9 and compare real pairs before enabling a caller.

With the high-precision sampler revision, the shifting, long-shift and flash cases had mean endpoint differences versus Java of 0.05199, 0.04960 and 0.05232 px. Their P99 differences were 0.11968, 0.13929 and 0.14047 px. The flash negative rose to 17.19026 px analytic error. The luma tests stayed within 1/255. These repeat measurements are on the same desktop driver.

## Android build

[GitHub Actions run 45241563035](https://github.com/JoyRaptor/JoyCreator/actions/runs/45241563035) built the default debug APK at `a397a2e9a` and passed 64 of 64 Studio suites. The verification workflow lives on a separate branch. The Android flow class has no editor or export caller. Physical driver parity and timing remain open.
