# Frozen-brush tween fidelity: prepared focused gate

Status: PREPARED ONLY; seven tests remain UNRUN. No build, queued wrapper or device action
was performed. Lead assigns the shared PC slot after UX handback; this file grants no slot.

## Exact source and adoption

Original source commit `2ab6cd4c4`, preserved branch
`codex/tween-wash-fidelity-20261026`, source checkpoint `1400e14ea`.
Adopt/review the two new files selectively on the current candidate, not the older branch's
whole model/engine/codec tree:

- `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/anim/WatercolourTween.kt`
  Git blob `4fd532421c7cfc8a80bc95314b41c2b4f32425dd`.
- `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/anim/WatercolourTweenTest.kt`
  Git blob `4ce7497a97423298f6b3a08cbf043e3e5096aa35`.

Current candidate must preserve its actual StrokeRecord.washFrame, LineTransform and frozen
brush validation contracts. Record candidate HEAD and hashes before running. Changes to any
dependency mean a new source scope, even if these two blobs match. No generic matrix lerp,
legacy Tween replacement, UI activation or captured-source guard removal is authorized here.

## Focused job (lead runs under normal admission)

From the admitted checkout, the inner Gradle invocation is:

```powershell
.\gradlew.bat -p joybrush :core:jvmTest --tests cc.joycreator.joybrush.core.anim.WatercolourTweenTest --no-daemon --max-workers=1
```

Use the coordinator's exclusive CreateNew jb-gradle.lock/resource envelope and current heap
policy, not a queued waiter or parallel build. UX currently owns the next job. This is a
Kotlin/JVM metadata check; it needs no phone, app install, GPU context or APK activation.

Require fresh execution and fresh exact-class XML:
`joybrush/core/build/test-results/jvmTest/TEST-cc.joycreator.joybrush.core.anim.WatercolourTweenTest.xml`.
UP-TO-DATE/cached output or an old XML is not new evidence. Preserve prior evidence and arrange
fresh execution through the normal job wrapper. Record command, HEAD, source hashes, start/end,
exit code, XML SHA256, and independently count 7 tests / 0 failures / 0 errors / 0 skipped.
Independently match the seven method names in the accompanying gate JSON. A missing, renamed,
skipped or additional method must be reconciled, not silently folded into an old seven-test claim.

## What the seven methods establish if they pass

| Method | Bounded metadata oracle |
|---|---|
| pureTranslationCarriesGeometryAndWashAndKeepsLegacyNullEndpointExact | Exact admitted endpoints, intermediate translation/colour/width and legacy null frame. |
| explicitWashFrameTranslationMatchesLineTransformComposition | Existing nonidentity source frame transported by actual LineTransform convention; wash-local coordinates retained. |
| onlyPureTranslationsAndMatchingRecipesAreAccepted | Rotation/scale/shear/perspective, changed seed/nonrigid samples and mismatched wash recipe explicitly refused. |
| frozenLookupUsesActualPresetEngineAndRejectsMissingOrMismatchedDefinitions | Actual engine selects policy; missing/wrong-ID/inconsistent frozen definitions and cross-engine pairs refuse. |
| genericInkKeepsLegacyTweenMathEvenWhenItsWashFrameDiffers | Ordinary Ink math and distinct-source/target Ink preset semantics remain unchanged. |
| invalidFractionIsExplicitAndCapturedRefusalRemainsUnchanged | Invalid fraction refuses; existing captured-record refusal stays in force. |
| translationPreservesUnknownSensorNanPayloadAndRejectsPayloadChanges | Unknown sensor NaN payload bits survive translation; changed payload is refused. |

## After a green focused gate

Update the canonical TWEEN-LIVE row from UNRUN to the exact candidate's scoped metadata result
and link the fresh receipt. The helper remains unbound until reviewed consumers supply the
immutable drawing/snapshot frozen brush closure. Consumer GL context, source/asset leases,
native wash/grain/pooling fidelity, unsupported-transform UX, cache identity, save/reopen and
context loss remain open. This gate does not prove native pixels or a finished user feature.
Root owns STATE; no LIVE or user-entry-point availability promotion follows from this job.
