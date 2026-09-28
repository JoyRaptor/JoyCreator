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

Answers welcome — none of these blocked the KDoc, all of them are things I was not allowed to change.

1. **The rule itself is still untested, and cannot be from `commonTest`.** `EnumFreezeTest` pins the
   token lists, the versions and the refuse-don't-coerce behaviour. It does *not* check that the
   sentence is still on each enum: a common source set has no file system, so grepping KDoc needs a
   `jvmTest` file reading `src/commonMain/kotlin/...` (Gradle test working dir = module dir). That is
   a new path outside this subtask's owner area. Do you want it, as a follow-up?
2. **A tripwire is not enforcement.** Someone can add `BoardKind.HOLOGRAM` and satisfy my test by
   editing one list literal, without ever touching `DOC_VERSION`. Nothing in the repo catches that,
   because the failure it causes only shows up in an *older* build. Real enforcement needs the v1
   list kept as data — e.g. a test-local `V1_BOARD_KINDS` plus "these lists differ ⇒ `DOC_VERSION`
   must be > 1". That is a test-only change and would fit in a follow-up. Worth it?
3. **`BrushPreset.version = 1` is a second literal beside `BRUSH_VERSION`.** Nothing in code ties them
   together; `BrushJson`'s own KDoc says "Bump with the defaults in [BrushPreset]". The one-character
   fix is `val version: Int = BRUSH_VERSION`, in a contract file I may only comment on. My test
   asserts the two are equal so drift fails the build, but the fix belongs to whoever owns the file.
4. **R3's "readers already refuse a newer version" needs the caller to call `validate`.**
   `DocJson.decode` / `BrushJson.decode` decode a newer file happily on purpose; it is
   `DocOps.validate` / `BrushValidate.validate` that says "from a newer Joy Brush". I wrote the KDoc
   to say exactly that rather than "the reader refuses". Confirm that is the intended reading — the
   alternative wording would be a false promise in a comment nobody tests.
5. **Cosmetic:** `BlendMode`'s KDoc sits directly under `LayerKind` with no blank line, because those
   two declarations were adjacent in the original and I did not want to re-space a contract file.
   One blank line if you prefer.
6. The `BrushPreset.kt` KDoc links `[BrushValidate.validate]`, a file another agent is editing right
   now. If they rename it, only the link goes stale — unresolved KDoc links are not compile errors.
