# Clickable icon-only controls with no hover label (tooltip)

Date: 2026-11-13. Worktree `launch-docs-20261113-042476` (HEAD e2fe46186). Rule reminder: every button gets a hover label (stylus and mouse show tooltips on Android).

**Method.** (1) Layouts: a small script read every `<ImageButton>`, clickable `<ImageView>`, `FloatingActionButton`, and `MaterialButton`/`Button` that has `app:icon` but no `android:text` in `res/layout` and `res/layout-land`, and kept those with **no** `android:tooltipText`. It also recorded whether `android:contentDescription` exists. (2) Code: for every Java/Kotlin file in `app/src/main`, `studiokit`, `joybrush-android/src/main` that calls `setOnClickListener`/`setOnTouchListener`, it counted label calls: `setTooltipText`, `TooltipCompat`, and the three in-house helpers found (`Ui.label` in vislab, `hint(` in SpriteSheetEditorActivity, `tooltip(` in joyfolder, `ChromeKit.label` in JoyBrush, which sets both contentDescription and tooltip). Listing files by "listeners vs label calls".

**Limits (read before trusting).** A grep cannot prove a given view has or lacks a tooltip. (a) Code is by file, not by view: a file with 12 listeners and 2 label calls may label exactly the two icon-only buttons, or none. (b) A file with listeners and zero label calls may have only text buttons (nothing owed) or Material buttons whose text is the label. (c) Views built in loops, adapters, or by helper in another file are not traced. (d) Layout views toggled `android:clickable` in code, or `LinearLayout`/`FrameLayout` icon cells that act as buttons, are not detected. (e) Custom canvas-drawn views (JoyBrush chrome, Faditor timeline) take taps by touch handlers; their hover labels, if any, are not visible to this scan. (f) `android:tooltipText` set in a `<style>` is not seen. (g) A tooltip only shows on hover/long-press on API 26+. (h) "contentDescription present" in a layout does NOT give a hover label by itself on Android: it feeds TalkBack only.

## Totals

| measure | count |
|---|---|
| layout icon-only controls without tooltipText | 54 |
| of those with contentDescription already (tooltip is the only gap) | 37 |
| of those with no contentDescription either | 17 |
| layout controls that DO have tooltipText (for scale) | 36 (12 layout files) |
| code files with click listeners | 204 |
| code files with listeners and zero label calls, referencing images/icons | 25 |
| code files with listeners and zero label calls, no image references | 47 |
| code files with listeners where labels look sparse (labels under half of listeners, 3+ listeners) | 27 |

Overall: the layouts are mostly labelled already, but the 54 below are not; the larger gap is code-built UI (FadRec overlay, Forensics, onboarding, Records/Trash adapters). JoyBrush Kotlin routes nearly every chrome control through `ChromeKit.label`, so it is largely covered; its misses are listed in section 3.

## 1. Layouts: no tooltipText (file:line, view, id, contentDescription)

### res/layout/activity_chat_assistant.xml (5)
- 19 ImageButton `chat_back` desc: Close
- 68 ImageButton `chat_settings` desc: Settings
- 225 ImageButton `chat_attach` desc: Attach image
- 247 ImageButton `chat_mic` desc: Voice input
- 270 ImageButton `chat_send` desc: Send
### res/layout/activity_fullscreen_preview.xml (7)
- 190 MaterialButton `btnZoomReset` desc: @string/zoom_slider_title
- 232 ImageView `btnClose` desc: @string/fullscreen_preview_close
- 320 MaterialButton `btnFullscreenCamSwitch` desc: @string/fullscreen_camera_switch
- 359 MaterialButton `btnFullscreenTorch` desc: @string/fullscreen_torch
- 398 MaterialButton `btnFullscreenPauseResume` desc: @string/button_pause
- 542 MaterialButton `btnFullscreenMirror` desc: @string/front_video_mirror_enable
- 586 MaterialButton `btnFullscreenCaptureShot` desc: @string/shortcut_take_photo
### res/layout/activity_qr_scanner.xml (2)
- 45 ImageView `qr_close` desc: @string/button_close
- 196 ImageView `qr_torch` desc: @string/button_torch
### res/layout/activity_video_player.xml (3)
- 21 ImageButton `back_button` desc: **none**
- 32 ImageButton `info_button` desc: @string/player_info_title
- 45 ImageButton `reset_zoom_button` desc: @string/desc_reset_zoom
### res/layout/activity_watch_fullscreen.xml (1)
- 45 ImageView `btn_watch_fs_back` desc: @string/back
### res/layout/activity_watch_video_player.xml (2)
- 31 ImageView `watch_player_back` desc: @string/back
- 62 ImageView `watch_player_play_pause` desc: **none**
### res/layout/bottom_sheet_dual_cam_info.xml (1)
- 17 ImageView `btn_close` desc: @string/close
### res/layout/bottomsheet_update_available.xml (1)
- 30 ImageButton `btnCloseSheet` desc: @string/desc_close
### res/layout/exo_styled_player_control_view.xml (10)
- 100 ImageButton `-` desc: **none**
- 101 ImageButton `-` desc: **none**
- 102 ImageButton `-` desc: **none**
- 103 ImageButton `-` desc: **none**
- 106 ImageButton `-` desc: @string/settings_title
- 114 ImageButton `-` desc: **none**
- 134 ImageButton `-` desc: **none**
- 149 ImageButton `-` desc: @string/desc_rewind
- 162 ImageButton `-` desc: @string/exo_controls_play_description
- 176 ImageButton `-` desc: @string/desc_fast_forward
### res/layout/fragment_home_sidebar.xml (2)
- 32 ImageView `home_sidebar_close_btn` desc: **none**
- 406 ImageView `btn_mini_apps_info` desc: @string/desc_mini_apps_info
### res/layout/fragment_lab_sidebar.xml (1)
- 28 ImageView `lab_sidebar_close_btn` desc: **none**
### res/layout/fragment_mini_app_info_sheet.xml (1)
- 28 ImageView `mini_app_close_btn` desc: **none**
### res/layout/fragment_records.xml (1)
- 819 FloatingActionButton `fab_scroll_navigation` desc: @string/scroll_to_top
### res/layout/fragment_records_sidebar.xml (1)
- 33 ImageView `records_sidebar_close_btn` desc: **none**
### res/layout/fragment_torch_tool.xml (1)
- 25 ImageView `torch_close_btn` desc: @string/button_close
### res/layout/fragment_watch_camera.xml (4)
- 27 ImageView `iv_watch_app_title` desc: @string/app_name
- 43 ImageView `btn_watch_hamburger` desc: @string/watch_hamburger_desc
- 148 ImageView `iv_watch_audio_toggle` desc: @string/watch_audio_toggle_desc
- 180 ImageView `iv_watch_pause_resume` desc: @string/watch_pause_desc
### res/layout/fragment_watch_trash.xml (1)
- 25 ImageView `btn_watch_trash_back` desc: @string/watch_back_desc
### res/layout/item_watch_recording.xml (1)
- 69 ImageView `btn_watch_rec_menu` desc: @string/watch_video_menu_desc
### res/layout/overlay_torch_screen_light.xml (1)
- 29 ImageView `screen_light_close_btn` desc: @string/button_close
### res/layout/picker_bottom_sheet.xml (1)
- 23 ImageView `picker_close_btn` desc: @string/close
### res/layout/picker_bottom_sheet_slider.xml (1)
- 278 ImageView `picker_slider_reset_icon` desc: @string/desc_reset
### res/layout-land/fragment_home.xml (6)
- 20 ImageView `btnHamburgerMenu` desc: @string/home_menu
- 652 MaterialButton `btnPreviewZoomReset` desc: **none**
- 685 MaterialButton `buttonTorchSwitch` desc: **none**
- 709 MaterialButton `buttonPauseResume` desc: **none**
- 714 MaterialButton `buttonCamSwitch` desc: **none**
- 719 MaterialButton `buttonMirrorSwitch` desc: **none**

Notes: `exo_styled_player_control_view.xml` is the ExoPlayer control layout (ids omitted; the ids are Exo ids on the next lines of the file). Items with `desc: **none**` need a description and a tooltip; the others need only the tooltip (plus `TooltipCompat` if minSdk < 26).

## 2. Code-built UI: listeners, no label call, and the file handles images/icons (most likely icon-only buttons; check these first)

| file | listeners | label calls | image refs | contentDesc calls |
|---|---|---|---|---|
| a:fadrec/ui/AnnotationService.java | 50 | 0 | 2 | 0 |
| a:forensics/ui/ForensicsEventsFragment.java | 13 | 0 | 2 | 0 |
| a:forensics/ui/ForensicsGalleryFragment.java | 11 | 0 | 16 | 1 |
| a:fadrec/ui/ScreenRecordingSettingsFragment.java | 10 | 0 | 2 | 0 |
| a:fadrec/ui/overlay/InlineTextEditor.java | 10 | 0 | 9 | 0 |
| a:fadrec/ui/overlay/TextEditorActivity.java | 10 | 0 | 4 | 0 |
| a:ui/OnboardingHumanFragment.java | 10 | 0 | 7 | 1 |
| a:fadrec/ui/FadRecHomeFragment.java | 7 | 0 | 13 | 3 |
| a:ui/VideoPlayerActivity.java | 7 | 0 | 16 | 0 |
| a:ui/miniapps/TorchToolFragment.java | 7 | 0 | 2 | 0 |
| a:ui/picker/PickerBottomSheetFragment.java | 7 | 0 | 14 | 2 |
| a:ui/miniapps/QRScannerActivity.java | 6 | 0 | 4 | 0 |
| a:ui/TrashAdapter.java | 4 | 0 | 13 | 0 |
| a:ui/bottomsheet/QualityPresetBottomSheet.java | 4 | 0 | 2 | 0 |
| a:forensics/ui/ForensicIntelligenceFragment.java | 3 | 0 | 2 | 0 |
| a:forensics/ui/ForensicsEventsAdapter.java | 3 | 0 | 8 | 0 |
| a:forensics/ui/ForensicsGalleryAdapter.java | 3 | 0 | 16 | 0 |
| a:ui/bottomsheet/StreamOrientationBottomSheet.java | 3 | 0 | 2 | 0 |
| jb:chrome/UnibrushHostController.kt | 3 | 0 | 3 | 1 |
| jb:library/DrawingsActivity.kt | 3 | 0 | 4 | 3 |
| a:ui/faditor/assetbrowser/AssetBrowserAdapter.java | 2 | 0 | 10 | 2 |
| a:ui/faditor/overlay/TextOverlayLayer.java | 2 | 0 | 31 | 0 |
| a:ui/faditor/waveform/WaveformDebugActivity.java | 2 | 0 | 5 | 0 |
| a:forensics/ui/ForensicsGalleryInfoBottomSheetFragment.java | 1 | 0 | 2 | 0 |
| a:ui/AppIconAdapter.java | 1 | 0 | 3 | 0 |

## 3. Code: sparse labelling (3+ listeners, labels under half of them)

| file | listeners | label calls | image refs | contentDesc calls |
|---|---|---|---|---|
| a:ui/faditor/sprite/SpriteSheetEditorActivity.java | 72 | 20 | 3 | 3 |
| a:ui/RecordsFragment.java | 34 | 10 | 22 | 5 |
| a:ui/HomeSidebarFragment.java | 19 | 4 | 2 | 1 |
| a:ui/faditor/sprite/SpritePalettePanel.java | 19 | 3 | 0 | 0 |
| a:ui/RemoteFragment.java | 16 | 2 | 4 | 1 |
| a:ui/lobby/LobbyFragment.java | 16 | 7 | 11 | 5 |
| a:ui/WatermarkSettingsFragment.java | 15 | 2 | 0 | 0 |
| a:ui/faditor/avatar/AvatarStudioActivity.java | 14 | 3 | 0 | 0 |
| a:ui/RecordsAdapter.java | 13 | 4 | 24 | 5 |
| a:ui/VideoSettingsFragment.java | 12 | 2 | 0 | 1 |
| a:ui/AudioSettingsFragment.java | 11 | 2 | 0 | 0 |
| a:ui/MotionLabSettingsFragment.java | 10 | 4 | 2 | 0 |
| a:ui/VideoPlayerSettingsFragment.java | 10 | 2 | 0 | 1 |
| a:ui/SettingsHomeFragment.java | 9 | 1 | 2 | 0 |
| a:ui/TrashFragment.java | 9 | 4 | 15 | 0 |
| a:ui/faditor/ObjectMenuSheet.java | 9 | 1 | 0 | 1 |
| a:ui/faditor/tools/AudioDrawerTabs.java | 8 | 1 | 0 | 2 |
| a:cc/joycreator/textlab/TextLookRow.java | 6 | 1 | 0 | 1 |
| a:ui/AboutFragment.java | 6 | 2 | 9 | 0 |
| a:ui/AppearanceSettingsFragment.java | 6 | 2 | 0 | 0 |
| a:ui/DebugLogBottomSheetFragment.java | 6 | 2 | 14 | 1 |
| a:ui/DigitalForensicsSettingsFragment.java | 5 | 2 | 0 | 0 |
| a:ui/RecordsSidebarFragment.java | 5 | 2 | 4 | 1 |
| a:ui/faditor/tools/FaditorToolsDrawer.java | 4 | 1 | 0 | 0 |
| a:joyfolder/studio/SpaceActivity.java | 3 | 1 | 0 | 1 |
| a:ui/faditor/VideoSourceBottomSheet.java | 3 | 1 | 5 | 0 |
| jb:select/SelectRowView.kt | 3 | 1 | 0 | 0 |

## 4. Code: listeners, zero label calls, no image references (probably text buttons or custom views; low priority, spot check)

| file | listeners | label calls | image refs | contentDesc calls |
|---|---|---|---|---|
| a:fadrec/ui/LayerPanelOverlay.java | 12 | 0 | 0 | 0 |
| a:fadrec/ui/FloatingWebcamService.java | 11 | 0 | 0 | 0 |
| a:fadrec/ui/PageTabBarOverlay.java | 9 | 0 | 0 | 0 |
| a:dualcam/ui/DualCameraSettingsFragment.java | 8 | 0 | 0 | 0 |
| a:fadrec/ui/FloatingControlsService.java | 8 | 0 | 0 | 0 |
| sk:ui/faditor/tools/ColorPickerDialog.java | 7 | 0 | 0 | 0 |
| a:fadrec/ui/annotation/TextEditorDialog.java | 6 | 0 | 0 | 0 |
| a:fadrec/ui/annotation/PageManagementBottomSheet.java | 3 | 0 | 0 | 0 |
| a:fadrec/ui/annotation/ShapePickerDialog.java | 3 | 0 | 0 | 0 |
| a:forensics/ui/ForensicsEvidenceInfoBottomSheet.java | 3 | 0 | 0 | 0 |
| a:forensics/ui/ForensicsExportCenterFragment.java | 3 | 0 | 0 | 0 |
| a:forensics/ui/LabSidebarFragment.java | 3 | 0 | 0 | 0 |
| a:ui/OnboardingPermissionsFragment.java | 3 | 0 | 0 | 5 |
| a:ui/WatchSettingsFragment.java | 3 | 0 | 0 | 0 |
| a:ui/components/ModeSwitcherComponent.java | 3 | 0 | 0 | 0 |
| a:ui/picker/NumberInputBottomSheetFragment.java | 3 | 0 | 0 | 0 |
| a:fadrec/ui/annotation/LayerManagementBottomSheet.java | 2 | 0 | 0 | 0 |
| a:forensics/ui/ForensicsInsightsFragment.java | 2 | 0 | 0 | 0 |
| a:ui/OnboardingWelcomeFragment.java | 2 | 0 | 0 | 0 |
| a:ui/bottomsheet/BatteryInfoBottomSheet.java | 2 | 0 | 0 | 0 |
| a:ui/faditor/FilterBottomSheet.java | 2 | 0 | 0 | 2 |
| a:ui/faditor/tools/BlendPickerPopover.java | 2 | 0 | 0 | 0 |
| a:ui/picker/TextInputBottomSheetFragment.java | 2 | 0 | 0 | 0 |
| jb:chrome/Popovers.kt | 2 | 0 | 0 | 0 |
| a:dualcam/ui/DualCameraInfoBottomSheet.java | 1 | 0 | 0 | 0 |
| a:fadrec/ui/ColorPickerDialogActivity.java | 1 | 0 | 0 | 0 |
| a:fadrec/ui/MinimizableOverlayButton.java | 1 | 0 | 0 | 0 |
| a:fadrec/ui/ProjectSelectionDialogActivity.java | 1 | 0 | 0 | 0 |
| a:ui/NetworkHealthBottomSheet.java | 1 | 0 | 0 | 0 |
| a:ui/OnboardingActivity.java | 1 | 0 | 0 | 0 |
| a:ui/PrivacyBlackActivity.java | 1 | 0 | 0 | 0 |
| a:ui/RecordsOptionsBottomSheet.java | 1 | 0 | 0 | 0 |
| a:ui/faditor/EasePickerPopover.java | 1 | 0 | 0 | 0 |
| a:ui/faditor/SlideCodeBottomSheet.java | 1 | 0 | 0 | 0 |
| a:ui/faditor/effects/LutLibraryPanel.java | 1 | 0 | 0 | 0 |
| a:ui/faditor/sprite/SequenceImportDialog.java | 1 | 0 | 0 | 0 |
| a:ui/faditor/text/TextStyleLibraryPanel.java | 1 | 0 | 0 | 0 |
| a:ui/faditor/tools/LinkTool.java | 1 | 0 | 0 | 0 |
| a:ui/faditor/tools/PinPickerDialog.java | 1 | 0 | 0 | 2 |
| a:ui/faditor/tools/PivotPickerPopover.java | 1 | 0 | 0 | 0 |
| a:ui/faditor/tools/SwipeUpHorizontalScrollView.java | 1 | 0 | 0 | 0 |
| a:ui/library/LibraryAdapter.java | 1 | 0 | 0 | 0 |
| a:ui/motion/Motion.java | 1 | 0 | 0 | 0 |
| a:ui/settingshub/SettingsHubAdapter.java | 1 | 0 | 0 | 0 |
| a:ui/trash/TrashAdapter.java | 1 | 0 | 0 | 0 |
| a:ui/trash/TrashSheets.java | 1 | 0 | 0 | 0 |
| jb:board/BoardChromeView.kt | 1 | 0 | 0 | 1 |

## 5. JoyBrush Kotlin specifically

| file | listeners | label calls |
|---|---|---|
| jb:chrome/UnibrushHostController.kt | 3 | 0 |
| jb:library/DrawingsActivity.kt | 3 | 0 |
| jb:chrome/Popovers.kt | 2 | 0 |
| jb:select/SelectRowView.kt | 3 | 1 |
| jb:board/BoardChromeView.kt | 1 | 0 |
| jb:select/SelectToast.kt | 3 | 2 |
| jb:JoyBrushActivity.kt | 11 | 11 |
| jb:chrome/QuadShapeEditorView.kt | 1 | 1 |
| jb:chrome/BrushDrawerView.kt | 2 | 3 |
| jb:chrome/PaperSheetView.kt | 4 | 5 |
| jb:chrome/SymmetryWidgetView.kt | 1 | 2 |
| jb:select/SelectBarView.kt | 1 | 2 |
| jb:select/SelectController.kt | 2 | 3 |
| jb:select/SelectSettingsView.kt | 3 | 4 |
| jb:chrome/LayerColumnView.kt | 2 | 4 |
| jb:chrome/ToolStripView.kt | 5 | 7 |
| jb:board/BoardPanels.kt | 1 | 5 |
| jb:chrome/UnibrushSheetControls.kt | 2 | 6 |
| jb:chrome/BrushSettingsView.kt | 1 | 6 |
| jb:chrome/TipProfilePointEditorView.kt | 1 | 6 |

The rows with fewer label calls than listeners (UnibrushHostController, Popovers, DrawingsActivity, SelectRowView, SelectToast) are the places to read; the rest look covered by `ChromeKit.label`. Canvas-drawn views that take taps in `onTouchEvent` without a listener (strip cells, curve editors) are invisible to this scan.

