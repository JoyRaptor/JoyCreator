# The clicks only you can make (store and legal, week 1)

Written 2026-11-14 for the owner. Plain steps, in the order that saves you time. Everything the agents could write is
already in the repository; this page is only what needs your hands, your accounts or your say-so. Screens on GitHub and
Google change names now and then: if a button is called something slightly different, it is the same step.

**Where things are now** (all on the `joy-creator` branch):

| What | Where |
|---|---|
| Privacy policy, tester page, a first one-page site | the `docs/` folder (the page text comes from `PRIVACY.md` and `tasks/SHOW2_tester_invite.md`) |
| Store name, short and full description | `fastlane/metadata/android/en-US/` (`title.txt`, `short_description.txt`, `full_description.txt`) |
| Data safety and content rating answers, each with its code evidence | `tasks/store/DATA_SAFETY_DRAFT.md`, `tasks/store/CONTENT_RATING_DRAFT.md` |
| Permission and foreground-service justifications | `tasks/SHOW2_play_declarations.md` |
| Messages to recruit testers and the feedback form's questions | `tasks/SHOW2_closed_test_kit.md` |

Why the site lives in `docs/` and not on a separate `gh-pages` branch: one branch to look after, the pages are rebuilt from the same
text files (`python -B tools/make_site.py`), and there is nothing to keep in sync.

## 1. Turn on the website (GitHub; about 2 minutes)

1. Open the repository on github.com, signed in as the owner.
2. **Settings**, then **Pages** in the left list.
3. Under "Build and deployment", **Source: Deploy from a branch**.
4. **Branch: `joy-creator`**, **folder: `/docs`**, then **Save**.
5. Wait a minute or two, reload that page. It shows "Your site is live at ..." and the address is
   `https://joyraptor.github.io/JoyCreator/`.
6. Open `https://joyraptor.github.io/JoyCreator/privacy.html` on your phone and read it once. **That exact address is the
   privacy-policy URL you paste into Google Play.** If you read it and something is wrong, tell a session to fix `PRIVACY.md`
   and rebuild; do not edit the `.html` files by hand.

Good to know: Pages publishes everything inside `docs/`, so the old developer note `docs/project-schema.md` becomes visible at
the same address. It holds nothing private, but say so if you would rather it moved.

## 2. Say which email goes on the page

The policy and the site say **`studio@joycreator.cc`** as a stand-in. Either:
- make that address real and check it receives mail (see step 5 for how), or
- tell a session the address you want and it changes `PRIVACY.md`, the site and the store text in one go.

Play also asks for a contact email in the Console. It can be the same one. Do not use your personal inbox if you can avoid it:
the repository is public and Play shows this address on the store page.

## 3. Create the Google Group for testers (about 5 minutes)

Google asks for **12 or more testers opted in for 14 days in a row**. A group lets you add people in one step.

1. Go to **groups.google.com**, signed in as the Google account you will use for the Play Console.
2. **Create group.** Name: "Joy Creator testers". Group email: any free name, for example `joycreator-testers`.
3. **Who can join: anyone can ask / join.** (Your testers are friends and family with ordinary Gmail addresses.)
4. Add the testers' Gmail addresses (or send them the join link; the tester page already tells them to tap **Join group**).
5. Copy the group's **web address** (it ends with the group name) and its **email** (ends with `@googlegroups.com`).
   The web address goes on the tester page; the email goes into Play (step 4.5).

## 4. Google Play Console (the account, the app, the forms)

Order that works best. Do each form after the first build is uploaded, but you can start the forms any time.

1. **Developer account** (if you do not have one from before November 2023). The identity check can take days, so do this first.
2. **Create app.** App name: **Joy Creator**. Default language English. **App**, not game. **Free**. The app ID is the one the
   build lane fixes (planned `cc.joycreator.app`; it cannot change later).
3. **Upload the first release bundle** to **Testing, then Closed testing** (the Play-build lane makes the file; Play App Signing: accept).
4. **Fill the forms.** Each has a draft in this repository; paste, then check each question against what the form really says:

   | Play form | Where the draft is |
   |---|---|
   | Privacy policy URL | the address from step 1.6 |
   | Store listing: name, short and full description | `fastlane/metadata/android/en-US/*.txt` |
   | App access, Ads, Target audience, Government / financial / health / news | `tasks/store/CONTENT_RATING_DRAFT.md` section 3 and section 2 |
   | Content rating questionnaire | `tasks/store/CONTENT_RATING_DRAFT.md` section 1 |
   | Data safety | `tasks/store/DATA_SAFETY_DRAFT.md` (every answer has its code line) |
   | Photo and video permissions, foreground-service types | `tasks/SHOW2_play_declarations.md` section 4 |
   | Category, tags, contact email | `tasks/store/CONTENT_RATING_DRAFT.md` section 4 |

5. **Closed testing, then Testers.** Create a list or add the **Google Group's email** from step 3. Save.
6. **Copy the opt-in link** that the track shows ("How testers join"). Put it in a message to a session: it goes on the tester
   page next to the group link (the two empty slots are `[GOOGLE GROUP LINK]` and `[PLAY OPT-IN LINK]` in
   `tasks/SHOW2_tester_invite.md`). Then rebuild: a session runs `python -B tools/make_site.py` and pushes.
7. **Send to review** the closed-test release. First reviews can take a few days: this is why the build must be in by **11-21**.

## 5. The domain `joycreator.cc` (later, when you want it: nothing breaks if you skip it)

Do this only after step 1 works. It is separate for a reason: a broken DNS change can take the page down.

1. In the repository: **Settings, Pages, Custom domain**: type `joycreator.cc`, **Save**. GitHub adds a file called `CNAME` to
   `docs/` for you. (Rebuilding the site never deletes it.)
2. At the place where you bought the domain, open its **DNS** settings and add, for the bare name `joycreator.cc`, four **A**
   records with the values GitHub documents: `185.199.108.153`, `185.199.109.153`, `185.199.110.153`, `185.199.111.153`
   (check them against GitHub's page "Managing a custom domain for your GitHub Pages site" before you save: they are GitHub's
   to change, not ours).
3. Optionally for `www.joycreator.cc`: add a **CNAME** record, name `www`, value `joyraptor.github.io`.
4. Wait (from a few minutes to a day). Back in **Pages**, tick **Enforce HTTPS** once it lets you.
5. When the new address works, change the privacy-policy URL in Play to `https://joycreator.cc/privacy.html` (Play lets you edit it).
6. **Email at the domain** (`studio@joycreator.cc`) is a different service: your registrar probably offers free forwarding to a
   Gmail address. Set that up so the contact address in the policy really receives mail.

## 6. Decisions only you can make (the drafts take the safe side; say if you want otherwise)

| Decision | The draft says | Why it is yours |
|---|---|---|
| **Who the app is for** | 13 and over; not a children's app, even though your children use JoyBrush at home | Playing it for under-13s brings Google's Families rules (more checks, stricter AI treatment) |
| **Data safety: how to count the AI helper** | Declare it: Joybot's messages and pictures go to the AI service you choose, optional | Google may exempt it; declaring is harmless, a wrong "nothing" is not |
| **Category** | Video Players & Editors (alternative: Art & Design) | Taste |
| **A "report this AI reply" button in Joybot** | **Exists now** (11-14): a flag beside Copy on every answer; it opens the person's email app with the reply prefilled to your contact address. Nothing is sent unless they press send. Mention it where Play asks how users can report AI content | Only that the contact address (`studio@joycreator.cc`) must really receive mail; it is a stand-in until you confirm |
| **The Google feedback form** | Questions are drafted in `tasks/SHOW2_closed_test_kit.md` | You create the Form (forms.google.com) so replies go to you; paste its link into the tester page next to `[FEEDBACK FORM LINK]` |

## 7. Still needs an agent, not a click

- Fill the three tester-page links once you have them (step 4.6, the group, the form), rebuild the site.
- If the Play build lane's removals (disguise icons, accessibility service, all-files access) do not land, the Data safety answers and the policy stop being true; `tasks/store/DATA_SAFETY_DRAFT.md` section E lists what to check.
