# REVIEW FIXES — SPEC_20260829_WORD_SYNC (adversarial audit 2026-08-29)

**Branch:** `joy-creator` · **Base commit:** `b8ac6a6c` (WordSyncMode + banner/lockout/ticks) · **Fix commit:** this one
**Reviewer:** muse-spark (self-adversarial) · **Owner:** JoyRaptor (non-technical explanation also in this doc, §7)

This file logs what the audit found and what was changed, so JoyRaptor can see the reasoning and tweak thresholds without re-reading code.

---

## 1. How to tweak quickly

| What you feel | Where to change | Current value | Try |
|---|---|---|---|
| Tint not visible in sun | `FaditorEditorActivity.java:16821` `updateWordSyncTint` | `0x334DD0E1` (20%) + top gradient | `0x4D4DD0E1` (30%) or increase stripe to 6dp |
| “Word Sync” chip too wordy / too short | `FaditorEditorActivity.java:30435` header chip | `"Word Sync"` / `"Exit Word Sync"` | change `wsChip.setText()` strings |
| Snap fights you zoomed-in / never fires zoomed-out | `OnsetDetector.java:208` `SNAP_MIN_MS`/`SNAP_MAX_MS`/`SNAP_PIXELS` | 40 ms / 120 ms / 12 px | 30/140 or 10 px |
| Ticks too faint / too strong | `LayerRowRenderer.java:820` `ensureOnsetPaint` | `0x88FFFFFF` 1.4dp | `0x66` fainter, `0xAA` stronger |
| Shuttle too fast / slow for word nudge | `FaditorEditorActivity.java:16870` `setMaxMsPerSec(12000)` | 12k ms/s | 8k slower, 15k original |
| Horizontal drag moves word when you meant to scroll | `TranscriptPanelView.java:676` `dx*msPerPx` | uses `wordSyncMode.getMsPerPixel()` directly | require `|dx|>|dy|` or `dx*msPerPx>15ms` |

All are one-line constants with comments pointing to this table.

---

## 2. Fixes applied (what + why + where)

### 2.1 Tint was invisible on Note 9 in sunlight (UX, spec “visually unmistakable”)
**Before:** `0x1A` (10%) cyan over black — office-visible, sun-invisible. No border.
**After:** `0x33` (20%) + `GradientDrawable` top stripe (`FaditorEditorActivity.java:16821`). Reads as “different mode” without the banner. Tweak table above if still faint.

### 2.2 Entry chip said “WS” (2 letters, cryptic)
**Before:** `FaditorEditorActivity.java:30439` `setText("WS")` — user would not know to tap.
**After:** `setText("Word Sync")` / `"Exit Word Sync"` when ON, `setContentDescription("Word Sync — fix transcript timing")`, ripple background, duplicate guard `findViewWithTag("word_sync_chip")` (`FaditorEditorActivity.java:30436`). Full label fits 1080px header; stage 2/3 ordering persists it automatically.

### 2.3 B/U/I toast never showed
**Before:** `setEnabled(false)` → `onClick` never fires (`FaditorEditorActivity.java:16952`). User sees grey buttons with no explanation.
**After:** keep `setEnabled(true)` at 0.5 alpha, `setContentDescription` explains need, `onClick` now shows `Toast.LENGTH_LONG` with “needs per-word styling — not yet built (SPEC §3.6, see fixes log)” (`FaditorEditorActivity.java:16942`). Spec says grey them and say why — now it does.

### 2.4 `cancelDrag` left transcript dirty (code correctness)
**Before:** `WordSyncMode.java:242` cleared `dragBeforeStarts` without reverting `dragTo()` writes already applied. `ACTION_CANCEL` (phone call) would leave transcript half-rippled with no undo.
**After:** `cancelDrag()` now loops `dragBeforeStarts` and restores each `TranscriptWord` (`WordSyncMode.java:242`). `TranscriptPanelView.java:875` `ACTION_CANCEL` calls it.

### 2.5 `onsetTickPaint` density race + wrong culling (code)
**Before:** `LayerRowRenderer.java:821` field initializer `setStrokeWidth(1.2f)` ran before constructor set `density` → 0 width on some paths. Culling `x< -20 || x>10000` hardcoded, misses long projects.
**After:** `ensureOnsetPaint()` lazy (`LayerRowRenderer.java:820`) — `0x88FFFFFF` 1.4dp* density, `Cap.ROUND`, cull against `lastHScrollOffsetPx .. lastWidthPx` (`LayerRowRenderer.java:832`). Ticks now 18% more opaque so magnet is visible.

### 2.6 Reflection fragility for zoom + transcript (code)
**Before:** `getWordSyncMsPerPixel()` reflected `dpPerSecondPx`, `getWordSyncTranscript()` reflected private `transcript`.
**After:** added `EditorTimelineView.getMsPerPixel():1859` and `TranscriptPanelView.getTranscript():165` (`FaditorEditorActivity.java:16735` + `16747` now call them directly). Falls back to 5.0 ms/px only if view null.

### 2.7 Canvas object drags not locked (UX, §3.1)
**Before:** lockout covered `LayerGestureCallback` + master trim, but `overlayLayer`/`spriteOverlayView`/`waveformOverlayView` still draggable — fat finger while aiming at syllable could move a `TextOverlayItem`.
**After:** `updateWordSyncTint()` now `setEnabled(!on)` for `overlayLayer`, `overlayLayerBelow`, `spriteOverlayView`, `spriteOverlayViewBelow`, `waveformOverlayView` (`FaditorEditorActivity.java:16832`).

---

## 3. Audit findings left as-is (intentional, tweak later)

* **Pin UI for STRETCH anchor** — *now fixed in Part 2*: long-press pin + gold dot (`TranscriptPanelView.java:893`, `WordSyncMode.java:145`).
* **Park-playhead + shuttle + tap (§3.2 third gesture)** — *now fixed in Part 2+3*: `FaditorEditorActivity.java:30116` maps playhead→source for both video/audio, checks `isFingerDown()`.
* **ScrubEngine lifecycle** — *now fixed in Part 2*: `WordSyncMode.release()` + `FaditorEditorActivity.java:1841` onDestroy.
* **Global snap vs WordSync snap double-magnet** — `overlaySoftSnapEnabled` still active in WordSync; may double-snap near onsets. Verbal test needed — not changed to avoid scope creep (low risk, both snaps defeatable).

---

## 4. What to verify on device (13-point acceptance)

1. Tint visible in sun + banner persistent with Exit (screenshot both bands).
2. “Word Sync” chip in transcript header opens/closes mode without “WS” confusion.
3. Layer drag/trim + canvas text drag do nothing while ON, scrub/zoom + word drag work.
4. Onset ticks faint but visible on both floating + audio bands (screenshot).
5. Drag word near consonant snaps; screenshot tick before/after.
6. Drag to open space between onsets stays (defeatable).
7. Ripple drag moves following 10 words equally; **one undo** restores all.
8. Stretch moves proportionally to clip end (or next pin when pinned UI lands).
9. Tap word → retype editor, move to next word without Done.
10. Shuttle still 72dp, deflection measured against 1/3 screen reaches full speed (screenrecord 3s).
11. `TT` → UPPERCASE, `Tt` → Title, `tt` → lowercase in two taps.
12. `B/U/I` still greyed but tap shows LONG toast explaining per-word styling not built.
13. Scrub audio follows dragged word (~23ms latency) — listen while dragging.

---

## 5. Non-technical summary (for JoyRaptor)

The tint was too pale to see outside, and the entry button just said “WS” — nobody would know it is Word Sync. Now the timeline gets a darker blue wash plus a thin blue edge (like a mode color), and the button says “Word Sync” / “Exit Word Sync” in plain words. Those two make it obvious you are in a different tool, which is the whole point of the lockout so you don’t accidentally move a clip while fixing words.

The grey B/I/U buttons looked dead and did nothing when tapped — now they still look grey but tapping tells you why (“needs per-word styling, not built yet”).

If you cancel a drag (e.g. phone call), the words used to stay half-moved with no undo — now they snap back.

The little onset ticks (where words start) are a bit brighter and no longer cut off on long songs.

If you want it more/less obvious, see the tweak table at top — all are one number.

---

## 6. Files touched in fix commit

* `app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java` — tint, chip, B/U/I, `getMsPerPixel`/`getTranscript` direct, canvas lockout
* `app/src/main/java/com/fadcam/ui/faditor/layers/LayerRowRenderer.java` — `ensureOnsetPaint` + viewport culling
* `app/src/main/java/com/fadcam/ui/faditor/transcript/WordSyncMode.java` — `cancelDrag` revert
* `app/src/main/java/com/fadcam/ui/faditor/transcript/TranscriptPanelView.java` — `getTranscript()`
* `app/src/main/java/com/fadcam/ui/faditor/timeline/EditorTimelineView.java` — `getMsPerPixel()`
* `app/src/main/java/com/fadcam/ui/faditor/move/TimeShuttleView.java` — (no change in this fix, already has `isEngaged`)

Build: `TYPECHECK OK 678`, `run-onset 22/22`, `run-wordsync 22/22`. `build.log` still stale (watcher `C:+Projects` path), device checks still owed.

---

## 7. Part 2 — What was fixed for non-engineers (you asked, done, no questions needed)

You said “I’m not an engineer — fix what needs to be fixed.” These were done on top of the 6 above, without asking you:

**Pin for Stretch:** Long-press any word while Word Sync is ON now pins it (gold dot above the word). That word becomes the anchor so a Stretch drag only spreads words up to that pin instead of to the end of the clip. Tap the pin again (long-press) to unpin. One drag still fixes 50 words, but now you can say “stop here”.

**Park + shuttle + tap:** If you park the playhead where you want a word, hold the little 72dp shuttle (it scrubs the playhead faster the farther you push), and tap the word — the word now jumps to the playhead (snapped to the nearest consonant). This was the third way to place a word in the spec; now it works.

**Scrub engine cleanup:** When you leave Word Sync or close the editor, the scrub audio track is now fully released. Before, it kept a small audio thread alive in the background.

**How to tweak these (still one number):** pin dot color at `TranscriptPanelView.java:600` `0xFFFFC107`, shuttle max speed at `FaditorEditorActivity.java:16870` `12000`.

---

## 8. Part 3 — Adversarial second pass (you asked to test everything again, fix what’s wrong)

**Pinned array could never be set before first drag:** `WordSyncMode.java:145` now auto-creates `pinned` to transcript length on first `setPinned`/`isPinned`, so long-press pin works even before any drag. Before, first pin was silently ignored.

**Shuttle-tap used wrong clip for source mapping:** `FaditorEditorActivity.java:30134` now finds `phClip` index and maps `playheadMs - segStart` correctly for both video (`Clip`) and audio (`AudioClip` when `transcriptIsForAudio`). Before it used `selectedClipIndex` for every playhead, so snapping landed in wrong clip after a cut.

**Shuttle “engaged” included spring-back:** `TimeShuttleView.java:177` `isEngaged` includes 260 ms spring-back. Tap-to-snap should only fire while finger is down, so added `isFingerDown()` and `FaditorEditorActivity.java:30120` now checks `isFingerDown()` not `isEngaged()`.

**Transcript horizontal vs vertical drag:** `TranscriptPanelView.java:672` now requires `|dx|>|dy| && |dx|>touchSlop` to start timing drag; vertical drag still scrolls. Before, any diagonal move could start timing drag and steal scroll.

Build still `TYPECHECK OK 678` (now 1899 classes), `run-onset 22/22`, `run-wordsync 22/22`. No new files, all fixes are 2–5 line tweaks.

