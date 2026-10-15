# Captured physical source values — format contract

Source4597a1cb6 is the initial implementation under verification. This document
describes its contract, not completed runtime capability. The accepted phone build
remains6d8651b49 until new exact-source checks and device evidence supersede it.

A captured stroke retains its complete raw samples, seed, frozen brush identity,
color, smoothing, capture zoom and width. Its source-domain window minus exclusions
clips the final source image after full-resolution pooling. Integer placement moves
that appearance without changing raw coordinates, sensor bits or the domain.
There is no inferred historical board/window and no path-segment clipping.

JBS record version4 adds optional flag bit5 after the existing v3 owner fields.
The block contains source version1, domain flags, optional signed32 window x/y/w/h,
u16 exclusion count, signed32 exclusion rectangles in canonical y/x/h/w order, then
signed32 dx/dy. Rectangles are half-open with positive extents. Exclusions may overlap
but wire duplicates/noncanonical order refuse. At most64 exclusions are accepted,
checked before list allocation. Existing record count/sample bounds still apply.
Captured records carry all three optional sensor channels, including raw NaN bits.
Unknown versions/flags, truncated blocks and v4 trailers refuse. Genuine v1-v3
defaults, file-order sequence migration and their historical trailer policy remain.

Document version11 is required for captured records. A version11 regional line
requires an explicit captured source; a legacy null value is never interpreted
using today's board geometry. Older regional archives remain readable at their
historical migrated version10. A new ordinary stroke-bearing save upgrades only its
output document header to11. Raster/media-only historical headers stay unchanged.
Caller document values are not rewritten by output preparation.

NEW history may add/delete complete captured endpoints or change only placement,
lock and group on an existing captured line. Recipe and domain reinterpretation,
ordinary/captured conversion and same-address delete/add chains refuse before cursor
publication. Full layer/cel/record addresses distinguish equal short IDs. Admitted
undo/redo retains its complete Step and exact endpoint bits. Collection/sample
snapshots prevent caller mutation after admission. Ordinary source policy is retained.

Captured records are presently refused by old replay/composition/SVG/region export,
snapshot/cold load/Scene and raw-sample edit consumers; Select filters them out until
its source-aware host exists. The archive reader and structural checker can retain
valid v11 captured values, but the writer still refuses them before touching output:
the clipping-aware saved-look validator and runtime adapter are not implemented yet.
Empty samples, empty declared looks and CPU-unsupported brushes cannot bypass this.

The next slice must prove actual post-pool physical clipping and integer blits on
the GPU, including cross-tile/negative coordinates, overlapping exclusions, owner
independence from current board state, rollback and wrong-order controls. Only then
can narrowly reviewed consumers be enabled. Live capture, production View/Open,
Select, Working/save/recovery and source-aware thumbnail/export remain unfinished.
