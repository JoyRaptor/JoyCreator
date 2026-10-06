# Joy Brush board build handoff — October 6, 2026

The owner is an artist. Boards are the lead's priority; advanced brushes/paper were deferred to Claude. Test scratches are disposable, and no artwork migration is needed. Maintain common PAINT/INK board ownership so later vector brushes use the same board/frame system.

## Source and build

Integration source lives at `C:/Temp/jb-region-routing`, branch `codex/region-routing`. The primary `C:/+Projects/Screenrecorder/FadCam` checkout is heavily dirty and has other agents' work. Its watcher builds that checkout, which does not contain this integration automatically. Do not overwrite the phone with that APK or wholesale merge/reset the dirty checkout. Fetch the integration branch and coordinate exact source ownership with the lead before modifying GlPaintEngine, JbCanvasView or JoyBrushActivity.

Installable integration APK: `C:/Temp/jb-region-routing/app/build/outputs/apk/default/debug/app-default-arm64-v8a-debug.apk`. The lead verifies installed `base.apk` SHA-256 against that exact file. Only Note 9 is authorized for this session; no uninstall or Note 20 deployment. App assembly uses the watcher, one build slot at a time. See `NOTE9_BOARD_TEST_20261006.md` for the final installed fingerprint, tests and actual device outcomes.

## Connected foundation

Menu → Boards chooses Image, Animation or Sprite, drag places a rectangle, then Apply creates it. Passive selection is separate from saved frame navigation. Selected board bounds crop layer/mask previews; passive selection returns to page previews. Shared paint outside Animation boards and bounded per-frame paint inside each board are routed by common ownership plans. Linked and held cels, history and transient playback have dedicated core/engine contracts. Current single-board/current-frame PNG uses an immutable captured target through the Android save picker.

Phone testing caught and fixed touch/callback integration failures that compile checks alone missed: Sprite pen filtering used the wrong generated control prefix; failed frame thumbnails repeatedly requested themselves; placement removal reentered CANCEL; new frame identity cancellation synchronously refreshed itself. Keep regression tests that exercise the real parent dispatcher and production controller callbacks.

## Unfinished specification work

- Full-screen seamless tiling and actual wrapped painting, saved first-wrap flag and exits.
- Sprite cell swaps across all layers and sequences.
- Onion skins, held-layer controls and row markers.
- Exact bounded transactions for Animation geometry, duplication/removal and move-all-frames.
- Shared board export choice sheet and GIF/range/PNG sequence/sheet sidecar/batch/Studio dispatch.
- Exact glass/frost board forms, adaptive contrast and reduced motion. Current placement forms are provisional AlertDialogs.
- Fill pen work and full physical S Pen pressure/tilt/feel acceptance.

The lead has not claimed that boards are fully finished. Existing brush engines must continue to draw through the shared physical-cel ownership and Undo contracts. Advanced paper remains a setting, never a layer; paper inclusion in export stays explicit.
