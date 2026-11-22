# C1 Studio motion blur route report

STU-7 · 2026-11-22 · Source base `25c3b35d2696e3f161222dad2e08196bba4592ef` on `origin/joy-creator`.

## Fixture and evidence

The debug fixture is a two-clip hard cut. The first clip has 90° display orientation written into the actual MP4 metadata. A title grows from 1× to 3×, a held-cell sprite travels horizontally, a half-opacity picture travels separately, and the existing Invert adjustment sits above a PiP. `MotionRouteFixture` creates the project using the same model setters and project store as Studio. The preview opens `FaditorEditorActivity`. Export uses `ExportDebugActivity` to hand the project snapshot to the real `ExportService`.

This fixture proves routing before there is an authored motion-blur effect. An export that takes the normal re-encode path for this project shows the existing overlay/rotation guard working. C2 still needs a separate proof that a one-clip, motion-only project bypasses direct trim.

| Evidence | Result |
|---|---|
| CPU shutter and keyframe core | 12,681 assertions pass. Four deliberate controls are rejected for rounding the clock, ignoring a cut, truncating a fractional key, and swapping packed velocity axes. |
| Real Studio models | Cut jumps, both dissolve edges, repeat and ping-pong boundaries, sprite cell swaps, fractional link motion, text/image affine, sprite, PiP, spine and sampled scissor pass locally. The unfenced and rounded-parent controls fail as expected. |
| CPU exposure reference | Slide, zoom, spin, parent/child, teleport and still controls fail for the targeted wrong behavior. A 128-versus-256-sample reference writes PNGs and reports coverage and average alpha difference. Still is bit-exact against the one-sample rendering. |
| Existing Studio suites | `timemap`, `fx` and `key` pass locally. |
| Debug route fixture APK | [GitHub Actions 45048325360](https://github.com/JoyRaptor/JoyCreator/actions/runs/45048325360), built from `81c8dc56e`, installed in place with the project's local debug signature. [Studio CI](https://github.com/JoyRaptor/JoyCreator/actions/runs/45048325378): 62/62 suites. |
| Snapdragon Note 9 | Android 10, Adreno 630. Editor seek and full export route observed. Paused PiP discrepancy below. |
| Exynos Note 9 | Owed. |

Bench runs have separate stages. `MotionBenchActivity` feeds a muted bundled clip through the actual GLES 2 Studio preview view at a 1280×720 internal compositor size for 60 seconds, with blur off. TextureView output-latch intervals are recorded as a compositor delivery measure. Compare them with SurfaceFlinger presentation data when available, and keep the two interpretations in the receipt. `MotionGatherCalibration` draws a separate 12-tap GLES 3 calibration over a 1280×720 RGBA8 target and consumes it in the screen pass. It uses the existing VisLab query ring and reports P50/P95/P99 over valid queries. The GLES 3 numbers give a device calibration and do not establish the GLES 2 effect cost. Neither stage measures the final ten-minute release workload.

### Device results · 2026-11-22

- Real Studio 720p baseline: 60.007 s, 1,798 TextureView latch intervals. P50 33.359 ms, P95 50.380 ms, P99 50.880 ms. These measure the compositor output latch.
- Separate 1280×720 GLES 3 12-tap calibration: 180 valid timer queries, P50 1.934 ms, P95 1.948 ms, P99 1.950 ms. Android thermal status 0. Battery temperature 32.5–32.6 °C. This calibration does not time a production motion-blur pass.
- The real editor sought to 0.647 s and 1.293 s around the 1.000 s cut. Both sides showed the authored title, sprite and half-opacity picture. The export service selected full re-encode, composed two items, and wrote a 540×960 H.264 MP4 with 60 frames at 30 fps and 2.000 s duration. Decoded frames near the two seek times show the cut and changing overlay placement.
- **PiP discrepancy:** The export shows the PiP at 0.647 s. The paused editor omits it at 0.647 s on two fixture runs, including after 2.8 s to settle. The preview logs show the PiP routed into the FX composite. This is a route proof with an observed parity gap. C1 adds no motion-blur render stage. Reproduction against a separate pre-C1 APK was not run. Keep this case for the C3/C4 compositor parity check.
- Local evidence: `C:/workspace/motion-c1-evidence`, filenames prefixed `note9-81c8dc56e-`.

For the device proof, claim only the sandbox phone through `tools/phone.ps1`. Start the debug activity `com.fadcam.beta/com.fadcam.ui.faditor.export.ExportDebugActivity` with `--ez motion_route_fixture true --ez open_preview true`. Read the generated test project ID from the `ExportDebug` log. Seek across the cut in Studio. Run the same debug activity with `--es project_id <fixture-id>` to export, and check the export log for the full re-encode path and the generated file. The bench component is `com.fadcam.beta/com.fadcam.ui.faditor.motion.MotionBenchActivity`. Use the `MotionBench` log tag for the receipt. Release the phone afterward.

## Current live and export routes

| Handoff | Current behavior and placement for motion blur |
|---|---|
| CLIP and per-item FX | `studiokit/.../fx/FxEffectDef.java:42` describes spatial and feedback capabilities. `FxCompiler.java:144–194` groups fixed full-resolution passes. A temporal input, sampled item placement and scope ownership belong in the compositor. A future MOTION card needs an explicit pass boundary. `app/.../export/AdjustmentLayerGlEffect.java:95–98,143–226` is single-input and SDR, with one incoming texture and PTS per draw. It cannot request other item states or future frames. |
| Normal preview | `app/.../compositor/FxLivePreviewController.java:397–438,547–589` resolves the active frame, promotes eligible material from Views, then sends a plan to the GL view. `FxPreviewTextureView.java:1713–1740,1875–1897` latches the decoder texture in GLES 2. `:1943–1968` places the spine and the existing below-blend text/sprite/image raster and textured quads. `:1970–2021` runs the PiP/adjustment walk, late overlays and present. For early per-item work, use the producer's own untransformed texture and sample transforms at its draw slot. Later promotion must follow actual lane order and prevent duplicate Canvas/View drawing. |
| Normal export | `app/.../export/ExportManager.java:816–869,6841–6869` decides near-lossless trim versus full re-encode. The fixture is ineligible because it has multiple clips, overlay items and PiP. `:5459,5533,5675,5729` assemble real per-piece video effects. Clip FX precede the spine transform in `:7113–7148`. The per-item sampling seam is before a rigid item is stamped into the composition, after that item's source texture, geometry and local effects are resolved. `:7819–7888` inserts adjustments only relative to PiPs and still appends a trailing text/sprite/caption block. |
| C4 effect scope | Replace the partial PiP-index insertion plus trailing overlay stages with one ordered compositor prefix shared by preview and export. Read only layers below the adjustment, keep higher overlays out of temporal sampling, and honor existing blend and matte dependencies. Preserve clip, transition, presentation, and audio timing order. Move generic ordering only under the card's approval rule. |
| Time authority | `app/.../model/TimeMap.java:54–85,110–150` owns clip starts, seam lengths, cut jumps, piece offsets and legacy clip-local timing. C1 adds `chunkToEditorUs` to retain the Media3 presentation fraction. `MotionTimelineBreaks` reuses these authored boundaries and `SpriteFrameResolver.resolveCellAt`, including transitions to a missing cell. Decoder discontinuities and separate dissolve source identities must be added by the eventual producers. |

Export has a sequential decoder, but C1 found **no retained previous and next source images** in the default path. `app/.../export/SurfaceFrameReader.java:124–129,247–318,376–383` holds the current OES texture, seeks when needed, and overwrites it as the decoder advances. `DecodedFrameCursor.java:18–19` only decides whether that held PTS satisfies the next request. To provide centered footage sampling, own copied 2D textures in a bounded sliding window and retain decoded PTS plus piece identity. Track Media3 queue backpressure and end-of-stream explicitly. Three full 1080p RGBA8 copies use about 23.7 MiB before staging and flow. Keep the first proof to a current/previous/next window and profile the OES copy.

In the live GL view, `FxPreviewTextureView.java:1875` and `:2707` are the source latches for master and PiP. Capture the decoder frame before any FX or project-space mix, and use a separately owned per-source/clip ring with decoded timestamp, source generation, dimensions and orientation. Flush on seek, cut, source or slot reassignment, size/rotation epoch when appropriate, and GL teardown. Discard results from a prior generation. Keep playback sharp until a valid neighbor exists. `FxHistory` holds feedback effect output and cannot serve as source history.

## What stage 2a already built

`db4861136` grouped image and eligible text batches into `GlImageOverlayEffect`, preserved lane order within those runs, and let static text be uploaded once. It also fixed long-export text routing by keeping real text/sprite identities together. The path is still enabled at `ExportManager.java:6122,6157–6173`. `:7606–7774` forms groups of images, text and eligible stamped items and continues to use the same Canvas text drawer for text textures.

There is still Canvas → bitmap → upload in `CompositeExportOverlay.java:670–766,925–975,1126–1230` for text rasterization and ordinary sprite/puppet draws. Warped or blended sprites use `SpriteBlendGlEffect.java:96–105,183–229`, which rasterizes each source sprite or avatar before the GPU stamp. Captions have a tighter GPU route via `GlCaptionEffect` and can stay above the blur. C3/C4 need to combine the existing atlas and glyph raster/upload work with a shared lane-ordered item compositor, cache unchanged source rasters, replace the repeated full-frame Canvas intermediate when possible, and keep certified fallbacks and existing effects outside that compositor's supported domain.

## Inventory and C2 seams

| Source | Current authority and C1 addition |
|---|---|
| Keyframes | `studiokit/.../keyframe/KeyframeTrack.java:64–84` is the original scalar interpolation. The double overload calls it for integral time. `KeyframeSet` provides the corresponding fractional property read. |
| Text, image and sprite | `app/.../model/TextOverlayItem.java:2405–2468,2810–2816` and `app/.../sprite/SpriteOverlayItem.java:392–424` own placement. The adapter uses `TransformQuad.renderQuad` with the existing mirror and pivot rules. The renderer supplies a text raster's measurement. Pins, changing text preset pixels, mesh deformation and cell topology require their own sampled producer path. |
| Parents | `app/.../model/SpaceLink.java:143–191,282–303` owns current segment selection, stored reference pose, switches, scale, rotation and space conversion. A new placement sample walks rigid parents at the same fractional time and reuses the segment and position math. A mesh-pin parent still uses the existing millisecond evaluator. C5 needs a finer resolved mesh-pose source. |
| PiP and spine | `app/.../export/GlPipFrameOverlay.java:217–237` evaluates overlay transform on the timeline and uses `PipPlacement` for fit and quad math. `app/.../model/Clip.java:1997–2017` and `SpineTransform.java:143–171` define the spine's clip-local pose and matrix. |
| Motion bounds | `SweptBounds` covers the four corners at each exact scheduled sample plus a halo. It is conservative for the sampled draw. A continuous or wider reconstruction must supply a proven envelope or use the full relevant frame. |
| Feature switch | `LaunchGate.java:20–27,59–67` and `assets/launch/hidden_features.json` hide launch doors but allow the master hide switch to be disabled. Use an explicit off-by-default motion opt-in for C2 execution and apply the launch gate to visible entry points as appropriate. An effects bypass or hidden label alone is insufficient because export has direct-entry paths. |
| Bench | `app/.../vislab/gl/GpuTimer.java` is the existing multi-stage delayed query ring. `cc/joycreator/textlab/TlGpuTimer.java` is a TextLab-specific alternative. C1 uses the VisLab ring without adding a third implementation. |

Proposed C2 paths: new rigid texture pass under `app/.../motion/gl/`; the off-by-default model/feature guard; `ExportManager` fast-path admission; `SpineTransformExportEffect`, `GlPipFrameOverlay`, `GlImageOverlayEffect` and the current sprite surface for controlled per-item insertion; `tools/jvm-harness/motion/` for comparisons to the C1 reference; and the existing item inspectors and keyframe storage for controls. C2 should reuse the actual item draw route. Keep C3/C4's shared compositor on the same producer and shutter contract.
