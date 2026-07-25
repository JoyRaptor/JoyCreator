# Audit: transition index shifting vs undo

Opened 2026-07-25 (Opus 5) after fixing the delete case in `601383a`, then **completed the
same session**. Every clip-structural undo path now restores transition placement, and the
rules are pinned by a JVM harness. One genuine gap remains, and it is NOT an index bug — see
"The one structural gap left".

## The bug shape

`Timeline` mutates `Transition.clipIndex` IN PLACE whenever the clip list is restructured:

- `removeTransitionsForDeletedClip(i)` — drops transitions at `i` and `i-1`, decrements every
  later `clipIndex`.
- `shiftTransitionsAfterInsert(i)` — increments every `clipIndex >= i`.
- `shiftTransitionsAfterSplit(i)` — increments every `clipIndex >= i`.

None of these are reversed by re-adding or re-removing a clip: `addClip(int, Clip)` and
`removeClip(int)` touch only the clip list. So **any undo path that restores clips without
also restoring the transition list leaves transitions attached to the wrong seams.**

That failure mode is nastier than data loss. The transition is still there and still plays —
just at a cut the user never chose. Nothing looks broken until playback or export.

## Fixed (all three structural edits)

- **Single-clip delete** (`EditActions.DeleteClipAction`) — snapshots at construction, restores
  on undo, and `execute()` now re-runs `removeTransitionsForDeletedClip` so REDO reproduces the
  whole delete. Verified `UndoManager.recordAction` does NOT execute the action, so the manual
  delete at the call site is not double-applied.
- **Dual-stream linked-pair delete** (`confirmDeleteLinkedPair`) — same snapshot/restore.

- **Split** (`EditActions.SplitClipAction` + the dual-stream `splitLinkedPartnerAndRecord`) —
  the most-used structural edit in the app, so this was the most-hit instance. The call site
  now snapshots BEFORE `shiftTransitionsAfterSplit` and passes it in; `execute()` re-applies
  the shift so redo is faithful.
- **Insert** (`EditActions.AddClipAction`, which is what the four "add clip / add image"
  sites all record) — fixed WITHOUT snapshot plumbing: an insert drops nothing and only
  increments, so the arithmetic inverse is exact. New `unshiftTransitionsAfterInsert`,
  harness-proven at every insert position.

Tools now available for the rest: `Transition.copy()` (deep — `paramOverrides` too, since
`clipIndex` is precisely the field a shallow copy would alias) and
`Timeline.snapshotTransitions()` / `restoreTransitions()`.

## Audit COMPLETE (2026-07-25) — every site walked

| Site | Call | Verdict |
|---|---|---|
| `EditActions.DeleteClipAction` | delete | **FIXED** — snapshot/restore (`601383a`) |
| `confirmDeleteLinkedPair` | delete | **FIXED** — snapshot/restore (`601383a`) |
| `EditActions.SplitClipAction` | split | **FIXED** — snapshot/restore, call site snapshots pre-shift |
| `splitLinkedPartnerAndRecord` | split | **FIXED** — same |
| `EditActions.AddClipAction` | insert | **FIXED** — exact inverse (covers all four add-clip/add-image sites) |
| `EditActions.DuplicateClipAction` | insert | **FIXED** — exact inverse |
| linked-pair add (`~22173`) | insert | **FIXED** — exact inverse in its `revert` |
| `ai/EditScriptApplier` ×4 | insert/split | **OUT OF SCOPE** — see below |

### The structural gap — ALSO FIXED

`FaditorEditorActivity` "insert a clip at the playhead" SPLITS the current clip when the
playhead is mid-clip, then inserts, but recorded only an `AddClipAction` — so **the implicit
split was not in the undo history at all**: undoing the insert removed the new clip and left
the original permanently cut in two. A pre-existing *undo-composition* defect rather than index
arithmetic.

Now recorded as ONE composite step, which is what it is to the user ("put this here"): undo
removes the inserted clip, re-joins the two halves back into the original, and restores the
transition list wholesale — a single pre-everything snapshot covers both the split's and the
insert's index shifts. The no-split path still records the plain `AddClipAction`, so the
common case is unchanged.

### Checked and NOT a bug — reorder

`Timeline.moveClip` deliberately does not touch transitions, so a transition stays at its
SEAM position ("between slots 2 and 3") rather than following a clip. `ReorderClipAction.undo`
is `moveClip(to, from)`, which is the exact inverse of `moveClip(from, to)` (traced both
directions), so reorder+undo round-trips cleanly and cannot corrupt placement.

Whether a transition *should* follow its clip through a reorder is a genuine design question —
most NLEs bind a transition to a specific cut — but the current behaviour is at least
self-consistent, and it is not the undo bug this audit is about. Recorded so the next reader
does not re-derive it.

### EditScriptApplier

The four AI-script sites apply a whole edit script; that subsystem does its own
snapshot/restore at script level rather than per-action, so the per-edit reasoning here does
not transfer. Worth a look when that subsystem is next touched — note `:927-928` shifts twice
with the same index, which may be deliberate (one split producing two boundaries) but is worth
confirming.

## How to verify without a device

The bug is pure model logic, so it does not need a phone:
1. Build a `Timeline` with N clips and transitions at known seams.
2. Perform the edit, then undo.
3. Assert the transition list — count, `clipIndex`, duration, type — is identical to before.

This is a good candidate for the JVM harness rather than a device pass.

## Harness landed (2026-07-25)

The "verify without a device" section above is no longer aspirational. The index bookkeeping
was extracted to `model/TransitionIndex` (Timeline delegates to it) precisely so it compiles
against nothing but annotation stubs, and `tools/jvm-harness/TransitionIndexTest.java` now
pins the rules — including the exact regression, `[0,2,4]` → delete clip 3 → `[0,3]` →
restore → `[0,2,4]`.

```bash
javac -nowarn -d tools/jvm-harness/out3 tools/jvm-harness/stubs/androidx/annotation/*.java app/src/main/java/com/fadcam/ui/faditor/model/Transition.java app/src/main/java/com/fadcam/ui/faditor/model/TransitionIndex.java tools/jvm-harness/TransitionIndexTest.java && java -cp tools/jvm-harness/out3 TransitionIndexTest
```

10/10 PASS. It also covers deep-copy aliasing (both directions — a shallow snapshot would
alias `clipIndex`, and a shallow restore would let the NEXT in-place shift corrupt the
snapshot and break a second undo), the delete-clip-0 boundary, and the non-index fields
(`paramOverrides` is a mutable map).

**This makes the remaining audit cheap**: the RULES are now proven, so each unaudited call site
reduces to one question — does its undo call `snapshotTransitions()`/`restoreTransitions()`?
No new reasoning about index arithmetic is needed.
