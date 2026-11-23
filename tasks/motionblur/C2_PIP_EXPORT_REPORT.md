# C2 picture-in-picture export checkpoint

Source `8d94fc4ac9492cbc47f1914da5fc7907313d7cea`. [Exact Android APK](https://github.com/JoyRaptor/JoyCreator/actions/runs/45096049717) and [Studio CI](https://github.com/JoyRaptor/JoyCreator/actions/runs/45096064612) succeeded. Studio passed 62/62 suites.

The PiP path normalizes the existing decoder texture once into a bounded GPU texture. The shared shutter gathers several placements of that same frame and hands a premultiplied overlay to the existing PiP compositor. It uses the authored editor-clock position, scale and rotation. The 180° centered exposure uses a 30 fps clock and clips to the visible item, edited piece and authored HOLD/STAIRS fences. Stationary placement uses the existing sharp blit. Opt-in PiPs with an active effect, key, mask or resolved matte continue through their existing paths in this slice.

## Snapdragon Note 9

| Check | Result |
|---|---|
| Movement and independent placement | Six moving frames measured 4.74–7.78/255 mean luma difference inside the PiP footprint and at most 0.041/255 outside. |
| Decoded orientation | The upright CPU source reconstruction gave 1.63–3.55/255 luma error where the source distinguishes upright from flipped. The deliberately flipped reconstruction gave 16.88–23.53/255 error. Synthetic wrong-orientation and identical-output controls both failed the corresponding checks. |
| Authored HOLD | The 733.333 ms exposure crosses the jump at 735 ms. The fitted next-pose gain in the first run was 0.00003 across 76,424 pixels. The repeat gave 0.00004. |
| Stationary frames | The first three decoded frames matched in the initial A/B. Across three on/off comparisons the largest pre-motion luma difference was 0.06246/255. Independent blur-off controls differed by up to 0.05984/255, so decoded bit equality across exports is unproved. |
| Prior routes | The 30 decoded frames for each of the sprite, plain title and main-clip regressions matched their previous verified builds exactly. |
| Device and collection | Short checks completed at Android thermal status 0. On the first attempt the renderer encoded the file but Studio failed to copy it into the Joy Creator folder. Subsequent attempts saved and exported normally. Restored the previous Face Studio APK and verified its hash after every session. Phone released. |

Local evidence in `C:/workspace/motion-c2-evidence` includes the PiP A/B and repeated controls, `pip-8d94fc4ac-comparisons.json`, and the device receipts. The nominal cadence, per-item author controls, Inspect Frame, live preview, and sustained performance checks remain open.
