# Watercolour completion audit — October24

Owner request: check Claude's continuation, finish remaining work, update STATE and
the roadmap, and clean up this lane's temporary files only when complete.

Remote main at audit start: a94933ad7. Claude's clean integration checkout is
C:/Temp/lead-wcl, branch lead/wc-live-land, HEAD1681bfdeb. It adopted the L1
diagnostics, wash-frame/codec and GPU/prewarm candidates and corrected the new
RecordedCel test's generic type. These commits are not on remote main. The L3
per-brush candidate e9b6ff9b6 was not included.

Lead checkout XML reports core2559 tests (4 optional skips), androidkit1064 and
native317, zero failures/errors; APK timestamp04:56. Specific new archive,
transform and codec suites pass. Those reports do not establish cold latency,
phone edits/reopen, look parity or the16.7ms target. L1 baseline is complete.

| Step | Actual status | Remaining work/proof |
|---|---|---|
| L1 | Measured on Note9 | Preserve20 cold/warm rows and scope labels |
| L2 | Implemented/tested candidate | Five cold comparisons; full one-second line fade |
| L3 | Separate UI candidate | Integrate/test current-brush setting and immediate line drawing |
| L4 | Core/codec tested, GPU mapping implemented | Production finish-only recolor; phone edit/one-Undo checks |
| L5 | Frozen-brush/frame archive test passes | Phone pixel hash, force-stop/reopen, context loss and first edit |
| L6 | Original POC approved | GPU comparison and owner approval of the phone engine |
| L7 | Not accepted | Optimize; repeat L1 (warm p95~36–73ms, target16.7ms) |

At audit start NativeLines.prepareRecordedLine deposited dabs again on recolor;
the isolated renderer cache had no production caller. The continuation connects
it through the existing CanvasSelectionHost → beginLineLift bridge: selecting
one line primes coverage, and a tint-only edit reuses it for finishing. Other
background lines cannot evict it. Geometry/brush changes invalidate a complete
recipe key; tests cover smoothing, scale, seed and sample mutation. Coverage is
bounded to32 full-resolution tiles plus the empty tile (~16.5MiB at256px),
included in texture ownership, working-byte accounting and edit-budget reserve.
Selection preflights its unit/composition allocations before priming coverage.
Cache hits reuse the CPU replay and tile keys too. Cached finishing bypasses
initialization of the ordinary live pass. Internal deposition/finish-only counters
are available for the forthcoming device proof. A separate cache pass still
duplicates fixed programs/images; the stated bound describes tile memory only.
Large lines and multi-selection use ordinary exact replay; finish-only behavior
is currently limited to a single selected line within this bound. GPU/context
and pixel-equality verification remain owed. Only wash travels;
paper/grain/gravity remain document-fixed.

## Continuation

- [x] Audit branches/reports and update STATE/roadmap.
- [x] Carry existing code plus Claude's correction onto current main in isolation.
- [ ] Complete production recolor caching and validate current candidates.
- [ ] Phone edits/save/reopen/context, cold/warm checks, appearance and speed.
- [ ] Owner look/feel approval; agents cannot sign off for the owner.
- [ ] After acceptance, retain durable evidence and delete only obsolete lane scratch.

Continuation branch vector/watercolour-finish-20261024 starts at a94933ad7.
Primary and Claude checkouts are preserved. No cleanup under the completion
condition yet: unfinished source and evidence are still needed.

## October24 continuation checks

- Restored source HEAD8c019c2bb; L3 is included. New cache/key candidate
  b5e3b167d is a separate LEAD PATCH, not merged or test-accepted.
- `git diff --check` and `node --check joybrush/tools/watercolour_check.js` pass.
- New cache/key tests have not run. Do not transfer Claude's earlier green
  counts to these new changes. The shared Gradle slot is occupied and free
  RAM is around0.5GB versus the2.2GB lightweight admission threshold. The Lead
  Coordinator has the slot/resource request. No processes were killed and no
  phone action/install was attempted during this audit.
- Explicit guarded standalone core/backend test attempt was deferred because
  the shared build slot was occupied/reserved. No Gradle run began for this
  candidate; the check did not pass or fail any test.
- Original L1 review, design note and20-row JSON are retained in this branch.
