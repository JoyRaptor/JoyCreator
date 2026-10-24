# JB-5.20e — Select: pick a line, lasso, the box, nudge, and the line/pixel boundary on screen

| | |
|---|---|
| **Tier** | T1 design (UI specialist). Phase 2 slices below say who builds each |
| **Status** | 🔒 §K locked by the owner 2026-10-12. Phase 2 (code) under way: e1 core on `ui/select-core`. Where a decision above and §K disagree, §K wins |
| **Needs** | JB-5.20 a–d (lines in the one layer: records with `seq`, slabs, look cache, one undo history). The canvas plug-in point `androidkit/tools/CanvasTool.kt` (landed 7db941d3). The Studio transform surface in `:studiokit` (D.02, landed) |
| **Reads** | `research/concepts-app-interaction-reference.md` (CR below), `research/R5_concepts_infinite_painter.md` §1.6, §3.2–3.4 (R5), `specs/JB-5.20_lines_in_the_one_layer.md` D11–D12, `specs/JB-2.05_selection_transform_ui.md`, `specs/JB-5.02_stroke_picking.md`, `OWNER_CONSTRAINTS.md`, `LEAD_RULINGS.md` R16, R38, R42, R47, R51 |
| **Owner area** | NEW `joybrush/core/.../core/select/` (pure maths, JVM-tested), NEW `joybrush/androidkit/.../androidkit/select/` (the tool, the box host, overlays), NEW `joybrush-android/.../android/select/` (popup, bar, layer-menu rows, badge, toasts). The Lead owns `JbCanvasView.kt`, `GlPaintEngine.kt`, `JoyBrushActivity.kt`: every change there is a **hook request** in §H, never an edit by this lane |

## Goal

The owner, verbatim:

> *"concepts is basically my poster child for the vector side of everything."* (one-layer brief §3)

> *"I can select a line, I can nudge any part of it … it doesn't smudge the pixels, it actually moves the underlying
> vector line while keeping all the pressure, velocity, and tilt … at any time I can change it, let's say from a pen
> stroke to a pencil stroke … to a filled shape."* (JB-5.20 Goal)

> Selection in dense line work must be as good as Concepts'; tapping outside commits; the finger becomes a tool finger
> once a pen is seen; a nudge moves the same distance on screen at any zoom. (OWNER_CONSTRAINTS 2026-09-28)

He draws on a Galaxy Note 9 with the S Pen, often one-handed with a baby in the other arm. So **every action in this
spec can be done with one contact** (the pen, or one finger). Two fingers and a resting thumb are shortcuts, never the
only way.

After this row, on any layer: tap a line and it is selected; tap the same spot again and the next line under it is
selected; loop around lines and they are selected, loop around paint and the paint is selected, with no mode switch.
A box appears at once. Drag it, scale it, turn it, bend it, recolour the lines, swap their brush, change their weight,
nudge any part of them, drag their tips, duplicate, delete, flip, or turn them into pixels. Tap outside and carry on.

## What this spec improves on Concepts (and why)

Concepts is the reference, and everything it does that the owner relies on is kept. Five things change, each because
the phone, the S Pen or one hand makes the Concepts way worse here:

1. **One Select tool, no Lasso / Item Picker switch.** Concepts toggles between the two (CR:355, CR:357). Here a tap
   picks and a drag lassos: the gesture says which. The switch disappears.
2. **The pen shows what a tap will take before it taps.** The S Pen hovers; the line a tap would select glows. Hovering
   on the spot just picked shows the *next* line in the cycle. Concepts' crosshair (CR:357) exists because a fingertip
   hides the target; a hovering pen tip hides nothing. The crosshair stays, for fingers only (D7).
3. **The lasso's filters work after the loop, not before.** Concepts' Partial / Layers / Lock filters must be set
   before you lasso (CR:355). Here the loop is kept while the selection lives, and the bar's chips **re-read the same
   loop**: lines or pixels, whole or touching, this layer or all. A wrong guess costs a tap, not a redraw.
4. **Pen paints, finger moves.** With a brush in hand and a selection live, the pen keeps painting (clipped to a pixel
   selection, R38/JB-2.05 D4) and the finger moves the box. Concepts does not separate the two inputs inside a selection.
5. **The box is the Studio's box**, the one the owner signed off over six rounds (TRANSFORM_UI_FEEL V6, R16): the same
   handles, colours, role ring and snapping. Concepts' corner-tap distort / two-corner skew and warp (CR:365) map onto
   the Studio's long-press role ring, which the owner already knows.

## Vocabulary (use these words in code, labels and tests)

| Word | Means |
|---|---|
| **Select tool** | The fourth tool on the strip, after Eraser. While it is chosen, a tap picks and a drag lassos |
| **Pick** | A tap on a line selects it. **Cycle**: a tap on the same spot selects the next line under it |
| **Lasso** | A drawn loop. It closes itself on lift (R42). What it took is decided by the bar's chips |
| **Selection** | What is selected: some lines, or a pixel region of one layer. It is one object, `SelectionState` |
| **Box** | The Studio's transform surface around the selection, with its handles and rotate arc |
| **Popup** | The row of actions for the selection (where it sits is an owner pick, §K-P1) |
| **Bar** | The chips at the bottom of the screen that say what is selected and re-read the lasso |
| **Nudge** | Concepts' Nudge (CR:318): push or pull part of a selected line, with soft falloff inside a ring |
| **Tip** | Either end of a selected line. Dragging a tip bends the line's end (blue dot, Studio's bend colour) |
| **Step move** | Moving a selection by `Nudge.stepDoc` (1 or 10 screen px). Keyboard arrows only. The code name `Nudge` (core/tool/Nudge.kt) keeps meaning this; the user-facing word *Nudge* means the bend (above), because that is the owner's word for it |
| **Bake** | A line becoming pixels at its own `seq` (JB-5.20 D8, D12). The picture does not change |

## What exists, and what core work this needs

Built and tested, not on screen (file:line from the 2026-10-11 inventory):

| Piece | Where | Use here |
|---|---|---|
| `StrokePicker.pick(lines, x, y, timeMs, screenPerDoc)` | `core/vector/StrokePicker.kt:60`, 29 tests | The ranking (12 screen px slop + half-width, ties by recency) |
| `InkEditSession` tap / band / drag / reweight / rebrush / recolor | `core/vector/InkEditSession.kt:173–394`, 22 tests | Nudge and tip drag go through `beginDrag/drag/endDrag` (:272–335) |
| `StrokeEdit.reshape / reweight / rebrush / recolor` | `core/stroke/StrokeEdit.kt:66–141`, 11 tests | The four record edits. Falloff `w = (1-(s/R)²)²` along the arc (:85–94) |
| `VectorEraser`, `InkErase` | `core/vector/VectorEraser.kt:110`, `InkErase.kt:173` | Slice = the eraser at width 0 (not this tool; §I) |
| `SelectionMask.polygon / add / subtract / invert` | `core/select/SelectionMask.kt:118–246`, 19 tests | Pixel selections |
| `Homography`, `Resample.lift / warp` | `core/select/Homography.kt:145`, `Resample.kt:57,144` | Moving pixels |
| `CelComposer.bake(d, ids)` | `core/vector/CelComposer.kt:169` | Rasterize Down |
| `UndoLog.Step` with `LineChange` | `core/paint/UndoLog.kt:37,55` | One history (5.20c) |
| The Studio box: `TransformOverlayView` + `HandleModel` + `TransformQuad` | `studiokit/.../transform/` | The box (D10) |

**New pure core (this lane, JVM tests, `core/select/`):**

1. **`LineLasso`** — which lines a loop takes. Nothing does this today: `Lasso` returns pixel coverage only
   (`Lasso.kt:147`) and `InkEditSession.band` is a rectangle that takes a line if any dab centre is inside (:240).
   Rule: a line's **inside fraction** is the share of its arc length inside the loop (non-zero winding, same rule as
   `Lasso`). **Whole** takes `f ≥ 0.9` (a loop that clips a tail by a hair still takes the line). **Touching** takes
   `f > 0`. Bounding boxes first; then per segment.
2. **`LineTransform`** — applies a `Homography` to a `StrokeRecord`. Nothing moves, scales or turns a line today.
   It must keep "all the pressure, velocity, and tilt" (owner):
   - `x, y` go through the map. `timeMs`, `pressure`, `tilt` are kept.
   - `azimuth` and `barrel` turn with the line: each is rotated (and mirrored, for a flip) by the map's local linear
     part at that sample, so a pencil shaded from the left and flipped is shaded from the right.
   - `screenPerDoc` is divided by the map's scale `s = √|det A|`. Smoothing and speed are measured in screen terms
     (`StrokeSmoother(smoothing, screenPerDoc)`, `InkReplay.kt:134`), so this keeps the line smoothed and paced exactly
     as drawn; without it a line scaled ×2 would replay with half the smoothing.
   - `widthScale` is multiplied by `s` when weights scale with the box, and kept when they stay (D10.6 offers both).
   - `id`, `seed`, `seq`, brush and colour are kept (D1 of JB-5.20: editing keeps `seq`).
3. **`StrokePicker.rank(lines, x, y, screenPerDoc): List<String>`** — the ranking without the cycle state, for the hover
   glow and the lasso preview. JB-5.02 Q4 asked for exactly this ("no read-only what is under this point").
4. **`SelectionState`** — one object for the live selection: the ids or the mask, the kept loop, the chip settings,
   the box quad, and `reread()` when a chip changes. Pure, so every rule in D5–D9 is a JVM test.
5. **Tip drag** is `StrokeEdit.reshape` grabbed at the end sample with the radius in D13. No new maths.

The lead's 5.20d work this depends on: records captured at pen-up, the look cache, GPU replay, `InkEditSession`
emitting `LineChange` steps into `UndoLog` (5.20c half two). Until those land, phase 2 can be built and tested against
fakes, but nothing reaches the screen.

## Decisions

### Getting a selection

**D1 — Four ways in.** (Concepts has four too, CR:353.)
1. **The Select tool** on the strip (new `ToolSlot.SELECT` after Eraser, `core/.../ToolMemory.kt:13`; glyph: an arrow
   with a dashed loop). Pen or finger: a tap picks (D3), a drag lassos (D5).
2. **The S Pen button, held, from any tool** (R42's default hold action LASSO): press the button, touch, draw a loop.
   It closes on lift. A quick press-and-release keeps meaning the eyedropper (R42 tap action). Rebinding stays R42's.
3. **The tool finger** (OWNER_CONSTRAINTS: once a pen is seen, the finger becomes a tool finger for select / lasso /
   colour pick). Whether one finger selects or pans by default is §K-P10.
4. **Select all lines** in the layer panel and in the Select tool's settings popover (tap the Select tool when it is
   already chosen: R5 P-tap-depth, CR:429).

And everything inserted lands selected (CR:445): a paste, a duplicate, and later an imported image or an object.

**D2 — The Select tool's settings popover** (tap the chosen Select tool; 220dp, the strip's popover style): *Select all
lines on this layer* · *Paste* (only when there is something to paste) · *Dim the rest while selecting* (on) · *Pick
locked lines too* (off, appears once a line has been locked). Nothing else: every other choice lives on the bar where
its effect is visible.

### Picking a line

**D3 — Tap picks; the same spot cycles.** A tap (lift within 12dp of the down and within 300ms, no hold) on a line
of the **active layer** selects the line `StrokePicker` ranks first (the *All layers* chip widens loops, not taps). A tap within **6 screen px** of the previous pick, while that pick is still
the selection, selects the next line in the ranking, wrapping. **There is no time window.** JB-5.02's 400 ms was a
double-tap speed; the owner taps again when he sees the wrong line, which is slower than that, and a slower re-tap
that silently restarts at the first line feels broken. Cycling ends when the selection changes any other way.

**D4 — In dense work the tap tells you what it did.** After a pick with two or more candidates, a small count shows
beside the tap for 1.2 s: `2 / 3` (Plex Mono 10sp on a frosted 18dp pill, cyan ring, offset 18dp up-right of the tap).
With **one** candidate nothing shows. How the count is presented, and whether fingers get a crosshair or a candidate
fan, is §K-P4.

**D5 — Pen hover shows the target (Select tool, or with the S Pen button held).** While the pen hovers within the
picking slop of a line, that line glows (a 2dp cyan outline at 60% around its own ink, 0ms in, 120ms out). Hovering
within 6 screen px of the current pick glows the **next** line in the cycle instead, so the pen shows what the next tap
will take. Uses `StrokePicker.rank` and `CanvasTool.hover` (`CanvasTool.kt:31`), throttled to the display rate.

**D6 — What a tap on something else does.** A tap on empty canvas **outside** the box ends the selection (the owner's
"tapping outside commits"). A tap on empty space **inside** the box does nothing (JB-2.05 D5: the likeliest mis-tap
while working the handles must not throw the work away). A tap on another line: replaces the selection, or adds it
(§K-P6).

**D7 — Fingers get a crosshair.** A finger in the Select tool that holds still for 300ms (8dp slop) shows a crosshair
48dp above the fingertip with a 1dp line down to it. Sliding moves the crosshair; the line under it glows (D5's glow);
lifting selects it. This is Concepts' Item Picker (CR:357), offset so the finger does not cover the target (CR:456).
A drag that starts moving before 300ms is a lasso (D8). (§K-P4 may swap this for a candidate fan.)

### The lasso

**D8 — Draw a loop; it closes itself on lift.** The path is drawn live as a 1.5dp cyan line with a 6/4dp dash. Lines
the loop would take glow while the loop is drawn (recomputed at most every 50ms, bounding boxes first), so the person
sees the result before lifting. A loop shorter than 24dp of travel is a tap, not a lasso.

**D9 — What a loop takes (JB-5.20 D11, with the chips).** By default: the lines on this layer that the loop encloses
**whole** (`LineLasso`, f ≥ 0.9). If it encloses none, it is a **pixel** selection of this layer (`SelectionMask.polygon`).
The loop is kept while the selection lives, and the bar offers three chips that **re-read it** live, each change one
tap, no redraw:
- **Lines (6) · Pixels** — take the lines, or the paint inside the loop. A pixel selection never moves lines (JB-5.20
  D8: pixel tools ignore lines unless they say they consume them).
- **Whole · Touching** — lines entirely inside, or any line the loop touches (Concepts' Partial, CR:355).
- **This layer · All layers** — lines only; a pixel selection is always one layer (JB-2.05 D10), so the chip greys
  out with the hover label *"Paint can be moved on one layer at a time."*
Re-reading replaces the selection; it is not an undo step (selecting never is: JB-5.20 D9 counts edits).
Whether these chips are the default, or a one-shot toast, or Concepts' set-before-you-lasso, is §K-P5.

**D10 — The box.** The selection gets the Studio's box exactly (`TransformOverlayView`, R16), driven by a Joy Brush
`Host` (`studiokit/.../TransformOverlayView.java:67–195`). What that gives, unchanged:
1. Corner and edge handles in the Scale role: amber squares at corners, amber triangles mid-edge, ~12.7dp and ~11.4dp
   across on a `#0D0D10` disc, touch radius 22dp + priority. Rotate arc 46dp above the top edge on an amber hairline.
2. Corner scale grows about the centre, uniform by default; it breaks to free aspect past 15% disagreement and rejoins
   under 10%. The box line tints green while uniform, red-pink once free (`TOV:2405–2411`).
3. Long-press a handle → the 112dp role ring: Scale / Free / Tilt / Bend; corner diagonals Flip H, Flip V, Reset;
   edge diagonals Fold, Mirror (`TOV:1395–1520`). This **is** Concepts' distort / skew / warp (CR:365), in the
   owner's own handle language.
4. Rotation detents at 0/90/180/270 and the 15° step (`TOV:317–346`), body-drag snapping to targets the host gives.
5. One undo step per gesture: `beginGesture` on down, `commitGesture` once on a clean up that moved (`TOV:2102, 2151`).
6. **Weights.** Scaling lines scales their weight with the box by default (Concepts' *Scale*, CR:363). A bar chip
   switches to *keep weights* (Concepts' *Stretch*). `LineTransform` does both (core item 2).
7. **Bend** for lines warps their samples through the Studio's 3×3 bend net (D.03 `mesh/`). For pixels, Bend is off
   (`setBendAvailable(false)`) until JB-2.05c warps pixels.
8. **The box is quiet while it moves:** the popup hides on the first move and returns 170ms after the lift.
The Studio's *Done* and *Bend* pills are not shown (they would sit on the top bar; here a tap outside ends it). That
needs a flag in `:studiokit` (§H6). The Studio's *Reframe ghost* is off (no `videoRect`).

**D11 — Two fingers.** Inside the box (its quad plus 24dp, never smaller than a 120dp circle around its centre) two
fingers move, scale and turn the selection; outside, they move the page. The choice is made when the second finger
lands and **latched** for the gesture (JB-2.05 D3). The Studio's box takes a second finger anywhere (`TOV:2049`), so
this needs §H6's flag. Whether two fingers anywhere should transform instead (Concepts, CR:234–235) is §K-P8.

**D12 — Who owns a contact while a selection is live.**

| Contact | Select tool | A brush, smudge or eraser chosen |
|---|---|---|
| Pen or finger on a handle, the rotate arc, a tip dot, the popup, the bar | the box / control | the box / control |
| Pen in the box body | moves the selection | **paints** (clipped to a pixel selection; a line selection ends and the stroke draws, as in Concepts: R5:222) |
| Finger in the box body | moves the selection | moves the selection |
| S Pen button held, in the box body | moves the selection | moves the selection |
| S Pen button held, outside the box | a new lasso (replaces, or adds: §K-P6) | a new lasso |
| Tap outside the box on empty canvas | ends the selection | pen: draws a dot and ends a line selection; finger: ends the selection |
| Two fingers | D11 | D11 |

The rule in words: **the pen keeps its tool; the finger and the pen button handle the selection.** So a person
holding a brush can shade inside a lasso with the pen and shove it with a thumb, and a person in the Select tool never
paints by accident.

### Editing lines

**D13 — Nudge (Concepts' Nudge, CR:318; the owner's "nudge any part of it").** From the popup, *Nudge* turns the box
into a ring. The handles hide; the selected lines stay highlighted.
- The ring (1.5dp cyan, 20% fill) follows the pen's hover, or sits under the finger. Its radius is in **screen dp**
  (default 48dp, 16–160dp), so the same push bends the same amount **on screen** at any zoom: zoom in for small
  corrections, out for gentle curves (owner's rule, and Concepts' own advice, CR:318).
- A drag inside the ring bends the **selected** lines it reaches (`InkEditSession.beginDrag/drag/endDrag`), falloff
  along the arc (`StrokeEdit.kt:85–94`), radius `R_doc = R_dp × density / zoom`. Lines not selected never move.
- While dragging, the line as it was shows as a 20% ghost (Concepts' before/after, CR:359).
- One drag = one undo step.
- The size control is §K-P7. The bar shows *Nudge* with the size control and a *Box* chip that returns to the box.
  A tap outside the selection ends everything, as always.

**D14 — Tips.** When 1–6 lines are selected, each shows a blue dot (`#5AA9FF`, 7dp radius, the Studio's bend-dot
colour: blue means fluid) at both ends; more than 6 and the dots would be noise, so none show. Dragging a dot moves that
end; the rest of the line follows with the same falloff over **35% of the line's arc length** (never less than 24
screen dp). Tip dots win over the box body and the mid-edge handles (a tip often sits exactly on one); corners and the
rotate arc win over tips. On a box side shorter than 56dp the mid-edge handles are not drawn at all. One drag = one
undo step.

**D15 — Recolour, re-brush, re-weight.** The popup carries the three, each acting on every selected line as one undo
step (`InkEditSession.recolor / rebrush / reweight`):
- **Colour** chip: shows the selection's colour (two colours = a split dot). Tap → the strip's 176dp colour wheel
  popover, every change live, one step per release. Drag off the chip → the eyedropper (the strip's own rule,
  `JoyBrushActivity.kt:1575–1661`), and the picked colour recolours.
- **Brush** chip: shows the selection's brush glyph. Tap → a strip of **line brushes only** (a pixel brush cannot take
  a line: `InkEditSession.rebrushRefusal`, :483). Each tap re-brushes live.
- **Weight** chip: shows `1.0×`. Drag left/right on it: ×0.25 to ×4, log scale, 1 step per 6dp, live, one undo step per
  drag (`StrokeEdit.MIN/MAX_WIDTH_SCALE`, :29–32). Tap → the strip's 220dp slider popover.
And, as in Concepts (R5 P14, CR:533), while lines are selected the **strip's own** colour swatch and brush button act
on them too; both wear the cyan ring to say so. Whether that is on is §K-P3.

**D16 — The popup's actions.** One row, never scrolling sideways (R5 anti-pattern 4); overflow goes behind **More**.

Lines:

| Order | Action | What it does | Undo |
|---|---|---|---|
| 1 | Colour | D15 | one per release |
| 2 | Brush | D15 | one per tap |
| 3 | Weight | D15 | one per drag |
| 4 | Nudge | D13 | one per drag |
| 5 | Duplicate | copies the lines 16 screen dp right and down, new ids, new `seq` on top; the copy becomes the selection | one |
| 6 | Delete | removes the lines; the selection ends. A red dot marks it (the one filled state) | one |
| 7 | More ▸ | Copy · Flip horizontal · Flip vertical · Lock · Rasterize Down | — |

Pixels (the same row shape, so the hand learns one place):

| Order | Action | What it does |
|---|---|---|
| 1 | Fill | fills the selection with the current colour (JB-2.07a `MaskPaint`) |
| 2 | Duplicate | lifts a copy onto the same layer, offset 16dp, and selects the copy |
| 3 | Delete | clears the pixels inside |
| 4 | Flip horizontal | |
| 5 | Flip vertical | |
| 6 | More ▸ | Copy · Invert selection |

- **Copy** puts a PNG of the selection on the system clipboard (other apps) and the records on Joy Brush's own
  clipboard (pasting brings back lines, still editable).
- **Flip** mirrors about the box centre through `LineTransform` (tilt mirrored too) or `Resample.warp`.
- **Lock** marks lines so a tap or lasso skips them (Concepts' Lock, CR:361). It needs a `locked` field in the record
  (5.20a's codec; the Lead's). **Until that field exists, Lock is not shown** (a control that does nothing is worse
  than a missing one).
- **Rasterize Down** bakes the selected lines into pixels, each at its own `seq` (JB-5.20 D12), one step; the
  selection becomes a pixel selection of the same loop, so the hand can carry on.
- **Group** (Concepts, CR:361) is left out: there is no group in the document model. It is a later row, named here so
  it is not forgotten.

**D17 — Highlight.** While lines are selected, everything else on the canvas fades toward the paper (Concepts'
Highlight Selection, CR:359, default on): a paper-coloured veil at 65% under the selected lines, which are drawn on top
at full strength. Fading toward the paper, not toward black, keeps the rule "greys fade, colour guides" on any paper.
A pixel selection instead tints the outside of the loop 20% and draws the loop as marching ants (1.5dp cyan, 6/4dp
dash, 30 px/s; still under reduced motion). The look is §K-P2; off in D2's popover.

**D18 — How the moving selection is drawn (performance, the Lead's engine).** On select, the selected lines are
**lifted** into their own texture (the cel's look re-rendered without them, which JB-5.20 D9 budgets at under 100 ms
for a line under 1,000 others). While the box moves, that texture is drawn through the box's matrix, so dragging costs
a textured quad, not a replay. On release, `LineTransform` writes the records and only their tiles replay, crisp.
A scale shows a slightly soft preview during the drag and sharpens on release. The same lift makes D17's highlight
free. Pixels use JB-2.05's homography stack the same way. This is a hook request (§H5), not this lane's code.

**D19 — Ending a selection.** A tap outside (D6), a pen stroke with a brush over a line selection (D12), choosing
another layer, opening Boards, or Undo past the selection's first step. Lines need nothing on end (every gesture was
already its own step). Pixels land their homography stack as **one** step (JB-2.05 D7, amended by R38: back into the
same layer). After an end, the popup offers nothing; *Reselect* is a later row (R5 §3.3 persistence).

**D20 — Undo is one press, one step, always.** Every row above that changes the drawing is exactly one `UndoLog.Step`.
Selecting, cycling, re-reading the chips, entering and leaving Nudge are not steps. Undo while a selection is live keeps
the selection if its lines still exist, and fits the box to them.

### The boundary between lines and pixels on screen

**D21 — Layer panel rows** (JB-5.20 D12; the panel is `JoyBrushActivity.kt:1182–1269`). After *Duplicate* and before
*Clear layer*, only when they apply:
- **Select all lines** — when the layer's current cel has lines.
- **Rasterize Down** — when it has lines. With lines selected on this layer it bakes those; otherwise all of them. One
  step.
- **Flatten Lower** — not on the bottom layer. This layer and every layer below become one pixel layer, all lines baked.
  One step. (JB-5.20 Q1 is still open; the options page asks it.)

**D22 — The badge.** Brushes that always make pixels (media, smudge, push, wet, blur, clone, and the Paint and Airbrush
shelves while they default to pixels) wear a 10dp badge at the bottom-right of their tile in the brush drawer. Its form
is §K-P9. Hover label: *"Makes pixels: this brush blends with what's under it, so its strokes can't be picked as
lines."* The per-brush *Editable lines* switch (JB-5.20 D6) sits in the brush's own settings sheet with the same
sentence under it.

**D23 — The two one-time toasts** (JB-5.20 D6, D8), verbatim, each shown once per install (a pref per toast):
- First stroke with a pixel brush on a layer that holds lines: *"Brushes that blend with what's underneath become
  pixels. Everything else stays editable."*
- First time Smudge consumes a line: *"That line became pixels."* with a trailing **Undo**.
Both use one small frosted toast (record 05: 11dp radius, 9/12dp padding, 11.5sp, cyan trailing action), new in
`android/select/SelectToast.kt`, because Joy Brush has only `android.widget.Toast` (`JoyBrushActivity.kt:2163`) and a
toast that cannot offer Undo cannot honour D20's promise in the moment.

### Hover labels (every control; stylus hover shows them, TalkBack reads them)

| Control | Label |
|---|---|
| Select tool | Select: tap a line to pick it, tap again for the one under it, or draw a loop around things |
| Colour chip | Recolour the selected lines. Drag off to pick a colour from the canvas |
| Brush chip | Swap the selected lines to another line brush |
| Weight chip | Drag to make the selected lines thicker or thinner |
| Nudge | Push or pull part of the selected lines |
| Duplicate | Make a copy of the selection and select the copy |
| Delete | Delete the selection |
| More | Copy, flip, lock, or turn the lines into pixels |
| Fill | Fill the selection with the current colour |
| Flip horizontal / vertical | Mirror the selection left to right / top to bottom |
| Lock | Lock these lines so taps and loops skip them |
| Rasterize Down | Turn the selected lines into pixels. The picture stays the same; they stop being editable |
| Lines · Pixels chip | Take the lines inside the loop, or the paint inside it |
| Whole · Touching chip | Take only lines inside the loop, or every line it touches |
| This layer · All layers chip | Look for lines on this layer only, or on every layer |
| Keep weights chip | Keep line thickness when you resize, or let it grow and shrink with the box |
| Nudge size | How far a nudge reaches. It stays the same size on screen at any zoom |
| Box chip | Go back to moving and resizing the selection |
| Rotate arc / handles | (the Studio's labels, unchanged) |

### Slice and the rest of 5.20e

**D24 — Slice** is the eraser at width 0 (JB-5.20 D11, CR:319): an eraser preset named *Slice* in the eraser shelf, which
splits a line where it crosses without removing ink. It is a brush preset, not part of the Select tool; listed so 5.20e
is complete. The two smudges and flood-fill wiring in 5.20e's row are the Lead's (engine) and are not in this lane.

## Concepts mapping (what each decision copies, changes or drops)

| Concepts | Reference | Here |
|---|---|---|
| Four ways into selection | CR:353, R5:180 | D1 (Select tool, S Pen button, tool finger, Select all) |
| Tap+Hold on canvas = selection | CR:179, CR:226 | Not copied: long-press is the eyedropper in Joy Brush (JB-2.03a, `Eyedropper.kt:36`), and the S Pen button is the faster route (R42) |
| Selection Menu at the bottom (type, Partial, Lock, Layers) | CR:355, R5:187 | D9's bar; filters re-read the kept loop instead of being set before |
| Lasso; lasso again to subtract | CR:357, CR:227 | D8; add/remove is §K-P6 (a drag inside the box moves it here, so "lasso over the selection" cannot also subtract) |
| Item Picker crosshair; second-finger tap adds | CR:357, CR:228–229, R5:194 | D7 for fingers; D5 hover for the pen; adding is §K-P6 |
| Highlight Selection, default on | CR:359, R5:220 | D17 |
| Selection Popup: Copy, Duplicate, Group, Lock, Delete, Flip H/V | CR:361, R5:200 | D16; plus Colour, Brush, Weight, Nudge, Rasterize Down; Group later |
| Scale / Stretch / Off; Rotate allow | CR:363, R5:202 | D10.6 weight chip; the Studio's role ring replaces the toggles |
| Control points: drag, distort, skew, warp | CR:365, CR:230–233, R5:209 | D10.3, the Studio's role ring and bend net |
| Pinch / twist transforms the selection | CR:234–235 | D11 (inside the box) / §K-P8 |
| Restyle by tapping another tool with a selection | CR:533, R5:705 | D15 / §K-P3 |
| Nudge tool, size + zoom for detail | CR:318 | D13 |
| Slice = vector eraser, width 0 splits | CR:319 | D24 |
| Drag selection onto a layer / New Layer | CR:236–237 | Later row (the layer column has no drop target yet) |
| No ✓, carry on drawing | R5:222 | D6, D12, D19 |
| Second finger as modifier | CR:193–203, R5 P5:638 | Shortcut only (D11, §K-P6); never the only way, for one-handed use |
| Phone: thumb of the holding hand is the modifier; enlarge control points | CR:645–647 | D10 (Studio touch radii 29–35dp), §K-P6 |
| Gestures "beyond the understanding of mere mortals" | CR:488 | Every control has a hover label (D-labels); hover glow and live lasso preview teach by showing |

## §H — Hooks the Lead adds (signature + call site)

| # | Hook | Call site | Why |
|---|---|---|---|
| H1 | `val penSeen: Boolean` (read-only getter on the existing private field) | `JbCanvasView.kt:677` | D12 and §K-P10: the tool must know whether a finger is a tool finger. Today it is private and never readable |
| H2 | Offer `tool.down` **after** the palm check, or expose `fun isPalmContact(ev): Boolean` | `JbCanvasView.kt:840` vs `:861` | Today the tool sees a palm before the canvas rejects it (`toolTouch` runs first) |
| H3 | Pen-button hold LASSO hand-off: when the button-held contact passes slop and the hold action is `LASSO`, call `selection.beginButtonLasso(downX, downY, ev, view)` and route the rest of the gesture to it; a contact that never passes slop keeps running the tap action | `JbCanvasView.kt:843–853` and where `buttonMoved` is set | D1.2 from any tool. Today the drag does nothing (comment at :844–846) |
| H4 | `var strokeClip: ((docX: Float, docY: Float) -> Int)?` (coverage 0..255; 0 drops the sample) | `JbCanvasView.feed`, before `feedOne` | D12: a brush paints inside a pixel selection only (JB-2.05 D4) |
| H5 | Engine: `liftLines(celId, ids): LiftedLines` with `setMatrix(FloatArray)`, `land(newRecords): UndoLog.Step`, `drop()`; `dimRest(alpha)`; pixel equivalents per JB-2.05 | `GlPaintEngine` | D17, D18 |
| H6 | `:studiokit` flags, defaults unchanged for the Studio: `setPinchAnywhere(boolean)`, `setPillsVisible(boolean)` (Done, Bend) | `TransformOverlayView.java:2049`, `:781`, `:818` | D10, D11 |
| H7 | `JbIcon.SELECT` and a Select button on the strip (`ToolStripView.showSelect`, `Host.selectTapped` with an empty default so the activity still compiles), hidden until the tool exists. **Not** a `ToolSlot`: a slot is a remembered brush (brush id, size, opacity, saved by name, `ToolMemory.kt:13–28`), and Select holds no brush. The activity wires `selectTapped` to `canvas.tool = selectTool` (first tap) or D2's popover (second tap) | `JbIcon.kt`, `ToolStripView.kt`; activity wiring `JoyBrushActivity.kt:654–657` | D1.1 |
| H8 | `CanvasTool.kt:21` says `ACTION_POINTER_DOWN` is offered to `down`; `toolTouch` offers only `ACTION_DOWN` at pointer 0 (`JbCanvasView.kt:828`). Fix the doc or the code | | Contract and code disagree; D11 works either way once the first finger is owned |

### H4 and H5, the exact contract (the Lead builds to this)

```kotlin
// ── H4, JbCanvasView ───────────────────────────────────────────────────────────────────────────────────────────────
/**
 * JB-5.20e D12 / JB-2.05 D4. While set, a brush or eraser stroke whose pen-DOWN lands where [paintClip] covers (> 0)
 * paints only inside it: the engine multiplies the stroke by the mask's coverage at commit, so a soft lasso edge is a
 * soft clip. A stroke that starts outside paints normally. A LINE-making stroke keeps only the samples with coverage > 0
 * (a record cannot be masked; its dabs may spill a radius past the edge, accepted). Document space, the active layer's
 * cel. Main thread; read at pen-down, so changing it mid-stroke affects the next stroke. null = no clip.
 */
var paintClip: cc.joycreator.joybrush.core.select.SelectionMask?

// ── H5, GlPaintEngine (GL thread; the tool reaches it through JbCanvasView's queue as every edit does) ────────────────
/**
 * Lifts the lines [ids] of [celId] out of the cel's look into a texture of their own and re-renders the look without
 * them (JB-5.20 D9 budget: under 100 ms for one line under 1,000). Returns null, and lifts nothing, if any id is missing
 * or a line cannot be replayed; the Select tool then shows the box with no live preview and commits on release only.
 * Only one lift at a time: a second call drops the first.
 */
fun liftLines(celId: String, ids: Set<String>): LiftedLines?

interface LiftedLines {
    /** Draw the lifted texture through [h] (document → document) until the next call. Cheap: one textured quad. */
    fun setTransform(h: cc.joycreator.joybrush.core.select.Homography)
    /**
     * Replaces the lifted lines' records with [after] (same ids, same seqs) as ONE undo step pushed onto the engine's
     * UndoLog (a `LineChange` per line), re-renders the lifted texture from [after] at identity, and stays lifted.
     * Returns false and pushes nothing when [after] equals what is there. Undo and redo while lifted go through the
     * engine's normal undo-step path (never a parallel one: one press, one step across every tool); the lift only
     * re-renders its texture from whatever records that step restored. (Lead, 2026-10-11.)
     */
    fun commit(after: List<cc.joycreator.joybrush.core.stroke.StrokeRecord>): Boolean
    /** The selection ended: the lines go back into the cel's look (their tiles re-render), no undo step. */
    fun release()
}

/**
 * Highlight (D17): everything drawn except the lifted lines is faded toward [paperArgb] by [alpha] (0 = off; the
 * Select tool uses 0.66 for "Fade the rest", 0.42 for "Both, lighter"). Display only, never in export or the look.
 */
fun dimRest(alpha: Float, paperArgb: Int)

/** Pixels, the same shape: [mask] lifted off [layerId]'s [celId] (kept in place when [keepSource], for Duplicate). */
fun liftPixels(layerId: String, celId: String, mask: SelectionMask, keepSource: Boolean): LiftedPixels?

interface LiftedPixels {
    fun setTransform(h: Homography)
    /** Warps the lifted pixels through the current transform back into the same layer (R38): ONE undo step; stays
     *  lifted at identity in the new place. False when the transform is the identity and nothing else changed. */
    fun commit(): Boolean
    /** Popup Delete: the lifted pixels become empty, ONE undo step, then [release]. */
    fun clear(): Boolean
    /** Popup Fill: the selection's area, weighted by its coverage, becomes [argb] over what is there, ONE undo step. */
    fun fill(argb: Int): Boolean
    fun release()
}
```

## Build slices (phase 2, after §K is locked)

| Slice | What | Who |
|---|---|---|
| e1 | Core: `LineLasso`, `LineTransform`, `StrokePicker.rank`, `SelectionState` with the chip re-read; JVM tests below | UI specialist (or T2 from this spec) |
| e2 | androidkit `select/`: `SelectTool : CanvasTool` (D3–D14 routing), `SelectBoxHost : TransformOverlayView.Host`, overlays (lasso path, hover glow, ring, tip dots, count pill) | UI specialist |
| e3 | joybrush-android `select/`: popup, bar, More menu, D2 popover, layer-panel rows (D21), badge (D22), `SelectToast` (D23) | UI specialist; T2-V can build from §K |
| H1–H8 | Hooks | Lead |

**Tests (e1, `./gradlew -p joybrush :core:jvmTest`, 0 failures):**
1. `LineLasso`: a line fully inside → taken by Whole and Touching; 95% inside → Whole; 50% → Touching only; outside → neither; a figure-8 loop uses non-zero winding (same answer as `Lasso` for the same polygon).
2. `LineTransform`: translate → every sample moved, everything else identical; uniform ×2 with weights scaling → positions ×2, `widthScale` ×2, `screenPerDoc` ÷2, and `InkReplay` dab radii ×2 (within float tolerance); ×2 keeping weights → `widthScale` unchanged; rotate 90° → `azimuth` +90° (wrapped), `tilt` unchanged; flip H → `azimuth` mirrored; id, seed, seq, brush, colour unchanged in every case.
3. `StrokePicker.rank` returns the same order `pick` uses for its first answer and its cycle (one source of truth).
4. `SelectionState`: a loop with no whole line → pixels; flip the chip to Touching → lines; to All layers on a pixel selection → refused in words, state unchanged; cycling by repeated same-spot taps visits every candidate once per round with no time window; a tap inside the box on empty space changes nothing.
5. One press, one step: a scripted session (pick, drag, nudge, recolour, duplicate, delete) produces exactly 5 `UndoLog` steps.

**On the phone (Note 9, the owner, never installed by this lane):**
1. Ink a face. Tap the nose line; tap the same spot again → the line under it; again → back.
2. Hover the pen over hatching → the line a tap would take glows; tap → that one.
3. Hold the S Pen button with a brush chosen, loop the eye → box at once; flip *Whole* to *Touching* → more lines.
4. Loop a painted patch with no lines → marching ants; shade inside it with the pen → stays inside; push it with a finger.
5. Corner-drag, rotate, long-press a corner → the Studio ring → Tilt. Each one undo step.
6. Nudge the chin at zoom 1, then zoom ×4 and nudge again → the ring is the same size on screen; the bend is finer.
7. Drag a tip of the eyebrow. Swap its brush pen → pencil → fill pen and back. Re-weight by dragging the chip.
8. Rasterize Down, then Undo. Flatten Lower, then Undo. The badge on Watercolour; the toast once, never twice.

## §K — The owner's picks (LOCKED, 2026-10-12)

The owner tried the options page on his own devices and sent this back. His words are kept exactly; §K overrides any
decision above it, and K1–K19 below say how.

> JB-5.20e picks: P1 A (Under the box) · P2 A (Fade the rest) · P3 C (The strip only) · P4 B (Count + finger
> crosshair) · P5 A (Chips re-read it) · P6 A (Tap replaces; ＋ adds) · P7 A (Slider in the bar) · P8 B (Anywhere moves
> it) · P9 C (Dotted corner) · P10 A (It selects) · Q1 A (This and every layer below) · Q2 A (Pixels by default)
>
> on p3 i chose C but i could be persuaded to B, lets try B.

His notes, verbatim:

> observation is that when I am dragging, uh, let's say, a corner of a selection, the menu fades out while I drag, and
> it looks like it does with what you have as well. But the concepts fade out is a little bit smoother, fading out and
> fading in. It takes a little bit more time. So it looks less poppy.
>
> I like how dragging line width changes the line width so I can scrub with my hand or tap to get a slider. That's
> great.
>
> there's a scenario that would frustrate me where I would draw a selection that was not a complete bounding box around
> a line but intersected a line and so it decided to take the paint but then when I asked it to take a line I couldn't
> I couldn't take any lines it was grayed out I had to change to select every line the loop touches, and then lines
> would happen. But what should happen, instead of making it so I can't click lines, is if I have Take only lines
> inside the loop selected. And so it defaults to taking the paint. If I click lines, it should ask, is there any lines
> connected? And it should just select lines and automatically Take every line the loop touches. As a fallback,
> because that's what I was meaning anyway, and I would get flustered and frustrated having to then search out the
> other option that I should have clicked. Likewise, if I have paint selected and I click take every line that the loop
> touches, it should automatically shift to selecting the lines. Instead of just having the toggle do nothing. So the
> inclusive or exclusive toggles only really work on paths. And so if I'm clicking the toggle between inclusive or
> exclusive, it should automatically go to selecting Paths.
>
> in Concepts, "copy" is a paperclip that reads clearer then a clipbord, "duplicate" which is clearer text then "copy
> and select new"
>
> When I had the concept setting turned on and I had add taps and loops, etc. And then I long pressed for the color
> picker. I thought that the behavior would be when I selected the new thing that it would add to what was already
> selected, but instead the selection focus just moved. That's counterintuitive. If I have add turned on, and I'm doing
> my color picker, it should add to it. [...] if I wanted to be exact and use the long hold drag to pick a specific
> line, that shouldn't deselect all the other lines if I have the add plus button active. Now, if it's NOT active, then
> it makes perfect sense to make a whole new selection.
>
> with "quiet" i keep wondering how to do things like if I missed the toast because i was thinking did i loose my
> chance? or if i took the lines but i really wanted paint, how could i go back? [...] I like how in a lot of instances
> it just "knows" what I want. the trouble is when it dosnt I dont have a clear way back. or where does selecting
> across layers live?
>
> the paint bucket icon for filling a selected region with paint should have the paint in the bucket and the paint in
> the drip/pouring part contain the color of the fill color as a nice little touch.
>
> when having selected a region, under more ellipsis we can have a shortcut to adjust HSB/etc
>
> this is an app wide gripe, but i thing the rotate handle needs to be at the bottom not the top, the reason being
> because your finger covers it. the only time it should be at the top is if the bottom is out of frame it should move
> to appear at the top. this may fight with the bar belore or perhaps its better to have the adjustments bar just
> appear lower, but rotating small objects you just cant see clearly what your doing
>
> thumb is a no go, it just takes away too much of the canvas.
>
> Recolour and re-brush should be in strip only, but the cyan ring should pulse to help with discovery to show they
> are real options, a person could mos the ring appearing.
>
> p8 your choice is tempting, but small selections become difficult to manipulate
>
> p10 behavior should be changable in settings but default b
>
> q2 should also be user changable in settings default a
>
> Bow should be ABOVE selection and only below if no room up top, so reverse of currently.
>
> box should be unintrusive. see my concepts screenshot attachment. they have the icons change with a threshold of
> brightness from white/black and there backing is the canvas color
>
> 02 dense hatching if we have a number i should be able to scrub that number to change selection
>
> 05 nudge needs to make sure it keeps the curve smooth it was getting course in the sample you gave but that could be
> because your not useing the real engine.
>
> 06 drag a tip - blue dots should appear only on hover with pen. only display like you had it if no pen for finger
> access. careful blue dots often fought with corner pins.
>
> studio rings i couldnt get working in your sample, i like the idea. its powerful and ties things together. concepts
> has a differnt system (and no bend) but its really clean and unintrusive [...] they have very thing lines that extend
> past the selection box so you can visually see alighnment to other things and tapping a corner puts a dot into it
> that makes it behave similar to our free, but when any two dots are tapped they move as a pair. move one dot or a
> combination and the lines that extend out change to show trajectory so for instance it can help you line up
> perspective [...] it also has a tiny target rectical that shows center and rotation if you have warped or perspective
> tweaked it to keep with the taper
>
> flip horazontal and verticla should not be hidden options behind an elipsis. that was my reason for being hesitent
> about p3 because of how many options present, but lets try it

### The locked parts

| Part | Locked | Note |
|---|---|---|
| P1 | **A, amended: the actions row sits ABOVE the selection**, below only when there is no room above | K2, K3 |
| P2 | **A, fade the rest** | |
| P3 | **B, on trial**: chips in the row AND the strip's colour and brush act on the selection, the strip's ring pulsing | His first instinct was C; "lets try B". K8 |
| P4 | **B, count + finger crosshair**, and the count is a scrubber | K11, K12 |
| P5 | **A, chips re-read the loop**, with the line-filter rule | K13 |
| P6 | **A, tap replaces; ＋ (or a resting thumb) adds**, for every way of picking | K12 |
| P7 | **A, slider in the bar** | |
| P8 | **B, two fingers anywhere move the selection** ("small selections become difficult to manipulate") | D11 replaced by K19 |
| P9 | **C, dotted corner** | |
| P10 | **B by default (one finger pans), switchable in Settings** | His note "p10 … default b" is taken over the picks line's "P10 A"; flagged to him in the reply |
| Q1 | **A, this layer and every layer below** | Closes JB-5.20 Q1 |
| Q2 | **A, Paint and Airbrush make pixels by default, switchable in Settings** | Closes JB-5.20 Q2 |
| Thumb direction | **Rejected**: "it just takes away too much of the canvas" | |

### What changes in the decisions above

- **K1 — Fades are slower and smoother (D10.8).** On the first move of a drag the row fades out over 220 ms
  (ease-out `(0.23,1,0.32,1)`); 120 ms after the lift it fades back in over 300 ms. Never a pop.
- **K2 — The row sits above the selection (D16, P1).** 12dp above the box's top edge; below the box only when there
  is no room above (under the top bar's 54dp); pinned inside the screen in both cases.
- **K3 — The row and the bar look like Concepts' (D16, D9).** No dark frosted pill: the backing is the PAPER colour
  (the canvas colour), a 14dp-radius rounded rect at about 88% so the drawing reads faintly through, with thin 1dp
  separators. Icons and labels are white or black, chosen by the brightness of what is behind them (the same sampling
  the top bar already does, `JoyBrushActivity.kt:1518–1562`), with a threshold, not a blend. Selected state stays a cyan
  ring.
- **K4 — The rotate handle goes BELOW the box** (D10.1), 46dp under the bottom edge, and moves above only when the
  bottom is out of frame. A finger on a handle above covers the thing being turned. The owner calls this an app-wide
  gripe, so the same rule is proposed for the Studio's box (hook H9).
- **K5 — The box's look is Concepts', its power is the Studio's (D10).** Thin 1dp lines along each edge, extended past
  the box to the screen edges (alignment guides, brightness-adaptive like K3). Corner points are small rings (10dp,
  touch radius 22dp). No edge handles are drawn (they crowded small boxes and fought the tip dots); one-axis stretch
  stays on the role ring. A tiny centre reticle (four 4dp ticks) shows the centre and turns with the box, following the
  perspective centre when the box is warped.
- **K6 — Corners, Concepts' way.** Drag a corner: scale (the Studio's uniform-until-15%-breakout rule). **Tap** a
  corner: it fills (active), and then it moves on its own (the Studio's Free, a corner-pin). Tap a second corner: the two
  active corners move as a pair (skew). Tap an active corner again to release it. While any corner moves, the extended
  edge lines follow it and show the trajectory, which is how perspective gets lined up with the scene.
- **K7 — Long-press a corner: the Studio's role ring** (Scale / Free / Tilt / Bend, flips and reset on the diagonals),
  unchanged. It is the way into Tilt and Bend. Bend is lines-only until JB-2.05c. It must work in the mockup, which it
  did not.
- **K8 — Restyle from the row and the strip, and the strip says so (P3 B, D15).** When lines become selected, the
  strip's colour swatch and brush button get the cyan ring and it pulses twice (0.6 s each, scale 1 → 1.15 → 1, alpha
  1 → 0.4 → 1), then holds steady. Reduced motion: no pulse, a steady ring.
- **K9 — The row shows the flips (D16).** Lines: Colour · Brush · Weight │ Nudge · Duplicate · Delete │ Flip
  horizontal · Flip vertical │ More (Copy, Lock, Adjust colour, Rasterize Down). Pixels: Fill · Duplicate · Delete │
  Flip horizontal · Flip vertical │ More (Copy, Invert selection, Adjust colour). Cells are 40dp and the weight chip
  52dp; the lines row is 391dp, which fits a 411dp phone.
- **K10 — Names and icons.** Copy is a **paperclip**. Duplicate's label is **"Duplicate"**. Fill's bucket is drawn
  with the paint in the bucket and in the pour in the actual fill colour.
- **K11 — The count is a scrubber (D4).** The "2 / 7" pill stays while the selection came from a tap, and dragging
  along it (12dp per step) walks through the lines under that spot, live, each one shown as the selection. It is the
  same order a tap-again cycle uses (`StrokePicker.rank`).
- **K12 — Add applies to every way of picking (D7, P6).** With ＋ on (or a thumb resting), a tap, the finger crosshair,
  the fan and the count scrubber all ADD (or remove) instead of replacing. With ＋ off, each makes a new selection.
- **K13 — Line filters imply lines (D9, P5).** The Lines chip is never greyed out.
  - On paint, tap **Lines**: take the lines the loop encloses whole. If there are none, take the lines it touches
    and switch the reach chip to Touching. Only if the loop touches no line at all does it stay on paint, saying
    "This loop doesn't touch any lines."
  - On paint, tap **Whole**, **Touching** or **All layers**: switch to lines with that reading (with the same
    fallback to Touching for Whole).
  - The reading chips only mean something for lines, so touching one always means lines.
- **K14 — Every guess keeps a visible way back** (the Quiet direction's lesson). A loop's reading stays on the bar for
  as long as the selection lives. Nothing that decides for the owner is a toast that can be missed.
- **K15 — Nudge keeps the curve smooth (D13).** The falloff is already C1-smooth. After a push, any stretch between two
  samples longer than 2 screen px is subdivided along a Catmull–Rom curve through its neighbours, and the in-between
  samples get interpolated pressure, tilt, lean and time, so a pushed line replays as a curve, never a polygon. Test: a
  straight line pushed by 3× the ring radius, filled in (`core/select/LineDensify`), has no gap over 2 px and no turn
  sharper than the push itself made. (A fixed degrees-per-sample bound was wrong: a tight push is a tight curve.)
- **K16 — Tip dots on pen hover (D14).** With a pen seen, a tip's blue dot appears only while the pen hovers within
  24dp of that tip, and only that tip's. With no pen seen (finger only), the dots show as before, for 1–6 lines. Tips
  never draw within 16dp of a corner point; the corner wins there.
- **K17 — Adjust colour (More).** Hue, saturation and brightness of the selection: on pixels, the selected pixels; on
  lines, each line's colour shifted the same way. Live while dragging, one undo step on release. It reuses the colour
  adjustment the "Adjustment layer feature" session builds, when that lands (hook H10).
- **K18 — Settings.** "One finger, after the pen is seen: Pans / Selects" (default Pans), and "Paint and Airbrush make:
  Pixels / Editable lines" (default Pixels), in Joy Brush's settings. Each brush's own *Editable lines* switch still
  overrides the shelf default.
- **K19 — Two fingers anywhere (P8 B).** With a selection live, two fingers anywhere move, scale and turn it (the
  Studio's pinch, `setPinchAnywhere(true)`, its default). Once the selection ends, two fingers move the page again.

New hooks from §K:
- **H9** — `TransformOverlayView.setRotateKnobBelow(boolean)`: the knob below the bottom edge, flipping above when
  clipped. Joy Brush sets true. The owner wants it app-wide, so flipping the Studio's default is the lead's call.
- **H10** — an engine "adjust the colour of the masked pixels / of these lines" operation, one undo step, shared with
  the adjustment-layer work.

## Do not

- Edit `JbCanvasView.kt`, `GlPaintEngine.kt` or `JoyBrushActivity.kt`. Ask for the hook.
- Modify `:studiokit` beyond H6's two default-preserving flags.
- Install on the phone. One Gradle at a time through `jb-gradle.lock`.
- Ship a control that does nothing (Lock before its field exists; Bend on pixels before JB-2.05c).

### K20 and K18 confirmed (owner, 2026-10-12)

> A note on the buttons. Instead of a ring or an outlined square on the plus to add to selection, it should be a solid.
> There's a couple other areas where instead of a cyan outline, it should be a solid cyan with a punch out of the icon or
> the text, just so you are very clear which one it is.
>
> pan is the good behavior, swappable in settings.

- **K20:** every ON state of a chip, toggle or segmented choice is SOLID cyan (#22D3EE) with its icon or word cut out of
  it (you see the paper through the cut), never a cyan outline. Covers the + add chip, keep-weights, Lines/Paint,
  Whole/Touch, This layer/Every layer, and the page's own segmented controls. Still to ask him: the strip's active-tool
  ring, paper swatches, layer cells, the page's option cards (their content is a picture).
- **K18 confirmed:** one finger PANS by default; Settings switches it to selecting.

### K21 — Selected-state gradient trial (owner, October 12, 2026; supersedes K20 colour only)

Owner first confirmed: "Keep rings around tools, swatches, and layers; use solid cyan for switches (Recommended)".
Then revised the colour:

> Actually, the solids can be the Joybrush gradient, because that will look pretty stylish. Shoot, might even— let's even try making the outlines also have the same gradient. Perhaps diagonal, with the bluer being on the upper left.

- Selected switch/segment fills use the Joybrush room pair, diagonally: bright blue upper left (#4397FD), violet lower right (#5C43FD). The icon/word remains cut out per K20.
- Tools, swatches and layer cells retain their contents within thin outlines, with the same gradient as a trial. The design page includes option-card outlines in the trial.
- New Select controls read `kit.p.roomEnd` → `kit.p.roomStart`; do not change mirrored global state-colour tokens. Lead sequences the shared strip/layer/ChromeKit follow-through after owner preview.
- K18 remains confirmed: one finger pans; Paint/Airbrush make pixels by default; both switchable in the Select menu.

### K22 — Floating pills and a shared selected language (owner, October12; revises the K21 trial)

Owner is of two minds: likes the Joybrush gradient, but cyan already means selected in Sprite Lab and Video Studio. The selection palette is NOT locked. The revised working preview offers Flat cyan / Cyan gradient / Joybrush gradient on the same drawing. Codex recommends the subtle cyan gradient for selected states, retaining blue/violet for Joybrush identity. Native SelectAccent keeps all three reversible; Cyan gradient is a trial default, not an owner ruling.

Owner's shape/backing direction is definite:
- Full pills rather than rounded squares for controls and floating rows/bars.
- The side strip can stay flat against its docked screen edge, with larger outer corners toward the canvas.
- Paper-coloured translucent backings on the canvas have NO grey perimeter stroke: they should blend into blank paper and read as floating icons, while still protecting controls over busy art.
- Width, colour and brush controls that can act on the selected line pulse the selection accent briefly when a line becomes selected, then settle. The palette does not change this discovery behavior. A pulsing width control must actually change the selected line, not merely the next brush size.

Owner's words:
> In general, instead of rounded square edges, I like things to be full pills, so larger roundovers, with the exception being the sidebar, which can have a roundover, but to be flat against the other side because that way it looks like it's actually coming out from the side.
>
> ideally they should look like they just kind of blend in and the buttons just pop up, all animated and smooth, instead of it looking like a toolbar box.
>
> Don't forget that whether it is the Joy Brush gradient or the Cyan gradient, the toolbar chips on the strip, when they can affect a stroke, they should be pulsing that selection color.

### K23 — Quality decides the default; Q2 is provisional (owner, October12)

> Q2 is not a hard fast rule. i am thinking of making the Proko pencil the default, and vector would be ideal if we can get it looking supurb. So far it WAS a tier as a raster, then regressed to b+ when i tried to add a few needed tweaks, we need the raster AND vector to b S tier. and if so then I prefer Vector. i would only go raster if raster looks enough better to warrent the extra file size

- Supersedes the claim that Q2 pixels-by-default is an owner-locked rule. Finger pan remains confirmed.
- Proko pencil is a candidate starting brush, not a finalized default. Target S-tier quality in BOTH raster and vector; prefer editable vector when visual quality is comparable/superb.
- Use raster as default only if a meaningful visible quality improvement justifies its larger file size. Do not claim that either implementation currently meets S tier.
- Owner reports raster formerly A tier and now B+ after needed tweaks: preserve this regression history for the pencil owner; compare against the earlier accepted appearance rather than treating B+ as the baseline target.
- UI keeps explicit shelf get/set choices and per-brush overrides; Lead/vector owner determine the eventual starting brush and mode after quality comparison. No engine changes in this lane.

### K24 — Selected styling locked (owner, October12; implemented October13)

> Cyan gradient works, rounded and borderless changes look great. build it. no more need to update the web preview. we got it locked in

- Supersedes the K21/K22 palette trial: native selected fills and outlines use cyan at upper left, subtly deeper cyan at lower right. `SelectAccent` now exposes this one approved diagonal shader; no palette trial option remains in native controls. Global token mirrors stay unchanged.
- Switches/segments keep cut-out words/icons. Tools, swatches and layer targets retain visible contents inside a thin cyan-gradient ring. Lead released LayerColumn presentation for this checkpoint: full-pill thumbnail/mask backings and clips match their outlined shape; bitmap scaling, hit rectangles and animation markers remain unchanged. Global colour token mirrors stay unchanged.
- Full pills and borderless paper-at-88% floating backings remain approved. The docked strip is flat against the screen, with 23dp canvas-facing corners.
- Strip width, colour and brush rings pulse twice over 1200ms on entry to line selection, then stay steady; reduced motion stays steady. `ToolStripView.setSelectionRestyle(on, pulse)` supplies this presentation. Lead must route the highlighted controls to real selected-line edits when wiring Activity.
- Web preview work is finished at the owner's request. Do not keep changing or republishing it. K23's quality-dependent pencil/mode preference remains provisional.
- Lead explicitly approved a backward-compatible `TransformOverlayView.Host.cancelGesture()` default no-op on October13. Ordinary uncommitted drag/tap and cancelled/unmoved pinch notify once before re-reading the host; Select rolls back its preview and restores the row. Existing Studio hosts keep their default behavior, and bend cancellation is unchanged.

### October13 — K11/K12 count implementation

The bounded count scrubber now uses tap-next and wrapped12dp horizontal steps over one
captured rank. Add keeps one group-aware base, replacing only the browsed candidate unit;
count selection never writes drawing/history. A22dp cyan-outline paper pill has a44dp
contact target, plain label and keyboard/accessibility actions. Cancel preserves the last
accepted choice; stale records/rank/flags, hidden controls and secondary contacts are guarded.

The owner-locked K24 styling and provisional K23 quality/default decisions remain intact.
Lead must supply App.hasRestingThumbForCount() from accepted cross-child contact context
and chain controller.viewMoved() alongside existing canvas.onViewMoved handling. No raw
palm inference or false default thumb provider. The current string candidates must migrate
with the rest of Select to ranked LineAddress entries before all-layer live binding.
Exact standalone32 core/56 native proof and integrated review are in reviews/UX_COUNT_20261013.md.

### K25 — A lines switch on each brush; the badge shows the mode last used; smudge drops a section (owner, 2026-10-24)

> A switch on each brush, badge appears when vector or raster was last used, ability to toggle is disabled or hidden from brushes that cannot be vector. smuge brush has the drop feature /rasterize a vector line or section it touches, other mode ignores vector lines. ux specialist already desighned this behavior in detail ask it whats up

- **Switch:** "Editable lines" in each brush's own hold-for-settings sheet (JB-5.20 D6), stored per brush (the
  per-brush tool preference keyed by brush id, `BrushLinePreferences`). The ⋯ menu row for the current brush is a
  shortcut to the same flag, never a second setting. Defaults: ON for the line brushes (Inks & pens, the line set and
  the watercolour vector brush), OFF for painting brushes; no global fallback.
- **Brushes that can never be lines** (media pencil/watercolour/oil, smudge, push, wet, blur, clone, paste) show NO
  switch (a dead knob is worse than none), only D22's sentence: *"Makes pixels: this brush blends with what's under it,
  so its strokes can't be picked as lines."*
- **Badge = the brush's current mode** (what it was last used as), on every drawer tile, bottom-right, 12dp box,
  ~80% of the drawer's ink. It replaces D22's pixels-only badge:
  - raster: P9 C's dotted corner, dots r1.3 at (10,2) (10,6) (10,10) (6,6) (6,10) (2,10) in a 12x12 box;
  - vector: a node-curve, stroke 1.3 `M2.5 9.5 C2.5 4 9.5 8 9.5 2.5`, round caps, with 2.2 x 2.2 square nodes centred on
    both ends.
  Pixels-always brushes always wear the raster mark. Hover label: "Makes editable lines" or D22's sentence.
- **Smudge, two brushes** (JB-5.20 D8, AMENDED): *Smudge* drops what it touches: the touched line is first SPLIT at
  the smudge footprint's edges (the same engine split as Slice, D24), only the touched piece is baked into pixels at its
  own seq, the untouched pieces stay editable lines; one undo step; D23's toast *"That line became pixels."* with Undo.
  D8's "never part of a line" rule was against baking part of a line while keeping its whole record (the ghost); a
  split first removes the ghost. *Smudge paint only* ignores lines: it runs on the pixel slabs and never reads a line.
- Builders: engine default + split (watercolour Codex brief L3 addendum, the lead reviews); the sheet switch, both
  badge marks, the no-switch sentence and the toasts (Codex Select brief A10); the look is checked by the UI specialist.
