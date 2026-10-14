# Joy Creator wings — what JoyBot can send people to (static read, 2026-10-14)

Source for JoyBot's "Take me there" routes and studio-wide help (plan Phase 2/4). Verify a line before relying on it.

| Wing | Entry | Extras | Notes |
|---|---|---|---|
| Shell | `MainActivity.switchFragment(pos, true)` | tabs: 0 Capture (Home / FadRecHome), 1 Library (Records), 2 Remote, 3 Studio list (FaditorMini), 4 Setup, 5 Finder (Smart Detection), 6 Lobby | |
| Studio editor | `FaditorEditorActivity` | `EXTRA_PROJECT_ID`, `EXTRA_VIDEO_URI(S)`, `EXTRA_START_BLANK_AUDIO`, `EXTRA_LOAD_BACKUP`, or `setData(video)` | AI button `btn_ai_assistant`; add-asset sheet (video, image layer, black clip, PiP, audio, voiceover, AI slide, FX layer, image clip); visualizer `addWaveformVisualizer`; export `ExportService` |
| JoyBrush | `DrawingLibrary.openIntent` (`EXTRA_DRAWING`) / `DrawingsActivity` | — | JoyBrush lane owns it. Exports GIF/PNG frames/sprite sheet/Send to Studio/Open in SpriteLab via `JoyBrushBoardImportActivity` |
| SpriteLab | `SpriteSheetEditorActivity` | `EXTRA_PROJECT_ID`, `EXTRA_SHEET_ID`, `EXTRA_RELINK` | slicing, auto-grid, cell names, pivots, presets, sequences, dope sheet |
| Avatar Studio | `AvatarStudioActivity` | `EXTRA_PROJECT_ID`, `EXTRA_RIG_ID`, `EXTRA_LIBRARY_DIR` | pose matrix, face tracking, mic lip-sync, `.avatar` library |
| VisLab | none yet (Lobby falls back to Studio) | — | visualizer lives in Studio + recorder |
| Recorder | Capture tab; `RecordingService` / `ScreenRecordingService` | — | floating controls, webcam bubble, annotation |
| Slides | `generate_slide` tool; Studio "AI slide" sheet `showSlideImportSheet` | — | HTML/GSAP → frames, template fallback offline |
| Joy Creator folder | `JoyFolderSetupActivity.intent(ctx)` | — | Share-in `ShareInActivity` + `ShareInbox` inserts at the playhead |
| Trash | `TrashFragment` via `OverlayNavUtil.show(..., "trash")` | — | Lobby logo long-hold |

Hand-offs that exist: Library→Studio, any app→folder→Studio (share-in), JoyBrush→Studio/SpriteLab, Studio→SpriteLab/Avatar,
sprite→timeline (`placeSpriteOnVideo`), avatar library→Studio, export→folder. **Missing: any JoyBot → room navigation.**
