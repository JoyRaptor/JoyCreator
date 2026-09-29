# JB-0.06 — Hidden pen-diagnostics overlay (the built-in "probe")

| | |
|---|---|
| **Tier** | T2 (owner uses it) |
| **Status** | 🟧 Built — see ROADMAP.md |
| **Depends on** | JB-0.05 |
| **Owner area** | NEW `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/diag/PenDiagnosticsView.kt`; EDIT `JbCanvasView.kt` (add ONE callback property, below); EDIT `joybrush-android/.../JoyBrushActivity.kt` (add the overlay and its toggle) |
| **Estimated size** | ~220 lines |

## Goal
The owner's Note 9 and Note 20 report tilt (owner, 2026-09-28) but nobody has measured the ranges,
the lean direction convention or the sample rate. This overlay shows the raw numbers live and copies
a report to the clipboard, so calibration uses real values.

## Decisions
1. `JbCanvasView` gets `var onRawEvent: ((MotionEvent) -> Unit)? = null`, called at the top of both
   `onTouchEvent` and `onHoverEvent` (before any other handling). Nothing else in the view changes.
2. `PenDiagnosticsView` (a plain View, semi-transparent dark panel, monospace text, top-left, not
   touchable — `isClickable = false`, returns false from onTouchEvent) shows, updated per event:
   tool type; action; pressure (3 decimals); AXIS_TILT in degrees; AXIS_ORIENTATION raw in degrees;
   the azimuth `AxisMapping` computes, in degrees; AXIS_DISTANCE (hover); button state; historySize;
   samples per second over the last second; a text histogram of intervals between samples (buckets
   <3, 3–5, 5–8, 8–12, 12–20, >20 ms); running min/max of pressure and tilt since shown.
3. Toggle: **long-press the "×" close button** in the activity shows/hides it (hidden by default).
   A "Copy report" pill appears with it: copies all current values + min/max + histogram as plain
   text to the clipboard (ClipboardManager).
4. No effect on drawing when hidden (the callback only stores numbers; the view redraws only while
   visible, at most 30 fps).

## Verification
Watcher green; sandbox phone screenshot with the overlay showing tilt changing as the pen leans.
Then the OWNER runs it on the Note 9 and Note 20 and pastes both reports into
`tasks/joybrush/research/DEVICE_PEN_REPORTS.md` (new file; the owner or an agent on his behalf).

## Do not
No calibration logic yet (a later spec reads the reports). Don't change input handling.

## Definition of done
Screenshot · watcher green · commit `JB-0.06: pen diagnostics overlay` · ROADMAP row → 🟧 Built.

## Questions

### For the Lead — the panel's colours are hex literals, against the JbColors rule (non-blocking, built anyway)

`JbColors` states "no hex literal, ever" for Joy Brush code, but `PenDiagnosticsView` lives in
`joybrush/androidkit`, which `build.gradle.kts` says has **no Android resources at all** and which
`joybrush-android` cannot be made to share (`jb_tokens.xml` lives in the app-facing module, and an
Android library cannot see the consuming app's resources). So the panel's fill and ink are two named
top-level literals in that one file (`PANEL_COLOR`, `TEXT_COLOR`) with a comment saying why. Is a
debug-only panel exempt from the token rule, or should `jb_tokens.xml` grow a pair of "diagnostics"
tokens and the file be moved into `joybrush-android`? Left as-is: the spec asks for a
"semi-transparent dark panel" and that is what it draws. Not a blocker; the build is green.

### Two readings the builder had to pick (documented in the code and in the copied report itself)

- "intervals between samples": the panel counts them between consecutive **samples** (historical +
  current, so the 240 Hz path inside one event is counted), over a sliding 1 s window, and the same
  window is the "samples per second". A pause between strokes therefore empties the histogram
  instead of landing in the `>20 ms` bucket.
- "running min/max … since shown": tracked for **pen** events only, and cleared each time the panel
  is shown. The copied report says both of these in its own header so the numbers cannot be misread.

