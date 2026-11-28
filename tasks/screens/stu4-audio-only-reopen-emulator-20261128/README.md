# STU-4: an audio-only project closes, saves and reopens (cloud emulator, 11-28, phone profile, API 29)
Script `tools/cloud/emulator/scripts/studio_audio_sweep.sh` (emulator run 46170309739): blank audio project, Add → Audio → a generated 6 s WAV (2 s tone, 2 s silence, 2 s tone), answer the waveform question, Close & Save, make the Joy Creator folder, Library, open the card.
| Check | Result |
|---|---|
| The sound is added and the total time reads 00:06 | PASS `04_audio_added.png` (the toolbar shows Extract audio, Sound, Clean, Fix audio, Beats when the clip is selected) |
| Close & Save writes the project and the Library lists it with its length | PASS: "Untitled audio, PROJECT, 0:06" `10_library.png` |
| Reopening from the Library shows the project with its length | PASS: title, total time 00:06, undo counter 1 `11_reopened.png` |
| The clip itself drawn on its row after reopen | NOT SEEN: the rows are dark in the emulator's picture and a tap on the row did not select it (`13_clean_open.png`, no Clean tool). The length survives; whether the bar draws needs the Note 9 (or a selection by the clip's own control). |
| Clean tab, Revert then Undo status line | NOT RUN (needs the clip selected) |
Things learned for scripts: the back arrow asks "Close Editor?" and needs Close & Save; the waveform dialog and the Library card have no text nodes (tap by position); closing before a folder is chosen lands on the folder step (make `Documents/JoyCreatorTest` first).
