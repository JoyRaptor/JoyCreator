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

## Verified checkpoint f84dec80f — November4

The leaving handoff spans the entire queued sequence, including earlier Working
saves, interleaved Open/Copy and coalesced idle requests. Completing the first
Working request is insufficient and was corrected before this checkpoint.

- Focused final25cases passed; full Android555cases/71suites, zero failures/errors/
  skips. [Independent XML/hash receipt](LIVE_SAVE_HOST_20261104.json).
- APK succeeded5m20s:170203732bytes,
  SHA e89d030e05863d4d5b3e38a7035daec9768b91922eff95c94d6d878a05dacd20.
  Exact artifact: C:/Temp/jb-lead-ops/live-save-apk-20261104/artifact.json.
- Fresh SM-N960U/API29 Home install verified; all280saved files unchanged.
  Private receipt: C:/Temp/jb-lead-ops/live-save-install-20261104/receipt.json.
- [Note9 archive-handoff PASS](LIVE_SAVE_DEVICE_20261104.json): two full-tile RGBA8
  comparisons, three edit markers survive archive reopen, failure blocks stale
  read, late completion/cancel checks, scratch cleaned. All280saved files unchanged;
  phone returned Home; both claims released.

The delay is simulated and scratch-only: actual Activity leave/recreation/EGL,
process death and complete captured-source save/recovery remain unproved. Typed
OOM is caught at the writer boundary; forced OOM proof is next. No captured-source
guards were removed; the broader goal remains unfinished. Claude lead owns the
noncentral backlog per the November4 BOARD split.
