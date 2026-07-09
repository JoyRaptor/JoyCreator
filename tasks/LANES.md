# LANES.md — live file-lock board for parallel agents
#
# ★ CURRENT PRIORITY TRACK: Layers/Timeline UX overhaul BUILD (design DONE 2026-07-06).
#   New session? Start with tasks/BOOTSTRAP_LAYERS_BUILD_20260706.md (paste-ready prompt).
#   Design contract = tasks/PLAN_GESTURE_CONTRACT_FINAL_20260706.md; slices = tasks/PLAN_LAYERS_UX_EXECUTION.md.
#   Model: OPUS high effort. Begin at Slice A (caption/visualizer Track kinds).

Two agents may work this repo at the same time (Fable/Claude + opencode). This file is
how they avoid clobbering each other. Protocol — no exceptions:

1. **Before EVERY task**, read this file fresh. If any file your task touches appears in
   the OTHER agent's `files:` list while its `status:` is ACTIVE, **SKIP that task**
   (note "skipped: lane conflict" in your progress log) and take the next task whose
   files are free. Come back to skipped tasks later.
2. **Before your first edit of a task**, update YOUR section: set `status: ACTIVE`,
   the date/time, and the exact files you will touch. Save this file.
3. **After the task's commit** (or when you abandon it), set your section back to
   `status: IDLE` and clear the files list. Never leave a stale ACTIVE claim when you
   stop working — a dead claim blocks the other agent for hours.
4. This file is coordination state, not history — keep each section tiny, overwrite it
   freely, and DO NOT commit it with unrelated changes (committing it is optional;
   the on-disk state is what matters).
5. `git status` is the fallback truth: if the other agent's claimed files are dirty in
   the tree, treat the claim as live even if the timestamp is old.
6. **SOLE BUILDER: never run gradle/gradlew.** The user's watcher is the ONE builder.
   Compile-verify by SAVING and reading `build.log` for a fresh `BUILD SUCCESSFUL`.
   Two gradle processes corrupt the resource merge (missing `.flat` → "100 class R
   errors"). If `build.log` won't update, STOP and tell the user — never invoke gradle.
7. **DEVICE is single-owner.** Before ANY device command (`am start`, `input`,
   `screencap`, `screenrecord`, `logcat`), read the `DEVICE:` token below. Take it only
   when it reads `free` (or the holder is IDLE): set `DEVICE: <you>`, do your batch,
   then set it back to `free`. Never drive the device while the other agent holds it.

## DEVICE TOKEN
DEVICE: Fable (2026-07-08 crunch — device unlocked by JoyRaptor; running the audio-consolidation +
  G5a smoke checklists, then fixing the audio-band clipping bug found during smoke.)
(Fable owns it during hard interactive device-loops; opencode does code+compile-only
and BATCHES its device-verify into windows when this reads `free` / Fable is IDLE.)

**Standing (permanent) Fable locks — never touch regardless of this board:**
`export/ExportManager.java`, `export/BlendModeGlEffect.java`, `export/PipFrameOverlay.java`,
`export/CompositeExportOverlay.java`, `project/ProjectStorage.java`,
`compositor/MasterPlaybackEngine.java`, `compositor/OverlayVideoPreviewView.java`,
`compositor/LayerPreviewController.java`, `compositor/DecoderBudgetProbeActivity.java`,
`avatar/PuppetPoseResolver.java`, `avatar/AvatarRig.java`, `avatar/PinWarpStrip.java`,
`avatar/DangleSim.java`, `avatar/PuppetPreviewView.java`, `avatar/FabrikSolver.java`.

---

## FABLE (Claude) — dynamic lane
status: ACTIVE (2026-07-09 crunch, Opus. Landed + committed this session:
        • 9b37f99 audio-band clipping fix — DEVICE-VERIFIED (both audio rows fully visible even
          under a compressed view; was: bottom band clipped off-screen when view height-constrained).
        • 2593bdb GL-transition card baker (Opus subagent) — P0 NATIVE-ABORT CRASH FIXED
          (glGetShaderInfoLog(int) → invalid-UTF-8 → NewStringUTF CheckJNI abort, uncatchable).
          DEVICE-VERIFIED editor opens clean. Feature INERT: shaders fail headless compile on
          SM-N960U → cards keep proxy (no regression). Follow-up documented in class javadoc.
        • bca07d3 G9 link-engine design doc (Sonnet subagent, design only).
        NEXT: G5a attach/detach device verify (owed), then G9a plumbing if budget.)
files: (releasing timeline/layers files; may take FaditorEditorActivity for G5a verify only)
prior-status: IDLE (2026-07-07 continuation session wrapped). Landed + committed this session:
  • 5b492e0 G1-groundwork: held-item MOVE reaches hidden lanes (M6 vertical edge-scroll) +
    honest drop-target affordance. Drag-FEEL hand-test owed (see handoff checklist).
  • 45f6c52 Slice F PiP no-overlap → invariant COMPLETE all types (text/img/sprite/audio/PiP).
    Verified by a 14-case logic harness.
  • bf7b63e G6.1 resizable timeline (grab bar) — DEVICE-VERIFIED both directions + persistence.
  • dfe868c + 1564b2f DURABILITY (road_map Tier-1): extracted audio → getFilesDir (survives OS
    cache wipe) + load-path migration rescuing existing cache-path audio. DEVICE-VERIFIED.
  • e004e80 docs: preserved authoritative design contracts in git.
  NEXT SESSION (want JoyRaptor present for drag-FEEL iteration per the hand-test rule): the UX gesture
  state machine — G1 proper (hold/tap/double-tap) + G2 menu + G3 keyframes + G4 preview handles.
  These are coupled + FEEL-gated; building blind risks regressing the tap/scrub/pickup that work.
  Other safe road_map: audio old→unified-row consolidation, dead-code cleanup (drawLayers/
  selectedLayerKind — unblocked now Slice F landed), perf (MMR width/height memoize).
  --- prior ---
  SLICE F: F1 + F2 done, device-verified.
  F1 (98c5a76): Timeline.enforceNoOverlapTextLanes() — text can't overlap on a shared lane (smart
    packing, idempotent, runs at load). Verified: separated 2 overlaps, 0 remain, butted pair kept.
  F2 (97ec804): "Compact lanes" tool-row button (JoyRaptor chose tool-row). Timeline.compactOverlayLanes()
    greedy-packs ALL text+sprite into fewest no-overlap lanes; compactLayers() = one undo step +
    refreshAfterLaneChange() persists compact/undo/redo. Verified: text 5 lanes→3 (0 overlaps), toast,
    undo reverts, persists. PiP/image compaction deferred (locked subsystem, needs 2+PiP test project).
  SLICE F REMAINDER (breakdown):
    - No-overlap invariant status: TEXT ✓ (F1), SPRITE ✓ (T8 one-per-lane), AUDIO ✓. PiP/IMAGE
      (overlayClips) NOT yet — same pattern (enforceNoOverlapVideoLanes via getOverlayStartMs +
      getEffectiveDurationMs, "video-<id>" lane) BUT: PiP is a standing-locked complex subsystem and the
      test project has only 1 PiP clip → couldn't test multi-PiP overlap. Do with a 2+PiP test project.
    - COMPACT LANES (JoyRaptor's CapCut-pain headline want): a generic packing that merges ALL items of a
      type across lanes into the FEWEST no-overlap lanes (same greedy algo as F1 but across all items,
      not per-lane). Model method is easy; BLOCKED on a UI decision — WHERE does the "compact lanes"
      control live (tool-row button? row-gutter action? gesture?). Ask JoyRaptor before building the trigger.
    - MOVE-BETWEEN-LANES: the M10 cross-row drag machinery already exists + is generic
      (LayerGestureController resolveNoOverlapStart / onItemMovedToTrack / onItemDroppedOnNewLayer). Needs
      a device HAND-TEST that it works for text/image/sprite/PiP (drag gesture → one scripted attempt +
      numbered checklist per the rules), not more code (likely).
  --- earlier this session (all always-green + device-verified on SM-N960U): ---
  ✓ Slice E vertical re-layout (7e89fb5 centralize, 4608da1 flip): ruler → LAYERS → MASTER → AUDIO.
  ✓ Master filmstrip delineation (84b570b) + refinement (3409931): sprocket frame TRAVELS with scroll,
    green trim handles paint in FRONT of the film, handles narrowed 14→11dp. (JoyRaptor feedback loop.)
  ✓ Accurate waveform W1 (38b29ee): peak-preserving extraction (max not mean, ~60 bins/sec) + peak
    render (max-of-range not point-sample) + perceptual .6. Existing clips render better now; new
    extractions get accurate peak data. W2 HD-zoom tier still open (FEEDBACK_20260703_timeline_fidelity).
  NEXT (JoyRaptor's "quick wins first" → then the big audio work, see FEEDBACK_20260706_audio_and_delineation.md):
    #3 audio as a full multi-track editor (stack/arrange/move like layers, dual-scroll band below master;
    needs the ~9 getSelectedAudioIndex ops re-anchored to selectedItemId) — the multi-session backbone;
    #4 audio-only export (new ExportManager mode). Then Slice F / G-series.
  --- prior (still valid) ---
  Slice E vertical re-layout DONE + device-verified (SM-N960U). 2 always-green commits:
        7e89fb5 Step 1 (centralize band-top geometry into 5 accessors, pixel-identical) →
        4608da1 Step 2 (flip: getM6RowsTopPx→rulerHeightPx, masterTopPx→below band; onMeasure reserves
        divider gap). New order: ruler → LAYER SUBSTRATE (Text/CC/viz/sprite) → MASTER (56dp, dominant)
        → AUDIO. Verified: master-seg select, M6 layer-item select, audio-clip select, scrub all
        hit-test at new positions; render order correct; plain single-track keeps master at top (no regress).
since: 2026-07-06 (continuation session)
files: (none — released; committed, code tree clean)
SLICE E FOLLOW-UPS (open, non-blocking — next session / G-series):
  • Within-band CC ordering + the "overlays&CC" vs "LAYERS" SUB-split (CC visually at the TOP of the
    substrate, above images/sprites/text). Currently layerBand = getLayers()+viz+captions (captions at
    BOTTOM). This is really G5 territory (piggyback/stratified fade-inheritance) — the mere row order is
    cosmetic; do it WITH the tether semantics, not standalone. File: FaditorEditorActivity#syncTimelineOverlays.
  • Cross-row MOVE-drag drop-zone can transiently overlap master's top edge (~28dp) during an ACTIVE
    pick-up-drag when the band is uncapped: viewportHeightPx grows by the drop-zone reserve past
    m6BandFootprintPx (which excludes it). Clipped + translucent + transient; NOT verified on device yet
    (needs a hold→drag hand-test). Address in G1/G6 drag work. HAND-TEST: pick up a Text/Sprite item,
    drag toward a new lane, watch whether the purple "+ new layer" strip bleeds over the master track top.
  • G6 (resizable timeline grab bar) is the long-term "master larger / more rows" answer and is independent
    — good next candidate; complements Slice E.
LAYERS-UX PROGRESS (this session, all DEVICE-VERIFIED + always-green):
  ✓ Slice A (0d0c5a1): CAPTION/VISUALIZER TrackKinds + TimedItem payload sockets (waveform +
    CaptionSpanRef) + Timeline getCaptionTracks/getVisualizerTracks. No visual change.
  ✓ Slice B+C (1f35695): captions & visualizers render as headered rows in LayerRowRenderer
    (amber CC w/ per-keyframe style segments + diamonds; cyan VIZ); OLD drawLayers
    text/caption/viz bars retired → SINGLE-render, reserved band collapsed, preview grew.
    Viz delete moved to the selection badge (deleteVisualizerWithConfirmation).
  ✓ FEEDBACK #4 (41dd79e): caption style chooser (Pop/Zoom/…) auto-hides when no caption is
    under the playhead (mirrors the caption-overlay visibility in updateCurrentTimeDisplay).
  ✓ AUDIO double-render (93fe745): suppressed the half-functional NEW audio rows (they didn't
    drive the ~9 getSelectedAudioIndex-anchored audio ops). Old complete audio system is the
    single audio UI. DEVICE-VERIFIED (new "Audio" row gone, CC is now last row, no crash).
  ⏭ Slice E vertical re-layout (FEEDBACK #3): DEFERRED to a dedicated next session (JoyRaptor's call
    2026-07-06 — too risky to rush at session tail). PLAN (always-green, 2 steps):
      Step 1 (safe, no visual change): centralize the band-top math into methods
        — masterTopPx()/masterBotPx(), audioBandTopPx(), overlayLayerBandTopPx() — and route ALL
        current inline sites through them. Sites to convert (EditorTimelineView):
          render: tTop/tBot (1663-64), audioTop/Bot (1667-68, 1588, 5576-78), getLayerTopPx (2210),
                  getM6RowsTopPx (2223), computeRects segRects tTop (1567), backgrounds (1671-74),
                  playheadBot (1739), drawCenterPlayhead (3268), ruler ticks (2493-2518).
          hit-test: master/segment (5548, 5560, 5771-72), audio (5576-79), ruler-scrub bounds (5793).
        Compile + device-verify NOTHING moved (pixel-identical) before committing Step 1.
      Step 2 (the flip): change ONLY the centralized methods to the new order — ruler → overlays+CC
        band → LAYERS band → MASTER (centered, ~1.15-1.3× taller) → AUDIO → tool drawer. Note the
        M6 rows have a capped internal scroll viewport (viewportHeightPx) — the master goes BELOW it.
        Device-verify EVERY tap target (master trim L/R, segment select, audio select+trim, ruler
        scrub, M6 row select/drag, header icons) before committing.
    Also relevant: contract §5/G6 (resizable timeline) is the better long-term answer to "MASTER
    larger" (user sizes it) and is independent — could land instead of / before the hardcoded flip.
  ALSO OPEN (from earlier): dead-code cleanup (inert old drawLayers/hitTestLayer*/selectedLayerKind
    + now the old audio path stays as the PRIMARY audio UI, so do NOT remove it); caption delete-badge
    no-op; full audio→unified-row migration (re-anchor ~9 getSelectedAudioIndex ops to selectedItemId).
  DEFERRED (still open, honest): (a) AUDIO double-render — drawAudioTrack (old, EditorTimelineView
    line ~1711, reads `audioClips`) still coexists with the new audio rows (`audioLayerTracks`
    from getAudioTracks); it's a LARGE independently-interactive subsystem (trim/mute/volume/
    waveform + own hit-testing) → needs its own focused slice + thorough audio-edit regression
    test before retiring the old path. (b) Dead-code cleanup: the now-inert
    drawLayers/hitTestLayer*/hitTestLayerRow/hitTestLayerTap/activeLayerIndex/Drag.LAYER_*/
    selectedLayerKind/Value + old caption/viz listener callbacks in EditorTimelineView are
    harmless (empty lists guard them) but should be removed in a pure-removal commit.
    (c) Minor wart: a selected CAPTION shows a delete badge that no-ops (onItemDeleteRequested
    has no caption branch — captions are clip-owned; a real "delete" = disable captions on the
    clip w/ undo, or suppress the badge for captions).
  NEXT candidates (JoyRaptor's FEEDBACK order): #5 minimal header (solo+twirl per contract §4.5 —
    move eye/lock/mute to per-OBJECT badges; LARGE); #3 vertical re-layout (overlays+CC top,
    MASTER centered/larger, AUDIO below — deep layout surgery in EditorTimelineView); then the
    audio consolidation above; #2 no-overlap all types (Slice F); G1–G9 gestures.
NEXT (ordered, per tasks/FEEDBACK_20260706_layers_ux.md): LAYERS-UX OVERHAUL — (1) consolidate the two
       row-render systems into ONE (EditorTimelineView#drawLayers + LayerRowRenderer) [prereq for the
       rest]; (5) header hit zones; (4) caption-chooser autohide; (3) vertical re-layout; (2) no-overlap
       ALL item types + move-between-layers; (6) gesture language DESIGN-FIRST with JoyRaptor. THEN sprite
       FF-A/FF-B, S7 relink, S2b polish. Watcher was live+green (BUILD SUCCESSFUL 14s).
NEXT (ordered, for the continuing session): (1) hand-test the one owed item — is the sprite
       VISIBLY composited on video in preview+export (see PLAN ⚠️); (2) S7 relink UI; (3) S2b
       polish; (4) FF-A presets/dope-sheet UI; (5) FF-B AI sprite tools. Watcher was live+green.
NOTE (build infra): watcher + opencode gradle running CONCURRENTLY corrupts the
incremental resource merge (missing .flat → 100 "class R" errors; fix = delete
app/build/intermediates + retrigger). While the watcher runs, opencode must NOT
invoke gradle — save and read build.log instead.

## OPENCODE — dynamic lane
status: IDLE (2026-07-07 — Opus "easy frontier" session wrapped. Landed: G7 coach-marks (954a63e) +
  transitions pull-down-for-more-rows (00d9e41), both build-green + launch-smoke-clean. DEFERRED both
  export items (quality setting + edit-safety) — entangled with the locked/correctness-critical
  ExportManager, see handoff.md. Owed hand-tests in that block.)
files: (none — released; committed, code tree clean)
since: 2026-07-07
