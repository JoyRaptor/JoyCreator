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
