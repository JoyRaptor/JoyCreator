# TextLab roadmap: from today to a finished wing (2026-11-12 night)

TextLab is Joy Creator's film-poster words wing (job card [TEXT-1](jobs/TEXT-1_textlab.md)): chrome, gold,
steel, neon, fire, ice and more, drawn live on the phone from the same shader and look files as the web look
book. This page is the wing's own roadmap (as VisLab has `VISLAB_ROADMAP.md`); `tasks/ROADMAP.md` links it.
Tick a row only after a Note 9 check; `[~]` = built and checked by an agent, the owner's own try owed.

Owner's direction (11-11 / 11-12): a handful of looks that would sit on a Hollywood poster, not a shelf of
presets; phone-first, one-handed; every look reachable everywhere words appear; packs others can make and sell.

## How to work (break none)

- Read first: the TEXT-1 card (its "Ship list" and "What is left"), `tasks/STATE.md` rows 5.55 and the TextLab
  rows, this file. The looks and shaders (`app/src/main/assets/textlab/**`, `tools/textlab-web/**`) belong to the
  TextLab lead session; the phone side (`app/src/main/java/cc/joycreator/textlab/**`) to the phone session.
- Builds: take `jb-gradle.lock` (create-new), `./gradlew :app:assembleDefaultDebug -x lint`. Phone: Note 9 only,
  `tools/phone.ps1 claim/release`; never `adb shell monkey`, never uninstall or clear data; press Home before an
  install if JoyBrush is open; log the battery temperature while testing.
- Checks that run off the phone: `bash tools/jvm-harness/run-textlabroom.sh` (36: the room's logic),
  `run-titlestyle.sh` (titles and their looks saved), `run-ideas.sh` (Joybot, incl. TextLab's tips and help),
  `tools/joyfolder-tests` (packs). On the phone, by adb (`TextLabDebugActivity`): `--es look <id>` stills,
  `--ez clearcheck true`, `--es export picture|video|sheet`, `--ez iconcheck true`, and
  `--es action textlab_change --es args '{"changes":[...]}'` (Joybot's actions with no AI, the room open).
- Every button gets a hover label; ON = solid cyan with the icon cut out; one press = one undo step; the
  person's copy of a supply wins and "back to the original" is one step away; no technical error is ever shown.

## Status

| | Item | State |
|---|---|---|
| T1 | Phone engine (every pass, fire, particles, shatter, photo stage), same files as the look book | [~] 11-12, parity measured on the Note 9 |
| T2 | Look previews: picture chips, moving Library cards (`.5x4.sprite.png`) | [~] 11-12 |
| T3 | Studio: a title wears a look (Look row, light Still / Sway / Record tilt, progress), preview = export; FX, bend, pin | [~] 11-12 |
| T4 | Joy packs (PACK-1): install, built-in packs in waves, unknown sockets kept | [~] 11-12 |
| T5 | Quick export: picture, video, moving sheet; shape and size to 4K | [~] 11-12 |
| T6 | Icons: 46 owner-approved, in the app (`ic_tl_*`, `TlIcons`) | [~] 11-12 |
| T7 | **The room** (`TextLabActivity`): every slider in its tabs with icons, + Pieces (add / take away, Fire included), light Live / Still / Sway, progress, background, fonts (own font from Supplies/Fonts, pack fonts), save (your copy wins), save as, rename, delete (Trash), back to the original, undo / redo, export, Joybot orb; plain words when a phone cannot draw the looks | [~] 11-12 night, Note 9: slider, undo, pieces, save as copy, revert seen |
| T8 | **Lobby**: TextLab room on the dial (its own blue), door, hero still of the last look, looks in recents | [~] 11-12 night, Note 9 |
| T9 | **Joybot**: `textlab_looks / _describe / _pick_look / _change / _save`, Studio's `set_title_look`, `open_room textlab`, room context, ideas, 5 tips, 6 help topics; one request = one undo step | [~] 11-12 night, Note 9: a free model made "Golden Metal Dark Steel" from the room |
| T10 | **Studio round trip**: Edit in TextLab on the Look row; "Use on the title" brings the look back as one undo step | [~] 11-12 night, Note 9 (the saved project carries the change) |
| T11 | **Library**: a look's card exports and renames (its preview follows; Trash takes both); a `.joypack` file opens the pack screen | [~] 11-12 night, Note 9 (export, rename row seen) |
| T12 | The owner's verdicts on the looks (Rusted, Crystal Ice, Phantom, Dust, Water's drips; next level for Liquid Chrome, Diamond, Retro Pop, Hologram) | owner |
| T13 | Speed: all eight slow looks under 33 ms on the Note 9 in the look book (Diamond 48 -> 25, Water 48 -> 32, Rusted 45 -> 20, Carved Stone 35 -> 21, the rest 17-22), pictures unchanged; the phone engine gets the speed when `TextLabRenderer` makes the same passes | [~] 11-13 look book (TextLab lead); port: phone engine |
| T14 | Packs: VisLab looks, brushes, papers, palettes, grades and caption styles read from packs (only text looks and fonts are today); signing; the shop and paywall | other wings / owner |
| T15 | VisLab reading the look shelf and the emitter; a JoyBrush text tool (none exists) so a look can go on drawn words; hand-drawn ink as letters | VisLab / JoyBrush leads |
| T16 | Words in `strings.xml` for translation (all TextLab English is in code, as in VisLab) | open: a whole-app job (UI-1) |
| T17 | WebP previews (PNG sheets ~0.9 MB): needs the sheet name rule to allow `.webp` across the Library and Sprite Lab | open |
| T18 | The wing's name ("TextLab" is a working name); the Note 20; a long run's heat | owner |

## Waits on the owner

- Try the room, the lobby door, Edit in TextLab and Joybot in TextLab on his own phone.
- The verdicts in T12; whether "TextLab" is the name; when to put it on the Note 20.
- Packs: signing, the shop and paywall, which waves ship first.
