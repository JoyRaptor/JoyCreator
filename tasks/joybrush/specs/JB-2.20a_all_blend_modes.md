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
