# Fresh engine cold source load — working implementation

Root worktree: C:/Temp/jb-live-current, base ddaf59cbe. This work is not yet accepted.
Read HANDOFF_20261015.md for previously verified source and exact APK evidence.

## Scope

ColdRecordedLoadPlan prepares detached bounded CPU inputs with actual shared structural
archive admission. It distinguishes every native paint cel, mask, lazy media store,
ordered physical source slab and saved line-look upload. Shared document cel IDs map
to canonical null physical addresses. Inactive, linked and empty physical owners remain
represented. Its limits and supported profile cannot be widened by a caller flag.

The internal GlPaintEngine.loadFreshRecordedArtwork only admits an initialized empty,
idle engine. It refuses existing scene/root, layers/document/history, orphan stroke or
media bookkeeping, playback and periodic state. It does not replace the View's engine.
Runtime upload and media budgets and actual paper catalogue/resources are checked.

Every texture is a fresh allocation with a primitive ownership ledger registered by
glGen before storage/upload. Native/mask/media maps receive their own uploads without
history. Source slabs remain caller-owned until root binding. The saved-scene factory
accepts only its distinct saved-look caches. Upload uses explicit formats, FBO and GL
checks, with tightly packed client data and saved/restored unpack/PBO/FBO/texture state.
No opening line replay or look composition is allowed. First edits still validate cold
footprints and dynamic replay constraints; cold admission promises no bounded latency.

Failure cleanup respects native/caller/scene/root ownership and physical context epoch.
Failed unbound scene close cannot make its remaining look uploads disappear from the
attempt ledger. Bound cleanup goes through joint reset. Old-context names are abandoned
rather than deleted in a replacement context. The empty-engine paper baseline is retained.

Two related lifecycle corrections: initWith clears ready before creating a replacement
context, and joint binding allocates its root accessors before the root assignment.

## Gates retained

This is internal diagnostic groundwork. No production Open, live raw capture, Select,
Working, recovery, thumbnail or export gate is removed. Regional lines remain refused
without persisted captured physical clipping. Source/media co-location and unsupported
plain multi-slab/non-normal display remain refused. The existing native path is unchanged.
Paper response of real shipped Ink is preserved; it is not cleared to simplify a fixture.

## Verification and remaining gates

- Full backend772/70suites passed3m51, zero failures/errors/skips:29 new plan tests
  and8 actual-engine admission/readiness/budget/paper tests. Independent XML receipt:
  C:/Temp/jb-lead-ops/live-cold-root-backend-repaired-xml.json.
- Initial770 run had one fixture failure: WC ERASE was rejected by BrushValidate
  before reaching the intended NORMAL-only GPU profile. The repaired fixture uses
  archive-valid MULTIPLY, explicitly asserts BrushValidate success, and preserves
  the NORMAL refusal assertion. Initial failure log/XML remain preserved privately.
- Sol found a real requested GPU-paper texture admission gap, including fluid assets;
  root added nonzero resource requirements before payloads. This is in the772 run.
- After772, root corrected cold RGBA8 filtering to ordinary LINEAR; floats remain
  NEAREST. Actual driver parameter checks were added to the cold probe. Added first-edit
  positive replay/composition assertions and debug flag consistency refusal too.
- Final backend repeat deferred safely at1920MiB after owned watcher pause (<2800MiB).
  No unowned app/JVM was stopped. Native regression and exact APK also remain pending.
- Actual cold loader GPU/replay/archive and failure/ownership evidence is still pending.

The earlier674d02f1 Note9 promotion acceptance does not exercise this new loader.
