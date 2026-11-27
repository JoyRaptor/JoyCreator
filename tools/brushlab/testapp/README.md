Joy Brush Test uses the production JoyBrushActivity, Studio Kit and shared core/GPU resources directly. It has its own application ID and drawing storage. It opens straight into drawing, so brush testing does not require building the video editor or native media libraries.

Build from an isolated worktree, after acquiring the shared jb-gradle.lock:
  .\gradlew.bat --no-daemon --no-watch-fs -p tools/brushlab/testapp :testapp:assembleDebug
Output: tools/brushlab/testapp/app/build/outputs/apk/debug/testapp-debug.apk

Verified 2026-10-02: BUILD SUCCESSFUL, 11.4 MB, debug-signed, `aapt2` reports
package `cc.joycreator.joybrush.brushtest`, label "Joy Brush Test", minSdk 24 / targetSdk 36,
launchable activity `cc.joycreator.joybrush.android.JoyBrushActivity`. Installable as built.

## The storage really is separate — why

The working file is `getExternalFilesDir("joybrush")/current.joybrush`
(`JoyBrushActivity.kt:1440`, `WORKING_DIR` at `:104`). Android scopes that path by application id, so:

- Joy Creator (`com.fadcam`, and the `.beta`/`.pro`/`.proplus`/`.notes`/`.calc`/`.weather` variants)
  keeps its drawings under `/storage/emulated/0/Android/data/com.fadcam/files/joybrush/`.
- This app keeps its own under `/storage/emulated/0/Android/data/cc.joycreator.joybrush.brushtest/files/joybrush/`.

Nothing here can read, overwrite or prune a real Joy Creator drawing. The main app also hosts
`JoyBrushActivity` (`app/src/main/AndroidManifest.xml:136`), so this is the same production screen
under a second id, not a fork of it.

## Rebuild after integrating — the APK goes stale

This app is built from whatever branch you build it on, so it silently carries that branch's paper
and document schema. The APK currently in `build/outputs/` predates the Lead's JB-9.06b export
backdrop work and the schema 7 paper changes, so **do not hand that one to the owner for a feel
judgement**: rebuild after the brush work is merged into the integration line. A brush judgement made
against an old paper is a judgement about the wrong app.

Use Pencil upright for detail, then lean around 45 degrees for shading at the same size. Try light/firm pressure and reverse lean. Use Flat Paint across red and blue strokes; it picks up separate color fragments from the canvas at pen-down. Bristle is the rough, dry counterpart to Sable. These are provisional stylus-tuned values; GPU verification establishes arithmetic, not artistic superiority. Fluid watercolour and impasto remain future work.
