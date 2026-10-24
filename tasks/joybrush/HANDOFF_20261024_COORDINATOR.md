# JoyBrush Lead Coordinator — October24

This supersedes this chat's October16 resume instructions. Read current origin,
`HANDOFF_20261023_ENGINE_LEAD.md`, `LEAD_PLAN_20261023.md`, peer handoffs and live
resource reservations before work. Claude Engine Lead owns engine integration
to main. Do not resume old engine architecture as though eight days of later
NativeLines/media work did not happen.

## Current isolated candidate

Checkout `C:/Temp/jb-select-integration-20261024`, branch
`lead/select-integration-20261024`, based on main`a94933ad7`.
Latest executable change at this handoff: `9805dbff6`; root context fix`d8ddf2ebe`.
This is a REVIEW CANDIDATE, NOT final-test verified, NOT on main, NOT installed.

- Select supporting`cc1061634` and all eight ordered LEAD PATCHes were applied,
  preserving current-main Joybot/Studio/media work. Source hunks applied cleanly.
  Cherry-pick attempts that conflicted only in shared todo/docs were aborted;
  the exact scoped doc/source patches were reapplied, preserving this lane's plan.
- Status/evidence docs`98d925b70/1b7da0af5`, original brief/gap list`001e6e710`,
  UX review`3e0d41e5d` and repair review`12cf5d5d3` are included.
- Builder follow-ups`b590e58c5` and`2cbfbfb86` are included as`32e9370ae` and
  `cd125654b`: bounded D2/More scrolling, persistent locked-option reveal and a
  capability-gated per-brush-priority caption. Current-main host defaults that
  capability FALSE; do not claim unmerged L3 behaviour from the caption.
- A Luna read-only audit found no additional concrete issue in clipboard brush
  import, destination-cel routing, one-step Add or selection-history recovery.
  UX confirmed its initial overflow/reveal SOURCE findings addressed. Neither
  review executed tests or closes rendered/phone acceptance.
- Root`d8ddf2ebe` abandons line-lift state/matrix/program before replacement-context
  allocation. Previously a dead lift could restore old names into a new layer
  or draw old preview/program handles. Two backend regression cases are authored;
  these are host fixtures, not actual EGL-loss/device evidence.
- Root`9805dbff6` adds allocation-before-coverage admission for interactive paint
  masks, checked before initial rasterisation, transform preview and commit.
  A16,384px span can permit hundreds of MiB; it was falsely documented as4MB.
  The first UI allowance is64 coverage tiles/4MiB, with exact negative/unaligned
  grid accounting. Six core and three app regression cases are authored. Global
  low-level mask/warp semantics remain unchanged. The false paint-inside toast
  was corrected. Pixel lift and all-brush clipping remain unimplemented/gated.
- New`specs/SELECT_PIXELS_DESIGN_20261023.md` is a proposal for Engine Lead review.
  It specifies a restricted first raster-plane slice, mixed-item ownership gate,
  full source/destination memory accounting, one-Step rollback, context loss and
  soft-alpha overlap concerns. It DOES NOT approve or start Phase B.

## Verification status

Only `git diff --check` and source reviews have run on this candidate. No fresh
core/backend/app XML, APK, screenshot, phone gesture or save/reopen pass exists.
The builder's122 passing app tests predate final fixes. Historical watercolor
and October16 proof belong to their exact source/artifacts, not this candidate.

Resource checks during preparation found an active helper-ringfix app assembly
under the shared Gradle claim and roughly0.4–0.8GB free; no root build was started.
Later the watercolor specialist reported explicit OWNER-RESERVED RAM AND PHONE
verification, and claimed`jb-phone.lock` as`watercolor-finish` at16:59 October24.
Root acknowledged: no build, waiting wrapper, phone job, lock or process kill.
Wait for that lane's release; absent Gradle lock alone is not a reservation grant.

After release: fresh RAM/process/lock admission, exact-source full standalone
core/backend checks, then app Select/Selection/Host tests including
`SelectMenuScrollTest`, `SelectSettingsTest`, `SelectToolEditTest` and final Kotlin
compile. Independently count fresh XML and preserve logs/source. Do not edit the
checkout during an admitted build. Build APK only after checks and resource
admission; capture exact revision/hash. Coordinate C1/C2 with UX/watercolor.

## What remains

1. Execute final suites; review/fix failures; Engine Lead integration to current
   main without losing newer hot-file work.
2. Exact integrated APK C1 gestures/one-step undo/save-reopen and C2 white/dark,
   short-window/large-text, real More hierarchy, layer menus and nudge screenshots.
3. A9 Slice atomic split/zero-width engine support, A10 badges/toasts after accepted
   L3 defaults, ring Bend after the vector wash-frame work, C3 veil.
4. Phase B approved design, real pixel lift/clip/operations, mixed-item/region
   invariants and resource/device proof. Existing null/unsupported gates stay.
5. Media/watercolor/Unibrush work stays with current Engine Lead and specialists;
   current atlas work is later than A2 and must not be replaced by old scaffolds.

Watercolor current candidate reported`0b02e9e06` on
`vector/watercolour-finish-20261024`, with bounded selected-line coverage cache
LEAD PATCH`b5e3b167d` and unaccepted L3 candidate`8c019c2bb`. Its predecessor
counts are NOT proof of the newest source. Its engine cache hunks do not touch
the root's lift-abandon fields. When combining accepted L3 with Select's new
API, override`App.perBrushLinesOverrideShelf()` true only if the integrated
per-brush semantics support the stated priority. Do not adopt unaccepted defaults
or claim owner look approval.

## Preserved state and scheduling

Old`C:/Temp/jb-live-current` remains clean at`fe2838486`; its corrected focused
October16 log says BUILD SUCCESSFUL2m24, but its XML directory is now absent.
No new independent test count is claimed. Preserve it and all helper/original
checkouts; do not blindly merge its obsolete engine path into current main.
No temporary source/evidence cleanup while required work is unfinished.

Persistent goal remains unfinished (historical tool status`usageLimited`); no
duplicate or completion update. Existing five-hour automation was found PAUSED.
Keep that status; refreshing stale instructions is not permission to re-enable
it. No credits/reset purchase or consumption is authorized.
