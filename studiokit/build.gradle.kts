// studiokit: the Studio's shared UI kit (D.02) — the colour tokens, the colour picker and the transform tool, moved out
// of :app so that :joybrush-android can use the SAME pieces instead of copying them (LEAD_RULINGS R23: share, don't copy).
//
// A pure move: the files keep their package names (com.fadcam.ui.faditor...), so no import anywhere changed. All Java.
plugins {
    alias(libs.plugins.androidLibrary)
}

android {
    namespace = "com.fadcam.studiokit"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.material)
}
