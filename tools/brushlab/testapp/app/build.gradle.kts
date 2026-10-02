plugins { alias(libs.plugins.androidApplication) }
android {
    namespace = "cc.joycreator.joybrush.brushtest"
    compileSdk = 36
    defaultConfig {
        applicationId = "cc.joycreator.joybrush.brushtest"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "2026.10.02-contact"
    }
}
dependencies { implementation(project(":joybrush-android")) }
