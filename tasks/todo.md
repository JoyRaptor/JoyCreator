# Launch plan (drafted 2026-10-15 from six read-only audits in tasks/audits/)

## Owner request October24: audit and finish vector watercolour
- [x] Audit Claude continuation and update STATE/roadmap with proof levels.
- [ ] Finish/integrate/verify remaining L2–L7 in isolated vector/watercolour-finish-20261024.
- [ ] Preserve source/evidence now; remove lane temporary files only after completion.
Evidence: joybrush/reviews/WATERCOLOUR_COMPLETION_AUDIT_20261024.md.

## Owner request October 23: finish vector/tween parent plans
- [ ] Complete the ordered implementation and acceptance work in `tasks/joybrush/VECTOR_TWEEN_COMPLETION_20261023.md` (isolated `codex/vector-tweens`; existing external lane work preserved).
- [x] Independent core/schema/export slice implemented; full core2552 (4 optional skips) and backend1061 green. Owner retained engine hot files with Engine Lead; copied-frame/GPU/UI/phone completion remains with that lane.

Rule: nothing below marked DECISION gets built until the owner picks.

## A. Studio (finish first)
- [x] Sprite pin links, visualizer parent-follow, string resources (150 + 7 tool labels), token sweep, drawer inventories.
- [ ] Sprite warp net looks detached from the sprite (owner report 2026-10-15). Sonnet agent investigating; verify on phone.
- [x] Dialogs: the editor theme already styles dialogs; the one off-theme site (pin chooser) fixed. Remaining dialog work is strings and 11 non-MDC sites (studio_dialogs_audit.md).
- [x] Hover labels: audit said 201 uncovered, but reading each site showed helpers already name nearly all; the real gaps (bottom-bar tools, visualizer layer buttons, B/I/U, text motion, pivot, export stripe) are labelled.
- [ ] Drawer helper duplicates (studio_tools_audit.md top 10): TextOverlayDrawer.Kit aliasing ObjectDrawer.Kit, Kit.setChipOn View overload, grip pill, header toggle names.
- [ ] Colour picker hex field wraps on the phone (colour helper: owner said do not touch).

## B. Lobby (lobby_audit.md)
- [x] Joybot counted drawings/exports as recordings (fixed 35945e4c).
- [ ] DECISION: Viz Lab room has no screen: remove until built, or build one.
- [ ] DECISION: floor words (Bits -> Home, Packs and Setup both -> Setup, Finder -> Lab): real destination or cut.
- [ ] Move project and drawing reads off the main thread in onResume.
- [ ] Hero truth: Capture shows "Back camera" always; Sprite Lab/Avatar hero tags say LAST SHEET/CHARACTER over a project name; hero New goes to Studio list; storage readout uses an assumed 9 MB/s.
- [ ] ~40 lobby strings in code, none translated.

## C. Library and shared storage (storage_library_audit.md)
- [ ] Library (Records tab) shows only recordings, photos, exports. Make it read the Joy Creator folder index, add kind chips and working search.
- [ ] About a dozen storage roots still outside the shared space (avatars, fonts, voiceovers, drawing working copies).
- [ ] DECISION: five owner questions listed at the end of that report.

## D. VisLab and Joy Brush (vislab_joybrush_audit.md)
- [ ] WaveformDebugActivity is exported in the main manifest. DECISION: keep for adb testing or move to debug.
- [ ] Viz Lab destination (same as B).
- [ ] Joy Brush "Send to Studio" always makes a new project; add "into existing project". Nothing flows back to a drawing.
- [ ] Joy Brush DrawingsActivity uses a private palette; ~48 colour lines and ~166 strings hard-coded (Joy Brush lead's lane).

## E. Legacy FadCam screens (legacy_screens_audit.md)
- [ ] Restyle first: Home tab, Records tab and rows, onboarding, What's New, update sheet, Settings tab, mode popup, Home sidebar, fullscreen preview, Trash.
- [ ] Five activities ignore the Joy Creator theme (Onboarding, What's New, FadCam Pro, Cloud Account, WebView).
- [ ] DECISION (orphan rule: revive or remove, never delete unasked): 11 classes with no caller (list in report).


## Progress 2026-10-16 night (lead)
- [x] Library v2 built and verified on the Note 9 (kind chips with counts, search above keyboard, shared-in filter, bands, grid/list, classic view kept). Owner decisions still open (agent report): keep a "Clips" kind? empty chips faded vs hidden? voiceovers shown in a project view? lobby recording card opens the recording? safe helpers for renaming projects / trashing sprite sheets? lobby "N ITEMS" count to match Library. Lead's calls: keep Clips; keep faded; voiceovers with their project (needs a project view, later); recording card opens the recording; build the helpers; match the count.
- [x] Undo: drawer close hook was never cleared, so Mask/Chroma/Blend undo was lost from the second open; fixed (one-shot). Video overlay blend mode undoable. Visualizer slider drag = one undo step.
- [x] Colour picker hex field never wraps; shared SliderFace.
- [x] Follow-what dialog with numbered pin diagram; visualizer drawer in two columns and height-bounded; case button cycle.
- [ ] Settings hub v2 (agent running): new overlay fragment in ui/settingshub, classic settings kept.
- [ ] Then: Remote/Server Room, Smart Detection, recorder Home (Bits): same treatment, one screen at a time; before shots in tasks/screens/before/v_*.png.
- [ ] Sweep leftovers: caption motion "Type - word" stuck; caption hide-eye / outline swatch no visible response; Invert effect not visible on an image overlay; Puppet RECORDING tab button column clipped.
- [ ] Lobby: reads off the main thread; "N ITEMS" count to match Library; recording card opens the recording.

## JoyBrush engine continuation — October 16 (Codex Lead)

- [x] Bound canonical-source bake through actual engine publication and public undo/redo;
  source 97d0a51c full backend 876 and native 203 passed.
- [x] Incremental replay tile admission without diagnostic raw/sample caps on the opted-in
  renderer; source 8b40d6dcf full core 2442 (four existing optional skips), backend 878,
  native 203 and combined APK passed. Default recording/Select/save guards remain.
- [x] Exact-source captured joint Note9 acceptance (802 exact RGBA8 rows, 14 actual
  output-registration and budget-boundary checks) and ordinary regression (2082 exact rows,
  16 in-memory archive roundtrips). Owner Working / three archives unchanged.
- [ ] Review/test the isolated live scratch source lease and prepared-unit handoff, then
  connect one committed raw/seed/domain transaction without a preceding native pixel Step.
- [ ] Actual Select host, continued joint native/media/structural/paper edits, durable captured
  source save/cold load/Working/recovery/thumbnail/export. Then ready Media Atlas A2.

## Progress 2026-10-16 morning (lead)
- [x] Settings hub v2 verified on the Note 9 (sections, jump chips, search above keyboard, Torch opens, Back returns). Classic settings kept under About. Owner decisions in the agent report: results stack at the bottom (nearest thumb); clock-widget tap and Joybot "setup" still land on the classic tab; Ko-fi link still points at the original FadCam author (not in hub); Packs has no room of its own.
- [x] Drawer header toggles (Hide/Lock/Mute/Bypass/Pass-through) = one undo step each; verified.
- [x] Caption drawer opens above the preset strip (was printed over it); verified.
- [x] Caption motion "None" works (sweep "fail" was a mis-tap on a picker that moves between opens).
- [x] TextOverlayDrawer.Kit and slider track read ObjectDrawer.Kit / SliderFace.
- [ ] Projects mode in the Library + shared NewProjectFlow (Opus agent running); then retire the "Faditor Mini" list to the classic view.
- [ ] Remote / Server Room (1900-line legacy streaming screen): needs an owner call: is streaming-to-browser a launch feature? Parked.
- [ ] Known sweep leftovers: audio envelope Clear/In oddities; sprite Lanes tab pills sit over the preview edge; Invert effect on an image overlay unverified (test image mostly off-screen); Puppet RECORDING tab column clipped.
- [ ] Shared overlay helper OverlayNavUtil.dismiss hides the whole overlay but pops one level: Library -> Trash close path; fix in the helper (legacy, many callers) or in the new screens.

## Status 2026-10-17 (lead)
Done and pushed: slanted chips (Library/Trash/Settings), JoyBot switch everywhere, lobby truth pass, Recently Deleted v2, Library header cycles, onboarding permissions page, ~100 sheet strings, overlay child-close fix.
Running: Settings sub-screens restyle (Sonnet); What's New + update sheets + mode popup (Sonnet).
Open (owner decisions): Viz Lab card; bottom words (Packs/Bits/Finder); Remote/Server Room + Smart Detection; WaveformDebugActivity export; 11 orphan classes; New button behaviour; pill colour; rename "Classic view".
Open (mine): restyle Home recorder (Bits); fullscreen preview; Home sidebar; audio envelope oddities; voiceovers in project view; rename/trash helpers for sheets/characters; retire AvatarToggleView + eye-colour pref; sweep leftovers (caption presets retest).
Parked: VisLab (brief in tasks/VISLAB_BRIEF.md); Joy Brush send-into-existing-project.

## Coordinator current-main Select reconciliation — October24
- [x] Reapply A1–A8/C2/safety/cancel/stale patches onto main7d2 while retaining UI placement/ink/chips/bench changes (source only; new combined Android tests owed).
- [ ] Preserve narrow bench observation APIs without reopening mutable host; fix clean-UP/CANCEL contract with real overlay evidence.
- [ ] Adopt reviewed canonical Copy admission separately when builder delivers; preserve source-domain refusals and owner K25/L3 gates.
- [ ] Integrate guarded bench runner +offline evidence validator; exact-APK per-step phone evidence remains owed.
- [ ] Run appropriate exact-source suites/build after resource admission; keep original9ace receipts and checkouts.

## Coordinator verification continuation — October26
- [x] Integrate debug screenshot ACK and true Brush-taking bench preconditions.
- [x] Independently count178 focused Android tests,0 failures/errors/skips including7 ACK fixtures.
- [ ] Finish fresh-resource-admitted exact APK and correct per-step Note9 visual evidence.
- [ ] Review isolated Atlas borrowed/FULL ownership candidate before compile/GPU activation gates.
- [ ] Compile isolated ordinary-WC diagnostic separately after current acceptance slot resolves.
See tasks/joybrush/HANDOFF_20261026_COORDINATOR.md for exact source/evidence limits.

## Frame-copy prerequisite — October26
- [x] Review allocation-before-tracking leak in copyOfTile.
- [x] Guard initialized texture ownership and add four thrown-failure regressions (source only).
- [ ] Run focused backend compile/tests after current Select device slot resolves.
- [ ] Review/adopt prepared direct-cel copy and clock/export contracts separately.
No live frame-copy/tween activation or GPU/context-loss proof from this prerequisite.

## Roadmap freshness — October26 owner request
- [x] Luna read-only stale-entry/ownership audit; root reconciles newer Switch evidence.
- [x] Replace active roadmap with bounded current states, owners, gates and open audits.
- [x] Preserve old board as historical archive and redirect stale entry documents.
- [x] Add operational agent-start contract and correct coordinator source/test scopes.
- [x] Validate local links and have Luna review the new dispatch instructions.
- [x] Publish reviewed documentation checkpoint0686a51d9; local HEAD equals GitHub branch SHA; notify domain owners.

## Combined native proof candidate — October26
- [x] Preserve current A10 base and selectively integrate frame717, ordinary WC e670 and frame probe7b code.
- [x] Add distinct disposable native frame dispatch and non-debug refusal regression (source only).
- [ ] Focused backend and app compile/test after UX slot handback.
- [ ] Exact APK assembly with fresh3200 admission, then authorized independently parsed GPU probes.
- [ ] Later archive/recovery/failure and watercolor frame-copy gates; no activation from compilation.
