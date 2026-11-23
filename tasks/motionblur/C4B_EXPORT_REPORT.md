# C4b ordered export framebuffer checks

## First picture and adjustment controls

The [exact APK](https://github.com/JoyRaptor/JoyCreator/actions/runs/45175466022) at `481f3320f` produced three raw 960 × 540 RGBA samples per control on the Snapdragon Note 9. Actual editor times were 66,666, 500,000, and 900,000 µs.

The grouped legacy output and ordered same-order output matched across every channel at all three times. The expected difference from setting the adjustment above the picture failed. The two ordered runs both entered `LaneCompositeGlEffect` and drew the adjustment first.

The disposable project file correctly serialized adjustment z-index 8 and picture z-index 4. On load, `Timeline.pruneOrphanedTrackFlags()` removed the built-in `adjustment` lane’s flag. ProjectStorage logged the removal for each fixture. The legacy orphan scan includes other built-in tracks and user-created track definitions, but currently omits adjustment layers. The negative capture therefore exercised the same effective order as the positive control.

The next fixture uses an explicitly defined adjustment track, whose z-index survives the existing serializer. This keeps the production legacy load semantics untouched. C4e should cover adjustment lane flag preservation under explicit True layer order.

The original APK hash matched after restoration. The phone was released. Raw manifests, device logs and captures are under `C:/workspace/motion-c2-evidence/c4b-lane-fixture/`.

## Mixed rigid producers

The [exact mixed APK](https://github.com/JoyRaptor/JoyCreator/actions/runs/45176733491) at `2c2c9e743` exported the same arrangement of picture, PiP, upright title, rigid sprite, and invert through the legacy and ordered routes. Three matched raw frames failed the C4 equivalence gate. Mean absolute channel errors ranged from 10.46 through 11.63/255. Maximum error reached 255/255.

The first-frame comparison shows the ordered title as a solid black rectangle. The bounded title raster had already been uploaded and its CPU bitmap recycled. `PipGl` chose the external-video shader from the absent bitmap reference even though the caller bound a 2D texture. Commit `669cedcd9` selects the shader based on the target bound by `drawStill` or `drawExternal`. The repaired device rerun is pending.

Raw evidence and the first-frame comparison image are under `C:/workspace/motion-c2-evidence/c4b-mixed-rigid/`. The original APK was restored by hash and the phone claim was released.

## Persisted-order control

The [corrected fixture APK](https://github.com/JoyRaptor/JoyCreator/actions/runs/45177682551) at `57ccca950` ran four matched controls on the Snapdragon Note 9. A named adjustment track retained its z-index across disk and export snapshot reloads.

The legacy grouped route and the ordered same-order route matched byte for byte on all three 960 × 540 frames. With the adjustment above the picture, the result changed as expected. The CPU reference inverts the raw RGB of the otherwise identical ungraded fixture and preserves its opaque alpha. The grouped negative failed against that reference at all three times, with mean channel error about 29.06/255.

The ordered inversion was very close to the CPU reference. Mean errors were 0.000871, 0.000873, and 0.000898/255. The first frame met the max-error threshold. One channel at the second timestamp and eight at the third were 2/255 apart, so the strict comparison still failed. The diagnostic originally read from an identity-copy output. Commit `4be23b758` now attaches the composed texture directly for the readback. The tolerance is unchanged.

The original APK was restored and hash-verified. Raw controls and comparisons are under `C:/workspace/motion-c2-evidence/c4b-persisted-order/`.

## After choosing the texture target explicitly

The [fixed mixed APK](https://github.com/JoyRaptor/JoyCreator/actions/runs/45178432280) at `669cedcd9` produced eight paired route trials across mixed, title-only, sprite-only, and PiP-only variations of the persisted fixture. The black title rectangle was gone. The title’s registered glyph bounds differed by about one pixel horizontally and vertically in the tested pose. This still trips the strict per-channel criterion.

| Added producer | Same-order mean RGBA error range | Max channel error |
| --- | ---: | ---: |
| Mixed | 0.242–0.306/255 | 223/255 |
| Title | 0.216/255 | 223/255 |
| Rigid sprite | 0.026–0.036/255 | 64/255 |
| PiP | 0–0.060/255 | 99/255 |

The pre-PiP frame matched exactly. The two active PiP frames differed, and a same-route repeat is needed to distinguish decoder variance from the ordered host. Old projects containing these producers remain outside the automatic route until equivalence is proven. Explicit-order tests stay behind the debug switch.

The original APK matched by SHA-256 and the phone claim was released. Raw controls, comparisons, and the title PNG are under `C:/workspace/motion-c2-evidence/c4b-mixed-fixed/`.

## Direct composed-texture readback

The [exact direct-readback APK](https://github.com/JoyRaptor/JoyCreator/actions/runs/45179506267) at `4be23b758` ran the same four persisted controls through the committed Note 9 runner. The capture attached the composed input texture to an FBO before copying it forward for encoding.

| Matched control | Max channel errors at the three editor times | Mean error | Result |
| --- | --- | --- | --- |
| Legacy grouped vs compatible order | 0, 0, 0 | 0/255 at each time | Byte-identical |
| True order vs independent CPU RGB inverse | 1, 1, 1 | 0.000802–0.000817/255 | Strict pass |
| Grouped order vs the same CPU inverse | 255, 255, 255 | 29.057/255 | Expected failure at every time |

All comparisons used 960 × 540 raw frames at 66,666, 500,000 and 900,000 µs. The original installed APK matched its recovery copy by SHA-256 after the run and the claim was released. Exact [Studio CI](https://github.com/JoyRaptor/JoyCreator/actions/runs/45179502031) passed. Records are under `C:/workspace/motion-c2-evidence/c4b-direct-rgba/`. This certifies the base picture/adjustment control. The larger corpus and mixed legacy eligibility remain open.

## Wider compatibility corpus

The first batch of the [exact corpus APK](https://github.com/JoyRaptor/JoyCreator/actions/runs/45182105218), source `b640a48de`, tested the base picture, reduced opacity, offset, and rotation. All twelve raw pairs matched byte for byte. Each fixture completed export. The original APK was restored and hash-verified. The phone was released. Evidence is under `C:/workspace/motion-c2-evidence/c4b-corpus/compatible-0/`. [Studio CI](https://github.com/JoyRaptor/JoyCreator/actions/runs/45182099123) passed.
