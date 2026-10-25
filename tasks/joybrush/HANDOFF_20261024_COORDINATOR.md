## Verified continuation — October24, after the evening test window

Latest executable candidate `1bdbec22a` adopts supporting cancellation/stale-callback
fix `d26afbc26` after actual regression failures. It has NOT been rerun yet. Root
also adopted the offline bench validator `b73b4b8ef`, independently tested29/29.
This utility proves evidence consistency only; it is not a phone/visual test.
Metadata field is `source40hex`. Android bench/runner adoption is still owed here.

Exact `79f2d4dba`: focused mask6 + context8 =14 cases PASS, all0fail/error/skip,
closed4m11. Root counted fresh testcase nodes and hashes independently.
Exact `24ba90ea9`: app test compilation failed on a missing runner import; no
behavioral cases ran. Fixture-only correction `55b06a3db` then ran27 cases:
20pass/7assertionfail/0error/0skip, closed1m3. Actual failures: HSB cancel committed,
dismiss retained preview, late slider UP edited a new drawing; stale success
redrew the new drawing, stale refusal reported into it; cross-layer copy reversed
local-seq order, and a UI-only source was copied without canonical proof. See
reviews/SELECT_FOCUSED_79F_20261024.json and SELECT_RED_55B_20261024.json.
Current supporting fix targets5 of7; Copy's2 failures intentionally remain until
engine-issued metadata admission is implemented and reviewed. Do not call this
an accepted build or transfer earlier test counts to the new source.

New main `origin/joy-creator7d2d88f85` merged the older Select bench/product changes
and K25/K26. This isolated candidate still has base`a94933ad7`. Reconcile by scoped
reapplication, preserving new A1–A8/C2/safety semantics; never wholesale replace
Select files or resolve away newer work. UX review38900705f has ordered bench
hunks and flags generic cancelGesture replaying a body tap on ACTION_CANCEL.
Actual overlay DOWN/CANCEL versus clean-UP tests are required for that edge.

K25 now settles per-brush defaults: line set/vectorWC ON, painting OFF,
pixels-always no switch; NO legacy global fallback. Old L3 candidate8c019c2bb is
owner-held. Retire the obsolete global/shelf-priority controls; do not enable the
interim perBrushLinesOverrideShelf caption as final design. K25 partial Smudge
uses an atomic split then bakes the touched part; K26 puts lines ABOVE realistic
paint on the same layer. Neither engine milestone is proved by tint-cache tests.
Exact L3 addendum is on current main at
briefs/CODEX_WATERCOLOUR_LINE_LIVE_20261023.md. Lead's two-shader landing is local
lead/wc-land2, C:/Temp/lead-wcl,4fcf8c7fb; ordinary and moved-wash programs separate.
Do not edit owner-held shader/program-selection paths or accept obsolete timing.

Owner requires `tasks/joybrush/ui-scripts/select_bench.sh <out>` after each step.
Harden its lock ownership, unresolved timeout, identity, exact APK and complete
JSON/PNG evidence before running; existing script prints success-like output on
failed/missing runs. Synthetic injected input is not physical S Pen/manual/save
acceptance. Preserve phone drawing hashes and fresh Home/readiness gates.

Old17:00 reservations and installed hashes became stale across an assistant-turn
failure. A NEW primary auto-install watcher12660 reappeared under interactive
shell4308; root did not restore it. The old2680 pause receipt is historical, not
proof the watcher is still off. Last59336 log showed successful install then
waiting at19:48. Recheck and coordinate before device work; do not kill unrelated
daemons. No root phone/Gradle job or waiting wrapper exists.

Next: focused rerun of the supporting fix, canonical Copy admission with source
proof/budget and separate Engine/View/Activity patches, reconcile current main,
then exact-APK per-step bench + manual/visual gates. Full suites remain owed.
PhaseB design remains a proposal, not implementation approval. Original notes
below are historical and superseded by this continuation where stated.

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
