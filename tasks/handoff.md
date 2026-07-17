# FadCam AI Handoff

> **♦ 2026-07-17 afternoon — FABLE(5) orchestrating OPUS subagents: the ENTIRE 0717 UI queue is
> BUILT + the fresh build is INSTALLED on the sandbox (29e37138 re-authorized; APK 13:12).**
> 4 commits, all compile-green, each reviewed line-by-line before commit:
> **(1) `fdf72ec` D2a curve math:** Easing grew EXPO pair / ANTICIPATE / OVERSHOOT / 3 springs
> (ω=odd·π/2 so cos≡0 at t=1 — exact endpoints, no residual ramp) / BOUNCE / STAIRS_4;
> endpoint contract JVM-verified (scratchpad harness: 0/1 exact, bounce in-range, bouncy>soft).
> **(2) `0bf5235` D2 widget + C7 + D2a picker:** new `KeyframeDiamondControl` (‹♦› — chevrons
> prev/next, custom-drawn diamond, carved-× on-key, tap=add/remove, long-press=picker) adopted by
> the drawer rows; Prop grew armed/easeGet/easeSet; C7 inline "Static — tap ♦ to animate" hint +
> pulse (NO auto-key); `EasePickerPopover` — 14 tiles rendered FROM apply(), ⊘ first, GREEN ring
> 0xFF4CAF50, edits the segment the playhead is IN (left key owns it); also on the ribbon diamond
> long-press. Old dialog "Add keyframe"/"Clear" buttons killed; "Clear all keyframes" = drawer
> action (does NOT reset range anymore — deliberate).
> **(3) `ddeb17d` C4 row display-parity:** consolidated GREEN property-key diamonds (66ms-bucket
> union) below the midline on overlay/sprite blocks; horizontal diamond drag = move the bucket in
> time (SELECTED item only, trim-style routing, neighbor clamps, one undo, cancel-restores);
> opacity-only rubber-band over a scrim (≥2 OPACITY keys).
> **(4) `2fbc874` C8 closed:** AUDIT FINDING — A1 edge auto-pan + DRAG_OUTLINE machine were
> already in 470f936 (the "not in code" was the stale-build artifact); real gaps fixed: gap-hover
> suppresses edge-pan (isHoverGapActive), same-row outline WHITE (was item-blend ≈ purple on
> text/sticker = the 07-04 ambiguity).
> Slice 2 gap-insertion (`32d19bb`) was already committed by the prior harness — reviewed, spec
> status updated. **DEVICE-VERIFY OWED (JoyRaptor, on the JUST-INSTALLED sandbox build):** every
> DEVICE-VERIFY line in the 0717 spec's status block items 2/3/4 + the C8 line, PLUS the older
> queues (gesture grammar, dual-stream checklist, lane visuals). **NEXT BUILD LANES (pick per
> spec's STILL-OPEN list):** ObjectMenuSheet Prop adapters for audio/PiP/visualizer; dual-stream
> Phase 4 linked clips; visualizer-studio Phase 4; export-side GL transition A/B; §4.5 eye/lock
> migration. WARNING: NEVER `gradlew --rerun-tasks` — it corrupts media3-patched inter-module
> jars (recover: normal incremental build, or clean lib-extractor/lib-exoplayer).**
> **ðŸŽ›ï¸ 2026-07-17 morning â€” FABLE(5) with JoyRaptor live: UI-spec critique + DRAWER BATCH built
> (`f8daae1`, compile-green 13s; INSTALL BLOCKED â€” sandbox 29e37138 went adb `unauthorized`
> mid-session, JoyRaptor must accept the USB prompt / cycle USB debugging; watcher installs next build).**
> **READ FIRST: `tasks/FEEDBACK_20260717_ui_dialogs_and_keyframes.md`** â€” ground-truth answers
> (dual-stream toggle IS visible on the Note 9, probed live: 16 concurrent HW AVC encoders; it
> lives in FadRec â†’ Screen Recording card â†’ "Record webcam as separate file", NOT the helper
> panel), spec corrections C1â€“C9, JoyRaptor's decisions (double-tap = TYPE editor everywhere; hold =
> general drawer; drawer trash gone, Ã— right; D2a practical ease-curve set incl. 3 spring damping
> levels + stairs). **BUILT:** unified preview grammar (tap=selectâ†’one selection pipeline w/ row
> auto-reveal, double-tap=type editor, hold=drawer) on TextOverlayLayer + SpriteOverlayView; modal
> image dialog RETIRED (images â†’ drawer); peek range chips (Start/End-here usable WHILE scrubbing,
> now with undo); drawer retargets on cross-object selection; sprite delete moved to the badge
> (drawer trash was silently its only delete). **NEXT (in order, per the spec's execution block):**
> (2) dragux_v3 SLICE 2 gap-insertion + C5 riders + C8 stragglers â€” START OF A FRESH WINDOW per the
> blueprint's own rule; (3) <â™¦ï¸> widget + C7 arming honesty + C4 row keyframe display-parity;
> (4) D2a ease picker. **DEVICE-VERIFY OWED (JoyRaptor, real fingers):** the whole gesture grammar,
> End-here-while-scrubbing, drawer retarget, row reveal, sprite badge delete.**

> **ðŸ¤ 2026-07-17 night â€” FABLE(5) orchestrating OPUS/SONNET subagents: three spec lanes landed
> in parallel, all compile-green on the watcher; NONE device-verified yet.**
> **(1) DUAL-STREAM RECORDING Phases 0â€“3 (`2970737` me, `c3b77fa`+`23b194b`+`a43e25a` Opus agent):**
> Phase 0 capability gate + hidden-unless-supported settings toggle (pref `fadrec_dual_stream_webcam`);
> I wrote the "architecture reality map" into the spec (the webcam is FloatingWebcamService's OWN
> session â€” the new encoder surface is its SECOND stream; screen side only needed its pause/PTS state
> extracted); agent built `RecordingClock` (shared pause, per-stream PTS baselines, byte-identical
> no-op for plain recordings), `WebcamEncoderPipeline` (`<name>_webcam.mp4`, teed PCM audio), the
> FloatingWebcamService second-surface bridge, and ScreenRecordingService orchestration with clean
> screen-only downgrade. Deferred: webcam-file rollover, avatar mode, Phase 4 `linkedClipId` editor
> linkage. **The spec's Status block contains the full adb/ffprobe device-verify checklist.**
> **(2) LANE_BADGES Â§2+Â§3 (`1693565`, Opus agent):** item preview images (image start-thumb, sprite
> cell at every frame key, video filmstrips REUSING the master T1 cache) + pinned-thumbnail scroll,
> all provider-injected so LayerRowRenderer stays pure-draw, viewport-culled. Â§4.5 eye/lockâ†’per-object
> migration still deliberately deferred.
> **(3) Transition clamp feedback (`5619b89`, Sonnet agent):** span-clamped resize drags now toast the
> seam limit, and the chip label shows "1.6s (of 5.0s)" when stored duration exceeds drawable span.
> **DEVICE-VERIFY QUEUE:** dual-stream checklist (spec Status block); lane Â§1-Â§3 visuals; clamp toast;
> plus everything from the earlier 2026-07-17 blocks. **REMAINING BUILD:** dual-stream Phase 4 (editor
> linked clips â€” editor lane); visualizer-studio Phase 4 (live-recording viz, perf-gated); export-side
> GL transition A/B.**

> **ðŸŒ€ 2026-07-17 late â€” FABLE(5): GL transition preview 4-bug fix (`ab4fc42`), DEVICE-VERIFIED on
> sandbox 29e37138 (installed there; main phone has the previous build).** JoyRaptor's "3 levels of
> disjointedness": (1) A sampled from its HEAD â€” seam preview double-subtracted the window offset
> (currentPos is already trim-relative); (2) BOTH clips frozen on their first frame â€” transition
> frame cache key had NO time component (now 50ms-bucketed); (3) frames inverted for the transition's
> duration â€” preview textures upload top-row-first vs GLTransitions' bottom-origin uv (preview shader
> template now V-flips at sampling; spin shaders made it read as horizontal mirror); (4) 9:16-on-16:9
> stretched fullscreen â€” GL quad fills the view, frames now letterbox-composed before upload. ALSO:
> duration quick-pick presets 100msâ€“10s; PROVED the yesterday clamp fix on device (pushed 5000ms
> RADIAL into cebc19e0 â†’ survives load round-trip; mid-transition screenshot upright+letterboxed).
> **REMAINING transition UX debt:** on short clips the resize drag is legitimately clamped to
> min(neighbor clip duration) but SILENTLY â€” the chip label even shows the clamped span while the
> model holds the full value ("1.6s" label vs 5000ms stored). Surface the limit (toast/label) next.
> Export-side GL transitions untouched this pass â€” JoyRaptor should A/B an export with a GL transition;
> the export overlay (GlTransitionFrameOverlay) has its own orientation/scale conventions.

> **ðŸŽ¨ 2026-07-17 â€” FABLE(5): JoyRaptor-feedback batch + spec sweep. 7 commits (`46523ed`â€¦`4b6533e`),
> ALL compile-green; FULL build installed on main phone REAL_SERIAL at session end.**
> **(1) LANDED THE PRIOR SESSION'S IN-FLIGHT TREE (`46523ed`, 1397 lines, was uncommitted):** slides
> Phase 4 overlay mode + time-stretch trims + freeze handles + SlideCodeBottomSheet + runtime r2 +
> double-transition clamp fix â€” all device-proven per the spec doc; icons/PSD left uncommitted (not ours).
> **(2) QUICK FIXES (`da555f1`):** (a) SlideCodeBottomSheet keyboard â€” sheet window wasn't IME-focusable
> over the immersive editor (clearFlags + ADJUST_RESIZE + explicit showSoftInput); (b) transcript
> version-chip menu gained "Copy word-level timing" (`[mm:ss.mmm-mm:ss.mmm] word` per line, exact spans,
> round-trips through import with NO interpolation â€” TranscriptIO.parseWordLevel runs first); (c) the
> transition "handles snap back" bug: the 100..2000ms clamp lived in FOUR places and the Transition
> CONSTRUCTOR clobbered longer durations on every load round-trip â€” all sites now share
> `Transition.clampDurationMs` (50ms..10s; export still seam-clamps via effectiveTransitionMs).
> **(3) CAPTIONS OVERHAUL (`0ac1fa2`, JoyRaptor's pro-tool spec):** CaptionStyle grew font/outline/shadow
> (+JSON); both renderers honor them identically; new `CaptionStyleStore` (app-wide named customs
> `custom_*` listed in the bottom ticker + per-clip drafts `customdraft_<clipId>` so tweaking one clip
> never restyles another; :export inits the store in CompositeExportOverlay). Drawer rebuilt: style row
> gone, 3-line position toggle + size on one row, collapsed font carousel, Pop/Zoom/Bounce, Text/
> Highlight swatches, Box/Outline toggle+color, Shadow, palette picker, save-floppyâ†’named style in the
> ticker, trash, export/import styles as text.
> **(4) LONGFILE ROUND-4 (`75f0a49`):** transcript SEARCH hits mirror on the words tape (amber bold /
> taller amber marks at wide zoom); BOTH silence dialogs live-preview candidates on slider release
> ("N gaps â€” Xs would be trimmed", generation-guarded); visualizer long-press â†’ object menu
> (Customize/Delete) replacing the confirm-less instant delete.
> **(5) SPEC SWEEP (`034c7c2`, `4b6533e`):** narrative-reorder = COMPLETE (added the last TODO,
> Decision-5 boundaryâ†’silence snapping in apply_narrative_proposal); b-roll spec audit = COMPLETE
> (header was stale, all 4 phases landed); LANE_BADGES Â§1 built (`drawKindBadge` gutter glyphs replace
> name labels; Â§4.5 eye/lockâ†’per-object migration deliberately deferred â€” needs per-object controls
> first; Â§2 previews + Â§3 pinning queued).
> **DEVICE-VERIFY OWED (JoyRaptor, on the just-installed build):** code-editor keyboard; word-level copy
> round-trip; transitions now stretch 50ms..10s and SURVIVE REOPEN; whole captions drawer (tweakâ†’saveâ†’
> ticker chipâ†’export caption with custom style); search hits on tape; gap live preview; viz hold menu;
> lane badges look. **REMAINING BUILD QUEUE (session-sized):** dual-stream recording spec (5 phases,
> never started, recording pipeline); visualizer-studio Phase 4 (live-recording viz, perf-gated);
> LANE_BADGES Â§2 item previews + Â§3 pinned thumbs; narrative/b-roll visual-verify with an AI key.**

> **ðŸŽ¬ 2026-07-16 evening â€” FABLE(5): AI-GENERATED SLIDES LANDED (spec + addendum), capture pipeline
> DEVICE-PROVEN behind a locked keyguard. 3 commits (`ee75ade`, `b58d92c`, `642fb06`) + docs.**
> **State discovered at session start:** the whole June `slides/` package (Phases 0â€“3: capture/encode/
> cache/contract/generate_slide/live-scrub-preview, schema v5 `generatedSource`) existed but **nothing
> ever invoked the render pipeline** â€” no caller of SlideCaptureEngine/SlideEncoder anywhere, so a slide
> clip's content-addressed MP4 never materialized and export/playback had no file.
> **(1) `ee75ade` render pre-pass:** new `slides/SlideRenderer` = the spec's ensureGeneratedSlidesRendered,
> but living in the EDITOR process, not ExportManager â€” the `:export` process can't host the WebView
> capture (WebView single-process data-dir rule + in-process latch). Runs at export kickoff
> (`startOutOfProcessExport` â†’ render off-main â†’ `doStartOutOfProcessExport`) and opportunistically on
> project load (`checkMissingMedia` tail) so play-through/thumbnails work pre-export. Generated slides
> exempt from the missing-media relink gate (their MP4 is regenerable cache, not source).
> **(2) `b58d92c` addendum (JoyRaptor 2026-07-16):** Add-asset â†’ "AI slide (animated)" â†’ SlideImportBottomSheet:
> Copy slide prompt (`SlideContract.buildExternalPrompt`, embeds + asks the model to echo a
> `faditor-slide-contract v1` marker; `CONTRACT_VERSION`), Paste slide HTML, Import .html. Import
> validates against the same contract, parses authored duration from `Faditor.register(...)`, emits the
> SAME ADD_GENERATED_SLIDE EditScript as the in-app tool, records undo, background-renders. Newer-than-app
> contract markers are rejected with an update hint. All strings hardcoded + TODO(strings).
> **(3) `642fb06` â€” first-ever device run found the June pipeline captured BLANK WHITE frames** (Chromium
> pauses rendering while the host activity is stopped; the sandbox sat behind a PIN Bouncer): fixed with
> setShowWhenLocked/turnScreenOn + webView.onResume/resumeTimers + `postVisualStateCallback` per-frame
> sync (raced 250ms fallback) + scrollbars off. Also: **this ffmpeg-kit build has NO libopenh264** â€” the
> mpeg4 fallback is the effective slide encoder (fine; Media3 re-encodes the intermediate). Proof, locked:
> 1080Ã—1920@30 â†’ 87 frames â†’ 472KB MP4, ffprobe + visual mid-frame all correct; adb entry now takes
> `--es encode_mp4` (completes Phase 0's real done-when).
> **OWED (sandbox was PIN-locked all session â€” UI half queued):** S1â€“S6 in DEVICE_VERIFY_QUEUE's new top
> block (on-load render, scrub, play-through, trim+transition, export-with-slide, addendum UI). Ready-made
> asset: `cebc19e0` "P0 control2 plain" has an unrendered fallback slide "Chapter One" at clip index 1.
> **Phase 4 (overlay/transparent slides) not started** â€” spec Section 8 deferrals (reorder, b-roll)
> untouched per instructions. Spec status block updated in feature-ai-generated-slides-spec.md.

> **ðŸ§­ 2026-07-12 â€” OPUS 4.8 truth-sweep + land-the-remainder session (sandbox UNPLUGGED all session â†’
> compile-verify only; every device check is queued, not run). 11 commits.**
> **(1) ROADMAP TRUTH-SWEEP (`5dd4dc6`):** the road_map top block was ~6 days stale (HEAD was far ahead).
> Added a 2026-07-12 block marking the whole avatar loop (A1â€“A6 + bake + point-at-video + A5), sprites
> (S1â€“S7 + FF-A/B), AV1â€“AV5, T1 filmstrip, clip-audio drawer, G1â€“G9, and the export fixes COMPLETE, and
> restated the real remainder as ship-blockers vs owed-verifies vs additive-backlog.
> **(2) LANDED THE CAPPED IN-FLIGHT TREE** (a subagent confirmed 4 complete, compile+package-verified
> features): `8c306e5` G5(b) piggyback-looks (attached viz fades with host opacity on export â€” **A/B
> proof owed**), `8332370` grade presets (opencode), `b36a36f` visualizer SAF import/export in the real
> drawer + one-tap Low-bandwidth (720p+Low) export chip. Tree cleaned.
> **(3) AV4 WIRE-UP (`cbf3dd5`, Fable subagent):** the built-but-unwired tape-waveform settings sheet is
> now reachable (editor Settings â†’ "Waveform visualizer"), persists via TapeWaveformStyle prefs, and a
> one-time eager/lazy chooser fires only from the two real user-initiated audio-add sites (extract /
> import â€” NOT project load). Eager kick wired. Device-verify owed.
> **(4) `.m4a` COMPLETE-COPY (`da96248`):** audio-only exports now say "Your audioâ€¦" not "Your videoâ€¦"
> (ExportService threads audioOnly â†’ notification + broadcast EXTRA_AUDIO_ONLY; activity picks the new
> string at both UI sites).
> **(5) TWO DURABLE DOCS:** `DEVICE_VERIFY_QUEUE_20260712.md` (every owed on-device check in one ordered
> turnkey list, solo-doable vs needs-JoyRaptor â€” item A1 = the `148c155` export re-verify, `aeb0517e` is AT
> the repro) and `DEPOLITICIZE_INVENTORY_20260712.md` (Â§4 audit: NO advocacy text remains, footers already
> neutralized; what's left = a small flag-accent+6-easter-egg sweep gated on ONE design choice, plus a
> large FadSec-brand/forensics reskin gated on 4 JoyRaptor-decisions â€” NOT a blind mass-edit).
> **NEXT:** plug the sandbox in â†’ clear the solo half of the device-verify queue (export re-verify, the
> G5(b) A/B proof, F1â€“F4 + AV4 hand-tests). JoyRaptor decisions: the 4 de-politicize scope calls; bookmarks/
> time-chip fold-in-vs-drop. Remaining solo build: clip-audio drawer â†’ overlay/PiP, H.264-baseline
> encoder-profile hook, TODO(strings) polish. Big never-started features are post-v1 (see roadmap Â§ðŸŒ±).

> **ðŸ—„ï¸ 2026-07-11 midday â€” FABLE(model=fable): CLIP-AUDIO DRAWER v2 SHIPPED + DEVICE-PROVEN
> (`5179647`, on the REAL phone SM-N986U â€” it replaced the sandbox on USB and is authorized).**
> JoyRaptor's design, full live-follow in v1: **double-tap a master clip â†’ its embedded audio slides
> down as a quad-band-tape shelf below the strip; double-tap closes.** Drawer is PINNED to its
> clip (geometry derives per-frame from segRects â†’ scroll/trim/reorder followed live with zero
> bookkeeping â€” device-proven by trimming 5.2sâ†’3.3s with the drawer open). Cuts cut video+audio
> together by construction. Transcript words RIDE the drawer (slide to its inside bottom; visual
> verify owed on a transcribed clip). Also landed: opencode's filmstrip T1 sweep (`78a6c6b`).
> **Two render bugs found by ground-truthing (pulled the band cache bin + ffprobe'd the source):
> a SILENT audio track normalized to its own zero peak â†’ full-height slab (fixed: <1e-5 RMS â†’
> flat baseline) and peak-sparks spammed flat plateaus (fixed: prominence required).** Real
> speech now renders syllable-lobed profiles exactly like the HTML prototype.
> **OWED:** real-finger 320ms double-tap feel (adb can't inject it â€” routing logcat-proven via a
> temp-widened window, since restored); transcript-relocation visual; split-with-drawer-open;
> AV4 settings-sheet wire-up (sheet exists, unwired) + first-import eager/lazy popup; extend the
> drawer to overlay/PiP videos (same DrawerState, layer-hop animation). Two throwaway projects
> left on the real phone (dino 5s + greater_phase2) â€” safe to delete. **WATCHER LORE: it was DOWN
> overnight (died with the sandbox unplug) â€” every poll of build.log MUST check mtime freshness
> vs wall-clock; a stale "BUILD SUCCESSFUL" tail reads exactly like a green build.**

> **âœ… 2026-07-11 â€” FABLE(5) verify-and-land session (phone back online, SM-N960U sandbox
> bdd51919). Landed the pending in-flight work as 5 commits and cleared the ENTIRE device-verify
> backlog â€” all PASS. Sandbox restored pristine (md5 `e81a6df8â€¦`), DEVICE token released.**
> **COMMITS THIS SESSION:**
> â€¢ `313e7fa` **export transitionâ‰¥clip muxer-stall FIX** (the filed engine bug). A transition as
>   long as/longer than the clip it straddles trims that clip's body to a sub-frame sliver â†’ Media3
>   emits NO output sample â†’ muxer watchdog aborts ("no output sample in 10000ms"). Fix: (1) new
>   `effectiveTransitionMs()` seam-clamps every head/tail overlap to what the straddled clip has;
>   (2) `mainBodyDegenerate` guard (<40ms timeline, transition-touched clips only) skips the micro
>   EditedMediaItem. Ordinary timelines untouched. **DEVICE VERIFY STILL OWED** â€” repro: AudioExportVerify
>   (`aeb0517e`) seam-2 transition â†’ 600ms (clip 3 is 427ms), export BOTH paths, expect no stall.
> â€¢ `fdad81f` **AV3 audio-row expand/collapse layout â€” DEVICE-VERIFIED.** 1st audio track expanded
>   (76dp tall quad-band tape), rest thin collapsed bars; tap a thin bar â†’ expands via the caret path
>   (undo step recorded, verified). Caption-enabled audio clips get a CC ribbon inside the expanded row.
> â€¢ `e7a863c` **AV4-groundwork (UNWIRED):** `TapeWaveformStyle` SharedPreferences round-trip
>   (`wave_viz_*` keys) + `analyzeEager` flag + `WaveformVisualizerSettingsSheet` (complete, referenced
>   by nothing â€” wiring under toolbar Settings + a first-import eager/lazy popup is the remaining AV4).
> â€¢ `acaeace` **AV5 plan doc** (`tasks/PLAN_AV5_PERF_AND_CLEANUP.md`): tile-cache the per-frame tape
>   draw (currently `tapeRenderer.draw` runs for every visible audio item every onDraw) + dead-code removal.
> â€¢ (`4ff1707` from the prior block â€” the two AUDIO#3 findings â€” was already committed + device-verified.)
> **DEVICE-VERIFY BATCH â€” ALL PASS on bdd51919:**
> â€¢ **W2 HD zoom (`0433439`):** zoomed-in audio bars pull the high-density span-limited extraction and
>   resolve letter/onset-level detail; zoomed-out unchanged; smooth scroll. PASS.
> â€¢ **Smoke (i)** extract-from-video â†’ the Audio tool extracted a new clip that renders its waveform on a
>   new audio row (persisted `waveform` int[] present). PASS. **(k)** vertical drag in the audio band
>   scrolls the band, doesn't mis-scrub. PASS. **(l)** hold-drag a floating item over the audio band shows
>   the cross-band drop affordance (drops onto a new visual lane above the band, band-correct). PASS.
> â€¢ **G5a attach/detach:** open a VIZ item's Rolodex â†’ link icon attaches ("Attached to clip 1 â€” rides its
>   trims and moves", icon turns green, `attachedClipId` persists in project.json); tap again detaches
>   ("Visualizer detached â€” window frozen where it is", icon â†’ link_off). Round-trips clean. PASS. (Ride
>   math is `Timeline.resyncAttachedVisualizers`, runs on every syncTimelineOverlays â€” sound; a clean
>   visual ride demo needs a DOWNSTREAM host since clip-0's start is pinned at 0.)
> â€¢ **GL "More effects" / transition cards â€” KEY DIAGNOSTIC RESOLVED.** The 5 transition preview cards
>   (Crossfade/Fade Black/Fade White/Wipe/Radial) **DO render LIVE GL correctly on this GPU** â€” captured
>   mid-animation they each show their DISTINCT effect (Fade Blackâ†’black, Fade Whiteâ†’white, Wipe's split
>   line moving between two demo images, Crossfade blending). So GL transition shaders compile+run fine in
>   the live GLSurfaceView on the SM-N960U. **â‡’ the `2593bdb` baker's headless shader-compile failure is
>   CONTEXT-LEVEL (pbuffer EGLConfig/precision vs GLSurfaceView default), NOT a GPU-wide problem** â€” the
>   leading suspect in the 2593bdb javadoc stands; fix = match the pbuffer context config to the working
>   live one. Also: **"âŒ„ More effects" is a non-clickable hint TextView** (`clickable="false"`), which is
>   why earlier sessions "couldn't expand" it â€” there is nothing to expand; the card strip is a plain
>   HorizontalScrollView.
> **STILL OWED (needs JoyRaptor):** the `313e7fa` export fix device verify (above); P0/P1 on real phone
> REAL_SERIAL (USB-debug toggle); G9 UI (5 answers in PLAN_G9_LINK_ENGINE.md); the .m4a export-complete
> copy string ("Your videoâ€¦"). NEXT build work: AV4 wire-up, then AV5 perf + dead-code.

> **ðŸŒŠ 2026-07-10 late â€” FABLE(5) continuation: findings FIXED (`4ff1707`, device-verified before
> the phone dropped) + W2 HD WAVEFORM ZOOM BUILT (`0433439`, build-green, DEVICE VERIFY OWED â€”
> phone went offline mid-session; adb shows no devices; watcher fails only at installDefaultDebug).**
> **W2 design (as built):** timeline audio bars now pull zoom-TIERED data (200/400 buckets-per-sec at
> â‰¥50/â‰¥150 px-per-sec, else legacy) from the shared `WaveformExtractor` via new
> `waveform/TimelineWaveformCache` â€” span-limited to the clip's source window quantized to 10s (trim
> drags reuse cache), amplitude-only (FFT skipped via new `withSpectrum=false`), extractor cache key
> SUFFIX for non-default density (old visualizer entries untouched, no version bump), in-memory LRU
> 48 entries, failed-source blacklist. Renderer: `HdWaveformProvider` hook + `drawHdAudioWaveform`
> (peak-per-bar max-of-range, VISIBLE-span-only iteration via canvas clip bounds, 1dp bars).
> `AudioClip.waveform` int[800] persisted field UNTOUCHED â€” loads/saves/generates as before, serves
> as instant placeholder + below-tier renderer. **FIRST ERRAND WHEN THE PHONE IS BACK: (1) the
> watcher will auto-install on its next build â€” then zoom into a long audio clip â†’ letter-level
> onsets resolve, zoomed-out unchanged, scroll smooth (extraction is once-per-tier, swap-in via
> invalidate); (2) the STILL-OWED smoke: i (extract-from-video) / k (audio-band vertical drag) /
> l (cross-band insertion line), G5a attach/detach, GL "More effects" row; (3) P0/P1 on REAL_SERIAL
> (JoyRaptor's USB toggle).** Sandbox bdd51919 was left PRISTINE by the findings-fix session (verify-then
> -restore, per its LANES note). G9 still gated on JoyRaptor's 5 answers (PLAN_G9_LINK_ENGINE.md).

> **ðŸŽšï¸ 2026-07-10 â€” FABLE(5): AUDIO-ONLY EXPORT SHIPPED + DEVICE-PROVEN (`bafe177`); AUDIO #3
> per-op smoke MOSTLY GREEN with 2 real findings; sandbox restored pristine.**
> **(1) AUDIO-ONLY EXPORT (`bafe177`) â€” the full user path works on SM-N960U:** dialog checkbox
> ("Export audio only (.m4a)", greys Resolution/Quality) â†’ EXTRA_AUDIO_ONLY through the same OOP
> ExportService/snapshot/notification infra â†’ `exportAudioOnly` (engine `d32cb02`). Pulled the .m4a:
> single AAC 48k stereo track, duration 13.739s vs 13.692s timeline; ffmpeg RMS proves music mixed at
> offsets over image silence (âˆ’31dB vs âˆ’65dB floor), 4-pt fade envelope applied (fade-in +25dB ramp,
> fade-out âˆ’11dB), muted/image spans silent, master-clip audio present, 2x-speed clip contributes its
> compressed duration. Two engine fixes rode along (audio path + SAF mime `audio/mp4` for .m4a).
> Test project `aeb0517e-1111â€¦` ("AudioExportVerify") left on the sandbox phone as a repro asset.
> **ðŸ”´ PRE-EXISTING ENGINE BUG (filed as background-task chip): a transition whose durationMs â‰¥ the
> FOLLOWING clip's duration stalls BOTH export paths** (watchdog "no output sample in 10000ms" â†’
> "Muxer error"). Repro: set the AudioExportVerify project's seam-2 transition back to 600ms (clip 3
> is 427ms). The audio path now survives the zero-length-item flavor (skip guard in `bafe177`), but
> the video path stalls even with that clip skipped â€” root cause is deeper in the transition-item
> construction. Fix in buildComposition + clamp transition duration at authoring time.
> **(2) AUDIO #3 PER-OP SMOKE (sandbox `bdd51919`, current build) â€” PASS: (a) rows render below
> master aqua+waveform+labels, no legacy dup bar; (b) tap-select ring + delete badge; (c) drag-trim
> BOTH edges (undo steps recorded, values persist); (d) drag-MOVE via hold-drag (`input draganddrop`,
> offset 10406â†’8806; plain swipe on body = scroll, as designed); (e) volume TOP-DRAWER opens off the
> audio selection showing ITS volume, keyframe mode + slider drag persists a volumeKeyframe; (f)
> per-clip mute round-trips; (g) split-at-playhead â†’ clean source-continuous halves; (j) transcript
> panel switches to the tapped audio clip (untranscribed clip â†’ speech-model chooser). NOT RUN (adb
> budget): (i) extract-from-video (existing extractions DO render waveforms), (k) audio-band vertical
> drag, (l) cross-band insertion line â€” hand-test with JoyRaptor.**
> **ðŸŸ¡ FINDING 1 â€” audio item's `layerId` is DROPPED somewhere in the undo/move/split path** (was
> `94e713bf`, both split halves = null) â†’ items visually RE-LANED from audio row 3 to row 1 after a
> later refresh. Repro: trim â†’ undo Ã—2 â†’ hold-drag move â†’ split, then delete/undo a master clip and
> watch the audio band. Suspect: AudioClip copy/undo-snapshot round-trip losing layerId.
> **ðŸŸ¡ FINDING 2 â€” with an AUDIO item selected, the toolbar Delete tool deleted the selected MASTER
> clip** ("Clip removed â€” gap left in place"), not the audio item (delete-badge tap on the audio item
> itself did nothing via adb, possibly hit-zone). One of the ~15 legacy-anchored ops NOT deriving the
> audio selection. (Undone on the spot; sandbox then restored from the pristine 12:41 pull â€”
> project.json byte-identical, undo_history.json cleared since it contained my smoke states.)
> **NOT REACHED: G5a attach/detach verify, GL "More effects" row check, P0/P1 real-phone re-verify
> (REAL_SERIAL not connected â€” needs JoyRaptor's USB toggle), W2 HD-waveform zoom, G9 UI (needs JoyRaptor's 5
> answers in PLAN_G9_LINK_ENGINE.md). Also: export-complete UI copy says "Your video has been
> savedâ€¦" for .m4a exports â€” one-string polish, strings.xml was opencode's lane this session.**

> **ðŸ§µ 2026-07-09 crunch â€” OPUS (Fable thread, multi-agent: Opus main + Opus & Sonnet subagents).
> THREE COMMITS, tree green, device-verified where it counts.**
> **(1) `9b37f99` audio-band clipping fix â€” DEVICE-VERIFIED.** Found in the audio-consolidation smoke:
> with several floating layer rows + master + the new audio band, the view's desired height exceeds
> what the parent grants, `resolveSize()` clamps it, and the bottom-most band (AUDIO) silently ran
> off-screen (2nd audio row half-clipped). Fix: the floating band is the only internally-scrolling
> flexible band, so it now absorbs the measure deficit â€” `onMeasure` computes desired height with no
> squeeze, and if granted less, hands the shortfall to `LayerRowRenderer.setViewportSqueezePx()`; the
> floating viewport cap shrinks by that (never below one 40dp band), pulling master + audio up. Proof:
> both audio rows fully visible even with the transitions drawer compressing the timeline.
> **AUDIO CONSOLIDATION (`f31f16c`) also now DEVICE-SMOKED** here: two headered Audio rows render below
> master in their own band, NO legacy duplicate bar, aqua waveforms â€” the FEEDBACK-#1 double-bar bug is
> gone. (Full per-op smoke â€” trim/move/volume-kf/mute/split/delete â€” still worth a pass, but the render
> + no-dup half is confirmed.)
> **(2) `2593bdb` GL-transition card baker (Opus subagent) â€” P0 CRASH FIXED, feature crash-safe but
> INERT.** The subagent's headless sprite-strip baker NATIVE-ABORTED the whole app on editor open:
> `GLES20.glGetShaderInfoLog(int)` on Adreno/Samsung returns invalid Modified-UTF-8 bytes, and the
> native `NewStringUTF` inside it aborts under CheckJNI â€” UNCATCHABLE by the Java try/catch the baker
> wrapped bake() in, so a routine shader-compile failure crashed everything. Removed all
> `glGet{Shader,Program}InfoLog(int)` calls (log our own source instead). DEVICE-VERIFIED: editor opens
> clean, baker degrades to proxy, no crash. **KNOWN LIMITATION (in the class javadoc):** the fragment
> shaders currently FAIL to compile in the headless pbuffer context on SM-N960U â†’ every card keeps its
> proxy (no visible change from shipped behavior, no regression). Kept not reverted since the wrapped
> source is byte-identical to the working live `GlTransitionPreviewView`, so the fix is likely small +
> context-level (leading suspect: pbuffer EGLConfig/precision vs GLSurfaceView default). **Next person:
> first confirm on device whether the LIVE GL cards (transitions "More effects" row â€” I could not get it
> to expand via tap or down-fling, itself worth a look) render the real effect or also fall back â€” that
> says whether shaders compile at all on this GPU or only the baker's context is at fault. Do NOT
> reintroduce the InfoLog(int) calls.**
> **(3) `bca07d3` G9 link-engine design doc (Sonnet subagent).** `tasks/PLAN_G9_LINK_ENGINE.md`, 285
> lines, lean-A: one shared link engine, G5 attach re-expressed as a preset over it. Grounded in the
> shipped G5 code; slices G9aâ€“G9f (G9a/b are UI-less plumbing, can start before G8; G9d needs G8
> multi-select); 5 open questions for JoyRaptor (trim propagation, a new `stratified` field on
> WaveformOverlayInstance, overlapping groups, toolbar tie-break, caption-attach scope).
> **STILL OWED (device, JoyRaptor's unlock needed if it re-locks): G5a attach/detach verify** (attach a viz
> via the Rolodex link icon, trim its host clip, confirm the VIZ row rides along + project.json shows
> attachedClipId); the audio per-op smoke; and the real-phone REAL_SERIAL P0/P1 re-verify.

> **ðŸŽ›ï¸ 2026-07-07 late â€” FABLE(5): AUDIO ROW CONSOLIDATION BUILT (`f31f16c`, build-green + installed
> on SM-N960U; DEVICE SMOKE BLOCKED â€” phone locked with a secure Bouncer mid-session, needs JoyRaptor's
> unlock).** Implemented exactly per the scoping block below: **(1) two-band renderer** â€”
> `LayerRowRenderer.layout()` now takes an `audioTopPx` anchor and lays audio rows in their OWN
> unscrolled band BELOW master (Slice-E order kept); band-aware hit-tests via `bandLocalY()`
> (returns NaN out-of-band + positive-form comparisons so touches can't alias across bands);
> cross-band insertion line + time-lock guides draw per band; marquee = floating band only
> (documented follow-up). **(2) selection derive** â€” `getSelectedAudioIndex()` maps
> `LayerGestureController.getSelectedItemId()` over `audioClips` when the new rows are fed, so all
> legacy-anchored audio ops work unchanged; transcript-panel switch moved to
> `onItemSelectionChanged`. **(3) legacy path retired** â€” `drawAudioTrack` + audio hit-tests gate
> off when `audioLayerTracks` non-empty; `syncTimelineOverlays` now feeds `tl.getAudioTracks()`;
> onMeasure/audioBandBotPx reserve the renderer band height. **(4) envelope ported** â€” blue
> volume rubber-band + keyframe dots drawn on renderer audio item bodies (1:1 legacy port).
> **SMOKE CHECKLIST (first unlocked session, sandbox project bdd51919 has 3 audio clips):**
> (a) audio rows render BELOW master, aqua, with waveform + labels, NO legacy duplicate bar;
> (b) tap audio item â†’ selection ring + trim caps; (c) drag-trim both edges (controller path);
> (d) drag-move (offsetMs); (e) volume sheet opens off the selection + keyframe drag draws the
> blue envelope on the row; (f) per-clip mute; (g) split-at-playhead; (h) delete via badge â†’
> confirmation; (i) extract-from-video â†’ waveform renders on the new row; (j) transcript panel
> switches when tapping an audio clip; (k) vertical drag in the audio band doesn't scroll weirdly;
> (l) cross-band drag of a text item over the audio band shows the insertion line at the right
> place. NOTE: Opus's earlier audio-track session left NO commits/stash â€” its work is gone;
> nothing to recover (verified reflog + fsck).

> **ðŸŽ§ 2026-07-07 ~20:45 â€” FABLE(5): AUDIO ROW CONSOLIDATION â€” SCOPED, NOT BUILT (deliberate; findings
> below cut the next session's discovery to zero).** Verified live-code facts (not doc claims):
> **(a) `LayerGestureController` ALREADY fully supports audio items** â€” `AUDIO_MIN_TRIM_GAP_MS`,
> audio-only trim state (`dragStartTrimInMs/OutMs`), move via `AudioClip.setOffsetMs`, doc'd to mirror
> `doAudioTrimDrag` semantics. **(b) The activity's gesture `Callback` ALREADY has complete audio
> branches** (`FaditorEditorActivity` ~10162-10188: move-undo, `AudioTrimAction` undo, delete
> confirmation via `deleteAudioClipWithConfirmation`, `prepareAudioPlayer()` resync). **(c)
> `LayerRowRenderer` ALREADY draws audio items** (waveform `drawAudioWaveform` :971, aqua color, mute
> icon, label, trim caps) â€” missing ONLY the volume-envelope rubber-band + keyframe dots (port from
> `EditorTimelineView.drawAudioTrack` :3344-3374). **(d) The old `onAudioClipSelected` side effect is
> tiny** â€” transcript-panel switch only (:1580-1591). **THE ACTUAL REMAINDER (why this needs its own
> session): (1) BAND PLACEMENT** â€” `LayerRowRenderer.layout()` stacks `layers` then `audioTracks` into
> ONE band at a single `topPx` (:257-271), so un-suppressing audio in `syncTimelineOverlays` (:9288,
> flip `emptyList()` â†’ `tl.getAudioTracks()`) puts audio rows ABOVE master â€” violating JoyRaptor's approved
> Slice-E order (audio BELOW master). Needs a second band: either a 2nd renderer instance laid out at
> `audioBandTopPx()` (cleanest; matches FEEDBACK #3's "dual-scroll band below master") + touch routing
> + gesture-controller arbitration (controller binds ONE renderer), or renderer-native two-band
> support. **(2) selection derive** â€” reimplement `EditorTimelineView.getSelectedAudioIndex()` to map
> the unified `LayerGestureController.getSelectedItemId()` â†’ index in `audioClips` (TimedItem id ==
> AudioClip id, see `TimedItem.ofAudioClip`); then ALL ~15 activity ops (volume sheet/mute/split/
> captions/delete/trim-to-selection, :3709-4267, :12113-12205) keep working UNCHANGED. **(3) retire the
> old path** â€” gate `drawAudioTrack` (:1742), the audio hit-tests (:5086/:5203/:5422/:5696), and
> collapse `audioBandTopPx()/audioTrackTotalHeightPx()` to 0, Slice-C style (keep the `setAudioClips`
> FEED â€” the derive-map needs the list). **(4) regression list** â€” select, trim both edges, move,
> volume sheet + keyframes, per-clip mute, split-at-playhead, delete, extract-audio waveform render,
> transcript-panel switch. Estimated one focused session with JoyRaptor available for trim-feel.

> **ðŸŽ¯ 2026-07-07 ~18:00-19:20 â€” FABLE(5) FINAL-DAY SESSION: ðŸ”´ P0 image-clip gapless gap FIXED +
> DEVICE-PROVEN (`62b227f`), a NEW pre-existing engine freeze found/bisected/guarded (`f855e51`), the
> gradle build blocker for agent shells root-caused + memoried, and opencode round-3 chat-UI work
> reviewed/committed (`cd95162` + `62012a4` + docs `6340e83`).**
> **(1) P0 FIX:** engine serves image clips as native media3 image windows (`setImageDurationMs`, same
> pipeline as export; ImageRenderer/PlayerView image output confirmed present in the patched media3 1.8);
> one image window spans the clip's whole visual duration (loop-extending a still = a longer still).
> Activity: legacy image-timer gated on `!isGapless()` (play button / tick), `onGaplessSeam` swaps the
> proven Glide overlay in/out (overlay = display, engine = clock), selectSegment/drag-finish sync the
> engine window + window-local seek, scrub-into-image pauses like the video path. **DEVICE PROOF
> (SM-N960U, isolated project videoâ†’9.86s-imageâ†’2 videos):** `Gapless engine ACTIVE for 4 clips` (this
> shape was ineligible before), ONE play tap crossed videoâ†’imageâ†’videoâ†’video with warm AUTO_TRANSITION
> seams (image window ran exactly 9864ms), and frame-hashing the 30fps screenrecording found no
> identical run >3 frames vs the bug's 22-72. NOT yet re-verified on JoyRaptor's real phone/project â€” that's
> the next session's first errand (install current build on REAL_SERIAL, reopen `27221664â€¦`, play across
> the freeze-frame boundary; her project must also dodge the new P1 below, i.e. check it for short
> speedâ‰ 1 clips first).
> **(2) NEW ðŸ”´ P1 (pre-existing, exposed by verify):** short speedâ‰ 1 clipped windows wedge the gapless
> clock entirely â€” full detail + bisect matrix + interim eligibility guard in road_map.md's top block.
> Guard verified on device: the freeze project now takes legacy and plays; long-2x and 1x controls keep
> gapless and play.
> **(3) BUILD LORE (critical for any agent shell):** `gradlew` fails with `Unable to establish loopback
> connection` / `Invalid argument: connect` because JDK17 `Pipe.open()` uses AF_UNIX sockets in
> `java.io.tmpdir`, and AF_UNIX connect() gets WSAEINVAL for ANY socket file under
> `C:\Users\JoyRaptor\AppData\Local\Temp` (subdirs incl. the agent scratchpad too; other dirs on the
> same volume work â€” underlying cause unknown, maybe AV/filter driver; flag to JoyRaptor if her own builds
> break). WORKAROUND (memoried): `$env:TEMP='C:\Users\JoyRaptor\gtmp'; $env:TMP=$env:TEMP` before any
> gradlew call. Also: failed clients leave busy zombie daemons (kill stray `java.exe`), and `cmd | tail`
> masks gradle's exit code â€” grep for BUILD SUCCESSFUL.
> **(4) Sandbox-phone housekeeping:** 6 disposable test projects were pushed to the SM-N960U project
> browser (`P0 image gapless verify`, `P0 control no image`, `P0 control2 plain`, `P0 img isolated`,
> `bisect A/B/Câ€¦`) â€” keep `bisect A/B/C` + `P0 img isolated` as repro assets (road_map references them),
> the rest are safe to delete from the browser. Their transcript `words` arrays are corrupted (PS JSON
> round-trip) â€” fine for playback repro, unusable for transcript testing.

> **ðŸš¨ 2026-07-07 ~16:15-17:00 â€” SONNET: first-ever main-phone real-project verify session, surfaced a P0
> gapless-engine gap. Full detail + fix guidance filed in `road_map.md`'s new ðŸ”´ P0 block (top of file) â€”
> this entry is the session narrative/evidence trail.** Device `REAL_SERIAL` (real phone, SM_N986U) had
> gone `unauthorized` in adb; JoyRaptor cycled the USB-debugging toggle on-device to force a fresh authorization
> prompt (killing/restarting the adb server alone did NOT trigger it â€” needs the toggle cycle). Installed
> the current debug build over the existing `4.0.0-beta9` (same package, `com.fadcam.beta` â€” confirmed via
> `applicationIdSuffix` in `build.gradle.kts`), pulled an off-device backup of the target real project
> (`27221664-21e9-4e9d-8fd7-e8884bb0eb55`, schemaVersion 7 on disk, last touched 2026-06-27 â€” i.e. never
> opened by any build with the Layers-model schema work) before doing anything, per JoyRaptor's explicit go-ahead
> to test with it (already-finished project, on-device `.bak` also exists). JoyRaptor opened it herself in-app:
> **no crash**, migration completed clean (new Layers-model fields present after re-save; the persisted
> `schemaVersion` number itself did not bump to 8+, likely cosmetic â€” worth a doc note, not a bug by itself).
> JoyRaptor then reported playback hiccups at clip changes/transitions and asked for a screen-recording pass
> while she pressed play. **Diagnostic method:** `adb shell screenrecord` capturing device screen while JoyRaptor
> played from the timeline start; pulled the mp4 via `adb exec-out cat` (plain `adb pull` mysteriously
> failed to create local files all session â€” root cause not chased, exec-out streaming worked every time,
> future sessions should just default to it); used `ffmpeg` to extract 30fps PNG frames across the suspect
> window, cropped to the content region, and `md5sum`'d each frame â€” a run of 22-72 consecutive identical
> hashes (~0.7-2.4s) landed right at a clip boundary a colorful title-card clip cutting to a grayscale
> scene, at a recording-relative timestamp that lines up with JoyRaptor's independently-reported "paused at three
> seconds after play" once corrected for the gap between recording-start and play-tap. JoyRaptor confirmed this
> wasn't an infinite hang â€” she had to manually re-tap play (multiple times) to get past the stall, which is
> the exact cold-re-prepare-per-seam signature `DIAG_20260701_transition_preview.md` originally diagnosed,
> now shown to still be reachable through a specific project shape. **Root cause traced to code:**
> `MasterPlaybackEngine.isEligible()` (line 335) bails the ENTIRE timeline out of the gapless engine if
> *any* clip is `isImageClip()` â€” this project has 15 video clips + one `frame-10.00s.jpg` freeze-frame
> insert (5000ms, `imageClip: true`), which is enough to disqualify all 14 other seams too, not just the one
> next to the still image. **Unresolved side-note (not blocking, flagged separately in road_map.md):**
> grepped the project JSON (both the pre-session backup and a fresh pull afterward) for any persisted
> `Transition` object/type string (`WIPE_LEFT` etc., per `model/Transition.java`'s enum) and found none, even
> though JoyRaptor visually confirmed a wipe renders correctly on scrub at that same boundary â€” either it's a
> real transition the serializer isn't persisting, or the freeze-frame's own content (lifted from the
> surrounding footage) just looks wipe-like against the following scene. Didn't chase further this session;
> next person touching this area should check `ProjectStorage`'s transition serialize/restore path
> (`ProjectStorage.java:1713-1735` write, `:2203-2207` read) against what the UI actually persists when a
> transition is placed. **Hand-off ask:** JoyRaptor wants this documented "wherever it's supposed to be" â€” landed
> as a ðŸ”´ P0 in `road_map.md` (top of file, above the strict-order active queue) since it blocks ordinary
> project shapes, not an edge case. Fix is Opus-tier per `PLAN_LAYERS_V2.md`'s own sizing table (same
> model/file as M-COMP-0 itself).
>
> **ðŸ”ï¸ 2026-07-07 ~13:25â€“14:05 â€” FABLE(5) "finish the frontier" swoop #1: ALL SIX remaining ðŸŸ¡-tier
> items LANDED build-green + committed; session was INTERRUPTED mid-device-verify-batch (~14:05), so
> device coverage of these is UNKNOWN â€” the follow-on session re-smokes them.** The landings:
> **(0ce35ea) export edit-safety + quality/resolution wired into the encoder** â€” `ExportService` now
> receives a SNAPSHOT of the project (closes the Opus-flagged by-reference mutation hazard) and
> `ExportManager` finally reads `ExportSettings.Resolution`/`.Quality` (encoder bitrate/size caps â€”
> the previously-dead enums are live). **(a533aa0) LUT filters + intensity slider** â€” `LutManager`
> pre-bakes intensity into the LUT bitmap (identity-lerp), `FilterBottomSheet` slider,
> `EffectStack` + `ProjectStorage` persist it. **(a4baee5) G6.3/G6.4 PiP promotion +
> minimize-during-export** â€” new `player/PreviewPipController` (fullscreen-extreme â†’ draggable PiP
> preview; small-preview/landscape promotion), export dialog got resolution/quality pickers, and
> export survives backgrounding (foreground-service glue in `ExportService`). **(f4eca41) B-roll
> Phase 3 vision tagging** â€” `AIToolExecutor` tags the asset bucket via vision API, `BRollBucket`
> stores tags. **(dbf1628) G8 marquee multi-select** â€” three-state toggle, live drag box in
> `EditorTimelineView` + `LayerRowRenderer`, batch delete; additive selection mode, existing gesture
> branches untouched. **(ae07b21) visualizer studio Phase 3 remainder** â€” per-instance bar
> width/gap overrides persisted on `WaveformOverlayInstance`. **VERIFY STATE: watcher green 14:05;
> device verify batch started but interrupted â€” treat ALL SIX as build-green-only until the smoke
> pass below reports.** Stray tree state cleaned (trailing-newline diff reverted).

> **ðŸŽ“ 2026-07-07 â€” OPUS "easy frontier" session: G7 COACH-MARKS + TRANSITIONS PULL-DOWN landed,
> JOYRAPTOR-VERIFIED ON DEVICE ("both work"); both export items DEFERRED (honest re-grade).** Two of four
> "easy"-graded frontier items were genuinely safe/self-contained and shipped build-green + device-proven:
> **G7 coach-marks (`954a63e`)** â€” first-ever timeline-item selection surfaces a one-time,
> non-blocking banner teaching the invisible per-item gestures (double-tap=edit Â· hold=menu Â·
> drag=move); persisted in `faditor_ui`/`coachmark_item_gestures_shown`, auto-dismisses, hooks the
> existing `onItemSelectionChanged`, fully try-wrapped so it can never break selection. **Transitions
> pull-down-for-more-rows (`00d9e41`)** â€” studio-drawers Â§C's last TODO: the GL-effects second row now
> starts COLLAPSED behind a discoverable "âŒ„ More effects" bar (tap or down-fling reveals, up-fling/
> re-tap collapses); additive to the transition panel, basics + insert paths untouched. **DEFERRED
> (honest re-grade â€” both turned out entangled with the standing-locked, correctness-critical
> `ExportManager`, not safe-easy):** (1) **export QUALITY setting** â€” the `ExportSettings.Resolution`/
> `.Quality` enums exist + persist + are AI-settable, but `ExportManager` reads NEITHER, so a picker
> would set a dead value; making it real = encoder surgery on the crown-jewel export path. (2)
> **export EDIT-SAFETY** â€” the project is handed to `ExportService` by REFERENCE (`setPendingProject`),
> and the export overlay's Back button returns to a live editor, so edits during export mutate the
> exporter's Timeline; the clean fix needs project snapshotting through the locked serializer. Both
> belong in a dedicated Fable/export session. **HAND-TESTS: DONE â€” JoyRaptor confirmed "both work" on
> device (2026-07-07):** (a) coach-mark banner appears on first timeline-item selection + dismisses +
> stays gone; (b) Transitions drawer "âŒ„ More effects" bar reveals/collapses the GL row. No follow-ups owed.
>
> **âš¡ 2026-07-07 â€” OPENCODE/SONNET: 10-task road_map Â§BACKLOG queue â€” 8 DONE, 1 PARTIAL, 1
> SKIPPED (all detail in Opencode-work.md's bottom PROGRESS LOG entry).** Commits: `ac23f30`
> (AssetScanner MMR probes â†’ 4-thread pool, `scan()` stays synchronous), `2758423`
> (VideoInfoBottomSheet's FFprobe+MMR metadata extraction moved off `onViewCreated` to a
> single-thread executor + cached per-sheet-instance â€” was the one genuine UI-thread-MMR offender
> after surveying ~20 files; everything else already backgrounded or dead code), `aba8ee3`
> (timeline fling `computeScroll()` now skips the seek+redraw when the rounded-px offset is
> unchanged frame-to-frame, still ticks the scroller normally), `f10352a` (Whisper transcription's
> `pcmToFloat` per-30s-chunk `float[]` pooled instead of freshly allocated â€” not in
> VolumeAudioProcessor, checked first), `38c09da` (photo capture's 6x `glReadPixels` now share one
> pooled `IntBuffer` instead of 6 fresh allocations â€” `GLWatermarkRenderer`), `c1563b8` (recording
> I-frame interval 1sâ†’2s in both encoder pipelines, no existing settings surface so stayed a
> constant), `f76bd2e` (`docs/project-schema.md` regenerated for v7-v10, was stuck at v5/v6;
> corrected a stale "unknown fields preserved" claim â€” saves do NOT round-trip genuinely
> unrecognized keys, hand-written serializer not reflective Gson), `e63ba4c` (T1 filmstrip: disk
> LRU cache layer landed â€” PARTIAL, the harder "sequential MediaCodec sweep instead of seek-per-
> thumbnail" accuracy fix is NOT done, see notes), `96cba7f` (pure-deletion removal of the
> confirmed-dead `drawLayers`/`hitTestLayer*`/`Drag.LAYER_*`/`activeLayerIndex`/`selectedLayerKind`
> subsystem in `EditorTimelineView.java` â€” 506 lines removed, independently re-verified the
> dead-code trace before deleting, not just trusted the prior session's punch list; turned out
> larger than that list implied, also removed the onDown/onMove/onUp dispatch branches +
> `doLayerDrag`/`layerSiblingFloor`/`layerSiblingCeil`/`sameLayerLane` + the long-press machinery).
> **W2 (zoomed HD waveform tier) SKIPPED for the 3rd time** â€” investigated fresh (not copy-pasted):
> the timeline's audio-clip waveform bars use a SEPARATE legacy pipeline
> (`FaditorEditorActivity.generateWaveform` â†’ `AudioClip.getWaveform() int[]`, extracted once,
> capped ~60 bins/sec) from the newer `WaveformExtractor`/`WaveformData` (MediaCodec + disk cache,
> span-limited) which is currently wired ONLY for placed visualizer overlays, not timeline audio
> bars. A real W2 needs migrating the timeline bars onto the newer pipeline (retiring the legacy
> one, not forking a third) â€” a schema-adjacent change (`AudioClip.waveform` is a persisted field)
> that needs its own session, not a draw-path tweak. **HAND-TEST OWED (JoyRaptor, no drags needed):**
> open any project in the Faditor editor, do a few normal timeline interactions (tap-select a
> clip, drag-trim a clip edge, tap an audio clip, drag the playhead) â€” confirms task 10's
> dead-code removal didn't regress anything live (build-verified + logcat-clean but NOT
> hand-tested on the actual editor screen this session â€” animating home screen blocked
> `uiautomator dump` mid-session, see Opencode-work.md task 10 entry for the device-lore detail).
>
> **ðŸ§° 2026-07-07 â€” OPENCODE/SONNET: 6-task road_map Â§BACKLOG queue â€” 4 landed, 1 blocked, 1
> investigated-deferred.** All additive/low-risk, none touched a standing-locked file. **DONE:**
> (1) Captions tool-row icon now tints green when caption-style keyframe mode is armed, matching
> the existing Volume/Opacity convention (`7612053`). (2) Removed the dead "trim"/"heal" tool-row
> entries (registry + ids.xml + activity fields + strings, 12 locale files) â€” trim had zero
> handler, heal was fully superseded by the Split tool's contextual heal-mode (`50d78ce`). (3)
> Canvas picker gained a "Customâ€¦" WÃ—H numeric-entry dialog (mirrors the crop toolbar's Custom-
> ratio chip pattern exactly), emitting a `custom_<w>_<h>` preset key through the existing
> callback â€” `CanvasPickerBottomSheet.resolveCanvasDimensions`/`displayLabel` updated, ExportManager
> untouched (`805d69f`). (4) New 9:16 safe-zone preview guide toggle in the Settings sheet (new
> `SafeZoneOverlayView`, dashed amber rect + thirds ticks, only draws near true 9:16, preview-only â€”
> never touches the project model or export) (`cdaf9c3`). **BLOCKED:** (5) low-bandwidth 720p/H.264-
> baseline export preset â€” `ExportManager` has zero encoder-profile hook anywhere (no
> `Codec.EncoderFactory`/`VideoEncoderSettings`) and the export dialog has no resolution/quality
> picker UI at all yet; needs the Fable/Opus lane since it requires editing the locked file. Full
> blocker detail + a possible resolution-only follow-up split in `tasks/Opencode-work.md`.
> **INVESTIGATED, DEFERRED:** (6) the old `drawLayers`/`hitTestLayer*`/`activeLayerIndex`/
> `Drag.LAYER_*`/`selectedLayerKind` path â€” a dedicated subagent trace confirmed ALL 5 symbols are
> genuinely dead (every list they read is permanently empty since Slice C; `LayerGestureController`
> has zero coupling to any of them) with a ready-to-execute line-by-line removal punch list in
> `Opencode-work.md`, but the actual multi-site edit (~30 call sites across the 6200-line, actively-
> G-series-extended `EditorTimelineView.java`) was deliberately left undone this session per the
> task's own "if you have ANY doubt, skip it" conservatism â€” a clean, fast follow-up for the next AI.
> **HAND-TESTS OWED (JoyRaptor, no drags â€” all taps/dialogs, none scripted this session):** (a) long-press
> Captions tool â†’ tool-row icon should turn green; (b) Canvas tool â†’ Customâ€¦ â†’ enter WÃ—H â†’ label
> should read "1080Ã—1920" style, preview reframes, exported file matches (ffprobe); (c) Settings â†’
> enable 9:16 safe-zone guide on a 9:16 project â†’ dashed guide appears, disappears on a 16:9 canvas,
> confirmed absent from the exported file.
>
> **ðŸŽ¯ 2026-07-07 â€” FABLE(5): G3 committed + G4 PREVIEW MANIPULATION HANDLES landed, DEVICE-PROVEN
> (975ade2 + 5b53db4).** G3 (built pre-interruption, recovered): diamond swipe=prev/next key nav,
> long-press=delete key, top â—€â—‡â–¶ ribbon over the preview while a keyframeable property is focused â€”
> committed after reverting the temp MOVE_SLOP_PX 80fâ†’4f. **G4 (contract Â§1/Â§7-G4): tap-select a
> text/image/sprite row item â†’ handles spawn over the PREVIEW** â€” dashed box (drag inside = MOVE,
> center-snap), corner squares (uniform SCALE), rotate stalk (ROTATE, cardinal snap); outside-box
> touches pass through. New `overlay/PreviewHandlesOverlay` (payload-agnostic `Target` adapters, the
> ObjectMenuSheet.Prop split) + new `LayerGestureController.Callback.onItemSelectionChanged` (fires
> only on real selection transitions). Writes = the G2 slider conventions verbatim (armedâ†’key at
> playhead, unarmedâ†’static; ONE undo step per gesture via recordOverlay/SpriteMenuUndo). Text box =
> the exact laid-out TextOverlayLayer child; sprite box = drawSprite math. NO aspect handles (models
> have uniform sizeFraction only â€” visualizer's 8-handle box already covers free-form). Audio/PiP/viz/
> caption targets ride their future Â§2 Prop adapters, same staging as G2. **DEVICE PROOFS (SM-N960U,
> bdd51919, scripted `input swipe` â€” preview-handle drags need NO hold, fully adb-drivable, unlike row
> pickups):** sprite MOVE persistedâ†’undo restored+box re-anchored (badge 50â†’49/redo 1 = one step);
> ROTATE ~12Â° tilt, new step cleared redo; SCALE corner ~2.5Ã— with rotated box tracking; visibility
> guard (item@23.5s selected, playhead@14s â†’ box correctly hidden); text box wraps "Enter text"
> exactly; empty-space tap â†’ box hides. **NITS/OWED:** (a) tap-on-empty cleared the handles but the
> bar's selected highlight looked stale in the same screencap â€” re-look on device, likely a missing
> invalidate on the MISS path (cosmetic, possibly pre-existing); (b) JoyRaptor feel-test â‰¤4 gestures:
> handle sizes/grab radius (14dp), rotate-stalk reach, MOVE snap feel, and whether swallowing
> tap-inside-box (previously = tap-to-edit on the overlay itself) bothers her â€” double-tap on the ROW
> is the editor express lane per contract, but the preview tap-to-edit muscle memory changed for the
> SELECTED item only; (c) device was found in LANDSCAPE with the preview off-screen â€” G6.4's
> small-previewâ†’PiP promotion is the real fix, until then editing landscape is rough (I temp-locked
> portrait for the test, auto-rotate RESTORED after). **G-SERIES REMAINDER:** G5 attach/detach
> (needs Slice-A caption/viz Track kinds â€” next hard slice), G6.3/G6.4 fullscreen-PiP + landscape-PiP,
> G7 coach-marks, G8 marquee, G9 linking; G2 audio/PiP/viz Prop adapters.
>
> **ðŸ—‚ï¸ 2026-07-07 â€” FABLE: G2 GENERAL ADVANCED MENU landed â€” peek/expand object bottom sheet (contract
> Â§2/Â§3), DEVICE-PROVEN for text/image (7e934f5) + SPRITE (follow-up commit).** Holdâ†’release-in-place (and
> canvas long-press) now opens a NON-MODAL sheet in the activity tree: **peek** = grip + the active
> property row (slider + keyframe diamond) with preview AND timeline still live â€” scrub while nudging;
> **expand** (grip drag-up/tap) = identity header (swatch Â· name Â· trash Â· âœ•) + general rows Pos X/Y Â·
> Scale Â· Rotate Â· Opacity + object actions + "Moreâ€¦" into the SAME type editor double-tap opens. Rows are
> `ObjectMenuSheet.Prop` adapters (payload-agnostic chrome): keyframe-aware writes â€” armed items
> record/update a key at the playhead via new `TextOverlayItem/SpriteOverlayItem.addPropertyKeyframeAt`
> (mirrors the shipped opacity-slider convention, X anchored as canonical); unarmed = static setters.
> Diamond = G2 basics only (solid=on-key Â±66ms, tap=drop key, first drop arms the whole pose); swipe-nav /
> long-press-delete / top ribbon = G3. ONE undo step per slider gesture / diamond tap (TransformSnapshot;
> sprite via LambdaAction+restoreTransform mirroring onSpriteManipulated). Text/image actions = the old
> interim dialog's layer moves (new layer above/below Â· move â–²/â–¼) â€” `showLayerItemActionsDialog` is
> REMOVED (both former callers rerouted). Sprite = no layer actions (T8 one-per-lane), Moreâ€¦â†’palette,
> delete=confirm+undo. **DEVICE PROOFS (SM-N960U, bdd51919, temp-slop trick):** text: holdâ†’peek â†’ expand â†’
> opacity drag persisted 0.504 â†’ undo restored default â†’ diamond tap keyed full pose at t=0 (all diamonds
> solid) â†’ undo cleared â†’ collapse â†’ dismiss, ROWGESTURE pickup/hold-release-menu logs clean. Sprite:
> holdâ†’peek â†’ expand ("Sprite" title) â†’ Scale drag persisted 0.509 â†’ undo reverted in-memory to 25%
> (badges 50â†’49 undo/1 redo; json read was a pre-undo autosave â€” undo doesn't force-save, app-wide
> behavior). **Sheet refresh rides `updateCurrentTimeDisplay`** (values+diamonds track the scrub). **OWED
> HAND-TESTS (JoyRaptor, â‰¤4 gestures):** (a) peek-scrub feel â€” hold a text item, scrub while the peek row is up;
> (b) grip drag-up/down feel vs tap; (c) Moreâ€¦/trash from the expanded sheet; (d) canvas long-press route.
> **G2 REMAINDER:** audio (volume row) / PiP / visualizer Prop adapters â€” their Â§2 sections come online
> with their type editors; peek-row "active property" persistence across reopens is session-only by design.
>
> **ðŸ‘† 2026-07-07 â€” OPUS: G1 GESTURE STATE MACHINE â€” double-tap + hold-release landed (contract Â§1).**
> Two of the four G1 gestures are now wired, ALWAYS-GREEN, additive with zero change to the existing
> move/trim/pickup/scrub machinery. **G1a double-tap â†’ type editor (c17ec31, DEVICE-PROVEN):** detection
> in `LayerGestureController.onRowBodyUp`'s tap-resolution (a 2nd clean tap on the SAME item within a 320ms
> UP-to-UP window) fires a new default `Callback.onItemDoubleTapped`; `FaditorEditorActivity` routes
> text/imageâ†’`showTextOverlayEditor`, spriteâ†’`openSpritePalette`, visualizerâ†’`showVisualizerDrawer`
> (audio/PiP = documented no-op until their drawers exist). Single tap still only selects (unchanged).
> DEVICE PROOF (SM-N960U, sandbox bdd51919): single tap on a text row bar = select only (delete badge, no
> dialog); double-tap opens the "Edit text" editor (field/colours/fonts/opacity/keyframes). **G1b
> holdâ†’release-in-place â†’ general menu (64566be, BUILD-GREEN + pickup-path-proven):** after a pickup lifts
> the item, releasing WITHOUT crossing the move slop fires `Callback.onItemMenuRequested` (guarded
> `pickupArmed && !movedDuringGesture && committed`, mutually exclusive with MOVE/TAP/double-tap/delete/
> CANCEL) â†’ text/image route to the existing `showLayerItemActionsDialog` as the interim Â§2 menu. The
> pickup path is device-confirmed (ROWGESTURE logs); the menu-pop is an **OWED HAND-TEST** (folds into
> JoyRaptor's owed G1 feel-test): hold a text/image row item ~0.5s without moving â†’ the layer-actions dialog.
> **DEVICE LORE (saved to memory):** a truly-stationary long-press CANNOT be adb-injected on this device â€”
> `sendevent` is root-denied, `input swipe` scrubs (0-distance ignores duration; any distance scrubs and
> scales oddly), `input draganddrop` fires pickup but always drifts ~35px = a MOVE. To force a stationary
> hold-release proof, temp-raise `MOVE_SLOP_PX` above the drift (same trick as temp-widening a double-tap
> window); restore after. **BUG FOUND (pre-existing, spawned task):** sandbox overlay 9d7fef2b has
> startMs=Long.MAX_VALUE/4 â†’ vanishes off-timeline; already corrupt in the pre-session .bak, so NOT from
> this work â€” likely the trim/overlap code's MAX/4 open-ended fallback. Needs repro + fix + load-time
> self-heal sanitizer. **G1 REMAINDER:** tapâ†’preview manipulation handles is G4 (separate slice); the
> Â§2/Â§3 peek/sandwich general menu (G2/G3) supersedes the interim `showLayerItemActionsDialog` when built.
>
> **ðŸ“ 2026-07-07 â€” OPUS: G6.2 SNAP DETENTS for the resizable timeline (67259b1, DEVICE-PROVEN both ways).**
> Extends shipped G6.1: on grab-bar release the band-height split snaps to the nearest of three detents â€”
> video-dominant (40dp) / balanced (140dp) / timeline-dominant (460dp), matching `LayerRowRenderer`
> MIN/DEFAULT/CAP â€” within a 32dp radius, with a CLOCK_TICK haptic; drops further out keep their free
> position (contract Â§5 "free-drag between them"). ACTION_UP-only change; `setLayerBandMaxHeightDp`
> self-clamps so detents can't drift out of range. DEVICE PROOF via ground-truth `timeline_band_max_dp`
> pref reads (SM-N960U): a drag from 151.46 landing near balanced snapped to exactly 140.0; a drag to a
> mid position kept a free 225.09 (no snap). **G6 REMAINDER:** G6.3 fullscreen extreme â†’ draggable PiP
> preview; G6.4 small-preview/landscape â†’ PiP promotion (contract Â§5, both bigger).
>
> **âœ‚ï¸ 2026-07-07 â€” FABLE: crop aspect gaps filled (road_map "small features") â€” 4:5 + Custom ratio.**
> The crop presets row (`CROP_ASPECT_PRESETS`/`applyCropAspectPreset`) had 1:1/4:3/3:4/16:9/9:16 but was
> missing **4:5 (Instagram portrait)** â€” added â€” and had no **custom numeric ratio** (the road_map
> "numeric ratio entry" gap) â€” added a "Custom" chip â†’ `showCustomCropRatioDialog()` (W:H number fields â†’
> `setLockedAspectRatio(w/h)`, remembers the last ratio for the session, ignores blank/invalid). Green.
> DEVICE-VERIFIED: crop mode now shows `Free Â· 1:1 Â· 4:5 Â· 4:3 Â· 3:4 Â· 16:9 Â· 9:16 Â· Custom` (screencap).
> OWED (1 tap): confirm the Custom chip opens the W:H dialog â€” scripted point-taps kept catching the
> adjacent confirm âœ“; the chips render + the dialog is a standard AlertDialog wired like the proven chips.
> ROADMAP NOTE: a doc-sweep this session found MUCH of the "small never-built features" backlog is already
> DONE (speed preset chips âœ“, crop rule-of-thirds grid âœ“, crop aspect presets âœ“, transitionFrameCache âœ“,
> KEEP_SCREEN_ON âœ“). Genuinely-remaining = diffuse perf micro-opts (hard to verify a benefit), JoyRaptor UX
> decisions (bookmarks/time-chip/muted-caption), locked export code, and the BIG never-started features
> (AI slides, dual-stream recording, visualizer studio phase 3, LUT filters, GL transition preview cards)
> which are each multi-session + flagged "need a fresh go-ahead". See road_map.md Â§BACKLOG.
>
> **ðŸ›Ÿ 2026-07-07 â€” FABLE: DURABILITY (road_map Tier-1) â€” extracted audio no longer lost on OS cache
> wipe.** Both audio-extraction sites (`AudioExtractor.extract()` + `FaditorEditorActivity` extract-from-
> video path) wrote the muxed `.m4a` to `getCacheDir()/faditor_audio` â€” but that file's `Uri.fromFile()`
> becomes the AudioClip's PERSISTED `sourceUri`, so an OS cache-clear silently broke the track
> (`recoverStaleCachePaths()` only recovers VIDEO clips). Now â†’ `getFilesDir()/faditor_audio` (app-internal,
> not OS-cleared, matching the existing `getFilesDir()/images` precedent; new path has no "cache" so it's
> also never mis-flagged stale). BUG DEMONSTRATED on device: `cache/faditor_audio` held 3 at-risk
> `.m4a`s (incl. today's project audio); `files/faditor_audio` will now receive new extractions. Green
> (BUILD SUCCESSFUL 14s). PLUS a load-path MIGRATION (`migrateAudioClipsToDurableStorage()`, guarded +
> idempotent) that rescues EXISTING projects: on load, any AudioClip whose sourceUri still points at
> `cache/faditor_audio` (and the file survives) is copied â†’ `files/faditor_audio` and the URI rewritten,
> then saved. DEVICE-PROVEN (project bdd51919): reopen copied all 3 at-risk `.m4a` â†’ files/faditor_audio,
> project.json went 4 `files/faditor_audio` refs / **0** `cache/faditor_audio` refs, the "Extract from
> video" waveform still renders intact, no crash.
>
> **ðŸ“ 2026-07-07 â€” FABLE: G6.1 RESIZABLE TIMELINE â€” grab bar between preview & timeline, DEVICE-VERIFIED
> both directions + persistence (contract Â§5's headline space-win).** A thin grab bar (grip pill) now sits
> on the previewâ†”timeline boundary (new `@id/timeline_grab_bar`, direct child of `editor_root` between the
> `layout_weight=1` player_container and controls_section). Vertical drag reallocates space by driving the
> M6 layer-band viewport cap: `LayerRowRenderer.MAX_VISIBLE_ROWS_DP` const â†’ resizable field
> `maxVisibleRowsDp` (clamped 40â€“460dp) + get/set/default; `EditorTimelineView.setLayerBandMaxHeightDp()`
> forwards + `requestLayout()` (taller band â‡’ taller measured height â‡’ the weight=1 preview reflows
> smaller, and vice-versa); `FaditorEditorActivity.setupTimelineResizeGrabBar()` maps the drag deltaâ†’dp,
> persists to `faditor_ui`/`timeline_band_max_dp`, restores on load. **DEVICE-PROVEN (SM-N960U, project
> FadCam_20260621_145132):** default 4 rows â†’ drag UP revealed all 7 rows (TextÃ—4 + SpriteÃ—2 + PiP) with a
> smaller preview â†’ drag DOWN collapsed to 2 rows with a big preview â†’ Close&Save + reopen RESTORED the
> 2-row state (persistence). No layout break, no crash. Green (BUILD SUCCESSFUL 21s). NOTE: sandbox left
> with the band dragged small â€” just drag the grip up to restore. **G6 REMAINDER (next):** G6.2 snap
> detents (video-dominant/balanced/timeline-dominant), G6.3 fullscreen extreme â†’ draggable PiP preview,
> G6.4 small-preview/landscape â†’ PiP promotion (contract Â§5). Scaling the MASTER track height itself
> (FEEDBACK #3 literal) is a separate knob â€” this slice resizes the layer band, the primary Â§5 use-case.
>
> **ðŸ§© 2026-07-07 â€” FABLE: SLICE F no-overlap invariant COMPLETE across ALL item types (PiP added).**
> Added `Timeline.enforceNoOverlapVideoLanes()` â€” a direct mirror of the shipped/proven text packer
> (F1) for PiP/video `overlayClips`: groups by `layerId`, packs each lane's overlapping clips into the
> fewest no-overlap sub-lanes, overflow â†’ deterministic `"video-<id>"` lane, IDEMPOTENT. PiP window =
> `[overlayStartMs, overlayStartMs + (hasLoopExtension ? visualDuration : trimmedDuration)]` (always
> bounded, unlike open-ended text). Wired into the saved-project load path in `FaditorEditorActivity`
> alongside the sprite/text migrations (logs "separated N overlapping PiP/video overlay(s)"). Now
> covered: TEXT âœ“(F1) Â· IMAGE âœ“(images are TextOverlayItems â†’ same F1 packer) Â· SPRITE âœ“(T8) Â· AUDIO âœ“ Â·
> **PiP âœ“(this)**. Green (BUILD SUCCESSFUL 17s). VERIFIED by a 14-case standalone JVM harness (overlap
> split, butted/gap kept, 3-way stagger packs back, idempotent 2nd run, loop-aware end, single-item
> no-op â€” ALL GREEN); device multi-PiP overlap not separately staged (needs a 2-PiP project â€” logic is
> algorithm-identical to the device-proven text/sprite packers). Minor follow-up (non-blocking): a NEW
> PiP still lands in the shared "video" lane (`setLayerId("video")`) â€” a same-session overlap separates
> on next load; immediate on-add separation is a nicety, deferred to keep the sensitive PiP add-path
> untouched. MOVE-between-lanes for PiP already resolves at drop (generic M10). Commit follows.
>
> **ðŸŽ›ï¸ 2026-07-07 â€” FABLE: G1 GROUNDWORK â€” held-item MOVE now reaches hidden lanes + honest drop-target
> affordance (green, on device; drag-FEEL hand-test owed).** Two refinements to the existing pickup-move
> machinery that JoyRaptor's 2026-07-07 hand-test surfaced, prep for the full G1 state machine:
> **(1) M6 VERTICAL auto-scroll during a held-item drag** â€” holding a picked-up item near the top/bottom
> of the capped row band now auto-scrolls the LayerRowRenderer viewport so hidden lanes become reachable
> mid-drag (was horizontal-only). New `EditorTimelineView.m6MoveDragVerticalScrollDelta(fy)` (neutral
> middle â†’ 0; returns 0 when content fits, so no spurious scroll; gentler 0.6Ã— speed since rows are
> short); wired into the edge-scroll runnable + the onMove trigger (kicks the loop when near a H edge OR a
> V band edge, self-stops when neither). **(2) M10 DROP-TARGET affordance** â€” the cross-row target was a
> full-width purple STROKE ring that read as an oversized "false size" of a small item (JoyRaptor: "a purple
> outline bigger than itâ€¦ why show me this false size?"). Replaced with a thin left-edge purple accent bar
> + ~12% purple wash = clearly "this ROW", while the single coherent proxy body (drawn at the item's TRUE
> size + resolved drop X) stays the size cue. Files: `EditorTimelineView.java`, `layers/LayerRowRenderer.java`.
> Watcher green (BUILD SUCCESSFUL), installed SM-N960U, editor launches clean. ROWGESTURE logging retained.
> **OWED HAND-TEST (JoyRaptor, drag-feel â€” â‰¤4 gestures):** (a) hold-lift a Text/Sprite item, drag it up so the
> band scrolls to reveal a hidden lane; drop it there â€” does the auto-scroll pace feel right, does it stop
> when you pull back to the middle? (b) drag a SMALL item over another lane â€” is the purple target now a
> tidy left-edge accent (not an oversized outline), with the moving proxy showing the item's real size?
> **NEXT (G1 proper):** the hold/tap/double-tap state machine (contract Â§1, Â§7-G1) â€” STRONG-MODEL.
>
> **âœ¨ 2026-07-06 ~15:25 â€” FABLE: T8 multi-sprite-per-lane bug FIXED + DEVICE-VERIFIED (b55b1cd).**
> Pre-T8 every placed sprite left `layerId=null`, so `Timeline.getLayers()` bucketed them ALL into the
> single default `"sprite"` track â†’ they overlapped (JoyRaptor's FEEDBACK_20260706 #2). Fix (Timeline.java +
> FaditorEditorActivity.java, +56 lines, dep-free): `Timeline.spriteLayerIdFor(item)="sprite-"+item.id`
> (DETERMINISTIC â†’ idempotent migration, stable lane even unsaved) + `Timeline.migrateSpriteLayers()`
> (splits any lane with 2+ sprites: keeps the first, moves the rest; run once in the saved-project load
> path) + `placeSpriteOnVideo()` stamps every NEW sprite its own lane. NO LayerTrackDef â€” getLayers()'s
> leftover-bucket branch already surfaces each non-default sprite layerId as its own buildSpriteTrack lane.
> DEVICE PROOF (SM-N960U, project bdd51919): load logged "moved 1 overlapping sprite(s)"; timeline now
> renders TWO "Sprite" rows (was one); project.json split s2 â†’ `layerId sprite-81563c97â€¦`; a placed 3rd
> sprite got its own `sprite-9a97698fâ€¦` lane (then removed to restore JoyRaptor's 2-sprite content). Watcher
> green (14s). KNOWN-COSMETIC: migrated/leftover lanes show the generic "Sprite" header name â€” per-lane
> naming lands with the LAYERS-UX renderer consolidation (FEEDBACK #1), which is the NEXT track.
> **NEXT-TRACK PLAN READY:** `tasks/PLAN_LAYERS_UX_EXECUTION.md` â€” the layers-UX overhaul HOW, grounded in
> confirmed code facts: BOTH row-render systems run in `onDraw` (`EditorTimelineView.drawLayers` +
> `LayerRowRenderer.layout`), fed together at `FaditorEditorActivity.syncTimelineOverlays()` 8844-8864.
> Text & audio render TWICE (the dup rows); captions & visualizers have NO TrackKind so they live ONLY in
> the old path. Plan = 7 always-green slices Aâ€“G (A: add CAPTION/VISUALIZER TrackKinds + TimedItem sockets
> + Timeline banding views; B: render them in LayerRowRenderer; C: delete old drawLayers rows + old
> selectedLayerKind hit-testing = removes ALL duplication; D: header hit zones + caption-chooser autohide;
> E: vertical re-layout; F: no-overlap-all-types + move-between-layers; G: gesture language DESIGN-FIRST
> with JoyRaptor). Not started â€” clean stop after T8. Sharp edge flagged: the god-class `selectedLayerKind`
> hit-test path must not be orphaned when the old renderer is deleted (Slice C).
>
> **ðŸŽ­ 2026-07-06 ~12:05 â€” FABLE-DAY: A2 TRACKING CORE + COMPOSITING FAMILY LANDED, ALL DEVICE-PROVEN
> (fafb0a8 + 22f29ee), + a REVIEW CATCH fixed: a4fbeba had flipped every PiP vertically on export.**
> **A2 core (fafb0a8, dep-free â€” MediaPipe stays USER-GATED):** params ARE the contract
> (`TrackingFrame`: yaw/pitch [-1..1], blendshapes by name, limb IK as `pinTarget.<partId>.x|.y`) â†’
> `TrackingParamPipeline` (One-Euro ALL inputs â†’ amplitude-jaw fallback â†’ LifeSignals merge,
> deterministic = bake-replay safe) â†’ `TrackingDriverBus` (tracker-thread push / render-pull,
> stale-source guard) â†’ `PuppetPreviewView` FABRIK-re-aims targeted parts' posed pins (outranks
> dangle). `SyntheticTrackingSource` (scripted, frame-counter-pure) + a "ðŸŽ¯ Track" studio chip =
> the device proof: smoke rig puppets itself, yaw sweep + arm orbit, frame-diffs 2.5â€“15.6 luma/s,
> USER-CONFIRMED motion, zero crashes. TrackingCoreTest 18/18. MediaPipe = drop-in behind
> `TrackingSource` (exact mapping in tasks/SPEC_FABLEDAY_20260706_delegation.md Â§D4).
> **Compositing family (22f29ee, Â§C of FEEDBACK_20260702 â€” ONE additive model, not three bolt-ons):**
> `CompositingSpec { masks[], chromaKey, matte }` on Clip (gson, tolerant, omit-empty);
> `MaskPathBuilder` = the single Path authority (Path.op; canvas-normalized shapes; addâˆªâˆ’subâˆª,
> invert = window mode). Masks: PipFrameOverlay clips at export, OverlayVideoPreviewView clips the
> live TextureView child (drawChild) + stills at preview â€” DEVICE-PROVEN both (A/B export diff:
> hole at authored coords Â±0.01, subtract-notch kept). Chroma key (shader: RGB-distance
> smoothstep tolerance/fuzziness + offset): PROVEN â€” keyed PiP region colorful-frac 0.04â†’0.64.
> Track matte (2nd PipFrameOverlay per recipient, lumaÃ—alpha, CPU `activeAt` gate; ExportManager
> resolves peerId + hides the serving peer): PROVEN â€” alpha follows matte luma (pure-PiP 0.82â†’0.48),
> unmatted outside the matte window (0.83 = control), peer hidden.
> **âš ï¸ REVIEW CATCH (device-proven, fixed in 22f29ee):** since a4fbeba (this morning) EVERY PiP
> exported VERTICALLY FLIPPED in place (bitmap textures Y-down vs frame UVs Y-up; authored y=.378
> rendered at .622; the blend A/B luma proof was symmetric in the flip = blind). Fix: shader samples
> overlay/matte V-flipped. Do not remove.
> **PLAUSIBLE-DEFECT NOTE (pre-existing, re-confirmed):** PiPs do NOT composite over IMAGE/gap
> master clips (gap-black item at t=4.2 renders no PiP, matte or not) â€” fix shape in SPEC Â§D6.
> **PREVIEW-HONESTY LEDGER:** preview shows clips unkeyed/unmatted + matte peer still visible
> (probe-#4: export = ground truth); masks DO preview live. Closing steps spec'd (Â§D3).
> **DELEGATION-READY:** tasks/SPEC_FABLEDAY_20260706_delegation.md â€” mask UI, key drawer + swatch
> sampling, matte pairing + dotted line, MediaPipe adapter, sprite/text masks, polish ledger.
> Sandbox restored (project.json.bak reverted, manifest flips reverted, temp cleaned).

> **ðŸŒŒ 2026-07-06 ~08:10 â€” AUTONOMOUS RUN #2: PiP still-fallback + A6 mesh density. STATE-OF-THE-WORLD:**
> **M-EXPORT-2 is COMPLETE (parity a7f7b89 + blend fc3055a). A6 is COMPLETE for the editor** (pin-warp core
> 5e3a94d, smooth+density c54e981/4ede612, pin authoring 0fc9365, dangle 00733aa, mesh overlay) â€” only the
> A2 tracking hookup (MediaPipe, USER-GATED) remains. This run landed: **(bfb60f4) PiP still-frame fallback**
> â€” 2nd+ simultaneous overlay videos now render a cached MMR still instead of nothing (device-proven: two
> overlapping PiPs both visible, top-most live + lower as still at its authored xy); **(4ede612) per-part
> warp mesh density** â€” `AvatarRig.Part.warpSegments` + a "Mesh" stepper (device-proven: 8 bands faceted â†’
> 40 smooth, persisted to JSON) + fixed a stale initial-label desync. Full run-#1 detail in the block below.
> **REMAINING FABLE (ungated):** A6 polish candidates (per-row alpha-extent strip clamp; PiP blend-preview
> approximation so blend clips aren't invisible-until-export), broader adversarial sweeps. **GATED (need
> user):** A2 MediaPipe gradle dep; blend-picker UI design; main phone; push. Sandbox = 1 NORMAL PiP + the
> a6-smoke-rig (dangle+4 pins). Autonomous chain: next wakeup 13:01, self-perpetuating +5h.
> **âœ… REVIEW FINDING FIXED + DEVICE-PROVEN (z-unification commit, Opus landed Fable's fix):** blend PiPs and
> NORMAL PiPs used to composite through DIFFERENT paths (chain vs CompositeExportOverlay), inverting z when
> interleaved. NOW: EVERY PiP composites via `BlendModeGlEffect` in the effect chain in track z-order (shader
> gained a mode-0 = SRC_OVER branch for NORMAL); PiP drawing + MMR machinery REMOVED from
> CompositeExportOverlay (which keeps sprites/text/captions/waveforms, all above every PiP). ONE PiP z
> authority. Proof: injected NORMAL-under-MULTIPLY overlap â†’ MULTIPLY on top (inversion gone); A/B vs
> c2=NORMAL: c2 region 103.7 luma (MULTIPLY) vs 129.4 (NORMAL), RGB diff 25.8 = genuine blend not plain-over;
> c1-only control diff 0.0 = NORMAL rendering unchanged. Sprites/text above both. M-EXPORT-2 is now fully
> closed (parity + blend + unified z).
> Other review notes (all benign): blend GlEffect runs a full-screen pass every master frame even outside the
> PiP window (src.a=0 â†’ base passthrough; transparent texture uploaded ONCE via identity-stable bitmap â€” the
> cost is one cheap shader pass, unavoidable since media3 effects can't time-scope to part of a clip).

> **ðŸŒ™ 2026-07-06 ~03:35 â€” AUTONOMOUS RUN #1 (3:01 wakeup): M-EXPORT-2 FULLY COMPLETE + review fixes.**
> **Blend modes LANDED + DEVICE-PROVEN (fc3055a):** `export/BlendModeGlEffect` (MULTIPLY/SCREEN/OVERLAY/ADD
> against the ACCUMULATED frame, GlTransitionExportEffect pattern) fed by `export/PipFrameOverlay` (MMR
> frames positioned by the SAME transform conventions as the NORMAL pass; both BitmapOverlay texture-identity
> traps handled â€” new-instance-per-live-frame AND identity-stable transparent for empty paths). Inserted
> BEFORE the text OverlayEffect (preview z-rule); CompositeExportOverlay skips non-NORMAL (no double
> composite). ACCEPTANCE: MULTIPLY over the black gap clip â†’ PiP-region luma 82.2â†’17.1, control region
> byte-identical (diff 0.0), pipFrames 171â†’0 proves the skip. Preview shows NORMAL for blend clips (probe #4
> rule: export = ground truth). Blend-picker UI awaits the user's design call â€” engine ready.
> **ADVERSARIAL REVIEW of the 07-06 landings â€” 2 CONFIRMED defects fixed:** (03b9d59) PiP MMR extraction/
> release now share one lock (the GlTransitionFrameOverlay thread-safety precedent); (d6850c1) unposed rig
> fell back to NOTHING â€” rest chain now doubles as the pose (identity warp) so freshly authored pins +
> dangle are live without arming a cell (was a silent authoring dead-end). All six JVM harnesses re-run ALL
> GREEN. PLAUSIBLE-but-unconfirmed (noted, not churned): GRAVITY/damping are px-based â†’ feel varies with
> density (feel-test knob anyway); dangle rest lengths go stale if the view resizes (studio is orientation-
> locked); armed pin-editing with a count-mismatched pose silently hides handles until re-toggle.
> **Preview-honesty clamp also landed:** PiP now clipped to the video content rect (setClipBounds), closing
> the review's overhang divergence. Sandbox restored to NORMAL blend; manifest flips reverted.
> **REMAINING for next runs:** PiP still-frame fallback (2nd+ simultaneous overlay), per-part mesh density,
> handoff consolidation. GATED: A2 MediaPipe dep, blend UI design, main phone.

> **ðŸª‚ 2026-07-07 ~00:35 â€” A6 DANGLE PHYSICS LANDED + DEVICE-PROVEN (00733aa). A6 build phase COMPLETE
> minus the A2-gated tracking hookup.** `avatar/DangleSim` (deterministic verlet chain, anchor-motion
> excitation, FABRIK-style final normalization = exact bone lengths at ANY whip violence â€” the harness
> caught 3 relaxation passes stretching ~10%; DangleTest 9/9). The chain's nodes BECOME the part's posed
> pins â†’ PinWarpStrip renders the bend: dangle + warp are ONE pipeline (bendy hair/tails free). Steps
> once/frame in PuppetPreviewView's onDraw preamble, vsync-paced; pin-editing a part suspends its physics.
> DEVICE-PROVEN: screenrecord diffs spike exactly at each yaw jerk (0.356/0.351) and zero out on settle.
> **DEVICE LORE (runbook-grade):** a continuously-invalidating view makes `screencap` STALE while making
> `screenrecord` RELIABLE â€” the two tools' failure modes are complements; pick by whether the screen
> animates. **FEEL KNOBS deliberately conservative** (DAMPING .90 = settles <1s, GRAVITY 2200): user
> feel-test decides floatier hair. Sandbox rig now has dangle:true on "arm" (backups on-device).
> **AUTONOMOUS OVERNIGHT CHAIN ARMED:** 3:01 AM one-shot wakeup, self-perpetuating +5h per run, queue =
> finish anything in flight â†’ adversarial self-review of tonight's 6 landings â†’ BlendModeGlEffect +
> injection test â†’ delegable stragglers â†’ per-part mesh density â†’ docs consolidation. A2 MediaPipe dep
> stays USER-GATED.

> **ðŸŽ¯ 2026-07-06 ~23:15 â€” A6 PIN AUTHORING UI LANDED + DEVICE-PROVEN (0fc9365). A6 is now AUTHORABLE
> end-to-end without JSON injection.** "âŒ– Pins" chip in Avatar Studio: disarmed = edit the REST chain
> (tap adds y-SORTED, drag moves with neighbor-clamped y â€” the PinWarpStrip monotonic convention is
> UI-unviolable; count changes re-seed every cell's pose pins), armed = pose the armed cell's pins
> (created/seeded only on explicit toggle/arm â€” passive syncs peek, Clear-cell can't be resurrected).
> The warp deforms LIVE under the finger (resolveNow per move). DEVICE-PROVEN on the smoke rig: handles
> render, drag persisted x 0.5â†’0.886, tap-add inserted sorted (y .786 before .9), 3 cells re-seeded to 4
> pins, Save round-tripped to project.json, zero crashes. NOTE: screenrecord on this device now flakes
> repeatedly (unfinalized moov) â€” `adb exec-out screencap -p` is fresh and reliable; prefer it for stills.
> **A6 REMAINING (Fable):** FABRIKâ†’posed-pins tracking hookup (A2, MediaPipe dep), dangle physics,
> timeline/export warp surfaces (ride A4). **USER FEEL-TEST OWED (2 min):** Avatar Studio â†’ "A6 Warp
> Smoke" â†’ âŒ– Pins â†’ drag pins around (star re-warps live), sweep Yaw â€” does authoring feel right?

---
## â•â•â•â•â•â•â•â•â•â•â• ARCHIVE â€” history below (superseded by the current blocks above) â•â•â•â•â•â•â•â•â•â•â•
The blocks above are the live queue + recent landings. Everything below is dated history kept for
provenance: A6 pin-warp build-up (smoke/core), M-EXPORT-2 core, the 2026-07-05 DeepSeek/drag/M-COMP-2
rounds, and older. Read top-down only if you need the "why" behind a current decision.
---

> **âœ… 2026-07-06 ~23:05 â€” A6 PIN-WARP DEVICE SMOKE PASSED (frame-proven on the Note 9).** Injected
> "a6-smoke-rig" (3-pin arm, 1Ã—3 yaw strip, cell swap at the right extreme) into the bdd51919 sandbox:
> warp bends per the authored pins at BOTH yaw extremes, the discrete swap fires, and the pin-snap
> CROSSFADE was frame-captured MID-FADE â€” both cells double-drawn on the SAME warped verts, exactly the
> plan's "swap over identical geometry" read. Zero crashes. Rig left in the sandbox for the user feel-test
> (backup project.json.bak-a6-20260706 on-device; manifest flips reverted). **BUILD-INFRA LESSON (cost
> ~20 min):** opencode's own gradle runs + the user's watcher built CONCURRENTLY and corrupted the
> incremental resource merge (missing .flat â†’ "100 errors: class R"). Recovery: delete
> app/build/intermediates between builds + retrigger. RULE GOING FORWARD: while the user's watcher is
> running, opencode must NOT invoke gradle itself â€” save-and-wait like the Fable lane, or the user pauses
> one side. **USER HAND-TEST OWED (A6, 1 min):** Sprites â†’ Avatar Studio â†’ "A6 Warp Smoke" â†’ sweep Yaw
> slowly â€” the star should bend like a hose left/right and do a soft 130ms cross-dissolve at the right
> threshold; does the warp FEEL right? **NEXT FABLE:** A6 pin authoring UI (design sketch below in the
> plan queue), then FABRIKâ†’tracking (A2, MediaPipe dep).

> **ðŸ¦¾ 2026-07-06 â€” A6 PIN-WARP CORE LANDED (5e3a94d, harnesses 16/16 + resolver-gate re-run GREEN).**
> `avatar/PinWarpStrip` (pure math â†’ `Canvas.drawBitmapMesh` vertex grid; width-preserving sweep along the
> resolved pin chain, end-bone extrapolation, monotonic-chain guard â†’ rigid fallback) +
> `AvatarRig.Part.restPins` (additive tolerant-read schema â€” the art-space rest chain) +
> `PuppetPreviewView` warp path with the 130ms PIN-SNAP CROSSFADE off the resolver's swapped signal (old
> cell rides the SAME verts = swap over identical geometry). **ARCHITECTURE CORRECTION recorded:** the
> plan's "limbs need GL" premise was wrong â€” Canvas mesh-draw warps natively and keeps ALL surfaces
> (studio preview / sprite overlay / export overlay) on one Canvas vertex authority. GL only if profiling
> ever disagrees. **REMAINING A6:** pin authoring UI in Avatar Studio (design-y, Fable); FABRIKâ†’posed-pins
> tracking hookup (A2); dangle physics; timeline/export warp path rides A4 avatar-as-timeline-object.
> **DEVICE SMOKE OWED:** no rig with restPins exists yet â€” inject one (same technique as the PiP injection:
> rig JSON with a 2-part arm, restPins [[.5,.1],[.5,.5],[.5,.9]], 1D domain cells with posed pins) â†’ open
> Avatar Studio â†’ wiggle the domain slider â†’ strip must bend smoothly + crossfade on cell swap.

> **ðŸ“¦ 2026-07-06 â€” M-EXPORT-2 CORE LANDED + DEVICE-PROVEN (Fable lane). PiP now exports with preview parity.**
> The 9956123-recovered WIP is completed: **PiP export rides `CompositeExportOverlay`** (a bottom-most
> overlay-video pass drawing MMR-decoded frames per absolute timelineMs, transform sampled from the SAME
> KeyframeSet + the preview's own DEFAULT_* constants). **The M-EXPORT-1 second-video-sequence path is
> DELETED** â€” probe #3 (verified against DefaultVideoCompositor source): the compositor draws the PRIMARY
> sequence ON TOP, so a second sequence composited the PiP invisibly UNDER the master; never resurrect it.
> `usesLayerFeaturesAffectingExport` now checks `overlayClips` explicitly (fast-path can never swallow a PiP).
> **DEVICE ACCEPTANCE (sandbox export Faditor_20260705_221335.mp4, ffmpeg frames):** PiP present ONLY inside
> its 4.85â€“10.5s window (pipFrames=171 on exactly the hosting item, 0 elsewhere); geometry pixel-matches the
> authored keys (x .859 / y .378 / scale .646 â€” the values opencode's TASK-2 drag persisted, so their lane's
> move+trim is transitively device-proven too); z-order = preview stack (PiP UNDER sprite/text/captions).
> **KNOWN GAPS (documented, next in lane):** (1) blend modes â€” BlendModeGlEffect after the
> GlTransitionExportEffect pattern, the last M-EXPORT-2 item; (2) preview lets a PiP overhang the video
> content rect, export clips at the canvas â€” small preview-honesty fix: clamp/clip the PiP TextureView to
> the content rect (delegable); (3) transition items don't carry overlays (pre-existing, brief); (4) MMR
> per-frame decode is the PiP export cost â€” streaming-decoder TextureOverlay is the perf follow-up if real
> projects hurt. NEXT FABLE: A6 pin-warp GL strip renderer (FabrikSolver 0fe01bd + plan Â§Pin-warp).

> **ðŸ§¾ 2026-07-05 night â€” FABLE REVIEW GATE ON THE DEEPSEEK BATCH + DRAG REMAINDER: ALL COMMITTED.**
> Personal review (no-swarm rule) of the two uncommitted batches; both compile-green in the 19:30 watcher
> build and now in history: **bbd0530** = the stalled opus drag-rewrite finishing pass (collapsed-row proxy
> render, TEMP ROWGESTURE move/drop lines, proxy-state reset â€” the single-proxy CORE was already committed;
> USER HAND-TEST remains the gate, logging stays until it passes). **fa086c7** = DeepSeek's 12 quickwins +
> THREE review fixes for defects that compiled green but would have broken on device: (1) cross_dissolve.glsl
> had its own main()/samplers â€” the loader WRAPS spec-format bodies â†’ runtime shader-compile failure;
> rewritten as `vec4 transition(uv)`. (2) The playhead-ticker optimization never restarted on play (onResume's
> post dies at the first paused tick) â†’ playhead/time/captions/audio scheduling freeze on first play; restart
> added at `onIsPlayingChanged(true)` (remove-then-post). (3) pitchCompensation was never persisted â†’ now in
> the shared clip serializer (written only when false; existing JSON byte-identical). Ride-along: Clip's
> deep-copy ctor + `relinked()` now carry the PiP overlay fields (a relinked PiP stayed a PiP). FLAGGED not
> fixed: copy-ctor drops volumeKeyframes (pre-existing); preview ignores pitch-compensation-OFF (export
> chipmunks, preview doesn't â€” only in the non-default state, opencode TASK 7); pitch toggle not undoable.
> **OPENCODE ROUND-2 QUEUE ISSUED: tasks/Opencode-work.md fully rewritten** (8 tasks: device-verify round-1 +
> playhead-restart regression check, PiP row move/trim/delete/undo with exact site list, export-dialog
> duration estimate, W1/W2 waveforms, 4 bottom-sheet scroll wrappers, preview-pitch investigate, verify
> sweep; progress log preserved; do-not-touch updated â€” ExportManager + ProjectStorage now whole-file
> Fable-lane). **DEVICE NOTE:** Note 9 was attached earlier tonight but the last watcher build says "No
> connected devices" â€” re-plug before device tasks. **FABLE NEXT (unchanged order): M-EXPORT-2** â€” probe #3
> first (export the PiP sandbox, read what the inert path produces), then sequence-gap/offset timing,
> per-frame transform via media3 VideoCompositorSettings sampling the SAME KeyframeSet, opacity, z-vs-captions,
> migrate buildOverlayVideoSequence to a shared LayerPreviewController authority, THEN BlendModeGlEffect;
> **then A6 pin-warp GL strip renderer** (FabrikSolver landed 0fe01bd). Sprite/avatar remaining: A2 MediaPipe
> driver (dep now possible â€” watcher alive), A4 recorder integration, A5 AI rigging; Build-1 sprite items are
> otherwise COMPLETE (S5 landed 3e9bd43, S7 landed 4b90a68).

> **ðŸ¤– 2026-07-05 ~22:00 â€” OPENCODE AGENT DeepSeek V4 BATCH (all compile-verified, no commit yet â€” SUPERSEDED: committed as fa086c7 with review fixes, see block above).**
> Batch of Sonnet-class quick wins from the planner road map (tasks/PLAN_QUICKWINS_20260702.md +
> handoff backlog items) that don't touch the 3 dirty drag-rewrite files (LayerGestureController,
> LayerRowRenderer, EditorTimelineView) or JoyRaptor-lane sprite/avatar files. **ALL build-verified green.**
> **COMPLETED (11 items):**
> 1. **Audio tool icon** `graphic_eq` â†’ `equalizer` (`FaditorToolRegistry.java:64`)
> 2. **Visualizer tool icon** `graphic_eq` â†’ `music_note` (`FaditorToolRegistry.java:86`)
> 3. **Split tool icon** `carpenter` â†’ `content_cut` (tool registry + editor activity)
> 4. **"Silence" â†’ "Clean" rename** (`strings.xml:1105`)
> 5. **Chat text selectable** (`ChatAssistantActivity.java`: `tv.setTextIsSelectable(true)` in addUserMessage/addBotMessage)
> 6. **MessageLog ring buffer** (200-entry cap with `trimMessageLog()`)
> 7. **FLAG_KEEP_SCREEN_ON scoping** (play=true / pause=false, removed blanket flag from editor + chat)
> 8. **Transcribe prompt gap** (auto-show transcribe prompt after asset-browser insert, for video clips)
> 9. **AI rename/describe tools** (3 new tools in `AIToolExecutor.java`: rename_clip, rename_asset, describe_clip)
> 10. **Real GLSL CROSS_DISSOLVE** (new `cross_dissolve.glsl` in assets/gl_transitions/, GLTransitionCatalog entry, Transition.java default return â†’ "cross_dissolve")
> 11. **Playhead tick optimization** (20#7: `playheadUpdater` only re-posts when `isPlaying()` or `audioTailActive`)
> 12. **Pitch compensation toggle** (field + getter/setter in `Clip.java`, "Maintain pitch" checkbox in `SpeedSliderBottomSheet.java`, export wired via `SonicAudioProcessor.setPitch(1.0f)` per-clip)
> **NOT TOUCHED (deferred/excluded):** Task 12 (Delete dead Trim/Heal layout blocks â€” risky cleanup), Task 18 (Rebrand pass 1 â€” user excluded), Task 19 (Visualizer Rolodex redesign â€” user excluded).
> **NEXT:** user to review + commit; then continue with remaining quick wins or next queue item.
>
> **ðŸ› ï¸ 2026-07-05 eve (Fable orchestrator) â€” GREEN-FIX + RELIABLE CROSS-LAYER + EXPORT FIX; DRAG REWRITE IN FLIGHT.**
> Sequence this session (all committed, watcher green, Note 9 attached): (1) **ee19a86** greened a
> committed-red tree â€” one getter typo (`getBgKeyTolerance`â†’`getKeyTolerance`, c5880be); the "100+ errors"
> were stale build.log noise. The user's phone had been on a STALE build â†’ the reason gesture fixes "never
> landed"; NEW RULE: nothing is "done" on code-trace, only on user confirmation against a green build.
> (2) **13c382a** RELIABLE cross-layer path (user-endorsed over fragile drag): add-image-as-new-layer
> (device-proven preview+persist, uses TextOverlayItem.createImage shared render path) + move-clip dialog
> "New layer above/below / Move to layer" (green+installed, long-press entry HAND-TEST-OWED). P3 masterâ†’layer
> promote left as proposal â€” NOW REVISIT: M-COMP-2 PiP just landed, so a non-destructive "copy master clip to
> video PiP layer" is viable (was blocked on live-PiP). (3) **c8eae1e** fixed a PRE-EXISTING export bug:
> image/gap clips (16x16 faditor_gap_black.png) collapsed ALL overlays to sub-pixel on export ("original"
> preset had no rescue Presentation); image-clip-gated scale-to-fit, video path byte-identical, before/after
> device frames prove overlays restored. (4) ðŸ”„ IN FLIGHT: **split-element drag rewrite** (opus) â€” user
> diagnosed the drag as two half-objects (origin ghost tracks X, target outline tracks Y, leaks into wrong
> row); rewrite = ONE proxy at (resolvedX, hoveredRow), drawn on exactly one row, ROWGESTURE-instrumented,
> USER hand-test is the gate. Spec: FEEDBACK_20260703_dragux_v3.md "ðŸ©º ROOT CAUSE". Edge-scroll CONFIRMED
> working on green; don't regress it. QUEUE AFTER: dedup device-verify (logic harness-proven, owed on-device);
> waveform fidelity W1; sprite same-frame-dup (JOYRAPTOR lane, FEEDBACK_20260705.md); 16x16 placeholder data-smell.
> USER OWED HAND-TESTS: P2 new-layer long-press dialog; the drag rewrite when it lands.

> **ðŸŽ¬ 2026-07-05 ~16:50 â€” M-COMP-2 LIVE PiP LANDED + PROBE #1 CLOSED (0453db9). WATCHER ALIVE, device attached.**
> **Probe #1 (PLAN_LAYERS_V2 Part 10) verdict: GO with headroom** â€” the Note 9 ran 2 AND 3 simultaneous
> 1080x1920 **HEVC** decoders at full ~29fps, zero steady-state drops (`compositor/DecoderBudgetProbeActivity`,
> permanent adb-driven debug tool; temp-flip exported to use). **2b creation** = AddAssetBottomSheet
> "Video overlay (PiP)" row (feature flag `OverlayVideoPreviewView.LIVE_PIP`) â†’ background import â†’ overlay
> Clip on layer "video" @ playhead, x/y/scale starter keys at t=0. **2c preview** = `compositor/
> OverlayVideoPreviewView` (one overlay ExoPlayer, top-most-visible clip only, TextureView transformed per
> tick from overlayTransform via KeyframeSet.valueAt at ABSOLUTE timeline ms â€” same evaluator export samples;
> drag/pinch mirrors SpriteOverlayView; decoder released when no PiP; master play/pause edges via
> onIsPlayingChanged). DEVICE-VERIFIED on the bdd51919 sandbox: PiP row (blue) renders; hidden before window;
> LIVE during playback at exact x=0.72/y=0.22/scale=0.35; hidden past end; paused scrub shows correct still;
> **save round-trip PROVEN** (serializer re-wrote overlayClips full-fielded, masters untouched â€” closes 2a's
> owed proof). Known NON-bug: this sandbox ends master playback ~1.4s after play â€” control-proven
> pre-existing (reproduces with zero overlay clips). Sandbox now contains an injected PiP clip; on-device
> backup at project.json.bak-mcomp2-20260705.
> **NEXT (M-COMP-2 lane, strict order): (1) M-EXPORT-2** â€” run probe #3 first (export the PiP sandbox, read
> what the current inert path produces), then fix export: overlay timing (overlayStartMs â†’ sequence offset/gap),
> per-frame transform (media3 1.8 `VideoCompositorSettings.getOverlaySettings(seqIdx, presentationTimeUs)` =
> native keyframe support â€” sample the SAME KeyframeSet), opacity, z-vs-captions parity (PiP must stay UNDER
> text/captions like preview; may need composition-level overlay), migrate buildOverlayVideoSequence to
> `LayerPreviewController.visibleOverlayVideoClips`, THEN blend modes (BlendModeGlEffect after
> GlTransitionExportEffect pattern). **(2) A6 pin-warp GL strip renderer** (FabrikSolver landed 0fe01bd, plan
> Â§Pin-warp: quad-strip + 3 pins, warp between cells, pin-snap crossfade; GL path since Canvas can't warp).
> **DELEGABLE (weaker AI, well-patterned):** PiP timeline-lane move/trim â€” add `item.getClip()` branches to
> `LayerGestureController` mutation sites (mirror TextOverlayItem/AudioClip: MOVEâ†’setOverlayStartMs,
> TRIMâ†’in/out points + undo snapshots); PiP still-frame fallback for 2nd+ simultaneous overlay (MMR poster
> into an ImageView when not top-most); PiP opacity/blend UI drawer (reuse opacity drawer pattern).
> **USER HAND-TEST OWED (PiP, â‰¤4 gestures):** open sandbox â†’ scrub across 1s..6.7s (PiP appears/disappears,
> shows frames while paused) â†’ drag the PiP around + pinch-scale (undo once after) â†’ add your own via
> + asset sheet â†’ "Video overlay (PiP)". Opencode/DeepSeek queue updated in tasks/Opencode-work.md (TASK 0
> reduced to verify-clean; 2 files added to its do-not-touch list).

> **ðŸ¤– 2026-07-05 ~12:00 â€” OPENCODE/DEEPSEEK WORK QUEUE ISSUED: `tasks/Opencode-work.md`.**
> While JoyRaptor/Basil are on cooldown, a Sonnet-class model on the opencode harness executes that
> file's TASK 0â€“9 (tree recovery + first real build of today's javac-only work, device smoke
> verification, JVM harness regression run, totalEffectiveMs one-liner, S5 lane visuals, palette
> key chip, onion skin, mirror-pose, sidecar import). Its progress log is APPENDED to that file â€”
> read it before assuming anything about tree state. JVM harnesses now live IN-REPO at
> `tools/jvm-harness/`. NOTE: the M-COMP-2 (PiP) session left in-flight dirty files; Opencode
> TASK 0 handles them by the twice-proven recover-or-stash protocol.

> **ðŸ§µ 2026-07-05 ~10:30 â€” JOYRAPTOR LANE SESSION SUMMARY (all commits local, watcher STILL down):**
> eafb687 resolver review-gate fixes â†’ bb68685 A1-UI matrix editor â†’ 7df95e9 S4 sprite preview +
> placement path â†’ c924c9f S2b (grid auto-detect + bg-key UI + sidecar export) â†’ f270f48 S6 export
> compositing (preview parity by construction) â†’ A2 core (One-Euro filter + smoother bank, harness
> ALL GREEN). **EVERY touched .java file is compile-verified via direct javac** (technique now in
> DEVICE_CONTROL_RUNBOOK-adjacent memory + reproduced below): real jars from ~/.gradle/caches
> transforms + app intermediates + a javap-regenerated full stub R (new resource names injected);
> fresh .class output dir FIRST on the classpath. Pure logic additionally proven by JVM harnesses
> (ResolverGateTest 7/7, DetectorTest 6/6, OneEuroTest 6/6 â€” sources in the session scratchpad).
> **NOT verified (needs watcher):** 3 XML files (manifest activity entry, layout sprite_overlay_layer
> slot, strings) + on-device behavior. FIRST ACTION WHEN WATCHER RETURNS: confirm green build, then
> device-verify: (1) Avatar Studio opens + add part + arm/drag/blend; (2) place star-guy â†’ scrub â†’
> drag â†’ undo; (3) export a clip with a sprite â†’ ffmpeg frame extraction == preview (S6 acceptance).
> Remaining Build-1: S3 palette panel â†’ S5 lane/keyframing â†’ S7 relink; then A2 MediaPipe driver.
> **S3 LANDED TOO (ca496e7):** SpritePalettePanel â€” micro (frame-step transport + live cell readout)
> + palette detents (instance chips, live cell-thumb carousel where TAP = drop a swap at the playhead
> w/ one-step undo, flips, end-behavior cycle, âš™ manager, empty "+ Load"). Sprites tool button now
> opens the PANEL; manager dialog reachable via âš™. Panel + full activity javac-clean (merged stub-R).
> Build-1 now: S5 lane visuals (diamonds/ribbon on EditorTimelineView) + S7 relink are the only gaps;
> swap-dropping itself already works via the palette (S5's mechanism, minus lane rendering).

> **âš ï¸ 2026-07-05 ~09:30 â€” BUILD WATCHER IS DEAD (build.log frozen at 03:06; no gradle/java process).
> USER: please restart the watcher** (`.\gradlew.bat installDefaultDebug --continuous > build.log` or
> your usual command). Gradle STILL can't run in-agent (loopback, re-tested today even unsandboxed).
> Agent workaround used meanwhile: javac against SDK-36 + app intermediates + gradle-cache androidx
> jars (working classpath saved: scratchpad verified-cp.txt) + JVM harness runs for pure-Java logic.
> **ðŸŽ­ JOYRAPTOR LANE TODAY: A1 review-gate fixes (eafb687) + A1-UI MATRIX EDITOR LANDED (bb68685).**
> A1-UI = AvatarStudioActivity + PuppetPreviewView + PoseMatrixView (arm-a-cell 3Ã—3, drag-to-pose,
> yaw/pitch sliders puppet the blend live, parts from sprite sheets, parent/unparent, dashed
> auto-blend cells). Entry: Sprites tool â†’ "ðŸŽ­ Avatar Studioâ€¦". VERIFIED: all 5 avatar files
> javac-clean + a 7-case JVM harness (ResolverGateTest) proves the review-gate fixes + hysteresis â€”
> ALL GREEN. **NOT yet verified (owed when watcher returns): FaditorEditorActivity dialog wiring,
> manifest/strings XML, on-device launch.** HONESTY NOTE: eafb687's original "build green/installed"
> claim was a STALE build.log read (03:06 build) â€” retroactively covered by today's javac+harness.
> Owed user hand-test when convenient: Sprites â†’ Avatar Studio â†’ new avatar â†’ add star-guy part â†’
> arm center cell, drag it, disarm, wiggle yaw/pitch â€” does the blend feel right?
> **S4 SPRITE PREVIEW ALSO LANDED (7df95e9):** SpriteOverlayView above video / below text+captions,
> resolver cells + keyframed transforms + drag/pinch/auto-keyframe/snap (TextOverlayLayer parity),
> visibleSpriteItems shared filter (S6 export must reuse it), renderer cache identity-validated.
> Interim placement: Sprites tool â†’ tap sheet â†’ "Place on video". javac-verified except
> FaditorEditorActivity + XML (watcher). WHEN WATCHER RETURNS: confirm green, then device-verify
> S4 (place star-guy, scrub, drag) + Avatar Studio launch. Remaining Build-1 queue: S3 palette
> panel â†’ S5 lane/keyframing â†’ S6 export (via visibleSpriteItems + resolver) â†’ S7 missing-sheet;
> avatar A2 tracking driver AFTER (needs MediaPipe gradle dep = watcher).

> **ðŸŽ­ 2026-07-05 â€” JOYRAPTOR LANE: A1 RESOLVER REVIEW GATE CLOSED (eafb687; see honesty note above re verification).**
> Gate found 3 confirmed MAJOR defects in a9d6cc5's PuppetPoseResolver, all FIXED: (1) discrete props
> were domain-dominant-cell-global â†’ sparse-authored cells reset unposed parts' sprites to cell 0;
> discrete choice is now PER PART (heaviest corner posing that part, own hysteresis, DiscreteState
> keyed "domainId/partId", sticky when the neighborhood doesn't pose it); (2) swapped/crossfade signal
> now fires exactly when a part's committed source cell changes (was: silent hard-cut for parts absent
> from the new dominant cell); (3) AvatarRig.fromJson is now truly tolerant-read â€” missing id/partId or
> malformed pin entries skip THEMSELVES instead of NPE-ing the whole rig into storage's silent drop.
> Plus: pins renormalize over pin-carrying poses only (pinless neighbor abstains, no drag toward origin).
> **âš ï¸ NEW BINDING USER RULE (2026-07-05): NO PARALLEL AGENT SWARMS / multi-agent workflows â€” a "small"
> review workflow burned ~5h of the usage plan in 6 min (43 agents). Reviews are done by the main agent
> reading the code personally; at most ONE subagent at a time. Recorded in orchestrator memory too.**
> **NEXT (JoyRaptor lane, unchanged order):** A1-UI matrix editor scaffold (built personally, in flight) â†’
> S4 sprite preview (SpriteOverlayView) â†’ S2b polish â†’ A2 tracking driver.

> **ðŸ› ï¸ 2026-07-05 â€” OVERLAP BUG FIXED (1bc4652), root-caused + PROVEN. User re-tested slice-3 and hit:
> (a) could place two clips OVERLAPPING by dropping between two butted clips; (b) cross-row preview
> OVERLAPS instead of butting. ROOT CAUSE (both, + the old diagonal tug-of-war): resolveNoOverlapStart /
> resolveOverlapOnRow were a 4-pass push-loop that OSCILLATED butt-before/after with no room and gave up
> STILL OVERLAPPING. Replaced with nearestFreeStart (merge siblings into blocks â†’ place in nearest
> free region; tail always free so a legal spot ALWAYS exists â†’ overlap impossible). Verified 13 cases
> in a standalone javac/java harness (scratchpad ResolverTest.java) incl. the exact repro BEFORE porting.
> Both live preview AND the commit-time guard (the PERSISTED-state guarantee) route through it. Time-lock
> reverted to WYSIWYG (rail straight when free, show BUTTED preview when occupied â€” never preview overlap).
> **STILL OPEN from the same user message (NOT yet built):** (1) A1 EDGE AUTO-PAN for row-item drags â€”
> holding a picked-up item at the screen edge must continuously pan the timeline so you can place further
> than the current view (edge-scroll infra exists for asset/audio drags ~line 655, NOT wired to the
> row-item pickup path); (2) #5 OFF-SCREEN BUTT / same-row beforeâ†’after â€” dragging a clip to butt AFTER
> another whose far edge is off-screen: the view should pan to show that side + preview them butted
> (the min-pan excursion partially does this when a joint is armed, but the panel-half before/after
> CHOICE + sustained edge-pan is the missing piece). These two are the user's next priority â€” build A1
> first (concrete, infra exists), then #5. Files: EditorTimelineView (edge-pan/excursion) +
> LayerGestureController. Owed user hand-test after. Everything below is prior state.**

> **ðŸŽšï¸ 2026-07-05 â€” SLICE-3 GESTURE ROUND: 3 of 5 items LANDED (commit after 7f5313a), BUILD GREEN,
> installed on Note 9. AWAITING USER HAND-TEST (drag-feel = unscriptable on this device).**
> Spec: tasks/FEEDBACK_20260703_dragux_v3.md (A9 SNAP-PRIORITY + WYSIWYG DROP PRINCIPLE, both BINDING).
> **DONE:** (#3/A9) diagonal tug-of-war killed â€” in a vertical time-lock the drag now RAILS STRAIGHT at
> the original time (was: resolveNoOverlapStart shoving it sideways to dodge the target row's occupant);
> the butt-magnet re-engages only when an edge comes within snap radius of a neighbour AT the locked
> time; real overlap resolves at RELEASE via the existing commit-time butt-displace; no excursion during
> a pure layer change. (#2) cross-row affordances now PURPLE not white â€” one constant
> (COLOR_DROP_TARGET_RING) drives the drag-target ring + new-layer zone outline + cross-band line;
> same-row keeps item color, snap-home stays gray. (#4) excursion no longer centers the joint (overshoot)
> â€” pans the MINIMUM to reveal it + 56dp margin, holds if already visible, clamped to scroll bounds.
> (#1 WYSIWYG live resolved preview was ALREADY built via per-move resolveNoOverlapStart+applyMoveTo; A9
> completes its time-lock case.) **DEFERRED â€” #5 off-screen panel-half butt selection** (held LEFT of
> panel center previews butted-BEFORE, RIGHT = butted-AFTER): partially served by the excursion reveal;
> the panel-half before/after CHOICE is a larger design-y interaction that deserves its own focused pass
> + feel-test. Files: LayerGestureController / LayerRowRenderer / EditorTimelineView. **OWED USER
> HAND-TEST (â‰¤4 gestures, relayed this session):** see the numbered checklist in the session's final
> message. After the user re-tests: fix what the feel surfaces, then either build #5 or move to the next
> queue item (transcript dedup relaunch).

> **âœ… 2026-07-05 â€” PHASE P RECOVERED, COMMITTED (7f5313a), FULLY DEVICE-VERIFIED. Step 1 of the
> landing block below is DONE â€” next AI starts at step 2 (slice-3 gesture round).**
> The prior agent's uncommitted Phase P work was found compile-GREEN (build.log: BUILD SUCCESSFUL,
> installed SM-N960U) with a coherent diff across the 6 expected code files. Committed as 7f5313a
> ("feat(layers): Phase P - track header menu + M11 ripple/gap toggle"). The AndroidManifest change
> was ONLY the temp `exported=true` uiautomator-launch flip â€” reverted, NOT committed (per the standing
> rule it never enters history). **Device verification (sandbox project bdd51919 project.json ground
> truth):** P1 rename persisted (`trackDefs[0].name="RenamedP"`), P1 delete-with-migrate + P2 z-order
> proven by the prior agent's on-device run; P3 CLOSED THIS SESSION â€” the persisted state shows
> `rippleMode:"gap"` + `clip[1]` replaced in place by a `displayName:"Gap"`, `imageClip:true`,
> `audioMuted:true` spacer pointing at a real generated 16Ã—16 black PNG (`files/images/
> faditor_gap_black.png`, 115 bytes on disk), `sourceDurationMs:19962` = the loop-extended original's
> VISUAL duration (proves the `hasLoopExtension()?getVisualDurationMs():getTrimmedDurationMs()` branch),
> clipCount preserved at 4 (no ripple shift). NOTE: the default-track-rename path (TrackFlags.customName
> â†’ layers-block "trackNames" map) is compile-verified only â€” the device test happened to rename a
> USER-created LayerTrackDef track instead; both paths are in 7f5313a. OWED USER HAND-TEST (feel only):
> long-press a layer header â†’ menu; toggle ripple/gap chip â†’ delete a clip â†’ confirm black gap stays.
> Now proceeding to slice-3 per the user-ordered queue.

> **ðŸ›¬ 2026-07-04 night â€” SESSION LANDING (JoyRaptor/Fable orchestrator). PICKUP INSTRUCTIONS:**
> **1. RECOVER PHASE P (probably in flight/killed at landing):** an agent was building "Phase P â€” layer
> header long-press menu (rename/move-z/delete), z-order end-to-end, M11 ripple/gap toggle." Its
> UNCOMMITTED work (compile-GREEN at landing) touches: AndroidManifest, FaditorEditorActivity, TrackFlags,
> Timeline, ProjectStorage, EditorTimelineView, activity_faditor_editor.xml. Protocol (proven 3Ã—):
> `git status` + build.log green + coherent `git diff` â†’ COMMIT with an honest message; red/incoherent â†’
> stash named clearly, restore green from 2995db4. Verify M11 gap-mode + menu behaviors on the sandbox
> Note 9 if attached (taps are scriptable; screencap STALE â†’ screenrecord+ffmpeg; input swipe can't drag).
> **2. THEN slice-3 gesture round (JUMPS the queue, user-ordered):** tasks/FEEDBACK_20260703_dragux_v3.md
> â€” the "A9 SNAP-PRIORITY RULE" + "SLICE 3 EXPANDED SPEC (WYSIWYG DROP PRINCIPLE)" sections are BINDING
> and written implementation-ready. One Fable/strong agent, files: LayerGestureController/LayerRowRenderer/
> EditorTimelineView. User re-tests after; expect a â‰¤4-gesture hand-test list back to them.
> **3. THEN:** transcript dedup relaunch (conditions in the ðŸ›¬ 2026-07-03 block + prior prompts: timestamped
> backup FIRST, live/edited transcripts byte-untouched, fix the stacking source, synthesize duplicates on
> sandbox to verify) â†’ v3 slice 2 (unified gap-insertion blueprint in the dragux doc) â†’ timeline fidelity
> W1/W2/T1 (tasks/FEEDBACK_20260703_timeline_fidelity.md) â†’ M-COMP-2 probe-first (PLAN_LAYERS_V2 Part 10 #1).
> **OPEN USER CALLS:** muted-track captions show/hide?; main-phone real-project session (never yet run).
> **STANDING RULES:** ONE editing agent at a time (shared watcher; parallel edits burn â€” proven). NEVER
> gradle (watcher builds on save; "BUILD FAILED" from install/device/EOF lines = compile SUCCESS). Sandbox
> Note 9 SANDBOX_SERIAL only; NEVER main phone REAL_SERIAL/project 27221664. Commit each green item.
> Local commits only, NEVER push. Don't touch faditor/avatar/ or sprite files (JoyRaptor's lane) or stash@{0}.
> Everything below this block is history; the queue above is current.

> Living document for the next agent. Update this file when you change architecture, fix a recurring bug class, or make a non-obvious design choice.
> Last updated: 2026-07-04

> **ðŸ›¬ 2026-07-04 eve â€” JOYRAPTOR LANDING: sprite S2 DEVICE-PROVEN end-to-end + avatar A1 model COMPLETE.**
> **S2 (cb07ad7 + a44350a):** full adb-driven device proof on the Note 9 â€” editor launch â†’ OS picker â†’
> star-guy from Downloads (fresh pushes need a MEDIA SCAN broadcast to appear in DocumentsUI) â†’ steppers
> 3x3â†’4x4 â†’ cell 0 named "idle" â†’ Save â†’ project.json: schemaVersion **9** (conditional stamp correct),
> sheetUri project://assets/<uuid>.png, cells [{0,"idle"}] â†’ reopened by sheet id: state restored on
> screen. **v9 write + round-trip PROVEN** (closes the S1 acceptance). Device-caught + fixed: new-sheet
> NPE (buildUi before sheet creation). Verification technique for the never-idle editor: uiautomator can't
> dump FaditorEditorActivity (live player invalidation) â€” launch the TARGET activity directly; the temp
> exported=true flip never entered git history (flipped + reverted between commits).
> **Avatar A1 model (a9d6cc5):** PuppetPoseResolver (pure single-authority: bilinear continuous+pin blend,
> empty-cell inheritance, dominant-corner hysteresis, swappedâ†’crossfade signal, caller-owned DiscreteState
> = deterministic bake replay) + AvatarRig storage, schema **v10** stamped only when rigs exist. GLM-5.1
> mined decisions (827947c) folded into the build.
> **âš ï¸ DISCLOSURE for the dragux lane:** a9d6cc5's `git add -A` accidentally swept your two in-flight
> files (LayerGestureController +17, LayerRowRenderer +40) â€” they built green and are committed under my
> A1 message; nothing lost, but your WIP is now in history there.
> **NEXT (JoyRaptor lane, strict order):** (1) resolver review gate + A1-UI matrix-editor scaffold
> (scrub/slider-driven, no ML); (2) sprite S4 preview (SpriteOverlayView, resolver-driven, below captions);
> (3) S2b polish batch; (4) A2 tracking driver (MediaPipe + One-Euro + thermal governor per MINED).
> User hand-test owed when convenient: open Sprites tool from the carousel â†’ does the star-guy sheet feel
> right to slice by hand (pinch-zoom, pivot drag â€” unscriptable gestures).

> **2026-07-02 night â€” Â§6/M6+M7 ROW-BAND TOUCH FIXED: tap-select, long-press-delete, AND scrub-over-rows all work now (BUILT GREEN, device-verified Note 9 sandbox bdd51919â€¦, com.fadcam.beta, NO commit). This unblocks the Layers hand-test.** The two bottom Track rows (purple "Text"/aqua "Audio") were totally inert â€” no select, no long-press, no scrub â€” because touches were consumed then dropped. **ROOT CAUSE (proven with temp logging, now removed): `LayerRowRenderer.hitTestHeader` had NO X-bounds check.** The per-row `headerRect` spans the row's FULL width in Y but is only meant to be the left 92dp header column; with only a Y-band test, ANY body touch (content-x well right of the header) fell through the 4 icon `.contains()` tests and hit the `return HitZone.NONE` fallthrough â†’ `handleM6RowTouch` treated the whole row (header AND body) as a header hit, returned true, and never called `onRowBodyDown` (M7 select/long-press/drag) NOR reached the scrub axis-decision. Log proof: body tap at content-x=398 â†’ `headerHit=NONE`, hitTestItem never ran. **FIX 1 (the primary):** added `if (x < row.headerRect.left || x > row.headerRect.right) continue;` in `hitTestHeader` â€” body-column touches now skip the header and fall through correctly. That alone restored TAP-select (brightened purple stroke + end-cap handles from 7ae8e42 now show â€” screenshotted) and LONG-PRESS ("Remove text overlay?" dialog fires â€” screenshotted). **Two more bugs surfaced for the SCRUB path (6a47560's intent) and were also fixed:** FIX 2 â€” the pending-axis DOWN in `handleM6RowTouch` returned true but never called `getParent().requestDisallowInterceptTouchEvent(true)` (every other armed-DOWN branch does), so the parent scroll container stole the follow-up MOVEs and the axis decision in `onMove` never ran â†’ added that call. FIX 3 â€” the scrub-passthrough delta was computed in CONTENT-space (`x + scrollOffsetPx`), but `updatePlayheadFromX` re-centers every call so `scrollOffsetPx` shifts by ~the same amount x moved â†’ the delta netted to ~0 and the playhead froze after the first event; changed it to RAW view-space x deltas (`m6RowPendingLastX - x`), exactly mirroring `GestureListener.onScroll`'s `distanceX`. After all three: horizontal swipe over the empty row band scrubs (log-proven playhead 5394â†’6815ms + ruler moved 0sâ†’17s, screenshotted), vertical stays row-scroll (symmetric branch; not meaningfully exercisable here since content 202px â‰¤ viewport 210px = nothing to scroll), a drag STARTING on an item still arms M7 move (`hit=BODY` â†’ m7ItemGestureActive â€” 6a47560's "item hits keep M7/M10 untouched" preserved), and DOWN above/below the rows still passes to normal timeline (`within=false`). Files: `layers/LayerRowRenderer.java` (+9, the X guard), `timeline/EditorTimelineView.java` (3 hunks: disallow-intercept + raw-x scrub Ã—2). Also removed a prior agent's leftover `DIAG onRowBodyDown` FLog in `LayerGestureController` (temp instrumentation, same class as mine). ALL my temp logging (M6_DIAG/LRR_DIAG) grep-verified gone. NOTE: this sandbox's items span the FULL project width, so there's little in-row EMPTY space to scrub from (mostly the top/bottom dead-bands + gaps) and items can't slide sideways â€” Â§7 duration-on-create + numeric fields is still the real ergonomic fix for that; the mechanism is correct regardless. User hand-test list in REPORT_RELAY.

> **2026-07-02 late â€” PING-PONG PARKED by user decision + resize-revert/black-screen regression FIXED (BUILT GREEN, device-verified Note 9 sandbox, NO commit).** User hit two bugs after L2 (9d9539c): resizing a loop reverted its size, then that clip + others went BLACK. ROOT CAUSE (cluster): (1) BLACK SPREAD = the gapless engine is ONE shared ExoPlayer/ONE playlist; a PING_PONG clip auto-promotes the whole project to gapless and injects baked-reversed MediaItems â€” if a reversed item fails to decode (or the auto-promote rebuild races) the shared player blacks out and the black spreads to EVERY clip. Evidence: an orphan baked file `cache/reversed/rev-1899-4884-*.mp4` existed for clip[3] but clip[3] was saved OFF â€” user set ping-pong, hit the black, reverted (deleted that orphan). (2) RESIZE REVERT = a loop-extension edge drag fired BOTH `onTrimFinished` AND `onLoopTrimFinished`; for a right-loop-drag the trim `endFraction`â†’1.0 (handle pinned to source bound), so `onTrimFinished` clobbered the clip's real out-point out to full source. FIX: new single flag `Clip.PING_PONG_PARKED=true` gates 4 seams (all L2 code KEPT, just dormant) â€” (a) loop-drawer ping-pong chip disabled/dimmed + "coming soon" toast, no mode switch; (b) `resolveReversedUri` returns null while parked â†’ `MasterPlaybackEngine.isEligible` rejects every PING_PONG clip â†’ whole project drops to LEGACY forward-tail = a stored ping-pong clip DEGRADES to a plain forward NORMAL-loop wrap (never black, never crash) in preview; (c) `ExportManager.buildLoopExtensionItem` `reverse` forced false â†’ export forward-tail too (preview==export); (d) `kickReverseBakeIfNeeded` early-returns â†’ NO bake ever kicked, incl. from resize. RESIZE FIX: `EditorTimelineView` ACTION_UP now fires `onLoopTrimFinished` ONLY (not `onTrimFinished`) when `loopChangedDuringDrag`, so a loop resize no longer clobbers in/out; `onLoopTrimFinished` made self-sufficient (`userDragging=false`, `setTrimFromClip`, `updateTrimBounds`, `setExactSeek`). DEVICE-VERIFIED (Note 9 sandbox bdd51919â€¦, package com.fadcam.beta): loop drawer shows ping-pong DIMMED + tapping it leaves loopMode=1 (proven via project.json), Loop chip stays green; "Extend to end" grew clip[1] loopAfterMs 11000â†’12000 with in/out UNCHANGED (0/8962) + clean gapless rebuild (6 items, no PlayerError) â€” this is the same updateTrimBoundsâ†’rebuild path an edge-drag uses; undo restored 11000 + clean rebuild; force-stop+relaunch came back "Gapless engine ACTIVE for 4 clips", zero black in any of ~6 screenshots across all clips. L3 WIP KEPT (see PLAN Â§Status). NOTE for un-parking: flip `Clip.PING_PONG_PARKED=false` restores L2 exactly as 9d9539c â€” but FIRST fix the black-decode root cause (a reversed item that won't decode must fall back to forward per-item, not black the shared player). Â§6 rows still dead (see below). NOT re-attempted: the raw edge-DRAG resize gesture (one scripted attempt missed the handle hit-zone in the reflowing per-clip zoom view) â€” hand-test item for user; the code path itself is proven via the drawer button.

> **ðŸ›¬ 2026-07-03 evening â€” SESSION LANDING. Sprite S1 REVIEW-GATED + P0 no-overlap fixed. READ FIRST.**
> Since the S1 commit: (1) **RIG VISION recorded as binding direction** (PLAN_SPRITE_ANIMATION Â§RIG VISION):
> keyframed transforms + per-item anchor override + parentItemId = full puppet rigs; all ADDITIVE to the S1
> schema; a future resolveTransformAt must be the single transform authority like SpriteFrameResolver is for
> cells; reviewers must flag anything in S2â€“S7 that paints us out. (2) **P0 audio-stacking FIXED (c7442ae)**:
> applyMove â†’ resolveNoOverlapStart snaps same-row overlaps to the nearer butting edge (multi-pass, landing
> row = hoverTarget else own row); cross-row occupied drops were already bookend-handled. (3) **S1 adversarial
> review gate ran (2 lenses Ã— 2 skeptics): 5 findings, ALL FIXED** â€” MAJOR: setTimeRange degenerate-range
> guard (endâ‰¤start â†’ open-ended, mirrors TextOverlayItem; was: sprite permanently invisible + S5 trim would
> hit it); MAJOR: pruneOrphanedTrackFlags now knows "sprite" + sprite layerIds (was: sprite track flags
> deleted every load); isVisibleAt made end-INCLUSIVE matching TextOverlayItem; keyTolerance serialized
> independently of bgKeyColor; createLayerTrack javadoc includes SPRITE.
> **NEXT:** S2 setup editor (SpriteSheetEditorActivity) â€” GATED on the competitor-research design pass
> (tasks/RESEARCH_COMPETITOR_UX_20260703.md, another worker; REMIND USER if still absent next bundle).
> Non-gated S2 prep any agent can do: sheet decode/cell-blit engine (decode-once bounded bitmap, cell
> srcRect math from grid geometry, bg color-key at decode) â€” pure engine, no UX decisions. User hand-test
> owed: no-overlap feel (try to stack two audio items; try to stack text) + the batch-2 drag/trim items.
>
> **ðŸŽ¬ 2026-07-03 afternoon â€” SPRITE ANIMATION BUILD 1 STARTED (S1 model+storage DONE, 3243fdb) + roadmap synced (c46c612).**
> User decisions: Fable-5 builds vision-heavy work personally (sprites, AI integrations, UX overhauls;
> small stuff can go to lesser models); major AI features AFTER core stability; NOT full ultracode â€”
> sequential Fable implementation + adversarial-review workflows at S1/S4/S6 gates. Roadmap got a sync
> section (9 orphaned plan clusters indexed, Layers/loops statuses corrected, sprite promoted). Sprite plan
> now lives IN-REPO: tasks/PLAN_SPRITE_ANIMATION.md with a BINDING 2026-07-03 amendment â€” build NATIVE on
> the landed Track model (schema v9, 4th TimedItem payload, SPRITE track grouping in Timeline.getLayers,
> dual-write stamps v9 only when sprite data exists). S1 landed green first try: sprite/ package
> (SpriteSheet+sidecar JSON, FrameTrack step/hold primitive, SpriteOverlayItem, SpriteFrameResolver â€” the
> ONLY cell-index authority). Load-path device-proven (v8 sandbox project opens under v9, json untouched);
> write-path proof rides the next real edit; sprite round-trip provable once S2/S3 create data. User's test
> sheet committed at repo root: star-guy-spritesheet.png. NEXT: S1 adversarial review gate â†’ S2 setup
> editor (DESIGN GATE: fold in tasks/RESEARCH_COMPETITOR_UX_20260703.md â€” in flight from another worker;
> if absent next bundle, REMIND the user). Still open elsewhere: audio-overlap P0 (dragux_v3 A8).
>
> **ðŸ›¬ 2026-07-03 ~04:15 â€” SESSION LANDING (Fable orchestrator, autonomous overnight run complete). READ THIS FIRST.**
> **The ENTIRE gesture cluster is DONE and installed on the sandbox (SANDBOX_SERIAL), awaiting ONE morning
> hand-test.** Code commits this session, all BUILT GREEN via the watcher, each checkpoint-committed:
> 9cf3080 (contract redesign recovered from the lost agent + 2 orch fixes) â†’ aadf9c1 (delete = selection
> trash badge) â†’ 4375fab (feedback batch 1: badge 9dp+viewport-pinned, row-scrub FLING, timeline-locked
> TRIM STRIPES, honest cross-band preview + insertion line) â†’ fcc0bd5 (adversarial-review fixes â€” CRITICAL:
> ACTION_CANCEL/pinch-interrupt now ABORTS+REVERTS instead of committing the drop/new-lane; delete badge
> deferred to tap-on-UP so swipes-from-badge scrub; trim handles win the badge-overlap strip; VelocityTracker
> recycle) â†’ c0248db (BOOKEND MANEUVER: occupied-row snap, panel-half BEFORE/AFTER, animated view excursion
> with CONTENT-LOCKED playhead cue, animate-back, suppressMoveMapping across the return glide) â†’ 5ac5701
> (post-pinch dead zone: surviving finger pans immediately, re-anchored zero-jump, flings on release).
> **Review evidence:** a 3-lens Ã— 2-skeptic adversarial workflow confirmed 5 defects (all fixed in fcc0bd5);
> full findings JSON in the session task output. Hand-test #1 evidence: ROWGESTURE pull proved every
> contract case (AXIS-HORIZONTAL scrub-not-move, TAP select-only, PICKUP, drop commits) and proved the old
> 7dp badge got ZERO hits (hence the redesign). Logcat buffer CLEARED at landing for a clean morning pull.
> **USER DECISIONS RECORDED THIS SESSION:** (1) bookend/no-overlap spec confirmed "correct" â€” sub-lane
> overlap rendering is DEAD/superseded; (2) DESIGN PRINCIPLE: per-item actions = SELECTION BADGES (trash
> roundel pattern), never new gestures â€” see PLAN_LAYER_GESTURE_CONTRACT.md Â§DESIGN PRINCIPLE.
> **NEXT (strict order):** (1) morning hand-test checklist (relayed in the session's final message â€”
> 6 items: swipe-scrub, tap+trash-badge incl. pinned-on-long-item, pickup+bookend excursion feel, cross-band
> insertion line honesty, trim stripes, pinchâ†’pan handback) + pull `adb logcat -d -s ROWGESTURE:D`, verify
> BOOKEND/EXCURSION/PINCH-HANDBACK/DELETE lines; (2) fix whatever the feel-test surfaces; (3) STRIP the TEMP
> ROWGESTURE logging (LayerGestureController ROWGESTURE_LOG + all RG() in EditorTimelineView/LayerRowRenderer);
> (4) then the standing queue: rebrand pass 1 (name DECIDED "Joy Creator", icons in art/, quick-wins d86a3c0)
> â†’ Tier-1 durability â†’ transcript dedup (timestamped project.json backup FIRST) â†’ M11 â†’ M-EXPORT-1 (Opus) â†’
> ping-pong unpark. Known deferred bookend edges are listed in the PLAN Status block (same-row overlap,
> 0-clamp, interior gaps).
>
> **ðŸ›¬ 2026-07-03 ~03:45 UPDATE 2 â€” HAND-TEST #1 PASSED (log-verified) + FEEDBACK BATCH 1 LANDED (4375fab).**
> User hand-tested the redesign: core contract CONFIRMED by feel AND by ROWGESTURE pull (AXIS HORIZONTAL
> scrub-not-move Ã—2, TAP select-only, PICKUP-MOVE commits, droppedOnNewLayer=true, cross-row toTrack move;
> trim "works excellent"). Log also PROVED the trash badge got ZERO hits all session ("hard to hit").
> **4375fab ships their 4 feedback fixes:** (1) badge 9dp + 2.0r slop + PINNED to screen-right edge on long
> items (deleteBadgeCx single-sources glyph+hitzone; layout() captures hScroll/width); (2) row-band scrub
> FLING (VelocityTracker on the custom path â†’ startPlayheadFling, extracted from onFling); (3) edge-trim
> STRIPES â€” timeline-locked content-space diagonal grid, 180ms fade (resize visibly "eats stripes" vs move);
> (4) cross-band hover now ARMS a new-lane drop + draws an insertion line at the TRUE lane position (visual
> â†’ boundary above audio band; audio â†’ below last audio row); drop commits via onItemDroppedOnNewLayer;
> HOVER REJECTED log throttled to once-per-row. BUILD GREEN, installed @ sandbox.
> **SUPERSEDED: the sub-lane overlap follow-up is DEAD** â€” user's new binding model: NO overlapping items
> on a layer row; occupied-row drops SNAP to bookends. Full BOOKEND MANEUVER spec (animated view excursion,
> timeline-PANEL-half bookend choice, playhead stays content-locked as the "temporary maneuver" cue,
> animate-back on exit) now in PLAN_LAYER_GESTURE_CONTRACT.md FOLLOW-UP 1 â€” Opus-tier, NEXT after user
> confirms batch 1 + the spec restatement. Post-pinch handback (FOLLOW-UP 2) still queued after that.
> An adversarial multi-agent review of 9cf3080+aadf9c1+4375fab ran at landing â€” check the session report
> for surviving findings before building on these files.
>
> **ðŸ›¬ 2026-07-03 ~03:10 UPDATE (orchestrator pickup) â€” GESTURE REDESIGN RECOVERED + COMMITTED, AWAITING USER HAND-TEST.**
> The in-flight agent's diff was found dirty + RED (1 compile error). Diff-reviewed (coherent, matches PLAN
> TARGET CONTRACT), fixed, committed **9cf3080**: swipe-on-item=scrub, tap=select, 450ms long-press=pickup
> (haptic+lift), drop zone PINNED to visible viewport bottom. Orch fixes folded in: (1) dead write-only field
> `m7PendingLastX` used out-of-scope `x` (the compile error) â€” removed; (2) **onUp had NO m7ItemPendingDown
> branch** â€” a quick tap left the 450ms pickup timer live â†’ item self-lifts after the finger left + gestures
> jam until next DOWN â€” added TAP resolution (cancel timer, close pending gesture, release parent intercept).
> Then **aadf9c1**: delete relocation done (plan's unchecked item â€” the agent had unwired long-press delete
> leaving onItemDeleteRequested UNREACHABLE): trash roundel on the SELECTED item (right end, inside trim cap),
> ItemZone.DELETE hit-tested first w/ finger slop, DownResult.CONSUMED fires the same confirmation dialog on
> DOWN like header icons; badge auto-skipped on too-narrow items (deleteBadgeCx single-sources glyph+hitzone).
> BUILD GREEN both commits; APK auto-installed on sandbox SANDBOX_SERIAL @ 03:05; logcat buffer CLEARED for
> a clean ROWGESTURE pull. **NEXT: user hand-test (4-gesture checklist relayed 03:06) â†’ pull
> `adb logcat -d -s ROWGESTURE:D`, verify: PENDING body â†’ AXIS HORIZONTAL scrub (no MOVE), TAP select-only,
> PICKUP armed + zoneInViewport=true + screenZoneBot < ~924, DELETE badge CONSUMED. Then queue items 2-5
> below (sub-lanes â†’ post-pinch handback â†’ strip ROWGESTURE â†’ rebrand...). ROWGESTURE logging still IN.**
>
> **2026-07-04 â€” LAYERS MVP COMPLETE (M-EXPORT-1 @ 1d0c0b0: shared-authority export parity, decoded-stream
> md5 gate) + PHASE R ROBUSTNESS LANDED (26cf3cf: hide-toggle live refresh device-proven; same-row overlap
> never persists â€” row system + legacy waveform lane + audio-trim clamps; ONE 8dp snap constant everywhere;
> time-locked vertical swap w/ dashed guides, occupant-never-moves). PHASE P IN FLIGHT (Fable): layer header
> long-press menu (rename/move-z/delete), z-order end-to-end, M11 ripple/gap toggle â€” if found dirty, assess
> diff + build.log per the twice-proven resume protocol.** USER HAND-TEST OWED (Phase R, 4 gestures: audio
> stack attempt must butt/snap-back; vertical time-locked swap w/ guides; free placement w/ gentle 1-2mm
> magnets; audio trim stops at neighbor). OPEN USER CALLS: muted-track captions show or hide?; main-phone
> real-project session. QUEUE AFTER P: transcript dedup relaunch (spec self-contained in prior prompt/
> handoff), v3 remaining P1s (edge auto-pan, minimap drag-nav), timeline fidelity W1/W2/T1, M-COMP-2
> probe-first, preview perf memoization (Track views rebuild per access), export-duration math oddity.

> **ðŸ“ 2026-07-03 eve â€” PING-PONG UNPARKED, FULLY DEVICE-PROVEN (fe88e39). Diagnosis method: ultracode
> 3-investigator + adversarial-judge workflow (plan + full evidence: tasks/PLAN_PINGPONG_UNPARK.md/.json).**
> Root cause SETTLED: mid-playlist HEVCâ†’AVC codec swap (amplified by zero error handling in the gapless
> engine). Fixed: (1) rank-1 containment â€” onPlayerError â†’ poison reversed URI â†’ PER-CLIP forward degrade â†’
> reseek+resume (corruption drill proven live, no blackout ever again) + debug EventLogger + bake-promote
> rebuildGeneration race fix; (2) hevc_mediacodec bake (attempt-1 winner; 10.5Mbps HEVC hvc1 = source-matched;
> âš ï¸ h264_mediacodec fails on this device but hevc_mediacodec WORKS â€” remember); (3) PARKED flag flipped, 4
> seams live. Export parity RUN ON-DEVICE for the first time (was only ever by-construction): reverse leg
> matches preview, same cache file. OWED: user eyeball of live wrap + reverse-audio ear-check.
> âš ï¸ **CONCURRENT-SESSION WARNING:** ~11 rebrand files (splash/strings/manifest/onboarding + new
> joy_creator_splash.png) are DIRTY in the tree from ANOTHER session's in-progress rebrand pass â€” NOT
> committed here, owner unknown. Do NOT commit/revert them blindly; confirm with the user/other harness first.
> Timeline-fidelity spec queued: tasks/FEEDBACK_20260703_timeline_fidelity.md (W1 waveform render = small,
> ride-along candidate). v3 drag-UX spec: tasks/FEEDBACK_20260703_dragux_v3.md (P0 = A8 audio overlap).

> **ðŸ›¬ 2026-07-03 ~03:15 â€” SESSION LANDING (JoyRaptor/Fable orchestrator at context limit). READ THIS FIRST.**
> **âš ï¸ AN AGENT MAY STILL BE / HAVE BEEN IN FLIGHT at landing:** "gesture contract redesign" editing
> EditorTimelineView / LayerGestureController / LayerRowRenderer / FaditorEditorActivity per
> tasks/PLAN_LAYER_GESTURE_CONTRACT.md (BINDING spec: swipe-on-item=SCRUB, tap=select,
> long-press=pickup+lift+haptic, drop zone pinned INSIDE viewport, delete moves to selection state, extend
> the TEMP ROWGESTURE logging). On pickup: `git status` â€” if those files are dirty: build.log green + diff
> coherent â†’ COMMIT it (agent reports are lost between sessions; diff-review is the protocol, done twice
> already today, see 7ae8e42); red/incoherent â†’ stash w/ clear name, restore green from 64192bb.
> **THEN, strict order (same files, ONE agent at a time):** (1) relay the redesign's â‰¤4-gesture hand-test to
> the user, pull `adb logcat -d -s ROWGESTURE:D` after; (2) sub-lane overlap rendering (PLAN FOLLOW-UP Â§);
> (3) post-pinch pan handback (PLAN FOLLOW-UP 2 Â§); (4) strip ROWGESTURE TEMP logging once user confirms.
> **Today's landed evidence chain:** row scrub pass-through 6a47560+1a13557+23930bb (X-only-slop + pinch-leak
> fixes); ghost-lock/selection 7ae8e42; L1 loops 52cdc29; L2 ping-pong 9d9539c but **PARKED** by ffcdc86
> (shared-player reversed-item decode failure blacks out preview â€” unpark = fix that decode, the parity
> architecture is sound); icon art in art/ (9562300; orchestrator rec: Expanse=splash, simplified Director
> clapper=launcher); name DECIDED = **Joy Creator** (5069a3b), Studio = descriptor.
> **Queue after the gesture cluster:** rebrand pass 1 (quick-wins catalog d86a3c0) â†’ Tier-1 durability â†’
> transcript dedup (timestamped backup FIRST) â†’ M11 â†’ M-EXPORT-1 (Opus) â†’ ping-pong unpark â†’ feedback
> batch (#8 small-screen, #5 transcribe-on-add+gear, #6 tools drawer) â†’ masking/chroma/alpha planning
> (FEEDBACK_20260702_layers_masking.md Â§C) â†’ full-studio Â§7 of DESIGN doc.
> **Tiering (user's budget rule):** Opus ONLY for ping-pong decode / M-EXPORT-1 / gesture state machines;
> Sonnet low-med for everything else; specs must be lesser-model-executable.
> **User workflow:** not a programmer; hand-tests on the Note 9 and describes FEEL â€” turn that into log
> evidence (ROWGESTURE pattern), never script drags (impossible on this device), â‰¤4-gesture checklists.

> **2026-07-02 eve â€” L2 TRUE PING-PONG COMPLETE (9d9539c, device-verified) + ghost-lock/selection commit (7ae8e42) + user icon art in art/ (9562300).** Ping-pong now truly reverses: `export/ReversedSegmentCache` (ffmpeg-kit, **libx264 forced â€” Note 9 HW encoder rejects these sources**, ~6s bake for 3.5s span, 30s guard, off-main + drawer kick + auto-promote); preview playlist alternates fwd/rev; ExportManager reverse branch uses the SAME baked file+formula (old forward-tail fake + mirror hack + `setPlaybackSpeed(-1f)` all DELETED); `totalEffectiveMs()` now counts loop extensions â€” closes the audio-tail resume-from-pause bug AND the totalEffectiveMs follow-up in one fix. Owed: user eyeball of a live ping-pong wrap + reverse-leg audio listen (test clip was muted); >30s bake guard untested on device (no long source). 7ae8e42 = TrackFlags baseline-snapshot merge guard (shows-unlocked-acts-locked fixed) + stale-flags cleanup + SELECTION VISUALS now actually drawn (were never implemented) â€” user hand-test checklist delivered. NEXT per queue: L3 loop polish (incl. dead ping-pong fields cleanup) â†’ rebrand pass 1 (icon concepts in art/, naming question OPEN: art says "Joy Creator STUDIO" â€” confirm with user before mass string work) â†’ Tier-1 durability â†’ dedup â†’ M11 â†’ M-EXPORT-1.

> **2026-07-02 â€” L1 (seamless NORMAL loops on gapless engine) COMPLETE, BUILT GREEN, device-verified on Note 9 sandbox (bdd51919â€¦).** Resumed WIP `1597fee` (compile-green, cut mid-verification) â€” the WIP was ~95% done: `MasterPlaybackEngine` already expanded NORMAL-loop clips into before/main/after rep windows with clamp math verified to mirror `ExportManager.buildLoopExtensionItem` EXACTLY (read both side by side â€” same `ceil` rep count, same `min(trimmedPlayMs, extensionMs - repIndex*trimmedPlayMs)` clamp, same append order), same-clip rep-to-rep seams suppressed (`SeamListener` fires only on real timeline-clip change), continuous visual position/duration plumbed through `getCurrentPositionInWindow()`/`getCurrentWindowDuration()`, and the activity's poll-based NORMAL-loop wrap block correctly bypassed when `playerManager.isGapless()` (ordering: PING_PONG legacy â†’ gapless-NORMAL early-return â†’ legacy-NORMAL â†’ STILL legacy, all correct, none of L2/L3 touched). **Real gap found and fixed:** the loop DRAWER's button-driven paths (`applyLoopMode`, `extendLoop` â€” the "Off/Loop/Still/Ping-pong" mode chips and "Extend to start/end/prev/next clip" buttons) mutated the clip's loop fields but never called `playerManager.updateTrimBounds(clip)`, unlike the drag-driven path (`onLoopTrimFinished`, already wired) and undo/redo (`refreshEditorAfterUndoRedo`, already wired) â€” so editing a loop via the drawer left the gapless engine's playlist STALE (wrong rep count/boundaries, or wrong engine entirely if switching to/from PING_PONG/STILL) until some unrelated action forced a rebuild. Fixed by adding the same `if (!clip.isImageClip()) playerManager.updateTrimBounds(clip);` call (guarded, matching the established convention) to both methods. Flag `FaditorPlayerManager.GAPLESS_ENGINE` restored to `true` (previous agent had set it `false` for baseline measurement only).
>
> **Device verification (Note 9 sandbox `bdd51919â€¦`, clip[1]: loopMode=NORMAL, loopAfterMs=11000, trimmed=8962ms â†’ 2 reps, rep0=8962ms full + rep1=2038ms clamped-partial, visual duration=19962ms):** wrap measurement used logcat-timestamped `MasterPlayEngine` seam/wrap markers correlated against `screenrecord` + `ffmpeg` frame extraction (NOT naive whole-clip `freezedetect` â€” this footage is near-static rug/fabric texture, so freezedetect at -30dB threshold produced sustained false-positive "freezes" on genuinely smooth playback; the reliable signal was per-frame MD5 hashing at 30fps in a 1s window bracketing each logcat-timestamped wrap, counting consecutive IDENTICAL frames). Results: main-passâ†’rep0 wrap = 2 single-duplicate-pairs out of 30 frames (normal frame-pacing rate, zero extended freeze); rep0â†’rep1 (partial) wrap = 1 duplicate pair out of 30; clip[1]â†’clip[2] exhaustion/auto-advance = 2 duplicate pairs out of 31 â€” **all three wraps show ZERO frozen-frame runs longer than 1 frame (33ms)**, vs the previous agent's measured legacy baseline of 106â€“163ms. Play-through auto-advance across BOTH remaining cuts (clip1â†’clip2, clip2â†’clip3) confirmed via `onGaplessSeam autoAdvance=true` reaching a clean 00:27/00:27 end state. Pause mid-extension (00:18, within rep1) held a coherent non-corrupted frame; trim-via-drawer ("Extend to end" +1000ms) confirmed via `project.json` diff (loopAfterMs 11000â†’12000) AND a live "gapless playlist prepared: 6 clipped items" log confirming the rebuild fired; undo confirmed via `project.json` byte-diff (only `lastModified` timestamp differs, loop fields exact match, gapless rebuild fired again on undo via the pre-existing `refreshEditorAfterUndoRedo`â†’`updateTrimBounds` path).
>
> **Seam-clobber add-on (commit `9edad8a`, already-committed fix â€” verifying it holds now that loops ride the same engine):** captured 3 genuine user-seek cross-clip seams (`autoAdvance=false`) during scripted taps and 2 auto-advance seams (`autoAdvance=true`) during play-through. All 3 user-seek seams correctly took the "don't re-home" code path (verified both by the `autoAdvance=false` log flag, which gates the `setPlayheadFraction` call in `onGaplessSeam`, and by the final on-screen playhead sitting at a real tapped position, never 0/a clip-start); both auto-advance seams correctly DID re-home (reaching the clean timeline end). **Caveat, reported honestly per the one/two-attempt rule:** a controlled "40 scripted taps across ONE fixed boundary" batch was NOT cleanly achieved â€” `EditorTimelineView` auto-reflows into a per-clip "zoomed filmstrip" view on tap, which invalidates a pre-computed tap coordinate the instant the view mode flips (recalibrating from a stale screenshot lands taps back inside the already-zoomed clip instead of crossing the boundary again). Two attempts (one blind 40-tap batch, one recalibrated 5-pair-at-a-time batch) both hit this same reflow obstacle; real cross-clip seam samples were captured through the incidental view transitions that DID occur, and all samples agree, but this is a smaller/less controlled sample than a clean 40-cross run would have given. Not iterated on further per the testing-economics rule â€” flagging for the next session if a stronger sample is wanted (would need e.g. a fixed-zoom / non-reflowing scrub surface, or driving seeks via a debug intent extra instead of raw taps).
>
> **One pre-existing bug found (NOT fixed â€” out of L1's gapless-engine scope, flagged separately, follow-up task spawned):** `FaditorEditorActivity.totalEffectiveMs()` (~line 587) sums `getTrimmedDurationMs()` per clip but never adds `loopBeforeMs`/`loopAfterMs`, so on any project with a loop extension it undercounts the real timeline length (confirmed: sandbox project's real total = 27624ms, `totalEffectiveMs()` returns 16624ms â€” short by exactly the 11000ms extension). This function gates the "audio-tail" feature (`onPlayPauseClicked`-equivalent ~line 3009): pressing Play from a paused position anywhere past the miscalculated `videoEndMs` (which includes the back half of clip[1]'s extension plus all of clip[2]/clip[3] in the sandbox) triggers `audioTailActive` mode â€” the video freezes on whatever frame was last shown while `updatePlayheadPosition()`'s audio-tail branch drives the time counter purely by wall-clock elapsed time, never resuming real ExoPlayer playback, until it "catches up" to the timeline end a few real seconds later. Reproduced live on-device. This is flag-independent (present in both `GAPLESS_ENGINE=true/false`) and pre-dates this whole session (`git log -S"totalEffectiveMs"` â†’ only the `10bc40b` baseline commit), so it's a latent defect in the audio-tail feature exposed by loop extensions, not something L1 introduced â€” left alone as instructed ("don't touch scope outside L1"), but it directly breaks "pause mid-loop â†’ resume" for any looped project, so it's a real near-term fix (one-line: mirror the `hasLoopExtension() ? getVisualDurationMs() : getTrimmedDurationMs()` pattern already used correctly elsewhere in this same file, e.g. ~7130-7136/~7160-7167/~6960-6966).
>
> **Files touched:** `FaditorEditorActivity.java` (+13, the two `updateTrimBounds` calls + comments), `FaditorPlayerManager.java` (flag restore, 1 line). No commit made (per instructions â€” the user commits). Build green throughout, no temp logging added or left behind.

> **2026-07-02 â€” P0 "ruler snaps playhead to 0" â€” ONE real cause FIXED (BUILT GREEN), scope narrowed; two more items diagnosed-not-built. Session ended early (usage window).**
> **Root cause CONFIRMED with device evidence (the gapless-seam clobber):** In M-COMP-0 gapless mode, ANY cross-clip seek (ruler-scrub OR clip-body tap â€” both funnel through `onPlayheadSeeked`) issues `player.seekTo(window,pos)` to a different media-item index, so ExoPlayer fires `onMediaItemTransition(REASON_SEEK)` â†’ `FaditorEditorActivity.onGaplessSeam(idx)`, which UNCONDITIONALLY re-homed the playhead to that clip's START via `setPlayheadFraction(inPoint/sourceDur)`. That async callback lands a few ms AFTER `onPlayheadSeeked` already set the correct tapped position, clobbering it â€” snapping to 0 when the target was clip 0, or to the clip's start otherwise. **Evidence (scripted 40-tap ruler/clip loop on Note 9 sandbox `bdd51919â€¦`, temp `SNAPDBG` logging in the seam path, now removed):** 10 seams fired that run; **2 forced playhead to exactly 0** (`onGaplessSeam newIndex=0 â€¦ AFTER setPlayheadFraction playheadNow=0`), **4 more yanked it backward by up to 3441ms** to the target clip's start â€” all with `userDragging=false` so no existing guard caught them. This is a genuine engine-adjacent transition-callback bug, NOT a raw ExoPlayer position-0 read: the 0 came from `setPlayheadFraction(startFrac)`, not from `getCurrentPosition()`. **Engine theory (transient position-0 / mediaItemIndex reset mid-seek) â†’ DENIED for the snap itself:** the position read path is clean; the snap is the seam handler re-homing, full stop.
> **Fix (minimal, engine untouched, legacy flag intact):** `MasterPlaybackEngine.SeamListener.onSeam(int)` â†’ `onSeam(int, boolean autoAdvance)`; engine passes `autoAdvance = (reason==REASON_AUTO)`. `onGaplessSeam(int,boolean)` now re-homes the playhead **only when `autoAdvance`** (playback played THROUGH a cut â€” where snapping to the new clip's start is correct and the playhead is already there). For a user SEEK it runs all the per-clip UI sync as before but LEAVES the playhead where `onPlayheadSeeked` put it. Files: `compositor/MasterPlaybackEngine.java` (interface + one call site), `FaditorEditorActivity.java` (`onGaplessSeam` signature + guarded `setPlayheadFraction`; the `this::onGaplessSeam` method-ref binds to the new 2-arg sig automatically). **BUILT GREEN.** **NOT re-verified on device after the fix** â€” the after-fix tap run happened to land all taps on the clip lane at the timeline-start scroll position (0 seams fired that run), so it neither reproduced nor exercised the seam; the before-fix repro + the code change are the evidence. Next session: re-run the loop with the timeline scrolled so taps cross a mid-timeline boundary into clip 0's region, expect ZERO `playheadNow=0` seams.
> **Coordinator's revised diagnosis (user hand-test) â€” verified in CODE, changes NOT yet made (ran out of window):**
>   â€¢ **Mechanism 1 (tap ambiguity):** CONFIRMED the geometry. `EditorTimelineView.hitTestSegment` accepts y from `rulerHeightPx - touchSlop/2` down through the clip lane, so taps in the bottom sliver of the ruler AND anywhere on a clip body select that clip. BUT the recommended behavior ("tap a clip â†’ select + seek to TAPPED x, not clip start") is **ALREADY IMPLEMENTED**: the segment-tap-up branch (`onUp`, ~line 4191) calls `seekToTimelineMs(xToTime(downX+scrollOffsetPx))` = the tapped position, THEN toggles selection via `onSegmentSelected`â†’`selectSegment` (which does NOT seek). So there is NO separate seek-to-clip-start on clip tap in current code. What the user saw as "snap to ~1s" was almost certainly the SAME gapless-seam clobber (mechanism above, now fixed) landing after that tapped-position seek crossed into clip 0 (their first clip is ~1s). **Recommendation for next session: re-test mechanism 1 on-device AFTER this fix before changing anything â€” it may already be resolved.** Note: a discrete tap in the PURE ruler zone (above the clip lane) currently does NOT seek at all â€” there is no `onSingleTapUp` in `GestureListener` (only `onScroll`/`onFling`), so `onUp` with `downSegIndex<0` just calls `onPlayheadDragFinished`. If the user expects "tap empty ruler = seek there", that's a separate small addition (add `onSingleTapUp`â†’`updatePlayheadFromX`).
>   â€¢ **Mechanism 2 (surface overlap / two surfaces eat one finger during a ruler DRAG):** NOT yet investigated in the preview/workspace surface code â€” only the timeline side was read. The timeline's `EditorTimelineView` already calls `getParent().requestDisallowInterceptTouchEvent(true)` on down/scroll, so the parent shouldn't steal it; the suspected culprit is a SEPARATE gesture surface on the preview/player area that also scrubs. Next session: find the preview-surface touch/scrub handler (search `playerView`/preview drag-to-scrub in `FaditorEditorActivity`), confirm both process pointers, and add first-claim-wins pointer ownership (a shared "a scrub gesture is active" latch checked by both). Physical gap between ruler hit-zone and that surface, and whether a few-dp dead-zone/hit-slop helps, is UNMEASURED â€” do it there.
> **Untouched/again-green rules honored:** no git commit; ExportManager/Layers-gesture/captions code not modified for this task; all temp `SNAPDBG` logging + the scratch `userSeekInFlight` field removed (grep-verified zero matches). **Legacy engine:** flip `FaditorPlayerManager.GAPLESS_ENGINE=false` to bypass â€” the seam path (and thus this whole bug class) doesn't run in legacy, so the fix is inert there; not re-tested with the flag off this session.

> **2026-07-02 â€” Caption-style-keyframe UX REDO COMPLETE (BUILT GREEN, full device-verify with JSON+screenshot evidence).** Found the M-COMP-1 commit (`01d0d66`) had already landed most of the scaffolding (data model, undo action, `caption_keyframe_drawer` XML, arm/nav/delete wiring, and the CC-lane per-segment coloring in `EditorTimelineView`) â€” this was NOT the killed stash (left untouched, stash@{1}), it was real committed foundation. Closed the actual gaps: (1) fixed a real bug where `Clip.captionStyleId` never resynced when kf[0] was replaced/removed, so deleting the first keyframe reverted to a STALE base style instead of "next keyframe's style extends back" â€” `Clip.java` now keeps `captionStyleId` as a derived cache of keyframe[0]'s style across `addOrUpdateCaptionStyleKeyframe`/`removeCaptionStyleKeyframe`/`setCaptionStyleKeyframes` (the last one matters most: undo/redo is now self-contained with no separate style snapshot needed); (2) tolerance 40msâ†’50ms (`Clip.CAPTION_STYLE_KEYFRAME_TOLERANCE_MS`); (3) nav buttons now dim/disable per-direction at ends (were show/hide as a pair); (4) new `captions/CaptionStyleKeyframeController.java` (stateless: `computeNavState`, `isOnKeyframe`, `tapActionFor`, `colorForStyle` alias) centralizes logic that was duplicated inline; (5) added a stopwatch shortcut (`caption_kf_arm_shortcut`) to the bottom caption-style chip bar so keyframe mode is reachable without long-pressing the CC timeline lane â€” taps the SAME arm state + drawer, not a second source of truth.
>
> **Device-verified on Note 9 sandbox (`bdd51919â€¦`), tap-only, no drags:** armâ†’toastâ†’drawer-open; drop keyframe (JSON: `t`/`s` fields, e.g. `{t:0,s:"boxed"},{t:6500,s:"hot"}`); on-keyframe tap = REPLACE (toast "Keyframe style replaced", array stays same length) vs off-keyframe = DROP (toast "Style keyframe dropped", array grows) â€” confirmed via `CaptionStyleKeyframeController.tapActionFor` computed BEFORE mutation; `<`/`>` nav jumps exactly onto keyframe times + dims at ends (`enabled`+`alpha` both checked via `uiautomator dump`); delete removes + confirmed the first-keyframe-removed-extends-back fix works (chip highlight + solid CC-bar color flipped from the deleted style to the surviving one); undo restored the keyframe array byte-for-byte across DROP/REPLACE/REMOVE (undo counter decremented, redo incremented, JSON diffed each step). CC-bar per-segment coloring + diamond markers (spec's visual ask) were ALREADY correct pre-existing code â€” got a clean two-tone screenshot (tealâ†’mint transition with a diamond at the boundary) as bonus confirmation. **Caveat found, not a bug:** on a very-high-speed clip (6.5x in the sandbox), nav-to-keyframe can land outside the Â±50ms tolerance because timelineâ†’source seek quantization gets amplified by the speed multiplier â€” pre-existing seek-pipeline characteristic (same pattern the opacity/volume keyframe drawers would hit), not something this task's scope covers fixing. `buildCaptionDrawerContent()`'s separate "Caption Style" bottom-sheet (distinct from the floating chip bar) still bypasses keyframe mode entirely (its chips always set base style) â€” left as-is, not in the spec's UX description, flagging for a future pass if the user wants keyframe-awareness there too.

> **2026-07-02 â€” M10 drag-between-layers + drop-to-new-layer COMPLETE (BUILT GREEN, one-undo-step gap fixed, device-verify PARTIAL â€” sandbox tap/pan/lock checks passed, the actual drag gesture unconfirmed).** Picked up `0cf3a4c`'s WIP, which was already ~95% complete: `LayerTrackDef.java` (new, persistent user-created track defs), `Timeline.getLayers()/getAudioTracks()` grouped by `layerId` with `extraLayerTracks`, `LayerRowRenderer` cross-row highlight ring + "+ New layer" drop zone (drawn BELOW the last row, not above the top row â€” the WIP took the plan's "or a dedicated drop zone" branch; document this if a future agent expects an above-top-row zone), `LayerGestureController` hover-target tracking + same-band/locked/hidden rejection guard, full `ProjectStorage` serialize/deserialize of `trackDefs`+`layerId`, and the real pre-existing `onScroll` guard fix (`!m7ItemGestureActive && !m6RowDragActive`) â€” kept as-is, verified regression-free (see below). **Gap I fixed:** the WIP recorded the position-change and the track-change as TWO separate `undoStack` pushes for a diagonal drag (the common case â€” finger rarely moves in a pure vertical line), violating acceptance (d) "each completed drag = ONE undo step." Fix: flipped `LayerGestureController.onRowBodyUp()`'s callback order so `onItemMovedToTrack`/`onItemDroppedOnNewLayer` fire BEFORE `onGestureFinished` (was: after); they now stage undo/redo `Runnable`s into `FaditorEditorActivity.pendingLayerTrackUndo` instead of calling `recordAction` themselves, and `onGestureFinished` folds them into ONE `mergedAction()` alongside the position-change halves (also handles the track-only-no-position-change case via `maybeRecordTrackOnlyChange`, which the WIP would have silently dropped in that edge case). Compiles green (`compileDefaultDebugJavaWithJavac` executed + passed, installed on Note 9). Files touched beyond `0cf3a4c`: `layers/LayerGestureController.java`, `FaditorEditorActivity.java` only â€” no model/storage changes, so v8-stamping behavior is exactly what the WIP already established.
>
> **Device verification done (cheap, per testing-economics rule):** tap-scrub on master timeline ruler moved playhead + preview + captions correctly (confirms the onScroll guard fix does NOT break normal panning â€” the M6/M10 flags are scoped to `handleM6RowTouch`, which early-returns `false` whenever `layerRowRenderer.isWithinRowRegion` is false, i.e. always for plain projects); horizontal swipe-pan also scrubbed correctly; no crashes in logcat across the session. Ground truth: pulled sandbox `project.json` (`bdd51919â€¦`) via `run-as` â€” schema v8, ONE "text" track + ONE "audio" track (locked, matching its on-screen padlock icon), `trackDefs: []` (M10 track-creation never yet exercised on this project â€” clean baseline for the user's hand-test). **My ONE scripted drag attempt landed on the master-timeline scrub area instead of the target row item** (coordinate miss â€” confirmed via before/after `project.json` diff: bit-identical, zero mutation, no crash) â€” per the mandate this was not iterated on. Full numbered hand-test checklist is in `tasks/REPORT_RELAY_20260702.md` and this session's final report.
>
> **Scope note:** M10 moves an ITEM between existing rows or to a new row; it does NOT reorder rows themselves (no drag-a-row-header-to-reorder feature exists yet, and none was in M10's milestone-table scope). The UX addendum's "higher row = higher z, row reorder must preserve the mapping" binding decision is real but applies to a not-yet-built row-reorder feature â€” flagging so it isn't assumed already satisfied.

> **2026-07-01 â€” "Transcribe this video?" prompt + Editor Settings sheet (BUILT GREEN, device-verified).** Feature A: `FaditorEditorActivity.maybeShowTranscribePrompt()` fires after `onVideoAssetPicked` (covers both video-insert paths that funnel through it: OS picker via `videoPickerLauncher`, and FadCam recordings via `VideoSourceBottomSheet.onRecordingSelected`; NOT covered â€” `insertAssetAtPlayhead`/`insertAssetAtIndex` asset-browser inserts and `initProject` initial load, out of scope per task). Dialog shows Fast/Accurate/High accuracy checkboxes (exact labels from `transcript_model_choice` strings) + "Don't ask me again" + centered OK; cancel/back = none selected, no pref change. Selected models run sequentially via a small queue (`startQueuedTranscriptions`/`runNextQueuedTranscription`) chained through a new `startTranscription(type, onDone)` overload (old call sites unchanged) â€” sequential because `TranscriptionEngine` already uses `Executors.newSingleThreadExecutor()`, so true concurrency wasn't possible anyway; queuing avoids stacking UI/progress state. Feature B: new `tool_settings` gear block appended at the end of the bottom tool carousel (`activity_faditor_editor.xml` ~line 1921) opens `FaditorSettingsBottomSheet` (new class, mirrors `FaditorInfoBottomSheet`'s programmatic-view/dark-gradient convention, `NestedScrollView` content, row-based so future settings are trivial to add) with a switch bound to the same pref. Pref: `Constants.PREF_FADITOR_ASK_TO_TRANSCRIBE` = `"pref_faditor_ask_to_transcribe"` (default true), getter/setter added to `SharedPreferencesManager`. Device-verified on Note 9 sandbox (bdd51919â€¦): gear opens sheet, switch toggle persists across `am force-stop`+relaunch (confirmed in `shared_prefs/app_prefs.xml`), dialog appears/doesn't appear per pref, dialog dismiss=no-op confirmed, and a real end-to-end Fast/Vosk transcription was triggered from the dialog (no Whisper/Accurate download triggered) â€” words appeared on the timeline confirming the existing pipeline wiring works.

> **2026-07-01 â€” Small-screen scroll pass (transcript model picker FIXED, BUILT GREEN, device-verified).** Root cause of the confirmed clip bug: `transcript_model_choice` (`activity_faditor_editor.xml`) was a plain `LinearLayout` (`0dp`+`weight=1`) stacking title/subtitle/Fast/Accurate/Whisper cards/note with no scroll container, so on a short/zoomed transcript panel the lower options were unreachable. Fix: changed that node's tag to `androidx.core.widget.NestedScrollView` (same id `transcript_model_choice`, same visibility toggling from Java â€” `transcriptModelChoice` field is typed `View`, only `setVisibility()` called, so no code changes needed), `fillViewport="true"`, with all existing rows moved into one inner `LinearLayout` child. Drawer itself (semi-transparent side panel, 260dp width, resize handle, word long-press) untouched. AUDITED other `activity_faditor_editor.xml` drawers (top drop-downs: volume/opacity/loop/caption-keyframe/visualizer/caption/move/word-scrub) â€” all already-safe by wide margin (est. â‰¤300dp content vs ~700dp+ available at 1080Ã—2280/480dpi); spot-verified `move_drawer` and `loop_drawer` on-device, both fit with room to spare, no changes made. Also noted (out of scope, NOT fixed): several `BottomSheetDialogFragment` pickers build rows programmatically with no scroll wrapper (`VolumeControlBottomSheet`, `AddAssetBottomSheet`, `CanvasPickerBottomSheet` [8 rows â€” real risk], `FlipPickerBottomSheet`); others already use `NestedScrollView` (`FilterBottomSheet`, `RelinkCatalogBottomSheet`, `VideoSourceBottomSheet`, `FaditorInfoBottomSheet`, `SpeedPickerBottomSheet`, `SpeedSliderBottomSheet`, `AssetBrowserPanel`'s RecyclerView). `CanvasPickerBottomSheet` (8 aspect-ratio rows, no scroll) is the next-most-likely clip candidate if this class of bug resurfaces. Device-verified on Note 9 sandbox (bdd51919â€¦) at simulated 1080Ã—2280/480dpi: opened transcript panel via the version-bar "+" (added transcript already existed so the first-run picker doesn't normally show â€” used "+ new version" to reveal it), confirmed only "Fast" was reachable before the fix's effect, then confirmed scrolling revealed "Accurate" (~128MB) and "High accuracy"/Whisper (~57MB) plus the trailing note, all tappable. Backed out without triggering a real transcription job (no project data mutated). `wm size`/`wm density` reset to device native (1440Ã—2960/420dpi) at end â€” confirmed stable across repeated checks (note: `wm density reset` alone re-applied a stale 560 override on this device; had to `wm density 420` explicitly).

> **2026-07-01 â€” Caption "apply style to all clips" FIXED + undo/redo popup spring animation (BUILT GREEN, device-verified).** Batch-2 items 1+2. **Root cause (item 1):** `applyCaptionStyleToAllClips` already set the base `captionStyleId` on every clip/audio clip correctly, but per-clip caption-style KEYFRAMES (`Clip.captionStyleAtClipMs`) take priority over the base style at render time in BOTH the live preview (`FaditorEditorActivity` ~6430) and export (`ExportManager`/`CompositeExportOverlay`) â€” so a keyframed clip kept showing its old style everywhere the keyframe track covered, base-style change or not. **Fix:** `applyCaptionStyleToAllClips` (~10739) now also snapshots + clears each video clip's caption-style keyframes as part of the same forward action, restoring them verbatim on undo (`Clip.setCaptionStyleKeyframes`); single `LambdaAction` undo step, unchanged dialog UX. **Item 2:** `showUndoRedoHistoryPopup` (~7731) now springs the popup (`card` root) in from the anchor button â€” pivot computed from the anchor's on-screen position translated into the popup's fixed-offset local coordinates (no layout wait needed), scale 0.3â†’1 + alpha, `OvershootInterpolator(1.6f)`, 200ms in / 150ms out with `AccelerateInterpolator`; row taps and outside-touch (`ACTION_OUTSIDE` via `setTouchInterceptor`) both route through one guarded animate-out-then-real-`dismiss()` path (double-dismiss-safe); the undo/redo jump itself runs synchronously before the out-animation starts. Known gap: system back-press still dismisses instantly (no public pre-dismiss hook on `PopupWindow` for that path) â€” accepted for this polish item. Device-verified on Note 9 sandbox (bdd51919â€¦): created a real keyframe repro (armed caption-keyframe mode, dropped a "hot" keyframe on a "bounce"-styled clip), long-pressed a different chip â†’ applied "zoom" to all 4 clips + 2 audio clips, confirmed via `project.json` ground truth (clip styles all â†’ zoom, keyframe list cleared) AND a live screenshot showing "zoom"-style captions rendering on a previously-untouched clip; one Undo restored the exact prior per-clip styles + the keyframe byte-for-byte; popup animation confirmed via `screenrecord`â†’ffmpeg frame extraction (mid-fade frame captured for pop-in; shrink-out completes within ~9 frames of the outside-tap, no stray/stuck popup).

## ðŸ”­ OPEN BACKLOG â€” START HERE (updated 2026-07-02 midday)

**ðŸž P0 BUGS (2026-07-02 user hand-test â€” fix BEFORE resuming the feature queue):**
1. **"Snap to zero" â€” USER-DIAGNOSED (2026-07-02 midday): NOT an engine bug.** Two stacked interaction
   problems: (a) tapping a CLIP in the lane under the ruler selects it AND seeks to the CLIP'S START â€”
   with a 1s first clip that looked like snapping to ~0 (playhead went to 1.0s, the clips' border);
   (b) fat-finger ruler drags also graze the adjacent workspace/scrub surface â†’ both react â†’ forward/
   backward seek fight. AGREED FIXES: clip tap = select + seek to the TAPPED position (not clip start);
   gesture EXCLUSIVITY (first surface to claim a drag owns the pointer until lift; assess a few dp of
   dead-zone between strips). An agent was mid-implementation when the midday window closed â€” check its
   dated entry below + `git status`: finish or `git restore` per its notes. It was also told to
   confirm/deny the gapless-engine transient theory with its instrumentation and CLOSE that thread.
   The fat-finger overlap fix needs a USER hand-test at the end (unscriptable).
2. ~~**Layer-row items can't be TAP-selected**~~ **FIXED 2026-07-02 night** (see top dated entry). Root
   cause was `LayerRowRenderer.hitTestHeader` missing an X-bounds check â€” it claimed the entire row band
   (header AND body) as a header hit, so `onRowBodyDown` never ran. Fixed + also fixed the two scrub-path
   bugs (parent-intercept + content-vs-raw-x delta). Tap-select, long-press-delete, and scrub-over-rows
   all device-verified. STILL OPEN structurally: Â§7 duration-on-create + numeric fields (sandbox items
   span full width â†’ can't slide sideways / little empty in-row space to scrub from). Â§6 linkage
   highlight (PLAN_LAYERS_UX_ADDENDUM) not built this session â€” only tap-SELECT was in scope here.

**FEEDBACK BATCH 3 (2026-07-02, queue after P0s + rebrand/dedup):**
a. Project title header: top-center "Untitled" â†’ long-press = rename project; tap = open project
   browser; replace pin icon with a twirl-down caret next to the centered title.
b. Project selector: rename option per project; small dim hint text "hold to select multiple"
   (a real user failed to discover multi-select delete).
c. AI chat batch: (i) paperclip attach image â†’ vision model; (ii) small tasteful model-slug label
   (two-color gradient per DESIGN doc) so users know which model OpenRouter routed to; (iii) chat text
   must be SELECTABLE/COPYABLE (timestamps, file names); (iv) BIG-TICKET: AI project-folder integration
   (Claude-Code-style agentic access to the project's files) â€” needs its own plan doc before building.
d. Layers UX addendum Â§6 (tap-select+linkage), Â§7 (duration-on-create + numeric duration/placement),
   Â§8 (locked-item styling: keep hue, ~50% desat, diagonal hatch, padlock wiggle on attempted touch).
e. AI-feedback integration tiers (EVAL of Minimax/DeepSeek/BigPickle doc, 2026-07-02): Tier-1
   verify-then-fix durability/leak pass (faditor_audio cache bug, transitionFrameCache, messageLog,
   AssetScanner threading, KEEP_SCREEN_ON, icon collision, dead trim/heal blocks); Tier-2 portability
   package (zip export/import, Make Portable, auto-backup) after M-EXPORT-1; Tier-3 AI upgrades
   (function-calling, streaming, token discipline, stock footage); Tier-4 plugin folder P0 + templates.
   âœ… USER-APPROVED 2026-07-02 ("go ahead with your plan") â€” micro-items catalogued with provenance in
   tasks/PLAN_QUICKWINS_20260702.md (Â§A folds into the Tier-1 agent, Â§B into rebrand pass 1, Â§C
   opportunistic; skip-list documented there too). Everything remains VERIFY-THEN-FIX.

(previous backlog below â€” 2026-07-01 evening)
Strategy + standing rules: `tasks/EVAL_20260701_joy_creator.md`. Design/brand direction: `tasks/DESIGN_JOY_CREATOR.md`.
2026-07-01 feedback batch 1 = DONE (11/11, entries below). **USER DECISIONS RECORDED:** checkpoint commits YES
(local branch `joy-creator`, push DISABLED â€” commit after every green+verified feature, message style
"feat/fix: ...", NEVER push, never rewrite history; other AI harnesses may ignore git entirely); transcript
dedup AUTHORIZED if end-user-invisible + auto-backup first; export work package AUTHORIZED; crop-in-transition
GL AUTHORIZED; visible rebrand AUTHORIZED (per DESIGN doc â€” de-politicize, keep character mechanic for later
AI-companion reskin); **LAYERS = GO** (validate plan vs rival-CapCut bar first).

**FEEDBACK BATCH 2 (2026-07-01, user-tested; do in order):**
1. **BUG:** caption "apply style to all clips" does NOT apply to other video clips (user tested). Fix `applyCaptionStyleToAllClips`.
2. Undo/redo history popup: spring-up scale animation from the pressed button; shrink back into it on select/dismiss.
3. Tools carousel v2: (a) labels autosize to ONE line ("transitions"/"transcript" wrap today); (b) edit mode â€”
   swipes SCROLL (never move icons), LONG-PRESS picks up an icon (icon lifts above finger), green vertical
   insertion line under finger, near-edge hover auto-scrolls the row, persistent Done control at right screen
   edge while editing; (c) replace pin icons with a DIVIDER BAR: left of divider = pinned home row (manual,
   â‰¥1 enforced), right of divider = auto-sorted by usage.
4. Caption STYLE KEYFRAME UX (model support exists â€” CaptionStyleKeyframes): stopwatch arm toggle in caption
   advanced settings; armed: click style at playhead = drop style keyframe; `<`/`>` jump prev/next keyframe;
   on a keyframe: style click REPLACES it; a small â€œâˆ’â€ affordance REMOVES it (previous style extends, or next
   if it was first); CC bar/segments tint to match each style's selector color (pop=yellow, hot=red, â€¦).
5. Rebrand pass 1 per `DESIGN_JOY_CREATOR.md`; then transcript dedup; then export work package; then **LAYERS**.

Older backlog (still valid, folded into the order above):

**A. VERIFY (low risk, do first):**
1. **Re-export the user's REAL project** `27221664-21e9-4e9d-8fd7-e8884bb0eb55` (21 clips, `content://`+`project://`
   sources, transitions at clips 10/15, 2 audio, long audio caption) on the NEW build and verify: orientation
   (`ffprobe` â†’ 1080Ã—1920, no rotation), captions play through the WHOLE song, transitions OK, audio present.
   This is the one thing not yet verified on the new code for the user's actual content. Main phone = `REAL_SERIAL`.
2. Interactive editor pass in-hand: caption sizing, crop-preview consistency, frame-accurate scrub, reorder
   minimap nav, move-clip drawer, undo on captions/filters. (Most editor changes are compile+smoke-verified only.)

**B. HELD â€” need user's OK / higher risk (do NOT do blind):**
3. **Transcript dedup** â€” project.json â‰ˆ2.4 MB; clips carry triplicate transcripts (re-runs of "High accuracy").
   Deduping shrinks it (faster save/load, less memory). EDITS transcript data â†’ confirm with user; make it undoable.
4. **Crop-during-transition GL** â€” cropped clips briefly letterbox during the ~600 ms transition (the transition
   segment doesn't apply per-clip crop; GL effect renders the incoming clip uncropped). GL-shader work; verify via
   a transition-between-cropped-clips export. Cosmetic, transitions-only.
5. **Out-of-process export** â€” run `ExportService` in its own `android:process` so a huge project can't OOM the
   editor. Note: breaks the per-process `LocalBroadcastManager` progress delivery â†’ must switch progress transport.

**C. POLISH / lower priority:**
6. Export quality control â€” output â‰ˆ9 Mbps for 1080Ã—1920; consider a quality setting or higher default bitrate
   (`DefaultEncoderFactory.Builder().setRequestedVideoEncoderSettings(...)`; guard with `setEnableFallback`).
7. Re-verify two OLD known issues (from `tasks/DIAG_20260626.md`): audio-track caption FOLLOW inside a loop-region;
   audio-tap timeline jump / transient unmute. May already be resolved â€” confirm before touching.
8. Thumbnail-cache memory cap in `EditorTimelineView` for very large projects (LRU) â€” minor.

**Environment / how to work:**
- User runs a watcher (`watch-build.ps1`) that auto-builds + installs on save â†’ `build.log` (UTF-16:
  `tr -d '\000' < build.log | tail -40`). **Do NOT run gradle.** Wait for `BUILD SUCCESSFUL`. Always-green: revert
  rather than break. `installDefaultDebug FAILED ... No connected devices` = fine, only compile matters.
- Devices: main `REAL_SERIAL` (SM-N986U, has real project 27221664); backup Note 9 `SANDBOX_SERIAL`
  (SANDBOX project `bdd51919â€¦` â€” currently left in a modified test state: 9:16 canvas + tweaked in-points, harmless);
  new S10e `R58M34STHCA` (fresh install). adb: `C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe`
  via PowerShell (`-s <serial>`). Screenshots: `screencap` + `adb pull` (PowerShell `>` corrupts PNGs). UI nav is
  flaky â€” use `uiautomator dump` to get real button bounds.
- Undo system: `EditActions.*` + generic `EditActions.LambdaAction(desc, redoRunnable, undoRunnable)` (data-only
  closures); UI refresh handled by `FaditorEditorActivity.refreshEditorAfterUndoRedo()`. `maxHistory=50`, snapshot
  capture throttled to â‰¥1500 ms. Project saves are async (`ProjectStorage.saveAsync`), sync-flushed on pause/exit.
- Full history: DIAG Rounds 1â€“8 in `tasks/DIAG_20260627_perf_stability.md`; export basics in `tasks/DIAG_20260626.md`.

> **2026-07-02 â€” GAPLESS ENGINE LANDED, DEVICE-PROVEN (M-COMP-0; closes feedback #9 the transition jump).**
> `compositor/MasterPlaybackEngine` (multi-MediaItem + ClippingConfiguration over remuxed sources), delegated
> behind FaditorPlayerManager's API; flag `GAPLESS_ENGINE` (FaditorPlayerManager:50) DEFAULT ON, legacy = OFF;
> ineligible projects (loops/transitions/images) auto-use legacy. Measured on Note 9: 0 frozen frames at all
> seams vs 1.79s/3.64s legacy stalls, same project. Also 2026-07-02: caption hide-pill long-press now applies
> to ALL clips (b6c2a0c, user-requested hotfix, installed on S10e+Note 9); Layers M6 (multi-row timeline),
> M7 (row item move/trim/delete), M-COMP-1 (layer preview compositing) all landed; M5 REGRESSION GATE PASSED
> bit-identical on device. Full detail: tasks/REPORT_RELAY_20260702.md + PLAN_LAYERS_V2.md status ticks.

> **2026-07-02 â€” LAYERS M5 LANDED (compile-green; the schema-v8 keystone).** New `faditor/layers/` package
> (Track/TimedItem/TrackKind/BlendMode); Timeline gains rippleMode + Track views (synchronized-from-flat,
> ephemeral); dual-write keeps plain projects stamped v7/old-build-readable, `usesLayerFeatures()` flips to v8;
> downgrade guard added (newer-version projects load read-only, save refused â€” also fixed the deserializer's
> unconditional version double-stamp). ExportManager + ALL call sites untouched (regression gate holds by
> construction; on-device byte-identical proof still owed â€” no device tonight). Undo snapshots carry the v8 block.
> NEXT: M6 multi-row timeline (must give track flags a persistent home â€” see PLAN_LAYERS_V2 status note),
> then M7, M-COMP-1. M-COMP-0 deliberately deferred until a device can run the fMP4 probe. See REPORT_RELAY_20260702.md.

> **2026-07-01 â€” User-feedback batch 1 (BUILT GREEN).** (a) Export dialog now has an editable output-filename field (pre-filled default `Faditor_<timestamp>`, extension fixed): plumbed via new `ExportSettings.outputFileName` â†’ `ExportManager.generateOutputPath()` honors it on BOTH the SAF and direct-File paths; sanitized twice (dialog + manager); blank/untouched = byte-identical default behavior. (b) Transcript drawer header un-clipped (pure XML, `activity_faditor_editor.xml` ~632-711): title singleLine+ellipsize, icons 32â†’28dp, tighter paddings â€” drawer structure/transparency/resize/word-long-press untouched. NEEDS on-device verify: dialog styling + custom-name export in both storage modes; header on S10e-class screen. (c) "Webcam menu" vertical-rotation box FIXED: real culprit was `AnnotationService` compact bar (`compactBar` in `annotation_toolbar_unified.xml`) â€” ancestors (`menuContainer` 280dp / `toolbarView` 304dp) never resized on rotate; new `applyCompactContainerSizing()` sets WRAP_CONTENT in compact mode + `updateViewLayout` re-measure; restores fixed widths for full menu. (NOT MinimizableOverlayButton/FloatingWebcamService â€” inspected, no bug there.) (d) Export progress stripe: new `ExportProgressStripeView` (3dp, two flat greens #4CAF50/#388E3C, animated diagonal stripes, clipped to progress), overlaid at top of `activity_faditor_editor.xml`, driven by the EXISTING `ExportService.ExportServiceListener` (no new transport); hidden on complete/error/cancel; animator stops when hidden. âš ï¸ PRODUCT GAP: no in-app path back to the editor during export (fullscreen export screen blocks), so the stripe's "editing while exporting" case is unreachable until a minimize/continue-editing affordance is added â€” needs a safety check (is editing during a running export safe?) before building. On-device verify: compact-bar rotate slim+flush both orientations, drag still works; stripe fill/animation/hide. Working queue (easyâ†’hard) from user feedback, remaining: webcam-menu vertical box, export top-stripe progress, caption apply-to-all, undo history popup, small-screen scroll pass, transcribe-on-add + editor settings gear, tools drawer + carousel edit mode (data-driven, pin only in edit mode), transition preview smoothness (LAST, confirm-first).

> **2026-07-01 â€” Undo/redo long-press history popup (BUILT GREEN, device-verified).** Long-pressing `btn_undo`/`btn_redo` in `FaditorEditorActivity` (`showUndoRedoHistoryPopup()`, ~line 7613) now opens a programmatic dark-styled `PopupWindow`: redo entries on top numbered +N..+1, a "Current position" centerline, undo entries below numbered -1..-N, auto-scrolled to the centerline on open, dismiss-on-outside-tap. Tapping a row calls `jumpUndoRedoBy()` which repeats `undoManager.undo()`/`redo()` the needed number of times (data-only) then does exactly ONE `refreshEditorAfterUndoRedo()` + `scheduleAutoSave()`, mirroring `performUndo()`/`performRedo()`. Added `UndoManager.getRedoHistory()` (~line 384, mirrors existing `getUndoHistory()`) returning the redo stack nearest-first (index 0 = next redo). Files touched: `FaditorEditorActivity.java` (long-click wiring + popup/row builders), `undo/UndoManager.java` (new accessor only, no structural change). Device-verified on Note 9 sandbox project (bdd51919â€¦): did rotate x2 + flip, long-pressed undo â†’ popup showed correct -1..-6 entries; tapped "-2" â†’ jumped correctly (undo 50â†’48, redo 0â†’2, rotate/flip UI reverted to matching state, single refresh, no flicker); long-pressed redo â†’ popup showed +2/+1 above centerline correctly ordered; outside-tap dismissed popup without side effects. All spec points confirmed on-device.
>
> Also noting: caption apply-to-all (`applyCaptionStyleToAllClips`, built earlier today by a prior agent) is in and compile-green but NOT yet device-verified â€” prior agent died before documenting it.

> **2026-06-28 â€” Export crash-hardening (Round 8, ADDITIVE only).** Defensive guards in the export pipeline, no behavior change for valid inputs: `CompositeExportOverlay.getBitmap` now (a) null-guards the caption styleId before `.equals()` and (b) wraps each non-essential overlay draw section (text/caption/waveform) in `try/catch(Throwable)` with a once-log + `canvas.restoreToCount(...)` so one bad overlay can't abort the encoder thread; `ExportManager` null-guards `getSourceUri()` in `getSourceWidth/Height`/`extractStillFrameForLoop` and moved `getOrCreateSilenceFile`'s `FileOutputStream` to try-with-resources. Core media pipeline NOT wrapped (real failures still surface + get logged by `writeExportErrorLog`). Build green. See DIAG Round 8.

> **2026-06-28 â€” Trimmed raw-fMP4 export FIXED (additive; normal/imported path byte-for-byte unchanged).** `ExportManager.resolveSeekableSourceUri(Clip)` (pure cache lookup, never blocks) points the VIDEO branches of `buildClipItem`/`buildTransitionItem`/`buildLoopExtensionItem` at the cached remuxed seekable file when one exists; `ExportService` warms that cache off the main thread (`collectSourcesNeedingRemux()` + `remuxSync` on a single-thread executor, then posts `export()` back to main) ONLY when raw-fMP4 sources need it. Empty list = common case = export immediately as before. Build green. See DIAG Round 5.

## ðŸŽ‰ MILESTONE (2026-06-27): first video shipped end-to-end
Export is correct: native-portrait encoding (no rotation-flag), consistent caption/crop sizing (canvas-based),
frame-accurate scrub settle, fixed transitions (audio bleed + aspect), and the audio-caption drop fixed. Now in
**autonomous hardening** â€” see `tasks/DIAG_20260627_perf_stability.md` "Round 5". Done: durable export error
logging; activity-leak static audit (no permanent leak â€” it was GC starvation from the undo-snapshot bomb,
now fixed); undo coverage for filter/color (`EffectStackAction`), opacity keyframes
(`OpacityKeyframesAction`), caption-style change, and visualizer add/remove (via a new generic
`EditActions.LambdaAction`); `refreshEditorAfterUndoRedo` now syncs overlays + re-binds captions.
Also added (built green): undo for caption position (`applyCaptionPosition` + both overlay `onMoved`),
caption size (`applyCaptionSize`), caption hide via long-press, and text/image overlay ADD + DELETE
(text-ADD recorded on commit, guarded by `textOverlayAddRecorded` so placeholders don't record); SKIPPED
overlay MOVE/time-range/keyframe drags as ambiguous.
âœ… **Trimmed-fMP4 export fix DONE + DEVICE-VERIFIED** (2026-06-28): additive â€” `ExportManager.resolveSeekableSourceUri`
(resolve file:// fMP4 â†’ cached remuxed file at the clip-builder setUri sites) + `ExportService` warms the remux
cache off-thread before export; normal/imported projects unchanged. Verified: the trimmed raw-fMP4 9:16 export
that failed ("not seekable to start") now succeeds â†’ 1080Ã—1920 portrait, audio+captions intact. See DIAG Round 7.
STILL QUEUED (not done): crop-during-transition GL geometry (risky GL + hard to verify), out-of-process export
(risky), transcript dedup (data-touching, needs user confirm). All changes compile green + key ones device-verified.

### On-device verification (2026-06-28, backup Note 9 SM-N960U) â€” see DIAG "Round 6"
- âœ… Orientation fix CONFIRMED: 9:16 export = `1080x1920`, NO rotation flag (was 1546x870+rotate-90). Portrait
  encoding works on an older device â†’ broadly compatible.
- âœ… Export completes end-to-end on the Note 9 with all changes; editor opens 9:16 project without crash;
  captions render at correct canvas size; durable error log captured a real failure.
- âš ï¸ NEW latent bug (NOT a regression): exporting a TRIMMED raw fragmented-MP4 clip fails ("not seekable to
  start") â€” `buildClipItem` uses Media3 ClippingConfiguration which needs a seekable source. Proper fix
  requires resolving clips to remuxed/seekable files at export time, which means restructuring the export-start
  flow (buildComposition runs on the MAIN thread today, so blocking remuxSync there would ANR â†’ must build the
  composition off-thread then `transformer.start` on main). This touches the just-shipped critical export path,
  so it's HELD pending a greenlight + broad device verification. Narrow impact: normal imports are remuxed.

## â­ Most recent work (2026-06-28) â€” finished undo coverage (BUILT GREEN)
- Added undo for the last uncovered edits: **caption style-keyframes** (`CaptionStyleKeyframesAction`, Clip-only)
  and **text/image overlay move / time-range / keyframe drags** (`OverlayTransformAction` over a new
  `TextOverlayItem.TransformSnapshot`; one undo step per gesture, snapshot at drag-start via new
  `onOverlayManipulated` / `onOverlayDragStart` hooks). `refreshEditorAfterUndoRedo()` now also rebuilds the
  on-canvas overlay layer. Additive only, no-op guarded. See DIAG "Undo coverage". BUILD SUCCESSFUL.

## â­ Most recent work (2026-06-27) â€” color fix, ANR fixes, editor features (BUILT GREEN, pending on-device verify)

All built via the watcher (BUILD SUCCESSFUL, installed on REAL_SERIAL). Launch smoke-tested (no FATAL).
Interactive gestures (trim/reorder/move) NOT yet user-verified â€” do not drive them blind on the user's
real project (risks mutating their timeline).

1. **Export saturation (color) fix.** `EffectStack.toEffects` passed the raw fractional saturation delta
   (`saturation - 1`) to Media3 `HslAdjustment.adjustSaturation`, but that API expects a PERCENTAGE
   (`HslShaderProgram` divides by 100). So a 1.45Ã— saturation boost applied as 0.45%, i.e. invisible â€”
   export looked desaturated vs. the live preview (whose `ColorMatrix.setSaturation` uses the multiplier
   directly). Fix: `(saturation - 1f) * 100f`. Confirmed by bytecode-decompiling HslShaderProgram +
   measuring HSV saturation on the exported MP4 vs. source frames.

2. **ANR on insert.** `onVideoAssetPicked` ran the file copy + `getVideoDuration` (FFprobeKit, synchronous)
   on the main thread â†’ multi-second block on SAF URIs. Now off-loaded to `assetImportExecutor`, timeline
   mutation posted back to main, "Optimizingâ€¦" overlay shown during the wait.

3. **ANR on trim-drag / general jank.** `EditorTimelineView.onDraw` drew EVERY clip with no off-screen
   culling, and `computeRects` eagerly extracted thumbnails for ALL clips (unbounded bitmap retention).
   With many clips + continuous trim-drag invalidation this starved input dispatch â†’ "not responding."
   Fix: cull segments outside the viewport in onDraw; load thumbnails lazily for on-screen clips only
   (with a `thumbnailsFailed` guard against retry storms); reorder blocks lazily load too.

4. **Move-clip drawer reorder buttons.** New row in `move_drawer`: send-to-start (`first_page`),
   move-one-left (`arrow_back`), move-one-right (`arrow_forward`), send-to-end (`last_page`) â€” "all the
   way" buttons on the outside. Wired to `moveSelectedClipTo` / `moveSelectedClipBy` (reuses the
   `ReorderClipAction` undo path); buttons dim when not applicable.

5. **Reorder edge-scroll + minimap drag-to-jump.** The reorder block row now scrolls when it overflows
   (was centered + clipped â†’ end clips unreachable). Drag a block near a screen edge to auto-scroll
   (`edgeScrollRunnable` reorder branch), or drag it onto the reorder minimap to jump to that part of the
   project. New fields: `reorderScrollPx`, `reorderMaxScrollPx`, `reorderMinimapRect`. Viewport box drawn
   on the reorder minimap.

6. **Frame-accurate trim-edge preview.** While dragging a trim handle, a floating bubble shows the EXACT
   in/out frame (`OPTION_CLOSEST`, not keyframe-snapped) so you can see which side of a baked-in jump cut
   the cut lands on. Debounced + serialized on `trimPreviewExecutor` with a cached `MediaMetadataRetriever`
   so it never blocks input. The filmstrip strip itself stays keyframe-based (fast); only the edge frame
   is exact. See `requestTrimEdgePreview` / `extractExactFrame` / `drawTrimEdgePreview`.

---

## â­ Systemic perf/stability diagnosis (2026-06-27 PM) â€” see `tasks/DIAG_20260627_perf_stability.md`

Chronic ANRs (~20 today) + a failed export traced to ARCHITECTURE, not a single bug. Device meminfo:
Dalvik heap 172 MB, 1862 Views, 5 live Activities; project.json = 2.4 MB (7,110 transcript words, duplicate
transcripts). **Root cause: snapshot-based undo serialized the FULL 2.4 MB project to JSON on the UI thread on
every edit and kept up to 200 such snapshots in heap** (~480 MB) â†’ GC thrashing/OOM/ANR; project save was also
synchronous per edit. Export runs in-process â†’ OOM-killed mid-render ("got decently far then quit"; no exception
persisted). FIXED (built green): async disk writes (`ProjectStorage.saveAsync`/`saveUndoHistoryAsync` +
`flushPendingWrites`), `maxHistory 200â†’50`, throttled/conditional snapshot capture. STILL TODO: transcript
dedup (data-touching), activity-leak heap dump, durable export-error log + out-of-process export. Restart the
app to clear leaked memory before retesting.

## Earlier work (2026-06-26) â€” EXPORT IS NOW WORKING END-TO-END

Full diagnosis + evidence: **`tasks/DIAG_20260626.md`**. Device/loop how-to: **`tasks/DEVICE_CONTROL_RUNBOOK.md`**
(see new Â§7bâ€“7f: read live `project.json` via `run-as`, `ffprobe` source clips, capture export failures).

Verified on-device (real 16:46 export, `ffmpeg`-measured + frame-checked):
- **Export no longer crashes.** Root cause was `ExportManager.buildTransitionItem` building an *image*
  outgoing clip as a clipped progressive video â†’ `UnrecognizedInputFormatException`. Now branches on
  `isImageClip()` (setImageDurationMs). The whole timeline renders.
- **Clip volume was NEVER applied on export.** `buildClipItem` gated `audioProcessors.add(volumeProcessor)`
  on `volumeProcessor.isActive()`, which is always false at composition-build time (BaseAudioProcessor
  only goes active after the pipeline calls configure()). Now gated on a `volumeAdjusted` boolean. Audio
  clips also now apply their **volume keyframe envelope** (was static-only) in `buildAudioSequence`.
- **End-of-timeline audio went silent** when the gap before a later audio clip exceeded the 600s silence
  WAV. Gaps are now chunked into â‰¤`SILENCE_FILE_MS` silence items.
- **Captions on export** now: render in the correct coordinate space (overlay was authored at canvas dims
  but composited onto the source-res frame â†’ top-left/clipped; now scaled to the frame in
  `CompositeExportOverlay.getBitmap`), honor per-clip **caption-style keyframes incl. "hidden" windows**,
  and audio-clip captions use a sane default size (`AudioClip.captionSizeFraction` 0.12â†’0.060 to match
  `Clip`/`CaptionOverlayView`).

**Autonomous buildâ†’verify loop now works** (the agent could not before): the user runs a continuous-build
watcher (`watch-build.ps1` / `gradlew -t installDefaultDebug`, logs to `build.log` UTF-16). The agent
edits source, polls `build.log` for `BUILD SUCCESSFUL`, drives the export via `adb` (screencap + tap),
pulls the MP4, and verifies with `ffmpeg` (audio RMS curve) + frame extraction. Gradle CANNOT be run from
inside the agent sandbox (loopback blocked) â€” always go through the watcher.

**Still open** (see DIAG): looped-clip "faded" color (likely codec/color-range, low priority, shelved);
CROSS_DISSOLVE is mislabeled (maps to the "Dreamy" GL shader) â€” rename it and add a real cross-dissolve;
editor bugs: loop-region caption-follow while scrubbing + audio-tap timeline jump.

**Next major work:** Asset Browser (un-bust + finish) then Layers â€” execution plan in
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
â”œâ”€â”€ app/src/main/java/com/fadcam/
â”‚   â”œâ”€â”€ MainActivity.java                 # Launcher
â”‚   â””â”€â”€ ui/faditor/                       # Editor package
â”‚       â”œâ”€â”€ FaditorEditorActivity.java    # Main editor UI (12k+ lines)
â”‚       â”œâ”€â”€ export/                       # Export pipeline
â”‚       â”‚   â”œâ”€â”€ ExportManager.java        # Builds Media3 Composition
â”‚       â”‚   â”œâ”€â”€ CompositeExportOverlay.java # Captions/text/waveforms bitmap overlay
â”‚       â”‚   â”œâ”€â”€ CaptionExportRenderer.java  # Export caption bitmap renderer
â”‚       â”‚   â”œâ”€â”€ OpacityExportEffect.java
â”‚       â”‚   â””â”€â”€ OpacityExportShaderProgram.java
â”‚       â”œâ”€â”€ gltransitions/                # GL transition effects
â”‚       â”‚   â”œâ”€â”€ GlTransitionCatalog.java
â”‚       â”‚   â”œâ”€â”€ GlTransitionShaderLoader.java
â”‚       â”‚   â”œâ”€â”€ GlTransitionShaderProgram.java
â”‚       â”‚   â”œâ”€â”€ GlTransitionFrameOverlay.java
â”‚       â”‚   â””â”€â”€ GlTransitionExportEffect.java
â”‚       â”œâ”€â”€ model/                        # Clip, Timeline, etc.
â”‚       â”œâ”€â”€ timeline/                     # EditorTimelineView, segments, playhead
â”‚       â”œâ”€â”€ transcript/                   # Transcript, TranscriptWord, caption UI
â”‚       â”œâ”€â”€ waveform/                     # WaveformStyleRenderer, visualizer styles
â”‚       â””â”€â”€ project/                      # ProjectStorage (JSON persistence)
â”œâ”€â”€ app/src/main/assets/gl_transitions/   # GLSL transition shaders
â”œâ”€â”€ media3-patched/                       # Forked Media3 modules (common/container/muxer)
â”œâ”€â”€ tasks/                                # Agent plans, todo, lessons
â”‚   â”œâ”€â”€ todo.md
â”‚   â”œâ”€â”€ lessons.md
â”‚   â””â”€â”€ handoff.md                        # This file
â””â”€â”€ build.gradle / settings.gradle
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
â†’ OverlayEffect (BitmapOverlay with CompositeExportOverlay)
â†’ OpacityExportEffect
â†’ Presentation (canvas resize / letterbox)
```

Do **not** put opacity before overlay â€” the overlay will re-composite at full opacity.

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
| Overlays are bright while video fades to black | Effect order: opacity before overlay | Order: overlay â†’ opacity â†’ presentation |
| Effects missing on single-clip "original" canvas | `isSimpleTrim` took fast path and skipped effects | Harden `isSimpleTrim` to require no overlays/loops/opacity/etc |
| Captions out of sync after trim/speed | Using absolute timeline ms instead of clip-local source ms | Use `clipLocalMs * speed` for captions |
| Waveform stops moving inside loop | `setSourceMapping`/`setLoopExtension` not configured or wrapping math wrong | Configure per slot; wrap in output time then apply speed |
| GL transition progress wrong on later clips | Treating absolute `presentationTimeUs` as item-local | Subtract item timeline offset |

---

## 6. Current known limitations (as of last update)

1. **PING_PONG reverse is a mirror**, not true reverse. Audio and caption highlight are forward-only.
2. **Loop-extension opacity keyframes** use the extension item's own start offset, so a clip-wide opacity envelope doesn't align perfectly across before/after extensions.
>3. ~~**Struck transcript words** are still rendered as captions~~ â€” FIXED. `CaptionOverlayView` and `CaptionExportRenderer` now filter struck words out of each phrase before wrapping/drawing.
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

> **2026-07-01 â€” Data-driven bottom tools carousel + swipe-up drawer + edit/pin/recent (Stages 1â€“3, BUILT GREEN, device-verified on Note 9 sandbox bdd51919â€¦).**
>
> **Architecture.** The old ~25 hardcoded `tool_*` LinearLayout blocks in `activity_faditor_editor.xml` were replaced by a data-driven carousel. New package `com.fadcam.ui.faditor.tools`:
> - `FaditorTool` â€” immutable model: `id` (stable string key, e.g. `"mute"`), `viewId/iconViewId/labelViewId` (the SAME `R.id.tool_*` ids the old XML used â€” declared in `res/values/ids.xml` so they survive removal from layout), `label`, `icon` (materialicons ligature), `bindMode` (CLICK / TOUCH_VOLUME / TOUCH_OPACITY), `alwaysHidden` (trim + heal, kept GONE).
> - `FaditorToolRegistry.defaultTools(ctx)` â€” canonical ordered list (same leftâ†’right order as old XML). **To ADD a tool:** append one `add(...)` here with a NEW id + NEW `R.id.*` (declare cell/icon/label ids in `ids.xml`), then wire its click in `FaditorEditorActivity` onCreate near the other `findViewById(R.id.tool_*)...setOnClickListener` calls. New unknown ids auto-append at the end of any saved order (never vanish).
> - `FaditorToolsAdapter` â€” builds cells into `@id/faditor_tools_row` (a `SwipeUpHorizontalScrollView`). **NOT a RecyclerView / no recycling** â€” cells are stable views because `FaditorEditorActivity` keeps ~30 cached field refs + `findViewById(R.id.tool_*_icon/label)` that mutate icon/label/color per selection. `buildCell` reproduces the old 72dp cell exactly (28dp/22sp icon, 11sp label, `#888888`, borderless ripple). Also owns Stage-3 edit mode: wiggle (Â±2.5Â° infinite ValueAnimator), drag reorder (`moveTool`/`dragDelegate()` operating on stable views), long-press pin (amber `push_pin` marker child, GONE unless pinned), trailing **edit chip** (`appendEditChip`, tuneâ†’check/grayâ†’green "Done").
> - `SwipeUpHorizontalScrollView` â€” custom HorizontalScrollView. `onInterceptTouchEvent` detects a vertical swipe-up (fires `OnSwipeUpListener` â†’ drawer) because clickable cells otherwise eat the gesture; in edit mode it routes gestures to an `EditDragDelegate` for drag-reorder (lazily starts drag on the intercepting MOVE using the stored DOWN anchor).
> - `FaditorToolsDrawer` â€” Stage 2 all-tools grid overlay (4 cols, labels, dim scrim). Re-fires the real carousel cell via `getCellForId(id).performClick()` then dismisses, so every handler (incl. mute/opacity tap paths) runs unchanged. Mirrors live icon/label/color + current order; omits `alwaysHidden` / non-VISIBLE cells (context filter). Scrim-tap + Back dismiss (Back wired in the editor's OnBackPressedCallback); swipe-down works on the panel handle/title (the inner ScrollView eats swipe-down over the grid â€” acceptable, scrim/back cover it).
> - `FaditorToolPrefs` â€” persistence + ordering. Resolves: pinned first (pin order), then manual saved order OR recency-desc, with any canonical tool missing from saved structures appended in canonical order.
>
> **AUDIT findings (behavior preserved 1:1).** No per-clip-type show/hide of tools exists â€” `selectSegment` only updates per-tool icon/label/color state (mute %, opacity %, speed, rotateÂ°, flip, crop, canvas, split/heal) via cached field refs; the same tool set shows for video/audio/image. Two tools are NOT simple clicks: **`tool_mute` and `tool_opacity` have rich custom `OnTouchListener`s** (tap=open drawer, long-press=toggle keyframe mode, vertical drag=live adjust value) installed by the activity AFTER `buildToolsCarousel()`. Preserved by: (a) keeping stable views with the same ids so the activity's post-build listener installs land on the generated cells; (b) marking them TOUCH_VOLUME/TOUCH_OPACITY so the adapter does NOT install its recency touch-hook on them (would be clobbered anyway) â€” instead recency is recorded inside `showVolumeControl()`/`showOpacityControl()`. `tool_trim`/`tool_heal` stay permanently GONE. `toolTranscript.setAlpha` pulse and `tool_captions` (label had no id originally; harmless new id added) preserved.
>
> **Prefs keys/format** (`Constants` + `SharedPreferencesManager.sharedPreferences`, JSON strings in `app_prefs.xml`):
> - `pref_faditor_tool_order` â€” JSON array of tool ids (manual drag order). e.g. `["move","trim","speed",...]`.
> - `pref_faditor_tool_pins` â€” JSON array of pinned ids. e.g. `["move"]`.
> - `pref_faditor_tool_order_mode` â€” `"manual"` (default) or `"recent"`.
> - `pref_faditor_tool_recency` â€” JSON object `{toolId: epochMillis}`.
> Settings: new **"Tool order"** switch row in `FaditorSettingsBottomSheet` (OFF=manual, ON=recent); toggling calls `FaditorEditorActivity.onToolOrderModeChanged()` â†’ `adapter.reapplyOrder()` (live re-sort reusing stable views).
>
> **Verification (device, Note 9, project bdd51919â€¦).** Stage 1: carousel visually identical (screenshot compare); Speed tap opened slider; mute tap opened volume drawer (custom touch listener OK); scroll shows all tools; dynamic labels/colors intact (6.5x green, 151% red, Free/9:16 green). Stage 2: swipe-up opened grid; tapping Rotate applied 90Â° + dismissed; scrim-tap dismissed. Stage 3: edit chip at right end; tap â†’ "Done"/green + wiggle (uiautomator couldn't reach idle = animation running); drag moved Loop; long-press pinned Move to front (amber marker); **committed order + pin survived `am force-stop`+relaunch** (confirmed in `app_prefs.xml` and on screen); drawer shows same order; enabling Recent mode live-reordered (just-used Settings jumped to front after pinned Move).
>
> **Not done / caveats.** Manual drag in edit mode intercepts horizontal swipes as drags (so you can't scroll-without-reorder in edit mode â€” expected). Swipe-DOWN over the drawer grid doesn't dismiss (ScrollView consumes it); use scrim tap/Back. Drag reorder is a manual LinearLayout implementation (not literally RecyclerView ItemTouchHelper) to keep stable views for the activity's field refs. The activity's now-unused private `dp(int)` remains (harmless).

---

> **2026-07-01 â€” Tools carousel EDIT MODE v2 (tested-feedback rework, BUILT GREEN; device-verify PENDING â€” no device attached this session).**
> Reworks the v1 edit interaction per user-tested spec. Files: `tools/FaditorToolsAdapter.java` (major), `tools/SwipeUpHorizontalScrollView.java` (rewrite), `tools/FaditorToolPrefs.java` (divider model + migration), `FaditorSettingsBottomSheet.java` (removed "Tool order" row), `FaditorEditorActivity.java` (wiring + back-press), `activity_faditor_editor.xml` (wrapped tools scroll in `@id/faditor_tools_overlay` FrameLayout + `clipChildren=false` up to `controls_section`), `values/ids.xml` (+`faditor_tools_divider`/`_drop_line`/`_done`).
> **v2 interaction model:** (1) Labels never wrap â€” single-line + `TextViewCompat` autosize 7â€“11sp in a fixed 64dp label width inside the 72dp cell. (2) Edit-mode gestures: plain swipe/fling SCROLLS (never reorders); a LONG-PRESS (`ViewConfiguration.getLongPressTimeout` timer armed in the ScrollView; cancelled if the finger moves past slop first) picks a cell up â€” it LIFTS (translationY âˆ’22dp, scale 1.18, elevation) and follows the finger via translationX in content space; a GREEN vertical line (in the overlay frame) marks the insertion gap; near-edge (56dp) continuous auto-scroll via a repeating ValueAnimator. Release settles (snap translationXâ†’0 after the reorder, ease translationY/scale) and drops into the slot.
> **Divider semantics:** a divider `View` sits in the row (subtle `0x33FFFFFF` normally, green + wider in edit). LEFT of it = pinned home row in manual order (= `PREF_FADITOR_TOOL_PINS`, stored order); RIGHT = unpinned, auto-sorted by `PREF_FADITOR_TOOL_RECENCY` desc. Drag math is pure index-based (`commitDrop`): capture pinned count `D` at pickup, remove dragged â†’ `effD`, translate the visible drop-slot, `draggedPinned = insVis<=effD`, new left section = first `newD` ids of the final visible order; a drop into the right side stamps descending recency so the dropped Lâ†’R order sticks. â‰¥1-pinned ENFORCED (dragging the last pin right â†’ `newD<=0` â†’ reject + snap-back + divider width-flash). Drawer mirrors `adapter.getTools()` (pins then usage). 
> **Prefs migration (v1â†’v2):** `migrateIfNeeded` runs lazily on first `resolveOrder`/`dividerIndex`: existing `PREF_FADITOR_TOOL_PINS` become the left section verbatim; `PREF_FADITOR_TOOL_ORDER` + `PREF_FADITOR_TOOL_ORDER_MODE` keys are REMOVED and the mode is gone (`getManualOrder`/`isRecentMode`/`setOrderMode`/`MODE_*` deleted); if no pins ever existed, the first canonical VISIBLE tool (`speed`) is seeded so â‰¥1-pinned holds. Unknown/new ids land in the right (usage) section (never vanish).
> **Persistent Done:** built in the overlay FrameLayout pinned `END|CENTER_VERTICAL` (fixed to screen edge, overlays the scrolling row); shown only in edit mode; tap commits+exits, as does system back (`initBackHandler`, after the drawer check). The trailing Edit chip now only ENTERS edit mode (hidden while editing).
> **Build:** compile GREEN (`compileDefaultDebugJavaWithJavac` + `packageDefaultDebug` OK; only `installDefaultDebug` fails = no device). **NOT device-verified** â€” the Note 9 sandbox (`SANDBOX_SERIAL`) was offline and no emulator/AVD available all session; every spec point needs on-device confirmation (one-line labels incl. transitions/transcript; swipe-scrolls-vs-long-press-drags with before/after uiautomator order; lifted icon + green line mid-drag; cross-divider pin/unpin + last-pin snap-back; Done visible at edge after scroll; order/pins survive force-stop; drawer order). **Accepted gap:** in edit mode the `mute`/`opacity` cells still carry the activity's custom long-press OnTouchListener (keyframe toggle) alongside the new drag pickup â€” both could fire on a long-press of those two cells; not rewired (out of minimal-wiring scope, and the activity installs those listeners post-build). Unused `faditor_settings_tool_order_*` strings left in place (removing risks stale refs).

---

> **2026-07-01 â€” Preview transition-boundary stutter: diagnosis only, no mitigation shipped (investigate-first task).**
>
> Full report: `tasks/DIAG_20260701_transition_preview.md`.
>
> **Root cause (dominant).** `FaditorPlayerManager` wraps exactly one `ExoPlayer`
> with one `MediaItem` at a time â€” no playlist/`ConcatenatingMediaSource`. Every
> clip-to-clip boundary during playback (`FaditorEditorActivity.advanceToSegment`,
> ~line 7303) calls `playerManager.loadClip()` â†’ `setMediaItem()` + `prepare()`,
> a full cold re-prepare (extractor probe, codec configure, first-frame decode,
> seek-to-trimStart) fired exactly at the boundary with zero pre-buffering. This
> is the jump, and it happens at **every** boundary, transition or not.
>
> **Root cause (contributing, transition-specific).** `decodeTransitionFrame()`
> (line 7187) caches decoded overlay bitmaps under a key
> (`uriString+"@"+outW+"x"+outH`) that omits `sourceMs` â€” so the incoming clip's
> preview bitmap freezes on the first frame decoded and never updates as the
> transition progresses, for both scrub and live playback. However, `ffprobe`
> on a real FadCam-recorded sample showed ~1s keyframe spacing, and
> `getFrameAtTime` uses `OPTION_CLOSEST_SYNC` (snaps to nearest keyframe) â€”
> since transitions are capped at 100-2000ms, a "correct" cache key would often
> still land on the same keyframe anyway. The companion fix that would make it
> reliably visible (`OPTION_CLOSEST` for exact-frame decode) is itself
> higher-risk: main-thread exact-frame MediaMetadataRetriever decode on every
> 50ms playback tick, which could stutter *worse* than the frozen-frame bug.
>
> **Why no mitigation shipped.** The dominant cause has no low-risk additive fix
> under this task's constraints (no new player instances, no MediaItem-queue
> restructuring, no compositor) â€” that's exactly Phase 5.3 (`road_map.md` line
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
> intended boundary (no live view while scripting blind taps â†’ timing drift).
> Not pursued further since the root cause was already unambiguous from source
> and no mitigation was being shipped to verify.

