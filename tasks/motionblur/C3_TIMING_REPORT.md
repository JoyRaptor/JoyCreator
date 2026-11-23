# C3 live preview timing on the Snapdragon Note 9

## First playback with lazy shader setup

The [exact timing APK](https://github.com/JoyRaptor/JoyCreator/actions/runs/45169730833), source `f03778e8c`, ran the same ordinary spine fixture four times. Natural Mix 0 kept the decoder clock and GL route active. The clock counted distinct decoded timestamps at successful EGL swaps. Numbers are elapsed CPU time from render start through swap and include any shader setup. They do not isolate GPU execution. Fixture output was 960 × 540.

| Trial | Mix | Distinct decoded frames | Moving-window samples | P50 (ms) | P95 (ms) | P99 (ms) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 100% | 26 | 11 | 3.76 | 99.39 | 99.39 |
| 2 | 0% | 27 | 12 | 3.68 | 5.16 | 5.16 |
| 3 | 0% | 28 | 12 | 2.11 | 3.19 | 3.19 |
| 4 | 100% | 29 | 12 | 3.10 | 55.03 | 55.03 |

The fixed comparison window is 233,333–600,001 µs. In both Mix 100 trials, the first actual shutter draw was at 266,666 µs and was the largest measured spike. The first run also had a 41.97 ms spike at 700,000 µs, outside the comparison window. Temperature was 26.5–26.6 °C and Android thermal status 0. The original APK was restored by SHA-256 and the phone was released.

This run identified a first-use playback hitch. Warmed timing, hardware GPU timing, seeking, the mixed ten-minute fixture, memory, and Exynos need their own gates.

Raw `c3-timing-*.log`, `c3-preview-timing-receipt.json`, and `c3-preview-timing-summary.json` are under `C:/workspace/motion-c2-evidence/`.

## After preparing the shutter program

The [exact warm-up APK](https://github.com/JoyRaptor/JoyCreator/actions/runs/45171529282) at `51e789204` repeated the same fixture and Mix order. The matched comparison window contains 12 samples per trial.

| Trial | Mix | Distinct decoded frames | P50 (ms) | P95 (ms) | P99 (ms) | First shutter frame or same control PTS (ms) |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 100% | 27 | 3.31 | 5.95 | 5.95 | 4.65 |
| 2 | 0% | 28 | 3.99 | 6.24 | 6.24 | 1.69 |
| 3 | 0% | 28 | 2.88 | 4.78 | 4.78 | 2.88 |
| 4 | 100% | 29 | 4.10 | 8.19 | 8.19 | 4.10 |

The preparatory draw took 37 ms and 6 ms. Log timestamps place it 5.34 and 7.08 seconds before the first shutter frame, while the disposable fixture was open and paused. Neither Mix 0 trial prepared a shutter program. The 55–99 ms first-motion stall dropped to 4.10–4.65 ms. Thermal status remained 0 at 26.1–26.3 °C. The original APK hash matched after restoration and the phone claim was released.

These are short 540p CPU draw-and-swap timings. The specification’s 720p GPU P95, mixed ten-minute playback floor, memory and Exynos gates remain open. The baseline is preserved at `C:/workspace/motion-c2-evidence/timing-before-warm-up/`. The rerun uses `c3-timing-*.log` and `c3-preview-timing-*.json` in the parent folder.
