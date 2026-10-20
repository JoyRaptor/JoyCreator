# JoyBrush Lead handoff, 2026-10-20

Read `HANDOFF_20261019_LEAD.md` first for the rules and the lanes; this note is what changed on 10-20.

## On main (origin/joy-creator)

- **3c39ab539**: tile board, one paint pass a frame (the helper's d4b3cd917), plus a pixel hash per matrix cell
  (`afterHash` in the bench report). Evidence: `evidence/tile-step1-20261020/` (README has the table).
  - Pixel-exact on the tile board for Ink, Pencil, Flat Paint, Marker, Soft air, Smudge, Eraser, Fill pen, and the same eight on
    the other three boards. Sable, Bristle, the legacy watercolour and every media brush are NOT repeatable run to run (two runs
    of the same build differ), so the hash cannot judge them; their verdicts, wrap and leak counts match.
  - Speed: frame times on the tile board swing 20-500 ms between identical runs. Six-run medians main -> patch: Marker 102 -> 76,
    Soft air 117 -> 100, Bristle 92 -> 86, Sable 22 -> 18, Pencil 162 -> 236 (noise). A modest gain, not the step change. The cost
    that is left is the dab pass itself (`JB.paint.dabs`, 15-860 ms a frame). Tile steps 2-3 (wrap copies, Flat Paint, legacy
    watercolour) are still open and still the helper's lane.
- **The palette-knife crash fix** (see below): 36 phone runs of the knife/oil list with no crash and no double release (before it: 3 hits in about 25 runs). Suites: androidkit 1057 green; core and app were green before it and untouched by it.
- The Note 9 may still carry a diagnostic build (crash-diag, logging on): the Lead installs the clean main build after this note.

## The crash: "duplicate after texture ownership" (found and fixed 10-20)

- Symptom: the app died at the end of a media stroke (seen on the palette knife; about 1 stress run in 8, nothing in 40 runs of
  two knives alone). Also seen once in a full 132-cell matrix on main, so it is not from today's changes.
- Cause (diagnostic APK, logging every texture release with both stacks): `GlPaintEngine.dropStalePayload`, inside a media stroke,
  added a SECOND change for a tile the stroke had just made (`A -> T`, then `T -> null`). Undo puts each change's `before` back in
  list order, so the tile came back on T, a texture the step also owns as an `after`: wrong pixels after undo, and T let go twice
  (the pool then handed it out twice, which is the crash).
- Fix: `MediaStrokeChanges.addDrops` ends the stroke's own change instead (one change per tile). Pure, 5 tests.
- Not fixed, worth a look: the warning that starts it ("dropped the media of N tile(s) ... changed outside the media funnel")
  fires on the knife in the bench. Something writes a look tile without telling the payload (requirement 5's net). It is lossy but
  never wrong; the cause is still unknown. Branch `lead/crash-diag` has the logging.

## Atlas: stays parked, and why (review: `reviews/MEDIA_ATLAS_FLUX_COST_20261020.md`)

- The tilted-water pass was 3.7x the window's because of the per-read address arithmetic, nothing else. Faking the address to a
  constant offset gives 1.18x. Every honest form I tried is 2x or worse; branches (4.6x) and a table fetch per read (9x) are much
  worse on Adreno. Per-read addressing cannot reach the window's speed. Details, numbers and the harness are in the review and
  `evidence/atlas-flux-20261020/`; the timing-only variants are on `lead/atlas-exp`.
- Memory does not favour the atlas for a screen-wide wash either (about 580 MB of wet state in both).
- So: the owner's seam fix goes into the window engine.

## Seams: "follow the water" (WIP, branch `lead/follow-water-wip`, not wired)

- Idea: while water runs with no pen down, move the window (one tile at a time, at most every ~1.2 s) toward where the water is
  heading, so a wash or drip that reaches the window's edge is not cut flat. Pure placement rule `MediaWindowMath.waterPlacement`
  with tests is written. Left to do:
  1. hysteresis: a move resets the water rectangle to the wet tiles' box, which presses on the new window's edges at once and can
     move it back; count an edge as pressed only if the rectangle's edge MOVED out since the last check;
  2. the frontier weight vs the wet weight (frontier 9 vs wet 10 does not come down for a wash with a dry top row; 12 does);
  3. wire it: `MediaPlace.followWater`, `MediaCanvas.frameInner` (water-only branch), behind a Water test choice ("Follow the
     water") so the owner compares by eye (keep the old behaviour selectable);
  4. judge it on the phone with the bench screenshots (a long tilted wash, Today vs Follow), looking at the picture, not a number.
- Limits to say to the owner: the window is still 1024 px; water left behind when the window moves is frozen until the window comes
  back. A whole-screen wash needs about 580 MB of wet state however it is built, and the Note 9 had about 1.9 GB available.

## Symmetry (the other agent's branch `lead/symmetry-1019`, a1a16627f, worktree C:/Temp/jb-symmetry)

- Not yet verified by the Lead. What I read: the angle table (re-derived by hand: all four rows and the kaleidoscope axis
  phi + theta/2 are right), `AngleFrame`, `SymmetryLane` (the transform is applied once, to the sample), the `GuideSettings`
  decode (a bad symmetry line cannot wipe the other guides), and the `JbCanvasView` fan-out (one engine stroke, one undo step,
  frozen at pen-down). The lane's scatter feeds the same NaN speed/direction the ordinary path does, so lane 0 matches today's stroke.
- Still to do before it reaches main: run the suites in a worktree of its own (not in jb-symmetry while the agent works), build,
  and test on the phone: the mirror of a round brush, a tilted/rotated brush, kaleidoscope, undo after a fan-out, Select picks
  the group, the eraser, an editable-line stroke saved and reopened; and the media families refuse in words (E4 is not built).

## Waiting on the owner (unchanged from 10-19 except 1)

1. NEW: is the atlas parked for good, and should the Lead build "follow the water" (above) for him to compare?
2. Whole-screen patch: skip (the atlas finding makes it the only way to a seamless screen-wide wash, at ~580 MB: his call).
3. Flatten "Keep lines editable" checkbox; symmetry Q1-Q6; pencil shelf name; Flatten blend-over-paper.

## Tools added (tasks/joybrush/lead-scripts/)

`water_profile.sh` (bench runner under the phone lock), `stress.sh` (loop a brush list until it crashes), `rep.sh` + `rep.js`
(repeat runs and medians), `summ.js` (per-cell compare of two matrix logs incl. the hash). Git Bash: hand Windows programs
`C:/...` paths. The commit hook blocks device serials and the Windows user path in new files.
