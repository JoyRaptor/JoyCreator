# Watercolour live integration — October23

Branch: vector/watercolour-live-20261023. Checkout:
C:/Temp/jb-watercolour-live-20261023. Initial source origin/joy-creator e5a0f02c0.
Owner explicitly requested briefs/CODEX_WATERCOLOUR_LINE_LIVE_20261023.md.
Primary checkout is dirty and is not used for implementation or builds.

## Execution plan

- [x] L1: five runs each editable lines OFF/ON, with cold/warm distinction,
  glFinish-bracketed frame percentiles, synthetic input latency and complete
  pooling fade/lift-to-final. Record APK/source identity and phone temperature.
- [ ] L2: measure existing startup prewarm, then prepare only missing watercolor
  programs/assets before first stroke. Five cold-launch comparisons.
- [ ] L3: per-brush editable-line reachability, owner preference pending;
  coordinate UI G1 and preserve other brushes/layers.
- [ ] L4: persistent wash frame follows line transforms; compatible Select API,
  recolour finish-only, move/rotate/rebrush/delete each one Undo.
- [ ] L5: save/reopen/context-loss and first edit, frozen brush without library.
- [ ] L6: preserved POC diagnostics and owner look comparison on the Note9.
- [ ] L7: repeat full timing matrix, target16.7ms p95 at300px/15% spacing.

No renderer fixes precede the L1 baseline. Diagnostic instrumentation may be
needed: current BenchRunner tail250ms truncates the1000ms pooling fade. Any
instrumentation-only baseline build must be labeled and retain existing renderer
behavior, stroke-only metrics and complete-span timing separately.

Lead/hot-file notification was posted to the canonical AGENT_BOARD before edits.
At startup UI owns jb-phone.lock and Lead owns jb-gradle.lock; no takeover.
Phone identity must resolve only SANDBOX_SERIAL from private devices.local.sh,
without serials in output. Never monkey, uninstall, clear data or touch Note20.
Luna helpers traced measurement and wash-frame/codec boundaries read-only.

## Results

Pending L1 measurements; no new phone/build/appearance acceptance yet.

## L1 baseline — October24

Diagnostic source `2d25701d2` (renderer unchanged from base e5a0f02c0).
FrameStats tests: 2 passed, no failures/skips. Androidkit main/test compilation
passed; APK `assembleDefaultDebug` BUILD SUCCESSFUL in4m42s.
Installed in-place on the Note9; lastUpdateTime2026-10-24 03:00:44.
Built and installed base APK SHA256 verified before and after all runs:
D9D09CE43FA4D1B309FB9CE2249D3DD42118AFC0CEA4ADC947109F5C96F6FCDA.

Ten separate force-stop/start launches alternate OFF/ON, five each. Each launch
runs the deterministic841-report topScribble twice: process-cold then same-process
warm. This preserves current startup prewarm/300ms job delay, and does not clear
OS/driver shader caches. Size300px, spacing15%, shipped watercolor preset.
Raw logs and script: C:/Temp/jb-watercolour-ops/live-baseline-20261024.
Machine-readable retained results: evidence/WATERCOLOUR_L1_20261024.json.

Timings are intrusive glFinish-bracketed CPU+GPU diagnostics. Input latency means
synthetic event dispatch to the next frame start, not physical pen-to-photon.
First-frame start excludes that frame's own rendering cost. Stroke percentiles
keep the existing down-through-UP+20ms filter; fullSpan metrics include the tail.
Idle was polled every100ms with two confirmations, so lift-to-idle and last GPU
frame end are observation bounds, not exact fade-commit timestamps. None timed
out. The first ever process run paid a large driver/program initialization cost;
its few stroke-filtered frames must not be interpreted as a representative warm
frame distribution. All runs remain in the table rather than discarding it.

| Lines | Launch | State | First frame start ms | Busy p50 / p95 ms | Input p50 / p95 ms | Idle after UP ms | Last GPU frame after UP ms | Temp C before / after |
|---|---:|---|---:|---:|---:|---:|---:|---:|
| False | 1 | process-cold | 138.74 | 1484.82 / 1484.82 | 797.54 / 1466.81 | 2217 | 2219.93 | 23.7 / 23.8 |
| False | 1 | same-process-warm | 7.35 | 16.75 / 65.7 | 12.07 / 74.29 | 1228 | 1231.08 | 23.7 / 23.8 |
| True | 2 | process-cold | 14.51 | 17.65 / 81.31 | 14.83 / 151.89 | 587 | 589.3 | 23.8 / 23.9 |
| True | 2 | same-process-warm | 282.18 | 19.75 / 73.46 | 15.98 / 161.31 | 537 | 539.02 | 23.8 / 23.9 |
| False | 3 | process-cold | 181.08 | 16.85 / 55.82 | 13.58 / 102.53 | 1240 | 1246.93 | 23.9 / 23.9 |
| False | 3 | same-process-warm | 11.27 | 17.57 / 48.29 | 11.05 / 44.26 | 1198 | 1221.35 | 23.9 / 23.9 |
| True | 4 | process-cold | 15.83 | 27.03 / 64.73 | 17.22 / 104.04 | 546 | 547.8 | 23.9 / 24 |
| True | 4 | same-process-warm | 6.41 | 17.26 / 45.44 | 11.86 / 52.01 | 614 | 623.16 | 23.9 / 24 |
| False | 5 | process-cold | 177.05 | 21.46 / 77.84 | 22.9 / 87.81 | 1199 | 1226.9 | 24 / 24.2 |
| False | 5 | same-process-warm | 6.03 | 17.52 / 42.61 | 12.2 / 60.81 | 1283 | 1286.53 | 24 / 24.2 |
| True | 6 | process-cold | 15.89 | 28.09 / 75.25 | 19.87 / 104.98 | 529 | 530.51 | 24.2 / 24.2 |
| True | 6 | same-process-warm | 6.71 | 16.32 / 52.67 | 11.35 / 59.18 | 582 | 590.67 | 24.2 / 24.2 |
| False | 7 | process-cold | 180.2 | 17.44 / 77.06 | 15.09 / 112.14 | 1191 | 1236.73 | 24.2 / 24.4 |
| False | 7 | same-process-warm | 5.97 | 17.37 / 50.7 | 11.37 / 45.15 | 1180 | 1182.99 | 24.2 / 24.4 |
| True | 8 | process-cold | 16.16 | 20.86 / 77.77 | 14.42 / 102.53 | 624 | 627.65 | 24.4 / 24.6 |
| True | 8 | same-process-warm | 5.97 | 16.81 / 52.12 | 10.86 / 47.45 | 509 | 516.56 | 24.4 / 24.6 |
| False | 9 | process-cold | 173.34 | 16.29 / 63.66 | 12.78 / 85.35 | 1219 | 1242.14 | 24.6 / 24.6 |
| False | 9 | same-process-warm | 4.1 | 16.35 / 35.75 | 10.38 / 36.02 | 1262 | 1265.5 | 24.6 / 24.6 |
| True | 10 | process-cold | 15.96 | 20.01 / 66.22 | 13.76 / 103.04 | 663 | 665.12 | 24.6 / 24.8 |
| True | 10 | same-process-warm | 10.62 | 18.07 / 45.83 | 11.28 / 45.76 | 531 | 567.22 | 24.6 / 24.8 |

L1 measurement complete, performance NOT accepted. Ordinary fade observations~1.2s (first~2.2s); line fade~0.5–0.66s. Source trace: UP calls reportHistory; with a board document that calls setBoardDocument, whose checkStrokeEnded settles watercolor immediately. Keep history publication from collapsing the cosmetic tail. Existing media prewarm does not compile/upload vector watercolor programs/assets; live WatercolourPass.begin still pays those lazily. L2 must prepare selected resources non-destructively, with graphics-context ownership and GL state preserved. Full-resolution pooling remains unchanged.
