# JB-0.01 — Pen-sample & stroke contracts, context-aware smoothing, tip direction, curves

| | |
|---|---|
| **Tier** | T1 |
| **Status** | Built — adversarial findings F1/F2/F4 fixed; F3 recorded below |
| **Depends on** | — |
| **Owner area** | `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/input/`, `.../stroke/`, `.../dynamics/` and the matching `commonTest` directories |
| **Estimated size** | — |

## Goal
The contract every later task builds on: one platform-neutral pen sample, a smoothing slider that
understands context, a tip direction that never goes NaN, and a response curve that refuses to
fabricate a value. What is fixed here is the guarantee that a broken number reaching these classes
can never silently delete a painter's work.

## Contract (verbatim)

### 1. A bad `screenPerDoc` is read as 1 — LEAD_RULINGS R1/R10/R19 pattern

`StrokeSmoother(amount, screenPerDoc)` takes the zoom as a plain `Float`. Anything that is not a
positive finite number is read as `1f`:

```kotlin
private val zoom: Float = if (screenPerDoc.isFinite() && screenPerDoc > 0f) screenPerDoc else 1f
```

Exactly the guard `Nudge.stepDoc` and `SizeOpacityDrag.ratio` already use, for the same documented
reason: one bad float must not be able to delete a stroke. The failure without it is total, not
partial — `feedPath` multiplies by `zoom` and `smoothedAt` divides by it, so `0f` gives `0/0` = NaN
and a NaN in `xs` is permanent (`segLen` = NaN ⇒ `along <= segLen` false forever ⇒ the stroke stops
releasing points for the rest of its life, and the pen-up catch-up dies with it).

**Alternative considered and rejected:** `require(screenPerDoc.isFinite() && screenPerDoc > 0)`, as
both reviewers proposed. A `require` here would throw on the live drawing path (a zoom read from
`ViewTransform` at stroke start) and abort a stroke in progress. Every other consumer of a zoom in
this codebase — `Nudge`, `SizeOpacityDrag`, `StrokePicker`, `GuideSnapper`'s callers — falls back to
1 rather than throwing, so a `require` would make the smoother the only class in the pipeline that
crashes on a bad number. The review's own recommendation is to use "the guard the rest of the
codebase already uses", and the guard the rest of the codebase uses is a fallback.

### 2. A sample with no place and no time in the world is dropped, and the drop is counted

```kotlin
val isPlaceable: Boolean get() = x.isFinite() && y.isFinite() && timeMs.isFinite()
```

`add` refuses a non-placeable sample, counts it in `droppedSamples`, and returns nothing. **Policy:
FAITHFUL — the rest of the stroke is kept.** Reasons, in order:

- A stroke is a time series. A missing pen sample is an ordinary event: Android coalesces
  `MotionEvent` batches and a pen can skip a millisecond. The resampler already resamples across a
  gap of any length, so "the device never reported that point" and "that point was not a number" are
  the same stroke. Dropping is *exactly* the same as if the sample had not arrived.
- Refusing the whole recording instead would throw away the other 999 good samples over one bad
  float. That is the same lost-work shape the codec's own KDoc forbids ("Half a stroke is worse than
  no stroke").
- Letting it through is what deleted the rest of the stroke in the first place (mimo's M1).
- The drop is **not silent**: `droppedSamples` is a public counter, so a caller replaying a file can
  report it. This is what separates "faithful" from "a third behaviour".

`isPlaceable` deliberately does **not** include tilt/azimuth/barrel. A pen with no tilt sensor has
`tilt = NaN`, and that is the documented, correct spelling of "no sensor" — treating it as a broken
sample would drop every sample from an S Pen. The NaN convention is for channels; `x`/`y`/`timeMs`
are not channels.

### 3. A non-finite curve POINT is dropped; a curve with none is refused

`Curve` filters points that are not finite in both coordinates, and `require`s that at least one
survives. **Policy: FAITHFUL, deliberately NOT loud** — a loud `require` on any bad point was
rejected because `Curve` is constructed by `Dynamics.Compiled.of` on the render thread *during a
stroke*, from a brush file that `Compiled`'s own KDoc says must not throw ("an unvalidated brush file
must not throw inside a stroke"). Refusing a whole 64-point curve over one bad float would also throw
away 63 points the painter did draw. So: drop the bad point, keep the curve, and refuse only the case
where nothing is left to keep — where the alternatives are a made-up value (forbidden) or a silent
NaN (the bug).

## Steps
Done. `StrokeSmoother` (guards 1 and 2), `PenSample` (`isPlaceable` + the channel-vs-position
distinction in the KDoc), `Curve` (guard 3), `DirectionTracker` (guard 4), `OneEuroFilter` (KDoc only).

## Tests
`StrokeSmootherTest`: `aZoomThatIsNotAUsableNumberIsReadAsOneAndTheWholeStrokeSurvives` (0, -1, NaN,
±Inf → all finite AND identical to the 1:1 stroke), `oneNonFiniteSampleInTheMiddleCostsThatSampleAnd
NothingElse` (NaN x, NaN y, NaN timeMs, +Inf timeMs → output equals the same stroke with that one
sample deleted), `aStrokeOfNothingButNonFiniteSamplesIsEmpty`, `anAbsentSensorIsNotAnUnplaceableSample`.
`CurveTest`: `aNonFinitePointIsDroppedAndTheRestOfTheCurveIsKept`, `anInfinitePointIsDroppedToo`,
`aCurveWithNoFinitePointAtAllIsRefusedByName`.
`DirectionTrackerTest`: `aNonFiniteAzimuthFallsBackToTheDirectionOfTravel`,
`aNonFiniteTiltFallsBackToTheDirectionOfTravel`, `aNonFiniteBarrelFallsBackInsteadOfBeingUsedRaw`.

**Command:** `./gradlew -p joybrush :core:jvmTest` — 655 tests, 2 failures, both pre-existing and not
this task's (see the builder's report for the non-vacuity counts).

## Do not
- Do not turn either guard into a `require`. Both classes are on the live drawing path.
- Do not widen `isPlaceable` to the sensor channels. NaN there means "no sensor", not "broken".
- Do not let `Curve` invent a value for a point it cannot read.

## Questions

**Q1 (for the Lead) — the `screenPerDoc` ingress guard belongs at the decoder too, and that is
out of JB-0.01's owner area.**

Guarding `StrokeSmoother` makes the replay *safe* (a bad zoom now yields a wrong-but-complete
stroke instead of a deleted one). It does not make it *right*, and it does not tell anyone the file
is bad. A `StrokeRecord` decoded from a corrupt or hostile `.jbs` still carries `screenPerDoc = 0f`
in memory, and it will be re-encoded verbatim by the next save, so the bad value persists in the
document indefinitely and each replay silently re-derives the stroke at 1:1. `StrokeCodec.decode`
(`stroke/StrokeCodec.kt:89`) and `StrokeRecord.init` (`stroke/StrokeRecord.kt:27-28`, which today
rejects only `predicted`) both validate something already, so the natural place is there.

This was left undone deliberately: `StrokeCodecTest.awkwardFloatsKeepTheirBits` asserts that a NaN
`timeMs` round-trips bit-exactly, and a decode-time refusal contradicts that test — which is a
contract question, not a code question, and the codec's own KDoc makes bit-exactness its promise.
**The Lead should rule: does a `StrokeRecord` with a non-positive or non-finite `screenPerDoc`
refuse to decode (loud, consistent with `ByteReader`'s "a truncated file must fail loudly"), or
round-trip and be repaired at the consumer (faithful, consistent with the guards above)?** The
reviewers both proposed the loud version; it is not this builder's call, and the answer changes
`StrokeCodecTest`. Not started — out of the owner area for the ruling above to unblock.

**Q2 (for the Lead) — `DabPlacer` does not guard dab `x`/`y`.** mimo's "adjacent hole": it clamps
radius/angle/flow/cap but passes `s.x`/`s.y` through raw, so a NaN coordinate that somehow reached it
would bucket into tile `(0,0)` via `NaN.toInt() == 0` and `RefCanvas.stamp`'s `cov <= 0f` test does
not reject NaN. This is **now closed upstream** by guard 2 above (nothing non-finite can leave
`StrokeSmoother`), but the placer is the last line of defence and does not have one. `paint/` is
another agent's area. Recommend a follow-up, not urgent.

**Q3 (recorded, not a question) — `OneEuroFilter` is dead code.** Confirmed by the reviews and
re-confirmed here: no callers outside its own file, no test. The KDoc now says so explicitly ("NOT
WIRED, and not tested… Whoever wires it must add tests in the same change") so a future builder
cannot read the board's 🟧 as "exercised". No tests were written for it: testing unreachable code
certifies a decision nobody made. Recommend moving it out of the built path, or wiring it with
tests, at the next task that touches `input/`.
