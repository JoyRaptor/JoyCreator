# Device matrix: Note 9, 2026-10-18 (run 2, after the Select-tool fix)

**Result: 132 of 132 cells completed. 80 works, 28 refused, 24 noop.** Canvas-board ink changed 7,029 px with an exact undo, so the sanity rule passes.
Run 1 (2026-10-17, every cell a no-op) is superseded; its cause is below.

- Code under test: `436ffb5ca` (lead/device-matrix, on top of the merge 6753dc323)
- APK: `app/build/outputs/apk/default/debug/app-default-arm64-v8a-debug.apk` (debug, com.fadcam.beta)
- APK sha256: `c378b9719467281e6a7f7478c1512c3edfb405d2b82e98fe7480abe0bc779bb2`
- Date: 2026-10-18. Phone run 05:42 to 06:02 EDT.
- Install: `adb install -r`, Success. The app was in front (MainActivity), so HOME was pressed before the install and again after the run. Only the Note 9 was attached.
- Launch: `am start -n com.fadcam.beta/com.fadcam.joybrush.JoyBrushBenchActivity --es jb_bench matrix --es jb_bench_brushes all`
- Done: `{"case":"matrix","event":"done","cells":132,"elapsedMs":1146684}`. No FATAL, no crash.

## Run 1 cause (every cell a no-op)

Debug build with temporary logs, cells 0 to 2, log `JoyBrushMatrixDbg`:

- Every stylus event was claimed by the canvas tool: 435 of 435 touch events logged `onTouchEvent: toolTouch returned true`. No `startStroke entered` line exists in the log, so `startStroke` never ran.
- Example: `onTouchEvent action=0 tool=2 x=372.0 y=516.0 drawing=false penSeen=false` followed by `onTouchEvent: toolTouch returned true`.
- The tool is the Select tool. `JoyBrushActivity.setUpSelect` calls `SelectController.attach()`, which sets `canvas.tool`. `SelectTool.selectToolInHand` defaults to `true` (SelectTool.kt line 54). On a fresh launch nothing clears it; `leaveSelect()` runs only when a brush is tapped on the strip, and the bench never taps one.

**Fix:** `MatrixBench` clears `canvas.tool` before each cell and restores the prior tool when the matrix finishes. The temporary logs were removed (JbCanvasView restored to HEAD).

## Table

Each cell: `p50 / p95 ms (frames)`, busy time per stroke frame. `[tag]` = verdict other than works.

| brush | canvas | animation | tile | sprite |
|---|---|---|---|---|
| joybrush.ink | 15.84 / 16.94 ms (36f) | 15.29 / 17.74 ms (37f) | 15.38 / 17.94 ms (38f) | 15.75 / 16.62 ms (38f) |
| joybrush.sable | 15.67 / 16.95 ms (37f) | 15.4 / 16.67 ms (38f) | 15.7 / 17.17 ms (38f) | 15.51 / 16.68 ms (38f) |
| joybrush.pencil | 23.44 / 56.33 ms (17f) | 42.3 / 61.44 ms (13f) | 76.46 / 87.14 ms (10f) | 15.62 / 18.41 ms (34f) |
| joybrush.bristle | 15.46 / 16.99 ms (38f) | 15.07 / 17.67 ms (38f) | 16.52 / 40.87 ms (28f) | 15.23 / 16.35 ms (38f) |
| joybrush.flatpaint | 0 / 0 ms (0f) **[refused]** | 0 / 0 ms (0f) **[refused]** | 220.53 / 220.53 ms (3f) | 0 / 0 ms (0f) **[refused]** |
| joybrush.marker | 15.39 / 16.78 ms (36f) | 15.74 / 18.59 ms (38f) | 30.95 / 47.73 ms (19f) | 15.37 / 17.17 ms (36f) |
| joybrush.softair | 16.92 / 20.86 ms (33f) | 16.67 / 24.4 ms (34f) | 97.34 / 195.66 ms (7f) | 15.46 / 18.18 ms (35f) |
| joybrush.smudge | 0 / 0 ms (0f) **[refused]** | 0 / 0 ms (0f) **[refused]** | 15.71 / 18.04 ms (36f) | 0 / 0 ms (0f) **[refused]** |
| joybrush.eraser | 15.36 / 17.61 ms (37f) | 15.47 / 17.26 ms (36f) | 15.75 / 17.72 ms (39f) | 15.54 / 18.72 ms (36f) |
| fill | 122.97 / 122.97 ms (1f) | 126.28 / 126.28 ms (1f) | 70.68 / 70.68 ms (1f) | 29.64 / 29.64 ms (1f) |
| joybrush.watercolour | 0 / 0 ms (0f) | 24.6 / 51.11 ms (18f) | 81.55 / 144.32 ms (7f) | 21.4 / 34.45 ms (25f) |
| media:dry:Proto Pencil | 1011.8 / 1011.8 ms (1f) | 17.13 / 28.4 ms (29f) **[noop]** | 0 / 0 ms (0f) **[refused]** | 15.63 / 23.53 ms (35f) |
| media:dry:Proto Mix | 15.74 / 17.48 ms (36f) | 18.82 / 24.83 ms (29f) **[noop]** | 0 / 0 ms (0f) **[refused]** | 15.83 / 20.81 ms (37f) |
| media:dry:Proto | 15.85 / 20.94 ms (37f) | 20.03 / 25.45 ms (27f) **[noop]** | 0 / 0 ms (0f) **[refused]** | 15.58 / 22.2 ms (35f) |
| media:dry:Proto 2 | 15.91 / 17.63 ms (38f) | 15.49 / 24.38 ms (33f) **[noop]** | 0 / 0 ms (0f) **[refused]** | 16.01 / 21.13 ms (33f) |
| media:dry:2H | 15.83 / 17.17 ms (38f) | 15.82 / 18.83 ms (36f) **[noop]** | 0 / 0 ms (0f) **[refused]** | 15.85 / 19.33 ms (35f) |
| media:dry:HB | 15.56 / 24.26 ms (37f) | 15.68 / 21.29 ms (36f) **[noop]** | 0 / 0 ms (0f) **[refused]** | 15.66 / 26.88 ms (33f) |
| media:dry:2B | 15.63 / 23.75 ms (33f) | 15.72 / 24.86 ms (35f) **[noop]** | 0 / 0 ms (0f) **[refused]** | 15.65 / 18.58 ms (35f) |
| media:dry:6B | 15.73 / 23.36 ms (34f) | 15.13 / 19.86 ms (34f) **[noop]** | 0 / 0 ms (0f) **[refused]** | 15.46 / 18.07 ms (36f) |
| media:dry:Woodless 8B | 16.92 / 20.43 ms (33f) | 18.1 / 25.47 ms (31f) **[noop]** | 0 / 0 ms (0f) **[refused]** | 16.18 / 20.93 ms (34f) |
| media:wet:All-round | 58.33 / 119.92 ms (8f) | 88.89 / 134.61 ms (6f) **[noop]** | 0 / 0 ms (0f) **[refused]** | 60.97 / 150.37 ms (7f) |
| media:wet:Round | 71.19 / 85.67 ms (8f) | 93.05 / 159.7 ms (7f) **[noop]** | 0 / 0 ms (0f) **[refused]** | 39.7 / 87.6 ms (11f) |
| media:wet:Wash | 160.31 / 160.84 ms (5f) | 157.53 / 168.46 ms (4f) **[noop]** | 0 / 0 ms (0f) **[refused]** | 87.03 / 106.13 ms (7f) |
| media:wet:Dry brush | 70.81 / 113.92 ms (8f) | 67.16 / 109.68 ms (7f) **[noop]** | 0 / 0 ms (0f) **[refused]** | 75.86 / 105.97 ms (9f) |
| media:wet:Water | 82.36 / 123.7 ms (7f) | 73.59 / 118.08 ms (7f) **[noop]** | 0 / 0 ms (0f) **[refused]** | 94.46 / 118.54 ms (6f) |
| media:wet:Thirsty | 64.93 / 113.53 ms (8f) | 62.72 / 112.74 ms (8f) **[noop]** | 0 / 0 ms (0f) **[refused]** | 38.43 / 87.95 ms (11f) |
| media:paste:All-round | 125.88 / 217.42 ms (4f) | 118.32 / 215.87 ms (5f) **[noop]** | 0 / 0 ms (0f) **[refused]** | 30.32 / 68.79 ms (17f) |
| media:paste:Oil flat | 94.54 / 116.71 ms (7f) | 127.42 / 129.51 ms (5f) **[noop]** | 0 / 0 ms (0f) **[refused]** | 19.91 / 63.7 ms (21f) |
| media:paste:Oil round | 117.23 / 131.23 ms (7f) | 68.98 / 93.36 ms (8f) **[noop]** | 0 / 0 ms (0f) **[refused]** | 16.8 / 31.68 ms (30f) |
| media:paste:Fan blender | 133.3 / 155.05 ms (5f) | 129.19 / 177.78 ms (6f) **[noop]** | 0 / 0 ms (0f) **[refused]** | 89.56 / 99.99 ms (7f) |
| media:paste:Palette knife 1 | 186.02 / 211.38 ms (4f) | 162.49 / 176.13 ms (4f) **[noop]** | 0 / 0 ms (0f) **[refused]** | 75.13 / 108.46 ms (8f) |
| media:paste:Palette knife 2 | 219.61 / 239.78 ms (4f) | 190.22 / 191.64 ms (4f) **[noop]** | 0 / 0 ms (0f) **[refused]** | 70.62 / 100.99 ms (8f) |
| media:paste:Scraper | 149.55 / 257.52 ms (4f) **[noop]** | 144.9 / 221.88 ms (4f) **[noop]** | 0 / 0 ms (0f) **[refused]** | 101.54 / 124.4 ms (6f) **[noop]** |

## Ten slowest cells by p95 (as the parser ranks them)

One row is a single-frame measurement (media Proto Pencil, canvas, 1 frame): it includes first-use cost and is not steady-state.

- media:dry:Proto Pencil | canvas | p50 1011.8 p95 1011.8 frames 1 | works
- media:paste:Scraper | canvas | p50 149.55 p95 257.52 frames 4 | noop
- media:paste:Palette knife 2 | canvas | p50 219.61 p95 239.78 frames 4 | works
- media:paste:Scraper | animation | p50 144.9 p95 221.88 frames 4 | noop
- joybrush.flatpaint | tile | p50 220.53 p95 220.53 frames 3 | works
- media:paste:All-round | canvas | p50 125.88 p95 217.42 frames 4 | works
- media:paste:All-round | animation | p50 118.32 p95 215.87 frames 5 | noop
- media:paste:Palette knife 1 | canvas | p50 186.02 p95 211.38 frames 4 | works
- joybrush.softair | tile | p50 97.34 p95 195.66 frames 7 | works
- media:paste:Palette knife 2 | animation | p50 190.22 p95 191.64 frames 4 | noop

Four of these ten rows are noop cells (three animation media cells, which the verdict bug mislabels, and Scraper on canvas). Their timings are for strokes the verdict calls no-op.

## Failed, refused, and noop cells (by cause)

**Refused, 22 cells: media brushes on the tile board.** Message: "Turn tiling off to use pencil, watercolour and oil". This is the app's rule, not a fault.

**Refused, 6 cells: smudge-engine brushes (Flat Paint, Smudge) on canvas, animation and sprite boards.** Message: "Layer 1 holds editable lines, and smudge doesn't work on lines yet." These cells ran with \`primed: true\`. The priming strokes became editable lines, so the smudge refused. The activity restores the Editable lines switch from the saved preference (JoyBrushActivity.kt line 404, default false). This phone's saved value was not read, so this is an inference from the refusal text.

**Noop, 22 cells, animation board. This is a bench verdict bug, not a dead brush.** 21 of them have \`framePx\` between 21,866 and 40,192 with \`undoExact: true\`, so the stroke painted the frame cels. \`MatrixDiff.verdict\` counts only the visible plane, so it reports them as noop. The 22nd is Scraper (framePx 0, see next). Six of the wet cells also ended with \`settled: false\`.

**Noop, 2 cells, Scraper on canvas and sprite.** The bench primes only erase and smudge brushes, and the scraper has no paint under it on an empty board, so there is nothing to scrape. Undo is exact and frames were drawn. This is a bench gap.

**Errors:** none. **Cells started with no result:** none.

## Notes on what was not done

- Not fixed in this round, each needs another build and phone window: (1) the animation verdict should count \`framePx\` as changed; (2) prime the scraper, or mark it as not measurable on blank paper; (3) decide whether the matrix should run with Editable lines off, since that is a product setting.
- Not verified: whether a normal first launch of the app also gives the Select tool the pen-down. The bench reproduces it on a fresh launch, so a user drawing straight after launch may get a selection instead of a stroke. The owner or lead should check on a normal launch.
- Not verified: the older bench cases (BenchRunner, for example fastC) go through the same tool path, so they may have the same hold unless some other step clears it.

Raw JSON: \`tasks/joybrush/reviews/DEVICE_MATRIX_20261017.jsonl\` (132 cell lines, plus the \`plan\` and \`done\` lines).
