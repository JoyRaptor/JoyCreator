pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
    versionCatalogs { create("libs") { from(files("../../../gradle/libs.versions.toml")) } }
}
rootProject.name = "JoyBrushTest"
includeBuild("../../../joybrush")
include(":testapp", ":joybrush-android", ":studiokit")
project(":testapp").projectDir = file("app")
project(":joybrush-android").projectDir = file("../../../joybrush-android")
project(":studiokit").projectDir = file("../../../studiokit")
