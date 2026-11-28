# Avatar Studio on the phone: progress in points

The owner's goal (11-27): the phone room at least as good as the web pilot, with maximum polish, before he tries it on
the phone. Each piece has difficulty points (1 = small, 13 = the hardest). **Landed** = on `joy-creator` and its tests
pass; it earns its points. **Built** = on a session's branch, not landed: half its points. Nothing counts as done on
the phone until the final phone run. Every session ticks its own rows here when it lands; the lead re-adds the totals.

## Totals

| | Points |
|---|---|
| Earned | **154 of 188 (82%)** (11-28, lead re-checked every row; Set up, Arrange, walking and scene row 1 counted in full as every named part has landed) |
| Landed | 146 |
| Built or partly landed (half) | 8 |

Updated 11-27 by the lead.

## The pieces

| Piece | Points | State | Who |
|---|---|---|---|
| **Plan and pilot** | | | |
| Phone spec, shared file format, format tests | 5 | ✅ landed (31277d386) | spec session |
| Step 0: pilot reads and writes the shared file; landmarks (Here / Behind / Not here); golden files | 8 | ✅ landed (253bde071, pilot v31); checked by script, owner's look owed | spec session |
| **Slice 1: the character room** | | | |
| X1 character file reader and checker (Java) | 3 | ✅ landed (8d4ab2daa) | slice 1 |
| Room skeleton: top bar, stage, panel, four stages | 3 | ✅ landed (fe069fb68) | slice 1 |
| Menu, open, save into the Joy Creator folder, autosave | 3 | ✅ landed (4a2d01f22, 81dfbc6f1) | slice 1 |
| Layers, parts, named undo / redo | 3 | ✅ landed (81dfbc6f1) | slice 1 |
| The doors: layered file, loose pictures, a sheet, one drawing, Update the art | 5 | ✅ all 5 landed (layered file, loose pictures, Update the art 81dfbc6f1; one drawing + pin wizard 282cf25b0; sheet cutter: parts become pictures with the chosen part's role, or one part that swaps, or a loop; the pilot's body placement owed); none tried on a phone | slice 1 |
| Joybot tools for the room | 2 | ✅ landed (81dfbc6f1) | slice 1 |
| Takes and voice engines | 3 | ✅ landed (81dfbc6f1) | slice 1 |
| Studio's Undo names the step | 1 | ✅ landed (81dfbc6f1) | slice 1 |
| Topps and Pip built in | 1 | ✅ landed | slice 1 |
| Perform panel: head pad, Hold to talk, Blink, moods, buttons, Breeze, take bar, Mic, Face | 5 | ✅ landed (cloud tests green); crude stage until X2; not tried on a phone | slice 1 |
| Landmarks screen on the phone | 3 | ✅ landed (whole head only; one piece on its own, preview slider owed) | slice 1 |
| Landscape and tablet layouts | 3 | 🟡 landscape split and tablet proportions landed; the tablet rail landed (layers always visible: beside the card held upright, far left sideways; built, never seen on a tablet; the labelled body map in the rail, sliders capped at 320 dp and two-column card sections owed) | slice 1 |
| X2 the movement engine (the pilot's solver), matched to the pilot within half a pixel | 13 | ✅ landed (see the X2 commit on joy-creator, 11-27): every vertex within 0.005 px in 41 poses of Topps, Pip, the landmark squares and a feature "gadget" (+ the Joybot draft locally); cloud Studio 82/0; not run on a phone | spec session (Opus) |
| X3 drawing on the GPU: masks, colour sets | 8 | ✅ landed (11-27): `CharacterGl` + `CharacterGlSource` (the pilot's shaders exactly, checked); on the Note 9 Topps and Pip in 5 poses each, no GL errors, 0.2 to 0.6% of pixels differ, every one over 32 levels on an outline (edge smoothing); masks and the Coral colour set match by eye; solve about 1 to 8 ms, draw 2 to 15 ms a frame | spec session (Opus) |
| Set up card: pins, magnifier, pin wizard | 5 | 🟡 pins on the picture with a loupe, and pins guessed from the pixels (matches the pilot), landed; the pin wizard landed (Pin one whole drawing door, not tried on a phone) | slice 1 |
| Arrange: the box, stances, all fours, reshape with pins, in front / behind | 8 | 🟡 the box, in front / behind (whole part and in a stance), stances and all fours landed (checked against the pilot, not tried on a phone); reshape with pins landed (matches the pilot's warpAt and dropPin; not tried on a phone) | slice 1 |
| Motion: swinging parts, turning round, round heads | 5 | ✅ swinging, turning around, breathing and blinking, round heads landed (not tried on a phone) | slice 1 |
| Walking, the joystick and its gestures | 5 | 🟡 walking by dragging the body (ground line, strides, jump, fall, all fours, hover) landed, 2 traces of the pilot matched frame by frame; the joystick (rate steer, speed curve, tap/flick gestures, six-slot sheet) landed, not tried on a phone; with its depth walking, camera angle and floor grid landed (not seen on a screen) | slice 1 |
| Face capture on the phone, walk with your face | 5 | ✅ landed (5d072ea78): the Face chip (always, then only while talking, then off), the head moved from where the pad was and as far as the pad goes, the expression and your own blinks, the idle sway resting while it drives, the face's lanes (face pins, expressions) in takes and on to the movement engine; Walk with your face (left and right, closer and further, Up close) under the Face controls for a character that walks. `run-avatar-face.sh` 28 checks, 29 planted faults caught (`--proof`); cloud build and `studio` green (run 46050047817, with slice 1's Stack). Not tried on a phone; the face mask (FACE-2) pass, Shoulders, Check directions and placing face pins on the phone were not ported; **since then, to pilot parity (11-27 evening, 04fa621b6):** the Puff button and the nod brow fix (b92cebd63); the face mask pass (Topps wearing the pilot's mask: 6 golden cases within 0.005 px) and the face driving it; Check directions; placing face pins in Set up (pick a point of your face, tap the character, drag, strength, All pins, one piece); his own face take played into the real room on the cloud emulator (`AvatarFaceReplayTest`, run 46098593937): every move, the Face chip, Walk with your face, Mirror, Puff, Check directions 5 of 5, a face pin by real taps. Shoulders since landed too (`a5a1337fc`: Google's body model, set up on first sight, Shoulders and sensitivity in Perform, Shrug in Motion; emulator `AvatarShoulderTest`, run 46104628933). Still owed: the Live picture in the face pin picker; the pilot's 478-point map | face session |
| Walking toward and away from the camera | 5 | ✅ landed (8a1116e7e): `Walker` + `DepthWalk` (the pilot's al-depth-walk.js: grows coming close, shrinks going away, turns to the front drawing, the camera's angle, Home walks back, the plain stage untouched when off); Topps toward, away and Home, 220 frames within 0.0001 px of the pilot; Up close through the room's walker; `run-avatar-depth.sh` 8 checks, 12 planted faults caught; the two walking traces still 0 frames off. Not tried on a phone; owed: the joystick in perspective (slice 1: `Stick` and `StickSheet`), room below in the stage's framing while it comes close | face session |
| Cameras per tracker (face and body on different cameras) | 5 | ✅ landed (`6e927d830`): the owner's idea (11-27: one person acts the body, another does the face). Face camera and Body camera chips in the face row list only the cameras the phone has (Front, Back, Back wide: an ultra-wide or the back camera at its widest); one camera for both by default; two at once where the camera system lists the pair, else one plain line and One camera for both; saved with the character, one undo step each. `CameraPlan` + `TrackerCameras` (CameraX's concurrent mode), `CameraPlanTest` (17 checks, 7 plants), emulator `AvatarCameraTest` (run 46150080019: both emulator cameras deliver at once; the refusal by real taps). Not on a phone yet | face session |
| Side-by-side pass against the pilot: every control, every word, polish | 8 | 🟡 the list is written (`PARITY.md`, 40 gaps); fixing them in order, each with a check and an emulator screenshot. **The room's look as Studio's sibling (face scout, 11-28, landed `30fcceab5`):** the face's settings in a sheet behind a Face settings chip (no half-width column), Studio's icons (no typed marks or grey filled circles), Studio's greys by role and its faces; BEFORE / AFTER pictures in `tasks/screens/AVA-LOOK_20261128/`; owed: the tablet set, sizes card by card, Perform trimmed | slice 1 + lead + face scout |
| **Slice 2: characters inside Video Studio** | | | |
| X4 voice recorder gives its sound to lip sync | 2 | ✅ landed (`PcmBlocks`; 18 checks, WAV bytes unchanged) | slice 2 (cloud) |
| X5 export sheet parts shared with Studio | 3 | ✅ landed (`ExportSheetParts`); Studio sheet screenshots owed | slice 2 (cloud) |
| A solid colour clip in Studio (key colour) | 2 | ✅ landed (Add › Solid colour; 23 checks) | slice 2 (cloud) |
| A character drawn in Studio's preview and export | 5 | ✅ landed 11-27: `SolverPoser` (CharacterSolver + CharacterGl off screen) behind the 3 draw sites; emulator `AvatarStudioDrawTest`: moves with its take, export = preview; fault `rest` fails | slice 2 (cloud) + X2/X3 session |
| Add a character to a project with its take and voice | 3 | ✅ landed (Add › Character; one undo step) | slice 2 (cloud) |
| Still draggable; record a pass in Studio | 3 | ✅ landed 11-28: a finger on a hand in the Perform pop-up (drawn by the solver) records the hand into the item's take and Studio plays it; emulator `AvatarStudioHandTest`, fault `rest` fails; the room's Perform stage takes hands too | slice 2 (cloud) + X2/X3 session |
| Send to Studio, the room's export sheet, video with a key colour | 5 | ✅ Send to Studio proven end to end 11-28 (emulator `AvatarSendToStudioTest`: folder step, a pass in the room, export mark, Send to Studio, a new project, Studio plays the pass; fault `rest` fails); video with a key colour and a phone run not tried | slice 2 (cloud) + X2/X3 session |
| **Slice 2b: acting from a video** | | | |
| Act from a video: pick layers, what it follows, read into keyframes, Bake / Keep as driver, lips from a sound layer | 8 | ✅ landed (cloud-compiled; engine 50 checks); no phone run | slice 2 (cloud) |
| The performing pop-up on the timeline | 5 | ✅ landed (room's PerformPanel + Mic via X4); no joystick yet (the room has none) | slice 2 (cloud) |
| **Slice 3: scenes** | | | |
| Scene file and clock, the camera lane | 3 | 🟡 built, landing now: `avatar/scene/` (`Scene` + undo, `SceneGeometry`, `SceneClock` with passes, shots, camera lane; `SceneFile` read / write with takes, carried characters, pictures); `run-avatar-scene.sh` 53 checks, 6 planted faults caught, numbers matched to the pilot (depth, frames, Topps placement, camera clamp) | slice 3 |
| Path tabs, the chair, the frame, the strip | 5 | 🟡 frame, chair and strip landed on the empty scene (frame button with sheet, strip from the side or bottom pushing the frame, Studio texture, Wide / Close / kept shots, Cut / Glide); `AvatarSceneUiTest` real taps on the emulator green, 69 JVM checks; the path tabs with the chair come with the scene on the four stages | slice 3 |
| The scene on the four stages; the empty scene | 3 | ✅ landed 11-27 (cloud-compiled; `AvatarSceneRoomTest` green on the emulator, pictures reviewed): new scene on a set (Meadow, Room, Night, Studio, Plain), Set up / Arrange / Motion / Perform cards, drag things, a character comes in (Topps, Pip, your shelf), frame in Perform, Leave back to the empty scene; scenes kept as `<Name>.scene.json`, Trash on delete. Not yet: saving shown on a real folder (the emulator has none: the room says so), adding pictures from a file, any phone. Playing inside a scene is row 4 | slice 3 (Claude) |
| Several characters, shots, walking on and off, the joystick in a scene | 8 | 🟡 landed d72cb44fd (cloud emulator: AvatarSceneTakeTest, Room, Folder, Picture green): several characters on one clock (the one in hand records through the room's own Record; the others play their takes; Play the whole scene), shots (Wide, Two-shot, close-up on each), pictures from a file kept inside the scene file, the X6 lost-work fixes S1/S2/S4/S5/S6/S9/S14. Not yet: walking on and off (edge rule), the joystick walking in a scene, keep-the-view shots, S3/S7/S8/S11-S13/S15 | slice 3 (Claude) |
| Exporting a scene; Send to Studio not flattened | 5 | ⬜ | after slice 1 |
| **Finishing** | | | |
| Second audit and its fixes (X6; Codex gone, Claude since 11-28) | 5 | 🟡 audit done 11-28 (`tasks/audits/claude/AVA_X6_20261128.md`, five reviews, ~90 findings); every lost-work finding in the room and the auditor's code fixed with a check that fails first; scenes S1-S5 asked, smaller items open with their owners | Claude (X2/X3 session) + owners |
| Android emulator tests on GitHub (`cloud-build.ps1 avatar-emu`: draw parity against the pilot, room smoke with real taps) | 3 | ✅ landed 11-27 | Claude (X2/X3 session) |
| The phone run: everything on the Note 9, then the owner's look | 5 | ⬜ | lead |
