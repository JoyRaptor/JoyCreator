# Adversarial review — JB-1.05b Brush files drive the drawing view (+ picker pill)

- Reviewer: muse-spark (cross-reviewer; builder was an unrelated subagent — different family).
- Task status: 🟧 Built. Commit reviewed: `424e559e`.
- Spec reviewed: `tasks/joybrush/specs/JB-1.05b_brushes_in_the_view.md` (decisions 1–5, verification, Do-not + builder Questions 1–7).
- §5b checks: diff touches the exact owner-area list (build-resources edit, NEW `BrushLibrary.kt`, `JbCanvasView` stroke-start path, activity pill, `brushes/index.txt`, board row, spec questions) — no engine/shader/placer/dabber/scatter changes per "Do not". Verification available to me: `:androidkit:compileKotlin` green inside my `:androidkit:test` run (BUILD SUCCESSFUL); screenshots are owner's (no adb here). Reviewed against committed code (the working tree holds uncommitted 0.08b work in the same files — out of scope).
- Severity scale per §5b: BLOCKER / MAJOR / MINOR.

## Finding 1 (MAJOR): the spec-mandated seed makes file-drawn strokes unreplayable — collides with the blueprint
Proof:
1. Spec decision 3 mandates `seed = SystemClock.uptimeMillis()`; implemented at committed `JbCanvasView.kt:301`.
2. The seed is never stored, never exposed, never reaches `StrokeRecord.seed` (JB-0.04's codec carries a seed precisely so "a replay draws the same marks" — unwired here), and scatter's salt derives from it (`SCATTER_SALT` xor, `:304`).
3. The blueprint (and `BrushDabber`'s determinism contract, and `Scatter`'s "pure function of (dabs, spec, seed)") promise strokes are recordings re-renderable at any zoom / replayable for timelapse / re-brushable. A preset stroke saved and reopened (JB-0.08b, whose save path snapshots pixels, not strokes) can never reproduce its jitter/scatter — and worse, any future stroke-record path that replays with a *different* seed draws a visibly different painting while claiming to be the same stroke.
This is the builder's Who-note Q2, elevated with proof from "question" to "spec contradicts blueprint": the spec text itself orders the collision. Needs a Lead ruling (seed into the stroke record vs deterministic per-stroke seed), not a code tweak here. Filed MAJOR because it silently breaks a load-bearing blueprint promise on every preset stroke from day one.

## Process note (no severity — for the orchestrator, not the code)
- Claimed with unmet Needs: the row lists JB-1.05a in Needs while 1.05a's row still says 🟦 Ready (its code is committed as `9c7cc9a6` but the row was never flipped). §2 step 3 gates on rows, not trees. Either flip 1.05a (my annex review covers its code) or slate it; a Built-on-Ready should not stand silently.

## Verified (proof)
- Decision 1 (brushes as resources): `processResources from(brushes)→joybrush/brushes` per the board note; `index.txt` exists with `ink`+`pencil` (verified on disk).
- Decision 2 (`BrushLibrary.builtIn`): decodes with the real `BrushJson`, VALIDATES with `BrushValidate`, skips+logs failures (`BrushLibrary.kt:64-78`) — this is the load-path enforcement my JB-0.03b Finding 1 asked for, present on the shipped path. Graceful empty (pill "Brush", hard-coded fallback) covers the APK-packaging doubt (spec Q6) without needing a device to prove the happy path.
- Decision 3 (view wiring): dabber-per-stroke + placer `look` + per-batch `Scatter.expand` with the salted second generator + pressure-only `dabInputsOf` (all-NaN rest, documented why: curves skip to base rather than invent numbers) + `preset.smoothing` unless `smoothingFromUser` (flag set only on `fromUser` slider moves — the 35% default provably doesn't count) + `beginStroke` from file colour/opacity/accumulate/blend/`TipShape(strokeHardness)` with first-dab-before-begin ordering. `queueEvent` FIFO preserves begin→dabs→end order; a dab-less stroke posts a harmless `endStroke` → 0 (spec Q4's behaviour change, confirmed + unchanged hard-coded path).
- In-flight-stroke preset isolation: `startStroke` snapshots `preset` into `strokePreset` (mid-stroke pill taps can't mix files/seeds in one stroke), and the pill wraps with label+tooltip kept in sync.
- Decision 5 (no grain yet): no grain calls anywhere in the path — confirmed absent, not merely untested.

## Explicitly not filed (builder's Questions, endorsed as asked)
- Q1 (salted-second-generator vs `Scatter`'s same-generator note): both texts read as claimed; one must change — Lead call, with my 1.05a annex confirming the other side.
- Q5 (file's `size.base` unscaled on screen): confirmed — `Brush.sizePx`/`minSizeFraction` ignored on the preset path, no control exists yet. JB-2.x decision, correctly flagged.
- Q3/Q4/Q6/Q7 above as verified.

## Recommendation
Rule on Finding 1 before preset strokes accumulate into saves users expect to reopen identically (it lands squarely on JB-0.08b's design). Process note needs a board edit, not code.
