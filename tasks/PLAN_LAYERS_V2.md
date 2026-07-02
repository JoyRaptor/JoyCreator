# PLAN — Layers / Multi-Track (v2, the definitive execution plan)

**Written:** 2026-07-01 by the architecture reviewer, after reading the real model/storage/export/preview
code. **Supersedes** the Layers sections of `PLAN_asset_browser_and_layers_EXECUTION.md` (M5–M11),
`PLAN_asset_browser_v2_layers.md` (§L), and `road_map.md` Phase 5 (5.1–5.5). Those remain valid on
*intent*; where they disagree with this doc on schema numbers, sequencing, or the compositor approach,
**this doc wins**. The asset-browser milestones (M0–M4) in the old execution plan are unaffected — keep them.

**Mandate:** build the keystone multi-track system so Joy Creator can rival or surpass CapCut on phones and
tablets, with the Note 9 as the performance floor. **No code is written by the planning agent; this doc is
the only output.** Build agents follow it in order.

**Standing rules that bind every milestone here** (from `EVAL_20260701_joy_creator.md` §8):
always-green (revert if you can't compile; you cannot run Gradle — poll `build.log`); new features = new
files (`faditor/layers/`, `faditor/compositor/`), extract-on-touch for the god classes; every surface
scrolls; device-verify on the sandbox Note 9 (`SANDBOX_SERIAL`), never the user's real project; data ops
back up first; update `handoff.md` (≤8 lines) after each landed milestone; screenshots via
`screencap`+`adb pull`. The DESIGN doc §5 (frosted, resizable, collapsible panels; the timeline must stay
navigable; transcripts drawer is the gold standard) is BINDING for all Layers UI.

---

## PART 1 — VERDICT ON THE EXISTING PLAN

**Overall: AMEND, don't replace.** The old plan's *shape* is right — additive Track model with `getClips()`
shims, multi-row timeline, GL compositor split into image/text (easy) vs second-video (hard), export via
Media3 `Composition`, ripple/gap toggle. Reading the actual code, however, surfaced **five things that are
wrong or dangerously under-specified for the CapCut bar.** Each is fixed in Parts 2–6.

### 1.1 What still holds (keep verbatim)
- **Additive migration + `getClips()`/`getTextOverlays()`/`getAudioClips()` shims.** The serializer
  (`ProjectStorage.ProjectSerializer`) is hand-written and the deserializer is defensively additive
  (`.has()` guards everywhere), so new `masterTrack`/`layers` JSON blocks won't break old parsing. Correct.
- **M5 (schema/Track) is a regression gate, not a feature.** Confirmed and reinforced (Part 2, §2.1).
- **Split the compositor: image/text first (low risk), second-video last (high risk, flag it).** Confirmed
  — and it's even lower-risk than the old plan thought, because the live preview is already an Android-View
  overlay stack over one `PlayerView` (Part 3). The *only* genuinely hard piece is a second live video.
- **Export is the least-blocked layer.** Media3 supports multiple video sequences + `OverlayEffect`. The
  existing per-clip `CompositeExportOverlay` (a `BitmapOverlay`) already composites text/image/caption/
  waveform at absolute timeline time. Confirmed.
- **Floating items live at absolute time; master ripples.** Correct and matches `TextOverlayItem`
  (start/end + local-time keyframes) and `AudioClip` (`offsetMs`) as they exist today.

### 1.2 What's WRONG or under-specified (the five corrections)

**(A) The schema version is wrong in every doc. It is NOT v6 — it is already v7, and the migration must be
v8.** `FaditorProject.SCHEMA_VERSION = 7`. v6 was consumed by `project://` relative asset paths; v7 by
waveform overlays. Every Layers doc says "schema v6 Track model" — that number is taken. **The Track model
is schema v8.** More importantly (Part 4), the deserializer at `ProjectStorage.java:1071-1072`
*unconditionally stamps the current `SCHEMA_VERSION` onto every project it loads and there is no downgrade
guard*. So today, if a new build writes a v8 project with `layers`, and the user's OLD build opens it, the
old deserializer silently ignores the unknown `layers`/`masterTrack` keys, reads only `timeline.clips`, and
**re-saves it stamped as v7 — permanently dropping every layer.** This is a real data-loss trap the old
plans never mention. Fixed by the dual-write + guard scheme in Part 4.

**(B) `Clip` has no blend mode, no per-overlay-video transform, and `sourceUri` is `final`.** The CapCut bar
requires overlay video with transform + opacity + **blend mode**. Reading `Clip.java`: there is opacity
(keyframable), rotation (0/90/180/270 only — NOT free rotation), flip, crop, speed, per-clip volume — but
**no blendMode field and no free-transform (arbitrary scale/rotate/position) for a clip used as an
overlay.** `TextOverlayItem` *does* have free transform + opacity + full keyframes (X/Y/SCALE/ROTATION/
OPACITY via `KeyframeSet`) but no blend mode either. **Blend modes are not expressible via `BitmapOverlay`**
(only alpha, via `StaticOverlaySettings`) — they need a custom `GlEffect` shader pass on export. The old
plan hand-waved "arbitrary compositing likely needs a custom GL pass"; this doc specifies exactly where
(Part 5, blend-mode `GlEffect`) and defers blend to a clearly-marked later milestone so it doesn't block N
video tracks. The `TimedItem` in Part 2 carries the transform envelope so an overlay-video clip animates
like a text overlay does.

**(C) The compositor plan mis-frames the hard part and ignores what already exists.** The old plan treats
"GL preview compositor" as one monolith and implies a from-scratch GL surface mixing N decoders. Reality:
(1) the live preview is `PlayerView` + a **stack of Android `View` overlays** (`WaveformOverlayView`,
`TextOverlayLayer`, two `CaptionOverlayView`s, `GeneratedSlideView`, image preview) inside `player_container`
— image/text/sticker/caption/sprite layers over the master are *already* composited on the View layer at
absolute time (`TextOverlayLayer.setPlayheadMs`). So **M8a is mostly "model the existing stack as tracks,"
not new rendering.** (2) The project **already has a working custom `GlEffect` that reads a second video's
frames** (`GlTransitionExportEffect` → `GlTransitionShaderProgram` + `GlTransitionFrameOverlay`) for
transitions on *export*, and a `GLSurfaceView` (`GlTransitionPreviewView`) that composites two textures with
a shader for *preview*. The second-video-layer compositor should **reuse/generalize this existing GL
plumbing**, not invent it. Part 3 rewrites the compositor section around these facts and adds the missing
**decoder-budget/pooling strategy** the old plan never gave (critical for the Note 9 / Snapdragon 845).

**(D) The boundary-jump fix is orphaned from the compositor plan.** `DIAG_20260701_transition_preview.md`
proved the clip-boundary jump is the single-ExoPlayer *cold re-prepare* (no pre-buffering) and that no
low-risk additive fix exists within the single-player constraint — the real fix is Phase 5.3. But the old
Layers plan's compositor milestones (M8a/M8b) are about *overlays over the master*, and **neither one
actually replaces the master-clip playback engine that causes the jump.** You could ship all of M8a/M8b and
still have the boundary jump. Part 3 adds an explicit sub-milestone (**M-COMP-0: master playback engine**)
that fixes the boundary jump for *plain single-track projects* (via a multi-`MediaItem` / gapless master
player) as the *foundation* the layer compositor builds on — so the jump fix and the compositor are one
coherent effort, and the jump fix ships value even before any layer exists.

**(E) Undo, and the second god class, are unaddressed.** Two findings: (1) **Undo is snapshot-based** —
`UndoManager.SnapshotRestorer.captureSnapshot()` serializes the whole project to JSON via
`ProjectStorage.toJson`. This means **every new model field gets undo for free** *provided the serializer
round-trips it* — a big de-risk, but also a hard requirement: if you add `layers` to the model but forget
to serialize it, undo will silently erase layers on the first undo. This must be a checklist item on M5.
(2) `EditorTimelineView.java` is **4,881 lines** and `FaditorEditorActivity.java` is **14,204** — the
multi-row timeline (M6/M7/M10) will bloat the timeline view past maintainability. The plan mandates
extracting layer rendering/interaction into new `faditor/layers/` classes (a `LayerRowRenderer` +
`LayerGestureController` the timeline view delegates to), honoring extract-on-touch, rather than growing the
god view.

### 1.3 Direct answers to the brief's specific questions
- *Does schema v8 Track/TimedItem accommodate blend modes, speed, per-item audio?* Not as the model stands —
  `Clip` has speed + per-clip audio but **no blend**; `TextOverlayItem` has transform but no blend. Part 2
  adds `blendMode` (default `NORMAL`) and a transform envelope to `TimedItem`, and folds the sprite plan's
  `FrameTrack` expectation in. Per-item audio already exists on `Clip`/`AudioClip`.
- *Does the compositor plan handle N decoders on mid-range hardware — budget/pooling?* The old plan did NOT.
  Part 3 specifies a hard decoder budget (Note 9 / Snapdragon 845-class: **2 simultaneous hardware AVC
  decoders for 1080p preview** — one master + one overlay-video; images/text/slides/sprites are bitmap/
  still-frame, zero video decoders), a pooling strategy, and a graceful-degradation path (freeze-frame the
  overlay video, or cap to one overlay video track in preview) when the budget is exceeded.
- *Does export mapping cover transforms + blend via Media3 or need custom GlEffects?* Transforms + opacity +
  position: **yes via Media3** (`ScaleAndRotateTransformation` + `Presentation` + `BitmapOverlay` alpha,
  already used). Blend modes: **no — custom `GlEffect`**, and the codebase already has the pattern
  (`GlTransitionExportEffect`). Part 5 maps each case.

---

## PART 2 — SCHEMA v8: TRACK / TIMEDITEM MODEL

New package `com.fadcam.ui.faditor.layers`. Additive; no existing class is reshaped in M5. The model wraps
the existing flat lists so `getClips()` etc. keep working.

### 2.1 Model shape (folds in the sprite plan's `TimedItem` expectations)

```
Timeline (extended, additively) {
  // EXISTING flat lists stay as the storage of record in v7-and-earlier form until M5b;
  // from M5 on, the Track objects are the source of truth and the flat lists become views.
  rippleMode : "ripple" | "gap"            // master edit behavior (default "ripple")
  masterTrack : Track(kind = MASTER)       // the spine; gapless in ripple mode
  layers      : List<Track>                // floating video/image/text/sticker/sprite, above master, by zIndex
  audioTracks : List<Track(kind = AUDIO)>  // below master
}

Track {
  id : String (UUID)
  kind : MASTER | VIDEO | IMAGE | TEXT | STICKER | SPRITE | AUDIO
  name : String
  zIndex : int                             // paint order within its band (higher = on top)
  collapsed : bool                         // UI: thin summary strip vs full row
  hidden : bool                            // not previewed, not exported
  locked : bool                            // not editable (taps/dr- ignored)
  muted  : bool                            // audio tracks + video-with-audio
  items : List<TimedItem>
}

TimedItem {
  id : String
  timelineStartMs : long                   // absolute position on the timeline (master items: derived; floating: free)
  zHint : int                              // stable z within a track for multi-item overlap (sprite plan requirement)
  blendMode : NORMAL | MULTIPLY | SCREEN | OVERLAY | ADD | ...   // default NORMAL; only VIDEO/IMAGE/STICKER/SPRITE honor it
  // exactly ONE payload, reusing the EXISTING model classes unchanged:
  clip : Clip?                             // MASTER + VIDEO + IMAGE items (Clip already covers image via imageClip)
  textOverlay : TextOverlayItem?           // TEXT + STICKER items (TextOverlayItem already covers PNG via imageUri)
  audioClip : AudioClip?                   // AUDIO items
  sprite : SpriteOverlayItem?              // SPRITE items (from sprite plan; carries its own FrameTrack)
  // transform envelope for OVERLAY video/image (text/sprite already carry their own KeyframeSet):
  transform : KeyframeSet?                 // X/Y/SCALE/ROTATION/OPACITY; null = use payload's own transform or identity
}
```

**Why this exact shape:**
- `Clip` already models a still image (`imageClip`), speed, per-clip audio, opacity keyframes, crop, flip,
  90°-rotation. Reusing it for MASTER + VIDEO + IMAGE items means **zero duplication** and the export path
  already knows how to render a `Clip`. The one gap — **free rotation/scale/position + blend for a
  *floating* video/image** — is supplied by `TimedItem.transform` (a `KeyframeSet`, the same primitive
  `TextOverlayItem` uses) and `TimedItem.blendMode`, layered on top at composite time. Do NOT add these to
  `Clip` itself (keeps master-clip behavior byte-identical; see the regression gate).
- `TextOverlayItem` already IS a fully-transformable, keyframable, time-ranged floating item (text or PNG).
  A TEXT/STICKER track is just a `Track` whose items wrap existing `TextOverlayItem`s. Migration is trivial.
- `AudioClip` already has `offsetMs` (absolute position) + trim + volume envelope + captions. An AUDIO
  track wraps existing `AudioClip`s.
- `SpriteOverlayItem` + `FrameTrack` come from `C:\ObsidianBrain\sprite plan.md` unchanged — that plan was
  explicitly shaped so its items "drop into a SPRITE layer without reshaping." `TimedItem.zHint` and the
  self-contained `timelineStartMs` are exactly what the sprite plan asked the Track model to provide.
- `blendMode` is on `TimedItem` (not `Track`) because CapCut applies blend per clip, and a track can hold
  clips with different blends.

### 2.2 Migration (on load, one-time, additive — no destructive change)
- `timeline.clips` → `masterTrack.items` (sequential; `timelineStartMs` derived by summing prior durations,
  exactly as `ExportManager.buildComposition` already computes `timelineCursorMs`).
- `timeline.textOverlays` → ONE `TEXT` layer (each item already has start/end + keyframes).
- `timeline.waveformOverlays` → they stay attached to their source clip as today (a visualizer is bound to
  an audio source, not a free track) — model them as a derived overlay on the owning track, **not** a new
  track kind, to avoid churning the waveform export path in M5.
- `timeline.audioClips` → ONE `AUDIO` track (each has `offsetMs`). Multi-lane stacking (already done in the
  timeline view for overlapping audio) becomes multiple `AUDIO` tracks later, opportunistically.
- `transitions` stay keyed to master seams (`clipIndex`) — unchanged.
- Old (v5/v6/v7) projects: load exactly as today; the Track objects are *built from* the flat lists on
  load, so there is no on-disk migration until the user opts into layers (see Part 4).

### 2.3 Back-compat shims (the load-bearing requirement)
`getClips()`, `getTextOverlays()`, `getAudioClips()`, `getWaveformOverlays()`, `getTransitions()` remain and
return **live views over the Track model** (masterTrack.items → clips, etc.). Every existing call site
(ExportManager, EditorTimelineView, FaditorEditorActivity, AIToolExecutor, ProjectStorage) keeps compiling
and behaving identically. Do **not** refactor call sites in M5 — that's the whole point of the shims. New
layer-aware code reads `timeline.layers` / `timeline.audioTracks`.

### 2.4 Undo (free, but only if serialized)
Undo is snapshot-based (`UndoManager.SnapshotRestorer.captureSnapshot()` → `ProjectStorage.toJson`). New
model fields get undo automatically **iff** the serializer/deserializer round-trip them. **M5 acceptance
therefore requires a JSON round-trip test for every new field**, or the first undo silently erases layers.

---

## PART 3 — PREVIEW COMPOSITOR ARCHITECTURE (the hardest piece)

New package `com.fadcam.ui.faditor.compositor`. This is where the boundary-jump fix and the layer preview
converge. Build it as three layers of increasing risk; each ships value independently.

### 3.0 The reality this must respect
- **Live preview today = one `ExoPlayer` in a `PlayerView` (`surface_type="texture_view"`) + a z-ordered
  stack of Android `View` overlays** in `player_container` (bottom→top: CanvasFrame, PlayerView, transition
  overlays, image/slide preview, `WaveformOverlayView`, `TextOverlayLayer`, audio-`CaptionOverlayView`,
  video-`CaptionOverlayView`). All overlays are driven by absolute timeline time.
- **The boundary jump** (`DIAG_20260701`) is the master player's cold `setMediaItem()`+`prepare()` at every
  clip seam, with zero pre-buffering — 100–400ms of frozen frame on the Note 9. A single `ExoPlayer` holds
  one `MediaItem`, so "pre-warm the next clip" is impossible without either a multi-`MediaItem` playlist or
  a second player.
- **A second live video already has working GL precedent for export** (`GlTransitionExportEffect` reads
  `nextClip` frames and composites in a shader) and for preview (`GlTransitionPreviewView` is a
  `GLSurfaceView` compositing two textures). These are the seeds to generalize.

### 3.1 M-COMP-0 — Master playback engine (fixes the boundary jump; foundation for everything)
**Risk: medium. Ships value with zero layers.** Replace the master track's single-`MediaItem` prepare-per-seam
with a **gapless multi-`MediaItem` timeline on one `ExoPlayer`** (`ExoPlayer` supports `setMediaItems(List)`
+ `ClippingConfiguration` per item, and handles pre-buffering the next item natively; `onMediaItemTransition`
replaces the manual `advanceToSegment` cold cut). Where `ClippingConfiguration` fails on raw fragmented MP4
(the reason the current code avoids it — see `FaditorPlayerManager` header comment), reuse the **existing
remux-to-seekable cache** (`resolveSeekableSourceUri` / `FragmentedMp4Remuxer`, already warmed off-thread by
`ExportService`) so every master item is seekable. This directly eliminates the cold re-prepare for plain
single-track projects.
- **Files:** new `compositor/MasterPlaybackEngine.java` (wraps/becomes the master driver);
  `player/FaditorPlayerManager.java` (delegate to it behind the existing public API so `FaditorEditorActivity`'s
  ~14k lines don't all change at once); `FaditorEditorActivity.advanceToSegment` path (route seam handling
  through `onMediaItemTransition`).
- **Fallback:** feature-flag it. If gapless playback regresses trim/scrub/transition behavior on the Note 9,
  fall back to today's engine. The transition overlay bitmaps keep working (they're not part of the master
  player timeline).
- **Acceptance (device, Note 9):** a 3-clip single-track project plays across both seams with **no
  visible freeze/skip** at the cut (screenrecord + frame extraction across the boundary; compare to the
  DIAG's documented 100–400ms stall). Trim, scrub-to-word, and existing transitions still behave. Export is
  untouched (this is preview-only). **This milestone alone closes feedback #9 for real.**

### 3.2 M-COMP-1 — Floating IMAGE / TEXT / STICKER / SPRITE / SLIDE layers (low risk)
**Risk: low.** These need **no second video decoder** — they're bitmaps/text/still-frames. The work is
mostly *modeling the existing overlay-View stack as tracks* and driving it from `timeline.layers` at
absolute time, honoring per-track `hidden`/`locked` and per-item `blendMode` (for images/stickers/sprites)
and `transform`.
- Reuse `TextOverlayLayer` (text/PNG, already transform+keyframe+snap capable), `WaveformOverlayView`,
  `CaptionOverlayView`, `GeneratedSlideView`, and the sprite plan's `SpriteOverlayView` — all already
  `View`s in the stack. Add a thin `compositor/LayerPreviewController` that, per playhead tick, shows/hides
  and positions each track's current item bottom→top by `zIndex`.
- Blend for View-based overlays in *preview*: Android `View`/`Canvas` supports `PorterDuff`/`BlendMode`
  (API 29+) on a `Paint`/layer — sufficient for preview parity with the export blend `GlEffect`. Where a
  blend can't be matched on the View layer for an older API, fall back to NORMAL in preview and document it.
- **Files:** new `compositor/LayerPreviewController.java`; extend the sprite `SpriteOverlayView` per its
  plan; NO change to the export path yet.
- **Acceptance (device):** a project with a master video + a floating image layer + a text layer + (if
  sprite Build 1 landed) a sprite layer shows all of them at the correct absolute times, positions, and
  z-order, in live playback AND while scrubbing. Toggling a track's hide/lock in the row header removes it
  from preview / freezes its edits.

### 3.3 M-COMP-2 — Second live VIDEO / PiP layer (high risk — do last, behind a flag)
**Risk: high. This is the only genuinely hard piece.** Real-time compositing of a 2nd decoded video over the
master, with transform + opacity + blend, synced across scrub.

**Recommended approach — a single GL compositor surface fed by a small pooled set of decoders:**
- Render the master + overlay video into **one `GLSurfaceView`/`SurfaceTexture` compositor** (generalize the
  existing `GlTransitionPreviewView` `GLSurfaceView` + shader plumbing). Each video track drives a
  `SurfaceTexture` fed by its own `MediaCodec`/`ExoPlayer` decoder; the compositor draws master texture,
  then each visible overlay-video texture with its `transform` (model matrix) + opacity + `blendMode`
  (fragment-shader blend), bottom→top. Images/text/slides/sprites stay on the View layer *above* the
  compositor (M-COMP-1) — they don't need to enter GL.
- **Decoder budget (Note 9 / Snapdragon 845-class, 1080p preview): 2 simultaneous hardware AVC decoder
  instances.** One is the master; the second is the *single* active overlay-video track. This is the hard
  cap for smooth preview. Strategy:
  - **Pool of 2 video decoders**, reused across tracks (not one per track). When more than one overlay-video
    track is visible at the same playhead time, only the top-most (highest `zIndex`) visible overlay video
    gets the live decoder; other simultaneously-visible overlay videos fall back to a **cached still frame**
    (decoded via `MediaMetadataRetriever`/the existing `GlTransitionFrameOverlay` pattern) for preview, and
    are composited full-quality only on export (export has no such live-decoder limit — it re-encodes
    offline). Document this "preview shows one live overlay video; export shows all" behavior; it is the
    same pragmatic tradeoff CapCut-class editors make on mid-range phones.
  - Still-frame caches for images/text/sprites cost **zero** video decoders.
- **Graceful degradation:** if even 2 live decoders drop frames on the Note 9 (measure it), fall back to a
  **freeze-frame for the overlay video during master playback** (live-decode the overlay only while
  scrubbing/paused), or cap preview to master-only + overlay-as-poster. **Do not ship a janky preview** —
  per the old plan's rule, if it's janky, STOP and report options to the user (limit to one overlay video,
  or pre-render overlay-video layers to a flattened clip).
- **Files:** new `compositor/GlLayerCompositorView.java` (generalizes `GlTransitionPreviewView`),
  `compositor/DecoderPool.java`, `compositor/VideoLayerSource.java`; `FaditorPlayerManager` / M-COMP-0 engine
  integration. Feature-flagged so M-COMP-0 and M-COMP-1 ship without it.
- **Acceptance (device):** a master video + one overlay PiP video (scaled to a corner, 70% opacity, NORMAL
  blend) composites in live preview and scrub **without major jank** on the Note 9; the PiP is correctly
  transformed and appears/disappears at its absolute time range. A second simultaneous overlay video shows
  as a still frame in preview (documented) and is verified full-quality on export in M-EXPORT.

---

## PART 4 — MIGRATION / COMPAT / DOWNGRADE GUARD

### 4.1 The downgrade trap (must-fix; old plans miss it)
`ProjectStorage.ProjectDeserializer` stamps `SCHEMA_VERSION` onto every loaded project and there is **no
guard against opening a newer project on an older build**. Left as-is, a v8 layers project opened by an old
build loses all layers on next save. Mitigations, in order of preference:

1. **Dual-write during the transition window (REQUIRED until layers ship to the user).** Until a project
   actually *uses* layers (has any non-empty `layers`/`audioTracks` beyond the auto-migrated single text/
   audio track, or any multi-item overlap), keep writing the **v7 flat lists as the source of truth** and
   write the v8 `layers` block **additively alongside**. Bump `schemaVersion` to 8 **only when the project
   genuinely uses a layer feature** the old build can't represent. Result: a project the user never added
   layers to stays fully readable by an old build; a real layers project is correctly marked v8.
2. **Downgrade guard on load.** When `schemaVersion` on disk `> SCHEMA_VERSION` of the running build, do NOT
   silently drop unknown data: show a clear "this project was made with a newer version" message and open
   **read-only** (or refuse to save over it), instead of re-saving a lossy v7. Add this guard in M5 (it's a
   few lines and it protects the user's real project forever).
3. Because undo snapshots are project JSON, the same dual-write rule must apply to snapshots, or an undo
   past the "added first layer" point could resurrect a flat-only project. Simplest: snapshots always carry
   the full v8 block; the guard in (2) covers cross-build safety.

### 4.2 Schema v8 serialization checklist (M5 gate)
Serialize and deserialize, with `.has()` guards, and a JSON round-trip test each: `rippleMode`; per-track
`id/kind/name/zIndex/collapsed/hidden/locked/muted`; per-`TimedItem` `timelineStartMs/zHint/blendMode`;
`TimedItem.transform` (a `KeyframeSet`, reuse the existing overlay-keyframes JSON shape at
`ProjectStorage:928-945`); the payload discriminator (which of clip/textOverlay/audioClip/sprite). Reuse the
existing `Clip`/`TextOverlayItem`/`AudioClip` serializers for payloads — do not duplicate them.

### 4.3 Asset portability
Overlay-video/image layers must obey the existing `project://` relative-path + copy-on-insert + MISSING-not-
black + Consolidate machinery (already built for clips/overlays; asset-browser M1/M2). A layer clip whose
source is missing shows MISSING in preview/timeline/row and blocks (or skip-with-warning) export — same
policy as master clips. No new asset code; just route layer items through the existing resolver.

---

## PART 5 — EXPORT MAPPING (parity with preview)

Files: `export/ExportManager.java` (+ a new `export/BlendModeGlEffect.java` when blend lands). Reuse the
existing per-clip `CompositeExportOverlay` and the transition GL pattern.

### 5.1 What maps to Media3 directly (no new GL)
- **IMAGE / TEXT / STICKER / SPRITE / SLIDE floating layers** → the existing `CompositeExportOverlay`
  (`BitmapOverlay`) path. It already draws text (`TextOverlayItem`), captions, and waveforms at absolute
  `timelineMs` onto each master clip item, self-filtering by time range, with alpha. Extend it to also draw
  the floating IMAGE/STICKER/SPRITE items (sprites via the sprite plan's `SpriteFrameResolver` — one pure
  function shared by preview and export, so divergence is impossible). Position/scale/rotation/opacity come
  from `TimedItem.transform` evaluated at `timelineMs`. **No second video sequence needed for these.**
- **Overlay-video transform + opacity + position** → a second `EditedMediaItemSequence` for the overlay
  video, each item carrying `ScaleAndRotateTransformation` + `Presentation` + a `BitmapOverlay` alpha (all
  already used in `assembleClipVideoEffects`). Media3 composites multiple video sequences.
- **Per-track hidden/muted** → skip hidden tracks' items; `setRemoveAudio`/skip for muted (mirrors existing
  `isAudioMuted` handling).

### 5.2 What needs a custom `GlEffect` (blend modes)
Blend modes (MULTIPLY/SCREEN/OVERLAY/ADD/…) are **not** expressible via `BitmapOverlay` (alpha only). They
need a fragment-shader `GlEffect` that blends the overlay texture against the accumulated frame with the
chosen equation. **The codebase already has the exact pattern** — `GlTransitionExportEffect` implements
`GlEffect` → `GlShaderProgram` and even samples a *second* video's frames. `BlendModeGlEffect` follows it.
Ship blend as a clearly-marked *later* milestone (M-EXPORT-2); N video tracks with NORMAL blend (the 90%
case) ship first via §5.1.

### 5.3 The regression gate (byte-identical single-track export) — REQUIRED, spelled out
The shipped single-track export path must produce **byte-identical output until the user opts into layers.**
How, concretely:
1. **Preserve the legacy path.** `buildComposition` already branches on `isSimpleTrim` (near-lossless) vs
   full re-encode. **Do not touch the flat-list iteration** (`for ci in getClipCount()` … `buildClipItem` …
   audio sequence). Because `getClips()`/`getAudioClips()` are shims over `masterTrack`/`audioTracks`, a
   migrated project with NO extra layers iterates the *exact same clips in the exact same order* → the same
   `Composition` → the same bytes.
2. **Extend `isSimpleTrim` to exclude ANY layer feature** (this is the sprite plan's "fast-path must respect
   slow-path feature set" lesson class). Add to the guard: `timeline.layers` empty AND `audioTracks` ≤ the
   single migrated track AND no `TimedItem` has a non-NORMAL blend AND no overlay-video track. If a project
   has real layers, it takes the full re-encode path — never the fast path.
3. **Prove parity before enabling.** Take an existing real project. Export BEFORE M5 → keep the MP4. Export
   AFTER M5 (no layers added) → the two MP4s must match (same duration; frame spot-checks + audio RMS per
   `DIAG_20260626` methods). This is the gate for all later layer work. If anything differs, the
   shim/migration is wrong — fix before proceeding. **The full N-layer export path is only reached when the
   project actually has layers**, so existing single-track projects are byte-frozen by construction.
4. **Export-matches-preview bar** (enforceable since the export-fix round): a project with a floating image/
   text/sprite layer (and, if M-COMP-2 shipped, an overlay video) EXPORTS with each layer composited at the
   right time/position/opacity/blend, and the exported frames MATCH the live preview at the same timestamps.

---

## PART 6 — UI PLAN (honoring DESIGN §5)

Files: new `layers/LayerRowRenderer.java` + `layers/LayerGestureController.java` (extract-on-touch — do NOT
grow `EditorTimelineView`'s 4,881 lines); `timeline/EditorTimelineView.java` delegates to them;
`FaditorEditorActivity.java` for glue. The timeline already renders read-only layer rows (VIZ/CC/overlay/
multi-lane audio) and they're tappable + long-press-deletable (roadmap §L steps 0–0f DONE) — extend that.

### 6.1 Multi-row timeline behavior
- **Master row is PINNED** (always on screen). Vertical scroll moves floating layers *above* and audio
  tracks *below* the master (CapCut's anchored main track). This is the single most important navigability
  rule — lining clips up to the master must never require scrolling the master away.
- **Each floating/audio track is collapsible via a caret**: expanded = full-height row with thumbnails/
  frame-diamonds; collapsed = a **thin colored summary strip** showing item spans. Collapsed is the default
  on phones to conserve vertical space (DESIGN §5: "vertically shrink upper drawers where possible").
- **Per-track hide / lock / mute toggles** live in the track's row header (left gutter), not in a separate
  panel — so they're one tap from the timeline and don't occlude it.
- Drive rows from `timeline.layers` / `audioTracks` once the model exists (M6), replacing the current
  read-only render fed from the flat lists.

### 6.2 Phone vs tablet
- **Phone:** collapsed rows by default; a floating "layer navigator" is a **frosted, resizable, quickly-
  toggleable panel** modeled on the transcripts drawer (the gold standard) — it must NOT cover the timeline
  region while you're aligning items (DESIGN §5: covering the timeline is as bad as covering the preview).
  Per-track edit controls (blend, opacity, transform) open in the snap-height bottom panel with detents
  (the sprite plan's/`AssetBrowserPanel`'s grab-handle pattern — the convergence standard per EVAL §3.4),
  which shares vertical space with the preview the way the transcripts drawer does, never occluding the
  timeline row you're working on.
- **Tablet (`sw600dp`):** rows can stay expanded; the layer navigator + property panel coexist beside/below
  the timeline rather than over it (mirrors the sprite plan's tablet arrangement).

### 6.3 Editing floating items (M7) + drag-between-layers (M10)
- Drag a floating item horizontally → change `timelineStartMs` (absolute). Trim in/out on its row. Delete.
  Master keeps its ripple/gap sequential behavior; floating items are free.
- Drag an item vertically between rows → change layer / z-order. Drag from the asset browser onto empty
  space below all rows → create a NEW track (the A5 "drop to new layer" deferral).
- All gesture logic goes in `LayerGestureController`, not the timeline view body.

---

## PART 7 — MILESTONE SEQUENCE (M-numbered, supersedes old M5–M11)

Each milestone: independently shippable, always-green, device-verifiable on the Note 9. Do in order. The
riskiest items (M-COMP-2, blend) are last and flagged so everything before them ships without them.

| M | Scope | New files / packages | Device acceptance (Note 9) | Risk |
|---|---|---|---|---|
| **M5** | Schema v8 Track/TimedItem model + migration + `getClips()` shims + dual-write + downgrade guard. No visible change. | `layers/Track.java`, `layers/TimedItem.java`, `layers/TrackKind.java`; extend `Timeline`, `FaditorProject` (v8), `ProjectStorage` (serialize/deserialize + guard) | **Regression gate:** existing project exports byte-identical before/after (dur + frames + audio RMS). Save/reload round-trips every new field (`run-as cat project.json`). Undo after a trim still works. Old build opening a v8 project is guarded (read-only/warn), not lossy. | **High** (the keystone risk) |
| **M-COMP-0** | Master gapless multi-`MediaItem` playback engine; fixes the clip-boundary jump. Preview-only. | `compositor/MasterPlaybackEngine.java`; delegate from `FaditorPlayerManager`; reroute `advanceToSegment` | 3-clip single-track project crosses both seams with **no visible freeze** (screenrecord across boundary). Trim/scrub/transitions still behave. Export untouched. Closes feedback #9. | **Med** |
| **M6** | Multi-row timeline UI: pinned master, collapsible floating+audio rows, per-track hide/lock/mute, driven by `timeline.layers`. | `layers/LayerRowRenderer.java`; extend `EditorTimelineView` (delegate) | Multi-row timeline; master pinned on vertical scroll; carets collapse/expand; hide/lock/mute render and take effect. | **Med** |
| **M7** | Edit floating items on rows: move (absolute time), trim in/out, delete. Master unaffected. | `layers/LayerGestureController.java`; glue in `FaditorEditorActivity` | Move/trim/delete a text/image item on its row; preview reflects new time; master clips unchanged. | **Med** |
| **M-COMP-1** | Preview compositing of floating IMAGE/TEXT/STICKER/SPRITE/SLIDE over master (View stack modeled as tracks; per-track hide, per-item blend on View layer, transform). | `compositor/LayerPreviewController.java`; extend sprite `SpriteOverlayView` | Image + text (+ sprite) layers appear at correct time/position/z in live preview AND scrub; hide/lock in row header affects preview. | **Low** |
| **M-EXPORT-1** | Export N non-video layers (image/text/sticker/sprite/slide) via extended `CompositeExportOverlay`; overlay-video via 2nd `EditedMediaItemSequence` with transform+opacity (NORMAL blend). Extend `isSimpleTrim` guard. | extend `ExportManager`, `CompositeExportOverlay` | A project with floating image/text/sprite layers exports with each composited at the right time/pos/opacity; frames MATCH preview at same timestamps. Single-track project still byte-identical. | **Med** |
| **M-COMP-2** | Second LIVE video / PiP layer in preview: GL compositor (generalize `GlTransitionPreviewView`) + 2-decoder pool + still-frame fallback + graceful degradation. Feature-flagged. | `compositor/GlLayerCompositorView.java`, `compositor/DecoderPool.java`, `compositor/VideoLayerSource.java` | Master + one overlay PiP video composites in live preview + scrub **without major jank**; correct transform/opacity/time-range. 2nd simultaneous overlay video shows as still (documented). | **High** |
| **M-EXPORT-2** | Blend modes on export (custom `GlEffect`, following `GlTransitionExportEffect`) + full overlay-video export parity with M-COMP-2. | `export/BlendModeGlEffect.java`; extend `ExportManager` | An overlay clip with MULTIPLY/SCREEN blend exports matching the preview blend; all overlay videos present full-quality in export. | **High** |
| **M10** | Drag-between-layers + drop-to-new-layer (completes A5). | extend `LayerGestureController` | Drag an item between layers (z changes); drop an asset below all rows → new track. | **Med** |
| **M11** | Master `rippleMode` toggle (ripple vs gap); floating layers stay absolute. Later: optional per-item `linkToMaster` (pin-to-master) wired to the M4 link toggle. | extend `Timeline`, `EditorTimelineView`, glue | Toggling ripple/gap changes master delete/trim behavior; floating layers don't shift. | **Low** |

**Ordering rationale:** M5 first (everything depends on the model; it's the regression gate). M-COMP-0 next
because it's the boundary-jump fix that ships value with zero layers and is the playback foundation the
compositor builds on. M6/M7 make layers visible and editable. M-COMP-1 + M-EXPORT-1 deliver the *common*
CapCut case (image/text/sticker/sprite over video, in preview and export) at LOW/MED risk. The two HIGH-risk
video-compositing + blend milestones (M-COMP-2, M-EXPORT-2) come last, flagged, deferrable. M10/M11 are
polish that can slot after M7 whenever convenient.

---

## PART 8 — SIZING, MODEL TIER, VERIFICATION

| M | Sessions (est.) | Model tier | Can verify purely on sandbox? |
|---|---|---|---|
| M5 | 1.5–2 | **Opus** (keystone model change; migration + guard + regression gate) | Yes (round-trip + before/after export diff on Note 9) |
| M-COMP-0 | 1.5–2 | **Opus** (gapless engine, subtle playback state machine) | Yes (screenrecord boundary frames) — but capture tooling on this Note 9 truncates short screenrecords (per DIAG); budget for that |
| M6 | 1–1.5 | Sonnet (UI, extends known timeline patterns) | Yes |
| M7 | 1 | Sonnet | Yes |
| M-COMP-1 | 1–1.5 | Sonnet (reuses existing View overlay stack) | Yes |
| M-EXPORT-1 | 1.5 | **Opus** (export parity is unforgiving; ffmpeg frame/RMS verify) | Yes (pull MP4 + ffmpeg) |
| M-COMP-2 | 2–3 | **Opus** (GL compositor + decoder pool; the hardest bet) | **Partly** — jank must be measured on real mid-range silicon; the Note 9 IS Snapdragon 845-class so it's a valid floor, but frame-drop measurement needs care |
| M-EXPORT-2 | 1.5–2 | **Opus** (blend GlEffect shader correctness) | Yes (ffmpeg frame compare) |
| M10 | 1 | Sonnet | Yes |
| M11 | 0.5–1 | Sonnet | Yes |

**Total: ~13–18 sessions** for the full CapCut-parity Layers system (the old plan's "3–5 sessions" covered
only M5–M9 at a coarse grain and under-counted the compositor + export-parity + blend work). A **usable**
Layers MVP (M5 → M-COMP-0 → M6 → M7 → M-COMP-1 → M-EXPORT-1) is ~8–10 sessions and delivers everything
except live second-video and blend — which is already competitive with CapCut for the dominant use cases
(captions/text/stickers/sprites/images over a talking head, plus the boundary-jump fix).

---

## PART 9 — WHERE WE SURPASS CapCut (leverage existing unique assets)
These are not extra milestones; they're why the Track model is shaped to *host* them:
- **Transcript-driven editing** — the Track model keeps `Clip`/`AudioClip` transcripts intact; captions
  become a first-class TEXT-track-like layer. No competitor edits by transcript at this fidelity on-device.
- **AI tools (EditScript pipeline)** — `AIToolExecutor` already applies EditScripts atomically with
  propose-then-confirm. Layer ops (add layer, move item, author sprite animation) become new EditScript ops;
  the sprite plan's fast-follow B and narrative/b-roll tools all target the same Track model → conversational
  multi-track authoring.
- **Sprite animation plan** — designed to drop into a SPRITE track; `SpriteFrameResolver` guarantees
  preview==export. A keyframed sprite avatar synced to the transcript is a CapCut-beating feature.
- **GL transitions catalog** — the 26-shader catalog + the GL plumbing being generalized for M-COMP-2 means
  transitions can eventually apply between *layers*, not just master seams.

---

## PART 10 — WHAT A BUILD AGENT MUST PROBE FIRST (could not be determined from code alone)
1. **Two-decoder budget on the actual Note 9.** The "2 simultaneous 1080p AVC decoders" figure is the
   Snapdragon-845 class norm, but the real ceiling (and whether the master's TextureView surface + a second
   decoder + the encoder during a background export coexist) MUST be measured on `SANDBOX_SERIAL` before
   committing M-COMP-2's live-second-video path. Probe: decode two 1080p AVC streams into two SurfaceTextures
   and composite; watch for `MediaCodec` allocation failures / dropped frames.
2. **Does gapless multi-`MediaItem` `ClippingConfiguration` actually seek on FadCam's fragmented MP4 after
   remux?** The current single-player code deliberately avoids `ClippingConfiguration` for fMP4. M-COMP-0
   assumes the existing remux-to-seekable cache makes it work. Verify on a real raw-fMP4 recording before
   building the whole engine on that assumption; if it still fails, the fallback is a **second warm
   `ExoPlayer`** to pre-prepare the next clip (heavier, but the DIAG lists it as the other valid fix).
3. **Media3 multi-video-sequence composite order + blend interaction.** Confirm on-device that a second
   `EditedMediaItemSequence` composites *over* the first with the expected z-order and that a per-item
   `BitmapOverlay`/`Presentation` positions the PiP correctly — before wiring the full N-track export.
4. **Android `View`-layer blend (`BlendMode`/`PorterDuff`) parity with the export `GlEffect` blend.** Verify
   a MULTIPLY on the preview View layer visually matches the exported GL blend closely enough; if not,
   document the preview-approximation and rely on export as ground truth.
5. **project.json size with layers.** The user's real project is already 2.4 MB (triplicated transcripts,
   EVAL §3.5). Layers + per-item transform keyframes will grow it and every undo snapshot clones it. Measure
   save/load latency with a multi-layer project on the Note 9; the transcript-dedup consolidation (EVAL §3.5)
   should probably land before heavy multi-layer projects are common.

---

## Status tracking
After each verified milestone: tick below, update `road_map.md` Phase 5 status, append a ≤8-line dated entry
to `handoff.md`, keep the tree green.

- [x] M5 schema v8 + migration + shims + dual-write + downgrade guard — LANDED 2026-07-02 compile-green (synchronized-from-flat views; ZERO call sites touched; ExportManager untouched; deserializer double-stamp bug fixed; save refused on newer-version projects). ⚠️ On-device REGRESSION GATE still owed (no device) — see REPORT_RELAY_20260702.md. ⚠️ Track flags/blend/transform are serialized but rebuild-as-default (views are ephemeral) — M6/M7 must give mutated fields a persistent home (side-table keyed by track id, or promote Track to storage-of-record).
- [ ] M-COMP-0 master gapless engine (boundary-jump fix)
- [x] M6 multi-row timeline UI — LANDED 2026-07-02 compile-green (LayerRowRenderer 419 lines owns all rendering/hit-testing; EditorTimelineView +111 delegation-only; TrackFlags side-table persisted + applied by Timeline view builders, closing M5's ephemeral-flags gap; toggles = one LambdaAction undo step each; plain projects = zero new rows/height; rows default EXPANDED deliberately — collapsed-by-default would stamp v8 on every migrated project). Device pass owed. [x] M7 edit floating items — LANDED 2026-07-02 compile-green (layers/LayerGestureController 324 lines; EditorTimelineView +65 delegation-only; move/trim/delete mutate PERSISTENT payloads (TextOverlayItem start/end, AudioClip offset/in/out) mirroring existing undo actions (OverlayTransformAction/AudioTrimAction/DeleteAudioClipAction), one step per gesture; locked/hidden rows inert pre-hit-test; master structurally unreachable). Device pass owed.
- [ ] M-COMP-1 image/text/sticker/sprite preview  [ ] M-EXPORT-1 export N non-video layers + NORMAL-blend overlay video
- [ ] M-COMP-2 live second video (GL + decoder pool)  [ ] M-EXPORT-2 blend modes + full overlay-video export parity
- [ ] M10 drag-between-layers  [ ] M11 ripple/gap toggle
