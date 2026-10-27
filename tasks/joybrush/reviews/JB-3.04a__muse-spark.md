# JB-3.04a — different-family cross-review (muse)

- Reviewer: `muse-spark-1.3-contributor-free`, family Muse Spark (Meta) — different family
  from writer `openrouter/stealth/space-bunny-alpha`.
- Method: read-only. No gradle, no edits.
- Dispatch note (my error, owned): the board row claimed "the dead link is LIVE". It is not —
  verify file existence before dispatching spec reviews.

**Verdict: SEND-BACK — 3 BLOCKERs. There is no spec to approve and the R34 gate is unsatisfied.**

## BLOCKERs

**B1 — the spec file does not exist.** `glob **/JB-3.04a*` → no files; only
`specs/JB-3.04_onion_skin_shared.md` exists. The board link
`[JB-3.04a](specs/JB-3.04a_onion_core.md)` is dead and the row text claiming otherwise is
wrong. No contract, Decisions, predicate, tests, numbers, citations, stop rule, or Do-not to check.

**B2 — R34 gate unsatisfied: no `OnionMath` extraction, no golden possible.** R34 order
(`LEAD_RULINGS.md:360-366`): D.02 → extract `OnionMath` (pure Java, no Android) into the kit
with SpriteLab calling it → generate the golden by running it → then this row. Tree state:
`:studiokit` exists but holds no onion file (`fx/ keyframe/ model/ Studio.java tools/
transform/` only); `grep OnionMath` tree-wide → zero source hits; the maths is still
`alphaFor` (`app/.../sprite/SpriteSheetEditorActivity.java:4530`, `255*strength*0.62^…`,
clamp 12..255) inside private `CellCyclePreview`, fields still in the Activity. `core/anim/`
holds only FilmStrip/PlaybackClock/PaperGeometry/FrameStepper. Any 3.04a draft claiming a
generated golden today is unbuildable. No Python transcription (per R34).

**B3 — alleged prior xr fixes unverifiable.** The board claims same-family xr found 2 BLOCKERs
(budget predicate; un-premultiply divisor) and fixed them (cumulative budget, 26 tests,
240.7199, ~60 citations). With no spec file nothing was re-derived or verified — board hearsay
until the file lands and a different-family reader checks it. (Writer + xr both
`space-bunny-alpha`; an independent read remains owed.)

## To make this reviewable

Land `specs/JB-3.04a_onion_core.md` **after** the R34 gate (extract → generate golden → write
spec against it), then re-dispatch with: written budget predicate + mutually-satisfiable tests,
un-premultiply by alpha with ≥2 non-uniform-alpha tests, derived numbers, citable lines.
References checked without contradiction (uncheckable without the spec): blueprint §1-idea-10c,
OWNER_CONSTRAINTS (iOS door), R34, R23 (Q2 pins 0.62/floor-12/MAX-6; Q3a), parent
`JB-3.04_onion_skin_shared.md` (itself 🟨 Draft, Q1–Q3 open), `D.02_studiokit_module.md`.
