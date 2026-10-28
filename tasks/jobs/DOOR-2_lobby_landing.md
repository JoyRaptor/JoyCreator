# DOOR-2: The lobby: one clear front door

**Milestone:** M2  ·  **State:** ⛔ waiting on the owner's picks (S18): the mix-and-match page is ready  ·  **Owner:** lead  ·  **Checked:** 2026-10-28

## Why it matters
The owner, 10-27: the lobby is much better than before, but at a glance it may still be unclear whether you
came to draw or to edit video; features can be missed; parts are still structured like FadCam. It has to be
so easy that iPhone fans like it. This is the first thing every user and every demo video shows.

## Done when
- [ ] A first-time user can say within five seconds what they can make here, and start any of them in one tap:
      draw and paint, edit a video, make a music visual, animate a character, record.
- [ ] Every room, chip and floor word goes where its name says (see [DOOR-1](DOOR-1_feature_map_audit.md)).
- [ ] Recent work of every kind (projects, drawings, looks, sheets, recordings) shows and opens in the right place.
- [ ] Joybot greets with something true and useful, and can take you anywhere.
- [ ] The owner picked the direction on a mix-and-match design page, and approved it on the phone.
- [ ] STATE §7.5 updated.

## Where it stands
- **10-28: the picks page is ready:** [`UI-Front-door-picks_20261028.html`](../design/UI-Front-door-picks_20261028.html): today's lobby, three directions (1 Marquee made clear, 2 What are you making?, 3 Five doors) at true size, swap parts (New row shape and makes, room names, floor words, recents, Joybot's line, Import), a "Your mix" phone and a copy line. Found while making it: Recent work shows no sheets, characters or looks (`LobbyFragment.java:1628-1638`); Joybot says nothing to a brand-new user (`:2032-2067`); Viz Lab's New goes to the project list though VisLab needs no project (`:1206-1210`); the Character chip wears Avatar's purple but opens Sprite Lab (`:1772`).
- Already fixed 10-28 (lead, `lead/door-fixes-20261028`): Import opens the phone's picker and files the picks; every trash route opens Recently deleted.
- The lobby works and is restyled (`ui/lobby/LobbyFragment`, about 2,600 lines; last design change 10-20).
- Design records (now in git): `tasks/design/UI-The-Marquee_lobby_and-first-run-intro.html` (the current lobby and
  first run), **`UI-Five-front-doors-options.html`** (front-door options from 10-19: read it first, it may already
  hold the answer), `UI-JoyCreator-grand-design.html`.
- Known problems: see DOOR-1 (the New row has no Drawing or music visual; floor words mislead; Sprite Lab needs a project).
- Owner decisions from 10-17: the New pill is Studio green; "New" opens the Library on Projects with the new-project sheet.

## What is left
- [ ] Wait for DOOR-1's map.
- [ ] Design: a mix-and-match page (true size, a few whole directions, then per-part swaps, and a line he can copy
      back with his picks), built from the existing records, not from scratch. He picks; lock the picks as the spec's §K.
- [ ] Build after the wings in M1 are settled (the owner's order), then phone-verify, then the owner tries it.

## Files and traps
- `ui/lobby/**`, `MainActivity` routing. The lobby is a router: keep every existing destination reachable while it changes.
- Design rules in [AGENT_START.md](../AGENT_START.md); before and after shots in `tasks/screens/`.
