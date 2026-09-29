# Adversarial review — JB-2.20a The Studio's 26 blend modes (Kotlin `BlendRgb`, golden parity, renderer wiring)

- Reviewer: claude (second adversarial pass; **muse-spark reviewed this task first** —
  `tasks/joybrush/reviews/JB-2.20a__muse-spark.md`, which filed 1 BLOCKER + 1 MAJOR + 4 MINOR).
  I say AGREE / DISAGREE on each overlap.
- Task status: 🟧 Built. Suite run by me at HEAD: `./gradlew -p joybrush :core:jvmTest` → 659 tests,
  1 failure, **not** in this task (`ThreeFingerSwipeTest`, JB-3.08a). `./gradlew -p joybrush
  :androidkit:compileKotlin` and `:androidkit:test` → **BUILD SUCCESSFUL**.
- Spec reviewed: `tasks/joybrush/specs/JB-2.20a_all_blend_modes.md` (Decisions 1–6, tests 1–3,
  builder Q1–Q4, orchestrator's added-rules bullets).
- §5b: I edited only this file. No git. No source edits.
- Severity: BLOCKER / MAJOR / MINOR per ROADMAP §5b.

**muse-spark's Finding 1 (androidkit red at HEAD) does NOT reproduce at HEAD.** I ran
`:androidkit:compileKotlin` and `:androidkit:test` myself, both green, and `OraExportTest` is
present with `BlendMode.entries`-aware helpers (`OraExportTest.kt:392, :431`). That BLOCKER is
closed by a later commit and I record it as **not reproduced**.

**Verdict: 3 MAJOR, 2 MINOR. All three MAJORs are false claims; none is a wrong pixel today.**

---

## Finding 1 (MAJOR — FALSE CLAIM, no live miscomposite): the "independent GLSL cross-check" cannot
## see a GLSL change at all. The whole second leg of the proof is decorative.

**Files and lines.**
- `tools/gen_blend_golden.sh:16-19` — *"**--model** … re-derive every row from the Studio's GLSL
  **source** (a SECOND, independent transcription, in Python) and diff that against the Java-generated
  table. **Catches the case where `BlendModes.java`'s Java mirror and its GLSL have drifted from each
  other, which no Java-side check can see.**"*
- `tools/blend-golden/glsl_model.py:10-13` — *"It transcribes the OTHER artifact — the
  `GLSL_BLEND_FN` string, the equations a GPU runs — in a third language … **If the Java mirror and
  the shader have drifted apart … this exits non-zero.**"*
- Repeated in the spec at `JB-2.20a_all_blend_modes.md:156-157`.

**Proof by construction.** `glsl_model.py` never opens `BlendModes.java`. Its *only* file read is
`read_table(path)` at `:264` — `text = path.read_text(encoding="utf-8")` — and `main` at `:290-296`
passes `BlendGolden.kt` (`DEFAULT_TABLE`, `:32-35`). The equations are a hand-written Python
function `blend(code, b, s)` at `:190-246`. Nothing parses the GLSL string; nothing hashes it.

**The counterfactual, step by step.** Somebody edits `GLSL_BLEND_FN` in
`app/src/main/java/com/fadcam/ui/faditor/model/BlendModes.java` — the most likely drift of all,
since that string *is* what the GPU runs and the Java `blend()` is only a mirror.

1. `GenBlendGolden` compiles the class and calls `BlendModes.blend(...)` (`:248`). It never reads
   `GLSL_BLEND_FN`. The regenerated table is **byte-identical**.
2. `gen_blend_golden.sh:85` `cmp -s "$REGEN" "$COMMITTED"` → **succeeds** → prints "in sync".
3. `run_model_check` (`:71-74`) runs `glsl_model.py` against the **unchanged committed table**,
   against the **unchanged hand-written Python**. It agrees. **Exit 0.**

So: edit the shader, break every pixel the phone renders, and the entire "one truth, checked"
machinery — generator, drift check, GLSL model, 28 tests — stays green. The model is a
*fourth* hand-transcription pinned to the *same* Java, not an independent witness to the shader.
It does have real value (it caught the ClipColor association difference), but not the value claimed.

**Contradicts the spec?** The spec itself never asked for it — Decision 4 asks only for the
generator and `--check`. The claim was **added** by the builder and then **endorsed by the
orchestrator** in the spec's added-rules bullets. So this is a false claim *added to the spec*; per
§5b item 3 it goes to the **Lead**, and the honest options are (a) delete the "catches GLSL drift"
sentence and keep the model as what it is, or (b) make it real: have `glsl_model.py` (or a new
harness step) *parse* `GLSL_BLEND_FN` out of `BlendModes.java` and fail if the committed table
disagrees with it — which is the only version of this that catches the drift named.

**Severity reasoning.** Not a BLOCKER: no wrong result ships today; the Kotlin genuinely matches the
Java on 92 742/92 742 rows. MAJOR because a false claim about the *proof* of the task's central
guarantee (R23: "a Joy Brush drawing sent to the Studio looks identical there") is precisely the
failure class this project has a documented history of, and because the guarantee it is being cited
for — preview/export parity — is **not** established by it.

---

## Finding 2 (MAJOR — FALSE CLAIM, repeated five times in one file): `render/Blend.kt`'s whole-pixel
## KDoc block describes a design that was not built, and its clamp claim is false three ways

**File:** `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/render/Blend.kt`

| line | the claim | what the code does |
|---|---|---|
| `:139-140` | *"The other twenty are **named in** `[needsWholePixelBlend]` and dispatched to `[BlendRgb]`"* | `needsWholePixelBlend` (`:182-187`) is `when { <7 named> -> false; else -> true }`. It names the **seven**; it names **none** of the twenty. |
| `:74` | *"The **twenty** modes whose answer belongs to the pixel rather than to a channel"* | `apply` returns for `ERASE_BELOW` at `:54-61`, so **19** modes reach `:75`. |
| `:92-93` | *"which is what the **twenty non-separable** modes need"* | **Six** are non-separable: COLOR, DARKER_COLOR, LIGHTER_COLOR, HUE, SATURATION, LUMINOSITY. The other 13 (DIFFERENCE, the dodges/burns, the hard/soft/vivid/linear lights, PIN, HARD_MIX, EXCLUSION, SUBTRACT, DIVIDE) are per-channel separable. |
| `:104-106` | *"Clamping the finished term is **the same clamp the seven separable modes get** below, so **all twenty-seven modes are clamped in exactly one place and in the same way**"* | Only the 19 whole-pixel modes are clamped (`:119`). `term` (`:145-168`) clamps **only ADD** (`min(1f, cs + cb)`); MULTIPLY `cs*cb`, SCREEN `cs + cb − cs*cb`, OVERLAY, DARKEN, LIGHTEN and NORMAL `cs` all return **unclamped**. `ERASE_BELOW` is not clamped at all. |
| `:170-171` | *"True for the **twenty modes that cannot be answered one channel at a time**"* | same two errors as `:74` and `:92-93`. |

**Proof that "non-separable" is false, from the suite's own hard assertion.**
`core/src/commonTest/.../blend/BlendRgbIdentityTest.kt:454-458` lists the separable modes and
`theSeparableModesReallyArePerChannel` (`:453-471`) asserts with `assertEquals(..., 0f)` that each is
**exactly** per-channel:

```kotlin
"NORMAL","MULTIPLY","SCREEN","OVERLAY","ADD","DIFFERENCE","DARKEN","LIGHTEN",
"COLOR_DODGE","COLOR_BURN","LINEAR_BURN","HARD_LIGHT","SOFT_LIGHT","VIVID_LIGHT",
"LINEAR_LIGHT","PIN_LIGHT","HARD_MIX","EXCLUSION","SUBTRACT","DIVIDE",   // 20
```

Thirteen of those twenty are in `Blend`'s whole-pixel list. So the two files assert opposite facts
about the same thirteen modes, and the suite is green.

**And the test file repeats the error with an explicit count.** `RegionRendererTest.kt:931`:

```kotlin
assertEquals(19, wholePixel.size, "nineteen are not, and all nineteen came from JB-2.20a")
```

"nineteen are **not** [separable]" is refuted by `BlendRgbIdentityTest` in the same module.

**The clamp sentence is also self-defeating.** `:102-104` argues that clipping inside the blend term
"is how an exporter stops doing what the Studio does" — and then `:119` clips inside the blend term
for 19 of 27 modes. If that reasoning is right, `wholePixelTerm`'s clamp is wrong; if the clamp is
right, the reasoning is wrong. The two sentences cannot both stand.

**Is the *behaviour* wrong?** No. `BlendRgb` is genuinely unclamped (I mutation-tested it — see
Verified §1), the clamp is at the caller as Q2 prescribed, and `wholePixelTerm` feeds the same `b[i]`
into all three `compositeChannel` calls, which is correct for the six whole-colour modes and
numerically identical for the thirteen separable ones. This is a documentation defect on a
frozen-contract file, five instances.

**AGREE with muse-spark's Finding 3** (they filed the "twenty non-separable" wording as MINOR). I
raise it to MAJOR and add three claims they did not file: the **"named in"** sentence, the
**"twenty" vs nineteen** count, and the **"clamped in exactly one place and in the same way"**
sentence — the last of which is the one a future reader would act on.

---

## Finding 3 (MAJOR — FALSE CLAIM on a frozen contract): "a mode's ordinal IS the Studio's
## `modeCode`" is false, and the divergence starts two constants before `ERASE_BELOW`

**Files and lines.**
- `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/DocModel.kt:106-110`:
  > *"the nineteen below the fold are the Studio's, appended in the Studio's `BlendModes.ALL` order
  > **so that a mode's ordinal IS the Studio's `modeCode`** (JB-2.20a, R23). **That correspondence is
  > the whole point**: the maths in `core/blend` is proved equal to the Studio's Java by a GENERATED
  > golden table rather than copied by eye, **and an ordinal that quietly drifted from `modeCode`
  > would make that table a lie.**"*
- Repeated in the freeze test that exists to stop exactly this:
  `core/src/commonTest/.../doc/EnumFreezeTest.kt:61` — *"The Studio's, in `BlendModes.ALL` order,
  **so ordinal == modeCode** (R23, JB-2.20a)."*

**Proof.** `DocModel.kt:117-124`:

```kotlin
NORMAL, MULTIPLY, SCREEN, OVERLAY, ADD, DARKEN, LIGHTEN, ERASE_BELOW,   // ordinals 0..7
DIFFERENCE, COLOR, COLOR_DODGE, COLOR_BURN, LINEAR_BURN, ...             // ordinals 8..
```

against `BlendRbl.studioCodeOf` (`BlendRgb.kt:77-108`):

| mode | ordinal | `studioCodeOf` | line |
|---|---|---|---|
| `DARKEN` | **5** | **7** | `BlendRgb.kt:96` |
| `LIGHTEN` | 6 | 8 | `BlendRgb.kt:97` |
| `DIFFERENCE` | 8 | 5 | `BlendRgb.kt:82` |
| `COLOR` | 9 | 6 | `BlendRgb.kt:83` |
| `HUE` | 24 | 23 | `BlendRgb.kt:100` |
| `SATURATION` | 25 | 24 | `BlendRgb.kt:101` |
| `LUMINOSITY` | 26 | 25 | `BlendRgb.kt:102` |

So `ordinal == modeCode` fails for **22 of the 27**, and — the part muse-spark's version of this
finding got slightly wrong — the divergence is **not** confined to "the ordinals after
`ERASE_BELOW`". It begins at `DARKEN`, two constants *before* `ERASE_BELOW`, because Joy Brush's
own pre-existing six (`DARKEN`, `LIGHTEN` at 5/6) sit where the Studio puts them at 7/8.

**Blast radius today: none, and I checked rather than assumed.** I grepped every `.kt` under
`joybrush/` (excluding `build/`) for `.ordinal` and `BlendMode.entries`. The only `.ordinal` *write*
in the whole module is `stroke/StrokeCodec.kt:66` — `w.u8(s.tool.ordinal)` — and that is `Tool`, a
different enum with its own freeze test (`ToolOrdinalFreezeTest.kt:6`). No production code reads
`BlendMode.ordinal`; the correspondence is carried name-wise by `BlendRgb.STUDIO_MODES`
(`:57-63`) and `studioCodeOf` (`:77-108`), both pinned by
`BlendParityTest.everyStudioModeIsCarriedOverExactlyOnceAtItsOwnCode` (`:98-114`) against the
table's verbatim `BlendModes.ALL`. So nothing composites wrongly today.

**Why it is still MAJOR.** The sentence is not a stray comment; it is the *justification* the enum
gives for the freeze, and it asserts a specific invariant that is false in a way that is invisible
until it bites. `DocJson` serialises the enum **by name** (kotlinx `enum` default), so no file
carries the ordinal — but the first person who writes `blend(ordinal, …)` against the golden table,
or persists `ordinal` in a `Cel`/archive sidecar, gets a silently wrong blend for 22 of 27 modes,
and the KDoc tells them the correspondence is guaranteed. The claim is also *load-bearing in the
wrong direction*: it says an ordinal that drifted "would make that table a lie", implying the
parity proof rides on the ordinal. It does not — `BlendParityTest` walks by Studio **code**, and
`studioCodeOf` is a name-keyed transcription pinned to the table. So the enum's ordering is
cosmetically `ALL`-shaped and the proof is unaffected, which the KDoc gets exactly backwards.

**AGREE with muse-spark's Finding 2** (they filed it "borderline MAJOR"). I file MAJOR on the
false-claim rule and add the `DARKEN`/`LIGHTEN` detail, which changes the recommended fix: this is
not "state the divergence at `ERASE_BELOW`", it is "there is no ordinal↔code correspondence at all
past the first five, and the proof does not use one".

---

## Finding 4 (MINOR) — **AGREE** with muse-spark Finding 6: `--check` runs the GLSL model only after
`cmp` succeeds, so a simultaneous Java+Python drift reports one half

`tools/gen_blend_golden.sh:85-95`. On a Java-side change `cmp` fails and the script `exit 1`s at
`:94` before `run_model_check` is ever called. The build still goes red, so this is a
diagnosis-quality issue, not a coverage hole. The honest fix is to run both unconditionally — and
per Finding 1 the model half needs rewriting anyway.

## Finding 5 (MINOR, briefing wording only) — the "19 tests that never consult the table" is loose

`BlendRgbIdentityTest` does read the table, for **inputs**:
`:298-300` (`BlendGolden.PAIR_COUNT`, `BlendGolden.BS`) and `:539-542`
(`BlendGolden.FIRST_OUT_OF_RANGE_PAIR`, `BlendGolden.BS`). The file's own KDoc is precise about the
distinction that matters — *"every **expectation** in this file is derived from the DEFINITION of a
mode … and never from the table"* (`:22-24`) — and that is true: no expectation is read from
`EXPECTED`. So this is a nit on my own briefing, recorded so the log does not overstate. **Not a
code finding.**

---

## Verified CORRECT — and I attacked the arithmetic, not just the test count

1. **The Kotlin transcription is a faithful one.** I read all 26 bands in `BlendRgb.kt` against
   `BlendModes.java:348-504` term by term, including operator *order* and associativity: luma
   `0.3/0.59/0.11` (`:374`), the two ClipColor rescales with `cl` measured once (`:386-399`), the
   `hi > lo` vs `>=` SetSat guard (`:323`), DARKER/LIGHTER_COLOR's `<`/`>` tie-to-backdrop (`:300-301`),
   the shared `2bs | 1−2(1−b)(1−s)` OVERLAY/HARD_LIGHT family with the operand swap (`:353-361`), and
   the `b + s >= 1` HARD_MIX threshold (`:274-277`). The final `else -> write(s, out)` at `:342` is
   the Studio's deliberate "not a fall-through". No divergence found.
2. **The golden table is a real observation, not a copy — and I proved it is *sensitive*.**
   `GenBlendGolden.java:248` calls `BlendModes.blend(b, s, code)` on the compiled real class;
   `:269-271` writes `Float.toString` literals (shortest round-tripping decimal, so the Studio's
   bits); `:103-116` asserts `ALL.length == 26` and `modeCode(ALL[i]) == i`.
   I ran the independent Python model myself:
   `python tools/blend-golden/glsl_model.py` →
   *"all 92742 rows agree with an independent transcription of GLSL_BLEND_FN; 92269 of them
   bit-for-bit identical (99.49%); largest difference 1.1920928955078125e-07 at 25 pair 1134
   channel 2"*.
   I then **mutation-tested the table's discriminating power** (my own script, inputs read from the
   committed `BlendGolden.kt`):
   - Changing the Studio's epsilon `1e-5 → 1e-4` moves DIVIDE at pair 1160 by **268 407**.
     `1e-3` → 295 248. `1e-2` → 297 932. `1e-6` → 2 684 069. So the epsilon constant is genuinely
     pinned, and not by one lucky row.
   - Changing SOFT_LIGHT's `D(b)` branch from `bi <= 0.25f` to `bi < 0.25f` (a one-character
     transcription slip) moves the table by **1.458e-4** — 146× the 1e-6 tolerance — at
     pair 1169, `b = [−2.93, 1.55, −1.90]`, `s = [2.04, −1.22, −2.93]`. To `bi >= 0.25f`: **2553**.
     To a 0.5 threshold: **0.617**. So SOFT_LIGHT's 0.25 boundary — which **no in-range row hits**
     (I checked: `BS` contains 0.5/0.0/1.0 1458 times each and 0.25 **zero** times) — is pinned
     *only* by the 60 out-of-range rows. That is a real, non-obvious load-bearing property of the
     orchestrator's added group, and it holds.
3. **The UNCLAMPED contract is genuinely pinned.** `BlendRgb.kt` contains no universal clamp; the
   only `min`/`max` are the Studio's own equation-internal ones. The 60 out-of-range (±3) pairs are
   what make it checkable, and `BlendParityTest.theStudioReturnsUnclampedResultsAndSoMustWe`
   (`:157-179`) asserts >100 values above 1 *and* >100 below 0 among them plus the named
   `SCREEN(2, 0) = 2` case. Without those rows every mode would land in 0..1 and the contract would
   be unpinnable — the orchestrator's reasoning is correct.
4. **The identity suite would survive a wrong table, and mostly does.** I checked each of its four
   claim classes against `BlendRgb.kt` rather than trusting the names: OVERLAY/HARD_LIGHT swap
   (`:66-75`) is broken by a single-operand slip; the HUE/SATURATION/COLOR/LUMINOSITY luminosity and
   saturation invariants (`:216-250`) are gated on `preClipColor` (`:580-610`), a genuinely separate
   third transcription — and the gate is on the *pre-clip* colour, not the recorded result, which is
   the trap the KDoc at `:521-534` describes and which the `used > 200 / skipped > 50` assertions
   (`:550-555`) keep honest; `setSatOnAColourWithNoSpanGivesBlackAndThenTheBackdropsLuminosity`
   (`:406-419`) is the only thing pinning `hi > lo` vs `hi >= lo` (a `>=` would divide by zero);
   `darkerAndLighterColorHandBackOneOfTheTwoColoursByteForByte` (`:368-390`) uses `==` on floats, so
   it is bit-exact, not approximate. **Two reservations, both non-blocking:** the 13 separable
   closed forms in `everySeparableModeMatchesItsClosedForm` (`:162-212`) are verbatim restatements of
   `BlendRgb.kt`'s expressions (SOFT_LIGHT at `:198-203` is the same line of code with the variables
   renamed), so that test is a tautology for a *branch-operator* slip — the golden table is what
   catches it, and per §2 above it does; and the file's own header at `:22-24` is honest that
   expectations come from definitions, not the table, so this is a design choice, not a false claim.
5. **Q2's prescribed design deviation is real and correctly disclosed.** The spec asked the seven
   separable modes to become a `blendRgb` one-liner in `term`; as built, `term`'s seven cases are
   byte-identical old expressions and all 19 new modes route through `wholePixelTerm`. The deviation
   is *value-for-value* and the proof for it is unusually good: `renderTerm`
   (`BlendParityTest.kt:241-247`) reads the renderer's own term by running `Blend.apply` with
   `sa = da = 1`, which reduces the W3C composite to `B` exactly in float, and compares it against
   `BlendRgb` on all 3 567 golden rows for the seven named modes (`:214-232`). That test is
   **non-vacuous** — it would catch a term that moved.
6. **`theEightOriginalModesStillAgreeWithTheRenderer` does not repeat the filter trap.** `:200-212`
   names the seven explicitly and asserts `8 + 19 == BlendMode.entries.size`, with a comment
   (`:195-199`) naming the `entries.filter { it != ERASE_BELOW }` bug by name. Good.
7. **`ERASE_BELOW` is handled before anything else and is refused by both doors.**
   `Blend.kt:54-61` returns destination-out on all four channels before the whole-pixel dispatch;
   `BlendRbl.studioCodeOf("ERASE_BELOW")` throws (`BlendRgb.kt:104-107`), pinned by
   `BlendParityTest.kt:266-269`. The alpha rule (`a = da(1 − sa)`, not `sa + da(1 − sa)`) is
   preserved and is the documented reason erase cannot be a blend term.
8. **The out-of-range code path matches the Studio.** `BlendRgb.kt:342` returns `s` for codes outside
   0..25, pinned by `aCodeOutsideTheStudioRangeYieldsTheSourceLikeTheStudioDoes`
   (`BlendParityTest.kt:278-288`) for −1/26/99; and `studioCodeOf` *throws* for an unknown **name**
   (`:104-107`), which is the deliberate asymmetry the KDoc at `:32-36` explains.
9. **The 1e-6 tolerance relaxation is honest and immaterial.** `BlendParityTest.kt:290` uses
   `1e-6 · max(1, |expected|)`; at the largest out-of-range magnitude (≈ 9) that is 9e-6. Against a
   transcription slip the smallest movement I could produce is 1.458e-4 (`<` vs `<=` on SOFT_LIGHT's
   0.25), i.e. **16× wider than the relaxed bound**, and a band swap is O(1). So the relaxation
   cannot hide anything the spec's flat 1e-6 would have caught. **AGREE with muse-spark Finding 4**
   that it is a MINOR deviation from Decision 5's wording, and add that it is provably safe here.

---

## Recommendation

- **Finding 1 → Lead.** It is a spec-level false claim (the spec's own added-rules bullet repeats
  it), and the fix is a decision: delete the claim, or make the model actually parse
  `GLSL_BLEND_FN`. Do not leave it as a load-bearing sentence in a drift check.
- **Finding 2** — five KDoc corrections in `render/Blend.kt` plus the misleading assertion message
  at `RegionRendererTest.kt:931`. No behaviour change.
- **Finding 3 → Lead** (it is a spec-adjacent contract statement repeated in the freeze test). The
  fix is a paragraph, not a code change, and it must say that the proof does **not** use ordinals.
- Finding 4: reorder the script's two checks. Finding 5: wording only.
- With Finding 1 and 3 corrected, I would consider the parity *methodology* (generated table +
  byte-exact drift check + a separate model + W3C-identity tests that survive a wrong table)
  genuinely strong — it is the right shape for all future Studio-parity work. It is the **claim
  about what the third leg covers** that is not earned.
