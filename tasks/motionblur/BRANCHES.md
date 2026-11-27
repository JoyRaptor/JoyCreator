# STU-7 branch register

`codex/stu7-finish-20261125` is the sole product candidate. It includes the unsettled gated C5 and C6 work and the latest landed C4 and C7 help. The recorded fixture tips are reproducible evidence. Keep all historical refs intact. Use at most one temporary proof branch, and fold reusable work back into the candidate before opening another.

[Live channel](CHANNEL.md) · [Resume here](../jobs/STU-7_motion_blur.md#resume-here)

| Branch | Tip at consolidation | Disposition |
| --- | --- | --- |
| `claude/motionblur-flow-20261122` | `a7d01ad7f` | Landed. Tip `a7d01ad7f` is in `joy-creator`. |
| `claude/motionblur-lane-20261122` | `f4e80974e` | Landed. Tip `f4e80974e` is in `joy-creator`. |
| `codex/motionblur-20261122` | `c1a4d59d2` | Landed. Tip `c1a4d59d2` is in `joy-creator`. |
| `codex/stu7-c4-c6-combined-20261125` | `b3074ed83` | Folded. Tip is in the active candidate history. |
| `codex/stu7-c4-current-candidate-20261124` | `c11c8ff27` | Landed. Tip `c11c8ff27` is in `joy-creator`. |
| `codex/stu7-c4-integration-20261123` | `d7c950bd7` | Landed. Tip `d7c950bd7` is in `joy-creator`. |
| `codex/stu7-c4-paused-pip-20261126` | `cf3c3fcca` | Landed via `5798b6c0c` (`3c0179278`). |
| `codex/stu7-c4-pip-latch-20261126` | `fb2269d17` | Landed via `904da5815` (`3c0179278`). |
| `codex/stu7-c4-ready-20261126` | `3c0179278` | Landed. Tip `3c0179278` is in `joy-creator`. |
| `codex/stu7-c4-verify-input-20261124` | `d7c950bd7` | Landed. Tip `d7c950bd7` is in `joy-creator`. |
| `codex/stu7-c4b-benchmark-gate` | `e8adf9628` | Landed. Tip `e8adf9628` is in `joy-creator`. |
| `codex/stu7-c5-avatar-emulator-20261125` | `eade01ae3` | Proof archive. Gate-open emulator APK superseded by `9949153bf` staging proof. |
| `codex/stu7-c5-avatar-proof-20261125` | `b063c9e25` | Proof archive. Encoded MediaStore retrieval superseded by lossless `9949153bf` proof. |
| `codex/stu7-c5-avatar-recovery-20261125` | `c27d93529` | Proof archive. Recovery and replay fixes are in the candidate. See `5cdc3561b` for staging. |
| `codex/stu7-c5-avatar-replay-20261124` | `c9297d7e7` | Folded. Tip is in the active candidate history. |
| `codex/stu7-c5-avatar-route-20261125` | `ef31b5b53` | Folded. Tip is in the active candidate history. |
| `codex/stu7-c5-avatar-staging-20261126` | `5cdc3561b` | Folded. Tip is in the active candidate history. |
| `codex/stu7-c5-gated-integration-20261124` | `23bc5f524` | Folded. Tip is in the active candidate history. |
| `codex/stu7-c5-latched-mesh-20261127` | `775331431` | Folded. Tip is in the active candidate history. |
| `codex/stu7-c5-latched-mesh-proof-20261127` | `7a2bd79c6` | Proof archive. The product fix is folded at `775331431`. This tip adds debug mesh fixtures. |
| `codex/stu7-c5-mesh-fixture-20261126` | `be4d7d073` | Proof archive. `7a2bd79c6` supersedes it for exact-time parity. |
| `codex/stu7-c5-mesh-sweep-20261124` | `2a647be01` | Superseded by ordered mesh integration `1fc57d25d` and exact-time `775331431`. |
| `codex/stu7-c5-ordered-mesh-20261124` | `1fc57d25d` | Folded. Tip is in the active candidate history. |
| `codex/stu7-c5-preview-proof-20261125` | `2bf627cbf` | Dead end. Decoder inset probe missed physical parity. Keep the receipt. |
| `codex/stu7-c5-puppet-clock-20261127` | `b3d39906b` | Proof archive. Measured the 106 ms gap. Product correction at `775331431`. |
| `codex/stu7-c5-staging-proof-20261126` | `9949153bf` | Proof archive. Pinned emulator evidence for product staging `5cdc3561b`. |
| `codex/stu7-c6-analysis-decoder-20261124` | `145438945` | Folded. Tip is in the active candidate history. |
| `codex/stu7-c6-dis-bakeoff-20261124` | `b627a4de7` | Research archive. GPU/DIS comparison. Candidate retains indexed production analysis. |
| `codex/stu7-c6-editor-session-20261125` | `8e18f9b9d` | Folded. Tip is in the active candidate history. |
| `codex/stu7-c6-exact-flow-sampling-20261127` | `04371ce8f` | Folded by cherry-pick at `c4201d358`. |
| `codex/stu7-c6-high-gl-20261124` | `5edb745d5` | Folded. Tip is in the active candidate history. |
| `codex/stu7-c6-high-reconstruct-20261124` | `8e5b0706d` | Superseded by the candidate’s integrated CPU oracle and `5edb745d5` GL route. |
| `codex/stu7-c6-indexed-queue-20261125` | `f1b661ed7` | Folded. Tip is in the active candidate history. |
| `codex/stu7-c6-indexed-session-20261125` | `576952f06` | Folded. Tip is in the active candidate history. |
| `codex/stu7-c6-native-input-proof-20261127` | `2e3bc6d10` | Proof archive. Captured failing linear sampling. Superseded by `f4fc6ad3c` for device quality. |
| `codex/stu7-c6-nearest-native-proof-20261127` | `f4fc6ad3c` | Proof archive. Physical pass for product change `c4201d358`. |
| `codex/stu7-c6-paused-color-20261126` | `ef9607b54` | Folded. Tip is in the active candidate history. |
| `codex/stu7-c6-paused-proof-20261126` | `179ff5bac` | Proof archive. Pinned emulator and device captures for product route `ef9607b54`. |
| `codex/stu7-c6-preview-integrate-20261124` | `273e295ed` | Folded. Tip is in the active candidate history. |
| `codex/stu7-c6-pts-index-20261125` | `9234fb238` | Folded. Tip is in the active candidate history. |
| `codex/stu7-c6-quality-host-20261127` | `88024f859` | Research archive. Residual guard rejected an invalid donor but did not improve the old reference replay. Remains isolated. |
| `codex/stu7-c6-reconstruct-20261124` | `393fcc04d` | Superseded by the integrated High CPU oracle and `5edb745d5` GL route in the candidate. |
| `codex/stu7-c7-authored-replay-20261127` | `f0b164d10` | Proof archive. Six exact synthetic crops at `f86163a07`. Replay on the eventual release candidate remains open. |
| `codex/stu7-c7-blind-review-20261124` | `583a31699` | Landed with audited tooling via `87305f485`. |
| `codex/stu7-c7-corpus-audit-20261124` | `fcafda1b5` | Landed with audited tooling via `87305f485`. |
| `codex/stu7-c7-current-review-20261124` | `575a72b11` | Landed. Tip `575a72b11` is in `joy-creator`. |
| `codex/stu7-c7-gated-help-20261126` | `51b838070` | Landed. Tip `51b838070` is in `joy-creator`. |
| `codex/stu7-c7-help-copy-20261124` | `2c88fb752` | Landed via `8c17ae74f`. Registration landed via `51b838070`. |
| `codex/stu7-finish-20261125` | `ea236f311` | Active candidate. Latest main baseline plus decoded-cut fence and flash control. |
| `codex/stu7-flow-luma-20261123` | `20cee01f7` | Superseded by integrated source indexing and flow analysis in `c4201d358`. |
| `codex/stu7-flow-verify-20261123` | `24d26bc81` | Proof archive. Older rooted-emulator retrieval superseded by exact service readbacks. |
| `codex/stu7-footage-reference-20261126` | `a9334c439` | Proof archive. Centered reference fixture later instrumented in `f4fc6ad3c`. |
| `codex/stu7-gated-fixtures-20261126` | `c317f9c35` | Dead end. Forced codec recreation did not produce the matched preview. Keep the receipt. |
| `codex/stu7-live-flow-gl-20261124` | `0e3aa6142` | Superseded by integrated Fast preview and the shared GL harness in `c4201d358`. |
| `codex/stu7-pose-sampling-20261123` | `96e9637ba` | Superseded by ordered mesh integration and exact-time pose in `775331431`. |
| `codex/stu7-c6-cut-proof-20261127` | `6ddc42458` | Sole temporary side branch. Pinned APK `dfef8d1f7` uses the current candidate product bytes. Tip adds only synthetic Android proof workflow. |
