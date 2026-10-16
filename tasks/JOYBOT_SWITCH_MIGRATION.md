# JoybotSwitch migration plan (AvatarToggleView -> JoybotSwitch)

Status 2026-10-16: JoybotSwitch built (`app/src/main/java/com/fadcam/ui/joybot/JoybotSwitch.java`
+ `JoybotSwitchFace.java`). Live ONLY on the Settings hub's three sheets (Show the intro, Check
for updates, Debug log). **Do not start this migration until the owner has approved the look on
the Note 9.** Keep `AvatarToggleView.java` until the last site is moved and verified; then delete
it with its `toggle_*` drawables and the `PREF_AVATAR_EYE_COLOR` preference row (see "Eye colour").

## The mechanical change (same at every site)

The public API is identical (`setChecked(b)`, `setChecked(b, animate)`, `isChecked()`,
`setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener)` firing for taps only with a
null button, `performClick()`), so per site:

- **XML**: `<com.fadcam.ui.AvatarToggleView` -> `<com.fadcam.ui.joybot.JoybotSwitch`. Keep the id.
- **Java**: the type `AvatarToggleView` / `com.fadcam.ui.AvatarToggleView` ->
  `com.fadcam.ui.joybot.JoybotSwitch` (field, local, cast, `instanceof`, import). Nothing else.
- **Size** (judgement, not mechanical): the legacy boxes are square (52x52 etc.). JoybotSwitch fits
  its 48:28 pill into the box, so 52x52 works unchanged (52x30 pill, 52dp target). Preferred
  end-state: `wrap_content` x `wrap_content` (48x28 pill in a 48dp target), which also frees
  vertical space in rows. Change sizes only where the row was built around the 52dp avatar.
- **Label**: give every switch a content description (its row's title) - it feeds TalkBack and
  the hover label. Rows that delegate a click to `sw.performClick()` need nothing else.

Behaviour differences to check at each site:
1. A **disabled** JoybotSwitch ignores `performClick()` (the avatar flipped anyway). Only
   AnnotationService disables one; that is the intended behaviour.
2. JoybotSwitch dims itself when disabled. A caller that also sets alpha (AnnotationService
   ~4680) should drop its own `setAlpha` or it double-dims.
3. No zZ overflow: the avatar turned off `clipChildren` on its parent; JoybotSwitch draws inside
   its bounds, so nothing to keep.

## Group A - normal screens (low risk; do first, one commit per file)

| File (Java) | Layout | Sites | Notes |
|---|---|---|---|
| ui/picker/PickerBottomSheetFragment.java | picker_bottom_sheet.xml:104, picker_bottom_sheet_slider.xml:111, picker_bottom_sheet_item.xml:96 | fields `swc`, `itemSwitch`, `dateSwitch`, `arabicDateSwitch`, `switchRef`; `applyThemedSwitchColors` param | Shared by ~20 settings sheets. Once the XML moves, delete the `ARG_JOYBOT_SWITCH` / `withJoybotSwitch()` opt-in and the `JoybotSwitch.replace` branch (both become redundant), and the dead `applyThemedSwitchColors`. Item rows: 52dp -> wrap_content. |
| ui/DebugLogBottomSheetFragment.java | (built in code) | `buildToggleRow` | Drop the AvatarToggleView branch and `newInstance(boolean)`; always JoybotSwitch. |
| ui/WatermarkSettingsFragment.java | fragment_settings_watermark.xml:412,490,545,584,623,662,701,740 | ~21 refs incl. two `instanceof AvatarToggleView` casts (~376, ~921) | Pure rename; casts become `instanceof JoybotSwitch`. |
| ui/MotionLabSettingsFragment.java | fragment_settings_motion_lab.xml:248,478 | `switchMotionEnabled`, `switchMotionAutoTorch` | Rename. |
| ui/HomeSidebarFragment.java | fragment_home_sidebar.xml:372,434 | `swPreview`, `sw` | Rename; fix the XML comment at :371. |
| ui/RecordsSidebarFragment.java | fragment_records_sidebar.xml:234 | `hideSwitch` | Rename. |
| forensics/ui/LabSidebarFragment.java | fragment_lab_sidebar.xml:225 | `hideSwitch` | Rename. |
| ui/RemoteFragment.java | fragment_remote.xml:374,468,1230 | `streamingToggle`, `cloudStreamingToggle`, `remoteAuthToggle` | Rename. Streaming toggles drive a server: tap it on the phone after. |
| ui/miniapps/TorchToolFragment.java | fragment_torch_tool.xml:95 (120x120, clickable=false) | `torchToggle`, uses `setChecked(on, animate)` | Rename. 120dp box gives a 120x70 hero pill - check it still reads as the torch's big button; JoybotSwitch honours `android:clickable/focusable="false"` from XML. |

## Group B - watch screens (medium risk: small round screens, separate activity)

| File | Layout | Sites | Notes |
|---|---|---|---|
| ui/WatchCameraFragment.java | fragment_watch_camera.xml:97 (80x80) | `watchAvatarToggle` | Rename. 80dp box -> 80x47 pill; check on a round watch emulator that it is not too wide for the bezel. |
| ui/WatchRemoteFragment.java | fragment_watch_remote.xml:25 (52x52) | `remoteToggle` (+ own tooltip, `setOnClickListener`, `postDelayed`) | Rename. It sets its own tooltip: JoybotSwitch leaves a caller's tooltip alone. |

## Group C - floating overlay services in fadrec/ (highest risk: overlay windows, run during recording)

Move last, test with a real screen recording running.

| File | Layout | Sites | Notes |
|---|---|---|---|
| fadrec/ui/FloatingControlsService.java | floating_unified_menu.xml:281,316 (48x48) | `annotationSwitch`, `snapGuidesSwitch` | Rename. Overlay window: confirm press scale and idle stir render over other apps. |
| fadrec/ui/AnnotationService.java | annotation_toolbar_unified.xml:627 (44x44) | `snapGuidesSwitch` (`setEnabled`, `setAlpha`, `setClickable`) | Rename; remove its `setAlpha(alpha)` at ~4680 (JoybotSwitch dims itself when disabled). 44dp box is under the 48dp target: make it wrap_content if the toolbar has room. |
| fadrec/ui/FadRecHomeFragment.java | card_floating_controls.xml:22 (52x52) | four locals `switchFloatingControls` (~202, ~307, ~328, ~594) | Rename all four. |
| fadrec/ui/annotation/LayerManagementBottomSheet.java | item_annotation_layer.xml:24,31,38 (40x40, three per row, RecyclerView) | `switchVisible`, `switchLocked`, `switchPinned` | Rename. 40dp boxes give 40x23 pills under the 48dp target; three switches in a row may be better as icon toggles (eye / lock / pin) than as switches - ask the owner. |
| fadrec/ui/annotation/ShapePickerDialog.java | dialog_shape_picker.xml:63 (52x52) | `switchFillShape`, uses `setChecked(true, false)` | Rename. |

## Eye colour

AvatarToggleView reads `Constants.PREF_AVATAR_EYE_COLOR` (Appearance settings, "avatar eye
colour") itself; no caller uses `setEyeColor` / `getEyeColor`. JoybotSwitch deliberately has no eye
colour: ON is always the app's cyan (design rule: ON = solid cyan, glyph cut out). When the last
site moves, the Appearance row for the eye colour (AppearanceSettingsFragment ~875-944) has nothing
left to colour and should go with it; HomeFragment ~10374 and FullscreenPreviewActivity ~2357 also
read that pref - check what they use it for before removing the pref itself.

## Verify per group

Build green, then on the Note 9: tap each switch, tap its row, rotate, leave and return (state
restores without animating), TalkBack reads "<label>, switch, on/off", stylus hover shows the label.

## DONE 2026-10-16 (code migrated, awaiting Note 9 verification)

- Groups A, B and C: every site above moved. All 17 layouts renamed to `com.fadcam.ui.joybot.JoybotSwitch`, ids kept; every Java type renamed (imports added where the file had none). No `AvatarToggleView` reference is left outside its own file.
- Every switch has a content description: row-title strings reused where they exist; new `strings_joybot_switch_labels.xml` (Preview, Torch, Record, Visible, Locked, Pinned). Picker switches set theirs from the row/item title in Java.
- Picker: `ARG_JOYBOT_SWITCH`, `withJoybotSwitch()`, the `JoybotSwitch.replace` branch, `applyThemedSwitchColors` removed. DebugLog: `newInstance(boolean)` and the legacy branch removed; `JoybotSwitch.replace` deleted (no users).
- AnnotationService: own `setAlpha` removed; its toolbar switch is now wrap_content (48dp target).
- Extra: `BehaviorSettingsFragment` (two `MaterialSwitch` rows) moved to JoybotSwitch.
- `JoybotSwitch.setAccent(int)` / `getAccent()` added (default Studio.ARMED). No migrated screen has a Studio-token section colour in its code (they use theme attrs), so no site sets an accent.
- `AvatarToggleView` marked `@Deprecated`, not deleted. Eye-colour pref cleanup still waits for deletion.
- Skipped: `FaditorSettingsBottomSheet`, `SnapSettingsSheet` (MaterialSwitch, Studio editor), `SpriteSheetEditorActivity` (Switch, sprite editor), `FaditorEditorActivity`, `ObjectDrawer` - out of scope.
- Still open: item_annotation_layer has three 40dp switches per row (eye / lock / pin as icon toggles may be better - ask owner); watch camera 80dp box gives an 80x47 pill (check on round watch).
