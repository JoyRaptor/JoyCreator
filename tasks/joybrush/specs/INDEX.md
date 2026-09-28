# Joy Brush — spec index

Status words: **Ready** (anyone of the right tier may claim it) · **Claimed** · **Built — awaiting T1
review** · **Reviewed** · **Proven on device** · **Outline** (not yet executable; Claude writes it
before it is needed — the runway rule, `README.md` §2).

Tiers: T1 Claude · T2 code model · T2-V vision/design model · T3 owner on the phone.

## Ready now (can run in parallel — no shared files)

| Spec | Tier | What | Status |
|---|---|---|---|
| [JB-0.04](JB-0.04_stroke_codec.md) | T2 | Save/load stroke recordings (binary codec) | Ready |
| [JB-2.10](JB-2.10_shape_recognizer.md) | T2 | Hold-to-shape: recognise and perfect shapes, keeping pressure/tilt | Ready |
| [JB-5.10](JB-5.10_vector_eraser_geometry.md) | T2 | Vector eraser: partial, whole stroke, erase-to-intersection | Ready |

## Phase 0 — Foundation

| Spec | Tier | What | Status |
|---|---|---|---|
| JB-0.01 | T1 | Pen-sample and stroke-record contracts; context-aware smoothing; tip direction; response curves | **Built** (20 tests pass) — awaiting owner feel-check in Phase 0's device build |
| JB-0.02 | T1 | Document model contract: document, boards, layers (paint / ink), tiles, ids; native file layout | Outline |
| JB-0.03 | T1 → T2 | Brush preset schema (T1 writes the contract) → JSON parser + validation (T2) | Outline |
| JB-0.04 | T2 | Stroke codec | Ready |
| JB-0.05 | T1 | Android shell: module, screen, low-latency surface, MotionEvent → PenSample adapter (historical samples, tilt/lean mapping), pen-vs-finger policy | Outline |
| JB-0.06 | T2 | Hidden pen-diagnostics overlay (raw values, sample-rate histogram) — the "probe", built in | Outline |
| JB-0.07 | T1 | GPU tile engine, round dab shader, stroke buffer (flow/opacity), tile undo | Outline |
| JB-0.08 | T2 | Save / open native file; PNG export with "include paper" | Outline |
| JB-0.09 | T2-V | Lobby entry and the first Joy Brush screen chrome, in Joy Creator's visual language | Outline |
| JB-0.10 | T2 | CPU benchmark harness for the Note 9 (flood fill, tile compression, PSD write) | Outline |
| JB-0.11 | T1 | Join the standalone build into the app build (includeBuild), coordinated with the watcher | Outline |
| — | T3 | **Owner check:** draw, save, reopen on the Note 9; lag feels like Infinite Painter's or better | — |

## Phase 1 — The brush engine (outlines)
Parametric tip (superellipse, taper, aspect, rotation) · tip texture + paper grain with the height
threshold and tilt gradient · dynamics evaluator · scatter/count/jitter/colour · presets Ink, Pencil,
Marker, Soft air, Smudge, Nudge, Eraser · PC Brush Lab · phone hot-reload lab. Mostly T1 (engine
stages) with T2 pieces (lab UI, preset files) and T2-V (brush panel).

## Later phases
See `JOYBRUSH_BLUEPRINT.md` §4. Early, independent specs are pulled forward into "Ready now" when
they share no files with active work (as JB-2.10 and JB-5.10 are).
