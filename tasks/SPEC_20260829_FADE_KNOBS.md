# SPEC — Fade knobs: outboard grip, dark curtain, no stolen vertical space

**Written:** 2026-08-29 · **For:** an external agent, FRESH session · **Owner:** JoyRaptor.

**Read `tasks/LANES.md` first** and claim `SPEC_20260829_FADE_KNOBS`. Rule 6 (never run
gradle), the WORKING-TREE HAZARD, the **no bare `git commit`** corollary apply. Device:
`.\tools\phone.ps1` (PowerShell) or `bash tools/phone.sh`.

**Background:** `tasks/design/fade_handles.html` — eight options drawn at true device scale.
JoyRaptor read it and chose. **This is the decision, not a menu.**

---

## 1. The decision

> "The solution is #6 knobs with #7's dark curtain. The knobs won't be in the way of trim or
> keyframes or trash can, and I'll still be able to click the layer in a lane above over 95%
> of the tape."

He rejected the study's own recommendation (#3, the envelope strip) for a specific reason
and he is right: **a full-width strip either covers the lane above or pushes it, and
vertical space is premium.** A knob is a ~20dp disc over a single point; a strip is a bar
over the whole width. The knob keeps 95% of the lane above clickable. That trade is the
whole design.

So: **outboard knobs for the grip, a dark curtain inside the clip for the readout.**

---

## 2. What to build

### 2.1 The knobs

- One per fade, floating **above the clip's top edge**, tethered by a hairline stem to the
  exact point in the clip it controls (option 06 in the study).
- Drawn ~20dp; hit target **≥44dp**, which is the entire point — it is decoupled from row
  height, so a 34dp image row and a 76dp audio row get the identical control.
- **Selected item only.** No knobs at rest, which answers "the handles are pretty ugly" by
  mostly not drawing them.
- **Identical for audio volume fades and image opacity fades.** One drawing routine, one hit
  test, shared. JoyRaptor: *"whatever we do for one, we should do for the other."*

### 2.1a THE KNOB MOVES. This is the most important line in the spec.

> "The knobs move right? The previous triangles didn't move and I didn't like that. The knobs
> should be above the trim area if no fade, and inwards if you have applied a fade — inwards
> by as much as you're fading."

**The knob's POSITION IS the readout.** At rest it sits directly above the trim zone at the
clip's edge — fade = 0. Drag it inward and it stays where you put it, and the distance from
the edge IS the fade length, permanently, at a glance.

This is precisely what the old triangles got wrong and why they had to go: they were drawn
at a fixed 20×12dp inset whatever the fade was, so a 0.2s fade and a 4s fade were pixel-
identical. The control showed that a fade COULD exist, never that one DID. Everything else in
this spec is comfort; this is the part that makes the feature work.

Consequences to get right:

- The knob is positioned from the MODEL every layout pass — `fadeInMs` / `fadeOutMs` mapped
  through the timeline's current scale. Never cached in view state, or it drifts on zoom.
- **Zooming re-places it**, because the same fade is a different number of pixels at a
  different zoom. Check this explicitly; it is the easy miss.
- The two knobs must never cross. Clamp each to the other, and to the clip's own length.
- On a clip too short to show both, they collide at the middle: clamp both to the midpoint
  and let the veils meet. Do not hide one.

⚠️ **The top row has nothing above it to float into.** The study flags this and it is the
one real weakness of option 06. Handle it explicitly: reserve a small top pad on the
timeline, or flip the knob to hang below the top edge for the first row only. **Say which
you chose and why.** Do not let it silently clip — a knob you cannot reach on row one is
the same bug in a new place.

### 2.2 The curtain

Inside the clip, the fade region is covered by a **dark veil** whose sloped edge shows the
ramp (option 07). For an image opacity fade this is near-literal: the clip really does go
dark. For audio it reads as the same idea.

The veil is **display only** — the drag target is the knob. That is the pairing JoyRaptor chose:
option 07's veil failed on its own because its drag band was still row-height-bound, and
option 06's knob was unmoored on its own. Together each covers the other's weakness.

### 2.3 The duration readout

> "The fade duration can appear while manipulating the knob — riding the inside edge of the
> triangle that's made from below the knob."

So: **while dragging only**, a duration label ("0.8 s") sits along the inside edge of the
veil's diagonal, below the knob. Not a permanent chip — it appears on touch-down and leaves
on release. Keep it clear of the veil's darkest area so it stays readable.

### 2.4 The edge marker, and what it is FOR

> "We don't need to show that edge on the item — except for possibly a thin colored dotted
> line sharing the object's color so you can line it up or snap it to other objects in other
> lanes."

A **thin dotted vertical line in the object's own colour**, at the fade's inner boundary.
Its job is not decoration — it is an **alignment target across lanes**, so a fade can be
lined up with a cut or another item's fade in a different row.

Make it a real snap target, not just a drawn line: while dragging a knob, snap to other
items' fade boundaries and clip edges in **other lanes**, within the same tolerance the
timeline already uses elsewhere. A dotted line you can see but not snap to is a tease.

---

### 2.5 What else gets fade knobs

JoyRaptor: *"This should be something we have also on the CC… what else could use these?"*

He is describing a general control, not an image feature. **Build it as one component with N
hosts, not as image code someone later copies onto captions.** The rule is simple: *any timed
object with a 0..1 intensity has fade knobs.*

| Host | Property | Note |
|---|---|---|
| Audio clip | volume | exists today |
| Image / video overlay | opacity | exists today (`1bc9a273`) |
| **Caption span** | opacity | **JoyRaptor's ask — see below** |
| Text overlay | opacity | same model as images |
| Sprite / sticker | opacity | same |
| Visualizer | opacity | same |
| **Master spine clip** | video opacity | **fade from / to black — see below** |

**Captions.** Today, making captions go away means keyframing the style to `hidden`, or
cutting the clip and setting the cut half to `hidden`. JoyRaptor named both workarounds. A
caption span with fade knobs replaces both with a drag. Note `SPEC_20260829_CAPTION_LAYERS`
landed up to three bindings per clip, each with its own style and position — so fades are
**per binding**, not per clip. A references track can fade while the lyrics stay up.

**Fade from and to black is the biggest one on this list and nobody asked for it**, because
it is so standard it is invisible until missing. It is the most common edit in film, it is
the same control, and the master spine is the one host that does not have it. If the
component is genuinely host-agnostic you get it nearly free; if wiring it up is awkward, that
is the component telling you it is not general enough yet. **Do the caption case in this
spec** (it is what was asked for) and **report how much work spine fade-to-black would be**
so it can be scheduled honestly rather than assumed cheap.

---

## 3. The open question — multi-select

JoyRaptor, honestly:

> "In the rare instance they are all the same and overlapping… have just one up top of the
> stack, or one that moves them all. Select-all to edit-all is an option. I haven't thought
> through that enough to know if that's a good idea or bad. Does selecting all move trims
> and/or fades the same amount? The same time? Or relative? I don't know what pro
> competition does."

**What the pro tools do**, since he asked:

| Tool | Multi-select fade drag |
|---|---|
| Pro Tools | "Batch Fades" applies the same **absolute length** to every selected clip — a command, not a drag |
| Resolve / Premiere | Dragging with several selected applies the **same delta** to all, each clamped to its own limits |

**The ruling for this app: same DELTA, and the gesture stops at the first item that hits its
limit.** Reasons, in order:

1. If you selected several clips, it is nearly always because you want to preserve their
   relative timing. A same-delta drag preserves it; a same-absolute-length drag destroys it.
2. Stopping the whole gesture at the limiting item keeps the group rigid, so nothing
   silently desynchronises when one clip is shorter than the others. Per-item clamping looks
   right until the moment two items diverge and you cannot tell which moved.
3. It matches what the user can already predict from dragging a multi-selection anywhere
   else in this editor.

Absolute-length batch fades stay available as an explicit **command** ("Set fade… 0.5 s"),
never as a drag. **Do not build that command in this spec** — note it and move on.

**One knob for the group**, drawn at the top of the stack, as JoyRaptor suggested. Not N
overlapping knobs.

**One undo step for the whole gesture**, however many items it moved. Standing ruling: one
press, one step.

**If this section still feels underspecified when you reach it, build the single-item case
completely and correctly and STOP.** Post on `LANES.md` and leave multi-select for a
follow-up. A half-built group behaviour is worse than none, and JoyRaptor has said outright he
has not finished thinking about it.

---

## 4. Fix the hit-test collision while you are here

Independent of the visual design, this is a live bug the study measured:

The fade zone is 20×12dp inboard of the 16dp trim; the delete badge's slop circle is r≈18dp
centred at `x1−25dp`. **They overlap almost entirely**, and `hitTestItem` checks fade
first — so in that strip **the trash is unreachable**, and 8dp lower the fade is unreachable.
`LayerRowRenderer` already carries a debug flag named *"flight recorder for fade/trim/delete
precedence at the contested top corner"*, so the contest was known; that one side always
loses was not.

Moving the grip outboard removes the collision **by construction**. When you do it:

- Delete the old in-row fade zone from `hitTestItem` rather than leaving it as dead
  precedence. A value written and never read caused three bugs on 2026-08-28; a hit zone
  tested and never reachable is the same trap.
- Then **retire `E2_DEBUG`** (it is `true` in production and logs on every top-corner touch).
  It existed for exactly this contest.

---

## 5. Files

```
app/src/main/java/com/fadcam/ui/faditor/layers/LayerRowRenderer.java      (draw + hit-test)
app/src/main/java/com/fadcam/ui/faditor/layers/LayerGestureController.java (drag)
app/src/main/java/com/fadcam/ui/faditor/timeline/EditorTimelineView.java  (top pad, if that is your §2.1 answer)
```

**Out of scope:** the fade MODEL. Audio volume fades and image `imageFadeInMs/OutMs` already
exist and already compose correctly (`1bc9a273`). You are replacing the affordance, not the
data.

---

## 6. Acceptance

`.\tools\phone.ps1 devices` and `build` (with its date) pasted. **Re-derive every tap
coordinate from a screenshot of the build you are testing** — a sweep on 2026-08-29 reported
a false audio regression because it reused stale coordinates after a layout change.

1. Knobs appear on a selected item, on a 76dp audio row and a 34dp image row. Screenshot
   both. Measure and state the actual touch target.
2. Drag a knob: the curtain grows, the duration rides the diagonal, both disappear on
   release. Screen-record 3 s.
3. **The trash is reachable** at the top corner, and so is the fade. Both, on the same clip.
   This is §4 and it is the check most likely to be skipped.
4. Trim still works at both ends; keyframes are still hittable.
5. Top row of the timeline: the knob is reachable. State which fix you used.
6. Tap a clip in the lane above, through where a strip would have been. It selects.
7. The dotted edge line appears in the object's colour and **snaps to an item in another
   lane**.
8. Audio fade and image fade look and behave identically. Screenshot side by side.
8b. **The knob MOVES.** Set a 0.3 s fade and a 3 s fade on the same clip; screenshot both.
   The knob positions must differ visibly and the distance from the edge must match the
   duration. Then **zoom the timeline in and out** and screenshot again: the knob stays on
   the same MOMENT, not the same pixel. This is §2.1a and it is the point of the feature.
8c. **Captions.** A caption binding fades out without keyframing a style to `hidden` and
   without cutting the clip. Screenshot mid-fade. With more than one binding on the clip,
   confirm the fade applies to the right one only.
9. Multi-select, if built: same delta, group stops at the limiting item, one undo. If not
   built, say so plainly.

---

## 7. Traps

- **`strings.xml` is UTF-8 with a BOM** — corrupted to UTF-16 on 2026-08-29, restored in
  `1e5df369`. Run `file` on it before committing.
- A child pushed outside its parent is clipped by it. A knob drawn above a row will be
  clipped by that row unless the parent allows overdraw — four attempts at an unrelated
  feature were lost to exactly this on 2026-08-28. **Expect it and check it first.**
- One undo step per gesture.
- Never `perl -i` without `-CSD`. Verify `grep -c 'â' <file>` is 0 on every file touched.
