# BOT-1: Joybot, finish and prove what is built

**Milestone:** M1  ·  **State:** 🔨  ·  **Owner:** "JoyBot UX vision and capabilities" (Claude, account C)  ·  **Checked:** 2026-10-28 against `fbfa50ce4`

Joybot's whole list is split into five cards, in this order:
**BOT-1 finish and prove** → [BOT-2 everywhere and findable](BOT-2_joybot_everywhere.md) →
[BOT-3 trust](BOT-3_joybot_trust.md) → [BOT-4 new reach](BOT-4_joybot_reach.md) →
[BOT-5 his character and the guide](BOT-5_joybot_character.md).

## Why it matters (for the user)
Joybot is how a newcomer finds and uses everything ("Siri, but in tune with the whole studio"). Everything
he can already do has to work every time on the phone before he is promoted at the front door (M2).

## Done when
- [ ] Every item below is phone-proven (agent or owner, said which) or explicitly parked by the owner.
- [ ] STATE §6 has a row for every Joybot feature, with its proof level and help mapping.

## Where it stands (verified 10-28)
Built and agent-phone-tested on the Note 9, 10-14 to 10-24: idea buttons and the breathing glow, one undo
per request, the slide wizard, pictures, the help book with "Show me how", typed actions across Studio, the
app (rooms, Library, Trash), VisLab and JoyBrush, Director mode (plan → Go ahead → Review), recipes, Carry on
after the app is killed, model fallback, locked items, `look` on the finished frame, multi-provider accounts,
the audit fixes (5 of 6 checks). A 53-task bench passes 53/53 with the fake model
([BENCHMARK](../joybot/BENCHMARK_2026-10-24.md)). Long jobs already survive closing the chat (AIJobService).
Ledger: [plan B6](../joybot/JOYBOT_PLAN_20261014.md), [audit fixes](../audits/2026-10-18/joybot_fixes.md).

## What is left
- [x] **JoyBrush room shows the wrong idea buttons.** Fixed 10-28: four JoyBrush buttons (a layer to colour
      on, start an animation, next frame from this one, what's in this drawing), only things the joybrush
      tools really do. **Phone ✅ 10-28 (agent, Note 9):** the buttons show in JoyBrush; "A layer to colour on"
      made a second layer in one step and one JoyBrush Undo removed it.
- [x] **Phone: the VisLab check** ✅ 10-28 (agent, Note 9): Record Hop's Bloom 0.60 → Joybot "set the bloom to
      1.5" → 1.50 on the same look, reply named the look → one VisLab Undo → 0.60. All six audit checks done.
- [x] **Phone: Joybot in JoyBrush after the button-face change** (`147c19c00`) ✅ 10-28: the face button opens
      Joybot; the layer request above ran through the joybrush tools.
- [x] **Offline honesty** (found 10-28: with no internet Joybot waited a minute and blamed "the AI service"):
      `NetCheck` says "Your phone isn't online" at once, or "isn't reaching the internet" after a failure. Bench
      53/53; installed on the Note 9 10-28, but the offline wording itself is not phone-tested (an agent may not
      switch the phone's Wi-Fi off).
- [x] **Chat colours** (owner, 10-28): your own words in the quiet grey, Joybot's replies in the aqua-to-lime
      colour ✅ on the Note 9 10-28 (`tasks/joybot/evidence/chat_colours_20261028.png`). Answer cards with buttons
      (help, plans, results) stay grey for now: owner's call.
- [ ] **Phone, never tried:** the free-model fallback and borrowed eyes (F9); `add_media`, `relink_missing`;
      ducking on a music + voice project; the hands-free voice loop (needs the owner's voice: owner check).
      `list_items` ✅ 10-28 (agent, Note 9, free model): "list everything on the timeline of my last project"
      ran the tool and answered by kind in plain words ([evidence](../joybot/evidence/list_items_live_20261028.png)).
- [x] **STATE rows** HYG-5 asked for: Director mode, recipes, hands-free, Carry on; stale §6 rows refreshed
      (10-28: 63 typed actions + 61 older tools counted; JoyBrush hands row updated).
- [x] **"Draw a moustache"** (impossible requests). Fixed 10-28: an honest "I can't" is passed on in one step
      (`HelpBook.saysItCant`), never pushed or blamed on the model; a false "done" is still caught. Bench 53/53
      on the working copy (the task now takes 1 step, was 2). Not phone-tested (no tool path changed).
- [ ] `add_marker` stays off until timeline marks land with undo ([STU-3](STU-3_timeline_marks.md)); then switch
      it on and phone-test.
- [ ] OpenAI as a provider: untested (owner's account had no API credit). Owner action.

## Specs, handoffs and evidence
- [Handoff](../joybot/JOYBOT_HANDOFF_20261015.md) (10-17), [plan](../joybot/JOYBOT_PLAN_20261014.md),
  [feature gaps](../joybot/FEATURE_GAPS_20261015.md), [audit](../audits/2026-10-18/joybot.md) and
  [fixes](../audits/2026-10-18/joybot_fixes.md), [benchmark](../joybot/BENCHMARK_2026-10-24.md).

## Files and traps
- Owned paths: `app/.../ui/faditor/ai/**`, `ui/lobby/JoybotView`, `JoybotFilm`; small hooks only into
  `FaditorEditorActivity`, `LobbyFragment`, `VisLabActivity`, `JoyBrushActivity` (the last two belong to their leads).
- Never screenshot the chat settings with a key showing. Never type a real key yourself.
- Phone: claim the lock; never chain an install after a refused claim; the owner's watcher reinstalls on every
  save in the main folder, which closes whatever room is open mid-test.
