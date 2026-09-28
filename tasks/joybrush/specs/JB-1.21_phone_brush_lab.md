# JB-1.21 — Phone Brush Lab: edit a brush on the PC, feel it on the phone a second later

| | |
|---|---|
| **Tier** | T2 |
| **Status** | see ROADMAP.md |
| **Depends on** | JB-1.05b (brush files drive the view) |
| **Owner area** | NEW `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/lab/BrushHotReload.kt`, EDIT `joybrush-android/.../JoyBrushActivity.kt` (start/stop it), NEW `tools/brushlab_push.sh` |
| **Estimated size** | ~150 lines |

## Goal
The phone is the judge of feel (owner's ruling). The owner edits a `brush.json` on his PC, saves, and
within about a second the brush on the phone is the new version — no rebuild, no reinstall.

## Decisions
1. **On the phone:** the lab folder is `getExternalFilesDir("joybrush/lab")`. `BrushHotReload` polls
   it every **500 ms** (a Handler on the main thread; stop in onPause) for `*/brush.json` files whose
   lastModified changed. Each changed file is decoded + validated (`BrushJson`, `BrushValidate`);
   valid → callback `onBrush(preset)`, invalid → callback `onError(file, problems)`.
2. **In the activity:** only in debuggable builds (`ApplicationInfo.FLAG_DEBUGGABLE`). A changed valid
   brush replaces the current preset immediately and a toast says "Reloaded <name>". Errors show a
   toast with the first problem.
3. **On the PC:** `tools/brushlab_push.sh <path-to-brush-folder>` watches that folder (poll `stat`
   every 0.5 s — no extra tools) and on change runs
   `adb push <folder> /sdcard/Android/data/<applicationId>/files/joybrush/lab/` using the device picked
   by `tools/phone.sh`'s rules (read that script; reuse its serial selection, never the Note 20
   unless `PHONE=` is set). Print one line per push.

## Verification
Sandbox phone: push a copy of `ink/brush.json`, change `size.base` from 6 to 30 on the PC, save → the
next stroke is fat within ~1 s. Break the JSON → a toast shows the problem and the old brush stays.

## Do not
No network server on the phone. No change to the engine or view.

## Definition of done
Device check described · commit `JB-1.21: phone brush lab` · ROADMAP row → 🟧 Built.

## Questions
