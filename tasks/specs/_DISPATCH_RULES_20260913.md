# Rules for every sheet dispatched 2026-09-13

**Read this first, then your own sheet. Then `_RULES_READ_FIRST.md` and `tasks/GIT_PRACTICE.md`.**

These are not style preferences. Each one is a failure this repo has actually had, most of them
within the last day.

---

## 1. `BUILD SUCCESSFUL` is not evidence. The dex is.

Two agents share one build directory. A build from the other lane can delete `app/build/intermediates/javac`
out from under yours, after which Gradle cheerfully reports:

```
BUILD SUCCESSFUL in 1s
```

…with your class nowhere on disk and an APK **older than your edit** that does not contain it.
Nothing in the output says so. On 2026-09-13 this happened twice, and the second time the APK was
ninety minutes stale.

**So verify every build through the packaged dex:**

```bash
bash tools/build-verify.sh <aSymbolYouJustWrote>
```

It greps the dex and fails loudly if your symbol is absent, with the recovery commands. If you
report "compiles" without having run it, your report is worthless and will be checked.

## 2. "Unable to delete directory" is CONTENTION, not your code

Another build is running in this tree. **Wait and retry.** Do not run `clean` — the build directory
is not yours alone, and cleaning it costs the other lane an hour.

## 3. Never install to the Note 20

The sandbox phone is the test device (`tools/devices.local.sh` names both; the file is gitignored).
The Note 20 holds JoyRaptor's real 48-minute project. If only the Note 20 is attached, **say so and
stop.** `tools/build-install.sh` refuses on purpose.

**The sandbox phone is shared too.** An install from the other lane kills the app you have open, and
the log then says `app died, no saved state` — which reads exactly like a crash and is not one.
Check for a `PackageUpdatedTask` line immediately after before you go hunting a stack trace that
does not exist.

## 4. A check nobody has seen fail is not a check

If your sheet asks you to add a test, a lint rule or a probe: **break the thing it guards, watch it
go red, then restore it** — and put that in your report. A green suite that cannot go red proves
nothing. `persist_lint.py` exists because a working stereo-pan control turned out never to have
been saved at all, and it carries its own negative control for the same reason.

## 5. Refusing is a valid outcome. Clamping is usually not.

Several of these sheets ask you to REFUSE an operation in a named case. Do not "improve" that into
a clamp, a fallback or an approximation to make a feature look complete. A refused gesture stops
and the user retries; a clamped one silently stores a shape they did not draw. If you believe a
refusal is wrong, say so in your report and leave it in.

## 6. Never resolve a merge conflict

If `git pull` conflicts or `git status` shows unmerged paths: **STOP, touch nothing, say so at the
top of your report.** Past agent merges caused silent data loss — one rewrote `strings.xml` as
UTF-16 and it still built; another dropped a whole commit off the branch. Minutes to leave alone, a
day to unpick.

## 7. Preview and export are ONE truth

This is the top-severity bug class in this codebase. Shared behaviour lives in ONE method or ONE
shader string that both surfaces call — never transcribed into two. If your change means the
preview and the export each need the same maths, you have found a helper that wants extracting, not
two places to edit.

## 8. Stay in your lane

Your sheet names the files you own. `tasks/LANES.md` is the live board. Another agent is working in
this repo right now. If your work needs a file you do not own, **say so and stop** rather than
editing it.

## 9. Report honestly, per item

Say **compile-verified** vs **device-verified**, per item, in those words. If you could not test
something, say which and why. A checkbox is a wish, a commit is a fact, a screenshot is proof.
