# RELAY REPORT — JoyRaptor-account window, started 2026-07-01 ~23:45 (updates in place as milestones land)
> For the next AI (Basil autonomous wake 03:26, or anyone else). Read this FIRST, then handoff.md.

## ⚠️ CRITICAL STATE FACTS
1. **A git STASH exists** on `joy-creator`: `WIP caption-style-keyframe UX - interrupted by usage cap 2026-07-01 23:33`.
   The previous session's caption-style-keyframe-UX agent was killed mid-surgery (FaditorEditorActivity was left
   missing whole methods + string resources → tree was RED). I stashed it (incl. untracked `faditor/captions/`) to
   restore green. **Do NOT `git stash pop` blindly** — it will conflict with Layers work on FaditorEditorActivity/Clip.
   Treat the feature as NOT DONE; redo it fresh later (use the stash as reference only). It stays in the queue.
2. Tree was verified GREEN at `ff39b6a` (carousel v2) after the stash, 2026-07-01 ~23:50.
3. **No device attached all night** (checked adb repeatedly). Everything this window is COMPILE-GREEN only;
   device work is batched below.
4. Reminder: build.log "BUILD FAILED" from `installDefaultDebug ... No connected devices!` is GREEN — only
   `compileDefaultDebugJavaWithJavac` matters. Never run gradle; the watcher builds on save. NEVER git push.

## THIS WINDOW'S PLAN (user directive: "jump straight onto Layers, the keystone")
Executing tasks/PLAN_LAYERS_V2.md in order, SKIPPING M-COMP-0 (its Part-10 probe #2 — ClippingConfiguration on
remuxed fMP4 — REQUIRES a device before the engine is built; do not build it blind):
- 🔄 **M5** (Opus agent): schema v8 Track/TimedItem + migration + shims + dual-write + downgrade guard. IN FLIGHT.
- then **M6** (multi-row timeline UI, Sonnet) → **M7** (edit floating items, Sonnet) → **M-COMP-1** (View-stack
  layer preview, Sonnet), as far as the usage window reaches. Checkpoint commit after each green milestone.

## M5 DEVICE-GATE DEBT (IMPORTANT — first device session must run this BEFORE M6+ features are trusted)
PLAN_LAYERS_V2 §5.3: export a real sandbox project BEFORE-M5 vs AFTER-M5 → must match (duration + frame
spot-checks + audio RMS). Pre-M5 baseline = checkpoint `ff39b6a` (build APK from it if needed). Also:
save/reload round-trip via `run-as cat project.json`, undo-after-trim, downgrade-guard behavior.

## FULL DEVICE-VERIFY BATCH (when ANY phone is attached; sandbox Note 9 SANDBOX_SERIAL preferred; NEVER the real project 27221664 / phone REAL_SERIAL)
1. M5 regression gate (above) — FIRST.
2. Carousel edit v2 (`ff39b6a`): full interaction pass + fix known conflict (in edit mode, mute/opacity long-press
   fires BOTH drag-pickup AND old long-press action — suppress tool long-press actions while in edit mode).
3. Export filename dialog (both storage modes), transcript header on small screen, compact-bar rotate
   slim/flush, export top-stripe fill/animate/hide (all from 2026-07-01 batch, see handoff.md).
4. Caption apply-to-all + undo history popup (c3c0c03).

## QUEUE AFTER LAYERS MILESTONES (unchanged from previous session's ordering)
caption style keyframe UX (REDO — see stash note) → rebrand pass 1 (DESIGN_JOY_CREATOR.md; de-politicize,
KEEP long-press character mechanic) → transcript dedup (timestamped project.json backup FIRST, sandbox-verify)
→ M-COMP-0 (device required) → continue PLAN_LAYERS_V2 sequence (M-EXPORT-1 …).

## LANDED THIS WINDOW (updated as I go)
- (2026-07-01 23:50) Recovery: stashed broken caption-keyframe WIP, tree green at ff39b6a.
- (2026-07-02 ~00:15) **M5 LANDED, compile-green** (Opus). New `faditor/layers/` (TrackKind, BlendMode, Track,
  TimedItem); Timeline +98 (rippleMode + ephemeral synchronized-from-flat Track views — flats stay in-memory
  storage of record, views rebuilt per access so APIs can never diverge); FaditorProject +33 (SCHEMA_VERSION=8,
  loadedFromNewerVersion); ProjectStorage +174 (dual-write: v7 flat lists byte-compatible + additive
  `timeline.layers` block; stamps 8 ONLY via `usesLayerFeatures()` ProjectStorage:694-718; downgrade guard
  ProjectStorage:1216-1227 — fixed pre-existing double-stamp bug, save()/saveAsync() REFUSE to write
  newer-version projects). ExportManager/call sites: ZERO changes. Undo snapshots carry full v8 block.
  ⚠️ M6/M7 NOTE: track flags/blend/transform serialize but rebuild-as-default — mutating UI must add a
  persistent home (side-table keyed by track id suggested). On-device regression gate owed (see above).
- (2026-07-02 ~00:45) **M6 LANDED, compile-green** (Sonnet). Multi-row timeline: new `layers/LayerRowRenderer.java`
  (419 lines, ALL rendering/measure/hit-testing) + `layers/TrackFlags.java`; EditorTimelineView +111 lines of
  delegation ONLY; Timeline `trackFlags` side-table (persisted via ProjectStorage `restoreTrackFlags` ~739,
  applied in all 3 view builders ~551-616) — M5's ephemeral-flags gap CLOSED; toggles undo as one LambdaAction
  each (FaditorEditorActivity.onTrackHeaderAction ~8279); plain single-track projects: zero new rows/height/
  behavior. NOTE: rows default EXPANDED (collapsed-by-default would flip usesLayerFeatures→v8 for every project).
  Hide/lock/mute take effect at TIMELINE level only; preview/export wiring = M-COMP-1/M-EXPORT-1 (TODOs at
  each toggle site). Device checklist: caret collapse, toggle icons/dim/lock-swallow, row-region vertical
  scroll, pinned headers under horizontal scroll, toggle undo, plain-project zero-change.
- (2026-07-02 ~01:15) **M7 LANDED, compile-green** (Sonnet). `layers/LayerGestureController.java` (324 lines, all
  gesture logic) + LayerRowRenderer `hitTestItem` (+65); EditorTimelineView +65 delegation-only;
  FaditorEditorActivity +162 (undo recording + delete confirmations + refresh glue). Gestures mutate PERSISTENT
  payloads only (text: start/end shifted or trimmed w/ 250ms min; audio: offsetMs move, in/out trim mirroring
  doAudioTrimDrag); undo = one step per gesture via existing action types; long-press item = existing delete
  confirmation flows; locked/hidden rows return null from hit-test (inert); master row structurally excluded.
  Device checklist in agent report §6 (drag/trim/delete text+audio on rows, undo each, locked/hidden inert,
  master unaffected).
