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
(a talking mouth), `look` (a pupil), `blink`, `dangle` (a tail), `breathe` (a chest), `mesh` (a pinned
whole drawing), `links` (follow any signal), `depth` (slide between head angles). The file is plain JSON.

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
| `al-demo.js` | Pip, drawn in code as a layered file and pushed through the same automatic rig. | Ships as the sample character. |

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

## Not checked

- The face camera (MediaPipe in the browser) is written but has not been tried with a real face.
- The microphone lip-sync has not been tried with a real voice; the Talk button and the take path have.
- Bending a pinned whole drawing is rougher here than the phone's existing puppet engine.
- Nothing has been built or tried on a phone. This is the design pilot only.
