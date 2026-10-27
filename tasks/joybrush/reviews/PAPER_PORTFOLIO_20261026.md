# PAPER-PORTFOLIO recovery — October 26 snapshot, published October 27, 2026

Docs-only assignment from the coordinator; paper specialist owns this report, the PAPER-PORTFOLIO row and the separate STATE proposal. No source, assets, shaders, UI or schema changes. No builds, device actions or integration. Source snapshot is `741fa01543265ca89634113892d4d30ea422eceb`, branch `codex/paper-recovery-docs-20261026`, checkout `C:/Temp/jb-paper-recovery-20261026`. The coordinator's working HEAD may advance independently; these findings are bound to this base, not an assertion that it is forever current.

## Plan and review

- [x] Read current roadmap, agent-start, STATE, Git hygiene and relevant paper contracts.
- [x] Preserve original and dirty worktrees; inventory source and published ancestry.
- [x] Resolve current catalogue files and map source/receipts/remaining acceptance.
- [x] Prepare small roadmap row and separate STATE proposal for root reconciliation.
- [x] Prepare explicit owned-docs checkpoint; commit/push/remote equality receipt is returned to the coordinator at publication.

Recovery first ran October26; publication/source-path checks resumed October27. Luna is not exposed as a callable worker/model in this session. No paid substitute or access retry loop was used. Standard-library catalogue/file/link checks were performed directly. No heavy-job or phone grant was requested or used.

## What is available, and what this does not prove

Catalogue: **29 surfaces / 32 backgrounds**, **78 files / 25,201,898 bytes** in `joybrush/assets/paper/` including the catalogue. The full exact file hashes below are this audit's asset receipt, not image-quality approval. Looks and surfaces are separate choices. Original canvas/pulp, strong and flattened crumples, rice, chalkboards, parchment/papyrus, silk/fabric/cement coexist with later photo/felt papers, fluid maps, drawing paper and Bristol.

Current source includes `03f4dff82` photographic papers/contrast-preserving hex/fluid maps, `59978be83` felt papers, `9380e40b2` height-only storage, and `64d16a596` drawing/Bristol scale. The original numerical launch library (`f381579ce`, `b6202c602`) is preserved in ancestry but is not the whole current catalogue. Height-only files (`packed=false`) are expanded at load through SurfaceMaps; do not decode all B channels as if every shipped file were already packed RGBA. Grey ImageIO samples must come from the raster rather than colour-converted getRGB, as PaperTestSetTest documents.

| Area | Current source availability | Remaining proof / owner |
|---|---|---|
| Catalogue/document | PaperCatalogue validates ids/metadata; PaperState joins current packaged entries, reports missing ids and falls back to smooth/flat. DOC_VERSION is14 at this snapshot;7 is only the historical None introduction. | Schema14 save/reopen/current-file paths must stay root-owned; no schema edit proposed. |
| Display/preview | Shared HexTile, CPU PaperRaster, GPU PaperBackground; mip footprints remain1/pitch, now with contrast preservation/heightMean and relief clamp0.9..1.1. PaperPreviews provides bounded cached crops. | Current32-look parity is not established by old21-look result. Zoom, seam, texture-direction and visual acceptance remain scoped. |
| Physical brush response | DocumentPaperGrain/StampPass use current surface, physical pitch/scale and Bite/brush response. mediaPaper supplies fluid maps, histogram and physical sizing/absorbency/tooth/compliance. | Source availability is not current device brush-feel or whole-media acceptance. Preserve watercolor/oil specialist ownership. |
| UI/custom colour | PaperSheetView has Background/Surface, Colour/Tint, Show/Bite/Scale, Light/export; layer-column swatch and bounded previews exist. Picker preserves brush colour. | Current complete UI/preview/cancel/undo/save/reopen phone matrix not recovered; root owns Activity/View integration. Tan-paper Select screenshots cannot satisfy it. |
| None | screenTransparent retains physical surface/Bite, screen uses checkerboard, exports exclude paper even if requested; sheet disables Include. | Recheck actual current save/reopen/board/frame/line/export matrix in assigned future gate. Never export checkerboard. |
| Undo/replay | Paper changes have paperBefore/paperAfter in shared history. P5 says current paper affects replayed vectors, existing raster pixels stay unchanged. setPaper itself invalidates background only. | Owning live operation must rebuild/replay vector sources on paper edits/undo. Current activation/history/generation proof is root-owned; this observation alone is not a confirmed regression. |
| Export | RegionRenderer paper callback initializes bounded256x32 floor BEFORE layers. CanvasPng, AnimExport, OraExport and BoardExport consume paper; None excluded. ORA prepares its separate paper PNG before opening the ZIP. | Whole-region transient for ORA paper layer remains; no measured Note9 peak/performance or current real-device Multiply/None/line/frame export parity claimed. General9.06b is landed, not unfinished as old handoff says. |
| Imported brush pictures | ImportedTexture core conversion exists. Procreate/ABR keep supported source bytes/settings, retain TEXTURE_NOT_DRAWN/fallback warnings and character-budget guards. | Per-brush imported image-tip/grain drawing remains incomplete; JB-1.05d image-tip spec is missing at this snapshot. Recover/approve contract before implementation; do not remove warnings or invent polarity/anchoring. |
| Tile mode | TilePaperSampler and TilePaperGrainSeamTest/TilePaperGpuFixtureTest exist. Four canonical reads cost twelve fetches. | Device performance unmeasured in sampler contract. Preserved separate GPU-checker branch is not automatically integrated. |

## Source and reusable-engine map

- [PaperCatalogue.kt](../../../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/paper/PaperCatalogue.kt)
- [PaperState.kt](../../../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/paper/PaperState.kt)
- [SurfaceMaps.kt](../../../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/paper/SurfaceMaps.kt)
- [PaperRaster.kt](../../../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/paper/PaperRaster.kt)
- [HexTile.kt](../../../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/paper/HexTile.kt)
- [PaperTexture.kt](../../../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/paper/PaperTexture.kt)
- [PaperPreviews.kt](../../../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/paper/PaperPreviews.kt)
- [TilePaperSampler.kt](../../../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/paper/TilePaperSampler.kt)
- [ImportedTexture.kt](../../../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/paper/ImportedTexture.kt)
- [PaperResources.kt](../../../joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/PaperResources.kt)
- [RegionRenderer.kt](../../../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/render/RegionRenderer.kt)
- [CanvasPng.kt](../../../joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/CanvasPng.kt)
- [AnimExport.kt](../../../joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/AnimExport.kt)
- [OraExport.kt](../../../joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/OraExport.kt)
- [BoardExport.kt](../../../joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/BoardExport.kt)
- [GrainTextures.kt](../../../joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GrainTextures.kt)
- [DocumentPaperGrain.kt](../../../joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/DocumentPaperGrain.kt)
- [StampPass.kt](../../../joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/StampPass.kt)
- [PaperSheetView.kt](../../../joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/chrome/PaperSheetView.kt)
- [PaperEditingTest.kt](../../../joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/paper/PaperEditingTest.kt)
- [PaperMipTest.kt](../../../joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/paper/PaperMipTest.kt)
- [PhotoPaperTest.kt](../../../joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/paper/PhotoPaperTest.kt)
- [PaperTestSetTest.kt](../../../joybrush/core/src/jvmTest/kotlin/cc/joycreator/joybrush/core/paper/PaperTestSetTest.kt)
- [PaperBackdropTest.kt](../../../joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/render/PaperBackdropTest.kt)
- [PaperExportBlendTest.kt](../../../joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/io/PaperExportBlendTest.kt)
- [PaperNoneExportTest.kt](../../../joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/io/PaperNoneExportTest.kt)
- [GlPaperHistoryTest.kt](../../../joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaperHistoryTest.kt)
- [TilePaperGrainSeamTest.kt](../../../joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/gl/TilePaperGrainSeamTest.kt)
- [TilePaperGpuFixtureTest.kt](../../../joybrush/core/src/jvmTest/kotlin/cc/joycreator/joybrush/core/paper/TilePaperGpuFixtureTest.kt)

Root exclusively sequences GlPaintEngine, JbCanvasView, JoyBrushActivity and BenchRunner changes. This report proposes no changes there. Help mapping is proposed separately in [STATE proposal](PAPER_STATE_PROPOSAL_20261026.md).

## Evidence map — historical receipts are not a current run

| Receipt | Bound scope | Limits |
|---|---|---|
| [JB-9.06c filtering](JB-9.06c__cpu_mip_filtering.md) |32 core tests/0; XML2026-10-02T12:36:36.4789227-04:00;21 actual material fixtures within1 byte, upload exact, GPU filter mutation red/restored green. Pre-rebase base379782ab. | Later photographic/contrast/height/fluid changes and32 backgrounds are outside that result; CPU-hook mutation was blocked, not run. |
| [JB-9.06b export](JB-9.06b__paper_export_compositing.md) | Restored82 core/132 backend historical cases; pre-layer Multiply and bounded blocks; post-stack and length-guard mutations red. | Own named suite set matters; [independent review](JB-9.06b__space-bunny.md) flags ambiguity if a different five-suite set is inferred. No current rerun or phone export proof. |
| [Paper UI integration](../PAPER_UI_INTEGRATION_20261002.md) | Historical full core1485/backend229, focused Resources5/History2/Chrome5 and UI compile. | Its statement that9.06b/c are pending is superseded by later landings. Not current full-family phone acceptance. |
| [Cold-paper device](COLD_PAPER_DEVICE_20261015.md), [machine receipt](COLD_PAPER_DEVICE_20261015.json) | sourcecf559dad00307cab40c32f08646cf4add7b3cceb; APK SHA2562EA0811EABE983112E2F451F0319CC04690AF14562E0A4F0C0AC16B19E3F781D; Ink+Watercolour42 injected-fault rows,126 prior texture-object checks, three archives unchanged. | Controlled rollback only; no old texture pixel-byte comparison, real OOM/driver/context-loss or live View activation acceptance. Artifact path in JSON is a historical receipt, not freshly rehashed here. |
| [Replay context](PAPER_REPLAY_CONTEXT_20261014.md) | P5 current-paper replay, frozen brush vs paper-resource boundary and saved-factory review. | Asset bytes/catalogue revisions are not pinned per stroke. No new schema/policy implied. |
| [R10 sections11–13](../research/R10_paper_and_canvas.md) | Photographic/felt pipeline, owner feedback and source/lighting findings. | Research notes and PC comparisons do not equal current device/creative sign-off. |

No Gradle, JVM, GPU, app or device tests ran in this docs checkpoint. Original32/21/82/132/etc. counts are linked historical receipts, not fresh XML. Actual checks here: catalogue counts, all referenced look/surface/fluid files exist, ids resolve, asset SHA256 inventory, Git ancestry/status/remote and local Markdown links.

## Known quality gaps and policy constraints

R10 section13 explicitly records the owner's rejection of the subtly crinkled paper: the procedural star-polygon nets are an acknowledged visual defect. Strong and medium free photo candidates are not a substitute for a gently flattened sheet. Preserve both materials and the rejection; do not label this family polished or silently delete it. Still-procedural canvasx3, crumplesx2, papyrus/parchment, silk/fabric, cement, blueprint/off-white and factory/handmade pulp require reviewed real-source or derived-source work. No new aesthetic replacement is authorized by this checkpoint.

The same section records watercolour_cold/rough retaining30%/45% directional lamp shading while look/surface rotatable=true. Neighbouring hexes can turn their retained lamp direction; the issue is recorded as unchanged. A future small paired rotation change needs scoped ownership and parity evidence, not an assumption that any photo should rotate. These findings are not newly verified device defects.

Rice translucency/opacity and dark pulp pigment must not become a1:1 height proxy. Maintain independent physical relief and neutral white balance; do not tint every paper yellow. No new image generation, deposition/filter experiments or brush design is included.

## Preserved worktrees, branches and remote snapshot

Audit inspected eight original worktrees below. All their committed heads are ancestors of both this base and published joy-creator@efcb293bb31285e5ce9c160f2ab91e0b67e7d588. No separate remote codex/jb-9.* heads were returned by ls-remote at recovery; committed content remains published through shared ancestry. Untracked helpers/logs/evidence are NOT thereby backed up. Never clean them to obtain a green status.

| Exact checkout | Branch | HEAD | Dirty state at publication inventory |
|---|---|---|---|
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.03` | `codex/jb-9.03` | `ccf2980b1ba5872074dc3a1f9df8d17b50ad909a` | 3 untracked helper/evidence paths; no tracked edits |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.03b` | `codex/jb-9.03b` | `b1b30633dd61de26816120a8ca16eabd672b7d13` | 17 untracked helper/evidence paths; no tracked edits |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.06` | `codex/jb-9.06` | `2c746d3c14e6962d0d9798e0c2fd616079bf840c` | 9 untracked helper/evidence paths; no tracked edits |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.06b` | `codex/jb-9.06b` | `e5cfa81594c86c6b2c965cfc7b52a4572f9db17e` | 1 untracked helper/evidence paths; no tracked edits |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.06c` | `codex/jb-9.06c` | `0dd712ed0cb9d45981dce0e18c720d8f62738e92` | 5 untracked helper/evidence paths; tracked: D tasks/joybrush/reviews/PAPER_OPENCODE_HANDOFF.md |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.07` | `codex/jb-9.07` | `cd726f0b9f551eebc0bd25b66a3346e396824f82` | 10 untracked helper/evidence paths; no tracked edits |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.08` | `codex/jb-9.08` | `0b7546b575213dc2402c5a1dccc8b42dedcecd16` | 9 untracked helper/evidence paths; no tracked edits |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.10` | `codex/jb-9.10` | `b6202c602c618879bf6f9ee8659b7903e5973920` | 24 untracked helper/evidence paths; no tracked edits |

The tracked deletion in jb-9.06c is reviews/PAPER_OPENCODE_HANDOFF.md. Preserve the deletion and recover the historical committed file via Git if needed; do not reset that checkout. The historical handoff is superseded by this report for export availability and catalogue size.

- Stale primary `C:/+Projects/Screenrecorder/FadCam`: joy-creator@fb80428412374284330eacf94233cafdb0ea9446;472 dirty/untracked paths at October26 inventory. Not pulled/reset/cleaned; count is a dated observation, not current synchronization.
- Coordinator `C:/Temp/jb-select-reconcile-20261024`: source initiallyd4a279bc2 and later741fa0154 while the read-only audit ran. Published coordinator branch was741fa01543265ca89634113892d4d30ea422eceb at last remote check; actual working HEAD advanced again during publication preparation. Do not infer current working source from its last pushed ref.
- `codex/jb-9.06b-ora-atomic@75f63439daef87aefe154f72e8f386610c6d89b3` is preserved and ancestral; no registered checkout found. Tip is a stylus runbook, not the ORA code fix itself.
- `C:/Temp/jb-opencode-dry-paper-only`: clean at72ae66d437295771c403ad2f6b334bd86f4372e4, branchopencode/media-dry-paper-only, not an ancestor of audited coordinator; no matching remote head returned. Preserve same-context shader-retention work; do not whole-file replace current engine.
- Published `opencode/tile-paper-gpu-parity@9eab9146befa5e50b3f38fe6012d6d0361ffe8c2` is not an ancestor of audited coordinator/shared checkpoint. Tip distinguishes signed zero in checker, not runtime activation. Review before adoption.
- Both shared lock files were absent during October26 read-only observation. This lane acquired neither lock and launched no heavy/device jobs. Recheck current holders before any future action.

## Smallest next task: PAPER-PARITY-DESIGN (proposal only)

Exclusive paths: a NEW `reviews/PAPER_PARITY_MATRIX_20261027.md` and, only after coordinator approval, a NEW core JVM test file for current catalogue fixtures; no existing engine/shader/UI/asset edits. First deliver a test design, not another engine. Freeze exact source/catalogue hashes and specify32 backgrounds,29 surfaces, packed-vs-height-only expansion, mip footprints, contrast/heightMean, relief, tint/None and tiled/non-tiled negative-coordinate sampling. Derive fixtures from production Kotlin, not a duplicate Python renderer; validate fixture cardinality and source identity before interpreting GPU error. Include a deliberately changed footprint or source hash as a required red control. Current checker tools may require adaptation because old launch-contact fixtures do not establish current32-look coverage.

Acceptance for the design: every catalogue id appears exactly once in the intended fixture matrix; explicit epsilon/byte limits and zoom/scale coordinates; independent raw-upload oracle; decode channels/gray colour-space specified; retained directional light finding distinguished from numerical parity; no new aesthetic/deposition decisions. Root grants any later test-file ownership and serialized Gradle/GPU job separately. Phone UI/undo/reopen/export and real paper creative acceptance remain a separate root-sequenced gate.

## Complete catalogue/source-asset map

These are exact snapshot metadata, not a claim that every independently selected look/surface pair has identical geometry. Selecting a look uses its default surface; the sheet also permits an intentional independent override.

### Backgrounds

| Look id | Look file (flat if absent) | Default surface | texelPx / hexTexels / rotate |
|---|---|---|---|
| amoled_black | flat | smooth | 2 / 180 / True |
| canvas_linen | look_canvas_linen.png | canvas_linen | 0.75 / 180 / False |
| canvas_cotton_duck | look_canvas_cotton_duck.png | canvas_cotton_duck | 1.0 / 180 / False |
| canvas_jute | look_canvas_jute.png | canvas_jute | 1.25 / 180 / False |
| pulp_factory | look_pulp_factory.png | pulp_factory | 2.0 / 180 / True |
| pulp_handmade | look_pulp_handmade.png | pulp_handmade | 2.0 / 180 / True |
| off_white | look_off_white.png | pulp_artisan | 2.0 / 180 / True |
| crumpled | look_crumpled.png | crumpled | 1.5 / 180 / True |
| crumpled_flattened | look_crumpled_flattened.png | crumpled_flattened | 1.5 / 180 / True |
| rice_cool | look_rice_cool.jpg | rice_fibres | 2.5 / 300.0 / True |
| rice_cream | look_rice_cream.jpg | rice_cream_fibres | 2.5 / 300.0 / True |
| sugarcane | look_sugarcane.jpg | sugarcane_pulp | 2.5 / 300.0 / True |
| construction_tan | look_construction_tan.jpg | construction_pulp | 2.5 / 300.0 / True |
| chalkboard_black | look_chalkboard_black.jpg | chalk_grit | 2.5 / 300.0 / True |
| chalkboard_green | look_chalkboard_green.jpg | chalk_grit | 2.5 / 300.0 / True |
| blueprint | look_blueprint.png | pulp_factory | 2.0 / 180 / True |
| parchment | look_parchment.png | parchment_skin | 2.0 / 180 / True |
| papyrus | look_papyrus.png | papyrus_strips | 1.2 / 180 / False |
| silk | look_silk.png | silk | 0.7 / 180 / False |
| fabric | look_fabric.png | fabric | 1.0 / 180 / False |
| cement | look_cement.png | cement | 1.25 / 180 / True |
| construction_blue | look_construction_blue.jpg | construction_blue_pulp | 2.5 / 300.0 / True |
| cardboard | look_cardboard.jpg | cardboard | 2.5 / 300.0 / True |
| rice_white | look_rice_white.jpg | rice_white_fibres | 2.5 / 300.0 / True |
| rice_lace | look_rice_lace.jpg | rice_lace_fibres | 2.5 / 300.0 / True |
| thai_kraft | look_thai_kraft.jpg | thai_kraft_pulp | 2.5 / 300.0 / True |
| thai_rose | look_thai_rose.jpg | thai_rose_pulp | 2.5 / 300.0 / True |
| watercolour_cold | look_watercolour_cold.jpg | cold_press | 3.014 / 300.0 / True |
| watercolour_rough | look_watercolour_rough.jpg | rough_press | 6.028 / 300.0 / True |
| watercolour_hot | look_watercolour_hot.jpg | hot_press | 3.014 / 300.0 / True |
| drawing_paper | look_drawing_paper.jpg | drawing_tooth | 1.37 / 300.0 / True |
| bristol_paper | look_bristol_paper.jpg | bristol_tooth | 1.096 / 300.0 / True |

### Physical surfaces

| Surface id | Height/surface file | packed | Fluid file | texelPx / hexTexels / rotate |
|---|---|---|---|---|
| pulp_artisan | surface_pulp_artisan.png | True | none | 2.0 / 180 / True |
| canvas_linen | height_canvas_linen.png | False | none | 0.75 / 180 / False |
| canvas_cotton_duck | height_canvas_cotton_duck.png | False | none | 1.0 / 180 / False |
| canvas_jute | height_canvas_jute.png | False | none | 1.25 / 180 / False |
| pulp_factory | height_pulp_factory.png | False | none | 2.0 / 180 / True |
| pulp_handmade | height_pulp_handmade.png | False | none | 2.0 / 180 / True |
| crumpled | height_crumpled.png | False | none | 1.5 / 180 / True |
| crumpled_flattened | height_crumpled_flattened.png | False | none | 1.5 / 180 / True |
| rice_fibres | height_rice_fibres.png | False | fluid_rice_fibres.png | 1.0 / 300.0 / True |
| sugarcane_pulp | height_sugarcane_pulp.png | False | fluid_sugarcane_pulp.png | 1.0 / 300.0 / True |
| construction_pulp | height_construction_pulp.png | False | fluid_construction_pulp.png | 1.0 / 300.0 / True |
| chalk_grit | height_chalk_grit.png | False | fluid_chalk_grit.png | 1.0 / 300.0 / True |
| parchment_skin | height_parchment_skin.png | False | none | 2.0 / 180 / True |
| papyrus_strips | height_papyrus_strips.png | False | none | 1.2 / 180 / False |
| silk | height_silk.png | False | none | 0.7 / 180 / False |
| fabric | height_fabric.png | False | none | 1.0 / 180 / False |
| cement | height_cement.png | False | none | 1.25 / 180 / True |
| construction_blue_pulp | height_construction_blue_pulp.png | False | fluid_construction_blue_pulp.png | 1.0 / 300.0 / True |
| cardboard | height_cardboard.png | False | fluid_cardboard.png | 1.0 / 300.0 / True |
| rice_white_fibres | height_rice_white_fibres.png | False | fluid_rice_white_fibres.png | 1.0 / 300.0 / True |
| rice_cream_fibres | height_rice_cream_fibres.png | False | fluid_rice_cream_fibres.png | 1.0 / 300.0 / True |
| rice_lace_fibres | height_rice_lace_fibres.png | False | fluid_rice_lace_fibres.png | 1.0 / 300.0 / True |
| thai_kraft_pulp | height_thai_kraft_pulp.png | False | fluid_thai_kraft_pulp.png | 1.0 / 300.0 / True |
| thai_rose_pulp | height_thai_rose_pulp.png | False | fluid_thai_rose_pulp.png | 1.0 / 300.0 / True |
| cold_press | height_cold_press.png | False | fluid_cold_press.png | 1.0 / 300.0 / True |
| rough_press | height_rough_press.png | False | fluid_rough_press.png | 1.0 / 300.0 / True |
| hot_press | height_hot_press.png | False | fluid_hot_press.png | 1.0 / 300.0 / True |
| drawing_tooth | height_drawing_tooth.png | False | fluid_drawing_tooth.png | 0.5 / 300.0 / True |
| bristol_tooth | height_bristol_tooth.png | False | fluid_bristol_tooth.png | 0.5 / 300.0 / True |

### File receipt

All paths are relative to `joybrush/assets/paper/`; includes catalogue and unreferenced supporting files if present.

| File | Bytes | SHA256 |
|---|---:|---|
| catalogue.json | 23331 | `59109007882c334b73282ecdcfada5099f6c4c5cdf8e31db9faad386cf3c1ac3` |
| fluid_bristol_tooth.png | 121990 | `77114d2b6ce46f91e8154563aaa9242f4bdc749529a7767eee6783184084d86b` |
| fluid_cardboard.png | 203934 | `43b01f9f105d851ccaed78ee8f63433c98b05d69d16d301425f2361788749f1d` |
| fluid_chalk_grit.png | 193918 | `85162f3a35c8816806290342cefeeaee0ae659b5643919e1d761cc5ecaeb711d` |
| fluid_cold_press.png | 111093 | `05a8e6c2a3b70cd0d5321e09a43030f1dd5086bc673259528b6ba4a683b42fdd` |
| fluid_construction_blue_pulp.png | 199625 | `7b66292dee19894244a188781579bbe2d859316598965d37432f14b5913596d0` |
| fluid_construction_pulp.png | 203917 | `cd85dda304afa892020b1fa57eff2efd7e7b1bc9dc159e21b2b9abf5b6167c35` |
| fluid_drawing_tooth.png | 114788 | `2603c06007b6f4b436ab584abd3d12728a15d24b6d9196f478c761b5ccadd3b2` |
| fluid_hot_press.png | 111093 | `05a8e6c2a3b70cd0d5321e09a43030f1dd5086bc673259528b6ba4a683b42fdd` |
| fluid_rice_cream_fibres.png | 203113 | `c503a1d7c731cc928bc1fe0cf0a3ff7b6598000119ac16a9106c6e88ccf08981` |
| fluid_rice_fibres.png | 203466 | `4ecc322cce1e8f600b61566aca5549e9f35b9aa02e89541caf691e66f6f14c9a` |
| fluid_rice_lace_fibres.png | 204847 | `8d78bb1308dbb463b953dd189dfa403f0f2c4c9b424aa102ad44edef6d654afc` |
| fluid_rice_white_fibres.png | 202984 | `5b2087a28ccfc910ea36d06ae24af3a5da7a5cf63b4978a05adea843d127682d` |
| fluid_rough_press.png | 97813 | `8ece484365bfd499fa2691ad07f61ddcaf8227124bfb59c47741bd9b6c3d92a7` |
| fluid_sugarcane_pulp.png | 197494 | `bbb365ea94f5cfa777968aecf2abfd41cb463fc067f20b303e59d124737d19c8` |
| fluid_thai_kraft_pulp.png | 200151 | `a82ea196eaa8992fa2c6eb543b41f1d18449cae7ce3d6000036763a8498bba11` |
| fluid_thai_rose_pulp.png | 200884 | `4c4b93b6eacf26e0e22cb989c08a68ab813fab610b9410b81bbb7933a85ef575` |
| height_bristol_tooth.png | 772204 | `6fa18e472a25e74fea90e29a5a41de55926a9fb03dcec34741c8abb2ab5d9484` |
| height_canvas_cotton_duck.png | 117565 | `4cbf00eb0aea6660db137ceb0fd5c11097342b2dd2cba608ad2a598fdfcc359e` |
| height_canvas_jute.png | 105565 | `a41903d16ad3f02326fdaa50d4293ae05026325cb831864591a9c5db55754fde` |
| height_canvas_linen.png | 120089 | `e648203157c144432acae6ba62882a75d22f6f75f86a32324a6c7cb39c7ab602` |
| height_cardboard.png | 1013439 | `c2eae3de48f5fe0b57ec5b7dbd2b268f7bad45f6e9ba8e0b08d7bfd311ffebe3` |
| height_cement.png | 159976 | `071c42676579958d4b3a578adc0cfed83de7aa50e36c725867495efe29b19d84` |
| height_chalk_grit.png | 1007877 | `53cab03a34d4559264a76a03a0727f50ae90f870d3b93a958dd231d81ca6ec02` |
| height_cold_press.png | 837057 | `6158e2d688c90a6b8a9f3a18e90e2ada96534f32c3e4d60a71646468fdfd5b34` |
| height_construction_blue_pulp.png | 1027837 | `240ab532f3293de9ae7181de30477fee917e9207bee33695c6337b7a2c44eee7` |
| height_construction_pulp.png | 1024355 | `187bfd407c220ee112609843bcaea137a94b0028eef52ee34cfd8fc1b642a718` |
| height_crumpled.png | 74449 | `4f5eda04cb7f450135a44ac0794d3f5942e7a0ae7defd0ab6e733f95b22bdb17` |
| height_crumpled_flattened.png | 35811 | `f6b99755aee6de657cb070dd4e3a85bdca1af0a3b974fabd6685426db86e8427` |
| height_drawing_tooth.png | 863789 | `a5d126849fe7b687af4f7fd54dc8ff01c775e1bf78b50d681ad1ad3d6aebf8b8` |
| height_fabric.png | 120714 | `5a0bac1be592f7b36e84539315a1a53ae8124b46025b32dd4dacdd2c19e50d5e` |
| height_hot_press.png | 957696 | `f8eab4aaf6681103c0f99aabbc571afcfe95f6f852ce0406c801d5961bb11385` |
| height_papyrus_strips.png | 49932 | `3ca9d3a4f0d23154a0e5d90c17cd4274b680b490de9ac3801938674e473dc3f8` |
| height_parchment_skin.png | 60799 | `4bfce6f446b2c053dd1892ffb09eece6e244456191fddb8fc25927ece379deef` |
| height_pulp_factory.png | 150285 | `560cd9e1df44b38f5ea2e430741b2b18fc6a4541ae6cc2ab1f18a544f112a343` |
| height_pulp_handmade.png | 162828 | `77e82a054da533f20774a8e2f9937e810a694efc702cab0e3eecbcfae4fab223` |
| height_rice_cream_fibres.png | 815219 | `c2e2b46c65307768593b2eba3783645515d26ffaa87de1de1db93b93911f46f3` |
| height_rice_fibres.png | 815445 | `ca05e491cb0796bbd0c27e2ff9acfcb3561b343adaad13c4666f0b42439ca996` |
| height_rice_lace_fibres.png | 809596 | `766fd2d2d1644868ed140b964c332d22a2c6527e33ef2b9b075faac3b668e8e2` |
| height_rice_white_fibres.png | 819079 | `63d4934da94418494b262c5487e9a63d4e4dbbaad93d2712c90cc2a4360dd70c` |
| height_rough_press.png | 776800 | `a7b321e71f2b526eea0d87259ffda22fe1cff7329e6dcbfec64674e556b17e1a` |
| height_silk.png | 115370 | `9e97da1da58b67e9a7da1e8827822d6e719816f08c3e0714d5fedc9f27ade6e3` |
| height_sugarcane_pulp.png | 996650 | `2311301280e924c96a9056858396a3b17904dbe771eaf63497111eed220b2c19` |
| height_thai_kraft_pulp.png | 996767 | `29aa6c27c28b1db271e19add11e283e0a60cd31fbeeeac068e3ee0cde073aaf8` |
| height_thai_rose_pulp.png | 989150 | `42dd1dd0e7d4c98614db27ebd94a85358b7f6a6fd2f0526589de09c8d88b1615` |
| look_blueprint.png | 57780 | `920a8799827537feb13cfc10bb1845410b0f9b2dca39c55248c575c428b31922` |
| look_bristol_paper.jpg | 34518 | `641a84a8c3c0434c4d4a4aa66e97d4152f1c226b91ca571ba310f706cd3ec0be` |
| look_canvas_cotton_duck.png | 70060 | `99dfff3f157a23da87ccb23fa64ee8390ecfcc07da95f6142a9142056ab8d668` |
| look_canvas_jute.png | 77089 | `0f7e63929d4121049c5164373d10c28a6f737e7740a6311707e739f1e6f3e694` |
| look_canvas_linen.png | 53342 | `93ba42f89bc969ee11e163ec85f57916590160018a8ea8df2ad6c72e72ceefbc` |
| look_cardboard.jpg | 432350 | `a6b3ff779b6b2289e56778a08c711f47a7b82759d3e365603e52b2ca6b6c081a` |
| look_cement.png | 115700 | `f34457a4cb8869bafba35542ceddcba92473dab0d737da1503a2097e7db6eb0b` |
| look_chalkboard_black.jpg | 322045 | `064a3587bd5ee857704be324468f2e75674e4a6fb7599dc26421b57c71a5cccc` |
| look_chalkboard_green.jpg | 365441 | `9aa86db1aa9d3e899e022839a296cf005b3d3911f06d0b8762d2885639750f1f` |
| look_construction_blue.jpg | 446629 | `9e8501d43c20b83614a20981f3005900582bb8564e91f5e9aa384ac3d1331892` |
| look_construction_tan.jpg | 446588 | `0cd7f6f706dbd7614b18b4a5dcfb67fda508636d65adfbbb33caad061dd0e782` |
| look_crumpled.png | 61479 | `ef51a0c65c9d73f04618fab82d0ca9df3350b7cd424e9d5f317c48588256b1f6` |
| look_crumpled_flattened.png | 35064 | `2c5c624735f52b0126779a2d7866c714c8f04455c38ff6ee10a5fa4afb4c5d46` |
| look_drawing_paper.jpg | 46703 | `a799f9a8aef2aed04eb1e30969f45677078c4c346d5ce51319bd740abc5a6366` |
| look_fabric.png | 59706 | `cb41b449d59b9d73e59dd9b22c9907576b1a9bf8f38d96cdd2c36b7d5ff05a68` |
| look_off_white.png | 90086 | `b410c27fc48fc3c989dd09fdc4d34af35cde02d3b1c162187cd3a48f43873621` |
| look_papyrus.png | 83064 | `e9db615410b5ca0077c108f3b9c289032d0d2cbbdb29eaba8f690eb965bc82a3` |
| look_parchment.png | 74108 | `64190acf882e495b11fe73a9894f5eab83b174cfe0925667e2e9657eb6f35226` |
| look_pulp_factory.png | 58036 | `22ef07c8e5261e22d38174f99889b4d5558f88d59b5c317de55388c9920ee754` |
| look_pulp_handmade.png | 65257 | `2107b7d39aea292569c86476ddfac57e56669eaf66eb4094a747208f48be727b` |
| look_rice_cool.jpg | 341222 | `fd2d77405bdbf41e717b69a45aa2e5504050eed961d08199f9500d291d703f2c` |
| look_rice_cream.jpg | 287249 | `479959c17d7d23d6b1d0268341b881756eda22c07c3d6f92bc4e91104c00dfc4` |
| look_rice_lace.jpg | 308988 | `033f456c57ed6893c77b8cd0e56d4c812386c70b633ec5420d66954efc309a32` |
| look_rice_white.jpg | 264687 | `853344d3a9780949334968a1ad0a73127133479e26dec269d16a05b74097de40` |
| look_silk.png | 50164 | `5057b1510fc606367e606acf0b31b125b70bd93b62beb85a3b76d7696688b8a8` |
| look_sugarcane.jpg | 379728 | `3e1974910219aa5d41abb97fd7fb6b45b45772784926047d937a64e53f9af6de` |
| look_thai_kraft.jpg | 471172 | `236e5b8db3191417481c69d4f0909c820e2882ae7e457a7800a91183ac08ea33` |
| look_thai_rose.jpg | 425960 | `afe0d86d2dfb9e2d1ee0f85b9503885c8f6be38ca6c45d9b7d179e6b6849136d` |
| look_watercolour_cold.jpg | 94710 | `8dd2f6f958a2991940c9df56533ed134f6043f292629146f6b84960a6d199996` |
| look_watercolour_hot.jpg | 43013 | `92415cba1af1dfabb8f0607077a07c524cb36fbdf93de5ded8698bb088cb0ef3` |
| look_watercolour_rough.jpg | 120674 | `72efc03487064fa4b131f8bdf22be00c669b13b5e56950130b221893a0dc1241` |
| SOURCES.md | 7197 | `1e6fa2b5a1450bd933bbc21a6cd47d52d6a4a25bd4792eb34daa5bc7438dc93b` |
| surface_pulp_artisan.png | 821305 | `cd90eefe21ad0126049318193ad6ea74eaa00b28c44f9081e53277a54381f3be` |
