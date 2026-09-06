# SPEC A — Rotation beyond one full turn

**Difficulty: LOW. Self-contained. Read `_RULES_READ_FIRST.md` first.**

## What JoyRaptor asked for, verbatim

> "Toward the object manipulation drawer, we should have the ability to have something rotate more
> than 360 degrees. Where you tap on the number, you can type in a number higher than 360, or type
> in negative numbers, or the ability to type in `16x` and it'll rotate sixteen times."

## The task

The rotation value in the object drawer is a tap-to-type control (the same "looks like text, is a
control" pattern used by the H/S/B numbers in `ui/faditor/tools/ColorPickerDialog.java` — read that
for the house style). Make it accept:

- any number outside 0–360 (`720`, `-45`, `1080`)
- a multiplier form: `16x` = sixteen full turns = 5760 degrees. Accept `16X` and `16 x` too.

Display the value the way it was entered where practical — keep showing `720°`, not `0°`.

## THE TRAP — this is the whole reason this spec exists

**370° and 10° are the same POSE but a completely different ANIMATION.** Rotation is keyframed. If
anything normalises the stored value into 0–360, a sixteen-turn spin silently collapses into a
fraction of one turn — and it will look CORRECT in a still frame, so it passes a casual check and
only fails when JoyRaptor plays it back.

Find and remove every normalisation on the path. Search for `% 360`, `Math.IEEEremainder`,
`while (deg > 180)`, `norm180`, `normalize`, and any clamp on rotation in at least:

- `keyframe/KeyframeSet` (the ROTATION track)
- `model/TextOverlayItem` rotation getters/setters and the `overlayTransform` round-trip
- `model/Clip` — `rotationDegrees`, and `spineRotationDeg` if present
- `project/ProjectStorage` — read AND write
- the export transform and the preview transform

The stored value must keep its winding. Report every normalisation you found and what you did with
it. Some of them may be legitimate (a *display* normalisation is fine; a *storage* one is not) —
say which is which rather than deleting them all.

## Acceptance criteria

1. Typing `720` stores 720, saves 720, reloads 720, and animating 0 → 720 turns twice.
2. Typing `-45` stores -45, not 315.
3. Typing `16x` stores 5760.
4. Garbage input (`abc`, empty, `x`, `1e9`, `--5`) is rejected without crashing, leaving the old value.
5. An existing project's rotation values are unchanged on load and re-save.
6. `./gradlew assembleDefaultDebug --console=plain` → BUILD SUCCESSFUL after your last edit.

## Deliver

The list of normalisations found, file:line, and what you did with each; the parse function and what
it rejects; the build verdict; and whether it is compile-verified or device-verified.

---

## Delivery record (SPEC A lane, 2026-09-05)

### What was built
- `KeyframeSet.parseRotationInput(String)` + `plainSignedDecimal` (KeyframeSet.java:108-147):
  optional sign, digits, at most one dot, optional trailing `x`/`X` multiplier (×360).
  Rejects exponent form ("1e9"), double signs ("--5", "+-5"), a second dot, bare "x",
  empty/whitespace, "720°", "1,5" (dot-only by design), and digit overflow (refuses
  Infinity instead of storing it; the multiplied value is guarded too). Null → the caller
  keeps the old value (ColorPickerDialog house rule).
- `PipDrawerTabs.promptForValue` rotation branch (PipDrawerTabs.java:381-457): rotation
  rows (prop key == `KeyframeSet.ROTATION`) skip the unit probe and the [-180,180] clamp,
  use a text keyboard (types digits AND "x"), hint "720, -45, 16x", and keep the old
  value on garbage instead of snapping to zero.
- Storage un-normalised: `TextOverlayItem.setRotationDeg` no longer folds `% 360`
  (TextOverlayItem.java:655; non-finite → 0 exactly like `Clip.setSpineRotationDeg`).
  PiP `overlayTransform` write/read goes through `KeyframeCodec` raw
  (ProjectStorage.java:1520-1522 save / 1877-1882 load); text/sprite/waveform/spine
  rotation read+write raw everywhere.
- UI honest: all five degree formatters raw (`v -> Math.round(v) + "°"` —
  FaditorEditorActivity 25840, 29948, 25970, 27520, 28700); all four rotation getters
  raw (25605, 25980, 28715, 29821); the `normDeg` helper is deleted. Preview and export
  both sample raw (`OverlayVideoPreviewView`; `PipFrameOverlay.java:131`;
  `GlPipFrameOverlay.java:221`; `LayerImageOverlayView` 178/269;
  `CompositeExportOverlay:1029`; `TextFxGlEffect:131`) — this also REMOVES a latent
  divergence: the text-FX export used to read the folded static getter while the preview
  sampled raw keyframes.
- Legitimate normalisations LEFT ALONE (metadata/display, not object storage): decoder
  orientation (ExportManager `sourceDisplayDims`:4335, `displaySize`:8732,
  `SurfaceFrameReader:152`, `SequentialFrameReader:216/452`, `FilmstripSweepExtractor:500`,
  `FxPreviewTextureView:1299`); quarter-turn clip orientation (`Clip.java:1115-1118`);
  the rotate-90 button (FaditorEditorActivity:7567); gesture cardinal snap
  (`PreviewHandlesOverlay:718`); `TextOverlayItem` shadowAngleDeg:230 (shadow angle,
  a different property).

### Adversarial review (second pass over my own build, same day)
Re-verified against the CURRENT tree after other lanes' saves; harness re-run ALL GREEN.

- **F1 — decision requested from JoyRaptor.** All four rotation SLIDERS stay bounded
  [-180, 180] (text 25858, PiP 27695, image 29978, sprite equivalent) while storage now
  holds raw winding. Typing 720 works and shows honestly (field seeds "720", readout
  "720°"), but the slider thumb pins at 180 — and a later slider TOUCH writes an
  in-window value, silently destroying the typed winding. That state could not exist
  before SPEC A (the setter folded), so this is a NEW interaction hazard SPEC A
  introduces. Options: (a) accept + document (a slider is in-window by nature; typing is
  the unclamped door — KeyframeSet.java:97 already says exactly that), (b) raise the
  slider bounds, (c) rotation rows keep the raw readout but lose slider write-through.
  Not changed without JoyRaptor's call.
- **F2 — accepted:** no magnitude cap. "999999999x" stores 3.6e11°; the render is
  garbage at that extreme but nothing can crash or hang (the guards refuse
  Infinity/NaN), and export duration is unaffected. The spec asked for no cap.
- **F3 — accepted:** dot-only decimals. A comma-locale "2,5" is rejected → keep-old,
  silently, like any other garbage. Consistent with `leadingNumber` on every other row.
- **F4 — cross-lane, informational:** the new SPEC B/E `SpineTransform` uses its own
  key "spineRotation" (SpineTransform.java:60). If a spine-rotation row ever routes
  through `promptForValue` it takes the NUMERIC branch (unit probe + clamp) — no "16x"
  grammar there. Flag for that lane if JoyRaptor wants turns on spine rotation too.
- **F5 — adjacent, by design:** engaging corner pin removes the ROTATION track and
  zeroes static rotation (CornerPinTransformHost.java:235-236) — corner pin replaces
  free rotation, so a typed 720 is destroyed by engaging it. Documented contract of that
  feature, not a SPEC A bug.
- **F6 — verified safe:** keyframe diamond-drop and gesture writes were already raw;
  undo snapshots (beginGesture copy 25620-25624, sprite `snapshotTransform`) restore
  raw values. `trimNumber` is pure formatting (no fold). `KeyframeCodec` greps clean.

### Verdict
- `assembleDefaultDebug` → **BUILD SUCCESSFUL** (fresh compile; `javap` confirmed
  `parseRotationInput`/`setRotationDeg` in the artefacts; the installed APK's dex
  contains them).
- `tools/jvm-harness/run-rotation.sh` → **ALL GREEN** (grammar cases), re-run against
  the current tree after other lanes' saves.
- **Compile-verified + grammar-harness-verified. NOT device-verified.**
- Device attempt (Note 9, 2026-09-05) — blocked by a PRE-EXISTING app bug, not SPEC A:
  the "Opening project…" overlay (FaditorEditorActivity.java:484 — waits for the first
  STATE_READY) never dismisses when the project has a missing media source; it is modal
  (BACK does not dismiss it) and its progress bar is decorative/cyclic. The
  FADE_8B_VERIFY fixture points its clip at a dead MediaStore uri (video:123774) and its
  two IMG overlays at a possibly-dead image:127378. Safety: backup taken before install
  (projects_backup_20260905_105508.tar, VERIFIED); fresh APK installed (lastUpdateTime
  2026-09-05 10:55). To finish device-verification: repoint the fixture's clip to an
  existing file (e.g. `file:///data/user/0/com.fadcam.beta/files/images/faditor_gap_black.png`
  with imageClip=true), reopen, then run the typing checks (720 → readout 720° → autosave
  → project.json `rotationDeg=720`; 16x → 5760; -45; abc → unchanged; reopen → values
  survive).
- Note: this lane's earlier verdict section in `tasks/todo.md` was overwritten by
  another lane's rewrite of that file; this spec file is the durable record.
