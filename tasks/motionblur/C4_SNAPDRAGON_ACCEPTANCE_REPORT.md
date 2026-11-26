# C4 shared compositor on the Snapdragon Note 9

Tested the debug APK from [`b3074ed83`](https://github.com/JoyRaptor/JoyCreator/commit/b3074ed8378fe7b59a59391acca61a4b46332de4) on the SM-N960U. The APK's cloud source and build are recorded in [Actions 45601544844](https://github.com/JoyRaptor/JoyCreator/actions/runs/45601544844). The previous installation was restored and checked by hash after each run. The device lock was released.

| Check | Result |
|---|---|
| Raw framebuffer corpus | All 76 capture requests completed. 14 compatible pairs, 13 true-order pairs, and both project-switch pairs passed the three-frame pixel comparator. All four expected fallback probes and the four project-compatibility fallbacks took their expected routes. Swapping the adjustment order made the comparator reject equivalence at all three times. |
| PiP repeatability | Plain PiP legacy repeat was bit-exact. Forcing its ordered route produced up to 47/255 channel error. Blended PiP differed up to 99/255 even between legacy repeats. Keep PiP outside the automatic equivalence set. |
| Legacy blur-off project | A fixture saved without motion or true-order fields reopened through the legacy FX route. It exported three readbacks and its project JSON SHA-256 stayed identical through open and export without edits. |
| 30-second export | First run: legacy 8,122/7,313 ms; ordered 7,842/7,636 ms. Ordered averaged 21.5 ms slower. Repeat: legacy averaged 6,944 ms; ordered 6,441.5 ms. The paired timings disagree on the direction of the difference. |
| 60-second blur-off latch | 1,795 intervals at 1280×720 internal size. P50 33.322 ms, P95 50.331 ms, P99 51.119 ms. P95 passed the 50.380 ms C1 budget at thermal status 0. |
| 50-seek memory | Identical seek gestures took each mode from 00:00 to 00:20. Ordered graphics PSS went from 57,668 to 71,420 KiB; legacy went from 51,256 to 67,100 KiB. Both ended at 48,864 KiB EGL PSS. The final ten seeks added 156 KiB graphics PSS ordered and 1,040 KiB legacy. Both modes grew from cold. |

After 50 warm-up seeks per mode, the next 50 added 136 KiB graphics PSS in each mode. Ordered rose from 73,184 to 73,320 KiB, and legacy from 69,236 to 69,372 KiB. EGL held at 50,784 KiB and GL at 768 KiB in both modes. The ordered graphics PSS premium stayed at 3,948 KiB. See `warm-seeks-084255/` for the memory samples and thermal logs.

Raw evidence is under `C:/Temp/stu7-c4-physical-20261125/`: `corpus/receipt.json`, `*-results.json`, `performance-retest-comparison.json`, `preview-latch.log`, `legacy-json-receipt.json`, and the ordered/legacy `*seek-*-meminfo.txt` series. No owner media is checked in.

The isolated PiP latch fix at `fb2269d17c5250ec328098d963905babe54e561f` [built in Actions](https://github.com/JoyRaptor/JoyCreator/actions/runs/45832403927). On Snapdragon all six fixture captures completed. Plain and blended PiP each matched legacy repeat and ordered output bit-for-bit at 66,666, 500,000, and 900,000 µs. All six runs logged 25 decoded GL frames and zero rewinds. The original APK was restored by hash and the phone lock released. See `pip-exact-085757/` for the receipt, device logs, and four comparisons.

With the exact-latch APK, the 30-second legacy runs took 6,479 and 7,156 ms. The ordered runs took 6,497 and 6,653 ms. Ordered averaged 242.5 ms faster (3.6%). GL draw represented 19% of samples in both legacy runs and 17% and 16% in ordered runs. Thermal status remained 0, and peak battery temperature was 30.3 °C. See `perf-exact-090105/comparison.json` for the complete GL phases.

On the exact-latch APK, 100 warm-up seeks were followed by 50 measured seeks per mode. Ordered graphics PSS remained exactly 73,336 KiB at all six checkpoints, and legacy rose from 69,276 to 69,300 KiB. EGL remained 50,784 KiB and GL remained 768 KiB in both modes. The ordered mode passed the measured steady-state no-growth check. This result follows warm-up and does not erase the cold allocation or approximately 4 MiB ordered graphics PSS premium. See `settled-seeks-090343/` for raw samples and the route logs. The original APK was restored and the lock released.

The C4 candidate remains isolated. The earlier paused-preview PiP comparison and the landing decision remain open. No Exynos or release-profile claim is made.
