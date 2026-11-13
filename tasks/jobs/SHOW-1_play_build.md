# SHOW-1: The Play build

**Milestone:** M4  ·  **State:** ⬜  ·  **Owner:** open  ·  **Checked:** 2026-10-27 against STATE §1 and LAUNCH_STRATEGY.md

## Why it matters
Google Play rejects apps that look like stalkerware or misuse permissions. These FadCam-era parts must leave
the Play build (a full build can keep them for side-loading if the owner wants).

## 16 KB native-library alignment and app size (2026-11-13, launch-and-truth lane)

Play needs 16 KB aligned native libraries. Measured on the debug APK: 7 of 23 failed. A build-config fix (CMake linker flag, vosk-android 0.3.75, MediaPipe 0.10.26.1) built and **22 of 23 now pass**; the last, `libtask_vision_jni.so` from tensorflow-lite-task-vision 0.4.4, has no aligned release (options listed). Not yet installed or run on a phone: speech to text and avatar tracking need a try. Debug APK 172.1 MB against Play's 200 MB limit; no release build exists yet. Details: [launch_checks_20261113](../audits/launch_checks_20261113.md).

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
