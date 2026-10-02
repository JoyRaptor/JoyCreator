# D.02a — T4 follow-up: fold what a rotation gesture STORES (bunny, 2026-10-02)

Fixes findings **1** and the half of **2** that belongs to this row, in my worktree
(`jb-d02afix`, from `7ac55662`). Findings 3 and 4 are reported below and **nothing was changed**
for them. Finding 5 is left alone and explained. **No gradle, no harness, no javac was run by me** —
I could not execute anything, so every claim below is either a source citation or arithmetic I
checked by hand in PowerShell. Read the "Unproven" section before trusting any of it.

## The rule I chose, and why

> **A rotation that arrives through a GESTURE is folded into (−180, 180] on the way into the model,
> and on the way out of the model into the gesture. A rotation that arrives through the drawer's
> TYPED field is left exactly as typed.**

The one-sentence version of the normalisation: **`TransformQuad.storedRotationDeg(startDeg,
snappedDeg)` is the only place a rotation gesture becomes a stored angle, and it folds both the
absolute it stores and the delta it hands the quad.**

Why **both read and write**, not one:

- **Write only** leaves a value an older build already stored unwrapped (205) sitting in the file,
  poisoning every keyframe made after it, and the first MOVE of the next gesture would measure from
  205 and write back something a whole turn out of step with the HUD.
- **Read only** cannot help, because the corruption is in what is *stored* — the keyframe track
  reads the stored number, not a read of ours. `KeyframeTrack.valueAt:80` interpolates
  `a.value + (b.value - a.value) * eased` and there is no wrap anywhere in the `keyframe/` package
  (verified: `Keyframe.java`, `KeyframeSet.java`, `KeyframeTrack.java`, `Easing.java` import only
  `androidx.annotation` and `java.util`).
- So the guarantee has to sit at **both ends of the `Host` contract**: `currentRotationDeg()`
  returns canonical, `writeRotation`/`writeSimilarity` store canonical. Each host also folds its
  own `startRot`, so the pinch's `startRot + deltaDeg` sum is canonical without relying on the
  caller's fold. `norm180` is idempotent, so the doubled fold is harmless, and it means a host
  that forgets is still correct — which matters because finding 8 says that is exactly how the next
  `Host` will be written.

**Why the gesture folds and the typed field does not.** This is not a free choice, and it is the one
place two specs disagree. SPEC A (`tasks/specs/SPEC_A_rotation_beyond_360.md:22-27`) is emphatic:
*"370° and 10° are the same POSE but a completely different ANIMATION… If anything normalises the
stored value into 0–360, a sixteen-turn spin silently collapses into a fraction of one turn"*, and
`KeyframeSet.java:95-98` says *"Displays may fold; storage may not"*. But SPEC A's storage door is
`KeyframeSet.parseRotationInput` → `TextOverlayItem.setRotationDeg`, reached from
`PipDrawerTabs.promptForValue` — **the typed field, which never calls `Host.writeRotation`.** I
verified the two doors are disjoint: `grep` for `rotateTo` across `app/` and `studiokit/` returns
only `PreviewHandlesOverlay`, `AffineTransformHost` and `CornerPinTransformHost`, and
`PipDrawerTabs.java:937-938` writes `cur.rotationDeg` straight to the field.

So the split SPEC A already established — *"a slider is in-window by nature; typing is the unclamped
door"* (SPEC A F1) — is what I extended to the gesture. A typed 720 keeps its winding and turns
twice; a dragged turn has one spelling because the drag only ever meant one pose. **SPEC A's
acceptance criteria are untouched by this change.** It does mean that *any* gesture on a typed-720
object now collapses the winding, which is the same hazard F1 already accepts for sliders — see
Question 1.

**Not a clamp.** 205 clamped to 180 is a different pose 25° away; folding changes only the
spelling. `itIsAFoldNotAClamp` measures this: `cos(-155°) == cos(205°)` and
`sin(-155°) == sin(205°)` to 1e-6, while `|cos(180°) - cos(205°)| = 0.0937`, which is 25° of the
unit circle.

## The 360 case and the detent

The detent and the global snap hook still run on the **unwrapped** number — that is what the D.02a
spec's "Do not" asks ("the dead-zone, detents and snap then run on the unwrapped value unchanged"),
and it matters: `snapRotation` (`TransformOverlayView.java:317`) snaps to the nearest multiple of a
configurable step, and a step that does not divide 360 would give a *different* answer on 359 than
on −1. Folding before the detent would therefore change detent behaviour. Folding after it changes
nothing but the stored spelling.

That is what makes the 360 case fall out with no detent change at all: at 360 the distance to the
nearest cardinal is 0, so the detent **holds** — and the number it holds is then expressed as 0
before it is stored. Verified by hand: 40 MOVEs of +9° from 0° accumulate to +360°, the pipeline's
unwrapped answer is 360, and what is stored is 0. 360 ≠ 0 is fixed; the pin itself is untouched, so
"upright is a thing you fall back into" is preserved.

The same holds for the pinch twin. Note the pinch's dead-zone is *subtracted*, so a twist of exactly
+360° leaves a genuine −7° pose — that is by design and I did **not** "fix" it; the twist that
actually lands on 0 is 367°. Both are pinned by name in the test.

## What I did about `PreviewHandlesOverlay` (finding 5)

**Left it. It is not in my owner area and I did not touch it.**

D.02a's owner area (`specs/D.02a_transform_fixes.md:8`) is `TransformOverlayView.java`, the three
`*TransformHost.java`, `SpecHBendMirrorTest.java`, and the two gestureangle harness files.
`app/src/main/java/com/fadcam/ui/faditor/overlay/PreviewHandlesOverlay.java` is in `overlay/`, was
never named by D.02a or by the audit, and the audit itself notes it was scoped out ("`overlay/` was
never in scope. Filed as a miss, not a broken promise"). The finding is correct and live; here is
everything I confirmed so the next lane does not have to re-read it:

- `:930-933` ROTATE — `t.rotateTo(snapRotation(startRot + (angle - startAngle)), currentTimeMs)`.
  Differenced against the **start** with no unwrap: a 100° anticlockwise turn from 0° stores +260.
- `:789-792` / `:805-809` pinch twin — `deltaDeg` *is* normalised to ±180 (`:791-792`), then
  `t.rotateTo(snapRotation(pinchStartRot + applied), currentTimeMs)` at `:808`. So the pinch is
  better than the arc but still stores `pinchStartRot + applied` unfolded.
- `:762` `pinchStartRot = t.rotationDeg(currentTimeMs)` and `:887` `startRot = t.rotationDeg(...)`
  are raw reads, so a legacy 205 enters here too.
- Its own `snapRotation` at `:823-829` returns an **unwrapped** result (it folds only for matching).
- It is live: `FaditorEditorActivity.java:25864` constructs it, `:26618` hands it point handles, and
  `Target.rotateTo` writes a ROTATION keyframe.

The fix is the same shape as mine and needs no new maths: fold `startRot`/`pinchStartRot` on read,
and fold the value handed to `rotateTo`. `TransformQuad.norm180` is already public in `:studiokit`
and the same package name, so `PreviewHandlesOverlay` can call it directly (R16/R23: one
implementation — do **not** add a second fold helper there). I have not written the patch because
doing so means editing an out-of-area file, which is how two lanes collide. **Question 2.**

## Files changed

| File | What |
|---|---|
| `studiokit/.../transform/TransformQuad.java` | **+** `public static final class StoredRotation` (`:667-687`) and `public static StoredRotation storedRotationDeg(float, float)` (`:689-721`). Pure, zero imports — the class's "Deliberately dependency-free" note stays true. Reuses the existing `norm180`; adds no second fold. |
| `studiokit/.../transform/TransformOverlayView.java` | ROTATE branch: `storedRotationDeg` feeds the quad, the model and the HUD from one value (`:2358-2375`). Pinch twin (`:2624-2630`). Three read sites fold the start angle (`:2091`, `:2533`, `:2571`). `Host.writeSimilarity`/`writeRotation`/`currentRotationDeg` KDoc now states the canonical contract. **Deleted the private duplicate `norm180`** (was `:2644`) — `TransformQuad.norm180` has been public since before D.02a and the overlay was carrying its own copy. |
| `app/.../transform/AffineTransformHost.java` | 6 sites folded: `currentRotationDeg` (`:254`), `startRot` in `beginGesture`/`rebaseGesture` (`:266`, `:283`), `writeQuad`'s `newAngle` (`:356`), `writeSimilarity` (`:429`), `writeRotation` (`:434`). |
| `app/.../transform/CornerPinTransformHost.java` | 8 sites folded: `currentRotationDeg` (`:173`), `startRot` ×2 (`:183`, `:200`), `writeSimilarity` (`:339`), `writeRotation` (`:367`), and three reads that were **compared or stored** and so break on a legacy 205 — the drift-walkaway test (`:396`), the bake's `rot0` base (`:485`) and the flip's `curRot` (`:789`). |
| `app/.../transform/SpineTransformHost.java` | 5 sites folded: `currentRotationDeg` (`:148`), `startRot` ×2 (`:164`, `:175`), `writeSimilarity` (`:195`), `writeRotation` (`:202`). |
| `tools/jvm-harness/GestureAngleTest.java` | +8 test methods, 0 existing ones touched. **Nothing weakened, no bound loosened.** |
| `tools/jvm-harness/run-gestureangle.sh` | Puts the `androidx.annotation` marker jar on the classpath (the test now loads the real interpolator) and extends the android-leak guard. Same `@argfile` shape as `run-spech.sh`. |

Two reads I deliberately did **not** fold, and why:
- `CornerPinTransformHost:115`, `:157`, `:217`, `:644` feed `Math.toRadians` → `cos`/`sin`, which
  are 360-periodic. Folding changes nothing there.
- `CornerPinTransformHost:567` `target.rotateTo(newRot, t)` where `newRot = rot0 + fit.rotDeltaDeg`.
  SPEC A's raw accumulation across bakes is deliberate ("Rotation ADDS — never folded into a
  window"), and I kept it; I only made the *base* canonical. The comment at `:524-527` now says so.

## Tests added, with the mutation each one catches

The harness cannot build a `MotionEvent`, so the touch plumbing is out of reach. What **is** pure is
the rotation arithmetic, and I factored it so that the whole store path is reachable: `oneMove()` in
the test is a line-for-line copy of the ROTATE branch with the one Context-coupled piece
(`snapRotation`, currently the identity) replaced by that identity, and `rotateGesture()` walks the
accumulator through `atan2`'s fold exactly as the view reads it. So the tests exercise the real
`TransformQuad` methods in the real order, and they reach the **real** `KeyframeSet`/`KeyframeTrack`
interpolator — which is precisely the class the original suite could not see.

| # | Test | Goes red if you… |
|---|---|---|
| 1 | `theStoredAngleIsTheFoldedOneAndTheHudAgreesWithIt` | remove the fold. 205 ≠ −155, and the HUD string becomes "205 (+30)". Also pins that the HUD text did **not** change. |
| 2 | `theKeyframesTurnTheShortWayAndNotTheLongWayRound` | remove the fold. Keys 0 → −155 read −77.5 at the midpoint; the same track from 205 reads +102.5. Runs through `KeyframeSet.valueAt` → `KeyframeTrack.valueAt:80`, the real interpolator. |
| 3 | `aFullTurnStoresZeroAndNot360` | remove the fold. 360 ≠ 0, and the quad turn is no longer 0. |
| 4 | `theDetentPinsTheFoldedNumberNotAnUnwrappedOne` | fold *before* the detent instead of after. 361 must still pin to the unwrapped 360 (→ stored 0) and 351.5 (8.5° short of a cardinal) must still go through unsnapped at −8.5. |
| 5 | `aStaleAngleFromAnOlderBuildHealsOnRead` | stop folding `startDeg`. A stored 205 healed to −155, +30 from it stores −125 with the turn still +30, and −125 is the same pose as 235. |
| 6 | `thePinchTwistStoresTheSameWayTheArcDoes` | fix only the arc and leave the pinch. 198 → −162 with the twist +23; and the −7 dead-zone case and the 367 full-turn case. |
| 7 | `everyStoredAngleIsCanonicalOverASweep` (3689 cases) | **fold it as a clamp** instead of a fold: `norm180(deg − unwrapped) != 0` (clamping 205 to 180 gives −25). Or break the model/quad pair: `norm180(deg − base − delta) != 0`. Or drop the fold: the range check fails. |
| 8 | `theFoldIsIdempotentAndDeterministicAtTheSeam` | make the range `[−180, 180]` instead of half-open: `norm180(-180)` must be `+180`. Also pins `deg − startDeg − deltaDeg` is a whole number of turns. |
| 9 | `itIsAFoldNotAClamp` | clamp. `205 → −155` not 180, `-205 → 155` not −180, cos/sin identical to 1e-6, and the clamp's 0.0937 cos gap measured. |

Every expected number is derived in the check that uses it. I recomputed all of them by hand in
PowerShell before writing them down, which caught six of my own errors: the pinch full-turn value
(0, not −7), the sweep's pose-equivalence (must be the *folded* difference, not the raw one), the
model/quad invariant (**minus** `base`, not plus — with the plus sign it is 180 out at the first
case), the idempotence probe (same sign error), the cos threshold (0.0937, not 0.9), and the grab
angle (170°, so the steps actually cross `atan2`'s fold).

**What none of these catch.** Reverting `moved = carried`, the third-finger branch, or either
`h.rebaseGesture()` call still prints green — those are touch plumbing and need a device or an
instrumented `MotionEvent`, which the harness cannot build. That is finding 2's remaining half and
it is **not** mine; see Question 3. What these *do* catch is the call-site half of T4, which finding
2 correctly said had zero coverage.

## Symbols verified, and where I read them

- `TransformQuad.norm180` — `studiokit/.../TransformQuad.java:658-664`. Pre-existing, documented
  "Fold degrees into (−180, 180]. Deterministic at the seam: +180 stays +180." **I did not change
  its arithmetic** — it is shared with the mesh lane (`:836`, `:841`, `:848`, `:849`) and it is
  correct as written. Worth noting: the private copy I deleted from `TransformOverlayView` was
  **not** equivalent — a `while (deg < -180)` fold returns −180 where `norm180` returns +180, i.e.
  the duplicate quietly implemented `[−180, 180]`. Unreachable at the old call site (the delta was
  never exactly −180), but it is why the duplicate is gone rather than kept.
- `TransformQuad.wrapRad` `:636-642`, `detentCardinalDeg` `:302-309`, `centreFactor` `:650-652`.
- `TransformOverlayView.applyDrag` ROTATE `:2305-2345` (pre-fix), `applyPinch` `:2559-2614`
  (pre-fix), `snapRotation` `:317-327`, `ROT_DETENT_ENTER_DEG/EXIT_DEG` `:317-318` (post-fix),
  `PINCH_ROT_DEADZONE_DEG` `:325`, `Host` `:104-140`.
- `AffineTransformHost:211` (cos/sin read), `:348-359` (`newAngle` tie-break), `:424`, `:431`;
  `CornerPinTransformHost:332-369`, `:396`, `:485`, `:524-525`, `:567`, `:787-788`;
  `SpineTransformHost:145-149`, `:187-204`, `:294`.
- `KeyframeTrack.valueAt:64-84` (esp. the bare `a.value + (b.value - a.value) * eased` at `:80`),
  `KeyframeSet.valueAt:216-219`, `KeyframeSet.ROTATION:27`, `Easing.LINEAR.apply` falling through
  to `return t` at `Easing.java:61-97`.
- SPEC A: `tasks/specs/SPEC_A_rotation_beyond_360.md:22-27`, `:44-48`, `:67-69`, `:95-103`;
  `KeyframeSet.java:91-123`; `PipDrawerTabs.java:937-938`;
  `CornerPinTransformHost.java:524-527`.
- Harness: `run-gestureangle.sh` (mine), `run-spech.sh:16-38` for the `@argfile` + gson pattern,
  and finding 3's carve-out at `run-spech.sh:33-35`.
- The `androidx.annotation` jar I put on the classpath: I listed
  `~/.gradle/caches/modules-2/files-2.1/androidx.annotation/annotation-jvm/1.9.1/…/annotation-jvm-1.9.1.jar`
  and confirmed by reading the archive that it contains `androidx/annotation/NonNull.class` and
  `androidx/annotation/Nullable.class`.

## Findings 3 and 4 — observed, changed nothing

- **3** — the five `androidx` grep guards (`run-mesh.sh:41`, `run-puppet.sh:41`, `run-specq.sh:35`,
  `run-specr.sh:36`, `run-spect.sh:37`) versus `MeshPinPose.java:3`'s `androidx.annotation.NonNull`.
  Not mine. For whoever picks it up: I put the same carve-out in `run-gestureangle.sh` for the
  files *my* test loads, and scoped it to those five files rather than the directory, because
  `keyframe/KeyframeGlyph.java` genuinely does `import android.graphics.Path` and a directory-wide
  guard would false-red. That is the trap in the obvious fix.
- **4** — `run-fx.sh:39`'s `|| exit 1` makes line 40's `CurveMigrationTest` dead code, so "fx 137
  pass + 3 pre-existing failures" cannot have come from that script. Not mine.

## Unproven — all of it

I ran no gradle, no `javac`, and no `run-*.sh`. Specifically:

1. **Nothing compiles yet.** I never built `:studiokit`, `:app` or the harness. Expect a fix round.
2. **The new harness has never run.** `run-gestureangle.sh` now needs the annotation jar; if the
   orchestrator's environment cannot find it the script exits 1 with a clear message rather than
   silently skipping the interpolator checks — but that is an untested new failure mode.
3. **`GestureAngleTest` has never been executed.** I verified brace balance, method structure and
   imports mechanically, and I recomputed every expected value in PowerShell — but PowerShell's
   `double` is not Java's `float`, and the tolerances (1e-3, 0.05) are the thing that would absorb
   the difference. My hand check of the 40×9° and 38×9.5° accumulations used doubles and got exactly
   360 and 361; in `float` the sums drift by ~1e-5, which is why those two assertions use 1e-2 and
   0.05 rather than 1e-3. Someone should confirm the chosen tolerances are not accidentally loose.
4. **The harness `oneMove()` is a copy, not the production code.** If `applyDrag`'s ROTATE branch is
   edited again, the copy silently rots. It is guarded only by the KDoc naming the file. A
   better long-term answer is to move the whole branch into `TransformQuad`, which I did **not** do
   because `snapRotation` is `Context`-coupled and doing it would be a larger refactor than this fix.
   Question 4.
5. **No phone check.** The owner's T3 item 3 ("turn the spin arc a quarter turn anticlockwise, key
   it, key 0° a second earlier, play → it turns the short way") is exactly this finding and is
   still unverified on a device. It is the single most valuable remaining check.
6. **`AffineTransformHost:356`** — I added `norm180(newAngle)` after the nearest-the-start
   tie-break. I reasoned it is behaviour-preserving for in-range objects, but I did not run it.
7. I did not touch `text/tasks/todo.md` — AGENTS.md asks for a plan there, but that file has
   already been lost to a lane collision once (SPEC A's delivery record says so), and it is not in
   my owner area.

## Questions for the Lead

1. **SPEC A vs T4 — is the door split the right ruling?** I have folded every *gesture* write into
   `(−180, 180]` and left the *typed* field alone, on the grounds that they are different doors and
   SPEC A explicitly protects only the typed one. The consequence you should rule on: **a corner
   drag on an object the owner typed `720` into now collapses it to in-range.** Before my change
   `AffineTransformHost:348-352` would have carried 720 → 725 through the tie-break. This is the
   same hazard SPEC A's F1 already accepted for sliders ("a slider is in-window by nature"), but it
   is now true of three gesture doors instead of one, and it is your call, not mine. If the answer
   is "a typed winding must survive any gesture", then the tie-break at
   `AffineTransformHost:350-355` needs to stay relative to the **raw** stored value, which means
   `startRot` cannot be folded — and I would want that decision written down before I change it.
2. **`PreviewHandlesOverlay` (finding 5) needs an owner.** It is the same defect, live, in
   `overlay/`, outside my area. It needs four one-line folds plus `TransformQuad.norm180` (already
   public, same package name — no second helper). Can you assign it, and to whom?
3. **Finding 2's other half.** The T1/T2/T3 call-site halves (`moved = carried`, the third-finger
   `repickPinchPair`, both `h.rebaseGesture()` calls) still have no coverage that could ever go red,
   and I do not think that is fixable in a `tools/jvm-harness` script — there is no way to build a
   `MotionEvent` off-device. Do you want a different kind of test (instrumented, or extracting the
   touch state machine into something constructible), or is finding 2's remaining half being closed
   by another lane?
4. **Should the ROTATE branch itself move into `TransformQuad`?** That would delete my test's copy
   of it and make the tests bind to production code rather than to a mirror of it. The blocker is
   `snapRotation` being `Context`-coupled; it could be injected as an interface, or the branch could
   take the already-snapped value. I stopped short because it is a bigger refactor than this finding
   warrants and I would rather not reshape the overlay unasked.