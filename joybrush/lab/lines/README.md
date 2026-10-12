# Line Lab: pencil and pen test sheets drawn with the app's real shaders

A JVM test writes each brush's strokes as the exact numbers the phone's GPU path is handed; a node script draws them
headless (Edge + SwiftShader) with `jb_dab.vert/frag` and `jb_tile.vert` + `jb_commit.frag`. No shader or app code is touched.

1. Sheets, for the shipped Pencil and Ink plus every `presets/*.json` you drop in this folder (brush files, any version):
   `.\gradlew.bat --no-watch-fs -p joybrush :core:jvmTest --tests "*LineLabSheetTest*" --rerun`
   -> `joybrush/core/build/linelab/<name>.json` and `summary.txt` (azimuth, paper). `--rerun` is needed after editing a preset.
   Paper: `LINELAB_PAPER=pulp_artisan` (default, the Pencil's designed paper) | any surface id | `none` (the app's smooth default).
2. Pictures (`NODE_PATH` = a node_modules holding playwright-core; `CHROME=` overrides the browser):
   `node joybrush/lab/lines/render_sheet.js joybrush/core/build/linelab/pencil.json joybrush/lab/lines/out/pencil.png`
   `   [--crop 1190,95,780,1050,3 --crop-out joybrush/lab/lines/out/pencil_tilt_crop.png]` (nearest-neighbour "magnified pixels")
   `bash joybrush/lab/lines/render_all.sh` draws every sheet and the crop. `out/` is git-ignored.
   `--shaders <dir>` draws with another branch's shader folder. A preset with `extensions.deposit = "tooth"` sets `u_grainCapacity` 1.

Sheet 2048x1280 px (20 px/mm), pen at 240 Hz and 2000 px/s, leaning to the page's left (azimuth pi), black ink on white:
- A top-left: ten 600 px diagonals (30 deg below horizontal), pressure 0.1 ... 1.0 left to right, tilt 0.15 rad
- B top-right: six 500 px verticals, pressure 0.5, tilt 0, 15, 30, 45, 60, 70 deg left to right
- C under A: pressure swell 0-1-0; upper tilt 0.15 rad, lower tilt 55 deg
- D under B: one zigzag, 8 legs of 300 px turning 150 deg, tilt 55 deg, pressure 0.6
- E under C: three sine waves (period 180 px), pressure 0.2 / 0.5 / 0.9 top to bottom, tilt 0.15 rad
- F bottom, right of E: 12 hatching strokes, 10 px apart, alternating direction, pressure 0.5, tilt 30 deg

The renderer throws on a GL error, a stroke that draws or changes nothing, or a NaN.
