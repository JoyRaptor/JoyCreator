# D.05 — Share the Studio's effects, gradients, blend modes, masks and keyframes through `:studiokit`

| | |
|---|---|
| **Tier** | T2 (T1 review) + T3 phone check |
| **Status** | see ROADMAP.md |
| **Depends on** | D.02 (`:studiokit` exists, and D.02's harness sourcepath change) |
| **Owner area** | MOVE (git mv, same packages) the files listed below from `app/src/main/java/com/fadcam/ui/faditor/` to `studiokit/src/main/java/com/fadcam/ui/faditor/`; EDIT `studiokit/build.gradle.kts` (add `libs.gson` or whatever alias the app uses for Gson) |
| **Estimated size** | ~10 lines of build; the moved files change by 0 lines |

## Goal (owner, 2026-09-29)
"See what other features you can get for almost free from the Studio: masks, blend modes, colour
effects, effect layers like gradient ramps for remapping colours, a gradient editor … and if these
are built modular, as we improve them in one place the other places get the enhancements."

The Lead's survey: all of the following are plain Java with no Android UI dependency (only
`androidx.annotation`, and Gson for JSON), each written as "one authority" that both Studio renderers
already share. Moving them into the kit is the modular step; Joy Brush then USES them (JB-2.20…2.24).
**Pure move: no behaviour change in the Studio.**

## What moves (same package names — no import anywhere changes)
- `fx/`: `FxRegistry` (17 effects: gaussian/directional blur, pixelate, brightness, colour grade,
  film, invert, levels, gradient map, threshold, posterize, duotone, RGB shift, solid colour,
  gradient fill, noise, offset), `FxEffectDef`, `FxParam`, `FxInstance`, `FxStack`, `FxCompiler`
  (stack → GLSL ES 1.00 + AGSL), `FxGlSource`, `FxUniforms`, `FxCost`, `FxPreviewTier`, `FxReorder`,
  `FxPresetStore`, `GradientRamp` (colour + opacity stops, bias, mirror/flip/bands), `GradientCurve`.
- `keyframe/`: all six files (`FxStack` needs `KeyframeSet`/`KeyframeCodec`).
- `model/BlendModes.java` (26 modes incl. NORMAL: GLSL + Java reference) and `model/MaskSdf.java`.
- `tools/GradientRampEditorView.java` — "THE standard gradient editor, app-wide".

Stays in the app: `fx/FxGradeMigration.java` (it reads the Studio's old `effects.EffectStack`; same
package, so nothing changes for it), `FxPanel`, `BlendPickerPopover`, `MaskKeyPanel` (they use app
resources — a later D.05b moves them with their resources when Joy Brush needs those panels).

## Steps
1. `git mv` each file. Before moving, re-run `grep '^import com.fadcam' <file>` — if a file imports
   anything NOT in this list or in `:studiokit` already, STOP and write it in Questions.
2. Add Gson to `:studiokit` (same alias/version the app uses; the app already has it, so no new
   download).
3. Harness: every `run-*.sh` already has both sourcepaths after D.02; run `run-fx.sh`,
   `run-mask.sh`, `run-key.sh`, `run-lanechain.sh`, and the blend test (`BlendModesTest`) — all green.

## Verification
- `git diff --stat -M` shows only renames (100 %) plus the build line.
- Watcher `build.log` green: `:studiokit`, `:app`, `:joybrush-android` compile tasks EXECUTED.
- **Owner check (2 minutes):** in the Studio, add an adjustment layer with a gradient map and a blur;
  open the gradient editor; change a clip's blend mode; add a mask — all as before; export a 3-second
  clip → the export looks like the preview.

## Do not
Change nothing inside the moved files. Do not move the panels (D.05b). Never run gradle on the owner's PC.

## Definition of done
Watcher + harness green (paste) · owner check noted · commit `D.05: fx, gradients, blends, masks, keyframes into studiokit` ·
ROADMAP row → 🟧 Built.

## Questions
