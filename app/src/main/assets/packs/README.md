# Built-in Joy packs

One manifest per pack, the same `joypack.json` format as a `.joypack` file (job card `tasks/jobs/PACK-1_joypacks.md`).
File paths are relative to the app's `assets/` folder.

- **Releasing in waves:** set `"held": true` on a pack to keep it out of a release; it disappears from every room.
- **New looks:** a TextLab look in `textlab/looks/index.json` (`looks`) that no pack lists still appears, under
  "TextLab" (so the lead's new looks are never lost); add it to a pack to file it there. Drafts never appear.
