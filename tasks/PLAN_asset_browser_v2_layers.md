# Plan: Asset Browser v2, Insert/Drag UX, Transitions polish, Layers

**Captured:** 2026-06-19 — from user feedback after the slides feature + canvas frame landed.
**Status:** backlog / design. Pick items per priority below. Nothing here is built yet
unless marked DONE.

---

## P0 — Bugs / data integrity

### B1. Inserted images (and some added video) go black after app reinstall — DATA LOSS
**Symptom:** a PNG inserted from the pinned assets folder shows black and stays black after the
first reinstall/update. Slides persist (internal files); core FadRec video persists (`file://`
primary storage + MANAGE_EXTERNAL_STORAGE); SAF-inserted images/video do not.
**Root cause:** `insertAssetAtPlayhead` / `insertAssetAtIndex` / drag-insert store the asset's SAF
`content://` *child document* URI as `Clip.sourceUri`, and call
`takePersistableUriPermission(childUri, …)` — which throws (only the *tree* grant is persistable)
and is swallowed. Access then depends on the pinned tree grant surviving reinstall, which is
unreliable in dev. (`FaditorEditorActivity` ~line 7075–7190, 7360–7420.)
**IMPLEMENTED (2026-06-19) — foundation:**
- **Schema v6 relative paths.** `FaditorProject.SCHEMA_VERSION=6`, new `PROJECT_URI_SCHEME="project"`.
  `ProjectStorage` adapters made non-static; `toStorageUri`/`fromStorageUri` rewrite `file://` URIs
  under the project dir to `project://<relative>` on save and resolve them back on load. Applied to
  clip `sourceUri`, audio `sourceUri`, and overlay `imageUri`. v5 projects load unchanged (absolute
  URIs only become relative once an asset lives in the project dir). Slide html/renderCache URIs not
  yet relativized (they survive reinstall via absolute internal path; cross-device = follow-up).
- **Hybrid-on-insert copy.** `importInsertedAsset()` in `FaditorEditorActivity` copies all images +
  any asset ≤25 MB (`ASSET_COPY_MAX_BYTES`) into `<projectDir>/assets/<uuid>.<ext>` and references the
  internal `file://`; larger videos stay referenced (best-effort tree grant). Wired into
  `insertAssetAtPlayhead` and `insertAssetAtIndex` (+ audio). `queryAssetSize`/`assetExtension` helpers.
- **Still TODO:** missing-media → relink surfacing (no silent black); slide URI relativization; the
  Consolidate review screen (below); migrate the user's already-broken SD-card clip via relink.

**CHOSEN STRATEGY (2026-06-19): Hybrid-on-insert (Option C) + on-demand Consolidate.**

**On insert (hybrid, automatic):**
- **Small assets (images, < ~20 MB):** copy into `<project dir>/assets/<uuid>.<ext>` and store the
  internal `file://` as `sourceUri` (mirror the slide model). Self-contained, survives forever.
- **Large videos:** keep the original URI but persist the **tree** grant and rebuild child access
  from it. Avoids copying GBs by default (user's explicit "no duplicate files everywhere" concern —
  videos are the big offenders).
- A black/missing source must show as MISSING in the relink catalog, never silent black.

**Consolidate Project (on-demand action — user can pack up & move):**
Gathers every still-referenced *original* asset into the project's pinned folder so nothing is left
behind. Behavior fixed with the user:
- **Always COPY** into the pinned folder (never silently move out of the user's gallery/DCIM).
  Dedupe by content-hash; skip anything already inside the pinned folder.
- **After copy, OFFER to delete originals:** "Delete files from where we copied them?" — opt-in,
  per the review screen below. Default off.
- **Review/manifest screen is the safety feature — REQUIRED.** Before doing anything it must show:
  - the specific files to be consolidated (one row each)
  - a thumbnail/preview for media files
  - the clearly-labeled **source folder**, with the **full path scrubbable** (horizontal scroll)
  - **per-file size**, plus a running total of space the copy will use
  - **individual checkboxes** to include/exclude each file
  The point: the user must be able to see exactly what's being duplicated and from where, so the
  feature is informed-and-safe rather than shipping useless duplicates blindly.
- Must NOT touch derived artifacts (slide render cache, fragmented-MP4 remux copies) — those are
  rebuildable and their paths are never persisted. Consolidate only acts on original `sourceUri`s.
- Run atomically: copy to temp, swap project refs only after all selected copies succeed; confirm
  total size before starting.

**REFERENCE STYLE — DECIDED (2026-06-19): schema v6 project-root-relative asset paths.**
Rationale (user): the project packs up as a simple, non-proprietary, human-readable bundle —
project JSON + an `assets/` folder, all in one SD-card folder that can be moved out wholesale and
still be recognized by FadCam on another device. (Absolute `content://` authority changes per
device, so co-locating files alone is NOT enough — relative paths are required for real portability.)
Implementation notes:
- Bump `SCHEMA_VERSION` to 6. On save, store consolidated/internal assets as paths relative to the
  project root (e.g. `assets/<uuid>.<ext>`). On load, resolve relative paths against the current
  project dir; keep absolute-URI fallback for un-consolidated assets still living elsewhere.
- Migration: v5 projects load unchanged (absolute URIs); relative paths only appear once an asset is
  copied/consolidated. No destructive migration needed.

### B2. Insert "green arrow" persists after the asset panel closes — FIXED (2026-06-19)
`onPanelCollapsed()` now hides `btn_insert_at_playhead` and clears `selectedAsset`.
(original below)

`btn_insert_at_playhead` stays visible after the asset browser collapses; bumping it inserts the
last-selected asset. **Fix:** hide `btn_insert_at_playhead` (and clear `selectedAsset`) whenever the
asset panel collapses/closes. Tie its visibility strictly to "panel open AND an asset selected."

---

## P1 — Quick polish

### Q1. Canvas hatch opacity — DONE (2026-06-19)
`CanvasFrameView` stripe now ~55% opacity (`0x8C3A3A3A`) so it reads as a subtle guide.

### Q2. Transition selection color → BLUE — DONE (2026-06-19)
`EditorTimelineView` selected-transition outline now uses `COLOR_TRANSITION_SEL` (blue 0xFF2196F3)
instead of the green `borderPaint`. (Trim handles still use the shared green handle paint — could
be blued later for full consistency.)

### C-play. Play/pause centered over playhead — DONE (2026-06-19)
Control row restructured: `btn_play_pause` is `layout_centerInParent`, with left group
(magnet+undo) `toStartOf` and right group (redo+link) `toEndOf` — flanking icons never shift it.

### Q3b. Transition preview never actually rendered (dissolve = "fade to black then snap") — FIXED (2026-06-19), pending verify
**Root cause:** `TransitionPreviewOverlayView` defaults to `GONE` and *nothing ever set it VISIBLE*
(every `setVisibility` call in `FaditorEditorActivity` was `GONE`). So `onDraw` early-returned and the
incoming frame/veil never painted — during playback AND scrub. The visible "fade to black" was just
`playerView.setAlpha(1f - progress)` over the black canvas; the next clip popped in when playback
advanced. **Fix:** `renderBitmap` now sets VISIBLE; `renderColor` sets VISIBLE when alpha>0 (GONE at
~0). Fade branches in `renderTransitionPreview` now keep `playerView` opaque (alpha 1f) so the veil
is the single fade source instead of double-darkening. Dissolve/wipe/push/etc. now composite the
incoming frame over the outgoing player.
**Perf + decode fixes (2026-06-19):**
- Transition overlay/GL layers are now sized to the canvas rect (`applyCanvasFrame`) so incoming
  frames letterbox on the canvas instead of spilling to full-screen.
- Decoded frames keep native aspect (`scalePreservingAspect`).
- `resolvePlaybackUri` is cached (`playbackUriCache`) — the per-tick `needsRemux` header read was
  snagging the scrub.
- `MediaMetadataRetriever` uses the file PATH for `file://` sources — `setDataSource(Context,fileUri)`
  was failing with status 0x80000000, which blanked the frame and caused a per-tick retry snag.
- Transition frames are cached per `uri@WxH` (decode once per scrub) with a negative cache for
  undecodable clips (`transitionFrameCache`/`transitionFrameFailed`, cleared in `hideTransitionPreview`).

**Verification BLOCKED by B1 (2026-06-19):** the user's test project references an SD-card video
(`content://…/0000-0000:+Projects/Fadcam video assets/Joyraptor-intro-screen.mp4`) whose SAF grant is
dropped on every reinstall, so the clip can't decode/seek and transitions over it show nothing. This
is NOT a transition-code regression (verified: `FaditorPlayerManager` resolves the content:// itself,
unchanged by our code). Transition correctness must be re-verified with reinstall-surviving media
(FadRec `file://` recordings or the internal slide). **B1 is now the practical blocker.**

**Polish pass (2026-06-19):**
- Slide-as-OUTGOING now previews: `updateScrubTransitionPreview` is wired into the slide branch and,
  when the outgoing clip is a slide, lifts `transitionPreviewOverlay`/`glTransitionPreviewView` above
  the slide WebView so the incoming frame composites over the held slide frame (true crossfade).
  `restoreSlideZOrder()` puts the WebView + editable overlay back on top when leaving the seam window.
- Fragmented FadRec MP4 next-clip decode is already covered: `decodeTransitionFrame` →
  `resolvePlaybackUri` remuxes to a seekable copy before `MediaMetadataRetriever`.
- Possible jank: `resolvePlaybackUri` may `remuxSync` on first access on the UI thread during scrub
  (cached after) — acceptable for now; revisit if it stutters.

### Q3. Real-time transition preview while scrubbing — DONE (2026-06-19), pending device verify
`updateScrubTransitionPreview(clip, segmentIndex, seekPosition)` (FaditorEditorActivity) mirrors the
playback transition-window detection but is driven by the scrub seek position; called from
`onPlayheadSeeked`'s video-clip seek branch after `seekTo`/`updatePreviewTransforms`. Reuses the
existing `renderTransitionPreview(progress, currentPos)` (fade/dissolve/wipe/GL) and
`hideTransitionPreview()` when outside the seam window. Needs build + device verify of dissolve.

---

## P2 — Asset Browser v2 (insert UX)

### A1. Resizable panel
Add a bottom grab handle (like the transcript panel) to drag the asset panel taller/shorter.

### A2. Panel persistence
Scrubbing the timeline, using the minimap, pressing play, or undo must NOT close the asset panel.
Only tapping the video viewer dismisses it. (Currently various interactions collapse it.)

### A3. Insert affordance on asset tap
Tapping an asset immediately shows the insert control cluster over the playhead:
- **Center green arrow** = insert at playhead. **Green** when the playhead is snapped to a clip
  boundary (clean insert); **yellow** when it will split a clip (cut-insert). Small label above it
  reads **"Insert"** (green) or **"Cut"** (yellow) so the user knows what will happen.
- **Two flanking buttons** (left/right of the arrow): **insert before this clip** / **insert after
  this clip**. Need icons — find/create a clear pair (e.g. bracket-with-arrow-left / -right, or
  "⇤▮" / "▮⇥"). Tapping any of the three performs that insert (or cut-insert).
- When the panel closes, ALL of these disappear (see B2).
- **KNOWN BUG (2026-06-19):** `btn_insert_at_playhead` lives inside `player_container` (bottom-center,
  elevation 8dp), but when the asset panel slides up it sits *under* the panel/scrim — so tapping the
  green arrow hits the panel and collapses it instead of inserting. The arrow must be raised above the
  asset panel (higher elevation / re-parent to a top-level overlay) so it's actually tappable while
  the panel is open. (Verified: it does correctly disappear when the panel collapses — only the
  z-order/tappability is wrong.)

### A4. Long-press = rename (not insert)
Change long-press on an asset from "insert" to "rename the file" (via `DocumentFile.renameTo`,
preserving extension — partially exists). Renaming must update existing clip references that point
at that file (match by document id, not display name) so nothing breaks. Plain tap → insert
affordance (A3); drag → drag-drop (A5).

### A5. Drag-and-drop insert (multiple paths to same result)
Dragging an asset thumbnail:
- Hovering over the **timeline minimap** jumps the timeline to that region after a short dwell.
- Hovering near the **timeline edges** auto-scrolls left/right.
- **Releasing over the timeline** drops the asset at the previewed position (split+insert if
  mid-clip) — or onto a new **layer** (see L1; deferred until layers exist).
- Dragging **back into the asset window** and releasing **cancels**.
- Dragging onto the **insert / insert-before / insert-after icons** (A3) performs that action.
- While hovering a cut-insert position, show a **yellow dotted line** at the proposed split point
  that tracks the finger.
Buttons (A3) and drag-drop (A5) are two routes to the same operations.

---

## TEXT OVERLAY ghosts — FIXED (2026-06-19)
Empty "Enter text" overlays could get stuck: OK/Cancel with no text left a placeholder, and the
slide WebView's `bringToFront()` covered the overlay layer so they couldn't be tapped. Fixes:
OK/Cancel with empty-or-hint text now removes the overlay; `showSlidePreview` brings `overlayLayer`
back above the slide. (To clear pre-existing ghosts: tap a video clip, tap the overlay, Delete.)
Still TODO: clearer overlay selection highlight + a timeline row for overlays (folds into Layers).

## C. Control row + snapping + relink relocation (user-specified)

Redesign the play/pause control row and the long-press media menu:
- **Repurpose the existing "link" icon** (next to play/pause) as the **timeline-link behavior
  toggle** (master-link ON/OFF — floating layers follow master edits vs stay absolute). Long-hold
  opens a mini preferences window for link behavior.
- **Mirror-opposite side: a MAGNET icon** = **global soft snap** — soft-snaps both in TIME and in
  SPACE and to adjacent objects, so items move along smart alignment planes (viewport + timeline).
  Long-hold opens a mini preferences window for snapping (per-axis / object / time toggles).
  Consolidate the existing per-overlay `overlaySoftSnapEnabled` / `btnSoftSnap` into this global magnet.
- **Move "relink media" OUT of the control row.** It belongs in the **long-press media menu**
  (long-press on an asset / clip). Show a **band-aid icon** for relink to the LEFT and RIGHT of the
  reorder title in that menu. Long-press = media-specific functions (relink, rename, etc.), not insert.
- Net: control row = [snap magnet] … [undo] [play/pause] [redo] … [timeline-link toggle]
  (exact arrangement TBD), with relink no longer there.
- **Play/pause MUST stay centered over the playhead.** Currently adding icons to either side jogs
  it off-center (handoff §5.5). Fix: anchor play/pause to the true center (e.g. center it in the
  parent independently, with flanking icons in equal-weight side groups) so left/right icon counts
  never shift it. This is a hard requirement for the control-row rewire.

**Layer-linking decision (from user):** it's a TOGGLE, not a fixed schema choice. Support BOTH
absolute-time and pin-to-master; default absolute, user flips via the timeline-link toggle above.
Schema: add `linkToMaster:bool` per floating item (or a global default + per-item override).

## L. Layers (multi-track) — design + what's blocking

### What's blocking full layers TODAY
The model and pipeline are single-track by design:
1. **Model:** `Timeline.clips` is a flat, *sequential* list = one video track. Clips have no explicit
   timeline offset or track index — position is implied by list order. Layers need clips with
   (trackIndex, timelineStartMs, z-order).
2. **Preview:** one `ExoPlayer` plays one clip at a time (`FaditorPlayerManager.loadClip`). Multi-layer
   compositing needs a real-time compositor (GL surface mixing N layers + overlays), not a single
   player.
3. **Timeline UI:** `EditorTimelineView` is a single row. Layers need a multi-row track view with
   vertical drag-between-tracks.
4. **Export:** Media3 `Composition` already supports multiple `EditedMediaItemSequence`s (we use 2:
   video + audio). N stacked video layers with positioning/blend is feasible via additional
   sequences + `OverlayEffect`, but arbitrary compositing (free position/scale/rotate per layer,
   blend modes) likely needs a custom GL pass. So export is the *least* blocked; preview is the most.

None are insurmountable — it's a deliberate, sizable architecture change, best done as its own
milestone. The asset insert/drag work above should be built "layers-aware" (A5 already leaves room
for "drop onto a new layer").

### Target design — CapCut-style master timeline (for review; build later)

**Core principle (from user):** ONE master video track that is the spine of the project and is
ALWAYS visible. All other layers (overlay video, images, text, stickers, audio) float ABOVE/BELOW
it at absolute timeline times. The master ripples; floating layers are pinned to absolute time.

**Schema (proposed v6 — additive, back-compat):**
```
Timeline {
  rippleMode: "ripple" | "gap"          // master-track edit behavior (default "ripple")
  masterTrack: Track(kind=MASTER)        // the spine; gapless in ripple mode
  layers: [ Track ]                      // floating tracks, ordered by zIndex (above master)
  audioTracks: [ Track(kind=AUDIO) ]     // below master
}
Track {
  id, kind: MASTER|VIDEO|IMAGE|TEXT|STICKER|AUDIO,
  name, zIndex, collapsed:bool, hidden:bool, locked:bool, muted:bool,
  items: [ TimedItem ]
}
TimedItem {
  timelineStartMs,                       // absolute position on the timeline
  clip|textOverlay|...,                  // reuse existing Clip / TextOverlayItem
  // master items are sequential & gapless (timelineStartMs derived); floating items are free
}
```
- **Migration:** `timeline.clips` → `masterTrack.items` (sequential). `textOverlays` → one TEXT
  layer (each already has start/end = timelineStart/end). `audioClips` (have `offsetMs`) → an AUDIO
  track. Old projects load unchanged; SCHEMA_VERSION bump. Keep `getClips()` etc. as thin
  shims over `masterTrack` during transition so existing code keeps working.

**Ripple / gap behavior (master track only):**
- `ripple` (CapCut default): deleting/trimming a master clip pulls following clips left — no gaps.
- `gap`: edits leave a hole (black) where the clip was; clips don't auto-collapse.
- Toggle in UI. Floating layers stay at absolute time (do NOT shift with master ripple) in v1;
  later add optional "pin to master clip" linking (CapCut behavior) as a per-item flag.

**Timeline UI (phone-optimized):**
- Master track row is PINNED (always on screen) — vertical scroll moves the floating layers
  above and audio below it while master stays put (CapCut's anchored main track).
- Each floating layer is **collapsible via a caret**: expanded = full-height row with thumbnails;
  collapsed = thin summary strip (colored bars showing item spans). Saves vertical space on phone.
- Per-track hide / lock / mute toggles in the track header.
- Drag items within a row (move in time) or between rows (change layer / z-order). Drag from the
  asset browser onto a row inserts there; onto empty space below creates a new layer (A5).

**Preview compositing:**
- Replace the single-ExoPlayer preview with a GL compositor surface. Per frame at time `t`: draw
  master frame, then each visible floating layer's current item (decoded via pooled
  MediaCodec/ExoPlayer instances or a bitmap/frame cache for images/text/slides), bottom→top, with
  each item's transform (pos/scale/rotation), opacity, and (later) blend mode. Slides and text
  composite through the same path. Scrubbing drives every layer with `seek(t)`.
- This is the heaviest piece and the main reason layers isn't a quick add.

**Export:**
- Map master + each layer to the Media3 `Composition` (multiple `EditedMediaItemSequence`s +
  `OverlayEffect`/`TextureOverlay` for positioned layers), reusing the slide + text + transition
  compositing already built. Arbitrary blend modes → a custom `GlEffect` pass if needed.

**Build phasing for layers (each shippable):**
0f. **(DONE 2026-06-22) Selection HIGHLIGHT on tapped layer row.** Tapping a layer row now draws a white ring
   (`layerSelPaint`) around it (`selectedLayerKind`/`selectedLayerValue` set in onUp's layer-tap dispatch,
   matched per-row in `drawLayers`; cleared in onDown when the touch lands off any layer row, and on
   video/audio selection). Device-verified: tap CC row → white ring; tap a clip → ring clears.
0e. **(DONE 2026-06-22) LONG-PRESS layer rows → delete/remove.** Mirrors the loved tap=open/long-hold=delete
   pattern on the timeline rows. `EditorTimelineView.layerLongPressRunnable` (scheduled with `pendingLayerTap`
   in onDown, cancelled on drag, consumed in onUp) fires new callbacks `onVisualizerLayerLongPressed` (→
   confirm "Remove visualizer?" → `removeWaveformOverlay`), `onOverlayLayerLongPressed` (→ confirm → 
   `removeTextOverlay`), `onCaptionLayerLongPressed` (→ hide captions for that clip, reversible/no confirm).
   Destructive deletes use a bare `MaterialAlertDialogBuilder` confirm. Device-verified: long-press VIZ row →
   "Remove visualizer?" dialog; Cancel preserved it. Layer rows now do tap=open + long-hold=delete.
   NOTE: audio DUCKING is NOT exposed — `duckAmount` is stored/set (UI + AI tool) but never APPLIED in
   export/playback (no duck processor), so it's a hollow value; real ducking needs a voice-activity duck
   processor (deferred). NEXT: cut a layer item at the playhead / drag between rows (needs Track model).
0d. **(DONE 2026-06-22) TAPPABLE layer rows** — the read-only layer rows are now interactive.
   `EditorTimelineView.hitTestLayerTap` (run in onDown, dispatched in onUp if not dragged) maps a tap to a
   layer row and fires new `OnSegmentActionListener` default callbacks: `onVisualizerLayerTapped` (→ opens
   that visualizer's Rolodex drawer), `onOverlayLayerTapped` (→ seeks to the overlay's start),
   `onCaptionLayerTapped(clipIndex)` (→ `selectSegment` + seek to that clip so its captions/props show;
   caption spans carry their clip index in `span[2]`). Device-verified: tap VIZ → drawer; tap CC → clip
   selected + captions show. This is the first INTERACTIVE layer step (read-only → navigate/edit). NEXT:
   make rows cuttable/draggable (split a layer item, drag between rows) — needs the Track model eventually.
0c. **(DONE 2026-06-22) CAPTION track row** — `EditorTimelineView` now renders a single **"CC" amber caption
   track** row (below the overlay + VIZ rows) with one segment per clip that has captions enabled + a
   transcript (`setCaptionSpans` fed from `syncTimelineOverlays`, computing each clip's timeline span via
   `getSegmentStartTimeMs`). Captions share ONE row (sequential clips don't overlap) like a real caption
   track. Pure render change. Device-verified: 3 captioned scenes show as CC|CC|CC on one amber row.
   So the timeline now shows VIDEO + multi-lane AUDIO + TEXT/IMAGE overlay rows + VISUALIZER + CAPTION as
   layers. NEXT visible slice: make the layer rows tappable (select + open the matching drawer), then cuttable.
0b. **(DONE 2026-06-21) VISUALIZER layer rows** — `EditorTimelineView` now renders each waveform/visualizer
   overlay as its own read-only **"VIZ" cyan row** (in `drawLayers`, continuing the rows below the existing
   text/image overlay rows), positioned at the overlay's `getStartMs..getEndMs` span. New `setWaveformLayers`
   setter fed from `syncTimelineOverlays`; `onMeasure` reserves height for `overlays.size()+waveformLayers.size()`
   rows. Pure render change (no model/storage change). Device-verified: VIZ row shows at the visualizer's span.
   So the timeline now visibly shows VIDEO + multi-lane AUDIO + TEXT/IMAGE overlay rows + VISUALIZER rows as
   layers — a real step toward the keystone. (Text/image overlays already rendered as rows pre-2026-06-21.)
   Next visible slice could be CAPTIONS as a row, then making these rows tappable/cuttable (editing).
0. **(DONE 2026-06-21) Multi-lane AUDIO rows** — overlapping audio clips auto-stack into separate lanes
   in `EditorTimelineView` (`recomputeAudioLanes`/`audioClipLanes`/`audioLaneCount`/`audioTrackTotalHeightPx`).
   Pure render change, no model/storage change. Directly fixes the user's "music+narration on one audio
   timeline" pain and is the first concrete multi-track slice. Later folds into the AUDIO Track model.
1. Schema v6 + migration + `getClips()` shims (no UI change; everything still works).
2. Timeline UI: render master pinned + read-only collapsed floating rows (text overlays as a row).
3. Editing floating items on their rows (move/trim/delete, caret collapse).
4. GL preview compositor (the big one) — multi-layer live preview + scrub.
5. Export mapping for N layers.
6. Drag-between-layers + "drop to new layer" (ties to A5).
7. Ripple/gap toggle + later "pin to master" linking.

---

## Suggested build order
1. **B2** (persistent insert-arrow) + **Q2** (transition blue) — small, high annoyance-reduction.
2. **B1** (image link breakage) — data integrity; copy-into-project approach.
3. **A1/A2** (resizable + persistent asset panel) — foundation for the insert UX.
4. **A3/A4** (tap-insert affordance + long-press rename).
5. **Q3** (scrub transition preview).
6. **A5** (drag-and-drop) — depends on A1–A3.
7. **Layers** — its own milestone, after the above stabilize.
