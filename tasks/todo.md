# Launch plan (drafted 2026-10-15 from six read-only audits in tasks/audits/)

Rule: nothing below marked DECISION gets built until the owner picks.

## A. Studio (finish first)
- [x] Sprite pin links, visualizer parent-follow, string resources (150 + 7 tool labels), token sweep, drawer inventories.
- [ ] Sprite warp net looks detached from the sprite (owner report 2026-10-15). Sonnet agent investigating; verify on phone.
- [ ] Dialogs: one Studio dialog theme (see studio_dialogs_audit.md). DECISION: positive-button colour.
- [ ] Hover labels: 201 clickable sites with no label, 117 in FaditorEditorActivity (studio_hover_labels_audit.md). Fix the 15 most visible first via Kit.describe.
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
