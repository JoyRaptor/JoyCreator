# RELAY REPORT — JoyRaptor-account window, started 2026-07-01 ~23:45 (updates in place as milestones land)
> For the next AI (Basil autonomous wake 03:26, or anyone else). Read this FIRST, then handoff.md.

## ⚠️ CRITICAL STATE FACTS
0. **THE SANDBOX NOTE 9 (SANDBOX_SERIAL) CAME BACK ON USB ~01:45** — device work unblocked. Note: gradle's
   `installDefaultDebug` can fail with a transient ADB `EOF` even when a device IS attached — that is NOT a
   compile failure; `adb install -r` manually works. If a late-window agent was doing the M5 regression gate
   (branch-dance: checkout ff39b6a → export → checkout joy-creator → export → compare), the tree might be left
   DETACHED at ff39b6a if it was killed mid-run — `git -C <proj> checkout joy-creator` restores it; check
   `git status`/`git log -1` FIRST.
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

## ✅ M5 REGRESSION GATE: **PASSED** (2026-07-02 ~02:10, Note 9)
ff39b6a vs 01d0d66 sandbox exports: ALL 621 frames byte-identical (PSNR inf, MSE 0), raw video md5 identical,
raw PCM md5 identical, RMS curves identical to 6dp, durations equal to the sample. Evidence + MP4s/frames in
the session scratchpad (see agent report). Old-build save strips the layers block; current build re-materializes
it losslessly — dual-write working as designed. Tree ended clean on joy-creator @ 01d0d66, build green,
4.0.0-beta9 installed on the Note 9. Sandbox project untouched (6 clips intact).
Still owed on-device: undo-after-trim spot check; downgrade-guard behavior (hand-stamped v9 project → read-only).

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
- (2026-07-02 ~01:50) **M-COMP-1 LANDED, compile-green + PARTIAL DEVICE PASS** (Sonnet; Note 9 reappeared
  mid-run). New `compositor/LayerPreviewController.java` (stateless: visibleTextOverlays / visibleImageItems /
  effectivePreviewVolume) + `compositor/LayerImageOverlayView.java` (inert until M10 creates IMAGE tracks);
  hidden TEXT track filtered at all 11 overlayLayer.setData sites; muted AUDIO track gates all 6 volume sites
  (multiplies clip-level, incl. the off-plan volume-drawer mute button the agent caught) + live push
  `applyAudioTrackMuteLive` ~8385; transform via existing KeyframeSet.valueAt. DEVICE-VERIFIED: launch, plain
  no-op, mute toggle + undo + on-disk round-trip, v7 stamp preserved. Still owed: interactive hide-tap pass,
  image-item render (needs M10).
- (2026-07-02 ~02:10) **REGRESSION GATE PASSED on Note 9** (Opus; details in the GATE section above). Layers
  foundation (M5→M-COMP-1) provably did not change single-track export output. M-EXPORT-1 is now PERMITTED
  by the plan when its time comes (after M10 makes layer content creatable).
- (2026-07-02 ~02:20) M-COMP-0 first attempt was killed by the usage cap 7s in (no edits); relaunched ~07:30
  after refresh; that run was then STOPPED mid-implementation for an urgent user hotfix (below) — its partial
  engine WIP was stashed and later cleanly re-applied.
- (2026-07-02 ~morning) **URGENT USER HOTFIX LANDED b6c2a0c + INSTALLED on S10e AND Note 9**: the caption
  hide pill (eye-slash) now applies to all clips on long-press like the style chips
  (showHideCaptionsOnAllClipsDialog/hideCaptionsOnAllClips: captionsEnabled=false everywhere, keyframes
  untouched, ONE undo step). User's friend's S10e (R58M34STHCA) got it before being detached.
- **M-COMP-0 RESUMED after the hotfix**: engine WIP re-applied to the tree (MasterPlaybackEngine.java +
  FaditorPlayerManager/FaditorEditorActivity edits, uncommitted), agent relaunched to assess + continue.
  STASH NOTE: stash@{0} = a safety copy of that engine WIP (drop once M-COMP-0 lands); stash@{1} = the old
  caption-style-keyframe UX WIP (redo fresh later, reference only). Feature flag must default OFF unless the
  final report says full device acceptance passed.
- (2026-07-02 ~09:00) **M-COMP-0 LANDED — FULL DEVICE ACCEPTANCE, flag DEFAULT ON** (Opus resume). 0 frozen
  frames at all 3 seams vs legacy 1.79s/3.64s (ffmpeg freezedetect, same project, A/B); probe passed on REAL
  fMP4 remuxed sources; trim→rebuild→seam freeze-free, undo byte-identical; resume agent fixed 2 latent WIP
  bugs (stale currentClip after seam → syncGaplessCurrentClip; listeners lost on engine rebuild →
  attachRegisteredListenersToEngine at all 4 prepare sites). Flag: FaditorPlayerManager.GAPLESS_ENGINE:50.
  Eligibility auto-fallback: loops/transitions/image clips use legacy. USER FEEDBACK #9 = CLOSED.
  Known-weak (next device session): waveform-level A/V sync proof; live export round-trip + bg/fg resume in
  gapless mode; >100% LoudnessEnhancer boost path. Engine WIP safety stash dropped after landing.
