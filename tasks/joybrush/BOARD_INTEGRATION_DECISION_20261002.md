# Lead decision: board chrome integration — 2026-10-02

To: JoyBrush Boards Specialist. Requested review of 35a348d6, 33171d86 and 54f66401,
worktree `C:/Users/JoyRaptor/AppData/Local/Temp/jb-board-chrome`.
Reviewed INTEGRATION.md, Activity-wiring.patch, and BoardChromeView source. This is a
boundary/source review, not full acceptance of all chrome code or a reproduced test run.

## Ownership decision

Lead owns the live document/session adapter, retained metadata, region projection, save/undo
and final JoyBrushActivity wiring. Do not apply the supplied Activity patch or implement a
second document store in the specialist worktree. No approved operational region API exists
yet: current CelProjection addresses whole-layer legacy cels and is not that API.

The scene/Host callback boundary is a useful starting point. Preserve it without promoting
the demo inputs into persisted document state. The specialist continues owning the board
chrome/layout and its independent fixes. This decision scopes technical work under the
owner's existing lead delegation; it changes no owner design requirement.

## Work the specialist can complete independently

1. Fix/measure rendering and input overhead before attaching boards. BoardChromeView init
   uses LAYER_TYPE_SOFTWARE; the patch creates a MATCH/MATCH view per board and rawPen calls
   refreshBoardChrome for every event. show/submit recompute layout, requestLayout and invalidate.
   Risk: full-window software bitmap per board plus pen-rate layout work on the Note 9. Bound
   software rendering to the needed chrome/shadow area, or use a proven alternative. Coalesce
   refresh to one scheduled UI frame, update only dirty scenes, and stop timers when inactive.
   Do not lose intermediate pen samples used by painting. Demonstrate passive boards and
   touch-transparent gaps do not intercept drawing, including overlapping hit regions and CANCEL.
   Keep this scoped to the specialist's views/helpers; Lead adapts Activity scheduling.
2. Include the owner's new preview rule, JB-3.00a §G7a: selected board crops layer thumbnails
   to its rectangle, animation previews follow its current frame, passive restores normal.
   Define preview bounds/state in chrome's inputs or a pure helper with switching tests.
   Lead owns actual GPU readback/rendering; no per-pen-event thumbnail pixel snapshots.
3. Record stable-ID contracts: action callbacks identify board and frame by IDs in the
   session adapter; frame indices from the shared strip are translated using one current
   scene snapshot. Hover playback is preview-only and must not call persisted selectFrame.
   Pausing, closing, changing scenes and losing graphics all stop temporary playback/drag.
4. Keep K6 explicitly incomplete until its shared presentation component exists. A reusable
   236 dp glass sheet can accept typed board export choices and callbacks, with no file I/O
   or exporter in the view. Lead owns actual export dispatch, range validation and Studio
   adaptation. Do not copy or restructure the private Studio export flow in this lane. Existing
   Studio controls and exports remain operational; sharing presentation and export adapters
   can land separately. Disable unsupported actions instead of showing working-looking buttons.

No phone installation is delegated by this note. No Activity, engine, retained pixel model,
shared task-board status or other agent's files may be edited by this independent work.

## Landing order and gates

1. Preserve every lane on its own branch; main's board-spec divergence is still unresolved.
   Do not cherry-pick all three commits blindly, reset main, or resolve a merge conflict.
2. Fresh overlap review against latest paper work and frame foundation. Shared SpriteLab/type
   extraction must retain Studio behavior; reported compile/controller tests are useful but
   no phone equivalence has been proven. Review modified callers and run appropriate checks.
3. Lead implements/reviews JB-3.01b/c region model and migration before operational animation:
   shared outside canvas, board-local cels, pixel-exact boundaries, crossed-edge stroke as one
   undo, held layers, persisted Board.currentFrameId, and matching CPU/GPU reads.
4. Land passive Image-board metadata/selection first if the loader/save pipeline safely accepts
   it. Multi-board support must be present before creation is exposed: today's CanvasSnapshot
   and JbCanvasView deliberately refuse multi-board files. Avoid creating drawings that cannot
   subsequently be saved/reopened. Size, lock and selection need validated, undoable mutations.
5. Lead attaches real scenes and host actions, incorporates the preview rule and removes the
   temporary legacy bottom-strip overlap. Prove painting, save/reopen, undo and preview/export
   on Note 9 before animation creation/playback controls are released.

Model version: the isolated tiled change uses v5; latest paper base uses v4, and region fields
still need a schema decision. Lead will assign the final version/migration after enumerating
all fields. Do not independently add another version bump or reinterpret old v4 frame payloads
as region cels. v5 tiled readers/writers and defaults require migration fixtures at landing.

Technical implementation choices stay with agents. Owner review remains for visual/product
acceptance after a working, safe feature can be demonstrated.
