# PLAN — Ping-pong UNPARK (2026-07-03, ultracode diagnosis: 3 investigators + adversarial judge)
Full ranked plan + evidence: **tasks/PLAN_PINGPONG_UNPARK_full.json** (implementation agent: READ IT).
Supersedes guesswork about the 03a4b09 blackout. Sandbox Note 9 = SM-N960U **SNAPDRAGON** (sdm845,
OMX.qcom) — all Exynos-specific lore is VOID for this device.

## Verdict (two layers)
1. **CONFIRMED amplifier:** MasterPlaybackEngine has ZERO error handling (no onPlayerError; stock player
   :288; upstream handler log-only FaditorPlayerManager:137). ANY single-item failure = permanent
   error state = whole-timeline black. Un-park is FORBIDDEN until this is fixed, regardless of trigger.
2. **Trigger (65/35):** 65% = mid-playlist HEVC(hvc1)→AVC(avc1) codec-family swap on the single
   player/surface (reuse impossible on MIME change; documented Samsung black-screen class). 35% = the
   baked AVC stream itself out-of-capability (**~40 Mbps, 5× over the device's declared H.264 L4.0 cap**,
   from libx264 CRF20 on screen content + VFR collapse) rendering silent black with audio.
   No logs survive to settle it — rank-1's instrumentation settles it retroactively on first repro.

## Judge's corrections (bake into all future reasoning)
- "Transformer export decoded the baked file fine" was NEVER device-verified — parity was
  by-construction only. The stream hypothesis was never falsified. Export re-check is OWED on-device.
- AAR binaries (inspected): libx264 + h264/hevc_mediacodec PRESENT; libx265/openh264 ABSENT →
  HEVC bake = hevc_mediacodec ONLY, mandatory in-code fallback chain.
- Decoder instance exhaustion RULED OUT (16+16 concurrent on this device).

## Execution order (fix stack kills BOTH triggers + contains residuals)
1. **Rank-1 — Resilience + instrumentation (prereq for everything):** onPlayerError in
   MasterPlaybackEngine → map failing window → if reverse rep: poison that reversed URI (per-session set
   in SourceResolver), rebuild playlist (that clip degrades to forward reps ONLY — scoped, not
   project-wide), reseek to pre-error visual position, resume. Debug-gated media3 EventLogger on the
   gapless player. Fix the bake-complete auto-promote race: rebuildGeneration counter; stale bake
   promotions are DISCARDED.
2. **Rank-2 — Single-codec playlist:** bake HEVC via hevc_mediacodec (`-c:v hevc_mediacodec -pix_fmt nv12
   -b:v 10M -g 30 -tag:v hvc1`, NO -profile/-level flags — suspected 0xffffffc3 trigger), ReturnCode-checked
   chain: retry `-pix_fmt yuv420p` → fall back to rank-3 hardened libx264 (`-preset veryfast -crf 23
   -profile:v high -level 4.0 -maxrate 12M -bufsize 24M -pix_fmt yuv420p -g 30 -fps_mode passthrough`).
   Winning codec recorded in cache key/filename (bump hash; device cache empty).
3. Flip `Clip.PING_PONG_PARKED=false` (4 seams) ONLY after 1-2 land green.
4. **Device verification:** ping-pong on sandbox clip → NO blackout, true reverse (frame evidence,
   screenrecord+ffmpeg — screencap stale on this device); EventLogger logcat at the seam (which codec,
   reuse vs re-init); forced-failure drill (poison a URI manually or corrupt a cache file) → per-clip
   forward degrade + playback continues = rank-1 proven; **EXPORT the ping-pong project ON-DEVICE and
   frame-compare the reverse leg vs preview (the owed, never-run parity check)**; reverse-leg AUDIO listen.

## Status — ✅ COMPLETE 2026-07-03 (commit 72c8cd7, fully device-proven)
- [x] Rank-1 resilience (forced-corruption drill: per-clip forward degrade, NO blackout, live video)
- [x] Rank-2 bake chain — **hevc_mediacodec/nv12 won attempt 1 both bakes** (40.8Mbps AVC → 10.5Mbps HEVC
  hvc1 = matches sources; single-codec playlist; libx264 fallback host-validated, never needed)
- [x] Unpark flip (PING_PONG_PARKED=false, 4 seams live, chip undimmed on-device)
- [x] Device verify: zero black/freeze frames; EventLogger = video/hevc every window (verdict SETTLED:
  trigger was the codec-family swap, now impossible); **owed export parity RUN for the first time — export
  reverse leg matches preview, same cache file (OMX.qcom hevc decoder)**; reversed audio present/continuous.
- OWED TO USER: eyeball a live ping-pong wrap + ear-check reverse-leg audio quality (weird-by-nature OK).
