# STU-F6: two PiPs with sound, Note 9, 2026-11-27 (partly done)

Set-up: blank project, main clip 6 s (2000 Hz tone), PiP A (440 Hz, 8 s) and PiP B (1320 Hz, 8 s) added with Add > Video overlay (PiP), all start at 0.
Checked with ffmpeg + a tone check (`tone_check.py`): the tone of each source is present in its source file.

## Result so far
| What | Result |
|---|---|
| Export (video and audio-only) with PiPs as added | Only the main clip's 2000 Hz is in the file. 440 Hz and 1320 Hz are absent (about -105 dB). **FAIL as a default.** |
| Why | The saved project has `audioMuted: true` on both PiPs the moment they are added. |
| Volume panel on a fresh PiP | Says **100%**, not "Muted" (one tap on the mute button then says "Muted", another says 100% again). The panel disagrees with what export does. **Fault to fix** (state shown vs state saved). |
| Overlay-only project | Export dialog says "0 clip(s)", 00:00 length, nothing is written. Expected (overlays have no length of their own), but no message tells the user. |
| Both PiPs unmuted then exported | **Not finished.** I lost track of which PiP was un-muted after several toggles; the run was stopped by the usage limit. Redo: select a PiP, Volume, tap the mute button until the panel says 100% AND check the saved file shows `audioMuted:false`, for both PiPs, then export and run `tone_check.py`. |
| Preview audio of both PiPs | Not measured (cannot be measured from the PC). Owner hands-on. |

Status for STU-1: F6 stays open (export default drops PiP sound; panel shows a wrong state).
