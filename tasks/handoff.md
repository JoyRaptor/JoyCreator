# FadCam AI Handoff

> **🛠️ 2026-07-05 — OVERLAP BUG FIXED (1bc4652), root-caused + PROVEN. User re-tested slice-3 and hit:
> (a) could place two clips OVERLAPPING by dropping between two butted clips; (b) cross-row preview
> OVERLAPS instead of butting. ROOT CAUSE (both, + the old diagonal tug-of-war): resolveNoOverlapStart /
> resolveOverlapOnRow were a 4-pass push-loop that OSCILLATED butt-before/after with no room and gave up
> STILL OVERLAPPING. Replaced with nearestFreeStart (merge siblings into blocks → place in nearest
> free region; tail always free so a legal spot ALWAYS exists → overlap impossible). Verified 13 cases
> in a standalone javac/java harness (scratchpad ResolverTest.java) incl. the exact repro BEFORE porting.
> Both live preview AND the commit-time guard (the PERSISTED-state guarantee) route through it. Time-lock
> reverted to WYSIWYG (rail straight when free, show BUTTED preview when occupied — never preview overlap).
> **STILL OPEN from the same user message (NOT yet built):** (1) A1 EDGE AUTO-PAN for row-item drags —
> holding a picked-up item at the screen edge must continuously pan the timeline so you can place further
> than the current view (edge-scroll infra exists for asset/audio drags ~line 655, NOT wired to the
> row-item pickup path); (2) #5 OFF-SCREEN BUTT / same-row before→after — dragging a clip to butt AFTER
> another whose far edge is off-screen: the view should pan to show that side + preview them butted
> (the min-pan excursion partially does this when a joint is armed, but the panel-half before/after
> CHOICE + sustained edge-pan is the missing piece). These two are the user's next priority — build A1
> first (concrete, infra exists), then #5. Files: EditorTimelineView (edge-pan/excursion) +
> LayerGestureController. Owed user hand-test after. Everything below is prior state.**

> **🎚️ 2026-07-05 — SLICE-3 GESTURE ROUND: 3 of 5 items LANDED (commit after 7f5313a), BUILD GREEN,
> installed on Note 9. AWAITING USER HAND-TEST (drag-feel = unscriptable on this device).**
> Spec: tasks/FEEDBACK_20260703_dragux_v3.md (A9 SNAP-PRIORITY + WYSIWYG DROP PRINCIPLE, both BINDING).
> **DONE:** (#3/A9) diagonal tug-of-war killed — in a vertical time-lock the drag now RAILS STRAIGHT at
> the original time (was: resolveNoOverlapStart shoving it sideways to dodge the target row's occupant);
> the butt-magnet re-engages only when an edge comes within snap radius of a neighbour AT the locked
> time; real overlap resolves at RELEASE via the existing commit-time butt-displace; no excursion during
> a pure layer change. (#2) cross-row affordances now PURPLE not white — one constant
> (COLOR_DROP_TARGET_RING) drives the drag-target ring + new-layer zone outline + cross-band line;
> same-row keeps item color, snap-home stays gray. (#4) excursion no longer centers the joint (overshoot)
> — pans the MINIMUM to reveal it + 56dp margin, holds if already visible, clamped to scroll bounds.
> (#1 WYSIWYG live resolved preview was ALREADY built via per-move resolveNoOverlapStart+applyMoveTo; A9
> completes its time-lock case.) **DEFERRED — #5 off-screen panel-half butt selection** (held LEFT of
> panel center previews butted-BEFORE, RIGHT = butted-AFTER): partially served by the excursion reveal;
> the panel-half before/after CHOICE is a larger design-y interaction that deserves its own focused pass
> + feel-test. Files: LayerGestureController / LayerRowRenderer / EditorTimelineView. **OWED USER
> HAND-TEST (≤4 gestures, relayed this session):** see the numbered checklist in the session's final
> message. After the user re-tests: fix what the feel surfaces, then either build #5 or move to the next
> queue item (transcript dedup relaunch).

> **✅ 2026-07-05 — PHASE P RECOVERED, COMMITTED (7f5313a), FULLY DEVICE-VERIFIED. Step 1 of the
> landing block below is DONE — next AI starts at step 2 (slice-3 gesture round).**
> The prior agent's uncommitted Phase P work was found compile-GREEN (build.log: BUILD SUCCESSFUL,
> installed SM-N960U) with a coherent diff across the 6 expected code files. Committed as 7f5313a
> ("feat(layers): Phase P - track header menu + M11 ripple/gap toggle"). The AndroidManifest change
> was ONLY the temp `exported=true` uiautomator-launch flip — reverted, NOT committed (per the standing
> rule it never enters history). **Device verification (sandbox project bdd51919 project.json ground
> truth):** P1 rename persisted (`trackDefs[0].name="RenamedP"`), P1 delete-with-migrate + P2 z-order
> proven by the prior agent's on-device run; P3 CLOSED THIS SESSION — the persisted state shows
> `rippleMode:"gap"` + `clip[1]` replaced in place by a `displayName:"Gap"`, `imageClip:true`,
> `audioMuted:true` spacer pointing at a real generated 16×16 black PNG (`files/images/
> faditor_gap_black.png`, 115 bytes on disk), `sourceDurationMs:19962` = the loop-extended original's
> VISUAL duration (proves the `hasLoopExtension()?getVisualDurationMs():getTrimmedDurationMs()` branch),
> clipCount preserved at 4 (no ripple shift). NOTE: the default-track-rename path (TrackFlags.customName
> → layers-block "trackNames" map) is compile-verified only — the device test happened to rename a
> USER-created LayerTrackDef track instead; both paths are in 7f5313a. OWED USER HAND-TEST (feel only):
> long-press a layer header → menu; toggle ripple/gap chip → delete a clip → confirm black gap stays.
> Now proceeding to slice-3 per the user-ordered queue.

> **🛬 2026-07-04 night — SESSION LANDING (JoyRaptor/Fable orchestrator). PICKUP INSTRUCTIONS:**
> **1. RECOVER PHASE P (probably in flight/killed at landing):** an agent was building "Phase P — layer
> header long-press menu (rename/move-z/delete), z-order end-to-end, M11 ripple/gap toggle." Its
> UNCOMMITTED work (compile-GREEN at landing) touches: AndroidManifest, FaditorEditorActivity, TrackFlags,
> Timeline, ProjectStorage, EditorTimelineView, activity_faditor_editor.xml. Protocol (proven 3×):
> `git status` + build.log green + coherent `git diff` → COMMIT with an honest message; red/incoherent →
> stash named clearly, restore green from 2995db4. Verify M11 gap-mode + menu behaviors on the sandbox
> Note 9 if attached (taps are scriptable; screencap STALE → screenrecord+ffmpeg; input swipe can't drag).
> **2. THEN slice-3 gesture round (JUMPS the queue, user-ordered):** tasks/FEEDBACK_20260703_dragux_v3.md
> — the "A9 SNAP-PRIORITY RULE" + "SLICE 3 EXPANDED SPEC (WYSIWYG DROP PRINCIPLE)" sections are BINDING
> and written implementation-ready. One Fable/strong agent, files: LayerGestureController/LayerRowRenderer/
> EditorTimelineView. User re-tests after; expect a ≤4-gesture hand-test list back to them.
> **3. THEN:** transcript dedup relaunch (conditions in the 🛬 2026-07-03 block + prior prompts: timestamped
> backup FIRST, live/edited transcripts byte-untouched, fix the stacking source, synthesize duplicates on
> sandbox to verify) → v3 slice 2 (unified gap-insertion blueprint in the dragux doc) → timeline fidelity
> W1/W2/T1 (tasks/FEEDBACK_20260703_timeline_fidelity.md) → M-COMP-2 probe-first (PLAN_LAYERS_V2 Part 10 #1).
> **OPEN USER CALLS:** muted-track captions show/hide?; main-phone real-project session (never yet run).
> **STANDING RULES:** ONE editing agent at a time (shared watcher; parallel edits burn — proven). NEVER
> gradle (watcher builds on save; "BUILD FAILED" from install/device/EOF lines = compile SUCCESS). Sandbox
> Note 9 SANDBOX_SERIAL only; NEVER main phone REAL_SERIAL/project 27221664. Commit each green item.
> Local commits only, NEVER push. Don't touch faditor/avatar/ or sprite files (JoyRaptor's lane) or stash@{0}.
> Everything below this block is history; the queue above is current.

> Living document for the next agent. Update this file when you change architecture, fix a recurring bug class, or make a non-obvious design choice.
> Last updated: 2026-07-04

> **🛬 2026-07-04 eve — JOYRAPTOR LANDING: sprite S2 DEVICE-PROVEN end-to-end + avatar A1 model COMPLETE.**
> **S2 (cb07ad7 + a44350a):** full adb-driven device proof on the Note 9 — editor launch → OS picker →
> star-guy from Downloads (fresh pushes need a MEDIA SCAN broadcast to appear in DocumentsUI) → steppers
> 3x3→4x4 → cell 0 named "idle" → Save → project.json: schemaVersion **9** (conditional stamp correct),
> sheetUri project://assets/<uuid>.png, cells [{0,"idle"}] → reopened by sheet id: state restored on
> screen. **v9 write + round-trip PROVEN** (closes the S1 acceptance). Device-caught + fixed: new-sheet
> NPE (buildUi before sheet creation). Verification technique for the never-idle editor: uiautomator can't
> dump FaditorEditorActivity (live player invalidation) — launch the TARGET activity directly; the temp
> exported=true flip never entered git history (flipped + reverted between commits).
> **Avatar A1 model (a9d6cc5):** PuppetPoseResolver (pure single-authority: bilinear continuous+pin blend,
> empty-cell inheritance, dominant-corner hysteresis, swapped→crossfade signal, caller-owned DiscreteState
> = deterministic bake replay) + AvatarRig storage, schema **v10** stamped only when rigs exist. GLM-5.1
> mined decisions (827947c) folded into the build.
> **⚠️ DISCLOSURE for the dragux lane:** a9d6cc5's `git add -A` accidentally swept your two in-flight
> files (LayerGestureController +17, LayerRowRenderer +40) — they built green and are committed under my
> A1 message; nothing lost, but your WIP is now in history there.
> **NEXT (JoyRaptor lane, strict order):** (1) resolver review gate + A1-UI matrix-editor scaffold
> (scrub/slider-driven, no ML); (2) sprite S4 preview (SpriteOverlayView, resolver-driven, below captions);
> (3) S2b polish batch; (4) A2 tracking driver (MediaPipe + One-Euro + thermal governor per MINED).
> User hand-test owed when convenient: open Sprites tool from the carousel → does the star-guy sheet feel
> right to slice by hand (pinch-zoom, pivot drag — unscriptable gestures).

> **2026-07-02 night — §6/M6+M7 ROW-BAND TOUCH FIXED: tap-select, long-press-delete, AND scrub-over-rows all work now (BUILT GREEN, device-verified Note 9 sandbox bdd51919…, com.fadcam.beta, NO commit). This unblocks the Layers hand-test.** The two bottom Track rows (purple "Text"/aqua "Audio") were totally inert — no select, no long-press, no scrub — because touches were consumed then dropped. **ROOT CAUSE (proven with temp logging, now removed): `LayerRowRenderer.hitTestHeader` had NO X-bounds check.** The per-row `headerRect` spans the row's FULL width in Y but is only meant to be the left 92dp header column; with only a Y-band test, ANY body touch (content-x well right of the header) fell through the 4 icon `.contains()` tests and hit the `return HitZone.NONE` fallthrough → `handleM6RowTouch` treated the whole row (header AND body) as a header hit, returned true, and never called `onRowBodyDown` (M7 select/long-press/drag) NOR reached the scrub axis-decision. Log proof: body tap at content-x=398 → `headerHit=NONE`, hitTestItem never ran. **FIX 1 (the primary):** added `if (x < row.headerRect.left || x > row.headerRect.right) continue;` in `hitTestHeader` — body-column touches now skip the header and fall through correctly. That alone restored TAP-select (brightened purple stroke + end-cap handles from 7ae8e42 now show — screenshotted) and LONG-PRESS ("Remove text overlay?" dialog fires — screenshotted). **Two more bugs surfaced for the SCRUB path (6a47560's intent) and were also fixed:** FIX 2 — the pending-axis DOWN in `handleM6RowTouch` returned true but never called `getParent().requestDisallowInterceptTouchEvent(true)` (every other armed-DOWN branch does), so the parent scroll container stole the follow-up MOVEs and the axis decision in `onMove` never ran → added that call. FIX 3 — the scrub-passthrough delta was computed in CONTENT-space (`x + scrollOffsetPx`), but `updatePlayheadFromX` re-centers every call so `scrollOffsetPx` shifts by ~the same amount x moved → the delta netted to ~0 and the playhead froze after the first event; changed it to RAW view-space x deltas (`m6RowPendingLastX - x`), exactly mirroring `GestureListener.onScroll`'s `distanceX`. After all three: horizontal swipe over the empty row band scrubs (log-proven playhead 5394→6815ms + ruler moved 0s→17s, screenshotted), vertical stays row-scroll (symmetric branch; not meaningfully exercisable here since content 202px ≤ viewport 210px = nothing to scroll), a drag STARTING on an item still arms M7 move (`hit=BODY` → m7ItemGestureActive — 6a47560's "item hits keep M7/M10 untouched" preserved), and DOWN above/below the rows still passes to normal timeline (`within=false`). Files: `layers/LayerRowRenderer.java` (+9, the X guard), `timeline/EditorTimelineView.java` (3 hunks: disallow-intercept + raw-x scrub ×2). Also removed a prior agent's leftover `DIAG onRowBodyDown` FLog in `LayerGestureController` (temp instrumentation, same class as mine). ALL my temp logging (M6_DIAG/LRR_DIAG) grep-verified gone. NOTE: this sandbox's items span the FULL project width, so there's little in-row EMPTY space to scrub from (mostly the top/bottom dead-bands + gaps) and items can't slide sideways — §7 duration-on-create + numeric fields is still the real ergonomic fix for that; the mechanism is correct regardless. User hand-test list in REPORT_RELAY.

> **2026-07-02 late — PING-PONG PARKED by user decision + resize-revert/black-screen regression FIXED (BUILT GREEN, device-verified Note 9 sandbox, NO commit).** User hit two bugs after L2 (9d9539c): resizing a loop reverted its size, then that clip + others went BLACK. ROOT CAUSE (cluster): (1) BLACK SPREAD = the gapless engine is ONE shared ExoPlayer/ONE playlist; a PING_PONG clip auto-promotes the whole project to gapless and injects baked-reversed MediaItems — if a reversed item fails to decode (or the auto-promote rebuild races) the shared player blacks out and the black spreads to EVERY clip. Evidence: an orphan baked file `cache/reversed/rev-1899-4884-*.mp4` existed for clip[3] but clip[3] was saved OFF — user set ping-pong, hit the black, reverted (deleted that orphan). (2) RESIZE REVERT = a loop-extension edge drag fired BOTH `onTrimFinished` AND `onLoopTrimFinished`; for a right-loop-drag the trim `endFraction`→1.0 (handle pinned to source bound), so `onTrimFinished` clobbered the clip's real out-point out to full source. FIX: new single flag `Clip.PING_PONG_PARKED=true` gates 4 seams (all L2 code KEPT, just dormant) — (a) loop-drawer ping-pong chip disabled/dimmed + "coming soon" toast, no mode switch; (b) `resolveReversedUri` returns null while parked → `MasterPlaybackEngine.isEligible` rejects every PING_PONG clip → whole project drops to LEGACY forward-tail = a stored ping-pong clip DEGRADES to a plain forward NORMAL-loop wrap (never black, never crash) in preview; (c) `ExportManager.buildLoopExtensionItem` `reverse` forced false → export forward-tail too (preview==export); (d) `kickReverseBakeIfNeeded` early-returns → NO bake ever kicked, incl. from resize. RESIZE FIX: `EditorTimelineView` ACTION_UP now fires `onLoopTrimFinished` ONLY (not `onTrimFinished`) when `loopChangedDuringDrag`, so a loop resize no longer clobbers in/out; `onLoopTrimFinished` made self-sufficient (`userDragging=false`, `setTrimFromClip`, `updateTrimBounds`, `setExactSeek`). DEVICE-VERIFIED (Note 9 sandbox bdd51919…, package com.fadcam.beta): loop drawer shows ping-pong DIMMED + tapping it leaves loopMode=1 (proven via project.json), Loop chip stays green; "Extend to end" grew clip[1] loopAfterMs 11000→12000 with in/out UNCHANGED (0/8962) + clean gapless rebuild (6 items, no PlayerError) — this is the same updateTrimBounds→rebuild path an edge-drag uses; undo restored 11000 + clean rebuild; force-stop+relaunch came back "Gapless engine ACTIVE for 4 clips", zero black in any of ~6 screenshots across all clips. L3 WIP KEPT (see PLAN §Status). NOTE for un-parking: flip `Clip.PING_PONG_PARKED=false` restores L2 exactly as 9d9539c — but FIRST fix the black-decode root cause (a reversed item that won't decode must fall back to forward per-item, not black the shared player). §6 rows still dead (see below). NOT re-attempted: the raw edge-DRAG resize gesture (one scripted attempt missed the handle hit-zone in the reflowing per-clip zoom view) — hand-test item for user; the code path itself is proven via the drawer button.

> **🛬 2026-07-03 evening — SESSION LANDING. Sprite S1 REVIEW-GATED + P0 no-overlap fixed. READ FIRST.**
> Since the S1 commit: (1) **RIG VISION recorded as binding direction** (PLAN_SPRITE_ANIMATION §RIG VISION):
> keyframed transforms + per-item anchor override + parentItemId = full puppet rigs; all ADDITIVE to the S1
> schema; a future resolveTransformAt must be the single transform authority like SpriteFrameResolver is for
> cells; reviewers must flag anything in S2–S7 that paints us out. (2) **P0 audio-stacking FIXED (c7442ae)**:
> applyMove → resolveNoOverlapStart snaps same-row overlaps to the nearer butting edge (multi-pass, landing
> row = hoverTarget else own row); cross-row occupied drops were already bookend-handled. (3) **S1 adversarial
> review gate ran (2 lenses × 2 skeptics): 5 findings, ALL FIXED** — MAJOR: setTimeRange degenerate-range
> guard (end≤start → open-ended, mirrors TextOverlayItem; was: sprite permanently invisible + S5 trim would
> hit it); MAJOR: pruneOrphanedTrackFlags now knows "sprite" + sprite layerIds (was: sprite track flags
> deleted every load); isVisibleAt made end-INCLUSIVE matching TextOverlayItem; keyTolerance serialized
> independently of bgKeyColor; createLayerTrack javadoc includes SPRITE.
> **NEXT:** S2 setup editor (SpriteSheetEditorActivity) — GATED on the competitor-research design pass
> (tasks/RESEARCH_COMPETITOR_UX_20260703.md, another worker; REMIND USER if still absent next bundle).
> Non-gated S2 prep any agent can do: sheet decode/cell-blit engine (decode-once bounded bitmap, cell
> srcRect math from grid geometry, bg color-key at decode) — pure engine, no UX decisions. User hand-test
> owed: no-overlap feel (try to stack two audio items; try to stack text) + the batch-2 drag/trim items.
>
> **🎬 2026-07-03 afternoon — SPRITE ANIMATION BUILD 1 STARTED (S1 model+storage DONE, 3243fdb) + roadmap synced (c46c612).**
> User decisions: Fable-5 builds vision-heavy work personally (sprites, AI integrations, UX overhauls;
> small stuff can go to lesser models); major AI features AFTER core stability; NOT full ultracode —
> sequential Fable implementation + adversarial-review workflows at S1/S4/S6 gates. Roadmap got a sync
> section (9 orphaned plan clusters indexed, Layers/loops statuses corrected, sprite promoted). Sprite plan
> now lives IN-REPO: tasks/PLAN_SPRITE_ANIMATION.md with a BINDING 2026-07-03 amendment — build NATIVE on
> the landed Track model (schema v9, 4th TimedItem payload, SPRITE track grouping in Timeline.getLayers,
> dual-write stamps v9 only when sprite data exists). S1 landed green first try: sprite/ package
> (SpriteSheet+sidecar JSON, FrameTrack step/hold primitive, SpriteOverlayItem, SpriteFrameResolver — the
> ONLY cell-index authority). Load-path device-proven (v8 sandbox project opens under v9, json untouched);
> write-path proof rides the next real edit; sprite round-trip provable once S2/S3 create data. User's test
> sheet committed at repo root: star-guy-spritesheet.png. NEXT: S1 adversarial review gate → S2 setup
> editor (DESIGN GATE: fold in tasks/RESEARCH_COMPETITOR_UX_20260703.md — in flight from another worker;
> if absent next bundle, REMIND the user). Still open elsewhere: audio-overlap P0 (dragux_v3 A8).
>
> **🛬 2026-07-03 ~04:15 — SESSION LANDING (Fable orchestrator, autonomous overnight run complete). READ THIS FIRST.**
> **The ENTIRE gesture cluster is DONE and installed on the sandbox (SANDBOX_SERIAL), awaiting ONE morning
> hand-test.** Code commits this session, all BUILT GREEN via the watcher, each checkpoint-committed:
> 9cf3080 (contract redesign recovered from the lost agent + 2 orch fixes) → aadf9c1 (delete = selection
> trash badge) → 4375fab (feedback batch 1: badge 9dp+viewport-pinned, row-scrub FLING, timeline-locked
> TRIM STRIPES, honest cross-band preview + insertion line) → fcc0bd5 (adversarial-review fixes — CRITICAL:
> ACTION_CANCEL/pinch-interrupt now ABORTS+REVERTS instead of committing the drop/new-lane; delete badge
> deferred to tap-on-UP so swipes-from-badge scrub; trim handles win the badge-overlap strip; VelocityTracker
> recycle) → c0248db (BOOKEND MANEUVER: occupied-row snap, panel-half BEFORE/AFTER, animated view excursion
> with CONTENT-LOCKED playhead cue, animate-back, suppressMoveMapping across the return glide) → 5ac5701
> (post-pinch dead zone: surviving finger pans immediately, re-anchored zero-jump, flings on release).
> **Review evidence:** a 3-lens × 2-skeptic adversarial workflow confirmed 5 defects (all fixed in fcc0bd5);
> full findings JSON in the session task output. Hand-test #1 evidence: ROWGESTURE pull proved every
> contract case (AXIS-HORIZONTAL scrub-not-move, TAP select-only, PICKUP, drop commits) and proved the old
> 7dp badge got ZERO hits (hence the redesign). Logcat buffer CLEARED at landing for a clean morning pull.
> **USER DECISIONS RECORDED THIS SESSION:** (1) bookend/no-overlap spec confirmed "correct" — sub-lane
> overlap rendering is DEAD/superseded; (2) DESIGN PRINCIPLE: per-item actions = SELECTION BADGES (trash
> roundel pattern), never new gestures — see PLAN_LAYER_GESTURE_CONTRACT.md §DESIGN PRINCIPLE.
> **NEXT (strict order):** (1) morning hand-test checklist (relayed in the session's final message —
> 6 items: swipe-scrub, tap+trash-badge incl. pinned-on-long-item, pickup+bookend excursion feel, cross-band
> insertion line honesty, trim stripes, pinch→pan handback) + pull `adb logcat -d -s ROWGESTURE:D`, verify
> BOOKEND/EXCURSION/PINCH-HANDBACK/DELETE lines; (2) fix whatever the feel-test surfaces; (3) STRIP the TEMP
> ROWGESTURE logging (LayerGestureController ROWGESTURE_LOG + all RG() in EditorTimelineView/LayerRowRenderer);
> (4) then the standing queue: rebrand pass 1 (name DECIDED "Joy Creator", icons in art/, quick-wins d86a3c0)
> → Tier-1 durability → transcript dedup (timestamped project.json backup FIRST) → M11 → M-EXPORT-1 (Opus) →
> ping-pong unpark. Known deferred bookend edges are listed in the PLAN Status block (same-row overlap,
> 0-clamp, interior gaps).
>
> **🛬 2026-07-03 ~03:45 UPDATE 2 — HAND-TEST #1 PASSED (log-verified) + FEEDBACK BATCH 1 LANDED (4375fab).**
> User hand-tested the redesign: core contract CONFIRMED by feel AND by ROWGESTURE pull (AXIS HORIZONTAL
> scrub-not-move ×2, TAP select-only, PICKUP-MOVE commits, droppedOnNewLayer=true, cross-row toTrack move;
> trim "works excellent"). Log also PROVED the trash badge got ZERO hits all session ("hard to hit").
> **4375fab ships their 4 feedback fixes:** (1) badge 9dp + 2.0r slop + PINNED to screen-right edge on long
> items (deleteBadgeCx single-sources glyph+hitzone; layout() captures hScroll/width); (2) row-band scrub
> FLING (VelocityTracker on the custom path → startPlayheadFling, extracted from onFling); (3) edge-trim
> STRIPES — timeline-locked content-space diagonal grid, 180ms fade (resize visibly "eats stripes" vs move);
> (4) cross-band hover now ARMS a new-lane drop + draws an insertion line at the TRUE lane position (visual
> → boundary above audio band; audio → below last audio row); drop commits via onItemDroppedOnNewLayer;
> HOVER REJECTED log throttled to once-per-row. BUILD GREEN, installed @ sandbox.
> **SUPERSEDED: the sub-lane overlap follow-up is DEAD** — user's new binding model: NO overlapping items
> on a layer row; occupied-row drops SNAP to bookends. Full BOOKEND MANEUVER spec (animated view excursion,
> timeline-PANEL-half bookend choice, playhead stays content-locked as the "temporary maneuver" cue,
> animate-back on exit) now in PLAN_LAYER_GESTURE_CONTRACT.md FOLLOW-UP 1 — Opus-tier, NEXT after user
> confirms batch 1 + the spec restatement. Post-pinch handback (FOLLOW-UP 2) still queued after that.
> An adversarial multi-agent review of 9cf3080+aadf9c1+4375fab ran at landing — check the session report
> for surviving findings before building on these files.
>
> **🛬 2026-07-03 ~03:10 UPDATE (orchestrator pickup) — GESTURE REDESIGN RECOVERED + COMMITTED, AWAITING USER HAND-TEST.**
> The in-flight agent's diff was found dirty + RED (1 compile error). Diff-reviewed (coherent, matches PLAN
> TARGET CONTRACT), fixed, committed **9cf3080**: swipe-on-item=scrub, tap=select, 450ms long-press=pickup
> (haptic+lift), drop zone PINNED to visible viewport bottom. Orch fixes folded in: (1) dead write-only field
> `m7PendingLastX` used out-of-scope `x` (the compile error) — removed; (2) **onUp had NO m7ItemPendingDown
> branch** — a quick tap left the 450ms pickup timer live → item self-lifts after the finger left + gestures
> jam until next DOWN — added TAP resolution (cancel timer, close pending gesture, release parent intercept).
> Then **aadf9c1**: delete relocation done (plan's unchecked item — the agent had unwired long-press delete
> leaving onItemDeleteRequested UNREACHABLE): trash roundel on the SELECTED item (right end, inside trim cap),
> ItemZone.DELETE hit-tested first w/ finger slop, DownResult.CONSUMED fires the same confirmation dialog on
> DOWN like header icons; badge auto-skipped on too-narrow items (deleteBadgeCx single-sources glyph+hitzone).
> BUILD GREEN both commits; APK auto-installed on sandbox SANDBOX_SERIAL @ 03:05; logcat buffer CLEARED for
> a clean ROWGESTURE pull. **NEXT: user hand-test (4-gesture checklist relayed 03:06) → pull
> `adb logcat -d -s ROWGESTURE:D`, verify: PENDING body → AXIS HORIZONTAL scrub (no MOVE), TAP select-only,
> PICKUP armed + zoneInViewport=true + screenZoneBot < ~924, DELETE badge CONSUMED. Then queue items 2-5
> below (sub-lanes → post-pinch handback → strip ROWGESTURE → rebrand...). ROWGESTURE logging still IN.**
>
> **2026-07-04 — LAYERS MVP COMPLETE (M-EXPORT-1 @ 1d0c0b0: shared-authority export parity, decoded-stream
> md5 gate) + PHASE R ROBUSTNESS LANDED (26cf3cf: hide-toggle live refresh device-proven; same-row overlap
> never persists — row system + legacy waveform lane + audio-trim clamps; ONE 8dp snap constant everywhere;
> time-locked vertical swap w/ dashed guides, occupant-never-moves). PHASE P IN FLIGHT (Fable): layer header
> long-press menu (rename/move-z/delete), z-order end-to-end, M11 ripple/gap toggle — if found dirty, assess
> diff + build.log per the twice-proven resume protocol.** USER HAND-TEST OWED (Phase R, 4 gestures: audio
> stack attempt must butt/snap-back; vertical time-locked swap w/ guides; free placement w/ gentle 1-2mm
> magnets; audio trim stops at neighbor). OPEN USER CALLS: muted-track captions show or hide?; main-phone
> real-project session. QUEUE AFTER P: transcript dedup relaunch (spec self-contained in prior prompt/
> handoff), v3 remaining P1s (edge auto-pan, minimap drag-nav), timeline fidelity W1/W2/T1, M-COMP-2
> probe-first, preview perf memoization (Track views rebuild per access), export-duration math oddity.

> **🏓 2026-07-03 eve — PING-PONG UNPARKED, FULLY DEVICE-PROVEN (fe88e39). Diagnosis method: ultracode
> 3-investigator + adversarial-judge workflow (plan + full evidence: tasks/PLAN_PINGPONG_UNPARK.md/.json).**
> Root cause SETTLED: mid-playlist HEVC→AVC codec swap (amplified by zero error handling in the gapless
> engine). Fixed: (1) rank-1 containment — onPlayerError → poison reversed URI → PER-CLIP forward degrade →
> reseek+resume (corruption drill proven live, no blackout ever again) + debug EventLogger + bake-promote
> rebuildGeneration race fix; (2) hevc_mediacodec bake (attempt-1 winner; 10.5Mbps HEVC hvc1 = source-matched;
> ⚠️ h264_mediacodec fails on this device but hevc_mediacodec WORKS — remember); (3) PARKED flag flipped, 4
> seams live. Export parity RUN ON-DEVICE for the first time (was only ever by-construction): reverse leg
> matches preview, same cache file. OWED: user eyeball of live wrap + reverse-audio ear-check.
> ⚠️ **CONCURRENT-SESSION WARNING:** ~11 rebrand files (splash/strings/manifest/onboarding + new
> joy_creator_splash.png) are DIRTY in the tree from ANOTHER session's in-progress rebrand pass — NOT
> committed here, owner unknown. Do NOT commit/revert them blindly; confirm with the user/other harness first.
> Timeline-fidelity spec queued: tasks/FEEDBACK_20260703_timeline_fidelity.md (W1 waveform render = small,
> ride-along candidate). v3 drag-UX spec: tasks/FEEDBACK_20260703_dragux_v3.md (P0 = A8 audio overlap).

> **🛬 2026-07-03 ~03:15 — SESSION LANDING (JoyRaptor/Fable orchestrator at context limit). READ THIS FIRST.**
> **⚠️ AN AGENT MAY STILL BE / HAVE BEEN IN FLIGHT at landing:** "gesture contract redesign" editing
> EditorTimelineView / LayerGestureController / LayerRowRenderer / FaditorEditorActivity per
> tasks/PLAN_LAYER_GESTURE_CONTRACT.md (BINDING spec: swipe-on-item=SCRUB, tap=select,
> long-press=pickup+lift+haptic, drop zone pinned INSIDE viewport, delete moves to selection state, extend
> the TEMP ROWGESTURE logging). On pickup: `git status` — if those files are dirty: build.log green + diff
> coherent → COMMIT it (agent reports are lost between sessions; diff-review is the protocol, done twice
> already today, see 7ae8e42); red/incoherent → stash w/ clear name, restore green from 64192bb.
> **THEN, strict order (same files, ONE agent at a time):** (1) relay the redesign's ≤4-gesture hand-test to
> the user, pull `adb logcat -d -s ROWGESTURE:D` after; (2) sub-lane overlap rendering (PLAN FOLLOW-UP §);
> (3) post-pinch pan handback (PLAN FOLLOW-UP 2 §); (4) strip ROWGESTURE TEMP logging once user confirms.
> **Today's landed evidence chain:** row scrub pass-through 6a47560+1a13557+23930bb (X-only-slop + pinch-leak
> fixes); ghost-lock/selection 7ae8e42; L1 loops 52cdc29; L2 ping-pong 9d9539c but **PARKED** by ffcdc86
> (shared-player reversed-item decode failure blacks out preview — unpark = fix that decode, the parity
> architecture is sound); icon art in art/ (9562300; orchestrator rec: Expanse=splash, simplified Director
> clapper=launcher); name DECIDED = **Joy Creator** (5069a3b), Studio = descriptor.
> **Queue after the gesture cluster:** rebrand pass 1 (quick-wins catalog d86a3c0) → Tier-1 durability →
> transcript dedup (timestamped backup FIRST) → M11 → M-EXPORT-1 (Opus) → ping-pong unpark → feedback
> batch (#8 small-screen, #5 transcribe-on-add+gear, #6 tools drawer) → masking/chroma/alpha planning
> (FEEDBACK_20260702_layers_masking.md §C) → full-studio §7 of DESIGN doc.
> **Tiering (user's budget rule):** Opus ONLY for ping-pong decode / M-EXPORT-1 / gesture state machines;
> Sonnet low-med for everything else; specs must be lesser-model-executable.
> **User workflow:** not a programmer; hand-tests on the Note 9 and describes FEEL — turn that into log
> evidence (ROWGESTURE pattern), never script drags (impossible on this device), ≤4-gesture checklists.

> **2026-07-02 eve — L2 TRUE PING-PONG COMPLETE (9d9539c, device-verified) + ghost-lock/selection commit (7ae8e42) + user icon art in art/ (9562300).** Ping-pong now truly reverses: `export/ReversedSegmentCache` (ffmpeg-kit, **libx264 forced — Note 9 HW encoder rejects these sources**, ~6s bake for 3.5s span, 30s guard, off-main + drawer kick + auto-promote); preview playlist alternates fwd/rev; ExportManager reverse branch uses the SAME baked file+formula (old forward-tail fake + mirror hack + `setPlaybackSpeed(-1f)` all DELETED); `totalEffectiveMs()` now counts loop extensions — closes the audio-tail resume-from-pause bug AND the totalEffectiveMs follow-up in one fix. Owed: user eyeball of a live ping-pong wrap + reverse-leg audio listen (test clip was muted); >30s bake guard untested on device (no long source). 7ae8e42 = TrackFlags baseline-snapshot merge guard (shows-unlocked-acts-locked fixed) + stale-flags cleanup + SELECTION VISUALS now actually drawn (were never implemented) — user hand-test checklist delivered. NEXT per queue: L3 loop polish (incl. dead ping-pong fields cleanup) → rebrand pass 1 (icon concepts in art/, naming question OPEN: art says "Joy Creator STUDIO" — confirm with user before mass string work) → Tier-1 durability → dedup → M11 → M-EXPORT-1.

> **2026-07-02 — L1 (seamless NORMAL loops on gapless engine) COMPLETE, BUILT GREEN, device-verified on Note 9 sandbox (bdd51919…).** Resumed WIP `1597fee` (compile-green, cut mid-verification) — the WIP was ~95% done: `MasterPlaybackEngine` already expanded NORMAL-loop clips into before/main/after rep windows with clamp math verified to mirror `ExportManager.buildLoopExtensionItem` EXACTLY (read both side by side — same `ceil` rep count, same `min(trimmedPlayMs, extensionMs - repIndex*trimmedPlayMs)` clamp, same append order), same-clip rep-to-rep seams suppressed (`SeamListener` fires only on real timeline-clip change), continuous visual position/duration plumbed through `getCurrentPositionInWindow()`/`getCurrentWindowDuration()`, and the activity's poll-based NORMAL-loop wrap block correctly bypassed when `playerManager.isGapless()` (ordering: PING_PONG legacy → gapless-NORMAL early-return → legacy-NORMAL → STILL legacy, all correct, none of L2/L3 touched). **Real gap found and fixed:** the loop DRAWER's button-driven paths (`applyLoopMode`, `extendLoop` — the "Off/Loop/Still/Ping-pong" mode chips and "Extend to start/end/prev/next clip" buttons) mutated the clip's loop fields but never called `playerManager.updateTrimBounds(clip)`, unlike the drag-driven path (`onLoopTrimFinished`, already wired) and undo/redo (`refreshEditorAfterUndoRedo`, already wired) — so editing a loop via the drawer left the gapless engine's playlist STALE (wrong rep count/boundaries, or wrong engine entirely if switching to/from PING_PONG/STILL) until some unrelated action forced a rebuild. Fixed by adding the same `if (!clip.isImageClip()) playerManager.updateTrimBounds(clip);` call (guarded, matching the established convention) to both methods. Flag `FaditorPlayerManager.GAPLESS_ENGINE` restored to `true` (previous agent had set it `false` for baseline measurement only).
>
> **Device verification (Note 9 sandbox `bdd51919…`, clip[1]: loopMode=NORMAL, loopAfterMs=11000, trimmed=8962ms → 2 reps, rep0=8962ms full + rep1=2038ms clamped-partial, visual duration=19962ms):** wrap measurement used logcat-timestamped `MasterPlayEngine` seam/wrap markers correlated against `screenrecord` + `ffmpeg` frame extraction (NOT naive whole-clip `freezedetect` — this footage is near-static rug/fabric texture, so freezedetect at -30dB threshold produced sustained false-positive "freezes" on genuinely smooth playback; the reliable signal was per-frame MD5 hashing at 30fps in a 1s window bracketing each logcat-timestamped wrap, counting consecutive IDENTICAL frames). Results: main-pass→rep0 wrap = 2 single-duplicate-pairs out of 30 frames (normal frame-pacing rate, zero extended freeze); rep0→rep1 (partial) wrap = 1 duplicate pair out of 30; clip[1]→clip[2] exhaustion/auto-advance = 2 duplicate pairs out of 31 — **all three wraps show ZERO frozen-frame runs longer than 1 frame (33ms)**, vs the previous agent's measured legacy baseline of 106–163ms. Play-through auto-advance across BOTH remaining cuts (clip1→clip2, clip2→clip3) confirmed via `onGaplessSeam autoAdvance=true` reaching a clean 00:27/00:27 end state. Pause mid-extension (00:18, within rep1) held a coherent non-corrupted frame; trim-via-drawer ("Extend to end" +1000ms) confirmed via `project.json` diff (loopAfterMs 11000→12000) AND a live "gapless playlist prepared: 6 clipped items" log confirming the rebuild fired; undo confirmed via `project.json` byte-diff (only `lastModified` timestamp differs, loop fields exact match, gapless rebuild fired again on undo via the pre-existing `refreshEditorAfterUndoRedo`→`updateTrimBounds` path).
>
> **Seam-clobber add-on (commit `9edad8a`, already-committed fix — verifying it holds now that loops ride the same engine):** captured 3 genuine user-seek cross-clip seams (`autoAdvance=false`) during scripted taps and 2 auto-advance seams (`autoAdvance=true`) during play-through. All 3 user-seek seams correctly took the "don't re-home" code path (verified both by the `autoAdvance=false` log flag, which gates the `setPlayheadFraction` call in `onGaplessSeam`, and by the final on-screen playhead sitting at a real tapped position, never 0/a clip-start); both auto-advance seams correctly DID re-home (reaching the clean timeline end). **Caveat, reported honestly per the one/two-attempt rule:** a controlled "40 scripted taps across ONE fixed boundary" batch was NOT cleanly achieved — `EditorTimelineView` auto-reflows into a per-clip "zoomed filmstrip" view on tap, which invalidates a pre-computed tap coordinate the instant the view mode flips (recalibrating from a stale screenshot lands taps back inside the already-zoomed clip instead of crossing the boundary again). Two attempts (one blind 40-tap batch, one recalibrated 5-pair-at-a-time batch) both hit this same reflow obstacle; real cross-clip seam samples were captured through the incidental view transitions that DID occur, and all samples agree, but this is a smaller/less controlled sample than a clean 40-cross run would have given. Not iterated on further per the testing-economics rule — flagging for the next session if a stronger sample is wanted (would need e.g. a fixed-zoom / non-reflowing scrub surface, or driving seeks via a debug intent extra instead of raw taps).
>
> **One pre-existing bug found (NOT fixed — out of L1's gapless-engine scope, flagged separately, follow-up task spawned):** `FaditorEditorActivity.totalEffectiveMs()` (~line 587) sums `getTrimmedDurationMs()` per clip but never adds `loopBeforeMs`/`loopAfterMs`, so on any project with a loop extension it undercounts the real timeline length (confirmed: sandbox project's real total = 27624ms, `totalEffectiveMs()` returns 16624ms — short by exactly the 11000ms extension). This function gates the "audio-tail" feature (`onPlayPauseClicked`-equivalent ~line 3009): pressing Play from a paused position anywhere past the miscalculated `videoEndMs` (which includes the back half of clip[1]'s extension plus all of clip[2]/clip[3] in the sandbox) triggers `audioTailActive` mode — the video freezes on whatever frame was last shown while `updatePlayheadPosition()`'s audio-tail branch drives the time counter purely by wall-clock elapsed time, never resuming real ExoPlayer playback, until it "catches up" to the timeline end a few real seconds later. Reproduced live on-device. This is flag-independent (present in both `GAPLESS_ENGINE=true/false`) and pre-dates this whole session (`git log -S"totalEffectiveMs"` → only the `10bc40b` baseline commit), so it's a latent defect in the audio-tail feature exposed by loop extensions, not something L1 introduced — left alone as instructed ("don't touch scope outside L1"), but it directly breaks "pause mid-loop → resume" for any looped project, so it's a real near-term fix (one-line: mirror the `hasLoopExtension() ? getVisualDurationMs() : getTrimmedDurationMs()` pattern already used correctly elsewhere in this same file, e.g. ~7130-7136/~7160-7167/~6960-6966).
>
> **Files touched:** `FaditorEditorActivity.java` (+13, the two `updateTrimBounds` calls + comments), `FaditorPlayerManager.java` (flag restore, 1 line). No commit made (per instructions — the user commits). Build green throughout, no temp logging added or left behind.

> **2026-07-02 — P0 "ruler snaps playhead to 0" — ONE real cause FIXED (BUILT GREEN), scope narrowed; two more items diagnosed-not-built. Session ended early (usage window).**
> **Root cause CONFIRMED with device evidence (the gapless-seam clobber):** In M-COMP-0 gapless mode, ANY cross-clip seek (ruler-scrub OR clip-body tap — both funnel through `onPlayheadSeeked`) issues `player.seekTo(window,pos)` to a different media-item index, so ExoPlayer fires `onMediaItemTransition(REASON_SEEK)` → `FaditorEditorActivity.onGaplessSeam(idx)`, which UNCONDITIONALLY re-homed the playhead to that clip's START via `setPlayheadFraction(inPoint/sourceDur)`. That async callback lands a few ms AFTER `onPlayheadSeeked` already set the correct tapped position, clobbering it — snapping to 0 when the target was clip 0, or to the clip's start otherwise. **Evidence (scripted 40-tap ruler/clip loop on Note 9 sandbox `bdd51919…`, temp `SNAPDBG` logging in the seam path, now removed):** 10 seams fired that run; **2 forced playhead to exactly 0** (`onGaplessSeam newIndex=0 … AFTER setPlayheadFraction playheadNow=0`), **4 more yanked it backward by up to 3441ms** to the target clip's start — all with `userDragging=false` so no existing guard caught them. This is a genuine engine-adjacent transition-callback bug, NOT a raw ExoPlayer position-0 read: the 0 came from `setPlayheadFraction(startFrac)`, not from `getCurrentPosition()`. **Engine theory (transient position-0 / mediaItemIndex reset mid-seek) → DENIED for the snap itself:** the position read path is clean; the snap is the seam handler re-homing, full stop.
> **Fix (minimal, engine untouched, legacy flag intact):** `MasterPlaybackEngine.SeamListener.onSeam(int)` → `onSeam(int, boolean autoAdvance)`; engine passes `autoAdvance = (reason==REASON_AUTO)`. `onGaplessSeam(int,boolean)` now re-homes the playhead **only when `autoAdvance`** (playback played THROUGH a cut — where snapping to the new clip's start is correct and the playhead is already there). For a user SEEK it runs all the per-clip UI sync as before but LEAVES the playhead where `onPlayheadSeeked` put it. Files: `compositor/MasterPlaybackEngine.java` (interface + one call site), `FaditorEditorActivity.java` (`onGaplessSeam` signature + guarded `setPlayheadFraction`; the `this::onGaplessSeam` method-ref binds to the new 2-arg sig automatically). **BUILT GREEN.** **NOT re-verified on device after the fix** — the after-fix tap run happened to land all taps on the clip lane at the timeline-start scroll position (0 seams fired that run), so it neither reproduced nor exercised the seam; the before-fix repro + the code change are the evidence. Next session: re-run the loop with the timeline scrolled so taps cross a mid-timeline boundary into clip 0's region, expect ZERO `playheadNow=0` seams.
> **Coordinator's revised diagnosis (user hand-test) — verified in CODE, changes NOT yet made (ran out of window):**
>   • **Mechanism 1 (tap ambiguity):** CONFIRMED the geometry. `EditorTimelineView.hitTestSegment` accepts y from `rulerHeightPx - touchSlop/2` down through the clip lane, so taps in the bottom sliver of the ruler AND anywhere on a clip body select that clip. BUT the recommended behavior ("tap a clip → select + seek to TAPPED x, not clip start") is **ALREADY IMPLEMENTED**: the segment-tap-up branch (`onUp`, ~line 4191) calls `seekToTimelineMs(xToTime(downX+scrollOffsetPx))` = the tapped position, THEN toggles selection via `onSegmentSelected`→`selectSegment` (which does NOT seek). So there is NO separate seek-to-clip-start on clip tap in current code. What the user saw as "snap to ~1s" was almost certainly the SAME gapless-seam clobber (mechanism above, now fixed) landing after that tapped-position seek crossed into clip 0 (their first clip is ~1s). **Recommendation for next session: re-test mechanism 1 on-device AFTER this fix before changing anything — it may already be resolved.** Note: a discrete tap in the PURE ruler zone (above the clip lane) currently does NOT seek at all — there is no `onSingleTapUp` in `GestureListener` (only `onScroll`/`onFling`), so `onUp` with `downSegIndex<0` just calls `onPlayheadDragFinished`. If the user expects "tap empty ruler = seek there", that's a separate small addition (add `onSingleTapUp`→`updatePlayheadFromX`).
>   • **Mechanism 2 (surface overlap / two surfaces eat one finger during a ruler DRAG):** NOT yet investigated in the preview/workspace surface code — only the timeline side was read. The timeline's `EditorTimelineView` already calls `getParent().requestDisallowInterceptTouchEvent(true)` on down/scroll, so the parent shouldn't steal it; the suspected culprit is a SEPARATE gesture surface on the preview/player area that also scrubs. Next session: find the preview-surface touch/scrub handler (search `playerView`/preview drag-to-scrub in `FaditorEditorActivity`), confirm both process pointers, and add first-claim-wins pointer ownership (a shared "a scrub gesture is active" latch checked by both). Physical gap between ruler hit-zone and that surface, and whether a few-dp dead-zone/hit-slop helps, is UNMEASURED — do it there.
> **Untouched/again-green rules honored:** no git commit; ExportManager/Layers-gesture/captions code not modified for this task; all temp `SNAPDBG` logging + the scratch `userSeekInFlight` field removed (grep-verified zero matches). **Legacy engine:** flip `FaditorPlayerManager.GAPLESS_ENGINE=false` to bypass — the seam path (and thus this whole bug class) doesn't run in legacy, so the fix is inert there; not re-tested with the flag off this session.

> **2026-07-02 — Caption-style-keyframe UX REDO COMPLETE (BUILT GREEN, full device-verify with JSON+screenshot evidence).** Found the M-COMP-1 commit (`01d0d66`) had already landed most of the scaffolding (data model, undo action, `caption_keyframe_drawer` XML, arm/nav/delete wiring, and the CC-lane per-segment coloring in `EditorTimelineView`) — this was NOT the killed stash (left untouched, stash@{1}), it was real committed foundation. Closed the actual gaps: (1) fixed a real bug where `Clip.captionStyleId` never resynced when kf[0] was replaced/removed, so deleting the first keyframe reverted to a STALE base style instead of "next keyframe's style extends back" — `Clip.java` now keeps `captionStyleId` as a derived cache of keyframe[0]'s style across `addOrUpdateCaptionStyleKeyframe`/`removeCaptionStyleKeyframe`/`setCaptionStyleKeyframes` (the last one matters most: undo/redo is now self-contained with no separate style snapshot needed); (2) tolerance 40ms→50ms (`Clip.CAPTION_STYLE_KEYFRAME_TOLERANCE_MS`); (3) nav buttons now dim/disable per-direction at ends (were show/hide as a pair); (4) new `captions/CaptionStyleKeyframeController.java` (stateless: `computeNavState`, `isOnKeyframe`, `tapActionFor`, `colorForStyle` alias) centralizes logic that was duplicated inline; (5) added a stopwatch shortcut (`caption_kf_arm_shortcut`) to the bottom caption-style chip bar so keyframe mode is reachable without long-pressing the CC timeline lane — taps the SAME arm state + drawer, not a second source of truth.
>
> **Device-verified on Note 9 sandbox (`bdd51919…`), tap-only, no drags:** arm→toast→drawer-open; drop keyframe (JSON: `t`/`s` fields, e.g. `{t:0,s:"boxed"},{t:6500,s:"hot"}`); on-keyframe tap = REPLACE (toast "Keyframe style replaced", array stays same length) vs off-keyframe = DROP (toast "Style keyframe dropped", array grows) — confirmed via `CaptionStyleKeyframeController.tapActionFor` computed BEFORE mutation; `<`/`>` nav jumps exactly onto keyframe times + dims at ends (`enabled`+`alpha` both checked via `uiautomator dump`); delete removes + confirmed the first-keyframe-removed-extends-back fix works (chip highlight + solid CC-bar color flipped from the deleted style to the surviving one); undo restored the keyframe array byte-for-byte across DROP/REPLACE/REMOVE (undo counter decremented, redo incremented, JSON diffed each step). CC-bar per-segment coloring + diamond markers (spec's visual ask) were ALREADY correct pre-existing code — got a clean two-tone screenshot (teal→mint transition with a diamond at the boundary) as bonus confirmation. **Caveat found, not a bug:** on a very-high-speed clip (6.5x in the sandbox), nav-to-keyframe can land outside the ±50ms tolerance because timeline→source seek quantization gets amplified by the speed multiplier — pre-existing seek-pipeline characteristic (same pattern the opacity/volume keyframe drawers would hit), not something this task's scope covers fixing. `buildCaptionDrawerContent()`'s separate "Caption Style" bottom-sheet (distinct from the floating chip bar) still bypasses keyframe mode entirely (its chips always set base style) — left as-is, not in the spec's UX description, flagging for a future pass if the user wants keyframe-awareness there too.

> **2026-07-02 — M10 drag-between-layers + drop-to-new-layer COMPLETE (BUILT GREEN, one-undo-step gap fixed, device-verify PARTIAL — sandbox tap/pan/lock checks passed, the actual drag gesture unconfirmed).** Picked up `0cf3a4c`'s WIP, which was already ~95% complete: `LayerTrackDef.java` (new, persistent user-created track defs), `Timeline.getLayers()/getAudioTracks()` grouped by `layerId` with `extraLayerTracks`, `LayerRowRenderer` cross-row highlight ring + "+ New layer" drop zone (drawn BELOW the last row, not above the top row — the WIP took the plan's "or a dedicated drop zone" branch; document this if a future agent expects an above-top-row zone), `LayerGestureController` hover-target tracking + same-band/locked/hidden rejection guard, full `ProjectStorage` serialize/deserialize of `trackDefs`+`layerId`, and the real pre-existing `onScroll` guard fix (`!m7ItemGestureActive && !m6RowDragActive`) — kept as-is, verified regression-free (see below). **Gap I fixed:** the WIP recorded the position-change and the track-change as TWO separate `undoStack` pushes for a diagonal drag (the common case — finger rarely moves in a pure vertical line), violating acceptance (d) "each completed drag = ONE undo step." Fix: flipped `LayerGestureController.onRowBodyUp()`'s callback order so `onItemMovedToTrack`/`onItemDroppedOnNewLayer` fire BEFORE `onGestureFinished` (was: after); they now stage undo/redo `Runnable`s into `FaditorEditorActivity.pendingLayerTrackUndo` instead of calling `recordAction` themselves, and `onGestureFinished` folds them into ONE `mergedAction()` alongside the position-change halves (also handles the track-only-no-position-change case via `maybeRecordTrackOnlyChange`, which the WIP would have silently dropped in that edge case). Compiles green (`compileDefaultDebugJavaWithJavac` executed + passed, installed on Note 9). Files touched beyond `0cf3a4c`: `layers/LayerGestureController.java`, `FaditorEditorActivity.java` only — no model/storage changes, so v8-stamping behavior is exactly what the WIP already established.
>
> **Device verification done (cheap, per testing-economics rule):** tap-scrub on master timeline ruler moved playhead + preview + captions correctly (confirms the onScroll guard fix does NOT break normal panning — the M6/M10 flags are scoped to `handleM6RowTouch`, which early-returns `false` whenever `layerRowRenderer.isWithinRowRegion` is false, i.e. always for plain projects); horizontal swipe-pan also scrubbed correctly; no crashes in logcat across the session. Ground truth: pulled sandbox `project.json` (`bdd51919…`) via `run-as` — schema v8, ONE "text" track + ONE "audio" track (locked, matching its on-screen padlock icon), `trackDefs: []` (M10 track-creation never yet exercised on this project — clean baseline for the user's hand-test). **My ONE scripted drag attempt landed on the master-timeline scrub area instead of the target row item** (coordinate miss — confirmed via before/after `project.json` diff: bit-identical, zero mutation, no crash) — per the mandate this was not iterated on. Full numbered hand-test checklist is in `tasks/REPORT_RELAY_20260702.md` and this session's final report.
>
> **Scope note:** M10 moves an ITEM between existing rows or to a new row; it does NOT reorder rows themselves (no drag-a-row-header-to-reorder feature exists yet, and none was in M10's milestone-table scope). The UX addendum's "higher row = higher z, row reorder must preserve the mapping" binding decision is real but applies to a not-yet-built row-reorder feature — flagging so it isn't assumed already satisfied.

> **2026-07-01 — "Transcribe this video?" prompt + Editor Settings sheet (BUILT GREEN, device-verified).** Feature A: `FaditorEditorActivity.maybeShowTranscribePrompt()` fires after `onVideoAssetPicked` (covers both video-insert paths that funnel through it: OS picker via `videoPickerLauncher`, and FadCam recordings via `VideoSourceBottomSheet.onRecordingSelected`; NOT covered — `insertAssetAtPlayhead`/`insertAssetAtIndex` asset-browser inserts and `initProject` initial load, out of scope per task). Dialog shows Fast/Accurate/High accuracy checkboxes (exact labels from `transcript_model_choice` strings) + "Don't ask me again" + centered OK; cancel/back = none selected, no pref change. Selected models run sequentially via a small queue (`startQueuedTranscriptions`/`runNextQueuedTranscription`) chained through a new `startTranscription(type, onDone)` overload (old call sites unchanged) — sequential because `TranscriptionEngine` already uses `Executors.newSingleThreadExecutor()`, so true concurrency wasn't possible anyway; queuing avoids stacking UI/progress state. Feature B: new `tool_settings` gear block appended at the end of the bottom tool carousel (`activity_faditor_editor.xml` ~line 1921) opens `FaditorSettingsBottomSheet` (new class, mirrors `FaditorInfoBottomSheet`'s programmatic-view/dark-gradient convention, `NestedScrollView` content, row-based so future settings are trivial to add) with a switch bound to the same pref. Pref: `Constants.PREF_FADITOR_ASK_TO_TRANSCRIBE` = `"pref_faditor_ask_to_transcribe"` (default true), getter/setter added to `SharedPreferencesManager`. Device-verified on Note 9 sandbox (bdd51919…): gear opens sheet, switch toggle persists across `am force-stop`+relaunch (confirmed in `shared_prefs/app_prefs.xml`), dialog appears/doesn't appear per pref, dialog dismiss=no-op confirmed, and a real end-to-end Fast/Vosk transcription was triggered from the dialog (no Whisper/Accurate download triggered) — words appeared on the timeline confirming the existing pipeline wiring works.

> **2026-07-01 — Small-screen scroll pass (transcript model picker FIXED, BUILT GREEN, device-verified).** Root cause of the confirmed clip bug: `transcript_model_choice` (`activity_faditor_editor.xml`) was a plain `LinearLayout` (`0dp`+`weight=1`) stacking title/subtitle/Fast/Accurate/Whisper cards/note with no scroll container, so on a short/zoomed transcript panel the lower options were unreachable. Fix: changed that node's tag to `androidx.core.widget.NestedScrollView` (same id `transcript_model_choice`, same visibility toggling from Java — `transcriptModelChoice` field is typed `View`, only `setVisibility()` called, so no code changes needed), `fillViewport="true"`, with all existing rows moved into one inner `LinearLayout` child. Drawer itself (semi-transparent side panel, 260dp width, resize handle, word long-press) untouched. AUDITED other `activity_faditor_editor.xml` drawers (top drop-downs: volume/opacity/loop/caption-keyframe/visualizer/caption/move/word-scrub) — all already-safe by wide margin (est. ≤300dp content vs ~700dp+ available at 1080×2280/480dpi); spot-verified `move_drawer` and `loop_drawer` on-device, both fit with room to spare, no changes made. Also noted (out of scope, NOT fixed): several `BottomSheetDialogFragment` pickers build rows programmatically with no scroll wrapper (`VolumeControlBottomSheet`, `AddAssetBottomSheet`, `CanvasPickerBottomSheet` [8 rows — real risk], `FlipPickerBottomSheet`); others already use `NestedScrollView` (`FilterBottomSheet`, `RelinkCatalogBottomSheet`, `VideoSourceBottomSheet`, `FaditorInfoBottomSheet`, `SpeedPickerBottomSheet`, `SpeedSliderBottomSheet`, `AssetBrowserPanel`'s RecyclerView). `CanvasPickerBottomSheet` (8 aspect-ratio rows, no scroll) is the next-most-likely clip candidate if this class of bug resurfaces. Device-verified on Note 9 sandbox (bdd51919…) at simulated 1080×2280/480dpi: opened transcript panel via the version-bar "+" (added transcript already existed so the first-run picker doesn't normally show — used "+ new version" to reveal it), confirmed only "Fast" was reachable before the fix's effect, then confirmed scrolling revealed "Accurate" (~128MB) and "High accuracy"/Whisper (~57MB) plus the trailing note, all tappable. Backed out without triggering a real transcription job (no project data mutated). `wm size`/`wm density` reset to device native (1440×2960/420dpi) at end — confirmed stable across repeated checks (note: `wm density reset` alone re-applied a stale 560 override on this device; had to `wm density 420` explicitly).

> **2026-07-01 — Caption "apply style to all clips" FIXED + undo/redo popup spring animation (BUILT GREEN, device-verified).** Batch-2 items 1+2. **Root cause (item 1):** `applyCaptionStyleToAllClips` already set the base `captionStyleId` on every clip/audio clip correctly, but per-clip caption-style KEYFRAMES (`Clip.captionStyleAtClipMs`) take priority over the base style at render time in BOTH the live preview (`FaditorEditorActivity` ~6430) and export (`ExportManager`/`CompositeExportOverlay`) — so a keyframed clip kept showing its old style everywhere the keyframe track covered, base-style change or not. **Fix:** `applyCaptionStyleToAllClips` (~10739) now also snapshots + clears each video clip's caption-style keyframes as part of the same forward action, restoring them verbatim on undo (`Clip.setCaptionStyleKeyframes`); single `LambdaAction` undo step, unchanged dialog UX. **Item 2:** `showUndoRedoHistoryPopup` (~7731) now springs the popup (`card` root) in from the anchor button — pivot computed from the anchor's on-screen position translated into the popup's fixed-offset local coordinates (no layout wait needed), scale 0.3→1 + alpha, `OvershootInterpolator(1.6f)`, 200ms in / 150ms out with `AccelerateInterpolator`; row taps and outside-touch (`ACTION_OUTSIDE` via `setTouchInterceptor`) both route through one guarded animate-out-then-real-`dismiss()` path (double-dismiss-safe); the undo/redo jump itself runs synchronously before the out-animation starts. Known gap: system back-press still dismisses instantly (no public pre-dismiss hook on `PopupWindow` for that path) — accepted for this polish item. Device-verified on Note 9 sandbox (bdd51919…): created a real keyframe repro (armed caption-keyframe mode, dropped a "hot" keyframe on a "bounce"-styled clip), long-pressed a different chip → applied "zoom" to all 4 clips + 2 audio clips, confirmed via `project.json` ground truth (clip styles all → zoom, keyframe list cleared) AND a live screenshot showing "zoom"-style captions rendering on a previously-untouched clip; one Undo restored the exact prior per-clip styles + the keyframe byte-for-byte; popup animation confirmed via `screenrecord`→ffmpeg frame extraction (mid-fade frame captured for pop-in; shrink-out completes within ~9 frames of the outside-tap, no stray/stuck popup).

## 🔭 OPEN BACKLOG — START HERE (updated 2026-07-02 midday)

**🐞 P0 BUGS (2026-07-02 user hand-test — fix BEFORE resuming the feature queue):**
1. **"Snap to zero" — USER-DIAGNOSED (2026-07-02 midday): NOT an engine bug.** Two stacked interaction
   problems: (a) tapping a CLIP in the lane under the ruler selects it AND seeks to the CLIP'S START —
   with a 1s first clip that looked like snapping to ~0 (playhead went to 1.0s, the clips' border);
   (b) fat-finger ruler drags also graze the adjacent workspace/scrub surface → both react → forward/
   backward seek fight. AGREED FIXES: clip tap = select + seek to the TAPPED position (not clip start);
   gesture EXCLUSIVITY (first surface to claim a drag owns the pointer until lift; assess a few dp of
   dead-zone between strips). An agent was mid-implementation when the midday window closed — check its
   dated entry below + `git status`: finish or `git restore` per its notes. It was also told to
   confirm/deny the gapless-engine transient theory with its instrumentation and CLOSE that thread.
   The fat-finger overlap fix needs a USER hand-test at the end (unscriptable).
2. ~~**Layer-row items can't be TAP-selected**~~ **FIXED 2026-07-02 night** (see top dated entry). Root
   cause was `LayerRowRenderer.hitTestHeader` missing an X-bounds check — it claimed the entire row band
   (header AND body) as a header hit, so `onRowBodyDown` never ran. Fixed + also fixed the two scrub-path
   bugs (parent-intercept + content-vs-raw-x delta). Tap-select, long-press-delete, and scrub-over-rows
   all device-verified. STILL OPEN structurally: §7 duration-on-create + numeric fields (sandbox items
   span full width → can't slide sideways / little empty in-row space to scrub from). §6 linkage
   highlight (PLAN_LAYERS_UX_ADDENDUM) not built this session — only tap-SELECT was in scope here.

**FEEDBACK BATCH 3 (2026-07-02, queue after P0s + rebrand/dedup):**
a. Project title header: top-center "Untitled" → long-press = rename project; tap = open project
   browser; replace pin icon with a twirl-down caret next to the centered title.
b. Project selector: rename option per project; small dim hint text "hold to select multiple"
   (a real user failed to discover multi-select delete).
c. AI chat batch: (i) paperclip attach image → vision model; (ii) small tasteful model-slug label
   (two-color gradient per DESIGN doc) so users know which model OpenRouter routed to; (iii) chat text
   must be SELECTABLE/COPYABLE (timestamps, file names); (iv) BIG-TICKET: AI project-folder integration
   (Claude-Code-style agentic access to the project's files) — needs its own plan doc before building.
d. Layers UX addendum §6 (tap-select+linkage), §7 (duration-on-create + numeric duration/placement),
   §8 (locked-item styling: keep hue, ~50% desat, diagonal hatch, padlock wiggle on attempted touch).
e. AI-feedback integration tiers (EVAL of Minimax/DeepSeek/BigPickle doc, 2026-07-02): Tier-1
   verify-then-fix durability/leak pass (faditor_audio cache bug, transitionFrameCache, messageLog,
   AssetScanner threading, KEEP_SCREEN_ON, icon collision, dead trim/heal blocks); Tier-2 portability
   package (zip export/import, Make Portable, auto-backup) after M-EXPORT-1; Tier-3 AI upgrades
   (function-calling, streaming, token discipline, stock footage); Tier-4 plugin folder P0 + templates.
   ✅ USER-APPROVED 2026-07-02 ("go ahead with your plan") — micro-items catalogued with provenance in
   tasks/PLAN_QUICKWINS_20260702.md (§A folds into the Tier-1 agent, §B into rebrand pass 1, §C
   opportunistic; skip-list documented there too). Everything remains VERIFY-THEN-FIX.

(previous backlog below — 2026-07-01 evening)
Strategy + standing rules: `tasks/EVAL_20260701_joy_creator.md`. Design/brand direction: `tasks/DESIGN_JOY_CREATOR.md`.
2026-07-01 feedback batch 1 = DONE (11/11, entries below). **USER DECISIONS RECORDED:** checkpoint commits YES
(local branch `joy-creator`, push DISABLED — commit after every green+verified feature, message style
"feat/fix: ...", NEVER push, never rewrite history; other AI harnesses may ignore git entirely); transcript
dedup AUTHORIZED if end-user-invisible + auto-backup first; export work package AUTHORIZED; crop-in-transition
GL AUTHORIZED; visible rebrand AUTHORIZED (per DESIGN doc — de-politicize, keep character mechanic for later
AI-companion reskin); **LAYERS = GO** (validate plan vs rival-CapCut bar first).

**FEEDBACK BATCH 2 (2026-07-01, user-tested; do in order):**
1. **BUG:** caption "apply style to all clips" does NOT apply to other video clips (user tested). Fix `applyCaptionStyleToAllClips`.
2. Undo/redo history popup: spring-up scale animation from the pressed button; shrink back into it on select/dismiss.
3. Tools carousel v2: (a) labels autosize to ONE line ("transitions"/"transcript" wrap today); (b) edit mode —
   swipes SCROLL (never move icons), LONG-PRESS picks up an icon (icon lifts above finger), green vertical
   insertion line under finger, near-edge hover auto-scrolls the row, persistent Done control at right screen
   edge while editing; (c) replace pin icons with a DIVIDER BAR: left of divider = pinned home row (manual,
   ≥1 enforced), right of divider = auto-sorted by usage.
4. Caption STYLE KEYFRAME UX (model support exists — CaptionStyleKeyframes): stopwatch arm toggle in caption
   advanced settings; armed: click style at playhead = drop style keyframe; `<`/`>` jump prev/next keyframe;
   on a keyframe: style click REPLACES it; a small “−” affordance REMOVES it (previous style extends, or next
   if it was first); CC bar/segments tint to match each style's selector color (pop=yellow, hot=red, …).
5. Rebrand pass 1 per `DESIGN_JOY_CREATOR.md`; then transcript dedup; then export work package; then **LAYERS**.

Older backlog (still valid, folded into the order above):

**A. VERIFY (low risk, do first):**
1. **Re-export the user's REAL project** `27221664-21e9-4e9d-8fd7-e8884bb0eb55` (21 clips, `content://`+`project://`
   sources, transitions at clips 10/15, 2 audio, long audio caption) on the NEW build and verify: orientation
   (`ffprobe` → 1080×1920, no rotation), captions play through the WHOLE song, transitions OK, audio present.
   This is the one thing not yet verified on the new code for the user's actual content. Main phone = `REAL_SERIAL`.
2. Interactive editor pass in-hand: caption sizing, crop-preview consistency, frame-accurate scrub, reorder
   minimap nav, move-clip drawer, undo on captions/filters. (Most editor changes are compile+smoke-verified only.)

**B. HELD — need user's OK / higher risk (do NOT do blind):**
3. **Transcript dedup** — project.json ≈2.4 MB; clips carry triplicate transcripts (re-runs of "High accuracy").
   Deduping shrinks it (faster save/load, less memory). EDITS transcript data → confirm with user; make it undoable.
4. **Crop-during-transition GL** — cropped clips briefly letterbox during the ~600 ms transition (the transition
   segment doesn't apply per-clip crop; GL effect renders the incoming clip uncropped). GL-shader work; verify via
   a transition-between-cropped-clips export. Cosmetic, transitions-only.
5. **Out-of-process export** — run `ExportService` in its own `android:process` so a huge project can't OOM the
   editor. Note: breaks the per-process `LocalBroadcastManager` progress delivery → must switch progress transport.

**C. POLISH / lower priority:**
6. Export quality control — output ≈9 Mbps for 1080×1920; consider a quality setting or higher default bitrate
   (`DefaultEncoderFactory.Builder().setRequestedVideoEncoderSettings(...)`; guard with `setEnableFallback`).
7. Re-verify two OLD known issues (from `tasks/DIAG_20260626.md`): audio-track caption FOLLOW inside a loop-region;
   audio-tap timeline jump / transient unmute. May already be resolved — confirm before touching.
8. Thumbnail-cache memory cap in `EditorTimelineView` for very large projects (LRU) — minor.

**Environment / how to work:**
- User runs a watcher (`watch-build.ps1`) that auto-builds + installs on save → `build.log` (UTF-16:
  `tr -d '\000' < build.log | tail -40`). **Do NOT run gradle.** Wait for `BUILD SUCCESSFUL`. Always-green: revert
  rather than break. `installDefaultDebug FAILED ... No connected devices` = fine, only compile matters.
- Devices: main `REAL_SERIAL` (SM-N986U, has real project 27221664); backup Note 9 `SANDBOX_SERIAL`
  (SANDBOX project `bdd51919…` — currently left in a modified test state: 9:16 canvas + tweaked in-points, harmless);
  new S10e `R58M34STHCA` (fresh install). adb: `C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe`
  via PowerShell (`-s <serial>`). Screenshots: `screencap` + `adb pull` (PowerShell `>` corrupts PNGs). UI nav is
  flaky — use `uiautomator dump` to get real button bounds.
- Undo system: `EditActions.*` + generic `EditActions.LambdaAction(desc, redoRunnable, undoRunnable)` (data-only
  closures); UI refresh handled by `FaditorEditorActivity.refreshEditorAfterUndoRedo()`. `maxHistory=50`, snapshot
  capture throttled to ≥1500 ms. Project saves are async (`ProjectStorage.saveAsync`), sync-flushed on pause/exit.
- Full history: DIAG Rounds 1–8 in `tasks/DIAG_20260627_perf_stability.md`; export basics in `tasks/DIAG_20260626.md`.

> **2026-07-02 — GAPLESS ENGINE LANDED, DEVICE-PROVEN (M-COMP-0; closes feedback #9 the transition jump).**
> `compositor/MasterPlaybackEngine` (multi-MediaItem + ClippingConfiguration over remuxed sources), delegated
> behind FaditorPlayerManager's API; flag `GAPLESS_ENGINE` (FaditorPlayerManager:50) DEFAULT ON, legacy = OFF;
> ineligible projects (loops/transitions/images) auto-use legacy. Measured on Note 9: 0 frozen frames at all
> seams vs 1.79s/3.64s legacy stalls, same project. Also 2026-07-02: caption hide-pill long-press now applies
> to ALL clips (b6c2a0c, user-requested hotfix, installed on S10e+Note 9); Layers M6 (multi-row timeline),
> M7 (row item move/trim/delete), M-COMP-1 (layer preview compositing) all landed; M5 REGRESSION GATE PASSED
> bit-identical on device. Full detail: tasks/REPORT_RELAY_20260702.md + PLAN_LAYERS_V2.md status ticks.

> **2026-07-02 — LAYERS M5 LANDED (compile-green; the schema-v8 keystone).** New `faditor/layers/` package
> (Track/TimedItem/TrackKind/BlendMode); Timeline gains rippleMode + Track views (synchronized-from-flat,
> ephemeral); dual-write keeps plain projects stamped v7/old-build-readable, `usesLayerFeatures()` flips to v8;
> downgrade guard added (newer-version projects load read-only, save refused — also fixed the deserializer's
> unconditional version double-stamp). ExportManager + ALL call sites untouched (regression gate holds by
> construction; on-device byte-identical proof still owed — no device tonight). Undo snapshots carry the v8 block.
> NEXT: M6 multi-row timeline (must give track flags a persistent home — see PLAN_LAYERS_V2 status note),
> then M7, M-COMP-1. M-COMP-0 deliberately deferred until a device can run the fMP4 probe. See REPORT_RELAY_20260702.md.

> **2026-07-01 — User-feedback batch 1 (BUILT GREEN).** (a) Export dialog now has an editable output-filename field (pre-filled default `Faditor_<timestamp>`, extension fixed): plumbed via new `ExportSettings.outputFileName` → `ExportManager.generateOutputPath()` honors it on BOTH the SAF and direct-File paths; sanitized twice (dialog + manager); blank/untouched = byte-identical default behavior. (b) Transcript drawer header un-clipped (pure XML, `activity_faditor_editor.xml` ~632-711): title singleLine+ellipsize, icons 32→28dp, tighter paddings — drawer structure/transparency/resize/word-long-press untouched. NEEDS on-device verify: dialog styling + custom-name export in both storage modes; header on S10e-class screen. (c) "Webcam menu" vertical-rotation box FIXED: real culprit was `AnnotationService` compact bar (`compactBar` in `annotation_toolbar_unified.xml`) — ancestors (`menuContainer` 280dp / `toolbarView` 304dp) never resized on rotate; new `applyCompactContainerSizing()` sets WRAP_CONTENT in compact mode + `updateViewLayout` re-measure; restores fixed widths for full menu. (NOT MinimizableOverlayButton/FloatingWebcamService — inspected, no bug there.) (d) Export progress stripe: new `ExportProgressStripeView` (3dp, two flat greens #4CAF50/#388E3C, animated diagonal stripes, clipped to progress), overlaid at top of `activity_faditor_editor.xml`, driven by the EXISTING `ExportService.ExportServiceListener` (no new transport); hidden on complete/error/cancel; animator stops when hidden. ⚠️ PRODUCT GAP: no in-app path back to the editor during export (fullscreen export screen blocks), so the stripe's "editing while exporting" case is unreachable until a minimize/continue-editing affordance is added — needs a safety check (is editing during a running export safe?) before building. On-device verify: compact-bar rotate slim+flush both orientations, drag still works; stripe fill/animation/hide. Working queue (easy→hard) from user feedback, remaining: webcam-menu vertical box, export top-stripe progress, caption apply-to-all, undo history popup, small-screen scroll pass, transcribe-on-add + editor settings gear, tools drawer + carousel edit mode (data-driven, pin only in edit mode), transition preview smoothness (LAST, confirm-first).

> **2026-07-01 — Undo/redo long-press history popup (BUILT GREEN, device-verified).** Long-pressing `btn_undo`/`btn_redo` in `FaditorEditorActivity` (`showUndoRedoHistoryPopup()`, ~line 7613) now opens a programmatic dark-styled `PopupWindow`: redo entries on top numbered +N..+1, a "Current position" centerline, undo entries below numbered -1..-N, auto-scrolled to the centerline on open, dismiss-on-outside-tap. Tapping a row calls `jumpUndoRedoBy()` which repeats `undoManager.undo()`/`redo()` the needed number of times (data-only) then does exactly ONE `refreshEditorAfterUndoRedo()` + `scheduleAutoSave()`, mirroring `performUndo()`/`performRedo()`. Added `UndoManager.getRedoHistory()` (~line 384, mirrors existing `getUndoHistory()`) returning the redo stack nearest-first (index 0 = next redo). Files touched: `FaditorEditorActivity.java` (long-click wiring + popup/row builders), `undo/UndoManager.java` (new accessor only, no structural change). Device-verified on Note 9 sandbox project (bdd51919…): did rotate x2 + flip, long-pressed undo → popup showed correct -1..-6 entries; tapped "-2" → jumped correctly (undo 50→48, redo 0→2, rotate/flip UI reverted to matching state, single refresh, no flicker); long-pressed redo → popup showed +2/+1 above centerline correctly ordered; outside-tap dismissed popup without side effects. All spec points confirmed on-device.
>
> Also noting: caption apply-to-all (`applyCaptionStyleToAllClips`, built earlier today by a prior agent) is in and compile-green but NOT yet device-verified — prior agent died before documenting it.

> **2026-06-28 — Export crash-hardening (Round 8, ADDITIVE only).** Defensive guards in the export pipeline, no behavior change for valid inputs: `CompositeExportOverlay.getBitmap` now (a) null-guards the caption styleId before `.equals()` and (b) wraps each non-essential overlay draw section (text/caption/waveform) in `try/catch(Throwable)` with a once-log + `canvas.restoreToCount(...)` so one bad overlay can't abort the encoder thread; `ExportManager` null-guards `getSourceUri()` in `getSourceWidth/Height`/`extractStillFrameForLoop` and moved `getOrCreateSilenceFile`'s `FileOutputStream` to try-with-resources. Core media pipeline NOT wrapped (real failures still surface + get logged by `writeExportErrorLog`). Build green. See DIAG Round 8.

> **2026-06-28 — Trimmed raw-fMP4 export FIXED (additive; normal/imported path byte-for-byte unchanged).** `ExportManager.resolveSeekableSourceUri(Clip)` (pure cache lookup, never blocks) points the VIDEO branches of `buildClipItem`/`buildTransitionItem`/`buildLoopExtensionItem` at the cached remuxed seekable file when one exists; `ExportService` warms that cache off the main thread (`collectSourcesNeedingRemux()` + `remuxSync` on a single-thread executor, then posts `export()` back to main) ONLY when raw-fMP4 sources need it. Empty list = common case = export immediately as before. Build green. See DIAG Round 5.

## 🎉 MILESTONE (2026-06-27): first video shipped end-to-end
Export is correct: native-portrait encoding (no rotation-flag), consistent caption/crop sizing (canvas-based),
frame-accurate scrub settle, fixed transitions (audio bleed + aspect), and the audio-caption drop fixed. Now in
**autonomous hardening** — see `tasks/DIAG_20260627_perf_stability.md` "Round 5". Done: durable export error
logging; activity-leak static audit (no permanent leak — it was GC starvation from the undo-snapshot bomb,
now fixed); undo coverage for filter/color (`EffectStackAction`), opacity keyframes
(`OpacityKeyframesAction`), caption-style change, and visualizer add/remove (via a new generic
`EditActions.LambdaAction`); `refreshEditorAfterUndoRedo` now syncs overlays + re-binds captions.
Also added (built green): undo for caption position (`applyCaptionPosition` + both overlay `onMoved`),
caption size (`applyCaptionSize`), caption hide via long-press, and text/image overlay ADD + DELETE
(text-ADD recorded on commit, guarded by `textOverlayAddRecorded` so placeholders don't record); SKIPPED
overlay MOVE/time-range/keyframe drags as ambiguous.
✅ **Trimmed-fMP4 export fix DONE + DEVICE-VERIFIED** (2026-06-28): additive — `ExportManager.resolveSeekableSourceUri`
(resolve file:// fMP4 → cached remuxed file at the clip-builder setUri sites) + `ExportService` warms the remux
cache off-thread before export; normal/imported projects unchanged. Verified: the trimmed raw-fMP4 9:16 export
that failed ("not seekable to start") now succeeds → 1080×1920 portrait, audio+captions intact. See DIAG Round 7.
STILL QUEUED (not done): crop-during-transition GL geometry (risky GL + hard to verify), out-of-process export
(risky), transcript dedup (data-touching, needs user confirm). All changes compile green + key ones device-verified.

### On-device verification (2026-06-28, backup Note 9 SM-N960U) — see DIAG "Round 6"
- ✅ Orientation fix CONFIRMED: 9:16 export = `1080x1920`, NO rotation flag (was 1546x870+rotate-90). Portrait
  encoding works on an older device → broadly compatible.
- ✅ Export completes end-to-end on the Note 9 with all changes; editor opens 9:16 project without crash;
  captions render at correct canvas size; durable error log captured a real failure.
- ⚠️ NEW latent bug (NOT a regression): exporting a TRIMMED raw fragmented-MP4 clip fails ("not seekable to
  start") — `buildClipItem` uses Media3 ClippingConfiguration which needs a seekable source. Proper fix
  requires resolving clips to remuxed/seekable files at export time, which means restructuring the export-start
  flow (buildComposition runs on the MAIN thread today, so blocking remuxSync there would ANR → must build the
  composition off-thread then `transformer.start` on main). This touches the just-shipped critical export path,
  so it's HELD pending a greenlight + broad device verification. Narrow impact: normal imports are remuxed.

## ⭐ Most recent work (2026-06-28) — finished undo coverage (BUILT GREEN)
- Added undo for the last uncovered edits: **caption style-keyframes** (`CaptionStyleKeyframesAction`, Clip-only)
  and **text/image overlay move / time-range / keyframe drags** (`OverlayTransformAction` over a new
  `TextOverlayItem.TransformSnapshot`; one undo step per gesture, snapshot at drag-start via new
  `onOverlayManipulated` / `onOverlayDragStart` hooks). `refreshEditorAfterUndoRedo()` now also rebuilds the
  on-canvas overlay layer. Additive only, no-op guarded. See DIAG "Undo coverage". BUILD SUCCESSFUL.

## ⭐ Most recent work (2026-06-27) — color fix, ANR fixes, editor features (BUILT GREEN, pending on-device verify)

All built via the watcher (BUILD SUCCESSFUL, installed on REAL_SERIAL). Launch smoke-tested (no FATAL).
Interactive gestures (trim/reorder/move) NOT yet user-verified — do not drive them blind on the user's
real project (risks mutating their timeline).

1. **Export saturation (color) fix.** `EffectStack.toEffects` passed the raw fractional saturation delta
   (`saturation - 1`) to Media3 `HslAdjustment.adjustSaturation`, but that API expects a PERCENTAGE
   (`HslShaderProgram` divides by 100). So a 1.45× saturation boost applied as 0.45%, i.e. invisible —
   export looked desaturated vs. the live preview (whose `ColorMatrix.setSaturation` uses the multiplier
   directly). Fix: `(saturation - 1f) * 100f`. Confirmed by bytecode-decompiling HslShaderProgram +
   measuring HSV saturation on the exported MP4 vs. source frames.

2. **ANR on insert.** `onVideoAssetPicked` ran the file copy + `getVideoDuration` (FFprobeKit, synchronous)
   on the main thread → multi-second block on SAF URIs. Now off-loaded to `assetImportExecutor`, timeline
   mutation posted back to main, "Optimizing…" overlay shown during the wait.

3. **ANR on trim-drag / general jank.** `EditorTimelineView.onDraw` drew EVERY clip with no off-screen
   culling, and `computeRects` eagerly extracted thumbnails for ALL clips (unbounded bitmap retention).
   With many clips + continuous trim-drag invalidation this starved input dispatch → "not responding."
   Fix: cull segments outside the viewport in onDraw; load thumbnails lazily for on-screen clips only
   (with a `thumbnailsFailed` guard against retry storms); reorder blocks lazily load too.

4. **Move-clip drawer reorder buttons.** New row in `move_drawer`: send-to-start (`first_page`),
   move-one-left (`arrow_back`), move-one-right (`arrow_forward`), send-to-end (`last_page`) — "all the
   way" buttons on the outside. Wired to `moveSelectedClipTo` / `moveSelectedClipBy` (reuses the
   `ReorderClipAction` undo path); buttons dim when not applicable.

5. **Reorder edge-scroll + minimap drag-to-jump.** The reorder block row now scrolls when it overflows
   (was centered + clipped → end clips unreachable). Drag a block near a screen edge to auto-scroll
   (`edgeScrollRunnable` reorder branch), or drag it onto the reorder minimap to jump to that part of the
   project. New fields: `reorderScrollPx`, `reorderMaxScrollPx`, `reorderMinimapRect`. Viewport box drawn
   on the reorder minimap.

6. **Frame-accurate trim-edge preview.** While dragging a trim handle, a floating bubble shows the EXACT
   in/out frame (`OPTION_CLOSEST`, not keyframe-snapped) so you can see which side of a baked-in jump cut
   the cut lands on. Debounced + serialized on `trimPreviewExecutor` with a cached `MediaMetadataRetriever`
   so it never blocks input. The filmstrip strip itself stays keyframe-based (fast); only the edge frame
   is exact. See `requestTrimEdgePreview` / `extractExactFrame` / `drawTrimEdgePreview`.

---

## ⭐ Systemic perf/stability diagnosis (2026-06-27 PM) — see `tasks/DIAG_20260627_perf_stability.md`

Chronic ANRs (~20 today) + a failed export traced to ARCHITECTURE, not a single bug. Device meminfo:
Dalvik heap 172 MB, 1862 Views, 5 live Activities; project.json = 2.4 MB (7,110 transcript words, duplicate
transcripts). **Root cause: snapshot-based undo serialized the FULL 2.4 MB project to JSON on the UI thread on
every edit and kept up to 200 such snapshots in heap** (~480 MB) → GC thrashing/OOM/ANR; project save was also
synchronous per edit. Export runs in-process → OOM-killed mid-render ("got decently far then quit"; no exception
persisted). FIXED (built green): async disk writes (`ProjectStorage.saveAsync`/`saveUndoHistoryAsync` +
`flushPendingWrites`), `maxHistory 200→50`, throttled/conditional snapshot capture. STILL TODO: transcript
dedup (data-touching), activity-leak heap dump, durable export-error log + out-of-process export. Restart the
app to clear leaked memory before retesting.

## Earlier work (2026-06-26) — EXPORT IS NOW WORKING END-TO-END

Full diagnosis + evidence: **`tasks/DIAG_20260626.md`**. Device/loop how-to: **`tasks/DEVICE_CONTROL_RUNBOOK.md`**
(see new §7b–7f: read live `project.json` via `run-as`, `ffprobe` source clips, capture export failures).

Verified on-device (real 16:46 export, `ffmpeg`-measured + frame-checked):
- **Export no longer crashes.** Root cause was `ExportManager.buildTransitionItem` building an *image*
  outgoing clip as a clipped progressive video → `UnrecognizedInputFormatException`. Now branches on
  `isImageClip()` (setImageDurationMs). The whole timeline renders.
- **Clip volume was NEVER applied on export.** `buildClipItem` gated `audioProcessors.add(volumeProcessor)`
  on `volumeProcessor.isActive()`, which is always false at composition-build time (BaseAudioProcessor
  only goes active after the pipeline calls configure()). Now gated on a `volumeAdjusted` boolean. Audio
  clips also now apply their **volume keyframe envelope** (was static-only) in `buildAudioSequence`.
- **End-of-timeline audio went silent** when the gap before a later audio clip exceeded the 600s silence
  WAV. Gaps are now chunked into ≤`SILENCE_FILE_MS` silence items.
- **Captions on export** now: render in the correct coordinate space (overlay was authored at canvas dims
  but composited onto the source-res frame → top-left/clipped; now scaled to the frame in
  `CompositeExportOverlay.getBitmap`), honor per-clip **caption-style keyframes incl. "hidden" windows**,
  and audio-clip captions use a sane default size (`AudioClip.captionSizeFraction` 0.12→0.060 to match
  `Clip`/`CaptionOverlayView`).

**Autonomous build→verify loop now works** (the agent could not before): the user runs a continuous-build
watcher (`watch-build.ps1` / `gradlew -t installDefaultDebug`, logs to `build.log` UTF-16). The agent
edits source, polls `build.log` for `BUILD SUCCESSFUL`, drives the export via `adb` (screencap + tap),
pulls the MP4, and verifies with `ffmpeg` (audio RMS curve) + frame extraction. Gradle CANNOT be run from
inside the agent sandbox (loopback blocked) — always go through the watcher.

**Still open** (see DIAG): looped-clip "faded" color (likely codec/color-range, low priority, shelved);
CROSS_DISSOLVE is mislabeled (maps to the "Dreamy" GL shader) — rename it and add a real cross-dissolve;
editor bugs: loop-region caption-follow while scrubbing + audio-tap timeline jump.

**Next major work:** Asset Browser (un-bust + finish) then Layers — execution plan in
**`tasks/PLAN_asset_browser_and_layers_EXECUTION.md`**.

---


---

## 1. What this project is

FadCam is an Android video editor built around Media3 Transformer. The flagship editor is `FaditorEditorActivity`. It supports:

- Multi-track timeline with video, image, and audio clips.
- Trim, speed, rotation/flip, crop, zoom, volume, opacity keyframes.
- Loop/ping-pong/still extensions before/after clips.
- Text overlays, captions from transcripts, audio visualizers (waveforms).
- GL transition effects between clips.
- Export via Media3 Transformer.

The app is large and complex; most bugs come from **timestamp math**, **effect ordering**, and **OpenGL/Bitmap cache assumptions**.

---

## 2. Repository layout

```
C:\+Projects\Screenrecorder\FadCam
├── app/src/main/java/com/fadcam/
│   ├── MainActivity.java                 # Launcher
│   └── ui/faditor/                       # Editor package
│       ├── FaditorEditorActivity.java    # Main editor UI (12k+ lines)
│       ├── export/                       # Export pipeline
│       │   ├── ExportManager.java        # Builds Media3 Composition
│       │   ├── CompositeExportOverlay.java # Captions/text/waveforms bitmap overlay
│       │   ├── CaptionExportRenderer.java  # Export caption bitmap renderer
│       │   ├── OpacityExportEffect.java
│       │   └── OpacityExportShaderProgram.java
│       ├── gltransitions/                # GL transition effects
│       │   ├── GlTransitionCatalog.java
│       │   ├── GlTransitionShaderLoader.java
│       │   ├── GlTransitionShaderProgram.java
│       │   ├── GlTransitionFrameOverlay.java
│       │   └── GlTransitionExportEffect.java
│       ├── model/                        # Clip, Timeline, etc.
│       ├── timeline/                     # EditorTimelineView, segments, playhead
│       ├── transcript/                   # Transcript, TranscriptWord, caption UI
│       ├── waveform/                     # WaveformStyleRenderer, visualizer styles
│       └── project/                      # ProjectStorage (JSON persistence)
├── app/src/main/assets/gl_transitions/   # GLSL transition shaders
├── media3-patched/                       # Forked Media3 modules (common/container/muxer)
├── tasks/                                # Agent plans, todo, lessons
│   ├── todo.md
│   ├── lessons.md
│   └── handoff.md                        # This file
└── build.gradle / settings.gradle
```

---

## 3. Build & verify

```powershell
# Compile only (fast)
cd C:\+Projects\Screenrecorder\FadCam
.\gradlew.bat compileDefaultDebugJavaWithJavac --no-daemon

# Full APK
cd C:\+Projects\Screenrecorder\FadCam
.\gradlew.bat assembleDefaultDebug --no-daemon

# Install (preserve data)
& "C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe" install -r `
  app\build\outputs\apk\default\debug\app-default-arm64-v8a-debug.apk
```

Package name: `com.fadcam.beta`.

---

## 4. Architecture that keeps breaking

### 4.1 Export pipeline (`ExportManager`)

`ExportManager.buildComposition(project)` turns the `Timeline` into a Media3 `Composition`. Key invariant:

> **Every `EditedMediaItem` must call `setDurationUs(...)` with its actual timeline duration.** Video items default to `C.TIME_UNSET`, so the timeline cursor will not advance correctly if you forget this.

Effect order inside each video item is critical:

```
[pre-overlay extras, e.g. GL transition]  (rare)
→ OverlayEffect (BitmapOverlay with CompositeExportOverlay)
→ OpacityExportEffect
→ Presentation (canvas resize / letterbox)
```

Do **not** put opacity before overlay — the overlay will re-composite at full opacity.

There is now a shared helper (after recent refactor):

```java
List<Effect> ExportManager.assembleClipVideoEffects(
    Clip clip, boolean isTransitionItem, Effect preOverlayExtra,
    FaditorProject project, List<CompositeExportOverlay.WaveformSlot> waveformSlots,
    int outW, int outH, long clipTimelineStartMs)
```

Prefer using it rather than building `videoEffects` lists inline.

### 4.2 Timestamps

- `presentationTimeUs` passed to `GlShaderProgram.drawFrame()` and `BitmapOverlay.getBitmap()` is **timeline-absolute** across the whole Composition.
- To convert to clip-local ms: `(presentationTimeUs / 1000) - clipTimelineStartMs`.
- Use the helper `ExportManager.clipMsFor(presentationTimeUs, clipTimelineStartMs)`.
- For loop extensions, each rep is a separate `EditedMediaItem`, so `clipLocalMs` resets per rep.

### 4.3 Overlays

`CompositeExportOverlay` is a single `BitmapOverlay` that draws:

- Text overlays (from `project.getTextOverlays()`)
- Captions (from clip transcript)
- Waveforms (from configured slots)

It receives absolute `presentationTimeUs`, but each effect lookup uses the appropriate local time:

- Text overlays: absolute `timelineMs` (they live on the global timeline).
- Captions: `clipLocalMs * speed` (source time).
- Waveforms: `clipLocalMs` then mapped through per-slot `setSourceMapping()` / `setLoopExtension()`.

### 4.4 GL transitions

- Adreno 650 drivers strip uniforms that are declared but not referenced in `main()`/the function chain. Fix: either reference the uniform or make it `const` in the export template.
- The export template injects `const float ratio = <value>;` to avoid stripping.
- Custom uniforms must have matching params in `GLTransitionCatalog` (or special-case handling like `GridFlip.bgcolor`).
- `GlTransitionFrameOverlay` samples the "to" frame via `MediaMetadataRetriever`. It is per-instance; synchronize access. Use `OPTION_CLOSEST` (not `OPTION_CLOSEST_SYNC`) for accurate frames.

### 4.5 Transcripts and captions

- `TranscriptWord` now has `forceLineBreakAfter`. It forces a caption phrase/line break after that word.
- UI: double-tap a word, or use the `wrap_text` icon in the transcript panel header.
- Persisted as `"b"` in project JSON (alongside `"x"` for struck).
- `Transcript.copy()` and `windowed()` preserve state; `TranscriptPanelView` edit operations preserve `forceLineBreakAfter`.

### 4.6 Loop/ping-pong/still extensions

- Loop mode `NORMAL` repeats the trimmed sub-range.
- `PING_PONG` uses `setScale(-1f, 1f)` as a **horizontal mirror stand-in** for true reverse. Media3 cannot reverse video; a proper fix needs pre-rendered reversed segments or FFmpeg.
- `STILL` extracts one frame via `MediaMetadataRetriever` and creates an image item with duration = extension length.
- Each rep is its own `EditedMediaItem`; timeline duration is explicit.

---

## 5. Recurring bug classes

| Symptom | Likely cause | Fix pattern |
|---|---|---|
| Transition is black/garbage on export | Uniform stripped by Adreno; or shader has undeclared uniform | Reference uniform in shader or make it `const` in export template; ensure catalog injects custom params |
| Text/caption/waveform appears once then disappears | BitmapOverlay cache sees same `Bitmap` instance with unchanged generation id | Return a fresh `Bitmap.createBitmap(scratch)` per frame, or otherwise bump the cache |
| Overlays are bright while video fades to black | Effect order: opacity before overlay | Order: overlay → opacity → presentation |
| Effects missing on single-clip "original" canvas | `isSimpleTrim` took fast path and skipped effects | Harden `isSimpleTrim` to require no overlays/loops/opacity/etc |
| Captions out of sync after trim/speed | Using absolute timeline ms instead of clip-local source ms | Use `clipLocalMs * speed` for captions |
| Waveform stops moving inside loop | `setSourceMapping`/`setLoopExtension` not configured or wrapping math wrong | Configure per slot; wrap in output time then apply speed |
| GL transition progress wrong on later clips | Treating absolute `presentationTimeUs` as item-local | Subtract item timeline offset |

---

## 6. Current known limitations (as of last update)

1. **PING_PONG reverse is a mirror**, not true reverse. Audio and caption highlight are forward-only.
2. **Loop-extension opacity keyframes** use the extension item's own start offset, so a clip-wide opacity envelope doesn't align perfectly across before/after extensions.
>3. ~~**Struck transcript words** are still rendered as captions~~ — FIXED. `CaptionOverlayView` and `CaptionExportRenderer` now filter struck words out of each phrase before wrapping/drawing.
4. **Generated-source clips** (AI image sequences, etc.) may not integrate with all overlay features.

---

## 7. Conventions

- Keep changes minimal. The codebase is large; small diffs are easier to verify.
- Run `compileDefaultDebugJavaWithJavac` after every change.
- Prefer shared helpers over copy-pasted effect-order code.
- Document non-obvious timestamp math with comments.
- Update `tasks/todo.md` for work-in-progress and `tasks/lessons.md` after bug fixes.
- Do not commit unless explicitly asked.

---

## 8. Quick map of hot files

| Area | Primary files |
|---|---|
| Main editor | `FaditorEditorActivity.java` |
| Export composition | `ExportManager.java` |
| Captions preview | `CaptionOverlayView.java` |
| Captions export | `CaptionExportRenderer.java` |
| All overlay bitmap | `CompositeExportOverlay.java` |
| Transcript data | `Transcript.java`, `TranscriptWord.java` |
| Transcript UI | `TranscriptPanelView.java` |
| Persistence | `project/ProjectStorage.java` |
| GL transitions | `gltransitions/*.java`, `assets/gl_transitions/*.glsl` |
| Waveforms | `waveform/WaveformStyleRenderer.java`, `WaveformOverlayInstance.java` |
| Timeline UI | `timeline/EditorTimelineView.java` |

---

## 9. When you pick up this project

1. Read `tasks/todo.md` for the current task and any open items.
2. Read `tasks/lessons.md` for patterns learned from recent fixes.
3. Run a compile to confirm the tree is green.
4. Make minimal changes, compile often, and reinstall with `-r` to preserve data.

---

> **2026-07-01 — Data-driven bottom tools carousel + swipe-up drawer + edit/pin/recent (Stages 1–3, BUILT GREEN, device-verified on Note 9 sandbox bdd51919…).**
>
> **Architecture.** The old ~25 hardcoded `tool_*` LinearLayout blocks in `activity_faditor_editor.xml` were replaced by a data-driven carousel. New package `com.fadcam.ui.faditor.tools`:
> - `FaditorTool` — immutable model: `id` (stable string key, e.g. `"mute"`), `viewId/iconViewId/labelViewId` (the SAME `R.id.tool_*` ids the old XML used — declared in `res/values/ids.xml` so they survive removal from layout), `label`, `icon` (materialicons ligature), `bindMode` (CLICK / TOUCH_VOLUME / TOUCH_OPACITY), `alwaysHidden` (trim + heal, kept GONE).
> - `FaditorToolRegistry.defaultTools(ctx)` — canonical ordered list (same left→right order as old XML). **To ADD a tool:** append one `add(...)` here with a NEW id + NEW `R.id.*` (declare cell/icon/label ids in `ids.xml`), then wire its click in `FaditorEditorActivity` onCreate near the other `findViewById(R.id.tool_*)...setOnClickListener` calls. New unknown ids auto-append at the end of any saved order (never vanish).
> - `FaditorToolsAdapter` — builds cells into `@id/faditor_tools_row` (a `SwipeUpHorizontalScrollView`). **NOT a RecyclerView / no recycling** — cells are stable views because `FaditorEditorActivity` keeps ~30 cached field refs + `findViewById(R.id.tool_*_icon/label)` that mutate icon/label/color per selection. `buildCell` reproduces the old 72dp cell exactly (28dp/22sp icon, 11sp label, `#888888`, borderless ripple). Also owns Stage-3 edit mode: wiggle (±2.5° infinite ValueAnimator), drag reorder (`moveTool`/`dragDelegate()` operating on stable views), long-press pin (amber `push_pin` marker child, GONE unless pinned), trailing **edit chip** (`appendEditChip`, tune→check/gray→green "Done").
> - `SwipeUpHorizontalScrollView` — custom HorizontalScrollView. `onInterceptTouchEvent` detects a vertical swipe-up (fires `OnSwipeUpListener` → drawer) because clickable cells otherwise eat the gesture; in edit mode it routes gestures to an `EditDragDelegate` for drag-reorder (lazily starts drag on the intercepting MOVE using the stored DOWN anchor).
> - `FaditorToolsDrawer` — Stage 2 all-tools grid overlay (4 cols, labels, dim scrim). Re-fires the real carousel cell via `getCellForId(id).performClick()` then dismisses, so every handler (incl. mute/opacity tap paths) runs unchanged. Mirrors live icon/label/color + current order; omits `alwaysHidden` / non-VISIBLE cells (context filter). Scrim-tap + Back dismiss (Back wired in the editor's OnBackPressedCallback); swipe-down works on the panel handle/title (the inner ScrollView eats swipe-down over the grid — acceptable, scrim/back cover it).
> - `FaditorToolPrefs` — persistence + ordering. Resolves: pinned first (pin order), then manual saved order OR recency-desc, with any canonical tool missing from saved structures appended in canonical order.
>
> **AUDIT findings (behavior preserved 1:1).** No per-clip-type show/hide of tools exists — `selectSegment` only updates per-tool icon/label/color state (mute %, opacity %, speed, rotate°, flip, crop, canvas, split/heal) via cached field refs; the same tool set shows for video/audio/image. Two tools are NOT simple clicks: **`tool_mute` and `tool_opacity` have rich custom `OnTouchListener`s** (tap=open drawer, long-press=toggle keyframe mode, vertical drag=live adjust value) installed by the activity AFTER `buildToolsCarousel()`. Preserved by: (a) keeping stable views with the same ids so the activity's post-build listener installs land on the generated cells; (b) marking them TOUCH_VOLUME/TOUCH_OPACITY so the adapter does NOT install its recency touch-hook on them (would be clobbered anyway) — instead recency is recorded inside `showVolumeControl()`/`showOpacityControl()`. `tool_trim`/`tool_heal` stay permanently GONE. `toolTranscript.setAlpha` pulse and `tool_captions` (label had no id originally; harmless new id added) preserved.
>
> **Prefs keys/format** (`Constants` + `SharedPreferencesManager.sharedPreferences`, JSON strings in `app_prefs.xml`):
> - `pref_faditor_tool_order` — JSON array of tool ids (manual drag order). e.g. `["move","trim","speed",...]`.
> - `pref_faditor_tool_pins` — JSON array of pinned ids. e.g. `["move"]`.
> - `pref_faditor_tool_order_mode` — `"manual"` (default) or `"recent"`.
> - `pref_faditor_tool_recency` — JSON object `{toolId: epochMillis}`.
> Settings: new **"Tool order"** switch row in `FaditorSettingsBottomSheet` (OFF=manual, ON=recent); toggling calls `FaditorEditorActivity.onToolOrderModeChanged()` → `adapter.reapplyOrder()` (live re-sort reusing stable views).
>
> **Verification (device, Note 9, project bdd51919…).** Stage 1: carousel visually identical (screenshot compare); Speed tap opened slider; mute tap opened volume drawer (custom touch listener OK); scroll shows all tools; dynamic labels/colors intact (6.5x green, 151% red, Free/9:16 green). Stage 2: swipe-up opened grid; tapping Rotate applied 90° + dismissed; scrim-tap dismissed. Stage 3: edit chip at right end; tap → "Done"/green + wiggle (uiautomator couldn't reach idle = animation running); drag moved Loop; long-press pinned Move to front (amber marker); **committed order + pin survived `am force-stop`+relaunch** (confirmed in `app_prefs.xml` and on screen); drawer shows same order; enabling Recent mode live-reordered (just-used Settings jumped to front after pinned Move).
>
> **Not done / caveats.** Manual drag in edit mode intercepts horizontal swipes as drags (so you can't scroll-without-reorder in edit mode — expected). Swipe-DOWN over the drawer grid doesn't dismiss (ScrollView consumes it); use scrim tap/Back. Drag reorder is a manual LinearLayout implementation (not literally RecyclerView ItemTouchHelper) to keep stable views for the activity's field refs. The activity's now-unused private `dp(int)` remains (harmless).

---

> **2026-07-01 — Tools carousel EDIT MODE v2 (tested-feedback rework, BUILT GREEN; device-verify PENDING — no device attached this session).**
> Reworks the v1 edit interaction per user-tested spec. Files: `tools/FaditorToolsAdapter.java` (major), `tools/SwipeUpHorizontalScrollView.java` (rewrite), `tools/FaditorToolPrefs.java` (divider model + migration), `FaditorSettingsBottomSheet.java` (removed "Tool order" row), `FaditorEditorActivity.java` (wiring + back-press), `activity_faditor_editor.xml` (wrapped tools scroll in `@id/faditor_tools_overlay` FrameLayout + `clipChildren=false` up to `controls_section`), `values/ids.xml` (+`faditor_tools_divider`/`_drop_line`/`_done`).
> **v2 interaction model:** (1) Labels never wrap — single-line + `TextViewCompat` autosize 7–11sp in a fixed 64dp label width inside the 72dp cell. (2) Edit-mode gestures: plain swipe/fling SCROLLS (never reorders); a LONG-PRESS (`ViewConfiguration.getLongPressTimeout` timer armed in the ScrollView; cancelled if the finger moves past slop first) picks a cell up — it LIFTS (translationY −22dp, scale 1.18, elevation) and follows the finger via translationX in content space; a GREEN vertical line (in the overlay frame) marks the insertion gap; near-edge (56dp) continuous auto-scroll via a repeating ValueAnimator. Release settles (snap translationX→0 after the reorder, ease translationY/scale) and drops into the slot.
> **Divider semantics:** a divider `View` sits in the row (subtle `0x33FFFFFF` normally, green + wider in edit). LEFT of it = pinned home row in manual order (= `PREF_FADITOR_TOOL_PINS`, stored order); RIGHT = unpinned, auto-sorted by `PREF_FADITOR_TOOL_RECENCY` desc. Drag math is pure index-based (`commitDrop`): capture pinned count `D` at pickup, remove dragged → `effD`, translate the visible drop-slot, `draggedPinned = insVis<=effD`, new left section = first `newD` ids of the final visible order; a drop into the right side stamps descending recency so the dropped L→R order sticks. ≥1-pinned ENFORCED (dragging the last pin right → `newD<=0` → reject + snap-back + divider width-flash). Drawer mirrors `adapter.getTools()` (pins then usage). 
> **Prefs migration (v1→v2):** `migrateIfNeeded` runs lazily on first `resolveOrder`/`dividerIndex`: existing `PREF_FADITOR_TOOL_PINS` become the left section verbatim; `PREF_FADITOR_TOOL_ORDER` + `PREF_FADITOR_TOOL_ORDER_MODE` keys are REMOVED and the mode is gone (`getManualOrder`/`isRecentMode`/`setOrderMode`/`MODE_*` deleted); if no pins ever existed, the first canonical VISIBLE tool (`speed`) is seeded so ≥1-pinned holds. Unknown/new ids land in the right (usage) section (never vanish).
> **Persistent Done:** built in the overlay FrameLayout pinned `END|CENTER_VERTICAL` (fixed to screen edge, overlays the scrolling row); shown only in edit mode; tap commits+exits, as does system back (`initBackHandler`, after the drawer check). The trailing Edit chip now only ENTERS edit mode (hidden while editing).
> **Build:** compile GREEN (`compileDefaultDebugJavaWithJavac` + `packageDefaultDebug` OK; only `installDefaultDebug` fails = no device). **NOT device-verified** — the Note 9 sandbox (`SANDBOX_SERIAL`) was offline and no emulator/AVD available all session; every spec point needs on-device confirmation (one-line labels incl. transitions/transcript; swipe-scrolls-vs-long-press-drags with before/after uiautomator order; lifted icon + green line mid-drag; cross-divider pin/unpin + last-pin snap-back; Done visible at edge after scroll; order/pins survive force-stop; drawer order). **Accepted gap:** in edit mode the `mute`/`opacity` cells still carry the activity's custom long-press OnTouchListener (keyframe toggle) alongside the new drag pickup — both could fire on a long-press of those two cells; not rewired (out of minimal-wiring scope, and the activity installs those listeners post-build). Unused `faditor_settings_tool_order_*` strings left in place (removing risks stale refs).

---

> **2026-07-01 — Preview transition-boundary stutter: diagnosis only, no mitigation shipped (investigate-first task).**
>
> Full report: `tasks/DIAG_20260701_transition_preview.md`.
>
> **Root cause (dominant).** `FaditorPlayerManager` wraps exactly one `ExoPlayer`
> with one `MediaItem` at a time — no playlist/`ConcatenatingMediaSource`. Every
> clip-to-clip boundary during playback (`FaditorEditorActivity.advanceToSegment`,
> ~line 7303) calls `playerManager.loadClip()` → `setMediaItem()` + `prepare()`,
> a full cold re-prepare (extractor probe, codec configure, first-frame decode,
> seek-to-trimStart) fired exactly at the boundary with zero pre-buffering. This
> is the jump, and it happens at **every** boundary, transition or not.
>
> **Root cause (contributing, transition-specific).** `decodeTransitionFrame()`
> (line 7187) caches decoded overlay bitmaps under a key
> (`uriString+"@"+outW+"x"+outH`) that omits `sourceMs` — so the incoming clip's
> preview bitmap freezes on the first frame decoded and never updates as the
> transition progresses, for both scrub and live playback. However, `ffprobe`
> on a real FadCam-recorded sample showed ~1s keyframe spacing, and
> `getFrameAtTime` uses `OPTION_CLOSEST_SYNC` (snaps to nearest keyframe) —
> since transitions are capped at 100-2000ms, a "correct" cache key would often
> still land on the same keyframe anyway. The companion fix that would make it
> reliably visible (`OPTION_CLOSEST` for exact-frame decode) is itself
> higher-risk: main-thread exact-frame MediaMetadataRetriever decode on every
> 50ms playback tick, which could stutter *worse* than the frozen-frame bug.
>
> **Why no mitigation shipped.** The dominant cause has no low-risk additive fix
> under this task's constraints (no new player instances, no MediaItem-queue
> restructuring, no compositor) — that's exactly Phase 5.3 (`road_map.md` line
> 308). The one isolated, additive candidate (transition cache-key fix) has an
> unverifiable/likely-negligible real-world payoff on FadCam's own footage, so
> it wasn't shipped either. **No code changed.** Build was already green;
> nothing to revert.
>
> **Device verification attempt.** Note 9 sandbox (serial `SANDBOX_SERIAL`),
> project with a plain-cut boundary at ~0.8s between clip 1 and clip 2.
> `adb shell screenrecord` truncated short capture windows unreliably (1-2s
> instead of requested 5-6s) on the first several attempts; a clean 5s capture
> was eventually obtained but landed mid-clip-2 rather than exactly on the
> intended boundary (no live view while scripting blind taps → timing drift).
> Not pursued further since the root cause was already unambiguous from source
> and no mitigation was being shipped to verify.
