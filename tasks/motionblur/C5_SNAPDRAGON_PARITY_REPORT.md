# C5 Snapdragon puppet and recorded-avatar checks — 2026-11-27

The SM-N960U ran both signed debug fixtures over USB after wireless debugging stopped responding. Preview and pre-encode export were compared at the same 960×540 editor frame times. `tools/motionref/compare-lanes.mjs` uses a per-channel maximum of 1/255 and a mean of 0.1/255 for equivalence. Raw captures stay outside Git under `C:/Temp/stu7-c4-physical-20261125/`.

## Authored puppet

The [c3b178216 fixture APK](https://github.com/JoyRaptor/JoyCreator/actions/runs/45917033085) exercised the ordered preview and export mesh paths. Both sampled puppet cells `0, 0, 1` at editor times `1233333, 1366666, 2566666` µs respectively. The three blur pairs failed strict pixel parity. Maximum channel differences were `71, 84, 73` and the means were `0.06623, 0.08122, 0.08312` on the 8-bit scale. The first USB collector missed the `LaneComposite` tag. Its completed export log identified three exact service readbacks, which were recovered from the cache before another installation and compared with the captured preview PNGs. Evidence is in `mesh-device-235918/`.

A controls-only rerun on the same APK captured all nine preview/export pairs across blur, sharp, and project master-off. Blur differed from sharp in both render paths. Sharp and master-off were bit-exact in export but differed in preview, even at the same reported times. Sharp preview/export maxima were `219, 218, 228`, and the master-off maxima were `217, 220, 228`. The mismatch therefore also exists with blur disabled. Exact timestamps and matching cells do not establish that the preview sampled the same current deformation in the sharp controls.

The follow-up [b3d39906b diagnostic APK](https://github.com/JoyRaptor/JoyCreator/actions/runs/45945516008) captured the sharp mesh clock on the same USB phone. For the preview image reported at editor time `1233333` µs it sampled local mesh time `1127` ms. Repeated draws at that exact decoder time sampled `1133` and `1135` ms. Sharp export sampled `1233` ms. The placement tuple was the same, and the preview and export base checksums differed. The master-off diagnostic did not reach its capture because Android returned no Play control. The original APK was verified on restoration. Evidence is in `puppet-clock-control-20261127/`.

## Recorded avatar

The [c3782a093 fixture APK](https://github.com/JoyRaptor/JoyCreator/actions/runs/45918154451) exercises gated staging from `5cdc3561b91224aead1fcda5fb509318fe51bf0a`. The [emulator run](https://github.com/JoyRaptor/JoyCreator/actions/runs/45918222289) produced three bit-exact preview/export pairs. On Snapdragon, preview used the native 960×540 staging branch, and both paths produced identical shutter taps, weights and standalone avatar stamps at editor times `366666, 1466666, 2766666` µs. The decoded base stage differed before the avatar composite. Final maximum channel differences were `14, 24, 25` and the means were `0.02614, 0.03523, 0.03383` on the 8-bit scale. All three export pairs differed from their sharp controls. Evidence is in `avatar-device-000442/`.

C5 remains open. The gated source and the device diagnostics remain on their isolated branches.

The original APK was reinstalled and its SHA-256 verified after both fixtures. The Note 9 lock was released.
