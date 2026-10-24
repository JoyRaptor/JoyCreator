# D7 host connection — unavailable owner boundary, exact held gate

This is a read-only ownership audit plus one concrete fail-closed View patch. It does **not** claim an implemented live stroke-end adapter. The proposed gate prevents a brush with length taper/dashes from being rendered/committed as an ordinary untapered native stroke. No production throw stubs or TODO bodies are included.

Files:

- `UNIBRUSH_D7_HOST_GATE.patch`:8-line admission gate in the D6 proposed View, before image resolution, brush freeze, raw capture or GPU preview begins.
- Outside scratch `C:/Temp/jb-unibrush-d7-host-draft/lead-proposed/.../JbCanvasView.kt`: full proposed source containing that gate.
- `UNIBRUSH_D7_HOST_SOURCE_EVIDENCE.md`: exact D6 proposed paths, SHA256s and line evidence.
- Outside scratch `C:/Temp/jb-unibrush-d7-host-draft/make_gate_patch.py`: generator; reads its stated source base and refuses an unexpected insertion site.

No canonical Lead source was edited. No build, GPU or device action was performed. The gate has not been compiled or phone-tested.

## What actually exists

In the D6 proposed engine, `requireLocalPaint` at507 rejects a nonnull PublishedPaintState. Public `beginStroke` at4660 calls it at4666, and native `endStroke` at4970 calls it at4971. The View's first dab at1841 queues that native begin; lift at1960 queues `setPendingLine; endStroke; reportHistory`. These paths cannot serve as a recorded-scene final publisher.

The existing GPU `prepareRecordedLine` rejects an active preview at2950 and rejects every line-shaping recipe at2961. Its scratch setup calls `beginStrokeBuffer`, which cancels the current stroke buffers. Clearing that guard while the preview remains active would destroy the valid preview before admission completes.

The engine does have a real atomic artwork root assignment: `publishJointPaintStep` at5352. It validates/stages scene+cursor+native owners and assigns the complete root at5473. However it requires a settled stroke at5363, returns Boolean, and runs scene/native retirement after assignment at5475 onward. A cleanup exception can therefore escape after publication. Calling this Boolean method directly as `StrokeLiftPort.publish` violates that port's contract that a thrown/refused publication leaves staged ownership with the transaction and publishes nothing.

`preparePlainRecordingPromotion` at3978 is expressly diagnostic-only. It prepares a bare initial root from proven canonical native physical cels, retains both reachable history branches and source transfer proof, and binds scene/history at installation. The ordinary View never invokes it. The scene's `stageJointPrepared` requires a bound publication; there is no combined public initial-scene+first-stroke transaction. It is unsafe to turn the diagnostic install into a speculative pen-up shortcut.

## Unavailable owner APIs

The missing operations are engine-owner capabilities, not new generic brush protocols:

1. **Live recorded preview lease.** The engine must capture one published root/cursor, physical GL epoch, document/paper/clip identities, physical layer/cel ownership and a contact serial at pen-down. It needs a private preview storage object that can remain alive while a final source is rendered privately. Existing scalar stroke fields and one-active sampled/dual texture owners cannot hold both preview and scratch replay simultaneously. A lease must detach and later restore/retire the complete preview state, or final replay must use a distinct private scratch owner. It must not merely save `strokeTiles` and call cancel: color moments, look, grain, masks/dual bindings, travel, operator, opacity and pools are also owners.
2. **Prepared joint publication with receipt.** Extract the preparation portion of `publishJointPaintStep` into an engine-owned ticket that owns the scene proposal, prebuilt candidate root/cursor, caller-owned source endpoints, expected root and retirement ledgers. Its final `tryPublish` checks root+physical/context/scene/cursor identities immediately before a single assignment, transfers ownership and returns a prebuilt accepted receipt without a later throwing cleanup step. It must not close a proposal as unconsumed after the root is assigned.
3. **Post-publication retirement ownership.** Keep retryable retirement handles with their actual physical-context owner after publication. Report cleanup failure as a cleanup diagnostic on an already successful receipt; never return Refused or throw a publication failure once the root changed. The current Boolean method needs this separation before reuse by the lift port. Allocate its receipt/result wrapper before assigning the root so publication does not depend on a post-assignment allocation.
4. **First-scene activation.** Either Lead explicitly promotes a proven native drawing to the source-bearing recorded owner *before* accepting the first such contact, or adds a combined initial-scene+first-step publication API. Existing archive/native/region/media/history eligibility checks remain mandatory. Promoting only on lift and then failing the final source would leave an externally changed owner with no accepted stroke. A canceled first contact must not publish a candidate brush/PNG registry or leave orphan resources.

Suggested **signatures only**, to be implemented inside the actual protected owner after its source invariants are proven:

```kotlin
// Engine-owned opaque leases/tickets; callers cannot construct them from GLuints or a cursor.
fun beginRecordedStampPreview(contact: FrozenRecordedContact): RecordedStampPreviewLease
fun makeShapedLiftPort(
    preview: RecordedStampPreviewLease,
    raw: StrokeRecord,
    preset: BrushPreset,
    candidateAssets: Map<String, FrozenBrushPngs>,
    limits: ShapedReplayLimits,
): StrokeLiftPort<PreparedRecordedStroke, RecordedStrokeReceipt>
```

These signatures are deliberately outside proposed production source; neither capability currently exists. Their implementation cannot be truthfully replaced with `engine.endStroke()` or a fake transaction whose only operation is the native commit.

## Exact flow once the owner exists

At UI contact, freeze a detached definition, PNG bundle, one seed and primary/secondary palette. Capture post-guide **raw** samples before response/stabilization. Freeze the actual D8 StabilizerSpec and contact zoom in StrokeCapture. Resolve sampled/dual resources once with the same seed; use separate UI/GL resolver owners.

Capture View `lineEditEpoch`, `graphicsEpoch`, target layer/document-cel address and one contact object/serial after `onBeforeStroke`. Every GL closure carries that exact contact. The GL lease independently captures the authoritative root/cursor/physical owner. Queued lift work must use `onGl(allowLost=true)` plus the existing `LineEditGate.stale` identity checks so stale work can retire/abandon its own preview and answer the UI. Default `boardStrokeWork` may silently drop a callback after graphics replacement and cancels the engine globally on error; it is not the new transaction runner.

While drawing, render only the lease's provisional unshaped private preview. Do not publish pixels, raw records, assets or history. Enforce the caller's raw-sample allowance while capturing, not just after retaining an unbounded raw list. A limit refuses the entire contact; it never truncates the eventual recording.

On lift, finish actual stabilization only for preview catch-up. Finish/snapshot the raw capture and palette once, then create `StrokeLiftTransaction(engine.makeShapedLiftPort(...))` on the GL thread. Its `prepare` performs bounded `ShapedReplay.replayAdmitted` and tile admission before retaining final dabs, then renders complete private final sources while the preview lease remains owned. Use the replay's hardness/opacity/grain and its first selected sampled/dual context. No extra response/stabilizer pass may be applied to already stabilized samples.

The owner prepares exactly one history step. For an editable line, publish the raw recipe/frozen definition/bundle and canonical final unit with its actual operator/footprint. For raster output, publish canonical final slab sources at one sequence, with opacity applied once. Captured/windowed sources must pool the original whole recipe before persisted capture-domain clipping and integer placement; today's physical region plan is not the historical capture domain. If that capture adapter is unavailable, refuse that domain at contact rather than removing the guard.

`publish` validates the same contact/root/cursor/document/paper/physical cel immediately before installing the prebuilt candidate root. `discard` retires only private unconsumed final resources. `discardPreview` retires only that lease, never a subsequent contact's preview. A failure preserves the old artwork/history and the still-valid preview until the host cancels the refused contact. Cancellation/context replacement publishes nothing; stale GLuints are abandoned without deleting replacement-context names.

The current `StrokeLiftTransaction` already prevents callback/cleanup reentrancy and records published-preview cleanup failures. Those pure tests do not prove the missing physical owner implementation. Do not weaken its atomic ownership contract to fit an existing Boolean native method.

## Required owner acceptance tests

Test pen-up final output versus actual replay (pressure, both length ends, short flick, taps, dash-first-period, scatter, color, sampled and dual); old artwork/undo count unchanged during preview; exactly one final history step; no step on cancel; preparation/GL/PNG/tile refusal preserves artwork and valid preview; cancel/replacement at each callback; failed retirement retains bounded handles for retry; no post-publication error reported as refusal; new-contact preview survives an old contact's late cleanup; first-scene activation retains both history branches and canonical region/media ownership; raster and editable-line results preserve opacity/operator once; save/reopen without library.

This remains a Lead application acceptance boundary. Owned core replay/transaction math can be finished independently, but enabling the actual Taper shelf row as a live brush requires this host connection.
