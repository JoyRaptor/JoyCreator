# Watercolour live integration — October23

Branch: vector/watercolour-live-20261023. Checkout:
C:/Temp/jb-watercolour-live-20261023. Initial source origin/joy-creator e5a0f02c0.
Owner explicitly requested briefs/CODEX_WATERCOLOUR_LINE_LIVE_20261023.md.
Primary checkout is dirty and is not used for implementation or builds.

## Execution plan

- [ ] L1: five runs each editable lines OFF/ON, with cold/warm distinction,
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
