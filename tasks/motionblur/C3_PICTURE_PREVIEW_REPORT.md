# C3 ordinary-picture preview

Source 4a8f97e1943c46b4f846ade6d01b274e99740e2e. [Exact APK](https://github.com/JoyRaptor/JoyCreator/actions/runs/45127115371) compiled on GitHub. [Studio CI](https://github.com/JoyRaptor/JoyCreator/actions/runs/45127131954) is the matching suite.

| Snapdragon Note 9 check | Result |
|---|---|
| Normal Studio route with the project switch on | The two-picture fixture entered live GLES composition. The motion draw reported editor PTS 266,666 µs, period 33,333 µs, six taps. The previous and current decoder timestamps were paired to the actual latched texture. |
| Same fixture with the project switch off | The ordinary legacy preview remained active. There was no live composite route and no shutter draw. |
| Device state | Battery temperature 26.8–26.9 °C with Android thermal status 0. No fatal error. Original APK restored by SHA-256 and the phone claim released. |

This is a renderer and clock smoke test. Pixel comparison with export, sustained timings, seeks, cut and loop parity, mixed-content promotion, other item types, and Exynos remain open. Raw logs and receipt are in C:/workspace/motion-c2-evidence/c3-preview-picture-*.log and c3-preview-picture-receipt.json.
