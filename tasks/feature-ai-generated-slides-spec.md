# Feature Spec: AI-Generated Animated Slides (for Claude Code)

**Status:** ALL PHASES IMPLEMENTED (0–4 + addendum + 2026-07-16-late additions), device-verified

## 2026-07-16 late-session additions (JoyRaptor live feedback, all landed + device-proven)

- **Slide time-stretch (trim = remap).** A slide's trim bars stretch/squeeze the authored
  animation to exactly fill the clip window (trim ceiling `SLIDE_MAX_DURATION_MS` = 30s), instead
  of cutting frames like video. The stretch/freeze mapping is BAKED into the rendered MP4
  (`SlideRenderer.mapSourceToAnimMs`; render covers source 0..outPoint), so playback, export and
  transitions need no special handling. Renders are keyed per trim-state
  (`SlideCache.mp4ForState`, `GeneratedSource.renderStateHash`) — two clips sharing one authored
  HTML (a split slide) bake to distinct files (first device run clobbered them — fixed).
  `Clip.repointGeneratedSlideSource` re-aims the clip after each bake; stale bakes pruned.
  NOTE: split slide pieces each play the WHOLE animation squeezed into their own window.
- **Inner freeze-zone handles (slides only).** Selected slide shows two inner markers (▶/◀):
  drag in from the trim bars to hold the first frame / last frame for a zone, animated middle
  stretches between (`GeneratedSource.freezeStartMs/freezeEndMs`). Live in
  EditorTimelineView (`FREEZE_*_HANDLE` drags → `onSlideFreezeChanged` → re-render).
- **Code editor.** Double-tap a slide (timeline clip or the live preview) → `SlideCodeBottomSheet`:
  view/edit the HTML, Copy all / Paste & replace / Apply → contract-validate → rewrite →
  re-render in place (same clip id, one undo step). Timeline double-tap on slides no longer
  opens the (useless, silent) audio shelf.
- **True-duration seek rescale** (`faditor_runtime.js`): models sometimes register a durationMs
  shorter than the timeline they built — end froze early / scrub never reached the last state.
  Seeks now rescale onto `tl.totalDuration()`. Renderer rev bump (`r2`) re-bakes existing slides.
- **Capture timeout scales with frame count** (`SlideCaptureEngine.PER_FRAME_BUDGET_MS`) — the
  old flat 60s cap made every stretched-slide render time out (the "Preparing 1 slide…" hang).
- **Relative images**: slide HTML now loads via `file://` from its own slides/ dir (runtime JS
  copied alongside, `SlideFiles.ensureRuntimeIn`), so `<img src="pic.jpg">` next to the HTML
  resolves; data-URI images/fonts and inline SVG explicitly allowed + taught in the external
  prompt. Validation now allows nested GSAP timelines (exactly one `Faditor.register`).
- **Double-transition clamp fix (ExportManager):** two transitions straddling one short clip
  (crossfade in + radial out on a 609ms slide sliver) jointly overspent it — the later one was
  degenerate-skipped in export while preview drew both (JoyRaptor's "radial in preview, not in
  export"). `transitionBudgetOnClip` now splits the clip proportionally; device-verified
  (`resolved=Radial durationMs=304` in the 23:16 export).
- **Slides skipped by tape audio analysis** (silent by construction; the 30s trim ceiling made
  the analysis span uncoverable → infinite "analyzing audio" churn).

## Phase status (2026-07-16 session)

- [x] **Phase 0 — Capture pipeline proof.** Built 2026-06-19 (`slides/` package: SlideRenderActivity — adb-triggerable, see its javadoc — SlideCaptureEngine, SlideEncoder, SlideCache, SlideFiles, SlideContract; `assets/faditor/` gsap.min.js + faditor_runtime.js + sample_slide.html). **DEVICE-PROVEN 2026-07-16** (first-ever run, sandbox <note9-serial>, behind a locked keyguard): sample slide 1080×1920@30fps → 87 PNG frames → MP4, ffprobe 2.9s/87 frames + silent AAC, frames visually correct (animation tracks time, title centered). Two real bugs found+fixed on the way: (1) Chromium pauses rendering while the host activity is stopped → every frame captured blank white behind the keyguard; fixed with setShowWhenLocked/turnScreenOn + webView.onResume/resumeTimers + `postVisualStateCallback` frame sync (raced with a 250ms fallback). (2) **This ffmpeg-kit build has NO `libopenh264`** — the June assumption was wrong; the `mpeg4` fallback is the effective encoder (fine: the slide MP4 is an intermediate Media3 re-encodes). The adb entry now takes `--es encode_mp4 <path>` to produce the MP4 in one invocation. Silent AAC track note still applies (Media3 rejects video-only items preceding items with audio).
- [x] **Phase 1 — Schema + EditScript plumbing.** Landed as schema **v5** (the repo's version counter had moved past the spec's assumed v3→v4; same shape as §3). `ADD_GENERATED_SLIDE`/`REGENERATE_SLIDE` live in EditScript/EditScriptApplier. **The export pre-pass** (`ensureGeneratedSlidesRendered`) was the missing piece until this session: it now lives in `slides/SlideRenderer` and runs **in the editor process** at export kickoff (`FaditorEditorActivity.startOutOfProcessExport`), NOT in `ExportManager` — the exporter runs in the separate `:export` process, which can't host the WebView capture (one WebView data dir can't be shared across processes, and SlideCaptureEngine's completion latch is in-process). The content-addressed cache file is shared filesystem, so the exporter just reads the MP4 the editor materialized. Slides also render opportunistically in the background on project load and after import, so play-through/thumbnails work pre-export. Generated slides are exempt from the missing-media relink gate.
- [x] **Phase 2 — Live preview.** `GeneratedSlideView` wired into scrubbing (`showSlidePreview`/`hideSlidePreview`); play-through uses the rendered MP4 via the normal player path.
- [x] **Phase 3 — Real AI authoring.** `generate_slide` in AIToolExecutor (OpenRouter call, validate→retry-once→built-in fallback per §5.2).
- [x] **Addendum — API-less "copy a prompt" path** (this session). `SlideContract.CONTRACT_VERSION`/`buildExternalPrompt()` (embeds `faditor-slide-contract v1` marker, asks the model to echo it); AddAssetBottomSheet → "AI slide (animated)" → `SlideImportBottomSheet` (Copy slide prompt / Paste slide HTML / Import .html file); `FaditorEditorActivity.importSlideHtml()` validates against the same contract, parses the authored duration from `Faditor.register(...)`, and emits the same `ADD_GENERATED_SLIDE` EditScript the in-app tool uses, then background-renders.
- [x] **Phase 4 — Overlay / transparent mode.** DONE + DEVICE-PROVEN 2026-07-16 ~23:15.
  `TextOverlayItem.generatedSource` (persisted), PNG-sequence render
  (`SlideRenderer.renderOverlayOne` → `slide_cache/<hash>_<state>/frame%04d.png`), export
  compositing (`CompositeExportOverlay.generatedOverlayFrame` — full-canvas draw with opacity,
  per-overlay frame reuse), live preview (a `GeneratedSlideView` WebView per overlay inside
  `TextOverlayLayer`, playhead-driven with the same stretch mapping), `ADD_GENERATED_SLIDE`
  overlay branch (`applyAddGeneratedOverlay`, needs `startMs`), `generate_slide` overlay mode
  un-forced (placement.startMs). Proof: fallback lower-third applied via ApplyEditsActivity to
  cebc19e0 at 500–3500ms → PNG seq baked → visible over the video in live preview AND in the
  exported MP4 frames at 1.5s/2.4s (transparency intact). Overlay stretch = animation fills the
  overlay's whole start..end range; no freeze handles for overlays (v1). Import UI (paste/file)
  still creates fullscreen slides only — overlay creation is via AI tool / EditScript for now.
**Owner of this spec:** written by Claude (chat) at the user's request, to be executed by Claude Code
**Depends on:** `tasks/HANDOFF.md`, `docs/project-schema.md` (schema v3 as of this writing)

---

## 0. Read First

Before touching any code:
1. Read `tasks/HANDOFF.md` in full — current state, constraints, and lessons learned.
2. Read `docs/project-schema.md` in full — this spec adds to it.
3. Open `FaditorProject.java`, `Clip.java`, `EditScript.java`, `EditScriptApplier.java`, `AIToolExecutor.java`, and `ExportManager.java` and confirm the actual current field/method names. **This spec proposes names and shapes based on the handoff doc; if real source has diverged, trust real source for naming, but keep the architecture decisions in Section 2 — they're load-bearing.**

The user is not a programmer. Don't ask them to test intermediate states that require reading code or logs — each phase below has a done-when check that's either fully automatable or reducible to "one visual glance at a video."

---

## 1. Goal (Plain English)

Today, AI editing in this app produces edits via EditScripts — cuts, speed changes, mutes, basic text overlays. It works but looks "rudimentary." We want the AI to be able to design and insert **fully custom animated slides** — chapter cards, stylized titles, animated lower-thirds — authored as HTML/CSS/JS (a medium any frontier LLM is genuinely good at), previewed live and scrubbable inside the editor exactly like a normal clip, and **rasterized to real video frames at export time** so the final output is a normal video file with no runtime dependency on a web view.

End-state user experience (north star, not all built in this spec): the user records a 50-minute lecture, opens AI chat, and types something like *"cut all the silence and filler, organize the clips to best convey the argument, design animated chapter cards, and pull in appropriate b-roll from my asset folder."* The AI calls a sequence of existing and new tools to do all of that. **This spec only covers the chapter-card / animated-slide piece.** Clip reordering and content-aware b-roll matching are separate future specs — don't build them here (see Section 8).

---

## 2. Architecture Decisions (Locked — don't re-litigate these)

1. **Reuse `Clip` for fullscreen slides.** A rendered slide ends up as a normal small MP4 file. Point `sourceUri` at it. The slide is then a clip like any other — trims, speed, transitions, timeline rendering, export composition all work with zero changes elsewhere. We add one optional metadata field (`generatedSource`) so the app knows it's AI-authored and regenerable.

2. **Overlay-style slides (transparent, composited over existing video) extend the overlay model, not `Clip`.** H.264 has no alpha channel, so these render to a cached **PNG sequence**, not a video file, and composite through the same code path that already draws text/image overlays onto frames.

3. **Animation must be deterministic and seekable, not wall-clock-driven.** This is the single most important decision in this spec. We standardize on **GSAP** (gsap.com) as the bundled animation engine because its timeline object has built-in `.seek(seconds)` / `.pause()`. We vendor the official `gsap.min.js` locally (no CDN) and write a small bootstrap (`faditor_runtime.js`, given in Appendix A) that every AI-authored slide must call into. The host app drives time by calling `Faditor.seek(ms)` via `WebView.evaluateJavascript()` — both for live scrubbing in the editor and for frame-stepped capture at export. AI-authored HTML must never use `setInterval`, `requestAnimationFrame`, `Date.now()`, or auto-running CSS animations for visual state.

   *Licensing note:* GSAP became free for commercial use (including all previously-paid plugins) in April 2025 under Webflow's standard license. The one carve-out is tools that let end users visually build animations without code, in competition with Webflow's builder. We're not doing that — the AI writes the GSAP code, the user never touches it — so this is clean. Don't redistribute a modified `gsap.min.js`; vendor it verbatim.

4. **Capture via a headless Activity, reusing the pattern you already built for `ApplyEditsActivity`.** Android WebViews render unreliably when never attached to a real window. Rather than fighting that, add a small internal (non-exported) `SlideRenderActivity` that hosts a real WebView in a real window, off-screen or invisible, and finishes itself when capture completes. This is the same headless-activity trick already proven in this codebase.

5. **Cache rendered output, keyed by content hash + dimensions + duration.** Never re-render a slide that hasn't changed. Cache lives under the project's own directory, not app-wide, so it travels with the project and is easy to nuke.

6. **`generatedSource` carries the *recipe* (HTML file + params), not the source of truth for rendering.** The rendered file (`renderCacheUri` / `renderSequenceDir`) is allowed to be missing or stale — same principle as the existing relink lessons learned ("never persist cache/remux paths as the thing that matters"). If the cache is missing, the app just re-renders from the HTML before export.

7. **No `addJavascriptInterface` / JS-to-Java bridge needed.** Communication is one-directional, Java calling into JS (`evaluateJavascript`), so there's no exposed bridge surface to secure. Keep it that way.

8. **AI-authored HTML must be fully self-contained and offline.** No CDN links, no remote fonts, no `fetch`/`XMLHttpRequest`. This is both a determinism requirement (cache key must mean something) and a robustness requirement (editing happens with the device possibly offline).

---

## 3. Schema Additions

Bump `schemaVersion` **3 → 4**. All new fields nullable / default-empty for backward compat, per existing convention.

### 3.1 `Clip` object — new optional field

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `generatedSource` | object\|null | no | null | Present only if this clip is an AI-authored slide. See below. |

### 3.2 `Generated Source Object` (new, referenced from `Clip` and `TextOverlay`)

| Field | Type | Description |
|---|---|---|
| `kind` | string | `"html_slide"` (only value for now). |
| `mode` | string | `"fullscreen"` or `"overlay"`. |
| `htmlUri` | string | `file://` URI to the authored HTML, stored under `<project dir>/slides/<id>.html`. **This is the source of truth.** |
| `contentHash` | string | sha256 of the HTML file + width + height + requested durationMs. Cache key. |
| `renderCacheUri` | string\|null | `file://` URI to the rendered MP4 (fullscreen mode). Regenerable — may be null/stale. |
| `renderSequenceDir` | string\|null | `file://` URI to the rendered PNG sequence directory (overlay mode). Regenerable. |
| `authoredDurationMs` | long | Duration the slide's own GSAP timeline was authored for (from `Faditor.register`). |
| `styleHint` | string\|null | The style direction given to the AI, kept for "regenerate" requests. |
| `sourceModel` | string\|null | OpenRouter model id that authored this slide, for debugging. |

### 3.3 `Text Overlay` object — new optional field

| Field | Type | Required | Default | Description |
|---|---|---|---|---|
| `generatedSource` | object\|null | no | null | Same shape as 3.2, `mode` will be `"overlay"`. When present, this overlay renders from `renderSequenceDir` frames instead of static `text`/`imageUri`. |

### 3.4 EditScript — new operation types

- `ADD_GENERATED_SLIDE` — `{mode, title|text, styleHint, durationMsHint, startMs, insertAtClipIndex?}`. For `mode: "fullscreen"` this inserts a new `Clip` at the given index/position. For `mode: "overlay"` this adds a `TextOverlay` spanning `startMs`→`startMs+durationMsHint`.
- `REGENERATE_SLIDE` — `{clipId|overlayId, newStyleHint?}`. Re-runs generation, replaces `generatedSource`, invalidates cache.

### 3.5 Version history entry to add

```
### v4 (date of implementation)
- Added `generatedSource` to Clip and TextOverlay for AI-authored animated
  HTML slides (fullscreen chapter cards + transparent overlay slides).
- Added ADD_GENERATED_SLIDE and REGENERATE_SLIDE EditScript operations.
```

---

## 4. New Code

New package: `com.fadcam.ui.faditor.slides`

| Class | Responsibility |
|---|---|
| `GeneratedSlideView` | Thin `WebView` wrapper for **live preview**. Loads the slide HTML once via `loadUrl(htmlUri)` (file-based, so relative `<script src="...">` to the vendored runtime resolves). Exposes `seekTo(long ms)` (calls `Faditor.seek(ms)` via `evaluateJavascript`) and `setOnReadyListener(Runnable)` (fires from `WebChromeClient.onReceivedTitle` when title becomes `"FADITOR_READY"`). |
| `SlideRenderActivity` | Headless, `android:exported="false"`. Hosts a real (but invisible/off-window-focus is fine, just attached) WebView at the requested pixel dimensions, drives `Faditor.seek()` frame-by-frame at the export framerate, captures each frame via `view.draw(canvas)` on an `ARGB_8888` bitmap (set `webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)` first — hardware-accelerated WebViews don't reliably hand you pixels via `draw()`), writes PNGs to a temp dir, finishes itself, returns the dir via result intent — mirror the existing `ApplyEditsActivity` request/result pattern. |
| `SlideCaptureEngine` | Orchestrates `SlideRenderActivity`, manages the wait-for-paint-before-capture timing (don't trust the `evaluateJavascript` callback alone — post twice via `Choreographer`/`postDelayed` to be safe before capturing each frame; verify empirically in Phase 0). |
| `SlideEncoder` | PNG sequence → MP4 for fullscreen mode, using the same ffmpeg invocation path already used in `ExportManager` for Clean Audio v2 (`-framerate {fps} -i frame%04d.png -c:v ... -pix_fmt yuv420p out.mp4`). For overlay mode, no encode step — PNG sequence is the final cached artifact. |
| `SlideCache` | `get(contentHash)` / `put(contentHash, file/dir)` under `<project dir>/slide_cache/`. |
| `SlideContract` | Holds the prompt template (Section 5) and the HTML validation pass (Section 5.2). |

### 4.1 Touch points in existing files

- **`FaditorProject.java` / `ProjectStorage.java`** — schema v4 fields, safe defaults for v≤3 load.
- **`Clip.java`** — add `generatedSource` field + deep-copy preservation (same lesson as the duck/zoom bug already fixed once for relink — don't repeat it).
- **`EditScript.java` / `EditScriptApplier.java`** — new op types from 3.4.
- **`AIToolExecutor.java`** — new tool `generate_slide` (Section 5).
- **`ExportManager.java`** — new pre-pass `ensureGeneratedSlidesRendered(project)` called at the start of export, before composition: walk clips/overlays with non-null `generatedSource`, check `contentHash` against cache, render via `SlideCaptureEngine`+`SlideEncoder` if missing/stale, persist the resulting `renderCacheUri`/`renderSequenceDir` back into the in-memory project before composition proceeds. Overlay compositing: find the existing code path that draws static text/image overlay bitmaps onto export frames, extend it to also accept a time-indexed PNG sequence source.
- **`FaditorEditorActivity.java` / `EditorTimelineView.java`** — for fullscreen slides, the timeline thumbnail/preview just works once `sourceUri` points at a real file. Before a render exists (or always, for snappier feedback), swap in `GeneratedSlideView` at the clip's screen position during scrubbing, fed by `seekTo()` as the playhead moves. For overlay slides, find wherever static text overlays are currently drawn live in the player and extend it the same way.

---

## 5. The Slide-Generation Contract (hardcode into `generate_slide`)

This is the piece that makes "hook any decently competent model through OpenRouter and have it just work" actually true — it's a strict enough contract that model quality matters less than instruction-following, which most current frontier and mid-tier models handle fine.

### 5.1 System prompt template

Fill in the `{{...}}` placeholders per-call, send as the system message, user content is just the title/text. Tell the model to return **raw HTML only.**

```
You are an animation engineer generating a single self-contained HTML file
for a mobile video editor named Faditor. Your output becomes one short
animated clip ("slide") that gets rasterized into real video frames.
Follow these rules exactly.

OUTPUT FORMAT
- Return ONLY raw HTML. No markdown code fences, no explanation, no
  commentary before or after the HTML.
- Reference exactly these two local scripts, in this order, nothing else
  external:
  <script src="gsap.min.js"></script>
  <script src="faditor_runtime.js"></script>
- No other external resource of any kind: no CDNs, no Google Fonts links,
  no remote images, no fetch/XMLHttpRequest/WebSocket. The renderer may be
  fully offline.

CANVAS
- The stage is a div with id="stage" sized exactly {{WIDTH}}x{{HEIGHT}}
  pixels. Fill it edge-to-edge. Use fixed pixel values, not vw/vh.
- Mode is "{{MODE}}". If "overlay": <body> and #stage background MUST be
  transparent so this composites over existing video. If "fullscreen":
  design a complete background — it fully replaces the video frame for its
  duration.

TIMING — THE MOST IMPORTANT RULE
- Build exactly one GSAP timeline. Never use setInterval, setTimeout,
  requestAnimationFrame, infinite/auto-running CSS animations, or
  Date.now() to drive visual state. The host app owns time: it will call
  Faditor.seek(ms) with arbitrary, possibly out-of-order timestamps. Your
  animation must look correct at any single timestamp, not just when
  played start-to-finish.
- When your timeline is fully built, call exactly once:
  Faditor.register(timeline, durationMs);
  where durationMs is your own authored length (aim for roughly
  {{DURATION_HINT_MS}}ms, exact precision not required — author for what
  looks good).
- The host app may hold your final frame longer than your authored
  duration, or cut you off early. Design an ending that looks fine frozen.

CONTENT
- Requested content: "{{TITLE_OR_TEXT}}"
- Style direction: "{{STYLE_HINT}}"
- Fonts: system-safe only (-apple-system, system-ui, Arial, Georgia,
  monospace) or whatever @font-face the runtime already declares. Do not
  @import or link any other font.

SKELETON TO FOLLOW
<!doctype html><html><head><meta charset="utf-8">
<style>
  html,body{margin:0;padding:0;background:{{BG}};}
  #stage{width:{{WIDTH}}px;height:{{HEIGHT}}px;position:relative;overflow:hidden;}
  /* your CSS here */
</style></head>
<body>
<div id="stage">
  <!-- your markup here -->
</div>
<script src="gsap.min.js"></script>
<script src="faditor_runtime.js"></script>
<script>
  const tl = gsap.timeline({ paused: true });
  // tl.from(...).to(...) etc.
  Faditor.register(tl, /* durationMs */ {{DURATION_HINT_MS}});
</script>
</body></html>
```

The tool fills `{{WIDTH}}`/`{{HEIGHT}}` from the project's `canvasPreset` (e.g. 1080×1920 for `9:16`), `{{BG}}` to `transparent` for overlay mode or a sensible default for fullscreen, `{{MODE}}`, `{{TITLE_OR_TEXT}}`, `{{STYLE_HINT}}`, `{{DURATION_HINT_MS}}`.

### 5.2 Validation pass before accepting a response

Reject and retry once (append a one-line correction to the prompt: *"Your previous output violated: {reason}. Fix and resend, raw HTML only."*) if any of:
- Doesn't contain `Faditor.register(`
- Doesn't contain exactly one `gsap.timeline(`
- Contains any of: `fetch(`, `XMLHttpRequest`, `setInterval(`, `setTimeout(`, `requestAnimationFrame(`, `Date.now(`, `<script src="http`, `@import url(http`

If it fails validation twice, fall back to a built-in minimal template (plain centered text, fade in/hold/fade out) so a bad model response never breaks the user's project — this fallback should ship in Phase 0 (Appendix A) regardless of AI involvement.

### 5.3 Tool definition (for `AIToolExecutor`)

```
generate_slide(mode: "fullscreen"|"overlay", title_or_text: string,
               style_hint: string, duration_ms_hint: number,
               placement: { startMs, insertAtClipIndex? } )
  → writes <project>/slides/<uuid>.html
  → emits ADD_GENERATED_SLIDE EditScript op
  → returns clipId/overlayId
```

Tool description (what the top-level orchestrator model sees) should make clear this produces a *designed, animated* visual, distinct from the existing plain `ADD_TEXT_OVERLAY`, so a casual instruction like "design animated chapter cards" reliably routes here instead of to plain text overlays.

---

## 6. Implementation Phases

Each phase has a done-when check Claude Code can verify itself via `assembleDefaultDebug`, `compileDefaultDebugJavaWithJavac`, and adb, per the existing workflow in `HANDOFF.md` §1. Only Phase 0's final check needs a human glance at a video.

### Phase 0 — Capture pipeline proof (no schema, no AI)
- Build `SlideRenderActivity`, `SlideCaptureEngine`, `SlideEncoder`.
- Vendor `gsap.min.js` (official minified build) and write `faditor_runtime.js` (Appendix A) into `app/src/main/assets/faditor/`.
- Use the sample HTML in Appendix B as a fixed test fixture.
- Add a headless ADB-triggerable entry point (mirror `ApplyEditsActivity`'s pattern) that runs: render Appendix B's HTML → produce one MP4 at a known path.
- **Done when:** `adb pull` of the output MP4 plays and visually matches what the same HTML shows in a desktop browser. This is the one step that needs the user's eyes — everything else in this phase is automatable.

### Phase 1 — Schema + EditScript plumbing (fullscreen only)
- Schema v3→v4: `generatedSource` on `Clip`, `ProjectStorage` defaults for v≤3.
- `ADD_GENERATED_SLIDE` (fullscreen) and `REGENERATE_SLIDE` wired to the Phase 0 pipeline, still using Appendix B's fixed HTML (real AI generation comes in Phase 3).
- `ExportManager.ensureGeneratedSlidesRendered()` pre-pass.
- **Done when:** applying an `ADD_GENERATED_SLIDE` EditScript through `ApplyEditsActivity` (reuse the existing headless test path) inserts a real clip into the project JSON, and a full export of that project contains the rendered slide at the correct position and duration.

### Phase 2 — Live preview
- Wire `GeneratedSlideView` into the editor so scrubbing across a slide clip shows the live, scrubbable WebView (not just the cached render).
- **Done when:** dragging the playhead across a slide clip shows the animation tracking position smoothly and matches the eventually-exported frames.

### Phase 3 — Real AI authoring
- Implement `generate_slide` per Section 5, OpenRouter call, validation, file write, EditScript emission.
- **Done when:** typing "add a chapter card here that says Introduction" in AI chat produces a working animated slide with zero manual HTML.

### Phase 4 — Overlay / transparent mode
- Extend `TextOverlay` with `generatedSource`, PNG-sequence capture + compositing in `ExportManager`, live overlay preview.
- **Done when:** an animated lower-third composites correctly over existing video, transparency intact, in both live preview and final export.

---

## 7. Constraints Carried Over From `HANDOFF.md` (don't drop these)

- AI still only mutates the project through validated EditScripts — never directly.
- Never add code comments unless explicitly asked.
- Never commit unless explicitly asked.
- Run the compile check after each phase: `.\gradlew.bat compileDefaultDebugJavaWithJavac --no-daemon`.
- `renderCacheUri`/`renderSequenceDir` follow the same rule as remuxed media paths: never treated as source of truth, always regenerable from `htmlUri`.
- Update `tasks/HANDOFF.md` and `docs/project-schema.md` after each phase, same documentation discipline already established in this project, so a fresh session can pick up cleanly.

---

## 8. Explicitly Out of Scope Here

Two pieces of the user's original end-to-end vision are **not** covered by this spec and shouldn't be started alongside it:
- **Clip reordering for narrative strength** — needs a `REORDER_CLIPS`/`MOVE_CLIP` EditScript op plus a transcript-chunking/scoring AI pass. Separate spec.
- **Content-aware b-roll selection** — v1 can work off filenames/folder names already surfaced by `AssetScanner` plus transcript context, no new infra needed; true semantic matching (vision-tagged assets) is a later v2. Separate spec.

---

## Appendix A — `faditor_runtime.js` (write this verbatim, it's original/small, not GSAP itself)

```javascript
window.Faditor = (function () {
  let tl = null;
  let durationMs = 0;
  function register(timeline, totalDurationMs) {
    tl = timeline;
    durationMs = totalDurationMs;
    tl.pause(0);
    document.title = "FADITOR_READY";
  }
  function seek(ms) {
    if (!tl) return;
    tl.pause();
    tl.seek(ms / 1000, false);
  }
  function getDuration() {
    return durationMs;
  }
  return { register, seek, getDuration };
})();
```

Listen for readiness in `GeneratedSlideView` / `SlideRenderActivity` via `WebChromeClient.onReceivedTitle(view, title)` firing with `"FADITOR_READY"` — no JS bridge object needed.

## Appendix B — Sample slide HTML (fixed test fixture for Phase 0, and the hard-fallback template for Section 5.2)

```html
<!doctype html><html><head><meta charset="utf-8">
<style>
  html,body{margin:0;padding:0;background:#0b0e14;}
  #stage{width:1080px;height:1920px;position:relative;overflow:hidden;
         display:flex;align-items:center;justify-content:center;}
  #label{font-family:-apple-system,system-ui,Arial,sans-serif;
         font-size:64px;color:#f5f5f0;font-weight:600;opacity:0;
         transform:translateY(30px);}
</style></head>
<body>
<div id="stage"><div id="label">Sample Chapter Title</div></div>
<script src="gsap.min.js"></script>
<script src="faditor_runtime.js"></script>
<script>
  const tl = gsap.timeline({ paused: true });
  tl.to("#label", { opacity: 1, y: 0, duration: 0.6, ease: "power2.out" })
    .to("#label", { duration: 1.8 })
    .to("#label", { opacity: 0, y: -20, duration: 0.5, ease: "power2.in" });
  Faditor.register(tl, 2900);
</script>
</body></html>
```

---

## Addendum (JoyRaptor, 2026-07-16): API-less "copy a prompt" path
Not everyone hooks up an API key, and some models (e.g. Claude) aren't reachable via OpenRouter.
Add a **"Copy slide prompt"** button to the slide-add flow: it copies a prompt that teaches ANY
external chatbot the slide contract (single self-contained HTML file, fixed duration, animation
purely a function of time — the same contract Section 2's renderer consumes). The user pastes the
AI's HTML back via a **paste/import entry point** that feeds the exact same HTML→MP4 render
pipeline the in-app AI path uses. Two additions, no architectural change. The prompt text should
embed the contract version so future renderer changes can detect stale prompts.
