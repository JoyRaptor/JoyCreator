# Feedback — Timeline fidelity: waveform readability + accurate filmstrip (2026-07-03, user)

## User asks
1. Waveform in the timeline too averaged-out to line up words / see subtle quiet areas & onsets.
   "Limit of the software or visual choice?"
2. Filmstrip thumbnails are "almost entirely inaccurate" for navigation (friend tried to navigate by them).
   "How much perf hit to make them accurate?" Use case: scan-scroll to FIND an unexpected popup in a
   screen recording instead of blind-scrubbing.

## Ground truth (code-verified)
- WaveformExtractor.java:40 — `BUCKETS_PER_SEC = 60` peak buckets + per-band FFT, DISK-CACHED, and
  span-limited extraction already supported (CACHE_VERSION 3). So ~17ms data resolution ALREADY EXISTS.
  The averaged look is the RENDERER's doing → **visual choice, not a software limit.**
- EditorTimelineView.java:395 — `MAX_THUMBNAILS_PER_SEGMENT = 30`, keyframe-snapped (OPTION_CLOSEST_SYNC;
  exact OPTION_CLOSEST is used ONLY for the trim-edge preview bubble). Screen recordings have very long
  GOPs (seconds) → thumbs are sparse AND land far from their labeled time. Friend's confusion fully explained.

## Fix plan (shares the waveform's own extract-once/disk-cache/zoom-density architecture)
### W1 — Waveform render fidelity (SMALL, high payoff)
Renderer: per-pixel-column MIN/MAX envelope (peak-preserving) instead of averaging when buckets>pixels;
perceptual amplitude scaling (log/sqrt, ~-60dB floor) so quiet detail reads; optional soft RMS body under
the peak outline (classic DAW look = pattern recognition the user wants). Zero extraction cost.
### W2 — HD zoom tier (MEDIUM)
When zoom makes 1 bucket span >~2px, extract an HD tier (200-400 buckets/sec) for the VISIBLE span only
(extractor already does span-limited), disk-cached alongside; render whichever tier matches zoom.
Letter-level onsets at high zoom.
### T1 — Accurate filmstrip via one sequential sweep (MEDIUM-LARGE, the right architecture)
Do NOT per-thumb OPTION_CLOSEST (long-GOP = seconds of decode per thumb = jank). Instead: per source,
ONE background sequential MediaCodec decode sweep at reduced size grabbing a frame every N ms → disk
thumbnail cache (pattern-match the waveform cache). Sequential decode ≈ 4-10x realtime downscaled →
a 5-min screen recording sweeps in well under a minute, once, off-main. Progressive UX: keyframe thumbs
show instantly (today's behavior), accurate thumbs swap in as the sweep fills. RAM stays LRU-capped
(the long-wished thumbnail LRU lands as part of this). Result: dense, truthful strip at any zoom →
the popup-hunting scroll "just works".
### Perf answer for the user
Waveform: no hit (render-only) / tiny one-time re-extract for HD tier. Filmstrip: one-time background
sweep per source (~⅕ of clip duration), then cheaper than today at draw time (no random keyframe seeks).

## Queue position
After the v3 drag-UX core batch (same EditorTimelineView file — sequential rule). W1 is small enough to
ride along with any timeline-touching agent. T1 is its own work item; do W1+W2 first (bigger align-by-ear
payoff per token).

## Status
- [ ] W1 render fidelity  - [ ] W2 HD zoom tier  - [ ] T1 sweep-cache filmstrip
