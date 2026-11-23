# C4b ordered export framebuffer checks

## First picture and adjustment controls

The [exact APK](https://github.com/JoyRaptor/JoyCreator/actions/runs/45175466022) at `481f3320f` produced three raw 960 × 540 RGBA samples per control on the Snapdragon Note 9. Actual editor times were 66,666, 500,000, and 900,000 µs.

The grouped legacy output and ordered same-order output matched across every channel at all three times. The expected difference from setting the adjustment above the picture failed. The two ordered runs both entered `LaneCompositeGlEffect` and drew the adjustment first.

The disposable project file correctly serialized adjustment z-index 8 and picture z-index 4. On load, `Timeline.pruneOrphanedTrackFlags()` removed the built-in `adjustment` lane’s flag. ProjectStorage logged the removal for each fixture. The legacy orphan scan includes other built-in tracks and user-created track definitions, but currently omits adjustment layers. The negative capture therefore exercised the same effective order as the positive control.

The next fixture uses an explicitly defined adjustment track, whose z-index survives the existing serializer. This keeps the production legacy load semantics untouched. C4e should cover adjustment lane flag preservation under explicit True layer order.

The original APK hash matched after restoration. The phone was released. Raw manifests, device logs and captures are under `C:/workspace/motion-c2-evidence/c4b-lane-fixture/`.
