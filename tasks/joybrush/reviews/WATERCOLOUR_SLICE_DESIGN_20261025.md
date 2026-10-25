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
gate or UI. New ownership helper source is being authored outside the checkout
while serial build windows remain available. No runtime capability is claimed.
