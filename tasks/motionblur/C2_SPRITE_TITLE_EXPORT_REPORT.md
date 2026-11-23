# C2 sprite and plain-title export checkpoint

The source of the six Note 9 exports is `978d6f1d9bca6614a26ca830a70df3a30665a13f`. [Exact Android build](https://github.com/JoyRaptor/JoyCreator/actions/runs/45085265711) and [Studio CI](https://github.com/JoyRaptor/JoyCreator/actions/runs/45085289054) succeeded. Studio reported 62/62 suites, including the fail-first checks for cell continuity and title placement.

| Case | Snapdragon Note 9 result |
|---|---|
| Sprite cell motion | The enabled fixture used `SpriteBlendGlEffect`; the disabled fixture used the existing Canvas overlay. Two frames before the switch showed a two-sided trail, with inside/outside mean luma differences of about 9.6/0.015 on the 8-bit scale. At 366.667 ms the exposure crosses the 367 ms cell switch. The previous cell extended backward by 72.38 chroma units while the leading band measured −0.17. The red and cyan interiors stayed distinct. |
| Plain title | The first eight decoded frames had zero luma differences. During translation and rotation, seven frames measured 3.02–5.32 mean luma difference inside the title footprint and at most 0.034 outside. The measured contribution from the later pose at the HOLD crossing was 0.00082. An injected 40% later pose measured 0.40007. |
| Previous routes | All 30 decoded picture frames and all 30 main-clip frames matched their prior verified builds exactly. |
| Device condition | Thermal status 0 before and after. Original Face Studio APK restored and hash-verified. Phone released. |

The first sprite slice covers ordinary rigid, unlinked cells without authored cell alignment or puppet deformation. The first title slice covers position and rotation at fixed font size, with the regular multiline layout. Both use the nominal 30 fps shutter clock and require project opt-in. The short fixtures establish output behavior. Sustained preview and export timings are still owed.

Local evidence is in `C:/workspace/motion-c2-evidence`: `c2-sprite-*.mp4`, `c2-title-*.mp4`, `c2-sprite-title-*-regression.mp4` and `result-978d6f1d9-*.json`.
