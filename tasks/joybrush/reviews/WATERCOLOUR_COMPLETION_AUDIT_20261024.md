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

Actual NativeLines replay uses GlPaintEngine.prepareRecordedLine, which still
deposits dabs again on recolor. The unused GlWatercolourLineRenderer's cache is
not production completion. The Select bridge reaches beginLineLift through
CanvasSelectionHost and can prime a bounded selected-line cache without a new
Select API. Only wash travels; paper/grain/gravity remain document-fixed.

## Continuation

- [x] Audit branches/reports and update STATE/roadmap.
- [ ] Carry existing code plus Claude's correction onto current main in isolation.
- [ ] Complete production recolor caching and validate current candidates.
- [ ] Phone edits/save/reopen/context, cold/warm checks, appearance and speed.
- [ ] Owner look/feel approval; agents cannot sign off for the owner.
- [ ] After acceptance, retain durable evidence and delete only obsolete lane scratch.

Continuation branch vector/watercolour-finish-20261024 starts at a94933ad7.
Primary and Claude checkouts are preserved. No cleanup under the completion
condition yet: unfinished source and evidence are still needed.
