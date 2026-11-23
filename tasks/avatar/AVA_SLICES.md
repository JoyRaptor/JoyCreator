# Part E: the slices (steps, no time estimates)

Part of the [Avatar Studio phone spec](AVA_PHONE_SPEC.md). Written 2026-11-23. **Nothing here is started.** Each slice
starts only on the owner's go-ahead, in its own session and worktree, and claims its files on the BOARD first.
The owner's order (card, "the path to the phone"): the character room; into Video Studio; scenes. Slice 0 is the small
pilot step the other three stand on.

How every slice is proven on the Note 9 (the sandbox phone; the Note 20 is never touched):
cloud build (`tools/cloud-build.ps1 run <branch>`), install through `tools/phone.ps1` under the phone lock (Home first if
JoyBrush has a drawing open), a temperature line every 30 seconds, BEFORE and AFTER screenshots in
`tasks/screens/avatar-<slice>/`, real finger taps through `phone.ps1`, and the slice's JVM tests in the cloud
(`cloud-build.ps1 test <branch>`). Every break test is first shown to FAIL on a planted fault before its pass is trusted.
"Built, not tried on the phone" is reported as exactly that.

## Slice 0: the pilot reads the shared format (web, small)

**Why first:** the phone and the pilot must write the same file before either is trusted, and the owner's landmark model
replaces the pairs in both.

Needs:
1. Load `al-format.js` in the pilot page; `AL.fromJson` upgrades `/1` to `/2` on open; `AL.toJson` writes `/2`.
2. The pilot's solver reads `set.marks` (each point blends between its own places, `AL.format.blend`) instead of
   `set.bridges`; the three-angle share-out code of 11-23 is then no longer needed for marked pieces.
3. The landmarks screen (spec A9) replaces "Match two angles": numbered points, Behind, Not here, Here, Add, Remove.
4. A golden writer: `AL.golden(ch, signals[])` writes the solved meshes for a fixed list of signals, for the phone's parity test.

Proof: `node tasks/avatar/format-tests/run.mjs` stays 0 failures; the pilot's break tests (`al-breaktest-bridge.js` 5/0,
head turning 33/0, scene 84/0, export 29/0) pass on the new code and the bridge test fails when fed the old pair model;
the owner's matched head (`odd-mesh`) turns Left Quarter to Upward with no jump at the swap (pixels whose outline changes,
as measured on 11-23). Published to his test page; his look at the landmarks screen is owed before slice 1 copies it.

Joybot tools: `marks_add`, `marks_place` (point, angle, x, y, state: here / behind / off), `marks_remove`.

## Slice 1: the character room on the phone

Needs, in order (each a small class, part C):
1. `CharacterFile` + `FormatCheck` in Java, tested in the JVM harness on `tasks/avatar/format-tests/` (the same 74
   checks: the same files must pass, the same planted faults must fail). `FileKind.CHARACTER`.
2. `CharacterSolver` port of `al-solve.js`, tested against the pilot's golden files: every vertex within 0.5 px for
   Topps (built-in) standing, turning, reaching with both arms, talking, blinking, on all fours, mid-walk.
3. `CharacterGl` on `MeshEngine` / `MeshGlSource`, with the mask pass; soft-edged masks checked on the Note 9's GPU
   (an owed item from the audit); the colour-set shader as its own small program.
4. `AvatarRoomActivity`: top bar, stage, panel, four stages, cards with the pilot's words and hover labels, the drawer and
   shelf, autosave into the Joy Creator folder. Phone portrait first, then landscape, then tablet (spec A2, A13, A14).
5. The doors: layered file (`PsdReader`, `AutoRig`), loose pictures, a sheet, one drawing; Update the art.
6. Perform and recording: `Inputs` (owners, passes, take back a pass), head pad, Hold to talk, Mic (on the fixed
   `VoiceoverRecorder`, part D item 1), Voice file, Face (`FaceCapture`), buttons, moods, Breeze, Walking, the joystick and
   its gestures, Walk with your face. The voice kept with the take.
7. Undo through `UndoManager` (after Studio's hover-names-the-step fix, part D item 2).
8. Pip and Topps as assets; Topps opens ready to play; Joybot's target for "Create new avatar".

Proof on the Note 9 (one run, Topps built-in, then a made-up test character through each door):
- Open the room from the lobby: Topps in Perform, alive. Screenshot.
- Set up: drag one pin with a finger (magnifier follows), one undo step back.
- Arrange: All fours from the pose row, the box on the head, a move, Put it back.
- Motion: drag a hand; the tail swings; turn round Smooth.
- Perform: record a pass dragging the head with the mic on and speaking; a second pass on one hand only; play: the first
  pass's head and voice are still there; take back the last pass; close the app, reopen: the take and voice come back.
- The file in the Joy Creator folder opens in the pilot on the PC (and the pilot's save opens on the phone): the round trip.
- Leaving the room: the camera and microphone lights go out.
- Frame rate while performing with Topps, and the phone's temperature, written down.
- Phone held sideways: the landscape layout. The test tablet: both tablet layouts (throwaway files only).

Joybot tools (the pilot's, plus what the room adds): `describe`, `character_open`, `character_new`, `character_add_part`,
`perform` (mood, look, mouth, button, blink), and new `character_stage` (setup / arrange / motion / perform),
`part_choose` (by name), `stance_go` (by name), `take_record` (on / off), `take_play` (on / off), `take_back_pass`,
`character_save`. Each is one undo step named for Joybot, and goes through the same checked edit as a tap.

## Slice 2: into Video Studio, with its take, still draggable

Needs (Studio first, part D items 4, 5, 6):
1. A word in `tasks/motionblur/CHANNEL.md` before touching `CompositeExportOverlay` / `SpriteBlendGlEffect` (STU-7 is
   reworking that path).
2. `SpriteOverlayItem` gains the character reference and its take; `CharacterItemPuppet` draws it through
   `CharacterSolver` for preview and export; old avatar items keep `AvatarItemPuppet`.
3. Studio's Add sheet lists the characters (the shelf); one tap drops the chosen one at the playhead with its take on its
   own lane and its voice as an `AudioClip` at the same start; the item is as long as the take. One undo step.
4. Still draggable: move / size / turn with `PreviewHandlesOverlay`; with the puppet badge on, a finger on its head, hand,
   foot or body records a pass into the item's take with Studio's record control (one undo step).
5. "Send to Studio" in the room's export sheet does step 3 into a chosen or new project.
6. The room's export sheet: Joy Creator file, One frame (see-through tick), Video (through `ExportService`; key colour on
   Studio's new Solid colour clip, or the PNG stopgap), sizes, preview, progress, Stop. `ExportSheetParts` shared with Studio.

Proof on the Note 9 (a throwaway project "TEST avatar"):
- Drop Topps with a recorded take into the project: it plays in Studio's preview in step with its voice.
- Drag it to another place and size: one undo step; the take still plays there.
- Puppet badge, drag his hand during playback: a pass; play: only the hand changed.
- Export 720p: the MP4 plays in the phone's gallery; one frame of the export compared with the same moment in Studio's
  preview (rule 5: preview and export agree), pixel difference written down.
- Video from the room with a blue key colour: the colour is behind him, edge to edge.
- Studio's own voiceover, undo and export sheet look and behave as before (before/after screenshots; Studio suite green).

Joybot tools: `studio_add_character` (name, at seconds), `character_export` (file / frame / video, size, key colour),
`character_send_to_studio` (project).

## Slice 3: scenes (strip, frame, shots, joystick and gestures)

Needs:
1. `SceneFile` + its checks (`FileKind.SCENE`), the same test files; `Scene`, `SceneClock`, the camera lane.
2. The path tabs with the chair; the frame button (shapes, margins, "Room round the frame"); the director's chair and the
   strip (`DrawerPlace` and the shared shift rule, part D item 3); Studio's canvas texture round the frame.
3. The scene in hand on the four stages (Set up, Arrange, Motion, Perform); the empty scene first and undeletable.
4. Several characters: one clock, red records the one in hand while the others play; shots (Wide, Two-shot, Close on each,
   kept, Cut / Glide); walking across and off the stage, the edge rule, off-screen tabs; the joystick in a scene, walking
   into the picture with the scene's floor angle.
5. Export of a scene (video at the frame's shape with its camera lane and every voice; Joy Creator file carrying its
   characters; one frame) and Send to Studio with the scene un-flattened (the card's ruling: rendered at Studio's export).

Proof on the Note 9:
- Create new scene on Meadow; add Topps twice; drag one up the floor (smaller, behind the other; one undo step).
- Record Topps 1 walking in from off-stage left with the joystick; then Topps 2's pass while 1 plays back; Play: both.
- Shots on: cut to Close on Topps 1 during a pass, glide back to Wide; replay follows the lane.
- Frame 9:16 then 16:9: nothing on the stage moves; the strip pushes the frame and never shrinks it.
- Speed with two characters and a set on the Note 9, and the temperature, written down (an open "not checked" from round 20).
- The scene file opens in the pilot on the PC, and back.

Joybot tools: the pilot's 14 scene tools as they are (`scene_new`, `scene_open`, `scene_add_character`,
`scene_add_picture`, `scene_place`, `scene_remove`, `scene_frame`, `scene_set`, `scene_stage`, `scene_take_in_hand`,
`scene_shot`, `scene_record`, `scene_play`, `describe`), plus `scene_strip` (scene / character / shots on or off),
`character_walk_to` (x on the stage, or off left / right), `stick_gesture` (slot, act).

## After the slices (named so they are not lost)

- Send one part to JoyBrush and back (his idea of 11-21; rides on the FX-1 hand-off moves).
- A first-run guided path for someone with no art ready (answered in chat 11-20, not built anywhere).
- The pilot's open "not fixed" list from the Codex pre-port review (card, 11-21), each checked again on the phone build.
- Bringing an old `.avatar.json` character into the new room.
