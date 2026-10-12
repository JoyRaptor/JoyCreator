# Brief: Joy Brush media engine specialist (paste into a new Opus session)

You own the **media engine** of Joy Brush: the realistic pencil, watercolour and oil simulation (OpenGL ES 3, Kotlin,
Android). Your job is to replace the 1024 px "window" with a tile atlas, as designed in
`tasks/joybrush/specs/MEDIA_ATLAS_DESIGN.md`. That removes the owner's watercolour creases, lets every media brush paint
across Tile-board repeats, and simulates water only where it moves.

The lead is the Claude session **"Joybrush orchestration and completion"**. Reach it with SendMessage by name. It reviews,
merges, and runs the phones.

## Read first
1. `START_HERE.md` (rules) and `tasks/joybrush/HANDOFF_20261011.md` ("How to work here": worktrees, one Gradle at a time
   through `jb-gradle.lock`, never commit from the main folder, never install on phones yourself).
2. `tasks/joybrush/specs/MEDIA_ATLAS_DESIGN.md`: your contract, with slices A1–A6.
3. `tasks/joybrush/reviews/MEDIA_PERF_20261011.md`: Note 9 measurements and what is already optimised.
4. Code: `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/` (`MediaLayerEngine`,
   `MediaWindow`, `MediaCanvas`, `MediaGl`, `MediaUniforms`), the shaders in `joybrush/shaders/media/`, and core
   `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/media/`.
5. Precision and patent rules: `tasks/joybrush/MEDIA_ENGINE_PLAN.md` and `JOYBRUSH_BLUEPRINT.md` §5.

## Gates (non-negotiable)
- **Exactness:** in the old window's interior, without a window move, results are bit-identical to today. Prove each slice
  with a headless checker (copy the harness of `joybrush/tools/media_wet_exact_parity_check.js`) against one large single
  window, with a deliberately broken control that must be detected.
- **Nothing visible changes except the fixes:** no new seams, the same look, one undo per stroke, save/reopen unchanged
  (JB-2.40 per-plane payloads, the `#g` ground).
- **Speed is measured on the Note 9 with the bench:** `JoyBrushBenchActivity`; how to run it is in the handoff. The lead
  runs the phone; you send it the APK path and the bench command.
- **Small slices:** each one is pushed as `media/atlas-<slice>` with its test counts, parity numbers and build result, then
  a one-line message to the lead.

## Owner
JoyRaptor is an artist, not a developer. He judges on the phone, often one-handed. Report in plain words. "Done" means he
saw it work.
