# D23 publication receipt — proposed engine/UI boundary

This is a source-level contract proposal, not an implemented engine hook or phone
acceptance. Lead owns GlPaintEngine/JbCanvasView and reviews its implementation.

The UI must receive a detached receipt only after the specific user stroke's history
step and its line/pixel state have been published successfully. `onStrokeEnded`
cannot supply it: JbCanvasView calls that before queued GL work, and also on cancel.
GlPaintEngine.endStroke can return zero while watercolor is still fading. Neither
pointer UP, tile count alone, nor a generic history-changed callback proves D23.

Proposed fields (runtime only; no archive/schema additions):

- Document generation and operation ID captured before admission, carried through
  deferred commit/fade and returned unchanged; one terminal publication per ID.
- Published history step token/revision for that exact operation.
- A set of engine-established facts: PIXEL_BRUSH_ON_LINE_LAYER and/or
  SMUDGE_CONSUMED_LINE. No fact inferred from brush name, current mode, current
  selection, current layer, or a later mutable snapshot.
- For SMUDGE_CONSUMED_LINE, a validated undo target for that published step. The
  Undo action must only execute if that step is still the current undo head in the
  same document; otherwise expire the action. Never undo a newer unrelated step.

PIXEL_BRUSH_ON_LINE_LAYER means the destination held lines at stroke admission and
the successful operation actually published pixels. It is not permission to freeze
the whole layer or to make a refused stroke appear successful.
SMUDGE_CONSUMED_LINE means the engine actually split/baked the touched contribution
and published the replacement and smudge together as one step under K25. Existing
whole-layer Freeze, ordinary smudge, and a planned split are not that event.

The View delivers receipts on the main thread only while the originating canvas,
document generation and UI lifetime still match. Late callbacks after drawing
switch, context replacement, Activity teardown or aborted bench are ignored. Abort
must invalidate delivery before restoring preferences. No receipt is emitted on
cancel, empty/clipped stroke, rejection, allocation failure or rollback. Internal
replay, preview/test-sheet, undo/redo and load must not create first-use notices.

The UI consumes these facts through SelectToast, with independent install prefs:

| Fact | Verbatim notice | Action |
|---|---|---|
| PIXEL_BRUSH_ON_LINE_LAYER | Brushes that blend with what's underneath become pixels. Everything else stays editable. | None |
| SMUDGE_CONSUMED_LINE | That line became pixels. | Undo for the validated step |

Set each seen pref only when that notice is actually presented in a valid foreground
UI. Deduplicate operation IDs as well as the once-per-install pref. If both facts
are legitimate in one operation, queue them so one toast does not immediately
replace the other; suppress repeats while a notice is queued. K26's realistic-paint
notice needs a separate fact/key when that engine path is enabled and must not be
inferred from these two facts.

Required review/tests before claiming completion:

| Boundary | Positive evidence | Negative evidence |
|---|---|---|
| Engine | Actual published pixel-on-lines step; actual atomic split/bake + smudge step | Cancel, zero change, refusal, OOM/rollback, whole-layer freeze, ordinary smudge |
| Deferred work | Fade completion delivers exactly once after publication | Early zero return and repeated frame/history notifications deliver nothing |
| Identity | Correct operation/generation/history token survives async queue | Late old document/old canvas/old context/aborted bench cannot show or mark seen |
| UI | Exact text through SelectToast, each independent pref once; queue preserves both | Replay/preview/undo/redo never consumes prefs; unavailable UI leaves them unseen |
| Undo | Consumed-piece action restores the one validated operation | Newer edit, different document, stale revision cannot undo unrelated work |
| Note 9 | Real qualifying strokes, first notice once and repeats silent; Undo picture/model verified | Preserved saved drawings and preference receipts; no synthetic callback-only acceptance |

Current source has no trustworthy SMUDGE_CONSUMED_LINE publisher. A9 permits a
documented engine dependency; it does not authorize simulating split or consuming
the D23 pref before the engine operation exists.
