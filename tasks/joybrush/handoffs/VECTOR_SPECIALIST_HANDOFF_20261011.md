# Handoff: Joy Brush vector brush specialist (2026-10-11)

Paste this whole file into a new Opus session. You are the **vector brush specialist**. Read
`tasks/joybrush/briefs/VECTOR_SPECIALIST_BRIEF.md` (your brief), then `tasks/joybrush/specs/VECTOR_RENDER_DESIGN.md`
(your approved design, §1–§9), then this file.

## Who you work with
- **The lead** is the Claude session "Joybrush orchestration and completion". Talk to it with SendMessage; ListAgents finds
  it. It reviews and merges every branch, owns `GlPaintEngine.kt` / `JbCanvasView.kt` (you got a one-time permission for
  H1 only), media/watercolour engines, the phone, and installs. Fallback channel: `tasks/joybrush/AGENT_BOARD.md`.
- **The owner (JoyRaptor)** is an artist, not a developer, and works one-handed around childcare. Report in plain
  language and emoji tables. Show him pictures, never code. Never install on the phone yourself.
- His competitor forensics are reliable: restate his model before designing (see `tasks/lessons.md`, October 11).

## Rules that bit this session
- **Work only in your own worktrees** under `C:/Temp/`. Never edit or build in `C:/+Projects/Screenrecorder/FadCam`.
- **Builds:** only through `C:/Users/JOYRAP~1/AppData/Local/Temp/claude/C---Projects-Screenrecorder-FadCam/f342c1ac-fa64-476d-bf1e-68bea4f52f35/scratchpad/jbgradle2.sh <worktree> <tasks…>`.
  - It checks free RAM BEFORE taking `C:/Users/JoyRaptor/AppData/Local/Temp/jb-gradle.lock`.
  - It waits while `jb-gradle.vector-hold` exists (the lead creates that file to get priority).
  - `jbgradle_root.sh` is the same script without `-p joybrush`, for `:app:assembleDefaultDebug`.
  - **Copy both scripts into your own scratchpad**: the paths above belong to this session.
  - Prefer targeted `--tests`; one heavy build at a time on this 16 GB PC.
- **GPU checkers:** `export NODE_PATH=/c/Users/JoyRaptor/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules`,
  then `node joybrush/tools/<check>.js`. `shader_check.js` needs `:core:jvmTest` to have run first in that worktree
  (it reads fixtures from `core/build`). `contact_check.js` FAILS ON MAIN TOO ("Live per-dab depth {light: 0.729}"):
  pre-existing and known; compare its output before and after, not pass/fail.
- **Never resolve a merge conflict.** Re-author your own change on the new base instead (that is how `vector/lines-stack`
  landed). Rebase onto `origin/joy-creator` before pushing.
- **Never globally flip the pen's tilt.** See the memory note on lean conventions and the section below.
- Helpers die on API usage limits. Make them commit as they go, and checkpoint their worktrees before you stop.

## What is DONE and merged (origin/joy-creator @ 76188061)
| Piece | Where |
|---|---|
| Design note §1–§8, approved by the lead | `specs/VECTOR_RENDER_DESIGN.md` |
| Line maths: taper (shape, final width), dashes (dot rule), wire width, deep-zoom densify | `core/vector/LineShaper.kt` |
| Sandwich drag cache (CPU): normal/erase/behind/multiply/screen collapse to K∘D + u·αD + c | `core/vector/LineSandwich.kt` |
| Brush v9: `line` section (`LineStyleSpec`), blends `multiply` / `screen` (stamp only), validation rule 28; replay applies taper and dashes before scatter | `core/brush/*`, `InkReplay.replay()` |
| GPU commit `u_blend`; engine mapping; multiply/screen refused in words on a mask | `jb_commit.frag`, H6 patch applied by the lead |
| Five replay/export fixes (H7): scatter tilt, NaN widthScale = 1, first-dab hardness, paper-grain `Omission`, DabInputs tripwire | `InkReplay`, `InkTiles`, tests |
| B1 bench: a 1,500 px ink line replays in 2.5 ms on desktop JVM | `jvmTest/vector/InkReplayBenchTest.kt` |
| `tools/commit_blend_check.js` (GPU, mutation-tested) | tools |

## What is PUSHED, not merged (your queue, in order)

1. **`vector/stamp-pass` @ aea246a7: H1, the shared stamp draw (your top priority).**
   - Contents: `gl/StampPass.kt` (verbatim move + `u_scale`), `GlPaintEngine` calling it, `jb_dab.vert` `u_scale`.
   - Done: `StampPassUniformsParityTest` (bit-identical over 600 random strokes); `tools/stamp_pass_check.js` (old vs new
     shader byte-identical, unset = 1, split invariance, scale 2; both controls fail as they must); androidkit tests
     374/0.
   - **Still owed before pinging the lead:**
     - (a) the full `:core:jvmTest` in `C:/Temp/jb-vector-h1`;
     - (b) then `node tools/shader_check.js`;
     - (c) `contact_check.js`: same output as main;
     - (d) `:app:assembleDefaultDebug` via `jbgradle_root.sh`;
     - (e) rebase onto main;
     - (f) SendMessage the lead the hash and evidence. They A/B ink, pencil and marker on the Note 9, then merge.
   - One semantic note for the lead: `look.fluidTexture` is captured at pen-down; it used to be read per batch. That
     only differs if the paper changes mid-stroke.
2. **`vector/docs-and-export` @ 64454f6f** (docs only, mergeable):
   - the JB-5.01 Decision/test 9 + R44.3 amendment;
   - R12 (the owner's Concepts watercolour research) and design §9, CORRECTED to his model;
   - a lessons.md entry.
   - Later on this branch: lead item (c), surfacing `InkTiles.Omission` ("pencil drawn without paper grain") as a plain
     sentence in RegionRenderer/OraExport export results, never a silent drop.
3. **`vector/pencil` @ 6266f1e1: "tooth capacity" deposit mode (WIP, unverified).**
   - `jb_contactParts` / `jb_contactRateAndTooth`, plus `u_grainCapacity` in `jb_dab.frag`, default off.
   - Based on the OLD brush-format branch, so rebase it.
   - Theory: today grain multiplies dab COVERAGE, so 12–40 overlapping dabs saturate every touched pixel. The grain goes
     binary and the tone follows overlap, not pressure. Capacity mode puts grain in each pixel's ceiling instead.
4. **`vector/line-lab` @ 7366c304: the line lab (helper checkpoint, unverified).**
   - A JVM sheet writer (`jvmTest/vector/LineLabSheetTest.kt`) writes `core/build/linelab/<preset>.json`.
   - `joybrush/lab/lines/render_sheet.js` renders a sheet with the app's real shaders.
   - Presets: `lab/lines/presets/pencil_p1.json` (shipped pencil + `extensions.deposit = "tooth"`) and `pencil_p2.json`
     (Proko-tuned).
   - Asked of the helper but maybe not finished: honour `deposit: tooth` → `u_grainCapacity`, and add a
     `--shaders <dir>` option.
   - Goal output: `C:/Temp/jb-linelab-out/` with the shipped pencil, P1 and P2 rendered with the vector/pencil shaders.
5. **`vector/svg-export` @ 2aa7730c: SVG, one outline per stroke (helper checkpoint, unverified, based on an old main).**
   - Files: `core/vector/svg/` (StrokeOutline envelope of dab tips, CurveFit, SvgWriter, LineSvg).
   - Owed: tests (IoU ≥ 0.99 against the dab raster), a rebase, a sample SVG.

## The owner's live asks (most important first)
1. **A vector pencil that beats Infinite Painter's Proko pencil.** Point mode: only slightly thicker with pressure, much
   darker. Tilt: a wide band, dark at the contact ("the source"), fading smoothly across, with a fine even grain that
   fills in with pressure. Zigzag turns darken where they overlap. His Oct 6 notes are in commit 0b79b040's message.
   - Plan: render P0/P1/P2 in the lab, look at them yourself against his reference shots (the session's images 2 and 3),
     iterate, then send him one PNG sheet at 1:1 and at 8× with SendUserFile. The gradient sign stays positive (stamp
     convention). Then propose the deposit mode to the lead as a brush word.
2. **Watercolour line brushes like Concepts' Waterful pack**, which he likes for H2, H3, F1 and A1. His model (R12, design
   §9):
   - rotated dab samples chosen by pressure and tilt;
   - darker-wins combine inside a stroke;
   - a wash texture as the stroke's alpha;
   - each stroke composites as its own layer.
   - Next step is a design note to the lead for the engine hooks: accumulate "max", an image-tip array, the wash sampler
     at commit, and a line frame in the record.
   - Samples must be ours (simulation bakes, his photos). Asked and unanswered: will he photograph real dabs and washes?
3. **V4: the 10 line presets** (design §2) as JSON in `joybrush/brushes/lines/`, plus a picture sheet for him.

## Settled findings to not re-learn
- **Tilt lean conventions differ.**
  - Stamp/Sable use `PenSample.azimuth` = AXIS_ORIENTATION − π/2 with no flip, tuned on the phone.
  - The media engines came from a browser lab with barrel-direction azimuth, so only the media brushes are 180° off.
  - That fix (+π in `MediaInput`) is the lead's. Branch `vector/tilt-flip` (local only, in `C:/Temp/jb-vector-tilt`) is
    DEAD: do not push it.
- Phone tilt for watercolour (gravity sensor + corner tilt pad, plan row M5.6) is unbuilt and is the lead's.
- 20 doc px per mm; 256 px tiles; the display magnifies tiles bilinearly with no culling (lead has H8).

## Worktrees (all under C:/Temp)
| Worktree | Branch | State |
|---|---|---|
| `jb-vector-h1` | `vector/stamp-pass` | H1; verification owed (above) |
| `jb-vector-stack` | `vector/docs-and-export` | docs; next: Omission surfacing |
| `jb-vector` | `vector/pencil` | pencil shader WIP |
| `jb-vector-lab` | `vector/line-lab` | helper's lab |
| `jb-vector-svg` | `vector/svg-export` | helper's SVG |
| `jb-vector-tilt` | `vector/tilt-flip` | dead; delete when convenient |
