# C6 High footage export on the Snapdragon Note 9

The SM-N960U ran the [gated debug fixture APK](https://github.com/JoyRaptor/JoyCreator/actions/runs/45776341462) at `f7590614d09f2ac4568eaeba3a919b296ad01100`. The existing installation was restored by hash and the device lock was released.

| Case | Export start to mux commit | High stages |
|---|---:|---:|
| Trimmed normal-speed bundled clip, first run | 12,267 ms | 1 |
| Same media and trim in a new project, repeat | 4,206 ms | 1 |
| Same normal-speed clip with the master switch off | 830 ms | 0 |
| Two trimmed bundled clips at half speed | 13,194 ms | 2 |

The three 960×540 RGBA readbacks matched exactly between the first and repeat normal-speed exports at editor PTS 133,666, 300,333, and 500,333 µs. High differed from the matched sharp control at all three times, with 18,964, 31,693, and 25,126 channel samples outside the comparator's one-code-unit limit. On the half-speed join, both sources independently entered High at source PTS 266,666 µs. A second capture bracketed that join. The first frame on source 2 at editor PTS 1,334,333 µs was identical to sharp. High differed from sharp on the next two sampled frames at 1,400,999 and 1,467,667 µs. This proves the first-frame fallback and subsequent High recovery at that source cut.

Thermal status was 0, with battery temperature ranging from 26.4 to 26.6 °C. The timings cover short fixtures with export setup overhead. The release gate still requires the 30-second and sustained playback runs.

Evidence under `C:/Temp/stu7-c4-physical-20261125/footage-device-024927/` includes native-sized manifests, three frame samples per route, repeat and sharp comparisons, export logs, and thermal samples. The cut evidence is in sibling folder `footage-device-025238/`.

## Private normal-speed and slow-motion recording

The [`0e3494ccf` test APK](https://github.com/JoyRaptor/JoyCreator/actions/runs/45778341242) exported two trims from the owner's local recording. Normal-speed High took 16,630 ms from export start to mux commit, compared with 825 ms for sharp. The steady slow-motion High trim took 17,563 ms, compared with 1,113 ms for sharp. High changed pixels at all six matched editor PTS values. Peak channel differences ranged from 54 to 74 in normal footage and from 114 to 127 in the slow-motion section. This comparison establishes the effect's output. Blind quality scoring and the full performance gate remain open.

Inspect Frame at 300 ms produced a bitmap after the High gather marker on the bundled clip. Direct live-preview parity, device seek/context-loss checks for High, and Exynos testing remain open. Private evidence is under `C:/Temp/stu7-c4-physical-20261125/footage-device-030512/`. The staged source copy was removed from the sandbox and the original APK was restored by hash before release of the lock.
