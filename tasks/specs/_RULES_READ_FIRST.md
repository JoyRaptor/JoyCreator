# RULES FOR EVERY SPEC IN THIS FOLDER — read before touching anything

Android video editor, Java. Repo root: `C:\+Projects\Screenrecorder\FadCam`.
Owner is JoyRaptor — an ARTIST, not a programmer. Report in plain language.

## Build
- A file-watcher rebuilds on save. It BUILDS ONLY — it does not install (changed 2026-09-16;
  it used to end in `installDefaultDebug`, which restarted the adb server and killed the
  wireless connection on every save, and reported BUILD FAILED with no phone attached even
  though the compile was clean). A SUCCESSFUL line now means compiled AND packaged, nothing
  more. To get it onto the phone: `bash tools/phone.sh install`.
- For an unambiguous verdict run, from the repo root:
      export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=C:\Windows\Temp"
      ./gradlew assembleDefaultDebug --console=plain
  NEVER run a task that installs. NEVER run `adb uninstall` — an agent once wiped 23 of JoyRaptor's
  real projects that way.
- `NoSuchFileException ...intermediates/javac/...class` = the watcher clobbered the incremental dir
  mid-run. Just re-run.
- If Gradle says `compileDefaultDebugJavaWithJavac UP-TO-DATE` because the watcher already compiled,
  do NOT trust task state — verify your symbols are really in the artefacts with `javap` on
  `app/build/intermediates/javac/defaultDebug/compileDefaultDebugJavaWithJavac/classes/...`.

## Git
- `git add` every file THE MOMENT you write it. Something in this repo periodically restores the
  working tree; staged work survives, unstaged work has been silently destroyed before.
- NEVER `git commit`, `checkout`, `stash`, `clean`, and NEVER resolve a merge conflict.
- Many files carry OTHER lanes' uncommitted staged work. Never revert or "tidy" anything that is
  not yours.

## The one rule that matters most in this codebase
**PREVIEW AND EXPORT MUST AGREE.** There are two renderers — a live GL/Canvas preview and a media3
export. A feature that works in one and not the other is the top-severity bug class here and has
bitten this project a dozen times. Whenever you touch rendering: put the shared truth in ONE method
or ONE shader string that both call. Never transcribe the same arithmetic twice.

## Verification honesty
Never claim something works because it compiled. Say explicitly: "compile-verified" vs
"device-verified". If you could not test it, say so.

## Off-device tests
`tools/jvm-harness/` holds ~70 tests that run WITHOUT a phone (`bash tools/jvm-harness/run-fx.sh`,
`run-mesh.sh`, `run-mask.sh`, etc). Logic with no `android.*` imports can be tested there. Two
harnesses are ALREADY RED for reasons unrelated to these specs — `run-matte.sh` (an FLog stub gap)
and `run-copy-lint.sh` (another lane's staged work). Do not try to fix those; just don't make them
worse.
