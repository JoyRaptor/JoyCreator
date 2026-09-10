# Diagnosis — Asset Browser State (2026-06-26)

**Device:** SM-N960U `<note9-serial>` (1440×2960, 560dpi)  
**App:** `com.fadcam.beta` v4.0.0-beta9  
**Project:** `bdd51919-f47d-4d33-b91e-e6ebbc56e445` (schema v7, 8 clips, 2 audio, 0 text overlays, 2 waveforms)  
**Watcher:** Running, `BUILD SUCCESSFUL` confirmed  

---

## Verification method

- Code analysis (all asset-browser/relevant source files)
- Device UI dump (`uiautomator`) for exact button coordinates
- SAF picker launch confirmed (pin button triggers `PickActivity`)
- Project JSON pulled via `run-as` + `exec-out` (avoids PowerShell `>` corruption)
- `importInsertedAsset` read in full; relink catalog, panel z-ordering, affordance bar verified

---

## 1. What works (verified by code + device)

| Feature | Status | Evidence |
|---------|--------|----------|
| Pin button in top bar | ✅ Working | `bounds=[189,28][329,168]` — tappable, launches SAF picker. Uiautomator confirms `clickable=true` |
| SAF directory picker | ✅ Working | `PickActivity` starts on pin tap (observed in logcat) |
| Insert affordance bar z-order | ✅ Fixed | Added **after** panel via `root.addView(insertAffordanceBar)` at line 11585. Old `btn_insert_at_playhead` XML button fully removed |
| Copy-on-insert (`importInsertedAsset`) | ✅ Implemented | Line 11767. Images + files ≤25MB copied to `{projectDir}/assets/{uuid}.ext`. Larger videos keep original URI |
| Schema v6/v7 relative paths | ✅ Implemented | `toStorageUri()`/`fromStorageUri()` at ProjectStorage:480/501. `project://` prefix for internal assets |
| Relink catalog | ✅ Implemented | `RelinkCatalogBottomSheet`, `buildMediaCatalog()`, `showRelinkCatalog()`, `applyRelink()`, `autoRelinkSiblings()` — robust |
| Panel persistence (scrub/play/undo) | ✅ Working | Scrubbing/minimap/play/undo don't call `collapse()` — only viewer tap dismisses via scrim |
| Long-press = rename | ✅ Implemented | `showRenameDialog()` in AssetBrowserPanel, `renameAsset()` in activity. Tap opens insert affordance (A3 redesign) |
| `isSourceAccessible()` | ✅ Exists | Line 1747. Checks `file.exists()` for `file://`, `openInputStream` for `content://` |
| Control row layout | ✅ Matches M4 target | Magnet (snap) → Undo → Play/Pause (centered at `[650,1533][790,1673]`) → Redo → Link. Relink is in the row still (moved later in M4) |

---

## 2. Concrete bugs / missing items

### B1-core: MISSING media shows silent black (the "busted" complaint)

**Critical.** The most user-visible bug. When a clip's `sourceUri` is inaccessible:
- `isSourceAccessible()` returns `false`  
- `countMissingMedia()` catches it and auto-shows the relink catalog on **project load**
- **BUT** during preview/scrub/playback, there is NO MISSING indicator. No overlay, no badge, no text. The player simply can't decode → black frame → user thinks it's broken

**Files affected:** `FaditorEditorActivity.java` — the preview pipeline (`resolvePlaybackUri`, `loadClip`, `seekTo`, `play`) has no check for `isSourceAccessible()` before trying to play. The player surfaces `ExoPlaybackException` silently.

**No `isSourceResolvable()` exists** — the M1 plan calls for creating it with caching. Only `isSourceAccessible()` exists (unwrapped, no caching).

### B1-missing: No MISSING badge/overlay in timeline or preview

Even when the relink catalog shows, the **timeline clips** and **preview** show black (no MISSING state). The plan (M1) requires a visible MISSING placeholder with filename + "Tap to relink" on the clip in the timeline and preview.

### B1-missing: No MISSING check on export

Export has no pre-flight check for missing media. If a clip is missing, the export will likely produce a black segment or crash. M1 calls for blocking export with a clear toast listing missing files.

### A3 partial: Insert affordance arrow is always green (no yellow "cut" mode)

The A3 spec says: green arrow when playhead is at a clip boundary; **yellow** when insert will split a clip. Currently the arrow is always green — the split-warning logic is not wired.

### A3 partial: Insert affordance has no label above it

The spec says "Insert" (green) or "Cut" (yellow) label above the arrow. No label is rendered.

### A5 partial: Drag-and-drop likely incomplete

`AssetBrowserAdapter.Callback` has `onItemDragStarted` callback. `FaditorEditorActivity` has `startAssetDrag()` and `endAssetDrag()` methods. However:
- No yellow dotted split line implementation found
- No edge auto-scroll
- No minimap dwell-jump
- Dragging back to panel to cancel not verified

### A3 partial: Flanking insert-before/insert-after icons not Material-specific

The code shows `"|< Insert"` and `"Insert >|"` as text buttons. The design spec asks for bracket-with-arrow icons or `"⇤▮"` / `"▮⇥"`. This is cosmetic but noted as incomplete.

### M4 pending: Relink still in control row (not yet moved to long-press menu)

The relink button (`btn_relink_media`, link icon) is still in the control row at `[937,1550][1042,1655]`. M4 calls for moving it to the long-press media menu. This is by design (M4 is a later milestone).

### M4 pending: No magnet long-press prefs

The magnet icon exists (`btn_soft_snap` at `[398,1550][503,1655]`), but long-press to open snap preferences is not implemented.

---

## 3. Design items fully DONE (from PLAN_asset_browser_v2_layers.md)

| Item | Status |
|------|--------|
| A1 — Resizable panel (grab handle) | ✅ Done (road_map 4.1) |
| A2 — Panel persistence | ✅ Done (road_map 4.2) |
| A3 — Insert affordance (basic) | ✅ Done (road_map 4.3) |
| A3 — Z-order fix (arrow above panel) | ✅ Done (code verified) |
| A4 — Long-press = rename | ✅ Done (road_map 4.4) |
| B2 — Green arrow disappears on panel close | ✅ Fixed |
| C — Play/pause centered | ✅ Done (road_map verified) |
| Schema v6/v7 + project:// relative paths | ✅ Done |
| Copy-on-insert hybrid strategy | ✅ Done |

---

## 4. Design items NOT started

| Item | Status | Notes |
|------|--------|-------|
| M1 — MISSING state (not-black) | ❌ Not started | The single most important fix |
| M1 — MISSING badge/overlay | ❌ Not started | |
| M1 — Export block on missing | ❌ Not started | Need product decision (block vs skip) |
| M2 — Consolidate Project review | ❌ Not started | |
| A5 — Drag-drop (yellow line, edge scroll) | 🔶 Partial | Infra exists, UX missing |
| M3 — Relink moved to long-press menu | ❌ M4 deferred | |
| M4 — Magnet long-press prefs | ❌ M4 deferred | |
| M4 — Link toggle (master-link) | ❌ M4 deferred | |

---

## 5. Recommendation

The core "busted" pain is **B1/M1: missing media shows black** — the most user-visible problem. The copy-on-insert (`importInsertedAsset`) is already in place for new inserts, but the **preview pipeline doesn't handle the MISSING case at all**. Start M1 immediately: add a `MISSING` state check to the preview/playback path, render a distinct overlay, and block export.

M2 (Consolidate) is lower priority — it's a safety/portability feature, not a data-loss fix.

M3/A5 (drag-drop) can follow M1 since it's additive UX on top of working infrastructure.
