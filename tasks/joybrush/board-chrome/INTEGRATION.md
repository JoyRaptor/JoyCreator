# JB-3.00a: Lead integration handoff

Status: core, standalone Android chrome and reusable export presentation compile and pass tests. JoyBrushActivity.kt has NOT been edited. The adjacent Activity-wiring.patch is a historical proposal: DO NOT APPLY. The Lead's BOARD_INTEGRATION_DECISION_20261002.md supersedes it and assigns the live adapter and final Activity wiring to the Lead. Board painting and export dispatch are not connected here.

R30 says `JoyBrushActivity.kt` is a shared hot file with a lock order. The owner explicitly required stopping before editing it. Main also contains another lane's staged Activity and engine changes. Never overwrite their file with this worktree's version.

The owner has delegated technical judgment to the parallel JoyBrush Lead chat and directed this lane to route technical approval questions there. Activity approval, shared export extraction and landing coordination now go to that Lead, not to the owner.

## Proposed Activity boundary

The patch adds a transparent board layer above guides and below the existing chrome, renders keyed scenes from a BoardChromeBinding supplied by the Lead, observes existing raw pen events without intercepting painting, refreshes after view movement and committed strokes, and cancels ephemeral hover/drag interactions before pause or destruction. The binding is absent until the Lead supplies a real document/session adapter, so no inert board controls appear.

Every scene contains the pure core Input and the Android Host callbacks. Input rectangles are screen pixels. Geometry, clipping, fades and touch targets come from BoardChromeLayout; host ink comes from the existing IconContrast sampler, not a guessed paper brightness. Refresh must also occur on frame, board, grid, onion, lock, selection and order changes and on fade/wiggle animation ticks. Do not persist selection, hover playback or these animation clocks.

## Required host responsibilities

- Read actual boards from the live document. Preserve their IDs, held frames, layer membership, lock, grid and saved tiled flag. Project the board rectangle through the live view transform. Measure the title if an exact title hit width is needed and pass titleWidthPx.
- Send only successful wrapped strokes to BoardChromeLayout.afterWrappedStroke; an arm tap never changes Board.tiled. Save that display flag with the drawing. At landing, recheck DOC_VERSION against the Lead's current model version (this isolated base was v4, so this branch is v5).
- Feed pen approach and selected-board down/lift clocks into Input. Recompute while 120/300 ms fades or the 300 ms wiggle are active. Respect reduced motion; it suppresses the wiggle while the fixed lifted-cell pose remains.
- Use Host.action for select/menu, rename, typed size, feature toggle, lock/confirmed move, export, onion, play, loop mode, FPS stepper, grid and preview controls. Typed sizes preserve the board's top-left through BoardChromeLayout.typedSize.
- Use Host.frame for frame-wheel steps. Host.hoverLoop is ephemeral: play the board at its own held-frame timing and restore the supplied frame on exit. It must not select, mutate the drawing or save. Call stopInteractions before pause.
- Host.stripScrollBy resubmits scroll offset; Host.stripLift/stripGap resubmit liftedFrame/insertionIndex. Host.stripDrop dispatches existing animation operations as one undo. Sprite drag/drop swaps every layer of both cells as one undo. Chrome does not store pixels.
- Supply `BoardChromeView.sceneIdentity` before `show`: the board ID and ordered frame IDs of that scene. A changed board/order cancels the captured gesture. With this identity, dragging a strip cell body scrubs continuously through `Host.stripScrub(boardId, frameId)`; holding its body still lifts it for reorder/remove. The right-edge grab zone comes from `core.anim.FilmStrip.edgeAt` (24 dp, left side only), not another view constant.
- `Host.stripHold(boardId, frameId, holdAtDown, dragPx, density, finished)` carries the original hold, screen displacement and density. Evaluate the existing `FilmStrip.setHoldByDrag` against the gesture's real document snapshot. `finished=false` is a transient width preview only; **never write it into the document, undo log or save queue**. `finished=true` commits once as one undo step. `Host.stripHoldCancelled` clears the transient preview on CANCEL, second finger, scene/order/host replacement or lifecycle stop. A release without a drag makes no duration change. The host validates the stable IDs and that the original hold is still current before committing. Preview input may update holds; this does not retarget or restart an active edge gesture. Without `sceneIdentity`, old strip callbacks retain their existing behavior.
- Host.art draws already-clipped preview/cell art. Host.frost supplies the existing shared blurred backdrop underneath 55% glass when Frost is enabled. No label or button gets blurred.
- Use BoardChromeLayout.layerMarker and another BoardChromeView in each applicable layer row. The actual held/animated layer mutation belongs to the document adapter.
- When Layout.tileCanvas is true, the canvas renderer supplies full-strength edge-to-edge repeated tile art and wrapped painting. Layout.suppressOtherBoards hides all other board chrome. The core keeps the original-tile ring and docks the one exit switch eight dp inside the nearest edge.

## Gaps against §K

1. **K6 presentation exists; operational sharing/export remains incomplete.** BoardExportLayout and ExportChoiceSheet provide the 236 dp, 55% glass sheet with typed animation/range/current-frame choices, format chips, memory line and gradient button. Geometry follows source CSS flex sizing; shared font measurement supplies intrinsic text widths. Unsupported actions default to disabled. Studio can reuse the component through its existing joybrush-android dependency, but its private export flow was not copied or restructured. Lead owns Studio adaptation, actual dispatch and range validation. Image/sprite presets remain host responsibilities.
2. **Real playback, tiling, frame/layer switching, swaps and the one-time wrap warning require the host/engine rows.** The committed canvas in this base accepts a single CANVAS board; another lane's staged projection work is deliberately excluded. This branch implements chrome layout/state output and event callbacks only. It does not prove pixel behavior.
3. **Board menu and text-entry panels are host actions.** Their view attachment is not implemented in this standalone renderer. The title and size affordances/typing ring/caret are drawn by core; rename/type actions need the Lead's existing panel cluster.
4. **Loop mode is a host state/action.** The picture supplies the loop path only; the renderer uses that source glyph. A different ping-pong/once glyph has not been invented. The host can cycle the existing loop mode while keeping the picture's loop mark.
5. **Visual/device verification remains with the owner.** Browser automatic review rejected the local file URL and forbade workarounds; the owner approved source inspection. Tokens, dimensions, glyph paths and tooltip strings were read from HTML/spec. No browser or Note 9 visual match is claimed, and nothing was installed.

## Verification

Core command: `./gradlew --no-watch-fs -p joybrush :core:jvmTest` (Windows wrapper equivalent). BUILD SUCCESSFUL. XML: 99 suites, 1,477 tests, zero failures/errors/skips; BoardChromeLayoutTest: 28 tests. Includes the original layout/state/model tests plus bounded raster planning, preview/identity/input contracts, K6 presentation geometry and interrupted pen fades.

Android check: `:joybrush-android:testDebugUnitTest :app:compileDefaultDebugJavaWithJavac`. BUILD SUCCESSFUL. Native Robolectric API 28 XML: 2 suites, 17 tests, zero failures/errors/skips. Checks transparent gaps/passive boards, overlapping targets, cancellation, host replacement, coalescing, shadow reuse, frame wheel, stable export gestures, slide-away cancellation, hold-play without a release tap and hover restoration/lifecycle exit. Shared RollDragController javac harness: 20 checks passed previously, independent of the XML counts.

## Lead follow-up contracts

Owner-requested polish follow-up: see POLISH_ACCEPTANCE.md for the state-by-state acceptance matrix. BoardChromePenFade now carries actual opacity through interrupted strokes while keeping 120 ms out / 300 ms back and 12% endpoints. Use one ephemeral clock per scene; reset on pause/disposal. This avoids the quick-tap lift darkening jump and the restart flash during a return fade. The host still supplies scheduled UI frames; this helper owns no timer or painting sample.

BoardChromeView uses ordinary hardware rendering and a 4 MiB bounded cache of small shadow rasters. The pure render plan splits each surface into at most 256×256 px patches and only rasterizes the perimeter of stroked rectangles. Test fixture: selected 548×1126 px outline uses 153,024 bytes versus 2,468,192 bytes for a full-window ARGB surface; passive fixture uses under 4,096 bytes. These are source/test measurements, not a Note 9 performance claim. Strip fades use bounded layers. `show` coalesces chrome state to one UI frame and ignores selected-board pen-coordinate changes that cannot change layout; painting pen samples must remain untouched.

Use a stable Host instance per live scene. Freeze BoardChromeIdentity from that scene before translating strip indices or committing actions; reject stale IDs/order. Call stopInteractions on scene/graphics loss and pause. Hover-loop callbacks are preview-only and must restore the prior frame without persisted selection. Host.acceptsPointer can reserve stylus input for painting; touch gaps and passive chrome pass through.

BoardLayerPreview.scope implements owner §G7a: active board bounds, current animation frame ID, normal page scope when passive. Cache keys include scope, layer ID and content revision, not pen events. Lead owns actual GPU reads and held-layer resolution; do not capture thumbnail pixels for each pen sample. ExportChoiceSheet freezes frame IDs for the gesture and supplies typed choices only; the host validates capabilities and performs export.

No changes to the protected paths, no Gradle execution in main, no merge into joy-creator, no phone install. Worktree: `C:/Users/JoyRaptor/AppData/Local/Temp/jb-board-chrome`, branch `codex/jb-board-chrome`, based on joy-creator `7bc60742`.


## Lead integration checkpoint 2026-10-05
Imported into codex/region-routing without the old Activity patch or schema. Stable-ID strip draft included. Core integration: 1619 tests, zero failures/errors, 4 optional corpus skips. Androidkit: 254, zero failures/errors. Native UI: 29, zero failures/errors, including all 20 BoardChromeView tests. The host adapter and device acceptance remain unfinished; these results prove components, not a fully wired board UI.
