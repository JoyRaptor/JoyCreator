# Tile board, one paint pass a frame (helper d4b3cd917), phone check 2026-10-20

Note 9, same hour, main (ffe3018e1 + the bench hash) vs the same plus d4b3cd917.
- `main_matrix.log` / `patch_matrix.log`: 33 brushes x 4 boards. `compare.txt` is the per-cell table (`summ.js`).
- `repeat6_tile.txt`: six repeats of six stamp brushes on the tile board each (`rep.sh`, `rep.js`).

What it shows:
- Pixel-exact: on the tile board Ink, Pencil, Flat Paint, Marker, Soft air, Smudge, Eraser and Fill pen give the SAME
  after-state hash (`afterHash`, FNV-1a over every plane) as main; so do the same brushes on canvas, animation and sprite.
- Sable, Bristle, the legacy watercolour and every media brush are not repeatable run to run (their hashes differ between
  two runs of the SAME build on boards the patch does not touch), so the hash cannot judge them. Their tile cells have the
  same verdict, wrap and leak counts.
- Speed: frame times on the tile board swing 20-500 ms between identical runs (pencil 22 to 559 ms on main). Medians over
  six runs, main -> patch: Marker 102 -> 76, Soft air 117 -> 100, Bristle 92 -> 86, Sable 22 -> 18, Ink 15.6 -> 15.7,
  Pencil 162 -> 236 (noise). A gain of 10-25 % on the heavier brushes; not the step change the profile hoped for.
  The cost left is the dab pass itself (JB.paint.dabs 15-860 ms a frame).
