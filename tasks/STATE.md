# STATE — what Joy Creator actually has, and how proven it is

**Purpose:** stop the rediscovery tax. This is the one file that says what exists.
Every agent reads it first. Every promotional claim comes from it. The help doc and
Joybot's knowledge are generated from it.

**Joy Brush owner check, 2026-10-01:** JoyRaptor reports drawing, colour picking, erasing,
closing and reopening work on the Note. Treat these ordinary workflows as device-verified.
The newly wired Recent drawings, preservation-before-Open, backup fallback and PNG export
are compile/test-verified separately; their phone checks remain owed. Details:
`tasks/joybrush/INTEGRATION_20260930.md`.

**Last full pass:** 2026-09-09 (Claude/Opus, launch-planning session)
**Last touched:** 2026-09-16 evening — the puppet UI finish sweep (`tasks/PUPPET_FINISH_LEDGER.md`):
nine dead knobs found and resolved, the tape made draggable, easing fitted from the recording, and
three defects JoyRaptor reported on device. Also `tools/phone.sh deploy` / `doctor`, which exist
because a stale watcher or a dropped transport had been leaving OLD builds on the phone.
**Scale:** 710 Java files, ~349,000 lines, schema v13

---

## Joy Brush Select lines — 2026-10-24

**BUILT on `codex/select-lines`, awaiting Lead integration and UI/Note 9 acceptance.**
Supporting source: `cc1061634`; separate `LEAD PATCH:` commits are listed in
`joybrush/reviews/SELECT_LINES_IMPLEMENTATION_20261024.md`. This is not a claim that the installed app has these features.

| Capability | State and evidence |
|---|---|
| Session Copy/Paste across drawings, fresh line/group IDs, frozen brushes, destination cel and automatic centring | Built; clipboard and host regression tests. |
| Lock/Unlock, Group/Ungroup, Invert and Pick locked lines too | Built; metadata codec round-trip and exact layer-scope tests. |
| Relative HSB with live preview and one edit on release | Built; preview/history and multiple-colour tests. |
| Selection retained/refitted through history; Delete undo restores it | Built; history, refused-edit, drawing-epoch and delayed-snapshot tests. |
| Select settings, scoped layer actions and Flatten Lower | Built; core policy tests. Dim preference is wired; the outside veil belongs to UI C3. |
| Eyedropper recolour and 20% nudge-before ghost | Built; gesture/ghost tests; phone screenshots owed. |

Latest app selection check: **122 tests, zero failures/errors**, plus Kotlin compile, before the final
snapshot-publication/refused-edit review fixes. Final rerun and standalone core/androidkit suites remain owed:
the shared build wrapper could not admit the standalone run while free RAM stayed below its 2.2 GB threshold;
the waiting request was cancelled before Gradle started. No build is left queued by this lane.
No phone verification or main integration claimed.

**Still open:** A9 Slice needs zero-width cut admission and an atomic split transaction; A10 badge/switch/toasts
wait for a settled L3 editable-lines default; ring Bend waits for vector L4. Phase B pixel selections require
Phase A integration, a design note and Lead approval before code. This is not waiting solely on JoyRaptor.

## The proof levels — this is the point of the document

| | Means | Consequence |
|---|---|---|
| 🟢 **LIVE** | JoyRaptor has used it on a phone and it worked | Safe to ship. Safe to promote. Goes in the help doc. |
| 🟡 **BUILT** | Code complete, compiles, never driven on a device | **This is where 1-star reviews come from.** Do not promote. Consider hiding at launch. |
| 🔵 **SPECCED** | Written down, not built | Roadmap fuel |
| ⚪ **IDEA** | Mentioned somewhere, never specced | INBOX.md fuel |
| 🔴 **BROKEN** | Known defect, diagnosed | Must be triaged before launch |
| 🟠 **ORPHAN** | Started, stopped for lack of hours, still wanted | **Recover it. Do not archive it.** |

**The rule that keeps this true: an entry moves to 🟢 when it is proved on a phone, not
when the code lands.** That single discipline is what this file is for.

**"Is it finished" and "is it proven" are different questions.** Do not conflate them.
JoyRaptor, 2026-09-09: *"almost anything partially built is likely part of the finished vision
that I haven't had time to get around to yet."* He works in fragments around childcare, so
work stops for lack of hours, not lack of intent. A 🟠 entry is an orphan waiting to be
finished — never dead weight to be filed away. A whole feature (masking) was already lost
this way once. Push back on an idea only with a substantive reason it is wrong, never
because it is incomplete.

**Provenance:** entries marked ✔ were verified against the code in this session. Entries
marked ~ are inherited from LEDGER.md / road_map.md claims and are only as good as those.
Anything unmarked is a reading that should be challenged.

---

## 1. RECORDING (the FadCam inheritance)

| Feature | Level | Notes |
|---|---|---|
| Background video recording (screen off) | 🟢 ~ | The original FadCam core |
| Screen recording (FadRec) | 🟢 ~ | fadrec/ — own encoding pipeline |
| **Webcam overlay while screen recording** | 🟢 ~ | ✔ FloatingWebcamService — **Joy Creator exclusive, FadCam has no such thing.** Headline feature. |
| Auto-hiding recorder controls | 🟢 ~ | JoyRaptor's work. Less visually invasive than FadCam. |
| Dual camera (front + back) | 🟡 ✔ | dualcam/ full package. Device state unknown. |
| Photo capture / screenshot | 🟢 ~ | PhotoCaptureActivity, ScreenShotCaptureActivity |
| Remote camera control over LAN | 🟡 ✔ | streaming/RemoteStreamManager, LiveM3U8Server. **Real, unpromoted, untested by JoyRaptor.** |
| Live streaming / cloud upload | 🟡 ✔ | CloudStreamUploader, CloudAuthManager — **being removed from the Play build** |
| Watermark | 🟢 ~ | watermark/WatermarkManager |
| Live audio visualizer during record | 🟡 ✔ | visualizer/LiveVisualizer |
| Forensics gallery | 🟡 ✔ | forensics/ — full package, purpose and state unclear, needs a decision |
| Motion detection | 🟡 ✔ | motion/ package |
| Clock widget | 🟢 ~ | widgets/ |
| MiniApps (QR scanner etc.) | 🟡 ~ | Recorder gadgets. Different product surface — do not overload. |

### 🚨 Recording features that must leave the Play build

| Feature | Why |
|---|---|
| 30 disguise icons (Calculator, Notes, Weather, `$ ~/r00t`…) | ✔ Stalkerware signature on a screen recorder |
| Accessibility service used for screenshots | ✔ Non-accessibility use of the Accessibility API = policy violation |
| MANAGE_EXTERNAL_STORAGE | ✔ Requires justification; usually refused for editors |
| Privacy black screen | Per LAUNCH_STRATEGY.md section 3 |
| Cloud / streaming | Per LAUNCH_STRATEGY.md section 5 |

All of these **stay in the website / F-Droid build.** Decision already made and documented.

---

## 2. THE EDITOR — core timeline

| Feature | Level | Notes |
|---|---|---|
| Multi-clip timeline, split/trim/reorder | 🟢 ~ | The spine |
| Multi-track layers | 🟢 ~ | road_map "Phase 5 KEYSTONE" — reported complete |
| Keyframes + easing | 🟢 ~ | keyframe/, EasePickerPopover |
| Masks | 🟢 ~ | Schema v13. **The engine shipped before the UI did — that gap is why LEDGER.md exists.** |
| Chroma key / green screen | 🟢 ~ | LEDGER 3a-KEY: preview and export both proved on the Note 9, 2026-08-05 |
| Luma key | 🔵 ~ | LEDGER: "spec complete, the work is not" |
| GL transitions (35+) | 🟢 ~ | gltransitions/ |
| **External .glsl transitions from a dropped-in folder** | 🟢 ✔ | GlExternalTransitions.scanAndRegister — **this is already a working pack loader.** |
| Speed / zoom / crop / flip | 🟢 ~ | |
| Text overlays + animation | 🟢 ~ | SPEC_TEXT_ANIMATION; odometer preset proved character-by-character 2026-07-31 |
| Adjustment layers / FX | 🟡 ✔ | Shell is 🟢 — add/select/own-lane/fx+trash badges device-proved on the sandbox phone 2026-09-10. **Whether an effect actually applies to the layers beneath is still unproved.** SPEC_ADJUSTMENT_LAYERS_FX.md |
| Timeline lane allocation (no two objects stacked) | 🟢 ✔ | SPEC W — three adjustment layers took three lanes, device-proved 2026-09-10. Same rule now guards text/image/sprite/PiP adds. |
| Mesh warp / Bend (IMAGES) | 🟢 | **Complete and device-verified 2026-09-10.** SPEC A through T. |
| Bend — SPRITES | 🟢 | SPEC Z slice 1. Rasterise-then-warp on a Canvas, one method shared by preview and export. |
| Bend — TEXT | 🟡 | 2026-09-16. Through the SAME SpriteMeshDraw, now generalised off SpriteOverlayItem, called by CornerPinTextView and CompositeExportOverlay. The pin sits INSIDE the bend on both. A bent box is a raster (the pin stays vector-sharp; a mesh cannot), and the in-canvas editor suppresses it. **Built, never bent by hand.** |
| Bend — VIDEO (PiP + spine) | 🔵 | SPEC ZD. The model has shipped since SPEC ZB and nothing reads it. 2026-09-16 established the route: bend the GEOMETRY, not the sampler — the preview binds GL_TEXTURE_EXTERNAL_OES and the export a GL_TEXTURE_2D, so a shared mesh SHADER cannot serve both, but a displaced vertex grid needs no shader change on either. Recorded in _HELD_BY_OPUS_20260913. |
| Corner-pinned TEXT, with effects | 🟡 | 2026-09-16: a pinned box with ANY effect on it exported UNPINNED while the preview showed it pinned — TextFxGlEffect rebuilt a per-frame item and the pin was never among the copied properties. Now concat-ed from the same cornerPinMatrix the preview and the plain export use. Not yet re-exported on a phone. |
| Corner pin on SPRITES | 🟡 ✔ | SPEC Z slice 1, 2026-09-13. Model, persistence, undo, transform surface, authoring and BOTH renderers — one shared matrix method. Compile- and harness-verified; **no one has dragged a sprite corner on a phone yet.** |
| Mesh bend on sprites / PiP / text / spine | 🔵 | SPEC Z. Sprites hold a MeshWarpSpec that nothing draws; PiP and spine need the Clip model work; text needs a pinned view. |
| Sprites on the transform surface | 🟡 ✔ | SPEC Z slice 1 step 2, 2026-09-13. They used the legacy handle overlay until now. |
| Transform (affine handles, rotation dial, pivot) | 🟢 | **Complete 2026-09-10.** Shipped alongside Bend. |
| Undo (one press = one step) | 🟢 ~ | JoyRaptor's ruling. Batch operations collapse to one undo. |
| Undo history after closing and reopening a project | 🟡 ✔ | 2026-10-22 to 10-24, agent-checked on the Note 9, not yet used by JoyRaptor. Phone-proved: steps made before an export now survive leaving (2301d5cfa: 41 recorded, 41 back, was 38), two quick edits no longer drag the next edit into one press (2301d5cfa), and undoing everything then leaving clears the saved history (58aaacd03). **Phone test owed** for b2a85dff7 (type-checked and harness-tested only; the C: drive was full on 10-24): every quick edit keeps its own step on a typical project, and the history is saved with every project save, so a crash leaves it matching the project. Test project on the Note 9: "Redo test clip". |
| Export (out-of-process) | 🟢 ~ | Own process so an editor crash cannot kill an export. Proved 2026-07-30. |
| Single-frame export (JPG/PNG) | 🟡 ~ | todo.md is this spec, marked partly done |

---

## 3. AUDIO

| Feature | Level | Notes |
|---|---|---|
| Waveforms (tile cache + mip pyramid) | 🟢 ~ | Device-proven on SM-N986U |
| Beat and onset detection | 🟢 ✔ | waveform/BeatDetector, OnsetDetector — pure DSP, offline, **already wired to snapping** |
| Silence detection | 🟢 ✔ | SilenceDetector — pure DSP |
| Loudness normalisation (EBU R128) | 🟢 ~ | |
| fix_audio chain (highpass → denoise → compress → loudnorm) | 🟢 ✔ | Works. **Not exposed as a button — only reachable by asking the chatbot.** |
| Voiceover recording | 🟢 ~ | audio/VoiceoverRecorder |
| Audio ducking | 🟡 | **Rebuilt** (2026-10-15 code read): lane menu "Duck under…" → Ducker + DuckApplier write ordinary volume points that export reads. Joybot's `set_ducking` mirrors it (needs the voice clip's transcript). The old set_clip_duck stays removed. Not seen on a phone by this lane. |
| Visualizer studio | 🟢 | Superseded by VisLab (section 5.5): music visuals in their own room, inside Studio and floating over other apps. |

---

## 4. TRANSCRIPTION — fully offline, no key, no upload

| Feature | Level | Notes |
|---|---|---|
| Vosk small (~40MB) — best timing | 🟢 ~ | ✔ engine present |
| Vosk large (~128MB) | 🟢 ~ | |
| whisper.cpp base.en (~57MB) — best wording | 🟢 ~ | ✔ native build, arm64 only |
| LCS merge of Vosk timing + Whisper words | 🟢 ✔ | TranscriptSynthesizer — genuinely novel |
| Filler-word detection and strike | 🟢 ✔ | |
| Word-level retiming and scrubbing | 🟢 ~ | WordScrubView |
| Captions with 5 animated styles | 🟢 ~ | pop / zoom / bounce / boxed / hot |

**This is the strongest competitive asset in the app and it needs no internet, no key, and
no money.** Nothing else on Android does the two-engine merge.

---

## 5. ANIMATION — sprites, avatars, puppets

| Feature | Level | Notes |
|---|---|---|
| Sprite sheets + grid detection | 🟢 ~ | sprite/; road_map says S1–S7 complete |
| Sprite sidecar .sprite.json | 🟢 ✔ | Export and import both exist in SpriteSheetEditorActivity. The importer dropped every preset and cell name until 2026-09-10; fixed then, **device-proved 2026-09-13** — the Export panel now prints the JSON on screen with a Copy button, so what the assistant will read is visible before it is written. |
| Saved animations — the ENGINE | 🟢 ✔ | SpriteFrameResolver expands a preset key at the playhead and runs it, wrapping per loop/pingpong/once-then-hold **until the next key**, honouring weights, with `endBehavior` overriding after the last key. One evaluator for preview, export and tape. |
| Saved animations — picking one | 🟢 ✔ | **Two screens list `sheet.getPresets()` now.** The drawer shows them as chips alongside the still cells (animations first, one chip design, a mode dot the only difference); the Lab's Clips section is a shelf of the same chip, and picking one lights its frames on the sheet **in its own order** before you decide to load it. Device-proved 2026-09-13. |
| Cell names | 🟢 ✔ | The grid letters each cell in four corners — index, name, viseme, order badge — and the film and drawer chips carry the name. **Two stores held a cell's name and nothing reconciled them**, so a sheet with named cells exported "0 named" to the assistant; `cellNames` is now the one truth, reading falls back to `Cell.name`, writing updates both, loading migrates. Device-proved 2026-09-13. `Cell.tags[]` is still written by nothing. |
| Live keyframing while playing | 🟢 ✔ | `onCellChipTapped` keys **at the playhead** by design, so tapping during playback records a performance. Position/scale/rotation arm the same way (`faditor_kf_hint_armed`). Sprites carry a `KeyframeSet` *and* a `FrameTrack` — two independent tracks, which is what makes the two-pass workflow work. **JoyRaptor's favourite thing in the app. Do not add an arming step.** |
| Bend / squash-and-stretch on sprites | 🔵 ✔ | Not built. `CornerPinTransformHost` is the only host with a mesh path and it is image-only; `SpriteOverlayItem` has no transform host at all. |
| Dope sheet | 🟢 ~ | |
| Avatar rigs (biped, head yaw/pitch, limbs) | 🟡 ~ | road_map claims the full loop is done; **JoyRaptor has not driven it** |
| MediaPipe face tracking | 🟡 ✔ | face_landmarker.task in assets, MediaPipeTrackingSource |
| Visemes from audio (spectral, 6-class) | 🟡 ✔ | SpectralVisemeAnalyzer, AudioLevelViseme |
| IK solver, dangle physics, motion smoothing | 🟡 ✔ | FabrikSolver, DangleSim, OneEuroFilter |
| Bake rig to keyframes | 🟢 ~ | LEDGER: device-verified |
| Point-at-video | 🟢 ~ | |
| Puppet architecture | 🟡 ✔ | SPEC_20260904_PUPPET_ARCHITECTURE — engine complete through stage 12. 15 harness suites, ~490 assertions. |
| **Puppeteering — a PNG bends** | 🟢 | Trace → triangulate → weights → MLS solve. JoyRaptor confirmed a rigged dinosaur bending on 2026-09-15, including limbs drawn as detached islands and the overlap halo fixed. Mesh smoothness confirmed 2026-09-16. |
| **Mesh quality — no more shattered glass** | 🟢 | 2026-09-16. Measured 81–91% of triangles under 20° (worst 0.1°), and it got WORSE as Mesh detail went up. Delaunay edge flipping + long boundary edges split + Lloyd relaxation took it to 10–16%, worst 7.3°, and reversed the trend so more detail now means fewer slivers. JoyRaptor: *"mesh much smoother!"* **Heat/biharmonic weights would NOT have fixed this** — the faceting was tessellation, not weighting; that reasoning is recorded in PuppetWeights. |
| **Enclosed holes** | 🟡 | 2026-09-16. A gap enclosed by the artwork (a hand on a hip) was filled with mesh, so the weights measured straight across it — 0.718 through against 1.02 around. Now traced, bridged and excluded; edge expansion shrinks holes instead of growing them. Harness-proved, not yet seen on a phone. |
| **Per-triangle depth (3/4 stance)** | 🟡 | 2026-09-16. Each pin carries a depth and the weights blend them into a field, so a shoulder can be behind the body with the hand in front and the handover lands ALONG the forearm. One sorted draw call; bending never re-sorts. Slider shipped, never turned on a phone. |
| Puppet pins, bones, IK, dangle | 🟢 ~ | Place, bone, drag-a-limb, simulated hair. Rigging and posing confirmed on a phone 2026-09-16. **DANGLE is the one part still unwitnessed** — the chains bake and the harness proves it, but nobody has watched a tail swing. Dangle chains BAKE to keyframes on rebuild as of 2026-09-16 — before that the type, its four sliders and DangleSim all existed and nothing ever called them. **Bones became SELECTABLE 2026-09-16** (tap a shaft), which is what let the bone scope exist at all: parent, length, rest angle, stretchy, flip elbow, joint limits. |
| **Puppet — the nine dead knobs** | 🟡 | 2026-09-16 audit: every control traced to the code that reads it. Show·Mesh, the reach eye, per-bone bend sign, pin mute, per-pin weight and Snap-to-keys were all authored, saved and read by NOTHING; each is now wired. Per-pin `scale` and the Free-pin rotate arc could not be expressed by the engine (`handleComponents()` is 2) and were REMOVED, field and all. Full list and reasoning: `tasks/PUPPET_FINISH_LEDGER.md`. |
| **Puppet KEYFRAMES and live takes** | 🟢 | SPEC_20260915_PUPPET_UI. Per-pin keys, record-on-touch, thinning, anchor-in/blend-out, and a tape that draws performances as long diamonds. **PROVED ON A PHONE 2026-09-16** — JoyRaptor rigged a character and reported arming, recording and play all working, with the keys playing the motion back afterwards. That is the whole performance loop, and it was the largest 🟡 in the animation wing. 2026-09-16: arming record now ROLLS THE TRANSPORT on the first real movement and stops it on release — JoyRaptor reported *"recording mode doesn’t work when I arm it and then I move something, playhead doesn’t move"*, and it did not: recording required playback to be running ALREADY, so arming and touching a pin was a no-op. |
| **Puppet tape — slide / stretch / retime** | 🟡 | 2026-09-16. `MeshPoseTrack.shiftRange / scaleRange / moveKey`, 26 assertions. A time edit NEVER destroys a key: each clamps at the nearest key outside what is moving and returns the delta actually applied. The hit-test is disjoint from the property diamonds by construction (puppet marks draw ABOVE the row midline, diamonds below), which is what made a fourth claimant on the timeline safe. **Never dragged on a phone.** |
| **Puppet easing — fitted from the recording** | 🟡 | 2026-09-16. `MeshEasingFit` tries every curve the app can express against the samples thinning is about to discard, and writes the one that fits. Crucially it is allowed to conclude LINEAR — a fitter that upgrades everything to a flourish would pass every other test and still be useless. 10 assertions. |
| Puppet helper strip (on-preview) | 🟡 | Four controls so mesh editing never needs the drawer: type swatch (drag OUT to place, drag a pin IN to delete), pose/place, key/arm/jump, depth scrub. Never touched by a human. |
| Puppet UI — badge, drawer tab, loupe, shapes | 🟡 | On the phone; lightly used. The loupe is now shared with the transform tool, whose own magnifier is therefore **unverified since the migration** (the code path is unchanged by inspection — it passes `avoid = null` and takes the identical branch — but nobody has opened that tool). |
| **The marionette badge — pins vs transform** | 🟡 | 2026-09-16. Tapping him used to GREY the pins, which left a rigged picture as the one kind you could not move: JoyRaptor, *"it kind of behaves like it’s not selected."* He now toggles between the pins and the ordinary transform box, and a hold brings the drawer. `rig.locked` was repurposed to mean "put away" and stays persisted — pins should not clutter a picture you are not animating. |
| **Puppet — Start over** | 🟡 | 2026-09-16, at the bottom of the Character scope. Names what it will destroy, says the picture is untouched, and says one undo brings it back — which is true, because the rig and the pose track are snapshotted together. |
| Puppet pins on a ROTATED picture | 🟡 | 2026-09-16. The box being measured was the axis-aligned BOUNDS of a rotated view, so pins sat off the artwork AND scattered during a rotate as those bounds swept. One map, one inverse. Not re-tested on a phone. |

### SpriteLab — the desktop companion (`tools/spritelab/SpriteLab.html`)

**A pilot, not a tier.** JoyRaptor's ruling, 2026-09-11: *everything* SpriteLab does is
destined to land in Joy Creator itself, on phones and tablets. He works in fragments around
childcare — often one-handed, often outside the house with only a phone — so "wait until you
are at a computer" is the failure this app exists to remove. The web tool's durable purpose
is interop with desktop software (Photoshop round-trips) and being a fast place to try an
interaction before paying Android's build cycle. **No capability may be desktop-only.**
Consequence: `SpriteSheet` must grow an additive per-cell transform, so alignment becomes
data rather than baked pixels. See INBOX 2026-09-11.

A single-file, zero-dependency web tool for repairing an AI-rendered
sheet before it ever reaches the phone: slice, reorder by tapping, rearrange cells, align
each frame, name clips, bake a clean sheet and write a `.sprite.json` the phone reads
field-for-field. Its cell geometry is a deliberate port of
`SpriteSheetRenderer.cellRectSource()`, so anything it can express the phone slices
identically. 🟢 — JoyRaptor is using it to animate the Joybot mascot, 2026-09-10.

Specs: SPEC_20260910_SPRITELAB_UI / _MODEL / _SEMANTIC_CELLS.

### SpriteLab ON THE PHONE — `SpriteSheetEditorActivity` (2026-09-13)

The pilot has landed. `tools/spritelab/SpriteLabMobile.html` is the approved design and the
Android screen is built to it, not merely inspired by it: **the icons are the same path data,
generated out of the HTML by `tools/spritelab/genicons.py` into `SpriteIcons.java`.** Edit the
mockup, re-run the script, and the phone follows — which is the only way two surfaces are still
the same a month later.

What is device-proved on the Note 9, 2026-09-13:

| | |
|---|---|
| Top bar | back · name · undo · redo · four-section segmented nav (amber grid, cyan align, violet clips, green out) · save, pink while dirty |
| Grid | cyan outline for a cell the roll uses, pink for the one showing now, order badge top-centre (`4,5` when a cell is used twice), index, name, viseme |
| Alignment | four scrubbable number pills — **drag to change, tap to type** — auto-centre, plant feet, reset, reset all |
| Onion | a toggle, a past pill and a future pill you SLIDE for count and TAP for colour, a strength number, and one swatch for the ground behind the art (tap cycles eight, hold lists them) |
| Sequence | tap cells to build the roll, holds, save as a named animation |
| Transport | play/pause · step · loop-pingpong-once segment · reverse · fps — and **play plays the ROLL**, holds honoured, ping-pong turning at the ends, once stopping on the last frame |
| Clips | a shelf of the one chip design; picking one lights its frames on the sheet in order |
| Export | writes `.sprite.json`, and SHOWS it, with a Copy button |
| Undo | snapshot-based, one press one step, a whole drag folded into one |

What is **not** on the phone yet, and is therefore still desktop-only in violation of the rule
above: **nothing, as of 2026-09-13.** Bake-to-a-new-sheet (content fit or keep, PNG or JPG),
multi-sheet merge, numbered frame export and drag-to-reorder on both the film strip and the
clips shelf all landed and were device-proved that day. `SpriteBaker` draws every pixel through
`SpriteSheetRenderer.drawCell`, the same single blit the preview uses, so a bake cannot disagree
with what was on screen; sources whose cells are a different shape letterbox rather than squash.
A bake never touches the original — it adds a new sheet beside it.

Sheet management landed the same day: Export carries a Sheets panel — open, rename, remove —
and the remove guard reads BOTH stores of sheet ids, timeline sprites and avatar-rig parts,
because those never overlap and counting only the first armed Remove on precisely the sheets a
puppet is built from.

**Arranging the sheet LANDED 2026-09-13.** JoyRaptor ruled that a drawing's name and alignment
travel with it, so `SpriteSheet` grew `cellOrder` (display -> source) and every per-cell lookup
maps through it; the map is applied in `cellRectBitmap`, the one place a slot becomes art, so
every surface reorders together. Slice carries one segment — drag [ Pan | Pivot | Swap |
Ripple ] — because four modes that all change what a drag does must be mutually exclusive, and
as four separate chips two could be armed at once with reorder silently winning.

> ⚠️ **Avatar Studio is the largest 🟡 block in the app.** Substantial, sophisticated, and
> never exercised by a human. Recommendation: **present in the launch build, not reachable
> from the UI.** Ship what has been driven.

---

## 5.5 VISLAB — the music visualiser lab (2026-10-17 to 10-24)

GPU visuals that react to music: a room to build looks in, the same looks inside Studio as video
layers, and a floating visualiser over other apps. Lead docs: `tasks/VISLAB_SPEC.md`,
`tasks/VISLAB_TODO.md` (checklist with code places), `tasks/VISLAB_ROADMAP.md` (what is next).
"Agent phone-tested" = driven on the Note 9 by an agent with screenshots; 🟢 = JoyRaptor used it.
"Built, phone test owed" = compiled and its JVM tests pass, not yet seen on a phone.

| Feature | Level | Notes |
|---|---|---|
| VisLab room: 41 looks, play to the demo song / mic / a song file | 🟢 ✔ | `vislab/room/VisLabActivity`; GLES 3 engine `vislab/gl/VisRenderer`, 47 shaders shared with the web pilot |
| Live sound analysis (bass..air, kick / snare / hat, beat and bar, spectrum, history) | 🟢 ✔ | `vislab/audio/FeatureExtractor`; frame-for-frame parity test with the pilot (`tools/jvm-harness/run-vislab.sh`) |
| The Lab: build a look from nothing, 26 elements, stack layers, every knob music-linkable (the ♪) | 🟢 ✔ | `room/KnobPanel`; looks saved as hand-editable `Supplies/Looks/<Name>.vis.json` in his folder |
| Parts shelf: save a layer as a part, reuse it in any look | 🟡 ✔ | `.vispart.json`; agent phone-tested 10-18 |
| Per-layer Opacity, Reactivity, Softness, Place (made in its own space), colours | 🟢 ✔ | |
| Words as art: 9 paths x 6 behaviours x 6 letter styles; the song's words or your own typed words | 🟢 ✔ | `words/WordFeed` + `words.frag`; 48 words at once (10-23), Look ahead / behind, Shift along, Fade in/out |
| Sliders: ghost dot where it started, soft snap to start / 0 / default / 45-90° | 🟡 ✔ | 10-23, agent phone-tested (`room/SnapSlider`, `GhostSeekBar`) |
| Knobs that only act on the music: ♪ badge, dim while silent | 🟡 ✔ | 66 flagged in `types.json` (`"music": true`); agent phone-tested 10-23 |
| Place Spin: a layer keeps turning, speeds up smoothly with the music | 🟡 ✔ | 10-23, agent phone-tested |
| Heat guard (halves resolution when the phone runs hot) | 🟡 ✔ | `view/HeatGuard`; seen working 10-18; hour-long heat test still owed |
| Floating visualiser over other apps: box or full screen, any look, hears a recording / the phone's sound / the mic | 🟢 ✔ | `vislab/overlay/VisOverlayService`; recorder menu's Visualiser switch |
| VisLab looks inside Studio: the visualiser's Style tab, look row + keyframe for which look shows when | 🟢 ✔ | `vislab/studio/VisLabStyleTab`, `StudioVis` (own GL thread), preview = export |
| Keyframable React / Fade / Bright / Colour / Size / Speed on a Studio visualiser | 🟡 ✔ | `model/VisLabKeys`; agent phone-tested 10-22 |
| Lyrics looks in Studio show the clip's own transcript | 🟡 ✔ | `vislab/studio/StudioWords`; agent phone-tested 10-22 (preview + export) |
| Blend mode on a Studio visualiser (Difference, Screen, Multiply...) | 🟡 ✔ | Real in export (`export/VisualizerBlendGlEffect`), Normal in preview; agent phone-tested 10-23 |
| Web look book (desktop pilot of every look and the editor) | 🟢 ✔ | `tools/vislab-web`; published as a private Claude artifact (v5) |
| Joybot can pick, edit and save looks | 🟡 ✔ | Joybot lane (`ai/actions/VisLab*`) |
| Font per Words layer: each Words layer in any app font or his imported ones | 🟡 | Built, phone test owed (R1). `words/VisFonts`; one glyph sheet per text + font |
| Parts instead of lumped scenes: Stars (crisp, twinkle / pulse to the beat / sparkle on hi-hats, shooting stars), Aurora curtains, Mountains (ranges you drive past, snow caps, bass lift) | 🟡 | Built, phone test owed (R2); look "Aurora Parts" = Northern Lights rebuilt from parts; checked in the look book |
| Knobs hide when the current mode makes them do nothing | 🟡 | Built, phone test owed (R6): `showFor` on 66 more knobs |
| Sprite element: a Sprite Lab sheet playing inside a look, its own speed / a frame per beat / per kick / the loudness picking the frame / one held, back and forth, one of the sheet's animations | 🟡 | Built, phone test owed (R7). `gl/SpriteLayers`, `room/Sprites`, `room/SheetShelf`; frames drawn by Sprite Lab's own renderer, so alignment matches; look "Record Hop" |
| MIDI as a sound source: a .mid file drives a look with exact kicks, snares, hats, notes and tempo; .kar lyrics become the song's words; a built-in synth plays it | 🟡 | Built, phone test owed (R5). `vislab/audio/Midi*`; `tools/jvm-harness/run-midi.sh` |
| VisLab finishes as Studio effects on any clip or adjustment layer: Lens split, Scanlines, Zoom blur, Glitch tear, VHS, CRT bend, Kaleidoscope, Mirror, Shake, Wet floor (keyframable) | 🟡 | Built, phone test owed (R4). `studiokit/.../fx/FxVisLabDefs` |
| Studio visualiser on the shared transform box: move, free resize, spin arc, snapping, keyed writes, hold or double-tap opens its drawer | 🟡 | Built, phone test owed (R3). `transform/VisualizerTransformHost` |
| Element packs from other people (plugins) | 🔵 | Designed in VISLAB_TODO section F |
| Proxies driving looks, layer reference, echo trails as a Studio effect, true blend preview, video element | 🔵 | `tasks/VISLAB_ROADMAP.md` R8-R12 |

## 6. AI — the agent

| Feature | Level | Notes |
|---|---|---|
| Chat assistant inside the editor | 🟢 ✔ | ChatAssistantActivity (1,844 lines) |
| **55 tools that mutate a real timeline** | 🟢 ✔ | Counted in executeTool(). No mobile competitor has this. |
| EditScript contract (23 op types) | 🟢 ✔ | Validate-all-then-apply, one undo step, unknown ops rejected |
| Proposal cards (confirm before applying) | 🟢 ~ | narrative / b-roll / avatar rig |
| AI-generated slides (GSAP, deterministic) | 🟢 ~ | Device-proven 2026-07-16, 87 frames to MP4 |
| Copy-prompt then paste-back for slides | 🟢 ~ | **The precedent for share-in.** Already works. |
| Vision (send a frame to the model) | 🟢 ~ | |
| B-roll tagging and suggestion | 🟢 / 🔴 ~ | Works on video-track projects; **fails on audio-track projects** — see section 9 |
| Background AI jobs that survive app close | 🟢 ~ | AIJobService — the pattern video generation will reuse |
| Headless apply over adb | 🟢 ~ | ApplyEditsActivity — dev tool |
| Image generation | 🔵 | Endpoint verified live: POST openrouter.ai/api/v1/images |
| Video generation | 🔵 | First post-launch feature |
| Share-in from other apps | 🟢 | Built 2026-10-13, Note 9-proven: "Share to Joy Creator" saves beside the open project (else Assets, else the top) and "Add to" drops it on the timeline. |
| Multi-provider support | 🟡 ✔ | Built (Joybot lane, 10-15/17): several AI accounts (OpenRouter, OpenAI, any compatible address), switch in the model picker; a no-credit account says so plainly. OpenAI itself untested: the owner's account had no API credit. |
| Streaming replies | 🟡 ✔ | Built: replies stream in, with a 75 s "went quiet" cut-off. Agent-checked on the Note 9 with free models. |
| Joybot sprite and states | ⚪ | INBOX.md — needs its own session |
| **Joybot as a harness** (one list of actions, native tool calls, tool routing) | 🟡 ✔ | Joybot lane 10-15→10-24: typed actions for Studio, the app (rooms, Library, Trash, new project from a recording), VisLab (looks, knobs, layers, brand, save/rename/revert/delete, floating visualiser). One request = one undo. Agent-checked on the Note 9 with free models; not yet used by JoyRaptor. Handoff: `tasks/joybot/JOYBOT_HANDOFF_20261015.md`. |
| Joybot audit fixes (Codex audit 10-18) | 🟡 ✔ | All 12 fixed + 4 more found on the phone (`tasks/audits/2026-10-18/joybot_fixes.md`). Note 9 agent checks 5/6 green; the VisLab one is folded into the VisLab session's 10-24 test. Locked items stay locked unless the person says "unlock" or "yes"; delete/revert ask first. |
| Joybot sees the finished video (`look`) | 🟡 ✔ | 95c7cd868 (side session): titles, layers, grade and effects at any moment, as exported. Agent-checked once on the Note 9. |
| Joybot draws in JoyBrush | 🟡 | ff36f19eb (side session): layers, frames, brush/colour, shapes. Phone test and push owed (10-24). |
| Free-model task benchmark | 🔵 | Side session running 10-24: measured success per free model; nothing claimed until it reports. |

---

## 7. PROJECTS, ASSETS, STORAGE

| Feature | Level | Notes |
|---|---|---|
| Project = folder + project.json | 🟢 ✔ | schema v13 |
| Atomic save (.tmp then rename, .bak kept) | 🟢 ✔ | |
| Refuses to overwrite a newer-schema project | 🟢 ✔ | Good discipline, already there |
| **Consolidate** — copy every asset into the project | 🟢 ~ | Dedup by content hash, copy-never-move, cancellable |
| project:// relative addressing | 🟢 ✔ | AssetResolver. One scheme, deliberately. |
| Fonts consolidated into the project | 🟢 ~ | They used to die on uninstall |
| Zip export / import of a project | 🟢 ~ | |
| Relink by content hash and filename | 🟢 ~ | One relink resolves a whole folder |
| Asset browser (scans a chosen folder) | 🟢 ~ | assetbrowser/AssetScanner |
| B-roll bucket + .broll_tags.json | 🟢 ✔ | Lives in Pictures/FadCam/assets — **outside the project** |
| **THE VAULT** (now "the Joy Creator folder") | 🟢 | ✔ Built 2026-10-13/15, agent-driven on the Note 9: required first step; projects mirrored + restored; Gather; one Trash with Undo; recordings and exports land in the folder with readable names, the Library reads it; share-in; big outside files linked with Copy/Move/Leave + yellow dot; Space screen; JoyBrush drawings with thumbnails in the folder and the Lobby. Open items: spec §6. Live spec: `tasks/SPEC_20261013_FILE_MANAGEMENT.md` (§4 has the proof level of every piece). |

### 🚨 The storage finding

✔ ProjectStorage line 84: `projectsRoot = new File(context.getFilesDir(), "faditor/projects")`

That is app-private internal storage. Projects are invisible to the user, invisible to a
PC, impossible to put on an SD card, and **deleted without warning on uninstall.**
On 2026-08-29 an acceptance test did exactly that and destroyed 23 of JoyRaptor's 24 projects.

Everything downstream (consolidate, project://, zip, relink) is already correct and does
not care where the root lives. **The vault is a small change that must happen before
strangers install.**

---

## 7.5 THE FRONT DOOR — the lobby, and the visual system (2026-09-17)

Design records 01–07 (artifacts). Backup before any of it: tag
`pre-studio-redesign-20260917`, branch `backup/pre-studio-redesign-20260917`.

| Feature | Level | Notes |
|---|---|---|
| **The lobby** | 🟢 | `ui/lobby/LobbyFragment`. Tab position **6** — deliberately last, so every existing tab keeps its index and nothing switching on a position was touched. A ROUTER over existing screens: it cannot lose a feature and it is one constant from being reverted. The app lands on it; the bottom bar hides there and the house icon is the way back from every room. |
| Marquee carousel | 🟢 | Wrap-around room titles, heavy white → thin grey. **A new room costs one entry in `buildRooms()`** — that is the whole reason it exists rather than a sixth nav icon. |
| Hero with real frames | 🟢 | MediaMetadataRetriever at 1s in (frame 0 of a recording is usually black), off-thread, LruCached, tag-guarded against a room switch mid-decode. |
| Mixed recents + corner cut | 🟢 | Projects, recordings, exports, photos, streams by recency. Sampled on device: project `#73FB9E`, export `#4DBFFB`. Until recordings landed the cut was decoration. |
| Joybot's line | 🟢 | May only say things it VERIFIED — a count or two timestamps compared. Says nothing on JoyRaptor's phone today, which is the feature working. |
| Export hairline | 🟢 | Real `ExportService` broadcasts (out-of-process, so a normal receiver). Scales, never resizes. |
| **The visual system** | 🟢 | Ground `#000000`; lane A is the ground, lane B `#0B0B0D` (4.3%, inside SPEC_N §6). Chrome on `studio_panel`. Selection **cyan**, neutral playhead **pink** (context tint preserved — do not flatten it). Transport recedes by value: one white control. |
| Drawer transparency | 🟢 | Top drawers at 64% black, coloured chips at 70%. **Measured**: the object drawer's secondary text was at **1.39:1** over a blown-out frame — invisible outdoors. Now 4.86:1. Blur is impossible below API 31; tint is the only lever. |
| First run | 🟢 | New copy, the promise before the ask, the AI-key card. Red gone: cursor, ticks and button all sampled. |
| `ui/motion/Motion` | 🟢 | Four curves, seven durations, six rules. **No helper for 100×/day controls** — those get no animation at all. |
| Object drawer header | 🟡 | One-line title + scrolling tabs, fixing the reported "label stacks vertically and pushes the rows past the minimum drawer" bug. **Compile-verified only** — needs a real double-tap on a phone. |

**Open rulings, JoyRaptor's not mine:** the contextual tool row (would fight the
existing pins + usage-recency system), the ~50 sites where green means "modified",
amber transform handles on the canvas, and pink-not-red for voiceover recording.

**Asset-blocked:** empty-room heroes, the first-run slideshow, Joybot's face, the
demo project, a display typeface. All listed in design record 07 with sizes.

---

## 8. ONBOARDING AND IDENTITY

| Feature | Level | Notes |
|---|---|---|
| App name is "Joy Creator" | 🟢 ✔ | strings.xml line 2 |
| Only 16 of 2,820 strings say "FadCam" | ✔ | Branding surface is small |
| Intro with animated fading lines | 🟢 ✔ | RowFadeAnimator — **the cycling-words mechanic already exists** |
| Language picker | 🟢 ✔ | |
| Permission screen (all up front) | 🟢 ✔ | Recommend deferring to point of use |
| "Verify you are a human" + privacy link | 🟢 ✔ | **Copy is written for a covert recorder. Must be rewritten for Play.** |
| New intro copy | 🔵 | Drafted 2026-09-09, approved by JoyRaptor |
| Vault picker screen ("Your Joy Creator folder") | 🟢 | Required first step since 2026-10-14 (shown after onboarding, before using the app); Note 9-proven. |
| Demo project | ⚪ | INBOX.md |
| New landing screen / studio funnel | ⚪ | INBOX.md — its own session |

---

## 9. 🔴 KNOWN BROKEN — triage before launch

| # | Defect | Severity | Source |
|---|---|---|---|
| 1 | ✅ **FIXED 2026-09-23** (LEDGER "audio clips kept getting new ids"): the loader keeps the saved id. Proof `bash tools/jvm-harness/run-audioid.sh` 14/14 — re-run 2026-10-14 after the harness got a StudioMirror stand-in (the folder hooks had broken its compile). | ~~High~~ | LEDGER 2c |
| 2 | ✅ **FIXED** (d0fecd60): narrative analyse works on audio; apply-narrative and b-roll refuse audio with a reason; no tool defaults to the auto-blank. Was: three AI tools resolve only against the video track — they fail on projects where the transcript sits on an audio clip (music / lyric projects). The one-path resolver already exists; three functions need to use it. | Medium | SPEC_20260828_AI_AUDIO_TOOLS |
| 3 | ✅ **FIXED 2026-10-13**: default is openrouter/free. Was: default AI model is openrouter/auto — the **paid** router. Every new user is billed on message one. | High — first impression | ✔ 4 code sites |
| 4 | ✅ **FIXED 2026-10-14**: `ai/AiKey` keeps it AES-GCM encrypted under an Android Keystore key; an old plain key is moved in on first read. Was: AI API key stored in plaintext preferences | Medium — security | ✔ |
| 5 | ✅ **FIXED 2026-10-14**: 8 tool steps per message, then it stops and says so. Was: AI tool-calling loop has no depth cap | Medium — can hang | Reports, plausible |
| 6 | ✅ **FIXED 2026-10-14**: 4096. Was: AI reply limit of 1024 tokens truncates edit instructions | Low, trivial fix | Reports |
| 7 | ✅ **FIXED 2026-10-14**: now merge_transcripts (old name still accepted). Was: ai_merge_transcript has "ai" in the name and uses no AI | Cosmetic honesty | ✔ |
| 8 | remove_silence is report-only for audio clips | Low | |
| 9 | Stranded drag-latch, second path — self-heals, fires occasionally | Low, watch only | LEDGER 2b |
| 10 | No automated tests at all — app/src/test does not exist | Structural risk | ✔ |
| 11 | **Saved sprite animations are write-only.** The resolver plays them; no screen lists them. A sheet imported with ten named animations shows zero. | High — makes the whole SpriteLab pipeline dead-end | ✔ 2026-09-10 |
| 12 | **Cell names are write-only**, and `Cell.tags[]` has never been written at all. Blocks every AI capability that would reason about what is drawn on a sheet. | Medium — blocks SPEC_20260910_SEMANTIC_CELLS | ✔ 2026-09-10 |
| 13 | **Sprite sheets live inside `project.json`**, so a character perfected in one project does not exist in the next. Same wound as THE VAULT: an asset trapped in composition scope. The B-roll bucket and `AvatarLibrary` already show the right pattern. | Medium — reuse, and it gets worse with users | ✔ 2026-09-10 |

---

## 10. WHAT DOES NOT EXIST (so nobody re-discovers the absence)

Text-to-image. Text-to-video. Text-to-speech or voice cloning. Voice enhancement.
On-device LLM. Hosted AI credits. Template gallery. Pack manager UI. Community pack index.
Music generation. A website. A Play listing. A privacy policy that mentions AI.
**A backup of this repository anywhere but JoyRaptor's PC.**

---

## 11. HOW TO MAINTAIN THIS

1. When something is proved on a phone, move it to 🟢 and say what proved it.
2. When a spec is written, add a 🔵 line. Do not write the spec until it is scheduled.
3. When an idea appears it goes in INBOX.md — **not** into a new SPEC_*.md.
4. When a defect is found, add it to section 9 with its severity.
5. LEDGER.md keeps the story. This file keeps the score. Both stay.

> **Joy Brush Note 9 agent check, 2026-10-01:** Open A/B and Recent recovery passed on the phone.
> The safety copy of the owner's drawing matches every original archive entry byte-for-byte.
> Fixed an actual large-drawing snapshot memory crash, coloured-paper decoding and premature GL
> startup uploads. 193 androidkit tests pass and app watcher build is green; updated APK installed.
> PNG with/without paper now passes on the Note 9, including exact pixels across negative tile
> coordinates. Fixed resumed GL work running before EGL was current after the picker. All 194
> androidkit tests pass. Owner drawing restored: all 1713 archive entries (1711 paint tiles) match
> the pre-test backup byte-for-byte. Final in-place install: 2026-10-01 09:30:55.
> Details: `tasks/joybrush/INTEGRATION_20260930.md`.

> **Joy Brush automatic graphics recovery, 2026-10-01:** completed saved artwork now restores
> automatically after runtime EGL loss. Verified on Note 9 with the 1711-tile drawing in the same
> Activity; 205 androidkit tests pass and full app builds pass. Undo history restarts; marks since
> the last completed save may be missing. Owner pre-test archive restored byte-for-byte, normal
> phone operation restored. Next blocker: memory overlap when Open switches two large drawings.

> **Joy Brush large drawing switching, 2026-10-01:** Open/Recent keep the selection compressed until
> the previous drawing is preserved and its snapshot released. 211 androidkit tests and app watcher
> build pass. Installed and verified on Note 9: large-to-large Open and Recent both pass, all selected
> pixels/settings exact; damaged files leave the previous drawing intact. Owner drawing restored,
> all 1713 entries exact. Large swaps still take tens of seconds. Next: frame/cel and host integration.

> **Joy Brush frame foundation, 2026-10-01:** legacy cel GPU stores, all-cel save/load, selected-frame
> PNG and frame-aware undo pass 227 androidkit plus 36 targeted paint tests. Note 9 checks pass for
> copy, isolated edits, undo/redo, linked/blank frames, holds and cold reopen. Latest owner artwork
> restored: all 1835 entries (1833 paint tiles) exact. Whole-layer animation creation shortcut removed
> because JB-3.00a/R50 requires region animation; JB-3.01b/c adaptation is still owed. Backed up on
> codex/frame-projection-foundation, not merged into origin/joy-creator: read-only preflight found a
> board-spec add/add disagreement. Remote paper changes not yet integrated/phone-verified here.
> Shared handoffs: tasks/joybrush/AGENT_BOARD.md. Detailed evidence: INTEGRATION_20260930.md.

> **Region ownership foundation, 2026-10-02:** RegionPaintPlan landed on origin/joy-creator through
> 0a46f811 from isolated codex/region-routing. Pixel-exact shared/frame tile partition, multi-board
> and negative-coordinate edges; 13 new tests. Combined latest-paper core suite: 1458/0; boundary
> sabotage caught, original restored. Runtime helper only: saved region model, GPU commit/undo,
> preview/export adapters and phone proof still owed. Primary tree's divergent spec is preserved.
Details: tasks/joybrush/INTEGRATION_20260930.md.

> **Saved region model, 2026-10-02:** origin/joy-creator 82a16eba adds v6 board cursors and per-layer shared/region cels. Core 1470/0 and Android file tests 225/0 before latest clean paper merge. Blank/copy/link operations return bounded pixel instructions; GPU/undo/preview/export adapters and phone proof remain owed. Existing test art is disposable; no conversion work.

> **Region CPU rendering, 2026-10-02:** origin/joy-creator 0f4e57cd connects export rendering to board-local frame/shared tile slices, including holds, masks and clip bases. Combined core1481/0, Android file/export225/0; no device proof. GPU paint/undo adapter remains next. Shared AGENT_BOARD.md now published on origin.

### JoyBrush Select coordinator candidate — October24
- Built on isolated current-main base a94933ad7: A1–A8 delivery + ordered Lead adapters, C2 bounded menus/locked-option reveal, per-brush caption capability gate. Not merged/installed; final suites and C1/C2 rendered/phone checks open.
- Additional source safety fixes d8ddf2ebe/9805dbff6 abandon stale selection-preview handles before context replacement and bound paint-mask coverage before allocation/transform/commit. New regression cases authored, not executed. Pixel lift/clip remains gated; corrected toast makes no painting-inside promise.
- PhaseB design proposal now exists at tasks/joybrush/specs/SELECT_PIXELS_DESIGN_20261023.md; Engine Lead review + PhaseA integration required before implementation. A9/A10/C3 remain open.
- Verification deferred to owner-reserved watercolor RAM/phone window; root holds no resource claim or waiting build. Full handoff: HANDOFF_20261024_COORDINATOR.md. Historical test counts do not verify this candidate.

October24 evening Select verification: exact79f focused14 passed; exact55b app27cases produced7 behavioral assertion failures (noerrors), source-specific receipts in reviews/SELECT_FOCUSED_79F_20261024.json and SELECT_RED_55B_20261024.json. Supporting cancellation/stale callback fix1bdbec22a is built source, rerun owed; Copy admission/order still blocks acceptance. Offline bench validator29tests passed, not phone evidence. Current main7d2 UI bench/K25K26 still needs scoped reconciliation; oldglobal L3fallback held. No final APK/phone/release claim.
