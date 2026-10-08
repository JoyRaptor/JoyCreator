# JB-5.14 R51 desktop JVM measurements

Measured October 7, 2026 (America/New_York). Worker 1, isolated checkout `C:/Temp/jb-measure-r51`; dependencies: InkTiles `6dd7d73b`, FillTrace `e8efaa1d`.

The synthetic 500-stroke line frame stores **208,790 B** of compressed recordings against **147,912 B** of compressed raster tiles. The painted frame reverses this: **209,042 B** of recordings against **569,684 B** of tiles. Storage savings depend on the marks, rather than following from stroke count alone. These are compressed ZIP entry payloads, not complete .jb file sizes.

The CPU reference takes seconds to rebuild the larger 512px frames on this laptop. Prepared 2,000-stroke frames have medians of **7,739.271 ms** (pen) and **11,470.305 ms** (procedural pencil). These numbers do not predict Note 9 performance or establish a playback budget. Dense 2048px flood fill and contour tracing have medians of **107.460 ms** and **169.683 ms**, respectively.

## Machine and method

- CPU: 13th Gen Intel Core i7-1360P, 12 cores / 16 logical processors; JVM sees 16 processors.
- RAM: 15.6 GiB visible; Windows 11 Home 10.0.22621, amd64.
- JVM: OpenJDK 64-Bit Server VM 17.0.19+10; test worker maximum heap 1,610,612,736 B (1.5 GiB). Gradle daemon heap 768 MiB; one test fork/Gradle worker.
- One discarded warmup and three measured runs **for each case**. This was explicitly reduced from the usual 3/9 to bound reference-renderer runtime, with Lead authorization. All three retained samples are below. p95 equals the maximum of three; it is not a robust tail estimate. There is no pass/fail time gate.
- `System.nanoTime` brackets each job; fixture construction is outside the brackets. Output hashing is inside. Prepared render excludes smoothing/placement; prepare-plus-render repeats both. Storage/compression is outside render timings.
- No forced GC, sleep, minimum-time selection or removal of slow samples. Fixed case order and limited warmup permit JIT/GC/thermal effects; for example, pen50 prepare-plus-render is faster than the preceding prepared case. Do not subtract these independent timings to derive preparation cost.
- A fresh `:core:cleanJvmTest` before the opt-in run prevented reuse of an earlier opt-out result. The primary watcher was paused only while holding the owned `jb-gradle.lock`, then restored; lock released. No phone, install, media/doc-model or GL operation.

## Fixtures and coverage

`R51Fixtures` produces deterministic synthetic hand-like curves, **not captured artist drawings**. Frame = 512 x 512 document pixels, exactly four 256 x 256 premultiplied RGBA8 tiles at scale1. Each record has 33 raw samples, varying pressure (0.3..0.95), finite tilt/azimuth, stable seed, smoothing0.2 and screenPerDoc1. Counts are prefixes of the same ordered recordings. Marks cross tile boundaries and overlap.

| Brush/count | Raw samples | Replayed dabs |
|---|---:|---:|
| pen50 | 1,650 | 20,449 |
| pen500 | 16,500 | 211,378 |
| pen2000 | 66,000 | 852,056 |
| pencil50 | 1,650 | 12,183 |
| pencil500 | 16,500 | 125,923 |
| pencil2000 | 66,000 | 507,577 |

Pen: 3px procedural tip, hardness0.9, spacing0.04, opacity/flow1, wash. Pencil: **plain procedural stamp pencil**, 7px tip, hardness0.55, spacing0.12, opacity0.65/flow0.25, buildup. Neither uses image tips/tip grain; this does not measure the realistic media pencil, paper crush, or paper grain. Painted storage fixture: 500 overlapping 32px procedural stamp curves, four opaque chosen colors, tip hardness0.7, spacing0.12, opacity0.6/flow0.35, wash. Every renderer refusal is checked; none occurred.

## Full-frame render timings

All times are milliseconds. Each frame job renders all four tiles, not a selected busy tile. Checksums fold all output bytes and agree between prepared and prepare-plus-render for each fixture.

| Case | Median ms | p95 ms | Retained samples ms | Output checksum |
|---|---:|---:|---|---:|
| r51-pen/50 prepare | 24.036 | 27.034 | 27.034, 24.036, 23.408 | -653441294282436117 |
| r51-pen/50 prepared full 512px frame | 255.867 | 279.437 | 279.437, 255.867, 204.235 | 8381252324659936475 |
| r51-pen/50 prepare + full 512px frame | 215.674 | 219.955 | 219.955, 215.674, 207.417 | 8381252324659936475 |
| r51-pen/500 prepare | 163.003 | 205.591 | 205.591, 155.890, 163.003 | -6825813319743204104 |
| r51-pen/500 prepared full 512px frame | 1932.544 | 1945.888 | 1847.752, 1945.888, 1932.544 | -363360998113477051 |
| r51-pen/500 prepare + full 512px frame | 2071.732 | 2073.944 | 2071.732, 2073.944, 2049.170 | -363360998113477051 |
| r51-pen/2000 prepare | 855.155 | 1160.811 | 1160.811, 842.368, 855.155 | -743071140003384904 |
| r51-pen/2000 prepared full 512px frame | 7739.271 | 7757.561 | 7704.490, 7739.271, 7757.561 | 862629897210028419 |
| r51-pen/2000 prepare + full 512px frame | 8811.902 | 8836.999 | 8836.999, 8773.247, 8811.902 | 862629897210028419 |
| r51-pencil/50 prepare | 15.237 | 16.502 | 16.502, 15.237, 14.222 | 8164146277366309723 |
| r51-pencil/50 prepared full 512px frame | 306.317 | 312.261 | 306.317, 312.261, 267.012 | -5676436429845570090 |
| r51-pencil/50 prepare + full 512px frame | 355.113 | 362.325 | 355.113, 362.325, 321.720 | -5676436429845570090 |
| r51-pencil/500 prepare | 147.493 | 165.868 | 165.868, 147.085, 147.493 | -4314841153422694501 |
| r51-pencil/500 prepared full 512px frame | 2859.357 | 2866.543 | 2866.543, 2859.357, 2848.006 | 1238237668966798960 |
| r51-pencil/500 prepare + full 512px frame | 2984.136 | 3076.092 | 2981.072, 2984.136, 3076.092 | 1238237668966798960 |
| r51-pencil/2000 prepare | 638.379 | 645.100 | 638.379, 627.548, 645.100 | 680045388850788247 |
| r51-pencil/2000 prepared full 512px frame | 11470.305 | 11675.376 | 11675.376, 11470.305, 11307.158 | 1139873696261893582 |
| r51-pencil/2000 prepare + full 512px frame | 12617.024 | 14820.570 | 12141.027, 12617.024, 14820.570 | 1139873696261893582 |

## Actual archive payload comparison

Uses `StrokeCodec.encodeAll(records)` as **one cel bundle / ZIP entry**, just as `JbArchive` does. RGBA8 tiles are compressed as separate entries. Raw DEFLATE level6 matches archive ZIP compression; a test compares its compressed length with a real `ZipOutputStream` entry. Includes the codec's record framing and IDs; excludes ZIP headers/paths, document.json, thumbnail and brush library on both sides. It measures rasterized versions of the **same fixture**, including anti-aliasing and stroke blend/composite, with empty tiles omitted as the renderer dictates.

| 500-stroke frame | Stroke bundle raw B | Stroke bundle deflated B | RGBA8 raw B | RGBA8 deflated B | Nonempty tiles | Alpha-covered pixels |
|---|---:|---:|---:|---:|---:|---:|
| line art | 507,394 | 208,790 | 1,048,576 | 147,912 | 4 | 154,779 / 262,144 (59.04%) |
| painted stamps | 508,394 | 209,042 | 1,048,576 | 569,684 | 4 | 218,152 / 262,144 (83.22%) |

Stroke/tile compressed byte ratio = 1.412 for line art, 0.367 for paint. This does not include future frozen brush copies/slab metadata, media payload stores, or any cross-frame deduplication.

## Dense line-art fill, 2048 x 2048

Opaque white reference, 3px border/horizontal hatch walls with 4px breaks, repeated small black islands. Seed(8,8); exact-color tolerance0, gapClose1, grow0. The **actual FloodFill mask**, not a fabricated region, feeds FillTrace. Selected **3,589,461 / 4,194,304 pixels (85.58%)**, mask checksum -6941069873414295238. Separate flood time includes gap closing/mask hashing; trace time excludes flood/reference construction and includes the tracer's 0.75px growth, simplification, stroke construction and coordinate hashing.

| Case | Median ms | p95 ms | Retained samples ms | Output checksum |
|---|---:|---:|---|---:|
| FloodFill2048 only | 107.460 | 150.187 | 150.187, 102.007, 107.460 | -6941069873414295238 |
| FillTrace2048 only (0.75px growth included) | 169.683 | 231.608 | 112.240, 231.608, 169.683 | -5350384837971289407 |

Output: **one filled record with 57,637 samples** (holes carried by the bridged contour), checksum -5350384837971289407. This fixture reveals substantial contour complexity even after simplification; count alone is not a success gate. The benchmark does not rasterize this whole fill output or claim a visual/phone acceptance result. Hole/growth correctness belongs to the landed FillTrace oracle tests; the fixture test separately checks substantial flood-region coverage and an excluded black island.

## Verification and reproduction

Targeted XML: **10 tests, zero failures/errors/skips**: R51FixturesTest5, R51JvmMetricsTest3, R51MeasurementTest2. First validation run took 2m15s. Explicit opt-in measurement run took 4m8s; `optInDesktopMeasurements` XML time **232.096s** (normal opt-out execution is only a fast guard). Raw output was freshly written, ended `COMPLETE`, and contained all 20 timing rows. The smoke test uses the landed renderer and checks premultiplied output and empty-frame behavior. Codec/ZIP tests validate actual serialization/compression, not hand-estimated sizes.

Reproduce under the shared Gradle lock and primary watcher pause/restore protocol. Set the existing Android SDK location via ANDROID_HOME; set `JOYBRUSH_R51_REPORT` to an absolute scratch .md path; run `gradlew.bat -p joybrush :core:cleanJvmTest :core:jvmTest --tests '*R51*'` with one worker/fork and the heap limits above. The opt-in test writes progress and every result to that path. Unset the environment variable for normal tests. Do not run concurrently with the watcher or another Gradle owner.

Brief section11 coverage: synthetic desktop storage comparison (item1), CPU frame rebuilding (item2), dense fill tracing (item6). **Not measured:** Note9; 80-frame cache/render-ahead; wet payload/drying; edit-under-1,000-lines rebuild; playback/proxy smoothness; wet-window feel. Those remain separate Lead/device work. No model-policy decision or automatic bake threshold is inferred from this run.

## October 8 follow-up: latest composer/replay source

Re-ran all cases after Lead source65682779, including the shared InkTiles reach/buffer/operator refactor and response-curve correction. Same fixtures, one warmup/three measured runs, same heap; no timing budget claimed. Targeted R51 XML11 tests, zero failures/errors/skips; opt-in run2m57s, fresh output COMPLETE. Original table above remains the October7 historical measurement; these are the October8 follow-up. Output checksums match the historical run for all cases (default fixture response curves are unchanged).

| Case | Median ms | p95 ms | Retained samples ms | Output checksum |
|---|---:|---:|---|---:|
| r51-pen/50 prepare | 30.868 | 31.810 | 30.868, 31.810, 29.915 | -653441294282436117 |
| r51-pen/50 prepared full 512px frame | 152.684 | 180.273 | 180.273, 152.684, 145.300 | 8381252324659936475 |
| r51-pen/50 prepare + full 512px frame | 150.910 | 152.965 | 152.965, 148.166, 150.910 | 8381252324659936475 |
| r51-pen/500 prepare | 121.449 | 123.830 | 123.830, 121.449, 119.534 | -6825813319743204104 |
| r51-pen/500 prepared full 512px frame | 1315.716 | 1418.103 | 1311.226, 1315.716, 1418.103 | -363360998113477051 |
| r51-pen/500 prepare + full 512px frame | 1538.956 | 1546.868 | 1538.956, 1529.776, 1546.868 | -363360998113477051 |
| r51-pen/2000 prepare | 521.634 | 588.329 | 508.647, 521.634, 588.329 | -743071140003384904 |
| r51-pen/2000 prepared full 512px frame | 5693.731 | 5880.187 | 5880.187, 5693.731, 5620.902 | 862629897210028419 |
| r51-pen/2000 prepare + full 512px frame | 6085.949 | 6286.600 | 6078.792, 6286.600, 6085.949 | 862629897210028419 |
| r51-pencil/50 prepare | 10.173 | 12.863 | 12.863, 8.991, 10.173 | 8164146277366309723 |
| r51-pencil/50 prepared full 512px frame | 191.212 | 192.255 | 191.212, 192.255, 189.126 | -5676436429845570090 |
| r51-pencil/50 prepare + full 512px frame | 209.073 | 209.693 | 209.073, 203.221, 209.693 | -5676436429845570090 |
| r51-pencil/500 prepare | 109.260 | 111.259 | 109.260, 104.351, 111.259 | -4314841153422694501 |
| r51-pencil/500 prepared full 512px frame | 1992.027 | 2114.273 | 2114.273, 1981.645, 1992.027 | 1238237668966798960 |
| r51-pencil/500 prepare + full 512px frame | 2138.628 | 2240.030 | 2138.628, 2098.204, 2240.030 | 1238237668966798960 |
| r51-pencil/2000 prepare | 455.543 | 469.227 | 469.227, 455.543, 447.548 | 680045388850788247 |
| r51-pencil/2000 prepared full 512px frame | 8249.350 | 8414.382 | 8414.382, 8249.350, 8038.690 | 1139873696261893582 |
| r51-pencil/2000 prepare + full 512px frame | 8902.003 | 8913.601 | 8902.003, 8513.430, 8913.601 | 1139873696261893582 |
| FloodFill2048 only | 75.602 | 129.059 | 129.059, 70.655, 75.602 | -6941069873414295238 |
| FillTrace2048 only (0.75px growth included) | 229.696 | 238.745 | 120.974, 238.745, 229.696 | -5350384837971289407 |

## Bytes per sample and quantisation experiment

Today's StrokeCodec v2 is lossless: sample payload21B minimum,29B with this fixture's tilt+azimuth,33B with barrel too. Encoded bytes below include record headers/ids as well as samples. All500-stroke cases contain16,500 samples.

| Fixture | Raw encoded B/sample | Deflated B/sample | Quantised-copy deflated bytes | Reduction versus original deflate |
|---|---:|---:|---:|---:|
| Line art500 | 30.751 | 12.654 | 181,696 | 12.98% |
| Painted500 | 30.812 | 12.669 | 182,088 | 12.89% |

Measured an opt-in research copy, encoded by the UNCHANGED codec: x/y rounded to1/64 doc px (max error1/128px per axis), time to1ms (max .5ms), pressure/tilt/azimuth/barrel to1/1024 of their native units (max1/2048). Missing/nonfinite sensors preserved; source records untouched. Tests assert error bounds, unchanged original bytes and actual deflate sizes. Raw encoded sizes stay507,394/508,394B: this experiment changes entropy, not field widths. These savings are empirical for two synthetic fixtures, not approval to quantise artist input.

An illustrative compact layout (16bit x/y deltas,16bit time delta,16bit pressure/tilt/azimuth,8bit tool) would be13B/sample versus this fixture's29B: about55% less SAMPLE payload before compression. Keeping today's headers gives243,394B raw for line art,244,394B for paint, about52% below today's raw totals. This is arithmetic only, NOT an implemented codec or measured compressed saving; range escapes, origins/scales, missing-value tags, precision and a new version still need design/tests. Today's exact recording contract remains unchanged.
