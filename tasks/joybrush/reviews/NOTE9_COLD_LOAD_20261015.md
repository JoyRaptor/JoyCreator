# Cold engine source load — exact Note9 checkpoint October15

PASS on source6d8651b4988b0f5f1ca0c4e004a7f5530b472b9b. Preserved arm64 and
installed base.apk SHA256 both equal
98dca0eee7900484617526fe1c18187aad1e3a4190b583166c93dc7f03f85fbd.
Assembly44s. Fresh known SM-N960U/API29, awake/unlocked/Home passed before install;
package update time changed. No uninstall. Both shared locks held; runner observed
completion and released only its own claims, restoring the owned watcher hidden.

## Independent device counts

All10 distinct expected Ink/Watercolour cases pass. Earlier8 joint/promotion cases
pass again, plus2 new recordedPromotionColdProbe cases. Total2,082 full-tile exact
RGBA8 rows with0 differing channels/pixels/max delta; rows are repeated checkpoint
comparisons, not2,082 distinct tiles. Sanitized count receipt is beside this report.

Each new cold case has307 exact rows. Its input comes from actual joint source
snapshot -> JbArchive.write -> JbArchive.read -> owned CPU plan -> throwaway artwork
reset -> loadFreshRecordedArtwork. It uploads8 distinct physical source slabs,
4 separate line looks and1 native sibling tile, opens with0 line preparations and
0 compositions, and remains unresolved through public display reads. The first edit
then makes2 line preparations and8 compositions; actual public undo/redo restores
both appearances exactly. Per case26 real GPU filter queries confirm LINEAR RGBA8
sampling. Complete source/cache/native/pool/global ownership checks and reset pass.

Across all10 cases:16 in-memory archive write/read roundtrips,186 exact payload byte
comparisons;1,460 external readbacks,2,036 GL-error drains,1,460 framebuffer checks,
1,802 feedback/ownership checks. All failure counters0. Engine-internal checks are
not added to these external totals. The post-cold-edit source archive is checked
separately, including document, raw records, frozen brushes and payload arrays.

Working separately and ALL3 root working-copy archives were hashed before install,
after install and after probe: unchanged. Three includes Working where present.
No owner's drawing is read as probe source or changed; the benchmark engine is unsaved.

## Source checks

- Full backend772 tests/70suites,0 failures/errors/skips PASS3m13 on exact6d8651b49.
  Includes29 owned-plan and8 actual engine admission/readiness/budget/paper tests.
- Native201 tests/24suites,0 failures/errors/skips PASS2m10, including Activity/bench
  compilation. Exact receipts: live-cold-root-{backend-final,native}-xml.json.
- Initial770/1 fixture failure retained: ERASE was invalid watercolor metadata before
  the intended NORMAL-only GPU gate. MULTIPLY fixture now explicitly passes the actual
  brush validator and is refused by the GPU profile, preserving the target assertion.
- Final engine blob d526a1e08fb968d68b5700ce7ae466d4f71af71e; plan blob
  88f087748ea0fa16d6dd8fe262f8ce7f4f6ca068.

Private complete evidence: C:/Temp/jb-lead-ops/recorded-phone-cold-root-6d8651b49-*;
artifact under apk-6d8651b49. Do not publish raw base64 logs or device identity.

## Limits and next work

This proves the internal flat cold-engine/source/edit/history/archive slice, not
production Open/Working/recovery/live capture/Select, physical quality or performance.
The cold GPU fixture contains no masks, media or upper ordered slabs; CPU descriptors
and tests cover them, but their runtime uploads still need independent device proof.
Allocation/upload failure injection and hostile unpack/PBO-state probes remain separate
requirements before a View replacement contract can use this loader. CPU accounting is
bounded admission, not a whole-process GPU/heap guarantee. Dynamic replay limits remain
first-edit refusals. Regional line archives still refuse without persisted physical
capture clipping. All existing public raster/source admission gates stay in place.

Next isolated work appends captured physical domain/integer placement to recorded
source with versioned codec, complete history and explicit unsupported-consumer gates.
Helpers' authored format/edit tests are not included in this6d8651b49 checkpoint.
