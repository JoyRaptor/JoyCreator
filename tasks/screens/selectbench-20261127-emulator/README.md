# SelectBench on the cloud emulator (11-27, hover-1 lane)
Run 46066524364, phone profile, script `select_bench` (bench started through the debug JoyBrushBenchActivity; `selectbench_watch.py` answers each screenshot request).

**15 steps pass**: setup, draw (5 lines), chooseSelect, tapPick, cycle, tapEmptyEnds, lasso, dragMove, undoMove (selection kept, D20), rotate (90 degrees), pinchAnywhere (x1.5), delete, duplicate, flipH, swapList. See `steps.md`.
**It stops at `screenshotAck` (a FAIL):** after swapList the bench asked for a held-state screenshot and the request row never reached the log within the bench's 20 s, so it gave up. Cut / Undo / Redo / Done (`copyPaste`, `cutUndoRedo`, `doneEnds`), group, lock, tip dots, nudge and the finger-pan step were therefore NOT reached. Cause not proven: the emulator's software GPU is slow to present the held state with the swap drawer open; on the Note 9 this step passes.

Two real faults found on the way (both fixed, landed 11-27): the whole-line eraser fronts the canvas tool slot so the bench found "no Select tool" (`StrokeEraserTool.behind`), and the draw step judged 600 ms after the last stroke (now waits up to 10 s for all five lines).
