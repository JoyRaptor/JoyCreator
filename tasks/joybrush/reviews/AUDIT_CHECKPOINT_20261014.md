# Claude audit checkpoint — 2026-10-14

Review the source-backed implementation and the receipts for artifact `8dd645ac363ac83c0da1e7bcfa374eefbcbbe59b`. Keep implementation ownership with Root; this packet requests an independent audit, not code changes.

## Highest-priority audit questions

1. **Single-root publication and ownership:** inspect the joint publish/undo/redo/reset path for one-root atomicity across recorded state, physical/native layers, and shared history cursor. Check texture ownership transitions and exact-once retirement across successful publish, refusal/exception, undo/redo, same-context reset, and context loss. The four Note 9 GPU reports exercise root identity (5 checks/run), public history endpoints (4/run), native read APIs, source/slab preservation and sibling isolation; all 556 exact RGBA comparison rows are zero-difference. Assess what these fixture assertions do and do not prove about production races/reentrancy and broader engine state.

2. **Before-mutation load admission:** inspect the caller-thread `load(contents)` gate and its shared metadata validation path. Confirm that unsupported or malformed retained content refuses before UI state mutation and before GL queueing, and that accepted content still reaches the established full load path. The APK receipt reports 199 native tests, while the GPU probe is not an archive-open/startup/recovery test. Identify missing tests for rejection preserving the currently visible drawing, including synchronous exceptions and UI/GL race edges, without treating this run as proof of arbitrary unsafe archive handling.

## Evidence identity

- Source: `8dd645ac363ac83c0da1e7bcfa374eefbcbbe59b`.
- Installed APK hash: `a9b7809a12150df7dc8140e842141e807d8123b3c9136f48d44f92c6c2878f42`, matching the APK receipt.
- Four distinct eager/cold × Ink/Watercolour device reports pass: 556 counted exact comparison rows, zero differences; GL/FBO/feedback failure counters zero.
- Native tests: 199. Backend 636 reused from source `5b3ebdca`; core 2,309 with 4 skipped reused from `1f185f334`; engine unchanged from `5b3ebdca`.
- Receipts say three named drawing archive hashes are unchanged; there was no archive/recovery roundtrip.

## Milestone boundaries to preserve

This evidence establishes the exercised joint engine scenarios for the two fixture brushes. It does not establish live stroke capture, load startup/recovery, general unsafe archive rejection coverage, native media execution, live native-base promotion/projection, Select, save activation, general brush compatibility, rendering quality, or performance. The assembly receipt itself says GPU was not executed there; cite the separate device reports for GPU results.
