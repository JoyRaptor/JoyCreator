# Adversarial review — JB-2.02 Zoom / pan / rotate + tap gestures

- Reviewer: muse-spark (cross-reviewer; builder was an unrelated subagent — different family).
- Task status: 🟧 Built. Commit reviewed: `83543c9f`.
- Spec reviewed: `tasks/joybrush/specs/JB-2.02_view_and_gestures.md` (ViewTransform contract, CanvasGestures machine, the 5 view edits, tests 1–6 + 8 Questions with rulings).
- §5b checks: diff touches only NEW `ViewTransform.kt` + test, NEW `CanvasGestures.kt`, the 5 listed `JbCanvasView` edits, board row, spec questions — inside the owner area; engine untouched per "Do not". Suite run by me: `ViewTransformTest` 16/16, 0 failures (fresh `:core:jvmTest` 410/0); `:androidkit:compileKotlin` green inside my `:androidkit:test` run; watcher-green per board note. Device pinch/rotate/tap + screenshot are owner's (no adb here). Reviewed against committed code.
- Severity scale per §5b: BLOCKER / MAJOR / MINOR.

## Finding 1 (MAJOR): the spec-named mid-stroke handoff to the gesture machine is dead — pinch can't start until every finger lifts
Proof:
1. Spec edit 3 (routing): "the moment a second finger lands during a finger stroke, cancel that stroke and hand the event stream to `CanvasGestures`."
2. Committed `JbCanvasView`: POINTER_DOWN during a finger stroke calls `cancelStroke()` (drawing=false) and falls through to `if (!drawing) gestures.onEvent(ev)`.
3. `CanvasGestures.pointerDown`/`move` both begin `if (!active) return` (`CanvasGestures.kt:117, 126`) and `active` is set true only in `down()` on ACTION_DOWN (`:98-114`). The machine never saw this gesture's DOWN (it went to the drawing stroke), so it consumes-but-ignores every subsequent event: pinch/zoom/rotate do nothing until all fingers lift and a fresh DOWN arrives.
4. The machine's own `onEvent` kdoc (`:67-70`) promises the opposite ("a gesture that started in a drawing stroke still has to be seen through to its last finger") — code contradicts kdoc and spec together.
5. Reverse direction, same root: pen POINTER_DOWN mid-gesture calls `gestures.reset()`; after the pen lifts, the still-down finger moves into a dead machine the same way.
User impact is friction, not loss (lift and re-place works), but the promised behaviour does not exist. Fix needs a mid-stream entry (`takeover(ev)` seeding down-state from live pointers) — new API, Lead/T1 call, not a tweak. Filed MAJOR (spec-named behaviour broken with proof).

## Verified (proof — the maths was hand-checked, not just test-counted)
- `docToScreen`/`screenToDoc` exact inverses (rotation-transpose + zoom-divide verified term by term, `ViewTransform.kt:52-71`); `docToClip` column-major matrix re-derived from both transforms (m00/m01/m10/m11/m02/m12 all match, y-flip correct, `:83-95`); `applyPinch` holds the old-centroid document point across rotate+scale+reposition in the right order (`:106-114`); `snapRotation` keeps the last centroid fixed with the 7° window (`:122-133`); `fit` centres with 5%-per-side margin and degrades sanely on zero/negative/NaN rects (axis ignored via MAX_VALUE, NaN pan ignored by the setter, zero view → MIN_ZOOM floor).
- Every write guarded (R1-style, `:26-28`): zoom clamped 0.05..64, rotation wrapped (no float drift), pan finite-kept, non-finite/non-positive `scaleFactor` ignored, `docToClip` degrades 0-size viewports to 1 px (finite matrix into GL, never poison).
- Tap machine: 250 ms / 20 px with history-aware wander (fast flick-back can't fake a tap), unknown-pointer ⇒ strayed (documented conservative choice), 2/3/4-finger mapping in one 3-line function (assignability preserved for JB-2.02b), rebase-on-pointer-change (no jump), dead-zoned rotation arming that applies nothing on the arming frame (no dead-zone jump), short-way-round turns, `MIN_SPREAD_PX` translate-only fallback.
- View edits 1–5 confirmed in committed code: `view.docToClip` in `onDrawFrame`, `screenToDoc`+rotation+`view.zoom` smoother in feed/start, routing with palm-wins + pen-takes-over + second-finger-cancels, undo/redo/requestRender wiring, first-layout identity with never-touch-again guard (matches Q1's ruled outcome).
- Phantom-event safety: pen UP reaching the machine after a drawing stroke hits `!active → return` — every pen tap verified harmless to tap counting.
- Spec Q3 (palm wins), Q4 (two fingers navigate pre-pen — sandbox-testable), Q7 (hard-coded tap map), Q8 (12 px spread, arming frame) all implemented as ruled.

## Explicitly not filed (already owned)
- Q5 (`Brush.sizePx` now document px — 12 px brush = 48 screen px at 4×): confirmed real, behaviour change with no control yet; correctly referred to the Lead/JB-2.16.
- Q6 (four Floats cross to the GL thread unsynchronised — plain `var`s in `ViewTransform`, GL-thread read in `onDrawFrame`): confirmed; worst case one wobbled frame, never a torn value (Floats don't tear) or a wrong document. T1 list, as referred.

## Recommendation
Fix Finding 1's handoff (or downgrade the spec/kdoc promise) — everything else in this task is sound and well-pinned (16/16). No BLOCKER open.
