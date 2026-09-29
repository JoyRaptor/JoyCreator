# D.02 — One shared kit: move the Studio's colours, colour picker and transform tool into `:studiokit`

| | |
|---|---|
| **Tier** | T2 (T1 review of the build files) |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-0.05 (Built), **D.02a** (the gesture fixes land first, in the app, so this stays a pure move) |
| **Owner area** | NEW module `studiokit/` (build file, manifest, `res/values/strings.xml`); EDIT `settings.gradle.kts` (one `include`), `app/build.gradle.kts` and `joybrush-android/build.gradle.kts` (one `implementation(project(":studiokit"))` each); MOVE (git mv) the 7 files listed below; EDIT `tools/check_joybrush_tokens.py` (one path) |
| **Estimated size** | ~60 lines of build/config; the moved files change by ONE line in total |

## Goal
Joy Brush must look and behave like the rest of Joy Creator, and the owner already has a colour
picker and a transform tool he likes in the Studio. `:joybrush-android` cannot use them today: the
app depends on it, not the other way round. So they move into a small library both depend on.
**Pure move — no behaviour change anywhere.** (This replaces the old D.02 outline; the scrubbable
number / slider row / header icon button become D.02b, written by the orchestrator, into this same
module.)

## What moves (git mv, keeping the SAME package names — so no import anywhere changes)
From `app/src/main/java/com/fadcam/ui/faditor/` to `studiokit/src/main/java/com/fadcam/ui/faditor/`:
1. `Studio.java` — the colour tokens (pure constants).
2. `tools/ColorPickerDialog.java` — the one colour picker.
3. `transform/TransformOverlayView.java`, `transform/TransformQuad.java`, `transform/HandleModel.java`,
   `transform/PreviewLoupe.java`, `transform/TransformDiag.java` — the transform surface. Its doc
   says it is "reusable by construction": everything app-specific is behind its `Host` interface.

Stays in the app: the three `*TransformHost.java` (they know the Studio's model), `MeshBendSeam` and
`transform/mesh/` (bend is not needed by Joy Brush yet), and every caller.

## Steps
1. `studiokit/build.gradle.kts`: `androidLibrary` plugin (same alias the app uses), namespace
   `com.fadcam.studiokit`, compileSdk 36, minSdk 24, Java 17 like the app; dependencies:
   `libs.material` and `androidx.annotation` (the only non-framework imports of the 7 files — check
   with `grep ^import`). No Kotlin plugin needed (all Java).
2. `settings.gradle.kts`: `include(":studiokit")`. App and joybrush-android: add the dependency.
3. The ONE code edit: `ColorPickerDialog` uses `com.fadcam.R.string.faditor_color_set`. Declare
   `faditor_color_set` ("Set") in `studiokit/src/main/res/values/strings.xml` and change that line to
   `com.fadcam.studiokit.R.string.faditor_color_set`. The app keeps its own `faditor_color_set` (and
   any translations): when a library and the app define the same name, the app's wins, so every
   language shows exactly what it shows today.
4. `tools/check_joybrush_tokens.py`: `STUDIO_JAVA` points at the new path. Run it: it must pass.
5. **The JVM harness** (`tools/jvm-harness/run-*.sh`, 57 scripts) compiles with
   `-sourcepath "app/src/main/java"`; after the move those classes live under `studiokit/`. Change
   every script's sourcepath to `app/src/main/java` + `studiokit/src/main/java` (joined with the same
   separator each script already uses for `-cp`). Then run `run-speck.sh`, `run-escape.sh`,
   `run-flip.sh`, `run-scalesnap.sh`, `run-rebase.sh`, `run-spech.sh` — all must stay green.

## Verification
- The watcher's `build.log`: `BUILD SUCCESSFUL` with `:studiokit:compileDebugJavaWithJavac`,
  `:app:compileDebugJavaWithJavac` and `:joybrush-android:compileDebugKotlin` EXECUTED. Paste the
  lines. `python tools/check_joybrush_tokens.py` passes.
- `git diff --stat -M` shows the 7 files as RENAMES (100% similar except ColorPickerDialog at 1 line).
- Owner check on the Note 9 (T3, 2 minutes, only after the watcher is green): in the Studio, open any
  colour (e.g. a caption's colour) — the picker looks and works as before; select a clip and use the
  transform handles — they work as before.

## Do not
- Do not change a single behaviour, colour value, or package name. Do not move the hosts or mesh.
- Do not touch `FaditorEditorActivity.java`, `LobbyFragment.java` or the other leftover files
  (LEAD_RULINGS R14) — with same-package moves nothing needs them.
- Never run gradle yourself on the owner's PC (the watcher builds).

## Definition of done
Watcher green (paste) · owner check noted · commit `D.02: studiokit module` · ROADMAP row → 🟧 Built.

## Questions
