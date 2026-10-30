# Note 9 verification — 2026-10-18

Owner explicitly authorized the plugged-in Note 9 during this audit. The coordinator held `jb-phone.lock` as `audit-20261018`, selected only the Note 9 by model, and released it after testing. No installation, uninstall, application source edit, Gradle invocation, AI provider request, or existing artwork edit occurred.

## Build identity and limits

- Device: Note 9, Android 10, display override 1080×2220, plugged in/full battery.
- Installed app: `com.fadcam.beta`, version 4.0.0-beta9, versionCode 37, targetSdk 36; last update 2026-10-18 11:40:13.
- Installed base APK SHA256: `9f01e9419cbca36abfa2c2c34e577ec15cb5391541b221a3d19ba6f7fd478c01`.
- This differs from the primary checkout's existing APK (`594f28e7ac371f2c467a50fd6ee4716b2523512e0a5c2611d02fa7c0360be7c0`). No exact checked-out Git revision is asserted for the installed binary. Phone observations apply to this installed artifact; source findings separately reference reviewed main `24fc23790` and named WIP checkpoints.
- No sustained thermal soak, power/current measurement, modern-Android touch test, or full product acceptance matrix was performed.

## 1. Confirmed: pause continues expensive Viz Lab rendering

Opened Viz Lab through the lobby and continued the existing **Written in Stars** look with the demo song. At approximately 12:13:05 EDT, tapped Pause; the screenshot shows the Play triangle, confirming paused UI state. Afterwards the same `vislab-gl` thread continued submitting 60 fps, with occasional lower samples.

At 12:13:06–12:13:10 the application logged 60 fps at a 432×888 buffer, with roughly 13.2–14.6 ms total GPU work per frame. Later samples at 12:13:41 onward still show 60 fps and around 13 ms GPU cost. Android `top -H` sampled the GL thread at 27.5% and 29.0% CPU (one-core percentage convention). The paused output was not a stable held composition.

This verifies V3's continuing workload. It does not isolate which feedback layers account for all visual changes, and does not establish overheating. Battery-temperature readings during the short session were 28.7–31.5°C, with charging and multiple workloads as confounders.

Evidence: [paused screen](evidence/vislab-paused.png), [paused render log](evidence/vislab-paused-log.txt).

## 2. Confirmed: visualiser phone-audio consent stops an active screen recording

At 12:14:58, started a disposable screen recording from Capture with Android's normal consent dialog. Enabled the floating Visualiser using the recording toolbar. Its controls explicitly said **hearing the recording** while the recorder elapsed time advanced.

Tapped the floating speaker control and approved its second Android capture dialog at 12:16:00. The recorder then logged:

```text
12:16:00.913 MediaProjection stopped externally
12:16:00.913 handleStopRecording: Stopping screen recording
12:16:01.023 Recording stopped. Duration: 61.6 seconds
12:16:01.254 ScreenRecordingService destroyed
```

The screen returned to **Start Screen Recording** while the floating visualiser remained. This is a phone reproduction of R1, not just a platform-contract inference. No Stop button was pressed during this sequence. One approximately 62-second test recording of the Capture screen was created; it was left available as test evidence, without deleting existing media.

Evidence: [running recorder and its audio source](evidence/recorder-before-phone-audio.png), [recorder after speaker consent](evidence/recorder-stopped-after-phone-audio.png), [stop log](evidence/recorder-projection-stop-log.txt).

## 3. Confirmed: toolbar On label survives closing the visualiser

Closed the floating visualiser with its own ×, then reopened the recorder menu. `dumpsys activity services` contained neither `VisOverlayService` nor `ScreenRecordingService`; `dumpsys media_projection` was null. The menu still showed **Visualiser On** in green. This reproduces R3. A later tap on this stale On row would start the overlay rather than turn it off.

Evidence: [stale On label after closure](evidence/recorder-stale-on-label.png).

## 4. Isolated Joy Brush microbench: current installed artifact

Used the exported debug `JoyBrushBenchActivity` and existing `BenchRunner`, whose benchmark branch opens a fresh unsaved canvas and returns before restoring Working; Working saves are explicitly skipped in bench mode. First ran one ink smoke, then `fastC` twice each for ink, watercolor Round, and oil All-round. No destructive persistence bug was deliberately exercised on the owner's library.

| Brush | Run | Stroke frames sampled | Input latency p50, ms | Busy-frame max, ms | Reported simulation texture bytes, MiB |
|---|---:|---:|---:|---:|---:|
| Ink | 1 | 7 | 7.15 | 48.90 | n/a |
| Ink | 2 | 7 | 8.81 | 30.92 | n/a |
| Watercolor Round | 1 | 1 | 214.29 | 271.76 | 215 |
| Watercolor Round | 2 | 2 | 184.86 | 240.02 | 219 |
| Oil All-round | 1 | 2 | 223.46 | 278.76 | 219 |
| Oil All-round | 2 | 3 | 37.70 | 523.81 | 219 |

Evidence: [six original JSON benchmark reports](evidence/joybrush-bench.jsonl). Reported `legacy` was false. The texture totals are the instrumented simulation inventory, not total app GPU/native/Java memory. The small synthetic stroke yields too few media frames for representative steady-state FPS or meaningful tail-percentile claims. Startup, paper baking, window movement and synchronization are included; this was an exploratory microbench, not a controlled comparison of optimization branches. It confirms substantial stalls remain in this artifact and identifies phases to profile; it does not establish a 10× achievable whole-app improvement.

Before and after both benchmark activities, hashed every file under the app's external-files `joybrush` directory: **13 files, zero differences**. This includes the working archives, library metadata and thumbnails in that directory. It is not a claim of a full phone-wide data integrity scan.

## Exit state

Returned the phone to Android Home. No active MediaProjection, screen-recording service, or floating visualiser remained. The pre-existing annotation/menu service remained as found. Phone lock released. Destructive JoyBrush save/open reproductions require test-owned storage or dedicated isolated debug probes; no such reproduction is claimed here.
