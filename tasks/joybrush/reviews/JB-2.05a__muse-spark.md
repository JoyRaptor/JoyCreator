# Adversarial review — JB-2.05a Selection masks (lasso, rect, ellipse, from-fill, boolean ops)

- Reviewer: muse-spark (cross-reviewer; builder was an unrelated subagent — different family).
- Task status: 🟧 Built. Commit reviewed: `22ac5ffa` (selection half; guide half is JB-2.12a, reviewed separately).
- Spec reviewed: `tasks/joybrush/specs/JB-2.05a_selection_mask.md` (contract, decisions 1–7, tests 1–10).
- §5b checks: diff touches only NEW `select/SelectionMask.kt`, NEW `select/Lasso.kt`, NEW `SelectionMaskTest.kt`, board row — inside the owner area; no existing file touched per "Do not". Suite run by me in a clean HEAD worktree: `SelectionMaskTest` 13/13, 0 failures (full `:core:jvmTest` 584/3, the 3 failures being JB-3.08a's two documented WIP reds + JB-5.10's known flaky timing bound — none here).
- Severity scale per §5b: BLOCKER / MAJOR / MINOR.

## Finding 1 (BLOCKER): `intersect` keeps `this`-only tiles — disjoint masks in different tiles intersect to the first mask
Proof: `SelectionMask.kt:139-158` — `out` starts as a copy of ALL `this.tiles` (`:142`), and the loop over `o.tiles` skips missing keys (`:145` `?: continue`). A tile present in `this` but absent in `o` (true value `a·0 = 0`) is left unchanged. Breaking input: `rect(RectPx(0,0,10,10)).intersect(rect(RectPx(300,0,10,10)))` (tile x 0 vs 1) returns the first square instead of `EMPTY`; also breaks commutativity. Hidden because the spec's op tests use overlapping squares sharing tile (0,0), same-tile disjoint works per-pixel (`:150-152`), and `intersect(EMPTY)` takes the early return (`:140`). Fix: drop `this`-only keys (start from empty / retain only intersection keys). Note `subtract` (`:116-133`) has the same loop shape but is CORRECT there (missing key ⇒ subtract 0 ⇒ keep) — the pattern was copied into a function with different identity semantics.

## Finding 2 (MAJOR): public `tiles` leaks mutability, breaking "Immutable" and the FULL-sharing invisibility
Proof: `val tiles` is public on `SelectionMask.kt:29`, exposing the `ByteArray`s — including the shared read-only `FULL_TILE` (`:303`, shared by `finalise` at `:339`). Any holder can do `m.tiles[k][i] = …`; through a shared `FULL_TILE` that corrupts every mask sharing it. Internal ops are safe (`writable` copies on `=== FULL_TILE`, `:317-320`), but the class's own contract (`:26-27` "stored sparsely… Immutable") is unenforceable, and the test suite itself reaches through `m.tiles` directly. Fix is a type-level one (private tiles + accessor, or defensive copy) — new API, Lead call.

## Finding 3 (MINOR): `ellipse` contradicts its own doc on negative radii
Proof: doc at `SelectionMask.kt:225` promises zero/negative radii come back `EMPTY`, but `ellipse` (`:227-238`) has no `rx/ry` guard — `rx=-50` just phase-shifts the 360-gon and fills non-empty. (NaN/Inf are accidentally EMPTY via `Lasso.kt:58`; negative finite is not.) Spec-conformant (spec §7 only mandates polygon garbage), code-contract violation — fix the doc or add the one-line guard.

## Finding 4 (MINOR): `invert`/`rect`/rasteriser overflow near `Int.MAX_VALUE`
Proof: `yEnd = within.y + within.h` (`SelectionMask.kt:171-172`) wraps — e.g. `RectPx(Int.MAX_VALUE-5, 0, 10, 10)` yields an empty `until` and `EMPTY` instead of a 5-px strip. Same family in `fromMask` origin arithmetic (`:258,264`) and `Lasso.writeRow` (`Lasso.kt:270`). Unreachable in any real document; hardening gap only (`fromMask`'s `row*w+col` is safe in practice — overflow implies `w*h > Int.MAX`, which `require` at `:253` throws on first).

## Verified (proof)
- All six spec test groups map (square 100-px/neighbours/bounds; negative-origin tile seam; triangle edge band with the 6/4/6 derivation in comments matching the `(inside·255+8)/16` formula; figure-8 + double-loop proving non-zero; seam-shift byte equality; op counts + `subtract(a).isEmpty`/`intersect(EMPTY)`/`add(EMPTY)`; `fromMask` at (−7,300); ellipse area ±1%; garbage + 16384/16385 cap; 3000-pt/2048² perf).
- Winding/fill verified by reading, not just tests: `down?+1:-1` directions, fill iff `winding != 0`, half-open `yLow ≤ sy < yHigh`, horizontals dropped, zero-length coincident spans — the figure-8 junction case is structurally sound.
- `subtract`/`intersect` rounding `(…+127)/255` is provably exact `round()` (a `.5` quotient would need a non-integer numerator — impossible); `rect` = 4-corner polygon, `ellipse` = 360-gon, `fromMask` keeps soft bytes with `require(size >= w*h)`.
- `add` returning `this`/`o` and `subtract` returning `this` on empty operands is identity-leak, not a bug (arrays frozen; a test even requires `===`).

## Recommendation
Fix Finding 1 (wrong-result, one-line) and rule on Finding 2 (API-level immutability) before transform/selection UI builds on these ops. 3–4 are MINOR.

## Addendum 2026-09-29 — BLOCKER + MAJOR fixed (`6a9630fa`), verified
- Finding 1 fixed as prescribed: `intersect` now iterates the other mask's keys (`tiles[entry.key] ?: continue`), copying only visited tiles — `this`-only tiles are dropped. The `subtract` twin was deliberately left alone (correct: `a − 0 = a`) and pinned with a dedicated test so a future "symmetry" fix cannot delete 2900 px of selection.
- Finding 2 fixed as a public API change: `tiles` is now `internal`, crossed only via `coverage`/`bounds`/`tileKeys` and a copying `tile()` (64 KB/call, documented as the price of the promise). Recorded in the spec as a Question since JB-2.07a/2.05b consume it — Lead's call on the copy cost, not mine to re-litigate.
- Fresh run in a clean HEAD worktree: `SelectionMaskTest` 19/19 (13 old + 6 new pins), 0 failures. Findings 1–2 closed; 3–4 (MINOR) stand.
