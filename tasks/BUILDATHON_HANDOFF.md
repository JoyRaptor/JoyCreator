# FadCam/Faditor — Autonomous Build-a-thon Handoff Prompt (2026-06-21)

Paste everything below the line into a fresh session to continue nonstop autonomous building.

---

You are continuing autonomous development on **FadCam/Faditor** (Android video editor, `C:\+Projects\Screenrecorder\FadCam`, package `com.fadcam.beta`). Work UNINTERRUPTED in a self-paced `/loop` build-a-thon: pick the next task, build it, verify, document, and immediately start the next. Never wait for me. When a decision is needed, pick the most reasonable option, implement it, and record it. Keep iterating until credits run out.

## Read first (in order)
1. `tasks/HANDOFF.md` — top "★★★ 2026-06-21 LATEST STATE" summary (what's done).
2. `tasks/FEEDBACK_20260621.md` — the live, prioritized backlog with EVERY loose end + the user's exact wording. **This is your task list.**
3. `tasks/PLAN_studio_drawers_redesign.md` — visualizer/transitions drawer redesign details + verification checklist.
4. `tasks/PLAN_asset_browser_v2_layers.md` — the LAYERS / multi-track design (the big one).
5. `tasks/RECORDING_HANDOFF.md` — recording pipeline + webcam rotation; `docs/project-schema.md` — project model.

## Build / verify loop (you CANNOT run Gradle)
A watcher runs in PowerShell: `.\gradlew.bat -t installDefaultDebug 2>&1 | Tee-Object build.log` — it rebuilds+installs on every save. Check compile results with `tr -d '\000' < build.log | tail -n 40` (UTF-16). Grep for `error:`, FIX failures, re-check. Compile is verifiable from build.log even if the device drops. **ALWAYS-GREEN: never end a chunk with the tree not compiling** — revert rather than leave it broken.

## Device verification (device is reachable now!)
ADB: `C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe`, device `REAL_SERIAL` (SM-N986U, 1440×3088). Use `MSYS_NO_PATHCONV=1` for `/sdcard` paths. Screenshots: `exec-out screencap -p > tasks/_x.png`, Read it, then delete temp files.
**Editor is `exported="false"` — navigate via UI:** `monkey -p com.fadcam.beta -c android.intent.category.LAUNCHER 1` → tap the **FadCam top tab** → ignore the "continue?" dialog (its Continue button is broken) → tap the **4th-from-left bottom-nav icon** (~`(840,2915)`) = Faditor → it opens the last project in `FaditorEditorActivity`. Play button ~`(720,2217)`; bottom toolbar ~y2885. Screenshots are downscaled — tap in DEVICE pixels (use `uiautomator dump /sdcard/ui.xml` for exact bounds).

## ORDER OF WORK
### A. Loose ends first (all in FEEDBACK_20260621.md — small, finish these)
1. **Caption "Hidden" pill misaligned** — eye-slash chip a few px too low, clips text pills. Fix in `setupCaptions`: icon chip `setIncludeFontPadding(false)` + `setGravity(CENTER)` + match metrics; row `gravity=center_vertical`.
2. **Audio volume + VOLUME KEYFRAMES** (P1, not started — see FEEDBACK for full spec): whole-track volume; long-hold Volume → grey→green stopwatch keyframe-mode toggle; volume edits write keyframes → linear fades; **blue rubber-band envelope** drawn keyframe-to-keyframe on the audio clip in the timeline; apply interpolated gain on playback + export.
3. **Visualizer Rolodex full redesign** — thumbnails are DONE; build the 3-column layout (left toggle icons · centre thumbnail carousel · right gradient carousel), the weighted 3D "settle" scroll, compact ~⅓ height, grab-bar + swipe-up/tap-video dismiss (drop title/X).
4. **GL transition REAL previews** — cards currently animate a category-PROXY (stopgap). Make each render its actual shader (shared GLSurfaceView card-renderer, or pre-bake thumbnail sprites). Shaders are in `assets/gl_transitions/`.
5. **Captions tap-for-properties** — tap a caption → properties drawer like the visualizer (tap=props, long-hold=delete); eventually the same Rolodex treatment. Also highlight the active caption-style chip as the playhead crosses clips.
6. **Manual transcript word-edit** — long-press a word in the transcript panel → edit dialog → replace text (manual companion to the new `correct_transcript` AI tool).

### B. Verify on device the round-1/2 fixes (confirm, fix if off)
Captions clip-aware seam-crossing; transition drag now previews (green seam) + lands on the nearest seam (panel collapses on drag-start); trash deletes a SELECTED transition not the clip; audio-track no longer overlaps the inline transcript.

### C. Then the BIG stuff (main tasks, mostly untouched)
1. **LAYERS + nested groups / pre-compose** — THE keystone. Multi-track timeline: video, narration, music, captions, visualizers each on their own scrollable row; drag-drop between layers; captions + visualizers become first-class cuttable layer objects with per-cut props; nested groups (After-Effects pre-compose). See `PLAN_asset_browser_v2_layers.md`. This unblocks most of the user's pain (manual "render intro → new project → combine" workflow).
2. **Dual-stream recording** (`feature-dual-stream-recording-spec.md`, `RECORDING_HANDOFF.md`).
3. **Transcript windowing** migration (`PLAN_transcript_windowing.md`).
4. **Webcam-landscape auto-rotation** — manual rotate/mirror controls already ship; auto was removed (180° artifact). Revisit only with careful landscape recording tests.

## Hard rules
- NEVER commit. No code comments unless idiomatic to surrounding code.
- AI mutates projects ONLY via validated EditScripts; never persist cache/remux paths in project JSON (store original source URI).
- After each chunk: update `tasks/FEEDBACK_20260621.md` (+ the relevant plan doc) with what you did and what's left, then immediately start the next chunk.
- User is a non-programmer creator: UX must be clear; he loves visual feedback (badges, previews, live thumbnails) and an "invisible UI" ethos (controls that fade, auto-hide, don't clutter recordings).

## What's already DONE this session (don't redo)
Captions clip-aware + `correct_transcript` AI tool + per-clip "Hidden" pill; transition drag seam-snap + preview + scroll-to-landed; trash-deletes-transition; audio/transcript overlap fix; GL transitions as individual 2nd-row cards + external `.glsl` auto-populate (with params) + category-proxy previews; mirror-wipe = horizontal bar; transitions drawer covers top bar + swipe dismiss; visualizer non-blocking drawer + colour/gradient swatches + sensitivity + Save-to-folder + auto-load + covered-span extraction + style THUMBNAILS; webcam manual rotate/mirror + fades + soft-gradient + auto-dismiss; A/B panther/camel preview frames.
