# STU-F6: two PiPs with sound, Note 9, 2026-11-27 (partly done)

**CORRECTION 11-28 (read against the code, phone-free):** a PiP is silent BY DESIGN (SPEC_PIP_AUDIO, `Clip.overlayAudioEnabled` defaults false; PiPs are added with `audioMuted=true`, "pixels only", M-EXPORT-1). Sound is an opt-in: select the PiP, open its object menu, tap **Include audio** (`addPipAudioAction`, one undo step). So the silent export below is the intended default, NOT a fault, and my "FAIL" call below is withdrawn. What remains a real, smaller finding: the Volume panel's mute button and the "100%" readout do not reflect that opt-in (the panel read 100% on a silent PiP). The true F6 test is: Include audio on BOTH PiPs, export, decode for 440 Hz and 1320 Hz (still owed, needs the phone).


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

## 11-28 re-run with Include audio ON for both PiPs (hover-1, Note 9): export STILL has no PiP sound
How: hold each PiP on the timeline (opens its object drawer), tap the red speaker icon; the lane header then shows a speaker and the saved project has `overlayAudioEnabled: true` on both PiPs (`audioMuted: true` stays, as designed). Export (video, defaults): `Untitled audio 5 2.mp4`, 5.97 s audio. Export trace lists three AUDIO_SOURCEs (main + both PiPs, 44.1 kHz mono AAC). Decoded with `tone_check.py`: only the main clip's 2000 Hz is present; 440 Hz and 1320 Hz are at about -105 dB. **FAIL: an opted-in PiP's sound does not reach the export** (the code path `buildOverlayAudioSequence` exists; cause not found). Possibly the same family as the ping-pong export not using the new rule: a stale build or a path that skips it; needs the export owner. Preview not measured.

## 11-28 re-run 2 on build `9e04c1a40`: blue PiP sound reaches the export; the two PiPs are laid end to end, not together (FAULT)
Both PiPs have Include audio on. In each PiP's drawer the sound icon now un-mutes (red speaker turns white speaker, one Undo step, lane header shows a speaker; `rerun2_red_pip_drawer_speaker_unmuted.png`), and a Volume row (100%) appears in the drawer. For this run the red PiP (A, 440 Hz) was un-muted first and the blue PiP (B, 1320 Hz) second; both start at 0 s, each 8 s long, over a 6 s main clip (2000 Hz). Export (`Untitled audio 5 3.mp4`), tone level per second (FFT amplitude):
| seconds | 440 Hz (A) | 1320 Hz (B) | 2000 Hz (main) |
|---|---|---|---|
| 0 to 5 | 0 | about 2030 | about 2028 |
| 6 to 7 | 0 | about 2033 | 0 |
| 8 to 14 | about 2035 | 0 | 0 |
- **Progress:** a PiP's sound now reaches the export (before the fix: nothing).
- **FAULT 1: the two PiPs' sound is placed one after the other** (B for 0 to 8 s, then A for 8 to 16 s), not mixed together from 0 s as the picture-in-picture lanes are. Two simultaneous PiPs each with sound can never be heard at once.
- **FAULT 2: the exported audio runs 15.96 s while the video is 6.0 s** (ffprobe), so the file's audio outlasts its picture by about 10 s.
- The main clip's 2000 Hz stays at its normal level (about 2028, the same as each PiP's tone), so adjusting a PiP did not change the main clip's volume in this run (no volume slider was moved).
Row STU-F6: **still FAIL** (sequential placement, audio longer than video). Likely place to look: `buildOverlayAudioSequence` (lays each PiP after a running cursor in one sequence); it needs one parallel sequence per overlapping PiP lane (as the sound lanes have) and a cap at the project length.
