# JB-7.03 — Export a puppet board as `.avatar` into the character library

| | |
|---|---|
| **Tier** | T2 |
| **Status** | 🟨 **Draft — the format belongs to the Studio and its schema is a Lead question.** See "Why this is a draft". |
| **Needs** | JB-7.02 (the rig exists and has been tested). **JB-7.02 is Draft; nothing beneath it is Built.** |
| **Owner area** | *(cannot be fixed until Q1.)* Provisionally: NEW `joybrush-android/.../board/AvatarExport.kt`. **No file in `app/`.** |
| **Estimated size** | ~200 lines |
| **Command** | `./gradlew -p joybrush :androidkit:compileKotlin :androidkit:test`; the watcher for `:joybrush-android` |

## Why this is a draft

Blueprint §4 Phase 7 ends with "Into the character library", and the row says *Export `.avatar` into
the character library*. The format is **not ours**: `AvatarLibrary` already owns it —
`files/avatar_library/<safe-name>-<id8>.avatar/avatar.json` with `libSchemaVersion`, the rig as
`rig.toJson()`, and the sheets beside it — and it already writes it atomically (temp dir, then rename,
because a save-over used to destroy the bytes it was about to copy).

So there is nothing to invent and nothing to copy. **What a spec cannot do is decide what a Joy Brush
board's art becomes inside that bundle**, and that is the whole row. See Q1.

## Goal

Blueprint §2: "a character drawn in Joy Paint talks in the Studio." This row is the hand-off: the pins
and the art leave Joy Brush as a `.avatar` in the Studio's own library, and opening it in Avatar Studio
gives back the rig you tested — not a lookalike.

Two buttons, matching the owner's Sprite-export precedent ("Export" / "Export and open in SpriteLab",
and "a file is always written first"): **Export `.avatar`** writes the bundle and stops; **Export and
open in Avatar Studio** writes it, then opens it. **The file is written before anything is opened, and
an open failure is a message, never a lost export.**

## Decisions (the ones that are genuinely Joy Brush's)

1. **The bundle is written by the Studio's `AvatarLibrary.save`, called from `joybrush-android`.** Not a
   Joy Brush writer, not a second layout, not a zip written by hand. R23. If `AvatarLibrary.save` turns
   out not to be callable from outside `app/`, that is Q1(a) — and the answer is a move under D.03, not
   a second writer.
2. **The sheets are PNGs Joy Brush already knows how to write** (`PngWriter`, JB-2.14a), rendered by
   `RegionRenderer` (JB-2.13a) — so an export is the same code path as Export PNG, and there is no new
   renderer. `AvatarLibrary.save` wants `List<SpriteSheet>`; **how many sheets, at what size, is Q2**.
3. **The art is flattened at export time and the pins are not pixels.** A `.joybrush` holds strokes; a
   `.avatar` holds sheets and a rig. So export rasterises, once, and the rig travels as pins.
4. **Save-over is the Studio's, not ours.** `AvatarLibrary.save` already does temp-then-rename for
   exactly the reason it does. Joy Brush must not "help" by deleting the old bundle first — that is
   the data-loss bug its own comment records.
5. **Refuse in words, never half-export.** No rig (`AvatarRigValidator` says so, in the Studio) → no
   file, and the message says what is missing. An art size of 0 → no file. Nothing is written to a
   temporary name and left there.
6. **The name comes from the board, sanitised by the Studio's own rule.** `AvatarLibrary.entryDirName`
   does `"My Dino" + "3fa4."` → `My_Dino-3fa4a1b2.avatar`. Joy Brush does not write a second
   sanitiser; a shared constant is never copied, and a sanitiser is a constant in spirit.
7. **One export is one undo-free operation.** It never dirties the document (JB-7.02 Decision 4, same
   reason), so autosave does not fire and the `.joybrush` on disk is untouched.
8. **Export is refused while a stroke or a rig test is in progress.** The art must be a settled state;
   exporting a half-drawn board is not a thing.

## Tests

The bundle format is the Studio's, so most of this row is verified by **the round trip**, which is the
only test that means anything here:

1. **Round trip (D1, D2, D3):** export a fixture puppet board, then `AvatarLibrary.load` the entry that
   comes back, and assert: the rig's part count, each part's `restPins` count and each pin's
   `x`/`y` within 1/1024 of what went in, and the sheet dimensions equal what was asked for. **If the
   pins do not survive the round trip, this row has failed**, regardless of how clean the file is.
2. **No rig, no file (D5):** a board with one pin exports nothing and the message names what is
   missing; `AvatarLibrary.list` count is unchanged before and after.
3. **Save-over keeps the old bundle until the new one is whole (D4):** assert the temp-then-rename
   behaviour by listing the library directory mid-flight — nothing but the entry dir and its `.tmp`
   sibling may exist, and after a successful save the `.tmp` is gone.
4. **A hostile board name cannot escape the library directory (D6):** `"../../evil"`, `"a:b"`,
   a 300-character name and a name that is only spaces each produce either a safe entry name or a
   refusal — and `Test-Path`-style verification that nothing was written outside
   `files/avatar_library/`. *(This is `JbArchive`'s `unsafeReason` discipline applied to a second
   archive: an entry name is never trusted, on read AND on write.)*
5. **Export never dirties the document (D7):** the `JbDocument` deep-copied before is `==` after,
   including every board, layer and cel.
6. **Refused mid-gesture (D8):** with a stroke in progress, export returns a refusal and writes
   nothing.

## Do not

- Do **not** write an `.avatar` file, a zip, a manifest or a schema version. `AvatarLibrary` owns all
  of it.
- Do **not** add a rig field, a sheet format or a `libSchemaVersion`. If Joy Brush needs one, that is
  an app-side change under the serialised order — referred, never done from here.
- Do **not** delete an existing entry before writing the new one (Decision 4).
- Do **not** sanitise a name in Joy Brush (Decision 6).

## Definition of done

- [ ] builds + tests green (paste)
- [ ] only owner-area files changed (paste `git status --short`)
- [ ] the round-trip test passes (paste it) — **this is the row's real acceptance**
- [ ] committed as `JB-7.03: export .avatar`; pushed
- [ ] ROADMAP row → 🟧 Built — after Q1

## Questions — for the Lead

**Q1. BLOCKING. Is `AvatarLibrary.save(Context, AvatarRig, List<SpriteSheet>)` callable from
`:joybrush-android`?** R23 says integration code calling the Studio's classes lives in
`joybrush-android`, and that module is in the app build, so read-only imports work today. But
`AvatarLibrary` is `final` with a private constructor and a static API — that is callable. **What I
cannot tell is whether you want it called at all from Joy Brush**, given D.03 will move
`transform/mesh/` and the avatar package may follow. The three answers: **(a)** call it as-is and
accept the coupling; **(b)** move `AvatarLibrary` into `:studiokit` under D.03 first; **(c)** add a
small public seam in `app/` and call that. **(c) touches `app/` and is therefore serialised behind
everything else on the board's order.**

**Q2. What is a Joy Brush puppet board, as a bundle?** A puppet board has layers of art and pins over
it. `AvatarLibrary.save` takes sheets and a rig of `Part`s, each `Part` naming one `sheetId`. So:

- **one board → one `Part` + one sheet** (the whole drawing, pins at board scale), or
- **one board → one `Part` PER LAYER** (which is how a character with separately-moving pieces is
  actually built), or
- **one board → one `Part` per pin group**, with Joy Brush inventing grouping.

I have written **the first** provisionally, because it is the only one that needs no concept Joy Brush
does not already have. But the first cannot express a rig that moves an arm independently, and the
owner's acceptance test for Phase 7 is "a character drawn in Joy Paint talks in the Studio", which
suggests pieces. **This is the ruling I most need**: it decides whether a puppet board can ever become
a multi-part character, or whether that is what JB-7.04's Character board is for (it is, I suspect —
which is why I lean to the first).

**Q3. Sheet size and the "include paper" question (D2).** Export PNG (JB-2.13b) asks "Include paper"
and offers 1×/2×/4×. A rig's rest pins are **normalised**, so the sheet's pixel size does not change
where a pin lands — but it changes the resolution of the art, and a character that looks crisp at 512
and soft at 2048 is a real difference. **Provisional:** the board's rect at 1×, paper included (a
character with a transparent background goes into the Studio on a white page, which is not what the
artist drew). Change either.

**Q4. Where does the exported character appear for the owner to check?** The board's acceptance is
T3 — "a character drawn in Joy Paint talks in the Studio". The STATE.md note in the design record says
Avatar Studio is "present but not reachable from the UI at launch", and the lobby's "Character" chip
sends to that gradient. **So "Export and open in Avatar Studio" needs an intent into
`AvatarStudioActivity` that may not exist yet.** Is that a Joy Brush change (an `Intent` extra naming
the library dir), an app change (serialised), or a third thing (the owner opens the library by hand)?
