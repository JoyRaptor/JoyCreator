# JB-8.03 — Import MyPaint brushes (.myb) into Joy Brush presets

| | |
|---|---|
| **Tier** | T2 |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-0.03 (Built), JB-0.03b (validation) |
| **Owner area** | NEW `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/brush/imports/MypaintImport.kt`, NEW `.../commonTest/.../brush/imports/MypaintImportTest.kt` |
| **Estimated size** | ~250 lines + ~200 lines of tests |

## Goal
MyPaint's whole brush library is CC0 (free to ship), and its format is plain JSON (research R3/R4).
Importing it gives Joy Brush dozens of real brushes and proves the import path before Photoshop's.

## The .myb format (version 3)
```json
{ "version": 3, "comment": "", "parent_brush_name": "", "group": "",
  "settings": {
    "radius_logarithmic": { "base_value": 2.0, "inputs": { "pressure": [[0,0],[1,0.4]] } },
    "opaque": { "base_value": 1.0, "inputs": {} }, … } }
```
Each setting = base value + per-input piecewise-linear curves whose outputs ADD to the base.

## Contract
```kotlin
package cc.joycreator.joybrush.core.brush.imports   // ("import" is a Kotlin keyword)s
data class ImportResult(val preset: BrushPreset, val warnings: List<String>)
object MypaintImport {
    fun convert(mybJson: String, id: String, name: String): ImportResult   // throws BrushException on unreadable input
}
```

## Mapping (decided)
| MyPaint | Joy Brush | Conversion |
|---|---|---|
| `radius_logarithmic` base b | `size.base` | `2·e^b` (diameter px) |
| its `pressure` curve [[x,y]…] | `size.inputs` pressure, multiply | each point → `[x, e^y]` (log-add = multiply) |
| its `random` curve | `sizeJitter` | `min(1, (e^{maxY} − 1))` using the curve's max y; warn |
| `opaque` base | `opacity.base` | as is, clamped 0..1 |
| `opaque_multiply` pressure curve | `opacity.inputs` pressure, multiply | points as is |
| `hardness` base | `tip.hardness.base` | as is |
| `dabs_per_actual_radius` a, `dabs_per_basic_radius` b | `spacing` | `1 / (2·(a + b))`, clamp 0.005..5 (if a+b ≤ 0 → 0.1, warn) |
| `elliptical_dab_ratio` r | `tip.aspect` | `1 − 1/max(r, 1)` |
| `elliptical_dab_angle` θ° | `tip.angle.base` | θ |
| `offset_by_random` o | `scatter.amount.base` | `o / 2` (MyPaint offsets in radii; ours in diameters) |
| `slow_tracking` s | `smoothing` | `min(1, s / 10)` |
| `smudge`, `smudge_length` > 0 | — | engine "smudge" is not built yet: keep as stamp, put raw values in `extensions`, warn |
| tilt inputs (`tilt_declination` 0..90°) on the settings above | `tilt` input | x' = x / 90 (normalised), then as the pressure rule |
| every other setting or input | `extensions` | raw JSON text under `"myb.<setting>"`, warn once per setting |
Plus: `sourceFormat = "myb"`, `license = "CC0"` only if the comment says so (else `"unknown"`),
`author` from `comment` if present, `accumulate = "wash"`, `engine = "stamp"`.
The result must pass `BrushValidate.validate` with no problems (clamp as needed and warn).

## Tests
Use 3 real brushes pasted as test strings: MyPaint's `classic/pen`, `classic/charcoal` and
`deevad/basic_digital_brushes`-style file (copy from github.com/mypaint/mypaint-brushes, CC0 —
note the source path in a comment).
1. Each converts and validates clean. 2. Pen: diameter base = 2·e^b within 1e-4; pressure curve
converted to multiply. 3. Spacing formula. 4. Unknown settings land in `extensions` with warnings.
5. Malformed JSON → BrushException.

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures.

## Definition of done
Tests pass (paste) · commit `JB-8.03: MyPaint import` · ROADMAP row → 🟧 Built.

## Questions
