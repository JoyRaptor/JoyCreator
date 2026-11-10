# Atlas solid: receipt, 2026-11-10

Job card: [ATLAS-SOLID](../../jobs/ATLAS-SOLID_never_refuse.md). Branch `claude/atlas-solid-20261109`, base `origin/joy-creator` `93c1cf76f`.
Session: Claude Fable 5.2, the owner's request of 2026-11-09 ("nothing breaks, no running out of RAM that would scare an artist").
Phone: the Note 9 (SM-N960U, Android 10), build `aadf3bb96`, APK sha256 `099156564f4c4805...`. Evidence: `../evidence/atlas-solid-20261110/`.

## What was run

| Check | Result |
|---|---|
| Core media tests through Gradle (`:joybrush:core:jvmTest --tests "...core.media.*"`, at `c8c3fbbb7`) | 233 tests, 0 failures, 0 skipped |
| `mediaFailureProbe` with Palette knife 1 (now includes `roomMade` and `farMark`) | pass. `roomMade`: 10 tiles set, 0 of the first line's tiles changed, far line whole (181,689 px), 0 of 183,628 px lost under a line across the set paint, 3 undos give a blank layer, no fourth step. `farMark`: 11 cached tiles let go, no failure |
| `mediaUndoProbe`, `mediaAtlasProbe`, `mediaAtlasLedgerProbe` (Oil round), at `c8c3fbbb7` | all pass |
| Long session, real drawing screen, 40 oil strokes (Oil round, Palette knife 1), 24 MB room | before the atlas handed back its cache: 24 of 40 refused. At `aadf3bb96`: **0 of 40 refused**, room held at or under 24 MB, 0 failed frames |
| Same, 20 pencil strokes (Proto Pencil), 12 MB room | 0 of 20 refused |
| Same, 20 watercolour strokes (Round), 60 MB room | **14 of 20 refused** ("There is more wet paint on this drawing than this phone can keep at once..."). Everything in the room was wet and held by the tool; nothing could set. Not fixed |
| Same 40 oil strokes, the phone's own room (315 MB) | 0 refused, nothing set (the room never filled) |
| All 22 realistic brushes, `scribble` and `turns`, default settings | 44 runs, 0 refused, 0 failed frames, 0 `JoyBrushTiles` lines, 0 admission misses |
| Saved drawings on the phone | 33 files, md5 identical before and after |
| Phone temperature | 25.7 C at the start, 28.9 C at the end; no throttling seen |

## Limits and what was not shown

- The small rooms (12, 24, 60 MB) are far smaller than the phone's real room, to reach the limit in two minutes. With the real room the limit is reached only after a long session; that long session was not run.
- **The 10-31 lost tiles and the failed tile copy were not reproduced.** The new at-birth check, `GraphicsMemoryException`, the spare-texture release and the smaller atlas afterwards compile and do not disturb the runs above, but none of them has fired on a phone. Their cause is still open.
- In the tiny-room oil pictures a faint comb of thin vertical lines shows in one patch (`marOil5-comb-detail.jpg`), about one brush step apart. Similar ticks show at stroke ends in the 315 MB run, where nothing set, so this looks like Palette knife 1's own step marks on flat paint. Not proved either way: the two runs are not pixel-comparable (strokes are seeded by the clock). The owner should look.
- A tap leaving a dot is proved in the JVM test only (Oil round lays a step). Not looked at on the phone, and not checked brush by brush.
- The wider JVM suites (androidkit, joybrush-android) were not run; only the core media tests and the full app build.
- Note 20: not installed.
- Earlier small-room runs through the real screen by other sessions (`jb_media_budget_mb`) did not hold their room past the first stroke: the screen reset it. Fixed here (`mediaBudgetHeld`); results from before this date that relied on it are suspect.
