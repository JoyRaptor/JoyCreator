# Order review — 2026-07-25 (after the neutral-substrate lane)

Checkpoint review per JoyRaptor's directive: after two tasks, re-examine what order the remaining
work should run in, and what must be built or moved so that bugs don't develop.

## What changed, and what it made REACHABLE

The neutral substrate did not add a feature so much as remove a constraint, so the risk is not
in the new code — it is in **combinations that were previously unreachable**. Audited each:

| Newly reachable | Verdict |
|---|---|
| Marquee selects mixed types on one lane → batch delete | **SAFE.** `performMarqueeBatchDelete` was already fully type-agnostic (text/sprite/waveform/PiP/audio, captions skipped); undo re-adds the same instances, so `layerId` restores byte-identically. |
| Drop onto a CC / visualizer row | **WAS BROKEN, FIXED** (`TrackKind.isLane()` whitelist — see spec's adversarial review). |
| Lane eye (hide) on a mixed lane | **SAFE.** All three `LayerPreviewController.visible*` honor `track.isHidden()`, and export shares those methods. |
| Lane mute on a lane holding a PiP | **INERT, and that is a latent trap** — see gap 2 below. |
| Orphan-layerId rows | **WAS BROKEN, FIXED**, and now regression-guarded by `tasks/getlayers_equiv.py`. |
| Two different-type items at the same TIME on one lane | Not possible by design — one lane = one track (Premiere/CapCut model). Documented in the spec. |

## The two real gaps this exposed

**GAP 1 — lane order does not control cross-type z. This is the substrate's half-delivered
promise.** You can now put a PiP, a sprite and a text on any lane, but their paint order is the
fixed global surface stack (overlay video → visualizer → image → sprite → text), because the
preview is five stacked Views (`activity_faditor_editor.xml` 536-566) and export mirrors that
split (PiPs in the GL effect chain, sprites-then-text in `CompositeExportOverlay`). So a user
who moves their video lane above their text lane sees **nothing change**, which reads as a bug
even though it is pre-existing behavior. Neutrality did not cause it — it made it *visible*,
because putting the two on one lane is now the natural thing to try.
→ Needs its own lane: **cross-type z unification** (one z-ordered composite pass instead of
five fixed surfaces, in preview AND export). Architecturally the largest remaining item in the
editor. Spec before building.

**GAP 2 — PiP video has no audio at all, and the UI is starting to imply that it does.**
`OverlayVideoPreviewView` sets `player.setVolume(0f)` ("pixels only — matches export's
setRemoveAudio"), and PiPs composite as pixels in the effect chain, so no audio path exists in
preview or export. Two things now point at that hole: (a) the mute icon on a lane holding a PiP
is drawn as applicable (correctly, by content — but it toggles nothing), and (b) JoyRaptor's
requested audio drawer on layer rows would render a waveform for audio that never plays.
→ **PiP audio must land BEFORE the layer-row audio drawer**, or the drawer is a lie. And it
must land WITH lane-mute support: `LayerPreviewController.isAudioClipTrackMuted` only walks
audio tracks and only matches `AudioClip`s, so a muted lane would not mute its PiP.

## Recommended order (revised)

1. ~~Neutral substrate S0–S5~~ ✅ done, adversarially reviewed, model half proven.
2. **Device verification** of the substrate + the standing verification debt (G9 links,
   dual-stream pair ops, ping-pong failure drill, speed-change gapless, JoyRaptor's 0719 batch).
   *Currently blocked: the phone is in human use.* This is the highest-value non-blocked work
   the moment the device frees up — the H.264 episode proved compile-green means nothing.
3. **PiP audio end-to-end** — model (per-clip volume/mute already exist on `Clip`), preview
   (real volume + lane mute), export (PiP audio into the composition). Self-contained, directly
   requested, and it unblocks 4.
4. **Audio drawer on layer rows** — extend the master-only clip-audio drawer
   (`EditorTimelineView` :307) to lane rows. Only meaningful after 3.
5. **Cross-type z unification** (gap 1) — spec first; the biggest architectural item left.
6. Ship-blockers, which are **not code**: rebrand art set, de-politicize naming decisions, the
   live `id.fadseclab.com` dashboard, locale/icon renames. All JoyRaptor-gated. Worth starting the
   decisions in parallel with 3-5 rather than discovering them at the end.

Moved DOWN from the earlier plan: nothing is being dropped, but "burn down verification debt"
cannot be the immediate next build task while the device is occupied, so 3 takes its slot and
2 resumes the moment the phone is free.
