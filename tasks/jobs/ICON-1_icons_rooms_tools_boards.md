# ICON-1: Icons for every room, every thing you can make, and JoyBrush's tools and boards

**Milestone:** M1/M2 (launch polish)  ·  **State:** 🟨 options page ready, waiting on the owner's picks  ·  **Owner:** icons lane  ·  **Checked:** 2026-11-15

## Why it matters
The owner, 11-15: we need icons for every main room and for everything you can create or do in them. They are used twice:
in JoyBrush's Tools and in Joybot's create tree (BOT-7), where "new image" shows the same icon as the image board. He
generated first ideas in ChatGPT and wants **at least 3 or 4 creative options for each**, then picks.

## Owner's references (art, not in git)
`art/icon-references-20261115/`: boards in three styles (Board, Image, Animation, Sprite, Tile; options A to C), a second
style row, and tools (isometric grid, 2-point and 3-point perspective, ruler, set square and protractor, symmetry,
radial symmetry). Use them as direction and inspiration, not as files to ship.

## The list
- **Rooms:** Lobby, JoyBrush, Studio, VisLab, TextLab (an "A with a sparkle" may already exist), SpriteLab, Avatar
  Studio / character animator (a Vitruvian-style figure: standing, and arms out in a circle and square), Capture /
  recording, Library, Joybot.
- **Things to make (BOT-7 create tree):** drawing/painting (easel, or brush and palette); drawing in 1, 2 and 3-point
  perspective; hand animation (a film frame with sprockets and a running man); tile; sprite (make) and sprite (load and
  edit); video (film camera); audio project (music notes, clearly NOT VisLab); music visuals (VisLab's own: e.g. a note
  whose head is an eye, or a double note with eyes); titles; character; find and tidy (a folder with a magnifying glass
  punched out).
- **JoyBrush Tools → Boards** (its own section, out of the ⋯ menu): Boards, Image board, Animation board, Sprite board,
  Tile board.
- **JoyBrush Tools → Rulers and guides** (its own section): grid, isometric grid, 1/2/3-point perspective, ruler,
  symmetry, radial symmetry.

## Owner's additions (11-15, later)
- **Settings hub: Joybot is Joybot.** The "Joybot" row showed a generic robot glyph (`smart_toy`). It now shows his
  flat face (`drawable-xxxhdpi/joybot_neutral`, the white line art the lobby and chat use), in proportion and tinted
  like the other row glyphs: `SettingsCatalog.Row.art` + `SettingsRowView.bindGlyph`. The Settings screen is the
  shell-lobby lane's; it was told.
- **The mascot everywhere, where it fits.** Joybot helps with the thing in the icon (holds the ruler, peeks from a
  folder, cranks the film camera), drawn from his flat 2D face, with simple **clamp hands** (never the gloved
  four-finger hands of the sprite sheets). On the options page this is direction **E "With Joybot"**: all 28 icons,
  so the owner can take him everywhere, nowhere, or swap him in icon by icon.

## Done when
- [x] A mix-and-match page at true size (phone dp, on the dark house background): every icon in 3 or 4 creative
      directions, per-icon swaps, and a "copy my picks" line. As before, the owner picks and his pick becomes the spec.
      **Built 11-15:** [design/UI-Icons-options.html](../design/UI-Icons-options.html). 28 drawings in five directions
      (A Line, the TextLab pen; B Cut-out, solid with details punched through; C Two-weight, outline plus one solid
      meaning piece; D Pixel, 16x16; E With Joybot, his mini flat self helping, things in the Line pen), each shown at 48 dp, 24 dp in a 40 dp button, and chosen. Things to make that are
      the same idea as a room, board or guide share its drawing and its pick (titles = TextLab, music visuals = VisLab,
      character = Avatar Studio, hand animation / tile / sprite = their boards, the three perspectives = the guides).
      Picks, per-icon notes and the copy line live in the page; a "your picks in place" block shows the lobby rooms,
      JoyBrush Tools and Joybot's make list at true size. **Owed: his picks.** His pick line becomes §K below.
- [ ] The picked set built as Android vector drawables in one consistent style (white glyph; cut-out when chosen,
      on solid cyan per the ON-state rule; legible at 24 dp and 48 dp), with names the code uses.
- [ ] JoyBrush and Joybot lanes told where they are.

## Rules that apply
House look: greys fade and colour guides; flat; chosen = solid cyan with the icon punched out. Icons must read at small
sizes on the Note 9's dense screen (dp stays as drawn). Original drawings only; no copies of other apps' icons.
