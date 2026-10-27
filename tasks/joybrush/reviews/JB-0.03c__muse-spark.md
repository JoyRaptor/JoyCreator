# Adversarial review — JB-0.03c The 0..1 hardness rule in `BrushValidate`

- Reviewer: muse-spark (cross-reviewer; builder was a follow-up agent finishing a dead agent's tests — different family).
- Task status: 🟧 Built. Commit reviewed: `5fd26419` (0.03c third of the commit).
- Spec reviewed: `tasks/joybrush/specs/JB-0.03c_hardness_rule.md` (contract, decisions 1–11, tests 1–6, Do-not, stop rule + Questions with xr rulings).
- §5b checks: diff touches only `brush/BrushValidate.kt` + `brush/BrushTest.kt` + `brush/imports/MypaintImportTest.kt` — inside the owner area; `MypaintImport.kt`, `BrushPreset.kt`, `BrushJson.kt`, `EnumFreezeTest.kt`, `DocModel.kt` untouched; no version bump. Suite run by me in a clean HEAD worktree: `BrushTest` 28/28 + `MypaintImportTest` 23/23, 0 failures (incl. `ShippedBrushFilesTest` in the full run — the shipped canary is green).
- Severity scale per §5b: BLOCKER / MAJOR / MINOR.

## Findings: none on the code. Verified instead — including the mutation runs the row owed

1. **Rule 24 as specified:** at the END after rule 23 (numbering preserved — rules 9–23 named across three files untouched); house message form `tip.hardness <value> is outside 0..1` written `!in` (NaN fails — the `<`/`>` trap explicitly avoided); `"tip.hardness"` added to `RANGED_BASES` (Infinity named once, not twice); base only (curve `y` still rule 19's business); refuse-never-clamp; no constants; no renumber; no bump (Decision 7's no-bump costings verified against the landed files — the fill-pen `version: 2` + rule-1b interaction is real, and `BrushPreset.version` is a reference, not a second literal).
2. **Non-vacuity proved by MY OWN two mutations** (the commit message records these as owed — I ran them in a disposable worktree): dropping `"tip.hardness"` from `RANGED_BASES` turns exactly 3 tests red (Infinity-twice, NaN-once, one-thing-per-rule); rewriting `!in 0f..1f` as `< 0f || > 1f` turns exactly the NaN tests red (NaN slips both comparisons). Both mutations revert clean. The row's pins hold the implementation, not the suite's goodwill.
3. **Edges and defaults:** 0 and 1 legal (fully soft/hard tips are brushes); NaN/Infinity named once by the range rule; `Param(0.9f)` default inside range (no newborn brush is broken); shipped `ink` 0.95 clean, `pencil`/`fill` defaulted clean.
4. **Importer rewrite endorsed (Decision 9):** the old test's "delete it then" KDoc is overruled correctly — the importer's warning still fires and is still right, the sweep now adds the refusal sentence, and the rewritten test pins all three (passthrough + warn + refused). `MypaintImport.kt` itself untouched, as ordered.

## Recommendation
No send-back. No BLOCKER, MAJOR, or MINOR open. The owed mutation evidence is now supplied above — the row's verification debt is closed.
