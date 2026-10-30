# BOT-5: Joybot's character, and Joybot as the guide

**Milestone:** M2 (with the front door)  ·  **State:** ⛔ waits on the owner's labelled sprite sheet and on DOOR-3  ·  **Owner:** open  ·  **Checked:** 2026-10-28

## Why it matters (for the user)
Joybot is the face of Joy Creator: he greets you, shows you around once, then lives in his corner. That is
the "iPhone fans like it" part of the front door.

## Done when
- [ ] His moods play the owner's animations; he flies to his corner and beside new pictures.
- [ ] First run: he guides, then takes up residence; tapping him later offers the tour again.

## Where it stands (verified 10-28)
Moods (idle, listen, think, work, talk, excited, show, oops) and places are built and played with two
stills; a sheet loader exists and maps animations by name. The owner is drawing the sheet.

## What is left
- [ ] Load the owner's labelled sheet; check each mood on the phone. (Owner: the sheet, and OK the names.)
- [ ] Fly-to-corner and show-off moves.
- [ ] Guide the first run, then fly to his corner ([DOOR-3](DOOR-3_first_run.md)); the tour stays with him
      ("what would you like to see?" with intent-shaped choices).
- [ ] Owner idea, undecided: tap him for a quick voice ask, hold for the full chat.
- [ ] Replace the FadCam avatar toggle with Joybot (FadCam independence, [FREE-1](FREE-1_fadcam_inventory.md)).

## Specs, handoffs and evidence
- [Plan A, "Joybot as a character"](../joybot/JOYBOT_PLAN_20261014.md), [INBOX 9-09 and 9-12](../INBOX.md).

## Files and traps
- `ui/lobby/JoybotView`, `JoybotFilm`, `ai/joybot/JoybotPulse`. Reduced motion → a still tint, never a breath.
