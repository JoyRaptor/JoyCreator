# SPEC — Keyframe shape language: one glyph set, drawn from the curve itself

**Written:** 2026-08-29 · **For:** an external agent · **Owner:** JoyRaptor.

**Read `tasks/LANES.md` first and claim a lane named `SPEC_20260829_KEYFRAME_SHAPES`.**
Rule 6 (never run gradle — save and read `build.log`) and the WORKING-TREE HAZARD
(`git add` each file the moment you write it) are not optional.

**You are an implementer, not a reporter.** "Built" is checked against `build.log`'s last
line and mtime. "Seen on device" is checked against `adb devices`.

---

## 1. Why this exists

JoyRaptor, who is not a developer and is the target user for this:

> "Ease in and ease out always confused me which was which and Adobe's designed key
> shapes did not help, so to this day it confuses me."

Today every keyframe in this app is the same diamond, whatever it does. A `HOLD` key that
freezes the value and a `BOUNCE` key that rebounds look identical. The user has to open
the ease picker to find out what a key does — which means reading a timeline is
impossible at a glance.

**The design principle that settles every question in this spec: the glyph is DRAWN FROM
`Easing.apply()`, so it cannot lie.** `keyframe/Easing.java` already establishes this
rule for the picker thumbnails ("MUST be rendered from `apply` itself so they can't
lie"). This spec extends the same rule to the keyframe glyph. Nobody has to remember
which is "in" and which is "out", because the glyph shows the actual motion.

---

## 2. The one fact that governs the whole design

Read `keyframe/KeyframeTrack.java:79`:

```java
float eased = a.easing.apply(t);   // a == the EARLIER keyframe
```

**A keyframe's easing governs the segment LEAVING it, to the RIGHT.** The glyph on
keyframe A describes the motion from A to B, not the motion arriving at A.

Every glyph must therefore read **rightward** — the curve detail is drawn on the right
half of the shape. Get this backwards and the language teaches the user something false,
which is worse than the undifferentiated diamond we have now.

---

## 3. The shape language

Five silhouettes. `Easing` has fifteen values (`keyframe/Easing.java:17`); they map onto
the five families like this, and the mapping is **the whole contract** — write it once,
in one place, and let every draw site call it.

| Glyph | Family | `Easing` values | Why this shape |
|---|---|---|---|
| ◆ **Diamond** | Linear | `LINEAR` | The universal keyframe mark. Keeping it for "no curve" means the existing app is not re-taught, only extended. |
| ■ **Square** | Hold / step | `HOLD`, `STAIRS_4` | A square is a step. Universally read as "jump to value". |
| ◤ **Ramp** | Eased on one side | `EASE_IN`, `EASE_OUT`, `EASE_IN_EXPO`, `EASE_OUT_EXPO`, `ANTICIPATE`, `OVERSHOOT` | The sloped edge **is** the speed ramp. Direction of the slope = direction of the speed change. Nothing to memorise. |
| ● **Circle** | Eased both sides | `EASE_IN_OUT` | Round = smooth. This is the one that kills the in/out confusion: a shape with no corners means motion with no corners. |
| ⬟ **Pentagon** | Exotic / physical | `SPRING_SOFT`, `SPRING`, `SPRING_BOUNCY`, `BOUNCE` | A novel shape for a curve that does something novel (leaves the 0..1 range, oscillates). |

### 3.1 The detail that makes it honest

The silhouette gives you the family at a glance. **Inside the silhouette, draw the actual
curve** by sampling `Easing.apply(t)` at ~12 points across the right half of the glyph.

- `EASE_IN_EXPO` is visibly steeper than `EASE_IN` — same family, different bite.
- `OVERSHOOT` shows the curve poking outside the ramp's edge, which is exactly what it
  does to the value.
- `SPRING_BOUNCY` visibly wobbles more than `SPRING_SOFT`.

So the user learns five shapes and reads infinite nuance, and no thumbnail can ever drift
out of step with the maths because there is no separate artwork.

At the smallest timeline zoom the glyph is a few pixels wide. **Below `DETAIL_MIN_PX`
(14 dp), draw the silhouette only, no curve detail.** A muddy 6-px squiggle is worse than
a clean 6-px triangle.

### 3.2 State, on top of shape

Shape says *what the key does*. These existing states say *what the key is doing right
now*, and they compose with every shape — do not let one replace the other:

- **Hollow** = playhead is not on this key. **Solid** = playhead is on it.
  (`KeyframeDiamondControl` lines 30–33 already do this for the drawer diamond.)
- **Solid + carved `×`** = "tap removes this key" — keep this exactly as it is.
- **Selected** = the existing selection treatment, unchanged.
- **Preset-owned (amber/yellow)** = reserved. See §6 — do not implement it, but do not
  design it out either.

---

## 4. What to build

### 4.1 `keyframe/KeyframeGlyph.java` (NEW) — the single renderer

```java
public final class KeyframeGlyph {
    public enum Family { LINEAR, HOLD, RAMP, SMOOTH, EXOTIC }

    /** The ONE mapping. Every caller uses this; nobody writes a second switch. */
    public static Family familyOf(Easing e);

    /** Append the glyph for `e` into `out`, centred on (cx, cy) at the given radius,
     *  reading rightward per §2. Curve detail is included only when 2*radius
     *  >= DETAIL_MIN_PX. */
    public static void pathFor(Easing e, float cx, float cy, float radiusPx, Path out);

    /** Short human label for the drawer / legend, e.g. "Ease out (expo)". */
    public static String labelOf(Easing e);
}
```

**No `android.graphics` beyond `Path`, and no view state.** This is a shape factory. If
you find yourself passing a `Canvas` or a `Paint` into it, stop — colour and fill are the
caller's business, because the caller is the one that knows hollow/solid/selected.

### 4.2 Point every draw site at it

There are at least three, and today they do not agree. **Finding all of them is part of
the task** — start from `Path` + `diamond` in these files:

```
KeyframeDiamondControl.java        the drawer control (the ‹ ♦ › widget)
EasePickerPopover.java             the curve picker (line 52 anchors on "the diamond")
timeline/EditorTimelineView.java   keyframes on layer bars  ← another lane may hold this
layers/LayerRowRenderer.java       row-level keyframe marks  ← check
```

Every one of them draws from `KeyframeGlyph.pathFor`. **If two draw sites disagree about
what a shape means, the language is dead** — that is trap 3 in the handoff ("two answers
to one question" caused three separate bugs on 2026-08-28) applied to pixels.

### 4.3 Long-press cycles the family

In `KeyframeDiamondControl`, long-press currently opens the ease picker (D2a) and that
stays. **Add: a two-finger tap, or a long-press-and-drag-up, cycles to the next family's
default** (`LINEAR → HOLD → EASE_OUT → EASE_IN_OUT → SPRING → LINEAR`). Changing
interpolation should not always cost a popover round trip.

Pick whichever gesture does not collide with what is already bound in that view —
`KeyframeDiamondControl`'s header comment lists tap, long-press and horizontal swipe as
taken. **Read it before choosing, and say in your report which gesture you chose and
why.** One undo step per change.

### 4.4 A legend, once

A small **"?"** in the keyframe drawer header opening a sheet that shows the five shapes,
each with its name, its curve drawn live, and one plain sentence. Sixty seconds of
reading buys the whole language.

Language for the sheet — JoyRaptor is the reader, so no jargon:

| | |
|---|---|
| ◆ | **Even** — same speed the whole way. |
| ■ | **Hold** — stays put, then jumps. |
| ◤ | **Ramp** — speeds up or slows down. The slope shows which. |
| ● | **Smooth** — eases away and eases in. The safe default. |
| ⬟ | **Springy** — overshoots, wobbles or bounces. |

Note the sheet does **not** say "ease in" or "ease out" anywhere. That vocabulary is the
thing that confused the owner for years; the shapes replace it. Keep the precise names in
the ease picker for people who want them.

---

## 5. Files

**Claim exactly these:**

```
app/src/main/java/com/fadcam/ui/faditor/keyframe/KeyframeGlyph.java   (NEW)
app/src/main/java/com/fadcam/ui/faditor/KeyframeDiamondControl.java
app/src/main/java/com/fadcam/ui/faditor/EasePickerPopover.java
app/src/main/java/com/fadcam/ui/faditor/timeline/EditorTimelineView.java
app/src/main/java/com/fadcam/ui/faditor/layers/LayerRowRenderer.java
```

⚠️ **`EditorTimelineView` and `LayerRowRenderer` are claimed by the REVIEW lane** in
`LANES.md` (claim dated 2026-08-26). Check whether that claim is live — LANES rule 5:
`git status` is the fallback truth; if those files are clean in the tree, treat the claim
as stale and take them, noting it on the board. **If they are dirty, do §4.1 + §4.2's
drawer/picker half only, and leave the timeline sites for a follow-up.** A partial
landing here is fine; a clobbered file is not.

**Out of scope:** `keyframe/Easing.java` itself — do not add, remove or reorder curves.
Do not touch `KeyframeCodec` (the project format is unchanged by this spec — you are
changing how a key is DRAWN, never what is STORED). If you think you need a model change,
you have misread the spec.

---

## 6. Reserved: preset-owned keyframes (do not build, do not design out)

A following spec adds image animation presets (pan / zoom / slide). JoyRaptor's requirement
for how they must look:

> "they should LOOK like keyframes and you should see the same rubber bands where
> applicable etc, and be able to tweak them as if they were real, so when the transition
> from wizard-type preset gets converted to pro manual edits it feels seamless — you're
> not having to change your mental model of how things work, no element jumps, changes,
> leaves or appears, just a color change on the keys you already know exist."

They will be **real keyframes** carrying a `presetOwned` flag, drawn in amber, that clear
the flag (and turn normal-coloured) the moment the user drags one.

**What that means for you:** `pathFor` must not bake colour, and the hollow/solid/selected
states must be a separate parameter from the shape. If your API makes "amber outline,
ramp shape, solid because the playhead is on it" awkward to express, it is the wrong API.
That is the only thing you need to do about §6.

---

## 7. Acceptance

1. **Build.** `build.log` last line `BUILD SUCCESSFUL`, mtime newer than your last edit.
   Paste both lines.
2. **All fifteen.** A screenshot showing all fifteen `Easing` values rendered as glyphs
   side by side (a throwaway debug row is fine, delete it before committing). Every one
   must be visually distinguishable from every other.
3. **Direction is right.** Place two keyframes, set the first to `EASE_IN`, screenshot;
   set it to `EASE_OUT`, screenshot. The ramps must be mirror images, and the one that
   starts slowly must be the one whose slope starts flat **on its right side** (§2).
   **This is the single check most likely to be got wrong — do it carefully.**
4. **Detail degrades cleanly.** Screenshot the same keyframe at maximum and minimum
   timeline zoom. At minimum zoom it is a clean silhouette, not a smudge.
5. **One renderer.** `grep -rn "Path()" ` across the four draw-site files shows no
   remaining hand-rolled keyframe shape. Paste the grep output.
6. **State still works.** Hollow / solid / carved-`×` still behave as before on a ramp
   and on a circle, not only on the diamond. Screenshot on-key and off-key.
7. **Legend.** Screenshot the sheet.
8. **Device.** Paste `adb devices`. If unplugged: report §7.1 and §7.5 (both are
   checkable without a device) and say plainly that the visual checks are owed. **Do not
   describe screenshots you did not take.**

---

## 8. Traps carried from 2026-08-28

- **Two answers to one question** caused three bugs in one day. This spec is entirely
  about having one answer; the `familyOf` switch exists in exactly one file.
- **A value written and never read** caused three more. If a glyph does not change when
  you change the easing, check that the draw site READS `keyframe.easing` before assuming
  your mapping is wrong.
- **A child pushed outside its parent is clipped by it.** If a pentagon or an overshoot
  curve gets cropped, the arithmetic is probably right and the clip bounds are the
  problem. Four attempts at an unrelated fix were lost to this on 2026-08-28.
- Never `perl -i` without `-CSD`. Verify `grep -c 'â' <file>` is 0 on every file touched.
