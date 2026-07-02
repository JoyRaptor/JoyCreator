# Asset Browser — Implementation Plan

## Overview
A pinned-directory asset browser that drops from the top-left, shows video/image/audio
previews, supports drag-to-timeline (cut-and-insert) and tap-to-insert-at-playhead.

## Architecture (built with layers in mind)
- **Panel:** Custom `FrameLayout` dropdown from top bar, NOT a bottom sheet.
  Semi-transparent scrim so user can still scrub video behind it.
- **Grid:** `RecyclerView` with `GridLayoutManager`, Glide thumbnails.
- **Drag:** Custom touch-based (same approach as timeline reorder), not Android DnD.
  Floating `ImageView` follows finger. Drop targets on timeline.
- **Insert:** Cut-and-insert at position (pushes clips right). Overlay-to-new-layer
  deferred until multi-layer track system exists.
- **Storage:** `pinnedAssetDir` (tree URI string) + `assetDirHistory` (list) in project JSON.
  Schema v3.

## Phases

### Phase 1: Foundation (model + storage + icons)
- [ ] `FaditorProject`: add `pinnedAssetDir`, `assetDirHistory`, `assetDirTrash`; bump SCHEMA_VERSION to 3
- [ ] `ProjectStorage`: serialize/deserialize new fields with safe defaults for v≤2
- [ ] `activity_faditor_editor.xml`: add `btn_asset_browser` (push_pin icon) next to `btn_close`
- [ ] `activity_faditor_editor.xml`: add bottom-centered `btn_insert_at_playhead` (down arrow)
- [ ] `FaditorEditorActivity`: register `OpenDocumentTree` launcher for dir picking
- [ ] `FaditorEditorActivity`: wire `btn_asset_browser` click → `showAssetBrowser()`
- [ ] `FaditorEditorActivity`: wire `btn_insert_at_playhead` click → insert selected asset at playhead
- [ ] `strings.xml`: add all `faditor_asset_browser_*` strings

### Phase 2: Browser Panel
- [ ] `AssetItem.java`: data model (uri, name, type, durationMs, thumbnail, isUsed)
- [ ] `AssetBrowserPanel.java`: custom dropdown FrameLayout
  - Semi-transparent background (alpha ~0.92)
  - Top bar: path text (horizontally scrollable), change-dir button, < > carrots, filter toggle, trash
  - Body: RecyclerView grid of assets
  - Animate drop-down / collapse
- [ ] `AssetBrowserAdapter.java`: RecyclerView adapter
  - Grid of thumbnails (video=image=audio with type badge)
  - "Used" badge/overlay for files already in project
  - Long-press → mini video preview (MediaMetadataRetriever getFrameAtTime loop)
  - Tap → callback to insert at playhead
  - Start drag → callback to begin drag mode
- [ ] `AssetScanner.java`: scan a SAF tree URI for video/image/audio files
  - Uses DocumentFile.fromTreeUri().listFiles()
  - Returns sorted list of AssetItem
  - Marks which are used by current project (match by URI string)

### Phase 3: Insert Logic
- [ ] `FaditorEditorActivity.insertAssetAtPlayhead(Uri, String type)`:
  - Probe duration via getVideoDuration()
  - Create Clip
  - Compute insertIndex from playhead position
  - If playhead is mid-clip: split at playhead, insert after split point
  - If playhead is between clips: insert at that position
  - Record undo, select segment, save
- [ ] `FaditorEditorActivity.insertAssetAtPosition(Uri, int timelineIndex)`:
  - Used by drag-drop: insert at specific index, pushing right
- [ ] Rename support: `AssetBrowserPanel.renameAsset(AssetItem)`
  - DocumentFile.renameTo() — preserves extension
  - Update project references if the file was used

### Phase 4: Drag and Drop
- [x] Drag state machine in `FaditorEditorActivity`:
  - `assetDragItem`: the AssetItem being dragged
  - `assetDragView`: floating icon preview following finger
  - `assetDragMode`: active/cancelled via `assetDragActive`
- [x] `EditorTimelineView` drop-target detection:
  - `startAssetDrag()` / `updateAssetDrag()` / `endAssetDrag()`
  - `getInsertIndexAtX(float screenX)`
  - cut-line + ghost clip preview while over the timeline
  - Edge scroll when dragging near left/right edges
- [x] Minimap hover: when drag is over minimap strip, scroll timeline without dropping
- [ ] Release on folder area → cancel, highlight the asset
- [x] Release on timeline → execute insert

### Phase 5: Polish
- [ ] Filter toggle: all / used-only / unused-only (3-state cycle)
- [ ] Directory history navigation (< > carrots)
- [ ] Trash: remove directory from history (only if no assets from it are in use)
- [ ] Visual: "used" files show a small green checkmark badge
- [ ] Visual: type badges (film/photo/music icon) on thumbnails
- [ ] Haptic feedback on drag start, snap, insert
- [ ] Save pinned dir with project; restore on reopen

## Key Design Decisions
- Panel drops from TOP (not bottom sheet) so it doesn't interfere with bottom controls
- Semi-transparent so user can see video + scrub while browsing
- Drag uses custom touch handling (consistent with existing timeline drag)
- Cut-and-insert only for now; overlay-to-layer deferred
- Linked instances: same URI used multiple times = multiple Clip objects pointing at same source
- Rename uses DocumentFile.renameTo() (SAF-compatible)
- All new fields nullable/empty-list for backward compat with schema v2

## Files to Create
- `app/src/main/java/com/fadcam/ui/faditor/assetbrowser/AssetItem.java`
- `app/src/main/java/com/fadcam/ui/faditor/assetbrowser/AssetBrowserPanel.java`
- `app/src/main/java/com/fadcam/ui/faditor/assetbrowser/AssetBrowserAdapter.java`
- `app/src/main/java/com/fadcam/ui/faditor/assetbrowser/AssetScanner.java`

## Files to Modify
- `app/src/main/java/com/fadcam/ui/faditor/model/FaditorProject.java`
- `app/src/main/java/com/fadcam/ui/faditor/project/ProjectStorage.java`
- `app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java`
- `app/src/main/java/com/fadcam/ui/faditor/timeline/EditorTimelineView.java`
- `app/src/main/res/layout/activity_faditor_editor.xml`
- `app/src/main/res/values/strings.xml`
