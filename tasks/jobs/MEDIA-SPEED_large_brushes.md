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
