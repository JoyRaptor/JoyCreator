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

Notes from the T2 build (2026-09-29). `:androidkit:test` is **104 tests, 0 failures**; the 12 new
ones are in `androidkit/src/test/.../lab/BrushHotReloadTest.kt`.

**The spec is short three of the seven things `specs/README.md` §3 asks for**, and none of the three
was a design decision, so the build went ahead with each one named here rather than guessed at:

1. **No "contract verbatim".** Decision 1 names `BrushJson`, `BrushValidate`, `onBrush(preset)` and
   `onError(file, problems)` by name only. They were read in the landed code and used exactly as they
   are: `BrushJson.decode(json): BrushPreset` (throws `BrushException`), `BrushValidate.validate(p):
   List<String>`, and `BrushLibrary.decode` as the model for calling them in that order.
   `BrushJson.decodeChecked` exists and does both, but it drops `decode`'s version-word check, so
   the lab does what `BrushLibrary` does: `decode`, then `validate`. That is what the spec's
   wording says, and it is also the only choice that keeps a version-1 `engine: "fill"` file out.
2. **No tests and no test command.** The only Verification in the spec is the sandbox-phone check,
   which nobody but the owner can run. The tests were written from the spec's decisions rather than
   from a list it gave, and `:androidkit:compileKotlin :androidkit:test` is the command that runs
   them.
3. **No stop rule.** Taken from `README.md` §3 item 7, which says the same thing.

**Decisions the spec left open, and the reading I built (PROVISIONAL — Claude to confirm):**

4. **A brush already sitting in the lab when the watcher starts IS loaded.** Decision 1 says the
   watcher reports files "whose lastModified changed", and a file present before the first poll has
   not changed by anything the watcher saw. I made first sight a change, because the Verification
   step is *push a copy of `ink/brush.json`, then edit it* and the other reading makes the push half
   of that silent — and a lab where pushing a new brush folder does nothing until you then edit it
   is a lab that looks broken. **If the Lead wants the strict reading, it is one line**: seed `seen`
   with every file's stamp on the first scan. Pinned by `aBrushInTheLabIsHandedOverAsTheDecodedPreset`.
5. **Folders are walked in name order**, so two brushes changed in the same tick reload the same way
   every time. Not in the spec; the test asserts the order, so it is a promise now.
6. **The error toast's wording is mine**: `Lab brush ink — <first problem>`. Decision 2 fixes
   "Reloaded <name>" and says only "a toast with the first problem" for the other half.
7. **The stamp is read before the bytes.** Reading it after would record the time of a file already
   rewritten again, and the newer version would then look unchanged for ever. Costs nothing and is
   the difference between "the edit arrived" and "the edit silently did not".
8. **A file that cannot be READ at all is reported and then marked as seen**, so it says so once
   rather than twice a second for as long as the lab is open. Marking it before the read is also why
   a save landing mid-scan is caught by the next tick rather than lost.
9. **`tools/brushlab_push.sh` watches `brush.json`, not the folder's own mtime.** A directory's
   mtime moves on create and delete and not on an in-place rewrite, which is the only edit this tool
   exists for. It also fingerprints `stat -c '%Y %s'`: `%Y` is whole seconds, so a save inside the
   same second as the last poll can be missed on time alone. Both are derived, not stylistic.

**For the Lead, on `JoyBrushActivity.kt` — a divergence I did NOT fix, because the fix is the pill's:**

10. **The brush pill keeps lying after a hot reload.** The lab sets `canvas.preset` and toasts
    "Reloaded Ink", but the pill's label is `brushLabel()`, which reads `brushes[brushIndex]` — a
    list built once from `BrushLibrary.builtIn()`. So after reloading a file called `ink` whose
    `name` is "Big Ink", the person draws with Big Ink and the pill still says "Brush: Ink", and the
    next tap on the pill cycles back to the packaged list and throws the reloaded brush away. Making
    the label follow means `brushLabel()`, `cycleBrush()` and the `brushes` field — JB-1.05b's
    region, named in the brief as *not* mine, so I stopped rather than touch it. **Which is it: a
    lab override the pill shows and `cycleBrush()` steps over, or a reloaded brush that the pill
    simply does not describe?**
11. **`BrushHotReload` is constructed with the lab `File`, not a `Context`.** Decision 1 says the
    folder *is* `getExternalFilesDir("joybrush/lab")`; the Activity resolves it and passes it, and
    the path string lives on `BrushHotReload.LAB_DIR` so the screen and the watcher cannot disagree
    about it. Doing the lookup inside the class would have made the whole class untestable on the
    JVM, and this is the class that decides whether a changed brush arrives.
