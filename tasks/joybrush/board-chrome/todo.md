# JB-3.00a board chrome plan

Scope: §K and §K10, owner words win. Source inspection substituted for browser by owner approval after file: security rejection. Worktree codex/jb-board-chrome; main staged files untouched. tasks/todo.md is locked, so this checklist is local to this row.

- [x] Step 1: pure Kotlin layout/state rules, numeric §K tests; core:jvmTest XML totals; commit.
- [x] Step 2 renderer: views consume layout; HTML tooltip text; shared SpriteLab strip extraction; Android compile and review; commit.
- [x] Step 3 proposal: exact Activity wiring hunks in Activity-wiring.patch and INTEGRATION.md; original git apply --check passed. Lead decision supersedes this historical patch: do not apply. Lead owns actual wiring.
- [x] Review: spec coverage and limitations recorded; commits checked with git branch --contains; protected file diff absent; no installation/device claim.

Step 1 verification: BUILD SUCCESSFUL; XML 95 suites, 1449 tests, 0 failures/errors/skips; chrome suite 28 tests. Format v5 adds only tiled; old v4 defaults false. Lifted cell outline, number and hold share a core transform origin; reduced motion suppresses the wiggle only.

Step 2 verification: BUILD SUCCESSFUL for joybrush-android Kotlin and app DefaultDebug Java. Shared RollDragController harness: 20 checks passed. SpriteLab uses extracted shared classes; Type and its font resources moved once to studiokit. App export asset remains in its required original path.

- [x] Lead follow-up: bounded shadow raster/cache; one UI-frame update; selected pen movement avoids unnecessary layouts; gaps/passive/overlap/CANCEL tests.
- [x] Owner G7a pure preview scope and stable scene/frame-ID contracts. Actual preview reads remain Lead-owned.
- [x] K6 reusable 236 dp glass presentation and typed callbacks; unavailable actions disabled. Studio adapter, export dispatch/range validation and host panels remain incomplete dependencies.
- [x] Final checks: core XML 98 suites / 1471 tests; Android XML 2 suites / 14 tests; all failures/errors/skips zero. App Java compilation passes. No Activity, engine or protected files edited; no phone installation.
