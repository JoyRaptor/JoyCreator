# Studio hover-label audit (read-only)

Date: 2026-10-15. Scope: `app/src/main/java/com/fadcam/ui/faditor/` excluding `sprite/`, `ai/`, `avatar/` and `joybrush`, plus `app/src/main/res/layout/activity_faditor_editor.xml`. No source files were edited.

## The rule and the helper

- `ObjectDrawer.Kit.describe(view, text)` (`app/src/main/java/com/fadcam/ui/faditor/tools/ObjectDrawer.java:1710`) calls `setContentDescription(text)` AND `TooltipCompat.setTooltipText(view, text)`. One call covers TalkBack and the stylus/mouse hover label.
- `TextOverlayDrawer.Kit.describe` (`tools/TextOverlayDrawer.java:95`) delegates to the same helper.
- The editor also names views directly with `TooltipCompat.setTooltipText` and `setContentDescription` (about 146 calls in `FaditorEditorActivity.java`). Those count as named here.
- A site counts as covered if a naming call reaches its view in the same method, through a helper that names it (for example `topRowToggle`, `valuePill`, `iconButton`, `typeHint`), or through the activity layout XML (`android:contentDescription` or `android:tooltipText`).

## Method

1. Every `setOnClickListener`, `setOnLongClickListener` and `setOnTouchListener` in scope was listed with its receiver.
2. For each site, the enclosing method was searched for `describe(...)`, `setContentDescription`, `setTooltipText` or `TooltipCompat` on that receiver. Helper factories were checked by reading their bodies, and the flagged sites were read in context.
3. The activity layout XML was checked for `ImageView`, `ImageButton`, `clickable` and `onClick` elements, and for glyph `TextView`s (materialicons font), that carry neither contentDescription nor tooltipText.
4. Touch-only drag, swipe and resize surfaces were separated out, because they are not buttons.

## Totals

- Listener sites scanned: **401** (one view can carry more than one listener, so these are sites, not distinct views).
- Covered (named, helper-named, or XML-labelled): **178**
- Drag, swipe and gesture surfaces, not counted as buttons: **22**
- **Uncovered: 201** sites across **30** files.
  - `FaditorEditorActivity.java`: **117** of 270 sites
  - All other files: **84**
- Text-labelled controls (Cancel buttons, model chips, a few word-sync toggles) are included. Icon-only controls are the priority; this pass does not split the two precisely.

## Top 15 most visible offenders

| # | Control | Where | Location | Kind |
|---|---|---|---|---|
| 1 | Split (tool_split) | Bottom tools bar | `FaditorEditorActivity.java:3570` | glyph + label |
| 2 | Delete segment (tool_delete) | Bottom tools bar | `FaditorEditorActivity.java:3571` | glyph + label |
| 3 | Duplicate segment (tool_duplicate) | Bottom tools bar | `FaditorEditorActivity.java:3572` | glyph + label |
| 4 | Add asset (tool_add_asset) | Bottom tools bar | `FaditorEditorActivity.java:3573` | glyph + label |
| 5 | Text (tool_text) | Bottom tools bar | `FaditorEditorActivity.java:3574` | glyph + label |
| 6 | Transitions (tool_transitions) | Bottom tools bar | `FaditorEditorActivity.java:3642` | glyph + label |
| 7 | Captions (tool_captions) | Bottom tools bar | `FaditorEditorActivity.java:3643` | glyph + label |
| 8 | Speed (toolSpeed) | Bottom tools bar | `FaditorEditorActivity.java:6613` | glyph + label |
| 9 | Crop (toolCrop) | Bottom tools bar | `FaditorEditorActivity.java:6622` | glyph + label |
| 10 | Rotate (toolRotate) | Bottom tools bar | `FaditorEditorActivity.java:6616` | glyph + label |
| 11 | Flip (toolFlip) | Bottom tools bar | `FaditorEditorActivity.java:6619` | glyph + label |
| 12 | Mute, press-and-hold (toolMute, touch listener) | Bottom tools bar | `FaditorEditorActivity.java:6485` | glyph + label |
| 13 | Loop (toolLoop) | Bottom tools bar | `FaditorEditorActivity.java:3646` | glyph + label |
| 14 | Timecode, tap to seek (timeCurrent) | Transport row | `FaditorEditorActivity.java:3659` | text |
| 15 | Text motion 'A' icon (animBtn, drawn on canvas) | Text style drawer header | `FaditorEditorActivity.java:35091` | icon drawn on canvas |

The bottom tools bar cells are built by `tools/FaditorToolsAdapter.java` `buildCell` (around line 823). The icon TextView and the label TextView get no contentDescription or tooltip there, and the editor's click wiring (`FaditorEditorActivity.java` 3570 to 3660) names nothing. The tool registry carries label text only.

## Not counted as listener sites: structural gaps

- **Timeline track headers are canvas-drawn, not Views.** `layers/LayerRowRenderer.java` defines `HitZone { CARET, HIDE, LOCK, MUTE }` (line 368), four icon zones per track row, hit-tested by `hitTestHeader` (line 4123). `describe()` cannot be applied to them because they have no View.
- **No hover handling exists in scope.** A search of `faditor/` for `onHoverEvent`, `setOnHoverListener` and `PointerIcon` finds nothing, and `timeline/EditorTimelineView.java` has no tooltip or hover code. Hover labels for the canvas zones need a hover path inside the view, not `describe()`.
- **Layout XML.** `activity_faditor_editor.xml` has 47 clickable or glyph elements with neither contentDescription nor tooltipText. Most are named in code under an alias (for example `close`, `prev`, `next` in the volume, opacity, loop and caption drawers) and are covered. Glyphs such as `volume_drawer_icon`, `opacity_drawer_icon`, `loop_drawer_icon` and `export_status_icon` are not clickable, so they are not buttons.
- **Image views created in code.** There are no `ImageButton`s in scope. The `new ImageView` sites (`assetbrowser/AssetBrowserAdapter.java:138`, `compositor/LayerImageOverlayView.java:88`, `tools/ObjectDrawer.java:1000,1080`, `tools/PuppetDrawerTabs.java:305,513,1056`, `VideoSourceBottomSheet.java:558`, `waveform/WaveformDebugActivity.java:163`) are non-clickable images or sit inside a clickable parent covered by the listener scan. `PuppetDrawerTabs.java:305` names its swatch through `hoverLabel`.

## Caveats

- Counts come from a scripted scan plus reading flagged sites. Generic variable names (`chip`, `row`, `v`, `t`, `tv`, `bar`, `card`, `b`) were matched by name, and a name match elsewhere in a file is not proof. Those sites are kept as uncovered and marked below. Each was checked for a naming call within about 120 lines of the listener, and none was found.
- Helper coverage was verified by reading bodies for `topRowToggle` (bold, italic, underline, case and small-caps toggles), `valuePill` (AudioDrawerTabs), `iconButton` and `typeHint` (PipDrawerTabs), and the FxPanel handle. `makeTextMotionIcon` (`FaditorEditorActivity.java:23323`) does not name its view, so the motion icons stay uncovered.
- Editor counts are per listener line. Some lines are click and long-press on the same view, so distinct views are fewer than the sites shown.

## Uncovered sites by file

### `app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java` (117)

- L2390 `editorTitle`: `editorTitle.setOnClickListener(v -> showRenameProjectDialog());`
- L2393 `editorTitle`: `editorTitle.setOnLongClickListener(v -> { showRenameProjectDialog(); return true; });`
- L3570 `tool_split`: `findViewById(R.id.tool_split).setOnClickListener(v -> splitOrHealAtPlayhead());`
- L3571 `tool_delete`: `findViewById(R.id.tool_delete).setOnClickListener(v -> deleteSelectedSegment());`
- L3572 `tool_duplicate`: `findViewById(R.id.tool_duplicate).setOnClickListener(v -> duplicateSelectedSegment());`
- L3573 `tool_add_asset`: `findViewById(R.id.tool_add_asset).setOnClickListener(v -> showAddAssetPicker());`
- L3574 `tool_text`: `findViewById(R.id.tool_text).setOnClickListener(v -> addTextOverlay());`
- L3575 `tool_visualizer`: `findViewById(R.id.tool_visualizer).setOnClickListener(v -> addWaveformVisualizer());`
- L3576 `tool_filter`: `findViewById(R.id.tool_filter).setOnClickListener(v -> openFilterSheet());`
- L3595 `tool_silence`: `findViewById(R.id.tool_silence).setOnClickListener(v -> toggleSilenceDetect());`
- L3597 `fixBtn`: `if (fixBtn != null) fixBtn.setOnClickListener(v -> fixSelectedAudio());`
- L3599 `beatsBtn`: `if (beatsBtn != null) beatsBtn.setOnClickListener(v -> showBeatDetectionSheet());`
- L3601 `alignBtn`: `if (alignBtn != null) alignBtn.setOnClickListener(v -> showAlignClipsSheet());`
- L3602 `tool_settings`: `findViewById(R.id.tool_settings).setOnClickListener(v -> {`
- L3610 `tool_sprites`: `findViewById(R.id.tool_sprites).setOnClickListener(v -> openSpritePalette());`
- L3613 `toolAdjustment`: `toolAdjustment.setOnClickListener(v -> openAdjustForSelection());`
- L3616 `toolAdjustment`: `toolAdjustment.setOnLongClickListener(v -> { addAdjustmentLayer(); return true; });`
- L3619 `toolCompact`: `if (toolCompact != null) toolCompact.setOnClickListener(v -> compactLayers());`
- L3622 `toolSelect`: `if (toolSelect != null) toolSelect.setOnClickListener(v -> cycleMarqueeMode());`
- L3628 `toolMove`: `toolMove.setOnClickListener(v -> toggleMoveDrawer());`
- L3631 `tool_transcript`: `findViewById(R.id.tool_transcript).setOnClickListener(v -> {`
- L3638 `tool_transcript`: `findViewById(R.id.tool_transcript).setOnLongClickListener(v -> {`
- L3642 `tool_transitions`: `findViewById(R.id.tool_transitions).setOnClickListener(v -> showTransitionPanel(!transitionPanelOpen));`
- L3643 `tool_captions`: `findViewById(R.id.tool_captions).setOnClickListener(v -> toggleCaptions());`
- L3646 `toolLoop`: `toolLoop.setOnClickListener(v -> {`
- L3659 `timeCurrent`: `timeCurrent.setOnClickListener(v -> showSeekToTimeDialog());`
- L6485 `toolMute`: `toolMute.setOnTouchListener(new View.OnTouchListener() {`
- L6555 `toolOpacity`: `toolOpacity.setOnTouchListener(new View.OnTouchListener() {`
- L6613 `toolSpeed`: `toolSpeed.setOnClickListener(v -> showSpeedSlider());`
- L6616 `toolRotate`: `toolRotate.setOnClickListener(v -> rotateNext());`
- L6619 `toolFlip`: `toolFlip.setOnClickListener(v -> showFlipPicker());`
- L6622 `toolCrop`: `toolCrop.setOnClickListener(v -> enterCropMode());`
- L6625 `toolCanvas`: `toolCanvas.setOnClickListener(v -> showCanvasPicker());`
- L6628 `toolAudio`: `toolAudio.setOnClickListener(v -> extractAudioFromCurrentClip());`
- L7498 `loopModeOff`: `loopModeOff.setOnClickListener(v -> applyLoopMode(Clip.LOOP_MODE_OFF));`
- L7499 `loopModeNormal`: `loopModeNormal.setOnClickListener(v -> applyLoopMode(Clip.LOOP_MODE_NORMAL));`
- L7500 `loopModeStill`: `loopModeStill.setOnClickListener(v -> applyLoopMode(Clip.LOOP_MODE_STILL));`
- L7509 `loopModePingpong`: `loopModePingpong.setOnClickListener(v ->`
- L7513 `loopModePingpong`: `loopModePingpong.setOnClickListener(v -> applyLoopMode(Clip.LOOP_MODE_PING_PONG));`
- L7517 `loopExtendStart`: `loopExtendStart.setOnClickListener(v -> extendLoop(-1000));`
- L7518 `loopExtendEnd`: `loopExtendEnd.setOnClickListener(v -> extendLoop(1000));`
- L7519 `loopExtendPrev`: `loopExtendPrev.setOnClickListener(v -> extendLoop(-5000));`
- L7520 `loopExtendNext`: `loopExtendNext.setOnClickListener(v -> extendLoop(5000));`
- L8495 `crop_btn_cancel`: `findViewById(R.id.crop_btn_cancel).setOnClickListener(v -> exitCropMode(false));`
- L8688 `chip` (name used elsewhere in file; not found near this site): `chip.setOnClickListener(v -> applyCropAspectPreset(key));`
- L10143 `btn_cancel_export`: `findViewById(R.id.btn_cancel_export).setOnClickListener(v -> {`
- L10168 `exportProgressStripe`: `exportProgressStripe.setOnClickListener(v -> reshowExportProgress());`
- L14460 `row` (name used elsewhere in file; not found near this site): `row.setOnClickListener(v -> onTap.run());`
- L16123 `row` (name used elsewhere in file; not found near this site): `row.setOnClickListener(v -> { popup.dismiss(); action.run(); });`
- L18399 `tsBox`: `tsBox.setOnClickListener(v -> {`
- L18829 `wordScrubTimestamp`: `wordScrubTimestamp.setOnClickListener(v -> {`
- L18872 `tv`: `tv.setOnClickListener(v -> applyWordSyncCase(c));`
- L18888 `tv`: `tv.setOnClickListener(v -> showWordSyncToast("Bold, underline and italic need per-word styling, which isn't built yet.")`
- L18915 `wordSyncDrawerRippleLabel`: `wordSyncDrawerRippleLabel.setOnClickListener(v -> cycleWordSyncRipple());`
- L18927 `wordSyncDrawerSnapLabel`: `wordSyncDrawerSnapLabel.setOnClickListener(v -> toggleWordSyncSnap());`
- L18942 `tt`: `if (tt != null) tt.setOnClickListener(v -> applyWordSyncCase("TT"));`
- L18944 `tT`: `if (tT != null) tT.setOnClickListener(v -> applyWordSyncCase("Tt"));`
- L18946 `ttLow`: `if (ttLow != null) ttLow.setOnClickListener(v -> applyWordSyncCase("tt"));`
- L18948 `b`: `if (b != null) b.setOnClickListener(v -> showWordSyncToast("Bold, underline and italic need per-word styling, which isn'`
- L18950 `u`: `if (u != null) u.setOnClickListener(v -> showWordSyncToast("Bold, underline and italic need per-word styling, which isn'`
- L18952 `i`: `if (i != null) i.setOnClickListener(v -> showWordSyncToast("Bold, underline and italic need per-word styling, which isn'`
- L18953 `wordSyncDrawerRippleLabel`: `if (wordSyncDrawerRippleLabel != null) wordSyncDrawerRippleLabel.setOnClickListener(v -> cycleWordSyncRipple());`
- L18954 `wordSyncDrawerSnapLabel`: `if (wordSyncDrawerSnapLabel != null) wordSyncDrawerSnapLabel.setOnClickListener(v -> toggleWordSyncSnap());`
- L21045 `itemView`: `h.itemView.setOnClickListener(v -> styleRv.smoothScrollToPosition(position));`
- L21092 `itemView`: `h.itemView.setOnClickListener(v -> gradRv.smoothScrollToPosition(position));`
- L21297 `chip` (name used elsewhere in file; not found near this site): `chip.setOnClickListener(v -> {`
- L21482 `b`: `b.setOnClickListener(onClick);`
- L22015 `ab`: `ab.setOnClickListener(v -> {`
- L22077 `motionBtn`: `motionBtn.setOnClickListener(open);`
- L22350 `kCopy`: `kCopy.setOnClickListener(v -> copyToClipboard(`
- L22370 `sCopy`: `sCopy.setOnClickListener(v -> copyToClipboard(`
- L22516 `chip` (name used elsewhere in file; not found near this site): `chip.setOnClickListener(v -> {`
- L23403 `toggle`: `toggle.setOnClickListener(v -> {`
- L23434 `toggle`: `toggle.setOnClickListener(v -> {`
- L23546 `boxToggle`: `boxToggle.setOnClickListener(v -> {`
- L23589 `colorRow`: `colorRow.setOnClickListener(v -> com.fadcam.ui.faditor.tools.ColorPickerDialog.show(`
- L23608 `hideRow`: `hideRow.setOnClickListener(v -> {`
- L27849 `gotIt`: `gotIt.setOnClickListener(v -> dismissGestureCoachMark());`
- L27850 `card`: `card.setOnClickListener(v -> dismissGestureCoachMark());`
- L35091 `animBtn`: `animBtn.setOnClickListener(v -> {`
- L35139 `pivotBtn`: `pivotBtn.setOnClickListener(v ->`
- L36407 `unlink`: `unlink.setOnClickListener(v -> {`
- L36442 `chip` (name used elsewhere in file; not found near this site): `chip.setOnClickListener(v -> {`
- L36619 `x`: `x.setOnClickListener(v -> remove.run());`
- L37032 `motionIcon`: `motionIcon.setOnClickListener(v -> com.fadcam.ui.faditor.TextAnimPickerPopover.show(`
- L37174 `t` (name used elsewhere in file; not found near this site): `t.setOnClickListener(v -> showFontPickerPopup(item, t, session));`
- L37258 `importRow`: `importRow.setOnClickListener(v -> {`
- L37631 `chip` (name used elsewhere in file; not found near this site): `chip.setOnClickListener(v -> {`
- L38948 `transcriptHeader`: `transcriptHeader.setOnClickListener(v -> showTranscriptPicker());`
- L38955 `transcript_model_fast`: `findViewById(R.id.transcript_model_fast).setOnClickListener(v ->`
- L38957 `transcript_model_accurate`: `findViewById(R.id.transcript_model_accurate).setOnClickListener(v ->`
- L38959 `transcript_model_whisper`: `findViewById(R.id.transcript_model_whisper).setOnClickListener(v ->`
- L39299 `transcript_clean`: `findViewById(R.id.transcript_clean).setOnClickListener(v -> {`
- L39316 `transcript_apply`: `findViewById(R.id.transcript_apply).setOnClickListener(v -> commitTranscript());`
- L39672 `chip` (name used elsewhere in file; not found near this site): `chip.setOnClickListener(v -> {`
- L39722 `chip` (name used elsewhere in file; not found near this site): `chip.setOnLongClickListener(v -> {`
- L40966 `bar` (name used elsewhere in file; not found near this site): `bar.setOnTouchListener((v, ev) -> {`
- L40970 `bar` (name used elsewhere in file; not found near this site): `bar.setOnClickListener(v -> setTransitionGlRowExpanded(!transitionGlExpanded));`
- L41018 `card`: `card.setOnClickListener(v -> insertGlTransitionAtPlayhead(glId));`
- L41019 `card`: `card.setOnLongClickListener(v -> {`
- L41046 `v` (name used elsewhere in file; not found near this site): `v.setOnClickListener(view -> insertTransitionAtPlayhead(type));`
- L41047 `v` (name used elsewhere in file; not found near this site): `v.setOnLongClickListener(view -> {`
- L41287 `typeView`: `typeView.setOnClickListener(v -> showTransitionTypePicker(t, index));`
- L41291 `durationView`: `durationView.setOnClickListener(v -> showTransitionDurationPicker(t, index));`
- L41294 `deleteView`: `deleteView.setOnClickListener(v -> deleteTransition(index));`
- L43414 `sourceChip`: `sourceChip.setOnClickListener(v -> showTranscriptPicker());`
- L43437 `chip` (name used elsewhere in file; not found near this site): `chip.setOnClickListener(view -> switchTranscriptVersion(idx));`
- L43438 `chip` (name used elsewhere in file; not found near this site): `chip.setOnLongClickListener(view -> { showTranscriptVersionMenu(idx); return true; });`
- L45498 `btnStart`: `btnStart.setOnClickListener(v -> {`
- L45528 `btnEnd`: `btnEnd.setOnClickListener(v -> {`
- L46355 `browse`: `browse.setOnClickListener(v -> {`
- L46359 `btnCancel`: `btnCancel.setOnClickListener(v -> dlg.dismiss());`
- L46360 `btnAdd`: `btnAdd.setOnClickListener(v -> {`
- L46572 `browse`: `browse.setOnClickListener(v -> {`
- L46577 `btnCancel`: `btnCancel.setOnClickListener(v -> dlg.dismiss());`
- L46578 `btnAdd`: `btnAdd.setOnClickListener(v -> {`
- L47437 `okButton`: `okButton.setOnClickListener(v -> {`

### `app/src/main/java/com/fadcam/ui/faditor/assetbrowser/AssetBrowserPanel.java` (8)

- L199 `folderIcon`: `folderIcon.setOnClickListener(v -> collapse());`
- L223 `btnPrevDir`: `btnPrevDir.setOnClickListener(v -> navigateDir(-1));`
- L228 `btnNextDir`: `btnNextDir.setOnClickListener(v -> navigateDir(1));`
- L234 `btnChangeDir`: `btnChangeDir.setOnClickListener(v -> {`
- L241 `btnFilter`: `btnFilter.setOnClickListener(v -> cycleFilter());`
- L247 `btnTrash`: `btnTrash.setOnClickListener(v -> confirmDeleteFromHistory());`
- L251 `btnTidy`: `btnTidy.setOnClickListener(v -> offerTidy());`
- L384 `panel`: `panel.setOnClickListener(v -> { /* consume */ });`

### `app/src/main/java/com/fadcam/ui/faditor/tools/PuppetDrawerTabs.java` (8)

- L310 `swatch`: `swatch.setOnClickListener(v -> {`
- L455 `wrap`: `wrap.setOnClickListener(v -> {`
- L532 `cell`: `cell.setOnClickListener(v -> {`
- L579 `t`: `t.setOnClickListener(v -> { host.setScope(scope); host.rebuildRows(); });`
- L721 `cell`: `cell.setOnClickListener(v -> host.confirmResetRig());`
- L795 `chip`: `chip.setOnClickListener(v -> {`
- L1064 `eyeView`: `eyeView.setOnClickListener(v -> {`
- L1250 `cell`: `cell.setOnClickListener(v -> {`

### `app/src/main/java/com/fadcam/ui/faditor/tools/FxPanel.java` (6)

- L413 `pos`: `pos.setOnClickListener(v -> {`
- L824 `c`: `c.setOnClickListener(v -> structural(stack, host, rebuild,`
- L852 `reset`: `reset.setOnClickListener(v -> structural(stack, host, rebuild,`
- L1434 `v`: `v.setOnClickListener(ignored -> onTap.run());`
- L1618 `save`: `save.setOnClickListener(v -> {`
- L1747 `add`: `add.setOnClickListener(v -> picker.setVisibility(`

### `app/src/main/java/com/fadcam/ui/faditor/ObjectMenuSheet.java` (5)

- L441 `pushToggle`: `pushToggle.setOnClickListener(v -> {`
- L451 `incrementToggle`: `incrementToggle.setOnClickListener(v -> {`
- L505 `moreBtn`: `moreBtn.setOnClickListener(onMore == null ? null : v -> onMore.run());`
- L522 `chip`: `chip.setOnClickListener(v -> a.run.run());`
- L544 `btn`: `btn.setOnClickListener(v -> a.run.run());`

### `app/src/main/java/com/fadcam/ui/faditor/tools/AudioDrawerTabs.java` (5)

- L350 `clear`: `clear.setOnClickListener(v -> {`
- L524 `revert`: `revert.setOnClickListener(v -> {`
- L611 `dn`: `dn.setOnClickListener(v -> new Apply(`
- L617 `ln`: `ln.setOnClickListener(v -> new Apply(`
- L713 `linkBtn`: `linkBtn.setOnClickListener(v -> host.onAudioReactiveLinkRequested());`

### `app/src/main/java/com/fadcam/ui/faditor/tools/PipDrawerTabs.java` (5)

- L1185 `dropper`: `dropper.setOnClickListener(v -> host.pickColorFromPreview(rgb -> {`
- L1356 `dec`: `dec.setOnClickListener(v -> bar.setProgress(Math.max(0, bar.getProgress() - 1)));`
- L1357 `inc`: `inc.setOnClickListener(v ->`
- L1399 `dec`: `dec.setOnClickListener(v -> {`
- L1404 `inc`: `inc.setOnClickListener(v -> {`

### `app/src/main/java/com/fadcam/ui/faditor/tools/FaditorToolsAdapter.java` (4)

- L81 `tool_split`: `* {@code findViewById(R.id.tool_split).setOnClickListener(..)} — and`
- L285 `editChip`: `editChip.setOnClickListener(v -> {`
- L306 `cell`: `cell.setOnTouchListener(new View.OnTouchListener() {`
- L784 `done`: `done.setOnClickListener(v -> {`

### `app/src/main/java/com/fadcam/ui/faditor/tools/FaditorToolsDrawer.java` (4)

- L166 `scrim`: `scrim.setOnClickListener(v -> dismiss());`
- L218 `close`: `close.setOnClickListener(v -> dismiss());`
- L366 `cell`: `cell.setOnClickListener(v -> {`
- L455 `target`: `target.setOnTouchListener(new View.OnTouchListener() {`

### `app/src/main/java/com/fadcam/ui/faditor/FilterBottomSheet.java` (3)

- L403 `chip`: `chip.setOnClickListener(v -> applyPreset(name));`
- L439 `chip`: `chip.setOnClickListener(v -> applySavedPreset(name));`
- L440 `chip`: `chip.setOnLongClickListener(v -> { confirmDeletePreset(name, dp); return true; });`

### `app/src/main/java/com/fadcam/ui/faditor/SheetKit.java` (3)

- L250 `x`: `x.setOnClickListener(v -> onClose.run());`
- L432 `row`: `if (onClick != null) row.setOnClickListener(onClick);`
- L608 `b`: `b.setOnClickListener(onClick);`

### `app/src/main/java/com/fadcam/ui/faditor/VideoSourceBottomSheet.java` (3)

- L215 `row`: `row.setOnClickListener(v -> {`
- L229 `row`: `row.setOnClickListener(v -> {`
- L641 `row`: `row.setOnClickListener(v -> {`

### `app/src/main/java/com/fadcam/ui/faditor/VolumeControlBottomSheet.java` (3)

- L194 `muteRow`: `muteRow.setOnClickListener(v -> {`
- L219 `volumeIcon`: `volumeIcon.setOnClickListener(v -> muteRow.performClick());`
- L230 `resetRow`: `resetRow.setOnClickListener(v -> {`

### `app/src/main/java/com/fadcam/ui/faditor/player/PreviewPipController.java` (3)

- L648 `expand`: `expand.setOnClickListener(v -> requestExpand());`
- L674 `undock`: `undock.setOnClickListener(v -> undock());`
- L680 `chrome`: `chrome.setOnTouchListener(new View.OnTouchListener() {`

### `app/src/main/java/com/fadcam/ui/faditor/waveform/WaveformVisualizerSettingsSheet.java` (3)

- L395 `swatch`: `swatch.setOnClickListener(v -> showColorChooser(band));`
- L421 `lane`: `lane.setOnClickListener(v -> {`
- L478 `cell`: `cell.setOnClickListener(v -> {`

### `app/src/main/java/com/fadcam/ui/faditor/KeyframeDiamondControl.java` (2)

- L154 `v`: `v.setOnTouchListener(new OnTouchListener() {`
- L213 `d`: `d.setOnTouchListener(new OnTouchListener() {`

### `app/src/main/java/com/fadcam/ui/faditor/SnapSettingsSheet.java` (2)

- L118 `c`: `c.setOnClickListener(v -> {`
- L144 `c`: `c.setOnClickListener(v -> {`

### `app/src/main/java/com/fadcam/ui/faditor/SpeedSliderBottomSheet.java` (2)

- L197 `chip`: `chip.setOnClickListener(v -> {`
- L243 `resetRow`: `resetRow.setOnClickListener(v -> {`

### `app/src/main/java/com/fadcam/ui/faditor/overlay/TextOverlayLayer.java` (2)

- L152 `textEditor`: `textEditor.setOnTouchListener((tv, ev) -> {`
- L1930 `tv`: `tv.setOnTouchListener(new OnTouchListener() {`

### `app/src/main/java/com/fadcam/ui/faditor/tools/BlendPickerPopover.java` (2)

- L134 `chip`: `chip.setOnClickListener(v -> show(v, getMode.get(), picked -> {`
- L309 `t`: `t.setOnClickListener(v -> tap.onPick(mode));`

### `app/src/main/java/com/fadcam/ui/faditor/waveform/WaveformDebugActivity.java` (2)

- L113 `importBtn`: `importBtn.setOnClickListener(v -> importLauncher.launch(`
- L154 `exportBtn`: `exportBtn.setOnClickListener(v -> {`

### `app/src/main/java/com/fadcam/ui/faditor/FaditorSettingsBottomSheet.java` (1)

- L304 `row`: `row.setOnClickListener(v -> onClick.run());`

### `app/src/main/java/com/fadcam/ui/faditor/ImagePresetPicker.java` (1)

- L124 `v`: `v.setOnClickListener(vv -> {`

### `app/src/main/java/com/fadcam/ui/faditor/RelinkCatalogBottomSheet.java` (1)

- L295 `row`: `row.setOnClickListener(v -> {`

### `app/src/main/java/com/fadcam/ui/faditor/SlideCodeBottomSheet.java` (1)

- L123 `codeBox`: `codeBox.setOnClickListener(v -> {`

### `app/src/main/java/com/fadcam/ui/faditor/TextAnimPickerPopover.java` (1)

- L216 `chip`: `chip.setOnClickListener(v -> {`

### `app/src/main/java/com/fadcam/ui/faditor/effects/LutLibraryPanel.java` (1)

- L48 `button`: `button.setOnClickListener(v -> {`

### `app/src/main/java/com/fadcam/ui/faditor/text/TextStyleLibraryPanel.java` (1)

- L48 `button`: `button.setOnClickListener(v -> {`

### `app/src/main/java/com/fadcam/ui/faditor/tools/LinkTool.java` (1)

- L213 `chip`: `chip.setOnClickListener(v -> {`

### `app/src/main/java/com/fadcam/ui/faditor/tools/PivotPickerPopover.java` (1)

- L82 `dot`: `dot.setOnClickListener(v -> {`

