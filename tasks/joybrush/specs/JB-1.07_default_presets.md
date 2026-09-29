# JB-1.07 — The default presets: Ink, Pencil, Marker, Soft air, Smudge, Nudge, Eraser

| | |
|---|---|
| **Tier** | T1 (the numbers) + T3 (the owner signs them off on the Note 9 — blueprint Phase 1's owner check is literally "You judge each brush on the Note 9 and sign it off") |
| **Status** | 🟨 Draft — **two of the seven cannot be written at all** until JB-1.06's file format is ruled (Q1), the Eraser duplicates a control that already exists (Q2), and the sizes will be wrong the moment JB-2.16 lands (Q3). Decisions 1–9 are complete and buildable now for the five that can be written |
| **Needs** | 1.05, 1.06 |
| **Owner area** | NEW `joybrush/brushes/marker/brush.json`, `softair/brush.json`, `eraser/brush.json` (+ `smudge/`, `nudge/` when Q1 lands) · EDIT `joybrush/brushes/index.txt` · EDIT `joybrush/brushes/pencil/brush.json` (**one line only** — Decision 3) · EDIT `joybrush/core/src/jvmTest/kotlin/cc/joycreator/joybrush/core/brush/ShippedBrushFilesTest.kt` · NEW `joybrush/core/src/jvmTest/kotlin/cc/joycreator/joybrush/core/brush/DefaultPresetsTest.kt` |
| **Estimated size** | ~110 lines of JSON + ~200 lines of tests |

## Goal

Blueprint §1 idea 7, and the owner's own words: *"A few excellent brushes, not hundreds."* Seven
presets ship with the app and a person never has to make a brush to make a picture. Today three
folders exist — `ink`, `pencil`, `fill` — and the `fill` one is the fill pen (JB-1.08a), which is
**not** in this row's list and is not touched. This spec writes the five that are missing from the
two that are not, pins all seven so a later edit cannot quietly turn a Marker into a second Airbrush,
and fixes the order, because **the order in `index.txt` is the order in the brush pill and the first
one is the brush the screen opens with** (Decision 2).

## Contract (verbatim)

Everything the files must say. `BrushPreset` (JB-0.03 / JB-1.08a) is unchanged by this spec.

```
joybrush/brushes/index.txt      (in this order, and this order is the product)
  ink          <- line 1: the brush the Joy Brush screen opens with
  pencil
  marker
  softair
  smudge       <- Q1: cannot be written until JB-1.06's format is ruled
  nudge        <- Q1: same. Folder, id and name all "nudge"; the FILE's engine word is "push"
  eraser
  fill         <- JB-1.08a, already there, untouched
```

## Steps

1. Write `DefaultPresetsTest.kt` first, from the Tests section. It will fail: three of the folders do
   not exist.
2. Write the three `brush.json` files that can be written, then the `index.txt`. (Five minus the two
   blocked by Q1: Marker, Soft air, Eraser. Ink and Pencil already exist.)
3. Extend `ShippedBrushFilesTest` (Tests 11–13).
4. `./gradlew -p joybrush :core:jvmTest` — green, output pasted.
5. The owner tries all seven on the Note 9 and says which ones are not good enough (T3). **A preset
   is not signed off because its numbers validate. It is signed off because a person drew with it.**

## Decisions

1. **Seven files ship, and the eighth (`fill`) is not one of them.** The board row's list is Ink,
   Pencil, Marker, Soft air, Smudge, Nudge, Eraser. `fill` is the fill pen, which LEAD_RULINGS R21
   made **a brush rather than a tool** and which already exists at `joybrush/brushes/fill/` with
   its own id (`fill`, not `joybrush.fill` — an inconsistency with the other two that I am **not**
   fixing here, because renaming a shipped id is a compatibility decision, not a tidy-up). It goes
   in the picker, at the end, because it is a different kind of thing from a texture brush. If you
   would rather the list were six and the fill pen were somewhere else, say so; it is a one-line
   change to `index.txt`.

2. **`index.txt` is the product, not a manifest.** `BrushLibrary.builtIn()` reads it in order and
   the pill cycles in that order, and `JoyBrushActivity` does `canvas.preset =
   brushes.firstOrNull()` on `onCreate` — **so the first line is the brush a person is holding the
   moment the screen opens, and the file's order is what they tap through.** Two consequences that
   are worth stating because neither is written down anywhere:
   - Line 1 is Ink. It is the brush that was already first, it is the one the owner's phase checks
     have been against, and it is the least surprising thing to open on.
   - Smudge and Nudge sit **after** the four painting brushes and before the Eraser, because they
     change what is on the canvas rather than add to it, and the Eraser is last because it is the
     only one that removes.
   The existing header comment at the top of `index.txt` is kept, and the one line this spec adds to
   it is: *the order here is the brush pill's order, and the first line is what the screen opens
   with.*

3. **Ink is not edited, and Pencil gains at most one line.** Both are `🟧 Built` / `🟩 Reviewed`
   and are what the owner has been signing off against; re-tuning them is a deliberate act, not a
   side effect of adding neighbours. The **one** exception is Pencil: once JB-1.05c lands, its
   `paperGrain` block stops being a file that validates and draws nothing and starts drawing, and
   `scale` is undefined today (JB-1.05c Q2, Decision 6 there), so the pencil will need an explicit
   `"scale"` to have any grain at all. **That one field is the only permitted edit to either file,
   and it is a change of one number.** Everything else about Ink and Pencil is exactly as it is.

4. **Every number below carries its reason, and the numbers are proposals, not rulings.** The tuning
   is T1's to set and the owner's to judge; what this spec owes is that every number is *argued*,
   so the argument can be disagreed with instead of the number being retyped. The two governing
   facts a preset author has to work with:
   - **`accumulate` is the single biggest decision in a preset.** In `wash`, a stroke's contribution
     is capped at its own opacity, so the stroke never goes darker where it crosses itself — that
     is what makes Ink and Marker read as ink and marker. In `buildup`, dabs inside one stroke pile
     up and the opacity is applied once at commit, which is what makes an airbrush an airbrush
     (JB-1.04 Decision 7, which is what `BrushDabber` implements in its `look`).
   - **`flow` is per dab and `opacity` is the ceiling.** A soft brush wants a *low* `flow` and a
     *high* `opacity`; a hard one wants a high `flow`. Confusing the two is how an airbrush ends up
     with a chalky edge.
   What follows is the proposal, one line of reason each.

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
   `corner 4` is a rounded square (jb_tip.glsl: 2 = circle, 4 ≈ rounded square, 16+ ≈ square) — a
   perfect circle reads as a pen, not a marker. The pressure curve bottoms out at 0.75 because a
   marker does not taper, it just presses. `wash` + `opacity 0.9` is the whole marker trick: the
   stroke cannot exceed 90 % and cannot darken on itself. `flow 0.7` so the colour arrives in the
   first centimetre rather than fading in. No grain, no scatter, no jitter.

   **Soft air** — `joybrush.softair`, name "Soft air". The only shipped preset that is
   `buildup`, because it is the only one that *should* get darker where it passes twice.
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
   `hardness 0.05` is "soft all the way from the centre" (jb_tip.glsl's own wording), not a
   hard-ish edge. `flow 0.05` is the number that makes it an airbrush: a hundred overlapping dabs
   build to about 1, and one dab is invisible. `buildup` so they do build. `spacing 0.02` so the
   build is smooth rather than banded. The pressure curve starts at 0.2, not 0, because a
   pressure-less finger (which the app supports, R7 §1.5) must still get an airbrush and not a dot.

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
   `blend: "erase"` is the word the engine already understands (JB-0.07's `StrokeBlend.ERASE`), and
   `JbCanvasView.beginStrokeNow` already reads it off the preset
   (`strokeErase || (p != null && p.blend == "erase")`, landed by JB-1.05b). `flow 1` +
   `opacity 1` so one pass clears; `hardness 0.5` so a light touch feathers instead of cutting a
   hole with a hard disc. **See Q2 — this preset currently does the same job as the Eraser button on
   the screen, by a different route.**

   **Smudge** *(Q1 — the file cannot be written until the format is ruled)* — `joybrush.smudge`,
   name "Smudge", `engine: "smudge"`, `size.base 60`, `hardness 0.4` (soft enough that the smear
   feathers), `smoothing 0.5` (a smudge looks best on a smooth path — every wobble is a band), no
   grain, no scatter, plus the three smudge numbers.

   **Nudge** *(Q1 — the file cannot be written until the format is ruled)* — folder `nudge`, id
   `joybrush.nudge`, name "Nudge", `engine: "push"`, `size.base 80`, `flow 1`, `hardness 0.6`,
   `spacing 0.05`, **`"tip": { "followDirection": true }`** — the load-bearing field, and not
   optional: JB-1.06 Decision 5 pushes along `dab.angle`, and with `followDirection` false a round
   tip has `angle = 0`, so the brush pushes in one fixed direction no matter how the stroke runs. A
   person who draws a curve with that brush will file it as broken. Test 4 pins it. The file's
   *engine* word is `"push"` while its folder, id and name are all "nudge"; JB-1.06's Q3 is where
   that split gets a ruling.

5. **Every shipped file carries the LOWEST version that can express it, and that is a test.** `ink`,
   `pencil`, `marker`, `softair` and `eraser` are version **1** files and stay readable by a build
   that predates the fill pen; `fill` is version 2 (JB-1.08a); `smudge` and the Nudge preset are
   version 3 (JB-1.06 Decision 8). This is `BrushJson.versionFor`'s rule and
   `ShippedBrushFilesTest` already round-trips the object; what this spec adds is a test that the
   **number on disk** is the lowest legal one, because a hand-edited file that claims `version: 3`
   while using no v3 word is a file that a future build cannot tell from a real one.

6. **Every preset is defined by a property, and the property is what the tests assert.** Not "the
   Marker's flow is 0.7" but "**the Marker cannot go darker than 0.9, ever**" and "**one Soft air
   dab is invisible and a hundred of them are not**". The numbers are the means; these are the
   things that make a brush that brush, and a test written on the property survives a tuning pass
   where a test written on the number does not. That is the difference between a suite that catches
   "someone made the airbrush opaque" and one that has to be rewritten by the person who did it.

7. **A preset folder on disk that is not in `index.txt` is invisible, and nothing catches it today.**
   `ShippedBrushFilesTest` walks the *folders*; `BrushLibrary` reads the *file*. A folder nobody
   listed validates, round-trips and is never shown to anybody — which is how a brush can be
   "shipped" for three phases and never once seen. Three tests close this from both directions, and
   they are the most valuable ones in this spec.

8. **No third copy of a preset file.** `commonTest/…/brush/BrushTest.kt` carries byte-identical
   inline copies of `ink` and `pencil`, because `commonTest` cannot open a file. That trick is the
   first two presets only: `ShippedBrushFilesTest`'s own KDoc already names the drift it causes.
   **The new tests read the files from disk** (they are `jvmTest`, where `java.io` is allowed) and
   the existing inline copies are left exactly as they are. Do not add copies for the new presets.

9. **Size is a document-px number and the range is `BrushValidate.MAX_SIZE_PX`, imported.** No
   preset hard-codes 4096; the assertion uses the constant (LEAD_RULINGS R19 made it public for
   exactly this). And note what the size numbers in Decision 4 mean today: **nothing on screen scales
   `size.base` yet** (JB-1.05b's Q5, still open), so these are the on-screen sizes at zoom 1 and
   switching brushes changes the apparent size. See Q3 — this is the one number in every file that
   is certain to move.

## Tests

`DefaultPresetsTest.kt` (NEW, `jvmTest/…/brush/`) reads every preset **from disk** and pins the
property, not the number, wherever a property exists. Tests 11–13 are additions to the existing
`ShippedBrushFilesTest.kt`, which is already the class that walks the disk.

1. **The set, exactly.** The ids on disk are exactly `joybrush.ink, joybrush.pencil,
   joybrush.marker, joybrush.softair, joybrush.eraser, fill` — plus `joybrush.smudge` and
   `joybrush.nudge` **if and only if** those two folders exist, and the test says so in its failure
   message rather than assuming. (So the suite is green both before and after Q1, and turning red
   when a folder is deleted from one place and not the other.)
2. **The names are the seven the board row names**, and `index.txt` lists every folder and every
   folder is in `index.txt` — both directions, as sets, ignoring blank lines and the `#` comment
   (Decision 7). This is the test that makes a preset real.
3. **Line 1 of `index.txt` is `ink`** (Decision 2), and the whole order is
   `ink, pencil, marker, softair, smudge, nudge, eraser, fill` with any folder that does not exist
   yet removed from **both** sides of the comparison. Asserted as the exact sequence, because the
   order *is* the product.
4. **The defining property of each brush, through `BrushDabber`** (Decision 6). For each preset,
   decode it, build a `BrushDabber` with a fixed seed, place a straight 100-sample stroke through
   a `DabPlacer`, and assert:
   - **Ink**: `accumulate == "wash"`, so every dab's `cap` equals the stroke's opacity and the cap
     is the *same* on the first and the hundredth dab — ink does not darken on itself.
   - **Marker**: `cap ≤ 0.9` on every dab, and `flow ≥ 0.5` so the colour arrives at once (a Marker
     with a soft flow is a pale pen).
   - **Soft air**: `accumulate == "buildup"`, so **every** dab's `cap` is exactly `1f`, and
     `strokeOpacity` equals the opacity evaluated at the first dab. Then the real airbrush
     assertion, which is a two-line arithmetic the test writes out: the sum of
     `1 − (1 − flow)ⁿ` over the stroke's dab count is `> 0.9` and the **first** dab alone is `< 0.1`
     — one dab is invisible, the whole stroke is solid. If someone raises `flow` to make the airbrush
     "stronger", this goes red and says which half broke.
   - **Eraser**: `blend == "erase"` and `flow == 1f` and `opacity.base == 1f`, so one pass clears.
   - **Pencil**: `paperGrain.enabled == true`, `tipTexture.enabled == false`, and
     `paperGrain.tiltGradient > 0f` — the three fields the file exists for, and the third is the
     owner's own idea and the reason JB-1.02 exists.
   - **Smudge / Nudge**, when they exist: `engine` is `"smudge"` / `"push"` respectively, and the
     Nudge preset's `tip.followDirection` is `true` (Decision 4). The test skips with a stated
     reason if the folder is not there yet.
5. **Size and range.** Every preset's `size.base` is `> 0f` and `<= BrushValidate.MAX_SIZE_PX` —
   the constant, imported, not retyped (Decision 9) — and `flow.base` and `opacity.base` are in
   `0..1` on every preset. The names and values are in the failure message, so a bad edit says which
   brush and which number.
6. **Nothing shares an id, and nothing shares a folder** (already partly in `ShippedBrushFilesTest`;
   kept because two presets with one id makes the second unreachable in the pill, and the failure is
   a missing brush rather than an error).
7. **No preset is a copy of another with a different name.** For every pair of presets, at least one
   of `{engine, accumulate, blend, tip.corner, tip.hardness.base, flow.base, size.base}` differs.
   This is the test that catches "the Marker is the Soft air with the size changed", which is the
   exact shape a rushed preset takes, and which no validator would ever speak to.
8. **Every preset validates clean, and the message names the file** (the existing
   `ShippedBrushFilesTest` assertion; restated here only so the new files are covered by a test
   whose failure text says "marker/brush.json").
9. **Grain is only on where it is meant to be.** Exactly one shipped preset has
   `tipTexture.enabled == true` or `paperGrain.enabled == true` — Pencil (and, after Q1, possibly
   Smudge). A Marker with grain is a different brush, and a grain number copied from Pencil is the
   likeliest mistake in this spec.
10. **The version on disk is the lowest that can express the file** (Decision 5). For each preset:
    re-encode the decoded preset with `BrushJson.encode` and assert the `"version"` it writes equals
    the number in the file, and that a file using no v2 and no v3 word says `1`. `fill` says `2`.
    When the v3 words exist, a smudge file says `3` and an ink file still says `1` — the assertion
    that a version bump does not sweep every shipped file along with it.

### Additions to `ShippedBrushFilesTest.kt` (the existing file, extended in place)

11. **Widen its id assertion.** It currently asserts only that `joybrush.ink` and `joybrush.pencil`
    are on disk ("the two example brushes must be on disk"). It becomes the full set from Test 1,
    and the message changes from "the two example brushes" to "the shipped brushes", so the failure
    text stops lying once there are eight.
12. **Add the `index.txt` round-trip.** The same test class reads `joybrush/brushes/index.txt`
    (the same `joybrushRoot()` walk it already has) and asserts Test 2's two-way set equality and
    Test 3's exact order. It belongs here rather than in the new file because this class is already
    the one that reads the disk, and a second root-finder is a second thing to get wrong.
13. **A folder with no `brush.json` is not a preset and must not fail the walk.** The existing
    filter is `it.isDirectory && File(it, "brush.json").isFile`, which silently skips a folder that
    has no `brush.json` — which is the correct behaviour for a folder holding a `tip.png`, and
    **wrong** for a folder somebody created and forgot. Add: every directory under `brushes/`
    either has a `brush.json` or is named in a comment in `index.txt`. This is the cheapest possible
    "I made a brush and it never appeared" guard, and the comment is the escape hatch so a future
    `assets/` folder does not have to be renamed.

**Command:** `./gradlew -p joybrush :core:jvmTest` — BUILD SUCCESSFUL, 0 failures.

### The device check (T3, the owner — this is the row's real ending)

Blueprint Phase 1: *"You judge each brush on the Note 9 and sign it off (or send it back)."* For each
of the seven: draw three strokes — light, medium, hard — and one stroke over itself. The Marker's
second pass must not go black. The Soft air must not have an edge. The Smudge must move colour and
leave the rest alone. The Nudge must push along the stroke and not sideways. **A preset whose
numbers validate and whose brush feels wrong is not done.**

## Do not

- Do not re-tune `ink/brush.json` or `pencil/brush.json` beyond Pencil's one `scale` line (Decision 3).
- Do not rename or re-id anything that ships, including `fill`'s odd bare `fill` id.
- Do not add a preset to a folder without adding it to `index.txt`, or the reverse. It will pass every
  existing test and never appear.
- Do not add an inline copy of a new preset to `commonTest` (Decision 8).
- Do not re-type `4096`, `0.04` spacing defaults, or any other number that already exists as a
  constant. `BrushValidate.MAX_SIZE_PX` is public for this.
- Do not put a `version` on a file that its own words do not need (Decision 5).
- Do not add a preset here that the board does not list. "A few excellent brushes, not hundreds."

## Definition of done

- [ ] `./gradlew -p joybrush :core:jvmTest` green (paste the output).
- [ ] `git status --short` shows only owner-area files.
- [ ] Committed as `JB-1.07: default presets`, pushed.
- [ ] The owner has drawn with all seven on the Note 9 and signed off or sent back (T3).

## Questions

_(Spec writer, 2026-09-29. `⚪ Outline` row: "JB-1.07 Default presets: Ink, Pencil, Marker, Soft
air, Smudge, Nudge, Eraser | T1 + T3 tuning | 1.05, 1.06". Decisions 1–9 are done. The three below
are why this is a Draft.)_

**Q1 — two of the seven cannot be written, because JB-1.06's file format is not ruled.** `Smudge`
and `Nudge` are the row's own two tools, and `BrushPreset` has **no field for either** — no
`strength`, no `pickup`, no `load`, no push `amount` (JB-1.06 Q1 proposes a shape and asks you to
rule on it). Two ways forward, and this is your call because it is a sequencing decision:
- **(a) Two waves.** Ship Ink/Pencil/Marker/Soft air/Eraser now, and Smudge/Nudge as a two-file
  follow-up the moment JB-1.06's format lands. The row closes as two commits. Tests 1 and 4 are
  already written to be green either way.
- **(b) Hold the row** until JB-1.06 is ruled, so the owner tunes all seven in one sitting on the
  phone — which is better for the tuning and worse for the runway.
I have written the tests for (a) and the spec so that (b) costs nothing later. **My recommendation
is (a)**, because the owner's phase-1 check is per-brush anyway and five brushes is already a
session.

**Q2 — the Eraser preset duplicates the Eraser button that is already on the screen, by a different
route, and only one of them respects a brush file.** `JoyBrushActivity.toggleEraser()` does
`canvas.brush = canvas.brush.copy(erase = true)`, which sets the flag on the **hard-coded** `Brush`
— so it is the JB-0.05 path and it ignores `preset` entirely. A brush file with `"blend": "erase"`
goes through `JbCanvasView.beginStrokeNow`'s `strokeErase || (p.blend == "erase")`, which is the
JB-1.05b path. So a person would have two controls that erase, differing in whether the rest of the
brush (hardness, flow, grain) is honoured. Options: (a) the **button** wins and the Eraser preset
is not shipped (the button already sets size, since `Brush.sizePx` is what it scales); (b) the
**preset** wins and the button is replaced by picking the Eraser in the brush pill, which is one
fewer control and the one the blueprint's "one engine, many presets" idea is really about;
(c) both, and the button is redefined as a temporary override of the current preset's `blend`. I
have shipped the file as (b)-shaped and left the button alone, because the button is an app-file
control and this spec is not.

**Q3 — every `size.base` in this spec is a number that is about to stop being true.** Nothing on
screen scales `size.base` yet, so today it *is* the on-screen size at zoom 1 and switching brushes
changes the apparent size (JB-1.05b Q5, still open). JB-2.16a has since ruled the direction
(LEAD_RULINGS R10): the size control shows the on-screen circle at its true screen size and writes
`size.base` in document px, screen px ÷ zoom. So the question is whether these presets should carry
a **size at all** as an authored default — they must, `BrushPreset.size` has no default and the file
cannot decode without it — and whether JB-2.16 should treat a preset's `size.base` as a *starting*
value that the first size drag replaces (my reading: yes) or as something the control then scales
around. Nothing here blocks the files from being written; it is recorded because seven `size.base`
numbers are the most retyped numbers in the project and a ruling now saves a sweep later.
