# The first Play build: report (SHOW-1, 2026-11-14)

The first signed release build of Joy Creator, and the first time code shrinking (R8) ever ran on it.
Card: [SHOW-1](../jobs/SHOW-1_play_build.md). Key backup steps for the owner: [PLAY_UPLOAD_KEY.md](PLAY_UPLOAD_KEY.md).

## What exists

| What | Where | Numbers |
|---|---|---|
| Signed Play app bundle (the file to upload) | `D:\JoyCreator-builds\joycreator-play-v37-candidate-20261114.aab` (outside the repository) | 106.0 MB file; version code 37, version name 4.0.0; app ID `cc.joycreator.app` |
| What a phone downloads | measured on the matching release APK (arm64), an upper bound: Play's own split APKs are smaller | **145.2 MB** against Play's 200 MB limit (the debug APK was 172-177 MB) |
| 16 KB alignment (Play rule for Android 15+) | every native library in the bundle, checked by reading each library's ELF headers; zip offsets also checked with `zipalign -c -P 16` on the APK | **21 of 21 aligned** |
| Signature | `jarsigner -verify` on the bundle, `apksigner verify` on the APK | signed with the upload key, SHA-256 `C7:60:CD:...:A9:A6:93` |
| Parts removed from the Play manifest (checked in the built bundle) | `MANAGE_EXTERNAL_STORAGE`, the accessibility screenshot service, the screenshot shortcut activity, the privacy black screen, the 15 disguise launcher aliases, FadCam's static launcher shortcuts | all absent |

**This bundle is a candidate, not the final upload.** It was built from the local branch
`claude/play-candidate-20261114`: this lane's `play` flavour plus the Smart Detection switch to MediaPipe
(`claude/mediapipe-detector-20261114`), which the launch-docs lane merges only after a live detection on the phone.
Without that switch the bundle carries `libtask_vision_jni.so`, which is not 16 KB aligned, and Play rejects it.
Rebuild from `joy-creator` once the switch has landed (steps at the end).

Only arm64 libraries ship (`abiFilters = arm64-v8a`), so Play will offer the app to 64-bit ARM phones only
(nearly every phone sold since 2019).

## Smoke test on the Note 9 (release build installed beside the debug app, 18:33 to 19:08)

Release APK from the same build as the bundle, installed as a new app (`cc.joycreator.app`) next to the debug app
(`com.fadcam.beta`), which was not changed. Fresh install, test folder `JoyCreatorPlayTest` (not the debug app's
folder). Phone 33.4 to 34.0 °C throughout, no throttling. No crash in the crash log at any point.

| Wing | Result | What was done | Screenshot |
|---|---|---|---|
| First run | ✅ | intro, permissions, "Before we start", folder step | `01`, `03b`, `05` |
| Folder without all-files access | ✅ | made a new folder through Android's picker; recording, export, drawing and looks all saved into it | `07` |
| Lobby | ✅ | every tab opens; the Library shows every new item with a thumbnail | `08`, `42` |
| Capture | ✅ | 7.6 s back-camera recording saved (7.7 MB MP4) | `13` |
| Studio export | ✅ | opened the recording, exported: 8.6 MB MP4 plus the `.joyproject` in the folder | `17`, `19` |
| JoyBrush paint and save | ✅ | three strokes, saved on leaving (`Drawing 1.joybrush`), reopened with the strokes intact | `29`, `31` |
| TextLab | ✅ | "Dark Steel" look renders live; saved to `Supplies/Looks/Dark Steel.text.json` | `24`, `25` |
| VisLab | ✅ | "Sound City" plays to the demo song | `38` |
| SpriteLab | ✅ | sliced a picture into a 3 x 3 sheet; saved (appears in the Library as a sprite sheet in the Untitled project) | `40`, `42` |
| Joybot chat | ✅ | chat opens, suggestions shown, "Transcribe this" ran its tool to the end ("1 word" on a near-silent clip) | `32`, `34` |
| Avatar | ✅ opens | the room opens; face tracking was not started (needs a rigged picture and a face) | `36` |
| Removed parts | ✅ | Lock and privacy has no privacy black screen row; Appearance has no app-icon (disguise) row | `46`, `47` |

Screenshots: [tasks/screens/play-build-20261114/](../screens/play-build-20261114/).

**Second round (19:30, after the store lane's permission review):** the Play manifest now also drops
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `BLUETOOTH_SCAN` and `WRITE_MEDIA_VIDEO`, closes four developer entry points
and names the two special-use services. Rebuilt, installed over the test copy (data kept), and checked: the
installed app no longer holds any of the removed permissions (`dumpsys package`); Android's battery-optimisation list
(the new target of the battery row) opens on the Note 9; Joybot made a title card on top of the video ("Added 1
layer", `52`). Not exercised: the onboarding battery row itself (replaying onboarding needs cleared app data, which
is never done) and the separate slide renderer (the title card used the built-in design). The GitHub cloud build of
the same commit succeeded (8.7 min, unsigned bundle) once release builds without the key stopped trying to sign.

**Not tested:** Smart Detection finding a person (the launch-docs lane owns it), speech to text with words in it,
avatar face tracking, LAN Remote, screen recording, the Note 20 or the tablet. Which speech engine did the
transcription is not visible on a release build.

### Found on the way (not caused by the Play build; for other lanes)

1. Studio's "Your recordings" list says "No recordings found yet" right after a recording was saved into the chosen
   folder (the recording did show in the Library). Not checked whether the debug app does the same.
2. "Record" from the lobby lands on FadCam's old camera home; Back from there does not return to the lobby and the
   bottom bar disappears; its side menu is FadCam's "Home Options" with Mini Apps (Torch, Compass, Metal Detector...).
   DOOR-3 (first run) territory: a tester meets it in the first minute.
3. The Library labels a TextLab look "VISLAB LOOK".
4. The Settings card still says "Theme, language and icon" in the Play build, which has no icon choice.
5. The version shown to testers is FadCam's "4.0.0" (version code 37). Changing the name is a one-line change; the
   owner may want "1.0".

## What changed in the code (all on `claude/play-build-20261114-1a01c6`)

- `app/build.gradle.kts`: a `play` product flavour beside `default` (`BuildConfig.PLAY_BUILD`), its app ID read from
  `joycreator.playApplicationId` in `gradle.properties` (the one place to change it); release signing reads
  `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` from `JOY_*` environment variables or the
  git-ignored `local.properties`.
- `app/src/play/AndroidManifest.xml`: removes the parts listed above, for the Play flavour only.
- Four settings rows hidden when `PLAY_BUILD` (app icon, privacy black screen, the screenshot shortcut in two places);
  `isPrivacyBlackModeEnabled()` is false in the Play build.
- R8 rules (`app/proguard-rules.pro`): no renaming (`-dontobfuscate`; the app is GPLv3, renaming hid nothing and
  breaks reflection, JNI and saved JSON); all of our own code kept; keep rules for OpenCV, ffmpeg-kit, Vosk/JNA,
  MediaPipe/protobuf, TensorFlow Lite, mp4parser, NanoHTTPD, Media3, AppLock; FadCam's rule that stripped warning and
  error logs is narrowed to debug/verbose/info.
- Two build fixes R8 forced, both also true for the debug build: `settings.gradle.kts` now points the remaining
  overlapping Media3 modules (effect, datasource, decoder, extractor, database) at the patched fork's identical 1.8.0
  copies, so the classes are not there twice; the app's leftover copy of `ui/type/Type.java` (moved to `studiokit`
  on 10-05) is deleted.

## How to rebuild the upload (after the detector switch lands)

1. Fresh worktree off `origin/joy-creator`; copy the main folder's `local.properties` and add the four signing lines
   (the password is in the owner's password manager; until it is backed up, in `D:\JoyCreator-keys\BACKUP-ME-then-delete.txt`).
2. Raise the version code in `app/build.gradle.kts` for every new upload (Play refuses a repeated code).
3. Take `jb-gradle.lock`, then: `./gradlew.bat :app:bundlePlayRelease -Pkotlin.compiler.execution.strategy=in-process --no-parallel`
   (about 7 to 12 minutes; it ran with about 2 GB of free memory).
4. Output: `app/build/outputs/bundle/playRelease/app-play-release.aab`. Check it:
   `python -I tools/play/check_play_bundle.py app/build/outputs/bundle/playRelease/app-play-release.aab`
   (module sizes, 16 KB alignment of every library, removed parts absent; ends `RESULT: OK`).
   **Or in the cloud** (the cloud-builds lane, untested with this flavour as of 11-14):
   `powershell -NoProfile -File tools/cloud-build.ps1 run joy-creator playRelease` (unsigned, about 10 min on
   GitHub), then `tools/cloud-build.ps1 sign` on this PC with the `JOY_*` variables set.
5. Native debug symbols are in the bundle (`BUNDLE-METADATA`, 13 MB, not downloaded by phones); Play Console uses
   them for crash reports.
