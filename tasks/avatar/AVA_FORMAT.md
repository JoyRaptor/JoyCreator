# Part B: ONE character file and ONE scene file (pilot and phone)

Part of the [Avatar Studio phone spec](AVA_PHONE_SPEC.md). Written 2026-11-23.
Converter and checker: `tools/avatarlab/al-format.js` (pilot and node). Tests: [format-tests/](format-tests/), run with
`node tasks/avatar/format-tests/run.mjs` (74 checks, 0 failures on 11-23: 3 test files both ways round, 37 planted faults
each caught by its own rule). Any saved file can be checked with `node tasks/avatar/format-tests/run.mjs <file>`.

## B1. Rules for the format

1. **Extend, do not fork.** There is one family, `joycreator.character/N` and `joycreator.scene/N`. A reader accepts every
   older N and upgrades it in memory; a writer writes the newest. The pilot's `/1` is not replaced by a new format; `/2` is
   `/1` with one meaning changed (the landmarks), and nothing else.
2. **A new field does not bump the number.** Readers keep fields they do not know and write them back unchanged (so the
   pilot and the phone can each be a step ahead without losing the other's work). The number goes up only when the
   meaning of an existing field changes.
3. **Readable by hand.** Plain JSON, ids are short strings, coordinates are character pixels (y down), angles in degrees,
   levels 0 to 1. No binary except pictures and sound, which are either inside the file as `data:` text or beside it.
4. **Same file, two packings.** *Travelling* (one file, what the pilot saves and what Send shares): pictures are
   `data:image/png|webp;base64,...`, the voice `data:audio/wav;base64,...`. *In the Joy Creator folder* (the phone, when a
   character's pictures pass 8 MB): `Name.character.json` beside `Name.pictures/<id>.png`, and the values are those relative
   paths. A reader takes either. The checker allows exactly these two forms (rule `pic-form`).
5. **File names.** `<Name>.character.json`, `<Name>.scene.json` (readable names, the Obsidian rule). `FileKind` gains
   `CHARACTER(".character.json")` and `SCENE(".scene.json")` in the WORK group. The old room's `.avatar.json` stays its own
   kind and is not opened by the new room (it says so in one sentence and offers the older room).
6. **The file never holds a person's identity**: no account, no device, no real name; the creator field, if one is ever
   added, is what the user typed.

## B2. The character, `joycreator.character/2`

Top level (the pilot's `/1` fields, unchanged, plus what `/2` means):

| Field | What it is |
|---|---|
| `format` | `"joycreator.character/2"` |
| `name`, `w`, `h` | The name, and the size of the drawing space the character was rigged in |
| `root` | The tree. Node `type`: `group` (draw every child), `set` (draw ONE child, picked by `pick.by`: `turn`, `reach`, `viseme`, `default`, `cycle`, `dir`), `art` (one drawing at `x`, `y`, picture `img`), `mirror` (an angle that is another, `of`, mirrored). Behaviour records hang off any node: `limb`, `hinge`, `squash`, `look`, `blink`, `lid`, `clips`, `swing`, `breathe`, `mesh`, `shapes`, `links`, `ride`, `emit`, `ball`, `wrap`, `depth`, `zo`, `trig`, `dx/dy/rot/sc/flip` (see the header of `al-model.js`, the list of record kinds) |
| `triggers` | The buttons: `[{ id, name }]` |
| `settings` | facing, blink, breathing, idle, `axisX`, `walk` (ground, stride, step, bob ...), `stick` (joystick gestures, depth tick), `floorAngle` |
| `poses` | Stances: `[{ id, name, trig, time, d: { <node id>: { x, y, rot, sc, orbit, z: { how, of, at, tip } } } }]` |
| `palettes` | Colour sets: `[{ id, name, trig, time, soft, blotch, stops: [{ from, to, sat, val, width }] }]` |
| `facePins` | Face pins (FACE-1): `[{ id, point, node, pin, amt, made }]`: a pin on `node` (its mesh pin `pin`) follows the face camera's point `point` (a MediaPipe face mesh index, 0..477) by `amt` (its two Shapes, `fp:<id>:x` and `:y`, follow `pt.<point>.x` and `.y`); `made`: placed by hand. The face camera watches every pinned `point` |
| `take` | The take kept with the character (B4) |
| `pictures` | `{ <img id>: data URL or relative path }` |
| `fine` | `{ <img id>: [w, h] }`: a picture painted finer than the character; `[w, h]` is the size it takes up (round 19) |
| `uid`, `demo`, `pilot`, `edited` | Shelf identity and the pilot's own bookkeeping; the phone keeps them, ignores `pilot` |

### B2.1 What `/2` changes: one set of landmarks per piece

**The owner's design (11-23).** The pilot matched angles pair by pair, each pair with its own landmarks, so three angles
round one spot of the pad (Left Quarter, Upward, Frontal) pulled one drawing toward different places. On the phone, and
in `/2`, a piece has ONE numbered set of landmarks and every angle places the SAME points.

```json
"marks": {
  "": {                                   // the whole drawing ('' ) or a piece, by its lower-case name ("frill")
    "pts": [ { "n": 1, "name": "tip 1" }, { "n": 2 }, ... ],          // numbered 1..n, a name is optional
    "at": {
      "<angle id>": [ [x, y], [x, y, "behind"], [x, y, "off"], ... ]  // index i is point pts[i]; every row has every point
    },
    "meets": [ { "a": "<angle id>", "b": "<angle id>", "at": 0.4 } ]   // optional: the old "They meet" slider, per pair
  }
}
```

| A place | Means | In the bend |
|---|---|---|
| `[x, y]` | The point is here in this angle's drawing | counts |
| `[x, y, "behind"]` | Folded round behind (the crest's far tips in profile): a ghost with a place, dragged where it would be | counts in full: still steers the bend toward this angle |
| `[x, y, "off"]` | Not here: greyed, a circle with a line through it, may sit anywhere | takes no part in this angle |
| (angle missing from `at`) | This piece has no landmarks in that angle | no warp, as an unmatched angle today |

- A **mirrored** angle has no row of its own: it reads its source's row mirrored about the set's `axisX` (else the
  character's middle). If the user places points on it by hand, it gets its own row and that wins.
- **How it bends (the shared rule, `AL.format.blend`).** For a pad position the turn gives each angle a weight. Each
  point's target is the weighted average of ITS OWN places over the angles that have it (`off` leaves the sum, the
  weights are renormalised). Each drawing is then warped from its own places to those targets (rigid MLS, the phone's
  `PuppetDeformer`; the pilot's `M.mls`). Because every drawing aims at the same target, two or three neighbours can never
  pull one point to different places, which is what popped in the pilot on 11-23.
- `meets` keeps the pilot's "They meet" per pair of angles and per piece: it shifts the weight curve between those two
  angles (0.5 = half way, the default; absent = 0.5).
- **Blend two looks** uses the same `marks` on a set of looks (a smile and a frown): the row of each look.
- The pilot's `set.warpWith` (a piece that warps by its own landmarks, or with another piece) is unchanged.
- **The face mask (FACE-2, 11-27)** is the whole drawing's landmarks of a head set (`marks['']`): a mask point is an
  ordinary numbered landmark whose `pts` entry also carries `key` (`"eyeL"`), `face` (the face mesh index it follows)
  and `handle: true` for the big ones; both checkers keep extra fields. The set gains
  `face: { turn: { <angle id>: [yaw, pitch] }, mine: { <angle id>: [point index, ...] }, hid, ok, fit, gain, direct, poses, posed, swapped }`:
  each drawing's real turn, the places put by hand (the rest are guessed and rewritten), and the poses. `fit`, `posed`
  and `swapped` are written at edit time; a reader only reads them. A head with no `face` is untouched. The solver's
  pass and the full shape: [FACE-2, "For the phone room"](../jobs/FACE-2_face_mask_and_poses.md).

### B2.2 How the pilot's pairs convert (`AL.format.upgrade`)

The pilot's `set.bridges = [{ a, b, part, pts: [{ a: [x, y], b: [x, y] }], meet }]` becomes `set.marks`:

1. Every dot is (angle, place). A pair joins its two dots. Two dots in the **same drawing** closer than 4 px are the same
   landmark (the same spot matched to two neighbours, the owner's three-angle case). Union-find over all dots of a piece.
2. Each joined group is one numbered landmark, numbered in the order it was first marked.
3. A group with two places **more than 6 px apart in one drawing** (a chain of pairs that disagrees) is split: the first
   place keeps the number; each disagreeing place becomes a landmark of its own with the place it was paired with. The
   report says so.
4. Places within 6 px in one drawing are averaged.
5. Every angle that has any landmark of the piece gets a full row: its own places, and "not here" for the points it never
   had, parked at the average of that point's places (so it can be found and dragged).
6. `meet` values other than 0.5 go into that piece's `meets`.
7. `bridges` is removed; `format` becomes `/2`; every other field is copied unchanged.

**The way back** (`AL.format.downgrade`, for a copy of the pilot older than step 0): every two angles that both place a point (neither
`off`) become a pair, with their `meet`. Every original pair comes back within 4 px (tested). It also yields pairs the
user never marked (an Upward-Frontal chin derived through Left Quarter): those are the point of the new model. **Since step 0 (11-27) the pilot reads and writes
`marks` itself**; the downgrade is only for an older copy of the pilot.

**Worked example** (`format-tests/v1-three-angles.character.json`): 7 pairs of the whole head over Left Quarter, Frontal
and Upward become 4 landmarks: the nose (matched from Left Quarter to both neighbours, dots 1.4 px apart: one landmark), the
chin (averaged in Upward, where it was marked twice 1 px apart), a second chin (Left Quarter had it 40 px away in another
pair: split off, "not here" in Frontal), and a point only Left Quarter and Upward have ("not here" in Frontal, parked at
(65, 70)). The frill's 3 pairs become 2 landmarks. "They meet" 0.4 on the whole head stays; the frill's 0.5 does not
need to be written.

**On the owner's own matched head** (the pilot's `local/odd-mesh.character.json`, read on the PC, not committed):
199 pairs became numbered landmarks in 10 pieces (front frill 52 pairs to 36 points, nose 32 to 20 ...), no splits,
no rule broken after; all 199 pairs come back from the downgrade, the worst 2.5 px from where they were (averaged
near-duplicates). His other saves (Topps repaired, Topps at 1223 px, Joybot's draft) have no landmarks and pass the rules.

## B3. The scene, `joycreator.scene/1` (unchanged number)

| Field | What it is |
|---|---|
| `format`, `uid`, `name` | `"joycreator.scene/1"`, its id, its name |
| `shape`, `safe` | The frame: `16:9`, `4:3`, `1:1`, `4:5`, `9:16`; margins `off`, `title`, `social`. The stage is always 1920 x 1080; the frame is a window on it |
| `floor` | `{ near, far, shrink }`: the floor's front and back (fractions of the height) and the size at the back; `shrink` is also the shot's camera angle |
| `wind`, `haze` | 0 to 1 |
| `items` | `[{ id, kind: back / obj / front / cast, name, x, y, s, flip, sway, img or frames + fps or made (a set that comes with the room), uid / stock (a cast member's character) }]` |
| `shots` | Kept shots: `[{ id, name, x, y, z }]` |
| `cam` | The camera lane: `[{ t, shot: wide / two / free / close:<item id> / <shot id>, ease }]`, in time order |
| `takes` | `{ <cast item id>: take }` (B4) |
| `characters` | When the scene travels: `{ <cast item id>: a whole character file }`, upgraded to the newest with the scene |
| `pictures` | As for a character |

The empty scene is no scene file at all (the plain stage); its frame and shots are kept per character in the room's
settings, not in either file.

## B4. A take

```json
"take": { "fps": 30, "n": 90,
          "ch": { "turnX": [0, 0.02, ...], "vis": ["Neutral", "Aa", ...], "trig:t1": [0, 1], "hLw": [0, 0, 1], "p:<pin>:x": [...] },
          "voice": { "rate": 24000, "n": 72000, "wav": "data:audio/wav;base64,..." } }
```

- `fps` is 30, `n` frames. Each lane is one value per frame; a lane may be shorter than `n` (its last value holds).
  Values are numbers, a mouth shape's name (`vis`), 0/1 steps (`flip`, `hLw`, `hRw`), or `null` (not driven).
  The lane names and who owns each are `OWN` in `al-inputs.js` (head, mouth, mood, blink, each hand and foot, body, wind,
  shoulders, each button `trig:<id>`, each pin `p:<pin>:<axis>`, face pins `face.*`).
- **The face camera's own lanes** (agreed 11-27 by the pilot and the phone room, `FaceDrive.ownerOf` = the pilot's
  `ownerOf`), all owned by `facepins` except the shoulders: `pt.<mesh index>.x` / `.y` (each watched face point, from
  its place in the neutral face), `face.<name>` (the expressions any part can follow: `cheekPuff`, `smile`, `jaw`,
  `mouthWide`, `pucker`, `wide`, `squint`, `browInner`, `frown`, `sneer`; 0..1; `face.cheekPuff` is owned by `puff`, the
  Puff button, since the camera never sees a puff), `mk.<point n>.x` / `.y` (the face
  mask's points, FACE-2) and `mp.<pose id>` (how strong each mask pose is, 0..1); `shrugL`, `shrugR`, `shrug` are
  the `shoulders`'. A take that has them plays them back even where the face camera is off; a reader that does not
  use a lane keeps it.
- `voice`: a mono 16-bit WAV exactly as long as the take (within one frame: rule `voice-len`), starting with it. Any rate
  reads back (the pilot writes 24 000; the phone's recorder writes 44 100).
- In Studio a character item carries the take as it is (call 5 of the spec), so a later pass made in Studio is the same
  punch-in, punch-out as in the room.

## B5. What the checker enforces (`AL.format.check`, the phone's unit test uses the same files)

Unknown format · no size · tangled tree (depth over 40) · unknown node kind · damaged kids · duplicate ids · references
that go nowhere (a mirror's source, a resting drawing, a mask, a rider, a stance's parts) · a drawing with no place or
no picture or a picture not in the file · a button a drawing, stance or colour set uses that is not in `triggers` · a
turning angle with no dot on the pad · `/1` pairs left in a `/2` file · landmarks: numbering, a row that does not place
every point, a bad place or state, an angle that is not one of the set's drawings, a row where every point is "not
here", a damaged "they meet" · a damaged take, a lane longer than the take, a damaged voice, a voice not as long as the
take · a damaged colour set · a picture neither inside nor beside the file · scenes: unknown shape, duplicate item ids,
unknown kind, a thing with no place or picture, a cast member that names no character, a camera cut to a missing shot or
out of time order, a take for something that is not a character, a carried character that is not in the scene (and
every character rule on the carried ones).

**Not covered yet** (named so nobody thinks they are): the behaviour records' own numbers (a limb's pins, a swing's
settings) are passed through unchecked; a stance's `z` rule is not cross-checked against the stack; picture data is not
decoded.
