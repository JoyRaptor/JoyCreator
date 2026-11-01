# JoyBrush engine and atlas: third-wave review

November 1, 2026. This is an additional read-only review after the first two reports. Investigation began at `ef47451af`; Klaw's fixes landed during the review. Findings below were rechecked against clean `joy-creator` HEAD `0fa6044fa4657ae0510a65151b3e63d9cbe836d5`. No project source, shared board, build lock or phone state was changed by this review.

Scope: atlas admission/eviction, current/next simulation state, park ownership, water measurements, render phase, brush frame splitting, ordinary stamp allocation, resource retirement and history publication. P1 entries concern corruption/work-preservation; P2 entries concern concrete behavior or verification defects. Rank is largest impact first. These are source traces and small JVM failure-seam probes; no real-device corruption or cause of the owner's earlier lost tiles is claimed.

## T1 — P1: sleeping media state can later delete another owner's graphics objects

[Sleep deletes state resources](C:/+Projects/JoyCreator/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaLayerEngine.kt:309); [release deletes the same state handles again](C:/+Projects/JoyCreator/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaLayerEngine.kt:1986).

`sleep()` deletes both paint states' textures and framebuffers, but retains the `state` array and its nonzero IDs. It clears other resources and sets `asleep=true`. A subsequent `release()` does not exclude those already-deleted states.

**Reachable sequence:** paint oil/pencil, lift, then switch active layer or let the media window become idle. `releaseWindow` sleeps the retained media engine. Ordinary stamp painting allocates new textures in the same context; the driver may legally reuse the released names. The owner's atlas/window menu switch invokes `releaseEngine`, whose final `release()` deletes those names again. A still-live stamp tile can lose its texture while its map entry remains.

**Proof:** a scratch probe invokes the actual compiled production `sleep()` and `release()` methods against a fake GLES allocator. After sleeping, it assigns one released texture/FBO ID to an unrelated owner, then releases the media engine. Both unrelated objects are deleted. This establishes the ownership defect under permitted name reuse, not a reproduction on the phone.

**Fix:** clear ownership records when sleeping, keep persistent brush/program resources distinct from transient targets, and make release idempotent. Do not delete by a stale ID merely because it is nonzero.

**Required check:** real GL sentinel textures/FBOs must survive sleep → other-owner allocation → release. Include idle, layer switch, atlas switch, failed init, repeated release and wake-before-release.

## T2 — P1: completed state held only in the park is never published to the drawing

[Step completion promotes the parked next half](C:/+Projects/JoyCreator/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaAtlas.kt:668); [store publication includes resident tiles only](C:/+Projects/JoyCreator/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaAtlas.kt:967); [release discards the park](C:/+Projects/JoyCreator/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaAtlas.kt:149).

When a completed block tile is evicted during a step, writeback saves its old completed half; its new output is preserved in `parkedNext`. At the global step flip, that output becomes the canonical `parked` state. If the tile remains nonresident, there is no corresponding durable write. `writeStores` clears the pending work and selects only resident tiles. `release` writes only dirty residents, then `unpark` returns the units and releases the pool without saving their contents.

Thus even with sufficient park capacity, a completed pigment/water update can survive on screen or in the park but be absent from saved stores, and disappear when the layer/window is released. A later reload can reconstruct the older state. This is separate from park exhaustion: preservation in the park succeeds, but publication from it is missing.

**Proof scope:** a scratch probe seeds a parked-only tile and invokes the actual compiled private `writeStores` and `unpark` methods. The store call clears pending work without reaching any durable writer; the discard removes the sole parked owner. Current-source tracing supplies the completed-block → parked promotion and release ordering. This is a failure-seam probe, not a full simulation/device reproduction.

**Fix/check:** track completed parked updates as dirty and publish them from the canonical park, or materialize them safely before publication. Flush every canonical owner before discarding the pool. Force completed tiles to remain parked through stroke end, save/reopen and layer switch, then compare all paint/water planes against a roomy reference. The test must pass without exhausting the park.

## T3 — P1: park exhaustion throws away a completed block's next state

[Full-park fallback](C:/+Projects/JoyCreator/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaAtlas.kt:606); [one-free-unit fallback](C:/+Projects/JoyCreator/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaAtlas.kt:611); [durable writeback](C:/+Projects/JoyCreator/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaAtlas.kt:725).

During step k, a tile whose own block has completed holds the old state in `state[k&1]` / `ws[wc]` and its new output in the opposite halves. Evicting it requires preserving both until the whole step flips. With zero park units free, `parkIfLive` logs and returns. With only one free, it gives the first unit back and returns. Residency nevertheless proceeds with eviction.

The durable fallback writes `current(store)`, which selects the old completed state ([finishedPaint](C:/+Projects/JoyCreator/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaLayerEngine.kt:1408)). No `parkedNext` entry exists to promote at the flip. Reloading the tile restores old state into both halves, losing the update that already ran. This affects pigment/paint as well as water flow. Incoming units are not released until commit, so temporary exhaustion can occur even when the steady-state set would fit.

**Fix:** reserve all required preservation space before eviction. If the reservation cannot be satisfied, defer/refuse before publishing the transition or use storage that preserves both phases. Ordinary single-phase half-float stores cannot substitute for both halves.

**Required check:** force a completed own tile to be evicted with 0, 1 and 2 park units free on the production `MediaAtlas` host. Compare all planes after the global flip with a roomy reference, including incoming units released at commit. The separate generously sized `ProbeBlockHost` does not test this branch.

## T4 — P1: failed park allocation or transfer can be committed as successful preservation

[Park allocation](C:/+Projects/JoyCreator/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaWaterPark.kt:44); [unchecked texture storage](C:/+Projects/JoyCreator/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaGl.kt:247); [unchecked park blit](C:/+Projects/JoyCreator/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaLayerEngine.kt:1943).

`MediaTex.make` never checks storage allocation errors. The blit used by `parkHalf`/`parkCopy` checks neither framebuffer completeness nor transfer errors. A failed allocation/copy can therefore return normally, publish a parked unit and allow eviction of the only exact resident copy. Java/Kotlin rollback hooks cannot protect a failure that never becomes an exception or unsuccessful result.

**Fix:** validate each allocation and preservation transfer before ownership commit, release partial chunk allocations and leave the original resident untouched on failure. Make postpublication bookkeeping nonthrowing; validation belongs before publication.

**Required check:** inject failure in every park plane allocation and each transfer. Verify unchanged current/next bytes, residency and durable state, and no leaked unit/texture. This is independent of the previous report's reservation-estimate issue.

## T5 — P1: ordinary tile allocation returns a failed texture as usable paint

[Allocator logs the error and returns the name](C:/+Projects/JoyCreator/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt:7636); [unvalidated empty-target initialization](C:/+Projects/JoyCreator/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt:2740); [tile publication](C:/+Projects/JoyCreator/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt:6200).

The current allocator consumes a GL allocation error, logs it and returns the texture ID. For a fresh ordinary pixel-stroke destination, `newStrokeTile` and `initializeTile(source=null)` do not validate the framebuffer or resulting clear/draw. A failed storage allocation can reach publication as a layer tile. Copying an existing source is a different path: its copy helper does check errors.

**Proof:** the source-extracted allocator probe injects `GL_OUT_OF_MEMORY`. It returns texture 73 despite failed storage and consumes the error. [Probe receipt](C:/Temp/jc-review-wave3-20261101/gpu-allocation-probe/RESULT.md). Klaw's latest version broadens logging to other errors but still returns the failed allocation; this conclusion remains current.

**Fix/check:** delete the fresh failed name and refuse before publishing. Validate target initialization/render completion. Inject failures for fresh stroke/layer targets with editable lines off; previous paint/history must remain unchanged. A later lost-tile blanking repair is not allocation admission.

## T6 — P1: brush-mask cleanup can leave committed paint without its Undo step

[Fallible cleanup after paint installation](C:/+Projects/JoyCreator/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt:6205); [history publication is later](C:/+Projects/JoyCreator/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt:6215).

`endStrokeInner` installs line/slab items and layer tiles, then calls `releaseStrokeTiles`. That calls sampled/dual mask retirement, whose deletion/error checks can throw ([sampled](C:/+Projects/JoyCreator/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/SampledMaskTextures.kt:79), [dual](C:/+Projects/JoyCreator/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/DualMaskTextures.kt:72)). `undo.push` has not run. The View catches the failure and cancels remaining buffers, but the published paint/items are already installed. The next Undo can affect the previous action instead of the stroke just painted.

**Fix/check:** publish paint and history together after all fallible preparation. Retirement after that must retain failed ownership for retry and must not turn a committed stroke into a failed command. Inject cleanup failure after drawing succeeds; require complete rollback or a complete, undoable committed stroke.

## T7 — P2: water appearance reads the wrong ping-pong half during odd phases

[Look always binds state[0]](C:/+Projects/JoyCreator/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaLayerEngine.kt:1887); [finished state is selected by job phase elsewhere](C:/+Projects/JoyCreator/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaLayerEngine.kt:1408).

After step 0 completes, the latest complete paint is in state[1]. While budgeted step 1 runs, state[0] is overwritten band by band or block by block. `renderLook` still reads state[0], while its water inputs use the current water half. Its leading `pasteSettle()` does not settle water. The look can combine incomplete/new paint regions, old paint regions and a different water phase, undermining the promise that a frame shows a completed simulation state.

**Fix/check:** bind a coherent completed paint/water generation, including halo reads and parked tiles. Pause at each stage/band/block boundary for odd and even steps; rendered appearance must match an oracle for that completed generation. Do not fix it by synchronously finishing every water job and sacrificing the frame budget.

## T8 — P2: oversized brush frames never split in the normal production cases

[Absent input types abort splitting](C:/+Projects/JoyCreator/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaCanvas.kt:942).

`FrameInput.halves` calls `cut` on thin, wet and erase inputs and requires each result to be non-null. `cut(null)` returns null. Normal pencil/oil/watercolour frames always omit at least one of those families, so a splittable backlog is treated as unsplittable. Atlas admission rethrows its room refusal; the legacy window path cannot divide a long frame either. Cold/busy phones and fast strokes are particularly exposed because they batch more input into one frame.

**Proof:** invoking the actual compiled private FrameInput method returned null for four-input dry-pencil, oil, watercolour and thinned-oil cases. The batch slices themselves succeeded.

**Fix/check:** distinguish absent input from a present batch that cannot be split. Test every family and mixed thinned oil. The current recursive atlas path gives each half the complete water substep request, unlike the window path that distributes it. Once splitting is enabled, bound total simulation advancement and preserve relative input order; verify picture parity and one Undo step across split/unsplit runs.

## T9 — P2: delayed dry measurements can override freshly painted water

[Query records only slot identity](C:/+Projects/JoyCreator/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaAtlas.kt:320); [fresh input marks WET](C:/+Projects/JoyCreator/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaAtlas.kt:204); [old result overwrites classification](C:/+Projects/JoyCreator/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaAtlas.kt:344).

Activity is captured asynchronously and consumed later. Between capture and consumption, input can rewet the same resident tile. The callback checks only that the tile still has the same slot, then unconditionally applies the older dry measurement. There is no query/touch revision. That stale DRY result feeds decisions to omit or delete water stores ([deletion](C:/+Projects/JoyCreator/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaAtlas.kt:1057)).

**Proof scope:** a small production-bookkeeping probe changes a freshly touched tile from WET to DRY by delivering the delayed dry result. Source tracing establishes that the host can deliver that ordering. Permanent water loss/visible impact still needs the full delayed-query test; it is not claimed for every rewet.

**Fix/check:** capture per-tile touch/residency generations with the query and reject obsolete observations; do not delete on stale measurements. Delay a dry query across fresh input → lift → release/reload and compare w0/w1 plus appearance.

## T10 — P2: the atlas parity probe accepts arbitrary differences when its control is noisy

[Pass condition](C:/+Projects/JoyCreator/joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaAtlasLedgerProbe.kt:64).

The result accepts `differ.isEmpty() || control > 0`. Once any tile differs between two roomy control runs, every difference between the squeezed and roomy atlases is excused. One noisy control tile can therefore mask hundreds of unrelated wrong tiles or missing stores while the probe reports pass.

**Fix/check:** make the oracle deterministic where exactness is required, or compare explicit per-plane/per-pixel bounded variability. A nondeterministic control should make an exact-parity gate inconclusive, not green. Sabotage a squeezed tile/store while allowing a control difference; the probe must fail. Also require actual block execution and park exhaustion cases when those capabilities are being accepted.

## Focused validation still owed

- Parked-only Undo invalidation: `MediaAtlas.restored` currently intersects affected keys with resident keys only, before clearing parked owners. A seeded production test should restore a tile present only in the park and verify it cannot reappear from stale parked state. Ordinary-user reachability was not established sufficiently for a separate confirmed finding here.
- Restore/wake bookkeeping: reset/reconcile water classifications, exclusions and in-flight measurements alongside restored textures. Test stale query delivery after Undo.
- Wide oil brush reads: the host logs when a brush's fixed 5x5 neighbor array cannot cover its reads, but continues. Prove all selectable sizes/orientations use complete reads, or refuse/split before changing brush/canvas state. This needs a pixel oracle beyond the existing no-crash size sweep.

## Probes and provenance

All probes are outside the repository. No Gradle, app installation or phone action ran. Existing compiled core/androidkit classes were used only for unchanged method logic; this is not a fresh compilation of the full latest app.

- [Frame splitting / water bookkeeping probe](C:/Temp/jc-review-wave3-20261101/FrameSplitProbe.java): actual cached production nested class and core bookkeeping.
- [Sleep/release sentinel probe](C:/Temp/jc-review-wave3-media-20261101/src/SleepReleaseProbe.java): actual cached production methods; fake GLES name reuse; reflective setup of resource handles.
- [Parked-state publication probe](C:/Temp/jc-review-wave3-20261101/ParkPublicationProbe.java): actual cached private methods; reflective parked-only setup and fake GLES for discard. The resident-key filter is unchanged in current source.
- [Allocation failure receipt](C:/Temp/jc-review-wave3-20261101/gpu-allocation-probe/RESULT.md): source-extracted allocator, injected GLES failure, small standalone Kotlin/JVM compilation.

Observed outputs:

```text
sleep deleted working targets: true, asleep=true
unrelated reused texture survives release: false
unrelated reused framebuffer survives release: false
parked-only completed state after writeStores: ownerPresent=true, pendingStores=0, pendingRect=null
after release's unpark: ownerPresent=false, pool=null
dry pencil, 4 splittable inputs: halves=null
oil, 4 splittable inputs: halves=null
watercolour, 4 splittable inputs: halves=null
thinned oil, 4 splittable inputs: halves=null
new water before delayed dry result: WET
new water after delayed dry result: DRY
```

The first two reports remain separate. Klaw's latest first-wave fixes were reconciled; none of the ten entries above is being presented as a re-discovery of those first six or wave-two E1–E8.
