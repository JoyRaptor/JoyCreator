# Phone results, hover-1 lane, 11-27 (Note 9 over USB, build of joy-creator ae62ac0c1; evidence in tasks/screens/hover1-20261127/)

| # | Check | Result |
|---|---|---|
| R1 | Envelope Clear is one undo step and the row refreshes (fix ce27b5e47) | **PASS.** Audio drawer on "Restore video test": Clear flips the row from "Envelope - 10 pts" to "Flat gain" at once; Undo toasts "Undid: Clear envelope" and the 10 points return (`r1_after_undo.png`). The Undo counter sits at 50 before and after Clear because the history is capped at 50 steps, so the counter cannot show the +1; the toast name is the proof. |
| R2 | Preview timing across the transition cut (the odd frame of 11-24) | **PASS this run.** "Untitled 7", playing from 16.99 s: editor 18.356 shows the picture at 0.433 s (frame 13), 19.201 shows 1.300 s (frame 39), 21.183 shows 3.267 s (frame 98): each is the editor time minus the same 17.92 s offset, nothing jumps or runs ahead, and the button reads Pause while playing (`c2.png`, `c3.png`, `c5.png`). The 11-24 frame (1.000 s at 18.006) did not reproduce, but I did not catch a frame at exactly 18.00 this time, so it is not proven gone; 6 shots across the cut is a sample, a screen recording would settle it. |
| host | Create-tree landings, 91 checks (`--landings`) | PASS in the cloud (run 46008265783); the non-square tile fix (300x200 -> 3:2) is in. |
