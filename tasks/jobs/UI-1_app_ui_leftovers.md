# UI-1: App UI leftovers from the two retired UI sessions

**Milestone:** M1 (feeds M2 and M3)  ·  **State:** 🔨 in progress since 11-10 (see the 11-13 session note below)  ·  **Owner:** open (Opus for whole-screen redesigns, Sonnet for restyles with these records)  ·  **Checked:** 2026-11-13 (header only; body checked 10-27 against the 10-17 and 10-20 handoffs, with the 11-10 and 11-13 sessions' notes added)

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
- [ ] Restyle: fullscreen camera preview, the Studio classic list leftovers. (Home sidebar done and seen 11-10; Records sidebar layout re-tokened, not seen on the phone.)
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
- **Onboarding (second pass, new build):** reached through Setup, search "onboarding", the "Show the intro" switch, then a relaunch; all three pages are on the Joy Creator look
  (`ui1_after_onboarding_welcome / slide2 / slide3`). After "Let me in" the app opens straight to the lobby, so the switch is back off. Odd: the three checkbox lines on page 3 render in a serif face though the layout asks for the body font; cause not found.
- **Home sidebar restyled** (`ui1_before_home_sidebar` / `ui1_after_home_sidebar`): the orange gradient "Soon" badges are flat zinc pills with dim text; hard-coded #262626 dividers and stock grey subtitles use the Joy Creator line/ink tokens. Same token swap in the Records sidebar layout, the mini-app card and the mini-app sheet. The Records sidebar was **not looked at** on the phone: the Records tab showed the Home recorder with its pill highlighted (a Library-lane oddity, reported, not touched).
- **11-13 session:** fullscreen camera preview moved off the legacy theme (its teal status bar), dock/zzz colours on tokens; landscape Home layout colour tokens (blue-grey text, grey arrows; **structure still old and not seen**, rotating the phone is not allowed); Studio classic list looked at, already on tokens (the yellow dot is the intended "files outside the folder" marker). The serif checkbox lines are IBM Plex Sans, whose capital I has slab serifs: not a bug. Records sidebar seen via Library > Classic Records. Found: the bottom-bar Records pill, pressed while on the Home recorder, showed the Home recorder (reproduced twice, not a Library bug; owner of MainActivity navigation to look). **11-13 phone pass (Note 9, build of the same changes, agent-run):**
  - Watermark in the **saved video file** (ffmpeg frame of a 3 s camera clip) and in a **photo** both read "Captured by Joy Creator - 13/Nov/2026 ..." top-left, no logo
    (`ui1_after_saved_video_watermark_20261113.png`, `ui1_after_photo_watermark_20261113.png`). Style set back to No Watermark afterwards; the preview's "For Real? :(" line now draws `>_<` (garble fixed, `ui1_after_watermark_none_preview_20261113.png`).
  - **Fullscreen preview** seen: dark bars with grey strokes, no teal status bar (`ui1_after_fullscreen_preview_20261113.png`). Still coloured: the green Start and orange Zoom buttons (not touched).
  - **What's New** seen (Settings search "what"): fully Joy Creator look (`ui1_after_whats_new_20261113.png`).
  - **Not done:** screen-recording watermark (Android's screen-capture consent dialog needs a person to tap "Start now"; I cancelled it), revoked-overlay-permission Menu switch (needs a system permission change), a translated screen (needs a language change), the Beta/Pro update sheets, the mode popup, location in a recording (SHELL-13).
- **Remote** looked at: heading reads "ACCESS", server status beside the chip (`ui1_after_remote_access_heading_20261110.png`).
- **Not checked:** any translated screen, What's New, the revoked-permission case of the Menu switch.

## Tablet first run, 11-13 (Tab S8 Ultra, landscape, clean install; roadmap plans item 20)
Shots in `tasks/screens/tablet/`. Agent-run; I did not grant any system permission and did not tick the consent boxes, so the walk stops at the permissions page.
- **Page 1 (welcome):** layout is the phone layout stretched: the title, pitch lines and story hug the bottom-left, "Start creating" is one bar across the whole screen, the top two-thirds is only the glow. The pitch line under the title rotates between two phrases (`intro_line_record` "video recording, even with the screen off", `intro_line_editor` "a real editor"), so the FadCam-era "screen off" line is a rotating line, not a bug in one place; owner's call whether to keep it. The small pencil glyph at the far left is Samsung's floating S Pen shortcut, not the app. Fix made 11-13: on screens 600dp wide or more the words block is a centred 560dp column (`welcome_column_w`); phones unchanged. Compiled and installed on the tablet; **the after shot is owed**: the tablet dropped off Wi-Fi debugging before I could turn the intro back on and look. The lobby on the tablet (`ui1_tablet_lobby_20261113.png`) is stretched the same way (headline strip, buttons across the full width, empty lower third); that is the app-shell lane's screen. Status bar: the green bar the shell lane saw on the intro after Joybot's chat is probably the theme's missing `android:statusBarColor`; added s_surface to `Theme.FadCam.JoyCreator` (compiled, not looked at).
- **Page 2 (permissions):** the four rows stretch the full 2960 px width with the tick circle far right, the "Allow what's missing" bar the same; text is phone-size. Tapping or swiping on this page raised Android's permission prompts one after another (camera, then microphone, ...); I denied each, and the page then says "Please allow the three above to carry on" and does not move on. So a swipe from the empty area does not page forward until the permissions are given (page 3 not seen). Not fixed: needs a max-width column like page 1.
- **Page 3 (consent), folder step, What's New, lobby on the tablet:** not seen (they sit behind permissions and consent ticks, which need a person).
- Still to do for item 20 elsewhere: every wide screen needs a column or a two-pane layout; this card only covers onboarding.

## Files and traps
- New screens follow the benchmark packages `ui/library`, `ui/settingshub`, `ui/trash`: thin fragment, bottom dock,
  small classes, tests, own strings file. Keep the legacy screen reachable ("Classic …") until the new one is proven.
- Launching with `am start -n com.fadcam.beta/com.fadcam.SplashActivity` can bring a JoyBrush drawing to the front: look before tapping.
