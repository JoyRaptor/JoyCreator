# Note 9 bench baseline — 2026-10-16, source 1198fb2f1 (origin/joy-creator)

APK `:app:assembleDefaultDebug` arm64 SHA256 1510d08d1649bb3256fa7580e9d9fa1b04d7b92f534a69e049c1c34ac8604cc6, installed
13:11. `JoyBrushBenchActivity` StrokeReplay cases fastC, slowC, scribble, spiral, topScribble, blue1-3, two runs each,
unsaved bench documents. Battery 30.7 °C before, 31.6 °C after. Raw JoyBrushPerf JSON: evidence/bench_20261016_1198fb2/.

busy = frame time with glFinish (ms), p50 / p95 across the main cases (fastC run 1 = warm-up outlier, excluded):

| Brush | busy p50 | busy p95 | Reading |
|---|---|---|---|
| Ink (joybrush.ink) | ~15.5 | ~16.4 | holds 60 fps |
| Pencil (media:dry:Proto) | ~15.6 | 20–50 (spiral worst) | 60 fps median, visible hitches |
| Watercolour (media:wet:Round) | 75–130 | 110–160 | 8–13 fps: the sim (MEDIA_PERF_20261011: ~95 ms of 136 at 4 substeps) |
| Oil (media:paste:Oil round) | 20–170 | 65–215 | slowest tails; 9 frames for the 600 ms slowC stroke |

Next: Media Atlas v2 B3 (simulate only wet tiles) for watercolour; oil needs its own profile (per-step paste passes,
MediaLayerEngine.pasteStep runs once per PasteStep, many steps per frame on fast strokes).
