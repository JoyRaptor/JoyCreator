# Work folders removed to free disk space (2026-10-18)

The owner asked for disk space back. Each folder below had **no unsaved changes** and its commit was **already on GitHub**
(origin). Nothing was lost: branches were kept. A session that used one of these folders brings it back with the
command in its row, run from the main project folder. Kept on purpose: anything with unsaved changes, anything not on
GitHub, anything used in the last day, the helper's and Codex's live folders, and the Claude app's own worktrees.

| Folder | Branch or commit | Bring it back |
|---|---|---|
| `C:/Temp/jb-240` | `lead/jb240` | `git worktree add C:/Temp/jb-240 lead/jb240` |
| `C:/Temp/jb-520a-integration` | `lead/jb-5.20a-integration` | `git worktree add C:/Temp/jb-520a-integration lead/jb-5.20a-integration` |
| `C:/Temp/jb-abr-frontier` | `codex/abr-frontier-fix` | `git worktree add C:/Temp/jb-abr-frontier codex/abr-frontier-fix` |
| `C:/Temp/jb-atlas-a1` | `media/atlas-a1` | `git worktree add C:/Temp/jb-atlas-a1 media/atlas-a1` |
| `C:/Temp/jb-count-integration` | `lead/jb-count-integration` | `git worktree add C:/Temp/jb-count-integration lead/jb-count-integration` |
| `C:/Temp/jb-feature-integration` | `lead/jb-feature-integration` | `git worktree add C:/Temp/jb-feature-integration lead/jb-feature-integration` |
| `C:/Temp/jb-handoff-live` | `lead/jb-handoff-live` | `git worktree add C:/Temp/jb-handoff-live lead/jb-handoff-live` |
| `C:/Temp/jb-lead` | `lead/tilt-fixes` | `git worktree add C:/Temp/jb-lead lead/tilt-fixes` |
| `C:/Temp/jb-live-lines` | `lead/jb-live-lines` | `git worktree add C:/Temp/jb-live-lines lead/jb-live-lines` |
| `C:/Temp/jb-midtests` | `lead/mid-tests` | `git worktree add C:/Temp/jb-midtests lead/mid-tests` |
| `C:/Temp/jb-oilrect` | `lead/oil-tight-rect` | `git worktree add C:/Temp/jb-oilrect lead/oil-tight-rect` |
| `C:/Temp/jb-opencode-accessibility` | `opencode/r51-accessibility-audit` | `git worktree add C:/Temp/jb-opencode-accessibility opencode/r51-accessibility-audit` |
| `C:/Temp/jb-opencode-dry-geometry` | `opencode/dry-window-geometry` | `git worktree add C:/Temp/jb-opencode-dry-geometry opencode/dry-window-geometry` |
| `C:/Temp/jb-opencode-dry-window-proposal` | a commit already on GitHub (exact id not recorded) | `git worktree add --detach C:/Temp/jb-opencode-dry-window-proposal <commit>` |
| `C:/Temp/jb-opencode-export` | `opencode/r51-export-audit` | `git worktree add C:/Temp/jb-opencode-export opencode/r51-export-audit` |
| `C:/Temp/jb-opencode-fast-c` | `opencode/media-fast-c-benchmark` | `git worktree add C:/Temp/jb-opencode-fast-c opencode/media-fast-c-benchmark` |
| `C:/Temp/jb-opencode-gpu-baseline` | a commit already on GitHub (exact id not recorded) | `git worktree add --detach C:/Temp/jb-opencode-gpu-baseline <commit>` |
| `C:/Temp/jb-opencode-ink-test` | `opencode/legacy-ink-boundary-test` | `git worktree add C:/Temp/jb-opencode-ink-test opencode/legacy-ink-boundary-test` |
| `C:/Temp/jb-opencode-media-bake` | `opencode/media-plain-bake-reference` | `git worktree add C:/Temp/jb-opencode-media-bake opencode/media-plain-bake-reference` |
| `C:/Temp/jb-opencode-media-report` | `opencode/jb240-wip-audit` | `git worktree add C:/Temp/jb-opencode-media-report opencode/jb240-wip-audit` |
| `C:/Temp/jb-opencode-media-trace` | `opencode/media-submission-trace` | `git worktree add C:/Temp/jb-opencode-media-trace opencode/media-submission-trace` |
| `C:/Temp/jb-opencode-note20-trace` | a commit already on GitHub (exact id not recorded) | `git worktree add --detach C:/Temp/jb-opencode-note20-trace <commit>` |
| `C:/Temp/jb-opencode-proposals-wave2` | a commit already on GitHub (exact id not recorded) | `git worktree add --detach C:/Temp/jb-opencode-proposals-wave2 <commit>` |
| `C:/Temp/jb-opencode-r51-memory` | `opencode/r51-memory-inventory` | `git worktree add C:/Temp/jb-opencode-r51-memory opencode/r51-memory-inventory` |
| `C:/Temp/jb-opencode-render-parity` | `opencode/media-render-parity` | `git worktree add C:/Temp/jb-opencode-render-parity opencode/media-render-parity` |
| `C:/Temp/jb-opencode-save-undo` | `opencode/r51-save-undo-audit` | `git worktree add C:/Temp/jb-opencode-save-undo opencode/r51-save-undo-audit` |
| `C:/Temp/jb-opencode-shader-control` | `opencode/shader-positive-control` | `git worktree add C:/Temp/jb-opencode-shader-control opencode/shader-positive-control` |
| `C:/Temp/jb-opencode-sprite-test` | `opencode/sprite-swap-export-test` | `git worktree add C:/Temp/jb-opencode-sprite-test opencode/sprite-swap-export-test` |
| `C:/Temp/jb-opencode-tile-gpu` | `opencode/tile-paper-gpu-parity` | `git worktree add C:/Temp/jb-opencode-tile-gpu opencode/tile-paper-gpu-parity` |
| `C:/Temp/jb-opencode-upload-reuse` | `opencode/media-upload-reuse` | `git worktree add C:/Temp/jb-opencode-upload-reuse opencode/media-upload-reuse` |
| `C:/Temp/jb-pencil` | `media/pencil-tune` | `git worktree add C:/Temp/jb-pencil media/pencil-tune` |
| `C:/Temp/jb-pencil-land` | `lead/pencil-land` | `git worktree add C:/Temp/jb-pencil-land lead/pencil-land` |
| `C:/Temp/jb-previews` | `lead/brush-previews` | `git worktree add C:/Temp/jb-previews lead/brush-previews` |
| `C:/Temp/jb-saved-current` | `lead/jb-saved-current` | `git worktree add C:/Temp/jb-saved-current lead/jb-saved-current` |
| `C:/Temp/jb-scoped-lines` | `lead/jb-scoped-lines` | `git worktree add C:/Temp/jb-scoped-lines lead/jb-scoped-lines` |
| `C:/Temp/jb-select` | `ui/select-tool` | `git worktree add C:/Temp/jb-select ui/select-tool` |
| `C:/Temp/jb-select-core` | `ui/select-e2` | `git worktree add C:/Temp/jb-select-core ui/select-e2` |
| `C:/Temp/jb-select-count` | `ui/select-count-main` | `git worktree add C:/Temp/jb-select-count ui/select-count-main` |
| `C:/Temp/jb-select-host-review` | `ui/select-host-review-current` | `git worktree add C:/Temp/jb-select-host-review ui/select-host-review-current` |
| `C:/Temp/jb-testfix` | `lead/test-repairs` | `git worktree add C:/Temp/jb-testfix lead/test-repairs` |
| `C:/Temp/jb-tileresize` | `lead/tile-live-resize` | `git worktree add C:/Temp/jb-tileresize lead/tile-live-resize` |
| `C:/Temp/jb-tilt` | `lead/paper-tilt` | `git worktree add C:/Temp/jb-tilt lead/paper-tilt` |
| `C:/Temp/jb-ui-integration` | `lead/jb-ui-integration` | `git worktree add C:/Temp/jb-ui-integration lead/jb-ui-integration` |
| `C:/Temp/jb-uniforms` | `lead/media-uniforms` | `git worktree add C:/Temp/jb-uniforms lead/media-uniforms` |
| `C:/Temp/jb-v8` | commit 64450ab109 | `git worktree add --detach C:/Temp/jb-v8 64450ab109` |
| `C:/Temp/jb-vector` | `vector/pencil` | `git worktree add C:/Temp/jb-vector vector/pencil` |
| `C:/Temp/jb-vector-h1` | `vector/stamp-pass` | `git worktree add C:/Temp/jb-vector-h1 vector/stamp-pass` |
| `C:/Temp/jb-vector-h7` | `vector/h7-fixes` | `git worktree add C:/Temp/jb-vector-h7 vector/h7-fixes` |
| `C:/Temp/jb-vector-lab` | `vector/line-lab` | `git worktree add C:/Temp/jb-vector-lab vector/line-lab` |
| `C:/Temp/jb-vector-stack` | `vector/wc-plan-conditions` | `git worktree add C:/Temp/jb-vector-stack vector/wc-plan-conditions` |
| `C:/Temp/jb-vector-svg` | `vector/svg-export` | `git worktree add C:/Temp/jb-vector-svg vector/svg-export` |
| `C:/Temp/jb-watercolour-phone` | `vector/watercolour-phone` | `git worktree add C:/Temp/jb-watercolour-phone vector/watercolour-phone` |
| `C:/Temp/jb-wetexact` | `lead/wet-exact` | `git worktree add C:/Temp/jb-wetexact lead/wet-exact` |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.04` | `opencode/jb-9.04` | `git worktree add C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.04 opencode/jb-9.04` |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.05` | `opencode/jb-9.05` | `git worktree add C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.05 opencode/jb-9.05` |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.06b-fix` | `codex/jb-9.06b-ora-atomic` | `git worktree add C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.06b-fix codex/jb-9.06b-ora-atomic` |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.09` | `jb-9.09-paper-response` | `git worktree add C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.09 jb-9.09-paper-response` |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.11` | `jb-9.11-surfaces` | `git worktree add C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.11 jb-9.11-surfaces` |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-audit-pen` | commit cd726f0b9f | `git worktree add --detach C:/Users/JoyRaptor/AppData/Local/Temp/jb-audit-pen cd726f0b9f` |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-bunny` | commit 0b7546b575 | `git worktree add --detach C:/Users/JoyRaptor/AppData/Local/Temp/jb-bunny 0b7546b575` |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-fixes` | commit 7ac556620d | `git worktree add --detach C:/Users/JoyRaptor/AppData/Local/Temp/jb-fixes 7ac556620d` |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-3.03` | commit fc22b59a88 | `git worktree add --detach C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-3.03 fc22b59a88` |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-3.06c` | commit fc22b59a88 | `git worktree add --detach C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-3.06c fc22b59a88` |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-4.01` | commit fc22b59a88 | `git worktree add --detach C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-4.01 fc22b59a88` |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-4.02` | commit fc22b59a88 | `git worktree add --detach C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-4.02 fc22b59a88` |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-8.04b` | commit fc22b59a88 | `git worktree add --detach C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-8.04b fc22b59a88` |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-9.02` | `jb-9.02-hexsampler` | `git worktree add C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-9.02 jb-9.02-hexsampler` |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-land` | commit 2ae2924929 | `git worktree add --detach C:/Users/JoyRaptor/AppData/Local/Temp/jb-land 2ae2924929` |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-land2` | commit 379782abd6 | `git worktree add --detach C:/Users/JoyRaptor/AppData/Local/Temp/jb-land2 379782abd6` |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-muse-3.03f` | commit 825b6c1cca | `git worktree add --detach C:/Users/JoyRaptor/AppData/Local/Temp/jb-muse-3.03f 825b6c1cca` |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-muse-8.01b` | commit 04e63a39c1 | `git worktree add --detach C:/Users/JoyRaptor/AppData/Local/Temp/jb-muse-8.01b 04e63a39c1` |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-muse-s2.14c` | commit a70fa772de | `git worktree add --detach C:/Users/JoyRaptor/AppData/Local/Temp/jb-muse-s2.14c a70fa772de` |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-muse-s3.02b` | commit 3c1b8b7bc7 | `git worktree add --detach C:/Users/JoyRaptor/AppData/Local/Temp/jb-muse-s3.02b 3c1b8b7bc7` |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-muse-s3.03b` | commit 4bfc967924 | `git worktree add --detach C:/Users/JoyRaptor/AppData/Local/Temp/jb-muse-s3.03b 4bfc967924` |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-muse-s3.08` | commit 2424be07d3 | `git worktree add --detach C:/Users/JoyRaptor/AppData/Local/Temp/jb-muse-s3.08 2424be07d3` |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-paper` | `paper-crumple-notes` | `git worktree add C:/Users/JoyRaptor/AppData/Local/Temp/jb-paper paper-crumple-notes` |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-tuft` | commit 64d16a596c | `git worktree add --detach C:/Users/JoyRaptor/AppData/Local/Temp/jb-tuft 64d16a596c` |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jbl` | `lead/jb-1.05c` | `git worktree add C:/Users/JoyRaptor/AppData/Local/Temp/jbl lead/jb-1.05c` |
| `C:/Users/JoyRaptor/AppData/Local/Temp/opencode/jb-clean-AUDIT2` | commit 03674d5ee7 | `git worktree add --detach C:/Users/JoyRaptor/AppData/Local/Temp/opencode/jb-clean-AUDIT2 03674d5ee7` |

Copy `local.properties` from the main folder into a re-added folder before building.
