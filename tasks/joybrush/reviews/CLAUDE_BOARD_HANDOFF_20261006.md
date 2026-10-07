# Joy Brush board build handoff — October 6, 2026

The owner is an artist. Boards are the lead's priority; Claude is actively building the paper and paint engines. Test scratches are disposable, and no artwork migration is needed. Maintain common PAINT/INK board ownership so later vector brushes use the same board/frame system. Latest connection evidence is in `BOARD_CONNECTIONS_20261006.md`; coordinate ownership before integrating either checkout.

## Source and build

Integration source lives at `C:/Temp/jb-region-routing`, branch `codex/region-routing`. The primary `C:/+Projects/Screenrecorder/FadCam` checkout is heavily dirty and has other agents' work. Its watcher builds that checkout, which does not contain this integration automatically. Do not overwrite the phone with that APK or wholesale merge/reset the dirty checkout. Fetch the integration branch and coordinate exact source ownership with the lead before modifying GlPaintEngine, JbCanvasView or JoyBrushActivity.

Installable integration APK: `C:/Temp/jb-region-routing/app/build/outputs/apk/default/debug/app-default-arm64-v8a-debug.apk`. The lead verifies installed `base.apk` SHA-256 against that exact file. Only Note 9 is authorized for this session; no uninstall or Note 20 deployment. App assembly uses the watcher, one build slot at a time. See `BOARD_CONNECTIONS_20261006.md` for the latest installed fingerprint and export/held acceptance; `NOTE9_BOARD_TEST_20261006.md` records the earlier placement/frame/painting checkpoint.

## Connected foundation

Menu → Boards chooses Image, Animation or Sprite, drag places a rectangle, then Apply creates it. Passive selection is separate from saved frame navigation. Selected board bounds crop layer/mask previews; passive selection returns to page previews. Shared paint outside Animation boards and bounded per-frame paint inside each board are routed by common ownership plans. Linked and held cels, history and transient playback have dedicated core/engine contracts. ExportChoiceSheet now dispatches GIF, all/range PNG frames with timing, sheet/JSON, frame PNG and Image batches. K9 layer markers toggle held state through bounded content transactions and Undo. Captured export targets survive the Android picker; no live cursor mutation.

Phone testing caught and fixed touch/callback integration failures that compile checks alone missed: Sprite pen filtering used the wrong generated control prefix; failed frame thumbnails repeatedly requested themselves; placement removal reentered CANCEL; new frame identity cancellation synchronously refreshed itself. Keep regression tests that exercise the real parent dispatcher and production controller callbacks.

## Unfinished specification work

October 7 source adds unlocked live handle sizing, editable board/cell dimensions, locked finger CellRoll preview and separately armed translated all-layer/mask swaps. Counts remain fixed when sizing cells; typed board dimensions snap to whole cells. Generic PAINT/INK address plans are shared; raster executor refuses unsupported vector content atomically. Core 1642, Androidkit 275, native 68 tests pass; watcher APK succeeds. Note9 is disconnected, so these NEW controls still require phone acceptance. Earlier count/grid/guide and sheet/JSON tests on October 6 remain valid historical evidence. See SPRITE_GRID_INTEGRATION_20261007.md for exact APK fingerprint, limits and testing checklist. Sprite export metadata still needs a cell-count label.

- Full-screen seamless tiling and actual wrapped painting, saved first-wrap flag and exits.
- Phone acceptance of the newly connected Sprite resizing, all-layer swaps and sequences.
- Onion skins.
- Exact bounded transactions for Animation geometry, duplication/removal and move-all-frames.
- Studio/SpriteLab receiver bridges and adapting Studio to the same export presentation.
- Exact glass/frost board forms, adaptive contrast and reduced motion. Current placement forms are provisional AlertDialogs.
- Fill pen work and full physical S Pen pressure/tilt/feel acceptance.

The lead has not claimed that boards are fully finished. Existing brush engines must continue to draw through the shared physical-cel ownership and Undo contracts. Advanced paper remains a setting, never a layer; paper inclusion in export stays explicit.
