# Painting speed — measured on the phones, and the plan (2026-10-11, Lead)

Tool: the debug stroke replay (`androidkit/bench/StrokeReplay`, `FrameStats`, `joybrush-android/bench/BenchRunner`).
It plays a synthetic 240 Hz S Pen stroke through the real input path on a fresh, never-saved drawing, and logs one JSON
line per run to `adb logcat -s JoyBrushPerf`:

```
adb shell am start -n com.fadcam.beta/com.fadcam.joybrush.JoyBrushBenchActivity \
  --es jb_bench "fastC,scribble,spiral" --es jb_bench_brushes "joybrush.ink,media:wet:Round" --ei jb_bench_runs 3 \
  [--ez jb_media_legacy true]
```

"busy" is each frame bracketed by `glFinish` (CPU + GPU, serialised). The Adreno timer queries returned impossible
values on both Notes. `fin` is the time spent waiting in that finish, which is roughly the GPU work. Gaps and pen-to-frame
latency are counted from pen-down to pen-up only. The first run of each group is dropped as warm-up.

## Note 9 (SM-N960U, Adreno 630, 1080×2220 @ 60 Hz): the floor

| Brush, 3 s scribble | Frames drawn | GPU `fin` p50 | Latency p50 / p95 | Stalls > 2 frames |
|---|---|---|---|---|
| Ink / Sable / stamp Pencil | ~182 (60 fps) | 5–9 ms | 8 / 16 ms | 0 |
| Realistic pencil, before 7640fadd | 84 (28 fps) | 25 ms | 18 / 42 ms | ~45 |
| Realistic pencil, after 7640fadd | 158–176 (55–60 fps) | 10 ms | 9 / 19 ms | ~3 |
| Watercolour (wet:Round) | 30 (10 fps) | 98 ms | 57 / 131 ms | 24 |
| Oil (paste:Oil round) | 40 (13 fps) | 54 ms | 38 / 81 ms | 38 |

Pencil window reloads per scribble went from ~87 to ~9 (Note 20: from 184 to 12). The window fix did not change
watercolour or oil.

## Causes (code analysis 2026-10-11, estimates until the per-pass split is measured)

**Oil**
- Every paste step (one per 0.3 mm of pen travel) re-processes a square of half-size hypot(halfW, lenMax) + 0.3 mm. For
  Oil round that is about 416² px, against a real footprint of about 15–30k px.
- The dab shader leaves pixels outside the footprint unchanged, so a tight oriented rect is exact. This is in progress
  on `lead/oil-tight-rect`.
- CPU cost is 17–21 ms/frame, from about 55 uniforms × 3 passes per step built as maps.

**Watercolour**
- Each frame runs 4 substeps × (flux, update into 4 × RGBA32F, copy back), about 1.7 KB/px/frame.
- `wetRect` only grows and never shrinks until 240 simulated seconds of idle, so after a stroke the whole area keeps
  simulating on bare paper.
- `touch(wetRect)` re-renders the look over the whole wet area every frame, at ~172 texel fetches per px.

**Frame-locked water (a look issue, not only speed)**
- `wetFrame` advances 4 × 1/120 s per drawn frame with no wall-clock accumulator.
- The lab and the vector replay run it at 60 fps, which is the canonical look. At 10 fps on the Note 9 the water ages
  6× less per mm of pen, so the live look there is wetter than the reference.
- Fix: step the water per pen time (once per 4 samples at 240 Hz), and run idle water on a fixed 30 Hz wall clock.

## Plan, in order

| # | Change | Exactness | Expected |
|---|---|---|---|
| 1 | Oil: tight oriented paste rect | bit-exact (headless proof) | oil GPU 54 → ~10–15 ms |
| 2 | Media CPU: per-frame-constant uniforms set once; no map merging per pass | exact | 11–21 ms → ~3–5 ms CPU |
| 3 | Wet: ping-pong p0/p1 by substep parity (no copy pass); scissored input clear | exact | −25 % sim bytes |
| 4 | Look: `texelFetch` for integer-aligned taps; render only fresh dab rects every frame, older water at ≤ 30 Hz with exact catch-up at pen-up | exact end state | look 25–40 → ~8–12 ms |
| 5 | Wet formats: w0 → RG32F, run → RG16F, bakes to single-channel | exact (unread channels) | 10–15 % |
| 6 | `jb_wet_update.frag`: fetch neighbours once, constant ring offsets, early-out at w = 0 | exact | 15–25 % of update |
| 7 | Activity-tile culling (async reduction readback); shrink `wetRect` to where water is | exact if conservative | 1.5–3× mid-stroke; ends the post-stroke tail |
| 8 | Water on pen time / wall clock (above) | changes the live look **toward** the reference | device-independent look |
| 9 | Adaptive governor: only knobs whose end state is exact (look cadence, spreading water steps across frames) | exact end state | smooth feel on slow phones |

Owner A/B before shipping #8: the look he judged "S-tier" may have been seen at a different frame rate.

## Display, all brushes (after the media work)
- Partial redraw: redraw only the damaged screen rect, plus an EGL partial update / buffer age.
- Front-buffer low-latency stroke tip, plus motion prediction.
- The off-screen tile cull is done (a02012be).
