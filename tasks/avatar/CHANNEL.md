# Avatar Studio build: the channel (Claude and Codex)

Where the Avatar Studio phone build is coordinated across harnesses. Plan: [AVA_PHONE_SPEC.md](AVA_PHONE_SPEC.md),
build order [AVA_SLICES.md](AVA_SLICES.md), file format [AVA_FORMAT.md](AVA_FORMAT.md), Studio reuse
[AVA_STUDIO_REUSE.md](AVA_STUDIO_REUSE.md), job card [AVA-1](../jobs/AVA-1_avatar_studio_redesign.md).

**The owner's split (11-23):** Claude (Opus) builds what people see and touch: screens, cards, the room, the
stages, Perform, every word and hover label, so it stays one hand. Codex builds the gears underneath, one well-fenced
piece at a time, and is the second auditor of what Claude lands. Codex works on Avatar Studio only when the owner
gives it a line below; motion blur (STU-7) comes first.

## How we keep out of each other's way

1. Before starting a piece, write a line under **Claims**: who, which piece, branch, the files you will touch.
   Claude does not touch a file Codex has claimed, and the other way round.
2. Each piece is on its own branch, from `joy-creator`; land with `git pull --rebase origin joy-creator` and
   `git push origin HEAD:joy-creator`, never force. Say here when it lands, with the SHA.
3. A question for the other side goes under **Messages**, newest first. Check here before starting and before landing.
4. House rules for both: `tasks/AGENT_START.md`. The owner is `JoyRaptor` in every tracked file; no phone serials,
   no secrets, no owner media. Every break test is shown to FAIL on a planted fault before its pass counts. No time
   estimates. Note 9 only, under the phone lock; the Note 20 is never touched.

## The Codex list (the owner pastes one line to start it)

Each is "trees": a fenced piece with its own tests, no screen work. Paste the quoted line to Codex as it is.

| # | Piece | Paste to Codex | Needs first | Proven by |
|---|---|---|---|---|
| X1 | **`CharacterFile` + `FormatCheck` in Java** (slice 1, item 1) | "Avatar Studio X1: read tasks/avatar/CHANNEL.md and AVA_FORMAT.md, claim X1 there, and build CharacterFile + FormatCheck in Java so the files in tasks/avatar/format-tests pass and every planted fault fails, as run.mjs does." | nothing | the same 74 checks as `node tasks/avatar/format-tests/run.mjs`, in the cloud JVM tests |
| X2 | **`CharacterSolver`: the pilot's `al-solve.js` ported** (slice 1, item 2) | "Avatar Studio X2: read tasks/avatar/CHANNEL.md, claim X2, and port tools/avatarlab/al-solve.js to CharacterSolver, matching the pilot's golden files within 0.5 px." | X1, and the golden files from Claude's step 0 (`AL.golden`) | every vertex within 0.5 px of the pilot for Topps standing, turning, reaching, talking, blinking, on all fours, mid-walk |
| X3 | **`CharacterGl`: drawing on `MeshEngine`, the mask pass, the colour-set shader** (slice 1, item 3) | "Avatar Studio X3: read tasks/avatar/CHANNEL.md, claim X3, and build CharacterGl on MeshEngine with the mask pass and the colour-set shader, checked on the Note 9's GPU." | X2 | a frame from the Note 9 against the pilot's same frame, pixel difference written down; soft-edged masks seen on the Note 9 |
| X4 | **Studio fix: `VoiceoverRecorder` gives a sample listener and a level** (Studio reuse item 1) | "Avatar Studio X4: read tasks/avatar/CHANNEL.md, claim X4, and give VoiceoverRecorder a sample listener and a level meter feed, proven in Studio first, Studio's voiceover unchanged." | nothing | a JVM test on the listener; Studio's voiceover records and plays as before on the Note 9 |
| X5 | **Studio fix: `ExportSheetParts` lifted out of the editor** (Studio reuse item 4, slice 2) | "Avatar Studio X5: read tasks/avatar/CHANNEL.md, claim X5, and lift Studio's export sheet parts out of FaditorEditorActivity into ExportSheetParts with no change in how Studio's sheet looks or works." | say so in `tasks/motionblur/CHANNEL.md` first (STU-7 holds the export path) | before/after screenshots of Studio's export sheet identical; an export still plays |
| X6 | **Second audit of whatever Claude lands for Avatar Studio** | "Avatar Studio X6: read tasks/avatar/CHANNEL.md and audit the latest Avatar Studio landing listed there; report findings in tasks/audits/codex/ and a line in the channel." | a landing listed below | findings with file and line, each one checked by Claude before it is fixed |

Claude keeps: step 0 in the pilot, the room's screens and cards (`AvatarRoomActivity` and everything drawn), the doors,
Perform and recording, Joybot's tools, Studio's Undo hover names, and every piece above that Codex has not claimed when
a slice needs it (Claude claims it here first, so Codex can see it is taken).

## Claims

| Who | Piece | Branch | Files | Since |
|---|---|---|---|---|
| Claude, "face capture scout" session (Opus), [FACE-2](../jobs/FACE-2_face_mask_and_poses.md) | The face mask and poses in the web pilot (owner: "Build it", 11-27) | `claude/face-capture-scout-b847e5` | new `tools/avatarlab/al-mask*.js`, `al-breaktest-mask.js`; small hooks in `al-solve.js` (the mask bend), `al-cards.js`, `index.html`; my own `al-facepins.js`, `al-face.js`. Not `al-format.js` or `al-bridge.js` (I use `AL.marks` as it is) | 11-27 |
| Claude, "Avatar Studio slice 1: the room" session (Opus) | Slice 1 items 4 to 8: the room's screens, doors, Perform and recording, undo, Joybot tools | its own worktree branch | new `avatar/room/**` classes; it claims X1 to X3 here before touching them if Codex has not | 11-23 |
| Claude, "Avatar Studio slice 1: the room" session (Opus) | **X1 (all of it, the lead's go 11-23)**: `CharacterFile` + `FormatCheck` + `Js` + `CharacterDoc` + `CharacterAssets` in `avatar/file/`, test `tools/jvm-harness/CharacterFormatTest.java` + `run-avatar-format.sh` | same branch | `app/src/main/java/cc/joycreator/avatar/file/**`, `tools/jvm-harness/CharacterFormatTest.java`, `run-avatar-format.sh`, one word in `run-studio-all.sh`. **Codex: X1 is taken (see Landings); take X4 or X6.** | 11-23 |
| Claude, "Avatar Studio phone spec" session (Opus) | **X2**: `CharacterSolver`, the pilot's `al-solve.js` ported, proven against `tasks/avatar/golden/` (then X3 if it lands cleanly) | its own worktree branch | new `app/src/main/java/cc/joycreator/avatar/solve/**`, `tools/jvm-harness/CharacterSolverTest.java` + `run-avatar-solve.sh`, one word in `run-studio-all.sh`, `tasks/avatar/golden/**`, `tools/avatarlab/al-golden.js` (reads X1's `avatar/file/**`, does not change it) | 11-27 |
| Claude, "Avatar Studio slice 2 (cloud)" session (Opus) | **X4**: `VoiceoverRecorder` sample listener + level (slice 1's `TakeVoice` / `TakeRecorder` are pure and do not tap the mic, so it is still owed) | `claude/ava-slice2-cloud` | `app/.../faditor/audio/VoiceoverRecorder.java`, new `audio/PcmBlocks.java`, test `tools/jvm-harness/VoiceoverTapTest.java` + `run-voiceover-tap.sh` | 11-27 |
| Claude, "Avatar Studio slice 2 (cloud)" session (Opus) | **X5**: `ExportSheetParts` lifted out of `FaditorEditorActivity.showExportConfirmation`, Studio's sheet unchanged | `claude/ava-slice2-cloud` | new `app/.../faditor/export/ExportSheetParts.java`; `showExportConfirmation` in `FaditorEditorActivity` only | 11-27 |
| Claude, "Avatar Studio slice 2 (cloud)" session (Opus) | **Solid colour clip** in Studio (Studio reuse item 6) | `claude/ava-slice2-cloud` | new `app/.../faditor/model/SolidColour*.java`; one entry in Studio's Add sheet; its strings | 11-27 |
| Claude, "Avatar Studio slice 2 (cloud)" session (Opus) | **Slice 2 + 2b**: a character in Studio (item, take, voice, drawing seam), Add sheet shelf, drag + pass, Act from a video, the performing pop-up, Joybot tools, the room's export sheet parts | `claude/ava-slice2-cloud` | new `app/src/main/java/cc/joycreator/avatar/studio/**`; small adds to `SpriteOverlayItem` (character uid + take), Studio's Add sheet, the layer drawer, `ToolRouter`'s avatar family; `CompositeExportOverlay` / `SpriteBlendGlEffect` / preview only after a word in `tasks/motionblur/CHANNEL.md`. Not `avatar/room/**`, not slice 1's `avatar/ui|perform|file|intake` (read only), not `avatar/solve/**` | 11-27 |

## Landings

(newest first: what landed, SHA, what was proven, what was not)

- 11-27 Claude, "Avatar Studio phone spec" session: **step 0 landed** (see the commit for the SHA; pilot page v31). The pilot reads `/1` and upgrades it, writes `/2`; the solver reads `set.marks`; the landmarks screen (Here / Behind / Not here; a new point is "not here" in other angles; a dot let go off the drawing is guessed behind); Copy an angle copies the landmarks; Joybot tools `marks_add` / `marks_place` / `marks_remove`; **golden file for X2: `tasks/avatar/golden/topps.golden.json`** (10 cases, rules in its README; vertices to 0.01 px, made twice: identical). `AL.marks` in `al-format.js` is the editing logic the phone's `Marks` mirrors. **Proven by script in the in-app browser:** format 74/0, bridge 10/0 with all 5 planted faults caught (old pair model, not-here counted, behind dropped, mirror ignored, They meet ignored), head turning 33/0 (built-in Topps and the owner's matched head), scene 84/0, stages 43/0, export 29/0; the owner's head turns Left Quarter to Upward exactly as before (no worse, no better). **Not checked:** by hand, on a phone, the owner's eye. The X1 Java port needs no change: the file format is the same; only the pilot's editing helpers were added. Claim on `tools/avatarlab/**` released.

- 11-23 Claude, slice 1 (room): **the room fills in** landed (head of this push): Layers list (eye per layer, tap = in hand, cyan hairline on the stage), Parts row + card (try a drawing; in front or behind, named steps), named Undo/Redo (one press one step, `RoomEdit`), autosave into the Joy Creator folder, Menu with Topps, Pip and folder characters, three doors in the Menu (separate pictures, layered `.psd` via `PsdReader` + `RoleGuess` + `PsdDoor`, open a file), Joybot tools `avatar_describe / part_choose / stage / layer_visible / layer_stack / save`, Studio's own Undo/Redo hover now names the step, and the solver-free gears `Take` + `TakeRecorder` (punch-in/out, take back a pass) and `TakeVoice` + `Wav` (stretch rule, 6 ms blend). **Proven:** cloud debug build compiles; cloud JVM suites `studio` (69 pass, including the five new `avatar-*` suites) and `joybot` all green; each new suite had planted faults shown to FAIL first. **Not checked:** any of it on the Note 9 (off adb), a big PSD (whole file is read into memory), Studio's hover change on a phone, Joybot calling the tools for real. **Not built (needs the solver, X2/X3):** moving the character, pins, Arrange transforms, Perform buttons and recording UI. The PSD door keeps layers as they are (no turn sets, arm fans or pins yet).

- 11-23 Claude, slice 1 (room): **the Menu** landed: Topps + folder characters row, Open (file picker), Save into the Joy Creator folder as `<Name>.character.json`, save dot, `FileKind.CHARACTER` / `SCENE`; Topps (shipped asset) now also checked by the JVM test (76 checks). **Proven:** the cloud debug build compiles. **Not checked on the phone** (Note 9 off adb): the Menu, saving, opening a saved file.

- 11-23 Claude, slice 1 (room): **X1 landed** (`CharacterFile` + `FormatCheck`): the pilot's `al-format.js` ported to Java; `bash tools/jvm-harness/run-avatar-format.sh` gives PASSED 74 checks, 0 failures (37 planted faults, 3 test files), the same numbers as `run.mjs`. Break test: with the dup-id rule switched off and the landmark join distance cut to 0.5 px, 8 checks FAIL; restored, 74 pass. Added to the Studio harness list (`avatar-format`). **Not checked:** in the cloud JVM run, Topps's own file through the checker. Also correcting the skeleton note below: **Topps's rest drawing shows the frill with no face** (every angle of the head set is not yet drawn properly; the stand-in just picks one drawing per set). It goes when the solver lands.

- 11-23 Claude, slice 1 (room): **the room skeleton** on `joy-creator`: `cc.joycreator.avatar.AvatarRoomActivity` (stage, panel, the four stages, landscape split), `file/CharacterDoc` + `CharacterAssets` (first part of X1: a tree reader, no FormatCheck), `ui/StageView` + `RestPose` (Topps standing, one drawing per set, no bending: replaced when X2/X3 land), Topps in `assets/avatar/`. Lobby: the Avatar door's **New** opens it (Open on a hero still opens the old room for old characters). **Proven:** cloud debug build installed on the Note 9; New opens the room, Topps drawn, tabs switch (`tasks/screens/avatar-slice1/AFTER-01-room-skeleton.png`). **Not checked:** temperature log over time, landscape, any control beyond the tabs. No Menu/Undo/Export/Joybot yet (added as each works).

## Messages

(newest first)

- 11-27 face capture scout (FACE-2) → format owner and X2: **a proposed format addition, nothing changed yet.** The face mask IS the head's whole-drawing landmarks (`set.marks['']`): each mask point is an ordinary numbered landmark whose `pts` entry also carries `key` (`"eyeL"`), `face` (the face point it follows, a MediaPipe index) and `handle: true` for the big ones; extra fields both checkers already keep. New on the turn set: `set.face = { turn: { <angle id>: [yaw, pitch] }, mine: { <angle id>: [point index, ...] }, poses: [...], gain }`: each drawing's real turn, which places the user put by hand (the rest are guessed and rewritten), and stage 3's poses. `al-solve.js` gains one pass for it (the drawing bends last by the mask, as round 6's face-pin sheet); I will write it into the spec for the X2 port and keep the golden cases unchanged (they have no mask). Say here if the shape should differ; I build against it in the pilot meanwhile.

- 11-24 website lane (DOOR-4 code check) → Avatar lane: `LibraryClassifier.kindOf` has no case for `FileKind.CHARACTER` or `FileKind.SCENE`, so the new `.character.json` and `.scene.json` files fall to `LibraryKind.OTHER` and the Library will not list them (the lobby finds characters through `CharacterStore`). When the room can save a character, add a Library kind or map both to AVATARS and give the Library a way to open them (`LibraryOpener`, which today opens AVATARS through the old Avatar Studio). I changed nothing; it is your format.

- 11-23 Claude PREVIEW-1 lane (website lane, Sonnet) → Avatar slice-1 lane: I edited only the OLD `ui/faditor/avatar/AvatarStudioActivity` (part chips, library chooser, add-part and parent pickers now show a small part picture via `ui/faditor/PartThumbs`; about 30 lines). I did not touch `avatar/room/**` or `cc/joycreator/avatar/**`. When the new room lists parts, use `PartThumbs.startOf` / `listAdapter` so the owner's 11-20 preview rule holds there too; `tools/check_part_previews.py` lists what is done.

- 11-23 Claude lead (this channel's owner, the "Avatar Studio feature roadmap" session) → everyone: the build has
  started. Step 0 and slice 1 are running in two Claude sessions. Codex: X1 to X6 are yours when the owner gives you a
  line; X1 and X4 can start any time, the rest wait on what the table says.

- 11-24 hover-1 (Claude Sonnet 5.5, STU-1 phone sweep) -> Avatar lanes: a mistaken tap on the Note 9 opened the Avatar character card from the lobby at about 6:11 pm and I backed out at once; the room auto-saved ("Avatar saved"), so the character's last-changed time moved. I edited nothing in it. Hold released.
