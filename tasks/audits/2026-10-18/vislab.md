# Viz Lab adversarial review — 2026-10-18

Reviewed HEAD `24fc23790` plus present working tree, read-only for application sources. No Gradle, APK build, emulator, phone, installation, or device measurements. This report owns GL/audio/performance and look persistence; the parent report separately covers overlay touch-through, toolbar state, and competing MediaProjections.

Supplement: the coordinating reviewer later used the owner-authorized Note 9 and confirmed V3's continuing paused render load. This specialist's source/headless results below are unchanged; fresh device evidence and the installed APK identity are in [phone.md](phone.md).

## Highest priorities

1. **V1 [P1] Failed saves are permanently acknowledged before disk I/O.** Transient storage errors can silently lose looks at restart.
2. **V2 [P1] Two different typed Words layers rebuild a 2048-square atlas twice every frame.** Headless production-code probe counted **1,440 one-MiB uploads and 120 mipmap builds across 60 frozen frames**, versus zero with a stable feed.
3. **V3 [P1] Pause continues full rendering and mutates feedback.** CPU/GPU work continues; additive echo looks can brighten instead of freezing.
4. **V4 [P2] Normal EGL teardown forgets to release/reset typed words.** Resuming a retained view after surface recreation can sample an invalid or reassigned texture.

P1 means fix in the next correctness/performance pass; P2 means a concrete defect with a narrower trigger. Findings below are static proofs unless an explicit headless result is provided. Headless GL counters measure submitted work, **not GPU timings, power, temperature, or real phone throughput**.

## V1 [P1] A failed look write is never retried

Location: `C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/vislab/room/VisLabSession.java:648-651`, `:679-690`.

Trigger: edit/create a look while the folder provider throws IOException or RuntimeException, e.g. temporarily unavailable removable storage, revoked SAF access, or a failed atomic write.

`sync()` stores the new text/name in `sent`/`sentName` before enqueueing `write()`. The catch only logs; it neither invalidates that acknowledgement nor schedules a retry. Subsequent `flush()`/sync sees `text.equals(sent.get(id))` and skips the failed look indefinitely until a different edit. `onKept()` is posted even when every write failed. After process restart, the older on-disk content wins. Deletions similarly remove `sent` and `files` before successful trashing, losing the information necessary to retry a failed deletion. A failed rename/write can also leave `sentName` inconsistent with the actual filename.

Remedy: distinguish pending revisions from successfully persisted revisions; acknowledge each successful write on the owning thread only if its revision still matches. Retain failed jobs, retry with backoff and on next flush, and surface the failure instead of firing a success notification. Preserve deletion jobs and original paths until trash succeeds. Test a filesystem that fails the first write/rename/trash and succeeds on the next flush, including a newer edit while the first job is pending.

## V2 [P1] Independent Words layers invalidate the same atlas every frame

Location: `C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/vislab/gl/VisRenderer.java:68-70`, `:559-566`; `C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/vislab/words/WordFeed.java:147-158`, `:198-202`, `:279-291`.

Trigger: combine two Words layers with distinct nonempty `text` values, e.g. a title and a tagline, a normal use of the new composable Lab.

Every typed layer uses the single renderer-wide `typed` feed. `typedText` changes A -> B -> A on each frame, calling `setTyped()` per layer. That creates a fresh Track, and `update()` drops all cached slot keys. Each draw then redraws/uploads its active 2048x128 RGBA slots and regenerates mipmaps for the entire 2048x2048 atlas. This defeats the atlas cache even when time and both strings are unchanged. A 0.6-second one-word loop has 12 active slots with the default 4-second back/3-second lead window.

**Actual production WordFeed headless counter probe:** after warming the atlas, 60 stable-time updates of one feed produced 0 uploads/0 mipmap builds. Alternating HELLO/WORLD as the production renderer does, for 60 stable-time pairs, produced 1,440 uploads/120 mipmap builds, 12 slots per draw. Source image volume = **1,509,949,440 bytes across those 60 frames** before driver details/mipmaps. This is arithmetic from actual calls, not measured phone bandwidth. This pressure is independent of the view resolution scaler, so lowering the screen buffer does not fix it.

Remedy: own/cache a feed per distinct text/track or layer identity, share immutable glyph data where possible, and free feeds when their owners disappear. Keep atlas slot reuse stable across repeated copies of the same word. Test two typed layers over several frames: each must retain its own content and unchanged frames must upload no glyph slots or mipmaps.

Reproduction source: `tasks/audits/2026-10-18/vislab_wordfeed_probe.py`. It compiles the actual WordFeed with tiny Android/GL stubs into an OS temporary directory. Stubs count texSubImage/mipmap calls; they do not emulate the GPU.

## V3 [P1] Pausing never stops work, and feedback is no longer a frozen picture

Location: `C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/vislab/view/VisTextureView.java:121-123`, `:208-237`; `C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/vislab/gl/VisRenderer.java:346-375`; `C:/+Projects/Screenrecorder/FadCam/app/src/main/assets/vislab/shaders/echo.frag:34`, `:49`, `:80`; `C:/+Projects/Screenrecorder/FadCam/app/src/main/assets/vislab/shaders/common.glsl:247-249`.

Trigger: press Pause on an echo look such as Meltdown, or open Joybot on top of the Lab (room onPause sets this same flag while the surface can remain available).

`setPaused` only changes a boolean. The Choreographer callback always reschedules, invokes FFT/feature analysis, evaluates layers, executes echo/layer/bloom/composite passes, and swaps buffers every display vsync. Only time and dt are zeroed; there is no visibility or lifecycle suspension in VisTextureView. When the platform destroys the surface it finally stops, so this finding applies while the paused/obscured surface remains alive, not indefinitely after all surface destruction.

There is also a visible correctness bug: echo uses `pow(DECAY, DT*60)`, so dt=0 preserves the previous canvas at full energy, then the renderer draws all under-echo layers onto it again. An additive light layer outputs zero coverage (`uAdd=1`), so the blend adds its light on each paused frame. The paused output brightens/saturates instead of holding still. Blur also still runs. The analyser itself clamps zero dt to 1/240, so sound-derived animation state moves despite the frozen scene clock.

**Headless actual FeatureExtractor/LiveAnalyzer check:** 240 calls with `dt=0` increased bass travel by 1.0158339 for a fixed test tone. No GPU result was measured.

Remedy: stop continuous frame scheduling and audio analysis when paused/hidden; cache the last composed output. Allow explicit dirty-frame renders for edits/resizes while paused without advancing feedback/simulation, and restore scheduling/reset frame timing on resume. A paused frame hash should stay identical over time, and frame submissions/audio analysis calls should settle to zero absent edits.

## V4 [P2] Typed words retain a texture ID across normal context recreation

Location: `C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/vislab/gl/VisRenderer.java:233-254`, `:267-270`; `C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/vislab/words/WordFeed.java:257-264`, `:297-312`; `C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/vislab/view/VisTextureView.java:330-341`.

Trigger: display typed words, then destroy/recreate the TextureView surface while retaining the view/renderer, such as a background/foreground surface cycle.

Normal `teardownEgl()` calls `renderer.release()` and destroys the context. That method releases `words`, but never releases or resets `typed`. The alternative `contextLost()` path does reset both, proving the asymmetry. Typed WordFeed keeps its nonzero texture ID and considers slots valid. On a new context, texture() does not allocate/repopulate the atlas. The old integer name can denote no initialized image or collide with a new render target, causing blank/corrupt words. Recreating the whole Activity builds a new renderer and masks this; the retained-view surface path is the affected case.

Remedy: release both word feeds on normal release, deduplicating if they reference the same object; clear both on context loss without issuing GL deletes against an absent context. Repeated same-view EGL destroy/recreate with typed words should reproduce identical text and show no GL errors.

## V5 [P2] The Lab's microphone stays open when the room pauses

Location: `C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/vislab/room/VisLabActivity.java:567-581`; `C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/vislab/audio/MicSource.java:62-69`, `:102-105`.

Trigger: select Microphone, then press the pause button, open Joybot, or otherwise cover/background the Lab without destroying the Activity.

The Activity calls `source.setPaused(true)` in both user pause and lifecycle pause. MicSource's method is a no-op, so AudioRecord and its read loop remain active until Activity.onDestroy/source replacement. Even when Android silences a background input, the app has not released its recorder. This wastes capture work while paused, keeps microphone ownership/indication longer than the room UX suggests, and can interfere with other app-owned recording paths. Phone-specific contention needs validation; unlike the floating overlay, the room does not use the recorder tap/recording guard.

Remedy: explicitly stop/release capture on lifecycle pause and reacquire on resume; decide/document separately whether user picture-pause should continue listening. Use a shared capture arbiter/RecordingTap instead of opening competing app-local microphones. Validate capture teardown with AudioRecord lifecycle counters and the microphone privacy indicator, including Joybot transitions and starting a screen recording.

## V6 [P2] Floating picture layers always upload a transparent pixel

Location: `C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/vislab/overlay/VisOverlayService.java:239-245`; `C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/vislab/gl/VisRenderer.java:653-674`; compare `C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/vislab/room/VisLabActivity.java:134`.

Trigger: float Meltdown, Trap Halo, or a custom look with a picked picture/logo.

Only the Activity installs `new Pictures(this)` through `setImageSource`; no other production caller exists. The service constructs the renderer without an image source, so imageTexture sees bmp=null and creates a 1x1 transparent texture for every image layer. The room's emblem/chosen picture silently disappears in the floating copy even though both hosts claim to show the same look.

Remedy: expose a shared image provider usable by all hosts and inject it in the floating service as well. Verify built-in default-logo and persisted content-URI pictures match room/overlay output.

## V7 [P2] Deleting picture layers retains their GPU textures until switching looks

Location: `C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/vislab/gl/VisRenderer.java:443-455`, `:653-682`, `:275-283`.

Trigger: repeatedly add a picture layer, let it render, then remove it while editing the same look.

refreshLook builds an alive-source set and deletes removed living simulation states, but never prunes `imageTex` or `imageRef`. Both maps retain the removed JsonObject and its uploaded texture until freeLook (look switch/release). A 1024-square RGBA image with full mipmaps is about 5.33 MiB; twenty removed layers can therefore retain roughly 107 MiB of GPU image storage. This is a size estimate, not device memory profiling. Custom compositing sessions can build a large GPU high-water mark despite a small current layer list.

Remedy: prune image textures/reference maps with the same alive set and delete each orphan once. Add/remove the same picture 100 times and confirm live GL texture ownership returns to baseline without switching looks.

## V8 [P2] Audio that stops arriving remains loud forever

Location: `C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/vislab/audio/LiveAnalyzer.java:58-67`; `C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/vislab/audio/RecordingTap.java:69-85`; `C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/vislab/overlay/OverlaySound.java:104-120`.

Trigger: pause a screen recording while its last PCM window contains sound, or lose/fail an audio source after it populated the ring.

LiveAnalyzer always reuses the ring's latest samples and never checks whether framesWritten advanced. RecordingTap does not write silence on pause; OverlaySound stays in RECORDING for paused recorder state and does not freeze the renderer. A failed source switch/NONE also leaves the ring intact. Consequently the last waveform window can drive bright reactive art indefinitely despite silence/no source. PcmRing.writeSilence exists but these lifecycle paths do not use it.

**Actual headless result:** write one second of a 100 Hz tone once, then call production LiveAnalyzer.step(1/60) 600 times with no writes. framesWritten remains 48,000, while level remains **1.0** and bass **0.8658336** after ten seconds of analysis time.

Remedy: define freshness in sample time, expose pause/end to the analyser, and feed zero windows/fade or hold the intended paused picture when data is stale. Do not merely reset the ring on each read: song playback intentionally reads delayed frames. Test recorder pause, permission revocation, decode failure, and clean source switching.

## V9 [P2] A custom floating look is lost on cold start before folder loading

Location: `C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/vislab/overlay/VisOverlayService.java:135-145`, `:476-484`; `C:/+Projects/Screenrecorder/FadCam/app/src/main/java/com/fadcam/vislab/room/VisLabSession.java:139-153`, `:703-770`.

Trigger: choose a custom floating look, stop the process, then start the floating visualizer before opening the Lab.

Session construction initially exposes built-in looks and asynchronously reads the user's folder. Service.onCreate immediately checks its remembered custom ID against that incomplete list, falls back to a built-in, and showLook overwrites the saved preference. The service has no session-loaded/listener subscription to restore the original choice after custom looks arrive. The picker later lists the look, but the persisted choice has already been replaced. A saved customized built-in can similarly start with its factory content and remain there until another explicit pick because showLook parses a detached JSON snapshot.

Remedy: expose a loaded/readiness signal and preserve a pending desired ID; apply/fallback only once loading completes. Subscribe hosts to relevant session content changes. Cold-start with a custom and customized built-in look and compare the first settled rendering with the persisted file.

## Current capability and architectural limits

- **Studio integration is not implemented at this HEAD.** No VisLab/visLook references exist in WaveformOverlayInstance, waveform preview, or export paths. S1-S5 in VISLAB_TODO explicitly describe unfinished work. This is an expected capability gap, not a broken shipped integration: no timeline item persistence, Rolodex selection, deterministic scrub/export feature track, or transcript feed exists yet.
- The Lab and floating renderer share shaders/scene format, and there is a clean explicit-frame renderer interface. That is a useful foundation, but integration must implement deterministic state reconstruction: simply calling a stateful renderer at a scrub timestamp advances at most six simulation ticks, not the full timeline history.
- Screen-space resolution adaptation is already present and correctly includes the final pass by resizing the actual window buffer. HeatGuard caps scale at 0.5 from SEVERE through recovery. These mechanisms do not control typed atlas upload cost, particle/agent counts, extra simultaneous renderers, or paused capture/render work.
- Current frame pacing targets the display refresh rate with no 30/60 fps budget or battery mode. On 90/120 Hz devices, light looks can render at the native high refresh even when a recording only needs 30/60. This is an optimization opportunity, not a claim that high refresh is inherently incorrect.
- The existing fixed-tick simulations cap catch-up at six and use the current frame's evaluated knobs/sound across every catch-up step. Do not promise deterministic preview/export/scrub equivalence before offline feature sampling, tick-by-tick evaluation, seed/reset ownership, and checkpoint/replay are implemented.

## Optimization sequence and validation

1. **Eliminate waste first:** fix V2/V3/V5/V7. These remove repeated work with no intended visual downgrade. The atlas probe establishes that a multi-Words case can drop from 24 slot uploads per frame to zero on unchanged frames; the whole-app speedup must still be measured.
2. **Introduce host budgets:** one shared analysis clock per sound source at 60 Hz, independent of screen refresh; explicit 30/60 fps caps, and pause/screen-off/hidden lifecycle gating. Give a floating/game/recording combination a lower default pixel/frame budget than the full-screen Lab. Use actual draw-cost and thermal headroom rather than only display interval to choose scale.
3. **Budget simulation separately from fill:** particle/slime state counts do not fall with view scale. Consider density/iteration budgets, lower-frequency simulation with interpolation, and cull/offload invisible work only with clearly specified state semantics. Avoid changing the owner-approved curling smoke merely because it is expensive; cache glyph content and correct lifecycle behavior first.
4. **Cache expensive setup off the critical frame:** prewarm shader variants, cache decoded pictures, and decode images off the GL thread. Atlas updates should update only changed glyph regions and avoid rebuilding a complete mip chain whenever feasible. Allocate half/bloom targets lazily if the look cannot use them.
5. **Build the Studio adapter around an explicit render session:** persisted look ID plus snapshot/revision policy, deterministic source-time feature track, transcript ownership, seek checkpoints, preview/export matching at 30/60 fps, and bounded GL-to-compositor transfer. Do not make per-frame bitmap readback the long-term architecture without profiling its bandwidth/copy cost.

Validation matrix: Note 9 baseline plus a modern 120 Hz phone; cheap/Words/particles/slime/reaction-diffusion/raymarch looks; one versus two distinct Words layers; 30/60/120 display pacing; paused/foreground/Joybot/background/screen-off; boxed/full floating over a real game with microphone/device/mixed audio while recording. Measure frame-time P50/P95/P99 and dropped frames, CPU thread time, GPU stage time, GL/bitmap allocations, encoder dropped frames, battery current/temperature and Android thermal status under consistent charging/ambient conditions. Run a 10-minute baseline and a 60-minute combined-load soak with cool-down/repeat controls. No overheating claim or 10x whole-app speedup is justified by this static/headless audit.

## Verification completed

- Existing VisLooksTest: **PASS, 26 primitives and 39 looks**.
- Existing VisFeatureParityTest: **PASS, 78 sampled frames and 21 kicks**.
- Both compiled/run with javac/java and cached Gson in a unique temporary output directory; no shared harness output was removed.
- Production WordFeed with stub Android/GL counters: stable feed 0 uploads/0 mipmaps versus alternating text 1,440 uploads/120 mipmaps over 60 frames.
- Production PcmRing/LiveAnalyzer with headless Context stub: stale tone remains level 1.0 after 600 unwritten frames; zero-dt calls still advance musical travel.
- Remaining lifecycle/persistence findings are verified by tracing current production source, not phone/UI tests. Existing JVM suites do not cover these GL/service/storage lifecycles.
