# AVA-1: Avatar Studio redesign (a real character studio, simple on a phone)

**Milestone:** after launch (Avatar stays hidden for launch, see ANI-1)  ·  **State:** 🔨 web pilot built, owner's look owed  ·  **Owner:** "Avatar Studio" session (Claude Fable 5.2, owner-asked 11-18)  ·  **Checked:** 2026-11-18 against `ffb3af63b`

## Why it matters (for the user)
The owner opened Avatar Studio as a newcomer and could not tell what it does: a 3x3 grid, yaw and pitch,
"part", and taps that sometimes did nothing because a cell was or was not armed. He wants one room where a
whole character, or only a face, is rigged with everything the app already has, more powerful than
Character Animator, Cartoon Animator and Moho for this job and far less tedious, and droppable into Studio.

## Done when
- [ ] The owner has tried the web pilot with his own Topps file and said what to keep and change.
- [ ] The agreed design is written as the phone spec (screens, the character file, the port map).
- [ ] The phone room is rebuilt to it, reusing the engines named in the pilot's README, and phone-proven.
- [ ] A character made there drops into Studio with its take and still takes a hand-drag there.
- [ ] STATE.md rows updated at each of those steps.

## Where it stands (11-18)
- **Web pilot built** in `tools/avatarlab/` and published as a private Claude artifact. Agent-checked in the
  in-app browser at phone size (411x846) and sideways (846x411); not on a phone, not tried by the owner.
- **Topps opens** (the owner's Character Animator PSD, 191 layers, read by a new dependency-free PSD reader):
  rigged from layer names alone into a head of 7 angles, eyes that follow and blink, a mouth on a hinge with
  "smile" as a button, two arms of 8 drawings each (the right arm's behind-the-body drawings folded into the
  same fan, keeping their place in the stack), a breathing chest, planted legs and a swinging tail. All 48
  arm pins and the mouth hinges were guessed from the pixels.
- **Checked by script in the browser:** record a take, record a second pass that replaced only the hand,
  replay, save the character with its take, reopen it (140 drawings, 60 frames), bake a 5x5 sprite sheet with
  a Sprite Lab sidecar, undo a pin move.
- **Four ways in, each run once:** PSD (Topps), a sheet (tap the parts: body, head and a 7-drawing arm became
  a working character with no pin placed), one whole drawing (7 taps, then it bends), and Pip the built-in.

## What the pilot settles (proposed, the owner rules)
1. First screen: a dotted body with a + on every part, and three doors for bringing a whole character in.
2. Two modes with one meaning each: **Build** (everything still, pins draggable) and **Perform** (everything
   alive). No arming. "Try it" brings the stage to life from inside a part's card.
3. A body map to say which part; one card per part that says in a sentence what the part does.
4. Head angles are dots on a pad (any number, anywhere, mirrored when a side is missing), not a 3x3 grid.
5. Arms: several drawings swap to the nearest hand and bend the rest of the way; pins are guessed.
6. Mouth: a hinge, a squash, or shapes picked by the voice; other mouths are buttons.
7. The whole character can turn around when the head turns the other way.
8. Takes are layered: a second pass replaces only what you touch.

## What is left
- [ ] Owner tries the pilot (PC page with Topps; the artifact on the phone with Pip) and rules.
- [ ] Not built in the pilot: clipping layers and blend modes from the PSD (Topps uses neither); eye whites
      that mask the pupil; legs that bend and walk cycles; hands tracked by camera; a motion library; saving a
      video; per-drawing art nudge for a mouth that sits wrong at one angle is manual (Move art).
- [ ] Not checked: face camera with a real face; microphone with a real voice; any phone.
- [ ] Topps notes for the owner: "smile" sits off the face at Left Profile, Left Quarter and Frontal in the
      file itself (it is aligned at Right Quarter); the Upward mouth is hidden in the file and stays hidden.
- [ ] Then the phone spec and the port. Engines to reuse are listed in `tools/avatarlab/README.md`.

## Specs, handoffs and evidence
- Pilot and its README: `tools/avatarlab/`. Earlier plan: [PLAN_AVATAR_STUDIO.md](../PLAN_AVATAR_STUDIO.md).
- Related: [ANI-1](ANI-1_puppets_and_sprites.md) (puppets, sprites, the launch decision for Avatar),
  JoyBrush boards JB-7.01 to 7.04 (draw a character in JoyBrush and send it here).

## Files and traps
- New files only: `tools/avatarlab/**`. The owner's PSD is never committed (`tools/avatarlab/local/` is ignored).
- The pilot's solver is the reference for behaviour, not a second engine to ship: the phone reuses
  `PuppetPoseResolver`, `PinWarpStrip`, `FabrikSolver`, `DangleSim`, the puppet mesh engine and the tracking bus.
