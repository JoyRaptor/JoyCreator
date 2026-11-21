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
| OIL-STEP | Landed 11-20 (engine round 4 session). A paste step around the same tile under the same page table copies its neighbourhood instead of 25 fresh lookups; every table change (residency, walls) invalidates it. Gain not yet timed: the `paste.nbr us` bench phase is on `claude/engine-r4-20261120-e499b0` for the Fold batch. | `NeighbourMemoTest` (5,000 mixed questions, every answer equal to a fresh lookup); cloud jb-androidkit 1,375/1,375, jb-android 606/606 |
| JB-SAVE-IO | Built, waiting on the Fold: the working save is held open for the restore instead of copied whole after every autosave; the old copy stays selectable (`jb_recovery_copy`). Needs the lifecycle gate (rebuilt, graphics lost, left mid-save) on a device before landing. | Branch `claude/engine-r4-20261120-e499b0`; four new `DrawingRecoveryTest` cases; cloud 1,377 + 606 green |
| ATLAS-TABLE | Built, waiting on the Fold: a same-size page table is written into its texture (glTexSubImage2D) through a kept staging buffer. GL-only, so exactness needs a device digest; the `atlas.tableDef us` phase measures whether it matters. | Same branch, commit 416040642 |
| WET-UNDO | Waiting on the Fold: measure first. The `undo copy` log line (cells, KB, wet blocks of the sheet, history against the 128 MB cap) decides whether copy-on-write blocks are worth a Large change. | Same branch, commit 566affc60 |
| WET-MAP | Waiting on the Fold: measure first. Readbacks run on view carry, cut, print and save-while-wet, not per frame; the readback ms/KB log decides it. | Same branch, commit 566affc60 |
