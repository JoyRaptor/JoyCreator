# STU-4: Studio sweep leftovers

**Milestone:** M1  ·  **State:** 🟡 11-10: the 10-28 red items fixed and built, partly phone-checked (see "Fixed 11-10"); was 🔨 re-checked 10-28 (code scout + Note 9 agent pass 4): most items already fixed; three new bugs found  ·  **Owner:** lead  ·  **Checked:** 2026-11-10

## Why it matters
Small rough edges the device sweeps found. Each one is the kind of thing that makes an app feel unfinished.

## Done when
- [ ] Each item re-checked on the phone: fixed already (write the commit) or fixed now, or written up as a
      defect in STATE §9.

## Re-check 10-28 (screens in `C:/Temp/qa-sweep-20261028/`)
- ✅ Caption/text motion: Type → Fade → back changes the label, the tile and the preview (not stuck). The label is cut off ("Type · 25% in / 0% out …").
- ✅ Hide eye hides the text; the Outline swatch opens the picker and the outline shows (tested on a Text overlay; a caption not tried).
- ✅ Invert on a picture shows in the preview (it acts as an adjustment layer: everything beneath inverts).
- ✅ `OverlayNavUtil` child-overlay close (7ffd27e4b, 10-16).
- ✅ Sprite bend net sits on the sprite.
- ✅ Colour picker hex is one line (d1655f38a); "#FF000B" touches the column edge (report only).
- ❓ Not reachable by the agent: puppet RECORDING tab (no Puppet tab found from Studio drawers), sprite Lanes tab, audio envelope (see the audio bug).
- ✅ Add → Audio with a real song (`vislab_demo.m4a`) works (the WAV failure was that test file). **Found on the way and fixed 10-28:** Fix audio never baked on a phone (file name from the raw URI, then no output format); now passes.
- ✅ Undo of a sprite Bend leaves it crisp again (fabedb713, agent-checked 10-28).
- ✅ (fixed 11-10, below) ~~**Open:**~~ after a Fix audio cache hit the waveform keeps the unfixed look; Clean acts on the video clip because selecting an audio clip leaves the video clip selected too; the editor title's tap does not open Rename (hold does).
- ✅ (fixed 11-10, below) ~~**New:**~~ the toolbar's last button "Edit" toggles to "Done" with no visible effect; after closing a text drawer, tapping the clip or preview doesn't reopen it (hold does, since 10-28).

## Fixed 11-10 (Studio bugs session, commits 99f8b3ad5 + 3422a7a5a on `claude/studio-bugs-20261110-f15370`)
- ✅ **Fix audio waveform stays unfixed.** Root cause was bigger than the waveform: a Fix/Clean bake wrote only
  the clip's slice, starting at 0, while the clip kept its in-point. On any trimmed or split clip the fixed sound
  played EARLY by the in-point and ran out before the out-point (ffmpeg on the PC: a beep at 9 s landed at 2 s),
  and the waveform lookups asked for a span past the end of the file, so they fell back to the stored (unfixed)
  picture, which nothing ever re-read. Now a bake opens with in-point silence and keeps the source's clock
  (`BakedAudioCache` "Timing", key v2-aligned; all three chains checked with ffmpeg 7 on the PC), and the stored
  waveform is re-read whenever the clip changes file (`audio/SourceWaveform`), on Fix, undo and redo. Also: Fix after
  Clean keeps the true original as its revert target; the Fix dialog's progress bar now moves.
- ✅ **Clean acts on the video under an audio selection.** Clean (cut silences) read only the film's selection,
  which stays set when a layer item is picked. Now a selected audio clip is what Clean works on (same rule as Split,
  Fix audio, Beats): same dialog with a live count, the clip becomes its kept pieces, one undo (`audio/AudioSilenceCut`).
  The film no longer DRAWS as selected while a layer item holds the selection, and a tap on the film takes the
  selection back (it used to toggle the hidden film selection off).
- ✅ **Title tap does not rename.** The title was `focusableInTouchMode`: Android spends the first tap giving such a
  view focus, so only a second tap clicked. Removed (the marquee runs from `setSelected`); hover label added.
- ✅ **Toolbar "Edit" → "Done" with no visible effect.** It is backed: Edit mode lets you hold a tool and drag it to
  reorder, and drag past the bar to pin it. Its only signs were a 2.5° wiggle (gone when animations are off) and a
  slightly greener bar. Now every tool sits on a raised pill while arranging, a one-line hint says what to do, and
  the Edit chip has a hover label.
- ✅ **Tap does not reopen a closed text drawer.** Tap = select, and the box was selected already, so the tap had no
  job; only double-tap and hold opened it. Now a tap on the already-selected text or picture (its tape on the
  timeline or its box in the preview) opens its drawer when no drawer is open. Double-tap and hold unchanged.
- ✅ **Joybot `add_marker` undo gap.** Joybot's chat parks each request's "before" as project.json alone, and marks
  live beside it, so an undo step that a bridge/live write merged into had no marks and Undo left Joybot's mark.
  `swapInProject` (the one door for outside changes) now gives the AI step on top the marks the editor held before the
  swap whenever the step lacks them (`UndoManager.completeTopAiBefore`). `add_marker` switched on. Limits: if a chat
  visit parks several steps and a LATER one adds a mark, undoing that later step leaves the mark until the first
  step is undone too; if Android recreated the Studio while the chat was open, the editor's "before" already holds
  the mark. Both go away if the chat's Step.before carries the marks (`MarkerWorkspaceStore.toSnapshotJson` under the
  editor's undo key): a Joybot-lane change, not made here. No sidecar save code was changed.
- Proof: APK built 11-10 13:54 (and again after the audio-Clean wording). Puppet/sprite hover strings (ANI-1 C7) compile
  with it, nothing broke. Note 9 11-10, throwaway project "Untitled 3": ✅ one tap on the title opens Rename; ✅ Edit puts
  every tool on a raised pill with the hint, Done removes them; ✅ selecting a song lights the audio tools and Clean opens
  on it (a 1:25 song has no gaps, so no cut to see); ✅ a swipe on the film took the selection back from the song.
  Not seen on the phone: the audio cut itself, the text-drawer reopen tap (the phone was taken by another session
  mid-test), Fix audio's waveform, Joybot add_marker undo (needs the bridge). The test sound `stu4_gaps_test.m4a`
  (2 s tone / 2 s silence) is in the Note 9's Downloads for the next check.
- Found, not fixed (outside UI/undo, or not reached): the Clean tab code in `AudioDrawerTabs.cleanTab` is never
  called (the Fix toast says "Revert in Clean tab", which does not exist); inside it, undoing a bake deletes the baked
  file, so Redo would point at a missing file, and Revert's redo uses a bare path. Audio clips' cut spans
  (`removedSpans`) are drawn on the timeline but playback and export ignore them. Clips already pointing at an
  old-style (v1) bake keep its early timing.

## 11-13 (Studio lane, from `tasks/audits/remaining_work_20261113.md`)
- STU-B2 Clean tab: wired into the audio drawer ("Clean": Reduce noise, Normalize loudness, Revert to original), so
  Fix audio's "Revert in Clean tab" points at something real. Its own faults fixed first: Undo of a Revert left the
  original playing (redo and undo were swapped), Revert's redo used a bare path, Undo of a bake deleted the
  processed file so Redo pointed at nothing; every swap now re-reads the waveform and rebuilds the preview players.
- STU-B4 old-style bakes: on open, a clip whose processed file is shorter than its out-point (made before 11-10,
  early by the in-point) plays its original at once and is re-baked in the new style in the background
  (`audio/EarlyBakeRepair`). Not a load-code change: it edits the open project like any other edit.
- STU-E1 audio-only project read 00:00 on reopen: the total-time display returned early when there was no film.
  It now shows where the last sound ends. Whether the clip itself draws on reopen: phone check owed.
- STU-B3 (diagnosis, not fixed; bigger than the audit says): cut spans (`removedSpans`, made by striking transcript
  words or tapping a yellow silence gap) are skipped by the PREVIEW for film clips only and ignored by EXPORT for
  every clip, film and audio alike (no reader under `export/`). So a struck word plays in the file. Two ways out:
  (a) export expands each clip's spans into kept pieces before building the composition, and TimeMap maps
  riders across the closed gaps (preview must then close gaps the same way, today it only jumps the playhead);
  (b) every action that makes a span makes pieces instead, as Clean and Joybot's cut already do (D8), keeping
  strikes editable through the transcript. (b) is the smaller, single-authority change; it needs a decision on
  whether a struck word stays re-instatable after the cut. Rule 5 (preview equals export) is broken until then.
- STU-B5 (diagnosis only, Save/engine row): the chat parks each request's before as `ProjectStorage.toJson`
  without marks (`AIChatState.Step.before`). Fix on the Joybot/save side: add the editor's undo marks payload
  (`MarkerWorkspaceStore.toSnapshotJson` under `com.fadcam.editor.undo.markerWorkspace.v1`) to each Step.before
  when the chat parks it; the editor already restores that key.

## 11-13 afternoon (Studio lane): results by device
- **Note 9:** text-drawer reopen tap (timeline and preview) ✅; Clean cut a 2 s/2 s gap sound into 3 pieces, one
  Undo back ✅ (pieces landed in a new bottom row: fixed, `Timeline.insertAudioClips`, not re-seen); Fix audio's
  waveform follows Fix/Undo/Redo ✅; Clean tab Revert/Undo/Redo ✅ (after Revert its Reduce noise/Normalize chips
  vanished and its status line ignored Undo: fixed, not re-seen). "Untitled audio 3" would not open from the Library
  (a tap did nothing): Library lane.
- **Tab S8 Ultra (landscape, test tablet):** TX-01 "Look sliders" shows while a title wears a look, TextLab's panel
  opens, a slider drag is one undo step ✅; the sheet opened only peeking (fixed: opens expanded, not re-seen). A
  double-tap opens the text drawer and a second double-tap closes it ✅ (S12). The Neon Bar title drew unlit/plain at
  0:00 on the tablet, so the slider's visual effect was not judged (TextLab lane to look).
- **Landscape layout notes (tablet):** the Library's Start New Project sheet only peeks (drag it up to use it); the
  floating pencil button bottom-left covers the tool row's first cell ("1x"); the text drawer's top rows sit over
  the preview's upper third. Studio otherwise lays out well: the whole tool row fits.
- **Also 11-13:** D7 (a seek exactly on a cut now shows the later clip, as selection and file do); Clip.relinked keeps
  the clip's fades (copy-lint); caption style chip is one undo step (video and audio).
- **Test run:** `run-studio-all.sh` 43 pass, 9 fail; the same 9 fail on a clean joy-creator (caption, fx, mesh,
  motionrange, preview-parity, puppet, specq, specr, spect): old breakage, not this work. Needs its own job.
- **Not done:** Joybot add_marker undo (needs the bridge); the audio-only project's clip on reopen; old early bakes on
  open (no such project to hand); D9 rounding, B3 export of cut spans (diagnosed above).

## 11-13 evening (Studio lane, no device reachable)
- **All 52 Studio suites green** (`run-studio-all.sh`), for the first time: the 9 silent ones were a mesh-engine
  annotation import (5 suites), two missing test stubs (2), a stale gradient default in a test (1), and a parity lint
  that did not know the preview routes pictures through `wantsGlExport()` (1; now checked against the model's code).
- **STU-D1 hover labels:** `util/HoverLabels` gives every tappable Studio control that has a spoken name its name as
  a hover label (Android 8+, re-applied after layouts, drawers included; dialogs/sheets in their own windows are not).
  Controls with no name at all still need one by hand: recount owed. Not seen with a stylus yet.

## What is left (from `tasks/todo.md` on 10-16/17; may already be fixed)
- [ ] Caption motion "Type – word" stuck.
- [ ] Caption hide-eye and outline swatch show no visible response.
- [ ] Invert effect not visible on an image overlay.
- [ ] Puppet RECORDING tab button column clipped.
- [ ] Sprite Lanes tab pills sit over the preview edge.
- [ ] Audio envelope Clear / In oddities.
- [ ] `OverlayNavUtil.dismiss` hides the whole overlay but pops one level (Library → Trash close path).
      The 10-17 UI handoff says this was fixed for child overlays: confirm.
- [ ] Sprite warp net looks detached from the sprite (owner report 10-15).
- [ ] Drawer helper duplicates (top 10 in `tasks/audits/studio_tools_audit.md`).
- [ ] Colour picker hex field wraps on the phone. **The owner said do not touch the colour helper**: report only.

## Files and traps
- Check git log for each before fixing; several were worked on after the list was written.
