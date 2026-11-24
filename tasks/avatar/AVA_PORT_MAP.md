# Part C: the port map (each pilot file and what it becomes on the phone)

Part of the [Avatar Studio phone spec](AVA_PHONE_SPEC.md). Written 2026-11-23 from each file's `Phone:` line (or the
README's "Phone twin" table where a file has none), checked against the Android source at `cece72679`.

**Status:** ✅ exists in the app and is used as it is · 🔧 exists, needs a change (said what) · 🆕 new class.
`F` = `app/src/main/java/com/fadcam/ui/faditor`, `S` = `studiokit/src/main/java/com/fadcam/ui/faditor`,
`R` = `app/src/main/java/cc/joycreator/avatar` (new package, the new-wing pattern TextLab set with `cc.joycreator.textlab`).

**One correction applies to every row:** the `Phone:` lines name Compose pieces (LazyRow, ModalBottomSheet, Compose
Canvas, pointerInput, combinedClickable). The app has no Compose (no `@Composable` anywhere). Read them as their Views
twins: `RecyclerView` row, `SheetKit` / `StudioDialog` sheet, a custom `View.onDraw(Canvas)`, `onTouchEvent` /
`GestureDetector`, `setOnLongClickListener`.

## C1. The engine (no screens)

| Pilot file | Lines | Its job | Phone class | Status |
|---|---|---|---|---|
| `al-model.js` | 176 | The tree, roles, undo snapshots, JSON | `R/file/CharacterDoc` (the tree as plain objects, Gson), `R/file/CharacterFile` (read, upgrade, write, the two packings), `R/file/SceneFile`. Roles and zones as one enum `R/model/Role` | 🆕 The old `F/avatar/AvatarRig` is a pose-matrix model and does not grow into this tree without breaking the old room; it stays for the old room (spec call 2) |
| `al-format.js` | 308 | The format's rules, `/1` to `/2`, landmark blend | `R/file/FormatCheck` + `R/file/Marks` (upgrade, blend), tested in `tools/jvm-harness` on the files in `tasks/avatar/format-tests/` | 🆕 |
| `al-solve.js` | 1023 | ONE pose solver: signals in, textured meshes out (sets, swap + bend, masks, lids, swing, ball, riders, streams, landmarks warp, stances) | `R/solve/CharacterSolver` (a port, plain Java, headless) calling: `F/transform/mesh/PuppetDeformer` (rigid MLS, for landmark warps and pinned drawings) ✅, `F/avatar/FabrikSolver` (chains, hose limbs) ✅, `F/avatar/PinWarpStrip` (limb skin strips) ✅, `F/avatar/DangleSim` (bendy swing) 🔧 (needs the pilot's stiff kind: links that never bend and hang from each other, and Breeze as an input), `F/transform/mesh/PuppetWeights` + `PuppetTriangulator` (mesh for a pinned drawing) ✅. Single authority, as `F/avatar/PuppetPoseResolver` is for the old room | 🆕 solver, reusing ✅ math. Golden parity files from the pilot (spec call 3) |
| `al-gl.js` | 156 | Textured triangles, premultiplied alpha, masks by multiply | `R/draw/CharacterGl`: `F/transform/mesh/MeshEngine` + `MeshGlSource` (the one shader source preview and export share) ✅ for the triangles; masks by an FBO pass (the file's own note) 🆕; the colour-set shader is a second small program used only while a set is on (Note 9 rule: program size is the cost) 🆕 | 🔧 |
| `al-inputs.js` | 321 | Touch, voice, camera, blinks, breathing, takes (punch-in per owner) | `R/perform/Inputs` (owners, passes, the take clock) 🆕; reads `F/avatar/TrackingDriverBus` ✅, `F/avatar/LifeSignals` (breath, saccades, blink) ✅, `F/avatar/SpectralVisemeAnalyzer` + `AudioLevelViseme` (mouth shape and opening) ✅, `F/avatar/OneEuroFilter` ✅ | 🆕 + ✅ |
| `al-face.js`, `al-facepins.js` | 415, 658 | The face camera, a line-for-line twin of the phone's | `F/avatar/face/*`: `FaceCapture` (the seam: start, `sample()`, stop), `FacePoints`, `FaceSignals`, `FaceCalibration`, `FacePreviewView` | ✅ (FACE-1 landed; parity checked by `tools/jvm-harness/face_parity.mjs`) |
| `al-pose.js` | 274 | Shoulders from the body tracker (SHRUG-1) | `F/avatar/face/ShoulderStage` | ✅ |
| `al-autorig.js` | 530 | Names to roles, folders to sets, pins guessed from pixels, eyelids by default | `R/rig/AutoRig` 🆕 (README: `F/avatar/AvatarRigTemplates`, unused today, is its natural home; it belongs to the old rig model, so its templates are read for ideas, not extended) | 🆕 |
| `al-psd.js` | 202 | Reads `.psd` / `.psb`: layer tree, RLE / raw / ZIP pixels, masks; no dependency | `R/intake/PsdReader` (a port; no PSD reader exists in the app) | 🆕 |
| `al-bridge.js` | 169 | Matching angles with landmark pairs | `R/solve/Marks` (the owner's one-set model, part B) + the landmarks screen `R/ui/LandmarksPanel`. Warp: `PuppetDeformer` ✅ | 🆕 (the pairs do not port; they convert) |
| `al-walk.js` | 333 | Planted feet, any number of legs, hop, jump, tiptoe, foot bar | `R/solve/Walker` (signals in, signals out: arithmetic, ports as it stands) | 🆕 |
| `al-stage-walk.js` | 156 | Walking across the stage and off; the edge rule; off-screen tabs | Two limits on `Walker`; the edge rule in `R/ui/StageView.onTouchEvent`; tabs = small `View`s at the stage's edges | 🆕 |
| `al-depth-walk.js` | 74 | Size for where the ground line is; front drawing when coming closer | Two functions beside `Walker` | 🆕 |
| `al-take-walk.js` | 62 | Walking hands kept as walking; a later pass owns the body; a take leads the walker | `R/perform/TakeWalk` (the pilot line said `AvatarParamTrack` gains two lanes: corrected, the take is the file's own take, spec call 5) | 🆕 |
| `al-stance.js` | 169 | Whole-body poses on a spring, arcs, the worked-out All fours, `heal` | `R/solve/Stances` | 🆕 |
| `al-tone.js` | 184 | Colour sets by hue, from a picture, arriving as a wave | `R/solve/Tone` (numbers) + the second shader in `CharacterGl` | 🆕 |
| `al-fit.js` | 239 | The transform box, reshape with pins | `R/ui/FitBox` drawn on the stage; numbers `dx, dy, rot, sc` per part. Studio's `F/overlay/PreviewHandlesOverlay` (move, corners, turn stalk) is the model for the look | 🔧 (reuse its drawing and hit sizes; the stance-aware part is new) |
| `al-stack.js` | 146 | One control for in front or behind | `R/ui/StackRow` (a `RecyclerView` of 50 dp tiles) | 🆕 |
| `al-stick.js`, `al-stick-moves.js`, `al-stick-depth.js` | 119, 132, 91 | The joystick, its gestures, walking into the picture | `R/ui/StickPad` (a `View` with a `GestureDetector`: tap, double tap, fling), the gestures sheet a `SheetKit` sheet, the floor grid drawn on its `Canvas` | 🆕 |
| `al-face-walk.js` | 92 | Walk with your face | A small drawer under Walking; reads `FaceSignals.LEAN_X` and `NEAR` through `FaceCapture` | 🆕 (on ✅ signals) |
| `al-voice.js`, `al-voice-play.js` | 170, 55 | The voice kept with a take; hearing it | `F/audio/VoiceoverRecorder` 🔧 (part D: a level tap) started on the take's first frame; the stretch rule and 6 ms blend on the WAV after the pass (`R/perform/TakeVoice` 🆕); hearing: `F/compositor/AudioClipPreviewPlayer` ✅ on the take's clock | 🔧 + ✅ |
| `al-video.js`, `al-export-video.js` | 192, 89 | The video: fresh performers per frame, key colour, sizes, progress, Stop | `F/export/ExportService` + `ExportManager` ✅ (Media3, H.264 + AAC, 30 fps, 1080p / 720p / 480p, progress broadcasts, cancel) with the character drawn by a new `CharacterItemPuppet` 🆕 from `F/export/CompositeExportOverlay` 🔧; the key colour as a solid-colour clip 🔧 (part D) | 🔧 |
| `al-mp4.js` | 70 | The pilot's own MP4 writer | Not ported (Media3 writes the MP4) | n/a |
| `al-scene.js` | 361 | The scene, the cast, the acts | `R/scene/Scene` (document + cast, each cast member one `CharacterSolver`), acts as plain methods | 🆕 |
| `al-scene-take.js` | 139 | One clock, passes, the camera lane, shots | `R/scene/SceneClock` (the same 30 fps clock), lane = list in the scene file | 🆕 |
| `al-scene-draw.js` | 264 | The frame loop: camera, depth, drawing, fingers | `R/ui/StageView` (one `View` over the GL surface; fingers in `onTouchEvent`), depth and haze in `Scene` | 🆕 |
| `al-agent.js` | 94 | The room's acts as named tools (18) | `R/ai/AvatarTools`, registered in the `avatar` family of `F/ai/joybot/ToolRouter` ✅ (which today routes only the legacy `author_avatar_rig`, `apply_avatar_rig`); changes go through `F/ai/EditScriptApplier`'s rule: checked edits, one undo step each | 🔧 + 🆕 |
| `al-demo.js` | 120 | Pip, drawn in code | Pip and Topps ship as `.character.json` files in `app/src/main/assets/avatar/` (Topps is the demo, the owner's ruling) | 🆕 (asset) |

## C2. The screens

| Pilot file | Its job | Phone class | Status |
|---|---|---|---|
| `al-app.js` | The room: stage, top bar, panel, autosave, the fit | `R/AvatarRoomActivity` (Views), `R/ui/StageView`, `R/ui/TopBar`, `R/ui/PanelHost`; autosave through `R/file/CharacterFile` into the Joy Creator folder (the `AvatarBundleFile` / `AvatarFolderCopy` pattern, `com.fadcam.joyfolder.studio`) | 🆕 (old `F/avatar/AvatarStudioActivity` kept) |
| `al-stages.js` | The four stages, sections tagged by stage, the earlier layout kept | `R/ui/StageTabs` + the same tags on the same sections | 🆕 |
| `al-cards.js` | One card per part (the biggest file) | `R/ui/cards/*Card` (Head, Eyes, Mouth, Brows, Jaw, Nose, Hair, Arm, Leg, Body, Tail, Layer, Extras), each a scrolling list of foldable sections built with `SheetKit` rows; words and hover labels from [AVA_HOVER_LABELS.md](AVA_HOVER_LABELS.md) | 🆕 |
| `al-ground.js` | Ground and walking, one card | `R/ui/cards/GroundCard` | 🆕 |
| `al-perform.js` | The Perform panel | `R/ui/PerformPanel` (take bar, pads, buttons, moods) | 🆕 |
| `al-intake.js` | The four doors, Update the art | `R/intake/Doors` + the system file picker (SAF); "From JoyBrush" through the JB-7 board hand-off | 🆕 |
| `al-shelf.js` | The shelf row and the drawer | `R/ui/Drawer` (a `RecyclerView` row); the list is the Joy Creator folder's `.character.json` files by date | 🆕 |
| `al-icons.js` | Pictures on controls (owner-approved drawings) | Vector drawables `res/drawable/ava_pic_*.xml`, three parts each (grey lines, a solid shape, lines in the mark colour) | 🆕 |
| `al-frame.js` | The frame and its button; the empty scene's shots | `R/ui/FrameButton` (36 dp), the frame drawn in `StageView`; the texture beyond it is Studio's `F/player/CanvasFrameView` art (`studio_backdrop_graffiti.jpg`) ✅ | 🆕 on ✅ |
| `al-scene-strip.js` | The director's chair and the strip | `R/ui/SceneStrip` (a row of `RecyclerView`s sharing the width, frosted), placed by `F/tools/DrawerPlace` ✅ and pushing the frame by Studio's transcript-drawer rule ✅ | 🆕 on ✅ |
| `al-scene-room.js` | What is in hand; the path tabs | `R/ui/PathTabs` | 🆕 |
| `al-scene-ui.js` | The scene's Set up, Arrange and Motion cards | `R/ui/cards/SceneCard` | 🆕 |
| `al-export.js` | The export mark and its sheet | `res/drawable/ic_export_studio.xml` ✅ tinted to the Avatar gradient; the sheet from Studio's export sheet parts 🔧 (part D) | 🔧 |

## C3. What already exists in the app and is NOT used by the new room

`F/avatar/AvatarStudioActivity`, `PoseMatrixView`, `PuppetPreviewView`, `AvatarRig`, `AvatarRigValidator`,
`AvatarNeutralBaker`, `AvatarLibrary` (bundle folders), `AvatarParamTrack`, `AvatarItemPuppet`, `SweepTimeMapper`,
`VideoFaceSweeper`: the old room and its Studio item. Kept working, untouched (rule: keep the old behaviour selectable;
half-built is not abandoned). A later job may offer "bring an old avatar into the new room"; not in these slices.
