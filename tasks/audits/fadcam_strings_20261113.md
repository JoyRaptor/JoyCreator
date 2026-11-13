# Remaining FadCam / FadRec / FadShot / Faditor strings a user can see

Date: 2026-11-13. Worktree `launch-docs-20261113-042476` (branch off `joy-creator`, HEAD e2fe46186).

**Method.** ripgrep over `app/src/main/res/values*/`, `layout`, `menu`, `xml`, `AndroidManifest.xml`, `assets/`, and quoted string literals in `app/src/main`, `studiokit`, `joybrush-android/src` Java/Kotlin. Raw hits were filtered by hand: resource `name=` attributes, `@style/...FadCam...` theme references, package names (`com.fadcam`), class names, comments, prefs keys and log tags were dropped. The credit lines were also dropped from the "to fix" count (marked credit-ok).

**Limits.** A grep sees text, not screens. "Visible" means a string that is shown, or written as a name on the phone; I did not run the app to confirm each one is reachable. Strings assembled from pieces, or strings that arrive from a server, are not seen. Raw totals before filtering: 4063 case-insensitive hits in 99 res files, of which almost all are identifiers (`fadcam_*` string names, `Widget.FadCam.*` styles, 130 in themes.xml). Locale folders were checked separately (below).

## Totals

| Area | Visible hits (after filtering) |
|---|---|
| English `values/` strings shown in UI or as a folder/file name | 21 strings |
| English `values/` search keywords (hidden, matched by Settings search) | 3 |
| Credit / licence (leave) | 2 |
| Layout `android:text` | 1 |
| Manifest launcher-disguise labels | 3 aliases say "FadCam" / "FadCam ++" (plus 1 `0xFF0000`, others generic) |
| `values-*` (locale folders) | see section 4 (corrected: not zero) |
| `menu/`, `xml/` visible text | 0 (file_paths.xml has an `external-files-path` name, not shown) |
| `assets/` shown to a user | web remote page (FadCam Remote), 2 PNG banners, Pro previews |
| Java/Kotlin literals that reach a user (name on phone, clipboard, text overlay) | about 14, plus about 30 that are AI-prompt or JS contract text |

## 1. English `values/` (file:line, text, category)

| file:line | text (trimmed) | category |
|---|---|---|
| strings.xml:292 | Video will be saved to Downloads/FadCam | folder on phone (visible UI) |
| strings.xml:295 | Create a copy in Downloads/FadCam (original file kept) | folder on phone |
| strings.xml:297 | Move file to Downloads/FadCam (saves storage space) | folder on phone |
| strings.xml:299 | Saved to Downloads/FadCam | folder on phone (toast) |
| strings.xml:694 | Video saved to FadCam folder in Downloads | folder on phone (toast) |
| strings.xml:795 | ...debug log HTML file (FADCAM_debug.html) into the public Downloads/FadCam/ folder... | file and folder name on phone |
| strings.xml:796 | ...debug log file will be created in the Downloads/FadCam folder... | folder on phone |
| strings.xml:977 | ...saves it to FadCam/FadShot location. | folder on phone |
| strings.xml:978 | (front camera) ...saves it to FadCam/FadShot location. | folder on phone |
| strings.xml:979 | (screenshot) ...saves it to FadCam/FadShot location... | folder on phone |
| strings.xml:1011 | Captures a screenshot ... saves it in FadCam/FadShot. | folder on phone (accessibility service description, shown in system Settings) |
| strings.xml:1570 | Exported videos ... with the prefix "Faditor_" followed by a timestamp... | file name on phone |
| strings.xml:1698 | Restore %1$d item(s) to the public Downloads/FadCam/ folder? | folder on phone (dialog) |
| strings.xml:2269 | Preview, search, share or delete FADCAM_debug.html | file name on phone |
| strings.xml:2270 | Shows the debug log file (FADCAM_debug.html)... | file name on phone |
| strings.xml:2289 | Public Downloads/FadCam/ folder | folder on phone |
| strings.xml:2340 | `fadcam_pro_header_button` = "Pro" (the surrounding block is the FadCam Pro upsell; comment at 2324-2338 says names were restored to "FadCam Pro" deliberately; the button label itself reads only "Pro") | visible UI, owner decision pending |
| strings.xml:54 | `copyright_info`: 2024-2025 https://fadcam.faded.dev, Licensed under GPL-3.0 | credit-ok (licence line) |
| strings_about.xml:49 | Built on FadCam by FadSec Lab | credit-ok |
| strings_settings_hub.xml:86 | search names: "studio, editor, faditor, ..." | hidden keyword (not shown) |
| strings_settings_hub.xml:100 | search names: "... fadrec" | hidden keyword |
| strings_settings_hub.xml:168 | search names: "classic, old, ... fadcam, mini apps" | hidden keyword |

Note: the grep for the Pro-upsell strings (`FadCam Pro` as visible text) found no remaining visible text with "FadCam Pro" inside the string bodies of English `values/`; only the section comment and the `fadcam_pro_header_button` name remain. Check with a screen look before calling it clean.

## 2. Layouts, menus, xml

| file:line | text | category |
|---|---|---|
| layout/dialog_project_management.xml:26 | `android:text="Projects saved at: ~/Documents/FadRec/Projects"` (also hard-coded English) | folder on phone (visible UI) |
| xml/file_paths.xml:8 | `external-files-path name="fadcam_files" path="FadCam"` | folder name on phone (FileProvider path, not shown but names a real folder) |
| xml/network_security_config.xml:3 | comment "for FadCam" | comment only, ignore |

No `menu/` hits.

## 3. AndroidManifest labels

| line | alias | label | category |
|---|---|---|---|
| 238 | .MainActivity.PakistanIcon | FadCam | disguise icon label (launcher) |
| 246 | .MainActivity.NoorIcon | FadCam ++ | disguise icon label |
| 254 | .MainActivity.BatIcon | FadCam | disguise icon label |
| 326 | .MainActivity.MinimalIcon | FadCam | disguise icon label |

Other aliases are intentionally generic (Camera, Notes, Calculator, Clock, Weather, FootballX 2024, ClutchX, JF-17: ThunderX, `$ ~/r00t`, `0xFF0000`). The application `android:label` is `@string/app_name` (fine). Whether the alias labels stay is a question for the owner: they are disguise icons, so a FadCam name on four of them is still the old brand in the launcher.

## 4. Locale folders (CORRECTED by the launch lane 2026-11-13)

The scan's first count of '0 in every file' was wrong (its regex matched nothing). Re-counted with a script: `<string>` values containing FadCam / FadRec / FadShot / Faditor, per locale `strings.xml`:

| locale | strings |
|---|---|
| main | 34 |
| main | 34 |
| main | 27 |
| main | 34 |
| main | 38 |
| main | 34 |
| main | 34 |
| main | 27 |
| main | 34 |
| main | 34 |
| main | 34 |
| main | 55 |

Most are the `Downloads/FadCam` folder wording and the copyright line (compare English `values/`, section 1); the same fix as English (L16, L17) covers them. English `values/` is the source to fix first.

## 5. Assets

| path | text | category |
|---|---|---|
| assets/web/index.html:9,46,5378 | title and header "FadCam Remote", "FadCam Remote Locked" | visible UI if the remote web page is served to a browser |
| assets/web/index.html:3362,3372 | "Start streaming from the Remote tab in FadCam app." | visible UI (web) |
| assets/web/js/services/AuthService.js (12 hits), js/viewmodels/DashboardViewModel.js (14), css/style.css (3), js/app.js (2), js/config.js (1), README.md (3) | mostly identifiers, some labels | check by eye, not triaged |
| assets/Welcome_to_FadCam_PNG.png, assets/fadcam_beta_release_banner.png, assets/FadCamPro/*.png | image files named FadCam; contents not inspected | check whether shown |
| assets/changelog/main.md | 0 hits | clean |
| assets/faditor/*, assets/gl_transitions/FadcamGlitch.glsl | internal names | code identifiers, ignore (but `GLTransitionCatalog.java:114` entry id "FadcamGlitch", display name "Glitch": fine) |
| CHANGELOG.md:6 | points to docs/FADCAM_CHANGELOG.md | credit-ok |

## 6. Java/Kotlin literals

Reaches the user (a file or folder name on the phone, clipboard text, watermark text, About):

| file:line | literal | category |
|---|---|---|
| data/Constants.java:440 | `RECORDING_DIRECTORY = "FadCam"` | folder on phone |
| data/Constants.java:451,454 | `RECORDING_SUBDIR_SHOT = "FadShot"`, `..._FADREC = "FadRec"` | folder on phone |
| data/Constants.java:275 | `RECORDING_DIRECTORY_FADREC = "FadRec"` | folder on phone |
| data/Constants.java:463,465 | `"Faditor_Std_"`, `"Faditor_Merge_"` | file name prefix on phone |
| ui/faditor/export/ExportManager.java:7866 | `"Faditor_" + timestamp` | export file name on phone |
| fadrec/ui/AnnotationService.java:705,935 | `"FadRec_"` project names | file name on phone |
| fadrec/ui/annotation/ProjectFileManager.java:75,421,436,451 | `Documents/FadRec`, `"FadRec_" + timestamp` | folder and file names on phone |
| service/RecordsDeletionService.java:725 | `"FadCam_" + currentTimeMillis + ".mp4"` | file name on phone |
| ui/miniapps/QRScannerActivity.java:419 | `"FadCam_MiniApps_QRScanner_" + ts + ".jpg"` | file name on phone |
| forensics/service/DigitalForensicsEventRecorder.java:428 | `FadCam/Forensics/Snapshots` | folder on phone |
| ui/faditor/ai/BRollBucket.java:40 | Pictures/`FadCam/assets` | folder on phone |
| ui/faditor/text/FontLibrary.java:251 | Pictures/`FadCam/fonts` | folder on phone |
| utils/RealtimeMediaInvalidationCoordinator.java:120 | `externalRoot/FadCam` | folder on phone |
| ui/WatchRecordsFragment.java:266 | `"FadCam"` (folder) | folder on phone |
| ui/AboutFragment.java:181 | `"Based on FadCam by FadSec Lab"` (hard-coded; credit text) | credit-ok |
| ui/faditor/FaditorEditorActivity.java:24688, 48573 | clipboard labels "Faditor caption style", "Faditor slide prompt" | label only visible in some clipboard UIs |
| ui/faditor/SlideCodeBottomSheet.java:148 | clipboard label "Faditor slide HTML" | same |
| ui/faditor/transcript/TranscriptionEngine.java:362 | HTTP `User-Agent: FadCam/1.0` | sent to a server, not on screen |
| ui/OnboardingHumanFragment.java:180 | link to github.com/anonfaded/FadCam PRIVACY.md | opens upstream's privacy page (visible destination) |

Watermark text: `opengl/GLWatermarkRenderer.java`, `utils/PhotoStorageHelper.java` and `ui/WatermarkSettingsFragment.java` use the token `<FADCAM_ICON>` / `[FADCAM_ICON]`, and already replace it with "Joy Creator". No stray brand text there.

AI / JS contract text (the model is told the editor is "Faditor"; the word is inside prompts the user may paste into another AI, so it is visible when copied): `ui/faditor/slides/SlideContract.java` lines 44, 68, 72, 103, 122, 154, 162-163, 175, 196, 296, 346 (the prompt at 122 begins "I'm using a mobile video editor (Faditor)..."), `SlideActions.java:60`, `SlideDesigns.java:69`. The JS API `Faditor.register/seek` is code and must stay unless the runtime JS is renamed in step.

Not visible, internal only (listed so nobody chases them): prefs keys `fadrec_prefs`, `fadrec_ui`, `faditor_color_picker`, wake-lock tag `FadCam:DualCamRecording`, `Constants.MODE_FADCAM/FADREC`, scanner checks `startsWith("Faditor_")` in FastFileScanner.java:674 and RecordsFragment.java:5941 (these must change together with the file name prefix, or old exports vanish from the Library).

## What to do first (suggestion only)

1. Folder names are the big visible item: `Downloads/FadCam`, `FadCam/FadShot`, `Documents/FadRec`, `Pictures/FadCam/*` appear in 16 UI strings and about 12 code literals. Changing a real folder name needs migration or a dual-read period, because existing recordings live there.
2. The `dialog_project_management.xml:26` line is both hard-coded and FadRec-branded; one edit fixes both audits.
3. Four launcher disguise labels still read FadCam.
