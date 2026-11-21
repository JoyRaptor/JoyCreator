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
| SAVE-CONTENT | Confirmed and fixed on `codex/r4-save-content` | Reopen the latest PiP, adjustment and intentionally empty cuts. SavedTimelineContentTest went from five failures to five passes. All 45 base storage checks also passed including the historical hollow-file backup. |
| SAVE-ROTATE | Confirmed and fixed on `codex/r4-save-rotate` | Protect the readable backup during a recovery save. Real WorkLossStorageTest failed two assertions before. Afterward 49 passed, covering backup bytes, complete orphan recovery and rejection of partial staging. |
| AUD-RACE | Fixed on `codex/r4-aud-race` | Host regression `tools/jvm-harness/run-audio-bake-race.py`: baseline shares a temp path and unlink-before-rename loses an existing result. After fix two keys, two real AAC encodes, two callers on one key, interrupted waiter detaches, original and both outputs decode. Device cancellation/UI untested. |
| GL-SPAN | Confirmed and fixed on `codex/r4-gl-span` | Cull adjustments outside the export piece before allocating their passes. The production admission gate submitted 20 disjoint passes on base and zero after. The clock sweep retained every active frame, including open-ended layers and negative loop offsets. GPU timing remains unmeasured. |
