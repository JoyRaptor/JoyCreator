# STU-F7 mirror frames, Note 9, 11-28: the return leg is frame-exact
Files pulled from the Note 9 (build 9e04c1a40 export): the source clip (`stu-b3-talk.mp4`, 540 frames, each prints its frame number), the phone's baked reversed copy (`cache/reversed/rev-v2-0-18021-8160.mp4`) and the export `Untitled 7 4.mp4` (1182 frames). Checked with `f7-mirror-check.py` (320x240 grey).
| What | Result |
|---|---|
| Baked reversed copy against the source | frame 0 is source 539 with error 0.03; every checked frame matches (0.01 to 0.03). The phone's bake is exact from its first frame. |
| Export frames 540 to 553 (the return leg's start) | each is the expected source frame, error 0.02 to 0.04; frames 560, 570, 600 also exact. |
| Export frames 525 to 539 (end of the forward pass) against the plain source | error rises from 0.3 to 6: that is the Radial transition blend on the cut (0.6 s), not a fault. |
Reading: the earlier "first 7 frames off" came from comparing the leg with those blended forward frames. Neither the encoder's warm-up nor the export's join is at fault; no H.264 vs HEVC test needed. Not repeated on the newest build (ba9d74aa7); the F7 preview re-run is still owed.
