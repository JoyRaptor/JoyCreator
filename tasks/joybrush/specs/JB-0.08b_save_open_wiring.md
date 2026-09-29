# JB-0.08b — Save, open and "Save a copy…" on the Joy Brush screen

| | |
|---|---|
| **Tier** | T2 (device check by owner) |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-0.05, JB-0.08a |
| **Owner area** | EDIT `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt` (add the functions below only), EDIT `joybrush-android/src/main/java/cc/joycreator/joybrush/android/JoyBrushActivity.kt` |
| **Estimated size** | ~200 lines |

## Goal
Work is never lost: autosave on leaving the screen, reopen where you left off, and a "Save a copy…"
the owner can put anywhere (Downloads, SD card, cloud folder).

## Decisions
1. **Working file:** `context.getExternalFilesDir("joybrush")/current.joybrush`. ⚠️ This folder is
   deleted if the app is uninstalled — that is why (3) exists, and why Joy Brush documents move to
   THE VAULT when roadmap item 2.1 lands. Never `adb uninstall` (START_HERE rule 3).
2. **Autosave** in `onPause` (and after 30 s of no strokes while open). Reopen `current.joybrush` in
   `onCreate` if it exists.
3. **"Save a copy…"** pill → `ACTION_CREATE_DOCUMENT` (type `application/x-joybrush`, default name
   `Joy Brush <yyyy-MM-dd HHmm>.joybrush`) → `JbArchive.write` to the returned Uri's stream.
   **"Open…"** pill → `ACTION_OPEN_DOCUMENT` → `JbArchive.read` → load.
4. JbCanvasView gets:
   ```kotlin
   /** GL thread does the reads; callback on the UI thread. */
   fun snapshot(onReady: (JbContents) -> Unit)
   fun load(contents: JbContents, onDone: () -> Unit)
   ```
   `snapshot` builds a JbDocument with `DocOps.newDocument` sized to the view (one CANVAS board,
   one PAINT layer whose id matches the engine layer, one cel "cel-1"), fills tiles from
   `engine.tileKeys` + `engine.readTile`. `load` calls `engine.resetDocument()` then `writeTile` for
   every tile of the first PAINT layer. (Multi-layer and ink arrive with 2.04/5.01.)
5. File I/O on a background thread (`Executors.newSingleThreadExecutor()`), never on the UI or GL
   thread. A failed open shows a toast with the exception message and keeps the current drawing.

## Verification
Watcher build green; on the SANDBOX phone: draw, press Home, force-stop from Recent apps, reopen →
drawing is back. Save a copy to Downloads, clear, Open… it → drawing is back. Screenshots.

## Do not
Don't write anywhere else on disk. Don't touch the engine or other app files.

## Definition of done
Screenshots · watcher green · commit `JB-0.08b: save, open, save a copy` · ROADMAP row → 🟧 Built.

## Questions

*Builder (stealth/space-bunny-alpha, JB-0.08b). For the Lead. The code is in and the watcher is
green; these are the places where the spec, the landed contracts and the owner's rule "never
silently drop anything" do not line up, and where I chose the reading I say below rather than
inventing a format or a signature.*

**Q1 — "Save a copy…" cannot be atomic, and I do not think it can be.** `JbArchive.save`'s atomic
write (`.tmp` → `.bak` → rename) only works on a `java.io.File`. A SAF `Uri` has no rename, so the
copy is written straight into the stream the picker handed back, which truncates on open. What the
archive gives us: `JbArchive.write` validates the whole document — ids, tile sizes, every declared
tile present — *before the first byte leaves*, so a document that cannot be written leaves the
target untouched. What it cannot give: protection from an I/O failure part way through the bytes (a
full card, a revoked provider), which would leave a half file at a name the person chose. On
failure the toast names it ("The copy did not work: …"). The working file, which is the one that
holds the unsaved work, IS atomic — it goes through `JbArchive.save`. Do you want (a) this,
(b) build in `cacheDir` first and then copy the finished file over, which still cannot make the
SAF write atomic but does keep a complete `.joybrush` on the device, or (c) a confirmation before
overwriting a target that already exists?

**Q2 — opening a file replaces the working drawing.** The spec says "Don't write anywhere else on
disk", so there is no `previous.joybrush`. Today the safety net is `JbArchive.save`'s own rotation:
after you Open… B, the next autosave moves `current.joybrush` (your drawing A) to
`current.joybrush.bak` before putting B in place, so A survives — until the autosave after that one.
One generation, not a promise. Should there be a `previous.joybrush`, or is the `.bak` enough?

**Q3 — "the engine cannot show this" is a second refusal, and I put it in the view.** `JbArchive`
already refuses a file it cannot represent, in words. It does *not* refuse a perfectly valid
multi-layer or ink document, because the format is right; it is the GPU engine that holds exactly
one layer. `JbCanvasView.load` therefore refuses — synchronously, on the caller's thread, before
any GL work is queued, so the drawing already on screen is untouched — anything with more than one
layer, cel or board, a non-PAINT layer, an animated layer, ink strokes, a non-CANVAS board, a paper
texture, or a tile that is not a tile. It throws `JbArchiveException` so the screen has one catch.
Is the engine's "can I draw this?" rule meant to live here, or in the archive?

**Q4 — a snapshot mid-stroke saves the last committed tiles.** The stroke under the pen is a live
preview in the engine, not a tile yet, so a save taken with the pen still down does not contain it.
The 30 s idle rule makes this reachable only through onPause with the pen down. The view cannot even
ask: `GlPaintEngine` exposes nothing that says "a stroke is in progress", and it is outside my owner
area. Adding `strokeInProgress` to the engine would let the view refuse, or defer, the snapshot.
Worth a one-line addition there?

**Q5 — the board is sized to the view, not to the art.** `DocOps.newDocument(id, name, w, h, ids)`
is what the spec named, so the CANVAS board is the view's size at the moment of the save. Tiles are
in document coordinates and are unaffected, so the drawing always comes back; only the board rect —
which is export bounds, JB-2.13b — is whatever the screen happened to be. An autosave before the
first layout would write a 1×1 board. Should the board instead be the bounding box of the tiles,
clamped to at least the view? That is a different `newDocument` call and I did not make it up.

**Q6 — a document with a paper texture will not open here.** `Paper.textureId` is refused on load,
because this screen cannot render a texture and a later save would drop it — that is the
"refuse, never skip" rule. But JB-2.13b adds paper texture, and every document it writes will be
unopenable until `load` learns about textures. Confirm that is the right trade, or tell me to ignore
the texture and say so in the UI.

**Q7 — `thumbnail.png`.** `JbArchive`'s own table says the thumbnail entry is "written by JB-0.08b",
and this spec never mentions one. I pass `thumbnailPng = null` (the field's default) and
`JbArchive.write` skips the entry, so the files this build writes have no thumbnail. Producing one
means downscaling tiles to a small RGBA buffer and running `PngWriter` (JB-2.14a, landed) — doable
inside the view — but it is not in this spec, so I did not add it. Yes or no?

**Q8 — two things the spec did not say that I had to decide.** (a) `DocOps.newDocument` asks its
`ids` lambda for the board, then the layer, then the cel; the document's layer id has to be the
engine's own `layerId` or the next stroke lands on a layer nothing draws on. I pass the three ids
in that order and then check the layer id came back as expected, so if the factory's order ever
changes the save says so instead of writing tiles nobody can see. (b) The paper colour is carried
both ways (`#RRGGBB` ↔ ARGB) so a document that came back from disk keeps its paper. Both are
inside my owner area; neither is in the spec.

**Owner note, not a question:** the working folder `getExternalFilesDir("joybrush")` is deleted on
uninstall, exactly as the spec's decision (1) says. Never `adb uninstall` this app.

