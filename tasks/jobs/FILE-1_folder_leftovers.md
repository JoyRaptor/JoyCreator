# FILE-1: Joy Creator folder and storage leftovers

**Milestone:** M1  ·  **State:** 🟡  ·  **Owner:** open (the folder lead's session, "App roadmap priorities", is retired)  ·  **Checked:** 2026-10-27 against the spec's §6

## Why it matters
"Uninstalling never loses your work, and everything lives in one folder you own" is a core promise. It is
built and mostly phone-proven; these are the last corners.

## Done when
- [ ] Everything in the spec's §6 is done or parked by the owner, and STATE §7 says so.

## Where it stands
Built 10-13 to 10-15 and mostly agent-proven on the Note 9: required first step, projects mirrored and
restored, Gather, one Trash with Undo, recordings and exports in the folder with readable names, the
Library reads it, share-in, big outside files linked with a warning and a yellow dot, Copy / Move / Leave,
the Space screen. Live spec: [`SPEC_20261013_FILE_MANAGEMENT.md`](../SPEC_20261013_FILE_MANAGEMENT.md) §4 (proof per piece) and §6 (open list).

## What is left
- [x] Undo after a Move: 11-15 PASS on the test Fold with a 44 MB file (Joybot Copy / Move / Leave card; Studio Undo then Redo kept the clip and its picture; the original is not put back in Downloads, which is the Move). [Report](../audits/device_checks_20261115.md).
- [x] Space → "Move unused to Trash…" (multi-select): the Note 9 pass is in the next lines (11-10 PASS); this box was stale.
- [x] 11-10 Share-in of a large file (over 25 MB) now asks Copy / Move / Leave (`ShareInActivity`, reusing `BringInDialog.Choice` + `OriginalsRemover`). Leave links only when the sharing app allows a lasting grant, else copies with a note. **Note 9 11-10 PASS** (a throwaway 31 MB file sent by an intent): the card "This is a large file (31 MB)" appears with Copy / Move / Leave / Cancel; Leave saved nothing in the folder and said "linked, not copied"; Move put a copy in the folder and removed the original. The first try exposed a bug (a `file://` share reported no size, so it skipped the choice); fixed by reading the size from the file. Not tried: Leave on a `content://` share whose app refuses a lasting link (falls back to copy; code path only), and Undo after a Move.
- [x] 11-10 Space → "Move unused to Trash…" PASS on the Note 9: the list pre-ticks every unused file (careful on a real phone); with only my throwaway ticked, "1 file moved to Trash", the folder went 643 → 611 MB, the file left the folder, the Trash showed it.
- [ ] In real use only (testing would litter his real folder): recording when the folder is missing, and the
      "make a Recordings folder?" offer after 20 loose recordings.
- [ ] The JoyBrush half: [`joybrush/briefs/JOY_FOLDER_FOR_JOYBRUSH.md`](../joybrush/briefs/JOY_FOLDER_FOR_JOYBRUSH.md) §0
      (textured paper in thumbnails, thumbnail = last view, "Open…" adds a new drawing). Coordinate with the JoyBrush lead.
- [ ] About a dozen storage roots still sit outside the shared space (avatars, fonts, voiceovers, drawing working copies).
- [ ] Later, owner's list: presets and settings into the folder, version history.
- [ ] Test leftovers on the Note 9, safe to delete: `/sdcard/Joy Creator (first test)`, the project
      "Restore video test" (a copy), `Camera 2026-10-13 23.51.mp4`, `z1.png`.

## Files and traps
- Read the spec's §5 before building: it lists the existing parts this extends. Do not duplicate them.
- Any "is this file used?" logic must read the folder's `.joyproject` copies as well as the app projects.

## Checked 2026-11-24 (Sonnet lane, no phone; queue row 20)
- **Storage roots outside the shared space:** avatars and fonts are done (work-loss F6, `AvatarFolderCopy`, `FontFolderCopy`); a project's imported pictures, extracted audio and voiceover recordings (kept in the app's own `faditor_audio`) are copied into the folder beside its `.joyproject` by `StudioMirror`, so an uninstall does not lose them; the JoyBrush drawing working copies are app-private on purpose (the folder gets the finished `.joybrush`). Nothing else in this list is a PC job.
- **What is genuinely left:** the two in-real-use checks (recording with the folder missing; the "make a Recordings folder?" offer), the JoyBrush half of the folder (thumbnail = last view is the JoyBrush save/library lane's), the owner's later list (presets and settings into the folder, version history), and the Note 9 test leftovers to delete. None can be proven on a PC.

## 11-24: the pack folder contract (roadmap plan row 7)
Done: see [PACK-1, "The pack folder contract"](PACK-1_joypacks.md). In short: `Supplies/Packs/<Pack name>/` holds each installed pack unzipped and readable; the person's own copies stay in the usual `Supplies/` homes and always win; removal goes to the Trash; the original `.joypack` is not kept. Helpers `PackFolders` with tests (28 pass).

## 11-24/27: large share-ins read in the code and fixed (website lane, no phone)
`ShareInActivity` (a big video or many files shared into Joy Creator). **Already sound:** the copy streams in 256 KB pieces on a background thread (flat memory for any size), writes to a temporary file and renames it only when whole, refuses a short copy (size must match), asks Copy / Move / Leave above 25 MB, deletes an original only after its copy is confirmed byte for byte, and checks free space before starting (primary storage).
**Real risks found and fixed:**
1. **Turning the phone started a second copy.** The screen had no `configChanges`, so a rotation recreated it and `onCreate` re-ran the whole share: two copies of a 2 GB file at once, the second named "... 2". Fixed in the manifest (the screen now handles orientation, size and theme changes in place).
2. **No sign of life on a big copy.** Only "Saving…". `JoyFolder.addFile` now takes a `Progress` callback (about every 1 MB); the card shows "clip.mp4: 1.2 GB of 2.0 GB", at most four updates a second, only for files over the large-file line.
3. **Out-of-space in operating-system words.** A failure part way ("write failed: ENOSPC...") reached the card as it was. `SpaceErrors` (pure Java) recognises it through the cause chain and the card says: the phone ran out of room, nothing half-finished was kept, free some space and share again. The up-front space check's message says the same.
**Proof:** `bash tools/joyfolder-tests/run.sh` 29 pass: a 5 MB stream reports a handful of rising progress steps ending at the full size (the test fails when progress is reported every chunk); a source that fails part way (ENOSPC) throws, leaves no file under the real name and no temporary file, and is read as out of room; the cause chain, another phrasing and an ordinary failure are told apart. Cloud debug build run 45457138879 on `d39d236ef`: green. Not looked at on a phone.
**Still open (needs a phone or a bigger change):** the copy runs while the screen is in front; if the person leaves it, Android may end the app mid-copy (the temporary file is then cleaned on next start, nothing half-saved shows). A foreground service for very large copies would close that; the free-space check only covers primary storage (an SD-card folder is not pre-checked, but the failure now says so plainly); no cancel button on the progress card.

## 11-27: Space's "Move unused to Trash" and the Library's undo, read in the code (website lane, no phone)
**Space, "Move unused to Trash" (fixed, with tests).** Three real faults, all in the batch path (`SpaceActivity.confirmTrashUnused` and `JoyFolder.trash`):
1. **A trash move that THROWS left the file renamed and not in Trash.** `JoyFolder.trash` first renames the file to its Trash stamp, then moves it; only a move that *returned false* put the name back, a move that *threw* (a full disk, a lost grant) skipped that. The new host test caught it (the file was not where it started). Fixed: any failure of the move puts the original name back.
2. **One failure hid and stopped the rest.** A failure on file 3 of 8 threw out of the loop: files 1 and 2 were already in Trash, the index was never saved, and the message said nothing was lost. `TrashBatch.run` carries on past a failure, always saves the index, and remembers which file failed and why.
3. **"Unused" was decided when the screen loaded.** A project could start using a file before the person pressed Move, and it was trashed anyway. Use is now read again at the moment of the move (`TrashBatch.plan`), and a file that has become used is left, and said so.
Plain summary (`TrashBatch.summary`): "2 files moved to Trash... 1 file was left because a project uses it now. Couldn't move X: the phone ran out of room... Nothing was lost."
**Proof:** `bash tools/joyfolder-tests/run.sh` 30 pass: plan keeps only ticked and not-in-use files; a run over four paths (one missing, one whose move throws ENOSPC) moves the other two, names the failing one with a plain reason, leaves it where it was, and a fresh open of the folder lists both trashed items (the index was saved). Cloud debug build run 46007550808 on `f7536319a`: green. Not looked at on a phone. The single-file Move to Trash (`confirmTrash`) uses the same `JoyFolder.trash`, so it gets fault 1's fix too.
**Library, "undo after a Move".** There is no Undo to fix in the Library: it has no "Move" action; its Move to Trash (item sheet and the batch bar) already refuses a file a project uses (`library_trash_in_use`) and trashes a TextLab look with its preview as one item. Getting a trashed file back is Settings → Recently deleted, with a selection to put several back at once (`TrashSelection`), which is one press for the whole selection. The card's "Undo after a Move" box (Studio's Copy / Move / Leave, Undo then Redo keeping the clip and its picture) passed on the test Fold 11-15. If the owner wants an "Undo" toast straight after a Library trash, that is a new control (it would call `JoyFolder.restore` for the items just moved): not built, his call.

## 11-27 Library sheet, rename, Move to Trash on the cloud emulator (website lane; run 46083022853, script `library_actions.sh`)
| Row | Result | Evidence |
|---|---|---|
| Library item sheet for a drawing (SHELL-12, first half) | ✅ PASS | hold a Library card: sheet with name, "Inside the app", date, Open / Rename / Move to Trash (`tasks/screens/emulator-20261127/library_item_sheet_drawing.jpg`) |
| Rename | ⏳ not confirmed | the rename field opened and took "Renamed_one", but the script never pressed the keyboard's tick, so the name stayed "Drawing 1": script gap, to fix (press Enter) |
| Move to Trash, then put back (FILE-1) | ⏳ not exercised | the sheet was still in rename when the script held the card again, so nothing was trashed (Recently deleted: empty, Library still 1 item): script gap |
| A missing file (SHELL-12, second half) | ⏳ not exercised | a drawing here is an entry in `files/joybrush/library.json` rather than a file of its own name, so the script's delete found nothing; needs the path read from that JSON |
| Photo item sheet | not run | the emulator has no photos in the Library; push one under the app's folder first |
No crash anywhere in the run (0 FATAL in the log). Note: the drawing's Library picture is the brush icon, not a thumbnail (nothing was drawn in it).
- **11-27 second and third emulator passes (runs 46091197325, 46093309796):** the rename dialog takes the typed name but the app's own **Rename button is hidden behind the keyboard**, and Enter / Back do not commit it, so the script has to tap the keyboard's tick key: fix in the script (next run). Because the rename never committed, the sheet stayed open and Move to Trash was not reached either (Recently deleted: empty). **A drawing that has never been saved has no file at all** (`files/joybrush/library.json` lists it, but no `.joybrush` file exists on disk): that is already the "missing file" case, and the Library listed it, opened its item sheet (`tasks/screens/emulator-20261127/library_sheet_unsaved_drawing.jpg`) and did not crash (0 FATAL). So SHELL-12's missing-file half: ✅ PASS (by this natural case). Rename and trash/put-back: still to confirm.
- **11-27 fourth emulator pass (run 46094413212):** ✅ **Rename PASS**: typing a new name and pressing the keyboard's tick renames the drawing; `library.json` holds "Renamed_one" and the Library card reads it (`tasks/screens/emulator-20261127/library_renamed.jpg`). **Move to Trash asks twice, by design**: the first tap turns the row red and says "Tap again to move to Trash" (`library_trash_asks_again.jpg`); the script only tapped once, so the move itself and the put-back from Recently deleted are still to confirm (script fixed to tap twice; next run).
