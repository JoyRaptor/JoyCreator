# Drill: schema downgrade guard (ship-blocker #3)

Ship-blocker #3 in `road_map.md` is *"Schema downgrade-guard drill — only forward migration
exercised on a real project."* This is the recipe, plus what reviewing the guard already found.

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
