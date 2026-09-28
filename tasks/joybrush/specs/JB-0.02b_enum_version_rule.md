# JB-0.02b — Write down the "new enum constant ⇒ version bump" rule (docs + guard test)

| | |
|---|---|
| **Tier** | T2 |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-0.02, JB-0.03 (Built) |
| **Owner area** | KDoc lines on the serialised enums in `core/doc/DocModel.kt` (`BoardKind`, `LayerKind`, `BlendMode`) and `core/brush/BrushPreset.kt` (`BrushInput`) — comments only; NEW `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/doc/EnumFreezeTest.kt` |
| **Estimated size** | ~60 lines |

## Goal
Lead ruling R3 (`tasks/joybrush/LEAD_RULINGS.md`): adding a constant to a serialised enum requires
bumping that file format's version, so an older app refuses the newer file clearly instead of
misreading it. Make that rule impossible to miss.

## Steps
1. Above each of the four enums add:
   `// SERIALISED: new constants are APPEND-ONLY and require bumping DOC_VERSION (or the brush "version"). See LEAD_RULINGS R3.`
2. `EnumFreezeTest`: assert the exact current name lists of the four enums (in order), plus
   `DOC_VERSION == 1`. A future change to either must update this test deliberately.
3. Tests: decoding a document with `"kind": "HOLOGRAM"` throws `DocException`; a brush with an input
   `"telepathy"` throws `BrushException` (both already true — the tests pin it).

**Command:** `./gradlew -p joybrush :core:jvmTest` — 0 failures.

## Do not
Change no behaviour, no names, no values. Comments and one test file only.

## Definition of done
Tests pass (paste) · commit `JB-0.02b: enum version rule` · ROADMAP row → 🟧 Built.

## Questions
