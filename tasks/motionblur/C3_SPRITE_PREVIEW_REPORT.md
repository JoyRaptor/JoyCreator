# C3 ordinary sprite preview on the Note 9

The Snapdragon Note 9 ran the disposable ordinary atlas fixture at 960 × 540 on the [exact sprite build](https://github.com/JoyRaptor/JoyCreator/actions/runs/45129811449), source `b94f4e9bb`. The debug readback latched decoded timestamps before presentation. The preview recorded six shutter taps at a 33,333 µs period during the run.

| Editor timestamp | Affected pixels | RGB MAE vs blurred export | RGB MAE vs sharp export | Opposite-cell candidates | Result |
| --- | ---: | ---: | ---: | ---: | --- |
| 366,666 µs | 4,794 | 1.53 | 34.71 | 0 | Pass |
| 400,000 µs | 14,995 | 3.28 | 30.22 | 214 | Strict cell-isolation check pending investigation |

Both frames tracked the matched blurred export. The second frame exceeded the opposite-cell detector threshold. This evidence does not establish the source of those pixels.

The device reported thermal status 0 throughout. The original APK was restored and the phone claim was released. The receipt covers pixel parity for one sprite atlas. Sustained timing and mixed-layer coverage are open.

Raw evidence is in `C:/workspace/motion-c2-evidence/c3-preview-sprite-*`. The paired references are `c2-sprite-blur.mp4` and `c2-sprite-sharp.mp4` in the same folder.
