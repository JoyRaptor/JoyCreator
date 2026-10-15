# Structural source preflight — October15

Source dc940101 exposes internal JbArchive.requireStructure(contents), borrowing stable input
only for the call. The writer and preflight share complete payload declarations, safe generated
paths, source/codec and frozen-brush checks. Preflight performs no ZIP IO or line replay; it is
not a retained certificate or immutable snapshot. Save-only geometric look checks stay before
output. Reader/legacy compatibility is unchanged, including alternate numeric spelling and known
empty/noncanonical stroke metadata; the future loader applies its own stricter profile.

Full backend735/68suites PASS3m12,0 failures/errors/skips, including23 new actual admission
regressions. Independent receipt C:/Temp/jb-lead-ops/live-contents-structure-backend-xml.json:
archive blob10924295eb70a48fab16c98122f7f724fda78846. Source path tests, both-direction completeness,
wrong sizes/operators/frozen references, mixed source write/read and save-vs-preflight geometry
are exercised. No new APK/phone run; this is an IO-only prerequisite and changes no View/GL APIs.

The upcoming owned ColdRecordedLoadPlan is isolated/unverified, not included in these735 tests.
No cold whole-document load, Working/recovery, live capture or Select activation is claimed.