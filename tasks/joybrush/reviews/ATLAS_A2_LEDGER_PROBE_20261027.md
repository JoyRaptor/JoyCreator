# Atlas A2 ledger probe: first Note 9 results (2026-10-27, Lead)

Probe: `MediaAtlasLedgerProbe` on `lead/atlas-room-repair-20261025` (b241700f9 → 6d3e296d4 → 5b4504cc4 → de3b3f72a, local branch).
Two fresh probe-owned `GlPaintEngine` + `MediaCanvas` runs per brush, same synthetic frames (5 samples / 16 ms): roomy (phone-sized
atlas, park off) vs squeezed (`jb_atlas_slots`, default 6x4 = 23 slots, the app's smallest; park 24). Every tile a save would read is
hashed and compared. Bench case `mediaAtlasLedgerProbe`; host runner `ui-scripts/run_native_probe.py` (fenced log, saved-file manifest).

## 6d3e296d4 on the Note 9 (evidence/native-probes-20261027/atlas-ledger-6d3e296d4)
| Brush | Roomy | Squeezed | Result |
|---|---|---|---|
| media:dry:HB | 51 tiles, 63 slots | 51 tiles, 23 slots | identical (0 differing); counters then lacked evictions, so "not proven" |
| media:paste:Oil round | 121 tiles | 111 tiles | 10 p0/p1 entries present only in roomy: tiles (-4..-1, 0) and (1, 2). Blank-vs-absent not yet told apart |
| media:wet:Wash | refused | refused | `MediaRoomException` mid-stroke: "A block of water and its ring do not fit beside what the frame holds (9 areas of 22)" |
At 4x4 (b241700f9) the same refusal (9 of 14). 173 saved files unchanged on every run.

## Reading of the wash refusal (code, not yet device-confirmed)
`MediaAtlas.admitInner` pins the frame's paint tiles plus its look ring (`lookHalo + 1 + LOOK_REACH`) for the whole frame. When the
water goes out of core, `blocks.enter` needs a block + ring (9) beside those pins and tiles with brush input waiting. The dry-only
fallback (`lookInParts`, look drawn tile by tile) does not exist for water. de3b3f72a makes the refusal report what held the room
(frame pins, waiting input, overlap) so the next device run confirms which one before any engine change.

## Next
1. Device run of 5b4504cc4/de3b3f72a (APK preserved at C:/Temp/jb-lead-ops/apk-atlas-ledger-5b4504cc4): eviction counts, blank-vs-paint
   for the oil tiles, and the refusal breakdown. Waits for the owner to finish testing oil on the Note 9 (its install replaces 8402bc0ef).
2. If the frame's look ring is what fills the room: release the look ring before water blocks run and draw the water frame's look in
   parts, as dry media already do.
3. Then fault injection (admission rollback, write-back) per ATLAS_A2_PROBE_SCOUT_20261027.md §4.
