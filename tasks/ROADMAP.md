# ROADMAP — what happens next, in order

**Companion files:** STATE.md (what exists), ORPHANS.md (started and stopped, still wanted), INBOX.md (unsorted ideas), LEDGER.md (the story).

Two lists, deliberately unequal.

**BEFORE LAUNCH is closed.** Nothing gets added to it without something coming out. That
is the whole point of it. If an idea arrives, it goes to INBOX.md.

**AFTER LAUNCH is open.** Reorder it freely, as often as you like. Reordering costs nothing.

---

# PART ONE — BEFORE LAUNCH

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
| 1.6 | Rewrite README and PRIVACY | They describe FadCam today and would be false about the AI |
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
| 1 | Captions on the GPU: tight box, re-raster only when the word changes (GlCaptionEffect) | -> ~3x | BUILT, gated off (GL_CAPTION_PASS) — needs one device test |
| 2 | One GPU pass per frame: fold crop, resize, images and captions into a single compositor like the preview's (today 6-9 full-frame passes) | -> ~5-6x | next after 4 |
| 3 | Two parts in parallel (the video chip runs several codec sessions) | -> ~7-9x (the chip's ceiling) | after 2 |
| 4 | **Smart re-export (render cache) + RANGE EXPORT** — key each part on ITS OWN content, cut parts at any time (not just clip seams), reuse every unchanged part. A range export is then "render the parts that cover the range" | a one-mask edit re-exports in minutes, not ~35; a range of already-rendered minutes is near-instant | next after Stage 1 — owner's pick for range export (see INBOX 2026-09-21) |
| 5 | Straight-copy stretches with no overlays/captions/effects (no re-encode) | ~50x on those stretches | later |
| 6 | Fast 720p draft preset | ~15-20x | later |

## Lane 2 — The foundation that gets expensive after users arrive

| # | Item | Why now |
|---|---|---|
| 2.1 | **THE VAULT** — projects move out of app-private storage into a folder the user picks | Today it has to work on one phone. After launch it has to work on every phone, forever. |
| 2.2 | Vault picker in first-run, with "I already have one" | The restore path is what proves the ownership promise |
| 2.3 | Migrate existing projects into the vault on first run | One-time, while JoyRaptor is the only user |
| 2.4 | Recordings land in the vault by default | Ends "where the hell is my screen recording" |
| 2.5 | Always copy on import, silently. No question. | The alternative breaks projects weeks later |
| 2.6 | Graceful "vault unavailable" state | SD card popped out must not look like data loss |
| 2.7 | Fix audio clip IDs regenerating on save (STATE section 9, defect 1) | Data integrity, and it undermines portability |
| 2.8 | Agree the pack folder contract (contract only, no manager UI) | Free now. Four asset systems later if we skip it. |

## Lane 3 — The AI, tightened

| # | Item | Size |
|---|---|---|
| 3.1 | Default model off openrouter/auto | One line. Currently bills every new user on message one. |
| 3.2 | Cap the tool-calling loop; raise the reply limit to 4096 | Small |
| 3.3 | Encrypt the stored API key | Small |
| 3.4 | Rename ai_merge_transcript | Trivial |
| 3.5 | **One-tap buttons for tools that already exist** — Clean up audio, Make chapters, Cut the ums | Small, and the highest value-per-hour item on this page |
| 3.6 | Streaming replies instead of a frozen "Thinking…" | Most of what makes an AI feel good |
| 3.7 | Fix the three tools that only see the video track | One file, three functions, one at a time |
| 3.8 | Split the AI networking behind one seam | Needed for everything after. Ship two providers: OpenRouter and a custom URL box. |
| 3.9 | **Share-in** — accept an image or video from the Android share sheet, straight onto the timeline | The headline. Works with any AI app, no key. |
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
