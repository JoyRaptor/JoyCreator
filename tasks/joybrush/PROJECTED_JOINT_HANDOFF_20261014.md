# Rooted projected joint route — bounded work plan

Owned files for this task: RecordedScene.kt, one new RecordedSceneProjectedJointTest.kt and this
handoff. Do not change GlPaintEngine or pure proof/projection files. No builds/device/Git commands.

- [x] Read current private stageJointPrepared, promoted adapter, proposal and joint tests.
- [x] Add internal rooted projection route through the existing staging/cleanup body.
- [x] Author focused no-GL original-Step/authorization/endpoint/epoch/rollback tests.
- [x] Review actual source and count authored methods; hand source to Root for compilation/tests.
- [ ] Root compilation/test/engine adoption (not run or claimed by this task).

This task does not implement engine transfer/publication/display or establish GPU evidence.

## Frozen source delta

Only RecordedScene.kt, new RecordedSceneProjectedJointTest.kt and this handoff were changed for
this task. GlPaintEngine and the pure proof/projection support were not edited. Source/test files
are frozen after Root's review request. Exact hashes before adoption:

- RecordedScene.kt SHA256 `045A8B6744638E332203873F62DEBE239943B1E078FABF81B068B06C5E7A43C9`
- RecordedSceneProjectedJointTest.kt SHA256 `7DDE16A261D980EAB78D0D08742A340879F8962A2A4B0C0C94BBECB83BC3B041`

Actual test declaration count: **20 authored/unrun methods**. Two added after Root's first
18-method read cover late multi-tile composition failure and both-facade binding/counter unwind.
No compiler, Gradle, device, Git, commit or push operation was used.

## Production code changes

At RecordedScene.kt:88, internal PromotedPublication extends the existing actual-root interface
with promotedBases, surviving exact legacyBaseSteps and nullable baseTransferIdentity. Ordinary/
reset roots use a null identity/empty promotion metadata; engine private ticket validation remains
the authority that can install this metadata. The interface does not certify a foreign GPU handle.

At RecordedScene.kt:481, internal stageJointProjected(expected, fullStep, direction, frozen)
shares the same structural/nonempty/reset guards and preparation counter unwind as public staging.
The existing private stageJointPrepared takes an optional expected promoted root internally; the
public stageJoint passes no root and retains its native-disjoint refusal before callbacks.

The projected branch requires expected === actual bound publication, nonnull transfer identity,
nonempty promoted addresses, actual scene ownership and bound history. It recomputes the temporary
edit through published PromotedBaseProjection using this exact root's cursor/eligible Step set;
the caller cannot supply a fabricated edit or alias Boolean. That helper checks actual undo/redo
head identity, exact captured old Step identity, canonical seq0 normal endpoints, supported scope
and duplicate/cross-family targets before renderer/context/budget work.

Endpoint protection includes the FULL original Step plus temporary projected Step. Cached source/
history theft refuses before warm. History prepareNew/Undo/Redo always uses the full original Step;
the temporary tile-to-slab representation never enters history or a retirement ledger. A prepared
projection's before-plane identities are rechecked after history preparation. Public/default edit
creation still occurs in its original position after cursor preparation to preserve callback order.

validateRender, canonical frozen definitions, touched-cel warm/coverage check, buildStage, epoch/
same-publication guards, JointStaged tracking/adoption/cancellation and resource cleanup are reused
unchanged. Same-epoch returned fresh outputs remain reclaimable on refusal; actual epoch loss
abandons old names. Only the engine assigns the complete candidate native/source/cursor root.

## Authored contract coverage

Tests exercise exact original TileChange/Step/cursor history through native undo/redo, mixed native
and frame base edits, RawAdd undo -> older native undo/redo -> RawAdd redo, unchanged public refusal,
foreign/equivalent expected root refusal, missing transfer/scope/plane, equivalent but uncaptured
Steps and non-head/direction refusal, NEW legacy writes/wrong source/encoded media refusal,
structural/empty pre-root guards, borrowed endpoints on cancellation, complete-step/global renderer
aliases, existing saved-cache theft, touched-only cold warm and union mismatch, same-epoch/root
switch cleanup, physical-epoch loss abandonment, late composition failure, facade binding/counter
unwind, final context callback staleness, full-history budget retirement/eligibility pruning and
resource-free null/null legacy history movement without invented slab ownership.

Static review checked actual source/fixture interfaces and counted 20 declarations; it is not
execution evidence. Late-failure fixture's ordered keys are backed by RecordedTilePlan's existing
LinkedHashSet affected-tile traversal. Cache-theft fixture creates valid saved cache ownership
first, then injects malformed root history metadata so it tests staging, not factory rejection.

## Limits and Root integration responsibilities

The root object and texture names in tests are fake; no real engine transfer, GL format, display,
reset, UI/save/load or physical appearance proof follows. Root must supply immutable detached
metadata in its actual PublishedPaintState and engine-owned readonly binding getter, preserve the
same transfer identity across later roots and prune old Step eligibility on cursor trimming.

Root validates native/disjoint endpoints, format/ownership, captured physical routing, plan/paper/
context, history capability and display capability before publishing. It skips only exact bridged
TileChange instances in native staging; assigns all native/recorded/cursor slices once; adopts
the scene lease; executes original history/native and scene cache retirement in the correct epoch.
The projected scene route does not independently authorize arbitrary source uploads or validate
native/media endpoints. It preserves existing global/factory protection and public native-disjoint
staging. Candidate plain multi-slab/non-normal display refusal remains Root-owned, as do production
activation and any first-frame/bootstrap/captured-routing policy.
