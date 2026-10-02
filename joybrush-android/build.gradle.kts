// joybrush-android: the app-facing Joy Brush module (JB-0.05).
//
// One Activity, hosting cc.joycreator.joybrush.androidkit.JbCanvasView. The engine is NOT copied
// into the app: the root settings.gradle.kts adds includeBuild("joybrush"), and that composite
// build substitutes the two coordinates below with joybrush/core and joybrush/androidkit.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinAndroid)
}

android {
    namespace = "cc.joycreator.joybrush.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    testOptions { unitTests.isIncludeAndroidResources = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Spec form was android { kotlinOptions { jvmTarget = "17" } }; kotlinOptions is deprecated in
// AGP 8 / KGP 2.2 in favour of the extension-level compilerOptions block.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    // core is listed alongside androidkit on purpose: androidkit hides core behind
    // `implementation`, but JbCanvasView.Brush exposes core types (TipShape, Accumulate) and the
    // Eraser control calls Brush.copy(...), which needs those types on the compile classpath.
    implementation("cc.joycreator.joybrush:core")
    implementation("cc.joycreator.joybrush:androidkit")
    implementation(libs.core.ktx)
    implementation(project(":studiokit"))   // D.02: the Studio's colour picker and transform tool, shared not copied
}
