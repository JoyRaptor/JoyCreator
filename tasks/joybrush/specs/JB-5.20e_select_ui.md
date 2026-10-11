# JB-5.20e — Select: pick a line, lasso, the box, nudge, and the line/pixel boundary on screen

| | |
|---|---|
| **Tier** | T1 design (UI specialist). Phase 2 slices below say who builds each |
| **Status** | 📝 Design, UI specialist, 2026-10-11. **Phase 1:** this spec + the owner's options page `tasks/design/UI-Select-options.html`. **Phase 2 (code)** starts when the owner sends back his picks; they become §K |
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

## §K — The owner's picks (locked when he sends the line back from the options page)

_Empty until the owner replies. The options page `tasks/design/UI-Select-options.html` offers these parts; his picks
and his words go here verbatim, and every "§K-Pn" above then reads from this section._

| Part | Question | Options on the page |
|---|---|---|
| P1 | Where the actions live | Over the box (Concepts) · Bottom dock (thumb) · A pill on the box |
| P2 | How selected lines show | Fade the rest · Cyan halo · Both, lighter |
| P3 | Recolour / re-brush from | Popup chips only · Popup chips and the strip's own buttons · The strip's buttons only |
| P4 | Dense picking help | Count only · Count + finger crosshair · Count + candidate fan |
| P5 | Lasso filters | Chips re-read the loop · A toast offers the other reading · Set before you lasso |
| P6 | Adding and removing | Tap replaces; ＋ chip or a resting thumb adds · Tap adds; tap empty space to finish |
| P7 | Nudge size | Slider in the bar · Drag the ring's edge · Pinch the ring |
| P8 | Two fingers | Inside the box transforms, outside moves the page · Anywhere transforms |
| P9 | The "makes pixels" badge | Pixel grid · "px" tag · Dotted corner |
| P10 | One finger after the pen | Selects (tap picks, drag lassos) · Pans, as today; selects only in the Select tool |
| Q1 | Flatten Lower means (JB-5.20 Q1) | This layer and every layer below · This layer and the one below |
| Q2 | Paint and Airbrush make (JB-5.20 Q2) | Pixels by default, switch per brush · Lines by default |

## Do not

- Edit `JbCanvasView.kt`, `GlPaintEngine.kt` or `JoyBrushActivity.kt`. Ask for the hook.
- Modify `:studiokit` beyond H6's two default-preserving flags.
- Install on the phone. One Gradle at a time through `jb-gradle.lock`.
- Ship a control that does nothing (Lock before its field exists; Bend on pixels before JB-2.05c).
