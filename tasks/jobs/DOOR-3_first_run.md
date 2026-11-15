# DOOR-3: First run: intro, permissions, folder, a demo project

**Milestone:** M2  ·  **State:** ⬜  ·  **Owner:** open  ·  **Checked:** 2026-10-27 against STATE §8 and the legacy screens audit

## Why it matters
A stranger's first two minutes. Today the intro still carries FadCam's shape: permissions all up front, and a
"verify you are a human" screen written for a covert recorder.

## Done when
- [ ] The intro uses the copy the owner approved on 9-09 (he must paste it: S5).
- [x] Permissions are asked where they are first needed, not all at the start. 11-14 (SHELL-36): the intro asks for nothing; Capture asks for the camera and microphone on the way in and at Start (`ui/permission/CaptureAccess`); other rooms already asked at use. Fold + Note 9 shots `tasks/screens/shell-20261113/`.
- [x] The "verify you are a human" screen is rewritten or removed (it fails Play review as it stands). 11-13 (SHELL-35): three plain promises, no forced ticks, no FadCam privacy link; its Joybot key buttons wired. Note 9 shots `tasks/screens/shell-20261113/after_11..13_*`. English only; our own privacy link waits on PRIVACY.md being published.
- [x] The Joy Creator folder step (required since 10-14) sits naturally in the flow. 11-13 shown on Android 11+ (`<queries>`), 11-14 restyled and opened straight after the intro with no lobby flash (SHELL-37; Fold). Optional Joybot hello waits on BOT-5.
- [ ] A small demo project you can press play on (the design record lists its assets).
- [ ] Onboarding and What's New use the Joy Creator theme (today: legacy MaterialComponents themes).
- [ ] Phone-verified; STATE §8 updated.

## Where it stands
- `OnboardingActivity` (AppIntro host, legacy theme), `OnboardingWelcomeFragment` (new look),
  `OnboardingHumanFragment` (mixed), `OnboardingPermissionsFragment` (old look): see
  [legacy_screens_audit.md](../audits/legacy_screens_audit.md) §1.
- Design record: `tasks/design/UI-The-Marquee_lobby_and-first-run-intro.html`.

## What is left
- [ ] S5 from the owner (the intro text).
- [ ] Design together with DOOR-2 (same records, same picks page).
- [ ] Build, phone-verify on a fresh install (on the Note 9 as a second app ID, never by clearing data).
