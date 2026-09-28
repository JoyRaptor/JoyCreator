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
package cc.joycreator.joybrush.core.brush.imports   // ("import" is a Kotlin keyword)
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

*Filled in by the build. The mapping table above is what was implemented; these are the readings it
had to make where the table was silent, and the one thing it deliberately did not decide.*

1. **"tilt inputs … on the settings above … then as the pressure rule"** — read as *the two settings
   that have a pressure rule* (`radius_logarithmic`, `opaque_multiply`). Reading "the settings above"
   as all eleven would have required inventing a y-conversion for every one of them, which is exactly
   the kind of quiet approximation the Phase 8 brief forbids. If you meant all eleven, this is the
   change and it is one line per setting in `DYNAMIC`.
2. **`opaque`'s own input curves are dropped.** The table gives `opaque` a base and
   `opaque_multiply` a pressure curve, and nothing gives `opaque` an input rule. Implemented as
   written, which means `charcoal` and `basic_digital_brush` lose their opacity ramp — the digital
   brush's `opaque` base is `2.5e-05`, so it imports at a flat, nearly invisible opacity. **This is
   the mapping I would most want a second opinion on**, because it is a large visible loss and the
   table reads more like an omission than a decision.
3. **`license` reads all four free-text header fields**, not `comment` alone. `deevad/
   basic_digital_brush.myb` states `license: CC-Zero/Public-Domain` in `notes` and says nothing in
   its comment, so the literal rule reports "unknown" for the one file in the set that does declare
   CC0. Still never a guess: no claim anywhere → `"unknown"`.
4. **`radius_by_random` is dropped.** MyPaint has *both* it and a `random` input on
   `radius_logarithmic`; the table only reads the latter into `sizeJitter`. Kept raw and warned.
5. **A curve is refused, never trimmed, on: more than 64 points, x outside its domain, a point that
   is not a pair, or a y that comes out non-finite.** This refuses the whole file over one bad
   curve, which is harsh against 44 good settings — chosen because "imports successfully, draws
   differently" is the worse outcome.
6. **The x range is *not* checked on curves this importer drops**, because several MyPaint inputs
   are not 0..1 (`direction_angle` 0..360, `gridmap_x/y` 0..256, `barrel_rotation` ±180). Checking
   it everywhere would refuse real brushes. **The shape of a curve is checked everywhere**, mapped or
   not — see the review note below.
7. **`MIN_SIZE_PX = 0.01` is the importer's own floor**, not the validator's: `BrushValidate` has no
   lower size rule, and `2·e^b` underflows to 0 for `b < −745`.
8. **Absent `radius_logarithmic` → MyPaint's documented default base 2.0** → `2·e²` = 14.78 px, plus
   a warning. The transform is applied on the fallback path too. (It was not, in the first cut: the
   log base leaked through as a 2 **pixel** diameter. Pinned by
   `theAbsentRadiusFallbackIsInDiameterNotLogSpace`.)
9. **Leftover `BrushValidate` problems become warnings**, not a `BrushException`. Every value is
   checked or clamped before the call, so this should always be empty; if it ever is not, the caller
   finds out at import rather than at save. Tests assert the string never appears for a valid file.
10. **`tip.hardness.base` has no range, and that is `BrushValidate`'s gap, not this importer's.**
    `hardness` is documented "as is" above, `BrushValidate` rule 16 asks only for finiteness, and the
    value reaches `jb_tip.glsl`. `1e30` is therefore legal, finite and meaningless. **Ruling: add
    the 0..1 range rule to `BrushValidate` in JB-0.03b, not to the importer** — the same field is
    reachable from `brush.json`, `.abr`, `.kpp` and native presets, so an importer clamp would close
    one of five doors; and whether `jb_tip.glsl` should saturate above 1 or treat it as an error is
    a rendering decision the importer must not make. Until that rule lands the importer **passes the
    value through unchanged and warns**, which invents no range. Pinned by
    `aHardnessOutsideZeroToOneIsPassedThroughButNotSilently`, which should be deleted when the rule
    lands.
