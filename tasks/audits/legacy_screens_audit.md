# Legacy screens audit: app/src/main/java/com/fadcam/ui

Date: 2026-10-15. Read-only inventory. No source files were edited.

## Scope and method

- Scope: top-level classes in `app/src/main/java/com/fadcam/ui/*.java` (about 70 files), the `ui/bottomsheet/` subfolder (5 classes, reached from the Remote screens), and the layouts in `app/src/main/res/layout` that those classes inflate.
- Reachability was traced statically: `new X()`, `newInstance`, `Intent(..., X.class)`, `show(...)`, `R.id` rows in the Settings home, the bottom-nav menu, `MainActivity` tab switch, and manifest entries. A class with no caller in Java, XML, or the manifest is marked NONE.
- Look is counted from each layout XML: `s_*`/`studio_*` colour references (Studio tokens), `?attr/` references (theme-driven), hard-coded `#hex` colours, Material widget classes/styles, and legacy colour names (`white`, `black`, `redPastel`, `button_pause`, `gray`, `colorPrimary`).
- Studio token values in `res/values/studio_tokens.xml` match the constants in `studiokit/src/main/java/com/fadcam/ui/faditor/Studio.java` (checked GROUND, SURFACE, PANEL, RAISED, LINE, INK, INK_FAINT, GO).
- Nothing was run on a device. No screenshots were taken. The look column is a static read.

### Look key

- NEW: built from Studio tokens, with few or no hard-coded colours and no Material widgets.
- THEME: no hard-coded colours. It takes colours from the active theme attributes (`?attr/`). The app default theme is "Joy Creator" (`MainActivity` line 1562 and `Constants.DEFAULT_APP_THEME`), so these screens render black at runtime. They do not use Studio tokens directly.
- OLD: Material widgets, hard-coded hex, or legacy colour names dominate, or the activity's manifest theme is a legacy parent (`Theme.MaterialComponents` or `Base.Theme.FadCam`) that the Joy Creator theme does not override.
- MIXED: a blend of the above.

### Verdict key

- OK: keep as is.
- restyle: move to Studio tokens and the Joy Creator look.
- merge: fold into another screen.
- remove-candidate: no caller found. Confirm before deleting.

## Launch flow (what a user sees first)

1. `SplashActivity` (manifest theme `Theme.FadCam.Splash`).
2. `MainActivity` decides the next screen:
   - first run (onboarding not completed): `OnboardingActivity`;
   - intro preference on and onboarding complete: `WhatsNewActivity`;
   - Wear OS: `WatchMainActivity`.
3. `MainActivity` tab bar (`res/menu/bottom_nav_menu.xml`): Home (1), Records (2), Remote (3), Faditor Mini (4), Settings (5), Lab (6, `LobbyFragment`, outside this scope).
4. `HomeFragment.onStart()` runs the update check and can open `UpdateAvailableBottomSheet` for a stable release.

## 1. Launch and first run

| Screen (class) | Layout(s) | What it does | Look | Reachable from | Verdict |
|---|---|---|---|---|---|
| OnboardingActivity (AppIntro host) | simple_list_item_single_choice, dialog_language_dark | Hosts the intro slides and a language dialog | OLD. Manifest parent is `Theme.MaterialComponents.DayNight.NoActionBar` | First run only (`MainActivity` ~line 414); also WatchMainActivity | restyle |
| OnboardingWelcomeFragment | onboarding_welcome | Slide 1, welcome | NEW. 8 Studio tokens, no hard-coded colours | Onboarding slide 1 | OK |
| OnboardingHumanFragment | onboarding_human_slide | Slide 2 | MIXED. 5 tokens, 7 hard-coded `white`, `black`, `#FF35F6BF` | Onboarding | restyle |
| OnboardingPermissionsFragment | onboarding_permissions_slide | Slide 3, permission requests | OLD. 10 hard-coded `white`, 2 MaterialButton, 5 hex | Onboarding | restyle |
| WhatsNewActivity | activity_whats_new | Changelog after an update | MIXED, close to NEW. 10 tokens, 2 Material widgets. Manifest parent is legacy MaterialComponents | `MainActivity` launch path; HomeSidebarFragment | restyle (light) |

## 2. Home tab and its overlays

| Screen (class) | Layout(s) | What it does | Look | Reachable from | Verdict |
|---|---|---|---|---|---|
| HomeFragment | fragment_home | Tab 1 (launch default): recording controls, clock, Pro header, status tiles. 12,922 lines | MIXED toward OLD. 15 tokens, 31 `?attr`, 27 hex, 16 MaterialCardView, 7 MaterialButton, 3 `button_pause` | Home tab | restyle (largest single job) |
| HomeSidebarFragment | fragment_home_sidebar | Side overlay: tips, mini apps, updates, what's new | MIXED. 18 tokens, 44 `?attr`, 10 hex, 14 Material | HomeFragment sidebar | restyle (light) |
| TipsCarouselFragment | fragment_tips_carousel, item_tip_card | Tip carousel sheet | THEME/OLD. 7 `?attr`, 2 Material, 1 hex | HomeSidebarFragment | restyle (light) |
| FadCamProActivity | activity_fadcam_pro | FadCam Pro promo | MIXED. 5 tokens, 14 hex. Theme `FadCamProTheme` has a legacy `Theme.MaterialComponents` parent | HomeFragment Pro header (line ~4683) | restyle |
| FullscreenPreviewActivity | activity_fullscreen_preview | Full-screen camera preview. 2,361 lines | OLD. 33 hard-coded colours, 14 Material3 buttons, blue-grey text. Window uses the Joy Creator theme | HomeFragment preview; WatchFullscreenActivity | restyle |
| PrivacyBlackActivity | none | Black cover screen | NEW. Black | HomeFragment; WatchCameraFragment | OK |
| UpdateAvailableBottomSheet | bottomsheet_update_available (+ UpdateRainView) | Stable update prompt | MIXED. 4 tokens, 1 `?attr`, 3 hex | HomeFragment `onStart()` auto-check (stable only); HomeSidebarFragment; AboutFragment | restyle (launch-time) |
| BetaUpdateBottomSheet | bottomsheet_beta_update | Beta update prompt | OLD/MIXED. 1 token, 3 hex | HomeSidebarFragment | restyle (light) |
| ProUpdateBottomSheet | bottomsheet_pro_update | Pro update prompt | MIXED. 2 tokens, 3 hex | HomeSidebarFragment | restyle (light) |
| MiniAppsInfoBottomSheet | bottomsheet_mini_apps_info | Mini apps info | NEW/THEME. 4 tokens, 11 `?attr` | HomeSidebarFragment | OK |
| DockRevealAnimator | none | Bottom dock reveal animation (helper, not a screen) | n/a | MainActivity; HomeFragment | OK |

## 3. Records and gallery (tab 2)

| Screen (class) | Layout(s) | What it does | Look | Reachable from | Verdict |
|---|---|---|---|---|---|
| RecordsFragment | fragment_records, dialog_progress | Records tab: list or grid, filter chips, batch actions, FABs. 6,280 lines | OLD/MIXED. 4 tokens, 11 hex, 13 Material, 20 filter chips, 10 ChipGroup, FAB and ExtendedFAB, `#CCFFFFFF` text | Records tab | restyle (high) |
| RecordsAdapter | item_record, item_forensics_month_header | Each row in the Records list | OLD. 2 MaterialCardView, `redPastel`, `white`, `black` | RecordsFragment | restyle (high) |
| RecordsSidebarFragment | fragment_records_sidebar | Sort and grid options overlay | THEME plus Material. 9 `?attr`, 4 Material, 2 hex | RecordsFragment sidebar | restyle (light) |
| RecordsOptionsBottomSheet | bottomsheet_records_options | Older records options sheet (uses IconRainView) | NEW-leaning. 5 tokens | NONE (0 callers) | remove-candidate |
| InputActionBottomSheetFragment | picker_bottom_sheet | Confirm sheet for delete and reset actions | THEME | RecordsFragment, RecordsAdapter, TrashFragment and others (12 callers) | OK |
| VideoInfoBottomSheet | bottomsheet_video_info, video_info_row_item | Two-column video info | THEME. 6 `?attr`, 2 tokens | RecordsAdapter | OK |
| ScanInfoBottomSheet | bottomsheet_video_info (same layout as above) | Scan or image info | THEME, same layout as VideoInfoBottomSheet | ImageViewerActivity; RecordsAdapter | merge into VideoInfoBottomSheet |
| VideoPlayerActivity | activity_video_player | Playback with speed overlay and info. 3,729 lines | NEW by theme. No hard-coded colours; Joy Creator at runtime | Records tap (RecordsFragment line ~3003); Trash; Faditor Mini | OK |
| PlayerInfoBottomSheetFragment | picker_bottom_sheet | Player help sheet | THEME | VideoPlayerActivity info button | OK |
| VideoPlayerSettingsFragment | fragment_video_player_settings | Player settings (playback speed, quick speed) | THEME. 11 `?attr` | Settings row; VideoPlayerActivity | OK |
| VideoPlayerSettingsBottomSheetFragment | none | Deprecated 13-line stub | n/a | NONE | remove-candidate |
| ImageViewerActivity | activity_image_viewer (+ ZoomableImageView) | Image viewer | THEME. Joy Creator at runtime | Records image tap; TrashFragment | OK |
| TrashFragment | fragment_trash, dialog_progress | Recently deleted list | OLD/MIXED. 2 tokens, 6 hex, 9 Material (chips, outlined and text buttons), `colorError` | HomeFragment; StorageSettingsFragment; LobbyFragment; OverlayNavUtil | restyle (medium) |
| TrashAdapter | item_trash | Rows in Trash | OLD-leaning. 4 Material, 2 hex | TrashFragment | restyle (with TrashFragment) |
| GalleryFastScroller | none | Draggable scroller (widget) | n/a | RecordsFragment; TrashFragment | OK |

## 4. Remote tab (tab 3) and its sheets

| Screen (class) | Layout(s) | What it does | Look | Reachable from | Verdict |
|---|---|---|---|---|---|
| RemoteFragment | fragment_remote | Stream server, connected clients, quality, cloud link. 1,921 lines | NEW-leaning. 8 tokens, 36 `?attr`, 8 hex, no Material | Remote tab | OK (light: move the 8 hex colours to tokens) |
| ClientDataBottomSheet | bottom_sheet_client_data_usage | Per-client data usage | THEME. 4 `?attr` | RemoteFragment | OK |
| ClientDetailsBottomSheetFragment | bottom_sheet_client_details, client_info_row_item | Connected client details | THEME | RemoteFragment | OK |
| ClientEventLogsBottomSheet (bottomsheet/) | bottom_sheet_client_event_logs | Client event log | THEME | ClientDetailsBottomSheetFragment | OK |
| EndpointsBottomSheetFragment | bottom_sheet_endpoints_simple, endpoint_row_item | Server endpoint list | THEME. 2 hex | RemoteFragment | OK |
| NetworkHealthBottomSheet | bottom_sheet_network_health | Network health metrics | NEW-leaning. 4 tokens, 7 `?attr`, 1 hex | RemoteFragment | OK |
| SegmentsInfoBottomSheet | bottom_sheet_segments_info | Segment buffer info | THEME. 6 `?attr` | RemoteFragment | OK |
| BatteryInfoBottomSheet (bottomsheet/) | bottom_sheet_battery_info | Battery info | OLD. 19 hex, 10 Material, outlined TextInputLayout, `#000000` text | RemoteFragment | restyle (medium) |
| UptimeInfoBottomSheet (bottomsheet/) | bottom_sheet_uptime_info | Uptime info | THEME. 6 `?attr` | RemoteFragment | OK |
| QualityPresetBottomSheet (bottomsheet/) | bottom_sheet_quality_preset | Quality preset picker | THEME. 9 `?attr` | RemoteFragment | OK |
| StreamOrientationBottomSheet (bottomsheet/) | bottom_sheet_stream_orientation | Orientation picker | THEME | NONE (0 callers) | remove-candidate |
| CloudAccountActivity | activity_cloud_account | WebView device-link sign-in | Manifest theme `Base.Theme.FadCam` (legacy, not Joy Creator). 3 hex | RemoteFragment (link cloud) | restyle |

## 5. Settings tab (tab 5) and its sub-screens

Settings sub-screens open through `OverlayNavUtil.show(...)` from the rows in `SettingsHomeFragment`. The shared row style is `SettingsGroupRow`, and its divider is a literal `#262626` (`res/values/styles.xml` line ~121). That is close to, but not, the `LINE` token `#2C2C35`.

| Screen (class) | Layout(s) | What it does | Look | Reachable from | Verdict |
|---|---|---|---|---|---|
| SettingsHomeFragment | fragment_settings_home (+ 11 `item_mini_app_card` includes, widget_clock_preview) | Tab 5 landing: groups, mode button, mini-app cards. 672 lines | MIXED toward NEW. 4 tokens, 25 `?attr`, no hex | Settings tab | OK |
| mode_selector_popup (layout of the mode button in SettingsHomeFragment) | mode_selector_popup | Mode switcher popup | OLD-leaning. 8 MaterialCardView, literal `#4CAF50` (the old Material green the AUDIO token replaced), `#1E1E1E`, `#33FFFFFF` | Settings mode button | restyle |
| AppearanceSettingsFragment | fragment_settings_appearance | Theme, icon, language | THEME. 5 `?attr` | Settings row; MainActivity | OK |
| VideoSettingsFragment | fragment_settings_video | Video recording settings. 1,837 lines | THEME. 11 `?attr` | Settings row; HomeFragment; WatchSettingsFragment | OK |
| AudioSettingsFragment | fragment_settings_audio | Audio settings | THEME. 4 `?attr` | Settings row | OK |
| BehaviorSettingsFragment | fragment_settings_behavior | General behaviour switches | THEME plus Material. 4 Material, 2 switches | NONE. Only a comment in NotificationSettingsFragment | remove-candidate |
| NotificationSettingsFragment | fragment_settings_notification, bottomsheet_notification_custom | Notification settings | THEME. 4 `?attr` | Settings row | OK |
| StorageSettingsFragment | fragment_settings_storage | Storage location | THEME. 1 `?attr` | Settings row | OK |
| SecuritySettingsFragment | fragment_settings_security | App lock | THEME. 3 `?attr` | Settings row | OK |
| ShortcutsSettingsFragment | fragment_settings_shortcuts | Start, stop and torch shortcuts | THEME. 1 include | Settings row; MainActivity | OK |
| ShortcutIconGridBottomSheet | bottomsheet_app_icon_grid | App icon picker | MIXED. 1 token, 2 hex, IconRainView | ShortcutsSettingsFragment | OK |
| AppIconAdapter | item_app_icon_grid | Grid cells for the icon picker | THEME | ShortcutIconGridBottomSheet | OK |
| AppIconGridBottomSheet | bottomsheet_app_icon_grid | Older copy of the icon picker | Same layout as above | NONE (only a comment in ShortcutIconGridBottomSheet) | remove-candidate |
| WatermarkSettingsFragment | fragment_settings_watermark, item_watermark_preview | Watermark design and live preview. 1,067 lines. Includes the location row | THEME plus Material. 19 `?attr`, 2 TabLayout, 8 switches | Settings row `group_watermark_quick` | restyle (medium: default Material tabs) |
| LocationPrivacySettingsFragment | fragment_settings_location_privacy | Location embed toggle | THEME | NONE. Its toggle now lives in WatermarkSettingsFragment | merge into WatermarkSettingsFragment (or remove) |
| DigitalForensicsSettingsFragment | fragment_settings_digital_forensics | Forensics settings | THEME. 5 `?attr`, 2 hex | Settings row | OK |
| MotionLabSettingsFragment | fragment_settings_motion_lab | Motion Lab settings | MIXED. 8 `?attr`, 7 hex, 2 switches | Settings row | restyle (light) |
| MotionLabInfoBottomSheetFragment | picker_bottom_sheet | Motion Lab info | THEME | MotionLabSettingsFragment | OK |
| AdvancedSettingsFragment | fragment_settings_advanced | "Power-user entry points" | THEME. 2 `?attr` | NONE. No settings row opens it | remove-candidate |
| MiniAppInfoBottomSheetFragment | fragment_mini_app_info_sheet | Mini-app "coming soon" info | THEME. 9 `?attr`, 1 hex | NONE. MiniAppsInfoBottomSheet is used instead | merge into MiniAppsInfoBottomSheet (or remove) |
| AboutFragment | fragment_about, loading_dialog | About, links, version, Ko-fi | OLD/MIXED. 25 `?attr`, 4 MaterialCardView, 3 MaterialButton, 3 OutlinedButton | Settings row `group_about` | restyle (medium) |
| KoFiSupportBottomSheet | bottomsheet_kofi_support | Support sheet | MIXED. 5 hex. Some may be the Ko-fi brand colour | AboutFragment | restyle (light; check brand colours first) |
| ReadmeBottomSheetFragment | dialog_readme, picker_bottom_sheet | README sheet | MIXED. 6 Material text views | Settings row | OK |
| DebugLogBottomSheetFragment | picker_bottom_sheet | Debug log preview, search, share | THEME | Settings debug row | OK |
| PrefsBackupBottomSheetFragment | picker_bottom_sheet | Import, export, reset preferences | THEME | Settings row | OK |
| WidgetsBottomSheetFragment | none. Source file is 0 bytes | Empty | n/a | NONE | remove-candidate |

## 6. Other activities and adapters in scope

| Screen (class) | Layout(s) | What it does | Look | Reachable from | Verdict |
|---|---|---|---|---|---|
| ViewPagerAdapter | none | Old ViewPager2 adapter (72 lines) | n/a | NONE. `MainActivity` swaps fragments itself | remove-candidate |
| WebViewActivity | activity_webview | Generic WebView | Manifest only | NONE. Declared in manifest, no Intent | remove-candidate |
| FaditorMiniFragment | fragment_faditor_mini | Faditor Mini tab 4: start project, recent projects | NEW-leaning. 13 tokens, 4 `?attr`, 5 hex, 3 Material | Tab 4 | OK (light) |

## 7. Wear OS screens (not on phone launch)

| Screen (class) | Layout(s) | Look | Reachable from | Verdict |
|---|---|---|---|---|
| WatchMainActivity | activity_watch_main | NEW (1 hex) | MainActivity redirect on Wear | OK |
| WatchCameraFragment | fragment_watch_camera | NEW (8 tokens) | WatchMainActivity page 1 | OK |
| WatchRecordsFragment | fragment_watch_records, item_watch_recording | NEW-leaning | WatchMainActivity | OK |
| WatchRemoteFragment | fragment_watch_remote | NEW (3 tokens) | WatchMainActivity | OK |
| WatchSettingsFragment | fragment_watch_settings | NEW (7 tokens) | WatchMainActivity | OK |
| WatchTrashFragment | fragment_watch_trash, item_watch_trash | NEW-leaning | WatchRecordsFragment; WatchCameraFragment | OK |
| WatchFullscreenActivity | activity_watch_fullscreen | NEW | WatchCameraFragment | OK |
| WatchVideoPlayerActivity | activity_watch_video_player | MIXED. 3 tokens, 6 hex | WatchRecordsFragment | restyle (light) |

## 8. Shared views and helpers (not screens)

- AvatarToggleView: used by 16 files, including camera and Remote screens. OK.
- OverlayNavUtil: used by 35 files. Every Settings sub-screen and overlay opens through it. OK.
- BaseFragment, TouchDelegates, GlowSlide, FadePageTransformer, ZoomableImageView, GalleryFastScroller, ShimmerButton, ShimmerTextView, CoffeeRainView, IconRainView, UpdateRainView, VideoItem, RecordActionListener, GeotagHelper, LocationHelper: OK.
- ShimmerButton is used only by FadCamProActivity. ShimmerTextView is used only by HomeFragment's Pro header.

## Remove-candidates (no caller found)

Each has a static reference count of zero outside its own file, and the manifest was checked for activities. A repo-wide re-check (background grep, worktrees excluded) agreed. These are orphans, not verdicts to delete. The project rule is that half-built work is recovered, not archived, so each one needs an owner decision: revive it or remove it. `tasks/NAV_SPEC.md` line 119 already records RecordsOptionsBottomSheet as "dead (0 refs) → delete or revive".

1. `WidgetsBottomSheetFragment.java`: empty file.
2. `VideoPlayerSettingsBottomSheetFragment.java`: deprecated 13-line stub.
3. `ViewPagerAdapter.java`: unused. MainActivity uses fragment swapping.
4. `WebViewActivity.java` and `activity_webview.xml`: manifest-only activity.
5. `AdvancedSettingsFragment.java` and `fragment_settings_advanced.xml`: no Settings row opens it.
6. `BehaviorSettingsFragment.java` and `fragment_settings_behavior.xml`: only a comment refers to it.
7. `LocationPrivacySettingsFragment.java` and `fragment_settings_location_privacy.xml`: duplicate of the location row in Watermark settings. Merge or remove.
8. `RecordsOptionsBottomSheet.java` and `bottomsheet_records_options.xml`: no caller.
9. `AppIconGridBottomSheet.java`: no caller. Its layout is still used by ShortcutIconGridBottomSheet.
10. `MiniAppInfoBottomSheetFragment.java` and `fragment_mini_app_info_sheet.xml`: MiniAppsInfoBottomSheet is used instead.
11. `bottomsheet/StreamOrientationBottomSheet.java` and `bottom_sheet_stream_orientation.xml`: no caller.

## Notes and caveats

- Theme: `MainActivity` applies `Theme_FadCam_JoyCreator` when the preference is "Joy Creator" (the default). Several activities set it at runtime (VideoPlayerActivity, ImageViewerActivity, FullscreenPreviewActivity). Five activities do not: OnboardingActivity, WhatsNewActivity, FadCamProActivity (legacy parents) and CloudAccountActivity and WebViewActivity (`Base.Theme.FadCam`). Their window chrome stays legacy whatever the user picks.
- The older Material themes (Crimson Bloom, Gold, SilentForest, and others) are still selectable in `themes.xml`. The restyle work should not remove them without an owner decision.
- `KoFiSupportBottomSheet` may use the Ko-fi brand colour on purpose. Check that before restyling.
- Lab tab (`LobbyFragment`), Faditor and FadRec packages, and the Wear screens are outside the phone-screen scope. Wear is listed for completeness.
- Counts are from a static scan of XML and Java, not a rendered check.

## Prioritised: 10 screens a user most likely sees at launch that most need restyling

1. **Home tab (HomeFragment + fragment_home).** Default landing tab on every launch. Mixed Material cards and buttons, 27 hard-coded hex colours, and a 12,922-line class.
2. **Records tab (RecordsFragment + fragment_records).** Second tab. Material filter chips, ChipGroup, FAB and ExtendedFAB, and `#CCFFFFFF` text.
3. **Record rows (RecordsAdapter + item_record).** Every record a user owns is one of these rows. Legacy `redPastel`, `white` and `black`, and MaterialCardView.
4. **Onboarding (OnboardingActivity + onboarding_human_slide + onboarding_permissions_slide).** First run. Legacy MaterialComponents parent theme and hard-coded white text. The welcome slide is already NEW, so the other two should match it.
5. **What's New (WhatsNewActivity).** Shown from the launch path when the intro preference is on. Legacy parent theme, two Material buttons.
6. **Update sheet (UpdateAvailableBottomSheet).** Can open from Home's `onStart()` auto-check when a stable release exists. Mixed tokens and hex.
7. **Settings tab and mode popup (SettingsHomeFragment + mode_selector_popup).** Settings is a default tab. The popup uses a literal Material green `#4CAF50` and Material cards.
8. **Home sidebar (HomeSidebarFragment).** Opened from the Home tab. 14 Material widgets and 10 hex colours mixed into a settings-style list.
9. **Full-screen preview (FullscreenPreviewActivity).** Opened from Home's preview. 33 hard-coded colours and Material3 buttons.
10. **Trash (TrashFragment + TrashAdapter).** Reached from Home, Storage and Lab. Material chips, outlined buttons, and `colorError`.

Next in line: FadCam Pro (FadCamProActivity), About (AboutFragment), and the Remote battery sheet (BatteryInfoBottomSheet).
