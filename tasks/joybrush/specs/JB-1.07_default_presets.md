# JB-1.07 — The default presets: Ink, Pencil, Marker, Soft air, Eraser — **wave one**

**xr: openrouter/stealth/space-bunny-alpha 2026-09-29** — the Lead's review fixes applied: scoped to
**wave one** (Marker, Soft air, Eraser) per R39, with Smudge and Nudge named as wave two after
JB-1.06 — `BrushValidate.ENGINES` is `setOf("stamp", "smudge", "wet", "fill")` and has **no
`"push"` word**, so those two files are *refused by name* today, not merely early; the Eraser preset
ruled to **win over the Eraser button**, whose removal is stated as **JB-2.01's** edit and not this
row's; every **version literal removed** (R30 item 3 — the builder reads `BRUSH_VERSION` at landing);
`ShippedBrushFilesTest` and the new `DefaultPresetsTest` both confirmed in `jvmTest` on disk. Reading
the code also found the earlier draft's Pencil premise **false** — `GrainSpec.scale` exists and
defaults to `1f`, and what is missing is an engine that reads it — so Pencil and Ink are out of the
wave-one owner area entirely.

| | |
|---|---|
| **Tier** | T1 (the numbers) + T3 (the owner signs them off on the Note 9 — blueprint Phase 1's owner check is literally "You judge each brush on the Note 9 and sign it off") |
| **Status** | 🟦 **Ready for WAVE ONE: five presets** (Ink, Pencil, Marker, Soft air, Eraser) plus the `fill` pen that already ships. R39 splits the board row into two waves: **Smudge and Nudge wait for JB-1.06**, which is a T1 row, `🔒` the Lead's own, and `🟸 Draft` — not built. **A spec that promises seven presets when two of them are impossible is a spec that stalls**, so this file is written for five and says plainly where the other two go. |
| **Needs** | 1.05 (`BrushJson` / `BrushValidate` / `BrushDabber` — all Built). **NOT 1.06** — that is wave two (R39) |
| **Owner area** | NEW `joybrush/brushes/marker/brush.json` · NEW `joybrush/brushes/softair/brush.json` · NEW `joybrush/brushes/eraser/brush.json` · EDIT `joybrush/brushes/index.txt` · EDIT `joybrush/core/src/jvmTest/kotlin/cc/joycreator/joybrush/core/brush/ShippedBrushFilesTest.kt` · NEW `joybrush/core/src/jvmTest/kotlin/cc/joycreator/joybrush/core/brush/DefaultPresetsTest.kt` — **these six paths and nothing else** |
| **Not in the owner area** | `joybrush/brushes/pencil/brush.json` and `joybrush/brushes/ink/brush.json` are **not opened in wave one** (Decision 3). `JoyBrushActivity.kt` is **not touched at all**, and in particular **the Eraser button is not removed by this row** (Decision 10). `BrushJson.kt`, `BrushPreset.kt` and `BrushValidate.kt` are **not touched** (Decision 5). |
| **Estimated size** | ~65 lines of JSON + ~290 lines of tests |
| **Command** | `./gradlew -p joybrush :core:jvmTest` — BUILD SUCCESSFUL, 0 failures |

## Goal

Blueprint §1 idea 7, and the owner's own words: *"A few excellent brushes, not hundreds."* Seven
presets are the finished set; **five of them are what this row ships**, because two of them need a
file format that does not exist yet. Today three folders exist — `ink`, `pencil`, `fill` — and
`fill` is the fill pen (JB-1.08a, built), which is **not** in the row's list and is not touched. So
this row writes the two that are missing from the five that are buildable, pins all of them so a
later edit cannot quietly turn a Marker into a second Airbrush, and fixes the order, because **the
order in `index.txt` is the order in the brush pill and the first one is the brush the screen opens
with** (Decision 2).

## Wave two, named in advance

**JB-1.06 smudge & nudge (T1, `🟸 Draft`, not built)** is what wave two waits for, and it is not a
small dependency. `BrushValidate.ENGINES` is `setOf("stamp", "smudge", "wet", ENGINE_FILL)` today
(`BrushValidate.kt:16`) — there is **no `push` word**, so a Nudge preset written now would be
refused by rule 21 with `unknown: engine "push"`, and `ShippedBrushFilesTest` would go red. JB-1.06's
owner area is precisely the three files that add the words (`BrushPreset.kt`, `BrushValidate.kt`,
`BrushJson.kt`'s `wordsNeedingVersion`), so nothing this row can do brings wave two closer.

| wave | presets | waits for | where it is specified |
|---|---|---|---|
| **one (this row)** | Marker, Soft air, Eraser (+ Ink, Pencil, `fill` already there) | nothing beyond 1.05 | this file, below |
| **two** | Smudge, Nudge — plus Pencil's one `"scale"` line (Decision 3) | **JB-1.06** for the two files; **JB-1.05c** for the pencil's `scale` | the *Wave two* section at the end of this file |

**Nothing in wave one may anticipate wave two**: no `smudge/` or `nudge/` folder, no
`"engine": "smudge"`, no `"version"` bump, and no new line in `index.txt` for a folder that does not
exist.

## Contract (verbatim)

Everything the files must say. `BrushPreset` (JB-0.03) is unchanged by this spec.

```
joybrush/brushes/index.txt      (in this order, and this order is the product)
  ink          <- line 1: the brush the Joy Brush screen opens with
  pencil
  marker
  softair
  eraser
  fill         <- JB-1.08a, already there, untouched
```

The two lines that come **between `softair` and `eraser`** in the finished list, `smudge` and
`nudge`, are wave two (see above). The existing header comment at the top of `index.txt` is kept,
and the one line this spec adds to it is: *the order here is the brush pill's order, and the first
line is what the screen opens with.*

**The fields the three new files use**, pasted from the landed
`joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/brush/BrushPreset.kt`:

```kotlin
@Serializable data class TipSpec(
    val source: String = "procedural",   // "procedural" | "image"
    val image: String? = null,
    val corner: Float = 2f,              // superellipse exponent (jb_tip.glsl)
    val taper: Float = 0f,
    val aspect: Float = 0f,
    val angle: Param = Param(0f),        // degrees
    val followDirection: Boolean = false,
    val hardness: Param = Param(0.9f),
    val minPx: Float = 1f,
)

@Serializable data class Param(val base: Float, val inputs: List<InputCurve> = emptyList(), val combine: String = "multiply")

@Serializable data class BrushPreset(
    val format: String = "joybrush.brush",
    val version: Int = BRUSH_VERSION,
    val id: String,
    val name: String,
    val engine: String = "stamp",        // "stamp" | "smudge" | "wet" | "fill"
    val tip: TipSpec = TipSpec(),
    val size: Param,                     // diameter in px
    val opacity: Param = Param(1f),      // ceiling for the whole stroke
    val flow: Param = Param(1f),         // per dab
    val spacing: Float = 0.04f,          // fraction of diameter
    val tipTexture: GrainSpec = GrainSpec(),
    val paperGrain: GrainSpec = GrainSpec(),
    val scatter: ScatterSpec = ScatterSpec(),
    val sizeJitter: Float = 0f,
    val angleJitter: Float = 0f,
    val color: ColorJitter = ColorJitter(),
    val accumulate: String = "wash",     // "wash" | "buildup"
    val blend: String = "normal",        // "normal" | "erase" | "behind"
    val smoothing: Float = 0.3,
    val license: String = "CC0",
    val author: String = "",
    val sourceFormat: String = "native",
    val extensions: Map<String, String> = emptyMap(),
)
```

**And the two constants the tests import rather than retype**, from
`joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/brush/BrushJson.kt:20` and
`BrushValidate.kt:25`:

```kotlin
const val BRUSH_VERSION = 2      // "the first brush version that could express the fill pen's two new words"
const val MAX_SIZE_PX = 4096f    // "The largest brush diameter, px. Public so every size control clamps to the SAME number."
```

**And the four words `BrushValidate` accepts** (`BrushValidate.kt:16-18`), because a file that uses
anything else is refused by name and the message is the whole error report:

```kotlin
private val ENGINES = setOf("stamp", "smudge", "wet", ENGINE_FILL)   // "fill"
private val ACCUMULATES = setOf("wash", "buildup")
private val BLENDS = setOf("normal", "erase", BLEND_BEHIND)         // "behind"
```

`"erase"` is already legal, which is the whole of why the Eraser preset is a two-line change to
reality rather than a new word.

## Steps (wave one)

1. Write `DefaultPresetsTest.kt` first, from the Tests section. It will fail: three of the folders
   do not exist.
2. Write the three `brush.json` files (Marker, Soft air, Eraser), then `index.txt`.
3. Extend `ShippedBrushFilesTest` (Tests 11–13).
4. `./gradlew -p joybrush :core:jvmTest` — green, output pasted.
5. **The owner draws with all five on the Note 9 and signs off or sends back** (T3). A preset is not
   signed off because its numbers validate. It is signed off because a person drew with it.

## Decisions

1. **Five files ship in wave one, and the `fill` pen is not one of the row's seven.** The finished
   list is Ink, Pencil, Marker, Soft air, Smudge, Nudge, Eraser; `fill` is the fill pen, which
   LEAD_RULINGS R21 made **a brush rather than a tool**, and it already exists at
   `joybrush/brushes/fill/` with its own id — the bare `fill`, not `joybrush.fill`, an inconsistency
   with the other two that this row does **not** fix, because renaming a shipped id is a
   compatibility decision and not a tidy-up. It stays in the picker, at the end, because it is a
   different kind of thing from a texture brush. Wave one writes **three** new files; the two
   already there are pinned by the tests and not edited.

2. **`index.txt` is the product, not a manifest.** `BrushLibrary.builtIn()`
   (`androidkit/BrushLibrary.kt:34`) reads it through `folderNames()`, which drops blanks and `#`
   comments and keeps everything else **in file order**, and the pill cycles in that order. So:
   - Line 1 is **Ink**. It is the brush that was already first, it is the one the owner's phase
     checks have been against, and it is the least surprising thing to open on.
   - **Marker and Soft air sit between Pencil and the Eraser**, in that order: they add to the
     canvas like the first two do, and the Eraser is last of the painting brushes because it is the
     only one that removes. Smudge and Nudge slot between Soft air and the Eraser in wave two,
     because they *change* what is on the canvas rather than add to it — **and the wave-one order
     above is deliberately the wave-two order with those two lines missing**, so wave two is two
     inserted lines and not a re-order.

3. **Neither `ink/brush.json` nor `pencil/brush.json` is opened in wave one.** The earlier draft of
   this spec allowed Pencil a one-line edit (an explicit `"scale"` on its `paperGrain`) and said
   "`scale` is undefined today". **Verified against the tree, that premise was wrong** and the
   edit does not belong here anyway:
   - `GrainSpec.scale` **exists** — `val scale: Float = 1f` (`BrushPreset.kt:48`) — and
     `BrushValidate` bounds it (`!(g.scale > 0f) || g.scale > MAX_GRAIN_SCALE`, with
     `MAX_GRAIN_SCALE = 64f`). The pencil therefore **validates today at `scale = 1`**, and a
     re-encoded file writes the number out. Nothing about it is undefined.
   - What is missing is that **no engine reads it**: `rg` over `joybrush/` finds `paperGrain.scale`
     and `tipTexture.scale` only inside `BrushValidate` and the tests. The field is a number that
     validates and does nothing, which is JB-1.05c's job (it is `🟸 Draft`, Lead's, T1).
   - And the *value* is a T1/T3 tuning number, not a builder's: JB-1.05c has provisionally defined
     `pitchPx = GRAIN_UNIT_PX / scale` with `GRAIN_UNIT_PX = 64` document px, and LEAD_RULINGS R38
     has now **ruled** Q2 to exactly that ("pitch = 64 / scale document px, fixed physical size").
     The definition is settled; the number a pencil wants is what the owner judges with a pencil in
     his hand. Writing `"scale": 8` into a file whose shader does not exist yet would be a number
     nobody can see and a claim nobody can check.

   So Pencil and Ink are **not in the owner area for wave one**. Both are `🟧 Built` / `🟩 Reviewed`
   and are what the owner has been signing off against; re-tuning them is a deliberate act and
   never a side effect of adding neighbours.

4. **Every number below carries its reason, and the numbers are proposals for the owner, not
   rulings.** The tuning is T1's to set and the owner's to judge; what this spec owes is that every
   number is *argued*, so the argument can be disagreed with instead of the number being retyped.
   The two governing facts a preset author works with:
   - **`accumulate` is the single biggest decision in a preset.** In `wash`, `BrushDabber.look`
     returns `cap = opacity` for every dab, so a stroke's contribution is capped at its own
     opacity and the stroke never goes darker where it crosses itself — that is what makes Ink and
     Marker read as ink and marker. In `buildup` it returns `cap = 1f` for every dab and the
     opacity is applied once at commit (`strokeOpacity`), which is what makes an airbrush an
     airbrush. (Both verified in `BrushDabber.kt:124`.)
   - **`flow` is per dab and `opacity` is the ceiling.** A soft brush wants a *low* `flow` and a
     *high* `opacity`; a hard one wants a high `flow`. Confusing the two is how an airbrush ends up
     with a chalky edge.

   **Marker** — `joybrush.marker`, name "Marker". A felt tip: nearly opaque, almost no taper, a
   slightly squared tip so the paper shows through the corners.
   ```json
   { "format": "joybrush.brush", "version": 1, "id": "joybrush.marker", "name": "Marker",
     "engine": "stamp",
     "tip": { "corner": 4, "aspect": 0, "hardness": { "base": 0.85 } },
     "size":  { "base": 24, "inputs": [ { "input": "pressure", "curve": [ [0, 0.75], [1, 1] ] } ] },
     "opacity": { "base": 0.9 },
     "flow":  { "base": 0.7 },
     "spacing": 0.03,
     "accumulate": "wash", "blend": "normal",
     "smoothing": 0.3, "license": "CC0" }
   ```
   `corner 4` is the shader's own words for a rounded square — `jb_tip.glsl:5`: *"superellipse
   exponent: 2 = circle/ellipse, 1 = diamond, 4 ≈ rounded square, 16+ ≈ square"* — and a perfect
   circle reads as a pen, not a marker. The pressure curve bottoms out at 0.75 because a marker
   does not taper, it just presses. `wash` + `opacity 0.9` is the whole marker trick: the stroke
   cannot exceed 90 % and cannot darken on itself. `flow 0.7` so the colour arrives in the first
   centimetre rather than fading in. No grain, no scatter, no jitter.

   **Soft air** — `joybrush.softair`, name "Soft air". The only shipped preset that is `buildup`,
   because it is the only one that *should* get darker where it passes twice.
   ```json
   { "format": "joybrush.brush", "version": 1, "id": "joybrush.softair", "name": "Soft air",
     "engine": "stamp",
     "tip": { "corner": 2, "aspect": 0, "hardness": { "base": 0.05 }, "minPx": 1 },
     "size":  { "base": 60, "inputs": [ { "input": "pressure", "curve": [ [0, 0.2], [1, 1] ] } ] },
     "opacity": { "base": 0.9 },
     "flow":  { "base": 0.05 },
     "spacing": 0.02,
     "accumulate": "buildup", "blend": "normal",
     "smoothing": 0.4, "license": "CC0" }
   ```
   `hardness 0.05` is "soft all the way from the centre" (`jb_tip.glsl`'s own wording), not a
   hard-ish edge. `flow 0.05` is the number that makes it an airbrush: a hundred overlapping dabs
   build to about 1, and one dab is invisible. `buildup` so they do build. `spacing 0.02` so the
   build is smooth rather than banded. The pressure curve starts at 0.2, not 0, because a
   pressure-less finger (which the app supports, R7 §1.5) must still get an airbrush and not a dot.
   `minPx 1` is the default and is written out because the file states its defaults outright.

   **Eraser** — `joybrush.eraser`, name "Eraser".
   ```json
   { "format": "joybrush.brush", "version": 1, "id": "joybrush.eraser", "name": "Eraser",
     "engine": "stamp",
     "tip": { "corner": 2, "aspect": 0, "hardness": { "base": 0.5 }, "minPx": 1 },
     "size":  { "base": 40 },
     "opacity": { "base": 1 },
     "flow":  { "base": 1 },
     "spacing": 0.05,
     "accumulate": "wash", "blend": "erase",
     "smoothing": 0.2, "license": "CC0" }
   ```
   `"blend": "erase"` is a word this build already understands in three places, all verified:
   `BrushValidate.BLENDS` contains it (`BrushValidate.kt:18`); `JbCanvasView.kt:349` reads
   `val eraseBlend = strokeErase || (p != null && p.blend == "erase")` (landed by JB-1.05b); and
   the engine's word is `StrokeBlend.ERASE`. `flow 1` + `opacity 1` so one pass clears;
   `hardness 0.5` so a light touch feathers instead of cutting a hole with a hard disc. The preset
   is `engine: "stamp"`, so it is one of the two engines R20 allows on an INK layer.

5. **Every shipped file carries the LOWEST version that can express it, and this row writes no
   version number at all.** Three rules, and the third is the one R30 item 3 is about:
   - **Wave one needs no bump whatsoever.** Marker, Soft air and Eraser use only words that have
     existed since version 1 (`stamp`, `wash`, `buildup`, `normal`, `erase`), so all three are
     **version 1** files and stay readable by a build that predates the fill pen. `ink` and
     `pencil` are already version 1; `fill` is version 2 because it uses `engine "fill"`.
   - **The rule is `BrushJson.versionFor`'s**, and the builder must not restate it: a file that
     uses no version-2 word encodes as the version it already had.
   - **No literal version number appears in this spec for wave two, and `BrushJson.kt` is not in
     the owner area.** When wave two writes the Smudge and Nudge files their version is **whatever
     `BRUSH_VERSION` is at the time that row runs** — the builder reads the current constant and
     writes that. R30 item 3: *"version numbers are assigned AT LANDING, never in a spec."*
     (JB-1.06's own spec does write a literal `3` in Decisions 8 and its test 12; that is 1.06's
     file, not this one, and the number it lands is whatever `BRUSH_VERSION` is then.)
   The test that pins this is Test 10, and it reads `BRUSH_VERSION` from the code rather than
   asserting a number.

6. **Every preset is defined by a property, and the property is what the tests assert.** Not "the
   Marker's flow is 0.7" but "**the Marker cannot go darker than 0.9, ever**" and "**one Soft air
   dab is invisible and a hundred of them are not**". The numbers are the means; these are the
   things that make a brush that brush, and a test written on the property survives a tuning pass
   where a test written on the number does not. That is the difference between a suite that catches
   "someone made the airbrush opaque" and one that has to be rewritten by the person who did it.

7. **A preset folder on disk that is not in `index.txt` is invisible, and nothing catches it
   today.** `ShippedBrushFilesTest` walks the *folders*; `BrushLibrary.folderNames()` reads the
   *file*. A folder nobody listed validates, round-trips and is never shown to anybody — which is how
   a brush can be "shipped" for three phases and never once seen. Three tests close this from both
   directions, and they are the most valuable ones in this spec.

8. **No third copy of a preset file, and every source-level test in this row is in `jvmTest`.**
   `commonTest/…/brush/BrushTest.kt` carries byte-identical inline copies of `ink` and `pencil`
   because `commonTest` cannot open a file — which `ShippedBrushFilesTest`'s own KDoc states in as
   many words. That trick is the first two presets only; **the new tests read the files from disk**,
   they are in `jvmTest` where `java.io` is allowed, and the existing inline copies are left exactly
   as they are. Do not add copies for the new presets. *(This is the project's standing rule: a test
   that opens a file is a `jvmTest` test. Several other rows got this wrong; this one must not.)*

9. **Size is a document-px number and the range is `BrushValidate.MAX_SIZE_PX`, imported.** No
   preset hard-codes 4096; the assertion uses the constant, which R19 made public for exactly this
   (`BrushValidate.kt:25`, "Public so every size control clamps to the SAME number"). And note what
   the size numbers in Decision 4 mean today: **nothing on screen scales `size.base` yet**
   (JB-1.05b's Q5, still open), so these are the on-screen sizes at zoom 1 and switching brushes
   changes the apparent size. See Q3 — this is the one number in every file that is certain to move.

10. **The Eraser PRESET wins over the Eraser button, and the button is removed by JB-2.01 and not
    before.** R39 settles it: *"The Eraser preset wins over the Eraser button; the button is removed
    by the chrome row (2.01), not before."* The reason is in the code, and it is not a matter of
    taste: `JoyBrushActivity.toggleEraser()` does `canvas.brush = canvas.brush.copy(erase = true)`
    on the **hard-coded** `Brush` — the JB-0.05 path, which ignores `preset` entirely, so it cannot
    honour the preset's hardness, flow or grain — whereas `"blend": "erase"` goes through
    `JbCanvasView.beginStrokeNow`'s `strokeErase || (p.blend == "erase")` and does. **So this row
    ships the preset and leaves the button exactly where it is.** Two consequences, both stated so
    that nobody helps too much:
    - **This row does not edit `JoyBrushActivity.kt`**, which is not in its owner area and is under
      R30 item 1's one-row-at-a-time order anyway. The button's removal is **JB-2.01's** edit, in
      JB-2.01's own file, in JB-2.01's own cluster.
    - **Between this row landing and JB-2.01 landing there are two controls that erase**, differing
      in whether the rest of the brush is honoured. That is a real, temporary state, it is what the
      ruling produces, and the honest thing is to name it (Q2) rather than to quietly delete a
      control out of another row's area.

11. **No preset may use a word this build refuses, and the three words that matter are `engine`,
    `accumulate` and `blend`.** `BrushValidate` rule 21 reports the offender by name
    (`unknown: engine "push"`), which is the whole error report. Wave one's three files use only
    `stamp`, `wash` / `buildup`, and `normal` / `erase`, so all three pass rule 21 with nothing
    looked up. **Wave two's two files cannot**: `ENGINES` has no `"push"` today, and adding it is
    JB-1.06's edit to `BrushValidate.kt` (Decision and *Wave two* above).

## Tests

**Every test in this section is a `jvmTest` test** (Decision 8). `DefaultPresetsTest.kt` is NEW in
`jvmTest/…/brush/` and reads every preset **from disk**; it pins the property, not the number,
wherever a property exists. Tests 11–13 are additions to the existing
`ShippedBrushFilesTest.kt`, which is already the class that walks the disk (and which is itself at
`joybrush/core/src/jvmTest/kotlin/cc/joycreator/joybrush/core/brush/ShippedBrushFilesTest.kt` —
verified on disk, not from memory).

**The fixture, written out so no test has to invent a board.** A straight stroke of 100
`PenSample`s, `x = 2.0f * i` for `i` in `0..99`, `y = 0f`, `timeMs = 8.0 * i` (125 samples a second,
a real pen rate), `pressure = 0.5f`, `tilt = 0f`, everything else its `PenSample` default; a fixed
seed for the `BrushDabber`; and the real `DabPlacer(dabber.spacing, dabber::look)`, whose `add`
returns the `Dab`s. The dab count is **computed by the test from the dabs the placer actually
produced** and is never written down as a literal — a test that states a dab count is asserting the
placer's internals, and a change to `spacing` would then fail a test about the brush.

1. **The set, exactly.** The ids on disk are exactly `joybrush.ink, joybrush.pencil,
   joybrush.marker, joybrush.softair, joybrush.eraser, fill` — **plus** `joybrush.smudge` and
   `joybrush.nudge` **if and only if** those two folders exist, and the test says so in its failure
   message rather than assuming. (So the suite is green both before and after wave two, and turns
   red when a folder is deleted from one place and not the other.) The `fill` entry is the bare id,
   not `joybrush.fill`, and the test says so in a comment so nobody "fixes" it.
2. **`index.txt` lists every folder, and every folder is in `index.txt`** — both directions, as sets,
   ignoring blank lines and the `#` comment (Decision 7). This is the test that makes a preset real.
3. **Line 1 of `index.txt` is `ink`**, and the whole order is exactly
   `ink, pencil, marker, softair, eraser, fill` in wave one, and
   `ink, pencil, marker, softair, smudge, nudge, eraser, fill` in wave two — **with any folder that
   does not exist yet removed from both sides of the comparison** and the removal named in the
   failure message. Asserted as the exact sequence, because the order *is* the product. Written as
   one expectation table with the conditional removals in it, so wave two is a one-line change here
   rather than a new test.
4. **The defining property of each brush, through `BrushDabber` + `DabPlacer`** (Decision 6). For
   each preset, decode it from disk, build a `BrushDabber` with the fixed seed, place the 100-sample
   stroke, and assert:
   - **Ink**: `accumulate == "wash"`, so every dab's `cap` equals the stroke's opacity **and the cap
     is the same on the first and the hundredth dab** — ink does not darken on itself.
   - **Marker**: `cap ≤ 0.9` on every dab, and `flow ≥ 0.5` on every dab, so the colour arrives at
     once (a Marker with a soft flow is a pale pen). Both bounds are the file's own numbers, read
     from the decoded preset and not retyped.
   - **Soft air**: `accumulate == "buildup"`, so **every** dab's `cap` is exactly `1f`, and
     `strokeOpacity` equals the opacity evaluated at the first dab — which for this file is
     `opacity.base` with no inputs, so `0.9` exactly, and the test says why. Then the real airbrush
     assertion, which is the arithmetic the test writes out: with `f = flow.base = 0.05` and `n` the
     dab count the placer produced, the stroke's coverage is `1 − (1 − f)ⁿ`; assert
     `1 − (1 − f)¹ < 0.1` (**one dab is 5 %, invisible**) and `1 − (1 − f)ⁿ > 0.9` (**the whole
     stroke is solid**), and print `n` and the two values in the failure message. The threshold the
     test derives for itself is `n > ln(0.1) / ln(0.95) = 2.302585 / 0.051293 = 44.9`, so **45 dabs
     is the least that passes** — if the placer ever produced fewer, the failure names the count
     rather than saying "the airbrush is too weak". If someone raises `flow` to make the airbrush
     "stronger", this goes red and the message says which half broke.
   - **Eraser**: `blend == "erase"`, `flow == 1f` and `opacity.base == 1f`, so one pass clears.
   - **Pencil**: `paperGrain.enabled == true`, `tipTexture.enabled == false`, and
     `paperGrain.tiltGradient > 0f` — the three fields the file exists for, and the third is the
     owner's own idea and the reason JB-1.02 exists. The existing `BrushTest` already asserts the
     first two's values (`BrushTest.kt:155-157`); this is the disk-read version, so an edit to the
     file cannot pass unnoticed.
5. **Size and range.** Every preset's `size.base` is `> 0f` and `<= BrushValidate.MAX_SIZE_PX` —
   the constant, **imported**, not retyped (Decision 9) — and `flow.base` and `opacity.base` are in
   `0..1` on every preset. Note in a comment that `BrushValidate` does **not** range those two bases
   today (only `size` is in `RANGED_BASES`), so this assertion is this suite's own and is the only
   thing standing there. The names and values go in the failure message, so a bad edit says which
   brush and which number.
6. **Nothing shares an id, and nothing shares a folder** (already partly in `ShippedBrushFilesTest`;
   kept because two presets with one id makes the second unreachable in the pill, and the failure is
   a missing brush rather than an error).
7. **No preset is a copy of another with a different name.** For every pair of presets, at least one
   of `{engine, accumulate, blend, tip.corner, tip.hardness.base, flow.base, size.base}` differs.
   This is the test that catches "the Marker is the Soft air with the size changed", which is the
   exact shape a rushed preset takes and which no validator would ever speak to. On this row's set it
   also catches the specific mistake available here, which is shipping the Eraser as a copy of the
   Ink with `"blend"` changed.
8. **Every preset validates clean, and the message names the file** (the existing
   `ShippedBrushFilesTest` assertion, which walks every folder; restated here only so the new files
   are covered by a test whose failure text says `marker/brush.json`).
9. **Grain is only on where it is meant to be.** Exactly **one** shipped preset has
   `tipTexture.enabled == true` or `paperGrain.enabled == true` — Pencil. (In wave two, Smudge may
   join it; the test says `Pencil` and `Smudge` if and only if the folder exists.) A Marker with
   grain is a different brush, and a grain number copied from Pencil is the likeliest mistake in
   this spec.
10. **The version on disk is the lowest that can express the file** (Decision 5). For each preset:
    re-encode the decoded preset with `BrushJson.encode` and assert the `"version"` it writes
    equals the number in the file — the existing round-trip, restated with the version named. Then
    the two that matter: a file using **no** `engine "fill"` and no `blend "behind"` says `1`
    (which on this row's set is Ink, Pencil, Marker, Soft air and Eraser), and `fill` says the
    number in its own file, which is `2` today. **The test reads `BRUSH_VERSION` from
    `cc.joycreator.joybrush.core.brush` and never writes a version literal** — so when wave two
    bumps it, this test needs no edit. It also asserts that at most one shipped file claims any
    given version above 1, which is the assertion that a version bump does not sweep every shipped
    file along with it.

### Additions to `ShippedBrushFilesTest.kt` (the existing file, extended in place)

11. **Widen its id assertion.** It currently asserts only that `joybrush.ink` and `joybrush.pencil`
    are on disk — the literal string is `"the two example brushes must be on disk, found $ids"` at
    line 47. It becomes the full set from Test 1, and the message changes from "the two example
    brushes" to "the shipped brushes", so the failure text stops lying once there are six.
12. **Add the `index.txt` round-trip.** The same test class reads
    `joybrush/brushes/index.txt` through the `joybrushRoot()` walk it already has (line 53) and
    asserts Test 2's two-way set equality and Test 3's exact order. It belongs here rather than in
    the new file because this class is already the one that reads the disk, and a second
    root-finder is a second thing to get wrong.
13. **A folder with no `brush.json` is not a preset and must not fail the walk — but a folder
    somebody made and forgot must.** The existing filter is
    `it.isDirectory && File(it, "brush.json").isFile` (line 25), which silently skips a folder that
    has no `brush.json`. That is correct for a folder holding a `tip.png` and **wrong** for a folder
    somebody created and forgot. Add: every directory under `brushes/` either has a `brush.json` or
    is named in a comment in `index.txt`. This is the cheapest possible "I made a brush and it never
    appeared" guard, and the comment is the escape hatch so a future `assets/` folder does not have
    to be renamed.

**Command:** `./gradlew -p joybrush :core:jvmTest` — BUILD SUCCESSFUL, 0 failures.

### The device check (T3, the owner — this is the row's real ending)

Blueprint Phase 1: *"You judge each brush on the Note 9 and sign it off (or send it back)."* For each
of the **five** in wave one: draw three strokes — light, medium, hard — and one stroke over itself.
The Marker's second pass must not go black. The Soft air must not have an edge. The Eraser must
clear in one pass and must feather at a light touch. **A preset whose numbers validate and whose
brush feels wrong is not done.** Smudge and Nudge get their own device check in wave two.

## Do not

- **Do not create `smudge/` or `nudge/` folders, do not write `"engine": "smudge"` or `"push"`, and
  do not put their names in `index.txt`.** They are wave two (JB-1.06), and
  `BrushValidate.ENGINES` has no `"push"` word today, so the files would be refused by name.
- **Do not edit `ink/brush.json` or `pencil/brush.json`.** They are not in the owner area
  (Decision 3). Pencil's one `"scale"` line is wave two, after JB-1.05c, and the number is the
  owner's to judge with a pencil in his hand.
- **Do not remove, hide or alter the Eraser button.** The Eraser preset wins (Decision 10), and the
  button is **JB-2.01's** edit, in `JoyBrushActivity.kt`, which is not this row's file and is under
  R30 item 1's lock order. Do not touch `JoyBrushActivity.kt` at all.
- **Do not write a version number.** Not in a file, not in a test, not in a comment that reads like
  a rule. Wave one needs no bump (Decision 5), and wave two's number is read from `BRUSH_VERSION`
  at landing (R30 item 3). Do not edit `BrushJson.kt`, `BrushPreset.kt` or `BrushValidate.kt` to get
  one.
- Do not rename or re-id anything that ships, including `fill`'s odd bare `fill` id.
- Do not add a preset to a folder without adding it to `index.txt`, or the reverse. It will pass
  every existing test and never appear.
- **Do not put a source-level test in `commonTest`.** `commonTest` cannot open a file — the
  existing `ShippedBrushFilesTest`'s KDoc says so — and there is no third copy of any preset
  (Decision 8).
- Do not re-type `4096`, `0.04` (the default spacing), `64` (`MAX_GRAIN_SCALE`) or any other number
  that already exists as a constant. `BrushValidate.MAX_SIZE_PX` is public for this.
- Do not put a `version` on a file that its own words do not need (Decision 5).
- Do not add a preset here that the board does not list. "A few excellent brushes, not hundreds."
- Do not add a `size` default, a colour, an `author` or an `extensions` block "while you are there".
  Every key in the three files is one Decision 4 argues for.

## Definition of done (wave one)

- [ ] `./gradlew -p joybrush :core:jvmTest` green (paste the output; 0 failures).
- [ ] `git status --short` shows **only** the six owner-area paths (three new `brush.json`,
      `index.txt`, `ShippedBrushFilesTest.kt`, `DefaultPresetsTest.kt`).
- [ ] Committed as `JB-1.07: default presets (wave one)`, pushed.
- [ ] The owner has drawn with **all five** on the Note 9 and signed off or sent back (T3).
- [ ] `INDEX.md` updated to "Built — awaiting T1 review", and the board note says **wave one of two**
      so nobody reads this row as the whole of the plan.

## Wave two — after JB-1.06 (and JB-1.05c for the pencil)

Not this row. Named here so the second half is a copy-and-paste and not a re-invention, and so the
first half cannot quietly grow into it.

**Owner area grows by:** NEW `joybrush/brushes/smudge/brush.json` · NEW
`joybrush/brushes/nudge/brush.json` · EDIT `joybrush/brushes/index.txt` (two inserted lines) · EDIT
`joybrush/brushes/pencil/brush.json` (**one line**, `"scale"` inside `paperGrain`, and only once
JB-1.05c's shader honours it) · the same two test files.

**The two files.** `Smudge` — `joybrush.smudge`, name "Smudge", `engine: "smudge"`, `size.base 60`,
`hardness 0.4` (soft enough that the smear feathers), `smoothing 0.5` (a smudge looks best on a
smooth path — every wobble is a band), no grain, no scatter, plus whatever smudge numbers JB-1.06
lands (`R38` has ruled the shape: `"smudge": {"strength","pickup","load"}`, plain floats 0..1, one
`SmudgeSpec`). `Nudge` — folder `nudge`, id `joybrush.nudge`, name "Nudge", `engine: "push"`,
`size.base 80`, `flow 1`, `hardness 0.6`, `spacing 0.05`, and
**`"tip": { "followDirection": true }`** — the load-bearing field, and not optional: JB-1.06
Decision 5 pushes along `dab.angle`, and `BrushDabber.kt:117` only adds the direction to the angle
when `followDirection` is true, so a round tip with it false has `angle = 0` and the brush pushes in
one fixed direction no matter how the stroke runs. A person who draws a curve with that brush will
file it as broken. **The file's *engine* word is `"push"` while its folder, id and name are all
"nudge"** — that split is JB-1.06's to make, and it is why `"push"` must be in `BrushValidate.ENGINES`
before the file exists.

**The tests that move to wave two:** Test 1's conditional smudge/nudge clause, Test 3's wave-two
order, Test 4's smudge/nudge half (`engine` words and `tip.followDirection == true`, skipped with a
stated reason if the folder is not there), and Test 9's second allowed grain owner. Test 10 reads
`BRUSH_VERSION` and needs no edit.

**The version.** The two new files carry **whatever `BRUSH_VERSION` is when they are written** — the
builder reads it — and no other shipped file's number moves with them (Test 10's last assertion).

## Questions

_(Spec writer, 2026-09-29; revised against the review, R39 and the landed code the same day.)_

### For the Lead

**Q1 — wave one is five presets and the row is scoped to it (R39).** R39 splits the board row:
Marker, Soft air, Eraser now, Smudge and Nudge after JB-1.06. The tree confirms the split is not a
sequencing preference but a hard block: `BrushValidate.ENGINES` is
`setOf("stamp", "smudge", "wet", ENGINE_FILL)` today, there is no `"push"` word, and `BrushPreset`
has no field for a smudge's strength/pickup/load or a push's amount. JB-1.06's owner area is
precisely the three files that add them, so nothing this row can do shortens the wait. **A spec that
promises seven presets when two are impossible is a spec that stalls**, and the board row keeps
saying seven, so this file says five and names the other two. **I need nothing ruled here** — this
is R39 applied — unless you want wave one to also carry a *stub* folder for Smudge and Nudge, which
I would argue against: a folder with a placeholder `brush.json` is a brush that validates and draws
nothing.

**Q2 — between this row and JB-2.01 there are two controls that erase, and I have not fixed it.**
R39 rules that the preset wins and that the button is removed by **JB-2.01**, not before. The
interim state is real: `JoyBrushActivity.toggleEraser()` sets `erase = true` on the hard-coded
`Brush` and ignores `preset` entirely, so the button and the new preset differ in whether hardness,
flow and grain are honoured. I have deliberately **not** touched `JoyBrushActivity.kt` — it is not in
this row's owner area and it is under R30 item 1's one-row-at-a-time order. The two things I would
like confirmed: (a) JB-2.01's cluster really does carry the button's removal, so it is not left
stranded; and (b) if the owner is going to meet both in the meantime, a one-line temporary note in
the button's content description ("quick erase; the Eraser brush is in the pill") would be a
**JB-2.01** edit and not this row's. Say if you want it the other way.

### Ruled here, low-risk and reversible, flagged for you to confirm

**Q3 — PROVISIONAL (Claude to confirm): Pencil's `"scale"` is not in wave one (Decision 3).** The
earlier draft of this spec said Pencil needed an explicit `paperGrain.scale` "once JB-1.05c lands,
because `scale` is undefined today". That premise was **false against the code** — the field exists,
defaults to `1f`, and is validated — and the *value* is a T1/T3 judgement, not a builder's. R38 has
now ruled JB-1.05c's Q2 (`pitchPx = 64 / scale` document px, fixed physical size), so the definition
is settled and the only thing missing is a number the owner picks while holding a pencil. I have
therefore taken both files out of the owner area for wave one. **If you would rather the number land
now** — it is valid today and would be ready the day the shader does — say so and I will add
`pencil/brush.json` back with an explicit `"scale"` and a stated value, and a test that it is inside
`0 < scale ≤ 64`.

**Q4 — PROVISIONAL (Claude to confirm): wave one is committed as one commit and the row closes as
two.** The alternative is to leave the row open until wave two so the owner's tuning pass happens
once. I chose two commits because blueprint Phase 1's check is per-brush anyway, five brushes is
already a session, and a row that cannot close is a row that cannot be counted. The cost is that the
owner signs off on five brushes twice.

**Q5 — every number in Decision 4 is a proposal, and this is the T1 judgement the row cannot make
for itself.** Specifically: Marker's `corner 4` (a rounded square — the shader's own gloss, but
whether a marker *reads* better than `corner 2.5` on the Note 9 is a look, not a fact), Soft air's
`hardness 0.05` against `0.2`, and Eraser's `hardness 0.5`. They are written so the argument can be
had about the number, and the owner's device check (T3) is what settles them. If you want different
values before a builder runs, they are one number each and Test 4's bounds are read from the decoded
file rather than retyped, so nothing in the suite would need editing.
