# PACK-1: Joy packs: one file that brings new things into Joy Creator, from us or from anyone

**Milestone:** M1 (format and built-in packs), after launch (selling)  ·  **State:** 🔨  ·  **Owner:** Claude Opus 5.5 "Visual preview thumbnails for looks" (TextLab phone engine session)  ·  **Checked:** 2026-11-12 against `3e282ca13`

## Why it matters (for the user)
New looks, brushes, papers, fonts and more arrive as one file the person taps to open, and Joy Creator knows what to do
with it. The owner can release TextLab looks (and anything else) in waves, keep some for sale, and let other people
make and sell their own packs.

**The owner's words (2026-11-12):** build so looks can be released in packs, in waves; start with them all in; a
`.joypack` is something other people can put together too; it should carry many kinds of things ("a lot of different
sockets"), any or all of them; when opened, the app reads its manifest and knows what to do.

## The design (recommendation; the owner may change any of it)

**A `.joypack` is a zip file with a manifest, `joypack.json`, at its top.** Everything else inside is ordinary Joy
Creator files with their usual readable endings (`.text.json`, `.vis.json`, `.brush.json`, `.paper.json`,
`.palette.json`, `.ttf`, `.cube`, pictures...). Nothing in a pack is program code: only data and shader text, so a
pack cannot read your files or reach the network (a shader can at worst stall the GPU, which is why new shader
pieces are test-run before use, as VisLab's design already says).

```json
{
  "format": "joy.pack/1",
  "id": "joycreator.textlab.film",          // unique: author + name, never reused
  "name": "Film titles",
  "author": "Joy Creator",
  "version": "1.0",
  "needs": "1",                               // the oldest Joy Creator pack level that can use it
  "about": "Chrome, steel, gold and neon, lit like a film poster.",
  "cover": "cover.5x4.sprite.png",           // optional: a moving picture for the shop and the Library
  "contents": [
    { "socket": "text-look", "file": "looks/dark-steel.text.json" },
    { "socket": "font",      "file": "fonts/archivo.ttf" }
  ]
}
```

**Sockets** (what kind of thing an entry is; one list for the whole app). The first ones are the supplies the app
already reads: `text-look`, `vis-look`, `vis-part`, `brush`, `paper`, `palette`, `font`, `grade` (`.cube`),
`caption-style`. More are added as rooms learn to take them (`vis-element` with its shader, `sprite-sheet`,
`sticker`, `sound`, `template`, `joybot-skin`, ...). **A socket this app does not know yet is kept, never dropped**,
and the pack says "needs a newer Joy Creator for 2 of its things", so a pack made for a later version still installs.

**Installing** puts the pack, unzipped, in its own folder: `Supplies/Packs/<Pack name>/` (the Obsidian rule: readable
files, one folder you can see, copy or delete). Every room that reads a socket also reads it from the installed packs.
Removing a pack is removing its folder. Changing a pack's look and saving makes your own copy in the usual folder
(`Supplies/Looks/`): **the user's copy wins**, and a pack update never overwrites your changes.

**Built-in packs** (in the app) use the same manifest, in `assets/packs/`, pointing at files the app already holds.
Each can be marked `"held": true` to keep it out of a release: that is how waves work. Today every built-in pack is in.

**Trust.** A pack may carry a signature. Packs signed by Joy Creator show as ours; anyone else's install with the
author's name shown and their new shader pieces off until the person turns them on (VisLab's rule). Paid packs:
buying on the website and opening the file (no store cut), or Play Billing inside the app later; the rules on linking
out of the app from inside it vary by country and must be checked before launch (owner and business lane).

**Room.** Measured 11-12: all 35 TextLab looks' own files are 61 KB; all TextLab assets about 1 MB (mostly fonts; the
biggest, Archivo, is already in the app). So a look pack is tiny; the real room is each look's moving preview
(about 0.9 MB as PNG, made on the phone, can be remade or shrunk). Room is not a reason to hold looks back; selling is.

**What packs cannot add yet:** a brand-new TextLab ability (a new kind of surface or particle) needs the shader
pieces built in, until the engine takes abilities as plug-in pieces (the separate abilities job, with the TextLab lead).
A pack look that needs a piece this app lacks is shown as "needs a newer Joy Creator", never broken.

## Done when
- [x] `joypack.json` read by one class, with every socket listed and unknown ones kept; tests on a plain JVM.
      `studiokit/.../joyfolder/JoyPack.java`; `tools/joyfolder-tests` "packs: manifest, sockets, unknown kept, unsafe
      paths refused" (26 passed, 11-12).
- [x] Built-in TextLab looks in three packs (Film 13, Poster 4, Stuff 18, from their `family`), all in:
      `app/src/main/assets/packs/textlab-*.joypack.json` (+ README). `"held": true` keeps a pack out (code path; not
      yet tried by flipping one on the phone).
- [x] One shelf of text looks for every room: `cc.joycreator.textlab.TextLookShelf` (built-in packs + installed packs
      + `Supplies/Looks/*.text.json`), used by the TextLab screen's chips and Studio's Look row.
- [~] Opening a `.joypack` installs it: `PackOpenActivity` (VIEW / SEND of a zip; shows name, author, version, what it
      holds, size, "N things wait for a newer Joy Creator"; Install) and `PackInstaller` (reads the whole pack first,
      refuses paths out of the pack, 300 MB / 5000 files cap; installs to `Supplies/Packs/<name>/`, same id = update
      in place). **Note 9, 11-12:** a two-look test pack made with `tools/joypack/make_pack.py`, opened from a file,
      installed, its two looks on the TextLab screen's shelf. Not yet tried: opening from the Library or a share,
      removing a pack's folder, a pack with fonts. An installed pack is one Library item (`FolderIndex` treats a
      folder with `joypack.json` like a plugin folder). Making a pack: `python -I tools/joypack/make_pack.py <folder>`.
- [ ] STATE row; help mapping. (STATE row added 11-12; help mapping pending.)

## Every wing reads its sockets (TEXTLAB_ROADMAP T14, 11-13, branch `claude/textlab-cross-wing-2dd79d`)

One reader for every wing: `studiokit/.../joyfolder/PackShelf.installed(fs, socket)` (installed, usable packs in
folder order, entries in manifest order; a missing file, a broken manifest or a later level is left out). JVM test
"packs: installed shelf by socket" in `tools/joyfolder-tests` (27 pass). `TextLookShelf` and the TextLab room's
pack fonts now use it too.

| Socket | Who reads it now | How |
|---|---|---|
| `text-look` | TextLab room, Studio's Look row (11-12) | `TextLookShelf` |
| `vis-look` | VisLab room, Joybot's VisLab actions | `vislab/room/PackLooks` + `VisLabSession`: offered after the built-ins like one of them, never copied out; the person's copy is written once they change it and wins from then on; "Revert to default" puts the pack's version back (the copy goes to Trash); delete is refused in plain words ("Removing the pack takes them away"). A pack installed while the app runs joins at once (`VisLabSession.packsChanged`). An id that clashes with a built-in gets the pack's name in front |
| `vis-part` | VisLab's parts shelf, one row per pack under the pack's name | `PackLooks.Part`, `KnobPanel.packParts` |
| `font` | Studio's text and caption font pickers, VisLab words, Joybot's font lists (and TextLab, 11-12) | `joyfolder/studio/PackSupplies` copies each into the app (`files/packs/<pack id>/`; Studio draws and exports from file paths, its export in a process of its own); `FontLibrary.fromPacks()` / `choosable()`, labelled "Name · Pack" |
| `grade` (`.cube`) | Studio's LUT list (filter sheet, LUT panel), preview and export | `PackSupplies` + `LutManager.presets(ctx)` / `nameOf`; a project stores `grade:<pack id>/<file>` so it finds the grade on any phone with the pack |
| `caption-style` (`.caption.json`, a saved style's JSON) | Studio's caption style ticker and Joybot's caption actions, preview and export | `PackSupplies` + `CaptionStyleStore` (listed after your own styles; not deletable, like the built-ins) |
| `brush`, `paper`, `palette` | **nobody yet**: JoyBrush has no supplies shelf at all (no `.brush.json` / `.paper.json` / `.palette.json` reader in `joybrush*/`); its engine is held (BOARD: Codex save lifecycle, JoyBrush Lead backlog) | spec below for the JoyBrush lead |

`PackSupplies.sync` runs once a run after the app opens (`MainActivity.onResume`) and right after an install
(`PackOpenActivity`); it writes `files/packs/shelf.json`. A removed pack's copies stay, marked gone: work that uses
its font, grade or caption style keeps its look, but the pickers stop offering them. Nothing is ever written into
the person's folder by this.

**Spec for the JoyBrush lead: brushes, papers and palettes from packs.** When JoyBrush gets a shelf of supplies
(its own `Supplies/Brushes`, `Papers`, `Palettes`), read packs the same way and nothing more:
1. On the folder thread, `PackShelf.installed(jf.fs, "brush")` (and `"paper"`, `"palette"`): each `Found` has the
   pack's name (`pack.name`, show it as the shelf row's heading), the file (`rel`) and a label.
2. Offer them after the built-ins and before the person's own, under the pack's name. Never copy them out.
3. Changing one and saving writes the person's copy into their own `Supplies/<kind>/` under the same name; that copy
   wins (the pack's stays as "back to the original", one step away), as VisLab's `PackLooks` does.
4. A drawing that uses a pack brush or paper must keep drawing after the pack is removed: store the brush/paper
   settings in the drawing (as a title carries its whole look), not just a reference to the pack file.
5. Papers that are pictures: read them through `JoyFs.open`, never by a file path (the folder is a SAF tree).
No engine change is needed for any of this; it is shelf code only.

**Owner decisions, not built:** signing (who counts as "ours"), the shop and paywall, which waves ship first.

**Note 9, 11-13** (test pack "Cross wing test": two VisLab looks, one part, a font, a grade, a caption style, made
with `make_pack.py`): installed from a file; the app's copies and `shelf.json` written; VisLab offered "Pack Aurora"
and "Coral from a pack" (its id clashed with the built-in Coral, so it became `cross-wing-test-coral`) and the part
under "CROSS WING TEST"; changing Pack Aurora wrote `Supplies/Looks/Pack Aurora.vis.json`, Revert moved it to Trash,
one undo brought it back; Joybot's delete refused in words; Studio's text font list offered "Pack Bebas · Cross wing
test" and the text drew in it. Pack folder removed: the untouched pack look left VisLab, the changed one stayed as the
person's own, Studio's three items were marked gone. **Not seen:** the grade in Studio's LUT list and the caption style
in the ticker (on disk and in the list only); a pack installed while VisLab is open.

## What is left
- [ ] Studio's LUT list and caption ticker with a pack's grade and style, seen on the phone.
- [ ] JoyBrush's shelf (spec above) when its lead takes it; `vis-element` (new shader pieces) with VisLab.
- [ ] Signing, the shop (owner).

## Specs, handoffs and evidence
- VisLab's earlier element-pack design: `tasks/VISLAB_TODO.md` section F. Roadmap "After launch": pack manager.
- TextLab: `tasks/jobs/TEXT-1_textlab.md` (Studio step, previews).

## Files and traps
- `studiokit/.../joyfolder/FileKind.java` (endings and Supplies folders), `FolderIndex` (a folder with `plugin.json`
  is one item: a pack folder with `joypack.json` should be treated the same way).
- The TextLab lead owns `app/src/main/assets/textlab/**`: built-in pack manifests live in `assets/packs/`, outside it.
