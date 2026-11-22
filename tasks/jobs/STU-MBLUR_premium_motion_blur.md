# STU-MBLUR — Premium motion blur for Studio

**Milestone** After launch · **State** ⬜ design ready for comparison · **Implementation owner** Open · **Source checked** 2026-11-22 against `331164acfed017a85594b18ee07974c4c7425cbd`

## Why it matters

Give artists one shutter effect for filmed motion, animated layers, sprite drawings and Avatar Studio performances, with usable editing on a Galaxy Note 9.

## Done when

- [ ] Motion Blur works on an Effect Layer, on clips, and on animated sprite and puppet layers with the documented targets and timing.
- [ ] Both Note 9 variants pass the defined sustained 720p30 preview workload and 1080p30 export gates.
- [ ] Benchmarked footage quality, alpha and crossing-layer coverage, seeking, cuts, retimes, recorded puppet playback and preview/export parity pass.
- [ ] STATE records actual branch, evidence, limits and help entry.

## Where it stands

- [x] Source and industry controls reviewed. Independent [implementation and verification plan](../design/JoyCreator_Premium_Motion_Blur_Plan.md) written for model comparison.
- [ ] Render-route proof, implementation and phone verification remain open.

## Implementation order

1. Prove access to the right scope and time samples in the actual Studio preview and export paths.
2. Add the shared shutter and authored motion path behind a feature flag.
3. Benchmark video motion estimators and add clip/Effect Layer footage support.
4. Wire deterministic Android Avatar Studio and sprite adapters. Validate on both Note 9 variants.
5. Tune the measured quality tiers, finish help and obtain owner acceptance.

## Dependencies

- [STU-1](STU-1_studio_phone_backlog.md) and [STU-5](STU-5_studio_structure_and_primitives.md) for effect-layer and timeline/export contracts.
- [AVA-1](AVA-1_avatar_studio_redesign.md) for the Android redesign adapter. Coordinate before changing shared puppet code.
- The [future plans ledger](../FUTURE_PLANS.md) records the original motion-blur idea.
