# VisLab review findings V1 to V5: receipt and the V2 proposal (2026-11-01)

Answers the VisLab half of [the Codex review](joybrush/reviews/CODEX_REVIEW_WAVE2_20261101.md). Each
claim was read against the code first. Branch `claude/festive-perlman-8dde53`, base `0fa6044fa`.
Not on `joy-creator` yet. Phone: Note 9, sandbox projects only, build restored afterwards.

| | Finding | Verdict | What changed | Proof |
|---|---|---|---|---|
| V1 | A slow frame silently exports the wrong look | ✅ Confirmed, fixed | An export now waits for the frame (up to 60 s, Cancel stops the wait). A frame that sticks, or a look that breaks part way, fails the export. The classic style stands in only for a look that never drew or a phone with no ES 3, as the design says | Tests. Phone: throwaway build with every wait forced to time out showed "Export failed", no video saved, project safe. Cancel stopped a running export at once, with the Cancelled message |
| V2 | Studio frames go GPU to CPU to GPU | ✅ Confirmed, not built | Timing lines added. Proposal below | Measured on the phone, below |
| V3 | Two layers of one video at different speeds rebuild a decoder | ✅ Confirmed, fixed | Each moment goes to the decoder closest behind it (`VideoHeads`), so layers settle on one decoder each and share when they match | Tests: 1x + 2x uses 2 decoders over 40,000 frames. **Not tried on the phone** |
| V4 | Sprite animation differs between scrubbing and export | ✅ Confirmed, fixed | Own speed comes from the moment. Beats and kicks come from exact counts of the whole sound (`HitCounts`, built in the background, awaited before an export) | Tests: random seeks, reverse scrub, rebuilt renderer. Phone: a frame-per-beat sprite after one jump to 7.533 s matches the exported frame 226. One moment, one look. Own speed not tried on the phone |
| V5 | Nine music-linked effects thrash the eight-clock cache | ✅ Confirmed, fixed | One clock per walk of the timeline (the preview, or one export part), recent moments remembered for effects a frame behind (`EarShelf`). The cap of eight stays | Phone, same 30 s project with nine effects: **370 s on the build that was on the phone, 68 s on this one**, about the same as with no effects; clocks made: 8,083 before, 1 for the export after |

The review's numbers held: its probe's frames (4 vs 1) and over 1,000 analysis steps a frame (1,197
in the test's matching model). 248.8 MB/s is right for a full 1080p look at 30 fps.

## Limits to know

- **Own speed with a music link on Speed** has no single answer for a moment: it travels, and after a
  jump restarts from the steady speed. Only that case can differ between a scrub and an export.
- **Sprites per beat before the counts are in** (the first second or so in a project, a few seconds for
  a long song) count as they play, then correct themselves. An export waits for the counts.
- **A sprite's beat count now starts at the source's start**, not the clip's. A clip trimmed to start
  later begins on a different frame than it did. Preview and export agree.
- **Five decoders per video.** Six or more different moments of one video do restart decoders.
- **Counting costs the drawing nothing**: it runs on a low-priority thread, and only for looks with a
  sprite playing per beat or kick. Measured 495 ms for 30 s of sound on the Note 9, so about 3 s for a
  three-minute song (scaled, not measured).

## V2 proposal: keep Studio's frames on the GPU

Measured on the Note 9 with the timing lines (milliseconds per frame, over 120 frames):

| Size | Look on the GPU | Submit | Read back | Row flip | New bitmap | Into bitmap | Overlay pass in all |
|---|---|---|---|---|---|---|---|
| 768 x 432 box (export) | 22.7 | 1.7 | 3.9 | 1.3 | 0.17 | 1.4 | 37 |
| 1280 x 720 full frame (export) | 39.8 | 1.4 | 4.4 | 2.9 | 0.16 | 3.8 | 58 |
| 648 x 364 box (preview) | 22.5 | 9.8 | 8.1 | 1.3 | 0.21 | not kept | |

A 720p full-frame export took 68 s for 900 frames, 75 ms a frame. The copying (read, flip, bitmap)
is **11 ms** of that, about 15%. Another 5.5 ms of the pass is unaccounted for, mostly the canvas draw.
The upload back to the GPU was not timed. **The look's own GPU time, 40 ms, is the biggest single
cost, and V2 does not touch it.** So V2 is worth doing, but it is not the big win the review's
"major" suggests at 720p. Its value grows with size: 1080p would be about 2.25 times the copying
(an estimate, not measured), plus 8 MB of memory churn each frame. Nothing here claims an fps gain.

**Recommendation, smallest first:**

1. **Do next (a small job, no visible risk):** one reused bitmap per item, and draw the look upside
   down in GL so no flip is needed. Saves about 3 ms a frame at 720p and stops the memory churn.
2. **Hold:** read-back into a buffer the GPU fills in the background, so the wait is not on this
   thread. Only worthwhile if step 3 is refused.
3. **Hold until exports at 1080p are a real complaint:** a shared GL context with Media3's, the look
   as a texture drawn in the export's own pass. Saves the copying and the upload, up to about 17 ms
   plus the upload. Needs fences across threads, context-loss handling, and a second route for the
   preview, whose plain visualizers are drawn by a software View. Blend, alpha, orientation, effect
   order and cancellation all need proving again. A large job with export parity at stake.

## Phone notes

Temperatures logged every 30 s: battery 26.6 to 30.9 C, chip 33.8 to 60.4 C, no throttling seen (the
old build's 370 s export was the warmest). Test projects "VL Review full", "VL Review nine" and their
exports are left on the Note 9. The "before" build is what the phone held (installed 00:52 that
night, from another lane), not a clean build of the base commit; its logs show the old clock per
effect. The Studio review session installed over this one by mistake between 01:47:40 and 01:47:55 and
put this build back. Nothing was lost, and no check overlapped: the first export had ended at 01:46 and
every other check ran after it. The phone's clock fell back an hour during testing.

Seen, not changed: a look that cannot be read shows nothing in the preview but the classic style in
the export.

Checks: `bash tools/jvm-harness/run-sprite.sh` (20 + 35 pass), `bash tools/jvm-harness/run-vislab.sh`
(pass), `:app:assembleDefaultDebug` builds.
