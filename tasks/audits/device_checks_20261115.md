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
| 2a | Joybot: "drop a marker here" then one Undo | ⬜ waits on a key (S14), skip until the owner supplies one: without an AI key Joybot is "offline helper" and answers with a how-to text (no crash, no marker) | `joybot-marker-*.png` |
| 2b | Joybot: a cut through Joybot | ⬜ waits on a key (S14), skip until the owner supplies one: same | |
| 2c | Joybot: free-model fallback, add media, relink, ducking | ⬜ waits on a key (S14), skip until the owner supplies one: the settings dialog asks for an API key first (F5) | `joybot-settings.png` |
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

## Update, same day, on the test tablet (Tab S8 Ultra, landscape; same build 4bc535455; supersedes the Fold rows above where they differ)

| # | Check | Result | Proof |
|---|---|---|---|
| 1a | Tap on a cut's trim handle (F1) | ❌ **reproduced on the tablet**: cut at 8.630, tap the handle, clock reads 00:17 (double), preview on source frame 0, pill still 8.630 | `tablet-f1-*.png` |
| 1c | Audio clip not drawn (F3) | ❌ **reproduced on the tablet, so not a Fold-only layout problem**: after Add > Audio the audio lane shows empty; collapsing and expanding the film lane (the small arrow) makes the clip and its waveform appear. A redraw problem on adding audio | `tablet-audio-added.png`, `tablet-audio-after-lane-toggle.png` |
| 1d | Clean tab: status after Revert then Undo | ✅ Fix audio, then the drawer's **Clean** tab (tab strip: Level, A/V Sync, FX, Clean; the Fold's narrow drawer had hidden it) reads "Processed audio in use — original kept."; Revert shows "Offline renders — the original file is never modified." with Reduce noise and Normalize back; **Undo returns the status to "Processed audio in use — original kept."** | `tablet-clean-*.png` |
| 1e | Caption-style chip undo | ⬜ not run: the Captions drawer says "No captioned clip selected"; captions need a spoken clip plus the speech model download (a spoken test file can be made with Windows speech; not done) | `tablet-captions-drawer.png` |
| 3a | SHELL-10 watermark | ✅ **saved video and saved photo** (camera, rear, via the Home recorder; Watermark Style set to "Time with Joy Creator") both read "Captured by Joy Creator - 15/Nov/2026 03:02:44 AM" top-left, no FadCam/Rec logo. ⬜ **screen recording not run**: its consent opens the system "share or record an app" picker listing every app on the tablet; I backed out and the app showed "Screen recording permission denied" with no crash. ⬜ the Menu switch with overlay permission revoked not tried | `shell10-saved-video-frame.png`, `shell10-saved-photo.jpg`, `tablet-watermark-style-set.png` |

Left on the tablet (mine, safe to delete): project "Untitled 2", `Download/TEST JC *`, `Joy Creator/Camera 2026-11-15 03.02.mp4`, `Photo 2026-11-15 03.02.jpg`. **Its Watermark Style was left on "Time with Joy Creator"** (the default is No Watermark): switch it back in Settings > Recording > Watermark if another test needs a clean recording.
Other note: Settings > "AI and Joybot" repeats "Connect an AI (there's a free one)", same wording issue as F5.
Still open: caption-style chip undo, screen-recording watermark, old-style Fix audio re-time; the AI-dependent Joybot checks wait on a key (S14).

## Update 3, same day, on the test Fold (cover screen, adb only; same build 4bc535455)

| # | Check | Result | Proof |
|---|---|---|---|
| 3a | SHELL-10 watermark in a **screen recording** | ✅ Watermark Style "Time with Joy Creator" on; a 20 s screen recording (Screen tab, whole-screen consent, 1088x1920) reads **"Recorded by Joy Creator - 15/Nov/2026 05:24:14 AM"** at the top-left; no FadCam/Rec logo. Note: it overlaps the status bar and the logo, and says "Recorded by" where video and photo say "Captured by" (fine, but not one wording) | `shell10-saved-screenrec-frame.png` |
| 3a | so SHELL-10 is now ✅ for video, photo and screen recording. Not tried: the Menu switch with the overlay permission revoked | | |
| F8 | First Capture visit asks three permissions in a row: camera, microphone, then **photos and videos**. A plain explainer sheet comes first ("Capture needs the camera and microphone ... Nothing leaves your phone"), good, but it does not mention photos and videos. Declining photos still opens the recorder. A tester may refuse the photo one; worth asking only when it is needed | `fold-capture-permission-explainer.png`, `fold-capture-photos-permission-asked.png` |
| F9 | The speech-model download (Transcript > Fast timing, ~40 MB) shows only an animated bar and the words "Downloading speech model (one time)...", with no percentage or size; on a fast Wi-Fi link (864 Mbps link, ping 55 ms) it ran for over 10 minutes without finishing. A tester cannot tell stalled from slow | `fold-*` / see below |
| 1e | Caption-style chip undo | ⬜ still not run: waiting for the model download to finish (see F9) | |

Left on the Fold (mine, safe to delete): projects "Untitled", "Untitled 2", "Untitled 3", "Untitled audio"; `Joy Creator/` test files and `Screen recording 2026-11-15 05.24.mp4`; `Download/JC *`. Its Watermark Style is on "Time with Joy Creator". Camera, microphone permissions granted, photos denied.

Fold released 11-15 for the Studio polish lane's dialog shots, with the speech-model download (F9) still unfinished and watched no further, because their APK install restarts the app. **Caption-style chip undo is the one check still owed**: it needs the model downloaded once (about 40 MB, Transcript > Fast timing) and then Captions on a spoken clip (JC speech video.mp4 is in the Fold's Download folder).

## Update 4, test Fold (build of 18:02 already on it): STU-C5 marks, AN-08, and what did not run

| Check | Result | Proof |
|---|---|---|
| STU-C5 drop a bookmark (tap the chip) | ✅ chip flashes, one undo step | `stuc5-bookmark-dropped.png`, `stuc5-marks-popup.png` |
| STU-C5 finish a region (In then Out) | ✅ "Region saved" (0 to 3.5 s). See F10 for how awkward it is | `stuc5-region-finished.png` |
| STU-C5 loop timing | ✅ Loop on: over 50 s of playback the clock only ever read 0 to 3 s, so it wraps at the region's end | `stuc5-loop-on.png` |
| STU-C5 region export | ✅ one file "Untitled 4 - Region 1.mp4", 3.52 s, 1280x720, video and audio; "Export Complete" screen | `stuc5-region-export-dialog.png`, `stuc5-region-export-frame.png` |
| STU-C5 rename | ✅ Edit bookmark (name, time, colour, Delete); renamed to "Intro" | `stuc5-renamed.png` |
| STU-C5 reopen | ✅ closed and reopened from the Library: 2 bookmarks, 1 region, Loop on all kept | `stuc5-reopened-marks.png` |
| STU-C5 undo | ✅ one press, one step: rename, then Loop, then the region, then bookmark 2, then bookmark 1 | `stuc5-undo-after-rename.png`, `stuc5-undo-all.png` |
| AN-08 timeline grab bar | ✅ on the Fold: log `GRABBAR gesture ended moves=106 baseline=140 dp now=264.8 dp`; the timeline grew | `an08-grabbar-fold.png` |
| 1e caption-style chip undo | ⬜ still not run: the speech model never finished downloading (see F9, F11) | |
| AN-06 puppet basics, AN-09 sprite corner pin and bend, STU-C2 | ⬜ not run: I could not get a sprite sheet into a Studio project on the Fold. A sheet imported through "New sprite sheet" opened the SpriteLab editor, but after Save and Back the Studio sprite panel still listed no sheet (F13). Earlier passes already proved AN-06 pins, key and live take, and AN-09 bend, on the Note 9 and tablet; tape drags, bone tap and rotated-picture pins, and the corner-pin drag, are still unproven | `an09-new-sprite-sheet-open-with-chooser.png` |

New findings:
- **F10 Marks popup.** A region can be made only while the popup stays open, and any close (X, or touching the timeline) throws away a half-made region ("In 6.3 s . set Out" goes back to "new region"). With the popup open the timeline cannot be moved, so the only way to put In and Out at different times is to drop bookmarks first and jump between them with the arrows. A tester will think Set In / Set Out are broken. Suggest: keep the half-made region when the popup closes, and let the popup sit above the timeline. Also the Export button's spoken label reads "Export . %1$d" (an unfilled format string).
- **F11 Speech model download (corrects F9).** The bar does advance, but the source (alphacephei.com) gave the Fold only about 5 to 10 KB/s on this Wi-Fi (the huggingface file gave 50 KB/s; this PC got 250 KB/s, then dropped repeatedly), so 40 MB would take over an hour with no percentage or size shown. Suggest showing percentage and size, a Cancel, and a mirror or a bundled small model.
- **F12 Picker.** On the Fold (Android 14), tapping an image in the file picker's GRID view opens an "Open with" chooser instead of picking it; the LIST view picks correctly. Probably the system picker, not the app, but a tester hits it on the first image.
- **F13 Sprite sheet import.** The editor guessed a 3x3 grid for a sheet named `*.2x2.sprite.png` (file-name hint not read when it comes through the picker), and the sheet did not appear in Studio's sprite panel after Save and Back. Needs a second look on a phone with the Library route.

Left on the Fold (mine, safe to delete): projects "Untitled 3", "Untitled 4", Joy Creator folder test files, `Download/JC *`. Fold lock released. The speech-model retry download was stopped.

## Update 5, test Fold: caption-style undo passes; AN-06 partly; a way round the model download

| Check | Result | Proof |
|---|---|---|
| 1e Caption-style chip undo | ✅ A clip with captions made from the speech clip (Transcript > Fast timing, words appear on the timeline). One tap on the "Zoom" chip = one undo step (counter 4 to 5); one Undo takes it back (5 to 4, redo 1); closing and reopening the Captions drawer shows "Pop" again. 🟡 Small: while the drawer stayed open after Undo, the "Zoom" chip still looked selected until the drawer was reopened (F14) | `caption-style-*.png` |
| AN-06 place pins | ✅ the first pin ("Chest", a hard Pin tool) places with one undo step, badge shows 1 pin | `an06-pins-placed.png` |
| AN-06 second pin, key, live take, tape drags, bone tap, rotated-picture pins | ⬜ **blocked on the Fold's narrow screen (F15):** after the first pin the Puppet drawer grows to cover the whole picture, so no second pin can be placed (taps land on the drawer); closing the drawer leaves "pose mode" where taps and drags on a lone hard pin do nothing (a hard Pin is an anchor and cannot be dragged). On the Note 9 and tablet the same drawer left room. A reliable way to place several pins on a phone-sized screen is owed | `an06-pin-dragged-take.png`, `an06-grab-drag-arm.png` |
| AN-09, STU-C2 sprite corner pin and bend | ⬜ still not run (sprite sheet did not reach Studio, F13) | |

How the caption check was unblocked (for anyone repeating it): the speech model download from alphacephei.com was unusable over the Fold's link (F11), so I downloaded the 40 MB `vosk-model-small-en-us-0.15.zip` on the PC, unzipped it and copied the folder into the test app's own `files/vosk/` (`run-as`, through `phone.ps1 shell-write`; no uninstall, no data cleared); Transcript then used it at once. A mirror, or bundling this small model, would remove the problem for testers too (F11).

F14 Captions drawer chip stays on the undone style until the drawer is reopened. F15 Puppet drawer covers the picture after the first pin on a phone-sized screen (known on the tablet 11-14; on the Fold cover screen it blocks the second pin entirely).
