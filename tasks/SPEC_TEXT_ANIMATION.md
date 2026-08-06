# SPEC: Animated text (and animated timers)

> ## ⚠ STATUS CORRECTIONS 2026-08-06 (line-by-line code audit — trust these over the body)
> - **The tape ▶◀ carets are NOT parked.** They were rewritten for text boxes and are LIVE:
>   `EditorTimelineView.hitTestTextAnimHandle` + the draw/drag path, persisted in
>   ProjectStorage and honoured by CompositeExportOverlay. The old method names this doc
>   says have no caller no longer exist.
> - **There are ELEVEN presets, all implemented**, not six: NONE, TYPEWRITER, FADE, RISE,
>   GHOST, BEAM, MATRIX, UNSCRAMBLE, MASK_WIPE, ODOMETER, NEON_FLICKER. The "which are v1"
>   answer earlier in this doc was never updated after the later sections shipped.
> - Still genuinely MISSING: retrigger-on-value-change (the timer tick pop).



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

### ~~KNOWN GAP — GHOST ships without its blur~~ — CLOSED 2026-07-30, blur SHIPS on text boxes

**The decision resolved to "blur both surfaces, no divergence", because the price was measured
and it is small.** The user's condition was *"if ghost preview would cause noticeable lag in
working but look much better in export i think the divergence in this specific instance is
warranted"* — the measurement says there is no noticeable lag, so the sanctioned divergence was
not needed and was not taken.

**The recorded blocker was pessimistic on two counts, both found by reading the painters.**
It said blurring the preview "costs every frame of playback". In fact the preview draws each text
box in its OWN `TextBoxView`, so (i) only a blurring box pays, not the whole overlay, and (ii) a
project with no GHOST box pays exactly nothing. `TextBoxView.applyBlurLayerPolicy` switches that
one view to `LAYER_TYPE_SOFTWARE` only when `CaptionAnimator.presetBlurs` is true.

**The measurement** (Note 9, one build, layer type chosen from a flag file so build-to-build
variance could not contaminate the delta; driver = 48 sustained scrubs; n = 54–62 windows of 30
draws per arm):

| box | HARDWARE median | SOFTWARE median | delta |
|---|---|---|---|
| 1041x564 | 193.5 us | 591.0 us | **+397.5 us (+205%)** |
| 1080x1031 | 201.0 us | 566.0 us | +365.0 us (+182%) |

Large relatively, **~0.4 ms absolutely — about 2.4% of a 16.7 ms frame, per animating box.**
It is a LOWER BOUND: it times the inside of `onDraw` and so excludes the layer's own bitmap
allocation and upload.

**Device-proof that it draws** (`tasks/screenshots/ghost_blur_preview_ramp.png`): recording
playback of a GHOST box, the glyphs are soft early in the entrance and sharpen as it settles,
exactly as `blurPx = (1-e) * fontPx * 0.18` with `alpha = e` predicts. The frame carries its own
control — `PICKERTEST`, a non-GHOST text box in the SAME frame, stays sharp throughout, so the
softness is the preset and not a frame-wide artefact.

**SCOPE, stated precisely: this ships for TEXT BOXES only.** Both of their surfaces go through
`TextBoxRenderer`, so preview and export agree by construction. **Captions still do not consume
`blurPx`** — `CaptionOverlayView` and `CaptionExportRenderer` ignore it, and the caption preview
is ONE shared view for all words rather than a view per object, so its cost profile is different
and its decision is genuinely separate. Do not assume this entry settled it.
**Also still true: the picker thumbnail omits the blur** (`TextAnimPickerPopover`), which now
under-advertises GHOST for text boxes. Small follow-up.

<details><summary>The original entry, kept because its reasoning was right and only its cost model was wrong</summary>

#### (original) KNOWN GAP — GHOST ships without its blur

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

## NEON_FLICKER — the recorded blocker is MISLEADING. Read this before estimating it.

Derived 2026-07-30 by reading the painters, not by trusting the note. **Not built.**

Its blocker says *"needs the renderer to modulate STROKE/GLOW, which is not a geometric transform."*
That is true and it is not the problem. **The problem is that stroke and glow are OPTIONAL
PER-OBJECT properties that most objects do not have**, so a preset which merely modulates the
existing ones renders as nothing:

- `TextBoxRenderer.paintRun` draws its glow pass only `if (o.getGlowRadiusPx() > 0f &&
  o.getGlowColorInt() != TRANSPARENT)`, and its outline pass only if a stroke width and colour are
  set. A default text box has neither.
- **Captions have no per-object glow at all.** `CaptionOverlayView` sets a fixed
  `setShadowLayer(fontPx * 0.12f, …)` from `style.shadow` and a fixed outline width; there is no
  user glow to modulate.

So "modulate stroke/glow" would produce a tile that does nothing on a plain text box and nothing on
any caption — **exactly what `Preset.implemented` exists to prevent**, and the same trap MATRIX
would have fallen into had it only been given a Transform.

**Therefore NEON_FLICKER must SUPPLY its own glow**, derived from the unit's own colour, rather than
scale someone else's. A preset owns its feel and must not depend on unrelated user styling to be
visible. Concretely that means a glow RADIUS (and probably a colour) on
`CaptionAnimator.Transform`, plus a glow pass in the two caption painters that do not currently have
one. The precedent for the shape is MASK_WIPE, which added `revealFrac` to `Transform` and had all
three renderers consume it.

**Good news, and it retires the handoff's worry: this does NOT walk into the GHOST-blur question.**
That question exists because `BlurMaskFilter` is ignored on a hardware canvas. `setShadowLayer` is
not `BlurMaskFilter` — it is honoured for TEXT on a hardware canvas, and `TextBoxRenderer` already
relies on exactly that for its glow pass in the live preview today. So a glow can be drawn on both
surfaces from one code path with no divergence and no `LAYER_TYPE_SOFTWARE`. *Strongly indicated by
the code; worth one on-device confirmation (give a text box a glow and look) before building.*

**The cheap alternative, recorded so it is a choice rather than a discovery:** flicker ALPHA only.
It works on every object with no new channel at all — a neon tube's flicker really is mostly a
brightness stutter — but without a glow it will read as a stutter rather than as neon. Whether that
is enough is a taste call for the user.

## ODOMETER — design, derived 2026-07-29, CORRECTED 2026-07-31, then BUILT

> **⚠ TWO THINGS BELOW WERE WRONG AND ARE CORRECTED IN PLACE. Read the corrections first.**
>
> **1. THE FILLERS MUST ROLL A SEQUENCE, NOT A SCRAMBLE — the user caught this.** The design
> below originally said the filler characters "come from the same deterministic `mix` as MATRIX".
> That is wrong, and not by a detail: **a wheel is an ordered ring, and the whole point of an
> odometer is that you can READ the roll** — …5, 6, 7, settling on 8. Random glyphs sliding
> vertically is MATRIX with extra motion; it would be a second scramble preset, and this project
> has already paid once for two presets that a viewer cannot tell apart (LEDGER §1, the tile
> discriminability work). The determinism argument the old text made was sound and is retained —
> it just argues for a deterministic ORDER, which a ring gives for free and more cheaply than a
> hash. So: `charAtWheel(k)` is **the real character stepped BACK `k` places along its own ring**,
> never a pool draw.
>
> **2. THE SCOPE QUESTION IS MOOT. Do not ask it.** It asked whether to ship captions-only (a),
> build a text-box canvas renderer first (b), or defer (c). **(b) already happened**, for other
> reasons: both text-box surfaces now draw glyph by glyph through one shared `TextBoxRenderer`
> (preview via `TextBoxView.onDraw`, export via `CompositeExportOverlay`, which no longer
> rasterises the box to a bitmap). `TextOverlayItem.textAnimGranularitySupported` returns `true`
> for every granularity as a result. **There is no `TextView` wall left to gate around**, so
> ODOMETER ships everywhere, and the user never has to answer a question the code has answered.

Written down before coding because the last two presets both had blocker notes that were wrong in
opposite directions.

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
exactly, with no special case. Same length in, same length out, so layout cannot reflow.

**`charAtWheel(k)` steps the ring, it does NOT draw from a pool** (correction 1 at the top of this
section). Each character sits on the ring its own kind belongs to — `0–9`, `a–z`, `A–Z` — and
`charAtWheel(k)` is that character moved back `k` places, wrapping. `'8'` at k=3 is `'5'`; `'c'` at
k=4 is `'y'`. Because the wheel counts DOWN to 0 as the unit settles, the slot shows
target−k … target−2, target−1, target: **it counts UP into place**, which is what a mechanical
odometer does and what makes the roll readable. This is also strictly MORE deterministic than the
MATRIX `mix` it replaces — there is no hash for two surfaces to agree on, only arithmetic — so the
preview/export agreement the old text argued for gets stronger, not weaker.

**A character with no ring does not roll.** Punctuation, spaces, CJK, emoji: there is no "previous"
glyph that means anything, and inventing one would put an unreadable character in a slot the user
is being invited to read. Those slots show the real character throughout and simply arrive with the
rest. The stillness is deliberate: an odometer with a fixed `:` between two spinning fields looks
like an odometer; one whose `:` also spins looks broken.

**At WORD or BLOCK granularity the whole run rolls as one wheel** — every character in the run
steps together. That falls out of applying the ring per character of the run, needs no special
case, and is the honest reading of the granularity the user picked: the unit IS the thing being
animated, so a unit-sized wheel is right. LETTER gives the classic per-slot look.

**~~THE SCOPE QUESTION~~ — ANSWERED BY THE CODE, 2026-07-31. Superseded, kept for the record.**
It asked which of three ways out to take, because "the text-box preview is a `TextView`
(`TextOverlayLayer:211` sets a string on it). One view, one string: it cannot draw two glyph rows
clipped to a slot." **That is no longer true.** Option (b) — give text boxes a canvas renderer —
was built for other reasons: `TextBoxRenderer.drawUnit` now draws each unit with `canvas.drawText`
and BOTH surfaces call it, the preview through `TextBoxView.onDraw` and the export through
`CompositeExportOverlay`. The three options were:
- ~~**(a) Gate it, and ship captions-only**~~ via an `allowedPresets` parameter. Not needed; there
  is nothing to gate.
- **(b) Give text boxes a canvas renderer in preview first.** **DONE**, and it did collapse two
  open items into one exactly as predicted — `textAnimGranularitySupported` now returns `true` for
  every granularity, so WORD/LETTER on a text box came free with it.
- ~~**(c) Defer ODOMETER**~~. MASK_WIPE and NEON_FLICKER were taken first and are shipped, so
  there is nothing left to defer behind.

**The lesson worth keeping is the one about the note, not the note.** This scope question was
escalated to the user twice and declined once (*"skip odometer for now i dont know how to
answer"*) — and the correct answer was never a preference at all. It was a fact about the code
that changed while the question sat open. **Re-derive a blocker against the code before asking
anyone to arbitrate it**; that rule has now been paid for four times in this one spec (UNSCRAMBLE
overstated, MASK_WIPE understated, NEON_FLICKER named the wrong obstacle, ODOMETER outlived its).

6. The retrigger-on-value-change requirement (a timer wanting a pop on each TICK) is **still not
   addressed in `CaptionAnimator`** — it is an event, not a function of elapsed time, and the
   spec warns it is painful to retrofit. It is untouched by this work.

---

## MASK_WIPE — BUILT AND SHIPPED, 2026-07-30

Third of the five, in the user's order — taken out of turn only because the user declined to
answer ODOMETER's scope question (*"skip odometer for now i dont know how to answer"*), and the
handoff's standing instruction was to take MASK_WIPE next in that case. **ODOMETER was not
started; its scope question is still the user's.**

### Its blocker note was CORRECT about the requirement and MISLEADING about the cost

`unsupportedReason(MASK_WIPE)` read *"needs a per-unit clip rect"*. That was accurate: a clip is
neither geometry nor alpha, so no existing `Transform` field could express it, and no amount of
cleverness with the two existing channels would have got there. Unlike UNSCRAMBLE — whose note
overstated the work by describing a per-glyph `Transform` array that already existed — this one
named something genuinely absent.

What the note did not say, and what re-deriving it against the drawing loops showed, is that
**the clip is cheap at every surface that needs it.** The rule from the UNSCRAMBLE session
("read the drawing loops before believing a blocker note") cuts both ways: a note can understate
the cost as easily as overstate it, so it is a hypothesis either way.

### THE FINDING THAT MATTERS: MASK_WIPE does NOT hit the text-box `TextView` wall

The handoff predicted it would, and said to check that first and say so early. It does not, and
the distinction is worth keeping because it decides which of the remaining presets are cheap.

The recorded wall is that the text-box preview draws an overlay as one `TextView` holding one
string, while the export draws with `canvas.drawText`. That blocks **ODOMETER**, which needs TWO
clipped glyph rows in one slot — one view with one string genuinely cannot draw two things. It
does not block MASK_WIPE, which needs ONE thing shown IN PART, and `View.setClipBounds` does
exactly that. So the wall is about **drawing two things, not about clipping**, and the earlier
wording ("cannot draw two clipped glyph rows") already contained the distinction — it was simply
read as "cannot clip".

`setClipBounds` applies in the view's own coordinate space and therefore BEFORE its
scale/translation, which is the same order the export gets by clipping AFTER its matrix. The two
surfaces agree by construction rather than by care. **NEON_FLICKER, the last one, modulates paint
and should not touch this wall either** — which would leave ODOMETER as the only preset that
actually needs the text-box canvas renderer.

### The third output channel

`Transform.revealFrac` (0..1, default 1). It is a field on `Transform` rather than a fourth
function because every renderer already holds a `Transform` at the draw site, so a surface cannot
consume the transform and silently miss the reveal — which is what a separate function would have
invited, and what `blurPx` is the standing example of.

It is a **fraction, not pixels**, and that is load-bearing: the four consumers measure their slot
in four different units (preview caption px, export frame px, a `TextView`'s measured width, a
60dp thumbnail). A pixel radius would mean four different wipes from one project.
`CaptionAnimator.revealClip` turns it into a rect in ONE place, so "which edge, and how tall"
cannot be answered twice.

Decisions, so they are not re-litigated:
- **Geometry and alpha stay at identity**, exactly as MATRIX does. A wipe with a fade on top is a
  fade with extra steps, and it would stop being distinguishable from FADE.
- **Left-to-right**, i.e. reading order. A direction control would be a second setting on a picker
  whose whole design is one tap, and text uncovering against its reading direction reads as an
  exit. The exit needs no rule of its own: progress falls back through the same number.
- **The mask clips horizontally only**, with a generous font-relative vertical reach (2em up, 1em
  down). An over-tall mask clips nothing; an under-tall one crops ascenders and reads as a font
  bug on tall letters only.
- **`revealDrawsAnything` is a separate skip predicate** because MASK_WIPE holds alpha at 1, so the
  renderers' existing `alpha <= 0.004f` early-out never fires for it.
- **The export insets by `TextOverlayRenderer.padPxFor`** before wiping. The export bitmap carries
  a 0.35em transparent margin for shadows; the preview's `TextView` has no equivalent. Wiping the
  raw bitmap would spend the first and last few percent uncovering empty padding and put the mask
  edge where the preview never puts it.

### Fixed in passing: preset labels now have ONE authority

Three switches spelled out preset names, each with a `default: Preset.name()` fallback. MATRIX
shipped a session with the caption drawer reading a bare **`MATRIX`**; the text box's
`name().charAt(0) + name().substring(1).toLowerCase()` would have rendered this preset as
**`Mask_wipe`**. Both are the same defect — a fallback that produces something plausible instead
of failing. All three now call `CaptionAnimator.presetLabel`, and the harness fails on any label
containing an underscore, equal to the enum constant, or fully upper-case, so the NEXT omission is
caught rather than shipped.
