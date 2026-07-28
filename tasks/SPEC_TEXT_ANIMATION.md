# SPEC: Animated text (and animated timers)

**Status 2026-07-28 (late): THE FEATURE IS AUTHORABLE.** Engine, storage, preview, export AND
the authoring UI are done and committed. `project.json` is no longer the only way in. What is
NOT done is the on-device capture of a large-amplitude frame — no device was attached when the
UI landed, so that evidence is still outstanding. Read "Where this actually is" first.

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

### DONE — the authoring UI

| Piece | Where |
|---|---|
| Tape `▶` `◀` carets + zone tint (amber) | `EditorTimelineView.drawCaptionAnimHandles` / `hitTestCaptionAnimHandle` |
| Caret ↔ zone mapping, as pure math | `CaptionAnimator.caretFractionForZone` / `zoneFromCaretFraction` |
| The caret's meaningful range | `CaptionPhrases.maxUsefulZoneMs` |
| Preset grid + granularity, tiles drawn from the evaluator | `TextAnimPickerPopover` |
| "A in motion" entry point + Motion row | `FaditorEditorActivity.makeTextMotionIcon`, `buildCaptionDrawerContent` |
| Undo for zones / preset / granularity | `applyCaptionAnimZones`, `applyCaptionAnimPreset`, `applyCaptionAnimGranularity` |
| 145 off-device checks (was 131) | `tools/jvm-harness/CaptionAnimatorTest.java` |

The carets copy the slide freeze-zone precedent exactly — tight hit-test, checked BEFORE the
outer trim handles, `LambdaAction` undo — with ONE deliberate departure: **the freeze carets
convert px through `getTrimmedDurationMs()` (timeline ms), and these must not.** The caption
zones are SOURCE ms, so `finishCaptionAnimDrag` never divides by the speed multiplier. Copying
that line unchanged would have halved every zone on a 2× clip, which is precisely the §3g class
of defect one level over.

Strings are HARDCODED with `// TODO(strings)` — the extraction is frozen behind the rebrand
(`road_map.md:49`). Follow that, do not "fix" it.

### DONE — decisions forced while building the UI, each with its reasoning

- **The caret's travel is scaled to what actually changes, not to the tape.** The zones are
  absolute durations on the CLIP but spent against each PHRASE, and phrases are short while clips
  are long. A caret mapped linearly onto the tape would put its whole useful range in the first
  few percent — on a 60s clip every position from ~3% to the centre saturates every phrase, so
  94% of the travel would be dead and the control would feel broken. Full inward travel therefore
  maps to `CaptionPhrases.maxUsefulZoneMs()` (half the longest VISIBLE phrase). Both endpoints of
  the reporter's model stay exactly true — caret at the end is off, caret at the centre means
  every phrase finishes arriving as it starts leaving — and every position between them is
  distinct. Pinned by `caretMapping()` in the harness.
- **Measured on the TRIMMED window, not the whole source transcript.** A phrase outside the trim
  is not drawn, so it must not scale a handle against text the user cannot see.
- **Picking a preset with both carets at the ends seeds an entrance.** Zero zones ARE the off
  state — that is the timing model and why there is no enable switch — but it means every tile in
  the picker would apply cleanly and change nothing on a fresh clip. Choosing a preset is an
  explicit request to animate, so it seeds half the usable entrance range, in the SAME undo step,
  and the carets then show what happened.
- **The carets appear only while the caption drawer is open.** A captioned clip is commonly
  selected for reasons unrelated to animating it, and two extra carets competing with the trim
  handles for the tape edges is clutter the rest of the time.
- **The emphasis row is relabelled "Highlight".** Pop/Zoom/Bounce are the active-word emphasis;
  the new row is the entrance/exit. Both being called "Animation" read as if one were redundant.

### KNOWN GAP — GHOST ships without its blur

`Transform.blurPx` is computed by GHOST and **consumed by no renderer**, so GHOST is currently
slide + shrink + fade. The picker's thumbnail deliberately omits the blur to match, because a
tile that advertises softness the app never draws is the exact failure a thumbnail-from-the-
evaluator exists to prevent.

This is recorded rather than quietly fixed because the obvious fix is a trap: `BlurMaskFilter` is
ignored on a hardware-accelerated canvas, so adding it to the preview alone does nothing on
screen while the export — which draws into a `Bitmap`, i.e. software — really would blur. That is
a preview/export divergence no frame-diff of the preview would catch. Blurring the preview needs
`LAYER_TYPE_SOFTWARE` on the overlay, which costs every frame of playback. It is a decision with
a price, not an oversight to patch. Full reasoning is on `CaptionAnimator.Transform#blurPx`.

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

## Open questions — all answered; one wants the reporter's sign-off

- ~~Does per-glyph layout need RTL / combining marks?~~ **LTR-only for v1.**
- ~~Do animation zones belong on the item or the preset instance?~~ **On the item**, mirroring
  `GeneratedSource.freezeStartMs` / `freezeEndMs`.
- ~~Does export need a frame-rate-independent formulation?~~ **Time-based**, so the fixed-30fps
  export path (`TimerText.DEFAULT_FPS`) and the live preview agree by construction. Already how
  `CaptionAnimator` works.
- ~~Which of the ten presets are v1?~~ **The six that are implemented** — NONE, TYPEWRITER, FADE,
  RISE, GHOST, BEAM. This was never really a taste question: the picker filters on
  `Preset.implemented`, and the other five return identity, so shipping them would ship five
  tiles that do nothing. The taste question that remains is which of the five to BUILD next, and
  that one is genuinely the reporter's.
- ~~The exact composition order against existing keyframes.~~ **Answered by reading what both
  renderers already do, and now written down** (below). It was undefined in prose, not in code —
  and the two paths already agree, which is the part that mattered.

### Composition order (as implemented in BOTH renderers — reporter to confirm the taste)

`CaptionOverlayView.drawUnit` and `CaptionExportRenderer.drawUnit` compose identically:

1. **A caption-style keyframe selects the style** at the current time. That style carries
   `Anim` (POP / ZOOM / BOUNCE), so a keyframe changes WHICH emphasis is in play.
2. **The preset transform is computed first** from `unitProgress` — the tape carets' timing.
3. **The active word's emphasis multiplies over it**: `scaleX/scaleY` multiply, `dy` adds. The
   preset's `alpha` alone gates whether the unit draws at all.

So the preset never replaces the emphasis, and a keyframed style change never fights the
entrance: one is a transient arrival, the other a permanent 1.15× on the spoken word. **The order
is not in question — it is the same in both paths and pinned by the harness. What the reporter
may still want to change is the FEEL** (e.g. whether emphasis should be suppressed during an
entrance rather than multiplied into it).

## Suggested next steps for whoever picks this up

Engine, persistence and the authoring UI are all DONE. `docs/project-schema.md` is caught up too
— the four `captionAnim*` rows, the `SCHEMA_VERSION` correction (11 → 12) and the previously
undocumented `GeneratedSource.freezeStartMs`/`freezeEndMs` all landed with the UI.

What is left, in order:

1. **Capture the large-amplitude device frame the evidence is still missing.** This is the ONLY
   unfinished item from the previous handoff. It could not be done when the UI landed because no
   device was attached. It is now much easier than it was: open the caption drawer, drag the `▶`
   caret well in, and the entrance is a known span at a known place instead of something to hunt
   for. The existing proof (2901 px changed, bounding box exactly the caption text, zero pixels
   elsewhere) shows the plumbing works; it does not show the LOOK, because the sampled frame sat
   at the zone saturation point where progress is 1 by construction.
2. **Walk the UI once on-device for the things a harness cannot see:** that the amber carets are
   grabbable without stealing edge grabs from the trim handles, that the popover's tiles read as
   distinct at 60dp, and that LETTER granularity on a long phrase does not drop frames (it is the
   cost centre — per-glyph layout in both paths).
3. **Decide GHOST's blur** — see "KNOWN GAP" above. It is a real decision with a performance
   price, not a bug to fix in passing.
4. Only then add more presets, one at a time, as data driven by `unitProgress` — and never any
   easing arithmetic outside `CaptionAnimator`. The five unimplemented ones each name what they
   need; **which to build next is the reporter's call.**
5. The retrigger-on-value-change requirement (a timer wanting a pop on each TICK) is **still not
   addressed in `CaptionAnimator`** — it is an event, not a function of elapsed time, and the
   spec warns it is painful to retrofit. It is untouched by this work.
