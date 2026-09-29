# JB-2.20a — All 26 of the Studio's blend modes in Joy Brush's document and export maths

| | |
|---|---|
| **Tier** | T2 |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-2.13a (`Blend`, `RegionRenderer`), JB-0.02b (`EnumFreezeTest`) — Built |
| **Owner area** | EDIT `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/DocModel.kt` (append enum constants, `DOC_VERSION = 2`), `.../doc/DocJson.kt` (accept 1 and 2), `.../render/Blend.kt`; EDIT `.../commonTest/.../doc/EnumFreezeTest.kt`; NEW `tools/blend-golden/GenBlendGolden.java` + `tools/gen_blend_golden.sh`; NEW (generated, committed) `.../commonTest/.../render/BlendGolden.kt`; NEW `.../commonTest/.../render/BlendParityTest.kt` |
| **Estimated size** | ~200 lines + a generated table |

## Goal
Joy Brush layers should offer the same blend modes as the Studio, computed by the SAME equations, so
a Joy Brush drawing sent to the Studio (JB-3.07) looks identical there. The Studio's single authority
is `model/BlendModes.java` (GLSL + a Java reference, `BlendModes.blend(b, s, mode)`). Joy Brush's
export maths is Kotlin common code (it must stay pure Kotlin for the iOS door), so it cannot CALL the
Java — instead it is **proven equal to it** by a generated golden table, and a script regenerates the
table whenever the Studio's equations change. One truth, checked, not copied by eye.

## Decisions
1. **Enum:** `BlendMode` keeps its 8 constants in order and APPENDS the Studio's other 19, in the
   Studio's code order: DIFFERENCE, COLOR, COLOR_DODGE, COLOR_BURN, LINEAR_BURN, HARD_LIGHT,
   SOFT_LIGHT, VIVID_LIGHT, LINEAR_LIGHT, PIN_LIGHT, HARD_MIX, EXCLUSION, SUBTRACT, DIVIDE,
   DARKER_COLOR, LIGHTER_COLOR, HUE, SATURATION, LUMINOSITY. (Check against `BlendModes.modeCode`'s
   switch; if the Studio has one this list misses, include it and say so.) `ERASE_BELOW` stays
   Joy Brush's own (the Studio has no equivalent).
2. **Version (LEAD_RULINGS R3):** new constants ⇒ `DOC_VERSION = 2`. `DocJson.decode` accepts 1 and 2
   (a v1 document is read unchanged); `encode` writes 2. `EnumFreezeTest` is updated deliberately:
   the new list and `DOC_VERSION == 2`.
3. **Blend.kt:** the compositing formula (W3C source-over with a blend term) is unchanged. The TERM
   becomes an RGB function `blendRgb(mode, b: straight rgb backdrop, s: straight rgb source) →
   rgb`, UNCLAMPED — the exact contract of `BlendModes.blend` — then clamped 0..1 by the caller, as the
   Studio's GLSL callers do. Separable modes may stay per channel; HUE/SATURATION/COLOR/LUMINOSITY and
   DARKER/LIGHTER_COLOR are whole-colour (SetLum/SetSat/ClipColor, as in `BlendModes`).
4. **Golden table:** `tools/blend-golden/GenBlendGolden.java` compiles against
   `app/src/main/java` (or `studiokit/src/main/java` after D.05 — accept either path), draws 400
   (b, s) pairs from a fixed-seed `java.util.Random(20260929)` plus the 27 corner cases
   {0, 0.5, 1}³, runs `BlendModes.blend` for every Studio mode, and writes `BlendGolden.kt`: a Kotlin
   `object BlendGolden { val cases: FloatArray; … }` with mode code, b, s and expected rgb as float
   literals (`toString()` of the Java float — exact). `tools/gen_blend_golden.sh` builds and runs it;
   `--check` regenerates to a temp file and exits non-zero if it differs from the committed one (a
   drift check like `check_joybrush_tokens.py`).
5. **Parity test:** for every row, Joy Brush's `blendRgb` equals the table within 1e-6 (the Java
   and Kotlin float maths are the same IEEE operations; a larger difference is a transcription bug).
6. This spec is the CPU (export) half. **Preview must agree with export**: the GL engine's layer
   compositing is the Lead's **JB-2.20b** (it will compile the Studio's `GLSL_BLEND_FN`). Until
   JB-2.20b lands, no UI may offer a mode other than NORMAL (the layers panel JB-2.04 depends on 2.20b).

## Tests
1. `BlendParityTest`: every golden row, every mode.
2. `EnumFreezeTest` updated; a v1 document with `"blend": "MULTIPLY"` still decodes; a document with
   `"blend": "HUE"` encodes as version 2 and round-trips; a v2 document is refused by nothing here.
3. Existing `RegionRenderer` / `Blend` tests for the 8 original modes still pass unchanged (the
   8 must not move — if one does, the Studio and Joy Brush disagreed before, and that is a finding
   for the Lead, not something to "fix" quietly).

**Commands:** `bash tools/gen_blend_golden.sh --check` exits 0; `./gradlew -p joybrush :core:jvmTest` — 0 failures.

## Do not
Do not edit `BlendModes.java`. Do not add a UI. Do not touch GL.

## Definition of done
Check + tests pass (paste) · commit `JB-2.20a: the Studio's blend modes in Joy Brush` · ROADMAP row → 🟧 Built.

## Questions

*(Builder session, 2026-09-29. My owner area was `core/.../blend/` + its test dir + `tools/`; these
are the parts of Decisions 1, 2 and 3 that live in `core/doc/` and `core/render/`, which is not
mine. Nothing below was guessed at or half-applied — the maths is complete and proven; only the
document-model half is outstanding.)*

### Q1 — Decision 1: the 19 appended constants, and Decision 2's version bump. **BLOCKED, needs the orchestrator.**

Exact edit to `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/DocModel.kt`:

```kotlin
const val DOC_VERSION = 2      // was 1 — R3, a new constant in a serialised enum

@Serializable enum class BlendMode {
    NORMAL, MULTIPLY, SCREEN, OVERLAY, ADD, DARKEN, LIGHTEN, ERASE_BELOW,
    // ↓ appended; order is the Studio's `BlendModes.ALL` / `modeCode`, and is FROZEN
    DIFFERENCE, COLOR, COLOR_DODGE, COLOR_BURN, LINEAR_BURN,
    HARD_LIGHT, SOFT_LIGHT, VIVID_LIGHT, LINEAR_LIGHT, PIN_LIGHT, HARD_MIX,
    EXCLUSION, SUBTRACT, DIVIDE, DARKER_COLOR, LIGHTER_COLOR,
    HUE, SATURATION, LUMINOSITY,
}
```

Then in `.../commonTest/.../doc/EnumFreezeTest.kt`: `blendModeNamesAreFrozenInOrder`'s expected
list becomes that same 27-name list, and `theVersionsTheNamesWereWrittenFor` expects
`assertEquals(2, DOC_VERSION)`. **No edit to `DocJson.kt` is needed**: `decode` already parses any
`version` int and leaves the check to `DocOps.validate`, so v1 and v2 both read, and `encode` will
write 2 through the `JbDocument.version = DOC_VERSION` default.

I checked Decision 1's list against `BlendModes.modeCode`: all 26 codes (0..25) are covered and
`modeCode(ALL[i]) == i` for every i, so nothing was missed. The generator asserts this itself, so
a future Studio mode added without a Joy Brush constant will fail `everyStudioModeIsCarriedOver-
ExactlyOnceAtItsOwnCode` rather than going unnoticed.

### Q2 — Decision 3: wiring `render/Blend.kt` to the new `BlendRgb`. **BLOCKED, needs the orchestrator.**

`core/render/Blend.kt` is not in my owner area. Its eight `term(...)` cases stay as they are until
someone edits that file. The intended edit, when it happens:

- For the seven separable modes Joy Brush already had, `term(mode, cs, cb)` should become
  `blendRgb(codeOf(mode), floatArrayOf(cb, cb, cb), floatArrayOf(cs, cs, cs), scratch)[0]` — i.e.
  one line instead of a `when`. Nothing numeric changes: `BlendParityTest.theEightOriginalModes-
  StillAgreeWithTheRenderer` reads the renderer's own term through `Blend.apply` with `sa = da = 1`
  (which reduces the composite to `B` exactly) and compares it against `BlendRgb` on all 3 567
  golden rows, so that swap is already proven value-for-value.
- The non-separable modes do NOT fit `term`, which is per channel. `Blend.apply` needs the blend
  computed ONCE for all three channels and reused, so `apply` gains a `blendRgb` call in front of
  its three `compositeChannel` calls and then clamps 0..1 by itself (that clamp is what the
  UNCLAMPED contract expects the caller to do). **Do not clamp inside `blendRgb`.**
- `ERASE_BELOW` is untouched and must keep returning before any of the above.

Until that lands, Decision 6's rule stands: no UI may offer a mode other than NORMAL.

### Q3 — where the generated test files live. **Low-risk; ruled by me, no answer needed unless the Lead disagrees.**

The spec says the generated table goes in `commonTest/.../render/`. My owner area is
`commonTest/.../blend/`, so `BlendGolden.kt`, `BlendParityTest.kt` and `BlendRgbIdentityTest.kt`
are all in `blend/`. Moving them is a rename if the Lead wants the spec's paths.

### Q4 — two cases where the Studio's own comment overstates its equations. **Not mine to fix.**

1. `BlendModes` says HARD MIX "is Vivid Light thresholded at 0.5, and that threshold reduces
   algebraically to `b + s >= 1` on BOTH halves of vivid's branch". True in real arithmetic, but in
   float32 at `b = 0.1, s = 0.9` VIVID gives `0.49999988` — because `2 - 2*0.9f` is `0.20000005f`,
   not `2*0.1f` — while HARD MIX gives `1`. And at `b = 0, s = 1` VIVID's dodge is `0/1e-5 = 0`,
   so the two modes disagree outright. One pixel, at a corner. Pinned by
   `hardMixAndVividLightDisagreeAtOneDegenerateEndpoint` and
   `vividLightLandsJustUnderAHalfWhereHardMixLandsJustOverIt` rather than "corrected": correcting it
   would make Joy Brush stop matching the Studio at exactly the point R23 says the Studio is the
   authority. If the Lead wants the Studio fixed, that is a `BlendModes.java` change (D.05 territory).
2. ClipColor's two RESCALES preserve luminosity, but its final `clamp(c, 0, 1)` does not, so a
   rescaled colour can end at a different luminosity than the mode asked for. That is the W3C
   definition and both implementations do it; noted because it makes a tempting invariant false.

### Orchestrator rules applied (low-risk readings, no stop)

- **Decision 4's "400 pairs plus the 27 corner cases" is taken literally as `b == s`** for the 27
  corner rows — that is the only reading that yields 27 *pairs* from 27 triples. Two groups were
  ADDED because the spec's set leaves holes nothing else fills, and both are in the generated
  header:
  - **702 corner-vs-other-corner pairs.** With `b == s`, DARKER_COLOR and LIGHTER_COLOR return `b`
    either way and COLOR/HUE/SATURATION/LUMINOSITY all collapse to the input, so five of the
    twenty-six modes would be essentially untested at the values most likely to expose a branch
    error.
  - **60 out-of-range pairs (±3).** With `b, s` in 0..1 EVERY one of the 26 modes happens to land
    in 0..1, so a table of in-range rows cannot tell an UNCLAMPED implementation from a clamping
    one — the UNCLAMPED contract would be unpinned. These rows are what pin it.
- The generated table stores each pair's inputs ONCE (6 floats) and the results per (pair, mode),
  rather than 9 floats per row: 1 586 KB of Kotlin instead of ~4 MB, same 30 914 rows. Float
  literals are `Float.toString` exactly, as the spec asks, so the committed table holds the
  Studio's bits.
- `bash tools/gen_blend_golden.sh --check` also runs the independent GLSL cross-check, because a
  regeneration diff alone cannot see the Java mirror and the shader drifting apart.
