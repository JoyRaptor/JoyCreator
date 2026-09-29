# Adversarial review — JB-0.06 Hidden pen-diagnostics overlay

- Reviewer: muse-spark (cross-reviewer; builder was an unrelated subagent — different family).
- Task status: 🟧 Built. Commit reviewed: `f25837b3`.
- Spec reviewed: `tasks/joybrush/specs/JB-0.06_pen_diagnostics.md` (decisions 1–4, verification, Do-not + the Lead colour question + two documented readings).
- §5b checks: diff touches only NEW `PenDiagnosticsView.kt`, the ONE `onRawEvent` property + two first-line invocations in `JbCanvasView.kt`, the activity toggle/copy pill, the ROADMAP row, and spec questions — inside the owner area; input handling untouched. Watcher-green evidence in the board Who note; the sandbox screenshot and owner `DEVICE_PEN_REPORTS.md` are owed (no adb here). Reviewed against committed code.
- Severity scale per §5b: BLOCKER / MAJOR / MINOR.

## Finding 1 (MINOR): readings use pointer index 0 even when the action is about another pointer
Proof: `PenDiagnosticsView.kt:109-117` — `getToolType(0)`, `getPressure(0)`, `getAxisValue(..., 0)` unconditionally, while `actionName` reports e.g. `PTR_DOWN` for the `actionIndex` pointer. With two fingers down the panel shows finger 1's pressure/tilt beside finger 2's arrival — tool/action mismatch. For the panel's purpose (single-pen tilt/lean calibration) index 0 is the right pointer essentially always, but the mismatch is silent. Fix is `ev.actionIndex` for the DOWN/POINTER_DOWN/UP readings, or one kdoc line stating index-0 semantics.

## Finding 2 (MINOR): lean azimuth assumes zero canvas rotation
Proof: `PenDiagnosticsView.kt:116` — `AxisMapping.androidOrientationToAzimuth(orientation, 0f)`. The drawing path passes the live `view.rotation` (`JbCanvasView.kt` feed); the probe hardcodes 0. If the owner rotates the page (JB-2.02) and then measures the lean convention for the calibration table, every azimuth is off by the rotation. Same one-line fix class as Finding 1 (pass the view's rotation or note the assumption in the report header).

## Finding 3 (MINOR): `report()` does not prune the window, so an idle clipboard gets a live-looking rate
Proof: `PenDiagnosticsView.kt:142-153` — `report()` calls `rebuild()` directly; `prune(now)` only runs inside `onRawEvent`. Copy the report a minute after the last touch and it still says "rate 240/s" with a full histogram. The numbers were true when last measured, but the report presents them as current with no timestamp. Fix: `prune(SystemClock.uptimeMillis())` (plus `rebuild()`) at the top of `report()` — the header already documents the window semantics, so the code should match the header.

## Verified (proof)
- Decision 1: `onRawEvent` is the first line of both `onTouchEvent` and `onHoverEvent` (committed `JbCanvasView` lines 197/206); nothing else in the view changed.
- Decision 2 contents all present: tool/action/pressure-3dp/tilt-deg/orientation-deg/computed-azimuth-deg/distance/buttons/history/rate/gap-histogram with the specified `<3…>20` buckets (`bucketOf`, `:240-247`)/pen-only min-max since shown (`:119-130`, cleared on show via `reset()`).
- Decision 3: long-press × toggles (`setOnLongClickListener { toggleDiagnostics(); true }` — the `true` keeps it from also closing), hidden `GONE` by default, Copy pill visible with the panel.
- Decision 4 (no effect while hidden): `if (!isShown) return` is the whole hidden cost (`:102`); visible redraws throttled to 33 ms (`:132-135`); `onTouchEvent` returns false and the view is not clickable/focusable (`:71-72, :87`).
- The two documented readings (sample-based intervals over a sliding 1 s window; pen-only min/max) match the implementation and are restated in the copied report's own header (`:149-150`).
- Colours: the only hex literals in the module are the two named panel constants with the why-comment (`:18-22`); the exempt-or-move question is already before the Lead in the spec — endorsed, not duplicated.

## Recommendation
No send-back. Three MINORs (all wrong-or-stale numbers in a tool whose numbers feed calibration — worth fixing, none blocks). Device screenshot + owner reports remain owed.
