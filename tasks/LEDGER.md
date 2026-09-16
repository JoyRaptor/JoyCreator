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
| Transcripts stored once per clip instead of once per file — bloated project files and the undo snapshot | `88cd1b7` | Harness 29/29 + device round-trip 14/14; 24% smaller on a 9-clip project |
| One undo press after a crash could revert a whole session (441 fields, mislabelled as one small edit) | `d41e130` | Reproduced, then before/after: sidecar advanced 622,949 → 658,527 bytes on the same action that previously left it untouched |
| Transcript pool stopped paying after any cross-session undo (file grew back) | `d41e130` | 31,945 → 37,684 bytes before; holds at 31,944 after |
| Playback froze partway through an image clip and the transport went dead | `4e19c44` | Two runs froze at head=4593; two runs after play through to head=13,264 |
| Previous clip kept playing (audibly, unmuted) underneath a still image | `d8d6bb3` | playerPos 1338→9714 across the image before; flat at 487 after |
| Image-timer flag leaked when leaving an image clip, freezing the playhead and looping one second of audio forever | `c43522f` | Fixed at the funnel (`loadClipForPlayback`) after call-site patches proved insufficient |
| Play was dead on an image clip after a pause (images had no way to restart the playhead loop) | `c073ae7` | Caused by `d8d6bb3` removing accidental life support; `startImagePlayback` now posts the loop |
| A transient buffering stall killed the playhead loop permanently | `c073ae7` | Captured `playing=false pwr=TRUE … moved=false`; loop now keyed on playWhenReady too |
| "Rename lane" wrote a name to disk + pushed an undo step while nothing ever drew it (§3c) | `386c636` | Row + its 57-line dialog deleted; no callers remained |
| The AI reported "ducking set to N%" for a field nothing reads (§3f) | `386c636` | Tool unregistered; `duckAmount` has 0 refs in export and 0 in the player package |
| **A drag could seek a clip using ANOTHER clip's coordinates** — the §2a big one | `1d061ea` | New `SEEKRANGE` probe, same scripted gesture each run on AudioExportVerify: **40 out-of-range seeks → 0** (two runs), in-range 379 → 446/444 so the probe stayed alive |
| Seek right after loading a clip landed at the clip's START | `1d061ea` | `effectiveTrimEnd()` tested `Long.MIN_VALUE`, but media3 reports `C.TIME_UNSET` (= MIN_VALUE+1) and 0 pre-prepare → window collapsed to 0. **14 zero-window seeks → 0** |
| A transition longer than the clip it hands off to seeks past that clip's end | `1d061ea` | Caught by `SEEKRANGE` as `rel=600 window=500` from the GL handoff; clamped to B's length |
| `ENDED` with clips still ahead parked forever instead of advancing (§2a layer ii) | `1d061ea` | Net added + its recovery action proved with a temporary switch (advanced `sel=2 → 3`, playback continued); it also fired on a real park at the last clip. See the caveat below. |
| A clip serving as another's luma matte still rendered as a normal PiP in the PREVIEW, and — worse, because it survived into the exported file — still contributed its AUDIO to the export while its picture was hidden (the two §3a divergences) | see §3a | New `MatteVisibilityTest`, **14 checks against the REAL model classes** (not stubs), `bash tools/jvm-harness/run-matte.sh`. It pins both sides: the peer is still in `visibleOverlayVideoClips` (the export video path resolves peers out of that list) and is NOT in the new `renderableOverlayVideoClips`, including the case where it is opted into audio and unmuted — i.e. it would be audible on its own terms and is excluded before that question is ever asked. Dex-scanned for `renderableOverlayVideoClips` + `servingMatteClipIds`, with `FadCamApplication` and `FaditorEditorActivity` as the positive control |
| Lane mute icon: drawn on lanes with no audio, fake speaker glyph, stranded 62dp from the caret (§3b) | `e1fe61d` | Screenshots: layer lanes now draw a caret only; audio lanes a real `volume_up`, red crossed `volume_off` when muted. `undo_count` unchanged (3) across taps where the glyph sits on no-audio lanes; 3 → 4 → 5 on an audio lane. Gutter 92dp → 34.4dp |
| §3g text-box motion was UNVERIFIED end to end — the serializer was proved but nothing showed the PICKER actually reaching `TextOverlayItem` (§3g) | see §3g | Note 9, `bb2a9deb`, baseline **zero** `textAnim` keys so no leftover could masquerade as success; picked **RISE** because nothing on disk had held it. Three levels agreed: the dialog's MOTION row read **"Rise · 25% in / 0% out of this box"** (25% = `MAX_ZONE_PCT/2`, the seed, so the label reports the model not the tap); the text **vanished from the preview at t=0**, which is RISE at progress 0 — the renderer consuming the new fields; and after Close & Save the disk held `"textAnimPreset":"RISE"` + `"textAnimInPct":0.25`. Controls: a full-file `diff` shows the keys on **exactly one** overlay and none of the other five (not blanket defaults); the same diff shows `"Enter text"` → `"PICKERTEST"`, an independent signal that OK committed, so a missing key could not be blamed on the dialog failing; a no-edit reopen + re-save returned both keys unchanged (full round-trip); undo 17 → 19; and "Animate by" offered only `Block`, i.e. `44f80d2`'s gate working. `textAnimGranularity`/`textAnimOutPct` correctly ABSENT (default + sparse-omit) |
| The §3g preset tiles were static poses, so a preset's ease could not be seen at all — three glyphs frozen at progress {0, 0.5, 1} (§3g) | `aac13f0` | 16-frame burst on the real picker in `bb2a9deb`. **Per-tile temporal sd: Type 4.08, Fade 3.92, Rise 6.90, Ghost 7.21, Beam 6.84 — and NONE exactly 0.00.** NONE is the built-in control: it is deliberately left static, so a zero there proves the instrument reads the TILES and not the clock, the timeline or global screen noise. Before, all frames were byte-identical, i.e. 0.00 everywhere. Character is proved too: sampled ink shows TYPEWRITER quantised to 3 discrete values (44.96 / 48.10 / 51.24, one per glyph) while FADE sweeps continuously (41.96 → 51.22) — step vs ramp, exactly what a frozen tile could not express. Removing the 600ms hold (span = 2 × zone) roughly halved the frames where a pair is indistinguishable: Type/Fade 8/16 → 6/16, Rise/Beam 5/16 → 3/16, Type/Ghost 6/16 → 2/16. Freshness control: `javap -constants` shows `TILE_SPAN_MS = 1800` (the old 2400 would survive a stale compile) and the dex has `drawUnits` PRESENT with the deleted `drawSamples` ABSENT, `FadCamApplication` present as the partial-dex control. **Caveat, deliberately not swept under: the FREEZE-FRAME half is not fixed — see §3g outstanding item 1.** |

| The text-box timing carets (§3h) existed but were PARKED with no caller, and the one question that mattered — can a caret be grabbed without stealing a trim grab — could not be answered off-device | see §1f | Six scripted drags on the Note 9. Three caret drags landed on **predicted** stored values (0.1241798 vs 0.1243; 0.359678; 0.20713 vs 0.204±0.004), each moving undo by **exactly one** (32→33→34→35→36, `Recorded: Text animation timing`). Two grabs at the trim cap — including its exact drawn centre — recorded `Overlay time range` instead, i.e. **the trim was NOT stolen**. Neither caret erased the other's zone. Harness 303 → **326, 0 failed**, and the new checks were shown to discriminate by reintroducing the old negative-travel floor (4 fail, then restored). Dex: 7 new symbols present, 2 deleted ones ABSENT, `FadCamApplication`=3 |

## 1b. THE EXPORT IS PROVED — 2026-07-30, and it found two real bugs on the way

**The oldest gap in §3g is closed: a file has been exported and its PIXELS checked.** Every prior
§3g proof was preview-only or model-only; the shared-renderer rewrite (`651359b`) rested on an
argument from construction. It now rests on a measurement.

**Method — predicted, not eyeballed.** `PICKERTEST` (text box, open-ended, no `startMs`/`endMs`)
was set to **RISE + LETTER** on disk, the project exported through the real UI, the file pulled and
frames extracted with ffmpeg. A text box's per-letter state is a closed-form function of the
playhead, so the expected number of drawn letters and their alphas were computed first:

| media time | predicted | export shows | preview shows |
|---|---|---|---|
| 0.000s | 0 letters | nothing | nothing |
| 1.318s | 2, alphas 1.00 / 0.88 | `PI`, I faint | `PI`, I faint |
| 2.500s | 4, last alpha 0.56 | `PICK`, K faint | — |
| 4.000s | 6, last alpha 0.69 | `PICKER`, R faint | — |

**Preview and export agree at the same media time** (`tasks/screenshots/
textbox_export_vs_preview_1318.png` is the side-by-side; `textbox_export_letter_progression.png`
is the four-frame sweep). The staggered LETTER entrance, the arrival order and the part-risen
newest letter all match the arithmetic. **The shared renderer does what its one-renderer-two-callers
design claimed.** Captions, the sticker and the waveform visualizer are all present in the exported
frames too — also never previously pixel-confirmed.

**~~BUG A~~ / ~~BUG A2~~ / ~~BUG B~~ — ALL THREE FIXED AND PROVED, 2026-07-30, `13bd59e`.
See §1c below for the measurement. The three paragraphs that follow are kept as the original
diagnosis, because the reasoning is what made the fix a one-shot.**

**BUG A — the exported file has 5.7s of VIDEO and 30.9s of AUDIO.** Measured, not inferred:
`ffprobe` gives video `duration=5.743844`, `nb_frames=177`, last packet `pts_time=5.710`; audio
`duration=30.912`, `nb_frames=1449`. 5743ms is exactly the master track's length. **So a player
shows ~25 seconds of frozen or blank picture while the audio keeps going.** The cause is visible in
the project: audio clips sit at `offsetMs` 20608 and 13709, far past the last video clip, and the
muxer writes them out while the video track simply stops. Whether audio may extend past video is a
design question; 25s of no picture is not a good answer to it either way.
**BUG A2, the same thing from the other side:** the export dialog announces **`00:05`** while the
file it produces is **30.9s**. The dialog is reporting the video length and the muxer is writing the
audio length. One of the two is wrong and they should not disagree.

**BUG B — an open-ended text box paces its animation against the PROJECT duration, which a trailing
AUDIO clip can stretch.** `TextOverlayItem.animSpanMs` resolves a missing `endMs` to the timeline
duration, which here is 30.9s *because of the audio*, so the 25% entrance is 7.7s long — but only
5.7s of video is ever rendered. **The exported file therefore never shows the finished word**: it
reaches `PICKER` and the video ends. This is consistent between preview and export (so it is NOT a
divergence, and does not weaken the proof above), but it is close to certainly not what a user
means by "animate this title in". It is arguably downstream of BUG A: if the visual duration drove
the span, the entrance would fit.

**~~BUG C~~ — FIXED AND PROVED, 2026-07-30, `49390f4`. See §1d below. The paragraph that follows
is the original diagnosis, kept because its root cause was exact and made the fix a one-shot.**

**BUG C — IMAGE OVERLAYS DO NOT EXPORT. CONFIRMED BY MEASUREMENT, and the root cause is exact.**
The handoff found this by reading and asked for it to be confirmed before anyone spent a session on
it. It is confirmed. It could not be tested with the sandbox as it stood — both image overlays live
at 6009–11009ms and 23497–23747ms, i.e. **past the 5743ms end of the video track**, where ffmpeg
encodes nothing, and "no frame" must not be mistaken for "no image". So one was **moved to
1000–5000ms** (the overlay's `startMs`/`endMs` and its layer item's `timelineStartMs` together, with
a deep-equality guard asserting nothing else changed) and the project re-exported.

*Result:* **0 of 409,920 pixels differ**, at 2.5s **and** at 3.5s, between the export with the image
inside the rendered window and the export with it outside. Not faint, not misplaced — absent.
Three controls make that mean something:
- **The preview DOES draw it** at the same playhead (`tasks/screenshots/
  imageoverlay_preview_shows_it.png`) — and at its original position it would not have been visible
  there at all, which also proves the app really loaded the edited project.
- **The differ works:** the same comparison between 2.5s and 3.5s of one file reports 383,976
  differing pixels.
- **The exporter is not simply ignoring overlays:** the same run exports the text box, the captions,
  the sticker and the waveform visualizer. And the two mp4s have different md5s, so the export
  genuinely re-ran rather than a cached file being re-read.

*Root cause, read after measuring.* `CompositeExportOverlay:524` sends **non-image** overlays to the
shared `TextBoxRenderer` and `continue`s. Image overlays therefore fall through to the older path
below it, which builds a throwaway `TextOverlayItem`, calls `setImageUri` on it (`:570`) and hands
it to `TextOverlayRenderer.render`. **`TextOverlayRenderer` contains zero references to images** — a
grep for `imageUri` in it returns nothing; it is a text rasteriser, and at `:68` it substitutes
`" "` when the text is empty. An image overlay's text IS empty, so it renders one blank space.
**`setImageUri` at `:570` is a call into a void.** This is precisely the §3a failure mode this
ledger exists for: a setter call that looks like function. ~~§3a item 2 inferred that image
overlays were "rendered in export (`CompositeExportOverlay:531` feeds `setImageUri`)"~~ — **that
inference was wrong, and is corrected here rather than in place so the reasoning survives.**
*Scope of the fix:* an image branch in the export that loads the URI and draws the bitmap into the
overlay's rect. The preview already does it, so the geometry is settled; this is the export half
only. **Not attempted this session** — it is a feature, not a one-liner, and the session's job was
to establish whether it was real.

## 1d. BUG C IS FIXED — IMAGE OVERLAYS EXPORT, 2026-07-30, `49390f4`

The cheapest real win on the docket, and it was cheap for the reason the ledger predicted: the
root cause was exact and the geometry was already settled by the preview.

**The fix.** An image branch in `CompositeExportOverlay` that decodes the URI and draws the bitmap.
Geometry is **MIRRORED from `TextOverlayLayer.position`, not re-derived** — height is a fraction of
the frame height, width follows the bitmap's own aspect, centred on the animated centre, FIT_XY into
that rect. Preset transform, opacity composition and MASK_WIPE reveal follow the same order the text
path and the preview's View properties use. **The dead `setImageUri` call is DELETED**, not left in
place — a setter nobody reads is the exact §3a failure mode, and leaving it would re-arm the trap.
Bitmaps are decoded once per overlay, cached for the clip's lifetime, and downsampled against the
OUTPUT frame (a 12MP photo on a 480p export would otherwise be held at full size for all 928
frames). A failed decode is cached as a null so it logs once, not 900 times; `release()` recycles.

**Measured, with the control that makes it mean something:**

| sample | pixels differing vs the previous export |
|---|---|
| t=8.0s | **129,563** of 409,920 |
| t=10.0s | **124,529** |
| t=2.5s — outside the 6009–11009 span | **0** |

The ledger's original measurement of this overlay was **0 differing pixels**. The zero at t=2.5s is
the control: the differ works on these exact files, and the change touched only the image.

**GEOMETRY PROVED AGAINST THE MODEL, NOT EYEBALLED.** Predicted from `project.json` (`centerY`
0.68915164, `sizeFraction` 0.30, asset 2734×737) BEFORE looking: export rect y **460.4 … 716.6**;
measured **460 … 716**. The top edge is unambiguous — a pure black gap at y=456–459 separates the
image from PICKERTEST's white text, and `brightfrac` jumps to 1.000 exactly at y=460.

**PREVIEW AND EXPORT AGREE, and the proof is stronger than a diff:** each independently matches the
same model. With the overlay temporarily moved inside the master-track window (deep-equality guard —
only `startMs`/`endMs`/`timelineStartMs` differed, the script refused to write otherwise), the
preview at 00:02.954 draws it at y **666…974** against a predicted **666.4…975.9** — with the video
content rect measured INDEPENDENTLY (x 250…829, top y=110, via a grey-vs-colour test) rather than
derived from the answer, which would have been circular. Two surfaces, two scales, one authority,
both within ~2px.

**Both image overlays work, and they exercise DIFFERENT paths:** `edff4a88` is a `project://assets`
PNG, `37b215f3` is a `content://` URI — and the latter sits at 23497–23747, i.e. inside the tail
filler from `13bd59e`, so **images render over the filler too**.

**Verified:** harness **298 passed, 0 failed**, matte ALL PASS, both from a clean compile. APK
dex-scanned with `FadCamApplication` as the positive control (3) alongside `imageOverlayBitmap`.
Sandbox restored to `82d8342d`.

~~**FOUND, NOT FIXED — a PREVIEW-side gap, now the mirror image of the old bug.**~~ **DIAGNOSED
2026-07-30, and it IS a blanket rule after all — the reasoning that said otherwise was wrong.**
The observation was: at 7.743s, past the master track, the preview did not draw this image overlay
while the export now does — yet it *does* draw the PICKERTEST text box at the same time, "so it is
not a blanket 'nothing past the master track' rule."

**That inference was the trap, and it is worth keeping visible.** PICKERTEST has no `startMs` or
`endMs`, so its span is `0 … Long.MAX_VALUE` — it is visible at EVERY clock value, including a
frozen or clamped one. **Its being drawn therefore carries no information about what time the
overlay surface thinks it is.** It looked like a control and was not one.

**The discriminating test, run on the Note 9:** scrub past the master end and ask whether a TEXT
overlay whose span also lies past it is drawn. At **20.872s**, inside `LayerOne`'s 20556–25117
span, the preview draws PICKERTEST and **not** `LayerOne`. So it is not image-specific at all:
**the preview draws NOTHING whose span begins past the end of the master track.**

**Mechanism, read afterwards and consistent with all three observations.** The overlay clock is
structurally SEGMENT-based:
- the only playhead callback the timeline emits is
  `EditorTimelineView.Listener.onPlayheadSeeked(int segmentIndex, float fractionInSegment, boolean)`
  — there is no timeline-absolute one;
- its handler (`FaditorEditorActivity:1733`) begins
  `if (segmentIndex < 0 || segmentIndex >= tl.getClipCount()) return;`
- everything downstream hangs off that early return: `updateCurrentTimeDisplay` →
  `getAbsolutePlayheadMs()` (which is *clips-before-selected* + *position-within-clip*, so it
  **cannot exceed the master total by construction**) → `setTextOverlayPlayhead`.

Past the last clip there is no segment, so the overlay surfaces are never ticked and freeze.
**The blue time chip disagrees because it is a different widget** — `EditorTimelineView` draws it
from its own `playheadPositionMs` (`:698`, "Absolute playhead position in timeline"), which is
genuinely absolute. Two clocks, one honest, one segment-derived; the honest one is on screen, which
is exactly why this read as "the time is 20.8s but the overlay is missing".

**Scope, stated plainly: this is bigger than an overlay bug.** Nothing placed past the last video
clip can be seen, positioned or keyframed in the editor, although the export now renders it
correctly (`13bd59e`, `49390f4`) — so the preview and the export disagree about a whole region of
the timeline, in the direction that hides work the user has already done.
**FIXED AND PROVED ON THE NOTE 9, 2026-07-30.** One helper,
`FaditorEditorActivity.overlayClockMs(segmentDerivedMs)`: past the master track it returns the
timeline's own `playheadPositionMs`; inside it, it returns the segment-derived value **unchanged**.
Applied in `setTextOverlayPlayhead` (so all ~20 of its callers are covered in one place) and once
in `updateCurrentTimeDisplay` for the image-track, sprite and PiP surfaces, which had the identical
blindness.

**A refinement to the mechanism, found by reading the emitter rather than assuming the early
return fires.** `onPlayheadSeeked` IS called past the last clip — `updatePlayheadFromX` clamps
`targetSegment` to `segments.size() - 1` **and** clamps `posInSegmentMs` to that segment's end
before reporting. So the overlays were not merely un-ticked; they were actively told the wrong
time, 5743ms, on every drag frame. The early return exists but is not what bites.

**The audio tail was a second, separate hole.** It is the only path that advances the playhead past
the last clip during PLAYBACK, and it updated the tape and the clock and then `return`ed, never
ticking any overlay surface — the same omission the "below" surface had, in a different path. Now
ticks all four.

**Proved with four positives and two controls:**
- past the master track, **text (`LayerOne` at 20.750s), image overlay (8.529s), sprite (the
  pangolin, `startMs` 12645) and the PiP video panel** all now draw where none of them did before —
  `tasks/screenshots/preview_past_master_before_after.png` is the before/after at both times;
- **control 1, spans are still respected:** at 5.898s — past the master end but before the image
  overlay's 6009ms start — the image is correctly still absent. The fix is "use the honest clock",
  not "switch everything on past the end";
- **control 2, nothing inside the master track changed:** at 00:00.023 the preview is
  frame-for-frame what it was before the fix — rug, star sprite, waveform, and no PICKERTEST
  (RISE at progress 0 draws nothing). That is the property the conditional was written to
  guarantee, and it is why this could be done safely inside the §2a-hardened path;
- 0 `FATAL EXCEPTION` in logcat across the whole walk.

**A GRADLE SPURIOUS FAILURE, and the artifact check that had to follow it.** `assembleDefaultDebug`
failed once with `cannot find symbol` across ExportManager's imports, then succeeded on an immediate
re-run with **no source change** — the documented re-run rule. But the successful run had javac
EXECUTE while `dexBuilder`, `mergeProjectDex` and `packageDefaultDebug` all reported `UP-TO-DATE`,
which is the exact signature of the corrupt APK `2883b8c` warned about. The APK was dex-scanned
before it was trusted; it was in fact fresh. **A spurious failure does not excuse skipping the
artifact check — it is precisely when to do it.**

## 1e. MATRIX REWORKED — it RESOLVES across the message now, 2026-07-30, `78a2d13`

**The user rejected the shipped MATRIX on sight**, and was right:

> *"Matrix has all the text — spaghetti garbage starting out on the screen. when what it should be
> is each letter goes through a bunch of glyph nonsense and then resolves on a letter… there's a
> bit of a gradient of nonsense as the message reads across."*

**The arithmetic agreed before any code changed.** The old rule inked EVERY character as a glyph
from progress 0 and locked position *i* once progress passed `(i+1)/(L+1)`. Modelled in
`tasks/matrix2_predict.py`, `PICKERTEST` at p=0.00 rendered as a full ten-character body of
katakana. That is a block of noise CLEARING, not a message ARRIVING — a legible effect, but not the
one the preset is named for.

**Now each character has a three-stage life: BLANK → churning glyphs → its real letter.** At any
instant there is settled text on the left, two-to-four characters churning, and nothing yet on the
right.

| p | rendered (`x` = a churning katakana/digit) |
|---|---|
| 0.35 | `THE Mxxx1` |
| 0.50 | `THE MxTxxx xxx` |
| 0.65 | `THE MATRIX xAS xxx` |
| 0.80 | `THE MATRIX HAS Yxx` |

**Two parameters, and the reasoning is the part worth keeping:**
- **Reveal time is strictly left-to-right, with NO jitter.** An earlier draft jittered the reveal as
  well as the duration. That makes reveal non-monotonic and opens **HOLES** mid-message — a
  character drawn while one to its left is still blank, which reads as dropped text. **Caught in the
  model, not on the phone.** All jitter now lives in the DURATION.
- **Duration varies per character** (0.18–0.42 of the unit's progress) — the user's *"it takes
  different durations for different letters to resolve"*. One fixed duration makes every letter
  resolve a fixed distance behind the last, which reads as a mechanical wipe.
- **The reveal spread is scaled by `1 - CHURN_MAX`** so the LAST character's churn still fits inside
  the zone. Without it the tail characters clamp to 1 and snap together on the final frame —
  measured at **3/10 and 4/18 before the scaling, 0 after**.

The blank is a **SPACE**, so the string is still the same length and the layout-safety property is
untouched. Determinism is untouched: the duration is keyed on `(unitIndex, i)` through the same
hand-rolled `mix`, so preview and export resolve each letter at the same instant.

**Harness 295 → 300.** Four new checks pin what the user asked for and the property the draft broke:
a leading edge exists at p=0; settled/churning/blank zones coexist in that order mid-entrance; **no
holes at any progress**; characters resolve at differing offsets.

**A PRE-EXISTING HARNESS FAILURE THE HANDOFF DID NOT KNOW ABOUT — the number was wrong.** The
harness at `3ebd586` was **295 passed / 1 FAILED**, not the recorded 298/0. NEON_FLICKER shipped a
deliberately unit-keyed flicker, which broke a control asserting that only UNSCRAMBLE varies with
`unitIndex` — and that session had REVERTED its own harness test over an encoding problem, so
nobody re-ran it and the stale figure was copied into the handoff. **This is the third time a
harness count in a doc has been wrong; measure it by running it.** The control is now a **pinned
SET** rather than a blanket rule: a preset that reads `unitIndex` must be declared, and one that
starts reading it by accident still fails.

**~~NOT YET SEEN ON A PHONE.~~ PROVED ON THE NOTE 9 the same day, and character-for-character.**
`PICKERTEST` set to MATRIX with the picker's seeded 25% zone; predicted from
`tasks/matrix2_predict.py` BEFORE looking:

| | |
|---|---|
| media 2556ms → progress | 0.3323 |
| predicted | `cmV8Vﾓ` + 4 blanks |
| on screen | `cmV8Vﾓ` + 4 blanks |

Exact, including the count of drawn characters and the blank tail. **The screenshot carries its own
clock** — the `00:02.556` chip and the text are in the SAME framebuffer grab, so they cannot
disagree. **Six drawn, four blank IS the leading edge** — the precise thing the user objected to
being absent, and the old build would have inked all ten from frame one. Evidence:
`tasks/screenshots/matrix2_resolve_wave_full.png`, `matrix2_resolve_wave_zoom.png` (3× crop
settling the katakana as U+FF93).

**THE CHURN POOL IS NOW WEIGHTED TOWARDS ASCII — user direction, same day.**
*"it should have a higher ratio of english letters and numbers in the nonsense pool so it is clear
we're not trying to spell anything in any specific language. also `*&^%$#@!{}?<>` should be in the
pool of jibberish."* It was 56 katakana against 10 digits — **85% katakana**, which read as
scrambled Japanese rather than as machine noise. Now A–Z, a–z, 0–9 and that symbol run, listed
**twice** against the katakana range: **75% ASCII measured from real output**, katakana still fully
reached. Weighted by REPEATING the ASCII block rather than by thinning the katakana, so every
existing range invariant holds unchanged. **Harness 300 → 303**, and the ratio is pinned as a ratio
sampled from real output rather than as a character list — thinning the ASCII back out fails the
test even if every character is still "declared".

## 1c. BUGS A, A2 AND B ARE FIXED AND MEASURED — 2026-07-30, `13bd59e` (on `2883b8c`)

`2883b8c` was committed **uncompiled and untested**. It has now been built, corrected and proved.

**BUG A — the export dropped everything past the master track.** Per-stream `ffprobe` on the
sandbox, same project, before → after:

| | before | after |
|---|---|---|
| video `duration` | 5.743844 | **30.776333** |
| video `nb_frames` | 177 | **928** |
| last video packet | 5.710 | **30.743** |
| audio `duration` | 30.912 | 30.912 (unchanged, as intended) |

**Predicted, then looked at.** `getTotalDurationMs` = max(video 5743, audio end 30771) was computed
from `project.json` BEFORE the run, so 25028ms of filler was the prediction; the app then logged
`project runs to 30771ms but the master track ends at 5743ms — appended a 25028ms black filler`.
The arithmetic matched to the millisecond.

**THE TAIL IS REAL PICTURE, NOT A BLACK PAD — this is the load-bearing half.** A duration hint
would have produced 25s of nothing. Frames pulled from the exported file, each checked against the
spans on disk:

| media time | drawn | why that is right |
|---|---|---|
| 8.0s / 10.0s | PiP clip, star sprite, PICKERTEST | PiP spans 7948–13507 |
| 21.0s | LayerOne, 2nd sprite, PICKERTEST | LayerOne 20556–25117; sprite starts 12645 |
| 28.0s | sprite + PICKERTEST, LayerOne gone | LayerOne ended at 25117 |

**Every appearance AND disappearance matches its on-disk span** — the disappearances matter as much
as the appearances, since a stuck last frame would show everything forever. **The star sprite's face
CHANGES between frames**, so the filler carries the live per-frame overlay pipeline rather than one
frozen composite. Non-black pixel counts move 95.6k → 63.8k → 58.3k across the tail, i.e. the
picture is genuinely varying. None of this was in the file before.

**BUG A2 — the dialog announced `00:05` for a 30.9s file. FIXED.** Root cause: both export-facing
call sites used `totalEffectiveMs()`, which sums **master clips only**. They now use
`Timeline.getTotalDurationMs()` — the length the exporter actually writes, now that the filler makes
the video cover it. **The other eight `totalEffectiveMs()` callers genuinely mean "where does the
video track END"** (`audioTailStartMs`, `videoEndMs`) and were deliberately left alone; changing
them would have moved where audio-tail handling begins. Proved on the phone: the dialog now reads
`00:30 • 4 clip(s) • 3 audio`, matching both the timeline and the 30.776s file. That reading also
doubles as a behavioural freshness proof of the installed build.

**BUG B resolved as a CONSEQUENCE, exactly as the handoff predicted.** PICKERTEST's 25% entrance
against a 30.9s project is 7.7s, which a 5.7s video cut off. In the new file it is faint at 1.318s
and fully opaque by 8.0s, so the finished word is now shown for the remaining 23 seconds. No
separate work was needed. (The sandbox is at BLOCK granularity, so the whole body fades together —
that is the default state it was restored to, not a regression of the LETTER work.)

**A DEFECT IN `2883b8c` FOUND BY READING BEFORE BUILDING:** it left `buildClipItem`'s `@NonNull`
stranded above the new method's javadoc, so `ensureBlackFillerUri` carried **both** `@NonNull` and
`@Nullable` while `buildClipItem` carried none. Legal Java, so a green build would never have said
so. Moved back.

**BUG C is still open and the evidence is CONSISTENT with that**, which is itself a control: image
overlay `edff4a88` spans 6009–11009, now inside the rendered window, and it is **absent** from the
t=8 and t=10 frames while both sprites and both text boxes draw in the same frames. So the tail
filler renders overlays generally, and images specifically are still broken at
`CompositeExportOverlay`. Untouched by this change.

**Verification:** harness **298 passed, 0 failed** (unchanged from `e3b0ef5`), matte harness ALL
PASS, both from a clean compile. APK dex-scanned with `FadCamApplication` as the positive control —
**3 hits, where the corrupt APK `2883b8c` warned about read 0** — alongside `ensureBlackFillerUri`
(3) and the new-path strings `"ms black filler"`, `"Tail filler"`. Editor opened with 0 fatals.

**Residual, stated rather than hidden:** the video now runs 30.776s against 30.912s of audio, so it
is still ~136ms short — `getTotalDurationMs` (30771ms) is itself slightly under the audio stream's
encoded length. 99.6% coverage against 18.6% before. Also, `getTotalDurationMs` is max(video, AUDIO)
and does not consider OVERLAY ends, so a text box extending past the last audio clip would still be
clipped. Neither was worth chasing today; both are recorded so nobody rediscovers them as new.

Evidence: `tasks/screenshots/export_tailfiller_4frames.png` (the four tail frames side by side),
`export_dialog_00_05_before.png` and `export_dialog_00_30_after.png` (bug A2 on the phone).

**A GRADLE LIE, CAUGHT BY MTIME — new instance of the standing rule.** The build reported
`compileDefaultDebugJavaWithJavac UP-TO-DATE` **immediately after an edit to that very file**, while
the `.class` mtime was 4 seconds AFTER the edit and the APK 4 seconds after that. The artifact had
in fact been rebuilt; the task-state line was simply not trustworthy. **Read mtimes and dex symbols,
never the task states** — and note this is the opposite failure from the ledger's usual one (here
`UP-TO-DATE` under-reported real work, rather than masking a stale artifact).

**A NEW FINDING, NOT CAUSED BY THIS CHANGE — A RIG-DRIVEN SPRITE DRIFTS ON EVERY OPEN/CLOSE.**
Opening the sandbox and closing it **without touching the preview** moved sprite `8850f07c`:
`centerX` 0.9237256 → 0.8064244, `centerY` 0.1685828 → 0.1312584, `sizeFraction` 0.25 → 0.2584466.
**The control is in the same file and the same save:** sprite `81563c97` did NOT move. The
difference is that `8850f07c`'s `sheetId` (`08c2e885`) is the sheet the `a6-smoke-rig` drives with
`dangle: true` — so the DANGLE SIMULATION's settled state is being written back into the document.
Merely viewing a project silently edits it, and it **accumulates across sessions**. Filed here, not
fixed — it is a real bug but it was not this session's job. Sandbox restored to `82d8342d`.

**A CORRECTION TO THE HANDOFF: the sandbox md5 is `82d8342d`, NOT `eb3d16b8`.** Measured on device
with `run-as … md5sum`. The content is otherwise exactly as documented (PICKERTEST present, both
image overlays back at their original spans), so this is a stale figure in the handoff rather than a
changed project. **`eb3d16b8` should not be used as a restore target.**

**A NEAR-MISS WORTH THE WARNING — I TRUNCATED `project.json` TO ZERO BYTES.** The restore used
`MSYS_NO_PATHCONV=1 adb push sandbox.json /sdcard/…` with a POSIX-ish LOCAL path; the push failed
(mangled to `C:/Program Files/Git/sdcard/…`) but **the `cat > …` half of the pipeline still ran and
emptied the file** (md5 `d41d8cd9…`, the empty-file md5). Recovered immediately from the local pull.
The ledger already says local paths must be WINDOWS-style under `MSYS_NO_PATHCONV=1`; the new part
is that **the write half executes even when the push half fails**, so a failed push is not a safe
no-op. Verify the push line before trusting the pipeline, or split it into two commands.

**THE TEXT-BOX PICKER IS CONFIRMED ON A PHONE — 2026-07-30.** The four-granularity gate was open in
code (`textAnimGranularitySupported` returns true, the picker is passed `null`) but had never been
seen on a device. It has now: long-press PICKERTEST in the PREVIEW → sheet in PEEK → drag the handle
up → scroll the action list → **More…** → Edit text → the MOTION row's `≡A`. The popover draws
**NINE preset tiles** (None / Type / Fade / Rise / Ghost / Beam / Matrix / Unscramble / Mask wipe)
over an **"Animate by" row offering all four: Letter, Word, Sentence, Block** — where a text box used
to be offered Block alone. Screenshot `tasks/screenshots/textbox_picker_nine_tiles_four_grans.png`.
The nine tiles are also the behavioural freshness proof for the installed build. Dismissed with BACK
rather than by picking, and `project.json` verified still `eb3d16b8` afterwards — **picking a preset
writes immediately, so observing must not become editing.**

**TEXT BOXES HOLD FRAME RATE AT LETTER — measured 2026-07-30.** Matched 6s playback runs from t=0
over the same span, changing ONLY `textAnimGranularity` on disk with the app force-stopped,
`dumpsys gfxinfo` reset after load and before play:

| | LETTER | BLOCK (control) |
|---|---|---|
| Frames | 273 | 278 |
| Janky | 89 (**32.60%**) | 99 (**35.61%**) |
| 50th | 12ms | 11ms |
| 90th | 24ms | 24ms |
| 95th | 29ms | 30ms |
| 99th | 89ms | 97ms |

**Per-glyph layout on the new `TextBoxRenderer` costs nothing measurable** — the same conclusion the
caption path reached, now established for the second renderer. **Read the direction honestly: LETTER
scoring BETTER than BLOCK is noise, not a speedup**; the point is that the difference is small and
pointing the wrong way to be a cost. Both arms bracket the known ~33.7% editor baseline (video
decode + waveform), so the jank is the editor's, not the animation's.
**Stated weakness:** unlike the caption measurement, this run has no in-run screenshot proving the
two arms looked different. That they do is established elsewhere in the session (the preview at
1.318s under LETTER draws only `PI`), but not inside these two runs. A future repeat should capture
one frame per arm.

**THE ENGLISH UI WAS FULL OF MOJIBAKE. FIXED 2026-07-30.** Spotted in passing while exporting: the
export dialog read `00:05 â€¢ 4 clip(s) â€¢ 3 audio` and `background service â€" you can…`. It was
not a compile-encoding problem — `gradle.properties` already sets `-Dfile.encoding=UTF-8`. **The
corruption was committed into the file itself:** `app/src/main/res/values/strings.xml` literally
contained the characters `â€¢`, `â€”`, `â€¦`, `â€“`, `â€™`, `Â·`, `Â©`, `Â°` — a UTF-8 → cp1252 →
UTF-8 round-trip that had been saved at some point. **122 sequences across 108 lines**, i.e. every
em dash, bullet, ellipsis, en dash and curly apostrophe in the DEFAULT locale.
**The control that made it unambiguous: the translations were clean.** `values-fr` and `values-in`
carry the correct `•` and `—` on the very same string, so this was damage to one file, not a
project-wide convention.
**Repaired 120 of 122**, by decoding each sequence back through cp1252 rather than by hand-listing
replacements. Verified: the XML still parses, `<string>` count identical **2673 → 2673**, elements
2694 → 2694, and the diff is **107 lines changed 1-for-1 with zero replacement characters
introduced**. One line looked like it had been damaged by the fix; a codepoint dump proved the
opposite (`U+00E2 U+20AC U+00A2` → `U+2022`) and the "damage" was the terminal's own rendering —
**a reminder that a console is not evidence about encodings.**
**The 2 left alone are EMOJI whose bytes are genuinely lost** (they now hold U+FFFD replacement
characters), e.g. `watch_status_recording`, `shape_picker_title`, `rename_dialog_toast_success` and
three `stream_notes_*`/`remote_battery_low_warning` strings that were probably ⚠️. They were already
broken before this change and cannot be recovered mechanically — **guessing which emoji belonged
there would be inventing UI copy**, so they are left for a human. Listed here so they are findable.
**Proved end to end:** the em dash is `e2 80 94` in the built `resources.arsc`, and the installed
app now draws `00:05 • 4 clip(s) • 3 audio` / `service — you can close the app`.
Before/after: `tasks/screenshots/strings_mojibake_before.png`, `strings_mojibake_after.png`.
*Not covered by the `// TODO(strings)` freeze — that is about un-extracted hardcoded Java strings;
this is corrupted characters inside a resource file that already exists.*

## 1e. GHOST'S BLUR SHIPS, AND NEON_FLICKER IS BUILT-BUT-UNSEEN — 2026-07-30

**GHOST's blur — `0f8c404`, DONE and device-proved.** A recorded user decision with a condition
attached ("if ghost preview would cause noticeable lag... the divergence is warranted"), so the
answer was a number. Measured with the layer type read from a flag file, one build, both arms:
**193.5us -> 591.0us** on a 1041x564 box (+205%) and **201.0us -> 566.0us** on a 1080x1031 one
(+182%); n = 54-62 windows of 30 draws each. ~0.4ms absolute, **2.4% of a 16.7ms frame** — not
noticeable lag, so the sanctioned divergence was NOT taken and both surfaces blur.
The spec's premise was wrong twice over: the preview draws each text box in its OWN view, so
only a blurring box pays, and only while on screen. Recorded as a LOWER BOUND (times the inside
of onDraw, excludes the layer's bitmap allocation/upload).
Evidence: `tasks/screenshots/ghost_blur_preview_ramp.png` — soft early, sharpening as it
settles, with `PICKERTEST` in the same frame staying sharp as a free positive control.
**Scope: TEXT BOXES only.** Captions still ignore `blurPx` — one shared view for all words, so a
different cost profile and a separate decision.

**NEON_FLICKER — `0566390`, BUILT AND NEVER SEEN. Do not mark it verified.** In the installed
APK (control 3, `NEON_FLICKER`=1, `glowPx`=3), but the Note 9 was unplugged immediately after the
install, so the look, the flicker rhythm and the entire caption-side glow are unobserved.
Its recorded blocker named the wrong obstacle — modulation was never the hard part, HAVING
something to modulate was, since stroke and glow are optional per-object properties a default
text box lacks and a caption has no per-object form of. The preset supplies its own glow
(`Transform.glowPx`) in the unit's own fill colour. One assumption WAS checked on device first:
`setShadowLayer` is honoured for text on a hardware canvas (30px magenta halo, seen live), which
is why this needs no software layer and no divergence.

**Five harness errors this session, each of which read as a finding first:** a JSON-injected
preset is inert without `textAnimInPct`/`OutPct` (the picker seeds them, the model does not);
whole-window jank measured the wrong thing and its arms alternated state; a per-instance counter
never filled because `TextBoxView`s are recreated constantly; a cyan probe glow vanished against
a teal carpet; and non-ASCII in a Java string literal broke the harness build, costing the
NEON_FLICKER invariant test, which was written and reverted. Harness unchanged at 298/0.

## 1f. THE TEXT-BOX TIMING CARETS ARE BUILT, AND THE DRAG QUESTION IS ANSWERED — 2026-07-30

**§3h is closed.** The carets parked since `27762a6` now ride a TEXT BOX's tape on its layer row:
drag `▶`/`◀` inward, the zone tints, release commits. The interaction is the one the user drove
and approved for captions, unchanged — no redesign, no slider pair.

**The open question since 2026-07-29 — "are the carets grabbable without stealing trim-handle
grabs" — is ANSWERED, by scripted drags, which is the only thing that could answer it.** Six real
gestures on the Note 9 (`bb2a9deb`, PICKERTEST, preset RISE):

| Gesture | Result |
|---|---|
| entrance caret → x=625 | `textAnimInPct` **0.1241798**, predicted **0.1243** |
| entrance caret → x=750 | **0.359678**, predicted 0.3634 (2-point fit: travel 265.4px, x0 559.1) |
| exit caret → x=750 | `textAnimOutPct` **0.20713**, predicted **0.204 ± 0.004** |
| entrance caret → far left | in = 0 (sparse-omitted), **carets STILL DRAWN** |
| **trim cap at x=305** | **"Overlay time range"** — the TRIM, not the caret |
| **trim cap CENTRE x=380** | **"Overlay time range"** — the trim again |

**Undo moved by exactly ONE per gesture: 32 → 33 → 34 → 35 → 36**, each `Recorded: Text
animation timing`, and the trim grabs recorded `Overlay time range` instead. The drag previews
live (`onTextAnimZonesPreviewed`, no undo, no autosave) and commits once on release against the
value captured at DOWN — the before-values travel WITH the callback, because by release the model
already holds the gesture's own preview and an implementer reading "the current value" would undo
to the last pixel of the drag.

**Neither caret erases the other's zone.** The exit drag left `textAnimInPct` at 0.359678
untouched, and the entrance-to-zero drag left the out-zone at 0.20713. That is precisely the
symptom of the negative-travel trap (§3g), so it is now pinned on a real device as well as in the
harness.

**Geometry, measured rather than assumed.** Item tape L=316.4, R=877.8, inset 19.69px (10dp at
density 1.96875), travel 261px. All three caret positions match the model within ~1.5px. The
tape's right edge was CONFIRMED by scrolling until the exit caret came into view — before that,
the item body was clipped at the row's right edge (1063.5) and the screen could not distinguish an
item ending at 1109 from one ending at 3449.

**The trade-off, stated honestly because it is real.** The caret is hit-tested BEFORE the row's
trim handles (it must be — the row handler swallows the touch otherwise), with the tighter zone:
caret ±0.9×inset against trim ±inset. So the band `[L+2, L+19.7]` — about 18px immediately right
of the trim cap's centre — belongs to the caret. The DRAWN cap spans `L±3.9`, so its centre and
left side are trim (proved twice above) and only its rightmost ~2px are not. This is the same
relationship the caption carets had with the master trim bar, i.e. the version the user drove.

**What changed, and where the maths now lives.** The px half of the mapping moved OUT of the view
into `CaptionAnimator` (`caretTravelPx` / `caretInX` / `caretOutX` / `zoneFromCaretInX` /
`zoneFromCaretOutX`), where the harness can reach it: **303 → 326 checks, 0 failed**, including
the negative-travel trap from both sides and a sweep asserting every tape width that offers carets
can reach the cap. **The new checks were proved to DISCRIMINATE:** reintroducing the old
`Math.max(1f, …)` floor fails exactly 4 of them, and the file was restored after.

`LayerRowRenderer.itemBodyRect` is the one derivation of an item's tape — content-x for left/right,
SCREEN-y for top/bottom, written to mirror `hitTestItem` line for line (same 6dp minimum width,
same 3dp inset, same `itemsBottom()`, same band mapping inverted). Draw and hit-test both call it,
so they cannot drift. `rowContentXRange` was added alongside it and the caret draw clips to it —
without that, an item scrolled partly off-screen left would paint its tint and caret over the
PINNED row headers, since `itemBodyRect` deliberately reports the true tape rather than a clipped
one. Found by reading the geometry, not on screen.

Freshness controls on the APK: `itemBodyRect`/`caretTravelPx`/`zoneFromCaretInX`/
`onTextAnimZonesPreviewed`/`drawTextAnimHandles`/`itemHandleHalfWidthPx`/`rowContentXRange` all
PRESENT, the deleted `hitTestCaptionAnimHandle` and `drawCaptionAnimHandles` both **ABSENT** (a
stale dex could not show that), `FadCamApplication` = 3 as the partial-dex control. Gradle
reported `compileDefaultDebugJavaWithJavac UP-TO-DATE` on a build that had in fact recompiled —
the artifact was trusted, not the report.

Sandbox `bb2a9deb` restored and verified at **`82d8342d`**, 11 projects, rotation lock 0. The
restore used a device-local byte-exact copy (`cp` inside `run-as`), never an `adb push`, so the
truncation hazard was not in the path at all.

**Incidental finding, NOT caused by this work: audio clip IDs are regenerated on every
load/save.** A full deep-diff of the project before and after the test showed the three
`audioClips[].id` values (and their mirrored `items[].id`/`payloadId`) all changed, alongside the
intended caret edits. It is the same class as the §3 rig-driven sprite drift — opening a project
mutates data the user never touched — and it belongs on that list.

## 1g. ODOMETER IS BUILT — the last of the five presets, 2026-07-31, `d9c54e4`

**The user's spec correction IS the design.** The written design said the fillers came from
MATRIX's deterministic `mix`. Wrong: a wheel is an ORDERED ring and the point of an odometer is
that the roll can be READ. `charAtWheel(k)` is now the real character stepped BACK `k` places
along its own ring (`0-9`, `a-z`, `A-Z`), counting UP into place. That is also strictly MORE
deterministic than the hash it replaces — no `mix` for preview and export to agree on, only
arithmetic. A character with no ring keeps its own glyph and, alone, does not roll.

**Its scope question was moot, and THAT is the lesson.** It asked how to cope with the text-box
preview being a `TextView`. It was escalated to the user twice and declined once
(*"skip odometer for now i dont know how to answer"*) — and the answer was never a preference.
Option (b) had already been built for unrelated reasons: both text-box surfaces draw through one
shared `TextBoxRenderer`, so there was nothing to gate around. **Re-derive a blocker against the
code before asking a human to arbitrate it** — paid for four times in this one spec now.

**Fourth output channel:** `CaptionAnimator.rollUnit` + `rollClip`, android-free so the harness
reaches them. The travel IS the clip's height, read off the rect rather than recomputed, so window
and distance cannot drift. Four consumers: both caption renderers, the shared text-box renderer,
and the picker tile — which must drive it or ODOMETER would advertise three static "A"s.

**Harness 326 → 350, 0 failed, and the checks DISCRIMINATE:** injecting a hash into the digit ring
fails exactly the two asserting ring adjacency and monotone approach. One of those two only became
a real check *because* that injection exposed the first draft as vacuous — it could fail for a
single character value only. A control that cannot discriminate is not a control.

**`TextBoxRenderer`'s roll scratch is THREAD-LOCAL, not static.** Every method on that class is
static and stateless, so a plain `static` buffer would have looked consistent and been a data
race — the main thread draws the preview while the export draws on its own worker. It also avoids
`ThreadLocal.withInitial`, which is API 26 against this module's minSdk 24.

**PROVED ON THE NOTE 9 — the authoring half only.** ODOMETER now APPEARS in the picker (it was
filtered out by `!p.implemented`), **its tile rolls**, "Animate by" offers all four granularities,
selecting it records **ONE** undo step (38 → 39, `Recorded: Text animation`), the MOTION row reads
*"Odometer · 25% in / 0% out of this box"*, and `"textAnimPreset": "ODOMETER"` lands on disk.

**~~⚠ NOT PROVED: THE RENDER. Do not mark this verified.~~ THE PREVIEW RENDER IS NOW PROVED —
2026-07-31, ten frames, character for character. See §1h.** The paragraph below is kept intact
because its refusal to claim the stale frame as evidence was correct, and because the reason the
capture was hard is recorded there: the span in the predictor was wrong (5820ms, read off the
tape; it is really 30771ms), which made the roll look ~1.5s long instead of ~7.7s.
**The EXPORT half of ODOMETER is still unproved.**
`tasks/odometer_predict.py` was written
BEFORE any device work and models `unitProgress` → `decelerate` → wheel → ring. One captured frame
showed the box mid-roll reading `HAUCWJLW…`, which is exactly the model's **progress-0** row
(`HAUCWJLWKL`) — but the time chip in that same grab read 72ms, so the preview render was STALE
rather than agreeing, and a match against the wrong clock is not evidence. The character-for-
character proof MATRIX got still has to be taken. The model is ready; what is missing is a frame
whose playhead and glyphs provably come from the same instant.

**Two accidental edits were made to the sandbox during this session and both were caught**: a
scrub swipe landed on the object sheet and recorded `Add text overlay`, and another trimmed a
master clip (`Trim [535–1035] → [0–1035]`). Undoing the first **deleted PICKERTEST itself** — so
undo of an "Add text overlay" step is not necessarily inverse to what the label suggests when the
stack has been disturbed. Recovery was the device-local byte-exact backup, not undo.
**Scripted swipes aimed at the timeline are unsafe while a bottom sheet is open** — the sheet owns
that region. Close it first, or move the playhead with the PLAY button, which cannot edit.

## 1h. ODOMETER'S RENDER IS PROVED — character for character, 2026-07-31

**§1g's one open claim is closed for the PREVIEW.** The handoff's first job was a frame whose
playhead and glyphs provably come from the same instant. Ten of them were taken.

**FIRST, THE BLOCKER THAT WAS NOT A PREFERENCE: the span was wrong, and it was wrong in the
predictor, not on the phone.** `tasks/odometer_predict.py` assumed **5820ms**, read off the item's
TAPE, and the handoff recorded the contradiction with the editor's `00:30` header as unresolved.
Settled by reading the code rather than by looking harder at the screen:

- PICKERTEST carries **no `startMs` and no `endMs`** on disk (verified in `project.json`).
- `TextOverlayItem` defaults are `startMs = 0`, `endMs = Long.MAX_VALUE`, and `animSpanMs`
  resolves an open end to `timelineDurationMs`.
- The preview feeds it the **same value the export does** — `getProjectDurationMs()` delegates to
  `Timeline.getTotalDurationMs()` — so there was no preview/export ambiguity underneath it.
- `getTotalDurationMs` = max(sum of trimmed clip durations, latest audio end). From `bb2a9deb`:
  video `250 + 4566 + 427 + 500` = **5743ms**, audio end **30771ms**, max = **30771ms**.

Both halves reproduce figures this ledger measured independently in §1c (5743 master track, 30771
project duration, export dialog `00:30`), so the number is triangulated rather than asserted. **The
tape is simply a different quantity** — items are drawn against the master track — and it was the
editor header that agreed with the model all along. The correction is committed at `c326524`,
**deliberately BEFORE any device work, so the model was frozen ahead of the capture.**
Confirmed a third time on screen afterwards: playback ran to a playhead of **`00:30.771`**.

**Consequence, and it is why this capture was easy where the last one was not:** the in-zone is
**7693ms**, not ~1455ms. The roll is readable for nearly eight seconds. The previous session was
hunting a frame inside a 1.5s window and caught a stale one.

**THE MEASUREMENT.** Preset set to ODOMETER entirely on-device (`sed` + `cp` inside `run-as`, no
`adb push` in the path), guarded by a **whole-file deep-equality diff that reported exactly ONE
difference**, `textAnimPreset: RISE -> ODOMETER`. Byte sizes reconciled independently: 63215 ->
63219 is exactly +4, the length of `RISE` -> `ODOMETER`. The playhead was moved with the **PLAY
button only** — it cannot edit — and ten `screencap` frames were taken during playback, each
carrying the time chip and the glyph row in the SAME framebuffer.

| chip | predicted upper (outgoing) | predicted lower (incoming) | on screen |
|---|---|---|---|
| 00:00.009 | — | `HAUCWJLWKL` | `HAUCWJLWK…` |
| 00:00.295 | `HAUCWJLWKL` | `IBVDXKMXLM` | both, as predicted |
| 00:01.043 | — | `JCWEYLNYMN` | `JCWEYLNYM…` |
| 00:01.818 | `KDXFZMOZNO` | `LEYGANPAOP` | both |
| 00:02.644 | `LEYGANPAOP` | `MFZHBOQBPQ` | both |
| 00:03.454 | `MFZHBOQBPQ` | `NGAICPRCQR` | both |
| 00:04.271 | `NGAICPRCQR` | `OHBJDQSDRS` | both |
| 00:05.028 | — | `OHBJDQSDRS` | `OHBJDQSDR…` |
| 00:05.570 | `OHBJDQSDRS` | `PICKERTEST` | both |
| 00:06.576 | — | `PICKERTEST` | `PICKERTEST` |

**10 of 10 match.** The trailing character is clipped by the preview's right edge on the
single-row frames, so nine glyphs are read rather than ten; the nine that are legible match
exactly. Evidence: `tasks/screenshots/odometer_roll_proof.png` (all ten, each with its own chip),
`odometer_frame_1043_JCWEYLNYM.png` (one full untouched frame).

**THE SPEC CORRECTION IS VISIBLE IN THE DATA, which is the point of the preset.** The first
character steps **H → I → J → K → L → M → N → O → P** across the ten frames — monotone, adjacent on
its own ring, counting **UP** into `PICKERTEST`'s real `P`. That is a wheel that can be READ, not a
scramble. The upper/lower assignment is also confirmed rather than assumed: `TextBoxRenderer`
translates incoming DOWN by `phase*slotH` and outgoing UP by `(1-phase)*slotH`, so incoming is the
LOWER row — and on every two-row frame the lower row is the model's `incoming`.

**THE CONTROLS DISCRIMINATE, and they were checked rather than asserted** — the house rule that
caught a vacuous test last session:

| model fed the same ten frames | frames matched |
|---|---|
| **true model, span 30771** | **10 / 10** |
| ring, span 5820 (the OLD wrong span) | 2 / 10 |
| ring, span 15000 (arbitrary wrong span) | 2 / 10 |
| **SCRAMBLE fillers (hash pool), span 30771** | **0 / 10** |

So the test can fail, and it fails for exactly the two defects it exists to detect: a wrong span
and a scramble-instead-of-a-ring. The scramble arm scoring **zero** is the strongest of these — a
hash pool cannot produce these glyph rows at all.

**The staleness trap that ruined the last attempt is closed by construction:** ten different clocks
produced ten different glyph rows advancing monotonically with the clock. A frozen preview shows
one row. Note also that the progress-0 row `HAUCWJLWKL` — the row the previous session caught at a
72ms chip — is reproduced here at a **00:00.009** chip, so that observation was right about the
model and right to be refused as evidence.

**SCOPE, STATED PLAINLY: this is the PREVIEW only.** No file has been exported with ODOMETER set.
The shared-renderer design says the export must agree — `rollUnit`/`rollClip` live in
`CaptionAnimator` and both surfaces reach them through the one `TextBoxRenderer` — but that is an
argument from construction, which is precisely what this ledger does not accept as proof. **The
export half of ODOMETER remains unproved and must not be claimed.**

Sandbox restored to **`82d8342d`** from the device-local byte-exact backup and verified; 11
projects; rotation lock 0; 0 `FATAL EXCEPTION` across the walk.

## 1i. THE RIG-DRIVEN SPRITE DRIFT DID NOT REPRODUCE — a NEGATIVE result, 2026-07-31

Docket item 1 was the sprite drift (§1c). **A clean open → play-to-end → Close & Save cycle on
`bb2a9deb`, with no edit of any kind, did NOT move sprite `8850f07c`.** It is still at exactly the
values §1c recorded as its BEFORE state: `centerX` 0.9237256, `centerY` 0.16858277,
`sizeFraction` 0.25.

This is recorded rather than quietly dropped, because it changes the shape of the investigation:
the drift is **not** a property of merely opening and closing a project, so §1c's wording
("opening and closing a project silently moves…") is too strong. Something else in that session
was a necessary condition and has not been identified yet. **Do not go looking for it in the plain
load/save path** — this run is the control that rules that path out.

Two further facts from the same cycle, both useful:
- The file was **byte-identical after open + full playback** (md5 unchanged) and only changed on
  **Close & Save**. So the write is on the save path, not the load path.
- **§2c REPRODUCED, and now minimally.** The same no-edit cycle regenerated all three
  `audioClips[].id` values and their mirrored `items[].id` / `payloadId` — **10 diffs total, of
  which 9 are these ids and 1 is `lastModified`.** Nothing else in the file moved. That is a
  cleaner reproduction than the one §2c was found with (which was tangled up with real caret
  edits), and it is now a one-command repro for whoever fixes it.

## 1j. THE PICKER NO LONGER UNDER-ADVERTISES GHOST — and a doc that contradicted its own body

Docket item 2, both halves. **Two stale javadocs and one stale behaviour, all the same root fact:**
GHOST's blur shipped in `0f8c404` (§1e) and three places were never told.

**THE DOC CONTRADICTION — found by the previous session, confirmed and fixed.** The handoff spotted
that `TextBoxRenderer.drawUnit`'s javadoc said *"blurPx is not applied, and that is a decision"*
while the body sets a `BlurMaskFilter`. It is worse than one stale paragraph: the **class-level**
doc said the same thing (*"deliberately NOT applied here… the one channel whose two surfaces
genuinely cannot match"*). The inline comment five lines below the contradiction was correct and
current the whole time, which is exactly how this survives review.

**The old text's PREMISE was right and its CONCLUSION was wrong**, which is the interesting part
and why it is corrected in place rather than deleted: a hardware canvas really does ignore
`BlurMaskFilter`. It assumed the preview's canvas had to STAY hardware. It does not —
`TextBoxView.applyBlurLayerPolicy` switches to `LAYER_TYPE_SOFTWARE` exactly when
`CaptionAnimator.presetBlurs(preset)`, so both surfaces honour the filter and there is no
divergence to avoid. **This is the fourth time a note in this feature has been wrong, and the
third time the wrongness was a stale claim about a wall that had already been demolished.**

**THE PICKER TILE — fixed, and the fix is a PARAMETER, not a constant.** The tile's comment read
*"t.blurPx is deliberately NOT applied. Neither CaptionOverlayView nor CaptionExportRenderer
consumes it today… When a renderer gains blur, this line is where the tile follows it."* A renderer
has since gained blur. **But "always blur now" would have been wrong**, because ONE picker serves
TWO targets and the honest answer differs:

- **TEXT BOX → blurs.** `TextBoxRenderer` draws `blurPx`. Not blurring under-advertises GHOST,
  which is the entire reason a user picks it.
- **CAPTION → does not.** Captions still ignore `blurPx` (one shared view for all words — a
  separate decision, still open as docket item 3). Blurring would advertise softness the app never
  draws, which is precisely what the old comment correctly guarded against.

So `show(...)` gained `targetBlursGhost`; the text-box call site passes `true`, the caption
overloads pass `false`. **The tile also switches to `LAYER_TYPE_SOFTWARE` when it blurs** — without
that the `BlurMaskFilter` would be a no-op on the popover's hardware window, i.e. a call into a
void that looks like function, the exact §3a failure mode. The filter is cleared per unit, since
`paint` is shared with the tile's background and the NONE glyph.

**MEASURED ON THE NOTE 9, with an alpha-invariant instrument — and the first instrument was WRONG,
which is worth more than the result.** The obvious measure (max edge gradient) gave GHOST 22 in the
text-box picker against 162 in the caption picker, and that comparison is **worthless**: both tiles
run their own loop clock and GHOST's blur RAMPS, so those were two unmatched phases. Repeating it
across frames proved it — the text-box tile also produced 331 (settled) and the caption tile 67
(nearly transparent). **A single-frame comparison of an animating tile is not a control.**

The sound measure is **`maxGradient / peakContrast`**: a sharp edge's gradient tracks its own
contrast, so the ratio stays ~1.8 however faint the glyph gets; blur drops the gradient without
dropping the peak. It was **verified to be alpha-invariant before being trusted** — a caption frame
with peak contrast of just **15** (all but invisible) still scored **1.73**.

| GHOST tile, left glyph, 12 frames each | ratios |
|---|---|
| **TEXT BOX picker** (`targetBlursGhost=true`) | 0.28 0.29 0.43 0.45 0.47 0.94 0.94 1.85 1.85 1.85 1.85 |
| **CAPTION picker** (`targetBlursGhost=false`) | 1.24 1.53 1.59 1.73 1.79 1.87 1.87 1.87 1.87 1.87 |

**Visibly blurred frames (ratio < 1.2): text box 7 of 11, caption 0 of 10.**

**The overlap at the top is not a weakness, it is a required property.** GHOST's blur returns to
zero as the unit settles, so the settled phase MUST look identical in both pickers — the four
text-box frames at 1.85 are that landing. The claim being made is "a blurred frame occurs only for
the text box", and that separates cleanly.

Two further controls: **in the same tile and the same frame**, the blurring glyph is soft while its
neighbour is crisp (paint, canvas and clock all identical, so phase cannot explain it); and the
caption tile's faint glyph is **dim but crisp-edged**, which is what makes "soft" distinguishable
from "faint" by eye as well as by number. Evidence:
`tasks/screenshots/ghost_tile_target_aware.png` (the three-panel comparison),
`ghost_tile_row_zoom.png` (the whole tile row, Fade/Type/Rise/Beam all crisp).

**Verified:** harness **350 passed, 0 failed**, from a clean out dir with the test class confirmed
present. Gradle again reported `compileDefaultDebugJavaWithJavac UP-TO-DATE` on a build that had in
fact recompiled — every `.class` postdates its source and the APK postdates every `.class`, so the
artifact was trusted and the task states were not. **Fourth instance of that lie.** `javap` confirms
the new 6-arg `show(..., boolean, OnPick)` overload exists in the compiled class. Both pickers were
dismissed with BACK and `project.json` re-verified at `82d8342d` each time — picking a preset writes
immediately, so observing must not become editing.

**AN ACCIDENTAL EDIT, CAUGHT AND REVERTED — and the rule it sharpens.** A `Trim [535–1035] →
[495–1035]` was recorded (undo 39 → 40) and autosaved. Cause: the "drag the sheet handle up" swipe
was replayed when **no sheet was in PEEK** — I had just closed the caption drawer — so it went
straight through to the timeline and grabbed the master clip's trim handle. The ledger's existing
rule is *"scripted swipes aimed at the timeline are unsafe while a bottom sheet is OPEN"*; the
sharper form is **a sheet-relative gesture is unsafe whenever the sheet is not actually there, and
that is the more common mistake, because the coordinates still look right.** Screenshot between
steps instead of chaining swipes. Recovery was the device-local byte-exact backup (`cp` inside
`run-as`, app force-stopped first), **not undo** — verified back to `82d8342d`.

## 1k. CAPTIONS BLUR TOO — the last GHOST surface, 2026-07-31

Docket item 3, and the decision the user attached a condition to: *"if ghost preview would cause
noticeable lag… the divergence is warranted."* So the answer had to be a number, and it is.

**THE OLD REASON FOR DEFERRING IT WAS NOT THE REAL ASYMMETRY.** The note said the caption decision
was separate because *"the caption preview is ONE shared view for all words rather than a view per
object, so its cost profile is different."* The conclusion was right — it WAS a separate decision —
but the stated reason does not survive reading. **One shared view is still exactly ONE software
layer, the same count a text box needs**; only its size differs. What actually differs is
**DURATION**: a text box's entrance happens once, while captions re-animate line after line for as
long as anyone is speaking. So the question was never peak per-draw cost (already known, ~0.4ms
from §1e) but **sustained frame rate**. That is what was measured.

**THE INSTRUMENT.** GHOST-with-blur against GHOST-without — the decision-relevant comparison. A
FADE control would have confounded the blur with GHOST's `dx`/`scale`. Both arms came from **ONE
build**, with the blur and the layer type read from a flag file (`files/nocaptionblur`), because
rebuilding between arms restarts the process and changes the thing being measured. The flag logged
which arm was live and both arms were confirmed in logcat (`caption blur enabled = true|false`).
Clip 1 of `bb2a9deb` (4566ms, `captionAnimInPct` 0.5 — a half-length zone, i.e. the worst case) was
switched `FADE → GHOST` on disk, guarded by a whole-file deep diff reporting **exactly one**
difference. Six runs, ~6s of playback each, `dumpsys gfxinfo` reset after load and before play:

| arm | janky | 50th | 90th | 95th | 99th |
|---|---|---|---|---|---|
| blur ON | 44.24% / 43.94% / 43.96% | 14 / 13 / 13 | 29 / 29 / 30 | 32 / 31 / 32 | 81 / 93 / 81 |
| blur OFF | 42.91% / 43.30% / **36.74%** | 13 / 12 / 12 | 28 / 27 / 27 | 32 / 30 / 32 | 85 / 89 / 105 |

**The between-arm difference is 3.06pp. The OFF arm's own within-arm spread is 6.56pp.** The
difference is comfortably inside the measurement's own noise floor, and the 99th percentile points
the WRONG way (blur ON is better in two of three runs) — the same signature the ledger already
recorded for LETTER-vs-BLOCK. **Read honestly: there IS a consistent small shift at the median
(+1ms) and the 90th (+2ms), and it is not claimed to be zero — but the median stays under the
16.7ms budget and the 90th was already over it in both arms.** Not noticeable lag, so the
sanctioned divergence was NOT taken and both surfaces blur, exactly as the text-box decision went.

**THE CONTROL THAT MAKES THE MEASUREMENT MEAN ANYTHING: the blur actually renders.** A no-op would
also have cost nothing, which would have produced the same table. Matched-clock frames from the two
arms (the bursts landed within ~17ms of each other, so this is not the unmatched-phase mistake made
earlier the same day):

| playhead | blur ON | blur OFF |
|---|---|---|
| 00:00.997 / 00:00.980 | `is` a smear | `is` sharp |
| 00:04.148 / 00:04.162 | `fluffy` heavily blurred | `fluffy` sharp |

**And the in-frame control is the good one:** at 00:00.997 the settled words `this` and `cat` are
SHARP in the blur-ON frame too, because a settled unit has `blurPx` 0. Only the entering word
blurs. A whole-view softness or a layer artifact would have blurred all three.
Evidence: `tasks/screenshots/caption_ghost_blur_ab.png`.

**What shipped.** `CaptionOverlayView.paintWord` and `CaptionExportRenderer.paintWord` both apply
the filter, cleared per word so it cannot leak through the shared `TextPaint`.
`CaptionOverlayView.applyBlurLayerPolicy` mirrors `TextBoxView`'s — the preview needs
`LAYER_TYPE_SOFTWARE`, the export does not, because it already draws into a software Bitmap canvas.
**That asymmetry is why blurring only one surface would have been a divergence rather than a
saving.** The measurement flag was removed before commit.

**`targetBlursGhost` IS GONE AGAIN, one commit after §1j added it.** With captions blurring, every
consumer of `Transform#blurPx` blurs, so the parameter had exactly one value. Removed rather than
left as always-true: **a flag with one value is dead flexibility that reads as a real choice**, and
the next reader would delete it without knowing why it existed. The reason it existed is recorded
at the draw site instead. §1j's reasoning was correct for the four hours it was true.

**Verified:** harness **350 passed, 0 failed** from a clean out dir, matte harness ALL PASS. Gradle
reported `UP-TO-DATE` on builds that recompiled for the fifth and sixth time; every `.class`
postdates its source and the APK postdates every `.class`. **Freshness control that a stale build
could not fake: `blurEnabled` is ABSENT from the compiled class** (the measurement flag, deleted),
and `show` has 2 overloads with 0 `boolean` parameters. Sandbox restored to `82d8342d`, scratch
files removed from the device, 11 projects, rotation lock 0.

## 1l. THE 2^61−1 STRANDING IS A LIVE BUG, DIAGNOSED AND FIXED — 2026-07-31

§3 item 5 recorded an unreachable text overlay in the sandbox carrying `startMs`
**2305843009213693951**, said *"where that value comes from is unknown, and it may be sandbox-only
damage from an earlier probe"*, and asked that nobody spend a session on it **without first
checking whether any code path can still produce it.** That check was done. **One can. Two, in
fact.**

**IT IS NOT SANDBOX DAMAGE.** The value appears in **three separate projects** — `bdd51919`,
`a2025388`, `aeb0517e` — plus `bb2a9deb`'s undo history. In every case it sits on a text overlay
whose text is `"Enter text"`, i.e. one made by the Text toolbar button, which creates items with
**no `endMs`** (open-ended). One project could be a probe; four instances with one signature is a
code path.

**THE ARITHMETIC NAMES THE CULPRIT.** 2305843009213693951 is exactly `Long.MAX_VALUE / 4`, and
that constant occurs in only a handful of places in the codebase. The chain:

1. `TimedItem.getDisplayDurationMs(fallbackMs)` returns **`fallbackMs - start`** for an item whose
   `endMs` is `Long.MAX_VALUE` — an open end resolves its length against the timeline total.
2. `LayerGestureController.resolveOverlapOnRow` passed **`Long.MAX_VALUE / 4`** as that total.
3. So an open-ended sibling's occupied block ran to `start + (MAX/4 − start)` = **exactly MAX/4**.
4. `nearestFreeStart`'s *"after the last block"* branch — commented **"always feasible, so a legal
   spot ALWAYS exists"** — returns `max(tailLo, desiredStart)` = **MAX/4**.
5. `applyCommittedStart` writes it via `setTimeRange`.

**The result is an item ~73 million years down the timeline: no playhead reaches it, no long-press
finds it, no timeline chip shows it. It cannot be edited or deleted through the UI at all.**

**THE IRONY IS THE FINDING.** This is the **commit-time guard**, whose own comment calls it *"the
LAST line of defence that guarantees the PERSISTED state never overlaps, whatever the live preview
showed."* The **live** resolver is handed a real `totalEffectiveMs` and is fine. The guard that
exists to protect what reaches disk was the only thing corrupting what reached disk.

**A SECOND PATH, found by checking the other uses of the same constant rather than stopping at the
first hit.** `trimSiblingFloor` also resolved sibling lengths against `Long.MAX_VALUE / 4`, so
LEFT-trimming an open-ended text item on a row with an open-ended sibling sets `floor` = MAX/4 and
strands it the same way. **Its guard could not catch it:** the very next line clamps with
`maxStart == Long.MAX_VALUE ? newStart : maxStart`, and an open-ended item's `maxStart` **is**
`Long.MAX_VALUE` — so for exactly the items at risk the clamp is a no-op. `trimSiblingCeil` was
never able to strand anything (it narrows to a sibling's START, never the computed end) but was
moved onto the same helper so the trap is not left half-armed.

**THE FIX.** `lastTotalMs` is captured on DOWN and on every MOVE — the commit path is the only one
not handed a total, since `onRowBodyUp` takes just a boolean — and `effectiveTotalMs()` feeds all
three sites. **Its fallback is 0, not a large sentinel, and that direction is deliberate:** with 0
an open-ended sibling's length clamps to 0 through its own `Math.max(0, …)`, contributes no block,
and the resolver returns the drop position unchanged. **When the total is unknown the right failure
is to leave the item where the user put it, not to fling it somewhere unreachable.** A large
fallback is what caused this in the first place.
`Long.MAX_VALUE / 4` survives in two places on purpose, both now commented: `applyTrim`'s `ourEnd`
(a comparison bound only — nothing derived from it is stored) and `breakthroughMs()` (a
push-through threshold whose sibling `maxStartMs()` already clamps to the real total).

**DEVICE PROOF ATTEMPTED 2026-07-31 AND NOT ACHIEVED. The attempt is written up because the
NEXT attempt should not repeat it.** Four scripted drags across two builds (fixed, and a
deliberately reverted before-arm) all produced sane values, and **the before-arm produced sane
values too** — so the test did not discriminate and proves nothing in either direction. **The fix
is not disproved; the experiment is invalid.** What went wrong, from a temporary `STRANDPROBE` log
inside the guard:

| probe output | what it means |
|---|---|
| `newLayerZone=true, commitDur=5743` | the long-press grabbed the WRONG item — the open-ended sibling, not the small one — because they overlapped, and the drop landed in the new-layer zone, skipping the guard entirely |
| `cur=500 fixed=500` | **`cur` at guard time is the item's PRE-drag start, not where the drag left it.** A setup whose pre-drag position does not already overlap the block can never reach the stranding branch |
| `guard: cur=2000 fixed=2000 items=1` | **the killer: `dest.getItems()` was 1** — the destination row held only the dragged item, so `nearestFreeStart` skipped it as self, found `n == 0`, and returned the desired start unchanged. No sibling, no block, no bug |

**So the necessary conditions for a repro, now known and each learned the hard way:**
1. The dragged item's **pre-drag** `startMs` must already overlap the open-ended sibling's block
   (`cur` is what the guard resolves, not the drop point).
2. The drop must leave the item on a row that **still contains the sibling** — assert
   `items >= 2` in a probe before believing anything.
3. The two must not overlap at the grab point, or the hit-test takes the sibling (`commitDur`
   identifies which item was actually grabbed — the sibling's is its full display duration).
4. Release with the finger in the RIGHT half of the panel (`fingerSidePref = +1`), or the resolver
   escapes to the *before* side and returns a sane value legitimately.
5. Avoid the new-layer zone (`newLayerZone=false` in the probe).

**A CORRECTION TO THE PREDICTION, confirmed by the probe: the relevant total is `totalEffectiveMs`
(the MASTER TRACK, 5743ms), not `getTotalDurationMs` (30771ms).** `EditorTimelineView` passes
`totalEffectiveMs` to the gesture controller, and the probe logged `lastTotalMs=5743`. One
observed drag landed at exactly **5743 / 5993** — an item butted against the open-ended sibling's
block end, dur 250 preserved — which is the arithmetic working, just not through the stranding
branch. **§1h's 30771 is right for the ANIMATION span and wrong for the drag system; they are
different totals and this ledger should not conflate them again.**

**The environment fought back, twice:** the Note 9 dropped off USB mid-run and the adb daemon died
a second time later. Both were recovered (`kill-server`/`start-server`), and neither corrupted
anything, but a long scripted repro on this rig should checkpoint state rather than assume a run
completes.

**Left clean:** the temporary probe and the reverted constant were removed via `git checkout`, the
built class was re-scanned to confirm `STRANDPROBE` is **absent** from the artifact, the clean fixed
build is installed, sandbox restored to `82d8342d`, device scratch files deleted, 11 projects,
rotation lock 0.

**PROOF STATE, STATED HONESTLY: traced by reading and corroborated by data — NOT device-proved.**
The mechanism is complete and every step is a named line; the predicted value matches the damage in
three real projects to the digit; the fix compiles, `effectiveTotalMs` is in the built class, and
harness **350/0** + matte ALL PASS are unchanged. **But no drag has been performed on a phone to
watch a sane value land where MAX/4 used to.** That is the missing half and it should be the next
session's cheap win: put two open-ended text items on one layer row, drop one past the other, read
`startMs`. **Do not mark this verified until that is done.** The three damaged projects are also
still damaged — the fix stops new stranding, it does not repair existing items, and repairing them
needs a migration decision (their `startMs` is unrecoverable, so the honest repair is to reset it
to 0 rather than guess).

## 1p. TAP LATENCY — FIXED ON THE SECOND ATTEMPT. The first attempt made it WORSE.

**Keep this entry for the shape of the mistake, not the fix.**

JoyRaptor, on a Note 20 holding his real 45-minute project: *"the tap to seek has a markedly long
delay… it seems to not be the same on the Note 9."* Correct observation, and not a device
difference.

**ATTEMPT 1 (REVERTED, `18e320b` → reverted in `4f4fe49`).** Diagnosis: a tap calls
`setExactSeek(true)`, so ExoPlayer decodes forward from the previous keyframe, and §3d measured
keyframes ~1.0s apart. Fix: seek FAST, then settle EXACT 180ms later — the two-stage shape
drag-scrubbing already used. **It did not help, and made the count worse.** A review found that
`EditorTimelineView.seekToTimelineMs` calls `onPlayheadSeeked` and then `onPlayheadDragFinished`
on the very NEXT line, and `onUp` fires the latter again — and its body did an unconditional
`setExactSeek(true) + pause + seekInClip`. So a tap ALREADY cost two exact decodes beyond its own,
and adding a settle made it four.

**ATTEMPT 2 (`b5a6c1b`, shipped).** The settle is drag-scrubbing's: when the finger lifts mid-scrub
the preview sits on a keyframe, so it re-seeks precisely. **A tap has no such problem — its own
seek was already exact.** `onPlayheadDragFinished` simply had no did-a-drag guard. Gated on
`wasRealDrag`, captured before `userDragging` is cleared (`userDragging = true` appears in exactly
one place, the drag-start path). This REMOVES decodes where attempt 1 added them.

**THE LESSON.** Attempt 1 was a plausible fix for a correctly-diagnosed cause, and it was still
wrong, because the diagnosis stopped one call too early. **Before optimising a path, read what
runs immediately AFTER the thing you are changing** — the cost was not in the seek I was looking
at, it was in the handler on the next line. And: a performance change that is not MEASURED is not
a fix. Attempt 1 shipped on reasoning alone and moved the number in the wrong direction.

**Not yet confirmed by the user on the Note 20.** Ask whether it feels faster AND whether it still
feels accurate when lining up a cut — the second question is the one a wrong fix here would break.

## 1o. THE DISLODGE GESTURE — BUILT 2026-08-04, HAND TEST OWED

JoyRaptor's scheme (§3A.5b): **hold ARMS, a vertical pull COMMITS.** It replaces long-press-goes-
straight-to-reorder, which fired on a plain timer with no stillness or intent test — so pausing to
think, or holding steady for a precision horizontal move, dropped you into reorder uninvited. That
was his actual complaint and it is now impossible.

- Hold → purple halo + up-chevron (`drawDislodgeArmedLift`). Hold alone does nothing functional,
  so without the cue "now pull up" is unlearnable and the gesture reads as broken.
- Vertical pull past ~24dp AND vertically dominant → dislodge, routed through the SAME
  `moveSelectedClipToLayer` the Move drawer's ↑ uses. One model op, two entry points, so gesture
  and button cannot drift apart.
- Deliberate horizontal move → DISARMS. Without this, holding then sliding sideways left the arm
  live and RELEASING opened reorder — the same interruption the scheme exists to prevent,
  reintroduced one level down. Caught by reading my own code, not by testing.
- Release without moving → reorder dialog, unchanged. **Double-tap** is a fast path to the same
  dialog; its first tap seeks and is NOT undone (see below).

**THE SEEK DECISION (JoyRaptor + agent, 2026-08-04).** He proposed seek-then-undo for instant feedback.
Rejected in favour of simply KEEPING the seek: you tapped that clip, so the playhead being there is
what you meant, and undoing it would cost TWO exact decodes inside 250ms on a path that is already
the slow one. Backing out of reorder restores the prior playhead **before the mode flips**, so the
nudge happens behind the reorder UI and is never seen — his own refinement, and the reason the
whole problem disappears.

**⚠ NOT DEVICE-VERIFIED.** `adb`'s `input swipe` interpolates movement over its whole duration, so
it cannot express hold-still-THEN-move: the long-press timer never survives to arm. One scripted
attempt was made per the house rule. The hand test is four steps and is written into
`NEXT_SESSION_PROMPT_20260804b.md`.

**NOT DONE: the insertion highlight.** §3A.4 specifies it, but the dislodge is currently DISCRETE
(vertical pull demotes immediately at the clip's own time) so there is no continuous drag to
highlight into. Correct order is recorded in §3A.5b: hand the in-flight touch to
`LayerGestureController` FIRST so a dislodged clip becomes an ordinary picked-up layer item and
inherits A9, WYSIWYG, edge-pan, minimap nav, the overlap resolver and the undo merge — all already
built. A second drag engine would rebuild every one of them worse.

## 1n. ✅ ANCHORING IS PROVED IN THE APP — end to end on device, 2026-08-03

The feature §1m records as INERT is now **verified working in the running app**, not just in the
harness. This closes the loop that entry opens.

**Method — the interesting part, because the obvious test was unreachable.** Creating an overlay
through the Text tool could not be driven (the bottom tool row is context-sensitive and scrolls,
and scripted double-taps do not register). So the anchor was **seeded directly into
`project.json`** and the app asked to honour it:

1. Seeded the existing overlay with `hostClipId` = clip 1, `hostOffsetMs` = 500, `startMs` = 3700
   (clip 1 started at 3200). Written via `adb push` to `/data/local/tmp` then `run-as cp` — never
   a push straight onto `project.json`, per the standing rule.
2. Opened the project and **demoted clip 0 to a layer** (M12's ↑). That removes clip 0 from the
   spine, which is structurally the same event as a delete — and it is a button that CAN be
   driven reliably, where the delete tool could not be reached.
3. **Result: `startMs` 3700 → 500**, exactly as predicted, with `hostClipId` and `hostOffsetMs`
   intact. Logcat: `ANCHOR[demoteToLayer] moved=1 orphaned=0`.

**So the whole chain is proved:** persisted anchor → read on load → structural edit → shift →
written back to disk.

**AND THE ATTACH IS PROVED TOO — the last link, closed the same session.** The Text tool WAS
reachable after all: the bottom tool row has a pinned group left of a divider and a
usage-sorted SCROLLING group right of it, and `Text` had simply scrolled out of view. Two
horizontal swipes on the right group brought it back. Creating an overlay produced
`hostClipId = 1decc7fa` — the clip under the playhead — written to `project.json` by the
autosave that `addTextOverlay` schedules before it even opens the editor dialog.
(`startMs`/`hostOffsetMs` are absent because both are 0 and the serializer is sparse.)

**M11 is therefore verified end to end: attach → persist → load → shift → save.** No link is
taken on faith.

Sandbox restored byte-exact afterwards (md5 `a74cd91c…`), seed file removed from `/data/local/tmp`.

**Reusable trick worth keeping: when a UI path cannot be driven, seed the STATE and drive a
DIFFERENT action that produces the same event.** A demote and a delete are the same structural
change to the spine; one was reachable and the other was not.

## 1m. THE ADVERSARIAL PASS — 21 CONFIRMED DEFECTS IN ONE NIGHT'S WORK, 2026-08-03

**Keep this entry. It is the strongest argument in this ledger for how to work here.**

Everything built on 2026-08-03 was handed to two review agents with one instruction: BREAK IT.
They confirmed **21 defects** in code that was compile-green, harness-green and committed with
confident messages.

**The one that matters most: M11 anchoring was completely INERT.** It shipped with 35 passing
checks against the REAL model classes, a debug invariant probe, and a commit message reading "the
shift lands, proved against the REAL model classes". Every word was true and the feature did
nothing: `attachOverlayToHostUnderStart` had **zero call sites**, so `hostClipId` was null for
every overlay in every project and `applyAnchorShift` returned on its first line, every time.
**An unreachable feature passes every test it has.** The question no test asked was "who CALLS
this?"

Others worth remembering as shapes:
- **A dialog that damaged data merely by being OPENED.** The mask sheet seeded a default box and
  applied it before `show()`, with no dismiss handling — BACK left a permanent hole in the PiP.
- **Fixing half a divergence made it worse.** §2d corrected text overlays; burned-in captions
  stayed wrong, so instead of everything drifting together (readable as "the export is offset")
  the user would see captions sliding against titles that were now right.
- **Two of four new sliders were dead on arrival** — both renderers gate on size AND colour, and
  the colours defaulted to transparent. The commit message asserted the gate was size-only.
- **"0 = off" was false for shadow** — the renderers read `radius > 0 ? radius : fontPx * 0.10f`,
  so 0 is the default shadow and dragging 0→1 makes it SMALLER.
- **A Cancel button is not a dismissal handler.** BACK, outside-tap and rotation all bypassed it
  and kept every live-written edit — and the stale snapshot then became the next "original".
- **A convenience constructor was a trap**: the 2-arg `BlendModeGlEffect` hard-coded the §2d
  offset to 0, so any future caller would silently reintroduce the drift with no failing test.

**RULE EARNED: after building anything, run an adversarial pass before believing it.** The two
reviews cost about twenty minutes and were worth more than the code they reviewed. A green
harness proves the logic; it says nothing about whether the logic is reached, whether the dialog
that drives it is safe to open, or whether the field it writes is ever read back.

## 2. OPEN — diagnosed, root cause known, NOT yet fixed

**2a. Playhead↔clip mapping — FIXED 2026-07-28, `1d061ea`. Moved to §1.**
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

**2c. Audio clip IDs are regenerated on every load/save. FOUND 2026-07-30, not yet diagnosed.**
A full deep-diff of `bb2a9deb` across one open-edit-save cycle showed all three
`timeline/audioClips[].id` values changed, along with the `items[].id` and `items[].payloadId`
that mirror them — while everything else outside the intended edit stayed byte-identical. Nothing
in the caret work touches audio; this fires on the ordinary load/save path.
**It is the same class as the rig-driven sprite drift (§3, item 3): opening a project silently
mutates data the user never touched.** Two reasons it matters beyond tidiness: an id that is not
stable across a save cannot be referenced by anything that outlives the session (undo history on
disk, links, mattes), and it makes "did this edit change only what I meant" unanswerable by diff
without knowing to ignore these fields — which is how a real mutation would hide. Found only
because the caret verification diffed the whole file rather than grepping the keys it cared about.

**ROOT CAUSE FOUND 2026-08-05 — it is a WRITE with no matching READ, and the reason is that the
setter does not exist.** Read out of the code, not inferred:
- `ProjectStorage:1907` writes `acJson.addProperty("id", ac.getId())` — the id IS serialized.
- `ProjectStorage:2460` loads it as `AudioClip ac = new AudioClip(acUri, acDuration)`, and both
  `AudioClip` constructors (`:141`, `:153`) do `this.id = UUID.randomUUID().toString()`.
- The load block that follows restores `inPointMs`, `outPointMs`, `offsetMs`, `layerId`,
  `volumeLevel`, keyframes, waveform and transcripts — **but never `id`**.
- **`AudioClip` has no `setId` at all** (grep returns nothing), so the load path could not have
  restored it even if someone had written the line. That is why this survived: it does not read
  like a forgotten field, it reads like a field that was never meant to round-trip.

So every load mints a fresh UUID and every save writes the new one — the value on disk is
write-only, which is the §3a failure mode this ledger exists for, in its other direction.
**Fix shape: add `setId` and restore it at `:2460`, and do it in the SAME change as the
`items[].payloadId` mirror** — the item's `payloadId` is what points at the audio clip, so
restoring one without the other swaps a silent id churn for a real dangling reference. Anything
that outlives a session and names an audio clip (on-disk undo history, links, mattes) is
unreliable until this lands. Not fixed here; diagnosed so the next session starts at the fix.

## 3. PROMISED — on the docket, must not be lost again

**3h. ~~THE TEXT-BOX TIMING CARETS~~ — BUILT AND PROVED ON DEVICE, 2026-07-30. Moved to §1.**
See the §1 row and §1f below. The decision record that follows is kept intact because it is why
the carets were built rather than a slider pair; do not re-litigate it.

**3h. THE TEXT-BOX TIMING CARETS — THE USER HAS ANSWERED. BINDING, 2026-07-30.**

The question this ledger has carried open longest is closed. The user's words:

> *"The carets should work just exactly like they did on the very first iteration with the closed
> captions before we decided to change it for the closed captions. That actually worked really well
> as far as text is concerned. Closed captions was a different story, and it needed something
> different. But originally, those carets were expected to be put on text in the first place. And
> how they were operating in the closed captions worked perfectly well for text."*

**So: revive the PARKED caret behaviour verbatim, aimed at a TEXT BOX.** Drag `▶` / `◀` inward on
the item's tape to set the in/out zones, with the zone tinted — the exact interaction that shipped
for captions in `27762a6` and was retired there. **Do not redesign it**, and do not substitute a
slider pair in the Edit-text dialog: that was offered as a cheaper alternative and the user has now
chosen the carets explicitly, for the second time.

**What it costs, re-derived by reading rather than estimated.** The machinery is complete and
harness-covered, and its MATH is already target-agnostic — `caretFractionForZone`,
`captionAnimTravelPx`, the `CAPTION_ANIM_MIN_TRAVEL_PX` floor and the fixed negative-travel trap all
take a `RectF` and a fraction. Three things are genuinely clip-shaped and must be replaced:
1. `selectedCaptionAnimClip()` resolves the target from `segments.get(selectedIndex).clip` and
   demands `hasTranscript()`. A text box is a `TimedItem` on a layer row.
2. The draw site is `segRects.get(selectedIndex)` — a MASTER-CLIP rect.
3. The zone getters read `Clip.getCaptionAnimInPct()`; a text box stores `textAnimInPct`.

**THE REAL OBSTACLE, AND IT IS NOT THE ONE THE OLD NOTE NAMED.** The note called it "a second
geometry" and left it there. Reading it out: an item's rect is **content-x for left/right but
SCREEN-y for top/bottom** (`LayerRowRenderer.hitTestItem` maps x through `timeToX` and y through
`bandLocalY`), and the layer band carries **its own vertical scroll**, independent of the timeline's
horizontal one. So reviving the carets needs a public `itemBodyRect(itemId, …)` on
`LayerRowRenderer` that MIRRORS `hitTestItem`'s geometry — one derivation, or the caret will draw
where the finger cannot grab it, which is the classic two-derivations bug this project keeps paying
for.

**DO NOT BUILD THIS WITHOUT A PHONE ATTACHED.** It is a DRAG on a tape whose carets sit ~10px from
the green trim handles, and this ledger already records that the caret-vs-trim grab "needs a drag,
not a screenshot". It is the one open item whose correctness cannot be established off-device at
all. **Not started 2026-07-30 for exactly that reason** — the Note 9 was unplugged, and blind-
building a two-coordinate-system drag is how `2883b8c` happened.

**2d. ✅ FIXED AND PROVED ON DEVICE 2026-08-03 — overlays no longer drift. Moved from OPEN.**
**THE FIX:** `ExportManager.editorTimeOffsetFor` → `CompositeExportOverlay` and
`BlendModeGlEffect`/`PipFrameOverlay`. **A/B on the same fixture: PiP onset 5.50s → 4.90s**, the
target, sharp step against a flat baseline.

**⚠ THE FORMULA THAT LOOKS RIGHT AND IS NOT — two attempts died here.** The offset is NOT
`editorStart - compressedStart`. Measured: for the clip immediately AFTER a seam those are
**EQUAL** (both 3200 in the fixture) — a transition does not push that clip later, it eats 600ms
off its **HEAD** by advancing its in-point. So a start-comparison returns **0 for exactly the clip
that needs the correction**, and is coincidentally correct for the NEXT one (600), which is what
made two rounds of plumbing look wired-but-inert. The real quantity is the cumulative content the
export has already swallowed: **the sum of every transition at a seam BEFORE this clip.**
**A one-line `DRIFTDIAG` log settled in one export what two rounds of reading did not.**

<details><summary>Original entry — how it was found and first proved</summary>

Found by reading while mapping §4A call sites, then **PROVED BY EXPORT on the Note 9**. Model was
frozen in `60861f1` (`tasks/PREDICT_transition_overlay_drift.md`) BEFORE the capture.

**THE MEASUREMENT.** Fixture `302da9ac`, unmodified: 600ms `tangentMotionBlur` at the seam
(editor 3200ms), PiP `6126e8b4` at `overlayStartMs` **5501**. Two hypotheses, frozen, 600ms apart:

| | PiP onset predicted | observed |
|---|---|---|
| drift (§2d right) | **5501 ms** | **5.50 s** ✅ |
| compensated (§2d wrong) | 4901 ms | nothing — baseline flat through 4.90 |

Measured as a redness step (the PiP is carpet, the bed is a child's room) at 50ms resolution:
`R−G` holds **3.8** from 4.55 through 5.45, jumps to **9.8** at **5.50**, and keeps climbing.
No step anywhere near 4.90. **The control discriminated**; the two arms were 12 samples apart.
Corroborating in the same file: the transition itself shows as the largest frame-to-frame change at
**2.7–3.0s**, exactly where compression puts it (clip0 solo 2600 + 600 transition), against an
editor seam of 3200.

**So the mechanism is confirmed end to end:** editor time is uncompressed
(`getSegmentStartTime:2063`), export time is compressed (`timelineCursorMs:904` + `:923-940`), and
`CompositeExportOverlay:449` compares the compressed clock against a start authored in the
uncompressed one. **Every overlay after a seam lands late by the cumulative transition duration.**

**Not yet decided: WHICH TIMEBASE IS AUTHORITATIVE.** Mapping overlay times through the compression
the export already computes is far smaller than making the editor compress. Decide deliberately —
and note §2a (terminal playhead short of the clip sum) and the 20260726 handoff's backward playhead
jumps are very likely the same root cause, so a fix should be checked against all three symptoms.

</details>

**2e. ✅ FIXED AND PROVED 2026-08-03 — image clips on the spine no longer kill the export.**
**ROOT CAUSE (read from media3's own source, not guessed — and the COLON was a red herring):**
`DefaultAssetLoaderFactory:187` routes to the IMAGE loader only when `TransformerUtil.isImage()`
is true AND `imageDurationMs` is set. We set the duration; `isImage()` was the failure. For a
`file://` URI `getImageMimeType()` resolves the type **purely from the FILE EXTENSION**
(`uriPath.lastIndexOf(".")`). Faditor copies picked images into `files/images/` under names
derived from a content-URI id — `asset_1785180024028_image:127376` — which have **no extension
at all**, so the lookup returns null, the still is handed to the VIDEO loader, and the export dies
with "the asset loader has no audio or video track to output".

**THE FIX:** `ExportManager.imageMimeTypeOf()` sniffs the file's magic bytes (PNG/JPEG/GIF/WEBP/
HEIF) and declares the type via `MediaItem.Builder.setMimeType()` at BOTH image build sites.
Sniffed rather than assumed because the declared type only decides ROUTING — `BitmapFactory`
re-detects the real format when decoding — so accuracy costs a 12-byte read.

**PROVED on device:** the same fixture that died at 8126ms now exports clean — no
`ExportException`, video **13.726s**, the image included. Regression-checked in the SAME file:
the §2d PiP onset is still **4.90s** and the transition still shows at 2.9s, so neither fix
disturbed the other. (Content compresses to 13126; the file is padded to the editor's declared
13726 by the existing tail-fill, `ExportManager:1051-1066`.)

<details><summary>Original entry — how it was found</summary>

The §2d export **threw** rather than completing:
`IllegalStateException: The asset loader has no audio or video track to output.` →
`ExportException: Asset loader error`. Clips 0+1 muxed (8126ms, matching 3200+5526−600), then
clip 2 — a 5000ms IMAGE — killed it.

- **The UI does report it** (`FaditorEditor: Export failed: Asset loader error`), so it is not
  silent to the user.
- **But a plausible-looking 8.1s partial MP4 is left in the output folder**, indistinguishable at a
  glance from a good export. That is the part likely to bite someone.
- The asset is NOT missing or empty: `files/images/asset_1785180024028_image:127376`, 1,442,861 bytes.
- **LEADING HYPOTHESIS, UNPROVEN: the literal COLON in the filename.** The name is derived from a
  content-URI id (`image:127376`) and keeps the `:`; the stored `sourceUri` percent-encodes it
  (`...image%3A127376`). A mismatch between the encoded URI and the on-disk name — or media3
  refusing the path — would produce exactly this "no track to output". **Test before believing:**
  copy the asset to a colon-free name, point a clip at it, export. A second control worth running is
  a freshly-added image, to establish whether this is every image or only these legacy assets.
- Do NOT conflate with §1d (image OVERLAYS export — fixed) — this is an image CLIP on the master
  spine, a different path.

The editor and the export run on **two different timebases**, and overlay start times are authored
in one and consumed in the other:
- **Editor time is UNCOMPRESSED.** `EditorTimelineView.getSegmentStartTime` (`:2063`) is a prefix
  sum of `SegmentData.effectiveMs` and **ignores transitions entirely**.
- **Export time IS COMPRESSED.** `ExportManager.timelineCursorMs` (`:904`) advances by each
  emitted item's real duration, and a transition SHORTENS the items it straddles (`:923-940`,
  clamped by `effectiveTransitionMs` `:1165`).
- **The consumer mixes them.** `CompositeExportOverlay:449` takes
  `timelineMs = presentationTimeUs / 1000` — Composition-absolute, i.e. compressed — and compares
  it against `o.getStartMs()`, authored in uncompressed editor time (`:341`, `:398`, `:618`, `:656`).

Predicted symptom: every overlay after a seam appears EARLY in the export by the cumulative
transition duration up to that seam, drifting further with each transition. A 1s cross-dissolve
therefore desyncs every downstream title, sticker, sprite and PiP by 1s — silently, and only in
the exported file. **This is exactly the failure shape §1b's export proof was built to catch, and
it slipped past because that proof's fixture had no transition.**

Corroborating, already on this page: §2a records that "the terminal playhead sits short of
`getTimelineEndMs()`'s sum-of-clips **whenever transitions overlap clips**" — i.e. the two
timebases are already known to disagree on total length. `HANDOFF_20260726` also names transitions
as the likely explanation for that run's backward playhead jumps. Same root, three symptoms.

**To prove it:** one project, two clips, ONE transition, an overlay starting after the seam at a
known time. Export, extract frames, compare the overlay's first frame against its authored start.
The control that discriminates: the SAME project with the transition removed — the overlay must
land on time there, or the instrument is measuring something else.
**Do not fix it before proving it**, and when fixing, decide deliberately which timebase is
authoritative — making the editor compress is a much larger change than mapping overlay times
through the same compression the export already computes.

</details>

**3i. ✅ SPINE ⇄ LAYER MOVE SHIPS — DEVICE-PROVEN 2026-08-03. The long-standing want is MET.**
JoyRaptor's headline ask ('drag layers like clips into and out of the main layer, like CapCut') now
WORKS, via the **reliable button path** the dragux_v3 decision (2026-07-05, user-proposed and
endorsed) said to build FIRST: the Move drawer's ↑/↓ — which until now showed "coming soon"
toasts — lift a spine clip onto a layer and drop a floating clip into the main track.

**Device evidence (Note 9, project `302da9ac`, read from `project.json`, not from the screen):**
- ↑ : `clips 3 → 2`, `overlayClips 1 → 2`. The moved clip `1decc7fa` carries
  `layerId=video`, `overlayStartMs=0` (its exact prior absolute position) and
  **`overlayAudioEnabled=true`** — the carry that stops a demoted clip going silent.
- Visually: the spine closed up (00:13 → 00:10), a new layer row appeared, and the clip renders
  as a PiP in the preview.
- ↓ : full ROUND TRIP restores `clips=3` with **byte-identical id order** to the original.
- One undo step each (`Recorded: Move to layer`, `Recorded: Move to main track`).
- Project restored byte-exact from a device-local backup afterwards (md5 verified).

**STILL OPEN on M12:** the DRAG gesture (§3A) — the spine is not in the row-gesture pipeline, so
that is a new gesture path, not an extension. The buttons are the shipped capability; the drag is
the delight layer on top, exactly as the 2026-07-05 decision sequenced it.

**3i-spec. THE SPEC BEHIND IT — BINDING AS OF 2026-08-03.**
The user's highest-priority want: CapCut-style dragging of clips INTO and OUT OF the main layer.
**Full interaction spec written into `PLAN_LAYERS_UX_ADDENDUM.md` §3A and §4A** — screen-relative
drop zones, dwell-armed split, content-aware pan (no jump-to-limit), the colour table, the one
anchor rule and its edge cases, and the ask-the-user prompt for orphaned anchors. Read those two
sections before writing any code; the decisions in them are settled and must not be re-litigated.
**Order: §4A anchoring FIRST (2–3 sessions), then M12 (4–6).** Nothing is built yet.
Three corrections this pass, each of which changes the cost:
- **A PiP overlay and a master clip are the SAME CLASS** (`Clip`, two lists, `layerId != null`).
  **M12 needs no schema change** — an earlier claim that it needs a type conversion was wrong.
- **Edge auto-pan (A1) and minimap drag-nav (A2) ALREADY SHIP** for layer-item drags. M12 extends
  them to master clips; it does not build them.
- **The ripple/gap toggle ALREADY SHIPS** (`toggleRippleMode()`). `PLAN_LAYERS_V2.md`'s unchecked
  M11 box is stale on that point — what M11 still owes is anchoring, which is unbuilt
  (`anchorClipId` appears nowhere in the source).

## 3a-DRAWER. THE PiP DRAWER IS A TOP, TRANSLUCENT, TABBED PANEL — 2026-08-05, device-proven

**User spec, 2026-08-05.** Verbatim asks: compact so the timeline stays scrubbable; comes down
from the TOP not up from the bottom; black at 40% opacity so you can see the video through it;
an icon row beside the title; mask/blend/chroma as animated tabs that slide spatially; volume
keyframes; Scale to 400%; drop "Show audio waveform" (double-tap already opens it).

**Answers the user gave when asked (BINDING, do not re-litigate):**
1. **PiP only for now.** `ObjectMenuSheet` still serves text/sprite/audio/visualizer across 19
   call sites; porting them is mechanical once this is proven.
2. **FOUR tabs** — Video · Mask · Chroma key · Blend — not chroma-folded-into-blend. Blend
   really is sparse (5 options) but chroma has ~8 controls and Luma key adds more.
3. **Luma matte lane: consumed on the CANVAS, grayscale in the TIMELINE.** The lane above stops
   being composited as a picture; its timeline tapes stay visible but render grayscale so you
   can see the assets and know they have become a mask. **The object that TURNED luma key on
   needs an obvious marker** so it is clear which one is causing it.
4. **Any lane type may be the matte** — video, text, sprite, image, shapes. User's words: it
   *"should feel as easy as 'this here should be cutout by anything one lane above'"*.

**SHIPPED AND SEEN ON THE NOTE 9.** `tools/PipOverlayDrawer` (chrome: top anchor, 40% black,
icon row, four tabs, directional slide) + `tools/PipDrawerTabs` (content). The activity gained
~90 lines of wiring instead of ~500 of inline UI — the same discipline the Mask panel took.
Confirmed on device: toolbar clear, six compact rows, **Volume now carries `‹ ◇ ›` like every
other row**, timeline fully scrubable underneath, tab titles change, content slides, and the
active tab's icon turns green (Mask and Chroma both checked).

**One real layout bug, found by looking rather than by reading.** Anchored `Gravity.TOP` the
drawer landed ON the editor's own top bar — its title and icon row collided with the project
title, the close ✕ and the export button. Translucency made that read as a rendering fault
rather than a layout one, and it put two tappable things in one place. Now offset by the
measured height of `editor_top_bar` (measured, not a constant — the bar carries a
device-dependent status-bar inset).

**Volume's missing diamond was HONEST, not an oversight — and is now real.** It shipped as a
`staticProp` because `buildOverlayAudioSequence` only ever called `setVolume()`, a constant; a
diamond over an export that ignores it is a control that lies. Fixed at the source: that
sequence now uses `setVolumeEnvelope()` when the clip has keys, mirroring the master-clip path.
The curve moved out of `VolumeAudioProcessor` into `model/VolumeEnvelope` because it now has
three readers (export processor, live preview, menu row) — **a preview that fades at a different
rate than the file is worse than one that does not fade at all, because it looks correct.**
Harness: `run-key.sh` now covers both shared model curves, 37 chroma + 17 envelope, ALL GREEN,
and the envelope checks discriminate (removing the leading clamp fails exactly one).

**Scale 150% → 400%**, and the PINCH clamp went 300% → 400% in the same change: the two
disagreed, so fingers could reach a size the slider could neither display nor restore.

### ⚠ NOT BUILT YET — LUMA KEY. The spec above is complete; the work is not.
Nothing of item 3/4 is implemented. What exists to build on: `CompositingSpec.mattePeerId` +
`LayerPreviewController.servingMatteClipIds` + `MatteVisibilityTest` (14 checks) already do
CLIP-to-CLIP luma matte, canvas-consumed, in preview AND export. So the remaining work is a
new SELECTOR ("the lane immediately above") on a working engine, plus:
- generalising the matte SOURCE beyond `Clip` — text/sprite/image lanes are not in the peer
  list the resolver reads, and `TextOverlayItem` has no compositing field;
- the timeline treatment: grayscale tapes on the matte lane + an obvious badge on the object
  that enabled it.
Also still open from the earlier chroma work: frame-rate cost of the GL tier, the rotation
refusal, and the OK/Cancel/Remove round-trip through the new tabs (the drawer writes live like
the dialog did, so the revert contract needs re-checking in its new home).

### "I TRIED ADDING A KEYFRAME TO POS X AND I WASN'T ABLE TO" — the key was landing all along

**Both leading suspects were wrong, and one log line said so in a single run.** The hypothesis
was a stale or zero `lastPlayheadAbsoluteMs` reaching the diamond through the new drawer's Host.
It is neither:

```
KFDIAG drop key=x clip=6126e8b4 ph=4973 span=[5501,13629] keysBefore=1 keysAfter=2
```

`ph=4973` matched the on-screen `00:04.973` exactly, and the key was WRITTEN — it round-tripped
to `project.json` as `"x":[{t:0},{t:4973}]`. So the gesture worked, the model changed, and the
user was right anyway, because of the second half of that line: **the playhead was 528ms BEFORE
the clip's own span**, where the evaluator clamps flat and the authored value can never be seen.

**THE MEASUREMENT THAT NAILED IT: a whole-screen pixel diff across the tap.** Before vs after,
1080×2220, everything: **the only pixels that changed were the undo counter** (`49`→`50`).
Not the diamond, not the slider, not the timeline. The diff of the FULL screen is its own
control — something did change, so the differ works and the rest of the screen really was inert.
*A feature whose only observable is the undo count is indistinguishable from a broken one.*

**Three dead feedback channels, all fixed, none of which was the "keyframe code".**
1. The drawer had **no playhead subscription at all** — `updateCurrentTimeDisplay` refreshed the
   object sheet, the ribbon and three other drawers, and had never been taught about this one.
   Every row was a snapshot of the instant the drawer opened, so scrubbing moved no value and no
   diamond ever turned green. A drawer whose entire reason for being top-anchored is *"so you can
   scrub while you keyframe"* was the one panel that ignored the scrub.
2. The diamond's `onAction` called `host.onChanged()` (preview + timeline + save) and **never
   re-read its own row**, so the control that dropped the key was the last thing to know.
3. **PiP transform keys were never drawn on the tape.** `LayerRowRenderer.keyframeSetOf` answered
   only text and sprite payloads; a clip returned `null`, so `drawItemKeyframeDiamonds` had
   nothing to draw. Two time bases meet in that method and the trap is silent: text/sprite keys
   are item-LOCAL, a PiP's `overlayTransform` keys are **ABSOLUTE timeline ms** (that is what
   `OverlayVideoPreviewView` and `PipFrameOverlay` both read), so the naive `start + t` would
   have drawn every diamond at double the offset — a plausible wrong time that survives a
   screenshot. `keyTimeToTimelineMs` converts per item, and `LayerGestureController`'s
   drag floor (`0`) became `earliestLegalKeyTimeMs`, because for a PiP "time zero" is the clip's
   start, not the project's.

**THE USER'S RULE IS NOW ENFORCED, in `model/KeyableSpan` — off-model, shared, harness-pinned.**
Dropping a key only happens when the playhead is over the object's span; outside it the diamond
**flashes red, shakes, ticks the haptic and toasts** *"Move the playhead over this clip to add a
keyframe"*. Three channels because the failure it replaces was a SILENT one. Both ends of the
span are CLOSED (the last frame is a pose people key on purpose) with the same ±40ms slop the
on-key test uses, or "park on the first frame" would fail about half the time and look random.
`run-key.sh` grew 18 checks (72 total, all green) and **they discriminate — proven by three
injections, each failing exactly its own checks and nothing else**: allowing a degenerate span
fails 2, dropping the negative-slop clamp fails 2, making the end exclusive fails exactly 1.

**PROVED ON THE NOTE 9, two arms through one control, differing only in where the playhead is.**

| arm | playhead | vs span [5501,13629] | KFDIAG | `project.json` | on screen |
|---|---|---|---|---|---|
| A (control) | 4973 → 5085 | OUTSIDE | **no line — nothing ran** | unchanged | toast; diamond stays hollow |
| B | 7859 | inside | `keysBefore=1 keysAfter=2` | `x:[{t:0},{t:7859}]` | **diamond solid green + ×**, green diamond on the PiP tape AT the playhead |

Arm A is what makes arm B mean something: same build, same drawer, same diamond, same finger —
only the playhead moved. And scrubbing on to 9047 turned the diamond **back to hollow**, which is
the third proof (the row now tracks the playhead) and could not have happened before this change.

**Left deliberately alone:** the static pose is still encoded as a single key at `t=0`, which for
a PiP starting at 5501 is itself "outside the span". That is the pre-existing `putStatic` parity
convention every project on disk already uses, the evaluator clamps flat before the first key so
it is inert, and changing it is a migration — not something to fold into a bug fix.

Also fixed here (user, small): **"Soften edges" no longer wraps** — the Mask tab's label column
went 78dp → 94dp, plus `maxLines(1)` + ellipsis so a longer translation moves the problem to
truncation rather than silently growing the drawer over the preview.

---

## 3a-KEY. THE CHROMA KEY HAS A UI, AND THE PREVIEW KEYS — 2026-08-05. ⚠ NOT YET SEEN ON A PHONE.

**The binding condition is met: the sliders are tuned against a keyed preview, not blind.** §3a
scope item 1 (*"get it all working in preview too for v1"*) was the reason the key had no UI while
the export had keyed for months. It now keys live.

**ONE key, not two.** `model/ChromaKey.java` holds the GLSL as a single string and BOTH renderers
compile it: `BlendModeGlEffect`'s fragment shader lost its hand-written key block and now calls
`fadKeyAlpha` from that constant, and the new preview tier concatenates the same constant. The
uniform packing (`packParams`/`packColor`) is shared too, so a clamp added on one side cannot be
missing on the other. This is the `featherRadiusPx` discipline applied to the key — and it is
enforced at COMPILE time, since the GLSL is a compile-time constant inlined into both shaders.

**Why a whole GL tier and not a filter — three cheaper routes were checked and all are closed on
this project's floor (minSdk 24, sandbox on API 29):** `RenderEffect` is API 31+ and AGSL
`RuntimeShader` API 33+ (which is why `applyPreviewColorGrade` previews colour only on new
phones); media3's `setVideoEffects` is recorded in that same method's javadoc as **not rendering
in this preview path**; and a per-frame `getBitmap()` + CPU loop cannot hold frame rate. A key is
a per-pixel ALPHA decision from a distance test, which no `ColorMatrix` can express at any API
level. `ChromaKeyTextureView` takes the decoder on an external-OES surface and presents keyed
RGBA — the tier `GlTransitionPreviewView` already proved on this device.

**Cost is opt-in.** Nothing constructs the GL tier unless `ChromaKey.isActive` is true for the
clip on screen — and `isActive` is false at offset +1, where the key cannot change a pixel. A
project that never touched the key allocates no EGL context and runs the old `TextureView` path
unchanged.

**THE ACTIVITY GOT SMALLER WHILE GAINING A FEATURE — 26,975 → 26,854 lines.** The mask dialog was
~120 lines inline in `FaditorEditorActivity`; adding the key half there would have added as many
again. Instead the whole panel moved to `tools/MaskKeyPanel.java` behind a `Host` interface, and
the now-dead `addMaskSlider` and the superseded dialog were **deleted, not parked** — a dead copy
left "just in case" is the exact §3a trap (`setImageUri` into a void). Both are ABSENT from the
dex, which is the freshness control.

**Harness: `bash tools/jvm-harness/run-key.sh` — 37 checks, ALL GREEN**, and they DISCRIMINATE.
The first draft did not: every offset case chosen (keep=0 with offset +1, keep=1 with offset −1)
summed to exactly 0 or 1, so **deleting the clamp entirely still passed all three**. That is the
handoff's own lesson — *when two quantities coincide on the default case, the default case cannot
test them* — reproduced verbatim in fresh code. Three cases that go PAST the rail were added;
removing the clamp now fails exactly those three, verified by injection and restore.
The premultiplication trap is pinned with a CONTROL that proves the test can tell the two apart.

**Three defects found by reviewing my own work, all fixed:**
1. `sampleRawColor` with `surfaceW==0` computed NEGATIVE pixel coords; `glReadPixels` fails
   quietly and the untouched buffer reads as pure black — the dropper would have "succeeded" and
   keyed out black. A wrong colour is worse than a reported failure.
2. `onSurfaceTextureDestroyed` returned true (handing the SurfaceTexture to the framework) while
   the GL thread still held an EGL window on it — a driver use-after-free presenting as a crash
   on rotation with a stack naming neither this class nor GL. Now a bounded join; on timeout it
   returns false and leaks one SurfaceTexture rather than crashing.
3. **The eyedropper could never have worked.** The panel is a MODAL dialog: it covers the preview
   and eats every touch, so an armed dropper could not receive the tap it waits for. It now
   `hide()`s (which does not fire the dismiss/revert listener) and returns on either outcome,
   with a 15s net so a tap that lands off the preview cannot strand the user with a hidden panel
   and live edits pending.

**The dropper samples the UN-KEYED frame, deliberately.** If it sampled what is on screen, then
as soon as the key half-works that pixel is already transparent and the dropper returns the colour
BEHIND it — each tap drifting further from the answer the harder you try. Reading upstream of the
key makes it idempotent. Implemented by drawing unkeyed into the BACK buffer, reading one pixel,
then drawing keyed and swapping — so nothing unkeyed ever reaches the screen. A rotated PiP is
refused (and toasts) rather than sampling the wrong pixel, because the rotation is a View property
applied after the frame the sampler reads.

**Verified:** `assembleDefaultDebug` green; all six harnesses green (key 37 · anchor 35 ·
promote 24 · matte 14 · anchor-math 39 · undo 46); APK dex-scanned with `FadCamApplication` as the
positive control and the two deleted symbols as freshness controls.

### PROVED ON THE NOTE 9 — and the device found a bug everything else called green

**THE GREEN-HARNESS LESSON AGAIN, EXACTLY AS THE HANDOFF WARNS IT.** 37 harness checks, a clean
`assembleDefaultDebug`, a dex scan with controls — and the live tier **never executed once**. The
keyed view was created `GONE`, and a `GONE` TextureView is never laid out, so it never receives
`onSurfaceTextureAvailable` and never publishes a decoder surface — while the routing that would
make it VISIBLE was waiting for exactly that surface. **A deadlock between two of my own
conditions**, invisible to every off-device instrument, and it presents on screen as "the key
does nothing" — identical to a shader bug, a serializer bug, or a spec that never loaded.

**One log line separated four hypotheses in a single run** — the `DRIFTDIAG` lesson, reproduced:
```
KEYDIAG: clip=6126e8b4 spec=on=true tol=1.0 col=808080 wantKeyed=true surfaceReady=false routed=false
```
The spec HAD loaded and `isActive` HAD said yes; only the surface never arrived. `KEYDIAG` is
**retained** (`OverlayVideoPreviewView.KEYDIAG`), like `SEEKRANGE`/`ENDEDNET`/`PHDIAG`: the tier
has no other observable, so without it a regression that stops it routing looks exactly like a
shader that stopped keying.

**THE MEASUREMENT — three arms, and the control shares the render path.** Fixture `302da9ac`,
PiP `6126e8b4` (centred, scale 1.32), seeded on disk with a deep-equality guard asserting nothing
but that one clip's `compositing` differed. Key colour mid-grey `#808080`, whose distance to ANY
colour is at most 0.866 — so tolerance 1.0 must remove every pixel and tolerance 0.0 almost none.
Both arms run through the GL tier; **they differ by one number.**

| arm | tolerance | `routed` | at | result |
|---|---|---|---|---|
| C (control) | 0.0 | true (GL) | 00:06.069 | **PiP present, renders faithfully** |
| B | 1.0 | true (GL) | 00:06.090 | **PiP GONE — the master video shows through** |

**Arm C is what makes arm B mean anything.** Had the control been "key off" it would have run the
PLAIN TextureView path, and a blank arm B could equally have meant the GL tier simply fails to
draw. Routing both arms through the tier and varying only the tolerance rules that out — and
21ms apart, they are effectively the same instant of the same clip.
**It also proves the alpha composites over the MASTER, not over black:** in arm B the child is
visible where the carpet PiP was, which is `setOpaque(false)` + `EGL_ALPHA_SIZE 8` working end to
end. Zero `FATAL EXCEPTION`; no shader-compile or program-link failures from this tier (the two
`shader compile failed` lines in that logcat are `GlTransitionCardBaker`'s, pre-existing and
unrelated — filter by tag or you will read someone else's failure as yours).

**A METHOD TRAP, PAID FOR HERE:** arm C was first run on the build where the tier never engaged,
so that pass measured the plain path while *reporting* "control passes". It was re-run on the
fixed build before being believed. **A control taken on a different build is not a control.**

Sandbox restored byte-exact (`aac2ba5c`, verified after) from a device-local `run-as cp` backup;
rotation lock 0. The editor DOES save the seeded spec back on exit (md5 `d11b224b` before the
restore), so seeding a project is an edit, not an observation — restore, do not assume.

### THE PANEL AND THE EYEDROPPER ARE PROVED ON SCREEN — 2026-08-05, and both were BROKEN first

**Reaching the panel:** hold-and-release the PiP's TIMELINE LAYER ITEM → the object sheet opens
in PEEK → **TAP the grip pill** (it toggles; dragging it is not needed) → the expanded
*Video overlay* menu lists **Mask**. Pos X 53% / Pos Y 48% / Scale 132% match `project.json`
exactly, which is the check that the right clip is in hand. The panel draws both sections, and
the Chroma-key body is correctly collapsed until *Key out a colour* is ticked, then shows the
four swatches, **Pick from video**, Tolerance, Edge softness and Choke/spread.

**BUG 4 — THE EYEDROPPER'S TAP COULD NEVER ARRIVE, and the guards were innocent.** Arming inside
`OverlayVideoPreviewView` cannot work: **five sibling layers sit ABOVE it** in
`activity_faditor_editor.xml` (waveform, layer image, sprite, TEXT, captions) and the text layer
swallows preview taps. `consumeEyedropper` was never called at all — the diagnostic printed
nothing, which is a different signature from "a guard rejected it" and is what identified it.
Interception moved to the activity's existing `dispatchTouchEvent`, the only place above every
sibling; it disarms on any outcome and swallows the event so sampling cannot also drag the PiP.

**BUG 5 — TURNING THE KEY ON WHILE PAUSED MADE THE PiP VANISH.** The worst bug of the session and
the most user-visible: `setVideoSurface` hands the renderer a NEW surface, and nothing produces a
frame for it while paused — so the tier rendered black, the PiP disappeared, and it reads exactly
like *"the key deleted my video"*. Since the panel is always opened paused, **every first use of
this feature would have hit it.** Fixed with a zero-distance `seekTo(getCurrentPosition())` after
a real switch, which re-renders the current frame without moving the playhead.

**The eyedropper was the instrument that found bug 5, by being honest.** It kept returning
`rgba=0,0,0,255 glErr=0` — alpha 255 proving the draw RAN while the colour was black, i.e. the
texture genuinely held black. That ruled out the read and pointed upstream at the tier. Two wrong
fixes were tried against that evidence first (a back-buffer ordering theory, then an offscreen
FBO); **both were kept** — the FBO is the correct way to read a pixel you just drew regardless,
since a window back buffer is undefined after `eglSwapBuffers` — but neither changed the symptom,
which is what finally moved the search off the reader and onto the writer.

**PROVED, with the control that makes it mean something.** Sampling a point on the PiP now returns
`rgba=122,66,169` and the panel's Key colour reads **`#7A42A9`** — the same number, so the value
travelled GL → model → UI intact. **The earlier all-black samples are the control**: the same code
path returned exactly `0,0,0` at two different UVs before the fix and a real colour after, so this
is a live read of the frame and not a constant.

### MASK + KEY COMPOSE — measured, and timing-independent, 2026-08-05

The panel SEEDS a 30%×20% mask box on open, so these two features interact from the first use.
The risk was specific: `drawChild` clips `videoHost()`, and if that re-pointing were wrong a keyed
PiP would draw its FULL frame and the mask would silently stop existing.

**The obvious instrument was invalid and was discarded.** An M1-vs-M2 image diff showed 36,659
differing samples over the whole video column — but the arms sat at **00:06.639 vs 00:06.226**,
413ms apart on handheld footage, so the master content differed for reasons having nothing to do
with masking. *A diff between two arms captured at different playhead times measures the clock,
not the change.*

**What replaced it: the mask box's own geometry, predicted then measured.** Content rect width
measured at **342px** — independently confirming `baseW=342` from KEYDIAG — so a 0.30-wide box
centred is **x 489…591**. Gradient strength at those exact columns, against the null of every
other column in the band:

| arm | tier | left edge (489) | right edge (591) | median |
|---|---|---|---|---|
| M1 | plain TextureView | 12930 — **95th pct** | 16747 — **99th pct** | 4834 |
| M2 | **GL keyed** | 19389 — **99.3rd pct** | 14943 — **94th pct** | 5917 |

Both edges are 2.5–4× the median in BOTH tiers, at coordinates derived from the model before
measuring. **The mask survives the reroute.**

### THE EXPORT KEYS — A/B on real pixels, 2026-08-05. §3a-KEY's largest gap is CLOSED.

Two full exports of `302da9ac` at 720p/Low, identical but for the key:

| t | differing pixels (key-off vs key-on) |
|---|---|
| **2.0s — before the PiP starts** | **0** of 921,600 |
| 6.0s | 119,934 (13.0%) |
| 8.0s | 132,318 (14.4%) |

**The zero at 2.0s is the control** — the differ works on these exact files and the change is
confined to when the PiP is on screen. Visually at 8.0s: key-off shows the PiP band, key-on shows
continuous master where it was. **The `Enter text` overlay renders in BOTH arms** — a free
positive control proving this is the PiP being keyed and not a blanket "drop all overlays".
Both files are **13.726s / 534 frames**, identical to a pre-change export, so the shader refactor
did not disturb the export path.

**Honest limit:** this proves the export keys and that preview and export AGREE ON DIRECTION
(PiP present → PiP removed). It is not a pixel-exact preview-vs-export comparison at one media
time. Combined with the single shared shader source that is strong, but the §1d-style
absolute-geometry comparison is still the stronger instrument if the key ever looks wrong.

**TWO SELF-INFLICTED FALSE ALARMS, recorded because both wasted real time and both looked like
findings.**
1. **"The export is broken — no `moov` atom."** It was not. These exports take **~4–5 minutes**;
   every pull caught a file mid-write. `ffprobe` reporting "moov atom not found" means INCOMPLETE,
   and a size that pauses between samples is not a finished file. Poll for
   `ExportManager: Export completed`, not for a stable size.
2. **"The key-on export crashed."** I killed it myself: `am force-stop` to seed the next arm ran
   while that export was still going, which is what the `Scheduling restart of crashed service
   ExportService` line was. **The standing rule "never build while an export is running" applies
   to force-stop and to seeding the project too.**
   A third near-miss: a grep for "crashed service" matched `AnnotationService` failing at a
   timestamp BEFORE the export began — the same "filter by tag or you will read someone else's
   telemetry as your result" trap, this time from another service inside the same app.

**⚠ STILL NOT PROVED — do not mark these verified.**
1. **Frame-rate cost of the tier** (`dumpsys gfxinfo`, against the ~33.7% editor baseline).
2. **The rotation refusal** (a rotated PiP toasts rather than sampling the wrong pixel).
3. **OK / Cancel / Remove round-trip.** The revert-on-dismiss contract is unchanged from the
   shipped mask dialog but has not been re-exercised through the new panel.
4. A **pixel-exact** preview↔export comparison at one media time (see the honest limit above).

**METHOD NOTES PAID FOR THIS SESSION.**
- **`uiautomator dump` returned 67 bytes / 0 nodes** — the documented "reports success while
  measuring nothing" trap. Element positions were found by scanning the SCREENSHOT for the grip
  pill's exact colour (`0xFF555555`) instead, which located it at y=2090 when tapping y=2031 had
  been silently missing.
- **The project list re-sorts by `lastModified`, and MY OWN restore changed the order.** Restoring
  the backup reverted the timestamp, moved the fixture back down the list, and the next run opened
  `AudioExportVerify` instead — the runbook's rule (identify by the logcat line, never by row
  position) exists for exactly this and was worth obeying every time.
- Two accidental edits, both caught and undone: a swipe aimed at the sheet dragged **Opacity to
  3%**, and a long-press on the project list opened multi-select.

---

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
   ~~**FOUND 2026-07-29: there is no image overlay to mask yet, so "images in v1" is build the
   image overlay first.**~~ **THAT WAS WRONG, AND CORRECTED 2026-07-29 THE SAME DAY. There IS an
   image overlay, it ships, and it has a creation path: the STICKER TOOL.**
   `FaditorEditorActivity:2317` wires `R.id.tool_sticker` → `pickImageOverlay()` →
   `onOverlayImagePicked()`, which calls **`TextOverlayItem.createImage(uri, 0.5f, 0.5f, 0.30f)`**
   and adds it via `addTextOverlay`. It is persisted (`ProjectStorage:2548` restores `imageUri`),
   rendered in export (`CompositeExportOverlay:531` feeds `setImageUri`), and the object menu has a
   live image branch (`o.isImage() ? "Image" : …`, with "Images: the drawer IS their type editor").
   **Confirmed against real data, not just code:** `bb2a9deb` holds two such overlays, and their
   `"sizeFraction": 0.3` matches `createImage`'s hard-coded `0.30f` exactly — they were made by
   that tool.
   **What the earlier finding got right, and why it misled.** There are THREE different "image"
   things here and the previous pass conflated two of them:
   1. `Clip.isImageClip()` — a still on the MASTER track. Real creation path
      (`onImageAssetPicked:24634`, 5s still inserted after the selected segment).
   2. `TextOverlayItem` with `imageUri` — the image OVERLAY on a layer row. **This is the one v1
      needs, and it exists.**
   3. `LayerPreviewController.visibleImageItems` — a `TimedItem` of IMAGE payload kind on a layer
      track. **Still genuinely empty; nothing creates an IMAGE track.** That original claim stands,
      and so does the note that its hidden-track skip was never mirrored in `ExportManager`.
   The dead-end conclusion came from checking (1) and (3) and never (2).
   **Consequence for scoping: §3a v1 does NOT need "build the image overlay first."** The subject
   already exists. What it needs is `CompositingSpec` reachable from a `TextOverlayItem` (it lives
   on `Clip` only today) and honoured by the overlay preview + its export peer. That is mask
   plumbing after all — the original item — not a prerequisite build.
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

**3b. Lane mute icon — DONE 2026-07-28, `e1fe61d`. Moved to §1.**
All three asks landed: absent (not greyed) with no audio, a real `volume_up`/`volume_off`
speaker, flush against the caret with the gutter shrunk 92dp → 34.4dp. The touch box was also
enlarged from the 12dp glyph to the full row height, since a 12dp target is not finger-sized.

**3c. "Rename lane" — DELETE. Decided twice.** User: *"layers don't need names, OBJECTS need
names."* The decision was recorded and never applied: `FaditorEditorActivity:11792` still adds a
Rename row, the dialog persists a name, pushes an undo step and writes to disk, and
`LayerRowRenderer` never draws a track name anywhere. The user types a name and sees nothing.

**3d. Cut smoothness — BUILT, MEASURED, and DELETED. CLOSED, do not rebuild it.**
The hybrid was implemented (`8f3196f`), measured, and reverted on the user's decision
(`14e07ee`, 2026-07-28): *"170 nearly useless lines? 1-in-1000 odds of landing on a keyframe.
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

**3g-TEXTBOX. Text-box animation — ENGINE DONE AND AUTHORABLE; the round-trip is NOT yet proved.**
Commits `6d6667d` (engine) + the UI commit below. The other half of the user's 2026-07-29
direction.

**What is proved, on the Note 9, 2026-07-29:**
- The **MOTION row renders in the Edit text dialog** — label, the "A in motion" icon, and a state
  line — above FONT. Screenshot `16_textdialog.png`.
- **The picker opens from it with all six presets and `Animate by: Block` ALONE.** Letter, Word
  and Sentence are correctly absent. Screenshot `17_picker.png`. That gating is the load-bearing
  part: see below.
- **Picking RISE reads back `Rise · 25% in / 0% out of this box`** (uiautomator text), so the
  entrance seed (MAX_ZONE_PCT/2) and the readout both work through a real tap.
- Off device: harness **160 → 168**, including the property the fractions exist for — the same two
  numbers animate identically on a 0.4s title and a 300s one — with two controls proving that
  sweep is not passing vacuously. APK dex-scanned for `textBoxTransformAt`, `textAnimPreset`,
  `animSpanMs`, `applyTextOverlayAnim`, with `FadCamApplication` as the partial-dex control.

**What is NOT proved, and must not be claimed:**
- **The persistence round-trip.** After picking RISE and confirming the dialog, `project.json`
  still contains **zero `textAnim` keys** and is byte-unchanged at 67,599. The likely reason is
  benign — the overlay was created with EMPTY text and is probably discarded on OK, so there was
  nothing to save — **but that was not verified.** Redo it on a text box that has text.
- **That anything actually animates**, in preview or export. Both paths are wired to one
  evaluator and the harness pins the arithmetic; no pixel has been looked at.

**BLOCK-only is a real constraint, not an unfinished switch.** Preview draws a text overlay as an
Android `TextView`, which cannot transform individual characters; export draws it with
`canvas.drawText`, which can. Offering WORD or LETTER would animate per-unit in the exported file
and animate the whole body on screen — a preview/export divergence in the one place this project
keeps getting burned, and invisible until someone watches a finished export. Lifting it means
giving text boxes a canvas renderer in preview, the way captions already have. That is work, not
a flag. Recorded on `TextOverlayItem.textAnimGranularitySupported`.

**TEXT BOXES NOW ANIMATE PER LETTER / WORD / SENTENCE — BUILT 2026-07-30, PREVIEW PROVED ON THE
NOTE 9, EXPORT NOT YET PROVED.** The user's reason for wanting it, on record: *"really that was
the only point in doing animate text in the first place."*
**How it was done, and the one decision that matters:** captions keep preview and export in step
with TWO deliberate mirrors (`CaptionOverlayView.drawWord` / `CaptionExportRenderer.drawWord`),
which only works while someone maintains them by hand. Text boxes got the stronger version —
**ONE renderer, two callers**: `TextBoxRenderer` owns measurement, line layout, unit splitting,
all three animated channels and the three paint passes; the preview's new `TextBoxView.onDraw`
and `CompositeExportOverlay` both call it. There is nothing to keep in sync because there is only
one of it. Agreeing about WHERE GLYPH *i* SITS is a far finer-grained agreement than "is this
visible", and two hand-maintained layouts would not have held it.
Pieces: `TextBoxRenderer` + `TextBoxView` are new; `TextOverlayLayer` builds a `TextBoxView`
instead of a `TextView`; `CompositeExportOverlay` draws straight onto the frame canvas instead of
rasterising the box to a bitmap and transforming the bitmap; `textAnimGranularitySupported` now
returns true for every granularity and the text-box picker is passed `null` (= all).
**Decisions, so they are not re-litigated:**
- **The excursion margin.** The old `TextView` was moved by VIEW properties, so it could never clip
  itself. Now the motion happens inside `onDraw`, and a view's drawing IS clipped to its bounds —
  a RISE starting 0.9em low, or an UNSCRAMBLE starting 1.6em away, would be sliced off for the
  whole entrance and would read as a rendering bug. So `TextBoxView` is deliberately larger than
  its box by `EXCURSION_EM = 1.8f` (sized from UNSCRAMBLE, the furthest traveller) and draws the
  box inset. It is NOT computed per preset — a margin that resized on every pick would re-layout
  the box, and transparent slack costs nothing.
- **The view is not the box, and three places had to learn that.** Layout centres the BOX, not the
  view; and `FaditorEditorActivity.textHandlesTarget.frame()` insets by `boxInsetPx()` — without
  that the dashed selection frame and its corner handles stood ~2 type sizes clear of the text on
  every side. Caught on device, fixed, `tasks/screenshots/textbox_selection_hugs_box.png`.
- **Object opacity is passed INTO the renderer** rather than applied with `setAlpha` on the view,
  because the export has no view and would otherwise composite it at a different stage. The view is
  therefore left fully opaque for text; setting both would darken every semi-transparent box.
- **`blurPx` is still not applied** — see the GHOST decision below, which the user has now made.
- Padding is now `0.35em` on BOTH surfaces. The preview previously had none, which is the mismatch
  MASK_WIPE's export path had to inset around.
**A crash this caused, and the rule it paid for:** the first build died on `onDraw` with
`ArrayIndexOutOfBoundsException: length=0; index=0`. `splitLines` substituted `" "` for an empty
box while the unit map was still sized from the raw zero-length string, so a one-character line
indexed into a zero-length array. **Any two derivations of "the text" that can disagree eventually
will** — there is now one `normalise()` at the top of both entry points and everything is built
from its result, plus a bounds guard because the cost of being wrong inside `onDraw` is the editor
dying rather than one glyph being misplaced.
**PROVED on the Note 9, preview only:** `PICKERTEST` set to RISE + **LETTER** on disk, 10 playback
frames (`tasks/screenshots/textbox_letter_12frames.png`). At `00:01.054` the **P** has arrived
while the **I** is still below the baseline AND semi-transparent — two letters of one box at
different geometry and different alpha in the same frame, which BLOCK cannot produce by
construction. At `00:03.553` an **R** sits visibly below the line, which is also the excursion
margin working: without it that glyph would be sliced off. Editor opened with **0 fatals** after
the fix, harness still **298 passed, 0 failed**, matte harness ALL PASS.
**NOT PROVED, AND NOT CLAIMED:**
1. **The EXPORT.** No file has been exported since the rewrite. This is the one that matters —
   the whole justification for a shared renderer is that both sides agree, and that is currently
   an argument from construction, not a measurement. **Do this first next session.**
2. **The text-box picker showing all four granularities.** The gate is open in code
   (`textAnimGranularitySupported` → true, picker passed `null`) but the deep route into the
   text-box picker (preview long-press → sheet → expand → More… → Edit text → MOTION) was not
   completed before the session ended. The CAPTION picker was confirmed working.
3. **Frame rate.** LETTER on captions was measured and holds; the text-box path is a different
   renderer and is unmeasured.
**FOUND IN PASSING, NOT FIXED — IMAGE OVERLAYS DO NOT EXPORT.** `CompositeExportOverlay:541` sets
`frameOverlay.setImageUri(...)` and **nothing reads it**: `TextOverlayRenderer.render` is text-only
and `getImageUri` has no other reader in the export package. So a sticker drawn by the Sticker tool
shows in the preview (an `ImageView`) and is absent from the exported file — a §3a-class
preview/export divergence. **This contradicts §3a item 2 of this ledger**, which states image
overlays are "rendered in export (`CompositeExportOverlay:531` feeds `setImageUri`)" — that
inferred function from a setter call. Behaviour was left exactly as it was by this session's
change (images still take the old bitmap path). **Verify with an actual export before acting on
it**, since the claim above is from reading, not from a rendered frame.

**~~…the way captions already have.~~ CORRECTION, 2026-07-30, and it roughly DOUBLES the estimate
above — verified by reading, prompted by the user asking what per-letter text boxes would take.**
The paragraph above is right about the PRIMITIVE and wrong about the STATE. It reads as "preview
can't, export already does", i.e. one side to build. **Neither side does it today.** The export
does not draw a text box per glyph either: `CompositeExportOverlay:542` calls
`TextOverlayRenderer.render`, which draws WHOLE LINES (`canvas.drawText(line, x, y, paint)`,
`TextOverlayRenderer:100/106/112`) into a bitmap, and then `CompositeExportOverlay:556-573` applies
the animation to that **whole bitmap** — one transform, one alpha, one clip. Confirmed from the
other direction too: **`CaptionAnimator.splitUnits` and `unitIndexOf` have NO caller outside the
caption path**, so nothing anywhere cuts a text box into units. `textBoxTransformAt` /
`textBoxTextAt` are documented as BLOCK-by-construction (`unitCount = 1`) and their only four
callers are the two whole-body surfaces.
**So per-letter/word text boxes is: per-glyph drawing on BOTH surfaces, plus a shared layout
authority so the two agree on where glyph *i* sits.** That third piece is the one the old wording
hides entirely, and it is the part that matters — captions only stay honest because
`CaptionOverlayView.drawWord` and `CaptionExportRenderer.drawWord` are deliberate mirrors of each
other, and two independently-written text-box layouts would re-open §3g from a new angle.
The MASK_WIPE session's finding still stands and is unaffected: **a text box can be CLIPPED without
any of this** (`View.setClipBounds`), which is why MASK_WIPE ships on text boxes today. Clipping one
body is not the same problem as laying out N.

**Still owed on this half:** the `▶` `◀` carets. The user's stated instrument for text boxes is
the carets, not a dialog row. What shipped is the preset picker plus a seeded zone, which makes
the feature reachable at all — the alternative was an engine with no way in, which is precisely
the §3a failure this ledger exists to prevent.

**Two corrections to what this ledger said about those carets, both found by reading the code
2026-07-29 — they change the estimate, so do not plan off the old wording.**
1. *"Parked, no caller"* was imprecise. `drawCaptionAnimHandles` **is** called every frame
   (`EditorTimelineView:2376`) and `hitTestCaptionAnimHandle` is wired into the touch path. What
   is dead is the **gate**: `captionAnimHandlesVisible` (`:832`) is initialised false and its only
   setter (`:7366`) has **no caller anywhere in the app**. So the carets are one line from
   drawing again — for a CLIP.
2. **That is the trap.** The whole block is bound to MASTER-CLIP geometry: it draws into
   `segRects.get(selectedIndex)`, and `selectedCaptionAnimClip()` reads
   `segments.get(selectedIndex).clip` and demands `hasTranscript()`. A text box is not a master
   segment — it is an item on a layer row, with its own rect and its own span. **Pointing these at
   a text box is not re-pointing a target; it is a second geometry.** Budget it as new work that
   borrows the marker drawing and the drag-commit discipline, not as flipping a flag.

**The empty-overlay question from the previous entry is ANSWERED, by reading rather than
assuming.** `showTextOverlayEditor`'s OK handler does `if (txt.trim().isEmpty()) { …
removeTextOverlay(item) }`. The text box created during the device walk had no text, so it was
deleted on OK and never reached the serializer — which is exactly why `project.json` held zero
`textAnim` keys.

**THE SERIALIZER IS NOW PROVEN — 2026-07-29, Note 9, on a box that HAS text.** Done on
`bb2a9deb`'s existing overlay (text `"LayerOne"`), against a build dex-scanned for
`textAnimInPct` / `getTextAnimInPct` / `hasTextAnim` / `textAnimGranularitySupported` with
`FadCamApplication` + `FaditorEditorActivity` + `ProjectStorage` as the positive control and
`captionAnimInMs` / `maxUsefulZoneMs` as the freshness control (both absent).

*Pass 1 — does it survive at all.* Injected all four keys with the editor closed, opened the
project, closed it with Save. All four came back identical, **including `0.25` and `0.125`, chosen
because they are exactly representable in binary float so any drift would be real loss rather than
a formatting artifact**. Two independent controls prove the serializer actually RAN rather than
the file merely being left alone: the md5 changed (`0ea99369…` → `12be9e36…`), and the four keys
were injected INLINE on one line while the writer pretty-prints one key per line — a grep for the
inline form went **1 → 0**, so every key was re-emitted by the writer.

*Pass 2 — does it go through the MODEL, or only through Gson's tree?* Pass 1 cannot tell those
apart, so: injected `textAnimInPct: 0.9` and `textAnimOutPct: -0.2`. After the round-trip the
first came back **`0.5`** and the second was **ABSENT**. That is `clampTextZonePct` capping at 0.5
and flooring at 0, plus the sparse writer omitting a zero — behaviour that exists only on
`TextOverlayItem`, and that a JSON pass-through could not produce. **Read path, model, clamp,
write path and sparse-omission are all proved in one shot.**

The sandbox was left on supported values (preset FADE, 0.25/0.25, granularity BLOCK and therefore
correctly omitted), re-opened once to confirm it still parses, with the clip's `captionAnim*` keys
verified untouched. Note `project.json.bak` is the APP's own backup — it exists in other projects
too. Do not "clean it up".

**THE PICKER UI IS NOW PROVEN TOO — 2026-07-29, Note 9, `bb2a9deb`. §3g's last open claim is
closed.** Baseline: the project held **zero** `textAnim` keys, so any key that appeared was written
by the UI and could not be a leftover masquerading as success. Picked **RISE** specifically because
nothing on disk had ever held it.

Three independent levels agreed, in order:
1. **UI label** — the dialog's MOTION row went `None` → **"Rise · 25% in / 0% out of this box"**.
   25% is `MAX_ZONE_PCT / 2`, the seed `applyTextOverlayAnim` plants when both zones are 0, so the
   label is reporting the seeded model rather than echoing the tap.
2. **Preview render** — the text **disappeared** from the preview at t=0. That is not a glitch: the
   box starts at 0 with a 25% entrance, so progress at t=0 is 0 and RISE draws nothing. The preview
   renderer is consuming the fields the picker just wrote.
3. **Disk** — after Close & Save: `"textAnimPreset": "RISE"`, `"textAnimInPct": 0.25`, on overlay
   `0325f262` and **on no other overlay**. `textAnimGranularity` and `textAnimOutPct` are correctly
   ABSENT (BLOCK is the default, 0 is sparse-omitted) — matching the serializer proof exactly.

Controls that make the above mean something:
- **Targeted, not blanket.** A full `diff` of the whole file shows the two keys on exactly one
  overlay. The other five text overlays gained nothing, so this is not the writer emitting defaults
  everywhere.
- **OK really committed.** The box's text went `"Enter text"` → `"PICKERTEST"` in the same save, an
  independent signal in the same diff — so a missing `textAnim` key could not have been blamed on
  the dialog silently failing.
- **Round-trip.** Re-opened and re-saved with no edits: both keys came back unchanged. Picker →
  model → disk → read → model → disk is now closed end to end.
- Undo count moved 17 → 19 (one step for the animation, one for the text), consistent with
  `applyTextOverlayAnim` recording an undo action.
- **"Animate by" offered ONLY `Block`**, which is `44f80d2`'s supported-set gate working on the
  text-box path — visible in the same screenshot.

**The route in, since the last attempt could not find it.** Long-press the overlay **in the
PREVIEW**, not on the timeline chip — that is `onOverlayHeld` (`:16327`), which opens the object
menu sheet directly. The sheet opens in PEEK; drag its handle up to EXPAND, scroll the action list
to the bottom, and **"More…"** is the last entry. The previous session's "long-press gives an
Opacity `◀ ◇ ▶` bar" was the *timeline chip* — a different gesture on a different target, which is
why it looked like a dead end.

**A TRAP that will delete your subject if you do not know it** (`:19458`):
```java
input.setHint(R.string.faditor_text_hint);
if (!getString(R.string.faditor_text_hint).equals(item.getText())) { input.setText(item.getText()); }
```
A box whose text is **exactly** the hint (`"Enter text"`) opens with an **EMPTY** field. The OK
handler deletes empty boxes (`txt.trim().isEmpty() → removeTextOverlay`). So tapping OK on an
untouched "Enter text" box **destroys it** and writes nothing — a false negative that also removes
the evidence. Type real text before OK. This also retires the older puzzle about empty overlays:
the placeholder-equals-hint case is why they appear and vanish.

**Navigation was NOT needed and the earlier note was wrong about why.** A handoff said the stray
boxes "sit at ~0–5s". They do not: `9d7fef2b` has `startMs` **2305843009213693951** (= 2^61−1) and
`15053d1e` runs 25117–42588. What made the test possible is that `0325f262` has **no** `startMs`
or `endMs` on disk, and the defaults are `startMs = 0`, `endMs = Long.MAX_VALUE` — so it is visible
across the ENTIRE project, including t=0, and at t=0 it is the ONLY overlay visible (every other
one starts at 6s+). Selection was therefore unambiguous without solving the 20s-reach problem.
**Read the defaults, don't hunt the timeline.**

Still true and still costly (unchanged from the last attempt):
- **`showTextOverlayEditor` has two reachable callers**: `onItemDoubleTapped` on a LAYER-ROW item
  (`:12409`) and the object menu's "More…" (`:18110`, non-image overlays only).
- **A scripted double-tap on the timeline chip SEEKS instead of opening the editor.**
- **The `Text` toolbar button CREATES a new overlay on every tap** — it is not "open the selected
  box". Its default text is `"Enter text"`, which is NON-EMPTY, so those boxes persist.
- **Timeline navigation facts that cost time:** the minimap is **CLIP-scoped, not project-scoped**
  (tapping it seeks within the selected clip, ~5s wide, not across the 30s project); the ruler
  strip does **not** scroll horizontally; and tapping the `00:0X.XXX` time chip does **not** open a
  jump-to-time dialog on this build. Reaching an object at 20s is therefore still not solved.

**DIFF NOISE YOU MUST EXPECT in `project.json`, or you will misread your own experiment.** All
three `audioClips` ids are **regenerated on every save**, together with the `items[].id` and
`items[].payloadId` that reference them. Proved to be unconditional by an open → change NOTHING →
Close & Save cycle, which churned all three again. So id changes in a diff are background noise,
not something your edit did. **Not filed as a bug:** the rewrite is self-consistent, and the one
cross-reference that could have broken (`audioSourceRef` on the waveform overlays) resolved to no
`audioClip` id **before** the churn either (0 of 2 in the baseline, 0 of 2 after) — it points at
something else entirely. Worth understanding properly if the visualizer ever loses its source, but
there is no measured breakage to claim today.

**A correction to the previous entry's inference.** "The empty box was deleted on OK" is not
universally true: `bb2a9deb` currently holds **two overlays with `"text": ""`** that survived
serialization. So the empty-on-OK delete is not a guarantee, and "no `textAnim` keys" was never
safe to explain by it alone. The serializer proof above stands regardless — it did not rely on
that inference.

**THE SANDBOX LITTER IS CLEANED — 2026-07-29.** `bb2a9deb` had three `"Enter text"` overlays from
taps of the `Text` tool during the editor hunt. One of them (`0325f262`) became the picker-test
subject and is now **`PICKERTEST`**, carrying the proven `textAnimPreset: RISE` /
`textAnimInPct: 0.25`; it is deliberately KEPT, because a text box with real text, on a layer row,
spanning the whole project (no `startMs`/`endMs`, so 0 → `Long.MAX_VALUE`) is the ideal subject for
the next text-box test. The other two were removed.

**The removal also took two DANGLING LAYER ITEMS** whose `payloadId` still pointed at the deleted
overlays. Deleting the overlays alone would have left exactly the dangling-reference class of bug
this project has been bitten by before, so the prune walked every `items[]` list as well. Verified
by deep-equality against the original — the script asserted that nothing outside the two overlays
and their two layer items changed, and refused to write otherwise.

Round-trip proof of the surgery: wrote 63432 bytes, on-device size matched exactly, the project
**re-opened with no fatal in logcat**, `PICKERTEST` drew its layer chip, the text was correctly
absent at t=0 (RISE at progress 0, i.e. the persisted animation still working after a reload from
the hand-edited file), and a Close & Save let the app's own writer re-emit it at 63143 bytes with
**zero** remaining references to either removed id.

**Technique, since `run-as` cannot read `/sdcard` directly:** `adb push` to `/sdcard`, then
`adb shell "cat /sdcard/f.json | run-as com.fadcam.beta sh -c 'cat > files/.../project.json'"`.
Force-stop the app first so nothing overwrites the edit, and strip CR (`tr -d '\r'`) from anything
obtained via `run-as cat` before editing it — a pulled copy is NOT byte-identical to the file.

**The two remaining `"text": ""` overlays are NOT litter — they are IMAGE overlays**, and that
matters to §3a. Both carry an `imageUri` (one `content://…`, one `project://assets/…png`), and the
object-menu code has a live image branch (`o.isImage() ? "Image" : …`, and "Images: the drawer IS
their type editor — no More… target left"). This is what led to **correcting the §3a
claim that "image overlays in v1 has nothing to put a mask on"** — it does. Chased to the source
the same day: the **Sticker tool** creates these via `TextOverlayItem.createImage`, and the sandbox
overlays' `sizeFraction 0.3` matches that factory's hard-coded value. **§3a v1 therefore does not
need "build the image overlay first."** Full correction, including the three different "image"
concepts that got conflated, is in §3a item 2.

**3g. Text animation presets — BUILT, AND NOW VALIDATED BY THE USER ON THE PHONE.**
Spec: `SPEC_TEXT_ANIMATION.md`, kept current.

> **User, 2026-07-29, after driving it himself:** *"animations per word and per letter look great!
> i tried all styles carets worked well."*

That closes the question step 6 existed to answer. The large-amplitude frame was a proxy for
"does this actually look right", and a human has now answered it directly, across all styles and
both granularities — better evidence than the frame would have been. ~~What step 6 would still
have told us and nobody has measured: whether LETTER granularity holds frame rate.~~
**MEASURED 2026-07-29 — LETTER holds frame rate. See the table further down this section; the
numbers are there and they are not repeated here.** This sentence is struck rather than deleted
because an earlier revision left it standing while the table below already said the opposite, and
a file that contradicts itself is worse than one that is merely out of date.

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

**BOTH HALVES OF THAT DIRECTION ARE NOW BUILT AND PROVED ON THE PHONE — `97eb73b`, `27762a6`.**

**1. The model change — DONE, `97eb73b`.** `Clip.captionAnimInMs`/`OutMs` are now
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

**2. The caption range control — DONE, `27762a6`.** The caption drawer has a **Timing** section:
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
| 1. One evaluator (`CaptionAnimator`), preview and export on the same clock | DONE | `afa1f78` |
| 2. Presets + granularity + unit splitting, with a harness | DONE | `8618a46` |
| 3. Persist on `Clip`, round-trip, and RUN in both renderers | DONE | `08499cc` |
| 4. Tape `▶` `◀` carets | BUILT, then RETIRED for captions and PARKED for text boxes | `27762a6` |
| 5. Preset grid picker + granularity selector in the caption drawer | DONE | see below |
| 6. **Large-amplitude device frame** | **SUPERSEDED.** The user drove the whole thing and approved it, which is better evidence than the frame. The one question the frame stood in for that a human could NOT answer — does LETTER hold frame rate — is now measured, below | — |
| 7. Timing model → per-line fraction | DONE | `97eb73b` |
| 8. Caption range control in the style panel; carets off for captions | DONE | `27762a6` |

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
**→ The static-poses half of this was FIXED in `aac13f0`; see §1. The freeze-frame half is still
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

**Harness: 131 → 145 → 158 → 160 → 168 → 192 → 209 → 213 → 298 checks, all passing.** Run it with the command
at the end of this section. **Measure the count by running it, not by reading this line** — it went
stale twice, and the MATRIX entry had to correct a figure copied from here. **Dex-scan symbol list, CURRENT — the older list in this file was contradictory and
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
- ~~Caret travel is scaled to `CaptionPhrases.maxUsefulZoneMs()`~~ — **OBSOLETE, `97eb73b`.** That
  scaling existed because zones were absolute durations spent per PHRASE, so a linear map onto a
  60s tape left ~94% of the travel dead. A fraction is already per-line, so full travel is 0.5 at
  every length and there is no scale factor at all. `maxUsefulZoneMs` is deleted.
- ~~`finishCaptionAnimDrag` does NOT divide by the speed multiplier~~ — **OBSOLETE, `97eb73b`,
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
- ~~Zones are SOURCE ms, not timeline ms~~ — **SUPERSEDED by `97eb73b`: zones are a FRACTION of
  each line and have no base at all.** The original note read: "`getTrimmedDurationMs()` divides
  by the speed multiplier; clamping against it would have halved the zones on a 2× clip — the same
  units confusion that WAS §3g." Kept struck-through rather than deleted because it names the
  hazard the fraction was chosen to remove, and a future reader who sees a duration creeping back
  into this area should recognise it.
- **Presets compose with `CaptionStyle.Anim`, they do not replace it.** POP/ZOOM/BOUNCE sit at
  1.15× at rest (an active-word emphasis, not an entrance), so merging the vocabularies would
  silently restyle every existing captioned project.
- **MATRIX IS BUILT AND SHIPPED — `b1e0e00`, 2026-07-30.** First of the five, in the order the user
  set. Its blocker was expressiveness, not difficulty: a `Transform` is geometry and alpha, and
  MATRIX animates WHICH CHARACTER is drawn, which no scale/offset/opacity expresses. So it needed a
  **second output channel**, `CaptionAnimator.substituteUnit`. MATRIX leaves geometry and alpha at
  identity on purpose, which is why flipping `implemented=true` needed no new `presetTransform`
  case — the identity return was already correct.
  Three load-bearing properties, each pinned by a test rather than asserted:
  **determinism** (a pure function of unit index / char position / tick, with a hand-rolled integer
  mix rather than `java.util.Random`, because `Math.random()` per frame would make the preview and
  the export draw different characters from one project — the §3g divergence in a form no
  preview frame-diff could catch); **layout safety** (the returned string is always the same LENGTH,
  and both renderers already measured the slot from the REAL text, so substitution cannot reflow a
  line); and **quantisation** into 12 ticks, which makes substitution MORE robust than alpha across
  the preview/export sampling difference — pinned with a control asserting alpha over the same
  interval really does differ, so the comparison is not vacuous.
  Wired at **four** symmetric call sites: `CaptionOverlayView` + `CaptionExportRenderer` (captions),
  and `TextOverlayLayer` + `CompositeExportOverlay` (text boxes, via `textBoxTextAt` /
  `textBoxProgressAt`). Both text-box surfaces already refreshed their string per frame for TIMERS,
  so MATRIX reuses that hook. Doing the text box mattered — omitting it would have shipped a tile
  that does nothing on one of the two object types the picker serves. The picker TILE drives the
  substitution channel too, or MATRIX would have rendered as three static "A"s.
  **Proof:** harness **168 → 192** checks, 0 failed (before/after measured by stashing, because the
  count written here previously — 160 — was stale). On the Note 9: a **seventh tile appeared where
  the previous build had six**, a behavioural freshness proof no symbol scan can fake; its temporal
  sd over a 12-frame burst is **7.82**, the highest of any tile (Type 4.32) with NONE at exactly
  0.00 as the control; two sampled frames read **"A A I"** and **"N N 6"**. Its mean-luminance range
  is LOWER than Type's (3.61 vs 6.28) while spatial variance is higher — the signature substitution
  should have and no alpha preset can produce: the ink changes without the amount of ink changing.
  Caption path confirmed to disk: picking MATRIX and dragging In to 50% in the drawer persisted as
  `"captionAnimPreset": "MATRIX"`, `"captionAnimInPct": 0.5` on the clip.
  ~~**NOT captured: a screenshot of captions mid-scramble during playback.**~~ **CAPTURED AND
  VERIFIED 2026-07-29 — this was the last open item on MATRIX and it is closed.** The old wording is
  struck rather than deleted because one of its two stated reasons was WRONG and the correction
  matters (see below).

  **What was done, and why it is stronger than a photograph.** MATRIX is deterministic by
  construction — a pure function of (unit index, char position, tick) — so the exact string on
  screen at a given media time is PREDICTABLE. That turns "did I catch it" into a falsifiable
  prediction. The arithmetic was mirrored in Python (`tasks/matrix_predict.py`, written BEFORE any
  capture), the sandbox clip `50d1cf46` was switched on disk to MATRIX + **BLOCK**, and each
  screencap was checked character-for-character against the prediction computed from the on-screen
  `00:0X.XXX` time chip — the chip and the caption are in the SAME framebuffer grab, so they cannot
  disagree.
  **11 of 11 samples matched EXACTLY**: 6 paused (playhead parked) and **5 during real playback**,
  across BOTH phrases of the clip, in BOTH the entrance and the exit zone, at five distinct ticks
  (0, 2, 3, 5, 6, 7, 8). Examples — `thG4 caG i4 veG4 cuG4 shG` at tick 6, `%H wHNRK aHN flNRKX hHN
  nHNR` at tick 3, `AL AL$L4 AL$ AL$L49 AL$ AL$L` at tick 0.
  **The controls are built in, not added on.** Two of the eleven are frames where progress has
  passed 1 and the prediction is the REAL text — and the render is the real text, at
  `00:01.440` / `00:03.519`. Same clip, same preset, same phrase, ~300ms apart: scrambled then
  correct. So the scramble is time-dependent, not a font fault, not a layout fault, and not the
  instrument seeing what it wants. The exit sample (`p` falling back to 0.05) proves the exit really
  is the entrance reversed in the substitution channel, which until now was only asserted.
  Evidence: `tasks/screenshots/matrix_paused_tick6_full.png` (full screen),
  `matrix_playback_5frames.png` (the five playback frames with their time chips),
  and three magnified line crops.
  **A reading trap, since it nearly produced a false mismatch:** at caption size the substituted
  glyphs of adjacent words visibly OVERLAP, because the active word carries the style's 1.15x
  emphasis and scales about its own centre without reflowing. At low resolution `N41 N41B` reads as
  `N4 N41B` and a lowercase `n` reads as `h`. Both "mismatches" dissolved under a 3x crop. The
  overlap itself is pre-existing emphasis behaviour common to every preset, not a MATRIX defect —
  noted, not filed.

  **CORRECTION to the old entry: "future words are not drawn at all (phrase windowing)" is FALSE,
  and specifically false for MATRIX.** `CaptionOverlayView.onDraw` lays out and draws EVERY visible
  word of the current phrase; what hides a not-yet-arrived word for the other presets is
  `pre.alpha <= 0.004f` skipping the draw. **MATRIX returns identity from `presetTransform`, so its
  alpha is always 1 and every word of the phrase is always drawn, scrambled.** The photograph was
  therefore never as hard as the entry claimed — the only real obstacle was placing the playhead.
  Worth keeping in mind when the remaining four presets are built: a preset that animates a channel
  OTHER than the transform is not subject to the alpha gate, so it is visible where the others are
  not.

  **A FINDING THAT COST A WRONG FIRST PREDICTION — the transcript is WINDOWED TO THE TRIM before it
  is grouped into phrases.** Grouping restarts at the first word at/after `inPointMs`, so phrase
  boundaries are NOT those of the pooled transcript. On `50d1cf46` (`inPointMs` 1406) the first
  phrase is **"this cat is very cute she"**, not the "this is a cat this cat" you get by grouping
  the whole word list. Predicting from the pooled transcript gives the right GLYPHS against the
  wrong WORDS. Recorded in `matrix_predict.py` as well, because that is where the next person will
  hit it.

  **The BLOCK signature, useful for reading any future MATRIX screenshot:** at BLOCK every word is
  substituted independently with `unitIndex = 0` using its own length, so character position *i* of
  every unsettled word shows the SAME glyph (tick 6 → `G` at index 2, `4` at index 3). That column
  fingerprint cannot arise by chance and is the fastest way to confirm a capture is genuine.

  **Sandbox restored, and proved restored.** `50d1cf46` was returned to preset FADE with no
  `captionAnimGranularity` key — **md5 `eb3d16b8…`, byte-identical to the pre-edit pull**, and the
  project re-opened with no fatal afterwards. Clip 0 still carries the deliberate MATRIX / In 0.5
  from the previous session, so MATRIX remains demonstrated in the sandbox without clip 1 having to
  hold it. Note the app wrote NOTHING to disk across the whole session despite being open and
  playing (md5 unchanged before the restore) — scrubbing and playback do not save.
  **An instrument that lied, worth knowing:** the freshness scan for the deleted string
  `"needs glyph substitution"` came back PRESENT, which reads as a stale dex. It is not — ODOMETER's
  blocker is `"needs glyph substitution and a per-slot roll clip"` and CONTAINS it as a substring.
  Substring collisions make deleted-string controls unreliable; prefer a deleted SYMBOL, or verify
  the collision before concluding.
- **UNSCRAMBLE IS BUILT AND SHIPPED — 2026-07-29.** Second of the five, in the user's order.
  **Its recorded blocker overstated the work, and that is the useful finding.** The ledger said it
  "needs PER-GLYPH POSITIONAL SCATTER — likely a third channel or a per-glyph Transform array".
  It needed neither. **At LETTER granularity every glyph is ALREADY its own unit with its own
  `Transform` and its own progress, in BOTH renderers** (`CaptionOverlayView.drawWord` and
  `CaptionExportRenderer.drawWord` each loop per character and call `drawUnit` with a `unitIdx` they
  already have in hand). The one thing missing was that `presetTransform` could not SEE which unit
  it was transforming, so every glyph would have travelled along the same vector — a diagonal wipe,
  not a scatter. **The fix was a parameter, not a channel:** a four-argument
  `presetTransform(preset, progress, fontPx, unitIndex)` overload, with the old three-argument form
  kept and delegating with `unitIndex = 0`, so no existing caller had to change and no other preset
  could be disturbed. Contrast MATRIX, which genuinely did need a second output channel — the two
  look alike in the blocker list and are not alike at all. **Read the drawing loops before believing
  a blocker note.**
  Design decisions, so they are not re-litigated: **every unit travels the SAME distance and differs
  only in DIRECTION** (equal magnitude is what makes it read as one body reassembling rather than
  letters arriving from arbitrary depths); the direction is a pure function of `unitIndex` built
  from `mix` and **`Math.sqrt` only** — `sqrt` is the one operation of its kind IEEE 754 requires to
  be correctly rounded, so it is bit-identical everywhere, which `sin`/`cos` are not, and that is
  why the vector is drawn-and-normalised rather than an angle put through trigonometry; the settle
  is `decelerate`, deliberately NOT an overshoot, because at LETTER granularity an overshoot sends
  letters past their slot and reads as the text scrambling a second time just as it lands; alpha
  reaches 1 well before the motion ends, or it would be GHOST.
  **On a single-unit body (a text box, or BLOCK) it resolves to ONE direction and reads as a
  directional slide.** That is the honest result of scattering one object, not an approximation —
  but it means UNSCRAMBLE only says what it means at LETTER. Documented on the parameter.
  **Proof — harness 192 → 209 checks, 0 failed** (17 new). The load-bearing ones: 24 units give **24
  distinct directions** at equal distance; it is a pure function of (progress, unit); travel is
  proportional to `fontPx`; distance from home decreases **monotonically** (the no-overshoot
  property); the 3-arg overload is exactly unit 0; and `presetTransformAt` really passes the index
  through (8/8 distinct offsets). **The control that makes those mean something: no OTHER
  implemented preset's output changes with `unitIndex`** — plus a companion check that UNSCRAMBLE's
  own output DOES change across the same units at the same progresses, so that control is not
  vacuous.
  **On the Note 9:** an **eighth tile appeared where the previous build had seven** — the same
  behavioural freshness proof MATRIX's seventh tile gave, which no symbol scan can fake. Over a
  12-frame burst its mean-luminance sd is the highest of any tile (6.93) with **NONE at exactly
  0.00** as the control, and its per-row sd is 9.56 against 7.07 for the next highest (Rise): ink
  moving across rows is precisely what a scatter does and an alpha preset cannot. Measuring the
  per-glyph horizontal centroid, **UNSCRAMBLE puts glyphs in MIXED left/right directions in 6 of 7
  usable frames with a 10.0px spread**, the largest of any preset. **Stated honestly: GHOST scores
  3/7 on that same measure**, because it has a `dx` too and its per-glyph stagger moves centroids
  both ways — so the device measurement shows UNSCRAMBLE is the clear outlier, not that it is
  uniquely mixed. The definitive per-unit-direction property is pinned by the harness, not by the
  photograph; the photograph shows the tile is real and driven by the evaluator.
  Dex-scanned on the installed artifact: `scatterAxis` and the `Unscramble` label PRESENT,
  `FadCamApplication` as the partial-dex control, and the deleted blocker string
  **`"needs per-glyph positional scatter"` ABSENT** — with MASK_WIPE's and NEON_FLICKER's blocker
  strings still PRESENT in the same scan, which is the control proving the scan CAN find blocker
  strings and that this one is genuinely gone. **No substring collision this time** (unlike the
  MATRIX session's trap), and it was checked rather than assumed.
  **An instrument that lied, again, differently:** a first dex scan reported 0 hits for EVERY
  symbol including `FadCamApplication`. Nothing was wrong with the build — the loop split
  `grep -c` output on `:` and the Windows **drive-letter colon** ate the field. A scan whose
  positive control also reads zero is a broken scan, not a stale artifact. That is what the positive
  control is for; read it first.
  **Fixed in passing:** the caption drawer's Motion row had no `case MATRIX`, so it fell through to
  `p.name()` and read a bare **`MATRIX`** instead of `Matrix · word`. UNSCRAMBLE would have landed
  in the same hole. Both cases added; the row now reads `Matrix · word` on the phone, which doubles
  as an independent behavioural check that the new build is the one running.
- **MATRIX NOW CHURNS IN JAPANESE — halfwidth katakana + digits, 2026-07-29.** The user asked
  whether Japanese was possible. It is, and **the old code's reason for refusing it was wrong.**
  That comment said katakana "would render as tofu in most of" the caption fonts. All six families
  `CaptionStyle.typeface()` can return are SYSTEM families, so they resolve through Android's font
  FALLBACK chain, not one file. Verified on the Note 9: `/system/fonts` has `SECCJK-Regular.ttc`
  and `NotoSerifCJK-Regular.ttc`, and `fonts.xml` lists the latter `fallbackFor="serif"` — sans and
  serif branches both covered. A custom asset font would have been the tofu risk; there isn't one.
  **HALFWIDTH (U+FF66…U+FF9D) is load-bearing, not taste** — and it is also the film's actual look.
  The layout-safety property is that the slot width was measured from the REAL text and reused
  untouched; fullwidth katakana are ~2× a Latin advance, so they would preserve LENGTH while the ink
  stopped fitting and collided with the next unit. U+FF9E/U+FF9F are excluded because they are
  combining marks that would attach to the previous glyph instead of filling a slot.
  **Proof: harness 209 → 213, 0 failed**, pinning the range, that katakana is actually reached, that
  no fullwidth leaked in, and that the combining marks are absent. On the Note 9 at the same
  playhead used for the ASCII proof (`00:01.113`), the caption reads **`thｸﾖ caｸ iｸ veｸﾖ cuｸﾖ shｸ`**
  — character-for-character what `tasks/matrix_predict.py` predicts, with the BLOCK column
  fingerprint intact (same glyph at position 2 of every unsettled word). Screenshot
  `tasks/screenshots/matrix_katakana_tick6.png`.
  **Two traps paid for here, both worth knowing:**
  1. **Literal katakana in a CHAR/STRING literal breaks the harness build.** The harness compiles
     with the platform default encoding (windows-1252) while the files are UTF-8. Both the source
     and the test now build their alphabet from CODE POINTS, never literals. Note the limit of the
     rule, which this session first got wrong in a javadoc: the surrounding COMMENTS are full of em
     dashes and always compiled fine — mojibake in a comment changes nothing. Only literals matter.
  2. **A failing `javac` leaves the previous `.class` files in place, and the harness then runs the
     STALE build and reports a confident pass.** It printed `209 passed, 0 failed` off bytecode that
     predated the change. Delete the output dir, or check the compiler's exit before believing the
     runner. The clean run then found a real failure the stale one had hidden — the test's own
     `GLYPHS` mirror still held the ASCII alphabet.
  **Known limit, stated:** a device with no CJK font in its fallback chain (a stripped or Go ROM)
  would draw tofu. Digits are kept in the set so such a device still shows something. If it is ever
  reported, the fix is a `Paint.hasGlyph` probe in ONE shared helper called by both renderers —
  never two probes, or preview and export could pick different alphabets from the same project.
- **NEON_FLICKER — ITS RECORDED BLOCKER IS MISLEADING. Designed 2026-07-30, NOT built.** Full
  reasoning in `SPEC_TEXT_ANIMATION.md` ("NEON_FLICKER — the recorded blocker is MISLEADING").
  The note says it "needs the renderer to modulate stroke/glow". True, and not the problem.
  **Stroke and glow are OPTIONAL PER-OBJECT properties that most objects do not have:**
  `TextBoxRenderer.paintRun` draws a glow pass only when `getGlowRadiusPx() > 0` and a non-transparent
  colour, and **captions have no per-object glow at all** — `CaptionOverlayView` uses a fixed
  `setShadowLayer` from `style.shadow`. So "modulate what is there" is a tile that does nothing on a
  default text box and nothing on any caption — the exact thing `Preset.implemented` exists to stop.
  **So it must SUPPLY its own glow**, derived from the unit's colour: a glow radius (and colour) on
  `Transform`, plus a glow pass in the two caption painters that lack one. Same shape as MASK_WIPE's
  `revealFrac`. **It does NOT hit the GHOST-blur wall** — that wall is `BlurMaskFilter`, which a
  hardware canvas ignores; `setShadowLayer` is honoured for TEXT and `TextBoxRenderer` already
  depends on it in the live preview. So no `LAYER_TYPE_SOFTWARE` and no divergence. Confirm that
  with one on-device look before building. Cheap alternative on record: flicker ALPHA only — works
  everywhere with no new channel, but reads as a stutter rather than as neon. **That last one is a
  taste call for the user; everything above it is not.**
- **ODOMETER IS DESIGNED, NOT BUILT — and it has a SCOPE QUESTION THAT IS THE USER'S.** Full design
  in `SPEC_TEXT_ANIMATION.md` ("ODOMETER — design"). Two findings from re-deriving it against the
  drawing loops, which is now the rule:
  1. **Its blocker note is CORRECT** (unlike UNSCRAMBLE's). It genuinely needs a third channel: a
     rolling slot shows TWO characters at once at different offsets, which neither one `Transform`
     nor one `substituteUnit` string can express — plus a clip rect, or the outgoing character
     bleeds into the line above (caption leading is only `1.15 ×` the line box). **The caption half
     is nevertheless small:** both renderers already draw per-glyph at LETTER with `x`/`baseY`/`w`
     in hand and already `save()`/`restore()` per unit, so it is `clipRect` + a second `drawText`
     inside an existing bracket, at two symmetric sites.
  2. **The text-box half is BLOCKED, and it collapses into work already on the books.** The text-box
     preview is a `TextView` (`TextOverlayLayer:211`) — one view, one string, so it cannot draw two
     clipped glyph rows, while the export could. That is the preview/export divergence this area
     exists to prevent. **Giving text boxes a canvas renderer in preview is the SAME item already
     recorded as the reason they are BLOCK-only** (`textAnimGranularitySupported`), so doing it
     would unlock ODOMETER-on-text-boxes AND WORD/LETTER granularity there together.
  **NARROWED 2026-07-30 by the MASK_WIPE build, though the call is still the user's.** Finding (2)
  stands and is now sharper: the text-box wall is about **drawing two things, not about clipping**.
  MASK_WIPE needed a clip on the text box and got one from `View.setClipBounds` with no canvas
  renderer at all. ODOMETER still needs two clipped glyph rows in one slot, which one `TextView`
  holding one string genuinely cannot do — so the wall is real for ODOMETER specifically.
  Consequence: since NEON_FLICKER modulates paint and should not touch the wall either, **ODOMETER
  is likely the ONLY remaining preset that requires the text-box canvas renderer**, which makes
  option (b) a decision about one preset rather than about the whole remaining set.
  **The call the user owns:** ship ODOMETER captions-only behind a new `allowedPresets` gate on the
  picker (cheap, and the picker already takes `allowedGrans` for exactly this reason, so it is one
  argument away) — or fund the text-box canvas renderer first and get both. **Not started, so that
  nothing is left half-built:** a captions-only ODOMETER without the surface gate would put a dead
  tile on the text-box picker, which is the one thing this picker is designed never to do, so the
  gate is not optional and the whole thing is one unit of work.
- **MASK_WIPE IS BUILT AND SHIPPED — 2026-07-30.** Taken out of turn (ODOMETER is third in the
  agreed order) only because the user declined ODOMETER's scope question and the handoff's standing
  instruction was to take MASK_WIPE in that case. **ODOMETER was NOT started and its scope question
  is still the user's.** Full design in `SPEC_TEXT_ANIMATION.md` ("MASK_WIPE — BUILT AND SHIPPED").
  **Its blocker note was CORRECT about the requirement and misleading about the cost.** "Needs a
  per-unit clip rect" named something genuinely absent — a clip is neither geometry nor alpha, so
  it really did need a THIRD channel (`Transform.revealFrac`), unlike UNSCRAMBLE. But the clip
  turned out cheap at all four surfaces. **So a blocker note can understate the cost as easily as
  overstate it; it is a hypothesis in both directions.**
  **THE FINDING THAT MATTERS, and it contradicts what the handoff predicted: MASK_WIPE does NOT hit
  the text-box `TextView` wall.** The wall blocks ODOMETER because ODOMETER needs TWO clipped glyph
  rows in one slot, and one view holding one string cannot draw two things. MASK_WIPE needs ONE
  thing shown IN PART, and `View.setClipBounds` does that in the view's own coordinate space —
  i.e. before its scale/translation, which is the same order the export gets by clipping after its
  matrix, so the two agree by construction. **The wall is about drawing two things, not about
  clipping.** Consequence for planning: **NEON_FLICKER should not touch it either** (it modulates
  paint), which would leave ODOMETER as the only remaining preset that actually needs the text-box
  canvas renderer — so the ODOMETER scope question is narrower than it looked.
  Design decisions, so they are not re-litigated: geometry and alpha stay at IDENTITY (a wipe with
  a fade on top is a fade with extra steps, and would stop being distinguishable from FADE);
  left-to-right, i.e. reading order, with the exit needing no rule of its own because progress
  falls back through the same number; the mask clips HORIZONTALLY only with a generous
  font-relative vertical reach, since an over-tall mask clips nothing while an under-tall one crops
  ascenders and reads as a font bug on tall letters only; `revealFrac` is a FRACTION because the
  four consumers measure their slot in four different units and a pixel radius would mean four
  different wipes from one project; and it is a FIELD ON `Transform` rather than a fourth function
  precisely so a surface cannot consume the transform and miss the reveal — `blurPx` is the
  standing example of what a separate, easily-forgotten channel becomes.
  **Proof — harness 213 → 298 checks, 0 failed** (85 new; before/after measured by stashing, not
  read off this file). Load-bearing ones: alpha and geometry stay at identity at EVERY progress, so
  it cannot have been quietly backed by alpha; the reveal is font-size independent and
  unitIndex-independent (the stagger is `unitProgress`'s job); it is monotonic; out-of-range
  fractions cannot invert the rect (an inverted `clipRect` clips EVERYTHING, which would show up as
  text vanishing rather than as a wrong wipe); the exit re-masks exactly as the entrance uncovered;
  and `textBoxTransformAt` carries the channel, without which the preset would ship working on
  captions and dead on text boxes. **The control: no OTHER preset returns `revealFrac != 1` at any
  progress or unit — plus the companion check that MASK_WIPE itself varies it across 11 distinct
  values, so the control is not vacuous.**
  **A test that was WRONG and the code that was right:** a first version asserted 6/6 distinct
  reveal depths across a staggered phrase and failed at 4/6. Each unit's sweep is two slices wide
  while units are spaced one slice apart, so **at most TWO units are ever strictly mid-sweep** —
  a property of `unitProgress` shared by every preset. The assertion is now "a sweep is in
  progress", not "everything is mid-flight".
  **On the Note 9:** a **ninth tile appeared where the previous build had eight** — the same
  behavioural freshness proof MATRIX's seventh and UNSCRAMBLE's eighth gave, which no symbol scan
  can fake — labelled **"Mask wipe"**, with a space, which is the `presetLabel` fix arriving in the
  UI (the old code would have shown a bare `MASK_WIPE` here). Screenshot
  `tasks/screenshots/maskwipe_picker_9tiles.png`.
  **The measurement that separates a MASK from a FADE, over a 14-frame tile burst
  (`tasks/maskwipe_tiles.py`):** for each tile, the width of its ink and the brightness of its
  brightest ink.

  | tile | ink extent sd | extent range | peak ink sd | peak range |
  |---|---|---|---|---|
  | **Mask wipe** | **22.56** | **4–68 px** | **0.00** | **179–179** |
  | Fade | 19.33 | 29–77 px | 26.20 | 81–156 |
  | None (control) | **0.00** | 46–46 | **0.00** | 180–180 |

  **MASK_WIPE's ink appears and disappears in COLUMNS while never once dimming**; FADE's peak
  provably dims and never reaches full. **NONE is the built-in control and is the only tile reading
  0.00 on every measure**, so the instrument is reading the tiles rather than the clock or screen
  noise. Type and Matrix also hold peak at 179 (a step and a substitution — correctly not alpha
  ramps), so peak alone does not isolate MASK_WIPE; what does is the PAIR — peak sd 0.00 **and** a
  minimum extent of 4px. Type's minimum is 23px and Matrix's 36px, i.e. whole glyphs, because
  neither can show a sliver. Magnified: `tasks/screenshots/maskwipe_tile_x3.png` shows a single
  diagonal stroke of an "A", then a whole "A" plus the left stroke of the next.
  **On a REAL caption, paused and during playback:** at `00:02.407` the phrase "this cat is very
  cute she" drew as **`cu    she`** — the left half of "cute", cut by a hard vertical edge at full
  white, in the slot "cute" would occupy, with the other four words absent
  (`maskwipe_caption_cu_x3.png`). **The control is two playback frames of the SAME phrase 800ms
  apart** (`maskwipe_caption_pair.png`): `00:03.513` draws "her name" complete, `00:04.326` draws
  it masked down to a bare "h" stem and "nam" plus a sliver of "e" — same words, same x positions,
  so it is not a font fault, not a layout fault and not a clipped view. A partial glyph at full ink
  is the one thing no alpha channel can produce.
  **Persisted, and TARGETED:** picking the tile wrote `"captionAnimPreset": "MASK_WIPE"` to disk on
  its own (no Close & Save needed — worth knowing, since a previous session recorded that scrubbing
  and playback do NOT save; it is the PICK that writes). A structural deep-diff of the whole
  project against the app's own backup, ignoring only the documented id churn, found **exactly ONE
  difference in the entire file** — that preset, on that clip.
  **THE TEXT-BOX HALF IS PROVED TOO, quantitatively, and this is the strongest single piece of
  evidence.** `PICKERTEST` has no `startMs`/`endMs`, so its span resolves to the whole 30s project
  and `textAnimInPct 0.25` gives a 7500ms entrance — which makes the visible fraction predictable
  in closed form (`decelerate(local/7500)`). `tasks/maskwipe_predict.py` computes it per frame from
  the `00:0X.XXX` chip in the SAME framebuffer grab as the text. Over **12 playback frames the
  measured ink extent tracked the prediction with a worst deviation of 0.060 and most under 0.03**,
  reading `P` → `PIC` → `PICKE` → `PICKER` → `PICKERT` → `PICKERTES` → full
  (`maskwipe_textbox_12frames.png`). Both ends are controls: 0.002 predicted / 0.000 measured at
  `00:00.009`, and 1.000 / 1.000 from `00:07.520`. **The measured curve is also visibly the
  `decelerate` ease and not a linear ramp** — at `00:01.929` linear would predict 0.257 and the
  measurement is 0.467. The residual is one-directional and explained: the measured "full extent"
  is the last glyph's rightmost stem while the reveal is a fraction of the VIEW's width, which
  includes the trailing side bearing.
  Dex-scanned on the installed artifact: `revealFrac`, `revealClip`, `revealDrawsAnything`,
  `presetLabel`, `padPxFor`, `fontPxFor`, `setClipBounds` and the string `Mask wipe` all PRESENT,
  with `FadCamApplication` + `FaditorEditorActivity` as the positive control; **freshness control —
  the deleted blocker string `"needs a per-unit clip rect"` ABSENT, and `Mask_wipe` (the old
  title-caser's output) ABSENT**, while NEON_FLICKER's and ODOMETER's blocker strings are still
  found by the SAME scan, which is the control proving it can find that class of string. Substring
  collision checked, not assumed (the MATRIX session's trap).
  **An instrument that lied, a third time and a third way:** the first scan reported 0 for every
  symbol INCLUDING the positive controls — `strings` is not installed in this Git Bash. Same
  lesson, new cause: **read the positive control first; a scan whose control reads zero is a broken
  scan.** Two prior sessions hit this with a drive-letter colon and with a partial dex.
  **Fixed in passing — preset labels now have ONE authority.** Three switches spelled out preset
  names with a `default: Preset.name()` fallback; MATRIX shipped a session showing a bare `MATRIX`,
  and the text-box row's title-caser would have shown `Mask_wipe`. All three now call
  `CaptionAnimator.presetLabel`, and the harness fails any label containing an underscore, equal to
  the enum constant, or fully upper-case — so the NEXT omission is caught rather than shipped.
  **NOT proved, stated rather than hidden: the EXPORT path.** No file was exported. The export
  renderers were written to mirror the preview through the same evaluator and the same shared
  `revealClip`, and the harness pins the arithmetic, but no exported pixel has been looked at —
  which is the same limit every preset in this section carries.
- **One preset remains declared, NOT implemented and NOT designed** — NEON_FLICKER (stroke/glow).
  ODOMETER is designed but deliberately not built (see below). Each names its blocker
  in `CaptionAnimator.unsupportedReason` and returns identity, never an approximation. The picker
  filters on `Preset.implemented`. **Treat each remaining blocker note as a hypothesis to re-derive
  from the drawing loops, not as a specification** — UNSCRAMBLE's overstated the work and
  MASK_WIPE's understated it.
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
1. ~~The six preset tiles / freeze-frame distinction.~~ **CLOSED — not by engineering, by the
   user's decision. See §4.** The tiles animate (`aac13f0`); the freeze-frame gap (~3.0–3.7 mean /
   ~4% Type-vs-Fade against 33.90 / 98.5% for the None-vs-Type control) is real and is ACCEPTED as
   motion-only. Kept struck-through rather than deleted because the numbers are the reason the
   decision was a decision. Do not reopen it by giving a tile a decorative cue the renderers do not
   produce — that invariant is the whole reason the tiles are drawn from the evaluator.
2. **The text-box half of the user's direction** — see the BUILD note above. Carets are parked
   waiting for it. **This is now the largest open piece of §3g**, and the two corrections above
   (`drawCaptionAnimHandles` is called every frame; the dead thing is the gate; and the whole block
   is bound to MASTER-CLIP geometry) mean it is a SECOND GEOMETRY, not a re-pointed target.
3. ~~The export path's frame cost is unmeasured.~~ **MEASURED 2026-07-30 — and unlike the preview,
   LETTER is NOT free here.** Same project, same output settings, changing only granularity on disk
   (both the captioned clips and the `PICKERTEST` text box, together), timed from the exporter's own
   `Export started` / `Export completed` log lines rather than by wall clock around the UI:

   | | LETTER | BLOCK (control) |
   |---|---|---|
   | export wall time | **81.05s** | **56.08s** |

   **+44.5%.** Worth having because it is the exact opposite of the preview result, where LETTER
   was free at both the caption renderer (33.70% vs 33.70% janky) and the text-box renderer (32.60%
   vs 35.61%). Per-glyph layout is cheap when you are drawing one frame at the playhead and
   expensive when you are drawing every frame of the file.
   **Read it with its limits, which are real:** single runs, not repeated; the two arms move caption
   AND text-box granularity together, so this is their combined cost, not an attribution; and it is
   closer to a WORST case than a typical one, because the export is now 30.9s long (tail filler) and
   `PICKERTEST` is open-ended, so per-glyph work runs across the entire file while the captions only
   exist in the first 5.7s. A short title on a long video will cost far less than 44%.
   **Not a reason to discourage LETTER** — a 30s export taking 81s instead of 56s is a background
   service the user is told they can leave. Recorded so the number exists.
4. **Baseline editor jank is 33.7%** during playback and is NOT caused by §3g (identical in both
   arms). Recorded here because it was measured here, not because it belongs to §3g.
5. **An unreachable text overlay exists in the sandbox and nothing can select it.** `9d7fef2b` in
   `bb2a9deb` carries `startMs` = **2305843009213693951** (= 2^61 − 1) with no `endMs`. It is
   therefore never visible at any playhead position, so no preview long-press and no timeline chip
   can reach it — it cannot be edited or deleted through the UI at all. Found while picking a
   subject for the picker test, NOT diagnosed: where that value comes from is unknown, and it may
   be sandbox-only damage from an earlier probe rather than a live defect. Do not spend a session
   on it without first checking whether any code path can still produce it.

**Sandbox restored and PROVED restored, 2026-07-30.** `bb2a9deb`'s `project.json` is back at md5
**`eb3d16b8…`**, byte-identical to the handoff's reference, and it re-opened with **0 fatal
exceptions** and the md5 unchanged by the open. Two things worth carrying forward:
`project.json.bak` **was** the pre-session state at `eb3d16b8` — the app rotates it on write, so it
is a free byte-exact restore target and another reason not to "clean it up"; and **`MSYS_NO_PATHCONV=1`
must be set for the `/sdcard` argument but then breaks the `/c/...` LOCAL argument, so `adb push`
needs a WINDOWS-style local path in the same command.** Getting that wrong truncated `project.json`
to 0 bytes mid-session (the redirect ran before the failing `cat`); the `.bak` is what made it a
non-event. Also observed: rotation stayed `0` across an install, a force-stop and three `am start`
launches, which independently agrees with §5 — `monkey` was the cause, package events are not.

**Test-project state after this session** — `bb2a9deb` "P0 control no image" now sorts to the TOP
of Recent Projects (its `lastModified` is the newest), NOT second from the bottom as an earlier
revision of this file said. **Independently re-confirmed 2026-07-29 by dumping every project's
`lastModified` and sorting** — `bb2a9deb` came out first at "Jul 29, 07:30 AM", and opening the
top row did land in "P0 control no image". Do not navigate by the remembered date; the date moves
every time the project is opened. **And a navigation fact that nearly caused a mis-open on
2026-07-30: the Recent Projects rows are labelled with the SOURCE MEDIA FILENAME, not the
project's `name`.** `bb2a9deb` shows as "FadCam_20260621_145132", and three other rows show the
same string, so the list cannot be read for the project you want. Identify the row by matching its
displayed timestamp against the `lastModified` you dumped — that is what confirmed it. Map ids with
`adb shell run-as com.fadcam.beta cat files/faditor/projects/<id>/project.json` — and redirect
stdin (`< /dev/null`) if you loop over ids, or the inner `adb` swallows the loop's input and you
silently map only the first project. Its clip 1 is left at preset FADE, granularity WORD,
`captionAnimInPct`/`OutPct` **0.5 / 0.5** — set deliberately while proving the range control, and
left there because it demonstrates the feature. Its old `captionAnimInMs` keys are gone, which is
the read-and-ignore path working as designed, not data loss.

**Harness command (updated — it now compiles the phrase grouping and transcript too):**
`javac -nowarn -d tools/jvm-harness/out-caption tools/jvm-harness/stubs/androidx/annotation/*.java tools/jvm-harness/stubs-caption/com/fadcam/ui/faditor/transcript/CaptionStyle.java app/src/main/java/com/fadcam/ui/faditor/transcript/CaptionAnimator.java app/src/main/java/com/fadcam/ui/faditor/transcript/CaptionPhrases.java app/src/main/java/com/fadcam/ui/faditor/transcript/Transcript.java app/src/main/java/com/fadcam/ui/faditor/transcript/TranscriptWord.java tools/jvm-harness/CaptionAnimatorTest.java && java -cp tools/jvm-harness/out-caption CaptionAnimatorTest`

## 4. DECIDED — settled, do not re-litigate

- **GHOST's blur MAY diverge: export-only is authorised. (User, 2026-07-30.)** Asked whether to
  leave blur off on both surfaces or pay the preview's frame-rate cost, the user said: *"this is
  an exception where export can diverge for the better render… if ghost preview would cause
  noticeable lag in working but look much better in export i think the divergence in this specific
  instance is warranted."* **This is the ONLY sanctioned preview/export divergence in the project**
  and it is sanctioned because the alternative is a real cost to editing, not because the
  divergence is harmless. Note the condition attached to it: it is warranted *if* the preview
  blur actually costs frame rate. **So MEASURE the preview cost first** (`dumpsys gfxinfo`, the
  method that settled LETTER captions, against the known 33.7% editor baseline) — if software
  layer rendering turns out cheap, blur BOTH and no divergence is needed, which is the user's
  intent met more cheaply. Only if it is expensive should export blur alone.
  **NOT STARTED as of 2026-07-30.** `Transform.blurPx` is still consumed by nobody, and
  `TextBoxRenderer.drawUnit` documents why it does not apply it.
- **AI edits collapse into ONE undo step**, preserving the history behind them. (User, 2026-07-28.)
- **Trim precision is non-negotiable**; no keyframe snapping of user trim points.
- **No apology toasts** for things we can actually build.
- **The preset tiles are DONE — motion-only distinction is accepted. (User, 2026-07-30.)**
  Asked whether to accept it or give the tiles more than 60dp, he said: *"I don't know exactly
  what is at stake. the animations you have, I think, look great."* So the half-open item from
  `9c40056` is CLOSED as-is: the tiles are distinguishable by watching and not from a still, and
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

- **THE AUTO-ROTATE ALARM WAS OUR OWN INSTRUMENT. SOLVED 2026-07-29 — `monkey` did it.**
  **`adb shell monkey` calls `IWindowManager.thawRotation()` on teardown**, and *thaw* is precisely
  what writes `accelerometer_rotation = 1`. Every flip ever attributed to "a human may have picked
  the phone up" was the launch command in the step immediately before it.
  **Proved by a crossed 2×2, which is the only shape that separates the app from the mechanism** —
  the earlier attempt failed because it launched FadCam with `monkey` but the control app with
  `am start`, so it varied both at once and "concluded" FadCam-specific:

  | | `am start` | `monkey` |
  |---|---|---|
  | FadCam | `0 0 0 0 0` | `1 1 1 1 1` |
  | Settings (control app) | `0 0 0 0 0` | `1 1 1 1 1` |

  The logcat signature, for recognition: `WindowManagerService.thawRotation:4227` reached via
  `IWindowManager$Stub.onTransact` a second or two after the launch.
  **THE FIX IS ONE WORD: launch with `am start -n <pkg>/<activity>`, never `monkey`.** Then the
  tripwire means something again. **The user's rotation lock was never being disturbed by anything
  of theirs** — they re-locked it repeatedly and correctly, and only our own launches unlocked it.
  **Also exonerated, having been suspected on this page: Tasker** (installed, running, and it really
  does hold `WRITE_SECURE_SETTINGS`, which is what made it plausible) and FadCam itself (its source
  contains no reference to `thawRotation`, `freezeRotation` or `accelerometer_rotation`). Suspecting
  an app because it *could* is not evidence; the crossed test is.
  **This cost at least three sessions** — it ended device work on 2026-07-28, ended the MATRIX
  capture attempt, and interrupted this one twice — so the standing device rule should keep its
  human tripwire but drop the false trigger feeding it.

  <details><summary>The WRONG hypothesis this entry first recorded, kept because it shows the trap</summary>

  ~~The rule says stop if `accelerometer_rotation` goes to 1 mid-sequence,
  because that may mean someone picked the phone up; it is what ended device work on 2026-07-28 and
  the MATRIX capture on 2026-07-30. **It fired again on 2026-07-29, at the exact moment the app was
  launched**, and the evidence says no human was involved:
  `dumpsys power` put the last user activity 30s earlier (my own `monkey` launch injection),
  the display was still `rotation 0`, and **`com.fadcam.beta` holds no `WRITE_SETTINGS` and has no
  appops entry for it, so the app cannot have written the setting either.** Most likely the system
  restoring the user's own auto-rotate preference after the force-stop/relaunch that a previous
  session had overridden with `settings put`.
  **What was done:** re-locked with `settings put system accelerometer_rotation 0` (the runbook's
  own prescription after an adb reconnect) and continued, re-checking `mCurrentFocus` and the
  rotation setting between steps. It stayed 0 for the rest of the session and the work completed.
  **IT THEN FIRED A SECOND TIME the same session, on an `adb install`** — again with no human touch
  (`mLastUserActivityTime` 14 minutes stale, and that last activity was my own injected tap), again
  with the display still at `rotation 0`. Two independent occurrences, both correlated with a
  **package-state event** (launch after force-stop; install) and neither with a human. That is a
  much better hypothesis than the original one: the system restores the user's own auto-rotate
  preference when the package state changes, overwriting whatever a previous session's
  `settings put` had forced.
  **What is still the user's call:** whether the rule should be relaxed to "stop only if the setting
  flips WITHOUT a package event in the same step, or if `mLastUserActivityTime` shows touch input we
  did not inject". Do not relax it unilaterally — it is a safety rule about someone else's phone.
  ~~**Practical note either way: re-lock after every install and every launch**, not just after an
  adb reconnect, or a later step will trip the alarm on the previous step's leftovers.~~

  **Why it was wrong, and the lesson.** Both observations were real; the inference was not. "It
  flipped right after a package event" was true twice and still meant nothing, because a `monkey`
  launch was *inside* both of those steps — the package event and the real cause were perfectly
  confounded, so no number of repetitions of the same shape could separate them. **Two occurrences
  of a confounded observation are not two pieces of evidence; they are the same one twice.** The
  fix was to vary the two factors independently, which took one command.
  </details>


- **BLOCKED 2026-07-28 21:xx: the only phone attached is the Note 20 `<note20-serial>`.** The Note 9
  sandbox `<note9-serial>` is absent. Under the standing device rule that is a full stop on
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

---

## 2026-09-13 — SpriteLab on the phone, built to the approved mockup

Overnight session. JoyRaptor: *"now build EXACTLY this, fully functional on the phone."*
"This" is `tools/spritelab/SpriteLabMobile.html`, layout C, refined over several rounds.

**Device: Note 9, five install/verify cycles.** Build green at every commit
(`tools/phone.ps1 build`); no gradle run by hand.

### What was proved on the device, not merely compiled

| Proved | Evidence |
|---|---|
| Roll building with order badges | tapped cells 0,1,2,3 — grid showed cyan `1 2 3` and pink `4`, film chips `#1 c0` … `#4 c3` |
| Playback follows the ROLL | pressed play: preview showed c2, film chip c2 pink, grid badge 3 pink, scrub segment 3 pink — all four in lockstep |
| Number pill drag | swiped the `x` pill 200px: 2 → 13, preview AND grid art both moved (preview and export agree by construction — one `drawCell`) |
| Undo is one press for one gesture | that whole drag reverted in a single press; redo lit, undo greyed |
| Save clip → Clips shelf | "clip2" appeared beside "winkclip1", both `4f · 8fps` with a cyan loop dot |
| Picking a clip | cyan border, its frames lit on the sheet, action row appeared (Load / Rename-retime / Delete) |
| Drawer at detent 2 | two balanced rows, animations first, 9 + 8 chips |
| Drawer overflow | Cycle all · Ping-pong all · Hold this cell · ★ Save these 5 keys as an animation |
| Sprites tool icon | now a running figure in the toolbox |

### The defect the screenshots found that the build could not

**A named cell the assistant cannot see is not a named cell.** The Export panel read
"0 named" over a sheet whose cell 3 is called "surprised". Two stores held that name —
`cellNames`, which is what the sidecar JSON and therefore the AI read, and `Cell.name`, which
is what sheets written by the older palette carry — and nothing reconciled them. The entire
reason JoyRaptor wants cell naming is that it "super powers the LLM side", so a name landing
in the store the AI never reads is *worse* than no name: it looks done. `cellNames` is the one
truth now; reading falls back, writing updates both, loading migrates.

### Three things a drag would have made unbearable, found by audit, not by eye

- The tolerance pill called `reloadRenderer()`, which rebuilds the section containing the pill
  under your finger. The gesture died on tick one.
- An alignment nudge REBUILT the film strip chip by chip, per tick. So did the scrub bar, on
  every move event.
- Every keystroke and drag tick serialised the whole sheet for an undo snapshot.

Also: the grid kept throwing away your zoom and your selection on every re-decode (undo, a
tolerance change, a relink) — which is how you lose the close-up you were aligning in.

### One process note worth keeping

`tools/phone.sh launch` force-stops the app and fires `monkey`; when that silently fails the
PREVIOUS foreground app stays, and the next blind tap lands in it. Twice this session that was
JoyRaptor's personal Gmail. **Screenshot, or check
`adb shell dumpsys window | grep mCurrentFocus`, before every tap batch.** The screenshots
were deleted unread.

### Two more, found by testing the ROUND TRIP rather than the feature

- **Slice finished**: Grid / Names switches, Suspect (an amber dot on any cell whose ink runs
  into its own edge — it correctly flagged the two widest poses on the test sheet), and
  **Name many…**, which walks the cells with the art in front of you. Naming a cell the old
  way was four moves; sixteen cells was sixty-four, which is why sheets stay unnamed.
- **The Lab did not autosave on pause.** Named cell 0 "smile", killed the app, reopened — gone.
  Not a naming bug: this screen only saved on Back while the rest of the editor autosaves on
  pause. Proved fixed: named, pressed Home, relaunched, "smile" is on the grid in cyan and in
  the Alignment header, save button grey.

### Left undone ON PURPOSE

Swap / Ripple / Reset order is the last item from the mockup's Slice section and it is stopped
on a RULING, not on work: reordering cells decides whether a cell's name and its alignment
follow the drawing or the slot, and either answer silently rearranges sheets JoyRaptor has
already named. Both options and my lean are in INBOX 2026-09-13. Guessing that at 6am on his
behalf is how you lose someone's afternoon.

Commits: 28b03c0e · 1561af46 · 6657cffd · 73330444 · 9924d6ef · b02f0eaa · 95047608 ·
b3494144 · 13840add

---

## 2026-09-13 (later) — the ruling, and closing the polish gap

JoyRaptor, on the morning report: *"yes name and alignment move with it"*, and — on the UI —
*"still seeing emojis for icons instead of svgs. drawer dosnt have round overs and generally
still dosnt look near as nice... close that gap."*

### Cell order, built to the ruling

One numbering, plus a display->source map every lookup goes through. `cellNames`, `cellXf`,
the viseme and the enabled flag are keyed by the SOURCE cell, so a drawing brings all of them
when it moves, and Reset order restores everything because none of it was ever attached to the
slot. The map is applied in `SpriteSheetRenderer.cellRectBitmap` — the one place a slot becomes
a piece of art — so preview, export, grid, film and drawer reorder together by construction.
A saved animation's frame list deliberately does NOT follow: those are slots, and that is what
arranging a sheet is for.

Device-proved: armed Swap, dragged slot 0 onto slot 3, and "smile" arrived with the smiling
star while "surprised" went the other way. Reset order put both back.

### The polish gap

| Was | Now |
|---|---|
| Drawer had square corners on a flat panel | Rounded top on the scrim token, video still visible under it |
| Drawer chips were art floating on the background | CARDS — art on a surface, name, sub-line — the design's `.ch` |
| Three loose keyframe buttons | One segmented pill; the middle is a KEY diamond, not a bin that left you guessing what it would delete |
| Emoji for the bin, record, film, overflow, step arrows, mode dots | Eight more icons added TO THE DESIGN FILE and regenerated |
| Flip H / Flip V as words | The design's marks |
| Three controls calling setBackgroundColor | Pills that stay pills |
| Delete-key faded to 32% | Pink when it will do something, grey when it will not |

The scrim had to go from 0xE0 to 0xF2: the web design separates the drawer with
`backdrop-filter: blur(13px)`, Android has no cheap equivalent, and at 0xE0 the toolbox labels
behind it stayed legible and read as a rendering fault rather than as depth.

### The adversarial pass on Slice

Four things decided what a drag on the sheet would do — pan, pivot, swap, ripple — as four
chips that looked exactly like the three DISPLAY switches beside them, and **two could be
armed at once**, with the touch handler silently letting reorder win so Pivot would just stop
working. One segment now, exclusive by construction because a single setter drives both grid
modes. "Key" became "Bg key" (it read as "keyframe" on a screen that has none) with a droplet
of its own rather than borrowing Detect's wand.

### One thing I could not account for

Twice a cell name I had verified on screen was missing from `project.json` later. I could not
reproduce it: writing a name, pressing Home and reading the file off the device shows it
saved, and it survives a full editor round trip — checked by `run-as ... cat project.json`
both times, not by looking at the UI. So rather than claim a fix, the window was narrowed:
the Lab now writes about a second after you stop changing something, debounced, instead of
only on pause and on Back. If a name ever does go missing again, that is the thread to pull —
and the remaining suspect is a second in-memory copy of the project being saved over the top.

**The test sheet was left exactly as found**: the probe name written to cell 13 was cleared and
`project.json` re-read to confirm `{3: "surprised"}`.

Commits: ec257b73 · ca52d34a

---

## 2026-09-13 (day) — the SpriteLab output pipeline, in parallel with the transform agent

JoyRaptor: *"Get started on all the spec on your list... respect lanes and coordinate with it
so theres no conflicts... adversarially check your work."*

**Lane discipline.** Claimed SPRITELAB OUTPUT before the first edit and named, in the claim,
the files that belong to the SPEC X/Y/Z agent (TransformOverlayView, the four TransformHosts,
MeshStampGl, TransformQuad, MeshWarpSpec, OverlayTextureCache, FxPreviewTextureView,
ImageBlendGlEffect, FaditorEditorActivity, SpriteOverlayItem/View) so the boundary was on the
board rather than in my head. `SpriteSheetRenderer` was declared READ-ONLY for me and stayed
that way: the baker calls `drawCell`/`cellRectBitmap` and modifies neither. Nothing of theirs
was touched, and their commit c93218f0 (two spec files) did not collide.

**What landed**, all four device-proved on the Note 9:

| | Evidence |
|---|---|
| Bake to a new sheet | 3 frames -> 3x1, 1458x448 px, animation carried, "surprised" carried to its new slot, original untouched. PNG pulled off the device and looked at. |
| Merge across sheets | Starguy baked + Sprite pang -> 8x2, 3888x904, 9 frames. The pangolin panels are PORTRAIT and the stars are square: the merged sheet shows them letterboxed, undistorted. |
| Numbered frames | three PNGs on disk, listed by `run-as ls`, one pulled and viewed |
| Roll reorder | long-press lift, drag right past the end: `cell0, cell1, surprised` -> `cell1, surprised, cell0`, grid badges following |
| Shelf reorder | `winkclip1, clip2` -> `clip2, winkclip1` |

The gesture could only be scripted because `input motionevent` turns out to exist on this
Android 10 build — DOWN, hold, MOVEs, UP. `input swipe` cannot test a long-press-then-drag,
because it starts moving immediately and cancels the long press.

**The adversarial pass was the valuable part.** A reviewer went at the diff and found twelve
things; the first was mine and fatal:

- `moveRollDrag` computed the drop gap, drew it, and never stored it. The drop line tracked
  your finger perfectly and the reorder was a no-op **every single time**. The arithmetic
  around it was correct — the value just never arrived. A screenshot would have shown a
  convincing drag doing nothing.
- Back during a bake finished the Activity, and the completion block then showed a dialog on a
  dead window and saved that Activity's STALE project over whatever the editor had since
  written. The bake is modal now and the completion block checks it is still alive.
- The worker walked the live preset and sheet lists — a ConcurrentModification there is an
  uncaught exception on a non-UI thread, i.e. process death, not a message.
- **The sidecar importer had fallen behind the exporter**: per-cell ALIGNMENT, visemes and the
  arrangement were all silently destroyed by an export/import round trip.
- Re-slicing left a stale `cellOrder` pointing slots at drawings that no longer existed.
- `ensureCell` keyed a new Cell record by the display slot instead of the drawing, so on a
  rearranged sheet the Enabled switch darkened somebody else's frame.

Plus the hint that re-laid-out the strip under the lifted chip, no edge auto-scroll on a long
roll, a null preset frame, padding ignored in one fit mode, mkdir failures reported as "nothing
to write", and dropped animations never counted.

**Housekeeping.** The test clip was deleted and `project.json` re-read to confirm "Starguy" is
exactly as found. Two baked sheets could NOT be removed — there is no delete-a-sheet
affordance anywhere in the app. Logged in INBOX; it is a real gap, not a tidying nit.

**The morning's open question is answered, by the other lane.** Their LANES note (b670a8c7):
an `adb install` from one lane kills the app the other has open, and logcat then says "app
died, no saved state", which reads exactly like a crash. That is the unexplained process death
I chased this morning and could not reproduce, and it is almost certainly where the cell name
went: the Lab was holding unsaved work when the other lane installed. The debounced autosave
added at ca52d34a is the right mitigation for a shared phone, and now it has a reason rather
than a shrug. Two lanes, one device, is a hazard worth the line they added.

Commits: d4ff2b6e - 00d15220

---

## 2026-09-13 (afternoon) — sheet management, and a review that found two criticals

JoyRaptor: *"keep building out all the features and adversarily audit your work both in code
and quality of UI and ux."*

### Built

- **Sheets in this project** — Export gained a panel listing every sheet: open, rename, remove.
  This closes the gap the morning's bake testing exposed, where a sheet could be created and
  never removed, and the only way back was editing project.json by hand.
- **The merge rail** replaced the picker. Rethinking it for a phone made it SIMPLER than the
  desktop original: the desktop greys a source out rather than dropping it because reloading
  costs a file picker, but here every sheet is already in the project, so the whole
  load/unload/restore dance collapses into "tap to include".
- **Provenance**: a baked sheet records what it was baked from, by name, and the panel shows
  "baked · Starguy + Sprite pang".

### The two criticals, both the same shape: right about what it checked, wrong about what
### there was to check

**There are TWO stores of sheet ids.** Sprites on the timeline carry one; every PART of an
avatar rig carries another. They never overlap — placing an avatar copies each part sheet into
the project and places ONE sprite pointing at the neutral bake — so a part sheet has exactly
zero timeline references. The in-use guard counted zero and armed Remove on precisely the
sheets a puppet is built from. Removing one drops that body part out of the exported video with
no error anywhere, and those copies look exactly like the clutter the new panel invites you to
tidy. Verified in the code before fixing: `AvatarRig.Part.sheetId`, persisted by ProjectStorage.

**Remove claimed to be undoable and was not.** It called `noteChange()`, but the undo snapshot
holds the OPEN sheet only — so the button lit, the user pressed it, and the deletion stayed
while `save(true)` had already committed it. Rather than serialise the whole project on every
keystroke to make the claim true, removal stopped touching undo and offers "Put <name> back"
in the panel instead, with the dialog saying how long that lasts.

### What the UI audit caught that code review would not

- The bake header read **"15 frames · 4 sheets"** on a sheet with 3 frames. The rail was
  exclude-by-default, so Bake meant "bake every sheet in the project" — and an imported image
  sequence counts as a sheet with art, so a few hundred frames could join in and surface only
  as "Bake failed: OutOfMemoryError". Opt-in now.
- Every row in the Sheets panel drew its thumbnail through THIS activity's renderer, so all
  four showed cell 0 of the sheet already open. You were choosing what to delete from a list of
  identical pictures, none of which was the sheet in question. Name-and-sub rows now: a true
  line beats a false picture.

### Left for the other lane

`FaditorEditorActivity.onResume` gates its reload on `!timeline.isEmpty()`, which is VIDEO
clips only. In a sprite-only project the reload is skipped and the editor's next autosave
writes a stale project back — which now means a sheet removal or a rename can silently revert.
Their file; posted at the top of their lane on the board rather than edited.

### Housekeeping

Both test sheets removed through the new UI, and project.json re-read: back to
['Starguy', 'Sprite pang'] with winkclip1 and "surprised" intact. No hand-editing.

Commits: f6b50304 · f567b65e

---

## 2026-09-13 (late) — the held-back fix, taken while the other lane was paused

JoyRaptor: *"Anything you needed to do that you were afraid to because someone else is working,
go ahead and do that right now."* There was exactly one.

**`FaditorEditorActivity.onResume`'s reload gate asked "does this project have VIDEO".**
`Timeline.isEmpty()` is `clips.isEmpty()`. A sprite-only or sequence-only project legitimately
has none, so the reload after an external save was skipped, the editor kept a stale project,
and its next autosave wrote that stale copy back. The modified-signal is consumed BEFORE the
reload is attempted, so the refusal was also permanent and silent.

Harmless while the sprite editor only made additive edits. Not harmless once the Lab could
REMOVE a sheet: a removal or a rename could be silently undone by the editor sitting behind it.

Fixed as narrowly as possible — one gate swapped for a `reloadLooksReal` predicate, one new
private helper, a log line and a message where there was silence. Nothing else in their file
touched, the whole change is one `git show`, and the lane note on the board now says exactly
what moved and invites them to reshape it.

**Proved rather than argued:** renamed a non-open sheet in the Lab, returned to the editor,
backgrounded the app so the editor autosaved, and read project.json off the device. The rename
survived. Renamed it back and re-read: ['Starguy', 'Sprite pang'], winkclip1 and "surprised"
intact — JoyRaptor's project exactly as he left it.

Also closed a loose end in my own panel: it invited you to manage sheets without saying which
one was broken. Missing art now reads "⚠ art is missing", and only when a path can actually be
resolved and checked — a false alarm would send someone relinking art that never broke.

Commit: 26bdf18c

---

## 2026-09-13 — SPEC ZC: a text box that can be corner-pinned (compile-verified, device pass owed)

Text carried a pin in the model but neither surface could draw it — text goes through child
Views, and there was no view applying the matrix and no layout room for a pulled corner.
Mirrored the image path: new `overlay/CornerPinTextView` (a TextBoxView whose unpinned
`onDraw` is literally `super.onDraw`), layout inflation in the text branch of
`TextOverlayLayer.position` (the pin margin rides INSIDE `boxInsetPx()`, so the box stays
centred and the caret follows), and the same `TextOverlayItem.cornerPinMatrix` concat-ed
inside the rotate in `CompositeExportOverlay`'s text branch — one method, both surfaces.
The pin is a matrix on glyph outlines, so the box stays vector-sharp on both.

Two findings that changed the sheet: the pin write/read blocks in `ProjectStorage` were
already ungated (only the mesh is image-gated, and stays so) — no change needed there; and
the sheet's named export file, `TextOverlayRenderer`, no longer sits on any live export
path (its `CompositeExportOverlay` caller is unreachable dead code — both branches above it
`continue` — and its only live caller rebuilds a pin-less item), so the export half went
where the export actually happens. Known follow-ups for the transform lane, not this sheet:
text with effects via `TextFxGlEffect`, and plain text under a GL-routed image, rasterise
without the pin; the transform surface still offers text no pin channel (gate shut, untouched).

Proved: `bash tools/build-verify.sh CornerPinTextView` → VERIFIED in the packaged dex, plus
`textPinMatrix` in the dex and the new `excursionFraction` call in the layer's bytecode;
`typecheck.sh` 717 sources OK; preview-parity, persist-lint, pinbudget, flip and mesh
harnesses green (matte + copy-lint were already red, untouched). Unpinned fast path and
zero per-frame allocation hold by construction (same funnel as the image view). NOT proved:
no phone was attached, so the pinned preview/export screenshots, the sharpness photographs
and the project.json round-trip are owed — the JSON to paste is `"pinTLdx":0.2` (and
siblings per `CornerPin.jsonKeyFor`) on a text overlay.

Addendum: the ZA lane's bare commit `6d03b418` swept this sheet's two staged
`CompositeExportOverlay` hunks (the `textPinMatrix` field + the text-branch concat) into its
own commit — the exact bare-commit corollary in `LANES.md`, this time with this sheet on the
receiving end. Content intact in HEAD (verified via `git show`), history misattributed.
Left as-is: the commit also carries ZA's whole feature and no mid-flight history rewrite is
worth the risk. The remaining ZC work (new view, layer, LANES, this entry) is staged,
uncommitted, and NOT in that commit.

**Post-audit fix (2026-09-13):** the auditor flagged caret misalignment on a pinned box
being edited — `updateEditorInsets()` read the overridden `boxInsetPx()` (now including
`pinInsetPx`), so the EditText sat over the box rect but drawn glyphs were distorted while
the editor rendered undistorted. Fixed by suppressing the pin while the editor is attached
(`usesMatrix()` returns false when `hasEditor()` is true). This keeps caret/selection
aligned with undistorted editor glyphs; the pin re-engages automatically when the drawer
closes. `TextBoxView.hasEditor()` added as protected getter; doc updated.

---

## 2026-09-13 — SPEC ZB: a clip that can hold a warp (model only, nothing draws it yet)

PiP and spine are both `Clip`, so one pair of fields serves both: a `cornerPin` (eight
offsets, fractions of the clip's own size) and a `MeshWarpSpec`, with the sprite's
accessor names down the line — `get/set/copyInto/clear/has/animated/cornerPinMatrix`,
`hasMesh/get/set/installMeshCurve/meshLocalTime`, `wantsGl`. Put beside the spine pose,
not inside it. No renderer reads any of it (that is SPEC ZD); nothing on screen changes.

Two judgements the mirror forced. A clip has TWO keyframe sets where a sprite has one
(spine vs overlay envelope), so the pin tracks live in the existing sets — no new
persisted state, `KeyframeCodec` already round-trips any track name — with the overlay
envelope winning when both name a track. And a clip speaks clip-local ms everywhere
(`spinePoseAt`, `opacityAtClipMs`), so `animatedCornerPin`/`cornerPinMatrix` take
clip-local too; `meshLocalTime` is the floor-to-zero identity, kept so all four types
answer the same question with the same name. `SpineSnapshot` carries both, restores both,
compares bends by serialised form, and treats null keys and empty keys as the same
"not armed" every reader already treats them as.

Persistence extends the deliberate exclusion rather than working around it: eight sparse
keys via `CornerPin.jsonKeyFor` plus a `mesh` object only when authored, so an unwarped
clip adds not one byte. The lint gained a `("Clip", "cornerPin")` CUSTOM entry (getter +
setter with the `clip.` prefix — an exemption would pass with the block deleted).

Proved: new `run-clipwarp.sh`, 40/40 off-device through the REAL serialiser — copy bends
independently, unwarped saves byte-stable with zero warp keys, pre-sheet JSON loads
undistorted, unknown keys/unknown topology/malformed mesh/explicit-null pin all keep the
clip. The lint's negative control: writer deleted → red on `Clip.cornerPin`, restored →
green. `bash tools/build-verify.sh pinOwner` (a symbol only this change introduces) →
VERIFIED, APK newer than the edit. persist/envelope/mesh/pinbudget/speck/mask/splitcopy
green; copy-lint unchanged (its 2 masterFade fails pre-date this sheet, another lane).
NOT proved on a phone: no save/diff on device, no pre-sheet build install for the
forward-compat half (argued from the old reader's explicit mesh-drop plus unknown keys
never being queried). Nothing committed — staged only, per the sheet rules.

---

## 2026-09-15 (night) — the stranded batch lands, and two tools that stop the next hour being wasted

### Landed

The six-finding staleness sweep that the stalled watcher stranded on 2026-09-13 compiled green
first try and is committed (`4e8fe08b`). It included a crash three taps away: the film chips
held the renderer they were built with in a final field, and the strip lives outside the panel
that rebuilds, so relinking art or dragging the tolerance pill recycled that bitmap and the next
redraw threw. Also: "Add all" / "+ this cell" / "Clear" had skipped the currentCell funnel; the
alignment pills held a frozen copy of one cell's transform so dragging during playback wrote to
the wrong frame; the Slice cell editor had the same fault across a re-slice; clip badges
outlived the Clips section; stopping playback with an empty roll left the preview somewhere
nothing else knew about.

### Verified on the Note 20 (A8 E4 sandbox only)

The drawer — carded chips, the lit key diamond, no emoji — and the whole Lab on the Joybot 5x5
sheet: number pills, the Grid/Names/Suspect/Name-many row, the `drag [Pan|Pivot|Swap|Ripple]`
segment, the cell editor, the transport. The Sprites tool shows the running figure here too.

### The hour I lost, and what I did about it

The watcher also runs `installDefaultDebug`. With no phone attached that task FAILS, so **every
build prints "BUILD FAILED" even when the compile is clean** — and `phone.sh build` only ever
echoed that last line. I read it, believed the tree was red, and chased a phantom
`cannot find symbol: isStratified()` through the stale-intermediates remedy, the Gradle
configuration cache and the incremental-compile state. The real last compile task in the log
was clean; the failure was `:app:installDefaultDebug — No connected devices!`. The giveaway I
should have caught sooner: the error's reported line (Timeline.java:1646) did not match the
source (3252), because it was an old block in a 185MB log, not the current run.

Two tools came out of it:

- **`tools/phone.sh build`** now prints `COMPILE: clean|FAILED` on its own line, and when the
  build failed only at install it says so and points at the reconnect script.
- **`tools/wifi-adb.sh`** does the whole wireless reconnect in one command — drop stale entries,
  scan mDNS, try EVERY advertised endpoint (records outlive the listener, newest wins), restart
  the adb server and re-scan, print the ip:port. When it truly cannot reach the phone it names
  the two usual causes rather than shrugging, and it never suggests USB, because the connector
  is damaged. This is the half of INBOX 2026-09-15 that could be tooled; the other half —
  dropping the install from the watcher — is JoyRaptor's call and is recommended there.

### Still owed

Device proof of the six fixes in `4e8fe08b`, above all the tolerance-pill crash, which needs a
roll built and then that pill dragged. The phone stopped advertising mid-session (the Wireless
Debugging toggle turns itself off, a known field lesson) and could not be reached again.

Commits: 4e8fe08b - 5f41cc97 - fe01e03c

## 2026-09-15 — the Lab, verified on the phone rather than argued about

Three fixes from `4e8fe08b` were code-reviewed but never exercised on a device. All three were
run on the Note 20 against the A8 sandbox project, on the sheet with the 28 book pages.

| what was claimed | how it was actually checked | result |
|---|---|---|
| dragging the `tol` pill no longer dies on a recycled bitmap | built a 3-frame roll, went to Slice, dragged `tol` four times in both directions | no crash; `mCurrentFocus` still the Lab; value moved 0% → 12% → 2%, so the pill is live and not merely silent |
| "Add all" lands grid, preview, HUD and panel on the same frame | tapped Add all with the playhead sitting on cell 3 | all four moved to cell 0 together: pink badge on grid cell 1-of-28, preview art, HUD `c0`, ALIGNMENT header `cell 0`, film chip `#1 · c0` |
| the alignment panel retargets during playback | pressed play at 8fps and sampled the header three times | header read `cell 10`, then `cell 4`, and the preview art matched the header each time |

The third is the one JoyRaptor reported as "Starguy four, the crying one, starting to jiggle,
but that wasn't the one it showed I had selected." The panel now names the frame you are
looking at, frame by frame, with no rebuild.

Also confirmed by eye, since it was the other half of that report: the preview letterboxes with
a checkerboard at full width instead of stretching, and the film chips do the same. No
distortion at any width.

**One new defect, found by the verification itself and fixed.** Dragging `tol` from 0% to 12%
made the pill one digit wider, which re-flowed the row and pushed **Detect** onto the next line
— the primary action hopping out from under your finger while you are still setting up the
thing it acts on. Number pills now reserve their high-water width: they grow to fit a longer
number and never give the space back, with a digit of headroom reserved at build time so the
first crossing costs nothing.

**That fix is NOT compiled.** The watcher is off (it kept dropping the wireless debugging
connection) and this repo does not run Gradle by hand, so it has been parse-checked only —
see `tools/javacheck.sh`, added for exactly this gap. It needs a real build before it is
believed.
