# JB-0.08b — Save, open and "Save a copy…" on the Joy Brush screen

| | |
|---|---|
| **Tier** | T2 (device check by owner) |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-0.05, JB-0.08a |
| **Owner area** | EDIT `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt` (add the functions below only), EDIT `joybrush-android/src/main/java/cc/joycreator/joybrush/android/JoyBrushActivity.kt` |
| **Estimated size** | ~200 lines |

## Goal
Work is never lost: autosave on leaving the screen, reopen where you left off, and a "Save a copy…"
the owner can put anywhere (Downloads, SD card, cloud folder).

## Decisions
1. **Working file:** `context.getExternalFilesDir("joybrush")/current.joybrush`. ⚠️ This folder is
   deleted if the app is uninstalled — that is why (3) exists, and why Joy Brush documents move to
   THE VAULT when roadmap item 2.1 lands. Never `adb uninstall` (START_HERE rule 3).
2. **Autosave** in `onPause` (and after 30 s of no strokes while open). Reopen `current.joybrush` in
   `onCreate` if it exists.
3. **"Save a copy…"** pill → `ACTION_CREATE_DOCUMENT` (type `application/x-joybrush`, default name
   `Joy Brush <yyyy-MM-dd HHmm>.joybrush`) → `JbArchive.write` to the returned Uri's stream.
   **"Open…"** pill → `ACTION_OPEN_DOCUMENT` → `JbArchive.read` → load.
4. JbCanvasView gets:
   ```kotlin
   /** GL thread does the reads; callback on the UI thread. */
   fun snapshot(onReady: (JbContents) -> Unit)
   fun load(contents: JbContents, onDone: () -> Unit)
   ```
   `snapshot` builds a JbDocument with `DocOps.newDocument` sized to the view (one CANVAS board,
   one PAINT layer whose id matches the engine layer, one cel "cel-1"), fills tiles from
   `engine.tileKeys` + `engine.readTile`. `load` calls `engine.resetDocument()` then `writeTile` for
   every tile of the first PAINT layer. (Multi-layer and ink arrive with 2.04/5.01.)
5. File I/O on a background thread (`Executors.newSingleThreadExecutor()`), never on the UI or GL
   thread. A failed open shows a toast with the exception message and keeps the current drawing.

## Verification
Watcher build green; on the SANDBOX phone: draw, press Home, force-stop from Recent apps, reopen →
drawing is back. Save a copy to Downloads, clear, Open… it → drawing is back. Screenshots.

## Do not
Don't write anywhere else on disk. Don't touch the engine or other app files.

## Definition of done
Screenshots · watcher green · commit `JB-0.08b: save, open, save a copy` · ROADMAP row → 🟧 Built.

## Questions
