# Quick Wins — small suggestions adopted from the AI-feedback reviews (2026-07-02, USER-APPROVED)

> Provenance: [M]=Minimax 3, [D]=DeepSeek v4, [BP]=Big Pickle. Every claim is VERIFY-THEN-FIX
> (the reviews predate Layers; line numbers may have drifted; confirm each before changing).
> Tier plan approved by user 2026-07-02: Tier 1 = durability/leak pass; Tier 2 = portability package
> (after M-EXPORT-1); Tier 3 = AI upgrades; Tier 4 = plugin folder P0 + templates + presets pattern.

## A. Fold into the TIER-1 durability/leak agent (one pass, verify each first)
1. [BP] faditor_audio extracted-audio lives in getCacheDir → OS can wipe it; copy into project assets. **P0-adjacent.**
2. [BP] transitionFrameCache (Bitmap HashMap) → LruCache + clear in onDestroy.
3. [BP] ChatAssistantActivity static messageLog grows forever → ring buffer.
4. [BP] AssetScanner sequential MMR per file → small thread pool (~4).
5. [M]  MMR-on-UI-thread call sites → move to executor; cache clip width/height after first read.
6. [M]  FLAG_KEEP_SCREEN_ON set for entire editor lifetime → only during active playback/preview.
7. [M]  Playhead tick keeps firing while paused → stop/idle when not playing.
8. [BP] Timeline fling invalidate() unthrottled → throttle during fling (tiny change).
9. [BP] pcmToFloat allocates ~1.9MB per 30s chunk → pooled buffer.
10.[BP] Photo capture does 6× glReadPixels with fresh IntBuffers → PixelCopy or one reused buffer.
11.[BP] I-frame interval 1s → 2s default for local recording (configurable).
12.[BP] docs/project-schema.md says v5, code is v8 → regenerate schema docs.

## B. Fold into the REBRAND PASS 1 agent (visual/labels, same sweep)
13.[M] Icon collision: Audio/Volume and Visualizer both use graphic_eq → distinct icons.
14.[M] Split tool "carpenter" icon → content_cut (scissors); clearer.
15.[M/D] "Silence" tool icon+label → "Clean"/"Remove Silence" (auto_fix_high is overloaded).
16.[M] Armed-state color convention: tools that are armed/active tint their icon (e.g. volume keyframes armed) — green/accent per section palette.
17.[M] Delete dead Trim/Heal layout blocks + strings (visibility=gone corpses).

## C. Small standalone feature nuggets (slot opportunistically after P0s)
18.[M] Speed preset chips (0.5× / 1× / 2×) alongside the slider.
19.[D] Speed: audio pitch-compensation toggle (ExoPlayer supports it).
20.[D] Crop: rule-of-thirds grid overlay + numeric ratio entry.
21.[D] Canvas: custom resolution input alongside presets.
22.[M] Caption style content adds: "Meme text" (Impact, white w/ black stroke) + one big-bright kid-friendly style — trivial chips in the existing Rolodex.
23.[M] 9:16 safe-zone overlay toggle (social-app UI overlap guides) in canvas/preview.
24.[M] "Export for low bandwidth" preset (720p/H.264 baseline/low bitrate) — lands WITH the export work package's quality setting.
25.[BP] AI tools: rename_clip / rename_asset ("name my 47 IMG_xxxx clips by content") + describe_clip — cheap, land with Tier 3.
26.[D] Save-as-preset pattern: every tool with a config (filter, text style, speed, captions) gets "save current as preset" — adopt as a standing pattern, implement per-tool as we touch them.

## Explicitly SKIPPED (and why)
- Undo snackbar on every destructive op [M] — redundant: comprehensive undo + long-press history popup already shipped.
- Long-press tool = open properties [M] — collides with edit-mode long-press semantics in the new carousel.
- Fixed "6 primary + overflow" toolbar reorder [M/D/BP] — superseded by the user's pin/divider/recent carousel.
- Auto-transcribe on import with no consent [D] — we ship the ask-dialog; a "just do it silently" settings option can come later.
- On-device LLM, music/video generation, WASM/native plugins, Kotlin/Compose migrations — parked per the tier plan.
