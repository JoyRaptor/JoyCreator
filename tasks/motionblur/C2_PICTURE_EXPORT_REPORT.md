# C2 picture layer SDR export checkpoint

STU-7 · 2026-11-22 · Code `9abf49e3382cfff11964878bfac74696f0209c8d`

An opted-in, plain picture overlay now uses the shared six-sample shutter in its existing GPU layer slot. The picture's bitmap is sampled in isolation. Samples are averaged in linear light and the result uses the existing normal-blend RGB and opacity contract. The frame beneath the picture is copied through outside the swept footprint. The shutter uses the 180° Natural preset at a nominal 30 fps and respects the item range, edited piece boundary, and HOLD/STAIRS discontinuities.

This slice covers ordinary images with a centered pivot. Parent links, custom pivots, pins, meshes, animation presets, image FX, keys, masks and non-normal blend modes continue through their existing paths. The project setting defaults off and the debug fixture provides the opt-in for this checkpoint. Author controls and actual frame cadence remain open.

## Exact build and device evidence

- [Debug APK](https://github.com/JoyRaptor/JoyCreator/actions/runs/45078756299) succeeded. Commit receipt matched. [Studio CI](https://github.com/JoyRaptor/JoyCreator/actions/runs/45078779509) passed 62/62.
- The Snapdragon Note 9 exported two versions of a 960 × 540, 30 fps project. The moving top picture translated and rotated above a separate, stationary picture and the source video. Both exports completed through `GlImageOverlayEffect`.
- All eight decoded frames before motion matched exactly. At seven moving frames, mean luma difference in the swept footprint was 15.09–25.88/255. Outside the footprint, mean difference was at most 0.076/255. After motion, mean full-frame difference peaked at 0.570/255 and declined. The encoded idle frames after motion do not claim bit equality.
- The 733.333 ms frame straddles the authored HOLD jump at 735 ms. Over 57,902 pixels carrying the later-pose signature outside the held picture, the fitted gain of that signature was 0.00014. The deliberately injected 40% later pose measured 0.39975. Passing the two identical sharp files to the motion checker was rejected.
- The main-clip shader's 30 decoded frame hashes matched the already-verified `da3dd40c2` export exactly.
- The phone reported thermal status 2 before testing and 3 after the short export group. These runs verify output. Sustained performance is still open. The previous Face Studio APK was restored in place, its SHA-256 verified, and the phone released.

Local test files are in `C:/workspace/motion-c2-evidence`: `c2-picture-sharp.mp4`, `c2-picture-blur.mp4`, `c2-picture-spine-regression.mp4`, corresponding logs and `picture-9abf49e33.json`.

## Next item integrations

- Fixed-cell sprites can use the isolated raster supplied by `SpriteSheetRenderer.drawCell`. Preserve the sprite compositor's existing alpha interpretation, and fence cell switches with `MotionTimelineBreaks.addSprite`.
- Plain text needs an upright, padded raster from the existing `TextBoxRenderer` before its placement. The current full-frame text export already contains the authored pose, so transforming it again would move and rotate the title twice. Use only the placement channels consumed by the current text renderer and retain its style and opacity.
- The editor author controls, Inspect Frame, PiP, preview and the remaining effect destinations follow the C2 and later acceptance gates.
