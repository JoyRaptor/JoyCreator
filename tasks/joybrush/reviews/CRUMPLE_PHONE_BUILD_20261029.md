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

## Matched APK and protected install gate CLOSED

Baselinecd1e48805 APK169549163bytes, SUCCESS8m8s; evaluation8420d5173 APK177213152bytes,
SUCCESS4m35s. Measured difference7663989bytes/+4.52%; nine new stored paper entries
account for7662021bytes. [Exact artifacts, ZIP differences and install receipt](CRUMPLE_PHONE_RESULT_20261029.json).
Both preserved APKs independently rehashed; no diagnostics or other44 materials shipped.

Canonical project is C:/+Projects/JoyCreator (renamed October27). Trusted private
sandbox mapping was found there. FreshSM-N960U/API29/awake/unlocked/Home and exclusive
claims established before installation. Exact evaluation APK installed by upgrade in
place; installed SHA36f9492e57c5de8f3aff896ddf6cebde0c5c2d589b2582d3d5b0daaf333b2e57.
Complete external JoyBrush saved-file path/length/hash set33 files unchanged. Before/
after full private manifests and ADB trace live in C:/Temp/jb-lead-ops/paper-phone-install-20261029;
only their aggregate digest/count is committed. Temperature28.0C to28.1C. Claims released.
No activity launched, drawing opened/edited, uninstall, data clear or rotation change.

Owner comparison: open a fresh scratch drawing, use Paper and choose matching
Crumpled paper2048px/1024px/512px (spaces in actual labels) under BOTH Background and
Surface; compare one resolution at a time. This is a testing instruction, not a
completed help article. Phone decoder/GL upload/visual seams/brush feel and creative
acceptance remain OPEN. Installed is not equivalent to proven good paper.

The new global source-of-truth files are tasks/ROADMAP.md, tasks/BOARD.md and job
cards under tasks/jobs/. The isolated runtime baseline preserves newer published
app changes without editing the main folder. It does NOT complete HYG-2 or land
JoyBrush's integrated runtime on main. Remaining38 masters remain separate work.
