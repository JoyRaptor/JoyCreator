# SPEC — Captions into the GL compositor

**Written:** 2026-08-29 · **For:** an external agent, FRESH session · **Owner:** JoyRaptor.

**Read `tasks/LANES.md` first** and claim `SPEC_20260829_CAPTIONS_GL`. Rule 6 (never run
gradle), the WORKING-TREE HAZARD, the **no bare `git commit`** corollary apply.

**Read `tasks/FINDING_20260829_GL_ANIMATED_GAP.md` first.** This spec is its §4 step three.

---

## 1. Why, and why it is cheap

JoyRaptor asked whether captions could live in GL and whether that would be slower. It would be
**the same or faster**, for a reason worth stating plainly: text rasterisation happens on
the CPU either way. GL only changes where the resulting bitmap is *composited*.

A caption's glyphs change on a cue boundary — about 136 times in JoyRaptor's 7-minute song — and
its animation is scale, position and opacity, which are quad transforms and effectively
free. So: **raster per cue, cache the texture, transform per frame.**

This also closes the last structural hole in the Photoshop-parity stack. Captions currently
live on a Canvas surface, so a blended image above them composites against the video instead
of against the captions.

The groundwork is done. `SPEC_20260829_PREVIEW_PERF` landed `OverlayTextureCache` (`069ffdfc`)
which already does exactly this for text and sprite overlays: raster once at the authored
size, key on content and never on pose, transform the quad per frame. **Captions are a third
client of that cache, not a new mechanism.**

---

## 2. Prerequisite, now satisfied

This was blocked on `SPEC_20260829_CAPTION_LAYERS`, which landed (`0e4618bd`). A clip now
carries up to three `CaptionBinding`s, each with its own transcript, style, position and
size. **Every binding is its own GL layer** — do not collapse them into one texture, or a
blend between two caption tracks becomes impossible and the z-ordering the caption work just
established is thrown away.

---

## 3. What to build

1. **Route captions through `OverlayTextureCache`.** Key on the rendered cue text, style id,
   font, fitted size, and canvas resolution — **never** on `centerX`, `centerY`,
   `sizeFraction` or the animation phase. If any pose field is in your key you have rebuilt
   the bug `PREVIEW_PERF` removed.
2. **Composite at real z**, from `LayerPreviewController.orderedVisualItems` — the single
   z-order authority. Do not add a second ordering.
3. **Per-word highlight is the hard case.** A karaoke-style active-word colour changes the
   PIXELS, so it re-rasters per word — a few times a second, which is fine — but a per-
   character reveal changes them per frame and is not. Keep the Canvas fallback behind
   **one predicate**, reuse `OverlayTextureCache.canUseTexture` rather than writing a second
   one, and **state in your report exactly which caption animations ride the texture path
   and which fall back.**
4. **Export must not change.** `CompositeExportOverlay` already composites captions before
   the blend. Preview is the side that is wrong. If your change alters a single exported
   pixel, it is wrong.

---

## 4. Files

```
app/src/main/java/com/fadcam/ui/faditor/compositor/OverlayTextureCache.java
app/src/main/java/com/fadcam/ui/faditor/compositor/FxLivePreviewController.java
app/src/main/java/com/fadcam/ui/faditor/compositor/FxPreviewTextureView.java
app/src/main/java/com/fadcam/ui/faditor/compositor/LayerPreviewController.java
app/src/main/java/com/fadcam/ui/faditor/transcript/CaptionOverlayView.java   (may need none)
```

**Out of scope — do not start these here:**

- **The visualizer.** `FINDING_20260829` §3 says it is probably cheap in GL and marks that
  as **unmeasured inference**. Profile it in its own pass; do not act on my guess.
- **Per-word rich text** (bold reference, plain body inside one cue). Genuinely wanted —
  `HANDOFF_20260828` lists it, and `SPEC_20260829_WORD_SYNC` §3.6 needs it for its B/U/I
  buttons — but it is a MODEL change (`TranscriptWord` carrying style runs, both renderers
  honouring them) and bundling it here would make neither reviewable. It needs its own spec
  and a design pass with JoyRaptor.

---

## 5. Acceptance

Paste `phone.sh devices` and `build` (with its date).

1. **Preview equals export.** Export 15s containing captions; compare frames at 5s and 10s.
   PSNR > 40 dB, or identical. **Comparing a file to itself is not this check** — that
   mistake was made on 2026-08-29 and retracted.
2. A blended image above a caption now composites against the **caption**, not the video.
   Before/after screenshots. This is the structural win.
3. Three caption tracks with different styles all render at their correct z.
4. Raster count: play 30s with captions and log rasters. Expect roughly one per cue, not one
   per frame. Report the number.
5. Frame timing before and after (`dumpsys gfxinfo com.fadcam.beta framestats`). **Report it
   even if it got worse.**
6. `dumpsys meminfo` before and after a 3-minute play — the cache is bounded, so no
   monotonic climb.
7. State which caption animation types ride the texture and which fall back.

---

## 6. Traps

- **`strings.xml` is UTF-8 with a BOM** — corrupted to UTF-16 on 2026-08-29, restored in
  `af302055`. Check `file` on it before committing.
- A value written and never read caused three bugs on 2026-08-28. If a caption does not
  appear, check the compositor READS your texture before assuming the upload failed.
- A child pushed outside its parent is clipped by it — relevant if a caption near the canvas
  edge gets cropped.
- Never `perl -i` without `-CSD`. Verify `grep -c 'â' <file>` is 0 on every file touched.
