# STU-5: Studio's structure and new primitives

**Milestone:** M1 (structure) / after launch (primitives)  ·  **State:** ⬜ planned  ·  **Owner:** open (Opus; one at a time, they touch the editor core)  ·  **Checked:** 2026-10-28

## Why it matters
Plans from the Studio lead and the owner that fell out of the 10-27 roadmap (rows in [`FUTURE_PLANS.md`](../FUTURE_PLANS.md) §1.2).
The structural ones make every later Studio fix cheaper and stop "fiddly" bugs; the primitives are the owner's asks.

## The pieces
| Piece | Source | State |
|---|---|---|
| **One authority for timeline time to source time** (four copies disagree; "the single most valuable structural fix left") | FUTURE_PLANS 1.2 S6 | not built |
| **One snapping authority, app-wide** (owner: "fiddly"): the playhead snaps to keys, objects align to each other | 1.2 S7 | magnet built; the rest not |
| **Export speed**: the one-pass GPU stage, the owner's "one tap, fast" | 1.2 S17; old roadmap 1.9 | not built |
| **Primitives**: a Path object, text on a path, per-letter channels, paths as masks and motion paths | 1.2 S8, owner 09-24 | not built |

## Done when
- [ ] Each piece is phone-proven with a STATE row, or parked by the owner.

## Files and traps
- `FaditorEditorActivity` is 45,000+ lines: new code goes in small classes (owner rule), legacy kept until the new is better.
