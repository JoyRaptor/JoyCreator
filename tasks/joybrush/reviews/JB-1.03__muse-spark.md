# Adversarial review — JB-1.03 Procedural tileable cloud grain texture (deterministic)

- Reviewer: muse-spark (cross-reviewer, different family from builder space bunny agent #4).
- Task status: 🟧 Built. Commit reviewed: `3fea7770` ("JB-1.03: cloud grain texture").
- Spec reviewed: `tasks/joybrush/specs/JB-1.03_cloud_texture.md`, all 5 test clauses + 5 decisions.
- §5b checks: diff `3fea7770` touches only `core/grain/CloudNoise.kt`, `CloudNoiseTest.kt`, `jvmTest/.../WriteGrainAssets.kt`, `assets/grain/*.png` (all three present), and the ROADMAP row — inside the owner area. No `java.*` in commonMain; no `kotlin.random`. Spec command `./gradlew -p joybrush :core:jvmTest` run 2026-09-28: CloudNoiseTest 7/7 + WriteGrainAssets 1/1, 0 failures (incl. always-overwrite asset regeneration, so the committed PNGs are proven fresh from this generator).
- Severity scale: High/Medium = broken promise; Low = degenerate-params edge; Info = verified-good.

## Finding 1 (Low): flat-field input violates the "min = 0 AND max = 1" contract (reachable, degenerate only)
Proof: `CloudNoise.kt:72-86` — if `range == 0` (every lattice value identical), the field is filled with `0f`: min = 0 ✓, max = 0 ✗ (contract: "Result normalised so min = 0 and max = 1", spec Decision implied + test 2 asserts both for real seeds). Reachable: `generate(1, seed, octaves = 1, baseCells = 1)` — size 1 is a multiple of finest 1, passes validation (`:29-35`), single lattice cell → constant field → `fill(0f)`. No caller uses such params (assets use defaults/16-cell fine), `fill(0)` is the *safe* choice (flat-zero grain = no paint-gating surprises, never NaN), and test 2's seeds never go flat. One-line spec touch if the Lead wants it ("a constant field normalises to all-zero"), otherwise close as intended behaviour.

## Finding 2 (Info, checked and dismissed): `persistence` outside (0,1] cannot leak NaN into the shipped assets
I probed the hostile-param path: `persistence = NaN` → `amplitude` NaN → whole field NaN → `lo` stays `+MAX`, `hi` stays `−MAX` (all comparisons false, `:74-77`) → `range = -Inf`, not `> 0` → `fill(0f)` (`:82-84`): safe by accident. `persistence = +Inf` → `hi = +Inf`, `range = +Inf` → `(v-lo)/range` = NaN for finite v → array *can* hold NaN — but `WriteGrainAssets.grey` (`WriteGrainAssets.kt:54`, `(h*255).roundToInt().coerceIn(0,255)`) maps NaN→0, ±Inf→clamped, so the writer cannot crash; it would bake wrong pixels. Unreachable (writer passes the default; no validation rule ranges `persistence`, but no UI exposes it either). No action; recorded so the finding is visibly closed, not missed.

## Verified good (with proof — determinism story is airtight)
- Hash matches the spec bit-for-bit: SplitMix64 of `seed xor octave·0x9E3779B97F4A7C15 xor i·0xBF58476D1CE4E5B9 xor j·0x94D049BB133111EB`, top 24 bits / 2²⁴ (`CloudNoise.kt:96-110` vs spec Decision 2). ULong-vs-Long is bit-identical for xor/mul/shr-logical; `shr 40` on ULong is the logical shift the spec's "top 24 bits" requires. Pure integer math → identical on JVM/ART/JS: no `kotlin.random`, no floats in the hash.
- Tileability by construction (`mod cells` lattice, `:51,:57`; per-axis precomputed `i0/i1/fx`), *proven* including the float-ULP-hostile odd lattice (`oddLatticesStillTile`, 3 cells/96 px) and the "seam ≤ interior" sweep of every row+column.
- Range exactly `min == 0, max == 1` for real seeds (test `heightsRunFromZeroToOne`, float-exact by construction `(v-lo)/range`); all finite; mean in 0.35–0.65 for seeds 1–5 (not lopsided); 128-vs-2×-averaged-256 correlation > 0.98 (same field, finer sampling — the property JB-1.02's paper-space sampling relies on).
- Bad sizes refused incl. 0, non-multiples, `octaves/baseCells = 0` (`badSizesAreRefused`); `(baseCells shl (octaves−1))` computed in Long so `octaves ≤ 30` cannot overflow (`:32`); `size % finest` uses Long (`:33`).
- Asset pipeline self-healing: writer always overwrites + asserts square/single-channel/non-empty + spot-checks quantised pixels round-trip (`WriteGrainAssets.kt:18-52`), and it ran green in this session's suite run.

## Recommendation
No send-back. Cleanest Built in the round alongside JB-0.04. Finding 1 is a one-sentence spec clarification at most; Finding 2 is closed by inspection.
