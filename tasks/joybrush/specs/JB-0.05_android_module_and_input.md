# JB-0.05 — Joy Brush Android module, first screen, and pen input

| | |
|---|---|
| **Tier** | T2, **T1 review required before 🟩** (it edits the app's build files) |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-0.01 (done: `PenSample`, `StrokeSmoother`, `DirectionTracker`, `AxisMapping`) |
| **Owner area** | NEW `joybrush-android/` (whole folder); EXACT edits only to `settings.gradle.kts`, `build.gradle.kts` (root), `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml` as listed below |
| **Estimated size** | ~450 lines of Kotlin |

## Goal
The first thing the owner can hold: open a Joy Brush screen, draw with the S Pen, and feel the
pressure, smoothing and tilt. Rendering is a deliberately simple CPU placeholder (android.graphics
circles) — the GPU engine replaces it in JB-0.07. What must be right here is the INPUT.

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
    implementation(libs.core.ktx)
}
```
(If `kotlinOptions` is rejected by the Kotlin 2.2 plugin, use
`kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }`.)

## Files to create (package `cc.joycreator.joybrush.android`)
1. `JoyBrushActivity.kt` — full-screen, keeps the screen on while open, hosts `JbCanvasView`.
   Top-right: a small round "×" button (40dp, white 10% fill, 12% ring — the drawer idle style) that
   finishes the activity; a smoothing slider (0..1, default 0.35) in a pill at the top centre; a
   "Clear" pill. That is all the UI for now.
2. `PenInputAdapter.kt`:
   ```kotlin
   object PenInputAdapter {
       /** Every sample in the event, oldest first: all historical samples, then the current one. */
       fun samples(ev: MotionEvent, pointerIndex: Int, toDoc: (Float, Float) -> Pair<Float, Float>,
                   canvasRotation: Float): List<PenSample>
   }
   ```
   - For h in 0 until ev.historySize: x/y from `getHistoricalX/Y(pointerIndex, h)`, time
     `getHistoricalEventTime(h)`, pressure `getHistoricalPressure`, tilt
     `getHistoricalAxisValue(AXIS_TILT, pointerIndex, h)`, orientation
     `getHistoricalAxisValue(AXIS_ORIENTATION, pointerIndex, h)`; then the current sample the same way.
   - Tool: `getToolType(pointerIndex)` → STYLUS / ERASER / FINGER / MOUSE.
   - For FINGER and MOUSE: pressure = 1, tilt = NaN, azimuth = NaN.
   - For STYLUS/ERASER: tilt = the axis value (radians); azimuth =
     `AxisMapping.androidOrientationToAzimuth(orientation, canvasRotation)`; if tilt is 0 for every
     sample of a stroke the device probably lacks tilt — keep the zeros (diagnostics decide later).
   - barrel = NaN always on Android.
3. `TouchPolicy.kt` — who may draw:
   - Before any stylus event has been seen: fingers draw.
   - After the first stylus event (hover or touch) in this activity: fingers never draw (they will
     become the tool finger in Phase 2; for now they do nothing).
   - Ignore a finger ACTION_DOWN while the stylus is hovering (ACTION_HOVER_ENTER..EXIT) or within
     400 ms after the stylus lifted.
   - `ACTION_CANCEL`, or `FLAG_CANCELED` on API 33+ (`ev.flags and MotionEvent.FLAG_CANCELED != 0`)
     → discard the stroke in progress.
   - Call `requestUnbufferedDispatch(ev)` on a stylus ACTION_DOWN only (API 30+).
4. `JbCanvasView.kt` — a View. On pen down: new `StrokeSmoother(sliderValue, screenPerDoc = 1f)` and
   `DirectionTracker()`. Feed every sample from the adapter. Draw each released point as a filled
   circle, radius = 1.5 + 10 × pressure px, into an offscreen `Bitmap` the size of the view (the
   "canvas"); invalidate. On up: `finish()`, draw the rest. For tilt visibility, when a sample has
   tilt > 0.17 draw the circle as an ellipse squashed by cos(tilt) along the tilt direction
   (`DirectionTracker.update` output). Clear button clears the bitmap.
   Document space == view space in this spec (no zoom/pan yet).

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
- Do not implement zoom, layers, saving, GPU rendering — later specs.
- Never `adb uninstall`. Never install on the owner's Note 20 (START_HERE rule 3).

## Definition of done
Watcher build success pasted · sandbox screenshot · only owner-area changes · commit
`JB-0.05: Joy Brush module + pen input` · ROADMAP row → 🟧 Built (then T1 review).

## Questions
