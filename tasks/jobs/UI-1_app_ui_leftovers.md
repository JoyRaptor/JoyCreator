# UI-1: App UI leftovers from the two retired UI sessions

**Milestone:** M1 (feeds M2 and M3)  ·  **State:** ⬜  ·  **Owner:** open (Opus for whole-screen redesigns, Sonnet for restyles with these records)  ·  **Checked:** 2026-10-27 against the 10-17 and 10-20 handoffs

## Why it matters
The screens a user passes through between the lobby and the wings still look and feel like FadCam in
places. The lobby redesign (M2) needs every screen behind it to be finished first.

## Done when
- [ ] Every screen in the list below matches the design records, has before and after shots in
      `tasks/screens/`, and is verified on the Note 9.
- [ ] No user-visible "FadCam" or "FadRec" text is left outside the credits and licence.

## Where it stands
Done and verified 10-16 to 10-20: Library v2, Settings hub, Recently Deleted v2, New-project flow, the
Joybot switch (replaces AvatarToggleView), slanted chips, the Home recorder ("Bits"), Remote / Server Room,
Smart Detection, the new About, 36 renamed strings, lobby placeholders. Handoffs:
[10-17](../HANDOFF_STUDIO_UI_20261017.md), [10-20](../HANDOFF_STUDIO_UI_20261020.md).
**The design records are now in git** (landed 10-27; they were only on the main folder's disk before):
`tasks/design/UI-JoyCreator-grand-design.html`, `UI-Studio-final.html`, `UI-The-Marquee_lobby_and-first-run-intro.html`,
`UI-Five-front-doors-options.html`, `UI-The-swatch-room_pill.html`, `UI-The Art Order.html`.

## What is left
- [ ] Restyle: Home sidebar, Records sidebar, fullscreen camera preview, the Studio classic list leftovers.
- [ ] Eyeball on the phone: the mode popup (looked teal on an old build), What's New, the Beta/Pro update sheets.
- [x] Legacy themes: FadCam Pro, Cloud Account and WebView screens no longer exist (S19 cut; the only WebView is a headless slide renderer); What's New was already on the Joy Creator theme; Onboarding moved to it 11-10 (theme, human slide colours, page dots). Not yet looked at on a phone.
- [x] Burned-in watermark: the FadCam/Rec logo pictures are replaced by the text "Joy Creator" in the video, photo and screen-recording drawers and the settings preview (11-10, compiled; no phone recording yet).
- [x] Part A text sweep (English, Remote headings, 12 translations brand-only) is on `joy-creator` since `5885d6499`; the
      recovered backup `8391bad07` was re-checked 11-04 and adds nothing (every line it adds is already in, or is cloud text S19 removed).
      11-10: the 20 keys that named FadRec/FadShot are now Screen/Photo or Joy Creator in all 12 languages (my own word choices; `fr` and `ps` still have English sentences from before; native review owed).
- [ ] Home recorder: landscape layout still old. (11-10: the Menu row's Joybot switch ON after relaunch is the saved "floating controls" setting restoring the overlay on purpose; fixed so it shows OFF when the overlay permission is gone.)
- [x] Remote: heading now "Access", server status caption sits beside the chip, toasts all in strings (checked in source 11-10; already done by the part A sweep).
- [ ] Retire `AvatarToggleView` and its eye-colour preference once the Joybot switch is proven everywhere.
- [ ] Owner calls (S9): About "View source" link, the "Camera" label, Remote ring colours, Smart Detection chip colours.
- [ ] Unused now (remove only with S10): `forensics_board_doodles.xml`, `forensics_doodle_pattern.png`,
      `forensics_empty_icon_bg.xml`, `fadcam_server_room_bg_rounded.webp`.

## Phone check 11-10 (Note 9, debug build of c588dd300, agent-run)
- **Watermark:** settings preview shows "Captured by Joy Creator - <time>"; a real back-camera recording showed the same line burned into the live view
  (`screens/ui1_after_camera_recording_watermark_20261110.png`, `ui1_after_watermark_preview_20261110.png`). Setting put back to No Watermark. Not checked: the saved video file itself, a photo, a screen recording.
- **Menu switch (Home recorder, Screen tab):** ON after relaunch with the overlay tab visible at the edge, which is the saved setting restoring the overlay
  (`ui1_after_home_recorder_menu_20261110.png`). The revoked-permission case (should show OFF) was not tried.
- **Found and fixed:** the Watermark preview's funny line showed garbled text (`watermark_preview_funny` was double-encoded); text fixed, not re-looked at on the phone.
- **Not checked:** Onboarding (its activity is not startable without clearing data, which is not allowed), any translated screen, What's New.
- No before shots: the phone was on another session's screen when I started.

## Files and traps
- New screens follow the benchmark packages `ui/library`, `ui/settingshub`, `ui/trash`: thin fragment, bottom dock,
  small classes, tests, own strings file. Keep the legacy screen reachable ("Classic …") until the new one is proven.
- Launching with `am start -n com.fadcam.beta/com.fadcam.SplashActivity` can bring a JoyBrush drawing to the front: look before tapping.
