# JB-5.14 R51 JVM measurement bench

Status: fixture scaffold; no measurements yet. Worker 1 owns only new core/bench source/tests and this report in C:/Temp/jb-measure-r51. InkTiles and FillTrace are being built by the root lane; measurement waits for their verified landing.

Plan:
- [x] Deterministic 512 × 512 curved line and overlapping stamp fixtures, 33 raw samples per stroke, varying pressure/tilt, stable seeds.
- [x] Separate procedural pen/pencil presets without image dependencies; painted frame uses a 32px stamp and four colors.
- [x] JVM nanosecond timing and per-entry level-6 raw deflate matching archive ZIP payloads; tests compare compressed size with ZipOutputStream.
- [ ] Adopt landed APIs; verify fixture/metrics and integration tests under the shared Gradle lock.
- [ ] Warm 50/500/2000-stroke frame renders (pen and pencil), report preparation and prepared tile render separately.
- [ ] Compare deflated actual StrokeCodec records with identical rendered RGBA8 tiles for line/paint frames.
- [ ] Measure FloodFill and FillTrace separately on dense 2048 × 2048 line art; report region pixels, contour/sample counts and checksums.
- [ ] Record hardware/JVM, warmups, retained samples, timings, coverage and limitations; send scoped commit to root for integration.

All numbers will be desktop JVM measurements of synthetic work, with no Note 9 performance claim. Frame storage counts only compressed entry payloads, excluding ZIP headers/paths, document.json, thumbnail and brush library on both sides. StrokeCodec.encodeAll produces one bundle/ZIP entry per cel; each RGBA8 tile is its own entry, as in JbArchive (level 6). Frame raster dimensions are 512 × 512 (four 256px tiles), not 2048px; the 2048px case is fill tracing. Fixtures are constructed outside timing; output checksum work is included and identified in timings. No phone/install operations.
