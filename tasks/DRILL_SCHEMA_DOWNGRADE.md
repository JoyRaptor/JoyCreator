# Drill: schema downgrade guard (ship-blocker #3)

Ship-blocker #3 in `road_map.md` is *"Schema downgrade-guard drill — only forward migration
exercised on a real project."* This is the recipe, plus what reviewing the guard already found.

## RUN 2026-07-26 03:00–03:12, Note 9 (SM-N960U) — 7/7 PASS, one real gap found

Run on a throwaway `cp -r` clone of a real sandbox project (4 clips / 3 audio / 5 text /
2 sprites / 1 overlay clip) hand-stamped `"schemaVersion": 99`, with its `.bak` deleted so
nothing could mask the result. Pushed sha256
`e8b0fdae06099984c6811c1813fd0d61860f156e3a81c7dffb0637adf36db751`.

| step | result |
|---|---|
| 3 read-only dialog on open | PASS — fires immediately, wording correct ("watch and export it, but any edits you make will NOT be saved") |
| 4 edit is visible in-session, save refused | PASS — caption size slider moved to 20% in-session; `project.json` sha256 UNCHANGED, no `.bak` created |
| 5 close/reopen: edit gone, dialog again, file untouched | PASS — dialog re-fires; the slider reads back **6%**, not the 20% set in the previous session; sha256 still identical |
| 6 export works | PASS — 480p/Low export reached 100% ("saved to the Records Tab"); sha256 still identical afterwards |
| 7 restore | N/A — a throwaway clone, deleted. All 10 real sandbox projects verified sha256-identical to their safety copies afterwards |
| forward case (older stamp opens + saves) | PASS — a v7-stamped fixture opened and re-saved, restamped to 11 |

**GAP FOUND — the sidecar was not covered.** `project.json` was correctly left
byte-identical, but a **75,933-byte `undo_history.json` was written into the directory**,
because `save()`/`saveAsync()` each carry the guard and the undo-history write path does
not (it takes only a `projectId`, so it cannot even see the flag). Fixed by bailing out at
the top of `saveProjectNow`, and by refusing to RESTORE a read-only project's undo history
on open — an undo stack whose snapshots can never be saved is incoherent, and it would
also resurrect any sidecar left by a build predating the fix.

Re-run after the fix: **no `undo_history.json` at all**, `project.json` still sha256
`e8b0fda…`. Non-regression on a v10 (writable) clone through the identical tap sequence:
`project.json` sha256 changed, `.bak` rotated, `undo_history.json` written (75,930 bytes) —
so the new early-return only affects read-only projects.

This is the third time this guard has passed code review and then failed a real run
(silent refusal, now the sidecar). Re-run the drill whenever a new file is written next to
`project.json`.

## What the guard does (verified by reading, 2026-07-25)

- `ProjectStorage.load` compares the on-disk `schemaVersion` to
  `FaditorProject.SCHEMA_VERSION`. If the file is NEWER it keeps the on-disk stamp and sets
  `loadedFromNewerVersion`, rather than the old behavior of unconditionally re-stamping (which
  would silently re-save a newer project at our version and drop what we couldn't parse).
- Both write entry points refuse: `save()` and `saveAsync()` return early on that flag, as do
  the two other call sites (`ProjectStorage:373`, `:627`).

That much is correct and needs no change.

## What it was missing (FIXED 2026-07-25)

**The refusal was completely silent.** The editor opened normally, every edit appeared to work,
each autosave was refused with only an `FLog.w` line, and the entire session's work disappeared
on close. From the user's side that is indistinguishable from randomly losing their work — a
guard that buys safety with the user's time. `warnIfProjectIsReadOnly` now shows a
non-cancellable dialog on open explaining that the project can be watched and exported but not
edited here.

This is exactly what the drill is FOR: the guard passed every code review because the refusal
logic was right; only exercising it end-to-end reveals that the user is never told.

## The drill (device, ~5 min)

1. Pick a sandbox project and pull its `project.json` to the host **first** — the app rotates
   its own `project.json.bak`, so that file is NOT a pristine snapshot (learned the hard way
   this session).
2. Hand-stamp a future version: set `"schemaVersion": 99` and push it back
   (`adb push` + `run-as com.fadcam.beta cp`).
3. Open the project in the editor. **Expect:** the read-only dialog appears immediately.
4. Make an obvious edit (move a text, change a speed). **Expect:** logcat shows
   `save() refused` / `saveAsync() refused`; no crash; the edit is visible in-session.
5. Close and reopen the project. **Expect:** the edit is GONE and the dialog appears again —
   the file on disk is untouched. Confirm with `run-as ... cat` that `schemaVersion` is still
   99 and the JSON is byte-identical to what was pushed.
6. Export it. **Expect:** export works (read-only blocks saving, not exporting).
7. Restore the pristine file from step 1.

## Also worth asserting while there

- A project stamped *older* (e.g. v7) must open, adopt the running version, and save normally —
  the guard must not catch the ordinary forward-migration path. Every existing sandbox project
  exercises this, so step 3 of the forward case is just "open something old and save".
