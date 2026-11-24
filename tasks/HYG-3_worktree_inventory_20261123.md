# HYG-3 worktree inventory, 2026-11-23 (read-only; nothing removed)

Made by the website lane for build-queue job 5. Method: `git worktree list`, then per worktree `git status -uno`, the last
commit date, whether its HEAD is an ancestor of `origin/joy-creator` (**landed**), else whether any remote branch contains it
(**on-remote**), else **UNPUSHED**. Untracked files are not counted. The BOARD's live claims were NOT cross-checked: a worktree
named there is live whatever this table says.

**212 worktrees:** 95 clean and landed, 34 clean and on a remote branch, 57 with commits on no remote
(UNPUSHED), 29 with uncommitted changes.

## Safe-looking to remove once the lead agrees (clean and landed: 95)
Removing them loses no commit. Check the BOARD first; keep any named there.
| Worktree | Branch | Last commit | State |
|---|---|---|---|
| `C:/+Projects/JoyCreator` | `joy-creator` | 2026-11-23 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/agent-a2053189435d39f4c` | `worktree-agent-a2053189435d39f4c` | 2026-11-04 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/agent-a242dd1ef591c2a1c` | `worktree-agent-a242dd1ef591c2a1c` | 2026-11-04 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/agent-a2ae8840d7bee458b` | `worktree-agent-a2ae8840d7bee458b` | 2026-11-04 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/agent-a2b248b96f0b14aae` | `worktree-agent-a2b248b96f0b14aae` | 2026-11-04 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/agent-a4ecaabe691003b3b` | `worktree-agent-a4ecaabe691003b3b` | 2026-11-04 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/agent-a539eab362ede2dbe` | `worktree-agent-a539eab362ede2dbe` | 2026-11-04 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/agent-a61b863200321d515` | `worktree-agent-a61b863200321d515` | 2026-11-04 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/agent-a6a60c21ea3064216` | `worktree-agent-a6a60c21ea3064216` | 2026-11-04 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/agent-a9f01484237d5ab0a` | `worktree-agent-a9f01484237d5ab0a` | 2026-11-04 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/agent-aa72ab45085d1c7e6` | `worktree-agent-aa72ab45085d1c7e6` | 2026-11-04 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/agent-ada97301d19c3cc9f` | `worktree-agent-ada97301d19c3cc9f` | 2026-11-04 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/agent-ae6b15fda01055f73` | `worktree-agent-ae6b15fda01055f73` | 2026-11-04 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/agent-aee2365d4161fcf75` | `worktree-agent-aee2365d4161fcf75` | 2026-11-04 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/anim-labs-20261113-136711` | `claude/anim-labs-20261113-136711` | 2026-11-14 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/avatarlab-video-voice-d2d4e9` | `claude/avatarlab-video-voice-d2d4e9` | 2026-11-22 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/avatarlab-walk-stage-8c89bc` | `claude/avatarlab-walk-stage-8c89bc` | 2026-11-22 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/brush-set-20261113-81446f` | `claude/brush-set-20261113-81446f` | 2026-11-18 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/determined-lovelace-7fb58c` | `claude/studio-text-20261114` | 2026-11-14 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/face-capture-scout-b847e5` | `claude/face-capture-scout-b847e5` | 2026-11-22 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/friendly-bun-31ccb1` | `claude/kind-grothendieck-f56065` | 2026-10-24 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/intelligent-joliot-1f40ce` | `claude/intelligent-joliot-1f40ce` | 2026-09-23 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/joybot-20261113-d3a666` | `claude/avatarlab-scene-room-ac08ad` | 2026-11-22 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/joybrush-screens-20261113-1a6f0b` | `claude/joybrush-screens-20261113-1a6f0b` | 2026-11-16 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/play-build-20261114-1a01c6` | `claude/cloud-test-failures-20261116` | 2026-11-16 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/shell-lobby-20261113-78172a` | `claude/shell-lobby-20261113-78172a` | 2026-11-20 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/sizzle-prep-20261115-7d8ab2` | `claude/joybot-trick-20261116` | 2026-11-18 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/tab1-sizes-20261114-7011d5` | `claude/codex-r4-land-a5606e` | 2026-11-20 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/textlab-cross-wing-2dd79d` | `claude/textlab-cross-wing-2dd79d` | 2026-11-13 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/tickbox-palette-fc3dc7` | `claude/tickbox-palette-fc3dc7` | 2026-11-23 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/translations-20261115-bea302` | `claude/icons-20261115-174a79` | 2026-11-15 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/ui1-text-merge-e694e0` | `claude/device-checks-20261115-e321bf` | 2026-11-15 | clean |
| `C:/+Projects/JoyCreator/scratchpad/codex-round4-vislab` | `codex/r4-vis-idle` | 2026-11-20 | clean |
| `C:/+Projects/JoyCreator/scratchpad/r4-aud-race` | `codex/r4-aud-race` | 2026-11-20 | clean |
| `C:/Temp/ava-base-20261123` | `(detached)` | 2026-11-23 | clean |
| `C:/Temp/avatarlab-20261118` | `claude/avatarlab-20261118` | 2026-11-23 | clean |
| `C:/Temp/brush-colour-20261113` | `claude/pencil-colour-20261113` | 2026-11-13 | clean |
| `C:/Temp/bughunt-20261115` | `claude/open-tiles-20261117` | 2026-11-18 | clean |
| `C:/Temp/claude-atlas-20261109` | `claude/atlas-fibres2-20261117` | 2026-11-18 | clean |
| `C:/Temp/claude-door2-20261104` | `(detached)` | 2026-11-17 | clean |
| `C:/Temp/codex-engine-handback-20261113` | `codex/engine-handback-20261113` | 2026-11-13 | clean |
| `C:/Temp/engine-r4-docs-20261120` | `claude/engine-r4-docs2` | 2026-11-23 | clean |
| `C:/Temp/fx1-handoff-20261116` | `claude/fx1-handoff-20261116` | 2026-11-16 | clean |
| `C:/Temp/helper-main` | `(detached)` | 2026-10-25 | clean |
| `C:/Temp/helper-room` | `helper1/atlas-room` | 2026-10-25 | clean |
| `C:/Temp/jb-atlas-merge-20261030` | `claude/thumbs-20261112` | 2026-11-12 | clean |
| `C:/Temp/jb-atlas-room-repair-20261025` | `lead/atlas-room-repair-20261025` | 2026-10-27 | clean |
| `C:/Temp/jb-captured-domains` | `(detached)` | 2026-10-15 | clean |
| `C:/Temp/jb-claude-main` | `lead/claude-lines2` | 2026-10-17 | clean |
| `C:/Temp/jb-clips` | `lead/anim-clips` | 2026-10-23 | clean |
| `C:/Temp/jb-codex-unibrush` | `codex/unibrush` | 2026-10-21 | clean |
| `C:/Temp/jb-helper1-atlas` | `helper1/media-atlas-b1` | 2026-10-19 | clean |
| `C:/Temp/jb-justworks` | `lead/just-works` | 2026-10-17 | clean |
| `C:/Temp/jb-live-current` | `lead/jb-live-current` | 2026-10-16 | clean |
| `C:/Temp/jb-oil-b` | `(detached)` | 2026-10-25 | clean |
| `C:/Temp/jb-one-line-20261030` | `engine/one-line-20261030` | 2026-10-30 | clean |
| `C:/Temp/jb-one-line-swap` | `engine/one-line-swap` | 2026-10-30 | clean |
| `C:/Temp/jb-opencode-test-verification` | `(detached)` | 2026-10-08 | clean |
| `C:/Temp/jb-opencode-verify-09-18` | `(detached)` | 2026-10-09 | clean |
| `C:/Temp/jb-region-routing` | `codex/region-routing` | 2026-10-09 | clean |
| `C:/Temp/jb-roadmap-publication-20261026` | `lead/roadmap-publication-20261026` | 2026-11-04 | clean |
| `C:/Temp/jb-select-reconcile-20261024` | `lead/select-reconcile-20261024` | 2026-10-27 | clean |
| `C:/Temp/jb-symmetry` | `lead/symmetry-1019` | 2026-10-20 | clean |
| `C:/Temp/jb-symmetry-e` | `lead/symmetry-e` | 2026-10-20 | clean |
| `C:/Temp/jb-symmetry-merge` | `lead/symmetry-merge` | 2026-10-20 | clean |
| `C:/Temp/jb-symmetry-recover-20261027` | `claude/symmetry-recover-20261027` | 2026-10-27 | clean |
| `C:/Temp/jb-unibrush-baseline` | `(detached)` | 2026-10-18 | clean |
| `C:/Temp/jb-unibrush-merge-20261030` | `engine/unibrush-u8` | 2026-10-30 | clean |
| `C:/Temp/jbs-board` | `(detached)` | 2026-11-14 | clean |
| `C:/Temp/jc-audit-20261113` | `(detached)` | 2026-11-14 | clean |
| `C:/Temp/jc-lead-docs` | `lead/land-helper-20261028` | 2026-10-28 | clean |
| `C:/Temp/jc-lead-roadmap` | `lead/fold-overnight-20261028` | 2026-10-28 | clean |
| `C:/Temp/jc-vl-lane` | `claude/state-sync-20261027` | 2026-10-27 | clean |
| `C:/Temp/joybot-20261028` | `claude/joybot-20261028` | 2026-10-28 | clean |
| `C:/Temp/joybot-apk-20261113` | `(detached)` | 2026-11-14 | clean |
| `C:/Temp/joybot-report-20261114` | `claude/joybot-report-20261114` | 2026-11-14 | clean |
| `C:/Temp/joybot-sheet-20261113` | `claude/joybot-sheet-20261113` | 2026-11-20 | clean |
| `C:/Temp/ld-push` | `(detached)` | 2026-11-14 | clean |
| `C:/Temp/lead-uni` | `lead/unibrush-u1` | 2026-10-30 | clean |
| `C:/Temp/lead-wcl` | `lead/wc-land2` | 2026-10-25 | clean |
| `C:/Temp/motionblur-20261122` | `claude/motionblur-plan-20261122` | 2026-11-23 | clean |
| `C:/Temp/motionblur-flow-20261122` | `claude/motionblur-flow-20261122` | 2026-11-22 | clean |
| `C:/Temp/motionblur-lane-20261122` | `claude/motionblur-lane-20261122` | 2026-11-22 | clean |
| `C:/Temp/textlab-phone-20261112` | `claude/textlab-phone-20261112` | 2026-11-13 | clean |
| `C:/Temp/vl-studio-fx` | `vislab/r8-follow-20261027` | 2026-10-28 | clean |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.03` | `codex/jb-9.03` | 2026-10-01 | clean |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.03b` | `codex/jb-9.03b` | 2026-10-01 | clean |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.06` | `codex/jb-9.06` | 2026-10-01 | clean |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.06b` | `codex/jb-9.06b` | 2026-10-02 | clean |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.07` | `codex/jb-9.07` | 2026-10-02 | clean |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.08` | `codex/jb-9.08` | 2026-10-01 | clean |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.10` | `codex/jb-9.10` | 2026-10-02 | clean |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-d02a` | `(detached)` | 2026-10-08 | clean |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jbclean` | `(detached)` | 2026-09-30 | clean |
| `C:/workspace/motionblur-docs` | `docs/motionblur-channel` | 2026-11-23 | clean |

## Clean, on a remote branch (34)
Safe to remove the folder (the branch keeps the work); keep the branch.
| Worktree | Branch | Last commit | State |
|---|---|---|---|
| `C:/+Projects/JoyCreator/.claude/worktrees/agent-af4efb638ebe17937` | `claude/stu6-swatches-20261112` | 2026-11-13 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/festive-perlman-8dde53` | `claude/festive-perlman-8dde53` | 2026-11-01 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/launch-docs-20261113-042476` | `claude/mediapipe-detector-20261113` | 2026-11-14 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/modest-keller-2875af` | `claude/modest-keller-2875af` | 2026-11-13 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/priceless-yalow-249f46` | `claude/priceless-yalow-249f46` | 2026-11-23 | clean |
| `C:/+Projects/JoyCreator/.claude/worktrees/ui1-leftovers-20261110-38909d` | `claude/engine-r4-20261120-e499b0` | 2026-11-23 | clean |
| `C:/+Projects/JoyCreator/scratchpad/audio-studio-voice-review` | `codex/audio-studio-voice` | 2026-11-21 | clean |
| `C:/+Projects/JoyCreator/scratchpad/r4-aud-bake` | `codex/r4-aud-bake` | 2026-11-20 | clean |
| `C:/+Projects/JoyCreator/scratchpad/r4-studio-core` | `codex/r4-gl-mask` | 2026-11-20 | clean |
| `C:/+Projects/JoyCreator/scratchpad/r4-txt-transfer` | `codex/r4-txt-transfer` | 2026-11-20 | clean |
| `C:/+Projects/JoyCreator/scratchpad/studio-voice-src` | `codex/note9-studio-voice-20261119` | 2026-11-19 | clean |
| `C:/Temp/jb-codex-select` | `codex/select-d23-presenter-20261026` | 2026-10-27 | clean |
| `C:/Temp/jb-doc-maintenance-20261026` | `lead/doc-maintenance-20261026` | 2026-10-26 | clean |
| `C:/Temp/jb-live-goal-20261104` | `lead/live-goal-20261104` | 2026-11-04 | clean |
| `C:/Temp/jb-paper-approved-20261029` | `codex/paper-approved-20261029` | 2026-10-29 | clean |
| `C:/Temp/jb-paper-baseline-20261029` | `lead/paper-baseline-20261029` | 2026-10-29 | clean |
| `C:/Temp/jb-paper-cache-20261029` | `lead/paper-cache-20261029` | 2026-10-29 | clean |
| `C:/Temp/jb-paper-evaluation-20261029` | `lead/paper-full-evaluation-20261029` | 2026-10-29 | clean |
| `C:/Temp/jb-paper-recovery-20261026` | `codex/paper-recovery-docs-20261026` | 2026-10-27 | clean |
| `C:/Temp/jb-send-existing-20261027` | `claude/send-to-existing-20261027` | 2026-10-27 | clean |
| `C:/Temp/jb-vector-tweens` | `codex/vector-tweens` | 2026-10-26 | clean |
| `C:/Temp/jb-watercolour-finish-20261024` | `vector/watercolour-k25-20261025` | 2026-10-25 | clean |
| `C:/Temp/jb-watercolour-fixture-20261026` | `vector/watercolour-fixture-20261026` | 2026-10-26 | clean |
| `C:/Temp/jc-editorfix-20261112` | `claude/editor-timing-20261112` | 2026-11-12 | clean |
| `C:/Temp/jc-exportsafe-20261112` | `claude/export-timing-20261112` | 2026-11-12 | clean |
| `C:/Temp/jc-recfolder-20261112` | `claude/ai-fixes-20261112` | 2026-11-12 | clean |
| `C:/Temp/jc-send-existing` | `claude/send-to-studio-existing-20261027` | 2026-10-27 | clean |
| `C:/Temp/jc-ui1-text-20261028` | `claude/ui1-text-20261028` | 2026-10-28 | clean |
| `C:/Temp/lead-symcheck` | `lead/symmetry-check` | 2026-10-20 | clean |
| `C:/Temp/paper-wrapup-20261117` | `claude/paper-eval40c-20261118` | 2026-11-18 | clean |
| `C:/Temp/play-candidate-20261114` | `claude/play-candidate-20261114` | 2026-11-14 | clean |
| `C:/Temp/textlab-20261111` | `claude/textlab-20261111` | 2026-11-13 | clean |
| `C:/Temp/vl-proxy-bind` | `vislab/proxy-bind-20261027` | 2026-10-27 | clean |
| `C:/workspace/codex-motionblur-20261122` | `codex/motionblur-20261122` | 2026-11-23 | clean |

## UNPUSHED: push to `backup/<name>` first, never just remove (57)
| Worktree | Branch | Last commit | State |
|---|---|---|---|
| `C:/+Projects/JoyCreator/.claude/worktrees/hover-labels-8c53af` | `claude/hover-labels-8c53af` | 2026-11-23 | clean |
| `C:/Temp/claude-workloss-20261110` | `claude/workloss-20261110` | 2026-11-12 | clean |
| `C:/Temp/jb-board-state-map-20261027` | `docs/board-state-map-20261027` | 2026-10-27 | clean |
| `C:/Temp/jb-cold-faults` | `(detached)` | 2026-10-15 | dirty2 |
| `C:/Temp/jb-merge` | `(detached)` | 2026-10-13 | clean |
| `C:/Temp/jb-opencode-dry-apply-parity` | `opencode/media-dry-apply-parity` | 2026-10-09 | clean |
| `C:/Temp/jb-opencode-dry-paper-only` | `opencode/media-dry-paper-only` | 2026-10-09 | clean |
| `C:/Temp/jb-opencode-mid-05` | `opencode/mid-05` | 2026-10-11 | clean |
| `C:/Temp/jb-opencode-mid-07` | `opencode/mid-07` | 2026-10-11 | clean |
| `C:/Temp/jb-opencode-mid-08` | `opencode/mid-08` | 2026-10-11 | clean |
| `C:/Temp/jb-opencode-mid-09` | `opencode/mid-09-undo-sequence` | 2026-10-09 | clean |
| `C:/Temp/jb-opencode-mid-10` | `opencode/mid-10-codec-corpus` | 2026-10-09 | clean |
| `C:/Temp/jb-opencode-mid-11` | `opencode/mid-11-bake-partition` | 2026-10-09 | clean |
| `C:/Temp/jb-opencode-mid-12` | `opencode/mid-12-archive-report` | 2026-10-09 | clean |
| `C:/Temp/jb-opencode-mid-13` | `opencode/mid-13-board-boundary` | 2026-10-09 | clean |
| `C:/Temp/jb-opencode-mid-14` | `opencode/mid-14-board-lifecycle` | 2026-10-09 | clean |
| `C:/Temp/jb-opencode-mid-15` | `opencode/mid-15-sprite-grid` | 2026-10-09 | clean |
| `C:/Temp/jb-opencode-mid-16` | `opencode/mid-16-swap-plan` | 2026-10-09 | clean |
| `C:/Temp/jb-opencode-mid-17` | `opencode/mid-17-anim-ops` | 2026-10-09 | clean |
| `C:/Temp/jb-opencode-mid-18` | `opencode/mid-18-region-frames` | 2026-10-09 | clean |
| `C:/Temp/jb-opencode-mid-19` | `opencode/mid-19-layer-clone` | 2026-10-09 | clean |
| `C:/Temp/jb-opencode-mid-20` | `opencode/mid-20-docjson` | 2026-10-09 | clean |
| `C:/Temp/jb-opencode-mid-21` | `opencode/mid-21-playback` | 2026-10-09 | clean |
| `C:/Temp/jb-opencode-mid-22` | `opencode/mid-22-filmstrip` | 2026-10-09 | clean |
| `C:/Temp/jb-opencode-mid-23` | `opencode/mid-23-filltrace` | 2026-10-09 | clean |
| `C:/Temp/jb-opencode-mid-24` | `opencode/mid-24-floodfill` | 2026-10-09 | clean |
| `C:/Temp/jb-opencode-mid-25` | `opencode/mid-25-strokeedit` | 2026-10-09 | clean |
| `C:/Temp/jb-opencode-mid-26` | `opencode/mid-26-composer` | 2026-10-09 | clean |
| `C:/Temp/jb-opencode-mid-27` | `opencode/mid-27-inktile` | 2026-10-09 | clean |
| `C:/Temp/jb-opencode-mid-28` | `opencode/mid-28-archive` | 2026-10-09 | clean |
| `C:/Temp/jb-opencode-mid-29` | `opencode/mid-29-manifest` | 2026-10-09 | clean |
| `C:/Temp/jb-paper-state-map-20261027` | `docs/paper-state-map-20261027` | 2026-10-27 | clean |
| `C:/Temp/jb-symmetry-a1` | `lead/symmetry-a1` | 2026-10-19 | clean |
| `C:/Temp/jb-symmetry-a2` | `lead/symmetry-a2` | 2026-10-19 | clean |
| `C:/Temp/jb-symmetry-b` | `lead/symmetry-b` | 2026-10-20 | clean |
| `C:/Temp/jb-symmetry-c` | `lead/symmetry-c` | 2026-10-19 | clean |
| `C:/Temp/jb-symmetry-d` | `lead/symmetry-d` | 2026-10-20 | clean |
| `C:/Temp/jb-worker2-board-strip` | `codex/worker2-board-strip` | 2026-10-08 | clean |
| `C:/Temp/jb-worker2-composer` | `codex/worker2-composer` | 2026-10-07 | dirty1 |
| `C:/Temp/jc-marks-20261028` | `lead/unique-names-20261028` | 2026-10-28 | clean |
| `C:/Temp/jc-testbuild` | `claude/test-build-20261028` | 2026-10-28 | clean |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-2.03afix` | `bunny/JB-2.03a-audit-fixes` | 2026-10-02 | clean |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-8.01b` | `bunny/JB-8.01b` | 2026-10-01 | clean |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.01fix` | `bunny/JB-9.01-audit-fixes` | 2026-10-02 | clean |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-board-chrome` | `codex/jb-board-chrome` | 2026-10-02 | dirty4 |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-d02afix` | `bunny/D02a-rotation-normalise` | 2026-10-02 | clean |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-0.10` | `(detached)` | 2026-09-29 | clean |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-1.07` | `(detached)` | 2026-09-29 | clean |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-2.02c` | `(detached)` | 2026-09-29 | clean |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-2.11` | `(detached)` | 2026-09-29 | clean |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-3.02` | `(detached)` | 2026-09-29 | clean |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-3.05` | `(detached)` | 2026-09-29 | clean |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-5.01` | `(detached)` | 2026-09-29 | clean |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-5.03` | `(detached)` | 2026-09-29 | clean |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-5.11` | `(detached)` | 2026-09-29 | clean |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-8.04` | `(detached)` | 2026-09-29 | clean |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-verify` | `bunny/jvmTest-corpus-input` | 2026-10-02 | clean |

## Uncommitted changes: look before anything (29)
| Worktree | Branch | Last commit | State |
|---|---|---|---|
| `C:/+Projects/JoyCreator/.claude/worktrees/gracious-mendel-3513fb` | `claude/gracious-mendel-3513fb` | 2026-11-23 | dirty8 |
| `C:/+Projects/JoyCreator/.claude/worktrees/jolly-jepsen-a5fabe` | `claude/jolly-jepsen-a5fabe` | 2026-11-01 | dirty2 |
| `C:/+Projects/JoyCreator/.claude/worktrees/website-f86389` | `claude/website-f86389` | 2026-11-23 | dirty1 |
| `C:/+Projects/JoyCreator/scratchpad/r4-combined-check` | `(detached)` | 2026-11-20 | dirty38 |
| `C:/Temp/jb-520a` | `lead/jb-5.20a` | 2026-10-12 | dirty6 |
| `C:/Temp/jb-520a-recovery` | `lead/jb-5.20a-recovery` | 2026-10-12 | dirty34 |
| `C:/Temp/jb-capture-current` | `lead/jb-capture-current` | 2026-10-14 | dirty2 |
| `C:/Temp/jb-captured-engine` | `(detached)` | 2026-10-15 | dirty10 |
| `C:/Temp/jb-captured-scene` | `(detached)` | 2026-10-15 | dirty1 |
| `C:/Temp/jb-captured-source` | `lead/jb-captured-source` | 2026-10-14 | dirty6 |
| `C:/Temp/jb-cold-faults` | `(detached)` | 2026-10-15 | dirty2 |
| `C:/Temp/jb-media` | `media/jb-2.40` | 2026-10-07 | dirty23 |
| `C:/Temp/jb-opencode-media-snapshot` | `(detached)` | 2026-10-07 | dirty23 |
| `C:/Temp/jb-opencode-shader-verification` | `(detached)` | 2026-10-08 | dirty3 |
| `C:/Temp/jb-promotion-inventory` | `lead/jb-promotion-inventory` | 2026-10-14 | dirty2 |
| `C:/Temp/jb-root-media-integration` | `codex/jb-root-media-integration` | 2026-10-08 | dirty29 |
| `C:/Temp/jb-symmetry-f2` | `lead/symmetry-f2` | 2026-10-20 | dirty1 |
| `C:/Temp/jb-symmetry-f3` | `lead/symmetry-f3` | 2026-10-20 | dirty4 |
| `C:/Temp/jb-v6` | `(detached)` | 2026-10-06 | dirty3 |
| `C:/Temp/jb-vector-tilt` | `vector/tilt-flip` | 2026-10-10 | dirty6 |
| `C:/Temp/jb-worker2-composer` | `codex/worker2-composer` | 2026-10-07 | dirty1 |
| `C:/Temp/joybench-dev` | `bench/joybot-phone` | 2026-10-27 | dirty10 |
| `C:/Temp/lead-atlas-exp` | `lead/atlas-exp` | 2026-10-20 | dirty4 |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-8.04fix` | `bunny/JB-8.04-real-file-fix` | 2026-10-02 | dirty3 |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.06c` | `codex/jb-9.06c` | 2026-10-02 | dirty1 |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-board-chrome` | `codex/jb-board-chrome` | 2026-10-02 | dirty4 |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-brush-specialist` | `codex/brush-contact` | 2026-10-05 | dirty1 |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-pencil-quality` | `opencode/pencil-quality` | 2026-10-02 | dirty8 |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jbroad` | `(detached)` | 2026-10-02 | dirty1 |
