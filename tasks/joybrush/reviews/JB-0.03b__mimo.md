# Adversarial review — JB-0.03b brush validation hardening

- Reviewer: mimo (second adversarial pass; muse-spark did not review this task — this is its first review).
- Task status: 🟧 Built. Commit reviewed: `72845224` (tree at `b74aaf0e`).
- Spec: `tasks/joybrush/specs/JB-0.03b_brush_validation_hardening.md` (Lead rulings 1–6 + builder Questions 1–6).
- Suite: `./gradlew -p joybrush :core:jvmTest --rerun-tasks` at `b74aaf0e` → BUILD SUCCESSFUL, 280 tests / 0 failures (BrushTest 16 incl. the new rule tests; ShippedBrushFilesTest 1).
- §5b checks: commit `72845224` touches `BrushValidate.kt`, `BrushTest.kt`, NEW `jvmTest/.../ShippedBrushFilesTest.kt`, appended rulings in `JB-0.03` spec — owner area as declared. No edits by this reviewer.
- Severity: BLOCKER / MAJOR / MINOR per ROADMAP §5b.

**Verdict: no BLOCKER, no MAJOR. The six rulings are fully implemented and each has a failing-case
test; two comment-level overclaims in the new shipped-files test are the only findings of my own.
Six builder Questions are disclosed gaps outside the owner area — I assess their severities below
and endorse two of them as load-bearing for the tasks that follow.**

## Rulings 1–6 walked against the code (all present)

| Ruling | Code | Test |
|---|---|---|
| 1. caps: 8 inputs / 64 points | `BrushValidate.kt:27,30`, `:154`, `:158` | `aFileCannotCarryUnlimitedCurve` (`BrushTest.kt:524`) |
| 2. curve x in 0..1 + finite y; source word sets | `:188`, `:191`, `:20-21`, `:213-219` | `aCurvePointIsAPairOfNumbersInZeroToOne` (`:549`), `aSourceIsAWordThisBuildKnows` (`:568`) |
| 3. every number finite + the range table (`!in` idiom) | `:143` (bases), `:73-130` (spacing→color, incl. `smoothing` `:86`, `minPx` `:83`, `sizeJitter` `:89`, `angleJitter` `:90`, scatter `:93-96`, grain `:107-112`, color `:128-130`) | `everyRangedNumberIsChecked` (`:438`), `aNaNInARangedNumberIsAProblemNotAPass` (`:364`) |
| 4. `version < 1` error | `:52-53` | `aVersionFromNoOneIsRefused` (`:429`) |
| 5. shipped files read from disk, validated | `ShippedBrushFilesTest.kt:22-50` (walks `joybrush/brushes/*/brush.json`) | itself, green |
| 6. once-per-dab ask (Lead's own engine work) | `DabPlacer` / `DabLook` (outside this owner area, `061fd2b5`) | `theBrushIsAskedExactlyOncePerDabInOrder` (PaintTest) |

Also verified: the file header's own hazard note (`BrushValidate.kt:7-12` — `!(v > 0f)` alone lets
+Infinity through, "which is how a `1e999` size once reached the dab loop") is now applied
everywhere an exclusive bound is used (`:66`, `:107`), not just documented.

## F1 (MINOR — test comment overclaims what the test does): "a file edited on disk drifts from its copy silently. This is the test that notices" — the test never compares disk bytes with `BrushTest`'s inline copies

- **Proof:** `ShippedBrushFilesTest.kt:11-13` makes the promise; the whole test (`:22-50`) decodes
  the disk file, validates it, and asserts a *value* round-trip (`:41`) — it never reads `BrushTest`'s
  inline text and never compares bytes. A disk edit that still validates (renamed field with a
  default, changed `spacing`, deleted optional key) drifts from the inline copy **and stays green**.
- The header's next sentence ("the canary for a new rule in BrushValidate", `:16-17`) IS true —
  validate-breaking drift is caught. So the fix is one word in the comment ("notices" → "notices
  refusals"), or a real byte-comparison against `BrushTest`'s copies (needs the copies exposed —
  probably a jvmTest reading `commonTest`'s resource, an owner-area question for the Lead).

## F2 (MINOR — test comment promises byte-stability a first save cannot deliver): "What ships is what the app writes back out, so a save cannot quietly change a brush" — the assertion is value equality, and the first save WILL change the file's bytes

- **Proof (comment + assertion):** `ShippedBrushFilesTest.kt:40-41` — the assertion is
  `assertEquals(preset, BrushJson.decode(BrushJson.encode(preset)))`: value round-trip through the
  codec, **not** equality with the file on disk.
- **Proof (why bytes change):** `BrushJson.kt:20-24` sets `prettyPrint = true` and
  `encodeDefaults = true` but does **not** set `prettyPrintIndent` (`DocJson.kt:26` does set `"  "`;
  `BrushJson` does not), so a save re-lays-out with the library default indent and writes *every*
  defaulted key. The shipped file proves the gap: `joybrush/brushes/ink/brush.json` is 22 lines,
  2-space, compact inline arrays (`"inputs": [ { "input": … } ]`), **14 top-level keys** —
  `format, version, id, name, engine, tip, size, opacity, flow, spacing, accumulate, blend, smoothing, license`
  (verified by reading it) — while `BrushPreset` carries many more fields with defaults
  (`tipTexture`, `paperGrain`, `scatter`, `sizeJitter`, `angleJitter`, `color`, …).
  First save ⇒ extra keys + different layout ⇒ the app has "quietly changed" the brush file, which
  the comment says cannot happen.
- **Why it matters:** JB-1.21 (hot-reload) and the brush shelf will diff files; a guaranteed
  first-save rewrite of every shipped brush turns every user's file into a one-line-everywhere diff
  and defeats "a save only shows a diff when the brush really changed" (the related claim in
  `BrushTest`'s header region). Not a data-loss bug — values round-trip exactly — but the promise is
  false as written. Options for the Lead: set `prettyPrintIndent = "  "` in `BrushJson` + either
  `encodeDefaults = false` or accept and reword.

## Builder Questions 1–6: assessment (all disclosed in the spec; none is a hidden defect)

| Q | What it is | My severity call |
|---|---|---|
| 1 | Nothing calls `BrushValidate.validate` — refusals are advisory; hostile `brush.json` still reaches `DabPlacer` (engine then clamps radius to 0 ⇒ **silent no-draw stroke**, the same "lost work" shape R1 fixed) | **MAJOR the moment JB-1.05b/JB-1.21 lands without a checked load path.** Endorse the proposed `BrushJson.decodeChecked` (one line, owner area of `BrushJson` — assign explicitly). Same shape as JB-0.02's save-path gap (my `JB-0.02__mimo.md` F1): the project's validators are all currently **advisory**. |
| 2 | Denormal `size.base` (e.g. `1e-40`) passes `> 0` and draws nothing after clamping | MINOR (disclosed, test pins today's behaviour: `everyNonFiniteSizeIsRefusedByName`). Rule when the first caller lands: floor at `tip.minPx`. |
| 3 | Seven `Param` bases unranged (`opacity` 5 loads clean) | MINOR now (finite-only), **MAJOR-adjacent** once a shader consumes one — same ruling moment as Q1. Pairs with JB-0.03 F2 (spec line 21 clamp sentence). |
| 4 | Disabled grain still ranged (`enabled:false` + `scale:0` refused) | MINOR, deliberate ("every number in the file means something") — I agree with keeping it; needs one spec sentence so it reads as intended. |
| 5 | File size unbounded before validation (200 MB `name`) | MINOR for this task; belongs to the fetcher (JB-1.21/importers). Note as a handover item, not a code defect. |
| 6 | `paramsOf` hand-list trap (see my JB-0.03 F3) | MINOR — endorse `BrushPreset.allParams()` attached to **JB-1.04**'s dispatch. |

## Verified sound

- One-message-per-rule contract: `validationSaysOneThingPerRule` (`BrushTest.kt:277`) covers rules
  1–7, 18, 20, 21, 23 with `assertSole`-style assertions; `everyRangedNumberIsChecked` walks the
  whole range table; the fixture-conflation bug the orchestrator log records (input cap vs point cap)
  is fixed — `aFileCannotCarryUnlimitedCurve` (`:524`) now uses 65/1000-point fixtures that actually
  trip the asserted caps (log entry "The cap rule was right; the test was wrong").
- Spec's "a preset broken one way yields exactly one message containing a named substring" — holds
  for every new rule test; all green at HEAD.
- The shipped-files test's *real* promises hold: every shipped file decodes with zero problems,
  has a non-blank id, and ids are unique (`ShippedBrushFilesTest.kt:35-49`).
- Nothing outside owner area changed by `72845224` (verified via commit stat: `BrushValidate.kt`,
  `BrushTest.kt`, `ShippedBrushFilesTest.kt`, `JB-0.03` spec, board row, orchestrator log).

## Bottom line

Ship-able as Built. F1/F2 are comment-accuracy fixes (one-liners) for whoever owns those files;
Q1/Q6 are the two open items I would attach to the **JB-1.04 / JB-1.05b** dispatches so the
validators stop being advisory before a user ever gets a silent no-draw brush.
