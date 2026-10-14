# Joy Creator

> **DRAFT 2026-10-14 — awaiting the owner's review.** Replaces FadCam's README (roadmap 1.6). Links to the store,
> website and screenshots get filled in when they exist.

**A whole creative studio on your phone.** Draw and animate, edit video and sound, build sprites and characters, and
make visualisers — one app, made by an artist, for people who make things one-handed on the couch as much as at a desk.

## The wings

| | |
|---|---|
| **JoyBrush** | Painting, illustration and animation: pressure and tilt brushes, real papers, layers, boards for frames and sprite sheets. |
| **Studio** | Video and audio editing: a layered timeline, captions from on-device transcription, transitions, keyframes, slides, fast export. |
| **SpriteLab** | Sprite sheets and animations, named cells, straight into Studio. |
| **VisLab** | Music visualisers. |
| **Capture** | Camera, screen and dual-camera recording that lands straight in your projects. |
| **Joybot** | An AI helper that can see your project and do the fiddly parts. Bring your own key; free models work. |

## Your work is yours

Everything you make lives in **one folder you choose** — plain files you can open, move and back up with any file
manager. Removing the app never takes your work with it. No account, no ads, no tracking: see [PRIVACY.md](PRIVACY.md).

## Building

Android Studio (current stable), JDK 17, Android SDK 36, NDK and CMake 3.22.1. Open the project root and build the
`defaultDebug` variant. The app module depends on `studiokit` and on the JoyBrush modules under `joybrush/` and
`joybrush-android/`.

Contributors and agents: start with [START_HERE.md](START_HERE.md).

## Credits and licence

Joy Creator grew out of **[FadCam](https://github.com/anonfaded/FadCam)** by anonfaded, and keeps its recorder at
its core. Thank you. Like FadCam, Joy Creator is free software under the **GNU General Public License v3** — see
[LICENSE](LICENSE). "Joy Creator" and "JoyBrush" are the owner's marks and are not covered by the code licence.
