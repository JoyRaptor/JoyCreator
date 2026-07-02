import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
}

// whisper.cpp source location for the native transcription module (see
// app/src/main/cpp/CMakeLists.txt). Mirrors the media3.patched.path convention.
val whisperCppDir: String = run {
    val props = Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }
    props.getProperty("whisper.cpp.path") ?: "${rootDir}/../whisper.cpp"
}

android {
    namespace = "com.fadcam"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    val isBundle = gradle.startParameter.taskNames.any { it.lowercase().contains("bundle") }
    val isProBuild = gradle.startParameter.taskNames.any { it.lowercase().contains("pro") }

    splits {
        abi {
            // For pro builds: enable splits but only arm64-v8a (no universal)
            // For main builds: arm64-v8a + armeabi-v7a with universal APK
            isEnable = !isBundle
            reset()
            if (isProBuild) {
                include("arm64-v8a")
                isUniversalApk = false
            } else {
                include("armeabi-v7a", "arm64-v8a")
                isUniversalApk = true
            }
        }
    }

    defaultConfig {
        applicationId = "com.fadcam"
        minSdk = 24
        targetSdk = 36
        versionCode = 37
        versionName = "4.0.0"
        vectorDrawables.useSupportLibrary = true
        
        // Fix 16KB native library alignment for Android 15
        // Generate full native debug symbols so they can be uploaded to Play Console
        ndk {
            debugSymbolLevel = "FULL"
            // whisper.cpp native build: arm64-v8a only for now (the sideload
            // target). armeabi-v7a can be re-added once arm64 is validated.
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += "-DWHISPER_DIR=${whisperCppDir.replace('\\', '/')}"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            val props = Properties()
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream().use { stream ->
                stream?.let { props.load(it) }
            }
            val keystoreFile = props.getProperty("KEYSTORE_FILE", "")
            // Only set storeFile if keystore file path is provided and exists
            if (keystoreFile.isNotEmpty() && file(keystoreFile).exists()) {
                storeFile = file(keystoreFile)
                storePassword = props.getProperty("KEYSTORE_PASSWORD", "")
                keyAlias = props.getProperty("KEY_ALIAS", "")
                keyPassword = props.getProperty("KEY_PASSWORD", "")
            }
        }
    }
    
    // Helper: check if release signing config is valid
    val releaseSigningConfigValid = signingConfigs.getByName("release").storeFile != null

    buildTypes {
        debug {
            applicationIdSuffix = ".beta"
            isDebuggable = true
            versionNameSuffix = "-beta9" // Increment the beta version suffix for each release. Use `beta1` for the first beta release, then `beta2`, etc.
            resValue("string", "app_name", "FadCam Beta")
        }
        
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            isDebuggable = false
            signingConfig = signingConfigs.getByName("release")
        }
        
        create("pro") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            applicationIdSuffix = ".pro"
            isDebuggable = false
            if (releaseSigningConfigValid) {
                signingConfig = signingConfigs.getByName("release")
            }
            versionNameSuffix = "-Pro"
        }
        
        create("proPlus") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            applicationIdSuffix = ".proplus"
            isDebuggable = false
            if (releaseSigningConfigValid) {
                signingConfig = signingConfigs.getByName("release")
            }
            versionNameSuffix = "-Pro+"
            // Custom app name via gradle property
            val customAppName = project.findProperty("customAppName")?.toString() ?: "FadCam Pro+"
            resValue("string", "app_name", customAppName)
        }
    }

    flavorDimensions += "pro"

    productFlavors {
        create("notesPro") {
            dimension = "pro"
            applicationIdSuffix = ".notes"
            resValue("string", "app_name", "Notes")
        }
        create("calcPro") {
            dimension = "pro"
            applicationIdSuffix = ".calc"
            resValue("string", "app_name", "Calculator")
        }
        create("weatherPro") {
            dimension = "pro"
            applicationIdSuffix = ".weather"
            resValue("string", "app_name", "Weather")
        }
        create("default") {
            dimension = "pro"
            // Default for proPlus builds
        }
    }

// ./gradlew assembleNotesProRelease - Notes Pro variant
// ./gradlew assembleCalcProRelease - Calculator Pro variant
// ./gradlew assembleWeatherProRelease - Weather Pro variant
// ./gradlew assembleDefaultProPlusRelease -PcustomAppName="Custom Name" - Pro+ custom build (standalone)

    // Variant filter: only build specific variants
    variantFilter {
        val isPreBuiltFlavor = name.contains("notesPro") || name.contains("calcPro") || name.contains("weatherPro")
        val isDefaultFlavor = name.contains("default")
        
        if (isPreBuiltFlavor) {
            // Pre-built flavors: only 'release' build type
            if (!name.endsWith("Release")) {
                ignore = true
            }
        } else if (isDefaultFlavor) {
            // Default flavor: allow 'debug', 'release', and 'proPlus' build types
            if (name.endsWith("Pro") && !name.endsWith("ProPlus")) {
                ignore = true
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    sourceSets {
        getByName("main") {
            java.srcDir("libs/AppLockLibrary/src/main/java")
            res.srcDir("libs/AppLockLibrary/src/main/res")
        }
        // NOTE: Removed setSrcDirs(emptyList()) to enable test source detection
        // getByName("test").java.setSrcDirs(emptyList<String>())
        // getByName("androidTest").java.setSrcDirs(emptyList<String>())
        
        // Flavor-specific resources (icons override main icons)
        getByName("notesPro") {
            res.srcDir("src/notesPro/res")
        }
        getByName("calcPro") {
            res.srcDir("src/calcPro/res")
        }
        getByName("weatherPro") {
            res.srcDir("src/weatherPro/res")
        }
    }

    packaging {
        jniLibs {
            excludes += listOf("**/x86/**", "**/x86_64/**", "**/mips/**", "**/mips64/**")
            // OpenCV and ffmpeg-kit both bundle libc++_shared.so. Keep one copy.
            pickFirsts += listOf("**/libc++_shared.so")
            // Enable 16KB page size alignment for Android 15 compatibility
            useLegacyPackaging = false
        }
        resources {
            excludes += listOf(
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/DEPENDENCIES",
                "META-INF/*.kotlin_module",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "**/*.kotlin_metadata",
                "**/*.kotlin_builtins",
                "**/*.proto",
                "assets/PSDs/**"  // Exclude PSD source files from release APK
            )
        }
    }

    androidResources {
        noCompress.add("xml")
        additionalParameters.add("--no-version-vectors")
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        disable += "MissingTranslation"
    }
}

dependencies {
    implementation(libs.activity)
    implementation(libs.appintro.v631)
    implementation(libs.appcompat)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.core)
    implementation(libs.camerax.extensions)
    implementation(libs.camerax.view)
    implementation(libs.zxing.android.embedded)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.video)
    implementation(libs.constraintlayout)
    implementation(libs.gridlayout)
    implementation(libs.core.ktx)
    // Media3 ExoPlayer for playback (replacing deprecated exoplayer2)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    // Media3 Transformer + Effect for Faditor Mini video editing
    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    // AndroidX Media for MediaStyle notifications
    implementation(libs.media)
    implementation(libs.glide)
    implementation(libs.gson)
    implementation(libs.lottie)
    implementation(libs.material)
    implementation(libs.navigation.fragment.ktx)
    implementation(libs.navigation.ui.ktx)
    implementation(libs.okhttp)
    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.task.vision) {
        exclude(group = "org.tensorflow", module = "tensorflow-lite-api")
    }
    implementation(libs.opencv.android)
    implementation(libs.osmdroid.android)
    implementation(libs.osmdroid.wms)
    implementation(libs.swiperefreshlayout)
    implementation(libs.viewpager2)
    implementation(libs.lifecycle.process)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.livedata)
    implementation(libs.core.splashscreen)
    implementation(libs.documentfile)
    implementation(libs.localbroadcastmanager)
    implementation(libs.room.runtime)
    
    // Media3 for fragmented MP4 muxing (patched for live streaming via composite build)
    implementation(libs.media3.muxer)
    implementation(libs.media3.common)
    implementation(libs.media3.container)
    
    // NanoHTTPD for HTTP streaming server
    implementation(libs.nanohttpd.core)
    
    // MP4Parser for reliable MP4 box structure parsing
    implementation("com.googlecode.mp4parser:isoparser:1.1.22")

    // Vosk offline speech recognition (word-level timestamps) for transcript
    // editing + on-screen captions. Model is downloaded on first use, not bundled.
    implementation("com.alphacephei:vosk-android:0.3.47")

    annotationProcessor(libs.compiler)
    annotationProcessor(libs.room.compiler)

    implementation(mapOf("name" to "ffmpeg-kit-full-6.0-2.LTS", "ext" to "aar"))
    implementation(libs.smart.exception.java)
    implementation(fileTree(mapOf("dir" to "libs/aar", "include" to listOf("*.aar"))))

    // Unit Testing Dependencies (Local JVM tests - fast, no device needed)
    testImplementation(libs.junit)
    testImplementation("org.mockito:mockito-core:5.2.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    testImplementation("org.json:json:20240303")
    
    // Android Instrumented Testing (runs on device/emulator)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
