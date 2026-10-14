# NOTE9 recorded bake and archive proof — 2026-10-14

## Result

The retained full JSON reports contain six complete passing cases: five `joybrush.ink` runs (`behind`, `multiply`, `normal`, `screen`, `erase`) and one `joybrush.watercolour` `normal` run. Each report covers the concrete `createRecordedScene` / `stageBake` path and public engine `undoStep` / `redoStep` on a throwaway engine over a continuous empty plane.

| Brush / operator | Case | Brush fixture version | Source tiles | Result |
|---|---|---:|---:|---|
| ink / behind | `recordedBakeBehindProbe` | 6 | 4 | pass |
| ink / multiply | `recordedBakeMultiplyProbe` | 9 | 4 | pass |
| ink / normal | `recordedBakeProbe` | 6 | 4 | pass |
| ink / screen | `recordedBakeScreenProbe` | 9 | 4 | pass |
| ink / erase | `recordedBakeEraseProbe` | 6 | 4 | pass |
| watercolour / normal | `recordedBakeProbe` | 10 | 4 | pass |

Across the six full reports, the actual source tile count is 24 (four per case), with 144 recorded exact RGBA8 comparison rows and zero differing channels. All six reports have two successful archive snapshots, before and after bake: 12 writes, 12 reads, and 12 completed in-memory roundtrips. The archive checks report raw GPU bytes preserved and exact metadata/physical IDs. Aggregate archive exact CPU payload comparisons: 120.

GPU/readback diagnostics sum to 318 GL error checks with zero observed errors, 240 framebuffer checks with zero incomplete framebuffers, and 288 external feedback/alias checks with zero failures. `internalEngineChecksCounted` is false and the reports explicitly scope feedback checks to external ownership/alias checks, not per-draw internal counters.

Every report passed and had bake commit, public undo and redo acceptance, sequence/operator preservation, record removal, restoration by undo, and removal by redo. The opacity-twice CPU negative control differs from the recorded canonical copy in every case: 5,194 different channels for each ink operator and 8,781 for watercolor. This establishes that the fixture distinguishes the single-opacity canonical-copy result from applying opacity a second time; it is not a claim that the CPU negative control is the production path.

## Build and device identity

The matching install receipts identify source `487a6f36+tree-cffb66ab`; the install receipt requested installation for the ink run, while the watercolor receipt records no reinstall. Both report installed SHA-256 `FEADBF3E7EDD076A18C9D31B40C3830FDD3481C377BA70F2B1607049BD18E6E3`, matching `apk-bake-log-cffb66ab/receipt.json`. That build receipt records `PASS57s`.

The code identity is reported as source HEAD `487a6f36cf330a27b20163c140ef01674e176d3b`, tested tree `cffb66abb315abb24084cb2a631b24232fc7cc67`, and benchmark blob `c7a72ac9baf52790b943a5c7b02d07f19f776cf4`. These are separate identities: the tested tree is not represented as a commit, and no later root benchmark-fix commit is included in this proof.

The reports identify Qualcomm Adreno (TM) 630 running OpenGL ES 3.2 V@415.0 (GIT@fdd61e0, I20154638fb, 1602078382; Date:10/07/20).

## Earlier attempts and limits

The earlier run against source HEAD `487a6f36` emitted large reports as single JoyBrushPerf JSON lines. Android log output truncated those oversized lines, so that capture does not supply a complete report and is not counted as a pass. The initial ink multiply and screen fixtures also failed because their blend modes required brush version 9 while the available frozen fixture was version 6. The later tested tree supplied version 9 fixtures for multiply and screen (and version 10 for watercolor); those six full retained reports pass. The PowerShell chunk reassembly scratch checks passed, including out-of-order chunks, multi-byte UTF-8, incomplete sets, duplicate/conflicting metadata, invalid Base64, concurrent cases, and ordinary unchunked reports.

This is synthetic metadata plus actual physical GPU source/look readback in memory. It does not demonstrate loading a working/recovery document, host persistence, or a physical artwork-quality outcome. The working receipts only show the installed working state was present and unchanged. Capture remains diagnostic and default-off. Ordinary painting and media flows are outside this proof.
