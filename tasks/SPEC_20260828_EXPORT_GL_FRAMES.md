# SPEC — Export speed phase 2: keep PiP frames on the GPU

**Written:** 2026-08-28 · **For:** an external agent · **Owner:** JoyRaptor.

**Read `tasks/LANES.md` first and claim a lane.** Rule 6 (never run gradle) and the
WORKING-TREE HAZARD (`git add` each file as you write it) both apply.

**Do not touch `FaditorEditorActivity.java`.** Another agent is in it. Everything here
lives in `export/`.

---

## 1. Where export speed stands

Measured on JoyRaptor's Note 9, one 46-second project, 720p/Low:

| | Time |
|---|---|
| Before any of this | ~15m 00s |
| One forward decode per source (`a4880de5`) | 3m 49s |
| Fast pixel conversion (`fb23f6dc`) | 1m 47s |
| Seek to first needed frame (`9d35c33b`) | **1m 38s** |

That is 9.2x, and the output was verified **pixel-identical** (PSNR inf) across the
last change. It is still **2.1x slower than realtime**, and JoyRaptor's finished music
video is 7+ minutes — roughly a 15-minute export, iterated more than once.

---

## 2. What is left, measured

From `SEQ_FRAMES` on the last timed run:

```
decoded=764 converted=323 pumpMs=12185 convertMs=7099
decoded=441 converted=194 pumpMs=10046 convertMs=9538
```

`convertMs` is `SequentialFrameReader.imageToBitmap` — the Java YUV→ARGB walk. Roughly
**40% of the remaining wall clock** is spent turning decoded frames into `Bitmap`s that
are then drawn onto a Canvas and handed straight back to the GPU as a texture.

The round trip is: **hardware decoder → CPU bitmap → Canvas → GL texture upload.**
Two of those steps exist only because the frame source was written against
`BitmapOverlay`.

---

## 3. What to build

Decode PiP frames **to a Surface** and composite them as a GL texture, so the pixels
never come back to the CPU.

- `SequentialFrameReader` currently configures `MediaCodec` in ByteBuffer mode with
  `COLOR_FormatYUV420Flexible` and calls `getOutputImage`. Add a Surface path: create
  a `SurfaceTexture` (or `ImageReader` if a texture proves awkward on the Note 9),
  configure the codec to render into it, and expose the texture id + transform matrix.
- `BlendModeGlEffect` already runs a shader over a `PipFrameOverlay` texture. Feed it
  the decoder's texture directly instead of a `BitmapOverlay`.
- The PiP's position/scale/rotation/opacity currently happen on the Canvas in
  `PipFrameOverlay.getBitmap`. Those become a matrix on the GL side.

**Keep the CPU path.** It must remain the fallback for anything the Surface path cannot
serve, exactly as the retriever remained the fallback when the sequential reader landed.
A slow export beats a broken one, and this is JoyRaptor's shipping path.

---

## 4. The hard part: masks

`PipFrameOverlay` clips through `MaskPathBuilder.beginMask` — a Canvas path clip,
time-aware, sharing the preview's mask animator. **There is no GL equivalent today.**

Options, in the order worth trying:

1. Rasterise ONLY the mask to a small alpha bitmap when it changes, upload it as a
   second texture, and multiply in the shader. The mask changes on keyframes, not per
   frame, so this is cheap and cacheable.
2. Keep masked PiPs on the existing CPU path and route only unmasked ones through GL.
   Measure how common each is in JoyRaptor's projects before choosing this.

Option 2 is an acceptable first landing if option 1 proves large. Say which you did.

---

## 5. Acceptance

1. **Pixel parity.** Export the same project before and after. Compare with
   `ffmpeg -i a.mp4 -i b.mp4 -lavfi psnr -f null -`. Report the average and the
   minimum. Anything below ~40dB average needs explaining, not waving through.
2. **Timing.** Report `SEQ_FRAMES` lines and total wall clock before and after, on the
   same project and settings.
3. **Masks, blend modes and track mattes still render.** A project using each.
4. **The fallback is reachable.** Force it and confirm the export still completes.

---

## 6. Traps

**6.1 — `r_frame_rate = 90000/1`.** FadCam's own recordings declare the MP4 timescale
as the frame rate, so media3 asks the hardware decoder for "1080x1920 at 90000fps" and
is told no. `ExportManager.hardwareFirstAssetLoaderFactory` works around it. If you
create decoders yourself, you inherit this problem.

**6.2 — Fragmented MP4 does not seek.** FadCam records fMP4 without sidx. `seekTo` on
one SCANS the file: measured at over 45 seconds for a single seek on a 46-minute
source, uninterruptible. `SequentialFrameReader.isFragmented()` sniffs for a `moof` box
BEFORE seeking, and that ordering is not optional.

**6.3 — The export runs in its own process.** `com.fadcam.beta:export` survives app
restarts. `adb shell am force-stop com.fadcam.beta` before timing, or you will measure
the old code. This has already produced one false "no change" result.

**6.4 — The v-flip.** `GLUtils` uploads top-row-first; the OES decoder texture does
not. The existing still-image path in `FxPreviewTextureView` compensates. Get this
wrong and the PiP renders upside down.

**6.5 — Do not run gradle** (LANES rule 6). Save, then read `build.log` for a
`BUILD SUCCESSFUL` newer than your last edit.

**6.6 — Encoding.** Never `perl -i` without `-CSD` on these files; a run without it
double-encoded 1446 characters in one file on 2026-08-28 and still compiled. Check
`grep -c 'â' <file>` is 0 before committing.

---

## 7. Out of scope

- The three `CompositeExportOverlay` passes per item. Related, separately sized.
- Anything in `FaditorEditorActivity`.
- Preview rendering.

---

## 8. Reporting

Real line counts from `git diff --numstat`. `build.log`'s last line with its mtime. The
§5 numbers in full, including the PSNR figures. If option 2 was taken for masks, say so
plainly and give the measurement that justified it.
