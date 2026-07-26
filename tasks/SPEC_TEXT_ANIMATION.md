# SPEC: Animated text (and animated timers)

**Status 2026-07-26:** NOT STARTED — captured from a design conversation so it is not lost.
Requested by the reporter directly after the countdown-timer feature landed; the two are
related and should share machinery.

## Why it belongs on the caption engine, not the text overlay

The reporter's own reasoning for putting timers on the caption render path applies double
here:

> "captions has animations already. a clock ticker is more interesting with a pop or spring
> every time a second or minute ticks. and the stroke and background and highlight font
> colors have lots of style control we already made."

There is a second, harder reason. **Text overlays render as Android `TextView`s, which cannot
transform individual characters.** The caption renderer is a custom canvas renderer that
already measures and draws word by word (`measureText(word)` … `drawText(word, x, baseY,
textPaint)` in `CaptionOverlayView`). Per-letter animation is that same technique one level
finer. So the caption engine is the ONLY surface in the app where per-glyph animation is
reachable without building a new renderer.

## Reporter's design

1. An icon in the text dialog — an "A" in motion with motion lines.
2. Opens a GRID of presets (like KineMaster's keyframe-curve picker, but presets not curves).
3. The tape shows `>` `<` handles at each end; dragging them inward darkens those regions to
   denote the in/out animation zones. **Precedent already exists**: `GeneratedSource` carries
   `freezeStartMs`/`freezeEndMs` for exactly this variable in/out on AI slides — reuse that
   model rather than inventing one.
4. A word-level / letter-level toggle applies to all presets.

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

Plus: fold in the caption entrance animations that already exist, so the two sets are one
vocabulary rather than two.

## Design constraints (learned the hard way this session)

- **ONE AUTHORITY.** A pure `(spec, t) -> per-glyph transform[]` function that preview and
  export both call, exactly like `TimerText` and `LayerPreviewController.effectiveOverlayVolume`.
  Anything computed twice will drift, and drift in an intro title is invisible until someone
  watches the finished export.
- **Compose, don't replace.** Presets must multiply over existing keyframed opacity/scale/
  rotation with a stated order, or animating text silently overrides a user's keyframes.
- **Retrigger on value change, not just on time.** A timer wants a pop on each TICK, which is
  an event ("the displayed string changed"), not a function of elapsed time. Design this in
  from the start — it is painful to retrofit, and it is the thing that makes the timer feel
  alive rather than merely correct.
- **Per-letter is the cost centre.** Word-level could stay cheap; per-glyph means per-glyph
  layout and transforms in both preview and export. Budget accordingly.

## Open questions

- Does per-glyph layout need to handle RTL / combining marks, or is LTR-only acceptable v1?
- Do animation zones belong on the item (like `freezeStartMs`) or on the preset instance?
- Does the export path need a frame-rate-independent formulation (it renders at a fixed
  30fps — see `TimerText.DEFAULT_FPS`) or is time-based enough?
