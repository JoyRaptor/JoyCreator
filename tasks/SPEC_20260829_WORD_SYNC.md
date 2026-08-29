# SPEC — Word Sync mode: fix a sloppy transcript fast

**Written:** 2026-08-29 · **For:** an external agent · **Owner:** JoyRaptor.

**Read `tasks/LANES.md` first** and claim `SPEC_20260829_WORD_SYNC`. Rule 6 (never run
gradle), the WORKING-TREE HAZARD (`git add` as you write) and the **no bare `git commit`**
corollary apply.

---

## 1. The problem, in JoyRaptor's words

> "Scrubbing words is a little difficult with the tool. First off it only scrubs a little
> bit at a time, scooting can take a long while, long press can take a bit with a lot of
> words. Great for surgical edits to transcript but tedious with sloppy transcripts."

The existing word scrubber is a **precision instrument**. It is correct and it should stay.
This spec adds a **bulk instrument** beside it, for the case where a transcript is roughly
right and eighty words need moving.

---

## 2. What already exists — three pieces built for this and not yet used

**Read these before designing anything. All three are committed, tested, and idle.**

| Piece | What it gives you | Where |
|---|---|---|
| `waveform/PcmSidecar` | flat, memory-mapped, decoder-free audio (~2.6 MB/min) | `00e7413f` |
| `audio/ScrubEngine` | AE-style granular scrub playback, ~23ms latency | `00e7413f` |
| **`waveform/OnsetDetector`** | **where every word STARTS**, plus `snap()` | `0546a39e`, **16/16 harness tests pass** |
| `audio/ScrubAudioController` | already wires scrubbing to the playhead drag | `9a18dc5e` |
| **`transcript/WordSyncOnsets`** | **onset cache + zoom-scaled snap tolerance (§3.3 is DONE)** | `51f23ecc` |
| **`transcript/WordSyncRipple`** | **ONE / RIPPLE / STRETCH + `anchorFor` (§3.4 is DONE)** | `c4e4eaa0` |
| **`move/TimeShuttleView`** | **already shrunk to 72dp, gesture measured against the screen (§3.5 is DONE)** | `6becec92` |

**Three sections of this spec are already built and tested.** Run
`bash tools/jvm-harness/run-onset.sh` (22/22) and `bash tools/jvm-harness/run-wordsync.sh`
(22/22) to see them work. What remains is the MODE itself: the toggle, the lockout, the
gesture routing, the onset ticks on the tape, and the formatting row — the parts that need
`FaditorEditorActivity` and `TranscriptPanelView`. **Consume §3.3–3.5; do not rebuild
them.**

`OnsetDetector` is the one that changes the feel. It is pure Java, takes a `Samples`
callback, and `PcmSidecar.Handle` already implements it — so getting onsets for a source is
two lines. **Do not write a peak-finder.** Run `bash tools/jvm-harness/run-onset.sh` to see
what it does.

---

## 3. The mode

### 3.1 Entering and leaving

A toggle (toolbar button). While Word Sync is ON:

- Dragging layers, trimming, and selecting other objects are **locked out**. JoyRaptor asked
  for this explicitly and it is the point: the whole screen becomes one tool, so a fat-
  fingered drag cannot silently move a clip while you are aiming at a syllable.
- What still works: **scrub the timeline**, **zoom the timeline**, **tap and drag words**.
- The mode must be visually unmistakable — a tinted timeline background and a persistent
  banner with the exit control. A lockout the user does not know they are in reads as a
  broken app.

**No "Done" between words.** Every edit commits as it happens. JoyRaptor: *"quickly able to
move between many different words without hitting Done."*

### 3.2 The three ways to place a word

| Gesture | Result |
|---|---|
| **Drag a word** on the tape | moves its start, snapping to onsets (§3.3) |
| **Tap a word** | opens the existing retype editor, in place |
| **Park the playhead, then tap a word** with the shuttle engaged | the word snaps to the playhead |

That third one is often faster than aiming a drag, and it costs almost nothing once the
other two exist.

### 3.3 Snap to onsets — the thing that makes it feel like magic

While dragging, run the position through `OnsetDetector.snap(onsets, ms, tolerance)`.

- Compute onsets **once** per source, off the main thread, cache for the session.
- Tolerance scales with **timeline zoom**: about 40 ms zoomed in, up to ~120 ms zoomed out.
  A fixed tolerance either fights you when zoomed in or does nothing when zoomed out.
- **Show the onsets** as faint ticks on the tape while the mode is on. A magnet you cannot
  see is indistinguishable from a bug.
- Snapping must be **defeatable** — `snap()` already returns the input unchanged outside
  tolerance, and a drag that lingers between two onsets must be allowed to stay there.

### 3.4 Ripple drag — the actual fix for a sloppy transcript

**This is the highest-value item in the spec.** Sloppy transcript timing drifts
*monotonically*: everything after a point is late by a growing amount. Fixing that one word
at a time is exactly the tedium JoyRaptor is describing.

A toggle in the mode banner:

| Setting | Behaviour |
|---|---|
| **One** (default) | drag moves only that word |
| **Ripple** | drag moves that word AND shifts every later word by the same delta |
| **Stretch** | drag moves that word and *proportionally* redistributes every later word up to the next manually-pinned one |

**One drag fixes fifty words.** Stretch is the one that handles a transcript running
progressively late; Ripple handles a constant offset from a late start.

Must be **one undo step per drag**, whatever it touched. That is JoyRaptor's standing ruling
(one press, one step), and it matters most here, where a single gesture can move a hundred
words.

### 3.5 The shuttle control

JoyRaptor: *"The word nudge scrubber doesn't need to be that big. Make it a third the size.
Once you are dragging it, the drag can have velocity scale with distance from center using
the whole screen but we don't need the control to span the whole screen for that."*

`move/TimeShuttleView` currently measures deflection against **its own width**
(`onTouchEvent` uses `getWidth() / 2f`), and measures 220×44 dp.

**Change:** shrink to ~72 dp wide, and on `ACTION_DOWN` capture the touch X; thereafter
compute deflection from `(x - downX)` against a **travel half-width** that defaults to about
a third of the screen. Small control, large gesture field. The existing curve, dead zone
and inertial spring-back are good — do not retune them; only change what the deflection is
measured against.

### 3.6 The formatting row

The freed space either side of the shuttle carries: **TT · Tt · tt · B · U · I**

Applied to the selected word. An all-caps word becomes two taps: tap the word, tap `TT`.

⚠️ **`TT` / `Tt` / `tt` are cheap** — pure text transforms on the word. **`B` / `U` / `I`
are only cheap if per-word styling already exists.** Before building them, check whether
`TranscriptWord` / `CaptionStyle` can carry per-word bold/italic/underline and whether both
`CaptionOverlayView` and `CaptionExportRenderer` honour it.

**If they cannot: ship the three case buttons, grey out B/U/I, and say so in your report.**
Do not invent a per-word styling model inside this spec — that is its own piece of work
(`HANDOFF_20260828.md` lists "per-run rich text inside a caption cue" as a known want), and
smuggling it in here would make this unreviewable.

---

## 4. Files

```
app/src/main/java/com/fadcam/ui/faditor/transcript/WordSyncMode.java      (NEW — owns the mode)
app/src/main/java/com/fadcam/ui/faditor/transcript/WordSyncOnsets.java    (NEW — cache + zoom tolerance)
app/src/main/java/com/fadcam/ui/faditor/move/TimeShuttleView.java         (§3.5)
app/src/main/java/com/fadcam/ui/faditor/transcript/TranscriptPanelView.java
app/src/main/java/com/fadcam/ui/faditor/layers/LayerRowRenderer.java      (onset ticks on the tape)
app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java        (toggle + lockout ONLY)
```

**Keep the activity edit small.** Mode state, gesture routing and ripple maths live in
`WordSyncMode`. The activity should own the toggle, the lockout flag, and nothing else —
the same rule that made `AudioLayerSync`'s four defects fixable in one file.

**Do not touch** `audio/*` or `waveform/*`: those pieces are done and tested. Consume them.

---

## 5. Acceptance

1. **Build.** `build.log` last line `BUILD SUCCESSFUL`, mtime **and date** newer than your
   last edit. Paste both.
2. **Device.** `adb devices` pasted. **Read `tasks/DEVICE_CONTROL_RUNBOOK.md`** — there is
   no accessibility tree; the loop is screenshot → tap by coordinate.
3. Toggle on: dragging a clip does nothing, trimming does nothing, the banner is visible.
   Screenshot. Toggle off: everything works again.
4. Onset ticks visible on the tape. Screenshot.
5. Drag a word near a consonant: it snaps. Screenshot before/after with the tick visible.
6. Drag a word to open space between onsets: it stays where you put it.
7. **Ripple:** drag one word, screenshot the following ten words before and after — all
   moved by the same delta. **Press undo once**: all of them return. This is the check most
   likely to be half-right.
8. **Stretch:** same, and the later words move proportionally, ending at the next pin.
9. Tap a word → retype editor opens in place; edit, and move to another word **without a
   Done step**.
10. Shuttle: screenshot showing it at ~1/3 its old width; confirm a small deflection still
    reaches full speed by dragging across a third of the screen.
11. `TT` on a selected word gives all caps in two taps. Screenshot.
12. Report honestly whether B/U/I are live or greyed, and why.
13. **Scrub audio works while in the mode** — that is the whole point of pairing them.

---

## 6. Traps

- `getSelectedClip()` returns `getClip(0)` when nothing is selected — on a music project
  that is the auto-blank black spacer. Use `clipUnderPlayhead()`.
- A value written and never read caused three bugs on 2026-08-28, one of them transcript
  words written to a map nothing consumed (`27c8b75d`). If a moved word does not move on
  screen, check the renderer READS your write.
- One undo step per gesture. A ripple that undoes word-by-word is a failure, not a detail.
- Never `perl -i` without `-CSD`. Verify `grep -c 'â' <file>` is 0 on every file touched.
