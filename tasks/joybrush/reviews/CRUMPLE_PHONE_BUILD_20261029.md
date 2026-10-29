# Isolated crumple resolution evaluation — October29

Checkout C:/Temp/jb-paper-evaluation-20261029; branch lead/paper-evaluation-20261029.
Runtime baseline cd1e48805 combines preserved lead ace333729 with upstreamaa6cc4143
runtime/build changes:227 paths, NONE overlapped lead changes. Existing lead docs,
tooling and gitignore retained, no shared primary checkout or branch edited.
This selective integration is not an assertion of a full main/lead merge.

Overlay is9 verified runtime files/7662021bytes, new eval IDs at2048/1024/512.
Original29 surfaces/32 looks retain their parsed contents/defaults; new totals32/35.
No engine, texture-cache, loader, shader, activity or persistence behavior is changed
by the overlay. Test resources contain independent PIL rawRGBA PNG hashes; JPEG
checks cover dimensions and opacity rather than cross-decoder byte identity.

Source-only tests: production PaperCatalogues parser and mapping; actual packaged
BitmapFactory path; packed profiles unchanged; master height expansion B/A. Native
Robolectric is host evidence only, not Note9 decoder/GL/feel or owner acceptance.
All actual tests/builds/device receipts remain pending until recorded below.

Baseline checkout C:/Temp/jb-paper-baseline-20261029 atcd1e48805 exists. Its first APK
admission deferred at2045MiB, no JVM/job launched, shared claim released. No success
or APK size delta inferred. Fresh resource admission is required for every job.

Current artifact map: [source/size integration receipt](CRUMPLE_EVALUATION_INTEGRATION_20261029.json).
Full45 master work stays stopped7/45. Sources/diagnostics excluded from APK. This
checkout is evaluation-only; no runtime change is pushed to joy-creator.

## Production host gate CLOSED GREEN

Frozen cbc693e97068c2f697d5fd5d51cfeb226dbdbde2 passed4 tests/0failures/errors/skips,
SUCCESS3m48s/exit0, one independently counted XML suite. [Receipt](CRUMPLE_PRODUCTION_DECODE_20261029.json).
Actual packaged BitmapFactory loader under native Robolectric preserved independent
PNG raw-channel hashes, including profile alphaE[h²]; master height B/A expansion
and production catalogue acceptance passed. JPEG scope is dimensions/opacity.
This is host Android evidence, not physical Note9 decoding/GL/residency/feel.

Matched baseline APK job now admitted under shared lock, exec64321/log
C:/Temp/jb-lead-ops/paper-baseline-cd1e48805-20261029.log. No overlay APK or phone
job yet. First deferred attempt remains in history; admission was retried only after
fresh available RAM changed. Baseline code is cd1e48805, overlay adds only9resources,
three catalogue pairs, tests/docs. No engine/cache/loader behavior changed by overlay.
