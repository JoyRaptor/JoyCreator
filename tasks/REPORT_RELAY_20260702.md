# RELAY REPORT — JoyRaptor-account window, started 2026-07-01 ~23:45 (updates in place as milestones land)

## ⚠️ FOR THE 14:25 SCHEDULED SESSION (written 13:15)
The JoyRaptor session is ACTIVE this afternoon and OWNS two tracks — do not collide:
1. **Layer-row touch fix** (user-reported: rows block scrub/select/trim; = handoff's "surface overlap" fix #2)
   — agent running with a 14:15 hard stop; symptoms + status in tasks/FEEDBACK_20260702_layers_masking.md §A.
2. **Loop/ping-pong track** per NEW tasks/PLAN_LOOP_PINGPONG.md (Fable-diagnosed: 5 root causes incl.
   preview `setPlaybackSpeed(-1f)` = invalid in Media3, export reverse leg = forward-tail fake, and one looped
   clip disabling gapless project-wide). L1 (Sonnet) launches when the fix agent lands; L2 (Opus) after.
YOUR SAFE QUEUE at 14:25: purple linkage (if the fix agent's report says it didn't cover it), rebrand pass,
Tier-1 durability, transcript dedup (backup first), M11. CHECK `git status` before ANY FaditorEditorActivity /
EditorTimelineView / MasterPlaybackEngine / ExportManager edit — if dirty, the JoyRaptor track is mid-flight; pick
a disjoint item. NEW USER SPECS captured in tasks/FEEDBACK_20260702_layers_masking.md §B (masking / chroma key
/ video-as-alpha) — planning-only, do not build blind.
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
- (2026-07-02 ~10:15) **M10 LANDED — drag-between-layers + drop-to-new-layer, compile-green** (Sonnet resume
  of `0cf3a4c`'s WIP, which was already ~95% done). WIP had: `LayerTrackDef.java` (new persistent track defs),
  `Timeline` layerId-grouping + `extraLayerTracks`, `LayerRowRenderer` cross-row highlight + "+ New layer"
  zone (drawn BELOW the last row — the plan's "or a dedicated drop zone" branch, not an above-top-row zone),
  `LayerGestureController` hover-target + same-band/locked/hidden rejection, full `ProjectStorage` v10 field
  serialization, and the real pre-existing `onScroll` guard fix (row gesture flags now bypass the gesture
  detector) — all kept as-is. **Fixed:** the WIP recorded position-change and track-change as TWO separate
  undo pushes for a diagonal drag (violates acceptance (d) "ONE undo step"); flipped the callback order in
  `LayerGestureController.onRowBodyUp()` so the track-change callbacks fire BEFORE `onGestureFinished` and
  stage their undo/redo into `FaditorEditorActivity.pendingLayerTrackUndo`, which `onGestureFinished` now
  folds into one `mergedAction()` (also fixed a silent-drop edge case: track-only change with no position
  change). Files touched beyond the WIP: `layers/LayerGestureController.java` + `FaditorEditorActivity.java`
  only — zero model/storage changes, v8-stamping untouched. Device-verified (cheap): tap-scrub + swipe-pan on
  the master timeline both scrub correctly post-fix (onScroll guard confirmed regression-free — M6/M10 flags
  never engage for plain projects); no crashes; sandbox `project.json` ground truth pulled (v8, one text +
  one locked audio track, `trackDefs:[]`, clean baseline). The ONE scripted drag attempt missed the target
  row (landed on timeline scrub instead) — confirmed zero mutation via before/after JSON diff, not iterated
  on per the one-attempt rule. Full hand-test checklist in this session's final report + `handoff.md`.
  **Owed:** the actual cross-row drag / drop-to-new-layer / locked-row-rejection gestures are UNCONFIRMED on
  a real finger — next device session should run the checklist below before this milestone is called fully
  device-accepted.
- (2026-07-02 later) **Caption-style-keyframe UX REDO LANDED, compile-green, FULLY device-verified** (Sonnet).
  The `01d0d66` M-COMP-1 commit had already landed most scaffolding (model, undo action, drawer XML, arm/nav/
  delete wiring, CC-lane per-segment coloring) — confirmed via `git log` this predates and is separate from
  stash@{1} (the actual killed WIP, left untouched). Real gaps closed: `Clip.captionStyleId` now resyncs to
  keyframe[0]'s style on every keyframe-list mutation (fixes a stale-base-style bug on first-keyframe delete —
  spec's explicit "next keyframe's style extends back" requirement); tolerance 40ms→50ms; nav `<`/`>` now
  dim/disable per-direction independently (were show/hide as a pair); new `captions/CaptionStyleKeyframeController.java`
  (stateless nav/tolerance/tap-action helpers, new-features-new-files compliant); new stopwatch shortcut in the
  bottom caption-style chip bar (`caption_kf_arm_shortcut`) so keyframe mode is reachable without a CC-lane
  long-press — same arm state/drawer, no duplication. Device-verified on Note 9 sandbox (`bdd51919…`): arm
  toast+tint, drop/replace/remove all confirmed via `project.json` `t`/`s` field diffs across multiple ops on
  two clips, nav-lands-exactly-on-keyframe + dim-at-ends confirmed via `uiautomator dump` `enabled`/`alpha`,
  first-keyframe-delete-extends-back confirmed via chip highlight + CC-bar color flip screenshot, undo
  byte-exact across DROP/REPLACE/REMOVE (counter + JSON both checked each step), CC-bar two-tone coloring +
  diamond markers confirmed via screenshot (this part was pre-existing, not newly built). One caveat noted
  (not fixed, out of scope): very-high-speed clips (6.5x tested) can nav-land outside the ±50ms tolerance due
  to timeline→source seek quantization amplification — pre-existing seek-pipeline characteristic shared with
  the opacity/volume keyframe drawers. Files: `model/Clip.java`, `FaditorEditorActivity.java`,
  `activity_faditor_editor.xml`, new `captions/CaptionStyleKeyframeController.java`. Full report in this
  session's final message.
- (2026-07-02 later still) **P0 "ruler snaps playhead to 0" — ONE real cause FIXED (compile-green), scope
  narrowed, TWO items diagnosed-not-built; session ended early (usage window).** (Opus.)
  **CONFIRMED root cause with device evidence:** the M-COMP-0 gapless SEAM handler was clobbering the playhead.
  Any cross-clip seek (ruler-scrub OR clip-body tap, both go through `onPlayheadSeeked`) makes ExoPlayer fire
  `onMediaItemTransition(REASON_SEEK)` → `onGaplessSeam`, which UNCONDITIONALLY re-homed the playhead to the
  target clip's START (`setPlayheadFraction(inPoint/sourceDur)`) a few ms AFTER the correct tapped position was
  already set. Target=clip 0 → snap to 0. **Evidence:** scripted 40-tap loop on Note 9 sandbox `bdd51919…` with
  temp `SNAPDBG` logging (removed): 10 seams, **2 forced playhead to exactly 0**, 4 more yanked it back up to
  3441ms — all `userDragging=false`. **This is NOT a raw ExoPlayer position-0 read** (that engine-transient
  theory is DENIED for the snap) — the 0 came from the seam re-home. **Fix (minimal, engine untouched, legacy
  flag intact):** `MasterPlaybackEngine.SeamListener.onSeam` gains an `autoAdvance` boolean (`reason==REASON_AUTO`);
  `onGaplessSeam` re-homes the playhead ONLY on auto-advance (playback through a cut), never on a user seek.
  Files: `compositor/MasterPlaybackEngine.java`, `FaditorEditorActivity.java`. **NOT re-verified on device
  post-fix** (after-fix run's taps all landed on the clip lane at start-of-timeline → 0 seams fired → didn't
  exercise it). **Coordinator's user-diagnosed mechanisms, verified in CODE but changes NOT made (out of time):**
  (1) clip-tap "seek to tapped x, keep selection" is ALREADY the code's behavior (`seekToTimelineMs(tappedX)` +
  `onSegmentSelected` which does not seek) — the "~1s snap" the user saw was almost certainly the same seam
  clobber, now fixed; RE-TEST before changing anything. (2) preview/workspace surface-overlap during a ruler
  DRAG (two surfaces eating one finger) — NOT investigated; find the preview scrub handler in
  `FaditorEditorActivity`, add first-claim-wins pointer ownership, measure the hit-zone gap. **Tree GREEN, no
  commit, all temp instrumentation removed (grep-verified). Full hand-test list in the session final message +
  handoff.md.** NEXT SESSION PICK-UP: re-run the tap/scrub loop scrolled to a mid-timeline boundary → expect
  zero snap-to-0; then decide if mechanisms 1/2 still need code.
