# EXECUTION PLAN — Asset Browser (finish/un-bust) → Layers (keystone)

**For:** an implementing AI agent. **Audience assumption:** capable coder, but follow this literally —
do NOT improvise architecture. When a step is ambiguous or you'd have to guess a product decision,
**STOP and ask the user** instead of inventing one.

**Companion docs (READ FIRST, in order):**
1. `tasks/handoff.md` — project overview + most-recent state.
2. `tasks/DEVICE_CONTROL_RUNBOOK.md` — how to build/install/screenshot/tap/log on the phone. **Esp.
   §7b (read live `project.json` via `run-as`), §7d (capture export failures), §7f (you can't run Gradle —
   use the watcher).**
3. `tasks/PLAN_asset_browser_v2_layers.md` — the DESIGN + intent for everything here. This execution
   plan turns that design into ordered, verifiable steps. When they disagree, the design doc wins on
   *intent*; this doc wins on *order/process*.
4. `tasks/road_map.md` — Phase 4 (asset browser) + Phase 5 (layers) and their existing DONE items.
5. `tasks/DIAG_20260626.md` — recent export work + the verification techniques you'll reuse.

---

## 0. How you must work (non-negotiable)

- **Always-green.** Never end a step with the tree non-compiling. If you can't make it build, REVERT
  your change and report. The watcher logs to `build.log` (UTF-16): `tr -d '\000' < build.log | tail -40`
  — wait for the FINAL `BUILD SUCCESSFUL` before testing.
- **You cannot run Gradle yourself** (sandbox blocks it). The user runs a continuous-build watcher
  (`watch-build.ps1`) that rebuilds + reinstalls on every save. If `build.log` is stale, ask the user to
  start it.
- **Ship in SMALL chunks.** Each milestone below is independently shippable and has explicit
  **acceptance criteria you verify ON THE DEVICE**. Do them in order. Don't start the next until the
  current one is verified.
- **Verify, don't assume.** Use the runbook: `am start`, screenshot (`exec-out screencap -p > f.png` in
  Bash, or `screencap` + `adb pull` from PowerShell — PowerShell `>` corrupts PNGs), tap by coordinate,
  re-screenshot. For exports, pull the MP4 and check it with `ffmpeg` (audio RMS) + frame extraction
  (see DIAG_20260626.md for the exact commands).
- **Read the live project to know ground truth:** `adb -s REAL_SERIAL shell run-as com.fadcam.beta cat
  files/faditor/projects/<id>/project.json`. Newest dir under `.../projects/` = active project.
- **Data safety first.** Anything that copies/moves/deletes user media must be opt-in, reviewable, and
  COPY-not-move by default. Never delete originals without an explicit confirmed checkbox.
- **No git commits.** Docs + code only. Update `tasks/handoff.md` and `tasks/road_map.md` status after
  each verified milestone.

---

## M0 — Orient & diagnose the CURRENT "busted" state (NO code changes)

The user calls the asset browser "busted." Before changing anything, find out exactly how.

**Steps:**
1. Read the companion docs above.
2. Confirm watcher is running (`build.log` fresh, ends in `BUILD SUCCESSFUL` then `Waiting for changes`)
   and device `REAL_SERIAL` is connected.
3. Pull the active `project.json`; note its clips, audio, asset URIs (`file://` internal vs SAF
   `content://` vs `project://`).
4. On the device, open the editor → open the asset browser. Exercise it: open/resize/scroll, tap an
   asset, the insert affordance (green/yellow arrow), insert at playhead, insert before/after, long-press
   (rename), drag. Screenshot each step.
5. Specifically reproduce **B1 (data loss):** insert an image from the SD-card pinned folder; note in
   `project.json` what `sourceUri` got stored (SAF child `content://`? `project://assets/...`?). If it's
   already `project://` / internal `file://`, B1's copy-on-insert is working; if it's a raw SAF child URI,
   it will go black on reinstall.
6. **Write `tasks/DIAG_assetbrowser_<date>.md`** listing the concrete, reproduced bugs (with screenshots
   referenced) and which design items (B1/A3/A5/C) are actually incomplete vs already working.

**Acceptance:** a written diagnosis of real current bugs. **Do not fix anything in M0.** If the diagnosis
shows some milestones below are already done, mark them and skip.

---

## ASSET BROWSER — finish & un-bust (Phase 4 remainder)

> Most of Phase 4 is built (resizable panel, persistence, insert affordance, rename — see road_map 4.1–4.4).
> The remaining, highest-value work is **B1 data-integrity** and **A5 drag-drop**, plus control-row rewire.

### M1 — B1: missing media must show as MISSING, never silent black  ⟵ the core "un-bust"

**Why first:** the user's worst pain is assets silently going black after reinstall. Even before perfect
copy-on-insert, the app must never show a dead source as black — it must show a clear MISSING state with a
relink path. This is the single most important asset fix.

**Files:** `FaditorEditorActivity.java` (clip preview / asset resolution), `AssetBrowserPanel.java`,
likely a small new `MissingMediaBadge`/overlay. Check `ProjectStorage.fromStorageUri` and how a clip's
source is resolved for preview/thumbnail.

**Steps:**
1. Find where a clip's source URI is resolved for preview + where the thumbnail/first-frame is decoded
   (search `MediaMetadataRetriever`, `resolvePlaybackUri`, `setDataSource`, thumbnail loaders).
2. Add a single `boolean isSourceResolvable(Clip)` (and same for audio): can we open an InputStream /
   the file exists / the SAF grant is live? Cache the result per URI.
3. When NOT resolvable: render a distinct **MISSING** placeholder (icon + filename + "Tap to relink") in
   the preview AND on the timeline clip AND in the asset browser — never a black frame. Wire the tap to
   the existing relink flow (`relinkAsset` / band-aid relink — search for it).
4. On export, a MISSING clip should be skipped-with-warning or block export with a clear toast — NOT a
   silent black segment. (Decide WITH the user if unsure; default: block export + toast listing missing
   files.)

**Acceptance (device):** Create/confirm a clip whose SAF grant is dead (or temporarily revoke one). It
shows a clear MISSING state (not black) in preview + timeline + browser, and tapping it opens relink.
After relink to a live file, it previews normally.

### M2 — B1: "Consolidate Project" review screen (pack-up-and-move)

**Design is fully specified** in `PLAN_asset_browser_v2_layers.md` → "Consolidate Project (on-demand
action)". Build exactly that. The **review/manifest screen is REQUIRED** and is the safety feature.

**Files:** new Consolidate UI (a dialog/Activity/bottom-sheet), `FaditorEditorActivity.java`,
`ProjectStorage.java` (it already has schema v6 `project://` relative paths + `toStorageUri`/`fromStorageUri`).

**Steps:**
1. Build the manifest screen: one row per still-referenced ORIGINAL asset (not derived/cache artifacts),
   each with thumbnail, **source folder full path (horizontally scrollable)**, **per-file size**, a running
   total, and an **include/exclude checkbox**.
2. Action = **COPY** each checked asset into `<projectDir>/assets/<uuid>.<ext>`, dedupe by content hash,
   skip anything already inside the project dir. Run atomically (copy to temp, swap refs only after all
   selected copies succeed).
3. Rewrite those clips'/audio'/overlays' `sourceUri` to the internal file (saved as `project://assets/...`
   relative path via existing adapters). Confirm total size before starting.
4. AFTER copy, OFFER (separate, default-OFF, opt-in checkbox) to delete the originals from where they were
   copied. Only delete the ones the user explicitly checks.
5. Never touch slide render cache / remux copies.

**Acceptance (device):** Run Consolidate on a project that references an SD-card asset. The manifest shows
it (path/size/thumbnail). After consolidating, `project.json` references `project://assets/...`; the asset
folder contains the copy; the original is untouched (unless delete was opted-in). **Reinstall the app →
the project still shows the asset (no black).** This proves the portability goal.

### M3 — A5: drag-and-drop insert

**Design:** `PLAN_asset_browser_v2_layers.md` → A5. Buttons (A3) already exist; this adds the drag path.

**Files:** `AssetBrowserPanel.java`/`AssetBrowserAdapter.java` (start drag), `EditorTimelineView.java`
(drop target, split-line, edge auto-scroll, minimap dwell-jump), `FaditorEditorActivity.java` (glue +
insert ops — reuse existing `insertAssetAtIndex`/split-insert).

**Steps:** drag thumbnail → while dragging show a **yellow dotted split line** at the proposed split that
tracks the finger; dwell over the minimap region jumps the timeline; near edges auto-scroll; release over
the timeline inserts (split+insert if mid-clip) reusing existing insert ops; drag back into the asset
window cancels; drag onto the A3 icons performs that action. (Drop-onto-new-layer is deferred to M10.)

**Acceptance (device):** drag an asset, drop mid-clip → clip splits and asset inserts at that point; the
yellow split line tracked the finger; dragging back into the panel cancels with no change.

### M4 — Control-row rewire + relink relocation (section C)  [partly layers-aware]

**Design:** `PLAN_asset_browser_v2_layers.md` → section C. Build the UI now; the master-link TOGGLE's
*effect* only fully matters once layers exist (M5+), but the control + relocation ship now.

**Files:** `activity_faditor_editor.xml`, `FaditorEditorActivity.java`.

**Steps:** magnet icon = global soft-snap (consolidate the per-overlay `overlaySoftSnapEnabled`/`btnSoftSnap`
into one global magnet; long-hold = snap prefs); link icon = master-link toggle (long-hold = link prefs);
**move "relink media" out of the control row into the long-press media menu** (band-aid icon L+R of the
reorder title); **play/pause MUST stay centered over the playhead** regardless of how many side icons
exist (anchor it center-in-parent with equal-weight flanking groups — this is a hard requirement, see
handoff §5.5 history).

**Acceptance (device):** control row = [magnet] … [undo] [play/pause centered] [redo] … [link]; relink no
longer in the row (now in long-press menu); adding/removing a side icon never shifts play/pause off the
playhead.

---

## LAYERS — the keystone (Phase 5). Do NOT start until M1–M2 are solid.

> Build phasing comes from `PLAN_asset_browser_v2_layers.md` → section "L" (steps 0–7; steps 0–0f already
> DONE: multi-lane audio, VIZ/CC/overlay rows, tappable + long-press-delete layer rows). The milestones
> below are those steps made concrete + verifiable. **The model change (M5) is the riskiest thing in the
> whole roadmap — treat it with extreme care.**

### M5 — Schema v6 Track model + migration + back-compat shims (NO visible change)

**Goal:** introduce the Track/TimedItem model WITHOUT changing any behavior. Existing projects must
load, edit, preview, and export *byte-for-byte equivalently*.

**Files:** `FaditorProject.java`, `Timeline.java`, `ProjectStorage.java`.

**Design (from the plan):**
```
Timeline { rippleMode; masterTrack:Track(MASTER); layers:[Track]; audioTracks:[Track(AUDIO)] }
Track { id, kind:MASTER|VIDEO|IMAGE|TEXT|STICKER|AUDIO, name, zIndex, collapsed, hidden, locked, muted, items:[TimedItem] }
TimedItem { timelineStartMs, clip|textOverlay|... }
```
**Steps:**
1. Add the classes additively. Migrate on load: `clips → masterTrack.items` (sequential), `textOverlays →
   one TEXT layer`, `audioClips (have offsetMs) → an AUDIO track`. Bump `SCHEMA_VERSION`.
2. **Keep `getClips()`, `getTextOverlays()`, `getAudioClips()` as thin shims** over the new model so ALL
   existing code keeps working unchanged. Do not refactor call sites yet.
3. Old (v5/v7-current) projects load unchanged; new fields are additive.

**Acceptance (REGRESSION — critical):** Take an existing project. BEFORE M5, export it and keep the MP4.
AFTER M5 (no other change), export it again. The two exports must be equivalent (same duration; spot-check
frames + audio RMS per DIAG_20260626 methods). Editor preview/scrub unchanged. If ANYTHING differs, the
migration is wrong — fix before proceeding. **This is the gate for all later layer work.**

### M6 — Timeline UI: pinned master + collapsible floating/audio rows + per-track toggles

Much exists (rows render, tappable, long-press delete). Extend to the Track model + the phone-optimized
layout from the design.

**Files:** `EditorTimelineView.java`.

**Steps:** master row PINNED (always visible) while vertical scroll moves floating layers above + audio
below; each floating layer **collapsible via caret** (expanded = thumbnails, collapsed = thin summary
strip); per-track **hide/lock/mute** toggles in each track header; drive rows from `Timeline.layers` /
`audioTracks` (not the old flat lists) now that the model exists.

**Acceptance (device):** multi-row timeline; master stays pinned on vertical scroll; carets collapse/expand
rows; hide/lock/mute toggles render and affect that track (hidden→not previewed, locked→not editable,
muted→no audio).

### M7 — Edit floating items on their rows (move / trim / delete on a layer)

**Files:** `EditorTimelineView.java`, `FaditorEditorActivity.java`.

**Steps:** drag a floating item horizontally to change its `timelineStartMs`; trim its in/out on the row;
delete it. Master track keeps its existing behavior (sequential/ripple). Floating items are free (absolute
time).

**Acceptance (device):** move a text/image item in time on its own row and the preview reflects the new
time; trim and delete work; master clips unaffected.

### M8 — GL preview compositor (the hard one) — PHASED, with a safe fallback

**Do this in two sub-phases. 8a is high-value/low-risk; 8b is the genuinely hard part — do it last,
behind a flag, and be ready to defer it.**

**M8a — Composite IMAGE + TEXT + SLIDE layers over the master (low risk).**
These already render via overlay/bitmap/WebView paths. Extend the preview so that, at playhead time `t`,
each visible floating IMAGE/TEXT/SLIDE layer item draws over the master at its absolute time + transform +
opacity. No second video decoder needed. This covers the majority of real "layers" use (captions, lower-
thirds, stickers, slides over a talking head).
- **Files:** new lightweight compositor view OR extend the existing overlay layer stack; `FaditorPlayerManager`.
- **Acceptance:** put a text + an image layer at an absolute time over a master video. In live preview AND
  while scrubbing, they appear/disappear at the right times over the master, positioned correctly.

**M8b — Composite a second VIDEO layer (high risk — last).**
Real-time mixing of a 2nd decoded video over the master. Needs pooled MediaCodec/ExoPlayer instances (or a
frame cache) and a GL surface mixing master+layer per frame. This is the heaviest piece in the roadmap.
- Gate behind a feature flag so 8a ships independently.
- If it proves too costly on-device (frame drops), **STOP and report options to the user** (e.g. limit to
  one overlay video, or pre-render overlay video layers). Do not ship a janky preview.
- **Files:** new GL compositor, `FaditorPlayerManager.java`.
- **Acceptance:** a 2nd video layer composites over the master in live preview + scrub without major jank.

### M9 — Export mapping for N layers

**Files:** `ExportManager.java` (reuse the overlay/transition/caption compositing you now understand).

**Steps:** map master + each layer to the Media3 `Composition`: positioned IMAGE/TEXT/STICKER/SLIDE layers
via `OverlayEffect`/`BitmapOverlay` (same path as `CompositeExportOverlay`); a second VIDEO layer via an
additional `EditedMediaItemSequence` (+ overlay/blend). Respect each track's hidden/muted/opacity/transform.

**Acceptance (export-verify with `ffmpeg`/frames):** a project with a floating image/text layer (and, if
8b shipped, a video layer) EXPORTS with the layer composited at the right time/position — and the exported
frames MATCH the live preview at the same timestamps. (This is the "export looks like preview" bar that the
export-fix round established.)

### M10 — Drag-between-layers + drop-to-new-layer (completes A5)

Drag items between rows (change layer / z-order); drag from the asset browser onto empty space below the
last row creates a NEW layer (the A5 deferral). **Files:** `EditorTimelineView.java`,
`FaditorEditorActivity.java`. **Acceptance:** drag an item from one layer to another; drop an asset below
all rows → a new layer is created with that item.

### M11 — Ripple/gap toggle + (later) pin-to-master linking

Master-track `rippleMode` toggle: `ripple` (deletes/trims pull following master clips left) vs `gap`
(leave a hole). Floating layers stay at absolute time in v1. LATER: optional per-item `linkToMaster` flag
(pin-to-master) wired to the M4 link toggle. **Files:** `Timeline.java`, `EditorTimelineView.java`,
`FaditorEditorActivity.java`. **Acceptance:** toggling ripple/gap changes master delete/trim behavior as
specified; floating layers don't shift.

---

## Suggested improvements to the original design doc (apply these)

The design in `PLAN_asset_browser_v2_layers.md` is solid on intent. This execution plan adds what a less-
capable AI needs and what experience from the export-fix round taught:
1. **Diagnose-first (M0).** Don't trust "DONE" labels — verify current behavior on-device and write it
   down before changing code. ("Busted" may be one specific bug, not everything.)
2. **B1 ordering:** ship the **MISSING-not-black relink state (M1) BEFORE Consolidate (M2).** Stopping the
   silent data loss is more urgent than the pack-up feature, and is simpler.
3. **Every milestone is device-verifiable.** The original doc lists features; this one gives acceptance
   criteria you can check with the runbook + `ffmpeg`/frame verify. Use them as the definition of done.
4. **M5 is a regression gate, not a feature.** The schema/Track migration must produce identical exports.
   Diff before/after. This is the highest-risk change and the design doc under-emphasizes the regression
   risk.
5. **Split the GL compositor (M8a/M8b).** The design calls it "the big one" but treats it as one step. The
   image/text/slide compositor (8a) is low-risk and covers most use cases; the second-video compositor
   (8b) is the actual hard part — flag it, do it last, and be willing to defer/limit it rather than ship
   jank.
6. **Export-matches-preview is now an enforceable bar.** After the export-fix round, you can pull the MP4
   and compare frames/audio to the live preview. Hold layers to that bar (M9).
7. **When a product decision is ambiguous, STOP and ask the user.** (e.g. M1: block export vs skip missing
   clips; M8b: how to handle a janky second-video layer.) Don't invent policy.

---

## Status tracking
After each verified milestone: tick it here, update `tasks/road_map.md` Phase 4/5 status, and append a
one-line entry to `tasks/handoff.md`. Keep the tree green throughout.

- [ ] M0 diagnose  [ ] M1 missing-not-black  [ ] M2 consolidate  [ ] M3 drag-drop  [ ] M4 control row
- [ ] M5 schema/Track (regression gate)  [ ] M6 multi-row UI  [ ] M7 edit floating items
- [ ] M8a image/text/slide compositor  [ ] M8b video compositor  [ ] M9 export N layers
- [ ] M10 drag-between-layers  [ ] M11 ripple/gap
