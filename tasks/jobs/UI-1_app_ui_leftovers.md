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
- [ ] Five screens still on legacy themes: Onboarding, What's New, FadCam Pro, Cloud Account, WebView.
- [ ] The burned-in watermark still draws "FadCam"/"FadRec" (`WatermarkSettingsFragment` around line 1005).
- [ ] Translations (`values-xx`) still carry the old names.
- [ ] Home recorder: landscape layout still old; the Menu row's Joybot switch showed ON after relaunch (saved state?).
- [ ] Remote: "REMOTE SECURITY" repeats "SECURITY"; "SERVER STATUS" caption placement; hard-coded English toasts.
- [ ] Retire `AvatarToggleView` and its eye-colour preference once the Joybot switch is proven everywhere.
- [ ] Owner calls (S9): About "View source" link, the "Camera" label, Remote ring colours, Smart Detection chip colours.
- [ ] Unused now (remove only with S10): `forensics_board_doodles.xml`, `forensics_doodle_pattern.png`,
      `forensics_empty_icon_bg.xml`, `fadcam_server_room_bg_rounded.webp`.

## Files and traps
- New screens follow the benchmark packages `ui/library`, `ui/settingshub`, `ui/trash`: thin fragment, bottom dock,
  small classes, tests, own strings file. Keep the legacy screen reachable ("Classic …") until the new one is proven.
- Launching with `am start -n com.fadcam.beta/com.fadcam.SplashActivity` can bring a JoyBrush drawing to the front: look before tapping.
