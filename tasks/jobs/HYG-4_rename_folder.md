# HYG-4: Rename the project folder away from "Screenrecorder/FadCam"

**Milestone:** M0  ·  **State:** ⛔ waiting on the owner (S2: the name and a quiet hour)  ·  **Owner:** project lead  ·  **Checked:** 2026-10-27

## Why it matters
Joy Creator is a complete studio, not a screen recorder, and it is leaving FadCam behind. The folder
name is the first thing every agent reads.

## Is it safe? Yes, with this checklist
Nothing inside the app depends on the folder's name. What depends on it, checked 10-27:

| Depends on the path | Effect of renaming | Fix |
|---|---|---|
| **About 156 git worktrees** (each records the main folder's path) | they lose their link | `git worktree repair` from the new folder, once |
| **Claude Code memory** (`~/.claude/projects/C---Projects-Screenrecorder-FadCam/memory`) | new sessions start with no memory | copy the `memory/` folder to the new project key |
| **Desktop app sessions** (each stores its folder) | old sessions open in a missing folder | start new sessions in the new folder; old ones stay readable |
| `watch-build.ps1`, `tools/jvm-harness/run-specj-suite.sh` | the watcher and one test script point at the old path | edit two lines |
| `export/fixtures/parity/*.json` (8 test fixtures) | those tests look for media at the old path | update the paths |
| Docs in `tasks/` (64 files) | old links read wrong | history; leave them, fix only live docs |
| Gradle caches | rebuild once (a few minutes) | none |
| Codex / OpenCode sessions | same as desktop sessions | start new ones in the new folder |

## Done when
- [ ] The folder is `C:\+Projects\JoyCreator` (or the owner's choice) and the watcher builds there.
- [ ] `git worktree list` shows no broken entries.
- [ ] A new Claude session in the new folder sees the memory index.
- [ ] AGENT_START.md, the BOARD and the phone and build scripts name the new path.

## What is left (the quiet hour)
- [ ] Owner picks the name (S2). Recommendation: `C:\+Projects\JoyCreator`.
- [ ] Every session closed (desktop app, Codex, OpenCode) and the watcher stopped. The BOARD says "renaming".
- [ ] Move the folder (Explorer or `Move-Item`); then `git worktree repair` from the new folder.
- [ ] Copy `~/.claude/projects/C---Projects-Screenrecorder-FadCam/memory/` to the new key
      (`C---Projects-JoyCreator`), and the old key's `MEMORY.md` with it.
- [ ] Fix `watch-build.ps1`, `run-specj-suite.sh`, the 8 fixtures; restart the watcher; one build.
- [ ] Update AGENT_START.md and the BOARD; announce on the BOARD.

## Files and traps
- Do it after HYG-1 (so the main folder is clean) and ideally after HYG-3 (fewer worktrees to repair).
- Do not rename while any session is running in it: their edits would land in a folder that no longer exists.
