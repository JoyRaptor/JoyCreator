# HANDOFF — autonomous night session, 2026-08-19

JoyRaptor went to bed with ~40% of a 5-hour window left and asked me to finish what I could.
Everything below was done while he slept, on the **sandbox Note 9** (`<note9-serial>`).
**His Note 20 was not attached and "first lecture on phone" was never opened.** That was a
rule I set for myself: no unattended edits to his real project.

## What landed tonight

| Commit | What | Verified |
|---|---|---|
| `5ed2baef` | **The undo preview jump — root cause and fix** | **DEVICE**, reproduced and re-tested by me |
| `d33f694d` | **Motion range no longer outlives its object** | HARNESS, 15 assertions |
| `ee779c28` | **Black clips you can place yourself, any length** | **DEVICE**, end to end |
| `d4db52fd` | Diagnostics removed; a broken harness runner repaired | full suite green |

### The undo preview jump (`5ed2baef`) — the one that had beaten me four times
`updateCurrentTimeDisplay`'s parameter is `positionInCurrentSegmentMs` — SEGMENT-RELATIVE —
and it calls `getAbsolutePlayheadMs` to add the preceding clips' durations. Two bugs from
that one fact:

1. The original call passed a literal `0`, which is not "leave it alone" but "position 0
   within the current segment" — the clip's first frame. Selection follows the viewport, so
   undo parked the preview at the head of whatever clip JoyRaptor was looking at.
2. My first fix passed `lastPlayheadAbsoluteMs` straight in — an absolute time in a relative
   parameter, so the segment start was added twice. That is what made it *chaotic* rather
   than merely wrong, and why times could land past the end and render black.

`segmentRelativeForAbsolute` inverts the conversion term for term, including the speed
factor. **Verified by reproduction, not reasoning:** parked at 30301ms inside clip 5,
pressed undo three times, playhead held at 30301 throughout even as `selIdx` moved 5→4.
Before the fix the same gesture produced `34180 -> 54888 (+20708)`, and 20708 is exactly
clip 8's start.

**Method note worth keeping.** Four theories preceded the right one — `updateTrimBounds`,
`resyncGapless`, `loadClip`, the compositor — and every one died to instrumentation, not
argument. SEEKDIAG showed 852 seek/load events with **not one** from an undo, which cleared
the player entirely. PREVDIAG showed the compositor faithfully drawing whatever it was told.
Only PHJUMP, logging the playhead's own setter, named the culprit. When a bug survives two
code-reading rounds, print the value and the caller.

### Black clips (`ee779c28`)
"Black clip (title card / pause)" in the Add Asset sheet, right after the Image rows,
because that is what it is. Length comes from `promptForTimeMs`, so it inherits the whole
§3c grammar for free — `2m30s`, `10.5s`, `1:30`, `90f`, `1/6 min`. Inserted AFTER the
selected clip (inserting mid-clip would mean splitting one, which is a different and
destructive operation; Split already exists for that). Named `Blank`, distinct from the
auto-blank's `Blank (auto)`, so the housekeeping pass never resizes or deletes a title card.

Device-verified end to end: 10 clips became 11 with
`clip1: name='Blank' image=true dur=5000ms muted=true`, while the project's existing
`Blank (auto)` stayed exactly as it was.

### Harness: a runner that was never running
Removing the diagnostics exposed that **`run-anchor.sh` had been failing to COMPILE** —
the `Context` stub has no `getAssets()`, so `LutManager` could not build and the runner died
before a single assertion. Confirmed pre-existing by reverting my own stub and reproducing
it. Same trap `run-caption.sh` was written up for: a test that never runs reads as coverage.

Full suite now green — **123 checks**: run-anchor 24, run-matte 25, run-fx 8, run-caption 9,
run-flextime 42, run-motionrange 15.

## Still open

**Needs JoyRaptor's design decisions (deliberately not guessed):**
- The four-triangle work: what purple-crossing-orange MEANS, then folding the marker glyphs
  into the Start/End buttons with a fade-in when animation is enabled. His instinct that the
  crossing "just inverts the animation" is unverified — I did not confirm it, and the glyph
  should not be designed until the semantics are pinned, or the ambiguity ships twice.
- SPEC_IMAGE_SEQUENCE §3b's three-equivalent-fields dialog (frame rate / per-image duration /
  total). `promptForTimeMs` is the single-value version; §3b is a bigger shape.

**Needs a repro he can produce:**
- §2b text not rendering until an empty adjustment layer was deleted.
- §2c whether audio actually extends the EXPORT (model says yes; export unverified).

**The big one, still correctly deferred:**
- Porting upstream's fMP4 seek subsystem (`FragmentedMp4IndexBuilder` + hybrid finalization).
  ~2,300 lines across 8 files in the playback core, 1,845 commits of divergence. This is what
  makes filmstrip extraction fast on FadCam recordings and would retire the ffmpeg remux.

## Device state
- **Sandbox Note 9:** current build installed and smoke-tested (opens a project, undo works,
  no crash, no diagnostic output).
- **JoyRaptor's Note 20:** last got the `04:30` build, which is now several commits behind and
  still contains all five diagnostics. **It needs a reinstall** to get tonight's four commits:
  ```
  adb -s <note20-serial> install -r app/build/outputs/apk/default/debug/app-default-arm64-v8a-debug.apk
  ```
- Sandbox project `P0 control2 plain` was used as a fixture: it now has an extra `Blank` clip
  and a few undo/redo steps applied. Nothing of JoyRaptor's was touched.
