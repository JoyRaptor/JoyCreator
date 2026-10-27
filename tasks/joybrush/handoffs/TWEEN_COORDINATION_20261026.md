# Tween integration contract — October 26, 2026

## Ownership and bases

Owner authorized direct coordination with **JoyBrush Lead Coordinator**. Lead confirmed:
hot files remain with root lead; no parallel edits to GlPaintEngine/JbCanvasView/JoyBrushActivity.
No builds, queued wrappers or phone actions in this continuation; Select has the next admitted
ACK APK job, available RAM about1.1 GiB. Current device candidate stays frozen.

- Current approved coordinator: `8e7692c83`, code `f84504944`, branch
  `lead/select-reconcile-20261024`, `C:/Temp/jb-select-reconcile-20261024`.
- It already includes `59d1f1d63` (the saved v14 tween foundation), **not** clip merge `6a4c471eb`.
- Preserve parallel `151b92621` / `lead/anim-clips-integrated`. Continuation source is isolated
  in `C:/Temp/jb-tween-runtime-20261026`, `codex/tween-runtime-20261026`, based on151b92621.
- Direct-cel playback/export adapters go first. Component scenes and scene-clip exports remain
  a separate dependency slice; never remove their types or codec refusals just to compile them
  on the direct-cel candidate. Selectively reapply reviewed source to a NEW current-base checkout.
- Direct-cel source checkout now exists: `C:/Temp/jb-tween-direct-20261026`, branch
  `codex/tween-direct-20261026`, base8e7692c83. Commit `f5b05489e` adds exposure timing,
  scoped sample validation and the independent plane-copy preparation helper. This was a
  selective port; it does not change saved schema, old clock APIs, Select/Copy or engine files.
- Component-aware counterpart is preserved separately as `35a9d23b1` on the151-based branch.
  Lead requested the copy helper be frozen for an independent ownership review; it is frozen.

## Required hot-file work, in order

1. **Independent editable frame duplication.** Only a DUPLICATE into new exclusive frame cels
   qualifies for whole-plane copying. RegionCopy is also used for Hold/Unhold into shared
   content, where copying an entire plane is incorrect. Identify the operation from before/after
   document ownership, not simply `change.copies.isNotEmpty()`.
   - Capture source NativeLines records, base seq0, ordered upper slabs/operators and known
     GPU-widened footprints before removals/publication. Preserve record IDs and seq for matching.
     First inspect the authoritative backend: published recorded-scene roots/captured brush
     closures cannot be silently replaced by an empty NativeLines-only snapshot. Existing current
     Select/Copy capture gates and frozen asset closure must continue to apply.
   - `PreparedLinePlaneCopy.capture` (new independent source helper) snapshots those items and
     supplies textureCount for reservation; `materialize` returns LineItems.Changes. It does not
     classify RegionCopy, allocate before admission, apply a step, or update canonical metadata.
   - Budget base/slab texture copies **in addition to** visible raster pending copies. Copies must
     be independent textures; caller tracks all allocations even if preparation fails midway.
   - Apply returned slabs/lines and destination footprints in the SAME transaction/UndoLog.Step
     as document + pixel changes. Publish once, retain history texture ownership, roll back both
     item and pixel publication on failure, and recycle only newly allocated copies. Source stays
     unchanged. Do not flatten the source to implement duplication.
   - Direct frame/cel copying first; clone entire component graphs and alias mappings only after
     clip reconciliation. New helper alone does not fix the live engine.
2. **Transient GPU scene.** Feed sample + original cel-local records through TweenScene, replay
   using each drawing's frozen brush and actual engine path (including grain/watercolour/assets).
   Compose interpolated line units in seq order with source-frame raster bases/slabs/operators.
   Clip to board ownership; share no generated pixels with canonical stores, save or UndoLog.
   Context/revision/stop changes must dispose transient textures and restore saved cursor display.
3. **Every-exposure scheduling.** PlaybackClock.exposureAt returns a sample and absolute next
   wall wakeup, blank/reverse/loop flags. It shares the existing clock's mode/range/speed. Reverse
   ping-pong samples interior frames with offsets n-1..0 and normal forward matching. Only LOOP
   interpolates the last selected frame to the first selected. Use ceiling for scheduler delays
   (minimum1ms), resample from elapsed time after a delayed wake; never increment a separate clock.
4. **Controls/strip.** Only expose the saved frame toggle after the renderer is bound. Enabling
   needs hold>1; hold1 clears it atomically. Use token colors and one document undo step.
5. **Export before record normalization.** Capture original JbContents/streams/frozen assets before
   ExportPicture.asShown strips line metadata. Board export plans expand eligible tween holds
   into k/n exposures with cumulative delays; optional transient render callbacks accept original
   contents/sample/rect/paper. Missing renderer must refuse an active tween, never emit held pixels
   while claiming a tween export. Preserve static saved-look path for untweened exports. Enforce
   expanded output count/memory caps before bytes. The GPU callback must not modify saved cels.

## Separate animation-clips dependency milestone

The foundations absent from current coordinator include saved Board.end/v12, Cel.parts/v13,
CelParts ownership/clone/transfers, component-aware RegionPaintPlan/RegionRenderer, clip scheduler,
scene export plan/runner, board engine projection/transaction and UI operations. They coexist under
Frame.tween/v14 on151b92621. Directly comparing the two tips currently shows82 production-source
paths differing across core/androidkit/app,149 paths including tests/assets/docs: these are two
feature branches, not interchangeable copies. Original6a4c471eb contains107 changed paths including
review evidence; a historical69-file integration count is not a safe reapply manifest.

Keep DocJson's strict unknown-key checks and future-version refusal. Do not accept unsupported
parts/end and then drop them, flatten them or re-save them through an older schema. Reconcile
the actual schema/codec/model contracts before a newer clip document reaches the candidate.
Use CelParts.resolve for ownership fragments, keep whole stroke geometry, clip at render time,
and scope correspondence per layer/physical-cel pair/piece. Scene playback/export combines each
clip's exposure independently, respecting held layers and blank end modes.

## Proof status

October26 continuation is **source only**; no new compile/test/device claim. Earlier2552 core /
1061 backend and Android compilation evidence belongs to2896cf2d5, not this new source. Earlier
clip GPU/phone proof belongs to its original build, not the merged current Select/tween combination.
Before completion: admitted compile; owner-requested checks; engine copy/undo/rollback and
GPU replay parity; Note9 toggle/scrub/loop/ping-pong/export/save/reopen/context loss acceptance;
owner look judgement where needed. Update STATE/roadmaps only to the level actually demonstrated.
