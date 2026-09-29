# The 38 new Draft specs — Lead review (2026-09-29)

Reviewer: the local Lead (Claude). Method: every spec read in full (Phase 7 by its Questions, since it is
honestly blocked); every existing API, path and constant it leans on looked up in the repo; every number in
a test table re-derived where I could. Rulings that answer the specs' Questions are R25–R40 in
`LEAD_RULINGS.md` and **override the specs**.

## Headline

**They are good — close to the Lead's standard, not equal to it.** The structure is right almost everywhere:
one owner area, verbatim contract, numbered decisions with reasons, a "Do not" list, honest Questions, and the
Questions are mostly the RIGHT questions (several found real gaps in built work). Existing APIs check out: of
~150 named classes/constants, the ones that did not exist were either created by another spec or external file
formats. Patent and licence rules (R8, `license = "unknown"`, no Mixbox) are respected throughout.

What keeps them from being Ready as-is is a small set of **repeated mechanical slips**, all cheap to fix:

| # | Slip | Where | Fix |
|---|---|---|---|
| 1 | **Wrong expected numbers in tests** (the quality bar says values must be derived) | JB-3.02 (tests 1, 4, 5, 11), JB-3.03 (test 3), JB-5.03 (test 8), JB-3.05 (test 3), JB-4.01 (test 5) | corrected values below |
| 2 | **Unit mix-up: "44 dp" written as 88** and a `const` declared "already × density" (impossible) | JB-3.02, JB-3.03 | R32: constants are dp × density at the use site |
| 3 | **Source-level tests put in `commonTest`**, which cannot open files (JB-1.07 Decision 8 says so itself) | JB-2.04, 2.12, 2.16, 2.17, 2.23, 3.04 | move to `jvmTest` |
| 4 | **A model that does not match the real class** | JB-2.22 / 2.22b (`Ramp` vs the Studio's `GradientRamp`) | rewrite from the real class |
| 5 | **Two false premises** | JB-2.14c Q1 (PSD Subtract/Divide), JB-3.04 (Python "transcription" parity) | R38 / R34 |
| 6 | Contract typos that will not compile or contradict themselves | JB-0.12 (`object` with a constructor), JB-5.03 (`dx: Double, dy: Float`), JB-2.14c (missing imports/types), JB-2.15 (Android type in core, `SaveTarget2`), JB-2.17 (`TWO_FINGER_DRAG` vs `PINCH`), JB-5.01 (`RegionRenderer.MAX_REGION_PX` is a top-level `render.MAX_REGION_PX`) | listed per spec |
| 7 | Version numbers claimed by several specs at once (`DOC_VERSION` 3 in 2.21 and 7.01; 4 in 2.23; `BRUSH_VERSION` 3 in 1.06 and 2.22b) | 2.21, 2.23, 7.01, 1.06, 2.22b | R30: assigned at landing |
| 8 | A spec header says "Ready" while the board says Draft | JB-4.01, 4.02 | the board is right |

**One thing the specs got RIGHT that the orchestrator got WRONG:** JB-4.02's Q2 (held frames export at the
wrong speed — `SpritePacker` never writes `weights`). The orchestrator's handoff log calls this "false because
there is no SpriteSheet.kt in this repo". The app is Java: `SpriteSheet.java` exists and writes `weights`. The
spec writer was right; see R36. That handoff lesson must be struck from `ORCHESTRATOR_LOG.md`.

## Verdicts

Legend: 🟦 = Ready once the listed fixes are applied (a spec writer can do them; no design work) ·
🔒 = the Lead's own T1 row, not for builders · ⛔ = quality fine but a "Needs" row is not built (chrome chain
D.02a → D.02 → 2.01) · 🔧 = a part must be rewritten first · 🕒 = off the runway.

| Spec | Tier | Verdict | What must change before it moves |
|---|---|---|---|
| JB-0.10 CPU bench harness | T2 | 🟦 | R39: entry point in the diagnostics panel (later); public deflate constant; add `RegionRenderer.render` + `JbArchive.read` cases |
| JB-0.12 front buffer + prediction | T1 | 🔒 | R38: AndroidX libraries; Half A shrinks to settings + policy + pen state; fix `object`/constructor typo |
| JB-1.05c grain in the dab shader | T1 | 🔒 | good spec; R38 answers Q1–Q4 |
| JB-1.06 smudge and nudge | T1 | 🔒 | good spec; R38 answers Q1–Q3; patent rules verified respected |
| JB-1.07 default presets | T1+T3 | 🟦 (5 of 7) | Marker, Soft air, Eraser now; Smudge/Nudge after 1.06 (R39) |
| JB-2.01 screen chrome | T2-V | ⛔ | needs JB-0.09 + D.02; owner approves a mockup first (R39) |
| JB-2.02b tool finger + gestures | T2 | ⛔ | needs 2.05; drop `ZOOM_FIT`; move source-level test to `jvmTest` |
| JB-2.04 layers panel | T2-V | ⛔ | needs 2.01; HIDE the blend chip until 2.20b (was "greyed"); tests 9–10 to `jvmTest` |
| JB-2.05 selection + transform | T1 | 🔒 | R38: same layer, not a new one; S Pen button conflict resolved |
| JB-2.11 hold to shape | T2 | 🟦 (core half) | split: `HoldToShape` core now; the `JbCanvasView` wiring is the Lead's; fix the "withdrawn vs still visible" contradiction (R39) |
| JB-2.12 helpers | T2-V | ⛔ | needs 2.01; test 7 to `jvmTest` |
| JB-2.14c export PSD | T2 | 🟦 | fix contract imports/types; delete the stream-of-consciousness in Decision 6; R38 answers Q1–Q3 (INK layers omitted with a warning) |
| JB-2.15 autosave + crash safety | T2 | ✅ superseded | R26 — `SaveQueue` built and tested; remaining items → JB-0.08c |
| JB-2.16 brush swatch drag | T2 | ⛔ | needs 2.01; tests to `jvmTest` |
| JB-2.17 cheat sheet + hints | T2-V | ⛔ | lands last; Needs gain 2.11, 2.16, 2.02b; four hints; constants to core |
| JB-2.20b GL layer compositing | T1 | 🔒 | strong spec; R38 confirms option (a) |
| JB-2.21 filter layers | T1 | 🔒 | rewritten per R38: GPU export through the same GLSL, no per-effect CPU twins |
| JB-2.22 gradient tool | T2 | 🔧 | `Ramp` does not match the Studio's `GradientRamp`; golden CAN be generated from the real Java |
| JB-2.22b gradient fill pen | T2 | 🔧 | the owner's answer about "set a shape as this"; same `Ramp` fix |
| JB-2.23 layer masks + clipping | T1 | 🔒 | good; R38 confirms; test 10 to `jvmTest` |
| JB-3.02 animation paper | T2-V | 🔧→🟦 (core half) | corrected numbers below; R32 units; R33 peg bar owns PLAY+MODE |
| JB-3.03 film strip | T2-V | 🔧→🟦 (core half) | corrected numbers below; R32 units; R33 tap = duplicate |
| JB-3.04 onion skin | T1 | 🔧 | R34: split, and the golden must come from the real Java after D.02 |
| JB-3.05 playback + audio | T2 | 🟦 (FrameStepper) | fix test 3; drop Decisions 13–14 pill; audio → JB-0.02c |
| JB-3.06b animation export | T2 | 🟦 (plan + GIF + PNG seq + sheet) | R35: MP4 via Send to Studio, WebP dropped; fix `NNNN.txt` typo |
| JB-4.01 sprite board | T2-V | ⛔ (core half 🟦) | fix test 5 (an unfitted board only); needs 2.01 for the view |
| JB-4.02 cell order + play | T2-V | 🟦 (core half) | roll maths independent of the packer; export of holds waits for 4.03c |
| JB-4.03b sprite export buttons | T2 | ⛔ | needs 4.01 + **4.03c** (weights) + **4.03d** (open in SpriteLab) |
| JB-5.01 ink replay + raster | T1/T2 | 🟦 (T2 half) | split off 5.01b (capture, Lead); `render.MAX_REGION_PX` reference; one `JbCanvasView` constant edit after the save wiring |
| JB-5.03 reshape/re-weight/re-brush | T2 | 🟦 | needs 5.03a + 5.01 built; fix `dy` type; corrected test 8 below |
| JB-5.11 context-aware eraser | T2 | 🟦 | needs 5.01; rewrite Decision 5/test 6 (erase the rest, report the one); speck rule per R28 |
| JB-7.01–7.04 puppet / character | mixed | 🕒 | honestly blocked; no more Phase 7 specs until Avatar Studio's entry points are documented |
| JB-8.01 `.abr` import | T2 | 🟦 | R40; safe for a free model now — no chrome dependency |
| JB-8.02 Procreate import | T2 | 🟦 | R40: delete `Inflate.kt`, use `expect/actual` |
| JB-8.04 Krita import | T2 | 🟦 | R40 |

**Tally:** 16 rows can be built by the free models right after their small fixes (a real runway); 8 are the
Lead's; 9 wait on the chrome chain; 3 need a rewrite; 4 are parked. **The bottleneck to anything visible on the
phone is the chrome chain (JB-0.09 → D.02a → D.02 → 2.01), not the runway.**

## Corrected numbers (derived — the derivations are the point, R9)

**JB-3.02 (dp; density 1 unless stated)**
- Test 1 — 5 pegs, 600 px bar, pitch 44: centres `212, 256, 300, 344, 388` (symmetric about 300). The spec listed
  `256, 300, 344, 388, 432` (not centred). Even with the old 88 pitch it would be `124, 212, 300, 388, 476`.
- Test 2 — 200 px bar, 5 pegs: pitch 40, first 20, last 180 — and 40 ≥ the floor `2 × 14 = 28`, so consistent.
- Test 3 — `pegAt(322)` = 2 (22 from peg 2 and 22 from peg 3: tie goes to the lower index); `pegAt(323)` = 3.
- Test 4 — density 3 (floor 144 px), zoom 1: the step is **200** (100 × 1 = 100 < 144). The spec's headline said 100.
- Test 5 — zoom 0.05, density 1: the step is 1000, but a 600 px view spans 12 000 doc px = **12 ticks**, not "≤ 2".
- Test 11 — zoom 1 and 0.1 give steps **50 and 500** at density 1 (100 and 1000 only at density 2).

**JB-3.03 (tick = 44 dp)** — holds `[1,2,1,3]`: cell widths `44, 88, 44, 132`, `cellLeft` `0, 44, 132, 176`, strip
width **308**; density 3 → tick 132. A 999-tick cell is `999 × 44 = 43 956` (Decision 4 said this; test 3 wrote
88 912 — a contradiction). Boundaries: `frameAt(44)=1`, `frameAt(43.999)=0`, `frameAt(131.999)=1`,
`frameAt(132)=2`. Edge grab 24 dp one-sided: `edgeAt(44)=0`, `edgeAt(20)=0`, `edgeAt(44.001)=-1`, `edgeAt(0)=-1`.
The half-tick drag is 22 px.

**JB-3.05 test 3** — a SIX-frame ping-pong walks `0 1 2 3 4 5 4 3 2 1 0 …` and emits `ShowFrame` at most 10 times a
cycle (2n − 2). The spec listed a five-frame sequence.

**JB-5.03 test 8** — the real falloff (`StrokeEdit.reshape`, verified) is `(1 − u²)²` over ARC LENGTH, u = arc / radius.
At radius 24: a sample 20 away moves **9.3 %** of the offset, not "under 1 %". At zoom 8 the radius is 3: a sample
2 away moves **30.9 %**, not "nearly full"; 0.5 away moves 94.5 %.

**JB-4.01 test 5** — after `fitted(...)` (Decision 2) there is NO spare strip; the case only exists on a board that
was not fitted (a stale one). Build the test from an unfitted 500×300 board with `byCount(3,2)` (498 wide, a 2 px
strip).

## Verified OK (spot-checks that held)

`glslBlendFnWithModeParam` / `GLSL_BLEND_FN` exist · `SizeOpacityDrag.SIZE_PER_DOUBLING_DP = 160`,
`LOCK_TRAVEL_DP = 12`, `TAP_MS`, `TAP_SLOP_PX`, `MIN_SPREAD_PX` exist · `PenSample.predicted` / `isPlaceable` ·
`Resample.lift/warp`, `Homography.fromQuads`, `MAX_SELECT_SPAN`, `TipMath.coverage`, `FillPen.outline` ·
`GradientRamp.sampleColor/sampleAlpha` (Android-free) · `ShippedBrushFilesTest` in `core/src/jvmTest` · the
research numbers quoted in JB-3.04 (`alphaFor` 102 / 63 / 39) re-derive · JB-1.06's patent design (ONE carried
colour, convergence properties as tests) matches R8 · JB-8.x set `license = "unknown"`, keep notices.
