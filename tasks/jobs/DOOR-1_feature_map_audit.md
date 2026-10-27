# DOOR-1: Map how a user reaches every feature

**Milestone:** M2  ·  **State:** ⬜ start now (read-only, cheap or mid model)  ·  **Owner:** open  ·  **Checked:** 2026-10-27

## Why it matters
The owner's biggest worry: features hidden in pockets, paths that are fractured or still FadCam-shaped, and
a newcomer who cannot tell what the app is for. Before anyone redesigns the lobby, we need the map:
every feature, the taps it takes from the lobby, and where the path is broken, hidden or misleading.

## Done when
- [ ] `tasks/audits/feature_reach_<yyyymmdd>.md` exists, with one row per feature in STATE: the path from the lobby
      (tap by tap), taps needed, whether a newcomer would find it, and a problem note.
- [ ] A short "worst ten" list at the top: the most valuable features that are hardest to find, and the most misleading paths.
- [ ] Every route that still goes through a FadCam-era screen (the old tabs Home, Records, Remote, the forensics "Lab") is flagged for [FREE-1](FREE-1_fadcam_inventory.md).

## What we already know (verified in code 10-27)
- The lobby is a router over the old FadCam tab screens: tab 0 Home (Capture), 1 Records (Library), 2 Remote,
  3 Studio list, 4 Settings, 5 the forensics screen.
- **The "New" row offers Recording, Project, Character and Import: no Drawing and no music visual,** although
  JoyBrush and VisLab are whole wings. "Character" goes to Sprite Lab, which lives inside a project, so with no
  project it lands on the project list. "Import" opens the Library; nothing imports.
- **The floor words:** Finder → the forensics "Lab" screen (name and place disagree); Remote; Bits → Capture
  (the same place as the Capture room); Packs → Settings (the same as Setup).
- Rooms: Studio, Capture, Sprite Lab, Avatar, Viz Lab, Joy Brush (last). Sprite Lab and Avatar heroes show a project's
  name under "LAST SHEET" / "LAST CHARACTER". Trash is reached by a hidden long-press on the banner.
- Full code-level detail (10-15, partly outdated: Viz Lab now has a real room): [lobby_audit.md](../audits/lobby_audit.md).

## How to do it
1. Take the feature list from [STATE.md](../STATE.md) (every row with a user-facing feature) plus the JoyBrush and VisLab roadmaps.
2. For each, trace the path in code from `LobbyFragment` / `MainActivity` (and on the phone where unsure, read-only).
3. Note anything reachable only from inside another wing, only by long-press, or only through a FadCam-era screen.
4. Read-only: no code changes. Report to the lead, who turns findings into DOOR-2/DOOR-4/FREE-1 work.
