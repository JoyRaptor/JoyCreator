# Avatar Studio on the phone: progress in points

The owner's goal (11-27): the phone room at least as good as the web pilot, with maximum polish, before he tries it on
the phone. Each piece has difficulty points (1 = small, 13 = the hardest). **Landed** = on `joy-creator` and its tests
pass; it earns its points. **Built** = on a session's branch, not landed: half its points. Nothing counts as done on
the phone until the final phone run. Every session ticks its own rows here when it lands; the lead re-adds the totals.

## Totals

| | Points |
|---|---|
| Earned | **40 of 175 (23%)** |
| Landed | 35 |
| Built, not landed (half) | 5 |

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
| The doors: layered file, loose pictures, a sheet, one drawing, Update the art | 5 | ✅ 3 of 5 landed (81dfbc6f1) | slice 1 |
| Joybot tools for the room | 2 | ✅ landed (81dfbc6f1) | slice 1 |
| Takes and voice engines | 3 | ✅ landed (81dfbc6f1) | slice 1 |
| Studio's Undo names the step | 1 | ✅ landed (81dfbc6f1) | slice 1 |
| Topps and Pip built in | 1 | ✅ landed | slice 1 |
| Perform panel: head pad, Hold to talk, Blink, moods, buttons, Breeze, take bar, Mic, Face | 5 | ✅ landed (cloud tests green); crude stage until X2; not tried on a phone | slice 1 |
| Landmarks screen on the phone | 3 | ✅ landed (whole head only; one piece on its own, preview slider owed) | slice 1 |
| Landscape and tablet layouts | 3 | 🟡 landscape split and tablet proportions landed; the two-column tablet layouts owed | slice 1 |
| X2 the movement engine (the pilot's solver), matched to the pilot within half a pixel | 13 | ✅ landed (see the X2 commit on joy-creator, 11-27): every vertex within 0.005 px in 41 poses of Topps, Pip, the landmark squares and a feature "gadget" (+ the Joybot draft locally); cloud Studio 82/0; not run on a phone | spec session (Opus) |
| X3 drawing on the GPU: masks, colour sets | 8 | ✅ landed (11-27): `CharacterGl` + `CharacterGlSource` (the pilot's shaders exactly, checked); on the Note 9 Topps and Pip in 5 poses each, no GL errors, 0.2 to 0.6% of pixels differ, every one over 32 levels on an outline (edge smoothing); masks and the Coral colour set match by eye; solve about 1 to 8 ms, draw 2 to 15 ms a frame | spec session (Opus) |
| Set up card: pins, magnifier, pin wizard | 5 | 🟡 pins on the picture with a loupe, drag as one undo step (cloud build compiles); guessing pins from pixels and the wizard owed | slice 1 |
| Arrange: the box, stances, all fours, reshape with pins, in front / behind | 8 | 🟡 the box (move, resize, turn, Put it back) and in front / behind built (compiles); stances, all fours and reshape wait on X2 | slice 1 |
| Motion: swinging parts, turning round, round heads | 5 | 🟡 swinging, turning around, breathing and blinking cards built (compiles); round heads owed | slice 1 |
| Walking, the joystick and its gestures | 5 | ⬜ | slice 1 |
| Face capture on the phone, walk with your face | 5 | ⬜ | slice 1 |
| Side-by-side pass against the pilot: every control, every word, polish | 8 | ⬜ | slice 1 + lead |
| **Slice 2: characters inside Video Studio** | | | |
| X4 voice recorder gives its sound to lip sync | 2 | ✅ landed (`PcmBlocks`; 18 checks, WAV bytes unchanged) | slice 2 (cloud) |
| X5 export sheet parts shared with Studio | 3 | ✅ landed (`ExportSheetParts`); Studio sheet screenshots owed | slice 2 (cloud) |
| A solid colour clip in Studio (key colour) | 2 | ✅ landed (Add › Solid colour; 23 checks) | slice 2 (cloud) |
| A character drawn in Studio's preview and export | 5 | 🟡 built: seam, `CharacterItemPuppet`, `ItemPuppets`; draws its standing picture until X3 installs and STU-7 answers on the 3 draw sites | slice 2 (cloud) |
| Add a character to a project with its take and voice | 3 | ✅ landed (Add › Character; one undo step) | slice 2 (cloud) |
| Still draggable; record a pass in Studio | 3 | 🟡 drag as a sprite; a pass via the Perform pop-up; finger-on-a-hand driving not built | slice 2 (cloud) |
| Send to Studio, the room's export sheet, video with a key colour | 5 | 🟡 built (`RoomExportSheet`, Studio drop-on-open); the room's button is slice 1's file | slice 2 (cloud) |
| **Slice 2b: acting from a video** | | | |
| Act from a video: pick layers, what it follows, read into keyframes, Bake / Keep as driver, lips from a sound layer | 8 | ✅ landed (cloud-compiled; engine 50 checks); no phone run | slice 2 (cloud) |
| The performing pop-up on the timeline | 5 | ✅ landed (room's PerformPanel + Mic via X4); no joystick yet (the room has none) | slice 2 (cloud) |
| **Slice 3: scenes** | | | |
| Scene file and clock, the camera lane | 3 | ⬜ | after slice 1 |
| Path tabs, the chair, the frame, the strip | 5 | ⬜ | after slice 1 |
| The scene on the four stages; the empty scene | 3 | ⬜ | after slice 1 |
| Several characters, shots, walking on and off, the joystick in a scene | 8 | ⬜ | after slice 1 |
| Exporting a scene; Send to Studio not flattened | 5 | ⬜ | after slice 1 |
| **Finishing** | | | |
| Second audit (Codex) and its fixes | 5 | ⬜ | Codex + lead |
| The phone run: everything on the Note 9, then the owner's look | 5 | ⬜ | lead |
