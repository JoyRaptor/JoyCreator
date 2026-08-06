# ✅ 2026-08-06 — FAST-FOLLOW A IS BUILT. The dope sheet owed since 2026-07-06 shipped with
#   SPEC_IMAGE_SEQUENCE (commit 960744a), built ONCE for sprites AND image sequences per that
#   spec's §0. It is the palette's third detent, where S3 always drew it. A sequence strips its
#   frames and edits their weights; a grid sprite strips its frame-track KEYS and can turn a
#   selection into a preset ("Make preset"). Read tasks/HANDOFF_20260806_IMAGE_SEQUENCES.md.
#   FF-B is partly there too: describe_sprite_sheet already existed; describe_sequence and
#   edit_sequence landed beside it. STILL MISSING from FF-B: set_sprite_grid, label_sprite_cells,
#   author_sprite_animation, apply_sprite_proposal.
#   ⚠ The "REMAINING" line further down this file is STALE about FF-A. Trust the code.
#
# 📊 BUILD-1 STATUS 2026-07-06 ~13:20 (Fable — DEVICE-VERIFIED this session, watcher live/green):
#   • Picked up opencode (out of credits): finished the audio-overlap P0 + committed a52965b —
#     addAudioClip(clip) auto-resolves; undo/redo/split-restore/deserialize routed to (clip,false)
#     for exact placement. Watcher rebuilt green (18s).
#   • SPRITE Build-1 is FUNCTIONALLY LIVE ON DEVICE (SM-N960U, project FadCam_20260621_145132):
#     setup editor made named cells; palette panel WORKS (◄▶ transport, ◄k/k► keyframe-nav, instance
#     selector [Sprite 1 | pangolin 2], ⚙ edit-sheet, trash); NAMED-cell carousel (0 dirt…5 ball);
#     tap-cell → toast "Swap dropped at playhead" + undo ticks (S5 keyframe-drop ✅); timeline SPRITE
#     lane renders amber bar "✦ 21" + frame diamonds (S5 lane visuals ✅ — the 07-05 "remain" note is
#     STALE, landed same-day); Flip H/V + end:hold present. S1–S6 all effectively done.
#   ✅ HAND-TEST PASSED (user 2026-07-06): sprite VISIBLY composites on video (pangolin enters ~13s).
#     S4 preview + S6 export compositing CONFIRMED. centerY=0.5 default lands in the letterbox — a
#     placement default worth revisiting (drop onto video content), NOT a bug.
#   ✅ T8 FIXED + DEVICE-VERIFIED (Fable 2026-07-06 ~15:20): multi-sprite-per-lane bug closed.
#     Timeline.spriteLayerIdFor(item)="sprite-"+item.id (DETERMINISTIC → idempotent, survives unsaved
#     sessions) + Timeline.migrateSpriteLayers() (splits any lane holding 2+ sprites; keeps the first,
#     moves the rest; run once in the saved-project load path). placeSpriteOnVideo() now stamps every
#     NEW sprite with its own spriteLayerIdFor lane. NO LayerTrackDef needed — getLayers()'s leftover-
#     bucket branch already surfaces each non-default layerId as its own buildSpriteTrack lane.
#     DEVICE PROOF (SM-N960U, project bdd51919): load logged "moved 1 overlapping sprite(s)"; timeline
#     now renders TWO "Sprite" rows (was one); project.json split s2→layerId sprite-81563c97…; a placed
#     3rd sprite got its own sprite-9a97698f… lane (then removed to restore JoyRaptor's 2-sprite content).
#     NOTE: migrated/leftover lanes render with the generic "Sprite" header name — per-lane naming
#     lands with the LAYERS-UX renderer consolidation (FEEDBACK_20260706 #1). Commit: b55b1cd.
#   ▶ NEXT — LAYERS-UX OVERHAUL BUILD. Gesture design pass is DONE (co-designed w/ JoyRaptor 2026-07-06).
#     ★ START A NEW SESSION WITH: tasks/BOOTSTRAP_LAYERS_BUILD_20260706.md (paste-ready prompt).
#     Authoritative design = tasks/PLAN_GESTURE_CONTRACT_FINAL_20260706.md; always-green slice
#     sequence = tasks/PLAN_LAYERS_UX_EXECUTION.md. Model: OPUS, high effort (Slice C + G1 = max).
#     Order: Slice A (caption/viz Track kinds) → B→C (consolidate renderers, delete old drawLayers) →
#     D (header hit zones + caption-chooser autohide) → E (vertical re-layout) → F (no-overlap all +
#     move-between-layers + lane consolidation) → G1–G9 (gestures/menu/keyframes/handles/overlays/
#     resizable-PiP/coach-marks/multi-select/linking). THEN sprite FF-A/FF-B, S7, S2b.
# ⚠ THE "REMAINING" LINE BELOW IS STALE — corrected 2026-08-06 by a line-by-line code audit.
#   ALREADY BUILT AND REACHABLE: S7 relink UI (sheet manager → Relink, red-tinted when missing);
#   S2b auto-detect grid, bg-key UI, onion skin, sidecar import/export, filmstrip (all in
#   SpriteSheetEditorActivity); FF-A dope sheet (sprite palette → ▦ chip).
#   GENUINELY STILL MISSING: FF-B's set_sprite_grid / label_sprite_cells /
#   author_sprite_animation / apply_sprite_proposal; the palette's per-track arm toggles (arming
#   exists, but in ObjectMenuSheet); FF-A's dope-sheet TRANSFORM rows + per-key easing
#   (DopeSheetView is frames-only; that capability lives in ObjectMenuSheet); sw600dp two-pane
#   (no values-sw600dp resource dir exists at all).
#   REMAINING (genuine NEW build — multi-session): S7 relink UI; S2b polish (auto-detect grid, onion
#     skin, bg-key UI, sw600dp two-pane, sidecar-export button, filmstrip); FF-A presets/dope-sheet UI;
#     FF-B AI sprite tools (AIToolExecutor has 0 sprite refs today). Do them in that order.
#
# ── prior status (2026-07-05, kept as history) ──
# 📊 BUILD-1 STATUS 2026-07-05 (JoyRaptor lane): S1 ✅ S2 ✅ S2b-core ✅(c924c9f) S3 ✅(ca496e7)
# S4 ✅(7df95e9) S6 ✅(f270f48) — S5 lane visuals + S7 relink remain; ALL of today's work is
# javac-verified but OWED watcher-green + on-device acceptance (watcher died 03:06, see handoff).

# ⚡ AMENDMENT 2026-07-03 (Fable, BINDING — read before the original plan below)
The original plan (2026-07-01) predates the Layers landing and says "build on the current overlay system,
migrate into Layers later." **That premise is obsolete — Layers SHIPPED (schema v8, Track/TimedItem,
gapless engine, full gesture contract).** Build 1 goes NATIVE on the Track model from day one; the
migration phase is DELETED. Code facts verified 2026-07-03:
- `layers/TrackKind.java:27` already declares `SPRITE`.
- `layers/TimedItem.java:56` reserves the payload socket: add `@Nullable SpriteOverlayItem sprite` as the
  FOURTH payload + `ofSprite()` factory + `payloadKind()="sprite"` + a `getDisplayDurationMs` branch
  (start/end semantics like text overlays, open-end → fallback).
- Storage pattern: model objects live in Timeline FLAT lists; Track/TimedItem are lightweight VIEWS
  (M5 shim — see TimedItem class doc). So `Timeline.spriteOverlays[]` mirrors `textOverlays[]`, and
  `Timeline.getLayers()` grows a SPRITE-track grouping branch mirroring TEXT (layerId-aware).
- Sprite rows inherit the ENTIRE 2026-07-03 gesture contract for free (tap-select, trash badge, pickup,
  bookend snap, home ghost) because it operates on TimedItem generically — but S5 must add
  `getDisplayDurationMs`/hit-test awareness and per-item rendering in `LayerRowRenderer` (cell-thumb strip).
**S1 execution checklist (concretized against real code):** new package `ui/faditor/sprite/`:
`SpriteSheet` (+cells +presets, sidecar read/write helpers), `FrameTrack` (STEP/HOLD — the one new
primitive, no interpolation), `SpriteOverlayItem` (mirrors `model/TextOverlayItem`: id, normalized
centerX/centerY, sizeFraction, rotationDeg, opacity, startMs/endMs, own KeyframeSet, local time base),
`SpriteFrameResolver` (ONE pure static `resolveCellAt(sheet, item, timeMs)` — preview/export/lane/AI all
call it; no other code computes a cell index). `FaditorProject.spriteSheets[]`;
`Timeline.spriteOverlays[]` + `hasSpriteOverlays()` (this getter is the S6 isSimpleTrim guard);
`TimedItem` 4th payload; ProjectStorage round-trip — follow the storage's established additive/tolerant
convention (CHECK how v8 handled unknown-field back-compat before deciding bump vs additive).
Accept: sprite data survives save/reload via `run-as` cat; a pre-existing no-sprite project round-trips
unchanged (M5-style regression gate).
**Model policy (user decision 2026-07-03): Fable 5 builds this feature personally** (vision-heavy tier —
same for AI integrations and UI/UX overhauls; small contained items may go to lesser models). Execution
shape: sequential milestones S1→S7, checkpoint commit per green milestone, device verify per runbook,
adversarial review workflows after S1 (model), S4 (preview gestures), S6 (export). NOT full ultracode —
implementation can't parallelize (one watcher tree, god-class files); reviews can and do.
**S2 design gate:** before building the setup editor UX, do a design pass against competitor workflows —
fold in tasks/RESEARCH_COMPETITOR_UX_20260703.md when it lands (in flight from another worker).

**S4 STATUS 2026-07-05: PREVIEW SHIPPED (7df95e9), device verify owed (watcher down).**
SpriteOverlayView in the preview stack (above video, below text/captions per the export rule):
resolver-driven cell, KeyframeSet transforms, drag/pinch with auto-keyframe-when-armed + snap
(TextOverlayLayer contract parity), MISSING placeholder, pass-through touches. SpriteOverlayItem
gained the animated* helpers + TransformSnapshot (one undo step per gesture).
LayerPreviewController.visibleSpriteItems is the SHARED hidden-track filter — S6 export MUST call it.
Interim placement path until S3: Sprites tool → tap sheet → "Place on video" (first enabled cell,
holds from playhead). S3 palette panel + S5 lane/keyframing remain next in Build 1.

**S2 STATUS 2026-07-04: CORE SHIPPED + DEVICE-PROVEN** (cb07ad7 + the onCreate-order NPE fix):
engine (SpriteSheetRenderer: decode-once, color-key-at-decode, cellRectSource geometry authority),
SpriteGridEditorView (zoom/pan/tap/pivot), SpriteSheetEditorActivity (import→slice→name→save), Sprites
carousel tool + sheet-list dialog. DEVICE PROOF (Note 9, adb-driven end-to-end): launched editor → OS
picker → picked star-guy from Downloads → grid 3x3→4x4 via steppers → named cell 0 "idle" → Save →
project.json shows schemaVersion 9 (conditional stamp correct), sheet with cols/rows 4/4, cell {0,"idle"},
sheetUri project://assets/<uuid>.png (bundle copy + relative-URI conversion working) → RELOADED by sheet
id: 4/4 + "idle" restored on screen. v9 write path + round-trip = PROVEN. S2b still deferred: auto-detect
grid, onion skin, bg-key UI, sw600dp two-pane, sidecar export button, filmstrip polish.

**RIG VISION (user 2026-07-03, binding design direction — shape for it, don't build it yet):**
keyframable transforms (scale/rotate/move — already in S1 via KeyframeSet) + a settable ANCHOR/pivot
point + PARENT LAYERS = full animation rigs: a head sprite parented to a body sprite, arm/hand-gesture
sprites inheriting the body's motion while swapping their own cells. "That's everything we need to make
some pretty powerful stuff." Model implications, all ADDITIVE to the S1 schema when their time comes:
(1) per-ITEM anchor override `anchorX/anchorY` (nullable → falls back to the sheet's pivotX/pivotY —
pivot already ships in S1); (2) `parentItemId` on SpriteOverlayItem + transform composition
parent∘child at evaluation time (a pure function beside SpriteFrameResolver — same
single-authority rule: ONE resolveTransformAt that preview/export/AI all call); (3) cycle guard +
orphan tolerance (dead parent = un-parented, never crash). Rig authoring UX and AI rig tools come
after Build 1 + fast-follow A; nothing in S1..S7 may paint us out of this — reviewers should flag
anything that would.

# Plan — Sprite Animation feature for FadCam/Faditor ## Context FadCam is an Android video editor (`C:\+Projects\Screenrecorder\FadCam`, package `com.fadcam.beta`, phones first, tablets supported). We're adding **sprite-sheet animation** as an overlay layer: upload a sheet, slice it into a named grid of cells in a full-screen setup editor, then animate *which cell shows when* on the existing timeline (plus the sprite's position/scale/rotation/opacity as a unit), with reusable loop/ping-pong presets and conversational AI authoring as fast-follows. Use ladder this must serve from one set of primitives: **trivial** (drop an avatar, auto-loop a 3-frame idle as an animated sticker) → **sweet spot** (anonymous-vlogger pose-to-pose avatar driven by the transcript) → **ceiling** (stacked sprites for richer scenes) → **future** (plugins: parenting, nested dope sheets). The real product is the **named, versioned, LLM-legible JSON model**; the setup editor, the palette, the timeline lane, the AI, and future plugins are all clients of it. Hard constraint: this is **integrated into the video editor, not a standalone animation app**. The user must see the video and sync frame keyframes to audio on the existing timeline. ## Decisions locked with the user 1. **Build 1 = core pipeline.** Presets and AI authoring are **fast-follows** (A, B below). 2. **Build on the current overlay system now**, shaped for easy migration into the pending Layers/Track refactor — do not block on Layers. 3. **Time lives only in the real timeline.** The bottom panel is a palette/dope-sheet, never a second timeline. Video is never covered by sprite UI. 4. **AI features are visible but gated** — tapping one without an API key opens the existing OpenRouter key dialog (same as chat assistant). Nothing hollow, fully discoverable. 5. Sprite z-order: **above video, below captions.** Multiple sprites among themselves: list order (newest on top); true reordering arrives with Layers. ## UI: one bottom panel, three detents (+ timeline lane + full-screen setup) **Sprites button** in the bottom tool row toggles a **single bottom panel** with three snap heights, reusing `AssetBrowserPanel`'s grab-handle + height-ratio persistence pattern (roadmap 4.1): - **Micro detent** — transport (play, ◄ ► frame-step, frame counter) + current-cell indicator only. For playback review; nearly zero footprint. - **Palette detent (default)** — horizontal carousel of cell chips (number badge + name, transparent render) and preset chips (loop/ping-pong icon); per-track arm toggles (Pos/Scale/Rot/Opacity); Flip H / Flip V; end-behavior (hold ▸ default / loop / ping-pong); sprite-instance selector; edit-sheet (⚙ → setup editor). Empty state = single `+ Load sprite sheet`. **Keyframe context chip:** when the playhead sits on a frame keyframe, show `[◄ nudge ►] [cell name] [delete]` — single-keyframe fixes without opening the dope sheet. - **Dope-sheet detent** — panel rises to cover the *timeline region* (phones): per-property rows (frame / x / y / scale / rotation / opacity) with draggable keyframe chips, easing per key, multi-select, group-move, delete. `timeline ⇄ dope` toggle. This is also where fast-follow A's "select keys → make preset" lives. **Timeline sprite lane** — `EditorTimelineView` gains a sprite lane (same mechanism as the existing caption/visualizer lanes): frame-keyframe diamonds (≥44dp touch targets), tap = jump playhead, long-press = delete, expanded ribbon variant shows cell thumbnails at each key. Dropping a swap = scrub the real playhead, tap a cell in the palette. **Full-screen setup editor** (`SpriteSheetEditorActivity`, separate activity; write-back via the established `signalModified` → editor reloads on resume pattern used by `ChatAssistantActivity`): grid overlay with numbered cells (3×3 default), cols/rows steppers, **margin + spacing controls**, reading order, default fps, tap-a-cell → name field + tags + enabled toggle, pivot point, optional **background color-to-alpha key**, **onion skin**, **play-to-preview filmstrip**, `auto-detect grid` button (algorithmic gutter scan now; AI vision joins in fast-follow B). Cell numbers are static; names/tags are aliases. **Tablets / large screens (`sw600dp`)** — same components, responsive arrangement: - Setup editor: two-pane (grid left; cell inspector + filmstrip right). - Dope-sheet detent coexists *below* the timeline instead of covering it. - Palette shows cell name labels persistently; carousel can wrap to two rows. ## Data model (the spine — versioned, documented, LLM-legible) New package `com.fadcam.ui.faditor.sprite`. **`SpriteSheet`** — definition, stored at **project level** (`FaditorProject.spriteSheets[]`) and exportable/importable as a **standalone sidecar** `<name>.sprite.json` next to the PNG in the pinned assets folder (this is the sharing format, the plugin contract, and what lets the AI "find the sheet and the JSON in the project"): ``` schemaVersion, id, name, sheetUri (ORIGINAL source, project://-relative — never a cache path), cols, rows, marginX, marginY, spacingX, spacingY, order ("row-major" default), fps (default cadence), bgKeyColor + keyTolerance (nullable), pivotX, pivotY (0..1, default .5), cells: [ { index, name, tags[], enabled } ], presets: [ { id, name, type ("loop"|"pingpong"|"once"), fps?, frames:[cellIndex...] } ] // fast-follow A ``` **`SpriteOverlayItem`** — a placed instance (`Timeline.spriteOverlays[]`). Mirror `model/TextOverlayItem.java`: normalised `centerX/centerY`, `sizeFraction`, `rotationDeg`, `opacity`, `startMs/endMs`, reusable eased `KeyframeSet` for whole-unit transform, **local time base** (`timelineMs - startMs`) exactly like text overlays. Plus: ``` sheetId, flipH, flipV, endBehavior ("hold" default | "loop" | "pingpong"), frameTrack: [ { timeMs, ref:{cell:N} | {preset:"id"} } ] // DISCRETE — held until next entry ``` **`FrameTrack`** — the one new primitive. Modeled on `keyframe/KeyframeTrack.java` but **step/hold, no interpolation**. Discrete frames vs. eased transforms is the core correctness split. **`SpriteFrameResolver`** — a single **pure static function** `resolveCellAt(sheet, item, timeMs) → cellIndex` that owns ALL frame math: step-hold lookup, preset phase (starts at the entry's `timeMs`, advances at preset fps, wraps by loop/ping-pong), and `endBehavior` after the last entry. Preview, export, the timeline lane, and AI validation all call this one function — preview/export divergence is impossible by construction. No other code computes a cell index. **Layers prep:** items carry `timelineStartMs` + a z-order hint and are fully self-contained, so the future Track migration (`tasks/PLAN_asset_browser_and_layers_EXECUTION.md` M5) drops them into a `SPRITE` layer without reshaping — same path `textOverlays` will take. ## Reuse map | Concern | Reuse / extend | |---|---| | Eased transform keyframes | `keyframe/KeyframeSet.java`, `KeyframeTrack.java`, `Easing.java` | | Overlay item shape, auto-keyframe-when-armed, time-range, local time base | `model/TextOverlayItem.java` | | Preview compositor pattern (drag/pinch/playhead/snap) | `overlay/TextOverlayLayer.java` → new `SpriteOverlayView` custom View blitting `canvas.drawBitmap(sheet, srcRect, destRect, paint)` (ImageView can't sub-rect) | | Full-screen grid editor drawing/touch | `crop/CropOverlayView.java` | | Bottom panel drag handle + height persistence | `assetbrowser/AssetBrowserPanel.java` (roadmap 4.1) | | Timeline lanes (tap, long-press, expanded row) | `timeline/EditorTimelineView.java` | | Export compositing | `export/CompositeExportOverlay.java` + `SpriteFrameResolver` | | Undo | `undo/UndoManager.java` / `EditAction` — keyframe ops, sheet edits, preset ops all register | | Keyframe time snapping | `TextOverlayLayer`'s `TIME_SNAP_MS` pattern (snap to other keys + frame boundaries) | | Cross-activity write-back | `AIChatState.signalModified` + reload-on-resume (ChatAssistantActivity pattern) | | JSON round-trip + schema bump | `project/ProjectStorage.java`, `FaditorProject.SCHEMA_VERSION` (additive) | | AI tools, descriptions, dispatch, proposal cards, key gating | `ai/AIToolExecutor.java`, `EditScript`/`EditScriptApplier`, chat assistant's OpenRouter config | | Missing-media safety | existing MISSING-not-black + relink catalog + consolidate (handoff B1, `RelinkCatalogBottomSheet.java`) | ## Correctness rules (each maps to a documented lesson in `tasks/lessons.md`) - **`isSimpleTrim` fast path MUST exclude sprite overlays** (`hasSpriteOverlays()` in the guard) — the documented "fast-path must respect slow-path feature set" bug class; without this, a single trimmed clip with only a sprite exports with the sprite silently missing. - **Export overlay returns a fresh Bitmap per frame** (generation-id cache lesson). - **Decode the sheet once, bounded** (`inSampleSize` to a max texture-safe size), blit sub-rects from the single bitmap; tiny per-cell thumbs for the carousel; LRU across multiple sheets; recycle per the existing CompositeExportOverlay pattern. - **bg-key color-to-alpha is applied once at decode time** to the shared bitmap — preview and export see identical pixels; no duplicate shader paths. - **`presentationTimeUs` is timeline-absolute**; convert to item-local before resolver/keyframe lookups. - **Effect order fixed**: sprites composite inside the `OverlayEffect` step (below captions within `CompositeExportOverlay` draw order), before opacity/presentation (`assembleClipVideoEffects`). - **Never persist cache/decoded/remux paths** — original `project://`-relative URI only. ## Build phases (milestones with device-verified acceptance, repo style) ### Build 1 — core pipeline - **S1 Model + storage.** Classes above, `ProjectStorage` round-trip, sidecar export/import, additive `SCHEMA_VERSION` bump. *Accept:* sprite data survives save/reload (`run-as` cat `project.json`); a pre-existing no-sprite project exports byte-equivalently before/after (M5-style regression gate). - **S2 Setup editor.** `SpriteSheetEditorActivity` full feature list above incl. margins/spacing, auto-detect (gutter scan), naming, pivot, bg-key, onion skin, filmstrip; two-pane on sw600dp. *Accept:* on-device, slice a real downloaded sheet with gutters; names persist; filmstrip plays. - **S3 Palette panel.** Sprites button + three-detent panel (micro/palette; dope detent lands in FF-A), empty `+ Load` state, arm toggles, Flip H/V, end-behavior, keyframe context chip. *Accept:* video never obscured; detents snap + persist height. - **S4 Preview.** `SpriteOverlayView` below captions: resolver-driven cell, eased transforms, drag/pinch with auto-keyframe-when-armed, snap. *Accept:* scrub + play shows correct cell + transform live. - **S5 Timeline lane + keyframing.** Sprite lane + expanded ribbon; tap-cell-at-playhead drops a swap; undo/redo covers every op. *Accept:* drop two swaps synced to an audio beat, nudge one via context chip, undo/redo both. - **S6 Export.** `CompositeExportOverlay` + resolver; `isSimpleTrim` guard extended. *Accept:* export a clip with a sprite; `ffmpeg` frame extraction shows the same cell/transform as preview at the same timestamps; single-trimmed-clip-with-sprite does NOT take the fast path. - **S7 Missing-sheet safety.** Dead sheet URI → MISSING placeholder (never black/crash) in preview, palette, lane; relink via existing catalog. *Accept:* revoke/rename the source, editor shows MISSING, relink restores. ### Fast-follow A — presets + dope sheet Dope-sheet detent (per-property rows, multi-select, group-move, easing); select contiguous frame keys → **make preset** (loop/ping-pong/once, fps); preset chips in carousel; preset edit/rename/delete/speed. Mirroring a running preset = Flip H on the instance (free — the frame track is untouched; the mirror is the output transform). ### Fast-follow B — AI authoring (visible but key-gated) Tools in `AIToolExecutor` + `getToolDescriptions()`: `describe_sprite_sheet` (reads sheet + sidecar, narrates "I understand this is a 4×4 of expressions…"), `set_sprite_grid`, `label_sprite_cells`, `author_sprite_animation` (transcript-driven pose-to-pose with creative interpolation), `apply_sprite_proposal` (atomic, undoable, propose-then-confirm like narrative/b-roll). Sprite sheets, cell names, and presets included in `get_project_state`. Vision-assisted grid detect + cell labeling. Every AI entry point checks for a configured key and opens the existing key dialog if absent. The four autonomy levels (read-back / recommend / execute-on-go-ahead / full-auto) are all just conversation over these tools — no new chat UI. ## Verification workflow (per `tasks/DEVICE_CONTROL_RUNBOOK.md`) - Agent cannot run Gradle (sandbox loopback); user runs the watcher — poll `build.log` (UTF-16: `tr -d '\000' < build.log | tail -40`) for the FINAL `BUILD SUCCESSFUL` before device tests. - Ground truth via `adb shell run-as com.fadcam.beta cat files/faditor/projects/<id>/project.json`. - UI verify via `adb exec-out screencap` + coordinate taps; export verify via pulled MP4 + `ffmpeg` frame extraction (DIAG_20260626 methods). - Always-green: never leave the tree non-compiling; update `tasks/handoff.md` + `tasks/road_map.md` after each verified milestone; no commits unless asked. ## Out of scope (now) Tier-3 full-screen studio, sprite parenting, nested dope sheets, plugin API surface, sprite z-reorder UI (waits for Layers). The model is shaped so all of these are additive later.