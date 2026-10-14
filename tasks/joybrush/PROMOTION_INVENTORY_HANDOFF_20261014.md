# Plain native promotion inventory — bounded source handoff

Owned worktree: `C:/Temp/jb-promotion-inventory`, parent-provided branch
`lead/jb-promotion-inventory` based on `5b3ebdca`. No live/original worktree changes,
builds, device operations, Git commands, commits or pushes were performed.

## Plan and completed source work

- [x] Read applicable AGENTS and actual engine/proof/scene/region/test interfaces.
- [x] Add one internal dry inspection entry and private native ownership/projection builders.
- [x] Author one focused no-GL test class.
- [x] Review source for mutation/GL/provider calls, inventory completeness and retained protection.
- [ ] Root compile/test/review and actual integration. No passing test/GPU result is claimed here.

Only source changes:

1. `joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPaintEngine.kt`
   adds the NativeBaseTransferProof import, PlainPromotionInventory and inspection helpers.
2. `joybrush/androidkit/src/test/kotlin/cc/joycreator/joybrush/androidkit/gl/GlPlainPromotionInventoryTest.kt`
   contains **19 authored/unrun @Test methods** (some cover multiple malformed/alias cases).
3. This worktree handoff note. No existing source-proof/projection tests were changed; their
   previously integrated 23-contract semantics remain intact.

## Concrete entry and invariants

`engine.inspectPlainPromotionInventory(ownerId)` returns exact borrowed Physical tuples, a
detached `(owner, canonical cel) -> key/name` content snapshot, ownership Occurrences, selected
names checked under the native RGBA8 contract, the exact existing UndoLog cursor and document/paper/logical/physical
context identities. It neither clears maps nor creates missing stores, settles paint, performs
GL calls, calls renderers, registers/releases a scene, binds history, or assigns artwork roots.
Ordinary writers and protectedRecordedTextureHandles are unchanged; the new entry has no caller
in ordinary paint/UI/save/load paths. It is not a promotion/publication API or activation permit.

Selected scope requires current local/ready/idle context, no playback/periodic mode, valid actual
document paint owner, no selected mask/clip/media/agreement state or recorded header, canonical
shared map alias, exactly all known document frame maps (including inactive and empty ones),
distinct frame stores, canonical payload-map metadata and a plan matching saved current frames.
Selected names must be nonzero (including negative Int bit patterns), excluded from floatNames, and have exactly one allocation
occurrence at their exact physical tile address.

Only `cels[actualDocSharedId] === layer.tiles`, matching layer.sharedCel, is deduplicated. Frame,
sibling, mask, projection, media, history and pool ownership is preserved as occurrences, not
subtracted from a protection union. History ownership enumerates undo-before and redo-after,
not every endpoint reference; current undo-after is therefore allowed. History/cache maps retain
their occurrence multiplicity. Repeated native/mask objects refuse rather than recurse or alias.

Layer.projected owns its handles. visibleProjection is checked against the actual current
source/region route: a whole single slice can directly borrow its native tile; multiple slices
must select the same-key owned projection. Complete expected index equality catches stale/missing
entries. It never invokes visibleTiles/projectedTiles or allocates a display texture. Agreements
are validated against existing canonical current look endpoints with actual same-tile payload,
then remain borrowers; foreign/stale agreements cannot authorize aliases.

The non-base inventory includes native masks/media/owned projections, current stroke buffers,
media stroke endpoints, exact cursor-owned endpoints, registered scene units/looks/slabs, every
engine free pool, clear/white/thumb, compositor pairs, paper images, frozen stamp/grain bindings,
grain/preview/background holders and all WC bands/finish pools/image sets through their existing
readonly holder APIs. Outstanding local scene leases/preparations refuse via its existing binding
preflight; the inspector never claims to inventory their unpublished names. Float registry entries
are format exclusions, not falsely counted as an extra allocation owner.

Native stores have an **RGBA8 contract**, including caller-produced names accepted by replacement
APIs. This inspection checks current native ownership and float-registry exclusions under that
contract; it does not establish complete allocation lineage or certify an arbitrary uploaded
handle by absence from floatNames. No GPU format query/readback was added. Reflection tests use
made-up handles solely to test bookkeeping. Actual GPU format/source ownership remains a separate
Root acceptance gate.

## Tests and Root follow-up

Authored cases cover shared/known inactive/empty frames and exact map tokens, no store creation
on missing/mismatched/unknown scope, duplicate empty stores/current names, masks/clip/payload,
stale plans/recorded headers, history owner/reference distinction and multiplicity, sibling/mask/
projection/media/pool/stroke/global/float/cache aliases, grain/preview/background/WC inventories,
direct and sliced display borrowing, stale display indexes, agreement borrowing, idle/context
refusals, context/source snapshot freshness and unchanged general cold-look alias rejection.

Static review read back the actual written files and counted 19 actual declarations. Kotlin
compilation, targeted/full tests and driver checks were intentionally NOT run under this task.

Root still owns the actual transfer ticket and whole-activation capability policy: no prior scene,
ALL-layer metadata/agreement/owner snapshot, supported reachable undo AND redo histories, final
rechecks around providers, detached new empty stores, coordinator/renderer phase acknowledgement,
complete root assignment, display/snapshot routing and reset/source retirement. The inventory
does not certify an arbitrary cursor's history destinations or make a local scene eligible for
promotion; it includes stable local scene protection only so inspection cannot ignore such owners.

Refresh the real inventory before publication rather than reusing its old occurrence list. Feed
its tuples/occurrences/native-contract set into NativeBaseTransferProof only within an engine-owned
ticket; do not accept a caller-created summary as authorization. Do not relax native-disjoint
cold adoption or fabricate tile/slab alias Steps. Root should run target androidkit tests and
appropriate full/native checks under the shared admission locks before integration/activation.

## Root review corrections before adoption

Both requested corrections are included: source/display names now use `!= 0`, preserving unsigned
GLES name bit patterns represented by Int; one new authored case exercises negative shared/frame
sources and borrowed display entries. Comments now acknowledge caller-produced native names and
distinguish the RGBA8 contract/current ownership/float registry from complete allocation lineage
or driver-format proof. The preexisting `java.util.IdentityHashMap` import at line 53 is available.

Review lesson: follow the existing engine's nonzero texture-name convention; do not infer GLuint
validity from signed positivity or infer driver format/allocation provenance from store membership.
This prerequisite does not authorize first-frame/bootstrap recording or resolve per-stroke captured
physical routing/clipping under changing region display; Root retains those production policies.

## Root verification in live integration

Root adopted only the reviewed inventory source and 19 tests. Full androidkit:test passed in
2m19 under the exclusive resource-admitted shared lock. Independent XML count: 655 tests,
62 suites, zero failures/errors/skips. Receipt: C:/Temp/jb-lead-ops/live-promotion-inventory-backend-xml.json;
exact engine/test Git blobs are recorded there. No promotion, live UI or GPU-format proof follows.

