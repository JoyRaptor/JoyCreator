// joybrush-core: the platform-neutral engine (blueprint §3.1). NO Android, NO Java-only APIs in
// commonMain — this module must compile for the phone, the PC Brush Lab (JS) and, later, iOS.
// Only the JVM target is enabled today; js() and the iOS targets are added when a client needs them.

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm {
        // Same bytecode level as the Android app, whatever JDK happens to run the build.
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
