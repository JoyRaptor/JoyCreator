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
- ~~**Zones are SOURCE ms.**~~ **SUPERSEDED 2026-07-29 (`d71b614`): zones are a FRACTION of each
  LINE, 0…0.5, and have no units at all.** The old note read "both renderers evaluate against
  source time; timeline ms would halve the zones on a 2× clip" — true, and it required every call
  site to remember which base it was in. A fraction is correct in both bases by construction, so
  the hazard is removed rather than managed. It is also the only form that can be authored ONCE
  for a whole video, which is why the user asked for it.
- **Presets compose with `CaptionStyle.Anim` rather than replacing it** — those three are an
  active-word emphasis (1.15× at rest), not an entrance.

### DONE — the authoring UI

| Piece | Where |
|---|---|
| **Caption timing: In/Out sliders, 0–50% of every line** | `FaditorEditorActivity.addCaptionAnimRangeControl` / `makeCaptionAnimSlider` |
| Live preview during a drag; ONE undo step on release | `previewCaptionAnimZones` + 4-arg `applyCaptionAnimZones` |
| Travel ↔ stored fraction, as pure math (shared by slider and caret) | `CaptionAnimator.caretFractionForZone` / `zoneFromCaretFraction` |
| Is there anything here to time at all | `CaptionPhrases.hasAnimatableSpan` |
| Tape `▶` `◀` carets + zone tint (amber) — **PARKED, no caller; for TEXT BOXES** | `EditorTimelineView.drawCaptionAnimHandles` / `hitTestCaptionAnimHandle` |
| Preset grid + granularity, tiles drawn from the evaluator | `TextAnimPickerPopover` |
| "A in motion" entry point + Motion row | `FaditorEditorActivity.makeTextMotionIcon`, `buildCaptionDrawerContent` |
| Undo for zones / preset / granularity | `applyCaptionAnimZones`, `applyCaptionAnimPreset`, `applyCaptionAnimGranularity` |
| 160 off-device checks (131 → 145 → 158 → 160) | `tools/jvm-harness/CaptionAnimatorTest.java` |

**Captions are timed from the style drawer, not the tape** (user, 2026-07-29). He drove the carets
and they worked; he rejected them for captions anyway, because captions ride long videos and
arrive line after line: *"to get a fifty percent fade in, fifty percent fade out, I'm gonna be
having to do a lot of dragging over perhaps a thirty minute clip. And that just won't do."*
The carets remain the right instrument for a TEXT BOX — one object, one visible span, one gesture
— and are kept intact and uncalled until that path exists.

The freeze-caret precedent had ONE deliberate departure that is now **moot**: the freeze carets
convert px through `getTrimmedDurationMs()` (timeline ms) and the caption zones must not, because
they were source ms. A fraction has no base, so `finishCaptionAnimDrag` has nothing to divide by
and the warning was deleted rather than restated.

Strings are HARDCODED with `// TODO(strings)` — the extraction is frozen behind the rebrand
(`road_map.md:49`). Follow that, do not "fix" it.

### DONE — decisions forced while building the UI, each with its reasoning

- ~~**The caret's travel is scaled to what actually changes, not to the tape.**~~ **OBSOLETE
  (`d71b614`).** That scaling — full travel mapping to `CaptionPhrases.maxUsefulZoneMs()`, half
  the longest visible phrase — existed only because the zones were absolute durations on the CLIP
  spent against each PHRASE, so a linear map onto a 60s tape left ~94% of the travel dead. A
  fraction is already per-line: full travel is 0.5 at every phrase length, there is no scale
  factor, and `maxUsefulZoneMs` is deleted. Both endpoints of the user's model still hold exactly,
  and now they hold at EVERY line length rather than one clip's longest phrase — which is what
  makes "set it once for the whole video" possible.
- **Whether to offer the control at all asks the EVALUATOR, not the span.**
  `hasAnimatableSpan()` tests "does full travel buy a non-zero zone". `spanMs` floors every phrase
  at 1ms so a degenerate ASR word (`startMs == endMs`) still draws, and on a 1ms span the floor
  cap in `zoneForSpan` returns 0 at every setting — a "span > 0" test would offer a control that
  provably cannot do anything. Swept over spans 1…300 in the harness.
- **Measured on the TRIMMED window, not the whole source transcript.** A phrase outside the trim
  is not drawn, so it must not be what earns a clip a timing control.
- **Picking a preset with both zones at zero seeds an entrance** (0.25, half of full travel).
  Zero zones ARE the off state — that is the timing model and why there is no enable switch — but
  it means every tile in the picker would apply cleanly and change nothing on a fresh clip.
  Choosing a preset is an explicit request to animate, so it seeds, in the SAME undo step, and the
  Timing sliders then show what happened.
- ~~**The carets appear only while the caption drawer is open.**~~ **They no longer appear for
  captions at all** (`665d543`). The reasoning behind the original rule — a captioned clip is
  commonly selected for reasons unrelated to animating it, and two extra carets competing with the
  trim handles for the tape edges is clutter — is why they should stay opt-in when text boxes
  revive them.
- **The slider previews live but records one undo step per GESTURE.** A `SeekBar` fires on every
  pixel; one undo entry per pixel would bury the user's real history under a hundred of its own.
  Hence `previewCaptionAnimZones` (no undo, no autosave) plus a four-argument
  `applyCaptionAnimZones` that is handed the value from BEFORE the gesture — by release, the
  clip's own value is the preview, not the undo target. Proved on device: undo count 10 → 13
  across exactly three slider gestures.
- **The readout says "of each line", not a bare percentage.** A bare "50%" invites reading it as
  half the clip, which is the exact misunderstanding the model change exists to prevent. At 50/50
  it appends "· in ends as out begins", stating the model's headline property where the user
  actually arrives at it.
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

~~1. Capture the large-amplitude device frame.~~ **SUPERSEDED.** The user drove the whole feature
   himself on 2026-07-29 — *"animations per word and per letter look great! i tried all styles"* —
   which answers the question the frame was a proxy for, better than the frame would have.
~~2. Walk the UI on-device.~~ **DONE 2026-07-29**, with two of its three questions closed:
   - **LETTER granularity holds frame rate. CLOSED.** Matched 5s preview runs over the same
     captioned span, changing only `captionAnimGranularity`: LETTER 270 frames / **33.70%** janky
     / 12ms median / 27ms p95; BLOCK 273 / **33.70%** / 12ms / 30ms. Identical, with LETTER
     marginally faster at the tail. The conditions really differed — under BLOCK the whole phrase
     is drawn at once, under LETTER only the last few glyphs were on screen — which is the
     positive control. Residual 33.7% jank is the editor's baseline, identical in both arms.
     Caveats: PREVIEW only, and a phrase is capped at six words so ~30 glyphs is the worst case
     by construction.
   - ~~Are the carets grabbable without stealing trim-handle grabs?~~ **Moot for captions** — they
     no longer draw. It returns when text boxes do.
   - **The tiles still do NOT read as distinct at 60dp.** Type vs Fade: mean **1.08/255**, 2.9% of
     pixels differing by >8; control None vs Type **26.38 / 17.7%** on the same instrument. The
     tiles are static poses (four screenshots 0.4s apart are byte-identical). **Still open.**

**What is actually left, in order:**

~~1. Make the preset tiles legible at 60dp.~~ **CLOSED, twice over.** The static-pose half was
   fixed in `4a1ea41` (tiles now animate, driven by the evaluator). The residual freeze-frame gap
   was then **accepted by the user, 2026-07-30** — *"the animations you have, I think, look great"*
   — as motion-only distinction. **Do not "fix" it with a decorative cue:** the tiles are
   trustworthy precisely because they can only advertise motion the renderers actually produce.
2. **The text-box path into this panel** — the other half of the user's 2026-07-29 direction, and
   a BUILD rather than a context branch: `TextOverlayItem` has no `CaptionStyle` and no animation
   zones, and nothing opens `caption_drawer` for a text box. The `▶` `◀` carets are parked intact
   waiting for exactly this.
3. **Measure the EXPORT path's per-glyph cost.** Only the preview has been measured.
4. **Decide GHOST's blur** — see "KNOWN GAP" above. A real decision with a performance price, not
   a bug to fix in passing.
5. Add more presets, one at a time, as data driven by `unitProgress` — and never any easing
   arithmetic outside `CaptionAnimator`. ~~which to build next is the reporter's call~~ —
   **ANSWERED: MATRIX, UNSCRAMBLE, ODOMETER, MASK_WIPE, NEON_FLICKER** (user, 2026-07-30).
   **MATRIX shipped `c85a7c5`; UNSCRAMBLE shipped 2026-07-29. ODOMETER is next and is designed
   below.**

## ODOMETER — design, derived 2026-07-29, NOT yet built

Written down before coding because the last two presets both had blocker notes that were wrong in
opposite directions, and because this one has a **scope question that belongs to the user**.

**What it is.** Each character slot is a wheel. As the unit arrives the wheel spins down through
filler characters and lands on the real one — a mechanical odometer. The half-rolled state, where
the outgoing character is sliding out of the top of the slot while the incoming one rises into the
bottom, is the whole look.

**Re-derived blocker (the ledger's note was right this time, unlike UNSCRAMBLE's).** It needs two
things a `Transform` plus `substituteUnit` cannot give:
1. **Two draws per unit.** At any instant a rolling slot shows TWO characters at different vertical
   offsets. `substituteUnit` returns one string and `Transform` is one geometry, so neither can
   express it. **This is a genuine third channel** — unlike UNSCRAMBLE's, which turned out to be a
   parameter.
2. **A clip rect per slot**, or the outgoing character bleeds into the line above. Caption lines are
   stacked at `1.15 × (descent − ascent)`, so there is not enough leading to hide it, and on a
   single-line phrase it would spill outside the pill.

**Why the caption half is nevertheless straightforward.** Both caption renderers already draw
per-glyph at LETTER granularity, and `drawUnit` already receives `x`, `baseY` and `w` — so the slot
rect is computable at the draw site today, and both methods already `save()`/`restore()` around each
unit. Adding `clipRect` + a second `drawText` inside that existing bracket is a small, symmetric
change at the two caption sites. `Canvas.clipRect` on a rect is cheap, and ~30 glyphs is the worst
case by construction (a phrase is capped at six words).

**Proposed channel**, mirroring `substituteUnit`'s shape and determinism rules:
```java
/** What a slot shows mid-roll: two characters and how far through the step the wheel is. */
public static Roll rollUnit(Preset p, String text, float progress, int unitIndex)
// Roll { String incoming; String outgoing; float phase; }  // phase 0..1
```
with the wheel position `wheel = (1 − decelerate(progress)) × ROLL_STEPS`, `incoming = charAtWheel(
floor(wheel))`, `outgoing = charAtWheel(floor(wheel) + 1)`, `phase = wheel − floor(wheel)`. The
renderer draws `incoming` at `dy = phase × slotH` and `outgoing` at `dy = (phase − 1) × slotH`, both
clipped to the slot. `charAtWheel(0)` is the REAL character, so at `progress = 1` the wheel is at 0,
the real character sits at `dy = 0` and its neighbour is clipped fully out of view — it lands
exactly, with no special case. Filler characters come from the same deterministic `mix` as MATRIX,
for the same reason: `Math.random()` per frame would make the preview and the export roll different
characters. Same length in, same length out, so layout cannot reflow.

**THE SCOPE QUESTION — the user's call, do not guess it.** **The text-box preview is a `TextView`**
(`TextOverlayLayer:211` sets a string on it). One view, one string: it cannot draw two glyph rows
clipped to a slot. So ODOMETER cannot run on a text box in preview, while the export
(`CompositeExportOverlay`, `canvas.drawText`) could — which is the exact preview/export divergence
this whole area exists to prevent. Three ways out:
- **(a) Gate it, and ship captions-only.** Cheapest and follows an existing precedent: the picker
  already takes `allowedGrans` for exactly this reason ("this picker never offers a control that
  provably will not do what it says"), so an `allowedPresets` parameter is the same shape, one
  argument away. Cost: ODOMETER is simply absent from the text-box picker.
- **(b) Give text boxes a canvas renderer in preview first.** **This is NOT new work — it is the
  SAME work already recorded as the reason text boxes are BLOCK-only** (`TextOverlayItem
  .textAnimGranularitySupported`). Doing it unlocks ODOMETER on text boxes AND WORD/LETTER
  granularity there, in one go. Much larger, and it touches the surface the user actually looks at.
- **(c) Defer ODOMETER** and take MASK_WIPE / NEON_FLICKER first.

**Recommendation: (a) now, (b) later as its own funded piece** — it keeps the agreed build order
moving without quietly committing a session to the text-box renderer rewrite. But (b) is where the
real value is, because it collapses two open items into one, so it deserves a deliberate decision
rather than being reached by default.
6. The retrigger-on-value-change requirement (a timer wanting a pop on each TICK) is **still not
   addressed in `CaptionAnimator`** — it is an event, not a function of elapsed time, and the
   spec warns it is painful to retrofit. It is untouched by this work.
