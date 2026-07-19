# KineMaster-class Playhead lane — 2026-07-19

JoyRaptor's directive: clone the best parts of KineMaster's playhead so Faditor is
competitive-or-superior to CapCut/KineMaster. Four timeline-view features, all
implemented in `com.fadcam.ui.faditor.timeline.EditorTimelineView` (the god view
that owns the playhead / ruler / selection / trim-drag state). No held file was
edited (GlTransition*, FaditorEditorActivity, ProjectStorage, UndoManager,
LayerRowRenderer, FaditorPlayerManager).

## What built

### 1. Playhead time-chip
- Floating dark pill at the top of the playhead (in the ruler band, centred on the
  playhead x, clamped on-screen), white monospace-ish bold text, subtle
  context-coloured border.
- Format `mm:ss.mmm`, hours prefix `h:mm:ss.mmm` only when > 1h (`fmtChipTime`).
- Bigger/bolder while scrubbing (`playheadScrubbing`, set in `updatePlayheadFromX`,
  cleared on ACTION_UP/CANCEL).
- String is cached (`chipCachedMs`/`chipCachedText`) so a static frame does no
  `String.format` — respects the view's no-work-in-onDraw perf culture.
- Drawn by `drawPlayheadChip(...)` from `drawCenterPlayhead`.

### 2. Context-coloured playhead + chip border
- `resolvePlayheadContextColor()` picks the target: trim-drag tint (amber
  `COLOR_PLAYHEAD_TRIM`) > legacy-audio selection (aqua) > master-clip selection
  (blue) > selected layer item's row-family colour (by `TrackKind`) > neutral white.
- Row-family colours MIRROR `LayerRowRenderer`'s per-kind constants (they are
  `private` there, so they are re-declared here as `COLOR_PH_*` with a comment
  pointing back — the hex values are identical: text/sticker purple `8C3DFA`,
  audio aqua `35F6BF`, sprite amber `FFB74D`, caption gold `FFC107`, visualizer
  cyan `4DD0E1`, video/image/master blue `4397FD`).
- Smoothly animated (~150ms `ValueAnimator` + `ArgbEvaluator`, one reused
  animator) via `updatePlayheadContextColor()` called at the top of `onDraw`;
  `playheadColorCurrent` drives both the playhead line and the chip border.

### 3. Dotted guide lines over the selected part
- `drawContextGuides(...)` (from `drawCenterPlayhead`, screen space):
  - Horizontal short-dash guides along the TOP and BOTTOM of the selected item's
    row band, extended across the full visible width, in the low-alpha context
    colour. Implemented for MASTER-clip selection (`segRects[selectedIndex]`) and
    legacy AUDIO selection (`audioClipRects[selectedAudioIndex]`).
  - Vertical short-dash guide at the dragged edge during a MASTER trim
    (`trimDragX`), full strip height.
- Reused dashed `guidePaint` (DashPathEffect, no per-frame alloc); colour/alpha set
  per frame.

### 4. Bookmarks (ruler markers)
- Long-press on the RULER band drops/removes a bookmark at that time; tap on a
  bookmark seeks to it (`seekToTimelineMs`, which drives the existing
  `OnSegmentActionListener` so the player follows). Ruler-area-only detector
  (`rulerLongPressRunnable` + `rulerTouchActive`/`rulerLongPressFired`), inserted
  at the top of `onDown`/`onUp` so the existing scrub/drag/pickup arbitration is
  untouched (a scroll cancels the pending long-press via `onScroll`).
- Diamond glyphs drawn on the ruler (`drawBookmarkGlyphs` inside `drawRuler`,
  content space, viewport-culled, reused `bookmarkPath`).
- Model: `List<Long> bookmarksMs` added to `FaditorProject` (getter + add/remove/
  toggle helpers) as directed. The view keeps its own working copy (survives
  `setTimeline`), exposed via `setBookmarks`/`getBookmarks` + a `BookmarkListener`.
- Persistence: a standalone sidecar `BookmarkStore` helper reads/writes
  `<projectDir>/bookmarks.json` (does NOT touch the held `ProjectStorage`).

## Scoped down / TODO

- **Bookmark persistence wiring is deferred (held `FaditorEditorActivity`).** The
  view has no `FaditorProject`/projectDir reference (it only receives a `Timeline`
  via `setTimeline`), and the only code that configures the view lives in the held
  activity, so I could not wire `BookmarkStore.load(...)` → `setBookmarks(...)` on
  open, nor `setBookmarkListener(...)` → `BookmarkStore.save(...)` on change. The
  model (`FaditorProject.bookmarksMs`), the sidecar helper (`BookmarkStore`), and
  the view API are all in place; only a ~3-line wire-up in the activity remains.
  In-session bookmarks (drop / remove / seek / draw) work fully.
  TODO: after the GL review lands, either add that wire-up in the activity OR fold
  `bookmarksMs` into the manual `ProjectSerializer`/`ProjectDeserializer` in
  `ProjectStorage.java` (project.json path).

- **Layer-item horizontal guides (feature 3) are scoped to master + audio.** The
  per-row band rect for TEXT/STICKER/VISUALIZER/etc. items is owned by the held
  `LayerRowRenderer` (`RowLayout.bodyRect`, no public accessor), so I cannot draw
  the top/bottom guide along a floating-layer item's band without editing that
  held file. The context COLOUR still tints correctly for those kinds (that only
  needs `TrackKind`, which the view can resolve). TODO: add a
  `bandRectForItem(id)` accessor to `LayerRowRenderer` when it un-freezes, then
  extend `drawContextGuides` to the layer bands.

- **Vertical trim guide covers the master trim only** (uses `trimDragX`). Audio /
  layer-item trim edges track their edge x elsewhere; TODO to extend once the
  above lands.

- **Undo integration skipped** (TODO). Bookmark add/remove is not on the undo
  stack: `UndoManager.java` is held, and there is no addable undo seam reachable
  without editing it. TODO: register a bookmark snapshot op when UndoManager
  un-freezes.

## Device-verify checklist

- [ ] Chip shows at playhead, reads `mm:ss.mmm`, grows/bolds while scrubbing.
- [ ] Chip shows `h:mm:ss.mmm` only past 1h.
- [ ] Playhead line + chip border animate (no hard flip) when selecting a master
      clip (blue), an audio clip (aqua), a text/sticker item (purple), a
      visualizer (cyan); neutral white with nothing selected.
- [ ] Playhead goes amber while dragging a trim handle, animates back on release.
- [ ] Dotted top/bottom guides appear along the selected master clip's band and
      the selected audio clip's band, spanning the visible width; subtle, not
      fighting the green selection ring/handles.
- [ ] Vertical dotted guide appears at the dragged edge during a master trim.
- [ ] Long-press on the ruler drops a diamond bookmark; long-press it again (or
      tap+long-press near it) removes it; tap a bookmark seeks the player there.
- [ ] Scrubbing by dragging the ruler still works (long-press does not hijack it).
- [ ] Pinch-zoom / segment drag / item pickup unaffected.
- [ ] (Persistence) once wired: bookmarks survive close/reopen via bookmarks.json.
