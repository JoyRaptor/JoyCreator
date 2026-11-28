# STU-F6 re-run, Note 9, 11-28 (build 0dd90399b, contains 6651652ca): PASS
Set-up: new project "Untitled 10": main clip `h1_main_blue` (6 s, 2000 Hz), two PiPs added with Add → Video overlay (PiP): `h1_pipA_440` (red, 440 Hz) and `h1_pipB_132…` (blue, 1320 Hz), both starting at 0 s and overlapping. Each PiP held on the timeline to open its drawer, then the red speaker (Include audio) tapped: it turns white, the lane header shows a speaker (both lanes did). Export, High, Original.
Result (decoded on the PC, one-second windows, level of each tone):
| seconds | 440 Hz (PiP A) | 1320 Hz (PiP B) | 2000 Hz (main) |
|---|---|---|---|
| 0 | 3892 | 3893 | 3870 |
| 1 to 4 | 4071 to 4078 | 4056 to 4078 | 4050 to 4057 |
- **Both PiPs sound together with the main clip, at the same level** (before the fix: one after the other).
- **Audio length 5.995 s against video 6.000 s** (before: audio 15.96 s against a 6 s picture).
Not covered: ear listening (an agent cannot hear), a PiP with different start times, three or more PiPs.
