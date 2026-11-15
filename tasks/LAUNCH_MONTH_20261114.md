# Launch month: on the Play Store by mid-December 2026

Written 2026-11-14 by the orchestrator, from the owner's order of 11-14: within this month, the app done,
a sizzle video, a website and the app on the Play Store. Claude usage is limited to one month too. **This plan
outranks the M1 to M4 order in [ROADMAP.md](ROADMAP.md) until launch.** Nothing is deleted: parked work keeps
its card and returns after launch.

## 0. Owner rulings, 11-14 evening (these override anything below)

- **No half-built public launch.** Once people use it we can't make big breaking changes. So: the closed test still
  starts 11-21 (it is private, only his testers see it, and its 14-day clock costs nothing), and we keep building
  through it. The public release goes out when the work he wants is built and polished, not on a fixed date.
  Before the public release, the saved-file formats (projects, drawings, sprite sheets, packs) are frozen.
- **The lobby needs connecting, not a makeover.** It is the most important screen but comes after the wings work
  well. His idea: a room you have no project in plays a short motion-graphic reel of what that room makes (an ad
  for the room, never for money). See DOOR-2.
- **JBX-09 (dark-paper buttons) is stale:** he watches the top buttons flip black/white over dark paper and dark
  parts of the drawing. Off the launch list unless someone finds a case that does not flip.
- **Money: no subscription, ever.** Website idea: JoyBrush packs, some paid, simple "pay, then download". A
  separate business-model planning session is owed (AI-built apps will close this window; plant the flag soon).
- **Website domain:** keep `joycreator.cc` (already bought) for now; `joycreator.studio` or similar only once it
  earns. App ID `cc.joycreator.app` matches the domain he owns.
- **Privacy:** expect identity slips until launch; the new guard blocks most. Run the history scrub once, right
  before the public release, not now.
- **Usage:** weekly reset plus a 5-hour Fable window. **Fable ends 11-17**: until then Fable does only the hardest
  engine work (streaming saves JBX-36, strokes surviving recreation JBX-37, the lost-tile cause JBX-35).
  **No tokens spent waiting:** a lane blocked on a build lock, RAM or a device ends its turn, or waits in a
  background script, never in turns. One or two test devices at a time.

## 1. The clock: Google Play's test rule

For a personal developer account created after 13 November 2023 (organisation accounts and older accounts are
exempt): a **closed test with at least 12 testers opted in for 14 days in a row**, then an application for
production access, reviewed in up to about 7 days. A rejection (for example testers who never really used it)
costs another 14 days. Sources are third-party guides of 2026; check the Play Console's own wording when the
account exists.

So **the closed-test build must be live on Play by Friday 11-21** (latest 11-23). That date drives everything.

| Date | Milestone |
|---|---|
| 11-14 to 11-21 | Week 1, **Play-ready**: account, app ID, testers, a release build Play accepts, privacy page live |
| 11-21 | Closed test starts; 12+ testers opted in the same day |
| 11-22 to 12-05 | Weeks 2-3, **polish while they test**: a new build to the test track every few days |
| ~12-05 | 14 days complete: apply for production |
| 12-06 to 12-14 | Week 4, **ship**: review, store page, website, sizzle video, launch |

## 2. Week 1: Play-ready (11-14 to 11-21)

**Owner (only he can):**
- [ ] Play Console developer account (identity check can take days), or say if one exists from before Nov 2023
- [ ] App ID, permanent (S1). Recommended `cc.joycreator.app`
- [ ] 12 or more testers with Gmail addresses who will really open it a few times over two weeks (family, friends,
      art groups); a Google Group makes adding them one step
- [ ] Contact email for the privacy policy (S4), and whether the domain `joycreator.cc` is his
- [ ] The Smart Detection test clip (handoff 11-14 section 5)
- [ ] Go on the history scrub (repo privacy lane's summary); best done before launch draws eyes to the repo

**Lanes:**
- [ ] **Play build** (SHOW-1): a `play` flavour without the disguise icons, the accessibility screenshot service,
      all-files access and the privacy black screen; release bundle signed with Play App Signing (upload key kept
      private on D: and in the owner's password manager, never in git); measured under 200 MB; 16 KB check on the
      release output; the detector switch merged after the clip; every wing smoke-tested on the release build
- [ ] **Store and legal** (SHOW-2): privacy policy hosted (a first one-page website on GitHub Pages serves it),
      store listing text, tester invite page, content rating and data-safety answers drafted
- [ ] **First run** (DOOR-3): the folder step and the intro read right on the release build; nothing a tester meets
      first is FadCam-shaped or broken
- [ ] Feature freeze for new big things: finish and fix only

## 3. Weeks 2-3: polish while testers test (11-22 to 12-05)

The launch list, in order:
1. **Nothing loses work**: SAFE-1 phone checks; the engine session's streaming saves and the paint-store save
   (`mediaPlaneOfCel`, ATLAS-SOLID "Found 11-14") once measured
2. **The front door** (DOOR-2, owner pick S18): his biggest worry
3. **The picked designs**: brush editor (BRUSH-UI §K), Joybot's arrival and chat drawer, tablet layouts (TAB-1:
   the centred column and the Joybot panel first; Studio side panels if they fit)
4. **Studio**: struck transcript words still in exports (STU-B3, owner ruling owed), the owed device checks
5. **Tester feedback**, triaged daily into these rows
5b. **A "report this AI reply" control in Joybot** (Google Play's AI-content rule expects one; found by the store lane
    11-14, `tasks/store/OWNER_CLICKS.md` §6): small, for whichever lane holds the Joybot chat
6. **Sizzle video, part 1** (week 3): shot list, showcase projects, screen recordings on the phone; edited in
   Joy Creator's own Studio (the video shows the app making itself)

## 4. Week 4: ship (12-06 to 12-14)

- [ ] Apply for production on day 14
- [ ] Store page: screenshots, feature graphic, short video (from the sizzle)
- [ ] Website: the full page (design record `tasks/design/` website files), download button to Play
- [ ] Sizzle video final
- [ ] Launch day: production release, website live, the owner's posts

## 5. Parked until after launch (cards kept)

Select on pixels code (spec landed 9b3323407), new TextLab looks, STU-6 effect swatches, BOT-6 talk half,
Joybot the brush maker, FOLD-1 Story Board and auto-markers (Remote Studio and Detect ship as they are), pack
selling, paper portfolio, Palette knife 3, Proto Pencil 2 unless ready by week 2, M3 beyond what Play flags,
HYG-3 cleanup.

## 6. The barometer (11-14, from the 11-13 audit)

Points: S=1, M=2, L=4, XL=8. One week of usage (owner's measure) took the audit from 0 to about 77-109 of 863
points (57 rows done, 8 large ones "done?"), nearly all small and medium. Remaining usage: about three weeks, so
about 230-330 points at that pace (big items go slower per point, so plan for the low end).

[Triage](audits/launch_triage_20261114.md) of what is left: **LAUNCH 42 rows / 105 points** (some double-counted,
so about 95), POLISH 75 / 165, AFTER 105 / 484. LAUNCH plus the owner's picked designs (brush editor, Joybot arrival,
tablet panels: roughly 60 more) fits in about two weeks of usage, leaving about one week for polish and tester
reports. AFTER waits for after launch. Riskiest: the Play build (L10/L11/SHELL-43), the app ID (SHELL-45), signing
(L15), permissions asked up front (SHELL-36), the lobby front door (SHELL-17/18), streaming saves (JBX-36), strokes
surviving recreation (JBX-37), FadCam folder names (SHELL-44/L16), the website plus data safety (L30/L09), and the
owner's long watercolour session (JBX-40).

## 7. Usage budget

On 11-14 about ten lanes used a whole 5-hour window in about four hours. So:
- **At most four or five lanes at once**; idle lanes cost nothing and stay idle until their week comes.
- Sonnet for checks, docs and copying an existing look; Opus for design and hard code; Fable only for the engine.
- Long threads write a handoff into their card and are cleared, instead of re-reading weeks of history.
- The orchestrator keeps turns short and batches its messages.

| Week | Active lanes |
|---|---|
| 1 | Play build (new, Opus); launch docs (Sonnet); engine (Fable, the owner's session); first run (lobby lane); finishing the picked designs (JoyBrush screens, Joybot sprites) as usage allows |
| 2-3 | Engine; lobby (front door); JoyBrush screens; Joybot sprites; Studio (checks); TAB-1; a Sonnet triage lane for tester reports |
| 4 | Launch docs (store, website); sizzle; whoever fixes the last tester reports |
