# Shared Slice and smudge sections — October25

Owner continues work while Claude is unavailable. Codex Coordinator approved
the pure planning and ownership-oracle steps; runtime/schema activation remains
separately reviewed. This is not a completed Slice or smudge feature.

- [x] Preserve the complete original StrokeRecord recipe, seed, raw sensor
  samples and wash frame; never reseed or resample shortened fragments.
- [x] Pure fractional InkLine parameter partition: zero-width crossings remove
  no interval, touched ranges and their complement cover the whole domain,
  half-open boundaries have one owner, and dot strokes remain represented.
- [x] Defensive snapshots and pre-copy input/work/output budgets, with refusal
  before unbounded input-tail traversal. Captured sources remain refused.
- [x] Ten focused core tests pass on a8b63e010, including tangent/vertex/end,
  overlap/repeats, tiny spans, unknown sensor channels and budget failures.
- [ ] Pure exclusive ownership predicate and independent scalar alpha oracle.
- [ ] Source geometry/frame/replay-version provenance and persistence design.
- [ ] Canonical full optical finish followed by source ownership, with exact
  full-tile RGBA identity proof and negative/self-crossing/halo cases.
- [ ] Native split + touched bake + smudge as one staged transaction, one
  Undo/redo, no intermediate publication, and failure rollback.
- [ ] Paint-only pickup excludes retained lines; moving an untouched line
  after smudge leaves no copied-line ghost in the pixel slab.
- [ ] Save/reopen and first edit, native diagnostic, then phone UI acceptance.

## Ownership boundary under review

A geometric union is not proof of visual equality. Separate fractional masks
under source-over lose alpha: a*w1 over a*w2 produces
a*(w1+w2)-a*a*w1*w2. The approved next pure step classifies each queried point
into exactly one source span using canonical geometry and one shared frame,
sample grid and deterministic tie rule. Nearest-centreline parameter is an
explicit partition of the finished optical source, not recovered pigment
deposition or branch-specific wash physics. The scalar oracle must prove
exactly one owner at every sampled point and one original operator application.

Every line owner under a smudge contact, including optical halo, must be
considered. A selected line or nearest branch alone is insufficient. Existing
captured/frame/mask/media guards stay until their own proof; this work does not
change the Lead's shader/program selection or the K26 media adapter. Reshape,
rebrush and serialized source-frame behavior need explicit domain decisions.

The planner itself changes no StrokeRecord fields, codecs, renderer, admission
gate or UI. No runtime capability is claimed.

## Ownership candidate, unverified

977dbdac1 adds the pure LineSliceOwnership helper and scalar oracle tests;
987630925 isolates the default identity frame from the mutable shared
Homography.IDENTITY array and adds boundary-refusal regressions. There are ten
ownership test cases authored, not executed. Do not transfer the earlier ten
planner results to them.

The helper snapshots geometry and one inverse source frame, chooses earliest
fractional parameter on equal squared distance, and assigns a single half-open
span even outside the optical halo. Independent straight-line grid expectations
check the parameter and span, then the scalar premultiplied oracle checks
reconstruction and a single multiply application. Fractional half-masks are an
explicit failing counterexample, not a supported runtime mode.

The numeric domain is deliberately bounded: non-finite queries, singular or
pole-crossing maps, and squared projection/distance overflow or underflow are
refused rather than guessed. Ordinary float document coordinates remain within
these arithmetic bounds; support for arbitrary finite Double magnitudes is not
claimed. This candidate still does not persist domains or activate renderer,
Slice, smudge, rebrush or reshape behavior.

## Proposed persistence and activation boundary (review required)

This is a design proposal, not an implemented record or archive format. A section
needs a stable source identity, the complete original recipe and raw samples,
canonical centreline geometry, an ownership-rule/replay version, its owned
parameter interval, and one source-to-document placement. Sibling sections must
reference the same immutable source. A section's editable id must remain distinct
from that shared source identity. Repeated cuts partition an existing interval;
they must not replay a shortened trajectory or create a new random seed.

The ownership placement and StrokeWashFrame have different purposes. The former
maps the canonical finished source and its partition; the latter locates the wash
texture within that source's original recipe. Moving a section carries its source
placement, while the original wash recipe stays fixed. Applying a transform twice
to raw samples and placement would be incorrect. Until a filtered transformed
source has an explicit sampling contract, initial exact acceptance should be
limited to the unchanged source grid and integer translations. General projective
placement is not established by the pure inverse-coordinate test.

Initial sections should refuse reshape, rebrush and width changes: these alter
the canonical optical source or ownership geometry. Recolour could become a
source-preserving finish edit only after native full-tile comparison, including
edge alpha, gravity pooling and multiply. Unsupported capturedPhysicalSource,
media and already-masked inputs retain their current guards. Do not reinterpret
the existing translation-only CaptureDomain as this new section type.

Archive work must separately specify budgets, immutable shared-source snapshots,
duplicate-id rejection, missing/unknown version refusal, and frozen old-version
fixtures. No codec bump or new StrokeRecord field is authorized by this proposal.
Retained section sequence and blend operation must preserve the original optical
application once; sibling ordering at the same original sequence requires an
explicit model decision rather than assigning artificial new chronological seqs.

Smudge admission must gather all intersected optical owners, stage the retained
domains and touched pixels together, and exclude retained line projection from
pickup. The pixel slab must preserve the original chronological placement with
respect to other content. A single commit publishes the staged change and records
one Undo/redo; any budget, bake or publication failure leaves both source lines
and pixels untouched. These transaction and ordering requirements remain open
implementation work, not consequences of passing the scalar ownership oracle.
