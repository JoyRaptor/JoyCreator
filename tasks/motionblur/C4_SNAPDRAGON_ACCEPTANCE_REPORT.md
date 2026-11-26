# C4 shared compositor on the Snapdragon Note 9

Tested the debug APK from [`b3074ed83`](https://github.com/JoyRaptor/JoyCreator/commit/b3074ed8378fe7b59a59391acca61a4b46332de4) on the SM-N960U. The APK's cloud source and build are recorded in [Actions 45601544844](https://github.com/JoyRaptor/JoyCreator/actions/runs/45601544844). The previous installation was restored and checked by hash after each run. The device lock was released.

| Check | Result |
|---|---|
| Raw framebuffer corpus | All 76 capture requests completed. 14 compatible pairs, 13 true-order pairs, and both project-switch pairs passed the three-frame pixel comparator. All four expected fallback probes and the four project-compatibility fallbacks took their expected routes. |
| PiP repeatability | Plain PiP legacy repeat was bit-exact. Forcing its ordered route produced up to 47/255 channel error. Blended PiP differed up to 99/255 even between legacy repeats. Keep PiP outside the automatic equivalence set. |
| Legacy blur-off project | A fixture saved without motion or true-order fields reopened through the legacy FX route. It exported three readbacks and its project JSON SHA-256 stayed identical through open and export without edits. |
| 30-second export | First run: legacy 8,122/7,313 ms; ordered 7,842/7,636 ms. Ordered averaged 21.5 ms slower. Repeat: legacy averaged 6,944 ms; ordered 6,441.5 ms. The paired timings disagree on the direction of the difference. |
| 60-second blur-off latch | 1,795 intervals at 1280×720 internal size. P50 33.322 ms, P95 50.331 ms, P99 51.119 ms. P95 passed the 50.380 ms C1 budget at thermal status 0. |
| 50-seek memory | Identical seek gestures took each mode from 00:00 to 00:20. Ordered graphics PSS went from 57,668 to 71,420 KiB; legacy went from 51,256 to 67,100 KiB. Both ended at 48,864 KiB EGL PSS. The final ten seeks added 156 KiB graphics PSS ordered and 1,040 KiB legacy. Both modes grew from cold; the no-growth gate remains open. |

Raw evidence is under `C:/Temp/stu7-c4-physical-20261125/`: `corpus/receipt.json`, `*-results.json`, `performance-retest-comparison.json`, `preview-latch.log`, `legacy-json-receipt.json`, and the ordered/legacy `*seek-*-meminfo.txt` series. No owner media is checked in.

The C4 candidate remains isolated. Paused PiP pixel parity, the memory gate, and the unambiguous landing decision remain open. No Exynos or release-profile claim is made.
