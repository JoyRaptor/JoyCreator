# Dry pencil window correction: root design gate

Confirmed source hazards: Stick.emit bounds use max local extent as an unrotated radius; a diagonal rotated rectangle can exceed it. MediaCanvas follows the latest pen before drawing the entire queued dry batch. MediaWindow.follow never verifies the relocated window fits the supplied rectangle. These establish clipping paths, not identification of every seam in the owner's image.

## First implementation slice

- Pure DryWindowGeometry helper/tests: conservative packed-instance shader footprint and validated tile-aligned placement. OpenCode owns two new files only.
- Root wires the proven rotated enclosure into DryStroke's dirty bounds. Preserve every instance, its order and all brush parameters. Add a real DryStroke regression demonstrating the old diagonal under-enclosure.
- Memory refusals log available RAM, threshold and reservation at the UI/GL gates. No changed memory limit or brush behavior. Compile before publication.

## Multi-window rendering contract

Do not call dryFrame consecutively on arbitrary chunks. Its dabs all read pre-frame crush, accumulate delta and apply once with the original batch's final travel. Sequential chunks change the paper read by later dabs and smear direction.

Required design for a larger batch: spatial owned write interiors, overlapping read halos, frozen pre-frame paper input for all interiors, identical original batch-final travel, and original ordered dabs contributing to each relevant interior. Write each destination pixel once. This also supports a single large contact without shrinking it or dropping it.

Existing stroke copy-on-write is insufficient as a frame snapshot: tiles already touched earlier in the stroke are subsequently written in place. Snapshot those paper inputs explicitly before any new frame writes. Keep missing tiles implicitly zero and include temporary snapshot/allocator costs in preflight. Where the active window has full-float paper, snapshot that state rather than silently rounding it through half-float stores. Snapshot release must be guaranteed on refusal/context loss.

Do not persist read halos as write interiors. Preserve untouched neighbouring state and colour. Load/write back without injecting extra water simulation steps; wet-under-dry scheduling remains an explicit integration gate. One outer stroke/Undo step must encompass all affected tiles.

Before landing: actual GL comparison with a sufficiently large unclipped reference, overlapping dabs and changed crush/smear, translated and negative-coordinate windows, oversized contacts, exact untouched pixels, all-store Undo/Redo, refusal before partial publication, and allocation cleanup. Desktop geometry alone is insufficient.

Status: rotated dirty bounds regression passes and is published; pure geometry/placement helper integrated95e1bdd9 after20 targeted tests pass. Production multi-window implementation remains pending. Note20 wireless connection currently undiscoverable; Note9 remains untouched.
