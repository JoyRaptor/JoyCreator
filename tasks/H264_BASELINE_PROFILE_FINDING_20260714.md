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

**Status:** investigated + designed, NOT implemented. Load-bearing fork change → do with full context.
