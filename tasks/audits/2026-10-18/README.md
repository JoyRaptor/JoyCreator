# Joy Creator adversarial audit — 2026-10-18

Three parallel specialist reviews plus coordinating review of screen-recorder integration. Reviewed main HEAD `24fc23790`; inspected named JoyBrush worktrees without treating unmerged code as shipped. No application sources were changed. The owner subsequently authorized the Note 9, allowing the safe runtime checks below.

## Read the detailed reports

- [JoyBot: 12 defects, capability matrix, and harness architecture](joybot.md)
- [Viz Lab: 9 defects and CPU/GPU/thermal optimization plan](vislab.md)
- [Joy Brush: 3 data-loss defects, water scheduling, merge limits and optimization plan](joybrush.md)
- [Floating visualiser / screen recorder: 3 integration defects](recorder.md)
- [Note 9 verification, installed build identity, microbench results and evidence](phone.md)

The 28 findings include 14 P1 and 14 P2 items. Severity means engineering repair priority, not a claim that every issue reproduces on every device. Detailed reports distinguish source traces, CPU counterexamples, actual-production-code headless probes, and phone observations.

## Highest-priority findings

| Priority | Finding | Evidence |
|---|---|---|
| P1 | Separate JoyBrush drawings share a document ID and can overwrite the same external backup. | Source trace and CPU counterexample; destructive device repro intentionally avoided. |
| P1 | Opening a JoyBrush document can retain the old media window and write old pigment into the new drawing. | Source trace of load → frame → flush; still present in inspected integration WIP. |
| P1 | Fully black/erased mask tiles can be omitted from the fresh-canvas snapshot, so hidden painting reappears. | Source trace and shader/serialization counterexample. |
| P1 | JoyBot turns malformed native tool arguments into `{}` and executes default behavior; targeted transition removal can become remove-all. | Native parser and legacy tool default traced. |
| P1 | Long JoyBot jobs can overwrite subsequent user edits or resurrect a deleted clip. | Async analysis → whole-project adoption traced; no revision guard. |
| P1 | Stop/Send and project-switch paths share cancellation/history state between jobs. | Request scheduling, cancellation and UI listener traces. |
| P1 | A native batch can propose a plan then edit before approval. | Approval/navigation checks occur after batch dispatch. |
| P1 | JoyBot retiming an overlay to its existing end can collapse the clip to 1 ms; changing audio start/end can use the wrong duration. | Endpoint mapping returns -1; setter clamps; old-offset calculation traced. |
| P1 | Viz Lab can permanently acknowledge a failed save and never retry it. | Sent revision stored before I/O; failure only logged. |
| P1 | Two distinct Words layers invalidate one shared atlas every frame. | Actual WordFeed probe: 1,440 1-MiB slot uploads and 120 mipmap builds over 60 frozen frames; stable feed: zero. |
| P1 | Pause keeps expensive Viz Lab rendering active. | Note 9: paused Play icon, continued ~60 fps, ~28–29% of one CPU core for GL thread in sampled readings. |
| P1 | Floating speaker/phone-audio consent terminates an ongoing screen recording. | Note 9 reproduction and MediaProjection-stop/finalization log. |
| P1 | Full-screen floating overlay can block touches to other apps on Android 12+. | Window alpha/flag trace and official Android contract; Note 9 runs Android 10, so this device cannot validate it. |
| P2 | Closing the floating visualiser leaves its recording-toolbar label On. | Note 9: service absent, projection null, label still green On. |

## JoyBot against the intended product

JoyBot has meaningful Studio editing and inspection tools and genuine Viz Lab scene/layer/knob actions. The main gaps relative to a universal user/app/agent harness are:

1. **JoyBrush has no drawing command adapter.** Navigation/help exist, but painting, board/layer/stroke/frame manipulation and paint-context observation are not registered tools. A more capable model cannot invoke an API that does not exist.
2. **The agent cannot see the finished result.** `look(playhead)` retrieves source footage rather than the composed Studio frame. There is no rendered paint/VisLab observation endpoint either. The host cannot reliably verify layout, occlusion, visualizer placement or paint edits from those source images.
3. **State ownership is not uniform.** Studio snapshots, app-level VisLab changes, background jobs, user edits, undo, save receipts and recovery do not share a versioned transaction boundary. The failures above arise at those boundaries.
4. **Pluggability is provider compatibility today.** The client primarily accepts Chat Completions-compatible models; it does not yet offer a durable external-agent session/protocol with common document handles, observation and command receipts across apps.
5. **The 90% free-tier target is not measured.** Tool routing and typed schemas are useful foundations. Remaining legacy defaults, malformed-call handling, growing history, false-success paths and cancellation conflicts directly undermine weaker models. Evaluate task success and unintended edits against expected document postconditions, per model, before claiming a completion rate.

Recommended backbone: a common adapter exposing document ID/revision, live selection/playhead, paged objects, typed commands, expected-revision commits with operation IDs, actual rendered observations, events, undo and durable commit receipts. UI actions and agent actions should use the same command boundary.

## Performance priorities

**Viz Lab:** stop paused/hidden render and capture work; fix per-layer word atlas ownership; prune deleted image textures; share audio analysis; use explicit frame and simulation budgets for Lab versus floating/recording hosts. Existing adaptive resolution and HeatGuard help but do not bound atlas churn or simulation count. Build Studio integration around deterministic frame/audio/seek sessions; current main does not implement that adapter yet.

**Joy Brush:** retire quiet water components and process active regions rather than a broad wet rectangle; separate immediate brush feedback from bounded water catch-up at a stable dt; avoid oil-relief look work for pure watercolor where equivalence is proven; complete bounded atlas residency; make board previews viewport-aware; bound save/readback memory peaks. Existing scissoring/ping-pong/uniform reuse must remain in the baseline rather than being counted as new gains.

The Note 9 microbench found ink latency p50 about 7–9 ms, versus about 185–214 ms for the two tiny watercolor strokes; oil was variable and had a 524 ms worst busy frame. Media texture inventory was 215–219 MiB. These are small exploratory samples on the identified installed artifact, **not steady-state FPS, a sustained thermal test, or proof of a 10× whole-app improvement**. Sparse active-region workloads are plausible candidates for order-of-magnitude work reduction; broad fully wet paintings need separate expectations.

## Merge/flatten status

Reviewed main has no exposed Merge Down/Flatten implementation to certify. The inspected layer-ops WIP refuses media/animated merge cases; its newer flatten work still needs dedicated GPU/failure-rollback/undo/save/reopen review. NativeLines v1 structural/history risks have hardening work pending in separate branches. None of those pending pieces are treated as finished, failed merely for being unfinished, or already integrated.

## Verification and repair order

Existing ActionRegistry and IdeaEngine checks: **424 passed**. Existing Viz look tests passed for 26 primitives/39 looks, and feature parity passed for 78 samples/21 kicks. Actual-code headless probes established glyph atlas churn, stale PCM remaining loud and zero-dt analysis advancing. The Note 9 confirmed pause load, speaker-consent recording interruption and stale toolbar state. Six isolated JoyBrush microbench reports were collected; 13 pre-existing JoyBrush files were byte-identical afterwards. No new APK was built or installed.

Repair order: protect document identity/persistence and old simulation ownership; harden command validation, state isolation and revision-checked commits; fix recorder capture ownership and overlay input; remove paused/atlas waste; add common rendered observation and JoyBrush commands; then measure end-to-end free-model task completion and sustained combined recording/rendering loads.
