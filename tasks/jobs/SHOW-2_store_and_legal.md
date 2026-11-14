# SHOW-2: Store and legal

**Milestone:** M4  ·  **State:** ⛔ waiting on S4 and S6  ·  **Owner:** owner + lead  ·  **Checked:** 2026-10-27

## Done when
- [ ] Google Play developer account, a public developer email, 12 closed testers, and the 14-day closed test running (S6; calendar time only, can start before the app is finished).
- [ ] README.md and PRIVACY.md final (drafted 10-14 from a code audit; blanks: contact email, publish date: S4).
- [ ] The privacy policy hosted at a public URL.
- [ ] The GitHub repo confirmed private (or deliberately public with the right licence notice).
- [ ] The store listing written from STATE (only 🟢 features are promoted).
- [ ] The bundled What's New text (`assets/changelog/main.md`) is Joy Creator's, not FadCam's.

## Where it stands
README and PRIVACY drafts are in the repo root. CHANGELOG.md is Joy Creator's since 10-14.

## Owed before launch: a full history scrub of the public repository (noted 2026-11-13)

The git history still holds personal identifiers (the owner's real name, a personal email, phone serials) from before the commit guard worked; the guard only stops new ones. A full history rewrite of the public repo is owed before the repository or store listing goes public. The plan and runbook are kept outside the repo (a folder on the D: drive named for the scrub, dated 11-14); no rewrite is scheduled yet. Rewriting history changes every commit id, so every session and worktree must stop pushing first. Prepared in this lane: [PRIVACY.md](../../PRIVACY.md), [TRADEMARK.md](../../TRADEMARK.md), [the closed-test kit](../SHOW2_closed_test_kit.md), [the trademark worksheet](../SHOW2_trademark_search_worksheet.md).

## Week 1 of the launch month (2026-11-14, launch lane)

Prepared, nothing submitted or switched on:

- **Privacy policy as a web page.** `tools/make_site.py` builds a small site into `docs/` (index, privacy, testers, names) from [PRIVACY.md](../../PRIVACY.md), [the tester invite](../SHOW2_tester_invite.md) and [TRADEMARK.md](../../TRADEMARK.md); system fonts only, no outside scripts. Every page shows a yellow **Draft** banner while its source still holds an owner placeholder. **To put it live (owner, one setting):** repository Settings, Pages, deploy from branch `joy-creator`, folder `/docs`; the page is then at `https://<owner>.github.io/<repo>/privacy.html`, and that address is the privacy-policy URL for Play. The contact email is a placeholder until he confirms S4. Note that Pages publishes everything in `docs/`, including the dev file `project-schema.md`. Rebuild after any edit: `python -B tools/make_site.py`.
- **Store listing text.** Title, short and long description in `fastlane/metadata/android/en-US/` (plain text, within Play's limits). The old FadCam feature graphic there still has to be replaced (week 4).
- **Tester invite page:** [SHOW2_tester_invite.md](../SHOW2_tester_invite.md), published as `docs/testers.html`; the older [closed-test kit](../SHOW2_closed_test_kit.md) holds the recruitment messages and the feedback-form questions. Needs the opt-in link and a way to reach the owner.
- **Content rating and data-safety drafts, permissions and foreground-service justifications:** [SHOW2_play_declarations.md](../SHOW2_play_declarations.md), read against the manifest and code. Gaps it found: no "report this AI reply" control (Play's AI-content policy), `specialUse` and battery-optimisation permissions that Play will question, Bluetooth scan and biometric permissions to confirm or drop, and the owner's call on the target audience.
