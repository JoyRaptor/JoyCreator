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

**Step 1 result (2026-09-28) — DONE, from `build.log` tail (the watcher, not a hand-run gradle):**
```
> Task :joybrush:core:jvmJar
> Task :joybrush:androidkit:jar
> Task :joybrush-android:compileDebugKotlin
> Task :app:processDebugMainManifest
> Task :app:mergeDefaultDebugJavaResource
> Task :app:packageDefaultDebug
> Task :app:assembleDefaultDebug

BUILD SUCCESSFUL in 4m 11s
257 actionable tasks: 66 executed, 191 up-to-date
Configuration cache entry stored.
```
So the composite substitution, the manifest entry and the Kotlin all compile. **Step 2 is still
outstanding — there is no `adb` on the build machine, so nobody has opened the screen yet.**

**Step 2 correction — the appId is `com.fadcam.beta`, not `com.fadcam`.** The debug build type adds
`applicationIdSuffix = ".beta"`, and the watcher builds `assembleDefaultDebug`. The command is:
```
adb shell am start -n com.fadcam.beta/cc.joycreator.joybrush.android.JoyBrushActivity
```

## Do not
- Do not touch any other app file (especially not `FaditorEditorActivity`).
- Do not add Jetpack Compose, AppCompat themes, or other dependencies.
- Do not implement zoom, layers or saving — later specs. Do not modify anything under `joybrush/`.
- Never `adb uninstall`. Never install on the owner's Note 20 (START_HERE rule 3).

## Definition of done
Watcher build success pasted · sandbox screenshot · only owner-area changes · commit
`JB-0.05: Joy Brush module + pen input` · ROADMAP row → 🟧 Built (then T1 review).

## Questions

_Added by the JB-0.05 build. These are build-file level, so they need a human with a build._

1. ~~**HIGHEST RISK — will `cc.joycreator.joybrush:core` resolve from an Android consumer at all?**~~
   **ANSWERED YES — empirically, by the watcher build on 2026-09-28. Do not "fix" this.** The
   substitution works exactly as the Gradle composite docs describe: `includeBuild("joybrush")`
   plus default (no explicit `substitute`) rules maps `${project.group}:${project.name}`, and
   KGP 2.2's `jvm()` consumable configuration *is* selected by the Android consumer. The build
   log shows `:joybrush:core:jvmJar` and `:joybrush:androidkit:jar` running as part of the app
   build, then `:joybrush-android:compileDebugKotlin`, then `BUILD SUCCESSFUL in 4m 11s`
   (257 actionable tasks: 66 executed, 191 up-to-date), and
   `joybrush-android/build/tmp/kotlin-classes/debug/.../JoyBrushActivity.class` on disk. The
   fallback worry below was unfounded: `cc.joycreator.joybrush:core-jvm` is indeed not a valid
   coordinate (it is a publication name, and there is no project called `core-jvm`), but it is
   not needed.

2. **`joybrush/androidkit` needs an `android.jar` at CONFIGURATION time, and throws without one.**
   `findAndroidJar()` is called inside its `dependencies { }` block, so a failure is a
   configuration-time `GradleException`. With composite default substitutions Gradle fully
   configures every project of the included build, so simply adding `includeBuild("joybrush")`
   makes the APP build depend on a `platforms/android-XX/android.jar` existing. It reads
   `ANDROID_HOME`, `ANDROID_SDK_ROOT`, then `../local.properties` `sdk.dir` — all of which the app
   build already needs, so in practice it will be found. Worth knowing anyway: this is new
   coupling. Declaring explicit `dependencySubstitution` blocks in the app's settings would stop
   Gradle configuring the included build until one of the substituted projects is actually needed,
   and would speed up every watcher build. Suggest for a later spec.

3. **Took the spec's own `kotlinOptions` fallback.** The spec's `android { kotlinOptions {
   jvmTarget = "17" } }` is written as *deprecated* in the current kotlinlang DSL docs
   ("Migrate away from android.kotlinOptions" → `kotlin { compilerOptions { jvmTarget.set(
   JvmTarget.JVM_17) } }`), so `joybrush-android/build.gradle.kts` uses the fallback form. It
   needs the extra `import org.jetbrains.kotlin.gradle.dsl.JvmTarget`. Flagging in case the spec
   wants the deprecated form for consistency.

4. **No `AndroidManifest.xml` in the new library module — CONFIRMED FINE.** The watcher build ran
   `:joybrush-android:processDebugManifest` with only `namespace` in the build file. Nothing to do.

5. **R8 / release builds are untested here.** `isMinifyEnabled = true` for release and pro. Nothing
   in JB-0.05 is reached by reflection and the activity is manifest-referenced, so R8 should keep
   it — but `joybrush/core` uses kotlinx.serialization, and the save/open work (JB-0.08) will need
   R8 keep rules for the generated serializers. `app/proguard-rules.pro` is not in this spec's
   allowed edit list, so no rules were added. Carry this into JB-0.08.

6. **The watcher now configures the whole joybrush build on every save.** `:joybrush-android` is
   included unconditionally, so the owner's watcher will pay for configuring `joybrush/core` and
   `joybrush/androidkit` on every app build. Correctness first, but if build time becomes a
   problem, gating the include behind a `-P` property is the lever.
