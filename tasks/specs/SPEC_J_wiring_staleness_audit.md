# SPEC J — Cross-lane wiring and staleness audit

**Difficulty: MEDIUM. Reading and proving, then small targeted fixes. NOT a refactor.**
**Read `_RULES_READ_FIRST.md` first.**

## Why this exists

Seven lanes (A, B, C, D, E, F, G) landed in the transform surface inside a week, each under a file
ownership boundary that stopped them looking at each other's work. The build is green and the
harnesses pass — but **"compiles" is not "wired"**.

The recurring failure in this app is **staleness**: the handles move, the model moves, and the
picture does not — until some unrelated event happens to trigger a resync. JoyRaptor has reported that
class of bug at least four times.

**Do not rewrite anything. Find the gaps, prove each one, fix the small ones, report the rest.**

## The six things to check — each is a real, verified starting point

### 1. `onChanged` / GL-resync parity across the four transform hosts
`transform/` now holds FOUR hosts: `CornerPinTransformHost` (image), `TextAffineTransformHost`,
`PipAffineTransformHost`, `SpineTransformHost`. Three different lanes wrote them, and they do NOT
call `onChanged` consistently across `writeQuad` / `writeTranslate` / `writeSimilarity` /
`writeRotation`.

`TextAffineTransformHost.writeSimilarity` ends with a **commented-out call and an unfinished
sentence**:

```java
// onChanged is already triggered via target's refresh, but ensure
// onChanged.run();
```

Establish what the contract actually is — `FaditorEditorActivity.requestGlPreviewResync()` and the
comment above it (~line 25462) describe the exact bug it exists to prevent, in JoyRaptor's own words —
then make all four hosts obey it. Say for each host and each write method whether it needed a
change.

### 2. `transformItemId` now means two different things
`enterTransformMode` (image) and `enterTextTransformMode` (text) BOTH set `transformItemId`.
Anything that reads that field and assumes "an image is in transform mode" is now wrong for text.
There are ~36 mentions of the three transform ids in `FaditorEditorActivity`. Check every read,
confirm it is still correct, or split the field. Report what you found either way.

### 3. Playhead refresh coverage
`FaditorEditorActivity:10013` refreshes the transform overlay when any of the three ids is set.
Confirm that is the ONLY clock that can move a transform-mode object, and that a keyframed text or
PiP pose moves its handles the same way an image's does.

### 4. Rotation dial coverage (SPEC F)
The dial is instantiated in `ObjectMenuSheet` and `tools/PipDrawerTabs` only. SPEC F listed FIVE
rotation rows: text, image, PiP, sprite, waveform visualiser. Confirm every one renders through one
of those two renderers and therefore gets a dial — or name the row that still has a slider.
**A rotation SLIDER is the SPEC A winding-collapse bug and must not survive anywhere.**

### 5. Double-tap survival
Each `enter*TransformMode` sets `setOnDoubleTap(...)`. Confirm all four types open the drawer they
opened before the transform surface took over, and that `exitTransformMode` leaves nothing behind.

### 6. Mirror coverage (SPEC G)
`mirrorSignX/Y` is read by `ImageOverlayDraw`, `CornerPinImageView` and `TextOverlayLayer.buildPip`.
Confirm there is no FOURTH image path that draws without it.

The GL export blend path is **already verified safe** — `ImageOverlayFrameOverlay` composites
through `ImageOverlayDraw.draw`. The mesh stamp is a **known hole owned by SPEC H** — do not fix it
here, just confirm the boundary.

## Rules for this audit

- **Evidence, not assertion.** For every item, say what you read and what it proved. "Looks fine"
  is not an answer.
- **Fix only what is small and certain.** Anything structural: write it up, do not do it.
- **Do not touch** `transform/mesh/**` (SPEC H) or the two-finger routing (SPEC I).
- The tree is green at commit `bed9c816`. If it goes red in a file you did not edit, stop and say
  so — do not fix it.

## Acceptance criteria

1. A written answer for each of the six items, with `file:line` evidence.
2. Every small fix has a stated before/after behaviour.
3. `./gradlew assembleDefaultDebug --console=plain` → BUILD SUCCESSFUL.
4. The harnesses that were green stay green: `run-mesh`, `run-rotation`, `run-pinbudget`,
   `run-preview-parity`, `run-frame-parity`, `run-persist-lint`, `run-key`, `run-mask`.

## Deliver

The six answers with evidence; what you fixed versus what you only reported; the build verdict;
compile-verified vs device-verified.
