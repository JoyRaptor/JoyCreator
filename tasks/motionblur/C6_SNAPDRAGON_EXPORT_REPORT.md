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

## Independent centered-shutter calibration

The debug-only fixture a9334c439 from [Actions 45835093208](https://github.com/JoyRaptor/JoyCreator/actions/runs/45835093208) used the full untrimmed 14-frame, 640×360, 30 fps BT.709 sharp sequence. It used the same centered 180-degree shutter as the 240 fps integrated reference. Both exports read the same 640×360 frames at the exact matched timestamps. No reference resizing was needed. The private source was staged on the phone, removed after the run, and retained locally only. The previous APK was restored by hash and the lock released.

| Editor PTS | Reference frame | High mean absolute error | Sharp mean absolute error | High / sharp PSNR |
|---|---:|---:|---:|---:|
| 100,000 µs | 4 | 2.241 / 255 | 1.720 / 255 | 34.94 / 37.23 dB |
| 200,000 µs | 7 | 2.038 / 255 | 1.501 / 255 | 36.13 / 40.43 dB |
| 333,333 µs | 11 | 2.358 / 255 | 1.817 / 255 | 34.23 / 36.94 dB |

High had more whole-frame error than the sharp control at all three times. In the reference-defined motion region (pixels with more than eight code values of contrast against its clean sharp frame), it reduced mean error from 15.629 to 15.188 and from 16.436 to 16.119 in the first and third samples. Error rose from 10.593 to 10.715 in the second. Quality on this low-light shot remains open. The 14 frames are one shot for C7. These three diagnostics do not provide a blind-review score.

Raw pixels, local previews and metrics are in C:/Temp/stu7-c4-physical-20261125/reference-device-091014/.

## Paused High preview on Snapdragon

The isolated [c0a9420b6 debug APK](https://github.com/JoyRaptor/JoyCreator/actions/runs/45836328274) used the gated candidate ef9607b54. The real editor paused at editor time 300,333 µs and latched source PTS 533,333 µs. The draw logged matching preceding and next source PTS 500,000 and 566,666 µs. Cold and repeated 960×540 readbacks were byte-identical, and High differed from the master-off readback in 18,432 pixels (maximum 46/255, RGB RMS 1.559).

Inspect Frame produced distinct High and sharp PNGs at the same requested position (RGB RMS 1.900). The preview/Inspect cross-route RMS was 2.923 for High and 2.837 for sharp. Inspect passes through the encoder and its route marker reports the first successful High frame. The exact pre-encode service export comparison is tracked separately. The previous installation was verified by hash after both runs and the device lock was released.

The readbacks and logs are in C:/Temp/stu7-c4-physical-20261125/paused-device-091908/ and paused-device-092024/.

## Exact pre-encode paused preview/export comparison

The isolated [793aaf5ac APK](https://github.com/JoyRaptor/JoyCreator/actions/runs/45838060635) ran through Wi-Fi debugging on the Snapdragon. The preview and Android export service both wrote full 960×540 bottom-up RGBA at exact editor PTS 300,333 µs. Cold and warm High preview matched bit-for-bit. Preview and export each differed from their own sharp control, with maximum High-versus-sharp error 46/255 on both.

| Comparison | Max error | Mean error | Channels above 1 |
|---|---:|---:|---:|
| High preview vs export | 15 | 0.08253 | 32,048 |
| Sharp preview vs export | 15 | 0.08056 | 31,755 |
| High minus sharp, preview vs export | 6 | 0.00781 | 1,178 |

Both the High and sharp pair miss the strict 1/255 maximum. Most of the difference is already present in the sharp base. The difference of the two motion effects is considerably smaller and still exceeds 1/255. Exact staged preview/export pixel parity remains open. Evidence is in C:/Temp/stu7-c4-physical-20261125/paused-raw-device-134854/.

The corrected [emulator run](https://github.com/JoyRaptor/JoyCreator/actions/runs/45881985564) completed all five preview and raw service comparisons plus both Inspect controls. High preview and raw export were bit-exact at editor PTS 300,333 µs, and the sharp pair was bit-exact too. Both sharp controls differed from their own High render. This isolates the outstanding pixel gap to the physical Note 9 path.

## Native replay and supplied Sony calibration, 2026-11-27

The [2e3bc6d10 native trace APK](https://github.com/JoyRaptor/JoyCreator/actions/runs/45946157639) captured the previous, current and next staged color textures, four packed flow textures and immediate High result at 100,000 µs from the original low-light calibration. All four packed grids matched the desktop baseline byte-for-byte. The color textures differed by 1.193–1.218/255 RGB RMS from desktop decode. Replaying the phone’s same colors, flow, sampler filters and shutter on SwiftShader still differed from the native gather by mean 0.2804/255 and maximum 52/255. The High-minus-sharp effect correlated 0.7774. This leaves a render-path difference to isolate with the same captured inputs. Evidence stays in `D:/Temp/stu7-c6-native-input-proof-20261127/native-reference-device-054010/`.

The supplied Sony reel is encoded at 24 fps and labels selected source shots 240 or 480 fps. A private intake used three visually distinct segments starting at 9, 44 and 61 seconds, cropped the speed labels and verified every decoded cropped frame was byte-distinct. It integrated centered 180-degree 30 fps references using the labeled rates and made corresponding sharp 30 fps inputs. The public reel does not establish camera shutter metadata. The ordinary-spacing 24 fps section supplies separate cut and import material. The other supplied MP4 is truncated and has no usable frame index.

On the Snapdragon, the Sony 240 fps street-running sample completed matching High and sharp service exports. At 100,000, 200,000 and 333,333 µs, High whole-frame MAE was 2.774, 2.796 and 2.873/255 versus sharp 2.341, 2.445 and 2.453/255. Within the reference-defined motion region, High reduced error from 12.198 to 11.844, 11.731 to 11.247 and 13.188 to 12.379/255. This is one further filmed calibration shot, without a blind rating. The private owner media and raw device results stay under `D:/Temp/stu7-owner-media-intake-20261127/`. The prior APK was restored and verified after both runs.

### Packed flow sampler break-test

The native receipt showed linear filtering on all four packed flow grids after the Media3 sampler bind. The upload configured nearest sampling, which the bind replaced. Interpolating packed motion bytes can create a velocity that neither adjacent cell contains. Candidate `04371ce8f3bf1d395172fb637e4abf8817464c8f` reapplies nearest filtering to the four flow samplers after binding and keeps linear filtering for color donors. The focused GL suite passed, and a deliberate wrong-minification-filter mutation failed the new assertion.

At 100,000 µs, desktop replays used the exact native colors and four flow grids. Selecting intact flow cells reduced whole-frame average error from the captured phone High output’s 2.241 to 1.492/255 on the original low-light shot, where native sharp was 1.720/255. For the Sony running shot the figures were 2.774 to 1.887/255, where native sharp was 2.341/255. Both exact-input replays also improved their motion regions. These are host replays of device inputs. The candidate still needs its actual Android and preview/export measurements. [Studio](https://github.com/JoyRaptor/JoyCreator/actions/runs/45985678343) · [Native APK build](https://github.com/JoyRaptor/JoyCreator/actions/runs/45985684594).

### Native corrected sampling proof

The signed `f4fc6ad3cbb02a30f0657b2d2ed2c7a36832960f` fixture from [Android Actions](https://github.com/JoyRaptor/JoyCreator/actions/runs/45985684594) reported `GL_NEAREST` on all four packed flow samplers through the draw for both real shots. Both reruns completed High and sharp export on the USB Snapdragon Note 9. The references and source inputs are identical to the preceding cases.

| Shot | Editor PTS (µs) | Old High MAE / 255 | Corrected High MAE / 255 | Sharp MAE / 255 |
| --- | ---: | ---: | ---: | ---: |
| Sony running | 100,000 | 2.774 | 1.882 | 2.341 |
| Sony running | 200,000 | 2.796 | 1.861 | 2.445 |
| Sony running | 333,333 | 2.873 | 1.949 | 2.453 |
| Low-light hand | 100,000 | 2.241 | 1.499 | 1.720 |
| Low-light hand | 200,000 | 2.038 | 1.452 | 1.501 |
| Low-light hand | 333,333 | 2.358 | 1.719 | 1.817 |

All six corrected High samples improved whole-frame accuracy against the sharp baseline. These are measured exports from two independent shots. Preview parity, broader scene coverage and sustained Android profiling are open. Evidence is in `D:/Temp/stu7-owner-media-intake-20261127/native-nearest-sony/native-reference-device-060321/` and `native-nearest-low-light/native-reference-device-060432/`. The prior APK was verified by SHA-256 after each run and the device lock was released.

### In-file boxing cut and preceding-shot control

The replacement boxing file decodes at 1920×1080 SDR, encoded 25 fps. The marked 7–12 and 187–196 second ranges supply rope occlusion footage. The latter contains the hard cut at source 194.76 s. Its 640×360 probe starts at 193.56 s, so the cut lands at editor 1,200,000 µs. The downloaded cadence does not establish the original camera rate. This is a private stress fixture.

The control replaces all 30 decoded frames before the cut with the alternate ring shot and keeps the 30 decoded frames after it byte-identical, verified by frame checksums. The signed `f4fc6ad3c` Snapdragon export captured exact raw frames at 1,160,000, 1,200,000 and 1,240,000 µs in High and sharp for both files. Both sharp post-cut samples matched bit-for-bit. The first High post-cut frame changed with the preceding shot, with peak 128/255 and 27,200 channels differing by more than 1/255. The next High frame matched bit-for-bit. This reproduces forbidden cross-cut donation on the first frame inside a single file.

Host luma analysis found mean absolute change of 0.248 and 0.211 with 4.8% and 4.3% trusted cells across the two hard-cut pairs. Their adjacent same-shot pairs kept 84–96% trusted cells. The early moving-rope range retained at least 83% across the sampled large frame change. These diagnostics guide a conservative fence and do not establish general cut thresholds. Both original APK restorations were verified and the phone lock is free. Raw media and captures stay under `D:/Temp/stu7-owner-media-intake-20261127/boxing/`.
