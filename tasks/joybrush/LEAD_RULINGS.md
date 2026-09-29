# Joy Brush — Lead rulings (Claude)

Rulings on questions raised by builders, the orchestrator and the adversarial reviewers. A ruling here
overrides the spec it names where they disagree. Newest at the bottom. Specs and the board are updated
from this file when nobody else is editing them (to avoid merge conflicts).

## 2026-09-28 — orchestrator's first triage

**R1. JB-0.03 F1 (size = Infinity freezes the stroke) — BLOCKER, fixed in two places.**
- Engine (done by the Lead, commit after `8b0654a`): `DabPlacer` now clamps every dab to finite
  values (radius 0..2048 px, flow 0..1, cap 0..1, angle finite) and the step to the next dab is always
  finite. A bad brush can no longer hang or freeze a stroke. Test: `anInfiniteBrushSizeCannotFreezeTheStroke`.
- Validation: **JB-0.03b's scope now includes it.** Its rule 3 ("every number must be finite") already
  covers `size.base = 1e999`; add the reviewer's exact case as a named test, and add
  `size.base ≤ 4096` to the range table. The orchestrator may dispatch JB-0.03b as soon as the board
  is merged.

**R2. JB-0.07 F3 (lean and barrel copied instead of interpolated in `DabPlacer.lerp`) — fixed** by the
Lead: both now interpolate the short way round (`Angles.lerp`). Test:
`leanDirectionTakesTheShortWayRoundBetweenSamples`.

**R3. Unknown enum values (JB-0.02 `BoardKind`/`LayerKind`/`BlendMode`, JB-0.03 `BrushInput`) —
decided: this is a VERSION rule, not a code bug.**
- Adding a constant to any serialised enum **requires bumping the file's version**
  (`DOC_VERSION` / brush `version`). Readers already refuse newer versions with a clear message
  ("from a newer Joy Brush"), and `DocJson.decode` / `BrushJson.decode` already turn an unknown
  constant into `DocException` / `BrushException` instead of crashing — so an old app shown a new file
  says "can't open, newer version", and never silently turns a new kind into a wrong one (which would
  destroy data on re-save).
- Action: add this rule as a KDoc line on each serialised enum and a sentence in JB-0.02 / JB-0.03
  specs ("new constant ⇒ version bump"). No behaviour change. Folded into JB-0.02b (below).

**R4. `Tool` stored by ordinal (JB-0.04 review) — decided: order is frozen.** Constants are only ever
appended. Done by the Lead: KDoc on `Tool` + `ToolOrdinalFreezeTest`.

**R5. "Fingers never draw after the first pen event" (JB-0.07 review) — the owner's ruling stands.**
The reviewer's worry (a pen that dies mid-session strands the user) is real for battery pens but not
for the owner's S Pens (EMR drawing needs no battery). Mitigation already planned: after JB-2.02 fingers
NAVIGATE (never dead), and JB-2.02b adds a "Fingers draw" setting. No code change now.

**R6. JB-1.20 blocked on "needs a browser" — alternative Definition of Done.** Either (a) the builder
runs the page headless with any installed Chrome/Chromium (`CHROME=<path>`, as
`joybrush/tools/shader_check.js` does) and attaches screenshots, or (b) the OWNER opens it via
`python -m http.server 8777` and confirms it draws. Either one completes it. Set it back to 🟦 Ready.

**R7. Model names on the board.** When the orchestrator dispatches a subagent it records the model it
CONFIGURED for that subagent (or "opencode default: <model>") — not "subagent". If the harness truly
cannot tell, write "unknown model (subagent of <orchestrator model>)".

**R8. Merging the orchestrator's diverged board.** The orchestrator was right not to resolve the
conflict. Procedure: the orchestrator pushes its local branch to a NEW branch
(`git push origin HEAD:bunny/orchestrator-sync` — a new branch can never conflict), and the Lead merges
it into `joy-creator` taking the orchestrator's statuses for every row it changed, then pushes. After
that the orchestrator's `git pull --ff-only` succeeds (the merge contains its commits), and its
uncommitted files are untouched.

## 2026-09-29 — review of the second orchestrator's first session (commits 0555cdf..83543c9)

Verified in the cloud: `:core:jvmTest` 410/0, `:androidkit:test` 85/0, androidkit compiles. The
serialization `implementation` → `api` fix, the GIF header (5 → 7 bytes) and LZW width fixes are right.

**R9. JB-2.06a provisional rulings 1–3 — confirmed.** Each follows from the spec's own Decisions
(push-back restores the edge → 512; a closed 3×3 ring encloses (5,5) → 72; the diagonal count is
derived per pixel). Standing rule for everyone: an expected value in a test may be changed ONLY with
its derivation written into the test, as was done here — never just to make a run go green.

**R10. JB-2.02 questions.** Provisional rulings 1–4, 7, 8 confirmed.
- Q5: brush size in DOCUMENT px is correct (a stroke must look the same when you zoom back out).
  For JB-2.16: the size control shows the on-screen circle at its true screen size (radius × zoom)
  while you drag, and writes `size.base` in document px (screen px ÷ zoom).
- Q6: real defect, fixed by the Lead with an immutable per-frame snapshot of the view — but it edits
  `JbCanvasView.kt`, which the uncommitted JB-0.08b also edits, so the Lead holds it until JB-0.08b is
  committed and pushed, then lands it. Do not fix it in JB-0.08b.

**R11. JB-0.08b questions.**
- Pen down during an autosave: `GlPaintEngine.strokeInProgress` now exists (Lead, this commit). An
  autosave that finds it true does NOT snapshot; it sets a "save owed" flag and saves right after the
  stroke's `endStroke`/`cancelStroke`. Never end or cancel the person's stroke to save.
- SAF "Save a copy" cannot be atomic — accepted. Build the whole archive into a temp file in
  `cacheDir` first (same code path as a normal save), then stream it to the Uri opened with mode
  `"wt"`. On any failure say so in words ("Couldn't save the copy. Your drawing is safe.") and delete
  the temp file. "Save a copy" must never touch the working file.
- 534 lines against ~200 is fine IF every line is exercised by a test or the device check.

**R12. Spec runway.** Front-load spec writing now: keep at least 3 🟦 Ready rows at all times; when
it drops below 3, write specs before dispatching more builds. Order as the orchestrator proposed,
with two changes:
- JB-0.09 (lobby entry) must edit `LobbyFragment.java`, which has the OWNER's uncommitted changes.
  Mark it ⛔ Blocked "owner must commit or discard his LobbyFragment edits first" until he does. Write
  the spec anyway.
- JB-2.10 (hold-to-shape maths) is claimed by the Lead — do not split or dispatch it.
- JB-1.20 headless browser check by the orchestrator (R6 option a): approved.

## 2026-09-29 — muse-spark audit of the new Built work (10 review files)

**R13. The two MAJORs.**
- **JB-2.02 mid-stroke handoff is dead — CONFIRMED, fixed by the Lead.** Before a pen is seen a finger
  draws; a second finger cancels that stroke, but `CanvasGestures` never saw the ACTION_DOWN so it
  ignored the rest until a re-touch. `CanvasGestures.handOver()` (landed) lets the view hand the
  gesture over; the machine then adopts every finger where it is. It is only adopted when the view
  says so, so a palm the view swallowed plus one finger still cannot drag the page. The view's
  one-line call is in `tasks/joybrush/held/JbCanvasView_lead.patch` with the R10 frame-snapshot fix;
  the Lead applies it after JB-0.08b is pushed. Device check owed (sandbox phone, fingers only):
  draw with one finger, drop a second, pinch — the page zooms with no lift.
- **JB-1.05b `uptimeMillis()` seed makes strokes unreplayable — downgraded to MINOR.** Where a seed
  comes from does not matter; replay needs the seed to be SAVED. `StrokeRecord.seed` already exists.
  Rule for whichever task first records strokes: the `StrokeRecord` gets the exact seed the
  `BrushDabber` used (the view keeps it for the stroke's life). No code change now.
- JB-1.05a: its code is committed (`9c7cc9a`) and built on — the orchestrator sets its row to 🟧 Built.
- The MINORs: orchestrator triages per ROADMAP §5b.

**R14. The 6 uncommitted app files are NOT the owner's.** The owner does not write code or use git;
they were left in the working tree by earlier agent sessions (Studio polish work): `ProjectStorage.java`,
`INBOX.md`, `LEDGER.md` (staged), `FaditorEditorActivity.java`, `LobbyFragment.java`,
`strings_studio_polish.xml`. The watcher has been building WITH them all along. Procedure, AFTER
JB-0.08b is pushed so nothing else is uncommitted:
1. `git switch -c bunny/leftover-app-edits`
2. `git add` those 6 paths by name, commit "Leftover app edits from earlier sessions (for Lead review)"
3. `git push origin bunny/leftover-app-edits`, then `git switch joy-creator`.
The Lead reviews them in the cloud and either merges them into `joy-creator` or drops them, then
JB-0.09 is unblocked. Never ask the owner to commit, stash or resolve anything.

## 2026-09-29 — eight new specs from the Lead (runway)

**R15.** The Lead wrote eight specs, all pure `:core` maths, testable in the cloud, and dispatchable
now: they need no screen chrome (JB-2.01) and touch no file anyone is editing. They are the "a" halves
of UI rows; each UI row keeps its number and now also depends on its "a". Lead-written, so they go
straight to 🟦 Ready (no cross-review needed). The orchestrator adds these rows to the board:

| Row | Tier | Needs | Status |
|---|---|---|---|
| [JB-2.05a](specs/JB-2.05a_selection_mask.md) Selection masks: lasso (non-zero), rect, ellipse, from-fill, boolean ops | T2 | 0.07, 2.06a | 🟦 Ready |
| [JB-2.05b](specs/JB-2.05b_transform_and_resample.md) Transform maths: affine, box handles, tile resample | T2 | 2.05a, 2.02 | 🟦 Ready (after 2.05a) |
| [JB-2.12a](specs/JB-2.12a_guides_and_snapping.md) Guides: grid / iso / perspective / ruler / ellipse, stroke snapping, visible lines | T2 | 0.01, 2.02 | 🟦 Ready |
| [JB-2.16a](specs/JB-2.16a_size_opacity_drag.md) Size & opacity drag maths + zoom-scaled nudge | T2 | 0.03b, 2.02 | 🟦 Ready |
| [JB-3.05a](specs/JB-3.05a_playback_clock.md) Playback clock: loop / ping-pong / once, range, audio position, no drift | T2 | 3.01 | 🟦 Ready |
| [JB-3.08a](specs/JB-3.08a_three_finger_swipe.md) Three-finger swipe: mode choice, badge override, frame flip / brush | T2 | 2.16a, 3.01 | 🟦 Ready (after 2.16a) |
| [JB-4.01a](specs/JB-4.01a_sprite_grid_math.md) Sprite grid maths: by size / count, cell lookup, edge drag, sub-grids | T2 | 0.02, 4.03a | 🟦 Ready |
| [JB-5.03a](specs/JB-5.03a_stroke_edits.md) Stroke record v2 (colour, width) + reshape / re-weight / re-brush | T2 | 0.04, 5.02 | 🟦 Ready |

Dependencies to add to existing rows: JB-2.05 needs 2.05a + 2.05b · JB-2.12 needs 2.12a · JB-2.16
needs 2.16a · JB-3.05 needs 3.05a · JB-3.08 needs 3.08a · JB-4.01 needs 4.01a · JB-5.03 needs 5.03a.
Parallel-safe: every pair above except 2.05a→2.05b and 2.16a→3.08a (and 5.03a edits StrokeCodec, so
nothing else touching `stroke/` runs beside it). The orchestrator keeps writing its own list
(0.09, D.02, 2.14c, 8.01/8.02/8.04, 3.06b, 2.15, 0.10); the Lead will not write those.

## 2026-09-29 — reuse the Studio's colour picker and transform tool; more specs

**R16. Reuse, don't rebuild (owner).** Joy Brush uses the Studio's colour picker
(`ColorPickerDialog`) and transform surface (`TransformOverlayView` + `TransformQuad` +
`HandleModel`). The Lead audited the transform tool first:
`tasks/joybrush/reviews/STUDIO_TRANSFORM_AUDIT__lead.md` — 4 MAJOR gesture bugs (a drag + second
finger jumps back; a third finger splits the pinch's undo; an absorbed drag can end uncommitted;
rotation past 180° is stored the long way round, which breaks keyframes), 1 MINOR feel bug (corner
lags the finger by half), 1 stale harness test. Order: **D.02a** (fix them, in the app) → **D.02**
(move them into the shared `:studiokit` module, pure move). The Lead has taken over **D.02** (it
supersedes the old outline; the orchestrator's planned pieces — scrubbable number, slider row, header
icon button — become **D.02b**, still the orchestrator's to write, into the same module).

**R17. Warp and puppet come almost free.** The Studio's `transform/mesh/` holds a green grid-warp
engine and a full puppet pipeline (contour → triangles → weights → MLS solver → pose tracks; ~290
harness checks pass). Plan (outline rows, specs later): **D.03** move `mesh/` into `:studiokit`;
**JB-2.05c** warp a selection with the lattice (T1, after JB-2.05); Phase 7's puppet board builds on
the same engine — JB-7.02 already says "no copy". Liquify is NOT in there (JB-1.06 nudge covers push).

**R18. New Ready specs** (add to the board; Lead-written, so 🟦 Ready):

| Row | Tier | Needs | Status |
|---|---|---|---|
| [D.02a](specs/D.02a_transform_fixes.md) Fix the Studio transform tool's gesture bugs (audit T1–T6) | T2 (T1 review) + T3 | — | 🟦 Ready |
| [D.02](specs/D.02_studiokit_module.md) `:studiokit` module: Studio colours, colour picker, transform surface (pure move) | T2 (T1 review) + T3 | D.02a | 🟦 Ready (after D.02a) |
| [JB-2.05b](specs/JB-2.05b_transform_and_resample.md) **REVISED:** homography (quad→quad) + tile resample; handles come from the Studio overlay | T2 | 2.05a | 🟦 Ready (after 2.05a) |
| [JB-2.07a](specs/JB-2.07a_lasso_fill.md) Mask paint: lasso fill / erase / paint-behind maths | T2 | 2.05a | 🟦 Ready (after 2.05a) |
| [JB-2.06b](specs/JB-2.06b_fill_tools_on_canvas.md) Fill tools on the canvas now (tap fill, lasso fill/behind, lasso erase) + Tool pill | T2 + T3 | 2.06a, 2.07a, 2.05a, 2.13a | 🟦 Ready (after 2.07a) |
| [JB-2.03a](specs/JB-2.03a_colour_pill_and_eyedropper.md) Colour pill (Studio picker) + long-press / S Pen button eyedropper | T2 + T3 | D.02, 2.13a | 🟦 Ready (after D.02) |
| [JB-2.13b](specs/JB-2.13b_paper_and_png_export.md) Paper colour + Export PNG (screen / drawing / board, include paper, 1–4×) | T2 + T3 | 2.13a, 2.14a, 0.08b, D.02 | 🟦 Ready (after D.02) |

Outline rows to add: **D.03** move `mesh/` into `:studiokit` (T2, after D.02) · **JB-2.05c** warp a
selection (T1, after 2.05, D.03) · **D.04** make `tools/jvm-harness/run-*.sh` pick `:` or `;` by
`uname` so the cloud can run them (T2, tiny).
Changes to existing rows: JB-3.08a — owner answered: ends STOP, with push-through wrap (spec
updated, still Ready after 2.16a). JB-3.05 (player, not 3.05a) — owner: pressing Play while at the
end starts from the beginning. JB-2.06b now includes lasso fill; there is no separate 2.07b.
Parallel-safety: JB-2.06b and JB-2.03a both edit `JbCanvasView` and `JoyBrushActivity` — never run
them at the same time. D.02a and D.02 touch app files — one at a time, and never beside JB-0.09.

**R19. JB-2.16a / JB-4.01a referred questions — fixed by the Lead.**
- `BrushValidate.MAX_SIZE_PX` is now public and `SizeOpacityDrag.MAX_SIZE` IS it (no copy); a test
  pins the equality. JB-2.16a Q4 (pass `view.zoom`, not its inverse): confirmed.
- `SpriteGridMath`: `cellRect` and `subGridLines` add in Long; `cellRect` REFUSES
  (`IllegalArgumentException`) a cell whose rectangle cannot be expressed in Int instead of wrapping
  to a wrong place; `cellAt` range-checks in Double before any `toInt()`, so it no longer relies on
  JVM saturation (iOS door). Two tests added. Orchestrator's other provisional rulings on both
  specs: confirmed.

## 2026-09-29 — owner: the fill pen, swappable vector brushes, recent colours, share everything

**R20. Every vector brush is swappable after drawing (owner; "as in Concepts").** On an INK layer a
stroke is its recording (`StrokeRecord`), so selecting one or MANY strokes and picking another pen
redraws them as if drawn with that pen: pencil → fill pen gives the solid shape (ends joined), fill
pen → pencil gives the outline stroke. `StrokeEdit.rebrush` (JB-5.03a) already keeps the samples and
the seed; the Lead's JB-5.01 renders by the stroke's CURRENT brush; JB-5.03 (UI) applies a brush pick
to the whole selection as ONE undo step. Only `stamp` and `fill` engines are allowed on ink layers:
`smudge` and `wet` read the pixels underneath, so they are paint-layer brushes (picking one while
ink strokes are selected is refused in words).

**R21. The fill pen is a brush, not a tool** (owner). JB-1.08a (brush `engine: "fill"`,
`blend: "behind"`, brush version rule) + JB-2.06b revised (tap fill tool + fill pen on paint layers).
The old "lasso fill / lasso erase" tools are gone; JB-2.07a (`MaskPaint`) stays as the fill pen's
raster maths.

**R22. Eyedropper:** drag off the colour swatch (fast; drag back onto it to cancel) AND long-press
(with a visible cancel: slide back to the start circle, or a second finger; setting to turn it off).
JB-2.03a revised. **Recent colours bar** (owner: "a must have") = D.02c: one app-wide colour history
shared by the Studio picker and Joy Brush, and a thin bar that grows and divides by history (12 max).

**R23. Share, don't copy (owner's modular rule).** When Joy Brush needs something the Studio has, it
moves into `:studiokit` (same package names, pure move) and both use it; improvements land once.
Joy Brush's pure-Kotlin core cannot call Java, so where core needs the same MATHS (blend modes) it is
proven equal by a generated golden table + drift check (JB-2.20a), never copied by eye. Integration
code that calls kit classes lives in `joybrush-android` (the only Joy Brush module in the app build).

**R24. New Ready specs** (Lead-written → 🟦 Ready; add to the board):

| Row | Tier | Needs | Status |
|---|---|---|---|
| [JB-1.08a](specs/JB-1.08a_fill_pen.md) Fill pen brush: `engine "fill"`, `blend "behind"`, outline maths, shipped preset | T2 | 0.03b, 0.01 | 🟦 Ready |
| [JB-2.06b](specs/JB-2.06b_fill_tools_on_canvas.md) **REVISED:** tap fill tool + fill pen on paint layers | T2 + T3 | 2.06a, 2.07a, 2.05a, 1.08a, 2.13a | 🟦 Ready (after 1.08a, 2.07a) |
| [JB-2.03a](specs/JB-2.03a_colour_pill_and_eyedropper.md) **REVISED:** colour pill + drag-off eyedropper + long-press with cancel | T2 + T3 | D.02, 2.13a | 🟦 Ready (after D.02) |
| [D.02c](specs/D.02c_recent_colours_bar.md) Shared colour history + recent-colours bar | T2-V + T3 | D.02 | 🟦 Ready (after D.02) |
| [D.05](specs/D.05_share_fx_gradients_blends.md) Move fx (17 effects, gradient ramp/curve), keyframes, BlendModes, MaskSdf, gradient editor into `:studiokit` | T2 + T3 | D.02 | 🟦 Ready (after D.02) |
| [JB-2.20a](specs/JB-2.20a_all_blend_modes.md) The Studio's 26 blend modes in the document + export maths, golden-table parity, DOC_VERSION 2 | T2 | 2.13a, 0.02b | 🟦 Ready |

Outline rows to add (Lead designs; specs follow as their dependencies land):
- **JB-2.20b** (T1) GL layer compositing with the Studio's `GLSL_BLEND_FN` — preview = export. Needs 2.20a.
- **JB-2.21** (T1 engine + T2 UI) FILTER layers = the Studio's adjustment layer: an `FxStack` over
  everything below (blur, levels, colour grade, gradient MAP to remap colours, posterize, duotone…),
  UI = the Studio's `FxPanel` (D.05b moves it). Needs D.05, 2.20b.
- **JB-2.22** (T2) Gradient tool: the app-wide gradient editor bar (`GradientRampEditorView`) +
  drag to place linear / radial / curve gradients, baked into the layer, clipped to a selection or
  to a fill-pen shape. **JB-2.22b** fill pen with a gradient fill ("set a shape as this", owner).
- **JB-2.23** (T1) Layer masks and clipping (a fill-pen shape can be a mask; an adjustment layer
  clipped to a shape = "set a shape as a gradient map", owner). Needs 2.21.
- **D.05b** (T2) move `FxPanel`, `BlendPickerPopover`, `MaskKeyPanel` with their resources.

## 2026-09-29 — the local Lead takes over: verified state, the seven open items, and the 38 new specs

Verified on a CLEAN checkout of the pushed commit (`1bae49c6`), not the working folder (which had a live
builder writing into it): `:core:jvmTest` **665 tests / 2 failures**, `:androidkit:test` **92 / 0**. The
orchestrator claimed 665/1 and 90/0. The second core failure is the JB-5.10 wall-clock test, which is
NOT "red for lack of an index" (R28). After R25/R26: `:core:jvmTest` 680 / 0, `:androidkit:test` 92 / 0.
Every spec-by-spec verdict is in `reviews/SPECS_38__lead.md`; **these rulings override the specs**.

### A. The open items the cloud Lead left

**R25. JB-3.08a — the badge override is PER BOARD and remembered; `badge()` is a pure read.** Done, with tests.
- The orchestrator's diagnosis was wrong twice. The test fails at line 138, not 128–130, and a per-board
  map would NOT have passed the old test "unchanged" (line 129 asserted "forgotten on switch").
- The real defect was in the CODE: `badge()` erased the override whenever it was asked about another board,
  so merely drawing two badges destroyed the person's choice. Now a `HashMap<boardId, SwipeMode>` that only
  `tapBadge` writes (it also drops entries for boards the document no longer has).
- Reason (owner's own ruling, blueprint §6): the mode is keyed to the ACTIVE BOARD. Glancing at the sketch
  board and coming back must not change the animation board's badge.
- Mutation-checked: the old code fails two of the new tests. Spec JB-3.08a Decision 2 and test 2 updated.

**R26. Save / autosave (JB-0.08b's three MAJORs + JB-2.15).** All three MAJORs verified against the code
(they are real work-loss). They are ONE bug — a save request was a moment, not a thing that waits its turn —
so they are fixed by one class: `core/io/SaveQueue<D>` (pure Kotlin, JVM-tested, 13 tests, two mutations
turn them red). JB-2.15's own contract is superseded: it put `android.net.Uri` in `:core` (breaks the iOS
door), referenced an undefined `SaveTarget2`, gave the wrong file path, and missed that the screen pauses the
GL thread after the FIRST snapshot, which would strand a queued second save. Rules now in force:
- Requests are queue entries: one at a time, in order, never dropped. IDLE merges only with a WAITING IDLE at
  the END of the queue; EXPLICIT ("Save a copy") is never merged or dropped.
- A pen down holds the whole queue; ONLY `strokeFinished()` (end AND cancel) releases it — the check is
  level-triggered, so an undo/redo/timer cannot pay or cancel the debt.
- `JbCanvasView.strokeInProgress` is the UI thread's own `drawing` flag (was an unsynchronised GL-thread read).
- The GL thread is paused only when the queue is drained, never after the first snapshot alone.
- A 15 s snapshot watchdog fails a save in words instead of blocking the queue for ever.
- **Fourth bug found and fixed:** after ANY save, including "Save a copy", the screen marked the drawing fully
  saved — but a copy never writes the working file, so a copy could cancel the pending autosave.
- Status: core + view landed. The `JoyBrushActivity` wiring is finished and compiled but is held until the
  JB-1.21 builder (mid-edit on that file) commits — then it is rebased and pushed.
- STILL OPEN from the 0.08b review, written up as **JB-0.08c** (T2, to spec): (F6) a re-save drops document
  metadata (layer name/blend/lock, board name, `textureScale`, thumbnail) — carry the loaded `JbDocument` and
  replace only what the engine owns; (F5) no `onDestroy` cleanup; (F7) one wrong-paper frame on open.
- NEW risk found (not in any review): **"Open…" replaces the canvas, then the next autosave overwrites
  `current.joybrush` — so the drawing you had before Open is gone** (only one `.bak` generation). Ruling: before
  Open… replaces a drawing, silently write the current one to `recent/<timestamp>.joybrush`, keep the last 5.
  Goes into JB-0.08c. No dialog.
- Owner check (Note 9): draw → Home → force-stop → reopen (drawing is back); pen down, tap "Save a copy…" →
  the copy contains the stroke; start a stroke, tap Undo mid-stroke, lift, Home, force-stop → what is on screen
  is what comes back.

**R27. JB-2.02's MAJOR is already fixed — remove it from the held list.** R13's `CanvasGestures.handOver()`
exists and `JbCanvasView` calls it (line ~272). No new API is needed. It has no JVM test (a `MotionEvent`
cannot be built off-device); the device check "draw with one finger, add a second, pinch → zooms without
lifting" stays owed.

**R28. JB-5.10 — do NOT build a grid index; narrow Decision 5 and replace the wall-clock assertion.**
- The "known-red" test is misdiagnosed. Measured warm cost is ~1.3 s against a 2.5 s bound: it passes alone and
  failed for me only while another Gradle job ran. A wall-clock assertion is flaky by construction.
- The builder's own arithmetic says a spatial index has a ~3.7× ceiling on this layout (50 scribbles through
  one 200×200 square — not a drawing anyone makes), so a grid would not deliver the 50 ms promise anyway.
  The eraser already has a per-line bounding-box prefilter (`VectorEraser.kt`), which is what a real layer needs.
- Ruling: Decision 5's promise applies to REALISTIC layouts (lines spread across the layer); the adversarial
  layout is bounded by a **deterministic work counter** (segment-pair tests ≤ a stated N), never milliseconds.
  Add the counter as an `internal` test hook; delete the `< 2_500L` assertion. JB-5.10 Q1 (speck rule): the
  0.5-doc-px rule applies to ALL THREE modes, implemented once in `VectorEraser` (that is one edit to JB-5.10's
  file; JB-5.11 then inherits it).

**R29. R14 leftover app files — MERGED, not dropped.** They are the owner's own Studio polish (hidden
recycle-bin long-press on the lobby logo; caption in/out sliders with their own values; old text boxes load
as per-letter — his ruling of 2026-09-23, recorded in `LEDGER.md`). The phone's builds contained them all
along, so dropping would silently remove features he has been using. Merged by cherry-picking ONLY commit
`14a82209` (the branch's other commit duplicates JB-0.08b). Branch `bunny/leftover-app-edits` can be deleted.
The watcher must show a green app build after this lands; that is the check. JB-0.09 is unblocked on this axis.

**R30. The lock order for shared hot files** (one row at a time in each; never two rows in one file):
1. `JoyBrushActivity.kt`: JB-1.21 (in flight) → JB-0.08b/2.15 wiring (Lead) → JB-0.08c → JB-0.10 entry →
   then chrome (JB-2.01) BEFORE any other row adds a pill. Rows 2.03a, 2.06b, 2.16, 2.17, 3.02, 3.05, 4.01
   all want this file; they wait for 2.01's cluster instead of each bolting on controls.
2. `GlPaintEngine.kt` / `JbCanvasView.kt` (Lead only): 2.20b → 1.05c → 1.06 → 5.01b → 2.21 → 2.23 → 2.05 →
   0.12 Half B. T2 rows do not edit these two files (JB-2.04 and JB-2.05's small engine edits are the
   Lead's, taken in this order).
3. `DocModel.kt` / brush format: version numbers are assigned AT LANDING, never in a spec. Specs say "bump to
   the next `DOC_VERSION`" / "the next `BRUSH_VERSION`"; the builder reads the current number. (2.21 says 3,
   2.23 says 4, 7.01 says 3 — they collide.) Brush words each carry their own minimum version in
   `wordsNeedingVersion` (smudge/push, gradient fill), so an ordinary brush never gets swept along.

**R31. JB-0.02's open MAJOR (re-saving drops unknown keys and can stamp a newer version over discarded
content).** R3 is extended: ANY new serialised field bumps the version (not only enum constants); readers
already refuse newer versions in words. The remaining hole is a same-version file with keys the reader does not
know: it is REFUSED in words ("this file has X, which this version of Joy Brush does not know"), never
silently dropped. Small T2 row **JB-0.02d** (DocJson only). Until it lands nothing new may rely on additive
fields.

### B. The 38 new specs — how they are judged, then the rulings they asked for

**Overall verdict:** they are good — a strong model wrote them and they hold the house quality bar in nearly
every respect (verbatim contracts, numbered decisions, honest Questions, existing APIs almost all real,
patent/licence rules respected). They are not yet as reliable as the Lead's, in five repeated ways, all
mechanical (list in `reviews/SPECS_38__lead.md`): wrong expected numbers in tests (JB-3.02 has four), unit
mix-ups (44 dp written as 88), source-level tests put in `commonTest` (cannot open files — `jvmTest`), stale
paths/line numbers, and two false premises (PSD Subtract; the Studio's ramp model). Fix list → Ready.

**R32. Units: every layout constant is in DP and multiplied by density at the use site.** JB-3.02 and JB-3.03
wrote "44 dp" and declared 88 as a `const` "already × density" — impossible (density is runtime) and twice the
size. Tick = **44 dp**, peg pitch **44 dp**, peg radius **14 dp**, hit radius **22 dp**, edge grab **12 dp**.
Every derived number in their test tables changes; the corrected JB-3.02 numbers are in the review file.

**R33. Animation board controls.** The peg bar owns PLAY and MODE (the owner's own idea: the pegs ARE the
buttons). The film strip has prev/next and nothing else — JB-3.05 Decisions 13–14 are amended accordingly, so
there is one saturated control. JB-3.03's `+` tap = **DUPLICATE** frame (what animators do most; FlipaClip is the
named reference), long-press = Blank / Link / Hold ± / Delete. (Phone-check item: if the owner finds duplicate
surprising, it is one constant.) Strip thumbnails: (c) now — numbered cells, host supplies bitmaps; thumbnails
via CPU render are **JB-3.03b**. **Audio:** JB-3.05's audio half needs a document field: new T1 row **JB-0.02c**
(`Board.audio`, archive entry `audio/<boardId>.<ext>`, `MAX_AUDIO_BYTES` = 16 MiB, refuse at attach time in
words; copied INTO the archive; version bump per R30/R31). The pure `FrameStepper` half of JB-3.05 is Ready.

**R34. Onion skin (JB-3.04).** The parity plan as written repeats the exact failure the JB-2.20a reviewers
caught (two hand-written transcriptions compared with each other; neither reads the real code). Corrected:
the golden is generated by RUNNING the real Java, so the Java must first be a runnable class — it is a private
inner class of a 4 823-line Activity today. Order: D.02 (`:studiokit`) → extract `OnionMath` (pure Java, no
Android) into the kit with SpriteLab calling it → generate the golden from it → then the core maths (**3.04a**).
No Python transcription. Q2: 0.62, floor 12, MAX 6 stay as the Studio has them (pinned, not ours to change).
Q3: (a) all frames in play order; the HOST passes the ghost list so the view never learns about ranges.

**R35. Animation export (JB-3.06b).** GIF, PNG sequence and sprite sheet + the plan layer are Ready. **MP4 is
delivered by "Send to Studio" (JB-3.07)**, where the Studio's own exporter produces it — no in-app MP4 encoder
now (a `MediaCodec` path is a later T2 row with a mandatory device check). **WebP is dropped** (Android cannot
write an animated one and nothing here can oracle a hand-written encoder). No scale factor, no range picker on
the sheet. Also fix: `NNNN.txt` in Decision 8 is a literal typo for the manifest name — use `<name>.timing.txt`.

**R36. Sprite board (4.x) — and a correction to the orchestrator.** The orchestrator's log says the "held
frames export at the wrong speed" finding is FALSE because "there is no SpriteSheet.kt in this repo". **It was
right.** The app is Java: `app/.../sprite/SpriteSheet.java` exists, its `Preset` carries `weights`
(`hasWeights()`, written only when something is held, range 1..9999 via `SequenceTiming`), and
`SpritePackerTest.nothingTheAppDoesNotWriteIsWritten` wrongly lists `weights` as a key the app never writes.
A cell held ×3 currently exports at the wrong speed. Ruling: `Clip` gains `weights: List<Int> = emptyList()`;
`SpritePacker` writes it under the app's own `hasWeights()` rule; the test's absent-list loses `weights` and a
positive round-trip test is added. New row **JB-4.03c** (T2; edits a reviewed file, so a re-review follows).
JB-4.03b's "refuse a roll with a hold" (Decision 7) is deleted once 4.03c lands. JB-4.02 Q1: roll is
session-only for now (a). JB-4.01 Q1: "used" is derived (yes). Q2: a `−`/`+` stepper pair now.
- **Export location (4.03b Q1):** write BOTH files together into `Documents/JoyBrush/<name>/` through
  MediaStore on API 29+ (no permission prompt, both land in one folder, visible in the Files app); below 29 the
  app's own folder, and say where. No tree-picker.
- **"Export and open in SpriteLab" (4.03b Q2):** the owner's ruling stands, so this needs the Studio side:
  new Lead row **JB-4.03d** (app file, in the serialised order) — `SpriteSheetEditorActivity` accepts a
  sheet + sidecar and imports them into a new project. Until then 4.03b ships "Export" only and the second
  button is absent (not dead).

**R37. Ink (5.x).** JB-5.01 is split: **5.01** (replay + raster, pure core, T2, Ready) and **5.01b** (the view
captures a `StrokeRecord` per stroke, and the GL side — T1, Lead). Q1: the SEED is generated by the view at
stroke start, stored in the `StrokeRecord`, and is the record's alone from then on (R13 confirmed; shape (a)).
Q2: randomness is a property of the RECORDING (doc-space spacing) — beads at 16× are invisible in practice
(edge scallop ≈ 0.005 × diameter). Q3: the 0..1 hardness rule lands in `BrushValidate` (tiny row **JB-0.03c**),
not in the ink path. Q4: no dab cap. JB-5.03: fix the contract typo `dy: Float` → `Double`; Q1: a smudge/wet
pick is refused AT PICK TIME (the UI greys it with the sentence), the session's refusal stays as the backstop;
Q2: one shared grab point; Q3: steps (50); Q4: wiring is mine, with 5.01b. JB-5.11: Q1 per R28; Q2: R10
stands (document px — the eraser is the brush with an erase blend; one-line change if it feels wrong); Q3:
erase everything else and REPORT the line that could not be replayed (do not refuse the whole gesture) —
Decision 5 and test 6 are rewritten that way.

**R38. The Lead's own T1 rows — decisions the writers asked for.**
- **JB-2.20b:** ship the single composite pass and measure on the Note 9 (option a); reject (b) as the exact
  two-implementations hazard. `GLSL_BLEND_FN` + `glslBlendFnWithModeParam()` (verified to exist) are the
  source. `ERASE_BELOW` as an extra branch is acceptable and documented — the Studio has no band for it.
  Keep two generators sharing one Java helper. Build BEFORE 0.12.
- **JB-2.21 — filters run on the GPU for export too.** The spec's plan (write a CPU twin for each of 17
  effects, offer an effect only when its twin has a golden, panel greyed in waves) is the expensive road and
  the Studio has no CPU reference for any effect (verified: `FxRegistry`/`FxCompiler` only emit GLSL/AGSL).
  Ruling: a `FxExecutor` seam in core; the on-device implementation runs the SAME compiled GLSL on an
  offscreen EGL surface for export, so preview = export BY SHADER IDENTITY and no effect waits for a twin. CPU
  twins are optional, for the few cloud-testable ones. The filter's ES 1.00 program stays its own program.
  A filter over animation frames is out of scope. The version cost (old builds refuse new files) is accepted —
  one owner, one phone.
- **JB-2.22:** `GradientRamp.sampleColor/sampleAlpha` exist in Java and are Android-free (verified), so the
  golden is generated from the REAL class — proof, not transcription. BUT the spec's `Ramp` does not match it:
  the Studio has TWO tracks (colour stops + opacity stops, per-left-stop `biasToNext`), booleans
  `mirror/flip/solidBands`, cap 8 — no global `bias`, no `bands: Int`. Rewrite the contract from the real class
  before Ready (2.22b's `RampData` inherits the error). Baked = baked. Placement kind is a three-way chip
  (line / circle / curve) in the editor bar; no gesture-only rules.
- **JB-2.22b:** needs the owner's answer in plain words (see the report); build Reading A only after it.
- **JB-2.23:** the composition reading is confirmed (a shape becomes a mask; a filter is clipped to it). Mask
  is a snapshot, not a live link. A mask on a FILTER layer is refused. Mask tiles compress in the zip, so the
  "262 KB per mask" worry is overstated.
- **JB-1.05c:** Q1 (a): accept the half-band, fix the comment (a dry pencil should speckle at a feather touch;
  T3 tunes). Q2: pitch = 64 / scale document px, fixed physical size. Q3: null image → `cloud_256`. Q4:
  per-stroke depth now. Also a new T1 row **JB-1.05d** for per-brush image tips/grain (needed by imports).
- **JB-1.06:** Q1: `"smudge": {"strength","pickup","load"}` and `"push": {"amount"}`, plain floats 0..1; one
  `SmudgeSpec`. Q2: the engine↔layer rule lives in core (`brush/EngineRules.kt`, tiny) so 1.06 and 2.04 share
  it. Q3: the pixel one is named "Push"; the tool-finger nudge stays "nudge".
- **JB-0.12:** use the AndroidX libraries (route a) — `androidx.input:input-motionprediction` for prediction
  and `androidx.graphics:graphics-core` for the front buffer; do NOT hand-roll a predictor. Half A shrinks to
  `LatencySettings` + `LatencyPolicy` + `PenState` (the two switches are independent; hidden below API 29).
  Half B (the view rewrite onto `SurfaceView`) is the Lead's, after 2.20b. Also fix: `object LatencyPredictor`
  cannot have a constructor.
- **JB-2.05 (T1, Lead):** confirmed: selection = stack of homographies (cap 64); painting is CLIPPED to the
  selection (samples outside are dropped; no fence); moved pixels commit back into the SAME layer as one undo
  step (NOT a new layer — Decision 7 is overturned; the layer list stays flat). **S Pen button — the spec
  contradicts the blueprint.** Blueprint: hold-while-drawing = erase, tap = eyedropper. Spec: button-tap with
  no loop = erase. Ruling (a design change, flagged to the owner): HOLD BUTTON + LOOP = select; the button never
  erases; button TAP = eyedropper; erase is the Eraser preset or the pen's own eraser end.

**R39. The UI rows.**
- **JB-2.01 (chrome):** the layout is the screen the owner lives in — I will produce a mockup he approves on
  the phone/preview BEFORE a T2-V builds it; until then the writer's cluster (rows: Undo Redo Tool Brush Colour /
  Paper Layers Helpers Gradient Export ⋯; rail: Brush Colour Layers) is a proposal. Starts after D.02. 600 dp
  breakpoint confirmed. `JbToast` local (a) until a shared toast exists.
- **JB-2.02b:** NUDGE is a mode; the cycle control is the badge in the cluster; `ZOOM_FIT` is dropped from the
  assignable set (untestable from this spec).
- **JB-2.04:** the blend chip is HIDDEN until 2.20b (a dead control is worse than a missing one); a file with a
  mode this build cannot composite is kept-and-marked; `SHARE = 0.5` is provisional until JB-0.10 measures a
  layer; editing `GlPaintEngine` is fine (JB-0.07 was xr-cleared).
- **JB-2.11:** 500 ms is a guess for the owner to tune. Contradiction fixed: the rough stroke STAYS visible in the
  engine's stroke buffer while the preview draws over it; on lift the buffer is cancelled and the perfected
  samples are fed through the same path (one stroke, one undo step). Fill pen: off.
- **JB-2.12:** helpers are per-session in v1 (persisting a perspective setup is a later row, with a version
  bump); snapping on; three ellipse handles.
- **JB-2.14c (PSD):** Q1's premise is wrong. Photoshop's Subtract is `base − blend` and Divide is `base ÷ blend`
  (clipped) — the same as ours; write the keys. The owner check opens a file with one layer per mode in Krita to
  confirm (verify before trusting this ruling). `ERASE_BELOW` refuses with an offer of a flattened export. INK
  layers are OMITTED with a warning naming them (an empty layer confuses). Also fix the contract (it uses
  `LayerKind`, `RectPx`, `StrokeRecord` without imports; `toBytes(...)` has no types) and delete the
  stream-of-consciousness in Decision 6.
- **JB-2.16 / JB-2.17:** 2.16 waits for 2.01 (fill pen: greyed ring "no size"). 2.17 lands LAST and its Needs
  gain 2.11, 2.16, 2.02b; add a fourth hint (the S Pen loop — a hidden gesture nobody discovers) — the "three,
  then never" rule is relaxed to four; `TAP_MS`/`TAP_SLOP_PX` move to core so the sheet can read them.
- **JB-0.10:** budgets provisional; the entry point is a "Run benchmarks" button in the hidden pen-diagnostics
  panel (after the Activity lock order); make `JbArchive`'s deflate level a public constant (one-line edit
  allowed); add `RegionRenderer.render` and `JbArchive.read` as two more cases; `psd-write` stays Unavailable.
- **JB-1.07:** two waves (five now, Smudge/Nudge after 1.06). The Eraser preset wins over the Eraser button;
  the button is removed by the chrome row (2.01), not before.

**R40. Import and Phase 7.**
- **JB-8.01/8.02/8.04:** all three importers store image tips/grain and set `source = "image"` WITH a visible
  warning "this brush's texture is kept but not drawn yet" until JB-1.05d lands (option a, uniformly).
  8.01: may edit `MypaintImport.kt` to read the shared `MAX_CURVE_POINTS`; hardness stand-in accepted
  (calibration later); budgets 64 MiB / 2 048. 8.02: **no hand-written Inflate** — `expect/actual` with the JVM
  using `java.util.zip` (and a zlib-backed actual on iOS later); taper imports LOSSY with a warning; Procreate's
  built-ins are refused; set name becomes a prefix. 8.04: ColorSmudge imports with a warning; a brush using both
  tilts carries a specific warning; licence/author are kept and surfaced later in the brush detail sheet.
- **Phase 7 (7.01–7.04) stays Draft and off the runway.** Pins (7.01) are built in option (c) — Joy Brush data,
  no solver — when scheduled; nothing else until the Lead documents Avatar Studio's real entry points
  (`PuppetPoseResolver`, `AvatarLibrary`, driver/viseme names) in one short file. No more Phase 7 specs before that.

## 2026-09-29 — the owner answers the two open questions (effect shapes; stylus buttons)

**R41. Effect shapes — the owner's answer to JB-2.22b ("set a shape as this") is Reading A, and bigger.**
Owner, in his words: draw a shape and it fills with a gradient; "but not a normal gradient" — a drawn shape can
be set to be an EFFECT that acts on what is underneath it: a gradient map (recolours what is under it), a blur,
a distortion. "Select a line, set it to a solid-fill shape, and add an effect to it." He was not sure it is a
good idea. **It is, and it is not a new engine — it is the machinery already planned, wearing a friendlier
face.** A shape whose fill is an effect is exactly "a FILTER layer (JB-2.21) clipped to a mask (JB-2.23)", except
the shape stays LIVE. So:
- A shape has a **fill kind**: `solid` (today), `gradient` (colours painted inside it — JB-2.22b's original
  meaning), or `effect` (an FX stack applied to whatever is below it, only inside the shape). The gradient
  fill paints; the effect fill *transforms what is underneath*; both exist because they are different tools.
- **Live, on ink layers.** A shape is an ink stroke (R20), so it stays a recording: move it, reshape it,
  re-brush it, change or remove its effect afterwards, crisp at any zoom. The effect lives in the document's
  filter table (`FilterSpec`, JB-2.21) and the stroke points at it by id (`StrokeRecord.effectId`), so the stroke
  codec stays small and the Studio's own `FxPanel` is the editor (R23: one panel, improved once).
- **On paint layers it bakes**, as one undo step ("apply this effect to the pixels under this shape") — a paint
  layer has no live shapes. The same gesture and the same panel; only the result is permanent.
- **Preview = export by shader identity** (R38): the effect runs as the same compiled GLSL on the GPU, on
  screen and in export. No per-effect CPU twin.
- **Feel:** select a line → the selection row offers `Fill` → `Solid | Gradient | Effect`; choosing Effect opens
  the FX panel on that shape. A soft edge is a per-shape feather slider (0 = crisp).
- **Distortions:** the Studio's registry today has blurs (gaussian, directional), pixelate, gradient map, colour
  grade, RGB shift, offset and others, but NO true distortion (ripple, twirl, bulge, displacement). Adding one
  is a Studio effect (`FxRegistry`), so it improves both apps at once. Request logged for D.05/JB-2.21 wave 2.
- Build order (all the Lead's, T1): 2.20b → 2.21 (filter layers, GPU export) → 2.23 (masks/clipping) → **JB-5.04
  effect shapes** (fill kinds, `effectId`, feather, the selection-row `Fill` action). JB-2.22b as written is
  superseded: its gradient-FILL half becomes the `gradient` fill kind of JB-5.04; its "capture a ramp from a
  region" half stays as a small optional action in the gradient editor.
- Constraint: nothing here may touch `StrokeCodec`/`StrokeRecord` until JB-5.03a (in review) is cleared; the
  extra field is added at JB-5.04's landing with the version bump (R30/R31).

**R42. Stylus buttons are USER-ASSIGNABLE, and the lasso is the S Pen button's default.** Owner: the S Pen
button is most useful as lasso, but Settings must let the user choose what every stylus button does; if a pen
has several buttons or a back-end eraser, those get slots too, whenever detected. Some legacy Wacoms work with
the e-pen, and off-brand USB pens can have more buttons. This AMENDS R38's S Pen rule (which hard-coded "button
never erases"): the default is now **primary button, held = lasso; tap = eyedropper**, and everything is
rebindable. Rules:
- **Slots appear by detection, not by a fixed list.** Android reports pen buttons as `buttonState` bits
  (`BUTTON_STYLUS_PRIMARY` 0x20, `BUTTON_STYLUS_SECONDARY` 0x40, and mouse-style `BUTTON_SECONDARY` 2, `TERTIARY`
  4, `BACK` 8, `FORWARD` 16 which off-brand and legacy-Wacom pens often use), as `TOOL_TYPE_ERASER` for a back-end
  eraser, and as key events for tablet "express keys" (any keycode). A button becomes a slot the first time it is
  seen (and settings has "Detect a button": press it now). Seen buttons are remembered across launches.
- **Two bindings per button:** *while held* (drawing with it down) and *on tap* (press and release without
  drawing). Held actions: LASSO, ERASE, PAN. Tap actions: EYEDROPPER, UNDO, REDO, TOGGLE_CHROME, BRUSH_PREV,
  BRUSH_NEXT. Not every button needs both. A lasso ALWAYS closes itself when the pen lifts (no "did it close a
  loop?" guessing) — that also removes the erase/select ambiguity R38 was worried about.
- **The tip's own contact bit (`BUTTON_PRIMARY`) is never bindable** — it is set by touching the screen, not by
  a button. Verify the raw bits on the Note 9 with the pen diagnostics panel (it prints `btn 0x..`).
- **Defaults:** primary = lasso (held) / eyedropper (tap); secondary = erase (held); back-end eraser = erase
  (held); every other detected button starts unassigned. Hover-clicks are not bindable (Samsung's Air Command owns
  them — blueprint §3.5): a button acts only while the pen is touching.
- Unknown buttons and unknown action names in saved settings are KEPT, never dropped (a newer build's setting
  survives an older build).
- Row **JB-2.02c** (core map + detector, T2, Ready) is written; the settings screen waits for chrome (2.01) and
  the routing in `JbCanvasView` is the Lead's (with JB-2.05, whose lasso is now "the LASSO action", not a
  hard-coded button). JB-2.02b's finger gesture table is unchanged.
