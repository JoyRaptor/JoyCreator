# Watercolour continuation and K25 policy — October25

Owner: preserve the Engine Lead's shader/program-selection ownership and keep
8c019c2bb out until the L3 addendum is corrected.

## Current candidate

Branch vector/watercolour-k25-20261025, based on mainf79efc378. The held8c019c2bb
is not an ancestor (git ancestry check). The finish shader and WatercolourPass
are unchanged from main, which now contains the Lead's two-program shader fix.
This source change does not claim that fix meets the phone speed target.

- f87402bc4 adds the corrected shared policy and eight regression tests.
- 3043aaf14 is a separate Activity LEAD PATCH: current-brush shortcut, tool
  changes and lab reload resolve the same per-ID setting. Pixel-only brushes
  have no shortcut, and a stale popup cannot change a newly selected brush.
- A10 brush-sheet controls/badges belong to the Select builder; no obsolete
  global-priority caption or second preference has been introduced.

## Policy and API

`BrushLinePreferences(prefs).enabled(brush)` / `set(brush,on)` retain the key
`joybrush.brushMakesLines.<brush-id>`. `BrushLinePreferences.canSwitch(brush)`
lets A10 hide the switch. No legacy-global read, write or migration occurs.

Defaults ON: the ten exact catalogue IDs under brushes/lines plus vector
watercolour engines. K25 also explicitly says Inks & pens; the shipped Ink is
included by exact ID joybrush.ink. The narrower L3 addendum names ten presets
and watercolour, so this additional shipped identity is documented explicitly.
No shelf/name/substring classification is used: pink or Pen wash painting
brushes remain OFF. Other painting brushes default OFF; explicit per-ID choices
survive. Null, media, smudge, push, wet, blur, clone and paste always return
false, ignore stale stored ON flags and cannot write a switch.

## Verification and remaining work

Read-only source review found no concrete issue; git diff --check passes.
The initial focused eight-test run onfda29f68c compiled and executed:seven passed,
one failed. The failed painting-override test accidentally used its clearing
helper when recreating the preferences wrapper. Fixture correction ba341098b
reuses the same preferences object. Select's integrated e9d2a417604c19138aa15a4e7f094edf0945dc33
run now passes all eight policy tests (app total171, zero failures/errors/skips).
Git blob comparison confirms both policy and corrected test files are identical
to this branch. Receipt: C:/Temp/jb-select-acceptance-20261025/app-e9d2a4176/receipt.json.
This is integrated-source proof for those files, not this branch's complete APK
or phone acceptance. No redundant policy-only run was performed.

Earlier evidence remains tied to its own source:

- 0b02e9e06: core2559 (four optional skips), backend1068, no failures/errors.
- a4f173b9f: native320, no failures/errors/skips; APK build succeeded5m57 using
  private Media3 output. That APK predates the Lead shader fix and current K25
  changes, was never installed by this lane, and is not the current candidate.
- GPU wash/frame/seam technical checks passed; six original POC appearance
  checks failed. Those are shader-vs-POC results, not native cache parity.
- No actual cache pixel diagnostic, cold/warm matrix, live edits, archive/reopen
  or graphics restart proof has been obtained on the current candidate.

The previous phone baseline/claim and installed APK identity are stale after
other lanes' work. Recheck watcher, lock, model and installed hash before testing.
Do not install the older held-policy APK. Preserve source/evidence scratch.

L3/K25 is not complete: corrected policy tests pass in Select's integrated
candidate; final adoption and phone proof remain separate. Smudge's shared Slice + touched-section bake
(one Undo, no smear ghost) remains an engine task whose assignment the Lead
decides. K26 realistic paint below editable lines remains Lead-owned; the old
refusal must not be removed without the composition adapter.

## Shared Slice planning checkpoint

Pure LineSlicePlan source82391bb71 passed ten focused core tests on exact
a8b63e0105c3dc9b35efa4f083f09bb52d04b8c3:zero failures/errors/skips,
BUILD SUCCESSFUL in2m. Its original recipe/sample/frame snapshots, zero-width
crossings, half-open endpoint ownership, touched-dot support and early
input/pair/piece refusal are checked. No runtime/schema/GPU activation occurred.
See [Slice design and gates](WATERCOLOUR_SLICE_DESIGN_20261025.md).

Exclusive ownership source987630925 also passed ten focused tests on exact
8e56aea956f07327d3c39c6972034f1fcf7fd576, zero failures/errors/skips in2m16s.
This proves the pure predicate/scalar oracle only. Source-domain persistence,
chronological pixel contribution, runtime transaction and native/phone proof
remain open; the coordinator has not authorized schema/runtime activation.
