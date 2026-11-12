# SAFE-1: no way to lose work (the work-loss hunt)

**Milestone:** M1  ·  **State:** 🟡  ·  **Owner:** open (started by Claude Fable 5.2, 2026-11-10)  ·  **Checked:** 2026-11-10 against `origin/joy-creator` `d8617809d`

## Why it matters (for the user)
"Nothing breaks" and "uninstalling never loses work" are the app's first promises. This job hunts every way
a save, a delete, a move or a restart can lose something a person made, in every wing, and closes it.

## Done when
- [ ] Every finding in the [report](../audits/work_loss_20261110.md) is fixed, or parked with the owner's yes.
- [ ] Each fix has a test that fails on the old code, and the ones a person would see are tried on a phone.
- [ ] STATE.md §7 rows say what is proven.

## Where it stands
First pass 2026-11-10: three read-only agents listed every place each wing writes, deletes, moves or loads
user work; the lead (Fable) read the save paths end to end and confirmed each finding in the current code.
22 findings; 17 fixed and tested off the phone, see the report for each one's state and proof.

The tests that carry the proof (all run 2026-11-10):

| Test | What it proves | Result |
|---|---|---|
| `bash tools/jvm-harness/run-workloss.sh` | The real `ProjectStorage`: two holders of one project, racing saves, audio-only projects, a cut-off project file | 27 pass. The same checks on the code from before: 4 fail (audio-only one save behind; 35 of 150 racing saves wrong; 38 loads found no readable project) |
| `bash tools/jvm-harness/run-diskstamp.sh` | Noticing a file someone else wrote; keeping what a save replaces | 20 pass |
| `bash tools/jvm-harness/run-sidecars.sh` | Timeline marks ride in the folder copy and come back | 16 pass |
| `bash tools/joyfolder-tests/run.sh` | Folder core: streamed atomic write, repair in subfolders, Trash log rebuilt | 25 pass (21 old, 4 new) |
| `:joybrush-android:testDebugUnitTest` | `DrawingListRepairTest` (8), `WorkingSaveRestoreBindingTest` (6, 3 new), and the whole existing suite | all pass |

## What is left
- [ ] Phone checks (Note 9), one sitting: see "Phone checks owed" in the report.
- [ ] The findings marked NOT STARTED in the report (recording write errors, the folder copy catching up at
      app start, slides after a restore, avatars into the folder, VisLab after a change of folder).
- [ ] A way for a person to bring back a kept copy (`backups/replaced-*.json`) without an agent.
- [ ] Second pass: JoyBrush engine saves (wet paint owed at save time, lost-tile cause), VisLab and SpriteLab
      end to end, export and Capture.

## Specs, handoffs and evidence
- **Handoff (read this first to continue): [`tasks/HANDOFF_20261111_work_loss_and_review.md`](../HANDOFF_20261111_work_loss_and_review.md)**
- Code review of export, the timeline clock and the editor: [`tasks/audits/code_review_20261111.md`](../audits/code_review_20261111.md)
- Report and findings table: [`tasks/audits/work_loss_20261110.md`](../audits/work_loss_20261110.md)
- Related: [FILE-1](FILE-1_folder_leftovers.md) (storage roots outside the folder), JoyBrush `JB-LIVE-SAVE`
  (`tasks/joybrush/reviews/LIVE_SAVE_HANDOFF_20261104.md`).

## Files and traps
- `ProjectStorage` now has one lock per project and remembers what `project.json` looked like when it last
  read or wrote it. Load through the storage you will save with, or `changedOutside` cannot help you.
- An editor off screen that finds the file changed does **not** save; it reloads when it comes back. A writer
  that must change a project behind an editor goes through `JoybotStorage` (or signals `AIChatState`).
- `tools/jvm-harness/stubs-clipwarp/.../StudioMirror.java` is a stand-in: a new static method that
  `ProjectStorage` calls must be added there too, or four harness suites stop compiling.
- A test that uses `ProjectStorage.saveAsync` must end with `System.exit`: the writer thread keeps the JVM open.
- `DrawingLibrary` puts any `.joybrush` file in its folder back on the list. Never leave a stray one there.
