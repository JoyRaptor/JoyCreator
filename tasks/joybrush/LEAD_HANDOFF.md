# Joy Brush — Lead handoff (cloud Lead → local Lead, 2026-09-29)

You are taking over as **T1 Lead / design lead of Joy Brush**, the painting + frame-by-frame
animation wing of Joy Creator (repo `JoyRaptor/JoyCreator`, branch `joy-creator`). Until now the Lead
ran in a cloud session; you run on the owner's PC. Read this whole file first, then the files in §3.

## 1. The owner and how to work with him
- JoyRaptor ("scott"). An **artist, not a programmer**: he cannot code and does not use git. Never
  give him a git, gradle or file chore. His jobs: rulings, pasting reports between agents, and phone
  checks with a Note 9 plugged in. Talk to him in plain language, short, and hand him ready-to-paste
  blocks for the orchestrator.
- Money is tight: free models do most building (see §2). The Lead checks their work, rules on
  questions, writes specs when asked, and builds the hard T1 parts itself.
- House rules (START_HERE.md, OWNER_CONSTRAINTS.md): never put his real name, personal email or device
  serials in git; never `adb uninstall` (deletes projects); never install on his Note 20 unless
  `PHONE=` is set; **never run gradle for the app** on his PC (a watcher, `watch-build.ps1`, builds
  and writes `build.log`) — the ONLY gradle allowed is the standalone Joy Brush build:
  `./gradlew -p joybrush :core:jvmTest` and `./gradlew -p joybrush :androidkit:compileKotlin :androidkit:test`.
  Agents never resolve merge conflicts (the Lead does). `git add` explicit paths only. Push every session.
  "Preview and export must agree."
- Performance floor: Note 9 (Android 10, has tilt). Tablet check later (Tab S8). Screen-size agnostic
  UI. Keep the iOS door open (core is pure Kotlin common).

## 2. The team (opencode harness on the owner's laptop)
- **Orchestrator** ("space bunny alpha", an opencode free model): the ONLY agent that edits the board
  (`ROADMAP.md`) and commits for builders; dispatches builders (up to 5 in parallel), triages
  reviews, writes `ORCHESTRATOR_LOG.md`. It adds "provisional rulings" to specs and marks questions
  🔴 "For the Lead". It was just restarted with a fresh context (its handover is in ORCHESTRATOR_LOG.md).
- **Builders**: subagents; claim a row, build to the spec, stop at the owner area.
- **Adversarial reviewers** (e.g. muse-spark, mimo — different model families): read-only; write
  `tasks/joybrush/reviews/<task>__<model>.md` with BLOCKER / MAJOR / MINOR / NIT (ROADMAP §5b).
- Tiers: T1 = Lead (you), T2 = code model, T2-V = vision/design model, T3 = owner on the phone.
- Status ladder: ⚪ Outline → 📝 Draft → 🟦 Ready → 🟨 Claimed → ⛔ Blocked → 🟧 Built → 🟩 Reviewed
  (xr) → 📱 On phone → ✅ Signed off (owner only).
- The Lead does not edit ROADMAP.md while the orchestrator has it open (conflicts). Instead: rulings
  go in `LEAD_RULINGS.md` (numbered R1…R24 so far, newest at the bottom), including any board rows
  for the orchestrator to add. Specs the Lead writes go straight to 🟦 Ready.
- If the board diverges: orchestrator pushes to a NEW branch (`bunny/orchestrator-sync`), the Lead
  merges into `joy-creator` taking the orchestrator's statuses, orchestrator then `git pull --ff-only` (R8).

## 3. Read these (in this order)
1. `tasks/joybrush/ROADMAP.md` — roles, claim loop, §4 spec writer, §5 cross-review, §5b adversarial
   reviewers, the board (phases 0–8).
2. `tasks/joybrush/LEAD_RULINGS.md` — every Lead decision, R1–R24. They override specs.
3. `tasks/joybrush/JOYBRUSH_BLUEPRINT.md` (v1.1) — the vision, owner rulings (§3, §3.5), phases (§7),
   patent design-around rules (§5).
4. `tasks/joybrush/OWNER_CONSTRAINTS.md`, `tasks/joybrush/specs/README.md` + `SPEC_TEMPLATE.md`,
   `tasks/joybrush/design/JOYBRUSH_VISUAL_LANGUAGE.md` (placeholder colour indigo→blue
   `#5C43FD→#4397FD`, owner undecided), `research/R1–R8` (R8 = patent claims).
5. `tasks/joybrush/ORCHESTRATOR_LOG.md` — the latest handover (what just landed and what is held).
6. `tasks/joybrush/reviews/STUDIO_TRANSFORM_AUDIT__lead.md` — the Lead's audit of the Studio tool.
7. `tasks/lessons.md` — incl. "the owner does not code: never hand him a git chore".

### 3a. The research library (the subagent reports the whole design rests on)
The eight research reports from the start of the project are saved IN FULL (~5,300 lines) in
`tasks/joybrush/research/`. The blueprint summarises them; go to the report when a spec or ruling
touches its subject. Written 2026-09-28 (early drafts say "Joy Paint" — the old working name).
| Report | What it covers — read it before working on… |
|---|---|
| [R1_rebelle.md](research/R1_rebelle.md) | Rebelle's wet/dry paint simulation, its academic lineage, how to get the look on Android → Phase 6 (wet engine, watercolour) |
| [R2_expresii.md](research/R2_expresii.md) | Expresii's brush and ink model (stroke-direction rotation, distance damping, "one great brush") → brush dynamics, JB-6.03 |
| [R3_krita_mypaint.md](research/R3_krita_mypaint.md) | Krita's brush engines and libmypaint (inputs → curves, smudge, what to port) → brush format, dynamics, JB-8.03/8.04 imports |
| [R4_photoshop_and_formats.md](research/R4_photoshop_and_formats.md) | Photoshop's brush engine, `.abr`/`.brushset`/`.kpp`/`.myb` formats, the owner's tip model (superellipse, taper, aspect) → tips, JB-8.x imports, PSD export |
| [R5_concepts_infinite_painter.md](research/R5_concepts_infinite_painter.md) | Concepts and Infinite Painter: brushes, UI, gestures, selection, vector re-brushing → UI specs, ink layers, tool finger, swatch drags |
| [R6_android_apps_ui.md](research/R6_android_apps_ui.md) | Ranking of Android drawing/animation apps, UI anatomy, reviews (34k-review complaint analysis: crashes/lost work #1), minimal-UI proposal → JB-2.01 chrome, autosave |
| [R7_stylus_and_engine.md](research/R7_stylus_and_engine.md) | Stylus input (S Pen tilt/azimuth, palm rejection), low-latency inking (front buffer, prediction), tile/GL engine architecture → input, JB-0.12, engine |
| [R8_patent_claims.md](research/R8_patent_claims.md) | What the risky patents actually claim and the design-arounds (the rules in §8 below) → ANY smudge, wet, pigment-mixing or brush-loading work |

Also: [design/JOYBRUSH_VISUAL_LANGUAGE.md](design/JOYBRUSH_VISUAL_LANGUAGE.md) (tokens, components,
the look — for every UI spec) and [reviews/](reviews/) (every adversarial review file, per task and
reviewer model — read a task's reviews before ruling on it).

## 4. Architecture (what exists)
- `joybrush/` = standalone Gradle build (Kotlin 2.2, group `cc.joycreator.joybrush`):
  - `:core` — Kotlin common, JVM target only, NO Android. Packages: `input` (PenSample with NaN for
    missing channels, StrokeSmoother one-slider screen-px smoothing, DirectionTracker, Angles,
    Tool with frozen order), `dynamics/Curve`, `stroke` (StrokeRecord = the stroke as a recording:
    raw samples + brushId + seed + smoothing + zoom; StrokeCodec binary JBS1), `paint` (Dab, TipMath =
    CPU twin of the shader, DabPlacer, Tiles 256², UndoLog, RefCanvas golden CPU reference), `doc`
    (JbDocument: infinite canvas + Boards CANVAS/ANIMATION/SPRITE/PUPPET/CHARACTER, PAINT/INK layers,
    cels + frameCel; DocJson; AnimOps), `brush` (BrushPreset = MyPaint-style Param base + input→curve;
    BrushJson, BrushValidate, BrushDabber, Scatter, FillPen), `fill` (FloodFill, MaskPaint),
    `select` (SelectionMask, Homography, Resample), `shape` (hold-to-shape recogniser/perfecter,
    built by the Lead), `guide`, `tool` (SizeOpacityDrag, Nudge, ThreeFingerSwipe), `anim`
    (PlaybackClock), `sprite` (SpriteGridMath), `render` (Blend, RegionRenderer — CPU export
    composite), `export` (GifEncoder, SpritePacker → `.sprite.json` for SpriteLab), `vector`
    (StrokePicker, VectorEraser), `view/ViewTransform`, `grain/CloudNoise`, `io`.
  - `:androidkit` — Kotlin/JVM compiled against android.jar (`compileOnly`): `gl/GlPaintEngine`
    (sparse RGBA8 premultiplied tiles, R16F stroke buffer, copy-on-write commit = undo snapshot,
    `replaceTiles` for brush-less edits, `strokeInProgress`), `JbCanvasView` (GLSurfaceView; fingers
    draw until a pen is seen, then navigate; per-frame view snapshot for the GL thread),
    `CanvasGestures` (pinch/pan/rotate/taps; `handOver()`), `io/` (JbArchive `.joybrush` save with
    tmp+sync+rename+.bak, PngWriter, OraExport), `BrushLibrary`, `diag/PenDiagnosticsView`.
  - Shaders (GLSL ES 3.00, shared with the PC Brush Lab `tools/brushlab/BrushLab.html`) in
    `joybrush/shaders/`. Brushes in `joybrush/brushes/*/brush.json` + `index.txt`.
- `joybrush-android/` — Android library in the app build: `JoyBrushActivity` (temporary "pill" UI:
  brush, eraser, undo/redo, save a copy, open, smoothing), `JbColors` (tokens, D.01).
- The Studio (the app's video editor, `app/src/main/java/com/fadcam/ui/faditor/`) has reusable parts
  Joy Brush will SHARE via a new `:studiokit` module (R16–R23): colour picker, transform surface
  (`transform/`), mesh warp + full puppet engine (`transform/mesh/`), 26 blend modes
  (`model/BlendModes`), 17-effect FX stack + compiler, gradient ramp/curve + app-wide gradient editor,
  masks (`MaskSdf`), keyframes. Rule R23: move into the kit once (same package names), never copy;
  where pure-Kotlin core needs the same maths, prove it equal with a generated golden table.
- `tools/jvm-harness/` — plain-Java test harness for Studio code (`run-*.sh`; Windows `;` classpath).

## 5. How the Lead checks work (do this for every report the owner pastes)
1. `git pull --ff-only origin joy-creator`; run both Joy Brush gradle commands; COUNT results from
   `joybrush/core/build/test-results/jvmTest/*.xml` and `joybrush/androidkit/build/test-results/test/*.xml`
   (don't trust claims). App-side: read the watcher's `build.log`.
2. Read the diff of anything touching the engine, contracts (`StrokeRecord`, `DocModel`, brush
   format), or shared Studio code. Check code against its spec's Contract and Decisions.
3. Any changed EXPECTED value in a test must carry its derivation in the test (R9). Suspicious green
   tests: break the code on purpose and confirm the test goes red (mutation check).
4. Answer every 🔴 "For the Lead" question in specs; confirm or overturn orchestrator provisional
   rulings; write it as the next R-number in LEAD_RULINGS.md; push.
5. Adversarial findings: BLOCKER/MAJOR are real until disproved; verify each against code.
6. Give the owner a short plain-language summary + a paste block for the orchestrator.
Spec quality bar (for reviewing Drafts): owner area of exact files and no overlap with running
work; a verbatim Contract; numbered Decisions with numbers (not "reasonable"); tests with concrete
expected values that were actually derived; correct references to EXISTING APIs (open the files);
respects owner rulings, R-rulings and patent rules; says what it must NOT touch; right tier.

## 6. State at handoff (commit 652114e) — UNVERIFIED by the Lead
- Orchestrator claims: `:core:jvmTest` 665 tests / 1 failure; `:androidkit:test` 90 / 0. Board: 22
  Reviewed, 26 Built, 38 Draft, 11 Ready. **First action: verify these numbers yourself.**
- Specs went 48 → 88. **The 38 new Draft specs were written by the orchestrator's spec writers and
  have NOT been reviewed by the Lead** (the owner asked whether they are as good as the Lead's). Review
  them against the §5 quality bar before they become Ready; start with anything T1-adjacent, anything
  touching contracts or app files, and anything in phases 2–3 (next to be built).
- Recently landed by the bunnies: JB-2.05a, 2.12a, 2.16a, 3.05a, 4.01a, 1.08a, 2.20a, 3.08a (WIP),
  plus BLOCKER fixes (Resample.over, SelectionMask.intersect across tiles, GL context loss rebinding
  dead textures). Worth a Lead look: GL context-loss fix in `GlPaintEngine` (Lead's file).
- The orchestrator reported four false claims in its own KDocs, the worst: JB-2.20a's "independent
  GLSL cross-check" (`glsl_model.py`) never reads `BlendModes.java`. Check what JB-2.20a actually
  built against its spec (R23: the golden table must be GENERATED from `BlendModes.blend` in Java,
  with a `--check` drift mode).

## 7. Open decisions waiting for the Lead
1. **JB-3.08a red test** (the 1 failure): the override-badge rule. Spec Decision 2 + test 2 say an
   override is remembered with its board and FORGOTTEN when another board becomes active; a KDoc/test
   also expects two boards each keeping their own. Cloud Lead's recommendation: make it **per-board
   memory** (a `MutableMap<boardId, SwipeMode>`; returning to a board restores your choice — the owner
   keyed the mode to the active board), update spec Decision 2 and test 2 to match, and say so in a ruling.
2. **JB-0.08b — three MAJORs** from review (work-loss risks) held by the orchestrator: read
   `reviews/JB-0.08b__*.md`, verify, fix (JbCanvasView / JoyBrushActivity / JbArchive).
3. **JB-2.02 — a MAJOR needing new API** (see its review file).
4. **JB-5.10 timing test** red cause = no spatial index in the vector eraser. Recommendation: add a
   uniform grid index (keep Decision 5's promise) rather than weakening the bound.
5. **R14 leftover app files** (`ProjectStorage.java`, `INBOX.md`, `LEDGER.md`,
   `FaditorEditorActivity.java`, `LobbyFragment.java`, `strings_studio_polish.xml`) — left by earlier
   agent sessions, NOT the owner. Check whether the orchestrator parked them on
   `bunny/leftover-app-edits`; review and merge or drop. JB-0.09 (lobby entry) is blocked on this.
6. App-file order: **D.02a** (Studio transform gesture fixes from the audit) → **D.02** (`:studiokit`
   pure move + harness sourcepaths) → D.02c (shared colour recents + bar) / D.05 (fx, gradients,
   blends, masks, keyframes). One at a time; never beside JB-0.09.

## 8. The Lead's own T1 backlog (build these; bunnies struggle with them)
- JB-2.05 selection + transform (Studio `TransformOverlayView` via a Joy Brush Host; core
  SelectionMask/Homography/Resample exist; tap outside commits; survives ops).
- JB-5.01 ink layers: render strokes from recordings by their CURRENT brush (stamp and `fill`
  engines; R20: every vector brush swappable after drawing, multi-select, one undo).
- JB-2.20b GL layer compositing with the Studio's `BlendModes.GLSL_BLEND_FN` (preview = export).
- JB-1.05c grain in the dab shader; JB-1.06 smudge/nudge (ONE carried colour — patent rule);
  JB-1.07 default presets; JB-0.12 front-buffer low latency + motion prediction
  (androidx.graphics GLFrontBufferedRenderer, androidx.input motionprediction), with an off switch.
- JB-3.04 onion skin as one shared component (from SpriteLab); JB-3.07 send to Studio.
- JB-2.21 filter layers (Studio FxStack), JB-2.23 masks/clipping, JB-2.05c warp (Studio lattice),
  Phase 6 wet (level 0 look on commit, level 1 wet-lite), Phase 7 puppet/character (Studio mesh +
  puppet engine, Avatar Studio's solver, `.avatar` export).
- **Patent design-around rules** (blueprint §5, R8): smudge carries ONE colour state (no separate
  reservoir + pickup buffers); no brush loading by sweeping over the canvas; 2D tip only; no
  lattice-Boltzmann; no polygon watercolour; edge darkening computed on the raster wet mask. Never
  ship Mixbox; never use MoXi code.

## 9. Owner rulings to keep in mind (full list in blueprint + rulings)
Joy Brush is the name · fingers draw until a pen is seen, then navigate / become a tool finger
(select · lasso · colour pick) · tap outside commits a transform · context-aware 3-finger swipe
(frames on an animation board with ≥2 frames, else size/opacity; corner badge; tap overrides; ends
STOP with push-through wrap; Play at the end restarts) · paper is a setting, export has "include
paper" · export screen / selection / board · hold-to-shape keeps pressure and tilt · one smoothing
slider · context-aware eraser (partial / whole / to-intersection) · nudge scales with zoom · boards
wear their sections' colours · sprite export: "Export" and "Export and open in SpriteLab" · fill pen
is a BRUSH (Concepts-style: any vector stroke can be re-brushed after drawing) · eyedropper by
drag-off-swatch (drag back to cancel) and long-press (visible cancel, can be turned off) · recent
colours bar is a must-have · share everything with the Studio, modular, improve once.

## 10. Phone checks owed (owner, Note 9)
JB-0.05 first screen · JB-0.08b save/reopen/autosave · JB-2.02 zoom/pan/rotate + "draw with one
finger, add a second, pinch → zooms without lifting" (R13) · JB-1.05b brush pill · JB-1.20/1.21 brush
labs · the checks listed in D.02a / D.02 / JB-2.06b / JB-2.03a / JB-2.13b as they land.

## 11. Useful facts
- Cloud needed `-Pjoybrush.androidJar=<path>`; locally the Android SDK is found from ANDROID_HOME or
  `local.properties` — plain commands work.
- `tools/jvm-harness` transform/mesh/puppet suites were green at the audit except the stale
  SpecHBendMirrorTest (fixed by D.02a step 5).
- Tests the Lead added are good references for style: `shape/ShapeRecognizerTest` (10 seeds,
  synthetic hand-drawn strokes via `StrokeMaker`).
