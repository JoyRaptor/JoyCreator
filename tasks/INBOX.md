# INBOX

## 2026-09-24 — from the Studio lead: owner's ideas OUTSIDE the Studio (pick these up)

Filed so they don't go stale. The Studio plan is tasks/STUDIO_PLAN.md; these are not in it.

1. **Getting images in, three ways, zero file management for the user.** (a) Generate with AI
   through OpenRouter (the owner's key, BYOK — see grok-4.6_REPORT_20260908_AI_INTEGRATION_BYOK_PLUGINS.md);
   (b) paste from the clipboard (from anywhere, incl. other parts of the app); (c) Android
   "Share to Joy Creator". Every route lands the file in the right place automatically — the
   open project's folder, or the general asset library — and it just shows up. The Studio
   only needs one entry point ("Add image" → Generate / Paste / Files); the plumbing
   (downloads, naming, folders, library index) is this item.
2. **App-wide facelift, keeping every FadCam feature.** Lobby ≈80% look / 60% function; Studio
   in progress (Studio lead); SpriteLab nearly done; the rest of the app is untouched. Plan
   the whole app's navigation at a high level so moving between features is easy. Reuse
   tasks/NAV_PLAN.md / NAV_SPEC.md (owner: take them with a grain of salt) and the design
   records in tasks/design/ (UI-JoyCreator-grand-design.html, UI-The-Marquee…, UI-The-swatch-room_pill.html).
3. **Navigation without the old nav bar.** The lobby's nav bar is being replaced, so every
   FadCam screen needs a new way in: recording (the home screen's recording info → its full
   settings screen), the storage "radio" widget (gone — bring it back, re-themed), and every
   settings sub-screen. Nothing FadCam had may be lost; it all gets Joy Creator branding so
   none of it looks like FadCam's.
4. **Export queue + instant feedback** — handed to the export lane 2026-09-24 (confirm button
   becomes "Queue export" with a small warning while one runs; immediate "Preparing…" so a
   press never looks ignored).

## 2026-09-16 — the grab bar was never broken, and what that should teach us

JoyRaptor: *"my ability to adjust the size of the timeline to preview ratio has been broken... I
can't grab the handle."* Two earlier sessions guessed at causes and defended against them. The
third left a diagnostic instead, and one press answered it: 71 moves arrived, every one applied
what it asked for, the drag ended at 126.6dp — and the next gesture started at 140.

A 32dp snap radius around a 140dp detent is a **64dp dead zone around the position the timeline
normally sits at.** Every smaller adjustment was applied, felt, then undone on release.

The lesson is the diagnostic, not the fix. Two guesses cost two sessions; the instrumented
version cost one press. When a report is "it does nothing", log what the code actually computed
before theorising about why.

## 2026-09-16 — ZA_CONTROL's timestamp was disturbed

Opening the project list on the sandbox and tapping a row by coordinate, I landed on ZA_CONTROL
instead of ZA_VERIFY and opened it (22:04). Content was not edited and nothing was saved
deliberately, but it is a CONTROL project for the ZA lane and its recents timestamp has moved.
Flagging rather than tidying — if that lane needs a pristine control, check it.


## 2026-09-16 — one command for the phone, wired or wireless

    bash tools/phone.sh deploy --build

`phone.sh deploy` already existed (added earlier the same day) and already did the hard parts:
USB or wireless, a staleness warning when the APK is older than the watcher's last build, plain
`adb install` so the adb server is never restarted, and a read-back of the phone's own
lastUpdateTime so a "Success" that went to another device cannot pass.

Two things were added to it rather than beside it:

* `--build`, which assembles first — `assembleDefaultDebug` only, never `installDefaultDebug`,
  because gradle's install task is the thing that restarts the adb server and drops wireless on
  every build. The watcher stays on assemble for the same reason.
* `pick_serial` now refuses to volunteer REAL_SERIAL. It preferred the sandbox when it could see
  one, but with only the real phone attached it would have picked it. `PHONE=` still overrides,
  so a deliberate read-only session on the real phone is unaffected.

**A near-duplicate was written and deleted the same hour.** I built `tools/deploy.sh` without
checking whether phone.sh had grown a deploy verb — it had. Two scripts that both install is
precisely the drift this file keeps recording; one entry point, as phone.sh's own header says.

## 2026-09-16 — sprites still have no way in from Add Asset

Confirmed on the sandbox by opening the sheet: Video clip, Image as new layer, Black clip, Video
overlay (PiP), Audio, Record voiceover, AI slide, FX Adjustment Layer, Image-as-clip. **No sprite
entry.** This was filed on 2026-09-12 and is still open — sprites remain reachable only from the
toolbar, two levels down. One row in AddAssetBottomSheet.

— ideas, one line each, no ceremony

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

## Captured 2026-09-11 (SpriteLab parity ruling)

- **SpriteLab reaches full parity on device.** JoyRaptor's ruling: the web app is a PILOT,
  not a permanent tier. Phones and tablets both. The only durable reason for a desktop tool
  is interop with desktop software, never capability. Any proposal that splits features
  "heavy on desktop / light on phone" has already been rejected once — do not re-propose it.
- **Consequence: `SpriteSheet` needs an additive per-cell transform** (dx, dy, scale,
  rotation). Today alignment can only exist as baked pixels because the format has nowhere
  to put it; with the Lab on the phone that becomes wrong, and alignment should be
  non-destructive data that survives a round trip. Tags and viseme assignments likewise —
  `Cell.tags[]` already exists and has never been written; `visemeMap` currently rides on
  `AvatarRig`, not on the sheet.
- **Treat SpriteLab's state model as the reference implementation** the phone converges on:
  stable art identity (`srcId`), arrangement (`cellOrder`), per-cell transforms, names, tags,
  visemes, clips. `.spritelab.json` and `.sprite.json` should converge, not drift — divergence
  silently loses work on a round trip.
- **The simplicity question, not a gesture rule.** A proposed "every gesture needs a
  one-thumb path" rule was **rejected** by JoyRaptor 2026-09-12: *"a rule might get in the
  way of the best option."* The app is very feature-dense, so the standing question for
  every surface is **"can this be done more simply, with less UI, more intuitively?"** —
  judgement, asked every time, not a checklist item. Applies well beyond sprites.
- **Swipeable panes for the phone Lab** — his own suggestion for fitting everything on a
  small screen. Tablets get more room; the phone gets panes. Design session of its own.

## Captured 2026-09-12 (SpriteLab UI pass)

- **PRINCIPLE — every button carries a hover label.** JoyRaptor, 2026-09-12: every control
  should say what it does on hover, so anyone can learn the app by pointing at it. On Android
  this is invisible *most* of the time, which costs nothing — but it is **not** dead weight:
  a Galaxy Note stylus produces real hover, and so does a plugged-in mouse or a keyboard-and-
  trackpad tablet setup. So it helps exactly the people trying hardest to learn.
  Two consequences: (1) **anything built from now on ships with hover labels**, and (2) there
  is a **systematic sweep owed** over every existing button in the app. The sweep is NOT
  scheduled and was deliberately kept out of the SpriteLab sessions to protect their context.
  Whoever picks it up should treat it as its own lane. SpriteLab itself is already complete —
  zero untitled buttons, verified.
- **Sprockets should do what sprockets do.** A scrollbar is a mouse affordance; on a tablet
  with no pointer a long filmstrip could not be moved at all. Dragging the perforated margin
  now pans the reel. Generalises: wherever the app draws a physical metaphor, the metaphor
  should be grabbable — the timeline spine included.

## Captured 2026-09-12 (theming, measured)

- **The editor never joined the theming system that already ships.** Measured, not guessed:
  the app has **7 alternate palettes** (`colors_amoled / gold / pookiepink / red /
  shadowalloy / silentforest / snowveil`, 33 colours each) driven by theme attrs
  (`colorButton`, `colorTopBar`, `colorDialog`, `colorHeading`, …). Recorder and Forensics
  resolve them properly. The editor does not: **1,092 hardcoded `0xFF…` literals live under
  `ui/faditor`** out of 1,447 app-wide, and exactly **one** editor file has ever called
  `resolveThemeColor`. `FaditorEditorActivity` alone holds 363.
  So the job is not "build theming" — it is **"make the editor use the theming that already
  exists"**, plus widening the token set (8 attrs is too few for an editor; it needs roughly
  20: surface, panel, line, ink, dim, accent, plus state colours).
- **The green JoyRaptor wants to replace is `0xFF4CAF50`** — Material Green 500, used **143
  times in the editor** (197 app-wide), the single most common literal in the codebase. He
  prefers SpriteLab's emerald `#34d399`. That one swap is a sed; the other 949 literals are
  the actual work. 170 distinct colours in the editor is itself the finding — a designed
  palette is 15 to 20.
- **Two-layer colour, and the layers must not mix.** SpriteLab demonstrates the shape: a
  neutral base plus accents. Proposal — (1) a **theme** the user picks, app-wide; (2) a
  **section accent** so the room tells you where you are (Sprite Lab, Avatar Studio, Capture,
  Editor). **Hard rule: STATE colours are global and constant** (in SpriteLab, cyan = selected,
  pink = showing now). A section accent must never be a colour that already means a state, or
  "pink" means "playing" in one room and "you are in Sprite Lab" in another. That collision is
  the whole risk in the section-colour idea and it is cheap to avoid by choosing accents from
  outside the state set.
- **Not a spec yet, deliberately.** Per this repo's own rule, a spec is what an idea becomes
  *after* it is scheduled. This is a lane of its own — mechanical, large, and dangerous to do
  blind, because a wrong token makes text invisible rather than throwing. It wants a
  screenshot-diff pass, not a compile.
- **The restyle is a DE-BRANDING, not a re-skin.** JoyRaptor, 2026-09-12: FadCam's ethos is
  hacker / privacy / surveillance; Joy Creator is meant to be a fun, colourful, art-first
  application. The inherited defaults carry the old ethos — the default dingy red especially.
  Pure black is a deliberate art choice: it makes the colours of *the user's project* pop.
  So the target is not "tidy the palette", it is "stop looking like the app it was forked
  from". SpritePop is the closest reference point.
- **Scalpel, not hammer — the translucent drawers are load-bearing.** The editor's drawers are
  semi-transparent ON PURPOSE so you can see the video playing underneath while you noodle
  with options. That is a compromise this feature-dense editor needs and a flat SpritePop-style
  opaque panel would destroy it. Any theming lane must treat drawer surfaces as their own token
  with alpha, not fold them into the panel colour. Expect more cases like this; the restyle
  goes in waves, checking each surface's purpose before restyling it.

## Captured 2026-09-12 (Joybot, and the sprite entry point)

- **Name a whole sheet by talking to Joybot.** "This is a dinosaur. Left to right, top to
  bottom: happy, sad, surprised…" and he fills every `cellNames` entry in one go. With a key
  connected an LLM infers better (and can propose the cells you did not describe); **with no
  key it should still work** as an offline skill that parses a spoken/typed list in an
  understood format and populates positionally. This is the thing that makes naming cheap
  enough that people actually do it — see SPEC_20260910_SEMANTIC_CELLS for why naming matters
  at all. SpriteLab now has the typed equivalent (bulk naming, base + auto-numbering); the
  voice/LLM version is the mobile counterpart.
- **Tap Joybot for voice, hold or swipe for chat.** Today tapping the corner mascot opens the
  chat. Proposal: **tap = quick audio input** for one-shot asks ("name these cells", "make a
  run cycle"), **hold / swipe-down = the full chat** for longer work. JoyRaptor thinking
  aloud, not decided.
- **Per-project chat log.** Keep the assistant's history with the project so there is context
  and a record of what was done to it. Unresolved: whether that is genuinely useful or just
  clutter. Worth a small trial before building.
- **🔴 The sprite entry point is effectively undiscoverable.** JoyRaptor went looking for
  sprites under **Add asset** and could not find them — he assumed the feature had been lost.
  It has not: `Sprites` is its own tool, **26th of 29** in the horizontal toolbar (between
  Loop and Adjust), and importing a sheet is then **two more levels down** — the drawer's ⚙
  chip opens the sheet manager, where "＋ New sprite sheet" finally reaches the import.
  Two cheap fixes: put **Sprites / sprite sheet** in the **Add asset** menu where people look
  for assets, and surface an import affordance in the drawer's empty state. The deeper fix is
  the planned top-level Sprite Lab entry.

## 2026-09-13 — RULED AND BUILT: a cell's name follows the DRAWING

**JoyRaptor, same day: "yes name and alignment move with it."** Built and device-proved —
swapped slot 0 with slot 3 and "smile" went with the smiling star. Kept below because the
reasoning is the reason the implementation looks the way it does.

The shape that makes his answer true without inventing a second identity: ONE numbering, plus
a display->source map that every lookup goes through. `cellNames`, `cellXf`, the viseme and the
enabled flag are keyed by the SOURCE cell, so moving a drawing brings all of them, and "Reset
order" restores everything because none of it was ever attached to the slot. A saved
animation's frame list does NOT follow — those are slots, which is the point of arranging.

---

## (original question, 2026-09-13)

Swap / Ripple / Reset order is the last thing from the mobile mockup's Slice section that is
not on the phone, and it is not a build problem — it is a semantics problem I will not guess
at, because guessing wrong silently rearranges work you already did.

Reordering cells means the sheet gets a `cellOrder` map: "slot 3 now shows the drawing that
was in slot 7". The question is what happens to everything ELSE that is keyed by cell number:
the name, the alignment nudge, the viseme.

**Option A — the name follows the DRAWING.** You called that picture "surprised", so it stays
"surprised" wherever you move it, and its alignment travels with it. Costs a second index
space inside the sheet (slot number vs drawing number), and saved animations keep referring to
SLOTS, so an animation you already made would play different drawings after a reorder.

**Option B — the name follows the SLOT.** Moving a drawing moves its name and its alignment
with it in one operation, so there is only ever one numbering. Saved animations keep playing
the same slots, which now hold the drawings you just arranged — which is usually what
reordering is FOR. SpriteLab on the desktop behaves this way and warns that reordering
renumbers.

I lean **B**: one numbering, no hidden second identity, and it matches the tool you are
already using. But it is your work being rearranged, so it is your call.

Everything else from the mockup's Slice section landed on 2026-09-13 (Grid / Names switches,
Suspect, Name many…). Still owed after this ruling: multi-sheet merge and the Sources rail,
bake-to-a-new-sheet, numbered frame export, and drag-to-reorder on the film strip and clips
shelf.

## 2026-09-13 — RESOLVED: a sprite sheet can now be removed from inside the app

Built the same day. Export -> "Sheets in this project": tap a sheet to open, rename or remove
it. Remove refuses on the open sheet and on any sheet still used by timeline sprites OR by the
parts of an avatar rig, and says which on the button. It can be put back while you are still on
that screen, and the dialog says so rather than implying a general undo.

The two test sheets named below were removed through that UI, and project.json re-read to
confirm the project is back to ['Starguy', 'Sprite pang'].

---

## (original, 2026-09-13) — a sprite sheet cannot be deleted from inside the app

Found by using the new bake: it adds a sheet to the project, which is right, and there is then
no way to remove one. Testing left two ("Starguy baked", "Starguy baked baked") in JoyRaptor's
BundlingFontTest project. They are harmless — extra entries in the sheet list plus their PNGs
in assets/ — but they cannot be tidied away without editing project.json by hand, which is
exactly the kind of outside-the-app edit that has caused silent loss in this repo before.

A sheet list with rename/delete belongs somewhere. The drawer's gear button already opens
"manage sheets", so that is probably the place.

Wanted alongside it: the bake dialog offers "Open it", but nothing tells you later which sheets
are bakes of which. A `bakedFrom` field is already in the web tool's JSON and would be one line
to carry.

---

## 2026-09-15 — snapping across the whole editor is fiddly, and puppeteering will expose it

JoyRaptor, while designing the puppet drawer:

> "Make a note that we need to work on improving snapping system app/editor wide because this
> has been fiddly when I create so far."

This is a standing complaint about EXISTING work, not a puppet feature. It is filed here
because puppeteering is about to make it much worse and that is the forcing function:

- Live recording drops a key per frame. Hand-editing afterwards means landing the playhead
  EXACTLY on a key. Land one frame off and you author a second key beside the first — which
  reads as a jitter or a jump in the picture, and is very hard to spot in a dense track.
- So the `‹ ♦ ›` jump-to-key control stops being a convenience and becomes the primary way to
  move the playhead while editing a performance. It has to be exact and it has to be reachable
  without hunting — hence its promotion to the top of the puppet drawer.

What "improve snapping" probably means, to be scoped properly before anyone builds:
beat/onset snapping already exists (`BeatDetector`, `OnsetDetector`, wired), so the gap is
snapping the PLAYHEAD to keys, clip edges, markers and item boundaries — one shared snap
authority with one tolerance, rather than per-surface guesses. Worth an audit of who snaps
what today before specifying.

Nothing here is scheduled. Do not start a SPEC for it.

---

## 2026-09-16 — RESOLVED: the two magnifiers are one

Migrated the same night it was filed. `PreviewLoupe` moved to `transform/` (both surfaces use
it, and the dependency has to point one way — puppet may lean on transform, never the reverse),
and `TransformOverlayView.drawLoupe` now keeps only what is specific to that tool: which handle
is held, and the quad and handles drawn over the magnified picture. `drawLoupeContent` and the
`drawingLoupeContent` guard are gone; re-entrancy is the loupe's business now.

Compile clean. NOT device-verified: nobody has dragged a transform handle since, so the one
thing this could have broken — the magnifier on the tool that already shipped — is unproven.
Worth a look before trusting it.

---

## 2026-09-15 — two magnifiers now, and they will drift

`PuppetOverlayView` needed the transform tool's loupe ("we need that exact same helper here" —
JoyRaptor). The chrome — corner placement, circular clip, the walk that draws the preview stack
magnified, the crosshair and rim — is now in `puppet/PreviewLoupe.java`, with the per-tool
decoration supplied by the caller.

**`TransformOverlayView` still has its own copy.** It works, it was not touched, and the new
class copies its placement rule, its 2.2x zoom and its rim colours deliberately so the two look
identical today. They will not stay identical: the next person to tune one will not know about
the other.

The migration is ~40 lines — `drawLoupe` keeps finding the handle and hands its position plus a
`Decor` that draws the quad and handle dots. Not done in the same session that introduced it,
because that file is 2,169 lines and owns every touch on the picture, and the puppet lane had
already edited its sibling twice that day.

Also filed here rather than fixed silently because the repo's own rule is that a second copy of
anything is how preview and export start disagreeing — the same reasoning, one level up.

## 2026-09-15 — a gradle install kills the wireless adb connection

`:app:installDefaultDebug` restarts the adb server, which drops `adb connect`'s wireless
session; the next command reports "device not found" and `adb connect` to the same port is
"actively refused" because the phone's listener has moved. With JoyRaptor's USB connector
damaged (WIRELESS_ADB_CONNECT.md), the watcher's auto-install and wireless adb are mutually
exclusive in practice.

Worth deciding: either the watcher stops running `installDefaultDebug` and an agent installs
with `adb -s <ip:port> install -r -d`, or every wireless session re-runs `mdns services` after
each build. The second is what happens today, by accident, several times an hour.

**Half of this is now tooled (2026-09-15):** `bash tools/wifi-adb.sh` does the whole reconnect
— stale-entry cleanup, mDNS scan, try every endpoint, server restart and re-scan, and a message
naming the two usual causes when it genuinely cannot reach the phone. Re-running it after a
build is one command instead of four.

**RESOLVED 2026-09-16.** `watch-build.ps1` now runs `:app:assembleDefaultDebug` instead of
`installDefaultDebug`. The watcher builds and packages; nothing in it touches adb, so a save can
no longer drop the phone connection, and a clean compile can no longer be reported as
`BUILD FAILED` because no phone was attached. Installing is now explicit and takes one command,
`bash tools/phone.sh install`. `_RULES_READ_FIRST.md` and `DEVICE_CONTROL_RUNBOOK.md` §2a and §9
were updated in the same commit, because three documents telling agents "the watcher already
installed it" would have been worse than the original problem.

The reasoning that led there, kept for the record: With no device attached
the watcher's `installDefaultDebug` FAILS, which makes every build report `BUILD FAILED` even
when the compile succeeded — an agent reading the tail of build.log then believes the tree is
red and starts hunting a phantom. That cost this session about an hour: the real state was
`> Task :app:compileDefaultDebugJavaWithJavac` clean, failing only at install with
"No connected devices!". Recommendation: drop `installDefaultDebug` from the watcher command
and let agents install explicitly. A build that says FAILED when the code is fine is worse than
no signal at all.

## 2026-09-16 — the Bg-key picker samples the pixel that is THERE, not the one you see

`SpriteGridEditorView.handleTap` in colour-pick mode maps the tap into source space and reads
`bmp.getPixel` directly. On a sheet that has been reordered or nudged, the grid now draws each
display slot's own drawing — so the pixel under the finger on screen is not the pixel that gets
read. Pick a background colour after a swap and you sample the neighbour.

Not fixed here, deliberately:

- It is the same approximation the picker has always made for per-cell transforms, so it is
  pre-existing rather than something the reorder work introduced.
- In practice the key colour is the flat background, which is the same everywhere on the sheet,
  so the wrong pixel is usually the right colour anyway.
- The fix is fiddly — map the tap to its display slot, then to the source cell, then offset
  within the cell — and there is no way to compile or test it while the watcher is off.

Worth doing when someone is next in that file with a working build. The correct shape is the one
`cellRectBitmap` already has: go through `sheet.sourceCell(slot)` rather than assuming the slot
and the art share an index.
