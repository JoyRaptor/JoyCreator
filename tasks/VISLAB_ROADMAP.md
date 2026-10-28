# VisLab roadmap — build everything that is owed (2026-10-23)

For whoever picks this up (any account, any model): this is the build order for everything still
owed in VisLab and VisLab-in-Studio, with a short spec per item. Tick a box only after the phone
test passes, add the commit hash, and keep `tasks/VISLAB_TODO.md` (the long checklist with code
places) and `tasks/STATE.md` (the app's score card) in step.

Owner's words that set the direction (10-22/23): "build everything that needs to be built, including
the special effects"; Studio is to replace After Effects (everything keyframable, proxies drive
things, adjustment layers carry effects); VisLab is a lab of composable parts, not lumped scenes;
phone-first, one-handed; save usage: cheap helpers for scoped work, the lead for taste and surgery.

## How to work (break none)

- Read first: `tasks/VISLAB_SPEC.md`, `tasks/VISLAB_TODO.md` (sections C, F, G), this file.
- Never run gradle. The owner's watcher (`watch-build.ps1`) builds on save into `build.log`
  (UTF-16). If it is not updating, say so at the top of your report and ask him to restart it.
  Without a build, type-check by hand: `typecheck.sh` (javac against the last build's classes;
  copy it from an earlier session's scratchpad or rebuild it from `tools/javacheck.sh` + the
  classpath notes in the 10-22 session).
- Phone: Note 9 only (sandbox). `tools/phone.ps1 claim vislab "<why>"`, `release vislab`. Never
  `adb shell monkey` (it unlocks his rotation); never force-stop over an open JoyBrush drawing;
  never uninstall or clear data; find buttons by name (uiautomator dump), not by guessed position.
- Log the phone's temperature every 30 s while testing and report the load per step.
- Commit only your own paths. Other lanes commit in the same main folder and may hold unpushed
  commits there: push ONLY your files (build the commit on `origin/joy-creator` with a temporary
  index if the local branch carries someone else's unpushed work).
- CRLF files stay CRLF. Never put code containing `$$`, `$(` or backticks through a shell.
- Every new button gets a hover label; ON = solid cyan; one press = one undo; keep old behaviour
  selectable; existing looks must not change.

## Status

`[~]` = built, type-checked and its JVM tests pass, phone test still owed. `[x]` only after the
Note 9 test. Every `[~]` row was compiled together on 10-24 (Studio + effects + VisLab, exit 0).

| | Item | Size | Who | State |
|---|---|---|---|---|
| R1 | Font per Words layer (G9) | S | lead | [~] font pills on Words layers; Studio typed words fixed too. Note 9 10-27: the Font row is there on Halo Lyrics' "Sung word" (Joy, Popular, Popular Italic, Designer, Trendy, Light...); choosing another font on the phone not tried |
| R2 | Split lumped scenes into elements (G6) | M | lead (taste) | [~] Stars, Aurora curtains, Mountains + look "Aurora Parts"; look book checked; Outrun / Nebula audit still owed |
| R3 | Studio visualiser on the shared transform box (G5b) | L | Sonnet + lead | [~] VisualizerTransformHost, body hold opens the drawer, spin arc below (reset for other objects). Note 9 10-27: holding the box opens the drawer, and the box shows with its handles. Double-tap not confirmed (adb's two taps may be too slow for it); moving, resizing and the spin arc not tried on a visualiser |
| R4 | VisLab finishes as Studio effects (G8d, "special effects") | L | Sonnet + lead | [x] agent phone-tested 10-27, on joy-creator 59346ae8a. 16 effects: 10 in FxVisLabDefs + Glow, Flash, Vignette, Ripple, Swirl, Warp in FxVisLabDefs2; every picker chip says what it does (FxBlurbs). Note 9: all seven new ones in the picker with their sentences and each renders on an adjustment layer in the live preview. Owner sign-off pending. The first ten were not individually re-checked on the phone |
| R5 | MIDI as a sound source (G10) | M | Sonnet + lead | [x] agent phone-tested 10-27: our test song Joy Beats (`tools/test-music/joy-beats.kar`) picked as "A song" plays in the room, and its words sing in Halo Lyrics on time ("the", "feel"). MidiFile / MidiFeatures / MidiSource / MidiSynth; run-midi 15/15. Owner sign-off pending |
| R6 | Hide knobs that do nothing in the current mode (G2b) | S | Haiku audit + lead | [~] showFor on 66 knobs; run-vislab passes |
| R7 | Sprite element: a SpriteLab sheet inside a look (G8a) | M | lead | [~] Sprite element + look "Record Hop"; run-sprite 11/11; pilot draws the built-in sheet. Note 9 10-27: Record Hop plays in the room to the demo song and to a .mod. Picking a Sprite Lab sheet, each Play mode, reopening and a Studio export not tried |
| R8 | Studio proxies drive a look's Place and picture (G7) | M | lead | [x] agent phone-tested 10-27 (Note 9), on joy-creator. Style tab, "Layers can follow an object": each layer's pill lists dummies, pictures, titles and sprites. Halo Drop's Logo followed Picture 1 to its centre in the preview and in a 720p export. Tried with a picture, not a dummy. The layer follows the object's pose even before or after its time on the timeline. A second, smaller R8 sits on `backup/proxy-bind-20261027` (4d6d990ed, not landed, kept): per-link Move / Size / Turn switches, and an offset mode where the layer stays where the look drew it until the object moves. It is a candidate "keep the old behaviour selectable" option. Owner sign-off pending |
| R9 | Layer reference: a look shows another Studio item (G8c) | L | lead | [x] agent phone-tested 10-27: picture layers get a "shows" row (its own picture, any Studio picture or title, with all its effects). Halo Drop's Logo showed Picture 1, with the look's Difference blend, in the preview and in the export. Titles not tried on the phone; sprites, clips and avatars are not offered yet. Owner sign-off pending |
| R10 | Echo trails as a Studio effect (stateful feedback) | M | lead | [~] "Echo Trails" (Echo / Light trails / Glow trails; trail length, zoom, turn, colour drift, centre) on adjustment layers. New FEEDBACK capability: its own pass, never the last; `FX_PREV` / `FX_DT`; `fx/FxHistory` keeps two frame-sized pictures per pass in export and preview, resets on a jump. Tests + glslc 35/35. [x] agent phone-tested 10-27 on joy-creator 59346ae8a: trails in the live preview while playing AND in an exported video (frame at 3.0 s vs the source). Owner sign-off pending |
| R11 | True blend preview for visualisers in Studio | M | lead | [x] agent phone-tested 10-27: a full-frame Halo Drop on Difference shows inverted colours in the preview, matching the exported frame. Found on the phone and fixed: visualisers are not lanes of `getLayers()` (they have their own rows), so the first build never composited them. They are now drawn last in the GL composite, where the export blends them: above effect layers and pictures, below captions. The Blend tab no longer says "applied when you export" for a visualiser; Joybot's help says pictures and visualizers preview their blend. Owner sign-off pending |
| R12 | Video element (G8b) | M | later | [ ] |
| R13 | Tracker modules (.mod) as a sound source, like MIDI: play the samples, exact hits per channel (owner sent two demoscene beat .mods 10-24) | M | lead | [x] agent phone-tested 10-27: the owner's posio_beats.mod (on the Note 9 only, never committed) picked as "A song" plays through the app's own player and drives Record Hop. Per-channel hits not checked separately on the phone (run-mod 20/20 covers them). `vislab/audio/ModFile`, `ModPlayer`, `ModMidi`, `ModSource`. Owner sign-off pending |
| R14 | Studio effects that hear the soundtrack: link an effect knob to the kick / bass / beat of a clip (VisLab's music links; SoundClock already analyses Studio clips) | M | lead | [x] agent phone-tested 10-27: a "♪ music" row under each slider in an effect layer's or a clip's own Effects drawer (Loudness, Bass, Kick, Snare, Hi-hats or Beat; strength 25, 50, 100, -50, -100%), one undo step per tap. Flash Amount on Kick at 100% flashes on the kicks in the preview (layer and clip) and in a 720p export (frame brightness 129 -> 219 one frame after a kick). Found on the phone and fixed: a clip's own effects showed the row but did not listen; and the export froze an effect layer's moving values at the first frame (keyframes too). `fx/FxMusic`, `vislab/studio/StudioEars`. Owner sign-off pending |
| R15 | Visualiser stacking: the preview draws a visualiser under pictures and text (its canvas sits below them), the export over them (the overlay pass); seen 10-27 with a picture over a look | S | lead | [ ] |
| — | Owed checks: R1 audit (needs a recording + his "Start now"), R2 audit (Android 12+), D2 hour-long heat test (owner deferred) | | owner | [ ] |

## Specs

**R1 Font per Words layer.** A "Font" choice on every Words layer: the app's fonts plus his
imported ones (`ui/faditor/text/FontLibrary`). Stored by name in the look (`"font": "<name>"`);
a missing font falls back to the default, once noted. `WordFeed` gets a Typeface; the renderer
keeps one feed per (text, font). Pilot: same field, CSS font name. Done when: the same look shows
two fonts side by side on the Note 9, survives reopen, and the default looks are unchanged.

**R2 Composable elements.** New elements (types.json + one shader each, so the editor builds
itself): **Aurora curtains** (curtains only, own softness), **Mountains** (ridge silhouettes,
layers of depth, a drive-by scroll speed, react to bass), **Stars** (crisp points, twinkle styles:
sparkle, pulse to the beat, shooting stars, drift). Then audit other lumped elements (Nebula's
stars, Outrun's sun / mountains / grid / sky) and give each part its own element. The old combined
elements stay and every shipped look renders exactly as before. Done when: Northern Lights can be
rebuilt from parts, with stars crisp while the curtains are soft.

**R3 Transform box.** Plan in VISLAB_TODO G5b (VisualizerTransformHost implementing the shared
`TransformOverlayView.Host`; rotate, keyed pose writes, undo per gesture, timeline selection shows
the box). Done when: drag, corner-resize, rotate and a keyed drag each work and undo in one step
on the Note 9, and a linked visualiser follows its parent.

**R4 VisLab finishes as Studio effects.** Port VisLab's finish effects into Studio's effect
library (`studiokit/.../fx/FxRegistry`, each a GLSL body with a capability): Glitch tear, VHS,
Scanlines, CRT bend, Kaleidoscope, Mirror, Zoom blur, Shake, Vignette, Wet floor, Colour split,
Flash. They then work on any clip and on adjustment layers (his After-Effects way: an empty layer
whose effects change everything under it, masked / faded / switched by animating the layer), with
keyframable parameters. Skip what Studio already has (pixelate, posterize, invert, RGB shift,
grain, gradient map) unless VisLab's is clearly better. Done when: three of them on an adjustment
layer over a clip look right in preview AND export on the Note 9.

**R5 MIDI source.** Read a .mid file (and .kar lyrics) into exact events; a sound source that
turns them into the same features the analysis gives (kick / snare / hat from the drum channel,
bass / mid / high from note ranges and velocity, beat and bar from tempo), plus timed words from a
lyric track. Played by a simple built-in synth or silently beside a song. Room: "A song" also
accepts .mid. Done when: a MIDI drum loop drives a look with every kick landing exactly.

**R6 Knobs that do nothing.** A Haiku audit lists, per element, the knobs that only act in some
modes (from the shaders); add `showFor` so they hide where they do nothing. Done when: no slider in
any element is dead in the mode it is shown in.

**R7 Sprite element.** Like the picture element, but a SpriteLab sheet that plays: its own frame
rate, a frame per beat, or held; every effect and Place apply. Done when: a sheet animates inside a
look in the room and in a Studio export.
*As built (10-24):* type `sprite` (`shaders/sprite.frag`, draw kind `sprite`). Play: Its own
speed / A frame per beat / A frame per kick / Loudness picks / Hold one frame, plus Back and forth.
The layer keeps the whole Sprite Lab sheet in the look (`"sheet"`, its picture links made full by
`vislab/room/SheetShelf`) and an animation id (`"clip"`). `vislab/room/Sprites` draws the
frames through Sprite Lab's own `SpriteSheetRenderer` (slicing, background key, per-frame
alignment) into an even grid with gutters (`SpriteAtlas`); `vislab/gl/SpriteLayers` holds the
texture and a `SpriteClock` per layer. No sheet = the built-in Joy record (8 frames). Knob panel:
Sheet pill (chooser of every Sprite Lab sheet in projects and the character library) and an
Animation pill row when the sheet has animations. Phone test owed: pick a real sheet, each Play
mode, reopen the look, a Studio export.

**R8 Proxies drive looks.** In Studio, a VisLab layer's Place (across, up, size, turn) and a
picture element's position / size / spin can follow a Studio proxy (dummy) object, like other
items' links. Done when: a dummy animated in Studio moves the logo inside a look.

**R9 Layer reference.** A look's media layer shows another Studio item (sprite, clip, text,
avatar) rendered each frame, so every VisLab effect applies to it. In the room it shows a stand-in.

**R10 Echo trails effect.** VisLab's echo (feedback trails, zoom, swirl, colour drift) as a Studio
effect: needs the previous frame, so it is its own pass with history, in preview and export.

**R11 True blend preview.** Visualisers with a blend mode preview it (today: Normal in preview,
real in export), through the GL preview compositor.

## Log (newest first)

- 2026-10-27 (evening): R8, R9, R11, R13 and R14 phone test on the Note 9, plus the room checks for R1,
  R3, R5 and R7 (rows above), then landed on joy-creator. Test project: a new Studio project on a 30 s
  testsrc2 clip with an ffmpeg drum beat at 126 bpm (`/sdcard/Movies/vlfx-beat.mp4`), a picture (a
  yellow and green disc, `/sdcard/Pictures/vl-logo.png`), one effect layer and one visualiser on Halo
  Drop. Two Draft exports (`/sdcard/Joy Creator/Untitled 2.mp4` and `Untitled 3.mp4`); frames were
  measured on the PC. Fixed on the phone (commit "Phone test fixes for R11 and R14"):
  1. Tapping FX on the main clip opens the clip's own effects. The music row showed there but did
     not listen; the preview and the export now pass it the ears.
  2. Export froze an effect layer's moving values (keyframes and music) at the first frame: the
     compiled steps kept the first frame's cards. Each frame now uploads its own values, as picture,
     object and text effects already did.
  3. Blended visualisers never reached the preview composite (see R11).
  4. A visualiser's hidden and locked state were saved only when it was attached to a clip, so a
     free visualiser came back visible (and exported) after reopening.
  5. The visualiser drawer's Hide recorded a step but the preview kept drawing it until the next
     refresh.
  Seen in passing, other lanes, not fixed: the picture Opacity drawer read 26% but the picture stayed
  solid and recorded no undo step; the export sheet's File name ("vlfx-r8-r14") was ignored and
  the file saved as "Untitled 2.mp4"; holding or double-tapping a picture did not open its drawer.
  Undo history (26 steps) survived closing and reopening the project. Phone: battery 29 -> 36 C,
  chip 35 -> 66 C at the peak during the exports; both exports took the same time (no throttling).
- 2026-10-27 (afternoon): Studio effects phone test on the Note 9, then merged to joy-creator
  (59346ae8a, fast-forward). Build: `tools/build-install.sh :app:assembleDefaultDebug` in the
  worktree `C:/Temp/vl-studio-fx` under the exclusive `jb-gradle.lock`, reusing the idle Gradle
  daemon (2 m 47 s; rebuild 9 s); `adb install -r` with the phone claimed. Test project: a new
  Studio project ("Untitled 5" on disk) on a 6 s ffmpeg testsrc2 clip (`/sdcard/Movies/vlfx-test.mp4`),
  one FX adjustment layer. Results: the seven new effects are in the picker with their hover
  sentences (read from the accessibility tree); each renders in the preview; Echo Trails' light
  trails show during playback and in the exported file (`/sdcard/Joy Creator/Untitled.mp4`, frames
  in `C:/Temp/vlfx-proof`). Found and fixed on the phone: the panel steps ranges >= 4 wide in whole
  units, so Echo's Zoom (-2..2) showed 0.3 as "0"; Zoom, Ripple Speed and Fade narrowed (Zoom now
  reads 30, re-checked); Ripple default strength 0.6 -> 1.0. Phone 32.5 -> 35.5 C battery, 36 -> 43 C
  CPU over ~20 min (the 30 s logger failed; start/end readings only). Seen in passing, not fixed
  (Library lane): a new project named "Untitled 5" is not found by the Library's search. The
  owner's main folder is diverged (local commits + other lanes' uncommitted work) and was NOT
  synced, so his watcher builds do not include this until it is reconciled.
- 2026-10-27 (later): R10 Echo Trails. `FxEffectDef.Capability.FEEDBACK`: the planner gives it a
  pass of its own and always adds a plain pass after it (the last render carries the layer's
  mask / opacity composite, which must not be fed back). `FX_PREV(uv)` reads last frame's output
  of that pass (outside the frame is empty; before any history it reads the pass input, so trails
  start from the picture), `FX_DT` is seconds since then. `app/.../fx/FxHistory` keeps two
  frame-sized pictures per feedback pass, used identically by `AdjustmentLayerGlEffect` (export)
  and `FxPreviewTextureView` (preview, keyed by layer position + pass). Valid only across
  consecutive moments (gap <= 250 ms); a redraw of the same moment shows one ordinary step over
  the current picture without advancing history, so paused edits stay visible. Rates per second,
  Trail length per 1/30 s raised to dt (echo.frag's rule) so 30 fps export = 60 fps preview.
  Known limit: export renders each clip's chain separately, so trails restart at a clip boundary.
  Checks: FxCompilerTest (planning, history declared only in the feedback pass, one FEEDBACK
  effect), every real layer program (FxGlSource.fragment, composite + masks) compiled with glslc
  as GLSL ES 3.1: 35/35. Type-checked against the app. Not yet in an APK.
- 2026-10-27: R4 second half on branch `vislab/studio-fx-20261027` (isolated worktree
  `C:/Temp/vl-studio-fx`, per the 10-26 git hygiene rule): Glow (one-pass bloom: a 24-point
  sunflower disc of the bright parts, screened on), Flash (a colour wash, optional strobe), Vignette
  (size / softness / roundness / colour), Ripple, Swirl, Warp. Effect picker chips get a hover
  sentence each. Checks: FxCompilerTest green incl. registry self-check and a sentence for every
  effect; every effect's GLSL compiled with the NDK's glslc as strict ES 3.1 (33/33). Not built
  into an APK or phone-tested yet. Our own test music: `tools/test-music/joy-beats.mjs` writes
  `joy-beats.kar` (126 bpm, 32 bars, broken beat + gallop hats + bass + stabs + karaoke words,
  written fresh in the style of the owner's demoscene .mod beats) for the MIDI phone test.
- 2026-10-24: R7 built (lead). Helpers for R3/R4/R5 were cut off by the usage limit; their partial
  work was reviewed and finished by the lead (tests added: run-midi.sh, FxCompilerTest
  visLabFinishes). R3: spin arc placement reset when a non-visualiser is selected. All R1-R7
  compiled together; phone test of R1-R7 next (needs a build that holds them).
- 2026-10-23: roadmap written; R1-R6 started.
