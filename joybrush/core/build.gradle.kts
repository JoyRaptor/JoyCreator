// joybrush-core: the platform-neutral engine (blueprint §3.1). NO Android, NO Java-only APIs in
// commonMain — this module must compile for the phone, the PC Brush Lab (JS) and, later, iOS.
// Only the JVM target is enabled today; js() and the iOS targets are added when a client needs them.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    // JB-0.02: document.json. The version is pinned in the root joybrush/build.gradle.kts.
    kotlin("plugin.serialization")
}

kotlin {
    jvm {
        // Same bytecode level as the Android app, whatever JDK happens to run the build.
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        // The serialization plugin and this one dependency are the whole JSON layer of the engine
        // (JB-0.03 brush.json; JB-0.02 adds document.json). Nothing else here is platform-specific.
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
