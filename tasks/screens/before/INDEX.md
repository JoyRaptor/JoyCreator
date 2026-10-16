# BEFORE screenshots: index

Device: Samsung Note 9 (serial the Note 9), app com.fadcam.beta, screen 1080x2220.
Captured on 2026-10-16 with `screencap`. Nothing was installed, deleted, or committed.

"Viewed" means I opened the PNG and checked what it shows. Entries marked "not viewed" were captured
but not checked by eye, so treat their labels as provisional.

## Incidents during capture (read first)

1. Text drawer and Visualizer drawer both commit a change to the project when closed.
   - Opening the Text drawer and closing it added an "Enter text" clip to the Restore video test timeline.
   - Opening and closing the Visualizer drawer added a second VIZ track.
   - Undo counter moved 10 -> 11 -> 12. I pressed Undo twice (12 -> 10). The timeline is visually back to the original
     (viewed in s11). Redo stack now holds 2 entries instead of 1. This is not a content change.
   - Frames s04 to s06 and s09 to s10 show the state before the undo.
2. Closing the editor showed "Close & Save" (the app's normal close, nothing deleted). I took it.
3. Once the app was in the legacy FadCam record screen, a few taps on the bottom bar were ambiguous.
   Nothing was started: the Start Screen Recording button was never tapped.

## Files

| File | Screen | How reached | Status |
|---|---|---|---|
| s01_studio_editor_text_drawer_selected.png | Studio editor, project "Restore video test", Text drawer open with "hello 2" selected | App opened directly into the editor | Viewed |
| s02_studio_editor_timeline_restore_video_test.png | Studio editor, clean timeline | Back from Text drawer | Not viewed |
| s03_studio_editor_timeline_toolbar_scrolled.png | Studio editor timeline, bottom toolbar scrolled (Fix audio, Sprites, Visualizer, Captions, Split) | Toolbar swiped | Viewed |
| s04_studio_editor_text_drawer.png | Studio editor, Text drawer (Enter text, Standard/Text, B/I/U) | Tapped Text in toolbar | Not viewed. Closing this drawer adds an "Enter text" clip |
| s05_studio_editor_text_drawer_reopened.png | Studio editor, Text drawer again | Drawer reopened by a stray tap | Not viewed |
| s06_studio_editor_timeline_after_text_drawer.png | Studio editor timeline after closing Text drawer (state carries the extra text clip) | Back | Not viewed |
| s07_studio_editor_captions_drawer.png | Studio editor, Captions drawer (Style, Font, Highlight, Motion, Timing) | Tapped Captions in toolbar | Not viewed |
| s08_studio_editor_sprites_drawer.png | Studio editor, Sprites drawer (cell list, winkclip1) | Scrolled toolbar, tapped Sprites | Not viewed |
| s09_studio_editor_visualizer_drawer.png | Studio editor, Visualizer drawer | Tapped Visualizer | Not viewed. Closing it adds a second VIZ track |
| s10_studio_editor_timeline_with_stray_edits_UNDONE.png | Studio editor timeline showing the two stray edits (Enter text clip, second VIZ track) | After s09 | Viewed. Captured before the undo, despite the file name |
| s11_studio_editor_timeline_after_restore.png | Studio editor timeline after two undos (undo count 10) | After s10 | Viewed. Matches the original |
| s12_lobby_home_studio_room.png | Lobby, STUDIO room (Last project card, Library row, Recording / Project / Character / Import buttons, bottom nav) | Relaunch and Close & Save | Viewed |
| s13_lobby_room_capture.png | Lobby, CAPTURE room | Tapped CAPTURE title | Not viewed |
| s14_lobby_room_sprite_lab.png | Lobby, SPRITE LAB room | Tapped SPRITE LAB title | Not viewed |
| s15_lobby_room_avatar.png | Lobby, AVATAR room | Tapped AVATAR title | Not viewed |
| s16_lobby_room_viz_lab.png | Lobby, VIZ LAB room | Tapped VIZ LAB title | Not viewed |
| s17_lobby_room_joy_brush.png | Lobby, JOY BRUSH room | Tapped JOY BRUSH title | Not viewed |
| s18_nav_finder.png | Legacy "Smart Detection" screen (Finder tab) | Lobby nav Finder | Viewed. Same image as s22 |
| s19_nav_remote.png | Lobby nav Remote | Lobby nav Remote | Not viewed |
| s20_nav_bits.png | Lobby nav Bits | Lobby nav Bits | Not viewed |
| s21_nav_packs.png | Lobby nav Packs | Lobby nav Packs | Not viewed |
| s22_nav_setup.png | Same image as s18 (the Setup tap did not change the screen) | Lobby nav Setup | Viewed. Duplicate |
| s23_probe_current.png | Same as s18 (Smart Detection) | Probe | Viewed. Duplicate |
| s24_bar_home.png | Lobby, STUDIO room (Home tab of legacy bar) | Home tab | Viewed |
| s25_bar_folder.png | Legacy bar, Folder tab | Legacy bar | Not viewed |
| s26_bar_person.png | Legacy bar, Person tab | Legacy bar | Not viewed |
| s27_bar_edit.png | Legacy bar, Edit tab | Legacy bar | Not viewed |
| s28_bar_settings.png | Legacy bar, Settings tab | Legacy bar | Not viewed |
| s29_lobby_nav_remote_try2.png | Lobby nav Remote, second try | Lobby nav | Not viewed |
| s30_lobby_nav_bits_try2.png | Lobby nav Bits, second try | Lobby nav | Not viewed |
| s31_lobby_nav_packs_try2.png | Legacy record screen (Camera / Screen / Mic tabs, Start Screen Recording button, "Settings" tab highlighted) | Lobby nav Packs | Viewed |
| s32_lobby_nav_setup_try2.png | Same as s31 | Lobby nav Setup | Duplicate |
| s33_settings_tab_legacy.png | Legacy record screen | Legacy Settings tab | Viewed |
| s34_side_drawer_menu.png | "Home Options" drawer (Profiles, Preview Area, Preview quick actions, Mini Apps) | Hamburger icon top-left | Viewed |
| s35_lobby_after_drawer.png | Still the legacy record screen (Home tap did not reach the lobby) | Back, then Home tap | Not viewed |
| s36_library_list.png | Legacy record screen (Library tap did not open a list) | Tap on LIBRARY chevron | Viewed |
| s37_after_back_from_record.png | Not verified | Back | Not viewed |
| s38_library_try2.png | Not verified | Library chevron, second try | Not viewed |

Pre-existing, not captured by me: `color_picker_hex_wrap.png` was already in this folder.

## Not reached

- Lobby Library list (LIBRARY row chevron) with filter chips: not reached.
- Record row options: not reached.
- Trash, About, What's New: not reached.
- Mode popup: not reached.
- Fullscreen preview of a clip: not reached.
- Onboarding: not attempted (it needs an app data reset, which was ruled out).
- Studio project list: not reached.
- Settings sub-screens (each settings row): not reached. The legacy Settings tab showed the record screen, not the settings hub.
- Studio editor drawers Fix audio, Beats, Select, Volume, FX, Adjust, Move, Opacity, Align, Transitions, Canvas: not opened.
- Lobby nav tabs (Remote, Bits, Packs, Setup) have not been identified by eye.

## Verified before shots (lead, 2026-10-16, viewed by eye)
- v_finder_smart_detection.png: Finder = legacy "Smart Detection" screen (FadCam).
- v_remote_server_room.png: Remote = legacy "Server Room" (stream/server stats).
- v_bits_home_recorder.png: Bits = legacy screen recorder home (Camera/Screen/Mic tabs, Start Screen Recording).
- v_packs_settings.png and v_setup_settings.png: Packs and Setup both = the legacy Settings list.
- The Library "before" is s36 (the legacy Records screen).
