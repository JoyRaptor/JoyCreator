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

## 11-24 audio ducking: PASS in the editor (hover-1 lane, Note 9, build df093b915)
- **How:** a spoken test file was made on the PC (Windows speech synthesis, 18 s of counting and a sentence), pushed to the phone's Downloads, and added with Add, Audio, Files, Just once (`j1.png`-`j3.png`). Hold the audio lane's header to open the lane menu: Move up, Move down, Solo, **Duck under...** (`j4.png`); it asks "Duck under which lane?" and lists the two Audio lanes (`j5.png`).
- **Result:** choosing the lane that holds the music gave the toast "Nothing to duck under - no speech found in that lane" (`j7.png`, by design); choosing the voice lane gave **"Ducked - 17 keyframes from levels"** and a cyan volume envelope with dots now dips under the speech on the audio lane (`j8.png`). The test project "Restore video test" keeps that envelope and the voice lane (throwaway).
- **Not done:** listening to the ducked result in an export; "transcript" as the speech source.
- Still owed: corner pin picture check, jobs 17 and 18, other transition types, STU-6 on-clip checks for 11 effects.

## 11-24 corner pin picture check and snapping feel (hover-1 lane, Note 9)
- **Corner pin: still not proven.** With the sprite lane selected, its box and four corner squares show (`k1.png`); an adb swipe of the lower-right corner (700 ms) changed nothing on screen (`k2.png`, same as `k1.png`), twice now. An earlier drag did leave a "Distort sprite" undo step, so the control is wired; a swipe that starts on the handle does not move it reliably. A finger or a scripted touch with a press-and-hold is needed.
- **STU-5 snapping feel: not checked.** Whether a drag snaps "as before" is a feel judgement that scripted swipes cannot give; it needs the owner's hand or a recorded side-by-side with the old build.
- Jobs 17 and 18: not started.

## Owner's hands-on list (things an agent cannot judge or drive; added 11-24)
1. **Corner pin on a sprite by dragging a corner:** three adb swipes (300, 700 and 1400 ms) on the lower-right corner of the selected sprite's box moved nothing on screen (`k2.png`, `k3.png`); an earlier drag left a "Distort sprite" undo step. Try it with a finger and say whether the picture bends.
2. **STU-5 snapping feel:** drag clips, text and sprites and say whether they snap as before.
3. Hear a ducked export (the voice lane and the 17-keyframe envelope are in the "Restore video test" project).
4. A transition between two different clips, each type, played and exported (Untitled 7 only has two identical clips).
5. Jobs 17 (STU-4 leftovers) and 18 (puppets) are not started: they need projects built first and a screen map; see their cards.

## 11-24 preview timing after PreviewClock (hover-1 lane, Note 9, build of joy-creator c0d9d4afc)
- Installed a fresh cloud build over Wi-Fi (data kept) and used "Untitled 7" (two 18 s clips of a frame-counter picture, Radial transition on the cut; the picture shows its own time and frame number, so any jump is visible).
- **Seek: PASS.** Tapping the overview bar at 17 s put the playhead at 16.992 and the picture read 00:00:17.000, frame 510 (`m2.png`): within 8 ms.
- **Play across the cut: mostly clean, one thing to look at.** Playing from 16.99 s: at editor time 19.823 the picture read 1.867 s (frame 56), and at 22.874 it read 4.933 s (frame 148): both fit clip 2 starting at editor time 18 (offset 17.95 to 17.94), the clock only ever rose, nothing froze or went back (`m4.png`, `m5.png`). **But the first frame after the cut (`m3.png`) shows editor time 18.006 with the picture at 1.000 s, frame 30, about 1 s ahead of where the later frames put it, and the transport button still showed Play, not Pause.** One screenshot, not a recording: it may be the transition's incoming frame being drawn from the wrong offset, or the button lagging. A screen recording across the cut would settle it.
- Not checked: audio staying in step, a seek while playing, a seek inside the transition.

## 11-24 the odd frame at the cut: read against the code (Opus, STU-5 lane)
**Not caused by PreviewClock.** Neither PreviewClock commit (`8dc841183`, `b2a9d00a9`) touches the hand-off at the end of a blend (`bOffsetMs`, `transitionNextSourceMs`, `glTransitionHold`, `advanceToSegment`, `seekInClip`: 0 changed lines), and for this project (both clips at normal speed, one transition) the blend window is exactly what it was before: `PreviewClockTest` pins that the old and new windows agree on every such timeline.
**What it is (an older behaviour):** the preview does not overlap the two clips at a transition the way the export does. During the blend it draws clip 2's first second (`transitionNextSourceMs`: clip 2 at progress x the blend's length), so the blend's last frame is clip 2 at 1.000 s, frame 30. At the cut that frame is held over the screen while clip 2 gets ready (`glTransitionHold`, released on clip 2's first rendered frame or after 1.5 s, which is also why the button still read Play), but clip 2 is then started from its beginning (`loadClipForPlayback`; only a LIVE blend seeks clip 2 on to 1 s). So the one frame at 18.006 shows 1.000 s, and from then on clip 2 runs from 0, as the later frames showed. The effect: clip 2's first second is seen twice, once inside the blend and again after it.
**Why not fixed here:** the two halves want opposite things. The editor's timeline puts clip 2's start at 18.0 s (it does not shorten at a transition), so after the cut clip 2 should be at 0; the export overlaps, so its blend shows clip 2's head and goes on from 1 s. Making them agree is the open STU-5 structural item (the timeline does not overlap clips at a transition; code review section A, D4 "structural difference"), a design call because it changes either what the editor's timeline shows or what the blend shows. Recorded on the STU-5 card. A screen recording across a cut, if the phone lane takes one, would show the held frame and then the restart.

## 11-24 corner-pin drag: what the code says (hover-1 lane, phone-free)
- `TransformOverlayView` (studiokit `transform/`) commits a drag as "Distort" only when the finger starts on a corner square and moves past touch slop (`onUp`: kind CORNER, `moved`). A still finger on the box for `LONG_PRESS_MS = 450` opens the long-press ring and ends the drag; a first finger OFF the box is held `PINCH_HOLD_MS = 150` for a possible second finger, then handed to the views beneath.
- So for adb: start **exactly on the corner square's centre** (read it from a screenshot: squares at the box corners), use a **short** swipe (250-350 ms) of **150 px or more**, so slop is passed well inside 450 ms. The drags that worked left a "Distort sprite" step (700 ms is borderline); the ones that did nothing were 700/1400 ms swipes of only 60-90 px, which sit near slop for much of the 450 ms window and can fall into the long-press path. A finger is a cleaner test than adb.
- Next phone attempt: top-right corner square, `swipe x y x-150 y+150 300`, then check Undo's name reads "Distort sprite" and the preview bends.
6. Puppets: a keyed pin (Grab drag at a later time, then scrub), a live take (Recording scope, Record on touch, play and drag) and a dragged performance. Pin placement already passes (tasks/audits/phone_results_hover1_20261127.md).
7. **Ducking, by ear:** play or export "Restore video test" (voice lane plus music with the 17-key ducking envelope) and say whether the music clearly dips under the voice and comes back after it. The audio-only export works since the STU-C3 fix; the level numbers alone could not show the dip (see `tasks/screens/studio-export-20261127-note9/README.md`).

## 11-27 where the remaining Studio launch rows stand (hover-1 lane)
- **STU-F7 (loop / ping-pong, 7 acceptance checks in `PLAN_LOOP_PINGPONG.md` lines ~110-121): not started.** A ready way to run the frame-inversion and no-black checks: the project "Untitled 7" holds a frame-counter picture (the test file `stu-b3-talk.mp4`, each frame prints its own time and number), so an exported ping-pong leg decodes with ffmpeg/OpenCV (the scripts used for the transition export are in the 11-27 evidence folder's README) and the frame numbers must run backwards on the reverse leg. Needs: find the Loop / ping-pong control for a clip (the lane menu string is `studio_label_loop_mode`), set it, export, decode.
- **STU-F6 11-28 on 9e04c1a40: PiP sound now reaches the export but the two PiPs are laid end to end and the audio outlasts the video (tasks/screens/stu-f6-pip-audio-20261127-note9). STU-F7 11-28: export PASS (frame 580 matches 499), preview owed.** Earlier note: STU-F6 half done 11-27, see tasks/screens/stu-f6-pip-audio-20261127-note9 (PiPs are silent by design; the real test is the object menu "Include audio" on both, then export; owed).** **STU-C6 (sweep of unproven rows): started 11-28, drawer-toggle undo steps and drawer hover descriptions pass (tasks/screens/stu-c6-drawer-undo-labels-20261128-note9); bent-sprite link, drag-rotation fold, paused-effect redraw, drawer polish still unrun.** **STU-F1: PASS 11-28** (cycle, side-handle drag, export vs preview within 0.2%; tasks/screens/stu-f1-fit-handle-export-20261128-note9).

## 11-28 hover-1 hand-off: what is left of C6, F6, F7 (Note 9 route notes)
- **Done:** F1 pass (36434c10b), C6 drawer-toggle undo steps and drawer hover descriptions pass (348fd9f08). **F7 and F6 are with the Opus lane**: F7 export still has the return leg forward (frame 580 equals frame 40) on the fix APK `a709b07d8`; F6 with "Include audio" on both PiPs still exports no PiP sound.
- **Left in C6:** link to a pin of a bent sprite; drag-rotation fold; paused-effect redraw (11-28: FAULT, a text with Brightness/Exposure shows no change in the preview at all while the export is darkened, see the C6 README; an effect on a paused frame must redraw when its slider moves); drawer polish (visualiser drawer two columns, "Follow what" dialog with the numbered pin diagram, one-button text case cycle, caption drawer above the preset strip, hex colour never wraps).
- **How to drive the phone (works):** `tools/phone.ps1 claim hover-1`, wake with key 224; Joy Creator lobby "Carry on" at about (942,1138) opens the last project; New project > "Select any video" > Files/Just once > pick a video pushed to /sdcard/Download makes a project in one step. Hold a timeline row 900 ms (`swipe x y x y 900`) opens that object's drawer; double-taps need `shell-write "input tap x y & input tap x y & wait"`. uiautomator dumps fail on the Library screen (never idle) but work in the editor; Library taps need screenshot coordinates.
- Test files made on the PC (not in the repo): tone videos (440 / 1320 / 2000 Hz) pushed as h1_pipA_440.mp4, h1_pipB_1320.mp4, h1_main_blue.mp4 in /sdcard/Download; projects "Untitled audio 5" (two PiPs) and "Untitled 8" (text, Fit to width) are throwaways on the Note 9.
