# STU-2: After an undo, the preview keeps showing a removed slide

**Milestone:** M1  ·  **State:** 🟡 fix written 10-14, never landed  ·  **Owner:** open  ·  **Checked:** 2026-10-27

## Why it matters
Ask Joybot for a title slide, undo it, and the slide stays on screen. It looks like undo failed.

## Done when
- [ ] The fix is on `joy-creator` and phone-proven: Joybot makes a cinematic title slide, open the editor,
      tap Undo, and the preview shows the video, not the slide. Redo brings the slide back.
- [ ] STATE row updated.

## Where it stands
Fix `f46e45c67` ("undo/redo puts the preview back on the right kind of clip") lives only on branch
`claude/dazzling-satoshi-75a363`. It was type-checked, never built, never phone-tested. It adds
`refreshPreviewSurfaceAfterUndoRedo()` at the end of every undo and redo in `FaditorEditorActivity`; the
method is **not** on `joy-creator` (checked 10-27).

## What is left
- [ ] Re-apply the change on current `joy-creator` (the editor has moved a lot since 10-14; cherry-pick will
      likely conflict, so re-apply by hand: about 70 lines in one place).
- [ ] Make sure ordinary undos (volume, text colour) do not move the player (the old "undo snaps to the start" bug).
- [ ] Build, phone test as above, STATE row.

## Files and traps
- `FaditorEditorActivity.java` is about 46k lines and touched by many lanes: small, unique anchors only;
  STU-3 also edits it heavily, so do these one after the other.
