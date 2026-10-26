# FRAME-DUP borrowed texture provenance — October 26, 2026

Assigned by JoyBrush Lead Coordinator to the tweens chat. Base: `7f2324833`, isolated
`C:/Temp/jb-tween-direct-20261026`, branch `codex/tween-direct-20261026`.
Exclusive source: PreparedLinePlaneCopy.kt and PreparedLinePlaneCopyTest.kt. No engine,
BoardTransactionMemory, View, Activity or schema source is changed here.

## Contract

`borrowedSourceTextures: List<Int>` is an immutable captured snapshot in the same order
as materialize's base/slab copy callbacks. It contains one entry for each new texture to
allocate; repeated source handles remain repeated. Its size equals textureCount.

For shared board-transaction admission the caller must reserve:

1. Each new visible raster and new base/slab copy separately.
2. The UNION of retained original handles across visible raster and borrowed base/slabs,
   deduplicating originals by handle rather than summing independent counts.

The list grants no graphics lease. Caller retains the source handles until copying completes,
tracks every allocation before initialization or uses the exception-safe allocator, and owns
publication/rollback/history. The helper neither reserves memory nor deletes originals.

## Source checks and limits

One new real-helper check aliases handle700 across two base slots and a slab. It checks all
five slots/order/duplicates, JVM-cast mutation refusal, unchanged captured handles after
NativeLines changes, and materialize requesting those exact original handles. The callback
is a non-GPU observer; placeholder destination handles are distinct.

**SOURCE ONLY:** no build/test run was admitted for this followup. Existing three-test
e5fa12086 proof covers the older helper, not this change. The updated helper class now has
four test methods awaiting execution. Earlier clock/export six/thirty proof is unchanged.

Lead owns live integration in `C:/Temp/jb-frame-live-integration-20261026`, base315a1decf
plus adopted marker64d9579ef. First live envelope is ordinary-only, same board/rect, fresh
exclusive destination; published/local RecordedScene and captured streams remain blocked,
with an explicit refusal rather than flattening. Full runtime/GPU/undo/save/phone proof remains.

Next gate: source review, one lead-admitted exact-source helper run, then root engine integration.
