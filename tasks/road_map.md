# Joy Creator (formerly FadCam/Faditor) — Autonomous Roadmap

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
- Code built and installed; APK installs successfully on device SANDBOX_SERIAL
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
**Status:** ❌ NOT STARTED  |  **Depends on:** nothing
- Currently show category-proxy animations
- Build `GlTransitionShaderLoader`, `GlTransitionShaderProgram`, `GLTransitionCatalog`
- Render actual GLSL shaders in each card via shared GLSurfaceView or pre-baked sprites
- 26 shaders already in `assets/gl_transitions/`
- Java side NOT built at all
- **Files:** New files in `gltransitions/`, `TransitionPreviewCardView.java`

### 3.2 Transitions pull-down-for-more-rows gesture [Track F]
**Status:** ❌ NOT STARTED  |  **Depends on:** 3.1
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
**Status:** 🔶 Partial (colour override + sensitivity shipped)  |  **Depends on:** nothing
- Bar-width/gap sliders
- Template gallery
- SAF import/export of custom styles
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
| 3.1 GL real preview cards | P2 | ~2 sessions | Medium | — |
| 3.3 Visualizer tap-dismiss | P2 | ~0.5 session | Low | — |
| 3.4 Visualizer auto-snap | P2 | ~0.5 session | Low | — |
| 3.5 Captions Rolodex | P2 | ~1 session | Low | — |
| 4.1–4.5 Asset Browser v2 | P2 | ~2 sessions | Medium | — |
| 5.1–5.5 Layers | P0 (keystone) | 3-5 sessions | **High** | Phase 0-1 |
| 6.1 AI-Generated slides | P2 | 2-4 sessions | High | — |
| 6.3 Visualizer Studio full | P2 | ~1 session | Low | — |
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
