# HANDOFF 2026-07-26 — context/account switch

---

## 0z. PROGRESS LOG (newest first) — updated as work lands this session

### 2026-07-28 ~08:30 — PRIORITY-4 SWEEP CLEAN. AUTONOMOUS RUN ENDED (user returned).
Ran the one instrument the audit had never had pointed at it deliberately: the METHOD NOTE's
own blind spot — architecture stated as a PREMISE in prose rather than as a checklist item.
Searched `SPEC_*.md` for ownership/authority claims (`belongs to`, `single source of truth`,
`one authority`, `is the canonical`) and checked each against the code.

**Result: no unbuilt premise found.** Recorded so it is not re-swept:
- **CLEARED** `SPEC_PIP_AUDIO` §"Effective volume — one authority". The premise HOLDS: preview
  (`FaditorEditorActivity:16399`, `OverlayVideoPreviewView`'s host callback) and export
  (`ExportManager:1991`) both call `LayerPreviewController.effectiveOverlayVolume`. Same
  pattern documented on `TimerText` for the countdown string.
- **N/A** `SPEC_TEXT_ANIMATION` §"ONE AUTHORITY". That is a design constraint for work NOT YET
  BUILT, not a claim about existing code — nothing to drift from yet. Worth honouring when the
  feature is written; it explicitly cites the two shared-authority precedents above.

So the premise/checklist gap that hid the transcript-ownership bug appears to be a one-off
rather than a pattern across the spec set.

**RUN ENDED HERE.** The user returned, and what remains is not autonomous work: five queued
decisions (`NEXT_SESSION_PROMPT_20260727.md` items 3b, 3c, 4, 5) plus the one-pinch
stranded-latch test that `adb` cannot perform. Restart with `/loop` any time.

### 2026-07-28 ~08:05 — POLISH PASS: no code change, and one of MY OWN earlier notes was WRONG.
A deliberate no-change wake. The two polish targets I had queued turned out not to exist, and
checking that is the result.

**CORRECTION — I mis-stated this on 2026-07-28 ~03:35.** That entry says `BlendMode`'s javadoc
("non-NORMAL modes are treated as NORMAL by preview/export") is "now HALF FALSE" because export
implements the modes via `BlendModeGlEffect`. **That was wrong, and the javadoc is accurate.**
There are TWO unrelated blend-mode fields:
- `TimedItem.getBlendMode()` → the `BlendMode` ENUM. Its only non-serialization use anywhere is
  a capability check (`ExportManager:2227`, inside a boolean "does this project need the
  compositor" scan). **It never selects a blend, in preview or export.** So the javadoc is right.
- `Clip.getOverlayBlendMode()` → a **String**, PiP-only, and genuinely wired: `BlendModeGlEffect`
  maps it (`modeCode`, `:61`) to shader modes 1–4 for MULTIPLY/SCREEN/OVERLAY/ADD.
I conflated the two. Had I "corrected" the comment I would have introduced an error into a file
that was right. **The real observation** is the smell underneath: two parallel representations
of one concept, only one of them wired — exactly the "two sources of truth will drift" hazard
this codebase has already been bitten by (`ModelType` labels vs `strings.xml`). Worth
consolidating some day; not a bug today, and nothing can currently set the enum anyway (no UI
call site, only the deserializer).

**Second sweep run (priority 3, different phrase set)** — `temporarily|workaround|until we|
revisit|placeholder|not implemented|unimplemented|coming soon|stub` over `ui/faditor`. Nothing
new. Everything of substance is ALREADY TRACKED — recording them by name so they are not
re-investigated a third time:
- **CLEARED** `FaditorEditorActivity:5442/5445` "Move layer up/down (coming soon)" toasts — the
  move-drawer stubs, already in §0z (#6 scrubber work) and in the outstanding list.
- **CLEARED** `FaditorEditorActivity:5183` + `Clip.java:234` — the ping-pong chip is a
  deliberately disabled "coming soon" affordance, marked PARKED at the call site.
- **CLEARED** `AIToolExecutor:2424` — "per-word zoom keyframes coming soon" is a USER-FACING
  string that honestly states what the tool does today (clip-wide zoom). Not a hidden gap.
- Every other hit is an ordinary use of the word "placeholder" (MISSING-clip overlay, waveform
  placeholders, Glide placeholder colours).

**Honest note on where this run is:** the polish seam is now thin. What remains is either
user-gated (five decisions queued — see `NEXT_SESSION_PROMPT_20260727.md` items 3b, 3c, 4, 5,
plus the one-pinch stranded-latch test) or diminishing-return sweeps.

### 2026-07-28 ~07:40 — the stale player under an image clip is STOPPED too. Both halves done.
Closes the half `4e19c44` deliberately scoped out. `startImagePlayback` is documented as
"internal timer, no ExoPlayer", but nothing on the `advanceToSegment` path enforced it: the
OUTGOING clip's player kept decoding underneath the still at the OUTGOING clip's speed.

**It was audible, not just wasteful.** During the image, `playerPos` ran **1338 → 9714ms at
~2×** while `head` advanced at 1× — ~8.4s of a clip the user is not watching — and clip 0 is
**not muted** (`volumeLevel 0.26`), so its audio played under the still.

**Fix:** pause the player in the image branch (before `startImagePlayback`, so its
`updatePlayPauseButton(true)` still wins). The transport toggle already did this for the same
reason — the audio-tail branch even carries the note *"the image branch below restarted image
playback and never paused the audio players, so the music kept going"*. This path never got it.

**Sequencing worth noting:** this is only safe BECAUSE of `4e19c44`. Before the ticker counted
`imagePlaybackActive`, pausing here would have killed the ticker the instant it ran — the same
trap flagged last wake. The freeze fix had to land first; then this became a two-line change.

**Proved before/after on the same fixture:** before, `playerPos` 1338 → 9714 across the image;
after, **flat at 487** for the whole image clip, `head` advancing normally 748 → 5838, and
playback still reaching `segAtHead=4, head=13607` of a 14092ms timeline — stale decode gone,
nothing downstream broken.

### 2026-07-28 ~07:15 — IMAGE-CLIP PLAYBACK FREEZE: found and FIXED (`4e19c44`).
The `aeb0517e` "wedge" from the last wake was not a fixture quirk — it is a real bug in any
legacy-path project where a video clip is followed by an IMAGE clip. Playback stops dead
partway through the image and the transport goes unresponsive.

**Chain, from the trace.** `advanceToSegment`'s image branch (`:9588`) shows the image and
starts its own timer but — unlike the video branch immediately below it — **never touches the
player**. So the OUTGOING clip's ExoPlayer keeps running underneath the image at the OUTGOING
clip's speed. PHDIAG shows precisely that: `head` at **1.014×** while `playerPos` runs at
**1.98×** (clip 0 is a 2× clip). The stale player then exhausts its source → `Playback state:
ENDED` → `isPlaying()` false → the playhead ticker's re-post condition
(`isPlaying || audioTailActive || transitionPlaybackActive`) is false → **the ticker dies and
takes the image clip's own timer with it.** An image clip was only ever advancing by accident,
riding on the previous clip still playing underneath it.

**Fix:** add `imagePlaybackActive` to that condition — the same repair
`transitionPlaybackActive` got after the 2026-07-18 transition-freeze repro. A consistency fix,
not a new idea: `isPlayingAnything()` (~`:7325`) already counts `imagePlaybackActive` as
playing; the ticker was the one place that did not. **The obvious alternative is worse** —
pausing the player on entering an image clip would drop `isPlaying()` immediately and kill the
ticker on the spot.

**Proved before/after on the same fixture:** two independent runs froze at `head=4593` in
segment 1 (UI readout stuck at 00:04/00:14 across three samples, so a real stop and not a
logging artifact); after the change, two runs play through with the head advancing
**750 → 13264ms** across segments 1→2→3 of a 14092ms timeline.

**NOT fixed, deliberately, worth its own look:** the stale player should not be running under an
image clip at all — it decodes the previous clip at the wrong speed and may still be audible.
This commit only stops the freeze.

### 2026-07-28 ~06:45 — SEAM STALL SOLVED IN ONE LINE — but the line asserts something UNTRUE.
**NEEDS A USER DECISION.** The cause is now proven all the way down, and a one-line change makes
the stall vanish completely. It is NOT shipped, because it works by telling media3 something
FadCam cannot guarantee. The decision is a product one.

**THE CAUSE, exactly.** `DefaultMediaSourceFactory:589`:
`.setEnableInitialDiscontinuity(!mediaItem.clippingConfiguration.startsAtKeyFrame)`.
The engine builds every window with a `ClippingConfiguration` and never sets `startsAtKeyFrame`,
so it defaults FALSE → an initial discontinuity on EVERY clipped period → `ClippingMediaPeriod`
reports it at `startUs` → ExoPlayer resets the video renderer at every cut. That is the
`videoDisabled → videoEnabled → renderedFirstFrame` sequence and the twin decoder flushes.

**PROVEN by flipping it** (`setStartsAtKeyFrame(true)`, project `74e36000`, same fixture and
metric as all week):

| | renderer teardowns | decoder flushes | overall rate |
|---|---|---|---|
| before | 3 (one per seam), 250/271/330ms | 6 (two per seam) | 0.887× / 0.890× / 0.894× |
| after | **0** | **0** | **0.999× / 0.993×** |

All three seams still cross (engine log confirms `seam -> window 1/2/3`). Seam-to-seam wall time
drops by exactly one stall (3.318s → 3.062s), i.e. content duration is preserved and only the
stall is gone. **An ~11% whole-playthrough deficit goes to ~0.**

**WHY IT IS NOT SHIPPED.** media3's own doc: *"Sets whether the start point is **guaranteed to
be a key frame**. If false, the playback transition into the clip may not be seamless."* The
flag is an ASSERTION BY THE APP, not a request. FadCam's in-points are arbitrary user trim
positions, so asserting it is untrue in general — on a non-keyframe start the decoder can begin
mid-GOP and show wrong or broken frames until the next keyframe. My fixture played clean, but
its in-points may simply be keyframe-aligned, and **I did not verify pixels** — so "looked fine
on one 4-clip project" is not evidence it is safe.

**THE DECISION, for the user.** Three real options, all with costs:
1. **Snap trim in-points to keyframes.** Makes the assertion true and the stall disappears for
   free. Cost: trims lose sub-GOP precision (up to ~1–2s on these recordings) — an edit-semantics
   change users would feel.
2. **Pre-cut / re-encode each clip at its trim point** so starts really are keyframes. Exact
   trims AND no stall. Cost: a transcode per clip on edit.
3. **Accept the ~11% deficit** and leave playback as it is.
A fourth, cheaper hybrid worth considering: set `startsAtKeyFrame(true)` ONLY for windows whose
in-point is already keyframe-aligned (detectable at playlist-build time), leaving the rest
as-is — most seams get the win, none of them lie.

**Do not ship option 1 or the hybrid without a pixel check at the first frames after a seam.**

### 2026-07-28 ~06:15 — SEAM MECHANISM FOUND (renderer teardown per cut). One fix tried, REJECTED.
The seam item now has a mechanism, not just a location — and the obvious fix for it was tested
and does not work. Nothing shipped; the tree carries only a comment warning off the dead end.

**THE MECHANISM.** `EventLogger` is already attached in debug builds
(`FaditorPlayerManager:941`, `setEventLoggingEnabled(BuildConfig.DEBUG)`), so this needed no new
instrument. On project `74e36000` every seam logs, in order:
```
videoDisabled  window=0        rendererReady video,false
videoEnabled   window=1        downstreamFormat window=1   renderedFirstFrame window=1
```
**The video renderer is torn down and rebuilt at every cut**, and `videoDisabled →
renderedFirstFrame` was **250 / 271 / 330 ms** — which is the entire per-seam loss measured all
week. Decisive detail: every window in that project is the SAME source file with a
**byte-identical format** (`video/hevc hvc1.1.6.L150.B0 1080x1920 bitrate=7697274`), so nothing
about the format forces this. The ACodec log agrees — the decoder is flushed twice per seam
(`OMX.qcom.video.decoder.hevc signalFlush`) with no codec re-creation.

This closes the chain: the cost is in the player (proved), it scales with the entered clip
(its keyframe/bitrate cost to re-fill after a flush), and it has nothing to do with source
continuity (proved) — because **every** clipped playlist item pays a renderer restart.

**THE FIX I TRIED AND REJECTED — playlist preloading.** media3 1.8.0 has
`ExoPlayer.setPreloadConfiguration`, whose `DEFAULT` **disables** playlist preloading, and this
engine never set it. So the class header's claim that "ExoPlayer pre-buffers the next item
natively" is true of the SOURCE but not of the RENDERER. Setting a 2s target preload looked
like the answer. It is not:

| | seam1 | seam2 | seam3 | mean |
|---|---|---|---|---|
| baseline | 250ms | 271ms | 330ms | 284ms |
| preload 2s | 219ms | 236ms | 398ms | 284ms |

Identical mean, and — the part that actually settles it — **the event sequence was unchanged**:
still `videoDisabled → videoEnabled → downstreamFormat → renderedFirstFrame`, with the next
window's format still arriving only AFTER the transition. No preload activity in logcat at all.
**Reverted**, because it costs an extra buffered period for no measured gain. A comment at the
call site records this so it is not re-attempted blind.

**WHERE TO GO NEXT (not yet tried).** The target is the renderer restart itself, not the
buffer. Worth checking, in rough order of promise: whether `ClippingConfiguration` per item is
what forces a fresh period+renderer at each cut (a single `ClippingMediaSource`-free playlist,
or pre-cut media, would test it); whether `DefaultPreloadManager` — a different API from the
one I tried — warms renderers rather than just sources; and whether the double `signalFlush`
per seam is one flush too many (each pair is ~85ms apart, both BEFORE the transition callback).

**Useful metric for whoever continues:** `videoDisabled → renderedFirstFrame` from EventLogger
is a direct, ms-accurate per-seam cost that needs NO instrument change — much better than the
50ms PHDIAG rebuild used earlier this week. `scratchpad/seamgap.sh` extracts it from a logcat
capture.

### 2026-07-28 ~05:40 — CONFOUND RESOLVED, AND IT RESOLVES AGAINST MY OWN EARLIER READING.
Seam kind does NOT drive the cost. **Retract "a contiguous seam is worse than a discontinuous
one" as a causal claim** — it was the confound, and the data that settles it was already in hand.

**How it was settled without a new fixture.** If seam KIND drove the cost, seams of the SAME
kind should cost alike. In `cebc19e0` run1 the CROSS-SOURCE seams alone cost
**191 / 1000 / 151 / 925 / 524 / 165 ms** — a ~6.6× spread inside one kind, far larger than any
gap between kinds. The 276–348ms measured at the contiguous seam sits comfortably inside that
range. So kind explains nothing; per-seam cost is set by something else, and the obvious
candidate given last wake's result (the PLAYER loses the time, not the UI thread) is the decode
ramp-up of whichever clip is being entered — its bitrate, resolution, keyframe spacing.

**WHAT STILL STANDS, unchanged:** the originally recorded cause — *"a decoder seek into a
discontinuous source position"* — remains REFUTED, and for a reason the confound does not
touch: a seam needing **no seek at all** costs 276–348ms, so the seek cannot be the mechanism.
Also unchanged: the cost is real (123–350ms typical), it is in the player (`head` and
`playerPos` lose time together), and it accounts for ~90% of the whole-playthrough deficit.

**So the corrected statement of item 3 is:** *crossing a window boundary costs 120–350ms
(sometimes ~1s) of real playback rate, the loss is inside ExoPlayer rather than the UI thread,
and it scales with the clip being entered rather than with whether the source position was
continuous.* That is the sentence a fix should be designed against.

**The device attempt this wake FAILED and is worth knowing about.** `aeb0517e` (the fixture
with a `gap=+0` seam at position 2→3) will not play through: it stops ~5s in with
`playing=false pwr=true` — an ExoPlayer BUFFERING stall at a window transition that never
recovers — and subsequent play taps do nothing. It runs the LEGACY path (2 transitions), whose
per-clip cold `setMediaItem()+prepare()` is exactly what `MasterPlaybackEngine` was built to
replace, so a hard stall there is plausible rather than surprising. **This may be a real bug
worth its own look** (a legacy-path project that wedges mid-playback), but it is NOT the seam
item and was not chased. `bdd51919` has the same shape and is the obvious retry fixture.

**Navigation is solved and was NOT the problem this time** — `uiautomator` found the row and
`btn_play_pause` exactly, and the playhead was confirmed at 00:00 before the run. Note the list
reorders constantly: opening a project re-saves it, so it jumps to row 1 (`aeb0517e` was row 1
this wake, not row 6).

### 2026-07-28 ~05:10 — SEAM COST IS THE PLAYER, NOT THE UI THREAD. My own hypothesis refuted.
The seam diagnosis now has three hypotheses tested and two dead. Still nothing optimised — but
the next person no longer has to guess where to look.

**The hypothesis I formed from the code, and then killed.** `MasterPlaybackEngine`'s header
says the gapless path pre-buffers the next item so a cut is a *warm* `onMediaItemTransition`,
and its 2026-07-02 probe found **ZERO frozen frames** at any seam. That plus `onGaplessSeam`
doing real main-thread work per seam (`setVolume`, `setPlaybackSpeed`,
`updatePreviewTransforms`, caption/overlay rebind) suggested the seam cost was the MAIN THREAD
being busy, delaying the 50ms playhead ticker while video kept decoding normally.

**It is not.** `PHDIAG` logs two independent clocks — `head` (advanced by the main-thread
ticker) and `playerPos` (ExoPlayer's own). If the ticker were being starved, `playerPos` would
still read ~1.00×. Across the three gapless runs, measured in the window after each seam, the
two lose time IDENTICALLY:

| | seam→1 | seam→2 | seam→3 |
|---|---|---|---|
| run1 | head 0.91× / player 0.92× | 0.93× / 0.93× | 0.98× / 0.98× |
| run2 | 0.88× / 0.88× | 0.86× / 0.88× | 0.96× / 0.96× |
| run3 | 0.88× / 0.89× | 0.85× / 0.85× | 0.87× / 0.87× |

Control (same measurement in a seam-free window): head 1.00× / player 1.00×.
**So ExoPlayer itself runs at ~0.85–0.98× for a few hundred ms after a media-item transition.**
The fix belongs in playback (buffering / decoder ramp-up at a window change), NOT in trimming
the seam handler's UI work. Note this is consistent with "zero frozen frames": the video never
freezes, it runs slightly slow while the new window spins up.

**MEASUREMENT TRAP worth keeping:** `playerPos` is WINDOW-LOCAL and resets at every seam. A
window that starts at the seam measures the reset, not playback — it produced nonsense like
`-4.69×` on the first pass. Start the window at the first sample where `playerPos` has begun
advancing monotonically inside the new window.

**CONFOUND STILL OPEN — I failed to close it, twice. Do not record it as clean.** In both
fixtures measured so far the contiguous `gap=+0ms` seam sits at the same ordinal position
(1→2), so "seam kind" and "which clip is entered" are entangled — and now that the cause looks
like per-window decoder ramp-up, the "position" explanation is the more likely one (a clip's
own bitrate/keyframe spacing would set its ramp cost). `aeb0517e` has a `gap=+0` seam at
position 2→3 and separates them. This wake I opened it correctly (confirmed: `gapless=false`,
as its 2 transitions require) but never got a playthrough — the transport taps went out of
phase across runs and the "seek to start" tap landed in the lane area on that layout.

**NAVIGATION, solved — use this instead of guessed coordinates.** Blind taps have now cost
several attempts. `uiautomator` gives exact positions:
```
adb shell uiautomator dump /sdcard/ui.xml     # quote the path: Git-Bash mangles /sdcard/...
adb shell "cat /sdcard/ui.xml"                # then match resource-id + bounds
```
`btn_play_pause` was at (540,1190) on that project — the transport moves with the preview
aspect, so it MUST be looked up per project, not carried over.

### 2026-07-28 ~04:40 — SEAM COST CONFIRMED ON THE GAPLESS PATH TOO; scope caveat CLOSED.
Closed the caveat the previous entry opened. The refutation is not a legacy-path artifact — it
holds on the gapless engine, and more strongly.

**Why the earlier fixture was legacy, answered:** `FaditorPlayerManager` — *"transition projects
are gapless-ineligible"*. `cebc19e0` has 3 transitions, hence `gapless=false`. That also makes
transitions the likely explanation for that run's backward playhead jumps: the gapless fixture
here shows **0 backward steps** in all three runs.

**Gapless fixture `74e36000` "bisect B 1x clip0"** (4 clips, 0 transitions, `gapless=true`
confirmed in the trace), three playthroughs at 50ms:

| seam | kind | run1 | run2 | run3 |
|---|---|---|---|---|
| 1→2 | **same source, gap=+0ms — NO seek** | **348ms** | **299ms** | **276ms** |
| 0→1 | cross source | 180ms | 213ms | 199ms |
| 2→3 | cross source | 142ms | 148ms | 123ms |

Overall rate **0.887× / 0.890× / 0.894×** — statistically indistinguishable from legacy's
0.884× / 0.888×. **Gapless does not make seams cheaper.**

**Five runs across two projects and both engines now agree:** the seam needing NO seek is
consistently the most expensive, roughly double a cross-source seam. The recorded cause
("a decoder seek into a discontinuous source position") is refuted on both paths.

**Where the time goes:** summing the three seam windows accounts for **91% / 93% / 88%** of the
whole playthrough's deficit (e.g. run1: 670ms of seam loss out of 735ms total). Outside the seam
windows playback runs ~0.98–1.00×. So this is not diffuse slowness — it is concentrated at the
cuts, which is what makes it worth fixing.

**CONTROL CAVEAT, stated rather than hidden:** these fixtures are ~6.5s, so the dedicated
"sample a 1s window far from any seam" control has no room to run (it returned an empty set).
The metric was validated by that control on the longer legacy runs, and here by the accounting
identity above (seam losses ≈ total loss ⟹ non-seam playback ≈ 1.0×).

**CONFOUND, not yet eliminated — do this before building a fix.** In BOTH fixtures measured the
contiguous seam sits at the same ordinal position (1→2), so "seam kind" and "seam position" are
entangled; the effect could belong to whichever clip is entered second rather than to the kind.
`aeb0517e` / `bdd51919` have their `gap=+0` seam at position **2→3** and would separate the two.
I tried to open `aeb0517e` and mis-tapped (its `project.json` was untouched and the trace read
`gapless=true`, which it cannot be with 2 transitions) — so this is UNMEASURED, not measured-
and-clean. It is one playthrough of work.

### 2026-07-28 ~04:10 — SEAM STALL MEASURED ON THE NOTE 9; ITS RECORDED CAUSE IS REFUTED.
Item 3 said "measured, not yet optimised" with the cause given as *"a decoder seek into a
DISCONTINUOUS source position"*. Before optimising I re-measured — and the stated cause does
not survive. **Nothing was optimised this wake; the next attempt should start from the
corrected cause, not the old one.**

**Method (reusable).** `PHDIAG` throttles to every 10th tick (500ms) — too coarse for a
150–250ms event. Temporarily changed `% 10` to `% 1` for 50ms resolution (**reverted; not
committed**), then played project `cebc19e0` "P0 control2 plain" (9 clips, 27.6s, 8 seams of
MIXED kind) end to end, twice. Rate = Δhead/Δwall-clock from the logcat stamps; a seam is a
`segAtHead` transition. Scripts in the session scratchpad (`seam_analyse.py`).

**The metric is honest** — control: the identical 1-second window sampled away from any seam
loses **1–8ms** (≈1.00×) at nine spots in each run. Without that, a metric that always reports
a deficit would have looked like a finding.

**Reproduced across two runs** (run1 → run2):
- whole playthrough **0.884× → 0.888×**: a 27.6s project takes ~31s, ~3.5s lost. Reproducible.
- clean seams cost **123–327ms** each — the reported 150–250ms band is about right.

**THE REFUTATION.** The cause cannot be the discontinuous seek, because a **CONTIGUOUS**
same-source seam is the *worst* one measured:

| seam | source gap | run1 | run2 |
|---|---|---|---|
| 1→2 same source | **+0ms (no seek needed)** | **287ms** | **327ms** |
| 0→1 same source | +404ms (seek) | 155ms | 150ms |

Two data points each, both directions consistent. A seam that requires **no seek at all** costs
roughly twice one that does. So the cost lives in the seam/window-transition machinery itself,
not in the source discontinuity. Optimising "make the seek cheaper" would have chased the wrong
thing.

**SCOPE CAVEAT — read before generalising.** This project played with `gapless=false` (the
LEGACY path). The Note 20 report that produced item 3 was on an 11-clip project which may well
be `gapless=true` (the 4-clip fixture `129d8643` reports gapless=true). The refutation above is
therefore proven for the legacy path; whether the gapless path shares the cause is UNVERIFIED.
Re-run this method on a gapless project before acting.

**UNEXPLAINED, reproducible, logged not chased:** the playhead steps BACKWARD twice per
playthrough — −600ms and −5497ms, at the same points in both runs — and `segAtHead` traverses
0,1,2,3,4,3,5,6,7,2,8 rather than in order. No clip has `loopMode` set, so that is not it; the
project does have **3 transitions**, which is the obvious next suspect (`PHDIAG` has a `trans=`
field to check). Could equally be clips whose timeline order differs from array order, which
would make the seg sequence expected and only the two backward head steps anomalous. Not
investigated further — flagged so it is not mistaken for measurement noise, because it
reproduces exactly.

**Incidental v12 confirmation:** opening `cebc19e0` re-saved it **58,999 → 44,800 bytes
(−24.1%)** — a bigger win than the 15.1% on the 4-clip fixture, as expected for a project where
one transcript is shared by 4 clips.

### 2026-07-28 ~03:35 — STRANDED-LATCH HUNT: negative result that NARROWS it to the pinch path.
Tried to close outstanding item 1 by driving the editor over adb instead of waiting for the
user. **Did not reproduce it** — but the run is worth keeping, because it eliminates most of
the suspect list and leaves one suspect standing.

**How to drive this probe (reusable).** The heal only fires from `updatePlayheadPosition()`,
i.e. ONLY WHILE PLAYING — with playback stopped no gesture can ever produce the warning. Better
still, don't wait for the heal at all: `PHDIAG` prints `drag=` every tick, which IS
`userDragging`, so the latch can be read directly. Sequence per gesture: tap the ruler near 0,
tap play, gesture, then read the newest `PHDIAG` line.

**Which gestures can even latch it.** `userDragging` latches only on
`onPlayheadSeeked(..., isDragging=true)`, which comes from `updatePlayheadFromX` on the
ROW-SCRUB path. Confirmed by a mid-gesture control (sample while the finger is still down):
- audio band (y≈1622) — `mid=drag=true` ✅ the probe genuinely exercises the latch
- ruler (y≈1408), filmstrip (y≈1798), empty lane (y≈1476) — `mid=drag=false`; these never latch,
  so a "clean" result from them proves NOTHING. The first six gestures I ran were all of this
  kind and had to be thrown away.

**Result:** on the audio band the latch clears correctly on release — `drag=false` in 3/3 runs,
no heal in any of them. Same for ruler/filmstrip/empty-lane scrubs, fling releases, cross-lane
releases and double-taps.

**TWO SELF-MANUFACTURED SIGNALS I nearly recorded as findings** (both caught, both worth
knowing about before re-running this):
1. `after=drag=true` on a 2500ms swipe looked like a live reproduction. It was not: the fixture
   is only 7s long, playback ENDED mid-swipe, `PHDIAG` stopped printing, and `tail -1` returned
   a stale line from DURING the gesture. Any probe here must either use a fixture longer than
   the gesture or require the sampled line to post-date the release.
2. The freshness gate I then added was itself blind — `adb shell date "+%m-%d %H:%M:%S"`
   word-splits, so the comparison ran against just `07-28` and every line passed as fresh. The
   three results above were re-checked by reading the clock times by hand.

**WHAT THIS LEAVES.** Every adb-injectable SINGLE-TOUCH path releases cleanly. The prime
remaining suspect is the one `input` cannot synthesize: the `if (isScaling) return true`
ACTION_UP on a PINCH, which is also exactly the shape of the FIRST stranding (the post-pinch
handback pan). `adb input` has no multitouch and `sendevent` is denied on this device, so this
needs either the user's fingers or a different injection route. **Recommend: stop spending
autonomous cycles on it and ask the user for one pinch-zoom-then-release while playing** —
`c158456` will name the branch the moment it happens. Keep `PHDIAG` + the snapshot until then.

### 2026-07-28 ~03:05 — DATA-LOSS BUG FOUND AND FIXED (`d41e130`): one undo could eat a session.
Found by MEASURING, not reading, while checking whether the v12 pool shrank the 22MB sidecar:
after eight edits across two app sessions, `project.json` had been rewritten twice and
`undo_history.json` still carried the PREVIOUS DAY's mtime. Cause: the debounced
`autoSaveRunnable` called `projectStorage.saveAsync(project)` directly instead of going
through `saveProjectNow`, so it advanced project.json and never wrote the sidecar — and an
edit that only schedules an autosave (the rotate button) never wrote undo history at all.
`UNDO_HISTORY_SAVE_THROTTLE_MS`'s own comment already claimed the sidecar is written on the
ordinary edit path; that path had quietly stopped honouring it.

**The consequence was REPRODUCED, not argued.** project.json sits at edit N while the sidecar
describes edit M ≪ N. Any death skipping `onPause` (crash, LMK, `am force-stop`) leaves that
pair on disk, and on reload ONE undo press restores edit M's PRE-state:
`v12 rot=180` → **`v11 rot=90`, 441 fields reverted to the previous day**, under a row labelled
`"Reorder clip 3 → 2"` — an edit from that day, so the label actively misdescribes it.
Note rotation ALONE could not discriminate (a correct one-step undo also lands on 90), so the
test compared whole documents against both candidate outcomes and asserted the candidates
differ from each other. This app has an OOM-crash history, so the window is real.

**Fixed + proved with the old build as the control:** the identical action (select clip,
rotate, wait 3s) left the sidecar at 07-27 15:17 TWICE before the fix; after it, 622,949 →
658,527 bytes in the same second as `Project auto-saved`. Re-running crash-then-undo:
`Undone (snapshot): Rotate 90° → 180°` and a whole-document comparison shows exactly one step
reverted, with a paired control confirming a wrong rotation would have been caught.

**Second fix in the same commit — a gap MY OWN v12 pool introduced.** A snapshot restore
deserializes a fresh `NamedTranscript` per clip, so the restored project holds forks, not one
shared instance; the pool writer keys on identity, so pooling stopped paying the moment a
snapshot was restored and the next save rewrote the old duplicated shape (measured 31,945 →
37,684 bytes after ONE cross-session undo, snapshots growing back with it). `fromJson` now
re-shares exactly as `load()` does, beside the `TranscriptDedup` pass already there for the
same reason. Device log: `re-shared transcripts in snapshot — collapsed=1 recovered=0
shared=2` (recovered=0 ⇒ identical forks collapsed, no words moved); file now holds at
31,945 → 31,944 across the undo (1 byte = `180`→`90`).

**NEEDS A USER DECISION — the window is narrowed, NOT closed.** A death inside the 15s
throttle still desynchronises the pair. Closing it means detecting staleness at load and
refusing to restore an incoherent undo stack. The detection is straightforward (the sidecar
has no stamp today; write the project's `lastModified` into it and compare on load — the
sidecar is currently a bare JSON list, so this needs a header shape with list = legacy). What
is NOT mine to choose is the behaviour: **after a crash, is it better to have NO undo history,
or one that may silently over-revert?** There is a precedent for discarding — the schema-
downgrade drill already refuses to restore a read-only project's undo history on the grounds
that "an undo stack whose snapshots can never be saved is incoherent" — but this trades away
real functionality after exactly the event where a user most wants undo. Ask before building.

**Also noticed, not acted on:** `BlendMode`'s javadoc says non-NORMAL modes "are treated as
NORMAL by preview/export for now". That is now HALF FALSE — export implements MULTIPLY/SCREEN/
OVERLAY/ADD via `BlendModeGlEffect` (M-EXPORT-2 landed); preview still does not
(`LayerImageOverlayView:141`). Inert either way: `setBlendMode` has NO UI call site, only the
deserializer, so a user cannot currently produce a non-NORMAL value. Stale-comment cleanup,
not a bug.

### 2026-07-28 ~02:30 — OUTSTANDING ITEM 2 DONE: transcripts are stored ONCE per file (`88cd1b7`).
The undo-snapshot cost item from `NEXT_SESSION_PROMPT_20260727.md` §2 is implemented and
proved. Schema **v12** adds a project-level `transcriptPool` + per-clip `transcriptRefs`;
each distinct transcript is written once instead of once per clip that carries it.

**The design question was settled by measurement, not by argument.** A JVM bench at project
scale (`SplitBench`, session scratchpad) split the serialize cost: **building the Gson tree is
44%, stringifying is 56%.** So collapsing duplicates as a post-pass over the finished tree
recovers only about half of what is there (268→140ms) where interning during serialization
gets 268→12ms. The writer therefore interns from inside `serializeClipObject`; the reader is
deliberately NOT symmetric — `TranscriptPoolCodec.expand()` rewrites a pooled tree back to the
inline shape before deserialization starts, so the long clip/audio deserializers keep seeing
the single input shape they have always seen. Zero change to them.

Two things that would have silently eaten data, both handled + tested: interning is by id but
**guarded by instance identity** (same-id forks that `TranscriptSharing` has not collapsed yet
get their own suffixed pool key — interning by id alone would give every clip the first fork's
words), and refs preserve list **order and length** exactly because `activeTranscript` is an
INDEX into that list. Pooling turns on only when it pays, so an un-duplicated project keeps
the inline shape, its older stamp, and byte-identical JSON.

**PROVED three ways, every check paired with a positive control:** `TranscriptPoolCodecTest`
29/29 (harness); Note 9 project `129d8643` 14/14 — after a 4×90° rotate (a real edit that
returns the model to its exact starting value) the saved file went v11→v12, 37,779→32,060
bytes, and expanding it and comparing ABSOLUTELY against the pre-change file shows the ONLY
field that differs anywhere is `.label`, which is `TranscriptLabelMigration`'s documented
`"Fast"`→`"Fast timing"` rename (`15706a1`), unrelated to pooling; and the read path proved by
the APP rather than by my host reimplementation — force-stop, cold reopen (captions render),
edit, save again, 7/7 with the pool byte-identical across pooled→load→pooled.

**HONEST LIMIT:** the effect on the user's own 5.3M-char project is **projected, not
measured** — that project is on the Note 20. The 15.1% measured here is small only because
this fixture's transcripts are 37 words; the win scales with the transcript share of the file,
which on the real project is 99.2% with 71.8% duplication.

**STILL OPEN from that same item:** `undo_history.json` is 22MB (several full snapshots) and
was NOT looked at — it is now written in the pooled form, so it should shrink on its own, but
nobody has confirmed that or asked whether 50 retained snapshots is the right number.
**Note on the `activeTranscript` index→id migration** (the landmine flagged in `ed0d7ec`): it
is NOT made worse by this change — pooling preserves list order exactly, which the harness
asserts — but it is also NOT fixed, and it remains the right thing to do.

### 2026-07-27 ~17:00 — PLAYHEAD FREEZE ROOT-CAUSED + FIXED; F-COLOR/F-MINIMAP; audit sweep.
**B-PLAYFREEZE IS CONFIRMED FIXED ON DEVICE (2026-07-27 17:48)** — 90 post-fix PHDIAG samples
in the user's real project, 0 frozen, 0 `drag=true`. See SPEC §13 for the full result.

**THE ONE THING TO DO NEXT:** the confirming run showed the self-heal FIRING once with playback
continuing through it — so a SECOND stranding path exists that the direct fix did not cover.
Not user-visible (the net catches it), but close it so the net stays a net.
**The instrument is already in and waiting (`c158456`)** — additive only, no control flow on it.
`EditorTimelineView` snapshots the branch-selecting flags at every ACTION_UP/CANCEL and the heal
warning prints them, so the NEXT stranding names its own culprit:
`userDragging was stranded … | lastUp: action=UP reorder=… scaling=… postPinchPan=… activeDrag=…`
So: have the user drive the editor normally, grep for `lastUp:`, read which branch was live,
fix THAT return, done. Suspects if you want a prior: the `if (isScaling) return true` UP, the
audio-band tap/double-tap returns, the slide double-tap return.
NOTE `c158456` is **compile-verified only** — the Note 20 was unplugged, so the watcher's
`installDefaultDebug` failed with "No connected devices!" while javac ran clean. It installs on
the next connect; confirm the APK timestamp before trusting it.
KEEP `PHDIAG` (`61f184d`) until that second path is closed; remove both together after.

**B-PLAYFREEZE fixed (`b0400b8`)** — full write-up in SPEC §13. Short version: `userDragging`
was a stranded latch; the post-pinch handback pan latched it and its ACTION_UP returned before
the shared block that clears it. Measured, not guessed — and the instrument proved WRONG the
two hypotheses a read-only pass had produced. Fix is (1) that branch now ends its own drag and
(2) the class is closed via `isGestureActive()` + a self-heal, so the other ~12 bypassing
returns can't strand it either. STILL OPEN from that same report and NOT looked at: the long
pause at inter-clip gaps, and playback perf with many layers.

**TRAP worth remembering:** `adb logcat` with no `-T` replays the whole ring buffer, so a
freshly-armed monitor re-reports OLD lines. It briefly looked like the fix had failed; the
timestamps were all pre-install. Use `logcat -T 1` when watching for NEW events.

**F-COLOR + F-MINIMAP done (`c174205`)**, palette harness 30/30 — see SPEC §12. Appearance is
compile-verified only and **wants the user's eyes**, especially whether 12 mini-map lines at
2dp pitch is the right density on the Note 20.

**Audit sweep (`29c7937`)**: 1.5's mechanical half done (AI clip-reorder is now rollbackable);
**1.2 and 1.3 re-verified as ALREADY CLOSED** — the audit text was stale, now marked with
evidence so a later session doesn't re-scope them. 1.5's three-way decision still needs the
user, and a NEW related risk is logged: an AI reorder silently drops any clip missing from
`newOrder`.

**Note on the build watcher:** while the Note 20 is the only attached device it auto-installs
there on every save, which KILLS the running app. That cost a live repro once. Don't save app
source while the user is mid-test on that phone.

### 2026-07-27 ~16:10 — AUDIT 1.6 FIXED AND PROVEN (`fddf7a8`). Next: Note 20 playback sync/perf.
**Undo after an app restart now actually reverts the edit.** Root cause was that
`snapshotBefore` held the state AFTER the edit (recordAction captured at record time; most
sites mutate first). In-session undo hid it entirely because it prefers `action.undo()`.

Fixed with a **deferred rolling baseline**, NOT by the "restore the predecessor" rule the
audit refutes and NOT by reordering the 18 record-before-mutate sites: the manager keeps the
state as of the last edit and hands it to each new entry, recapturing on the looper tick
AFTER the edit handler unwinds. That tick sees the final post-edit state regardless of the
site's ordering. `resetBaseline()` is seeded at saved-project load, new-project load and the
AI swap, and is deliberately SYNCHRONOUS — deferring it would let an edit record against a
stale baseline, which after an AI swap is the pre-AI state (undo would silently eat the AI
step). Both alongside-hazards fixed: undo/redo peek-apply-then-pop and return `false` when
nothing was restored; the snapshot path invalidates BOTH stacks.

PROVED, both halves:
- `tools/jvm-harness/UndoManagerTest.java` 34/34, every check paired with a positive control.
- Note 9 device control on project `302da9ac`, using **Rotate** (a record-BEFORE-mutate site,
  i.e. the case a naive fix over-reverts): sidecar entry holds the PRE value (0, not 90); and
  after force-stop + reopen + undo the saved `project.json` is **byte-identical to the
  pre-edit file** apart from `lastModified`, while still differing from the post-edit file in
  exactly `rotationDegrees` (so the comparison isn't vacuous).

Cost is now measured, not guessed: `BASELINE` log lines carry duration + snapshot size
(9ms / 6611 chars on that small fixture; size matches `project.json` exactly). **Watch these
lines on the Note 20's large projects** — one project serialize was added to project-open.

**NEXT, user-reported 2026-07-27 ~16:05, to be done ON THE NOTE 20 with the user present**
(they will plug it in): preview/timeline desync during playback in a large project. Symptoms
as reported: with the main video playing and ~4-5 text layers stacked, approaching that stack
the **playhead stops moving and the timeline view stalls** while video keeps playing and the
text overlays do NOT composite in as they enter; manually scrubbing into a text's time range
DOES render it. Intermittent — sometimes audio+video+overlays+playhead move, but rarely all
together. Separately: **gaps between clips have an unnaturally long pause**, and the user
suspects the visual inter-clip gap is being given real time to resync. They also want
playback perf checked with many text layers, visualizers and PiP layers. NOTE: the display-
only inter-clip inset from the uniform-axis change (`d697ca9`) is a rendering inset and should
NOT add playback time — verify that first, and prove whatever is claimed.

### CONTEXT-WINDOW HANDOFF (2026-07-27 ~14:50). Batch of device-driven fixes landed; undo 1.6 next.
This session's scrubber/timeline fixes, all installed on the Note 9, all AWAITING the user's
final feel-confirm: uniform time axis (`d697ca9`, fixed the warp + below-length at the root),
edge-tracking (`dc39134`), overshoot step-past (`3d58688`), diagonal-drag loosen (`c0d4241`),
terminology lanes/layers (`1ab0d88`), and "End here" live-playhead + scroll-off clamp (`this batch`).
Field-feedback ledger = SPEC §12 (honest DONE/OPEN list). **NEXT SESSION TOP PRIORITY = AUDIT 1.6**
(undo after app restart silently does nothing — the everyday data-trust bug). Deliberately NOT
rushed in a closing window: it is core-path surgery, every obvious fix is wrong (audit 1.6 lists
why: 18 record-before-mutate sites, the AI checkpoint, absent predecessors), and it needs a DEVICE
positive control. Do it first, fresh, per audit 1.6's "recommended direction" + its two
alongside-hazards (undo() silent-success on null snapshot at `UndoManager:318-330`; orphaned-graph
invalidation on the plain snapshot path). Then SPEC §12 open items (scrubber completion: other
payloads, relayer+glide, move drawer; polish: color-by-type, badge, center-on-add, pan-delay,
caption-drawer, mini-map).

### WARP BUG DIAGNOSED (proven) → TIME AXIS MADE UNIFORM (`d697ca9`), awaiting user test.
User reported a BOUNDED text block changing length while scrubbed. Instrumented `onPreview` with a
`SCRUBWARP` Log.d; the device log PROVED the model is perfect — `dur` held constant at 2488ms the
whole scrub, `lightFound=true` (no resync fallback). So the warp was RENDER-side: the block width
= `timeToX(end)-timeToX(start)` and `timeToX` was NON-LINEAR. Root cause (confirmed in
`computeRects`): a MIN-WIDTH clamp on short clips (`Math.max(minSegmentPx, …)`) + a positioning GAP
between clips (`+ segmentGapPx`) — both add pixels that aren't time. User's call: make time UNIFORM
("that's what zooming fixes"). `d697ca9`: `computeRects` now lays segments strictly proportional
with NO gap/clamp (video+audio) → `segRects` abut → `timeToX`/`xToTime` linear = uniform scale.
Kills the overlay warp AND makes the playhead↔time mapping EXACT (prerequisite for precise
Start/End-here). Short clips are now genuinely narrow (zoom to work with them — accepted trade);
clips separated by a DISPLAY-ONLY inset in `drawSegment` (never in the time mapping). SCRUBWARP
logging stripped. Installed Note 9 APK 13:04:35. **AWAITING USER TEST:** uniform look, warp gone,
clip separation reads OK, playhead lines up. This is a CORE rendering change (segRects feed
playhead/trim/thumbnails/overlays/hit-test/minimap) — watch for any regression there.
**PENDING NEXT (separate, kept isolated):** "End here" does nothing (Start works) — real bug;
and clamp the scrub so an object can't be pushed past the timeline end (scroll-off-forever).
Full field-feedback queue = SPEC §12.

### #6 v1 VALIDATED ON DEVICE + edge-tracking added (`dc39134`). PLACEMENT: the MOVE DRAWER is the real home.
User tested the object-menu scrubber: **"the moving itself was very smooth"** — so v1 WORKS
(menu opened, no crash, buttery). Two things from the feedback:
1. **Edge-tracking (FIXED `dc39134`):** scrubbing an object toward the viewport edge ran it
   off-screen. Added `EditorTimelineView.followScrubTimeMs(ms)` — pans the timeline to keep the
   scrubbed point inside a centered dead-zone (25% margin each side), so tracking starts BEFORE
   the edge with lookahead. Called from onPreview. Installed Note 9 APK 09:38:23. Pending feel.
2. **PLACEMENT was wrong — the real home is the MOVE DRAWER (the "move" tool, `open_with`).** It
   is a HALF-BUILT version of this exact feature (`FaditorEditorActivity` ~:5289 `initMoveDrawer`):
   clip-reorder buttons WORK; **"move to" time input wrongly seeks the PLAYHEAD** (`performMoveToInput`
   → `seekToTimelineMs`, :5473) — the user's "timestamp jumps the playhead not the item" complaint;
   **Layer up/down are "coming soon" toast STUBS** (:5335-5340); timestamp shows the playhead;
   "Layer 1" hardcoded; NO shuttle. User decision: **build the scrubber into the move drawer too**
   ("can be in both"), acting on **WHATEVER OBJECT IS SELECTED** (any type). So NEXT: complete the
   move drawer — add the shuttle, fix "move to" to move the selected ITEM, make the timestamp/layer
   readouts track the selection, implement Layer up/down as real object relayering, wire collision.
   The proven toolkit (engine/session/shuttle) re-homes here; the move-drawer XML already has the
   slots (`move_target_input`, `move_go`, `move_layer_up/down`, `move_position_*`, `move_clip_*`).

### #6 LIVE WIRING STARTED — user greenlit it (2026-07-27). v1 slice: TEXT OVERLAYS, lock-only (`fcd5ade`).
User: "Note 20 is unplugged — wire up #6, I'll feel-test." Only the Note 9 is attached (safe).
Staged per SPEC §10 B: ONE payload first (text overlay), lock-only collision, then extend +
add relayer. What landed (`fcd5ade`, compiles + installed Note 9 APK 09:13:02, NOT run by me —
menu opens via hold-release, adb can't reach it):
- `ObjectMenuSheet.setTimeScrub` gained a `showPushThrough` arg (v1 hides the toggle).
- `EditorTimelineView.updateLayerItemStartLight(id, ms)` — the ANR-safe per-frame path: nudges the
  fed TimedItem's start in place + invalidate, NO getLayers()/setLayerTracks rebuild (SPEC §8a).
- `FaditorEditorActivity.attachTextOverlayTimeScrub` wired into `showObjectMenuSheetForTextOverlay`:
  session-driven span move (start+end together), light refresh per frame, full sync + one undo
  step on commit. Keyframes ride along (item-local time — verified). Locked overlays skipped.
**PENDING device feel-test (user, Note 9 first):** does the menu open without CRASHING; does the
shuttle feel buttery; does lock stop flush against a sibling; does jump-to-time + Snap + undo work.
Known v1 limits (noted, not bugs): jump-to-time TELEPORTS (glide is the polish slice); link-group/
visualizer riders resync on commit not live; push-through relayer + cross-lane y-glide are the
NEXT slice. After Note 9 passes → swap to Note 20 to PROFILE the light refresh on a large project.
NEXT payloads: sprite, audio (AudioClip.setOffsetMs), PiP (Clip.setOverlayStartMs), visualizer.

### ENGINE HARNESS HARDENED (`0f93cda`) — `ObjectTimeMoverTest` 15→21 (total move harness now 31/31).
Added multi-obstacle + list-order-independence + multi-item-above cases (each with a positive
control): lock-right/left pick the NEAREST obstacle regardless of list order; the above lane is
scanned fully. Test-only, no app change — hardens the core the device-gated wiring will build on.

### ADVERSARIAL SELF-REVIEW of this session's changes — caught + fixed one regression (`9c732e1`).
The #7 move-slop fix (`eec7ed2`) dp-scaled the slop at `onRowBodyMove:711`, which is SHARED by
MOVE and TRIM. TRIM maps the edge to the finger's ABSOLUTE time (`applyTrim`, no grab offset),
so the enlarged slop turned a trim's first ~8dp into a dead zone then snapped the edge to the
finger — an ~3mm start-lurch (MOVE is immune: it captures `moveGrabOffsetMs`). Fixed by scoping
the enlarged slop to MOVE only (its purpose is the hold-release menu, which TRIM lacks) and
keeping TRIM at the historical raw `TRIM_START_SLOP_PX = 4f` — trim feel now byte-for-byte as
before `eec7ed2`. Everything else reviewed clean: mute guard (`8d528db`) agrees with the drawn
disabled state; dead-code removal (`effc7b7`) provably unreachable + compiles; gap shrink
(`fd9a77a`) is a pure constant; the menu section (`08beb76`) is inert (nothing calls
`setTimeScrub`, no background loop when GONE). Move-package harnesses re-run: 25/25.

### ALL BLIND-SAFE LAYER-POLISH WORK NOW DONE (2026-07-27). Only device/feel-gated work remains.
- #1 lock one-way door — `565f63a`. #2 lane names — DROPPED (user: "layers don't need names").
  #3 mute-on-non-audio-lane — `8d528db`. #4 dead hide/lock header hit zones removed — `effc7b7`
  (provably dead: `HitZone.HIDE/LOCK` produced only by branches gated on always-empty rects;
  enum + activity handlers left intact). #5 gap hit-zone 8dp→5dp — `fd9a77a` (user chose "shrink
  it"; feel-tune, retunable). #7 move-slop dp-scaled to 8dp — `eec7ed2`.
- #6 object time-scrubber — toolkit BUILT+PROVEN + inert UI section shipped; LIVE wiring
  DEVICE-GATED (see below). This is the ONLY remaining scrubber work.
- **What's left is all device/user-gated:** the #6 live wiring (crash+ANR risk if built blind),
  and FEEL-tuning of the retunable constants (#5 gap 5dp, #7 slop 8dp, shuttle curve/speed). No
  more layer-polish is safely buildable blind. Next autonomous cycles should catch a user reply
  or do device-verified work — NOT force blind changes.

### OBJECT TIME-SCRUBBER (#6) — UI SECTION BUILT (`08beb76`); LIVE WIRING IS DEVICE-GATED (why below).
Additive `ObjectMenuSheet.setTimeScrub(...)` section landed (`08beb76`): the `TimeShuttleView`,
a tap-to-type `m:ss.mmm` readout (→ jump-to-time), and Push-through + Snap toggles. It is INERT —
`show()`'s signature is unchanged and nothing calls `setTimeScrub` yet, so no existing menu/gesture
behaviour is touched. Compiles; not run (object menu opens via hold-release, adb can't reach it).

**The remaining LIVE wiring is deliberately DEVICE-GATED, not skipped — the reason is a real
ANR risk, not caution (SPEC §8a, found by code-reading 2026-07-27):** `TimedItem` snapshots the
payload start at construction and the renderer draws from `setLayerTracks` views, so a live
`setStartMs` needs a Track-view REBUILD to show; `syncTimelineOverlays()` (the normal refresh) is
heavy (link-group propagation, visualizer/preview resync) and can't run ~60×/s; a light per-frame
row rebuild is needed but its cost is UNKNOWN and, given this app's documented long-project
ANR/2-3fps history ([[faditor-long-project-perf]]), a per-frame rebuild could ANR a 45-min
project. That is a usability/correctness risk that MUST be device-profiled — beyond the
"feel-tuning" the user accepted blind. The cross-lane GLIDE (§9) is also new renderer work (even
the drag proxy snaps rows vertically). So the Host + per-frame refresh + relayer glide land as a
DEVICE-VERIFIED step (blueprint = SPEC §8/§8a/§9). The whole PROVEN toolkit is ready for it:
engine (`c1f5bc7`, 15/15), session (`36c7d02`, 10/10), shuttle (`a6c9510`), section (`08beb76`).

### OBJECT TIME-SCRUBBER (#6) — LOGICAL CORE BUILT + PROVEN; editor wiring specified, deferred.
User decisions (2026-07-26): **build the whole feature**; **push-through is a TOGGLE for all
kinds incl. audio** (on = relayer, off = lock); **animation must be "buttery, not jarring."**
The user then stepped out → full autonomy.

Landed this stretch, each proven OFF-DEVICE with positive controls (see commits):
- `c1f5bc7` `ObjectTimeMover` — collision lock + push-through one-lane-up-or-new relayer,
  return-to-origin, breakthrough threshold, toggle-off=lock. Harness 15/15.
- `a6c9510` `TimeShuttleView` — Choreographer frame-synced variable-speed jog/shuttle, dead
  zone + cubic ramp, inertial eased spring-back. Compiles; feel is device-only (retunable consts).
- `36c7d02` `ObjectTimeScrubSession` — tick→resolve→preview, one-step commit / no-op-if-unchanged,
  origin-anchored lock reference. Harness 10/10.
All three live in `app/.../faditor/move/`; harness tests in `tools/jvm-harness/*Test.java`
(compile+run cmds in each test's header; out4). App build stays green.

**DELIBERATELY NOT wired into the editor yet** (the honest reason, do NOT mistake for "forgot"):
the wiring is surgery in the 20k-line `FaditorEditorActivity` + `LayerRowRenderer`, it is
feel-driven, and the buttery cross-lane GLIDE can't be verified by adb — writing it blind risks a
silent regression in the LIVE editor. It is fully specified in `SPEC_OBJECT_TIME_SCRUBBER.md`
§8 (wiring blueprint: additive `ObjectMenuSheet.setTimeScrub`, Host impl reusing the cross-row-drag
`mergedAction` undo + proxy, per-payload start/layerId setters, new-lane via `createLayerTrack`) and
§9 (animation contract: lane change = y-glide via the excursion-animator pattern, new lane eases in,
jump-to-time glides). Land it as a DEVICE-VERIFIED step. It is ADDITIVE — must not alter existing
menu/gesture behaviour.

### ⭐ USER REFRAME (2026-07-26, new account) — "layers don't need names, OBJECTS need names."
Interactive Q&A corrected my layer-polish framing. Load-bearing corrections, do NOT re-derive:
- **Lane names are UNWANTED** (finding #2 direction was WRONG). Layers stay nameless. Object
  naming is a separate, unbuilt idea.
- **"Move up/down" arranges OBJECTS, not lanes.** The user calls timeline OBJECTS "layers." The
  purpose is a precise, TOUCH-FREE way to move an object (vid/vis/pip/audio) — for imprecise
  touch, small/cluttered screens, AI control, and exact-value entry.
- The full, detailed design is now **`tasks/SPEC_OBJECT_TIME_SCRUBBER.md`** (variable-speed
  jog/shuttle scrubber + live tap-to-type time readout + jump-to-time + a magnetic
  collision/push-through-relayer model with a strict one-lane-up-or-new-lane limit and
  return-to-origin). It is a MULTI-PHASE, feel-driven feature, NOT a commit — Phase 1 (UI shell +
  lock-at-collision, no relayering) can start; Phase 2 (relayering) waits on the user confirming
  §2/§6 of that spec. User's verbatim answer is in the spec's appendix.
- **#7 gesture slop FIXED — `eec7ed2`.** The "hold works only sometimes" cause: the post-pickup
  move slop was a hardcoded 4 RAW px (~1.5dp here, tighter on the Note 20) — smaller than
  hold-jitter, so hold→release registered a micro-move and the object menu didn't open. Now
  dp-scaled via `setMoveSlopPx` (mirrors `setSnapRadiusPx`) to the conventional 8dp; only touches
  `LayerGestureController:711`, leaving the 450ms scrub-vs-move arbitration and `TOUCH_SLOP_DP`
  intact. Compile+install verified (APK 22:33:41 > edit); NOT feel-tested (adb can't inject
  hold-release) — 8dp is the user's to retune (one-line `MOVE_SLOP_DP`).
- **#5 gap target:** user chose "shrink it" — still TODO (gesture-feel, defer with #7 tuning).
- **Priority order the user gave: #7 → #6 → #4.** #7 done; #6 = the SPEC above (next, needs
  Phase-2 confirmation); #4 = dead hide/lock header code removal (cleanup, last).

### LAYER FINDING #3 (mute-on-non-audio-lane) FIXED — `8d528db` (new account, 2026-07-26 ~22:05).
The mute glyph is drawn DISABLED (COLOR_ICON_OFF, no strike) on any lane with no audio
(`drawMuteIcon`'s `applicable` = `rowCarriesAudio(t)`), but `hitTestHeader` returned
`HitZone.MUTE` regardless — so tapping the greyed mute on a text lane flipped `TrackFlags.muted`,
pushed a "Mute track" undo step and scheduled an autosave for zero audible effect. Fix:
`hitTestHeader` now reports MUTE only when `rowCarriesAudio(row.track)` (the same predicate that
greys it), else consumes as `HitZone.NONE`. Compile-verified + installed (APK 22:04:56 > edit
22:04:44). CONFIRMED BY CODE READING, NOT device-reproduced — mute rect is on a custom Canvas
view (no uiautomator node), same adb limitation as the `565f63a` lock fix; wants the same ~10s
human test. Safe by construction (mirrors an existing condition; audio/master lanes untouched).
**All other layer findings (#2, #4–#7) verified against code this session and are AFFORDANCE/
DESIGN decisions — NOT shipped blind. Put to the user via AskUserQuestion; awaiting the answer.**
#2 rename-draws-nothing (`LayerTrackDef.getName`/`TrackFlags.customName` persist + undo, but
`namePaint` only ever draws a kind glyph at :2026-2035, never a name; header comment at :670-672
deliberately keeps only caret+mute). #4 `lockRect`/`hideRect` `setEmpty()` at :653-654 → the
HIDE/LOCK branches at :2147-2148 are dead code. #5 gap-insertion (`gapIndexAt`) tested FIRST at
LayerGestureController:896. #6 no drag-to-reorder (menu-only). #7 MOVE_SLOP_PX=4 / 450ms pickup.

### ⭐ NEW TOP PRIORITY (user, 2026-07-26 late): LAYER MANAGEMENT POLISH — moving layers,
dragging, reordering, and navigating the lane menus, using what already exists. Ducking is
explicitly LOW priority ("I have never used ducking"). The user is not a developer and is
relying on us for the last 10%. Treat the PiP-volume-envelope and set_clip_duck items as
parked.

**Layer surface mapped this session. Highest-value findings, in order:**

1. **LOCK IS A ONE-WAY DOOR — FIXED this session (`LayerGestureController.beginPickup`).**
   Unlock lives ONLY in the hold→release-in-place object menu, and that menu could not open on
   a locked object: the locked branch set `lockedHoldRefused` but did NOT clear
   `pendingBodyDown`, so `onRowBodyUp` computed `wasTap = true` and its `wasTap` branch is
   tested BEFORE the `holdReleaseInPlace` branch. So locking an item from the row made it
   permanently locked (locked audio/PiP have no double-tap editor either). The code's own
   comment at the refusal says the release "must still open the drawer (it holds the Unlock
   action)" — the fix makes that true. **CONFIRMED BY CODE READING, NOT DEVICE-REPRODUCED:**
   `adb input swipe` cannot synthesize a hold-release-in-place (both a 900ms and a 1400ms
   1px-drift hold produced only selection + the Opacity drawer, no menu), which is the same
   class of adb gesture limitation already recorded for drag/sendevent.
   **20-SECOND HUMAN TEST WANTED:** select a text overlay → hold → release → menu → Lock. Now
   hold that same item again and release. Before the fix: nothing. After: the menu with Unlock.
2. **Renaming a lane does nothing you can see.** The name persists (`LayerTrackDef.setName` /
   `TrackFlags.customName`) and records an undo step, but NO code path ever draws a track name
   — `LayerRowRenderer` configures a `namePaint` and never uses it for a name, and the header
   deliberately "keeps only the caret + mute; no name, no kind identity". Confirmed visually on
   the Note 9: every lane header is an anonymous caret + icon. So Move up / Move down / Delete
   layer all target rows the user can only identify by position. This is probably the single
   biggest "layer management feels unclear" item and it is pure rendering work.
3. **Mute is offered on lanes that have no audio.** The mute rect is laid out on EVERY row and
   only the glyph greys out; the hit-test still returns MUTE, so tapping it on a text lane
   flips the flag, records an undo step, and does nothing audible. Also: track hide and track
   mute are preview-only — both have `TODO(M-EXPORT-1)` and the export ignores them.
4. **Per-lane hide/lock are unreachable by construction**: the renderer sets `lockRect`/
   `hideRect` to EMPTY, so those hit zones can never fire and their handlers are dead code.
   Per-object hide/lock moved into the object sheet; the header still renders as if they exist.
5. **~38% of each row's height is a "make a new lane" target.** The gap hit zone is ±8dp around
   a 3dp gap between 34dp rows, and gap is tested BEFORE row, so aiming at a neighbouring lane
   frequently creates a new one instead — and because an emptied user lane is auto-deleted, one
   drag can create a lane and destroy another.
6. **No drag-to-reorder of rows exists at all** — lane order is menu-only (Move up/Move down),
   and new AUDIO lanes skip the zIndex splice entirely so they land in builder order rather
   than where the insertion line promised.
7. Row gestures are arbitrated by a 450ms timer racing a **22dp** slop (system default is 8dp),
   so short swipes over an item do nothing at all and can resolve as taps; and the
   hold-release menu needs the finger to stay within **4 raw px** (~1.3dp), which is why "hold
   works sometimes".

### RELOADED UNDO HISTORY CAME BACK INVERTED — **FIXED + verified** (`b27d2b8`, audit 1.7).
`loadHistory` appended with `addLast()` while the rest of the class pushes/pops the other end,
so after a restart the OLDEST edit was on top and the history popup listed events upside down.
Proven against the SAME sidecar file, unchanged on disk: before the fix it rendered
`-1 Trim, -2 Added a title card`; after, `-1 Added a title card, -2 Trim`. Same input,
different build. Also confirmed while there: the AI flag round-trips (the sidecar's AI entry
carries `"ai": true`, the user's trim does not) and the violet row survives a restart.

### ❌ DO NOT "FIX" 1.6 THE OBVIOUS WAY. A sweep of all 115 `recordAction` sites refutes the
candidate repair (restore entry *i-1*'s snapshot): **18 sites record BEFORE mutating** and
already hold a correct pre-state (rotate `:5884`, flip, speed, volume/mute, delete clip/audio,
split-audio, reorder, replace-source, canvas-preset, audio-trim…), so the rule reverts one edit
too many for all of them; it would ALSO silently discard a whole AI edit, because
`recordAiCheckpoint`'s snapshot is a true pre-state; and the predecessor snapshot is often
absent or non-adjacent (1.5s throttle + byte budget drop entries, so the reloaded stack is a
SUBSEQUENCE of history). Recommended direction, plus two hazards that must be fixed alongside
(`undo()` returns true even when nothing was restored; the plain snapshot path invalidates only
the REDO stack), are written up in audit 1.6.

### ✅ THE OFF-BY-ONE IS NOW FULLY VERIFIED, INCLUDING THE RESTART PATH → audit item **1.6**.
Both halves measured on the Note 9: (1) the sidecar's stored snapshot for
`Trim [0–1929] → [0–614]` literally contains `clip0.outPointMs = 614`, the AFTER value —
a file-level measurement with no UI timing in it; (2) force-stop → reopen
(`Loaded 1 history entries from disk`) → undo logs
`Undone (snapshot): Trim [0–1929] → [0–614]`, badge 1→0, redo→1, and the saved project is
STILL 614. Positive control: the mechanism demonstrably ran, and the same trim undone
in-session (action path) correctly restores 1929.
**So: edit → close the app → reopen → undo does nothing, and says it did.** No AI needed;
this is the everyday case. Candidate fix + its one semantic choice (what happens to the
OLDEST entry, which has no predecessor snapshot) are written up in audit 1.6. Still NOT
fixed — core-path surgery, wants its own session and its own positive control.

### ⚠️ (superseded by the block above) snapshot-based undo is OFF BY ONE.
Restoring an entry's `snapshotBefore` yields the state INCLUDING that edit, not the state
before it, despite the field's name and javadoc. Measured: after undoing a
`Trim [0–1929] → [0–616]` via the snapshot path, the saved project still held
`outPointMs=616`. **Pre-existing, not introduced by fc34336** — the same mechanism governs
every history entry reloaded from disk, so undo after an app RESTART has presumably always
been off by one (that variant is NOT separately reproduced). The AI checkpoint is immune by
construction because it is captured before the project swap. Fixing it means moving when
`recordAction` captures its snapshot, which is core-path surgery and wants its own session
plus a positive control. **Do not fold it into an unrelated change.**

### AI EDITS ARE NOW ONE LABELLED, UNDOABLE, VIOLET UNDO STEP (`fc34336`) — user-approved
design, device-verified. `recordAiCheckpoint()` captures the pre-AI state BEFORE
`project = reloaded`; `invalidateActionsForProjectSwap()` drops the poisoned action refs and
keeps the snapshots (removing entries that have neither, since `recordAction` skips the
snapshot when one was taken too recently); `HistoryEntry.aiOrigin` drives a violet row in the
long-press history popup and round-trips through the sidecar (absent `ai` reads as
user-authored, so old sidecars load unchanged); `AIChatState` now carries a description so
the row reads "Added a title card". Proof on the Note 9: undo=1 after a trim → AI edit +
resume → `Recorded AI checkpoint: Added a title card (undo=2)`, 0 entries dropped → popup
shows the AI row violet above the white user row → one undo removes the AI's overlay from
the preview, the timeline AND the saved file.
**Still wanted by the user, NOT built:** (1) per-step granular undo for a multi-op AI run,
with tool icons — note their own caveat that acquisitions (transcribe, analyse silence)
should probably NOT be undoable, so the split is mutations-are-undoable vs
acquisitions-are-informational; watch the undo-history OOM history before adding N snapshots
per AI turn. (2) A pre-execution checkbox approval list of what the AI plans to do
(unchecked = muted + strikethrough), executing as one step — cheap given `EditScript` already
carries typed ops and a description and `validate()` already exists, BUT ops are
order/state-dependent, so unchecking one needs re-validation and dependent-op handling.

### PRIOR TIER-1 BUG, **DEVICE-REPRODUCED** then FIXED by fc34336: undo silently dead after an AI edit
(full write-up = audit item **1.5**). When the AI assistant edits a project, the editor
reloads and does `project = reloaded` (`FaditorEditorActivity.java:1245-1272`) but never
clears or rebinds the undo stack, so every entry still points at the DISCARDED object graph.
Measured with a positive control on `74e36000`: the SAME undo of the SAME action visibly
restores the clip before an AI edit, and does NOTHING after one — while still logging
`Undone (action): …` and decrementing the badge, so the user is told it worked. Ground truth:
the next autosave still has the un-undone value.
**Reproduce it without an LLM** — `ApplyEditsActivity` is exported (`AndroidManifest.xml:129`):
`am start -a com.fadcam.APPLY_EDITS --es project_id <id> --es edit_script '{"version":1,
"operations":[{"type":"ADD_TEXT_OVERLAY","text":"AIMARK","centerX":0.5,"centerY":0.8,
"sizeFraction":0.09,"startMs":0,"endMs":3000}]}'`. That is a genuinely reusable harness for
any AI-path test — it needs no network and no API key.
**NOT FIXED — needs a user decision** (three options in the audit item: clear the stack /
synthesize one "AI edits" snapshot step / declare AI edits outside the undo model). Each
changes what the undo button means, so it should not be picked blind. The second, worse
variant — undo restoring a pre-AI snapshot and DISCARDING the AI's work — is reasoned from
`UndoManager:302-307` and remains **unverified**; it needs history loaded from disk.

### ⚠️ NO DEVICE ATTACHED as of ~18:20 (RESOLVED ~18:25 — Note 9 is back and unlocked). `adb devices` is EMPTY — the Note 9 was unplugged
(the Note 20 did NOT appear; nothing was installed to a wrong phone). Device verification is
unavailable until a phone is back. The build watcher is still alive and still compiles, so
source edits are safe, but NOTHING can be device-proved right now — and the watcher will
auto-install to whatever phone appears next, so re-check `adb devices` before saving source.

### NEXT UP — Tier 3 / Tier 4 of AUDIT_UNFINISHED_20260726.md. Most of what is left is either
a USER DESIGN DECISION or a data-touching change that should not land unverified. Pending user
decisions now number five: B1, B4, B5, B3's remedy, and the preview-side preset-crop gap.

- **Tier 3 triage done (no code shipped — on purpose).**
  - **3.7's audio contradiction RESOLVED by reading** (details in the audit doc): audio and
    text/video use deliberately DIFFERENT strategies — text/sprite/PiP get separated onto
    another LANE at load, audio gets SHIFTED IN TIME at add-time
    (`Timeline.resolveAudioOverlap:438`). So the missing "audio load-time pass" is not an
    oversight of the same mechanism. Residue is narrow and **cosmetic only** (persisted
    overlapping audio draws two blocks in one row; export mixes every audio clip regardless of
    lane). NOT built: it is a data-touching migration (rewrites layerIds, persists on autosave)
    and there is no device to verify it on. Also corrected a coupling worry from the review
    pass — adding audio lanes cannot re-expose the legacy `drawAudioTrack` branch, because
    `audioLayerTracks` comes from `getAudioTracks()`, which is non-empty whenever any audio
    clip exists.
  - **3.2: I re-read all three sub-claims.** `removedSpans` clamping makes that sub-claim a
    **non-issue (drop it)**; "no entry point B" for dual-stream auto-detect is **real** (one
    call site); the speed-mismatch trim logs a warning but says nothing to the USER — **real**.
    Both remaining 3.2 items are UI-visible additions, so they are design decisions, not
    mechanical fixes.
  - **3.5 stays closed** (premise dead — verified last stretch). Its only residue is dead-code
    removal, which is not worth doing blind with no device to confirm the timeline still draws.

- **2.3 EXPORT LEG FIXED + device-proved. PREVIEW LEG DELIBERATELY NOT SHIPPED (needs a user
  decision) — see below.** The preset table moved from `ExportManager.getCropRect` to
  `Clip.cropRectNdc`, and a new `Clip.effectiveCropFractions()` turns either the custom
  fractions or a preset's NDC rect into ONE image-space rect {l,t,r,b}. `ExportManager`
  delegates to it (still exactly one table) and `GlTransitionFrameOverlay.cropSrcRect` (the
  export INCOMING leg) now uses it, so a preset-cropped clip no longer blends uncropped and
  snaps at the cut.
  **Numbers (Note 9; fixture = black image → 800ms GL cross_dissolve → 1080x1920 clip
  cropped "9:16"; second fixture identical except the crop is written as the numerically
  equal `custom` 0.34375..0.65625, which is the ORACLE for what the preset must render):**
    - baseline pre-fix, preset vs custom: **235/798 frames differ**, blend-region mean
      absdiff **18.47**; within-export content width **1.000 during the blend → 0.312 after
      the cut** (that width snap IS the user-visible bug).
    - after the fix, preset vs custom: **0/798 frames differ**; width **0.312 throughout**.
    - regression control, custom on old build vs new build: **0/798 differ** → the working
      custom path is untouched.
    - instrument not blind: the same diff saw 235 differing frames on the baseline pair; the
      encoder-residual floor (mean ~1–2) was calibrated from regions of that same pair whose
      composites are identical. All three mp4s have DIFFERENT sha256 while decoding to
      identical frames — the reason this class of proof compares pixels, never files.
  **⚠️ THE BIG FINDING — the RE-SCOPED block's premise was WRONG, and it changes the scope.**
  It claimed "preview and export currently AGREE — both blend a preset-cropped clip uncropped,
  then snap to the cropped framing at the cut". The preview does NOT snap, because **the live
  preview never renders a named preset crop at all**: `applyCropZoom`
  (`FaditorEditorActivity` ~:7391) is `"custom"`-only, and it is the only thing that crops the
  normal-playback preview. MEASURED at an identical playhead (00:12.132, same clip selected):
  the same crop written as `custom` previews as a narrow strip; written as `9:16` it previews
  FULL WIDTH. So the real state was: export self-inconsistent (blend vs cut), preview
  self-consistent but silently ignoring presets everywhere.
  Consequence: following the spec literally — cropping presets in `cropToClipBounds` /
  `liveLegGeometry` only — would have MOVED the snap into the preview (cropped during the
  blend, uncropped the instant the cut lands) instead of removing it. Those three preview
  sites are therefore left `"custom"`-only with a comment saying exactly this, so nobody
  "finishes the job" and reintroduces it.
  **Needs a user decision (do NOT ship blind):** named preset crops are invisible in the
  whole editor while the export applies them — what you see is not what you get, for every
  preset-cropped clip, not just during transitions. Closing it means making `applyCropZoom`
  preset-aware, which changes what the editor shows for every existing preset-cropped
  project. That is a visible behaviour change, so it is diagnosed here and left for the user.
  **Sandbox:** `aeb0517e` was the fixture host (JSON surgery on an already-indexed project,
  per the F12 recipe); restored byte-exact, project.json AND .bak both back to
  `0db82121…` = its safety copy. 9/10 projects match; `cebc19e0` still diverges on purpose.
  HONEST NOTE: during fixture pushes I deleted that project's `project.json.bak` and
  `undo_history.json` WITHOUT copying them first (unlike the 129d8643 restore earlier, where
  I did). Both are derived files and .bak has been rewritten from the restored original, but
  if that project had a distinct pre-existing backup or undo stack, it is gone. Four test
  exports were left in the device's Faditor export folder, as previous sessions also did.
  New reusable tool: **`tasks/framing_probe.py`** measures the content width at given
  timestamps of ONE export — the within-export blend-vs-cut check that `export_ab_diff.py`
  (an A-vs-B tool) structurally cannot do.
  **AN ADVERSARIAL REVIEW OF MY OWN DIFF CAUGHT A REGRESSION I HAD SHIPPED INTO THE WORKING
  TREE — worth repeating on compositor changes.** `ExportManager` gates the whole crop block
  on `isVideo` (`= !clip.isImageClip()`, :2273/:2329), so an IMAGE clip's own segment is never
  crop-effected — but `GlTransitionFrameOverlay` routes image clips through `cropSrcRect` too.
  My first version therefore would have cropped an image clip's BLEND and not its CUT: the
  same snap, mirrored — the exact failure I had just written a comment warning against on the
  preview side. Fixed before commit by excluding image clips (they keep the `"custom"`-only
  rule, making the expression identical to the old code for them). NOTE this exclusion is
  correct BY CONSTRUCTION, not by measurement: the only image asset in these projects is
  solid black, so a framing probe cannot see a crop on it. An image-incoming fixture would
  need a non-black image pushed and indexed.
  **Other caveats from that review (recorded, not fixed):** `cropSrcRect` cuts in UNROTATED
  space while the clip's own segment is cropped AFTER `ScaleAndRotateTransformation`, so a
  rotated+cropped clip's blend and cut disagree — pre-existing for `"custom"`, now reachable
  for presets too. And `docs/project-schema.md` advertises a `4:5` preset that
  `cropRectNdc` has no case for (such a project gets no crop anywhere — consistent, so not a
  new break, but the doc is wrong).
  **SCOPE, HONESTLY:** no UI path writes a named preset to a clip today — the crop tool only
  ever sets `"custom"` or `"none"` (`FaditorEditorActivity:6026, 6071, 6465`; `EditActions`
  just replays whatever was set). So this bug is reachable from legacy or hand-edited project
  JSON (which is what the fixtures are) and from any future writer using the presets the
  schema advertises. The fix is still right, but it is lower-severity than the audit implied,
  and that is worth knowing before spending more on the preview leg.

**2.3 scoping notes from before the work (kept — the code sites are still accurate):**

- **The four sites, all branching `"custom".equals(clip.getCropPreset())` and applying NO
  crop for a named preset:** `GlTransitionFrameOverlay.cropSrcRect` (:216, export incoming
  leg), `FaditorEditorActivity.cropToClipBounds` (:9281, preview static tier),
  `liveLegGeometry` (:9053, preview live A+B tier) and `cropKey` (:9302 — the frame-cache
  key; MISS IT AND a preset-cropped frame gets served from an uncropped cache entry).
  The OUTGOING leg is already correct in export: `ExportManager` :2331-2340 feeds the Crop
  effect from `getCropRect(preset)` for any non-`none` preset.
- **The one conversion to route all four through** (per the RE-SCOPED block): NDC→fractions
  off `ExportManager.getCropRect`, whose array is `{left, right, bottom, top}` in NDC −1..1
  and is consumed as `new Crop(left, right, bottom, top)`. Fractions: `l=(left+1)/2`,
  `r=(right+1)/2`, `t=(1-top)/2`, `b=(1-bottom)/2`. That table is currently private static
  in ExportManager — expose it (or move it) so preview and export cannot drift apart. Keep
  the existing epsilon rules (`>0.01`, and full when both `>=0.99`).
- **Transition seam convention (verified from `TransitionIndex.removeForDeletedClip`, which
  drops `t.clipIndex == i` and `i-1` for clip i):** a transition at `clipIndex = i` is the
  seam AFTER clip i, so OUTGOING = clip i and **INCOMING = clip i+1**. The crop under test
  goes on clip i+1.
- **Fixture candidate:** `aeb0517e` ("AudioExportVerify") already has a `GL_SHADER`
  cross_dissolve. Prefer JSON surgery on an ALREADY-INDEXED project + restore afterwards
  (what F12 did with 302da9ac) over a cloned dir — a dir created behind the app's back is
  NOT indexed and its list row misroutes the click.
- **⚠️ CORRECTION to the RE-SCOPED block's step 4 — `--check-asym` does NOT apply here.**
  It takes ITEM CENTRES (`x,y;x,y`) and fails when a flip maps one item onto another: it is
  a compositing-LAYOUT guard, not a crop guard. And its intent ("put the crop OFF-CENTRE")
  is unachievable by construction: EVERY named preset rect is centred on both axes, so no
  preset fixture can expose a flip of the crop rect. Do not fake an off-centre preset —
  that would no longer be the thing under test. Use these two guards instead:
    1. **Within-export blend-vs-post-cut framing comparison** (what F12 actually proved):
       pull a mid-blend frame and a post-cut frame from the SAME export. Post-cut framing
       comes from the Crop effect, the known-good authority — so "no snap at the cut" is a
       real check, and it is the check the user-visible bug is about.
    2. **Pick the `9:16` preset** (x∈[-0.3125,0.3125], y FULL) on the 1080x1920 portrait
       sources: it crops in ONE axis only, so the realistic bug for this conversion —
       mis-indexing the `{left,right,bottom,top}` array and cropping the wrong axis —
       shows up as an obviously wrong framing rather than a subtle offset.
- **Order (unchanged and load-bearing): BASELINE BOTH EXPORTS BEFORE TOUCHING CODE.**
  Fixture P = preset `9:16` on the incoming clip; fixture C = the numerically equal `custom`
  fractions (l=0.34375, r=0.65625, t=0, b=1). Today they must DIFFER mid-blend and AGREE
  after the cut — capturing that difference first is what makes the later fix
  distinguishable from a no-op. Then the symmetric 4-site change, then re-export both and
  require agreement THROUGHOUT, plus preview screenshots mid-blend for both (an export-only
  diff cannot see a preview regression, and preview/export parity is the invariant this
  item exists to protect).
- Do NOT "correct" the preset table's 16:9-oriented NDC constants in the same change (a
  standing warning in the RE-SCOPED block): parity with the cut is the goal, and
  "fixing" the constants would break it.

- **TRANSCRIPT NAVIGATOR DONE + device-verified (`2627e00`).** Built both halves of the
  decided design. (1) `TranscriptPanelView.setClipWindow(inMs,outMs)` + `inClipWindow()`:
  words whose SOURCE start falls outside the current clip's trim draw dimmed (0xFF6E6E6E,
  struck 0xFF4A4A4A); the window defaults to UNBOUNDED so any caller that never sets one
  keeps the old rendering. (2) A tap on a dimmed word is now unambiguously NAVIGATION —
  it routes straight to `onSeekToMs` and is no longer eligible for the double-tap
  line-break gesture. IMPLEMENTATION NOTE (differs from the scoped plan, deliberately):
  no new `onNavigateToSourceMs` callback was needed — `onSeekToMs` (18955) ALREADY
  re-homes a source ms onto whichever clip of the same source contains it, and
  `seekToTimelineMs` → `onPlayheadSeeked(isDragging=false)` already sets
  `selectedClipIndex` + `setSelectedIndex`. So cross-clip selection was already wired;
  only the highlight and the tap disambiguation were missing. Host applies the window via
  new `applyTranscriptClipWindow()` at both panel binds in `loadTranscriptPanelContent()`
  and from the playhead updater (selection changes route through `selectSegment` →
  `loadTranscriptPanelContent`, so coverage is complete).
  **Bug this exposed and fixed in the same commit:** both halves of an IN-MEMORY split
  share ONE `Transcript` instance (`Clip` copy-ctor addAll's the same `NamedTranscript`
  refs), so the existing re-bind branch — which only fires when the instance DIFFERS —
  left `transcriptClipId` pinned to the pre-split clip, and the `clip.getId().equals(
  transcriptClipId)` gate at ~7971 stopped updating the playback highlight the moment
  playback crossed the seam. Added an else-if that re-homes the id when the instance
  matches but the clip differs.
  **Device proof (Note 9, project `129d8643` "bisect C long 2x", predictions computed in
  Python from project.json BEFORE looking at the screen):** clip [1406,4457] → bright
  "this cat is very cute she is white and"; sibling clip [4457,4884] of the SAME source →
  bright "fluffy" ONLY. Both observed exactly — same panel, same 37 words, different clip
  (that pair IS the positive control: a blind instrument would have shown one set twice).
  Then a real split at source 2730 (predicted blind): half A bright "this cat is very
  cute", half B bright "cute she is white and", boundary word bright in both. Tapping a
  dimmed word selected the owning clip, flipped the dimming, and seeked to that word;
  playing across the seam kept the highlight advancing (not frozen).
  **Sandbox restored + verified:** `129d8643` project.json AND .bak are back to
  sha256 `111bea60…` = its safety copy; 9/10 projects match their safety copies and
  `cebc19e0` still diverges ON PURPOSE ('hi' start=1892, lastModified unchanged —
  untouched this session). Its `undo_history.json` held full-project snapshots of my
  split, which would have fought the restored file, so it was removed after being copied
  to scratchpad `tn/backup_undo_history.json` (a missing history is a supported state:
  `loadUndoHistory` logs "No undo history found" and returns false).
  **FIXTURE TRAPS PAID FOR HERE (both mine, not the app's):** (a) the timeline toolbar's
  orange "±" at ~(386,1305) is `btn_ripple_mode` (edit mode), NOT split — tapping it
  recorded an undo entry and looked like "the split silently failed"; the real Split lives
  in the SCROLLABLE bottom tool carousel and its position moves. Get real bounds with
  `adb shell uiautomator dump /sdcard/vh.xml` + `exec-out cat` (needs
  `MSYS_NO_PATHCONV=1` in Bash or Git-Bash mangles the device path) and grep for
  `id/tool_split`. (b) `adb shell cat` inserts CRs — a pulled project.json will NEVER
  sha256-match its safety copy until you normalize CRLF→LF (or pull with `exec-out cat`,
  which is byte-exact). I nearly mis-read this as "the project was already modified".
  **Cosmetic follow-up, NOT a regression:** right after a split/undo the active-word cyan
  box can briefly sit on an out-of-window (dimmed) word, because `setActiveSourceMs` is
  driven by the player position which hasn't resettled. Pre-existing behaviour that the
  new dimming merely makes visible; it self-corrects on the next tick.

### (DONE — see above) Transcript navigator (audit 1.1 step 3, DECIDED design).

Decided behaviour: after a split, the panel shows the WHOLE source with the current clip
highlighted, and BECOMES a navigation surface (tap a word in another clip's region → jump to
that clip). Key finding from scoping (do NOT re-derive): **the panel already shows the whole
source** — `loadTranscriptPanelContent()` binds `currentTranscript = clip.getTranscript()`
(`FaditorEditorActivity.java:20835`, also 20820 for audio), and post the transcript-SHARING
migration every clip from one source holds the SAME whole-source transcript. So the old
"windowing wraps words misaligned" symptom is already gone; what's missing is the
CURRENT-CLIP HIGHLIGHT and the cross-clip NAVIGATION. Build those two things:

1. `TranscriptPanelView` (494 lines): add `setClipWindow(long inMs, long outMs)` +
   `clipWindowInMs/OutMs` fields. In `onDraw`, render words whose `startMs` is OUTSIDE
   `[inMs,outMs]` dimmed (they belong to OTHER clips of the same source); words inside are
   full-strength. The existing `activePaint` cyan highlight (playback word) stays. Words are
   in SOURCE time already (`transcript.words[i].startMs`), so the window is a direct compare.
2. Tap routing in `onTouchEvent` (~line 383-399): currently a tap calls
   `listener.onSeekToMs(words[idx].startMs)`. Change so that when the tapped word is OUTSIDE
   the current clip window, it fires a NEW listener callback `onNavigateToSourceMs(long)`
   instead — the host resolves which clip's `[inPointMs,outPointMs]` contains that source ms,
   selects that segment (`selectSegment`), and seeks. Inside-window tap keeps `onSeekToMs`.
3. Host (`FaditorEditorActivity`): after every `transcriptView.setTranscript(...)` (7 sites:
   20828, 20843, 7966, 20959, plus the binds near 7964/12180), also call
   `transcriptView.setClipWindow(clip.getInPointMs(), clip.getOutPointMs())`. Implement the
   new `onNavigateToSourceMs` in the Listener at 18955: find the clip index whose source
   window contains the ms (careful: multiple clips of the same source can overlap in source
   time — pick the one nearest the current selection, or the first containing it), then
   `selectSegment(idx)` + seek. Note `editorTimeline.setTranscriptHighlight(selectedClipIndex,
   sourceMs)` at 7980 already exists for the timeline-side highlight — mirror its clip
   resolution.
4. DEVICE-VERIFY: split a clip in cebc19e0 or a throwaway, open the transcript on each half,
   confirm (a) the same whole-source words show on both, (b) the current half's words are
   full-strength and the other half's are dimmed, (c) tapping a dimmed word switches the
   selected clip and seeks. Use a THROWAWAY clone (restore after) — and remember the adb-dir
   indexing trap above (corrupt/clone an ALREADY-INDEXED project, don't hand-make a dir).

### Not scoped for code changes without a user design decision: B1, B4, B5, and B3's fix.
B1 (PiP audio route), B4 (link badge = "lock", no unlink), B5 (dual-stream discoverability)
and B3's remedy are all discoverability/affordance calls the user has NOT decided. Diagnosed;
await a design decision before shipping UX changes.

- **LOAD-FAILURE SHAPE fix DONE + device-verified (`5803fce`).** Decided behaviour built:
  one bad item no longer aborts the whole load into a silent `.bak`. ProjectStorage's
  deserializer now wraps each clip / overlay-clip / audio-clip / text-overlay / waveform
  item in its own try/catch (sprite path already did); a bad item is skipped + recorded on
  `FaditorProject.loadSkips`. The Activity's `warnIfItemsSkipped()` dialog names the dropped
  items and offers **Keep going** / **Open last backup** (new `loadBackupOnly()` +
  `EXTRA_LOAD_BACKUP`). A `loadSkipDialogPending` guard blocks all saves while the dialog is
  open so an autosave/onPause can't rotate the current file into `.bak` and destroy the clean
  backup being offered. Device proof on an INDEXED project with a text overlay's
  sizeFraction=null: `Skipping malformed text overlay #0 — UnsupportedOperationException:
  JsonNull` → `Loaded with 1 skipped item(s): [Text overlay #1]` → dialog shown with the
  overlay absent + rest intact → "Open last backup" → `loadBackupOnly: … (skips=0)` reloaded
  clean. Positive control: valid sibling overlay 'Yo' + the clean backup both loaded skips=0.
  TEST-INFRA NOTE (cost real time): a project dir created via adb (bypassing the app) is
  NOT indexed — its recent-projects row misroutes the click to another project. To device-test
  a load-corruption fix, corrupt an ALREADY-INDEXED project's project.json (with a pulled
  restore point), not a freshly-adb-created dir.
- **Sandbox integrity RESTORED + verified.** cebc19e0 restored to the user's real state
  (`scratchpad/b3_before.json`, 'hi' start=1892) for BOTH project.json and .bak; throwaway
  fixture `aaaa1111-…` deleted. All 9 non-cebc safety-copied projects are sha256-IDENTICAL to
  their copies (playback/scrub never dirtied them). `3072f113` (user's B5 project) untouched.
- **B3 DIAGNOSED (device, not a standalone code bug — a symptom of B4).** On cebc19e0's 'hi'
  overlay I measured all three drag routes: a **body/center drag SCRUBS** (playhead moves, 'hi'
  data UNCHANGED — pulled before/after: start=1892 end=4925 both times); an **edge-grab TRIMS**
  (resizes) — this is the only drag that visibly changes 'hi', and it is correct behaviour for a
  trim handle; a **move requires a long-press pickup (450ms, ITEM_PICKUP_MS)** which is
  undiscoverable AND, because 'hi' is in the TIME+OPACITY link group, the move is refused/clamped
  (the B4 constraint). Net: the user tried to move 'hi', the middle-drag scrubbed (nothing
  happened to the text) and/or the edge-grab trimmed ("got longer"), and the real move was
  blocked by the link. So B3's root cause is **B4 (time-link + no discoverable unlink)**, NOT a
  separate gesture bug. FALSE ALARM ruled out: a left-trim to 0 serialises `startMs` as ABSENT
  (ProjectStorage:1795 only writes startMs when !=0; reader defaults absent->0), which pull-diff
  shows as `null` — that is start=0, correctly handled on load, NOT corruption.
  Fix is a UX/affordance decision (make unlink discoverable per B4; make trim-vs-move legible) —
  NOT shipped blind. cebc19e0's 'hi' currently has start=0 from my trim experiment; restore from
  the pre-experiment pull `scratchpad/b3_before.json` (start=1892) when done with B1/B4 (they
  also need cebc19e0's PiPs/link-groups). Note: still deciding whether B4's unlink UX is a
  user-design call before shipping.
- **B2 FIXED + device-verified (`21e4cce`).** Diagnosis: NOT a start-freeze. cebc19e0
  ("P0 control2 plain", the cat project, row 1/2 in the list — NOT the screen-recording
  "Untitled" project that happens to be open on arrival, which is a DIFFERENT project)
  plays fine from the start. The bug is **pressing play with the playhead already at the
  timeline END = silent no-op** (no auto-rewind): play() seeks to the last clip's trim-end,
  immediately hits end, stops (`Playback stopped at last segment end` -> STATE_ENDED),
  playhead never moves, no audio. Fix rewinds to 0 at end. The load-bearing signal is
  `isAtTrimEnd() on the last segment` gated on `!hasAudioTail` — isEnded() is false (Activity
  pauses proactively ~150ms before ENDED) and the terminal playhead (13166) sits 189ms below
  getTimelineEndMs()'s sum-of-clips (13355) because of 3 transitions, so neither isEnded() nor
  a position-epsilon could fire. Proven by on-device log + screenshots; positive control
  (mid-clip play does NOT rewind) also passed.
- Note: opening cebc19e0 bumps its lastModified (now row 1). Playback/scrub with autosave may
  have touched the earlier "Untitled" screen-recording project's mtime — re-verify the 9
  non-cebc sandbox projects' sha256 at end of stretch (cebc19e0 intentionally still diverges).
- Wakeup mechanism note: ScheduleWakeup is /loop-scoped and clamps to 1h; cloud scheduled
  tasks have NO device access (wrong env). Continuation is by ongoing work in-session; this
  §0z + git log is the seam if the turn ends.

---

## 0a. HUMAN TEST PASS, 2026-07-26 ~12:20 — findings + two product decisions

The user ran the four things a harness cannot. Evidence is on the Note 9 in
`cebc19e0` (touched, **do NOT restore it — it is the evidence**) and a new `3072f113`.

### DECISIONS TAKEN (build to these)

1. **Corrupt project file.** Do BOTH: skip the bad ITEM and keep the project, *and* tell the
   user what is broken/missing, *and* offer "open the last backup instead" as a choice. Not a
   silent fallback and not a silent skip — say what was lost and let them pick.
2. **Transcript panel after a split: show the WHOLE source with the current clip highlighted.**
   Rationale from the user, and it is a stronger reason than the spec recorded: because every
   clip points at ONE source, the panel becomes a NAVIGATION surface — you can see how big your
   clip is and jump to the other clips through the transcript itself. Build it as a navigator,
   not just a viewer.

### CONFIRMED WORKING (harness could not have shown these)

- **Neutral-substrate queue item 7 PASSES.** Dropping into the row gap created lanes named
  **"Layer 8" / "Layer 9"** — `LAYER` kind, correct naming, not "Text N".
- **Item 5/6 direction confirmed**: the `'hi'` text overlay is sitting on `layerId
  43fda830-…`, which is a **LAYER-kind lane** — a text really does land and stick on a neutral
  lane.
- **Audit 1.2's schema fix fired in the wild**: that project is now `schemaVersion=11`,
  stamped because the user created real LAYER lanes. Exactly the designed behaviour, on a
  project no fixture touched.

### BUGS FOUND

- **B1 — PiP audio opt-in is effectively unreachable.** The user could not turn it on, and the
  file proves why: BOTH PiPs still have `overlayAudioEnabled` absent (never set) and
  `audioMuted=true`, so silence was CORRECT behaviour. "Include audio" lives only on the PiP
  object menu, and that menu opens only on **hold → release-in-place on the timeline item**
  (`onItemMenuRequested`). The user reasonably double-tapped the PiP in the preview and got
  nothing. The feature is built and correct and cannot be found. Needs a discoverable route.
- **B2 — playhead does not move / no audible playback** in that project. NOT explained by B1
  and not yet diagnosed. Reproduce in `cebc19e0` before anything else.
- **B3 — a horizontal drag on a text layer RESIZES it instead of moving it.** User moved the
  `'hi'` text sideways and it "got longer in the time domain as opposed to just shifted".
  Real bug, distinct from the link-group constraint below.
- **B4 — "purple link" is a LINK GROUP, not a lock, and there is no discoverable way out.**
  The file has 2 link groups: 3 waveforms bound by `TIME`, and 2 text overlays bound by
  `TIME`+`OPACITY`. That is why most items would not drag sideways — they are time-linked to
  their host, which is *correct* behaviour badly communicated. The user read purple as "locked"
  and could not find any unlock/unlink affordance. Needs the badge to say what it means and
  offer "unlink".
- **B5 — dual-stream recording is not discoverable.** The user enabled something in options and
  still found no dual mode; the new project `3072f113` is a plain 1-clip, `schemaVersion=7`
  project with no PiP. So SPEC_PIP_AUDIO acceptance 4 stays BLOCKED, and there is a real
  discoverability bug in front of it.

### Note on sandbox integrity

`cebc19e0` no longer matches its safety copy **on purpose** — it holds the user's test work.
The other 9 still match. Do not "restore" `cebc19e0` until B2/B3 are diagnosed from it.

---

## 0. CURRENT STATE (updated 11:40) — READ THIS FIRST

HEAD `45905e0`, branch `joy-creator`, tree clean except the always-ignorable
`tools/jvm-harness/out*/`. Build watcher ALIVE, installing to the **Note 9 only** (Note 20
unplugged). APK 11:19:50, installed, newer than every source file.

**Pick up here.** The `hasValue()` sweep is now COMPLETE (`6712a17` + `45905e0`): all 195
guard sites in ProjectStorage's read paths examined, 182 converted, 13 identity fields left
loud on purpose. Device-verified on both the clip path and the audio/text/sprite/overlayClip
paths.

That work surfaced the thing actually worth doing next, which is bigger than the guard class:

> **A single malformed value in ONE item aborts deserialization of the WHOLE project**, and
> `load()` then silently serves `project.json.bak` with nothing but an `FLog` line. The user
> loses everything since their previous save because one overlay had a bad number. Guarding
> optionals shrank the surface; it did not change the shape.
>
> Two candidate fixes, either of which is a real improvement:
> 1. **Per-item fault tolerance** — wrap each clip/overlay/audio item's deserialization so a
>    bad item is skipped and logged, and the rest of the project still loads. Note the sprite
>    path ALREADY does this (`catch (Exception ignored)`), so there is precedent in-file; the
>    other three payload types do not.
> 2. **Make the fallback visible** — if `load()` falls back to `.bak`, tell the user, the way
>    `warnIfProjectIsReadOnly` tells them about a newer-schema file. Silent rollback is the
>    part that turns a bad field into lost work.
>
> Reproduce it in one line: take any clone, set a text overlay's `sizeFraction` to `null`
> (read unguarded in `TextOverlayItem`'s constructor, `ProjectStorage:2290-2295`), open the
> project — it silently opens the backup, or fails to open at all if there is no backup.

**Diagnostic lesson worth keeping:** filter logcat by PID, not tag —
`adb shell "logcat -d --pid=$(adb shell pidof com.fadcam.beta) -t 700"`. App-wide greps
returned nothing because Bluetooth chatter had pushed the app's lines out of the window, and
three fixtures were mis-diagnosed before I read the actual exception.

**All 10 real sandbox projects are sha256-identical to their safety copies.** Every experiment
in this run used a throwaway `cp -r` clone; nothing needed restoring. Stray artifacts left
deliberately on the sandbox: a handful of 480p test exports in `FadCam/Faditor/`.

### Audit items closed in this run (see the audit's STATUS BOARD for the live list)

1.2, 1.3, 1.4, 2.1, 2.2, 2.4, 2.5 (export leg), 2.6, 2.7 (items 2/3/8), guard hygiene, plus
the timer-export answer owed in §7 and the ROWGESTURE logging in Tier 4. Each has its own
commit whose message states what was proved and how.

**Three of them found more than the audit described** — worth knowing, because it means the
audit's wording is a starting point, not a spec:
- **1.3** is not "drops an object". For 3 of 4 sites the loader treats the file as corrupt and
  silently opens `project.json.bak`. Reproduced: a fixture opened titled "ROLLED BACK TO BAK".
- **2.4** has the direction BACKWARDS. The export was correct; the PREVIEW over-rendered the
  neighbouring clip's words. Fixing the export on the audit's wording would have broken it.
- **2.1** needed a second fix: any re-bind blanked captions while paused, so the slider erased
  the very thing it was meant to show.

### Verification tooling in `tasks/` — REUSE, do not rebuild

- `export_ab_diff.py` — decode two exports, diff PIXELS. `--check-asym` refuses a fixture
  symmetric enough to hide a flip. Proven on 2.7 item 8 and cross-type Z.
- `export_audio_probe.py` — fit a source's amplitude inside an export. 1.0 = mixed once,
  2.0 = doubled. `--expect-absent` for a not-present control.
- `schema_layer_stamp.py` — schema-stamp survey + corruption repro + 4 source tripwires.
- `getlayers_equiv.py`, `visible_equiv.py` — both now hard-fail on zero matched files.

### The habit that actually produced these results

Every proof carried a POSITIVE CONTROL, and that is not ceremony. **Five times in this run a
confident finding turned out to be my own harness**, and each was caught only by a control or
by looking at the actual pixels:
- a 3-way export comparison gave three byte-identical files → my setup deleted only the
  same-id fixture, so "row 1" kept opening the wrong project;
- a cross-encode pixel diff at threshold 8/255 read lossy residual as signal and made a clean
  cross-type-Z result look broken;
- a correlation probe nearly as long as the export had no lag headroom and fitted gain −0.204
  where the truth was 0.993;
- "the lane eye does not persist for text or sprite" — I grepped for `hidden`; the key is
  `objHidden`;
- an export "truncated with no moov atom" was me `am force-stop`-ing during muxing.

If a proof reports "no difference", assume the instrument is blind until a control says
otherwise.

### Next, in the audit's own order

1. **2.3** preset crops during transitions. Deliberately not started — symmetric three-site
   change in the GL transition compositor, and starting it without room to device-verify is
   worse than not starting. **The executable recipe is now written into
   `PERF_SPEC_LONGFILE_20260718.md`'s F12 RE-SCOPED block**, including "capture the FAILING
   baseline before touching code".
2. **Tier 3** items, then Tier 4.
3. Still open and needing a HUMAN, not a harness: 2.5's preview leg (someone must listen to a
   PiP), 2.5 acceptance 4 (needs a real dual-stream pair project — none exists on the
   sandbox), and 2.7 items 5–7 (gesture drag-and-drop; adb injection drifts, and this is why
   `ROWGESTURE_DEBUG` was turned off rather than deleted).

### Found in passing, NOT fixed

1. After an undo with the caption drawer open, the preview canvas collapses to a thin strip
   for a frame or two; it recovers on the next playhead move.
2. `ProjectStorage` has ~223 typed JSON reads guarded by `.has()` alone (78 `getAsString`,
   57 `getAsFloat`, 38 `getAsLong`, 34 `getAsBoolean`, 22 `getAsInt`; only 6 null-guarded).
   Audit 1.3 fixed the 4 named `layerId` sites; the rest want one `optString/optFloat` helper
   and a mechanical sweep, with its own proof.

### Device + harness traps (each cost real time)

- Screenshots via the **Bash** tool (`adb exec-out screencap -p > f.png`); PowerShell's `>`
  adds a BOM and corrupts binaries. Same for `adb shell cat` of JSON — read with `utf-8-sig`.
- `adb push` via **PowerShell**; from Bash `/data/local/tmp` becomes
  `C:/Program Files/Git/data/local/tmp` (or prefix `//`).
- `touch` does NOT trigger Gradle's watcher (content hash, not mtime).
- Foreground `sleep` is blocked; wait with a backgrounded `until [ "$APK" -nt "$SRC" ]`.
- An export of a 4-second project takes **~55 s**, not the ~15 s the dialog implies. Poll for
  output-size stability before touching the app.
- Never compare mp4 hashes (container bytes differ, frames identical).
- `FaditorEditorActivity` is not exported — `am start` cannot open a project. Use the UI:
  Faditor `(627,2108)`, row 1 `(538,705)`. **Purge every non-real project dir first**, or
  row 1 is ambiguous.

### Fixture recipe (how every proof here was made)

`adb shell run-as com.fadcam.beta cp -r <projects>/<real-id> <projects>/<fake-uuid>`, patch
the JSON on the host, `adb push` to `/data/local/tmp`, then
`adb shell "cat /data/local/tmp/x.json | run-as com.fadcam.beta sh -c 'cat > .../project.json'"`.
Set `lastModified` to now so it sorts to row 1. Delete the clone when done and re-verify the
10 real projects by sha256.
Projects: `/data/data/com.fadcam.beta/files/faditor/projects/<id>/project.json`
Exports: `/storage/emulated/0/Android/data/com.fadcam.beta/files/FadCam/Faditor/`

---

Landed in the first stretch, each with its own offline proof and device verification:

| commit | audit item | proof |
|---|---|---|
| `003895e` | guard hygiene | both python guards now hard-fail on zero matched files |
| `bb30275` | **1.2** LAYER schema hole | `tasks/schema_layer_stamp.py` (survey + corruption repro + guard + 4 source tripwires); device 7→11 with a LAYER def, stays 7 without |
| `7735a21` | **2.1 + 2.2** caption size | device A/B 3%↔20% live in preview; audio size 0.15 round-trips. Also fixed: any re-bind blanked the captions while paused |
| `2c03e2b` | **1.3** `layerId: null` | device: 15 objects in → 15 out with 4 explicit nulls; control (`cropPreset: null`) silently opens the older `.bak` |
| `d6cbd46` | **1.4** downgrade drill | drill RAN 7/7; found + fixed the undo-history sidecar being written for a read-only project |

**Device hygiene:** every experiment used throwaway `cp -r` clones. All 10 real sandbox
projects are **sha256-identical to their safety copies** — verified after the last
fixture was removed. Nothing needed restoring. One stray artifact left deliberately: a
480p export in `FadCam/Faditor/` on the sandbox from drill step 6.

**Reusable fixture recipe** (this is the machinery to reuse, it worked well):
`adb shell run-as com.fadcam.beta cp -r <projects>/<real-id> <projects>/<fake-uuid>`,
patch the JSON on the host, `adb push` to `/data/local/tmp` then
`cat /data/local/tmp/x.json | run-as com.fadcam.beta sh -c 'cat > .../project.json'`.
Bump `lastModified` to now so it sorts to row 1 of the project list, then tap
Faditor `(627,2108)` → row 1 `(538,705)`. Delete the clone when done.
`FaditorEditorActivity` is **not exported**, so `am start` cannot open a project directly.

**Newly known traps:**
- Screenshots must be captured with the **Bash** tool (`adb exec-out screencap -p > f.png`).
  PowerShell's `>` corrupts binaries with a BOM. Same for `adb shell cat` of JSON — read
  those with `utf-8-sig` or use Bash.
- `adb push /data/local/tmp/...` from **Bash** gets MSYS-mangled to `C:/Program Files/Git/data/...`.
  Use PowerShell for `adb push`, Bash for `exec-out` redirects.
- `touch` does NOT trigger Gradle's continuous build (it hashes content, not mtime). To
  force a rebuild you need a real content change.
- Foreground `sleep` is blocked by the harness; wait on the APK with a backgrounded
  `until [ "$APK" -nt "$SRC" ]; do sleep 3; done`.

**Two things found in passing, NOT fixed, worth their own look:**
1. Immediately after an undo with the caption drawer open, the preview canvas collapses to
   a thin strip for a frame or two; it recovers on the next playhead move.
2. `ProjectStorage` has ~223 typed JSON reads guarded by `.has()` alone (78 `getAsString`,
   57 `getAsFloat`, 38 `getAsLong`, 34 `getAsBoolean`, 22 `getAsInt`; only 6 null-guarded).
   Audit 1.3 fixed the 4 named `layerId` sites; the rest want one `optString/optFloat`
   helper and a mechanical sweep, with its own proof. See `2c03e2b`.

Next per the audit's own order: **2.5** (PiP audio plumbing), **2.6** (cross-type Z
absolute-geometry A/B), **2.7** (neutral substrate queue items 2,3,5–8), then **2.3**
(preset crop — read the F12 RE-SCOPED block, not the older note).

---

Written at the end of a long Opus 5 session because the account ran out of credits and work
continues on a **different login, same machine**. Everything below lived only in that
session's context and would otherwise be lost. HEAD at write time: `6a9a800`, tree clean
(except the always-ignorable `tools/jvm-harness/out*/`).

---

## 1. The transcript migration RAN and is VERIFIED

Observed live on the user's phone at 01:21 and confirmed on disk:

```
27221664  collapsed=11 recovered=2824 shared=7    <- legacy partitions reconstructed
a32d24e2  collapsed=26 recovered=0    shared=8    <- modern full copies, collapse only
00024cc7  collapsed=4  recovered=0    shared=2
66623e32  collapsed=11 recovered=0    shared=2
```

On-disk proof for the legacy project, before vs after:

```
BEFORE  78253218: [141, 272, 524, 1291]   (four partial forks)
AFTER   78253218: [2228]                   (one shared, complete)
        bdb7ca98: [173,262,506,1261] -> [2202]
        synth_17: [182,258,506,1266] -> [2212]
```

2824 = exactly the offline prediction. Every clip now shares one complete transcript.

**Follow-up already applied (`ProjectStorage.shareTranscriptsWithBackup`):** because each
clip still SERIALISES its own copy, forks reappear on every load and this migration runs
every time. It was writing a fresh multi-megabyte backup on each app launch — two 5.3MB
files from a single launch were measured. It now only backs up and rewrites when
`recovered > 0` (a real reconstruction); a pure in-memory re-collapse persists nothing.
The permanent fix is the deferred file-level dedup (serialise each transcript once, clips
hold ids) — see `SPEC_TRANSCRIPT_SHARING.md` open item 4.

**Housekeeping owed:** the redundant `project-preshare-*.json` files already written on the
user's device can be deleted; the migration has succeeded and byte-exact originals are in
the safety copies below. Total project dir is 213MB.

## 2. SAFETY COPIES — read-only originals of the user's real projects

```
C:\Users\JoyRaptor\fadcam-safety-2026-07-26\note20-projects\27221664.json   (1,149,763 bytes)
C:\Users\JoyRaptor\fadcam-safety-2026-07-26\note20-projects\a32d24e2.json   (5,337,181 bytes)
C:\Users\JoyRaptor\fadcam-safety-2026-07-26\note9-projects\proj_*.json      (10 sandbox files)
```

These are byte-exact pulls taken BEFORE the sharing migration existed. They are the recovery
path if the migration goes wrong. **Do not delete them.** The migration also writes its own
`backups/project-preshare-<ts>.json` on device, but that is one layer; these are the other.

## 3. DEVICES — two phones, different everything

| | Note 9 (sandbox) | Note 20 (THE USER'S REAL PHONE) |
|---|---|---|
| serial | `<note9-serial>` | `<note20-serial>` |
| model | SM-N960U, Android 10 | SM-N986U, Android 13 |
| screen | **1080x2220** | **1440x3088** |
| holds | disposable sandbox projects | **the user's real work** |
| attached now | NO | yes |

**Tap coordinates are NOT portable between them.** Everything below is Note 9 / 1080x2220
ONLY and must be re-derived from a screenshot on the Note 20:
Faditor nav icon `(627,2108)` · project row 2 `(538,896)` · play/pause `(540,1306)` ·
split `(387,1305)` · export icon `(1013,55)` · export-now `(758,1625)`.

`adb` is NOT on PATH:
`C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe`

**DANGER — the build watcher auto-installs to whatever single device is attached.** Saving a
source file will push a build to the user's real phone. That is fine for shipping a fix and
bad for anything experimental. Prefer the Note 9 for experiments; if only the Note 20 is
attached, think before saving.

## 4. Hard-won operational lessons from this session

- **Never `adb logcat -c`.** Clearing the buffer during a reinstall destroyed the one capture
  of the bug being hunted. It was only recoverable because a copy had been saved seconds
  earlier. Just note a timestamp instead.
- **A "compile-green" claim means nothing without a fresh APK timestamp.** This session opened
  by discovering the previous session's ~35 commits had NEVER been compiled: the watcher had
  died and a stale-from-07-06 javac intermediates dir made every build fail *before*
  type-checking, hiding a real error. Check `dumpsys package … lastUpdateTime` against
  `git log`, and that java processes exist.
  Fix for that failure: `Remove-Item app/build/intermediates/javac/<variant> -Recurse`.
  Never `gradlew --rerun-tasks` (corrupts media3-patched jars).
- **Validate data-touching changes offline against real pulled files BEFORE shipping.** A
  first-wins transcript merge looked obviously safe, and would have replaced a 1112-word
  transcript with a 1110-word one on the user's live project. It was caught only by a
  word-count guard added on a hunch. The rule now: pull the real file, run the algorithm on
  it in Python, print the before/after, THEN write the Java.
- **The device is the user's, and they may be using it.** Screenshot immediately before every
  tap; if focus or orientation changes between screenshots, stop injecting.
- `build.log` is UTF-16 — read via PowerShell `Get-Content -Encoding Unicode`, not `iconv`.
- Gradle needs `$env:TEMP='C:\Users\JoyRaptor\gtmp'; $env:TMP=$env:TEMP`.
- The `Glob` tool is broken on this repo's `C:\+Projects` path — use Bash `ls` or `Grep`.
- Windows Python cannot read `/c/...` paths; pass `C:/...`. A guard script "passed" silently
  over **zero matched files** because of this. Always print the file count.

## 5. What landed this session

| commit | what |
|---|---|
| `9ec93c0` | fixed the build — `staticProp` lives on `Prop`; nothing had compiled since 13:56 |
| `8fc7cb4` | seeded-lane pre-emption: a payload on another type's seeded lane stole that lane, renaming it, flipping its kind and hoisting it up the band (= a silent paint-order change). Device-proven; 4 assertions added to `getlayers_equiv.py` |
| `5b29ea7` | **countdown/count-up timer text objects** — `TimerSpec`/`TimerText`, one authority shared by preview+export, 38/38 harness, device-verified counting 0:05→0:01 |
| `9745071` | `selectSegment` resolved positions against the OLD selection (stale view index) |
| `1042345` | transcript word-tap: resolve against the transcript's clip, re-home onto the clip that contains the tapped time. **Fixed the user's 0:00 teleport**, verified on their own taps |
| `d3c870d` | playhead glides through a cut instead of freezing 110–330ms at every seam |
| `9ec300a` | **transcript sharing** + legacy-partition migration (see §1) |
| `6a9a800` | the unfinished-work audit |

## 6. Open, in priority order

Read `tasks/AUDIT_UNFINISHED_20260726.md` first — it is the map. Highlights:

1. ~~Verify the transcript migration~~ — DONE and verified on device, see §1.
2. **`TrackKind.LAYER` schema hole** (audit 1.2) — DATA-LOSS class, cheapest fix in the list.
   `SCHEMA_VERSION` is still 10 so the downgrade guard never fires for a neutral lane; an
   older build coerces LAYER→VIDEO and re-saves, permanently changing paint order.
3. **Caption size slider** (audit 2.1/2.2) — wired to model + export but never to the preview
   overlay, for a month. Users size captions blind. One setter + one bind + two serializer
   lines.
4. **`layerId: null` drops objects** (audit 1.3) — four `isJsonNull()` guards.
5. Transcript panel step 3 — but per `SPEC_TRANSCRIPT_SHARING.md`, show the WHOLE source with
   the current clip highlighted, NOT the windowing the old plan specified. The user needs to
   read ahead to place breaks.
6. Standing device-verify debt: PiP audio acceptance 2+4 (nobody has listened to a PiP or
   exported one), cross-type Z absolute-geometry A/B, neutral substrate queue items 2,3,5-8.
7. `SPEC_TEXT_ANIMATION.md` — designed, not started.

## 7. Things the user is owed an answer on

- Whether the transcript migration recovered their ~937 words (§1).
- ~~Timer export leg: no export has ever been rendered with a timer in it.~~
  **ANSWERED 2026-07-26 09:05 — the timer renders in the export and counts correctly.**
  A throwaway clone carrying one countdown timer text (`COUNT_DOWN`/`RELATIVE`, min+sec,
  precision NONE, span 0–4000 ms, off-centre at 0.32/0.30) was exported at 480p/Low and
  sampled at four timestamps. Rendered values, read off the frames:

  | t | expected `ceil((4000−t)/1000)` | in the exported frame |
  |---|---|---|
  | 0.35 s | 0:04 | **0:04** |
  | 1.2 s | 0:03 | **0:03** |
  | 2.2 s | 0:02 | **0:02** |
  | 3.2 s | 0:01 | **0:01** |

  4/4, matching `TimerText.format`'s ceiling-seconds contract (the same values
  `tools/jvm-harness/TimerTextTest.java` asserts). This is self-validating in a way a single
  frame would not be: a static text overlay cannot produce four different values in the right
  order, so it also rules out "the timer is baked as one frozen string at export time" — which
  was the plausible failure mode given preview and export share the authority but not the
  render loop.

  *Caveat worth stating: this verifies the RELATIVE countdown path with minutes+seconds and
  precision NONE. `ABSOLUTE` basis, `COUNT_UP`, hours, and the FRAMES/MILLIS precisions are
  covered by the 38/38 JVM harness but have not been through an export.*
- They asked for animated text; the spec exists, the build does not.
