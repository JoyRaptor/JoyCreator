# Select UI hunks and A10 badge handoff — October24

Read-only handoff for the Coordinator and Select builder. Source reference
origin/ui/select-accept at43d8588ef. Integration candidate55b06a3db remains frozen under
its own validator; no production/build/device/other-checkout changes from this review.

## Badge answer

No reusable K25 mode-badge renderer is published on the inspected UI branch. K25 specifies
the exact shapes; JbIcon.RASTER is the separate24px Rasterize Down action glyph and is
not the drawer's12dp dotted raster mark. Implement the K25 geometry directly in a small
native Canvas helper in Select support, reusable by drawer tiles. No bitmap generation,
alternate badge style or extra owner choice is needed.

- Box12dp at tile bottom-right, about80% drawer ink; use the tile/drawer's normal ink,
  not cyan selection fill. Raster dots have radius1.3 at (10,2),(10,6),(10,10),(6,6),(6,10),
  (2,10) in a12×12 coordinate space.
- Vector curve: stroke1.3, round caps, path M2.5 9.5 C2.5 4 9.5 8 9.5 2.5. Solid2.2×2.2
  square nodes centered at both endpoints; do not substitute circular nodes.
- Resolve current mode from the SAME per-brush preference/admission L3 actually consumes.
  Always-pixel brushes always show the raster mark regardless of a stale stored true value.
  Keep preview art/name clear and tile hit/hold behavior unchanged. Hover labels follow K25.
- Sheet switch is per brush, not a global fallback. The ⋯ row is a shortcut to that same
  flag. No switch for always-pixel brushes; show the exact D22 sentence instead. Defaults
  and the wording are now settled by K25, not the older provisional A10 note.
- Do not enable the older “per-brush priority over the shelf” caption merely because L3
  landed: K25 has NO global fallback to override. Retire the obsolete global control;
  only the current-brush shortcut can mirror the sheet flag. The prior default-false
  caption gate was a truthful interim protection, not the final K25 settings design.
- K25 amends Smudge to split first and bake only the touched section, one Undo step.
  “Smudge paint only” ignores lines. The conversion/Undo toast must follow real successful
  engine publication. K26 lines-over-realistic layering and its one-time toast also require
  the actual engine capability. A control or toast cannot stand in for L3/split adoption.

K25 is in5cfa826e7; K26 ineb10a11fd. Read these plus the current A10 addendum before
implementing; do not restore the obsolete D22 pixels-only10dp badge or a global mode flag.

## UI source to combine, in dependency order

| Commit | Owned source hunks and dependencies |
| --- | --- |
| 9b32f3fe6 | SelectBench.kt + SelectGestures.kt, BenchRunner debug dispatch, select_bench.sh; SelectTool.host exposed read-only as val for the bench, TransformOverlayView.rotateHandlePosition(float[]) read-only accessor. These are benchmark hooks, not artwork edits. |
| f2d1b7f4d | New SelectPlacement.kt and tests; Controller chrome exclusion/placement and paper-composited contrast; Bar chosen-word contrast; BoxHost/Tool body-tap handback; benchmark/runner corrections and two ToolEdit tests. **Body-tap handback needs the cancellation correction below before acceptance.** |
| 4f35f422f | Count placement below the box uses its bottom edge; bench's finger-pan check is Brush-in-hand (Select-in-hand intentionally picks/lassos, D1.1). Depends on f2 placement signatures and benchmark classes. |
| 43d8588ef | Evidence log for UI run1..run3 and required per-step bench procedure. Documentation, not new phone proof of the combined Select delivery. |

Combine hunks with current A1–A8/C2 source rather than replacing whole Controller/Tool
files with the UI branch's older versions. Preserve clipboard/metadata/HSB/history paths,
scroll/reveal/caption gates, pending/refusal handling and the candidate's regression probes.
Controller/Tool and BenchRunner overlap. Any actual conflict is re-applied on the new base
by its owner; do not discard either side or resolve it by taking a full stale file.

The UI contrast patch intentionally evaluates ink against the88% paper backing. Chosen
bar content fills dark on light/kraft paper for legibility; dark paper retains the cut-out.
Preserve that fix when combining newer settings/menu changes. Keep the completed cyan
gradient, full-pill chrome and borderless floating surfaces; no preview work.

## New source finding: a cancelled body contact must not replay a tap

On origin/ui/select-accept, BoxHost.cancelGesture() cancels the preview, then calls
tool.tapFromBox if a recorded single body contact is younger than300ms. But
TransformOverlayView sends ACTION_UP and ACTION_CANCEL through onUp(host, clean), and
both uncommitted paths can call host.cancelGesture(). Thus DOWN on an overlapping selected
line followed by CANCEL can choose a different line. Cancellation must never be a new pick.

The new ToolEdit test calls cancelGesture directly as its “clean tap”, so it cannot
distinguish these two paths. This finding concerns adopting f2 unchanged; it is not a claim
that the older frozen integration candidate already contains the bug.

Lead/Select builder should separate a clean unmoved BODY-UP notification from cancellation (or pass a
terminal reason), preserving existing Studio defaults. Only clean UP may replay the pick;
true cancellation, detached/retired gesture, corner tap and interrupted pinch must not.
Add actual overlay DOWN→UP and DOWN→CANCEL traces: clean body UP cycles once; CANCEL
preserves selection/count/preview and writes no history; moved/two-contact/cancelled pinch
does not replay a body tap. No UI source repair was made by this review.

## Readiness and evidence boundaries

- Coordinator owns exact combined validation and integration. Do not edit its frozen source
  during testing. Current red-evidence run and any fixture correction are not a green gate.
- Watercolor owns the current phone window; Select waits for the bounded handoff. No bench,
  installation, rotation change, watcher operation or phone-lock action by this review.
- After each implemented step, use the owner-directed select_bench.sh <out-dir> on an
  identified exact-source debug APK on Note9 under the current runbook/phone lock. Keep
  the runner/bench in sync with9b32/f2/4f35. Never use monkey or the Note20.
- Add bench checks/captures for A10: each eligible brush's on/off sheet and matching drawer
  mark, an always-pixel/no-switch sheet, white/dark ink readability and the once-only toast
  events. Existing UI runs are not proof of these new features. Preserve JSON, PNGs,
  package identity/hash, temperature record and any failures; a successful launch is not
  a completed bench.
- UI branch documents basic phone bench runs at C:/Temp/jb-select-ux-ops/run1..run3.
  That is prior-source evidence, not a fresh execution or whole C1/C2 acceptance in this
  review. A1–A8 history/copy/metadata/HSB, latest geometry, L3/K25/K26 and C3 require their
  own combined evidence. The synthetic pen bench also does not prove physical pen feel.
- C3 outside veil remains separate. Phase B's design proposal is not implementation
  approval. Existing staged/unstaged work in C:/Temp/jb-select-ux is preserved.

Technical cancellation warning and badge reuse recommendation were reported to JoyBrush
Lead Coordinator under the owner's standing technical-coordination authorization.
Coordinator acknowledged the edge and delegated correction/regressions to the Select
builder; its frozen candidate remains untouched. Separate script hardening is also owned
by that lane. This review performs neither fix and makes no new execution claim.
