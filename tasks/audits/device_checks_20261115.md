# Device checks, 11-15 (launch list: STU-B1, BOT-A1, BOT-A12, SHELL-10, SHELL-12, SHELL-28)

Run by a side session of the 11-14 orchestrator. **Device: the test Fold (Z Fold 4, cover screen 904x2316, driven only by adb)**,
because the Note 9 and the tablet were both claimed by other sessions. Build: cloud run 43688996768 = `joy-creator` at 4bc535455,
installed over the 11-14 build with data kept, no uninstall. The app was at its first run (a fresh device), so the first run was
also walked through: folder step, "Make my Joy Creator folder", system picker, Allow, "Start creating" all worked.
Screenshots: `tasks/screens/device-checks-20261115/`. Test files I made on the Fold (safe to delete): `Download/JC *.mp4|m4a|jpg`,
the Joy Creator folder's `JC big video.mp4`, `JC test photo.jpg`, projects "Untitled", "Untitled 2", "Untitled audio".

## Result table

| # | Check | Result | Proof |
|---|---|---|---|
| 1a | Studio: tap/seek exactly on a cut (D7) | ✅ a seek at the cut shows the later clip (preview frame 134 = the later clip's first frame, later clip selected). ❌ one case: see finding F1 | `studio-d7-*.png` |
| 1b | Studio: Text Fit "Fit to width" | ✅ the chip cycles Auto width, Wrap wide, Wrap medium, Wrap narrow, Fit to width; in Fit to width the title grows to fill the screen width | `studio-textfit-*.png` |
| 1c | Studio: audio-only project reopens | 🟡 length is right (card "0:06", editor total 00:06, file keeps the clip and its waveform) but **the clip is not drawn on the timeline** (F3) | `studio-audio-*.png` |
| 1d | Studio: Clean tab status after Revert then Undo | ⬜ not reached: Fix audio applied (one tap, one undo step) but I did not find the Clean tab in the audio drawer on this screen (F4) | `studio-audio-lane-after-collapse.png` |
| 1e | Studio: caption-style chip undo | ⬜ not run: needs captions, which need the on-device speech model download (a dialog offers 40 to 128 MB) | |
| 1f | Studio: old Fix audio file re-timed on open | ⬜ not run: no old-style project exists on a fresh device | |
| 2a | Joybot: "drop a marker here" then one Undo | ⬜ blocked: without an AI key Joybot is "offline helper" and answers with a how-to text (no crash, no marker) | `joybot-marker-*.png` |
| 2b | Joybot: a cut through Joybot | ⬜ blocked: same | |
| 2c | Joybot: free-model fallback, add media, relink, ducking | ⬜ blocked: the settings dialog asks for an API key first (F5) | `joybot-settings.png` |
| 3a | SHELL-10 watermark in a saved video, photo, screen recording | ⬜ not run on the phone. Code reads "Captured by Joy Creator" in all three writers (`WatermarkManager`, `DigitalForensicsEventRecorder`); the watermark is OFF by default, so a real proof needs it switched on plus a camera recording | |
| 3b | SHELL-12 photo item's Library sheet | ✅ no crash; sheet shows Open / Share / Move to Trash. ❌ tapping the card opens a viewer with a leftover strip "EVIDENCE • conf 0.00 • Unknown time" (F6) | `shell12-photo-*.png` |
| 3c | SHELL-12 missing-file item | ✅ no crash. The sheet opens (still showing the old size), Open shows a blank black player with no message (F7); the card was gone from the Library after returning | `shell12-missing-*.png` |
| 3d | SHELL-28 Move then Undo on a big (44 MB) file | ✅ Joybot's "Copy / Move / Leave" card: Move put the file in the folder, took it out of Downloads, the clip kept playing from `joy:` link; Studio Undo took the clip out, Redo brought it back with its picture. The original is not restored to Downloads (it is the Move, so by design) | `shell28-*.png` |

## Findings (nothing here was a one-line fix; none fixed)

- **F1 Studio, tap on a cut.** Split a clip so the playhead sits exactly on the cut, then tap the trim handle at the cut (the cyan
  handle that sits there): the clock under the preview reads double the time (00:08 for a cut at 4.438, 00:15 for 7.752) and the
  preview drops to the source's first frame while the pink time pill still says the right time. Reproduced twice. A swipe afterwards
  recovers. Seeks by swipe, at and just after the cut, are right. Card: STU-4.
- **F3 Studio, audio lane not visible.** On the Fold's tall narrow screen the audio lane sits below the visible timeline pane while an
  empty top lane is open, and a vertical swipe did not scroll to it; collapsing the top lane showed the clip and its waveform. In an
  audio-only project the two lanes show empty even after reopen. Needs a look on the Note 9 / tablet to tell layout from drawing.
  Owned by TAB-1 / Studio lane.
- **F4 Studio, Clean tab.** Not found on this screen; the audio drawer showed Level / Pan / Fades and six toggles (flags, scissors,
  speaker, lock, FX = A/B bypass, auto-key), one of which I toggled by accident (hence a few extra undo steps in the test project).
  Re-check by hand on the Note 9.
- **F5 Joybot without a key.** The first screen says "Connect an AI (there's a free one) and I can take on anything you can
  describe", but "Connect an AI" leads to a dialog that needs a pasted API key (model pre-filled `openrouter/free`). A tester with no
  key cannot get any AI answer. **Owner decision:** either a keyless free path, or reword to say a free key is needed. The offline
  helper's buttons (Transcribe, Add captions, Make a title card) and the large-file card work without a key.
- **F6 Photo viewer** shows "EVIDENCE • conf 0.00 • Unknown time" under any photo (a FadCam Smart Detection strip); a tester meets
  this on the first photo. Hide it for ordinary photos.
- **F7 Missing file Open** gives a blank black player (00:00 / 00:00) with no "this file is missing" message, and a notification
  permission prompt appears over it. The sheet's size line is stale until the list refreshes.
- **Move dedupe (not a bug, a note):** two identical big files; Move of the second pointed its clip at the first one's copy and
  deleted the original. Fine for identical files.
- **Tooling note:** `uiautomator dump` fails on the lobby and Library screens ("could not get idle state"), so scripts must fall back
  to coordinates there.

Not checked: any AI-dependent Joybot path, captions, Clean tab, watermark, real S Pen / touch feel (adb taps only).
