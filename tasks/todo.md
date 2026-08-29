# PLAN — SPEC_20260829_CAPTION_LAYERS: Caption layers (3 caption tracks per clip)

> **Lane:** `SPEC_20260829_CAPTION_LAYERS` — claim before first edit (`LANES.md:88`)
> **Files to claim** (LANES.md): `model/Clip.java`, `model/AudioClip.java`, `model/Timeline.java` (getCaptionTracks only), `project/ProjectStorage.java`, `export/CompositeExportOverlay.java`, `export/ExportManager.java` (~3093), `FaditorEditorActivity.java` (drawer + preview container)
> `transcript/CaptionOverlayView.java` must stay UNCHANGED per spec §4.

## 0. Why / what already exists
- Clip already holds `List<NamedTranscript> transcripts` + `activeTranscriptIndex` (Clip.java:159,163) — multi-transcript import/persist/UI picking shipped `b52e2727`.
- Singular binding `activeTranscriptIndex` + `captionStyleId` (Clip.java:247, AudioClip.java:150) + position/size on VIEW (`CaptionOverlayView.centerX/Y/sizeFraction`) blocks showing 2 at once. This spec is plumbing, not new subsystem (§2, §3).

## 1. Target model (`Clip` + `AudioClip`)
```java
public static final class CaptionBinding {
    public String transcriptId;  // NamedTranscript.id — NOT index
    public String styleId;
    public boolean enabled;
    public float centerX, centerY; // 0..1 canvas
    public float sizeFraction;
    public String label; // "Lyrics", "References"
}
private final List<CaptionBinding> captionBindings = new ArrayList<>();
```
- Cap **3** enforced in model (add path), not only UI (§3.1).
- Copy-ctor / `relinked()` / deep-copy: must carry bindings (Clip.java:540-636 shows precedent for hidden/passThrough/fx/loop — add same block for bindings).
- Helpers:
  - `getCaptionBindings()` / `getEnabledBindings()` / `addCaptionBinding()` / `removeCaptionBinding(i)` / `setActiveBindingIndex(int)` optional.
  - `getActiveNamedTranscript()` stays returning binding 0's transcript (back-compat, spec §3.2).
  - `captionStyleAtClipMs` etc. must remain but caller will loop per binding after.
  - Legacy getters `getCaptionStyleId()` / `isCaptionsEnabled()` / `getCaptionCenter*()` keep working as **alias to binding 0** for old callers not yet migrated (spec says mirroring binding 0).
- AudioClip mirrors identical binding list (AudioClip.java:148-156 analog).

## 2. Migration — ProjectStorage
- On load (`ProjectStorage.java` Clip deserializer ~1629, AudioClip path): if `captionBindings` array present → use it; else synthesize ONE binding from legacy fields (`transcripts.get(activeTranscriptIndex).id`, `captionStyleId`, `captionsEnabled`, `captionCenterX/Y`, `captionSizeFraction`, label "Captions"); if `activeIdx==-1` or OOB → no binding.
- Keep legacy fields readable AND keep writing them mirroring binding 0 for one release (spec §3.2). Requires serializer writes both new array + old scalars.
- `NamedTranscript.id` already at NamedTranscript.java:15 — verify.
- Precedence/ordering: no `transcriptId` uniqueness check (two bindings MAY share same transcript, §3.6).
- Legacy caption-style keyframes: remain per-clip (not per-binding) — no change this spec, but note exporter must still evaluate them per binding's style? Leave as-is; document.
- Backward open test: new build saves → old build still renders binding0 via legacy fields.

## 3. Shared live-binding helper (preview == export)
- New pure class e.g. `transcript/CaptionBindings.java` or `model/CaptionBindingHelper.java` with `static List<CaptionBinding> liveAt(Clip, long timeMs)` / enabled filter. Both preview and export call it — no third divergence (LEDGER §3g). If time-gating not needed (all bindings time-unbounded), helper is just `enabled == true && transcriptId resolves`.
- `CaptionFit.UNIFORM` cache must key on binding (not clip) — currently `CaptionOverlayView:170` caches per transcript/style/box. With N views (see §4) this is naturally per-binding; verify `CaptionExportRenderer` per-renderer instance not sharing static cache.

## 4. Preview — FaditorEditorActivity + container
- Where: `activity_faditor_editor.xml:629` has 2 `CaptionOverlayView`s today (video + audio). Change: replace fixed 2 with **dynamic container** that instantiates **one view per enabled binding** stacked in binding order (§3.3).
- Owner of multiplicity is the container host in `FaditorEditorActivity` (methods `bindCaptionData` ~29627, `updateCurrentTimeDisplay`).
- Per binding: `setData(transcript, style, callback)` where transcript resolved by `transcriptId` lookup in `clip.getTranscripts()`.
- Touch/drag: only **active** overlay is interactive (`setClickable(true)` + handler); others `setClickable(false)` pass-through (§3.3). Hit-test: topmost enabled binding whose drawn `blockRect` contains touch wins.
- Gestures (§3.5): tap→active, drag→`centerX/Y` of active binding, pinch→`sizeFraction` of active, double-tap→open caption drawer targeted at that binding. Requires scale detector on container, forwarding to active view only.
- Drawer retargeting: active binding drives caption drawer, Fit tab, font row, words-per-cue dial AND transcript drawer (§3.5). Use existing `b52e2727` source picker machinery programmatically (`setActiveTranscriptIndex`-equivalent but now `setActiveBinding`).
- Caption drawer track list: compact list `● Lyrics [pop] 👁` + Add/eye/rename-delete via long-press (§3.5). New track goes ABOVE (offset `centerY` by 0.12 per track), not overlapping.
- Trap: `getSelectedClip()` → `getClip(0)` when nothing selected; must use `clipUnderPlayhead()` (spec §6).

## 5. Export — CompositeExportOverlay + ExportManager
- `CompositeExportOverlay.java:325,804` reads `getCaptionStyleId()`; `ExportManager:3093` skips when `"hidden"`. Change each to **loop over enabled bindings** (§3.4).
- Per binding: create its own `CaptionExportRenderer` (already per-slot `AudioCaptionSlot` pattern at CompositeExportOverlay:320-342 shows how). Keep captionRenderer cache per binding+style.
- AudioClip captions: `buildAudioCaptionSlots` already maps audio captions into video clip windows — extend to iterate bindings per AudioClip as well (not just `hasTranscript()` single check).
- Opacity/fade: bindings' own? No — clip-level opacity applies uniformly; fine.
- Draw order: bindings in list order (bottom→top as spec? use binding order).
- Unified fitted size: ensure `CaptionExportRenderer` instances each compute their own fit; no shared static.

## 6. Timeline — getCaptionTracks()
- `Timeline.java:2093` builds single read-only `Track` "CC". Change to **one row per binding** labeled with `binding.label` (or "CC" fallback). Loop over `clip.getCaptionBindings()` where `enabled` && transcript exists. Keep Clip-owned invariant (Track never second source, §3.6).
- Existing `getCaptionTracks()` call site `FaditorEditorActivity.java:13151 layerBand.addAll(tl.getCaptionTracks())` automatically fans out.

## 7. UI wiring order in FaditorEditorActivity
- Find: `captionOverlay` / `audioCaptionOverlay` fields (FaditorEditorActivity.java:377-378), `bindCaptionData`/`bindAudioCaptionData`, `updateCurrentTimeDisplay` tick, `getVideoContentRect()`, drawer `showCaptionDrawer*`.
- Steps:
  1. Introduce `List<CaptionOverlayView> captionOverlays` + `int activeCaptionBindingIndex` (+ per-clip? global active follows last-touched across clips? Spec says last-touched caption drives drawers — single global active).
  2. Container `FrameLayout captionLayerContainer` in preview.
  3. Method `rebuildCaptionOverlays(Clip)` tears down and rebuilds views from bindings.
  4. Method `setActiveCaptionBinding(int)` updates clickable, drawer content, transcript drawer source, Fit tab.
  5. Forward pinch/drag callbacks to active binding's model fields and `ProjectStorage.saveAsync`.
  6. Drawer track list adapter (reuse style row + eye toggle).

## 8. Execution plan (build order)
- [ ] 1. Claim LANES.md lane `SPEC_20260829_CAPTION_LAYERS` ACTIVE with file list; `git status` clean check.
- [ ] 2. Model: add `CaptionBinding` to `Clip.java` + `AudioClip.java`, cap=3, copy/relinked, alias getters.
- [ ] 3. ProjectStorage: serializer/deserializer dual-write + migration synthesis; add `captionBindings` JSON array handling for both model types.
- [ ] 4. Shared helper + Timeline `getCaptionTracks()` loop (small, testable).
- [ ] 5. CompositeExportOverlay + ExportManager per-binding loops; verify preview==export helper.
- [ ] 6. FaditorEditorActivity container + multi-view + selection/gestures + drawer retarget + track list.
- [ ] 7. Integration: verify migration both directions, three-track overlap, independent Fit, selection retarget, drag isolation.
- [ ] 8. Lane release + commit staging per WORKING-TREE HAZARD (`git add` each file immediately).

## 9. Acceptance mapping (§5)
1. Build — last line `BUILD SUCCESSFUL` + mtime newer than edit (paste both).
2. Migration both directions — old→new same place/style; new→old via legacy mirror still renders (screenshot trio).
3. Three tracks one clip — lyrics bottom, reference middle, verse top, 3 styles/fonts non-overlapping.
3b. Preview selection round-trip — tap track1 → caption+transcript drawers switch; tap track3 switches again; double-tap opens drawer.
4. Preview equals export — 15s export, frame compare same text/size/position.
5. Independent fit — different FitModes per track, verify no inheritance.
6. Selection retargets — font change on track2 doesn't affect track1.
7. Drag isolation — dragging over track2 with track1 active moves only track1.
8. Device — `adb devices` (or state unplugged → §5.1+5.2-by-inspection).

## 10. Traps (spec §6)
- `getSelectedClip()` → `getClip(0)` on auto-blank spacer — use `clipUnderPlayhead()`.
- Written-never-read — confirm renderer READS new binding fields.
- Two answers to one question — single binding list + single helper; preview & export both call it.
- `perl -i` without `-CSD` forbidden; verify `grep -c 'â'` ==0 after edits.

## 11. Risks / out-of-scope
- Transcript position/size persistance mis-located → audit where `centerX/Y/sizeFraction` currently persist (VIEW vs Clip vs ProjectStorage) before synthesis.
- Export perf: 3 renderers × text measurement per frame; budget OK on phone but watch.
- Out of scope: slide object, per-word rich text, audio graph, `timingSourceBinding` link (track 2↔3 retime sync deferred).

## 12. Verification commands (non-gradle per LANES rule 6)
- Save; read `build.log` tail for `BUILD SUCCESSFUL` + mtime.
- `adb devices` for device token.
- `grep -rn captionBindings` to confirm no stale single-field read remains.
- `grep -c 'â' <touched files>` encoding check.

## 13. Next step
Await user confirmation of this plan before implementation (AGENTS.md Task Management step 2). On go: claim lane ACTIVE then execute §8 in order.
