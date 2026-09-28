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
