# C3 ordinary PiP preview on the Note 9

The [exact PiP APK](https://github.com/JoyRaptor/JoyCreator/actions/runs/45167916057), source `77bae3a78`, ran the placement fixture on a Snapdragon Note 9. The live shader reported six taps and a 33,333 µs period. The readback at 366,666 µs was 960 × 540 and matched export frame 11.

| Region | Preview RGB MAE vs blurred export | Preview RGB MAE vs sharp export |
| --- | ---: | ---: |
| 21,122 affected pixels, export controls more than 12 MAE apart | 3.57 | 39.60 |
| Whole frame | 1.19 | 2.66 |

The preview was 11.1× closer to the blurred export in the affected region. The original APK was restored with its SHA-256 verified. The phone claim was released. This run covers live ordinary PiP placement. Paused preview, timeline seams, stacked overlay decoders, and throughput remain open.

Raw evidence is in `C:/workspace/motion-c2-evidence/c3-preview-pip-*`. Export controls are `c2-pip-blur.mp4` and `c2-pip-sharp.mp4` in the same folder.
