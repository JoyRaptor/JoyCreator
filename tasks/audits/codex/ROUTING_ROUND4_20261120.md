# Codex round 4 (performance and stability, 11-19): who fixes what

[Report](JoyCreator_Audit_Round4_Perf_20261119.md): 20 findings. The orchestrator's split, 11-20: **Codex fixes what it can prove
on the PC; Claude lanes fix what needs a phone bench, and land everything.** Codex works on its own budget, which
saves the owner's Claude usage. Codex works only on branches and never lands on `joy-creator` itself, because the
commit guard (no identifiers) and the phone checks run on this PC, where a Claude lane merges, checks and lands.

| Who | Findings |
|---|---|
| **Codex** (branch per finding: `codex/r4-<id>`, host tests that fail before the fix) | SAVE-ROTATE, SAVE-CONTENT (lost work: first), AUD-RACE, AUD-BAKE, GL-SPAN, GL-MASK, VIS-NEST, VIS-SOUND, VIS-SPRITE, VIS-IDLE, TXT-TRANSFER |
| **Claude, Opus lane** (engine: needs Note 9 benches and exactness digests) | JB-SAVE-IO, WET-UNDO, WET-MAP, ATLAS-TABLE, OIL-STEP |
| **Claude, Avatar Studio pilot lane** | AV-SAVE, AV-IDLE |
| **Decision or listening first, not a fix yet** | AUD-RAIN (owner's ears: compare "Reduce noise" against CapCut on real rain), AUD-LAYOUT (true surround export is a large feature: after launch) |

Same rule as rounds 1 to 3: verify against today's `joy-creator` first, and record each verdict below with its proof.

## Verdicts
| Finding | Verdict | Proof |
|---|---|---|
| SAVE-CONTENT | **Landed** (Claude, 11-20) | Codex branch merged as is. Host `run-workloss.sh`: base 45 + new SavedTimelineContentTest 5 pass; with the old ProjectStorage the new test fails 5 of 5. Cloud: all suites green, cloud APK build ok. |
| SAVE-ROTATE | **Landed** | Merged as is. WorkLossStorageTest 49 pass on top of SAVE-CONTENT (both touch ProjectStorage, merged without conflict). Cloud green as above. |
| AUD-RACE | **Landed with one fix of mine** | Codex's new post-bake check compared the file length with the span only, but a bake runs to endMs on the source clock (it starts with startMs of silence), so every late bake would have been rejected as bad. Changed to: reject only if shorter than endMs minus 0.5 s (the truncated-file symptom). Host race regression (real FFmpeg) passes. The check itself runs on the phone's media reader: not exercised on a device. |
| AUD-BAKE | **Not landed (held)** | Rewrites every audio clip's edit positions (in/out, cuts, transcripts) when a clean/fix is applied, undone or reverted, and saves a new origin into projects. Right idea, but it can silently shift a user's cuts, and only a phone check of clean, undo, revert, split and reopen can prove it. Not worth the risk in launch week; do it after launch with a device pass. Branch `codex/r4-aud-bake` stays as is. |
| GL-SPAN | **Landed** | Merged as is. Host replay of the production gate: 20 disjoint layers removed, every active frame kept (5 s safety margin). Cloud APK build compiles the ExportManager change. No export frame fingerprint was run (needs a device): owed with the next export check. |
| GL-MASK | **Not landed (rejected for now)** | Turns every graphics hiccup in an adjustment layer into a failed export. For a launch audience a rare slightly-wrong mask is better than an export that dies with no workaround. The better fix (draw all masks in extra passes when the one-pass version will not compile) is larger and needs a device. |
| VIS-SOUND | **Landed** | Merged as is. Cloud APK build compiles it; its unit test lives in the app module, which no cloud suite runs: not executed. |
| VIS-NEST | **Landed** | Merged as is. Same note: compiled in the cloud build, its new unit test (app module) not executed. |
| VIS-SPRITE | **Landed** | Merged as is. Same note: compiled, new unit test not executed. |
| TXT-TRANSFER | **Not landed (held for a device)** | Gives each export title its own graphics context and hands textures across with GPU fences, so every driver's behaviour matters. A mistake shows as black, stale or torn titles in an export, or a crash, and a PC cannot show that. Needs a Note 9 and a Fold export check of animated titles first. Branch `codex/r4-txt-transfer` stays as is. |
| OIL-STEP | Landed 11-20; measured on the Note 9 11-23: no gain an artist could notice. Neighbour lookups are 17-19 us per paste step with or without the memo, about 0.9% of a full oil page's paste time (27-34 ms of 3.2-3.9 s); the cost is the uniform uploads, not the lookups. Kept: exact and harmless. | `NeighbourMemoTest`; Note 9 bench `oilnew`/`oilold`/`oilnew2` (paste.nbr us per step 17.4 / 19.3 / 18.7), 27.7-28.1 C throughout |
| JB-SAVE-IO | Built, waiting on a phone: the working save is held open for the restore instead of copied whole after every autosave; the old copy stays selectable (`jb_recovery_copy`). The Note 9 run of 11-23 lost the phone (adb dropped) before the save runs; the lifecycle gate is still owed. | Branch `claude/engine-r4-20261120-e499b0`; four new `DrawingRecoveryTest` cases; cloud 1,377 + 606 green |
| ATLAS-TABLE | Not worth it (Note 9, 11-23). Every page-table definition of a full oil page together is 15-21 ms of 3.2-3.9 s paste time (about 0.5%), and keeping the texture and staging buffer saved none of it (20.3 ms new vs 20.7 ms old); most of the time is building the table on the CPU. Taken back off the branch (9a52f572b). | Note 9 bench `oilnew`/`oilold`/`oilnew2`, atlas.tableDef us summed over ten bands |
| WET-UNDO | Waiting on a phone: measure first (the 11-23 Note 9 run lost the phone before the wash). The `undo copy` log line decides whether copy-on-write blocks are worth a Large change. | Same branch, commit 566affc60 |
| WET-MAP | Waiting on a phone: measure first (same). Readbacks run on view carry, cut, print and save-while-wet, not per frame; the readback ms/KB log decides it. | Same branch, commit 566affc60 |
| VIS-IDLE | **Landed** | Host replay: 3600 idle ticks, no frame callbacks after the held frame; edits, resize, resume and detach all still redraw. Cloud APK build ok. Phone check of a paused VisLab preview still owed. |

## Codex's own handoff (11-20), checked by the orchestrator
Codex's handoff matches the table above, except that it was written before VIS-IDLE landed (828f15a4b). Two notes carried over from it, because its file sits in a git-ignored scratch folder:
- **Before AUD-BAKE lands:** the AUD-RACE post-bake check (now judged against the source end time) must be changed to check the new segment's duration, because AUD-BAKE bakes segments, not source-clock files.
- **TXT-TRANSFER's proof is a desktop GPU test** (eight readbacks down to zero, at most 1/255 off). Still needed on the Note 9 and the Fold: a decoded export of a pinned animated title with masks, seek, resize, a rebuilt graphics context, and the export time.
- Rain clean-up quality stays the owner's listening test (matched loudness against CapCut). Codex's mixer notes (per-object effects, routing, stems) are product work for after launch, not launch fixes.
