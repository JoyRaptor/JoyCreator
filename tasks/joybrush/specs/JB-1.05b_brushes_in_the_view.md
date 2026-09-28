# JB-1.05b — Real brushes in the drawing view (brush files → dabber → scatter → engine)

| | |
|---|---|
| **Tier** | T2 (T1 review — touches the drawing view) |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-0.05, JB-1.04, JB-1.05a |
| **Owner area** | EDIT `joybrush/androidkit/build.gradle.kts` (package `joybrush/brushes` as resources — exact edit below), NEW `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/BrushLibrary.kt`, EDIT `JbCanvasView.kt` (stroke start only, as below), EDIT `joybrush-android/.../JoyBrushActivity.kt` (a brush picker pill) |
| **Estimated size** | ~200 lines |

## Goal
Draw with the brush FILES (Ink, Pencil, and every brush added later) instead of the hard-coded
round brush.

## Decisions
1. Package brushes like the shaders — in `androidkit/build.gradle.kts` `tasks.processResources { }`
   add: `from(rootDir.resolve("brushes")) { into("joybrush/brushes") }`.
2. `BrushLibrary.builtIn(): List<BrushPreset>` loads the list of ids from a new resource
   `joybrush/brushes/index.txt` (one folder name per line — create it listing `ink` and `pencil`,
   in `joybrush/brushes/index.txt`) and decodes each `brush.json` with `BrushJson.decode`, skipping
   (and logging) any that fail `BrushValidate`.
3. `JbCanvasView` gets `var preset: BrushPreset?` (null = the existing hard-coded `Brush`). When set,
   a stroke start builds: `BrushDabber(preset, seed = SystemClock.uptimeMillis())`; `DabPlacer(
   spacing = dabber.spacing, look = dabber::look)`; after each `placer.add(...)` the dabs go through
   `Scatter.expand(dabs, preset.scatter, rng, …)` (the rng is a second `SplitMix(seed xor 0x5CA7)`;
   inputs from the dab's pressure, others NaN) before `engine.addDabs`. Stroke smoothing =
   `preset.smoothing` unless the smoothing slider was moved this session. `beginStroke` gets the
   preset's colour (the view's current colour), `strokeOpacity`, accumulate (`wash`→WASH,
   `buildup`→BUILD_UP), blend (`erase`→ERASE) and a `TipShape(aspect, corner, taper,
   hardness = dabber.strokeHardness, minPx)`.
   Hardness is taken after the first dab: call `dabber.look` for the first dab BEFORE `beginStroke`
   by ordering the calls (place the first sample, then begin the stroke, then add the dabs).
4. Activity: a "Brush" pill cycles through `BrushLibrary.builtIn()` names; the current name shows on it.
5. Grain (tip texture / paper grain) is NOT drawn yet — that is JB-1.05c (T1, shader work).

## Verification
`./gradlew -p joybrush :androidkit:compileKotlin` green; watcher green; sandbox phone screenshot of an
Ink stroke (pressure taper) and a Pencil stroke (opacity by pressure).

## Do not
Don't change the engine, shaders, DabPlacer, BrushDabber or Scatter.

## Definition of done
Screenshots · builds green · commit `JB-1.05b: brush files drive the view` · ROADMAP row → 🟧 Built.

## Questions
