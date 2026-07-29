# LEDGER — what is fixed, what is open, what is promised

**Purpose (user's words, 2026-07-28): "as we finish these things, we should log that they are no
longer a problem… so that later on we don't have to reinvent the wheel. But also, if these
glitches come up again, we can address them knowing this history."**

This file exists because a whole feature — masking/chroma-key — was BUILT and then LOST: the
engine shipped, the authoring UI never did, and three separate audits failed to notice because
nothing was ever left unticked. Anything promised here stays here until it is shipped or
explicitly dropped. **Do not delete an entry to make the list look shorter.**

---

## 1. FIXED AND VERIFIED — do not re-investigate

Each of these was reproduced, fixed, and proved on the Note 9 with before/after evidence.
If a symptom below reappears, it is a REGRESSION, not a new bug — start from the named commit.

| What was wrong | Commit | How it was proved |
|---|---|---|
| Transcripts stored once per clip instead of once per file — bloated project files and the undo snapshot | `9c59d0e` | Harness 29/29 + device round-trip 14/14; 24% smaller on a 9-clip project |
| One undo press after a crash could revert a whole session (441 fields, mislabelled as one small edit) | `34b4297` | Reproduced, then before/after: sidecar advanced 622,949 → 658,527 bytes on the same action that previously left it untouched |
| Transcript pool stopped paying after any cross-session undo (file grew back) | `34b4297` | 31,945 → 37,684 bytes before; holds at 31,944 after |
| Playback froze partway through an image clip and the transport went dead | `8a3acaf` | Two runs froze at head=4593; two runs after play through to head=13,264 |
| Previous clip kept playing (audibly, unmuted) underneath a still image | `2a0329d` | playerPos 1338→9714 across the image before; flat at 487 after |
| Image-timer flag leaked when leaving an image clip, freezing the playhead and looping one second of audio forever | `46b4450` | Fixed at the funnel (`loadClipForPlayback`) after call-site patches proved insufficient |
| Play was dead on an image clip after a pause (images had no way to restart the playhead loop) | `92c41b9` | Caused by `2a0329d` removing accidental life support; `startImagePlayback` now posts the loop |
| A transient buffering stall killed the playhead loop permanently | `92c41b9` | Captured `playing=false pwr=TRUE … moved=false`; loop now keyed on playWhenReady too |
| "Rename lane" wrote a name to disk + pushed an undo step while nothing ever drew it (§3c) | `228293b` | Row + its 57-line dialog deleted; no callers remained |
| The AI reported "ducking set to N%" for a field nothing reads (§3f) | `228293b` | Tool unregistered; `duckAmount` has 0 refs in export and 0 in the player package |
| **A drag could seek a clip using ANOTHER clip's coordinates** — the §2a big one | `d3e3a63` | New `SEEKRANGE` probe, same scripted gesture each run on AudioExportVerify: **40 out-of-range seeks → 0** (two runs), in-range 379 → 446/444 so the probe stayed alive |
| Seek right after loading a clip landed at the clip's START | `d3e3a63` | `effectiveTrimEnd()` tested `Long.MIN_VALUE`, but media3 reports `C.TIME_UNSET` (= MIN_VALUE+1) and 0 pre-prepare → window collapsed to 0. **14 zero-window seeks → 0** |
| A transition longer than the clip it hands off to seeks past that clip's end | `d3e3a63` | Caught by `SEEKRANGE` as `rel=600 window=500` from the GL handoff; clamped to B's length |
| `ENDED` with clips still ahead parked forever instead of advancing (§2a layer ii) | `d3e3a63` | Net added + its recovery action proved with a temporary switch (advanced `sel=2 → 3`, playback continued); it also fired on a real park at the last clip. See the caveat below. |
| A clip serving as another's luma matte still rendered as a normal PiP in the PREVIEW, and — worse, because it survived into the exported file — still contributed its AUDIO to the export while its picture was hidden (the two §3a divergences) | see §3a | New `MatteVisibilityTest`, **14 checks against the REAL model classes** (not stubs), `bash tools/jvm-harness/run-matte.sh`. It pins both sides: the peer is still in `visibleOverlayVideoClips` (the export video path resolves peers out of that list) and is NOT in the new `renderableOverlayVideoClips`, including the case where it is opted into audio and unmuted — i.e. it would be audible on its own terms and is excluded before that question is ever asked. Dex-scanned for `renderableOverlayVideoClips` + `servingMatteClipIds`, with `FadCamApplication` and `FaditorEditorActivity` as the positive control |
| Lane mute icon: drawn on lanes with no audio, fake speaker glyph, stranded 62dp from the caret (§3b) | `a19ee53` | Screenshots: layer lanes now draw a caret only; audio lanes a real `volume_up`, red crossed `volume_off` when muted. `undo_count` unchanged (3) across taps where the glyph sits on no-audio lanes; 3 → 4 → 5 on an audio lane. Gutter 92dp → 34.4dp |
| The §3g preset tiles were static poses, so a preset's ease could not be seen at all — three glyphs frozen at progress {0, 0.5, 1} (§3g) | `4a1ea41` | 16-frame burst on the real picker in `bb2a9deb`. **Per-tile temporal sd: Type 4.08, Fade 3.92, Rise 6.90, Ghost 7.21, Beam 6.84 — and NONE exactly 0.00.** NONE is the built-in control: it is deliberately left static, so a zero there proves the instrument reads the TILES and not the clock, the timeline or global screen noise. Before, all frames were byte-identical, i.e. 0.00 everywhere. Character is proved too: sampled ink shows TYPEWRITER quantised to 3 discrete values (44.96 / 48.10 / 51.24, one per glyph) while FADE sweeps continuously (41.96 → 51.22) — step vs ramp, exactly what a frozen tile could not express. Removing the 600ms hold (span = 2 × zone) roughly halved the frames where a pair is indistinguishable: Type/Fade 8/16 → 6/16, Rise/Beam 5/16 → 3/16, Type/Ghost 6/16 → 2/16. Freshness control: `javap -constants` shows `TILE_SPAN_MS = 1800` (the old 2400 would survive a stale compile) and the dex has `drawUnits` PRESENT with the deleted `drawSamples` ABSENT, `FadCamApplication` present as the partial-dex control. **Caveat, deliberately not swept under: the FREEZE-FRAME half is not fixed — see §3g outstanding item 1.** |

## 2. OPEN — diagnosed, root cause known, NOT yet fixed

**2a. Playhead↔clip mapping — FIXED 2026-07-28, `d3e3a63`. Moved to §1.**
Root cause, for the record: the drag computed its position against the segment under the
playhead but handed it to the player holding the clip the drag STARTED on, because
`selectedClipIndex` was frozen for the whole gesture — the index and the media load were tied
together, and loading mid-drag snaps at split points, so neither moved. The index now follows
the playhead during a drag; only the load stays deferred. Seeks are clip-scoped
(`FaditorPlayerManager.seekInClip`).
**One caveat, deliberately left honest:** the layer-(ii) `ENDED` net's TRIGGER could not be
manufactured on demand — every attempt to park the player was won by a poll catching
READY+playing, so the ordinary advance handled it. Its recovery ACTION is proved; the trigger
predicate is evidenced only by the field `PHDIAG` signature. If it ever fires in the wild it now
logs `ENDEDNET: parked at end with play still on …`. **Watch for that line.**
The `SEEKRANGE` probe it was found with is still in `FaditorPlayerManager.seekTo` / `seekInClip`
and logs `ok=false` for any clip-relative seek that exceeds the loaded window. Keep it until the
playhead work is closed; it is what makes this class of bug visible instead of intermittent.

**2b. The stranded drag-latch, second path.** Self-heals, so it is invisible to the user, but it
fires: three separate times on 2026-07-28. Every captured instance reports **every gesture flag
false** (`reorder/minimapDrag/scaling/marquee/postPinchPan/audioDrag` all false, `activeDrag=NONE`,
`pointers=1`), which eliminates the entire original suspect list including the pinch path. Keep
`PHDIAG` + the `lastUp:` snapshot until this is closed.

## 3. PROMISED — on the docket, must not be lost again

**3a. Masking / chroma-key / track-matte AUTHORING UI. — the thing that got lost once already.**
The engine is BUILT, device-proven, and used by export: `CompositingSpec`, `MaskPathBuilder`,
`BlendModeGlEffect`, `PipFrameOverlay`. **Nothing in the app can create one** — the only writer
in the entire codebase is the deserializer (`ProjectStorage:1641`). No tool chip; no AI path.
Reachable today only by hand-editing `project.json`.

User's original ask, still the spec of record (`FEEDBACK_20260702_layers_masking.md`):
> "drag a little box over the webcam area, a helper slider rounds its corners to taste"
> "key out black, green, or DRAG A SWATCH to sample a specific colour from the video itself"

User's direction 2026-07-28: **do NOT paper over the gaps with an "incompatible" toast** — both
known limits are fixable, so fix them:
- ~~preview/export divergence~~ and ~~the export AUDIO leak~~ — **BOTH FIXED 2026-07-28, before
  any UI can let a user nominate an arbitrary clip as a matte.** Details below.
- masks on text/sprites: `TextOverlayItem` has no `compositing` field. A model addition.
  *(Deferred by the user 2026-07-28 — not v1.)*

**The two divergences, and how they were closed.** Fixed at the funnel, not at the two call
sites: `LayerPreviewController` keeps `visibleOverlayVideoClips` as the authority for *is this
overlay visible at all* — the export VIDEO path needs the peers in hand to resolve each
recipient's matte out of that list — and gains `servingMatteClipIds` + a derived
`renderableOverlayVideoClips` as the authority for *does this show up as a PiP*. Conflating the
two is what produced both bugs. Three consumers now agree by construction: the preview surface
(`FaditorEditorActivity:11136`) and the export overlay-AUDIO sequence (`ExportManager:1989`)
take the renderable list; the export video path takes the visible list and applies the shared
`servingMatteClipIds` itself, replacing the inline set it used to build.
Evidence in the §1 row: `MatteVisibilityTest`, 14 checks, real model classes, no device needed.
Deliberately mirrored from the export rather than improved on — the peer is hidden for the whole
clip, not only where the two overlap in time, and a DANGLING `mattePeerId` hides nothing and
degrades to unmatted. Making preview and export agree was the point; a "better" preview rule
would have re-opened the same gap from the other side.
Also asked for: a toolbox icon so it is discoverable, plus entry points "anywhere else that would
be useful — say, on a layer itself", and explicit instruction to **think hard about the UX so it
is genuinely usable rather than merely present.**

**SCOPE DECIDED BY THE USER 2026-07-28 — these four answers are binding, do not re-ask:**
1. **Live preview of key AND matte is IN v1.** *"get it all working in preview too for v1."*
   Today both are EXPORT-ONLY (`BlendModeGlEffect:54-56` — preview renders unkeyed/unmatted), so
   a tolerance slider would be tuned blind. This is the largest piece of the work and the one
   that stops the feature failing the same way twice.
2. **PiP overlays AND image overlays in v1. Text/sprites can wait.** *"pip first, and images.
   sprites text etc can wait."* Note `CompositingSpec` lives on `Clip` only — image-overlay
   support needs the spec reachable from the image-track item and honoured by
   `layerImageOverlay` + its export peer.
   **FOUND 2026-07-29, and it changes the size of this item: there is no image overlay to mask
   yet.** Two independent paths, both dead ends today. `LayerPreviewController.visibleImageItems`
   is documented "always empty today — nothing can create an IMAGE track yet", and its own
   TODO says the hidden-track skip was never mirrored in `ExportManager`. The other candidate —
   an overlay `Clip` whose source is a still, which the export audio path already anticipates
   (`isImageClip()` → "a still has no audio") — has no creation path either: the one PiP entry
   point (`FaditorEditorActivity:16925`) builds its `Clip` from a picked **video** URI.
   So "images in v1" is not a mask-plumbing task, it is **build the image overlay first**.
   Flagged rather than absorbed silently — it may change what the user wants v1 to be.
3. **Capsule is fine — NO true ellipse for now.** `corner=1` on a non-square box gives a stadium,
   not a circle. Accepted. Do not spend engine time on `MaskPathBuilder` shapes.
4. **A soft-edges (feather) slider IS in v1.** Engine work: `MaskPathBuilder:22-23` records the
   method — render the mask to an ALPHA_8 bitmap and `DST_OUT` it instead of `clipPath`, which
   is not antialiased. Both the preview and export mask paths go through that one class, so it
   is one change, not two.
   **ENGINE HALF BUILT 2026-07-29 — the slider itself waits on the panel.** `maskFeather` (0..1)
   on `CompositingSpec`; `clipCanvas` replaced by a `beginMask`/`endMask` bracket at all three
   call sites, and DELETED, so there is no second way to apply a mask. Feather 0 — every
   existing project — takes the identical `clipPath` path; only feather > 0 opens a layer.
   `featherRadiusPx` lives on the android-free model class because preview draws into a content
   rect and export into a full frame: a fixed pixel radius would make one slider mean two
   softnesses. Blur happens inside an ALPHA_8 **Bitmap** (software) precisely because
   `BlurMaskFilter` is ignored on the preview's hardware canvas — the GHOST trap, avoidable
   here because the blurred thing is static geometry, not every video frame. Erase bitmap is
   cached on a shape+size signature, so the export does not re-blur per frame.
   **Proved off-device: `CompositingSpecTest` 30 checks (was 12), ALL GREEN** — round-trip,
   clamping, feather-with-no-shapes is inert and serializes to nothing, and the units property
   that keeps the two renderers honest (half-size surface → half-size radius; portrait and
   landscape of the same frame agree). Dex-scanned for `MaskScope`/`beginMask`/`endMask`/
   `maskFeather`/`featherRadiusPx`/`buildErasePath` with `FadCamApplication` +
   `FaditorEditorActivity` as the positive control, **and for `clipCanvas` as a freshness
   control — it is now ABSENT, which a stale dex could not show.**
   **NOT proved: the pixels.** No phone was attached. That a soft edge actually looks soft, that
   it looks the SAME in preview and export, and that `saveLayer` on the preview's `drawChild`
   does not cost visible frame rate are all unverified. Do this the moment a Note 9 is attached.

Proposed interaction (drafted 2026-07-28, not yet built): a *Mask & Key* chip in the tool row
(enabled only with an overlay object selected) **and** the same action in the object's long-press
menu. Panel = two tabs. *Shape*: "+ Box" drops a drag/resize handle box on the preview, Round /
Rotate / **Soften** sliders, per-shape add|subtract chip, one "show only inside" switch. *Key*:
black/green/blue swatches **plus an eyedropper — tap it, then tap the preview to sample the
colour under your finger** (the user's "drag a swatch to sample a specific colour from the video
itself"), then Tolerance / Softness / Choke. Matte: "Use as matte for…" on a PiP's object menu;
the stencil clip gets a dashed outline + MATTE badge on its timeline item so its disappearance
from the canvas is explained rather than mysterious.

**Sequencing (user, revised 2026-07-28): §3g TEXT ANIMATION COMES FIRST, then this.**

**3b. Lane mute icon — DONE 2026-07-28, `a19ee53`. Moved to §1.**
All three asks landed: absent (not greyed) with no audio, a real `volume_up`/`volume_off`
speaker, flush against the caret with the gutter shrunk 92dp → 34.4dp. The touch box was also
enlarged from the 12dp glyph to the full row height, since a 12dp target is not finger-sized.

**3c. "Rename lane" — DELETE. Decided twice.** User: *"layers don't need names, OBJECTS need
names."* The decision was recorded and never applied: `FaditorEditorActivity:11792` still adds a
Rename row, the dialog persists a name, pushes an undo step and writes to disk, and
`LayerRowRenderer` never draws a track name anywhere. The user types a name and sees nothing.

**3d. Cut smoothness — BUILT, MEASURED, and DELETED. CLOSED, do not rebuild it.**
The hybrid was implemented (`7b3edff`), measured, and reverted on the user's decision
(`bd2bd58`, 2026-07-28): *"170 nearly useless lines? 1-in-1000 odds of landing on a keyframe.
lets not bloat the codebase."*

**The number, so nobody re-derives it:** over two projects, **7 real window starts, 0 aligned.**
Nearest sync sample 98/121/334/381/406ms away; keyframes ~**1.0s** apart (standard ~30-frame
camera GOP). Only an in-point of **0** — a clip never trimmed at the head — ever earns the flag.
A millisecond-precision trim therefore has ~**1-in-1000** odds of landing on a keyframe, and
**file length does not change this**: a longer file has more cuts, not better-aligned ones, which
is why the 45-minute project was never needed to settle it.
The `0.887× → 0.999×` figure below is real but came from forcing the flag on EVERY window — the
unsafe version, which asserts a keyframe start that isn't there and buys a corrupt first frame.
**A trimmed timeline keeps its 250–330ms renderer rebuild at every cut. That is the accepted
cost.** If cut smoothness is ever revisited it needs a different mechanism entirely, not this one.

<details><summary>Original entry + root cause (kept — the mechanism is still true)</summary>

**Cut smoothness — HYBRID, approved to execute, then PROVE IT EARNS ITS PLACE.**
Root cause is exact: `DefaultMediaSourceFactory:589`
`setEnableInitialDiscontinuity(!clippingConfiguration.startsAtKeyFrame)`. Every clipped window
leaves that flag false, so ExoPlayer rebuilds the video renderer at EVERY cut — measured
250/271/330ms per cut, ~11% of total playback time. Setting it true removed all renderer
teardowns and took playback 0.887× → 0.999×.
It is not shipped because the flag ASSERTS the clip starts on a keyframe, which arbitrary user
trims do not. **Frame-level trim precision anywhere is non-negotiable (user), so snapping trims
is permanently off the table.** Hybrid = set the flag only for windows already keyframe-aligned.
**User requirement: after implementing, test on a LONGER file and report what fraction of real
cuts actually benefit — "get it working and then verify its usefulness, or if it's just a waste."**
</details>

**3e. AI clip reorder — two-stage, approved to execute.**
User's design, better than either option originally offered: a reorder must never be able to lose
a clip, so it operates on a **complete tally**. If clips are meant to go, that is a **separate,
explicit deletion stage that reports what it deleted**; the reorder then runs against the new
complete list. Two stages, each accountable, neither able to drop anything silently.
Today `EditScriptApplier.applyReorderClips` silently deletes any clip missing from `newOrder`.

**3f. AI `set_clip_duck` reports success for an edit that does nothing.**
`AIToolExecutor:117/219/2298` registers it, describes it to the model, and returns "Audio ducking
set to N% on clip X". Nothing reads `duckAmount` — zero references in `ExportManager` or the whole
player package. The human-facing slider was correctly hidden behind `if (false)` with a note
saying the feature is unimplemented; the AI copy was missed. User has said ducking is low
priority, so the fix is to stop the assistant claiming it happened, not to build ducking.

**3g. Text animation presets — BUILT, AND NOW VALIDATED BY THE USER ON THE PHONE.**
Spec: `SPEC_TEXT_ANIMATION.md`, kept current.

> **User, 2026-07-29, after driving it himself:** *"animations per word and per letter look great!
> i tried all styles carets worked well."*

That closes the question step 6 existed to answer. The large-amplitude frame was a proxy for
"does this actually look right", and a human has now answered it directly, across all styles and
both granularities — better evidence than the frame would have been. **What step 6 would still
have told us and nobody has measured: whether LETTER granularity holds frame rate on a long
phrase.** That is the cost centre (per-glyph layout in both paths) and it is still unmeasured.

**THE TIMING MODEL IS BEING REPLACED — user direction, 2026-07-29. Binding.**
The animation itself stays. How it is authored changes, because captions and text boxes turn out
to be two different beasts sharing one panel:

1. **The caption style panel must become context-aware** — it is used to style closed captions
   AND text boxes, and the timing control must differ between them.
2. **The timeline `▶` `◀` carets are for TEXT BOXES ONLY.** Remove them for captions.
3. **For a text box the carets are relative to THAT LINE's display duration.** Both dragged to
   the middle means: take the length of that line, halve it — half the time the words/letters are
   coming in, half the time they are going out. New lines arrive constantly, so a per-line
   fraction is the only thing that means anything.
4. **For captions: no caret, no range finder on the timeline. A range control lives IN THE STYLE
   PANEL** (user's choice when asked, 2026-07-29), set once and applied to every caption line as
   a percentage of that line's own duration.
   *Why:* captions ride long videos. *"To get a fifty percent fade in, fifty percent fade out,
   I'm gonna be having to do a lot of dragging over perhaps a thirty minute clip. And that just
   won't do."*

**BOTH HALVES OF THAT DIRECTION ARE NOW BUILT AND PROVED ON THE PHONE — `d71b614`, `665d543`.**

**1. The model change — DONE, `d71b614`.** `Clip.captionAnimInMs`/`OutMs` are now
`captionAnimInPct`/`OutPct`, floats 0…0.5, a fraction of each LINE. The reasoning was the good
part and it survived: the ms version had to be held in SOURCE ms by hand so a speed-adjusted clip
would not animate over the wrong span — the exact mistake §3g originally was. **A fraction has no
units, so it is correct in both bases by construction.** The 0.5 cap is the model, not a safety
rail: at 0.5/0.5 the entrance ends exactly where the exit begins, at every line length.
Every consumer followed through; `CaptionPhrases.maxUsefulZoneMs` was DELETED (it existed only to
scale a caret's travel, which a fraction makes unnecessary) and replaced by `hasAnimatableSpan()`.
Old `captionAnimInMs`/`OutMs` keys are read and IGNORED, documented at the read site — there is no
honest conversion, and they only ever existed in the 2026-07-29 sandbox build.
`zoneForSpan` keeps a floor cap next to the clamp: rounding half an ODD span up would let two
zones sum to one ms more than the line they sit on.

**2. The caption range control — DONE, `665d543`.** The caption drawer has a **Timing** section:
In and Out sliders, 0–50% of every caption line, reading *"Applied to every caption line, as a
share of that line"* and *"In n%  Out n%  of each line"*, which at 50/50 gains *"· in ends as out
begins"*. Dragging previews live and records NOTHING; one undo step is written on release against
the value the gesture started from. **Captions no longer draw carets** — `toggleCaptions` no
longer turns them on, and the call was deleted rather than passed `false`.

**The carets are PARKED, not dead, and that distinction is the whole point of this file.**
They are for TEXT BOXES (user's reservation), there is no text-box path into this panel yet, so
`EditorTimelineView.setCaptionAnimHandlesVisible` currently has **NO CALLER**. The code is left
intact because it is complete and about to be wanted. A block comment at the code says so, and
this entry is the other half of the receipt — the difference from §3c, whose dead code was
undocumented and unrecorded. **If text boxes are dropped, delete that block with them.**

**Still to build for the text-box half — and it is a BUILD, not a context branch.** There is **no
text-box path into the caption style panel.** `caption_drawer` is opened only by
`toggleCaptions()`; `tweakCaptionStyle` targets a video clip or an audio clip and nothing else;
`TextOverlayItem` carries its own `colorInt`/`fontFamily` and a keyframed transform, with no
`CaptionStyle` and no animation zones. **Tell the user the size of this before sinking a session
into it.**

| Step | State | Commit |
|---|---|---|
| 1. One evaluator (`CaptionAnimator`), preview and export on the same clock | DONE | `95dc7e2` |
| 2. Presets + granularity + unit splitting, with a harness | DONE | `5cc34fd` |
| 3. Persist on `Clip`, round-trip, and RUN in both renderers | DONE | `c7b6359` |
| 4. Tape `▶` `◀` carets | BUILT, then RETIRED for captions and PARKED for text boxes | `665d543` |
| 5. Preset grid picker + granularity selector in the caption drawer | DONE | see below |
| 6. **Large-amplitude device frame** | **SUPERSEDED.** The user drove the whole thing and approved it, which is better evidence than the frame. The one question the frame stood in for that a human could NOT answer — does LETTER hold frame rate — is now measured, below | — |
| 7. Timing model → per-line fraction | DONE | `d71b614` |
| 8. Caption range control in the style panel; carets off for captions | DONE | `665d543` |

**LETTER GRANULARITY HOLDS FRAME RATE. Measured 2026-07-29, and this closes the cost centre.**
Matched 5s runs on the Note 9 over the SAME captioned span of `bb2a9deb`, changing only
`captionAnimGranularity` on disk with the editor closed, `dumpsys gfxinfo` reset before each:

| | LETTER | BLOCK (control) |
|---|---|---|
| Frames | 270 | 273 |
| Janky | 91 (**33.70%**) | 92 (**33.70%**) |
| 50th | 12ms | 12ms |
| 90th | 24ms | 25ms |
| 95th | 27ms | 30ms |

**Identical jank rate to two decimal places, identical median, and LETTER is marginally FASTER at
the 90th/95th.** Per-glyph layout costs nothing measurable. **The conditions genuinely differed —
that is the positive control, and it is a picture, not an assumption:** mid-playback under BLOCK
the whole phrase "this cat is very cute she" is drawn at once; under LETTER only "e she" is on
screen, the rest still arriving. Two runs of the same condition could not look different.
The residual 33.7% jank is IDENTICAL in both, so it is the editor's baseline (video decode +
waveform), not the animation — flagged as its own question, not attributed to §3g.
**Limits, stated:** this is the PREVIEW path only, not the export renderer; and "a long phrase"
cannot get longer than this, because `CaptionPhrases` caps a phrase at six words — so ~30 glyphs
IS the worst case by construction, not a sample of it.

**FIRST SIGHTING, 2026-07-29 05:44–05:52 (Note 9, sole device, verified build installed and
launched without a crash-loop).** Until now nothing in §3g had been seen by a phone. It has now:
on `bb2a9deb…` "P0 control no image", with a captioned clip selected and the caption drawer
open, the drawer draws a **Motion** row (`None` + the `≡A` entry), the hint *"Drag the ▶ ◀ carets
on the clip to set timing"*, and **the amber carets are really drawn on the clip**. `≡A` opens
the popover: six tiles — None / Type / Fade / Rise / Ghost / Beam — over an **Animate by** row,
Letter / Word / Sentence / Block, with Word selected. So steps 4 and 5 are confirmed present and
reachable by a thumb, which is more than the harness could say.

**Measured answer to "do the six tiles read as distinct at 60dp?" — NO, and here is the number.**
Four screenshots 0.4s apart are byte-identical, so the tiles are static poses, not animations.
Comparing the 113×66px glyph area of each tile: **Type vs Fade = mean abs difference 1.08/255,
with 2.9% of pixels differing by more than 8** — effectively the same image. Positive control on
the same measurement, against a tile that plainly reads differently: **None vs Type = 26.38 mean,
17.7% differing**, i.e. the instrument detects a real difference at ~24× the signal it finds
between Type and Fade. Zoomed 3× the five presets ARE distinguishable (Rise raises and shrinks
the first A, Beam narrows it, Fade dims it) but at 60dp the label is doing all the work.
**→ The static-poses half of this was FIXED in `4a1ea41`; see §1. The freeze-frame half is still
open and is now a user call — outstanding item 1 below carries the current numbers.**
**Not yet answered:** the caret-vs-trim-handle grab (the carets are drawn ~10px from the green
trim handles, so this is the real question, and it needs a drag, not a screenshot), the
large-amplitude frame, and whether LETTER granularity holds frame rate.

**Observed in passing and NOT a bug:** opening this 2026-07-07 project migrated it from inline
`"transcripts"` to `"transcriptPool"` + 3 × `"transcriptRefs"`, and `"words"` went 3 → 2. That is
the §1 transcript-pool fix doing exactly what it promises — three refs, two unique word lists,
because two clips share one source file. The project.json also grew 22.7KB → 63.7KB, which is
pretty-printing, not content. Flagged because "a transcript lost its words" is what it looks like
at a glance.

**The four fields are no longer `project.json`-only**, so this is out of the §3a failure mode.
It stays in §3 rather than moving to §1 because step 6 is unproven: no device was attached when
the UI landed, so the UI has been verified by build and by harness, **not by a human or a phone
looking at it.** Nothing here claims otherwise.

**Harness: 131 → 145 → 158 → 160 checks, all passing.** Run it with the command at the end of
this section. **Dex-scan symbol list, CURRENT — the older list in this file was contradictory and
is corrected here:** scan for `addCaptionAnimRangeControl`, `makeCaptionAnimSlider`,
`previewCaptionAnimZones`, `captionAnimUsable`, `CAPTION_ANIM_MIN_TRAVEL_PX`, `clampZonePct`,
`hasAnimatableSpan`, `captionAnimInPct`; **positive control** `FadCamApplication` +
`FaditorEditorActivity` + `EditorTimelineView` (the 2026-07-28 partial-dex trap); **freshness
control — these must be ABSENT:** `captionAnimInMs`, `getCaptionAnimInMs`, `maxUsefulZoneMs`.
A stale dex cannot show a deleted symbol missing, which is why the absent list is the strong half.
**Do not scan for `maxUsefulZoneMs` as PRESENT** — an earlier revision of this file said to, and
following it would make a correct dex look stale.

**Two defects found and fixed by reviewing this work before believing it:**
- `captionAnimTarget()` used `getSelectedClip()` alone, so with an AUDIO clip's captions selected
  the Motion row would have appeared and then silently written to whatever video clip happened to
  be selected underneath. Now gated on `getSelectedAudioIndex()`, the same test
  `tweakCaptionStyle` uses. This is the concrete instance of the "audio-clip captions have NO
  animation" note below.
- The caret's scale was computed from the FULL source transcript rather than the trimmed window,
  so words outside the trim — which are never drawn — could stretch the handle's travel.

**New decisions, with their reasoning, so they are not re-litigated:**
- ~~Caret travel is scaled to `CaptionPhrases.maxUsefulZoneMs()`~~ — **OBSOLETE, `d71b614`.** That
  scaling existed because zones were absolute durations spent per PHRASE, so a linear map onto a
  60s tape left ~94% of the travel dead. A fraction is already per-line, so full travel is 0.5 at
  every length and there is no scale factor at all. `maxUsefulZoneMs` is deleted.
- ~~`finishCaptionAnimDrag` does NOT divide by the speed multiplier~~ — **OBSOLETE, `d71b614`,
  and this is the good kind of obsolete.** That warning existed because zones were source ms while
  the freeze carets it copies are timeline ms. A fraction has no base, so the hazard is gone rather
  than handled, and the warning was deleted with it.
- **Choosing a preset with both zones at zero seeds an entrance** (0.25 = half of full travel), in
  the same undo step. Zero zones ARE off by design — but without this, every tile in the picker
  would apply correctly and change nothing on screen, and the feature would read as broken on
  first use.
- **The timing control lives in the style drawer, not on the tape** — see the user's quote above.
- **`hasAnimatableSpan` asks the evaluator, not the span.** It tests "does full travel buy a
  non-zero zone", NOT "is the span non-zero". Those differ and the difference is reachable:
  `spanMs` floors every phrase at 1ms so a degenerate ASR word (`startMs == endMs`) still DRAWS,
  and on a 1ms span the floor cap makes every setting return 0 — so a "non-zero span" test offers
  a control that provably cannot do anything. Caught by adversarial review 2026-07-29 **after the
  harness had already pinned the wrong behaviour as intended**; the harness now sweeps spans
  1…300 and pins that the control appears on exactly the spans where it works.
- **`captionAnimTravelPx` returns 0, not `Math.max(1f, …)`.** The old floor looked like a
  divide-by-zero guard and was a trap: on a segment narrower than its two trim handles the
  expression goes negative, the floor pins it to 1px, and the computed centre lands to the RIGHT
  of the exit caret's minimum — so every touch of the exit caret silently ERASED a zone the user
  had set, while the entrance caret's whole range was one pixel wide. Inherited, not caused;
  unreachable today because the carets are parked; fixed so the text-box build cannot inherit it.

**How step 1 was verified** (the handoff asked for it before anything was built on top):
preview computes `inPoint + positionInCurrentSegmentMs * speed` (`FaditorEditorActivity:8121`),
export computes `inPoint + clipLocalMs * speed` (`CompositeExportOverlay:576,590`) — the same
formula, speed applied on both sides BEFORE the animator sees anything. A frame-diff would have
proved one sample; the property that makes them agree everywhere is that the animation is a pure
function of ELAPSED media time, so that is what is pinned instead
(`CaptionAnimatorTest.clockInvariant`: same word 300ms in gives an identical transform at 0s,
60s and 1h into the file). **131 harness checks**, run with:
`javac -nowarn -d tools/jvm-harness/out-caption tools/jvm-harness/stubs/androidx/annotation/*.java tools/jvm-harness/stubs-caption/com/fadcam/ui/faditor/transcript/CaptionStyle.java app/src/main/java/com/fadcam/ui/faditor/transcript/CaptionAnimator.java tools/jvm-harness/CaptionAnimatorTest.java && java -cp tools/jvm-harness/out-caption CaptionAnimatorTest`

**How step 3 was proved on device** (Note 9, project `bb2a9deb…` "P0 control no image", clip 1,
same playhead both runs, toggling ONLY the four JSON fields): **2901 pixels changed, bounding box
(354,778)–(745,919)** — the caption text and nothing else; zero pixels differ in the video, the
sticker, the waveform or the timeline. **Stated honestly: this proves the PLUMBING, not the look.**
The sampled frame sat near the zone saturation point, where progress is 1 by construction and the
text is *meant* to be fully present, so the amplitude is small. **A large-amplitude frame has NOT
been captured** — do that once the tape handles exist and the playhead can be placed inside an
entrance without hunting.

**Decisions taken while building, so they are not re-litigated:**
- **The PHRASE is the animating object, not the clip.** Taking "the item's tape drives the timing"
  literally as the clip would animate a clip's first phrase and let every later phrase simply
  appear — continuous speech would animate once a minute. Zones are stored on the clip and applied
  against each phrase's own span. Every stated property survives: zero is off, and capping each
  zone at half the span (`CaptionAnimator.zoneForSpan`) makes the entrance end exactly where the
  exit begins at EVERY phrase length.
- ~~Zones are SOURCE ms, not timeline ms~~ — **SUPERSEDED by `d71b614`: zones are a FRACTION of
  each line and have no base at all.** The original note read: "`getTrimmedDurationMs()` divides
  by the speed multiplier; clamping against it would have halved the zones on a 2× clip — the same
  units confusion that WAS §3g." Kept struck-through rather than deleted because it names the
  hazard the fraction was chosen to remove, and a future reader who sees a duration creeping back
  into this area should recognise it.
- **Presets compose with `CaptionStyle.Anim`, they do not replace it.** POP/ZOOM/BOUNCE sit at
  1.15× at rest (an active-word emphasis, not an entrance), so merging the vocabularies would
  silently restyle every existing captioned project.
- **Five presets are declared but NOT implemented** — MATRIX, UNSCRAMBLE, ODOMETER (glyph
  substitution / positional scatter), MASK_WIPE (a clip rect), NEON_FLICKER (stroke/glow). Each
  names its blocker in `CaptionAnimator.unsupportedReason` and returns identity, never an
  approximation. The picker must filter on `Preset.implemented`.
- **Blur is ignored by BOTH renderers**, so GHOST reads as slide+shrink+fade. Consistently
  ignored is safe; approximating it in one path is the divergence this area exists to prevent.
- **Audio-clip captions have NO animation.** `AudioClip` deliberately did not get the four
  fields — half-persisted state no UI writes is how features rot. Add it with the UI, not before.

**Both "still needs the user" questions are now answered — see the spec for the full reasoning:**
- *Which of the ten presets are v1?* **The six implemented ones.** This was never a taste call:
  the picker filters on `Preset.implemented`, and the other five return identity, so shipping
  them would ship five tiles that do nothing. What IS the user's call is which to build next.
- *Composition order against existing keyframes?* **It was undefined in prose, not in code.** Both
  renderers already compose identically: a style keyframe selects the style (hence the emphasis),
  the preset transform is computed from the carets' timing, and the active-word emphasis
  multiplies over it (scales multiply, `dy` adds; the preset's alpha alone gates the draw). Now
  written down. **The user may still want to change the FEEL** — e.g. suppressing emphasis during
  an entrance instead of multiplying into it — but the two paths agreeing was the part that
  mattered, and they do.

**STILL OUTSTANDING FOR §3g — the honest short list, 2026-07-29:**
1. **The six preset tiles: the tiles now animate (`4a1ea41`, see §1) but a FREEZE FRAME still
   cannot separate Type from Fade.** The static-poses half is fixed and measured. What remains
   is that at any single instant Type vs Fade differ by only **~3.0–3.7 mean / ~4%** of pixels,
   against **33.90 / 98.5%** for the None-vs-Type control on the same instrument. So the two are
   now distinguishable *by watching* — Type pops each glyph in at full opacity, Fade ramps it —
   and still not distinguishable *from a photograph*. **This is a user call, not an engineering
   one:** accept motion-only distinction, or give the tiles more than 60dp. Do not "fix" it by
   giving a tile a decorative cue the renderers do not produce — that invariant is the whole
   reason the tiles are drawn from the evaluator.
2. **The text-box half of the user's direction** — see the BUILD note above. Carets are parked
   waiting for it.
3. **The export path's frame cost is unmeasured.** The LETTER result above is the PREVIEW only.
4. **Baseline editor jank is 33.7%** during playback and is NOT caused by §3g (identical in both
   arms). Recorded here because it was measured here, not because it belongs to §3g.

**Test-project state after this session** — `bb2a9deb` "P0 control no image" now sorts to the TOP
of Recent Projects (its `lastModified` is the newest), NOT second from the bottom as an earlier
revision of this file said. **Independently re-confirmed 2026-07-29 by dumping every project's
`lastModified` and sorting** — `bb2a9deb` came out first at "Jul 29, 07:30 AM", and opening the
top row did land in "P0 control no image". Do not navigate by the remembered date; the date moves
every time the project is opened. Map ids with
`adb shell run-as com.fadcam.beta cat files/faditor/projects/<id>/project.json` — and redirect
stdin (`< /dev/null`) if you loop over ids, or the inner `adb` swallows the loop's input and you
silently map only the first project. Its clip 1 is left at preset FADE, granularity WORD,
`captionAnimInPct`/`OutPct` **0.5 / 0.5** — set deliberately while proving the range control, and
left there because it demonstrates the feature. Its old `captionAnimInMs` keys are gone, which is
the read-and-ignore path working as designed, not data loss.

**Harness command (updated — it now compiles the phrase grouping and transcript too):**
`javac -nowarn -d tools/jvm-harness/out-caption tools/jvm-harness/stubs/androidx/annotation/*.java tools/jvm-harness/stubs-caption/com/fadcam/ui/faditor/transcript/CaptionStyle.java app/src/main/java/com/fadcam/ui/faditor/transcript/CaptionAnimator.java app/src/main/java/com/fadcam/ui/faditor/transcript/CaptionPhrases.java app/src/main/java/com/fadcam/ui/faditor/transcript/Transcript.java app/src/main/java/com/fadcam/ui/faditor/transcript/TranscriptWord.java tools/jvm-harness/CaptionAnimatorTest.java && java -cp tools/jvm-harness/out-caption CaptionAnimatorTest`

## 4. DECIDED — settled, do not re-litigate

- **AI edits collapse into ONE undo step**, preserving the history behind them. (User, 2026-07-28.)
- **Trim precision is non-negotiable**; no keyframe snapping of user trim points.
- **No apology toasts** for things we can actually build.
- **The preset tiles are DONE — motion-only distinction is accepted. (User, 2026-07-30.)**
  Asked whether to accept it or give the tiles more than 60dp, he said: *"I don't know exactly
  what is at stake. the animations you have, I think, look great."* So the half-open item from
  `d1a0761` is CLOSED as-is: the tiles are distinguishable by watching and not from a still, and
  that is fine. **Do not "fix" the freeze-frame half by adding a decorative cue** — the tiles are
  trustworthy precisely because they can only advertise motion the renderers actually produce,
  and a badge or a label glyph would trade that away for a screenshot nobody looks at.
- **Preset build order — MATRIX, then UNSCRAMBLE, ODOMETER, MASK_WIPE, NEON_FLICKER.**
  (User, 2026-07-30: *"that seems like a good order to build them in."*) Answers the question
  that had been open across four sessions. Each names its blocker in
  `CaptionAnimator.unsupportedReason` and returns identity today, never an approximation — so
  each one is "remove a reason, add a transform", and the picker un-greys it via
  `Preset.implemented`.
- Undo-after-crash: the queued question may be moot — the reason the undo sidecar was written
  rarely was its size, and the transcript pool (§1) made it far smaller. **Measure whether it can
  now be written on every edit**, which removes the staleness window entirely, before choosing
  between "discard stale history" and "warn the user".

## 5. UNRESOLVED / needs a human

- **BLOCKED 2026-07-28 21:xx: the only phone attached is the Note 20 `REAL_SERIAL`.** The Note 9
  sandbox `SANDBOX_SERIAL` is absent. Under the standing device rule that is a full stop on
  device work, so **§3g step 6 (the large-amplitude frame) could not be attempted**, and neither
  could the thumb/60dp/LETTER-frame-rate walk. Nothing was installed, tapped or captured; the
  only device command run this session was `adb devices`. §3g therefore stays in §3.
  To unblock: attach the Note 9 and detach the Note 20.
  Still outstanding alongside it — the Note 20 was installed at 15:06 on 2026-07-28 from a build
  dex-scanned for its new symbol but NOT for a positive control. It is attached now, so that can
  be settled the moment device work is allowed again, but installing kills whatever session is
  running on it, so it needs the user's word first.
- **W2 "HD zoom tier" for waveforms** (`FEEDBACK_20260706_audio_and_delineation.md` §2) — the tape
  renderer has been rewritten several times; separating W1 from W2 needs more reading than has
  been done. Flagged rather than guessed.
- **Caption-style chooser auto-hide** shipped option (a) (appears when a captioned clip is under
  the playhead); the user leaned (b) (appears after tapping a CC bar). One question, not a rebuild.
