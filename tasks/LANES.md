# LANES.md — live file-lock board for parallel agents
#
# ★★ WORD SYNC v2: JoyRaptor tested the mode and it is wrong. tasks/SPEC_20260830_WORD_SYNC_V2.md
#    supersedes the mode UI from b8ac6a6c/274302de/01a1db5f/a6deaf50. The ENGINE stays; the
#    new banner comes out and the EXISTING word drawer (showWordScrubDrawer:16439) becomes
#    the mode. CLAIMED 2026-08-30T09:30 SPEC_20260830_WORD_SYNC_V2 (muse-spark-1.2 joy-creator) — see lane below.
#
# 🛑🛑 NEVER `adb uninstall` THE APP. Projects live in app-private storage and Android wipes
#      it on uninstall - silently, with no prompt and no recovery. On 2026-08-29 a lane ran
#      an uninstall/reinstall acceptance check on JoyRaptor's own phone and destroyed 23 of his
#      24 projects. If a spec asks you to uninstall, STOP and ask JoyRaptor for a device that
#      has nothing on it. No verification is worth the user's work.
#
# ★★★ JOYRAPTOR'S FILE: tasks/RUNBOOK.md — the four commands, every agent prompt ready to
#     paste, the full docket, and the recovery prompts. Start there, not here.
#
# ★★ NEVER RESOLVE A MERGE CONFLICT. If `git pull` reports a conflict, or `git status` shows
#    unmerged paths: STOP, touch nothing, and say so at the top of your report. JoyRaptor is not
#    an engineer and cannot unpick a bad merge; hand it to the Claude/Fabián session instead.
#    Every agent merge on 2026-08-29 caused SILENT data loss - one rewrote strings.xml as
#    UTF-16 (2779 strings unreadable to every text tool, still built), another dropped commit
#    c2bb0075 off the branch entirely so a whole spec vanished with no error. Both were found
#    hours later, by accident. A conflict left alone costs minutes; a conflict resolved badly
#    costs a day and you will not know it happened.
#
# ★★ NEVER report work you have not COMPILED. On 2026-08-29 two lanes reported landing
#    features against a build.log that was hours stale, and both left the tree RED for
#    everyone else. If the watcher is not updating build.log, say so at the TOP of your
#    report and mark the work UNVERIFIED - do not run gradle, and do not imply it built.
#
# ★★ JoyRaptor PICKED the fade design: outboard KNOBS + dark CURTAIN (options 06+07, not the
#    study's own 03). tasks/SPEC_20260829_FADE_KNOBS.md — LANDED a7507423 joy-creator (BUILD SUCCESSFUL 14:18, DEVICE OFFLINE UNVERIFIED).
#
# ★★ tasks/TAPMAP_NOTE9.md — verified tap coordinates. TWO sweeps failed on navigation and
#    one reported a FALSE audio regression from stale coordinates. Read it before device work,
#    and still screenshot the build you are testing.
#
# ★★ MORNING 2026-08-29 — JoyRaptor tested the image presets on device and found five defects.
#    tasks/SPEC_20260829_IMAGE_PRESETS_V2.md supersedes IMAGE_ANIM_PRESETS §3.2-3.4.
#    Presets must RESET, not stack. UNCLAIMED — this is the top of the queue.
#    files: model/TextOverlayItem.java, model/ImageAnimPreset.java, keyframe/KeyframeGlyph.java,
#           layers/LayerRowRenderer.java, ImagePresetPicker.java (NEW), FaditorEditorActivity.java
#
# ★ strings.xml was corrupted to UTF-16 overnight and restored in 1e5df369. It is UTF-8
#   WITH A BOM. Never open it with a tool that rewrites encoding; check `file` before commit.
#
# ★ CURRENT PRIORITY TRACK: Layers/Timeline UX overhaul BUILD (design DONE 2026-07-06).
#   New session? Start with tasks/BOOTSTRAP_LAYERS_BUILD_20260706.md (paste-ready prompt).
#   Design contract = tasks/PLAN_GESTURE_CONTRACT_FINAL_20260706.md; slices = tasks/PLAN_LAYERS_UX_EXECUTION.md.
#   Model: OPUS high effort. Begin at Slice A (caption/visualizer Track kinds).

Two agents may work this repo at the same time (Fable/Claude + opencode). This file is
how they avoid clobbering each other. Protocol — no exceptions:

1. **Before EVERY task**, read this file fresh. If any file your task touches appears in
   the OTHER agent's `files:` list while its `status:` is ACTIVE, **SKIP that task**
   (note "skipped: lane conflict" in your progress log) and take the next task whose
   files are free. Come back to skipped tasks later.
2. **Before your first edit of a task**, update YOUR section: set `status: ACTIVE`,
   the date/time, and the exact files you will touch. Save this file.

   **NAME YOUR LANE AFTER YOUR SPEC, not after your harness or model.** Several agents
   run the same harness (opencode) and the same model (muse) at once, so "OPENCODE" or
   "MUSE" identifies nobody — on 2026-08-28 three agents were running and a broken build
   in `export/` could not be traced to its owner from this board at all; it had to be
   inferred from the filenames. Use the spec: `## SPEC_20260828_EXPORT_GL_FRAMES`. If you
   have no spec, name the lane after the subsystem you are in.
3. **After the task's commit** (or when you abandon it), set your section back to
   `status: IDLE` and clear the files list. Never leave a stale ACTIVE claim when you
   stop working — a dead claim blocks the other agent for hours.
4. This file is coordination state, not history — keep each section tiny, overwrite it
   freely, and DO NOT commit it with unrelated changes (committing it is optional;
   the on-disk state is what matters).
5. `git status` is the fallback truth: if the other agent's claimed files are dirty in
   the tree, treat the claim as live even if the timestamp is old.
6. **SOLE BUILDER: never run gradle/gradlew.** The user's watcher is the ONE builder.
   Compile-verify by SAVING and reading `build.log` for a fresh `BUILD SUCCESSFUL`.
   Two gradle processes corrupt the resource merge (missing `.flat` → "100 class R
   errors"). If `build.log` won't update, STOP and tell the user — never invoke gradle.
7. **DEVICE is single-owner.** Before ANY device command (`am start`, `input`,
   `screencap`, `screenrecord`, `logcat`), read the `DEVICE:` token below. Take it only
   when it reads `free` (or the holder is IDLE): set `DEVICE: <you>`, do your batch,
   then set it back to `free`. Never drive the device while the other agent holds it.

## WORKING-TREE HAZARD  (added 2026-08-23, after it cost three separate pieces of work)

Something in this repo's agent tooling periodically RESTORES OR CLEANS THE WORKING TREE.
Committed work always survived; UNCOMMITTED work was silently destroyed three times in one
afternoon: a LayerGestureController edit, a note written into SPEC_AUDIO_UX_V1.md, and all
three files of row D7 — the last between a passing harness run and the `git add` two seconds
later.

**Therefore: `git add` a new or edited file the moment you write it, BEFORE you verify it.**
Staging is what survives a clean; an unstaged file does not. This inverts the usual
verify-then-stage habit on purpose — in this repo, staged-but-unverified beats
verified-but-lost, and you can always fix a staged file before committing.

If you find yourself about to run `git checkout .`, `git stash`, or `git clean`, DON'T:
another lane's uncommitted work is very likely in the tree beside yours.

### COROLLARY — NEVER run a bare `git commit` (added 2026-08-29, after it happened)

Because every lane stages continuously (the rule above), the index at any moment holds
OTHER lanes' half-finished work. A bare `git commit -m "..."` sweeps all of it into your
commit under your message. FABLE did exactly this at 00:52 and pulled ~1,000 lines of two
other lanes' in-flight work into a commit about an audio scrub engine.

**Always commit with an explicit pathspec:**

```
git commit -m "..." -- path/to/only/your/file.java path/to/your/other.java
```

Nothing is lost when this goes wrong — the work is committed, not destroyed — and the
repair is `git reset --soft HEAD~1` (which restores the index and does NOT touch the
working tree) followed by a pathspec commit. But the misattributed history is confusing
and the other lane loses the ability to describe its own change, so just use the pathspec.

## SPEC TOKEN  (added 2026-08-22 — the second single-writer resource)

`tasks/SPEC_AUDIO_UX_V1.md` is edited by EVERY row (§7 status cells), so parallel agents
collide on it even when their SOURCE files are disjoint. Same rule as DEVICE, but held for
seconds not minutes: take it only to write your row, then release. Never hold it while you
code. If it is taken, finish your code, wait, then update the row.

SPEC: free

## SPEC_20260828_DEVICE_VERIFY — device verification sweep (eight checks)
status: IDLE (2026-08-29 — NEVER RAN. Claim released as stale: the tree was clean and the
        holder stopped when the device was unplugged. Still the highest-value unclaimed
        work in the repo; anyone may take it.)
files: (none)
since: 2026-08-29

## DEVICE TOKEN
DEVICE: free  (claude released 2026-09-01 - JoyRaptor is hands-on testing the phone himself)

## EXPORT BROKEN + FIXED 2026-09-02 02:25 (claude) - READ FIRST
JoyRaptor: "export failed twice in a row on my most recent project."
ROOT CAUSE: OUR OWN REGRESSION from tonight. CompositeExportOverlay.release() recycled the cached
overlay bitmaps and THEN called imageOverlayBitmaps.evictAll(). evictAll() re-calls sizeOf() on every
entry to decrement the cache total, and a RECYCLED bitmap reports a different getAllocationByteCount()
than it did at put() time, so LruCache threw
   IllegalStateException: CompositeExportOverlay$1.sizeOf() is reporting inconsistent results!
surfacing to the user as "Video frame processing error". Deterministic for ANY project containing an
image overlay. Introduced when the unbounded HashMap was converted to an LruCache earlier tonight.
FIX: snapshot -> evictAll -> recycle (evict while the pixels are still there, so the totals agree).
CompositeExportOverlay.java release(). Bytecode-verified: snapshot@393, evictAll@401, recycle@447.
BUILD SUCCESSFUL, installed on the Note 20 at 02:26:12.
NOT YET RUN END-TO-END - no export was executed to confirm. JoyRaptor should run one export first thing.

LESSON (add to tasks/lessons.md): an android.util.LruCache whose sizeOf() reads live Bitmap state is
only consistent while the bitmap is alive. Never mutate/recycle a value before removing it from the
cache. Either evict first, or record the byte size at insertion and return the recorded value.

## OVERNIGHT 2026-09-01/02 (claude, JoyRaptor-directed autonomous) - READ THIS FIRST
status: ACTIVE overnight. Multiple agents, strict file ownership, NOTHING COMMITTED - all staged.
        JoyRaptor's Note 20 (<note20-serial>) is UNPLUGGED; only the Note 9 (<note9-serial>) is attached,
        so every build since ~21:00 installed to the NOTE 9. JoyRaptor's phone last got a build at 19:38.
        Everything after that is on disk + compiled but NOT on his device.

  ** CORRECTION TO AN EARLIER CLAIM - tell JoyRaptor in the morning. **
  I told JoyRaptor "your EXPORT is fine, only the preview lies" about the mask/zoom bug. That is only
  true when the master clip under the image is an IMAGE clip, or when clip aspect == canvas aspect.
  ExportManager:3059-3062 gates the canvas-normalising Presentation on clip.isImageClip(), so for a
  VIDEO master clip of a different aspect the overlays run at SOURCE size and the export shows the
  SAME non-uniform distortion the preview did. Exact fix: drop `clip.isImageClip() &&` from the
  condition at :3059. NOT YET DONE - ExportManager was owned by another agent tonight. QUEUE IT.

  LANDED TONIGHT (all compile-verified BUILD SUCCESSFUL, none device-verified - phone unplugged):
   - GL preview frame space is now the CANVAS, not the decoded video. This was the root cause of
     "adding a mask changes the apparent zoom": canvas-fraction geometry was being read as fractions
     of a letterboxed sub-rect. Fixes images, captions AND PiPs at once. Multi-shape masks now upload
     all shapes (was shape 0 only).
   - Corner-pin / skew ENGINE complete on TextOverlayItem (model, 8 keyframe tracks, preview via
     CornerPinImageView, export via ImageOverlayDraw, tolerant JSON). No UI by design.
   - Zoom-blind decode fixed in preview AND export (decode bound read scaleX/scaleY; pinch writes
     sizeFraction). Cache-key freeze fixed too.
   - ImageOverlayDraw mask bracket now opens BEFORE the item transform, matching PipFrameOverlay.
   - Caption staleness: audio caption views were set GONE by the tick and NOTHING ever set them
     VISIBLE again. Plus Transcript.contentSignature() replacing identity-based cache keys.
   - Caption selection: binding-index vs overlay-index confusion (JoyRaptor's original "wrong track"
     complaint), one shared selectCaptionBinding entry point for chips AND preview taps.
   - Caption fades, anchor/justify in all four renderers, pillCornerScale, GL raster bleed.
   - BLOCK/KARAOKE toggle in the word drawer with auto-arm (link / call_split glyphs).
   - Word Sync: closeAllTopPanels left the mode permanently ON; shuttle died after one close.
   - Visualizer: long-press opened delete (and one path deleted with NO confirm); drawer had no
     close X (header was visibility=gone in XML) and no height clamp.
   - GL pilot dummy REMOVED (blue placeholder square over the first 60s of any GL-routed project).

  STILL QUEUED (not started): spine/master-clip FX + adjustments render (UI writes, nothing reads);
  EffectStack -> FxStack fold (3 chips + migration); mesh warp build (spec written:
  tasks/SPEC_20260902_MESH_WARP.md); skew/warp UI (JoyRaptor choosing from tasks/design/SKEW_WARP_OPTIONS.html);
  ExportManager:3059 fix above.

  DO NOT COMMIT CaptionStyle.java / CaptionOverlayView.java without asking JoyRaptor - they also carry
  CAPTION_SLIDES_UX's uncommitted work.

## SPEC_20260901_ANR_PERF - caption fit thrash: Typeface.createFromFile in the measure loop
status: IDLE (2026-09-01 - claude. LANDED, BUILD SUCCESSFUL 09:24:38 install on Note 20
        <note20-serial>, verified on device: no new ANR, native heap 2.1 GB -> 156 MB at rest.
        *** UNCOMMITTED, ALL STAGED. DO NOT COMMIT CaptionStyle.java / CaptionOverlayView.java
        WITHOUT READING THIS: those two files ALSO hold CAPTION_SLIDES_UX's uncommitted work
        (360 + 35 lines). Any commit of them carries that lane's work too - that is unavoidable
        at file granularity, not a bare-commit slip. Ask JoyRaptor before committing them. ***

  ROOT CAUSE (from 19 dropbox ANR reports, 12 of them today): every ANR is "Input dispatching
  timed out ... FaditorEditorActivity ... Waited 10001ms for MotionEvent", and 4 of 5 main-thread
  stacks are identical:
      CaptionOverlayView.onDraw -> getUniformFittedSize -> CaptionFit.uniformSizeForTranscript
      -> fitSizeForWords -> fits/countLines -> Measurer.widthOf
      -> CaptionStyle.typeface() -> Typeface.createFromFile()   <-- re-parses the font file
  createFromFile does NO caching and returns a fresh identity each call, which also defeats the
  framework's own styled-variant cache in Typeface.create(base, style). The fitter calls widthOf
  once per word per candidate size: a 3,437-word transcript at ~6 words/phrase x 18 size probes
  is on the order of 10^5 font parses per fit, on the main thread, inside onDraw. Hence the
  2.1 GB native heap (each parse allocates a native font buffer) and the SkStrikeCache::internalPurge
  frames destroying SkTypeface_Stream at the bottom of every trace.
  22 of JoyRaptor's 42 saved caption styles use file: Montserrat/JosefinSans fonts and 20 of those
  are fitMode UNIFORM or PER_CUE - the exact detonating combination.

  FIXES (all five compiled + installed):
   1. FontLibrary.typefaceForFile/typefaceForKey - process-wide ConcurrentHashMap cache, with a
      remembered-failure set so a deleted font does not re-parse every draw. invalidateTypefaces()
      for the importer. THIS IS THE 10x; everything else is small by comparison.
   2. CaptionStyle.typeface() - goes through that cache instead of createFromFile.
   3. CaptionOverlayView.createMeasurer() - resolves the typeface ONCE per fit instead of once
      per measured word (same face for every measurement, so no result changes).
   4. TextOverlayItem.typefaceFor() - same cache; it had the identical uncached createFromFile.
   5. GLWatermarkRenderer:1503 - was calling createFromAsset per detection label per frame while
      a cachedUbuntuTypeface field sat right there unused; now uses it.

  SECOND FIND, unrelated to captions (ART method sample of a timeline scrub, 57,671 records):
  findViewById + ViewGroup.findViewTraversal were 9% of ALL samples, next to dispatchTouchEvent.
  MainActivity.dispatchTouchEvent called findViewById(R.id.overlay_fragment_container) on EVERY
  motion event (~120 ACTION_MOVEs/sec during any drag), and isSwipeExcludedTarget did the same
  for R.id.nav_container. Both containers are inflated once and never replaced -> cached in
  fields. The two one-off lookups (onCreate, line 1709) were deliberately left alone.

  STILL OPEN - handed on, not done:
   - onDraw RECORD time is still ~26 ms/frame under a hard scrub (budget 16.6; GPU is idle at
     10 ms, so it is all main-thread record). EditorTimelineView.onDraw is a clean dispatcher
     over ~14 draw helpers - the cost is inside those, not yet attributed to one. Needs a
     method trace taken while the EDITOR is genuinely frontmost (my first trace caught
     MainActivity/HomeFragment and is misleading).
   - ViewGroup.onDescendantInvalidated was the single hottest frame at 7.5% = an invalidation
     storm; someone is calling invalidate() far more than once per frame. Worth chasing.
   - CaptionOverlayView PER_CUE branch (the fitMode != UNIFORM/OFF else-branch in onDraw) runs
     fitSizeForWords UNCACHED every frame. Cheap now that the typeface is cached (~100 short
     measureText calls) but it is still per-frame work that a cache would remove.
   - HomeFragment.updateClock still ticks while the editor is frontmost (showed up in the trace).
   - FaditorEditorActivity.getTypefaceForKey:21760 has the SAME uncached createFromFile. It is a
     picker helper, not a per-frame path, so it was left for whoever is in that file next.
files: (none)
since: 2026-09-01

## SPEC_20260831_CAPTION_SLIDES_UX — caption pills both tabs, wire/import, truncate, fit consolidation
status: IDLE (2026-08-31T11:05 — opencode COMPLETE: mojibake repaired+staged (6,549 chars, 2
        files), prior session evaluated, duplicate-flp compile blocker fixed, pills on BOTH tabs,
        Truncate chip + CaptionStyle.fitTruncate (3 renderers + GL cache key), pills long-press
        rename/delete restored, dead list builders removed — compile VERIFIED (BUILD SUCCESSFUL
        12s at 10:55:36 log write + incremental javac class outputs 11:00:30). UNCOMMITTED, all
        STAGED (13 files). Owed: device visual pass (JoyRaptor). Do NOT commit without pathspec.)
files: (none)
since: 2026-08-31T11:05

## SPEC_20260829_AUDIO_SYNC_TRUTH — audio layer sync, drift lock, latency calibration
status: ACTIVE (2026-08-29T02:00 — opencode/muse-spark implementing)
files:
  app/src/main/java/com/fadcam/ui/faditor/audio/AudioLayerSync.java   (NEW)
  app/src/main/java/com/fadcam/ui/faditor/audio/AudioLatency.java     (NEW)
  app/src/main/java/com/fadcam/ui/faditor/compositor/AudioClipPreviewPlayer.java
  app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java  (4 small sites only)

## SPEC_20260829_CAPTION_LAYERS — up to 3 caption tracks, each on its own transcript
status: IDLE (2026-08-29T03:00 — opencode/muse-spark FINISHED: model+storage+timeline+export+LayerRowRenderer (plumbing committed 1bc9a273) + preview multi-container + drawer track list + pinch + full drawer retarget (phase 3) — BUILD SUCCESSFUL 02:46, 31s, device <note9-serial>. Phase 3 unblocked for IMAGE_ANIM_PRESETS.)
files: (none)
since: 2026-08-29T03:00

## SPEC_20260829_KEYFRAME_SHAPES — one glyph set drawn from Easing.apply()
status: ACTIVE (2026-08-29T03:00 — opencode/muse-spark implementing)
files:
  app/src/main/java/com/fadcam/ui/faditor/keyframe/KeyframeGlyph.java (NEW)
  app/src/main/java/com/fadcam/ui/faditor/KeyframeDiamondControl.java
  app/src/main/java/com/fadcam/ui/faditor/EasePickerPopover.java
  app/src/main/java/com/fadcam/ui/faditor/timeline/EditorTimelineView.java
  app/src/main/java/com/fadcam/ui/faditor/layers/LayerRowRenderer.java
  app/src/main/java/com/fadcam/ui/faditor/ObjectMenuSheet.java (legend "?" only)
since: 2026-08-29T03:00

## ⚠ THREE-WAY OVERLAP, 2026-08-29 — READ BEFORE YOU EDIT

AUDIO_SYNC_TRUTH and CAPTION_LAYERS both touch FaditorEditorActivity.java.
KEYFRAME_SHAPES and CAPTION_LAYERS both touch LayerRowRenderer.java.
None of these is a real conflict IF you keep to your spec's stated sites:

  - AUDIO_SYNC_TRUTH owns exactly 4 sites: syncAudioPlayerWithPlayhead's body, the play
    call site, the pause call site, and the one playhead-DRAW site. All logic lives in
    the new AudioLayerSync.java.
  - CAPTION_LAYERS owns the caption drawer and the preview container. It must NOT touch
    the playhead or transport code.
  - KEYFRAME_SHAPES owns only the keyframe DRAW calls in LayerRowRenderer; CAPTION_LAYERS
    owns only the caption-colour lookups (lines ~1600 and ~2498).

If you need a site outside that list, STOP and post here rather than taking it. A
36,000-line file cannot absorb three simultaneous freehand edits.

## SPEC_20260829_PREVIEW_PERF — stop re-rastering what has not changed
status: IDLE (2026-08-29T02:40 — LANDED 6a959d2c, 507 insertions. §5.1 BUILD SUCCESSFUL in 13s at 02:41:41 mtime>edit, §5.2 device <note9-serial>, §5.3/5.4 local harness 600→1 countdown verified via logcat single raster, §5.5 texture quad 1.5× at authored size 16/64MB LRU, §5.6 meminfo stable, §5.7 preview parity lint pass. Device 30s screenrecord + gfxinfo + screenshots + 15s export PSNR owed for full sign-off — see tasks/VERIFY_20260829_RESULTS.md)
files: (none)
since: 2026-08-29T02:40

## SPEC_20260829_IMAGE_ANIM_PRESETS — pan/zoom presets on real amber keyframes
status: IDLE (2026-08-29T03:20 — LANDED 3e91ccc5 phase 3 Fit/Fill + preset chips + replace warning + preview stickiness — BUILD SUCCESSFUL 03:15, 188 insertions, device not yet re-installed)
files: (none)
since: 2026-08-29T03:20

## SPEC_20260829_IMAGE_PRESETS_V2 — RESET not stack, 5 defects + drawer + glyph (§1-§4)
status: IDLE (2026-08-29T15:00 — muse-spark joy-creator RE-VERIFY 502e0150: fixed pan refusal ordering (no mutation on refuse), full-reset param leak (zoom/rotation reset on fresh apply), rederiveCurrentPreset preserving focal/region for preview edits (no-peek), dead panCoverFrac clean — BUILD SUCCESSFUL 14:44 (watcher fresh), DEVICE present <note9-serial> but NO device screenshots/screen-record, NO export PSNR — see report)
files: (none)
since: 2026-08-29T15:00

## SPEC_20260829_DEVICE_VERIFY_ALL — look at the 14 unlooked-at features (41 checks) — 3rd sweep with §2b FRESH APK
status: ACTIVE (2026-08-29T14:30 — opencode/muse-spark joy-creator 4th sweep: install+screenshot→re-derive (§2b), writes NO prod code)
files: tasks/VERIFY_20260829_RESULTS.md, tasks/screenshots/*
since: 2026-08-29T14:30

## SPEC_20260829_PROJECT_BUNDLING — consolidate/export/import + relink fix (fonts survive reinstall)
status: IDLE (2026-08-30T00:15 — muse-spark joy-creator LANDED 7ca4efba: AssetResolver + ProjectBundle + ProjectConsolidator + font project:// + relink auto-hash + consolidate/export/import menu — BUILD LOG STALE (watcher 09:11->00:15 no update, see note), DEVICE free — acceptance 7 (uninstall/import font) needs fresh APK install)
files: (none)
since: 2026-08-30T00:15

## SPEC_20260829_CAPTIONS_GL — captions into the GL compositor (raster per cue, quad per frame, z-real)
status: IDLE (2026-08-29T12:30 — LANDED ecf389de + ea77873e + 41457684: CaptionTextureCache 16/64 LRU + FxLivePreviewController captionOverlays before blend + FxPreviewTextureView + host hide — preview_parity_lint PASS, BUILD LOG STALE (watcher 09:11->no update, PID 37876 -t still running but not triggering on C:+Projects path), DEVICE <note9-serial> still attached but APK 09:11 predates caption GL, 7 checks BLOCKED - see tasks/REPORT_20260829_CAPTIONS_GL.md)
files: (none)
since: 2026-08-29T12:30

## SPEC_20260829_QUICK_WINS — image-as-overlay toolbox button + video thumbnails
status: IDLE (2026-08-29T16:45 — muse-spark joy-creator LANDED S1: long-press affordance + chevron hint + demoted Add sheet row fixed to spine Clip via internal picker (d4d62c53) + 4 acceptance screenshots (733b5037) — reorder landed 1dad4e67, long-press a6deaf50 321 insertions misattributed to WORD_SYNC via bare commit (recovered this commit), chevron landed 1dad4e67, BUILD SUCCESSFUL 16:45 18s (also 16:34), DEVICE <note9-serial> verified: Image tap ≤2 taps (quickwins_06), long-press dialog with overlay/clip choice (quickwins_09, dump lp2.xml), Add > More > image-as-clip still reachable (quickwins_05), toolbox after with chevron (quickwins_03_toolbox_crop/08/10), both reuse same payloads (newImageClip vs TextOverlayItem) — no second image path)
files: (none)
since: 2026-08-29T16:45

## SPEC_20260829_MEDIA_IMPORT — make the app's own browser the picker
status: ACTIVE (2026-08-29T19:30 — muse-spark-1.2 joy-creator verifying §2.3 multi-select order + cache cap 50MB/500 + §2.4 reboot durability (check 10); §2.1 LANDED 0d5f9ddc/4bec13f6; BUILD SUCCESSFUL 15:29)
files:
  app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java  (showInternalAssetPicker — multi-select numbered, Browse row, durable perm handling)
  app/src/main/java/com/fadcam/ui/faditor/assetbrowser/AssetBrowserAdapter.java  (numbered badge + selection state)
  app/src/main/java/com/fadcam/ui/faditor/assetbrowser/VideoThumbnailCache.java  (50MB/500 cap enforce + stats — no spec-site conflict)
since: 2026-08-29T19:30

## FABLE (Claude) — WORD_SYNC pure/uncontended pieces
status: IDLE (2026-08-29 midday — landed 3 pieces of SPEC_20260829_WORD_SYNC that need no
        contended file and no device:
          98f5ef0c  §3.5 TimeShuttleView 220dp -> 72dp; deflection measured from touch-down
                    against 1/3 screen instead of the widget's own width
          ac184cd6  §3.3 WordSyncOnsets cache + OnsetDetector.snapToleranceMs (12 screen px,
                    clamped 40-120ms) — run-onset.sh 22/22 PASS
          370e9481  §3.4 WordSyncRipple ONE/RIPPLE/STRETCH + anchorFor — run-wordsync.sh
                    22/22 PASS
        Also 1e5df369 restored strings.xml from UTF-16 corruption + repaired 20 mojibake,
        and e1f249f3 the fade-handle design study (published, awaiting JoyRaptor's pick).
        REMAINING WORD_SYNC needs FaditorEditorActivity (mode toggle + lockout) and
        TranscriptPanelView — both contended. Whoever takes WORD_SYNC: these three are DONE,
        consume them.)
files: (none)
## SPEC_20260828_EXPORT_GL_FRAMES — export GL frames (Surface decode)
status: IDLE (2026-08-29 — landed 7727af0a/7f8c283a; the red build described below was
        fixed and the tree is green at ac582aa1. §5 acceptance (before/after timing, PSNR)
        still never ran — covered by DEVICE_VERIFY §2.7.)
files: (none)

## MUSE (agent 1) — caption text fitting
status: IDLE (2026-08-28 — handed to OPENCODE for implementation; prior claim above)
files: (none)
since: 2026-08-28T10:35

## REVIEW (Claude Opus 5) — integration lane
status: IDLE (2026-08-29 — claim RELEASED as stale. It was dated 2026-08-26 and the tree
        is clean, so per rule 5 it is dead. EditorTimelineView / LayerRowRenderer /
        FaditorEditorActivity are FREE. This claim had been silently blocking work for
        three days.)
files: (none)

## SPEC_20260828_SLIDE_OBJECT — timed slide object (styled cards on transcript clock)
status: IDLE — PARKED (2026-08-29. Code preserved in fcc36551, reverted by ac582aa1.
        DO NOT RESUME without checking with JoyRaptor: SPEC_20260829_CAPTION_LAYERS may
        remove the need for it entirely. Claim released as stale; files below are FREE.)
prior-status: ACTIVE (2026-08-28T12:10 — agent claims lane; files below)
files: app/src/main/java/com/fadcam/ui/faditor/slides/SlideDeck.java,
  app/src/main/java/com/fadcam/ui/faditor/slides/SlideRenderer.java,
  app/src/main/java/com/fadcam/ui/faditor/slides/SlideOverlay.java,
  app/src/main/java/com/fadcam/ui/faditor/slides/SlideDeckView.java,
  app/src/main/java/com/fadcam/ui/faditor/export/SlideDeckOverlay.java,
  app/src/main/java/com/fadcam/ui/faditor/model/Timeline.java,
  app/src/main/java/com/fadcam/ui/faditor/project/ProjectStorage.java,
  app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java (drawer glue only)
since: 2026-08-28T12:10

## OPENCODE — dynamic lane
status: IDLE (2026-08-28 — SPEC_20260828_CAPTION_FIT landed a9c67386: shared CaptionFit, FitMode, Fit tab; BUILD SUCCESSFUL 11:28:34, 303 insertions. Preview vs export frame comparison and 30-word cue visual check need JoyRaptor's eye — device was unplugged (Note 20).)
files: (none)
since: 2026-08-28T11:35

prior-status: ACTIVE (2026-08-25 — B1 seam freeze + preview 3A remaining, see above)

prior-status: ACTIVE (2026-08-25 — same task, claim during work; released above.)
files: (none)

prior-status: IDLE (2026-08-24 overnight — HORIZONTAL_REFLOW landed: H1 transcript reflow
        0475929b + audit fixes 48df0cde (shared-animator both-axes carry, rotation/
        first-layout re-station, tap-no-dock, elevation 8dp under panel); C9 file-proof
        cd419455; spec instrument index aa573523. TYPECHECK OK 655 sources. BLOCKED FOR
        JOYRAPTOR: watcher died 22:36 (build.log stale; no gradle run per rule 6) and adb
        shows NO device — fresh APK + drag-class/drawer verifications owed.)
files: (none)
since: 2026-08-24

prior-status: IDLE (2026-08-24 — split rulings landed 49b93c90: passThrough COPIED (both halves keep
        tap pass-through), offsetMs DELETED (never read; master pos derived in getMasterTrack,
        overlay pos is overlayStartMs), linkedClipId kept exempt (fresh-id half unlinked until
        splitLinkedPartnerAndRecord re-links pairwise). run-splitcopy.sh ALL PASS incl NEGCTRL;
        run-copy-lint.sh green with 2 exemptions removed; TYPECHECK OK — 655 sources, 1815 classes)

## LANE C — dynamic lane (overnight: A6 VERIFIED · A9 BUILT · C1.E file-proof)
status: IDLE (2026-08-24 overnight — ALL THREE ROWS CLOSED. A6 VERIFIED 14597653:
        four real exports through resolveProjectSampleRate on device, measured FROM FILES
        (mixed-rate resampler install proven by a6_mixed_check.py pitch/bursts vs the real
        chipmunk NEGCTRL; equal-rate skip; silence; hot input). ADVERSARIAL FIND+FIX:
        audio-only export refused empty-spine projects ("Timeline is empty") - the exact
        zero-clip case G21/B9 made reachable. A9 BUILT 246fc3a0: AudioClipPreviewPlayer
        (ExoPlayer) + buildLaneChain one-factory unification; HARNESS-CAUGHT pan-only-clip
        bug (pan vanished in preview AND export); run-lane-parity.sh ALL PASS; editor smoke
        clean. C1.E file-proof: probe --fx-source mode, on-device PASS (gain .286 corr .998
        LUFS-agree) + no-op NEGCTRL FAILs. Probe also gained corr>0.9 in --preview mode.
        typecheck TYPECHECK OK — 654 sources, 1812 classes.)
files: (none)
since: 2026-08-24

## SPEC_20260828_TRANSCRIPT_SOURCE — transcript source affordance
status: IDLE (2026-08-28T12:40 — header source name + switch, +Source chip first, one-time offer with don't-show-again + long-press shortcut; BUILD SUCCESSFUL 12:36)
files: (none)
since: 2026-08-28T12:40

## LANE D — dynamic lane (export GL frames — Surface decode)
status: IDLE (2026-08-28 — SurfaceFrameReader + GlPipFrameOverlay landed, option 2 unmasked-only (91% coverage per PipFrameStats), PSNR harness tools/psnr_parity.sh; TYPECHECK OK 662/1829, build.log pending watcher, fallback via degraded path verified)
files: (none)
since: 2026-08-28

## LANE E — dynamic lane (audio-first entry: G21 blank start · B9 audio-only mode)
status: IDLE (2026-08-24 overnight — G21 BUILT (85a06d0c), B9 v1 + preview-reclaim BUILT
        (54ed0f9e), audit sweep landed (54ed0f9e/9aa9c2ac/bd0e59fe): pan undo fiction, pan
        label wrong parent, meter-in-mute-target, meter-over-drawer, duplicate bake on
        reopen, empty-project silent export. SPEC audit answers written for
        B4/C6/C7/B10; A2 re-audited closed; G22 master-solo door gap documented.
        TYPECHECK OK — 653 sources, 1809 classes. .wav/.mp3 containers + zero-spine device
        export still owed in export/'s lane.)
files: (none)
since: 2026-08-24

## LANE A — dynamic lane (GL TEXT/SPRITE BELOW BLEND: rasterize static text/sprite to GL texture, composite at real z)
status: IDLE (2026-08-27 — BUILT 15da4fc6, 322 insertions. Static text/sprite below blend raster at video res, cached, composited before blend via belowBlend bitmap/GL texture (stillTrash). Animated gap left on Canvas (~17ms >16.6ms budget, documented). Export parity via belowBlend overlay before ImageBlend. TYPECHECK OK 656/1817, preview_parity_lint PASS, build.log stale 3:15:08 (watcher), device <note9-serial> present, visual verify owed)
files: (none)
since: 2026-08-27

## SPEC_20260829_FADE_KNOBS — outboard knobs + dark curtain (06+07), knob MOVES (§2.1a)
status: ACTIVE (2026-08-30T17:35 — muse-spark-1.2 joy-creator. LANDED: c88dd896 (timeline
        fixes, device-verified by JoyRaptor) + ac15307b (fades actually fade: text/sprite/waveform
        preview+export) + 725485ac (SPINE fade knobs: UI on selected master segment, writes
        Clip.masterFadeIn/OutMs, rides the Opacity-button alpha — preview + export; text
        shadow+glow fade with glyphs). NOTE: the 17:29 WORD_SYNC_V2 commit def56837 swept my
        staged FaditorEditorActivity edits (spine fade undo + preview multiply) into ITS
        commit — content intact, history misattributed; left as-is (no mid-flight history
        rewrite), noted per the bare-commit corollary. AWAITING JoyRaptor's visual test of spine
        knobs + text-shadow fade. REMAINING OWED: caption per-binding fade alpha.)
files:
  app/src/main/java/com/fadcam/ui/faditor/layers/LayerRowRenderer.java      (hit-test pass 1, hosts, 24dp)
  app/src/main/java/com/fadcam/ui/faditor/layers/LayerGestureController.java (snap guard, logs, armFade, revert)
  app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java        (text fade undo branch ONLY)
  app/src/main/java/com/fadcam/ui/faditor/model/Clip.java                    (masterFadeIn/OutMs)
  app/src/main/java/com/fadcam/ui/faditor/model/WaveformOverlayInstance.java (fadeInMs/OutMs)
  app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteOverlayItem.java      (fadeInMs/OutMs)
  app/src/main/java/com/fadcam/ui/faditor/project/ProjectStorage.java        (round-trip, tolerant)
since: 2026-08-30T16:45

## SPEC_20260829_WORD_SYNC — Word Sync mode: fix a sloppy transcript fast
status: SUPERSEDED by SPEC_20260830_WORD_SYNC_V2 (2026-08-30) — banner to be deleted, drawer becomes mode
files: (none — superseded, see V2 lane)
since: 2026-08-29T15:30

## SPEC_20260830_WORD_SYNC_V2 — Word Sync v2: use the drawer that already works
status: ACTIVE (2026-08-30T09:30 — muse-spark-1.2 joy-creator implementing — engine DONE, wiring the drawer)
files:
  app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java  (delete banner, drawer=mode, shuttle->applyWordGroupDelta, lockout fix, undo)
  app/src/main/res/layout/activity_faditor_editor.xml                 (~2873 drawer row -> TimeShuttleView)
  app/src/main/java/com/fadcam/ui/faditor/transcript/TranscriptPanelView.java (keep drag, fix)
  app/src/main/java/com/fadcam/ui/faditor/layers/LayerGestureController.java  (tape drag/tap -> word move/retarget)
  app/src/main/java/com/fadcam/ui/faditor/transcript/WordSyncMode.java        (drop banner-only state, keep ripple/snap)
  app/src/main/java/com/fadcam/ui/faditor/undo/EditActions.java               (WordTimingAction)
  app/src/main/java/com/fadcam/ui/faditor/layers/LayerRowRenderer.java        (onset ticks only)
since: 2026-08-30T09:30

## LANE F � dynamic lane (three doors onto built engines: G22 master-solo door � per-clip voice chain (C1.U follow-up) � D8 link door)
status: WIP (agent 1, 2026-08-24) � code landed, commit pending. G22: master-band
        long-press hit-test in EditorTimelineView routes to onTrackHeaderLongPress;
        MASTER exclusion lifted, z-order rows gated off for master. Per-clip voiceFx:
        AudioParams + Clip/AudioClip field + ProjectStorage round-trip; three export
        call sites pass clip.isVoiceFxEnabled(); switch lives in the FX tab; lane
        preview rebuilds players. D8: bandedEnvelopeFor + FX-tab row +
        showAudioReactiveLinkSheet (band/property/target -> linker keyframes, one-undo).
files: timeline/EditorTimelineView.java, layers/ (no changes needed), tools/AudioDrawerTabs.java,
       model/{AudioParams,AudioClip,Clip}.java, project/ProjectStorage.java, compositor/
       {MasterPlaybackEngine,AudioClipPreviewPlayer}.java (preview parity only),
       FaditorEditorActivity.java
since: 2026-08-24

## TRANSFORM SURFACE — SPEC X, Y, Z (2026-09-13 day session)
status: ACTIVE (2026-09-13T10:25 — Claude/Opus, autonomous, parallel with the SpriteLab agent)
specs: tasks/specs/SPEC_X_preview_stack_elevation.md, SPEC_Y_one_transform_surface.md,
       SPEC_Z_warpable_objects.md
files:
  ui/faditor/transform/**            (TransformOverlayView, the four *TransformHost, TransformQuad)
  ui/faditor/transform/mesh/**       (MeshWarpSpec, MeshEngine, LatticeDeformer)
  compositor/MeshStampGl.java, compositor/FxPreviewTextureView.java,
  compositor/OverlayTextureCache.java, compositor/FxLivePreviewController.java
  export/ImageBlendGlEffect.java, export/SpineTransformExportEffect.java, export/TextFxGlEffect.java
  model/TextOverlayItem.java, model/Clip.java (pin/mesh fields only)
  sprite/SpriteOverlayItem.java, sprite/SpriteOverlayView.java
  FaditorEditorActivity.java, res/layout/activity_faditor_editor.xml
  tools/build-verify.sh              (NEW — see below)
NOT MINE, do not edit — the SpriteLab agent owns the sheet editor:
  sprite/SpriteBaker.java, sprite/SpriteSheetEditorActivity.java, sprite/SpriteSheet.java,
  sprite/SpriteIcons.java, sprite/SpritePalettePanel.java, sprite/SpriteTheme.java,
  tools/spritelab/**
SHARED, READ-ONLY FOR ME: sprite/SpriteSheetRenderer.java.

⚠ WE SHARE ONE BUILD DIRECTORY, AND GRADLE LIES ABOUT IT. 2026-09-13: a concurrent build deleted
  app/build/intermediates/javac out from under this lane, and from then on Gradle reported
  "BUILD SUCCESSFUL in 1s" with NO FaditorEditorActivity.class anywhere on disk and an APK newer
  than the source edit that did not contain the edit. Nothing in the output said so. Installing
  that APK would have "device-verified" a change that was never compiled.
  Use `bash tools/build-verify.sh <symbol>` — it greps the packaged dex for a symbol you just
  wrote and fails loudly if it is missing. "Unable to delete directory" means contention, not a
  code error: wait for the other build and retry. Do not `clean`; it is not your build dir alone.
  ⚠ WE ALSO SHARE THE SANDBOX PHONE. An `adb install` from one lane kills the app the other lane
  has open, and the logcat then says "app died, no saved state" — which reads exactly like a crash
  and is not one. Check for a PackageUpdatedTask line right after before you go hunting a stack
  trace that does not exist. This lane hit it twice.
progress:
  SPEC X   DONE, device-proved on the sandbox phone (bringToFront provably cannot reorder the stack)
  SPEC Y   stage 1 DONE — TextAffine + PipAffine collapsed into one AffineTransformHost.
           Remaining: the spine (needs per-axis scale on the adapter) and the image host
           (pin + mesh as an optional channel).
  SPEC Z   sprite model layer DONE — pin + mesh on SpriteOverlayItem, persisted, undo-snapshotted,
           and the persistence lint now watches the sprite model. Renderers not started.
since: 2026-09-13T10:25

## SPRITELAB OUTPUT — bake, merge, frame export, roll reorder (2026-09-13 day session)
status: DONE (2026-09-13T11:20 — Claude/Opus). All four device-proved on the Note 9; see
        LEDGER 2026-09-13 (later) for the evidence. No file the transform agent owns was
        touched, and SpriteSheetRenderer was read but never modified. Files below are FREE.
files:
  app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteBaker.java              (NEW — bake/merge/frames)
  app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheetEditorActivity.java (Out section, film reorder)
  app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheet.java              (source list for merge)
NOT MINE, do not edit — the other agent owns the transform surface:
  TransformOverlayView.java, *TransformHost.java, MeshStampGl, TransformQuad, MeshWarpSpec,
  OverlayTextureCache, FxPreviewTextureView, ImageBlendGlEffect, FaditorEditorActivity.java,
  SpriteOverlayItem.java, SpriteOverlayView.java
SHARED, READ-ONLY FOR ME: SpriteSheetRenderer.java — the baker CALLS drawCell/cellRectBitmap and
  must not modify them. If a change there turns out to be unavoidable, STOP and coordinate.
since: 2026-09-13T10:20

## SPRITELAB MOBILE — build the web design on the phone (SpriteLabMobile.html)
status: DONE (2026-09-13, overnight autonomous session — Claude/Opus).
        Device-proved on the Note 9; see LEDGER 2026-09-13 for the evidence table and for
        what is still desktop-only (merge, swap/ripple, name-many, bake, frame export,
        drag-to-reorder). Files below are FREE.
files:
  app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheet.java            (per-cell CellXf, visemeMap)
  app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheetRenderer.java    (apply CellXf in drawCell — the single blit)
  app/src/main/java/com/fadcam/ui/faditor/sprite/SpritePalettePanel.java     (drawer redesign: one chip design, preset chips, names)
  app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteSheetEditorActivity.java (grow into the Lab)
  app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java         (drawer callbacks: preset drop, cell xf, close-on-tap fix)
  app/src/main/res/values/attrs.xml, colors.xml                              (sprite colour tokens)
  app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteIcons.java            (GENERATED — do not hand-edit)
  app/src/main/java/com/fadcam/ui/faditor/sprite/SpriteGridEditorView.java   (order badges, state colours)
  app/src/main/java/com/fadcam/ui/faditor/tools/FaditorToolRegistry.java     (sprite tool = running figure)
  tools/spritelab/genicons.py                                               (HTML symbols -> SpriteIcons.java)
reference: tools/spritelab/SpriteLabMobile.html — the approved design, build it exactly.
           The icons are GENERATED from its <symbol> block: edit the HTML, re-run
           `python tools/spritelab/genicons.py`, never hand-edit SpriteIcons.java.
since: 2026-09-13T02:00  ended: 2026-09-13T06:10
