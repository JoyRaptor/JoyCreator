# HANDOFF 2026-07-26 — context/account switch

---

## 0z. PROGRESS LOG (newest first) — updated as work lands this session

### NEXT UP — the rest of AUDIT_UNFINISHED_20260726.md's STATUS BOARD in its stated order
(Tier 3, then Tier 4). B1/B4/B5/B3-remedy still await a user design decision, and there is
now a FIFTH thing needing a user decision — the preview-side preset-crop gap, below.

- **2.3 EXPORT LEG FIXED + device-proved. PREVIEW LEG DELIBERATELY NOT SHIPPED (needs a user
  decision) — see below.** The preset table moved from `ExportManager.getCropRect` to
  `Clip.cropRectNdc`, and a new `Clip.effectiveCropFractions()` turns either the custom
  fractions or a preset's NDC rect into ONE image-space rect {l,t,r,b}. `ExportManager`
  delegates to it (still exactly one table) and `GlTransitionFrameOverlay.cropSrcRect` (the
  export INCOMING leg) now uses it, so a preset-cropped clip no longer blends uncropped and
  snaps at the cut.
  **Numbers (Note 9; fixture = black image → 800ms GL cross_dissolve → 1080x1920 clip
  cropped "9:16"; second fixture identical except the crop is written as the numerically
  equal `custom` 0.34375..0.65625, which is the ORACLE for what the preset must render):**
    - baseline pre-fix, preset vs custom: **235/798 frames differ**, blend-region mean
      absdiff **18.47**; within-export content width **1.000 during the blend → 0.312 after
      the cut** (that width snap IS the user-visible bug).
    - after the fix, preset vs custom: **0/798 frames differ**; width **0.312 throughout**.
    - regression control, custom on old build vs new build: **0/798 differ** → the working
      custom path is untouched.
    - instrument not blind: the same diff saw 235 differing frames on the baseline pair; the
      encoder-residual floor (mean ~1–2) was calibrated from regions of that same pair whose
      composites are identical. All three mp4s have DIFFERENT sha256 while decoding to
      identical frames — the reason this class of proof compares pixels, never files.
  **⚠️ THE BIG FINDING — the RE-SCOPED block's premise was WRONG, and it changes the scope.**
  It claimed "preview and export currently AGREE — both blend a preset-cropped clip uncropped,
  then snap to the cropped framing at the cut". The preview does NOT snap, because **the live
  preview never renders a named preset crop at all**: `applyCropZoom`
  (`FaditorEditorActivity` ~:7391) is `"custom"`-only, and it is the only thing that crops the
  normal-playback preview. MEASURED at an identical playhead (00:12.132, same clip selected):
  the same crop written as `custom` previews as a narrow strip; written as `9:16` it previews
  FULL WIDTH. So the real state was: export self-inconsistent (blend vs cut), preview
  self-consistent but silently ignoring presets everywhere.
  Consequence: following the spec literally — cropping presets in `cropToClipBounds` /
  `liveLegGeometry` only — would have MOVED the snap into the preview (cropped during the
  blend, uncropped the instant the cut lands) instead of removing it. Those three preview
  sites are therefore left `"custom"`-only with a comment saying exactly this, so nobody
  "finishes the job" and reintroduces it.
  **Needs a user decision (do NOT ship blind):** named preset crops are invisible in the
  whole editor while the export applies them — what you see is not what you get, for every
  preset-cropped clip, not just during transitions. Closing it means making `applyCropZoom`
  preset-aware, which changes what the editor shows for every existing preset-cropped
  project. That is a visible behaviour change, so it is diagnosed here and left for the user.
  **Sandbox:** `aeb0517e` was the fixture host (JSON surgery on an already-indexed project,
  per the F12 recipe); restored byte-exact, project.json AND .bak both back to
  `0db82121…` = its safety copy. 9/10 projects match; `cebc19e0` still diverges on purpose.
  HONEST NOTE: during fixture pushes I deleted that project's `project.json.bak` and
  `undo_history.json` WITHOUT copying them first (unlike the 129d8643 restore earlier, where
  I did). Both are derived files and .bak has been rewritten from the restored original, but
  if that project had a distinct pre-existing backup or undo stack, it is gone. Four test
  exports were left in the device's Faditor export folder, as previous sessions also did.
  New reusable tool: **`tasks/framing_probe.py`** measures the content width at given
  timestamps of ONE export — the within-export blend-vs-cut check that `export_ab_diff.py`
  (an A-vs-B tool) structurally cannot do.
  **AN ADVERSARIAL REVIEW OF MY OWN DIFF CAUGHT A REGRESSION I HAD SHIPPED INTO THE WORKING
  TREE — worth repeating on compositor changes.** `ExportManager` gates the whole crop block
  on `isVideo` (`= !clip.isImageClip()`, :2273/:2329), so an IMAGE clip's own segment is never
  crop-effected — but `GlTransitionFrameOverlay` routes image clips through `cropSrcRect` too.
  My first version therefore would have cropped an image clip's BLEND and not its CUT: the
  same snap, mirrored — the exact failure I had just written a comment warning against on the
  preview side. Fixed before commit by excluding image clips (they keep the `"custom"`-only
  rule, making the expression identical to the old code for them). NOTE this exclusion is
  correct BY CONSTRUCTION, not by measurement: the only image asset in these projects is
  solid black, so a framing probe cannot see a crop on it. An image-incoming fixture would
  need a non-black image pushed and indexed.
  **Other caveats from that review (recorded, not fixed):** `cropSrcRect` cuts in UNROTATED
  space while the clip's own segment is cropped AFTER `ScaleAndRotateTransformation`, so a
  rotated+cropped clip's blend and cut disagree — pre-existing for `"custom"`, now reachable
  for presets too. And `docs/project-schema.md` advertises a `4:5` preset that
  `cropRectNdc` has no case for (such a project gets no crop anywhere — consistent, so not a
  new break, but the doc is wrong).
  **SCOPE, HONESTLY:** no UI path writes a named preset to a clip today — the crop tool only
  ever sets `"custom"` or `"none"` (`FaditorEditorActivity:6026, 6071, 6465`; `EditActions`
  just replays whatever was set). So this bug is reachable from legacy or hand-edited project
  JSON (which is what the fixtures are) and from any future writer using the presets the
  schema advertises. The fix is still right, but it is lower-severity than the audit implied,
  and that is worth knowing before spending more on the preview leg.

**2.3 scoping notes from before the work (kept — the code sites are still accurate):**

- **The four sites, all branching `"custom".equals(clip.getCropPreset())` and applying NO
  crop for a named preset:** `GlTransitionFrameOverlay.cropSrcRect` (:216, export incoming
  leg), `FaditorEditorActivity.cropToClipBounds` (:9281, preview static tier),
  `liveLegGeometry` (:9053, preview live A+B tier) and `cropKey` (:9302 — the frame-cache
  key; MISS IT AND a preset-cropped frame gets served from an uncropped cache entry).
  The OUTGOING leg is already correct in export: `ExportManager` :2331-2340 feeds the Crop
  effect from `getCropRect(preset)` for any non-`none` preset.
- **The one conversion to route all four through** (per the RE-SCOPED block): NDC→fractions
  off `ExportManager.getCropRect`, whose array is `{left, right, bottom, top}` in NDC −1..1
  and is consumed as `new Crop(left, right, bottom, top)`. Fractions: `l=(left+1)/2`,
  `r=(right+1)/2`, `t=(1-top)/2`, `b=(1-bottom)/2`. That table is currently private static
  in ExportManager — expose it (or move it) so preview and export cannot drift apart. Keep
  the existing epsilon rules (`>0.01`, and full when both `>=0.99`).
- **Transition seam convention (verified from `TransitionIndex.removeForDeletedClip`, which
  drops `t.clipIndex == i` and `i-1` for clip i):** a transition at `clipIndex = i` is the
  seam AFTER clip i, so OUTGOING = clip i and **INCOMING = clip i+1**. The crop under test
  goes on clip i+1.
- **Fixture candidate:** `aeb0517e` ("AudioExportVerify") already has a `GL_SHADER`
  cross_dissolve. Prefer JSON surgery on an ALREADY-INDEXED project + restore afterwards
  (what F12 did with 302da9ac) over a cloned dir — a dir created behind the app's back is
  NOT indexed and its list row misroutes the click.
- **⚠️ CORRECTION to the RE-SCOPED block's step 4 — `--check-asym` does NOT apply here.**
  It takes ITEM CENTRES (`x,y;x,y`) and fails when a flip maps one item onto another: it is
  a compositing-LAYOUT guard, not a crop guard. And its intent ("put the crop OFF-CENTRE")
  is unachievable by construction: EVERY named preset rect is centred on both axes, so no
  preset fixture can expose a flip of the crop rect. Do not fake an off-centre preset —
  that would no longer be the thing under test. Use these two guards instead:
    1. **Within-export blend-vs-post-cut framing comparison** (what F12 actually proved):
       pull a mid-blend frame and a post-cut frame from the SAME export. Post-cut framing
       comes from the Crop effect, the known-good authority — so "no snap at the cut" is a
       real check, and it is the check the user-visible bug is about.
    2. **Pick the `9:16` preset** (x∈[-0.3125,0.3125], y FULL) on the 1080x1920 portrait
       sources: it crops in ONE axis only, so the realistic bug for this conversion —
       mis-indexing the `{left,right,bottom,top}` array and cropping the wrong axis —
       shows up as an obviously wrong framing rather than a subtle offset.
- **Order (unchanged and load-bearing): BASELINE BOTH EXPORTS BEFORE TOUCHING CODE.**
  Fixture P = preset `9:16` on the incoming clip; fixture C = the numerically equal `custom`
  fractions (l=0.34375, r=0.65625, t=0, b=1). Today they must DIFFER mid-blend and AGREE
  after the cut — capturing that difference first is what makes the later fix
  distinguishable from a no-op. Then the symmetric 4-site change, then re-export both and
  require agreement THROUGHOUT, plus preview screenshots mid-blend for both (an export-only
  diff cannot see a preview regression, and preview/export parity is the invariant this
  item exists to protect).
- Do NOT "correct" the preset table's 16:9-oriented NDC constants in the same change (a
  standing warning in the RE-SCOPED block): parity with the cut is the goal, and
  "fixing" the constants would break it.

- **TRANSCRIPT NAVIGATOR DONE + device-verified (`bd84402`).** Built both halves of the
  decided design. (1) `TranscriptPanelView.setClipWindow(inMs,outMs)` + `inClipWindow()`:
  words whose SOURCE start falls outside the current clip's trim draw dimmed (0xFF6E6E6E,
  struck 0xFF4A4A4A); the window defaults to UNBOUNDED so any caller that never sets one
  keeps the old rendering. (2) A tap on a dimmed word is now unambiguously NAVIGATION —
  it routes straight to `onSeekToMs` and is no longer eligible for the double-tap
  line-break gesture. IMPLEMENTATION NOTE (differs from the scoped plan, deliberately):
  no new `onNavigateToSourceMs` callback was needed — `onSeekToMs` (18955) ALREADY
  re-homes a source ms onto whichever clip of the same source contains it, and
  `seekToTimelineMs` → `onPlayheadSeeked(isDragging=false)` already sets
  `selectedClipIndex` + `setSelectedIndex`. So cross-clip selection was already wired;
  only the highlight and the tap disambiguation were missing. Host applies the window via
  new `applyTranscriptClipWindow()` at both panel binds in `loadTranscriptPanelContent()`
  and from the playhead updater (selection changes route through `selectSegment` →
  `loadTranscriptPanelContent`, so coverage is complete).
  **Bug this exposed and fixed in the same commit:** both halves of an IN-MEMORY split
  share ONE `Transcript` instance (`Clip` copy-ctor addAll's the same `NamedTranscript`
  refs), so the existing re-bind branch — which only fires when the instance DIFFERS —
  left `transcriptClipId` pinned to the pre-split clip, and the `clip.getId().equals(
  transcriptClipId)` gate at ~7971 stopped updating the playback highlight the moment
  playback crossed the seam. Added an else-if that re-homes the id when the instance
  matches but the clip differs.
  **Device proof (Note 9, project `129d8643` "bisect C long 2x", predictions computed in
  Python from project.json BEFORE looking at the screen):** clip [1406,4457] → bright
  "this cat is very cute she is white and"; sibling clip [4457,4884] of the SAME source →
  bright "fluffy" ONLY. Both observed exactly — same panel, same 37 words, different clip
  (that pair IS the positive control: a blind instrument would have shown one set twice).
  Then a real split at source 2730 (predicted blind): half A bright "this cat is very
  cute", half B bright "cute she is white and", boundary word bright in both. Tapping a
  dimmed word selected the owning clip, flipped the dimming, and seeked to that word;
  playing across the seam kept the highlight advancing (not frozen).
  **Sandbox restored + verified:** `129d8643` project.json AND .bak are back to
  sha256 `111bea60…` = its safety copy; 9/10 projects match their safety copies and
  `cebc19e0` still diverges ON PURPOSE ('hi' start=1892, lastModified unchanged —
  untouched this session). Its `undo_history.json` held full-project snapshots of my
  split, which would have fought the restored file, so it was removed after being copied
  to scratchpad `tn/backup_undo_history.json` (a missing history is a supported state:
  `loadUndoHistory` logs "No undo history found" and returns false).
  **FIXTURE TRAPS PAID FOR HERE (both mine, not the app's):** (a) the timeline toolbar's
  orange "±" at ~(386,1305) is `btn_ripple_mode` (edit mode), NOT split — tapping it
  recorded an undo entry and looked like "the split silently failed"; the real Split lives
  in the SCROLLABLE bottom tool carousel and its position moves. Get real bounds with
  `adb shell uiautomator dump /sdcard/vh.xml` + `exec-out cat` (needs
  `MSYS_NO_PATHCONV=1` in Bash or Git-Bash mangles the device path) and grep for
  `id/tool_split`. (b) `adb shell cat` inserts CRs — a pulled project.json will NEVER
  sha256-match its safety copy until you normalize CRLF→LF (or pull with `exec-out cat`,
  which is byte-exact). I nearly mis-read this as "the project was already modified".
  **Cosmetic follow-up, NOT a regression:** right after a split/undo the active-word cyan
  box can briefly sit on an out-of-window (dimmed) word, because `setActiveSourceMs` is
  driven by the player position which hasn't resettled. Pre-existing behaviour that the
  new dimming merely makes visible; it self-corrects on the next tick.

### (DONE — see above) Transcript navigator (audit 1.1 step 3, DECIDED design).

Decided behaviour: after a split, the panel shows the WHOLE source with the current clip
highlighted, and BECOMES a navigation surface (tap a word in another clip's region → jump to
that clip). Key finding from scoping (do NOT re-derive): **the panel already shows the whole
source** — `loadTranscriptPanelContent()` binds `currentTranscript = clip.getTranscript()`
(`FaditorEditorActivity.java:20835`, also 20820 for audio), and post the transcript-SHARING
migration every clip from one source holds the SAME whole-source transcript. So the old
"windowing wraps words misaligned" symptom is already gone; what's missing is the
CURRENT-CLIP HIGHLIGHT and the cross-clip NAVIGATION. Build those two things:

1. `TranscriptPanelView` (494 lines): add `setClipWindow(long inMs, long outMs)` +
   `clipWindowInMs/OutMs` fields. In `onDraw`, render words whose `startMs` is OUTSIDE
   `[inMs,outMs]` dimmed (they belong to OTHER clips of the same source); words inside are
   full-strength. The existing `activePaint` cyan highlight (playback word) stays. Words are
   in SOURCE time already (`transcript.words[i].startMs`), so the window is a direct compare.
2. Tap routing in `onTouchEvent` (~line 383-399): currently a tap calls
   `listener.onSeekToMs(words[idx].startMs)`. Change so that when the tapped word is OUTSIDE
   the current clip window, it fires a NEW listener callback `onNavigateToSourceMs(long)`
   instead — the host resolves which clip's `[inPointMs,outPointMs]` contains that source ms,
   selects that segment (`selectSegment`), and seeks. Inside-window tap keeps `onSeekToMs`.
3. Host (`FaditorEditorActivity`): after every `transcriptView.setTranscript(...)` (7 sites:
   20828, 20843, 7966, 20959, plus the binds near 7964/12180), also call
   `transcriptView.setClipWindow(clip.getInPointMs(), clip.getOutPointMs())`. Implement the
   new `onNavigateToSourceMs` in the Listener at 18955: find the clip index whose source
   window contains the ms (careful: multiple clips of the same source can overlap in source
   time — pick the one nearest the current selection, or the first containing it), then
   `selectSegment(idx)` + seek. Note `editorTimeline.setTranscriptHighlight(selectedClipIndex,
   sourceMs)` at 7980 already exists for the timeline-side highlight — mirror its clip
   resolution.
4. DEVICE-VERIFY: split a clip in cebc19e0 or a throwaway, open the transcript on each half,
   confirm (a) the same whole-source words show on both, (b) the current half's words are
   full-strength and the other half's are dimmed, (c) tapping a dimmed word switches the
   selected clip and seeks. Use a THROWAWAY clone (restore after) — and remember the adb-dir
   indexing trap above (corrupt/clone an ALREADY-INDEXED project, don't hand-make a dir).

### Not scoped for code changes without a user design decision: B1, B4, B5, and B3's fix.
B1 (PiP audio route), B4 (link badge = "lock", no unlink), B5 (dual-stream discoverability)
and B3's remedy are all discoverability/affordance calls the user has NOT decided. Diagnosed;
await a design decision before shipping UX changes.

- **LOAD-FAILURE SHAPE fix DONE + device-verified (`e6a1f0b`).** Decided behaviour built:
  one bad item no longer aborts the whole load into a silent `.bak`. ProjectStorage's
  deserializer now wraps each clip / overlay-clip / audio-clip / text-overlay / waveform
  item in its own try/catch (sprite path already did); a bad item is skipped + recorded on
  `FaditorProject.loadSkips`. The Activity's `warnIfItemsSkipped()` dialog names the dropped
  items and offers **Keep going** / **Open last backup** (new `loadBackupOnly()` +
  `EXTRA_LOAD_BACKUP`). A `loadSkipDialogPending` guard blocks all saves while the dialog is
  open so an autosave/onPause can't rotate the current file into `.bak` and destroy the clean
  backup being offered. Device proof on an INDEXED project with a text overlay's
  sizeFraction=null: `Skipping malformed text overlay #0 — UnsupportedOperationException:
  JsonNull` → `Loaded with 1 skipped item(s): [Text overlay #1]` → dialog shown with the
  overlay absent + rest intact → "Open last backup" → `loadBackupOnly: … (skips=0)` reloaded
  clean. Positive control: valid sibling overlay 'Yo' + the clean backup both loaded skips=0.
  TEST-INFRA NOTE (cost real time): a project dir created via adb (bypassing the app) is
  NOT indexed — its recent-projects row misroutes the click to another project. To device-test
  a load-corruption fix, corrupt an ALREADY-INDEXED project's project.json (with a pulled
  restore point), not a freshly-adb-created dir.
- **Sandbox integrity RESTORED + verified.** cebc19e0 restored to the user's real state
  (`scratchpad/b3_before.json`, 'hi' start=1892) for BOTH project.json and .bak; throwaway
  fixture `aaaa1111-…` deleted. All 9 non-cebc safety-copied projects are sha256-IDENTICAL to
  their copies (playback/scrub never dirtied them). `3072f113` (user's B5 project) untouched.
- **B3 DIAGNOSED (device, not a standalone code bug — a symptom of B4).** On cebc19e0's 'hi'
  overlay I measured all three drag routes: a **body/center drag SCRUBS** (playhead moves, 'hi'
  data UNCHANGED — pulled before/after: start=1892 end=4925 both times); an **edge-grab TRIMS**
  (resizes) — this is the only drag that visibly changes 'hi', and it is correct behaviour for a
  trim handle; a **move requires a long-press pickup (450ms, ITEM_PICKUP_MS)** which is
  undiscoverable AND, because 'hi' is in the TIME+OPACITY link group, the move is refused/clamped
  (the B4 constraint). Net: the user tried to move 'hi', the middle-drag scrubbed (nothing
  happened to the text) and/or the edge-grab trimmed ("got longer"), and the real move was
  blocked by the link. So B3's root cause is **B4 (time-link + no discoverable unlink)**, NOT a
  separate gesture bug. FALSE ALARM ruled out: a left-trim to 0 serialises `startMs` as ABSENT
  (ProjectStorage:1795 only writes startMs when !=0; reader defaults absent->0), which pull-diff
  shows as `null` — that is start=0, correctly handled on load, NOT corruption.
  Fix is a UX/affordance decision (make unlink discoverable per B4; make trim-vs-move legible) —
  NOT shipped blind. cebc19e0's 'hi' currently has start=0 from my trim experiment; restore from
  the pre-experiment pull `scratchpad/b3_before.json` (start=1892) when done with B1/B4 (they
  also need cebc19e0's PiPs/link-groups). Note: still deciding whether B4's unlink UX is a
  user-design call before shipping.
- **B2 FIXED + device-verified (`a9e4503`).** Diagnosis: NOT a start-freeze. cebc19e0
  ("P0 control2 plain", the cat project, row 1/2 in the list — NOT the screen-recording
  "Untitled" project that happens to be open on arrival, which is a DIFFERENT project)
  plays fine from the start. The bug is **pressing play with the playhead already at the
  timeline END = silent no-op** (no auto-rewind): play() seeks to the last clip's trim-end,
  immediately hits end, stops (`Playback stopped at last segment end` -> STATE_ENDED),
  playhead never moves, no audio. Fix rewinds to 0 at end. The load-bearing signal is
  `isAtTrimEnd() on the last segment` gated on `!hasAudioTail` — isEnded() is false (Activity
  pauses proactively ~150ms before ENDED) and the terminal playhead (13166) sits 189ms below
  getTimelineEndMs()'s sum-of-clips (13355) because of 3 transitions, so neither isEnded() nor
  a position-epsilon could fire. Proven by on-device log + screenshots; positive control
  (mid-clip play does NOT rewind) also passed.
- Note: opening cebc19e0 bumps its lastModified (now row 1). Playback/scrub with autosave may
  have touched the earlier "Untitled" screen-recording project's mtime — re-verify the 9
  non-cebc sandbox projects' sha256 at end of stretch (cebc19e0 intentionally still diverges).
- Wakeup mechanism note: ScheduleWakeup is /loop-scoped and clamps to 1h; cloud scheduled
  tasks have NO device access (wrong env). Continuation is by ongoing work in-session; this
  §0z + git log is the seam if the turn ends.

---

## 0a. HUMAN TEST PASS, 2026-07-26 ~12:20 — findings + two product decisions

The user ran the four things a harness cannot. Evidence is on the Note 9 in
`cebc19e0` (touched, **do NOT restore it — it is the evidence**) and a new `3072f113`.

### DECISIONS TAKEN (build to these)

1. **Corrupt project file.** Do BOTH: skip the bad ITEM and keep the project, *and* tell the
   user what is broken/missing, *and* offer "open the last backup instead" as a choice. Not a
   silent fallback and not a silent skip — say what was lost and let them pick.
2. **Transcript panel after a split: show the WHOLE source with the current clip highlighted.**
   Rationale from the user, and it is a stronger reason than the spec recorded: because every
   clip points at ONE source, the panel becomes a NAVIGATION surface — you can see how big your
   clip is and jump to the other clips through the transcript itself. Build it as a navigator,
   not just a viewer.

### CONFIRMED WORKING (harness could not have shown these)

- **Neutral-substrate queue item 7 PASSES.** Dropping into the row gap created lanes named
  **"Layer 8" / "Layer 9"** — `LAYER` kind, correct naming, not "Text N".
- **Item 5/6 direction confirmed**: the `'hi'` text overlay is sitting on `layerId
  43fda830-…`, which is a **LAYER-kind lane** — a text really does land and stick on a neutral
  lane.
- **Audit 1.2's schema fix fired in the wild**: that project is now `schemaVersion=11`,
  stamped because the user created real LAYER lanes. Exactly the designed behaviour, on a
  project no fixture touched.

### BUGS FOUND

- **B1 — PiP audio opt-in is effectively unreachable.** The user could not turn it on, and the
  file proves why: BOTH PiPs still have `overlayAudioEnabled` absent (never set) and
  `audioMuted=true`, so silence was CORRECT behaviour. "Include audio" lives only on the PiP
  object menu, and that menu opens only on **hold → release-in-place on the timeline item**
  (`onItemMenuRequested`). The user reasonably double-tapped the PiP in the preview and got
  nothing. The feature is built and correct and cannot be found. Needs a discoverable route.
- **B2 — playhead does not move / no audible playback** in that project. NOT explained by B1
  and not yet diagnosed. Reproduce in `cebc19e0` before anything else.
- **B3 — a horizontal drag on a text layer RESIZES it instead of moving it.** User moved the
  `'hi'` text sideways and it "got longer in the time domain as opposed to just shifted".
  Real bug, distinct from the link-group constraint below.
- **B4 — "purple link" is a LINK GROUP, not a lock, and there is no discoverable way out.**
  The file has 2 link groups: 3 waveforms bound by `TIME`, and 2 text overlays bound by
  `TIME`+`OPACITY`. That is why most items would not drag sideways — they are time-linked to
  their host, which is *correct* behaviour badly communicated. The user read purple as "locked"
  and could not find any unlock/unlink affordance. Needs the badge to say what it means and
  offer "unlink".
- **B5 — dual-stream recording is not discoverable.** The user enabled something in options and
  still found no dual mode; the new project `3072f113` is a plain 1-clip, `schemaVersion=7`
  project with no PiP. So SPEC_PIP_AUDIO acceptance 4 stays BLOCKED, and there is a real
  discoverability bug in front of it.

### Note on sandbox integrity

`cebc19e0` no longer matches its safety copy **on purpose** — it holds the user's test work.
The other 9 still match. Do not "restore" `cebc19e0` until B2/B3 are diagnosed from it.

---

## 0. CURRENT STATE (updated 11:40) — READ THIS FIRST

HEAD `270ce4c`, branch `joy-creator`, tree clean except the always-ignorable
`tools/jvm-harness/out*/`. Build watcher ALIVE, installing to the **Note 9 only** (Note 20
unplugged). APK 11:19:50, installed, newer than every source file.

**Pick up here.** The `hasValue()` sweep is now COMPLETE (`0c13dca` + `270ce4c`): all 195
guard sites in ProjectStorage's read paths examined, 182 converted, 13 identity fields left
loud on purpose. Device-verified on both the clip path and the audio/text/sprite/overlayClip
paths.

That work surfaced the thing actually worth doing next, which is bigger than the guard class:

> **A single malformed value in ONE item aborts deserialization of the WHOLE project**, and
> `load()` then silently serves `project.json.bak` with nothing but an `FLog` line. The user
> loses everything since their previous save because one overlay had a bad number. Guarding
> optionals shrank the surface; it did not change the shape.
>
> Two candidate fixes, either of which is a real improvement:
> 1. **Per-item fault tolerance** — wrap each clip/overlay/audio item's deserialization so a
>    bad item is skipped and logged, and the rest of the project still loads. Note the sprite
>    path ALREADY does this (`catch (Exception ignored)`), so there is precedent in-file; the
>    other three payload types do not.
> 2. **Make the fallback visible** — if `load()` falls back to `.bak`, tell the user, the way
>    `warnIfProjectIsReadOnly` tells them about a newer-schema file. Silent rollback is the
>    part that turns a bad field into lost work.
>
> Reproduce it in one line: take any clone, set a text overlay's `sizeFraction` to `null`
> (read unguarded in `TextOverlayItem`'s constructor, `ProjectStorage:2290-2295`), open the
> project — it silently opens the backup, or fails to open at all if there is no backup.

**Diagnostic lesson worth keeping:** filter logcat by PID, not tag —
`adb shell "logcat -d --pid=$(adb shell pidof com.fadcam.beta) -t 700"`. App-wide greps
returned nothing because Bluetooth chatter had pushed the app's lines out of the window, and
three fixtures were mis-diagnosed before I read the actual exception.

**All 10 real sandbox projects are sha256-identical to their safety copies.** Every experiment
in this run used a throwaway `cp -r` clone; nothing needed restoring. Stray artifacts left
deliberately on the sandbox: a handful of 480p test exports in `FadCam/Faditor/`.

### Audit items closed in this run (see the audit's STATUS BOARD for the live list)

1.2, 1.3, 1.4, 2.1, 2.2, 2.4, 2.5 (export leg), 2.6, 2.7 (items 2/3/8), guard hygiene, plus
the timer-export answer owed in §7 and the ROWGESTURE logging in Tier 4. Each has its own
commit whose message states what was proved and how.

**Three of them found more than the audit described** — worth knowing, because it means the
audit's wording is a starting point, not a spec:
- **1.3** is not "drops an object". For 3 of 4 sites the loader treats the file as corrupt and
  silently opens `project.json.bak`. Reproduced: a fixture opened titled "ROLLED BACK TO BAK".
- **2.4** has the direction BACKWARDS. The export was correct; the PREVIEW over-rendered the
  neighbouring clip's words. Fixing the export on the audit's wording would have broken it.
- **2.1** needed a second fix: any re-bind blanked captions while paused, so the slider erased
  the very thing it was meant to show.

### Verification tooling in `tasks/` — REUSE, do not rebuild

- `export_ab_diff.py` — decode two exports, diff PIXELS. `--check-asym` refuses a fixture
  symmetric enough to hide a flip. Proven on 2.7 item 8 and cross-type Z.
- `export_audio_probe.py` — fit a source's amplitude inside an export. 1.0 = mixed once,
  2.0 = doubled. `--expect-absent` for a not-present control.
- `schema_layer_stamp.py` — schema-stamp survey + corruption repro + 4 source tripwires.
- `getlayers_equiv.py`, `visible_equiv.py` — both now hard-fail on zero matched files.

### The habit that actually produced these results

Every proof carried a POSITIVE CONTROL, and that is not ceremony. **Five times in this run a
confident finding turned out to be my own harness**, and each was caught only by a control or
by looking at the actual pixels:
- a 3-way export comparison gave three byte-identical files → my setup deleted only the
  same-id fixture, so "row 1" kept opening the wrong project;
- a cross-encode pixel diff at threshold 8/255 read lossy residual as signal and made a clean
  cross-type-Z result look broken;
- a correlation probe nearly as long as the export had no lag headroom and fitted gain −0.204
  where the truth was 0.993;
- "the lane eye does not persist for text or sprite" — I grepped for `hidden`; the key is
  `objHidden`;
- an export "truncated with no moov atom" was me `am force-stop`-ing during muxing.

If a proof reports "no difference", assume the instrument is blind until a control says
otherwise.

### Next, in the audit's own order

1. **2.3** preset crops during transitions. Deliberately not started — symmetric three-site
   change in the GL transition compositor, and starting it without room to device-verify is
   worse than not starting. **The executable recipe is now written into
   `PERF_SPEC_LONGFILE_20260718.md`'s F12 RE-SCOPED block**, including "capture the FAILING
   baseline before touching code".
2. **Tier 3** items, then Tier 4.
3. Still open and needing a HUMAN, not a harness: 2.5's preview leg (someone must listen to a
   PiP), 2.5 acceptance 4 (needs a real dual-stream pair project — none exists on the
   sandbox), and 2.7 items 5–7 (gesture drag-and-drop; adb injection drifts, and this is why
   `ROWGESTURE_DEBUG` was turned off rather than deleted).

### Found in passing, NOT fixed

1. After an undo with the caption drawer open, the preview canvas collapses to a thin strip
   for a frame or two; it recovers on the next playhead move.
2. `ProjectStorage` has ~223 typed JSON reads guarded by `.has()` alone (78 `getAsString`,
   57 `getAsFloat`, 38 `getAsLong`, 34 `getAsBoolean`, 22 `getAsInt`; only 6 null-guarded).
   Audit 1.3 fixed the 4 named `layerId` sites; the rest want one `optString/optFloat` helper
   and a mechanical sweep, with its own proof.

### Device + harness traps (each cost real time)

- Screenshots via the **Bash** tool (`adb exec-out screencap -p > f.png`); PowerShell's `>`
  adds a BOM and corrupts binaries. Same for `adb shell cat` of JSON — read with `utf-8-sig`.
- `adb push` via **PowerShell**; from Bash `/data/local/tmp` becomes
  `C:/Program Files/Git/data/local/tmp` (or prefix `//`).
- `touch` does NOT trigger Gradle's watcher (content hash, not mtime).
- Foreground `sleep` is blocked; wait with a backgrounded `until [ "$APK" -nt "$SRC" ]`.
- An export of a 4-second project takes **~55 s**, not the ~15 s the dialog implies. Poll for
  output-size stability before touching the app.
- Never compare mp4 hashes (container bytes differ, frames identical).
- `FaditorEditorActivity` is not exported — `am start` cannot open a project. Use the UI:
  Faditor `(627,2108)`, row 1 `(538,705)`. **Purge every non-real project dir first**, or
  row 1 is ambiguous.

### Fixture recipe (how every proof here was made)

`adb shell run-as com.fadcam.beta cp -r <projects>/<real-id> <projects>/<fake-uuid>`, patch
the JSON on the host, `adb push` to `/data/local/tmp`, then
`adb shell "cat /data/local/tmp/x.json | run-as com.fadcam.beta sh -c 'cat > .../project.json'"`.
Set `lastModified` to now so it sorts to row 1. Delete the clone when done and re-verify the
10 real projects by sha256.
Projects: `/data/data/com.fadcam.beta/files/faditor/projects/<id>/project.json`
Exports: `/storage/emulated/0/Android/data/com.fadcam.beta/files/FadCam/Faditor/`

---

Landed in the first stretch, each with its own offline proof and device verification:

| commit | audit item | proof |
|---|---|---|
| `b8b6234` | guard hygiene | both python guards now hard-fail on zero matched files |
| `d77daf3` | **1.2** LAYER schema hole | `tasks/schema_layer_stamp.py` (survey + corruption repro + guard + 4 source tripwires); device 7→11 with a LAYER def, stays 7 without |
| `eaff34b` | **2.1 + 2.2** caption size | device A/B 3%↔20% live in preview; audio size 0.15 round-trips. Also fixed: any re-bind blanked the captions while paused |
| `7a09eb6` | **1.3** `layerId: null` | device: 15 objects in → 15 out with 4 explicit nulls; control (`cropPreset: null`) silently opens the older `.bak` |
| `6851eca` | **1.4** downgrade drill | drill RAN 7/7; found + fixed the undo-history sidecar being written for a read-only project |

**Device hygiene:** every experiment used throwaway `cp -r` clones. All 10 real sandbox
projects are **sha256-identical to their safety copies** — verified after the last
fixture was removed. Nothing needed restoring. One stray artifact left deliberately: a
480p export in `FadCam/Faditor/` on the sandbox from drill step 6.

**Reusable fixture recipe** (this is the machinery to reuse, it worked well):
`adb shell run-as com.fadcam.beta cp -r <projects>/<real-id> <projects>/<fake-uuid>`,
patch the JSON on the host, `adb push` to `/data/local/tmp` then
`cat /data/local/tmp/x.json | run-as com.fadcam.beta sh -c 'cat > .../project.json'`.
Bump `lastModified` to now so it sorts to row 1 of the project list, then tap
Faditor `(627,2108)` → row 1 `(538,705)`. Delete the clone when done.
`FaditorEditorActivity` is **not exported**, so `am start` cannot open a project directly.

**Newly known traps:**
- Screenshots must be captured with the **Bash** tool (`adb exec-out screencap -p > f.png`).
  PowerShell's `>` corrupts binaries with a BOM. Same for `adb shell cat` of JSON — read
  those with `utf-8-sig` or use Bash.
- `adb push /data/local/tmp/...` from **Bash** gets MSYS-mangled to `C:/Program Files/Git/data/...`.
  Use PowerShell for `adb push`, Bash for `exec-out` redirects.
- `touch` does NOT trigger Gradle's continuous build (it hashes content, not mtime). To
  force a rebuild you need a real content change.
- Foreground `sleep` is blocked by the harness; wait on the APK with a backgrounded
  `until [ "$APK" -nt "$SRC" ]; do sleep 3; done`.

**Two things found in passing, NOT fixed, worth their own look:**
1. Immediately after an undo with the caption drawer open, the preview canvas collapses to
   a thin strip for a frame or two; it recovers on the next playhead move.
2. `ProjectStorage` has ~223 typed JSON reads guarded by `.has()` alone (78 `getAsString`,
   57 `getAsFloat`, 38 `getAsLong`, 34 `getAsBoolean`, 22 `getAsInt`; only 6 null-guarded).
   Audit 1.3 fixed the 4 named `layerId` sites; the rest want one `optString/optFloat`
   helper and a mechanical sweep, with its own proof. See `7a09eb6`.

Next per the audit's own order: **2.5** (PiP audio plumbing), **2.6** (cross-type Z
absolute-geometry A/B), **2.7** (neutral substrate queue items 2,3,5–8), then **2.3**
(preset crop — read the F12 RE-SCOPED block, not the older note).

---

Written at the end of a long Opus 5 session because the account ran out of credits and work
continues on a **different login, same machine**. Everything below lived only in that
session's context and would otherwise be lost. HEAD at write time: `11c4927`, tree clean
(except the always-ignorable `tools/jvm-harness/out*/`).

---

## 1. The transcript migration RAN and is VERIFIED

Observed live on the user's phone at 01:21 and confirmed on disk:

```
27221664  collapsed=11 recovered=2824 shared=7    <- legacy partitions reconstructed
a32d24e2  collapsed=26 recovered=0    shared=8    <- modern full copies, collapse only
00024cc7  collapsed=4  recovered=0    shared=2
66623e32  collapsed=11 recovered=0    shared=2
```

On-disk proof for the legacy project, before vs after:

```
BEFORE  78253218: [141, 272, 524, 1291]   (four partial forks)
AFTER   78253218: [2228]                   (one shared, complete)
        bdb7ca98: [173,262,506,1261] -> [2202]
        synth_17: [182,258,506,1266] -> [2212]
```

2824 = exactly the offline prediction. Every clip now shares one complete transcript.

**Follow-up already applied (`ProjectStorage.shareTranscriptsWithBackup`):** because each
clip still SERIALISES its own copy, forks reappear on every load and this migration runs
every time. It was writing a fresh multi-megabyte backup on each app launch — two 5.3MB
files from a single launch were measured. It now only backs up and rewrites when
`recovered > 0` (a real reconstruction); a pure in-memory re-collapse persists nothing.
The permanent fix is the deferred file-level dedup (serialise each transcript once, clips
hold ids) — see `SPEC_TRANSCRIPT_SHARING.md` open item 4.

**Housekeeping owed:** the redundant `project-preshare-*.json` files already written on the
user's device can be deleted; the migration has succeeded and byte-exact originals are in
the safety copies below. Total project dir is 213MB.

## 2. SAFETY COPIES — read-only originals of the user's real projects

```
C:\Users\JoyRaptor\fadcam-safety-2026-07-26\note20-projects\27221664.json   (1,149,763 bytes)
C:\Users\JoyRaptor\fadcam-safety-2026-07-26\note20-projects\a32d24e2.json   (5,337,181 bytes)
C:\Users\JoyRaptor\fadcam-safety-2026-07-26\note9-projects\proj_*.json      (10 sandbox files)
```

These are byte-exact pulls taken BEFORE the sharing migration existed. They are the recovery
path if the migration goes wrong. **Do not delete them.** The migration also writes its own
`backups/project-preshare-<ts>.json` on device, but that is one layer; these are the other.

## 3. DEVICES — two phones, different everything

| | Note 9 (sandbox) | Note 20 (THE USER'S REAL PHONE) |
|---|---|---|
| serial | `SANDBOX_SERIAL` | `REAL_SERIAL` |
| model | SM-N960U, Android 10 | SM-N986U, Android 13 |
| screen | **1080x2220** | **1440x3088** |
| holds | disposable sandbox projects | **the user's real work** |
| attached now | NO | yes |

**Tap coordinates are NOT portable between them.** Everything below is Note 9 / 1080x2220
ONLY and must be re-derived from a screenshot on the Note 20:
Faditor nav icon `(627,2108)` · project row 2 `(538,896)` · play/pause `(540,1306)` ·
split `(387,1305)` · export icon `(1013,55)` · export-now `(758,1625)`.

`adb` is NOT on PATH:
`C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe`

**DANGER — the build watcher auto-installs to whatever single device is attached.** Saving a
source file will push a build to the user's real phone. That is fine for shipping a fix and
bad for anything experimental. Prefer the Note 9 for experiments; if only the Note 20 is
attached, think before saving.

## 4. Hard-won operational lessons from this session

- **Never `adb logcat -c`.** Clearing the buffer during a reinstall destroyed the one capture
  of the bug being hunted. It was only recoverable because a copy had been saved seconds
  earlier. Just note a timestamp instead.
- **A "compile-green" claim means nothing without a fresh APK timestamp.** This session opened
  by discovering the previous session's ~35 commits had NEVER been compiled: the watcher had
  died and a stale-from-07-06 javac intermediates dir made every build fail *before*
  type-checking, hiding a real error. Check `dumpsys package … lastUpdateTime` against
  `git log`, and that java processes exist.
  Fix for that failure: `Remove-Item app/build/intermediates/javac/<variant> -Recurse`.
  Never `gradlew --rerun-tasks` (corrupts media3-patched jars).
- **Validate data-touching changes offline against real pulled files BEFORE shipping.** A
  first-wins transcript merge looked obviously safe, and would have replaced a 1112-word
  transcript with a 1110-word one on the user's live project. It was caught only by a
  word-count guard added on a hunch. The rule now: pull the real file, run the algorithm on
  it in Python, print the before/after, THEN write the Java.
- **The device is the user's, and they may be using it.** Screenshot immediately before every
  tap; if focus or orientation changes between screenshots, stop injecting.
- `build.log` is UTF-16 — read via PowerShell `Get-Content -Encoding Unicode`, not `iconv`.
- Gradle needs `$env:TEMP='C:\Users\JoyRaptor\gtmp'; $env:TMP=$env:TEMP`.
- The `Glob` tool is broken on this repo's `C:\+Projects` path — use Bash `ls` or `Grep`.
- Windows Python cannot read `/c/...` paths; pass `C:/...`. A guard script "passed" silently
  over **zero matched files** because of this. Always print the file count.

## 5. What landed this session

| commit | what |
|---|---|
| `292b791` | fixed the build — `staticProp` lives on `Prop`; nothing had compiled since 13:56 |
| `4eda119` | seeded-lane pre-emption: a payload on another type's seeded lane stole that lane, renaming it, flipping its kind and hoisting it up the band (= a silent paint-order change). Device-proven; 4 assertions added to `getlayers_equiv.py` |
| `cfb0452` | **countdown/count-up timer text objects** — `TimerSpec`/`TimerText`, one authority shared by preview+export, 38/38 harness, device-verified counting 0:05→0:01 |
| `2b4530d` | `selectSegment` resolved positions against the OLD selection (stale view index) |
| `58485af` | transcript word-tap: resolve against the transcript's clip, re-home onto the clip that contains the tapped time. **Fixed the user's 0:00 teleport**, verified on their own taps |
| `108cff9` | playhead glides through a cut instead of freezing 110–330ms at every seam |
| `71fd1f8` | **transcript sharing** + legacy-partition migration (see §1) |
| `11c4927` | the unfinished-work audit |

## 6. Open, in priority order

Read `tasks/AUDIT_UNFINISHED_20260726.md` first — it is the map. Highlights:

1. ~~Verify the transcript migration~~ — DONE and verified on device, see §1.
2. **`TrackKind.LAYER` schema hole** (audit 1.2) — DATA-LOSS class, cheapest fix in the list.
   `SCHEMA_VERSION` is still 10 so the downgrade guard never fires for a neutral lane; an
   older build coerces LAYER→VIDEO and re-saves, permanently changing paint order.
3. **Caption size slider** (audit 2.1/2.2) — wired to model + export but never to the preview
   overlay, for a month. Users size captions blind. One setter + one bind + two serializer
   lines.
4. **`layerId: null` drops objects** (audit 1.3) — four `isJsonNull()` guards.
5. Transcript panel step 3 — but per `SPEC_TRANSCRIPT_SHARING.md`, show the WHOLE source with
   the current clip highlighted, NOT the windowing the old plan specified. The user needs to
   read ahead to place breaks.
6. Standing device-verify debt: PiP audio acceptance 2+4 (nobody has listened to a PiP or
   exported one), cross-type Z absolute-geometry A/B, neutral substrate queue items 2,3,5-8.
7. `SPEC_TEXT_ANIMATION.md` — designed, not started.

## 7. Things the user is owed an answer on

- Whether the transcript migration recovered their ~937 words (§1).
- ~~Timer export leg: no export has ever been rendered with a timer in it.~~
  **ANSWERED 2026-07-26 09:05 — the timer renders in the export and counts correctly.**
  A throwaway clone carrying one countdown timer text (`COUNT_DOWN`/`RELATIVE`, min+sec,
  precision NONE, span 0–4000 ms, off-centre at 0.32/0.30) was exported at 480p/Low and
  sampled at four timestamps. Rendered values, read off the frames:

  | t | expected `ceil((4000−t)/1000)` | in the exported frame |
  |---|---|---|
  | 0.35 s | 0:04 | **0:04** |
  | 1.2 s | 0:03 | **0:03** |
  | 2.2 s | 0:02 | **0:02** |
  | 3.2 s | 0:01 | **0:01** |

  4/4, matching `TimerText.format`'s ceiling-seconds contract (the same values
  `tools/jvm-harness/TimerTextTest.java` asserts). This is self-validating in a way a single
  frame would not be: a static text overlay cannot produce four different values in the right
  order, so it also rules out "the timer is baked as one frozen string at export time" — which
  was the plausible failure mode given preview and export share the authority but not the
  render loop.

  *Caveat worth stating: this verifies the RELATIVE countdown path with minutes+seconds and
  precision NONE. `ABSOLUTE` basis, `COUNT_UP`, hours, and the FRAMES/MILLIS precisions are
  covered by the 38/38 JVM harness but have not been through an export.*
- They asked for animated text; the spec exists, the build does not.
