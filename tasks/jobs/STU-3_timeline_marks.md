# STU-3: Timeline bookmarks and regions

**Milestone:** M1  ·  **State:** 🟡 built, rescued, not on `joy-creator`  ·  **Owner:** open (whoever lands it should be strong on the Studio editor)  ·  **Checked:** 2026-10-27

## Why it matters
The owner asked for it on 10-23 (tapping and holding the playhead timestamp did nothing). It gives named
bookmarks, In/Out regions, looping a region and exporting just a region: everyday editing comfort.

## Done when
- [ ] On `joy-creator`, built, and phone-proven on the Note 9: tap the timestamp drops a bookmark; hold
      opens the marks popup; named marks; In/Out regions; loop a region; export a region (video and audio-only);
      undo and redo; reopen keeps everything.
- [ ] The owner has tried it and is happy with the feel.
- [ ] STATE row, with help mapping; Joybot's `add_marker` can then be switched on (it was held until marks join undo).

## Where it stands
Built by a Codex session on 10-23/24 in the main folder and **never committed**. Saved on 10-27 in
`rescue/main-folder-20261027` (`94a5f7e69`). Codex's own notes (in that branch's `tasks/todo.md` and
`tasks/road_map.md`) say:
- 68 focused checks pass (migration, save-failure protection, saved undo, loop boundaries, export planning);
- built into an APK on 10-24 and the new code confirmed inside it;
- **on the Note 9: tap drops a bookmark and hold opens the menu (proven)**; a popup-height layout bug was being fixed when the session stopped (10-24 13:02);
- not yet proven: playback loop timing, exported region files, the rest of the popup.
- Owner-approved design: `tasks/SPEC_TIMELINE_MARKS_20261023.md` (in the rescue branch).

## What is left
- [ ] Re-apply the rescue branch's markers changes onto current `joy-creator`: new `ui/faditor/markers/**`,
      `strings_timeline_marks*.xml`, `tools/jvm-harness/markers/**`, and the edits to `FaditorEditorActivity`,
      `ExportManager`, `ExportService`, `EditorTimelineView`, `AudioLayerSync`. Diff each against the rescue base
      `fb8042841` to see exactly what Codex changed.
- [ ] Land the spec file too.
- [ ] Finish the popup-height fix; run `tools/jvm-harness/markers/run.ps1`.
- [ ] Build, then the phone checks above; then ask the owner to try it.
- [ ] STATE row; tell the Joybot lead `add_marker` can go live.

## Files and traps
- Big edits to the same editor file as STU-2: do one, push, then the other.
- Codex also completed a half-done `WaveformOverlayView` refactor to get the build through; check that change is in the rescue branch.
