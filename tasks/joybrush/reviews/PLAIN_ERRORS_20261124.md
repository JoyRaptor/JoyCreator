# No technical errors shown: JoyBrush sweep, 2026-11-24

By Claude Opus 5.5. JoyBrush's three bars include "no technical errors ever shown". A read-only sweep found every way a message reaches a person in JoyBrush, and hundreds of sentences that carry the app's insides: internal ids, "renderer", "cel", "slab", stored-tile kinds, zip and archive internals, exception class names, byte counts. Most arrive as an exception's own text on the end of a plain sentence ("Couldn't export. Your drawing is safe. this PNG renderer supports raster layers; vector rendering is not connected").

**What changed (code at `566fcb8f1`, cloud build 45431491457 green, Android suite 616 tests pass):** one rule at the points where messages reach the screen, rather than hundreds of edits in files other sessions hold. `chrome/PlainWords` keeps the plain sentences in front and drops the technical tail; a message with nothing plain left becomes "That didn't work, and your drawing is safe. Try again, or close and reopen the drawing."; the full text goes to the log. Wired at `JoyBrushActivity.toast` (every save, open, export and canvas refusal toast), the board refusal (`BoardRuntimeController` and `BoardExportCoordinator`), brush-mode errors (`UnibrushHostController.say`), and "Not deleted" in the drawings list. A person's own names in quotes ("Sketch" is locked) and plain messages pass untouched. `PlainWordsTest` pins it with real messages from the app (tails dropped, wholly technical ones replaced, plain ones untouched, the usual technical words caught). No engine file was changed; engine messages are covered because they reach the screen through the same points.
**Left for later (a content job, not urgent):** the dropped tails were often the only clue to what went wrong; the better long-term fix is a plain sentence at each throw site (most live in `androidkit/io/JbArchive.kt`, `ZipCarry`, `DeferredMedia`, and many are pinned by tests). Not seen on a phone.

## The same rule for Studio, TextLab and the Library (2026-11-24, code `da7088327`, cloud build 45434400996, Studio harness 79 and JoyBrush Android 616 pass)
Every place in those rooms that put an exception's own text on screen now goes through `PlainWords.forPerson` (callable from Java): TextLab saving, renaming and deleting a look and opening a pack (`TlLookFiles`, `PackOpenActivity`), the Library's move to Trash (`LibraryActions`), and five Studio messages (export copy, gather, reorder, align, one export error). VisLab and SpriteLab had none. A ratchet, `tools/check_plain_errors.py` (in the cloud Studio run as `plain-errors`), fails when a screen shows `e.getMessage()` directly; Studio, TextLab and the Library are at 0. Still on the baseline, outside these rooms: 20 lines in 9 older screens (settings backup, recordings, the watch screens, the debug log, screen recording, battery, forensics), for a later pass; the ratchet stops them growing.

## VisLab and SpriteLab pass (11-24)

- VisLab: nothing to fix. VisRenderer's `lastError` and broken-layer reasons hold exception text, but they
  reach only the log (VisTextureView's fps line; its StatsListener has no caller). Save failures already
  toast `vl_save_failed`.
- SpriteLab: the sprite editor's bake and export messages were already plain. The JoyBrush board import
  (`JoyBrushBoardImportActivity`, both catch sites) toasted "Could not open board: <exception text>"; both
  now go through `PlainWords.forPerson`, pinned in PlainWordsTest ("Could not open board: null" reads
  "Could not open board.").
- Ratchet limit: `check_plain_errors.py` reads one line at a time, so an exception's text saved into a local
  first (as the board import did) is not counted. Reviewed by hand for VisLab and SpriteLab.
