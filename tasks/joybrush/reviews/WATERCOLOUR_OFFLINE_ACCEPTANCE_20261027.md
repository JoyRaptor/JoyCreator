# Ordinary watercolor offline acceptance checker

This is a host-side report validator. It does not launch ADB, compile an APK,
obtain a device receipt, execute GPU code or establish artist acceptance.
Runtime dispatch source remains e670257df; root owns the combined native candidate.

Verification: ten synthetic Python unittest methods pass with the bundled runtime.
The domain lead independently reran them after correcting one invalid negative
fixture (a legitimate source grain override to zero must be accepted). A final
run covers the exact committed checker/test files. No Gradle, APK or ADB run is
part of this checkpoint. Existing runtime/native evidence is unchanged.

Usage from the repository root (Python standard library only):

```text
python tools/watercolour_ordinary_acceptance.py report.json --brush joybrush.lines.watercolour
python tools/watercolour_ordinary_acceptance.py fenced-log.txt --brush joybrush.lines.watercolour --run 1 --receipt host-receipt.json
python -m unittest discover -s tools -p test_watercolour_ordinary_acceptance.py -v
```

Capture must contain one selected report, or all selected recordedProbeChunk parts
exactly once from one fresh launch/capture fence. Group identity is exact case,
brush and integer run. Concatenate base64-decoded bytes before decoding UTF8;
non-ASCII characters may straddle chunks. Missing, duplicated or inconsistent
parts, selected malformed JSON and fatal exceptions refuse. Do not mix reused
run1 sessions: the app report has no launch GUID. A host fence/run receipt remains
mandatory for real device evidence even when this checker passes.

Report invariants: complete passed ordinary case (never cache), release GL error0,
translation[37,-19], Undo depths1/3, all seven ordered Add/move/tint/Undo/redo rows,
complete monotonic tile comparison union1..128 including transparent RGBA, equal
positive tint-control/actual changed-pigment counts, deterministic fixture seed/
sample count, bounded size, grain/paperCut0, matching operator/id/settings and
SHA256 of the exact UTF8 frozen brush JSON. Intentional overrides have unique
known fields and valid non-no-op before/after values. This is not a replacement
for full BrushValidate, native comparison, raw sensor/history checks inside the
probe or measurement of the original grainy brush's appearance/performance.

Optional independently captured host receipt schema:

```text
sourceRevision: exact 40-hex final candidate SHA
apkSha256, installedBeforeSha256, installedAfterSha256: matching 64-hex hashes
model: SM-N960U
api: 29
savedBefore, savedAfter: complete identical maps from relative path to {size, sha256}
requiredSavedPaths: explicit existing Working/current.joybrush and its current.joybrush.bak paths
```

Include every saved root/working archive in each manifest; do not copy historical
file counts or pass only a curated two-file subset. File path sets, byte sizes and
hashes must be unchanged. Existing saved-data receipt summaries can be retained
alongside this stricter manifest-based input, but a bare archivesUnchanged=true
does not satisfy this checker. The tool cannot independently determine whether
the supplied manifest is complete, fresh, from the named phone, or bound to the
report's capture; the host must record that provenance and preserve raw artifacts.
No device readiness/claim is granted by a supplied receipt passing validation.

If --receipt is omitted, success explicitly says device receipt not checked.
Even with it, success means only that supplied report/receipt invariants agree.
Full View save/reopen/context recovery, actual pen-up fade, cache reuse, Note9
speed and owner visual approval remain separate completion gates. Synthetic test
results must never be promoted to native/device proof.
