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
