# C3 upright title preview on the Note 9

The [exact title APK](https://github.com/JoyRaptor/JoyCreator/actions/runs/45130120777), source `a088e00c7`, ran the disposable upright title fixture on the Snapdragon Note 9. Readback was 960 × 540. The live shader logged six taps with a 33,333 µs frame period.

| Decoded editor timestamp | Pixels changed by more than 12 MAE between export controls | Preview MAE vs blurred export, affected pixels | Preview MAE vs sharp export, affected pixels | Whole-frame MAE, blurred / sharp |
| --- | ---: | ---: | ---: | ---: |
| 366,666 µs, moving title | 23,571 | 14.12 | 33.47 | 1.72 / 2.63 |
| 733,333 µs, before the 735 ms HOLD edge | 2 | Insufficient region | Insufficient region | 1.62 / 1.60 |

The moving title was 2.37× closer to the blurred output in the affected region. Immediately before the HOLD edge the export controls nearly converged, and the preview was close to both over the whole frame. The third fixture returned to the launcher before its Play control became available. The frame after the HOLD edge still needs a capture. The previous APK was restored, its SHA-256 matched, and the phone claim was released.

Raw captures and comparison JSON are under `C:/workspace/motion-c2-evidence/c3-preview-title-*`. The paired references are `c2-title-blur.mp4` and `c2-title-sharp.mp4` in the same folder.
