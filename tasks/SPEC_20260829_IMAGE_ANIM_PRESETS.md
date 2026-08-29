# SPEC — Image animation presets: documentary moves without hand-keyframing

**Written:** 2026-08-29 · **For:** an external agent (suggested: the KEYFRAME_SHAPES lane,
in a FRESH session — it built the glyph system this depends on, but the spec below carries
everything needed and stale context is not worth its cost) · **Owner:** JoyRaptor.

**Read `tasks/LANES.md` first** and claim a lane named `SPEC_20260829_IMAGE_ANIM_PRESETS`.
Rule 6 (never run gradle), the WORKING-TREE HAZARD (`git add` as you write) and the **no bare
`git commit`** corollary are not optional.

**Read `tasks/SPEC_20260829_KEYFRAME_SHAPES.md` §6.** It reserved the amber preset-owned
keyframe state for exactly this spec, and the glyph renderer was built so colour is the
caller's choice. You are the caller.

---

## 1. What JoyRaptor asked for, and the sentence that governs the design

> "Documentaries often have several key moves for an image."

He wants pan and zoom without hand-keyframing. But the constraint that decides every
implementation question is this one:

> "They should LOOK like keyframes and you should see the same rubber bands where
> applicable etc, and be able to tweak them as if they were real, so when the transition
> from wizard-type preset gets converted to pro manual edits it feels seamless — you're not
> having to change your mental model of how things work, no element jumps, changes, leaves
> or appears, just a color change on the keys you already know exist."

**So: real keyframes in the real model, from the first moment.** Not a descriptor that
renders as fake markers and materialises into keys later. A preset OWNS a set of ordinary
keyframes and rewrites them when the clip is trimmed; the user's first drag hands ownership
over permanently. Nothing appears, nothing vanishes, nothing moves. The amber turns
ordinary.

This also makes the feature a teaching tool: someone who does not yet understand keyframing
gets a working animation made of the real thing, and can poke at it safely.

---

## 2. What already exists

| | |
|---|---|
| An image overlay | `model/TextOverlayItem` with `imageUri != null` (`isImage()`, line 848) |
| Its animation | `KeyframeSet` with named scalar tracks: `X`, `Y`, `SCALE`, `SCALE_X`, `SCALE_Y`, `OPACITY`, `ROTATION` (`keyframe/KeyframeSet.java:20`) |
| One keyframe | `keyframe/Keyframe.java` — `timeMs`, `value`, `easing`. **Add your flag here** |
| Curves | `keyframe/Easing.java`, 15 values. Do not add any |
| Glyph drawing | `keyframe/KeyframeGlyph.java` — `silhouetteFor` / `curveFor`, colour supplied by the caller |
| Persistence | `keyframe/KeyframeCodec.java` writes `{t, v, e}` per key |

---

## 3. What to build

### 3.1 The ownership flag

```java
// keyframe/Keyframe.java
/** True while this key is maintained by an image animation preset (drawn amber).
 *  Cleared permanently the first time the user drags it in the TIMELINE. */
public boolean presetOwned = false;
```

`KeyframeCodec`: write it only when true (`"p": true`), and default false on read, so every
existing project file stays byte-identical and older builds ignore it.

```java
// model/ImageAnimPreset.java  (NEW) — stored on the item alongside its keyframes
public enum Kind { NONE, PAN_LEFT, PAN_RIGHT, PAN_UP, PAN_DOWN,
                   ZOOM_IN, ZOOM_OUT, SLIDE_IN_LEFT, SLIDE_IN_RIGHT,
                   SLIDE_IN_TOP, SLIDE_IN_BOTTOM }
```

The preset stores its `Kind` and its parameters. It is **not** a second source of truth for
the animation — the keyframes are. The preset exists to answer one question: *when this
clip's length changes, where do my two owned keys go?* Answer: the ends.

### 3.2 Sticky bookends

`applyPreset(item, kind)` writes exactly **two** keyframes per affected track, at the item's
first and last visible ms, both `presetOwned = true`.

On a trim or a move of the item, **re-place the owned keys at the new ends** — do not scale
the interior, there is no interior. Two keys, both stuck to the edges. This is why the
preset is a descriptor and the keys are real: the descriptor makes trim-follow a two-line
operation instead of a keyframe-rewriting problem.

**Conversion, and the exact rule:**

- Dragging an owned key **in the timeline** → clear `presetOwned` on **every** key of that
  item, set the preset to `NONE`. The animation is now the user's. **One undo step**, and
  undo restores both the flags and the preset kind. (JoyRaptor's standing ruling: one press,
  one step.)
- Editing zoom / position / rotation **in the preview** → update the preset's parameters and
  rewrite the owned keys. **Does NOT convert.** JoyRaptor: *"adjustable in preview to tweak zoom
  or position rotation doesn't convert, there's special stickiness."*

That asymmetry is deliberate and it is the heart of the feature. The preview is the wizard;
the timeline is the pro tool. Touching the pro tool is what commits you.

### 3.3 Never let the background peek

A pan or zoom moves the image, so the scale that covers the canvas at t=0 may not cover it
at t=1. **Compute the minimum scale that covers the canvas across the WHOLE animation path
and clamp to it.** Sample the path (16 points is plenty), take the worst case, use it.

Re-run whenever the canvas aspect changes. A project that renders square because a 16×16
black spacer set the aspect is a real bug that happened here (LANES, REVIEW lane 2026-08-26)
— an image preset that assumed the old aspect would tear a black edge into the frame.

A square image on a square canvas has nowhere to pan. `PAN_*` on such an item must be
refused with a short message, not silently applied as a no-op animation.

### 3.4 The presets

| Kind | Behaviour |
|---|---|
| `PAN_*` | Cover the canvas, then travel across the long axis. Direction names the travel. |
| `ZOOM_IN` / `ZOOM_OUT` | Region ↔ full. Default region is centred at ~70 %; the user drags it in the preview, which updates the preset and does not convert. |
| `SLIDE_IN_*` | Fit to canvas, then enter from the named edge. Uses `X`/`Y` plus `OPACITY`. |

Default easing on preset keys: `EASE_IN_OUT` (the circle glyph — "smooth", the safe
default). A documentary move that starts and stops abruptly reads as a mistake.

### 3.5 Fit and Fill — build these FIRST, they stand alone

Two buttons, no animation involved, useful the moment they exist:

- **Fit** — scale so the whole image is visible inside the canvas (letterbox).
- **Fill** — scale so the image completely covers the canvas (crop).

JoyRaptor: *"like with desktop wallpaper options — shrink to fit, stretch to fit."* Everyone
already knows this control. **Land this before anything else in this spec**; it is an
afternoon and it is independently valuable if the rest slips.

### 3.6 Opacity fade handles

JoyRaptor: *"a special opacity handling top corner exactly like the volume fade handles for
audio, where it's stackable on any opacity below."*

Two draggable corner handles on the image's timeline bar, dragging inward to set a fade-in
and fade-out. **Build these on the same model and the same gesture code as the audio volume
fade handles.** Find that code and share it — do not write a parallel implementation.
Two implementations of one idea is the "two answers to one question" trap that caused three
bugs on 2026-08-28, and here the user would *feel* the divergence as two things that ought
to behave alike and don't.

"Stackable on any opacity below" means the fade **multiplies** the existing opacity
animation rather than replacing it. So a preset's opacity keys and a hand-dragged fade
compose. Do not have the fade write opacity keyframes.

### 3.7 The replace warning

When a preset is applied to an item that already has an animation:

> You seem to have a custom animation.  **[Replace]  [Keep]**

Trigger it when the item has **more than two keyframes on any track**, or **two that are not
exactly at the ends**. Not when the existing keys are themselves `presetOwned` — swapping one
preset for another is not destructive and must not nag.

### 3.8 Drawing the amber

Owned keys draw in amber through the EXISTING `KeyframeGlyph` renderer, with the existing
hollow / solid / carved-`×` states unchanged. **Colour only.** If you find yourself adding a
sixth silhouette, or a parameter to `pathFor`, stop — `SPEC_20260829_KEYFRAME_SHAPES` §6
was written to make this a colour choice at the draw site and nothing more.

---

## 4. Files, and the order to take them

**Phase 1 — model and engine (start here, no contention):**
```
app/src/main/java/com/fadcam/ui/faditor/keyframe/Keyframe.java        (one field)
app/src/main/java/com/fadcam/ui/faditor/keyframe/KeyframeCodec.java   (read/write it)
app/src/main/java/com/fadcam/ui/faditor/model/ImageAnimPreset.java    (NEW)
app/src/main/java/com/fadcam/ui/faditor/model/TextOverlayItem.java
```

**Phase 2 — drawing:**
```
app/src/main/java/com/fadcam/ui/faditor/layers/LayerRowRenderer.java  (amber + fade handles)
```

**Phase 3 — the drawer UI. DO NOT START until `LANES.md` shows `SPEC_20260829_CAPTION_LAYERS`
as IDLE.**
```
app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java
```

That file is held by the caption lane. Phases 1 and 2 are days of work; by the time you need
phase 3 it should be free. **If it is not, post on `LANES.md` and stop — do not take it.**
An agent silently editing a contended file is how this repo loses work.

**Out of scope:** the export path beyond making it honour the same keyframes (it already
reads `KeyframeSet`, so if you only write keyframes, export follows for free — **verify that
rather than assuming it**), the GL compositor, captions, audio.

---

## 5. Acceptance

1. **Build.** `build.log` last line `BUILD SUCCESSFUL`, mtime newer than your last edit.
   Paste both **with the date** — a twelve-hour timestamp misread happened on 2026-08-29.
2. **Device.** `adb devices` pasted. No device → report the checkable items and say plainly
   the rest is owed. That is acceptable; inventing an observation is not.
3. **Old projects unchanged.** Open a project saved before this change: identical rendering,
   no amber anywhere. Screenshot. **Check this before anything else.**
4. **Fit / Fill.** A tall image and a wide image, each Fit and each Fill, on a 16:9 canvas.
   Four screenshots.
5. **A preset applied.** Apply `ZOOM_IN`. Two amber keys appear at the ends. Screenshot the
   timeline and two preview frames from different times.
6. **Trim follows.** Drag the item's out point. The amber key stays glued to the new end.
   Screenshot before and after.
7. **Preview tweak does NOT convert.** Change the zoom region in the preview. Keys stay
   amber. Screenshot.
8. **Timeline drag DOES convert.** Drag an amber key in the timeline. Every key on that item
   turns ordinary in one step. **Then press undo once** and confirm the amber and the preset
   both come back. Screenshot all three states. **This is the check most likely to be got
   wrong** — a half-undo that restores the colour but not the preset kind is the failure to
   watch for.
9. **No background peek.** Apply `PAN_RIGHT` to an image barely wider than the canvas and
   step through the whole animation. No frame shows canvas background. Say how many
   positions you checked.
10. **Square refuses.** `PAN_*` on a square image on a square canvas gives a message, not a
    dead animation.
11. **Export matches.** Export 10 s containing a preset animation; compare a frame against
    the preview at the same time.
12. **Fades compose.** An item with a preset opacity animation AND a dragged fade handle:
    the fade multiplies, it does not replace. Screenshot mid-fade.

---

## 6. Traps carried forward

- `getSelectedClip()` silently returns `getClip(0)` when nothing is selected — on a music
  project that is the auto-blank black spacer. Use `clipUnderPlayhead()`.
- A value written and never read caused three bugs on 2026-08-28. If amber does not appear,
  check that the draw site READS `presetOwned` before assuming your write is wrong.
- A child pushed outside its parent is clipped by it — relevant to the fade handles at a bar's
  corners.
- Never `perl -i` without `-CSD`. Verify `grep -c 'â' <file>` is 0 on every file touched.
