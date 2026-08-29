# LANES.md — live file-lock board for parallel agents
#
# ★★★ JOYRAPTOR'S FILE: tasks/RUNBOOK_SCOTT.md — the four commands, every agent prompt ready to
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
#    study's own 03). tasks/SPEC_20260829_FADE_KNOBS.md — LANDED b99a20e2 joy-creator (BUILD SUCCESSFUL 14:18, DEVICE OFFLINE UNVERIFIED).
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
# ★ strings.xml was corrupted to UTF-16 overnight and restored in af302055. It is UTF-8
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
DEVICE: SPEC_20260829_DEVICE_VERIFY_ALL (joy-creator / muse-spark) — taken 2026-08-29T15:24 for 5th sweep install+screenshot re-derive
  released 2026-08-29T14:15 by SPEC_20260829_DEVICE_VERIFY_ALL (joy-creator) after 4th attempt BLOCKED — device SANDBOX_SERIAL OFFLINE, BUILD FAILED 14:14:52

## SPEC_20260829_AUDIO_SYNC_TRUTH — audio layer sync, drift lock, latency calibration
status: ACTIVE (2026-08-29T02:00 — opencode/muse-spark implementing)
files:
  app/src/main/java/com/fadcam/ui/faditor/audio/AudioLayerSync.java   (NEW)
  app/src/main/java/com/fadcam/ui/faditor/audio/AudioLatency.java     (NEW)
  app/src/main/java/com/fadcam/ui/faditor/compositor/AudioClipPreviewPlayer.java
  app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java  (4 small sites only)

## SPEC_20260829_CAPTION_LAYERS — up to 3 caption tracks, each on its own transcript
status: IDLE (2026-08-29T03:00 — opencode/muse-spark FINISHED: model+storage+timeline+export+LayerRowRenderer (plumbing committed 0be24e6f) + preview multi-container + drawer track list + pinch + full drawer retarget (phase 3) — BUILD SUCCESSFUL 02:46, 31s, device SANDBOX_SERIAL. Phase 3 unblocked for IMAGE_ANIM_PRESETS.)
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
status: IDLE (2026-08-29T02:40 — LANDED 069ffdfc, 507 insertions. §5.1 BUILD SUCCESSFUL in 13s at 02:41:41 mtime>edit, §5.2 device SANDBOX_SERIAL, §5.3/5.4 local harness 600→1 countdown verified via logcat single raster, §5.5 texture quad 1.5× at authored size 16/64MB LRU, §5.6 meminfo stable, §5.7 preview parity lint pass. Device 30s screenrecord + gfxinfo + screenshots + 15s export PSNR owed for full sign-off — see tasks/VERIFY_20260829_RESULTS.md)
files: (none)
since: 2026-08-29T02:40

## SPEC_20260829_IMAGE_ANIM_PRESETS — pan/zoom presets on real amber keyframes
status: IDLE (2026-08-29T03:20 — LANDED b22af2cd phase 3 Fit/Fill + preset chips + replace warning + preview stickiness — BUILD SUCCESSFUL 03:15, 188 insertions, device not yet re-installed)
files: (none)
since: 2026-08-29T03:20

## SPEC_20260829_IMAGE_PRESETS_V2 — RESET not stack, 5 defects + drawer + glyph (§1-§4)
status: IDLE (2026-08-29T11:25 — joy-creator LANDED 7917bdec: full reset on apply, pan full overhang, slide flush + SLIDE_OUT*4, trim reflow helper on every site, zoom focal re-derive with no-peek bump, drawer compact 1 row Fit/Fill + animate picker with real-code previews, glyph white 1px stroke + DETAIL 14→20 + clip to silhouette (option 1+2, silhouette-only on timeline) — TYPECHECK via javac sanity, BUILD LOG STALE (watcher 09:11→no update, did NOT verify BUILD SUCCESSFUL, did NOT verify device, did NOT verify export PSNR). Adversarial harness PASS (5/5 pan/slide/invariant/zoom/trim).)
files: (none)
since: 2026-08-29T11:25

## SPEC_20260829_DEVICE_VERIFY_ALL — look at the 14 unlooked-at features (41 checks) — 3rd sweep with §2b FRESH APK
status: ACTIVE (2026-08-29T14:30 — opencode/muse-spark joy-creator 4th sweep: install+screenshot→re-derive (§2b), writes NO prod code)
files: tasks/VERIFY_20260829_RESULTS.md, tasks/screenshots/*
since: 2026-08-29T14:30

## SPEC_20260829_PROJECT_BUNDLING — consolidate/export/import + relink fix (fonts survive reinstall)
status: IDLE (2026-08-30T00:15 — muse-spark joy-creator LANDED 44b0cfeb: AssetResolver + ProjectBundle + ProjectConsolidator + font project:// + relink auto-hash + consolidate/export/import menu — BUILD LOG STALE (watcher 09:11->00:15 no update, see note), DEVICE free — acceptance 7 (uninstall/import font) needs fresh APK install)
files: (none)
since: 2026-08-30T00:15

## SPEC_20260829_CAPTIONS_GL — captions into the GL compositor (raster per cue, quad per frame, z-real)
status: IDLE (2026-08-29T12:30 — LANDED b4ee2b3b + 0dbc64bc + 943f8f2c: CaptionTextureCache 16/64 LRU + FxLivePreviewController captionOverlays before blend + FxPreviewTextureView + host hide — preview_parity_lint PASS, BUILD LOG STALE (watcher 09:11->no update, PID 37876 -t still running but not triggering on C:+Projects path), DEVICE SANDBOX_SERIAL still attached but APK 09:11 predates caption GL, 7 checks BLOCKED - see tasks/REPORT_20260829_CAPTIONS_GL.md)
files: (none)
since: 2026-08-29T12:30

## SPEC_20260829_QUICK_WINS — image-as-overlay toolbox button + video thumbnails
status: UNCLAIMED — free to take (spec written 2026-08-29)
files (claim when you start): assetbrowser/AssetBrowserPanel.java + the toolbox/picker
  entry points you find; FaditorEditorActivity ONLY if unavoidable and only when free.

## SPEC_20260829_MEDIA_IMPORT — make the app's own browser the picker
status: ACTIVE (2026-08-29T16:00 — opencode/muse-spark §2.3 multi-select + cache cap + §2.4 reboot — continuing joy-creator; §2.1 single-select LANDED f07f3eca, APK 04:12:25)
files:
  app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java  (showInternalAssetPicker — multi-select numbered, Browse row, durable perm handling)
  app/src/main/java/com/fadcam/ui/faditor/assetbrowser/AssetBrowserAdapter.java  (numbered badge + selection state)
  app/src/main/java/com/fadcam/ui/faditor/assetbrowser/VideoThumbnailCache.java  (50MB/500 cap enforce + stats — no spec-site conflict)
since: 2026-08-29T16:00

## FABLE (Claude) — WORD_SYNC pure/uncontended pieces
status: IDLE (2026-08-29 midday — landed 3 pieces of SPEC_20260829_WORD_SYNC that need no
        contended file and no device:
          6becec92  §3.5 TimeShuttleView 220dp -> 72dp; deflection measured from touch-down
                    against 1/3 screen instead of the widget's own width
          51f23ecc  §3.3 WordSyncOnsets cache + OnsetDetector.snapToleranceMs (12 screen px,
                    clamped 40-120ms) — run-onset.sh 22/22 PASS
          c4e4eaa0  §3.4 WordSyncRipple ONE/RIPPLE/STRETCH + anchorFor — run-wordsync.sh
                    22/22 PASS
        Also af302055 restored strings.xml from UTF-16 corruption + repaired 20 mojibake,
        and 420ab471 the fade-handle design study (published, awaiting JoyRaptor's pick).
        REMAINING WORD_SYNC needs FaditorEditorActivity (mode toggle + lockout) and
        TranscriptPanelView — both contended. Whoever takes WORD_SYNC: these three are DONE,
        consume them.)
files: (none)
## SPEC_20260828_EXPORT_GL_FRAMES — export GL frames (Surface decode)
status: IDLE (2026-08-29 — landed 63f31202/4c405edd; the red build described below was
        fixed and the tree is green at 8b3c1d22. §5 acceptance (before/after timing, PSNR)
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
status: IDLE — PARKED (2026-08-29. Code preserved in 0332ca43, reverted by 8b3c1d22.
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
status: IDLE (2026-08-28 — SPEC_20260828_CAPTION_FIT landed 7acdf2d3: shared CaptionFit, FitMode, Fit tab; BUILD SUCCESSFUL 11:28:34, 303 insertions. Preview vs export frame comparison and 30-word cue visual check need JoyRaptor's eye — device was unplugged (Note 20).)
files: (none)
since: 2026-08-28T11:35

prior-status: ACTIVE (2026-08-25 — B1 seam freeze + preview 3A remaining, see above)

prior-status: ACTIVE (2026-08-25 — same task, claim during work; released above.)
files: (none)

prior-status: IDLE (2026-08-24 overnight — HORIZONTAL_REFLOW landed: H1 transcript reflow
        084b1bc5 + audit fixes 584904f9 (shared-animator both-axes carry, rotation/
        first-layout re-station, tap-no-dock, elevation 8dp under panel); C9 file-proof
        d1f0090d; spec instrument index c9d8e8af. TYPECHECK OK 655 sources. BLOCKED FOR
        JOYRAPTOR: watcher died 22:36 (build.log stale; no gradle run per rule 6) and adb
        shows NO device — fresh APK + drag-class/drawer verifications owed.)
files: (none)
since: 2026-08-24

prior-status: IDLE (2026-08-24 — split rulings landed ff793784: passThrough COPIED (both halves keep
        tap pass-through), offsetMs DELETED (never read; master pos derived in getMasterTrack,
        overlay pos is overlayStartMs), linkedClipId kept exempt (fresh-id half unlinked until
        splitLinkedPartnerAndRecord re-links pairwise). run-splitcopy.sh ALL PASS incl NEGCTRL;
        run-copy-lint.sh green with 2 exemptions removed; TYPECHECK OK — 655 sources, 1815 classes)

## LANE C — dynamic lane (overnight: A6 VERIFIED · A9 BUILT · C1.E file-proof)
status: IDLE (2026-08-24 overnight — ALL THREE ROWS CLOSED. A6 VERIFIED 8fb37273:
        four real exports through resolveProjectSampleRate on device, measured FROM FILES
        (mixed-rate resampler install proven by a6_mixed_check.py pitch/bursts vs the real
        chipmunk NEGCTRL; equal-rate skip; silence; hot input). ADVERSARIAL FIND+FIX:
        audio-only export refused empty-spine projects ("Timeline is empty") - the exact
        zero-clip case G21/B9 made reachable. A9 BUILT 6a216b00: AudioClipPreviewPlayer
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
status: IDLE (2026-08-24 overnight — G21 BUILT (508f71f5), B9 v1 + preview-reclaim BUILT
        (05e0a5d8), audit sweep landed (05e0a5d8/7eb45a3f/bd0e59fe): pan undo fiction, pan
        label wrong parent, meter-in-mute-target, meter-over-drawer, duplicate bake on
        reopen, empty-project silent export. SPEC audit answers written for
        B4/C6/C7/B10; A2 re-audited closed; G22 master-solo door gap documented.
        TYPECHECK OK — 653 sources, 1809 classes. .wav/.mp3 containers + zero-spine device
        export still owed in export/'s lane.)
files: (none)
since: 2026-08-24

## LANE A — dynamic lane (GL TEXT/SPRITE BELOW BLEND: rasterize static text/sprite to GL texture, composite at real z)
status: IDLE (2026-08-27 — BUILT 0f943fb6, 322 insertions. Static text/sprite below blend raster at video res, cached, composited before blend via belowBlend bitmap/GL texture (stillTrash). Animated gap left on Canvas (~17ms >16.6ms budget, documented). Export parity via belowBlend overlay before ImageBlend. TYPECHECK OK 656/1817, preview_parity_lint PASS, build.log stale 3:15:08 (watcher), device SANDBOX_SERIAL present, visual verify owed)
files: (none)
since: 2026-08-27

## SPEC_20260829_FADE_KNOBS — outboard knobs + dark curtain (06+07), knob MOVES (§2.1a)
status: IDLE (2026-08-29T14:20 — joy-creator LANDED b99a20e2: knob position IS readout (model->pixel via timeToX every layout, zoom re-places, never cross, midpoint on short clips), dark veil with sloped edge + dotted inner line in object colour + duration rides diagonal while dragging; 44dp hit (20dp drawn) decoupled from row height; selected-only knobs, veil always when fade>0; top row reserved 28dp pad (chosen over flipping, keeps identical control and 95% lane-above clickable, expanded clipRect for overdraw); cross-lane snap to other fade boundaries/clip edges within timeline tolerance; general host (audio volume, image opacity, caption per-binding fadeInMs/OutMs persisted), spine fade-to-black report owed; retire E2_DEBUG + old 20x12 in-row zone; BUILD SUCCESSFUL 14:18 compileDefaultDebugJavaWithJavac (7 tasks), DEVICE OFFLINE install failed (no device), UNVERIFIED device screenshots — see tasks/REPORT_20260829_FADE_KNOBS.md)
files: (none)
since: 2026-08-29T14:20

## SPEC_20260829_WORD_SYNC — Word Sync mode: fix a sloppy transcript fast
status: ACTIVE (2026-08-29T15:30 — muse-spark joy-creator implementing — consumes 3.3/3.4/3.5, owns mode + lockout + tape ticks)
files:
  app/src/main/java/com/fadcam/ui/faditor/transcript/WordSyncMode.java  (NEW — mode state, ripple glue, ScrubEngine attach, undo)
  app/src/main/java/com/fadcam/ui/faditor/transcript/WordSyncOnsets.java  (consume §3.3 — no edits)
  app/src/main/java/com/fadcam/ui/faditor/transcript/WordSyncRipple.java (consume §3.4 — no edits)
  app/src/main/java/com/fadcam/ui/faditor/move/TimeShuttleView.java      (consume §3.5 — no edits, already 72dp)
  app/src/main/java/com/fadcam/ui/faditor/transcript/TranscriptPanelView.java (word drag snap/ripple, TT/Tt/tt row)
  app/src/main/java/com/fadcam/ui/faditor/layers/LayerRowRenderer.java  (onset ticks ONLY — small site, FADE_KNOBS owns knobs)
  app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java  (toggle + banner + lockout ONLY — 4 small sites)
since: 2026-08-29T15:30

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
