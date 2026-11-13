# Codex worktree recovery inventory — November 13, 2026

Companion to [engine handback](CODEX_ENGINE_HANDBACK_20261113.md). Read-only scan; nothing cleaned, staged, committed or uploaded from these old work folders.

Fetched origin main: `bb04ad91f9f56ae6eec1579c14bad34af5179991`. Registered worktrees: **181**; **57** dirty; **57** with HEAD ancestry not reachable from fetched origin refs. These totals include active Claude and other lanes, not just Codex. Counts drift during concurrent work.

## What is safe to conclude

- Last Codex lead folder `C:/Temp/jb-live-goal-20261104` is clean at `eae6bc255db12aefe28aa0a6ee1b00d587ce97ee`; `origin/lead/live-goal-20261104` contains it. Older lead `C:/Temp/jb-live-current` is clean at `fe2838486563fefcc91196551a4b725e23d76d51`, also reachable on origin. Ordinary integrated line and Select folders are clean and reachable too.
- **Other historical helpers do contain unsaved/local-only candidates.** Do not generalize the last folder’s cleanliness to all folders. Their presence is not proof of a missing feature: many are exact uploaded files or older variants already superseded.
- “Local-only commits” below means `git rev-list HEAD --not --remotes=origin`, after a fresh fetch. It is not a patch-equivalence test. A squash, cherry-pick or a different merge may already carry the work.
- Dirty includes staged changes, unstaged changes and untracked files. Logs alone can make a folder dirty. The table does not imply a source patch is missing.
- Blob checks below hash file bytes through Git attributes and compare against objects reachable from fetched origin refs. An exact match means those file contents are recoverable remotely somewhere, not necessarily at the same path or on main. Unmatched means review required; it does not mean the functionality is absent.
- Full scan JSON stays locally at `C:/Temp/codex-engine-handback-20261113-inventory.json`; focused blob results at `C:/Temp/codex-engine-local-source-20261113.json`. Secret contents and owner media were not read. Protected primary and all old worktrees were preserved.

## JoyBrush/helper folders

| Folder | HEAD | Dirty status entries | Local-only commits |
|---|---|---:|---:|
| `C:/Temp/helper-main` | `f79efc378c8b` | 1 | 0 |
| `C:/Temp/helper-room` | `8a0680ced2fe` | 1 | 0 |
| `C:/Temp/jb-520a` | `bfab94da80af` | 7 | 0 |
| `C:/Temp/jb-520a-recovery` | `f58bf2024d91` | 34 | 0 |
| `C:/Temp/jb-atlas-merge-20261030` | `f7221c86fec5` | 0 | 0 |
| `C:/Temp/jb-atlas-room-repair-20261025` | `de3b3f72ae7c` | 0 | 0 |
| `C:/Temp/jb-board-state-map-20261027` | `e3c9e7280f9f` | 0 | 1 |
| `C:/Temp/jb-capture-current` | `3993de74ac6b` | 2 | 0 |
| `C:/Temp/jb-captured-domains` | `caae13b4efe3` | 5 | 0 |
| `C:/Temp/jb-captured-engine` | `cf559dad0030` | 30 | 0 |
| `C:/Temp/jb-captured-scene` | `596b02d3439d` | 2 | 0 |
| `C:/Temp/jb-captured-source` | `178e72a3abc1` | 20 | 0 |
| `C:/Temp/jb-claude-main` | `2a21d866b273` | 6 | 0 |
| `C:/Temp/jb-clips` | `1553c5b45077` | 0 | 0 |
| `C:/Temp/jb-codex-select` | `b3fd5f98b0fb` | 0 | 0 |
| `C:/Temp/jb-codex-unibrush` | `6a5bdae1d617` | 0 | 0 |
| `C:/Temp/jb-cold-faults` | `6a3508ed0eb4` | 2 | 2 |
| `C:/Temp/jb-doc-maintenance-20261026` | `87abacb6de3d` | 1 | 0 |
| `C:/Temp/jb-helper1-atlas` | `f834e9d0fa1e` | 0 | 0 |
| `C:/Temp/jb-justworks` | `41785d4cfb44` | 2 | 0 |
| `C:/Temp/jb-live-current` | `fe2838486563` | 0 | 0 |
| `C:/Temp/jb-live-goal-20261104` | `eae6bc255db1` | 0 | 0 |
| `C:/Temp/jb-media` | `019739abb583` | 23 | 0 |
| `C:/Temp/jb-merge` | `58388a3460f8` | 0 | 1 |
| `C:/Temp/jb-oil-b` | `7e92d951cec8` | 0 | 0 |
| `C:/Temp/jb-one-line-20261030` | `b7b6831cfd60` | 0 | 0 |
| `C:/Temp/jb-one-line-swap` | `1ad5730f08cf` | 0 | 0 |
| `C:/Temp/jb-opencode-dry-apply-parity` | `542f2482524c` | 0 | 2 |
| `C:/Temp/jb-opencode-dry-paper-only` | `72ae66d43729` | 0 | 2 |
| `C:/Temp/jb-opencode-media-snapshot` | `019739abb583` | 25 | 0 |
| `C:/Temp/jb-opencode-mid-05` | `f6ea4e19698f` | 0 | 1 |
| `C:/Temp/jb-opencode-mid-07` | `9a27ba4ceefc` | 0 | 1 |
| `C:/Temp/jb-opencode-mid-08` | `5b98cd7eeef8` | 0 | 1 |
| `C:/Temp/jb-opencode-mid-09` | `ca3d7fc5d0f1` | 0 | 1 |
| `C:/Temp/jb-opencode-mid-10` | `3c2b9935213b` | 0 | 1 |
| `C:/Temp/jb-opencode-mid-11` | `ec8e9d3ca2bd` | 0 | 1 |
| `C:/Temp/jb-opencode-mid-12` | `a17f88ba5f32` | 0 | 1 |
| `C:/Temp/jb-opencode-mid-13` | `731829e5b95a` | 0 | 1 |
| `C:/Temp/jb-opencode-mid-14` | `2f4522f75f71` | 0 | 1 |
| `C:/Temp/jb-opencode-mid-15` | `7dc657702e8a` | 0 | 1 |
| `C:/Temp/jb-opencode-mid-16` | `927898c75e14` | 0 | 1 |
| `C:/Temp/jb-opencode-mid-17` | `0712b0714bb6` | 0 | 1 |
| `C:/Temp/jb-opencode-mid-18` | `02451f8074bb` | 0 | 1 |
| `C:/Temp/jb-opencode-mid-19` | `ca6cf8fb2617` | 0 | 1 |
| `C:/Temp/jb-opencode-mid-20` | `55b99e99bcb5` | 0 | 1 |
| `C:/Temp/jb-opencode-mid-21` | `709eec59a691` | 0 | 1 |
| `C:/Temp/jb-opencode-mid-22` | `1b91d42e8a1a` | 0 | 1 |
| `C:/Temp/jb-opencode-mid-23` | `e9070caeb4c1` | 0 | 1 |
| `C:/Temp/jb-opencode-mid-24` | `f95c3cd854fc` | 0 | 1 |
| `C:/Temp/jb-opencode-mid-25` | `9403ee3eaa7a` | 0 | 1 |
| `C:/Temp/jb-opencode-mid-26` | `28760f589594` | 0 | 1 |
| `C:/Temp/jb-opencode-mid-27` | `43190964446d` | 0 | 1 |
| `C:/Temp/jb-opencode-mid-28` | `1e37c05b98f5` | 0 | 1 |
| `C:/Temp/jb-opencode-mid-29` | `f4c1479c890f` | 0 | 1 |
| `C:/Temp/jb-opencode-shader-verification` | `6a37102e8d49` | 3 | 0 |
| `C:/Temp/jb-opencode-test-verification` | `e44d86c35f4f` | 4 | 0 |
| `C:/Temp/jb-opencode-verify-09-18` | `d5896ab2308f` | 20 | 0 |
| `C:/Temp/jb-paper-approved-20261029` | `8c25efc9bc1b` | 0 | 0 |
| `C:/Temp/jb-paper-baseline-20261029` | `cd1e48805a23` | 0 | 0 |
| `C:/Temp/jb-paper-cache-20261029` | `eb5dc205370f` | 0 | 0 |
| `C:/Temp/jb-paper-evaluation-20261029` | `35d9b7b775f4` | 0 | 0 |
| `C:/Temp/jb-paper-recovery-20261026` | `4d4a85e17845` | 0 | 0 |
| `C:/Temp/jb-paper-state-map-20261027` | `71cc6c60a116` | 0 | 1 |
| `C:/Temp/jb-promotion-inventory` | `5b3ebdcaf355` | 6 | 0 |
| `C:/Temp/jb-region-routing` | `d5896ab2308f` | 3 | 0 |
| `C:/Temp/jb-roadmap-publication-20261026` | `93c1cf76fe78` | 0 | 0 |
| `C:/Temp/jb-root-media-integration` | `7087eae4bc22` | 29 | 0 |
| `C:/Temp/jb-select-reconcile-20261024` | `ace333729236` | 0 | 0 |
| `C:/Temp/jb-send-existing-20261027` | `9a6140ae7d91` | 0 | 0 |
| `C:/Temp/jb-symmetry` | `789bef0af8e0` | 0 | 0 |
| `C:/Temp/jb-symmetry-a1` | `112235fee842` | 0 | 1 |
| `C:/Temp/jb-symmetry-a2` | `c15a0cb37a26` | 0 | 3 |
| `C:/Temp/jb-symmetry-b` | `a19d73d2a2f0` | 0 | 2 |
| `C:/Temp/jb-symmetry-c` | `b93f82786b2d` | 0 | 1 |
| `C:/Temp/jb-symmetry-d` | `651755a6ba10` | 0 | 1 |
| `C:/Temp/jb-symmetry-e` | `789bef0af8e0` | 0 | 0 |
| `C:/Temp/jb-symmetry-f2` | `789bef0af8e0` | 2 | 0 |
| `C:/Temp/jb-symmetry-f3` | `789bef0af8e0` | 4 | 0 |
| `C:/Temp/jb-symmetry-merge` | `a1a16627f28b` | 0 | 0 |
| `C:/Temp/jb-symmetry-recover-20261027` | `9af3d91c0ed0` | 0 | 0 |
| `C:/Temp/jb-unibrush-baseline` | `107748a78d12` | 1 | 0 |
| `C:/Temp/jb-unibrush-merge-20261030` | `72cfa6f51a3d` | 0 | 0 |
| `C:/Temp/jb-v6` | `36c064287415` | 30 | 0 |
| `C:/Temp/jb-vector-tilt` | `8e81e0d4e35c` | 6 | 0 |
| `C:/Temp/jb-vector-tweens` | `05474f3646c5` | 0 | 0 |
| `C:/Temp/jb-watercolour-finish-20261024` | `e7b6f8a1082c` | 0 | 0 |
| `C:/Temp/jb-watercolour-fixture-20261026` | `6b1912bd1ce1` | 0 | 0 |
| `C:/Temp/jb-worker2-board-strip` | `e0d1eb04753d` | 1 | 1 |
| `C:/Temp/jb-worker2-composer` | `de7e1d6615b5` | 1 | 1 |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-2.03afix` | `ac85e26638d1` | 0 | 1 |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-8.01b` | `0d5c2e0e7839` | 0 | 1 |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-8.04fix` | `379782abd60b` | 3 | 0 |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.01fix` | `99c5f616b254` | 0 | 2 |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.03` | `ccf2980b1ba5` | 3 | 0 |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.03b` | `b1b30633dd61` | 17 | 0 |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.06` | `2c746d3c14e6` | 9 | 0 |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.06b` | `e5cfa81594c8` | 1 | 0 |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.06c` | `0dd712ed0cb9` | 6 | 0 |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.07` | `cd726f0b9f55` | 10 | 0 |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.08` | `0b7546b57521` | 9 | 0 |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.10` | `b6202c602c61` | 24 | 0 |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-board-chrome` | `c5e003461f7f` | 4 | 5 |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-brush-specialist` | `1d0eafca6d0d` | 6 | 0 |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-d02a` | `65682779365e` | 1 | 0 |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-d02afix` | `41ce73accfae` | 0 | 1 |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-0.10` | `413b9417ed61` | 0 | 1 |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-1.07` | `09c50c3085dc` | 0 | 1 |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-2.02c` | `d855dcb4b88b` | 0 | 1 |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-2.11` | `e6265db10ec6` | 0 | 1 |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-3.02` | `156f616be50c` | 0 | 1 |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-3.05` | `59ab8dcbfe51` | 0 | 1 |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-5.01` | `f4ed3924d3dc` | 0 | 1 |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-5.03` | `94a11de98306` | 0 | 1 |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-5.11` | `97440d329cdc` | 0 | 1 |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-8.04` | `0063f7447331` | 0 | 1 |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-pencil-quality` | `df786ae196ed` | 22 | 0 |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-verify` | `3b1ae80ff1b8` | 0 | 2 |

## Focused dirty source comparison

Selected original lead/engine/scene/source/capture/codec/media/symmetry helpers were checked more deeply. Both staged-only and unstaged/untracked names are included. Working-file contents were hashed; an independently different index version still needs `git diff --cached` review before cleanup. No builds were run.

### C:/Temp/jb-520a

HEAD `bfab94da80af05450fe76dab04c0534de728a2d0`. Checked 7 source/document files; 4 exact contents reachable on origin; **3 unmatched candidates**.

Review these existing files before any removal:

- `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/JbArchive.kt` — blob `422c836aea3d6c5073e2e3a4b40bdae46bc5dd9e`
- `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/doc/DocModel.kt` — blob `5c8eb87eb75baedc7d2cfd947ea5f17dbc886b96`
- `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/vector/LineRules.kt` — blob `eb53e32f2c92e4d0935b4bc21b6734876c9f1fec`

### C:/Temp/jb-520a-recovery

HEAD `f58bf2024d914496a8d4633176c594a8930f2703`. Checked 34 source/document files; 22 exact contents reachable on origin; **12 unmatched candidates**.

Review these existing files before any removal:

- `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/io/CanvasSnapshot.kt` — blob `848d06eecc104a6ff4edab3ea0f3275032b6bed8`
- `joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/io/ArchiveBoardCorpusTest.kt` — blob `4f67011d719f6b3515d6050a6c2cb5e95b8e728c`
- `joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/io/LineArchiveTest.kt` — blob `b7abac70e41126cee3e5efb3aa7729dfec497eea`
- `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/doc/AnimationBoardOperationCorpusTest.kt` — blob `7c6e37f6948d3db77538dd2ada73a12740e4bfff`
- `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/doc/AnimationBoardOpsTest.kt` — blob `5c713cc7f71be04240136afd17de8fe9875a6b69`
- `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/doc/BoardPreviewHistoryTest.kt` — blob `ef402acbcb9552096c2dc47c1c2bf5cf55e7bd1d`
- `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/doc/LineDocumentTest.kt` — blob `37f0e91a2eec7748db4a92986c71474c8ae89d01`
- `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/doc/MediaLayerDocTest.kt` — blob `1f01746385421913f86828c8523601bde86bbcd8`
- `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/doc/SpriteCellOpsTest.kt` — blob `ddf1211ed94f04dbdfc5b81636002593e98c2584`
- `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/doc/VectorBoardOwnershipTest.kt` — blob `82bb168d7aee623167ffb150abecad8fb5191eec`
- `tasks/LANES.md` — blob `faa0177e5c92d450b9627469baf327bd8d26fa3a`
- `tasks/joybrush/JB520A_RECOVERY_PLAN_20261012.md` — blob `c75b16cc04afab10476f40dcf7fc52d91b992322`

### C:/Temp/jb-capture-current

HEAD `3993de74ac6bd11eead6b631158b207d758cc455`. Checked 2 source/document files; 0 exact contents reachable on origin; **2 unmatched candidates**.

Review these existing files before any removal:

- `joybrush-android/src/test/kotlin/cc/joycreator/joybrush/android/board/StrokeCaptureDispatchTest.kt` — blob `88bea5c6fc0d465ee0ff3603261d2c4e69d8b68b`
- `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt` — blob `3885403fa91b9d3737c3b1af47f52b549ee47c78`

### C:/Temp/jb-captured-domains

HEAD `caae13b4efe3a1386bf030706f2c5fd64522a369`. Checked 5 source/document files; 2 exact contents reachable on origin; **3 unmatched candidates**.

Review these existing files before any removal:

- `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/ColdPayloadProbeFixture.kt` — blob `a3bd6fc2ee6246a4a7906ae7cfd342d79855f510`
- `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/RecordedColdPayloadProbe.kt` — blob `749b0c67152d0c8a3f73d40bc3b5786a6e15f5c7`
- `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/stroke/CapturedPaintDomainsTest.kt` — blob `0c47ad24e7590ad86760135cb372c819e5a95a1c`

### C:/Temp/jb-captured-engine

HEAD `cf559dad00307cab40c32f08646cf4add7b3cceb`. Checked 30 source/document files; 29 exact contents reachable on origin; **1 unmatched candidates**.

Review these existing files before any removal:

- `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/RecordedLiveSourceProbe.kt` — blob `91590e7739dcc2831e7a03e237e488c35bee2eb6`

### C:/Temp/jb-captured-scene

HEAD `596b02d3439d30b32db58c29fb488757c1b0d296`. Checked 2 source/document files; 2 exact contents reachable on origin; **0 unmatched candidates**.

No unmatched working-file contents in this focused set. This does not authorize deletion.

### C:/Temp/jb-captured-source

HEAD `178e72a3abc1ca1904a8a2b038f14ec19a2b1df6`. Checked 20 source/document files; 14 exact contents reachable on origin; **6 unmatched candidates**.

Review these existing files before any removal:

- `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/RecordedCapturedCopyProbe.kt` — blob `5b11d1533b722e8798e1c1b315bb947e4e3c33c6`
- `joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/gl/GlColdRecordedLoadTest.kt` — blob `aa5b9b1f84654dca0d6de919df05a2bfed057032`
- `joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/io/ColdRecordedLoadPlanTest.kt` — blob `4e30487ad1668ebf500d836d725996401eb80533`
- `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/stroke/CapturedStrokeRules.kt` — blob `5ded794f25e5d469b01450b2bc64eedacad2699a`
- `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/select/CapturedRecordHistoryTest.kt` — blob `bcca0bd53499b176d2d8365aacb9eee38c145660`
- `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/vector/CapturedConsumerGateTest.kt` — blob `8d65304120695fa80819c9adc981019470cae5eb`

### C:/Temp/jb-cold-faults

HEAD `6a3508ed0eb46c0e9a5455825ea5aaa812537596`. Checked 2 source/document files; 0 exact contents reachable on origin; **2 unmatched candidates**.

Local-only ancestry:

- `6a3508ed0 Fix fault matrix fixture import and distinguish pre-load paper restoration`
- `05b3d6232 Exercise controlled cold source upload and retirement rollback on disposable engines`

Review these existing files before any removal:

- `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/ColdPayloadProbeFixture.kt` — blob `86e5beb6da97f7a4a4594ca28099840314dfa91d`
- `joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/gl/ColdPayloadProbeFixtureTest.kt` — blob `aaf8af34f54f25a8a824ef1d7a34328d09acbd38`

### C:/Temp/jb-media

HEAD `019739abb5833be46d5edde227c0c108d4edb50e`. Checked 22 source/document files; 22 exact contents reachable on origin; **0 unmatched candidates**.

No unmatched working-file contents in this focused set. This does not authorize deletion.

### C:/Temp/jb-promotion-inventory

HEAD `5b3ebdcaf355801cc58c07db1dddf0d5347e7ae5`. Checked 6 source/document files; 4 exact contents reachable on origin; **2 unmatched candidates**.

Review these existing files before any removal:

- `tasks/joybrush/PROJECTED_JOINT_HANDOFF_20261014.md` — blob `94d20f730351c419f0208d1085ba5aac0e443a27`
- `tasks/joybrush/PROMOTION_INVENTORY_HANDOFF_20261014.md` — blob `3c577cd000cf900db0145479dea3a4d356d58646`

### C:/Temp/jb-root-media-integration

HEAD `7087eae4bc2214ba024ebe02d08b59130ac357e1`. Checked 28 source/document files; 28 exact contents reachable on origin; **0 unmatched candidates**.

No unmatched working-file contents in this focused set. This does not authorize deletion.

### C:/Temp/jb-symmetry-f2

HEAD `789bef0af8e05df00d42b994123addf48dae96ff`. Checked 2 source/document files; 0 exact contents reachable on origin; **2 unmatched candidates**.

Review these existing files before any removal:

- `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/chrome/GuideOverlayView.kt` — blob `df287e82f0801bea2aa81df4ab21d71f965867d1`
- `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/chrome/SymmetryOverlayLogic.kt` — blob `6fda14ec552ff2ce46f288c852a2a3a1d8532408`

### C:/Temp/jb-symmetry-f3

HEAD `789bef0af8e05df00d42b994123addf48dae96ff`. Checked 4 source/document files; 0 exact contents reachable on origin; **4 unmatched candidates**.

Review these existing files before any removal:

- `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/media/MediaCanvas.kt` — blob `f51582ab7c4da73caf6669cd415b481d280e0da5`
- `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/media/MediaSafety.kt` — blob `246315a28ba7c7ed8cb88413724a80e5d7eadcfb`
- `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/symmetry/MediaLane.kt` — blob `5f4cf2187137b317669635a0d138fd5b6686eaae`
- `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/symmetry/SymmetryLanes.kt` — blob `06b568e07cf0eca52ebcb4096e7dc292f5e50430`

### C:/Temp/jb-v6

HEAD `36c0642874158d26f06ac4117446f60ee06453d0`. Checked 2 source/document files; 2 exact contents reachable on origin; **0 unmatched candidates**.

No unmatched working-file contents in this focused set. This does not authorize deletion.

### C:/Temp/jb-vector-tilt

HEAD `8e81e0d4e35cc3782e36ab8df49061c917f25291`. Checked 6 source/document files; 0 exact contents reachable on origin; **6 unmatched candidates**.

Review these existing files before any removal:

- `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/chrome/BrushSettingsView.kt` — blob `bcf38645ce33b59c32632c59b28400a02e479ce6`
- `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/JbCanvasView.kt` — blob `f47e968502a15c916ff5053fa132b3e75f91d76e`
- `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/diag/PenDiagnosticsView.kt` — blob `50bdbd0777a3269efb17fc82194e1f8d0f345dd0`
- `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/input/MotionEventSamples.kt` — blob `dec653da84a35ba631ebf72907ea695da3e64af7`
- `joybrush/core/src/commonMain/kotlin/cc/joycreator/joybrush/core/input/AxisMapping.kt` — blob `99ba169905db49e7e357565855fc02f08e5ed734`
- `joybrush/core/src/commonTest/kotlin/cc/joycreator/joybrush/core/input/AxisMappingTest.kt` — blob `64160084ccde98154ebc088882b2a86bfe0ec90d`

## Recovery priority for Claude

1. **Cold-source failure matrix:** `C:/Temp/jb-cold-faults` has detached local-only commits `05b3d6232` and `6a3508ed0`, plus two unmatched working files. Compare against current cold-loader/probe code first. These are the most directly relevant local-only engine-test candidates identified here; no new pass is claimed.
2. **Capture/physical source helpers:** inspect unmatched files in `jb-capture-current`, `jb-captured-domains`, `jb-captured-engine`, `jb-captured-source`, and staged `jb-520a-recovery`. Most engine-helper files match remote exactly; porting the whole old folder would regress current code.
3. **Writer tests:** isolated published `7502d1160` on `lead/live-goal-20261104` is recoverable without filesystem archaeology. It is not on audited main and remains unverified in the Codex evidence. Port only after reconciling current Activity.
4. **Separate owners:** symmetry f2/f3, vector tilt, board-strip/board chrome, pencil/OpenCode branches and old mid-* commits require their domain lead. Do not fold them into the three engine tasks merely because their folders are dirty.
5. For each needed candidate, preserve a binary patch plus index/untracked contents outside the old folder, inspect for secrets/owner media, then create an explicit-path backup commit on a separate branch and push/verify it. Preserve provenance and mark unrun source. A backup is not acceptance or a reason to merge it wholesale.

This handback does not certify every historical local branch, reflog or unregistered folder. It covers all 181 registered worktree heads/statuses and the named deeper source comparisons. It authorizes no deletion. Existing [HYG-3](../../jobs/HYG-3_cleanup.md) remains unfinished.
