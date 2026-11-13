# Joy Creator

> Beta. **Get it:** [Google Play link: PLACEHOLDER until the listing exists] · **Website:** [PLACEHOLDER] ·
> **Contact:** [PLACEHOLDER, owner to give the public email].
> Features are at different stages of proof; [tasks/STATE.md](tasks/STATE.md) says honestly which are which.

**A whole creative studio on your phone.** Draw and animate, edit video and sound, build sprites and characters, and
make visualisers — one app, made by an artist, for people who make things one-handed on the couch as much as at a desk.

## The wings

| | |
|---|---|
| **JoyBrush** | Painting, illustration and animation: pressure and tilt brushes, real papers, layers, boards for frames and sprite sheets. |
| **Studio** | Video and audio editing: a layered timeline, captions from on-device transcription, transitions, keyframes, slides, fast export. |
| **SpriteLab** | Sprite sheets and animations, named cells, straight into Studio. |
| **VisLab** | Music visualisers built from parts you can mix, used inside Studio videos. |
| **TextLab** | Film-poster title looks (chrome, gold, neon, fire...) that any title can wear. |
| **Capture** | Camera, screen and dual-camera recording that lands straight in your projects. |
| **Joybot** | An AI helper that can see your project and do the fiddly parts. Bring your own key; free models work. |

## Your work is yours

Everything you make lives in **one folder you choose** — plain files you can open, move and back up with any file
manager. Removing the app never takes your work with it. No account, no ads, no tracking: see [PRIVACY.md](PRIVACY.md).

## Building

You need Android Studio (current stable), JDK 17, Android SDK 36, NDK 27.2.12479018 and CMake 3.22.1.
Two sources live **outside** this repository and must be present before Gradle will configure the app:

1. **whisper.cpp** (speech to text, built natively). Clone it next to this folder, or point at it from
   `local.properties`:
   ```
   git clone --depth 1 https://github.com/ggerganov/whisper.cpp ../whisper.cpp
   ```
   or add `whisper.cpp.path=/your/path/whisper.cpp` to `local.properties`. Without it the native build stops with
   "WHISPER_DIR not set".
2. **Patched Media3** (used by Studio's export). Clone it to `/tmp/media3-patched`, or set
   `media3.patched.path=/your/path` in `local.properties`:
   ```
   git clone --depth 1 https://github.com/anonfaded/media3-patched.git /tmp/media3-patched
   ```
   Gradle only warns if it is missing, but the build then uses stock Media3 and some export behaviour differs.

Then open the project root and build the `defaultDebug` variant (`./gradlew assembleDefaultDebug`). The app module
depends on `studiokit` and on the JoyBrush modules under `joybrush/` and `joybrush-android/`. Release signing reads
`KEYSTORE_*` entries from `local.properties`.

*Not yet proved: nobody has followed only this README on a clean machine; the two external folders above were found by
reading the build files, 2026-11-13.*

Contributors and agents: start with [START_HERE.md](START_HERE.md). The FadCam documentation this project
inherited is kept in [tasks/archive/fadcam-docs/](tasks/archive/fadcam-docs/README.md).

## Credits and licence

Joy Creator grew out of **[FadCam](https://github.com/anonfaded/FadCam)** by anonfaded, and keeps its recorder at
its core. Thank you. Like FadCam, Joy Creator is free software under the **GNU General Public License v3** — see
[LICENSE](LICENSE). "Joy Creator" and "JoyBrush" are the owner's marks and are not covered by the code licence: see [TRADEMARK.md](TRADEMARK.md).
