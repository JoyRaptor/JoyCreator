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
        //
        // `api`, not `implementation` (orchestrator, provisional — Claude to confirm). `JbDocument`
        // and `BrushPreset` are `@Serializable` data classes on core's PUBLIC API, and a consumer
        // that touches either one without kotlinx-serialization on its classpath gets a
        // NoClassDefFoundError at the moment it loads the class — not at compile time, which is why
        // this stayed invisible until androidkit's own tests ran. `implementation` makes
        // serialization an implementation detail of core, which it is not: it is written into the
        // type signature of everything the document and brush formats return.
        commonMain.dependencies {
            api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// The tests read the shipped brush files and grain assets straight off disk (ShippedBrushFilesTest,
// DefaultPresetsTest, the grain tests). Gradle only knows a directory is a test INPUT if it is
// declared, so before this an edited preset left `:core:jvmTest` UP-TO-DATE: "BUILD SUCCESSFUL" with
// the changed brush never tested (found by the JB-1.07 builder, LEAD_RULINGS R44).
tasks.named("jvmTest") {
    inputs.dir(rootDir.resolve("brushes")).withPropertyName("shippedBrushes")
    inputs.dir(rootDir.resolve("assets")).withPropertyName("shippedAssets")
    inputs.dir(rootDir.resolve("shaders")).withPropertyName("shippedShaders")
}
