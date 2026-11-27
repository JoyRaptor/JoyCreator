# SelectBench on the Note 9 (11-27, hover-1 lane, agent run)
Build `703cdc2a1` (arm64 debug, cloud), Note 9, started through the debug JoyBrushBenchActivity with `--ez jb_no_shots true` (the screenshot protocol off; it needs the host ACK files, which a non-rooted phone only gets through `run-as`). Results read from logcat.

**All 23 steps pass, 0 failed.** The ones asked about: `cutUndoRedo` PASS (Cut is one undo step, the line is gone, Undo brings it back and reselects it, Redo removes it again); `doneEnds` PASS (Done ends the selection, no undo step). Also PASS: copyPaste, group, lock, tipDots, nudge, fingerPansWithBrush. See `steps.md`.

Not covered: the per-step screenshots (`jb_no_shots`), a physical S Pen (the bench uses synthetic pen events), and the owner's own look.
