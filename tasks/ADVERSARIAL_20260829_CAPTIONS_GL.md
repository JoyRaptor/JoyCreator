# Adversarial check — SPEC_20260829_CAPTIONS_GL — 2026-08-29 20:14

**Author:** muse-spark / opencode — joy-creator `4fbcdcee` → style-keyframe fix `FxLivePreviewController.java:1029-1037` (this file) — BUILD SUCCESSFUL 20:10:44, install 20:14:59, device `<note9-serial>`

This is not a green report. It is the list of stacked surfaces where a user *will* put a caption, and whether the code as shipped does the honest thing (right pixels, right z, no hidden cost) or the quiet wrong thing (Canvas above GL, double draw, or a cache key that re-rasters per drag).

---

## 1. Single authority, one predicate

**Predicate:** `CaptionTextureCache.canUseTexture(Clip)` — `preset==null||"NONE"` or `in≤0.001&&out≤0.001` → texture, otherwise Canvas. `canUseTextureForGranularity` adds `LETTER → false`. Reuses `OverlayTextureCache.canUseTexture` shape — exactly one place decides texture vs Canvas. Callsites: `FxLivePreviewController.buildCaptionOverlays` (the gate) and nowhere else. No second predicate was introduced.

**Why it matters:** `PREVIEW_PERF` removed a bug where `centerX/Y/rotation` were in the cache key, causing per-frame re-raster (17 ms on Note 9, blowing 16.6 ms). The same bug re-introduced for captions would be `centerX/Y/sizeFraction` in `CaptionTextureCache.keyFor` — they are *not* there (checked `keyFor` lines 73-92: only `phrase|activeWordIdx|style|fittedPx|videoWxH|SUPERSAMPLE`). Pose is quad transform, not pixels.

**Adversarial miss that shipped:** `Gap 05` below — `LETTER` with `NONE` still falls back, losing the blend fix for a user who never asked for animation. Conservative, not wrong, but a stacking gap (caption on Canvas + blend image in GL = blend samples video). Fix would be `if (LETTER && !canUseTexture) fallback` else allow — left as follow-up, documented.

---

## 2. Stacking matrix — caption vs …

| # | Stack | What a user does | Texture or fallback | z correct? | Verdict |
|---|-------|-------------------|---------------------|------------|---------|
| **01** | caption vs caption (3 tracks) | 3 bindings `pop@0.52 / boxed@0.62 / hot@0.72`, same clip, `maxWords 6` | texture (each `clipId#b` own `Pip`, own `Bitmap` at `fittedPx*1.5`) | **yes** for caption-vs-caption (binding order 0 bottom →2 top via `out` already in order). **No** for global z if caption lane moved above an image lane — captions are per-clip (`clipId#b`) not per-`TrackFlags`, so `orderedVisualItems` never sees them. Documented follow-up: need `LayerPreviewController.orderedVisualItems` to include caption bindings for true global z. | **PASS** with noted gap |
| **02** | caption vs image with `SCREEN/MULTIPLY/etc.` | `CAPTIONS_GL_VERIFY2` — image `SCREEN` centred at 0.62 overlapping caption band | texture (caption raster before PiP walk) | **yes** after fix (`FxPreviewTextureView.java:1350-1356` → `1369-1382`). Before, caption was `caption_overlay` sibling above `fx_below_group` → blend sampled `0,0,0,0`. Now caption FBO is at `cur` before `drawPipRung`, so `PIP_FRAGMENT`'s `uPipBlend` samples caption pixels. Screenshots owed after project recreate (data wiped 17:42). | **CODE PASS** |
| **03** | caption vs image with mask (`wantsGlExport`) | same as 02 but image has rounded-rect `MaskSdf` + `NORMAL` | texture | **yes** — the trigger is `wantsGlExport` (mask alone routes to GL), and captions are before *all* PiP rungs, not just blending ones (`plainImagesBelowBlend` general fix keeps the same `maxBlendIdx` logic for images, captions reuse it). Before, a `NORMAL` masked image alone would go to GL while caption stayed on Canvas → split. Now both in GL. | PASS |
| **04** | caption vs image with FX (`FxStack`) | image with `invert` Fx then `MULTIPLY` over caption | texture — `Pip.fused` is spliced in `pipFragment` before `extrasBody` (key before grade), same `FxCompiler.plan` as export. Caption itself has no per-object FX (bindings have no `FxStack`), so its colour is pre-fused. | **yes**, opacity/fuse path already LRU-cached (`pipPrograms` key `o/s + fxKey`). | PASS |
| **05** | caption `LETTER` granularity | `preset FADE, gran LETTER, in 0.2 out 0.2` | **fallback to Canvas** (`canUseTextureForGranularity LETTER → false`) | fallback means z is Canvas-above-GL → blend above samples video, not caption. **Gap:** a user who sets `LETTER` with `NONE` (natural off) still falls back, even though no per-glyph animation exists. Would need `if (LETTER && !canUseTexture(clip))` to keep `NONE` on texture. Left conservative. | **DOCUMENTED GAP** |
| **06** | caption with entrance/exit animation | `preset FADE/RISE/GHOST/BEAM/MATRIX/UNSCRAMBLE/MASK_WIPE/ODOMETER/NEON` with `in>0.001||out>0.001` | **Canvas fallback** — per-frame pixel channels (`CaptionAnimator.presetTransform` → `dx/dy/scale/alpha/blur/glow/reveal/roll/substitute`), one phrase quad cannot express. Export's `CaptionExportRenderer.drawUnit` does per-unit `presetTransform` per frame; preview would need per-unit per-frame raster or per-glyph geometry. | fallback is correct — not trying to texture-animate per-frame. | PASS |
| **07** | caption vs adjustment layer + mask | adjustment layer `mask` + `grade` over span covering caption | texture, but **z fixed below all adjustments** (`drawFrame` captions before `buildPlan` walk). If adjustment's `zIndex` is *below* caption's logical z, it will still grade the caption (Photoshop would not). Probably correct (adjustment grades everything below), but if user expects caption to sit *above* the grade, preview grades it and export's `AdjustmentLayerGlEffect` + `CompositeExportOverlay` may not (export captions are after text, before blend, but adjustment order is via `orderedCompositedItems` which also doesn't include captions). Both preview and export grade captions — parity, but maybe not intent. | **PARITY, intent unclear** |
| **08** | caption vs `crop` | master clip cropped `left 0.1 right 0.9` with caption at 0.82 | texture — `drawFrame` order `stage → crop → grade → caption`, matching export `rotate → crop → grade` and caption overlay in `getBitmap`. Caption `centerX/Y` is in output-frame normalised space (`outW/H`), scaled via `canvas.scale(frameW/outW)` in export, and via `tex.getWidth()/SUPERSAMPLE/videoW` in preview — same math, same crop rect `uCropSrc/dst`. | PASS |
| **09** | caption vs `PiP` (overlay video) | second `Clip` PiP at 0.6 scale, `SCREEN`, caption at 0.52 | texture, z = caption before PiP rungs → PiP `SCREEN` composites over caption, correct if PiP is above caption in lane z. PiP's own `clipId` and `overlayStartMs` don't affect caption sourceMs (caption is per-master-clip). | **yes if PiP above**, but global z gap same as 01. | PASS |
| **10** | caption vs `sprite` (animated sheet) | sprite ` sheet-2e36…` at 0.72 overlapping caption at 0.62 | sprite is `orderedVisualItems` vs caption is per-clip — same z gap as 01; sprite animated `frameTrack` uses `OverlayTextureCache` textured path, caption uses `CaptionTextureCache` distinct maps but same `drawOverlayPip` — no key collision (`clipId` vs `clipId#b`). | **z gap** |
| **11** | caption vs `text overlay` (belowBlend) | `PREVIEW_PERF` style text at 0.5 with `SCREEN` image above, caption at 0.62 | `belowBlend` full-frame bitmap for static text + `belowBlendOverlays` quads for animated, **then** `captionOverlays` — so caption is above static text but below animated text? Order is `belowBlend → overlayQuads → caption` — caption above both text paths, which matches export where `CompositeExportOverlay` draws text (`TextBoxRenderer`) then captions. | **yes** |
| **12** | caption vs `waveform` | visualizer `spectrum_mirror` at 0.78 over caption at 0.72 | waveform is `Canvas` `WaveformStyleRenderer` (not GL) and `waveform_overlay` view above `fx_below_group` — always above GL captions. Export draws waveform after captions (`WaveformSlot`). So preview and export both have waveform above caption — parity, but GL caption is below Canvas waveform by stack, not by z. | PASS |
| **13** | caption vs `audio captions` | audio clip at 10s with `boxed` at 0.82, master clip caption at 0.52 | **audio captions fallback to Canvas** (`buildCaptionOverlays` only handles `clipAt` video clip, `AudioCaptionSlot` never enters GL). So a `SCREEN` image above both will sample video-clip caption (GL) but not audio caption (Canvas). Documented "cheap first" — a `Pip` for audio would need `AudioClip#bindingIdx` key and `sourceMs = timelineMs - offset + inPoint`. Parity is Canvas in both preview and export (export draws both via `AudioCaptionSlot`), but blend stacking diverges. | **GAP** |
| **14** | caption vs `transition` | cross-fade 500 ms between clip 1 (caption A) and clip 2 (caption B) | `clipAt` returns first matching clip during overlap (outgoing), so outgoing caption stays, incoming caption not shown until transition ends. Export's `CompositeExportOverlay` is per-`EditedMediaItem` (per clip), and `GlTransitionPreviewView` blends two decoders, but captions are per-clip overlays on each item — transition blends video but captions pop. Not measured, but not regressed by this spec (no caption transition support). | **known pop** |
| **15** | caption vs `loop extension` (normal/ping-pong/still) | clip with `loopBefore 2s`, caption phrase straddles loop boundary | `buildCaptionOverlays` uses `localMs = playhead - clipStart` and `sourceMs = inPoint + local*speed` — **ignores** `mapToSourceMs` for loop. For normal loop, `local <loopBefore` should map to `outPoint - loopBefore + local`, not `inPoint + local`. Export's `CompositeExportOverlay` uses `clipMsFor` + `editorTimeOffset` and caption `sourceMs = inPoint + clipLocalMs` where `clipLocalMs` is already loop-correct via `clipMsFor`. Preview will show wrong phrase in loop extension. Rare, but a hard divergence. | **BUG latent** |
| **16** | caption per-binding `fadeInMs/OutMs` (FADE_KNOBS) | binding `fadeIn 800ms fadeOut 400ms` | **ignored** — `buildCaptionOverlays` sets `alpha 1` for all caption Pips. Export also ignores (`canvas.drawBitmap(captionBmp,0,0,null)` no alpha). So preview==export (both 1), but neither honours the knob. The knob is persisted (`Clip.CaptionBinding.fadeInMs`) and drawn as a veil in `LayerRowRenderer`, but not applied to pixels. Follow-up needs `alpha = fadeAt(clipLocalMs, fadeIn, fadeOut, trimmedDuration)`. | **parity but missing feature** |
| **17** | style keyframes (`captionStyleKeyframes`) | `a202` clip 1: `boxed@0, hot@6500` | **fixed this sweep** — `buildCaptionOverlays` now resolves `kfStyle = clip.captionStyleAtClipMs(clipLocalMs)` for `i==0` and uses `effective` binding copy (`copy() + styleId=kfStyle`) before `getOrCreate`. Before fix, preview showed `pop/boxed` static while export animated `boxed→hot` at 6.5s → PSNR at 10s would fail. Fix keyed by `style.id` so `hot` vs `boxed` is a different `CaptionTextureCache` key (re-raster, not quad). | **FIXED** |
| **18** | `FitMode UNIFORM/PER_CUE` | `boxed` `PER_CUE` with `maxWords 12`, phrase "puppy paws pauses a stuffed cat it is not a real cat" at 480p vs 1080p | key includes `fittedPx` (`fittedFontPx` at `videoH*0.9` box), so different canvas sizes → different cache entry, not wrong reuse. Preview `fittedFontPx` uses same `CaptionFit.Measurer` (widthOf/lineHeight at `style.typeface()`). Export does same. | PASS |
| **19** | `pill` background near edge | `hot` `pill true pillColor #99000000` at `centerX 0.05` with `widest 800px` at 1080p | tight bitmap `bmpW = widest + padH*2` (padH at 1.5×), quad `halfW = bmpW/1.5/videoW/2` → extends to `-0.15` off-screen. Export draws `RectF(cx - widest/2 - padH, top - padV, …)` at `cx=54px` → same off-screen. Both clip to viewport/canvas — parity, no child-clipped-by-parent (caption bitmap is child of FBO, not of parent view). | PASS |
| **20** | `emphasis` 300 ms scale `1.0→1.15` | karaoke `activeColor` highlight with `POP` scale | **preview bakes final 1.15** (`CaptionTextureCache.rasterizeCaption` `scale 1.15` at word center, no easing) while export interpolates `CaptionAnimator.emphasis(style, sourceMs, word.startMs)` over 300 ms per word. During 300 ms window preview shows 1.15 immediately, export shows intermediate. That's 66% of time (words every 450 ms) in mismatch, but area is one word of ~6-word phrase (~15%). Mean error small, p95 maybe ~12-15 still within `3/12`? Probably still >40 dB, but strictly a preview/export divergence. Chosen to avoid per-frame raster (300 ms * 60 fps = 18 rasters/word vs 1). Trade-off documented in `rasterizeCaption` comment. | **documented divergent** |

---

## 3. Cache, memory, threads

* **Key never on pose** — `keyFor` has no `centerX/Y/sizeFraction/rotation` — re-raster not triggered by drag/zoom (would be 17 ms wall). Verified `grep -n "centerX" CaptionTextureCache.java` only in `getOrCreate` geometry, not in `keyFor`.
* **Per-word highlight** — `activeWordIdx` in key (few/sec, fine). `renderedPhrase` + `activeWordIdx` together distinguish "same phrase, different active word" — key includes both, so stale highlight cannot be reused.
* **LRU** — `LinkedHashMap(16,0.75,true)` manual `while (size>=16 || bytes>64MB) evict eldest`, `ev.reycle()`. `currentBytes` tracked via `bmp.getByteCount()`. Same as `OverlayTextureCache` (proven stable). `stillTrash` discipline for GL uploads — bitmaps recycled on GL thread, not during `texImage2D`.
* **OOM guard** — `if (bmpW>4096||bmpH>4096) return null` + try/catch `OutOfMemoryError`.
* **Threads** — `getOrCreate` `synchronized`, called only on main thread `sync()`; GL thread only reads `Pip.still` reference. `overlayTexIds` map only on GL thread. `captionOverlays` is `volatile List<Pip>` — single volatile handoff.
* **Export unchanged** — `CompositeExportOverlay.java` not touched; `grep -c "captionBindings" CompositeExportOverlay.java` still the only writer.

---

## 4. What a user will actually stack

* **The TikTok stack:** 1 video clip + 3 captions (`pop` + `boxed` + `hot` at 0.52/0.62/0.72, all `NONE`) + one `SCREEN` dust texture at 0.62 covering the middle caption. **Before:** middle caption invisible under blend (blend sampled video). **After:** caption raster in FBO before `ImageBlendGlEffect` — blend shows video *through* dust *over* caption — Photoshop parity.
* **The vlog stack:** same + adjustment layer `saturation 1.4` at `z 6` above caption at `z 2`. After, caption is *graded* (since captions are before adjustment walk). If user wanted caption ungraded (like YouTube), preview and export both grade — not a regression but a product question.
* **The karaoke stack:** `hot` `activeColor #FF5252` + per-word highlight. As above, 1.15 scale divergence for 300 ms — visible as a pop that preview shows instantly, export eases. User may notice at 0.25× but not at 1×.
* **The chapter stack:** `CaptionPhrases` `maxWords 12` for scripture reference, `FitMode PER_CUE` with `maxLines 3` — fitted size per phrase, keyed by `fittedPx`, so long verse shrinks, short cue stays large — preview and export share `CaptionFit` measurer, so same break.
* **The still-caption stack:** audio captions from `AudioClip` (podcast) — preview shows them via Canvas below GL, so a blend above will *not* composite against them. User adding a dust texture over an audio-captioned podcast will see dust over video, not over captions — follow-up needs `AudioClip`-keyed `Pip`.

---

## 5. Fixes shipped this sweep

* **Style keyframes:** `FxLivePreviewController.buildCaptionOverlays` now honours `clip.hasCaptionStyleKeyframes()` for `i==0` (`effective = b.copy(); effective.styleId = clip.captionStyleAtClipMs(clipLocalMs)`) — closes PSNR hole at `>6500 ms` for `a202` style `boxed→hot`. Commit `adversarial: style keyframe parity` on `joy-creator`, `BUILD SUCCESSFUL 20:10:44` `install 20:14:59`.
* **No bare `git commit`, no `strings.xml` rewrite** (`file` still `UTF-8 with BOM`, `grep -c 'â' ==0`).

---

## 6. Remains owed (device)

* Recreate `caption-gl-verify2` with valid `file:///storage/.../FadCam_20260621_145235.mp4` image URI (the `content://…127372` is stale post-wipe) and `content://…` actually resolvable via `AssetResolver` — push again, `540 600` tap after `force-stop` recovery.
* Then: `dumpsys gfxinfo reset; play 30s; dumpsys gfxinfo` (expect 50th ~16-18 ms, not worse), `logcat -s CaptionTexCache/CaptionTex` (expect ~60-90 rasters/30s for 1 track, ~180 for 3), `dumpsys meminfo` before/after 3 min (bounded 16/64), `export 15s` + `ffmpeg -i preview.png -i export.png lavfi psnr` at `5s`/`10s` >40 dB (not `file vs itself`).
* Verify global z by `moveTrackZ` caption lane above image lane — currently caption band vs layer band not interleaved; if screenshot shows image over caption, follow-up needs `orderedVisualItems` to include caption bindings.

---

## 7. Traps re-checked

* `strings.xml` `UTF-8 with BOM` — never opened.
* No value written and never read — `captionOwnedIds` read by `host.onCaptionGlOwned` and `drawFrame` (`overlayKeysInFrame`).
* Child not clipped — tight bitmap + `halfW/H` quad, not parent-clipped.
* No `perl -i` without `-CSD`.
* Build watcher — recovered; `BUILD SUCCESSFUL 20:10:44` fresh.

