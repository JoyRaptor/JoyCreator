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
- **C — UI.** ✅ DONE. "Include audio" / "Mute overlay audio" action on the PiP object menu
  (`showObjectMenuSheetForPipClip`), one undo step, refreshing the preview volume and the rows.
  A static **Volume** slider (0–2×) appears on the sheet only once the clip has opted in —
  a volume control on a silent clip is noise. Deliberately static, not keyframed: a PiP volume
  ENVELOPE is not threaded through the export sequence yet (audio clips have one; see below).
  The row mute icon's applicability also requires opt-in, so a lane holding only silent PiPs
  correctly shows the greyed icon rather than promising a mute that would do nothing.
- **D — layer-row audio drawer.** ✅ BUILT, **visual verification owed**. An opted-in PiP gets
  a waveform shelf on its own lane row, aligned to the same x span as its body, so picture and
  audio read together — the lane-row sibling of the master clip-audio drawer. Toggled from the
  PiP object menu ("Show/Hide audio waveform"); pure session view state, no undo step, matching
  the master drawer.
  - Built INERT-WHEN-CLOSED on purpose, the same discipline as S0: `laneAudioDrawerPx()` returns
    0 unless that lane's drawer is open, and it is the single number feeding row height, item
    extents and hit-testing. A project with no drawer open is pixel-identical.
  - Items keep their normal height instead of stretching into the shelf: every item extent now
    goes through `RowLayout.itemsBottom()`, which returns `bodyRect.bottom` when closed.
  - **Hit-testing was aligned with drawing in the same pass** — four sites derived item bounds
    from `bodyRect.bottom`, so with a drawer open a tap on the waveform would have selected the
    item above it. All four now use `itemsBottom()`. This is the class of bug that only appears
    once the feature is switched on, which is exactly why it was worth chasing before shipping.
  - Tape data reuses the master drawer's banded cache via its URI-keyed API, on the FULL-source
    span: with the cache's superset reuse, one extraction per file serves every trim window, so
    opening a drawer never restarts a long analysis.
  - ⚠️ NOT seen on a device — the phone was in human use. The layout maths and the closed-path
    inertness are reasoned, not observed. First device pass should check: shelf height/alignment,
    the tape aligning to the PiP body's x span, and a tap on the shelf NOT selecting the item.

## Adversarial follow-up on slice B (found + fixed before it shipped)

The first cut of `buildOverlayAudioSequence` read `timeline.getOverlayClips()` directly, while
the exported PIXELS come from `LayerPreviewController.visibleOverlayVideoClips`. That split the
two: a PiP hidden by its lane's eye — or by its own per-object eye — would have vanished from
the picture while its **audio kept playing in the export**. An object excluded from the export
must be excluded whole. The sequence now sources the same shared authority, so pixels and audio
cannot disagree about which PiPs exist.

Worth noting the asymmetry this makes explicit, because it is a real design decision: for
`AudioClip`s, `hidden` does NOT silence (only `muted` does) — an audio clip has no picture, so
its eye means nothing. For a PiP, `hidden` means the whole object is out. Both are right; they
just differ, so neither should be "fixed" to match the other.

## Known gaps (deliberate, recorded so they are not "discovered" as bugs)

- **Preview plays at most ONE PiP's audio** — the view owns a single `ExoPlayer` bound to the
  top-most visible overlay clip. Export has no such limit, so a project with two overlapping
  opted-in PiPs will export both but preview only the top one. Fixing needs a second player
  (or a mixer) in `OverlayVideoPreviewView`.
- **No PiP volume ENVELOPE on export.** `Clip` carries a `VolumeKeyframe` list and
  `buildAudioSequence` honors it for audio clips; `buildOverlayAudioSequence` applies only the
  static level. Wiring the envelope is a small, contained follow-up.
- **PiP loop extension / removed spans are not reflected in its audio.** The audio item uses
  the clip's in/out points and trimmed duration; the preview's visual extent has the same
  simplification today (`topVisibleAt` uses `getTrimmedDurationMs`), so the two agree — but
  neither honors a looped PiP.

## Acceptance

> **STATUS 2026-07-25 ~21:25 (Note 9, device):** the shared authority `effectiveOverlayVolume`
> is verified across the whole gate matrix — A default (not opted in) → **0.0**; B opted in →
> **0.8**, the clip's own level; C lane muted → **0.0**; D clip muted → **0.0**. Since preview
> and export both read this one method, that covers the *decision*. It does NOT cover the
> plumbing on either side: **1 and 3 are satisfied at the authority level; 2 and 4 still need a
> real listen and a real export.** Do not mark this spec done on the strength of the matrix.

1. ✅ (authority) Open any existing project with a PiP → export is byte-identical to before
   (nothing opted in). *Fixture A returned 0.0 with `overlayAudioEnabled` absent.*
2. ⏳ Opt a PiP in → its audio is audible in preview at its own volume, and present in the export
   at the right timeline offset. *Authority returns 0.8; preview/export plumbing UNVERIFIED.*
3. ✅ (authority) Mute that PiP's lane → it goes silent in BOTH preview and export (one
   authority). *Fixture C → 0.0. This is the lane-mute-never-reached-PiPs bug, confirmed fixed.*
4. ⏳ A dual-stream pair still exports single-voice audio unless the user explicitly opts the
   webcam PiP in. *Not exercised — needs a real pair project.*
