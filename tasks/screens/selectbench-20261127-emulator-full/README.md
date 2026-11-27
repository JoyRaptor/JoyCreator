# SelectBench on the cloud emulator, all steps (11-27, hover-1 lane)
Run 46069040662, phone profile, `select_bench` script with `jb_no_shots` (no screenshot protocol). **All 23 steps pass, 0 failed, in 112 s of script time**, including cutUndoRedo (Cut one step, Undo reselects, Redo removes again) and doneEnds. Same result as the Note 9 run (`../selectbench-20261127-note9/`).
Faults found by porting it, all fixed: the whole-line eraser hid the Select tool from the bench; the draw step and the cutUndoRedo step judged after fixed delays that were too short on a slow device (now they wait up to 10 s / 8 s).
Not covered: real S Pen (synthetic pen events), per-step screenshots, real GPU speed.
