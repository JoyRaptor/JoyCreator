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
