# JB-0.03b — Brush file validation: the Lead's rulings on JB-0.03's questions

| | |
|---|---|
| **Tier** | T2 |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-0.03 (Built) |
| **Owner area** | `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/brush/BrushValidate.kt`, `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/brush/BrushTest.kt`, NEW `joybrush/core/src/jvmTest/kotlin/cc/joycreator/joybrush/core/brush/ShippedBrushFilesTest.kt`, the "Lead rulings" section appended to `specs/JB-0.03_brush_preset_and_dynamics.md` |
| **Estimated size** | ~120 lines + ~150 lines of tests |

## Goal
Brush files will arrive from Wi-Fi hot-reload (JB-1.21) and from other people's packs (Phase 8). A
malformed or hostile file must be REFUSED with a readable message, never slow the render thread or
render as silent nonsense. These are the Lead's rulings on the six questions the JB-0.03 builder
raised — copy them into that spec as a "Lead rulings 2026-09-28" section.

## Rulings (each becomes a validation rule with its own test)
1. **Size caps:** at most **8** `inputs` per Param; at most **64** points per curve.
2. **Curves:** every point's x is in **0..1** and finite, every y is finite. **Sources:**
   `tip.source` ∈ {`procedural`, `image`}; `tipTexture.source` and `paperGrain.source` ∈ {`cloud`,
   `image`}.
3. **Every number must be finite** (no NaN, no ±Infinity) — every Float in the preset, including
   every Param base. **Ranges:**
   | Field | Range |
   |---|---|
   | smoothing | 0..1 |
   | tip.minPx | 0.25..16 |
   | sizeJitter | 0..1 |
   | angleJitter | 0..360 |
   | scatter.count | 1..16 |
   | scatter.countJitter | 0..1 |
   | grain scale (both grains) | > 0 and ≤ 64 |
   | grain edge | 0..1 |
   | grain tiltGradient | −4..4 |
   | grain radial | 0..4 |
   | color hue/saturation/value jitter | 0..1 |
   Use `!in a..b` style checks (NaN fails them), never `<`/`>` alone.
4. **`version < 1` is an error** ("unknown brush version N").
5. **Yes — the shipped files are read from disk** by `ShippedBrushFilesTest` (jvmTest): find the
   `joybrush` folder the same way `WriteGrainAssets` does, load every `joybrush/brushes/*/brush.json`,
   decode and validate each with zero problems. Keep BrushTest's inline copies too.
6. (For JB-1.04, already fixed by the Lead:) `DabPlacer` now asks the brush exactly once per dab,
   passing distance and index — see `DabLook` in `paint/DabPlacer.kt`.

## Tests
One failing case per new rule (a preset broken one way yields exactly one message containing a
named substring), plus the shipped-files test. **Command:** `./gradlew -p joybrush :core:jvmTest` —
BUILD SUCCESSFUL, 0 failures.

## Do not
Change `BrushPreset`, `BrushJson` or `Dynamics`. No new dependencies.

## Definition of done
Tests pass (paste) · only owner-area files · commit `JB-0.03b: brush validation hardening` · ROADMAP
row → 🟧 Built.

## Questions
*(raised by the builder of JB-0.03b, 2026-09-28 — the rulings above are all implemented; these six
are the gaps the builder was told not to guess at, and none of them is inside JB-0.03b's owner area.)*

1. **Nothing calls `BrushValidate.validate` yet.** As of this build the only callers are the tests, so
   the refusal is *advisory*: a hostile `brush.json` can still be decoded and handed to `DabPlacer`
   with any `size.base`, and the engine's clamp will turn the radius into `0` — the stroke then
   draws nothing, silently, which is the same shape of lost work as the freeze. The first real caller
   should be the thing that loads a brush for use (the brush shelf, and JB-1.21's hot-reload), and
   that file does not exist yet. **Who owns it, and should `BrushJson` grow a
   `decodeChecked(json): BrushPreset` that throws on a non-empty problem list so no caller can
   forget?** A `decodeChecked` would be a one-line change to `BrushJson.kt`, which is outside this
   spec's owner area ("Do not change `BrushPreset`, `BrushJson` or `Dynamics`").
2. **`size.base` has a ceiling but no floor.** `above 0 and ≤ 4096` refuses `1e999`, `0` and `NaN`,
   but a *denormal* like `1e-40` px passes, because it is above 0. (`1e-50` does **not** get through
   — it underflows to `0.0f` and is refused.)
   *Premise corrected 2026-09-29 (muse-spark Finding 2).* This question used to say the brush
   "draws nothing, silently", on the strength of a `DabPlacer` that clamps a sub-pixel radius to 0.
   That placer no longer does that. `BrushDabber.kt:110` computes
   `radius = max(diameter, preset.tip.minPx) / 2f`, so a `1e-40` diameter loses to the `minPx` floor
   (≥ 0.25 by rule 8) and draws visible `minPx/2`-px dots at full flow — a *dotted* stroke, not a
   missing one, and pinned clean by `everyNonFiniteSizeIsRefusedByName`. The question it asked
   ("should the floor be `tip.minPx`?") is therefore already answered: it is, in the engine. What is
   left is only whether the *validator* should refuse it as well — the numbers disagree today, one
   says legal and one says 0.25 — which is a wording question about where the floor is spoken, not a
   behaviour that is currently wrong. MINOR; left as ruled, with the test pinning today's behaviour.
3. **Seven `Param` bases still have no range** — `opacity`, `flow`, `tip.angle`, `tip.hardness`, the
   two grain depths, `scatter.amount`. Ruling 3's table did not name them, so all validation can ask
   is that they are finite, and `"opacity": {"base": 5}` loads clean; whether the ceiling holds then
   depends on whatever draws it (JB-1.04 / the shader). Should `opacity`, `flow` and the two grain
   depths be ranged `0..1` at the same time the first caller of `validate` is written?
4. **A disabled grain is still ranged.** `tipTexture: {"enabled": false, "scale": 0}` is refused even
   though nothing samples that texture, because "every number in the file means something" is easier
   to state and to test than "every number the sampler can reach". A file that never enables a grain
   and leaves it at 0 must set `scale` to something legal. Say the word if `enabled == false` should
   skip the grain's own rules instead.
5. **Validation happens *after* the file is parsed, so the file itself is still unbounded.** The caps
   (8 inputs, 64 points) bound the work the *render thread* does, but a `brush.json` with a 200 MB
   `name`, a huge `extensions` map or a megabyte-long `image` path is read into memory by
   `BrushJson.decode` before `validate` ever sees it, and decoding a `"count": 1e999` throws
   `BrushException` from the number parser rather than from a rule (safe, but not the same message).
   A byte ceiling belongs to whoever fetches the file (JB-1.21's hot-reload, the Phase 8 importers);
   `BrushJson` is outside this spec's owner area.
6. **`BrushPreset` has no way to enumerate its own `Param`s, so the validator keeps its own list.**
   `BrushValidate.paramsOf` is a hand-written list of the eight `Param`s in the preset, and rules 16
   to 20 walk *only* that list — so a `Param` added to `BrushPreset` and forgotten there is checked by
   nothing, which is the very bug this spec closed for `tip.hardness`. `RANGED_BASES` is a deny-list
   on top of it and is the *safe* kind of trap (a name wrongly left out yields two messages, which
   `assertSole` catches); the list itself is the unsafe one, and no test can catch a missing entry
   without reflection. **Should `BrushPreset` grow `fun allParams(): List<Pair<String, Param>>` (or a
   sealed settings tree) that `BrushValidate` and any future exporter both walk?** That is a change to
   `BrushPreset.kt`, which this spec is forbidden to touch, and it is the one thing that would make
   the finite check structural rather than conventional.

---

## Follow-up 2026-09-29 — Q1 implemented (`BrushJson.decodeChecked`)

**Reproduced.** mimo (`JB-0.03b__mimo.md` F-adjacent, Builder Questions table, Q1) and muse-spark
(`JB-0.03b__muse-spark.md` Finding 1) found the advisory-refusal gap independently, both rated it
MAJOR-or-MAJOR-on-landing, and both endorsed the same fix — the builder's own `decodeChecked` — so
under ROADMAP §5b it counts as reproduced. One dissent on the facts, resolved below: both reviewers
found **no** production caller, and there **is** one (`BrushLibrary`), because JB-1.05b landed after
they read. The finding stands anyway; the census is in Q7.

**What landed** (this task's owner area only: `core/…/brush/BrushJson.kt` + `BrushTest.kt`):

- `BrushJson.decodeChecked(json): BrushPreset` — `decode`, then every `BrushValidate` rule, refusing
  with the validator's own sentences joined by `"; "` behind the prefix `brush.json cannot be used: `,
  in the same voice as `decode`'s `brush.json cannot be read: `. `decode` is now the *readable* door
  and keeps its own contract (including the early version-word throw); `decodeChecked` is the *usable*
  one, and gets that same sentence from rule 1b instead, so one refusal lists every problem rather than
  stopping at the first. The shared JSON read is factored into a private `parse`, so the two doors
  cannot drift on how a file is parsed.
- Tests (`BrushTest`, section 10): a three-problem file is refused and all three are named, with the
  exact list asserted against `BrushValidate.validate` so a new rule cannot change the count silently;
  `size.base: 0`, `opacity: 5` and a v1 `engine: "fill"` file each produce their right message (see
  Q8 for why the middle one is *no* message today); every shipped brush comes back through the checked
  door byte-for-byte unchanged, and an unparseable file is still refused by the parser's sentence.
- Non-vacuity: with the refusal reverted (`if (false && problems.isNotEmpty())`) the suite goes red on
  `aCheckedLoadRefusesABrushWithProblemsAndNamesEveryOneOfThem` and
  `theThreeBordersOfTheCheckedDoorAreTheOnesTheReviewsNamed` — 645 tests / 3 failures instead of 2,
  the two new reds being the only difference. The third new test
  (`aLegalBrushComesBackThroughTheCheckedDoorUnchanged`) is *supposed* to stay green through that
  revert: it pins the passing path, not the refusal, and a test that failed with the guarantee removed
  would be asserting something the guarantee does not say. Restored: `./gradlew -p joybrush
  :core:jvmTest` → 645 tests / 2 failures, both pre-existing and not mine
  (`ThreeFingerSwipeTest.anOverrideIsRememberedForTheBoardItWasMadeOnAndForgottenOnAnother`, and the
  load-dependent `VectorEraserTest.toIntersectionOverFiftyOverlappingLinesStaysInteractive` flake —
  the latter passed on two of the three runs, which is what a timing flake does).

### Questions raised by this follow-up

7. **The census, and the one call site that must switch.** Every `BrushJson.decode` caller in the tree,
   and what this task did about each:
   | # | Site | Converted? |
   |---|---|---|
   | 1 | `androidkit/…/BrushLibrary.kt:66` (`decode`) — **the real load path** | **No — outside the owner area.** It is the one production caller, and it already hand-rolls `decode` + `BrushValidate.validate` + skip-with-a-log-line, so the shelf is *not* actually unguarded today (see below) |
   | 2 | `BrushTest.kt` (~30 call sites) | No, deliberately: those tests assert `decode` and `validate` as *separate* things, and several assert that a preset is clean — collapsing them would delete what they test. `decodeChecked` is tested alongside, not substituted |
   | 3 | `EnumFreezeTest.kt:225,228` | No — it pins that an unknown `BrushInput` word throws at decode time, which is a parse refusal, not a rule |
   | 4 | `ShippedBrushFilesTest.kt:35,41` | No — it wants the problem *list* (and a round-trip) per shipped file, which is the other half of this function's contract |
   | 5 | `BrushDabberTest.kt:78,79` | No — fixtures, and `BrushPreset` is built in Kotlin here, not read from a file |
   | 6 | `MypaintImport*` | No `BrushJson.decode` at all; importers build `BrushPreset`s and their tests assert empty problem lists directly |
   | 7 | `JbCanvasView.kt:314` (`BrushDabber(p, seed)`) | Not a decode site. It is the *end* of the path: whatever `JoyBrushActivity` hands it |
   | 8 | JB-1.21's hot-reload | **Does not exist** — the spec is 🟦 Ready, and there is no watcher/folder-watch code in `joybrush/` at all |

   **The Lead's call, and it is a small one:** `BrushLibrary.decode` (site 1) is now four lines longer
   than it needs to be, because it re-implements the checked door by hand. Converting it is
   `BrushJson.decodeChecked(json)` plus the existing `try/catch` — the log line and the skip stay
   exactly as they are. I did not make that edit: `androidkit` is outside this task's declared owner
   area, and it is the module the app-file order (`D.02a → D.02 → D.02c / D.05`, one at a time)
   touches, so colliding with it is not mine to risk. **The second, forward-looking half is the one
   that matters: JB-1.21 must call `decodeChecked`, because that is a *new* load path and a new load
   path is exactly how the advisory gap comes back.** A hand-edited `brush.json` arriving over
   Wi-Fi is the hostile-input case this whole spec exists for, and the hot-reload spec should name
   `decodeChecked` by name so it cannot be built against `decode` by default.

8. **Seven `Param` bases are still unranged, and this is a contract question, not a gap to fill.**
   `opacity`, `flow`, `tip.angle`, `tip.hardness`, `tipTexture.depth`, `paperGrain.depth`,
   `scatter.amount` are checked only for finiteness, so `"opacity": {"base": 5}` decodes, validates
   clean, and — through the checked door — *loads*. Today that is survivable and I have pinned it:
   `BrushDabber.unit` clamps flow/opacity/cap to 0..1, `DabPlacer` clamps radius/angle/flow/cap, the
   shader clamps hardness, so a 5 arrives at the canvas as 1. Both reviewers put this at MINOR now
   and **MAJOR-adjacent the moment a shader or CPU consumer reads one unclamped** (mimo, Q3). I did
   not invent ranges: what a *legal* opacity is depends on what consumes it, and the range is not
   derivable from this module. **For the Lead: which of the seven are fractions by contract, and
   should the ranges be added in the same change that first calls `validate` on a live path, or as a
   separate ruling?** If the answer is "0..1 for opacity, flow and the two depths", it is a
   seven-line change to `BrushValidate` plus the `RANGED_BASES` names — say the word and it is cheap.
9. **Q2's `minPx` floor is duplicated in two places that can disagree** (see the corrected Q2). The
   engine applies it, the validator does not, so a sub-pixel brush validates clean and draws dots.
   Not a behaviour bug today. **For the Lead:** should `validate` refuse `size.base < tip.minPx` with
   the same numbers the engine uses, so the person is told at the file rather than discovering a
   dotted stroke?

