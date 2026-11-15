# SHOW-1: The Play build

**Milestone:** M4 (launch week 1)  ·  **State:** 🟡 candidate bundle built and phone-tested  ·  **Owner:** Play build lane (Claude Opus 5.5, 11-14)  ·  **Checked:** 2026-11-14 against the built bundle and the Note 9

## 11-14: where it stands (read this first)

Full report: [PLAY_BUILD_REPORT_20261114.md](../launch/PLAY_BUILD_REPORT_20261114.md). Owner's key backup:
[PLAY_UPLOAD_KEY.md](../launch/PLAY_UPLOAD_KEY.md).

- [x] `play` flavour without the disguise icons, the accessibility screenshot service, all-files access and the
      privacy black screen (manifest overlay `app/src/play/` plus four hidden settings rows); FadCam's cloud was
      already gone (S19); LAN Remote stays (owner 10-17). The `default` debug build is unchanged.
- [x] App ID from one property (`joycreator.playApplicationId` in `gradle.properties`, now `cc.joycreator.app`,
      **owner's S1 still owed**: permanent once uploaded).
- [x] Upload key made outside the repository (`D:\JoyCreator-keys\`), signing reads passwords from git-ignored
      `local.properties` or `JOY_*` environment variables. **Owner's backup owed.**
- [x] First R8 release build: two duplicate-class fixes, keep rules, no renaming. Signed bundle 106 MB; phone
      download 145.2 MB (limit 200); 21 of 21 native libraries 16 KB aligned.
- [x] Installed beside the debug app on the Note 9; every wing smoke-tested ✅ (table in the report).
- [ ] **The upload bundle**: rebuild from `joy-creator` after the launch-docs lane merges the Smart Detection
      switch (`claude/mediapipe-detector-20261114`); the 11-14 bundle is a candidate built with that branch merged
      locally (`claude/play-candidate-20261114`, not pushed). Without the switch Play refuses one library.
- [ ] Owner: S1 app ID, Play Console account, key backup, then the upload to a closed test.
- [ ] Not tested: Smart Detection on a live subject, speech with words, avatar face tracking, LAN Remote.

## Why it matters
Google Play rejects apps that look like stalkerware or misuse permissions. These FadCam-era parts must leave
the Play build (a full build can keep them for side-loading if the owner wants).

## 16 KB native-library alignment and app size (2026-11-13, launch-and-truth lane)

Play needs 16 KB aligned native libraries. Measured on the debug APK: 7 of 23 failed. A build-config fix (CMake linker flag, vosk-android 0.3.75, MediaPipe 0.10.26.1) built and **22 of 23 now pass**; the last, `libtask_vision_jni.so` from tensorflow-lite-task-vision 0.4.4, has no aligned release (options listed). Not yet installed or run on a phone: speech to text and avatar tracking need a try. Debug APK 172.1 MB against Play's 200 MB limit; no release build exists yet. Details: [launch_checks_20261113](../audits/launch_checks_20261113.md).

**Evening 11-13:** the last failing library is removed by switching Smart Detection to MediaPipe's detector (branch `claude/mediapipe-detector-20261113`, built, 21 of 21 libraries aligned). **Not merged: no device was attached to run Smart Detection, speech to text or face tracking after the library upgrades.** See section 4 of the audit.

**11-14, Note 9:** rebuilt on main (`claude/mediapipe-detector-20261114`), 21 of 21 libraries aligned, the detector loads through MediaPipe without error. Still owed (need a person, a dictated clip, the avatar room): detections on a live subject, speech to text after the Vosk upgrade, avatar face tracking. Section 5 of the audit.

## Done when
- [ ] A Play build variant without: the 30 disguise icons; the accessibility service used for screenshots;
      MANAGE_EXTERNAL_STORAGE; the privacy black screen; cloud and streaming.
- [ ] It builds, installs on the Note 9 beside the debug app, and every wing works.
- [ ] Uses the app ID from S1.

## Where it stands
Listed since 9-09 (old roadmap Lane 1, items 1.1–1.4; STATE §1 "must leave the Play build"); not started.
Needs a build the owner's watcher doesn't make. Reasons per item: [LAUNCH_STRATEGY.md](../LAUNCH_STRATEGY.md) §3, §5.

## What is left
- [ ] Add a `play` product flavour that leaves those parts out (manifest, resources, code paths).
- [ ] Build it under the build lock; install beside the debug app; smoke-test every wing.
- [ ] Check storage still works without all-files access (the Joy Creator folder uses Android's picker, so it should).
