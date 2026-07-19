# H.264 baseline-profile export — scoping finding (2026-07-14, Opus-4.8)

**TL;DR:** The roadmap item *"low-bandwidth export … the true H.264-baseline-profile part still needs
an ExportManager encoder-profile hook"* is **mis-scoped**. It is NOT reachable from ExportManager alone.
Media3 **deliberately ignores the requested H.264 profile** and hardcodes `AVCProfileHigh` on API ≥ 29.
Delivering baseline requires a small, principled **patch to the load-bearing media3 fork**
(`media3-patched/…/DefaultEncoderFactory.java`) plus device verification of the actual output codec
profile. Do it with full context — it rebuilds the whole media3 fork and touches load-bearing code.

## Why the naive hook is a no-op
`ExportManager.export()` already builds the encoder factory at
[ExportManager.java:357](../app/src/main/java/com/fadcam/ui/faditor/export/ExportManager.java) when
quality != HIGH:
```java
builder.setEncoderFactory(new DefaultEncoderFactory.Builder(context)
    .setRequestedVideoEncoderSettings(new VideoEncoderSettings.Builder()
        .setBitrate(bitrate)
        .build())
    .build());
```
Adding `.setEncodingProfileLevel(AVCProfileBaseline, <level>)` here does **nothing** for H264. Proof, in
the fork:
- `VideoEncoderSettings.setEncodingProfileLevel` javadoc (line ~153): *"Profile settings will be ignored
  when using DefaultEncoderFactory and encoding to H264."*
- `DefaultEncoderFactory.adjustMediaFormatForH264EncoderSettings` (lines 750–809) runs for every H264
  export (called at line 373-374) and, on **API ≥ 29** (our sandbox SM-N960U is API 29):
  ```java
  int expectedEncodingProfile = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh;   // line 756
  ...
  mediaFormat.setInteger(MediaFormat.KEY_PROFILE, expectedEncodingProfile);        // line 772 — UNCONDITIONAL overwrite
  if (!mediaFormat.containsKey(MediaFormat.KEY_LEVEL)) {                            // level IS guarded…
      mediaFormat.setInteger(MediaFormat.KEY_LEVEL, supportedEncodingLevel);
  }
  ```
  Note the asymmetry: KEY_LEVEL is only set if not already present, but **KEY_PROFILE is overwritten
  unconditionally** — clobbering the profile that lines 358-366 set from the requested
  `VideoEncoderSettings`. (Interesting: the API 24–25 branch, lines 794-805, already *defaults* to
  `AVCProfileBaseline`. It's only 26+ that force High.)

## Minimal principled patch (media3 fork)
Make `adjustMediaFormatForH264EncoderSettings` respect an already-requested profile, mirroring how it
already guards KEY_LEVEL. Two clean options:

**Option A (smallest):** guard the profile overwrite in all three SDK branches:
```java
if (!mediaFormat.containsKey(MediaFormat.KEY_PROFILE)) {
    mediaFormat.setInteger(MediaFormat.KEY_PROFILE, expectedEncodingProfile);
}
```
Then in ExportManager, for `Quality.LOW`, call
`.setEncodingProfileLevel(MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline, <level>)`. Caveat: the
caller must supply a *valid* level (both profile AND level must be non-NO_VALUE to reach line 365, per
lines 358-360), and an unsupported level risks MediaCodec rejection. Picking the level is the fiddly bit.

**Option B (more robust, preferred):** thread the requested profile into
`adjustMediaFormatForH264EncoderSettings` and use it as `expectedEncodingProfile` when it's Baseline —
the method *already* computes the supported level itself via
`EncoderUtil.findHighestSupportedEncodingLevel(encoderInfo, mimeType, expectedEncodingProfile)`
(line 767). So the caller only needs to request the profile; the level is auto-derived and guaranteed
supported. This avoids the level-picking landmine entirely. Also verify the encoder actually supports
Baseline first (it does on essentially all devices — Baseline is CDD-required per the line 800 comment).

Keep it **purely additive**: default (High/Original) exports must stay byte-identical. Only `Quality.LOW`
(the "Low bandwidth" chip, which sets 720p + Low) opts into Baseline.

## Verification plan (needs the device)
1. Watcher rebuilds the media3 fork (slow) — confirm `:app:packageDefaultDebug` with 0 `error:` lines.
2. Regression: export a normal HIGH/ORIGINAL project → must still complete cleanly (unchanged path).
3. Low path: export with the "Low bandwidth" chip → must complete (no encoder rejection).
4. Prove the profile actually changed: the output KEY_PROFILE is hard to read without tooling. Easiest
   is a **temporary** `FLog.d` in `adjustMediaFormatForH264EncoderSettings` printing the final
   `mediaFormat.getInteger(KEY_PROFILE)` — run a Low export, read logcat, confirm it's
   `AVCProfileBaseline` (value 1) not `AVCProfileHigh` (value 8) — then REMOVE the temp log. (Or write a
   tiny MediaExtractor check on the output file.)

## Files
- `media3-patched/libraries/transformer/src/main/java/androidx/media3/transformer/DefaultEncoderFactory.java`
  — `adjustMediaFormatForH264EncoderSettings` (lines 750-809), and the requested-profile carry-through
  at lines 358-371 + the supported-profile validation near lines 566-571.
- `app/src/main/java/com/fadcam/ui/faditor/export/ExportManager.java` — the `!qualityIsDefault` block at
  lines 354-365 is where `Quality.LOW` would request Baseline.
- `app/src/main/java/com/fadcam/ui/faditor/model/ExportSettings.java` — `Quality { HIGH, MEDIUM, LOW }`.

**Status:** DEVICE-PROVEN (2026-07-19 15:38, Fable, Note 9) — ffprobe on a flag-ON Low-bandwidth
export: `profile=Baseline, level=31` (720x1280), with the export process's own encoder logging
`setupAVCEncoderParameters with [profile: Baseline] [level: Level31]`. Flag restored to OFF.

**⚠️ THE FIRST DEVICE TEST CAUGHT DEAD CODE:** the initial flag-ON export came out `profile=High`
because `settings.gradle.kts` substituted muxer/common/container/exoplayer but NOT
`media3-transformer` — the patched DefaultEncoderFactory never shipped; the Maven transformer
(which unconditionally clobbers the profile to High on API≥26) was in the APK. Fixed by adding
`substitute(media3-transformer) → :lib-transformer` (comment in settings.gradle.kts). Lesson for
every future media3-patched change: a patch is INERT unless its module is in the substitution
list — compile-green in the fork proves nothing about the APK.

**Owed:** one default-quality (flag-OFF) export regression check — expect `profile=High`
unchanged now that the fork's transformer ships (its High path is documented byte-identical,
but the substitution swaps the whole module's provenance, so eyeball one normal export).

Previous status: IMPLEMENTED (2026-07-19, Opus-4.8) — plumbing landed, default OFF, both modules compile green.
Runtime ffprobe proof is a device errand (recipe below). NOT committed.

### What was implemented (Option B — thread original profile, no level-picking)
1. **media3 fork** `DefaultEncoderFactory.java`:
   - Call site (was line 374): `adjustMediaFormatForH264EncoderSettings(...)` now passes a 4th arg
     `requestedVideoEncoderSettings.profile` — the **original** requested profile off the factory field.
     Comment `// FadCam patch:` explains why we read the original (not `supportedVideoEncoderSettings`):
     the validation reset (the profile/level guard in `findVideoEncoderWithClosestSupportedFormat`, was
     lines 566-573) wipes `supported…`.profile→NO_VALUE whenever no level is supplied, so a level-less
     Baseline request would be dropped. The original field is never touched by that reset.
   - `adjustMediaFormatForH264EncoderSettings` signature gained `int requestedProfile`.
   - **SDK ≥ 29 branch:** after HDR profile selection, if `requestedProfile == AVCProfileBaseline` AND
     `expectedEncodingProfile` is still the `AVCProfileHigh` default (HDR didn't force one), switch
     `expectedEncodingProfile` to Baseline. The existing `findHighestSupportedEncodingLevel(…, profile)`
     call then auto-derives a guaranteed-supported level → no invalid-level landmine. The unconditional
     `KEY_PROFILE` set now writes Baseline; `KEY_LEVEL` stays guarded as before.
   - **SDK ≥ 26 branch:** same Baseline honoring (for API 26-28 devices). Both branches are grep-able via
     `// FadCam patch:`.
   - Untouched: SDK 24-25 branch (already defaults to Baseline) and the validation reset (irrelevant to
     our path since we read the original profile field).
2. **app** `ExportManager.java`:
   - New default-OFF constant `private static final boolean REQUEST_BASELINE_PROFILE = false;`.
   - In the `!qualityIsDefault` encoder-factory block, the `VideoEncoderSettings.Builder` conditionally
     calls `.setEncodingProfileLevel(MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline,
     VideoEncoderSettings.NO_VALUE)` when the constant is true (level NO_VALUE on purpose — the fork
     auto-derives it). Added `import android.media.MediaCodecInfo;`. Default OFF ⇒ path byte-identical.

### How the validation reset (lines ~566-573) is handled
NOT patched. We sidestep it: `adjustMediaFormatForH264EncoderSettings` receives the profile straight from
`requestedVideoEncoderSettings` (the factory's original field), which the reset never mutates. The reset
still nulls `supportedVideoEncoderSettings.profile`/`.level` (so the level-less request never hits the
lines 358-366 `KEY_PROFILE`/`KEY_LEVEL` setter with a bogus level — a good thing), while the fork picks up
Baseline downstream from the untouched original.

### Compile verification
`$env:TEMP='C:\Users\JoyRaptor\gtmp'; $env:TMP=$env:TEMP` then from `FadCam/`:
`.\gradlew.bat :media3-patched:lib-transformer:compileDebugJavaWithJavac` → BUILD SUCCESSFUL (17 executed,
recompiled DefaultEncoderFactory; only pre-existing benign enum/deprecation warnings).
`.\gradlew.bat compileDefaultDebugJavaWithJavac` → BUILD SUCCESSFUL (app green). Never used `--rerun-tasks`.

### Device ffprobe repro recipe (orchestrator errand)
Precondition: flip `REQUEST_BASELINE_PROFILE = true` in ExportManager.java (temporary; it's OFF by default),
rebuild + install debug APK on the SM-N960U (API 29).
1. In FadCam Faditor, open any project, tap the **"Low bandwidth"** export chip (sets 720p + Quality.LOW →
   `!qualityIsDefault`, so the encoder factory with the Baseline request is used). Export.
2. Pull the output file, e.g.:
   `adb pull /sdcard/Movies/FadCam/<exported>.mp4 C:/Users/JoyRaptor/gtmp/baseline_test.mp4`
3. `ffprobe -v error -select_streams v:0 -show_entries stream=profile,level,codec_name -of default=nw=1
   C:/Users/JoyRaptor/gtmp/baseline_test.mp4`
   → expect `codec_name=h264`, `profile=Constrained Baseline` (or `Baseline`). Default/OFF or a HIGH
   export must instead show `profile=High`.
4. (Alt, no ffprobe) temporary `FLog.d` in `adjustMediaFormatForH264EncoderSettings` printing
   `mediaFormat.getInteger(KEY_PROFILE)` — Baseline = 1, High = 8 — then remove it.
5. Regression: with the constant still true, run a normal HIGH/ORIGINAL export → must still complete and
   report `profile=High` (that path never sets the encoder factory, so it's unaffected). Then restore
   `REQUEST_BASELINE_PROFILE = false`.
