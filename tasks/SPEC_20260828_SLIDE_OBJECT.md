# SPEC — The timed slide object: styled cards on the transcript's clock

**Written:** 2026-08-28 · **For:** an external agent · **Owner:** JoyRaptor.

Claim a lane in `tasks/LANES.md` **named after this spec**. Rule 6 (never run gradle —
read `build.log`) and the WORKING-TREE HAZARD (`git add` each file as you write it, and
**stage by explicit path, never `git add -u <dir>`** — that has already swept other
agents' work into the wrong commit) both apply.

---

## 1. What this is for

JoyRaptor is putting ~136 scripture references on screen over a 7-minute song. Captions
cannot do it, and the reason is specific:

> "I don't have any control over making some words italic or bold or different colors.
> Like, I can't say, okay, the scripture reference at the beginning is going to be bold
> and such and such color while the text is going to have this other font in the body."

A caption carries **one style per cue**. He needs a **reference line** and a **body**
with different weight, size and colour, in one card, 136 times, each landing on a
musical beat. He also wants to be able to pause anywhere and read what is on screen.

His own framing of the shape, which this spec follows:

> "perhaps it'd be good to have a drop-down drawer that has an advance next slide
> button, and you could live retime the slides backwards and forwards. and it would drop
> keyframes on the object. Or if you had an AI go in and give the timing script, those
> that timing script would show up as keyframes, and you could nudge those keyframes
> around. And you could have it be by absolute timing or by clip timing."

And the constraint that kills the existing approach:

> "it should be something that's recorded like a video and not like thousands of PNGs."

---

## 2. Why the existing generated-slide path will not do

`TextOverlayItem.isGeneratedSlide()` + `GeneratedSource.renderSequenceDir` already
render authored HTML — by baking a PNG **sequence** at `SlideRenderer.RENDER_FPS = 30`.
For a 7-minute object that is **~12,600 images** to render, store and composite. It is
a non-starter on time, disk and export speed.

**The content only changes 136 times in 7 minutes.** Rasterise on a CUE BOUNDARY, not
on a clock. Hold ONE bitmap and swap it when the cue changes. This is the same move that
took the export from 15 minutes to 1m38s (`1d00f159`, `53ef0811`): stop doing per-frame
work for something that changes rarely.

---

## 3. The model

New: `com.fadcam.ui.faditor.slides.SlideDeck` (pure model, no `android.*` beyond
annotations, so the JVM harness can exercise it — follow `CaptionPhrases`/`CaptionFit`).

```
SlideDeck
  id, label
  timeBase: ABSOLUTE | CLIP        // §1 "absolute timing or by clip timing"
  List<Slide> slides
  cueAtMs(long t) -> int           // which slide is on screen at t, or -1

Slide
  long startMs                     // the cue boundary; end = next slide's start
  String html                      // or a small styled-run model, see §3.1
```

### 3.1 Content: styled runs, not a browser

**Do not embed a WebView per frame.** A WebView cannot be composited into the export
pipeline cheaply or deterministically, and preview/export parity is the whole game here.

Two acceptable routes; pick one and say which:

- **(a) Restricted HTML → styled runs.** Parse a small, documented subset (`<b> <i> <br>
  <span style="color|font-size|font-family">`, `<p>`) into a list of runs, and draw with
  `StaticLayout`/`Canvas`. Deterministic, fast, identical in both renderers.
- **(b) A styled-run model with no HTML at all**, and let the AI emit JSON.

(a) is preferred because JoyRaptor wants to author with an AI: *"I want you to make an HTML
file that it's a slideshow"*. Accepting a restricted HTML subset means his Claude output
drops straight in. **Document the subset in the spec file you write back.**

If a run's font names a `file:` key, resolve it through `com.fadcam.ui.faditor.text.FontLibrary`
— imported fonts must work here as they do for text and captions.

---

## 4. Timing is keyframes, because that plumbing exists

JoyRaptor: *"it would drop keyframes on the object"*. Do not invent a timing system.

- A slide boundary IS a keyframe on the deck object.
- Reuse the keyframe conventions already in the editor. Read `Clip.KEYFRAME_SNAP_MS`
  (80ms) and `opacityKeyframeIndexAt` in `model/Clip.java` first: **one window, one
  predicate, shared by the write, the indicator and the jump.** Three private tolerances
  is exactly the bug that was fixed there, and the same trap is waiting here.
- Nudging a boundary retimes that slide. Boundaries must never cross each other.

### 4.1 The drawer

- **Advance next slide** — drops a boundary at the playhead. This is the live-retiming
  gesture: play the song, tap on each beat.
- **Prev / next** boundary navigation, and a **delete boundary** control. Mirror the
  opacity keyframe helper (`opacity_kf_prev` / `opacity_kf_onkeyframe` /
  `opacity_kf_delete` / `opacity_kf_next`) — it is the established pattern and JoyRaptor has
  asked for "the official helper" once already.
- **Import timing script** — accept a pasted or picked list of `startMs` values (and
  optionally content) and turn them into boundaries in one undo step.
- Vertical space in these drawers is scarce; JoyRaptor has said so more than once.

---

## 5. Rendering, and parity

ONE renderer used by both sides, exactly as `CaptionFit` is shared:

- **Preview:** a view that draws the CURRENT slide's cached bitmap.
- **Export:** a `BitmapOverlay` (see `export/CompositeExportOverlay` for the pattern,
  including the **ping-pong buffer** trick — a fresh full-frame copy per frame was a real
  measured cost, see the comment there).

Rules:

1. Rasterise only when the cue index changes, or the box/style changes. Cache one bitmap.
2. The export runs **per frame in a separate process**. Anything computed per frame there
   is a bug; see `SEQ_FRAMES` and commit `53ef0811`.
3. Preview and export must resolve the cue with the same function on the same clock.

---

## 6. Acceptance

1. A deck of 136 slides over a 7-minute project renders in preview and export, and a
   frame grabbed at the same timestamp from each **matches** (`ffmpeg -lavfi psnr`).
   Report average and minimum.
2. Export time for that project is not materially worse than the same project without the
   deck. Report both wall clocks. **This is the requirement that kills a per-frame
   rasteriser**, so measure it honestly.
3. Tapping "advance next slide" during playback lays boundaries at the tapped times, and
   they can then be nudged.
4. A slide with a bold coloured reference line and a plain body renders with both styles.
5. An imported font (`file:` key) renders on a slide.
6. Undo: one gesture, one undo step.

---

## 7. Traps

- **7.1** `getSelectedClip()` silently returns `getClip(0)` when nothing is selected — on
  a music project that is the auto-blank black spacer. Use `clipUnderPlayhead()`.
- **7.2** The auto-blank ("Blank (auto)") is a real clip created so overlays and audio
  past the last video clip still render. A deck will usually sit over it. Do not filter
  it out, and do not let `syncTrailingBlankForOverhang` fight your object: read that
  method before touching timing.
- **7.3** Composition time is not editor time. A transition SHORTENS the clips it
  straddles, so overlays authored in editor time render late by exactly one transition
  unless corrected. See `editorTimeOffsetMs` in `PipFrameOverlay`/`CompositeExportOverlay`.
- **7.4** Never `perl -i` without `-CSD`; check `grep -c 'â' <file>` is 0 before commit.
- **7.5** `FaditorEditorActivity.java` is 36k lines and shared. Keep your additions in new
  files and touch it only for the drawer glue.

---

## 8. Out of scope

Animating within a slide. Video inside a slide. Replacing captions. Changing the
generated-slide PNG path that already exists (leave it alone).

---

## 9. Reporting

Real `git diff --numstat`. `build.log`'s last line with its mtime. The §6 numbers in
full, especially §6.2. Say which checks need JoyRaptor's eye rather than claiming them.
