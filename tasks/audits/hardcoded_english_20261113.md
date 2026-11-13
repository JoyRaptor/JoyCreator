# Hard-coded English a user can see

Date: 2026-11-13. Worktree `launch-docs-20261113-042476` (HEAD e2fe46186).

**Method.** ripgrep (one line at a time) over `app/src/main` and `joybrush-android/src/main` (test sources excluded) for: `Toast.makeText(ctx, "literal"`, `.setText("literal")`, `.setTitle/.setMessage/.setPositiveButton/.setNegativeButton/.setNeutralButton/.setHint("literal")`, `setContentDescription("literal")`, and `android:text`, `android:hint`, `android:contentDescription` with a literal (not `@string`, not `tools:`) in `res/layout`, `layout-land`, `menu`, `xml`. Only literals containing a letter are counted (so "+", "1", symbols are skipped).

**Limits.** Misses: Toast/setText whose string is on the next line, strings built with concatenation or `String.format("...")` as first argument, ternaries (`cond ? "a" : "b"`), `String` variables set elsewhere then passed in, `Snackbar`, `setTooltipText("...")`, notification builders, `getString` fallbacks, `AlertDialog` text set through custom views, and strings in layouts added at runtime from `.json`/`assets`. It also over-counts: debug-only screens (TextLabDebugActivity, WaveformDebugActivity), developer menus and unit names such as "px", "ms", "fps" are included, and some literals are file names or format strings not meant to be translated. Each line was not opened; treat it as a candidate list.

## Counts by kind

| kind | count |
|---|---|
| Toast | 111 |
| setText | 194 |
| dialog/hint | 135 |
| contentDesc | 10 |
| xml | 435 |
| **total** | **885** |

XML kind covers `android:text` + `android:hint` + `android:contentDescription` literals. A repo-wide check of all `android:text="..."` with any non-`@` start gave 529 in layouts; 435 of them (all kinds above, incl. layout-land) contain letters.

## Counts by area

| area | hits |
|---|---|
| res/layout | 414 |
| app java: ui/faditor | 227 |
| app java: fadrec | 77 |
| app java: ui | 51 |
| app java: joyfolder | 37 |
| res/layout-land | 21 |
| app java: cc | 10 |
| app java: vislab | 10 |
| joybrush-android (library) | 7 |
| app java: ui/library | 5 |
| joybrush-android (chrome) | 5 |
| app java: ui/lobby | 4 |
| app java: ui/settingshub | 3 |
| app java: ui/bottomsheet | 2 |
| app java: dualcam | 2 |
| app java: forensics | 2 |
| app java: ui/trash | 2 |
| joybrush-android | 2 |
| app java: RecordingStartActivity.java | 1 |
| app java: RecordingStopActivity.java | 1 |
| app java: MainActivity.java | 1 |
| app java: ui/about | 1 |

## Counts by file (all files)

| file | hits |
|---|---|
| res/layout/activity_faditor_editor.xml | 85 |
| a:ui/faditor/sprite/SpriteSheetEditorActivity.java | 74 |
| a:ui/faditor/ai/ChatAssistantActivity.java | 55 |
| a:fadrec/ui/AnnotationService.java | 52 |
| res/layout/annotation_toolbar_unified.xml | 38 |
| a:ui/faditor/FaditorEditorActivity.java | 36 |
| res/layout/fragment_home_sidebar.xml | 20 |
| res/layout-land/fragment_home.xml | 19 |
| a:joyfolder/studio/JoyFolderSetupActivity.java | 18 |
| a:ui/HomeFragment.java | 13 |
| res/layout/fragment_home.xml | 12 |
| a:joyfolder/studio/SpaceActivity.java | 11 |
| res/layout/floating_webcam.xml | 11 |
| a:cc/joycreator/textlab/TextLabDebugActivity.java | 10 |
| res/layout/fragment_forensics_insights.xml | 10 |
| a:fadrec/ui/FloatingControlsService.java | 9 |
| res/layout/activity_chat_assistant.xml | 9 |
| res/layout/floating_unified_menu.xml | 9 |
| res/layout/fragment_video_player_settings.xml | 9 |
| res/layout/item_layer_row.xml | 9 |
| res/layout/onboarding_permissions_slide.xml | 9 |
| a:ui/faditor/assetbrowser/AssetBrowserPanel.java | 8 |
| a:ui/RecordsFragment.java | 7 |
| a:ui/faditor/waveform/WaveformDebugActivity.java | 7 |
| jb:library/DrawingsActivity.kt | 7 |
| res/layout/fragment_mini_app_info_sheet.xml | 7 |
| res/layout/fragment_settings_dual_camera.xml | 7 |
| res/layout/item_annotation_layer.xml | 7 |
| a:joyfolder/studio/JoyFolderBanner.java | 6 |
| a:ui/faditor/ai/joybot/ModelPicker.java | 6 |
| res/layout/client_data_item.xml | 6 |
| res/layout/fragment_faditor_mini.xml | 6 |
| a:ui/ClientDetailsBottomSheetFragment.java | 5 |
| a:vislab/overlay/VisOverlayService.java | 5 |
| a:ui/faditor/avatar/AvatarStudioActivity.java | 5 |
| a:ui/faditor/VolumeControlBottomSheet.java | 5 |
| jb:chrome/UnibrushHostController.kt | 5 |
| res/layout/bottom_sheet_battery_info.xml | 5 |
| res/layout/bottom_sheet_endpoints.xml | 5 |
| res/layout/bottom_sheet_network_health.xml | 5 |
| res/layout/bottom_sheet_quality_preset.xml | 5 |
| res/layout/bottom_sheet_uptime_info.xml | 5 |
| res/layout/fragment_records.xml | 5 |
| res/layout/fragment_torch_tool.xml | 5 |
| res/layout/item_page_tab.xml | 5 |
| res/layout/item_pro_feature_row_4col.xml | 5 |
| res/layout/mode_selector_popup.xml | 5 |
| res/layout/number_input_bottom_sheet.xml | 5 |
| res/layout/overlay_layer_panel.xml | 5 |
| res/layout/overlay_page_tab_bar.xml | 5 |
| a:fadrec/ui/FadRecHomeFragment.java | 4 |
| a:fadrec/ui/ProjectNamingDialogActivity.java | 4 |
| a:ui/faditor/assetbrowser/AssetBrowserAdapter.java | 4 |
| a:ui/faditor/sprite/SequenceImportDialog.java | 4 |
| a:ui/faditor/tools/FaditorToolsAdapter.java | 4 |
| a:ui/lobby/LobbyFragment.java | 4 |
| res/layout/activity_fullscreen_preview.xml | 4 |
| res/layout/fragment_lobby.xml | 4 |
| res/layout/fragment_remote.xml | 4 |
| a:fadrec/ui/FloatingWebcamService.java | 3 |
| a:fadrec/ui/ProjectSelectionDialogActivity.java | 3 |
| a:ui/FaditorMiniFragment.java | 3 |
| a:vislab/room/VisLabActivity.java | 3 |
| a:ui/TrashAdapter.java | 3 |
| a:ui/faditor/ai/bridge/BridgeActivity.java | 3 |
| a:ui/faditor/sprite/JoyBrushBoardImportActivity.java | 3 |
| a:ui/faditor/RelinkCatalogBottomSheet.java | 3 |
| a:ui/faditor/SheetKit.java | 3 |
| res/layout/activity_qr_scanner.xml | 3 |
| res/layout/bottomsheet_mini_apps_info.xml | 3 |
| res/layout/client_info_row_item.xml | 3 |
| res/layout/dialog_project_management.xml | 3 |
| res/layout/endpoint_row_item.xml | 3 |
| res/layout/fragment_settings_home.xml | 3 |
| res/layout/fragment_watch_camera.xml | 3 |
| res/layout/fragment_watch_settings.xml | 3 |
| res/layout/home_recording_tiles_row.xml | 3 |
| res/layout/item_annotation_page.xml | 3 |
| res/layout/item_project.xml | 3 |
| res/layout/item_pro_feature_row.xml | 3 |
| a:ui/VideoInfoBottomSheet.java | 2 |
| a:ui/WatchVideoPlayerActivity.java | 2 |
| a:dualcam/ui/DualCameraInfoBottomSheet.java | 2 |
| a:ui/faditor/VideoSourceBottomSheet.java | 2 |
| a:ui/library/LibraryDock.java | 2 |
| a:ui/library/LibraryCardView.java | 2 |
| a:ui/SettingsHomeFragment.java | 2 |
| a:ui/NetworkHealthBottomSheet.java | 2 |
| a:ui/MiniAppInfoBottomSheetFragment.java | 2 |
| a:ui/faditor/player/PreviewPipController.java | 2 |
| jb:JoyBrushActivity.kt | 2 |
| a:joyfolder/studio/BringInDialog.java | 2 |
| a:ui/WatermarkSettingsFragment.java | 2 |
| a:ui/faditor/sprite/SpritePalettePanel.java | 2 |
| res/layout-land/fadrec_screen_icon.xml | 2 |
| res/layout/bottomsheet_forensics_evidence_info.xml | 2 |
| res/layout/bottom_sheet_layer_management.xml | 2 |
| res/layout/bottom_sheet_page_management.xml | 2 |
| res/layout/bottom_sheet_segments_info.xml | 2 |
| res/layout/fragment_settings_appearance.xml | 2 |
| res/layout/item_project_list.xml | 2 |
| res/layout/item_tip_card.xml | 2 |
| res/layout/item_watch_trash.xml | 2 |
| res/layout/minimizable_overlay_button.xml | 2 |
| res/layout/overlay_torch_screen_light.xml | 2 |
| res/layout/picker_bottom_sheet_slider.xml | 2 |
| res/layout/text_input_bottom_sheet.xml | 2 |
| res/layout/widget_clock_preview.xml | 2 |
| a:RecordingStartActivity.java | 1 |
| a:RecordingStopActivity.java | 1 |
| a:ui/EndpointsBottomSheetFragment.java | 1 |
| a:ui/bottomsheet/StreamOrientationBottomSheet.java | 1 |
| a:ui/bottomsheet/QualityPresetBottomSheet.java | 1 |
| a:vislab/studio/VisLabStyleTab.java | 1 |
| a:vislab/room/VisLabSession.java | 1 |
| a:ui/WatchRecordsFragment.java | 1 |
| a:fadrec/ui/LayerPanelOverlay.java | 1 |
| a:forensics/ui/ForensicsEventsAdapter.java | 1 |
| a:forensics/ui/ForensicsInsightsFragment.java | 1 |
| a:MainActivity.java | 1 |
| a:ui/about/AboutJoyFragment.java | 1 |
| a:ui/VideoSettingsFragment.java | 1 |
| a:ui/library/LibraryFragment.java | 1 |
| a:ui/TrashFragment.java | 1 |
| a:ui/RecordsAdapter.java | 1 |
| a:ui/ScanInfoBottomSheet.java | 1 |
| a:ui/RemoteFragment.java | 1 |
| a:ui/settingshub/SettingsEmpty.java | 1 |
| a:ui/faditor/SpeedSliderBottomSheet.java | 1 |
| a:ui/settingshub/SettingsHubFragment.java | 1 |
| a:ui/trash/RecentlyDeletedFragment.java | 1 |
| a:ui/settingshub/SettingsSearchDock.java | 1 |
| a:ui/trash/TrashCardView.java | 1 |
| a:fadrec/ui/ColorPickerDialogActivity.java | 1 |
| a:ui/LocationPrivacySettingsFragment.java | 1 |
| res/layout/activity_whats_new.xml | 1 |
| res/layout/annotation_arrow_button.xml | 1 |
| res/layout/bottomsheet_beta_update.xml | 1 |
| res/layout/bottomsheet_video_info.xml | 1 |
| res/layout/bottom_sheet_client_data_usage.xml | 1 |
| res/layout/card_floating_controls.xml | 1 |
| res/layout/dialog_loading.xml | 1 |
| res/layout/dialog_progress.xml | 1 |
| res/layout/dialog_project_name.xml | 1 |
| res/layout/dialog_text_editor.xml | 1 |
| res/layout/floating_controls_button.xml | 1 |
| res/layout/fragment_settings_notification.xml | 1 |
| res/layout/fragment_settings_screen_recording.xml | 1 |
| res/layout/fragment_settings_shortcuts.xml | 1 |
| res/layout/fragment_settings_video.xml | 1 |
| res/layout/fragment_trash.xml | 1 |
| res/layout/item_record.xml | 1 |
| res/layout/loading_dialog.xml | 1 |
| res/layout/onboarding_human_slide.xml | 1 |
| res/layout/overlay_inline_text_editor.xml | 1 |
| res/layout/onboarding_welcome.xml | 1 |

Path shorthand: `a:` = `app/src/main/java/com/fadcam/` (or `a:cc/...` = `app/src/main/java/cc/...`), `res/` = `app/src/main/res/`, `jb:` = `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/`.

## Listing (grouped by file, biggest first; cap about 400 lines)

### res/layout/activity_faditor_editor.xml (85)
- 39 [xml] `android:text="arrow_back"`
- 53 [xml] `android:text="push_pin"`
- 106 [xml] `android:contentDescription="AI Assistant" />`
- 145 [xml] `android:text="close"`
- 176 [xml] `android:text="A↔B"`
- 359 [xml] `android:text="RGB"`
- 386 [xml] `android:text="TV"`
- 413 [xml] `android:text="GL"`
- 421 [xml] `android:text="GL Shader"`
- 444 [xml] `android:text="Transition"`
- 686 [xml] `android:text="timer"`
- 717 [xml] `android:text="chevron_left"`
- 742 [xml] `android:text="drag_indicator"`
- 769 [xml] `android:text="search"`
- 797 [xml] `android:text="wrap_text"`
- 822 [xml] `android:text="person"`
- 825 [xml] `android:contentDescription="Speaker"`
- 848 [xml] `android:text="chevron_right"`
- 895 [xml] `android:text="keyboard_arrow_up"`
- 906 [xml] `android:text="keyboard_arrow_down"`
- 917 [xml] `android:text="close"`
- 1176 [xml] `android:text="play_arrow"`
- 1237 [xml] `android:text="select_all"`
- 1242 [xml] `android:contentDescription="Multi-select mode" />`
- 1253 [xml] `android:text="align_horizontal_center"`
- 1258 [xml] `android:contentDescription="Ripple or gap delete mode" />`
- 1276 [xml] `android:text="undo"`
- 1324 [xml] `android:text="redo"`
- 1393 [xml] `android:text="link"`
- 1431 [xml] `android:text="mic"`
- 1435 [xml] `android:contentDescription="Record voiceover" />`
- 1446 [xml] `android:text="Transcribing…"`
- 1594 [xml] `android:text="auto_crop"`
- 1607 [xml] `android:text="center_focus_strong"`
- 1649 [xml] `android:text="restart_alt"`
- 1668 [xml] `android:text="check"`
- 1701 [xml] `android:text="sync"`
- 1775 [xml] `android:text="arrow_back"`
- 1800 [xml] `android:text="check"`
- 1824 [xml] `android:text="movie"`
- 1932 [xml] `android:text="volume_up"`
- 1984 [xml] `android:text="volume_up"`
- 2006 [xml] `android:text="timer"`
- 2017 [xml] `android:text="volume_off"`
- 2028 [xml] `android:text="close"`
- 2049 [xml] `android:text="chevron_left"`
- 2066 [xml] `android:text="chevron_right"`
- 2110 [xml] `android:text="opacity"`
- 2132 [xml] `android:text="timer"`
- 2143 [xml] `android:text="close"`
- 2164 [xml] `android:text="chevron_left"`
- 2200 [xml] `android:text="delete"`
- 2212 [xml] `android:text="chevron_right"`
- 2256 [xml] `android:text="loop"`
- 2277 [xml] `android:text="close"`
- 2477 [xml] `android:text="style"`
- 2486 [xml] `android:text="Caption Keyframes"`
- 2498 [xml] `android:text="timer"`
- 2509 [xml] `android:text="close"`
- 2530 [xml] `android:text="chevron_left"`
- 2556 [xml] `android:text="delete"`
- 2568 [xml] `android:text="chevron_right"`
- 2696 [xml] `android:text="open_with"`
- 2717 [xml] `android:text="close"`
- 2750 [xml] `android:text="0.0s"`
- 2760 [xml] `android:text="0f"`
- 2770 [xml] `android:text="Lane 1"`
- 2803 [xml] `android:text="first_page"`
- 2816 [xml] `android:text="arrow_back"`
- 2829 [xml] `android:text="arrow_forward"`
- 2842 [xml] `android:text="last_page"`
- 2864 [xml] `android:text="arrow_upward"`
- 2876 [xml] `android:text="arrow_downward"`
- 2905 [xml] `android:text="play_arrow"`
- 2974 [xml] `android:text="chevron_left"`
- 2985 [xml] `android:text="chevron_right"`
- 2996 [xml] `android:text="align_horizontal_center"`
- 3007 [xml] `android:text="close"`
- 3035 [xml] `android:text="TT"`
- 3048 [xml] `android:text="Tt"`
- 3061 [xml] `android:text="tt"`
- 3119 [xml] `android:text="call_split"`
- 3125 [xml] `android:contentDescription="Split into words" />`
- 3157 [xml] `android:text="ONE"`
- 3169 [xml] `android:text="SNAP"`
### a:ui/faditor/sprite/SpriteSheetEditorActivity.java (74)
- 491 [contentDesc] `joy.setContentDescription("Joybot");`
- 707 [Toast] `if (undoStack.isEmpty()) { Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show(); return; }`
- 801 [Toast] `Toast.makeText(this, "The difference view needs Android 10 or newer",`
- 809 [Toast] `Toast.makeText(this, "Difference against the previous frame \u2014 "`
- 1138 [setText] `dragLabel.setText("drag");`
- 1158 [Toast] `Toast.makeText(this, "Every drawing is back where it started",`
- 1211 [setText] `tLabel.setText("Tags");`
- 1235 [dialog/hint] `in.setHint("mouth, hand, blink\u2026");`
- 1237 [dialog/hint] `.setTitle("New tag")`
- 1239 [dialog/hint] `.setPositiveButton("Add", (dl, w) -> {`
- 1247 [dialog/hint] `.setNegativeButton("Cancel", null)`
- 1255 [setText] `vLabel.setText("Viseme");`
- 1531 [setText] `caption.setText("Cell " + at[0] + " \u00b7 " + (at[0] + 1)`
- 1548 [dialog/hint] `.setTitle("Name the cells")`
- 1551 [dialog/hint] `.setNeutralButton("Next \u203a", null)`
- 1552 [dialog/hint] `.setPositiveButton("Done", (dl, w) -> {`
- 1557 [dialog/hint] `.setNegativeButton("Cancel", null)`
- 1641 [contentDesc] `bgSwatch.setContentDescription("Preview background");`
- 1650 [dialog/hint] `.setTitle("Preview background")`
- 1745 [dialog/hint] `.setNegativeButton("Close", null)`
- 1974 [Toast] `if (renderer == null) { Toast.makeText(this, "No art loaded", Toast.LENGTH_SHORT).show(); return; }`
- 1981 [Toast] `Toast.makeText(this, "This frame is empty \u2014 nothing to match to",`
- 2037 [Toast] `Toast.makeText(this, "No ink to measure", Toast.LENGTH_SHORT).show();`
- 2078 [dialog/hint] `.setTitle("Drift")`
- 2080 [dialog/hint] `.setPositiveButton("Close", null)`
- 2081 [dialog/hint] `.setNeutralButton("Auto-centre it", (d, w) -> autoCentre(false))`
- 2180 [setText] `alignSub.setText(" cell " + cell`
- 2235 [Toast] `Toast.makeText(this, "Playing all " + labSeq.size() + " frames",`
- 2250 [dialog/hint] `.setTitle("Name this sequence")`
- 2252 [dialog/hint] `.setPositiveButton("Set", (dl, w) -> {`
- 2256 [dialog/hint] `.setNegativeButton("Cancel", null)`
- 2310 [Toast] `Toast.makeText(this, "Tap cells to build a sequence first", Toast.LENGTH_SHORT).show();`
- 2318 [dialog/hint] `.setTitle("Name this animation")`
- 2320 [dialog/hint] `.setPositiveButton("Save", (dl, w) -> {`
- 2337 [dialog/hint] `.setNegativeButton("Cancel", null)`
- 2427 [setText] `empty.setText("No animations yet. Build a sequence in Play, then Save clip.");`
- 2435 [setText] `hint.setText("Tap a clip to see it on the sheet. Long-press to drag it somewhere else.");`
- 2511 [dialog/hint] `.setTitle("Delete \u201c" + pick.name + "\u201d?")`
- 2512 [dialog/hint] `.setPositiveButton("Delete", (dl, w) -> {`
- 2518 [dialog/hint] `.setNegativeButton("Cancel", null).show());`
- 2545 [setText] `wrap.setText("Playback: " + types[idx[0]] + " (tap to change)");`
- 2549 [setText] `wrap.setText("Playback: " + types[idx[0]] + " (tap to change)");`
- 2556 [dialog/hint] `fps.setHint("fps");`
- 2561 [dialog/hint] `.setTitle("Edit clip")`
- 2563 [dialog/hint] `.setPositiveButton("Save", (dl, w) -> {`
- 2574 [dialog/hint] `.setNegativeButton("Cancel", null)`
- 2596 [setText] `note.setText("Alignment is stored with the sheet, so nothing has to be baked here \u2014 "`
- 2714 [setText] `hint.setText("Tap a sheet to open, rename or remove it.");`
- 2822 [dialog/hint] `.setTitle("Rename sheet")`
- 2824 [dialog/hint] `.setPositiveButton("Rename", (dl, w) -> {`
- 2834 [dialog/hint] `.setNegativeButton("Cancel", null)`
- 2840 [dialog/hint] `.setTitle("Remove \u201c" + target.getName() + "\u201d?")`
- 2841 [dialog/hint] `.setMessage("It leaves this project's sheet list. The image file stays on disk, "`
- 2845 [dialog/hint] `.setPositiveButton("Remove", (dl, w) -> {`
- 2859 [dialog/hint] `.setNegativeButton("Keep", null)`
- 2924 [setText] `lbl.setText("merge in");`
- 2962 [Toast] `Toast.makeText(this, "Fill crops whatever does not fit the cell",`
- 2981 [Toast] `Toast.makeText(this, "JPG has no transparency \u2014 the sheet bakes onto white",`
- 3022 [Toast] `Toast.makeText(this, "No art loaded", Toast.LENGTH_SHORT).show();`
- 3121 [dialog/hint] `.setTitle("Baked")`
- 3129 [dialog/hint] `.setPositiveButton("Open it", (dl, w) -> openSheet(made.getId()))`
- 3130 [dialog/hint] `.setNegativeButton("Stay here", null)`
- 3214 [Toast] `Toast.makeText(this, "JSON copied", Toast.LENGTH_SHORT).show();`
- 3223 [setText] `nudge.setText("No cell is named yet. A named cell is something the assistant can "`
- 3258 [setText] `hint.setText("Tap cells on the sheet to build a sequence");`
- 3422 [setText] `hd.setText("x" + hold);`
- 3479 [Toast] `if (renderer == null) { Toast.makeText(this, "No art loaded", Toast.LENGTH_SHORT).show(); return; }`
- 3499 [Toast] `if (n == 0) { Toast.makeText(this, "Nothing to centre", Toast.LENGTH_SHORT).show(); return; }`
- 3984 [dialog/hint] `.setPositiveButton("Set", (dl, w) -> {`
- 3992 [dialog/hint] `.setNegativeButton("Cancel", null)`
- 4315 [dialog/hint] `.setTitle("Alignment travels as numbers")`
- 4323 [dialog/hint] `.setPositiveButton("Write the JSON", (d, w) -> exportSidecar())`
- 4324 [dialog/hint] `.setNeutralButton("Take me to Bake", (d, w) -> showSection("out"))`
- 4325 [dialog/hint] `.setNegativeButton("Cancel", null)`
### a:ui/faditor/ai/ChatAssistantActivity.java (55)
- 446 [setText] `statusText.setText("✓ Done");`
- 553 [dialog/hint] `.setTitle("Show the assistant…")`
- 693 [setText] `inputField.setText("What do you see in this frame?");`
- 748 [setText] `caption.setText("Picture attached");`
- 792 [dialog/hint] `inputField.setHint("What should I do with this picture?");`
- 982 [Toast] `Toast.makeText(this, "Joybot is still working — tap Stop first", Toast.LENGTH_SHORT).show();`
- 997 [dialog/hint] `inputField.setHint("Ask anything…");`
- 1492 [Toast] `Toast.makeText(this, "Joybot is still working — tap Stop first", Toast.LENGTH_SHORT).show();`
- 1601 [dialog/hint] `.setTitle("Name this recipe")`
- 1603 [dialog/hint] `.setPositiveButton("Save", (d, w) -> saveRecipe(f, field.getText().toString(), card))`
- 1604 [dialog/hint] `.setNegativeButton("Cancel", null)`
- 1699 [dialog/hint] `.setTitle("Delete “" + s.recipe.name + "”?")`
- 1700 [dialog/hint] `.setMessage("Its steps will be gone from My recipes.")`
- 1701 [dialog/hint] `.setPositiveButton("Delete", (d, w) -> aiExecutor.execute(() -> {`
- 1720 [dialog/hint] `.setNegativeButton("Cancel", null)`
- 1811 [Toast] `Toast.makeText(this, "Joybot is still working — tap Stop first", Toast.LENGTH_SHORT).show();`
- 1931 [dialog/hint] `.setTitle("Undo from here?")`
- 1932 [dialog/hint] `.setMessage("Put the project back how it was before this? " + rest)`
- 1933 [dialog/hint] `.setNegativeButton("Cancel", null)`
- 1934 [dialog/hint] `.setPositiveButton("Undo from here", (d, w) -> undoFrom(pid, changes, index, rows))`
- 2132 [Toast] `Toast.makeText(this, "Undo the newer change first", Toast.LENGTH_SHORT).show();`
- 2421 [dialog/hint] `inputField.setHint("Ask anything\u2026");`
- 3035 [dialog/hint] `inputKey.setHint("sk-or-v1-...");`
- 3050 [setText] `eye.setText("visibility");`
- 3054 [contentDesc] `eye.setContentDescription("Hold to show the key");`
- 3075 [setText] `labelModel.setText("Model:");`
- 3087 [setText] `labelPic.setText("Picture model (for pictures Joybot makes):");`
- 3101 [setText] `labelBase.setText("Service address (optional):");`
- 3132 [setText] `detected.setText("✓ An OpenRouter key.");`
- 3138 [dialog/hint] `inputModel.setHint("you'll pick from your " + p.name + " models");`
- 3140 [setText] `detected.setText("✓ A " + p.name + " key — address filled in for you.");`
- 3148 [setText] `hint.setText("Free tiers available on OpenRouter for lower-end models.\n"`
- 3161 [setText] `readAloud.setText("Read Joybot's replies aloud");`
- 3190 [setText] `titleText.setText("AI Assistant Settings");`
- 3200 [dialog/hint] `.setPositiveButton("Save", null)`
- 3201 [dialog/hint] `.setNegativeButton("Cancel", null);`
- 3203 [dialog/hint] `builder.setNeutralButton("Remove account", (d, w) ->`
- 3205 [dialog/hint] `.setMessage("Forget the " + account.name + " account and its key on this phone?")`
- 3206 [dialog/hint] `.setPositiveButton("Forget it", (d2, w2) -> {`
- 3212 [dialog/hint] `.setNegativeButton("Keep", null)`
- 3348 [setText] `copyBtn.setText("content_copy");`
- 3364 [Toast] `Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();`
- 3620 [setText] `notNow.setText("Not now");`
- 3622 [contentDesc] `notNow.setContentDescription("Don't export the video now");`
- 3626 [setText] `if (sub instanceof TextView) ((TextView) sub).setText("Not exported.");`
- 3630 [setText] `go.setText("Export now");`
- 3632 [contentDesc] `go.setContentDescription("Start the export now with these settings");`
- 3764 [setText] `discard.setText("Discard");`
- 3768 [setText] `if (sub instanceof TextView) ((TextView) sub).setText("Discarded.");`
- 3772 [setText] `applyBtn.setText("Apply");`
- 3926 [setText] `modelLabel.setText("offline helper \u25BE");`
- 3930 [setText] `modelLabel.setText("\uD83C\uDFA8 " + ModelCatalog.nameOf(this, JoybotPictures.model(this)) + " \u25BE");`
- 3989 [dialog/hint] `.setTitle("Talk to\u2026")`
- 4074 [dialog/hint] `inputField.setHint("Paste your key here");`
- 4078 [dialog/hint] `inputField.setHint("Ask anything\u2026");`
### a:fadrec/ui/AnnotationService.java (52)
- 366 [setText] `txtCurrentProject.setText("Current: " + sanitizedName);`
- 368 [setText] `txtCurrentProject.setText("Current: None");`
- 376 [setText] `txtProjectsPath.setText("Projects saved at: " + userFriendlyPath);`
- 467 [dialog/hint] `.setMessage("This will permanently delete ALL projects. This action cannot be undone!")`
- 483 [dialog/hint] `.setNegativeButton("Cancel", null)`
- 595 [dialog/hint] `.setTitle("Delete Project?")`
- 596 [dialog/hint] `.setMessage("Delete \"" + displayName + "\"? This cannot be undone.")`
- 597 [dialog/hint] `.setPositiveButton("Delete", (dialog, which) -> {`
- 600 [Toast] `Toast.makeText(AnnotationService.this, "Project deleted", Toast.LENGTH_SHORT).show();`
- 605 [Toast] `Toast.makeText(AnnotationService.this, "Failed to delete project", Toast.LENGTH_SHORT)`
- 609 [dialog/hint] `.setNegativeButton("Cancel", null)`
- 673 [Toast] `Toast.makeText(this, "✅ Loaded: " + projectName, Toast.LENGTH_SHORT).show();`
- 679 [Toast] `Toast.makeText(this, "❌ Failed to load project", Toast.LENGTH_SHORT).show();`
- 725 [Toast] `Toast.makeText(this, "📝 New Project: " + newProjectName, Toast.LENGTH_SHORT).show();`
- 829 [dialog/hint] `.setMessage("Continue with your last saved project \"" + sanitizedName + "\" or start a new one?")`
- 830 [dialog/hint] `.setPositiveButton("Continue", (dialog, which) -> {`
- 835 [dialog/hint] `.setNegativeButton("New Project", (dialog, which) -> {`
- 917 [Toast] `Toast.makeText(this, "Could not load project — starting fresh", Toast.LENGTH_SHORT).show();`
- 1408 [Toast] `Toast.makeText(this, "👆 Selection mode: Tap to select, drag to move", Toast.LENGTH_SHORT).show();`
- 1410 [Toast] `Toast.makeText(this, "🖊️ Draw mode", Toast.LENGTH_SHORT).show();`
- 1658 [setText] `btnCompactRecord.setText("fiber_manual_record");`
- 1660 [setText] `btnCompactPause.setText("pause");`
- 1668 [setText] `btnCompactRecord.setText("stop");`
- 1670 [setText] `btnCompactPause.setText("pause");`
- 1677 [setText] `btnCompactRecord.setText("stop");`
- 1679 [setText] `btnCompactPause.setText("play_arrow");`
- 2059 [Toast] `Toast.makeText(this, "✏️ Text added!", Toast.LENGTH_SHORT).show();`
- 2062 [Toast] `Toast.makeText(this, "Layer is locked", Toast.LENGTH_SHORT).show();`
- 2247 [Toast] `Toast.makeText(this, "🗑️ Text deleted!", Toast.LENGTH_SHORT).show();`
- 3068 [Toast] `Toast.makeText(AnnotationService.this, "✨ Created: " + pageName, Toast.LENGTH_SHORT).show();`
- 3090 [Toast] `Toast.makeText(AnnotationService.this, "🗑️ Deleted: " + pageName, Toast.LENGTH_SHORT).show();`
- 3261 [Toast] `Toast.makeText(AnnotationService.this, "✨ Created: " + layerName, Toast.LENGTH_SHORT).show();`
- 3930 [Toast] `Toast.makeText(this, "✏️ Text updated!", Toast.LENGTH_SHORT).show();`
- 3965 [Toast] `Toast.makeText(this, "✏️ Text added!", Toast.LENGTH_SHORT).show();`
- 4123 [Toast] `Toast.makeText(this, "Error showing rename dialog", Toast.LENGTH_SHORT).show();`
- 4158 [Toast] `Toast.makeText(this, "Error showing rename dialog", Toast.LENGTH_SHORT).show();`
- 4225 [setText] `recordingTimerText.setText("⏸ PAUSED");`
- 4270 [setText] `iconStartStop.setText("stop");`
- 4280 [setText] `iconPauseResume.setText("pause");`
- 4297 [setText] `iconStartStop.setText("stop");`
- 4307 [setText] `iconPauseResume.setText("play_arrow");`
- 4439 [setText] `iconAnnotation.setText("edit");`
- 4443 [Toast] `Toast.makeText(this, "✏️ Annotation Enabled - Draw freely", Toast.LENGTH_SHORT).show();`
- 4446 [setText] `iconAnnotation.setText("edit_off");`
- 4449 [setText] `descAnnotation.setText("Enable to use annotation tools");`
- 4450 [Toast] `Toast.makeText(this, "📱 Annotation Disabled - Use phone normally", Toast.LENGTH_SHORT).show();`
- 4487 [setText] `iconCanvas.setText("visibility");`
- 4491 [Toast] `Toast.makeText(this, "👁️ Canvas Visible", Toast.LENGTH_SHORT).show();`
- 4494 [setText] `iconCanvas.setText("visibility_off");`
- 4496 [setText] `labelCanvas.setText("Show Canvas");`
- 4497 [setText] `descCanvas.setText("Pinned layers still visible");`
- 4498 [Toast] `Toast.makeText(this, "Canvas Hidden - Pinned layers still visible", Toast.LENGTH_SHORT).show();`
### res/layout/fragment_home_sidebar.xml (20)
- 72 [xml] `android:text="account_circle" android:textColor="?attr/colorHeading"`
- 154 [xml] `android:text="verified" android:textColor="@color/s_ink" android:textSize="18sp"`
- 173 [xml] `android:layout_height="wrap_content" android:text="v-- >> v--"`
- 212 [xml] `android:text="science" android:textColor="@color/s_ink" android:textSize="18sp"`
- 231 [xml] `android:layout_height="wrap_content" android:text="v-- >> v--"`
- 279 [xml] `android:text="visibility"`
- 343 [xml] `android:text="widgets"`
- 442 [xml] `android:text="flashlight_on"`
- 510 [xml] `android:text="qr_code_scanner"`
- 575 [xml] `android:text="explore"`
- 667 [xml] `android:text="graphic_eq"`
- 759 [xml] `android:text="sensors"`
- 839 [xml] `android:text="speed"`
- 892 [xml] `android:text="architecture"`
- 945 [xml] `android:text="directions_walk"`
- 998 [xml] `android:text="travel_explore"`
- 1051 [xml] `android:text="location_on"`
- 1104 [xml] `android:text="qr_code_2"`
- 1185 [xml] `android:text="new_releases"`
- 1275 [xml] `android:text="lightbulb"`
### a:ui/faditor/assetbrowser/AssetBrowserPanel.java (8)
- 193 [setText] `folderIcon.setText("keyboard_arrow_up");`
- 589 [setText] `btnFilter.setText("filter_list");`
- 593 [setText] `btnFilter.setText("check_circle");`
- 597 [setText] `btnFilter.setText("radio_button_unchecked");`
- 601 [setText] `btnFilter.setText("link_off");`
- 726 [Toast] `Toast.makeText(getContext(), "Only files in your Joy Creator folder can go to Trash from here.",`
- 733 [dialog/hint] `.setTitle("Files no project uses")`
- 735 [dialog/hint] `.setPositiveButton("Move to Trash", (d, w) -> {`

## Not listed (150 files, 591 hits)

Counts are in the file table above; re-run the method for the lines. Files: res/layout/annotation_toolbar_unified.xml (38), a:ui/faditor/FaditorEditorActivity.java (36), res/layout-land/fragment_home.xml (19), a:joyfolder/studio/JoyFolderSetupActivity.java (18), a:ui/HomeFragment.java (13), res/layout/fragment_home.xml (12), a:joyfolder/studio/SpaceActivity.java (11), res/layout/floating_webcam.xml (11), a:cc/joycreator/textlab/TextLabDebugActivity.java (10), res/layout/fragment_forensics_insights.xml (10), a:fadrec/ui/FloatingControlsService.java (9), res/layout/activity_chat_assistant.xml (9), res/layout/floating_unified_menu.xml (9), res/layout/fragment_video_player_settings.xml (9), res/layout/item_layer_row.xml (9), res/layout/onboarding_permissions_slide.xml (9), a:ui/RecordsFragment.java (7), a:ui/faditor/waveform/WaveformDebugActivity.java (7), jb:library/DrawingsActivity.kt (7), res/layout/fragment_mini_app_info_sheet.xml (7), res/layout/fragment_settings_dual_camera.xml (7), res/layout/item_annotation_layer.xml (7), a:joyfolder/studio/JoyFolderBanner.java (6), a:ui/faditor/ai/joybot/ModelPicker.java (6), res/layout/client_data_item.xml (6), res/layout/fragment_faditor_mini.xml (6), a:ui/ClientDetailsBottomSheetFragment.java (5), a:vislab/overlay/VisOverlayService.java (5), a:ui/faditor/avatar/AvatarStudioActivity.java (5), a:ui/faditor/VolumeControlBottomSheet.java (5), jb:chrome/UnibrushHostController.kt (5), res/layout/bottom_sheet_battery_info.xml (5), res/layout/bottom_sheet_endpoints.xml (5), res/layout/bottom_sheet_network_health.xml (5), res/layout/bottom_sheet_quality_preset.xml (5), res/layout/bottom_sheet_uptime_info.xml (5), res/layout/fragment_records.xml (5), res/layout/fragment_torch_tool.xml (5), res/layout/item_page_tab.xml (5), res/layout/item_pro_feature_row_4col.xml (5), res/layout/mode_selector_popup.xml (5), res/layout/number_input_bottom_sheet.xml (5), res/layout/overlay_layer_panel.xml (5), res/layout/overlay_page_tab_bar.xml (5), a:fadrec/ui/FadRecHomeFragment.java (4), a:fadrec/ui/ProjectNamingDialogActivity.java (4), a:ui/faditor/assetbrowser/AssetBrowserAdapter.java (4), a:ui/faditor/sprite/SequenceImportDialog.java (4), a:ui/faditor/tools/FaditorToolsAdapter.java (4), a:ui/lobby/LobbyFragment.java (4), res/layout/activity_fullscreen_preview.xml (4), res/layout/fragment_lobby.xml (4), res/layout/fragment_remote.xml (4), a:fadrec/ui/FloatingWebcamService.java (3), a:fadrec/ui/ProjectSelectionDialogActivity.java (3), a:ui/FaditorMiniFragment.java (3), a:vislab/room/VisLabActivity.java (3), a:ui/TrashAdapter.java (3), a:ui/faditor/ai/bridge/BridgeActivity.java (3), a:ui/faditor/sprite/JoyBrushBoardImportActivity.java (3), a:ui/faditor/RelinkCatalogBottomSheet.java (3), a:ui/faditor/SheetKit.java (3), res/layout/activity_qr_scanner.xml (3), res/layout/bottomsheet_mini_apps_info.xml (3), res/layout/client_info_row_item.xml (3), res/layout/dialog_project_management.xml (3), res/layout/endpoint_row_item.xml (3), res/layout/fragment_settings_home.xml (3), res/layout/fragment_watch_camera.xml (3), res/layout/fragment_watch_settings.xml (3), res/layout/home_recording_tiles_row.xml (3), res/layout/item_annotation_page.xml (3), res/layout/item_project.xml (3), res/layout/item_pro_feature_row.xml (3), a:ui/VideoInfoBottomSheet.java (2), a:ui/WatchVideoPlayerActivity.java (2), a:dualcam/ui/DualCameraInfoBottomSheet.java (2), a:ui/faditor/VideoSourceBottomSheet.java (2), a:ui/library/LibraryDock.java (2), a:ui/library/LibraryCardView.java (2) ...
