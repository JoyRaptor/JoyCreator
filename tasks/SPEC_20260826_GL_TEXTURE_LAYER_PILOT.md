# SPEC — Pilot: one Canvas layer becomes a GL texture layer

**Written:** 2026-08-26 · **For:** an external agent · **Owner ruling:** JoyRaptor.

**The measurement IS the deliverable.** This is a pilot, not a feature. It exists to answer one
question with real numbers on real hardware: *what does compositing a rasterised Canvas layer
as a GL texture cost on JoyRaptor's Note 9?* A working pilot that reports "too expensive, here is
the frame time" is a complete success. Shipping the conversion while dodging the measurement is
a failure, however good it looks.

---

## 1. Why — the structural problem this tests a way out of

The preview is 18 stacked views. `fx_preview_view` (the GL compositor) is 4th from the bottom,
and **13 sibling views sit above it, every one painting over its output unconditionally.**

```
   fx_below_group, canvas_frame, player_view
 → fx_preview_view              ← GL compositor
   transition overlays, image_preview, slide_preview
   sprite_overlay_layer_below, overlay_layer_below
   overlay_video_layer           ← PiP videos
   waveform_overlay, layer_image_overlay
   sprite_overlay_layer, overlay_layer
   audio_caption_overlay, caption_overlay, caption_style_bar
```

That is why z keeps breaking between an image in GL and one on Canvas, why a mask on NORMAL
shifted depth, and why every fix so far has been another "promote this case into GL" rule
(`wantsGlExport`, `plainImagesBelowBlend`, the `fx_below_group` duplicate surfaces). Each rule
covers one case. The stack keeps producing new ones.

If Canvas layers can be composited **inside** the GL pass at their real z, that whole class of
bug ends and the promote rules can be deleted. This pilot converts ONE layer to find out what
that costs.

---

## 2. Scope — exactly one layer

**Convert `layer_image_overlay` (`LayerImageOverlayView`) only.**

Chosen because it is already half-way there: masked, blended, FX'd and keyed images are ALREADY
routed into GL by `TextOverlayItem.wantsGlExport()`, so the "plain" ones left on Canvas are the
remaining half of a split that already exists. Converting them removes a real inconsistency even
if the pilot goes no further.

**Do NOT convert** text, sprites, captions, waveform or the transition overlays. **Visualizer is
explicitly deferred** — JoyRaptor's ruling: "i am ok with deferring a visualizer to get everything
else." It is audio-reactive and changes every frame, so it is the worst case for this technique
and the wrong thing to learn on.

---

## 3. Reuse what exists — do not invent a bitmap→GL path

`FxPreviewTextureView` already uploads and composites bitmaps:

- Cached still textures for PiPs that are not the live decoder's clip (`:216`, `:312-319`)
- `baseStill` / `baseStillUploaded` (`:700-702`) for image-clip bases
- `ImageBaseStillCache` on the controller side, wired to the view's `stillTrash()` so a bitmap
  handed to GL is never recycled underneath it
- A documented `GLUtils` upload convention: **top-row-first, so the still variant flips v**
  (`:133`, `:218`). Get this wrong and the layer renders upside down.

The pilot should feed rasterised Canvas content through that same machinery. If you find it
genuinely cannot carry a full-frame overlay texture, say why in the report before building a
second path.

---

## 4. What to build

1. **Rasterise on demand, not per frame.** `LayerImageOverlayView` draws to a Canvas today.
   Have it draw to an offscreen `Bitmap` when its content changes, and cache that. Content
   changes on edit, on keyframe movement, and on playhead crossings that alter what is visible —
   NOT on every tick. **Per-frame re-rasterisation is the thing most likely to sink this; if you
   end up doing it, measure it and say so.**
2. **Composite the bitmap as a layer in the GL plan at its real z**, from
   `LayerPreviewController.orderedVisualItems` (the ordering fixed in `78032689` — top band row
   carries the highest z and paints last).
3. **Keep the view for interaction.** It stays in the layout as an invisible hit-test surface so
   dragging, selection and handles keep working. Only DRAWING moves.
4. **Do not delete the promote rules yet.** `wantsGlExport` and `plainImagesBelowBlend` stay
   until convergence is decided. Two mechanisms briefly overlapping is fine; a half-removed one
   is not.

---

## 5. The measurement — the actual deliverable

On **JoyRaptor's Note 9 (SM-N960U, Android 10, serial `<note9-serial>`)**, because it is the
oldest device and it sets the ceiling. Report:

- **Frame time** with the layer on Canvas vs as a GL texture — median and worst case, while
  playing, on a project with at least one image overlay.
- **Rasterisation cost**: how long one bitmap draw+upload takes, and how often it actually fires
  during 30s of playback. Frequency matters more than unit cost.
- **Memory**: bitmap bytes held for the texture at preview resolution.
- **Whether anything regressed visually**: z-order against masked/blended images, drag and
  selection, and the seam behaviour fixed in `cb807d5e`.

Then answer plainly: **can the Note 9 carry this for text and sprites too, or not?** A number
and a recommendation. If the answer is no, say which of the fallbacks looks viable —
lower-resolution overlay textures, dirty-region uploads, or abandoning convergence and keeping
the promote rules.

---

## 6. Traps — each of these has already been paid for

**6.1 — Measure, then theorise.** Four theories died against this device family in two days: the
band clamp limiting the PiP, the dead transform path causing the seam squash, blend modes not
compositing image-over-image, and track matte "not existing" (it does — `CompositingSpec.mattePeerId`,
fully built on the export side, absent from the preview renderer). Every one was plausible and
argued from the code. The device is the authority.

**6.2 — `getSelectedClip()` returns the WRONG clip, silently.** It falls back to `getClip(0)`
when nothing is selected (`FaditorEditorActivity:822-832`), and `Timeline.getClip()`
(`model/Timeline.java:289`) is an unguarded `clips.get(index)` that throws on an empty timeline.
This already made the preview render the wrong clip's crop for weeks. Use `clipUnderPlayhead()`.
~120 unaudited call sites remain; do not add one.

**6.3 — The v-flip.** `GLUtils` uploads top-row-first while the OES decoder texture is not. The
existing still path already compensates. A new texture layer that forgets this renders inverted.

**6.4 — Bitmap lifetime across threads.** The GL thread must never touch a recycled bitmap. The
stills cache never recycles; it hands bitmaps to `stillTrash()`. Follow that discipline exactly.

**6.5 — Build protocol.** `tasks/LANES.md` rule 6: **never run gradle/gradlew.** Save, then read
`build.log` for a `BUILD SUCCESSFUL` whose mtime is NEWER than your last edit. The watcher stalls
silently — it prints "Waiting for changes to input files" while ignoring them, and has needed a
manual restart three times in three days. `bash tools/jvm-harness/typecheck.sh` is javac-only;
it passed on a tree gradle could not compile, so it proves nothing alone.

**6.6 — Claim a lane** (LANES.md rule 1) and `git add` each file as you write it (WORKING-TREE
HAZARD: uncommitted work here has been destroyed at least six times). Commit by explicit path.

**6.7 — Never rewrite a whole file for a small edit.** `584904f9` did and introduced a BOM that
stopped the tree compiling (repaired in `07f36175`). Check the first bytes are not `EF BB BF`.

---

## 7. Out of scope

- Converting any other Canvas layer. That decision waits on §5's numbers.
- Deleting the promote rules.
- Track matte in the preview — real and wanted (built in export, missing in
  `FxPreviewTextureView`), but it is Phase 2 and cheaper after convergence.
- The frame-comparison harness that would replace `preview_parity_lint.py`'s name-matching with
  actual pixel comparison. Wanted, separately sized.

---

## 8. Reporting

Real line counts from `git diff --numstat`. The literal last line of `typecheck.sh` and of
`build.log` with its mtime. The §5 numbers in full — those are the point. Say which visual
checks need JoyRaptor's eye rather than claiming them. **If the pilot shows convergence is too
expensive, that is the correct answer and should be reported as a finding, not as a failure.**
