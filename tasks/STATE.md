# STATE — what Joy Creator actually has, and how proven it is

**Purpose:** stop the rediscovery tax. This is the one file that says what exists.
Every agent reads it first. Every promotional claim comes from it. The help doc and
Joybot's knowledge are generated from it.

**Joy Brush owner check, 2026-10-01:** JoyRaptor reports drawing, colour picking, erasing,
closing and reopening work on the Note. Treat these ordinary workflows as device-verified.
Recent drawings, preservation-before-Open, backup fallback and PNG export have
separate proof scopes; the later October1 recovery/device notes at the end of this
file supersede the initial compile-only snapshot. Do not infer PNG export proof
from a recovery check. Details:
`tasks/joybrush/INTEGRATION_20260930.md`.

**Last full pass:** 2026-09-09 (historical; not a current whole-product audit).
**Latest scoped refresh:** 2026-10-26 — JoyBrush feature families, cross-wing reuse map and mandatory update policy.
**HYG-5 pass (partial), 2026-10-27:** new section 7.6 (the app screens restyled 10-16 to 10-20, which had no rows) and section 9 rows 8, 10, 11 and 12 corrected against the current code. Still to do (job card HYG-5): re-check every 🟡 and 🔴 row, the inherited "~" rows in sections 1 to 4, and ask the JoyBrush lead for its layer-operation rows.
**Historical update:** 2026-09-16 evening — the puppet UI finish sweep (`tasks/PUPPET_FINISH_LEDGER.md`):
nine dead knobs found and resolved, the tape made draggable, easing fitted from the recording, and
three defects JoyRaptor reported on device. Also `tools/phone.sh deploy` / `doctor`, which exist
because a stale watcher or a dropped transport had been leaving OLD builds on the phone.
**Historical September scale (not recounted):** 710 Java files, ~349,000 lines, schema v13

---

## The proof levels — this is the point of the document

| | Means | Consequence |
|---|---|---|
| 🟢 **LIVE** | JoyRaptor has used it on a phone and it worked | Safe to ship. Safe to promote. Goes in the help doc. |
| 🟡 **BUILT** | Code complete, compiles, never driven on a device | **This is where 1-star reviews come from.** Do not promote. Consider hiding at launch. |
| 🔵 **SPECCED** | Written down, not built | Roadmap fuel |
| ⚪ **IDEA** | Mentioned somewhere, never specced | INBOX.md fuel |
| 🔴 **BROKEN** | Known defect, diagnosed | Must be triaged before launch |
| 🟠 **ORPHAN** | Started, stopped for lack of hours, still wanted | **Recover it. Do not archive it.** |

**The rule that keeps this true: an entry moves to 🟢 when it is proved on a phone, not
when the code lands.** That single discipline is what this file is for.

**"Is it finished" and "is it proven" are different questions.** Do not conflate them.
JoyRaptor, 2026-09-09: *"almost anything partially built is likely part of the finished vision
that I haven't had time to get around to yet."* He works in fragments around childcare, so
work stops for lack of hours, not lack of intent. A 🟠 entry is an orphan waiting to be
finished — never dead weight to be filed away. A whole feature (masking) was already lost
this way once. Push back on an idea only with a substantive reason it is wrong, never
because it is incomplete.

**Provenance:** entries marked ✔ were verified against the code in this session. Entries
marked ~ are inherited from LEDGER.md / road_map.md claims and are only as good as those.
Anything unmarked is a reading that should be challenged.

---


## Shared feature map — read before building or writing help

STATE is the required cross-wing catalogue of major user-visible capabilities.
Marketing uses the proof level, Joybot maps help to stable feature IDs, and builders
inspect reusable source before starting another implementation. A source link is
not a help article or a guarantee that a component can be embedded unchanged.

| Feature ID / user capability | Where / availability | Reuse and help mapping |
|---|---|---|
| `studio.text-animation` — animated titles, words and timers | Studio text overlays; existing inherited LIVE claim below, not newly retested | [Animated-text contract](SPEC_TEXT_ANIMATION.md); shared [CaptionAnimator](../app/src/main/java/com/fadcam/ui/faditor/transcript/CaptionAnimator.java), [TextOverlayItem](../app/src/main/java/com/fadcam/ui/faditor/model/TextOverlayItem.java), [picker](../app/src/main/java/com/fadcam/ui/faditor/TextAnimPickerPopover.java), [export](../app/src/main/java/com/fadcam/ui/faditor/export/CompositeExportOverlay.java). Reuse media-time evaluation, persistence and preview/export conventions; do not build a second animation clock. User-help mapping pending. |
| `studio.caption-animation` — animated transcript phrases | Studio captions; see transcription/text entries for exact proof | Same CaptionAnimator and phrase grouping in `ui/faditor/transcript`; text and captions share machinery. Check source-time vs timeline-time adapter before reuse. Help mapping pending. |
| `shared.transforms` — move/resize/rotate/pivot, mesh and corner-pin families | Studio surfaces; individual media types have different proof/limits below | `ui/faditor/transform`, SpriteMeshDraw and export paths; see editor/Bend entries. Reuse adapters and shared geometry, not assumptions that every media type is already supported. Help mapping pending. |
| `vislab.studio-bridge` — music visuals in Studio and reusable look elements | VisLab room / Studio visualiser / floating visualiser; individual levels in section5.5 | `vislab/studio`, `vislab/gl`, Studio export/transform adapters and Sprite Lab renderer. Reuse existing text/font/sprite machinery across wings; new combinations require their own proof. Help mapping pending. |
| `joybrush.editable-lines` — select, edit, Copy/Paste and Nudge drawings | JoyBrush Select; agent-device-verified bounded baseline on integration branch, owner acceptance separate | [Current roadmap](joybrush/ROADMAP.md), [source/evidence](joybrush/HANDOFF_20261026_COORDINATOR.md); `joybrush-android/.../select` hosts and `joybrush/androidkit` line engine. Pixel PhaseB and outside-selection fade remain incomplete. Help mapping pending. |
| `joybrush.boards` — create, size, move and lock Image, Animation and Sprite regions | JoyBrush menu → Boards; integrated source on `lead/select-reconcile-20261024`; historical scoped October6/7 phone workflows, current full acceptance pending | [Owner contract](joybrush/specs/JB-3.00a_boards_owner_model.md), [BoardRuntimeController](../joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/board/BoardRuntimeController.kt), [BoardDocumentOps](../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/BoardDocumentOps.kt). Current mixed-content, mask, geometry and exact visual gates remain open. Help `joybrush.boards.create-size-lock`: PENDING. |
| `joybrush.board-preview` — scope layer previews, held layers and onion skin to a board | Select a board; onion peg and layer held marker; integrated source on `lead/select-reconcile-20261024`; historical scoped phone checks, current G7a frame/cache/visual acceptance pending | [BoardRuntimeController](../joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/board/BoardRuntimeController.kt), [LayerColumnView](../joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/chrome/LayerColumnView.kt), [BoardSession](../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/BoardSession.kt). Reuse board ownership and existing preview adapters; no new compositor claimed. Help `joybrush.boards.frames-holds-onion`: PENDING. |
| `joybrush.tile-pattern` — preview a repeating Image pattern and wrap supported strokes into it | Image feature switch; integrated source on `lead/select-reconcile-20261024`; historical scoped Tile phone checks, current acceptance pending | [BoardRuntimeController](../joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/board/BoardRuntimeController.kt), [BoardSession](../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/BoardSession.kt). Existing unsupported-tool guards remain; realistic pencil, watercolor and oil wrapping is refused. Help `joybrush.boards.pattern-limits`: PENDING. |
| `joybrush.sprite-roll` — size Sprite cells, preview their order and separately arm swaps | Selected Sprite; finger roll after lock, separate swap arm, stylus paints; integrated source on `lead/select-reconcile-20261024`; historical scoped two-layer swap/Undo and sizing checks, current media/mask acceptance pending | [SpriteGridMath](../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/sprite/SpriteGridMath.kt), [CellRoll](../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/sprite/CellRoll.kt), [SpriteCellOps](../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/SpriteCellOps.kt). Do not infer realistic-media or complete mask/INK behavior from the bounded phone check. Help `joybrush.boards.sprite-order-swap`: PENDING. |
| `joybrush.board-export` — export board/range/frame images, animation, GIF or sheet; open staged output in Studio/SpriteLab | Board export icon and internal handoff; integrated source on `lead/select-reconcile-20261024`; historical scoped artifact/import/reopen/video checks, current artifact/refusal/picker acceptance pending | [BoardExportCoordinator](../joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/board/BoardExportCoordinator.kt), [BoardExport](../joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/BoardExport.kt), [AnimExportPlan](../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/export/AnimExportPlan.kt), [internal receiver](../app/src/main/java/com/fadcam/ui/faditor/sprite/JoyBrushBoardImportActivity.java). Internal staging is not arbitrary external import; live-source and tween export gates are separate. Help `joybrush.boards.export-studio-spritelab`: PENDING. |
| `joybrush.paper-surfaces` — choose the background and custom color independently from the physical tooth under the brush | Layer-column Paper circle opens the sheet; integrated source on `lead/select-reconcile-20261024` includes 29 surfaces and 32 looks. Historical scoped core/GPU and controlled rollback evidence only; full current device acceptance remains pending. | Reuse [PaperState](../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/paper/PaperState.kt), [SurfaceMaps](../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/paper/SurfaceMaps.kt), [PaperRaster](../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/paper/PaperRaster.kt), [PaperPreviews](../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/paper/PaperPreviews.kt), [PaperResources](../joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/PaperResources.kt), [PaperSheetView](../joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/chrome/PaperSheetView.kt) and [RegionRenderer](../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/render/RegionRenderer.kt). Distinct from watercolor wash texture. Help sections—Paper background vs surface; Colour vs Tint; Show vs Bite; None vs Include; paper vs watercolor wash texture—are PENDING. |
| `joybrush.brush-mode` — choose editable lines per brush | Hold brush settings / drawer badge; integration built and automated checks passed, A10 functional agent-phone check passed; registered-image visual review passed within scope | BrushLinePreferences, BrushModeBadge and settings Switch. Pixel-only brushes remain pixels; do not present this as accepted on all devices. Help mapping pending. |
| `joybrush.watercolor-lines` — pressure/tilt-driven editable wash recipes | JoyBrush Line watercolour / preset drawer, hold for brush mode, Select for edits; integration foundation, current ordinary/appearance/save/recovery acceptance pending | Reuse [dab math](../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/brush/WatercolourDabber.kt), [frozen wash coordinates](../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/stroke/StrokeWashFrame.kt) and [line transform convention](../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/select/LineTransform.kt). Distinct from raster water simulation; no ready Studio bridge claimed. Help mappings Watercolor lines / Brush makes lines / Paper versus wash texture pending. |
| `joybrush.frame-tweens` — frame in-betweens and animation | Foundation exists; live duplication/playback/export integration unfinished | `joybrush/core/anim`, PlaybackClock and isolated tween contracts; see roadmap. Do not advertise complete editable-line frame duplication or tween playback yet. Help mapping pending. |

When a major feature is built, update its main section and this map if it introduces
another reusable family. State where users find it, which branch/build exposes it,
what is proved, what is missing, reusable engine/source links, and the help mapping
(or explicitly pending). Keep minor fixes/test counts in handoffs. Never infer LIVE
from compilation, an isolated branch, synthetic checks or agent-only device testing.

---
## 1. RECORDING (the FadCam inheritance)

| Feature | Level | Notes |
|---|---|---|
| Background video recording (screen off) | 🟢 ~ | The original FadCam core |
| Screen recording (FadRec) | 🟢 ~ | fadrec/ — own encoding pipeline |
| **Webcam overlay while screen recording** | 🟢 ~ | ✔ FloatingWebcamService — **Joy Creator exclusive, FadCam has no such thing.** Headline feature. |
| Auto-hiding recorder controls | 🟢 ~ | JoyRaptor's work. Less visually invasive than FadCam. |
| Dual camera (front + back) | 🟡 ✔ | dualcam/ full package. Device state unknown. |
| Photo capture / screenshot | 🟢 ~ | PhotoCaptureActivity, ScreenShotCaptureActivity |
| Remote camera control over LAN | 🟡 ✔ | streaming/RemoteStreamManager, LiveM3U8Server. **Real, unpromoted, untested by JoyRaptor.** |
| Remote (LAN streaming: watch and control the phone camera from a browser on the same Wi-Fi) | 🟡 ✔ | **10-28: FadCam's cloud, E2E, Pro, Ko-fi and Discord parts removed** (S19, 63bcd54cf and the three before it; they talked to the FadCam developer's own servers). Agent-checked on the Note 9 10-28: Remote starts, the LAN page loads (`/`, `/status`), `/live.m3u8` streams 2 s segments, no cloud control left. To be folded in as Remote Studio (FOLD-1). Note: the web page loads hls.js, flatpickr and Font Awesome from CDNs, so a viewer PC with no internet gets a broken page. |
| Watermark | 🟢 ~ | watermark/WatermarkManager **10-28: off by default** (3cbc64ff6); it used to burn "Captured by" + the FadCam logo into every recording, screen recording and photo. People who chose one keep it. |
| Live audio visualizer during record | 🟡 ✔ | visualizer/LiveVisualizer |
| Forensics gallery | 🟡 ✔ | forensics/ — full package, purpose and state unclear, needs a decision |
| Motion detection | 🟡 ✔ | motion/ package |
| Clock widget | 🟢 ~ | widgets/ |
| MiniApps (QR scanner etc.) | 🟡 ~ | Recorder gadgets. Different product surface — do not overload. |

### 🚨 Recording features that must leave the Play build

| Feature | Why |
|---|---|
| 30 disguise icons (Calculator, Notes, Weather, `$ ~/r00t`…) | ✔ Stalkerware signature on a screen recorder |
| Accessibility service used for screenshots | ✔ Non-accessibility use of the Accessibility API = policy violation |
| MANAGE_EXTERNAL_STORAGE | ✔ Requires justification; usually refused for editors |
| Privacy black screen | Per LAUNCH_STRATEGY.md section 3 |
| Cloud / streaming | Cloud removed 10-28 (S19); LAN Remote stays (owner 10-17) |

All of these **stay in the website / F-Droid build.** Decision already made and documented.

---

## 2. THE EDITOR — core timeline

| Feature | Level | Notes |
|---|---|---|
| Multi-clip timeline, split/trim/reorder | 🟢 ~ | The spine |
| Multi-track layers | 🟢 ~ | road_map "Phase 5 KEYSTONE" — reported complete |
| Keyframes + easing | 🟢 ~ | keyframe/, EasePickerPopover |
| Masks | 🟢 ~ | Schema v13. **The engine shipped before the UI did — that gap is why LEDGER.md exists.** |
| Chroma key / green screen | 🟢 ~ | LEDGER 3a-KEY: preview and export both proved on the Note 9, 2026-08-05 |
| Luma key | 🔵 ~ | LEDGER: "spec complete, the work is not" |
| GL transitions (35+) | 🟢 ~ | gltransitions/ |
| **External .glsl transitions from a dropped-in folder** | 🟢 ✔ | GlExternalTransitions.scanAndRegister — **this is already a working pack loader.** |
| Speed / zoom / crop / flip | 🟢 ~ | |
| Text overlays + animation | 🟢 ~ | SPEC_TEXT_ANIMATION; odometer preset proved character-by-character 2026-07-31 |
| Adjustment layers / FX | 🟡 ✔ | Shell is 🟢 — add/select/own-lane/fx+trash badges device-proved on the sandbox phone 2026-09-10. **Whether an effect applies to the layers beneath: agent-proved 10-27** (Note 9): the new VisLab effects (see section 5.5) render on an adjustment layer in the preview; an effect layer's Flash washes the clip beneath it in the preview (visualisers, pictures, text and sprites sit above effect layers in both preview and export); Echo Trails and a music-linked Flash show in exported videos. The older built-in effects were not re-checked in that pass. Fixed 10-27: an export froze a layer's keyframed or music-linked values at the first frame. Owner has not checked. SPEC_ADJUSTMENT_LAYERS_FX.md |
| Timeline lane allocation (no two objects stacked) | 🟢 ✔ | SPEC W — three adjustment layers took three lanes, device-proved 2026-09-10. Same rule now guards text/image/sprite/PiP adds. |
| Mesh warp / Bend (IMAGES) | 🟢 | **Complete and device-verified 2026-09-10.** SPEC A through T. |
| Bend — SPRITES | 🟢 | SPEC Z slice 1. Rasterise-then-warp on a Canvas, one method shared by preview and export. |
| Bend — TEXT | 🟡 | 2026-09-16. Through the SAME SpriteMeshDraw, now generalised off SpriteOverlayItem, called by CornerPinTextView and CompositeExportOverlay. The pin sits INSIDE the bend on both. A bent box is a raster (the pin stays vector-sharp; a mesh cannot), and the in-canvas editor suppresses it. **Built, never bent by hand.** |
| Bend — VIDEO (PiP + spine) | 🔵 | SPEC ZD. The model has shipped since SPEC ZB and nothing reads it. 2026-09-16 established the route: bend the GEOMETRY, not the sampler — the preview binds GL_TEXTURE_EXTERNAL_OES and the export a GL_TEXTURE_2D, so a shared mesh SHADER cannot serve both, but a displaced vertex grid needs no shader change on either. Recorded in _HELD_BY_OPUS_20260913. |
| Corner-pinned TEXT, with effects | 🟡 | 2026-09-16: a pinned box with ANY effect on it exported UNPINNED while the preview showed it pinned — TextFxGlEffect rebuilt a per-frame item and the pin was never among the copied properties. Now concat-ed from the same cornerPinMatrix the preview and the plain export use. Not yet re-exported on a phone. |
| Corner pin on SPRITES | 🟡 ✔ | SPEC Z slice 1, 2026-09-13. Model, persistence, undo, transform surface, authoring and BOTH renderers — one shared matrix method. Compile- and harness-verified; **no one has dragged a sprite corner on a phone yet.** 10-28: Undo of a sprite's bend or corner pin now restores it (fabedb713; before, the warp stayed and the sprite stayed soft); bend net, bend, undo and redo agent-checked on the Note 9. |
| Mesh bend on sprites / PiP / text / spine | 🔵 | SPEC Z. Sprites hold a MeshWarpSpec that nothing draws; PiP and spine need the Clip model work; text needs a pinned view. |
| Sprites on the transform surface | 🟡 ✔ | SPEC Z slice 1 step 2, 2026-09-13. They used the legacy handle overlay until now. |
| Transform (affine handles, rotation dial, pivot) | 🟢 | **Complete 2026-09-10.** Shipped alongside Bend. |
| Undo (one press = one step) | 🟢 ~ | JoyRaptor's ruling. Batch operations collapse to one undo. |
| Undo history after closing and reopening a project | 🟡 ✔ | 2026-10-22 to 10-24, agent-checked on the Note 9, not yet used by JoyRaptor. Phone-proved: steps made before an export now survive leaving (2301d5cfa: 41 recorded, 41 back, was 38), two quick edits no longer drag the next edit into one press (2301d5cfa), and undoing everything then leaving clears the saved history (58aaacd03). **Phone test owed** for b2a85dff7 (type-checked and harness-tested only; the C: drive was full on 10-24): every quick edit keeps its own step on a typical project, and the history is saved with every project save, so a crash leaves it matching the project. Test project on the Note 9: "Redo test clip". **10-28 re-check (agent, Note 9):** three rotations each undid one step after reopening; after two edits, a 5 s wait and a force-stop the history matched on relaunch. |
| Export (out-of-process) | 🟢 ~ | Own process so an editor crash cannot kill an export. Proved 2026-07-30. |
| Single-frame export (JPG/PNG) | 🟡 ✔ | Export sheet, "single frame": agent-checked on the Note 9 10-28: a real 1080x1920 PNG and JFIF JPEG land in the Joy Creator folder under the project's name. Not yet tried by JoyRaptor. |
| Timeline bookmarks and regions (`studio.timeline-marks`) | 🟡 ✔ | Landed 10-28 (b3c2b23b0; built by Codex 10-23/24, rescued). Tap the playhead time to drop a bookmark; hold it for **Marks & regions**: rename, jump previous/next, Set In/Out to make a region, loop a region while playing, export just the regions (video or audio-only, named "<project> - Region 1"). Undo/redo, and kept after closing the project and after a force-stop. Agent-checked on the Note 9 10-28 (loop held inside a 5.4 s region for 11 s; region export 5397 ms). Not yet tried by JoyRaptor. Joybot's `add_marker` can now be switched on (Joybot lane). Spec `tasks/SPEC_TIMELINE_MARKS_20261023.md`. Help `studio.timeline-marks`: PENDING. |
| Export: the file name you type is the file's name | 🟡 ✔ | Fixed 10-28 with the marks landing: the name now travels to the export process (it was dropped and the file saved as the project's name, "Untitled 2.mp4"). Agent-checked on the Note 9 10-28 (`qa-name-1028.mp4`). |
| Hold a selected picture or text to open its drawer | 🟡 ✔ | 10-28 (3f8aa55ed): before, only a visualiser's box answered the hold. Agent-checked on the Note 9 (picture opens Image/Transform; text opens its editor). Double-tap on a selected picture was not confirmed by adb taps. |
| Undo/redo put the preview back on the right clip, and the playhead inside the project | 🟡 ✔ | 10-28 (77d27970c, 1a7a8637e): a removed slide no longer stays on screen; a redo that shortens the project brings the playhead back inside it. Agent-checked on the Note 9; JoyRaptor's exact case (Joybot title slide) needs an AI key and is not re-run. |

---

## 3. AUDIO

| Feature | Level | Notes |
|---|---|---|
| Waveforms (tile cache + mip pyramid) | 🟢 ~ | Device-proven on SM-N986U |
| Beat and onset detection | 🟢 ✔ | waveform/BeatDetector, OnsetDetector — pure DSP, offline, **already wired to snapping** |
| Silence detection | 🟢 ✔ | SilenceDetector — pure DSP |
| Loudness normalisation (EBU R128) | 🟢 ~ | |
| Fix audio (highpass → denoise → compress → loudnorm) | 🟡 ✔ | **Corrected 10-28:** this row said 🟢 "works", but on the Note 9 every bake failed ("Invalid argument"): the baked file was named after the source's whole URI, and its .tmp name hid the format from ffmpeg. Fixed (c3f8ddffe, d0795437b); agent-checked 10-28 on a 1:25 song from Downloads: Bake OK (2.1 MB), plays, Undo returns the original, a second Fix is a cache hit. It IS a button (the audio clip's drawer, "Fix audio"). Open: after a cache hit the clip's waveform keeps the unfixed look; the Clean (remove silence) button acts on the video clip when it stays in the selection. Not yet tried by JoyRaptor. |
| Voiceover recording | 🟢 ~ | audio/VoiceoverRecorder |
| Audio ducking | 🟡 | **Rebuilt** (2026-10-15 code read): lane menu "Duck under…" → Ducker + DuckApplier write ordinary volume points that export reads. Joybot's `set_ducking` mirrors it (needs the voice clip's transcript). The old set_clip_duck stays removed. Not seen on a phone by this lane. |
| Visualizer studio | 🟢 | Superseded by VisLab (section 5.5): music visuals in their own room, inside Studio and floating over other apps. |

---

## 4. TRANSCRIPTION — fully offline, no key, no upload

| Feature | Level | Notes |
|---|---|---|
| Vosk small (~40MB) — best timing | 🟢 ~ | ✔ engine present |
| Vosk large (~128MB) | 🟢 ~ | |
| whisper.cpp base.en (~57MB) — best wording | 🟢 ~ | ✔ native build, arm64 only |
| LCS merge of Vosk timing + Whisper words | 🟢 ✔ | TranscriptSynthesizer — genuinely novel |
| Filler-word detection and strike | 🟢 ✔ | |
| Word-level retiming and scrubbing | 🟢 ~ | WordScrubView |
| Captions with 5 animated styles | 🟢 ~ | pop / zoom / bounce / boxed / hot |

**This is the strongest competitive asset in the app and it needs no internet, no key, and
no money.** Nothing else on Android does the two-engine merge.

---

## 5. ANIMATION — sprites, avatars, puppets

| Feature | Level | Notes |
|---|---|---|
| Sprite sheets + grid detection | 🟢 ~ | sprite/; road_map says S1–S7 complete |
| Sprite sidecar .sprite.json | 🟢 ✔ | Export and import both exist in SpriteSheetEditorActivity. The importer dropped every preset and cell name until 2026-09-10; fixed then, **device-proved 2026-09-13** — the Export panel now prints the JSON on screen with a Copy button, so what the assistant will read is visible before it is written. |
| Saved animations — the ENGINE | 🟢 ✔ | SpriteFrameResolver expands a preset key at the playhead and runs it, wrapping per loop/pingpong/once-then-hold **until the next key**, honouring weights, with `endBehavior` overriding after the last key. One evaluator for preview, export and tape. |
| Saved animations — picking one | 🟢 ✔ | **Two screens list `sheet.getPresets()` now.** The drawer shows them as chips alongside the still cells (animations first, one chip design, a mode dot the only difference); the Lab's Clips section is a shelf of the same chip, and picking one lights its frames on the sheet **in its own order** before you decide to load it. Device-proved 2026-09-13. |
| Cell names | 🟢 ✔ | The grid letters each cell in four corners — index, name, viseme, order badge — and the film and drawer chips carry the name. **Two stores held a cell's name and nothing reconciled them**, so a sheet with named cells exported "0 named" to the assistant; `cellNames` is now the one truth, reading falls back to `Cell.name`, writing updates both, loading migrates. Device-proved 2026-09-13. `Cell.tags[]` is still written by nothing. |
| Live keyframing while playing | 🟢 ✔ | `onCellChipTapped` keys **at the playhead** by design, so tapping during playback records a performance. Position/scale/rotation arm the same way (`faditor_kf_hint_armed`). Sprites carry a `KeyframeSet` *and* a `FrameTrack` — two independent tracks, which is what makes the two-pass workflow work. **JoyRaptor's favourite thing in the app. Do not add an arming step.** |
| Bend / squash-and-stretch on sprites | 🔵 ✔ | Not built. `CornerPinTransformHost` is the only host with a mesh path and it is image-only; `SpriteOverlayItem` has no transform host at all. |
| Dope sheet | 🟢 ~ | |
| Avatar rigs (biped, head yaw/pitch, limbs) | 🟡 ~ | road_map claims the full loop is done; **JoyRaptor has not driven it** |
| MediaPipe face tracking | 🟡 ✔ | face_landmarker.task in assets, MediaPipeTrackingSource |
| Visemes from audio (spectral, 6-class) | 🟡 ✔ | SpectralVisemeAnalyzer, AudioLevelViseme |
| IK solver, dangle physics, motion smoothing | 🟡 ✔ | FabrikSolver, DangleSim, OneEuroFilter |
| Bake rig to keyframes | 🟢 ~ | LEDGER: device-verified |
| Point-at-video | 🟢 ~ | |
| Puppet architecture | 🟡 ✔ | SPEC_20260904_PUPPET_ARCHITECTURE — engine complete through stage 12. 15 harness suites, ~490 assertions. |
| **Puppeteering — a PNG bends** | 🟢 | Trace → triangulate → weights → MLS solve. JoyRaptor confirmed a rigged dinosaur bending on 2026-09-15, including limbs drawn as detached islands and the overlap halo fixed. Mesh smoothness confirmed 2026-09-16. |
| **Mesh quality — no more shattered glass** | 🟢 | 2026-09-16. Measured 81–91% of triangles under 20° (worst 0.1°), and it got WORSE as Mesh detail went up. Delaunay edge flipping + long boundary edges split + Lloyd relaxation took it to 10–16%, worst 7.3°, and reversed the trend so more detail now means fewer slivers. JoyRaptor: *"mesh much smoother!"* **Heat/biharmonic weights would NOT have fixed this** — the faceting was tessellation, not weighting; that reasoning is recorded in PuppetWeights. |
| **Enclosed holes** | 🟡 | 2026-09-16. A gap enclosed by the artwork (a hand on a hip) was filled with mesh, so the weights measured straight across it — 0.718 through against 1.02 around. Now traced, bridged and excluded; edge expansion shrinks holes instead of growing them. Harness-proved, not yet seen on a phone. |
| **Per-triangle depth (3/4 stance)** | 🟡 | 2026-09-16. Each pin carries a depth and the weights blend them into a field, so a shoulder can be behind the body with the hand in front and the handover lands ALONG the forearm. One sorted draw call; bending never re-sorts. Slider shipped, never turned on a phone. |
| Puppet pins, bones, IK, dangle | 🟢 ~ | Place, bone, drag-a-limb, simulated hair. Rigging and posing confirmed on a phone 2026-09-16. **DANGLE is the one part still unwitnessed** — the chains bake and the harness proves it, but nobody has watched a tail swing. Dangle chains BAKE to keyframes on rebuild as of 2026-09-16 — before that the type, its four sliders and DangleSim all existed and nothing ever called them. **Bones became SELECTABLE 2026-09-16** (tap a shaft), which is what let the bone scope exist at all: parent, length, rest angle, stretchy, flip elbow, joint limits. |
| **Puppet — the nine dead knobs** | 🟡 | 2026-09-16 audit: every control traced to the code that reads it. Show·Mesh, the reach eye, per-bone bend sign, pin mute, per-pin weight and Snap-to-keys were all authored, saved and read by NOTHING; each is now wired. Per-pin `scale` and the Free-pin rotate arc could not be expressed by the engine (`handleComponents()` is 2) and were REMOVED, field and all. Full list and reasoning: `tasks/PUPPET_FINISH_LEDGER.md`. |
| **Puppet KEYFRAMES and live takes** | 🟢 | SPEC_20260915_PUPPET_UI. Per-pin keys, record-on-touch, thinning, anchor-in/blend-out, and a tape that draws performances as long diamonds. **PROVED ON A PHONE 2026-09-16** — JoyRaptor rigged a character and reported arming, recording and play all working, with the keys playing the motion back afterwards. That is the whole performance loop, and it was the largest 🟡 in the animation wing. 2026-09-16: arming record now ROLLS THE TRANSPORT on the first real movement and stops it on release — JoyRaptor reported *"recording mode doesn’t work when I arm it and then I move something, playhead doesn’t move"*, and it did not: recording required playback to be running ALREADY, so arming and touching a pin was a no-op. |
| **Puppet tape — slide / stretch / retime** | 🟡 | 2026-09-16. `MeshPoseTrack.shiftRange / scaleRange / moveKey`, 26 assertions. A time edit NEVER destroys a key: each clamps at the nearest key outside what is moving and returns the delta actually applied. The hit-test is disjoint from the property diamonds by construction (puppet marks draw ABOVE the row midline, diamonds below), which is what made a fourth claimant on the timeline safe. **Never dragged on a phone.** |
| **Puppet easing — fitted from the recording** | 🟡 | 2026-09-16. `MeshEasingFit` tries every curve the app can express against the samples thinning is about to discard, and writes the one that fits. Crucially it is allowed to conclude LINEAR — a fitter that upgrades everything to a flourish would pass every other test and still be useless. 10 assertions. |
| Puppet helper strip (on-preview) | 🟡 | Four controls so mesh editing never needs the drawer: type swatch (drag OUT to place, drag a pin IN to delete), pose/place, key/arm/jump, depth scrub. Never touched by a human. |
| Puppet UI — badge, drawer tab, loupe, shapes | 🟡 | On the phone; lightly used. The loupe is now shared with the transform tool, whose own magnifier is therefore **unverified since the migration** (the code path is unchanged by inspection — it passes `avoid = null` and takes the identical branch — but nobody has opened that tool). |
| **The marionette badge — pins vs transform** | 🟡 | 2026-09-16. Tapping him used to GREY the pins, which left a rigged picture as the one kind you could not move: JoyRaptor, *"it kind of behaves like it’s not selected."* He now toggles between the pins and the ordinary transform box, and a hold brings the drawer. `rig.locked` was repurposed to mean "put away" and stays persisted — pins should not clutter a picture you are not animating. |
| **Puppet — Start over** | 🟡 | 2026-09-16, at the bottom of the Character scope. Names what it will destroy, says the picture is untouched, and says one undo brings it back — which is true, because the rig and the pose track are snapshotted together. |
| Puppet pins on a ROTATED picture | 🟡 | 2026-09-16. The box being measured was the axis-aligned BOUNDS of a rotated view, so pins sat off the artwork AND scattered during a rotate as those bounds swept. One map, one inverse. Not re-tested on a phone. |

### SpriteLab — the desktop companion (`tools/spritelab/SpriteLab.html`)

**A pilot, not a tier.** JoyRaptor's ruling, 2026-09-11: *everything* SpriteLab does is
destined to land in Joy Creator itself, on phones and tablets. He works in fragments around
childcare — often one-handed, often outside the house with only a phone — so "wait until you
are at a computer" is the failure this app exists to remove. The web tool's durable purpose
is interop with desktop software (Photoshop round-trips) and being a fast place to try an
interaction before paying Android's build cycle. **No capability may be desktop-only.**
Consequence: `SpriteSheet` must grow an additive per-cell transform, so alignment becomes
data rather than baked pixels. See INBOX 2026-09-11.

A single-file, zero-dependency web tool for repairing an AI-rendered
sheet before it ever reaches the phone: slice, reorder by tapping, rearrange cells, align
each frame, name clips, bake a clean sheet and write a `.sprite.json` the phone reads
field-for-field. Its cell geometry is a deliberate port of
`SpriteSheetRenderer.cellRectSource()`, so anything it can express the phone slices
identically. 🟢 — JoyRaptor is using it to animate the Joybot mascot, 2026-09-10.

Specs: SPEC_20260910_SPRITELAB_UI / _MODEL / _SEMANTIC_CELLS.

### SpriteLab ON THE PHONE — `SpriteSheetEditorActivity` (2026-09-13)

The pilot has landed. `tools/spritelab/SpriteLabMobile.html` is the approved design and the
Android screen is built to it, not merely inspired by it: **the icons are the same path data,
generated out of the HTML by `tools/spritelab/genicons.py` into `SpriteIcons.java`.** Edit the
mockup, re-run the script, and the phone follows — which is the only way two surfaces are still
the same a month later.

What is device-proved on the Note 9, 2026-09-13:

| | |
|---|---|
| Top bar | back · name · undo · redo · four-section segmented nav (amber grid, cyan align, violet clips, green out) · save, pink while dirty |
| Grid | cyan outline for a cell the roll uses, pink for the one showing now, order badge top-centre (`4,5` when a cell is used twice), index, name, viseme |
| Alignment | four scrubbable number pills — **drag to change, tap to type** — auto-centre, plant feet, reset, reset all |
| Onion | a toggle, a past pill and a future pill you SLIDE for count and TAP for colour, a strength number, and one swatch for the ground behind the art (tap cycles eight, hold lists them) |
| Sequence | tap cells to build the roll, holds, save as a named animation |
| Transport | play/pause · step · loop-pingpong-once segment · reverse · fps — and **play plays the ROLL**, holds honoured, ping-pong turning at the ends, once stopping on the last frame |
| Clips | a shelf of the one chip design; picking one lights its frames on the sheet in order |
| Export | writes `.sprite.json`, and SHOWS it, with a Copy button |
| Undo | snapshot-based, one press one step, a whole drag folded into one |

What is **not** on the phone yet, and is therefore still desktop-only in violation of the rule
above: **nothing, as of 2026-09-13.** Bake-to-a-new-sheet (content fit or keep, PNG or JPG),
multi-sheet merge, numbered frame export and drag-to-reorder on both the film strip and the
clips shelf all landed and were device-proved that day. `SpriteBaker` draws every pixel through
`SpriteSheetRenderer.drawCell`, the same single blit the preview uses, so a bake cannot disagree
with what was on screen; sources whose cells are a different shape letterbox rather than squash.
A bake never touches the original — it adds a new sheet beside it.

Sheet management landed the same day: Export carries a Sheets panel — open, rename, remove —
and the remove guard reads BOTH stores of sheet ids, timeline sprites and avatar-rig parts,
because those never overlap and counting only the first armed Remove on precisely the sheets a
puppet is built from.

**Arranging the sheet LANDED 2026-09-13.** JoyRaptor ruled that a drawing's name and alignment
travel with it, so `SpriteSheet` grew `cellOrder` (display -> source) and every per-cell lookup
maps through it; the map is applied in `cellRectBitmap`, the one place a slot becomes art, so
every surface reorders together. Slice carries one segment — drag [ Pan | Pivot | Swap |
Ripple ] — because four modes that all change what a drag does must be mutually exclusive, and
as four separate chips two could be armed at once with reorder silently winning.

> ⚠️ **Avatar Studio is the largest 🟡 block in the app.** Substantial, sophisticated, and
> never exercised by a human. Recommendation: **present in the launch build, not reachable
> from the UI.** Ship what has been driven.

---

## 5.5 VISLAB — the music visualiser lab (2026-10-17 to 10-27)

GPU visuals that react to music: a room to build looks in, the same looks inside Studio as video
layers, and a floating visualiser over other apps. Lead docs: `tasks/VISLAB_SPEC.md`,
`tasks/VISLAB_TODO.md` (checklist with code places), `tasks/VISLAB_ROADMAP.md` (what is next).
"Agent phone-tested" = driven on the Note 9 by an agent with screenshots; 🟢 = JoyRaptor used it.
"Built, phone test owed" = compiled and its JVM tests pass, not yet seen on a phone.

| Feature | Level | Notes |
|---|---|---|
| VisLab room: 42 looks, play to the demo song / mic / a song, MIDI or tracker file; while the editor is open the shrunk picture sits on Studio's filmstrip art with a soft glow of the look's own colours behind the see-through drawer | 🟢 ✔ | `vislab/room/VisLabActivity`; GLES 3 engine `vislab/gl/VisRenderer`, shaders shared with the web pilot. The art and glow (`room/RoomBackdrop`, owner's idea 10-27/28) agent phone-tested 10-28, 60 fps closed / 55 open |
| Live sound analysis (bass..air, kick / snare / hat, beat and bar, spectrum, history) | 🟢 ✔ | `vislab/audio/FeatureExtractor`; frame-for-frame parity test with the pilot (`tools/jvm-harness/run-vislab.sh`) |
| The Lab: build a look from nothing, 35 elements, stack layers, every knob music-linkable (the ♪) | 🟢 ✔ | `room/KnobPanel`; looks saved as hand-editable `Supplies/Looks/<Name>.vis.json` in his folder |
| Parts shelf: save a layer as a part, reuse it in any look | 🟡 ✔ | `.vispart.json`; agent phone-tested 10-18 |
| Per-layer Opacity, Reactivity, Softness, Place (made in its own space), colours | 🟢 ✔ | |
| Words as art: 9 paths x 6 behaviours x 6 letter styles; the song's words or your own typed words | 🟢 ✔ | `words/WordFeed` + `words.frag`; 48 words at once (10-23), Look ahead / behind, Shift along, Fade in/out |
| Sliders: ghost dot where it started, soft snap to start / 0 / default / 45-90° | 🟡 ✔ | 10-23, agent phone-tested (`room/SnapSlider`, `GhostSeekBar`) |
| Knobs that only act on the music: ♪ badge, dim while silent | 🟡 ✔ | 66 flagged in `types.json` (`"music": true`); agent phone-tested 10-23 |
| Place Spin: a layer keeps turning, speeds up smoothly with the music | 🟡 ✔ | 10-23, agent phone-tested |
| Heat guard (halves resolution when the phone runs hot) | 🟡 ✔ | `view/HeatGuard`; seen working 10-18; hour-long heat test still owed |
| Floating visualiser over other apps: box or full screen, any look, hears a recording / the phone's sound / the mic | 🟢 ✔ | `vislab/overlay/VisOverlayService`; recorder menu's Visualiser switch |
| VisLab looks inside Studio: the visualiser's Style tab, look row + keyframe for which look shows when | 🟢 ✔ | `vislab/studio/VisLabStyleTab`, `StudioVis` (own GL thread), preview = export |
| Keyframable React / Fade / Bright / Colour / Size / Speed on a Studio visualiser | 🟡 ✔ | `model/VisLabKeys`; agent phone-tested 10-22 |
| Lyrics looks in Studio show the clip's own transcript | 🟡 ✔ | `vislab/studio/StudioWords`; agent phone-tested 10-22 (preview + export) |
| Blend mode on a Studio visualiser (Difference, Screen, Multiply...): hold the visualiser, Blend tab; Joybot can set it too | 🟡 ✔ | In the export (`export/VisualizerBlendGlEffect`, agent phone-tested 10-23) and, since 10-27, in the preview too (R11). Agent phone-tested 10-27/28: preview matches the exported frame, and a visualiser sits under pictures, sprites, text and captions in both (R15). Help: Joybot topic "blend"; action `set_blend_mode` |
| Web look book (desktop pilot of every look and the editor) | 🟢 ✔ | `tools/vislab-web`; published as a private Claude artifact (v5) |
| Joybot can pick, edit and save looks | 🟡 ✔ | Joybot lane (`ai/actions/VisLab*`) |
| Font per Words layer: each Words layer in any app font or his imported ones. Where: the room's editor, a Words layer, Font | 🟡 ✔ | Agent phone-tested 10-28 (R1): two fonts in one look at once, kept after reopening. `words/VisFonts`; one glyph sheet per text + font. Help mapping pending |
| Parts instead of lumped scenes: Stars (crisp, twinkle / pulse to the beat / sparkle on hi-hats, shooting stars), Aurora curtains, Mountains (ranges you drive past, snow caps, bass lift), Outrun's Neon grid, Retro sun and Neon ridge, and Nebula clouds (the Nebula without its stars). Where: the room's editor, Add a layer | 🟡 ✔ | Agent phone-tested 10-28 (R2): looks "Aurora Parts" and "Outrun Parts" rebuild Northern Lights and Outrun from parts; the old combined elements and looks are unchanged. One shader per new part (`neongrid.frag`, `retrosun.frag`, `neonridge.frag`; glslc-checked). Help mapping pending |
| Knobs hide when the current mode makes them do nothing | 🟡 ✔ | Agent phone-tested 10-28 (R6) on Spectrum (Line hides Bars, Bar width and Rounded); `showFor` on 66 knobs |
| Sprite element: a Sprite Lab sheet playing inside a look, its own speed / a frame per beat / per kick / the loudness picking the frame / one held, back and forth, one of the sheet's animations. Where: a Sprite layer, Sheet | 🟡 ✔ | Agent phone-tested 10-28 (R7): a project's sheet chosen in the room animates there and in a Studio export. `gl/SpriteLayers`, `room/Sprites`, `room/SheetShelf`; frames drawn by Sprite Lab's own renderer, so alignment matches; look "Record Hop" |
| MIDI as a sound source: a .mid file drives a look with exact kicks, snares, hats, notes and tempo; .kar lyrics become the song's words; a built-in synth plays it. Where: VisLab room, "A song" | 🟡 ✔ | Agent phone-tested 10-27 (R5): our test song Joy Beats (`tools/test-music/joy-beats.kar`) plays in the room and its words sing in Halo Lyrics on time. `vislab/audio/Midi*`; `tools/jvm-harness/run-midi.sh` |
| Tracker modules (.mod) as a sound source: the module's own samples play, and its drum hits drive the look exactly, as MIDI does. Where: VisLab room, "A song" (its hover label says so) | 🟡 ✔ | Agent phone-tested 10-27 (R13) with a 4-channel beat module: it plays through the app's own player and drives Record Hop. Per-channel hits are covered by `tools/jvm-harness/run-mod.sh` (16 checks; 20 with the two owner modules present locally), not checked separately on the phone. `vislab/audio/ModFile`, `ModPlayer`, `ModMidi`, `ModSource` |
| VisLab finishes as Studio effects on any clip or adjustment layer (keyframable): Lens split, Scanlines, Zoom blur, Glitch tear, VHS, CRT bend, Kaleidoscope, Mirror, Shake, Wet floor; and Glow, Flash (with strobe), Vignette, Ripple, Swirl, Warp; and Echo Trails (moving things leave fading trails that zoom, turn and change colour; adjustment layers only). Where: Studio, a clip's or adjustment layer's Effects drawer, Add effect; every chip's hover label says what it does | 🟡 | Agent phone-tested 10-27 on the Note 9 (R4, R10), all on joy-creator (59346ae8a); owner has not used them yet. The seven new effects show in the picker with their sentences and render on an adjustment layer in the preview; Echo Trails' trails show while playing and in an exported video. Also: FxCompilerTest green, every real layer program compiles under glslc (GLSL ES 3.1, 35/35). The first ten were type-checked and tested off-device, not individually re-checked on the phone. Limits: Glow is a one-pass approximation of VisLab's bloom; Echo Trails restart at each clip boundary in export. Source: `studiokit/.../fx/FxVisLabDefs`, `FxVisLabDefs2`, `FxBlurbs`, `FxCompiler` (FEEDBACK), `app/.../fx/FxHistory` |
| Studio visualiser on the shared transform box: move, free resize, spin arc, snapping, keyed writes, hold or double-tap opens its drawer, follows a parent it is linked to | 🟡 ✔ | Agent phone-tested 10-27/28 (R3): drag, corner resize, spin, a keyed drag and following a linked proxy, each one undo step. `transform/VisualizerTransformHost` |
| Studio effect sliders that follow the music: a "♪ music" row under every slider of an effect, pick Loudness, Bass, Kick, Snare, Hi-hats or Beat and how hard it pushes (25, 50, 100%, or the other way). Where: Studio, an effect layer's or a clip's own Effects drawer | 🟡 ✔ | Agent phone-tested 10-27 (R14): Flash on Kick flashes on the drum in the preview and in an exported video. It hears an audio track playing at that moment, else the clip's own sound. One undo step per tap. `studiokit/.../fx/FxMusic`, `vislab/studio/StudioEars`. Help mapping pending |
| A look's parts follow Studio objects: a look's layer (a logo, rings, sparks...) follows a picture, title, sprite or dummy's position, size and turn, either snapping onto it or keeping its own place and moving as the object moves, and copying any of Position, Size and Rotation (as Studio's link). Where: hold the visualiser, Style tab, "Layers can follow an object" (the chip beside it, and the "copies" row under it) | 🟡 ✔ | Agent phone-tested 10-27/28 (R8): a keyed dummy and a picture, both ways of following, preview and export; the copies choice (Position off, undo, reopen) 10-28. `vislab/studio/VisFollow` (run-visfollow 16/16). Help `vislab_follow` (Joybot HelpBook) |
| A look shows a Studio item: a look's picture layer shows any picture, title, sprite (or performing avatar), overlay video or the main video in the project, as it is at that moment, with all the look's picture effects. Where: hold the visualiser, Style tab, the picture layer's "shows" row | 🟡 ✔ | Agent phone-tested 10-27/28 (R9): a picture and the main video in the preview and the export; a sprite, a title and an overlay video in the preview. Avatars not tried on the phone. `vislab/studio/VisShow`, `StudioPictures`, `StudioArt`. Help `vislab_show` (Joybot HelpBook) |
| Video inside a look: one of your videos playing in a look, round and round, with the picture's pump, shake, halo and colour split. Where: the room's editor, Add a layer, Video clip (or Use a video on any picture layer) | 🟡 ✔ | Agent phone-tested 10-28 (R12): plays in the room at 50-53 fps; in Studio a look's own video runs on the look's clock. Frames from Studio's forward decoder (`export/SequentialFrameReader`) at 540 px, `vislab/room/Videos`. The web look book has Video clip and "Use a video" too (10-28). Help `vislab_video` (Joybot HelpBook) |
| Element packs from other people (plugins) | 🔵 | Designed in VISLAB_TODO section F |
| Element packs for VisLab from other people (looks and parts as plugins) | 🔵 | Designed in VISLAB_TODO section F; not on the VisLab roadmap yet |

## 5.6 JOYBRUSH ENGINE — current feature families (October26)

[Mandatory roadmap](joybrush/ROADMAP.md) owns broad progress;
[lead handoff](joybrush/HANDOFF_20261026_COORDINATOR.md) binds exact source/evidence.
Current integration is `lead/select-reconcile-20261024`; production `joy-creator`
receives documentation separately. Do not infer feature deployment from these docs.
Agent phone checks stay distinct from owner LIVE/creative acceptance.

| What users can do / feature family | Level | Availability and remaining limits |
|---|---|---|
| Paint watercolor/oil/pencil on the atlas; water catches up after pen-up | 🟡 | Existing main foundation and earlier agent checks; preserve approved full-resolution watercolor pooling. Current combined performance/quality acceptance is not complete. |
| Large washes beyond resident atlas room | 🔴 | Isolated park/residency repair passed bounded tests; real GL ownership, atomic durable stores and capacity/save/undo proof still gate activation. |
| Hold Brush / Smudge / Eraser to open that brush's settings | 🟡 | Existing main code and earlier agent installation; owner use not newly claimed. |
| Select editable lines, move/resize/Nudge, Copy/Paste and finger-pan | 🟡 agent-device checked | Bounded exact-APK Select workflow passed on Note9; current A10 extension is integrated, automated-tested and passed its bounded functional phone check; registered-image review passed within scope. Physical pen, outside fade and pixel-selection feature family remain separate gates. |
| Choose editable lines per brush; see drawer badge | 🟡 built integration | K25 policy and native settings switch integrated and automated-tested. Dedicated A10 functional agent-device check passed; registered-image review passed within scope. Always-pixel media stay pixels; D23 first-use notices need actual engine publication/consumption signals. |
| Watercolor editable lines with frozen wash coordinates | 🟡 foundation | Geometry/archive/render groundwork exists. Separate ordinary replay/undo diagnostic is compiled but not yet wired/run; current save/reopen/recovery and original-preset appearance acceptance remain owed. See section10.5. |
| Lines over realistic paint on one layer (K26) | 🔵 / guarded | Ordered compositor/media integration and proof remain; do not advertise the whole live source/undo/save connection complete. |
| Frame tween settings and sampling | 🟡 foundation | Saved setting/sampler exist; tested isolated helpers do not establish live playback, controls or export. Clips foundation remains separately unmerged into coordinator. |
| Duplicate an animation frame without losing editable lines | 🔴 | Existing path keeps pixels but loses editability. Canonical-operation validation/copy ownership prerequisites are under integration; actual independent live copy, undo and save proof remain. |
| Smudge only the touched piece of a line | 🔴 | Ordered section/Slice design and pure planning exist; atomic touched-only bake/no-ghost runtime remains incomplete. |
| Unibrush stamp families and picture lines | 🟡 hidden / audit needed | Preserved specialist code and historical checks; recover exact current taper/edit/acceptance/speed/activation gates before claiming completion. |
| Oil stroke ends, colour loading and dirty brush | 🟡 isolated / audit needed | Preserved Covers/Carries specialist code; reconcile current source and device/artist proof. Defaults remain0; older stroke-end receipts do not prove later loading/carry work. |
| Symmetry | 🟡 recovered, agent-checked on the Note 9, not landed | Branch `claude/symmetry-recover-20261027` (JoyBrush line). 10-27 agent check with finger swipes on a fresh drawing: mirror ×2 exact, ×8 radial, kaleidoscope (16 copies), eraser, tap one copy to select the whole set, one recolour, one Undo each, save and reopen all work; realistic brushes say "Symmetry is not available for pencil, watercolour and oil on this build" and paint nothing (the atlas has no symmetry pin). Moving the line is not an Undo step (way back: Helpers, Symmetry off then on). Not checked: real pen tilt and pressure, scatter, speed at 30 copies, the owner's feel. Evidence: `tasks/joybrush/evidence/symmetry-phone-20261027/` on the branch. Lands after HYG-2. |
| Create, size, move and lock Image, Animation and Sprite regions (`joybrush.boards`) | 🟡 BUILT; historical scoped agent-device evidence | Boards menu and source are integrated on `lead/select-reconcile-20261024`. October6/7 workflows are historical; current mixed-content/mask/geometry and exact visual acceptance remain open. See the [owner contract](joybrush/specs/JB-3.00a_boards_owner_model.md) and [controller](../joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/board/BoardRuntimeController.kt). Help `joybrush.boards.create-size-lock`: PENDING. |
| Board-scoped layer previews, held layers and onion skin (`joybrush.board-preview`) | 🟡 BUILT; historical scoped agent-device evidence | Source is integrated; October7 scope checks are historical. Current G7a saved-frame/crop/cache parity and all row-state visuals remain open. Reuse [BoardRuntimeController](../joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/board/BoardRuntimeController.kt), [LayerColumnView](../joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/chrome/LayerColumnView.kt) and [BoardSession](../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/BoardSession.kt); no new compositor is claimed. Help `joybrush.boards.frames-holds-onion`: PENDING. |
| Repeating Image pattern and supported-stroke wrapping (`joybrush.tile-pattern`) | 🟡 BUILT; historical scoped agent-device evidence | Source is integrated; October7 boundary/disarm/Undo checks are historical. Current acceptance remains open. Preserve explicit refusal for realistic pencil, watercolor and oil wrapping and all other unsupported-tool guards. Reuse [BoardRuntimeController](../joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/board/BoardRuntimeController.kt) and [BoardSession](../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/BoardSession.kt). Help `joybrush.boards.pattern-limits`: PENDING. |
| Sprite cell sizing, ordered finger roll/preview and separately armed swaps (`joybrush.sprite-roll`) | 🟡 BUILT; historical scoped agent-device evidence | October7 two-layer swap/one-Undo and sizing checks are historical; current media/mask acceptance remains open. Reuse [SpriteGridMath](../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/sprite/SpriteGridMath.kt), [CellRoll](../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/sprite/CellRoll.kt) and [SpriteCellOps](../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/SpriteCellOps.kt). Do not infer realistic-media or complete mask/INK behavior. Help `joybrush.boards.sprite-order-swap`: PENDING. |
| Board/range/frame export and internal Studio/SpriteLab handoff (`joybrush.board-export`) | 🟡 BUILT; historical scoped artifact/import/reopen/video evidence | Existing encoders and internal receiver are integrated; current artifact agreement, refusal/preflight and picker-cancellation/snapshot checks remain open. Reuse [BoardExportCoordinator](../joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/board/BoardExportCoordinator.kt), [BoardExport](../joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/BoardExport.kt), [AnimExportPlan](../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/export/AnimExportPlan.kt) and [internal receiver](../app/src/main/java/com/fadcam/ui/faditor/sprite/JoyBrushBoardImportActivity.java). Internal staging is not arbitrary external import; live-source and tween export gates remain separate. Help `joybrush.boards.export-studio-spritelab`: PENDING. |
| Send a board to Studio **into an existing project** (`joybrush.board-to-existing-project`) | 🟡 ✔ | On `joy-creator` 10-28 (8b238c428). When projects exist, Send to Studio asks "New project" or a project by name; into an existing one the animation is one more sprite layer over the whole project, looping, half the canvas, centred, and a board sent twice keeps both sheets. A failed import never deletes the project. Rules test 16/16; agent-checked on the Note 9 10-28 (chooser listed the projects; the layer landed over 0–5 s at about half width; the project's clip intact). Not yet tried by JoyRaptor. Help `joybrush.board-to-existing-project`: PENDING. |
| Choose paper background, surface and brush response (`joybrush.paper-surfaces`) | 🟡 BUILT; historical scoped core/GPU and controlled rollback evidence | Source integrates 29 surfaces and 32 looks; this is not whole-family completion or owner acceptance. **None** keeps the physical surface and Bite response, shows checkerboard on screen, and excludes paper from exports even when Include is requested. Current visual quality/parity across all 32 looks, replay invalidation, sheet/preview/cancel/undo UI, save/reopen/export device matrix and imported image-tip/grain behavior remain open; missing JB-1.05d image-tip contract and owner-rejected procedural crumples remain explicit open gaps. Tan-paper Select proof does not establish paper-family acceptance. Reuse [PaperState](../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/paper/PaperState.kt), [SurfaceMaps](../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/paper/SurfaceMaps.kt), [PaperRaster](../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/paper/PaperRaster.kt), [HexTile](../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/paper/HexTile.kt), [PaperPreviews](../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/paper/PaperPreviews.kt), [PaperResources](../joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/PaperResources.kt), [PaperSheetView](../joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/chrome/PaperSheetView.kt) and [RegionRenderer](../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/render/RegionRenderer.kt). Keep raster paper separate from [editable watercolor lines](../joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/brush/WatercolourDabber.kt). Help sections—Paper background vs surface; Colour vs Tint; Show vs Bite; None vs Include; paper vs watercolor wash texture—are PENDING. |
## 6. AI — the agent

| Feature | Level | Notes |
|---|---|---|
| Chat assistant (Joybot), in the Studio, the lobby, SpriteLab, the Library, VisLab and JoyBrush | 🟢 ✔ | Tap Joybot in the room's corner. ChatAssistantActivity. Help: "joybot" topic. Room idea buttons: Studio, lobby, SpriteLab, Library, VisLab; JoyBrush's added 10-28 (BOT-1, phone ✅ 10-28). |
| Joybot "Did you know?" tips and a hopeful offline message | 🟡 | Built 10-28 (BOT-2): every empty open greets you with one of 51 tips about built features, leaning to the wings you use, with a small "Take me there"; works offline. With no internet Joybot says so, then what still works, then a tip. `joybot/TipBook`, `TipUsage`. Agent-checked on the Note 9 10-28 (the tip, Take me there); the offline wording needs the phone offline (owner check). |
| Connect an AI by talking to Joybot (key kept on the phone) | 🟡 | Built 10-28 (BOT-6): "Connect an AI" on the greeting, or say it. He asks whether you pay for Claude, ChatGPT, another or none and explains the choices. A key pasted in the chat is caught on the phone: never sent to the AI or kept in the chat, shown only as "key ending …1a2b", checked with one free call before it's saved. `joybot/KeyCatcher`, `SetupFlow`. Agent-checked on the Note 9 10-28 with a fake key (masked, not saved, not logged). ⚙ stays for power users. Settings › Studio › "AI and Joybot" opens the same settings. |
| Bring your own AI: Claude Code, Codex or any MCP app uses Joybot's tools | 🟡 | Built 10-28 (BOT-6): Joybot › Connect my computer (also help "bridge"). Opens a door on the phone over your own Wi-Fi or a USB cable, nothing through us: a six-digit code, the owner's gradient QR, "say this to Claude Code". Joybot's own rules hold (one Undo per burst, locks, deletes wait for a tap). `ai/bridge/`. Proved on the PC end to end over HTTP (19/19) and agent-checked on the Note 9 10-28 over a USB cable (a title change, `look`, wrong code and web page refused, Stop); not yet with a real Claude Code. The talk half (Joybot's chat → the computer's AI) is not built. |
| **Joybot's tools: 63 typed actions + 61 older tools** | 🟢 ✔ | Counted 10-28 (`ai/actions/*`, `AIToolExecutor.executeTool`). Studio, the app, VisLab, JoyBrush. No mobile competitor has this. |
| EditScript contract (23 op types) | 🟢 ✔ | Validate-all-then-apply, one undo step, unknown ops rejected |
| Proposal cards (confirm before applying) | 🟢 ~ | narrative / b-roll / avatar rig |
| AI-generated slides (GSAP, deterministic) | 🟢 ~ | Device-proven 2026-07-16, 87 frames to MP4 |
| Copy-prompt then paste-back for slides | 🟢 ~ | **The precedent for share-in.** Already works. |
| Vision (send a frame to the model) | 🟢 ~ | |
| B-roll tagging and suggestion | 🟢 / 🔴 ~ | Works on video-track projects; **fails on audio-track projects** — see section 9 |
| Background AI jobs that survive app close | 🟢 ~ | AIJobService — the pattern video generation will reuse |
| Headless apply over adb | 🟢 ~ | ApplyEditsActivity — dev tool |
| Image generation | 🔵 | Endpoint verified live: POST openrouter.ai/api/v1/images |
| Video generation | 🔵 | First post-launch feature |
| Share-in from other apps | 🟢 | Built 2026-10-13, Note 9-proven: "Share to Joy Creator" saves beside the open project (else Assets, else the top) and "Add to" drops it on the timeline. |
| Multi-provider support | 🟡 ✔ | Built (Joybot lane, 10-15/17): several AI accounts (OpenRouter, OpenAI, any compatible address), switch in the model picker; a no-credit account says so plainly. OpenAI itself untested: the owner's account had no API credit. |
| Streaming replies | 🟡 ✔ | Built: replies stream in, with a 75 s "went quiet" cut-off. Agent-checked on the Note 9 with free models. |
| Joybot sprite and states | ⚪ | INBOX.md — needs its own session |
| **Joybot as a harness** (one list of actions, native tool calls, tool routing) | 🟡 ✔ | Joybot lane 10-15→10-24: typed actions for Studio, the app (rooms, Library, Trash, new project from a recording), VisLab (looks, knobs, layers, brand, save/rename/revert/delete, floating visualiser). One request = one undo. Agent-checked on the Note 9 with free models; not yet used by JoyRaptor. Handoff: `tasks/joybot/JOYBOT_HANDOFF_20261015.md`. |
| Joybot audit fixes (Codex audit 10-18) | 🟡 ✔ | All 12 fixed + 4 more found on the phone (`tasks/audits/2026-10-18/joybot_fixes.md`). Note 9 agent checks 5/6 green; the VisLab one is folded into the VisLab session's 10-24 test. Locked items stay locked unless the person says "unlock" or "yes"; delete/revert ask first. |
| Joybot sees the finished video (`look`) | 🟡 ✔ | 95c7cd868 (side session): titles, layers, grade and effects at any moment, as exported. Agent-checked once on the Note 9. |
| Joybot draws in JoyBrush | 🟡 ✔ | a94933ad7 (10-24): layers, frames, brush and colour, simple shapes; one request = one Undo. Agent-checked on the Note 9 10-24 ("add a layer called Sketch and draw a red circle": one Undo removed both). Re-check after the button-face change (147c19c00) owed (BOT-1). |
| Director mode: plan → Go ahead → Review | 🟡 ✔ | Built 10-15: for a big request Joybot shows a short plan; after Go ahead it runs up to 40 steps, then a Review card lists each change with Show me and Undo from here. Agent-checked on the Note 9 10-15 (Review rows, Show me, Undo from here, card survives reopening). Help: "director" topic (10-28). |
| Recipes: save a request, run it again | 🟡 ✔ | Built 10-15: "Save as a recipe" under a finished request; My recipes chip → Run (offline when it names nothing project-specific). Files in the Joy Creator folder, `Joybot/recipes`. Help: "recipes" topic. Agent-checked on the Note 9 10-15 (save, run, undo, delete). Triggers ("every new recording…") not built (BOT-4). |
| Hands-free Joybot (hold the mic, he answers aloud) | 🟡 | Built 10-15: hold the mic → speak → it sends itself → the reply is read aloud → say "yes", "undo", "stop" or "no". Help: "hands_free" topic. Not phone-tested: needs the owner's voice (BOT-1, S16). |
| Carry on after the phone kills the app | 🟡 ✔ | Built 10-15: a Studio request interrupted by the app being killed offers "Carry on" next time. Agent-checked on the Note 9 10-15 (force-stop). VisLab and JoyBrush requests can't resume yet (BOT-3). |
| Free-model task benchmark | 🟡 ✔ | 10-24: `bash tools/jvm-harness/run-joybot-bench.sh` runs the real agent on the PC against 53 tasks (Studio, VisLab, audit faults); 53/53 with the fake model after fixing a false "done" it found (f5a342ae9). Free models live: ready, not run (no OpenRouter key on the PC). `tasks/joybot/BENCHMARK_2026-10-24.md`. |

---

## 7. PROJECTS, ASSETS, STORAGE

| Feature | Level | Notes |
|---|---|---|
| Project = folder + project.json | 🟢 ✔ | schema v13 |
| Atomic save (.tmp then rename, .bak kept) | 🟢 ✔ | |
| Refuses to overwrite a newer-schema project | 🟢 ✔ | Good discipline, already there |
| **Consolidate** — copy every asset into the project | 🟢 ~ | Dedup by content hash, copy-never-move, cancellable |
| project:// relative addressing | 🟢 ✔ | AssetResolver. One scheme, deliberately. |
| Fonts consolidated into the project | 🟢 ~ | They used to die on uninstall |
| Zip export / import of a project | 🟢 ~ | |
| Relink by content hash and filename | 🟢 ~ | One relink resolves a whole folder |
| Asset browser (scans a chosen folder) | 🟢 ~ | assetbrowser/AssetScanner |
| B-roll bucket + .broll_tags.json | 🟢 ✔ | Lives in Pictures/FadCam/assets — **outside the project** |
| **THE VAULT** (now "the Joy Creator folder") | 🟢 | ✔ Built 2026-10-13/15, agent-driven on the Note 9: required first step; projects mirrored + restored; Gather; one Trash with Undo; recordings and exports land in the folder with readable names, the Library reads it; share-in; big outside files linked with Copy/Move/Leave + yellow dot; Space screen; JoyBrush drawings with thumbnails in the folder and the Lobby. Open items: spec §6. Live spec: `tasks/SPEC_20261013_FILE_MANAGEMENT.md` (§4 has the proof level of every piece). |

### 🚨 The storage finding

✔ ProjectStorage line 84: `projectsRoot = new File(context.getFilesDir(), "faditor/projects")`

That is app-private internal storage. Projects are invisible to the user, invisible to a
PC, impossible to put on an SD card, and **deleted without warning on uninstall.**
On 2026-08-29 an acceptance test did exactly that and destroyed 23 of JoyRaptor's 24 projects.

Everything downstream (consolidate, project://, zip, relink) is already correct and does
not care where the root lives. **The vault is a small change that must happen before
strangers install.**

---

## 7.5 THE FRONT DOOR — the lobby, and the visual system (2026-09-17)

Design records 01–07 (artifacts). Backup before any of it: tag
`pre-studio-redesign-20260917`, branch `backup/pre-studio-redesign-20260917`.

| Feature | Level | Notes |
|---|---|---|
| **The lobby** | 🟢 | `ui/lobby/LobbyFragment`. Tab position **6** — deliberately last, so every existing tab keeps its index and nothing switching on a position was touched. A ROUTER over existing screens: it cannot lose a feature and it is one constant from being reverted. The app lands on it; the bottom bar hides there and the house icon is the way back from every room. |
| Marquee carousel | 🟢 | Wrap-around room titles, heavy white → thin grey. **A new room costs one entry in `buildRooms()`** — that is the whole reason it exists rather than a sixth nav icon. |
| Hero with real frames | 🟢 | MediaMetadataRetriever at 1s in (frame 0 of a recording is usually black), off-thread, LruCached, tag-guarded against a room switch mid-decode. |
| Mixed recents + corner cut | 🟢 | Projects, recordings, exports, photos, streams by recency. Sampled on device: project `#73FB9E`, export `#4DBFFB`. Until recordings landed the cut was decoration. |
| Joybot's line | 🟢 | May only say things it VERIFIED — a count or two timestamps compared. Says nothing on JoyRaptor's phone today, which is the feature working. |
| Export hairline | 🟢 | Real `ExportService` broadcasts (out-of-process, so a normal receiver). Scales, never resizes. |
| New row: Import (`lobby.import`) | 🟡 ✔ | 10-28 (1d1bd5500). Opens the phone's file picker (pictures, videos, sounds) and files the picks the way a share into Joy Creator does (`ShareInActivity`): readable names, into the Assets folder or the top of the Joy Creator folder; they show in the Library at once. Before, the chip only opened the Library. Agent-checked on the Note 9 10-28 (2 PNGs in, Library 171 → 173). On that phone a one-time "Complete action using" chooser shows first (a second file app is installed). Help `lobby.import`: PENDING. |
| Every way to the trash opens Recently deleted | 🟡 ✔ | 10-28 (1d1bd5500): the lobby banner hold, the Capture logo hold and Joybot's "trash" route opened the classic FadCam Trash; they now open Recently deleted, which lists the classic Trash's recordings too and keeps "Classic trash" one tap away in its options. Agent-checked on the Note 9 10-28. |
| Viz Lab room's New makes a new look | 🟡 ✔ | 10-28 (fc2c9ba16): opens VisLab on a new look with its layers open, as the strip's "+ New" card does (it starts with one Aurora layer). Before, it opened the project list. Agent-checked on the Note 9 10-28. |
| Library: a VisLab look opens in VisLab | 🟡 ✔ | 10-28 (fa8dd576f, d73f6d9d1): tapping a `.vis.json` look under "Fonts and supplies" opens VisLab on that look (before, Android's "Open with" chooser). Agent-checked on the Note 9 with "My look" and "Sound City". |
| Library: rename a project | 🟡 ✔ | 10-28 (ce2b1f91c): hold a project → Rename; the editor title and the folder's `.joyproject` follow. Agent-checked on the Note 9. |
| **The visual system** | 🟢 | Ground `#000000`; lane A is the ground, lane B `#0B0B0D` (4.3%, inside SPEC_N §6). Chrome on `studio_panel`. Selection **cyan**, neutral playhead **pink** (context tint preserved — do not flatten it). Transport recedes by value: one white control. |
| Drawer transparency | 🟢 | Top drawers at 64% black, coloured chips at 70%. **Measured**: the object drawer's secondary text was at **1.39:1** over a blown-out frame — invisible outdoors. Now 4.86:1. Blur is impossible below API 31; tint is the only lever. |
| First run | 🟢 | New copy, the promise before the ask, the AI-key card. Red gone: cursor, ticks and button all sampled. |
| `ui/motion/Motion` | 🟢 | Four curves, seven durations, six rules. **No helper for 100×/day controls** — those get no animation at all. |
| Object drawer header | 🟡 | One-line title + scrolling tabs, fixing the reported "label stacks vertically and pushes the rows past the minimum drawer" bug. **Compile-verified only** — needs a real double-tap on a phone. |

**Open rulings, JoyRaptor's not mine:** the contextual tool row (would fight the
existing pins + usage-recency system), the ~50 sites where green means "modified",
amber transform handles on the canvas, and pink-not-red for voiceover recording.

**Asset-blocked:** empty-room heroes, the first-run slideshow, Joybot's face, the
demo project, a display typeface. All listed in design record 07 with sizes.

---

## 7.6 THE APP SCREENS, RESTYLED (2026-10-16 to 10-20)

Built by the UI/UX session and checked by agents on the Note 9 (after-shots in `tasks/screens/after/`; handoffs
`HANDOFF_STUDIO_UI_20261017.md` and `HANDOFF_STUDIO_UI_20261020.md`). **Agent-checked only: the owner has not accepted these**,
so they stay 🟡 and are not to be promoted yet. Old screens are kept reachable behind a "Classic" link (Records, About, Settings, Studio list).

| Feature | Level | Notes |
|---|---|---|
| **Library v2** — every kind of content in one place | 🟡 ✔ | `ui/library`. Kind chips with counts, search above the keyboard, a "shared in" filter, bands, grid or list, header buttons that cycle sort and view, and a Projects view with a New project pill (shared `NewProjectFlow`). The old Records tab stays as "Classic Records". Shots: `library_*.png`. Help mapping pending. |
| **Settings hub v2** | 🟡 ✔ | `ui/settingshub`. Sections with jump chips, search above the keyboard, live status on rows, every classic row mapped to a destination. Classic settings stay under About. Shots: `settings_hub*.png`. Help mapping pending. |
| **Recently Deleted v2** (Trash) | 🟡 ✔ | `ui/trash`. Project names and real thumbnails, one Trash for the whole folder, Undo on delete. Shot: `recently_deleted.png`. Help mapping pending. |
| **Home recorder ("Bits"), Remote / Server Room, Smart Detection** restyled | 🟡 ✔ | `ui/home` (HomeRec*), `strings_remote.xml`, `forensics/ui/view/SlantChip`. Owner ruled Remote and Smart Detection are launch features. Landscape Home still old; see the handoff for the open items. |
| **About** rebuilt | 🟡 ✔ | `ui/about` (AboutJoyFragment). "View source" still points at the original FadCam author: owner decision S9. Old About behind "Classic About". |
| **JoyBot switch** replaces the FadCam avatar toggle everywhere | 🟡 ✔ | `ui/joybot`; old `AvatarToggleView` is deprecated, not removed (FREE-1). Shots: `joybot_switch_*.png`. |
| Settings sub-screens, What's New, update sheets, mode popup, onboarding permissions page restyled | 🟡 | Audio, About and Watermark settings were looked at on the phone; What's New and the update sheets were **not** (10-17 handoff). Five legacy activities still ignore the theme (job UI-1). |
| Lobby truth pass and placeholder pictures | 🟡 ✔ | Counts only camera and screen recordings; rooms and projects with no picture get a designed placeholder. Shots: `lobby_after_truth_pass.png`. |
| Slanted ("sheared") chips on Library, Trash and Settings | 🟡 ✔ | The signature look; owner asked for a 15% narrower gap (done). |

---

## 8. ONBOARDING AND IDENTITY

| Feature | Level | Notes |
|---|---|---|
| App name is "Joy Creator" | 🟢 ✔ | strings.xml line 2 |
| Only 16 of 2,820 strings say "FadCam" | ✔ | Branding surface is small |
| Intro with animated fading lines | 🟢 ✔ | RowFadeAnimator — **the cycling-words mechanic already exists** |
| Language picker | 🟢 ✔ | |
| Permission screen (all up front) | 🟢 ✔ | Recommend deferring to point of use |
| "Verify you are a human" + privacy link | 🟢 ✔ | **Copy is written for a covert recorder. Must be rewritten for Play.** |
| New intro copy | 🔵 | Drafted 2026-09-09, approved by JoyRaptor |
| Vault picker screen ("Your Joy Creator folder") | 🟢 | Required first step since 2026-10-14 (shown after onboarding, before using the app); Note 9-proven. |
| Demo project | ⚪ | INBOX.md |
| New landing screen / studio funnel | ⚪ | INBOX.md — its own session |

---

## 9. 🔴 KNOWN BROKEN — triage before launch

| # | Defect | Severity | Source |
|---|---|---|---|
| 1 | ✅ **FIXED 2026-09-23** (LEDGER "audio clips kept getting new ids"): the loader keeps the saved id. Proof `bash tools/jvm-harness/run-audioid.sh` 14/14 — re-run 2026-10-14 after the harness got a StudioMirror stand-in (the folder hooks had broken its compile). | ~~High~~ | LEDGER 2c |
| 2 | ✅ **FIXED** (d0fecd60): narrative analyse works on audio; apply-narrative and b-roll refuse audio with a reason; no tool defaults to the auto-blank. Was: three AI tools resolve only against the video track — they fail on projects where the transcript sits on an audio clip (music / lyric projects). The one-path resolver already exists; three functions need to use it. | Medium | SPEC_20260828_AI_AUDIO_TOOLS |
| 3 | ✅ **FIXED 2026-10-13**: default is openrouter/free. Was: default AI model is openrouter/auto — the **paid** router. Every new user is billed on message one. | High — first impression | ✔ 4 code sites |
| 4 | ✅ **FIXED 2026-10-14**: `ai/AiKey` keeps it AES-GCM encrypted under an Android Keystore key; an old plain key is moved in on first read. Was: AI API key stored in plaintext preferences | Medium — security | ✔ |
| 5 | ✅ **FIXED 2026-10-14**: 8 tool steps per message, then it stops and says so. Was: AI tool-calling loop has no depth cap | Medium — can hang | Reports, plausible |
| 6 | ✅ **FIXED 2026-10-14**: 4096. Was: AI reply limit of 1024 tokens truncates edit instructions | Low, trivial fix | Reports |
| 7 | ✅ **FIXED 2026-10-14**: now merge_transcripts (old name still accepted). Was: ai_merge_transcript has "ai" in the name and uses no AI | Cosmetic honesty | ✔ |
| 8 | **remove_silence, ✔ checked 10-27:** on video clips it cuts (non-destructive, each cut can be tapped back); **on audio clips it still refuses** ("not yet via AI — use fix_audio or manual cut", `AIToolExecutor`). Was: report-only. | Low | ✔ code 10-27 |
| 9 | Stranded drag-latch, second path — self-heals, fires occasionally | Low, watch only | LEDGER 2b |
| 10 | **Automated tests, ✔ re-counted 10-27:** the old claim "no automated tests at all" is wrong. There are 65 JVM harness scripts (`tools/jvm-harness/run-*.sh`, 111 test files) and 386 test files in the JoyBrush modules. What is still true: the Studio app module has no `app/src/test` folder, so Studio code is checked only through the harness. | Structural risk, reduced | ✔ count 10-27 |
| 11 | ✅ **FIXED 2026-09-13** (617cf316d, re-checked in code 10-27): the Sprite palette lists saved animations as animated chips (`SpritePalettePanel`, `AnimChipView`). Was: saved sprite animations were write-only. | ~~High~~ | ✔ |
| 12 | **Cell names, ✔ re-checked 10-27:** the palette now reads them (`sheet.cellName`), and Joybot's `label_sprite_cells` writes them (tool exists since 08-07). `Cell.tags[]` is stored in the sheet but I found nothing in the AI tools that writes it (only `ai/` was searched). Still blocks the tag half of SPEC_20260910_SEMANTIC_CELLS. | Medium | ✔ |
| 13 | **Sprite sheets live inside `project.json`**, so a character perfected in one project does not exist in the next. Same wound as THE VAULT: an asset trapped in composition scope. The B-roll bucket and `AvatarLibrary` already show the right pattern. | Medium — reuse, and it gets worse with users | ✔ 2026-09-10 |

---

## 10. HISTORICAL GAPS — recheck before assuming a feature is absent

The following September inventory has NOT had a current whole-product audit.
Treat these as discovery questions, not current marketing statements or permission
to rebuild an existing engine. Check the feature map, source and peer branches first:

Text-to-image. Text-to-video. Text-to-speech or voice cloning. Voice enhancement.
On-device LLM. Hosted AI credits. Template gallery. Pack manager UI. Community pack index.
Music generation. A website. A Play listing. A privacy policy that mentions AI.
The earlier claim that this repository has no off-PC backup is superseded:
GitHub `JoyRaptor/JoyCreator` has published branches. That does not establish a
backup of every dirty worktree, ignored asset or local evidence; see Git hygiene.

---

## 10.5 JOYBRUSH VECTOR WATERCOLOUR — current acceptance limits

Sample-based editable watercolor is a distinct family from raster water simulation.
Preserve the owner-approved crisp, high-resolution pooling. That approval does not
establish every current engine/edit/save/performance path.

| Capability | Current evidence / limit |
|---|---|
| Pressure/tilt, pooled pigment and frozen wash-frame foundations | Code and earlier scoped GPU/archive proofs exist; current whole-family device/creative acceptance remains incomplete. |
| Ordinary line move/replay/tint/Undo | Dedicated diagnostic compiled in integration, but dispatch/native execution pending. Debug grain/paperCut0 isolates transported optics; it cannot establish original grainy-preset appearance. Selected-cache probe is a separate test. |
| Per-brush editable mode | Integrated and automated-tested; dedicated current A10 functional agent-phone check passed; registered-image visual review passed within scope. |
| Saved/cold first-bake and recovery | Separate snapshot/cold-loader groundwork exists; real View save/reopen/graphics restart/recovery/edit matrix still owed. |
| Full fade and Note9 speed | Historical synthetic/glFinish measurements exceeded16.7ms; no new performance acceptance claimed. Repeat identical cold/warm/fade matrix before a speed claim. |

Use the [current roadmap](joybrush/ROADMAP.md), not the superseded October24 branch
assignments, for next work and exact [lead evidence](joybrush/HANDOFF_20261026_COORDINATOR.md).
## 11. HOW TO MAINTAIN THIS

1. Every noteworthy built feature MUST update STATE: user benefit/entry point, actual availability/branch, proof/limits, reusable source and help mapping. Owner phone use may justify 🟢; agent-only phone checks stay explicitly labeled and do not silently become owner LIVE.
2. When a spec is written, add a 🔵 line. Do not write the spec until it is scheduled.
3. When an idea appears it goes in INBOX.md — **not** into a new SPEC_*.md.
4. When a defect is found, add it to section 9 with its severity.
5. Before a new feature, read this cross-wing map and inspect existing engines/adapters. Reuse Studio/VisLab/JoyBrush machinery when appropriate; absence from your local wing is not proof it does not exist elsewhere.
6. Roadmap records broad achievements, handoffs/LEDGER hold working detail, STATE records major discoverable capabilities. Update all affected records at a coherent checkpoint and publish small reviewed patches; no stale whole-file replacement.

> **Joy Brush Note 9 agent check, 2026-10-01:** Open A/B and Recent recovery passed on the phone.
> The safety copy of the owner's drawing matches every original archive entry byte-for-byte.
> Fixed an actual large-drawing snapshot memory crash, coloured-paper decoding and premature GL
> startup uploads. 193 androidkit tests pass and app watcher build is green; updated APK installed.
> PNG with/without paper now passes on the Note 9, including exact pixels across negative tile
> coordinates. Fixed resumed GL work running before EGL was current after the picker. All 194
> androidkit tests pass. Owner drawing restored: all 1713 archive entries (1711 paint tiles) match
> the pre-test backup byte-for-byte. Final in-place install: 2026-10-01 09:30:55.
> Details: `tasks/joybrush/INTEGRATION_20260930.md`.

> **Joy Brush automatic graphics recovery, 2026-10-01:** completed saved artwork now restores
> automatically after runtime EGL loss. Verified on Note 9 with the 1711-tile drawing in the same
> Activity; 205 androidkit tests pass and full app builds pass. Undo history restarts; marks since
> the last completed save may be missing. Owner pre-test archive restored byte-for-byte, normal
> phone operation restored. Next blocker: memory overlap when Open switches two large drawings.

> **Joy Brush large drawing switching, 2026-10-01:** Open/Recent keep the selection compressed until
> the previous drawing is preserved and its snapshot released. 211 androidkit tests and app watcher
> build pass. Installed and verified on Note 9: large-to-large Open and Recent both pass, all selected
> pixels/settings exact; damaged files leave the previous drawing intact. Owner drawing restored,
> all 1713 entries exact. Large swaps still take tens of seconds. Next: frame/cel and host integration.

> **Joy Brush frame foundation, 2026-10-01:** legacy cel GPU stores, all-cel save/load, selected-frame
> PNG and frame-aware undo pass 227 androidkit plus 36 targeted paint tests. Note 9 checks pass for
> copy, isolated edits, undo/redo, linked/blank frames, holds and cold reopen. Latest owner artwork
> restored: all 1835 entries (1833 paint tiles) exact. Whole-layer animation creation shortcut removed
> because JB-3.00a/R50 requires region animation; JB-3.01b/c adaptation is still owed. Backed up on
> codex/frame-projection-foundation, not merged into origin/joy-creator: read-only preflight found a
> board-spec add/add disagreement. Remote paper changes not yet integrated/phone-verified here.
> Shared handoffs: tasks/joybrush/AGENT_BOARD.md. Detailed evidence: INTEGRATION_20260930.md.

> **Region ownership foundation, 2026-10-02:** RegionPaintPlan landed on origin/joy-creator through
> 0a46f811 from isolated codex/region-routing. Pixel-exact shared/frame tile partition, multi-board
> and negative-coordinate edges; 13 new tests. Combined latest-paper core suite: 1458/0; boundary
> sabotage caught, original restored. Runtime helper only: saved region model, GPU commit/undo,
> preview/export adapters and phone proof still owed. Primary tree's divergent spec is preserved.
Details: tasks/joybrush/INTEGRATION_20260930.md.

> **Saved region model, 2026-10-02:** origin/joy-creator 82a16eba adds v6 board cursors and per-layer shared/region cels. Core 1470/0 and Android file tests 225/0 before latest clean paper merge. Blank/copy/link operations return bounded pixel instructions; GPU/undo/preview/export adapters and phone proof remain owed. Existing test art is disposable; no conversion work.

> **Region CPU rendering, 2026-10-02:** origin/joy-creator 0f4e57cd connects export rendering to board-local frame/shared tile slices, including holds, masks and clip bases. Combined core1481/0, Android file/export225/0; no device proof. GPU paint/undo adapter remains next. Shared AGENT_BOARD.md now published on origin.
