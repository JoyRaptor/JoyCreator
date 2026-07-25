# Audit: transition index shifting vs undo

Opened 2026-07-25 (Opus 5) after fixing the delete case in `601383a`. This is a **scoped,
unfinished audit** — one instance is fixed and proven by reading; the rest are listed so the
next pass can work through them rather than rediscovering the shape.

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

## NOT yet audited — the remaining call sites

Each needs the same question asked: *does the undo step that reverses this restructuring also
restore the transition list?*

| Site | Call |
|---|---|
| `FaditorEditorActivity:19745` | `shiftTransitionsAfterSplit` (transition-seam split helper) |
| `FaditorEditorActivity:22173` | `shiftTransitionsAfterInsert` |
| `FaditorEditorActivity:22338` | `shiftTransitionsAfterSplit` |
| `FaditorEditorActivity:22346` | `shiftTransitionsAfterInsert` |
| `FaditorEditorActivity:22403` | `shiftTransitionsAfterInsert` |
| `FaditorEditorActivity:22948` | `shiftTransitionsAfterInsert` |
| `FaditorEditorActivity:23020` | `shiftTransitionsAfterInsert` |
| `FaditorEditorActivity:23353` | `shiftTransitionsAfterSplit` |
| `FaditorEditorActivity:23732` | `shiftTransitionsAfterInsert` |
| `ai/EditScriptApplier:537` | `shiftTransitionsAfterInsert` |
| `ai/EditScriptApplier:745` | `shiftTransitionsAfterSplit` |
| `ai/EditScriptApplier:927-928` | `shiftTransitionsAfterSplit` ×2 (double-shift — check intent) |

**Start with SPLIT**: it is the most-used structural edit in the app, so if its undo has this
gap, users are hitting it routinely.

`EditScriptApplier:927-928` calls `shiftTransitionsAfterSplit(clipIndex)` twice with the same
index — that may well be deliberate (a split producing two new boundaries), but it is worth
confirming rather than assuming.

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
