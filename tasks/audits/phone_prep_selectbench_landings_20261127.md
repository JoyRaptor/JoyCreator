# Phone prep: SelectBench and the create-tree landings (hover-1 lane, 11-27; Note 9 was offline, nothing run)

Note 9 over USB showed `offline` at 9:5x; one `Reconnect-Note9.ps1 -Quiet` did not bring it back (USB, not Wi-Fi: unplug and replug, accept the debugging prompt). No retry loop.

## Build to install
Cloud run 46019274493 on `6b9b28e2f` (today's joy-creator, includes 51fdde46b): `tools/cloud-build.ps1 fetch 46019274493`, then `phone.ps1 install build\cloud\joycreator-defaultDebug-6b9b28e2f\app-default-arm64-v8a-debug.apk` under the lock. (Fetch may still be unfinished: `last-run`/`ls build/cloud`.)

## SelectBench
- The bench starts with `adb shell am start -n com.fadcam.beta/com.fadcam.joybrush.JoyBrushBenchActivity --es jb_bench select` (never monkey; through `phone.ps1 start` under the lock). It draws five ink lines with a synthetic pen, then runs steps `setup draw chooseSelect tapPick cycle tapEmptyEnds lasso dragMove undoMove rotate pinchAnywhere swapList copyPaste cutUndoRedo doneEnds group lock tipDots nudge fingerPansWithBrush end`, one JSON line each on logcat (`{"selectBench":<step>,"pass":...}`), ending with a `summary` line (`passed`, `failed`). The new Cut, Undo, Redo, Done steps are `cutUndoRedo` and `doneEnds`.
- The packaged runner is `tasks/joybrush/ui-scripts/select_bench.sh <out-dir> [lane-name]`, but it sources the owner's main-folder `tools/devices.local.sh` (for `SANDBOX_SERIAL`) and wants a build receipt for an exact-source APK: from this worktree it will not run as is. Quick route: start the bench by hand as above and read `phone.ps1 adb logcat -d -s <BenchRunner.TAG>` for the JSON lines (looking is allowed without the lock); screenshots for `selectBenchShot` events need the host script, so they would be missing.
- Report: pass/fail per step, the summary line, battery temperature.

## Create-tree landings (Joybot create bubbles)
1. Lobby: the purple Joybot orb at the top right opens the chat (screenshot first; the lobby has no text nodes).
2. In the chat, the create bubbles appear (or say "I want to make something"). Walk each: Drawing or painting (16:9), Drawing in perspective (2-point, 4:3), Hand animation (9:16, 24 fps), Tile (512, and typed `300x200`), Sprite make one (4x4 at 64), Sprite edit (Show all my sheets), Video, Find.
3. Expected: JoyBrush opens a NEW drawing with the board made at that shape (tile 300x200 = a 3:2 board since fix ae62ac0c1, not square), the guide on for perspective, one Undo step; the others open their room (Library, Studio, SpriteLab).
4. Screenshot each landing; note any that open the wrong room or a plain square board.
Host proof already: `CreateLandingE2E`, 91 checks, cloud run 46008265783.
