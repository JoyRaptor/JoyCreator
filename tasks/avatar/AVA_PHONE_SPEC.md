# Avatar Studio on the phone: the spec (AVA-1, step 3 of "the path to the phone")

**Written 2026-11-23** against `joy-creator` at `cece72679` (pilot files as of `f29e58bd4`, 11-23). **State: a written spec. No phone
code exists for it yet.** The slices that build it come after this, and only with the owner's go-ahead.
Job card: [AVA-1](../jobs/AVA-1_avatar_studio_redesign.md). The pilot is `tools/avatarlab/` (the owner's test page is v30).

| File | What is in it |
|---|---|
| **This file, part A** | Every screen of the phone room: phone portrait first, then phone landscape and tablet; each control with its hover label |
| [AVA_FORMAT.md](AVA_FORMAT.md) (part B) | ONE character file and ONE scene file, shared by the pilot and the phone; the owner's landmark model; how the pilot's pairs convert |
| [format-tests/](format-tests/) | Round-trip test files (made-up art only) and `run.mjs`; the converter and checker are `tools/avatarlab/al-format.js` |
| [AVA_PORT_MAP.md](AVA_PORT_MAP.md) (part C) | Each pilot file and the native class it becomes, marking what already exists in the app |
| [AVA_STUDIO_REUSE.md](AVA_STUDIO_REUSE.md) (part D) | What Video Studio already has, each marked plug in / fix there first / new |
| [AVA_SLICES.md](AVA_SLICES.md) (part E) | The slices in order: what each needs, how it is proven on the Note 9, its Joybot tools. Steps, no time estimates |
| [AVA_HOVER_LABELS.md](AVA_HOVER_LABELS.md) | Every control's words and hover label, harvested from the pilot by `harvest-hover.mjs`, to carry over word for word |

## Engineering calls made in this spec (the owner's rules decide taste; these are mine to make and say)

1. **The room is built with Android Views, not Compose.** The app has no Compose at all (no `@Composable` anywhere; Studio,
   TextLab and the old Avatar room are Views). The pilot's `Phone:` lines that say "LazyRow", "ModalBottomSheet" or
   "Compose Canvas" mean the same thing in Views: a `RecyclerView` row, a `SheetKit` / `StudioDialog` sheet, a custom `View`
   drawn on a `Canvas`. Part C says this file by file.
2. **A new room, beside the old one.** The new room is `cc.joycreator.avatar.AvatarRoomActivity` (the new-wing package, as
   TextLab is `cc.joycreator.textlab`). The old `AvatarStudioActivity` (pose matrix, `.avatar.json`) stays, reachable from
   Settings as "the older Avatar room", until the owner says the new one has replaced it (rule: keep the old behaviour
   selectable). The lobby's Avatar door opens the new room.
3. **One solver, written once in plain Java, proven against the pilot by numbers.** The pilot's `al-solve.js` is the
   reference for behaviour. The phone's `CharacterSolver` ports it and calls the math the app already has (rigid MLS in
   `PuppetDeformer`, `FabrikSolver`, `DangleSim`, `PuppetWeights`, `PinWarpStrip`), and it is the single authority, as
   `PuppetPoseResolver` is today: the room's stage, recording, Studio's preview and Studio's export all call it. Parity is
   proven by golden files: the pilot writes the meshes it solves for a fixed list of signals; the JVM harness reads them
   and the Java solver must land every vertex within 0.5 px.
4. **The file is the same file.** `<Name>.character.json` and `<Name>.scene.json`, readable JSON, the format in part B,
   written into the Joy Creator folder by the same pattern as `AvatarBundleFile` (one file, pictures inside it as text,
   comes back after a reinstall). `FileKind` gains `CHARACTER` and `SCENE`.
5. **A take stays a take.** A character dropped into Studio carries its take as the file has it (lanes at 30 frames a
   second, the voice beside it), not converted into the older `AvatarParamTrack` (which holds only numbers, while a take
   also holds mouth shapes by name and on/off steps). `AvatarParamTrack` is left as it is for the old room.

## A0. Rules every screen follows (the owner's, recorded on the card, the audit and in memory)

| Rule | What it means on these screens |
|---|---|
| ON = solid cyan, icon cut out | A chosen chip, tab or round button is filled `Studio.ARMED` with its icon punched out to the ground colour. Never an outline. |
| Tick box for on/off; a sliding switch only for two equal choices | "Frame on", "See-through", "Room round the frame", each walking tick: tick boxes (`SheetKit` checkbox, cyan knock-out tick). Ground line / Anywhere, Steps / Hops, Cut / Glide, Smooth / Instant / A card: switches (two or three equals). |
| Cyan and magenta from the global palette | `Studio.ARMED` (cyan) and `Studio.LIVE` (magenta) only, read from `studiokit/.../Studio.java`. No hex in this room. The landmark colours (first drawing cyan, second magenta), the stage's "in hand" ring, the joystick knob, recording red-dot are all these two. The room's own gradient (export mark, Joybot orb) is `Studio.ROOM_AVATAR` to `ROOM_AVATAR_DEEP`. |
| Every button gets a hover label | Every control in this file has one (column "Hover label"); card controls carry the pilot's words ([AVA_HOVER_LABELS.md](AVA_HOVER_LABELS.md)). Set with `TooltipCompat.setTooltipText`, and `HoverLabels.watch(root)` catches any that slip. A long press on an icon-only stage button on a phone shows the same words (audit 27). |
| One press = one undo step | A drag is one step, a batch is one step, a slider drag is one step. Entering a mode is never a step (audit 20). Undo's hover label names the step ("Undo: Move the left arm"); holding Undo opens the history (as Studio). |
| dp as drawn | Sizes below are the Note 9's dp. Never inflate them for a wider screen; a tablet gets more columns, not bigger controls. |
| Drawers never opaque | Sheets and the strip are about 55% see-through; Frost adds blur on Android 12 and later, never a solid fill. |
| An automatic guess keeps a way back | Every guessed thing (pins from the art, "All fours" worked out, eyelids added, a landmark guessed "behind") says so on its card and has one-tap "Guess again" / "Remove" / "Put it back". |
| No technical errors, ever | A file that cannot be read says one plain sentence ("That file is not a character this room can open. Nothing was changed."); the detail goes to the log only (audit 32). |
| Phone-first, full parity | Everything the pilot does is reachable with one thumb on the Note 9; nothing is desktop-only. A mouse's right button and a pen's side button do what a long press does. |
| Preview equals export | The stage, Studio's preview and every export draw through the same solver and shader. |

## A1. The ways in

| Where | What the user sees | One press does | Hover label |
|---|---|---|---|
| Lobby, Avatar door | The Avatar wing's door | Opens the room on the character used last (first time: Topps, in Perform, ready to play) | "Avatar Studio: make a character move and talk" |
| Studio, Add, Character | The shelf of characters as a sheet | Drops the chosen character (with its take) on the timeline at the playhead | "Add one of your characters, with its last take" |
| JoyBrush board (JB-7) | "Send to Avatar Studio" | Opens the room with the board's layers as a new character (the layered-file door) | "Rig these layers as a character in Avatar Studio" |
| Joybot | "Take me there" on any Avatar help answer | Opens the room at that card | (Joybot's own) |

First run: Topps opens ready to play. Joybot flies from his corner (lower left, always animating) to the Menu and circles
"Create new avatar"; the motion is the Joybot sprite-sheet lane's (its principles doc), this room supplies only the target
view id and the moment (after the first 8 seconds of play, or the first time the user opens the Menu).

## A2. The room, phone portrait (Note 9: 411 x 846 dp less the system bars)

```
┌──────────────────────────────────────────┐ 44 dp  top bar
│ ☰  ↶  ↷                       ⬆export  ◉ │        Menu, Undo, Redo ........ Export mark, Joybot orb
├──────────────────────────────────────────┤
│                                    ┌──┐  │        the STAGE (old-black; the character fitted into the
│            (the character)         │◎ │  │        rectangle left between the hint line and the stage
│                                    │◐ │  │        buttons: audit 3)  ·  stage tools: a column of
│                                    └──┘  │        36 dp round buttons on the right edge
│  hint line (one line, tap to fold)       │
│  [Standing][All fours][+]   (Arrange)    │        pose row, on the picture, Arrange only
├──────────────────────────────────────────┤ 44 dp  panel header
│ [map] Scene › Topps › Head    Parts|Layers│
├──────────────────────────────────────────┤ 36 dp  the four stages
│ Set up | Arrange | Motion | Perform       │
├──────────────────────────────────────────┤
│  the card for what is in hand             │        scrolls; sections fold; seldom-used behind "More"
│  ...                                      │
└──────────────────────────────────────────┘
```

- The stage takes what the panel leaves, never less than 44% of the height. The panel's top edge has a grab bar
  (as Studio's) to give the stage more or less; the split is kept per orientation.
- **What is in hand** is said in three places, all always on screen: the small body map (the button at the left of the
  header; a tap opens the big labelled map as a sheet, audit 8), a tap on the picture, and the path tabs
  (`Scene › Topps › Head`, slanted tabs with pictures, lobby corner shape; a tap on the current tab shows the part bare).
- The character's name is not in the top bar at this width (audit 23); it heads the Menu.

### Top bar

| Control | Look | One press | Long press | Hover label |
|---|---|---|---|---|
| Menu | ☰, 36 dp | Opens the drawer (A3) | - | "Menu: your characters and scenes, open, save, send" |
| Undo | arrow, 36 dp | Takes back one step | History list (Studio's popup) | "Undo: <the step's name>" (named live; "Nothing to undo" when empty) |
| Redo | arrow, 36 dp | Puts it back | History list | "Redo: <the step's name>" |
| Export | Studio's own export mark (`ic_export_studio`) in the Avatar gradient, 40 dp | Opens the export sheet (A11) | - | "Export: keep it as a Joy Creator file, a picture or a video" |
| Joybot | his orb with his face (`joybot_neutral`), upper right | Opens Joybot here, knowing what is in hand | - | "Joybot: ask for help, or ask him to do it" |
| Save dot | small dot by Menu | - | - | "Saved" / "Not saved yet: it saves by itself in a moment" (never says saved for an untouched character: round 8) |

### Stage tools (right edge, 36 dp round, grey; on = solid cyan cut-out)

| Control | Stage | One press | Hover label |
|---|---|---|---|
| Fit to screen | all | Frames what is in hand | "Frame the part in hand" |
| Selection look | Set up, Arrange | Cycles the outline look (Spotlight is his pick, round 14; the others stay one tap away) | "How the part in hand is shown: Spotlight, dashes, glow, corners, wash" |
| Onion skin | Set up, Arrange, landmarks | Shows the drawing next to this one faintly; while matching, the tint (A9) | "Show the neighbouring drawing faintly" |
| Pick a piece | Set up, Arrange | Next tap on the picture takes that layer alone | "Tap a piece on the picture to take it in hand" |
| Hold this pose | Motion | Freezes the living pose to mend it in Set up; the chip "Held pose ×" lets go | "Freeze this pose so you can mend it" |
| Magnifier | while dragging a pin | A round loupe that follows the finger (round 3 ruling) | (none: appears only while dragging) |

### Panel header (44 dp)

| Control | One press | Long press | Hover label |
|---|---|---|---|
| Body map button (36 dp, the small map) | Opens the big labelled body map sheet (two-legged / four-legged, Extras as "+ more", Ground bar under the feet) | - | "Which part: tap one on the map" |
| Path tabs | A tab puts that level in hand (Scene, the character, the part); the current tab shows the part bare | - | "<level>: take it in hand" / "Show this part on its own" |
| Parts / Layers (two-way switch) | Card by part, or every layer of the file as a list with pictures (eye and folder targets at least 36 dp, audit 22) | - | "Work part by part, from the body map" / "Every layer of the file, as a list" |

### The four stages (36 dp row, thumb reach)

The second stage's word is **Arrange** (one constant: `R.string.avatar_stage_2`, so it changes in one place; the code
name stays `fit`). The owner has not confirmed the word yet.

| Tab | The question it answers | The picture | A drag on the picture | Hover label |
|---|---|---|---|---|
| Set up | What exists, and where does it hinge? | still, shows pins | moves a pin | "Set up: bring parts in and place their pins" |
| Arrange | Does it look right here, then in every pose? | still, shows the box on the part in hand; pose row | moves the part; corners resize; the handle below turns | "Arrange: make it look right in every pose" |
| Motion | How does it move? | alive | pulls the character, nothing recorded | "Motion: how it moves and behaves" |
| Perform | (play and record) | alive | puppeteers | "Perform: play it and record takes" |

The full map of which card section lives in which stage is the design note
[AVA-1_stages_20261121.md](../design/AVA-1_stages_20261121.md) ("Where things went"), built and kept in the pilot's
`al-stages.js` (each section is tagged `setup` / `fit` / `motion` / `all`). The phone uses the same tags on the same sections.
The earlier "one long card per part" layout stays a choice in the Menu (the owner asked to keep it, round 11).

## A3. The drawer (Menu)

| Control | One press | Hover label |
|---|---|---|
| Open | The system file picker for a `.character.json`, `.scene.json`, a layered file (`.psd`, `.psb`) or pictures | "Open a character, a scene or a layered art file" |
| Save | Writes the character (or scene) to the Joy Creator folder now (it also saves by itself) | "Save it in your Joy Creator folder now" |
| Send | Android share of the file | "Send the file to someone or somewhere" |
| Close | Closes the drawer | "Close" |
| Row of characters (cards 58 x 52 dp, one always cut off at the edge) | Create new, then yours (last used first), then Topps and Pip. A tap opens it; holding offers Rename, Copy, Remove (with undo). | "<name>: open" / "Create a new character" |
| Create new avatar | The four doors (A4) | "Make a new character from your art" |
| (a line) | | |
| Create new scene | A new scene on a set (Meadow, Room, Night, Studio, Plain), opens Perform with the Scene part up | "Make a new scene to put characters in" |
| Your scenes (rows with a picture) | Opens Perform with that scene chosen | "<scene name>: open in Perform" |
| One long card per part | Tick box: the earlier layout | "Show the earlier layout: one long card per part" |
| The older Avatar room | Opens `AvatarStudioActivity` | "The Avatar room from before, for characters made there" |

## A4. Bringing art in (the doors)

A sheet with four big tiles, each with a picture and one sentence, plus "From JoyBrush" when a board was sent.

| Door | Steps on the phone | Hover label |
|---|---|---|
| A layered file | File picker; read with the PSD reader (port of `al-psd.js`); rigged from layer names; pins guessed from the pixels; a summary sheet says what it found ("7 head angles, 2 arms of 8 drawings, a tail") and what it could not read (clipping and blend layers counted and said) | "A layered file (.psd): every layer becomes a part, rigged by its name" |
| Loose pictures | Multi-pick; each picture lands in Extras; tap a part on the body map, then a picture, to say what it is | "Separate pictures: one per part" |
| One sheet of parts | One picture; tap each part; the cutter keeps only what touches the tap, removes background touching the edge | "One picture with the parts laid out: tap each part" |
| One whole drawing | Tap head, hands, feet (7 taps); it bends as one pinned drawing | "One finished drawing: tap the head, hands and feet and it bends" |
| Update the art (Menu, on an open character) | Re-opens the repainted layered file; every pin and setting stays; new layers land in Extras; a bigger file of the same shape comes in as finer art (round 19) | "Bring in the repainted file: every pin and setting stays" |

## A5. Set up

The card for the part in hand, sections tagged `setup` (design note, table "Where things went"). On the picture: pins
(yellow = where it turns, blue = holds a spot as it bends; only the pins that wire this part, round 11-22 ruling), drag to move,
one undo step per drag, magnifier while dragging.

Head in Set up: **its angles pad** (132 dp square; dots anywhere, any number; the mirrored side drawn hollow), **This angle**
(Copy this angle, Rename, Remove), Round head, and the **landmarks** entry (A9). Arms and legs: Its drawings (a fan of
pictures, "Try this drawing"), This arm (Copy, Remove), In its hand. Body: Which way it faces, Its own turn drawings.
Every layer: What it is, Shapes, Carried by, Its looks, Round shape, Pins on this drawing.

## A6. Arrange

On the picture, Arrange shows the **transform box** (one cyan pixel line; corners 20 dp targets; the turn handle below,
kept clear of the stage buttons: the round 7c crest fault came from a handle hidden behind them) and the **pose row**
(Standing, each stance with its own picture, + for a new one). In a stance, what is moved is moved for that stance.
Sections: Fit this angle, In front or behind (**the stack**: one row of 50 dp tiles in drawing order, back on the left,
the part in hand cyan; Forward one, Back one, Very front, Very back, or tap another part to step past it), Stances (first
on the Body card), Colour sets, Masks, Size and turn. "Put it back" on every box.

| Pose row control | One press | Long press | Hover label |
|---|---|---|---|
| Standing | Works on the standing pose | - | "Standing: what you move is moved when it stands" |
| A stance chip (its picture) | Works on that stance; the character goes into it | Rename, Copy, Work it out again, Remove | "<stance>: what you move is moved in this stance only" |
| + | New stance from the pose now | - | "Make a new stance from how it stands now" |

## A7. Motion and Perform

**Motion** is the character alive with nothing recorded: How it bends, Walking, Breathing, Turning around (Smooth /
Instant / A card), Swinging, Follows, Loop, Stream; every card ends with "Play with it" (the turn pad, Hold to talk, its
buttons, moods). A tap chooses a part without taking hold of it.

**Perform** (phone portrait): the card area becomes the performer's panel.

```
│ ● ▶  ───────●────────── 0:04.2   ↺pass  │ 44 dp  the take bar (the only Record / Play)
│ ┌────────┐  [Hold to talk]   ┌────────┐ │        head pad (132 dp)   ·   joystick pad (same size, when open)
│ │  pad   │  🎤  🎵  🙂  🕹    │ stick  │ │        Mic, Voice file, Face, Joystick (pictures; words in hover labels)
│ └────────┘                   └────────┘ │
│ [smile][All fours][Coral] ...           │        its buttons, right under the transport (audit 25)
│ moods: 😐 😴 😠 😢 😊 🤨  ·  Breeze ───  │
│ Walking ▸   Walk with your face ▸        │
```

| Control | One press | Long press / drag | Hover label |
|---|---|---|---|
| Record (red dot, `Studio.LIVE`) | Starts a pass: writes only what you drive (head, mouth, mood, blink, each hand, each foot, body, wind, each button, each pin), hands each back over a quarter second | - | "Record a pass: only what you move is replaced" |
| Play | Plays the take (a scene: everything) | - | "Play the take" |
| Take strip | Drag to scrub (silent), arrow keys nudge | - | "Drag to move through the take" |
| Take back the last pass (its own icon, not Undo's) | One tap takes the whole pass back, voice and all | - | "Take back the last pass" |
| Clear the take | Asks first, then empties | - | "Throw this take away" |
| Head pad | Drag turns the head; any number of angles | - | "Drag to turn the head" |
| Hold to talk | The mouth talks while held | - | "Hold to talk: the mouth moves while you hold" |
| Mic (tick on = solid cyan) | Lip-sync from the microphone; the voice is kept with the take while recording | - | "Talk into the phone: the mouth follows your voice" |
| Voice file | Picks a sound; it plays and the mouth follows | - | "Play a voice recording: the mouth follows it" |
| Face | The face camera drives head, eyes, brows, mouth (FACE-1); a small private preview | Calibrate (neutral face) | "Your face drives the character" |
| Joystick | Opens the second pad: a rate stick that walks the body; tap or flick = a gesture | - | "A second pad for your other thumb: walk the character" |
| Joystick sliders (pad corner) | The gestures sheet: six slots (Tap, Two taps, Swipe up / down / left / right), each offering only what this character can do; "Up and down walk into the picture" tick; Camera angle slider | - | "Choose what each tap and flick does" |
| A button chip (its picture, audit pictures list) | Switches it on/off as a smooth level | - | "<button>: on and off" |
| Mood chips (eyes in that mood) | Seven moods | - | "<mood>" |
| Blink | Tap blinks, hold keeps the eyes shut (round 3) | hold | "Blink (hold to keep the eyes shut)" |
| Breeze | Slider, recorded | - | "Wind on anything that swings" |
| Walk with your face | Drawer of three tick boxes (Left and right, Closer and further, Up close) | - | "Walk with your face: lean to a side, come closer" |

Many fingers: every finger that lands on a hand, a foot, the head, the body or a pin owns it until it lifts; fingers on
empty stage pan and pinch.

## A8. Recording, step by step

1. Perform, red dot. The microphone (if Mic is on) starts on the take's first frame through Video Studio's recorder
   (part D); the take's clock follows the sound's clock so they cannot drift.
2. Drive what you want. Only those owners are written. The rest plays back from the old take underneath.
3. Red dot again (or Play) stops. The pass is one undo step and one "take back the last pass".
4. A voice recorded in a pass replaces the old voice only for the stretch it was on, with 6 ms blended at each end.
5. The take saves with the character by itself (autosave after the pass). Leaving the room lets go of the camera and the
   microphone at once (audit "before the port" list); a microphone permission granted late never starts capture outside Perform.

## A9. Landmarks: matching the angles of a piece (the owner's model of 11-23)

Opened from the head's angle pad ("Landmarks" on the head card, and on every face-part and layer card inside the head) or
from a part's looks ("Blend two looks"). It replaces the pilot's pair-by-pair "Match two angles".

- **One numbered set of landmarks per piece** (the whole head, or a piece such as the front frill that warps on its own).
  Every angle places the **same** points. Point 3 is point 3 in every angle.
- The stage shows the angle chosen on the pad, solid, with its points numbered; the neighbouring angle as a ghost
  (onion button: tinted cyan/magenta, or its own colours with a **See-through** slider and **Solid** to swap which is on top).
- A **new angle starts with the points already there** (copied from the nearest placed angle, moved by the angle's own box),
  to be dragged into place.
- Anywhere on the pad, between two angles or three, each point blends between its own places, so nothing can fight.

| Control | One press | Hover label |
|---|---|---|
| + Add a landmark | Two taps: the spot on the solid drawing, then on the ghost (or "It is in the same place"). In every other angle it starts "not here", parked where it was tapped, ready to be dragged into place (built so in the pilot, 11-27: a guessed "behind" would bend the other angles toward a wrong spot) | "Add a landmark here: it appears in every angle, ready to drag" |
| A point (numbered dot, 28 dp target) | Chooses it; drag moves it in this angle only | "Landmark <n>: drag it to the same spot in this drawing" |
| Behind | The chosen point is folded round behind in this angle: a ghost dot that still has a place and still steers the bend | "Behind: this spot is round the back here, but still steers the turn" |
| Not here | The chosen point takes no part in this angle: a small circle with a line through it, greyed | "Not here: this spot does not exist in this drawing" |
| Here | Back to a normal point | "Here: this spot is seen in this drawing" |
| Remove landmark n | From every angle (one undo step) | "Take landmark <n> out of every angle" |
| They meet (slider, per neighbour pair) | Where two drawings meet on the way between them | "Where the two drawings meet on the way between them" |
| Warps with | The whole head / By its own landmarks / With <another piece> | "How this piece bends when the head turns" |
| Done | Back to the card | "Done matching" |

Behind is the default guess for a point dragged off the drawing's pixels. A point that is "not here" in both drawings on
screen (it belongs to other angles) is not shown unless it is the chosen one. Every change is one undo step. Built and
checked in the pilot (step 0, 11-27): `tools/avatarlab/al-bridge.js`.

## A10. Scenes, inside Perform

No scene room. The path tabs start with the scene (`Meadow › Topps › Head`); the scene's own tab wears the director's chair,
unlit with a character in hand, lit with the scene in hand; tapping it puts the scene in hand. **The empty scene** (no
scene open) is the plain dark stage as before and is always the first card; it cannot be deleted.

**The two round buttons** (36 dp, grey, bottom right of the stage in Perform, side by side, counted as part of the strip):

| Control | One press | Long press | Hover label |
|---|---|---|---|
| Frame (its shape in brackets, "[16:9]") | Frame on / off (on = solid cyan). The frame is only a shape: a zinc hairline, a little darker outside, Studio's canvas texture beyond the stage | Sheet: 16:9, 4:3, 1:1, 4:5, 9:16 (each with its picture and use), margins (Off, Title, Social), tick box "Room round the frame" | "The picture's shape: tap to show it, hold for shapes" |
| Director's chair | Opens upward into three tick chips: Scene, Character, Shots | - | "What the strip shows: scenes, characters, shots" |

**The strip** sits above the panel's edge, see-through, and shares its width between the parts that are on (one: all of
it; two: halves; three: thirds). Each part scrolls sideways by itself and ends in its own add card. It pushes the frame
the way Studio's transcript drawer pushes the picture (only as far as its room allows, never smaller): from the side for an
upright frame, from the bottom for a wide one, a square one from whichever side has more room (`DrawerPlace` rule).

| Part | Cards (58 x 52 dp, picture + name) | Tap | Long press, or a second tap on the chosen card | Hover label |
|---|---|---|---|---|
| Scene (green top edge) | Empty, your scenes, + New | The scene changes | Puts the scene in hand at Arrange | "<scene>: show it" / "+: a new scene" |
| Character (violet) | The cast, + Add | That character is in hand; its buttons and moods fill the panel | Puts it in hand at Arrange, alone on the plain stage | "<name>: perform this one" |
| Shots (amber) | Cut / Glide switch, Wide, Two-shot, Close on each, yours, + Keep this view | The camera goes to the shot (cut or glide) | Frame it by hand, rename, keep, remove | "<shot>: send the camera there" |

Shots need the frame: turning Shots on turns the frame on; frame off folds Shots away and the camera goes to Wide.

**The scene in hand** uses the same four stages: Set up brings things in (its picture in the body map's place; Add a
character, an object, a backdrop, a foreground; the set; what is in it; name, delete), Arrange places them (drag up the floor
and it goes back, smaller, behind what stands in front; size; flip; the frame's tick box, shapes and margins), Motion is the
scene alive with nothing recorded (wind, haze, an object's sway and loop speed, Try the shots), Perform is Perform.

**One transport.** The take bar is the scene's clock when two or more characters are in it: red records the one in hand
while the others play back; Play plays everything; cuts are recorded on the camera lane (Clear on the Shots part).

**Walking across and off.** Dragging the body walks the character across the whole stage and out of the picture, up to a
third of the stage's width past each edge; a finger held within 26 dp of the glass edge means "keep going". A character out
of sight has a **tab** at that edge (its picture, 36 dp): press it and it walks to the edge, drag it and it walks in after
the finger (hover label "<name> is off to the <side>: drag to bring them in").

## A11. The export sheet

Opened by the export mark. A `SheetKit` sheet (Studio's export sheet's own parts, part D), 55% see-through.

| Control | Kind | Hover label |
|---|---|---|
| Joy Creator file | choice | "Keep it as a Joy Creator file: opens in Studio and here, still editable" |
| One frame | choice | "One picture of this moment, at the frame's shape" |
| Video | choice (shown only where this phone's encoder can make it) | "A video of the take, with its voice" |
| Send to Studio | button | "Put it in a Studio project with its take" |
| Each character | tick box with its picture | "Include <name>" |
| The set | tick box | "Include the set behind" |
| See-through (one frame) | tick box | "A picture with nothing behind the character" |
| Behind (video): the stage / a key colour | switch | "What is behind the character in the video" |
| Key colour: Green, Blue, Magenta, Pick | chips with their colour (starts Green; offered only where something shows behind) | "<colour> behind, to cut out later" |
| Size: 1080p, 720p, 480p | chips (only the sizes the encoder takes) | "<size> on the short side" |
| Preview | the first picture as it will be | (none) |
| Make it | button | "Make the video" |
| Progress bar + Stop | while making | "Stop: nothing is saved" |
| Sprite sheet (More) | the old bake for Sprite Lab, kept | "A sprite sheet of the take, for Sprite Lab" |

Show only what works: a dead choice is worse than a missing one. If a video cannot be made, one plain sentence says so.

## A12. Joybot in the room

The orb opens him with what is in hand. His tools (part E, per slice) are the room's acts as named commands with plain
arguments, the same list a connected model uses; they change the character only through the same checked edits the room
makes (rule 6 of AGENT_START: validated edits, never raw JSON), each one undo step named for Joybot.

## A13. Phone landscape (Note 9 sideways: 846 x 411 dp)

```
┌───────────────────────────────────────────────┬──────────────────────┐
│ ☰ ↶ ↷                              ⬆  ◉       │ [map] Topps › Head   │ 44 dp header (Parts|Layers in a menu)
│ ┌┐                                             ├──────────────────────┤
│ ││ stage tools: a column at the stage's LEFT   │ Set up|Arrange|Mot|Pe│ 36 dp
│ └┘ edge (audit: Try it / Pick a piece strip)   ├──────────────────────┤
│               (the character)                 │  the card (340 dp)    │
│  hint: one-line strip at the bottom           │                       │
└───────────────────────────────────────────────┴──────────────────────┘
```

- The panel is on the right, 340 dp, the same 44 dp header; the stage keeps the rest. The pose row runs along the stage's
  bottom edge in Arrange.
- Perform: the take bar across the top of the panel; head pad on the left of the panel, joystick on the right of the
  stage's bottom corner when open (two thumbs, one each side of the phone).
- Scenes: the strip comes from the side for an upright frame (shares the height), from the bottom for a wide one.

## A14. Tablet (Tab S8 Ultra; `DrawerPlace` decides: side panels from 840 dp)

- **Tablet portrait (800 x 1280 dp class):** stage on top (about 60%); below it the panel in two columns: a rail (about
  200 dp) with the body map at double size, labelled, and Layers under it; the card on the right; sliders capped at 320 dp
  so two sit side by side. Perform: pad and Hold to talk on the left, buttons, moods and walking on the right, no scrolling.
- **Tablet landscape (1280 x 800 dp class):** left rail (about 220 dp) with the labelled body map, Layers, the stances and
  colour sets, always visible; the stage in the middle; the card on the right (420 to 560 dp, sections in two columns).
  In a scene, a tapped character's expressions sit right there in the right column (the owner's ask).
- Sizes stay as drawn: the tablet gets more columns, never bigger buttons.

## A15. Not decided here (the owner's, or open on the card)

- The second stage's word (Arrange is the lead's pick).
- The icons he has not decided (round 9 examples page) and the three he sent back.
- Whether the four-way stage row stays at the top of the panel or moves to the very bottom of the screen.
- The older Avatar room: when it may leave the Settings list.
