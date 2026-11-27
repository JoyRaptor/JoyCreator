# Golden files: what the pilot's solver draws, for the phone's solver to match

Made by the web pilot's `AL.golden` (`tools/avatarlab/al-golden.js`) and checked by the phone's solver
(`cc.joycreator.avatar.solve.CharacterSolver`, X2) in `bash tools/jvm-harness/run-avatar-solve.sh`: the same pieces in the
same order, the same opacity, every vertex within 0.5 px. On 11-27 every case matched to 0.005 px (the files' own rounding).

| Files | Character | Cases | What it exercises |
|---|---|---|---|
| `topps.golden.json` | Topps, built-in (`tools/avatarlab/characters/topps.character.json`; the owner's art, in the repository at his word) | rest; turn left, right, up; both hands reaching; talking; eyes shut; All fours at full; moods Angry and Cheery; **mid-walk** (signals captured from the pilot's own walker: body 139 px along, both feet planted) | head angles, limbs (reach, elbows, foot bars), mouth hinge, lids, tail swing, breathing, a stance, walking feet |
| `pip.character.json`, `pip.golden.json` | Pip, the pilot's practice character (drawn in code by `al-demo.js`) | the same, plus wind with a turn, talking and breathing, and its button | masks, many lids, mouth shapes, bendy and stiff swings (the chain and charm) |
| `squares.character.json`, `squares.golden.json` | three plain squares (made up) | turns toward B, past it, toward the mirrored B, toward C, between B and C | the owner's landmark model: here, behind, not here, a mirrored angle, "they meet" |
| `gadget.character.json`, `gadget.golden.json` | made-up shapes | rest; head turned both ways; the hose arm reaching two ways; a button; wind | a plain round head and a turned outline (painted on, a part in one piece, one that sticks out), a hose arm, a hand that rides it and swaps by direction, a shape on pins, a loop, a stream, a stiff charm in the wind |

How each case is made: a fresh solver (nothing carried over), the case's signals held for 8 frames at 30 a second (the frame
clock `t` is set each frame), then every drawn piece written as `{ id, img, a, ghost, under, v }`: `v` is the vertices in
character pixels, rounded to 0.01. Triangles and texture places are not written (they come from the art). Each file was made
twice in a row: identical.

To make them again, open the pilot through its own server (`node tools/avatarlab/serve.mjs`, through the in-app browser's
preview so saving into `local/` is allowed), then in the console:
`AL.golden.put(AL.golden.make(<character>, <cases>), 'golden-<name>.json')` writes into `tools/avatarlab/local/`; copy it here.
The mid-walk signals were taken by holding the body with `app.inp.walker.grab([0, 0])`, dragging it 6 px a frame for 24 frames
in Perform (simulated time from `al-breaktest-fit.js`), and keeping the signals of the last frame.

**Checked locally only, not in the repository:** the owner's Joybot draft (his art stays out): 26 cases, 0.005 px, with
`bash tools/jvm-harness/run-avatar-solve.sh tools/avatarlab/local/golden-joybot.character.json tools/avatarlab/local/golden-joybot.json`.

**Not in them:** walking itself (the walker is not part of the solver; it feeds it foot and body signals), the owner's saved
characters, swinging over many frames (8 only), onion skin and forced poses (building aids), a picture painted finer than its
character.
