// joybrush-androidkit: the Android side of the engine — GPU tiles, pen input, the drawing view.
//
// A plain Kotlin/JVM library compiled against android.jar (compileOnly). That lets it be
// compile-checked anywhere, including a cloud machine with no Android SDK, and the Android app
// consumes it like any other jar. It has no Android resources; the shared shaders ride along as
// Java resources under /joybrush/shaders/, and the shipped brush files under /joybrush/brushes/.
//
// android.jar is found in this order: -Pjoybrush.androidJar=<path>, $ANDROID_HOME or
// $ANDROID_SDK_ROOT, then sdk.dir in the repo's local.properties — newest platform wins.

import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
}

fun findAndroidJar(): File {
    (findProperty("joybrush.androidJar") as String?)?.let { return file(it) }
    val roots = mutableListOf<String>()
    System.getenv("ANDROID_HOME")?.let(roots::add)
    System.getenv("ANDROID_SDK_ROOT")?.let(roots::add)
    val lp = rootDir.resolve("../local.properties")
    if (lp.exists()) Properties().apply { lp.inputStream().use(::load) }.getProperty("sdk.dir")?.let(roots::add)
    for (r in roots) {
        val platforms = File(r, "platforms").listFiles()?.filter { File(it, "android.jar").exists() } ?: continue
        val newest = platforms.maxByOrNull { it.name.filter(Char::isDigit).take(3).toIntOrNull() ?: 0 } ?: continue
        return File(newest, "android.jar")
    }
    throw GradleException(
        "joybrush androidkit: no android.jar found. Set ANDROID_HOME, sdk.dir in local.properties, " +
            "or pass -Pjoybrush.androidJar=/path/to/android.jar"
    )
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":core"))
    compileOnly(files(findAndroidJar()))
    testImplementation(kotlin("test"))
}

tasks.processResources {
    from(rootDir.resolve("shaders")) { into("joybrush/shaders") }
    // JB-1.05b: the shipped brush files, one folder per brush plus index.txt. Read by
    // cc.joycreator.joybrush.androidkit.BrushLibrary from /joybrush/brushes.
    from(rootDir.resolve("brushes")) { into("joybrush/brushes") }
}
