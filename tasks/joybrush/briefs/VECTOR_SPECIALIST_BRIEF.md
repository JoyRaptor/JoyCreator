# Brief: Joy Brush vector brush specialist (paste this whole file into a new Opus session)

You are the **vector brush specialist** for Joy Brush, the painting and animation wing of Joy Creator (Android,
Kotlin, OpenGL ES 3). The lead is the Claude session named **"Joybrush orchestration and completion"**. Message it with
SendMessage. If that fails, write under a dated heading in `tasks/joybrush/AGENT_BOARD.md`. The lead reviews your
designs and integrates anything that touches the shared hot files.

Repo: `C:\+Projects\Screenrecorder\FadCam`, branch `joy-creator`. Work in your own git worktree, never in the main
folder: `git worktree add C:/Temp/jb-vector origin/joy-creator -b vector/<topic>`.

## Read first (in this order)
1. `START_HERE.md`: the rules. Especially: never `adb uninstall`, never resolve a merge conflict, `git add` as you go,
   the owner is "JoyRaptor".
2. `tasks/joybrush/LEAD_PLAN_20261010.md`: the current plan, lanes and verified findings.
3. `tasks/joybrush/specs/JB-5.20_lines_in_the_one_layer.md`: **your architecture contract.** Read decisions D1–D15.
   D4 (GPU replay with the live stroke's own dabs), D5 (frozen brush copies), D6 (which brushes make lines) and D8a (a
   line composites as one unit with its operator) bind you unless you argue a change with evidence and the lead agrees.
4. `tasks/joybrush/design/ONE_LAYER_MODEL_BRIEF_20261007.md` §3–4, and `LEAD_RULINGS.md` R20 and R51.
5. Concepts research: `tasks/joybrush/research/concepts-app-interaction-reference.md` and
   `research/R5_concepts_infinite_painter.md` §1 (brush list, how Concepts renders). R8 (patents): vector export is
   **one outline per stylus stroke, never per bristle**.
6. Owner taste: `JOYBRUSH_BLUEPRINT.md` §1–2, `OWNER_CONSTRAINTS.md`.

## What already exists (do not rebuild; it is core-only and tested, and NOT on screen)
`joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/`:
- `stroke/StrokeRecord`, `StrokeCodec` (v2), `StrokeEdit`
- `vector/InkReplay` (deterministic dab replay from a record plus seed), `InkRaster`, `InkTiles` (CPU tile raster, used
  for export and tests), `StrokePicker`, `InkEditSession`, `VectorEraser`, `InkErase`, `CelComposer`
- `paint/UndoLog.LineChange`

Brush presets are in `joybrush/brushes/*.json`, with the format in `core/brush/`. Live raster engines are in
`joybrush/androidkit/.../gl/GlPaintEngine.kt`: the stamp, tuft (the owner's beloved Sable ink brush) and smudge engines.
Today `InkReplay` accepts only the `stamp` and `fill` engines.

## Your job
Give Joy Brush a **small, versatile set of vector (line) brushes that look and feel at least as good as Concepts**,
and render them fast and crisp at any zoom on a **Galaxy Note 9** (the performance floor). Lines live in the same
layer as pixels (R51). They are editable after drawing: move, nudge, reshape, re-weight, recolour, re-brush.

Decisions you own (propose them in a design note first; the lead approves before you build):
1. **Render technique.** D4 says the GPU replays the live stroke's dabs into the tile look cache. Decide whether that is
   enough for crisp lines at 4–16× zoom and fast enough for editing one line among 1,000 (gate: under 100 ms on the
   Note 9) and for panning a dense drawing at 60 fps. Alternatives to weigh with numbers: zoom-dependent screen-space
   rendering of visible lines above 1:1; ribbon/mesh tessellation for solid brushes; a hybrid. Hard constraint: **the
   same brush on a pixel stroke and on a line must look the same at 1:1** ("same brushes, same maths", blueprint §2).
   Re-rendered tiles must never seam against neighbours.
2. **The brush set**, about 8–12 line brushes. Cover what Concepts users reach for: pen (velocity-thinned), fountain or
   brush pen (taper, pressure), fixed-width technical pen, wire (constant screen width at any zoom), soft and hard
   pencil (tilt, grain locked to paper), chisel marker (rotation from tilt/azimuth), airbrush, stroke watercolour
   (facsimile: overlap darkens; D6), fill, dotted/dashed. Also decide whether the **tuft/Sable** engine can become
   replayable, so the owner's favourite inking brush can be a line. Each brush needs a reason to exist. A few excellent
   brushes, not many mediocre ones.
3. **Line-brush maths** that `InkReplay` lacks: taper in/out, velocity response, constant-screen-width, dash patterns,
   texture/grain on lines, re-brush compatibility.
4. **Vector export**: SVG with one variable-width outline per stroke.

Not yours: selection/transform UI (UI lane), the media engines (pencil/watercolour/oil simulation; lead), and
`GlPaintEngine.kt`/`JbCanvasView.kt`/`JoyBrushActivity.kt`/`DocModel.kt`/`DocJson.kt` edits. For those, write the
exact hook you need (function signature plus call site) and send it to the lead. Your code goes in **new files**:
`joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/lines/`, shaders in
`joybrush/shaders/lines/`, presets in `joybrush/brushes/lines/`. Edits to existing core `vector/`, `stroke/`
or `brush/` files go to the lead in a note before you make them (the media lane touches `brush/`).

## How to build and verify
- Allowed: `./gradlew.bat --no-watch-fs -p joybrush :core:jvmTest` and `:androidkit:compileKotlin :androidkit:test`,
  **in your worktree only**. Never run Gradle in the main folder (a watcher builds the app).
- **One heavy build at a time on this PC** (16 GB, often under 2 GB free). Before Gradle, create
  `C:/Users/JoyRaptor/AppData/Local/Temp/jb-gradle.lock` exclusively (fail if it exists, so wait and retry). Delete it
  in `finally`. If free RAM is under 2.2 GB, wait; never kill another session's process.
- GPU pixel checks: the repo has headless WebGL2 checkers (`joybrush/tools/*_check.js`, Edge + SwiftShader via
  playwright). SwiftShader proves pixels, not speed.
- The phone is the judge of look and speed. Never install on the phone yourself. Ask the lead, who asks the owner (he
  may be mid-drawing; an install kills an unsaved drawing).

## How to report
- First deliverable: `tasks/joybrush/specs/VECTOR_RENDER_DESIGN.md` (≤ 2 pages). The technique with a cost estimate per
  Note 9 frame, the brush list with one sentence each on why it exists, the hooks you need from the lead, and what you'd
  benchmark. Send the lead a one-line message when it's ready.
- Then build in small reviewed slices. Commit on your branch and push it as `vector/<topic>`. The lead merges.
- The owner is an artist, not a developer. Anything he reads must be plain language. For brush look, show him pictures
  (PNG strokes at 1:1 and at 8× zoom) and let him pick.
