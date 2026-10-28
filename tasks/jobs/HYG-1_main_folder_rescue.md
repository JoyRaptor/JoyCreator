# HYG-1: Land the main folder's unsaved work, then bring the folder back in step

**Milestone:** M0  ·  **State:** 🟡 main folder synced with GitHub 10-28 (its old edits kept in `stash@{0}` there, on `rescue/main-folder-20261027`, and its 7 local commits on `backup/main-folder-local-commits-20261028`); timeline marks landed (STU-3); left: the JoyBrush pieces (tuft tuning files, staged tween reviews) for the JoyBrush lane  ·  **Owner:** lead  ·  **Checked:** 2026-10-28

## Why it matters
Finished work was sitting only on one hard drive, in a folder no other session can see. One bad reset
and it is gone. And the owner looks at the main folder, so when it lags behind GitHub he sees features
"missing" that are really built.

## Done when
- [x] Every unsaved edit in the main folder is on GitHub (done 10-27: `rescue/main-folder-20261027`, `94a5f7e69`).
- [ ] Each piece in the rescue branch is either landed on `joy-creator` or recorded as obsolete here.
- [ ] The main folder's `joy-creator` equals `origin/joy-creator`, with its working tree clean (or holding only
      a live session's in-progress work, named on the BOARD).

## Where it stands (10-27)
The main folder was on local `joy-creator` at `fb8042841`: **55 commits behind GitHub and 7 ahead.**
- The 7 local-only commits are all superseded by later versions on GitHub (same subjects; the GitHub
  versions are newer and fuller: `a94933ad7`, `c473b0171`, `95c7cd868`, `42dce3428`, `f5a342ae9`,
  `4f6bdad1c`, `243388e87`). Safe to drop once the rescue is landed.
- About 470 "modified" files were mostly line-ending noise from 10-19. **Real edits: 14 files, plus 25
  staged and about 85 untracked**, all now in the rescue branch (code and text; screenshots and
  downloaded design assets stayed local).

What the rescue branch holds:
| Piece | From | Goes to |
|---|---|---|
| Timeline bookmarks and regions (`ui/faditor/markers/**`, big edits to `FaditorEditorActivity`, `ExportManager`, `EditorTimelineView`, `ExportService`, `AudioLayerSync`, strings, `tools/jvm-harness/markers/`, `SPEC_TIMELINE_MARKS_20261023.md`, `road_map.md` notes) | Codex, 10-23/24 | [STU-3](STU-3_timeline_marks.md) |
| JoyBrush tuft tuning (`TuftTuningView.kt`, `TuftKnobs.kt`, `TuftTuningTest.kt`) | unknown JoyBrush session | JoyBrush lead decides |
| `JoyBrushActivity.kt` / `TopButton.kt` edits (Joybot button face) | Joybot session, 10-24 | probably already on GitHub as `147c19c00`; compare, then drop |
| `ItemLocks.java` (7 lines) | Joybot session | compare with GitHub; land or drop |
| Staged tween reviews (`tasks/joybrush/reviews/TWEEN_*`, `handoffs/TWEEN_COORDINATION_20261026.md`) | Codex tween specialist | JoyBrush lead (check they exist on the tween branches) |
| 2026-10-18 audit (`tasks/audits/2026-10-18/*`) | Codex audit | land as-is (docs) |
| Design records (`tasks/design/*.html`) | UI sessions | **landed 10-27** with the roadmap |
| Notes in `todo.md`, `VISLAB_TODO.md`, `lessons.md`, `AGENT_BOARD.md`, `CURRENT_HANDOFF.md`, `LANES.md`, `STATE.md`, `ROADMAP.md` | various | merge the facts into the right job cards, then drop |

## What is left
- [ ] Land STU-3 (timeline marks) from the rescue branch onto current `joy-creator` (re-apply the diff by hand where the editor moved).
- [ ] Compare the Joybot and JoyBrush pieces with GitHub; land or drop each.
- [ ] Hand the tuft files and tween reviews to the JoyBrush lead (BOARD message).
- [ ] When no session holds files in the main folder (check the BOARD and `isRunning` sessions): reset the
      main folder's `joy-creator` to `origin/joy-creator` and clean its tree. **Only after every piece above
      is accounted for.**
- [ ] Do not commit `tasks/api.txt` (it holds a real key). See "Waiting on the owner" S3.

## Files and traps
- `rescue/main-folder-20261027` sits on the OLD base `fb8042841`. Never merge it whole; cherry-pick or
  re-apply pieces.
- The VisLab lead also edits in the main folder; check it is not mid-edit before resetting.
