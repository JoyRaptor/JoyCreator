# Just works, second pass: agent report (verbatim), 2026-10-17

Agent: ae34e9e030492f05d (Sonnet subagent of the previous JoyBrush Lead). Branch lead/just-works, commit 325b5278f on
c3627d200. Recorded verbatim at the new Lead's request; not yet verified or merged by the Lead.

---

All ten items from the second pass are done and pushed to `lead/just-works` (commit `325b5278f`, on top of `c3627d200`). I did not push `joy-creator`. All three full suites pass, but nothing was run on the phone.

**Test results (full suites, lock taken, then removed)**
- `:core:jvmTest`: 2490 run, 0 failed, 4 skipped. Those 4 were skipped before my changes too.
- `-p joybrush :androidkit:test`: 929 run, 0 failed.
- `:joybrush-android:testDebugUnitTest`: 263 run, 0 failed.
- The new and extended test classes all passed:

| Test class | Tests |
|---|---|
| `UndoTrimNoticeTest` | 8 |
| `LayerStackTest` | 15 |
| `BoardExportLayoutTest` | 11 |
| `BrushLibraryTest` | 4 |
| `FillPenRasterTest` | 17 |
| `LayerOpsMidStrokeTest` | 9 |
| `ExportChoiceSheetTest` | 11 |
| `FolderCopyNoticeTest` | 3 |

**The ten items**
1. **Media stroke (not a bug the way the audit described).** The trace shows `startMediaStroke` already queues the board-document creation ahead of the stroke (`JbCanvasView.kt:1383`, same call as the line path at 1619), and the begin step is queued behind it. The only way to reach the silent skip was the document creation failing, and that already says its reason. I added a flag so any remaining null case now always speaks: the reason if there was one, else "This drawing could not take pencil, watercolour or oil". I could not prove the first stroke end to end. Robolectric surfaces never run queued GL work, so only the flag and sentences are unit-level. If the owner really saw "first pencil stroke did nothing", it was not this path, and a device repro is needed.
2. A fill that changes nothing now says "Nothing to fill there." (test covers filling an already-red area).
3. A blank layer rename says "A layer needs a name." The activity restores the field to the kept name, so the Done key plus the focus loss that follows do not say it twice.
4. **Mask editing on a layer with no mask.** `setEditingMask(true)` says "This layer has no mask yet. Add one from its menu." The call sites only offer it when a mask exists, so I chose to say so rather than add a mask. The "Painting on the mask" toasts now show only when it actually switched.
5. `addMask` on a layer that already has one says "This layer already has a mask."
6. The brush Test button with the pen down says "Lift the pen first."
7. Greyed export scope rows and format chips now explain themselves when tapped, through `BoardExportLayout.whyGreyed`. The memory bar stays quiet because it is information, not a button. The real export sheet never greys rows or chips today, so this is covered by tests only.
8. A missing `index.txt` gives "The built-in brushes could not be loaded." and a single log line.
9. **Folder-copy failure.** A new `FolderCopyNotice` tells the person once per drawing per session: "Couldn't copy this drawing to your Joy Creator folder. It is safe in the app, and the next save will try again." It also fires when the folder cannot be opened at all, which was a silent early return before. Thumbnail failures stay log-only. Only the once-per-drawing logic and the sentence are unit-tested, not the Toast itself.
10. Moving or deleting a layer so a clipped layer becomes the bottom says "Unclipped: there's nothing below to clip to." (via a new `LayerStack.lostClipIn`, shown as a notice).

**Hot-file hunks:** `JbCanvasView.kt` only. The first pass added the `engine` hook, `onNotice` and `penIsDown()` with its guards. This pass added `mediaDocRefused` plus one `else` in the media begin, the fill else-branch, `renameLayer`, `setEditingMask`, `addMask`, `drawStrokes`, `moveLayer`, `deleteLayer` and the `tellIfUnclipped` helper. `GlPaintEngine.kt` is untouched, and I touched nothing under the forbidden paths.

**Left alone:** `DrawingLibrary.kt:169` (a folder-copy rename failure) is still log-only; only the missing-copy case was asked for.
