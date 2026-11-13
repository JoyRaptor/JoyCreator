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
| T13 | Speed: Diamond 46, Crystal Ice 45, Rusted 42, Water 39, Fizz 38, Overgrown 36, Carved Stone 36, Voltage 35 ms on the Note 9 | TextLab lead (shaders) |
| T14 | Packs read by every wing: VisLab looks and parts, Studio fonts, grades (`.cube`) and caption styles (with text looks and TextLab fonts, 11-12); one reader `PackShelf`. Brushes, papers, palettes: JoyBrush has no supplies shelf yet, reader spec for its lead on [PACK-1](jobs/PACK-1_joypacks.md). Signing, shop, paywall: owner | [~] 11-13, Note 9 with a test pack: installed; VisLab offered both pack looks and the pack part; a change wrote your copy, Revert put the pack's back (copy to Trash), undo brought it back, delete refused in words; Studio's font list offered the pack font and drew in it; pack removed: its untouched look gone, your copy kept, Studio's three items marked gone. Grade and caption lists: on disk only, not seen in Studio's UI |
| T15 | VisLab: a Picture layer's "Words in a look" (pick a TextLab look, type the words; moving, one undo step; the layer carries the look). Specs on [TEXT-1](jobs/TEXT-1_textlab.md): the emitter as a VisLab layer (large), a JoyBrush text tool (none exists; its lead's call); hand-drawn ink as letters later | [~] 11-13, Note 9: HOT in Blaze on Pack Aurora, moving, kept in the look file, undo one step. Frame cost in VisLab not measured; Studio export of a VisLab look with words not tried |
| T16 | Words in `strings.xml` for translation: TextLab's on-screen English in `res/values/strings_textlab.xml` (206 strings, no visible change); Joybot-facing text, names stored as data and the debug screen stay in code. VisLab: not done (a later sweep, UI-1) | [~] 11-13, built; the pack screen seen on the Note 9 reading from the file |
| T17 | WebP previews: `.sprite.webp` in the sheet name rule (`SheetName`), the Library (`SheetLinks`, newer of two wins), TextLab writes WebP and drops its old PNG preview; old PNG sheets still read, renamed and trashed with their look. Sprite Lab reads no named sheets (nothing to change) | [~] 11-13, Note 9: a look's preview made by the Library as WebP, 77 KB against 894 KB for the PNG of the same look, same picture (2/255 mean difference); the Library card itself not looked at (the Library shows family photos) |
| T18 | The wing's name ("TextLab" is a working name); the Note 20; a long run's heat | owner |

## Waits on the owner

- Try the room, the lobby door, Edit in TextLab and Joybot in TextLab on his own phone.
- The verdicts in T12; whether "TextLab" is the name; when to put it on the Note 20.
- Packs: signing, the shop and paywall, which waves ship first.
