# DOOR-3: First run: intro, permissions, folder, a demo project

**Milestone:** M2  ·  **State:** ⬜  ·  **Owner:** open  ·  **Checked:** 2026-10-27 against STATE §8 and the legacy screens audit

## Why it matters
A stranger's first two minutes. Today the intro still carries FadCam's shape: permissions all up front, and a
"verify you are a human" screen written for a covert recorder.

## Done when
- [ ] The intro uses the copy the owner approved on 9-09 (he must paste it: S5).
- [ ] Permissions are asked where they are first needed, not all at the start.
- [ ] The "verify you are a human" screen is rewritten or removed (it fails Play review as it stands).
- [ ] The Joy Creator folder step (required since 10-14) sits naturally in the flow.
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
