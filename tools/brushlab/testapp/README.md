Joy Brush Test uses the production JoyBrushActivity, Studio Kit and shared core/GPU resources directly. It has its own application ID and drawing storage. It opens straight into drawing, so brush testing does not require building the video editor or native media libraries.

Build from an isolated worktree, after acquiring the shared jb-gradle.lock:
  .\gradlew.bat --no-daemon --no-watch-fs -p tools/brushlab/testapp :testapp:assembleDebug
Output: tools/brushlab/testapp/app/build/outputs/apk/debug/app-debug.apk

Use Pencil upright for detail, then lean around 45 degrees for shading at the same size. Try light/firm pressure and reverse lean. Use Flat Paint across red and blue strokes; it picks up separate color fragments from the canvas at pen-down. Bristle is the rough, dry counterpart to Sable. These are provisional stylus-tuned values; GPU verification establishes arithmetic, not artistic superiority. Fluid watercolour and impasto remain future work.
