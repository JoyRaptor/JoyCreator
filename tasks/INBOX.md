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

## Captured 2026-09-10 (idle power investigation)

- **The app holds ~534MB RES.** Measured on the Note 20 while investigating JoyRaptor's hot
  phone. It is **not** the heat source — idle JoyCreator draws 2.5–3.5% CPU, renders zero
  frames, and holds no wakelocks; the heat was Google Messages at 100–124% plus Samsung
  Rubin plus charging. But half a gigabyte resident on a memory-pressured device is worth a
  look on its own: find out what is holding it (thumbnail caches, waveform tiles, GL
  textures, decoded bitmaps) and whether any of it should be trimmed on
  `onTrimMemory`/background.

## Captured 2026-09-10 (SpriteLab session)

- **A character sheet is a more valuable asset than a project.** Reusable, portable,
  hand-editable, AI-extensible, small. Argues for a sheet library outside any project, and
  further out for a shareable character format (sheet + JSON + rig as one bundle).
- **A well-named cell has more uses than the person who named it intended.** `surprise` is
  an expression, an "O" phoneme, a reaction beat, and a blink-alternative. JoyRaptor has
  tested this: LLMs infer secondary uses unprompted. The value compounds with every AI tool
  added later. Specced in SPEC_20260910_SEMANTIC_CELLS — this line is the reminder that the
  *idea* is bigger than the feature and must not decay into "labelling".
- **Animated GIF / WebP preview per clip from SpriteLab** — so a clip can be checked on a
  phone or in a message without opening Joy Creator. Needs an encoder; none in the current
  zero-dependency build. Park until it earns a dependency.
- **Onion-skin anchor ghost** — pin one chosen frame as a permanent faint ghost so a walk
  cycle's feet can be kept planted against a fixed reference, not just against neighbours.
- **Sprite sheet from video** — sample N frames from a clip, background-key them, pack into
  a sheet. `SequencePacker` already packs a sequence into a grid sheet; the missing half is
  the sampler. Would make a sprite out of anything you filmed.
- **Joybot's own sheet is the first customer.** JoyRaptor is animating the AI-window mascot
  in SpriteLab right now. Whatever the tool cannot do for Joybot is the next feature.
- **Undo history in the SpriteLab project file** — deliberately excluded for now (reload
  starts clean). Revisit only if someone actually asks.

## Captured 2026-09-11

- **Desktop sprite editor as a web app on joycreator.cc** — build sprite sheets on a big
  screen with a mouse, then move the sheet plus its `.sprite.json` sidecar into Joy Creator.
  The sidecar is already documented in code as "the sharing format, the plugin contract",
  so the contract exists; this is a second client for a model that already ships.
  Open question the owner raised and preferred: land them in a **shared Assets library**
  rather than one project's folder, so a sprite is reusable across projects. That is the
  same question the vault answers — do not design a separate home for it.
- **Sprite sheet adjustment work** in the app is in flight alongside this.
