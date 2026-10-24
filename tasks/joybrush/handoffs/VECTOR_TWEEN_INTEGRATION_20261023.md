# Vector / animation tween integration — October 23, 2026

## Return audit — October 24, 2026 (supersedes branch/clip status below)

- Primary inspected at `fb8042841` remains document v11 and does not contain the tween commit.
- Lead has picked up `2896cf2d5` as `59d1f1d63` on clean `lead/vector-tweens-1`.
- Clean `lead/anim-clips-integrated` at `151b92621` contains both that commit and clip merge
  `6a4c471eb`, preserving v12/v13/v14. The old frozen/dirty clip status below is historical.
- Clip review now includes an 8-row GPU proof, phone playback/end modes, performance and GIF
  decoding. These are earlier clip-build results, not combined integration or vector-tween proof.
- NativeLines copying on frame duplication remains missing. No production consumers of the
  tween scene/sampler were found in any of these three trees. Component cels are not yet resolved
  by TweenScene. The engineering checklist below remains open.
- Continued independent work: `TweenFrameSample.nextChangeTimeMs` now computes forward
  exposure wakeups from cumulative tick boundaries. **SOURCE ONLY:** shared compile runner
  deferred on October 24 (RAM546 MiB available, required2800 MiB). The prior passing suites below
  do not cover this addition; range/speed/ping-pong and runtime binding remain with integration.
- Refreshed primary `tasks/STATE.md`, `tasks/ROADMAP.md` and `tasks/joybrush/ROADMAP.md`.
  No merge, hot-file edits, phone action or new device acceptance occurred. Retain source worktrees
  and evidence because the complete parent task is not finished and not waiting solely on owner.

Owner requested completion of the parent vector/tween plans. Owner confirmed in this chat that the Engine Lead retains `GlPaintEngine.kt`, `JbCanvasView.kt`, and `JoyBrushActivity.kt`. This continuation does not edit those files. Independent work is on `codex/vector-tweens` in `C:/Temp/jb-vector-tweens`, based on `ff36f19eb`. Full core/backend verification and Android consumer compilation passed. This is ready for lead review; main-branch integration and phone acceptance remain open.

## Parent plans

Independent source commit: `2896cf2d5` on `codex/vector-tweens`. The source worktree is clean after commit. Root released its lane and shared build slot; the owner reserved engine hot files with the existing lead.

1. `ROADMAP.md` Phase 5 -> R51 / `design/ONE_LAYER_MODEL_BRIEF_20261007.md` -> `specs/JB-5.20_lines_in_the_one_layer.md` -> `specs/JB-5.20e_select_ui.md` -> NativeLines v1 / v1.2 hardening.
2. `specs/VECTOR_RENDER_DESIGN.md` -> ten line brushes, taper/dash/wire behavior, sharp zoom rendering, edit cache, SVG and phone proofs.
3. R51 Phase 4 -> `specs/JB-5.40_tweens.md` -> implemented `core/anim/Tween.kt` and independent schema/sampler/scene work below.
4. `specs/JB-3.00a_boards_owner_model.md` -> `specs/ANIMATION_CLIPS_20261018.md` -> preserved, unintegrated `C:/Temp/jb-clips` work (v12/v13, component cels). Its source-frozen October 22 handoff remains authoritative.
5. Unibrush finish U3-U8 overlaps frozen PNG transport/replay/edits/save. Keep its existing lead lane; NativeLines is the live publisher, not debug-only RecordedScene publication.

## Independent code prepared in this continuation

- Saved `Frame.tween`, stable minimum schema version 14, setter/hold behavior for both legacy `AnimOps` and actual `RegionDocumentOps`. Versions 12/13 remain allocated to the preserved clip implementation. Reconcile schema at integration; do not overwrite clip fields or lower versions.
- `TweenFrameSample.atTick/atTimeMs` gives a quantized exposure sample. It carries the next play-order frame only for eligible tween holds, and last-to-first only when caller supplies looping. The sampler does not invent a second ping-pong/end policy.
- `TweenScene.build` resolves frame cels per layer, skips held layers, and calls existing `Tween.between` independently within each pair of cels. Raster content remains source-frame content. It does not generate stored frames or modify a drawing. Component-cel resolution still needs the clip-aware consumer.
- `SavedCelTileSource` plus `ExportPicture.asShown` fix slab-only export: after all lines are rasterized, compose upper slabs rather than silently exporting the base alone. Exact saved looks are retained byte-for-byte; no CPU replay replaces paper grain. Missing declared payloads fail before output.

## Required engine changes, in dependency order

- [ ] **Preserve editable data when duplicating a frame.** `RegionDocumentOps.addFrame(DUPLICATE)` emits `RegionCopy`. Current `GlPaintEngine.applyBoardChange` copies visible GPU tiles only. Source NativeLines records, bases, slabs and GPU-widened footprints are not copied to the new cel. `LineItems.copyOf` already copies records with their existing IDs/seq and independent base/slab textures; `duplicateLayerStep` demonstrates footprint and rollback handling. Add the same ownership-aware item copy to the board transaction and its ONE Undo step. Account for copied texture bytes before allocating. Apply only to genuinely new duplicate-frame destinations; Hold/Unhold also uses RegionCopy and must not copy an entire plane into shared content.
- [ ] **Prove frame independence.** Duplicate two source lines, recolour/nudge only the destination, Undo/Redo, save/reopen; source must remain exact and both frames must retain the same per-line IDs. Include slabs/erase ops, GPU footprint edges, missing-memory rollback and linked-cel behavior.
- [ ] **Bind transient tween rendering.** Use source/target line records and frozen brush closure from NativeLines. Use the existing GPU replay path to retain paper grain, watercolour and supported image assets; do not silently substitute CPU InkTiles where it reports omissions. Render in seq order with the source frame's pixel slabs, clipping to the owning board. Never write generated in-betweens into canonical line storage, saved cels, currentFrameId or undo.
- [ ] **Schedule held tween ticks.** Current BoardRuntimeController tick sleeps until PlaybackClock.nextChangeMs, which means the next SOURCE-FRAME boundary. A tween needs each integer exposure tick inside the hold (k/n). Keep the existing loop/ping-pong/end clock; derive each sample and wakeup from cumulative board tick boundaries. Avoid repeated per-frame-duration summation and comparisons between independently rounded clocks.
- [ ] **Wire controls and strip.** Add the saved frame tween toggle and token-based strip tint / hover label from JB-5.40 T9. Enabling requires hold > 1; lowering hold to 1 disables tween in the same document edit. Do not expose a working-looking toggle until transient rendering is bound.
- [ ] **Resolve reverse-playback exposures.** Existing PlaybackClock gives source-frame starts on its backward leg; feeding those starts to the new sampler cannot animate the inner hold. The clock consumer needs the precise reversed exposure tick, with regression examples at both ping-pong seams. Do not invent another independent end/loop clock.
- [ ] **Wire export before flattening away records.** Actual Activity paths already call ExportPicture.asShown; ordinary vector static exports were therefore not globally broken. Tween export must receive original records before that normalization, or generated transient snapshots for each exposure. It cannot recover correspondence after asShown removes line streams. Share the sampler with playback, honor ranges/loop end policy, and preserve each format's timing/caps.
- [ ] **Reconcile overlapping clips/component cels.** Do not copy the frozen jb-clips tree over new main or resolve conflicts by dropping either side. Retain physical fragment ownership, blank/hold/loop/ping-pong behavior, per-layer timing, one undo and masks/still pixels. The simple TweenScene helper addresses direct frame cels; component graphs must be resolved explicitly.
- [ ] **Complete remaining parent vector requirements.** Actual LineAdmission still refuses whole-stroke shaping, fill/smudge as editable lines, media on crossed item tiles, picture tips and tiled-board lines. Ten line JSONs existing is not proof each is reachable/admitted. Unibrush U3-U8 handles its image/dual/taper path. Sharp zoom tiles/live overlay and GPU sandwich cache remain unbound. Keep Sable phase 2 separate from the ten initial stamp-line brushes.
- [ ] **Finish SVG product export.** Existing core SVG writers and tests do not establish an accessible mixed-document export. Connect scoped paths and raster slabs in seq order, preserve clipping/operators where representable, explain any approximation and verify the output in an independent SVG renderer.
- [ ] **Device and artistic acceptance.** Note9: copied-frame independence, same-layer supported paint+lines, one undo per gesture, raw/live/edited/reopened parity, context loss, preview/export parity, line-under-1000 edit gate, 1:1 and 8x sharpness/cost. Owner judges pencil/watercolour quality. Preserve full-resolution brush behavior and current accepted presets unless approved.

## Status corrections

- Select/capture/native save/edit are present in current source; October 16 reachability notes predate them.
- Static vector exports use saved looks through ExportPicture.asShown. The concrete corrected bug is upper slabs with no remaining line/look.
- Core Tween.between is implemented; tween playback is not wired merely because that math exists.
- A duplicated animation frame currently copies the picture but loses the destination editable records. Stable IDs in the spec are not yet proof of live behavior.

## Verification in the independent branch

`C:/Temp/jb-vector-tweens-final-suites.log`: `:core:jvmTest :androidkit:test`, BUILD SUCCESSFUL in 6m50s. XML totals independently reread: core 232 suites / 2552 tests / 0 failures / 0 errors / 4 optional skips; backend 113 suites / 1061 tests / 0 failures / 0 errors / 0 skips. Tests include real PNG/OpenRaster decoding for slab-only and fully erased exports. No device action or installation occurred.

`C:/Temp/jb-vector-tweens-android-compile.log`: `:joybrush-android:compileDebugKotlin`, BUILD SUCCESSFUL in 1m34s (actual Kotlin compilation executed). Existing deprecation/named-parameter warnings remain; no new warning from the changed Frame serializer.
