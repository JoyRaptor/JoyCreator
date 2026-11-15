# Reel per room: the lobby's room loops

**Job:** L29 / SHOW-3 prep  ·  **Written:** 2026-11-15  ·  Feeds [DOOR-2](../jobs/DOOR-2_lobby_landing.md) (the owner's idea of 11-14: a room you have no project in plays a short loop of what that room makes: an ad for the room, never for money).
Shot numbers refer to [SIZZLE_STORYBOARD.md](SIZZLE_STORYBOARD.md) and [SHOT_LIST.md](SHOT_LIST.md). **No new filming is needed for most reels: each is a re-cut of sizzle shots.**

## Rules for every reel

- **6 s seamless loop**, silent (the lobby makes no sound), no text longer than three words, **no price, no "free", no ad copy** (it is an ad for the room, never for money).
- **Shape: the hero's shape is DOOR-2's to fix.** Master each at **9:16, 720 x 1280** with the subject inside the middle square, so DOOR-2 can crop to a square or a wide hero without a re-edit. Re-export, not re-film.
- **Size:** about 1 MB each, 30 fps. Seven reels is about 7 MB, a small cost against the Play build's 145 MB phone download (limit 200 MB, `tasks/launch/PLAY_BUILD_REPORT_20261114.md`). The way they ship (MP4, or the moving WebP sprite sheets the Library already plays, `.5x4.sprite.webp`) is DOOR-2's call; the Library's sprite-sheet player is the existing precedent (STATE 5.55).
- **Real pixels only.** Every frame is a recording of the real app; a reel never shows a feature that is not in the build (no 🟡 without the owner's OK).
- **Plays only when the room has no project.** Once a person has a project there, the room shows their own work (the existing recents and hero), as today.
- Made once in Studio ("Reels" project, one timeline per reel), exported to the folder, then handed to DOOR-2.

## The list (the lobby's rooms: `ROOM_STUDIO`, `ROOM_JOYBRUSH`, `ROOM_VIZ`, `ROOM_TEXTLAB`, `ROOM_SPRITE`, `ROOM_CAPTURE`, `ROOM_AVATAR`)

| Reel | Room (lobby) | 6 s story | Re-used sizzle shots | New to film | Status |
|---|---|---|---|---|---|
| **R-Studio** | STUDIO | A clip is split, trimmed to the beat, a title slides in, captions pop; the playhead runs | S06 (cut), S07 (key/mask), S08 (captions) | none | **Ready** (🟢). Caption beats the demo's hook: "Cut. Key. Caption." |
| **R-JoyBrush** | JOYBRUSH | A pencil line, a colour, then a watercolour wash blooming; the paper's tooth in close-up | S03, S04 | none (Fox in the Rain close crops) | **Ready** for the pencil half; the wash half **after owner OK** (🟡). Until then use pencil + colour + erase only |
| **R-VisLab** | VIZ | A look swaps on the beat; words move as art | S09, S10 | none | **Ready** (🟢 ✔); no audio |
| **R-TextLab** | TEXTLAB | Gold letters catch light; they burn away; a new look swaps in | S11 | none | **After owner OK** (TextLab 🟡). Until then the room's hero stays the existing still (`TlLobbyHero`) |
| **R-SpriteLab** | SPRITE | A drawn character is bent and walks; the dope sheet ticks | S05 | none | **Ready** (🟢: puppet confirmed 2026-09-15) |
| **R-Capture** | CAPTURE | A screen recording with a face bubble on top of a drawing; the red dot blinks | S12 | none | **Ready** (🟢) |
| **R-Joybot** | AVATAR / Joybot (the room that carries the assistant; confirm the room's final name in DOOR-2) | The chat drawer opens, a request is typed, the timeline changes, undo | S13 | none | **Ready** (🟢 ✔). Make sure the key setup is not in frame |
| Library (optional) | LIBRARY | A card sheet plays; a look card moves | New: a 5-s film of the Library with moving cards | A short Library recording | After the Library design settles; not needed for launch |

## Reuse total

Seven reels from 8 sizzle shots (S03 to S06 and S08 to S13); **zero new filming** for the first seven. Making them is one Studio session after the sizzle edit: about an hour of agent time, plus the owner's one look.

## Order of work

1. After the sizzle shoot: crop each reel master (720 x 1280) from the sizzle takes and trim to 6 s with a loop point (first frame = last frame).
2. Send the folder to DOOR-2 with a one-line table (reel name, room, size).
3. DOOR-2 decides shape, playback (no audio, loop, pause when the room is not on screen to save battery and the 55-60 fps budget) and the "once there is a project, hide it" rule.
4. Owner pass on the lobby once, after DOOR-2 builds it.

## Not in scope here

The playback code, the hero's crop, the empty-room logic, and any lobby redesign (the owner's 11-14 ruling: the lobby needs connecting, not a makeover).
