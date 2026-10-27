# ROADMAP — what happens next, in order

**Companion files:** STATE.md (what exists), ORPHANS.md (started and stopped, still wanted), INBOX.md (unsorted ideas), LEDGER.md (the story).

## Built feature register — timeline marks, 2026-10-24

**Status: BUILT; focused verification passed; final phone/owner acceptance pending.**
The owner approved `SPEC_TIMELINE_MARKS_20261023.md`; this closes the old undecided
bookmarks/playhead-chip idea in `road_map.md` without adding a new launch prerequisite.

- [x] Named gradient bookmarks: timestamp tap/drop and hold/open; compact centered wheel,
  animated navigation, one-line times, color/name/time edits, shared Studio artwork/icons.
- [x] Named In/Out regions, independent export selection and an optional active loop target.
- [x] Atomic marker metadata, legacy compatibility, and saved undo/redo.
- [x] Separate named queued composition-range exports, including audio-first M4A ranges.
- [x] Native APK built/packaged; 68 focused current-source JVM assertions passed.
- [ ] Complete Note 9 save/reopen, undo/redo, wheel gestures, loop and exported-file checks.
- [ ] Owner approval of the native widget's appearance and feel.

October 24 return audit corrected duplicate wheel touch handlers; native inspection also
found the canvas bitmap's intrinsic height inflating the menu. Corrected sizing preserves
the original untinted image. Keep BUILT distinct from owner-proven LIVE (see STATE.md).

Two lists, deliberately unequal.

**BEFORE LAUNCH is closed.** Nothing gets added to it without something coming out. That
is the whole point of it. If an idea arrives, it goes to INBOX.md.

**AFTER LAUNCH is open.** Reorder it freely, as often as you like. Reordering costs nothing.

---

# PART ONE — BEFORE LAUNCH

> **JoyBrush update, October26:** source for range-aware exposure timing, independent editable
> plane-copy preparation and transient-render export routes is committed on
> `codex/tween-direct-20261026`, based on lead-approved8e7692c83. Three bounded ownership checks
> passed on exact source, followed by6 timing/30 export tests on frozenf7ed34f16. The duplicate
> marker prerequisite is written; live engine integration and new device proof remain owed.
> Engine/GPU/UI integration remains
> with the lead, and clip/component reconciliation is a separate prerequisite. Detailed source
> hashes and proof limits are in [STATE](STATE.md) and the
> [coordination contract](joybrush/handoffs/TWEEN_COORDINATION_20261026.md).

> **JoyBrush vector/tween status, 2026-10-24 (existing scope; no launch-scope addition):**
> Saved tween state/sampling/transient line scenes and slab-only static export fixes are built
> and tested in `2896cf2d5`, picked up as `59d1f1d63`. They are included in the local animation
> clips integration branch (`6a4c471eb`), but absent from primary HEAD `fb8042841`.
> The October 24 forward exposure wakeup helper is source-only; compilation was deferred for
> insufficient RAM. Still open: editable frame-copy identity, GPU in-betweens, playback/controls,
> component-cel resolution, animated export and phone acceptance. This is engineering work,
> not a completed feature waiting solely on JoyRaptor. Detailed rows and proof levels:
> [JoyBrush roadmap](joybrush/ROADMAP.md) and [STATE](STATE.md).

> **2026-09-16 evening — puppeteering is FINISHED as designed, and still almost entirely
> unproven on a phone.** The UI sweep is done: every function that was designed, mocked up or
> discussed has been traced to the code that reads it. Nine dead knobs were found and either
> wired or removed, the tape drags (slide / stretch / retime), easing is fitted from the
> recording, and three defects JoyRaptor reported on device are fixed. The list, with the seven
> things still open and the reason each is open, is `tasks/PUPPET_FINISH_LEDGER.md`.
>
> **The risk has not moved.** JoyRaptor has confirmed four things on a device: a rigged dinosaur
> bends, the overlap halo is gone, the mesh is smooth, and the helper strip works well enough to
> report six specific defects against. Nobody has keyed a pin, recorded a take, dragged a
> performance, tapped a bone, watched a tail swing or rotated a rigged picture on hardware.
> **That is still the single largest before-launch risk in the animation wing, and it is a
> morning with a phone, not a sprint of code.** Fourteen harness suites and ~530 assertions are
> not the same claim and must never be reported as one.
>
> Three of the open items need HIM rather than more code: a screenshot of the overlapping corner
> widget, one press of the timeline grab bar (the diagnostic is now conclusive — it prints
> `moves=`, which separates "the touch never arrived" from "something ate the drag" from "the
> clamp refused the size"), and a verdict on whether the "Everything" chip is missed.

## Lane 0 — Pure waiting. Start these first, they cost only calendar.

| # | Item | Who | Notes |
|---|---|---|---|
| 0.1 | ✅ **DONE 2026-09-09** — repo backed up to github.com/JoyRaptor/JoyCreator | — | 3,253 commits pushed. History rewritten first: name, personal email and device serials removed from every commit, file and message. Local copy of the original history kept at `../joycreator-backup-20260909-2035.git`. **Confirm the GitHub repo is set to Private.** |
| 0.2 | Google Play developer account | JoyRaptor | One-time fee plus identity verification, which takes days |
| 0.3 | A developer email address | JoyRaptor | Goes on the listing publicly. Not the personal one. |
| 0.4 | Recruit 12 closed testers | JoyRaptor | Family, church, one Reddit post |
| 0.5 | Start the 14-day closed test clock | JoyRaptor | **The build does not have to be finished.** It updates during. |

## Lane 0.5 — Look at what is already built. See ORPHANS.md.

| # | Item | Notes |
|---|---|---|
| 0.6 | ✅ **DONE 2026-09-10** — it plays. The 2026-08-29 "critical regression" was an automation artefact, not a defect. | |
| 0.7 | **Re-run the 41-check device sweep** (SPEC_20260829_DEVICE_VERIFY_ALL) | 14 finished features have never been looked at. One evening with a phone. **Highest value per hour on this page.** |
| 0.8 | Fix whatever the sweep finds | |

## Lane 1 — Cannot ship without these

| # | Item | Why |
|---|---|---|
| 1.1 | Remove the 30 disguise icons from the Play build | Stalkerware signature |
| 1.2 | Remove the accessibility screenshot service from the Play build | Policy violation on its own |
| 1.3 | Resolve MANAGE_EXTERNAL_STORAGE for the Play build | Usually refused for editors |
| 1.4 | Remove cloud / streaming / privacy-black-screen from the Play build | LAUNCH_STRATEGY sections 3 and 5 |
| 1.5 | Decide and set the application ID | **Permanent.** Changing it later loses every user. |
| 1.6 | 📝 **DRAFTED 2026-10-14, owner to review** — README.md and PRIVACY.md rewritten from a code audit (every server the app talks to, when, and that location/weather are off by default); blanks: contact email, publish date. Also: the update sheet no longer shows FadCam's release notes (CHANGELOG.md is now Joy Creator's; FadCam's moved to docs/). Still FadCam: the bundled What's New text (assets/changelog/main.md) and the streaming server's notice fetch from anonfaded/FadCam (full build only). | They describe FadCam today and would be false about the AI |
| 1.7 | Privacy policy hosted at a public URL | Required before you can submit |
| 1.8 | Rewrite the "verify you are a human" screen | Currently written for a covert recorder |
| 1.9 | **Export: long projects finish in one tap, fast** (owner, 2026-09-23) | The 48-min lecture exported end to end for the first time today. Speed stages below. |

### Export speed roadmap — added by JoyRaptor 2026-09-23 ("our export road map")

Measured on the Note 20 at 1080p. Hardware ceilings (app_process MediaCodec bench): H.264 encode
416 fps (13.9x realtime), 720p 723 fps (24x); decode of the screen recording ~300 fps (~10x).
Encode and decode share the video chip, so a GPU-only 1080p pipeline tops out around 7-9x.
Every export writes GL_SAMPLE lines to its trace saying where the frame time went — use them.
Detail and history: tasks/todo.md "NEXT: export speed".

| Stage | What | Expected | Status |
|---|---|---|---|
| 0 | Wake lock + keep the export screen on | 0.25x -> 1x | DONE (2d82f102, 4858cae4) |
| 0b | Image overlays on the GPU with the preview's own code (PipGl, pipForModel) | -> 1.5x, preview parity for images | DONE (da854f68) |
| 1 | Captions on the GPU: tight box, re-raster only when the word changes (GlCaptionEffect) | -> ~3x | DONE (331fd9b9, device-proven) |
| 2 | One GPU pass per frame: fold crop, resize, images and captions into a single compositor like the preview's (today 6-9 full-frame passes) | -> ~5-6x | 2a BUILT db486113 (per-clip overlay window; text boxes in the image run, drawn once) — device test owed |
| 3 | Two parts in parallel (the video chip runs several codec sessions) | -> ~7-9x (the chip's ceiling) | BUILT 869102b4: two part workers + the sound pass alongside from the start; a part failing in parallel retries alone |
| 4 | **Smart re-export (render cache) + RANGE EXPORT** — key each part on ITS OWN content, cut parts at any time (not just clip seams), reuse every unchanged part. A range export is then "render the parts that cover the range" | a one-mask edit re-exports in minutes, not ~35; a range of already-rendered minutes is near-instant | 4a smart re-export BUILT b363b8ec; 4b range export ENGINE BUILT f6e63879 (ExportService range extras; trim optimization) — the in/out UI is the studio lane's |
| 5 | Straight-copy stretches with no overlays/captions/effects (no re-encode) | ~50x on those stretches | DECIDED AGAINST (2026-09-24): captions cover every minute of JoyRaptor's projects, so there is nothing to copy; and joining camera-encoded and re-encoded parts with -c copy mixes H.264 parameter sets in one track (playback glitches). The render cache (4a) is the safe form of 'do not redo what did not change'. Range export already copies everything after the first keyframe. |
| 6 | Fast 720p draft preset | ~15-20x | AVAILABLE: Resolution 720p already scales at the head of the chain, so every overlay pass runs at 720p. A one-tap "Draft" chip (720p + Low) is handed to the studio lane |

## Lane 2 — The foundation that gets expensive after users arrive

> **2026-10-13: superseded in scope by `tasks/SPEC_20261013_FILE_MANAGEMENT.md`** — the owner asked
> for best-in-class file management across the WHOLE studio (Studio, JoyBrush, SpriteLab, VisLab),
> not just Studio projects. The items below map into that spec's Phase 1. Key ordering from it: the
> folder ships BEFORE the application ID changes (1.5), because the folder is the only bridge a new
> ID can read.
>
> **2026-10-13 build:** the folder core, the Studio mirror/restore and the setup screen are built and compile
> (17 core tests pass). Device test owed. Recordings/exports into the folder are next. See the spec's build log.

| # | Item | Why now |
|---|---|---|
| 2.1 | ✅ **DONE 2026-10-13/14** — the Joy Creator folder (Studio side); JoyBrush side briefed. See SPEC_20261013_FILE_MANAGEMENT §4 | Today it has to work on one phone. After launch it has to work on every phone, forever. |
| 2.2 | ✅ **DONE 2026-10-14** — choosing the folder is a required first step (owner: no fractured body of work); "I already have one" restores | The restore path is what proves the ownership promise |
| 2.3 | ✅ **DONE** — choosing the folder copies every project in, with media after a size-showing consent | One-time, while JoyRaptor is the only user |
| 2.4 | ✅ **DONE** — recordings and exports land in the folder, readable names | Ends "where the hell is my screen recording" |
| 2.5 | ✅ **SUPERSEDED 2026-10-14 by the owner** — small files copied silently; big ones linked with a warning and Copy / Move / Leave per file; yellow dot until brought in. | The alternative breaks projects weeks later |
| 2.6 | ✅ **DONE** — "can't reach this folder, nothing has been lost" + reconnect; recordings fall back inside the app | SD card popped out must not look like data loss |
| 2.7 | ✅ **DONE 2026-09-23** — audio clip ids survive save/load (re-proved 2026-10-14) | Data integrity, and it undermines portability |
| 2.8 | Agree the pack folder contract (contract only, no manager UI) | Free now. Four asset systems later if we skip it. |

## Lane 3 — The AI, tightened

| # | Item | Size |
|---|---|---|
| 3.1 | ✅ **DONE 2026-10-13 (compile owed — watcher was off)** — default is now `openrouter/free`, the free router (owner: "their first experience is free and they can set to whatever later"). ChatAssistantActivity DEFAULT_MODEL + both AIToolExecutor reads. Users who already saved a model keep it. | One line. |
| 3.2 | ✅ **DONE 2026-10-14** — 8 tool steps per message; replies up to 4096 tokens | Small |
| 3.3 | ✅ **DONE 2026-10-14** — `ai/AiKey`, Android Keystore AES-GCM; old plain key migrated | Small |
| 3.4 | ✅ **DONE 2026-10-14** — merge_transcripts (old name still accepted) | Trivial |
| 3.5 | **One-tap buttons for tools that already exist** — Clean up audio, Make chapters, Cut the ums | Small, and the highest value-per-hour item on this page |
| 3.6 | ✅ **BUILT 2026-10-14 (compiles; needs a phone with an AI key to try)** — replies stream into the bubble; a tool command shows "Working…" instead of code | Most of what makes an AI feel good |
| 3.7 | ✅ **DONE** (d0fecd60) — found already fixed 2026-10-14 | One file, three functions, one at a time |
| 3.8 | ✅ **DONE 2026-10-14** — `ai/AiService`: one address for chat + tools; settings has "Service address" for any OpenAI-compatible server (https, or http://localhost on the phone); no key needed for a custom one. Tested on the Note 9 against a local address. Was: Split the AI networking behind one seam | Needed for everything after. Ship two providers: OpenRouter and a custom URL box. |
| 3.9 | ✅ **DONE 2026-10-13 (Note 9-proven)** — **Share-in** — accept an image or video from the Android share sheet, straight onto the timeline | The headline. Works with any AI app, no key. **Owner, 2026-10-13: implement "as soon as we have the perfect place to put things" — i.e. it lands right after the file home (Lane 2), not before.** |
| 3.10 | generate_image through OpenRouter, into the project | Same pipeline as 3.9. Endpoint verified. |

## Lane 4 — What a stranger meets

| # | Item |
|---|---|
| 4.1 | New intro copy (drafted and approved 2026-09-09) |
| 4.2 | Defer permission requests to the point of use |
| 4.3 | Demo project — a small pre-loaded project you can press play on |
| 4.4 | Hide Avatar Studio and other 🟡 features from the launch UI. Mesh warp / Bend is 🟢 as of 2026-09-10 and ships visible. |
| 4.5 | Promotional screenshots (from the demo project) |
| 4.6 | Feature graphic and store icon |
| 4.7 | Store listing copy (generated from STATE.md) |
| 4.8 | Website — GitHub Pages is free and sufficient |
| 4.9 | 60–120 second demo video |
| 4.10 | **The Lobby, fully polished** — added by the owner 2026-10-13 ("needs to be fully polished before we launch"). Known gap: it cannot open a different drawing; "New drawing" reopens the same one, and drawings have no thumbnails. Overlaps SPEC_20261013_FILE_MANAGEMENT Phase 2. Owner is scoping it. |

---

# PART TWO — AFTER LAUNCH

Ordered by current thinking. Reorder freely.

## First update — the "look what landed" moment

- **Video generation.** Prompt, background job, confirm the cost, lands on the timeline.
  Requires: silent audio track added on import or the export dies.
- **Joybot.** The sprite, his animated states, his corner, his tour. Its own session.
- More providers behind the seam built in 3.8.

## Soon after

- **Sprite read-back — the three missing doors.** Saved animations as tappable chips in the
  palette (the resolver already plays them — STATE §5), cell names on chips and on the tape,
  and a sheet library outside the project so a character is reusable across projects.
  Small, and it is what makes SpriteLab's output usable. SPEC_20260910_SEMANTIC_CELLS §5.
- **Semantic cells in the AI toolkit** — `infer_sprite_semantics`,
  `sync_sprite_to_transcript`, `author_sprite_variant`, and teaching
  `describe_sprite_sheet` to lead with names. Turns a named sheet into a character the
  model can direct. SPEC_20260910_SEMANTIC_CELLS §6.
- Pack manager UI — install, enable, disable, share as a zip
- Theme packs (also the natural Supporter unlock)
- Template gallery
- Finish the AI proposal tools (narrative reorder, b-roll placement)
- New landing screen and the recorder-to-Studio funnel
- Luma key
- Adjustment layers and FX
- Free-up-space action, project sizes in the list

## Later

- **Automatic lip sync** — `SpectralVisemeAnalyzer` (6 classes, built) + `AvatarRig.visemeMap`
  (name-keyed, built) + a sheet whose cells carry viseme assignments (SpriteLab authors them).
  Every link of that chain exists except the map, and the map is six taps.
- **Live-performance polish** — punch-in/punch-out on a keyframe pass, and snapping dropped
  keys to `BeatDetector`'s onsets. Protects the one-pass tap workflow instead of replacing it.
- **Bend on sprites** — squash and stretch as a layer over a sprite. Real work:
  `CornerPinTransformHost` is image-only today and `SpriteOverlayItem` has no transform host.
- Community pack index — a static file on GitHub Pages, zero infrastructure
- Composed-frame vision — the AI sees what you see, overlays included
- Lecture mode — one confirmation card runs the whole edit
- Avatar Studio unhidden, once driven by a human
- Mesh warp unhidden
- Multi-camera and remote-camera-over-LAN promoted properly
- Automated tests, starting with the validators
- Supporter tier via Play Billing
- F-Droid submission

## Parked, deliberately

On-device LLM. Hosted AI credits. WASM or native plugins. Music generation. iOS.
Any subscription. Any advertisement. Any export paywall. Any watermark.

---

## The promise that does not move

Free forever. Unhobbled. No ads, no subscription, no watermark, no export paywall.
AI stays bring-your-own-key at zero margin. Support is optional and unlocks cosmetics only.
