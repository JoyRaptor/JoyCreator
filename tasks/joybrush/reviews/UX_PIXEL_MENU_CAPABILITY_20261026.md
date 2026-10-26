# Pixel menu capability repair — source-only

Base4b9ed850c, branch ui/control-completion-20261026, isolated checkout
C:/Temp/jb-ux-control-completion-20261026. Coordinator approved exclusive Controller/
Row scope. Owner requests functional UX completion; PhaseB engine work remains open.

Current production pixel lift is unavailable. The fallback selection row still
offered More with no clipboard/colour capability; clicking it opened an empty popup.
Controller now supplies explicit menu availability to showPixels, hides an empty
action row, and rechecks that menu items exist before opening a popup. Row omits
unavailable More and its trailing separator. Existing supported Fill/Delete/Flip,
Paste, HSB and line actions retain their callbacks. No fake pixel operation was added.

Four row tests exercise zero actions, unavailable More, available More callback,
and exact Fill/Delete/Flip callbacks. Five real Controller/Popover tests exercise
empty fallback, available colour menu, revoked colour capability, canonical copied
lines enabling Paste, and cleared clipboard at click time. Clipboard proof uses the
existing canonical engine fixture; no new production or test-only access seam.

Primary reviewed both Luna changes, corrected a JUnit import, replaced a vacuous
separator assertion and added canonical clipboard cases. Diff check passed. All nine
tests are UNEXECUTED; no Gradle, phone, preview or saved-data action occurred. Root
owns focused verification/integration and exact installed-candidate acceptance.

PhaseB lift/mask/preview/commit/cancel, Fill/move/delete/flip/duplicate/copy/invert/
HSB/painting clip, joint history and device/data proof remain engine dependencies.
C3, Slice/smudge, K26 and A10 theme/phone proof remain separately open. This repair
prevents empty controls; it does not complete those features.

## Integration attempts

Current A10/docs basef628 selectively integrated as53364b741. First attempt stopped
at private-init wrong-root guard11s before compilation (included builds also run init
scripts); corrected harness skips non-target roots. Second attempt compiled production
but stopped at test compilation3m46: missing assertTrue import introduced during primary
separator-assertion review. Added only that import; no product change. Both red logs
preserved at C:/Temp/jb-lead-ops/ux-menu-53364b741-20261026.log and
ux-menu-53364b741-corrected-20261026.log. Neither attempt executed tests or provides a
fresh XML pass. Coordinator granted one focused retry on the corrected frozen source.

## Executed focused proof

Corrected sourcee955cd61709e3cd1509485905029d150b462ca9f completed SUCCESS1m3s,
wrapperexit0. Actual testcase nodes:199 across29 suites,0 failures/errors/skips.
This includes all27 prior f7 suites (190 cases), SelectPixelMenuTest5 and
SelectRowCapabilityTest4. No source changes were made while the job ran.

Receipt: `C:/Temp/jb-lead-ops/ux-menu-e955cd617-count.json`.
Copied XML/hash set: `C:/Temp/jb-lead-ops/evidence/ux-menu-e955cd617`.
Log: `C:/Temp/jb-lead-ops/ux-menu-e955cd617-20261026.log`.
The private init enumerated those29 suite names, skipped non-target included roots,
and bound512MiB tests/CPU2/one worker. Canonical lead-build performed fresh2200MiB
admission/exclusive locking. Job and shared slot explicitly released to Coordinator.
Product repair is ready for Coordinator's selective integration; no new APK/phone
or PhaseB feature completion is inferred. Both earlier red logs remain preserved.
