# Golden files: what the pilot's solver draws, for the phone's solver to match

Made 2026-11-27 by step 0 (AVA-1) with `AL.golden` (`tools/avatarlab/al-golden.js`), from the built-in Topps
(`tools/avatarlab/characters/topps.character.json`, the owner's art that is in the repository at his word).
The phone's `CharacterSolver` (Codex list X2 in [../CHANNEL.md](../CHANNEL.md)) must land every vertex within 0.5 px.

| File | Character | Cases |
|---|---|---|
| `topps.golden.json` | Topps (built-in) | rest; turn left, right, up; both hands reaching; talking; eyes shut; the All fours stance at full; moods Angry and Cheery |

How each case is made: a fresh solver (nothing carried over), the case's signals held for 8 frames at 30 a second, then
every drawn piece written as `{ id, img, a, ghost, under, v }`: `v` is the vertices in character pixels, rounded to 0.01.
The triangles and texture places are not written (they come from the art). Made twice in a row: identical.
Each case was checked to differ from rest (the stance moves parts up to 230 px; talking moves the jaw 6.3 px).

To make it again: open the pilot through its own server (`node tools/avatarlab/serve.mjs`), then in the console
`AL.golden.put(AL.golden.make(await AL.fromJson(<the character file>)), 'golden-topps.json')` writes it into
`tools/avatarlab/local/`; copy it here.

**Not in it:** walking (the walker is not part of the solver; it feeds the solver foot and body signals and has its own
test), the owner's saved characters (his files stay out of the repository), and swinging after many frames (8 frames only).
