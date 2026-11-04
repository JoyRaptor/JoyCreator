# JB-LIVE-SAVE — outgoing save / reopen ordering

Owner resumed the existing unfinished JB5.20d goal. This is a bounded save/recovery
prerequisite, not captured-stroke activation. Base a15828b95, isolated
C:/Temp/jb-live-goal-20261104, branch lead/live-goal-20261104.

Current E1 source permits a startup read before the outgoing GL snapshot queues
its write. A shared disk executor cannot order a write that does not exist yet.
Register a per-working-path handoff before the onPause save request (including a
request queued behind another operation), and before each Working snapshot.
Startup awaits completion asynchronously without occupying the disk worker.
Failures stop automatic reopen/autosave rather than silently loading a stale file.
Timeout and late callbacks must resolve once; cancellation releases Activity waiters.

Acceptance: deterministic delayed snapshot/write/reopen preserves old+new edits;
pending-onPause behind an existing save, coalescing, multiple saves and unrelated
files; snapshot timeout/failure and disk failure; destroyed waiter cleanup. Test real
Activity wiring separately from the pure coordinator. Then native compilation,
appropriate regressions and exact APK/device evidence if resources/readiness permit.
Preserve prior archives/recovery, ordinary paint/media, papers and UI. Keep all
captured-source/save activation guards. No feature completion from compilation.

Root owns Activity+barrier integration. Luna audits current capture gaps and save
ordering; no parallel hot-file edits. Main folder and original worktrees untouched.
