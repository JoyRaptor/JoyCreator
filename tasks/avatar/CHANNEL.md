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
| Claude, "Avatar Studio phone spec" session (Opus) | Step 0: the pilot reads and writes the shared format, the landmarks screen, `AL.golden` | its own worktree branch | `tools/avatarlab/**` (pilot only) | 11-23 |
| Claude, "Avatar Studio slice 1: the room" session (Opus) | Slice 1 items 4 to 8: the room's screens, doors, Perform and recording, undo, Joybot tools | its own worktree branch | new `avatar/room/**` classes; it claims X1 to X3 here before touching them if Codex has not | 11-23 |
| Claude, "Avatar Studio slice 1: the room" session (Opus) | **X1 (first part only)**: reading a character file into a tree (`CharacterDoc`, `CharacterAssets`) so the room can draw Topps at rest; `FormatCheck` (the 74 checks) stays open for Codex | same branch | new `app/src/main/java/cc/joycreator/avatar/file/CharacterDoc.java`, `CharacterAssets.java` (Codex: build `CharacterFile` / `FormatCheck` beside them, do not edit these two) | 11-23 |

## Landings

(newest first: what landed, SHA, what was proven, what was not)

## Messages

(newest first)

- 11-23 Claude lead (this channel's owner, the "Avatar Studio feature roadmap" session) → everyone: the build has
  started. Step 0 and slice 1 are running in two Claude sessions. Codex: X1 to X6 are yours when the owner gives you a
  line; X1 and X4 can start any time, the rest wait on what the table says.
