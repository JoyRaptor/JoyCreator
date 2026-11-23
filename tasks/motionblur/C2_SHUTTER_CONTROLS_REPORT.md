# C2 shutter settings checkpoint

Renderer source 7e5eddddab345423aa9aaa96b33e6df0dc3c7357. [Exact Android APK](https://github.com/JoyRaptor/JoyCreator/actions/runs/45099407878) and [Studio CI](https://github.com/JoyRaptor/JoyCreator/actions/runs/45099423812) succeeded. The focused JVM motion suite passed 12,710 assertions. The wrong-settings control failed at the expected partial-Mix assertion.

Rigid items save a shutter angle, timing, custom phase and Mix. Natural remains the fallback for older projects. Presets share the same scheduler as custom settings. The GPU still uses at most six taps. Partial Mix assigns five to the exposure and one to the unblurred pose. Sprite-cell discontinuities are resolved over the full selected exposure.

## Snapdragon Note 9

| Check | Result |
|---|---|
| Persistence | A 180° Trailing preset loaded from disk at 100%, 40% and 0% Mix before export. |
| Trailing direction | Full exposure pulled the moving object's linear-luma centroid back 6.69–8.57 px on ten consecutive frames. |
| Partial mix | On pixels differing by at least 20/255 between the sharp and full-blur results, actual 40% Mix gave 11.31–14.66/255 error against a linear-light 40% reconstruction. Comparing against the sharp result gave 32.49–34.54/255 and against full blur gave 47.11–50.55/255. |
| Zero mix | All 58 decoded frames matched the blur-off export at the same presentation timestamps. |
| Device | Verified the prior APK hash after both sessions and released the claim. Thermal status was 0 in the partial- and zero-Mix session. |

Local image evidence is in controls-preset-compare-7e5edddda.json. The saved-settings debug fixture covers a retimed spine clip. The other export routes use the same controls but were not re-exported with non-default settings in this checkpoint.

The first editor surface is at 6d8fae49662e1fccf2a8c00dc9ce023555aefd19. [Editor APK](https://github.com/JoyRaptor/JoyCreator/actions/runs/45100118857) and [editor CI](https://github.com/JoyRaptor/JoyCreator/actions/runs/45100442378) succeeded. On the Note 9, the editor initially showed Natural and disabled per-item blur. Applying Trailing and enabling the clip persisted both fields to disk. Undo restored Natural and the unchecked box. Redo restored Trailing and the checked box. Android thermal status remained 0, and the prior APK was restored by verified hash. The project master switch is in testing. Inspect Frame, live preview and effect layers remain open.
