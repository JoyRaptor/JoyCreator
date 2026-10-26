# Ordered optical contributions — proposal, no runtime activation

Reviewed against watercolor e04944c32 and cross-checked against preserved stale
primary fb8042841. The latter is NOT current canonical main: fresh origin is
f79efc378, already integrated into coordinator f6b8242f5 (module-equivalent to
tested52e559d04). RecordedCel, RecordedTilePlan, RecordedScene, NativeLines and
CelComposer match the compared references. GlPaintEngine differs through the
reviewed watercolor/cache and Copy work; do not treat the stale primary's lack
of hooks as a later upstream removal or restore/remove code from that comparison.
This proposal does not establish runtime cache activation or diagnostic validity.

## Existing seams to reuse

- CelComposer.Slab already carries original seq and operator. Whole-line bake
  replaces a line with its own source pixels at that seq, not a top/base slab.
- RecordedCel and NativeLines prohibit two lines, or a line plus slab, at one seq.
  RecordedTilePlan exposes only Line and Pixel. Splitting into several ordinary
  records with identical seq therefore violates existing invariants.
- RecordedScene.Renderer.line produces canonical source units; copyForBake
  clones source pixels after full pooling, opacity once, with the same operator.
  Renderer.look consumes ordered Source(seq,texture,op). GlPaintEngine.tileItems
  similarly builds OrderedRecordedUnit; composeRecordedTile requires strict seq
  order and uses float ping/pong before final quantization. Keep that outer seam.
- RecordedScene.stageJoint and its bound publication/history cursor model are
  the transaction seam to extend later. No direct NativeLines mutation followed
  by a second history update is acceptable.
- CapturedStrokeRules.snapshot is useful for defensive recipe copies.
  SourcePlacement is a useful checked integer translation value. CaptureDomain
  owns rectangular captured pixels; it cannot represent nearest-parameter
  optical sections. CapturedPhysicalSource and CapturedPaintDomains retain
  their separate guards and region-capture semantics.

## Minimal conceptual API (not production declarations)

```kotlin
SourceKey(documentId, sourceId, contentDigest, replayVersion, ownershipVersion)
OpticalSource(key, originalRecipe, frozenPreset, canonicalGeometry, paperIdentity)
Section(editId, sourceKey, interval, placement: SourcePlacement, finishVariant)
PixelPart(partId, ownedTiles, pixelFormat, revision)
ContributionEvent(eventId, layerId, celId, seq, originalOperator, children)
Child = SectionPart(section) | PixelPartRef(part)
prepareContributionChange(expectedRoot, replacements, limits): StagedChange
assembleEventTile(event, key): BorrowedOrFreshSourceTile
```

Each original chronological event owns one seq. Children have stable logical
identities inside that event, not additional seq values. Section selection uses
full (layer,cel,event,editId) addresses. Source identity is immutable and scoped to
the document; digest/version validation includes the complete replay inputs,
including paper dependencies. It is not a mutable record id or just seed+brush.
Copy allocates a new event seq and fresh editable child ids; it may share only
validated immutable source data. Recolour creates a child finish variant and
never modifies the original source or siblings.

First finish the full original optical source, then binary-classify its pixels.
Assemble the event's child pixels on transparent scratch before passing exactly
one source texture and original operator to Renderer.look. At the unchanged grid,
disjoint children copy original premultiplied RGBA without another brush opacity
or quantization. This is a future nested event type, not permission to weaken
the existing flat model's seq uniqueness. New model/history/archive support is
required before activation; existing source callbacks cannot provide sections
merely by accepting another StrokeRecord.

Source-to-document placement and the source's original StrokeWashFrame remain
distinct. Initial integer placement carries the already-finished source, including
its texture and pooling. Paper clipping and board ownership cannot be inferred
from today's geometry when replaying historical sources.

## Overlap choices for the coordinator

| Policy | Behavior and tradeoff |
|---|---|
| Refuse overlapping child support | Exact and simplest first acceptance. Refuse before publication, including smudge output that would overlap retained support. Conservative support bounds may refuse usable edits. |
| Assemble children in stable internal order; apply event operator once | Allows moved overlap and touched smudge pixels. Normal source-over within event intentionally increases local alpha; per-child recolour is defined. Original multiply/erase is applied once externally. Requires a documented stable child order, sufficient precision and native oracle. Recommended extension after disjoint proof. |
| Apply each child operator separately | Familiar independent glazing, but changes the original event contract at overlap and can apply multiply/erase repeatedly. Needs an explicit user-facing decision and separate proof; not recommended as an implicit fallback. |

No max-alpha/union shortcut is proposed: it has no unambiguous pigment choice for
different-colour children. Initial recommendation is disjoint-only admission;
stable internal assembly is the concrete follow-up needed for useful overlapping
smudge. Moving a section does not silently move its event to the chronological top.

## Smudge admission and atomic publication

Gather every optical owner intersecting the full contact/pickup support, not only
the selected line or nearest branch. Prepare retained domains, touched-source
pixels, their output footprint and old/new look tiles privately. Paint-only
pickup omits retained projection. Publish one expected-root change with one
Undo cursor update; rollback releases only newly owned tiles in their epoch.
Undo restores both source domains and pixels. Save/reopen must reproduce the
event, child order, operator, source references and exact touched pixel format.

A single carried smudge colour can mix several source events and intervening
paint. Its output has no unique historical seq. Initial admission must refuse
multi-event/unsupported mixed pickup before replacing the existing path; assigning
the mixture to the earliest/latest event would alter chronology. Supporting it
requires a separately specified contribution-aware transport rule. This is a
real scope limit, not a claim that ordinary smudge integration is solved.

Refuse unsupported captured sources, masks, media/float adapters, unknown source
versions/paper, noninteger/general transforms, reshape/rebrush/width edits,
invalid intervals, overlap under the first policy, and exceeded budgets. Budget
both canonical-source replay and retained/touched/scratch/Undo tiles before
allocation; never truncate owners. No capability flag changes in this proposal.

## Independent scalar evidence

Run tools/check_ordered_contributions.py with Python (standard library only).
It uses independent premultiplied formulas, checks normal/multiply/erase order
counterexamples and identity for one-hot unchanged-grid reconstruction, and
demonstrates why overlapping children applying multiply separately differs from
grouped application. It does not import production code, validate a Kotlin API,
simulate smudge or prove GPU/save/Undo behavior. Native full-tile comparisons
with intervening translucent/multiply/erase content remain mandatory.
