# SPEC — Semantic cells: names as the AI contract

**Status:** 🔵 SPECCED · 2026-09-10
**Surfaces:** SpriteLab (authoring) · sprite palette (display) · AI toolkit (the payoff)
**Companions:** SPEC_20260910_SPRITELAB_UI.md, SPEC_20260910_SPRITELAB_MODEL.md

---

## 0. Why this is not a labelling feature

The obvious reading of "let me name cells" is *so the user can find them*. **That reading
is wrong and it would produce the wrong feature.**

JoyRaptor, 2026-09-10:

> *"unless a frame has really hard to see details, I am going by visuals. Even on the tape
> in the phone timeline where the image is very small, many times I can see and understand
> the change. The reason I want to give cell names is less for in-UI, and more because it
> super-powers the LLM side of our app."*

A human looks at a picture. **A language model cannot.** Every AI capability in this app
that touches a sprite sheet is currently blind to what is drawn on it — `describe_sprite_sheet`
can report a cell's bounding box and how many pixels are opaque, and nothing else. It can
tell you cell 14 is 62% full. It cannot tell you cell 14 is a shrug.

Names close that gap, and JoyRaptor has already tested what happens when they do:

> *"'surprise face' — an LLM might also double for the 'O' phoneme, a 'wide smile' for 'E'.
> …I have already experimented and LLMs can infer many secondary uses for a well named cell."*

That is the whole thesis. **A well-named cell is reusable in ways nobody enumerated**, and
the model finds those uses on its own. A numbered cell is reusable in zero ways.

Three consequences follow, and this spec is the three of them:

1. **Names must be authored somewhere pleasant** — SpriteLab, §2.
2. **Names must survive the trip** — the JSON and the importer, §4.
3. **The AI must be told this is possible**, or the capability exists and nobody uses it, §6.

---

## 1. What already exists (verified 2026-09-10)

| Piece | State |
|---|---|
| `SpriteSheet.cellNames` — `Map<Integer,String>`, sparse, round-trips | ✅ built |
| `SpriteSheet.Cell.tags[]` — a list of strings per cell, serialised | ✅ built, **never written by anything** |
| `label_sprite_cells` AI tool — writes names by index | ✅ built |
| `describe_sprite_sheet` — reports occupancy, boxes, and "any existing cell names" | ✅ built |
| `AvatarRig.visemeMap` — `Map<String,Integer>`, **name → cell index** | ✅ built |
| `SpectralVisemeAnalyzer` — 6 classes: `REST AA EE OO CLOSURE FRIC` | ✅ built, 🟡 undriven |
| Word-level transcript timing (Vosk + Whisper LCS merge) | ✅ 🟢 the app's strongest asset |
| Anything that **displays** a cell name | 🔴 **does not exist** |

So the format holds names, three things write them, and **nothing reads them back**. Same
shape as the saved-animation gap: engine shipped, door did not.

`visemeMap` being `Map<String, Integer>` is the detail that makes this cheap. It is already
keyed by name on one side. Naming the cells closes the loop.

---

## 2. Authoring in SpriteLab

**Long-press a cell** (or press `N` with a cell focused) opens a small inline editor:

```
┌ cell 14 ───────────────────────┐
│ name  [ shrug              ]   │
│ tags  [body] [uncertain] [+]   │
│ viseme  ○REST ○AA ○EE ●OO ○CL ○FR │
└────────────────────────────────┘
```

- **Name** — one line, free text. Suggest-as-you-type from names already used on this sheet
  so a set stays consistent (`talk_a`, `talk_b`, not `talk_a`, `TalkB`).
- **Tags** — chips, multi-select, free text. The format has carried `tags[]` since it was
  written and nothing has ever put anything in it. Tags are where the *secondary* meaning
  lives: `mouth`, `open`, `surprise` on one cell lets a model reach it three ways.
- **Viseme** — see §3.

**Bulk naming** matters more than single naming, because a mascot sheet is thirty faces:

| Tool | Behaviour |
|---|---|
| **Name the sequence** | Type `talk` with 6 chips selected → `talk_01…talk_06` |
| **Name from clip** | A saved clip named `wave` names its frames `wave_01…` |
| **Copy names across sources** | Two sheets of the same character: match by position, carry names over |

Named cells show their name **under the thumbnail on the chip and under the cell on the
grid**, at a small size, truncated. This is a courtesy, not the point — JoyRaptor navigates
by picture — so it must never push the picture smaller. Toggleable.

---

## 3. The viseme rail

Six buttons, one per class `SpectralVisemeAnalyzer` already emits. Drag a cell onto one, or
select a cell and press the class. Assignments show as a small mouth glyph on the cell.

| Class | Means | Typical cell |
|---|---|---|
| `REST` | closed / neutral | idle mouth |
| `AA` | open jaw — "father" | shout, yawn |
| `EE` | wide, spread — "see" | **wide smile** |
| `OO` | rounded — "boot" | **surprise face** |
| `CLOSURE` | bilabial — m, b, p | pressed lips |
| `FRIC` | fricative — s, f, sh | teeth showing |

The parenthetical examples are JoyRaptor's own: *"'surprise face' an LLM might also double
for the 'O' phoneme, a 'wide smile' for 'E'."* A cell is **not** consumed by a viseme
assignment — `surprise` stays a usable expression *and* answers for `OO`. One drawing, two
jobs, which is the entire economy of a small hand-made sheet.

Assigning all six is what makes **automatic lip sync** possible the moment the sheet lands
on the phone: audio → `SpectralVisemeAnalyzer` → class → `visemeMap` → cell. Every piece of
that chain exists today except the map, and the map is six taps.

**An LLM can also fill the rail from names alone** — that is §6's `infer_sprite_semantics`.

---

## 4. The JSON, and why human-readable is a feature

```json
{
  "spriteSchemaVersion": 1,
  "name": "joybot",
  "sheetUri": "joybot.png",
  "cols": 6, "rows": 5, "fps": 12,
  "cellNames": {
    "0": "idle", "1": "blink", "7": "thinking",
    "12": "surprise", "13": "wide_smile", "20": "shrug"
  },
  "cells": [
    { "index": 12, "name": "surprise", "tags": ["face", "mouth", "open", "round"] },
    { "index": 13, "name": "wide_smile", "tags": ["face", "mouth", "happy", "spread"] }
  ],
  "visemeMap": { "REST": 0, "AA": 12, "EE": 13, "OO": 12, "CLOSURE": 1, "FRIC": 13 },
  "presets": [
    { "id": "p1", "name": "thinking", "type": "loop", "fps": 6, "frames": [7, 8, 9, 8] }
  ]
}
```

`cellNames` and `cells[].tags` are read by `SpriteSheet.fromJson()` today, unchanged.
`visemeMap` is **additive and currently ignored** by the sheet reader — the importer routes
it into a rig (§5). Nothing about the existing contract breaks.

**Readability is load-bearing, not cosmetic.** JoyRaptor:

> *"the naming makes the JSON human readable. Important for non-technical users who look at
> it and may want to add new animation via text later or tweak a single thing they couldn't
> get right in the editor — or an AI LLM looking at it and inferring NEW subtle variants
> and adding them to the JSON."*

A non-programmer can open a file that says `"thinking": frames [7,8,9,8]` and add
`"thinking_fast"` with a higher fps. They cannot do that with `[7,8,9,8]` and no names. So:

- **Write `cellNames` even when sparse.** Never omit it as "optional metadata".
- **Order keys for reading**, not for the parser: identity, geometry, names, visemes,
  animations last and longest.
- **Pretty-print with two spaces.** The file is a document, not a wire format.
- Frames stay literal integers. `weights` only appear when something is actually held.

---

## 5. The phone side

| # | Change | Size |
|---|---|---|
| 5.1 | **Cell names render under palette chips** and on the frame-track tape. The one display path that has never existed. | S |
| 5.2 | **Saved animations appear as chips** in the same grid as cells, badged with their wrap type, dropping `FrameTrack.Key.ofPreset` at the playhead. (See STATE §5 — the resolver already plays these.) | S |
| 5.3 | **Importer accepts `visemeMap`** and offers "build a rig from this sheet", creating an `AvatarRig` with the map pre-filled. | M |
| 5.4 | Sheets move to an **asset library outside the project** so a named, rigged character is reusable across projects. | M |

5.1 and 5.2 are the pair that turn SpriteLab's output into something the phone can show.

---

## 6. The AI toolkit — the actual payoff

> *"so many possibilities (don't lose that idea, I genuinely want the AI toolkit to let
> users and the AI know that is possible because it is)."* — JoyRaptor, 2026-09-10

Capability nobody is told about is capability nobody uses. Three moves.

### 6a. Teach the existing tools to see names

`describe_sprite_sheet` today returns geometry, occupancy and boxes. It must return
**names, tags and viseme assignments first**, and when a sheet has none, say so *with the
remedy*: "this sheet has no cell names — `label_sprite_cells` will make every other sprite
tool work better on it." A tool that reports an absence should name the cure. (Same
principle as `faditor_kf_outside_span`, which was rewritten for exactly this reason.)

### 6b. New tools

| Tool | Does |
|---|---|
| `infer_sprite_semantics` | Given a sheet's names/tags, propose **secondary uses**: viseme assignments, emotional categories, and plausible new animations built from cells that already exist. Returns a proposal card; nothing applies without confirmation. |
| `sync_sprite_to_transcript` | Given a named sheet and the project's word-level transcript, lay down a frame track: expression cells on emphasis and sentence boundaries, viseme cells on syllables. **This is the feature the transcription engine was always going to be good for.** |
| `author_sprite_variant` | Given `thinking [7,8,9,8]`, invent `thinking_slow`, `thinking_impatient` from the same cells with different order, timing and holds. Costs no new art. |

All three are read-name, write-JSON. None needs vision. None needs the network beyond the
model call the app already makes.

### 6c. Tell the user

- When a sheet with names is imported, Joybot offers once: *"This sheet has 18 named cells.
  I can build extra animations from them, or lip-sync it to your audio."*
- The sprite panel gets a small ✨ affordance that opens the chat pre-seeded with the sheet
  in context.
- The naming editor in SpriteLab carries one line of copy explaining *why*: **"Names are how
  the AI understands your character. `wide_smile` can also become the 'E' mouth shape."**

---

## 7. The idea to not lose

Recorded verbatim because it is the strategic point and it is easy to let it decay into a
labelling feature:

> A well-named cell has more uses than the person who named it intended. `surprise` is an
> expression, a phoneme, a reaction beat, and a blink-alternative. The value of naming
> compounds with every AI tool added afterwards, and it costs the user thirty seconds with
> a sheet they already made. **The sheet stops being pixels and starts being a character
> the model can direct.**

Corollary: **a character sheet is a more valuable asset than a project.** It is reusable,
portable, hand-editable, AI-extensible and small. That is a second argument for §5.4 — a
sheet library outside any project — and, further out, for a sharable character format.

---

## 8. Acceptance

- [ ] A cell can be named and tagged in SpriteLab in under five seconds
- [ ] Six viseme assignments can be made in six taps
- [ ] Exported JSON is legible enough for a non-programmer to add an animation by hand
- [ ] `describe_sprite_sheet` leads with names and names the remedy when there are none
- [ ] An LLM given only the JSON proposes at least one animation nobody authored
- [ ] Cell names are visible on the phone's palette chips
- [ ] Importing a sheet with a `visemeMap` can produce a working lip-synced rig
