# Select lines implementation — 2026-10-24

Branch: `codex/select-lines`. Base: `e5a0f02c0`. Integration belongs to the Engine Lead.

## Source implemented, acceptance pending

| Brief | What the owner can do after integration | Current evidence |
|---|---|---|
| A1 | Copy lines across drawings in the same app session; Paste imports their kept brushes, assigns fresh line/group identities and selects the copies. Copies remain in place when their centre is visible, otherwise they centre in the current view. Paste targets the current cel under that destination. | Clipboard/host regression tests passed, including frame boundaries. Translation uses `LineTransform.apply`, so the vector lane's wash-frame extension is honoured. |
| A2 | Lock/unlock, group/ungroup and invert line selections; choose whether locked lines can be picked. | One replacement transaction per metadata action. Group expansion also respects the locked-line filter. Save codec round-trip coverage added. |
| A3 | Shift each selected line's hue, saturation or brightness while retaining its own colour relationship. | Preview uses the lifted lines, writes no history, and release commits one edit. Each slider returns to neutral after applying its relative change. |
| A4 | Undo/refit a moved selection; recover the selection after undoing Delete; retain surviving lines when only part of a selection disappears. | Late snapshots with unchanged selected records preserve an unfinished drag. A drawing epoch prevents selection recovery across Open. |
| A5 | Open Select settings for Select all lines, Paste, Pick locked lines too and Dim the rest, alongside the existing settings. | Preferences and `Picks.fadeRest` wired. The outside veil remains UI specialist C3; this does not claim it works. Phone screenshots pending. |
| A6 | Select all lines or Rasterize Down from a layer's menu; Flatten Lower as one existing engine flatten transaction. | Rasterize scopes to this layer's selected lines, otherwise all its lines. Flatten Lower preserves layers above and refuses locked/frame layers or a clipped layer immediately above the result. Hidden lower layers are removed in the same undo step without contributing visible paint. Phone proof pending. |
| A7 | Drag off the row's colour dot or strip swatch to recolour the selected lines with the sampled colour. | Only the final eyedropper callback commits. Returning to the dot or cancelling does not take a colour. No selection keeps the ordinary eyedropper path. |
| A8 | See the original line shape at 20% during a nudge. | Original picker geometry is held for the drag and drawn through one 20% overlay layer; release/cancel clears it. Phone screenshot pending. |

## Verification

- Initial draft: `:joybrush-android:compileDebugKotlin` **PASS**, 8m07s, under `lead-build.sh` with a 768 MB Gradle heap. This predates the final review fixes and layer actions; it is not the final compile claim.
- App selection tests plus Kotlin compile: **PASS**, 122 tests across 18 XML suites, zero failures/errors, 7m13s. This includes destination-cel and exact-layer-scope regressions, but predates the two final review fixes described below. Scratch log: `jb-select-final-tests.log`.
- Final review fixes: `614c6eb1f` publishes the line snapshot after Flatten Lower; `cc1061634` restores exact addresses after a refused edit instead of expanding their groups. Final rerun is still owed.
- Core and androidkit full suites: **not run to completion**. The standalone request remained outside Gradle at the shared wrapper's 2.2 GB free-RAM admission check; cancelled before Gradle started. No suite pass is inferred from the app check.
- Phone: no install or acceptance claim yet.

## Dependencies to the Lead and vector/UI specialists

**A9 / Slice — engine hook required.** No Slice preset is shipped in `joybrush/brushes/index.txt` or its brush files.
`BrushValidate` rejects `size.base <= 0`, and `ToolMemory` clamps ordinary sizes above zero. The canvas's current eraser
path does not call `InkErase`/`VectorEraser`. `LineEdit` exposes Add, Replace, Delete and Bake separately: splitting one
record into two needs an atomic remove-and-add batch with one history step. Adding a zero-width JSON preset now would
either fail loading or silently behave as a raster eraser. No invalid or misleading preset was added. The Lead needs to
provide the zero-width cut admission and atomic split transaction before the shelf preset can be made usable.

**A10 / G11 — waits for L3.** The vector specialist reports a per-brush candidate (`e9b6ff9b6`,
`BrushLinePreferences`, watercolour default ON), with unchanged Select-host callback signatures. **Updated owner
ruling K25 is now settled:** each brush has its own flag; defaults are ON for the line set/watercolour vector brush,
OFF for painting brushes, no global fallback, and no switch on pixels-always brushes. The current-mode badge has
both raster and vector forms. Engine-default integration/acceptance is still required before A10 is accepted;
the earlier claim that the owner's choice was pending is superseded.

**Ring Bend / G12 — waits for L4.** `LineTransform.kt` and the watercolour wash-frame implementation remain vector-owned.

**Phase B — gated.** Paint lift/clip work starts only after Phase A is merged and the Lead approves the pixel-selection
design note required by the brief. No pixel engine work is included here.

## Integration

Supporting Select/core implementation must be applied before the separate `LEAD PATCH:` commits, which name its new
helpers. The branch as a whole retains all those dependencies. No merge to `joy-creator` is performed by this lane.

- Supporting source: `cc1061634` (apply first).
- Lead patches, in order: `b87f1273c`, `38e699cd5`, `2e8cf6120`, `6f6460736`, `d192b6197`, `66f180815`, `5e4eb7ffe`, `614c6eb1f`.
- State/roadmap update: `98d925b70`, plus this follow-up evidence update.
- Branch pushed; Lead and UI specialist were sent the integration/review request. C1/C2 phone and look review remain open.
- Latest `origin/joy-creator` inspected at `a94933ad7`; no Select-lane integration was found.

## Cleanup status

The worktree and its current test evidence are retained because integration, final validation and dependent slices
remain unfinished. No shared phone/build lock was removed. Other specialists' `jb-select-*` folders and older logs
are not owned by this lane and were not deleted. No build is left queued by this lane.

## C2 source follow-up — 2026-10-24

Coordinator requested the two bounded fixes from UI review `3e0d41e5d` while preparing isolated integration.
Supporting-only commit **`b590e58c5`**, six Select source/test files, is pushed and sent to Coordinator/UI.

- D2 and More use `SelectMenuScrollView`: height is bounded by the parent's measured constraint with the top-bar
  reserve and placement gaps deducted. Rows retain their natural heights and full pills; the original `Popovers`
  surface and outside-tap catcher are unchanged. This does not change general app popovers.
- D2's locked option starts hidden, reveals when a locked line is encountered, and remains available after unlocking,
  turning the setting off, switching drawings or reopening. Existing persisted `pick_locked` values, including false,
  retain the control so migration cannot strand a preference.
- Five tests added: settings at 240dp/1.8 font scale with Paste and last-choice reachability; nine More-length rows
  with minimum targets and resize; outside dismissal without action; reveal/off/reopen; legacy false preference.
- `git diff --check` passed. **Tests not executed here.** Coordinator owns final integrated suites and received both
  test class names. No heavy build or phone action was started for this follow-up. C1/C2 native acceptance remains open.
- A9, A10, G12 and Phase B gates are unchanged. No further Lead-owned source was edited.

### C2 truth follow-up

Supporting-only **`2cbfbfb86`** adds `SelectController.App.perBrushLinesOverrideShelf()` defaulting false, passed
to the settings view. The unchanged caption “Each brush’s own Editable lines choice takes priority.” is included
only when true. Current global-only integration therefore makes no per-brush-priority claim. L3/Lead can override
the capability in its own host patch. The existing D2 heading, choices and styling are unchanged.
One view test checks default/explicit-false absence, explicit-true presence and unchanged other words.
`git diff --check` passed; the test remains unexecuted by this lane and is handed to Coordinator's integrated run.
No Activity/engine/View edit, build or phone action.
