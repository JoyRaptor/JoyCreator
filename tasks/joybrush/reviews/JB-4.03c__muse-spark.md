# Adversarial review — JB-4.03c Sprite sidecar `weights` (held frames survive export)

- Reviewer: muse-spark (cross-reviewer; builder was a follow-up agent finishing dead agents' work — different family).
- Task status: 🟧 Built. Commit reviewed: `5fd26419` (4.03c third of the commit).
- Spec reviewed: `tasks/joybrush/specs/JB-4.03c_sprite_weights.md` (contract with verbatim app quotes, decisions 1–10, tests 1–7, Do-not + xr verification against the Studio Java).
- §5b checks: diff touches only `export/SpritePacker.kt` + `export/SpritePackerTest.kt` — inside the owner area; `app/` untouched; signature unchanged; schema version untouched. Suite run by me in a clean HEAD worktree: `SpritePackerTest` 31/31, 0 failures; `SpriteGridMathTest` (JB-4.01a, cross-lane caller) green in the full run.
- Severity scale per §5b: BLOCKER / MAJOR / MINOR.

## Findings: none. Verified instead (including against the app, by reading — not on trust)

1. **App rule reproduced exactly:** `weights` last field defaulting `emptyList()` (R36 shape); write iff any weight ≠ 1 (`hasWeights`); placed inside the preset immediately after `frames`; length mismatch refused (never padded/truncated — `weightAt`'s tolerant read makes padding a silent timing change); 1..9999 refused (never clamped — `clampWeight` on read makes clamping silent); refusal messages in the packer's `"clip \"name\" …"` shape naming the numbers.
2. **Append-last was load-bearing and verified:** I grepped every `Clip(` construction repo-wide — all existing positionals end at `fps` (4th arg); the new defaulted 5th field changes no call's meaning. `AnimExportPlan.sheetClip` (3.06b) constructs fully named. `SpriteGridMathTest` packs with `clips = emptyList()`, insulated.
3. **The byte-identical promise is pinned as a string comparison**, not a key-list comparison (all-1s sidecar ≡ default sidecar) — the app's "carries no information" comment made executable.
4. **Refusal edges:** 0/10000/−1 refused; 1 and 9999 accepted; both length directions refused with name + counts in the message; determinism (pack-twice-equal) pinned.
5. **Untouched invariants hold:** `presetsCarryTheirFramesAndOmitAnInheritedFps` passes unchanged (sparse-write survives); root key order test unaffected (weights is preset-level, never root); `SPRITE_SIDECAR_SCHEMA_VERSION` stays 1 (the app already writes weights at schema 1 — no extension, no bump).
6. The spec's withdrawn Q3 is correctly withdrawn (all six keys verified root-level in the app writer — I confirm the correction, no row owed); the genuine blind spot (preset-level keys invisible to the clipless test) is named in the spec for the next presets-touching row rather than assumed closed.

## Recommendation
No send-back. No BLOCKER, MAJOR, or MINOR open. Re-review flag satisfied: the reviewed file changed, the change matches the format owner byte-for-byte in rule, placement, range, and refusal policy.
