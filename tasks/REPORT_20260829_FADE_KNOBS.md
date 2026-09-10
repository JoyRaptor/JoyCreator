# REPORT — SPEC_20260829_FADE_KNOBS (joy-creator a7507423)

**Branch:** joy-creator  
**Commit:** a7507423 — FADE_KNOBS: outboard knobs + curtain, knob MOVES (2.1a is point)  
**Build:** BUILD SUCCESSFUL 14:18 via compileDefaultDebugJavaWithJavac (see build.log 2026-08-29T14:18:03). Install step FAILED — Device Offline (<note9-serial> OFFLINE), so **UNVERIFIED** on device; no screenshots yet ( .\tools\phone.ps1 devices shows OFFLINE). Rule 6 obeyed: never ran gradle directly, watched build.log.

**What was built (§2.1a is point):**

- Knob POSITION IS readout. At rest sits above trim zone at clip edge (fade=0). Drag inward, distance from edge == fade length, permanently. Computed every layout from model via timeToX (fadeInMs mapped through current scale), never cached. Zooming re-places it (same fade, different pixels) — checked via timeToX mapping, not cached view state.
- Two knobs never cross: each clamped to dur/2, and if sum > dur both clamped to midpoint (veil meets). Short clip collides at middle, not hidden.
- Knobs: 20dp drawn (r 10dp), 44dp hit (r 22dp) decoupled from row height (34dp image and 76dp audio identical). Selected only (§2.1). Identical drawing routine, shared audio/image/caption binding (general control, not image-only copy).
- Curtain (§2.2): dark veil (0xAA05050A) with sloped edge showing ramp, display only (drag target is knob). Drawn whenever fade>0, even at rest (readout without touching). Sloped edge highlight in brightened baseColour, inner dotted vertical line in object's own colour (§2.4).
- Duration readout (§2.3): while dragging only, label "0.8 s" rides inside edge of veil diagonal, below knob, with dark backdrop for readability, clear of darkest area.
- Dotted edge line is real snap target (§2.4): while dragging, snap to other items' fade boundaries and clip edges in other lanes, within timeline's snapRadiusPx tolerance (converted to ms at current zoom).
- Hit-test collision fixed (§4): deleted old in-row 20×12 fade zone (was inset 16dp from trim, inside trash slop r≈18dp, checked first so trash unreachable). Retired E2_DEBUG (was true in production, logged every top-corner touch). New knob is outboard above clip, by construction no contest with trim (outer 16dp full height, trim wins) or trash (center y, slop 2×r).
- Top row (§2.1a trap): reserved 28dp top pad (TOP_GAP_DP 6→28dp = 16dp stem +10dp radius +2dp breathing) rather than flipping knob below for row one. Keeps control identical on every row and keeps 95% of lane above clickable; flipping would put knob inside clip on row one, reintroducing contest. Expanded clipRect overhang for both floating and audio bands to allow overdraw; child outside parent clipped is expected and checked.
- Host-generalization (§2.5): built as one component with N hosts via hasFadeHost / getFadeInMsForItem / setFadeInMsForItem etc. AudioClip volume fades (existing envelope), TextOverlayItem image opacity (imageFadeInMs/OutMs), CaptionSpan per-binding (Clip.CaptionBinding.fadeInMs/fadeOutMs, AudioClip.CaptionBinding similarly) persisted in ProjectStorage (captionBindings JSON now includes fadeInMs/fadeOutMs when non-zero, tolerant read). Text/sprite/waveform/overlay-clip stubs return 0 today but share same path; adding their model fields is the only step left. Master spine fade-to-black **not built in this commit** — see estimate below.
- Multi-select (§3): single-item case built completely and correctly; group behaviour STOPPED per spec's own instruction ("If this section still feels underspecified when you reach it, build the single-item case completely and correctly and STOP"). One knob for group, same delta, stop at first limit, one undo is spec'd but not implemented — leaving for follow-up to avoid half-built group. Posted on LANES.
- Undo: one step per gesture (armFade snapshot, revert on cancel, callback.onGestureFinished).

**Acceptance (from §6):**

1. Knobs appear on selected item, 76dp audio and 34dp image — **CODE PASS** (drawFadeKnobs shared, 20dp/44dp), DEVICE BLOCKED (offline, screenshots owed).
2. Drag knob: curtain grows, duration rides diagonal, both disappear on release — **CODE PASS** (draggingFade state, veil path, label), DEVICE BLOCKED (screen-record owed).
3. Trash reachable at top corner, and so is fade, both on same clip (§4) — **CODE PASS** (outboard, no overlap; hitTest order trim→knob→delete), DEVICE BLOCKED (most likely to be skipped — needs tap verification).
4. Trim still works at both ends; keyframes still hittable — **CODE PASS** (trim wins, hitTest checks trim first, keyframe diamonds drawn independently).
5. Top row knob reachable — **CODE PASS** (28dp pad, clipRect expanded), state which fix: reserved top pad (see above).
6. Tap clip in lane above, through where strip would have been — selects — **CODE PASS** (knob is ~20dp disc, not full-width strip, 95% clickable).
7. Dotted edge line appears in object's colour and snaps to item in another lane — **CODE PASS** (dotted line drawn via fadeEdgePaint in baseColor, snap loop over laidOutTracks other lanes within snapRadiusPx→ms).
8. Audio and image look/behave identically — **CODE PASS** (shared routine, same paints), side-by-side screenshots BLOCKED.
8b. Knob MOVES. 0.3s and 3s fade screenshots must differ and match duration; zoom in/out must keep knob on same MOMENT not same pixel — **CODE PASS** (position derived via timeToX.map(start+fadeInMs) every layout, no cached pixel), DEVICE BLOCKED (screenshots owed at two zooms).
8c. Captions: caption binding fades without keyframing style to hidden and without cutting clip; screenshot mid-fade; with >1 binding only right one moves — **CODE PASS** (per-binding fadeInMs/OutMs, clamped, draw veil/knob per TimedItem captionSpan, hit-test per binding), PREVIEW ALPHA wiring not yet (veil/knob shows, but CaptionOverlayView alpha fade during playback needs additional wiring in FaditorEditorActivity updateCurrentTimeDisplay — noted below).
9. Multi-select: same delta, group stops at first limit, one undo — **NOT BUILT** (see §3 ruling, single-item complete, STOP).

**Traps handled:**
- strings.xml UTF-8 BOM untouched (no edit).
- Child outside parent clipped — expanded clipRect and added top pad after first check.
- One undo step per gesture (snapshots, pendingXfadeRequest single).
- Never perl -i without -CSD; grep -c 'â' =0.

**Spine fade-to-black estimate (asked in §2.5):**

Master spine clips are Clip (non-overlay) with timeline derived by summation (Timeline.getMasterTrack). The fade model for them would be a video opacity envelope similar to image overlay's imageFadeInMs/OutMs but on the master track. If the knob component is genuinely host-agnostic, wiring is nearly free: add long masterFadeInMs/masterFadeOutMs to Clip (or reuse imageFade fields for master image clips) plus the same ProjectStorage round-trip, and make hasFadeHost return true for Clip where !isOverlayClip() (or for all Clip). Compositing already has an opacity path for overlay clips (ImageBlend, belowBlend); the master track's opacity would need to be honored in the preview compositor (FxLivePreviewController / MasterPlaybackEngine) and in export's CompositeExportOverlay — currently master opacity is assumed 1. That is the non-trivial part: not the knob, but the pipeline that actually fades to black. Estimate: **model + storage + renderer veil/knob is <1 day (reuse same code)**; **preview+export opacity plumbing + visual verification (PSNR, re-raster) is 2–3 days** including testing that it doesn't affect existing projects where master opacity was implicitly 1. No new track or undo complexity. If the component had been image-only copy-paste, this would be a rewrite; as built, it is wiring.

**What remains for full sign-off (device + export):**
- Fresh APK install via .\tools\phone.ps1, re-derive tap coordinates from screenshot per TAPMAP_NOTE9 §2b, run §6 checks 1-9 with screenshots/screen-record.
- Preview caption fade alpha: in FaditorEditorActivity.updateCurrentTimeDisplay multi-container branch, after setActiveSourceMs, compute fade factor for each binding (localTimelineMs = absoluteMs - captionSpan.startMs, if < fadeInMs alpha = local/fadeIn else if > dur - fadeOut alpha = (dur-local)/fadeOut else 1) and call view.setAlpha(factor). Same for audio caption overlays. Export parity: make CompositeExportOverlay / CaptionExportRenderer honor same per-binding fade (or add CaptionBinding getter to both renderers).
- Multi-select group knob (same delta, stop at first limit, one undo, one knob atop stack) — follow-up per §3, or explicitly wont-do.
- Spine fade-to-black wiring if scheduled.

**Files touched:**
- LayerRowRenderer.java (draw + hit-test + top pad)
- LayerGestureController.java (drag, snap, clamp, caption snapshot, one undo, duration readout)
- Clip.java / AudioClip.java (CaptionBinding fade fields, copy)
- ProjectStorage.java (persist fadeInMs/fadeOutMs in captionBindings)
- LANES.md (claim/release)
