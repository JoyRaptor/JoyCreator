# C2 export shutter cadence checkpoint

Source 50ce57f9e5bc798e8f97cacad2ddc118757d78ef. [Exact Android APK](https://github.com/JoyRaptor/JoyCreator/actions/runs/45097732251) and [Studio CI](https://github.com/JoyRaptor/JoyCreator/actions/runs/45097750951) succeeded. Studio CI passed 62/62 suites. The focused JVM motion suite passed 12,698 assertions. A forced-30-fps control failed the cadence assertions.

Main clips, pictures, plain titles, sprites and PiP use the preceding edited presentation interval for the exposure. At the start of a piece or after a reset, the first frame is sharp. At an abrupt change in frame spacing, the exposure follows the interval leading into the current frame. Duplicate or backwards timestamps and gaps over one second are sharp and re-anchor cadence. The item, keyframe discontinuity and edited-piece fences still apply.

## Snapdragon Note 9

| Check | Result |
|---|---|
| 2× export | Both clips encoded 58 frames at 960×540 and 60 fps, with matching presentation timestamps. Moving-frame intervals measured 16,666–16,667 µs. Media3 produced 0.966667 s from the nominal one-second fixture. |
| Exposure width | On ten moving frames, the 60 fps reconstruction gave 7.75–8.33/255 mean luma error on swept edges. The fixed-30-fps reconstruction gave 31.52–33.28/255. A synthetic fixed-30-fps render failed the same check. |
| Main clip at normal speed | A separate three-frame comparison recovered the expected swept transform. The exposure reconstruction gave 2.50–2.62/255 error where matching the sharp clip to the blurred result gave 9.18–12.21/255. |
| Pictures, sprites and titles | All three decoded-image comparisons passed. This included sprite cell-boundary isolation and the title HOLD check. |
| PiP | Moving luma difference was 4.74–7.78/255 inside the footprint and at most 0.040/255 outside. The decoded source stayed upright and the fitted next-pose gain at the HOLD fence was zero. The stationary bit-equality check failed. The first five frames matched and the largest stationary whole-frame luma difference was 0.02544/255. Prior independent blur-off controls showed stationary encode variation. |
| Storage and device | The first test encountered the existing Joy Creator folder index rename error after encoding. The finalized MP4 was recovered from the public folder. The other six exports saved. Thermal status stayed 0 during the six-export session. Both sessions restored the original APK by verified hash and released the phone. |

Local evidence includes cadence-oracle-50ce57f9e.json, cadence-note9-50ce57f9e.json, cadence-spine-1x-50ce57f9e.json and cadence-regressions-50ce57f9e.json. These are short SDR tests on a Snapdragon Note 9. Editor controls, Inspect Frame, live preview, Exynos verification and sustained profiling remain open.
