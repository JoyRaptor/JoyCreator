# Avatar Studio: the web pilot

A pilot of the redesigned Avatar Studio, in the browser, at phone size. It is where the design gets
settled before it is built on the phone (phone-first rule: nothing here is meant to stay desktop-only).
Job card: `tasks/jobs/AVA-1_avatar_studio_redesign.md`.

**To run it:** serve this folder and open it (the preview entry is called `avatarlab`):

```bash
python -m http.server 8795 --directory tools/avatarlab
```

then <http://localhost:8795/>. `?demo` starts from Pip, the practice character. It is also published as a
private Claude artifact (`node tools/avatarlab/build.mjs` folds it into one file in `dist/`).

## What it is

One idea: **a character is a tree of three things**, the same three a layered art file has.

| Node | Draws | Used for |
|---|---|---|
| group | every child | a folder |
| set | ONE child, picked by a rule | head angles (by turn), arm drawings (by reach), mouth shapes (by voice or a button) |
| art | one drawing | everything you painted |

Behaviour hangs off a node as a small record: `limb` (shoulder, elbow, hand pins), `hinge` or `squash`
(a talking mouth), `look` (a pupil), `blink`, `lid` (an eyelid), `clips` (masks), `swing` (anything that
dangles), `breathe` (a chest), `mesh` (a pinned whole drawing), `links` (follow any signal), `depth`
(slide between head angles). The file is plain JSON.

**Masks (`clips`).** Any part can be shown only INSIDE another part, or be HIDDEN where another part is.
The masking part may be drawn too, or be invisible (`maskOnly`). It is an ordinary part, so it moves,
tilts and swaps like any other: that is what makes a mask animatable. Drawn with the art's own soft edge.

**Eyelids (`lid`).** An upper lid that comes down (relaxed), tilts (angry or sad) and a lower lid that
pushes up (cheery), made without art. Two modes: *invisible* lids cut the eye, so the face painted
underneath shows (right for a pupil painted straight onto a face, like Topps); *drawn* lids are
skin-coloured, sampled from the face, and kept inside the white of the eye. A layer named "eyelid" or
"lower lid" in the file becomes a lid too. Seven moods (Relaxed, Sleepy, Angry, Sad, Cheery, Wary) are
three lid numbers and one brow number.

**Swinging (`swing`).** Two kinds. *Bendy* (hair, a braid, a feather, a tail) is a jointed chain that is
dragged by its root and catches up late; the drawing is skinned along it. *Stiff* (a chain link, an
earring, a charm) never bends: it swings from its pin, knocks against its limits, and whatever hangs
from it rides along, so links hung one from the next behave like a chain. Both have springiness, settle,
follow-through, "hangs down", and a Breeze amount; bendy adds joints and flutter. Wind is a recorded
signal like any other.

**Walking (`settings.walk`, `al-walk.js`).** There is no walk-cycle animation. Each foot is planted at a spot
on the stage and stays there, so nothing slides, until the body has left it behind; then it lifts, swings ahead
and plants again. Legs are ordinary bending limbs from hip to foot. A hand nobody holds swings against the
legs; the body bobs and leans; the tail follows because it already swings. Two scenes: *from the side* (one
ground line: lift the body and the legs tuck or hang, let go and it falls and lands) and *from above* (every
direction is walking, further up can be smaller). Two gaits: steps, hops. A foot under a finger is that
finger's until the body moves on. Stride, step height, bounce, lean and arm swing are five sliders.

**Many fingers.** In Perform every finger that lands on a hand, a foot, the head, the body or a pin owns it
until it lifts: two hands, a hand and a foot, both feet and the body. Fingers on empty space pan and pinch.

**Bridges (`set.bridges`, `al-bridge.js`).** Between two neighbouring head angles the artist marks the same
landmarks on both drawings (tap, then drag the magenta twin onto the ghost). As the head turns, each drawing
is warped toward the other so the landmarks arrive together and the swap does not jump. A mirrored angle
takes its bridge from the pair it mirrors. The warp is the same moving-least-squares the phone's puppet mesh
engine already has.

**Each angle is its own stack.** In front of or behind the face is set per angle (an ear in front on one side,
behind on the other; a tuft behind the head when he looks up). Any set of angles can have its own pad
(`pick.dial`), so a body can turn separately from the head.

## Screens

- **Build.** A body map says which part; one card per part says, in words, what it does. Everything
  holds still so pins can be dragged. "Try it" brings the stage to life without leaving the card.
- **Perform.** Drag the head, the hands, the body on the character itself. Hold Talk, use the mic or a
  voice file. Record; a second recording only replaces what you touch.
- **Four doors for art:** a layered `.psd` (rigged from layer names, pins guessed from pixels), loose
  pictures, one sheet with the parts laid out (tap the parts), or one whole drawing (tap head, hands, feet).

## Files

| File | Job | Phone twin (reuse, do not rebuild) |
|---|---|---|
| `al-psd.js` | Reads `.psd`/`.psb`: layer tree, RLE/raw/ZIP pixels, masks. No dependency. | New on the phone (port this). |
| `al-model.js` | The character tree, roles, undo, JSON. | `AvatarRig` grows into the tree; `AvatarLibrary` bundle keeps storing it. |
| `al-solve.js` | ONE pose solver: signals in, textured meshes out. Stage, replay and bake all call it. | `PuppetPoseResolver` (sets, hysteresis, cross-fade), `PinWarpStrip` + `FabrikSolver` (limbs), `DangleSim`, `PuppetRigSolver`/MLS + `PuppetWeights` (pinned drawing). |
| `al-gl.js` | Textured triangles, premultiplied alpha. | The existing GL stack; same shader. |
| `al-autorig.js` | Names to roles, folders to sets, pins guessed from pixels. | New. `AvatarRigTemplates` (unused today) is its natural home. |
| `al-inputs.js` | Touch, voice, face camera, blinks and breathing, takes. | `TrackingDriverBus`, `MediaPipeTrackingSource`, `SpectralVisemeAnalyzer`, `LifeSignals`, `AvatarParamTrack`. |
| `al-app.js`, `al-cards.js`, `al-perform.js`, `al-intake.js` | The screens. | The new Avatar Studio activity. |
| `al-walk.js` | Planted feet that step as the body is dragged; arm swing, bob, lean; hop and hang. | New. Signals in, signals out: ports as it stands. |
| `al-bridge.js` | Matching two head angles with landmark pairs; the bridge tool. | Warp = the puppet mesh engine's MLS. |
| `al-demo.js` | Pip, drawn in code as a layered file and pushed through the same automatic rig. | Ships as a sample character. |
| `serve.mjs` | Local server: no caching, and PUT into `local/` (how a rigged character is saved beside the page). | Pilot only. |

## What is new here (the parts worth the port)

1. **Pins guessed from the art.** All drawings of one arm overlap at the shoulder, so the shoulder is
   where most of them are opaque at once; the hand is the far end. A 16-drawing arm fan needs no taps.
2. **Reach = swap + bend.** The drawing whose hand is nearest wins, then a two-bone bend takes the hand
   the rest of the way; the swap cross-fades with both drawings solved to the same pose.
3. **Head angles as dots on a pad**, not a fixed 3x3: any number of drawings, anywhere; missing sides
   mirror; face parts slide by depth between angles so the swap does not pop.
4. **The whole character can turn around**: one three-quarter drawing faces both ways.
5. **Layered takes**: a second pass replaces only what was touched.
6. **Follows**: any part can ride any signal (mouth, eyes, blink, breath, turn).
7. **Lids without art**, in two modes, and moods built from them.
8. **Stiff chains and a breeze**, beside the bendy swing the phone already has.

9. **Takes that cannot eat your work.** Every signal has an owner; a pass writes only the owners you drive and
   hands each back over a quarter second; one tap takes a pass back; the take is saved with the character.
10. **Update the art.** Re-open the repainted layered file: layers are matched by folder path and name, every
    pin and setting stays, new layers land in Extras.
11. **Loops and rubber-hose limbs.** A part whose drawings are frames; a limb with no elbow that bows, stretches
    and wobbles between its shoulder pin and wherever the hand is.
12. **Carried by.** Any part rides the end of a limb or a swinging part, on a loose wrist, and can swap drawing
    by the way the limb points. It goes behind the body when its limb does.
13. **Streams.** One part sent out as copies that drift, grow and fade (rings, bubbles, sparks, dust).
14. **A round head from one drawing.** Parts are painted on the ball (slide, foreshorten, vanish round the
    edge), ride it in one piece (an eye with working lids), or stick out of it and pass behind.
15. **Bouncy follows, and buttons as levels.** A follow can overshoot and settle; any button is also a smooth
    0..1 level that parts can follow; a part can shake or spin.

## Characters

- `characters/topps.character.json` is the demo character (the owner's art, in the repository at his word).
  `node build.mjs` folds it into the published page.
- `local/` is ignored: the owner's PSD, and a Joybot draft (`local/joybot.character.json`, built by
  `local/joybot-build.js` from pictures; no code of his own runs when he plays). `build.mjs` folds the draft into
  the page when it is on the machine; `node build.mjs --no-local` leaves it out.

## Not checked

- The face camera (MediaPipe in the browser) is written but has not been tried with a real face; that includes
  the new "while talking" mode that steers from where the hand left the head.
- Walking, swinging and the bridge warp were checked in still frames and by numbers, not watched in motion.
- Many-finger dragging was not tried with real fingers (the test browser has one pointer).
- The microphone lip-sync has not been tried with a real voice; the Talk button and the take path have.
- Bending a pinned whole drawing is rougher here than the phone's existing puppet engine.
- Nothing has been built or tried on a phone. This is the design pilot only.
