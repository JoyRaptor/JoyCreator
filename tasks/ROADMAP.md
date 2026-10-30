# Joy Creator roadmap: the source of truth

**Point any agent at this one file.** It is enough to start work.

Reading order (progressive disclosure, stop as soon as you have what you need):

1. **This page**: the goal, the milestones, every job in one line, and what is waiting on the owner.
2. **Your job card** in [`tasks/jobs/`](jobs/): what "done" means, what is left, the specs and handoffs behind it.
3. **The specs and handoffs** the card links to: the detail.
4. **Every future plan, owner ruling and idea** lives in [`FUTURE_PLANS.md`](FUTURE_PLANS.md) (nothing is ever deleted;
   a plan that becomes a job points at its card). Check it before you propose to cut or skip anything.

Before you edit anything, read [`tasks/AGENT_START.md`](AGENT_START.md) (how to work here without
breaking other sessions) and check [`tasks/BOARD.md`](BOARD.md) (who is working on what right now).

| Wing | Its own source of truth | This roadmap holds |
|---|---|---|
| JoyBrush (paint and animate) | [`tasks/joybrush/ROADMAP.md`](joybrush/ROADMAP.md), run by the JoyBrush lead | one summary row, plus the jobs that cross into other wings |
| VisLab (music visualiser) | [`tasks/VISLAB_ROADMAP.md`](VISLAB_ROADMAP.md) | one summary row, plus the cross-wing jobs |
| Everything else: Studio, Joybot, Library and files, SpriteLab, puppets, Capture, the lobby, launch | **this page** | every job |

What exists, and how well proven, lives in [`tasks/STATE.md`](STATE.md). Joybot's in-app help and every
advertised feature are built from STATE, so **a feature missing from STATE is a feature users never find.**

**Last reconciled:** 2026-10-27 by the project lead (Claude, session "JOY CREATOR SUPREME ORCHESTRATOR"),
against `origin/joy-creator` at `8d2c40e3a`, every Claude Code session on this machine (all accounts) and
the Codex sessions. Rows say when they were last checked. **A row checked more than 7 days ago is
suspect:** re-verify it against the code before you rely on it.

---

## The goal

Joy Creator is a complete creative studio on a phone, not a screen recorder: draw and paint
(JoyBrush), edit video (Studio), make music visuals (VisLab), build and puppet characters (SpriteLab,
Avatar), record (Capture), with Joybot as the helper who knows all of it.

- **Easy enough that iPhone fans like it.** One-handed, phone-first, every feature findable.
- **Free forever.** No ads, no subscription, no watermark, no export paywall. AI is bring-your-own-key at zero margin.
- **Free of FadCam.** Every FadCam-derived part is rebuilt or replaced, so Joy Creator stands on its own,
  legally and in how it feels.

## The milestones: how we chew this

The owner's order: finish and polish every wing, wire them together, then rebuild the front door,
then show it off. M3 runs alongside everything.

| | Milestone | Done when | State |
|---|---|---|---|
| **M0** | **Safe ground** | No unsaved work anywhere; one main line (`joy-creator`) holds everything; the clutter is archived; STATE.md is true | 🔨 started 10-27 |
| **M1** | **Every wing finished and polished** | Each wing's open list is empty or explicitly parked; every feature is phone-proven and has its STATE row | 🔨 wings at different stages: Studio strong, VisLab close, JoyBrush behind |
| **M2** | **One front door** | A first-time user knows at a glance what they can make, reaches every feature from the lobby, and nothing feels like FadCam | ⬜ the audit can start now; the redesign waits for M1 |
| **M3** | **Free of FadCam** | Every FadCam-derived screen and class is rebuilt, replaced or cut; licence position confirmed | ⬜ inventory first |
| **M4** | **Show it** | Play build, store listing, website, demo recordings, launch | ⬜ the calendar-time items can start now |

## Status key

| | Means |
|---|---|
| ✅ | Done and proven on a phone (the row says whether the owner or an agent proved it) |
| 🟡 | Built: compiles and its tests pass; phone test owed |
| 🔨 | In progress: claimed on [BOARD.md](BOARD.md) by a named session |
| ⬜ | Not started: ready to pick up |
| ⛔ | Blocked: the row names the blocker |
| ❓ | Unverified: a doc claims it, nobody has checked the current code |

---

## Waiting on the owner

Each of these needs the owner and nobody else. The lead's recommendation comes first where there is one.

| # | Decision or action | Recommendation | Unblocks |
|---|---|---|---|
| S1 | **Pick the permanent app ID** (changing it later loses every user) | `cc.joycreator.app`: it matches JoyBrush's code name `cc.joycreator.joybrush` | [SHOW-1](jobs/SHOW-1_play_build.md), [FREE-2](jobs/FREE-2_names_and_ids.md) |
| S2 | ~~Pick the new folder name~~ **Decided 10-27:** `C:\+Projects\JoyCreator` | done | [HYG-4](jobs/HYG-4_rename_folder.md) |
| S3 | **Move `tasks/api.txt` out of the project folder.** It holds an AI API key in plain text, and GitHub refused a push because of it | Keep it in a password manager or outside the repo | safety |
| S4 | Contact email for the privacy policy, and a read of the README and PRIVACY drafts | `studio@joycreator.cc` (named in START_HERE): confirm it receives mail | [SHOW-2](jobs/SHOW-2_store_and_legal.md) |
| S5 | Paste the intro text approved on 9-09 (it was never saved to the repo) | | [DOOR-3](jobs/DOOR-3_first_run.md) |
| S6 | Google Play developer account, a public developer email, 12 closed testers | Start now: these cost only calendar time | [SHOW-2](jobs/SHOW-2_store_and_legal.md) |
| S7 | JoyBrush: three paper-sourcing choices (scanner, phone photos or generated images; ship the strong crumple now; is a photographer credit OK) | Flatbed scanner, about 10 scans | JoyBrush roadmap PAPER-PORTFOLIO |
| S8 | JoyBrush: Pencil Tuner on the Note 9 against JoyBrush, same paper, and say how they differ | | JoyBrush pencil |
| S9 | Studio and app UI calls: the About "View source" link, the Camera label, Remote ring colours, Smart Detection chip colours | | [UI-1](jobs/UI-1_app_ui_leftovers.md) |
| S10 | OK to remove 11 FadCam classes nothing calls and 4 unused drawables, once their replacements are proven | Yes, in one pass | [FREE-1](jobs/FREE-1_fadcam_inventory.md) |
| S11 | Puppets: a screenshot of the overlapping corner widget; and do you miss an "Everything" chip? | | [ANI-1](jobs/ANI-1_puppets_and_sprites.md) |
| S12 | Owner checks you said you want to do: Studio transitions, and the new VisLab effects inside Studio | | M1 |
| S13 | Joybot: your labelled sprite sheet, and OK the mood names (idle, listen, think, work, talk, excited, show, oops) | The names as listed | [BOT-5](jobs/BOT-5_joybot_character.md) |
| S14 | Joybot: an OpenRouter key on the PC for the live free-model test (or about $10 credit for four models) | Key in an environment variable, never in a file | [BOT-3](jobs/BOT-3_joybot_trust.md) |
| S15 | Joybot defaults: paid picture model (FLUX.2 Klein ~1½¢ or Nano Banana ~4¢), ask before every paid picture, read replies aloud | Klein; always ask (a "don't ask under 5¢" switch); read-aloud off | [BOT-4](jobs/BOT-4_joybot_reach.md) |
| S16 | Joybot: try him with your own voice (hands-free loop) and, when you have API credit, with OpenAI | | [BOT-1](jobs/BOT-1_joybot_owed.md) |
| S17 | Joybot: ask Anthropic (in writing) whether Joy Creator may start a person's own Claude Code on their own computer, so they can talk to it from Joybot. Their terms forbid apps routing requests through Pro/Max plans "on behalf of their users"; this case isn't addressed | Ask before building that half; everything else in BOT-6 doesn't need it | [BOT-6](jobs/BOT-6_bring_your_own_ai.md) |
| S18 | **Pick the front door**: open `tasks/design/UI-Front-door-picks_20261028.html` on the phone, tap a direction and one option per part, and paste back the copy line | Direction 1 ("Marquee, made clear") is the smallest step from today; direction 2 is the most iPhone-simple | [DOOR-2](jobs/DOOR-2_lobby_landing.md) |
| S19 | ~~Cut FadCam's cloud and promo parts~~ **Decided 10-28: yes, cut** (the code talks to the FadCam developer's servers: `fadseclab.com`, their Supabase project). LAN Remote on your own Wi-Fi stays | done | [FREE-1](jobs/FREE-1_fadcam_inventory.md) |
| S20 | ~~FadCam extras for the launch build~~ **Decided 10-28: fold in and rebrand, do not cut**, as already ruled 10-17 (Remote/Server Room and Smart Detection are launch features) and planned in `DESIGN_JOY_CREATOR.md` §7: the forensics evidence board becomes a **Story Board**; motion/noise/object detection becomes **auto-markers** and finding things in a scene; Remote becomes **Remote Studio**; dual/multi-camera feeds Capture; Annotation folds into recording (and later a JoyBrush layer). Disguise icons and the accessibility screenshot service: still a Play-policy question (SHOW-1) | done | [FREE-1](jobs/FREE-1_fadcam_inventory.md) |

---

## Jobs

One line per job. The card holds the detail. "Owner" is the session or harness that holds the job;
"open" means anyone may claim it on the BOARD.

### M0 Safe ground

| ID | Job | State | Owner | Next step | Checked |
|---|---|---|---|---|---|
| [HYG-1](jobs/HYG-1_main_folder_rescue.md) | Land the unsaved work from the main folder and bring the folder back in step with GitHub | 🟡 folder synced 10-28; timeline marks landed | lead | JoyBrush pieces of the rescue (tuft tuning files, staged tween reviews) go to the JoyBrush lane | 10-28 |
| [HYG-2](jobs/HYG-2_one_main_line.md) | One main line: JoyBrush's integrated work (159 commits on `lead/select-reconcile-20261024`) onto `joy-creator` | ⬜ | JoyBrush lead | The JoyBrush lead decides when its gates allow; the app on the phone lacks this work until then | 10-27 |
| [HYG-3](jobs/HYG-3_cleanup.md) | Clean up: archive stale docs, remove tracked junk, audit about 156 worktrees and 256 local branches, OpenCode leftovers | ⬜ | open (cheap model for the inventory) | Inventory first; archive, never delete unique work | 10-27 |
| [HYG-4](jobs/HYG-4_rename_folder.md) | Rename the project folder away from "Screenrecorder/FadCam" | ✅ | lead | Done 10-27: moved, worktrees repaired, scripts repointed | 10-27 |
| [HYG-5](jobs/HYG-5_state_sync.md) | Make STATE.md true: every shipped feature has a row, a proof level and a help mapping | 🔨 first pass done 10-27 (app screens added, 4 stale rows fixed) | "Joybrush roadmap task" (account C) | Audit STATE against the code and the last two weeks of commits | 10-27 |

### M1 Every wing finished and polished

| ID | Job | State | Owner | Next step | Checked |
|---|---|---|---|---|---|
| **JB** | **JoyBrush**: see [its roadmap](joybrush/ROADMAP.md). [Paper evaluation](jobs/JB-PAPER-RES_resolution_evaluation.md): initial3resolution comparison installed; all45 masters prepared. Owner order: full45phone evaluation, compression, then pack choices. Full-library GPU retention/integration checks active; owner/GL/feel acceptance open. Full engine/Atlas/brush/import/export gates remain per wing. | 🔨 | JoyBrush lead; Codex paper evaluation | Full-library evaluation in progress; HYG-2 still unlanded | 10-29 |
| **VIS** | **VisLab**: see [its roadmap](VISLAB_ROADMAP.md). R1-R16 built, on `joy-creator`, and agent phone-tested on the Note 9 (10-27/28), including R8's "Keeps its place" and "copies" (Position / Size / Rotation) choices; Joybot help covers R8, R9 and R12; the web look book is current (v7). Left: the owner trying them and signing off; avatars shown in a look not tried on the phone | 🟡 | VisLab lead (session "Music visualizer effects design") | The owner tries the new pieces | 10-28 |
| [STU-1](jobs/STU-1_studio_phone_backlog.md) | Studio: phone-test backlog (undo after reopening, adjustment layers really applying, bend and corner pin, single-frame export) | 🟡 | open | One phone session down the list | 10-27 |
| [STU-2](jobs/STU-2_slide_preview_after_undo.md) | Studio orphan: after an undo the preview keeps showing a removed slide | 🟡 ✔ landed 10-28 (77d27970c, 1a7a8637e), agent-checked | lead | The owner's exact case (a Joybot title slide) needs an AI key: owner check | 10-28 |
| [STU-3](jobs/STU-3_timeline_marks.md) | Studio: timeline bookmarks and regions (named marks, In/Out regions, loop, region export) | 🟡 ✔ landed 10-28 (b3c2b23b0), all checks agent-passed on the Note 9 | lead | The owner tries it; Joybot lane switches on `add_marker` | 10-28 |
| [STU-4](jobs/STU-4_studio_sweep_leftovers.md) | Studio sweep leftovers (caption motion, caption eye and outline, Invert on images, Puppet RECORDING tab, sprite Lanes pills, audio envelope) | ❓ from 10-16/17 lists | open | Re-check each on the phone; most may be fixed | 10-27 |
| [STU-5](jobs/STU-5_studio_structure_and_primitives.md) | Studio structure (one time authority, one snapping authority, export speed) and new primitives (paths, text on a path) | ⬜ planned | open | Time authority first | 10-28 |
| [BOT-1](jobs/BOT-1_joybot_owed.md) | Joybot: finish and prove what is built (JoyBrush room's wrong buttons, the owed phone checks, STATE rows, honest "can't do that") | 🔨 JoyBrush buttons, audit checks, chat colours, list_items phone ✅ 10-28 | "JoyBot UX vision and capabilities" | Fallback/borrowed eyes, add_media, ducking on the phone; S16 owner voice | 10-28 |
| [BOT-2](jobs/BOT-2_joybot_everywhere.md) | Joybot everywhere and findable: help for every STATE feature, ideas in every room, hover labels, empty-state copy | 🔨 VisLab + Director help, "Did you know?" tips, "What can I do here?", chat hover sweep: done 10-28 (tips and What here phone ✅) | "JoyBot UX vision and capabilities" | JoyBrush help after HYG-2; the rest of STATE after HYG-5; JoyBrush ideas that read the drawing | 10-28 |
| [BOT-3](jobs/BOT-3_joybot_trust.md) | Joybot you can trust: "done" checked against real changes, resumable VisLab/JoyBrush jobs, live free-model benchmark, edits against a revision | ⬜ | open | Honesty by measurement first; live bench needs S14 | 10-28 |
| [BOT-4](jobs/BOT-4_joybot_reach.md) | Joybot's new reach: naming layers, Capture, deeper JoyBrush/SpriteLab, cross-wing jobs, pictures, recipe triggers, per-step model routing | ⬜ | open | Naming clips and layers first (owner-requested, designed) | 10-28 |
| [BOT-6](jobs/BOT-6_bring_your_own_ai.md) | Bring your own AI: pair Claude Code, Codex, OpenCode or Hermes with the phone over Wi-Fi or USB (a stylish Joybot QR + code); they get every Joybot tool and can be talked to from Joybot. Nothing runs on our side | 🔨 tools half built 10-28: pairing screen + owner's QR, MCP door, connect-an-AI chat with the key kept on the phone; PC 19/19 and Note 9 agent ✅ (USB) | "JoyBot UX vision and capabilities" | A real Claude Code / Codex on Wi-Fi (owner); the talk half (OpenCode first; Claude Code waits on S17) | 10-28 |
| [BOT-5](jobs/BOT-5_joybot_character.md) | Joybot's character (the owner's sprite sheet) and Joybot as the first-run guide | ⛔ S13, DOOR-3 | open | When the sheet arrives | 10-28 |
| [FILE-1](jobs/FILE-1_folder_leftovers.md) | Joy Creator folder and storage leftovers (undo after a Move, Space's "Move unused to Trash", large share-ins, the JoyBrush half) | 🟡 | open | Two phone checks, one rule change | 10-27 |
| [ANI-1](jobs/ANI-1_puppets_and_sprites.md) | Puppets, sprites, SpriteLab and Avatar: prove puppeteering on a phone, close the finish ledger, give sprites their missing doors | 🟡 built 9-16; little phone proof since | open | Phone-prove the puppet basics first | 10-27 |
| [UI-1](jobs/UI-1_app_ui_leftovers.md) | App UI leftovers from the two retired UI sessions (sidebars, fullscreen preview, What's New, update sheets, five legacy-themed screens, watermark text) | ⬜ | open | Per the card; design records are now in git | 10-27 |

### M2 One front door: the lobby and first run (the owner's biggest worry)

| ID | Job | State | Owner | Next step | Checked |
|---|---|---|---|---|---|
| [DOOR-1](jobs/DOOR-1_feature_map_audit.md) | Map every feature to the path a user takes to reach it; find the dead ends, the hidden pockets and the FadCam-shaped routes | ✅ report written 10-28, lead to review | Sonnet Worker 1 | [tasks/audits/feature_reach_20261028.md](audits/feature_reach_20261028.md) | 10-28 |
| [DOOR-2](jobs/DOOR-2_lobby_landing.md) | The lobby: clear at a glance whether you are here to draw, edit, visualise, animate or record; every room and button honest | ⬜ design can start; build after M1 | open | Mix-and-match design page from the existing design records | 10-27 |
| [DOOR-3](jobs/DOOR-3_first_run.md) | First run: intro, permissions asked where they are used, folder step, a demo project | ⬜ | open | Needs S5 for the copy | 10-27 |
| [DOOR-4](jobs/DOOR-4_interwoven.md) | Wings that hand work to each other (JoyBrush to an existing Studio project, VisLab and Studio, SpriteLab, the Library showing everything) | ⬜ partly built | open | Inventory from DOOR-1, then one flow at a time | 10-27 |

### M3 Free of FadCam

| ID | Job | State | Owner | Next step | Checked |
|---|---|---|---|---|---|
| [FREE-1](jobs/FREE-1_fadcam_inventory.md) | Inventory every FadCam-derived part and decide for each: rebuild clean, replace, or cut | 🟡 inventory done 10-28 ([report](audits/fadcam_inventory_20261028.md)): ~160,500 FadCam lines left, 37% of the app module; JoyBrush, VisLab, lobby, Library, Settings hub have none | lead | Owner calls S19, S20; then cut cloud/promo, then replace classic Records/Trash/Studio list | 10-28 |
| [FREE-2](jobs/FREE-2_names_and_ids.md) | Names: app ID, package names, folder names on the phone ("FadCam" folders), watermark, translations | ⛔ S1 | lead | After S1 | 10-27 |
| [FOLD-1](jobs/FOLD-1_fold_fadcam_features_in.md) | Fold FadCam's features in under Joy Creator names: Remote Studio, multi-camera, Smart Detection, auto-markers, Story Board, Annotation | ⬜ planned (owner direction 10-17, 10-28) | open | Agree the order with the owner | 10-28 |

### M4 Show it

| ID | Job | State | Owner | Next step | Checked |
|---|---|---|---|---|---|
| [SHOW-1](jobs/SHOW-1_play_build.md) | Play build: remove the disguise icons, the accessibility screenshot service, all-files storage access, cloud and streaming | ⬜ | open | Needs a build the watcher doesn't make | 10-27 |
| [SHOW-2](jobs/SHOW-2_store_and_legal.md) | Store and legal: privacy policy hosted, README, listing, closed test | ⛔ S4, S6 | owner + lead | | 10-27 |
| [SHOW-3](jobs/SHOW-3_website_and_demos.md) | Website, screenshots, demo video, recordings of building things | ⬜ after M2 | open | The website design record is now in git | 10-27 |

---

## After launch (ordered by current thinking; reorder freely)

Carried from the 10-15 roadmap ([archive](archive/ROADMAP_20261015.md)); not jobs yet.

- Video generation (prompt, background job, cost confirm, lands on the timeline)
- Sprite read-back: saved animations as chips, cell names on chips, a sheet library across projects (STATE §9 #11–13)
- Semantic cells in the AI toolkit
- Pack manager, theme packs, template gallery
- Automatic lip sync; live-performance polish; bend on sprites
- Lecture mode; multi-camera (composed-frame vision was built 10-24: Joybot's `look`, 95c7cd868)
- Supporter tier via Play Billing; F-Droid
- **Restored 10-28** (dropped from the list when the roadmap was rewritten 10-27; checked in code 10-28):
  project sizes in the project list (not built); luma key (spec only, STATE §2; chroma key exists);
  community pack index (not built); automated tests for the Studio app module (none; JoyBrush and the
  JVM harness have them); mesh warp shown to users (built and device-verified 09-10; whether it is still
  hidden is unchecked); Remote is a **launch** feature (owner 10-17), folded in as Remote Studio: job FOLD-1;
  AI proposal tools for audio clips (built for video; audio is refused with a reason).
  **Small ones may move before launch** if they fit a job already open (project sizes → FILE-1; mesh warp → STU-1).

**Parked on purpose:** on-device LLM, hosted AI credits, native plugins, music generation, iOS,
any subscription, any advert, any export paywall, any watermark.

---

## From the plans ledger: the top 25, given homes (10-28)

The full list (106 plans and ideas, 94 rulings) is in [`FUTURE_PLANS.md`](FUTURE_PLANS.md). These are the ones most at risk:

| # | Plan | Home |
|---|---|---|
| 1 | Remote and Smart Detection are launch features (owner 10-17) | [FOLD-1](jobs/FOLD-1_fold_fadcam_features_in.md); S20 corrected |
| 2 | Play's 200 MB app-size limit: measure what the app bundles | [SHOW-1](jobs/SHOW-1_play_build.md) |
| 3 | Story Board (the forensics evidence board, reskinned: sequence clips, hand a rough cut to Studio) | [FOLD-1](jobs/FOLD-1_fold_fadcam_features_in.md) |
| 4 | Auto-markers from motion/noise on long recordings (timeline marks now exist to receive them) | [FOLD-1](jobs/FOLD-1_fold_fadcam_features_in.md) |
| 5 | Re-run the 41-check device sweep (14 features never looked at) | [STU-1](jobs/STU-1_studio_phone_backlog.md), [HYG-5](jobs/HYG-5_state_sync.md) |
| 6 | The 09-22 nav spec: search palette, live strip, hold-to-fan floor ([`NAV_SPEC.md`](NAV_SPEC.md), [`NAV_PLAN.md`](NAV_PLAN.md)) | [DOOR-2](jobs/DOOR-2_lobby_landing.md) |
| 7 | Pack folder contract (before launch; the pack manager comes after) | [FILE-1](jobs/FILE-1_folder_leftovers.md) |
| 8 | Sprites get a row in Add asset | [ANI-1](jobs/ANI-1_puppets_and_sprites.md) |
| 9 | App-wide hover-label sweep | [UI-1](jobs/UI-1_app_ui_leftovers.md) |
| 10 | A remote phone as a second camera (multicam) | [FOLD-1](jobs/FOLD-1_fold_fadcam_features_in.md) |
| 11 | JoyBrush puppet and character boards (JB-7.01–7.04) | JoyBrush roadmap (message to the JoyBrush lane) |
| 12 | Studio primitives: path object, text on a path, per-letter channels, motion paths | [STU-5](jobs/STU-5_studio_structure_and_primitives.md) |
| 13 | One authority for timeline time to source time (four copies disagree) | [STU-5](jobs/STU-5_studio_structure_and_primitives.md) |
| 14 | One snapping authority, app-wide | [STU-5](jobs/STU-5_studio_structure_and_primitives.md) |
| 15 | Business: letter to the FadCam author, our own donation links, trademark search, store listing still says FadCam | [SHOW-2](jobs/SHOW-2_store_and_legal.md) |
| 16 | 24 owner decisions owed beyond S1–S20 | [`FUTURE_PLANS.md` §2.12](FUTURE_PLANS.md); the lead brings them to the owner a few at a time |
| 17 | Hide unproven 🟡 features from the launch UI | [SHOW-1](jobs/SHOW-1_play_build.md) |
| 18 | Dual-stream recording finish (the webcam file never rolls over on long recordings) | [FOLD-1](jobs/FOLD-1_fold_fadcam_features_in.md) |
| 19 | ~~A video row's own audio sub-drawer~~ already built (07-11, `a91b01921`; owner 10-28) | done |
| 20 | Tablet layouts | [UI-1](jobs/UI-1_app_ui_leftovers.md) |
| 21 | VisLab element packs from other people | VisLab roadmap |
| 22 | Joybot inbox extras (skins, launch buttons, prompt packing, notices, per-project log) | Joybot lane (BOT-4) |
| 23 | Paste a picture from the clipboard (the third "way in") | [FILE-1](jobs/FILE-1_folder_leftovers.md) |
| 24 | Annotation becomes a JoyBrush layer | [FOLD-1](jobs/FOLD-1_fold_fadcam_features_in.md); JoyBrush roadmap |
| 25 | Export speed: the one-pass GPU stage, "one tap, fast" | [STU-5](jobs/STU-5_studio_structure_and_primitives.md) |

---

## Keeping this true (every agent, every harness)

1. **Update as you go, not at the end.** Every milestone in your job (a spec written, a build passing, a
   phone test, a merge) updates your job card, and the job's row here if its state changed. The detail
   goes in the card and its handoff; this page gets one line.
2. **A job is not done until STATE.md says so.** Your last step is always the STATE row: what the user
   gets, where they find it, its proof level, and its help mapping. No STATE row means not done.
3. **Edit rows, never whole files.** Never paste an older copy of this page over a newer one. If your
   push conflicts here, keep both changes.
4. **Check before you claim.** Before you call something open, missing or broken, check the date of
   your source and the current code. Old lists (todo.md, INBOX.md, LANES.md, old handoffs) are history,
   not a queue.
5. **Contradictions get fixed, not followed.** If two documents disagree, the code and git win; fix
   the document that is wrong, and say so in your report.
6. **New ideas go to [INBOX.md](INBOX.md)**, not into new spec files or new rows. The lead promotes them.
7. **A new job gets a card** from [`jobs/_TEMPLATE.md`](jobs/_TEMPLATE.md) and a row here. Specs and
8. **A plan never just disappears.** Before removing, cutting or parking anything, search [`FUTURE_PLANS.md`](FUTURE_PLANS.md),
   the OWNER DECISIONS sections and `DESIGN_JOY_CREATOR.md` for it and quote what was ruled. When a plan leaves this page,
   its row moves to `FUTURE_PLANS.md` with the reason.
   handoffs hang off the card, never off this page.
