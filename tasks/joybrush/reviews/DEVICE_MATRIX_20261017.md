# Device matrix: first Note 9 run (2026-10-17)

**Result: the run finished, but every one of the 132 cells is a no-op (0 changed pixels). These numbers are not brush costs.** The stroke replay appears not to have drawn anything. Treat this run as a harness or stroke-delivery failure until it is diagnosed. It is not evidence about any brush.

- Build commit: `6753dc323` (lead/device-matrix, merge of origin/joy-creator; `2ba653ed8` is the matrix bench commit it includes)
- Merge of origin/joy-creator: clean, no conflicts
- Build: `:app:assembleDefaultDebug` (debug, applicationId com.fadcam.beta), BUILD SUCCESSFUL in 8m 55s, 0 `e:` errors
- APK: `app/build/outputs/apk/default/debug/app-default-arm64-v8a-debug.apk`
- APK sha256: `e06b33adfac13d58807a92b51e063043a48e6e45a893ffa930654d8d581e35fd`
- Date: 2026-10-17
- Device: Galaxy Note 9 (SM_N960U), the only device attached
- Install: `adb install -r`, Success (no uninstall, no data clear)
- Launch: `am start -n com.fadcam.beta/com.fadcam.joybrush.JoyBrushBenchActivity --es jb_bench matrix --es jb_bench_brushes all` (boards, from and to left at defaults: all four boards, all cells)
- Plan: 132 cells (33 brushes x 4 boards); `done` event after 301,993 ms; 22:45 to 22:50 local
- Raw log: `matrix-logcat.txt` in the worktree root (not committed)

## Table

Each cell: `p50 / p95 ms (frames)`, the busy time per stroke frame as the bench reports it. `[noop]` = verdict noop.

| brush | canvas | animation | tile | sprite |
|---|---|---|---|---|
| joybrush.ink | 3.15 / 3.15 ms (1f) **[noop]** | 4.08 / 4.08 ms (2f) **[noop]** | 16.85 / 16.85 ms (1f) **[noop]** | 5.14 / 5.14 ms (2f) **[noop]** |
| joybrush.sable | 4.33 / 4.33 ms (2f) **[noop]** | 4.65 / 4.65 ms (2f) **[noop]** | 24.4 / 24.4 ms (1f) **[noop]** | 3.94 / 3.94 ms (2f) **[noop]** |
| joybrush.pencil | 2.95 / 2.95 ms (2f) **[noop]** | 15.16 / 15.16 ms (1f) **[noop]** | 5.13 / 5.13 ms (2f) **[noop]** | 4.77 / 4.77 ms (2f) **[noop]** |
| joybrush.bristle | 4.88 / 4.88 ms (2f) **[noop]** | 22.28 / 22.28 ms (1f) **[noop]** | 4.28 / 4.28 ms (2f) **[noop]** | 4.53 / 4.53 ms (2f) **[noop]** |
| joybrush.flatpaint | 2.83 / 2.83 ms (2f) **[noop]** | 4.53 / 4.53 ms (2f) **[noop]** | 5.04 / 5.04 ms (2f) **[noop]** | 3.91 / 3.91 ms (2f) **[noop]** |
| joybrush.marker | 4.42 / 4.42 ms (2f) **[noop]** | 3.9 / 3.9 ms (2f) **[noop]** | 16.91 / 16.91 ms (1f) **[noop]** | 6.03 / 6.03 ms (2f) **[noop]** |
| joybrush.softair | 3.88 / 3.88 ms (2f) **[noop]** | 3.6 / 3.6 ms (2f) **[noop]** | 3.5 / 3.5 ms (2f) **[noop]** | 2.76 / 2.76 ms (2f) **[noop]** |
| joybrush.smudge | 4.8 / 4.8 ms (2f) **[noop]** | 4.84 / 4.84 ms (2f) **[noop]** | 20.85 / 20.85 ms (1f) **[noop]** | 4.44 / 4.44 ms (2f) **[noop]** |
| joybrush.eraser | 4.15 / 4.15 ms (2f) **[noop]** | 3.65 / 3.65 ms (2f) **[noop]** | 3.45 / 3.45 ms (2f) **[noop]** | 4.08 / 4.08 ms (2f) **[noop]** |
| fill | 1.63 / 1.63 ms (2f) **[noop]** | 19.17 / 19.17 ms (1f) **[noop]** | 4.96 / 4.96 ms (2f) **[noop]** | 18.99 / 18.99 ms (1f) **[noop]** |
| joybrush.watercolour | 3.32 / 3.32 ms (2f) **[noop]** | 18.04 / 18.04 ms (1f) **[noop]** | 4.92 / 4.92 ms (2f) **[noop]** | 5.8 / 5.8 ms (2f) **[noop]** |
| media:dry:Proto Pencil | 3.61 / 3.61 ms (2f) **[noop]** | 4.77 / 4.77 ms (2f) **[noop]** | 4.26 / 4.26 ms (2f) **[noop]** | 3.83 / 3.83 ms (2f) **[noop]** |
| media:dry:Proto Mix | 4.45 / 4.45 ms (2f) **[noop]** | 5.8 / 5.8 ms (2f) **[noop]** | 23.01 / 23.01 ms (1f) **[noop]** | 4.21 / 4.21 ms (2f) **[noop]** |
| media:dry:Proto | 5.06 / 5.06 ms (2f) **[noop]** | 4.32 / 4.32 ms (2f) **[noop]** | 5.64 / 5.64 ms (2f) **[noop]** | 4.15 / 4.15 ms (2f) **[noop]** |
| media:dry:Proto 2 | 3.43 / 3.43 ms (2f) **[noop]** | 3.56 / 3.56 ms (2f) **[noop]** | 4.19 / 4.19 ms (2f) **[noop]** | 5.53 / 5.53 ms (2f) **[noop]** |
| media:dry:2H | 3.92 / 3.92 ms (2f) **[noop]** | 3.02 / 3.02 ms (2f) **[noop]** | 14.43 / 14.43 ms (1f) **[noop]** | 5.01 / 5.01 ms (2f) **[noop]** |
| media:dry:HB | 2.41 / 2.41 ms (2f) **[noop]** | 4.7 / 4.7 ms (2f) **[noop]** | 5.5 / 5.5 ms (2f) **[noop]** | 4.5 / 4.5 ms (2f) **[noop]** |
| media:dry:2B | 16.87 / 16.87 ms (1f) **[noop]** | 3.95 / 3.95 ms (2f) **[noop]** | 16.68 / 16.68 ms (1f) **[noop]** | 4.29 / 4.29 ms (2f) **[noop]** |
| media:dry:6B | 25.81 / 25.81 ms (1f) **[noop]** | 4.19 / 4.19 ms (2f) **[noop]** | 5.2 / 5.2 ms (2f) **[noop]** | 4.16 / 4.16 ms (2f) **[noop]** |
| media:dry:Woodless 8B | 4.56 / 4.56 ms (2f) **[noop]** | 16.82 / 16.82 ms (1f) **[noop]** | 4.95 / 4.95 ms (2f) **[noop]** | 3.46 / 3.46 ms (2f) **[noop]** |
| media:wet:All-round | 3.65 / 3.65 ms (2f) **[noop]** | 4.21 / 4.21 ms (2f) **[noop]** | 17.7 / 17.7 ms (1f) **[noop]** | 3.48 / 3.48 ms (2f) **[noop]** |
| media:wet:Round | 3.7 / 3.7 ms (2f) **[noop]** | 2.8 / 2.8 ms (2f) **[noop]** | 2.83 / 2.83 ms (2f) **[noop]** | 3.9 / 3.9 ms (2f) **[noop]** |
| media:wet:Wash | 5.09 / 5.09 ms (2f) **[noop]** | 3.35 / 3.35 ms (2f) **[noop]** | 16.46 / 16.46 ms (1f) **[noop]** | 5.27 / 5.27 ms (2f) **[noop]** |
| media:wet:Dry brush | 5.69 / 5.69 ms (2f) **[noop]** | 2.44 / 2.44 ms (2f) **[noop]** | 11.18 / 11.18 ms (1f) **[noop]** | 4.58 / 4.58 ms (2f) **[noop]** |
| media:wet:Water | 3.93 / 3.93 ms (2f) **[noop]** | 5.07 / 5.07 ms (2f) **[noop]** | 3.76 / 3.76 ms (2f) **[noop]** | 5.43 / 5.43 ms (2f) **[noop]** |
| media:wet:Thirsty | 5.61 / 5.61 ms (2f) **[noop]** | 3.68 / 3.68 ms (2f) **[noop]** | 18.52 / 18.52 ms (1f) **[noop]** | 4.01 / 4.01 ms (2f) **[noop]** |
| media:paste:All-round | 4.62 / 4.62 ms (2f) **[noop]** | 4.05 / 4.05 ms (2f) **[noop]** | 5.94 / 5.94 ms (2f) **[noop]** | 7.32 / 7.32 ms (2f) **[noop]** |
| media:paste:Oil flat | 21.84 / 21.84 ms (1f) **[noop]** | 2.8 / 2.8 ms (2f) **[noop]** | 4.92 / 4.92 ms (2f) **[noop]** | 5.14 / 5.14 ms (2f) **[noop]** |
| media:paste:Oil round | 4.44 / 4.44 ms (2f) **[noop]** | 16.54 / 16.54 ms (1f) **[noop]** | 5.03 / 5.03 ms (2f) **[noop]** | 3.17 / 3.17 ms (2f) **[noop]** |
| media:paste:Fan blender | 5.14 / 5.14 ms (2f) **[noop]** | 4.22 / 4.22 ms (2f) **[noop]** | 16.49 / 16.49 ms (1f) **[noop]** | 5.5 / 5.5 ms (2f) **[noop]** |
| media:paste:Palette knife 1 | 4 / 4 ms (2f) **[noop]** | 16.98 / 16.98 ms (1f) **[noop]** | 17.61 / 17.61 ms (1f) **[noop]** | 3.97 / 3.97 ms (2f) **[noop]** |
| media:paste:Palette knife 2 | 4.26 / 4.26 ms (2f) **[noop]** | 3.46 / 3.46 ms (2f) **[noop]** | 5.05 / 5.05 ms (2f) **[noop]** | 2.56 / 2.56 ms (2f) **[noop]** |
| media:paste:Scraper | 22.41 / 22.41 ms (1f) **[noop]** | 3.92 / 3.92 ms (2f) **[noop]** | 24.33 / 24.33 ms (1f) **[noop]** | 3.44 / 3.44 ms (2f) **[noop]** |

## Ten slowest cells (by p95)

These are slow only as no-op strokes; they do not show brush cost.

```
media:dry:6B | canvas | p50 25.81 p95 25.81 frames 1 | noop
joybrush.sable | tile | p50 24.4 p95 24.4 frames 1 | noop
media:paste:Scraper | tile | p50 24.33 p95 24.33 frames 1 | noop
media:dry:Proto Mix | tile | p50 23.01 p95 23.01 frames 1 | noop
media:paste:Scraper | canvas | p50 22.41 p95 22.41 frames 1 | noop
joybrush.bristle | animation | p50 22.28 p95 22.28 frames 1 | noop
media:paste:Oil flat | canvas | p50 21.84 p95 21.84 frames 1 | noop
joybrush.smudge | tile | p50 20.85 p95 20.85 frames 1 | noop
fill | animation | p50 19.17 p95 19.17 frames 1 | noop
fill | sprite | p50 18.99 p95 18.99 frames 1 | noop
```

## Failed, refused, or errored cells

- Refused: 0
- Errored: 0
- Verdict `noop`: 132 of 132 cells (changedPx 0, changedTiles 0, undoSteps 0, undoExact null)
- Cells started without a result: 0
- Every cell settled (`settled: true`)
- Frames per stroke: 1 or 2 in every cell, for a stroke about 600 ms long

## Crash stack

None. No `FATAL EXCEPTION` in JoyBrushPerf or AndroidRuntime during the run.

## Observations (unverified)

- The stroke frames count (1 or 2) against a 600 ms stroke suggests the replayed touches did not reach the drawing surface. Not verified; no test was run to confirm.
- Logcat during the run had one warning when the run began: `WindowManager: preserveSurfaceLocked: failed, no surface` for the JoyBrushActivity window (22:45:13).
- Next step (not done): find out why the stroke replay changes nothing, then re-run this matrix.

Raw JSON: `tasks/joybrush/reviews/DEVICE_MATRIX_20261017.jsonl` (132 cell lines, plus the `plan` and `done` lines).
