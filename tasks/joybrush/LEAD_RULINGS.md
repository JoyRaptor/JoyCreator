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
