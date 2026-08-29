# FINDING 2026-08-29 — the remaining GL gap is animated content, and the fix is cheaper than the one that was rejected

**Survey only. No code changed.** Read-only pass over the preview compositor while
`SPEC_20260829_AUDIO_SYNC_TRUTH`, `_CAPTION_LAYERS` and `_KEYFRAME_SHAPES` were in flight.

JoyRaptor asked two things: whether captions could live in GL, and whether "everything in GL"
would make the app slower. The answer to both turns on one measurement that was taken
correctly and then generalised too far.

---

## 1. State of the migration — further along than the handoff suggests

There is already **one z-order authority**: `LayerPreviewController.orderedVisualItems`,
which sorts lanes by `Track::getZIndex` and honours hidden lanes and per-object eyes.
`FxLivePreviewController` calls it (lines 645, 668, 693, 764). `FINDING_20260818_ZORDER`
said neither renderer called it; that is **out of date** — the preview GL chain does.

Items are promoted onto the GL path by a chain of rules in
`FxLivePreviewController.refresh()` (~line 300-365):

| Rule | What it promotes | Status |
|---|---|---|
| `glImageOverlays` / `wantsGlExport` | images with fx, chroma key, blend mode **or a mask** | live |
| `maskedImageOverlays` | masked images (pre-3A.1 path) | live, now redundant — see §2 |
| `plainImagesBelowBlend` (3A.2) | plain images under a blending image | live |
| `plainTextsBelowBlend` / `plainSpritesBelowBlend` | text/sprite under a blending image | live, **STATIC ONLY** |

**JoyRaptor's mask bug is already fixed.** He reported that a masked image showed no mask
until he added a blend mode. That was true before "3A.1": `hasExportMask()` is now inside
`wantsGlExport()`, so a masked image routes to GL on its own. The comment at
`FxLivePreviewController:313-317` documents exactly this. Masking is implemented **only**
in GLSL (`model/MaskSdf.GLSL_MASK_FN`, uniforms `uPipMaskOn`/`uPipMaskGeo` in
`FxPreviewTextureView:1688`); `compositor/LayerImageOverlayView` contains the string
"mask" zero times. So "the mask needed a blend mode" was precisely "the mask needed GL",
and that is now automatic. **Worth re-testing on device before assuming it still bites.**

---

## 2. The real remaining gap

`FxLivePreviewController.buildBelowBlendBitmap()` (~line 754) rasterises below-blend
text/sprite into a full-frame bitmap so a blend above has something to sample. It drops
animated items on the floor:

```java
if (tto.isAnimated()) continue;                                            // text
if (!sso.getKeyframes().isEmpty() || sso.getFrameTrack().size() > 1) continue;  // sprite
```

The reasoning is recorded honestly at line 748:

> "Animated items would need per-frame raster (every frame the transform changes) and
> measured ~17ms on Note 9 for full-frame text raster every frame, blowing the 16.6ms
> budget."

**The measurement is right. The conclusion does not follow.**

The four properties that make an item "animated" here are read at lines 797–820:
`animatedOpacity`, `animatedSizeFraction`, `animatedCenterX/Y`, `animatedRotation`.

**Every one of those is a quad transform, not a pixel change.** Position, scale, rotation
and alpha cost nothing in GL — and `FxPreviewTextureView.Pip` **already carries exactly
these fields** (`cx, cy, halfW, halfH, rotationDeg, alpha`) and already uploads them per
frame for PiPs.

So the 17 ms was measured for the wrong strategy. Re-rasterising the glyphs every frame is
expensive; re-rasterising them **never** and moving the quad they live on is free. A
re-raster is needed only when the *content* changes — the string, font, colour, or a
per-word highlight — which for a caption is a few times a second, and for a title is once.

**Cache key today:** `videoW`, `videoH`, `playheadMs`, the visible-id set, project
duration. **`playheadMs` is in the key**, so even the static bitmap is rebuilt on every
playhead tick — a full-frame `ARGB_8888` allocation (~8.3 MB at 1080p), a full clear, and
a full text raster, roughly 20× a second during playback. Keying on rendered CONTENT
instead of on time would make static text raster once for the whole project. That is a
straight performance win independent of everything else here, and it is in JoyRaptor's "make
it faster across the board" bucket.

---

## 3. What this means for captions (JoyRaptor's actual question)

**Captions in GL would be the same speed or faster, not slower.** Text rasterisation
happens on the CPU either way; GL only changes where the resulting bitmap is composited.
A caption's glyphs change on a cue boundary — ~136 times in JoyRaptor's 7-minute song — and
its animation is scale/position/alpha, which is quad transform. So: raster per cue, cache
the texture, transform per frame.

This is the same "keep one and advance it, do not pre-compute a timeline" move that took
export from 15 minutes to 1 m 38 s, and the same one JoyRaptor arrived at himself for the
slide object when he rejected pre-rendering 12,600 PNGs.

**The visualizer is a different case and should be judged separately.** It is not in the
GL composite at all — `visibleWaveformOverlays` feeds a Canvas view and export slots only.
It is genuinely per-frame, so the texture-cache argument does not apply. But it draws
**bars, not glyphs**, and geometry is the cheap thing in GL. My read is that the visualizer
is cheap to render in GL and merely laborious to move. **That is inference from the call
sites, not a measurement — do not quote it as a finding until someone profiles it.**

---

## 4. Recommended shape, if this becomes a spec

Not a big-bang migration. One z-ordered GL compositor taking two kinds of layer —
**textures you upload** and **quads you transform** — with object types moved onto it one
at a time, cheapest first: images (done) → static text/sprite (done) → **animated
text/sprite** (§2, the open gap) → captions → visualizer (measure first).

Two rules that must hold, both learned the hard way in this repo:

1. **One z-order authority.** `orderedVisualItems` already is it. JoyRaptor's original masked-
   image confusion was two orderings disagreeing; a migration that leaves two has spent
   the effort and kept the bug.
2. **Raster on content change, transform per frame.** If any new code rasterises inside a
   per-frame path, it will hit the same 17 ms wall and get the same wrong conclusion drawn
   about it.

---

## 5. Sequencing note

None of this should start until `CAPTION_LAYERS` lands — it changes how many caption
surfaces exist, and moving captions to GL before that is settled would be redone.
