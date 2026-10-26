# Atlas parked ownership repair candidate — 2026-10-25

Base: helper-room 8a0680ced2fe2a740f545fa383736f68ae7cf965.
Candidate: 34b27b6f8091c07ea9115b5f80ea61abf6646088
(preceded by ffbd23d66 and 4c18b39c7).

This isolated source candidate is NOT activated or merged into the coordinator.
Luna prepared the bounded repair; root reviewed and required two follow-ups.
Only git diff --check has passed. No compilation, Android test, GPU or saved-data
acceptance is claimed. Existing park/unsupported activation guards remain.

The reviewed failure trace was a completed tile evicted with current=10,next=11,
then reloaded as a ring neighbor. A ring visit must retain the canonical parked
owner and must not restore current into both resident halves then promote stale
resident state across the next step. The repair distinguishes borrowed ring
residency from FULL owners, restores a FULL saved next half explicitly, promotes
borrowed-to-own tiles by reloading FULL, and discards borrowed copies before the
next step reads. The host stepDone callback actually occurs after the engine's
phase flip, with no intervening stage read; this candidate does not change that API.

Partial ring planes must never become saved state. Root review found that this
requires guarding direct writeBackSlot and store-flush paths, not only the
residency callback. The candidate centralizes that guard, excludes borrowed
residents from store flush reservations/writes, checks pins before changing the
ownership marker, and clears borrowed copies during keyed or full invalidation.

Acceptance still required:
- Compile against this exact source and independently count relevant test XML.
- Exercise the real MediaAtlas host, not only the synthetic ProbeBlockHost, with
  forced nine usable resident slots and a seeded multi-block world.
- Prove own -> eviction -> ring reload -> flip -> second eviction, and ring -> own
  promotion, for odd/even steps, dirty/clean victims and no-input/input boundaries.
- Compare raw current/next paint, paper, water, flux and run planes against the
  canonical reference after each step; require changed wet pixels, real evictions
  and zero GL errors. Include negative/far coordinates and halo/corner transport.
- Verify borrowed tiles never publish partial stores, and FULL writes still occur.
- Verify interrupted jobs, pinned refusal and load failure preserve ownership.
- Resolve phase/look coherence, finished evicted state persistence (P3), park
  exhaustion and resource admission (P4) before any production activation.
- Save/undo/reopen equivalence and operator/look correctness need independent
  production-host evidence; this ownership repair alone cannot establish them.

The current production host is nested in a GL-backed MediaAtlas. Existing fake
residency tests do not exercise load/park ownership; no duplicate toy model was
added and claimed as proof. PAPER/RUN restoration and complete optical/phase
coherence remain device gates even though the half-selection source is repaired.

## Admission rollback follow-up — 2026-10-26

The next source candidate adds begin/commit/rollback hooks to the real
MediaAtlasResidency transaction. MediaAtlas snapshots only the incoming and
victim ownership entries, borrowed/ground markers, park handle/counters, and
the previous residency table. During staging it reads old parked units without
returning them to the allocator; victim units taken during the attempt are
tracked, and replaced old units are deferred until post-publication commit.
Failure restores the maps and temporary table, returns newly taken units, and
releases a pool created only by the failed admission. The existing A1
MediaAtlasResidency test harness now injects load, eviction, and write-back
callback failures and checks rollback/retry lifecycle ordering; these tests
have not been run in this source-only pass.

This is park ownership and atlas-table rollback only. It does NOT make
`writeBackSlot`/`writableMediaTiles` durable writes atomic if a store or GPU
write partially succeeds before throwing; that needs separate COW transaction
work. No real MediaAtlas GL host or forced-capacity ownership test is added.
Compilation, Android tests, GPU/save gates, and P3/P4 resolution remain
outstanding; no activation or production-safety claim follows from this patch.
