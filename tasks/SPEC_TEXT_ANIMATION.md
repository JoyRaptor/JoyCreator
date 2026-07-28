# SPEC: Animated text (and animated timers)

**Status 2026-07-28:** **STEP 1 (UNIFICATION) IS DONE AND IN THE BUILD.** Presets are NOT
started. Read "Where this actually is" before doing anything else.

Requested by the reporter directly after the countdown-timer feature landed; the two are
related and should share machinery.

---

## Where this actually is (2026-07-28)

### DONE — `CaptionAnimator` is now the one authority

`app/src/main/java/com/fadcam/ui/faditor/transcript/CaptionAnimator.java`

Before this, the three shipping animations (`CaptionStyle.Anim` = POP / ZOOM / BOUNCE) were
implemented **twice**: `CaptionOverlayView` (preview) and `CaptionExportRenderer` (export). The
export copy said so in its own comment — *"Approximate the preview interpolators"*.

The easing arithmetic in the two copies actually matched. **The divergence was the CLOCK:**

| | drove the animation from |
|---|---|
| preview | a 300ms `ValueAnimator` started on the word-change EVENT → **wall-clock** time |
| export | `(sourceMs - wordStart) / 300` → **media** time |

Those agree only when wall-clock and media time agree, which is exactly when it doesn't matter.
On a **2× clip** the same animation covered a different span in the file than on screen; a
**paused** preview kept animating while media time stood still; and a **scrub** re-triggered the
entrance instead of showing the frame that would actually be exported.

**Media time won** — the exported frame is ground truth, so the preview changed. The
`ValueAnimator` is gone; `CaptionOverlayView.setActiveSourceMs()` now asks `CaptionAnimator` for
the value at the current playhead. That makes paused-preview and scrub correct *by construction*
rather than by matching two implementations. Both renderers now call
`CaptionAnimator.transform(...)`; the duplicated `switch`, the duplicated `lerp`, the duplicated
`EMPHASIS_MS` and the three interpolator imports are deleted.

**This is the load-bearing part of the whole feature.** Every preset below lands on that one
evaluator. Do not add a second path — if you find yourself writing easing arithmetic anywhere
other than `CaptionAnimator`, stop.

### DONE — the groundwork the presets need

Already in `CaptionAnimator`, unused by any UI yet:

- `enum Granularity { LETTER, WORD, SENTENCE, BLOCK }` — see "Granularity" below.
- `unitProgress(mediaMs, itemStartMs, itemEndMs, inZoneMs, outZoneMs, unitIndex, unitCount)` —
  the tape-driven timing model, see below. Returns one signed 0→1→1→0 number that every preset
  can be driven from, with units staggered across their zone so the effect reads as a sweep.
- `Transform { scale, dy, alpha }` — deliberately multiplicative/additive so presets **compose
  with** keyframed opacity/scale instead of replacing them.

### NOT STARTED

The presets themselves, the preset-picker UI, the tape handles, and per-glyph layout.

---

## The timing model — the reporter's, 2026-07-28 (BINDING)

> "the in/out carets show how long it will take for the animation to play in, and how long, if at
> all, to play out. If the carets are at the end, then there will be no animation. If they are
> brought all the way both into the centre, then everything animates in, and then as soon as it's
> in, it starts animating out. But all the animations, for every word or letter or the whole body
> — however it is parsed — are moved and controlled based off of those."

So:

- **The handles ARE the timing.** They are not a per-preset parameter. Every preset, at every
  granularity, is driven by the same two numbers: in-zone length and out-zone length.
- **Handles at the ends ⇒ no animation.** Zero-length zones are the natural "off", which is why
  there is no separate enable switch.
- **Handles fully to the centre ⇒ animates in, and begins animating out the instant it is in.**
  This falls out of the model rather than being special-cased.
- The zones are measured against **the object's own tape span**, so lengthening a clip stretches
  its animation rather than leaving it stranded at the head.

`CaptionAnimator.unitProgress` implements exactly this. Precedent for storing the zones:
`GeneratedSource.freezeStartMs` / `freezeEndMs` already model a variable in/out on AI slides —
reuse that shape rather than inventing one.

**UI:** the tape shows `>` `<` handles at each end; dragging them inward darkens those regions to
show the in/out zones.

## Granularity — four levels (BINDING, reporter 2026-07-28)

`LETTER` · `WORD` · `SENTENCE` · `BLOCK` (the whole text body).

The reporter's reasoning, which is the design rationale to preserve:

> "most of these animations can be at any of those, and I'm sure some people would want a
> paragraph to fly in, and other people would want single letters to fly in for a completely
> different effect. One is more like a PowerPoint presentation, and the other is more like a
> cinematic commercial."

So granularity is a **setting orthogonal to the preset**, not a property of it. One preset ×
four granularities = four quite different effects, which is most of the expressive range for
very little extra code.

`WORD` is what ships today. `LETTER` is the cost centre — per-glyph layout and transforms in
BOTH paths; budget for it.

## Why it belongs on the caption engine, not the text overlay

The reporter's own reasoning for putting timers on the caption render path applies double here:

> "captions has animations already. a clock ticker is more interesting with a pop or spring
> every time a second or minute ticks. and the stroke and background and highlight font
> colors have lots of style control we already made."

There is a second, harder reason. **Text overlays render as Android `TextView`s, which cannot
transform individual characters.** The caption renderer is a custom canvas renderer that already
measures and draws word by word (`measureText(word)` … `drawText(word, x, baseY, textPaint)` in
`CaptionOverlayView` / `CaptionExportRenderer`). Per-letter animation is that same technique one
level finer. So the caption engine is the ONLY surface in the app where per-glyph animation is
reachable without building a new renderer.

## Reporter's design

1. An icon in the text dialog — an "A" in motion with motion lines.
2. Opens a GRID of presets (like KineMaster's keyframe-curve picker, but presets not curves).
3. The tape `>` `<` handles described under "The timing model" above.
4. A granularity selector (letter / word / sentence / block) applying to all presets.

## Presets

Reporter's five:
- **Typewriter** — words or letters simply appear.
- **Ghost** — fade in while sliding a few px horizontally, shrinking, un-blurring, and going
  transparent→opaque. Stacked so letters seem to materialise out of smoke.
- **Fade in** — the simple one.
- **Matrix** — each slot ticks through nonsense glyphs (kanji, digits) before settling on the
  correct letter as it prints.
- **Beam in** — letters start 200% tall / 5% wide and normalise over a few frames as they are
  written in.

Five more proposed, biased toward what the existing style fields already support:
- **Odometer / counter-roll** — each glyph rolls vertically through a few characters and
  lands. Pairs naturally with the timer.
- **Mask wipe** — a directional mask sweeps across; letters revealed by clipping rather than
  opacity, so it reads as printed rather than faded.
- **Neon flicker** — the existing glow + stroke fields flicker on like a sign. Nearly free
  given what is already on the model.
- **Rise with overshoot** — words slide up past the baseline and settle. The lower-third
  standard.
- **Unscramble** — letters start displaced and settle into place (positional cousin of matrix).

Plus: the three that already exist (POP / ZOOM / BOUNCE) are folded in as presets, so the two
sets are one vocabulary rather than two. **Still to confirm with the reporter: which of the ten
are v1.**

## Design constraints (learned the hard way)

- **ONE AUTHORITY.** ✅ Now enforced by `CaptionAnimator`. Anything computed twice will drift,
  and drift in an intro title is invisible until someone watches the finished export — which is
  precisely what had already happened here before 2026-07-28.
- **Compose, don't replace.** Presets must multiply over existing keyframed opacity/scale/
  rotation with a stated order, or animating text silently overrides a user's keyframes.
  `Transform` is shaped for this; the composition ORDER is still undefined — define it.
- **Retrigger on value change, not just on time.** A timer wants a pop on each TICK, which is
  an event ("the displayed string changed"), not a function of elapsed time. Design this in
  from the start — it is painful to retrofit, and it is the thing that makes the timer feel
  alive rather than merely correct. **Not yet addressed in `CaptionAnimator`.**
- **Per-letter is the cost centre.** Word-level stays cheap; per-glyph means per-glyph layout and
  transforms in both preview and export. Budget accordingly.

## Open questions — three answered, one still open

- ~~Does per-glyph layout need RTL / combining marks?~~ **LTR-only for v1.**
- ~~Do animation zones belong on the item or the preset instance?~~ **On the item**, mirroring
  `GeneratedSource.freezeStartMs` / `freezeEndMs`.
- ~~Does export need a frame-rate-independent formulation?~~ **Time-based**, so the fixed-30fps
  export path (`TimerText.DEFAULT_FPS`) and the live preview agree by construction. Already how
  `CaptionAnimator` works.
- **STILL OPEN:** which of the ten presets are v1, and the exact composition order against
  existing keyframes.

## Suggested next steps for whoever picks this up

1. **Verify the unification held** before building on it: same `(word, sourceMs)` must give the
   same transform in preview and export. The A/B export frame-diff method is the tool
   (see the memory note on absolute-geometry A/B diffs — symmetric proofs miss flips).
2. Persist `granularity` + `inZoneMs` / `outZoneMs` on the caption/text item and round-trip them
   through `ProjectStorage` (bump the schema note in `docs/project-schema.md`).
3. Wire the tape `>` `<` handles to those two fields — the visible half of the timing model.
4. Only then add presets, one at a time, each as data driven by `unitProgress` — no new easing
   arithmetic outside `CaptionAnimator`.
