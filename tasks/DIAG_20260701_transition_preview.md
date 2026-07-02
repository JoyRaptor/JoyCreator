# DIAG 2026-07-01 — Editor preview stutter at clip-to-clip boundaries

## Scope
Investigate-first task. Symptom: during Faditor editor PREVIEW playback, crossing a
clip-to-clip boundary looks jumpy/stuttery (users fear export does the same — it
doesn't; export is a separate, correct Media3 Composition pipeline in
`ExportManager.java`). Goal was a cheap, additive interim mitigation, NOT a
compositor/second-surface rework (that's roadmap Phase 5.3, `road_map.md` line 308).

## Playback architecture (short summary)

- **Single ExoPlayer, single MediaItem at a time.** `FaditorPlayerManager` (app/src/main/java/com/fadcam/ui/faditor/player/FaditorPlayerManager.java)
  wraps exactly one `ExoPlayer`. There is no `ConcatenatingMediaSource`, no
  playlist, no `MediaItem` queue. Trim bounds are NOT expressed via
  `ClippingConfiguration` — they're manual (`trimStartMs`/`trimEndMs`) with
  seek-and-poll enforcement, because fragmented MP4 / SAF `content://` sources
  don't reliably support `ClippingConfiguration`.
- **Boundary crossing = full re-prepare.** `FaditorEditorActivity.advanceToSegment()`
  (~line 7303) is called every time the playhead reaches the end of a clip
  during playback (`isAtEnd` branch, ~line 6896, plus loop/still/ping-pong
  variants). For a video clip it calls `loadClipForPlayback(nextClip)` →
  `playerManager.loadClip(clip)` → `preparePlayer(clip)` which does:
  ```java
  player.setMediaItem(mediaItem);
  player.prepare();
  ...
  pendingSeekMs = trimStartMs;   // seek fires once STATE_READY arrives
  ```
  This tears down the current `MediaPeriod`/extractor/decoder state and starts
  a **cold prepare** of the next clip's Uri: extractor sniff/probe, container
  parsing, hardware `MediaCodec` allocation + `configure()`, first buffer
  decode, then a seek to `trimStart`. None of this begins until the boundary is
  reached — there is zero pre-buffering/priming of the next clip while the
  current one is still playing. `DefaultLoadControl` is never customized
  (grepped project-wide — no `LoadControl`/`setBufferDurationsMs` anywhere in
  `ui/faditor`), so ExoPlayer uses stock buffering targets, which are tuned for
  streaming-start latency, not for gapless mid-timeline seam continuation.
  This is polled from a 50ms main-thread `Handler` loop
  (`playheadHandler`/`playheadUpdater`, line 514-529) — the re-prepare is
  kicked off synchronously from that tick.
- **Transitions are a bitmap overlay bolted on top, not part of the ExoPlayer
  timeline.** When a `Transition` exists at a seam (`getTransitionAtSeam`),
  entering its window (~line 6879-6891) does NOT change what ExoPlayer is
  doing — the outgoing clip keeps playing live via `playerView`. Instead,
  `TransitionPreviewOverlayView`/`GlTransitionPreviewView` composite a
  **decoded bitmap** of the incoming clip's frame on top, driven by
  `renderTransitionPreview()` (line 7030) → `decodeTransitionFrame()` (line
  7187), which uses `MediaMetadataRetriever.getFrameAtTime(sourceMs*1000,
  OPTION_CLOSEST_SYNC)` **synchronously on the main thread**, once per tick.
  When `progress >= 1f` the code calls `hideTransitionPreview()` then
  `advanceToSegment(seam+1, true)` — i.e. the transition window still ends in
  the exact same hard ExoPlayer re-prepare cut described above.

## Root cause(s) — ranked by contribution to the observed jump

1. **Dominant: cold ExoPlayer re-prepare at every clip boundary (transition or
   not).** `setMediaItem()`+`prepare()` on the main thread, fired exactly at
   the boundary with no advance warning, causes: probe/extractor init →
   codec `configure()` → first-frame decode → seek-to-trimStart → first
   rendered frame. On mid-range/older hardware (Note 9 sandbox included) this
   is commonly 100-400ms of nothing-new-rendered, which reads as a freeze/skip
   right at the cut. This affects **every** boundary, with or without a
   `Transition` configured at that seam. This is very likely what the user
   means by "especially around transitions" too, since a transition simply
   adds more visual activity right before the same hard cut, making the
   subsequent stall more noticeable by contrast.
2. **Contributing, transition-specific: `decodeTransitionFrame` cache-key bug
   makes the incoming clip appear frozen during the transition window itself**
   (independent of the boundary-crossing cut in #1). The cache key is
   `uriString + "@" + outW + "x" + outH` — **it does not include `sourceMs`**.
   The very first call for a given clip+size during a transition decodes one
   frame and caches it under that key; every subsequent call during the same
   transition (as `progress`, and therefore the intended `sourceMs`, advances)
   hits the cache and returns that same stale bitmap. Confirmed by reading
   `decodeTransitionFrame()` (line 7187-7226) — `cachedTransitionFrame(key)` is
   checked before any use of `sourceMs`. The code comment at line 7162-7165
   ("decoding once ... keeps the scrub smooth") shows this was an intentional
   design for the **scrubbing** use case (`updateScrubTransitionPreview`,
   paused, dragging a handle), but the same cache is shared with the **live
   playback** path (`renderTransitionPreview` called every 50ms tick while
   `transitionPlaybackActive`), where freezing the incoming clip's visual
   during a wipe/dissolve/push is actively wrong, not just a missed
   optimization.
   - **However:** I pulled a real FadCam-recorded sample
     (`FadCam_20260621_145148.mp4`) and ran `ffprobe` frame-type analysis —
     keyframes (I-frames) land roughly **once per second** (0.000s, 1.022s,
     ...). Since `getFrameAtTime(..., OPTION_CLOSEST_SYNC)` snaps to the
     nearest keyframe (not the exact requested timestamp), and transitions are
     capped at 100-2000ms (`Transition.java` line 60/66,
     `Math.max(100, Math.min(2000, durationMs))`), a very large fraction of
     transitions will only ever span **zero or one** keyframe boundary
     regardless of the cache bug — i.e. even a "correct" cache key would often
     still show the same or nearly the same frame throughout, because
     `OPTION_CLOSEST_SYNC` itself is coarse. This caps the real-world payoff of
     fixing the cache key alone.

## Mitigation options considered (ranked)

| # | Option | Class | Risk | Payoff | Throwaway once 5.3 lands? |
|---|---|---|---|---|---|
| 1 | Fix `decodeTransitionFrame` cache key to include a time-bucketed `sourceMs` | Additive, preview-only bitmap cache fix | Low (isolated to `FaditorEditorActivity`'s transition-overlay helper methods; no player/export changes) | **Low-medium, capped by `OPTION_CLOSEST_SYNC` keyframe granularity** (see above) — could ship but benefit is not reliably demonstrable on FadCam's own ~1s-GOP footage for short transitions | Yes — moot once GL compositor renders every frame live |
| 2 | Switch `OPTION_CLOSEST_SYNC` → `OPTION_CLOSEST` for exact-frame decode | Additive (same call site) | **High** — `OPTION_CLOSEST` forces MediaMetadataRetriever to decode forward from the last keyframe to the exact frame on the main thread, on every distinct timestamp requested during real-time 50ms-tick playback. This is well-documented as slow (tens to 100+ms per call depending on codec/device) and would very plausibly make the transition window itself *stutter worse* than today, trading "frozen frame" for "dropped ticks." | Would fix option 1's cap, but at meaningful regression risk | Yes |
| 3 | Tune `DefaultLoadControl` buffer durations smaller/larger, or call `player.setMediaItem`+`prepare` for the *next* clip ahead of the boundary while clip A is still playing | Additive per the task's allowed list ("earlier next-item preparation") | **Not actually implementable as additive** — a single `ExoPlayer` only holds one `MediaItem`; calling `setMediaItem()` for clip B *replaces* clip A immediately (there is no queue), so "pre-preparing the next item" on the *same* player instance means it stops showing clip A right now. Doing this properly needs either (a) a playlist/`MediaItem` queue on the same player (a structural change to how media items are built — explicitly disallowed), or (b) a second player instance to warm in the background (explicitly disallowed). **No low-risk variant of this exists within the stated constraints.** | N/A — not shippable | N/A |
| 4 | `setPauseAtEndOfMediaItems`-style tweak | Additive per task's allowed list | N/A — this API only has meaning on a playlist-based player (pauses playback when a `MediaItem` in a multi-item timeline ends); with a single-`MediaItem` player there is no next item to pause before, so this doesn't apply | None | N/A |
| 5 | Short visual easing/mask over the swap (e.g. a brief crossfade-to-black or a freeze-frame hold on the outgoing clip's last decoded frame while ExoPlayer re-prepares, using the *existing* `TransitionPreviewOverlayView`/`transitionPreviewOverlay` hook) | Additive — reuses an existing overlay view already in the render tree, no new surfaces | Low-medium — needs the overlay to already have a captured bitmap of the outgoing clip's last frame to hold during the prepare gap; capturing that reliably (e.g. via `PlayerView`'s surface) without adding a new capture pipeline is nontrivial and risks jank of its own if done naively (e.g. `getBitmap()` off a `TextureView` needs the view to be backed by a `TextureView`, not a `SurfaceView`, which is a `PlayerView` config concern) | Yes | 

## Decision

**No mitigation shipped.** Rationale:
- The dominant root cause (#1 in the causes list — cold ExoPlayer re-prepare
  with zero pre-buffering) has **no low-risk additive fix** under this task's
  constraints. Every real fix (playlist/queued MediaItems, a second warm
  player, or a compositor) is explicitly out of scope and is exactly what
  Phase 5.3 is for.
- The one candidate that IS additive and isolated (transition frame cache-key
  fix) has a real but hard-to-verify payoff on FadCam's own footage because of
  `OPTION_CLOSEST_SYNC` keyframe snapping, and its "make it actually visible"
  companion change (`OPTION_CLOSEST`) is itself higher-risk than the problem
  it's fixing (main-thread exact-frame decode during real-time playback ticks).
- On-device verification (screenrecord + ffmpeg frame extraction, Note 9
  sandbox, serial `SANDBOX_SERIAL`) was attempted for the plain-cut boundary
  case (project's first two clips, ~0.8s in). `adb shell screenrecord`
  repeatedly truncated short capture windows (1-2s instead of the requested
  5-6s) on this device/build of platform-tools, which is a capture-tooling
  limitation, not a finding about the app. A clean 5s capture was eventually
  obtained but landed mid-clip-2 rather than exactly on the intended boundary
  due to UI automation timing drift (no live view available while scripting
  blind taps). Given (a) the source-level mechanism is unambiguous and fully
  explained by reading `FaditorPlayerManager`/`FaditorEditorActivity` directly,
  and (b) no safe mitigation exists to demonstrate anyway, further time was not
  spent chasing frame-perfect on-device video evidence for a change that isn't
  being shipped.

Per the task's hard rule ("if you cannot demonstrate improvement, REVERT the
change and say so — a clean revert + good diagnosis is a successful outcome"):
**no code was changed.** This report is the deliverable.

## What would actually fix it (for the Phase 5.3 backlog)
- Pre-warm the next clip's decoder before the boundary: needs either a second
  pooled `ExoPlayer`/`MediaCodec` instance decoding clip B into an offscreen
  surface a few hundred ms before the cut (classic gapless-playback pattern),
  or a proper multi-`MediaItem` playlist on one player with
  `Player.Listener.onMediaItemTransition` handling the trim-bounds swap instead
  of a full `setMediaItem`+`prepare`.
- Once a GL compositor exists (5.3), transitions should sample decoded frames
  from live decoder output (already-composited textures), not
  `MediaMetadataRetriever.getFrameAtTime`, eliminating cause #2 entirely and
  making cause #1 moot (the compositor holds both clips' decoders live across
  the seam by construction).
