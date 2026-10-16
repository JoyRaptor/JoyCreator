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
