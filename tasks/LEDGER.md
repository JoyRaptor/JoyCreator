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
| §3g text-box motion was UNVERIFIED end to end — the serializer was proved but nothing showed the PICKER actually reaching `TextOverlayItem` (§3g) | see §3g | Note 9, `bb2a9deb`, baseline **zero** `textAnim` keys so no leftover could masquerade as success; picked **RISE** because nothing on disk had held it. Three levels agreed: the dialog's MOTION row read **"Rise · 25% in / 0% out of this box"** (25% = `MAX_ZONE_PCT/2`, the seed, so the label reports the model not the tap); the text **vanished from the preview at t=0**, which is RISE at progress 0 — the renderer consuming the new fields; and after Close & Save the disk held `"textAnimPreset":"RISE"` + `"textAnimInPct":0.25`. Controls: a full-file `diff` shows the keys on **exactly one** overlay and none of the other five (not blanket defaults); the same diff shows `"Enter text"` → `"PICKERTEST"`, an independent signal that OK committed, so a missing key could not be blamed on the dialog failing; a no-edit reopen + re-save returned both keys unchanged (full round-trip); undo 17 → 19; and "Animate by" offered only `Block`, i.e. `36a8e3c`'s gate working. `textAnimGranularity`/`textAnimOutPct` correctly ABSENT (default + sparse-omit) |
| The §3g preset tiles were static poses, so a preset's ease could not be seen at all — three glyphs frozen at progress {0, 0.5, 1} (§3g) | `4a1ea41` | 16-frame burst on the real picker in `bb2a9deb`. **Per-tile temporal sd: Type 4.08, Fade 3.92, Rise 6.90, Ghost 7.21, Beam 6.84 — and NONE exactly 0.00.** NONE is the built-in control: it is deliberately left static, so a zero there proves the instrument reads the TILES and not the clock, the timeline or global screen noise. Before, all frames were byte-identical, i.e. 0.00 everywhere. Character is proved too: sampled ink shows TYPEWRITER quantised to 3 discrete values (44.96 / 48.10 / 51.24, one per glyph) while FADE sweeps continuously (41.96 → 51.22) — step vs ramp, exactly what a frozen tile could not express. Removing the 600ms hold (span = 2 × zone) roughly halved the frames where a pair is indistinguishable: Type/Fade 8/16 → 6/16, Rise/Beam 5/16 → 3/16, Type/Ghost 6/16 → 2/16. Freshness control: `javap -constants` shows `TILE_SPAN_MS = 1800` (the old 2400 would survive a stale compile) and the dex has `drawUnits` PRESENT with the deleted `drawSamples` ABSENT, `FadCamApplication` present as the partial-dex control. **Caveat, deliberately not swept under: the FREEZE-FRAME half is not fixed — see §3g outstanding item 1.** |

## 1b. THE EXPORT IS PROVED — 2026-07-30, and it found two real bugs on the way

**The oldest gap in §3g is closed: a file has been exported and its PIXELS checked.** Every prior
§3g proof was preview-only or model-only; the shared-renderer rewrite (`a247b5c`) rested on an
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

**~~BUG A~~ / ~~BUG A2~~ / ~~BUG B~~ — ALL THREE FIXED AND PROVED, 2026-07-30, `9b03bdc`.
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

**~~BUG C~~ — FIXED AND PROVED, 2026-07-30, `5a6cb4c`. See §1d below. The paragraph that follows
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

## 1d. BUG C IS FIXED — IMAGE OVERLAYS EXPORT, 2026-07-30, `5a6cb4c`

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
filler from `9b03bdc`, so **images render over the filler too**.

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
correctly (`9b03bdc`, `5a6cb4c`) — so the preview and the export disagree about a whole region of
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
which is the exact signature of the corrupt APK `d29e8bf` warned about. The APK was dex-scanned
before it was trusted; it was in fact fresh. **A spurious failure does not excuse skipping the
artifact check — it is precisely when to do it.**

## 1c. BUGS A, A2 AND B ARE FIXED AND MEASURED — 2026-07-30, `9b03bdc` (on `d29e8bf`)

`d29e8bf` was committed **uncompiled and untested**. It has now been built, corrected and proved.

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

**A DEFECT IN `d29e8bf` FOUND BY READING BEFORE BUILDING:** it left `buildClipItem`'s `@NonNull`
stranded above the new method's javadoc, so `ensureBlackFillerUri` carried **both** `@NonNull` and
`@Nullable` while `buildClipItem` carried none. Legal Java, so a green build would never have said
so. Moved back.

**BUG C is still open and the evidence is CONSISTENT with that**, which is itself a control: image
overlay `edff4a88` spans 6009–11009, now inside the rendered window, and it is **absent** from the
t=8 and t=10 frames while both sprites and both text boxes draw in the same frames. So the tail
filler renders overlays generally, and images specifically are still broken at
`CompositeExportOverlay`. Untouched by this change.

**Verification:** harness **298 passed, 0 failed** (unchanged from `b01ad53`), matte harness ALL
PASS, both from a clean compile. APK dex-scanned with `FadCamApplication` as the positive control —
**3 hits, where the corrupt APK `d29e8bf` warned about read 0** — alongside `ensureBlackFillerUri`
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

**3g-TEXTBOX. Text-box animation — ENGINE DONE AND AUTHORABLE; the round-trip is NOT yet proved.**
Commits `b7391e8` (engine) + the UI commit below. The other half of the user's 2026-07-29
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
- **"Animate by" offered ONLY `Block`**, which is `36a8e3c`'s supported-set gate working on the
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
- **MATRIX IS BUILT AND SHIPPED — `c85a7c5`, 2026-07-30.** First of the five, in the order the user
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
   user's decision. See §4.** The tiles animate (`4a1ea41`); the freeze-frame gap (~3.0–3.7 mean /
   ~4% Type-vs-Fade against 33.90 / 98.5% for the None-vs-Type control) is real and is ACCEPTED as
   motion-only. Kept struck-through rather than deleted because the numbers are the reason the
   decision was a decision. Do not reopen it by giving a tile a decorative cue the renderers do not
   produce — that invariant is the whole reason the tiles are drawn from the evaluator.
2. **The text-box half of the user's direction** — see the BUILD note above. Carets are parked
   waiting for it. **This is now the largest open piece of §3g**, and the two corrections above
   (`drawCaptionAnimHandles` is called every frame; the dead thing is the gate; and the whole block
   is bound to MASTER-CLIP geometry) mean it is a SECOND GEOMETRY, not a re-pointed target.
3. **The export path's frame cost is unmeasured.** The LETTER result above is the PREVIEW only.
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
