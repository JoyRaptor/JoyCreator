# JB-4.02 — adversarial audit (muse, for bunny)

- Auditor: `muse-spark-1.3-contributor-free`, family Muse Spark (Meta). Read-only: no gradle,
  no edits. Spec `tasks/joybrush/specs/JB-4.02_cell_order_and_play.md` read whole (Decisions
  1–17, R36 weights close-out, Q1 session-only, Q3 playAll, xr notes). Local HEAD `7bc60742`.
- Target: 🟧 Built (core) 2026-09-30 (sibling orchestrator's builder; 3 new files, 21 tests).
- Files read fully: `core/.../sprite/CellRoll.kt` (362), `CellRollTest.kt` (988),
  `CellRollShapeTest.kt` (469); reviewed clock `anim/PlaybackClock.kt`, `anim/FrameStepper.kt`;
  `doc/AnimOps.kt:442-522` (`frameStartsMs/totalDurationMs/playableSchedule`).

**AUDIT-VERDICT: CLEAN (0 BLOCKER, 0 MAJOR).**

## Prior-review fixes (all confirmed present, not trusted from the log)

- Decision 17 cursor-after-remove: `clampedTo` = `coerceIn(0, max(0, kept.size-1))`
  (`CellRoll.kt:250-251`), used by `untapped/untappedAll/prunedTo`; tests 2,3,17 pin it.
- J2 contradiction fixed: shape test walks FIELDS + PARAMETER types only, allows `Board`
  return (`ALLOWED` contains `Board`; asserts `returned.containsKey("Board")`).
- `removedBy` null→Int fixed: spec `:314`, code `:321`, test pins `val removed: Int`.
- `labSeq.add` 2122 consistently; no 4304 remains.
- Lesson 9: both no-repeat rules explicitly deleted with reasons; only per-probe
  `frameIndexAt==shown` kept.

## Attacked by hand-walk (7 lines, no defect with proof)

1. **1501/2501-step walks**: fixture `[(0,3)..(5,3)]` @12fps → duration 250.0 exact,
   starts `[0,250,500,750,1000,1250]`, range 1500.0, backSpan 1000, cycle 2500.0; emissions
   `[1,2,3,4,5,4,3,2,1,0]` @ `[250,…,2500]` verified (rising AT, falling +1 infimum, seam 1500
   emits nothing, turn 2500 → 0). Walk carries `shown`, asserts equality every ms, plus
   emission-instant identity and an independent 21-probe period search returning 2500.
   LOOP/LOOP-hold/PING_PONG/ONCE midpoints re-derived correct. No instant/emission confusion.
2. **Ping-pong seam**: seam AT → frame 5, seam+0.001 → frame 4 explicitly asserted; single 5
   in the emission list. Deleted false rules documented, not re-asserted.
3. **Decision 17**: `coerceIn` ≡ app's clamp for all reachable cursors; remove-last,
   prune, remove-only→empty, remove-first-at-0 (catches clamp-to-size bug), `cleared/playAll`→0
   all pinned and matching `unAddCell`/`pruneDeadFrames`.
4. **Session-only Q1**: imports only `PlayMode,Board,BoardKind,Frame,RectPx` — no
   document/archive/file/export path; `asBoard` builds a transient `Board(id="roll")`; J1 asserts
   byte-identical `DocJson.encode` before/after a full 2501-step play with no roll keys.
5. **Holds**: `cellsAndHolds` = `cells to holds`, one per entry; test 15 asserts
   `[(0,3),(1,1)]→([0,1],[3,1])` ≠ `([0,1,1,1],[])`; lengths equal over all 12 suite rolls;
   test 14 pins `1..16` + 200 LCG moves. No `Clip`/`SpritePacker` type or import (J2 denies
   them; only lowercase prose "packer's clip type").
6. **Edges**: empty-roll refusals/identities, `cellAtIndex` OOB throws, single-cell ping-pong
   degrades to `cycle==rangeMs` (no spin), remove-during-play safe by immutability, empty-board
   play throws from `PlaybackClock:49 require(frames>0)` by design (view-half guard; spec
   silent — not a core defect, not filed).
7. **Vacuous/unguarded recursion (Lesson 8)**: `collect:386-432` guards `HashSet<Type>` of
   expanded nodes through every recursive call — the exact remedy. Non-vacuity present
   everywhere (suite size 12, `bumps>0`, proved-sets, `steps==2501`, `2n-2` sizes, `removed: Int`
   typing). Test-12 24 fps boundaries exact (`125.0`-family, `±0.001` probes dwarf ulps).
