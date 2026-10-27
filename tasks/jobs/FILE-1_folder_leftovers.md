# FILE-1: Joy Creator folder and storage leftovers

**Milestone:** M1  ·  **State:** 🟡  ·  **Owner:** open (the folder lead's session, "App roadmap priorities", is retired)  ·  **Checked:** 2026-10-27 against the spec's §6

## Why it matters
"Uninstalling never loses your work, and everything lives in one folder you own" is a core promise. It is
built and mostly phone-proven; these are the last corners.

## Done when
- [ ] Everything in the spec's §6 is done or parked by the owner, and STATE §7 says so.

## Where it stands
Built 10-13 to 10-15 and mostly agent-proven on the Note 9: required first step, projects mirrored and
restored, Gather, one Trash with Undo, recordings and exports in the folder with readable names, the
Library reads it, share-in, big outside files linked with a warning and a yellow dot, Copy / Move / Leave,
the Space screen. Live spec: [`SPEC_20261013_FILE_MANAGEMENT.md`](../SPEC_20261013_FILE_MANAGEMENT.md) §4 (proof per piece) and §6 (open list).

## What is left
- [ ] Undo after a Move: needs an outside file over 25 MB (smaller files are copied, not linked).
- [ ] Space → "Move unused to Trash…" (multi-select): compiles, never on the phone.
- [ ] Share-in of a large file still copies whatever the size; make it follow Copy / Move / Leave (link + warning above 25 MB).
- [ ] In real use only (testing would litter his real folder): recording when the folder is missing, and the
      "make a Recordings folder?" offer after 20 loose recordings.
- [ ] The JoyBrush half: [`joybrush/briefs/JOY_FOLDER_FOR_JOYBRUSH.md`](../joybrush/briefs/JOY_FOLDER_FOR_JOYBRUSH.md) §0
      (textured paper in thumbnails, thumbnail = last view, "Open…" adds a new drawing). Coordinate with the JoyBrush lead.
- [ ] About a dozen storage roots still sit outside the shared space (avatars, fonts, voiceovers, drawing working copies).
- [ ] Later, owner's list: presets and settings into the folder, version history.
- [ ] Test leftovers on the Note 9, safe to delete: `/sdcard/Joy Creator (first test)`, the project
      "Restore video test" (a copy), `Camera 2026-10-13 23.51.mp4`, `z1.png`.

## Files and traps
- Read the spec's §5 before building: it lists the existing parts this extends. Do not duplicate them.
- Any "is this file used?" logic must read the folder's `.joyproject` copies as well as the app projects.
