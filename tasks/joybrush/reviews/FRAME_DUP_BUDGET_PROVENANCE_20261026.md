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

## Engine source review — candidate 717f6c326

Read-only review of the lead-owned isolated frame-live candidate found two physical-domain
issues, both **reviewed fixed in source** at `717f6c326cc4ba7c8ca50505f16af9645dfd79d3`:

- Whole base textures could disagree with the board-clipped initial visible copy. The lead
  added `materializeWithClippedBases`: initialize each independent base transparent, then copy
  only its tile's intersection with the owning board. Raw trajectories stay intact; lines and
  slabs retain their existing plane clips and operators. For this same-rectangle envelope,
  RegionTransferTiles produces equal source/destination keys and tile-local rectangles, so
  copyTextureRect's identical source/destination coordinates match the visible-copy path.
- Outside-board keys produce no copy slices. Their transparent initialization now checks
  framebuffer completeness and GL error immediately, even when no later blit occurs. The
  exception-safe allocation guard releases a failed output before it reaches publication.

The source retains per-output tracking, shared texture admission and one item/document/pixel
history step. This is inspection evidence, not executed atomicity or pixel-fidelity proof.
All FIVE current helper tests on the lead candidate are unrun. Prior three-test execution
does not cover provenance, clipped-base callbacks or the new engine consumer. GPU edge/negative
coordinate pixels, injected allocation/publication rollback, undo/redo, save/reopen and device
behavior remain open. Existing rollback assumes restoration calls succeed; empty destination
maps may remain after failure. Captured/RecordedScene duplication remains explicitly refused.

No source edits, builds, queued jobs or phone actions were performed during this review.
Root owns current STATE refresh; no user-facing activation or LIVE promotion is proposed.
