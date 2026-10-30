# Timeline bookmarks and export regions — approved October 23, 2026

Owner approved the interactive marker-wheel concept and asked for native implementation.
Reference: C:/Users/JoyRaptor/.codex/visualizations/2026/10/23/01a16abb-e888-72d0-9269-2555af1140eb/joy-creator-marker-wheel.html.

## Interaction contract

- Tap the drawn playhead timestamp to drop a bookmark at the current timeline time. A second
  bookmark within the same frame is not created; open the existing bookmark instead.
- Hold the timestamp to open the compact Bookmarks / Regions popup. Tap existing ruler marks to
  seek; hold a ruler mark to open its settings. The bottom-left time keeps Jump to Time.
- Names, colors, and exact bookmark times are editable. Regions have names, independent In/Out,
  color, export selection, and one optional active loop target. In/Out capture at the playhead
  supports either order, rejects reversed/empty ranges, and preserves saved regions.
- A tap and each side-paddle step animate the selected row to the center; no teleport. The list
  supports flicking and a soft centered resting divot, with a short decelerating settle.
- A bookmark's name and compact seconds share one row; trim insignificant trailing zeroes.
  Long names scroll only when focused/edited and respect system animation preferences.
- Editing has a cyan outline on the exact row plus a named editing header. App-standard active
  chip colors apply to ordinary toggles. Marker identity uses named app gradients, with readable
  knockout ink; being exported and being the active loop are separate states.
- When the playhead matches a bookmark within half a project frame, its timestamp and line use
  the bookmark's gradient. Otherwise retain the existing playhead/context treatment.

## Visual contract

- Reuse the exact ic_marker_flag_start/end and ic_export_studio drawables.
- Reuse studio_backdrop_graffiti at its original brightness with no tint or opacity overlay.
- Use softly rounded upside-down trapezoids; keep the outer tab edges vertical and only their
  shared seam diagonal. Other visible buttons use pills. Paddles sit on the left/right edges.
- Use Studio tokens, fonts, and motion helpers; gradient color is information, not a new theme.

## Data and composition

- Persist stable point/region IDs and metadata in a versioned, atomic project sidecar. Migrate
  legacy bookmarks.json offsets without losing existing points; continue mirroring offsets for
  existing JoyBot callers. Store times outside a shortened timeline rather than deleting them.
- All changes are undoable as complete marker-workspace snapshots. Loading, redraw, scrubbing,
  and animation never add undo entries. Failed persistence must not silently erase the last file.
- Region looping uses the existing timeline seek/playback path, preserving audio and overlays.
- Selected regions export as individually named jobs through the existing snapshot/queue flow.
  A region is a time window on the finished composition, so source timing, speed, transitions,
  overlays, masks, and audio must remain aligned; do not destructively trim the editor project.

## Completion checks

- Compile the native files and resources; prove the new symbols are packaged in the APK.
- Focused model/store checks include legacy import, roundtrip metadata, invalid/reversed ranges,
  failed writes, and preservation of newer or malformed files.
- Verify tap versus hold, animated centering, editing outline, gradient matching, save/reopen,
  undo/redo, loop boundaries, and named range-export duration/preview alignment on the Note 9
  when available under the shared phone lock. Never claim source presence as device proof.
