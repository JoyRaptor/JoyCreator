# Closed-test kit (job L38, prepared 2026-11-13)

Google Play asks a new personal developer account for a **closed test with at least 12 testers opted in for 14 days in
a row** before production (roadmap S6; check Play Console's current wording, the rule has changed before). Calendar
time is the only cost. Everything below is ready to paste once the account exists. **Placeholders in `[BRACKETS]`
need the owner.**

## 0. What the owner must do first (nobody else can)
- [ ] Create the Play developer account and the app entry (needs the permanent app ID, roadmap S1).
- [ ] Make a closed-testing track, add a tester group (a Google Group or an email list) and copy the opt-in link.
- [ ] Give a public contact email for testers (roadmap S4): [CONTACT EMAIL].
- [ ] Create the feedback form from section 4 (Google Forms or any form tool) and copy its link: [FORM LINK].

## 1. Recruitment text (short, for a message or post)

> **Help me test Joy Creator: a free creative studio for your phone.**
> I'm an artist making Joy Creator: paint and animate, edit video, make music visuals and title looks, all in one
> app. It is free for good: no ads, no subscription, no watermark. I need **12 people on Android** to try it for two
> weeks before it goes public.
> **What I'm asking:** install it from the testing link, open it a few times over the two weeks (a few minutes is
> fine), try the checklist if you like, and tell me what confused you or broke.
> **Important:** please keep it installed and opted in for the whole 14 days: Google counts that.
> **Link:** [PLAY OPT-IN LINK] · **Questions:** [CONTACT EMAIL]
> It is a beta: some features are newer than others. Your drawings and projects stay on your phone, in a folder you choose.

Longer version (for a community page): add one line per wing (JoyBrush, Studio, VisLab, TextLab, SpriteLab, Joybot) and
the sentence "Joybot, the AI helper, needs your own key from an AI service and is optional."

**Who to ask:** friends and family with Android phones, artists' and video-editing communities the owner already
belongs to, the owner's followers. Aim for 15-20 sign-ups so 12 stay. Don't pay for testers and don't ask for
reviews; testing is feedback, not ratings.

## 2. Reply to someone who says yes
> Thank you! Here is the link: [PLAY OPT-IN LINK]. Open it on your Android phone, tap "Become a tester", then install
> from the Play Store page it opens. Keep it installed for two weeks. The checklist and form are here: [FORM LINK].

## 3. Test checklist (ten steps, about 20 minutes, any order)

Tell testers: "Stop at any step that is confusing and write down what you expected."

1. **First open.** Does the app open? Were you asked to pick a folder for your work? Did you understand why?
2. **Look at the lobby.** Can you tell where to go to draw, to edit a video, to make a visual? Which door did you try first?
3. **Draw.** Open JoyBrush, make a drawing with two brushes, undo once, close the app, and open it again: is the drawing there?
4. **Make a short video.** Open Studio, add a video or picture, add a title, and press Export. Did the file land where you expected? Does it play?
5. **Library.** Find the drawing and the video in the Library. Hold one: does the menu make sense?
6. **Music visual.** Open VisLab, pick a look, and play it. Try changing one thing.
7. **A title look.** Open TextLab, pick a look for a word of your own, and save it or put it on a title in Studio.
8. **Joybot (optional).** Open Joybot and ask it "What can I do here?". If you have an AI key from OpenRouter or similar, add it and ask it to do one small thing, then undo it.
9. **Rotate and interrupt.** Take a call or switch apps in the middle of drawing or editing and come back: is your work still there?
10. **Close it and leave.** Close the app. A day later, open it: was everything where you left it?

Also welcome, any time: anything that felt slow, hot, ugly, hard to tap, or in a language you didn't expect.

## 4. Feedback form (paste into a form tool, one question per line)

Intro line: *"Thank you for testing Joy Creator. Nothing here is graded: tell me what you really thought. About 5 minutes."*

| # | Question | Type |
|---|---|---|
| 1 | Your phone make and model, and Android version | short text |
| 2 | How many days did you open the app? | choice: 1 / 2-3 / 4-7 / more |
| 3 | What did you try? | tick boxes: JoyBrush, Studio, VisLab, TextLab, SpriteLab, Library, Joybot, Capture/recording, Settings |
| 4 | Which checklist steps did you do? | tick boxes 1-10 |
| 5 | Did anything crash, freeze, or lose your work? What were you doing? | long text |
| 6 | Did anything surprise or confuse you? | long text |
| 7 | What did you like most? | long text |
| 8 | Which wing would you use again? | choice: the six above, none |
| 9 | Was anything too slow, hot or heavy on your phone? | choice + text |
| 10 | Did you find everything you wanted from the lobby, or did you have to hunt? | choice + text |
| 11 | Would you recommend it? 0-10 | scale |
| 12 | Can I contact you about your answers? Email (optional) | short text |

Do not collect more than this, and say in the form that the answers are used only to improve the app.

## 5. During the 14 days (owner or an agent)
- [ ] Day 1: confirm 12+ testers show as opted in; top up if not.
- [ ] Day 3, 7, 11: a short thank-you and a nudge in the same channel.
- [ ] Read each form answer; log crashes in tasks/ (give each a row in STATE section 9 if real).
- [ ] Day 14: check the Play Console says the requirement is met, then apply for production access.
