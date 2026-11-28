# ANI-1 puppet proof on the cloud emulator, 11-28 (phone profile, API 29, software GPU)
Script `tools/cloud/emulator/scripts/puppet_proof.sh`, emulator run 46149543153, picture project seeded by the harness (a gradient disc).
| Step | Result |
|---|---|
| Hold the picture, open the drawer's person-icon tab | PASS: Puppet tab (Grab, Pin, Stiff, Dangle, Free, Bone; Selected / Character / Recording) `04_puppet_open.png` |
| Place pins | PASS: Pin tool then a tap on the picture places "Hip", undo count 1 to 2; a second tap lower places another pin (`05_pin1.png`, `06_pin2.png`) |
| Drag a pin with the drawer closed (a live take) | PASS for the gesture: the lower pin moved from the bottom to the lower right, one undo step (count 4 to 5) (`10_after_drag.png`) |
| Key a pin (diamond in the drawer header) | NOT SEEN: in this run the diamond tap landed on the Chroma key tab (a dump needle matched "Key"); the first script revision hit it by text, the later one by position and the key mark on the tape was not photographed. Redo with the pin selected. |
| Posed picture, replay, tape drags, bone tap | NOT PROVEN: the test picture is a plain disc, so a swing or a bend cannot be seen; the play tap did not start playback in the run (time stayed 00:00). Needs a rigged picture (a figure with a tail) on the Note 9. |
