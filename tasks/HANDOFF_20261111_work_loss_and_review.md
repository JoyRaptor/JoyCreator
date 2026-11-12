# Handoff, 2026-11-11: the work-loss hunt and the code review

From Claude Fable 5.2 (session "work-loss hunt"), for whoever continues. The owner's order, in his words:
"go through all of studio for work loss and get that finished. then go through the entire project code review
systematically and exhaustively for quality, performance bugs etc to improve. go autonomously, hardest
first. Another agent is working on oils and paints: don't bother with the oils or watercolor or atlas."

**Start with this sentence:** "Continue from tasks/HANDOFF_20261111_work_loss_and_review.md."

## Where everything is

| What | Where |
|---|---|
| Everything below is landed on | `origin/joy-creator` (check `git log --grep="Work-loss\|Code review\|Export:"`) |
| My branch and worktree | `claude/workloss-20261110`, `C:/Temp/claude-workloss-20261110` (has `local.properties`; builds with `./gradlew.bat :app:assembleDefaultDebug`, about 1 to 3 minutes) |
| Work-loss findings, each with its state and proof | [`tasks/audits/work_loss_20261110.md`](audits/work_loss_20261110.md) |
| Code-review findings (export, the timeline clock, the editor), each fixed / confirmed / scout | [`tasks/audits/code_review_20261111.md`](audits/code_review_20261111.md) |
| Job card | [`tasks/jobs/SAFE-1_work_loss.md`](jobs/SAFE-1_work_loss.md); roadmap row SAFE-1; STATE §7 rows |
| No locks held | phone and build locks released |

## Done and landed (built, tested off the phone; see the two reports for each row)

- **Studio work-loss, two passes:** one writer per project and no stale queued save; a project open in two
  places (reload on return, an off-screen stale editor does not save, a kept copy of what a save replaces);
  transcription that outlives its editor; audio-only projects; timeline marks in the folder copy; restart
  reopens the same project; silent sprite-sheet drops; delete and MOVE checks; every edit asks for an
  autosave; Consolidate (same-named files, failed copies said); an export never replaces a finished one;
  zip import could save over the original (a project is the folder it lives in); Avatar Studio saves when
  left; folder copies owed at close are caught up; a restore puts in-project files back inside the project.
- **JoyBrush save lane:** a drawing that does not open closes the canvas and asks; the drawing list has a
  backup and rebuilds from the files; streamed folder copy; change of folder.
- **Folder, Trash, VisLab:** Trash log rebuilds; repair in subfolders; recordings Trash list; avatar save
  swap; VisLab looks made while the folder was out of reach come home.
- **Code review fixes:** extract-audio out-of-memory and wrong clip muted; sound-delay estimate cached;
  timeline verbose logging off; undo merge into a snapshot step; source-check and Consolidate dialog
  guards; picture-in-picture sound divided by speed twice; loudness pass; reversed-piece cache; long-export
  part lengths; loop repeats' volume; failed copy into a chosen folder; **and in the last commit:** editor
  captions on a speeded clip, sound players rebuilt after Joybot changes the project, the held last frame
  (named by the moment, the exact frame).

Tests, all run by `bash <script>` from the repo root: `tools/jvm-harness/run-workloss.sh` (45),
`run-diskstamp.sh` (20), `run-sidecars.sh` (32), `run-consolidate.sh` (23), `run-undo.sh` (83),
`run-vislab-home.sh` (14), `tools/joyfolder-tests/run.sh` (25); `:joybrush-android:testDebugUnitTest` (566).

## Not done: phone

Only this was seen on the Note 9 (11-11, 03:28 build): installs, the lobby opens, Studio opens a project,
Home saves it with its backup, no crash. **Everything else is unproven on a phone.** The checks owed are
listed at the bottom of `work_loss_20261110.md`. Driving notes: the screen is 1080 x 2220 for taps (not
1440); the editor needs two quick Back presses to exit; `am start` on the launcher activity stacks a new
lobby on top instead of returning to the task (use recents: `KEYCODE_APP_SWITCH` twice); `run-as
com.fadcam.beta` reads the project files directly (`files/faditor/projects/<id>/`); the phone's shell
arithmetic is 32-bit (compute numbers on the PC). The other session installs its own builds: claim the
phone lock, and say on the BOARD when you install over theirs.

## Not done: next, in this order

1. **Done 11-12 for the export (Claude Opus 5.5):** `model/TimeMap`, D5 + D6 + E2, Note 9 A/B; see the
   code-review report §A "The fix" and STATE §2. Left of this item: the editor side (D7, D9, the
   playhead during a blend), E5, D2, D3, D8, D10, D11. The original note follows.
   **The timeline clock (the owner's "studio clock sync", job STU-5).** Section A of the code-review report
   has every place that converts timeline time to source time and the worked disagreements. The root: a
   transition shortens the export but not the editor's timeline; overlays were corrected for it, music,
   voiceover, fades and volume curves were not, and the export pads the end with black. Recommended: a
   small pure class `model/TimeMap` with tests that pin today's numbers first; then D6 (fades and volume
   curves after a transition), then D5 + E2 together (audio lanes and the black tail). **These change what
   exports contain: compare preview and export on a phone before landing** (rule 5 in AGENT_START).
2. **The scout rows still marked "scout" in the code-review report**: verify each in the code before acting.
   The likeliest wins: E17 (a full-frame picture copy per frame for animated picture layers, probably why
   long exports slow down), E8 to E10 (cancel and stuck states), E5 (volume curve during a transition), R4
   (all audio players rebuilt on every undo), E14, E15.
3. **Areas no one has reviewed yet:** the rest of the editor screen (about 45,000 lines), the timeline view's
   drawing and touch code, compositor and player, Joybot and the AI tools (31,000 lines), transcription,
   sprites, puppets, Avatar, VisLab beyond saving, Library and lobby, recording. Scouts on Sonnet worked
   well with a strict brief ("real defects only, quote the lines, say CONFIRMED or LIKELY"); their briefs
   are in this session's transcript and the format of their reports is in the code-review file.
4. **Work-loss rows still open** in `work_loss_20261110.md`: recording write errors and no free-space check
   (F5, needs its own job and phone tests), avatars and fonts into the Joy Creator folder (F6), VisLab after
   a change of folder within one run (F7), JoyBrush wet paint owed at save time and the lost-squares cause
   (J6: the other session's lane), a screen to bring back a kept copy (`backups/replaced-*.json`).

## Traps I hit

- The usage limit is shared with subagents: three Fable scouts at once ended the session. Use
  `model: "sonnet"` for scouts and no more than three at a time; resume a stopped one with SendMessage.
- Never write Java through a shell heredoc (backslashes and quotes are mangled): use the Write/Edit tools,
  or a Python patch script written with the Write tool. Files are CRLF: patch scripts must keep that.
- `tools/jvm-harness/typecheck.sh` is not reliable in a fresh worktree (it cannot see the Kotlin modules):
  use Gradle (`:app:compileDefaultDebugJavaWithJavac`, about 30 seconds once warm).
- A harness test that touches `ProjectStorage.saveAsync` must end with `System.exit`.
- A new static method `ProjectStorage` calls on `StudioMirror` must also go in
  `tools/jvm-harness/stubs-clipwarp/com/fadcam/joyfolder/studio/StudioMirror.java`.
- `joy-creator` moves every few minutes (TextLab and oils sessions): fetch and merge before every push,
  never force. My merges so far were clean except one line in `FaditorEditorActivity` (`swapInProject`
  gained a second argument).
- The main folder `C:\+Projects\JoyCreator` has not been fast-forwarded by me: the owner's watcher there
  builds an older tree until someone does (`git -C "C:/+Projects/JoyCreator" merge --ff-only
  origin/joy-creator`, safe only if its dirty files do not overlap).
