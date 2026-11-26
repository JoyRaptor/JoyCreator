# C6 High footage export on the Snapdragon Note 9

The SM-N960U ran the [gated debug fixture APK](https://github.com/JoyRaptor/JoyCreator/actions/runs/45776341462) at `f7590614d09f2ac4568eaeba3a919b296ad01100`. The existing installation was restored by hash and the device lock was released.

| Case | Export start to mux commit | High stages |
|---|---:|---:|
| Trimmed normal-speed bundled clip, first run | 12,267 ms | 1 |
| Same media and trim in a new project, repeat | 4,206 ms | 1 |
| Same normal-speed clip with the master switch off | 830 ms | 0 |
| Two trimmed bundled clips at half speed | 13,194 ms | 2 |

The three 960×540 RGBA readbacks matched exactly between the first and repeat normal-speed exports at editor PTS 133,666, 300,333, and 500,333 µs. High differed from the matched sharp control at all three times, with 18,964, 31,693, and 25,126 channel samples outside the comparator's one-code-unit limit. On the half-speed join, both sources independently entered High at source PTS 266,666 µs. This verifies the route across two trimmed, retimed source registrations. Pixel-level comparison at the join is a separate test.

Thermal status was 0, with battery temperature ranging from 26.4 to 26.6 °C. The timings cover short fixtures with export setup overhead. They do not satisfy or replace the 30-second or sustained playback release runs.

Evidence under `C:/Temp/stu7-c4-physical-20261125/footage-device-024927/` includes native-sized manifests, three frame samples per route, repeat and sharp comparisons, export logs, and thermal samples. Owner footage was not part of this capture. The private normal/slow recording comparison, Inspect Frame parity, repeated seek and context loss, and Exynos acceptance are still open.
