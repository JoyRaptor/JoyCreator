# Sprite export / K10 acceptance coverage — October 27, 2026

Docs-only final handback. No new feature, application test run, build, phone action,
lock or source change. Checkout `C:/Temp/jb-board-recovery-20261026`, branch
`codex/board-recovery-docs-20261026`, starting clean at
`0e6e2e8a91c571c114509f5e44c89469668b36ec` (remote matched before work).
Underlying canonical source741fa0154; reviewed test files below have no diff
through root's adopted recovery commit0604c8a74. This is not an audit of subsequent
unfrozen runtime changes. Root owns shared queue and engine/View/Activity.

## Source keys

- [S: SpriteSwapExportTest](../../../joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/io/SpriteSwapExportTest.kt)
- [L: BoardChromeLayoutTest](../../../joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/chrome/BoardChromeLayoutTest.kt)
- [V: BoardChromeViewTest](../../../joybrush-android/src/test/kotlin/cc/joycreator/joybrush/android/board/BoardChromeViewTest.kt)
- [R: BoardRuntimeControllerTest](../../../joybrush-android/src/test/kotlin/cc/joycreator/joybrush/android/board/BoardRuntimeControllerTest.kt)
- [C: SpriteCellOpsTest](../../../joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/doc/SpriteCellOpsTest.kt)
- [E: BoardExportCoordinatorTest](../../../joybrush-android/src/test/kotlin/cc/joycreator/joybrush/android/board/BoardExportCoordinatorTest.kt)
- [Contract: K10 and updated K8](../specs/JB-3.00a_boards_owner_model.md)
- [Recovered source / historical APK scopes](BOARD_RESIDUAL_20261026.md)

## Existing Sprite artifact test — what it actually establishes

S contains one test, `swapMovesBothLayersThroughRealSheetExport`. It calls actual
`SpriteCellOps.swap`, production `RegionTransferTiles.slices`, actual
`BoardExport.stage`, then independently decodes the PNG with ImageIO. A test-side
byte-copy loop applies transfers from immutable before-state; it is **not the GL
executor**. Geometry fixture: board(-260,-1,780,3), grid3×1,260×3 cells.

| Requirement | Existing assertions in S | Specific missing proof in this test |
|---|---|---|
| Both layers move, hidden lower paint preserved | Four reciprocal transfers; lower red/green swap inspected directly; top yellow/transparent swap reflected in decoded sheet | No production GPU publication, texture lifetime, rollback or joint Undo |
| Transparent source replaces destination | Top cell0 becomes zero; decoded cell0 shows lower green instead of stale yellow | No absent-white mask transfer; fixture explicitly asserts maskTransfers empty |
| Negative/tile-boundary translation, no mirror | Negative origin and260px cells cross256px grids; asymmetric1px corner markers and edge samples | One horizontal pair only; no vertical/2D-grid pair, swapback artifact or arbitrary translation corpus |
| Unrelated artwork intact | Every third-cell pixel checked in both layers; outside sentinel unchanged | One outside sentinel, not exhaustive outside-region checksum |
| Export agrees with committed modeled bytes | Actual PNG dimensions780×3, expected color/marker samples, file count2 | Sidecar content is not parsed; file count alone proves no grid/timing/relative-URI contract; no actual receiver/reopen |
| Multi-board/held/linked ownership | Static shared cels only | No crossing Animation ownership, saved cursor, held/linked cel or mask artifact oracle |

Do not duplicate this useful test as a generic “sprite swap export missing” task.
C separately covers same-cel/swapback, both Animation boundaries/hidden/shared/
held/linked addresses, white-default masks and legacy-INK ownership planning.
Those model tests do not fill S's actual GPU/export/save gaps. E separately has
`allImagesUseFolderAndSpriteLabHandsOffTheCapturedGrid` and
`pickerRecreationPreservesExactFrameRangeAndBoardTarget`; callback/Bundle tests
are not receiver asset/reopen proof. Historical actual receiver/MP4 evidence stays
in the recovered report, not relabeled as a fresh run here.

## K10 and owner Sprite clarification matrix

| Owner acceptance | Existing test source | Remaining proof boundary |
|---|---|---|
| All board kinds icon-only at rest | L `restIsIconOnlyForEveryKind` | Layout output, not all actual paper backgrounds/contrast/screenshots |
|48dp approach brackets, return fade; number only beyond Animation frame1 | L `bracketsAt48DpIncludingBoundary`, `bracketFadeAfterLeaving`, `onlyAnimationPastFrameOneHasRestNumber` | Host hover/paint approach lifecycle at rotated pan/zoom and physical S Pen needs scoped runtime/device proof |
| HOLD play opens FPS, release does not play; tap plays | L `fpsHoldPanelAbovePeg`; V `holdingPlayRequestsFpsWithoutTogglingPlaybackOnRelease`; R `nativePlaybackUsesPreviewAndStopsWithoutChangingCursor` | These separate checks do not prove combined FPS edit→held timing→pause/reopen on current APK |
| First committed wrapping stroke sets saved tile icon; mere arm does not | L `tileFlagChangesOnlyOnWrappedStrokeAndSurvivesDisarm`, serialization tests; R `tileChromeHidesOtherBoardsAndArmingDoesNotChangeSavedMetadata`, `tileToolHintAppearsOnlyAfterFirstSuccessfulArmAndSurvivesPause`; BoardDocumentOps/RegionDocument JSON tests | Pure helper/metadata and controller arming are not atomic successful-stroke publication with undo/save. Historical October7 phone subset exists; current realistic-media refusals must remain |
| Unlocked resize retains counts; typed cells grow whole grid; one commit/Cancel | R `spriteHandlePreviewsCellAndBoardSizesWithoutHistoryAndCancelRestores`, `spriteCornerCommitsOneAtomicEditAndQueuedEditRejectsChangedGrid`, `typedCellDimensionsRetainCountsAndGrowTheBoard` | Callback transaction count, not actual paint-address preservation across save/reopen or real GPU Undo |
| Locked finger sequence, separate arm swap; pen always paints | R `lockedSpriteSequenceIsTransientAndPenStillPassesThrough`, `lockedArmedSpriteDragCommitsOneSwapAndCancelCommitsNothing`, `boardButtonRemainsInteractiveWhileSpriteCellsPassAllPaintingPointersThrough` | Native dispatch/host-model evidence, not physical stylus pressure/feel or complete all-media swap executor |
| Preview/strip survive lifecycle and stable identity changes | R paused-thumbnail/refetch and stale-frame-drop tests; V `replacementIdentityCannotUsePreviousScenesGeometryBeforeNextUiFrame`, nested-target/strip-cancel tests | No current end-to-end captured/media/INK parity implied; active FRAME-DUP/TWEEN/CLIPS/LIVE-SOURCE lanes remain excluded |

## Bounded next gate / Claude handback

1. Root chooses a frozen source and reviews adjacent coverage before assigning new
   tests. Small artifact candidate: add a **separate vertical/masked static-raster
   sheet + parsed sidecar fixture** using independent expected pixels, white mask
   default and existing schema parser. Own test-only path; no renderer changes.
2. Request only that named backend test gate after source review, under root's
   resource lock/queue. No build grant requested or consumed in this assignment.
3. GPU swap/one-Undo/save/reopen and current K10 visual/pen acceptance remain
   separate runtime/device gates with exact source/APK/run/data receipts. Do not
   infer them from the CPU fixture or remove unsupported realistic-media guards.

Root roadmap-row proposal (not applied; new-report-only scope): BOARD-RESIDUAL
coverage matrix recovered; existing two-layer decoded export and K10/native
tests inventoried, no rerun; current masked/vertical/sidecar artifact and actual
GPU/visual acceptance open. Link this report and preserve active lane exclusions.
STATE proposals remain with root; this report introduces no new user capability.

Verification for this docs patch: local link resolution and whitespace checks only;
application tests actually run **none**. Publication SHA/remote equality, final
dirty status and any worker outcome are supplied in the handoff message.
Luna independently inventoried named K10/Sprite tests read-only; the specialist
reviewed the returned names against source. No worker edits or execution proof.
