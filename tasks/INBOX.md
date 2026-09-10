# INBOX — ideas, one line each, no ceremony

Anything that comes to mind goes here. No format. No commitment. Nothing here is a plan
until it moves to `ROADMAP.md`.

**Rule:** never write a new `SPEC_*.md` for an idea. Write a line here. A spec is what an
idea becomes *after* it is scheduled, not before.

Triage: JoyRaptor and Claude sweep this together. Each line goes to ROADMAP, to STATE (if it
turns out it already exists), or gets deleted. Deleting is fine. That is what an inbox is for.

---

## Captured 2026-09-09 (from the launch-planning session)

- **Joybot sprite states** — the AI assistant gets an animated sprite with many looping
  states (idle, thinking, working, done, error, celebrating). Cycling or ping-pong frames
  so each state is a small loop, not a still. Multiple visual "tastes"/skins for him.
  JoyRaptor: "that should be a session" — deserves its own spec, not a bolt-on.
- **Joybot guides onboarding, then flies to his corner** — he walks the user through
  first-run and ends by taking up residence in the app so it's obvious he's always there.
  (Ergonomics note: bottom-right is thumb-reachable, top-right is not. Try both.)
- **Joybot's tour lives with him, permanently** — tapping him asks "what would you like to
  see?" with intent-shaped options ("record something" / "edit a video I have" / "show me
  the AI"), not feature-shaped ones. Available forever, not just on day one.
- **Demo project** — a small pre-loaded project (clip + caption + transition + chapter card)
  so a new user can press play and see it work in five seconds. Doubles as the source of
  the Play Store screenshots and the YouTube shorts.
- **New landing screen / default UI** — JoyRaptor: opening FadCam felt like "where do I go from
  here?" Joy Creator is a studio suite and should land accordingly. Its own session.
- **Funnel from recorder into Studio** — keep every FadCam power reachable, but present it
  as one product. Users should always get *more* with Joy Creator, never less.
- **Animated cycling word list on the intro screen** — the mechanic already exists
  (`RowFadeAnimator` in `OnboardingActivity`). Reuse, don't rebuild.
- **Multi-camera / remote camera over LAN** — a genuine FadCam power Joy Creator has not
  touched or promoted. Belongs in the intro copy and probably in a tour.
- **"Free up space" action** — show project sizes in the project list; let people reclaim
  space deliberately rather than being asked about it at import time.
- **"Link instead of copy" advanced setting** — buried, for power users only. Never a
  first-run question.
- **Video generation as the first post-launch feature** — the "look what landed" moment two
  weeks after the store clock starts, rather than a launch-day risk.
- **AI can send images/audio/files and receive images/video** — the full media seam, the way
  it would work using OpenRouter directly. Build the seam properly even if only part of it
  is switched on at launch.

## Captured 2026-09-09 (share-in and Joybot session)

- **Prompt packing** — before launching an external AI app, copy a packed prompt to the
  clipboard: the user's words plus canvas size, aspect ratio, "no watermark". Same idea as
  the slide contract's existing copy-prompt path. This is what makes share-in feel designed
  rather than manual.
- **Launch buttons for AI services** — ready-made for ChatGPT, Gemini, Claude, OpenArt,
  Copilot, plus "add your own". Prefer opening the installed app, fall back to the browser.
- **Joybot notices rather than interviews** — the first time you share something in from an
  app, he offers to keep a button for it. One tap, and it cannot be wrong about what you
  use because it saw you use it. Better than a setup questionnaire.
- **Chat empty-state copy (JoyRaptor's idea, drafted)** — no key: "I'm Joybot. Right now I can
  cut your ums, clean up your audio, build chapters, and write captions — all on your phone,
  free, no account. Want to upgrade my brain? Connect an AI model with the gear icon."
  Key connected: "Connected to [model]. Prompts, images and video cost whatever your
  provider charges — Joy Creator never adds a markup and never sees your bill. If you're not
  on a free model, keep an eye on your usage."
- **VERIFY (5-minute device test, before building video generation):** the July rule was
  "Media3 rejects video-only items *preceding* items with audio". It is a sequencing rule,
  not a blanket one, and it predates the layers work. Put a silent clip in front of a clip
  with sound and export. If it still fails, incoming generated video needs a silent audio
  track on import. If it does not, that whole normalisation step can be dropped.
