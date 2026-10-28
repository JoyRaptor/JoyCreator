# User-visible FadCam / FadRec / FadShot / Faditor text, 2026-10-28

Sweep for [UI-1](../jobs/UI-1_app_ui_leftovers.md) part A, names only ([FREE-2](../jobs/FREE-2_names_and_ids.md) owns the app ID, packages and
folder names). Code at `4cbf972e4` (`origin/joy-creator`). Nothing here touches package names, class names, log tags, preference keys,
on-disk paths, or the credit to the original FadCam project.

## The watermark (an owner call is needed)

| Question | Answer | Evidence |
|---|---|---|
| Is it on by default? | **Yes.** The default style is `timestamp_fadcam` ("Time with Joy Creator" in the picker). | `Constants.java:474`, `SharedPreferencesManager.java:1100-1103` |
| What does a camera recording burn in? | `Captured by <FADCAM_ICON> - 28/Oct/2026 09:41:07 AM` plus timezone, optional location and custom text. | `RecordingService.java:3071-3073`, `:3110-3112`; `WatermarkManager.java:159-161`; `PhotoStorageHelper.java:303` |
| What is `<FADCAM_ICON>`? | Not text. It is the picture `res/drawable/menu_icon_unknown.png`: the **FadCam wordmark with the red-eyed figure**. | `GLWatermarkRenderer.java:1221-1224`, `:1281-1290` |
| What does a screen recording burn in? | `Recorded by <ICON> - <time>`, where `<ICON>` is `res/drawable/fadrec.png`: the **"Rec" lettering with the red-eyed figure**. | `ScreenRecordingService.java:828-832`, `GLWatermarkRenderer.java:1217-1220` |
| Any literal "FadCam" or "FadRec" text burned in? | No. The words are only in the two pictures. The text parts are "Captured by" / "Recorded by". | same |
| Other styles | "Brand Label Only" (`badge_fadcam`) burns the same picture; "Timestamp" and "No Watermark" burn none. | `WatermarkSettingsFragment.java:438-451` |

**Left alone on purpose.** It is on by default, so I did not change the default (the owner's "no forced watermark" promise). Changing the
words cannot fix it either, because what shows is a picture of the old brand. Options for the owner: (a) default to No Watermark,
(b) swap the two pictures for a Joy Creator mark the owner draws (same filenames, no code change), (c) have the renderer draw the words
"Joy Creator" in place of the picture. The settings preview shows the same picture (`WatermarkSettingsFragment.java:486-491`, `:1005-1006`).

## Changed (English)

| Was | Now | Where |
|---|---|---|
| "Time with Fadcam" (watermark picker array) | "Time with Joy Creator" | `strings.xml` `array_watermark_time_fadcam` |
| "FadShot" (Records chip) | "Photos" | `strings.xml:451` |
| "Show FadShot and Fullscreen icons ..." | "Show Photo and Fullscreen icons ..." | `strings.xml:1023` |
| "FadShot" (landscape Home button caption) | "Photo" | `res/layout-land/fragment_home.xml:604` |
| "FadShot Back" / "FadShot Front" (shortcut badge) | "Photo Back" / "Photo Front" | `ShortcutsSettingsFragment.java:172-174` |
| "...within FadCam..." and "...FadCam automatically saves..." (Mini Apps info sheet) | "Joy Creator" | `res/layout/bottomsheet_mini_apps_info.xml:74`, `:122` |
| "FadCam could not start recording." / clip label "FadCam Error" | "Joy Creator ..." | `HomeFragment.java:2387`, `:2421` |
| "FadRec Annotation" (notification title) | "Joy Creator Annotation" | `AnnotationService.java:3487` |
| "FadCam Playback" (notification channel name) | "Joy Creator Playback" (channel id unchanged) | `PlaybackService.java:439` |
| "FadCam Device" (default cloud device name) | "Joy Creator Device" | `CloudAccountActivity.java:72` |
| "Set up E2E encryption in the FadCam dashboard ..." (toast) | "... in the web dashboard ..." | `RemoteFragment.java:1228` |

### Remote screen
- Two headings both said "Security" ("SECURITY" over the encryption card, "REMOTE SECURITY" over the password card). Now **Encryption** (`strings_remote.xml` `remote_section_security`) and **Access** (`strings.xml` `remote_security_title`).
- Twenty-seven hard-coded English strings in `RemoteFragment.java` moved to `strings_remote.xml`: 15 toasts, the set-password hint and helper text, and the five auto-lock picker rows (labels and descriptions).
  Still English-in-code there, not user-facing words: the placeholder URLs (`:662`, `:683-685`) and the dot mask (`:1152`).

## Changed (translations): brand name only

Twelve locale files: `values-ar, de, el, es, et, fr, in, it, ps, ru, tr, zh-rCN`. **493 string values** (56 distinct keys) had "FadCam"/"Fadcam"
replaced with "Joy Creator", and nothing else in the sentence changed. A string was only touched where the English text had already dropped
the brand (so the two stay in step). Inflected languages (ru, tr, ar, et, el) may read slightly off with an uninflected product name: a
translator pass, not a code job. Comments, `translatable="false"` keys, paths and "FadCam Pro" were not touched.

**Left in translations (listed, not changed):**
- 253 values where the English still names it (the "Left alone" list below): the translation matches the English.
- 216 values in 20 keys that say "FadRec" or "FadShot" where the English now says "Screen" or "Photo": `desc_fadrec_logo`, `faditor_fadrec_source`,
  `fadrec_mic_permission_needed_message`, `fadrec_mode_title`, `fadrec_preview_not_available`, `fadrec_projects`, `media_type_photo_fadrec`,
  `preview_quick_actions_sub`, `records_filter_helper_screen`, `records_filter_helper_shot_fadrec`, `records_filter_screen`, `records_filter_shot`,
  `records_filter_shot_fadrec`, `screen_rec_splitting_value_format`, `screen_recording_notif_title`, `screenshot_accessibility_enable_needed`,
  `settings_tab_fadrec`, `shortcut_take_screenshot`, `watch_fadshot_label`, `watermark_preview_fadrec`. Each needs the local word for "Screen" or
  "Photo", which I will not guess in twelve languages.
- 27 keys where the brand sits in a path or the Pro name in at least one locale (`faditor_info_export_desc`, `storage_location_note`, ...), and two
  mixed strings (`app_description`, `easter_eggs_array`) with a brand left after the replacement. Re-read these with a translator.

## Left alone on purpose (still say FadCam or FadShot)

| Text | Why | Where |
|---|---|---|
| "Downloads/FadCam", "FadCam/FadShot location", "Faditor_" prefix, "Documents/FadRec/Projects" in strings and a layout | Real folder or file names on the phone. Rewording them would be false, and renaming the folders orphans files. FREE-2 owns this. | `strings.xml:297-304`, `:702`, `:803-804`, `:985-987`, `:1019`, `:1568`, `:1696`, `:2290`; `dialog_project_management.xml:26` |
| "FadCam Pro" (title, buttons, update sheet, sidebar) | It names a separate app by the original developer; a comment at `strings.xml:2326-2332` records that this was restored on purpose. A Play-exclusion item (lane 1.4) | `strings.xml:2340-2356`, `:2963-2969` |
| "Built on FadCam by FadSec Lab" (About) and the Ko-fi sheet naming the upstream developer | Credit and attribution (GPL) | `strings_about.xml:49`, `AboutFragment.java:189`, `KoFiSupportBottomSheet.java:96-110` |
| "upgrade to FadCam Pro+" in the streaming note | Names the upstream paid app. Needs an owner decision with the cloud removal (lane 1.4) | `strings.xml:2469` |
| 4 disguise launcher labels "FadCam", "FadCam ++" | Play exclusion (lane 1.1), removed with the aliases | `AndroidManifest.xml:212`, `:220`, `:228`, `:300` |
| Themes and styles named `Widget.FadCam.*`, `Base.Theme.FadCam` | Resource names, not text | `themes.xml` |
| Wake-lock tags, shared-preference names (`FadCamPrefs`, `FadCamCloudPrefs`), HTTP user agents, JS bridge name | Not user-visible; preferences would be orphaned | e.g. `RecordingService.java:304`, `:1148`, `TranscriptionEngine.java:362` |
| `ScanInfoBottomSheet.getReadablePath`, `WatchRecordsFragment:266` | Path handling | `ScanInfoBottomSheet.java:186` |

## Found, not changed: links to the upstream project

- The onboarding agreement links its privacy policy to `github.com/anonfaded/FadCam/blob/master/PRIVACY.md` (`OnboardingHumanFragment.java:180`),
  and the Readme sheet's GitHub row opens `github.com/anonfaded/FadCam` (`ReadmeBottomSheetFragment.java:79`). Joy Creator has its own PRIVACY.md
  (ROADMAP 1.6-1.7), so the onboarding link should move to it once it is hosted. Owner or lead call, not a wording change.
- The streaming server fetches push notices from the upstream repository (`LiveM3U8Server.java:1913`); known from ROADMAP 1.6.

## Not covered

Anything in `app/.../ui/faditor/ai/**`, `vislab/**` and JoyBrush (other lanes). A text with the brand split across `getString` pieces would
not show in a plain search. Nothing was checked on a phone.
