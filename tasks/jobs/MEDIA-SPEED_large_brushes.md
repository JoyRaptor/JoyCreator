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
6. [x] Lift the 380 px refusal. (Done 10-31, 75e055f8d: oil asks the atlas for the box it paints; 380 and 512 px are not refused. Checked 11-13.)

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

## Second session of the owner's testing, 2026-10-31 evening

The turns look right to him. A long stroke then dropped a square. The log held two faults:

- 16:29:40 `Board tile copy failed`, from `reserveStores` through `writableMediaTiles`, copying a tile on first touch. The failed-frame path kept the paint, so the stroke ended but nothing was lost. The real cause is still open: the old check could not tell a lost source tile from an error another pass left behind. The copy now clears and logs an earlier error, names the source and target status when it does fail, and a lost source tile starts blank (`JoyBrushTiles`). A tile retired twice is refused and logged with its stack, and texture 0 no longer enters a pool. Those were the suspects for the lost tiles: two tiles sharing one texture.
- 16:30:48 the drawing's 315 MB room ran out and strokes were refused. `writeStores` dropped everything when one new tile did not fit, which left the same stale stores as the failed frame, and the next stroke cut boxes. Tiles that have stores now get their paint, a new tile keeps its look as plain pixels, and the refusal is still said. `mediaFailureProbe` room run: 0 of 172,898 pixels lost.

Room: raised to 1 GB on a 6 GB phone, then put back to 256 MB (315 MB on the Note 9) before landing. A save holds every store in the 512 MB heap at once, so a bigger room would make saves run out of memory. The figure is about 7 by 7 cm of oil: raising it needs saves that stream to disk.

Oil flat and Fan blender ship with Leans at 0.7 (it was 0 on every brush, so which way the pen leaned did nothing). Round is unchanged. A burst's stretch is now measured from the smaller of a hair brush's width and twice its length: the fan's corners showed as fins in fast strokes at 280 px.

## Codex review of the 10-30/31 work, 2026-11-01

Six findings, all checked against the source and fixed:

1. Symmetry lanes used the old smoother while their recordings kept the chosen stabilizer: lanes now use the same one (`SymmetryStabilizerTest`).
2. Tapered or dashed symmetry strokes replayed shaped although they were not drawn shaped: such a stroke now stays plain paint. Shaping every lane is still owed.
3. The first paste step could overwrite a bound texture by making the noise late: it is made before the passes bind.
4. A hop shrinking after a backlog could land a step behind the last sample, off the path at a corner: the leftover is clamped (`aHopShrinkingAtACornerNeverLandsOffThePath`).
5. Shared folder repair stopped at the first failed copy: each drawing now fails alone and is retried by `backfillFolderCopies`. Partly covered already by that backfill.
6. A failed write during recovery cleared its pending work and skipped the fallback: any tile not written is now distrusted (`mediaFailureProbe` writeFailed: 0 of 163,268 pixels lost).

Not proved on the phone: 1 and 2. `lineParity` reports "refused at pen-down" on the build before these fixes and after, so editable-line replay was not exercised there.

## Second review: open engine items, 2026-11-01

Full text: [CODEX_REVIEW_WAVE2_20261101.md](../joybrush/reviews/CODEX_REVIEW_WAVE2_20261101.md). Checked against the source so far:

- E1 reopen races its outgoing save: `restoreWorkingFile` reads at once; plausible, timing not proved.
- E2 export can run out of memory: the writer catches `Exception` only. Confirmed.
- E3 atlas memory admission: not checked yet.
- E4 saves hold everything in the heap: confirmed (`readContents`). This is what holds the paint room at 315 MB.
- E5 masked stroke previews: not checked yet.
- E6 a stationary hold paints nothing: confirmed (`MediaSpline.add`).
- E7 distant tiles fail the atlas table: confirmed; it throws, so it takes the failed-frame path.
- E8 Library thumbnails fail on line drawings: confirmed in the Note 9 log; fixed 11-12 (`thumbnailOf` draws `ExportPicture.asShown`). `ThumbnailLinesTest` fails on the old code with the phone's own error and passes now; not installed on the Note 9 (another session's 19:56 build was there).

## Third review: engine and atlas, 2026-11-01

Full text: [CODEX_REVIEW_WAVE3_20261101.md](../joybrush/reviews/CODEX_REVIEW_WAVE3_20261101.md). All ten read true against the source.

| # | Finding | Result |
|---|---|---|
| T1 | A sleeping engine's freed names deleted again at release | Fixed from the source; `mediaFailureProbe` sleepThenRelease passed but did not exercise it: the Note 9 reused none of the 32 names |
| T2 | Water steps that ran in the park never reached the stores | Fixed from the source (`publishParked`); no test can prove it yet |
| T3 | A full park lost a step already run | Fixed from the source (`keepNextInStores`); not exercised |
| T4 | Failed park storage or copy counted as kept | Fixed (`MediaTex.make` refuses, `copyFailed`, a chunk is all or nothing); failure not injected |
| T5 | A tile whose storage failed was used anyway | Fixed (`refuseFailedStorage`); failure not injected |
| T6 | Cleanup could throw before the stroke's undo step | Fixed: cleanup follows the undo step |
| T7 | The look read a half being overwritten in odd water steps | Fixed: it reads `finishedPaint()` |
| T8 | Oversized frames never split | Fixed; the two halves share the frame's water steps |
| T9 | A dry measurement taken before new water marked it dry | Fixed (`wetSinceQuery`) |
| T10 | Any noisy control excused every difference | Fixed: only the tiles the control differs on are excused, and a noisy control reports `inconclusive` |

T1 is a possible cause of the four black tiles of 10-31, not a shown one. A deleted name that is bound again comes back with no storage, which is what jdb found on them, but it needs the driver to reuse the name, and the Note 9 did not in the probe. Both tile failures stay unexplained.

`mediaAtlasLedgerProbe` with watercolour: 87 tiles differ between two roomy runs, so water has no exact answer and the probe is inconclusive. 18 tiles differ beyond those, before and after the park fix. Oil is exact: 0 tiles differ. Water parity needs a run with a fixed clock before T2 and T3 can be proved.

- Status 11-12: E1 is Codex's (JB-LIVE-SAVE), E2 fixed (export catches running out of memory), E3 on [ATLAS-SOLID](ATLAS-SOLID_never_refuse.md), E4 belongs to the save lane, E6 and E7 fixed, E5 handed to Codex on the board.