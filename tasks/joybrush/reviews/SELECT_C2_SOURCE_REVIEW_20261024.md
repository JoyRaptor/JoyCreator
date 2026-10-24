# Select delivery C2 review and C1 gates — October24

Reviewed supporting implementation cc1061634 and status98d925b70 on codex/select-lines.
Review worktree C:/Temp/jb-ux-review-20261024, branch ui/select-review-20261024.
The existing C:/Temp/jb-select-ux has staged benchmark/gesture/overlay work belonging to
the UI lane; it was left untouched. No build, installation, phone action or preview edit.

## Verdict

C2 SOURCE REVIEW COMPLETE WITH NOTES; C2 VISUAL ACCEPTANCE AND C1 PHONE PASS OPEN.
Follow-up: source findings1/2 below are addressed in b590e58c5; reviewed without executing
the new tests. Their original observations remain below as review history, not current blockers.
Caption follow-up: 2cbfbfb86 addresses the premature per-brush-priority sentence in finding3;
all three source notes are addressed. The underlying L3/A10 behavior and visual acceptance
remain open. Coordinator candidate79f2d4dba is a frozen integration candidate, not main or
a phone-accepted APK.
The delivery contains the A1–A8 source paths and uses the existing approved presentation
primitives. That does not prove their native appearance or gesture feel. The delivery's
reported122 passing app tests predate its final two fixes; final app/core/androidkit gates
are the builder/Lead's responsibility and were not rerun or independently claimed here.
The implementation note still correctly marks final verification pending.

Use K24 as the final presentation authority: solid gradient/cut-out switches, visible
contents inside gradient rings for tools/swatches/layers, full pills, borderless88% paper
floating chrome. The brief's shorthand “never an outline” does not reverse the owner's
explicit ring choice. The old page's palette/default trial text cannot reopen K23/K24.
The approved page is a reference only; the owner ended preview editing.

## Findings

1. **P2 — expanded popovers need an overflow path before small-window acceptance.**
   SelectSettingsView.kt:15 is a plain vertical LinearLayout. It now holds four40dp choice
   rows, their headings, explanatory text and one/two40dp actions (:29–44). Popovers.show
   directly attaches content (:52) and only clamps its position (:126,:152); it supplies
   no scrolling or available-height content adaptation. More now has additional actions
   too. In a window shorter than the content plus top reserve, moving the box cannot make
   the bottom controls reachable. This is a source-derived overflow risk, not a claim that
   the Note9 portrait screenshot failed. Add a bounded scrollable content area or equivalent
   fit behavior; check all actions with Paste present, a short window and larger system text.
   Preserve pill hit targets and the ordinary outside-tap dismissal behavior.

2. **Minor D2 source mismatch — locked-line option is unconditional.**
   Spec D2 says “Pick locked lines too” appears once a line has been locked. Settings :43
   always constructs it and Controller.showSettings passes its current preference only,
   not a reveal condition. Resolve this against D2 before marking exact page parity. Do
   not silently reinterpret the final styling or remove a persisted way back when resolving it.

3. **Existing L3/A10 gate, not new acceptance:** Settings :36 promises each brush's own
   Editable lines choice takes priority. The delivery itself says the admission/default
   choice remains unsettled. That sentence must be true in the integrated implementation
   before A10/D2 is accepted. Keep provisional Q2/Proko/vector-versus-raster quality decisions
   separate from a screenshot of an ON switch.

## Follow-up source review — b590e58c5

Both settings and More now route through showScrollableMenu. SelectMenuScrollView caps
content height against the parent measure minus reserved top/gap space and lets the child
keep its natural height; it does not shrink40dp choices. The shared Popovers outside catcher
is unchanged. Source finding1 is addressed; actual root-layout measurement, scrolling and
touch reachability still require the coordinator's integrated checks and the phone capture.

Settings now receives a reveal condition. SelectPreferences retains revelation after a
locked line is encountered, preserves any existing pick_locked preference (including false),
and never hides an enabled setting's Off control. Controller checks visible locked records
on opening settings and selected locked records on refresh. Source finding2 is addressed;
the persistent way back survives unlock/off/reopen in the supplied regression scenarios.

Read all five added tests: short window with1.8 text scale and Paste reaches the last choice;
long menu row targets and resize; outside dismissal without action; reveal lifetime; and
legacy false migration. These are meaningful source checks, not executed evidence here.
The More-height test uses a representative list rather than opening Controller's actual More;
the final root-hierarchy/phone pass must still cover that production path. No new material
source issue was found in this bounded diff. L3/A10 truth, C3 and all visual/phone gates remain.

## Caption/candidate source review — 2cbfbfb86 / 79f2d4dba

The caption now requires App.perBrushLinesOverrideShelf(), default false, passed explicitly
to Settings. Candidate79f2d4dba has no production override enabling it, so the sentence is
hidden until a host implements and explicitly enables actual per-brush priority. Existing
shelf controls remain. Read the new regression: default/explicit false hide the sentence,
true adds exactly one, and the remaining text is unchanged. No material issue found in
this bounded change; this is test-source review, not an executed test result.

Candidate retains the exact b590 menu wrapper/preferences source. The coordinator's new
context-loss and paint-mask preflight changes were reported UNEXECUTED and are outside
this caption review; no approval or test result for them is implied. Do not rebase or edit
the frozen candidate while its builder validates it. Current candidate still has null
liftPixels and empty dimRest; C3 and Phase B are not implemented by a preference or design
proposal. Phase B still requires Lead's implementation approval. No build/phone lock,
device action, watcher control, preview edit or production source change by UX.

## Source checks that agree with the design

- D2 adds Select all lines, conditional Paste, Pick locked lines and Dim the rest. Every
  action/choice has kit.label text. Choice height40dp and radius20dp preserve full pills;
  chosen words use DST_OUT over SelectAccent. Controller wires persisted finger/pick-locked/
  dim preferences and closes D2 before Select all/Paste. A5 only wires fadeRest; it does not
  implement the C3 veil (CanvasSelectionHost.dimRest is still empty).
- More adds Lock/Unlock, Group/Ungroup, Invert and Paste using the existing labelled menu
  row pattern. HSB remains the three existing sliders; it previews relative changes and
  returns each slider to neutral after its final edit. Actual cancellations/history and
  readable white/dark appearance still need the integrated pass.
- Layer rows are conditional in Activity:1511–1518 from SelectLayerMenu.model, with plain
  labels and Undo wording. Their new scope rules remain model/engine proof; source presence
  does not show how the rows fit or whether the actual transaction works on the phone.
- Nudge stores original selected geometry at drag START (SelectTool:661), converts full
  widths through live view zoom (:168), and draws before other overlay marks using ONE
  saveLayerAlpha(...,51) (SelectOverlayView:47–64). This is the correct20% group alpha,
  rather than independently raising opacity at every overlap. END/CANCEL/new selection
  clear the ghost (:640,:672,:704). This is a centreline/weight ghost; it does not establish
  texture-perfect watercolor/picture-tip replay or prove phone rendering at two zooms.

## Evidence owed before C1/C2 close

Lead first integrates supporting source and its dependent LEAD PATCH commits, checks the
two final fixes, and names the APK's exact revision/hash. A branch screenshot from another
APK cannot establish acceptance. Then use the existing13-step C1 checklist in
SELECT_LINES_GAPS_20261023.md §4, retaining per-gesture one-step Undo and save/reopen checks.
Keep white/dark paper, ordinary and reduced motion, pen/button/finger routing in that pass.

Minimum additional delivery captures/checks:

| Capture | What must be visible/observed |
| --- | --- |
| D2 on white and dark paper | All four actions/preferences and existing two settings; Paste absent/present correctly; full pills/cut-out text, readable labels, no clipped bottom controls. Repeat a short-window/larger-text fit check. |
| Layer menu, applicable/inapplicable cases | Select all lines, scoped Rasterize Down, Flatten Lower in their correct place; unavailable rows absent; actual one-step result/Undo preserves layers above and restores lines. |
| Nudge before/mid-drag/after | Original shape remains stationary at20% while the edited shape moves; two zooms; crossing lines do not become a darker ghost from repeated compositing; release/cancel removes it. |
| More and HSB | Contextual Lock/Unlock, Group/Ungroup, Invert, Paste and Adjust; different selected colours retain relative hues; preview/cancel/final release and Undo match the drawing and neutral sliders. |
| Colour drag-off | One successful recolour; returning to the dot/cancelling changes nothing; the no-selection eyedropper still behaves normally. |
| History and reopen | Move→Undo/Redo keeps/refits selection, Delete→Undo recovers it, surviving subsets remain selected, Open cannot resurrect old addresses; save/reopen preserves lock/group data. |

Use only the sandbox Note9 under the current device runbook/lock and readiness check;
never change rotation settings or use monkey. No Note20 work. No device run is initiated
by this review. C3 outside veil remains separate and unfinished; A9 Slice and A10/L3 plus
Phase B approval gates remain open. The agreed cyan/full-pill/borderless look is unchanged.
