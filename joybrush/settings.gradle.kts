// Joy Brush — a STANDALONE Gradle build, deliberately not included in the app's root build.
//
// Why standalone: the owner's PC runs a watcher that builds the app on every save. Joy Brush grows
// here, tested on its own, without any chance of turning that build red. It joins the app with one
// includeBuild line (JB-0.05).
//
// Run the core's tests from the repo root:
//     ./gradlew -p joybrush :core:jvmTest
// Compile-check the Android kit (needs an android.jar — see androidkit/build.gradle.kts):
//     ./gradlew -p joybrush :androidkit:compileKotlin

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "joybrush"
include(":core")
include(":androidkit")
