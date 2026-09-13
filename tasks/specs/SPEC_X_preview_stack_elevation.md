# SPEC X — Make the preview stack's order unrepresentable-wrong

**Difficulty: SMALL-MEDIUM. One list, one method, and deleting a convention.**
**Read `_RULES_READ_FIRST.md` first, then `tasks/GIT_PRACTICE.md` — the identity rule.**

JoyRaptor, 2026-09-13, after the SPEC-bug-3 fix landed:

> "How do we stop being ad-hoc and build properly?"

He is asking the right question about the right thing. What shipped on 2026-09-13 FIXED his
symptom — captions were being covered by image overlays — but it fixed it with a convention, and a
convention is a bug waiting for the next person who does not know about it.

---

## What is there now, and why it is not enough

`player_container` holds the preview stack. The layout declares the order, bottom to top:

```
waveform_overlay → layer_image_overlay → sprite_overlay_layer → overlay_layer
  → audio_caption_overlay → caption_overlay → caption_style_bar → crop_overlay
  → safe_zone_overlay        (+ previewHandlesOverlay, added programmatically)
```

In a `FrameLayout` that declaration order IS the z order — until somebody calls
`bringToFront()`, which raises a child above **every** sibling, not just the one the caller had in
mind. Two slide paths call it on `overlayLayer` meaning "above the slide", and each silently put
every image above the captions as well. The one call that put the captions back was gated on the
project having waveform visualizers, so a project without one never recovered.

The 2026-09-13 fix added `FaditorEditorActivity.restorePreviewStackOrder()`: one method that names
the stack bottom-to-top and raises each member in turn, restoring exactly the XML arrangement. The
two ad-hoc calls now call it afterwards.

**That is still a convention.** It works only for as long as every future `bringToFront()` on a
member of this stack remembers to call it — which is the same fragility one level up, and the next
person will not know. There are already `bringToFront()` calls in this file at ~10538, ~11381,
~13608, ~24644, ~24681, ~24718, ~24890 and ~24922.

## The change

**Give every member of the stack an explicit elevation from ONE named list, and let elevation —
not child order — decide.**

Android composites by `Z` (elevation + translationZ) first and falls back to child order only for
ties. Once every member carries a distinct elevation, `bringToFront()` **cannot** reorder the
stack: it changes child order, and child order no longer decides. The ad-hoc call stops being
dangerous instead of needing to be remembered. Touch dispatch follows the same ordering, so the
"who receives this touch" question gets the same answer as "who is drawn on top" — which is the
user's mental model and currently is not guaranteed.

### What to build

1. **One list, one place.** A single ordered declaration of the preview stack — id and elevation
   together, bottom to top — and one method that applies it. Put it where a reader looking for
   "what is on top of what" would look, and say in its doc that this is the only answer to that
   question.
2. **Leave gaps between the numbers** (e.g. 1, 2, 3 … not 1.0, 1.1). Something will need to be
   inserted between two existing layers, and it should not require renumbering.
3. **Apply it once, at setup.** Elevation is sticky, so this is not a per-frame or per-selection
   concern. Applying it more than once is harmless but should not be necessary — if you find you
   need to re-apply it, something else is fighting you and THAT is the bug to report.
4. **Fold in the programmatic layers.** `previewHandlesOverlay` (8dp), `transformOverlay` (8dp)
   and `keyframeRibbon` (10dp) already carry hand-set elevations, and two of them are equal —
   which means their relative order is currently decided by child order after all. They belong in
   the same list with distinct values.
5. **Then delete `restorePreviewStackOrder()` and the calls to it.** If it is still needed,
   the elevations are not doing their job and the spec has not landed. Do not keep both.
6. **Keep the two slide `bringToFront()` calls.** They are not the bug — `slidePreview` lives
   inside `fx_below_group`, a different parent, so reordering there is local and legitimate. Say
   in the report which `bringToFront()` calls you kept and why.

### Watch for

- **`elevation` draws a shadow.** A view with elevation and a non-transparent background casts
  one. Every member of this stack is a transparent overlay, so in principle none should — but
  check on the device, and use `setOutlineProvider(ViewOutlineProvider.BACKGROUND)` with a
  transparent background, or `setClipToOutline`, only if a shadow actually appears. Do not
  pre-emptively add code for a problem you have not seen.
- **`clipChildren`.** `player_container` and `fx_below_group` set `clipChildren="false"`.
  Elevation does not change clipping, but confirm nothing that was drawing outside its bounds
  stops doing so.
- **The caption probe still stands.** `PreviewHandlesOverlay`'s `CaptionProbe` (2026-09-13) exists
  because the handles overlay must decline a touch that belongs to a caption *even though it is
  legitimately above it*. Elevation does not replace that and must not remove it.

## Acceptance criteria

1. Every member of the preview stack has an elevation from one declared list; no member's z
   depends on child order.
2. Calling `bringToFront()` on any member — from anywhere, at any time — provably does not change
   what is drawn on top. **Demonstrate this**: call it on `overlayLayer` deliberately and show the
   captions still above the images.
3. `restorePreviewStackOrder()` is gone, along with its call sites.
4. A caption over a full-frame image is still selectable and draggable (the SPEC-bug-3 acceptance
   test — it must not regress).
5. No shadows, no clipping changes, nothing new drawn or hidden. **Device screenshots before and
   after, same project, same frame.**
6. `./gradlew assembleDefaultDebug --console=plain` → BUILD SUCCESSFUL, and the standing
   harnesses stay green.

## Boundaries

- You own the preview-stack wiring in `FaditorEditorActivity` and
  `res/layout/activity_faditor_editor.xml`.
- Do **not** touch `ui/faditor/transform/**`, `transform/mesh/**`, `TransformQuad`,
  `CornerPinTransformHost`, or the bake path.
- Do not change what any layer DRAWS. This is about order and nothing else — if a layer looks
  different afterwards, you have changed something you should not have.
- **The sandbox phone is the test device. NEVER install to or write on the Note 20** — it holds
  JoyRaptor's real projects. If only the Note 20 is attached, say so and stop.

## Deliver

The list you chose and why those numbers. Which `bringToFront()` calls you kept. The proof for
acceptance 2 — an actual demonstration, not an argument. Screenshots for 5. Build verdict;
compile-verified vs device-verified, stated plainly.
