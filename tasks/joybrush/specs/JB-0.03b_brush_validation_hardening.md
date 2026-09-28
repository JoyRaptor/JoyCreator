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
   but a *denormal* like `1e-40` px passes: it is above 0, and `DabPlacer` clamps a sub-pixel radius
   to 0, so such a brush again draws nothing, silently. (`1e-50` does **not** get through — it
   underflows to `0.0f` and is refused.) Is a sub-pixel brush a legitimate thing to allow, or should
   the floor be `tip.minPx` (0.25)? Left as ruled, with a test pinning today's behaviour
   (`everyNonFiniteSizeIsRefusedByName`).
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

