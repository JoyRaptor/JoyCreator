# JoyBrush lead handoff — October 9, 2026

Read this first for the current checkpoint; ROADMAP.md and individual specs define the product. Older LEAD_DESK entries are historical and may have stale completion statuses. Update this handoff when integrating work, not when a worker merely finishes writing it.

## New agent: start here

This is the single entry document. Read it fully, then START_HERE.md and applicable AGENTS.md. Owner instructions override documents. The current queue below overrides historical ROADMAP readiness; the roadmap remains the full product/spec index. A frontier lead can be Codex or Claude; historical Claude-only wording does not restrict the owner's appointed lead.

1. Assess actual capabilities: **frontier** for architecture/artistic/GPU correctness ownership; **mid** for objective bounded implementation. Vision access alone does not establish artistic judgment. When uncertain choose mid. Never promote yourself to unlock a task.
2. Read coordinator STATE and check current Git/worktrees before claiming. Existing live assignments have priority. No duplicate A1 worker: its amendment is already ready.
3. Use the canonical shared file at `C:/+Projects/Screenrecorder/FadCam/tasks/joybrush/CURRENT_HANDOFF.md`, even from a separate worktree. Run `tasks/joybrush/Claim-JoyJob.ps1` with `-Job`, `-Agent` (unique chat/run ID), `-Tier frontier|mid`, and `-State Doing`. The script atomically checks readiness and marks ownership. If it fails, do not begin. Merely reading this document cannot start an agent: tools and workspace access are required.
4. Claim only Ready rows that fit your tier. Read the row's contract and evidence requirements. Use an isolated worktree and own only the approved paths; overlapping paths require the lead's explicit sequencing. Mid-tier agents do not invent missing architecture/specifications or take Blocked work. If nothing fits, report that fact and stop instead of inventing work.
5. Do the claimed job, adversarially check it, then use the same script with `-State Review -Evidence "commit hash; checks; remaining limits"`. Review means submitted, not integrated. Use Blocked for a concrete blocker. Only the lead sets Ready or Integrated. Never set owner phone acceptance yourself. Do not auto-reclaim a quiet job; inspect its owner first.
6. Free OpenCode work goes through the authorized coordinator and its skill/runner; no direct shared-branch push. Batch reports. The lead commits the canonical queue/status updates with integration so later agents retain the history. Claims are shared on this PC; agents on another PC must coordinate with the lead before working.

Never overwrite uncommitted canonical claims during a pull/sync. Read the shared file again before any update. If a claim command encounters the exclusive lock, retry after the current update finishes; do not bypass it. `-LeadUpdate` is a policy-controlled lead override, not identity authentication. These guards prevent duplicate claims by cooperating agents, not agents that ignore instructions.

Example (from any checkout, passing the canonical document explicitly):

```powershell
& 'C:/+Projects/Screenrecorder/FadCam/tasks/joybrush/Claim-JoyJob.ps1' -Job JB-NOW-01 -Agent 'your-unique-chat-id' -Tier frontier -State Doing
```

## Live queue — only Ready jobs may be claimed

| Job | Tier | Status | Owner | Evidence |
|---|---|---|---|---|
| JB-NOW-01 | frontier | Ready | — | A1 integration contract below |
| JB-NOW-02 | frontier | Blocked | — | Dry-window architecture; depends on 01 and approved implementation slices |
| JB-NOW-03 | frontier | Blocked | — | Physical tilt/memory diagnostic; lead must reserve device and approve measurement plan |
| JB-NOW-04 | frontier | Blocked | — | JB-2.40 adoption; depends on architecture reconciliation and scoped ownership |
| JB-NOW-05 | mid | Blocked | — | No new free coding assignment ready; lead must publish owned paths plus objective acceptance for each leaf |
| JB-NOW-06 | frontier | Blocked | — | JB-5.20/5.40 product wiring; depends on JB-2.40 gates |

**JB-NOW-01 contract:** review existing 487a8453, amendment 72ae66d4 and checker 542f2482 in `C:/Temp/jb-opencode-dry-paper-only`; do not rebuild the optimization. Coordinator reports actual amendment compile PASS (50s) and source lifecycle review PASS. Independently inspect scope and cleanup/wake semantics against current main, integrate only those approved changes, record actual checks/hash and update this checkpoint. Own the four A1 production paths listed below, the new parity checker and this handoff; coordinate before any overlap with the protected media lane. Preserve existing GPU proof unless source changes invalidate it. Do not claim device speed, full clipping, Undo or JB-2.40 completion. Runtime sleep/wake remains an explicit device gate. Read build/resource guards before verification; no automatic installation/device reservation.

## Owner direction

Make JoyBrush dependable for painting, then connect the animation/vector tools. Follow Claude's board ownership specification, including active-board layer previews cropped to that board and normal previews when passive. Raster and future vector brushes must share a sound foundation. Owner is an artist; agents own engineering and verification. Test scratches are disposable; do not spend effort preserving or converting them as valuable user artwork. Paper/brush artistic direction stays with frontier owners. Codex leads until Claude's October13 recovery.

Weekly allowance last reported4%. Conserve paid supervision: free workers get bounded jobs in separate worktrees; one consolidated report per batch, exceptions only for decisions requiring the lead. No speculative filler audits. Do not restart living workers or rerun unchanged passing checks.

## Durable source and current lanes

- Shared branch `origin/joy-creator`, root `codex/region-routing`: published checkpoint **b16ab93e**. Root checkout `C:/Temp/jb-region-routing`; primary `C:/+Projects/Screenrecorder/FadCam` last synced **3c81b509** (newer published benchmark/planner still need primary fast-forward). Check actual Git heads before acting.
- Protected original Claude media WIP: `C:/Temp/jb-media`, branch `media/jb-2.40`, head019739ab plus local edits. Do not reset, overwrite or edit it without coordinating its owner.
- Root media adoption: `C:/Temp/jb-root-media-integration`, staged UNTESTED019foundation plus captured WIP on7087. Not a completed JB-2.40 implementation. Review before integrating; current main's media optimizations must be reconciled.
- WIP capture `C:/Temp/jb-media-wip-review-20261008.patch`, SHA256665EC6A578CF414CB1B1F7ADF73804C23263E364BDBB5B85C1667EDC74D61405. Local-only patch/worktrees must be retained until reviewed work is committed; Git does not back them up automatically.
- OpenCode coordinator chat **01a1191f-745d-7040-aedb-a08f02a2afa0**. State/logs: `C:/Users/JoyRaptor/.codex/opencode-coordinator/STATE.md`. This file identifies live handles and queued work; read it before dispatching duplicates. Skill: `C:/Users/JoyRaptor/.codex/skills/joybrush-opencode/SKILL.md`.

## Verified work — do not rebuild

| Work | Actual evidence / limits |
|---|---|
| Board transaction memory and sparse transfers | 8fc958c2; backend325 pass. Configured Undo allowance, not whole-process GPU governor. Successful GPU operations/Undo still need device checks. |
| Shared Animation filmstrip/scrubber; Sprite preserved | 196e3c2d; native70 pass. Physical phone appearance/performance pending. |
| Armed Tile periodic paper brush wiring | be616a0a; CPU sampler22 checks, GPU112 probes/56 seam pairs and broken controls pass. Canonical paper preserved when unarmed. Media/fluid tiling remains refused; phone cost unknown. |
| Rotated pencil dirty bounds and memory-warning logs | 1ee81b4c; actual bounds regression1 pass, Androidkit compile39s. Does not solve entire multi-window clipping or prove memory warning fixed. |
| Large fast-C baseline | 8617b762/644c34ce; eight tests. 750screenpx/60ms raw8→64 changes generated points4628→4726 and error26.4→0.31px. Desktop CPU only; do not thin raw input on this evidence. |
| Render optimization, traces and direct upload reuse | Through3c81b509;80 actual shader cases exactRGBA8,12 buffer tests and compile pass. No changed input/physics/precision. Trace markers are CPU submission, not GPU completion. |
| Paired render benchmark | 68a48aef; sized before/after pixel proofs and valid paired GPU queries. SwiftShader software GPU, variable modest gains; no phone or3–4x claim. |
| Dry geometry and spatial planner | 95e1bdd9/1aa40e69;20/29 actual targeted tests. Pure UNWIRED metadata, not the clipping fix. |
| Plain-over-media bake reference | f72b0fc5; nine CPU checks. Not GPU plain-write integration or completion of media slice2. |
| Export regression checks | 75b02d01/758b3533; three actual tests. Sprite copying is a test byte model, not GPU blit. Animation INK missing-lookup refusal is current boundary, not desired final behavior. |
| Existing R51 selection, ink tiles/export, fill tracing, composer and tween math | Follow ROADMAP/LEAD_DESK and MEASURE_R51_JVM. Composer/Undo math and tween math already built; remaining runtime/UI/storage must use them. |

## Unmerged A1 optimization — resume the existing job

Paper-only dry apply/copy: production **487a8453**, checker **542f2482**, local separate lane `C:/Temp/jb-opencode-dry-paper-only`. Four production paths: MediaGl.kt, MediaLayerEngine.kt, jb_media_apply.frag, jb_media_copy.frag. Actual compile passed; synthetic-delta raw Float32 and renderedRGBA8 proofs passed. Does not cover full dab/window/Undo/device.

Root held publication: sleep deleted the two new programs, forcing recompilation after10s idle. Coordinator completed amendment **72ae66d4** (actual compile50s and scoped lifecycle source review PASS) to retain programs through same-context sleep/wake, while deleting/recreating FBOs attached to replaced textures; delete programs only at release/context discard. Read coordinator STATE and independently review the existing amendment before merging. Preserve source proof; do not regenerate unchanged heavy GL fixtures. All assigned free implementation jobs are now ready for lead review; no replacement worker needed.

## Remaining critical path, in order

1. Finish/review A1 amendment; integrate scoped code/checker only after evidence. Reconcile source/primary and build a verified fresh APK; phone performance is still a gate.
2. Fix full dry-window crossing: frozen full-float pre-frame paper, disjoint owned write interiors/read halos, original ordered overlapping dabs and original batch-final travel, staged publication/rollback and one Undo. Use DrySpatialPlan; do not merely call dryFrame on consecutive chunks. Wet-under-dry must run with defined once-frame semantics. Root design in reviews/DRY_WINDOW_RENDERING_PLAN_20261008.md.
3. Diagnose physical tilt orientation and memory refusal on device. Current logs distinguish UI/cold-window/water-upgrade gates. Do not globally flip all device/engine axes or raise memory limits from a paraphrased popup. Note20 wireless was undiscoverable at last check; Note9 untouched in this run.
4. Complete **JB-2.40** media payload on ordinary PAINT layers, per cel/frame: exact ground, every plain-write bake plus safety net, board moves/creation, dry save with live wash unchanged, one Undo and ceiling. Audit: reviews/JB240_WIP_REQUIREMENT_AUDIT_20261008.md. Read actual current DOC_VERSION at landing. Full media row remains unlanded.
5. Then **JB-5.20 a–f**: one layer's pixels plus editable stroke records, actual engine replay/composition, unified history, slab/sequence/look-cache storage, codec, controls, rasterize/flatten/smudge/fill and complete exports. Existing math/helpers are not product wiring. Doc/export gates remain closed until JB-2.40 is complete.
6. Wire **JB-5.40** tween controls/runtime using the already built math. Finish device board/frame/export/Undo acceptance and measured latency, sustained painting, memory and heat checks.

## Delegation boundary

| Frontier ownership required | Mid-tier/free workers suitable under a precise contract |
|---|---|
| Architecture across painting, cels, vectors, Undo, save/export; semantics and merge decisions | Isolated helpers, validators, deterministic transforms, tests, UI wiring to an approved specification |
| Brush feel, paper/paint physics and artistic judgment | Exact-output oracles and measurement scaffolding for frontier-approved changes; no aesthetic decisions |
| GPU state lifetime, frozen snapshots, feedback avoidance, halos, atomic refusal/rollback | Mechanical scoped shader variants/counters/buffer work with independent checking and frontier review before landing |
| Choosing performance/quality tradeoffs and interpreting real device profiles | Recorded-input replay, stage counts, byte/hash comparisons, benchmark collection and cleanup |
| Resolving ambiguous specs, cross-lane changes, final phone/product acceptance | Read-only source traces, precise regression coverage, documentation/status consolidation |

These are responsibility boundaries, not a ban on mid-tier implementation. A worker may implement a hard job's well-specified leaf; it must not invent the contract or certify the whole architecture. No job requires Claude exclusively: a capable frontier lead can own it.

## Avoid repeating failed assumptions

- Scale snapping cannot periodise globally hashed HexTile; tested periodic adapter already exists.
- Existing baseline contact depth GPU test fails identically on old/new source (light≈.729 vs expected<.01). Unresolved existing defect; do not weaken its threshold or declare a new regression without differential proof.
- All-point input is not proven the bottleneck; MediaSpline emits0.75docpx subdivisions. Keep geometric fidelity separate from actual pixels and phone timings.
- A no-op positive control or constant/coarse-mip fixture can falsely pass/fail. Use genuinely broken shaders and supported sensitive cases; keep actual parity tolerances.
- Root rejected first-draft parity assumptions: optimized output must equal original even with relief enabled; test actual look1 and alpha, plus an independent broken control.
- Whole-stroke COW does not freeze a new frame's input: previously touched tiles can be rewritten in place.
- Existing128MiB/236MiB tool reservations and resident-payload ceiling are separate; new architecture alone does not prove early refusal fixed.
- Avoid duplicate paid workers: Joy Worker1/2 finished and stopped. Free tasks need owned paths and one batched report. Three/five worker trials are owner-authorized, not proof of unlimited RAM or stability.

## Verification and evidence pointers

Reviews: NOTE20_FAST_C_FINDINGS_20261008.md, PAINT_OPTIMIZATION_BATCH_20261008.md, PAINT_SPEED_PLAN_20261008.md. Coordinator A1-D-E-verified-batch.md contains precise hashes/log paths and timing statistics. Detailed logs are local; summarized outcomes and source/tests are durable in Git. Preserve decisive provenance with each landed batch.

Serialize builds via `C:/Users/JoyRaptor/AppData/Local/Temp/jb-gradle.lock`; pause only the exact primary watcher and descendants, restore it hidden. Heavy guard2800MiB; approved lightweight single-class512mTest/768mGradle and compile-only768m use2200MiB after pause. Do not run builds or browser checks alongside workers; serialize builds and browser checks. Never kill unrelated sessions. Read DEVICE_CONTROL_RUNBOOK before phone action; exact serial, install-r, APK hash/package time verification, no monkey or data wipe.

Latest verified Note20 install remains7087eae4, SHA256f233a37c0dd2be5ca9a25afb5d93713f1a458944bebea0e5dedab16c953a0ed6. Published source does not mean installed app. Overall new-plan estimate about45% (range40–50), effort estimate only; do not count unmerged jobs or helpers as finished product.
