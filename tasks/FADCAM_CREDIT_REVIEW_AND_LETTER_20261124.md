# FadCam: credit review and a draft letter to its author (L33)

Written 2026-11-24 by the website lane (build-queue row L33). **Not legal advice.** Nothing here has been sent or published; the
letter is a draft for the owner to read, change and send himself, from his own account. Read with
[FREE-1](jobs/FREE-1_fadcam_inventory.md) and [audits/fadcam_inventory_20261028.md](audits/fadcam_inventory_20261028.md) (its 11-23 addendum
says 31.6% of the app module's Java lines are still the fork's).

## 1. Where Joy Creator credits FadCam today

| Where | What it says | Verdict |
|---|---|---|
| `LICENSE` | The GNU GPL v3, unchanged | Right. Keep. |
| `README.md` (line ~58) | "grew out of FadCam by anonfaded, and keeps its recorder at ..." with a link to `github.com/anonfaded/FadCam` | Right. Keep the link. |
| `TRADEMARK.md` and `docs/trademark.html` | "grew out of FadCam by anonfaded (FadSec Lab), which is GPL v3, and keeps that credit in About and in the README" | Right. |
| `PRIVACY.md` and `docs/privacy.html` | "built on FadCam by FadSec Lab, free software under the GNU GPL v3" | Right. |
| In-app About (`aboutjoy_based_on`) | "Built on FadCam by FadSec Lab" | Right. It should link to the FadCam repository (check that it does; text only was found here). |
| Play full description (`fastlane/.../full_description.txt`, last line) | "open source (GPL v3) and is built on FadCam by FadSec Lab" | Right. Keep it on the store page. |
| `docs/*.html` footers | "free software (GNU GPL v3), built on FadCam by FadSec Lab" | Right. |
| `strings.xml` `fadsec_info` | "free and open-source ... click on 'view source code'" | Fine; check the button goes to Joy Creator's source, not FadCam's. |
| `strings.xml` `app_icon_fadseclab` ("FadSec Lab") and 30 launcher-icon aliases | Leftover disguise-icon names | Not a credit. Cut with the disguise icons (FREE-1). |
| `README.md` build notes | clone `anonfaded/media3-patched` | A real dependency on the author's patched Media3; credit and keep until replaced. |

**Gaps to close (owner's choice, none urgent):**
1. A GPL v3 notice is only as good as the source offer: the Play listing and About should point at the repository that holds the *complete* corresponding source
   (the public Joy Creator repository). Confirm that repository is public before the store build ships.
2. Keep a copyright/credit line for FadCam's author in the repository (README is enough) while any FadCam-derived file remains. Do not remove the original
   file headers from the fork's files.
3. The names "FadCam" and "FadSec Lab" belong to their author. Credit them in words; do not use their logo or name as if it were Joy Creator's. The
   three-flavour disguise icons used FadCam-era names; they are being removed (S10 / FREE-1).
4. If the owner ever wants to change licence, that only becomes possible once every FadCam-derived file is gone or rebuilt clean (FREE-1). A lawyer should check it first.

## 2. Draft letter (for the owner to edit and send himself)

> **To:** the FadCam author (contact route to be chosen by the owner: the GitHub profile, or the project's issue page or Discord)
> **Subject:** Thank you, and a heads-up: Joy Creator is built on FadCam
>
> Hello,
>
> I'm JoyRaptor, an artist from [PLACE, optional]. I wanted to tell you in person about something I've made, because it began with your work.
>
> Joy Creator is a free Android studio for painting, animation, video editing, music visuals and titles. In June I forked FadCam as the starting point
> for its recorder and its screen capture, and I'm very grateful for them: they are the part of the app that works every day.
> Everything else in Joy Creator (the Studio, JoyBrush, VisLab, TextLab, SpriteLab and the helper Joybot) was built on top.
>
> A few things you should know, so nothing surprises you:
> - Joy Creator is released under the same licence, the GNU GPL v3, and the full source is public at [REPOSITORY LINK].
> - It credits FadCam and FadSec Lab in the app's About page, in the README and on its store page, with a link to your repository.
> - It still contains a good deal of FadCam's code in the recorder, screen-recording, Records and Remote parts. Over time I plan to rebuild or replace
>   those parts with my own, and I'll keep the credit for as long as any of your code remains.
> - I have removed FadCam's own cloud addresses, donation links and Pro screens, so nothing in Joy Creator sends anyone to your services or asks them for money in your name.
> - Joy Creator is free: no ads, no subscription, no account.
>
> If you'd like the credit worded differently, a link added, or anything changed, I'll gladly do it. If you have advice about the parts I took from you
> (especially the patched Media3), I'd welcome it. And if you'd rather I did something differently, please say so.
>
> Thank you again for FadCam.
>
> JoyRaptor
> [CONTACT EMAIL, to be confirmed by the owner: not his personal address]

**Facts in the letter to check before sending:** "In June" (the fork snapshot is dated 2026-06-05); "removed FadCam's own cloud addresses, donation links and Pro
screens" (true at 11-24 by search: no `supabase` or `fadseclab.com` in the app source; the only `FadCam Pro` and Ko-fi mentions left are code
comments in `services/UpdateCheckService.java` and `strings_about.xml`, nothing a user sees or taps); the repository link; the contact email (open item S4).

## 3. What the owner decides
- Whether to send the letter, by which route, and under which name and address (S4).
- Whether to make Joy Creator's source offer explicit in About (a "Source code" link to the repository).
