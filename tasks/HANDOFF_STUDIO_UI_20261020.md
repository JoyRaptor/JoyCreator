# Studio + app UI lead: progress 2026-10-20 (continues HANDOFF_STUDIO_UI_20261017.md)

## Done and verified on the Note 9 (after-shots in tasks/screens/after/*_v2.png)
- Home recorder ("Bits"): flat zinc cards, slanted Camera/Screen/Mic chips, slanted Start/Pause/etc buttons (ui/home: HomeRecSkin/Button/StartButton/Tile), solid-cyan dock (shared by all legacy tabs).
- Remote / Server Room: ON AIR header chip, sectioned settings, token colours (strings_remote.xml, styles_remote.xml).
- Smart Detection: slanted filter chips (forensics/ui/view/SlantChip), brand-free empty state.
- About: new ui/about package (AboutJoyFragment + small classes, fragment_about_joy.xml, strings_about.xml). Old AboutFragment kept behind a "Classic About" link.
- 36 user-visible FadCam/FadRec/Faditor strings renamed (Joy Creator / Screen / Studio). Left on purpose: credit + GPL text, real folder/file names (Downloads/FadCam, "Faditor_" prefix), FadCam Pro strings, Settings search aliases.
- Lobby: LobbyHeroPlaceholder for rooms/projects with no picture.
Commits: d5710ef80, 2996cc2a6 (local; push is the owner's call).

## Open
- Owner decisions: About "View Source Code" still points at the original author's GitHub (set AboutCredits.SOURCE_URL once there is a public fork repo); wording of About tagline/wing lines; Camera mode label ("Joy Creator" in Records filter + settings tabs, owner said "Camera"); Remote ring colours (StorageProgressRingView is shared); Smart Detection chips all cyan vs per-type colour.
- Watermark burned into videos still draws "FadCam"/"FadRec" (WatermarkSettingsFragment ~1005); preview label now says "Screen".
- Translations (values-xx) still carry the old names.
- Home recorder: landscape layout still old; "Menu" row JoyBot switch showed ON with an overlay tab after relaunch (check whether that is saved state); Material background-override warning risk was fine on the Note 9.
- Remote: "REMOTE SECURITY" sub-label repeats "SECURITY"; "SERVER STATUS" caption oddly beside the chip; hardcoded English toasts in RemoteFragment.
- Unused now (delete only with the owner's OK): forensics_board_doodles.xml, forensics_doodle_pattern.png, forensics_empty_icon_bg.xml, fadcam_server_room_bg_rounded.webp.
- Still from the old list: fullscreen preview, Home sidebar, Records sidebar, Studio classic list leftovers, retire AvatarToggleView, Studio leftovers, mode popup + What's New + update sheets not yet eyeballed (popup was teal on the stale build; recheck), New project pill colour.
- Note: never launch with `adb shell monkey`; launching with `am start -n com.fadcam.beta/com.fadcam.SplashActivity` can bring a Joy Brush drawing task to the front: check before tapping (a stray tap left a dot on a drawing; undone).
