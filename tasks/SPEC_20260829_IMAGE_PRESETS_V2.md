# SPEC — Image presets v2: make them RESET, not stack

**Written:** 2026-08-29 · **For:** an external agent, FRESH session · **Owner:** JoyRaptor.

**Read `tasks/LANES.md` first** and claim `SPEC_20260829_IMAGE_PRESETS_V2`. Rule 6 (never
run gradle), the WORKING-TREE HAZARD, the **no bare `git commit`** corollary, and
`tools/phone.sh` for device work all apply.

**This supersedes the behaviour of `SPEC_20260829_IMAGE_ANIM_PRESETS` §3.2–3.4.** Phases
1–3 landed (`1bc9a273`, `3e91ccc5`) and JoyRaptor used them on a real project. The plumbing is
right; the semantics are wrong. **My original spec is the reason** — it said what keyframes
to write and never said what to clear first.

---

## 1. The governing rule I failed to write down

JoyRaptor, after using it:

> "It's actually stacking animations, which is too complex. **This is supposed to be for
> beginners. It should be fresh each time.** Which is why I was saying before that it needs
> to clear everything. Because it's still keeping position data and scale data when it
> should be clearing those out, centering it, and lining it up with the appropriate edge."

**Applying a preset is a FULL RESET, not a modification.** Every preset application must,
in this order:

1. **Delete every preset-owned keyframe on every track** (`x`, `y`, `scale`, `scaleX/Y`,
   `rotation`, `opacity`) — not just the tracks the new preset will use.
2. **Reset the item's static transform** — centre it, clear rotation, and set scale to the
   cover scale the new preset needs. Leftover position/scale from a previous preset is
   exactly what JoyRaptor is hitting.
3. **Derive the new animation from the image and canvas geometry alone**, as if the item
   had just been dropped in.

The invariant to hold in your head: **applying preset B to an item must give byte-identical
results whether the item previously had preset A, preset B, or nothing.** Write a check for
that; it is the whole spec in one line.

---

## 2. The five defects, in JoyRaptor's words

### 2.1 Trim from the START breaks the animation

> "Trimming the end shorter caused the bookend to be sticky and work correctly. However,
> trimming from the beginning, making it shorter, caused a weird behavior where the end
> keyframe moved in on the animation so that there would be a ramp up until some inner point
> and then it would just stop."

`TextOverlayItem.reflowPresetOwnedKeys(long)` is correct — it deletes owned keys and re-adds
them at `0` and `dur`. **The bug is that the IN-POINT trim path does not call it**, or calls
it with a duration that no longer matches the visible span. Out-point trim works, which
tells you the call site is the problem, not the maths.

Find every path that changes an image item's start or duration and make all of them reflow.
**One helper, called from every trim/move site** — not a copy per site. Two answers to one
question caused three separate bugs on 2026-08-28.

### 2.2 Presets stack instead of replacing

> "If I try a few zoom options and then go back to it, well, now I have zoom on top of a
> pan, it didn't clear the keyframes and it didn't center the position back."

See §1. This is the headline fix.

### 2.3 PAN on a landscape image over a portrait canvas does the wrong thing

> "When I say pan and replace, it should stretch the image that is landscape to the full
> height of the portrait canvas and then pan from the edge of the image on one side to the
> edge of the image on the other."

The rule, stated generally: **scale so the image covers the canvas on the axis it is
PANNING ACROSS, then travel from one image edge flush with that canvas edge to the other.**

- Landscape image, portrait canvas, horizontal pan → scale to canvas HEIGHT, then pan the
  full horizontal overhang.
- Tall image, landscape canvas, vertical pan → scale to canvas WIDTH, pan the vertical
  overhang.
- **Travel the full overhang, not 90% of it.** The current implementation deliberately
  under-travels to guarantee no background peek; once the cover scale is correct, edge-to-
  edge is exactly the no-peek bound. Under-travelling is a visible loss of the move.
- No overhang on the pan axis → the pan is impossible; refuse with a message (already
  implemented for square-on-square, generalise it).

### 2.4 Zoom focal point moves the whole animation

> "I had the zoom where it correctly did the zoom out and it was covering everything, but
> then I wanted to change the focal point. I found that that focal point wasn't for that
> keyframe, nor did it add a keyframe — instead it just moved the whole animation relative,
> so now when it zoomed out it was actually having stuff behind it peek through because it
> was no longer aligned."

Dragging in the preview while a ZOOM preset is active must edit **the preset's focal point
parameter** (`zoomCenterX/Y`) and then **re-derive both owned keyframes from scratch**,
re-running the cover-scale computation.

It must NOT translate existing keyframe values by the drag delta. That is what breaks the
no-peek guarantee: the scale was computed for the old focal point and is no longer
sufficient for the new one.

**After any focal-point change, the no-peek check must still hold at every sampled point.**
If a focal point would require more scale, increase the scale — silently and automatically.
The beginner promise is that background never peeks, and that outranks preserving the
user's exact zoom amount.

### 2.5 SLIDE starts on-screen instead of off it

> "For the sliding animations, they don't quite work either. They need to start exactly off
> screen and then slide on screen, like you're moving to a next slide."

A `SLIDE_IN_*` must begin with the image **completely outside the canvas on the named
edge** — its trailing edge exactly flush with the canvas edge, not one pixel inside — and
end in its resting position. `SLIDE_IN_LEFT` means it enters FROM the left.

Compute the off-screen start from the item's covered size, not from a fixed fraction: an
image scaled to cover needs to travel its own half-width plus the canvas half-width to be
fully clear. A guessed offset leaves a sliver visible at t=0, which is exactly what JoyRaptor is
seeing and reads as a broken animation rather than a deliberate one.

**Add `SLIDE_OUT_*` for all four edges too.** JoyRaptor's mental model is presentation slides —
things arrive and things leave. Having only arrivals means every image has to be trimmed to
hide its exit.

Acceptance: at t=0 the canvas shows NO part of the image; at t=1 it is at rest. Sample at
least 8 points and confirm monotonic travel with no overshoot past the resting position
(unless the easing is deliberately an overshoot curve).

### 2.6 "None" is ambiguous

> "Under animation presets is None. Naturally, I wonder how is this different than clear all
> keyframes?"

Because it currently is not different. Fix by making it explicit — the chip becomes
**"No animation (reset)"**, and it performs the same full reset as §1 with no keys written:
centred, cover-scaled, static. Then say so in a one-line hint under the row.

---

## 3. The drawer is too tall

> "The vertical space for the image settings is getting very tall. I'm thinking start, span,
> end can share the line with two nice fit and fill icons, as well as animation presets can
> also be on the same line."

Compact to **one row**: `start · span · end` numeric fields, then a **Fit** icon, a **Fill**
icon, and an **animation** button.

For the animation button, **reuse the existing "animate A with motion lines" icon** already
used for text animation — same idea, so the same glyph. Tapping it opens a picker in the
same style as the existing keyframe-easing picker.

### 3.1 The picker needs animated previews

> "We could have little animations showing a pan box that's a rectangle moving across a
> square. The rectangle can have dotted lines and the square can be solid, representing the
> canvas and the dotted lines being the image that would be panning across… and then slide,
> showing the dotted box going from out of the canvas into the canvas… and a little animated
> zoom in and zoom out. These can all be small, but little previews that are accurate so
> that you know what you're getting."

Each tile is a small looping animation:

- **solid rectangle** = the canvas
- **dotted rectangle** = the image
- the dotted rectangle performs the actual move, on a ~1.5s loop

**Drive every tile from the SAME code that computes the real animation**, exactly as
`EasePickerPopover` renders its curves from `Easing.apply()` so they cannot lie
(`keyframe/Easing.java` states this rule; `KeyframeGlyph` follows it). A tile that is hand-
animated will drift from what the preset does, and then the picker teaches something false.

Pause the loops when the picker is closed — twelve looping animations behind a closed sheet
is pure battery.

---

## 4. Keyframe glyph readability (`KeyframeGlyph`)

JoyRaptor, on the shipped shape language:

> "The green keyframes are a little bit hard to read on the tape. So perhaps in the timeline
> the keyframes should have a small white pixel stroke, whether they be amber, green, or
> whatever."

**Add a 1px white (or high-contrast) stroke around every keyframe glyph on the timeline**,
independent of fill colour. Solves amber-on-amber and green-on-green at once.

> "There is the diagonal on the diamond and the easing curve over the circle — they don't
> really read cleanly. I can tell looking closely what they are, but I think other people
> will think that's just a bug or a glitch. It looks like they're holding six."

He is right, and this is a real design failure of my original spec. The curve detail is
drawn from `Easing.apply()` and is honest, but at timeline size it reads as damage.

**Fix, in order of preference:**

1. **Clip the interior mark to the silhouette** so nothing pokes outside the shape, and
   centre it properly. A contained mark reads as a mark; an escaping one reads as a glitch.
2. **Raise `DETAIL_MIN_DP`** so the interior curve only appears when the glyph is genuinely
   large enough to read it. On the timeline, silhouette-only is fine — the shape already
   carries the family.
3. If it still reads as noise at any usable size, **drop the interior mark on the timeline
   entirely** and keep it only in the ease picker, where the glyph is large.

JoyRaptor explicitly sanctioned option 3: *"unless that S-shaped ease-in-ease-out curve and the
diagonal can be perfectly centred and clipped so they're not poking out of the shape, I
don't think we should use it."* **Try 1+2 first; if you are not confident it reads, take 3
and say so.** He confirmed the shape language itself works — *"they have diamonds for rotate
and opacity and circles for position and scale, that's good."*

---

## 5. Files

```
app/src/main/java/com/fadcam/ui/faditor/model/TextOverlayItem.java     (reset + pan/zoom geometry)
app/src/main/java/com/fadcam/ui/faditor/model/ImageAnimPreset.java
app/src/main/java/com/fadcam/ui/faditor/keyframe/KeyframeGlyph.java    (§4)
app/src/main/java/com/fadcam/ui/faditor/layers/LayerRowRenderer.java   (§4 stroke)
app/src/main/java/com/fadcam/ui/faditor/ImagePresetPicker.java         (NEW — §3.1)
app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java     (drawer row + trim call sites)
```

---

## 6. Acceptance — every item is a thing JoyRaptor hit

Use `bash tools/phone.sh`. Paste `bash tools/phone.sh devices` and `build`.

1. **SLIDE.** `SLIDE_IN_LEFT`: at t=0 no part of the image is on canvas. Screenshot t=0,
   t=0.5, t=1. Repeat for one `SLIDE_OUT_*`.
2. **The invariant.** Apply PAN_RIGHT, then ZOOM_IN, then PAN_RIGHT again. The final state
   must be identical to applying PAN_RIGHT to a fresh item. Screenshot all three, plus the
   fresh reference. **This is the spec in one check.**
2. Landscape image on a PORTRAIT canvas, PAN_RIGHT: image covers full canvas height, starts
   flush left, ends flush right, no background at any sampled point. Say how many points.
3. Trim from the END: bookend follows. Trim from the START: bookend follows. Screenshot
   both, before and after. **§2.1 is the regression JoyRaptor found.**
4. ZOOM_IN, then drag the focal point in the preview: the zoom re-derives, keys stay amber,
   and **no background peeks at any point**. Screenshot start, middle, end.
5. "No animation (reset)": item returns to centred, cover-scaled, static, no keys.
6. Drawer: one row with start/span/end + Fit + Fill + animation. Screenshot before/after
   heights.
7. Preset picker tiles animate, and each tile matches what its preset actually does.
   Screen-record 3 seconds.
8. Timeline keyframes have a visible light stroke and read clearly on a green tape and an
   amber one. Screenshot both, zoomed in and out.
9. State honestly which §4 option you took and why.

---

## 7. Traps

- `getSelectedClip()` returns `getClip(0)` when nothing is selected — on a music project
  that is the auto-blank black spacer. Use `clipUnderPlayhead()`.
- **`strings.xml` is UTF-8 with a BOM.** It was rewritten as UTF-16 on 2026-08-29 and had to
  be restored (`1e5df369`). Never open it with a tool that rewrites encoding; verify
  `file app/src/main/res/values/strings.xml` still says UTF-8 before you commit.
- Never `perl -i` without `-CSD`. Verify `grep -c 'â' <file>` is 0 on every file touched.
- One undo step per user action, including a preset application that clears twenty keys.
