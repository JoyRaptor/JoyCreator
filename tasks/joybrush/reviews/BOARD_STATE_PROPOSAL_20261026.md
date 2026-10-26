# STATE proposal — boards, October 26, 2026

Proposal only: root owns `tasks/STATE.md`; this patch does not edit it. Reconcile
against current rows, do not replace STATE with an old copy. Source and receipts:
[residual recovery](BOARD_RESIDUAL_20261026.md), frozen canonical741fa0154.

## Proposed shared feature-map entries

| Feature ID / user capability | Where / availability | Reuse and help mapping |
|---|---|---|
| `joybrush.boards` — create/size/lock Image, Animation and Sprite regions | JoyBrush menu → Boards, integrated source; historical scoped agent-Note9 functional checks, current/owner full acceptance pending | BoardRuntimeController/BoardChromeView, core BoardDocumentOps/BoardSession/layout. Help ID `joybrush.boards.create-size-lock`: PENDING. |
| `joybrush.board-preview` — scope layer thumbnails, onion skin and held layers to a board | Select board; onion peg / layer runner-or-mountain marker. Source present, historical scoped agent checks; current G7a exact-frame/cache/visual gates open | Existing Activity adapters, core ownership and PlaybackClock; not a new compositor. Help `joybrush.boards.frames-holds-onion`: PENDING. |
| `joybrush.tile-pattern` — preview repetitions and wrap supported strokes into the tile | Image feature switch. Historical scoped agent-Note9 checks; realistic pencil/watercolour/oil remain explicitly refused; other existing unsupported-tool guards stay | Existing Tile/session/commit routing. No realistic-media wrapping or physical-pen claim. Help `joybrush.boards.pattern-limits`: PENDING. |
| `joybrush.sprite-roll` — exact grid sizing, ordered cell preview and separate all-layer swaps | Selected Sprite; lock enables finger sequence, separate feature arm swaps, stylus paints. Historical two-layer/Undo phone scope; current all-media/mask matrix not inferred | SpriteGridMath/CellRoll/SpriteCellOps + bounded thumbnail host; shared strip controllers in studiokit. Help `joybrush.boards.sprite-order-swap`: PENDING. |
| `joybrush.board-export` — board/range/frame PNG, GIF, frames, sheet and internal Studio/SpriteLab handoff | Board export icon. Existing integrated encoders/internal receiver; historical scoped artifact/import/reopen/video checks. Current combined/live-source/tween export gates separate | AnimExportPlan/Runner/SpritePacker, BoardExportCoordinator, JoyBrushBoardImportActivity + SpriteSheet parser. Internal staging is not arbitrary external import. Help `joybrush.boards.export-studio-spritelab`: PENDING. |

Root should turn source names above into checked relative links in STATE when
reconciling. The recovery report already contains exact clickable source paths.

## Proposed section5.6 additions

Add the five feature families above with level **BUILT; historical scoped
agent-device evidence**, not generic owner LIVE. State current source availability,
historical APK/fixture receipt limits, pending exact K/K10/G7a/API29 acceptance and
separate active runtime lanes. Prefer one compact grouped boards row if the root's
updated table already covers individual previews/exports elsewhere; stable feature
IDs should still map to help/reuse. No test count belongs in the broad feature map.

## Pending user-help structure

- Create, size and lock a board.
- Choose frames, holds, onion skin and shared layers.
- Draw a repeating pattern and understand unsupported-tool limits.
- Size Sprite cells; select their order; preview; arm swaps; undo.
- Export this board/range/frame; open a durable project in Studio or SpriteLab.

These are discoverability proposals, not completed help articles. Review actual
labels/current controls and unsupported capability rules before publication. Do
not advertise arbitrary external import, live editable-frame duplication, tween
playback/export, clips, realistic-media Tile support or all-line/media operations
from the historical raster board evidence.
