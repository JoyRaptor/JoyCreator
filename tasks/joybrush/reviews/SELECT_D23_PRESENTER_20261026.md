# D23 isolated pure UI presenter — source only

Assignment: Select specialist, based on coordinator ecc2ea8e8; isolated branch
`codex/select-d23-presenter-20261026`, checkout `C:/Temp/jb-codex-select`.
Only new presenter/types/preferences and tests; no Activity/View/engine/SelectToast
edits, receipt producer or activation. The engine adapter may refine these types.

SelectNoticeReceipt is an internal immutable non-data class with kind, opaque
document generation/operation/history-head identity tokens and layer ID. Only a
future trusted successful-publication adapter may issue facts. No factory derives
them from stroke-end, line creation, a brush name or whole-layer Freeze.

SelectFirstUseNotices queues at most one of each D23 kind, suppresses duplicate
in-flight offers and validates current context again at presentation. A renderer
must confirm actual presentation through onShown; enqueueing, failed/null/throwing
show, cancellation, stale context and delayed callbacks do not consume a seen pref.
The two exact messages use independent keys. If persistence fails after a real show,
the presenter reports that failure and prevents repeat spam within its own lifetime;
durability across process restart is unproved and must not be reported as achieved.

Document/lifecycle invalidation drops callback/action ownership before cancelling
the surface. Smudge Undo is a one-shot call into an injected ATOMIC guardedUndo
transaction, carrying the exact receipt. That seam must validate generation, layer
and current history head and perform that step's undo together, or refuse. A separate
precheck followed by unguarded undo is forbidden. Stale/hidden/disposed actions do
nothing; a newer edit refuses the old action rather than undoing unrelated work.

Ten prepared tests cover renderer confirmation, duplicate/same-operation kinds,
queue cancellation, document switch, failed show, synchronous/reentrant finish,
failed persistence, stale/new-history Undo, valid/disposed Undo and independent
real SharedPreferences keys without unrelated edits. UNCOMPILED/UNRUN pending the
shared focused slot. These are pure renderer-contract tests, never engine/GPU or
actual SelectToast presentation evidence.

Still required: Lead review and focused compilation/tests, actual publication
producer, View/lifecycle adapter, actual SelectToast onShown/onFinished contract and
device once-per-install/guarded-Undo proof through real qualifying operations.
K26's separate notice is not implemented here. Neither notice is activated.
