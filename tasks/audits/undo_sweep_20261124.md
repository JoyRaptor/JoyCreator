# Undo sweep: one press, one step (2026-11-24)

Owner rule: one press is one undo step, and a batch operation collapses into a single step.
This sweep covered Studio, VisLab, TextLab and SpriteLab, looking for actions that change the
work but record no step, or that take several presses to undo.

## How the sweep was done

- **Studio.** A script listed every method that saves the project (`scheduleAutoSave`,
  `saveProjectNow`) or tells its host something changed (`onChanged`), and that has no undo
  call. That gave 88 methods in `FaditorEditorActivity` plus a few in the drawers.
  - Each one was traced by hand or by a read-only helper.
  - Each was sorted as housekeeping, recorded elsewhere (named), a real gap, or several presses.
- **VisLab, TextLab and SpriteLab.** Each room's undo was mapped, and every call that changes
  its work was checked against its push helper.

## Each room's undo

| Room | Mechanism | Granularity |
|---|---|---|
| Studio | `UndoManager.recordAction(EditAction)`. Drawers bracket with `beginEdit`/`endEdit` or `Host.recordUndo`. AI steps are snapshots. | action |
| VisLab | `VisLabSession.remember`, reached through `beginEdit(label)` and `roomWillEdit`. A step's group collapses a Joybot request. | snapshot of the touched looks |
| TextLab | `TlUndo.before`, reached through `beginChange`. Typing folds into one step per run. | whole look and its words |
| SpriteLab | `noteChange()`, reached through `markDirty()`. Edits inside a time burst (600 ms, wider during a drag) share a step. | serialised sheet |

## Batch 1: fixed (commit on `claude/undo-sweep`, landed when green)

| Room | Action | Before | Now |
|---|---|---|---|
| Studio | Transition duration, type, direction, GL effect | no step | one step each. Type followed by direction (or GL effect) is ONE step, picked or cancelled. |
| Studio | Delete a transcript version | no step (and it also dropped its caption tracks) | one step. The version and its tracks come back. |
| Studio | Import timestamped text, plus the track it wires | no step | one step for both |
| Studio | Rename or switch a transcript version | no step | one step |
| Studio | Caption track: add (3 places), rename, delete, show/hide (eye), "Hide this track" | no step | one step each |
| Studio | Caption pinch-resize | no step (the size slider had one) | one step per pinch |
| Studio | Centre words (word scrub) | no step (Nudge had one) | one step |
| Studio | Visualiser layers: add, duplicate, delete, move left or right | no step | one step each (`vizUndoable`) |
| Studio | Volume tool drag, the volume drawer's bar, Mute | no step | one step per drag or tap |
| Studio | Puppet drawer: mute pin, auto pull, stretchy, flip elbow, joint limits | no step | one step each. View toggles (show pins, bones, mesh, reach ring) and tool settings (record on touch, snap) are not edits. |
| Studio | Puppet helper depth scrub | no step | one step per drag |
| SpriteLab | Background key: pick or clear | no step and no autosave | one step, saved |
| SpriteLab | Relink a picture on an existing sheet | rode on the next edit's step | its own step |
| SpriteLab | Number pill dragged within 4 s of another edit | joined that edit (two in one press) | its own step |
| SpriteLab | Drag, twist or pinch on the preview with a pause over 0.6 s | split into two steps | one step from touch to lift |
| SpriteLab | "Change" that changed nothing (Detect that found no grid, Put back) | an empty step (a press that does nothing) | no step |
| VisLab | Knob moved by keyboard or TalkBack | no step | one step per press |
| TextLab | Knob moved by keyboard or TalkBack | no step | one step per press |
| TextLab | Slider tapped without moving, colour dialog closed unchanged | an empty step | no step |

**Tests**
- `tools/jvm-harness/UndoStepsTest.java` (16 checks, real `Clip` and `AudioClip`). It proves that
  `CaptionTrackState` puts deleted versions and tracks back, on the SAME binding objects that
  other undo steps hold. It failed 3 checks when the field restore was removed.
- `TextLabAuditTest`, 3 new checks: a change of nothing is not a step; a typing run is never dropped.

**Ratchet**
- `tools/check_undo_steps.py`, run as `run-undo-lint.sh` in the studio suite. It counts methods in
  the four rooms that change the work with no undo call in sight. There is a per-file baseline;
  the 11-24 count is 77 methods in 8 files, and a count may only fall.
- It failed on a planted method that called `host.edited()` with no step.
- It is a heuristic: many counted methods are fine (housekeeping, or a caller records). Its job is
  to stop NEW gaps.

**Not a gap after all**
- A helper reported that the object drawer's Hide, Lock and Pass-through had no undo step. They
  already had one: `ObjectDrawer` records every header toggle through `setUndoSink`.
- The step I added for them in batch 1 would have cost two presses per tap. It was taken out
  before landing (lesson in `tasks/lessons.md`).
- The same sink already covers the sprite, visualiser, audio and PiP header toggles.

## Batch 2: fixed

| Where | Action | Before | Now |
|---|---|---|---|
| Studio text drawer | Keyframe diamond on stroke or glow: drop or update a key | no step (delete-key had one) | one step (`recordOverlayMenuUndo`) |
| Studio text drawer | Motion start here, end here, whole span, and their typed times | no step (the timeline carrots had one) | one step each |
| Studio | Add a transition with the playhead mid-clip | the split had no step, so one Undo left the clip cut in two | the split and the transition are ONE step |
| Studio transcript | Drag a paragraph to a new place | no step | one step. It restores the project by value through the new `UndoManager.recordSnapshotStep`, which is not marked as Joybot's. A refused or failed move now also puts the chapter titles and speakers back (before, they moved on their own). |
| Studio | Import a visualiser style | no step | one step |
| Studio | Caption keyframe stopwatch, first arm (lays the first key, switches captions on) | no step | one step |
| Studio | Opacity tool drag with the drawer shut | no step (with it open, the drawer's session records one) | one step |
| Studio | Relink a missing clip, plus the siblings it relinks | the siblings were not in the step | one Undo puts every clip back |

**Batch 2 test:** `UndoManagerTest`, 7 new checks: the snapshot step is one press each way and is
not marked as Joybot's. The ratchet baseline fell from 77 to 73.

## Batch 3: fixed

| Where | Action | Before | Now |
|---|---|---|---|
| Studio text drawer, no words selected | Whole-box colour, font, align, wrap and Fit, case, outline, glow, shadow, plate | no step: the drawer's step held the styled words only | in the drawer visit's ONE style step, through the new `undo/TextBoxLook` |
| Studio captions, Style and Fit tabs | Every `tweakCaptionStyle` control (20 callers): Fit modes, Truncate, floor, Slide, colours, words per line and more | only the first tweak had a step (the switch onto the track's draft style); later tweaks edited the draft with no step, so Undo jumped back past all of them | one step per press. Tweaks within 600 ms while still the top step are one gesture (a slider drag) and fold in. |
| Studio captions, Fit tab | Grow, Align, apply to all tracks | no step | one step each (`CaptionTrackState`) |

**Batch 3 test:** `UndoStepsTest`, 4 new checks (`TextBoxLook` round trip); 20 pass.

**Trade-off to know:** two separate caption chips tapped within 600 ms of each other fold into
one step. That is the same window SpriteLab uses.

## Batch 4: the last two gaps, and the sweep closed

| Where | Action | Before | Now |
|---|---|---|---|
| Studio | Add an audio file from the picker | no step (a voiceover had one) | one step (`AddAudioClipAction`, the voiceover's door) |
| Studio | Tap a silence to cut it, or tap a cut one to put it back | no step: tapping again was the only way back | one step each way |

**Every method the ratchet counts has been read (11-24).** Each of the 73 is one of these:
- housekeeping: migration, repair, lifecycle, export settings;
- recorded by its caller or container: drawer undo sinks, `beginEdit`/`endEdit` brackets, gesture
  commits, the transform hosts;
- the undo or redo body itself;
- dead code: `showWordEditDialog`, `applyWordAbsStart`, the decoration controls;
- a file or tool setting rather than an edit: asset rename on disk, the folder history, Puppet lock
  and record-armed.

The armed-slider question is settled in the code: the outline and glow sliders write the base
value, which is in `TextBoxLook`; only the diamond writes a key, and it records its own step.

## Still owed (owner or design calls, or a phone)

| Where | Action | Why it is not fixed |
|---|---|---|
| Studio | Rename the project | Owner call: a project's name is closer to a file name than to an edit of the work. |
| SpriteLab | Bake into a new sheet | Design call: SpriteLab's undo holds one sheet, and a bake adds a sheet to the project. It needs a project-level step, or a Put back chip like the one removal has. |

**Noticed in passing, not an undo gap**
- Caption pinch-resize multiplies the size at touch-down by `getScaleFactor()`. That factor is
  relative to the previous event, because `onScale` returns true. The pinch therefore barely
  changes the size. Worth a phone check before any fix.
- Dead code with no caller that changes words with no step: `showWordEditDialog`,
  `applyWordAbsStart`, and the decoration controls. Wrap them or delete them before anyone wires
  them up.

**Phone check owed:** every batch-1 row. Press each control once, then Undo once and confirm it is
fully back; then Redo once. This is agent work on the Note 9 when it is free.
