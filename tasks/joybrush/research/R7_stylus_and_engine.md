# R7 — Stylus input, low-latency inking, and painting-engine architecture for Joy Paint

Research date: 2026-09-28. Author: research agent (R7).
Scope: Topic 1 (stylus input on Android + Samsung/Wacom/USI specifics + smoothing), Topic 2 (low-latency inking: graphics-core, motion prediction, Jetpack Ink), Topic 3 (canvas/engine architecture).

**Confidence tags used throughout**
- **[C]** = confirmed from a cited primary source (Android docs/AOSP/AndroidX source, Khronos, Samsung/Wacom docs, project source).
- **[S]** = supported by secondary sources (reviews, forums, vendor marketing) — plausible but not authoritative.
- **[I]** = inferred / engineering judgement / from prior knowledge not re-verified in this session. Treat as a hypothesis to test on-device.
- **[VERIFY]** = must be measured on the owner's Note 9 / Note 20 before design decisions depend on it.

Project facts checked in repo: `app/build.gradle*` → `minSdk = 24`, `compileSdk = 36`, `targetSdk = 36`. No `androidx.graphics`, `androidx.ink`, or `input-motionprediction` dependency yet. Existing GL code uses `GLSurfaceView` (e.g. `app/src/main/java/com/fadcam/ui/faditor/gltransitions/GlTransitionPreviewView.java`) and a handful of classes use EGL14 directly.

Owner's devices and their OS ceiling (prior knowledge, [I]): Galaxy Note 9 (Snapdragon 845/Adreno 630 or Exynos 9810/Mali-G72, 6/8 GB) — last official OS Android 10 (API 29). Galaxy Note 20 (SD865+/Adreno 650 or Exynos 990/Mali-G77, 8 GB; Ultra 12 GB) — last official OS Android 13 (API 33). **Neither will ever run Android 14 (API 34)**, which matters a lot for Jetpack Ink rendering (see Topic 2).

---

## TOPIC 1 — Stylus input on Android

### 1.1 MotionEvent axes relevant to painting

| Axis / API | Meaning for a stylus | Tag |
|---|---|---|
| `AXIS_X/Y` (+ `getX/getY`) | Sub-pixel float position in view coordinates. | [C] |
| `AXIS_PRESSURE` | Normalised 0..1 (can exceed 1 on some devices if uncalibrated). Kernel `ABS_PRESSURE` must be 0 while hovering and non-zero while touching. | [C] AOSP touch-devices doc |
| `AXIS_TILT` | "0 radians indicates that the stylus is being held perpendicular to the surface, and PI/2 radians indicates that the stylus is being held flat against the surface." | [C] MotionEvent javadoc |
| `AXIS_ORIENTATION` | For a stylus: "the direction in which the stylus is pointing in relation to the vertical axis of the current orientation of the screen", range −π..π; 0 = pointing up, −π/2 = left, ±π = down, +π/2 = right. This is the **azimuth of the tilt**, not barrel rotation. | [C] javadoc (range/direction mapping [I] from javadoc text recalled) |
| `AXIS_DISTANCE` | Hover distance; 0 = contact, larger = farther. Units device-specific; many devices don't report it (you only get hover enter/move/exit). | [C] javadoc; per-device availability [VERIFY] |
| `AXIS_SIZE`, `AXIS_TOUCH_MAJOR/MINOR` | Contact size (normalised / in pixels). For EMR pens usually 0 or constant — useless for the pen, useful for **palm heuristics on finger events**. | [C]/[I] |
| `getToolType(i)` | `TOOL_TYPE_STYLUS`, `TOOL_TYPE_ERASER` ("an eraser or a stylus being used in an inverted posture"), `TOOL_TYPE_FINGER`, `TOOL_TYPE_MOUSE`. | [C] |
| `getButtonState()` | `BUTTON_STYLUS_PRIMARY` / `BUTTON_STYLUS_SECONDARY` (API 23+). Also `ACTION_BUTTON_PRESS/RELEASE` (API 23+). | [C] names; [I] API level |
| `KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY/SECONDARY/TERTIARY/TAIL` | Android 14+: stylus button presses **also** generate KeyEvents. | [C] AOSP stylus doc |
| `FLAG_CANCELED` | Android 13+: set on `ACTION_POINTER_UP` when a pointer was a palm/accidental touch — "the typical actions that occur in response for a pointer going up (such as click handlers, end of drawing) should be aborted." | [C] |

**How the framework derives tilt + orientation** [C, AOSP touch-devices]: the kernel reports `ABS_TILT_X`/`ABS_TILT_Y` in degrees from perpendicular (centre = (max+min)/2). InputReader converts them into "a perpendicular tilt angle ranging from 0 to PI/2 radians and a planar orientation angle ranging from −PI to PI radians". So tilt magnitude and tilt direction arrive pre-computed; the app should build the tilt vector as:

```
tiltVec = (sin(orientation) * sin(tilt), -cos(orientation) * sin(tilt))   // screen space, y down  [I: sign convention — verify with probe]
altitude = PI/2 - tilt
```

Caveat [S]: users report that on some Samsung tablets the orientation direction was reversed (Tab S6 Lite, later fixed by firmware), and on Note 10 Lite "tilt only works in diagonals" (i.e. coarse quantisation). Build a per-device calibration toggle (flip azimuth, deadzone, quantisation smoothing).

### 1.2 Batched (historical) samples, unbuffered dispatch, timestamps

- **Batching** [C]: "For efficiency, motion events with ACTION_MOVE may batch together multiple movement samples"; consume `getHistoricalX/Y/Pressure/AxisValue(axis, pointerIndex, pos)` for `pos in 0 until historySize`, then the current sample. **Never draw only the current sample** — at 120–360 Hz pen rates vs 60 Hz frames, you would drop 50–80 % of the data.
- **`View.requestUnbufferedDispatch(MotionEvent)`** (API 21) / `requestUnbufferedDispatch(int source)` (API 30) [I — reference page not machine-readable this session; the Android stylus guide shows calling it on `ACTION_DOWN` [C]]: asks the input system to deliver events as soon as they arrive instead of batching to vsync. Effects: lower input latency, one sample per event, more `onTouchEvent` calls (CPU/battery cost), and no framework resampling. Google's own low-latency sample calls it on `ACTION_DOWN` of a drawing stroke [C]. Recommendation: call it on `ACTION_DOWN` for stylus strokes only; not for finger pan/zoom.
- **Timestamps**: `getEventTime()`/`getHistoricalEventTime()` are ms (uptimeMillis base). `getEventTimeNanos()` exists from API 34 [I]. On API < 34 you have ms resolution — at 240–360 Hz that is 3–4 ms granularity with jitter, so **velocity must be filtered**, never raw 1-sample finite differences.
- **Pen report rates**: widely quoted but **not authoritatively documented** for S Pen. Galaxy Tab S8+ touch sampling is quoted as 240 Hz [S]; S-series Ultra "360 Hz pen polling" appears only in low-quality sources [S-weak]. Wacom EMR tablets historically 133–200+ Hz [S]. → **[VERIFY]**: the first Joy Paint deliverable should be a *Stylus Probe* screen that logs per-sample Δt histograms, axis ranges, tilt/orientation, distance, button bits, tool type, and `InputDevice.getMotionRange()` for each axis on the Note 9/Note 20.

### 1.3 Tool types, buttons, eraser end, hover

- **Hover** [C]: `ACTION_HOVER_ENTER/MOVE/EXIT` are delivered to `onGenericMotionEvent` / `onHoverEvent`, *not* `onTouchEvent`. Use hover for: brush-cursor preview, palm-rejection arming, and "pen is near" state.
- **Eraser end**: `TOOL_TYPE_ERASER` [C]. S Pens have no eraser end; Wacom Pro Pen 3 / some USI / Surface-style pens do [I].
- **Side buttons**: S Pen has one side button. On current Samsung firmware it is reported via `getButtonState() & BUTTON_STYLUS_PRIMARY` while touching/hovering [I — widely relied on by apps like Concepts, whose help centre documents a configurable "S-Pen side button" [S]]. Historical Samsung quirks: older Samsung firmware used non-standard action codes for "pen with button pressed" [I]; Samsung dev-forum threads report events being disrupted while the button is held when S Pen Remote is active [S]. Also, Samsung's Air Command may grab button+hover clicks system-wide [I]. → Handle both `ACTION_BUTTON_PRESS` and polling `getButtonState()` on every event; also listen for Android 14+ `KEYCODE_STYLUS_BUTTON_*` (not reachable on Note 9/20).
- **Pointer icons**: `View.onResolvePointerIcon()` / `setPointerIcon()` (API 24) let you show a custom hover cursor; Android 14 adds `PointerIcon.TYPE_HANDWRITING` [C]. On Samsung, the S Pen hover dot is drawn by the system; setting a transparent/empty custom icon and drawing your own brush-outline cursor from hover events is the usual approach [I].
- **Android 14+ stylus features** [C]: stylus handwriting into any `EditText` (irrelevant for canvas views; make sure the canvas view is not treated as a text field), stylus button KeyEvents, note-taking role / `ACTION_CREATE_NOTE` (lock-screen note launch — optional nice-to-have for "quick sketch"). Android 13: `FLAG_CANCELED` palm cancellation.

### 1.4 Samsung S Pen specifics

| Item | Finding | Tag |
|---|---|---|
| Technology | Wacom EMR, battery-free tip sensing; third-party EMR pens (Staedtler Noris Digital, LAMY AL-star EMR, Wacom One pen) work on S Pen devices. | [C] Wikipedia/S Pen |
| Pressure levels | 4,096 on Note 9-era and later S Pens; Samsung lists 4,096 for current Ultra pens. Reported to apps as 0..1 float. | [S]/[C] Samsung support |
| Tilt on **tablets** (Tab S6–S11) | Supported, used by Infinite Painter, Clip Studio, Krita, etc. Some model-specific bugs (reversed azimuth on Tab S6 Lite, edge issues). | [S] |
| Tilt on **phones** | S23 Ultra reviewed as "support for pressure and tilt sensitivity" [S]. Note-series phones: forum reports show tilt is reported but may be coarse (Note 10 Lite "tilt only works in diagonals") [S]. No authoritative Samsung statement found for Note 9 or Note 20. | **[VERIFY]** on owner's phones |
| Barrel rotation | **Not supported.** Only Wacom Art Pen (KP-701E), Wacom 6D Art Pen, Apple Pencil Pro, Huawei M-Pencil Pro support barrel rotation. `AXIS_ORIENTATION` on S Pen is tilt azimuth, not twist. | [C] Seven Pens barrel-rotation page (list); [I] for S Pen specifically (absent from list) |
| Hover | Supported (Air View / hover dot); hover range ~10 mm class. Whether `AXIS_DISTANCE` is populated: unknown. | [I] / [VERIFY] |
| Latency (display+digitizer) | Note 20: 26 ms; Note 20 Ultra and S21 Ultra: 9 ms; S22 Ultra: 2.8 ms (Samsung figure, includes prediction). Note 9: no figure found (likely ~40 ms class) [I]. | [C] Wikipedia citing Samsung |
| Bluetooth / Air Actions | Bluetooth S Pen introduced with **Note 9**; continued to S24 Ultra. S25 Ultra's S Pen dropped Bluetooth. S Pen Creator Edition and Fold Edition have no Bluetooth. | [C] Wikipedia, Samsung support |
| S Pen Remote SDK | Samsung SDK (two JARs: `spenremote-v1.0.x.jar`, `sdk-v1.0.0.jar`), `SpenRemote` → `SpenUnitManager` → `SpenUnit.TYPE_BUTTON` (ButtonEvent ACTION_DOWN/UP) and `TYPE_AIR_MOTION` (AirMotionEvent dx/dy in −1..1). Samsung devices only, Note-series class hardware; Air Motion drains battery. Samsung Developer terms apply (not an OSS licence — check compatibility with GPL distribution before bundling; it can be an optional runtime-detected module). | [C] Samsung dev docs |
| Uses for Joy Paint | Remote button = undo / play-pause animation / flip onion skin while pen is out of hand; Air-motion gestures = frame step. Nice-to-have, not core. | [I] |

### 1.5 Wacom, USI, Bluetooth pens on Android

- **Wacom MovinkPad 11 / MovinkPad Pro 14** (Android 14/15 tablets): Pro Pen 3, 8,192 pressure levels, ±60° tilt, EMR battery-free, three side buttons [S — Parka Blogs review, Wacom store]. Expect `BUTTON_STYLUS_PRIMARY/SECONDARY` plus Android-14 key codes for a third button [I].
- **One by Wacom / Wacom One / Intuos over USB-OTG** [S]: supported on Android 14+ (Wacom support article, 403 to fetch; summary via search). Pressure and tilt pass through as a standard stylus. Caveats: no Wacom driver panel, active-area-to-screen mapping on phones is awkward (aspect mismatch), and **it's an indirect (off-screen) device** — the app should handle `SOURCE_STYLUS` from a device that is *not* the touchscreen (hover cursor matters more; no finger touch on the tablet surface). Only one of several apps tested supported stylus buttons [S — Android Police].
- **USI 1.0/2.0** (Chromebooks, Pixel Tablet, some Lenovo/Fire tablets): 2,048 (1.0) / 4,096 (2.0) pressure levels, tilt in 2.0, delivered through standard MotionEvent axes — no SDK [S].
- **Bluetooth HID styluses** [C, AOSP stylus doc]: Android 6.0+ fuses Bluetooth stylus HID data (pressure, barrel buttons, eraser) with the capacitive touchscreen position (`InputDevice.SOURCE_BLUETOOTH_STYLUS`, API 23). Apps receive a normal stylus MotionEvent. Many cheap "active" capacitive pens have **no pressure at all** and appear as `TOOL_TYPE_FINGER` or stylus with pressure 1.0 [I]. → Joy Paint must work well with pressure-less input: velocity-to-width mapping and a "simulated pressure" curve (like Procreate/Infinite Painter's finger modes).

### 1.6 Palm rejection strategy

Layered approach (all [I] engineering, built on [C] platform signals):
1. **Platform first**: honour `ACTION_CANCEL` (abort + remove stroke) and `FLAG_CANCELED` on `ACTION_POINTER_UP` (Android 13+) [C].
2. **Stylus-mode latch**: the first time a `TOOL_TYPE_STYLUS/ERASER` event is seen (hover or touch), switch the canvas into *stylus draws, fingers navigate* mode (persisted per device; user-overridable). Procreate, Infinite Painter and Concepts all expose this [S].
3. **Proximity window**: while the pen is hovering, or for ~300–500 ms after a hover-exit/pen-up, **ignore any finger `ACTION_DOWN` for drawing and for single-finger tools**; still allow two-finger pan/zoom only if both fingers are small and move coherently.
4. **Contact-size heuristic** for finger events: reject if `getTouchMajor()` exceeds ~15–20 mm (convert px→mm via `DisplayMetrics.xdpi`), or if `getSize()` is near max, or if the contact appears within the pen's "hand side" region (left/right-handed setting) during a stylus session.
5. **Deferred commit for finger strokes** (finger-paint mode only): buffer the first ~50–80 ms of a finger stroke; if a second pointer/palm or a stylus appears, cancel it without ever committing to the layer.
6. **Never let navigation gestures leave marks**: all strokes render first into a *stroke buffer* (see Topic 3), so cancellation = discard buffer, zero undo cost.

### 1.7 Deriving rotation without a barrel sensor

Options, ranked (all [I], standard practice in Krita "Drawing Angle", Procreate "Azimuth", MyPaint `direction`):
1. **Tilt azimuth** (`AXIS_ORIENTATION`) when `tilt > ~10°` — physically meaningful for chisel/flat brushes. Below the threshold azimuth is noise → cross-fade to option 2.
2. **Stroke direction**: `θ = atan2(vy, vx)` from the *smoothed* velocity (not raw deltas). Details:
   - Filter the direction as a **unit vector** (EMA on (cos θ, sin θ), then `atan2`) to avoid wrap-around at ±π.
   - **Speed gate / hysteresis**: only update θ when speed > v_min (≈ 20–50 px/s at canvas scale); otherwise hold the last θ. This prevents spinning dabs at stroke start/ends and during pauses.
   - Initialise θ from the first ~3–5 mm of movement (lag the first dabs or back-fill their rotation once known — Krita's "lock angle on start" does something similar).
   - Offer MyPaint-style `direction` (0..180°, sign-ambiguous) vs `direction_360` modes; flat brushes usually want 180° symmetry.
3. **Fixed / random / canvas-relative** angle modes, plus "rotation follows canvas rotation" toggle.
4. Twist emulation is impossible without hardware; don't fake it.

### 1.8 Velocity estimation

- Use time-based, not sample-based, filtering because Δt is irregular (and ms-quantised below API 34).
- Recommended: **1€-filter-style adaptive EMA** on position and a separate EMA on velocity; or a short-window **least-squares fit** (2nd order over last ~20–40 ms), which is what Android's `VelocityTracker` does for pointers (LSQ2/impulse strategies) [I]. `VelocityTracker` itself is fine for UI fling but is per-event, allocation-sensitive, and not designed for per-sample brush dynamics — compute your own in the stroke model.
- Normalise speed to **canvas units per second** (divide by zoom) for brush dynamics, and to screen mm/s for smoothing parameters, so zoom level doesn't change the feel.
- Clamp outliers (Δt < 1 ms or duplicate timestamps from batching → merge samples).

### 1.9 Smoothing / stabilisation

| Technique | What it is | When | Tag |
|---|---|---|---|
| **1€ filter** (Casiez et al.) | Low-pass whose cutoff rises with speed: `fc = mincutoff + beta·|dx̂|`; params `mincutoff`, `beta`, `dcutoff`. Tuning: beta=0, adjust mincutoff (≈1 Hz) for no jitter when slow; then raise beta (start 0.001/0.0001) to kill lag when fast. BSD/MIT reference implementations incl. Java. | Always-on light de-jitter of raw input (position, pressure, tilt). | [C] gery.casiez.net/1euro |
| **Pulled string / lazy mouse** | Brush point stays still until pen moves beyond radius R, then is dragged along the segment. Zero wobble, visible lag; "catch up" at stroke end. | User "Stabiliser" slider (Krita "Stabilizer", Procreate "StreamLine", Clip Studio "Stabilization"). | [I] |
| **Spring–mass model** (Google ink-stroke-modeler) | Stages: wobble smoothing (time-variant moving average), position modelled as mass on spring towards the input (Euler integration with drag), upsampling to a min rate, stylus-state interpolation (pressure/tilt/orientation), prediction (Kalman or "stroke-end" catch-up). Apache-2.0, C++20, Abseil only; Bazel + CMake. | Best-quality modelled pen; can be used via NDK or ported to Java (≈1–2 kLOC). | [C] github.com/google/ink-stroke-modeler |
| **Catmull-Rom → cubic Bézier fitting** | Interpolate *between* filtered samples for dab placement; centripetal CR avoids cusps. Re-parameterise by arc length for dab spacing. | Dab placement / vector stroke storage. | [I] |
| **Curve fitting on stroke end** (Schneider-style Bézier fit) | Fit a minimal Bézier chain to the finished stroke. | Vector layers / "QuickShape" style clean-up; not for wet raster. | [I] |

**Recommendation**: pipeline = raw samples (all historical) → 1€ on x/y/pressure → optional user stabiliser (pulled string or spring–mass with adjustable mass) → arc-length resampler emitting dabs at `spacing × diameter` → brush dynamics. Keep the raw samples in the stroke record so strokes can be re-smoothed/re-rendered later (vector layers, animation re-timing).

---

## TOPIC 2 — Low-latency inking

### 2.1 androidx.graphics:graphics-core (front-buffer rendering)

- **Status** [C]: stable; 1.0.0 (2024-05-29) → 1.0.4 (2025-12-03). Fixes of note: 1.0.2 "front buffer usage flag flickering on Android 14+", 1.0.3 "full-screen flickers while drawing on certain devices with API<33", 1.0.4 "compatibility and performance for particular devices".
- **API level**: front-buffered rendering is available on **Android 10 (API 29)+** [C — Android stylus docs/"low-latency library is available from Android 10"]. Note 9 (API 29) and Note 20 (API 33) both qualify. Below 29 you must fall back to normal double-buffered rendering.
- **Classes** [C]:
  - `GLFrontBufferedRenderer<T>(surfaceView, callback)` — two layers: `onDrawFrontBufferedLayer(eglManager, bufferInfo, transform, param)` renders *only the new increment* straight to the front buffer (no vsync wait); `onDrawMultiBufferedLayer(eglManager, bufferInfo, transform, params)` renders the full scene on `commit()`. `cancel()` on `ACTION_CANCEL`. Renamed "double" → "multi" buffered in alpha04.
  - `CanvasFrontBufferedRenderer<T>` (Canvas API variant), `LowLatencyCanvasView` (View-hierarchy convenience), `GLFrameBufferRenderer` (general multi-buffer swap-chain with configurable depth), `SurfaceControlCompat`, `BufferTransformer` for pre-rotation.
- **Constraints** [C]: meant for small incremental regions (the active stroke). Full-screen updates, pan, zoom on the front buffer cause **tearing** — switch to the multi-buffered path while navigating.
- **Integration with Joy Creator** [I]: GLFrontBufferedRenderer takes a `SurfaceView` and owns its own GL thread (`GLRenderer`) and EGL context; it cannot be bolted onto the existing `GLSurfaceView` classes. Joy Paint should have its own `SurfaceView` + `GLRenderer`. The front-buffer and multi-buffer callbacks run on the same GL thread/context, so the layer tile textures and stroke buffer are accessible from both [I — confirm with a spike].
- **What goes in the front layer for a painting app** [I]: render the new dabs for this input event directly (same shader as the stroke buffer) composited over nothing (the front buffer already contains the last committed frame + previous increments). This is exact only for **normal-blend, non-mixing** brushes. For wet/smudge/blend-mode brushes, draw an *approximate* preview in front and replace it on the next multi-buffered frame (≤ 1 frame later). This is the classic compromise.

### 2.2 Motion prediction

- `androidx.input:input-motionprediction` — **1.0.0 stable 2025-11-19** [C]. `MotionEventPredictor.newInstance(view)`, `record(event)`, `predict()` → a synthetic MotionEvent (includes orientation/tilt). minSdk raised 21 → **23** in rc01 [C]. Built-in Kalman predictor; on **API 34+ delegates to the platform `android.view.MotionPredictor`** (TFLite-based model in AOSP) [C].
- Rule [C]: predicted points are temporary; draw them only in the front layer/overlay and **erase them when real events arrive**; never commit.
- On Note 9/Note 20 (API ≤ 33) you get the Kalman path. Predict ~1 frame (8–16 ms); more causes overshoot at stroke direction changes [I]. Expose a "prediction" setting (off/low/high) like Infinite Painter's latency modes (Disabled/Active/Fastest) [C — Infinite Painter settings docs].

### 2.3 Measured latency numbers

| Figure | Source | Tag |
|---|---|---|
| Ink API "4 ms end-to-end latency" writing on Samsung Galaxy Tab S8 | Android Developers Blog, Oct 2024 | [C] (vendor claim; methodology not published) |
| S22 Ultra S Pen 2.8 ms; Note 20 Ultra & S21 Ultra 9 ms; Note 20 26 ms | Wikipedia (Samsung figures) | [C]-ish (marketing) |
| Infinite Painter 7.1 adopted Google's "Low Latency Drawing" (front buffer) | Infinite Painter docs | [C] |

No independent published measurements of GLFrontBufferedRenderer on Note 9/20 were found → measure with a high-speed phone camera (240 fps slo-mo) during the spike [VERIFY].

### 2.4 Jetpack Ink (androidx.ink)

**Maturity** [C]: 1.0.0 stable **2025-12-17**; 1.1.0-alpha09 on 2026-09-23. Modules: `ink-authoring(-compose)`, `ink-brush(-compose)`, `ink-geometry(-compose)`, `ink-nativeloader`, `ink-rendering`, `ink-storage`, `ink-strokes`. Kotlin Multiplatform; JVM/Linux x86_64 server rendering; experimental Metal renderer for iOS. Core is Google's C++ `google/ink` library (Apache-2.0, Bazel 7, "no hard guarantees about interface stability") [C]. Licence of the Jetpack artifacts: Apache-2.0 (AndroidX) [I—standard for AndroidX]. Apache-2.0 is compatible with GPL-3.0 distribution [I—well-established].

**minSdk** [C/I]: launch blog says "Android 5.0 (API 21) or later, with enhanced functionality on Android 10 (API 29) and Android 14 (API 34)". AndroidX raised many default minSdks to 23 in 2025 — irrelevant for Joy Creator (minSdk 24).

**API shape** [C, module docs]:
- `StrokeInputBatch` (x, y, t, optional pressure/tilt/orientation) → `InProgressStroke` (live) → immutable `Stroke` = `ImmutableStrokeInputBatch` + `Brush` + `PartitionedMesh`.
- `Brush` = color + size + `BrushFamily` (like a font family). `BrushFamily` = `BrushCoat`s, each with `BrushTip` (shape, behaviours driven by inputs) and `BrushPaint` (texture layers, colour functions, self-overlap).
- `InProgressStrokesView` = the low-latency authoring surface (built on graphics-core front buffer); `InProgressStrokesFinishedListener` hands you finished strokes.
- `CanvasStrokeRenderer`/`ViewStrokeRenderer` render onto an `android.graphics.Canvas`.
- `ink-storage`: protobuf + delta compression of `StrokeInputBatch`; custom BrushFamily protobufs.
- Geometry: `Box`, `Vec`, `PartitionedMesh` intersection/coverage → lasso select, stroke eraser; 1.1 alpha adds **partial (geometry-splitting) eraser**.

**Textures / brush expressiveness** [C, google/ink `brush_paint.h`]: `TilingTexture` (repeats by an affine transform of vertex positions; sizes in brush-size or stroke units; wrap repeat/mirror/clamp) and `StampingTexture` (copied onto each **particle** of a particle coat; supports sprite-sheet animation). 10 blend modes between texture layers (modulate … xor). Self-overlap modes `ANY/ACCUMULATE/DISCARD`. 1.1-alpha09: colour shift now in **Oklab** (CHROMA/LIGHTNESS targets) [C].

**Critical rendering finding (from AndroidX source)** [C]:
- `CanvasStrokeUnifiedRenderer` uses `CanvasMeshRenderer` (android.graphics.Mesh + SkSL, "both more performant and more fully featured") **only when `SDK_INT >= 34`**; otherwise `CanvasPathRenderer`.
- `CanvasPathRenderer.canDraw()` refuses coats whose texture mapping is not `MAPPING_TILING` (**no stamping/particle textures**), supports only self-overlap `ANY/DISCARD`, and applies a **single colour per coat (no per-vertex colour/opacity shift)**.
- → On the owner's Note 9 (API 29) and Note 20 (API 33), Jetpack Ink renders only the simpler path-based subset. Textured/particle brushes silently drop coats ("logs a warning and skips that coat").
- Custom GL rendering of Ink meshes is not a public path: `Mesh.getRawVertexBuffer()` / `getRawTriangleIndexBuffer()` are `@RestrictTo(LIBRARY_GROUP)` + `@InkInternalOnlyApi`; public `Mesh` exposes only `vertexCount`, `vertexStride`, `triangleCount`, `bounds`, `vertexAttributeUnpackingParams`, `fillPosition()`. Per-vertex attributes are not publicly readable.

**Suitability as Joy Paint's VECTOR stroke layer** [I, based on the above]:
- ✅ Good for: a clean "ink/lineart" vector layer (pens, markers, highlighters), selection/lasso geometry, stroke/partial erasing, compact stroke storage, handwriting-like notes, server/desktop parity via JVM.
- ❌ Poor fit for: painterly textured brushes on API < 34 (i.e. the owner's phones), wet mixing/smudge (Ink is mesh-extrusion, not dab/stamp-accumulation — it cannot read the canvas), custom GLSL brush math shared with a desktop web prototype, GPU tile-engine integration (renders to `android.graphics.Canvas`, not your FBOs, without internal APIs).
- Middle path: use **`ink-stroke-modeler`** (the smoothing/prediction half, Apache-2.0, small C++, CMake) or port its spring model to Java; optionally depend on `ink-geometry` for hit-testing. Revisit full Ink when minimum target devices are API 34+ or if Google exposes a public mesh/GL renderer.

---

## TOPIC 3 — Canvas / engine architecture

### 3.1 Tile-based sparse raster storage — what shipping engines do

| Engine | Tile size / format | Notes | Tag |
|---|---|---|---|
| **Krita** | 64×64 tiles; tiles stored/compressed per tile (LZF) in `.kra` (`TILEWIDTH 64`, `PIXELSIZE 4` for RGBA8). Paint devices are unbounded; image bounds are a crop. Tiles are COW; undo uses centralised tile-history in the data manager (new engine replaced per-memento hash tables — old: "two hash tables at least 4 KiB each" per memento; new: "three hash tables per data manager"). Swapper compresses/swaps idle tiles to disk. Dab rendering on **CPU**; OpenGL used for display only. | [C] KDE wiki Tile Data Format / Transactions Design; [I] CPU dabs |
| **MyPaint / libmypaint** | 64×64 tiles, RGBA **uint16 "fix15"** (0x8000 = 1.0), **premultiplied**, sRGB-gamma. "Memory is allocated just for the tiles that you paint on" → infinite canvas for free. Dabs on CPU; all tiles touched by the stroke are composited, update region clipped to new dabs' bbox. | [C] mypaint.app backend docs |
| **Procreate (iPad)** | "Valkyrie" Metal GPU engine, "64-bit color" (16 bpc). Canvas limits by device: 134 MP max (e.g. 11,585²) on recent iPads, 16,384 px longest edge; layer count computed dynamically from RAM and canvas size (e.g. 59 layers at 2224×1668 vs 12 at A3 4960×3508 on a 4 GB iPad Pro). Tile size unpublished. | [C] Procreate help; [S] engine |
| **Infinite Painter (Android/iOS)** | GPU (OpenGL), default **16 bits per channel** ("Deep color (64 bit)" toggle to disable on incompatible devices), optional gamma-corrected mixing; layers limited only by memory; canvas can be expanded/cropped any time (not auto-infinite); Google Low Latency Drawing since 7.1. | [C] Infinite Painter docs |
| **Heavypaint** | "Mesh painting program under the hood, not raster" — strokes as meshes, resolution-scalable. | [S] |
| **Concepts** | Vector infinite canvas; textured brushes are image-stamp based vector strokes that "pixellate if you zoom in too far". | [C] concepts.app |

### 3.2 "Infinite" canvases

- **Raster-infinite (MyPaint model)**: sparse tile map keyed by (tx, ty) — any tile can exist; the document has no fixed size; export crops to the painted bbox or a user frame. Cost is proportional to painted area only [C MyPaint].
- **Pseudo-infinite (Krita)**: fixed document bounds, but panning past the edge shows a button to grow the canvas; growing is cheap because devices are tiled [C Krita 2.8 blog/manual].
- **Expandable (Infinite Painter)**: explicit crop/expand at any time [C].
- **Vector-infinite (Concepts, Heavypaint)**: geometry scales; textures pixelate beyond their native resolution [C/S].
- **Recommendation** [I]: Joy Paint uses the **MyPaint model** (sparse, unbounded tile map) with an optional *artboard* rectangle (for animation frames, export, and onion-skin alignment). For animation, every frame = its own sparse layer set sharing the artboard; this makes 2D animation memory proportional to drawn content, not frames × canvas.

### 3.3 Memory budgets on 6–12 GB phones

Arithmetic [I]:
- RGBA8: 4 B/px. 4096² = 16.8 MP → **64 MiB** per fully covered layer. 2048×2048 → 16 MiB. 8K² → 256 MiB.
- RGBA16F: 8 B/px → **128 MiB** per 4K² layer.
- Tile 256² RGBA8 = 256 KiB; RGBA16F = 512 KiB.
- Android does not give an app all RAM: GPU textures count against the process's graphics PSS; the LMK kills backgrounded or pressure-causing apps. Practical safe budget on a 6 GB Note 9 ≈ 0.8–1.2 GB of GPU tile pool, on 8 GB Note 20 ≈ 1.5–2 GB, on 12 GB Tab/Ultra ≈ 3 GB. Read `ActivityManager.getMemoryInfo()` / `getLargeMemoryClass()` at start, react to `onTrimMemory` [I].
- So on a Note 9 at 4K², RGBA8: ~12–16 *fully covered* layers resident, far more if sparse. That's in the same league as Procreate's 4 GB iPads (12 layers at A3) [C numbers from Procreate].
- **Two-tier storage**: GPU-resident tile pool (LRU) + CPU-side compressed tiles (LZ4/zstd, typical 3–10× for line art) + disk swap for undo history and off-screen animation frames. Evict tiles of hidden layers / non-visible frames first.
- Layer-count limit should be computed dynamically, like Procreate, and shown to the user.

### 3.4 Undo via tile deltas / copy-on-write

Recommended design [I, patterned on Krita's tile-history and MyPaint snapshots]:
1. A stroke is rendered into a separate **stroke buffer** (screen-sized or bbox-sized RGBA16F FBO) and composited into the layer only on pen-up ("wet layer" — also how opacity-capped strokes, stroke-level opacity and cancel work).
2. On commit, for each tile the stroke touches: **copy the old tile** (GPU→GPU `glCopyImageSubData` — core in ES 3.2 — or `glBlitFramebuffer` in ES 3.0) into an undo tile, then composite. Undo record = list of (layer, tx, ty, oldTileRef, newTileRef?).
3. Move undo tiles to CPU asynchronously (PBO readback, compress on a worker thread), keep last N strokes on GPU for instant undo.
4. Redo either keeps new tiles too (memory ×2) or **replays the stroke record** (deterministic brush with stored seed) — replay is cheaper in memory and also powers timelapse and animation retiming. Keep both options: tile snapshots for correctness, stroke records for features.
5. Budget undo by bytes, not steps.

### 3.5 GPU vs CPU dab rendering

| | GPU (GLES 3.x) | CPU (NEON/Java) |
|---|---|---|
| Who | Procreate (Metal), Infinite Painter, most modern mobile apps [C/S] | Krita, MyPaint, Clip Studio (mostly) [I] |
| Throughput | Thousands of dabs/frame as instanced quads; large soft brushes cheap | Large brushes expensive (area ∝ r²); SIMD helps |
| Read-back of canvas (smudge, wet mix, colour pick) | Needs ping-pong or framebuffer-fetch; sequential dependency between dabs breaks batching | Trivial |
| Determinism across devices | Varies by GPU precision (mediump!), blend precision | Bit-exact |
| Latency path integration | Natural with GLFrontBufferedRenderer | Needs upload per frame |
| Web prototype parity | **WebGL2 = GLSL ES 3.00 → same shader source runs on Android GLES 3.0+** | JS/WASM code would need porting |

**Key GL facts for the owner's devices** [C]:
- OpenGL ES **3.2** absorbed the Android Extension Pack: floating-point render targets, **advanced blend equations** (`KHR_blend_equation_advanced`: multiply, screen, overlay, darken, lighten, dodge, burn, hard/soft light, difference, exclusion, HSL modes), ASTC, texture buffers, `glCopyImageSubData` [C Khronos 2015 announcement]. Adreno 630/650 and Mali-G72/G77 are ES 3.2 devices [I].
- `EXT_shader_framebuffer_fetch` exists on Adreno 5xx/6xx and Mali Bifrost gen-2+ [S] — enables programmable blend modes and dab-level canvas reads **without ping-pong** in one pass (with coherent ordering per pixel). Must be feature-detected; keep a ping-pong fallback.
- `GL_TEXTURE_2D_ARRAY` (ES 3.0) for tile pools and brush-tip atlases; `GL_MAX_ARRAY_TEXTURE_LAYERS` ≥ 256 guaranteed [I].

**Recommendation**: GPU dab engine with a narrow CPU role:
- Dabs rendered as **instanced quads** into the stroke buffer (attributes: centre, radius, angle, aspect, opacity/flow, colour, tip index, grain offset, per-dab random seed).
- **Normal/erase/most blend modes** in the stroke→layer composite shader (not per dab).
- **Smudge/wet mixing**: MyPaint-style "pickup colour" — every k dabs, sample the canvas under the dab into a tiny pickup texture (or 1-px average via mip-level), carry it as dab colour state. This is O(1) GPU reads per dab and batches well; full per-pixel smudge (Procreate's) uses framebuffer fetch or a small ping-pong region around the dab.
- **Brush math lives in shared GLSL ES 3.00 + JSON param schema**, identical in the desktop web prototype (WebGL2) and Android — strongest argument for GPU.
- Use `highp` float in fragment shaders for position/accumulation (mediump on Mali causes banding and position jitter) [I].

### 3.6 8-bit vs 16-bit/half-float, banding, linear vs sRGB

- 8-bit layers band visibly with **soft, low-flow brushes** (repeated small alpha increments quantise to 1/255 → "stair-steps" and flow floors where a 2 % flow brush stops accumulating) [I, well known]. Krita: 16-bit int gives "smoother gradients"; 16-bit float has ~10–11 bits effective precision but more range [C Krita manual].
- Infinite Painter defaults to 16 bpc and Procreate markets "64-bit color" [C] — the premium mobile apps pay the 2× memory.
- **Linear vs sRGB** [C Krita manual]: blending in gamma space causes dark fringes on soft edges/blur and "muddy" colour mixing; linear gives physically correct gradations but white overpowers when mixing with black (artists often dislike pure linear for everything). Infinite Painter makes gamma-corrected mixing a toggle [C].
- **Recommendation** [I]:
  - **Stroke buffer: RGBA16F always** (the accumulation stage is where banding originates).
  - **Layer storage: RGBA8 premultiplied sRGB by default** (memory), **per-document "Deep colour" = RGBA16F** when device RAM ≥ 8 GB, as Infinite Painter does.
  - Composite in a user-selectable space: "Classic (sRGB, matches Photoshop/PSD)" default, "Linear (natural light mixing)" option. Keep brush-colour mixing (smudge/wet) optionally in Oklab/linear — Jetpack Ink also moved colour shifts to Oklab [C].
  - Dither when converting 16F → 8-bit on commit/export (blue-noise or ordered dither) to hide residual banding.

### 3.7 Blend modes on GPU

- Separable modes (multiply, screen, overlay, darken, lighten, dodge, burn, hard/soft light, difference, exclusion) and non-separable HSL modes follow the W3C Compositing spec formulas — the same set as OpenRaster `svg:*` ops and PSD modes [C OpenRaster list].
- Implementation options: (a) `KHR_blend_equation_advanced` in fixed-function blending (ES 3.2 core; needs `glBlendBarrier` between overlapping draws unless `_coherent` variant); (b) framebuffer fetch; (c) **layer compositing shader** that samples both backdrop and layer textures and writes to a third target (ping-pong) — the most portable and what a tile compositor naturally does (composite per tile, bottom-up, cached per group). Recommendation: (c) for layer stacks + cache of the "everything below the active layer" and "everything above" composites, so painting touches only 3 textures per tile per frame [I].

### 3.8 File formats

- **OpenRaster (.ora)** [C openraster.org, v0.0.6]: ZIP; first entry `mimetype` = `image/openraster` STORED; `stack.xml` (`<image w h version xres yres>`, nested `<stack>` with `isolation`, `<layer src x y opacity visibility composite-op name>`); `data/*.png`; `Thumbnails/thumbnail.png` ≤ 256²; `mergedimage.png` (8 or 16 bpc; required since 0.0.2). Composite ops: `svg:src-over, multiply, screen, overlay, darken, lighten, color-dodge, color-burn, hard-light, soft-light, difference, color, luminosity, hue, saturation, plus, dst-in, dst-out, src-atop, dst-atop`. Supported by Krita, GIMP, MyPaint, Scribus. Signed x/y offsets per layer map perfectly to sparse tiled layers (write each layer's painted bbox). **Recommended interchange format** — trivial to write from Java (`ZipOutputStream` + `Bitmap.compress(PNG)` or a 16-bit PNG encoder).
- **Native format**: ORA-compatible ZIP extended with private namespaced XML/JSON (animation timeline, stroke records, brush refs, vector layers) and **raw compressed tiles** instead of PNGs for fast save/load, plus a standard `mergedimage.png` — so any ORA reader still opens a flattened/partial view [I].
- **PSD** libraries:

| Library | Lang | R/W | Licence | Android fit | Tag |
|---|---|---|---|---|---|
| ag-psd | TypeScript/JS | Read+Write (layers, blend modes, opacity, clipping, masks; no 16-bit, no PSB, text partial; does not regenerate composite) | **MIT** | Ideal for the **desktop web prototype**; not for Android runtime | [C] |
| Molecular Matters psd_sdk | C++ | Read (groups, masks, 8/16/32-bit), **limited export** | BSD-2-Clause | NDK possible; mobile "not planned" by authors, ~2 % porting | [C] |
| aseprite/psd | C++ | Experimental WIP | MIT | Not production | [C] |
| pixelprobe (AOSP tools/base) | Java | Read | Apache-2.0 [I] | Uses desktop Java imaging (AWT) [I] → not usable on Android as is | [S]/[I] |
| java-psd-library | Java | Read | LGPL-3.0 | Old, AWT-based [I] | [S] |
| TwelveMonkeys imageio-psd | Java | Read | BSD | Needs `javax.imageio` (absent on Android) [I] | [I] |
| Krita / GIMP PSD code | C++/C | R/W | GPL | Licence-compatible with GPL-3.0 Joy Creator, but large and Qt/GEGL-bound | [I] |
| Aspose.PSD | Java | R/W | Commercial | No | [S] |

  → **Write a small custom PSD writer in Java** (8-bit RGB, layer records with blend-mode keys, opacity, visibility, RLE/PackBits channel data, merged image; ~600–1000 LOC) against Adobe's published spec; import via a custom reader for the same subset. Use ag-psd in the web tool and as a test oracle (round-trip files between them) [I].

### 3.9 Hybrid vector + raster designs

| App | Vector model | Raster interplay | Tag |
|---|---|---|---|
| **Clip Studio Paint** | Vector layers record the pen trajectory + brush settings; control points editable; "erase up to intersection". Nearly any brush can be used, rendered by the brush engine along the path. | Incompatible on vector layers: "Color Mixing", "Watercolor Edge", "Anti-Overflow"; can't fill or paint thinly/mix. Transforming a patterned-brush vector line rescales the pattern rather than resampling pixels. | [C/S] CSP tips |
| **Concepts** | Everything is a vector stroke on an infinite canvas; even soft pencils/watercolour are movable. | Texture brushes are image stamps along the vector path; pixelate at high zoom. | [C] |
| **Jetpack Ink** | Input batch + brush → extruded triangle mesh (+ particles); textures tiled or stamped. | Rendered via Canvas; no canvas read-back. | [C] |
| **Heavypaint** | Strokes as meshes, resolution-scalable painting. | — | [S] |

**How vector strokes with textured brushes get rasterised** [I, consistent with the above]: the stored primitive is the **input sample stream + brush preset + seed**; rendering = re-run the brush engine (dabs or mesh) at the current zoom/export resolution. Textures are sampled in *stroke space* (moves with the stroke) or *canvas/paper space* (grain stays put). Cache the rasterisation per zoom-bucket in tiles; invalidate on edit.

**Recommendation for Joy Paint** [I]:
- **One brush engine, two layer kinds.**
  - *Raster (paint) layers*: stroke → dabs → stroke buffer → baked into tiles. All brushes including smudge/wet/mixing. Stroke records kept only for undo/redo/timelapse.
  - *Vector (ink/line) layers*: strokes stored as records (smoothed samples + brush + seed); rendered by the **same GPU dab engine** into a tile cache at the current view resolution (re-rasterised on zoom settle / export). Brushes whose settings need canvas read-back (smudge, wet mix, "anti-overflow") are disabled on vector layers — the exact Clip Studio rule.
- Vector layers are what 2D animation needs most: editable line art, stroke-level transforms, stroke interpolation for inbetweens, cheap memory per frame, lasso-select strokes, "erase to intersection".
- A Bézier-fitted **path** representation (for SVG export and control-point editing) is derived from the sample stream on demand, not the primary truth.

---

## Recommended architecture (condensed)

```
MotionEvent (all historical samples, unbuffered on stylus DOWN)
  → InputNormaliser (tool type, pressure curve, tilt vec, azimuth calibration, buttons, hover state, palm filter)
  → StrokeModel (1€ de-jitter → user stabiliser [pulled-string | spring-mass] → arc-length resampler → dynamics: speed, direction θ, tilt)
  → Predictor (androidx MotionEventPredictor; front-layer only)
  → BrushEngine (GLSL ES 3.00 shared with web prototype; instanced dabs → RGBA16F stroke buffer)
  → Presenter: GLFrontBufferedRenderer (API 29+) front = new dabs; multi-buffer = tile compositor
  → Commit: COW tiles → layer (RGBA8 or RGBA16F), undo record (tile snapshots + stroke record)
Storage: sparse 256² tile map per layer (GPU pool, texture arrays) ⇄ CPU compressed tiles ⇄ disk swap
Files: native = ORA-superset ZIP (raw tiles + JSON), interchange = ORA, PSD export (custom writer)
```

Tile size: **256×256 on GPU** (fewer draw calls/state changes than 64², still fine-grained for sparse coverage and undo; 256 KiB RGBA8), stored in `GL_TEXTURE_2D_ARRAY` pages (e.g. 64 slices per page). CPU/disk representation may sub-tile at 64² for better compression of sparse edges [I].

## Risks

1. **Tilt quality on Note 9/Note 20 unknown** — may be absent or coarse. Mitigate: stroke-direction rotation as the default dynamic; tilt as optional.
2. **Front-buffer compatibility** — graphics-core release notes show device-specific flicker fixes through 1.0.4; Samsung One UI overlays (Air View hover, edge panels) may interact. Keep a runtime kill-switch (Infinite Painter ships Disabled/Active/Fastest).
3. **Jetpack Ink on API < 34** loses textured/particle coats; raw mesh APIs are internal. Don't make it the core engine.
4. **GPU precision/driver variance** (Mali mediump, float-blend quirks, framebuffer-fetch availability) → brush results differ web vs Android vs Adreno vs Mali. Mitigate with golden-image tests per GPU family and `highp`.
5. **Memory/LMK kills** with many layers × animation frames → dynamic layer limits, tile eviction, autosave of tiles to disk.
6. **Web prototype ↔ Android parity**: only guaranteed if the prototype targets WebGL2 with the same shader + parameter schema and deterministic RNG.
7. **S Pen Remote SDK licence** (Samsung terms, closed JARs) vs GPL-3.0 distribution — keep optional or skip.
8. **API-level floor**: minSdk 24 devices get no front buffer (API < 29) and ms-resolution timestamps; ensure a normal double-buffered path is solid.
9. **Samsung button/Air Command interception** may swallow side-button events on some firmware — needs on-device testing.

## Suggested first spikes

1. *Stylus Probe* activity (log axes, ranges, Δt histogram, buttons, hover, tool types) on Note 9 + Note 20 + any Tab S.
2. *Latency spike*: GLFrontBufferedRenderer + instanced dab shader + MotionEventPredictor; measure with 240 fps camera vs a plain GLSurfaceView path.
3. *Banding spike*: soft 2 %-flow airbrush into RGBA8 vs RGBA16F stroke buffer; confirm Mali/Adreno parity.
4. *Tile pool spike*: 256² texture-array pool, COW undo, 4K² canvas × 20 layers on the Note 9; record PSS and LMK behaviour.

---

## Sources

Android / AOSP / AndroidX
- Advanced stylus features (Views): https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/advanced-stylus-features
- MotionEvent source (javadoc quotes): https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/core/java/android/view/MotionEvent.java
- MotionEvent reference: https://developer.android.com/reference/android/view/MotionEvent
- AOSP touch devices (tilt/orientation derivation, hover, tool type): https://source.android.com/docs/core/interaction/input/touch-devices
- AOSP stylus accessories (HID, Bluetooth fusion, Android 14 KeyEvents): https://source.android.com/docs/core/interaction/accessories/stylus
- Stylus input in text fields / handwriting pointer icon: https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/stylus-input-in-text-fields
- graphics-core releases: https://developer.android.com/jetpack/androidx/releases/graphics
- input-motionprediction releases: https://developer.android.com/jetpack/androidx/releases/input
- MotionEventPredictor: https://developer.android.com/reference/androidx/input/motionprediction/MotionEventPredictor
- Ink releases: https://developer.android.com/jetpack/androidx/releases/ink
- Ink modules: https://developer.android.com/develop/ui/views/touch-and-input/stylus-input/ink-api-modules
- About Ink API: https://developer.android.com/develop/ui/compose/touch-input/stylus-input/about-ink-api
- Introducing Ink API (4 ms on Tab S8; API 21/29/34): https://android-developers.googleblog.com/2024/10/introducing-ink-api-jetpack-library.html
- Ink renderer selection source: https://raw.githubusercontent.com/androidx/androidx/androidx-main/ink/ink-rendering/src/androidMain/kotlin/androidx/ink/rendering/android/canvas/internal/CanvasStrokeUnifiedRenderer.android.kt
- Ink path renderer source: https://raw.githubusercontent.com/androidx/androidx/androidx-main/ink/ink-rendering/src/androidMain/kotlin/androidx/ink/rendering/android/canvas/internal/CanvasPathRenderer.android.kt
- Ink mesh renderer source: https://raw.githubusercontent.com/androidx/androidx/androidx-main/ink/ink-rendering/src/androidMain/kotlin/androidx/ink/rendering/android/canvas/internal/CanvasMeshRenderer.android.kt
- Ink Mesh public API: https://raw.githubusercontent.com/androidx/androidx/androidx-main/ink/ink-geometry/src/commonMain/kotlin/androidx/ink/geometry/Mesh.kt
- Ink raw mesh buffers (restricted): https://raw.githubusercontent.com/androidx/androidx/androidx-main/ink/ink-geometry/src/jvmAndAndroidMain/kotlin/androidx/ink/geometry/JvmMeshExtensions.jvmAndAndroid.kt
- google/ink: https://github.com/google/ink ; BrushPaint header: https://github.com/google/ink/blob/main/ink/brush/brush_paint.h
- google/ink-stroke-modeler: https://github.com/google/ink-stroke-modeler
- Front-buffer sample gist: https://gist.github.com/Mercandj/0a9faf62aa4f1b7a18a1756d31a6f67d

Samsung / Wacom / pens
- S Pen (Wikipedia; latency figures, Bluetooth history): https://en.wikipedia.org/wiki/S_Pen
- Samsung — different S Pens: https://www.samsung.com/us/support/answer/ANS10003226/
- S Pen Remote SDK: https://developer.samsung.com/galaxy-spen-remote/s-pen-remote-sdk.html ; API: https://developer.samsung.com/galaxy-spen-remote/api-reference/com/samsung/android/sdk/penremote/SpenUnitManager.html
- Samsung dev forum (button + touch events): https://forum.developer.samsung.com/t/no-touch-events-while-button-is-pressed/3119
- Tab S6 Lite rotation issue: https://eu.community.samsung.com/t5/tablets/samsung-tab-s6-lite-spen-rotation-issue/td-p/1857736
- Note 10 Lite tilt diagonals thread: https://r2.community.samsung.com/t5/Galaxy-Note/Note-10-Lite-S-Pen-tilt-only-works-in-diagonals/m-p/5268003/highlight/true
- S23 Ultra S Pen artist review: https://www.parkablogs.com/content/artist-review-samsung-s23-ultra-s-pen
- Barrel rotation pens list: https://docs.sevenpens.com/drawtab/core/barrel-rotation
- Concepts S Pen side button: https://tophatch.helpshift.com/hc/en/3-concepts/faq/193-how-do-i-customize-the-s-pen-side-button/
- Wacom MovinkPad 11 review: https://www.parkablogs.com/content/wacom-movink-pad-11-review ; store: https://estore.wacom.com/en-us/wacom-movinkpad-11-dtha116cl0z.html
- Wacom with Android: https://support.wacom.com/hc/en-us/articles/1500006342822-Which-pen-tablet-can-I-use-with-Android
- Wacom One M on Android review: https://www.androidpolice.com/wacom-one-m-review/
- USI: https://en.wikipedia.org/wiki/Universal_Stylus_Initiative

Smoothing
- 1€ filter: https://gery.casiez.net/1euro/

Engines, formats, colour
- Krita tile data format: https://community.kde.org/Krita/Tile_Data_Format
- Krita transactions/undo design: https://community.kde.org/Krita/Transactions_Design
- Krita pseudo-infinite canvas: https://dimula73.blogspot.com/2013/05/krita-28-prealpha-new-pseudo-infinite.html
- Krita bit depth: https://docs.krita.org/en/general_concepts/colors/bit_depth.html
- Krita linear vs gamma: https://docs.krita.org/en/general_concepts/colors/linear_and_gamma.html
- MyPaint canvas backend: https://www.mypaint.app/en/docs/backend/canvas/ ; libmypaint tiled surface: https://github.com/mypaint/libmypaint/blob/master/mypaint-tiled-surface.c
- Procreate max canvas: https://help.procreate.com/articles/dabqrn-maximum-canvas-size ; layer limit: https://help.procreate.com/articles/YB7CjQ-maximum-layer-limit
- Procreate 5 / Valkyrie: https://www.macstories.net/reviews/procreate-5-review-a-rebuilt-graphics-engine-drives-fantastic-animation-color-and-brush-tools-in-an-art-app-perfectly-tailored-to-the-ipad/
- Infinite Painter settings (16 bpc, low latency modes): https://docs.infinitestudio.art/painter/technical/settings/ ; layers: https://docs.infinitestudio.art/painter/layers/
- Concepts raster vs vector: https://concepts.app/en/digital-art-raster-vs-vector/ ; brushes: https://concepts.app/en/android/manual/brushesandtools
- Clip Studio vector layers: https://tips.clip-studio.com/en-us/articles/7586 ; https://tips.clip-studio.com/en-us/articles/532
- Heavypaint on Google Play: https://play.google.com/store/apps/details?id=com.HEAVYPOLY.HEAVYPAINT3
- Khronos OpenGL ES 3.2 announcement: https://www.khronos.org/news/press/khronos-expands-scope-of-3d-open-standard-ecosystem
- KHR_blend_equation_advanced: https://registry.khronos.org/OpenGL/extensions/KHR/KHR_blend_equation_advanced.txt
- EXT_shader_framebuffer_fetch: https://registry.khronos.org/OpenGL/extensions/EXT/EXT_shader_framebuffer_fetch.txt
- OpenRaster: https://www.openraster.org/ ; layout: https://www.openraster.org/baseline/file-layout-spec.html ; stack: https://www.openraster.org/baseline/layer-stack-spec.html
- ag-psd (MIT): https://github.com/Agamnentzar/ag-psd
- Molecular Matters psd_sdk (BSD-2): https://github.com/MolecularMatters/psd_sdk
- aseprite/psd (MIT): https://github.com/aseprite/psd
- PSD parsing resources: https://gist.github.com/ocornut/52a4b9c679ed670851e7
- pixelprobe (AOSP): https://android.googlesource.com/platform/tools/base/+/refs/heads/mirror-goog-studio-main/pixelprobe/
