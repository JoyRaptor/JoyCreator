# Select host acceptance and routing review — October14

Read-only source review for Lead. Pinned source ccb4913bf, branch
ui/select-host-review-current, C:/Temp/jb-select-host-review. No production source edits,
builds, GPU/device actions or new verification claims. Existing cyan gradient, full pills,
borderless paper and completed Count source remain unchanged. Helper turns could not run
because of their usage limit; this review was completed directly from source.

## Decision before activation

There is no concrete SelectionHost implementation or SelectController construction in
the pinned production source. SelectionHost is the addressed interface; RecordedScene
is a GL-thread staging coordinator, not that adapter. This review specifies what the
concrete adapter must prove; it does not approve an implementation that is absent.

An immediate Boolean must describe a completed canonical transaction, not queue acceptance.
True requires the intended addressed data, one shared history step and the immediately
readable host snapshot to agree. False/refusal/cancellation requires no later publication
by that request. Existing callers cannot represent Pending. Returning false on a timed
wait while a queued GL closure remains eligible to commit is unsafe. Returning true at
enqueue time is also unsafe: rendering, context validation or history admission can fail.

Evidence: SelectTool.kt:245 consumes commitBox's result to move its loop/clip or restore
the original box; :352 ends selection on successful delete; :364 reads visibleLines and
selects new duplicates immediately after add; :659 refreshes host lines immediately after
commit. SelectionHost.kt:108 and :120 require accepted lifts to remain lifted at identity.

## Smallest viable acceptance/cancellation contract

1. Capture immutable addressed input plus document, physical cel, selection/lift session,
   request number, graphics/context generation and canonical/history revision. Validate
   the entire batch, writable layers/records, existing addresses, unchanged seqs and frozen
   brush identities before allocation. Never infer ownership from record ID alone.
2. Stage on the actual GL owner. Before publication, revalidate context, scene/history
   identity AND the request's live cancellation permission. Rendering may be discarded
   without editing history. Coalesce preview requests only within the same live lift.
3. Resolve cancellation versus publication at one serialized decision. Timeout/cancel may
   return false only after it has irrevocably revoked this request's publication permission.
   If publication has already won, do not fabricate a refusal. Obtain its definitive result;
   if that cannot be done within the UI responsiveness budget, the immediate Boolean API
   needs a Pending/completion path before activation. A check followed by a separate publish
   is insufficient: cancellation can race between them.
4. A successful result must expose the new immutable active/visible snapshot before the
   current synchronous caller continues. Do not require an Android UI-post acknowledgement
   while that same UI thread is blocked waiting for GL. Queue admission and a UI-posted
   future snapshot are not a synchronous success barrier.
5. Finish once. A released/cancelled lease invalidates queued previews and unaccepted edits;
   cleanup releases only owned staged resources on their correct live context. Never restore
   a pre-edit source after an already committed edit, or delete reused GL names after loss.
   A late UI notification for an obsolete selection/document must not change new controls.

RecordedScene.kt:110 already checks its scene generation, before-state identity, undo/redo
heads and renderer context at commit (including reentrant callback changes); :137 closes an unaccepted lease; :324 forgets lost
names. It has NO per-UI-request timeout/cancellation token. Closing a lease and committing
it must be serialized on GL; merely posting close after a timed wait leaves the same race.
UndoLog.kt:173 publishes the prepared scene with history. Keep that publication discipline
and do all fallible work before the nonthrowing assignment.

JbCanvasView.kt:2405 onGl queues work for a rendered frame and may silently skip it when
content is lost or graphicsEpoch changed. It returns Unit and has no request completion
contract. A Boolean bridge cannot use it unchanged and assume eventual acknowledgement.
An epoch-skipped request also needs a terminal refusal/cleanup outcome.

## Callback requirements

Paths below are relative to joybrush/androidkit/src/main/kotlin/cc/joycreator/joybrush/androidkit/.

| Contract | Required concrete behavior |
| --- | --- |
| select/SelectionHost.kt:42 activeLines, :45 visibleLines | Read a coherent immutable UI snapshot derived from canonical replay geometry. Preserve drawing/visible-stack rank and exact layer/cel address, flags and groups. No synchronous GL traversal or mutable borrowed arrays. A successful write's snapshot is immediately observable. |
| :50 liftLines, :68 liftPixels | A returned lease binds the complete source scope/context, never silently omits a failed member. Null is a real refusal: pixels retain clipping but hide edit capabilities; line edits have a direct replace fallback. A pending GPU job is not an already usable pixel lift. Readiness/retry needs an explicit integration path if staging is asynchronous. |
| :53 replaceLines, :57 addLines, :60 deleteLines, :63 bakeLines | All-or-nothing addressed transaction and one shared UndoLog step. Replace preserves seq/input pairing; add validates vacant destinations and assigns new seqs per physical cel in input order. Bake uses canonical sources at original seq/operator after full WC pooling, never the composed display look. Do not mix unsupported legacy/media/structural publication. |
| :97/:116 setTransform, :105 preview | Display-only, no records/history. Snapshot input immediately; latest accepted preview wins only within its lease. Out-of-scope addresses refuse explicitly. Final commit cannot be followed by an older queued preview. |
| :108/:120 commit, :123 clear, :126 fill | Definitive result as above. Commit rebases the surviving lift to identity; pixel false preserves source/coverage. Clear/fill are one step, fill respects coverage and retains selection/clip. Adapters must restore the committed preview on refusal, including nudge/tip callers that do not perform their own rollback. |
| :111/:128 release | Idempotent lifecycle retirement, no history step. Put the CURRENT committed contents back into normal display; discard pending previews, not committed edits. Restore nothing from an obsolete document/plane. |
| :71 paintClip, :76 dimRest | Clip is an actual drawing mask, captured/ordered for stroke start, including null clearing; not just an overlay. Dimming affects display only, never canonical pixels, export or history. Context/plane changes invalidate old clipping. |
| :79 adjustColour, :82 canAdjustColour | False capability until real pixel/line preview and one-step commit work. done=false is preview; done=true publishes once or restores/refuses with a note. This void method cannot report Pending as successful completion. |
| :85 penButtonTap, :88 toast, :91 invalidate | Use existing eyedropper action; emit UI notes/actions on the main thread with stale-session guards. Request canvas rendering and overlay invalidation without publishing an edit. No recursive toast forwarding. |

RecordedEdits.kt:21/:35/:55 already supplies addressed replace/add/delete preflight;
RecordedScene.stageBake at :154 supplies canonical-source staging. These primitives still
need the joint physical maps/display/history/save publication and the UI bridge above.

## Concrete caller hazards to retain in integration tests

- SelectTool.kt:397 rasterizeDown ends selection even when bakeLines returns false. A refusal
  therefore drops count/handles today; there is no caller-level retry selection. Decide that
  behavior before activation; never use false as Pending and let a late bake happen afterward.
- :570 nudge/tip completion calls commitLines without inspecting its Boolean. A failed final
  write must not leave the preview looking edited while records are unchanged. The adapter
  can restore its committed baseline; otherwise a narrowly reviewed caller repair is needed.
- SelectController.kt:324/:326 colour/brush picker callbacks edit the tool's CURRENT selection.
  Bind App picker callbacks to their opening selection/document token; close/invalidate them
  on selection/history/context change so a late pick cannot recolour a different selection.
- Undo/Redo/recovery/layer/cel/board/file transitions retire gestures, count and lifts before
  swapping canonical context. canvas.onBeforeHistoryChange currently closes popovers/stops
  board preview only (JoyBrushActivity.kt:464). Chain selection cancellation/retirement there
  rather than relying on hidden controls or late preview cleanup. A committed operation is
  undone through shared history, never by an old lift's release.

## Routing contract and smallest test plan

App.hasRestingThumbForCount() (SelectController.kt:65/:310) must read accepted cross-child
contact state. At count DOWN the pen-origin control captures Add-or-accepted-thumb once;
finger/keyboard count input uses Add only. Mid-count thumb changes do not rewrite its base.
Track admitted pointer identities and retire them on UP/CANCEL/detach/context change;
onRawEvent is diagnostics, not admission (JbCanvasView.kt:950 precedes palm/tool routing).

Other required App callbacks are in SelectController.kt:47–65: real paper colour and
line-only colour/brush pickers, frozen brush icons, fresh destination IDs, supported
snapshot copy, restyleRing forwarding to ToolStrip.setSelectionRestyle, and the persisted
paintMakesLines shelf preference with per-brush priority. Picker/copy/Undo-action callbacks
must use their captured source context. Width/colour/brush edits route to selected lines;
the restyle notification only draws the already-approved finite ring pulse. Proko/Q2
quality-dependent defaults remain provisional; this host review makes no quality choice.

Canvas first-DOWN palm rejection exists at JbCanvasView.kt:928/:975. Once a tool owns a
stream, :920 forwards events directly; SelectRouter.otherDown at :117 counts any secondary
finger beside a pen as a thumb. Count/box receive child events before canvas rejection.
The joint admission policy must cover those paths too; the existing router counter alone
does not prove a finger was accepted. Do not claim a palm detector from these interfaces.

Chain controller.viewMoved() (:150) into existing JoyBrushActivity.canvas.onViewMoved
(:456), preserving icons, guides, fill, board and paper-tilt callbacks. It reprojects count/
box and resamples ink without ending selection or writing history. On page/paper/context
switch also invalidate outstanding sample and edit/lift generations. Keep the canvas tool
installed; selectToolInHand routes pen versus brush, not canvas.tool=null toggles.

Run the following deterministic tests when the concrete adapter exists; they are a plan,
not tests executed in this review. Use a controllable GL/frame executor and terminal ledger
(records, snapshot version, undo/redo depths, lease releases, UI callbacks). A real phone
pass follows only under Root's device runbook.

| Minimal case | Assertions |
| --- | --- |
| 1. Parameterized completed/refused host write | Exercise replace/add/delete/bake and lifted line/pixel commit/clear/fill. Success returns after records+UI snapshot+one step agree; refusal changes none; duplicate is immediately selectable at assigned seq; pixel success reuses an identity lift and moved clip. |
| 2. Cancel/deadline publication race | Cancel before enqueue execution, after staging and at a barrier immediately before publish; drain every late task. Every false result has zero later edits/history/UI-success. Also force publish to win and require exactly one true terminal result. Epoch-skipped onGl work must settle, not strand a waiter. |
| 3. Preview/lease/context race | Queue two previews, final commit and stale preview; cancel/release, then select same record ID in another cel. No old preview or release touches the sibling/new selection. Refused nudge/tip restores committed display. Context loss, undo-before-edit and stage allocation failure retain source/history and clean only owned resources. |
| 4. Accepted thumb and rejected palm through real children | Pen-count with accepted finger on canvas or other chrome captures one additive base; rejected hover/grace contact never adds, transforms or navigates. Add works for finger/keyboard count too. Release/cancel the thumb and start a new count: no stuck Add. Mid-count contact changes keep the captured base. Include pointer-ID reuse and secondary contact before pen arrival. |
| 5. Pen/finger ownership with actual root hierarchy | Parameterize brush/Select in hand, button tap/hold and Settings Pans/Selects. Plain brush pen paints (pixel clip retained, line selection ends); Select pen picks/lassos; button tap eyedrops and hold lassos, no paint. Handles/body/tips use boxTakes; two fingers transform the live selection, and navigate after it ends. Trace exactly one owner and no accidental dot/history entry on handoff/cancel. Explicitly test the current Select-in-hand override to fingerSelects; do not infer Pans behavior solely from the Settings value. |
| 6. View/history/picker lifecycle | Pan/zoom/rotate after a dense pick: count/box stay at document anchors,44dp target remains within reserves, new contrast samples win; no record/history changes and existing callbacks still run. While a picker/preview is open, undo, change cel/file or detach; late results cannot edit the new target. Hide/cancel/reattach produces no extra count choice and the next DOWN works. |

Reuse existing CountTool/View, SelectBoxCancel, SelectToolEdit/ScopedEdit and RecordedScene
failure tests as baseline. They prove their own fake/pure paths; they do not substitute for
these actual adapter/child admission and cross-thread completion tests.

## Outcome

Required callbacks and caller-dependent cancellation requirements are recorded. The live
host remains unbound; synchronous Boolean versus pending GL publication is an activation
decision for Lead. No styling change, speculative implementation, build or device work
was performed. This review adds only documentation to the separate review branch.
