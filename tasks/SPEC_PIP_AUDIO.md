# SPEC: PiP (overlay-clip) audio

> Opened 2026-07-25 (Opus 5) as item 3 of `REVIEW_ORDER_20260725.md`. Prerequisite for the
> layer-row audio drawer JoyRaptor asked for — a waveform on a lane row is a lie until the audio
> it draws can actually be heard and exported.

## Today's behavior (verified, not assumed)

A PiP overlay clip is **pixels only, everywhere**:
- Preview: `OverlayVideoPreviewView.ensureActive` sets `player.setVolume(0f)` — the comment says
  *"pixels only — matches export's setRemoveAudio (M-EXPORT-1)"*.
- Export: PiPs are not `EditedMediaItem`s at all. `buildOverlayVideoSequence` was DELETED in
  M-EXPORT-2 (a second video sequence composited invisibly under the master); PiPs now ride the
  GL effect chain / `CompositeExportOverlay` as pixels. So no audio path exists.
- `Clip` already carries `audioMuted`, `volumeLevel` and a `VolumeKeyframe` envelope — the model
  is there; nothing consumes it for overlay clips.

Preview constraint to respect: the view owns exactly ONE `ExoPlayer`, bound to the **top-most
visible** overlay clip (`topVisibleAt`). So preview audio can only ever come from one PiP at a
time. Export has no such limit (each PiP is its own item in an audio sequence).

## THE DEFAULT IS THE WHOLE DESIGN — opt-in, not opt-out

Making PiP audio simply "work" would change the output of **every existing project that has a
PiP**, silently. Worse, for a dual-stream pair (the same take recorded twice — screen + webcam,
where the webcam rides as a PiP) it would **double the audio**: the same voice, twice, slightly
phased. That is a data-destroying-looking regression in an export the user may not re-check.

Therefore: a new `Clip.overlayAudioEnabled` flag, **default `false`**, serialized additively.
- Existing projects: field absent → `false` → byte-identical exports. No migration.
- New PiPs: start silent, exactly like today.
- The user opts a PiP into contributing audio, per clip.

Do NOT reuse the existing `audioMuted` for this. Its default is `false` (= audible), which for
overlay clips would mean "every existing PiP becomes audible" — the exact regression above.
`audioMuted` keeps its meaning (a mute toggle) and composes on top of the new flag.

## Effective volume — one authority

`LayerPreviewController` is already the shared preview/export authority for visibility. Extend
it for overlay audio so the two cannot diverge (the existing
`effectivePreviewVolume`/`isAudioClipTrackMuted` pair for `AudioClip` is the template):

```
effectiveOverlayVolume(timeline, clip) =
    !clip.isOverlayAudioEnabled()              -> 0     // opt-in gate
    clip.isAudioMuted()                        -> 0     // the clip's own mute
    isOverlayClipLaneMuted(timeline, clip)     -> 0     // its LANE's mute, multiplicative
    else                                       -> clip.getVolumeLevel()
```

`isOverlayClipLaneMuted` walks `timeline.getLayers()` (floating lanes) and returns the muted
state of whichever lane holds this clip. **This closes gap 2 of the order review**: the existing
`isAudioClipTrackMuted` only walks audio tracks and only matches `AudioClip`s, so without this a
muted lane would not mute its PiP — and the mute icon on such a lane (now drawn by content) is
already telling the user it will.

## Slices

- **A — model + preview.** `Clip.overlayAudioEnabled` + `ProjectStorage` round-trip (additive,
  written only when true so existing JSON stays byte-identical); the two
  `LayerPreviewController` helpers; `OverlayVideoPreviewView` sets the active clip's effective
  volume instead of a hardcoded 0, and re-applies it when the bound clip or its flags change.
  Zero behavior change until a clip opts in.
- **B — export.** `buildOverlayAudioSequence(timeline)`, mirroring `buildAudioSequence`'s proven
  shape: overlay clips sorted by `overlayStartMs`, silence-gap items to place each one, each PiP
  as an audio-only (`setRemoveVideo(true)`) item with its clipping config, volume/envelope
  processor, and a `SonicAudioProcessor` when its speed ≠ 1. Added as an extra sequence in both
  composition builders. Returns null when no PiP has opted in → composition byte-identical.
  ⚠️ `usesLayerFeaturesAffectingExport` already returns true whenever any overlay clip exists,
  so the full re-encode path is already taken — no change needed there.
- **C — UI.** An "Include audio" toggle on the PiP object menu (`ObjectMenuSheet` already has a
  PiP adapter), plus volume where the sheet already exposes it for audio clips.
- **D — layer-row audio drawer.** Extend the master-only clip-audio drawer
  (`EditorTimelineView` :307, keyed by master `segments`) to lane rows holding a PiP. Only
  meaningful once A–C land.

## Acceptance

1. Open any existing project with a PiP → export is byte-identical to before (nothing opted in).
2. Opt a PiP in → its audio is audible in preview at its own volume, and present in the export
   at the right timeline offset.
3. Mute that PiP's lane → it goes silent in BOTH preview and export (one authority).
4. A dual-stream pair still exports single-voice audio unless the user explicitly opts the
   webcam PiP in.
