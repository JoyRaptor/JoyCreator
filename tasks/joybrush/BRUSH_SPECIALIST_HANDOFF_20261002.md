# Brush specialist handoff — October 2

Owner direction: finish a usable test slice with the remaining weekly usage. OpenCode agents are explicitly authorized to continue remaining brush work.

Branch: `codex/brush-contact`. Isolated worktree: `%TEMP%/jb-brush-specialist`. Base: `d6110150` (landed paper surface, directional/wet deposition and export parity). Do not merge the divergent main checkout by force or overwrite the Lead's newer paper/UI work.

Implemented:
- Brush v7 live aspect, anchored contact, hardness and tip/paper depth. Older brushes retain their prior uniform behavior.
- Pencil: upright point, long narrow side around 45 degrees, pressure-sensitive tooth reach and dark near edge/faint far body. Azimuth follows lean; missing tilt gives upright detail.
- Bristle: rough/dry counterpart to Sable, preserving Sable's existing deformation engine.
- Flat Paint: pressure/tilt detail-to-belly contact, shared paper response, real local colour-fragment pickup from the pen-down canvas, painting onto bare canvas and erasing.
- Stateless local spatial pickup; no second colour reservoir. Nine existing neighboring textures provide seamless pickup without GPU readback or additional canvas storage. Paint moves at most half a tile per dab. It does not feed paint deposited earlier in the same stroke back into pickup.
- Standalone `tools/brushlab/testapp`: production JoyBrushActivity and libraries, separate `cc.joycreator.joybrush.brushtest` package. It opens directly onto the canvas. It has separate drawing storage from Joy Creator.

Checks and artifacts: final results are appended below after packaging. Build logs are local `brush-build.log` and `brush-apk.log`; do not commit them. Run all builds from the worktree with `--no-daemon` and atomic ownership of `%TEMP%/jb-gradle.lock`; acquisition errors must stop the script, and cleanup must remove only your own token.

Changed engine paths: core BrushPreset/Json/Validate/Dabber, Dab/Placer/TipMath/Tiles/RefCanvas/InkRaster/SmudgeStroke/PaintPickup; GPU GlPaintEngine instance layouts and neighbor binding; JbCanvasView only the SmudgeParams construction; shaders jb_contact/jb_dab/jb_smudge_dab/jb_commit. The Lead's Activity and paper metadata/history code are not edited. Review these small seams against the newer Lead branch before integration.

Remaining:
1. Owner Note 9 feel check: Pencil upright→45° shading→upright without resizing; soft vs firm pressure, reverse lean, thin detail. Compare with Infinite Painter Proko; tune values from actual feedback.
2. Flat Paint across separate red/blue marks; confirm pickup detail, bare-canvas paint, erase/undo/save/reopen and responsiveness. GPU assertions cover colour separation and positive/negative neighbor crossings, but phone feel is still required.
3. Integrate with Lead's newer paper UI/schema branch. Keep paper metadata, chronological undo and context seeds intact. No artwork migration.
4. PC BrushLab remains a legacy prototype and does not model these new contact fields faithfully. Do not use it to retune v7 files until its evaluator/rendering is updated to the production contracts.
5. Real fluid watercolour (JB-6.01/6.02), impasto/pigment mixing and within-stroke paint propagation remain unbuilt. Target Expresii for watercolour, Rebelle for oils; consider Procreate, Concepts Soft Pencil/Waterful and the curated Krita brushes. Do not mark those roadmap rows complete because the current paper response looks wet.

Research: `research/R11_adaptive_brush_competition.md`. Pencil gradation already exists in Infinite Painter; our improvement must be judged by long contact, live tooth response and actual feel, not a uniqueness claim.

## Verified result
`gradlew.bat --no-daemon --no-watch-fs -p joybrush :core:jvmTest :androidkit:compileKotlin :androidkit:test`: core 1499 tests and androidkit 225 tests, zero failures/errors/skips. `node joybrush/tools/contact_check.js`: live geometry/tooth, red-blue spatial separation, positive/negative neighboring tile pickup, paint-on-empty and erase pass. `node joybrush/tools/shader_check.js`: legacy wash/smudge, paper deposition and display/export parity pass, GL error 0. Packaging and phone status follow below. No owner feel sign-off and no fluid-watercolour claim.