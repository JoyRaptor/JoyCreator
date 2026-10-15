# Note9 actual native-paint promotion — October15

**PASS on exact source674d02f1f2d7210201d69e3689c988325fb0183f.** The preserved
arm64 APK and installed base.apk SHA256 both equal
`2a0d9b1200e5272093158e6b13b6fd95a8c9991f0dfb9bdfa03b80ebd8d3069e`.
App assembly59s; package update time changed. Fresh known sandbox SM-N960U/API29,
awake/unlocked/Home readiness passed before installation. Both shared locks held for
install/probe; the probe completed and only owned locks were released. No uninstall.

## Independently counted device evidence

All8 unique expected Ink/Watercolour cases pass: eager/cold joint plus flat/regional
native promotion. **1,468 full-tile exact RGBA8 comparison rows,0 differing channels**;
these include repeated source/display/history checks, not1,468 unique tiles.

| Case | Ink rows | Watercolour rows | In-memory archive roundtrips per brush |
| --- | ---: | ---: | ---: |
| Joint eager |141|141|0|
| Joint cold |137|137|0|
| Promotion flat |239|239|3|
| Promotion regional |217|217|1, before any raw record |

Eight in-memory JbArchive.write/read roundtrips complete, with90 independently counted
byte-array payload comparisons and exact document/raw-record/frozen-brush values.
Flat source roundtrips cover promotion, RawAdd and final transformed line. Regional
roundtrip covers only the line-free promoted state; line-bearing regional saves remain
refused pending persisted captured clipping. No upper-slab/media payload is exercised
by these archive fixtures.

External counters:1,124 readbacks,1,536 GL-error drains,1,124 framebuffer checks and1,404
feedback/ownership checks, all failure counters0. Engine-internal checks/readbacks are
not added to those external counters. Nonzero-alpha, changed-reference and wrong-order
controls pass. Promotion performs0 opening replay/composition calls, preserves physical
current/inactive/empty cel scope, and keeps canonical sources separate from cached looks.
Actual RawAdd/move, public undo through older native paint and redo retain original
complete Step/cursor identity. Reset cleanup completes in every case.

Working separately and ALL3 root working-copy archives were hashed before installation,
after installation and after probe: unchanged throughout. Bench uses a throwaway engine;
it does not load/save/alter the owner's live drawing. The three-file count includes the
Working archive where present; it does not mean three extra files besides Working.

## Source and regression identity

- Engine alias fix25e365f3, pure unused domain valuea07806681, diagnostic inventory674d02f1.
- Full backend712/67suites,0 failures/errors/skips PASS2m2; exact blobs/count receipt
  `C:/Temp/jb-lead-ops/live-promotion-inventory-backend-fix-xml.json`.
- Native201/24suites,0 failures/errors/skips PASS2m11 on a07806681; production engine and
  native source unchanged by the later diagnostic-only correction. APK compiles the correction.
- Focused new pure captured-domain28/1suite passes. Prior full core2309 with4 optional
  skips remains separate evidence; this is not a newly executed full2337 run.

Initial actual native shared-cel alias refusal (178e72a3) and diagnostic computed-property
reflection failure (a07806681) are retained privately. The fix preserves eligible original
history Steps and keeps new ambiguous writes refused; no test guard was weakened.
New no-driver inventory test initially used the wrong factory API; repaired with the
actual RecordedScene.create factory and typed RecordedCel, with assertions unchanged.

Private complete reports, captured log, install/Working receipts and independent count:
`C:/Temp/jb-lead-ops/recorded-phone-promotion-674d02f1-*`. Raw report chunks are not copied
into the repository; do not print device serial or base64 logs. Sanitized receipt is beside
this report. The capture runner now retains successive log windows to avoid ring eviction.

## Boundaries

This verifies the exercised kernel transfer/display/history/source-archive scenarios.
It does not activate live committed capture, real Select, arbitrary regional transforms,
media on recorded planes, cold whole-document loading, Working/recovery, thumbnails/export,
performance or pencil quality. Preserve full-resolution approved watercolor and all UI
locks. View raster-save/load guards stay until complete source routes are verified.

The next JbArchive structural-preflight extraction is authored/unverified separately and
is NOT in this APK or its reported712 tests. Root will verify before publishing that code.