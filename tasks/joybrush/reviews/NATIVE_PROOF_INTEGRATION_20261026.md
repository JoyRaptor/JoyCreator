# Combined native diagnostics — isolated integration candidate

Base f6287b921. Preserves current Select/A10 source; selectively applies code-only
frame717f6c326, watercolor dispatche670257df and frame probe7b23ce906 changes.
Root adds a separate `nativeFrameDuplicateProbe` View/BenchRunner entry and a
non-debug refusal regression. Source-only until exact compile/test/device gates pass.

The ordinary watercolor case stays `recordedWatercolourOrdinaryProbe`, using its
own derived/frozen fixture and disposable engine. The frame case uses its own fresh
engine and fixed ordinary Ink recipe. Each finally reclaims its disposable engine;
neither receives the View's artwork engine or writes owner saved drawings.
Old replay overload, cache diagnostic and other recorded cases are preserved.

## Verification plan

- Backend: existing exact frame26 on717 is historical subset proof, not proof of
  this combined candidate. Run frame budget/entry/copy/initialization suites plus
  FrameDuplicatePixels and WatercolourOrdinaryFixture on this exact source.
- App: compile and run meaningful dispatch compatibility/debug-guard tests; retain
  current Select/A10 regression coverage when integrating later UX/presenter changes.
- Build exact canonical APK only after tests pass and fresh3200 MiB admission.
- Authorized phone checks: fresh sandbox/Home/lock/APK identity; hash complete
  Working/current/backup/archive path-set before/after install and each probe.
- Parse complete case-scoped chunk streams with byte-order reassembly and fresh
  launch fence. Record all nonvacuity controls, row counts, frozen fixture hash and
  GL release result. Incomplete logs or unresolved execution retain the claim.

Frame first oracle covers actual independent duplicate, physical-base clipping,
target-only deletion and one-step Undo/redo on odd negative board edges. Low-budget
device refusal, injected publication failure, archive/save/reload, watercolor and
captured/joint paths remain separate gates. Ordinary watercolor does not establish
selected cache, original grainy look, physical fade, View save/recovery or speed.

UX currently owns the next focused app slot. No competing heavy/phone job starts
from this candidate until explicit resource/slot admission.

## Adversarial fixture followup

Adopted probe11ca8c132 after source review: fixtureVersion2 uses patterned negative,
edge and wholly outside base tiles; demands actual zero-slice base membership,
shared-plane immutability, exact footprints/document, source handle-history coverage
and no visible/base aliasing. Captures/normalizes/restores pack/PBO and framebuffer
state; cleanup failure cannot pass. These controls are unrun on this combined source.
The report's remaining-gates list excludes dispatch/device execution itself because
it describes gates still owed AFTER a successful diagnostic; no native run is yet claimed.
