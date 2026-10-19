# JoyBrush Lead handoff, 2026-10-19

Read this first, then `specs/SYMMETRY_20261019.md` (the owner's newest request) and `tasks/lessons.md`.

## Where things are

- **main (origin/joy-creator):** b00b82667 plus this handoff commit. It's on the Note 9 and green: core 2515, androidkit 1050, app 317.
- What landed 10-18/19:
  - layer Lock / Merge Down / Flatten = freeze / Freeze layer (LAYER_OPS §2–§4);
  - whole-drawing export, flat PNG or layered ORA, remembered (⋯ → Export…);
  - drawings with editable lines now export (`ExportPicture.asShown`);
  - Water test switch (⋯ → Water test: Today / A brush first / S sleeps / flat / flat no relief / all);
  - window patch: no stroke loses its tail; at most 8 parts a frame;
  - media prewarm on every canvas surface (no first-stroke hitch after an install);
  - audit P1 fixes (doc ids, media leak, erased mask tiles);
  - bench extras jb_water, jb_media_atlas, jb_start_ms.
- **Integration worktree:** C:/Temp/jb-integ-fix (branch lead/integration-1018-fix). Locally it has the helper's diagnostic timing merge (73c691498), NOT pushed. Its 22-brush zero-cost phone check was pending when this session ended. Merge origin/joy-creator into it before pushing (this handoff commit went straight to main).
- **Scripts:** tasks/joybrush/lead-scripts/ (copied from the lead's scratchpad; `S` = the script folder):
  - lead-build.sh: machine-wide Gradle lock at %LOCALAPPDATA%/Temp/jb-gradle.lock, plus a free-RAM gate;
  - full-then-apk.sh: core + androidkit + app suites, then apk-install;
  - apk-install.sh: phone lock, HOME first, `install -r`, launcher start, screenshot;
  - install-apk.sh `<apk>`;
  - build-main-apk.sh: keeps the integration APK, builds main's.
  APKs and screenshots land in that folder and are git-ignored.

## Lanes

- **Opus helper** (session local_76d7423a…, branch helper1/media-atlas-b1): Tile board speed for ordinary brushes (pencil, softair, marker, bristle, flatpaint and legacy watercolour run 2–10× slower on tile than on canvas; see reviews/DEVICE_MATRIX_20261017.md). Step 1 is diagnostic timing (73c691498), then a profile and a plan BEFORE any fix. It may edit the tile paths in GlPaintEngine; the lead stays out of them.
- **Atlas: PARKED** (off by default). Tilted water flux stays 3–5× the window's. Everything it fixed is kept.
- **Codex:** Unibrush / Digital Pen (briefs/CODEX_DIGITAL_PEN_BRIEF_20261018.md) and Animation clips C1–C8 in C:/Temp/jb-clips (its lane note says the owner asked). Stay out of jb-clips.

## Waiting on the owner

1. Try Water test "A · brush first" against "Today". If he likes A, making it the default is the biggest pen-down win (~35% faster).
2. Whole-screen patch: lead recommends SKIP for now (explained to him 10-19: ~3× graphics memory, no help zoomed out, risk). Note: symmetry with watercolour/oil (spec E4) needs it.
3. Flatten "Keep lines editable" checkbox: asked; no answer yet.
4. Symmetry questions Q1–Q6 in the spec.
5. Older: pencil shelf name (TrueTooth vs Proto); Flatten blend-over-paper.

## Rules learned 10-18/19 (keep them)

- A media / window change reaches main only after the FULL list runs on the Note 9 in the same hour as a main baseline: 22 brushes flat + 6 wet tilted + wideFlick. The lead must look at the screenshots himself. Watercolour-only checks let a 45 s palette-knife freeze through (reverted, c93a200d5).
- Same-hour baselines: single runs vary ±20%. Compare against main measured in the same hour.
- To undo a bad push: `git revert -m 1` the merges, check the tree equals the last good commit, push, and reinstall the saved last-good APK. Bring the work back later with a revert of the revert.
- Never build in a folder another agent is editing (Codex took jb-clips mid-session).
- The disk fills fast: 79 idle worktrees removed 10-18 (tasks/REMOVED_WORKTREES_20261018.md). Delete only regenerable build output, never worktrees with unsaved work.

## Late 10-19: Tile board speed, step 1 (NOT on main, NOT phone-tested)

- Helper commit **d4b3cd917** (helper1/media-atlas-b1). Tile-board stamp/tuft dabs are bucketed per tile and painted in one pass per frame (TileStrokeBatch.kt).
- Merged locally as integration ec0a50e94 (not pushed); all suites green (androidkit 1052, app 317).
- The Note 9 was left on MAIN (1e33f1188 code) on purpose.
- Main baseline done 10:39–10:59: 33 brushes × 4 boards, helper scratchpad wp_tile4/main_today.log.
  Main tile p50 ms/frames: pencil 78/4, bristle 67/8, marker 53/6, softair 115/5, flatpaint 134/2 (flatpaint not in step 1).
- To finish:
  1. `install -r` the tile-fix APK;
  2. run the same matrix in the same hour;
  3. add a per-cell hash of MatrixBench's "after" plane on both builds (stateAfter is already read back) as the pixel-exact seam proof;
  4. push only if canvas/animation/sprite are within noise, tile is faster and the hashes match.
- Helper handoff: helper1/media-atlas-b1 f834e9d0f, tasks/joybrush/handoffs/HELPER1_TILE_SPEED_HANDOFF_20261019.md (exact baseline command). Main baseline logs copied to tasks/joybrush/evidence/tile-baseline-20261019/.

## Owner's phone test, 10-19 afternoon (evidence/owner-seams-20261019.jpg, red arrows)

- **Water default stays "Today".** "A · brush first" stays an option; he didn't feel it much faster. Revisit once the optimisations land.
- **Seams are still there**, in Today and in A:
  - drips cut off flat;
  - straight bands across a wash;
  - seams that GROW as the water keeps running after it looked fine;
  - a long crisscross stroke that seams.
- Cause: the water only moves inside the ~1024 px patch near the pen. Drips running out of it, and water left behind when it moves, freeze at its edge. The 10-19 window patch only stopped STROKES being cut; it doesn't keep water outside the patch moving.
- **Top engine item for next week: no seams.** All still-moving water must be simulated together. Options, in the lead's order:
  1. a patch that grows to hold the moving water (bounded by memory; the whole-screen-patch scope, sized by the water instead of the screen; also needed for symmetry E4);
  2. the parked atlas, if tilted flux gets fixed.
  - Pair either with "water only where water is" for speed.
- Water does follow the paper: jb_wet_flux runs water downhill along the paper's relief (u_paperBake height), with absorbency and fibre direction (u_fluidBake) and paper-dependent pinning at wet fronts.
- **Rotation:** tipping the phone rotated the canvas. Fixed 10-19: while "Tilt with phone" is on, the screen keeps its orientation (lockRotationForTilt).
  His phone's rotation lock keeps coming back unlocked after agent sessions. Known cause (DEVICE_CONTROL_RUNBOOK §monkey): `adb shell monkey` turns auto-rotate ON. Every agent must launch with `am start`, never monkey.
