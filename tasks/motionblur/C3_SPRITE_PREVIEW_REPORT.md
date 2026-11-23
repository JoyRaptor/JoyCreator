# C3 ordinary sprite preview on the Note 9

The Snapdragon Note 9 ran the disposable ordinary atlas fixture at 960 × 540 on the [exact sprite build](https://github.com/JoyRaptor/JoyCreator/actions/runs/45129811449), source `b94f4e9bb`. The debug readback latched decoded timestamps before presentation. The preview recorded six shutter taps at a 33,333 µs period during the run.

| Editor timestamp | Affected pixels | RGB MAE vs blurred export | RGB MAE vs sharp export | Opposite-cell candidates | Result |
| --- | ---: | ---: | ---: | ---: | --- |
| 366,666 µs | 4,794 | 1.53 | 34.71 | 0 | Pass |
| 400,000 µs | 14,995 | 3.28 | 30.22 | 214 | Strict cell-isolation check pending investigation |

Both frames tracked the matched blurred export. The second frame exceeded the opposite-cell detector threshold. This evidence does not establish the source of those pixels.

The device reported thermal status 0 throughout. The original APK was restored and the phone claim was released. The receipt covers pixel parity for one sprite atlas. Sustained timing and mixed-layer coverage are open.

Raw evidence is in `C:/workspace/motion-c2-evidence/c3-preview-sprite-*`. The paired references are `c2-sprite-blur.mp4` and `c2-sprite-sharp.mp4` in the same folder.

## Registered cell-color check

The fixture has a red video background. An absolute red count in a wide region includes source footage. In a registered 45 × 36 pixel region within the opaque portion of each cell, average `R − (G + B)/2` was +222.75 before the switch and −195.42 afterwards. The blurred export measured +224.11 and −195.88. Every pixel in the registered interior had the expected chroma sign in both preview frames. This confirms the cell identity and purity in the measured interior. The wide count above cannot determine edge contamination.
