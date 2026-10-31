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

Soft on a scribble, with lines off: gap p50 41 ms at 256 px, 84 at 512, 129 at 1024, 336 at 2000. The gap is not slowness: Soft spaces dabs at .15 of its size and frames are only drawn for a new dab, so gap = spacing / pen speed (38 px, 77 px, 154 px, 300 px at about 0.9 px per ms). The earlier "Soft 2000 is 3 fps" was a wrong reading of this figure. The real cost is busy p95: 21 ms at 256, 30 at 512, 56 at 1024, 110 at 2000 (with the bench glFinish brackets), so about 256 px holds 45 fps and 2000 px about 9. Unmeasured: context loss.

## 2026-10-31 08:15 lost tiles (owner's Drawing 3, Note 9)

Four layer-2 tiles (-2_5, -1_5, -2_6, -1_6; textures 510-513, status not complete, still valid names) drew black and every autosave failed with "Check failed." No save had landed since 00:33. Under jdb on the running app the four tiles were dropped from the live map; the save at 08:32 then landed (11.08 MB) with everything else. Those four tiles' paint is gone. Copies: `C:/Temp/jb-owner-rescue-20261031`, owner media, not committed. `lostTileProbe` passes on the Note 9; suites 0 failures; drawing reopened on the new build. Cause unproven: an allocation failure under memory pressure is the leading guess (swap 1.8 of 2.0 GB, lmkd kills that minute).
