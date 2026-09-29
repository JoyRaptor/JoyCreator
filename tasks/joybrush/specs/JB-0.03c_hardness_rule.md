# JB-0.03c — the 0..1 hardness rule lands in `BrushValidate`

| | |
|---|---|
| **Tier** | T2 (tiny) |
| **Status** | 📝 Draft spec |
| **Depends on** | JB-0.03b (`BrushValidate`, Built) |
| **Owner area** | `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/brush/BrushValidate.kt` · `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/brush/BrushTest.kt` · `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/brush/imports/MypaintImportTest.kt` — **nothing else** |
| **Estimated size** | ~10 lines of code, ~35 lines of tests, ~12 lines of test edits |

## Goal

`tip.hardness` is the one `Param` base a person can put any finite number in and Joy Brush accepts:
`"hardness": {"base": 1e30}` is a legal brush file today, and the tip shader silently clamps it to 1
at the canvas. The person is told nothing, and the brush they made is not the brush they get. This
row gives hardness the 0..1 range rule the other dozen numbers already have, in
`BrushValidate` — **not in the ink path** (R37 Q3), so every way a brush is loaded gets it, and the
ink layer inherits it for free.

## Contract (verbatim)

### `core/.../brush/BrushValidate.kt` — the three parts this row touches

```kotlin
    /**
     * The [Param] bases a range rule below already speaks for, so a base that breaks *both* is named
     * once. It is a deny-list on purpose: a [Param] missing from [paramsOf] is not caught by this
     * check, it is caught by nothing — and a [Param] wrongly *left out* of this set only produces a
     * second message, which every `assertSole` in the tests would notice. The trap, therefore, is
     * [paramsOf], not this set.
     */
    private val RANGED_BASES = setOf("size")
```

```kotlin
        // 16 — a number no range speaks for must still be a number. `"hardness": {"base": 1e999}`
        // decodes to +Infinity, which draws nothing and which JSON cannot write back out, so a brush
        // holding one cannot be saved again. Seven bases are in this state (opacity, flow, tip.angle,
        // tip.hardness, the two grain depths, scatter.amount) and this is all that can be asked of
        // them until they are ranged.
        val notNumbers = ArrayList<String>()
        for ((name, param) in paramsOf(p)) {
            if (name in RANGED_BASES) continue
            if (!param.base.isFinite()) notNumbers += "$name.base = ${param.base}"
        }
        if (notNumbers.isNotEmpty()) {
            out += "not a finite number: " + notNumbers.joinToString("; ")
        }
```

```kotlin
    private fun paramsOf(p: BrushPreset): List<Pair<String, Param>> = listOf(
        "size" to p.size,
        "opacity" to p.opacity,
        "flow" to p.flow,
        "tip.angle" to p.tip.angle,
        "tip.hardness" to p.tip.hardness,
        "tipTexture.depth" to p.tipTexture.depth,
        "paperGrain.depth" to p.paperGrain.depth,
        "scatter.amount" to p.scatter.amount,
    )
```

**`paramsOf` already contains `"tip.hardness"`. Do not add it.** The only edit to the two lists is
`RANGED_BASES`.

### The message shape to match — ten existing range rules, all identical in form

```kotlin
        if (p.smoothing !in 0f..1f) out += "smoothing ${p.smoothing} is outside 0..1"
        if (p.tip.minPx !in 0.25f..16f) out += "tip.minPx ${p.tip.minPx} is outside 0.25..16"
        if (p.tip.taper !in 0f..1f) out += "tip.taper ${p.tip.taper} is outside 0..1"
        if (p.grainEdge...) out += "grain edge is outside 0..1: " + ...
```

The pattern is `<name> <value> is outside <lo>..<hi>`, written with `!in` so NaN fails too. `tip.hardness`
is already the name the file uses (`paramsOf` line 259) and the name the rest of the validator uses
(test 3 below: `"tip.hardness input 1 (pressure) has 1000 points, at most 64"`), so the new message
uses `tip.hardness` and not `hardness`.

### `commonTest/.../brush/BrushTest.kt` — the helper and the test this row edits

```kotlin
    private fun preset(build: (BrushPreset) -> BrushPreset): BrushPreset =
        build(BrushJson.decode(inkJson))

    private fun assertSole(problems: List<String>, expected: String) {
        assertEquals(1, problems.size, "expected one message, got $problems")
        assertTrue(problems.single().contains(expected), "message was: ${problems.single()}")
    }
```

```kotlin
    @Test
    fun everyBaseNoRuleRangedMustStillBeANumber() {
        // These seven bases have no range yet (see the table in JB-0.03b), so all that can be asked
        // of them is that they are numbers: 1e999 decodes to +Infinity, which draws nothing and
        // which JSON cannot write back out.
        assertSole(
            BrushValidate.validate(preset { it.copy(tip = it.tip.copy(hardness = Param(Float.POSITIVE_INFINITY))) }),
            "not a finite number: tip.hardness.base = Infinity",
        )
        assertSole(
            BrushValidate.validate(preset { it.copy(tip = it.tip.copy(angle = Param(Float.NaN))) }),
            "not a finite number: tip.angle.base = NaN",
        )
        assertSole(BrushValidate.validate(preset { it.copy(opacity = Param(Float.NEGATIVE_INFINITY)) }), "opacity.base = -Infinity")
        assertSole(BrushValidate.validate(preset { it.copy(flow = Param(Float.NaN)) }), "flow.base = NaN")
        assertSole(
            BrushValidate.validate(preset { it.copy(paperGrain = it.paperGrain.copy(depth = Param(Float.POSITIVE_INFINITY))) }),
            "paperGrain.depth.base = Infinity",
        )
        assertSole(
            BrushValidate.validate(preset { it.copy(tipTexture = it.tipTexture.copy(depth = Param(Float.NaN))) }),
            "tipTexture.depth.base = NaN",
        )
        assertSole(
            BrushValidate.validate(preset { it.copy(scatter = it.scatter.copy(amount = Param(Float.NEGATIVE_INFINITY))) }),
            "scatter.amount.base = -Infinity",
        )
        // size.base is the one base a range does speak for, so the size rule names it instead.
        assertSole(
            BrushValidate.validate(preset { it.copy(size = Param(Float.POSITIVE_INFINITY)) }),
            "size.base must be above 0 and at most 4096, is Infinity",
        )
    }
```

### `core/.../brush/imports/MypaintImport.kt:368-379` — why the rule belongs here and not in the importer

```kotlin
        // ---- tip: hardness, aspect, angle ----------------------------------------------------------
        // `hardness` is "as is" per the spec, and BrushValidate has no range for it — rule 16 only
        // asks for finiteness — so a file can put a number here that is finite and meaningless.
        // Clamping would invent a rendering decision (does jb_tip.glsl saturate above 1, or is it an
        // error?), which is not the importer's to make, and which belongs in BrushValidate with the
        // rest of the range table. Until then the value passes through untouched and the person is
        // told, because silence is the one thing this importer must not do about it.
        val hardness = readSetting(settings, "hardness")?.base ?: 0.9f
        if (hardness < 0f || hardness > 1f) {
            warn(warnings, "hardness $hardness is outside the 0..1 MyPaint documents; " +
                "passed through unchanged")
        }
```

And `MypaintImport.kt:451-457`, the importer's own last chance to be wrong:

```kotlin
        // The importer's own last chance to be wrong. Every value above has already been checked or
        // clamped, so this should always be empty; if it is not, the caller hears about it here
        // rather than finding out when the brush will not save.
        for (problem in BrushValidate.validate(preset)) {
            warn(warnings, "imported brush would be refused: $problem")
        }
```

**`MypaintImport.kt` is NOT edited by this row.** Both blocks above are correct as written; the second
one simply starts firing for hardness too, which is the behaviour this row wants.

### `commonTest/.../brush/imports/MypaintImportTest.kt:666-685` — the test this row rewrites

```kotlin
    /**
     * `hardness` is "as is" per the spec, and `BrushValidate` rule 16 only asks for finiteness, so
     * 1e30 is a legal, finite, meaningless hardness. The importer must not clamp it — that would
     * invent a decision about `jb_tip.glsl` that is not its to make — but it must not be silent
     * either. This test fails when the JB-0.03b range rule lands; delete it then.
     */
    @Test
    fun aHardnessOutsideZeroToOneIsPassedThroughButNotSilently() {
        val wild = convert(file(""""hardness": { "base_value": 1e30, "inputs": {} }"""))
        assertEquals(1e30f, wild.preset.tip.hardness.base, 1e20f)   // untouched
        assertEquals(emptyList(), BrushValidate.validate(wild.preset))  // …and legal, today
        warnsAbout(wild, "hardness", "outside", "passed through unchanged")
        // The three real brushes are all inside 0..1, so none of them gets this warning. (Checked on
        // the message, not on the word "hardness" — the pen does warn about a *dropped hardness
        // input*, and that is a different sentence about a different thing.)
        for (json in listOf(pen, charcoal, basicDigital)) {
            val r = convert(json)
            assertTrue(r.warnings.none { it.contains("passed through unchanged") }, "${r.warnings}")
        }
    }
```

Its KDoc says *"delete it then"*. **This row rewrites it instead — see Decision 8.**

## Decisions already made

1. **The rule is a 0..1 range in `BrushValidate.validate`, numbered 24, with its code at the END of
   the function, after rule 23.**
   *Why:* the file's rules are numbered in the order they were added, and rules 9–23 are each named
   in this file's KDoc, in `JB-0.03b`'s spec and in `BrushTest`'s section comments. Putting a "24"
   between the tip rules and renumbering 9–23 would touch three files this row is not allowed to
   edit and would invalidate a spec the Lead has ruled on. A rule numbered out of physical order with
   one line saying so is cheaper and reversible.
2. **Message: `out += "tip.hardness ${p.tip.hardness.base} is outside 0..1"`.** *Why:* the exact form
   of the ten existing range rules (see the Contract), so a screen that already renders these strings
   renders this one correctly and a person recognises it. **PROVISIONAL — Claude to confirm** (a
   name/wording, not a format).
3. **The test is `!in 0f..1f`, never `< 0 || > 1`.** *Why:* `!in` fails NaN, and the class KDoc at the
   top of this file says so in as many words: *"A range is written `v in lo..hi`, which fails NaN and
   ±Infinity as well as the numbers outside it."* Infinity is outside 0..1 anyway; NaN is not caught
   by `<`/`>` and would slip through to the engine.
4. **`"tip.hardness"` is ADDED to `RANGED_BASES`.**
   *Why:* `RANGED_BASES` exists so a base that breaks two rules is named once. Without it,
   `hardness = +Infinity` produces two messages ("outside 0..1" and "not a finite number"), and
   every `assertSole` in the suite notices a second message. This is the deny-list's documented safe
   trap — a name wrongly *left out* is caught by the tests — and the set's own KDoc says so.
5. **Only the BASE is ranged. A curve's `y` is still not.** *Why:* rule 19 already states the
   position — *"y is the setting's own value, so it is not ranged here — it only has to be a
   number"* — and `TipMath.kt:34` clamps with `tip.hardness.coerceIn(0f, 1f)` at the point of use.
   R37 Q3's "the 0..1 hardness rule" reads as the base, which is how every other `Param` range in
   JB-0.03b's table reads. Ranging curve `y` is a different row and would touch rule 19 and every
   other setting at once.
6. **The validator REFUSES; it never clamps or repairs.** *Why:* the whole file is a list of
   sentences, and `BrushJson.decodeChecked` turns a non-empty list into one `BrushException`. A
   validator that quietly fixed a number would be a second, invisible source of truth.
7. **`BRUSH_VERSION` is NOT bumped, and `BrushPreset.kt` is NOT edited — so `EnumFreezeTest` is not
   touched and is not in the owner area.**
   *Why:* R31's trigger is "ANY new serialised field" and R3's is a new enum constant. **This row adds
   neither.** `tip.hardness` has been a serialised field since JB-0.03; what changes is which values
   this build *accepts*, which is a reader, not a format. Bumping `BRUSH_VERSION` would make every
   brush file an older build had already written fail in that build with "from a newer Joy Brush" —
   a real cost against the owner's own brush library, bought for nothing. It would also move
   `BrushPreset.version`'s default (a second file) and turn `EnumFreezeTest.theVersionsTheNamesWereWrittenFor`
   (line 93, `assertEquals(2, BRUSH_VERSION)`) red.
   **This is PROVISIONAL and it is the one place I may be over-riding the row's own framing** — see
   Questions 1. Per R30 this spec writes no version number. If the Lead rules that a bump is wanted,
   the change is: bump to the next `BRUSH_VERSION`, move `BrushPreset.version`'s default with it, and
   add `EnumFreezeTest.kt` to the owner area.
8. **`everyBaseNoRuleRangedMustStillBeANumber` is edited, not deleted: the hardness case moves out
   and the comment's "seven" becomes "six".**
   *Why:* the remaining six are `opacity`, `flow`, `tip.angle`, `tipTexture.depth`,
   `paperGrain.depth`, `scatter.amount` (counted from the test's own body — it lists exactly those six
   plus hardness plus `size`). Leaving "seven" in place would be a claim the file no longer makes,
   which is the failure mode this repo keeps filing findings about.
9. **`MypaintImportTest.aHardnessOutsideZeroToOneIsPassedThroughButNotSilently` is REWRITTEN, not
   deleted, even though its own KDoc says "delete it then."**
   *Why:* deleting it also deletes the only coverage of `MypaintImport.kt:376-379`, the importer's own
   warning — and that warning still happens and is still right. The new truth is worth *more* than
   the old one: the importer passes `1e30` through untouched, warns that it has, **and** the preset it
   produced is now refused by the validator, so the caller hears "imported brush would be refused:
   tip.hardness 1.0E30 is outside 0..1" as well. A test that pins all three is strictly better than
   no test.
   **This is a deliberate departure from a comment in the landed file. PROVISIONAL — Claude to
   confirm** (Questions 2). It touches only a test, and reverting is deleting one function.
10. **The ink path is not touched** (R37 Q3). *Why:* the ruling places the rule in `BrushValidate`
    deliberately — a rule in the ink path protects one caller and leaves every other way of loading a
    brush (the swatch, an import, a hand-edited file) unprotected.
11. **The three shipped brushes stay clean.** *Why, verified:* of
    `joybrush/brushes/{fill,ink,pencil}/brush.json`, only `ink` carries a `hardness` key at all —
    `"hardness": { "base": 0.95 }` — and `pencil` and `fill` omit the key and take the default
    `Param(0.9f)`. All three are inside 0..1, so `ShippedBrushFilesTest` stays green and no shipped
    file needs editing.

## Steps

1. Write the new tests (below) in `BrushTest.kt` first. Run the command; they are red.
2. Add rule 24 at the end of `BrushValidate.validate`.
3. Add `"tip.hardness"` to `RANGED_BASES`.
4. Update the rule-16 comment: the seven become six, and `tip.hardness` comes out of its list.
5. Edit `everyBaseNoRuleRangedMustStillBeANumber` (Decision 8).
6. Rewrite the MyPaint test (Decision 9).
7. Run the command. Green, including `ShippedBrushFilesTest` and the whole of `MypaintImportTest`.

## Tests

**New, in `commonTest/.../brush/BrushTest.kt`** (use the existing `preset {}` and `assertSole`):

| # | Test name | Input → expected |
|---|---|---|
| 1 | `hardnessOutsideZeroToOneIsRefusedWithTheHouseRangeMessage` | base `0f` → clean; `1f` → clean; `0.5f` → clean. base `-0.001f` → one message containing `tip.hardness` and `outside 0..1`; `1.001f` → same; `2f` → same; `-1f` → same. **Both edges are legal** — a fully soft tip and a fully hard tip are both brushes. |
| 2 | `aHardnessThatIsNotANumberIsCaughtByTheRangeRuleAndNamedOnce` | base `Float.NaN` → **exactly one** message (`assertSole`) and it is the range one. This is the test that catches a `<`/`>` implementation, which lets NaN through both comparisons. |
| 3 | `aHardnessOfInfinityIsNamedByTheRangeRuleAndNotTwice` | base `+Infinity` and `-Infinity` → `assertSole` with the range message, and assert the message does **not** contain `not a finite number`. This is the test that catches a missing `RANGED_BASES` entry (Decision 4): without it there are two messages. |
| 4 | `aHardnessCurveIsStillNotRanged` | base `0.5f` with one input whose curve `y` runs to `3.0f` → **clean**. Pins Decision 5, so a later row that ranges curve `y` has to change this test on purpose. |
| 5 | `anUntouchedBrushStillHasAZeroPointNineHardness` | `BrushPreset(id = "b", name = "B", size = Param(1f))` → `tip.hardness.base == 0.9f` and `validate` is empty. The default is inside the range, so a new brush is never born broken. |

**Edited, in the same file:**

- `everyBaseNoRuleRangedMustStillBeANumber` — delete the `tip.hardness` case, change the comment's
  "seven" to "six", and delete `tip.hardness` from the parenthetical list. Everything else in it is
  untouched and must still pass.

**Rewritten, in `commonTest/.../brush/imports/MypaintImportTest.kt`:**

| # | Test name | Input → expected |
|---|---|---|
| 6 | `aHardnessOutsideZeroToOneIsPassedThroughWarnedAboutAndNowRefused` | `convert(file(""""hardness": { "base_value": 1e30, "inputs": {} }"""))` → `preset.tip.hardness.base == 1e30f` (untouched — the importer still does not clamp); `warnsAbout(…, "hardness", "outside", "passed through unchanged")` still holds; **`BrushValidate.validate(preset)` now returns a NON-empty list** containing `outside 0..1`; and the warnings contain `imported brush would be refused`. The three real brushes (`pen`, `charcoal`, `basicDigital`) still get no `passed through unchanged` warning. |

**Command:** `./gradlew -p joybrush :core:jvmTest` — **0 failures**. This command also runs
`ShippedBrushFilesTest` (jvmTest), which loads every `joybrush/brushes/*/brush.json` from disk and
demands zero problems — so the shipped library is checked by the same run. Paste the output.

## Do not

- **Do not put the rule in the ink path** (R37 Q3 placed it here deliberately), and do not add a
  clamp to `TipMath`, `Dab` or `BrushDabber`. The engine already clamps; that is exactly why the
  person must be told at the door instead.
- **Do not clamp or repair the value in the validator.** A validator that fixes a number is a second
  source of truth. Refuse, in words.
- **Do not renumber rules 9–23.** Rule 16's own comment and JB-0.03b's table depend on those
  numbers. The new rule is 24 and lives at the end.
- **Do not add `MIN_HARDNESS` / `MAX_HARDNESS` constants.** Every other range in this file is two
  literals in one `!in` test. A constant here is a second thing to keep in step for no gain.
- **Do not forget `RANGED_BASES`.** It is a one-word omission that produces a second message and is
  caught by three different `assertSole` calls — you will see it as a failure, not as a silent pass.
- **Do not range the curve's `y`** (Decision 5), and do not touch rule 19.
- **Do not edit `MypaintImport.kt`.** Its warning is right and its `validate` sweep now catches this
  on its own. Editing it is the tempting, wrong move.
- **Do not delete `aHardnessOutsideZeroToOneIsPassedThroughButNotSilently` outright** (Decision 9).
  Its KDoc says to, and the KDoc's reasoning — the rule has landed — is right; its conclusion is
  what this row overrules, because the test's value is now higher than it was.
- **Do not touch `BrushPreset.kt`, `BrushJson.kt`, `EnumFreezeTest.kt` or `DocModel.kt`.** In
  particular, do **not** bump `BRUSH_VERSION` (Decision 7) and do not write a version number here.
- **Do not add a `wordsNeedingVersion` entry for hardness.** It is not a new word in the file; it is
  a range on a word that has been there since version 1. Adding it would say "this file is older than
  a word it is using", which is false.
- **Do not fix the other six unranged bases** (`opacity`, `flow`, `tip.angle`, the two grain depths,
  `scatter.amount`) in passing. JB-0.03b Q3 asked about them as a group; that is a Lead decision
  and this row is one rule.

## Definition of done

- [ ] New tests 1–5 in `BrushTest.kt`; rewritten test 6 in `MypaintImportTest.kt`; edited
      `everyBaseNoRuleRangedMustStillBeANumber`.
- [ ] `./gradlew -p joybrush :core:jvmTest` — 0 failures, including `ShippedBrushFilesTest` and the
      other 30-odd `MypaintImportTest` cases. **Paste the output.**
- [ ] Test 3 is green — it is the one that proves `RANGED_BASES` was updated.
- [ ] The rule-16 comment no longer claims seven unranged bases, and no longer lists `tip.hardness`.
- [ ] `git status --short` shows **only** the three files in the owner area. **Paste it.**
- [ ] Committed as `JB-0.03c: the 0..1 hardness rule in BrushValidate`; pushed.
- [ ] ROADMAP row → 🟧 Built.

## Stop rule

**Stop and write the question in Questions if any of these is true. Do not guess:**

- A **shipped** brush file fails validation once the rule lands. `ShippedBrushFilesTest` goes red and
  the cure is never to widen the range — the cure is to decide, in the open, what a shipped file with
  an illegal number should do. That is a file-format decision and it is the Lead's.
- Any existing `MypaintImportTest` case other than the one in Decision 9 goes red. Each one is a
  pinned fact about the importer, and "just update the expectation" is how a wrong expected number
  becomes permanent (R9).
- You cannot express the rule without editing `BrushPreset.kt` or `BrushJson.kt`. The scope of this
  row was chosen so that neither is needed; if it turns out they are, the row is bigger than the
  Lead thinks and it should be re-scoped rather than quietly widened.
- The Lead's answer to Question 1 is that `BRUSH_VERSION` **does** move. Stop, re-read Decision 7's
  last paragraph, and do not start until the owner area is updated — `EnumFreezeTest.kt` will go red
  and that is the row telling you the scope is wrong.

## Questions

### For the Lead

1. 🔴 **Does this row move `BRUSH_VERSION`?** The framing of the row says "work out what it does to
   `BRUSH_VERSION` and say `bump to the next BRUSH_VERSION`", and my reading of the rulings is that
   it should **not** move: R31's trigger is a new *serialised field*, R3's is a new enum constant, and
   this row adds neither — it makes an existing field's range narrower. **My recommendation is no
   bump** (Decision 7): bumping makes every brush file the owner has already saved fail in an older
   build with "from a newer Joy Brush", costs a second file (`BrushPreset.version`'s default), and
   turns `EnumFreezeTest.theVersionsTheNamesWereWrittenFor` red for no gain. If the Lead rules that a
   stricter reader is itself a format change, the whole scope changes with it.
2. 🟠 **The MyPaint test: rewrite or delete?** Its landed KDoc says *"This test fails when the JB-0.03b
   range rule lands; delete it then."* I am rewriting it instead (Decision 9), because deleting it
   also deletes the only coverage of the importer's own warning. Confirm the rewrite, or overrule me
   and it gets deleted.
3. **Is the message wording right?** `"tip.hardness 1.5 is outside 0..1"` — it matches the ten existing
   range messages exactly (Decision 2). Low-risk, but it is a user-visible string and there is no
   reason for a T2 builder to be the last person to touch it.
4. **Should the rule land in the same commit as the range for `opacity` and `flow`?** JB-0.03b Q3
   raised all seven together. This row is deliberately one of them (R37 Q3 called it tiny). If the
   Lead wants the other two, that is a different row, not a bigger version of this one.
