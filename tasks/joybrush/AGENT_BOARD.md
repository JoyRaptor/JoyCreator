# Joy Brush shared agent board

## Owner clarification to Boards Specialist — 2026-10-02

Selected/active board: layer-selector previews show only that board's bounds. Passive/unselected
board: normal layer previews return. Applies to every board kind; animation previews follow the
board's current frame. Spec: JB-3.00a §G7a, with the owner's words preserved. Documentation only;
not implemented or phone-verified. Please include this in the board chrome/integration handoff.

Working handoff for Codex, OpenCode and other harnesses. Lead: Codex while Claude is on cooldown,
per JoyRaptor. Priority: dependable painting first, then board-region animation. Read
`START_HERE.md`, `ROADMAP.md`, `LEAD_RULINGS.md` and `specs/JB-3.00a_boards_owner_model.md`.
This board supplements those authorities; it does not replace the owner's requirements.

## Where to put information

- File ownership and device claims: `tasks/LANES.md` (claim before edits).
- Build assignments and status: `ROADMAP.md`, maintained by its assigned orchestrator.
- Engineering decisions: `LEAD_RULINGS.md`, maintained by Lead.
- Builder/auditor messages: append a dated entry here with agent, task, commit/worktree,
  files, evidence, blocker and requested recipient. Keep older entries; no giant transcripts.
- Detailed reviews: `reviews/<task>__<reviewer>.md`; link the review from the entry.

A written message is not delivery confirmation. The recipient records acknowledgement here.
Do not claim phone verification from a build or test report. Builders and reviewers distinguish
core tests, driver checks, app compilation and actual phone behavior. No personal identities,
device serials, credentials or artwork in this file.

## Lead handoff — 2026-10-01

FRAME_PROJECTION foundation is tested but **not the finished region-animation feature**.
Local primary tree is `joy-creator`; backup branch `codex/frame-projection-foundation`.
Files: GlPaintEngine, JbCanvasView, CanvasSnapshot, CanvasPng, CelProjection, UndoLog,
JoyBrushActivity and FrameProjectionTest. Evidence: `INTEGRATION_20260930.md`.
Whole-layer animation creation shortcut removed after reading the owner's new board model.
Do not restore it or label this region animation. Existing legacy animation archives remain readable.

Boards Specialist: owns locked board design and chrome in an isolated worktree. Before integrating
frame stores, specify/build JB-3.01b and JB-3.01c: shared outside pixels, per-board frame stores,
pixel-exact edge splitting, one stroke/one undo, held layers, saved Board.currentFrameId and migration.
Current foundation stores entire layer cels, has session-only frame selection and supports one board.
Its duplicate/link/undo ownership work is reusable; projection and rendering need region adaptation.
Export-sheet reuse gap (K6) is recorded by the specialist and still needs Lead review.

Paper Specialist: JB-9.03b, JB-9.06 and JB-9.08 pushed to origin through 0b7546b5. Their reported
test/build evidence is recorded in their reviews. They have not been integrated into this primary
working tree or installed on the phone by Lead. Audit preview/export blend parity before promotion.
JB-9.07 Paper sheet is the next integration dependency; image generation remains paused per owner.

Muse/Bunny: please link each completed build and cross-audit here. Muse's JB-8.01b claim is in
MUSE_LOG.md, isolated worktree and planned branch muse/JB-8.01b; Lead has not reviewed it yet.
The frame foundation is available for an adversarial audit; do not overwrite its staged/local files.

## Integration blocker and resource priorities

Read-only merge-tree found an add/add disagreement in JB-3.00a between local HEAD and origin.
No merge was attempted and no conflict resolved. Back up work separately. Do not reset the dirty
primary tree, blanket-stage files, or merge the frame foundation into the specialist's worktree.
The new region rules are accepted; the locked local §K and remote changes must both be preserved
through an authorized integration path. Paper and frame hot files also require fresh overlap review.

Frontier review required before release: region ownership/migration; paint-save/undo safety;
GPU versus export parity (including masks, clipping and non-NORMAL blends); bounded memory and
malformed-file behavior in importers. This is review allocation, not a blanket rewrite judgement.
Lower-cost agents can build narrow specified helpers and tests in isolated lanes; passing cross-audits
do not substitute for end-to-end phone proof. No build is flagged for wholesale rewriting yet.
