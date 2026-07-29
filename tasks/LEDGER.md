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
3. **Capsule is fine — NO true ellipse for now.** `corner=1` on a non-square box gives a stadium,
   not a circle. Accepted. Do not spend engine time on `MaskPathBuilder` shapes.
4. **A soft-edges (feather) slider IS in v1.** Engine work: `MaskPathBuilder:22-23` records the
   method — render the mask to an ALPHA_8 bitmap and `DST_OUT` it instead of `clipPath`, which
   is not antialiased. Both the preview and export mask paths go through that one class, so it
   is one change, not two.

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

**3g. Text animation presets — BUILT AND AUTHORABLE. One piece of EVIDENCE is still outstanding.**
Spec: `SPEC_TEXT_ANIMATION.md`, kept current.

| Step | State | Commit |
|---|---|---|
| 1. One evaluator (`CaptionAnimator`), preview and export on the same clock | DONE | `95dc7e2` |
| 2. Presets + granularity + unit splitting, with a harness | DONE | `5cc34fd` |
| 3. Persist on `Clip`, round-trip, and RUN in both renderers | DONE | `c7b6359` |
| 4. Tape `▶` `◀` carets (the visible half of the timing model) | DONE | see below |
| 5. Preset grid picker + granularity selector in the caption drawer | DONE | see below |
| 6. **Large-amplitude device frame** | **NOT CAPTURED — attempted 2026-07-28, BLOCKED: only the Note 20 was attached (see §5)** | — |

**The four fields are no longer `project.json`-only**, so this is out of the §3a failure mode.
It stays in §3 rather than moving to §1 because step 6 is unproven: no device was attached when
the UI landed, so the UI has been verified by build and by harness, **not by a human or a phone
looking at it.** Nothing here claims otherwise.

**How steps 4–5 were verified, and the limit of that verification.** The harness grew from 131 to
**145 checks**, all passing, with the new ones (`caretMapping`) pinning the caret↔zone round-trip
across its whole travel and the model's headline property end to end — both carets at full travel
means entrance ends exactly where exit begins. The APK was dex-scanned for the new symbols
(`TextAnimPickerPopover`, `CAPTION_ANIM_IN_HANDLE`, `zoneFromCaretFraction`, `maxUsefulZoneMs`,
`applyCaptionAnimPreset`) **and for `FadCamApplication` + `FaditorEditorActivity` as the positive
control** — the check the 2026-07-28 partial-dex trap defeated. Class files and APK both postdate
the last source edit. **What none of that proves:** that the carets are grabbable in a real
thumb's-width without stealing edge grabs from the trim handles, that the tiles read as distinct
at 60dp, or that LETTER granularity holds frame rate. Those need the phone.

**Two defects found and fixed by reviewing this work before believing it:**
- `captionAnimTarget()` used `getSelectedClip()` alone, so with an AUDIO clip's captions selected
  the Motion row would have appeared and then silently written to whatever video clip happened to
  be selected underneath. Now gated on `getSelectedAudioIndex()`, the same test
  `tweakCaptionStyle` uses. This is the concrete instance of the "audio-clip captions have NO
  animation" note below.
- The caret's scale was computed from the FULL source transcript rather than the trimmed window,
  so words outside the trim — which are never drawn — could stretch the handle's travel.

**New decisions, with their reasoning, so they are not re-litigated:**
- **Caret travel is scaled to `CaptionPhrases.maxUsefulZoneMs()`, not to the tape.** Zones are
  absolute durations on the clip but spent per PHRASE, and phrases are short while clips are long,
  so a linear map onto the tape would leave ~94% of a 60s clip's travel dead. Full travel now
  means "half the longest visible phrase", which keeps both endpoints of the user's model exactly
  true and makes every position between them distinct.
- **Choosing a preset with both carets at the ends seeds an entrance zone**, in the same undo
  step. Zero zones ARE off by design — but without this, every tile in the picker would apply
  correctly and change nothing on screen, and the feature would read as broken on first use.
- **The carets show only while the caption drawer is open.** Otherwise every captioned clip
  carries two extra carets competing with the trim handles for its edges.
- **`finishCaptionAnimDrag` does NOT divide by the speed multiplier**, unlike the freeze-caret
  precedent it otherwise copies line for line. The freeze zones are timeline ms; these are source
  ms. Copying that one line unchanged would have halved every zone on a 2× clip.

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
  appear — continuous speech would animate once a minute. Zones are stored on the clip as
  DURATIONS and applied against each phrase's own span. Every stated property survives: zero is
  off, and capping each zone at half the span (`CaptionAnimator.zoneForSpan`) makes the entrance
  end exactly where the exit begins at EVERY phrase length.
- **Zones are SOURCE ms, not timeline ms.** `getTrimmedDurationMs()` divides by the speed
  multiplier; clamping against it would have halved the zones on a 2× clip — the same units
  confusion that WAS §3g.
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

**Still outstanding for §3g:** step 6, the large-amplitude device frame — and a human's eyes on
the UI.

**Harness command (updated — it now compiles the phrase grouping and transcript too):**
`javac -nowarn -d tools/jvm-harness/out-caption tools/jvm-harness/stubs/androidx/annotation/*.java tools/jvm-harness/stubs-caption/com/fadcam/ui/faditor/transcript/CaptionStyle.java app/src/main/java/com/fadcam/ui/faditor/transcript/CaptionAnimator.java app/src/main/java/com/fadcam/ui/faditor/transcript/CaptionPhrases.java app/src/main/java/com/fadcam/ui/faditor/transcript/Transcript.java app/src/main/java/com/fadcam/ui/faditor/transcript/TranscriptWord.java tools/jvm-harness/CaptionAnimatorTest.java && java -cp tools/jvm-harness/out-caption CaptionAnimatorTest`

## 4. DECIDED — settled, do not re-litigate

- **AI edits collapse into ONE undo step**, preserving the history behind them. (User, 2026-07-28.)
- **Trim precision is non-negotiable**; no keyframe snapping of user trim points.
- **No apology toasts** for things we can actually build.
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
