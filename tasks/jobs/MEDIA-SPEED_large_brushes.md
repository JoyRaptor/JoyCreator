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

## Result 2026-10-31 (Note 9, branch `engine/media-speed`, not landed: owner look owed)

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

