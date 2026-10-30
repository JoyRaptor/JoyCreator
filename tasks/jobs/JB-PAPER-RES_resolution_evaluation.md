# JB-PAPER-RES: compare paper detail, brush response and package size

**Milestone:** M1 · **State:** 🟡 evaluation installed; owner/GL acceptance open · **Owner:** JoyBrush coordinator + paper specialist (Codex) · **Checked:** 2026-10-29 against8420d5173

## Why it matters
Keep convincing physical paper while choosing phone-friendly texture sizes. Compare the same crumpled paper at2048/1024/512 rather than silently lowering quality or shipping45 full-size textures.

## Done when
- [x] Preserve all45 selected expanded source images and recipes; all45 reactive offline masters and two reduced profiles prepared.
- [x] Preserve independently filtered height² in packed profiles and verify production catalogue/native host BitmapFactory (four tests passed).
- [x] Build matched baseline and evaluation APKs; measure size increase.
- [x] Upgrade the verified sandbox Note9 in place; complete saved-file path/length/hash set unchanged, exact installed APK verified.
- [x] STATE row/wing roadmap/evidence updated with limits.
- [ ] ActualNote9 decoder/GL/mips/residency/seams, save/reopen/export and every relevant brush-family comparison.
- [ ] Owner chooses acceptable texture detail/feel. Installed is not creative acceptance.
- [x] All45 offline masters completed; root verified135 actual files and51 unique inputs,205722497encoded bytes.
- [ ] Approved final phone profiles/integration; no bulk shipping by assumption.

## Where it stands
Evaluation branch lead/paper-evaluation-20261029, tested assetscodecbc693e97, APK8420d5173. It combines leadace333729 with published runtime/buildaa6cc4143 in baselinecd1e48805, then adds ONLY9 paper assets/three catalogue pairs plus tests/docs. HYG-2 is not thereby complete; no runtime assets landed on main.

Baseline169549163bytes; evaluation177213152bytes: +7663989bytes/+4.52% (matched arm64 debug builds, not release size). ActualNote9SM-N960U/API29 installed SHA36f9492e57c5de8f3aff896ddf6cebde0c5c2d589b2582d3d5b0daaf333b2e57. All33 saved files unchanged during install; temperature28.0→28.1C; claims released. No activity launched or drawing edited.

## What is left
Use a fresh scratch drawing and matching Background AND Surface labels Crumpled paper2048px/1024px/512px (spaces in actual labels), one resolution at a time. Owner feel and device visual checks remain open. Full45 materials are not shipped. Remaining master bakes run only under fresh per-material resource admission/exclusive shared lock; stop on first failed gate, no wait/retry loop.

## Specs, handoffs and evidence
- [Current wing roadmap](../joybrush/ROADMAP.md), PAPER-PORTFOLIO.
- [Build/install and remaining gates](../joybrush/reviews/CRUMPLE_PHONE_BUILD_20261029.md).
- [Measured artifacts and protected install](../joybrush/reviews/CRUMPLE_PHONE_RESULT_20261029.json).
- [Independent host XML receipt](../joybrush/reviews/CRUMPLE_PRODUCTION_DECODE_20261029.json).
- Paper pipeline: codex/paper-approved-20261029@8e5a8ddb5; source/master/profile receipts remain on that branch and local visualization artifacts.

## Files and traps
Resource-only test overlay, no shipping ID/default replacement or engine/cache/loader edits. Keep full2048 masters. Explicitly separate encoded package size, modeled residency and observed child RAM. Device mapping is private tools/devices.local.sh under NEW C:/+Projects/JoyCreator; never commit serials. Never uninstall/clear data/touch owner drawings/rotate/monkey-launch. One Gradle/asset heavy job and one phone owner at a time.

## Owner follow-on, October29
[Codec research](../joybrush/reviews/PAPER_CODEC_OPTIONS_20261029.md) records modern
storage/GPU compression choices. It is a separate unstarted benchmark; current
phone variants and feel comparison remain unchanged.

### Current owner order: full library, compression, then packs
The owner explicitly requests all45 full-resolution masters on the sandbox phone
for evaluation before choosing removals or downloadable packs. The205722497-byte
master payload is an evaluation baseline, not an accepted shipping footprint.
Target a10× reduction in the subsequent codec benchmark while measuring quality;
that ratio is a goal, not an established result.

- [x] Stage all45 master catalogue pairs plus the two existing reduced comparisons
  in isolated `lead/paper-full-evaluation-20261029` (resource checkpoint05f845ac9).
- [ ] Verify every packaged master with production decoding; preserve shipping IDs.
- [ ] Fix unbounded paper GPU retention with complete live-borrower and cold-rollback
  ownership before allowing full-library phone browsing. A separate Sol review/
  implementation lane owns this narrow change; CPU previews are sequential but
  full-resolution transient allocation and eager queue behavior need observation.
- [ ] Build, protected-install and traverse the full45 masters/two comparisons on
  the Note9. Verify exact source, catalogue coverage, GL liveness/reclamation and
  complete saved-file manifests; installed3-profile comparison does not satisfy this.
- [ ] Owner evaluates full library and matched Background/Surface brush response.
- [ ] Benchmark compression with decoder overhead, physical-map fidelity, CPU/GPU
  memory and load-time evidence; preserve the approved full-resolution masters.
- [ ] After evaluation/compression, choose base-app papers and optional packs.

## Offline master phase CLOSED — October29
Paper branch085841ddd is pushed and remote-verified. Root independently verified all
135 actual output hashes/modes/dimensions,51 unique recorded source/recipe inputs,
45 successful resource receipts with termination/claim release and the exact total
205722497bytes (look63614156, height117214451, fluid24893890). Maximum observed
child peak594.42MiB. [Independent aggregate proof](../joybrush/reviews/PAPER_MASTERS_COMPLETE_20261029.json).
These full-resolution evaluation artifacts are NOT all in the APK. The installed
phone comparison remains only one paper at three resolutions. Numerical preparation
is not measured physical paper accuracy or creative/GL/seam/feel acceptance. No
codec experiment or runtime-format change was started.
