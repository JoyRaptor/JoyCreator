# Outreach — anonfaded (FadCam)

**Status:** DRAFTED, not sent
**Created:** 2026-08-06
**Related:** [LAUNCH_STRATEGY.md](LAUNCH_STRATEGY.md) §8 item 3

---

## Before you send

**Where to send.** GitHub is better than email for a first contact — it is public, it is
their turf, and it creates a visible good-faith record. Open a **GitHub Discussion** on
`anonfaded/FadCam` (or an issue if Discussions are off). If they list an email in the
repo or on Patreon, send the same text there too.

**Attach a demo video.** This matters more than the letter. You are a video creator
approaching a developer — a 60–90 second screen recording of the editor doing layers,
masking, and puppet animation will do more than any paragraph. Unlisted YouTube link is
fine. If you only do one thing on this list, do this one.

**Three things to know going in:**

1. **They already sell FadCam Pro** (Patreon lifetime access). You are proposing
   something adjacent to a product they earn from. Naming that yourself, early and
   plainly, is much better than having them raise it. The draft below does.
2. **You have leverage, so don't grovel.** You have written a 120,000-line video editor
   they do not have and, from their roadmap, were not going to build. That is the offer.
   Warm and direct beats apologetic.
3. **Don't ask for a license exception in the first message.** That is the big ask — it
   asks them to give up the copyleft on their own work. Establish that you exist, that
   you have complied, and that you built something real. The commercial conversation, if
   it happens, happens later.

**What you are actually asking for:** a conversation, and their blessing to publish under
a distinct brand with prominent credit. That's it. It is small, it costs them nothing,
and it is very likely to get a yes.

**On the exhaustive feature list** — you asked whether to include one. No. A tight list
of the eight or so things that are genuinely surprising reads as confident; a
forty-item dump reads as anxious and won't get read. The demo video is the exhaustive
version.

---

## Draft letter

> Subject: **I built a full video editor on top of FadCam — would like to talk to you
> about it**

Hi,

I'm the developer of Joy Creator, which is a fork of FadCam. I wanted to reach out
directly rather than have you find out about it some other way.

Some context on who I am: I'm an animator and content creator, not a software developer.
I've never written a program in my life. Earlier this year I went looking for a mobile
recorder and editor for my YouTube workflow, tried everything on the Play Store, and
hated all of it — ads everywhere, export quality deliberately crippled, subscriptions for
things that should be table stakes. Then I found FadCam. It was the only thing I found
that was ad-free, respected the user, and had a genuinely impressive feature set
underneath. So I started building the editor I wished existed on top of it, using AI
assistance to write the code.

That was about two months ago. It got further than I expected.

Your editor, Faditor, went from the roughly 15,000 lines it was when I forked to about
121,000. It now has:

- Multi-track layers with layer blend modes
- Masking
- Full keyframe animation with an editable curve/dope sheet
- Puppet/avatar animation with face tracking
- Sprite sheet animation
- Transcript-driven editing (offline speech recognition, word-level timestamps)
- Waveform editing
- GL transitions and effects
- An AI assistant that can perform edits on the timeline

The honest summary is that it's much closer to a mobile After Effects than to a clip
trimmer, and it does several things CapCut and KineMaster don't do and don't seem
interested in doing. It needs a serious visual polish pass before I'd call it pretty, but
it works. I'd be glad to send you a short screen recording — I think it's more convincing
than a list.

**On the licensing, so it's not an awkward question later:** I understand this is a
derivative work and that GPLv3 applies to all of it, including my part. I'm not asking
you to change that. I intend to publish my source, keep your copyright notices intact,
and credit FadCam prominently. I've also written a trademark notice for my own project
that explicitly disclaims any right to the FadCam name, logo, or FadSec Lab branding.

**And on the commercial side, plainly:** I know you sell FadCam Pro, and I don't want to
blindside you. I'm poor and I am trying to make a living from this, so I won't pretend
otherwise. But I don't think we're actually going after the same people. FadCam is a
privacy-first tool with an edge to it, and that identity is a real part of its appeal.
Joy Creator is meant to be a general-audience creative suite — aimed at creators,
animators, and compositors who want something that can handle large projects and won't
hobble their exports. The privacy and offline-first parts are a great bonus there rather
than the pitch. Different brand, different audience, same excellent engine underneath.

So what I'd like to ask is small:

1. Are you okay with me publishing this under a distinct brand, with clear credit to
   FadCam and full GPL compliance?
2. Would you be open to collaborating? I'd genuinely like to send fixes back upstream —
   I've been deep in the recording and export paths for two months and have found
   things. If there's a version of this where we exchange code and both benefit, I'm
   very interested.

One thing I want to be clear about: I'm not asking you for money, and I'm not asking you
to give up anything. If your answer is "publish it, credit me, we're done," that's a
perfectly good outcome and I'll go do that. I mostly wanted you to hear about it from me
first, and to say thank you — genuinely. I couldn't have built any of this from nothing,
and the fact that your work was open is the only reason it exists.

Thanks for reading,
[your name / handle]
[link to repo]
[link to demo video]

---

## If they say no, or don't reply

Nothing structurally changes. GPLv3 is a **license, not a permission slip** — the right
to fork, modify, and distribute is already granted to you by the license itself, and it
cannot be withdrawn as long as you comply. Their blessing is valuable for goodwill,
collaboration, and not making an enemy. It is not a legal prerequisite.

So: no reply after ~2 weeks → send one polite follow-up → then proceed with the
compliance-and-ship plan in `LAUNCH_STRATEGY.md` regardless.

If they respond negatively, the questions worth asking are what specifically they object
to, and whether a different brand/positioning resolves it. Log the outcome in the
Decision Log either way.
