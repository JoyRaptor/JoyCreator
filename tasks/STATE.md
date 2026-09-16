# STATE — what Joy Creator actually has, and how proven it is

**Purpose:** stop the rediscovery tax. This is the one file that says what exists.
Every agent reads it first. Every promotional claim comes from it. The help doc and
Joybot's knowledge are generated from it.

**Last full pass:** 2026-09-09 (Claude/Opus, launch-planning session)
**Last touched:** 2026-09-16 evening — the puppet UI finish sweep (`tasks/PUPPET_FINISH_LEDGER.md`):
nine dead knobs found and resolved, the tape made draggable, easing fitted from the recording, and
three defects JoyRaptor reported on device. Also `tools/phone.sh deploy` / `doctor`, which exist
because a stale watcher or a dropped transport had been leaving OLD builds on the phone.
**Scale:** 710 Java files, ~349,000 lines, schema v13

---

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
| Corner-pinned TEXT, with effects | 🟡 | 2026-09-16: a pinned box with ANY effect on it exported UNPINNED while the preview showed it pinned — TextFxGlEffect rebuilt a per-frame item and the pin was never among the copied properties. Now concat-ed from the same cornerPinMatrix the preview and the plain export use. Not yet re-exported on a phone. |
| Corner pin on SPRITES | 🟡 ✔ | SPEC Z slice 1, 2026-09-13. Model, persistence, undo, transform surface, authoring and BOTH renderers — one shared matrix method. Compile- and harness-verified; **no one has dragged a sprite corner on a phone yet.** |
| Mesh bend on sprites / PiP / text / spine | 🔵 | SPEC Z. Sprites hold a MeshWarpSpec that nothing draws; PiP and spine need the Clip model work; text needs a pinned view. |
| Sprites on the transform surface | 🟡 ✔ | SPEC Z slice 1 step 2, 2026-09-13. They used the legacy handle overlay until now. |
| Transform (affine handles, rotation dial, pivot) | 🟢 | **Complete 2026-09-10.** Shipped alongside Bend. |
| Undo (one press = one step) | 🟢 ~ | JoyRaptor's ruling. Batch operations collapse to one undo. |
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
| Audio ducking | 🔵 | Removed pending a mixer that can honour it (set_clip_duck removal comment in code ✔) |
| Visualizer studio | 🔵 ~ | feature-visualizer-studio-spec.md, Tier 3 planned |

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
| Puppet pins, bones, IK, dangle | 🟡 | Place, bone, drag-a-limb, simulated hair. Engine proved off device; gestures barely driven. Dangle chains BAKE to keyframes on rebuild as of 2026-09-16 — before that the type, its four sliders and DangleSim all existed and nothing ever called them. **Bones became SELECTABLE 2026-09-16** (tap a shaft), which is what let the bone scope exist at all: parent, length, rest angle, stretchy, flip elbow, joint limits. |
| **Puppet — the nine dead knobs** | 🟡 | 2026-09-16 audit: every control traced to the code that reads it. Show·Mesh, the reach eye, per-bone bend sign, pin mute, per-pin weight and Snap-to-keys were all authored, saved and read by NOTHING; each is now wired. Per-pin `scale` and the Free-pin rotate arc could not be expressed by the engine (`handleComponents()` is 2) and were REMOVED, field and all. Full list and reasoning: `tasks/PUPPET_FINISH_LEDGER.md`. |
| **Puppet KEYFRAMES and live takes** | 🟡 | SPEC_20260915_PUPPET_UI. Per-pin keys, record-on-touch, thinning, anchor-in/blend-out, and a tape that draws performances as long diamonds. **Unit-proved, never keyed on a phone.** 2026-09-16: arming record now ROLLS THE TRANSPORT on the first real movement and stops it on release — JoyRaptor reported *"recording mode doesn’t work when I arm it and then I move something, playhead doesn’t move"*, and it did not: recording required playback to be running ALREADY, so arming and touching a pin was a no-op. |
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
| Share-in from other apps | 🔵 | **The headline.** Needs an intent filter plus the existing import path. |
| Multi-provider support | 🔵 | ai_provider pref ✔ **declared and never used** |
| Streaming replies | 🔵 | Currently a frozen "Thinking…" |
| Joybot sprite and states | ⚪ | INBOX.md — needs its own session |

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
| **THE VAULT** | 🔵 | ✔ **Not built. Projects live in getFilesDir() — wiped on uninstall.** |

### 🚨 The storage finding

✔ ProjectStorage line 84: `projectsRoot = new File(context.getFilesDir(), "faditor/projects")`

That is app-private internal storage. Projects are invisible to the user, invisible to a
PC, impossible to put on an SD card, and **deleted without warning on uninstall.**
On 2026-08-29 an acceptance test did exactly that and destroyed 23 of JoyRaptor's 24 projects.

Everything downstream (consolidate, project://, zip, relink) is already correct and does
not care where the root lives. **The vault is a small change that must happen before
strangers install.**

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
| Vault picker screen | 🔵 | |
| Demo project | ⚪ | INBOX.md |
| New landing screen / studio funnel | ⚪ | INBOX.md — its own session |

---

## 9. 🔴 KNOWN BROKEN — triage before launch

| # | Defect | Severity | Source |
|---|---|---|---|
| 1 | **Audio clip IDs regenerate on every load and save.** AudioClip has no setId, so the id on disk is write-only. Anything outliving a session that names an audio clip is unreliable. Fix shape fully diagnosed. | High — data integrity, undermines portability | LEDGER 2c |
| 2 | Three AI tools resolve only against the video track — they fail on projects where the transcript sits on an audio clip (music / lyric projects). The one-path resolver already exists; three functions need to use it. | Medium | SPEC_20260828_AI_AUDIO_TOOLS |
| 3 | Default AI model is openrouter/auto — the **paid** router. Every new user is billed on message one. | High — first impression | ✔ 4 code sites |
| 4 | AI API key stored in plaintext preferences while unused Keystore machinery exists | Medium — security | ✔ |
| 5 | AI tool-calling loop has no depth cap | Medium — can hang | Reports, plausible |
| 6 | AI reply limit of 1024 tokens truncates edit instructions | Low, trivial fix | Reports |
| 7 | ai_merge_transcript has "ai" in the name and uses no AI | Cosmetic honesty | ✔ |
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
