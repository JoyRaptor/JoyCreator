# Brief: Joy Brush selection & transform UI specialist (paste into a new session ONLY after the lead says "go")

**Status: GO for both phases (lead, 2026-10-11).**
- **Phase 1:** the interaction spec and the owner's design-options page.
- **Phase 2:** code. It starts after the owner picks a design option. It builds on the canvas plug-in point
  `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/tools/CanvasTool.kt`; read its contract. Set
  `JbCanvasView.tool` to your Select tool, and every contact you accept is yours until the gesture ends. Your tool and
  overlay views live in new files. The lead adds any other hook you need.

You are the **selection and object-manipulation UI specialist** for Joy Brush (Android, Kotlin). The lead is the
Claude session **"Joybrush orchestration and completion"**. Reach it with SendMessage, or under a dated heading in
`tasks/joybrush/AGENT_BOARD.md`. Work in your own worktree: `git worktree add C:/Temp/jb-select origin/joy-creator
-b ui/<topic>`.

## Goal
Match Concepts as closely as possible for selecting, moving and reshaping things on the canvas, without loading the
screen with buttons. The owner works **one-handed on a phone** (often holding a baby), with an S Pen, on a Galaxy Note 9.

## Read first
1. `START_HERE.md` (rules), `tasks/joybrush/LEAD_PLAN_20261010.md` (lanes).
2. `tasks/joybrush/research/concepts-app-interaction-reference.md`: the gesture grammar, selection, transform popup,
   nudge, item picker and lasso. Then `research/R5_concepts_infinite_painter.md` §1 and §3 (patterns P1–P18).
3. `specs/JB-5.20_lines_in_the_one_layer.md` D11 (selecting) and D12 (Rasterize Down / Flatten Lower).
4. Owner rulings that override research: `OWNER_CONSTRAINTS.md` (tap outside commits; the finger becomes a tool finger
   for select / lasso / colour pick once a pen is seen; nudge moves the same distance **on screen** at any zoom;
   selection in dense line work must be as good as Concepts) and `LEAD_RULINGS.md` R42 (S Pen button: hold and loop =
   lasso).
5. Look and feel: `design/JOYBRUSH_VISUAL_LANGUAGE.md`. House rules: every button has a hover label (stylus hover
   shows tooltips); dp values stay as drawn (don't inflate for big screens); drawers are never opaque (~55%
   see-through; Frost = blur only).

## Core you build on (exists, tested, not on screen)
`core/vector/StrokePicker` (tap and tap-again cycling), `InkEditSession` (select, drag, steps), `StrokeEdit`
(reshape/reweight/rebrush/recolour), `VectorEraser` (slice = width 0), and `core/select/` (`Lasso`,
`SelectionMask`, `Homography`, `Resample`, `Nudge`). Undo is ONE history: `UndoLog.Step` with `LineChange`.
**One press, one undo step**, always (owner ruling).

## Scope (JB-5.20e)
The Select tool. Tap to select a line, tap again to cycle. Lasso: lines it encloses, else a pixel selection, with no
mode switch. A transform box with the Concepts popup (copy, duplicate, delete, flip, lock, colour, swap brush,
re-weight, Rasterize Down). Move, scale, rotate and stretch handles, and two-finger transform inside the box. Nudge with
soft falloff and a size slider. Drag a tip. The layer-menu buttons. The "bakes to pixels" badge and its one-time toasts.

## Boundaries
New files go in `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/select/` and
`androidkit/.../select/`. The lead owns `JbCanvasView.kt`, `GlPaintEngine.kt` and `JoyBrushActivity.kt`. Ask for a
hook (signature plus call site) and the lead adds it. Build and phone rules are the same as the vector brief: one Gradle
at a time via `jb-gradle.lock`, never install on the phone yourself.

First deliverable: an interaction spec `specs/JB-5.20e_select_ui.md` (gestures, states, what each control does,
mapped to Concepts with file:line from the research). Before any code, give the owner 2–3 visual options as an HTML
page to choose from (see the memory "design options as a mix-and-match page").
