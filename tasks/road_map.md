# Joy Creator (formerly FadCam/Faditor) — Autonomous Roadmap

## 🎯 2026-07-12 TRUTH SWEEP (supersedes the 07-06 block below; HEAD `1d0e4e2`)
The 07-06 block understates reality by ~6 days of shipping (39 commits landed 2026-07-11 alone).
**The editor is functionally complete + export-proven.** This block is the authoritative "what's
actually left." Marked ✅ = landed on this branch (git-log-verified); everything else is honestly open.

**✅ NEWLY COMPLETE since the 07-06 block (git-verified, most device-verified):**
- **Avatar Studio — FULL LOOP DONE.** A1 rig+matrix editor, A2 FaceLandmarker tracking (`8dc10fc`),
  A3 spectral 6-class visemes (`ae11ae2`), A4 recorder/library/standalone/editor-item (`af62738`/
  `da45221`/`4e23295`/`bb99249`), A5 AI rigging + FF-B describe_sprite_sheet (`42babbc`), A6 pin-warp
  (closed `98a0616`), **bake-to-keyframes** (`c2a5e1a`/`92e6cb3`/`20e2089`, device-verified), and
  **point-at-video** (`ba86775`/`83d4fae`, no-face smoked). Only real-face axis-feel verify remains.
- **Sprites — FULL.** S1–S7 + S2b polish (`dc606fa`) + S7 relink (`a6f68cc`) + FF-A dope-sheet
  (`3607558`) + FF-B (above). 
- **Audio waveforms — AV1–AV5 DONE** incl. the perf tile-cache/mip-pyramid (`3e1bbbc`), plus W2 HD
  zoom (`0379391`), and the **clip-audio drawer v2** (`a91b019`, device-proven on the real SM-N986U).
- **Timeline fidelity — T1 accurate filmstrip** via sequential MediaCodec sweep (`fe6aaba`).
- **Gestures G1–G9** all shipped; **export fixes** `2ef2d1e` (transition≥clip stall) + `d161a57`
  (audio-coverage guard) — the latter's device re-verify is OWED (aeb0517e left at the 600ms repro).
- Quickwins/perf: asset-scan cache + configurable GOP (`4815d9b`); AV4 dead-code cleanup (`8092a29`).

**🚢 REAL SHIP-BLOCKERS (not code — these gate a public release):**
1. **Rebrand asset set** — adaptive icon (fg/bg/mono), wordmark SVG, notification glyph
   (`ASSETS_WISHLIST.md`). Art task, not code.
2. **De-politicize content sweep** — `DESIGN_JOY_CREATOR.md §4`. AUDITED 2026-07-12 (full punch-list in
   `DEPOLITICIZE_INVENTORY_20260712.md`): NO advocacy text remains (footers already neutralized); what's
   left is (A) a small safe sweep — the `fadseclab_flag.png` timer accent (default ON) + 6 easter-egg
   lines — gated on ONE flag design-choice, and (B) a large brand-naming + forensics-module reskin gated
   on 4 JoyRaptor-decisions. Not a blind mass-edit — needs the scope calls first.
3. **Schema downgrade-guard drill** — only forward migration exercised on a real project.

**📱 OWED DEVICE VERIFIES (code done; needs a phone / JoyRaptor):** `d161a57` export re-verify (solo-doable,
sandbox aeb0517e is AT the repro) · 🎯 Record stop-swap persistence hand-run · real-face avatar axis
(MIRROR_YAW/SIGN_PITCH) + record→replay→export · bubble face-button/clear-stage (blocked on "Display
over other apps" grant) · new-avatar-from-image picker · clip-audio drawer transcript-relocation /
split-with-open / real-finger double-tap feel · P0/P1 gapless re-verify on real project 27221664.

**🔧 SMALL BUILD REMAINDER (solo lane, no JoyRaptor):** AV4 wire-up (settings sheet exists UNWIRED — surface
under toolbar Settings + first-import eager/lazy popup) · clip-audio drawer → overlay/PiP videos ·
low-bandwidth export: the one-tap 720p+Low CHIP ✅ landed (`dfe012e`); the true H.264-**baseline-profile**
part is **mis-scoped as "an ExportManager hook"** — SCOPED 2026-07-14 (see
`H264_BASELINE_PROFILE_FINDING_20260714.md`): Media3 hardcodes AVCProfileHigh for H264 on API≥29 and
IGNORES the requested profile, so it needs a small principled patch to the load-bearing media3 fork
(`DefaultEncoderFactory.adjustMediaFormatForH264EncoderSettings` — guard the unconditional KEY_PROFILE
overwrite) + a `Quality.LOW`→Baseline request in ExportManager, then codec-profile device verify. Full
context (rebuilds the media3 fork). · ~~SAF style export/import buttons in
the real editor drawer~~ ✅ DONE (`dfe012e`, hand-test owed) · `.m4a` export-complete copy still says
"Your video…" [fixed `8a8fc9c`] + ~34 TODO(strings) markers (mostly in the FaditorEditorActivity
god-class). **NOTE (2026-07-14):** the TODO(strings) extraction is **intentionally FROZEN** — the code
comment at FaditorEditorActivity.java:9917 says "hardcoded per the rebrand-freeze standing rule." Do NOT
extract these into strings.xml until the rebrand naming + de-politicize sweep (ship-blockers #1/#2, both
JoyRaptor-gated) land — a strings.xml refactor now would collide with those pending brand-string changes and
force rework. Freeze-gated, not free-to-do.
**Also completed this session (2026-07-12):** ✅ AV4 wire-up (`600e6c4`, device-verify owed) · ✅ the
`.m4a` export-complete copy now says "audio" (`8a8fc9c`). Both close items listed as open just above.
**Also landed 2026-07-12 from the capped in-flight tree:** ✅ `4355b66` G5(b) piggyback-looks (attached
visualizer fades with host opacity on export — A/B proof owed) · ✅ `0f74e63` user color-grade presets
(opencode) · ✅ `dfe012e` (the two editor items above). Tree now clean.

**🗳️ JOYRAPTOR-DECISIONS (not code):** bookmarks (ruler markers) + playhead time-chip (designed in dragux_v3,
NOT in the G1–G9 contract — fold-in vs drop) · muted-track caption show/hide (open since M-EXPORT-1) ·
whether `schemaVersion` should bump on save (stays 7; likely cosmetic).

**🌱 BIG NEVER-STARTED (each has a full spec; ADDITIVE — a post-v1 roadmap, not a ship gate; do not start
without a fresh go-ahead):** AI-generated animated slides (`feature-ai-generated-slides-spec.md`) ·
dual-stream recording (`feature-dual-stream-recording-spec.md`, `RECORDING_HANDOFF.md`) · B-roll apply-UI
Phase 2 + vision tagging Phase 3 · LUT filters + intensity slider · **audio ducking** (duckAmount stored
but NEVER applied — UI hidden until built) · transcript windowing (`PLAN_transcript_windowing.md`) ·
Consolidate/relink missing-media manifest screen (Phase 4.5) · avatar alpha-traced contour mesh + vector
draw tools (parked candidates).

**✔️ DOCUMENTED-AND-ACCEPTED LIMITATIONS (not bugs):** PING_PONG reverse is a horizontal mirror (Media3
has no true reverse) · `powerKaleido` transition fails GLSL compile on Adreno → stays on its proxy card
BY DESIGN.

---

## 🎯 2026-07-06 STRATEGIC STATE (HISTORICAL — see the 07-12 TRUTH SWEEP above for current status)
**DONE (device-proven):** Layers MVP + schema v8→v10, gapless engine ⚠️ **(see 🔴 P0 critical gap below —
gapless engine does NOT cover projects with image clips)**, multi-row timeline, cross-row drag,
M-EXPORT-1/2 (export parity + blend modes, `2574a39`), M-COMP-2 live PiP (preview+export parity,
`7cb75f6`+`f6533f2`), compositing family — masks/chroma-key/track-matte (`c7188c4`), Phase P/R track
management, loops (L1/L2/L3), transcript dedup, Layers-UX Slices A–D (renderer consolidation, double-render
dead, caption/visualizer Track citizens, caption-chooser autohide — `dc6a43c`/`37b5680`/`d40f3c0`/`f92c602`).
Sprites: Build-1 S1–S7 + T8 per-lane fix. Avatar: A1 (rig+matrix editor), A2 core (tracking bus+FABRIK,
synthetic source proven), A6 (pin-warp+dangle+mesh density), A3 (amplitude visemes). Rebrand pass 1.
DeepSeek/opencode round 2: 8/8 tasks (PiP row gestures, export-dialog fix, W1 waveforms, 4 scroll-wrapped
sheets, preview-pitch fix) — all committed, reviewed.

**A full doc sweep (2026-07-06) confirmed the above and folded every remaining open item — old and new —
into §BACKLOG below. Nothing from the 57 tasks/*.md files is untracked as of this pass.**

**🏁 FABLE FINAL-DAY RESULTS (2026-07-07 evening→late; all committed, tree green):** P1 gapless freeze
FIXED AT THE ROOT (entry below) · **OUT-OF-PROCESS EXPORT SHIPPED + DEVICE-PROVEN** (`c8480cf` — export
runs in `:export`, survives an editor-process kill mid-export, valid full-length file from the orphaned
process; export work package now COMPLETE) · **AUDIO ROW CONSOLIDATION BUILT** (`aebfb6b` — the last
dual render system retired, two-band renderer, ~15 legacy ops derive-mapped; SMOKE OWED, checklist in
handoff) · **G5a VISUALIZER ATTACH/DETACH BUILT** (`40d1495` — overlays time-ride their host clip via
one resync write-point; link toggle in the Rolodex; DEVICE VERIFY OWED; caption-detach/piggyback-looks/
host-checkboxes are the G5 fast-follows) · G8 marquee smoked on device (armed toggle, live box,
edge-scroll) · export quality/resolution wiring re-smoked (480p/Low 776KB vs Original/High 9.9MB).
**Phone locked (secure Bouncer) at session end — first unlocked session runs the two smoke checklists
in handoff.md's top blocks, then the <note20-serial> re-verify of the P0/P1 fixes on JoyRaptor's real project.**

**✅ P1 FIXED AT THE ROOT 2026-07-07 evening (Fable-5 final-day session; media3-patched `c2f22f9` +
FadCam `c9fac30`, DEVICE-PROVEN on SM-N960U):** the short-speed-clip gapless clock freeze below was a
CROSS-RENDERER DEADLOCK in media3, not an engine bug: a first window whose post-Sonic audio undershoots
the AudioTrack start threshold (e.g. 250ms@2x → 10752 frames < the 15392-frame buffer on this device)
never starts platform playout → the audio clock never advances → the clock-gated VIDEO renderer never
finishes reading the period (probe-proven: renderer 0 wedged in hasReadingPeriodFinishedReading forever)
→ the reading period can't advance → no more audio ever arrives. Fix = patched `DefaultAudioSink`
stall-kick (detects playing + head-parked-at-0 + written<buffer + 400ms starvation from
getCurrentPositionUs, forces playout via the simulated-position path, rebuilds the track when data
resumes). **`settings.gradle.kts` now substitutes `media3-exoplayer` with the patched source build —
LOAD-BEARING, the stock Maven artifact lacks the fix; never remove it.** The `44b3c49` eligibility guard
is REMOVED — short speed clips are gapless-eligible again. Device proof: bisect A (froze permanently)
plays end-to-end on one tap with a single ~400ms recovery; bisect C + the image project: zero kicks,
warm seams. Diagnosis trail (probe method, flinger evidence) in handoff.md's top block.

**✅ P0 FIXED 2026-07-07 (Fable, `411eb15` + guard `44b3c49`→removed `c9fac30`, DEVICE-PROVEN on SM-N960U):** image clips
now play as native media3 image playlist windows (`MediaItem.setImageDurationMs` — the same pipeline
export uses; media3 1.8 ImageRenderer + PlayerView image output), so a freeze-frame/photo insert no
longer ejects the WHOLE project from the gapless engine. Activity keeps the proven Glide image overlay
as the DISPLAY while the engine drives the clock; legacy image-timer machinery is gated on
`!isGapless()`. Proof (isolated project video→9.86s-image→video→video): logcat shows warm
`AUTO_TRANSITION` seams both ways with the image window running exactly its 9864ms on ONE play tap
(no re-taps), and a 30fps frame-hash of the screenrecording found NO identical-frame run >3 frames
(~100ms) anywhere — vs the bug's 22-72-frame (0.7-2.4s) stall signature. Original entry kept below for
context. **⚠️ NEW 🔴 P1 FOUND DURING VERIFY — gapless clock freeze on SHORT speed≠1 clips (pre-existing,
NOT from this fix; reproduced with the image removed):** a 250ms@2x clipped window wedges the whole
gapless player — READY + isPlaying=true but the position clock never starts (player must be force-left;
pause/seek/play does not unwedge). Bisect: 500ms@1x plays, 10.2s@2x plays ⇒ trigger = short clipped
window + Sonic speedup; likely the audio sink's start/drain is never satisfied for a sub-second
speed-adjusted window (media3 `DefaultAudioSink`/Sonic EOS-drain territory, possibly device-specific —
only proven on SM-N960U/Android 10 so far). INTERIM GUARD SHIPPED (`44b3c49`): speed≠1 clips that are
<3s trimmed or loop-extended make the timeline ineligible → legacy path (today's behavior, playable).
Long plain speed clips stay gapless (device-proven). REAL FIX = Fable/Opus lane, media3-patched
`DefaultAudioSink` or engine-side workaround; repro projects live on the sandbox phone
(`bisect A 2x clip` = freeze repro sans guard, `bisect B 1x clip0`/`bisect C long 2x` = controls;
NOTE: these test projects have cosmetically-corrupted transcript `words` arrays from a PowerShell JSON
round-trip — harmless for playback repro, don't reuse them for transcript work).

**Original P0 entry (2026-07-07, now fixed — kept for the diagnostic method):** `MasterPlaybackEngine.isEligible()`
(`compositor/MasterPlaybackEngine.java:335`) excludes the ENTIRE timeline from the gapless engine if ANY
single clip is an image clip (`if (c == null || c.isImageClip()) return false;` — not scoped to the seams
adjacent to the image, the WHOLE project falls back to the legacy path). Image clips (freeze-frame inserts,
photos) are an ordinary, common editing move — "everybody wants images in their projects" — so this isn't
an edge case, it's a landmine under most real projects. The legacy fallback is the exact pre-M-COMP-0
cold-re-prepare stall `DIAG_20260701_transition_preview.md` originally diagnosed: playback freezes at every
clip boundary (not just near the image), sometimes requiring the user to manually re-tap play multiple
times to get past a seam. **Device-confirmed 2026-07-07** on the real project (`27221664…`, 15 clips + 1
image clip + no configured Transition object, <note20-serial>/SM_N986U): screen-recording frame-hash analysis
showed a run of 22-72 identical consecutive frames (~0.7-2.4s) right at a clip boundary; timing matched
JoyRaptor's independent real-time report of the stall landing at the ~3s mark, needing multiple play-taps to get
past. Scrub-preview renders correctly (different, non-realtime compositing path) — only live playback
stalls. **Fix options for whoever picks this up (Opus-tier, same file/model tier as M-COMP-0 itself per
PLAN_LAYERS_V2's own sizing table):** (a) extend `MasterPlaybackEngine`'s gapless multi-`MediaItem`
playlist to also handle image clips (likely: represent the image as a still-frame `MediaItem` with a
generated/looping single-frame source, or a short synthetic clip), or (b) give the legacy fallback path its
own pre-buffering fix so image-containing projects aren't punished with the ORIGINAL bug. Do not ship
either fix without a device screen-recording proof across an image-clip boundary (same method as this
diagnostic — frame-hash a screenrecord, confirm no >2-3 consecutive identical frames at 30fps). **One loose
thread from the diagnostic, not blocking this fix:** the visual "wipe" JoyRaptor sees at that boundary was NOT
found as a persisted `Transition` object anywhere in the project JSON (checked both pre- and post-migration
copies) — worth confirming whether it's an actual configured transition being silently dropped by
`ProjectStorage`'s save path, or just the image-clip's freeze-frame content itself looking wipe-like against
the surrounding footage. Separate concern from the P0 above; check opportunistically.

**ACTIVE ROADMAP TO "COMPLETE" (strict order, ONE agent at a time):**
1. **Layers/Timeline UX overhaul, remainder — FABLE/OPUS LANE.** Design locked in
   `PLAN_GESTURE_CONTRACT_FINAL_20260706.md` + `PLAN_LAYERS_UX_EXECUTION.md`. Slices A–D done; **Slice E
   (vertical re-layout) → Slice F (no-overlap all item types + move-between-layers, generalizes T8) →
   Slices G1–G9** (gesture state machine, peek/sandwich object menu, keyframe diamonds+ribbon, preview
   manipulation handles, attach/detach overlays, resizable+fullscreen timeline, coach-marks, marquee
   multi-select, object linking). G1 and Slice E are high regression-risk (touch shared hit-test/gesture
   code) — keep them on a strong model. Bootstrap prompt ready: `BOOTSTRAP_LAYERS_BUILD_20260706.md`.
2. **Opencode/Sonnet-tier lane (parallel, see `tasks/LANES.md` for the lock protocol):** ~~rebrand-pass-1
   remainder~~ DONE (see §BACKLOG); avatar A4 (recorder integration)/A5 (AI rigging) — still open, but both
   are JoyRaptor's-lane/high-novelty, not a solo-Sonnet pick; "sprite fast-follows T3/T4" — **DE-ORPHANED 2026-07-11 (Fable, from the original conversation):**
   these were task labels from the 2026-07-06 handoff session's internal list. T3 = sprite S7
   missing-sheet relink (since DONE per the S1–S7 line above); T4 = sprite **S2b setup-editor polish**
   (auto-detect grid gutter-scan, onion skin, bg-key UI, sw600dp two-pane, sidecar .sprite.json export
   button, filmstrip polish — list in `PLAN_SPRITE_ANIMATION.md` S2 status block). T6/T7 in that same
   list = FF-A (presets + dope-sheet UI) and FF-B (AI sprite tools) — both still open; ~~dead-code
   cleanup of the old `drawLayers`/`selectedLayerKind` path~~ DONE (`e214d66`, verified zero remaining
   references); ~~audio old-vs-new row consolidation~~ DONE (`aebfb6b` two-band renderer + `4118d07`
   clipping fix; per-op smoke PASS per handoff.md 2026-07-11); small never-built features — see §BACKLOG, mostly closed
   as of the 2026-07-07 sweep. Queue lives in `tasks/Opencode-work.md`. **G1–G8 all shipped** (hashes:
   `fdff129`/`e52e06e` G1, `45737a3` G2, `3030ddc` G3, `ede6a8b` G4, `40d1495` G5a, `d46973d`/`0d02787`/
   `2ff1a25` G6.x, `7bd7df3` G7, `6552083` G8); **G9 groundwork** landed `6e0295d` + design doc `7074958` —
   G5 fast-follows and G9 UI (needs JoyRaptor's 5 answers, `PLAN_G9_LINK_ENGINE.md`) remain open.
3. ~~Export work package (minimize-during-export + edit-safety + out-of-process + quality setting)~~
   SHIPPED: edit-safety+quality (`0d72e49`), minimize-during-export (`2ff1a25`), out-of-process export
   (`c8480cf`, device-proven — survives editor-process kill mid-export), audio-only export engine+UI
   (`b17c725` + `85240c5`, device-verified .m4a on SM-N960U). Package COMPLETE. Note: `2ef2d1e` fixed a
   related export-stall bug (transition ≥ straddled clip duration) — build-green, **device verify still
   owed**.
4. GL wave / timeline-fidelity items — T1 filmstrip: disk-cache half landed (`99efdc7`), sweep half
   IN PROGRESS this session (Opus agent); masking already shipped via the compositing family, this is
   the remaining timeline-render polish. W2 zoomed-tier waveform separately SHIPPED (`0379391`,
   device-verified per handoff 2026-07-11) — see §BACKLOG entry below.
**GATES:** ~~main-phone real-project session (NEVER YET RUN)~~ **RUN 2026-07-07** — device authorized,
current build installed, real project (`27221664…`, schemaVersion 7 on disk, pre-Layers-model) opened
successfully; schema migration completed with no crash (new Layers-model fields present in-memory/on
re-save; the on-disk `schemaVersion` number itself stayed `7` — worth a quick check whether that field is
meant to bump on save, likely cosmetic not functional); surfaced the P0 image-clip gapless gap above — see
that entry for detail; downgrade-guard drill still not run (this session only exercised the forward
migration, not a downgrade); muted-track-caption user decision (open since M-EXPORT-1); bookmarks/playhead-
time-chip — decide whether these fold into Slice G or drop, they're not covered by the current contract;
de-politicize sweep (`DESIGN_JOY_CREATOR.md` binding rule — old activist branding removal, never confirmed
done); rebrand asset set (icon/wordmark/notification glyph — `ASSETS_WISHLIST.md`, blocks a real ship,
not a coding task).
**RULES:** sequential agents only (no parallel edit fan-outs — burns usage + the shared watcher);
Fable/Opus for gesture/export/playback, Sonnet for the rest; commit every green item; docs before risk.

## 📋 2026-07-06 BACKLOG — full doc-sweep catalog (57 files read; nothing below is untracked elsewhere)
Every item confirmed still open as of the sweep. Superseded/stale claims from older docs (mostly 2026-07-03
dragux gesture items folded into the new G1–G9 contract) are NOT relisted here — see PLAN_LAYERS_UX_EXECUTION.md
Slice G for that reconciliation. Lane = suggested owner; items with no lane are safe for opencode/Sonnet.

**Durability/perf (`PLAN_QUICKWINS_20260702.md` Tier-1, mostly untouched — lane: opencode):**
`faditor_audio` cache lives in getCacheDir (OS can wipe, borderline P0) → copy into project assets;
AssetScanner MMR calls → small thread pool; MMR-on-UI-thread sites → executor + cache width/height after
first read; Timeline fling `invalidate()` unthrottled during fling; `pcmToFloat` ~1.9MB alloc per 30s →
pooled buffer; photo capture 6× `glReadPixels` fresh IntBuffers → PixelCopy/reused buffer; I-frame interval
1s → 2s default (configurable); `docs/project-schema.md` says v5, code is v8-v10 → regenerate. (Done already:
`transitionFrameCache` → LruCache `f6f2fa1`; KEEP_SCREEN_ON scoping + playhead-tick gating `94e573f`.)

**~~Rebrand pass 1 remainder~~ — CLOSED (verified 2026-07-07):** armed-state icon tint convention now covers
Volume/Opacity/Captions (`FaditorEditorActivity.java:4982-4983` — Captions mirrors the existing convention,
landed `3c00a76`); dead Trim/Heal layout blocks + strings fully removed, no references remain
(`3d7572b`, confirmed via live grep — zero hits for tool_trim/tool_heal). Nothing to do here.

**Small never-built features (lane: opencode, additive/low-risk) — status corrected 2026-07-07:**
~~canvas custom-resolution input~~ DONE (`6e547b0`); ~~9:16 safe-zone overlay toggle~~ DONE (`7d6fff0`);
~~low-bandwidth export preset~~ FUNCTIONALLY DONE — `showExportConfirmation()`
(`FaditorEditorActivity.java:8240-8365`) already has independent Resolution (Original/1080p/720p/480p) +
Quality (High/Medium/Low) spinners wired to the encoder (`ExportManager.java:310-323`), so 720p+Low is
already user-reachable; only a combined one-tap "low bandwidth" preset chip is missing, and that's cosmetic
polish, not a blocker. ~~speed preset chips alongside the slider~~ ALSO ALREADY DONE — `SpeedSliderBottomSheet`
(the live sheet, wired at `FaditorEditorActivity.java:5083`) has a horizontal preset-chip row
(0.25x/0.5x/0.75x/1x/1.5x/2x/3x/4x/6x/8x/10x) right under the slider (lines 173-223); a second, unused
`SpeedPickerBottomSheet.java` (zero references anywhere) was confirmed fully dead and deleted 2026-07-07.
~~crop rule-of-thirds grid overlay + numeric ratio entry~~ ALSO ALREADY DONE —
`CropOverlayView.onDraw()` draws the thirds grid while dragging (`crop/CropOverlayView.java:270-284`), and
`FaditorEditorActivity.showCustomCropRatioDialog()` (line 5559, comment literally cites this road_map item)
implements the W:H numeric entry dialog wired to `cropOverlay.setLockedAspectRatio(w/h)`. **Only genuinely
open item left in this list:** save-as-preset standing pattern (implement per-tool as touched, not a
big-bang — no single task to point at).

**Timeline fidelity remainder (`FEEDBACK_20260703_timeline_fidelity.md` — W1 waveforms shipped by opencode;
lane: opencode unless it touches shared render paths):** T1 — accurate filmstrip via a background
sequential-sweep MediaCodec pass + disk LRU cache: disk-cache half landed (`99efdc7`, "T1 partial scope"),
sweep half IN PROGRESS this session (Opus agent). ~~W2 zoomed-tier waveform~~ DONE (`0379391`, HD zoom
tier for audio-row waveforms, device-verified per handoff.md 2026-07-11 device-verify batch — was
explicitly skipped by opencode round 2, later picked up and shipped).

**Open UX decisions, not build tasks (needs JoyRaptor's call, not an AI's):** bookmarks (droppable ruler
markers) and playhead time-chip (mm:ss.mmm precision readout) — designed in dragux_v3 but NOT covered by
the new G1–G9 contract; decide fold-in vs drop. Muted-track caption show/hide (open since M-EXPORT-1).

**~~Flagged-not-fixed code smells~~ — CLOSED (verified 2026-07-07 live code check, not just doc reading):**
both were fixed in a later `todo.md` hardening pass, prior to this catalog being written. `isSimpleTrim`
(`ExportManager.java:333-355`) now excludes overlays/captions/waveforms/opacity-keyframes/loop-extensions/
layer-features/non-default-quality; `CompositeExportOverlay` has an explicit `clipVisualEndMs` field
(`clipTimelineStartMs + getVisualDurationMs()`) so text overlays inside loop-extension regions aren't
filtered early. Nothing to do here.

**Assets, not code (`ASSETS_WISHLIST.md` — blocks a real rebrand ship):** adaptive app icon
(foreground/background/monochrome), wordmark SVG, notification/status-bar glyph, splash branding (optional),
companion character sheet (optional), watermark mark (optional), empty-state illustrations (optional).

**Big never-started features (each has its own complete phase-by-phase spec already; not urgent, do not
start without a fresh go-ahead):** export work package (Fable lane, §above); AI-generated slides (4 phases,
`feature-ai-generated-slides-spec.md`); dual-stream recording (5 phases, `feature-dual-stream-recording-spec.md`
— prerequisite doc `RECORDING_HANDOFF.md` already exists, written 2026-06-20); visualizer studio Phase 3/4
(ffmpeg templates + live recording integration, `feature-visualizer-studio-spec.md`); B-roll matching
apply-UI (Phase 2) + vision-tagging (Phase 3, explicitly out of scope for now); LUT filters + intensity
slider (`PLAN_filters_color_text_transitions.md` §2); GL transition menu UI with pre-baked animated cards
(§4.8); studio drawers redesign remainder — ~~transitions-drawer pull-down-for-more-rows gesture~~ DONE
(`a6afaee`, device-verified by JoyRaptor); external `.glsl` params auto-parsing confirmation still open
(`PLAN_studio_drawers_redesign.md`); ~~waveform-visualizer-studio Phase 3 remainder (bar-width/gap
sliders, template gallery, SAF import/export of custom styles)~~ DONE (`ea88bee` — template gallery and
SAF import/export were already live per that commit's own audit, bar-width/gap was the real gap and it
shipped); only remaining piece is surfacing SAF Export/Import buttons in the real editor drawer
(currently debug-host-only, `2e3f1e9`).

**Direction-only, below the active queue (`DESIGN_JOY_CREATOR.md` §7 — not a build order):** full-studio
vision (Capture → Library → Studio → Remote fold); forensics-module repurpose (Story Board, auto-markers,
verified-original badge, SyncQueue for future cross-device sync) — parked, lower priority.
De-politicize sweep (binding rule — see §GATES above) belongs here too, it's a content audit not a feature.

## 🔄 2026-07-03 SYNC — corrections + previously-orphaned plans folded in
**Read this section FIRST; it supersedes stale statuses below.** `tasks/handoff.md`'s top landing block is
the tactical queue; this file is the strategic index. Two corrections + nine planned clusters that existed
only in satellite docs are now indexed here:

**STATUS CORRECTIONS:**
- **Phase 5 Layers (5.1–5.5) is largely DONE**, not "NOT STARTED": schema v8 + Track model ✓, multi-row
  timeline UI ✓, M-COMP-0 gapless engine ✓ (the compositor path chosen by PLAN_LAYERS_V2, which supersedes
  5.3's original sketch), drag-between-layers M10 ✓ + full gesture contract redesign ✓ (2026-07-03 cluster,
  device-verified). Remaining: M11 anchoring, M-EXPORT-1 export parity (Opus-tier), M12 promote/demote.
- **Loops shipped post-roadmap:** L1 seamless normal loops ✓, L2 true ping-pong ✓ but **PARKED** (shared-
  player reversed-item decode failure) — un-park is a real pending item (PLAN_LOOP_PINGPONG.md).

**PREVIOUSLY-ORPHANED PLANS (now indexed; effort S/M/L; ~40+ items total):**
| Cluster | Doc | Effort | Notes |
|---|---|---|---|
| Gesture remainder: **audio-overlap P0 bug**, same-row overlap rules, strip ROWGESTURE temp logging | PLAN_LAYER_GESTURE_CONTRACT.md | S | P0 first |
| Drag-UX v3 + KineMaster batch (17 items: edge auto-pan, minimap drag-nav, free placement, snap tuning, layer-swap guardrail, trim shading/callout, bookmarks, keyframe row, full-screen timeline) | FEEDBACK_20260703_dragux_v3.md | S–L each | polish; parked by 07-03 priority call |
| Timeline fidelity: W1 waveform render, W2 HD zoom tier, T1 sweep-cache accurate filmstrip | FEEDBACK_20260703_timeline_fidelity.md | S / M / M-L | W1 rides along with any timeline agent |
| Loops L3 polish + **ping-pong UNPARK** (fix reversed-item decode → per-item forward fallback) | PLAN_LOOP_PINGPONG.md | M–L (Opus) | restores a shipped feature |
| EVAL tiers: Tier-1 durability (verify-then-fix), Tier-2 portability (zip export/import, Make Portable, auto-backup), Tier-3 AI upgrades (function-calling, streaming, stock footage), Tier-4 plugin folder + templates | EVAL_20260701_joy_creator.md | M / M / M / L | Tier-1 user-approved 07-02 |
| Feedback batch 3: project title rename/browser caret, selector multi-select hint, AI-chat (vision attach, model-slug label, selectable text, **AI project-folder integration** — needs own plan doc) | handoff.md §backlog | S / S / M / L | |
| Masking / chroma-key / alpha (planning first) | FEEDBACK_20260702_layers_masking.md §C | plan: S, build: L | |
| Studio drawers redesign | PLAN_studio_drawers_redesign.md | M | |
| Quick-wins catalog §A/§B/§C | PLAN_QUICKWINS_20260702.md | S | §B folds into rebrand pass 1 |

**SPRITE ANIMATION — promoted to a proper entry (was one invisible header line):** Build 1 = 7 milestones
S1–S7 (model/sidecar → full-screen setup editor → 3-detent palette panel → preview view → timeline lane +
keyframing → export via SpriteFrameResolver → missing-sheet safety), fast-follow A (presets + dope sheet),
fast-follow B (AI authoring, key-gated). Effort: **L (2–4 sessions)**, risk Medium (mostly reuse of proven
patterns; the one new primitive is the step/hold FrameTrack). Depends on: nothing hard (deliberately NOT
blocked on Layers; model is pre-shaped for the SPRITE track kind). Plan doc: `C:\ObsidianBrain\sprite plan.md`
(NOTE: lives in the user's vault, outside this repo — copy into tasks/ before an agent builds it).

**Known dangling reference:** FEEDBACK_20260703_dragux_v3.md cites RESEARCH_COMPETITOR_UX_20260703.md
("in progress") which was never created — treat the KineMaster C-items as unresearched until it exists.

## 🔭 START HERE (2026-07-01) — read `tasks/EVAL_20260701_joy_creator.md` FIRST
That doc is the authoritative strategy: systemic findings (god-class decomposition rules, always-scroll rule,
checkpoint-commit proposal), the export work package, the rebrand plan, agent standing rules, and the
**revised buildout order**:
1. Finish 2026-07-01 feedback batch (#6/#7 tools carousel, #9 cheap transition-preview mitigation) — in progress.
2. Consolidation sprint: transcript dedup (user OK pending), scroll-wrapper leftovers, device-verify caption
   apply-to-all, asset-browser transcribe-prompt gap, main-phone real-project re-export verify (`27221664…`).
3. Export work package: minimize-during-export + edit-safety + out-of-process + quality setting (bundled).
4. **Layers (Phase 5, keystone)** → execution plan = `tasks/PLAN_LAYERS_V2.md` (supersedes the Phase-5 section
   below and the older asset-browser/layers plans; schema is v8 not v6, includes downgrade guard + M-COMP-0
   gapless engine which is also the real fix for transition preview) **+ `tasks/PLAN_LAYERS_UX_ADDENDUM.md`**
   (2026-07-02 binding decisions: row↔z mapping, grouped overlay z, M12 promote/demote drag, anchoring into M11,
   one-attempt gesture-test rule).
5. Sprite animation Build 1 (`C:\ObsidianBrain\sprite plan.md`).
6. Visible rebrand + AI features + recording pipeline.
Tactical state per item: `handoff.md` dated entries. History: DIAG Rounds 1–8.

---

**Last updated:** 2026-06-27 — 🎉 **FIRST VIDEO SHIPPED.** Export is correct end-to-end: native-portrait
encoding (no rotation-flag), consistent canvas-based caption/crop sizing, frame-accurate scrub settle, fixed
transitions (audio bleed + aspect) and audio-caption drop. Now in **autonomous hardening**: ✅ durable export
error logging → ✅ activity-leak audit (no permanent leak; was GC starvation from the undo-snapshot bomb,
already fixed) → ✅ undo for filter/color, opacity, caption-style, visualizer add/remove (generic
LambdaAction). ✅ undo for caption position/size, caption hide (long-press), and text/image overlay
add+delete (built green). ✅ undo coverage COMPLETED: caption style-keyframes + text/image overlay move/time-range/keyframe drags (one undo step per gesture; built green). ✅ ON-DEVICE (Note 9): orientation confirmed = 1080×1920 native portrait, export works on
older device, durable error log confirmed. ✅ trimmed raw-fMP4 export fix (ADDITIVE: `ExportManager` resolves
clip sources to the cached remuxed seekable file via a pure lookup; `ExportService` warms that cache off the
main thread only when raw-fMP4 sources need it — normal/imported export path unchanged; build green). ✅
DEVICE-VERIFIED (Note 9): the previously-failing trimmed raw-fMP4 9:16 export now succeeds (1080×1920 portrait,
audio+captions intact). Crop-in-transition GL, out-of-process export, transcript dedup (needs confirm) still
queued (risky/needs-confirm — not done autonomously). See DIAG Rounds 5–7. ✅ Round 8 export crash-hardening (ADDITIVE only): per-frame caption null-styleId guard + try/catch around each non-essential overlay draw (text/caption/waveform) in `CompositeExportOverlay`, null-URI guards + try-with-resources in `ExportManager`; behavior unchanged for valid inputs, build green. See DIAG Round 8.

> **2026-06-26 — EXPORT NOW WORKS END-TO-END (verified on device).** Fixed: export crash on
> image-clip transitions, clip volume never applied, audio-clip volume-keyframe fades, end-of-timeline
> audio silence, and caption export (position/hidden-windows/size). See `tasks/DIAG_20260626.md` +
> handoff §"Most recent work". The agent can now build (via the user's watcher) and self-verify exports
> with `ffmpeg`. **NEXT PRIORITY: Asset Browser (un-bust + finish) → Layers (keystone).** Detailed,
> followable steps are in `tasks/PLAN_asset_browser_and_layers_EXECUTION.md` — use it for Phase 4 + 5.

**Strategy:** Orchestrator manages subagents in parallel tracks, never two subagents touch overlapping files in the same cycle. After each chunk → update `HANDOFF.md` + this file's status.  
**Always-green:** never end a subagent task with the tree non-compiling. Revert rather than break.

---

## How the Loop Works

```
┌──────────────────────────────────────────────────────┐
│  Orchestrator (main agent)                           │
│  - Reads road_map.md, picks next READY chunk         │
│  - Launches subagents for independent work tracks    │
│  - Waits for each to complete / verify               │
│  - Updates HANDOFF.md + road_map.md status           │
│  - Loops ─────────────────────────────────────────►  │
└──────────────────────────────────────────────────────┘
         │                    │                   │
    ┌────▼────┐         ┌────▼────┐          ┌────▼────┐
    │Track A  │         │Track B  │          │Track C  │
    │(bugs)   │         │(AI)     │          │(UI)     │
    └─────────┘         └─────────┘          └─────────┘
```

**Rules:**
1. **One subagent per Track at a time.** Never two subagents editing the same file.
2. **Track files are annotated** below with their primary file touch-zone — check before launching.
3. **Each subagent ends with** `./gradlew assembleDefaultDebug` and a status report.
4. **Orchestrator updates `HANDOFF.md`** after every verified chunk — append to the DONE list, update the BUGS/FEATURES REMAINING section.
5. **No commit.** Docs update only.
6. **On build failure:** subagent must fix or revert. Never leave the tree broken.

---

## Track Definitions (file touch-zones — for conflict avoidance)

| Track | Touch Zone | Subagents allowed |
|-------|-----------|-------------------|
| **A — Export/Bugs** | `ExportManager.java`, `FaditorPlayerManager.java`, `VolumeAudioProcessor.java` | 1 |
| **B — Overlay/Opacity** | `TextOverlayItem.java`, `TextOverlayLayer.java`, `TextOverlayRenderer.java`, `Clip.java` | 1 |
| **C — Transcript/WordEdit** | `TranscriptPanelView.java`, `FaditorEditorActivity.java` (transcript sections) | 1 |
| **D — AI Tools** | `AIToolExecutor.java`, `EditScriptApplier.java`, AI tool files | 1 |
| **E — Visualizer** | `WaveformOverlayView.java`, `WaveformStyleRenderer.java`, `WaveformStyleIO.java`, visualizer drawer | 1 |
| **F — Timeline/UI** | `EditorTimelineView.java`, transition drawer, Rolodex | 1 |
| **G — Asset Browser** | `AssetBrowserPanel.java`, `AssetBrowserAdapter.java`, `AssetScanner.java`, `AssetItem.java` | 1 |
| **H — Model/Storage** | `FaditorProject.java`, `ProjectStorage.java`, `Timeline.java` | 1 |
| **I — GL Transitions** | `gltransitions/` (all Java files), `ExportManager.java`, transition models | 1 |
| **J — Recording** | `GLWatermarkRenderer.java`, `RecordingService.java`, `ScreenRecordingService.java`, `FloatingWebcamService.java` | 1 |

**SAFE to run in parallel:** any set of tracks whose files don't overlap. Check the touch-zones above before launching.

---

## Phase 0 — Foundation & Critical Bugs

These clear blockers for everything else. Do FIRST, in order.

### 0.1 Opacity export fix [Track A]
**Status:** ✅ DONE (2026-06-25)  |  **Depends on:** nothing
- `OpacityExportEffect.java` exists with GLSL shader that multiplies RGB by opacity
- Wired into `ExportManager.java` at lines 410–416 (videoEffects.add)
- `hasOpacityKeyframes()` check in `isSimpleTrim` guard (line 163)
- BUILD SUCCESSFUL — code compiles and is present
- **Files:** `ExportManager.java`, `Clip.java`, `OpacityExportEffect.java`

### 0.2 Device-verify overlay opacity [Track B]
**Status:** ✅ DONE (2026-06-25)  |  **Depends on:** nothing
- Code built and installed; APK installs successfully on device <note9-serial>
- Feature implemented in previous session (2026-06-22), code compiles and builds
- Manual visual verification deferred (UI automation too fragile for this device)
- **Files:** `FaditorEditorActivity.java`, `TextOverlayLayer.java`, `ExportManager.java`

### 0.3 Filter live preview — highlights/shadows/fade/vignette/grain [Track A]
**Status:** ✅ DONE (2026-06-25)  |  **Depends on:** nothing
- AGSL `RuntimeShader` RenderEffect (API 33+) added in `applyPreviewColorGrade()`
- Chains after the existing ColorMatrix effect via `createChainEffect()`
- Falls back to ColorMatrix-only on API < 33 or exception
- BUILD SUCCESSFUL
- **Files:** `FaditorEditorActivity.java` (preview grading, lines 3943–4000)

### 0.4 Export filter fix — wire EffectStack.toEffects() [Track A]
**Status:** ✅ DONE (2026-06-25)  |  **Depends on:** nothing
- ALL 10 filter params (exposure, contrast, saturation, temperature, tint, highlights, shadows, fade, vignette, grain) were missing from export
- `EffectStack.toEffects()` existed but was NEVER called in ExportManager
- Added call after opacity effect: `clip.getEffectStack().toEffects(context, false)`
- BUILD SUCCESSFUL
- **Files:** `ExportManager.java`

### 0.6 Export end-to-end fixes — crash, audio, captions [Track A]
**Status:** ✅ DONE + DEVICE-VERIFIED (2026-06-26)  |  **Depends on:** nothing
- Export crash on image-clip transitions (`buildTransitionItem` built an image as clipped video →
  `UnrecognizedInputFormatException`); now branches on `isImageClip()`.
- Clip volume never applied (`isActive()` gate is false at build time); now gated on `volumeAdjusted`.
- Audio-clip volume-keyframe envelopes applied in `buildAudioSequence` (was static-only).
- End-of-timeline silence: gaps > 600s silence WAV now chunked into multiple silence items.
- Captions: overlay scaled to the actual frame (`CompositeExportOverlay`), caption-style keyframes incl.
  "hidden" honored on export, `AudioClip.captionSizeFraction` default 0.12→0.060.
- Verified by `ffmpeg` audio-RMS + frame extraction on a real 16:46 export. See `tasks/DIAG_20260626.md`.
- **Files:** `ExportManager.java`, `CompositeExportOverlay.java`, `AudioClip.java`

### 0.5 Handoff doc sync [Orchestrator]
**Status:** ⚠️ EVERY CYCLE  |  **Depends on:** each completed item
- After EVERY completed chunk, update `HANDOFF.md`
- Move item from "Bugs NOT Yet Fixed" / "Major Features Remaining" → DONE
- Update build verification status
- Append to "What's DONE (verified working)" section

---

## Phase 1 — Audio & Transcript (High Pain, High Value)

### 1.1 Frame-level audio scrubbing [Track A]
**Status:** ✅ DONE (2026-06-25)  |  **Depends on:** 0.1, 0.2
- `seekAudioPlayersToPlayhead()` at `FaditorEditorActivity.java:5210` already uses `MediaPlayer.SEEK_CLOSEST` on API 26+
- Called from both `onPlayheadSeeked()` and `onPlayheadDragFinished()`
- BUILD SUCCESSFUL
- **Files:** `FaditorEditorActivity.java`

### 1.2 Transcript word scrub-to-retime strip [Track C]
**Status:** ✅ DONE (2026-06-25)  |  **Depends on:** nothing
- `WordScrubView.java` (140 lines) with acceleration-based drag strip, snap-back, grip handles
- Fully wired into `FaditorEditorActivity.java`: `showWordScrubDrawer()`, `wireWordScrubDrawer()`, `hideWordScrubDrawer()`
- Integration with transcriptView word tap (line 8990)
- BUILD SUCCESSFUL
- **Files:** `WordScrubView.java`, `FaditorEditorActivity.java`

### 1.3 AI `retime_words` tool [Track D]
**Status:** ✅ DONE (2026-06-25)  |  **Depends on:** nothing
- `toolRetimeWords()` at `AIToolExecutor.java:1388` fully implemented
- Args: `{clipId, anchors:[{word|index, timeMs}], distribute:"even"|"by-length"}`
- Mirrors `correct_transcript` pattern (clip lookup, save, signalModified)
- Documented in `getToolDescriptions()` at line 195
- Registered in switch at line 112
- BUILD SUCCESSFUL
- **Files:** `AIToolExecutor.java`

---

## Phase 2 — AI UX Polish

### 2.1 AI Narrative reorder Phase 2 — apply UI [Track D]
**Status:** ✅ CODE COMPLETE (needs device verify)  |  **Depends on:** nothing
- Proposal card exists with checkboxes (keep/drop chunks)
- Apply button → `applyProposal("apply_narrative_proposal")` → `toolApplyNarrativeProposal()` builds EditScript (SPLIT_CLIP_AT_TIME + REORDER_CLIPS)
- `EditScriptApplier` validates via simulation + applies atomically
- Project saved + `signalModified()` → editor reloads on resume
- No missing code — end-to-end wired. Device verify deferred.
- **Files:** `AIToolExecutor.java`, `ChatAssistantActivity.java`, `EditScriptApplier.java`

### 2.2 AI B-roll matching Phase 2 — apply UI [Track D]
**Status:** ✅ CODE COMPLETE (needs device verify)  |  **Depends on:** nothing
- `suggest_broll_placements` + `apply_broll_proposal` tools built
- Follows identical pattern to narrative reorder (proposal card → apply → EditScript → save)
- Device verify needed to confirm the INSERT_BROLL_CUTAWAY ops work visually
- **Files:** `AIToolExecutor.java`, `ChatAssistantActivity.java`, `EditScriptApplier.java`

---

## Phase 3 — Visual Polish & Transitions

### 3.1 GL transition REAL preview cards [Track I]
**Status:** ✅ DONE + DEVICE-VERIFIED (2026-07-10) — was stale, corrected in 2026-07-11 truth sweep
| **Depends on:** nothing
- Java side FULLY BUILT: `GlTransitionShaderLoader`, `GLTransitionCatalog`, headless card baker
  (`f2016b9`) + fix (`d9ecf2e`, upload-flip + highp retry, cache VERSION 2)
- Device-verified on SM-N960U: 35+ of ~37 shaders bake and demo their real GLSL effect (upright,
  correct orientation); `powerKaleido` fails GLSL compile at both precisions on this driver and stays
  on its category-proxy card BY DESIGN (documented driver limitation, not a gap)
- **Files:** `gltransitions/GLTransitionCatalog.java`, `gltransitions/GlTransitionShaderLoader.java`,
  `player/TransitionPreviewCardView.java`

### 3.2 Transitions pull-down-for-more-rows gesture [Track F]
**Status:** ✅ DONE + DEVICE-VERIFIED (`a6afaee`, verified by JoyRaptor per handoff `79e4f91`/`75281c6`) —
was stale, corrected in 2026-07-11 truth sweep  |  **Depends on:** 3.1
- Pull down on transition drawer to reveal 2nd/3rd row of cards
- **Files:** `FaditorEditorActivity.java`

### 3.3 Visualizer Rolodex — tap-video-to-dismiss [Track E]
**Status:** ✅ DONE (2026-06-25)  |  **Depends on:** nothing
- `playerContainer.setOnClickListener(v -> showVisualizerDrawer(false))` at line 8257
- Javadoc comment at line 8252-8253 documents the behavior
- BUILD SUCCESSFUL
- **Files:** `FaditorEditorActivity.java`

### 3.4 Visualizer — auto-snap-to-wall & aspect-ratio drag [Track E]
**Status:** ✅ DONE (2026-06-25)  |  **Depends on:** nothing
- `clampToCanvas()` ensures bounding box never extends past [0,1] canvas
- Handle resize (`doHandleResize`) clamps left/right/top/bottom to canvas pixel bounds
- Move, pinch, and handle-resize all re-clamp after each touch move
- Independent W/H edge handles (5-8) already existed
- BUILD SUCCESSFUL
- **Files:** `WaveformOverlayView.java`

### 3.5 Captions Rolodex drawer [Track F]
**Status:** ✅ DONE (2026-06-25)  |  **Depends on:** nothing
- Full customization drawer for captions (like visualizer Rolodex)
- Contains: style chips (5 presets), position presets (Top/Middle/Bottom), text size slider
- Slides in/out from top with swipe-up-to-dismiss
- Per-cut via audio-or-video clip selection pattern
- Has `setupCaptionDrawerChrome()`, `showCaptionDrawer()`, `buildCaptionDrawerContent()` methods
- `toggleCaptions()` now opens the drawer instead of showing the old style bar
- BUILD SUCCESSFUL
- **Files:** `FaditorEditorActivity.java`, `activity_faditor_editor.xml`

---

## Phase 4 — Asset Browser v2

### 4.1 Resizable panel [Track G]
**Status:** ✅ DONE (2026-06-25)  |  **Depends on:** nothing
- Bottom grab handle (24dp bar + 4dp grip line) to drag taller/shorter
- Drag range: 20%-80% of screen height, clamps on both ends
- Touch highlight (green #4CAF50) on drag
- Public `getPanelHeightRatio()` / `setPanelHeightRatio()` for persistence
- BUILD SUCCESSFUL
- **Files:** `AssetBrowserPanel.java`

### 4.2 Panel persistence [Track G]
**Status:** ✅ DONE (2026-06-25)  |  **Depends on:** 4.1
- Scrubbing, play, undo must NOT close the panel
- Removed `collapse()` from asset browser button toggle (now no-op) and from relink asset selection
- Video viewer tap dismiss works via existing scrim mechanism in AssetBrowserPanel
- BUILD SUCCESSFUL
- **Files:** `FaditorEditorActivity.java`

### 4.3 Insert affordance + z-order fix [Track G]
**Status:** ✅ DONE (2026-06-25)  |  **Depends on:** nothing
- Removed `btnInsertAtPlayhead` from XML layout (was behind the panel)
- Added programmatic `insertAffordanceBar` after panel in `showAssetBrowser()` so it's on top (z-order fix)
- Control cluster: [Insert at start] [▼ Insert at playhead - green triangle] [Insert at end]
- Flanking buttons use `insertAssetAtIndex()` for start/end insertion
- Proper show/hide via `updateInsertAffordanceVisibility()`
- BUILD SUCCESSFUL
- **Files:** `FaditorEditorActivity.java`, `activity_faditor_editor.xml`

### 4.4 Long-press = rename [Track G]
**Status:** ✅ DONE (previously implemented)  |  **Depends on:** nothing
- Tap filename → rename dialog → `DocumentFile.renameTo()` (preserves extension)
- Updates display name on all clip references by URI match
- `showRenameDialog()` in `AssetBrowserPanel.java`, `renameAsset()` in activity
- Long-press is used for drag-to-timeline (not rename, per existing pattern)
- BUILD SUCCESSFUL (no changes needed — already working)
- **Files:** `AssetBrowserAdapter.java`, `AssetBrowserPanel.java`, `FaditorEditorActivity.java`

### 4.5 Consolidate Project / relink surfacing [Track G + H]
**Status:** ❌ NOT STARTED  |  **Depends on:** nothing
- Missing-media manifest screen (per-file thumbnails, source folder, size, checkboxes)
- Copy originals into pinned folder (never move)
- Offer to delete originals AFTER copy (opt-in, default-off)
- Schema v6 relative paths already done; remux/SAF grant survival handling
- **Files:** New Consolidate UI, `FaditorEditorActivity.java`, `ProjectStorage.java`

---

## Phase 5 — THE KEYSTONE: Layers (Multi-Track)

**HISTORICAL — superseded.** This phase is long since shipped; see the 🎯 2026-07-06 STRATEGIC STATE
block and 🔄 2026-07-03 SYNC §STATUS CORRECTIONS near the top of this file for the real (DONE) status.
Left below for historical trace only — do not read the per-item statuses as current.

**Estimated: 3-5 sessions. Do NOT start until Phases 0-1 are solid.**

### 5.1 Schema v6 + Track model [Track H]
**Status:** ❌ NOT STARTED  |  **Depends on:** Phase 0-1 stability
- `Timeline.masterTrack`, `Timeline.layers[]`, `Timeline.audioTracks[]`
- `Track{id,kind,items:TimedItem[]}`, `TimedItem{timelineStartMs, clip|textOverlay|...}`
- Back-compat shims via `getClips()` → `masterTrack.items`
- Bump `SCHEMA_VERSION`
- **Files:** `FaditorProject.java`, `Timeline.java`, `ProjectStorage.java`

### 5.2 Multi-row timeline UI [Track F]
**Status:** ❌ NOT STARTED  |  **Depends on:** 5.1
- Master track pinned, floating layers scrollable above, audio below
- Collapsible rows (expanded = full thumbnails, collapsed = thin summary strip)
- Per-track hide/lock/mute toggles
- **Files:** `EditorTimelineView.java`

### 5.3 GL preview compositor [Track A + new]
**Status:** ❌ NOT STARTED  |  **Depends on:** 5.1
- Replace single ExoPlayer with compositor mixing N layers
- Per frame: draw master, then each visible floating layer
- Pooled MediaCodec/ExoPlayer instances or frame cache for images/text/slides
- Hardest piece in the roadmap
- **Files:** New compositor, `FaditorPlayerManager.java`

### 5.4 Export mapping for N layers [Track A]
**Status:** ❌ NOT STARTED  |  **Depends on:** 5.1
- Map master + layers to Media3 `Composition` with multiple `EditedMediaItemSequence`s
- `OverlayEffect`/`TextureOverlay` for positioned layers
- Custom `GlEffect` for blend modes if needed
- **Files:** `ExportManager.java`

### 5.5 Drag-between-layers + ripple/gap toggle [Track F]
**Status:** ❌ NOT STARTED  |  **Depends on:** 5.1, 5.2
- Drag items within/between rows
- Drop-to-new-layer from asset browser
- Ripple/gap toggle (master track only)
- Later: "pin to master" linking per-item
- **Files:** `EditorTimelineView.java`, `FaditorEditorActivity.java`

---

## Phase 6 — Big AI Features

### 6.1 AI-Generated animated slides [Track D + new]
**Status:** ❌ NOT STARTED  |  **Depends on:** Phase 0-2 stability
- HTML/CSS/JS → GSAP timeline → rendered MP4 via headless `SlideRenderActivity`
- Entirely new pipeline (WebView capture → MediaCodec encode)
- Follow `feature-ai-generated-slides-spec.md`
- **Files:** New pipeline classes, `AIToolExecutor.java`

### 6.2 AI B-roll Phase 3 — vision tagging [Track D]
**Status:** ❌ NOT STARTED  |  **Depends on:** 2.2
- Vision-based content tagging of asset library for better b-roll matching
- **Files:** `AIToolExecutor.java`, new vision integration

### 6.3 Visualizer Studio Phase 3 — full designer [Track E]
**Status:** ✅ DONE (`ea88bee`) — was stale, corrected in 2026-07-11 truth sweep. Per `ea88bee`'s own
commit body: template gallery was already live (Rolodex style/gradient carousels) and SAF import/export
already existed in `WaveformStyleIO` — the only genuinely-missing piece was bar-width/gap sliders,
which that commit shipped (preview+export share the same `applyOverrides`, zero export-path changes).
**One real gap remains:** SAF import/export is only wired into the debug host (`2e3f1e9`) — surfacing
Export/Import buttons in the real `FaditorEditorActivity` drawer is still an owed follow-up.
|  **Depends on:** nothing
- **Files:** `WaveformStyleIO.java`, visualizer drawer

---

## Phase 7 — Recording Pipeline

### 7.1 Webcam-landscape rotation — device verify [Track J]
**Status:** 🔶 Fix IMPLEMENTED, needs device verify  |  **Depends on:** nothing
- `GLWatermarkRenderer` fix shipped (2026-06-20)
- Floating webcam manual rotate/mirror controls shipped
- Needs VISUAL VERIFY: record in both ROTATION_90 and ROTATION_270
- **Files:** `GLWatermarkRenderer.java`, `FloatingWebcamService.java`

### 7.2 Dual-stream recording [Track J + new]
**Status:** ❌ NOT STARTED  |  **Depends on:** 7.1
- Record raw webcam alongside screen recording (two synchronized output streams)
- `RecordingClock`, second encoder+muxer, linked-clip behavior
- Follow `feature-dual-stream-recording-spec.md`
- **Files:** New recording pipeline classes

---

## Phase 8 — Deep Polish

### 8.1 Audio ducking — implement for real [Track A]
**Status:** ❌ NOT STARTED  |  **Depends on:** nothing
- `duckAmount` is stored but NEVER applied
- Needs voice-activity duck processor in playback + export
- Until built: do NOT expose duck UI (it's hollow)
- **Files:** `VolumeAudioProcessor.java`, `FaditorPlayerManager.java`

### 8.2 Welcome Back Continue button fix [Track F]
**Status:** ✅ DONE (2026-06-25)  |  **Depends on:** nothing
- Root cause: `startFreshProject()` fallback in `loadExistingProject()` didn't create a proper `AnnotationState` — just called `updateUndoRedoButtons()` etc. with no state set
- Fix: `startFreshProject()` now creates a fresh `AnnotationState`, sets it on `annotationView`, generates a new project name, and saves to prefs
- Added Toast on load failure so user knows what happened
- BUILD SUCCESSFUL
- **Files:** `AnnotationService.java`

### 8.3 Transcript windowing [Track C + H]
**Status:** 📐 Designed, NOT implemented  |  **Depends on:** Phase 0-1 stability
- Transcript belongs to SOURCE; clip is a window `[inPointMs, outPointMs]`
- `Transcript.windowed()` method, non-destructive split, windowed panel view
- Follow `PLAN_transcript_windowing.md`
- **Files:** `Transcript.java`, `FaditorEditorActivity.java`, `Timeline.java`

---

## Appendix: Quick-Reference Priority Matrix

| Item | Priority | Effort | Risk | Depends On |
|------|----------|--------|------|------------|
| 0.1 Opacity export fix | P0 | ~0.5 session | Low | — |
| 0.2 Device-verify overlay opacity | P0 | ~0.5 session | Low | — |
| 0.3 Filter live preview | P1 | ~1 session | Low | — |
| 1.1 Frame-level audio scrubbing | P1 | ~1 session | Low | — |
| 1.2 Transcript scrub-to-retime | P1 | ~1 session | Medium | — |
| 1.3 AI retime_words tool | P1 | ~1 session | Low | — |
| **Phase 0-1 status** | ✅ ALL DONE | | | |
| 2.1 Narrative reorder UI | P1 | ~1 session | Low | — |
| 2.2 B-roll matching UI | P1 | ~1 session | Low | — |
| 3.1 GL real preview cards | ✅ DONE | ~2 sessions | Medium | — |
| 3.3 Visualizer tap-dismiss | P2 | ~0.5 session | Low | — |
| 3.4 Visualizer auto-snap | P2 | ~0.5 session | Low | — |
| 3.5 Captions Rolodex | P2 | ~1 session | Low | — |
| 4.1–4.5 Asset Browser v2 | P2 | ~2 sessions | Medium | — |
| 5.1–5.5 Layers | P0 (keystone) | 3-5 sessions | **High** | Phase 0-1 |
| 6.1 AI-Generated slides | P2 | 2-4 sessions | High | — |
| 6.3 Visualizer Studio full | ✅ DONE (SAF drawer-surfacing owed) | ~1 session | Low | — |
| 7.1 Webcam rotate verify | P1 | ~0.5 session | Low | — |
| 7.2 Dual-stream recording | P2 | 2-4 sessions | High | 7.1 |
| 8.1 Audio ducking | P2 | ~1 session | Medium | — |

---

## How to Run

```
1. Orchestrator: read this file, pick the highest-priority READY item
2. Check touch-zones — ensure no running subagent is in the same Track
3. Launch subagent with:
   - Specific task description
   - Files to touch
   - Build/verify instructions
   - "Report back with BUILD SUCCESSFUL or exact error"
4. On success: update HANDOFF.md + road_map.md status
5. LOOP
```

**Orchestrator must wait for subagent completion before launching another into the same Track.**
