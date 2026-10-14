# Joint paint root and reset checkpoint — October 14, 2026

Verified engine source: `1f185f3348ffc03122c3fbbd92532ce5ff17f9c7`.

The opt-in engine publishes native physical stores, recorded sources/looks, agreement
metadata, document/paper and the complete undo cursor through one root assignment.
Mixed native changes on disjoint physical cels and recorded line/slab edits stage
before publication. Same-context reset prepares one empty root and validates both
facade handoffs; actual context loss abandons old resource names. Unconverted
writers refuse before mutation while bound. Ordinary painting remains unbound.

Independently counted results:

| Suite | Tests | Failures | Errors | Skips | Suites |
|---|---:|---:|---:|---:|---:|
| Core | 2309 | 0 | 0 | 4 optional | 207 |
| Backend | 613 | 0 | 0 | 0 | 59 |
| Android integration | 193 | 0 | 0 | 0 | 22 |

Evidence under `C:/Temp/jb-lead-ops/`: `live-joint-reset-core-xml.json`,
`live-joint-reset-backend-xml.json`, `live-joint-reset-native-xml.json`, and
the corresponding regression logs. Android integration completed in 2m47.
Backend includes actual engine mixed-history and lifecycle fixtures plus concrete
renderer context identity checks; these are not hardware execution evidence.

Exact source APK assembled in 53 seconds under the exclusive Gradle lock and
unchanged 3200MiB admission gate. Artifact:
`C:/Temp/jb-lead-ops/apk-1f185f334/joybrush-1f185f334-arm64.apk`.
SHA256: `67674D956E349191BC8E8DB5CD1E39B711F1A88353196FA0B39CE418E1EC53E1`.
Receipt: `joint-reset-apk-receipt.json`.

The new eager/cold joint GPU cases compile but remain unexecuted. Independent
canonical source plus seq0 CPU references, bound-root/public undo/redo identity,
disjoint native reads, untouched siblings and resource cleanup are their gates.
Luna's static review initially confused saved line-only indexes with eager caches;
root checked the actual slab-inclusive tile plan and the finding was retracted.
No production source change was made from that false finding.

Next integration source `6363d8e49c8a7aa3d3b94f285dc2fc854af1628b` merges
published Space/storage source `4bc16cef6` without conflicts. A Git comparison
confirms zero JoyBrush/core/backend/native module differences from tested source.
Its app assembly remains pending: fresh admission after owned watcher teardown
was 2468MiB, below 3200MiB. Preserve the earlier artifact, but prefer a newly
verified combined artifact before installation so Space is retained.

Fresh sandbox identity/API are correct and the phone is awake/unlocked; Joy Creator
is foreground, so the Home installation gate has not passed. An owner readiness
request is pending. No installation or new GPU launch was attempted.

Production base promotion, full-scene display, committed live capture, Select host,
structural/paper/media history and save/load/recovery remain open. This checkpoint
does not complete JB-5.20d, demonstrate physical pencil quality, or prove real GPU
bound publication. Existing archive/export guards remain in place.
