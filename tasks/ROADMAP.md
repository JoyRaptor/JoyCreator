# Joy Creator roadmap: the source of truth

**Point any agent at this one file.** It is enough to start work.

Reading order (progressive disclosure, stop as soon as you have what you need):

1. **This page**: the goal, the milestones, every job in one line, and what is waiting on the owner.
2. **Your job card** in [`tasks/jobs/`](jobs/): what "done" means, what is left, the specs and handoffs behind it.
3. **The specs and handoffs** the card links to: the detail.

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

---

## Jobs

One line per job. The card holds the detail. "Owner" is the session or harness that holds the job;
"open" means anyone may claim it on the BOARD.

### M0 Safe ground

| ID | Job | State | Owner | Next step | Checked |
|---|---|---|---|---|---|
| [HYG-1](jobs/HYG-1_main_folder_rescue.md) | Land the unsaved work from the main folder and bring the folder back in step with GitHub | 🔨 saved to `rescue/main-folder-20261027`; not yet landed | lead | Land timeline marks (STU-3) and the tuft files; then fast-forward the main folder | 10-27 |
| [HYG-2](jobs/HYG-2_one_main_line.md) | One main line: JoyBrush's integrated work (159 commits on `lead/select-reconcile-20261024`) onto `joy-creator` | ⬜ | JoyBrush lead | The JoyBrush lead decides when its gates allow; the app on the phone lacks this work until then | 10-27 |
| [HYG-3](jobs/HYG-3_cleanup.md) | Clean up: archive stale docs, remove tracked junk, audit about 156 worktrees and 256 local branches, OpenCode leftovers | ⬜ | open (cheap model for the inventory) | Inventory first; archive, never delete unique work | 10-27 |
| [HYG-4](jobs/HYG-4_rename_folder.md) | Rename the project folder away from "Screenrecorder/FadCam" | ✅ | lead | Done 10-27: moved, worktrees repaired, scripts repointed | 10-27 |
| [HYG-5](jobs/HYG-5_state_sync.md) | Make STATE.md true: every shipped feature has a row, a proof level and a help mapping | 🔨 first pass done 10-27 (app screens added, 4 stale rows fixed) | "Joybrush roadmap task" (account C) | Audit STATE against the code and the last two weeks of commits | 10-27 |

### M1 Every wing finished and polished

| ID | Job | State | Owner | Next step | Checked |
|---|---|---|---|---|---|
| **JB** | **JoyBrush**: see [its roadmap](joybrush/ROADMAP.md). Headline: the engine works, but the GPU, save and recovery checks, the Atlas, the brush set, imports and exports, and some glitches are still open | 🔨 | JoyBrush lead (session "Joy Brush lead handoff") | Per its roadmap | 10-27 |
| **VIS** | **VisLab**: see [its roadmap](VISLAB_ROADMAP.md). R1-R16 built and on `joy-creator`; all agent phone-tested on the Note 9 except part of R3 (resize, spin, keyed drag on a visualiser). Owner sign-off pending on all. Left: those R3 checks; R9's titles, overlay videos and avatars on the phone; R8 with a dummy; R8's offset mode as a choice | 🔨 | VisLab lead (session "Music visualizer effects design") | The owner tries the new pieces; R3's remaining phone checks | 10-28 |
| [STU-1](jobs/STU-1_studio_phone_backlog.md) | Studio: phone-test backlog (undo after reopening, adjustment layers really applying, bend and corner pin, single-frame export) | 🟡 | open | One phone session down the list | 10-27 |
| [STU-2](jobs/STU-2_slide_preview_after_undo.md) | Studio orphan: after an undo the preview keeps showing a removed slide. The fix exists but never landed | 🟡 fix on `claude/dazzling-satoshi-75a363` (`f46e45c67`) | open | Re-apply it on the current editor, then phone test | 10-27 |
| [STU-3](jobs/STU-3_timeline_marks.md) | Studio: timeline bookmarks and regions (named marks, In/Out regions, loop, region export). Built by Codex 10-23/24, never committed | 🟡 rescued; tap and hold proven on the Note 9 | open | Land it from the rescue branch; finish the phone checks | 10-27 |
| [STU-4](jobs/STU-4_studio_sweep_leftovers.md) | Studio sweep leftovers (caption motion, caption eye and outline, Invert on images, Puppet RECORDING tab, sprite Lanes pills, audio envelope) | ❓ from 10-16/17 lists | open | Re-check each on the phone; most may be fixed | 10-27 |
| [BOT-1](jobs/BOT-1_joybot_owed.md) | Joybot: finish and prove what is built (JoyBrush room's wrong buttons, the owed phone checks, STATE rows, honest "can't do that") | 🔨 most flows agent phone-tested 10-14 to 10-24 | "JoyBot UX vision and capabilities" | Fix JoyBrush's idea buttons; then the phone checks | 10-28 |
| [BOT-2](jobs/BOT-2_joybot_everywhere.md) | Joybot everywhere and findable: help for every STATE feature, ideas in every room, hover labels, empty-state copy | ⬜ | open | After BOT-1; help list from STATE once HYG-5 is done | 10-28 |
| [BOT-3](jobs/BOT-3_joybot_trust.md) | Joybot you can trust: "done" checked against real changes, resumable VisLab/JoyBrush jobs, live free-model benchmark, edits against a revision | ⬜ | open | Honesty by measurement first; live bench needs S14 | 10-28 |
| [BOT-4](jobs/BOT-4_joybot_reach.md) | Joybot's new reach: naming layers, Capture, deeper JoyBrush/SpriteLab, cross-wing jobs, pictures, recipe triggers, per-step model routing | ⬜ | open | Naming clips and layers first (owner-requested, designed) | 10-28 |
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
| [FREE-1](jobs/FREE-1_fadcam_inventory.md) | Inventory every FadCam-derived part and decide for each: rebuild clean, replace, or cut | ⬜ | open | The audit (read-only) | 10-27 |
| [FREE-2](jobs/FREE-2_names_and_ids.md) | Names: app ID, package names, folder names on the phone ("FadCam" folders), watermark, translations | ⛔ S1 | lead | After S1 | 10-27 |

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
  hidden is unchecked); Remote promoted (the owner ruled Remote a launch feature: belongs to DOOR-1/FREE-1);
  AI proposal tools for audio clips (built for video; audio is refused with a reason).
  **Small ones may move before launch** if they fit a job already open (project sizes → FILE-1; mesh warp → STU-1).

**Parked on purpose:** on-device LLM, hosted AI credits, native plugins, music generation, iOS,
any subscription, any advert, any export paywall, any watermark.

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
   handoffs hang off the card, never off this page.
