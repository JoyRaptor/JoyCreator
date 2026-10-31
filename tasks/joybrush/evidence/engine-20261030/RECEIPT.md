# Engine receipt, 2026-10-30/31 (Note 9, Adreno 630)

Build `engine/atlas-room-merge`, base `ab765c665`. Suites on the final commit: core, engine and Android all 0 failures.

| Check | Result | Folder |
|---|---|---|
| Stamp strokes, fast spiral before | Chisel worst frame 590 ms, Texture 584 ms | sweep-before-one-pass |
| Stamp strokes, fast spiral after one pass a frame | Chisel 103 ms, Texture 139 ms, Calligraphy 63 ms | sweep-after-one-pass |
| Line parity, lines on | 8 of 8 pass; Ink, Marker, Pencil, Calligraphy, Chisel, Soft, Round crc equal to U8 | line-parity-final |
| Texture crc | differs run to run with and without the change (spacing jitter), not caused by it | runs 7a/7b, not kept |
| 22 media brushes, main vs branch | no crash or refusal; timing level within run noise | media22-main, media22-branch |
| Animation clips probe | passed; the runner printed TIMEOUT because it waits for a brush label the probe does not log | media22-branch/23.log |
| Water undo probe, tightest room | 0 tiles differ on undo and redo, 1936 write-backs | not kept; result in this table |

Soft on a scribble, with lines off: gap p50 41 ms at 256 px, 84 at 512, 129 at 1024, 336 at 2000. Not fixed: the gap is about double the measured work, so the wait is outside the paint path. Unmeasured: context loss.
