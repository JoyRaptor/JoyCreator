# STU-VOICE · One-tap cached dialogue cleanup

Studio Voice audio review, November 21

Branch: `codex/audio-studio-voice`, based on `2644c7bca`. Review this branch as a unit. It includes the November 19 voice feature rebased onto the landed audio-cache race fix. The held AUD-BAKE timeline-coordinate change is independent.

The quiet-selection regression ran the production renderer against desktop FFmpeg. Before this change a second tap on the same 16-second quiet selection ran four FFmpeg analysis decodes again. Afterward the repeat used zero decodes and zero media probes, including after a process restart. Changing the trim, changing the source revision, damaging the receipt or providing no reliable revision forced fresh analysis. The first eight-second excerpt tested from the original rain demonstration produced processed audio and reused the same encoded file on a second request. These are cache and execution checks. Listening quality during speech and Note 9 power use remain open.

Run `python tools/jvm-harness/run-studio-voice-cache.py` with JDK 17+, FFmpeg and ffprobe on PATH. An optional first argument supplies a private recording with unprocessed rain during seconds 4–12. No recording is included in the repository.

## Earlier prototype evidence

**Android test build, November 19** · Branch `codex/note9-studio-voice-20261119`, based on `7c3e1f04d`.

## Use it

In Studio, open an audio clip, choose **Clean → Studio Voice** and keep editing after the render. For an embedded video soundtrack, use Studio’s Extract audio tool first. The same project-owned file feeds playback and export. **Undo** or **Clean → Revert** restores the original. Cancelling a render leaves the clip on its original source.

## What runs on the phone

- The app bundles a 297,646-byte speech-trained RNNoise model. It selects a stronger or gentler mix from up to 12 seconds of sampled audio. Relative room level, its variation and low-frequency energy inform the selection. The neural gains continue adapting during the render. For a long clip whose probes indicate clean audio, a low-rate full-span check looks for missed noise before offering to retain the original. These are scene heuristics, with no speaker recognition or dedicated dereverberation model.
- One worker renders off the UI thread. No neural inference runs during normal playback. Mono and stereo caches use AAC at 48 kHz and 192 kbps. For labeled 5.1 rear and 5.1 side, the engine cleans the center and renders lossless FLAC at the input rate so the other channels retain their samples. Other multichannel layouts keep their source and show an explanation. Existing mixer/export settings still govern the final delivery format.
- A fresh source handle is used for every pass over a picked file. The cache key includes source URI and revision metadata, trim, model and recipe version. Unknown provider revisions cause a fresh bake. Identical requests with known revisions reuse the project file. A probe checks channel count and duration before publication. A cancelled or failed operation never swaps the clip source. A 35 ms compensated filter delay, short preroll, tail flush and bounded-memory silence prefix preserve timeline coordinates.
- Relevant code lives in `StudioVoiceRecipe`, `StudioVoiceRender`, `AudioDrawerTabs`, and `FaditorEditorActivity.applyStudioVoice`. Model origin and digest ship in `app/src/main/assets/studio_voice/MODEL.txt`.

## Evidence

| Check | Result |
|---|---|
| User’s rain recording | All three stereo samples selected strong. The production filter graph plus AAC cache yielded **20.70 dB** of speech-level-matched quiet-window reduction, versus **20.69 dB** for CapCut Isolated voice on the same 380 windows. The original’s quietest 20% and strongest 25% define the comparison. This measures pauses. It does not isolate residual rain during speech or establish a listening preference. |
| Controlled noise during words | Synthetic dry speech plus background at +5 dB speech-to-noise ratio. On active 20 ms windows, gentle improved gain- and lag-aligned SI-SDR over the noisy input by **2.97 dB** for fan/hum, **3.25 dB** for traffic-like rumble and **4.90 dB** for clatter. An overlapping second voice scored **0.25 dB worse**. These are desktop runs of the same signal-processing settings. |
| Clean speech | Short 9.43-second dry fixture and 18.86-second dry narration selected original, including the full-span confirmation. The original path avoids re-encoding. |
| Stereo | Regression exercises equal-and-opposite channel polarity. Probe energy is measured across the channels before making the cleanup decision. |
| 5.1 | Desktop FFmpeg with the compiled production graph and final FLAC codec. At 48 kHz rear and 44.1 kHz side, a 1.2–3.8 second edit preserved all five bypass channels sample-for-sample and had the expected 3.8-second source-clock length. Center alignment within one sample. A 24-bit channel-ID test also preserved all five bypass channels exactly. |
| Build | Android ARM64 debug APK assembled. Five JUnit regression tests passed. The bundled ARM64 `libavfilter.so` contains the required filters. |
| Device and listening | No attached Note9 or configured emulator. Physical install, editor tap-through, cache hit after reopen, cancellation mid-render, export routing on device, long-clip energy/temperature and real-room blind listening remain open. |

## Next acceptance checks

Use a fresh test project on a Note9 with the same signing identity as its current installation. Time the first render, repeat the same edit, scrub and export, then undo and reopen. Include a late trim, backgrounding or cancelling mid-render, low storage, and 5.1 center dialogue with identifying signals on every surround channel. Listen blind at matched level on rain under speech, quiet word endings, overlapping voices, traffic, music and reflective rooms. Rate speech quality, background quality and overall preference separately. Competing speakers and long room reflections need separate evaluation before extending the product claim.

Verified Note9 package: `com.fadcam.beta` 4.0.0-beta9, APK Signature Scheme v2, ARM64. APK SHA-256 `38b5afda3b19b30cc7a275a396735300d9ea6d0772f4ca090fc081d2f17a8a58`. Explorer copy: `scratchpad/voice-cleanup-review/JoyCreator-Studio-Voice-Note9.apk`.
