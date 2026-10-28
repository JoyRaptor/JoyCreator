# BOT-2: Joybot everywhere, and everything findable through him

**Milestone:** M1 (feeds M2)  ·  **State:** 🔨  ·  **Owner:** "JoyBot UX vision and capabilities" (Claude, account C)  ·  **Checked:** 2026-10-28 against `fbfa50ce4`

## Why it matters (for the user)
Wherever you are, tapping Joybot should offer the right next things and answer "how do I…" for that room.
Today he is strong in Studio and VisLab, thin in JoyBrush, absent in Avatar, and his help barely covers
the newer wings. For a newcomer, a feature Joybot can't explain is a feature that doesn't exist.

## Done when
- [ ] Every room that has a Joybot button opens him with ideas that fit that room (phone-checked per room).
- [ ] His help answers a question about every feature in STATE (with "Take me there" or "Show me how").
- [ ] Every Joybot control has a hover label.

## Where it stands (verified 10-28)
Room ideas exist for Studio, Lobby, SpriteLab, Library and VisLab (`IdeaEngine.collect`). JoyBrush opens the
chat as room JOYBRUSH but has no ideas branch (BOT-1 fixes the wrong buttons; richer ideas are here). Avatar
has no Joybot entry. HelpBook has ~60 topics; VisLab is one "visualizer" topic and JoyBrush two (open the
wing, open a drawing): nothing on looks, Words, MIDI, the floating visualiser, brushes, layers, frames,
Select, symmetry, Send to Studio. Hover labels: ~220 controls done 10-15; the chat's own new controls since
(account switcher, bring-in card, plan and review cards) were not re-swept.

## What is left
- [ ] **Help for every STATE feature.** Pure Java, harness-tested (`IdeaEngineTest`).
  - [x] VisLab (8 topics: the room, building a look and parts, music links, Words, MIDI/.mod, the floating
        visualiser, looks in Studio, the Studio effects) and Director mode: 10-28, from STATE 5.5/6; 422/422.
  - [ ] JoyBrush (brushes, paper, layers, frames and onion skin, Select, symmetry, Send to Studio): **wait for
        [HYG-2](HYG-2_one_main_line.md)**: most of it is on the JoyBrush lead's branch, not in the app yet, and
        help must never describe a tool the person doesn't have.
  - [ ] Capture, Library, Space, Trash, SpriteLab, the rest of STATE (after [HYG-5](HYG-5_state_sync.md)).
- [ ] **JoyBrush ideas** that read the open drawing (e.g. "Add a layer for colour", "Make a 12-frame loop",
      "Send this to Studio"), using `JoybotHands.describe()`.
- [ ] **Avatar room**: a Joybot button and ideas (Avatar stays hidden for launch: decide with ANI-1).
- [ ] **Hover-label sweep** of the chat screen and every card it draws.
- [ ] **Empty-state copy** the owner drafted 9-09 (no key: what Joybot does free; key: "no markup, watch
      your usage"). Check what the chat shows today first.
- [ ] **"What can I do here?"**: list the room's actions from the action registry, in plain words.

## Specs, handoffs and evidence
- [Feature gaps](../joybot/FEATURE_GAPS_20261015.md) (M3, G2, G3), [INBOX 9-09](../INBOX.md) (empty-state copy).

## Files and traps
- `ai/joybot/HelpBook.java`, `IdeaEngine.java`, `ChatAssistantActivity.java`; JoyBrush's button lives in
  `JoyBrushActivity.kt` (JoyBrush lead's file: ask first). Its icon is stretched ~57% wide: the fix
  `ef5fcd84a` on `claude/joybot-icon-aspect-20261027` is the JoyBrush lead's to land.
