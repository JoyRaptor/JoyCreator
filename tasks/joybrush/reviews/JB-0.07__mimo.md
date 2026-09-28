# Adversarial review — JB-0.07 GPU tile engine, dab/commit/display shaders, pen input, drawing view

- Reviewer: mimo (second adversarial pass; muse-spark filed `JB-0.07__muse-spark.md`).
- Task status: 🟧 Built. Commits reviewed: `41102518` (core paint) + `f0e84040` (GL engine/view), tree at `b74aaf0e` — note HEAD also carries the R1/R2 hardening (`061fd2b5` and the JB-0.03b-era placer rewrite), which **fixes two of muse's five findings and supersedes a third**.
- Spec: none (T1 pre-spec; contract = board row + `tools/shader_check.js` + `RefCanvas` golden reference).
- Suite: `./gradlew -p joybrush :core:jvmTest --rerun-tasks` at `b74aaf0e` → BUILD SUCCESSFUL, 280 tests / 0 failures (PaintTest 9/9 incl. `theBrushIsAskedExactlyOncePerDabInOrder`, `leanDirectionTakesTheShortWayRoundBetweenSamples`, `anInfiniteBrushSizeCannotFreezeTheStroke`). GL paths reviewed statically as muse did (no phone/emulator in session).
- File-location note vs muse's citations: `GlPaintEngine.kt` now lives at `androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt` (owner area `androidkit/*` per board); line numbers below are of the current file.
- Severity: BLOCKER / MAJOR / MINOR per ROADMAP §5b.

**Verdict: one open MAJOR (F1, context-loss state), two findings FIXED since muse (F2, F3), one
SUPERSEDED by ruling R1 (F4 — with a residual latent MINOR I own), F5 confirmed as an accepted
owner ruling. My addition: a beginStroke lifecycle divergence between the CPU reference and the GPU
engine (MINOR, needs a ruling before JB-1.05 wires a second caller).**

## Status of muse-spark's findings at this commit

### F1 (muse Medium → I confirm **MAJOR**): `init()` still never clears layers/undo/pools — after context loss the engine presents dead texture names as the user's painting

- Re-verified: `init()` (`GlPaintEngine.kt:84-130`) recreates programs, VBOs, VAOs, FBO, `clearTex`
  and sets `ready = true` — it never touches `layers`, `undo`, `strokeTiles`, `freeLayerTex`,
  `freeStrokeTex`, or the active-stroke state. `release()` (`:133-147`) *does* tear all of that
  down — but nothing in the view calls `release()` on context loss: `onSurfaceCreated` calls
  `engine.init()` (+ idempotent `addLayer`) directly (`JbCanvasView.kt:79-82`).
- Consequences, re-traced at HEAD: the `Layer` maps still hold deleted texture names; on the new
  context those names are either fresh incomplete textures (black) or, worse, re-issued by the
  `glGenTextures` calls inside `init()`/`newTexture` (`:431-447`) to *other* engine objects, so a
  "layer tile" can alias the clear tex or a pool tex. `newLayerTile`/`newStrokeTile`
  (`:404-414`) then recycle pooled dead names **without re-specifying storage** (only
  `newTexture` allocates storage — the pool path skips it for layer tiles; stroke tiles at least
  `glClear` via `attach` + `glClearColor/clear` `:410-413`, but to a possibly-deleted FBO attachment).
  `undo` keeps swapping dead names as before/after (`:305-315`, `put` `:395-398`).
- Net: after a loss the user sees black blocks *presented as their painting* with a lying undo
  history — worse than the blank canvas the KDoc promises ("content is lost then", `:83`).
  `preserveEGLContextOnPause = true` (`JbCanvasView.kt:77`) narrows but does not close the window
  (activity recreation with a non-preserved context, driver eviction on the Note 9).
- **Fix (unchanged from muse):** at the top of `init()`, if `ready` or any map is non-empty,
  apply `resetDocument()`-equivalent semantics (clear layers/undo/stroke state/pools) — the KDoc
  already promises fresh-document. One short block; belongs on any phone-milestone checklist.

### F2 (muse Medium) → **FIXED at HEAD**: `radiusOf` is no longer evaluated twice per dab

- The placer now takes ONE `look: (sample, distance, index) -> DabLook` invoked exactly once per
  dab (`DabPlacer.kt:74-75`), with the KDoc contract at `:23-26` explicitly citing "the JB-0.03
  builder caught the earlier version asking for the radius twice". The convenience constructor
  (`:35-42`) builds `look` from the old lambda triple; `JbCanvasView` uses it (`:139-145`).
- Pinned by `PaintTest.theBrushIsAskedExactlyOncePerDabInOrder` (green in this session's run) —
  call-count asserted, exactly the test muse asked for. `angleOf` (the stateful
  `DirectionTracker.update`) is now behind the same single-call boundary. Closed.

### F3 (muse Medium) → **FIXED at HEAD**: azimuth (and barrel) are now interpolated wrap-aware

- `DabPlacer.lerp` uses `Angles.lerp` for **both** azimuth and barrel
  (`DabPlacer.kt:93-94`), with the review reference in the comment ("review JB-0.07 F3: these
  were copied from the later sample"). Pinned by
  `PaintTest.leanDirectionTakesTheShortWayRoundBetweenSamples` (green). Closed.

### F4 (muse Low) → **SUPERSEDED by ruling R1** for the pen path; residual latent gap = my M2 below

- At HEAD `DabPlacer.emit` clamps every dab at the boundary:
  `radius = if (l.radius.isFinite()) l.radius.coerceIn(0f, MAX_RADIUS_PX) else 0f`,
  `angle` finite else 0, `flow` finite coerced, `cap` finite coerced (`DabPlacer.kt:78-81`;
  `MAX_RADIUS_PX = 2048` `:100`; the comment `:76-77` cites JB-0.03 F1). So muse's "negative/NaN
  radius silently dropped" inputs **cannot arrive from the pen/brush path any more** — they become
  a 0-radius dab that renders the min-px dot (verified: `extent(0) = 2` `TipMath.kt:68`,
  `hx/hy` floored to `halfMin` `TipMath.kt:54-55`), and `anInfiniteBrushSizeCannotFreezeTheStroke`
  pins the freeze case.
- What I re-derived about the *underlying* surfaces (for the record, since both muse's text and an
  earlier note of mine were imprecise):
  - **negative radius** `< -1.414` → `extent ≤ 0` → `Tiles.touchedBy` empty range → dab dropped
    (muse's mechanism, correct for that input class — now unreachable);
  - **NaN radius** → `extent = NaN` → `floor(NaN).toInt() = 0` → exactly the `(0,0)` tile is
    bucketed, `stamp` probes only pixel (0,0), and for any dab not within ~a half-pixel of the
    document origin coverage is 0 → nothing written (but a phantom stroke-buffer tile exists for
    `(0,0)`, and `endStroke` will push a no-op undo entry for it on NORMAL blend —
    `RefCanvas.kt:86-106` has no "changed?" test). Neither "dropped" nor "NaN pixel" — quirk, not
    corruption. Unreachable via placer at HEAD anyway.
- **Residual (my M2):** `Tiles.touchedBy` itself is still unguarded (below).

### F5 (muse Low → **confirmed; accepted owner ruling, unchanged**): finger is dead after the first pen event

- Re-verified: `penSeen` set at `JbCanvasView.kt:101` (hover) and `:112` (touch); **no reset
  anywhere in the file** (grep: only those two assignments); the block is `:113`
  (`penSeen || penHovering || <PALM_GRACE_MS since penUpAt> → return true`), grace `PALM_GRACE_MS = 400`
  (`:199`). KDoc `:28-31` documents it as the owner's ruling, palm handling otherwise careful.
  Same strand-risk note as muse: a dead-pen-battery user cannot draw until Phase 2's tool finger
  exists — Lead-visible note, no code change (per owner ruling).

## My findings

### M1 (MINOR — needs a ruling before a second `beginStroke` caller exists): the CPU reference and the GPU engine disagree on what a double `beginStroke` means

- **Proof:** `RefCanvas.beginStroke` opens with `check(layerId == null) { "a stroke is already in
  progress" }` (`RefCanvas.kt:48`) → **IllegalStateException**, no state change. `GlPaintEngine.beginStroke`
  starts with `cancelStroke()` (`GlPaintEngine.kt:224`) → the in-progress stroke's buffer tiles are
  released (`cancelStroke` `:300-303` → `releaseStrokeTiles` `:416-420`) and the stroke is
  **silently discarded** — the paint the user just laid down this stroke vanishes, no error.
  Also, layer lookup diverges: GPU `error("no layer …")` (`:225`) vs CPU lazily creates the layer
  at commit (`RefCanvas.kt:83` `getOrPut`).
- **Reachability today:** nil — `JbCanvasView` sequences begin/add/end through one FIFO
  `queueEvent` (`:146-177`) and input transitions guard `drawing`, so no caller double-begins.
  But JB-1.05 (layers UI) and any second view/feature will call these APIs directly, and the
  *golden-reference* semantics (crash) differ from the *shipping* semantics (silent loss).
  Per §5b this is a contract divergence waiting for its consumer: pick one (recommend the
  reference's loud `check`, with the GL side cancelling only from an explicit `cancelStroke`),
  and pin it in a shared test before JB-1.05. Note this is the same class as JB-5.10's two
  findings: a behaviour pair to *rule on*, not a math bug.

### M2 (MINOR — latent defensive gap, made latent by R1): `Tiles.touchedBy` hangs or throws on a non-finite/absurd extent, because the clamp lives in `DabPlacer`, not in `Tiles`

- **Proof (code):** `Tiles.kt:18-27` — `e = TipMath.extent(d.radius)` (`:19`, = `r * 1.4143 + 2`,
  `TipMath.kt:68`). For `radius = +Inf`: `x0 = floor((x - Inf)/256).toInt() = Int.MIN_VALUE`,
  `x1 = Int.MAX_VALUE` (`:20-21`); the capacity expression `(x1 - x0 + 1) * (y1 - y0 + 1)` (`:24`)
  overflows Int (≈ 2³²·2³² wraps to 0) → `ArrayList(0)`, then the loop `for (ty in y0..y1) for (tx
  in x0..x1)` (`:25`) iterates 2³² × 2³² candidates and grows `out` → **hang then OOM, no
  exception**. For a large-but-finite radius (e.g. `1e9f`, `extent ≈ 1.4e9`) the capacity product
  wraps to an arbitrary Int → `NegativeArraySizeException` or a multi-GB allocation, then a
  10⁷ × 10⁷ iteration loop.
- **Reachability at HEAD:** the only production callers funnel through `DabPlacer`'s clamp
  (`RefCanvas.addDabs`/`GlPaintEngine.addDabs` both go through `Tiles.bucket`
  `RefCanvas.kt:59`, `GlPaintEngine.kt:254` — with dabs built by `DabPlacer.emit`). The gap is
  for *future direct* callers of the public `addDabs(List<Dab>)` APIs (tests, tools, a JB-1.05
  "big stamp" feature, the JB-0.08 archive re-render). Fix shape: inside `touchedBy`, bail on
  `!e.isFinite()` (return empty) and clamp the range to a sane tile window — one guard where the
  hazard lives, per "make it impossible to get wrong".

## Verified sound (re-checked this pass with fresh citations)

- **Undo ownership:** I specifically tried to break `resetDocument()` + `UndoLog.clear()`
  (a texture in a layer AND in a stack — double `release` into the pool → two callers would later
  alias one texture). The partition in `UndoLog.kt:11-16` holds: `clear()` releases only
  undo-stack `before`s and redo-stack `after`s (`:63-67`), layers own their current tiles, and
  `push()` releases redo `after`s (`:41-45`); `resetDocument` (`GlPaintEngine.kt:209-214`)
  cancels the stroke first, then clears undo, then recycles layer tiles — disjoint sets, every id
  released exactly once. Muse's "all four transitions traced" verdict stands; I re-derived it.
- **Commit/preview maths parity:** WASH `capForStroke = opacity` (`RefCanvas.kt:55` vs
  `GlPaintEngine.kt:236`), BUILD_UP stroke scale (`RefCanvas.kt:85` vs `GlPaintEngine.kt:376`),
  ERASE-skips-missing-tile (`RefCanvas.kt:88` vs `GlPaintEngine.kt:280` and preview `:358`),
  same commit program for preview and commit (`draw` `:352-363` vs `endStroke` `:278-291`;
  `layerOpacity = layer.opacity` vs `1f` is correct — display-time vs baked-into-layer).
- **Extent constant:** `jb_dab.vert:22` (`a_dab.z * 1.4143 + 2.0`, grep-verified) ==
  `TipMath.extent` (`TipMath.kt:68`).
- **Tile I/O now exists:** muse's "explicitly deferred" item is delivered — `layerIds`,
  `tileKeys`, `readTile`, `writeTile` (`GlPaintEngine.kt:170-206`, KDoc states writeTile is
  deliberately non-undoable), `resetDocument` (`:209`).
- **Input path:** `MotionEventSamples` consumes full history and coerces pen pressure / NaNs finger
  tilt+azimuth (`MotionEventSamples.kt:43-47,57-61`, grep-verified this session); multi-touch
  routing incl. `FLAG_CANCELED` (`JbCanvasView.kt:128`) unchanged from muse's trace; dab
  finiteness now enforced at the placer (`DabPlacer.kt:78-81`).
- **GL hygiene:** FBO bound/unbound symmetrically in `addDabs`/`endStroke`/`readTile`
  (`:241-265`, `:272-297`, `:182-185`); instance buffer grows (`fillInstances` `:449-457`);
  pools trimmed (`trimPool` `:427-429`).
- **Tests:** 280/280 green at `b74aaf0e`, PaintTest's parity/seam/undo cases all passing (same run).

## Bottom line

One open **MAJOR: F1** (context-loss state cleanup — three-line fix, gate for any phone milestone).
F2/F3 are closed with call-count/behaviour tests (the cross-review loop worked). F4 is superseded
by R1; my M2 keeps its spirit as a one-guard hardening for the future direct-caller paths. M1 and
F5 go to the Lead as rulings/notes, not code, for now.
