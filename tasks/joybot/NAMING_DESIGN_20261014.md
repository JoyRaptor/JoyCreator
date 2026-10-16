# Naming anything on the timeline — design walkthrough (2026-10-14)

**Asked by:** JoyRaptor ("rename image 01 to Cat 1 and its duplicate Cat 2, then tell Joybot to move Cat 2").
**Status:** design for the owner's three answers below; build in a Studio-editor session (touches the timeline
renderer, the object drawer and the project format).

## What exists today (verified in code)

| Item | Name field? | What the timeline bar shows |
|---|---|---|
| Video / picture clip (main track) | `Clip.displayName` — **but it is not free to reuse** (see trap 1) | nothing (the strip shows transcript words) |
| Video layer (PiP) | same `Clip` class | nothing |
| Image layer | none | "IMG" |
| Text layer | none (its text is its label) | the text |
| Sprite layer | none (the sheet has a name) | "✦" / "✦ 5" |
| Visualizer | none | "VIZ" |
| Adjustment layer | `AdjustmentLayer.name` (saved) | name · effect count |
| Audio clip | `AudioClip.label` (saved, default "Audio") | the label |

Drawer header (double-tap an item): coloured dot + the KIND ("Image"), `ObjectDrawer.titleView`. No rename UI for
items anywhere (layer-track rename was removed on purpose earlier).

## Traps found

1. **`Clip.displayName` already means two other things.** The editor stores a clip's *original file name* there
   so a missing file can be found again (relink), and the automatic black filler is recognised by the name
   "Blank (auto)". Writing a user's name into it would break relinking and the filler housekeeping.
   → Every item gets a NEW field, `name`, separate from file names. (Joybot's rename tool already refuses clips
   for this reason; the older `rename_clip` tool still writes `displayName` and should move to `name`.)
2. **A name is not a file name.** The same file can be on the timeline twice with different names ("Cat 1",
   "Cat 2"). Renaming the FILE (asset browser) is a different action, on disk, and must stay visibly separate.
3. **Undo.** A rename is one undo step.

## Proposed behaviour

- **Where you rename:** double-tap the item → in the drawer header, tap the name ("Image" today) → it becomes a
  text box with the current name selected → type → done. Long-press menu gets "Rename…" too. Hover label on it.
- **What shows:** the drawer header shows `● Cat 1 · Image` (name, then the kind faint). The timeline bar shows
  the name instead of IMG / VIZ / ✦; text layers show the name if set, else their text. Main-track clips get a
  small name chip at the strip's top-left (transcript words stay below it).
- **Empty name** = back to the automatic label.
- **Joybot:** sees names in his project context, and tools accept a name or an id ("move Cat 2 to 0:12"). If two
  items share a name he asks which ("the Cat 2 at 0:04 or at 1:10?").
- **Missing-file messages** say both: "Cat 2 (IMG_0042.jpg) is missing".

## Edge cases and a proposed answer for each

| Case | Proposal |
|---|---|
| Duplicate a named item | **RULED (owner, 10-14): no automatic renaming — the copy keeps the same name; the person renames it.** ("Auto renaming can get crazy with numbers.") |
| Split a named clip | **RULED: both halves keep the same name** (letters "get messy with multiple cuts"). Joybot tells them apart by time. |
| Two items with the same name | **RULED: allowed, no warning; Joybot asks which ("the Cat 1 at 0:04 or at 1:10?") when you refer to it by name.** |
| Very long names | full in the drawer, cut with "…" on the bar |
| Copy/paste between projects | the name travels with the item |
| Sprite layers | default name = the sheet's name ("Dino") instead of ✦ |
| Audio clips | default shows the file's name instead of "Audio" |
| Undo / redo | one step per rename |
| Old projects | no names → automatic labels exactly as today (nothing to migrate) |

## Work list (for the session that builds it)

1. Model + storage: `name` on Clip, TextOverlayItem, SpriteOverlayItem, WaveformOverlayInstance (Adjustment and
   Audio reuse theirs); ProjectStorage write + read; deep-copy keeps it; duplicate/split rules above.
2. UI: drawer header rename (ObjectDrawer title), long-press "Rename…", timeline labels (`LayerRowRenderer.labelFor`,
   a chip on the main strip), hover labels.
3. Joybot: context lists names; `rename_item` writes `name` for every kind; tools resolve names; `rename_clip`
   moves off `displayName`.
4. Tests: JVM round-trip of `name` for each kind; duplicate/split naming rules.
