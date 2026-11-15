# BOT-7: Joybot in the lobby is your guide; colours say where things will happen

**Milestone:** M1/M2 (launch polish)  ·  **State:** ⬜ card written 11-15  ·  **Owner:** Joybot brain lane  ·  **Checked:** 2026-11-15

## Why it matters
The owner, 11-15, after trying Joybot in the lobby. He tapped "make a transition", and Joybot said "I made a
transition" in a Studio project the owner had never opened, so he didn't know where. Asked for "a new blank Studio
project, 3:1", Joybot said "doing it", then "I can't make a new blank project folder with a 3.1 aspect ratio here", and
nothing changed. In the lobby, Joybot should be **your wizard, butler, custodian, co-pilot and tour guide**. Only once
you are working in a project does he start asking about that project. Most of this works **offline**: it is wizards, and
a connected AI only makes it more flexible.

## Done when
- [ ] **Never "doing it" then "I can't".** Joybot checks he can do a thing before he says he will. When he can't, he
      says so once, plainly, with the nervous animation (BOT-5), and offers the nearest thing he can do.
- [ ] **Lobby actions don't act in a project you can't see.** Asked for a project action in the lobby ("make a
      transition"), he asks first: "Make transitions in the last project you had open? Want to look at them with me?" The
      answer opens it, or he does nothing.
- [ ] **First buttons when Joybot opens in the lobby:** Create something · Help · Show me around · What can I do. No
      typing needed to start.
- [ ] **The create tree.** "Create something" (or "I want to make something") shows icon bubbles, one per kind of thing,
      **ordered by what this person makes most** (usage counts kept on the phone). Each kind asks only what it needs, as
      buttons with common choices plus "type your own", then lands in the right room already set up:

  | Bubble (icon from ICON-1) | Asks | Lands in |
  |---|---|---|
  | Drawing / painting (easel or brush and palette) | aspect ratio (common ones offered) | JoyBrush, an image board placed and locked to that ratio |
  | Drawing in 1, 2 or 3-point perspective | which, aspect ratio | JoyBrush with that perspective guide on |
  | Hand animation (a film frame with sprockets and a running man) | aspect ratio, frames per second | JoyBrush with an animation board at that rate |
  | Tile | tile size or ratio | JoyBrush with a tile board, tiling on |
  | Sprite (make one) | grid (cells, size) | JoyBrush with a sprite board and that grid |
  | Sprite (load and edit one) | which file | SpriteLab with it open |
  | Video (film camera) | aspect ratio | Studio, a new project at that ratio (3:1 included) |
  | Audio project (music notes, clearly not VisLab) | none | Studio, a new audio project |
  | Music visuals (VisLab's own icon, e.g. a note whose head is an eye) | none | VisLab |
  | Titles (the "A with a sparkle" TextLab icon) | none | TextLab |
  | Character / avatar (Vitruvian-style figure) | none | Avatar Studio or the character animator |
  | Find and tidy (a folder with a magnifying glass punched out) | what to find or do | Library: finds projects, exports and recent work; tidies, consolidates, exports |

- [ ] **Show me around** is a real tour of the rooms; **What can I do** lists what he can do here, as buttons.
- [ ] **Room colours carry intention.** The colour of a message or button shows *where* it will happen:
  - In the lobby, Joybot's default is his own colours. Opened inside a room, the default is that room's colours (Studio's
    greens in Studio).
  - When he talks about something in another room, or plans to do it there, that message takes that room's colours,
    even before you go there. "Take me there" wears the destination room's colour. Create-tree bubbles each wear their
    room's colours as a gradient.
  - When a message offers two rooms ("send it to Studio or TextLab?"), it keeps the current room's colour; an offer
    naming one room ("send this to Studio?") wears that room's colour.
  - The send arrow: Joybot's colours in the lobby, the room's colours inside a room (owner open to this; show both on
    the design page).
- [ ] Every part checked on the Note 9 offline, then with a connected AI if the PC has a key.

## Rules that apply
- Phone-first; one-handed; buttons before typing. Hover labels on every button. Joybot always animates (BOT-5).
- Icons come from ICON-1 (the same icon in JoyBrush's Tools and in Joybot's create tree).
- JoyBrush board presets go through a launch intent the JoyBrush screens lane provides; don't edit JoyBrush internals.
- The chat's layout, bubble drawable and actor belong to the joybot-sprites lane: agree any change to them with it.

## Where it stands
Card written 11-15 by the orchestrator from the owner's words. Nothing built.
