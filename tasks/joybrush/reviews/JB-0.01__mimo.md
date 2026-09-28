# Adversarial review — JB-0.01 pen-sample contract, smoothing, tip direction, curves

- Reviewer: mimo (second adversarial pass; muse-spark filed `JB-0.01__muse-spark.md`).
- Task status: 🟧 Built. Commit reviewed: `82bf0dd7` (tree at `b74aaf0e`).
- Spec: none exists (T1 pre-spec work) — contract = code KDoc + blueprint §2/§3.4 + consumers (as muse recorded).
- Suite: `./gradlew -p joybrush :core:jvmTest --rerun-tasks` at `b74aaf0e` → BUILD SUCCESSFUL, 280 tests / 0 failures (StrokeSmootherTest 10, CurveTest 4, DirectionTrackerTest 6, AxisMappingTest 3 — the 2 `core.vector` failures muse saw were uncommitted JB-5.10 work, now merged and green).
- §5b checks: owner area (`core/input|dynamics|stroke` + tests) only; no spec to contradict. Severity: BLOCKER / MAJOR / MINOR.

**Verdict: no BLOCKER. Muse's F1 stands (MAJOR — hold before JB-0.08); F2's chain is now closed
downstream (residual MINOR); F3 confirmed still open; F4's stated trigger is wrong (reproduced-with-
correction below) and its blast radius is now clamped; F5 confirmed. My two findings: a silent
stroke-truncation on NaN coordinates (MAJOR, same JB-0.08 ingress), and the unfiltered barrel
shortcut (MINOR).**

## Status of muse-spark's findings at this commit (each re-read in source)

### F1 (muse Medium → I confirm **MAJOR**): `StrokeSmoother` accepts any `screenPerDoc`, including 0/NaN — replay then emits Inf/NaN coordinates

- Re-verified exactly as muse wrote it: stored unchecked (`StrokeSmoother.kt:47`), multiplied in
  (`:93`), divided out (`:177-178`); `StrokeRecord.init` rejects only `predicted`
  (`StrokeRecord.kt:27-28`); codec stores `screenPerDoc` as raw f32 with no validation
  (`StrokeCodec.kt:89`); `JbCanvasView` hardcodes `screenPerDoc = 1f` (`JbCanvasView.kt:135`) —
  why today's tests pass.
- Severity: MAJOR (work-corrupting at replay) but **precondition is a persisted bad zoom**, which
  JB-0.08 will start persisting — so the `require(screenPerDoc.isFinite() && screenPerDoc > 0f)`
  guard belongs in `StrokeRecord`/`StrokeSmoother` **before JB-0.08 lands**, per §5b "rule before
  the consumer exists". Same ingress theme as my M1 below.

### F2 (muse Medium → **residual MINOR; the chain is closed, the hole in `Curve` is not)**: non-finite curve points still build

- `Curve.kt:19-23` still only `require(points.isNotEmpty())` — confirmed (I read the file this session).
- **But at HEAD two downstream layers now break the chain muse described:**
  1. `BrushValidate` rule 16 refuses every non-finite `Param` base (`BrushValidate.kt:143`,
     JB-0.03b) — file path closed;
  2. `DabPlacer.emit` refuses non-finite radius/angle/flow/cap at the placer boundary
     (`DabPlacer.kt:78-81`, R1-era, kdoc `:76-77` cites JB-0.03 F1) — so `Dynamics.eval` returning
     NaN/Inf yields a clamped dab, **not** a frozen stroke.
- So F2 is no longer a live chain: downgrade to MINOR (API-level hardening + a test would still be
  worth it when someone owns `Curve.kt`). The `"base": 1e999 → Inf → stall` path muse documented is
  now refused at validate *and* clamped at place — I re-traced both.

### F3 (muse Low → **confirmed MINOR**): `OneEuroFilter` still ships with zero callers and zero tests

- Grep this session: exactly two hits — its own file and the KDoc sentence in
  `StrokeSmoother.kt:30` that says it is deliberately not used. No `OneEuroFilterTest`. Unchanged
  since muse reviewed. Keep on the list: either test it at its first wiring or move it out of the
  built path (a future builder will read the board's 🟧 as "tested").

### F4 (muse Medium → **confirmed-with-correction; MINOR at HEAD**): poisoned direction is possible, but the stated trigger is wrong and the paint-side chain is now clamped

- **Where muse's mechanism fails:** the guard is `s.hasTilt && s.hasAzimuth && s.tilt > tiltThreshold`
  (`DirectionTracker.kt:57`) and `hasAzimuth = !azimuth.isNaN()` (`PenSample.kt:40`). Muse's stated
  trigger, "Inf tilt + NaN azimuth", makes `hasAzimuth` **false**, so the branch is skipped and
  `dx/dy` are never poisoned — it fails *safe*.
- **The real triggers (both reproduced by reading the code):**
  1. `azimuth = ±Inf` (passes `hasAzimuth`; `cos(±Inf)`/`sin(±Inf)` = NaN at `:58`) →
     `atan2(NaN,NaN)` = NaN returned at `:75`;
  2. the barrel shortcut at `:48` (my M2 below) — `barrel = ±Inf` passes `hasBarrel` and is
     returned raw.
  - Note `tilt = +Inf` with a *finite* azimuth is harmless (`cos`/`sin` of azimuth are finite) —
    so muse's suggested guard `!tilt.isFinite()` would close nothing; the guard that matters is
    `azimuth.isFinite()` (and barrel, M2).
- **Blast radius at HEAD:** the returned NaN angle is neutralized two layers later —
  `DabPlacer.emit` sets `angle = if (l.angle.isFinite()) l.angle else 0f` (`DabPlacer.kt:79`), so
  no NaN reaches `RefCanvas`/`GlPaintEngine` from the pen path. What remains: any *future*
  consumer of `tracker.update` (grain lean plane, JB-1.02's `leanDir`) would inherit the NaN —
  harden the tracker itself when that lands. Residual severity: MINOR.

### F5 (muse Info → **confirmed**): the NaN-channel discipline holds where it matters

- Independently re-walked: `lerpNullable` for tilt (`StrokeSmoother.kt:143-144`), `Angles.lerp` for
  azimuth/barrel (`:139-140`), plain lerp only for pressure/time (never NaN by contract,
  `PenSample.kt:17-18`), predicted dropped at ingest (`:90`), codec channel flags + NaN fill
  (`StrokeCodec.kt:43-45,104-106` — verified in my JB-0.04 pass), streaming==batch test green.
  Muse's determinism caveat (exp/acos/hypot not strictfp-guaranteed across JVM↔ART) stands as an
  accepted, documented-quality note.

## My findings

### M1 (MAJOR — silent work loss on the replay path): a NaN coordinate fed to `StrokeSmoother` permanently truncates everything after it, with no error

- **Proof (code):** `feedPath` (`StrokeSmoother.kt:113-131`): with an incoming NaN `x`/`y`
  (`add` → `:93`), `segLen = hypot(fx - prevX, fy - prevY)` = NaN (`:122`), so
  `while (along <= segLen)` (`:124`) is `number <= NaN` = **false forever** — no resample point is
  ever pushed again, and `untilNext = along - segLen` = NaN (`:129`) poisons the carried distance
  permanently. The pen-up catch-up also dies: `finish` (`:105`) tests
  `hypot(...) > 1e-9` with NaN → false → the true lift-off point is never pushed. Net: from the
  first NaN sample on, **the stroke stops following the pen**, silently; `release` keeps returning
  the stale tail.
- **Input:** `smoother.add(PenSample(100f, 200f, 0.0)); smoother.add(PenSample(Float.NaN, 200f, 8.0)); smoother.add(PenSample(110f, 205f, 16.0)); smoother.finish()` →
  nothing derived from `(110, 205)` is ever released.
- **Reachability:** device `MotionEvent`s never carry NaN x/y, but **stroke recordings do — the
  codec stores floats as raw bits by design** (`ByteWriter.f32` uses `toRawBits`,
  `StrokeCodecTest` even asserts NaN round-trips) and neither `StrokeCodec.decode`
  (`StrokeCodec.kt:99-109`) nor `StrokeRecord.init` validates coordinates. So a corrupt or
  hostile recording replayed through this smoother (JB-0.08's replay/timelapse) silently drops
  the rest of the stroke instead of failing — the exact failure mode `ByteReader`'s own KDoc
  forbids for bytes: "Half a stroke is worse than no stroke, so a truncated file must fail loudly"
  (`ByteReader.kt:9-10`).
- **Fix shape (for JB-0.08's dispatch, not this task's owner area):** refuse non-finite
  `x/y/timeMs` at decode (loud `StrokeCodecException`) **or** at `StrokeSmoother.add`; plus the
  `screenPerDoc` require from F1 — one ingress guard covers both. Note the two findings compose:
  F1 produces NaN at *output* (`smoothedAt`), M1 truncates at *input* (`feedPath`) — same remedy.
- **Adjacent hole (stated for completeness):** `DabPlacer` clamps radius/angle/flow/cap but **not
  dab `x`/`y`** (`DabPlacer.kt:79`); a NaN `x` slips past into `Tiles.bucket` (→ single `(0,0)`
  tile via `NaN.toInt() = 0`) and `RefCanvas.stamp`'s `cov <= 0f` check does **not** reject NaN
  (`RefCanvas.kt:74`) → a NaN pixel would be written. Only reachable if the ingress gap above is
  not closed — which is exactly why M1 asks for the guard *before* JB-0.08 wires replay.

### M2 (MINOR): `DirectionTracker.update` returns the raw barrel channel — no filtering, no finiteness guard, while every other branch filters as a unit vector

- **Proof:** `DirectionTracker.kt:48` — `if (s.hasBarrel) return s.barrel` is a *direct return*:
  unlike the lean branch (`:57-59`, unit vector from azimuth) and the travel branch (`:60-72`,
  damped vector blend with zero-cancellation guard), the barrel value is passed through raw —
  sensor noise, wrap jumps, and non-finite values (`hasBarrel = !isNaN` admits ±Inf,
  `PenSample.kt:41`) all become the tip angle unfiltered, then `cos(Inf)` = NaN one layer down
  (`TipMath`/shader `cos(t.angle)`).
- **Reachability:** nil on the target (every S Pen reports NaN barrel — `PenSample.kt:23-24`;
  only Wacom Art Pen / Apple Pencil Pro expose it), so MINOR hardening: `if (s.hasBarrel &&
  s.barrel.isFinite()) return s.barrel` (and consider the same wrap-aware smoothing as travel when
  a real barrel device becomes a test target).

## Verified sound (checked independently this pass)

- **Resampling/corner/speed maths:** STEP=1 px, corner window `k`, `lookahead` release discipline
  (`StrokeSmoother.kt:54-61,148-154`); corner detection peak-wins (`:239-248`); speed factor
  clamped (`:203-210`); ends/corners pinned by `boundaryBefore/After` (`:188-200`). All 10
  StrokeSmootherTest cases green in this session's 280-test run (incl.
  `streamingReleasesExactlyWhatBatchWould` — the STREAMING = BATCH KDoc promise at `:39-42`).
- **Curve:** LUT construction (256 entries, built once per Param, `Curve.kt`), duplicate-x keeps
  the *later* point (`Curve.kt:41-44` — I disproved a "keeps earlier" claim this session), `eval`
  coerces finite out-of-range x and maps NaN input x to `lut[0]`; cache behaviour documented in
  my JB-0.03 F1.
- **`Angles.lerp` / `AxisMapping`:** NaN passthrough as documented; short-way rotation (tested).
- **`OneEuroFilter` internals** (dt clamp, shared cutoff) — still unvetted by test (F3), not shown wrong.

## Bottom line

No send-back. Two items ride on the same dispatch — **JB-0.08's ingress**: (a) F1 `screenPerDoc`
require, (b) M1 refuse non-finite coordinates at decode/smooth — because that is the first moment
a hostile float becomes a user's lost stroke. F3/M2 are cheap hardening for whoever owns those
files; F2's chain fix landed with JB-0.03b/R1 (residual: a `Curve`-level require + test).
