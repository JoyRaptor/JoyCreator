# UX functional completion — current inventory

Owner instruction October26: finish working controls/features and the approved UI;
brush aesthetic tuning is deferred to separate sessions. Read-only source audit at
canonical4b9ed850c, C:/Temp/jb-select-reconcile-20261024, independently reviewed by
primary UX specialist and two bounded Luna audits. No canonical edits or jobs.

## Ready / verified, with limits

| Area | Actual state / next proof |
| --- | --- |
| Approved look | K24 cyan gradient, full pills, borderless paper chrome implemented. Older palette comparisons are history; no preview changes requested. |
| Basic line selection | d11 exact Note9 run20/0 and11 registered tan-paper screenshots reviewed; tap/cycle/count/lasso/transform/nudge/duplicate/delete/group/lock/pan have scoped evidence. Lock behavior is metadata; final pan has no post-pan PNG. |
| A10 switch | Native Switch4 passed on97; primary independently read XML/hash. Merged4b9. Phone white/dark/sheet/badge proof remains owed. |
| Row/bar/settings wiring | Controller has callbacks for line Copy/Paste/Lock/Group/Invert, Rasterize, HSB, filters, Select all, Pick locked and finger setting. Source presence does not close all phone interaction/visual cases. |
| Hover labels | New row/bar/menu/settings controls use ChromeKit.label; native Switch keeps its text and accessibility. Actual stylus feel remains separate. |

## Concrete next work / dependencies

| Requirement | Evidence / ownership / completion gate |
| --- | --- |
| Empty pixel More menu | SelectRowView.showPixels always offers More; Controller.showMore can create no items with no clipboard and no colour capability. UX owns isolated4b9-based repair in C:/Temp/jb-ux-control-completion-20261026: capability gate More, avoid empty row/popup, preserve real available actions; meaningful regression, then Coordinator-run verification. |
| A10 exact device acceptance | Select owns typed Python manifest/runner and A10 class; root owns runtime integration/BenchRunner/lifecycle. Needs per-brush ON/OFF persistence, always-pixel behavior, white/dark sheet/badge checks and successful publication notices on exact installed APK. |
| Menus/HSB/D2/layer rows | Source exists; obtain registered captures and real interactions on current APK, including HSB preview/cancel/commit and one-step Undo, locked-line reveal, menu scroll/reachability, layer actions and during-drag ghost. Historical C2 repairs are not complete visual acceptance. |
| Phase B paint selection | Host422 returns null from liftPixels;425 paintClip has no brush consumer. Need real mask/lift/preview/commit/cancel and pixel Fill/move/delete/flip/duplicate/copy/invert/HSB/painting clip, joint Undo and data-safety/device proof. Root engine dependency. The empty-menu repair implements none of these. |
| C3 outside fade | Host427 discards dimRest. Settings preference is wired but effect is absent. Root renderer hook must preserve true source alpha/operators, transformed selection and non-display isolation. Contract SELECT_C3_DEPENDENCY_CONTRACT_20261025.md; no UI veil substitute. |
| Slice / touched-section smudge | Pure planner proof only. Runtime splitting, source seed/phase/domain preservation, touched-only bake, no ghosts, atomic undo and successful-publication toast remain engine work. |
| K26 layer ordering | Actual compositor/media connection: editable lines above realistic paint, save/replay/undo correctness and native proof. Source contract alone cannot close it. |
| Physical pen/finger | Final creator-assisted pen hover, button lasso, palm/finger distinction, count and nudge feel. Prepare the functional build first; ask owner only when this concrete pass is ready. |

Line HSB was investigated rather than assumed broken: SelectTool.onSelectionChanged
acquires host.liftLines immediately at735 and retains the lift while selected.
CanvasSelectionHost.canAdjustColour requiring activeLift therefore does not prove an
idle-selection bug. No speculative HSB repair is proposed.

## Roadmap accuracy / sequencing

Root's refreshed ROADMAP.md and AGENT_START_20261026.md were reviewed; their current
ownership,4b9, d11/Switch4 evidence and explicit PhaseB/C3/Slice/K26 limits match this
audit. Older STOP '#8 stub/#9/#11 open', palette-trial wording, pre-K25 global-switch
gap and 'candidate not merged' passages are historical, not the next-agent queue.
Do not erase earlier failed runs or transfer older suite totals to new source.

Root owns engine/View/Activity/BenchRunner hot files and integration; UX owns the
isolated control repair and acceptance review. No build/phone slot is assigned to
UX. These are completion gates, not optional polish. Brush fidelity is deferred;
functional refusal/dependencies and drawing preservation still require proof.
