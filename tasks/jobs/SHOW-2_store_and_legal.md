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

The git history still holds personal identifiers (the owner's real name, a personal email, phone serials) from before the commit guard worked; the guard only stops new ones. A full history rewrite of the public repo is owed before the repository or store listing goes public. Rewriting history changes every commit id, so every session and worktree must stop pushing first.

**Owner's ruling 2026-11-14:** run it once, right before the public release (more slips are expected before then), not now. The guard is live and the tools are ready and tested (guard 0a59b919c, tools 0878a3b57; tests on copies passed: re-sync 44/44, end-to-end against a fake GitHub 20/20). Runbook and test logs: the scrub folder on D: dated 11-14; recovery notes beside the private backups on D:.

**How to run it at pre-launch (the session that does it):**
1. Re-read `tasks/GIT_PRACTICE.md` sections 1 and 5 and the runbook on D:. Re-run the tests there if the tools changed since 11-14 (read their output only through its masking filter).
2. If new private words turned up, add them to the private list (path in `git config joyguard.patternsFile`), then run `bash tools/joyguard/make-hashes.sh` and commit `.githooks/guard-hashes`. Clean any current files the guard flags.
3. Get the owner's go, then ask the orchestrator for a **quiet window of about 45 minutes**: every session idle and pushed, no commits, checkouts or builds in any work folder, no `jb-gradle.lock` or `jb-phone*.lock`. The run takes `jb-gradle.lock` itself.
4. `bash tools/joyguard/scrub-run.sh --check` until it says SAFE (it needs 20 minutes without git activity), then `bash tools/joyguard/scrub-run.sh --go`. It stops at the first surprise; before the force-push, nothing public changes. A refused force-push is reported, never retried another way. The report is in the `scrub-work` folder beside the private list.
5. Afterwards: send GitHub Support the request to purge the pull-request ref and cached objects (draft in the orchestrator's notes folder), check for forks, and move any work folders the run listed as skipped with `bash tools/joyguard/resync.sh --apply` once their sessions are done. Prepared in this lane: [PRIVACY.md](../../PRIVACY.md), [TRADEMARK.md](../../TRADEMARK.md), [the closed-test kit](../SHOW2_closed_test_kit.md), [the trademark worksheet](../SHOW2_trademark_search_worksheet.md).

## Week 1 of the launch month (2026-11-14, launch lane)

Prepared, nothing submitted or switched on:

- **Privacy policy as a web page.** `tools/make_site.py` builds a small site into `docs/` (index, privacy, testers, names) from [PRIVACY.md](../../PRIVACY.md), [the tester invite](../SHOW2_tester_invite.md) and [TRADEMARK.md](../../TRADEMARK.md); system fonts only, no outside scripts. Every page shows a yellow **Draft** banner while its source still holds an owner placeholder. **To put it live (owner, one setting):** repository Settings, Pages, deploy from branch `joy-creator`, folder `/docs`; the page is then at `https://<owner>.github.io/<repo>/privacy.html`, and that address is the privacy-policy URL for Play. The contact email is a placeholder until he confirms S4. Note that Pages publishes everything in `docs/`, including the dev file `project-schema.md`. Rebuild after any edit: `python -B tools/make_site.py`.
- **Store listing text.** Title, short and long description in `fastlane/metadata/android/en-US/` (plain text, within Play's limits). The old FadCam feature graphic there still has to be replaced (week 4).
- **Tester invite page:** [SHOW2_tester_invite.md](../SHOW2_tester_invite.md), published as `docs/testers.html`; the older [closed-test kit](../SHOW2_closed_test_kit.md) holds the recruitment messages and the feedback-form questions. Needs the opt-in link and a way to reach the owner.
- **Content rating and data-safety drafts, permissions and foreground-service justifications:** [SHOW2_play_declarations.md](../SHOW2_play_declarations.md), read against the manifest and code. Gaps it found: no "report this AI reply" control (Play's AI-content policy), `specialUse` and battery-optimisation permissions that Play will question, Bluetooth scan and biometric permissions to confirm or drop, and the owner's call on the target audience.

## Week 1, second pass (2026-11-14, store-and-legal session)

All four week-1 items are on `joy-creator`; nothing submitted, nothing switched on. Site stays in `docs/` (one branch, rebuilt from the text files).

- **Privacy policy matched to the code:** `PRIVACY.md` now covers location written into video files, plain-http Remote and bridge, Joybot model lists, the app lock, Bluetooth; contact is the placeholder `studio@joycreator.cc`; the live pages carry no Draft banner (the tester page keeps one until the three links are filled).
- **Store text:** `fastlane/metadata/android/en-US/{title,short_description,full_description}.txt` rewritten in the owner's voice, only from features with device proof in STATE (11, 75 and about 2,560 characters).
- **Tester invite:** `docs/testers.html` has the Google Group, opt-in, install, 14-day and feedback steps; owner slots `[GOOGLE GROUP LINK]`, `[PLAY OPT-IN LINK]`, `[FEEDBACK FORM LINK]`.
- **Data safety and content rating drafts, each answer with file and line:** [tasks/store/DATA_SAFETY_DRAFT.md](../store/DATA_SAFETY_DRAFT.md), [tasks/store/CONTENT_RATING_DRAFT.md](../store/CONTENT_RATING_DRAFT.md).
- **The owner's clicks in plain words (Pages, Google Group, Play Console, domain DNS, decisions):** [tasks/store/OWNER_CLICKS.md](../store/OWNER_CLICKS.md).
- **Found, for the Play build lane (the "report this AI reply" gap closed later 11-14, `959bbd3d9`):** `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `BLUETOOTH_SCAN`, `WRITE_MEDIA_VIDEO` and `specialUse` remain after the play-flavour manifest; `applicationId` was still `com.fadcam` on `joy-creator`; no "report this AI reply" control exists.
