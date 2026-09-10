# DEVICE-VERIFY QUEUE — turnkey checklist (2026-07-12)

> **▶ 2026-07-16 late (sandbox unlocked) — S1–S6 ALL RUN. S1 ✅ (open cebc19e0 → SlideRenderer log →
> mpeg4 MP4 in slide_cache, 413KB), S2 ✅ (title full-opacity mid-hold, dimmed at fade-out, restores
> scrubbing back), S3 ✅ (play crosses video→slide→video on the rendered MP4), S4 trim ✅ (3s→2.2s,
> ripple + undo correct). S5/S6 run by JOYRAPTOR live: S6 ✅ FULL PASS — copied prompt → external AI →
> pasted complex HTML → scrubbed → exported. S5 ✅ slide renders in export BUT 🐛 **transition at the
> slide seam (radial) shows in preview and NOT in export** → new fix item. Also from JoyRaptor's hands-on:
> slide trim should STRETCH the animation (speed-remap) not cut frames; wants inner freeze-zone
> handles (frozen start/animated middle/frozen end); wants double-tap slide → code view/edit sheet.
> All queued as build items in the Fable session.**

> **▶ NEW 2026-07-16 evening (Fable slides session) — AI-slide UI checks, sandbox was PIN-locked
> (Bouncer, `deviceLocked=1`) so only headless halves ran. Ready-made asset: project `cebc19e0`
> ("P0 control2 plain") has a fallback slide "Chapter One" inserted at clip index 1 via
> ApplyEditsActivity; its MP4 is NOT yet rendered — perfect for watching the on-load background
> render happen.** Already PROVEN headless (locked): capture+encode pipeline end-to-end (see
> feature-ai-generated-slides-spec.md Phase 0 status). OWED, needs an unlocked phone, all in
> project cebc19e0:
> - **S1 background render on load:** open the project → logcat `SlideRenderer: Rendering slide`,
>   then `SlideEncoder: Encoded slide MP4 (mpeg4)`; `slide_cache/<hash>.mp4` appears under the
>   project dir. Timeline thumbnail for clip 1 populates after.
> - **S2 scrub:** drag the playhead across the slide clip → live WebView preview tracks position
>   (text fades in ~0.6s, holds, fades out at the end).
> - **S3 play-through:** press play upstream of the slide → crosses into the slide (plays the
>   rendered MP4), then back to video.
> - **S4 trim + transition survive:** drag-trim the slide's right edge shorter; add a transition at
>   its seam; both persist in project.json and preview correctly.
> - **S5 export:** export the project → exported file contains the slide at the right position and
>   duration (frozen final frame if trimmed longer than authored — by design).
> - **S6 addendum UI:** Add asset → "AI slide (animated)" → Copy slide prompt (clipboard gets the
>   contract prompt); Paste slide HTML with any conforming HTML on the clipboard → clip inserts,
>   background-renders; Import .html file via the picker.
> - Slide render now works even LOCKED (setShowWhenLocked) — so S1 can also be re-checked locked.
> `adb install -r` arm64 APK):**
> - Item A1 export re-verify VIDEO PATH: **✅ PASS.** Exported AudioExportVerify (`aeb0517e`, confirmed at
>   the repro state: 600ms GL_SHADER transition after clip[3]=427ms) 480p/Low. Started 01:54:18,
>   **completed cleanly 01:55:34** (~76s): logcat `ExportManager/ExportService: Export completed` +
>   `FaditorEditor: Export saved to:` + `TransformerInternal: Release` (clean), Service destroyed. **NO
>   muxer stall, NO "no output sample" error** — the `313e7fa` (seam-clamp + degenerate-skip) + `148c155`
>   (audio-coverage guard) fixes hold on the exact repro that stalled 2026-07-12. STILL OWED (quick):
>   the audio-only (.m4a) path — re-export with "Export audio only" checked (already survived per prior
>   sessions; confirm on this build).
> - Already CONFIRMED live in the export dialog: F4 "Low bandwidth" chip + "Export audio only (.m4a)"
>   checkbox both present (item A3 partial). AV3 layout live (first audio row expanded, rest thin).
> - `aeb0517e` pre-state backed up to scratchpad `aeb_pre.json` — restore if the export mutated it (it
>   should not; export is read-only on the project).
> - REMAINING Section A: A1 audio-path, A2 G5(b) A/B proof, A3 finish (SAF round-trip), A6 record
>   stop-swap, A7 clip-audio drawer feels. Section B needs JoyRaptor.
>
> **▶ 2026-07-14 ~02:30 (device <note9-serial>, autonomous run continued):**
> - **A5 AV4 waveform settings: ✅ PASS** (sheet opens populated; ANALYSIS toggle live-updates + persists
>   across sheet close/reopen; restored default OFF). First-import eager/lazy chooser still owed.
> - **A4 grade presets: ✅ PASS** (adjust→save named preset→chip→persists→apply snaps grade back→long-press
>   delete). cebc19e0 restored (grade undone, preset deleted). Cross-clip-apply not separately shown.
> - A1 audio-only (.m4a): ✅ PASS (66.88kB output, da96248 copy verified). A1 fully closed.
> - A7 clip-audio drawer: ⏸ deferred (320ms double-tap is real-finger-only; adb can't land it).
> - A6 record stop-swap: ⚠️ Record entry point NOT in the selected-avatar toolbar — needs discovery
>   (likely standalone Studio). Badge to beat: ✦ 21. cebc19e0 unmutated.
> - A2 G5(b): ⏳ scouted — viz IS attached on aeb0517e but host has NO opacity fade; needs a fresh
>   throwaway project + opacity-fade setup + dual export. Best done with full context next session.
> - **Session net: A1(both)+A4+A5 PASS + da96248 bonus. Remaining solo items all need fresh
>   context (A2), real-finger (A6/A7), or file-picker (A3a SAF round-trip — untouched).**
>
> **▶ 2026-07-16 JoyRaptor real-finger walkthrough (live, both phones):**
> - **A7 drawer: gesture PASS** + 4 follow-ups (see item 7) — all 4 CODED + JoyRaptor-verified on device
>   except the round-2 refinements (words-in-filmstrip home, 1px descenders, 45dp drawer + black word
>   band, CC instant invalidate) which are in the build now. Layer-wide shelf: "perfect". CC fix: good.
> - **A6: CLOSED/re-scoped** — no in-editor record exists (see item 6). Stop-swap check folds into
>   point-at-video (item 11).
> - **🐛 NEW: preview double-tap dead** — double-tapping the AVATAR in the preview does nothing;
>   double-tapping CAPTIONS in the preview does nothing. Both should open the item's advanced
>   dropdown (object menu). → build item.
> - **🐛 NEW: visualizer crash (SANDBOX)** — long-press on the visualizer AND tapping the visualizer
>   dropdown both crash the app on 29e37138. NEW instability. Sandbox was unplugged before logs could
>   be pulled — **need the sandbox re-plugged to pull the crash buffer** (logcat -b crash persists a
>   while; also check tombstones). A3a (SAF round-trip) is BLOCKED behind this crash.
> - **🔥 FIXED ×2: the "first lecture on phone" glitch (REAL phone) — TWO stacked bugs.**
>   ⚠️ CORRECTION: my first diagnosis ("recording's tail is truncated, file damaged") was WRONG —
>   JoyRaptor disproved it (FadCam's own player plays the file to the very end; transcription read it
>   all; a NEW project with the file was 100% blank). The raw file is fine. Real root cause:
>   **(bug 1, the regression)** the editor routes every fMP4 source through the remux-to-seekable
>   cache (`FragmentedMp4Remuxer`, cache/remuxed/). At 07:31 on 07-16 a remux of the 2.37GB file was
>   started and KILLED mid-write, leaving a full-size ftyp+free+mdat husk with **NO moov atom
>   anywhere** (byte-verified). `hasRemuxedVersion()` only checked exists+mtime+size≥90% — the husk
>   passed, permanently poisoning the entry: every project resolving that source (incl. new ones) got
>   the husk → ParserException "Loading finished before preparation is complete"
>   (ERROR_CODE_PARSING_CONTAINER_MALFORMED). Before 07:31 no copy existed → raw file used → "used to
>   edit fine". FIX (FragmentedMp4Remuxer): (a) `hasLeadingMoov()` structural check in
>   hasRemuxedVersion — deletes moov-less husks, self-heals poisoned caches in the wild; (b) both
>   remux paths write to a `.part.mp4` temp and atomically rename onto the final name only after
>   validation — an app-kill can never poison the cache again. JoyRaptor's husk manually deleted on-device
>   (raw-file fallback works immediately).
>   **(bug 2, the melt)** the rank-1 recovery hook had nothing to poison for a FORWARD-leg failure
>   and rebuilt the identical playlist → identical error → ~18 rebuilds/sec on the main thread
>   forever (glitched loading screen). FIX: per-clip unpoisoned-failure cap (3) → stop + one toast.
>   Both fixes complement: cap breaks any future storm; validation removes this storm's trigger.
> - Real phone (<note20-serial>) is on an OLD build — install the current APK on it once this build
>   lands so the loop-breaker + walkthrough fixes reach the real device.


The sandbox (SM-N960U, adb `<note9-serial>`) was UNPLUGGED as of this writing, so a large batch
of code-complete work is compile-verified only. This is the single ordered list of everything owed on
device — plug the sandbox in and work top-to-bottom. Items needing JoyRaptor/a real face/a grant are tagged.
Device protocol: take the `DEVICE:` token in LANES.md first; restore any sandbox project you mutate.

Ground truth for any project state: `adb -s <note9-serial> shell run-as com.fadcam.beta cat
files/faditor/projects/<id>/project.json`. Sandbox editor project = `bdd51919…`; audio/export repro
= `aeb0517e…` (AudioExportVerify); avatar test bed = `cebc19e0…` (P0 control2).

## A. Solo-doable (no JoyRaptor, sandbox only) — do these first
1. **`148c155` export audio-coverage fix (RE-VERIFY).** ✅ **BOTH PATHS PASS.** Video path PASS on
   2026-07-14 ~01:55 (aeb0517e 600ms repro, see top marker). Audio-only (.m4a) path PASS 2026-07-14
   ~02:34 (cebc19e0): checked "Export audio only (.m4a)" (Resolution/Quality correctly grey out) →
   Export Now → logcat `ExportManager: Audio-only export started`→`…completed` in ~2s, clean
   `TransformerInternal: Release`, NO "no output sample" stall, `Export saved to:`, Service destroyed.
   BONUS — `da96248` verified on-device: the Export-Complete overlay reads **"Your audio has been saved
   to the Records Tab."** (the audio-specific R.string.faditor_export_complete_summary_audio, not the
   old "Your video…"). A1 fully closed.
2. **`8c306e5` G5(b) piggyback-looks (A/B FRAME-DIFF PROOF).** ⏳ **SCOUTED, needs fresh full context
   (2026-07-14).** Precondition check on aeb0517e (AudioExportVerify): the visualizer IS attached
   (`attachedClipId: 92bec151-379c-42f4-abcd-35e69d19022a`, `attachOffsetMs: 1000`) BUT the host clip
   92bec151 has **NO opacity keyframes** (project.json only has captionStyleKeyframes + volumeKeyframes;
   no opacity/alpha/fade envelope anywhere). So the proof can't run as-is — the host needs an opacity
   keyframe FADE added first. Recommended for next session: use a FRESH throwaway project (don't mutate
   aeb0517e — it must stay at its 600ms export-repro state), add a clip, attach a viz, add an opacity
   fade to the host, then run the dual export. This is a multi-step UI-setup + dual-export + frame-diff
   task — do it with full context, not a continuation tail.
   Export-compositing change under test: an attached visualizer must FADE with its host clip's opacity
   envelope; a DETACHED one must not. Use the absolute-geometry A/B diff method (see the
   ab-export-frame-diff-proof memory — symmetric proofs miss it). Attach a viz to a clip that has an
   opacity keyframe fade, export, confirm the viz dims with the host; detach, export, confirm full-opacity.
3. **`b36a36f` editor items (hand-test).** (a) Visualizer drawer ⇩/⇧ round-trips a style through a
   `.json` file (SAF). (b) Export dialog "Low bandwidth" chip sets the resolution to 720p + quality Low.
4. **`8332370` grade presets.** ✅ **PASS (2026-07-14 ~02:30, cebc19e0).** Filter sheet → dragged Contrast
   0.00→0.61 → "Save as preset…" → named "VerifyGrade" → OK → chip appeared in the presets row →
   persisted across a filter-sheet close+reopen (global library persists). Apply proof: dragged Contrast
   to -0.52, tapped the VerifyGrade chip → Contrast snapped back to 0.61 (chip applies its stored grade).
   Long-press chip → "Delete preset "VerifyGrade"?" dialog → OK → chip removed. CLEANUP: undid the clip
   grade (undo 3→2, Filter icon back to grey) and deleted the global preset — cebc19e0 restored. NOTE:
   cross-clip apply (apply to a *different* clip) not separately shown — tiny 0.4s/0.5s clips wouldn't
   tap-select (playhead scrubbed); apply-path is the same grade-write and was proven on the selected clip.
5. **AV4 waveform settings.** ✅ **PASS (2026-07-14 ~02:22).** Editor Settings → "Waveform visualizer"
   opens the sheet fully populated (loadFrom OK). Toggled "Analyze waveforms immediately" ON — subtitle
   updated live ("Analyzed at import."); swipe-dismissed the sheet, reopened via Settings → still ON
   (saveTo→loadFrom round-trips the ANALYSIS pref). Restored default OFF ("Analyzed when a clip is first
   opened.") so the global analyzeEager pref is unchanged. STILL OWED (needs a fresh import to observe):
   the first-import eager/lazy chooser-ONCE behavior + eager-vs-lazy analysis timing per the choice.
6-CLOSED. **🎯 Record stop-swap — RE-SCOPED by JoyRaptor (2026-07-16): there IS no in-editor record.**
   Recording happens in the screenrecorder branch of FadCam; the editor imports files. The queue item
   was written on a wrong premise. JoyRaptor's actual vision for avatars in the editor: **point the avatar
   at a recording in the project and have it read the face information the way captions read
   transcripts, then hide the source layer** — i.e., the EXISTING point-at-video flow is the intended
   UX (a direct record-into-editor is a possible future feature, not owed now). The stop-swap
   persistence concern transfers to the point-at-video sweep: after a sweep, the ✦ keyframes must
   persist as ONE undo step. Fold that check into item 11 (point-at-video on a face-bearing clip).
   ~~Original item below for history:~~
   **(historical) 🎯 Record stop-swap persistence (avatar).** ⚠️ ENTRY POINT NOT FOUND from the timeline
   (2026-07-14).** On cebc19e0 selected the "A6 Warp Smoke" avatar item (the ✦ 21-keyframe orange bar on
   the Sprite track). The selected-item toolbar is Move/Transitions/Captions/Visualizer/Crop/Transcript/
   Filter/Settings — **NO 🎯 Record / Studio / Track button** (it's treated as a generic sprite). A
   double-tap on the puppet in the preview did nothing. So the in-editor Record entry for an avatar item
   is either (a) under one of those toolbar buttons (Settings?), (b) only in the standalone Avatar Studio
   (person-icon on the Faditor tab → open this avatar → Record), or (c) a gesture I didn't find. NEXT
   SESSION: find the entry first (check standalone Studio path), THEN do the record→stop→badge-grows
   (✦ 21→>21)→one-undo-step run. Note: adb can do the button taps + wait, but avatar-Studio gestures may
   need real-finger. Current badge count to beat: **✦ 21**. cebc19e0 left unmutated (undo 2/redo 0).
   (Last attempt the stop-swap didn't persist — likely a stop-on-onPause discard when the session exited.)
7. **Clip-audio drawer feels (`5179647`).** ✅ **GESTURE PASS, JoyRaptor real-finger 2026-07-14 ~03:2x** —
   "double snap feels pretty good," drawer opens. **4 follow-ups from JoyRaptor's hands-on:**
   (a) **BUG — tap-disambiguation race:** single tap centers the playhead, so on short clips the clip
   shifts under the finger between the two taps → second tap lands on a different clip and no drawer
   opens. Fix: defer the single-tap playhead-centering until the double-tap window expires (or JoyRaptor
   floated inverting: double-tap = center playhead). → Fable fixing (defer-single-tap approach).
   (b) **DESIGN — drawer scope:** double-tap should expand/collapse the audio shelf for ALL clips in
   that layer (one layer-wide shelf state), not just the tapped clip — the per-clip shelf wastes the
   space for the other clips' audio. → JoyRaptor explicitly proposed; implement.
   (c) **DESIGN — transcript-word readability + dead gray strip:** words inside the tape (multi-color on
   high-contrast black) are harder to read than the old bottom-aligned subtle-green; the gray middle
   strip the words used to occupy is now unused. Either drop the gray strip, or keep words out of the
   tape (middle lane / on-video) — ONE design language; main layer differs from other layers only by
   magnetic snapping. → needs a small design call, then implement.
   (d) **BUG — CC tape color ignores animation style when UNKEYFRAMED:** switching caption style
   (Bounce→green, Zoom→blue, …) updates the word animations live but NOT the CC tape color; WITH
   keyframes the color updates correctly. Likely a missed invalidate on the unkeyframed style-change
   path. → Fable fixing.
   Still owed from the original checklist: split-with-drawer-open coherence check.

## B. Needs JoyRaptor / a real face / a system grant
8. **Real-face avatar axis** — Studio 🎯 Track with a real face: head-right must read POSITIVE yaw; if
   inverted, flip `MIRROR_YAW`/`SIGN_PITCH` in `MediaPipeTrackingSource` (one knob, both live+point-at
   paths). Then a real-face record→replay→export on an avatar item (synthetic A/B already proved the
   pipeline; this proves the human loop).
9. **Bubble face-button + clear-stage** — BLOCKED on the "Display over other apps" permission (JoyRaptor
   grants it in Settings; do NOT grant system permissions yourself). Then: bubble face button →
   puppet renders + follows → cycle back to webcam; clear-stage toggle → character floats alone.
10. **New-avatar-from-image** — person icon on the Faditor tab → standalone studio chooser →
    "+ New avatar from image…" → the picker flow (untested).
11. **Point-at-video on a FACE-bearing clip** — the no-face path is smoked; a real face confirms the
    sweep produces a live-driven take (incl. the video-vs-front-cam MIRROR_YAW check).
12. **P0/P1 gapless re-verify on the REAL project** `27221664…` (device <note20-serial>) — needs JoyRaptor's
    USB-debug toggle cycle to re-authorize adb. Play across the freeze-frame / short-speed-clip
    boundaries; frame-hash a screenrecord (no >2-3 identical consecutive frames at 30fps).

## Cleanup notes for JoyRaptor (from prior sessions)
- `cebc19e0` (P0 control2) carries a stray "Enter text" overlay, the injected "A6 Warp Smoke" avatar
  item (good for feel-testing replay), and pushed test art. `project.json.bak` (Jul 7) on-device if
  pristine matters.
- Throwaway projects safe to delete: dino 5s + greater_phase2 (real phone); bisect A/B/C leftovers.
