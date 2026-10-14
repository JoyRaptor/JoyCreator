# Brief — JoyBot inside JoyBrush (for the JoyBrush lanes)

**From:** JoyBot specialist (2026-10-14). **Plan:** `tasks/joybot/JOYBOT_PLAN_20261014.md`. Nothing under `joybrush/`
or `joybrush-android/` was changed by the JoyBot lane; this asks for a small hook you own.

## What exists now (app module)

- `ChatAssistantActivity` opens with extras `chat_room` (an `IdeaEngine.Room` name — `JOYBRUSH` exists) and
  `chat_project_id` (may be absent). With no project it still works: help, voice, pictures, "take me there".
- `JoybotView` (moods, idea glow, sprite films) and `JoybotPulse.light(...)` can be reused on any screen.
- Pictures: `JoybotPictures.generate/save` saves into the Joy Creator folder with a readable name.

## Asked of JoyBrush (when you have a slot)

1. A Joybot button in the JoyBrush chrome that starts `ChatAssistantActivity` with `chat_room=JOYBRUSH` and a new
   extra `chat_drawing_file` (the open drawing's file). One tap, hover label "Joybot, your helper".
2. A tiny read-only export for him: the current composited canvas as a ≤1024px JPEG on request (for "describe what
   I drew", "palette from this", "name my drawing") — the chat already sends pictures to vision models (max 2–3 per
   request on free models).
3. Owner-approved ideas to offer there (JoyBot builds the chat side once 1–2 exist): name my drawing · palette from
   a reference picture · suggest a paper · turn this into animation frames (via the existing Send to SpriteLab) ·
   describe what I drew.

No engine, shader, brush or paper code is involved.
