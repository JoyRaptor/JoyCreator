# Launch checks: app size and 16 KB alignment (jobs L12, L14; measured 2026-11-13)

Read-only measurements of an existing build output. **No build was run.** No release (minified) build output exists on
this machine, so these are **debug** numbers: they are an upper bound for size, and the alignment result applies to the
same native libraries a release build would ship.

Sample: `C:/Temp/t13/studio.apk`, built 11-13 07:31 from the TextLab speed work (the newest APK found; 5 other
APKs in that folder have the identical size). It is the `defaultDebug` arm64-v8a APK (the app builds arm64 only:
`abiFilters = arm64-v8a`).

## 1. Size against Play's 200 MB limit (L12)

| Measure | Value |
|---|---|
| APK file size (what a phone downloads, debug) | **176.8 MB** |
| Uncompressed content | 211.1 MB (3007 files) |
| Earlier samples | 169.5 MB (atlas ledger APK, 10-30), 171.2 MB (10-30), 170.2 MB (11-04 live-save APK) |
| Play's base-module download limit | 200 MB (check Play Console's current wording; an app bundle may add asset packs on top) |
| Margin | about **23 MB** before the limit, on a debug build |

The growth since 10-30 is about 5-7 MB. **A minified, shrunk release bundle should be smaller, but that was not measured:
do not quote a release number until `bundleRelease` has been built.**

Where the bytes are (compressed, in the APK):

| Part | MB |
|---|---|
| Native libraries (lib/arm64-v8a) | 87.8 |
| `joybrush/` (engine resources) | 27.1 |
| assets | 14.2 |
| res | 9.1 |
| resources.arsc | 5.9 |
| dex files (about 35 of them, debug, not shrunk) | about 20 in the 6 largest |

Largest single files: libopencv_java4.so 24.0, libmediapipe_tasks_vision_jni.so 14.0, libavcodec.so 13.1, libvosk.so 8.9,
libtask_vision_jni.so 7.3, assets/models/efficientdet_lite1.tflite 6.1, libavformat.so 5.9, libavfilter.so 4.0.
OpenCV, MediaPipe, the task-vision library and the detection model are about a quarter of the APK: they are what Smart
Detection and the avatar tracker use (decision L13: keep, size is fine, while the margin holds). Budget for new fonts,
models and packs: stay under **about 20 MB** until a release bundle gives a real number. Removing the 11 unused FadCam
classes (S10 / L34) saves very little size.

## 2. 16 KB page alignment of native libraries (L14)

Play requires native libraries to be 16 KB aligned for apps targeting Android 15+ (target here is 36). Method: read each
`.so` in the APK with a script, check the ELF `PT_LOAD` alignment, and check the uncompressed `.so` offsets in the zip
(`useLegacyPackaging = false`, so the libraries are stored uncompressed). The repository's `check_elf_alignment.sh`
was not used because it needs `zipalign` (not on this machine's PATH); the result below was computed directly.

* Zip offsets: **all 23** `.so` files are stored uncompressed at 16 KB-aligned offsets (good).
* ELF `PT_LOAD` alignment: **16 of 23 are fine; 7 are 4 KB aligned and FAIL**:

| Library | Alignment now | Comes from | How to fix |
|---|---|---|---|
| `libggml-base.so`, `libggml-cpu.so`, `libggml.so`, `libwhisper_jni.so` | 4096 | our own native build (`app/src/main/cpp/CMakeLists.txt`, whisper.cpp), NDK 27.2 | link with `-Wl,-z,max-page-size=16384` (NDK r27 does not do it by default), or move to NDK r28+ |
| `libvosk.so` | 4096 | `com.alphacephei:vosk-android:0.3.47` (prebuilt) | newer vosk-android, or rebuild the library; not verified which version is aligned |
| `libmediapipe_tasks_vision_jni.so` | 4096 | `com.google.mediapipe:tasks-vision:0.10.14` (prebuilt) | a newer MediaPipe release; not verified which one |
| `libtask_vision_jni.so` | 4096 | `org.tensorflow:tensorflow-lite-task-vision:0.4.4` (prebuilt) | newer task-vision or drop it; not verified |

**Verdict: not ready.** Play will reject or warn on a build with these seven. This needs a build-lock session (it changes
`app/build.gradle.kts` / `CMakeLists.txt` and dependency versions), so it is listed as a follow-up, not done here.
The `// Fix 16KB native library alignment` comment in `app/build.gradle.kts` does not fix anything: nothing follows it.

### Exact command for the session that fixes it
After the change, build and check the real release output (take `jb-gradle.lock` first; about 3.2 GB RAM):

```bash
./gradlew assembleDefaultRelease        # or bundleDefaultRelease then unzip the base module's lib/
"$ANDROID_HOME/build-tools/36.0.0/zipalign" -c -P 16 -v 4 app/build/outputs/apk/default/release/app-default-arm64-v8a-release.apk | tail -3
./check_elf_alignment.sh app/build/outputs/apk/default/release/app-default-arm64-v8a-release.apk
```
Every library must print ALIGNED. (The exact output path depends on the splits config; use `find app/build/outputs -name "*.apk"`.)
Alternative without zipalign: Android Studio, Build > Analyze APK, which flags 16 KB alignment per library.
