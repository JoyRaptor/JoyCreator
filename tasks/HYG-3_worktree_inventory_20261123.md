# HYG-3 inventory, 2026-11-23 evening (website lane; replaces the first read-only list)

Method: `git worktree list`; per worktree `git status --porcelain` (untracked counted), `git rev-list --count HEAD --not origin/joy-creator` (unique commits), the BOARD text and the linked sessions' folders (live owners). Branches: the same unique-commit count against `origin/joy-creator`, then whether any remote branch contains the tip. A commit that was landed by squash or rebase shows as "unique" here, so a candidate below may already be on main: check before saving.

## Done: 45 worktrees removed (clean, no unique commit, no live owner, not on BOARD, not locked)

`git worktree remove` without force; every branch kept. 167 worktrees remain (was 212). Removed:

- `C:/+Projects/JoyCreator/.claude/worktrees/agent-a2053189435d39f4c` (`worktree-agent-a2053189435d39f4c`, 2026-11-04)
- `C:/+Projects/JoyCreator/.claude/worktrees/agent-a242dd1ef591c2a1c` (`worktree-agent-a242dd1ef591c2a1c`, 2026-11-04)
- `C:/+Projects/JoyCreator/.claude/worktrees/agent-a2ae8840d7bee458b` (`worktree-agent-a2ae8840d7bee458b`, 2026-11-04)
- `C:/+Projects/JoyCreator/.claude/worktrees/agent-a2b248b96f0b14aae` (`worktree-agent-a2b248b96f0b14aae`, 2026-11-04)
- `C:/+Projects/JoyCreator/.claude/worktrees/agent-a4ecaabe691003b3b` (`worktree-agent-a4ecaabe691003b3b`, 2026-11-04)
- `C:/+Projects/JoyCreator/.claude/worktrees/agent-a539eab362ede2dbe` (`worktree-agent-a539eab362ede2dbe`, 2026-11-04)
- `C:/+Projects/JoyCreator/.claude/worktrees/agent-a61b863200321d515` (`worktree-agent-a61b863200321d515`, 2026-11-04)
- `C:/+Projects/JoyCreator/.claude/worktrees/agent-a6a60c21ea3064216` (`worktree-agent-a6a60c21ea3064216`, 2026-11-04)
- `C:/+Projects/JoyCreator/.claude/worktrees/agent-a9f01484237d5ab0a` (`worktree-agent-a9f01484237d5ab0a`, 2026-11-04)
- `C:/+Projects/JoyCreator/.claude/worktrees/agent-aa72ab45085d1c7e6` (`worktree-agent-aa72ab45085d1c7e6`, 2026-11-04)
- `C:/+Projects/JoyCreator/.claude/worktrees/agent-ada97301d19c3cc9f` (`worktree-agent-ada97301d19c3cc9f`, 2026-11-04)
- `C:/+Projects/JoyCreator/.claude/worktrees/agent-ae6b15fda01055f73` (`worktree-agent-ae6b15fda01055f73`, 2026-11-04)
- `C:/+Projects/JoyCreator/.claude/worktrees/agent-aee2365d4161fcf75` (`worktree-agent-aee2365d4161fcf75`, 2026-11-04)
- `C:/+Projects/JoyCreator/.claude/worktrees/friendly-bun-31ccb1` (`claude/kind-grothendieck-f56065`, 2026-10-24)
- `C:/+Projects/JoyCreator/.claude/worktrees/intelligent-joliot-1f40ce` (`claude/intelligent-joliot-1f40ce`, 2026-09-23)
- `C:/+Projects/JoyCreator/scratchpad/codex-round4-vislab` (`codex/r4-vis-idle`, 2026-11-20)
- `C:/+Projects/JoyCreator/scratchpad/r4-aud-race` (`codex/r4-aud-race`, 2026-11-20)
- `C:/Temp/ava-base-20261123` (`(detached)`, 2026-11-23)
- `C:/Temp/brush-colour-20261113` (`claude/pencil-colour-20261113`, 2026-11-13)
- `C:/Temp/codex-engine-handback-20261113` (`codex/engine-handback-20261113`, 2026-11-13)
- `C:/Temp/engine-r4-docs-20261120` (`claude/engine-r4-docs2`, 2026-11-23)
- `C:/Temp/jb-atlas-merge-20261030` (`claude/thumbs-20261112`, 2026-11-12)
- `C:/Temp/jb-atlas-room-repair-20261025` (`lead/atlas-room-repair-20261025`, 2026-10-27)
- `C:/Temp/jb-clips` (`lead/anim-clips`, 2026-10-23)
- `C:/Temp/jb-codex-unibrush` (`codex/unibrush`, 2026-10-21)
- `C:/Temp/jb-helper1-atlas` (`helper1/media-atlas-b1`, 2026-10-19)
- `C:/Temp/jb-live-current` (`lead/jb-live-current`, 2026-10-16)
- `C:/Temp/jb-oil-b` (`(detached)`, 2026-10-25)
- `C:/Temp/jb-one-line-20261030` (`engine/one-line-20261030`, 2026-10-30)
- `C:/Temp/jb-one-line-swap` (`engine/one-line-swap`, 2026-10-30)
- `C:/Temp/jb-roadmap-publication-20261026` (`lead/roadmap-publication-20261026`, 2026-11-04)
- `C:/Temp/jb-select-reconcile-20261024` (`lead/select-reconcile-20261024`, 2026-10-27)
- `C:/Temp/jb-symmetry-e` (`lead/symmetry-e`, 2026-10-20)
- `C:/Temp/jb-symmetry-merge` (`lead/symmetry-merge`, 2026-10-20)
- `C:/Temp/jb-unibrush-merge-20261030` (`engine/unibrush-u8`, 2026-10-30)
- `C:/Temp/jbs-board` (`(detached)`, 2026-11-14)
- `C:/Temp/jc-audit-20261113` (`(detached)`, 2026-11-14)
- `C:/Temp/joybot-apk-20261113` (`(detached)`, 2026-11-14)
- `C:/Temp/joybot-report-20261114` (`claude/joybot-report-20261114`, 2026-11-14)
- `C:/Temp/ld-push` (`(detached)`, 2026-11-14)
- `C:/Temp/lead-uni` (`lead/unibrush-u1`, 2026-10-30)
- `C:/Temp/lead-wcl` (`lead/wc-land2`, 2026-10-25)
- `C:/Temp/motionblur-flow-20261122` (`claude/motionblur-flow-20261122`, 2026-11-22)
- `C:/Temp/motionblur-lane-20261122` (`claude/motionblur-lane-20261122`, 2026-11-22)
- `C:/workspace/motionblur-docs` (`docs/motionblur-channel`, 2026-11-23)

## Branches: 454 local (274 landed, 115 unique but on a remote, **65 local only**), 317 remote

Nothing deleted. Landed local branches are safe to delete later; branches with unique commits stay.

### Save-the-orphans candidates: unique commits on no remote (65)

| Branch | Last commit | Unique commits | In a worktree now |
|---|---|---|---|
| `bunny/JB-8.01b` | 2026-10-01 | 1 | yes |
| `bunny/D02a-rotation-normalise` | 2026-10-02 | 1 | yes |
| `bunny/JB-2.03a-audit-fixes` | 2026-10-02 | 1 | yes |
| `bunny/JB-9.01-audit-fixes` | 2026-10-02 | 2 | yes |
| `bunny/jvmTest-corpus-input` | 2026-10-02 | 4 | yes |
| `codex/jb-board-chrome` | 2026-10-02 | 5 | yes |
| `tmp-203a` | 2026-10-02 | 1 | no |
| `tmp-901` | 2026-10-02 | 1 | no |
| `tmp-901b` | 2026-10-02 | 2 | no |
| `tmp-d02a` | 2026-10-02 | 1 | no |
| `claude/friendly-bun-31ccb1` | 2026-10-07 | 1 | no |
| `codex/worker2-composer` | 2026-10-07 | 1 | yes |
| `codex/jb-measure-r51` | 2026-10-08 | 2 | no |
| `codex/jb-tile-paper` | 2026-10-08 | 1 | no |
| `codex/worker2-board-strip` | 2026-10-08 | 1 | yes |
| `opencode/dry-spatial-plan` | 2026-10-09 | 1 | no |
| `opencode/media-dry-apply-parity` | 2026-10-09 | 2 | yes |
| `opencode/media-dry-paper-only` | 2026-10-09 | 2 | yes |
| `opencode/media-render-benchmark` | 2026-10-09 | 1 | no |
| `opencode/mid-09-undo-sequence` | 2026-10-09 | 1 | yes |
| `opencode/mid-10-codec-corpus` | 2026-10-09 | 1 | yes |
| `opencode/mid-11-bake-partition` | 2026-10-09 | 1 | yes |
| `opencode/mid-12-archive-report` | 2026-10-09 | 1 | yes |
| `opencode/mid-13-board-boundary` | 2026-10-09 | 1 | yes |
| `opencode/mid-14-board-lifecycle` | 2026-10-09 | 1 | yes |
| `opencode/mid-15-sprite-grid` | 2026-10-09 | 1 | yes |
| `opencode/mid-16-swap-plan` | 2026-10-09 | 1 | yes |
| `opencode/mid-17-anim-ops` | 2026-10-09 | 1 | yes |
| `opencode/mid-18-region-frames` | 2026-10-09 | 1 | yes |
| `opencode/mid-19-layer-clone` | 2026-10-09 | 1 | yes |
| `opencode/mid-20-docjson` | 2026-10-09 | 1 | yes |
| `opencode/mid-21-playback` | 2026-10-09 | 1 | yes |
| `opencode/mid-22-filmstrip` | 2026-10-09 | 1 | yes |
| `opencode/mid-23-filltrace` | 2026-10-09 | 1 | yes |
| `opencode/mid-24-floodfill` | 2026-10-09 | 1 | yes |
| `opencode/mid-25-strokeedit` | 2026-10-09 | 1 | yes |
| `opencode/mid-26-composer` | 2026-10-09 | 1 | yes |
| `opencode/mid-27-inktile` | 2026-10-09 | 1 | yes |
| `opencode/mid-28-archive` | 2026-10-09 | 1 | yes |
| `opencode/mid-29-manifest` | 2026-10-09 | 1 | yes |
| `opencode/mid-05` | 2026-10-11 | 1 | yes |
| `opencode/mid-07` | 2026-10-11 | 1 | yes |
| `opencode/mid-08` | 2026-10-11 | 1 | yes |
| `vector/watercolour-phone-before-a1` | 2026-10-12 | 6 | no |
| `claude/dazzling-satoshi-75a363` | 2026-10-14 | 16 | no |
| `backup/main-folder-presync-20261015` | 2026-10-15 | 29 | no |
| `lead/symmetry-a1` | 2026-10-19 | 1 | yes |
| `lead/symmetry-a2` | 2026-10-19 | 3 | yes |
| `lead/symmetry-c` | 2026-10-19 | 1 | yes |
| `lead/crash-diag` | 2026-10-20 | 3 | no |
| `lead/follow-water-wip` | 2026-10-20 | 1 | no |
| `lead/main-hash` | 2026-10-20 | 1 | no |
| `lead/symmetry-b` | 2026-10-20 | 2 | yes |
| `lead/symmetry-d` | 2026-10-20 | 1 | yes |
| `lead/wc-live-land` | 2026-10-24 | 8 | no |
| `docs/board-state-map-20261027` | 2026-10-27 | 1 | yes |
| `docs/paper-state-map-20261027` | 2026-10-27 | 1 | yes |
| `backup/s19-pre-rebase-20261028` | 2026-10-28 | 7 | no |
| `claude/test-build-20261028` | 2026-10-28 | 3 | yes |
| `lead/unique-names-20261028` | 2026-10-28 | 1 | yes |
| `claude/workloss-20261110` | 2026-11-12 | 9 | yes |
| `claude/atlas-save-buffers-hold` | 2026-11-14 | 5 | no |
| `claude/atlas-solid-20261109` | 2026-11-14 | 5 | no |
| `claude/shell-launchtest-20261115` | 2026-11-15 | 4 | no |
| `claude/hover-labels-8c53af` | 2026-11-23 | 2 | yes |

### Unique commits, safe on a remote branch (115)

| Branch | Last commit | Unique commits | In a worktree now |
|---|---|---|---|
| `bunny/leftover-app-edits` | 2026-09-29 | 2 | no |
| `codex/abr-frontier-fix` | 2026-10-02 | 1 | no |
| `codex/brush-contact` | 2026-10-05 | 1 | yes |
| `media/jb-2.40` | 2026-10-07 | 1 | yes |
| `opencode/dry-window-geometry` | 2026-10-08 | 1 | no |
| `opencode/jb240-wip-audit` | 2026-10-08 | 1 | no |
| `opencode/legacy-ink-boundary-test` | 2026-10-08 | 1 | no |
| `opencode/media-fast-c-benchmark` | 2026-10-08 | 2 | no |
| `opencode/media-plain-bake-reference` | 2026-10-08 | 2 | no |
| `opencode/media-render-parity` | 2026-10-08 | 1 | no |
| `opencode/media-submission-trace` | 2026-10-08 | 1 | no |
| `opencode/media-upload-reuse` | 2026-10-08 | 2 | no |
| `opencode/r51-accessibility-audit` | 2026-10-08 | 1 | no |
| `opencode/r51-export-audit` | 2026-10-08 | 1 | no |
| `opencode/r51-memory-inventory` | 2026-10-08 | 1 | no |
| `opencode/r51-save-undo-audit` | 2026-10-08 | 1 | no |
| `opencode/shader-positive-control` | 2026-10-08 | 1 | no |
| `opencode/sprite-swap-export-test` | 2026-10-08 | 1 | no |
| `opencode/tile-paper-gpu-parity` | 2026-10-08 | 3 | no |
| `backup/jb240-media-wip-20261010` | 2026-10-10 | 3 | no |
| `backup/jb240-root-integration-wip-20261010` | 2026-10-10 | 2 | no |
| `vector/brush-format` | 2026-10-11 | 1 | no |
| `vector/h7-fixes` | 2026-10-11 | 5 | no |
| `vector/line-lab` | 2026-10-11 | 2 | no |
| `vector/lines-stack` | 2026-10-11 | 1 | no |
| `vector/pencil` | 2026-10-11 | 2 | no |
| `vector/stamp-pass` | 2026-10-11 | 1 | no |
| `vector/svg-export` | 2026-10-11 | 1 | no |
| `lead/jb-ui-integration` | 2026-10-13 | 1 | no |
| `ui/select-count-e4` | 2026-10-13 | 6 | no |
| `ui/select-count-main` | 2026-10-13 | 2 | no |
| `ui/select-e2` | 2026-10-13 | 6 | no |
| `vector/watercolour-phone` | 2026-10-13 | 14 | no |
| `lead/jb-live-lines` | 2026-10-14 | 2 | no |
| `ui/select-host-review-current` | 2026-10-14 | 1 | no |
| `lead/atlas-exp` | 2026-10-20 | 1 | yes |
| `lead/symmetry-check` | 2026-10-20 | 1 | yes |
| `backup/main-folder-20261024` | 2026-10-24 | 7 | no |
| `lead/select-integration-20261024` | 2026-10-24 | 27 | no |
| `vector/watercolour-finish-20261024` | 2026-10-24 | 18 | no |
| `vector/watercolour-live-20261023` | 2026-10-24 | 8 | no |
| `codex/select-lines` | 2026-10-25 | 31 | no |
| `vector/watercolour-k25-20261025` | 2026-10-25 | 13 | yes |
| `vector/watercolour-ordinary-probe-20261025` | 2026-10-25 | 2 | no |
| `codex/frame-duplicate-contract-20261026` | 2026-10-26 | 2 | no |
| `codex/select-a10-acceptance-20261026` | 2026-10-26 | 32 | no |
| `codex/select-a10-current-20261026` | 2026-10-26 | 6 | no |
| `codex/select-a10-wired-20261026` | 2026-10-26 | 5 | no |
| `codex/select-bench-20261025` | 2026-10-26 | 31 | no |
| `codex/tween-direct-20261026` | 2026-10-26 | 6 | no |
| `codex/tween-runtime-20261026` | 2026-10-26 | 4 | no |
| `codex/vector-tweens` | 2026-10-26 | 4 | yes |
| `lead/doc-maintenance-20261026` | 2026-10-26 | 1 | yes |
| `lead/frame-live-20261026` | 2026-10-26 | 5 | no |
| `ui/a10-switch-style-20261026` | 2026-10-26 | 4 | no |
| `ui/control-completion-20261026` | 2026-10-26 | 1 | no |
| `ui/select-review-20261024` | 2026-10-26 | 19 | no |
| `ui/ux-roadmap-20261026` | 2026-10-26 | 1 | no |
| `vector/watercolour-fixture-20261026` | 2026-10-26 | 3 | yes |
| `claude/send-to-existing-20261027` | 2026-10-27 | 1 | yes |
| `claude/send-to-studio-existing-20261027` | 2026-10-27 | 1 | yes |
| `codex/board-recovery-docs-20261026` | 2026-10-27 | 2 | no |
| `codex/frame-duplicate-probe-20261026` | 2026-10-27 | 10 | no |
| `codex/native-init-cleanup-20261027` | 2026-10-27 | 5 | no |
| `codex/paper-recovery-docs-20261026` | 2026-10-27 | 1 | yes |
| `codex/select-d23-presenter-20261026` | 2026-10-27 | 3 | yes |
| `docs/clips-manifest-20261027` | 2026-10-27 | 1 | no |
| `lead/native-proof-integration-20261026` | 2026-10-27 | 5 | no |
| `ui/dark-canvas-contrast-20261026` | 2026-10-27 | 3 | no |
| `vector/watercolour-dispatch-20261026` | 2026-10-27 | 2 | no |
| `vislab/proxy-bind-20261027` | 2026-10-27 | 11 | yes |
| `claude/ani1-sprite-row-20261028` | 2026-10-28 | 1 | no |
| `claude/joybot-icon-aspect-20261027` | 2026-10-28 | 1 | no |
| `claude/s19-cut-20261028` | 2026-10-28 | 4 | no |
| `claude/stu2-undo-preview-20261027` | 2026-10-28 | 1 | no |
| `claude/stu4-title-audio-20261028` | 2026-10-28 | 1 | no |
| `claude/ui1-hover1-20261028` | 2026-10-28 | 2 | no |
| `claude/ui1-text-20261028` | 2026-10-28 | 2 | yes |
| `codex/paper-approved-20261029` | 2026-10-29 | 11 | yes |
| `lead/paper-baseline-20261029` | 2026-10-29 | 1 | yes |
| `lead/paper-cache-20261029` | 2026-10-29 | 5 | yes |
| `lead/paper-evaluation-20261029` | 2026-10-29 | 4 | no |
| `lead/paper-full-evaluation-20261029` | 2026-10-29 | 9 | yes |
| `claude/festive-perlman-8dde53` | 2026-11-01 | 3 | yes |
| `claude/jolly-jepsen-a5fabe` | 2026-11-01 | 1 | yes |
| `claude/joybrush-lead-226762` | 2026-11-04 | 2 | no |
| `lead/live-goal-20261104` | 2026-11-04 | 2 | yes |
| `claude/ai-fixes-20261112` | 2026-11-12 | 3 | yes |
| `claude/editor-timing-20261112` | 2026-11-12 | 1 | yes |
| `claude/export-timing-20261112` | 2026-11-12 | 1 | yes |
| `claude/review-ai-20261112` | 2026-11-12 | 1 | no |
| `claude/launch-docs-20261113-042476` | 2026-11-13 | 3 | no |
| `claude/modest-keller-2875af` | 2026-11-13 | 3 | yes |
| `claude/stu6-swatches-20261112` | 2026-11-13 | 1 | yes |
| `claude/textlab-20261111` | 2026-11-13 | 2 | yes |
| `claude/atlas-land-20261114` | 2026-11-14 | 5 | no |
| `claude/mediapipe-detector-20261113` | 2026-11-14 | 1 | yes |
| `claude/oil-taper-20261114` | 2026-11-14 | 1 | no |
| `claude/play-candidate-20261114` | 2026-11-14 | 8 | yes |
| `claude/bughunt-20261115` | 2026-11-16 | 2 | no |
| `claude/jbx42-20261116` | 2026-11-16 | 5 | no |
| `claude/atlas-leavesparse-20261117` | 2026-11-17 | 1 | no |
| `claude/atlas-widthredo-20261117` | 2026-11-17 | 3 | no |
| `claude/paper-eval40-20261117` | 2026-11-17 | 1 | no |
| `claude/paper-wrapup-20261117` | 2026-11-17 | 2 | no |
| `claude/paper-eval40b-20261118` | 2026-11-18 | 1 | no |
| `claude/paper-eval40c-20261118` | 2026-11-18 | 1 | yes |
| `codex/note9-studio-voice-20261119` | 2026-11-19 | 1 | yes |
| `codex/r4-aud-bake` | 2026-11-20 | 1 | yes |
| `codex/r4-gl-mask` | 2026-11-20 | 1 | yes |
| `codex/r4-txt-transfer` | 2026-11-20 | 2 | yes |
| `codex/audio-studio-voice` | 2026-11-21 | 2 | yes |
| `claude/engine-r4-20261120-e499b0` | 2026-11-23 | 9 | yes |
| `claude/priceless-yalow-249f46` | 2026-11-23 | 1 | yes |
| `codex/motionblur-20261122` | 2026-11-23 | 19 | yes |

## Worktrees still there (167), by why they stay

| Worktree | Branch | Last commit | Why it stays |
|---|---|---|---|
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-0.10` | `(detached)` | 2026-09-29 | 1 unique commits |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-1.07` | `(detached)` | 2026-09-29 | 1 unique commits |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-2.02c` | `(detached)` | 2026-09-29 | 1 unique commits |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-2.11` | `(detached)` | 2026-09-29 | 1 unique commits |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-3.02` | `(detached)` | 2026-09-29 | 1 unique commits |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-3.05` | `(detached)` | 2026-09-29 | 1 unique commits |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-5.01` | `(detached)` | 2026-09-29 | 1 unique commits |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-5.03` | `(detached)` | 2026-09-29 | 1 unique commits |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-5.11` | `(detached)` | 2026-09-29 | 1 unique commits |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-JB-8.04` | `(detached)` | 2026-09-29 | 1 unique commits |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jbclean` | `(detached)` | 2026-09-30 | 9 uncommitted |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-8.01b` | `bunny/JB-8.01b` | 2026-10-01 | 1 unique commits |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.03` | `codex/jb-9.03` | 2026-10-01 | 3 uncommitted |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.03b` | `codex/jb-9.03b` | 2026-10-01 | 17 uncommitted |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.06` | `codex/jb-9.06` | 2026-10-01 | 9 uncommitted |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.08` | `codex/jb-9.08` | 2026-10-01 | 9 uncommitted |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-2.03afix` | `bunny/JB-2.03a-audit-fixes` | 2026-10-02 | 1 unique commits |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-8.04fix` | `bunny/JB-8.04-real-file-fix` | 2026-10-02 | 3 uncommitted |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.01fix` | `bunny/JB-9.01-audit-fixes` | 2026-10-02 | 2 unique commits |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.06b` | `codex/jb-9.06b` | 2026-10-02 | 1 uncommitted |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.06c` | `codex/jb-9.06c` | 2026-10-02 | 6 uncommitted |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.07` | `codex/jb-9.07` | 2026-10-02 | 10 uncommitted |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-9.10` | `codex/jb-9.10` | 2026-10-02 | 24 uncommitted |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-board-chrome` | `codex/jb-board-chrome` | 2026-10-02 | 4 uncommitted, 5 unique commits |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-d02afix` | `bunny/D02a-rotation-normalise` | 2026-10-02 | 1 unique commits |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-pencil-quality` | `opencode/pencil-quality` | 2026-10-02 | 22 uncommitted |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-verify` | `bunny/jvmTest-corpus-input` | 2026-10-02 | 4 unique commits |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jbroad` | `(detached)` | 2026-10-02 | 1 uncommitted |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-brush-specialist` | `codex/brush-contact` | 2026-10-05 | 6 uncommitted, 1 unique commits |
| `C:/Temp/jb-v6` | `(detached)` | 2026-10-06 | 30 uncommitted |
| `C:/Temp/jb-media` | `media/jb-2.40` | 2026-10-07 | 23 uncommitted, 1 unique commits |
| `C:/Temp/jb-opencode-media-snapshot` | `(detached)` | 2026-10-07 | 25 uncommitted, 1 unique commits |
| `C:/Temp/jb-worker2-composer` | `codex/worker2-composer` | 2026-10-07 | 1 uncommitted, 1 unique commits |
| `C:/Temp/jb-opencode-shader-verification` | `(detached)` | 2026-10-08 | 3 uncommitted |
| `C:/Temp/jb-opencode-test-verification` | `(detached)` | 2026-10-08 | 4 uncommitted |
| `C:/Temp/jb-root-media-integration` | `codex/jb-root-media-integration` | 2026-10-08 | 29 uncommitted |
| `C:/Temp/jb-worker2-board-strip` | `codex/worker2-board-strip` | 2026-10-08 | 1 uncommitted, 1 unique commits |
| `C:/Users/JoyRaptor/AppData/Local/Temp/jb-d02a` | `(detached)` | 2026-10-08 | 1 uncommitted |
| `C:/Temp/jb-opencode-dry-apply-parity` | `opencode/media-dry-apply-parity` | 2026-10-09 | 2 unique commits |
| `C:/Temp/jb-opencode-dry-paper-only` | `opencode/media-dry-paper-only` | 2026-10-09 | 2 unique commits |
| `C:/Temp/jb-opencode-mid-09` | `opencode/mid-09-undo-sequence` | 2026-10-09 | 1 unique commits |
| `C:/Temp/jb-opencode-mid-10` | `opencode/mid-10-codec-corpus` | 2026-10-09 | 1 unique commits |
| `C:/Temp/jb-opencode-mid-11` | `opencode/mid-11-bake-partition` | 2026-10-09 | 1 unique commits |
| `C:/Temp/jb-opencode-mid-12` | `opencode/mid-12-archive-report` | 2026-10-09 | 1 unique commits |
| `C:/Temp/jb-opencode-mid-13` | `opencode/mid-13-board-boundary` | 2026-10-09 | 1 unique commits |
| `C:/Temp/jb-opencode-mid-14` | `opencode/mid-14-board-lifecycle` | 2026-10-09 | 1 unique commits |
| `C:/Temp/jb-opencode-mid-15` | `opencode/mid-15-sprite-grid` | 2026-10-09 | 1 unique commits |
| `C:/Temp/jb-opencode-mid-16` | `opencode/mid-16-swap-plan` | 2026-10-09 | 1 unique commits |
| `C:/Temp/jb-opencode-mid-17` | `opencode/mid-17-anim-ops` | 2026-10-09 | 1 unique commits |
| `C:/Temp/jb-opencode-mid-18` | `opencode/mid-18-region-frames` | 2026-10-09 | 1 unique commits |
| `C:/Temp/jb-opencode-mid-19` | `opencode/mid-19-layer-clone` | 2026-10-09 | 1 unique commits |
| `C:/Temp/jb-opencode-mid-20` | `opencode/mid-20-docjson` | 2026-10-09 | 1 unique commits |
| `C:/Temp/jb-opencode-mid-21` | `opencode/mid-21-playback` | 2026-10-09 | 1 unique commits |
| `C:/Temp/jb-opencode-mid-22` | `opencode/mid-22-filmstrip` | 2026-10-09 | 1 unique commits |
| `C:/Temp/jb-opencode-mid-23` | `opencode/mid-23-filltrace` | 2026-10-09 | 1 unique commits |
| `C:/Temp/jb-opencode-mid-24` | `opencode/mid-24-floodfill` | 2026-10-09 | 1 unique commits |
| `C:/Temp/jb-opencode-mid-25` | `opencode/mid-25-strokeedit` | 2026-10-09 | 1 unique commits |
| `C:/Temp/jb-opencode-mid-26` | `opencode/mid-26-composer` | 2026-10-09 | 1 unique commits |
| `C:/Temp/jb-opencode-mid-27` | `opencode/mid-27-inktile` | 2026-10-09 | 1 unique commits |
| `C:/Temp/jb-opencode-mid-28` | `opencode/mid-28-archive` | 2026-10-09 | 1 unique commits |
| `C:/Temp/jb-opencode-mid-29` | `opencode/mid-29-manifest` | 2026-10-09 | 1 unique commits |
| `C:/Temp/jb-opencode-verify-09-18` | `(detached)` | 2026-10-09 | 20 uncommitted |
| `C:/Temp/jb-region-routing` | `codex/region-routing` | 2026-10-09 | 3 uncommitted |
| `C:/Temp/jb-vector-tilt` | `vector/tilt-flip` | 2026-10-10 | 6 uncommitted |
| `C:/Temp/jb-opencode-mid-05` | `opencode/mid-05` | 2026-10-11 | 1 unique commits |
| `C:/Temp/jb-opencode-mid-07` | `opencode/mid-07` | 2026-10-11 | 1 unique commits |
| `C:/Temp/jb-opencode-mid-08` | `opencode/mid-08` | 2026-10-11 | 1 unique commits |
| `C:/Temp/jb-520a` | `lead/jb-5.20a` | 2026-10-12 | 7 uncommitted |
| `C:/Temp/jb-520a-recovery` | `lead/jb-5.20a-recovery` | 2026-10-12 | 34 uncommitted |
| `C:/Temp/jb-merge` | `(detached)` | 2026-10-13 | 1 unique commits |
| `C:/Temp/jb-capture-current` | `lead/jb-capture-current` | 2026-10-14 | 2 uncommitted |
| `C:/Temp/jb-captured-source` | `lead/jb-captured-source` | 2026-10-14 | 20 uncommitted |
| `C:/Temp/jb-promotion-inventory` | `lead/jb-promotion-inventory` | 2026-10-14 | 6 uncommitted |
| `C:/Temp/jb-captured-domains` | `(detached)` | 2026-10-15 | 5 uncommitted |
| `C:/Temp/jb-captured-engine` | `(detached)` | 2026-10-15 | 30 uncommitted |
| `C:/Temp/jb-captured-scene` | `(detached)` | 2026-10-15 | 2 uncommitted |
| `C:/Temp/jb-cold-faults` | `(detached)` | 2026-10-15 | 2 uncommitted, 2 unique commits |
| `C:/Temp/jb-claude-main` | `lead/claude-lines2` | 2026-10-17 | 6 uncommitted |
| `C:/Temp/jb-justworks` | `lead/just-works` | 2026-10-17 | 2 uncommitted |
| `C:/Temp/jb-unibrush-baseline` | `(detached)` | 2026-10-18 | 1 uncommitted |
| `C:/Temp/jb-symmetry-a1` | `lead/symmetry-a1` | 2026-10-19 | 1 unique commits |
| `C:/Temp/jb-symmetry-a2` | `lead/symmetry-a2` | 2026-10-19 | 3 unique commits |
| `C:/Temp/jb-symmetry-c` | `lead/symmetry-c` | 2026-10-19 | 1 unique commits |
| `C:/Temp/jb-symmetry` | `lead/symmetry-1019` | 2026-10-20 | named on BOARD |
| `C:/Temp/jb-symmetry-b` | `lead/symmetry-b` | 2026-10-20 | 2 unique commits |
| `C:/Temp/jb-symmetry-d` | `lead/symmetry-d` | 2026-10-20 | 1 unique commits |
| `C:/Temp/jb-symmetry-f2` | `lead/symmetry-f2` | 2026-10-20 | 2 uncommitted |
| `C:/Temp/jb-symmetry-f3` | `lead/symmetry-f3` | 2026-10-20 | 4 uncommitted |
| `C:/Temp/lead-atlas-exp` | `lead/atlas-exp` | 2026-10-20 | 4 uncommitted, 1 unique commits |
| `C:/Temp/lead-symcheck` | `lead/symmetry-check` | 2026-10-20 | 3 uncommitted, 1 unique commits |
| `C:/Temp/helper-main` | `(detached)` | 2026-10-25 | 1 uncommitted |
| `C:/Temp/helper-room` | `helper1/atlas-room` | 2026-10-25 | 1 uncommitted |
| `C:/Temp/jb-watercolour-finish-20261024` | `vector/watercolour-k25-20261025` | 2026-10-25 | 13 unique commits |
| `C:/Temp/jb-doc-maintenance-20261026` | `lead/doc-maintenance-20261026` | 2026-10-26 | 1 uncommitted, 1 unique commits |
| `C:/Temp/jb-vector-tweens` | `codex/vector-tweens` | 2026-10-26 | 4 unique commits |
| `C:/Temp/jb-watercolour-fixture-20261026` | `vector/watercolour-fixture-20261026` | 2026-10-26 | 3 unique commits |
| `C:/Temp/jb-board-state-map-20261027` | `docs/board-state-map-20261027` | 2026-10-27 | 1 unique commits |
| `C:/Temp/jb-codex-select` | `codex/select-d23-presenter-20261026` | 2026-10-27 | 3 unique commits |
| `C:/Temp/jb-paper-recovery-20261026` | `codex/paper-recovery-docs-20261026` | 2026-10-27 | 1 unique commits |
| `C:/Temp/jb-paper-state-map-20261027` | `docs/paper-state-map-20261027` | 2026-10-27 | 1 unique commits |
| `C:/Temp/jb-send-existing-20261027` | `claude/send-to-existing-20261027` | 2026-10-27 | 1 unique commits |
| `C:/Temp/jb-symmetry-recover-20261027` | `claude/symmetry-recover-20261027` | 2026-10-27 | named on BOARD |
| `C:/Temp/jc-send-existing` | `claude/send-to-studio-existing-20261027` | 2026-10-27 | named on BOARD, 1 unique commits |
| `C:/Temp/jc-vl-lane` | `claude/state-sync-20261027` | 2026-10-27 | named on BOARD |
| `C:/Temp/joybench-dev` | `bench/joybot-phone` | 2026-10-27 | 10 uncommitted |
| `C:/Temp/vl-proxy-bind` | `vislab/proxy-bind-20261027` | 2026-10-27 | 11 unique commits |
| `C:/Temp/jc-lead-docs` | `lead/land-helper-20261028` | 2026-10-28 | named on BOARD |
| `C:/Temp/jc-lead-roadmap` | `lead/fold-overnight-20261028` | 2026-10-28 | named on BOARD |
| `C:/Temp/jc-marks-20261028` | `lead/unique-names-20261028` | 2026-10-28 | 1 unique commits |
| `C:/Temp/jc-testbuild` | `claude/test-build-20261028` | 2026-10-28 | 3 unique commits |
| `C:/Temp/jc-ui1-text-20261028` | `claude/ui1-text-20261028` | 2026-10-28 | named on BOARD, 2 unique commits |
| `C:/Temp/joybot-20261028` | `claude/joybot-20261028` | 2026-10-28 | named on BOARD |
| `C:/Temp/vl-studio-fx` | `vislab/r8-follow-20261027` | 2026-10-28 | named on BOARD |
| `C:/Temp/jb-paper-approved-20261029` | `codex/paper-approved-20261029` | 2026-10-29 | 11 unique commits |
| `C:/Temp/jb-paper-baseline-20261029` | `lead/paper-baseline-20261029` | 2026-10-29 | 1 unique commits |
| `C:/Temp/jb-paper-cache-20261029` | `lead/paper-cache-20261029` | 2026-10-29 | 5 unique commits |
| `C:/Temp/jb-paper-evaluation-20261029` | `lead/paper-full-evaluation-20261029` | 2026-10-29 | 9 unique commits |
| `C:/+Projects/JoyCreator/.claude/worktrees/festive-perlman-8dde53` | `claude/festive-perlman-8dde53` | 2026-11-01 | 3 unique commits |
| `C:/+Projects/JoyCreator/.claude/worktrees/jolly-jepsen-a5fabe` | `claude/jolly-jepsen-a5fabe` | 2026-11-01 | 2 uncommitted, 1 unique commits |
| `C:/Temp/jb-live-goal-20261104` | `lead/live-goal-20261104` | 2026-11-04 | named on BOARD, 2 unique commits |
| `C:/Temp/claude-workloss-20261110` | `claude/workloss-20261110` | 2026-11-12 | 9 unique commits |
| `C:/Temp/jc-editorfix-20261112` | `claude/editor-timing-20261112` | 2026-11-12 | 1 unique commits |
| `C:/Temp/jc-exportsafe-20261112` | `claude/export-timing-20261112` | 2026-11-12 | 1 unique commits |
| `C:/Temp/jc-recfolder-20261112` | `claude/ai-fixes-20261112` | 2026-11-12 | 3 unique commits |
| `C:/+Projects/JoyCreator/.claude/worktrees/agent-af4efb638ebe17937` | `claude/stu6-swatches-20261112` | 2026-11-13 | 1 unique commits |
| `C:/+Projects/JoyCreator/.claude/worktrees/modest-keller-2875af` | `claude/modest-keller-2875af` | 2026-11-13 | live or linked session / this lane, 3 unique commits |
| `C:/+Projects/JoyCreator/.claude/worktrees/textlab-cross-wing-2dd79d` | `claude/textlab-cross-wing-2dd79d` | 2026-11-13 | named on BOARD |
| `C:/Temp/textlab-20261111` | `claude/textlab-20261111` | 2026-11-13 | named on BOARD, 1 uncommitted, 2 unique commits |
| `C:/Temp/textlab-phone-20261112` | `claude/textlab-phone-20261112` | 2026-11-13 | named on BOARD, 5 uncommitted |
| `C:/+Projects/JoyCreator/.claude/worktrees/anim-labs-20261113-136711` | `claude/anim-labs-20261113-136711` | 2026-11-14 | 1 uncommitted |
| `C:/+Projects/JoyCreator/.claude/worktrees/determined-lovelace-7fb58c` | `claude/studio-text-20261114` | 2026-11-14 | 1 uncommitted |
| `C:/+Projects/JoyCreator/.claude/worktrees/launch-docs-20261113-042476` | `claude/mediapipe-detector-20261113` | 2026-11-14 | live or linked session / this lane, 1 uncommitted, 1 unique commits |
| `C:/Temp/play-candidate-20261114` | `claude/play-candidate-20261114` | 2026-11-14 | named on BOARD, 4 uncommitted, 8 unique commits |
| `C:/+Projects/JoyCreator/.claude/worktrees/translations-20261115-bea302` | `claude/icons-20261115-174a79` | 2026-11-15 | live or linked session / this lane, named on BOARD |
| `C:/+Projects/JoyCreator/.claude/worktrees/ui1-text-merge-e694e0` | `claude/device-checks-20261115-e321bf` | 2026-11-15 | live or linked session / this lane |
| `C:/+Projects/JoyCreator/.claude/worktrees/joybrush-screens-20261113-1a6f0b` | `claude/joybrush-screens-20261113-1a6f0b` | 2026-11-16 | live or linked session / this lane, named on BOARD |
| `C:/+Projects/JoyCreator/.claude/worktrees/play-build-20261114-1a01c6` | `claude/cloud-test-failures-20261116` | 2026-11-16 | live or linked session / this lane, named on BOARD |
| `C:/Temp/fx1-handoff-20261116` | `claude/fx1-handoff-20261116` | 2026-11-16 | named on BOARD |
| `C:/Temp/claude-door2-20261104` | `(detached)` | 2026-11-17 | 30 uncommitted |
| `C:/+Projects/JoyCreator/.claude/worktrees/brush-set-20261113-81446f` | `claude/brush-set-20261113-81446f` | 2026-11-18 | live or linked session / this lane, named on BOARD |
| `C:/+Projects/JoyCreator/.claude/worktrees/sizzle-prep-20261115-7d8ab2` | `claude/joybot-trick-20261116` | 2026-11-18 | live or linked session / this lane |
| `C:/Temp/bughunt-20261115` | `claude/open-tiles-20261117` | 2026-11-18 | named on BOARD |
| `C:/Temp/claude-atlas-20261109` | `claude/atlas-fibres2-20261117` | 2026-11-18 | named on BOARD |
| `C:/Temp/paper-wrapup-20261117` | `claude/paper-eval40c-20261118` | 2026-11-18 | named on BOARD, 1 unique commits |
| `C:/+Projects/JoyCreator/scratchpad/studio-voice-src` | `codex/note9-studio-voice-20261119` | 2026-11-19 | 1 uncommitted, 1 unique commits |
| `C:/+Projects/JoyCreator/.claude/worktrees/shell-lobby-20261113-78172a` | `claude/shell-lobby-20261113-78172a` | 2026-11-20 | live or linked session / this lane |
| `C:/+Projects/JoyCreator/.claude/worktrees/tab1-sizes-20261114-7011d5` | `claude/codex-r4-land-a5606e` | 2026-11-20 | live or linked session / this lane, named on BOARD |
| `C:/+Projects/JoyCreator/scratchpad/r4-aud-bake` | `codex/r4-aud-bake` | 2026-11-20 | 1 unique commits |
| `C:/+Projects/JoyCreator/scratchpad/r4-combined-check` | `(detached)` | 2026-11-20 | 38 uncommitted |
| `C:/+Projects/JoyCreator/scratchpad/r4-studio-core` | `codex/r4-gl-mask` | 2026-11-20 | 1 unique commits |
| `C:/+Projects/JoyCreator/scratchpad/r4-txt-transfer` | `codex/r4-txt-transfer` | 2026-11-20 | 2 unique commits |
| `C:/Temp/joybot-sheet-20261113` | `claude/joybot-sheet-20261113` | 2026-11-20 | named on BOARD |
| `C:/+Projects/JoyCreator/scratchpad/audio-studio-voice-review` | `codex/audio-studio-voice` | 2026-11-21 | 2 unique commits |
| `C:/+Projects/JoyCreator/.claude/worktrees/avatarlab-video-voice-d2d4e9` | `claude/avatarlab-video-voice-d2d4e9` | 2026-11-22 | 1 uncommitted |
| `C:/+Projects/JoyCreator/.claude/worktrees/avatarlab-walk-stage-8c89bc` | `claude/avatarlab-walk-stage-8c89bc` | 2026-11-22 | 1 uncommitted |
| `C:/+Projects/JoyCreator/.claude/worktrees/face-capture-scout-b847e5` | `claude/face-capture-scout-b847e5` | 2026-11-22 | named on BOARD, 1 uncommitted |
| `C:/+Projects/JoyCreator/.claude/worktrees/joybot-20261113-d3a666` | `claude/avatarlab-scene-room-ac08ad` | 2026-11-22 | named on BOARD, 1 uncommitted |
| `C:/+Projects/JoyCreator` | `joy-creator` | 2026-11-23 | live or linked session / this lane, named on BOARD, 85 uncommitted |
| `C:/+Projects/JoyCreator/.claude/worktrees/gracious-mendel-3513fb` | `claude/gracious-mendel-3513fb` | 2026-11-23 | 10 uncommitted |
| `C:/+Projects/JoyCreator/.claude/worktrees/hover-labels-8c53af` | `claude/hover-labels-8c53af` | 2026-11-23 | live or linked session / this lane, 2 unique commits |
| `C:/+Projects/JoyCreator/.claude/worktrees/priceless-yalow-249f46` | `claude/priceless-yalow-249f46` | 2026-11-23 | 1 unique commits |
| `C:/+Projects/JoyCreator/.claude/worktrees/tickbox-palette-fc3dc7` | `claude/tickbox-palette-fc3dc7` | 2026-11-23 | live or linked session / this lane, named on BOARD |
| `C:/+Projects/JoyCreator/.claude/worktrees/ui1-leftovers-20261110-38909d` | `claude/engine-r4-20261120-e499b0` | 2026-11-23 | live or linked session / this lane, named on BOARD, 9 unique commits |
| `C:/+Projects/JoyCreator/.claude/worktrees/website-f86389` | `claude/website-f86389` | 2026-11-23 | live or linked session / this lane, 1 uncommitted |
| `C:/Temp/avatarlab-20261118` | `claude/avatarlab-20261118` | 2026-11-23 | named on BOARD |
| `C:/Temp/motionblur-20261122` | `claude/motionblur-plan-20261122` | 2026-11-23 | named on BOARD |
| `C:/workspace/codex-motionblur-20261122` | `codex/motionblur-20261122` | 2026-11-23 | 19 unique commits |
