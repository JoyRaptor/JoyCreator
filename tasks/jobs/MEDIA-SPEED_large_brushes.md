# MEDIA-SPEED: large oil and water brushes

Owner, 2026-10-31: larger brushes felt far too slow, wants 10-20x. He was on Palette knife 1 and large watercolour, Note 9.

## Found
- Oil stepped every 0.3 mm at any size. A size-scaled step is on `engine/media-speed` (`PasteBrush.stepScale`).
- Oil collapses once behind: a whole stroke can land in 3 frames. A per-frame budget is on the branch (`PasteSpacing.behind`).
- Each oil step still costs about 12 ms at 256 px. This is now the limit; cause not yet measured.
- Water at 256 px spends about 64 ms a frame in flush (look render, tile copies).
- Size limit: oil at 380 px and 512 px is REFUSED ("reaches further than this phone can hold"), 100 areas of 62.

## Results, Note 9, fast bench scribble (evidence: joybrush/evidence/media-speed-20261031)
| Brush | Before, media frames | After |
|---|---|---|
| Palette knife 1, 256 px | 6 | 21 |
| Oil round, 90 px | 3 | 13 |
| Oil round, 256 px | 4 | 5 |

## Plan
1. [x] Step with size; budget per frame.
2. [ ] Owner feel test of the branch: wide hops may show as scallops in a burst.
3. [ ] Measure one oil step's parts: does each framebuffer switch reload the whole atlas? Try invalidate or a smaller target.
4. [ ] Simulate large brushes at half resolution (4x fewer pixels), if the look survives.
5. [ ] Water: cut flush; same half-resolution question.
6. [ ] Lift the 380 px refusal.

Target: 10-20x. Not reached.

## Result 2026-10-31 (Note 9, landed on `joy-creator` the same day; the owner has looked at speed, not at sharp turns)

Measured first: 84 % of an oil step was the pixel shader, framebuffer switches about 10 %. Telling the driver a pass overwrites its box did nothing and was dropped.

- A bilinear read on a texel centre is one read (`jb_bilinearLS`): no pixel changes. Look reads about 64 to 16.
- Pixels past the brush leave before the noise.
- Stroke noise is one texture read (`MediaPasteNoise`), not four hashes: same kind of noise, not the same bytes.
- Oil asks the atlas for the box it paints, so 380 and 512 px are no longer refused.
- Steps grow with the root of size, bursts stretch to 12 % of width. Blades: 12 % of the 1.2 mm edge, ease over 2.5 mm (`bladeEaseMm`, 0 = old), paint creeps along lanes and thins out.

| Second stroke, scribble | Start of day | Now |
|---|---|---|
| Oil round 256 px | 578 ms, 5 media frames | 74 ms, 45 |
| Oil round 90 px | 734 ms, 3 | 33 ms, 87 |
| Knife 1 256 px | 6 media frames | 36+ |
| Water 256 px | about 100 ms | 75 ms, look 43 to 26 ms |

Every media brush at its own size and paste brushes at 256 px: 26 runs, no crash, no refusal (`evidence/media-speed-20261031/all-brushes`). `mediaAtlasLedgerProbe` and `mediaAtlasProbe` pass; suites 0 failures.

Open: oil at 512 px (about 500-900 ms); water under 50 ms; the first stroke after opening (over 1 s on the bench, which paints before idle prewarm finishes; not measured in real use); a step wider than 5 tiles logs that its brush reads past its tiles (about 512 px).

## Found by the owner on this build, 2026-10-31

**The razor-cut eraser.** A pink knife line, then lines across it, cut boxes out of the first. Those boxes were the crossing steps' own rectangles. A media frame failed near the end of the first line (14:43:28, a short toast; the exception was not logged, so its cause is still open), and the catch released the engine without writing it back. The look is written every frame and the stores only at a stroke's end, so the stores stayed blank while the look showed the line, and they still counted as agreeing. The next stroke loaded the blank stores and redrew its boxes from them.

Fixed: a failed frame ends the stroke through the same flush as a refused one, the engine writes back before it goes, and if that fails too the looks are distrusted so the stores are dropped on load (flat paint, never a hole). `a media frame failed` now goes to logcat under `JoyBrushMedia` with its stack. `mediaFailureProbe` (Palette knife 1): 37,488 of 185,375 laid pixels lost the old way, 0 of 193,853 now.

**The cul-de-sac.** At a sharp turn the whole body swung round the pen point, sweeping the outside of the corner. `PasteTrail` keeps the pen's path; a hair brush or trowel lies from the pen to the point one body-length back along it, so the tail stays on the path. Round a corner the body cuts the inside; doubling back the ends meet and it turns about its middle, shortening as it goes. `FOLLOW_SLACK` 1.1 stops a hand's wobble making it breathe. Blades keep their own steering. `Sharp turns` in a brush's settings keeps the old swing (`PasteBrush.turns`, bench `jb_paste_turns`). Note 9 `turns` stroke, before and after: `evidence/media-speed-20261031/turns-swing-vs-fold.jpg`. The fan is wider than long, so it has no body to swing and gains little.

After both: 14 scribble runs, no failed frame; Oil round 256 px second stroke 43 ms, 60 media frames (was 74 ms, 45). `mediaAtlasProbe`, `mediaUndoProbe`, `mediaFailureProbe` pass.
