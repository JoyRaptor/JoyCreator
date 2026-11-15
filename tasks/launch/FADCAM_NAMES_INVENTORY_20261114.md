# FadCam names on disk: every writer and reader (SHELL-44 / L16, 2026-11-14)

Read-only inventory of `joy-creator` at 0132a4610, made by a search agent and spot-checked by the Play build lane
(Log.java:198, ProjectFileManager.java:74-76, BRollBucket.java:39-41, RecordsDeletionService.java:725-728,
TrashManager.java:1028-1040, ExportManager.java:7880 confirmed). Design: [FREE-2](../jobs/FREE-2_names_and_ids.md).

`AppData` = `getExternalFilesDir(null)/FadCam` (`RecordingStoragePaths.java:131-135`), seen in a file manager as
`Android/data/<package>/files/FadCam`. "Folder chosen" = the Joy Creator folder is the recorder's custom storage
(`RecordingStoragePaths.java:322`); turning "Recordings here" off (`JoyFolderSetupActivity.java:373-383`) returns to
the internal mode and its old names.

## A. Visible writes that happen even with the folder chosen

| Where | Path or name | Feature |
|---|---|---|
| `Log.java:198` | `Download/FadCam/JoyCreator_debug.html` (fallback `Android/data/.../files/Download/`, :208) | debug log |
| `fadrec/ui/annotation/ProjectFileManager.java:74-76, 421, 436, 451` | `Documents/FadRec/Projects/FadRec_<ts>/project.fadrec` | screen-drawing projects |
| `fadrec/ui/AnnotationService.java:705, 935` | project names `FadRec_<yyyyMMdd_HHmmss>` | same |
| `ui/faditor/ai/BRollBucket.java:39-41, 136, 169, 194` | `Pictures/FadCam/assets/`, `fonts/`, `.broll_tags.json` | AI B-roll |
| `forensics/service/DigitalForensicsEventRecorder.java:428` | `AppData/Forensics/Snapshots/...` | forensics |
| `ui/miniapps/QRScannerActivity.java:419, 553` | `AppData/MiniApps/QRScanner/<ts>/FadCam_MiniApps_QRScanner_<ts>.jpg` | QR scanner |
| `service/RecordsDeletionService.java:725-728` | `Download/FadCam/<name>` (`FadCam_<ms>.mp4` without a name) | "save to gallery" |
| `service/FileOperationManager.java:125-127` | `Download/FadCam/` | Forensics copy/move |
| `ui/WatchRecordsFragment.java:264-266` | `Download/FadCam/` | Watch "save video" |
| `utils/TrashManager.java:1028-1040` (offered :995) | `Download/FadCam/` or `AppData/` | Trash restore |
| `ExportManager.java:3611, 3871` | `Android/data/.../faditor_export_errors/export_error_*`, `export_trace_*` | export logs |
| `ExportManager.java:7880` | `Faditor_<ts>` when an export has no name (the dialog normally fills `JoyCreator_` or the project name) | export |

Fallbacks to the old names while the folder is chosen (folder unreachable or a write failed):
`RecordingService.java:5376-5392` (→ `AppData/Camera/<Back|Front>/FadCam_<ts>[_partN].mp4`),
`ScreenRecordingService.java:1381, 1437-1449` (`AppData/Screen/FadRec_<ts>.mp4`, `_partN`, `_webcam`),
`PhotoStorageHelper.java:97-101, 193-207` (`AppData/FadShot/<Back|Selfie|FadRec>/FadShot_*`),
`BatchMediaActionService.java:501-508` (`AppData/Faditor/<Converted|Merge>/Faditor_Std_* | Faditor_Merge_*`),
`ExportManager.java:7979-7983` (`AppData/Faditor/`). Probably dead: `RecordingService.java:5402-5421`.

## B. Visible writes only without the folder (internal mode, or another picked folder)

Internal: `RecordingService.java:6293, 5443`; `ScreenRecordingService.java:1381`; `DualCameraRecordingService.java:1530`
(`DualCam_*`); `PhotoStorageHelper.java:117`; `BatchMediaActionService.java:501`; `ExportManager.java:7919`.
Another picked folder (old subfolders `Camera/Back`, `Screen`, `FadShot/<src>`, `Faditor/...`):
`RecordingService.java:6665, 6335`; `ScreenRecordingService.java:620, 1411, 1035-1039`; `PhotoStorageHelper.java:167`;
`BatchMediaActionService.java:476`; `ExportManager.java:8023-8027`. Folder-name helpers `RecordingStoragePaths.java:61, 65, 97`.

## C. Readers that must keep recognising the old names

`Constants.java:275-276, 440, 447, 451, 454, 461-465`; `FastFileScanner.java:87-90, 117, 154, 174, 368, 380, 382,
671-676, 685, 693-695, 740-741` (gap: inside the folder, :515-533, legacy `FadCam_*` names are skipped);
`RecordsFragment.java:2619, 2630, 2632, 2885, 2887, 4082-4091, 4179-4235, 4453, 5794-5858, 5938-5965, 6002-6007`;
`Utils.java:93-99` (timestamp from `FadCam_`); `LibraryCapture.java:62`; `OutsideFiles.java:104`;
`FragmentedMp4Remuxer.java:89`; `RealtimeMediaInvalidationCoordinator.java:120`; `WatchRecordsFragment.java:142-143`;
`HomeFragment.java:7638`; `VideoSourceBottomSheet.java:286-298, 383-388`; `ScanInfoBottomSheet.java:186`;
`TrashManager.java:1028-1040`; `FontLibrary.java:250-251`; `BRollBucket.java:67-79`; `ForensicsGalleryFragment.java:539`;
`ProjectSelectionDialogActivity.java:77`.

## D. Inside the app only (not visible; left alone)

Preference files and keys (`FadCamPrefs`, `FadRecProjects`, `fadrec_*`, `faditor_*`, `pref_faditor_*`), private
folders (`faditor/projects`, `faditor/remuxed`, `faditor/chunks`, `faditor_export`, `faditor_audio`, ...), wakelock
tags, notification channel IDs. Renaming these would only risk losing settings and projects inside the app.

Strings that show the old folders: `res/values/strings.xml:795, 977-979, 1011, 1698, 2289` and the same keys in the
locale files. joyfolder (studiokit) and joybrush-android write no FadCam names.
