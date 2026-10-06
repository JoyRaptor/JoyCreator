# Board controls checkpoint — 2026-10-06

Continuation of 84af5244 on codex/region-routing. This is a working integration slice, not a declaration that the board specification is finished.

## Implemented

- Saved frame navigation has no Undo step. Transient playback owns a display override map, never the persisted document. Pen-down clears overrides before freezing paint ownership.
- Metadata history retains later navigation for unrelated edits and restores cursors for intentional frame edits. Removed frames cannot remain current.
- Shared core preview plans accept PAINT and INK, including held and linked content. Bulk plan construction validates once per tick. Concrete GPU/PNG renderers still explicitly require raster rendering support.
- Native board selection, placement, renaming, passive geometry, frame wheel/scrub, duplicate/link/blank/delete/reorder/hold, FPS and playback are connected to serialized canvas APIs.
- Frame strip pictures use the actual bounded GPU composite, including blending, masks and paper. Readback restores preview plans and GL framebuffer/viewport state. Requests complete on refusal as well as success.
- Every selected board scopes layer and mask pictures to its bounds; passive selection returns to page scope. Content revisions prevent cursor navigation from invalidating all cached frame art.
- Rotated board chrome follows the canvas transform, with oversized sparse hit surfaces. Pen fading uses the shared fade clock; approach departure fades for 300 ms. Hover always loops independently of manual playback mode.
- Frame drops freeze source identities and original order, and refuse order changes before the deferred transaction runs. Pause stops preview/fade scheduling.
- Board-corner and menu PNG export capture the chosen board and saved frame, retain the target across picker recreation, and never change live navigation. The frame timing/encoding formats still need shared export dispatch.
- Strip art is bounded to an 8 MiB LRU cache. Animation strip cells accept pen navigation; Sprite cells reserve pen strokes for painting. Hiding the main chrome also hides board controls and stops their previews.

## Verification

Final backend suites: core 1625 tests, zero failures/errors, four optional corpus skips; androidkit 259 tests, zero failures/errors/skips. Native UI: 42 tests, zero failures/errors. Parent-dispatched pen taps pass at -45, +45 and 90 degrees, with pan/zoom and painting-gap pass-through. Stale deferred frame drops, hover LOOP versus manual ONCE, pause scheduling, old thumbnail replies and failure completion are checked.

Watcher app assembly: `:app:assembleDefaultDebug` BUILD SUCCESSFUL, 276 tasks, 1m 6s. APK: `app/build/outputs/apk/default/debug/app-default-arm64-v8a-debug.apk`. No device installation. Native compile review fixed anonymous View parent shadowing and missing enum fallback; the rotated pointer test waits for settled native layout before dispatch.

## Remaining required work

- Connect shared board export choice presentation and remaining output dispatch, including batches, GIF, range, sheet sidecars and Studio handoff. Current single-board/current-frame PNG is operational.
- Complete animation one-frame geometry and explicit all-frame content operations; duplicate/remove require exact bounded transactions.
- Full-screen seamless tiling with actual wrapped commits, saved first-wrap marker and exit control.
- Armed Sprite cell swaps across all layers and sequence controls.
- Onion rendering and held-layer row markers.
- Replace provisional native form dialogs with shared glass/frost board presentation, adaptive ink and reduced motion.
- Phone acceptance against K/K10 after the owner device-control gate. No APK was installed during this checkpoint.

Disposable test scratches need no migration. Advanced paper/brush work remains paused; vectors must reuse board/content ownership rather than a parallel board model.
