# Future plans, owner rulings and wanted features (the ledger)

> **The lead keeps this file. Nothing in it is ever deleted.** When a plan becomes a job, its row gets the job id;
> when it is built, the row says where and links the STATE row. A plan that leaves the roadmap comes HERE, never
> into the void. First built from a full sweep on 2026-10-28 (below), after the 10-27 roadmap rewrite dropped some.

**Why this file exists.** The roadmap was rewritten on 10-27 and some of the owner's plans fell out of it
(the forensics "Story Board", auto-markers, Remote and Smart Detection as launch features). This file
collects every future plan, owner ruling and wanted feature found in the repo, so none is lost. It started as a
draft for the project lead. The lead promotes items into `ROADMAP.md`,
job cards or a wing roadmap, and fixes the docs that are wrong.

**Sources swept (read-only):** `tasks/archive/ROADMAP_20261015.md`, `tasks/ROADMAP.md`, every job card,
`DESIGN_JOY_CREATOR.md`, `EVAL_20260701_joy_creator.md`, `LAUNCH_STRATEGY.md`, `ASSETS_WISHLIST.md`,
`AUDIT_UNFINISHED_20260726.md`, `INBOX.md`, `ORPHANS.md`, `RULINGS_NEEDED.md`, `STATE.md` (🔵 ⚪ 🟠, §9, §10),
`STUDIO_PLAN.md`, `NAV_SPEC.md` / `NAV_PLAN.md` (both untracked in git), the two Studio UI handoffs,
`SPEC_20261013_FILE_MANAGEMENT.md`, VisLab spec / brief / todo / roadmap, the JoyBrush roadmap and spec list,
every `PLAN_*`, `SPEC_*`, `RESEARCH_*` and `feature-*` file at `tasks/` root (title and status skim), a grep of
every owner-ruling line in `tasks/*.md` and `tasks/jobs/*.md`, `road_map.md`, `handoff.md` (grep), `LEDGER.md`
(grep), `todo.md`, `DOCKET_20260829.md`, `PRIORITY_20260806c.md`, `PLAN_AVATAR_STUDIO.md`, `PUPPET_FINISH_LEDGER.md`,
`OUTREACH_ANONFADED.md`, `DEPOLITICIZE_INVENTORY_20260712.md`, and a grep of `tasks/archive/*.md` for
"later / owner wants / wishlist / after launch". Standing rulings also come from the lead's auto-memory index
(`MEMORY.md`, outside the repo), marked "memory".

**Code checks:** `git grep` over `app/src/main`, `studiokit`, `joybrush`, `joybrush-android` at the
`joy-creator` checkout of 10-28, plus `git log --all --grep`. "Not built" means the grep found nothing; it is
a strong hint, not a proof. "Unclear" means I could not settle it from the code.

**Kinds:** OWNER RULING = a decision the owner made · PLAN = the owner wants it built (or a spec he approved) ·
IDEA = inbox / maybe / brainstorm · DECISION OWED = a question only the owner can answer, not in today's S1–S20 list.

**Counts (this draft):** 94 owner rulings (93 in Part 2, plus L4) · 55 plans and 24 ideas not in today's
roadmap · 24 owner decisions still owed (§2.12; 3 of them also appear as C2, S16, O5).
**Not in today's roadmap: 106 items** = Part 1's 85 rows (55 plans, 24 ideas, 3 decisions, 3 rulings the
roadmap does not carry: L4, F1, F6) + the 21 further decisions in §2.12. Part 2 also marks 7 rulings ⚠️ at risk.
Already captured items are listed compactly in Part 3 (not counted).

---

## Part 1: Not in today's roadmap (at risk)

### 1.0 The top 25, most important first

| # | Item | Why it matters | Detail |
|---|---|---|---|
| 1 | **Remote and Smart Detection are launch features** (owner ruling 10-17) | Today's S20 recommends cutting Forensics/motion detection from the Play build, and the After-launch list has "Remote promoted": both contradict the ruling | [1.9 F1](#19-remote-detection-forensics) |
| 2 | **Play's 200 MB app-bundle limit is unmeasured** (ffmpeg-kit, OpenCV, TFLite, MediaPipe, Vosk, whisper) | Can block the store upload outright | [1.10 B5](#110-store-website-business) |
| 3 | **Story Board**: the forensics evidence board reskinned as a pin/tag/sequence board that hands a rough cut to Studio | A named owner vision; no code, no row | [1.9 F2](#19-remote-detection-forensics) |
| 4 | **Auto-markers**: motion/noise/event logs become chapters and jump-markers on long recordings | Named in the owner's vision; timeline marks now exist to receive them | [1.9 F3](#19-remote-detection-forensics) |
| 5 | **Re-run the 41-check device sweep** (14 features never looked at) | Old roadmap's "highest value per hour"; dropped | [1.2 S1](#12-studio) |
| 6 | **Navigation principles from the 09-22 nav spec**: search palette, live strip, hold-to-fan floor | The owner's decided nav design; none in DOOR-2 | [1.4 L1–L3](#14-lobby-and-first-run) |
| 7 | **Pack folder contract** (contract only, before launch) | Old roadmap: "free now, four asset systems later if we skip it"; only the after-launch pack manager survived | [1.3 F-1](#13-library-and-files) |
| 8 | **Sprites have no row in Add asset** | The owner looked there and thought sprites were lost (09-12); still true in code | [1.5 A1](#15-animation-sprites-avatar) |
| 9 | **App-wide hover-label sweep** | Owner principle 09-12; only Joybot's chat is in a card | [1.11 O1](#111-other-cross-cutting) |
| 10 | **Remote phone as a second camera / multicam** (archetype E) | The owner's "capture superpower"; only a vague "multi-camera" after launch | [1.9 F4](#19-remote-detection-forensics) |
| 11 | **JoyBrush puppet and character boards (JB-7.01 to 7.04)** | Draft specs, no JoyBrush roadmap row, nothing built | [1.7 J1](#17-joybrush-planned-outside-its-roadmap) |
| 12 | **Studio primitives: Path object, text on a path, per-letter channels, paths as masks/motion paths** | The Studio lead's P3 plan from the owner's asks (09-24) | [1.2 S8](#12-studio) |
| 13 | **One authority for timeline time to source time** | "The single most valuable structural fix left"; four disagreeing copies | [1.2 S6](#12-studio) |
| 14 | **One snapping authority, app-wide** (owner: "fiddly") | Magnet built; playhead-to-keys / object alignment not | [1.2 S7](#12-studio) |
| 15 | **Business items from the launch strategy**: letter to the FadCam author, Joy Creator's own donation links and prompt, trademark search, YouTube plan | None is in SHOW-1..3 | [1.10](#110-store-website-business) |
| 16 | **24 owner decisions owed that are not in S1–S20** (nav D1–D7, 08-21 rulings, visual rulings, Library choices) | Unanswered questions block builds silently | [2.12](#212-decisions-still-owed-not-in-todays-s-list) |
| 17 | **Hide unproven (🟡) features from the launch UI** | Old roadmap 4.4; only Avatar survived (ANI-1) | [1.4 L5](#14-lobby-and-first-run) |
| 18 | **Dual-stream recording finish** (webcam file never rolls over on long recordings) | Spec'd, half-built | [1.1 C1](#11-capture-and-recording) |
| 19 | ~~Video row's own audio sub-drawer~~ **Built** (07-11, `a91b01921`: double-tap a main-track clip, its sound slides out with its waveform; owner confirmed 10-28) | Only the transcript under it is unconfirmed | [1.2 S4](#12-studio) |
| 20 | **Tablet layouts** | Owner: "phones and tablets both"; no `sw600dp` layouts exist | [1.11 O3](#111-other-cross-cutting) |
| 21 | **VisLab element packs from other people** | STATE says "not on the VisLab roadmap yet" | [1.6 V1](#16-vislab) |
| 22 | **Joybot inbox extras** (skins, AI launch buttons, prompt packing, "notices" buttons, per-project chat log) | Not in BOT-1..6 | [1.8](#18-joybot-and-ai) |
| 23 | **Paste a picture from the clipboard** (third of the owner's "three ways in") | Generate and Share are built; Paste only reads text | [1.3 F-4](#13-library-and-files) |
| 24 | **Annotation becomes a JoyBrush layer** | Only in S20's recommendation; not on the JoyBrush roadmap | [1.1 C4](#11-capture-and-recording) |
| 25 | **Export speed: the one-pass GPU stage and the owner's "one tap, fast" ruling** | Old roadmap 1.9 had a whole speed roadmap; today's has none | [1.2 S17](#12-studio) |

Columns below: **What** · **Source** (file:line, date) · **Kind** · **In code today** (one piece of evidence) ·
**Suggested home**. "Roadmap" notes say where today's roadmap touches it partly.

### 1.1 Capture and recording

| # | What | Source | Kind | In code today | Suggested home |
|---|---|---|---|---|---|
| C1 | Dual-stream (screen + raw webcam) finish: webcam file segment rollover, a linked badge on paired clips, trim mirroring across a speed mismatch, split mirroring test | `AUDIT_UNFINISHED_20260726.md:428-433`; `feature-dual-stream-recording-spec.md:89` | PLAN | Partly: rollover not built (`fadrec/encoding/WebcamEncoderPipeline.java:45` "v1 limitation") | After launch; a new Capture job (none exists) |
| C2 | Bits gadgets: 9 "coming soon" stubs (compass, sound meter, level...) cut or commit; FadMic third mode cut or commit | `NAV_SPEC.md:140-143` D2, §9; `NAV_PLAN.md:115` | DECISION OWED | Stubs still listed (`ui/settingshub/SettingsKey.java`, `SettingsHomeFragment.java`) | FREE-1 (owner call) |
| C3 | Every FadCam screen gets a new way in without the old nav bar; bring back the storage "radio" widget, re-themed | `INBOX.md:37-41` (09-24) | PLAN | Unclear (`ui/utils/StorageProgressRingView` used by Home and Remote) | DOOR-1 findings → UI-1 |
| C4 | Replace FadRec Annotation (~18,000 lines) with a JoyBrush layer | `ROADMAP.md:92` S20 (lead's recommendation 10-28) | IDEA | Not built (`fadrec/services/AnnotationService` is FadCam's) | JoyBrush roadmap (add a row) + FREE-1 |
| C5 | Visualiser while recording uses VisLab looks; one style library for Studio, recorder and VisLab | `VISLAB_BRIEF.md:24` (10-16); `VISLAB_SPEC.md:17-18` | PLAN | Partly: floating visualiser over any app built (VISLAB_SPEC §8); recorder's `visualizer/LiveVisualizer` is FadCam's | VisLab roadmap; BOT-4 "Capture" |
| C6 | Photo capture in-recording still-grab and headless both kept; screenshot needs designed states (it uses the accessibility trigger, which leaves the Play build) | `NAV_SPEC.md` §9 ("Photo = DUAL path", "Screenshot") | PLAN | Built (FadCam) | SHOW-1 (what replaces screenshots in the Play build) |

### 1.2 Studio

| # | What | Source | Kind | In code today | Suggested home |
|---|---|---|---|---|---|
| S1 | Re-run the 41-check device sweep and look at the 14 never-seen features: keyframe shape glyphs (ramp direction), A/V sync calibration tab, caption layers, amber preset keys, Fit/Fill, image opacity fade handles, sticky bookend keys, export GL frames timing/PSNR, transcript source affordance, opacity key delete, caption font import, caption Fit tab, horizontal reflow in portrait, the "~1:03 playback ceiling" | `ORPHANS.md:32-61, 94-95`; old roadmap 0.7 (`archive/ROADMAP_20261015.md:55`) "highest value per hour" | PLAN | Unclear: device sweeps 1-5 ran since (`tasks/audits/`), the 41-check list was never re-run whole | STU-1 |
| S2 | Loop / ping-pong: 7 acceptance checks never run (frame inversion, no black, degrade path, reverse-leg audio, >30 s guard, readout bubble, export parity). Owner: do it last | `ORPHANS.md:87`; `PLAN_LOOP_PINGPONG.md:110-121`; `AUDIT_UNFINISHED_20260726.md:653` | PLAN | Built, unverified | STU-1 (last) |
| S3 | Object time-scrubber for sprites, audio and PiP (text only today) + 8 UI gaps | `ORPHANS.md:86`; `SPEC_OBJECT_TIME_SCRUBBER.md` | PLAN | Partly (text overlays only, per ORPHANS) | STU-4 or after launch |
| S4 | A video row gets an expandable audio sub-drawer (its waveform and transcript under its picture tape) | `SPEC_NEUTRAL_SUBSTRATE.md:78-85` (owner, 07-25); `handoff.md:188` | ✅ BUILT (owner 10-28; `a91b01921`, 07-11) | Partly: double-tap on the main clip opens a "sound strip" (`STUDIO_PLAN.md:95`, 4032f7b3) | STU-4 (check whether the sound strip is it) |
| S5 | Serious audio editor too: blank project, an audio spine, audio lanes in the mini-map in their own colour | `RULINGS_NEEDED.md:113-121` (08-21) | PLAN | Partly: blank project built (`SPEC_AUDIO_UX_V1.md:498` G21, audio test projects exist); mini-map colour unclear | STU-1 / after launch |
| S6 | One authority for "timeline time to source time" (four copies disagree); harness tests first, then merge | `RULINGS_NEEDED.md:96-109` | PLAN | Not built (no shared class found) | After launch, its own deliberate session |
| S7 | One snapping authority app-wide: the playhead snaps to keys, clip edges, markers and item boundaries, one tolerance; other-object alignment on the canvas | `INBOX.md:391-414` (owner 09-15: "fiddly"); `STUDIO_PLAN.md:35` #20 | PLAN | Partly: the magnet and Snapping panel built (`STUDIO_PLAN.md:34` #19, `tools/SnapSettings`); object alignment "🔨" | STU-4 |
| S8 | Primitives that compose: a Path object (presets + stylus freehand, simplified on release), text on a path, per-letter channels (tracking, squash, offset along path), paths as masks and motion paths, rigging "that feels like puppetry" (research Moho/Toon Boom/Spine/Rive/Cascadeur first) | `STUDIO_PLAN.md:50-70` (09-24) | PLAN | Not built (no path object or letter tracking in `ui/faditor`; "text on a path" only in FadRec annotation) | After launch (new STU job) |
| S9 | Text box sizing: Fit mode (text scales to the box) and side-handle drag (Auto width and Wrap are built) | `STUDIO_PLAN.md:45, 97-98` | PLAN | Partly (1ad0851c wrap verified) | STU-4 |
| S10 | Parameter drivers: any knob driven by any object's property (Studio and VisLab) | `STUDIO_PLAN.md:111`; `VISLAB_TODO.md` G7 ("still later") | PLAN | Partly: VisLab Place follows a proxy (VisLab R8) | After launch |
| S11 | One slider row everywhere (`[icon][title][slider][value][key]`) in Audio, FX, Puppet, Mask, Grade and A/V-sync drawers; VisLab's snap slider (ghost of the start value, snaps to 0/45/90) proposed app-wide | `STUDIO_PLAN.md:24` #9; `VISLAB_TODO.md:208` G1 | PLAN | Partly (PipDrawerTabs + VisLab KnobPanel) | STU-4 |
| S12 | Double-tap toggles a drawer closed, generalised from the audio drawer to every double-tap-opens-a-drawer path | `SPEC_AUDIO_UX_V1.md:480` G3 (owner 08-23) | PLAN | Partly (audio and main clip, `STUDIO_PLAN.md:95`) | STU-4 |
| S13 | Bend on video (PiP and the spine); mesh bend and puppets on PiP, text and spine | `STATE.md:138, 141`; `SPEC_20260915_PUPPET_UI.md:310` | PLAN | Not built (🔵); STATE §5 line 197 also calls sprite bend unbuilt while §2 line 136 says 🟢: fix STATE | After launch. Roadmap's "bend on sprites" line is stale |
| S14 | Motion blur: velocity-based directional blur (practical); true accumulation blur as an export-only option | `archive/HANDOFF_20260821_STATE.md:180-188` (owner brainstorm 08-22) | IDEA | Not built (only the `tangentMotionBlur` transition) | After launch |
| S15 | A failure report that can leave the phone (what leaves needs the owner's ruling); the structured local record exists | `SPEC_20260824_VERIFIED_FIXES.md:200-212` | IDEA | Not built (no send path) | After launch (privacy: SHOW-2) |
| S16 | Multi-select fade/trim: same delta, stop at the first limit, one undo | `SPEC_20260829_FADE_KNOBS.md:160-177`; `RUNBOOK.md:234` ("not decided") | DECISION OWED | Not built (`REPORT_20260829_FADE_KNOBS.md:33`) | STU-4 after the ruling |
| S17 | Export speed roadmap: one GPU pass per frame (stage 2a built db486113, device test owed); the owner wants long projects to finish in one tap, fast, no ranged-export workaround | `archive/ROADMAP_20261015.md:70-89` (owner 09-23); `HANDOFF_20260923_export.md:49-51` | PLAN | Partly (stages 0-4 built, 6 Draft chip built 1152b27a) | STU-1 |
| S18 | Media-browser pin (untouched since the fork; owner wants it revisited with bundling) | `ORPHANS.md:108`; `DOCKET_20260829.md:116` | PLAN | Not built (no pin code found) | FILE-1 |
| S19 | Per-word rich text inside a caption cue ("bold reference, plain body") | `ORPHANS.md:107`; `DOCKET_20260829.md:117` | IDEA | Unclear (word styling works in text boxes, `STUDIO_PLAN.md:101`) | After launch |
| S20 | Playhead behaviour rework (KineMaster-style, 10-item checklist, bookmark persistence) | `ORPHANS.md:104`; `PLAYHEAD_KINEMASTER_20260719.md:96-110` | PLAN | Partly (bookmarks now in STU-3) | STU-4 |
| S21 | PiP audio gaps: preview plays at most one PiP's sound; no volume envelope on PiP export; loop extension not reflected | `AUDIT_UNFINISHED_20260726.md:470-473`; `SPEC_PIP_AUDIO.md:114-126` | PLAN | Unclear | STU-1 |
| S22 | Save-as-preset as a standing pattern, tool by tool | `road_map.md:252-253` | IDEA | Partly (colour-grade presets 0f74e63) | After launch |
| S23 | Low-bandwidth export with a real H.264 Baseline profile (needs a media3 fork patch) | `road_map.md:41-49`; `H264_BASELINE_PROFILE_FINDING_20260714.md` | IDEA | Not built (720p+Low chip exists) | After launch |
| S24 | Visualizer studio "Tier 3" (custom HTML visualisers, ffmpeg templates) | `ORPHANS.md:105`; `feature-visualizer-studio-spec.md` | IDEA | Likely superseded by VisLab parts | VisLab lead to confirm or close |
| S25 | Before video generation: the 5-minute Media3 check (silent clip before a clip with sound); if it fails, generated video needs a silent track on import | `INBOX.md:160-164`; `archive/ROADMAP_20261015.md:151-152` | PLAN | Not checked | Add to the After-launch "Video generation" line |
| S26 | Memory: the app holds ~534 MB resident; trim caches on `onTrimMemory` / background | `INBOX.md:168-174` (09-10) | IDEA | Not built (no `onTrimMemory` under `ui/`) | After launch (perf) |

### 1.3 Library and files

| # | What | Source | Kind | In code today | Suggested home |
|---|---|---|---|---|---|
| F-1 | Agree the pack folder contract now (contract only, no manager UI) | `archive/ROADMAP_20261015.md:111` (old 2.8, before launch) | PLAN | Not built (no pack manager; `GlExternalTransitions.scanAndRegister` is already a working pack loader, STATE §2) | FILE-1, before launch |
| F-2 | Library follow-ups: voiceovers shown with their project; safe rename/trash helpers for sprite sheets and characters; a Library row for Supplies/presets | `HANDOFF_STUDIO_UI_20261017.md:33`; `todo.md:51` | PLAN | Unclear / not built | FILE-1 / DOOR-4 |
| F-3 | Project tags (with rename, thumbnails and search, which are built) | `DESIGN_JOY_CREATOR.md:62` (07-02) | IDEA | Not built (no tag field found) | After launch |
| F-4 | "Getting pictures in, three ways, zero file management": Generate, **Paste from the clipboard**, Share; one "Add image" entry | `INBOX.md:25-31` (09-24) | PLAN | Partly: generate (`ai/joybot/JoybotPictures.java`, bfce16319) and share-in built; paste reads text only (`FaditorEditorActivity.java:47937`) | DOOR-4 |
| F-5 | Paid cloud sync / project backup (Tier 2, only with income) | `LAUNCH_STRATEGY.md:123` | IDEA | Not built | Parked with hosted credits |
| F-6 | Cross-device sync (FadCam's SyncQueue) and an optional "verified original" integrity badge, both parked | `DESIGN_JOY_CREATOR.md:74` | IDEA | Not built (only FadCam's `forensics/data/local/dao/SyncQueueDao`) | Parked; FREE-1 verdict |

### 1.4 Lobby and first run

| # | What | Source | Kind | In code today | Suggested home |
|---|---|---|---|---|---|
| L1 | Search palette: swipe down to find every room, setting, project, recording and action, offline, recency-ranked | `NAV_SPEC.md:41-42` P2, row 35 (09-22) | PLAN | Not built (no palette class; `git log --all` none) | DOOR-2 |
| L2 | Live strip: one persistent strip for recording / export / AI / transcribe, survives leaving the editor | `NAV_SPEC.md:55-56` P9, row 36 | PLAN | Partly: the lobby's export hairline (STATE §7.5) | DOOR-2 |
| L3 | Hold-to-fan floor words: hold a floor word, hero or tile and its children arc out under the thumb | `NAV_SPEC.md:44-45` P4 | PLAN | Not built | DOOR-2 |
| L4 | Durable modules rule: SpriteLab, VisLab, puppet/avatar rigs are stores outside the editor, two doors each, durable across projects | `NAV_SPEC.md:50-52` P7 | OWNER RULING (design) | Partly: Avatar has a library mode (`AvatarStudioActivity`), sprites are still project-locked (STATE §9 #13) | DOOR-4 / ANI-1 |
| L5 | Hide Avatar and other 🟡 features from the launch UI (mesh warp ships visible) | `archive/ROADMAP_20261015.md:135` (old 4.4) | PLAN | n/a | DOOR-2 (ANI-1 has only Avatar) |
| L6 | Asset-blocked: empty-room heroes, a first-run slideshow, Joybot's face, a display typeface; empty-state illustrations (empty library, no projects, empty story board, empty trash), a notification glyph, a wordmark, a watermark mark | `STATE.md` §7.5 "Asset-blocked"; `ASSETS_WISHLIST.md:6-28` | PLAN | Partly: designed placeholders (`LobbyHeroPlaceholder`), two icons in the repo root | SHOW-3 / DOOR-3 (owner art) |
| L7 | Projects merge: the Studio list and the FadRec projects dialog become one | `NAV_SPEC.md:70-71` rows 9-10 | PLAN | Unclear | FREE-1 |

### 1.5 Animation, sprites, avatar

| # | What | Source | Kind | In code today | Suggested home |
|---|---|---|---|---|---|
| A1 | Put Sprites / sprite sheet in **Add asset**, and an import button in the sprite drawer's empty state | `INBOX.md:90-95, 310-317` (owner, 09-12) | PLAN | Not built: `AddAssetBottomSheet.java:143-201` has no sprite row | ANI-1 (one row) |
| A2 | Shareable character format: sheet + JSON + rig as one bundle; "a character sheet is more valuable than a project" | `INBOX.md:178-180` | IDEA | Not built | After launch (with the sheet library) |
| A3 | SpriteLab extras: animated GIF/WebP preview per clip; onion-skin anchor ghost; sprite sheet from a video (sampler + `SequencePacker`); swipeable panes on the phone Lab | `INBOX.md:186-193, 231-232` | IDEA | Not built (no sampler, no anchor ghost; `SequencePacker` exists) | ANI-1 / after launch |
| A4 | SpriteLab still owed after the 09-13 ruling: multi-sheet merge and the Sources rail, numbered frame export, a `bakedFrom` field | `INBOX.md:358-360, 385-387` | PLAN | Unclear (drag-to-reorder was built, `LEDGER.md:3512`) | ANI-1 |
| A5 | Bug: the background-key picker samples the wrong pixel after a reorder | `INBOX.md:486-504` | PLAN (fix) | Not fixed (as filed) | ANI-1 |
| A6 | Desktop sprite editor hosted on joycreator.cc, landing sheets in a shared Assets library | `INBOX.md:199-207` (09-11) | IDEA | Web tool exists locally (`tools/spritelab/`), not hosted | SHOW-3 |
| A7 | Avatar ideas parked: viseme calibration ("say Ah/Eh/Ee..."), a behaviour hotbar while recording, mesh density options, MobileSAM auto-cut-out, AI 3/4 views | `PLAN_AVATAR_STUDIO.md:268-292` | IDEA | Partly ("life" signals built: `avatar/LifeSignals.java`) | After launch |
| A8 | Puppet: a per-key easing override, only if the fitted curve guesses wrong | `SPEC_20260915_PUPPET_UI.md:316-319` | IDEA | Not built | After launch |

### 1.6 VisLab

| # | What | Source | Kind | In code today | Suggested home |
|---|---|---|---|---|---|
| V1 | Element packs from other people (looks and parts as plugins: a Supplies folder with its own `types.json` + shaders, off until turned on, test-compiled and timed) | `VISLAB_TODO.md:194-203` (owner question 10-22); `STATE.md:328-329` ("not on the VisLab roadmap yet") | PLAN | Not built | VisLab roadmap (add a row, after launch) |
| V2 | Lobby Viz Lab hero shows the last look; Recent work shows looks | `VISLAB_SPEC.md:243` (❌); `jobs/DOOR-2_lobby_landing.md:24` | PLAN | Not built (`LobbyFragment.java:1628-1638` per DOOR-2) | DOOR-2 |
| V3 | Studio's per-letter text animations offered as VisLab letter styles | `VISLAB_TODO.md:300-302` | IDEA | Not built | VisLab roadmap |

### 1.7 JoyBrush (planned outside its roadmap)

| # | What | Source | Kind | In code today | Suggested home |
|---|---|---|---|---|---|
| J1 | Puppet board (pins over the art), rig test through Avatar's solver, export an avatar, character board (expressions, mouths, parallax, look-at) | `tasks/joybrush/specs/JB-7.01..7.04` (draft, 09-29); `jobs/ANI-1_puppets_and_sprites.md:41` points at them | PLAN | Not built (no commits; JoyBrush roadmap has 0 mentions of JB-7) | JoyBrush roadmap PORTFOLIO-AUDIT → a row |
| J2 | Annotation as a JoyBrush layer (see C4) | `ROADMAP.md:92` | IDEA | Not built | JoyBrush roadmap |

### 1.8 Joybot and AI

| # | What | Source | Kind | In code today | Suggested home |
|---|---|---|---|---|---|
| B-1 | Joybot skins, several visual "tastes" | `INBOX.md:112-115` (09-09) | IDEA | Not built | BOT-5 |
| B-2 | Launch buttons for AI apps (ChatGPT, Gemini, Claude, OpenArt, Copilot, "add your own"), app first, browser fallback | `INBOX.md:149-150` | PLAN | Not built (providers only, `ai/joybot/AiProviders.java`) | BOT-4 |
| B-3 | Prompt packing: before opening an outside AI app, copy a packed prompt (your words + canvas size, aspect, "no watermark") | `INBOX.md:145-148` | PLAN | Partly: copy-prompt for slides (`SlideImportBottomSheet`) | BOT-4 |
| B-4 | Joybot notices rather than interviews: the first share-in from an app offers to keep a button for it | `INBOX.md:151-153` | IDEA | Not built | BOT-4 |
| B-5 | A per-project chat log (trial first: useful or clutter?) | `INBOX.md:307-309` (09-12) | IDEA | Not built | BOT-3 |
| B-6 | The full media seam: the AI can send pictures, sound and files and receive pictures and video | `INBOX.md:139-141` | PLAN | Partly (vision + pictures built) | BOT-4 |
| B-7 | Text to speech for voiceovers, voice cloning, voice enhancement (discovery questions) | `STATE.md` §10 | IDEA | Partly: read-aloud only (`ai/joybot/JoybotVoice.java`) | After launch |
| B-8 | Joy Creator on PC and Mac, all of them talking to each other | `jobs/BOT-6_bring_your_own_ai.md:28-29` (owner 10-28) | PLAN | Not built | After launch (iOS stays parked) |

### 1.9 Remote, detection, forensics

| # | What | Source | Kind | In code today | Suggested home |
|---|---|---|---|---|---|
| F1 | **Remote / Server Room and Smart Detection ARE launch features** | `HANDOFF_STUDIO_UI_20261017.md:23` (owner 10-17); `STATE.md` §7.6 | OWNER RULING | Restyled and agent-checked 10-20 (`ui/home`, `strings_remote.xml`, `forensics/ui/view/SlantChip`) | **Conflicts with ROADMAP S20 (cut Forensics from the Play build) and the After-launch "Remote promoted" line.** Put it to the owner with S19/S20 |
| F2 | Story Board: the evidence board reskinned to pin, tag and sequence clips, assets and notes before editing, then hand a rough cut to Studio ("no mobile competitor has this") | `DESIGN_JOY_CREATOR.md:67-71` (07-02); `NAV_SPEC.md:23` (vocabulary), row 23, §9 ("rough-cut handoff = MISSING") | PLAN | Not built (only `forensics_board_*` drawables) | FREE-1 verdict + DOOR-4; after launch |
| F3 | Auto-markers: motion/noise detectors and the event log mark interesting moments on long recordings as chapters and jump-to marks in the timeline | `DESIGN_JOY_CREATOR.md:72-73`; `NAV_SPEC.md` row 23 | PLAN | Partly: transcript chapters only (`AIToolExecutor.java:123` `auto_chapters`) | STU-3 follow-on; after launch |
| F4 | Remote as a capture superpower: pull the remote stream in as a second camera; the multicam archetype (security / creative multi-camera, motion triggers, markers, Story Board review) | `DESIGN_JOY_CREATOR.md:63-66`; `NAV_SPEC.md:15`, §9 ("multicam ingest/trigger have no native row"); `INBOX.md:131-132` | PLAN | Not built (no remote-as-source code) | After launch ("multi-camera" line, made specific) |
| F5 | Motion/noise-triggered capture as a Capture mode | `DESIGN_JOY_CREATOR.md:60` | PLAN | Built (FadCam `motion/` package, `MotionSettingsRepository`), unpromoted | FREE-1 / S20 |
| F6 | Forensics rule: hide/park behind a settings toggle, never delete; repurpose piece by piece | `DESIGN_JOY_CREATOR.md:75` | OWNER RULING | Followed (gated by the Smart Detection toggle, `DEPOLITICIZE_INVENTORY_20260712.md:19`) | FREE-1 must respect it |

### 1.10 Store, website, business

| # | What | Source | Kind | In code today | Suggested home |
|---|---|---|---|---|---|
| B1 | Send the letter to the FadCam author (drafted 08-06, "highest leverage, ~5 min, do first") | `OUTREACH_ANONFADED.md:3` ("DRAFTED, not sent"); `LAUNCH_STRATEGY.md:198-199, 242` | PLAN | Not sent | Owner action; with FREE-1 and S9 |
| B2 | Joy Creator's own donation links (GitHub Sponsors, Liberapay, its own Ko-fi) and the approved in-app prompt (after a success such as the 5th export, at most monthly, never blocking) | `LAUNCH_STRATEGY.md:92-101` | PLAN | Not built (`ui/KoFiSupportBottomSheet` is FadCam's and S19 cuts it) | SHOW-2 |
| B3 | YouTube as the distribution engine: "how do I X on mobile" tutorials answered by Joy Creator | `LAUNCH_STRATEGY.md:166-170` | PLAN | n/a | SHOW-3 |
| B4 | Trademark: search that "Joy Creator" is free (Play, stores, USPTO) before relying on the name; register later | `LAUNCH_STRATEGY.md:181-183, 243-244` | PLAN | `TRADEMARK.md` exists, needs a contact email | SHOW-2 |
| B5 | Measure the app bundle against Play's 200 MB limit | `LAUNCH_STRATEGY.md:245-246` | PLAN | Unmeasured | SHOW-1, before launch |
| B6 | ffmpeg-kit is retired upstream: a replacement plan | `LAUNCH_STRATEGY.md:247` | PLAN | Vendored aar (`app/build.gradle.kts:338` `ffmpeg-kit-full-6.0-2.LTS`) | After launch / FREE-1 |
| B7 | Replace the fastlane listing (still "FadCam", "Background video recorder...") | `LAUNCH_STRATEGY.md:204`; `fastlane/metadata/android/en-US/title.txt` | PLAN | Not done | SHOW-2 |
| B8 | Secondary channels: r/androidapps, r/videoediting, XDA, F-Droid, Hacker News on a strong release | `LAUNCH_STRATEGY.md:171-172` | IDEA | n/a | SHOW-3 |

### 1.11 Other, cross-cutting

| # | What | Source | Kind | In code today | Suggested home |
|---|---|---|---|---|---|
| O1 | Hover-label sweep over every existing button (anything new ships with one) | `INBOX.md:236-245` (owner 09-12); `PUPPET_FINISH_LEDGER.md:68` C7; memory | PLAN | Partly (~220 controls 10-15, `jobs/BOT-2_joybot_everywhere.md:17`) | UI-1 (BOT-2 holds only Joybot's chat) |
| O2 | Theme work: prune 9 themes to Joy Creator + one dark alternative; a section accent per room (never a state colour); optional light mode later | `NAV_SPEC.md:143` D7; `INBOX.md:268-275`; `DESIGN_JOY_CREATOR.md:13, 22-26`; `ASSETS_WISHLIST.md:41` | PLAN / IDEA | Partly (room gradients in the lobby tokens, `NAV_SPEC.md:33-35`); no light mode | UI-1 |
| O3 | Tablet layouts (`sw600dp`): SpriteLab two-pane, the app | `INBOX.md:212-215` ("phones and tablets both"); `PRIORITY_20260806c.md:300`; `LEDGER.md:3690` | PLAN | Not built (no `sw600dp` resources) | After launch |
| O4 | Take ideas (not code) from upstream FadCam, e.g. its fMP4 seek/finalisation work | memory (`joy-creator-upstream-fork-gap.md`) | IDEA | Not built | FREE-1 |
| O5 | De-branding leftovers: the FadSec flag timer accent (drop or neutral), the web dashboard and locales | `DEPOLITICIZE_INVENTORY_20260712.md:7, 55` | DECISION OWED | Partly | FREE-2 |

---

## Part 2: Owner rulings (every decision found, briefly; most are already followed)

"Today" says whether the current code / roadmap follows it. ✅ followed · ⚠️ contradicted or at risk · · not applicable yet.

### 2.1 The product and the promise

| # | Ruling | Source | Today |
|---|---|---|---|
| O-1 | The app is "Joy Creator"; "Studio" is only a descriptor; the launcher icon is the symbol, no text | `DESIGN_JOY_CREATOR.md:47-51` (07-02) | ✅ |
| O-2 | Free forever: no ads, no subscription, no watermark, no export paywall; AI is bring-your-own-key at zero margin; support unlocks cosmetics only | `ROADMAP.md:37`; `archive/ROADMAP_20261015.md:201-204` | ✅ |
| O-3 | Free of FadCam: take the ideas, rebuild, cut the tie | `jobs/FREE-1_fadcam_inventory.md:6`; memory | ✅ in M3 |
| O-4 | Order of work: finish every wing, wire them together, rebuild the front door, then show it | `ROADMAP.md:43-44` (10-27) | ✅ |
| O-5 | No political or activist identity; keep the long-press character mechanic and reskin it (later Joybot) | `DESIGN_JOY_CREATOR.md:28-34` | ✅ |
| O-6 | Five archetypes are all first-class (capture, animate, audio, organise/watch, multicam) | `NAV_SPEC.md:7-16` (09-22) | ⚠️ multicam has no job |
| O-7 | Two distribution channels: a clean Play build, a full GitHub/F-Droid build that keeps the privacy tools | `LAUNCH_STRATEGY.md:62-79, 234-236` (08-06); `STATE.md:113` | ✅ SHOW-1 |
| O-8 | Comply with the GPL rather than extract; clean-room of our own code rejected | `LAUNCH_STRATEGY.md:224-232` | ✅ |
| O-9 | Hosted AI credits only once donations cover hosting for 6 months; supporter purchase never unlocks export quality | `LAUNCH_STRATEGY.md:110-122` | ✅ parked |
| O-10 | The approved donation prompt: after a success, at most monthly, never blocking | `LAUNCH_STRATEGY.md:96-99` | · not built (B2) |
| O-11 | Remote and Smart Detection are launch features; VisLab is a launch feature | `HANDOFF_STUDIO_UI_20261017.md:23-24` (10-17) | ⚠️ see F1 |
| O-12 | Forensics: hide behind a toggle, never delete; repurpose (Story Board, auto-markers) | `DESIGN_JOY_CREATOR.md:75` | ⚠️ repurpose not planned |
| O-13 | Phone-first, never desktop-only; web tools are pilots | `STATE.md:221-228` (09-11); `INBOX.md:212-215`; memory | ✅ |
| O-14 | "Can this be done more simply?" is asked every time; the one-thumb gesture *rule* was rejected ("a rule might get in the way of the best option") | `INBOX.md:226-230` (09-12) | ✅ |
| O-15 | Every button carries a hover label | `INBOX.md:236-245` (09-12); memory | ⚠️ sweep unscheduled |
| O-16 | Check what exists before building; one user-facing surface per job; build modular, small files; keep legacy until the new one is better | memory; `SPEC_20261013_FILE_MANAGEMENT.md:24`; `EVAL_20260701_joy_creator.md:33-38` | ✅ |
| O-17 | Save the orphans: half-built means unfinished, not abandoned | `ORPHANS.md:6-8`; `STATE.md:35-44`; memory | ⚠️ ORPHANS.md is not linked from today's roadmap |
| O-18 | Finish everything shown: a dead knob is worse than a missing one | memory; `SPEC_AUDIO_UX_V1.md:35` (08-12) | ✅ |
| O-19 | Keep the old behaviour selectable when a tool is reworked | memory; `VISLAB_SPEC.md:156` | ✅ |
| O-20 | Merge timing is the lead's call by a safety rule | memory (10-27) | ✅ |

### 2.2 Look and feel

| # | Ruling | Source | Today |
|---|---|---|---|
| O-21 | Pink over the default red; frosted dark glass so the video stays visible | `DESIGN_JOY_CREATOR.md:14-15` | ✅ |
| O-22 | Big menus must not cover the timeline or the preview's core; the transcript side drawer is the gold standard | `DESIGN_JOY_CREATOR.md:36-44`; `VISLAB_SPEC.md:245-249` | ✅ |
| O-23 | The restyle is a de-branding; pure black on purpose (the project's colours pop); the see-through drawers are load-bearing | `INBOX.md:280-291` (09-12) | ✅ |
| O-24 | Prefers SpriteLab's emerald `#34d399` over Material green | `INBOX.md:263-265` | ✅ (tokens) |
| O-25 | Drawers never opaque (~55% see-through); Frost = blur only (API 31+) | memory; `STUDIO_PLAN.md:22-23`; `VISLAB_SPEC.md:247` | ✅ |
| O-26 | Greys fade, colour guides: old black, zinc greys, flat pills | memory | ✅ |
| O-27 | ON = solid cyan with the icon cut out, never outlined | memory; `HANDOFF_STUDIO_UI_20261017.md:3` | ✅ |
| O-28 | dp values stay as drawn (the Note 9's denser look) | memory | ✅ |
| O-29 | Slanted (sheared) chips are the signature look; gap 15% narrower | `HANDOFF_STUDIO_UI_20261017.md:30` | ✅ |
| O-30 | Lane B `#0B0B0E` at 4.3%; mini-map 11 dp | `POLISH_CHECKLIST.md:486, 673` | ✅ |
| O-31 | The colour helper is good: do not change it | `STUDIO_PLAN.md:128`; `jobs/STU-4_studio_sweep_leftovers.md:36` | ✅ |
| O-32 | Ambient extras: faint glows, no fps cost, Studio art at Studio's scale | memory (10-28); `VISLAB_ROADMAP.md:54` R16 | ✅ |
| O-33 | Design options come as a mix-and-match page; he picks, the picks become the spec | memory; `jobs/DOOR-2_lobby_landing.md:33` | ✅ |
| O-34 | Before and after shots for every changed screen | memory | ✅ |
| O-35 | Export icon approved; AMOLED true black kept | `STUDIO_PLAN.md:27-28` | ✅ |
| O-36 | The lobby must be fully polished before launch | `archive/ROADMAP_20261015.md:141` (10-13) | ✅ M2 |
| O-37 | New pill is Studio green; lobby "New" opens the Library on Projects with the new-project sheet; "Classic Records" | `HANDOFF_STUDIO_UI_20261017.md:26-27`; `jobs/DOOR-2_lobby_landing.md:27` | ✅ |
| O-38 | Old FadCam orphan classes go only with his OK, in one pass | `HANDOFF_STUDIO_UI_20261017.md:28` | ✅ S10 |

### 2.3 Studio

| # | Ruling | Source | Today |
|---|---|---|---|
| O-39 | Undo: one press, one step; a batch collapses to one undo | `STATE.md:146`; `SPEC_20260829_WORD_SYNC.md:108`; memory | ✅ |
| O-40 | Live keyframing while playing is his favourite thing: never add an arming step | `STATE.md` §5 "Live keyframing" | ✅ |
| O-41 | Full neutrality: any object on any layer | `SPEC_NEUTRAL_SUBSTRATE.md:51-56` (07-25) | ✅ |
| O-42 | Deleting a clip that items are anchored to asks the user; anchoring on by default | `PLAN_LAYERS_UX_ADDENDUM.md:216, 318` (08-03) | ✅ |
| O-43 | Spine drops: video and image payloads only; the dislodge gesture spec is binding | `PLAN_LAYERS_UX_ADDENDUM.md:40, 131, 137` (08-03/04) | ✅ |
| O-44 | Back-propagate the dwell-arming / landing language to trims, reorders and lane moves as deliberate follow-on work | `PLAN_LAYERS_UX_ADDENDUM.md:202-206` (08-03) | · unclear |
| O-45 | Gesture contract, resizable timeline, marquee multi-select, object linking, sprite lane thumbnails (co-designed) | `PLAN_GESTURE_CONTRACT_FINAL_20260706.md:1, 112-312` (07-06) | ✅ built |
| O-46 | Linking is move-only: trimming the parent does not trim children; owner's link answers supersede §4 | `SPEC_20260924_LINKING.md:123, 359` (07-19, 09-24) | ✅ |
| O-47 | A time-linked follower that would land on something drops to its own lane | `STUDIO_PLAN.md:87` | ✅ |
| O-48 | Opacity fades the clip, not the layers above | memory | ✅ |
| O-49 | Fades stack: the envelope multiplies the set volume | `SPEC_AUDIO_UX_V1.md:401` (08-23) | ✅ |
| O-50 | Voiceover's door is a mic on the transport row (pulsing red while recording); the link button manages linked items; relinking moves to the object menu | `SPEC_AUDIO_UX_V1.md:490-492` | ✅ built |
| O-51 | A blank project must be possible (audio-first users) | `SPEC_AUDIO_UX_V1.md:498` (08-23) | ✅ built |
| O-52 | Text animation default granularity is per letter (old projects too); "None" clears the timing | `STUDIO_PLAN.md:14`; `LEDGER.md:3778`; `RULINGS_NEEDED.md:63-71` | ✅ |
| O-53 | Image presets: pan/zoom without hand-keyframing; Fit/Fill "like desktop wallpaper"; opacity handles like the volume fade handles; presets reset, not stack | `SPEC_20260829_IMAGE_ANIM_PRESETS.md:17-139`; `SPEC_20260829_IMAGE_PRESETS_V2.md:18` | ✅ built |
| O-54 | Caption layers: two things on screen from the same audio | `SPEC_20260829_CAPTION_LAYERS.md:18` | ✅ built |
| O-55 | Word Sync: no "Done" between words; nudge scrubber a third the size; toasts for toggles; undo for nudges | `SPEC_20260829_WORD_SYNC.md:57-114`; `SPEC_20260830_WORD_SYNC_V2.md:46-186` | ✅ built |
| O-56 | Media import uses the app's own browser; image-as-overlay is a toolbox button | `SPEC_20260829_MEDIA_IMPORT.md:16`; `SPEC_20260829_QUICK_WINS.md:16` | ✅ built |
| O-57 | The slide object is authored with an AI (HTML) and "drops keyframes on the object"; no new timing system | `SPEC_20260828_SLIDE_OBJECT.md:82-93` | · parked (DOCKET: ask before resuming) |
| O-58 | Export: one-pass completion; ranged export is not an acceptable workaround for the long-export stall; much faster without losing quality; progress never lies | `HANDOFF_20260923_export.md:49-51`; `INBOX.md:506-507` | ⚠️ no speed row today |
| O-59 | Straight-copy export decided against (09-24) | `archive/ROADMAP_20261015.md:88` | ✅ |
| O-60 | Studio is to replace After Effects: everything keyframable, proxies drive things, adjustment layers carry effects | `VISLAB_ROADMAP.md:8-11` (10-22/23) | ✅ in progress |
| O-61 | Snapping needs work app-wide ("fiddly") | `INBOX.md:395-396` (09-15) | ⚠️ S7 |
| O-62 | Instanced copies ("twin badges, link states"): not building; if it returns, one "Apply to all copies" chip | `archive/HANDOFF_20260806_PICKUP.md:122-127` (08-06) | ✅ |
| O-63 | Loop / ping-pong verification goes last | `AUDIT_UNFINISHED_20260726.md:653` | ✅ |
| O-64 | GL transitions: he wanted the live A+B tier (done) | `GL_TRANSITIONS_HANDOFF_20260718.md:19` | ✅ |
| O-65 | Timeline marks design approved 10-23 | `SPEC_TIMELINE_MARKS_20261023.md:1` | ✅ STU-3 |

### 2.4 Library and files

| # | Ruling | Source | Today |
|---|---|---|---|
| O-66 | Uninstalling never loses work; one folder the user picks, near the top; folders you earn; the Obsidian model; share-in rule; terms Assets / Exports / Recordings / Supplies | `SPEC_20261013_FILE_MANAGEMENT.md:12-24` (10-13); memory | ✅ |
| O-67 | Choosing the folder is a required first step; never migrate later; earned folders only for clutter | `SPEC_20261013_FILE_MANAGEMENT.md:21-23` (10-14); memory | ✅ |
| O-68 | Big outside files are linked with a warning and a yellow dot; Copy / Move / Leave per file | `SPEC_20261013_FILE_MANAGEMENT.md:75-76` (10-14) | ✅ (share-in of big files still copies: FILE-1) |
| O-69 | Presets copy out of the app into Supplies; the user's copy wins; "Revert to default" with a warning; voiceovers live with their project; shared-in items filterable | `HANDOFF_STUDIO_UI_20261017.md:29`; `VISLAB_BRIEF.md:24`; memory | ✅ |
| O-70 | Share-in lands right after the file home exists, not before | `archive/ROADMAP_20261015.md:125` (10-13) | ✅ |
| O-71 | App ID not tied to the domain (candidates listed) | `SPEC_20261013_FILE_MANAGEMENT.md:111` | ✅ S1 |
| O-72 | Project folder renamed to `C:\+Projects\JoyCreator`; no "FadCam" folder left | `ROADMAP.md:74`; `jobs/HYG-4_rename_folder.md:51` | ✅ |

### 2.5 Animation, sprites, avatar

| # | Ruling | Source | Today |
|---|---|---|---|
| O-73 | SpriteLab reaches full parity on the phone; the sprite format holds everything SpriteLab authors (per-cell transforms) | `INBOX.md:212-225` (09-11); memory | ✅ |
| O-74 | A cell's name and alignment follow the drawing | `INBOX.md:319-321` (09-13) | ✅ built |
| O-75 | Warp/bend belongs to the sprite sheet, not the cell | `HANDOFF_STUDIO_UI_20261017.md:29` | ✅ |
| O-76 | SpriteLab: Joy Creator's purple not carried over; the value bubble floats 48 px above the finger; three columns on desktop | `SPEC_20260910_SPRITELAB_UI.md:14, 47, 88` | ✅ |
| O-77 | Cell naming "super powers the LLM side" | `LEDGER.md:3072`; `SPEC_20260910_SEMANTIC_CELLS.md:14` | ✅ |
| O-78 | Puppets: fitted easing ("identify what it's closest to"); the systematic finish sweep | `SPEC_20260915_PUPPET_UI.md:315`; `PUPPET_FINISH_LEDGER.md:3` (09-16) | ✅ |

### 2.6 VisLab

| # | Ruling | Source | Today |
|---|---|---|---|
| O-79 | Three places: the room, Studio, the screen recorder | `VISLAB_SPEC.md:11-18` (10-16) | ✅ |
| O-80 | Looks live in Supplies as the person's own files | `VISLAB_SPEC.md:78` (10-16) | ✅ |
| O-81 | Words are art (spirals, star-field flight, tunnel ticker, smoke), not captions | `VISLAB_SPEC.md:165-168` (10-16/17); memory | ✅ |
| O-82 | A lab of parts: everything composable, presets are just finished looks | `VISLAB_TODO.md:17` (10-18); memory | ✅ |
| O-83 | Integrate, do not override: VisLab looks are a section of the visualiser's Rolodex; a small editor in Studio + "Edit in VisLab" | `VISLAB_HANDOFF_20261018_STUDIO.md:78-90` (10-18) | ✅ |
| O-84 | Keep the curling smoke as it is | `VISLAB_TODO.md:68` (10-18) | ✅ |
| O-85 | Adjustment layers carry effects like After Effects (an empty layer whose effects change everything under it) | `VISLAB_TODO.md:292-294` (10-23) | ✅ |

### 2.7 JoyBrush

| # | Ruling | Source | Today |
|---|---|---|---|
| O-86 | Hold a brush to open its advanced settings, with a live tilt/pressure/speed preview | memory | JoyBrush roadmap |
| O-87 | The Atlas is the goal; never park it without his yes | memory (10-20) | JoyBrush roadmap |
| O-88 | On-canvas tools in the Concepts placement; an automatic guess always keeps a one-tap way back | memory | JoyBrush roadmap |
| O-89 | Prefers the sharper full-resolution watercolour pooling | `lessons.md:1054` | JoyBrush roadmap |

### 2.8 Joybot and AI

| # | Ruling | Source | Today |
|---|---|---|---|
| O-90 | Default model is the free router: "their first experience is free" | `archive/ROADMAP_20261015.md:117` (10-13) | ✅ |
| O-91 | Joybot = moods and places; the owner draws the sprite | memory; `jobs/BOT-5_joybot_character.md` | ✅ BOT-5 |
| O-92 | Bring-your-own-AI rules: nothing runs on our side; Wi-Fi or USB; iPhone-simple QR; both ways; build toward the desktop; harness-neutral; set up by talking ("so simple my grandma can use it"); tablets pair by QR; chat colours (your words grey, Joybot aqua-to-lime) | `jobs/BOT-6_bring_your_own_ai.md:12-29, 47, 74, 96`; `jobs/BOT-1_joybot_owed.md:40` (10-28) | ✅ BOT-6 |

### 2.9 Phone and process

| # | Ruling | Source | Today |
|---|---|---|---|
| O-93 | Never launch with `adb shell monkey`; the phone lock; Note 9 is the sandbox (full control, never uninstall or clear data), the Note 20 holds real projects; have him press Home before an install over an open drawing | `AGENTS.md` phone rule (10-19); `DEVICE_CONTROL_RUNBOOK.md:9` (10-14); memory | ✅ |

### 2.10 Ruling conflicts to settle

1. **O-11 vs S20:** Smart Detection (forensics, motion detection) ruled a launch feature on 10-17; S20 recommends cutting it from the Play build. Both can hold if the Play build keeps a slim Smart Detection without TensorFlow/OpenCV, or if the owner moves it to the full build: his call.
2. **O-11 vs the After-launch list:** "Remote promoted" sits after launch. The restored note already says the owner ruled it a launch feature; it needs a job row (DOOR-1/FREE-1), and S19 must spell out that LAN Remote stays.
3. **O-12 vs FREE-1:** the forensics verdict should be "repurpose" (Story Board, auto-markers), not only "cut".
4. **O-17:** `ORPHANS.md` is not linked from today's roadmap, so its rescue list is invisible.

### 2.11 Stale STATE rows found on the way (for HYG-5)

- `STATE.md:378` "Image generation 🔵": Joybot already makes pictures (`ai/joybot/JoybotPictures.java`, bfce16319, 10-15).
- `STATE.md:197` "Bend / squash-and-stretch on sprites 🔵, not built" contradicts `STATE.md:136` "Bend — SPRITES 🟢". The After-launch line "bend on sprites" follows the stale row.
- `STATE.md:381` "Joybot sprite and states ⚪" is now BOT-5 (moods built, sheet awaited).

### 2.12 Decisions still owed, not in today's S-list

| # | Question | Source |
|---|---|---|
| D-1 | Setup hub grouping and the Capture-link pattern (nav D1) | `NAV_SPEC.md:142` |
| D-2 | The 9 Bits stubs and FadMic: cut or commit (nav D2) | `NAV_SPEC.md:142` |
| D-3 | Theme prune: which dark alternative survives (nav D7) | `NAV_SPEC.md:143` |
| D-4 | `support_url` is empty and onboarding reads it (nav D6) | `NAV_SPEC.md:143`, row 38 |
| D-5 | Masks on images invisible while editing unless a blend or effect is on: fix A (recommended) or B | `RULINGS_NEEDED.md:8-23` (08-21) |
| D-6 | Keep the 720p blur-matches-editor change | `RULINGS_NEEDED.md:27-37` |
| D-7 | Keep or remove the 4 m 16 s of black auto-extend added to a real lecture project | `RULINGS_NEEDED.md:41-47` |
| D-8 | Objects past the end always pull black in behind them (recommended: yes) | `RULINGS_NEEDED.md:51-59` |
| D-9 | A black clip inserts after the selected clip (recommended) or at the playhead | `RULINGS_NEEDED.md:75-83` |
| D-10 | Stills can stretch to one hour: right ceiling? | `RULINGS_NEEDED.md:87-92` |
| D-11 | Audio-editor direction: next, or after launch? | `RULINGS_NEEDED.md:113-121` |
| D-12 | Checkered-flag Start/Span/End icons: sign-off | `RULINGS_NEEDED.md:136-141` |
| D-13 | The contextual tool row; the ~50 places where green means "modified"; amber transform handles; pink-not-red voiceover recording | `STATE.md` §7.5 "Open rulings" |
| D-14 | Library: keep a "Clips" kind? empty chips faded or hidden? voiceovers in a project view? the lobby recording card opens the recording? the lobby "N ITEMS" count | `todo.md:51` |
| D-15 | Settings: clock-widget tap and Joybot "setup" still land on the classic tab; Packs has no room (cut the Packs floor word, recommended) | `todo.md:76, 89`; `HANDOFF_STUDIO_UI_20261017.md:25` |
| D-16 | Sprite tape colour ramp over the visible part of every tape | `STUDIO_PLAN.md:26` #11 |
| D-17 | Which gesture he tried on the promoted clip lane ("tap to expand audio") | `STUDIO_PLAN.md:16` #5 |
| D-18 | Multi-select fade/trim (S16) | `RUNBOOK.md:234` |
| D-19 | Muted-track caption show/hide | `road_map.md:62, 270` |
| D-20 | Delete or revive the dead `MaskKeyPanel` | `PRIORITY_20260806c.md:294-295` |
| D-21 | SpriteLab "range shading" needs a phone gesture or a no | `LEDGER.md:3687` |
| D-22 | Resume the parked slide object, or drop it (caption layers may cover it) | `DOCKET_20260829.md:126-127` |
| D-23 | Fit/Fill on an image with an active preset: clear, keep, or refuse | `PLAN_SPEC_20260829_IMAGE_ANIM_PRESETS.md:207` |
| D-24 | The FadSec flag timer accent: drop or neutral reskin | `DEPOLITICIZE_INVENTORY_20260712.md:55` |

---

## Part 3: Already captured (and where)

**Capture / recording:** Play build strips disguise icons, accessibility screenshots, all-files access, privacy
black screen, cloud and streaming → SHOW-1, S19, S20 · webcam overlay and auto-hiding controls promoted → FREE-1 ·
Joybot can start/stop a recording, webcam bubble → BOT-4 · "verify you are a human" rewrite → DOOR-3 ·
watermark text, landscape Home, Remote copy nits → UI-1.

**Studio:** phone backlog (undo after reopen, adjustment layers, bend/corner pin, single frame, ducking,
transitions) → STU-1 · slide preview after undo → STU-2 · timeline marks and regions → STU-3 · sweep leftovers →
STU-4 · luma key, AI audio proposal tools, mesh warp shown, automated Studio tests → ROADMAP "Restored 10-28" ·
VisLab effects in Studio → VisLab roadmap R4/R10/R14 · lecture mode, live-performance polish, automatic lip sync,
video generation → ROADMAP After launch.

**Library / files:** undo after Move, Space "Move unused to Trash", big share-ins, JoyBrush half, storage roots,
presets and version history later → FILE-1 · project sizes → ROADMAP restored (FILE-1) · Library shows every kind,
share-in to every wing → DOOR-4 · app ID and folder names → FREE-2, S1.

**Lobby / first run:** feature map → DOOR-1 (report 10-28) · lobby redesign, picks page → DOOR-2, S18 · intro copy,
deferred permissions, folder step, demo project, themed onboarding → DOOR-3, S5 · Joybot as first-run guide and tour
→ BOT-5 · new landing screen and recorder-to-Studio funnel → DOOR-2 · lobby can open any drawing, thumbnails → FILE-1
(JoyBrush half) and DOOR-2.

**Animation:** puppet phone proof, finish ledger C1–C7, sprite read-back doors, SpriteLab web-vs-phone list, Avatar
for launch → ANI-1, S11 · sheet library across projects, semantic cells in the AI → ROADMAP After launch.

**VisLab:** R1–R16, the owner's sign-off, the hour-long heat test → `VISLAB_ROADMAP.md`.

**JoyBrush:** engine, Select, watercolour, tweens, clips, Atlas, Unibrush, oil, symmetry, paper, boards, brush imports,
exports, fill, placed images and Joybot generation → `joybrush/ROADMAP.md` · Send to Studio into an existing
project, Studio to JoyBrush → DOOR-4 · paper choices, Pencil Tuner → S7, S8.

**Joybot:** finish and prove → BOT-1 · help everywhere, tips, empty-state copy → BOT-2 · honesty, resume, live bench
→ BOT-3, S14 · naming, Capture, deeper JoyBrush/SpriteLab/Avatar, cross-wing jobs, pictures, recipe triggers, model
routing, B-roll index → BOT-4, S15 · character, tap for voice → BOT-5, S13 · bring your own AI → BOT-6, S17.

**Remote / detection:** cut cloud and promo, keep LAN streaming → S19 · Forensics, disguise icons, Annotation for the
Play build → S20 · Remote ring and Smart Detection chip colours → S9.

**Store / business:** Play account, testers, email, privacy policy, README, closed test, What's New text, repo
private → SHOW-2, S4, S6 · website, screenshots, demo video, owner recordings → SHOW-3 · supporter tier, F-Droid →
ROADMAP After launch · parked on purpose: on-device LLM, hosted credits, native plugins, music generation, iOS,
subscriptions, adverts, paywalls, watermarks → ROADMAP.

**Hygiene:** rescue, one main line, clean-up, rename, STATE sync → HYG-1..5 · the API key file → S3.

## October29 owner idea — smaller high-resolution paper materials
Owner asks to evaluate modern/niche codecs, alpha packing and GPU texture formats
for the approved material library, preserving detail and brush response. [Research
and bounded benchmark](joybrush/reviews/PAPER_CODEC_OPTIONS_20261029.md). Compare
JPEGli/WebP/JPEGXL/AVIF storage and KTX2/Basis/native GPU paths separately; account
for added decoder code, load/transcode costs, numerical channel fidelity and Note9
capabilities. JPEG AI/neural material compression are research candidates, not a
promised phone implementation. Existing [paper evaluation](jobs/JB-PAPER-RES_resolution_evaluation.md)
continues unchanged. No codec implementation/benchmark job has been started.
