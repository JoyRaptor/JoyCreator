# STU-1: Studio phone-test backlog

**Milestone:** M1  ·  **State:** 🟡  ·  **Owner:** open (a careful phone agent; Sonnet is enough)  ·  **Checked:** 2026-10-27 against STATE §2

## Why it matters
Studio is the strongest wing, and the owner is happy with it. These are the features that are built but
nobody has driven on a phone. Built-but-unproven is where one-star reviews come from.

## Done when
- [ ] Each item below is driven on the Note 9 (sandbox), the result written here, and its STATE row
      moved to 🟢 (agent) or fixed.

## What is left (each was 🟡 in STATE on 10-27)
- [x] **Undo after closing and reopening:** quick edits keep their own steps after reopening, and a crash no **Agent-checked 10-28 on the Note 9: passes.**
      longer leaves the undo history behind the project (`b2a85dff7`). Test project "Redo test clip": three fast
      rotates, reopen, expect three steps; two edits, wait for autosave, force-stop, reopen, expect a matching history.
- [x] **Adjustment layers really change the layers beneath them**: agent phone-tested 10-27 by the VisLab
  lead on the Note 9 (an effect layer's Flash washes the clip beneath in the preview (visualisers, pictures, text and sprites sit above effect layers in both preview and export);
  Echo Trails and a music-linked Flash in exported videos). Fixed on the way: an export froze a layer's
  keyframed or music-linked values at the first frame. Keyframes themselves not re-tried on the phone;
  owner has not checked.
- [ ] **Bend on text** and **corner-pinned text with an effect** (export must match the preview).
- [ ] **Corner pin on sprites**, and sprites on the transform surface: nobody has dragged one on a phone.
- [x] **Single-frame export** (JPG/PNG): agent-checked 10-28, both formats land in the folder.
- [ ] **Audio ducking:** "Duck under…" from the lane menu, then export and listen. 10-28: on music-only lanes it says "no speech found" (by design); a voice lane is needed for a real test. The fade-in readout showed 24.41 s for a short drag: check.
- [ ] **VisLab in Studio:** the effects and Echo Trails were agent-tested 10-27; the owner wants to look (S12).
- [ ] **Transitions:** the owner wants to check them himself (S12). Prepare a test project with every transition type. 10-28: test project "Untitled audio 5" on the Note 9 has 9 transitions (Glitch, Push Left, Mirror Wipe, Wipe Left, Fade to White, Fade to Black, Crossfade, Radial, TV Channel); the 47 shader ones under "More effects" not yet placed.

## Specs, handoffs and evidence
- [STATE.md §2](../STATE.md) rows; undo detail in LANES history of the "Studio undo history drops steps after export" session (account C, 10-24).

## Files and traps
- Use throwaway projects only ("Restore video test", "Redo test clip"); never the owner's real projects.
- Phone lock and no `monkey`: see [AGENT_START.md](../AGENT_START.md).

## Code read 11-23 (hover-1 lane, no phone run)
- **Fade-in readout "24.41 s"** (`AudioDrawerTabs.fadeMsOf`): the slider maps squared (`f*f*maxFade`) onto 0..half the clip, so 24.41 s is exactly the slider's far end on a 48.8 s clip: a short drag that started at or reached the end reads the maximum. Not a wrong number by itself; whether the thumb sat at the end before the drag is the one thing to check on the phone.
- The rest of this card, the 41-check sweep and STU-6's on-clip checks are still owed (they need a long phone session of their own).

## Note 9 items 11-24 (hover-1 lane, build df093b915, "Restore video test")
- **Bend on text: preview PASS.** "hello 2" selected, Bend is on, the bend path and corner pin handles show and the letters draw curved (`tasks/screens/hover1-20261123/s1_text.png`). Export versus preview NOT compared (not exported): still owed.
- **Sprite transform surface: INCONCLUSIVE.** The sprite layer selects and shows the transform box with rotate handle (`s1_sprite.png`); one drag of its top-right corner by adb swipe changed nothing (`s1_sprite_drag.png`), and there is no corner-pin control in the visible toolbar. Not a pass, not proven a fault: the handle may need a slower press or the pin lives under another button. Owed.
- Not started: ducking with a voice lane, VisLab in Studio, the transitions project, the rest of the 41 checks.

## 11-24 second run (hover-1 lane): audio fade/ducking not reached
- Selecting the project's audio lane and tapping Volume opens a volume bar (113%) with a Keyframe stopwatch, a mute and a close; the fade-in and fade-out sliders and "Duck under" were not found in two taps (they are on another route, probably a drawer opened from the lane or its menu). I toggled Keyframe on and back off, closed it, and changed nothing in the project. Shots `a1.png`-`a3.png`. Ducking and the fade readout remain owed; whoever takes this next should look for the audio drawer first (`AudioDrawerTabs`).

## 11-24 third run (hover-1 lane, Note 9, build df093b915)
- **How to reach the audio drawer:** HOLD (about a second) the audio clip in the timeline; a double-tap by two adb taps did not register. The drawer opens with Level, Pan, In and Out fades, and tabs A/V Sync, FX, Clean (`b3.png`).
- **Fade-in readout: PASS.** A 60 px drag of the In slider from 0 reads 195 ms and draws the fade ramp on the clip (`b4.png`); the slider is squared (`fadeMsOf`), so 24.41 s is only the far end of a 48.8 s clip. I undid the change afterwards (Undo then Redo shows 49 / 1).
- **Ducking: not reachable on this project.** The lane menu on the main track offers only Solo (`b6.png`); Duck under needs a second lane carrying a voice (`duckKeyCandidates`), which this project does not have. Needs a project with a voice lane: owed.
- Still owed: sprite corner pin, transitions project, VisLab in Studio, the rest of the 41 checks, STU-6 on-clip checks.

## 11-24 sprite corner pin, a correction (hover-1 lane)
- My earlier corner drag on the sprite box (`s1_sprite_drag.png`) **did register**: Undo then Redo name the step "Distort sprite" (toast "Redid: Distort sprite", `c1.png`). So the sprite corner pin is wired and undoable. What the drag did to the picture is not proven: the preview looked the same in that shot (the sprite may be off screen at 0:00). Verdict: undo step PASS, visible distortion and export match owed. The test project now carries that Distort sprite step; it is a throwaway project.

## 11-24 transitions project not on the phone (hover-1 lane)
- The Library search "Untitled audio" on the Note 9 finds Untitled audio, 2, 3 and 4 (4 has three layers, 0:05) and **no "Untitled audio 5"**: the 9-transition test project named above is gone from this device (`d0.png`). A new test project with two or more clips and every transition type has to be built (Add two clips, then each cut's transition) before the transitions check; not done.
- VisLab in Studio, ducking (needs a project with a voice lane: add a voice clip), jobs 17 and 18: not started.

## 11-24 a transitions test project made on the phone (hover-1 lane)
- **How:** lobby Project, Browse Device (system chooser: Files, Just once), picked `stu-b3-talk.mp4` from Downloads (a session test file, 18 s, no real voice) as new project **"Untitled 7"** (`e4.png`); Duplicate gives two clips (0:36); with a clip selected the toolbar's **Transitions** opens the picker (Crossfade, Fade Black, Fade White, Wipe, Radial, then More effects; `e8.png`).
- **Radial placed on the cut: PASS for placement.** A blue transition block sits on the joint and the undo count rises (`e9.png`). Not a visual pass: both clips are the same frame-counter picture, so a wipe between them cannot be seen; use two different clips to see each transition draw. The other 8 listed types and the 47 shader transitions under More effects: not tried.
- **Ducking:** this clip has no voice; ducking still needs a project with a real voice lane.
- Still owed: VisLab in Studio, the rest of the 41 checks, jobs 17 and 18.

## 11-24 transitions project played and exported (hover-1 lane, Note 9)
- **Reopen: PASS.** "Untitled 7" reopens with its Radial transition kept (undo count 2, `f0.png`).
- **Play: PASS.** Playback runs; the frame counter at 4.17 s reads frame 127 (30 fps, `f1.png`), no hitch seen. The transition itself was not seen drawing (identical clips).
- **Export: PASS.** The export sheet reads 00:35, 2 clips (36 s minus the 1 s transition overlap), Export Now ran to 99% Finalizing then "Export Complete", saved to the Joy Creator folder (`f2.png`, `f3.png`, `f4.png`). I did not play the exported file back, so export-versus-preview of the transition is not compared.
- Still owed: VisLab in Studio, ducking with a real voice, a transition between two different clips, the other transition types, STU-6 on-clip checks, jobs 17 and 18.

## 11-24 playing "Restore video test" (hover-1 lane, Note 9, build df093b915)
- **Stained Glass on a clip: PASS in the preview (STU-6).** At 3.1 s the adjustment layer I added earlier turns the cat clip into stained-glass panes with dark lead lines; the text, sprites and pictures above it are untouched, as designed (`g2.png`; before it, `g1.png` at 0:00 shows the plain clip). No refusal, no crash, playback kept running. Frame time and export versus preview are not measured.
- **VisLab in Studio: partly seen.** The VIZ lane plays under the picture and a faint dotted cyan line draws along the lower picture (`g1.png`, `g2.png`); its look and the Echo Trails settings were not examined here (only agent-tested 10-27, and the owner has not looked, S12).
- Still owed: ducking with a real voice, other transition types between different clips, the other 11 STU-6 effects on a clip, frame times, jobs 17 and 18.

## 11-24 ducking attempt: voiceover (hover-1 lane, Note 9)
- **Method that is safe:** `uiautomator dump` on the Studio screen lists every control with its content description and id (this also shows the hover labels are in place: Undo, Play or pause, Record voiceover, Export, AI Assistant ...). A helper that finds a control by text or id and taps its bounds worked well in Studio; the lobby is a custom-drawn screen with no text nodes, so only the fixed "Carry on" button position is usable there, after a screenshot.
- **Record voiceover (mic button, id `btn_voiceover`): starts with no permission prompt**, the toast says playback is muted to prevent feedback, and the timeline keeps running (`i1.png`). Stopping with the play button ended it, but **no voice clip appeared and the undo count stayed 49** (`i2.png`): a room with no speech records nothing worth keeping, so ducking still has no voice lane to duck under. A real test needs spoken words into the phone mic, or a voice clip imported.
- Corner pin picture check, jobs 17 and 18 (puppet tab, lanes pills, caption motion): not done.
