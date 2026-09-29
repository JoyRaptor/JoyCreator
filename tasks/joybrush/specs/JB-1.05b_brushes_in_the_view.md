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

Notes and questions from the T2 build (2026-09-28). No gradle was run here; everything below is
verified by the watcher's `build.log`.

1. **Scatter's generator: decision 3 and the landed class disagree.** Decision 3 says a second
   `SplitMix(seed xor 0x5CA7)`; `Scatter`'s own class note says the caller "must hand this the SAME
   generator the brush was drawing from, not a fresh one", and that a scatter is a pure function of
   (dabs, spec, seed) "only because the dabber's draws came first". I built decision 3 — a second,
   salted generator — so the dabber's three draws per dab are never walked differently, which is the
   part `BrushDabber` and `DabPlacer` actually promise. If the Lead wants one stream instead,
   `BrushDabber` has to expose its `SplitMix`, and that is `joybrush/core`, outside this task.
2. **The seed is `SystemClock.uptimeMillis()`, so a file-drawn stroke cannot be replayed.** The
   blueprint calls Joy Brush strokes recordings that are re-rendered at another zoom. The seed never
   leaves this view, so a recorded stroke drawn with a preset comes back different. Does the seed
   belong in the document (JB-0.04's codec), or should the view expose it for the recorder?
3. **Smoothing ownership needed a new public flag.** Decision 3's "unless the smoothing slider was
   moved this session" is not knowable from `var smoothing`, so `JbCanvasView` gained
   `var smoothingFromUser: Boolean`, which the Activity sets from `onProgressChanged` only when
   `fromUser` is true — the starting 35% therefore does not count as a choice. Confirm that is the
   intended precedence, and that the flag is the right shape of answer.
4. **`beginStroke` now fires on the first dab, not on ACTION_DOWN** (decision 3's ordering: the
   dabber has to evaluate hardness and stroke opacity at its first `look` before the engine is told).
   A stroke that places no dab at all therefore never begins a GL stroke, and `engine.endStroke()`
   returns 0 for it — safe, but a behaviour change worth naming. The hard-coded `Brush` path still
   posts `beginStroke` at ACTION_DOWN, byte for byte as before.
5. **Size is the file's own now.** With a preset set, `Brush.sizePx` (12) and `minSizeFraction` are
   ignored; Ink draws at its `size.base` 6. Nothing on the screen scales it yet, so switching brushes
   changes the apparent size with no control. Should the size control (JB-2.x) scale `size.base`, or
   does the file win outright?
6. **Does a plain JVM jar's resources reach the APK?** `BrushLibrary` reads
   `/joybrush/brushes/…` with `getResourceAsStream`, the same route `ShaderLibrary` uses for the
   shaders — and the shaders have never actually run on a phone (JB-0.07's own row says so). The
   failure is graceful: `builtIn()` returns empty, the pill reads "Brush", the view falls back to the
   hard-coded round brush, and `Log.w("JoyBrush", …)` says why. So the owner's check is "the pill
   says Ink", not "it did not crash".
7. **No screenshots from here.** This sandbox has no adb, so Verification's two pictures are the
   owner's `📱` step: an Ink stroke showing pressure taper in size (its `size` curve), and a Pencil
   stroke showing opacity by pressure (its `opacity` curve, arriving as each WASH dab's cap).
