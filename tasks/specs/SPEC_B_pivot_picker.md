# SPEC B — Pivot picker in the image/video animation drawer

**Difficulty: LOW-MEDIUM. Self-contained UI plus one model field and a rendering change.**
**Read `_RULES_READ_FIRST.md` first.**

## What JoyRaptor asked for, verbatim

> "In the image and video drawer for animating and manipulating: currently CLEAR ALL KEYFRAMES is on
> its own line. It should easily fit in the line after the fit/fill animation — at the END of the
> row. And right before the end of the row should be a PIVOT button. When you tap it, it gives a
> very tiny little picture of a 3x3 dots, and you press on a dot to set the axis of rotation from
> either center, any of the four edges, or any of the four corners... The pivot is NOT animated. If
> you need to animate propeller blades, default works. But if you need to animate something else,
> like perhaps a broom, then you can put the pivot to a corner. And the icon for that will show
> which of the nine is selected — by default nine grey boxes and a centre white box, but if you set
> it to the bottom right, the icon updates to show that corner is where the pivot actually is."

## The task

1. **Layout.** Move "Clear all keyframes" off its own line onto the end of the row that follows the
   fit/fill animation controls. Immediately BEFORE it, add a Pivot button.

2. **The Pivot button's icon** is a miniature 3x3 of dots showing the CURRENT selection — grey dots
   with the selected one filled/white — so it is readable at a glance without opening anything.
   Default = centre.

3. **Tapping it** opens a small 3x3 dot picker. Tapping a dot sets the rotation pivot to one of the
   nine anchors (centre, four edge midpoints, four corners). It closes on pick.

4. **Model.** A static per-object pivot — an enum, or two normalised floats in 0..1 if you prefer
   room to grow. Persisted in `project/ProjectStorage`, TOLERANT on read (absent = centre), and an
   object at centre must re-save byte-identically so JoyRaptor's ~19 existing projects do not churn.

5. **Rendering.** Rotation must actually happen about that pivot in the PREVIEW *and* in the EXPORT,
   from one shared definition. This is the parity-critical half of this spec and the part most
   likely to go wrong — see the rules file.

## Already decided — do not relitigate

- **The pivot is NOT keyframable.** This is deliberate. Interpolating a moving pivot produces
  swooping arcs nobody authored and makes keyframes order-dependent. It is a static property of the
  object. (A gesture-time pivot — e.g. pinch about your fingers — is a separate idea and must be
  baked into position/scale/rotation on release, never stored.)
- Touch targets >= 44dp.
- Match the app's existing popover styling. Read `ui/faditor/tools/EasePickerPopover.java` and
  `ui/faditor/tools/BlendPickerPopover.java` and follow them rather than inventing a new look.

## Acceptance criteria

1. The 3x3 icon reflects the current pivot without opening the picker.
2. Setting the pivot to a corner and rotating turns about that corner in BOTH preview and export.
   State explicitly how you guaranteed both surfaces use the same definition.
3. An object left at centre behaves exactly as today, and its JSON is byte-identical.
4. One pick = one undo step.
5. `./gradlew assembleDefaultDebug --console=plain` → BUILD SUCCESSFUL after your last edit.

## Deliver

Where the pivot is applied in each renderer and the shared method that keeps them honest; the
no-op proof for centre; the build verdict; compile-verified vs device-verified.

## Plan (2026-09-05)

- [x] Model `TextOverlayItem`: two snapped floats `rotationPivotX/Y` in 0..1 (default 0.5/0.5;
      setter snaps each axis to {0, 0.5, 1}, guards non-finite). NOT keyframable — plain fields,
      no track, no TransformSnapshot member. THE SHARED DEFINITION lives here:
      `pivotOffsetFromCentreX/Y(pictureWidthPx)` = (pivot−0.5)·size — every renderer calls this,
      none re-derives it.
- [x] Storage `ProjectStorage`: sparse write next to `scaleLinked` — `pivotX`/`pivotY` written
      ONLY when the pivot is off centre ⇒ centre objects stay byte-identical. Tolerant read
      (absent = centre), inside the per-overlay try/catch.
- [x] Export: `ImageOverlayDraw.draw` — rotate AND entrance-scale anchors move from (cx,cy) to
      (cx+offset, cy+offset). This ONE site covers every export route: CompositeExportOverlay's
      canvas path and the blend/fx/chroma GL path (ImageBlendGlEffect → ImageOverlayFrameOverlay
      → ImageOverlayDraw) both render through it.
- [x] Preview View: `TextOverlayLayer.position` — for images, `setPivotX/Y` = picture centre +
      shared offset (boxInset-aware so a corner-pinned item pivots on the PICTURE). Centre-pivot
      writes w/2,h/2 = the Android default ⇒ no churn. While the finger is down (live) the
      default pivot stays, because the twist gesture bakes centre-rotation into the pose —
      applying a stored pivot under the finger would double-rotate.
- [x] Preview GL: `TextOverlayLayer.fxPipFor` — same definition folded into the Pip centre via
      scale-about-pivot then rotate-about-pivot (algebra reduces EXACTLY to today's expressions
      at 0.5/0.5). Pip format unchanged.
- [x] UI: `tools/PivotNineView` (new) — 3×3 dot grid, selected dot bright; used twice: as the
      button's icon (tiny, non-interactive) and inside `tools/PivotPickerPopover` (new) —
      popover styled after BlendPickerPopover, ≥44dp cells, closes on pick.
- [x] Drawer row: on the row that follows the fit/fill animation row (where "Clear all
      keyframes" already sits after the 2026-08-11 layout change), insert the Pivot button
      immediately before "Clear all keyframes" at the row's end.
- [x] Undo: one pick = one `EditActions.LambdaAction` ("Pivot").
- [x] Build `./gradlew assembleDefaultDebug --console=plain`; javap-verify new symbols.

## Delivery record (2026-09-05)

**Layout.** The row in question is the small row directly under the fit/fill animation row —
the 2026-08-11 layout change had already moved "Clear all keyframes" there (right-aligned), so
the remaining work was the Pivot button: a 44dp `PivotNineView` inserted immediately BEFORE
"Clear all keyframes" on that row (`buildImageTransformTab`, FaditorEditorActivity). The
button's icon is the live state — the same 3×3 the picker offers, selected dot white, others
grey — so acceptance 1 holds without opening anything. Default = centre white.

**Picker.** `tools/PivotPickerPopover` — sheet styled after `BlendPickerPopover` (same
`SHEET_BG` 0xFF1C1C1E, 16dp corners, 16dp elevation, above-the-anchor placement), title
"Rotation pivot", a 3×3 grid of 44dp cells (`PivotNineView` in picker mode), closes on pick.

**Model & storage.** Two snapped floats on `TextOverlayItem` (`rotationPivotX/Y`, default
0.5/0.5). `setRotationPivot` snaps each axis to {0, 0.5, 1} and guards non-finite input.
`ProjectStorage` writes `pivotX`/`pivotY` ONLY when `!isRotationPivotCentre()` (beside
`scaleLinked`), and reads them tolerantly (both must be present; absent = centre) inside the
per-overlay try/catch.

**Where the pivot is applied — one definition, three readers.** The shared truth is
`TextOverlayItem.pivotOffsetFromCentreX/Y(pictureSizePx)` = (pivot−0.5)·size; no renderer
restates the arithmetic:
1. **Preview View** — `TextOverlayLayer.position`: for images, `setPivotX/Y` = picture centre +
   shared offset, measured on the PICTURE box (view size minus the symmetric corner-pin inset),
   matching the fractions' unit. Centre pivot writes w/2,h/2 — the View's own default.
2. **Preview GL** — `TextOverlayLayer.fxPipFor` (images carrying fx/key/blend/mask): the pivot
   is folded into the Pip centre — preset scale about the pivot, then rotation about the pivot,
   then the preset translation — the exact composition the export's canvas spells
   translate→rotate(P)→scale(P). Pip format untouched.
3. **Export** — `ImageOverlayDraw.draw`: `canvas.rotate(rot, pvx, pvy)` and the entrance scale
   anchored at (cx+offset, cy+offset). This ONE site covers every export route:
   CompositeExportOverlay's canvas path AND the blend/fx/chroma GL path both render through
   ImageOverlayDraw (ImageBlendGlEffect → ImageOverlayFrameOverlay → ImageOverlayDraw).
   How both surfaces stay honest: all three read the same model method, and the GL fold was
   derived from the canvas matrix algebra (T·T(P)·R·T(−P)·T(P)·S·T(−P)), not transcribed by eye.

**Gesture note (deliberate).** While the finger is down (live), both preview paths keep the
centre pivot: the twist gesture bakes a centre-rotation into the pose, and applying a stored
pivot under the finger would rotate it a second time. Playback (and the static pose while not
manipulating) uses the stored pivot. The gesture-time pivot stays baked-into-pose per the
spec's "do not relitigate".

**No-op proof for centre.** (a) Storage: the sparse write emits nothing, so an object at
centre serializes byte-identically — same argument as `scaleLinked`'s. (b) View path: offset 0
→ pivot w/2,h/2 = the Android default. (c) GL path: the fold block is skipped entirely when
`isRotationPivotCentre()`, leaving the shipped expressions untouched. (d) Export: offsets are
0, anchors land on (cx, cy) — the identical matrix. Keyframes and TransformSnapshot untouched
(the pivot is not keyframable and rides no snapshot).

**Undo.** One pick = one `EditActions.LambdaAction("Pivot", redo, undo)`; the button icon
refreshes in the same callback.

**Verdict.** `./gradlew assembleDefaultDebug --console=plain` → BUILD SUCCESSFUL in 50s with
`compileDefaultDebugJavaWithJavac` freshly executed (not UP-TO-DATE), and `javap` on the
javac intermediates confirms the new classes (`PivotNineView`, `PivotPickerPopover`) and the
new model methods are in the artefacts. **Compile-verified. NOT device-verified** — no phone
attached. Owed on device: pick a corner pivot and spin a propeller-like image in the preview,
then export and confirm the corner rotation in the file; confirm a centre-pivot project
re-saves byte-identically; confirm the popover opens above the button on a phone-height
screen.

## Adversarial review (2026-09-05, second pass over my own build)

**BUG FOUND AND FIXED — duplicates dropped the pivot.** `TextOverlayItem.copyWithNewId`
carried scaleX/scaleLinked/cornerPin but not the pivot, so duplicating a corner-pivoted
propeller blade silently reset it to centre. Fixed: both fields ride along.

**BUG FOUND AND FIXED — the View-path write broke the centre no-op in one edge.** My first
version called `setPivotX(w/2)` on every tick for images. The View's DEFAULT pivot tracks its
actual laid-out size; my computed w/2 is this tick's TARGET size, so on a tick where the size
changes (Ken Burns zoom on a rotated image) the anchor disagreed with the drawn geometry for
one frame — a behaviour change for centre-pivot objects, which criterion 3 forbids. Now the
centre pivot writes NOTHING (the View default survives untouched), and a keyed view tag
(`faditor_tag_pivot_custom`, ids.xml) marks views that ever had a custom pivot so they are
restored to their ACTUAL laid-out centre when the pivot returns to centre or a gesture starts
— fixing recycled-view staleness the same stroke.

**BUG FOUND AND FIXED — the picker inferred the dot from touch coordinates.** The first draft
made PivotNineView both icon and picker, reading `lastX/lastY` inside `performClick`. An
accessibility click arrives with no preceding touch, so the "last" coordinates are stale and
it would pick the top-left dot. Restructured: PivotNineView is now a pure icon (no touch
code), and the popover is nine real 44dp `DotView` cells, each with its own click listener —
the same shape BlendPickerPopover uses for its rows.

**Checked, correctly untouched:** `FxLivePreviewController` ~1117 is CAPTION quads (rotation
hard 0); `LayerImageOverlayView` is the pilot placeholder raster for the TimedItem/KeyframeSet
model, not TextOverlayItem; the export text/sprite paths keep centre rotation (pivot is
image-only by design); sprite overlays have their own pivot feature; the gesture hosts'
`readPivot` stays centre because gestures bake (spec's "do not relitigate"). Keyed view tags
coexist with the layer's unkeyed `setTag(item)`.

**Accepted, stated for the record:** undoing a pick restores the model but the drawer's icon
only refreshes when the drawer rebuilds (the same staleness class every row's refresher has);
a hand-edited non-anchor `pivotX` in JSON snaps to the nearest anchor on load (the picker can
only produce anchors, and the setter documents this); a custom pivot can be one frame imprecise
while the object is simultaneously resizing — inherent to any target-size pivot, and the centre
pivot has NO such fuzz since it never leaves the View default.

**Rework build:** two compile errors on the way (R not imported in TextOverlayLayer →
fully-qualified `com.fadcam.R`; loop variables not effectively final in the cell lambda →
final copies), both fixed; then `./gradlew assembleDefaultDebug --console=plain` → BUILD
SUCCESSFUL. Still compile-verified, not device-verified.

## Device push (2026-09-05, Note 9 `SANDBOX_SERIAL`)

`adb install -r app-default-arm64-v8a-debug.apk` → **Success** (`-r` replaces while KEEPING
app data — JoyRaptor's projects untouched; no uninstall was run). App launched via its launcher
(`SplashActivity`), recovered to the editor with `FaditorEditorActivity` in the foreground,
process alive, zero AndroidRuntime fatals in logcat. **Install-verified + launch-verified on
the Note 9.** The three owed checks remain hands-on: corner-pivot spin in the drawer's preview,
the same rotation seen in an exported file, and a centre-pivot project re-saving
byte-identically.

## Device session 1 (2026-09-05, JoyRaptor hands-on) — pivot render ✓, handles ✗, row ✗

**Pivot render: WORKS** (JoyRaptor: "pivot looks good. works well."). Three problems reported:

**1. FIXED — the handles box didn't fold the pivot.** `PreviewHandlesOverlay` draws the
selection frame from the view's layout bounds, rotated about the box's own CENTRE; the
picture now renders rotated about the pivot — so at a left-edge pivot the picture rode ~8%
above the box, right-edge ~8% below, a corner pivot both at once ("8% high and 30% to the
right"). Fix: `TextOverlayLayer.foldRotationPivotIntoBox(o, rect)` — the SAME pivot
composition the render makes (pivot point from the shared offsets, rotate the box centre
about it), gated exactly like the render (nothing at centre, nothing under the finger), and
applied in `textHandlesTarget.frame()` through whichever layer owns the view. Hit-testing
inherited the fix (frame() is also the tap test).

**2. FIXED — the handles went stale when adjusting values in the drawer.** The handles
overlay only repainted on a playhead tick or a touch; a drawer slider write refreshed the
object layer but never the box, so "the two reportings split until I touch it again or move
the playhead" (JoyRaptor's centre test — SOMETIMES aligned because the playhead happened to
tick). Fix: `refreshOverlayPreview()` now invalidates the handles overlay whenever a target
is set — the funnel every drawer write already goes through.

**3. FIXED (clock) — the box drew at the raw playhead.** It ticked on `absoluteMs` while
every overlay surface renders at the `overlayClockMs`-corrected value; fed it the corrected
clock (same tick, moved below the correction) so the box cannot read a different rotation
than the picture past the last clip.

**4. FIXED — the row.** JoyRaptor: Pivot + "Clear all keyframes" belong ON the fit/fill/animation
row, right after the animation button — his original spec wording won over the 2026-08-11
own-row arrangement. Also renamed to **"Clear all ◇"** on a red rounded chip (same pill
shape as Fit/Fill, `0x33E57373` bg, `0xFFFF8A80` text). The own row is deleted.

**Centre "a bit off vertically"**: most plausibly symptom #2 (staleness made it look
randomly right). The render itself is untouched at centre (no pivot write, View default
preserved — adversarial-review hardening holds), the content rects are the same
`computeCanvasRect()` on both surfaces, and the box now ticks on the render clock. Re-check
on device; if a real offset remains it is PRE-EXISTING handles-box geometry, not SPEC B.

Rebuilt (`assembleDefaultDebug` BUILD SUCCESSFUL) and reinstalled on the Note 9 (`adb
install -r` → Success). Still compile-verified + install-verified; the visual re-check is
JoyRaptor's.

## Device session 2 (2026-09-05) — the fold was on the WRONG SURFACE

**Root cause found, and it reframes session 1's fixes.** A SELECTED IMAGE is not drawn by
`PreviewHandlesOverlay` at all: the ordinary handles SURRENDER images to
`TransformOverlayView` + `CornerPinTransformHost` ("now that [transform] is simply what a
selected image looks like"). Session 1's frame() fold was dead code for images — and
simultaneously load-bearing, because the transform host reads its box THROUGH that frame():
the host's `readQuad` was already presenting the exact folded quad (algebra checked: corner =
C′ + R(θ)(corner − C′) matches the render to the float). What was missing:

**1. FIXED — `writeQuad` wrote pin offsets in the FOLDED frame.** The dragged quad arrives
presented (folded); the conversion derived offsets against the folded box and stored them,
but the render applies pin offsets in the POSE frame — so every fold-drag polluted the
offsets, which is what JoyRaptor saw after the flip helpers ("image too far south... too far
right"): the flip permuted already-polluted offsets. Now the dragged corners AND the
reference box are un-folded to the pose frame first (P = presented centre + R(θ)·δ, the
model's shared pivot arithmetic), so the stored offsets stay pose-frame and round-trip.

**2. FIXED — `writeSimilarity` wrote the presented centre as the pose centre.** The pinch's
ncx/ncy is where the FOLDED centre lands under the fingers; writing it as the pose centre
double-displaces on the next fold (the "drift then jump" a pinch on a pivoted image would
make). Now un-folded: C = ncx − (I − R(θ′))·δ′ at the scaled size.

**3. FIXED — drawer writes never refreshed the transform surface.** `refreshOverlayPreview`
invalidated the handles overlay — which for images has NO target (surrendered) — so the
quad sat at its last sync until a playhead tick. That is the entire "stale until I touch it
or move the playhead" report, including the SOMETIMES-spot-on (right after a tick, the
re-synced quad is exact). Now `refreshOverlayPreview` also calls `transformOverlay.refresh()`
when the surface is open — covering Rotate/Pos/Scale slider writes AND pivot picks.

**Touch interaction**: during transform gestures the layer never sees the touch (the
transform overlay consumes it), so the render always used the stored pivot while the quad
was centre-based — "off even in the touch interaction". With readQuad already folding and
the writes now un-folding, gesture and render agree at every pivot.

**Seen with my own eyes**: screencapped the Note 9 mid-state — the picture clearly rides
outside the handle quad. (Method: `adb shell screencap` + `adb pull`; PowerShell `>`
redirection corrupts the binary PNG — noted in lessons.md.)

Rebuilt → BUILD SUCCESSFUL, reinstalled → Success. JoyRaptor re-tests: rotate −278.4°, watch the
quad land ON the picture immediately after each drawer write, pivot to each of the nine, and
re-try the flip helpers at a corner pivot.

## Device session 3 (2026-09-05) — three distinct defects, all pinned

JoyRaptor's report decoded into: (a) centre pivot rotating BY HAND is fine, but in the DRAWER the
helper is "completely stale until I close the drawer"; (b) merely OPENING the pivot popover
shifts the image a couple of px; (c) pivot to left/right made the image JUMP a full inch
("what if I wanted the image right there?") and then rotation looked like it turned about the
OPPOSITE edge; (d) outside the drawer, rotate-handle drag: "the helper making a 360 on itself
whereas the image is making a 360 plus moving in a circle", most divergent at ~100–150°.

**1. FIXED (a) — drawer writes funnel through `setTextOverlayPlayhead`, not
`refreshOverlayPreview`.** `overlayMenuProp`'s setter calls `setTextOverlayPlayhead(...)` +
`syncTimelineOverlays()`; my session-2 refresh hooked the wrong funnel, so slider writes
repositioned the picture and never re-synced the quad. `setTextOverlayPlayhead` now refreshes
both handle surfaces (handles `setPlayheadMs` at the corrected clock + `transformOverlay.refresh()`
gated exactly like the tick). Idempotent; the tick path unchanged.

**2. FIXED (c) — changing the pivot MOVES the picture no more.** The pivot is where rotation
HAPPENS, not where the object is anchored. `setOverlayRotationPivot` now compensates the
stored centre by the exact displacement the change would otherwise swing: ΔC =
(I − R(θ))·(δold − δnew), θ and (w,h) read at the current playhead (the presented rect's
dimensions = the pose box's — a fold preserves them). The channel shift mirrors the drawer
sliders' branch order (preset-owned keys shift + zoom focal rides, armed drops shifted keys
at the playhead, else the static pose). The "rotates about the opposite edge" reading was the
uncompensated jump plus the still-frozen quad; with the picture staying put, left pivot turns
about the left edge, right about the right.

**3. UNDO — one step, complete.** `TransformSnapshot` now carries `rotationPivotX/Y`
(ctor/matches/restore), so the pivot pick records `recordOverlayMenuUndo` with the pivot AND
the compensating centre in both directions. Every other snapshot-based undo is unchanged
unless the pivot actually differs.

**4. FIXED (d) — the rotate-handle live preview orbited the wrong point.** `applyDrag`'s
ROTATE case spun the local quad about `readPivot` (the presented centre — spins in place)
while the render orbits the stored pivot. New `Host.readFoldPivot` returns the true pivot
(presented centre + R(θ)·δ; spine host: centre — no pivot feature there), and the preview
rotates the grab quad about it — algebra: P + R(Δ)(P + R(θ)(b−P) − P) = P + R(θ+Δ)(b−P), the
exact presented pose at the new angle, so handles and picture agree at every degree of the
drag, not just at the start.

**Unresolved, watching: (b)** — opening the pivot popover performs NO model writes (verified:
the click only shows the PopupWindow), so the couple-of-px shift JoyRaptor saw is most plausibly
the tail of (a)'s staleness (a tick landing between states) or a focus/inset wobble. If it
survives this build, leave it on screen — screencap and measure.

Rebuilt → BUILD SUCCESSFUL (fresh compile; `readFoldPivot` confirmed in the javac artefacts
via javap), reinstalled → Success. Compile-verified + install-verified; interaction re-check
is JoyRaptor's.

## Device session 4 (2026-09-05) — the pin offsets were being written in the wrong frame

**The win**: "no staleness between the helper and the drawer" — session 3's refresh funnel
holds. What remained was worse, and its root explains the "random" pivots:

**THE BUG — every fold-space corner drag stored a WARPED pin offset.** Two stacked errors
across my own attempts: (1) the pre-session-3 conversion measured the dragged corners
against the FOLDED box, which stores a pose-dependent shear —
offset_err = (I − R(θ))·δ / (w,h) — so any drag while a pivot was set silently polluted the
eight CornerPin tracks; (2) the session-3 rewrite un-folded the dragged corners to the pose
frame and then let the ORIGINAL conversion un-rotate them AGAIN — the frame conversion
applied twice ("it looks like it is rotating the image 90 degrees as it resizes, and then
the bounding box snaps" — with θ ≈ 90° the double application is maximal). With the pin
tracks polluted, every pivot change swung a warped picture: "left pivot → rotation point too
low", "right pivot → turns about the left", "bottom-centre → pivoting from bottom-left-ish"
— the pivot mapping itself was never wrong (col→X, row→Y audited end to end); the PICTURE it
was measured against was.

**THE FIX — one transformation, not two.** `writeQuad` now un-folds each dragged corner
about the pivot by −θ (at the centre pivot P is the box centre, so this is exactly the plain
un-rotation the conversion always did) and measures against the POSE box corners. Proof of
round trip: stored offset o puts the presented corner at P + R(θ)(u − P) = the dragged point
d, exactly. Scale/tilt/free/flip drags at any pivot now depeg nothing and snap nothing — the
helper hugs the bounding edges continually, as JoyRaptor demanded.

**Recovered state note**: pictures whose pin tracks were polluted by the earlier builds stay
polluted — the fix stops NEW pollution but cannot know the old offsets were unintended.
JoyRaptor's test image should be re-created (or its corner-pin reset via the transform surface's
reset) before judging pivot behaviour.

Rebuilt → BUILD SUCCESSFUL; reinstalled → Success. Still compile-verified +
install-verified; JoyRaptor re-tests with a CLEAN image: all nine pivots, drawer rotate at each,
helper scale at a non-centre pivot, and the flip helpers.

## Device session 5 (2026-09-05) — measured on-device; the picker was innocent

**Ground truth pulled from the live project** (`adb run-as` → project.json): the test image
stored `pivotX=0.5, pivotY=1.0` (bottom-centre, EXACTLY as picked), `rotationDeg=368.18`.
The on-screen quad measured ≈8.3° — matching 368.18 mod 360 — and the full-res crop of the
Pivot button shows the bottom-centre dot white. **The picker, the icon, the storage and the
render all agree.** What did NOT agree was the helper's box: several px up/right, slightly
small — JoyRaptor's numbers.

**ROOT CAUSE — the rebuild/position race.** `refreshOverlayPreview` runs
`overlayLayer.setData(...)` — a REBUILD, which positions the rebuilt views from a POSTED
callback (the codebase's own note) — and then re-synced the handle surfaces SYNCHRONOUSLY.
The quad therefore measured the OLD layout box, the picture landed on the new pose a frame
later, and with playback PAUSED no playhead tick ever healed the gap: the helper stayed
stale indefinitely, at whatever the pose was when the write happened — which also explains
"offsets that don't match any clean pivot" (the stale box belonged to an earlier pose).

**FIX** — `refreshOverlayPreview` now runs `setTextOverlayPlayhead(lastPlayheadAbsoluteMs)`
right after `setData` (the same synchronous position() re-run the slider writes use), so the
layout box is current before the handles/transform re-sync. Idempotent; the tick path is
unchanged.

Rebuilt → BUILD SUCCESSFUL; reinstalled → Success. Compile-verified + install-verified;
JoyRaptor re-tests all nine pivots on the clean image.

## Device session 6 (2026-09-05) — measured again; two interactions fixed, one limit explained

Ground truth this session: `pivot (0,0)` (top-left), `rotation −373.17°`, **pose centre
(−0.23, 0.65) — off-canvas by design** (the pick's compensation parks the pose centre away
from the picture). That fact explains two of the three reports:

**1. FIXED — the travel clamp fought the pivot.** `setCenterTravelLimit` was half the
object's size beyond each edge — computed for the VISUAL box, but with a pivot the POSE
centre legitimately lives far from it, so every centre write (pinch, drag, the pick's own
compensation) ran into the clamp and truncated: JoyRaptor's "snapping or locking, maxing out
something". `position()` now widens the limit by |(I−R(θ))·δ| per axis, and the pivot pick
pre-widens the clamp with the NEW pivot's displacement BEFORE shifting the centre (the
old limit would otherwise truncate the very write that follows it). The clamp's floor of
half a frame is untouched; the visual grammar ("push it until just fully off-frame") is
preserved — the clamp still guards the picture, not the compensated bookkeeping point.

**2. FIXED — the first tap-drag on an unselected image was a dead drag.** The tap selects,
the selection hands the object to the transform surface, and the handles overlay consumed
the remaining stream without a target — the picture followed only from the second touch
on ("stuttery and doesn't quite go with my finger"). New `SelectionSource.handoffGesture`:
the DOWN that made the selection plus every MOVE/UP are dispatched to the transform
overlay, whose DOWN handler starts a BODY drag at the finger — one continuous motion, one
commit, the same grammar as dragging an already-selected image.

**3. EXPLAINED, not changed — the corner-scale wall.** Corner handles scale/tilt/stretch
through the EIGHT CornerPin tracks, which are serialisation-capped at ±2.0
(`CornerPin.MAX_OFFSET`) — a pre-existing reach of roughly 3× per grab, refused (not
clamped) beyond that, by design: "REFUSE, DO NOT CLAMP" so the picture can never silently
stop following the finger. The unlimited scaler is the two-finger pinch (writes
`sizeFraction`). If JoyRaptor wants corner-drag to grow the true SIZE once the pin saturates,
that is a new grammar decision for him — not smuggled in here.

**Still open, watching: the couple-of-px nudge when the pivot POPOVER opens.** The popover
provably writes nothing; the resting state now measures aligned (quad angle 8.3° vs 368.18
mod 360; stored pivot exactly as picked). If the nudge survives this build, the next step is
a screencap taken DURING the nudge.

### Session 6, part 2 — THE PIVOT WAS ANCHORING TO THE WRONG PICTURE (measured on device)

JoyRaptor's follow-up ("every selector dot pivots from somewhere else — centre reads top-right,
bottom-right reads top-right, top-left 'way higher up'") was measured against a fresh
screencap + the saved model. Ground truth: the test picture carries **corner pins** —
TL.dx −1.26, BR.dy +1.85 (in picture fractions) — the residue of his earlier corner-stretch
drags, already near the ±2 saturation wall. The pinned picture the user SEES is therefore a
quad displaced ~(−1.76·W, +1.31·H) from the untouched box the pivot arithmetic anchored to:

- "Centre" pivoted at the BOX centre — right-and-above of the pinned quad's visual centre.
- "Bottom right" pivoted at the box's BR — which sits at the pinned quad's TOP-right.
- "Top left" pivoted at the box's TL — floating above the drawn picture entirely.

Every dot JoyRaptor named matched the model of "pivot on the box, picture displaced by the
pins". Two more findings from the same session, both fixed:

1. **The "legacy mover" was the overlay view's own gesture listener.** The layer attaches a
   move/pinch/hold listener to every overlay VIEW; its hit area is the box plus the corner-pin
   excursion margin (and the unrotated bounding square of a rotated picture). Whenever the
   handles overlay declined a gesture, this second mover grabbed it and moved the pose with
   none of the pivot/pin grammar: JoyRaptor's "zooming by some other legacy means, then it
   completely disjoints, and eventually it snaps back". Images now DECLINE touches on their
   own view — the transform surface is their only mover, a miss above stays a miss.

2. **A tap on a pinned picture did not select.** The preview hit-test used the frame (the
   box); the drawn picture is the pinned quad. The hit-test now inverse-rotates the point
   into the box frame (as before) and, for pinned items, tests point-in-quad against
   box-corner + pin offset — grabbing the drawn shape, not the box a picture-width away.

**The pivot is now defined on the pinned quad** — the picture the user sees. The ONE shared
arithmetic gained the pinned form: δ = quadCentre + (pivot − 0.5) · quadSpan, quad =
pose-frame bbox of box corners + pin offsets. Flat pins collapse to the old δ exactly, so
every unpinned project is byte-identical. Routed through every consumer — the View pivot,
the travel clamp, the frame fold, the GL Pip fold, the export anchors (ImageOverlayDraw),
the transform host's readFoldPivot/writeQuad/writeSimilarity, and the pivot pick's
compensation — so preview and export keep moving together, and the selector dots land on
the visual corners of a pinned picture in the editor AND in the file.

Rebuilt → BUILD SUCCESSFUL; reinstalled → Success. Compile-verified + install-verified.
JoyRaptor re-tests: pivot dots on the pinned picture, first tap-drag, outside-finger pinch.
