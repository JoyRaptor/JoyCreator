# Board polish acceptance — specialist handoff, 2026-10-02

Polish means matching the locked owner design and making painting dependable. This is an acceptance matrix, not a new design or a claim of device verification. Lead owns integration; owner owns visual acceptance. Review against §K/§K10 and the HTML source (browser inspection was blocked; owner authorized source inspection).

| State / requirement | Specialist evidence | Integration / Note 9 gate |
|---|---|---|
| K1 tokens, opacity, shared fonts, 40 dp targets, hover labels | Pure layout numbers; shared Type/fonts; native view tests | Verify readability on light, dark and mixed artwork, Frost on/off; exact screenshot comparison |
| K2/K10 every kind icon-only at rest; frame number only after frame 1 | Core tests | No stray outlines/tabs after deselection; no artwork changes |
| K2 brackets at 48 dp; 300 ms departure | Core geometry/state tests | Hover/paint across each edge, zoom/pan/density; stable contrast without flicker |
| K2 hover loop | Pure hover rules + native mouse start/stop/restore test | S Pen and mouse only; own held timing; restore actual frame ID; no select/save/undo; pause/graphics loss stops |
| K3 satellite/title/size/selection ring and handles outside pixels | Numeric core tests | Verify all density-scaled baselines and corner poses; controls do not steal painting in gaps |
| K3 lock / nailed multi-frame move / typed sizing | Core lock/rules/top-left helper | Lead validates mutations, confirmation and one undo; resize cannot leak into pixels |
| K4 120 ms to 12%, 300 ms back; ruler 35% | Core tests + interrupted fade tests | Use one BoardChromePenFade per scene with monotonic clock; rapid strokes do not flash; stop scheduling after endpoints |
| K5 fused peg/held-tick strip/scroll/lift/insertion/fade | Numeric core tests; shared SpriteLab controller harness | Native hold/drag/remove/reorder and one undo; stable frame-ID snapshot; SpriteLab regression comparison |
| K10 HOLD play opens FPS, tap still plays | Native long-press test (no release tap) | Connect fps stepper and own-board timing; hold cannot briefly start playback |
| K6 icon and 236 dp glass presentation | Existing app asset + core sheet/native input tests | Lead dispatch/range validation/Studio adaptation/image/sprite presets remain incomplete; unavailable actions disabled |
| K7 full-canvas tile, only ring + exit switch, off-screen docking | Core output/docking/tiled-flag tests | Full-strength repeats, cross-edge strokes, first-wrap saved flag, one-time warning; canvas restore/save/reopen/undo |
| K8 shelf, grid, badges, preview, reduced motion | Core numeric/state tests; bounded wiggle raster reuse | Actual sequence/preview and all-layer swap, one undo; sub-grid never exports |
| K9 runner/mountain and owner G7a scoped thumbnails | Marker/preview helper tests | Real shared/held layer reads; selected board/current frame thumbnails; passive restores page; no per-pen readback |
| Input cancellation and stale scene | Native gap/overlap/CANCEL/host replacement/slide-away/export-ID tests | Stable Host per scene; reject removed/reordered IDs; pause and graphics loss cancel all transient gestures |
| Performance | Frame coalescing/equality tests; bounded 4 MiB shadow cache; no full-window software board layer | Profile real Note 9 input latency/frame pacing/memory with several boards and long held strips; painting samples never coalesced |
| Dependability across the complete workflow | Separate Lead-owned foundation | Paint across board edges → undo/redo → save/reopen → preview → export must agree pixel-for-pixel before release |

## Interrupted fade integration

Use `fade.apply(input, monotonicNowMs)` before `BoardChromeView.show`. Each board has its own ephemeral `BoardChromePenFade`; reset on scene disposal/pause. It carries actual opacity into the next transition: short pen-up and a new stroke during the return cannot jump to a different opacity. Durations/endpoints remain the locked 120/300 ms and 12%/100%. Without this helper the existing input supports a short-stroke lift by retaining the last pen-down duration, but repeated interrupted strokes need the explicit start alpha.

## Review discipline

Do not call the whole board experience complete from isolated unit tests. Confirm every gate above in the integrated app, and keep failed/untested gates visible. Do not redesign measurements, colours or gestures to chase a polish adjective. Follow-up fixes remain scoped to reproducible defects; phone verification and installation remain the owner's responsibility.
