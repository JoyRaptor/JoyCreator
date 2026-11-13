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
- [ ] `joypack.json` read by one class, with every socket listed and unknown ones kept; tests on a plain JVM.
- [ ] Built-in TextLab looks in three packs (Film, Poster, Stuff, from their `family`), all in; a held pack disappears.
- [ ] One shelf of text looks for every room (built-in packs + installed packs + the person's own), used by the TextLab
      screen and Studio.
- [ ] Opening a `.joypack` (from the Library, a share, or a file manager) installs it to `Supplies/Packs/` and its
      looks appear; removing the folder removes them. Phone-proven.
- [ ] STATE row; help mapping.

## What is left
- [ ] The above, in order. Then the other sockets room by room (VisLab element packs join here), signing, the shop.

## Specs, handoffs and evidence
- VisLab's earlier element-pack design: `tasks/VISLAB_TODO.md` section F. Roadmap "After launch": pack manager.
- TextLab: `tasks/jobs/TEXT-1_textlab.md` (Studio step, previews).

## Files and traps
- `studiokit/.../joyfolder/FileKind.java` (endings and Supplies folders), `FolderIndex` (a folder with `plugin.json`
  is one item: a pack folder with `joypack.json` should be treated the same way).
- The TextLab lead owns `app/src/main/assets/textlab/**`: built-in pack manifests live in `assets/packs/`, outside it.
