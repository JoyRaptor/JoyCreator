# LAUNCH STRATEGY — Joy Creator

**Status:** DRAFT / ACTIVE
**Owner:** JoyRaptor
**Created:** 2026-08-06
**Purpose:** Living document. Carries strategy, decisions, and progress across
conversations so no session has to re-derive the plan. Update the Decision Log and
Status Board every time something changes.

> This is a business/release document, not an engineering plan. Engineering plans live
> in the other `tasks/PLAN_*.md` files.

---

## 1. Situation (as of 2026-08-06)

- Joy Creator is a fork of [FadCam](https://github.com/anonfaded/FadCam) (GPLv3, by
  anonfaded), imported at commit `78647c1` (2026-06-05).
- Measured split at HEAD: **~42% our code, ~58% FadCam** (287,648 java/kt lines total;
  664 files). See §9 for method.
- Our contribution is ~87% concentrated in **one subsystem: Faditor**, the video editor
  (15,298 → 120,985 lines; 29 → 249 files). Everything else — camera recorder, screen
  recorder (`fadrec`), dual-cam, LAN streaming, forensics, OpenGL pipeline — is FadCam,
  substantially as imported.
- Faditor depends on only **22 distinct upstream classes**, most of them plumbing
  (`Log`, `FLog`, `Constants`, `SharedPreferencesManager`). The seam is thin.
- Upstream has diverged: `origin/master` is 46 commits ahead (to 2026-07-26), never
  merged. We are not consuming their changes.
- **anonfaded already monetizes FadCam** ("FadCam Pro — lifetime access" via Patreon
  shop). They are a commercial actor, not a pure hobbyist.

### Constraints we are planning around

- No legal budget. No servers. No website. No company.
- Solo, non-developer operator working through AI assistance.
- Needs revenue reasonably soon; cannot fund months of work with no income.

---

## 2. Thesis

**Free, open, and genuinely unhobbled — monetized on goodwill and convenience, not on
withheld features.**

Why this can work against CapCut / KineMaster despite being a no-name project:

1. **No hobbled export.** Competitors gate resolution, bitrate, and watermarks. We do
   not. This is the single clearest differentiator and it is also a values position.
2. **No subscription, no ads.** One of the few serious mobile editors that is neither.
3. **Features they don't have and won't build** — layer modes, masking and layer masking,
   keyframe animation, puppet/avatar animation, animating sprite sheets and image sequances, transcript-driven
   editing, waveform editing, GL transitions, AI-assisted editing. This is closer to
   After Effects' expandability than to a social-clip trimmer. local and on-device ai features
4. **Privacy and offline-first as a bonus,** not as the pitch.
5. **Owner is a working creator.** Built for a real YouTube workflow, and demonstrated
   on one. See §6.

**Audience:** general-audience creators, **animators, and compositors** who need something
that handles large projects and won't hobble their export. Joy Creator is a *suite*, not
a single editor. components being a screen recorder, webcam or live avatar display, they Joy Creator editor, avatar studio, audio visualizer studio with possibly more tools coming that will interrelate

**Positioning line (draft):** *A real editor that doesn't hold your work hostage.*

**Tagline / export signature:** **"Made with Joy."** Stamped on export (as metadata and
as an optional, off-by-default end card — never a forced watermark, which would violate
the no-hobbling promise). Cheap, warm, and it does organic marketing every time someone
shares a file.

---

## 3. Distribution: two channels, one codebase

This is the core structural decision. GPLv3 makes it free to do.

| | **Play Store build** | **GitHub / F-Droid build** |
|---|---|---|
| Audience | General creators | Privacy/power users |
| Icon disguise aliases | **Removed** | Kept |
| `MANAGE_EXTERNAL_STORAGE` | **Removed** (SAF/MediaStore) | Kept |
| Accessibility screenshot service | **Removed** | Kept |
| Privacy Black Screen | **Removed** | Kept |
| Cloud / streaming (FadCam infra) | **Removed** — see §5 | Optional |
| Review risk | Low once stripped | None (no review) |

The Play build is the *sanitized* build. The full-featured build stays available to
people who want it, distributed where no one reviews it. This is a normal, well-worn
pattern and it resolves the "I want the privacy tools but they'll get me banned"
tension without forcing a choice.

**One caution to be aware of:** covert-recording features carry jurisdiction-specific
legal exposure regardless of which store they ship in. Off-Play distribution removes the
account-ban risk, not that one. Worth a clear-eyed look before promoting those features
prominently.

---

## 4. Monetization ladder

Ordered by what is actually available *now* given zero infrastructure.

### Tier 0 — Available today, $0 infrastructure

- **Donations via external links** (Ko-fi, GitHub Sponsors, Liberapay). No server, no
  company needed. GitHub Sponsors and Liberapay take ~0%.
- **In-app donation prompt.** Approved approach: dismissible, shown *after* a success
  moment (e.g. after the 5th completed export), then at most monthly. Never blocking,
  never before the user has gotten value. The honest "this supports a family of five"
  framing works well in indie FOSS — use it once, plainly, without guilt-tripping.
- **YouTube channel as the engine.** See §6. This is the highest-leverage $0 asset and
  it is the one thing here that plays to existing expertise.

> ⚠️ **Play Billing rule that shapes the design:** donations that unlock *nothing* may
> use an external payment link. Anything that unlocks *any* app functionality —
> including cosmetic themes or icons — must go through Google Play Billing (15% cut on
> the first $1M/yr). Keep the two strictly separate or the listing is at risk.

### Tier 1 — Near-term, ~$25 + a weekend

- **"Supporter" purchase via Play Billing.** Must unlock something, so unlock things
  that cost nobody anything: alternate themes, alternate app icons, a supporter badge,
  early-access channel. **Never export quality, resolution, or watermark removal.**
  This preserves the no-hobbling promise while giving willing payers a way to pay.

### Tier 2 — Later, requires capital

- **Hosted AI credits.** Currently AI runs through OpenRouter with the user's own key:
  $0 cost to us, $0 revenue. Reselling inference requires a server, prepaid credits,
  billing, and abuse handling. **Do not attempt until there is enough recurring income
  to absorb a bad month.** Rough trigger: only revisit once donations alone cover
  hosting for 6 months without touching personal funds. Until then, BYO-key stays the
  default and is a genuine selling point.
- **Paid cloud sync / project backup.** Same reasoning. Not now.

### Not recommended

- Paid-app-only (GPL means a free rebuild is trivial; also blocks the growth we need).
- Ads (contradicts the entire thesis).
- Subscriptions (the thing users are fleeing).

### Launch cost floor

| Item | Cost |
|---|---|
| Google Play developer account | $25 one-time |
| Domain name | ~$12/yr |
| Website hosting (GitHub Pages / Cloudflare Pages) | $0 |
| Ko-fi / GitHub Sponsors | $0 |
| **Total to be live and able to receive money** | **~$40** |

---

## 5. ⚠️ Cloud & streaming — must resolve before shipping

`CloudAccountActivity`, the `streaming` package, and the relay/stream-key code point at
**FadCam's own server infrastructure**, which we do not own or control.

Shipping that under the Joy Creator brand is not viable:

- We cannot truthfully complete Play's Data Safety form for someone else's backend.
- We cannot guarantee availability, and outages would be our reviews' problem.
- It routes our users' data to a third party without a relationship governing it.

**Decision needed:** remove these features from the Play build entirely (recommended),
or — only if a partnership happens — negotiate explicit terms for using that backend.
Until then, treat cloud/streaming as OFF for anything we publish.

---

## 6. Website & audience

- **Website:** GitHub Pages or Cloudflare Pages, free. Needs to exist before Play
  submission anyway, because **Play requires a hosted privacy policy URL.** Minimum
  viable site: what it is, a 60-second demo video, download links (Play + GitHub +
  F-Droid), privacy policy, donate link, and source link (which also satisfies GPL §6).
- **YouTube is the distribution strategy, not a side project.** Building the tool *for*
  the channel and then making the channel demonstrate the tool is a genuine advantage no
  competitor has. Tutorials answering "how do I do X on mobile" that happen to be
  answered by Joy Creator are the cheapest possible user acquisition, and the ad revenue
  is a second income line.
- Secondary: r/androidapps, r/videoediting, XDA, F-Droid inclusion, Hacker News on a
  strong release.

---

## 7. Legal posture (no lawyer, minimal risk)

1. **Comply with GPLv3.** Publish source, retain FadCam's copyright notices, license the
   combined work GPLv3. Compliance is *protective*: it makes the listing hard to take
   down. Non-compliance invites a copyright complaint that would pull the app fast.
2. **Hold the name, not the code.** [TRADEMARK.md](../TRADEMARK.md) reserves "Joy
   Creator" and the logo. Clones can exist; clones called Joy Creator cannot. Registration
   later, when there is income.
3. **Own the docs.** `README.md` and `PRIVACY.md` are still FadCam's verbatim. Both must
   be rewritten before publishing — the privacy policy especially, because it will be
   factually false about our app the moment we ship AI features.
4. **Keep extraction viable.** If Faditor ever needs to leave the GPL base (e.g. for
   iOS, where GPL conflicts with Apple's terms), the seam is 22 classes today and grows
   every month. Not urgent, but the cost is monotonically increasing.

---

## 8. Status board

| # | Action | Status | Notes |
|---|---|---|---|
| 0a | **Back up repo to a private remote** | 🔴 URGENT | Only copy exists on one machine; push remote is `DISABLED_LOCAL_ONLY` |
| 0b | **Scrub commit email before any public push** | 🔴 URGENT | All 769 commits authored as `studio@joycreator.cc` — real name + personal address would become permanently public. See §11 |
| 1 | `TRADEMARK.md` | ✅ Done | Needs contact email filled in |
| 2 | Letter to anonfaded | 📝 Drafted | See `tasks/OUTREACH_ANONFADED.md` |
| 3 | Send letter | ⬜ Not started | Highest leverage, ~5 min, do first |
| 4 | Strip Play blockers (aliases, MEDIA, a11y, FGS) | ⬜ Not started | Required either way |
| 5 | Remove cloud/streaming from Play build | ⬜ Not started | See §5 |
| 6 | Rewrite `README.md` | ⬜ Not started | Currently FadCam's |
| 7 | Rewrite `PRIVACY.md` | ⬜ Not started | Currently FadCam's, and false |
| 8 | Replace `fastlane/` listing assets | ⬜ Not started | Currently FadCam's |
| 9 | Register domain + put up site | ⬜ Not started | Needed for Play privacy URL |
| 10 | Set up Ko-fi / GitHub Sponsors | ⬜ Not started | $0, do early |
| 11 | Play developer account ($25) | ⬜ Not started | Starts the 14-day/12-tester clock |
| 12 | Closed testing: 12 testers × 14 days | ⬜ Not started | Calendar-bound; start ASAP |
| 13 | Demo video (60–120s) | ⬜ Not started | Doubles as letter attachment + site hero |
| 14 | Polish pass on editor UI | ⬜ Not started | Acknowledged gap |

---

## 9. Decision log

Append here. Never rewrite history — add a new dated entry instead.

**2026-08-06 — Measured the fork split.**
Method: diffed the true import root `78647c1` against HEAD. Bucketed every `.java`/`.kt`
file at HEAD as added-by-us (277 files / 73,369 lines), modified (50 / 96,374, of which
47,669 insertions ours), or untouched-upstream (337 / 117,905). Caveat: `--numstat`
scores a reformatted upstream line as ours, so ~42% slightly flatters us.

**2026-08-06 — Rejected "clean-room the parts we built."**
Clean-room is a technique for reproducing *someone else's* work without copying it. Our
121k lines are already ours; rewriting them proves nothing. The GPL obligation flows
from Faditor *depending on* FadCam, not from any copying. The only route out is
replacing the base, not rewriting our own work.

**2026-08-06 — Chose GPL compliance over extraction, for now.**
Extraction is real (22 seams) but costs months with no income. Compliance costs ~$0 and
ships in weeks. Trademark + services, not code secrecy, carry the revenue defense.

**2026-08-06 — Chose two-channel distribution.**
Play gets the sanitized build; GitHub/F-Droid gets the full privacy build. Resolves the
policy-risk vs. feature-loss tension at no engineering cost.

---

## 10. Open questions

- [ ] Does anonfaded reply, and what do they want? Everything downstream shifts on this.
- [x] ~~Is "Joy Creator" free to use as a mark?~~ Searched 2026-08-06 — see §11.
- [ ] What is the AAB size with ffmpeg-kit + OpenCV + TFLite + MediaPipe + Vosk? Play's
      compressed limit is 200 MB. Unmeasured.
- [ ] `ffmpeg-kit` is retired upstream (binaries pulled 2025). Replacement plan?
- [ ] What does the editor need to look shippable? Scope the polish pass.
- [ ] There are currently **no automated tests** in `app/src/test` or `androidTest`
      despite the Gradle config expecting them. Risk to a solo non-developer maintainer.

---

## 11. Naming & identity findings (searched 2026-08-06)

### Name availability

| Check | Result |
|---|---|
| "Joy Creator" as an app on Play / App Store | No direct conflict found |
| "Joy Creator" registered US trademark | None found for the bare mark |
| Nearby registered mark | **JOY CREATOR COLLECTIVE** — Joy Group Holding Inc., covering influencer marketing / advertising services |
| `joycreator.com` | Registered 2012, parked at HugeDomains — **for sale, likely $2k+**. Out of budget. |
| `joycreatorstudio.com` | ✅ **AVAILABLE** |
| `joycreator.app` | Probably available (registry RDAP returned not-found; confirm at a registrar) |
| `madewithjoy.com` | Registered since 2007. Not available. |

**Assessment.** No blocking conflict. JOY CREATOR COLLECTIVE is in a different class of
goods and services (marketing services, not software), so coexistence is normal — but it
is a reminder that "Joy" + creative-word names are a crowded space.

**The real weakness is not conflict, it's distinctiveness.** "Joy Creator" is close to
descriptive, and descriptive marks are weak: hard to register, hard to enforce, easy for
others to crowd. Since trademark is the *primary* defense against GPL clones (§7), the
name is doing real strategic work and a weak one costs us.

**Recommendation:** ship as **Joy Creator Studio** — it's the stronger mark of the two,
the matching `.com` is free today, "Studio" correctly signals a suite rather than a
single editor, and "Joy Creator" still works as the everyday short name. Register
`joycreatorstudio.com` before announcing anything.

### Commit identity — must fix before first public push

All 769 commits are authored as `studio@joycreator.cc`. Pushing publicly would make a
real name and personal email permanently part of the git history — mirrored, cached, and
scraped within hours, and **not fixable after the fact without rewriting every commit**.

Fix before the first public push:
1. Enable GitHub's **"Keep my email address private"**, plus **"Block command line pushes
   that expose my email"** (Settings → Emails). This gives a `@users.noreply.github.com`
   address.
2. Set that as the local identity: `git config user.email "<id>+joyraptor@users.noreply.github.com"`
3. Rewrite existing history to replace the old address (`git filter-repo --mailmap`, or
   equivalent), **before** the repo is ever public.

Note `anonfaded@pm.me` also appears in history — that one is theirs, it is legitimate
upstream authorship, and it must be **left alone**. The GPL requires preserving it.

### GitHub account shape

Use the existing **JoyRaptor** handle as the personal account, and create a free
**Organization** for the project later when the brand is settled; repos transfer between
them at any time. Reasons: contribution history accrues to one identity you're already
known by, Sponsors verification is simpler on a personal account, and a throwaway account
named after the product becomes dead weight if the name changes.

Gmail is fine for *account recovery*; add the project Proton address as a second verified
email and use it for anything public-facing.
