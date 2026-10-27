# Adversarial review — JB-0.02d `DocJson` refuses unknown keys (R31)

- Reviewer: muse-spark (cross-reviewer; builder was a follow-up agent finishing a dead agent's walk — different family).
- Task status: 🟧 Built. Commit reviewed: `5fd26419` (0.02d third of the commit; 4.03c/0.03c reviewed separately).
- Spec reviewed: `tasks/joybrush/specs/JB-0.02d_refuse_unknown_keys.md` (contract, decisions 1–10, tests 1–10, stop rule, Do-not + Q0 ruling (a) + xr notes).
- §5b checks: diff touches only `doc/DocJson.kt` + `doc/DocModelTest.kt` — inside the owner area; `DocModel`, `DocOps`, `JbArchive`, `EnumFreezeTest`, `androidkit` untouched; no version bump; `BrushJson` untouched. Suite run by me in a clean HEAD worktree: `DocModelTest` 48/48, 0 failures (incl. all 10 new tests + `aNewerVersionStillDecodesAndIsReportedInWords` + `unreadableJsonThrowsDocException` still green).
- Severity scale per §5b: BLOCKER / MAJOR / MINOR.

## Finding 1 (MINOR): absent / non-integer `version` is SCANNED, but Decision 2's text and the KDoc both say it falls through
Proof: spec Decision 2 — "if it is absent, not an integer, or `> DOC_VERSION`, fall through to today's behaviour untouched"; KDoc — "A `version` that is absent or is not an integer falls through with it". Code (`DocJson.kt`): `val version = (obj["version"] as? JsonPrimitive)?.intOrNull; if (version != null && version > DOC_VERSION) return` — null (absent, string, `9999999999` overflow via `intOrNull`) does NOT return, so the walk runs. A versionless file (or `"version": "2"`) carrying an unknown key is refused, where the spec sentence promises today's behaviour. The behaviour is arguably the safer one (a versionless file is not "from the future", and scanning it closes rather than opens), but the KDoc asserts the opposite of what the code does — the exact failure mode this repo files findings about. One-line gate change or two-sentence spec/KDoc change; Lead's call. No test covers the absent/non-int case either way.

## Verified (proof — the walk was re-derived, not just test-counted)
- Exhaustiveness: CLASS recurses per key via `getElementIndex` (descriptor-derived, no hand table to drift); LIST per index; MAP descends VALUES only (frameCel's data keys never refused — the trap test 6 names); `else` refuses a `JsonObject` at a shape with no vocabulary (fail-closed, which is the property Q0-(a) needs since `ignoreUnknownKeys` stays `true`).
- Placement inside the existing `try` (non-JSON input still leaves as `DocException` — `JbArchive` catches only that) and non-`JsonObject` roots fall through to the typed decode untouched (both arms of test 10 hold by construction, not just by test).
- `version > DOC_VERSION` (integer) skips the scan, so test 7's newer-file-with-unknown-key still reaches `DocOps.validate` — the R3 sentence survives, as ruled.
- Message shape per Decision 6 with key + path, joined `"; "` (Decision 5); KDoc rewritten as Decision 9 orders (both stale sentences gone, same-version/newer-version split stated).
- Drift guard test 6 is written against the FILE with the frameCel exclusion spelled out; `unknownKeysAreIgnoredAtEveryLevel` deleted with a tombstone comment (not renamed); v1 fixture copied inline (no cross-file access); `encode` untouched; `DOC_VERSION` untouched.
- Cost: one extra tree parse per decode (scan needs the tree) — acceptable, unremarked in spec; noting for the record.
- Nullable descriptors (`grid?`, `textureId?`): reached and walked (deep tests green) — the wrapper-kind hazard the KDoc names does not fire on the pinned serialization version.

## Recommendation
No send-back. One MINOR (gate-vs-text mismatch on absent/non-integer versions). The Q0-(a) ruling + this implementation is the cheapest correct shape — the scan-is-the-whole-guard property holds by construction and by mutation (per the commit message's LIST-branch evidence).
