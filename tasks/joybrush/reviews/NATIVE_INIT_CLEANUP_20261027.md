# Narrow native initialization cleanup — October 27, 2026

Assigned by JoyBrush Lead Coordinator after read-only c971 audit. Isolated branch
`codex/native-init-cleanup-20261027`, base `c8e116205`, checkout
`C:/Temp/jb-native-init-cleanup-20261027`.

Ownership: GlPaintEngine createGlObjects initialization hunks only; GlProgram constructor;
new GlInitOwnership/GlProgramConstruction helpers and focused tests. Root owns View cleanup
callback fixes and app integration. No release-wide behavior or guarded feature is activated.

- [x] Preserve dirty combined candidate and branch from assigned frozen source.
- [x] Author fixed primitive ledgers before allocating GL objects; capture actual EGL context
  and engine physical epoch, gate every rollback deletion, never scan stale engine fields.
- [x] Register buffer/VAO/FBO/initial-texture names directly in ledgers before storage can fail;
  register grain placeholder on both success and thrown failure. Roll back only this attempt.
- [x] Make actual shader/program construction ownership testable and exception-safe across
  fragment compile, diagnostic retrieval, attach/link and shader retirement. Return only a
  linked program; constructor-local resources are cleaned on failure in their original context.
- [x] Author five Luna ledger tests and seven actual-constructor-flow fault tests.
- [ ] Coordinator source review, compile/focused execution and real GL fault/cleanup proof.

## Focused gate (prepared, NOT executed)

Run admitted backend tests on exact integrated source: GlInitOwnershipTest (5),
GlProgramConstructionTest (7), and existing GlContextLossTest to preserve stale-handle and
failed-reinit readiness policy. Selector arguments for the standalone JoyBrush build:

`-p joybrush :androidkit:test --tests cc.joycreator.joybrush.androidkit.gl.GlInitOwnershipTest --tests cc.joycreator.joybrush.androidkit.gl.GlProgramConstructionTest --tests cc.joycreator.joybrush.androidkit.gl.GlContextLossTest --no-daemon --max-workers=1`

No job/phone grant exists here. Lead admits the resource slot, records exact HEAD/dependencies,
fresh XML counts/hash and compiler output. Synthetic driver tests prove ownership/control flow,
not GPU shader compilation, driver deletion, GL state restoration, performance or native pixels.
The existing shader sources, upload formats and normal release path stay unchanged.

Same-current-context GL acceptance must inject a later initialization failure after earlier
objects exist, verify no surviving current-attempt names, and verify readiness remains false;
then retry cleanly. A lost/switched context must perform NO stale-name deletion. Preserve real
failure evidence. Deletion exceptions are suppressed on the original initialization failure;
all other current-attempt cleanup is still attempted. No successful cleanup claim if deletion
itself fails. Root's View release callback fix is a separate prerequisite, not solved here.

Frame V2 source handoff and frozen-brush seven-test gate remain separate deliverables. Their
tests and native execution are still open; this cleanup work does not promote those features.

## Published source scope

Source checkpoint `41dbdb38a`. Code-only selective diff is
`handoffs/NATIVE_INIT_CODE_20261027.patch`, against c8e116205, containing only the two granted
runtime files, two new helpers and two tests. Use `git apply --check --unidiff-zero` before
applying; reconcile if root's file has advanced. Reverse-check against this source passed.
New fault tests5+7 and existing context-loss8 make a prepared20-check gate; none was executed.
Parent and Luna source review found no concrete API/ownership mismatch; that is not compilation.
Root owns View callback fix, source integration and the shared build/phone queue. No job/lock
or phone claim is held here. Existing shared candidate dirty/staged paths were not touched.
