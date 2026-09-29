# Adversarial review — JB-0.02 Document model, boards, layers, cels, file layout

- Reviewer: muse-spark (cross-reviewer, different family from builder space bunny agent #1).
- Task status: 🟧 Built. Commit reviewed: `2175cf1c` ("JB-0.02: document model").
- Spec reviewed: `tasks/joybrush/specs/JB-0.02_document_model.md` (incl. the five 2026-09-28 Lead rulings, which I verified are all implemented and tested).
- §5b checks: diff `2175cf1c` touches only `core/doc/*`, `DocModelTest.kt`, the ROADMAP row, and a Questions/Rulings appendix in the spec — inside the owner area. No pixels, no I/O, no Android imports in commonMain. Spec command `./gradlew -p joybrush :core:jvmTest` run 2026-09-28: DocModelTest 39/39, 0 failures. (Full run: 137 tests, 2 failures, both in `core.vector.*` = JB-5.10's uncommitted in-flight work, outside this area.)
- Severity scale: High = loses/corrupts work; Medium = wrong output or broken promise; Low = unspecified edge; Info = verified-good.

## Finding 1 (Medium): forward-compat promise breaks on unknown *enum values* — a newer document fails to open at all
Proof: `DocJson.kt:24-29` configures `ignoreUnknownKeys = true`, and the spec + kdoc (`DocJson.kt:15-17`, spec test 3) promise "a document written by a later Joy Brush still opens here". But `BoardKind`, `LayerKind`, `BlendMode` (`DocModel.kt:45,65-66`) are plain kotlinx enums, and kotlinx.serialization throws `SerializationException` on an unknown enum value (e.g. a future `"kind": "VIDEO"`), which `decode` converts to `DocException` (`DocJson.kt:56-63`). So a v2 file using a new board/layer kind never reaches `validate`'s friendly "newer Joy Brush" message — the user cannot open it, full stop. String-typed words (`engine/accumulate/blend` in JB-0.03) were done right; the doc enums were not. The fix space (unknown-enum fallback, `coerceInputValues`, or explicit mapping) belongs to the Lead; I did not touch it.

## Finding 2 (Low): `version < 1` is silently read as v1
Proof: `DocOps.kt:59-61` only rejects `version > DOC_VERSION`. `version: 0` or `-3` decodes (spec test 4 covers `version: 2` → validate error, but nothing covers 0/negative) and validates clean, read with v1 assumptions. Same gap class as JB-0.03 Q4 (also noted there). Reachability: hand-edited or corrupt files only. Cheap ruling: reject `version < 1` with words.

## Finding 3 (Low): empty-string ids pass validation
Proof: `validate` checks id *uniqueness* (`DocOps.kt:64-68`, rule 2) but never non-emptiness. A layer with `id: ""` validates clean (contrast JB-0.03, which rejects blank brush ids). Lookups by id still work, so this is cosmetic — but `""` ids in a hand-merged file will confuse every debug listing. One-line rule if the Lead wants it.

## Finding 4 (Info): `tiles` ordering is the same byte-stability hole `frameCel` had, left open
The `canonical()` sort (`DocJson.kt:41-49`) fixes map-order instability, and the ORCHESTRATOR_LOG leans on it ("a re-save of an unchanged document is byte-identical"). `Cel.tiles` is a *list*: two documents with the same tile set in different order encode to different bytes. Round-trip preserves order, so the "unchanged re-save" claim holds in practice; only a future normaliser/repair tool could break it. Not a defect today — flagging so nobody "fixes" it by sorting (order may one day carry meaning) or, conversely, assumes set semantics.

## Verified good (with proof, no action)
- All five Lead rulings hold in code *and* test: TILE_SIZE aliases the engine constant (`DocModel.kt:14`, drift test kept); rule 11 boards+layers (`DocOps.kt:170-172`); rule-7 converse frame→board (`:130-134`); textureScale NaN/≤0/>64 (`:165-168`, `!in` + explicit `isNaN`); `newDocument` requires room (`:25`); `frameCel` key sort (`DocJson.kt:41-49`, order-permutation test).
- NaN discipline: `fps !in 1f..60f` catches NaN (`:87`, comment says so); `opacity !in 0f..1f` catches NaN (`:158`); paper colour regex is exactly `#RRGGBB` (`:15,:160`).
- `celFor` returns null (never throws) for broken static layers and unknown frames (`:178-182`); decode-never-validates is deliberate, documented (`DocJson.kt:51-55` + spec amendment), so JB-0.08 must call both — recording that here as the handoff, not a defect.

## Recommendation
Do not send back — contract implemented, 39/39 green, owner area clean. Recommend the Lead rule on Finding 1 (the only one that can lock a user out of their own newer file) before JB-0.08, and batch Findings 2–3 as one-line validation amendments with the JB-0.03 Q4 ruling.
