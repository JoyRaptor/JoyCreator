# C3 main-video preview on the Note 9

The [exact spine APK](https://github.com/JoyRaptor/JoyCreator/actions/runs/45168813410), source `702285394`, ran the ordinary moving-clip fixture on the Snapdragon Note 9. The live compositor used six taps with a 33,333 µs period. Both captures were 960 × 540.

| Decoded editor timestamp | Pixels changed by more than 12 MAE between export controls | Preview RGB MAE vs blurred export, affected pixels | Preview RGB MAE vs sharp export, affected pixels | Whole-frame MAE, blurred / sharp |
| --- | ---: | ---: | ---: | ---: |
| 366,666 µs, moving clip | 17,160 | 2.97 | 51.97 | 0.24 / 1.86 |
| 733,333 µs, before the 735 ms HOLD edge | 0 | No affected region | No affected region | 0.22 / 0.22 |

The moving preview was 17.5× closer to the blurred export over the affected region. The HOLD frame was close to both matching controls. The previous APK was restored with its SHA-256 verified, and the device claim was released. This fixture has one moving ordinary SDR clip. Mixed scope, unsupported clips, and sustained performance have separate gates.

Raw evidence is in `C:/workspace/motion-c2-evidence/c3-preview-spine-*`. The original paired controls are `linear-blur.mp4` and `linear-sharp.mp4` in the same folder.
