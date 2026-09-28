// Joy Brush — a STANDALONE Gradle build, deliberately not included in the app's root build.
//
// Why standalone: the owner's PC runs a watcher that builds the app on every save. Joy Brush grows
// here, tested on its own, without any chance of turning that build red. It joins the app later with
// one includeBuild line (tasks/joybrush/JOYBRUSH_BLUEPRINT.md §3.1).
//
// Run the core's tests from the repo root:
//     ./gradlew -p joybrush :core:jvmTest

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
