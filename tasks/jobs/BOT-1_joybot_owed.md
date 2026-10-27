# BOT-1: Joybot, what is still owed

**Milestone:** M1  ·  **State:** 🟡 most flows phone-tested 10-15 to 10-24  ·  **Owner:** the Joybot sessions (account C: "JoyBot UX vision and capabilities", "Free-model task benchmark for Joybot")  ·  **Checked:** 2026-10-27 against STATE §6 and the Joybot plan

## Why it matters
Joybot is how a newcomer finds and uses everything: "Siri, but in tune with the whole studio". For the
front door (M2) it is the guide, so it has to be reliable everywhere.

## Done when
- [ ] Every item below is phone-proven or explicitly parked by the owner.
- [ ] Joybot's help covers every STATE feature (depends on [HYG-5](HYG-5_state_sync.md)).

## Where it stands
Built and largely phone-tested: the idea engine and one-tap buttons, one undo per request, the slide wizard,
pictures, the help book, typed actions across Studio, the app, VisLab and JoyBrush, Director mode, locked
items stay locked, the `look` tool on the finished frame, multi-provider accounts, and a 53-task benchmark
(53/53 with the fake model). Details: [STATE §6](../STATE.md), [plan](../joybot/JOYBOT_PLAN_20261014.md) (ledger B6),
[handoff](../joybot/JOYBOT_HANDOFF_20261015.md) (last updated 10-17), [feature gaps](../joybot/FEATURE_GAPS_20261015.md).

## What is left (verify each against the plan's ledger before starting; some may be done)
- [ ] The VisLab phone check (the 10-24 audit was 5 of 6 green; VisLab was the one owed).
- [ ] Joybot drawing in JoyBrush: re-run on the phone after the button-face change (`147c19c00`); its
      first version was proven on the previous build.
- [ ] The benchmark against live free models (ready; needs the owner's OpenRouter key).
- [ ] Not phone-tested: the hands-free voice loop (needs his voice), the free-model fallback and borrowed
      eyes, add_media / relink / list_items, ducking on a music + voice project.
- [ ] `add_marker`: waits for timeline marks to land ([STU-3](STU-3_timeline_marks.md)).
- [ ] OpenAI as a provider: untested (the owner's account had no credit).
- [ ] Owner-requested, designed, not built: naming clips and layers ("Cat 1", "Cat 2"); placed images
      (reference images to picture models; the canvas half belongs to JoyBrush); the sprite sheet
      fly-to-corner and show-off moves (waits on the owner's labelled sheet).
- [ ] Joybot in the Avatar and Library rooms with their own ideas (SpriteLab done 10-15).
- [ ] A free "design a picture" (HTML/SVG) without a paid model.
- [ ] Hover labels on every Joybot button.
- [ ] Owner questions still on defaults: the default paid picture model, and whether to ask before paid calls.

## Files and traps
- Owned paths: `app/.../ui/faditor/ai/**`, `ui/lobby/JoybotView`, `JoybotFilm`. Small hooks into
  `FaditorEditorActivity` and `LobbyFragment` only.
- Never screenshot the chat settings dialog with the key showing.
