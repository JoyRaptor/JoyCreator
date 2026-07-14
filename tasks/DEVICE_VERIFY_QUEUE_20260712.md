# DEVICE-VERIFY QUEUE — turnkey checklist (2026-07-12)

> **▶ IN PROGRESS (2026-07-14 ~01:54, sandbox SANDBOX_SERIAL REPLUGGED, current build installed via
> `adb install -r` arm64 APK):**
> - Item A1 export re-verify: **STARTED** a VIDEO export of AudioExportVerify (`aeb0517e`, confirmed at
>   the repro state: 600ms GL_SHADER transition after clip[3]=427ms) at 01:54:18. Settings 480p/Low.
>   Export runs as an on-device background service — the RESULT (complete vs muxer stall) is knowable
>   from logcat + the Records folder even without me. RESUME STEP 1: check whether it completed —
>   `adb -s SANDBOX_SERIAL shell run-as com.fadcam.beta ls -lt files` or the Records tab; grep logcat
>   for "Export saved"/"no output sample". If it produced a valid ~14s Faditor_*.mp4 with no stall → A1 PASS.
>   Then repeat with "Export audio only (.m4a)" checked for the audio path.
> - Already CONFIRMED live in the export dialog: F4 "Low bandwidth" chip + "Export audio only (.m4a)"
>   checkbox both present (item A3 partial). AV3 layout live (first audio row expanded, rest thin).
> - `aeb0517e` pre-state backed up to scratchpad `aeb_pre.json` — restore if the export mutated it (it
>   should not; export is read-only on the project).
> - REMAINING Section A: A1 audio-path, A2 G5(b) A/B proof, A3 finish (SAF round-trip), A4 grade presets,
>   A5 AV4 settings+chooser, A6 record stop-swap, A7 clip-audio drawer feels. Section B needs JoyRaptor.


The sandbox (SM-N960U, adb `SANDBOX_SERIAL`) was UNPLUGGED as of this writing, so a large batch
of code-complete work is compile-verified only. This is the single ordered list of everything owed on
device — plug the sandbox in and work top-to-bottom. Items needing JoyRaptor/a real face/a grant are tagged.
Device protocol: take the `DEVICE:` token in LANES.md first; restore any sandbox project you mutate.

Ground truth for any project state: `adb -s SANDBOX_SERIAL shell run-as com.fadcam.beta cat
files/faditor/projects/<id>/project.json`. Sandbox editor project = `bdd51919…`; audio/export repro
= `aeb0517e…` (AudioExportVerify); avatar test bed = `cebc19e0…` (P0 control2).

## A. Solo-doable (no JoyRaptor, sandbox only) — do these first
1. **`148c155` export audio-coverage fix (RE-VERIFY).** `aeb0517e` is LEFT at the 600ms-seam-2 repro
   state. Just re-run the export (both the normal path and, if quick, audio-only). EXPECT: completes,
   no muxer "no output sample in 10000ms" stall. (The earlier `313e7fa` fix FAILED this same repro on
   2026-07-12; `148c155` is the follow-up audio-coverage guard — this confirms it.)
2. **`8c306e5` G5(b) piggyback-looks (A/B FRAME-DIFF PROOF).** Export-compositing change: an attached
   visualizer must now FADE with its host clip's opacity envelope; a DETACHED one must not. Use the
   absolute-geometry A/B diff method (see the ab-export-frame-diff-proof memory — symmetric proofs miss
   it). Attach a viz to a clip that has an opacity keyframe fade, export, confirm the viz dims with the
   host; detach, export, confirm it stays full-opacity.
3. **`b36a36f` editor items (hand-test).** (a) Visualizer drawer ⇩/⇧ round-trips a style through a
   `.json` file (SAF). (b) Export dialog "Low bandwidth" chip sets the resolution to 720p + quality Low.
4. **`8332370` grade presets (hand-test).** Filter sheet → adjust grade → "Save as preset…" → name it →
   appears as a chip → tap another clip → apply the chip → grade applies; long-press chip → deletes.
5. **AV4 waveform settings (once landed this session).** Editor Settings → "Waveform visualizer" opens
   the sheet; changes persist across relaunch. First audio import shows the eager/lazy chooser ONCE;
   choice persists; eager vs lazy analysis behaves per the choice.
6. **🎯 Record stop-swap persistence (avatar).** On `cebc19e0`, select the "A6 Warp Smoke" avatar item →
   🎯 Record → record ~3s (synthetic tracking is fine) → tap Stop → the ✦ key-count badge should GROW
   (take kept) and it should be ONE undo step. (Last attempt the stop-swap didn't persist — likely a
   stop-on-onPause discard when the session exited; needs a clean in-app run.)
7. **Clip-audio drawer feels (`5179647`).** Double-tap a master clip → audio tape shelf slides out;
   with a TRANSCRIBED clip, confirm the transcript words relocate to the drawer's inside bottom; split
   the clip with the drawer open (should stay coherent); real-finger 320ms double-tap open/close feel.

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
12. **P0/P1 gapless re-verify on the REAL project** `27221664…` (device REAL_SERIAL) — needs JoyRaptor's
    USB-debug toggle cycle to re-authorize adb. Play across the freeze-frame / short-speed-clip
    boundaries; frame-hash a screenrecord (no >2-3 identical consecutive frames at 30fps).

## Cleanup notes for JoyRaptor (from prior sessions)
- `cebc19e0` (P0 control2) carries a stray "Enter text" overlay, the injected "A6 Warp Smoke" avatar
  item (good for feel-testing replay), and pushed test art. `project.json.bak` (Jul 7) on-device if
  pristine matters.
- Throwaway projects safe to delete: dino 5s + greater_phase2 (real phone); bisect A/B/C leftovers.
