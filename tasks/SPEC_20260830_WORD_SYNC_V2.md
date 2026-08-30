# SPEC — Word Sync v2: use the drawer that already works

**Written:** 2026-08-30 · **For:** an external agent, FRESH session · **Owner:** JoyRaptor.

**Read `tasks/LANES.md` first** and claim `SPEC_20260830_WORD_SYNC_V2`. Never run gradle,
never a bare `git commit`, `git add` as you write, merge conflict = STOP.

**This supersedes the Word Sync mode UI that landed in `b3648455`/`139b5dbf`/`94b4cb9c`/
`2c7d4dfa`.** The engine underneath is right and stays. The interface built on top of it is
wrong and comes out.

---

## 1. What went wrong, in JoyRaptor's words

> "It's a mess. Instead of activating and using the original dropdown drawer and modifying
> it — it made an entirely NEW drawer! Didn't want that, the other one WORKED, I just needed
> it MODIFIED! I can't drag words in wordsync mode — that's the WHOLE POINT!"

Three defects, all confirmed in the code:

| # | Symptom | Cause, located |
|---|---|---|
| 1 | A second drawer appeared | `ensureWordSyncBanner` builds a new view and inserts it into `editor_root` at index 1 (`FaditorEditorActivity` ~16980) |
| 2 | The shuttle scrubs the TIMELINE, not the word | its listener calls `editorTimeline.setPlayheadPositionMs(cur + deltaMs)` (~17002) |
| 3 | Timeline scrubbing went strange, "biased towards rewinding" | see §5 — the mode is interfering with the timeline's own drag |

And the thing JoyRaptor actually wants already exists: **`showWordScrubDrawer(int index)`** at
`FaditorEditorActivity:16439`, opened by long-pressing a word (call site ~30327). It has the
word text as an editable field, prev/next nudges, a timestamp, a close button — and
`applyWordGroupDelta(long deltaMs)` at :16464, which moves the word by a delta and already
handles clip speed, transcript sync, caption rebind and autosave.

**That method is the correct target for the shuttle.** It is already written.

---

## 2. The rule

**Word Sync mode IS the existing word drawer.** Not a mode with a drawer — the drawer is
the mode.

- `showWordScrubDrawer(index)` → **enter** Word Sync mode (`wordSyncMode.enter()`).
- `hideWordScrubDrawer()` → **exit** it (`wordSyncMode.exit()`).

There is no separate toggle, no banner, no chip. JoyRaptor: *"When THAT drawer is open we have
entered wordsync mode, and we exit wordsync mode when it closes."*

**Delete the banner entirely** — `ensureWordSyncBanner`, `updateWordSyncBannerChrome`,
`wordSyncBanner`, `wordSyncBannerWired`, and the toolbar chip that toggles it. Do not leave
it behind a flag. A second way to enter a mode is a second thing to keep in sync, and it is
the defect being removed.

---

## 3. The drawer's one row

JoyRaptor: *"To the left of the scrubber nudger have the TT Tt tt b u i, and to the right have
the one/ripple/stretch toggle and snap toggle, all on a single row."*

```
[ TT  Tt  tt  B  U  I ]   [ ===shuttle=== ]   [ ONE ]  [ SNAP ]
```

- **Left:** `TT` `Tt` `tt` `B` `U` `I`, applied to the word in the drawer's text field.
  `WordSyncMode.transformTT/transformTt/transformtt` already exist — use them.
  `WordSyncMode.isRichTextAvailable()` returns **false**, so **B/U/I stay disabled** with a
  short toast explaining why. Do not build per-word styling here; it is its own spec.
- **Middle:** the shuttle (§4).
- **Right:** a `ONE / RIPPLE / STRETCH` cycle button and a `SNAP` on/off toggle, both reading
  and writing `WordSyncMode.getRippleMode()/setRippleMode` and `isSnapEnabled/setSnapEnabled`.

One row. If it does not fit on a 1080px-wide phone, shrink the labels — do not wrap to two
rows, and do not grow the drawer's height.

### 3.1 Every toggle explains itself with a toast

JoyRaptor: *"When you toggle those options, we need to show a toast saying what they do."*

`ONE` / `RIPPLE` / `STRETCH` and `SNAP` are three-letter labels for behaviours nobody can
guess, and getting one wrong moves a hundred words. So on **every** tap that changes one,
show a short toast saying **what will now happen**, not what the mode is called.

Use this copy exactly. It is written for someone who has never used an editor, and it names
the effect rather than the jargon:

| Toggle | Toast |
|---|---|
| `ONE` | **Only this word moves.** |
| `RIPPLE` | **This word and every word after it move together.** |
| `STRETCH` | **Words after this one spread out or squeeze to fit.** |
| `SNAP` on | **Snapping on — words jump to the nearest sound.** |
| `SNAP` off | **Snapping off — words land exactly where you drop them.** |

Rules:

- Fire on **every** tap, including tapping back to a mode you were already in. This is a
  reminder, not a notification of change — the whole point is that you can check what mode
  you are in without having to remember what the label meant.
- `Toast.LENGTH_SHORT`. These are read at a glance mid-edit.
- Do not stack: cancel the previous toast before showing the next, or fast cycling through
  three modes queues three toasts that outlive the gesture.
- The button label still shows the current mode, so the toast is confirmation, not the only
  indicator.

The same principle already applies to `B/U/I`, which are disabled: their toast must say why
in plain words — **"Bold, underline and italic need per-word styling, which isn't built
yet."** Not "unsupported".

---

## 4. The shuttle: right control, wrong target

JoyRaptor: *"The new scrubber nudger has great velocity acceleration etc. The only problem is it
is scrubbing the timeline and not the selected WORD. I actually like it better than the one
we currently have, but its target is the wrong thing."*

So: **keep `TimeShuttleView`, replace `WordScrubView`.**

1. In the drawer layout (`activity_faditor_editor.xml` ~2873, `@+id/word_scrub_strip`),
   replace the full-width `WordScrubView` with a `TimeShuttleView` at its natural width.
   `TimeShuttleView` is already 72dp and already measures deflection against a third of the
   screen rather than its own width (`6becec92`), which is exactly what JoyRaptor asked for:
   *"make it only 1/3rd what it currently is… once the user grabs the knob it's sensitive
   the entire width."* **That work is done — do not redo it.**
2. Its listener calls **`applyWordGroupDelta(deltaMs)`**, not
   `editorTimeline.setPlayheadPositionMs`. One line, and it is the whole of defect 2.
3. `onScrubEnd` commits one undo step for the whole gesture.
4. Delete `WordScrubView` usage and the `wordScrubStrip` field once nothing references it.
   Check whether the class is used anywhere else before deleting the file.

---

## 5. Timeline scrubbing must not change at all

JoyRaptor: *"As soon as I activated it, scrubbing the timeline is weird and seems biased towards
scrubbing left — like a smooth ratchet. It CAN go both ways but it's more responsive towards
rewinding."*

A directional bias in a drag almost always means **two handlers are consuming the same
gesture** and fighting: one advances the playhead, the other pulls it back, and whichever
wins depends on event order.

**Diagnose before changing anything.** Look at what the mode added to the timeline's touch
path — the lockout at `FaditorEditorActivity` ~15137 and ~16832, and any
`TranscriptPanelView.dragTo` wiring. Find which two paths both act on a horizontal drag.

**The rule: entering Word Sync must not alter timeline scrubbing in any way.** Same feel,
same direction, same speed as with the mode off. Verify by scrubbing with the mode off and
on and comparing — if they differ at all, it is not fixed.

---

## 6. Dragging words on the tape — the whole point

JoyRaptor: *"While in wordsync mode I can drag words horizontal from the timeline audio or video
tape itself."* And: *"I can't drag words in wordsync mode — that's the WHOLE POINT!"*

While the drawer is open, a horizontal drag **starting on a word** in the timeline's audio or
video tape moves that word:

- Route it through `WordSyncRipple.apply(...)` with the current mode, then
  `OnsetDetector.snap(...)` when SNAP is on. **Both are built and covered by 22 passing tests
  each** (`run-wordsync.sh`, `run-onset.sh`) — consume them.
- Dragging on the tape but **not** on a word still scrubs the timeline normally (§5).
- One undo step per drag, however many words moved (§6a).

### 6.1 Tapping a word on the TAPE retargets the drawer

JoyRaptor: *"Tapping words in the timeline tape while in wordsync mode changes which word you're
working on."*

A tap (not a drag) on a word in the tape sets `wordScrubCurrentIndex` to that word and
refreshes the drawer — the text field shows the new word, the timestamp updates, and the
shuttle, the case buttons and the ripple mode all now act on it. Same as tapping it in the
transcript panel; the tape is just a second way to reach the same selection.

This is what makes the mode fast: you never leave the tape. Tap a word, nudge it, tap the
next, nudge it. Reuse `showWordScrubDrawer(index)`'s selection path rather than writing a
parallel one — if the drawer is already open it should retarget, not reopen and re-animate.

---

## 6a. Word timing must become UNDOABLE — it never has been

JoyRaptor: *"Undo needs to work for word nudges etc."*

**This is not a Word Sync regression. Word timing has never been undoable in this app.**
There are five `setWordStart` call sites in `FaditorEditorActivity` (~16263, 16426, 16475,
16582, 16675) and **not one of them records an undo action**. `applyWordGroupDelta` contains
zero `undoManager` calls. The only word-related undo that exists is "Word case" for the
TT/Tt/tt transform (~17082).

That was survivable when moving a word was a slow, deliberate, one-at-a-time act. It is not
survivable now: RIPPLE and STRETCH move **every word after the one you dragged**, so a single
mistaken gesture can rewrite a whole transcript's timing with no way back. **Shipping ripple
without undo would be the most destructive feature in the editor.**

Required:

- A **`WordTimingAction`** in `undo/EditActions` holding the before and after start times for
  every word it touched — the arrays `WordSyncRipple.apply` already returns, and it
  deliberately never mutates its input precisely so the "before" survives.
- **One undo step per gesture.** A shuttle hold is one step, not one per frame: record on
  `onScrubEnd`, using the value snapshotted at `onScrubStart`. A tape drag is one step. A
  prev/next tap is one step.
- **Every one of the five call sites goes through it**, not just the new ones. A path that
  silently skips undo is the trap this section exists to close.
- Redo must work too, since the action carries both directions.

Verify by dragging in RIPPLE mode, pressing undo **once**, and confirming *all* the moved
words return — not just the one under your finger.

This is the feature. If time runs short, cut §3's formatting buttons before cutting this.

---

## 7. Files

```
app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java   (drawer, delete banner)
app/src/main/res/layout/activity_faditor_editor.xml                  (~2873 drawer row)
app/src/main/java/com/fadcam/ui/faditor/transcript/TranscriptPanelView.java
app/src/main/java/com/fadcam/ui/faditor/layers/LayerGestureController.java  (tape drag)
app/src/main/java/com/fadcam/ui/faditor/transcript/WordSyncMode.java  (drop banner-only state)
```

**Do not touch** `WordSyncRipple`, `OnsetDetector`, `WordSyncOnsets`, `PcmSidecar`,
`ScrubEngine`, `TimeShuttleView`. They are correct, tested, and JoyRaptor likes how the shuttle
feels. The bug is what they are wired to.

---

## 8. Acceptance

`.\tools\phone.ps1 devices` and `build` (with date) pasted. Re-derive taps from a fresh
screenshot.

1. Long-press a word → the **existing** drawer opens. **No second drawer or banner appears
   anywhere.** Screenshot.
2. Close it → mode exits. No leftover chrome.
3. One row: TT/Tt/tt/B/U/I, shuttle, ONE/SNAP. Screenshot at 1080px width.
4. Shuttle moves **the word**, not the playhead. Screen-record 3s showing the word moving on
   the tape while the playhead stays put. **This is defect 2 and the check most likely to be
   faked** — the playhead must not move.
5. **Drag a word on the timeline tape.** It moves. Screenshot before/after. §6.
6. RIPPLE: drag one word, the following ten move with it. **Undo once, all ten return.**
   Then redo once and they move again. §6a.
6b. Tap a different word **on the tape**: the drawer retargets to it without reopening, and
   the shuttle then moves that word. Screenshot both selections. §6.1
6c. Undo a shuttle nudge, a tape drag, and a prev/next tap — **each is exactly one press**,
   not one per frame or one per word.
7. `TT` on a word gives all caps. B/U/I are visibly disabled, and tapping one explains why
   in plain words.
7b. Tap each of ONE / RIPPLE / STRETCH / SNAP and screenshot the toast. The wording must
   match §3.1 exactly. Tap the same mode twice — **it toasts both times.** Cycle fast through
   all three and confirm the toasts do not stack up behind you.
8. **Scrub the timeline with the mode OFF, then ON.** Identical feel and direction.
   Screen-record both. §5.
9. Say plainly what you did NOT verify.
