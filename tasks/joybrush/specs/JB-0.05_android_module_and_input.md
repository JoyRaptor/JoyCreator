# JB-0.05 — Joy Brush Android module, first screen, and pen input

| | |
|---|---|
| **Tier** | T2, **T1 review required before 🟩** (it edits the app's build files) |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-0.01, JB-0.07 (done: the GPU engine, pen input and drawing view already exist in `joybrush/androidkit` as `cc.joycreator.joybrush.androidkit.JbCanvasView`) |
| **Owner area** | NEW `joybrush-android/` (whole folder); EXACT edits only to `settings.gradle.kts`, `build.gradle.kts` (root), `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml` as listed below |
| **Estimated size** | ~150 lines of Kotlin |

## Goal
The first thing the owner can hold: open a Joy Brush screen, draw with the S Pen on the real GPU
engine, and feel the pressure and smoothing. The engine, pen input, palm rejection and drawing view
are ALREADY BUILT (JB-0.07, `joybrush/androidkit`). This spec wires them into the app: a module, an
activity, three controls.

## Exact build edits (do these and nothing else in app files)
1. `gradle/libs.versions.toml` — under `[plugins]` add:
   ```toml
   androidLibrary = { id = "com.android.library", version.ref = "agp" }
   kotlinAndroid = { id = "org.jetbrains.kotlin.android", version = "2.2.0" }
   ```
2. Root `build.gradle.kts` `plugins { }` — add:
   ```kotlin
   alias(libs.plugins.androidLibrary) apply false
   alias(libs.plugins.kotlinAndroid) apply false
   ```
3. `settings.gradle.kts` — after `include(":app")` add:
   ```kotlin
   include(":joybrush-android")
   includeBuild("joybrush")   // Joy Brush core (standalone build, substituted as cc.joycreator.joybrush:core)
   ```
4. `app/build.gradle.kts` — in `dependencies { }` add `implementation(project(":joybrush-android"))`.
5. `app/src/main/AndroidManifest.xml` — next to the other editor activities add:
   ```xml
   <activity android:name="cc.joycreator.joybrush.android.JoyBrushActivity"
       android:theme="@style/Theme.FadCam.FaditorEditor"
       android:configChanges="orientation|screenSize|screenLayout|keyboardHidden"
       android:exported="true" />
   ```
   (`exported="true"` so it can be opened with adb until the lobby entry exists — JB-0.09 sets it
   back to false.)

## `joybrush-android/build.gradle.kts`
```kotlin
plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
}
android {
    namespace = "cc.joycreator.joybrush.android"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("cc.joycreator.joybrush:core")
    implementation("cc.joycreator.joybrush:androidkit")
    implementation(libs.core.ktx)
}
```
(If `kotlinOptions` is rejected by the Kotlin 2.2 plugin, use
`kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }`.)

## Files to create (package `cc.joycreator.joybrush.android`)
1. `JoyBrushActivity.kt` — full-screen; keeps the screen on while open; content = a FrameLayout with
   `JbCanvasView(this)` filling it (`import cc.joycreator.joybrush.androidkit.JbCanvasView`).
   Overlay controls (plain Views, no new dependencies):
   - top-right: a round "×" button (40dp, white 10% fill, 12% white ring) → `finish()`;
   - top-centre pill: a SeekBar 0..100 for smoothing → `canvas.smoothing = progress / 100f`
     (default 35);
   - bottom-left pills: **Undo** → `canvas.undo()`, **Redo** → `canvas.redo()`, **Clear** →
     `canvas.clearCanvas()`; enable/disable Undo/Redo from `canvas.onHistoryChanged`;
   - bottom-right pill: **Eraser** toggle → `canvas.brush = canvas.brush.copy(erase = on)`.
   Every button gets a `contentDescription` and `setTooltipText` (hover labels are a house rule).
   Call `canvas.onPause()` / `canvas.onResume()` from the activity's `onPause` / `onResume`.
2. Nothing else. Do NOT write your own input adapter, palm rejection or renderer — they exist in
   `JbCanvasView`, `MotionEventSamples` and `GlPaintEngine`.

## Verification (no gradle on the owner's PC — the watcher builds)
1. Save the files; the watcher builds. Check `build.log` shows `BUILD SUCCESSFUL` with a fresh
   timestamp (`.\tools\phone.ps1 build`). Paste the lines.
2. Install on the **sandbox** phone (`bash tools/phone.sh deploy`), open with
   `adb shell am start -n <appId>/cc.joycreator.joybrush.android.JoyBrushActivity`
   (appId from `app/build.gradle.kts` `applicationId`). Screenshot after drawing a few strokes.
3. Mark 🟧 Built only with (1) and (2) pasted. The OWNER does the feel check (JB-0.30).

## Do not
- Do not touch any other app file (especially not `FaditorEditorActivity`).
- Do not add Jetpack Compose, AppCompat themes, or other dependencies.
- Do not implement zoom, layers or saving — later specs. Do not modify anything under `joybrush/`.
- Never `adb uninstall`. Never install on the owner's Note 20 (START_HERE rule 3).

## Definition of done
Watcher build success pasted · sandbox screenshot · only owner-area changes · commit
`JB-0.05: Joy Brush module + pen input` · ROADMAP row → 🟧 Built (then T1 review).

## Questions
