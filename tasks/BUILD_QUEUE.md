# Build queue: constant building until Joy Creator is done (owner 11-23)

The owner, 11-23: "get agents building round the clock to get Joy Creator done. Doesn't have to be a ton in parallel,
but constant building." The orchestrator keeps **about three Claude lanes building at once**. When one finishes, the
next job down this list starts. **Each lane pulls its own next job**: when it finishes, it claims the next unclaimed
row that fits (phone-free unless a phone is free) and carries on in the same session. The orchestrator's hourly
check-in nudges any lane that has gone idle. (New sessions can't be started from an automated turn, so lanes chain.) Codex takes the hard builds (prompts given to the
owner 11-23) on its own budget.

**Owner 11-23 evening: the app comes first.** No more website or sizzle-video work until the app is done; the
website draft stays as it is. Pick app jobs only.

Rules for every lane: read `tasks/AGENT_START.md` and `tasks/BOARD.md` first; claim your files on BOARD; use your own
worktree; build and test in the cloud (`tools/cloud-build.ps1`), never with local Gradle; land on `joy-creator` only
when it is proven (no dirty overlap, nobody mid-edit, guard on); update the job card, the ROADMAP row and STATE.
Phone work: `tools/phone.ps1` with its lock, Note 9 only (never the Note 20, never `adb monkey`). If no phone is
attached, finish everything else and leave the phone check written up as owed. No time estimates in reports.

Models: **Sonnet, medium effort** for these unless marked. Escalate a tricky piece to Opus; Fable only for the
hardest work.

| # | Job | Card | Phone? | State |
|---|---|---|---|---|
| 1 | Hover-label sweep: every button in every room gets a hover label (owner rule) | [BOT-2](jobs/BOT-2_joybot_everywhere.md), memory rule | at the end | built and landed 11-23, cloud green ([HOVER-1](jobs/HOVER-1_hover_labels.md)); owed: Avatar buttons, phone hover check |
| 2 | Tick box and palette sweep: on/off becomes a cyan knock-out tick box, a switch only for two equal choices; cyan and magenta come from the shared palette, never a room's own hex (owner 11-21, 11-22) | [UI-1](jobs/UI-1_app_ui_leftovers.md) | at the end | started 11-23 |
| 3 | Website first draft from the design record in git | [SHOW-3](jobs/SHOW-3_website_and_demos.md) | no | ✅ first draft landed af2cf7f40 (`docs/`); ⏸ paused by the owner 11-23: app first |
| 4 | STATE and help truth after the 11-17 to 11-23 landings | [HYG-5](jobs/HYG-5_state_sync.md) | no | third pass done 11-23 (STATE rows, help owed list on the card) |
| 5 | Clutter inventory: worktrees and branches; file away only what is landed or empty; never delete unique work | [HYG-3](jobs/HYG-3_cleanup.md) | no | started 11-23 (website lane): inventory written ([list](HYG-3_worktree_inventory_20261123.md)); 45 safe worktrees removed, 65 local-only branches listed for the lead; rest owed |
| 6 | A picture for every Studio effect (picker grid, live swatch on each card) | [STU-6](jobs/STU-6_effect_swatches_and_textlab_generators.md) | at the end | built 11-23 (swatches, 7 Generate cards, 5 over-video effects); cloud green; GLSL not yet compiled on a phone, Note 9 pass owed |
| 7 | A tiny preview beside every part wherever one is listed or picked (owner 11-20) | [PREVIEW-1](jobs/PREVIEW-1_part_previews_everywhere.md) | at the end | nine lists built, host check in, cloud green 11-23; the hard rest is on the card with reasons; phone look owed |
| 8 | App UI leftovers: legacy-themed screens, watermark text, What's New, update sheets | [UI-1](jobs/UI-1_app_ui_leftovers.md) | at the end | phone-free part checked 11-23: wide-screen items were already done by TAB-1; the rest needs the phone or the owner (see card) |
| 9 | Studio phone backlog and the 41-check sweep (14 features never looked at) | [STU-1](jobs/STU-1_studio_phone_backlog.md) | yes | waits for a phone |
| 10 | Joybot create-tree landings not yet seen: image, tile, sprite, perspective, sprite edit, find | [BOT-7](jobs/BOT-7_lobby_wizard_and_room_colours.md) | yes | waits for a phone |
| 11 | Engine round 4 last three checks (save, wet undo, wet map) | engine-r4 lane | yes | waits for the Note 9 |
| 12 | Land Codex's branches as they appear (audit round 5, sparse leaving save, load split, mask multipass, title transfer) | `tasks/audits/codex/` | depends | as they come |
| 13 | No lost work: fix the remaining findings of the work-loss report, each with a test that fails first | [SAFE-1](jobs/SAFE-1_work_loss.md) | at the end | second pass built 11-23: 7 findings fixed with tests, kept-copy restore function, 4 suites now in the cloud run; phone list owed; open items in the report |
| 14 | Joybot help: a help topic for every STATE feature on the HYG-5 owed list (text and help data) | [BOT-2](jobs/BOT-2_joybot_everywhere.md) | no | done 11-23: seven topics + harness cases, 1311 pass |
| 15 | FadCam inventory: every package, screen, service and resource from FadCam, with a keep/rebuild/replace/cut verdict | [FREE-1](jobs/FREE-1_fadcam_inventory.md) | no | refreshed 11-23 (addendum to the 10-28 inventory); owner has not seen the plan |
| 16 | Send to Studio into an existing project: land it after a Note 9 check | [DOOR-4](jobs/DOOR-4_interwoven.md) | yes | already on joy-creator (8b238c428); Note 9 check: **owner to try** (adb strokes leave no mark; see card) |
| 17 | Studio sweep leftovers re-checked on the phone (caption motion, eye and outline, Invert, Puppet tab, Lanes pills, envelope) | [STU-4](jobs/STU-4_studio_sweep_leftovers.md) | yes | queued (added 11-23) |
| 18 | Puppets proven on a phone: pins, a keyed pin, a live take, a dragged performance | [ANI-1](jobs/ANI-1_puppets_and_sprites.md) | yes | queued (added 11-23) |
| 19 | Launch triage pool: every LAUNCH then POLISH row in `tasks/audits/launch_triage_20261114.md` that needs no phone; verify each against today's code first (many are fixed), fix the rest with tests, mark each row | [triage](audits/launch_triage_20261114.md) | no | worked 11-24 in seven batches (triage file sections 6; every launch and polish row a PC can prove is fixed or marked): built L27 key guard, L18 English ratchet, F13 sprite grid from file name, Export label, BOT-A29 screen recorder, BOT-A31 dope sheet; parked with reasons: BOT-A31 bake/export, BOT-A33, AN-06 F15, the rows that need the owner, a phone or the JoyBrush lane |
| 20 | FILE-1 phone-free items (Joy Creator folder leftovers) | [FILE-1](jobs/FILE-1_folder_leftovers.md) | no | checked 11-24: the open storage-roots item was already done; the stale box ticked; what is left needs a phone, the owner or the JoyBrush lane |
| 21 | BOT-2 open boxes that need no phone (help for every STATE feature, hover labels) | [BOT-2](jobs/BOT-2_joybot_everywhere.md) | no | 11-24: STATE "Help: pending" cut from 17 rows to 6 (the rest are the hidden Avatar room); hover labels covered by HOVER-1; per-room ideas need a phone |
| 22 | BOT-3 "edits against a revision" (Studio first): a long Joybot job commits only if the project has not moved, else a clear conflict; proved in the bench with the fake model | [BOT-3](jobs/BOT-3_joybot_trust.md) | no | looked at 11-24 (website lane): design on the card, not built; needs a phone first (see card) |
| 23 | ANI-1 finish ledger: code rows | [ledger](PUPPET_FINISH_LEDGER.md) | no | re-checked 11-24: every row done or waiting on a screenshot/phone; nothing a PC can prove |
| 24 | BOT-4: naming layers (the part a PC can finish) | [BOT-4](jobs/BOT-4_joybot_reach.md) | no | timeline bars show the item name (ItemLabel, 33 checks); drawer rename, chips, missing-file text left (phone) |
| 25 | STU-5: one snapping authority | [STU-5](jobs/STU-5_studio_structure_and_primitives.md) | no | written up 11-24, then built the same day on Opus: see row 26 |
| 26 | STU-5: one snapping authority (Opus) | [STU-5](jobs/STU-5_studio_structure_and_primitives.md) | yes (feel-check) | built 11-24: SnapAuthority decides every routed snap; 39 pinning checks against frozen copies of the old code; one deliberate fix (text drop time nearest); phone feel-check owed (hover lane) |
| 27 | STU-5: one time authority, the preview half (Opus) | [STU-5](jobs/STU-5_studio_structure_and_primitives.md) | yes (phone A/B) | built 11-24: PreviewClock gives the preview the export's clip starts, transition and blend length; unchanged where it was right (4,000 random timelines), five named fixes; Note 9 check owed |

Waiting on the owner (not in the queue): S1 app ID, S4 privacy email, S6 Play account and testers, S10 removing 11
unused FadCam classes, the icon picks, the hide list.
