# SPEC_20260831_CAPTION_SLIDES_UX — caption track pills, wire/import, truncate, fit-tab consolidation

> **Lane:** `SPEC_20260831_CAPTION_SLIDES` (claim in LANES.md before editing)
> **Parent features:** FIX_20260831_CAPTION_LAYER_SWITCHING + FEATURE_20260831_CAPTION_SLIDES (todo.md)
> **Contended file etiquette:** `FaditorEditorActivity.java` is shared with site-scoped ACTIVE lanes
> (AUDIO_SYNC_TRUTH: 4 transport sites; MEDIA_IMPORT: showInternalAssetPicker; FADE_KNOBS: text fade
> undo branch; WORD_SYNC_V2: word drawer). This spec owns ONLY the caption drawer / preview
> container / caption style-fit sites. Never touch transport, playhead, asset picker, fade-undo,
> or word-scrub code.

## 0. JoyRaptor's request (verbatim)

> One of the issues I have is that it seems to conflate all the different three tracks. So if I do
> something to one track, it seems to do it to all. It's not which track I have selected... I
> changed the color, alignment, mode, grow, formatting of one, and it happens to all of them.
> sometimes if i deliet one another deletes!
> Resize handles and that selection should correspond and communicate with the drawer. So you can
> select from the drawer or you can select from the preview window.
> Having three tracks up takes up far too much space... It should be three pills on the same line
> as the header that says "style" or "fit" - right after.
> if theres only one capton transcript theres nothing to wire have a simple + in a round pill
> chip; clicking on it will import timestamped text just like doing it in the transcripts drawer
> has to the video or music object you have selected in the timeline... in the scenerio that the
> selected object already has multipule availible transcripts allow the option to choose form them
> OR import timestamped text directly. in that window have a small ? button... two exampls they
> can copy, kareoke and slide modes.
> anyways the chip pill need green dot, title (show only the first 7-8 characters for space and
> the eye/grey toggle visibility icon. whatever one is selected use the same green as the rest of
> the app.
> in the other tab "all tracks" is ambiguous, am i linking all together (toggle) or do i press it
> whenever i want to sync the styles but they are not perminently linked? is it for all settings
> or just this tabs settings?
> the floor slider dosnt need a whole screenwitth, it can go on the same line as words and max
> lines after them.
> Likewise, grouping can be consolidated. to after or before fit... you can bring up your other
> cycle toggles as well. all into that second row. We can also have the different transcript
> selector tabs. after fit just like they are after style just so that you don't have to go back
> to select a different one. It'll be connected... so you can select in styles trak 2, go to the
> fit tab continue editing, select track 3 and continue tweaking things
> also Instead of side handlebars And corner grips... It should be constrained by width. And it
> shrinks everything down until it all fits in the box. There's your probably also be a check box
> for truncation so that if you absolutely needs to [cut] the text. for both fit and uniform.
> that way you can either have it be a long column that goes off the page or it can be
> considering exactly to the ratio of the box

## 1. Root-cause findings (previous sessions, verified)

1. **Style conflation** — `tweakCaptionStyle` materialized the draft style keyed
   `"customdraft_" + clipId`, so all 3 bindings on one clip shared ONE style object. **FIXED**:
   draft key is now `"customdraft_" + targetId + "_b" + bindingIdx` (FaditorEditorActivity ~20842).
2. **"Delete one, another deletes"** — `TranscriptDedup` grouped imported versions by
   engine+label and ate one import per project load. **FIXED**: engine `"import"` exempt
   (TranscriptDedup.java).
3. **Transcript panel snapping back** — `syncLegacyFromBindings` clobbered a valid
   `activeTranscriptIndex`, and `getActiveNamedTranscript` ignored it. **FIXED** in both
   Clip.java + AudioClip.java (honor valid index; b0 fallback only when unset/stale).
4. **Mojibake** — the 2026-08-30/31 session wrote `FaditorEditorActivity.java` +
   `ProjectStorage.java` double-encoded (UTF-8 read as CP1252, re-saved). Repaired 2026-08-31
   (6,549 conversions, zero markers left, ASCII/structure untouched); both files staged.

## 2. Already landed in the working tree (staged, verified by inspection)

- CaptionStyle.slideGroup + JSON; CaptionPhrases.ofSlides/layoutWords; slide-aware
  CaptionFit.uniformSizeForTranscript; CaptionTextureCache slide raster.
- boxWidthFraction + anchor + justify on CaptionBinding (Clip/AudioClip) incl. copy(),
  ProjectStorage round-trip, edge-drag resize with grip chrome in CaptionOverlayView
  (preview-only, never exported), export renderer consumes width.
- CaptionExportRenderer.drawSlidePhrase (export twin of drawSlide).
- Style tab: tall track list REPLACED by `buildTrackPillsRow` (green dot, ≤8-char label,
  eye toggle, "+" pill) — FaditorEditorActivity ~19116.
- `showWireCaptionTrackDialog`: choose existing transcript OR "Import timestamped text…",
  neutral "?" → `showImportFormatHelp` (karaoke + slide examples, both with Copy).
  `wireAfterImport` auto-wires the fresh import (~35404).
- Fit tab: Floor slider inline on the Words/Max-lines row; Grouping/Grow/Align/Copy-to-all
  cycle chips consolidated on ONE `growRow`; "All tracks" → **"Copy to all"** (one-shot copy,
  toast clarifies).
- Per-binding draft keys (fix #1) + dedup exemption + model index fixes above.

## 3. REMAINING WORK — two non-overlapping packages

### Package A — FaditorEditorActivity.java ONLY (owner: agent A)
1. **COMPILE BLOCKER**: `flp` declared twice in `buildCaptionFitTab` (~19986 and ~19992).
   Delete the second duplicate block; keep one `LayoutParams` assignment.
2. **Fit tab pills**: add `root.addView(buildTrackPillsRow(ctx)); root.addView(makeDivider(d));`
   at the TOP of `buildCaptionFitTab` (before the Fit mode row) — same row the Style tab uses,
   so you can switch tracks from either tab (fix the stale comment at ~20010 while there).
3. **Truncate chip**: 5th chip in `growRow` — text `Truncate: on/off`, green when on, wired via
   `tweakCaptionStyle(s -> s.fitTruncate = !s.fitTruncate)`. Contract: `CaptionStyle.fitTruncate`
   is created by Package B (public boolean, default true, JSON key `"fitTruncate"`).
4. **Verify + complete FIX_20260831_CAPTION_LAYER_SWITCHING leftovers** (staged hunks
   ~31938–32452 and ~34991–35401): (a) playhead tick video branch must NOT hide the shared
   `captionMultiContainer` while audio multi is active, and the audio branch re-shows it;
   (b) `rebuildAudioCaptionOverlays` sets the container VISIBLE (mirror video path);
   (c) `rebuildCaptionOverlays` empty-case respects audio multi. Implement anything missing.
5. **Verify `wireAfterImport` hook (~35404)**: wires the NEWEST engine="import" transcript on
   the SAME target the dialog resolved (audio vs video), not a stale/other clip.
6. **Dead code**: if `buildCaptionTrackListView` / `buildAudioCaptionTrackListView` now have NO
   call sites (rg to confirm), remove them — the pills row replaced them.
7. Do NOT touch todo.md / LANES.md / spec files (coordinator owns those).

### Package B — transcript/* + export/* ONLY (owner: agent B)
Files: `transcript/CaptionStyle.java`, `transcript/CaptionOverlayView.java`,
`transcript/CaptionFit.java`, `export/CaptionExportRenderer.java`,
`compositor/CaptionTextureCache.java`. NEVER touch FaditorEditorActivity.java / model / storage.

1. **CaptionStyle**: `public boolean fitTruncate = true;` + `o.put("fitTruncate", fitTruncate)`
   + `s.fitTruncate = o.optBoolean("fitTruncate", true)` (tolerant read).
2. **Semantics (JoyRaptor)**: truncation applies when a Fit mode is active (fitMode != OFF) for BOTH
   per-cue and UNIFORM.
   - `fitTruncate == true` (default, current look): fit constrains to boxW AND boxH
     (boxH = r.height() * 0.9f as today). At the floor scale, if the wrapped block is still
     taller than boxH, drop trailing lines until it fits and append "…" to the last kept
     line's last word (re-measure that word).
   - `fitTruncate == false`: width-only fit — pass an effectively infinite boxH
     (`r.height() * 8f`) into fitSizeForWords / uniform sizing so ONLY width constrains; the
     column may run off screen. No ellipsis.
3. **Apply at every fit call site so preview == export (§3g)**:
   - CaptionOverlayView: normal karaoke path (draw) + `drawSlide` + the UNIFORM cache helper
     (`getUniformFittedSize` boxH argument). Keep karaoke emphasis intact when truncating.
   - CaptionExportRenderer: `drawPhrase` + `drawSlidePhrase` + its uniform path — same rule,
     same ellipsis helper behavior.
   - CaptionTextureCache: its fit/wrap sites — same rule so the GL preview matches Canvas.
4. **Cache invalidation**: `CaptionOverlayView.setStyle` must treat a `fitTruncate` change like
   a fit change (add it to the `fitChanged` comparison) so the uniform cache re-computes.
5. Mirror the exact truncation algorithm in preview and export (same line-drop order, same
   "…" append) — divergence here is the class of bug this spec exists to kill.

## 4. Contracts (pin — agents must not drift)

- `CaptionStyle.fitTruncate` : `public boolean`, default `true`, JSON key `"fitTruncate"`.
- Drawer pill row: `buildTrackPillsRow(ctx)` — dot `●/○` green/grey, label ≤8 chars, eye
  `visibility/visibility_off` (materialicons), `+` pill green; tap selects + retargets drawer;
  eye toggles binding.enabled.
- Fit tab chip row order: Grouping / Grow / Align / Copy to all / Truncate.
- Fit box height constant when truncating: `r.height() * 0.9f`; width-only multiplier: `8f`.

## 5. Verification protocol (LANES rules 5/6 — binding)

- **Never run gradle.** The watcher is the sole builder. After the last edit, read `build.log`
  (UTF-16) tail for a fresh `BUILD SUCCESSFUL` with mtime > last edit. If the watcher is dead,
  report UNVERIFIED at the top and stop.
- `git add` each edited file IMMEDIATELY after writing (WORKING-TREE HAZARD). Never a bare
  `git commit` — coordinator commits with explicit pathspec only.
- Encoding gate after every edit: no `â`/`Ã`/`Â` mojibake markers in touched files
  (`grep -c 'â'` == 0), files stay UTF-8.
- Device checks are OUT OF SCOPE this round (JoyRaptor drives visual acceptance).

## 6. Acceptance mapping (JoyRaptor's list → where it lives)

| JoyRaptor's item | Status |
|---|---|
| Tracks conflated (color/align/mode/grow/format) | LANDED (per-binding draft keys) |
| Delete one, another deletes | LANDED (dedup import exemption) |
| Drawer ↔ preview selection sync | LANDED (phase 3 retarget + pills) |
| Three pills in tab header line (Style + Fit) | Style LANDED; **Fit = Package A.2** |
| "+" chip → wire existing / import / ? examples | LANDED (verify hook = Package A.5) |
| "All tracks" ambiguity | LANDED ("Copy to all" + toast) |
| Floor slider inline | LANDED |
| Grouping + cycles consolidated (second row) | LANDED (growRow) |
| Width-constrained shrink for slide fit | LANDED (layoutWords wrap) |
| Truncation checkbox (fit + uniform) | **Packages A.3 + B** |
| Resize via side grips (no corner handles) | LANDED (edge-grip width resize) |

## 7. ROUND 2 (2026-08-31 12:50) — selection isolation + header consolidation (JoyRaptor device findings)

### 7.0 Device evidence (project bedd5a6c + prefs caption_custom_styles_v1)
- Model isolation CORRECT: bindings carry distinct drafts `customdraft_<clip>_b0/b1/b2`.
- b2 slideGroup=true, b1 slideGroup=false — the slide toggle landed on the wrong track because
  of the UX bugs below; rendering paths were verified per-binding already.
- CAPMULTI tick log: overlays=3 (three audio overlay views stacked).

### 7.1 S1 — selection correctness (THE bug)
1. **Touch pass-through (CaptionOverlayView.onTouchEvent)**: every view is MATCH_PARENT and
   returns true on ACTION_DOWN unconditionally, so the TOPMOST overlay eats all taps. Change:
   consume the gesture ONLY when the touch is inside blockRect inflated by ~12dp (and blockRect
   is non-empty); otherwise return false so the container dispatch falls through to the view
   beneath. Tap on a caption = select it; drag/pinch still start on the caption body. This is
   SPEC_20260829_CAPTION_LAYERS §3.3 hit-test ("topmost enabled binding whose blockRect contains
   touch wins") finally enforced.
2. **Chrome only on ACTIVE (CaptionOverlayView)**: `drawBoxChrome` currently draws white outline
   + green grips on EVERY view. Add `setBoxChromeActive(boolean)` (default false); active draws
   outline + green grips; inactive draws NOTHING (JoyRaptor: "there should only be one with grab
   handles at any given time").
3. **Rebuild paths set the flag**: rebuildCaptionOverlays / rebuildAudioCaptionOverlays call
   `v.setBoxChromeActive(bindingIdx == active(Audio)CaptionBindingIndex)`; tag each view with its
   binding index (`setTag(bindingIdx)`); setActiveCaptionBinding / setActiveAudioCaptionBinding
   loop the container's children and refresh the flag per view. Legacy single overlay paths:
   setBoxChromeActive(true).
4. **Audio onTapped retarget** (rebuildAudioCaptionOverlays callback): add
   `activeCaptionIsAudio = true;` + transcript-drawer retarget + style-bar show, mirroring the
   video onTapped.
5. **Tab persistence — ObjectDrawer.refreshCurrentTab()**: selection changes must NOT call
   showCaptionDrawer(true) (full show resets activeTab to 0 — JoyRaptor: "if you're in fit, fit
   doesn't change, just the track changes"). Add `refreshCurrentTab()` that rebuilds the CURRENT
   tab's content in place (no slide animation, no activeTab reset, reportHeight). In the activity:
   a `refreshCaptionDrawerIfOpen()` helper = objectDrawer.refreshCurrentTab() + rebuild the header
   pills row. Replace all selection-driven `showCaptionDrawer(true)` calls (pill tap, overlay
   onTapped, eye toggle) with it. Full showCaptionDrawer(true) remains for first open.

### 7.2 S2 — header consolidation (JoyRaptor mockup)
`[CC-green] Style | (o track1) (o track2) (o track3) (+) | (fit icon) X`
1. **ObjectDrawer header slots**: new overload `show(tabs, toggles, lightAdjust, @Nullable View
   leadingView, @Nullable View middleView)`; the existing 3-arg show() delegates with null/null so
   every other drawer is unaffected. leadingView sits BEFORE titleView; middleView between
   titleView and iconRow (WRAP_CONTENT). Remove previous extras on each show().
2. **CC leading icon**: activity builds a TextView with the SAME ligature glyph the bottom tools
   carousel uses for Captions (find it in the tools-carousel data / tool_captions_icon), themed
   ACCENT green (0xFF4CAF50), materialicons font.
3. **Pills move into the header**: buildTrackPillsRow becomes the middleView; REMOVE the pills row
   + divider from BOTH tab builders (Style + Fit). Strip the row's 8dp top padding (header use).
4. **Fit tab icon**: tabs pass real iconRes — create `res/drawable/ic_caption_fit_24.xml`
   (24dp vector: box + inward arrows, white). Tab registration uses it for Fit.
5. **Remove Position + Timing tabs**: delete buildCaptionPositionTab (+ its registration).
   Timing's CONTENT is unique (caption Motion preset/granularity + range) — MOVE those rows to
   the BOTTOM of the Style tab, then delete buildCaptionTimingTab. Function preserved, tab gone.
6. **Selected pill shape**: replace `pill.setBackgroundColor(0x334CAF50)` (which squares the
   rounded bg) with a GradientDrawable: fill 0x264CAF50, full-corner radius, thin 1.5dp stroke
   0xFF4CAF50. Inactive pills keep floating_button_item_bg.
7. **Truncate = small checkbox, not a chip**: REMOVE the Truncate chip from growRow. In the Fit
   modeRow, AFTER the Off/Uniform/Per-cue chips, add: materialicons TextView
   "check_box_outline_blank"/"check_box" (grey 0xFF888888 off / green 0xFF4CAF50 on) + label
   "truncate off"/"truncate on" (11sp, grey). Same tweakCaptionStyle toggle.

### 7.3 Contracts
- `ObjectDrawer.refreshCurrentTab()` — no args, safe while animating (no-op if animating).
- `ObjectDrawer.show(List<Tab>, List<Toggle>, boolean, View, View)` — nulls clear extras.
- `CaptionOverlayView.setBoxChromeActive(boolean)` — default false; draw() honours it.
- Fit icon drawable id: `ic_caption_fit_24`.
