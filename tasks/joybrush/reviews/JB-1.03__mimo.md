# Adversarial review — JB-1.03 procedural tileable cloud grain texture

- Reviewer: mimo (second adversarial pass; muse-spark filed `JB-1.03__muse-spark.md`).
- Task status: 🟧 Built. Commit reviewed: `3fea7770` (tree at `b74aaf0e`).
- Spec: `tasks/joybrush/specs/JB-1.03_cloud_texture.md` (contract `:17-28`, Decisions 1–5, Tests 1–5, empty Questions section).
- Suite: `./gradlew -p joybrush :core:jvmTest --rerun-tasks` at `b74aaf0e` → BUILD SUCCESSFUL, 280 tests / 0 failures (CloudNoiseTest 7, WriteGrainAssets 1 — the writer ALWAYS overwrites, so the committed PNGs are proven regenerated from this generator in the same run).
- §5b checks: commit touches only `grain/CloudNoise.kt`, `grain/CloudNoiseTest.kt`, `jvmTest/.../WriteGrainAssets.kt`, `assets/grain/*.png` (all three files present), board row — owner area as declared. No `java.*` in commonMain; no `kotlin.random.Random` (grep: only the SplitMix64 hash) — Do-not list holds.
- Severity: BLOCKER / MAJOR / MINOR per ROADMAP §5b.

**Verdict: no BLOCKER, no MAJOR. Muse's F1 (flat-field max) is the one real contract deviation —
reproduced and re-examined below; muse's F2 dismissal re-confirmed. Decisions 1–5 and Tests 1–5
walked line-by-line; determinism/tileability story is airtight.**

## Status of muse-spark's findings at this commit (re-verified by me)

- **F1 flat-field input violates "min = 0 AND max = 1" (muse: Low)** — **CONFIRMED, still open.**
  See F1 below for my severity reasoning.
- **F2 `persistence` outside (0,1] cannot leak NaN into shipped assets (muse: Info, dismissed)** —
  **I re-checked the two hostile cases and agree**: NaN `persistence` → NaN field → `lo/hi` untouched
  by comparisons → `range ≤ 0` → `fill(0f)` (safe); `+Inf` persistence → `range = +Inf` → NaN cells,
  but the only consumer, `WriteGrainAssets.grey`, quantises via
  `(h*255).roundToInt().coerceIn(0,255)` (NaN→0) so the pipeline cannot crash, and no caller passes
  such a value (assets pass defaults / the one `cloud_fine` override from Decision 5).
  Recorded closed, not missed.

## F1 (MINOR, but by the letter of §5b it is a spec-named edge case — triage to judge): a constant field is normalised to all-zero, so max = 0, not the contract's max = 1

- **Proof (spec):** the contract KDoc promises unconditionally: "Result normalised so **min = 0 and
  max = 1**" (`JB-1.03:24`, verbatim in `:22` too), and Test 2 states "min == 0, max == 1 exactly
  (after normalisation)" (`JB-1.03:45`) — but Test 2 exercises only real seeds/default params, never
  the flat case.
- **Proof (code):** `CloudNoise.kt:72-86` — normalisation computes `lo`/`hi` over the field and,
  when `range == hi - lo == 0f` (every lattice value identical), takes the `fill(0f)` branch: min = 0
  ✓, **max = 0 ✗** vs contract.
- **Proof (reachable):** `generate(size = 1, seed, octaves = 1, baseCells = 1)` (likewise
  `octaves=1, baseCells=1, size=k` for any k): Decision 4's validation
  (`size % (baseCells * 2^(octaves-1)) == 0`, `CloudNoise.kt:29-35`) requires a multiple of 1 —
  every size passes — and with one cell per axis every lattice point is `(0,0)` per octave, so each
  octave is constant, the weighted sum is constant, `range = 0`.
- **Reachability in the product:** zero — no caller (`WriteGrainAssets`, Decision 5) generates with
  `octaves=1/baseCells=1`; `fill(0f)` is also the *safe* value (flat zero grain = no paint gating
  surprises, never NaN).
- **Why not MAJOR despite the §5b wording:** the deviation is reachable only through the public
  API with params no shipped caller uses, and the emitted value is the safe one; but the contract
  sentence is unconditional, so the honest options are (a) Lead rules it a one-line spec amendment
  ("a constant field normalises to all-zero") — my recommendation — or (b) a two-line code fix
  (`fill(1f)`? no — filling `0f` keeps min=0; to satisfy both you'd need `if (range == 0f) fill(0f)
  and the spec says so`). **Orchestrator: pick (a); do not "fix" by filling 1f — that breaks
  "min = 0".** Note this is the same class of item as JB-5.10's two spec-ambiguities: a sentence to
  amend, not a behaviour to change.

## Verified sound (clause walk: contract + Decisions 1–5 + Tests 1–5)

- **Decision 1 (periodic lattice):** `mod cells` on both axes at `CloudNoise.kt:51,57`, per-axis
  precomputed `i0/i1/fx` — periodicity by construction; wrap tested incl. the float-ULP-hostile
  odd-lattice case (`oddLatticesStillTile`, 3 cells / 96 px) and Test 3's "seam ≤ largest interior
  neighbour difference" sweep of every row and column (`JB-1.03:46-48`).
- **Decision 2 (hash):** `CloudNoise.kt:96-110` is the spec's SplitMix64 expression bit-for-bit
  (`seed xor (octave * 0x9E3779B97F4A7C15) xor (i * 0xBF58476D1CE4E5B9) xor (j * 0x94D049BB133111EB)`,
  top 24 bits / 2²⁴). `ULong`/`Long` xor-mul-shr are bit-identical; `shr 40` on ULong is the logical
  shift "top 24 bits" requires; pure integer arithmetic → identical JVM/ART output; no
  `kotlin.random.Random` anywhere (Do-not holds).
- **Decision 3 (smoothstep bilinear):** `t*t*(3-2t)` weight, tested by the determinism + range +
  mean tests (Tests 1, 2, 5 all green: same-args identical, seeds differ, min==0/max==1 exactly
  float-equal for real seeds, mean in 0.35–0.65 for seeds 1..5).
- **Decision 4 (size rule):** throws `IllegalArgumentException` for 0 / non-multiples /
  `octaves=0` / `baseCells=0` (`:29-35`, `badSizesAreRefused`); `baseCells shl (octaves-1)`
  computed in **Long** (`:32`) so `octaves ≤ 30` cannot overflow, and `size % finest` uses Long
  (`:33`) — I traced `octaves=30, baseCells=4` → finest = 2³¹ > Int.MAX → every Int size refused
  (loud, not a wrong pass).
- **Decision 5 (assets):** `WriteGrainAssets` ALWAYS overwrites, asserts square/single-channel/
  non-empty, spot-checks quantised pixels round-trip, resolves the repo path by walking up to the
  folder named `joybrush` containing `settings.gradle.kts`; all three PNGs exist
  (`cloud_256.png`, `cloud_512.png`, `cloud_fine_256.png`) and the test was green in this
  session's 280-test run — i.e. the committed bytes are fresh from this generator.
- **The property JB-1.02 leans on:** 128-vs-2×-averaged-256 correlation > 0.98 (same field, finer
  sampling) — verified by muse and consistent with my JB-1.02 pass (paper-space sampling).

## Bottom line

No send-back. F1 is a one-sentence spec amendment for the Lead (recommended wording: "a constant
field normalises to all-zero — min and max are both 0"), with a matching line added to Test 2's
KDoc so the gap is deliberate; F2 stays closed. Otherwise the cleanest Built in this review round
alongside JB-0.04.
