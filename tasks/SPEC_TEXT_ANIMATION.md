# SPEC: Animated text (and animated timers)

**Status 2026-07-28 (evening): THE ANIMATION RUNS.** Model, storage, preview and export are
done and committed. What is missing is the AUTHORING UI — the tape handles and the preset
picker — so today the feature is reachable only by editing `project.json`. Read "Where this
actually is" before touching code.

Requested by the reporter directly after the countdown-timer feature landed; the two are
related and should share machinery.

---

## Where this actually is (2026-07-28, evening)

### DONE — the engine, end to end

| Piece | Where |
|---|---|
| One evaluator, one clock | `transcript/CaptionAnimator.java` |
| Phrase grouping, shared by both renderers | `transcript/CaptionPhrases.java` |
| Four fields + a clamping setter | `model/Clip.java` (`captionAnim*`) |
| Sparse write / guarded read, no schema bump | `project/ProjectStorage.java` |
| Preview honours the preset | `transcript/CaptionOverlayView.java` |
| Export honours the preset | `export/CaptionExportRenderer.java` |
| Both fed the same four values | `FaditorEditorActivity.bindCaptionData`, `CompositeExportOverlay` |
| 131 off-device checks | `tools/jvm-harness/CaptionAnimatorTest.java` |

Commits `95dc7e2` (unification), `5cc34fd` (presets + harness), `c7b6359` (live end to end).
Evidence for each is in LEDGER §3g, including the honest limit on the device proof.

**The load-bearing rule:** every preset lands on `CaptionAnimator`. If you find yourself writing
easing arithmetic anywhere else, stop.

### DONE — the original §3g defect, and why it stays fixed

The three shipping animations were implemented twice. The easing arithmetic MATCHED; the
divergence was the CLOCK — preview ran a 300ms `ValueAnimator` from the word-change EVENT
(wall-clock), export used `(sourceMs - wordStart) / 300` (media time). Those agree only when the
two clocks agree, which is exactly when it does not matter. Media time won, so the preview
changed: paused and scrubbed frames are now correct by construction.

It stays fixed because the property is pinned, not the pixels:
`CaptionAnimatorTest.clockInvariant` asserts the animation depends ONLY on elapsed media time,
so any future wall-clock term fails the harness instead of failing quietly in an export.

### DONE — decisions that were forced while building

- **The PHRASE is the animating object, not the clip.** Zones are stored on the clip as
  durations and applied against each phrase's own span, capped at half of it
  (`zoneForSpan`). Otherwise continuous speech would animate its first phrase and let every
  later one simply appear.
- **Zones are SOURCE ms.** Both renderers evaluate against source time; timeline ms would halve
  the zones on a 2× clip.
- **Presets compose with `CaptionStyle.Anim` rather than replacing it** — those three are an
  active-word emphasis (1.15× at rest), not an entrance.

### NOT STARTED — the authoring UI, which is the whole remaining feature

1. **Tape `>` `<` handles.** Precedent to copy is exact: the slide freeze-zone handles in
   `EditorTimelineView` (`hitTestFreezeHandle` :7187, `doFreezeDrag` :7199, `finishFreezeDrag`
   :7221, `drawSlideFreezeHandles` :7245, `drawFreezeMarker` :7265 — already triangle carets),
   with the undo step at `FaditorEditorActivity:1661` (`LambdaAction`). Note their hit-test is
   deliberately TIGHT and checked BEFORE the outer trim handles.
2. **Preset grid + granularity selector.** Reuse `EasePickerPopover` — a 4-column grid of tiles
   that render their own thumbnail from the evaluator, which is precisely the reporter's
   "KineMaster keyframe-curve picker, but presets". The caption drawer is built
   programmatically in `FaditorEditorActivity.buildCaptionDrawerContent` :14837; the existing
   Pop/Zoom/Bounce row is :14950 and the icon-button helper is `addCaptionActionIcon` :15179.
   **The picker must filter on `Preset.implemented`** — five presets are declared but cannot be
   expressed as a `Transform` yet.
3. Strings are HARDCODED with a `// TODO(strings)` marker here — the extraction is frozen
   behind the rebrand (`road_map.md:49`). Follow that, do not "fix" it.

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

Steps 1 and 2 of the old list are DONE (the unification is verified, and the fields persist and
round-trip). What is left is the authoring UI, in this order:

1. **Tape `>` `<` handles**, copying the slide freeze-zone handles named above. They write
   `Clip.setCaptionAnimZones(in, out)` — one setter, because the clamp is on the SUM. Darken the
   dragged-in regions, per the reporter. The handles are the timing for every preset at every
   granularity; there is no separate enable switch because zero-length zones ARE "off".
2. **Preset grid + granularity selector** in the caption drawer, filtered on
   `Preset.implemented`.
3. **Capture the large-amplitude device frame** the current evidence is missing — once the
   handles exist the playhead can be placed inside an entrance without hunting for it.
4. Only then add more presets, one at a time, as data driven by `unitProgress` — and never any
   easing arithmetic outside `CaptionAnimator`.
5. `docs/project-schema.md` needs the four `captionAnim*` rows; its stated `SCHEMA_VERSION = 11`
   is also behind the code's 12, and `GeneratedSource.freezeStartMs`/`freezeEndMs` were never
   documented there either.

**Two things still need the reporter, and should not be guessed:** which of the ten presets are
v1, and the composition order against existing keyframes.
