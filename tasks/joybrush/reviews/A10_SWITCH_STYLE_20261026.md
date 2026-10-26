# A10 Editable lines switch — source-only styling repair

Worktree C:/Temp/jb-ux-a10-style-20261026, branch ui/a10-switch-style-20261026.
Base d56834cdc1859c07e0d9ce0f3e20500d7f2ef26f; production base is the coordinator's
f84504944 / tested36b0e07f3-equivalent modules. Original UX/review/Select/coordinator
worktrees and the frozen phone candidate were not edited.

## Change

BrushSettingsView's Editable lines row keeps its native Switch semantics, left label,
text sizing/padding, minimum row height and callback. Its grey thumb/track is replaced
by SelectPillSwitch: a native Switch subclass with a56×32dp full-pill track. On uses
the existing SelectAccent diagonal cyan shader and a true DST_OUT On word. Off uses
the same neutral fill/drawer ink as the approved Select choices. There is no outline,
new palette, global preference, separate selection model or settings layout rewrite.

Native label, checked state, accessibility role, focus/click behavior and listener
dispatch remain with Android Switch. No thumb splits the pill. Initial isChecked is
still assigned before the existing listener; construction does not write preferences.
The null/always-pixel branch and its exact D22 sentence are unchanged. Activity's same
per-id BrushLinePreferences binding and stale-current-brush guard are untouched.

Source delta:
- BrushSettingsView.kt: constructor substitution and removal of its two monochrome tint
  assignments for this row only. Other knob/slider tints remain.
- New select/SelectPillSwitch.kt: stateful palette track, local drawable bounds, isolated
  cutout/group alpha and normal native Switch On/Off accessibility text.
- New select/SelectPillSwitchTest.kt: four native-graphics Robolectric scenarios below.

## Review and unexecuted tests

Luna implemented the scoped component/tests; UX reviewed and corrected group-alpha
handling, glyph-free gradient sample points, bitmap bounds, native checked-callback
expectations and accessibility-text handling. Low-alpha ARGB8888 colour assertions allow
documented premultiplication rounding rather than requiring impossible exact RGB recovery.

The four test scenarios cover checked gradient/corners/cutout, neutral Off and two native
click transitions, native accessibility label/checkable/checked behavior, and drawable
alpha in both states without filling the cutout. They draw the actual widget's installed
track, not a duplicate renderer. Existing BrushLineModeUiTest covers initial no-write,
same callbacks and exact no-switch text; policy/Activity binding regressions remain relevant.

**UNEXECUTED:** no Gradle/compile/test, APK, device, screenshot or benchmark run was made
for this repair. Only source review and git diff whitespace checks were performed. Do not
transfer the178 passing tests from36b0e07f3 onto this new component. Coordinator must assign
a fresh shared validation slot; no queued wrapper/build/phone claim is held by UX.
Suggested bounded gate: new SelectPillSwitch tests plus existing BrushLineModeUi,
BrushLinePreferences and BrushLineActivityBinding tests, using the required resource runner.
Runtime native Switch sizing/track drawing and exact integrated phone appearance remain
unverified until those gates run. White/dark and paper-preview badge readability also
remain unknown; this track repair does not change or validate the badges.

## Separate current C1/C2 evidence audit

Readonly review of the actual new run, not the rejected old52 screenshots:
C:/Temp/jb-select-acceptance-20261026/bench-36b0e07f3-run1,
run eef75be8c55846c9a0d22bd3d4eac645, source36b0e07f3,
installed APK SHA2563ec0fedbfcf3f5c06bcc075eac00df70a90c332a90b78770d823523c8c10e300.
Luna inspected all11 PNGs; UX directly reviewed02/03/04/08/09/10/11. The named images now
show their expected states. Typed ACK/foreground/hash data supports registration but does
not replace visual review. Tan-paper row/bar/count placement, paper backing, selected
cyan pills, count2/2 and Nudge bar/Box are readable in the inspected frames.

| Image | Bounded observation |
| --- | --- |
| 01_drawn | Five ink strokes; no inferred acceptance of later controls. |
| 02_tap_pick | One diagonal selected, row above the box, full box/rotate knob. |
| 03_cycle_count | Horizontal line selected,2/2 count clear of its lower box edge. |
| 04_lasso_bar | Two lines in the box, selected cyan Lines2/filter chips, bar clear of chrome. |
| 05_moved /06_rotated | Moved/rotated selection presentation; behavioral one-step results are recorded separately. |
| 07_tip_dots | Blue endpoint indicator visible on selected zigzag. |
| 08_nudged | Bent zigzag and Nudge slider/Box state. This is after release, so it does NOT prove the during-drag20% ghost. |
| 09_copy_pasted | One selected diagonal partly outside the viewport. Model reports one added record/one step; the cropped image alone cannot prove record identity. |
| 10_grouped |5 lines selected with a large enclosing box; group metadata comes from the separate behavioral check. |
| 11_locked | Selected line/box still shown; locked=true is behavioral data, not a visible unlocked-menu/locked-edit test. No new lock icon requirement is invented. |

Results report19 PASS/1 FAIL. fingerPansWithBrush stops at precondition “no uniquely scoped
Nudge control” before panning: the preceding step has already entered Nudge, whose row is
hidden. The visible Box chip is the mode-exit control; the benchmark scopes Push-or-pull
in the hidden row instead. This is an untested pan outcome, not proof that finger panning
itself failed or passed. Coordinator received this finding; its builder owns the correction.

The current receipt has178/0/0/0 across24 suites. Luna independently checked all24 copied
XML names/counts/SHA256s against root-count and receipt, confirming the aggregate. Those
records bind the copies to their manifests; XML alone does not prove arbitrary source/APK
identity. Receipt/XML path: C:/Temp/jb-select-acceptance-20261026/app-36b0e07f3.
Root count: C:/Temp/jb-select-acceptance-20261024/app-36b0e07f3-root-count.json.

C1/C2 remain partial: no white/dark sheet/badge, D2/More/HSB/real layer-menu captures,
mid-drag ghost, full save/reopen or physical S Pen-feel acceptance in this image set.
C3 still needs the Lead-owned correct source/operator display hook. K26 and Slice/smudge
publication notices retain their engine dependencies. No existing evidence was modified.

## October26 — combined070 accessibility fixture correction

Coordinator reports combined070 app run:183 cases,1 failure,0 errors/skips. All179
existing regressions and the three new native-render cases passed. The sole failure
was a null className from detached createAccessibilityNodeInfo in the accessibility
test; this establishes no product styling failure.

The focused correction changes only SelectPillSwitchTest plus this record/todo.
The existing accessibility test first exercises stock android.widget.Switch, then
SelectPillSwitch, with the same explicitly obtained AccessibilityNodeInfo passed to
onInitializeAccessibilityNodeInfo. Each node is recycled. Both controls assert native
Switch className, checkable, exact checked state and label in Off/On/Off states, with
callback counts0/1/2. Existing styled layout/On/Off assertions remain. No production
accessibility override or assertion removal; all three rendering tests are unchanged.

The stock positive control and correction are SOURCE ONLY, not locally executed.
Coordinator owns the four-test rerun on unchanged production; no local test/build or
device job was started. Passing positive control and corrected fixture remain owed.

## October26 — actual API28 attachment diagnosis and replacement fixture

Coordinator's9f2 rerun failed the stock Switch positive control at className=null;
the other three rendering cases again passed. Explicit node allocation was insufficient.
The earlier fixture explanation was incomplete: attachment is required by the real
API28 framework, not merely by Robolectric's node factory.

Read-only javap inspection used the actual cached
`org/robolectric/android-all-instrumented/9-robolectric-4913185-2-i7` jar.
View.onInitializeAccessibilityNodeInfo dispatches to the internal implementation;
View.onInitializeAccessibilityNodeInfoInternal begins by returning when mAttachInfo
is null (bytecode offsets0–7). Only later does it call getAccessibilityClassName and
setClassName (offsets329/332). Switch.getAccessibilityClassName returns the native
Switch name. ShadowView from cached shadows-framework4.17 declares no replacement
accessibility initializer. Thus calling the public initializer on a detached stock
control reproduces the missing base className; it does not demonstrate an unsupported
node model or product defect.

Replacement fixture: create/setup a real Robolectric Activity, construct stock Switch
and SelectPillSwitch with its context, add both to the same vertical LinearLayout,
setContentView and make the Activity visible. Require isAttachedToWindow before
testing either control. Stock is checked first. Restore createAccessibilityNodeInfo;
preserve Switch role, label, checkable, exact Off/On/Off checked values and0/1/2
callbacks. Recycle nodes and close the Activity lifecycle in finally blocks. Product
files and the other three render tests are unchanged.

This correction is source-reviewed only, not executed locally. Coordinator owns the
next focused four-case rerun after its separate heavy-job release. Physical attached
device accessibility and broader A10 visual acceptance remain separate gates.

## October26 — bounded desktop verification passed

Coordinator adopted the attached fixture as97c49ab343c4b554768400d308d0083a536fcd66.
Its focused Switch4 run completed successfully in39s:4 tests,0 failures,0 errors,
0 skipped. Specialist independently read the actual XML and matched its SHA256 to
the Coordinator receipt:
`F91AA57E8DCCA997D11E334969CE2993DBF50575B1753C39456C862C9325F8D4`.

Receipt: `C:/Temp/jb-lead-ops/switch-attached-97c49ab34-root-count.json`.
XML: `C:/Temp/jb-frame-copy-integration-20261026/joybrush-android/build/test-results/testDebugUnitTest/TEST-cc.joycreator.joybrush.android.select.SelectPillSwitchTest.xml`.
Log: `C:/Temp/jb-lead-ops/switch-attached-20261026.log`.

All three native pixel-render scenarios pass, as does the attached stock/styled
native-node test: Switch role, label, checkable/checked Off/On/Off states and exact
callback counts. This closes the desktop fixture and these bounded rendering checks.
Coordinator reports the prior179 regressions green on production-identical070 and
backend4 green; those were not rerun or independently recounted in this UI update.
Coordinator merged the reviewed candidate as canonical4b9ed850c.

No new phone evidence exists for this change. Physical attached-device accessibility,
white/dark paper and A10 sheet/switch/badge appearance remain pending, as does C3.
The earlier d11 tan-canvas review remains separately scoped. No source/build/device
actions were performed by this docs update.
