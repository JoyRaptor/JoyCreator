# Vector and tween completion — October 23, 2026

Owner requested completion of the parent plans behind the editable-vector and tween specs, using Luna helpers for bounded work. Worktree: `C:/Temp/jb-vector-tweens`, branch `codex/vector-tweens`, base `ff36f19eb`.

## Parent plan chain

- Product roadmap Phase 5 -> R51 / `design/ONE_LAYER_MODEL_BRIEF_20261007.md` -> JB-5.20 one-layer editable strokes -> JB-5.20e selection -> NativeLines v1/v1.2 hardening.
- `specs/VECTOR_RENDER_DESIGN.md` -> replay fidelity, ten line presets, zoom rendering, line lab, SVG export.
- R51 Phase 4 -> `specs/JB-5.40_tweens.md` -> existing `core/anim/Tween.kt` -> missing saved frame setting and runtime/export consumers.
- Animation owner model JB-3.00a -> `specs/ANIMATION_CLIPS_20261018.md` / October 22 resume handoff -> overlapping clips, linked component cels, independent playback and export. Preserve its existing unintegrated implementation in `C:/Temp/jb-clips`.
- Unibrush finish U3-U8 overlaps frozen image assets and native-line replay. Engine Lead owns that lane in `C:/Temp/lead-uni`; do not replace it with RecordedScene activation (debug probes are not the live publisher).

## Ordered completion checklist

- [x] Trace parent plans and distinguish implemented source from stale handoffs.
- [ ] Confirm native-line capture/edit/save/undo proof and identify actual remaining defects.
- [x] Fix static export of ordered slab-only cels and fully erased stacks; retain exact saved looks and reject missing declared payloads. Existing static vector exports were already normalized through ExportPicture.
- [ ] Preserve line identity and independent editable content through frame duplication.
- [x] Add saved frame tween flag, validation and operations; preserve clip allocations v12/v13 and use stable tween minimum v14. Live UI/undo acceptance remains with integration.
- [x] Add one shared exposure/tween sampler for playback, scrubbing and export consumers.
- [x] Add scoped transient line-scene generation via the existing interpolation math; preserve source order/seq/brush and isolate layer/cel identities.
- [ ] Render generated in-betweens from matching cel-local record IDs with frozen brushes and unchanged raster content.
- [ ] Connect frame toggle/strip indicator, playback/hover scheduling and export to the sampler.
- [ ] Reconcile animation clips, component cels, board movement and combined clips without flattening editable frame copies.
- [ ] Complete vector zoom sharpness/caching and measure its device cost.
- [ ] Coordinate Unibrush asset/save/replay work with its existing lane; complete remaining supported vector brushes.
- [x] Verify full core and backend suites using the shared build slot: 2552 core (4 optional skips), 1061 backend; zero failures/errors.
- [x] Compile the Android consumer module against the changed core APIs: actual `:joybrush-android:compileDebugKotlin`, BUILD SUCCESSFUL in 1m34s.
- [ ] Verify device parity, save/reopen/context loss, latency, large edits and owner-visible brush quality.
- [ ] Update parent specs and handoffs with actual evidence and remaining owner decisions.

## Coordination and verification

- Primary checkout is dirty with unrelated work. No cleanup/reset or copying entire feature trees.
- `GlPaintEngine`, `JbCanvasView` and `JoyBrushActivity` belong to the current Engine Lead. Work on independent core/schema/export pieces first.
- Animation clips work is source-frozen and recoverable, last agent final October 22; current continuation preserves that worktree.
- Root owns builds, integration and device use. Luna helpers have disjoint file assignments and do not run Gradle or device commands.
- Owner confirmed Engine Lead retains the three hot files. Remaining copy/publication/UI/GPU integration is assigned to that lane; handoff `handoffs/VECTOR_TWEEN_INTEGRATION_20261023.md` gives exact transaction and scheduling requirements.

## Verification and review

- Full serialized Gradle run: `:core:jvmTest :androidkit:test`, BUILD SUCCESSFUL (6m50s), log `C:/Temp/jb-vector-tweens-final-suites.log`.
- Android consumer compile: `:joybrush-android:compileDebugKotlin`, BUILD SUCCESSFUL (1m34s), log `C:/Temp/jb-vector-tweens-android-compile.log`.
- XML independently counted: core 232 suites / 2552 tests / 0 failures / 0 errors / 4 optional skips; backend 113 suites / 1061 tests / 0 failures / 0 errors / 0 skips.
- New checks cover saved setting compatibility, atomic hold/tween changes, loop seams and integer tick boundaries at fps 1..60, layer-scoped correspondence, exact saved looks, ordered slab operators, missing payloads, PNG/OpenRaster output and fully erased stacks.
- Historical schema tests still check payloads byte-for-byte; only current-header expectations follow the new document version. Explicit historical v9/v10/v11 migration expectations remain unchanged.
- Reviewed gaps include a missing loop-policy argument, ambiguous duplicate frame IDs, stale samples without a successor, per-tile quadratic slab scans and transparent quantisation. These were corrected before the passing full run.
- No engine hot files, phone drawings, phone settings, installation, main-branch integration or remote publication changed in this continuation.
