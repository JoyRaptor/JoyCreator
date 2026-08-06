# SPEC — Image sequences (and the dope sheet)

Status: **BUILT and DEVICE-VERIFIED, 2026-08-06.** Written 2026-08-05 with the user.
Companion to `PLAN_SPRITE_ANIMATION.md` — read that first; this deliberately reuses it.

> ## ✅ 2026-08-06 — SHIPPED. See `tasks/HANDOFF_20260806_IMAGE_SEQUENCES.md`.
>
> Commits `66f2ed7` (model + import), `7712a8f` (tape + the Gradle unblock), `960744a`
> (dope sheet + resize modes + looping), `4ddcb6d` (AI tools + export bound + verification).
>
> **The load-bearing proof:** a 12-frame sequence at 2 fps with all weights ×2 was exported and
> its frames extracted with ffmpeg. Every sampled timestamp (0.5 / 1.5 / 4.5 / 8.5 / 11.5s)
> showed EXACTLY the frame the weight model predicts, and 12.5s — past the sequence's end —
> drew nothing. Preview == export, on pixels.
>
> Also device-verified: run detection ("We found 12 images"), `1/6 min` parsed live into
> 1.2 fps, the tape's uniform thumbnails at each change, "On twos" taking 6.00s → 12.00s with
> ×2 badges, the §5b vertical drag taking frame 3 to ×5 (12.00s → 13.50s, exactly
> (11×2+5)/2fps), and undo/redo.
>
> **What is NOT verified on device** — stated here so this file cannot become the stale status
> line §0 complains about: the AI tools (no API key in the sandbox), the §2a edge-drag and its
> §9c readout, the §6 continues/loop chips, and §3d convert-to-sprite-sheet. All are built,
> type-checked and harness-pinned where the logic is pure.
>
> **§2d does not exist in this document** despite §0.3 pointing at it. The decision it was
> meant to hold was made during implementation: weights ride `SpriteSheet.Preset` and the tick
> domain is the one `SpriteFrameResolver` already computed, so an unweighted preset resolves
> down the identical path. `SequenceTimingTest` pins that against the old arithmetic.

---

## 0. READ THIS FIRST — most of this is ALREADY DESIGNED, and some is already BUILT

User, 2026-08-06: *"that is already how sprite sheets are displayed… I don't want you to reinvent
the wheel either. Keep referencing and do a little bit of research on what the sprite sheet spec
and the sprite sheet code actually does… you can be putting that thought into the parts that are
not built."*

He is right, and an earlier draft of this spec re-derived several things that exist. **The
image-sequence feature is largely `PLAN_SPRITE_ANIMATION`'s Fast-Follow A + a new kind of
"sheet".** Read that plan and these classes before designing anything.

### What is BUILT and must be reused verbatim

| Thing | Where | Why it matters here |
|---|---|---|
| **`FrameTrack`** | `sprite/FrameTrack.java` | The discrete **step/hold** frame primitive. Entries are ITEM-LOCAL ms, sorted, each holding until the next. Deliberately no interpolation — "discrete frames vs eased transforms is the core correctness split". An image sequence is a FrameTrack whose cells are files. |
| **`SpriteFrameResolver.resolveCellAt(sheet, item, timeMs)`** | `sprite/SpriteFrameResolver.java` | **The single pure function that owns ALL frame math** — step-hold lookup, preset phase, wrap, end behaviour. The plan's own words: *"Preview, export, the timeline lane, and AI validation all call this one function — preview/export divergence is impossible by construction. No other code computes a cell index."* This is the "one evaluator" rule, already enforced. Do NOT write a second one for sequences. |
| **Presets in the model** | `SpriteSheet.getPresets()`, `presetById()` | `{id, name, type: loop\|pingpong\|once, fps?, frames:[cellIndex…]}` — a named, reusable, ordered frame run with a cadence. **This is 90% of the sequence timing model already.** |
| **`endBehavior`** | `SpriteOverlayItem` | `hold` \| `loop` \| `pingpong`. §6's looping is largely this. |
| **Tape thumbnails at each key** | `LayerRowRenderer` + plan's "expanded ribbon variant shows cell thumbnails at each key" | Exactly the display the user described. Uniform thumbnails at change points is the EXISTING sprite behaviour, not a new idea. |
| **`describe_sprite_sheet`** | `ai/AIToolExecutor.java` | The only sprite AI tool that exists. Sequences should extend it, not add a parallel one. |
| Decode-once/bounded, LRU, recycle · missing-media relink · `isSimpleTrim` guard | plan §Correctness rules | All already solved and documented. Reuse, don't rediscover. |

### ⚠ The sprite plan's own "REMAINING" list is STALE — verified 2026-08-06
`PLAN_SPRITE_ANIMATION.md:34` says *"FF-B AI sprite tools (AIToolExecutor has 0 sprite refs
today)"*. **It has 26.** Presets are in the model too. Trust the code, not that line — the usual
failure in this repo. What is genuinely still missing:
- **FF-A: the dope-sheet detent and "select keys → make preset" UI.** Model exists, UI does not.
- **FF-B beyond `describe_`**: `set_sprite_grid`, `label_sprite_cells`, `author_sprite_animation`,
  `apply_sprite_proposal` are all absent.

### What is GENUINELY NEW in this spec — spend the thinking here
1. **A sheet whose cells are N FILES, not sub-rects of one bitmap.** `SpriteSheet` addresses a
   cell by grid arithmetic; a sequence addresses it by URI. This is the real structural
   difference and everything else follows from it.
2. **Sequence DETECTION and the import flow** (§3) — no sprite equivalent.
3. **WEIGHTS** (§2) — the one new modelling idea. See §2d for how it folds into the EXISTING
   preset/FrameTrack model rather than replacing it.
4. **Bulk/pattern weight editing** (§5c) — new, and the answer to the tedium risk.
5. **AI batch patterns** (`applyStride`, `applyRamp`) — new tools, but they belong in FF-B
   alongside the sprite ones, not in a separate sequence namespace.

**Build FF-A ONCE, serving sprites and sequences together.** The dope sheet the user wants here is
the dope sheet the sprite plan has been owed since 2026-07-06. Two implementations of it would be
this project's oldest and most expensive mistake repeated.

---

## 1. What this is

Import N still images that form a series and treat them as one timeline object whose picture
changes over time. It serves two audiences that look different and are the same feature:

- **Animators** who render out `frame_0001.png … frame_0240.png` and think in frames, holds and
  "on twos".
- **Everyone else** who has thirty photos and wants a slideshow.

**The user's framing, and it is the design constraint:** *"so simple that they can use it, but so
versatile that power users and animators can really do a lot with it."*

**Sequences and sprite SHEETS are the same idea with different packaging** — one file with a grid
vs many files in a folder. Different animators work each way and both deserve support. So the
rule for this whole spec is: **reuse everything the sprite work already built; implement only
what is genuinely different.**

---

## 2. THE CORE MODEL — frames have WEIGHTS, not durations

Every frame carries a **weight**, default `1`.

```
frame_duration = total_duration × (weight / Σ weights)
```

This one idea collapses every feature below into a single mechanism:

| What the user wants | What it is under the model |
|---|---|
| Animation "on ones" | all weights 1 |
| Hold frame 3 for 5 beats | `weight[3] = 5` |
| "On twos" | every weight = 2 |
| A slideshow with uneven slide lengths | weights are proportions |
| Frame rate | a *display unit*, not a mode — `fps = Σweights / total_duration` |
| "Gradually getting faster" | a descending ramp across the weight array |

**Frames-per-second, seconds-per-frame and percentages are three VIEWS of one array.** Do not
build them as three modes. The user reached this themselves while describing a 60-minute
slideshow: *"that wouldn't be in frames, that would be in, like, percentages."* Both are weights.

**Why weights and not per-frame milliseconds:** resizing the object must not destroy authored
holds. With weights, stretching the object rescales everything and every hold survives
proportionally, for free.

### 2a. The two resize modes (the user's "toggle")
Resizing the object on the timeline can mean one of two things, and both are wanted:

- **RELATIVE (default)** — keep the weights, change `total_duration`. Ten images squeezed to half
  the length are still ten images, each half as long. This is the slideshow/animation default.
- **ABSOLUTE** — keep each frame's resolved milliseconds, change the frame COUNT. Ten one-second
  images squeezed to five seconds shows five frames; trimming from the left vs the right decides
  *which* five. This is the "film strip" mental model.

⚠ **The mode must be visible ON THE TAPE, not only in the drawer.** One handle with two different
destructive behaviours based on invisible state is the exact trap that bit the caret-vs-trim grab
(LEDGER §1f). Different handle shape or colour per mode.

---

## 3. IMPORT

### 3a. Detection
On picking an image, look in the same folder for siblings whose names differ only by a trailing
number (`shot_001.png`, `shot_002.png`, …). Recognise the run and offer it.

**Be conservative — OFFER, never assume.** `IMG_0001 … IMG_0400` in a holiday folder matches this
rule and is not a sequence. Show the count (*"We found 240 images in this sequence"*) and let the
user decline. Cap what is offered and never auto-import.

### 3b. The dialog — ONE question, not two
❌ **Do NOT ask "frames or slideshow?"** They differ only in per-frame duration. Asking it as a
*mode* creates something the user has to understand and can regret.

✅ Ask **how long**, with presets:
- *Animation* (~12 fps)
- *Slideshow* (~3s each)
- and three equivalent entry fields — **frame rate**, **duration per image**, **total duration** —
  which all write the same model. Editing any one updates the others live.

Whatever is chosen is **not binding**; everything is editable afterwards in the drawer and by
dragging the object on the timeline.

### 3c. Duration parsing
Accept the ways people actually type time: `10.5s`, `10.5 sec`, `10.5 seconds`, `1/6 min`,
`00:00:10.5`, `90f` (frames), `2m30s`. Cheap to build, disproportionately delightful.

### 3d. Sprite conversion is NOT an import branch
The user initially wanted a second import path that builds a sprite object instead. **Make it a
`Convert to sprite sheet` ACTION in the sequence drawer**, not a fork at import. At import the
user cannot yet know which they want; as an action it is discoverable later and reversible.

---

## 4. THE TIMELINE TAPE — reuse the sprite tape

The user: *"Currently with a sprite, it shows the sprite on your timeline and it's super helpful…
when a frame changes, that should show up in the timeline tape, really in the same way."*

- Draw a thumbnail **at each frame CHANGE**, not continuously. Unlike video there is nothing
  between changes, and with holds the changes are sparse — which is exactly what makes the tape
  readable rather than a smear.
- **Thumbnails are a UNIFORM size. Their POSITION carries the timing, not their width.**
  (User, 2026-08-06, correcting a false choice in an earlier draft of this spec.) A thumbnail is
  drawn where its frame comes IN; a long hold simply means a long gap before the next one, and
  the lane's own bar colour shows through that gap. So uneven timing is legible *and* honest at
  the same time — scaling thumbnails to duration would have made short frames unreadable to buy
  information that position already carries for free.
- `LayerRowRenderer` already draws sprite frame-swap diamonds (white) and property keyframe
  diamonds (green). Follow those conventions; keep sequence marks visually distinct from both.
- **Looped region:** same thumbnails, drawn slightly darker, so it reads as "this is a repeat".

---

## 5. THE DOPE SHEET (the differentiating feature)

A horizontally scrolling strip of frame thumbnails inside the object's top drawer.

### 5a. Hold = a number over the frame, not a wider frame
The user considered physically widening a held frame and then reconsidered. **The reconsideration
is right.** A 10× hold in a 200-frame sequence makes the strip mostly one image; a count badge is
constant-space and reads instantly. Frames stay uniform width; the weight shows as a badge.

### 5b. The gesture — vertical drag, applied to the SELECTION
**Vertical drag on a frame raises/lowers its weight** (min 1). This is the user's original idea
and it is kept.

The gesture-conflict worry (horizontal browse vs vertical weight vs the drawer's own vertical
scroll) is solved by **claiming the gesture**: when a vertical drag begins on a frame, the strip
calls `requestDisallowInterceptTouchEvent(true)` and owns it until release. Standard Android; no
ambiguity.

**The tedium fix — the drag applies to every SELECTED frame.** With nothing selected it affects
the frame under the finger. This is what stops *"clicking a button 200 times across 20 images"*.

### 5c. Bulk editing — the anti-tedium toolkit
Tedium is the main risk to this feature. Every one of these is cheap under the weight model:

1. **Range select** — tap a frame, shift/long-press another, select the run.
2. **Stride select** — *"every Nth frame, starting at S"*. Directly serves the user's own example
   (*"every 6th animation hold for five frames"*) and is how "on twos" is expressed.
3. **Paint-drag** — hold and sweep horizontally across frames at a vertical offset to set a run of
   weights in one gesture.
4. **Presets** — `On ones` / `On twos` / `On threes` applied to selection or all. One tap for the
   most common animation operation in existence.
5. **Ramp** — set weights interpolating A→B across a selection, with an ease curve. This is
   *"a walk cycle gradually getting faster"* as a single control.
6. **Numeric entry** — exact weight for the selection.
7. **Reverse / shuffle** — order operations that preserve weights.

---

## 6. LOOPING

- **Loop** and **ping-pong** (ping-pong must preserve weights when it mirrors).
- **Open-ended**: continue past the authored frames.

⚠ **The one idea to be careful with.** The user asked for open-ended to run *"until it hits some
other layer in that lane or the end of the project"*. Making an object's duration depend on its
NEIGHBOURS means: adding an unrelated object silently shortens this one, deleting one silently
extends it, and undo must restore a length nobody authored. This codebase already has a ledger
full of "the app moved my clip by itself" incidents and they were expensive to find.

**Resolution:** keep the affordance (a `continues →` marker on the tape), but **resolve it to a
concrete length whenever anything changes**, and show visibly when a neighbour clipped it. Never
silently.

---

## 7. AI INTEGRATION — a first-class requirement

The user: *"we have a full AI integration in this app… the AI should be able to understand this
section as well… and also be able to do intricate batch patterns to help relieve tedium."*

**The weight model is what makes this tractable.** A sequence's entire timing is one integer
array — a shape an LLM can read, reason about and rewrite. Per-frame millisecond durations would
be far harder to pattern over.

### 7a. The AI must be able to READ
Total frames, the weight array, total duration, loop mode, resize mode, which frames are selected.

### 7b. Tool surface (all in frame indices and weights — never pixels or ms where avoidable)
```
sequence.describe(objectId)
sequence.setWeights(objectId, weights[])
sequence.applyStride(objectId, start, every, weight)      // "every 6th frame holds 5"
sequence.applyRamp(objectId, fromIdx, toIdx, w0, w1, ease) // "gradually faster"
sequence.setTotalDuration(objectId, ms)
sequence.setFrameRate(objectId, fps)
sequence.setLoop(objectId, mode)          // NONE | LOOP | PING_PONG
sequence.setResizeMode(objectId, mode)    // RELATIVE | ABSOLUTE
sequence.reorder(objectId, op)            // REVERSE | SHUFFLE
```

### 7c. Rules it inherits
- Route through **`EditScriptApplier`** so snapshot/undo discipline is inherited rather than
  reinvented (see `AUDIT_TRANSITION_INDEX_UNDO.md` and the 2026-08-05 re-verification).
- **One AI edit = ONE undo step**, preserving history behind it — binding user decision,
  LEDGER §4.
- The AI must never report success for something it did not do — the `set_clip_duck` incident
  (LEDGER §3f) is why that tool was removed entirely.

---

## 8. ENGINEERING NOTES

- **Reuse `FrameTrack`.** `SpriteOverlayItem` already keys frame indices over time and
  `LayerRowRenderer` already draws its marks. A sequence may be *a FrameTrack over a list of image
  URIs*. **Read that code before designing anything parallel** — it may already answer "which
  frame at time t", the tape rendering, and the keyframe UI.
- **Memory.** A 500-frame 4K sequence must never decode all frames. The image-overlay export path
  already solved this (downsample against the OUTPUT size, cache per clip lifetime, recycle on
  release — LEDGER §1d). Reuse that discipline; do not rediscover it.
- **Missing files.** Sequences break the instant one file is renamed. Register **all N** files
  with `ProjectIntegrity` (detect) and `ProjectConsolidator` (make self-contained), or
  "Consolidate project" will silently miss them.
- **Preview == export.** One evaluator answering "which frame, at what opacity, at time t",
  shared by both renderers — the discipline `ChromaKey`, `VolumeEnvelope` and
  `CompositingSpec.featherRadiusPx` all follow, and for the same reason.

---

## 9. ANSWERED — 2026-08-06

### 9a. Holds survive a resize. YES. (User.)
Weights are the only timing model, so this is automatic.

### 9b. Per-frame ABSOLUTE holds are NOT built. (Delegated to me by the user; decided here.)
The user was clear they care about RELATIVE now and expected absolute would matter later for
music timing — but also supplied the reason it does not need building: *"because you have the
previews on the track, you can always align those up, stretching the whole track over a music
section and aligning it up by eye, and that works with peaks."*

**Decision: do not add per-frame absolute pinning.** Three reasons, in order of weight:

1. **Mixed units make resize unanswerable.** If three frames are pinned at 2s each and the object
   is squeezed below 6s total, every possible behaviour surprises someone — silently distort the
   pins, refuse the drag, or let the object exceed its own bounds. That is not a feature with an
   edge case, it is a class of bug. A single unit has no such state.
2. **It would cost the AI integration its best property.** §7 works because a sequence's timing is
   ONE integer array. Introduce a second unit and every batch pattern has to ask "is this frame
   pinned?" before it can reason.
3. **The motivating case is already served** by the user's own method, and §9c makes it precise.

**This is deliberately reversible.** If absolute is ever needed it can be added later as an
explicit per-frame PIN, visibly marked on the dope sheet, with a defined over-constraint rule
(pins win, the remainder redistributes, and if that is impossible the drag REFUSES rather than
silently distorting — the same "refuse, don't clamp" discipline as `KeyableSpan`). Adding it
later is cheap; building both now bakes the ambiguity into the model.

**Kept, and not the same thing:** the RELATIVE/ABSOLUTE *resize* modes in §2a. Those are about
what trimming MEANS (retime vs add/remove frames), not about mixing units inside one object.

### 9c. Live readout while resizing — cheap, and it is what makes eyeballing exact
Since aligning by eye against music is the sanctioned method, make the eye accurate: while
dragging the object's edge, show a live readout of the resulting **frame count · total duration ·
effective fps** (e.g. `24 frames · 4.0s · 6.0 fps`). Mirrors the loop-resize readout that already
exists (`PLAN_LOOP_PINGPONG` L3). No model change, and it turns "about right" into "landed on 6
fps exactly".
