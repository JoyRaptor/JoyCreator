# Launch plan (drafted 2026-10-15 from six read-only audits in tasks/audits/)

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

# Unibrush — 2026-10-18

Owner brief: tasks/joybrush/briefs/CODEX_DIGITAL_PEN_BRIEF_20261018.md. Section 0 wins.
Isolated branch codex/unibrush starts at lead/layer-ops as authorized in the handoff.

- [x] D1 engine/model: explicit rotation sources; legacy dab compatibility; pressure/tilt recipe APIs; JVM proof. Sheet controls are D9; side-fade is D2.
- [ ] D1 Lead review/integration and phone acceptance.
- [x] D2 engine: radial profiles and directional fade; CPU/GPU/transport checks. Lead integration and phone acceptance pending.
- [x] D3 engine: four-point shapes, rounding and A/B morph; CPU/GPU/replay checks. Lead integration and phone acceptance pending.
- [x] D4 engine: seeded colour dynamics, frozen palette/codec, CPU/GPU/replay checks. Lead patch held; app/phone acceptance pending.
- [ ] D5: sampled image sets and frozen archive resources; Lead engine patch; phone proof.
- [ ] D6: dual-tip masking; shader checks.
- [ ] D7: stroke-end taper parity; Lead integration if required.
- [ ] D8: stabilizer suggestions/settings and exact raw-record replay; Lead record integration.
- [ ] D9: Unibrush sheet, shape editor, modes, New brush, live preview; owner phone review.
- [ ] D10: measured Note 9 performance and soft-dab optimizations.

Review: each slice receives a separate commit and AGENT_BOARD note before Lead integration.
No merge/conflict resolution, shared-main edits, or phone use during the D1 engine work.

D1 review: full core 2521 tests / 0 failures / 0 errors / 4 optional corpus skips (2026-10-18).
Unmodified 107748a78 baseline fingerprint and new dabber agree across 88 recipes / 7128 dabs:
2fa83c231a3938bbcb5695597204c6a980db2bf47056d81a557dd2f1bf08a8d3.
64 behavior/source combinations replay the streamed live dabs after raw-record codec roundtrip,
with nonlinear response curves and a changed-seed control. No GPU, phone, feel or performance claim.

D2 review: core 2529 tests (4 optional corpus skips), Android backend 1050 tests; no failures/errors.
Production shaders match CPU in 72 cases / 73728 probes (max error < 1/255), with all three
wrong-profile/fade controls detected. Three-dab extended buffer matches individual draws byte-for-byte;
wrong-offset control differs in 2856 bytes. Historical 820c62802 vs new shader: zero differing pixels.
Headless Edge/SwiftShader only; Note 9 feel/performance and editable-line adapter remain pending.

D3 review (2026-10-19): core 2547 (4 optional skips), backend 1052, no failures/errors.
156 CPU/GPU cases / 159744 probes pass below 2/255 (max .0043181251); all six controls detected.
Extended VBO is byte-identical to individual draws; both wrong-offset controls differ.
Historical old shader comparison remains exact. Shader contact sheet inspected; no phone claim.
See tasks/joybrush/reviews/UNIBRUSH_D3_20261018.md and accompanying 20261019 evidence.
