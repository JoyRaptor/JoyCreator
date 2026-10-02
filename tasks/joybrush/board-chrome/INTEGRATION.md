# JB-3.00a: Lead integration handoff

Status: core and standalone Android chrome compile. JoyBrushActivity.kt has NOT been edited. The adjacent Activity-wiring.patch contains the exact proposed Activity hunks; approval is required before applying them. This is an attachment/data-flow boundary, not a claim that board painting or export has been connected.

R30 says `JoyBrushActivity.kt` is a shared hot file with a lock order. The owner explicitly required stopping before editing it. Main also contains another lane's staged Activity and engine changes. Reconcile those changes before applying this proposal; never overwrite their file with this worktree's version.

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
- Host.art draws already-clipped preview/cell art. Host.frost supplies the existing shared blurred backdrop underneath 55% glass when Frost is enabled. No label or button gets blurred.
- Use BoardChromeLayout.layerMarker and another BoardChromeView in each applicable layer row. The actual held/animated layer mutation belongs to the document adapter.
- When Layout.tileCanvas is true, the canvas renderer supplies full-strength edge-to-edge repeated tile art and wrapped painting. Layout.suppressOtherBoards hides all other board chrome. The core keeps the original-tile ring and docks the one exit switch eight dp inside the nearest edge.

## Gaps against §K

1. **K6 shared export sheet is not implemented here.** FaditorEditorActivity.showExportConfirmation is a private, approximately 400-line video-project dialog: filename, audio cleanup/LUFS, resolution/quality, keep-awake, audio-only, single-frame/timecode, range validation, queue/export service. It is an AlertDialog, not the specified 236 dp glass board sheet. Board chrome resolves the existing app ic_export_studio.xml and exposes export callbacks. A Lead-owned extraction must provide one shared sheet/presenter, add board all/range/current scopes plus GIF/PNG frames/sprite sheet/Send to Studio, memory line and gradient button, preserve Studio's existing exports, and obey 55% glass plus Frost. No sheet was copied.
2. **Real playback, tiling, frame/layer switching, swaps and the one-time wrap warning require the host/engine rows.** The committed canvas in this base accepts a single CANVAS board; another lane's staged projection work is deliberately excluded. This branch implements chrome layout/state output and event callbacks only. It does not prove pixel behavior.
3. **Board menu and text-entry panels are host actions.** Their view attachment is not implemented in this standalone renderer. The title and size affordances/typing ring/caret are drawn by core; rename/type actions need the Lead's existing panel cluster.
4. **Loop mode is a host state/action.** The picture supplies the loop path only; the renderer uses that source glyph. A different ping-pong/once glyph has not been invented. The host can cycle the existing loop mode while keeping the picture's loop mark.
5. **Visual/device verification remains with the owner.** Browser automatic review rejected the local file URL and forbade workarounds; the owner approved source inspection. Tokens, dimensions, glyph paths and tooltip strings were read from HTML/spec. No browser or Note 9 visual match is claimed, and nothing was installed.

## Verification

Core command: `./gradlew --no-watch-fs -p joybrush :core:jvmTest` (Windows wrapper equivalent). BUILD SUCCESSFUL. XML: 95 suites, 1,449 tests, zero failures/errors/skips; BoardChromeLayoutTest: 28 tests. Covers the implemented K1–K5/K7–K10 numbers and rules, including density, touch size, edge docking, lifted group transforms, fades, hover restoration and v4 default/v5 tile serialization. K6 sheet geometry has no test because it is not implemented.

Android check: `:joybrush-android:compileDebugKotlin :app:compileDefaultDebugJavaWithJavac`. This compiles the board view, shared typography/fonts, extracted strip and SpriteLab adapters. Shared RollDragController javac harness: 20 checks passed. The harness is independent of the XML suite count.

No changes to the protected paths, no Gradle execution in main, no merge into joy-creator, no phone install. Worktree: `C:/Users/JoyRaptor/AppData/Local/Temp/jb-board-chrome`, branch `codex/jb-board-chrome`, based on joy-creator `7bc60742`.
