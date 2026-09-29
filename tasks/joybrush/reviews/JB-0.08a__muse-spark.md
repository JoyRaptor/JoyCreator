# Adversarial review — JB-0.08a The `.joybrush` archive (atomic write, read, zip-slip guard)

- Reviewer: muse-spark (cross-reviewer; builder was an unrelated subagent — different family).
- Task status: 🟧 Built. Commit reviewed: `571d458b`.
- Spec reviewed: `tasks/joybrush/specs/JB-0.08a_document_archive.md` (layout, contract, decisions 1–4, tests 1–5).
- §5b checks: diff touches only NEW `androidkit/.../io/JbArchive.kt`, NEW `JbArchiveTest.kt`, and one `testImplementation` line in `androidkit/build.gradle.kts` — inside the owner area; no Android APIs in `JbArchive`, engine/view untouched. Suite evidence: `JbArchiveTest` 32/32, 0 failures (verification run 2026-09-28 19:11; fresh `:androidkit:test` re-run additionally blocked — see top note in JB-0.02b file). All five spec tests map to a 32-test suite (round-trip incl. negative keys, mimetype STORED-first, zip-slip/wrong-size/missing, unknown-ignored, save-twice `.bak` + mid-save failure atomicity).
- Severity scale per §5b: BLOCKER / MAJOR / MINOR.

## Finding 1 (MINOR): a kill between the two renames leaves no main file, and `open` cannot recover
Proof: `JbArchive.kt:134-151` — after `file.renameTo(bak)` succeeds, the drawing exists only as `.bak` + `.tmp` until `tmp.renameTo(file)` completes. Spec decision 4 and test 5 cover a *throw* before the final rename (`.tmp` deleted, original untouched: `:152-158`), but a process kill/power loss *between* the renames is not a throw: the next `open(file)` reports "there is no file" (`:431-432`) while both halves sit beside it. Nothing is lost (old in `.bak`, new in `.tmp`), but recovery is manual. Fix direction (JB-0.08b, not here): on open, if main is missing but `.bak`/`.tmp` exist, offer recovery instead of "no file".

## Finding 2 (MINOR): leading directory entries break the mimetype-first rule and consume the entry budget
Proof: `JbArchive.kt:284-294` — `count++` and the `count == 1 && name != "mimetype"` refusal run *before* the `entry.isDirectory` skip (`:291-294`). A zip that is byte-identical in content but carries leading `layers/` folder entries (what most desktop zippers emit) is refused, and every directory entry counts toward `MAX_ENTRIES`. Self-produced files never contain them (`write` emits no directory entries), so this is interop-only: "open a `.joybrush` re-zipped by another tool". Either skip directories before counting (one-line move, changes refusal behaviour — Lead call) or document that only this writer's zips open.

## Finding 3 (MINOR): a `strokes.jbs` for a PAINT cel (or a `strokesFile`-less INK cel) is accepted without a word
Proof: `documentFrom` (`JbArchive.kt:415-421`) checks a strokes entry's cel *exists* (`declared.containsKey`) but not that the cel is INK or names a `strokesFile` — while the write side is strict about cels it knows (existence + listing + shape + size: `checkTile`, `:467-485`; strokes-without-cel refused at `:186-192`). So a hand-built archive can attach ink to a paint cel and read clean; whatever consumes `JbContents.strokes` (JB-0.08b) will either ignore it (masking corruption) or misapply it. The doc's own rule 8 (paint↔ink separation) is not enforced on this map. Fail-open toward ignoring; suggest refusing, symmetric with the missing-strokes refusal at `:402-406`.

## Verified (proof)
- Decision 1 (write refusals): doc-validated first (`:170-173`); unlisted/unknown-cel/misshapen/wrong-size tiles refused (`:467-485`); strokes-without-cel refused (`:186-192`); strokesFile-without-strokes refused (`:196-202`); hostile layer ids refused on write via `safeName` (`:487-503`) — so a `../` layer id fails loudly at save, not at someone else's open.
- Decision 2 (read refusals): mimetype STORED + first (`:288-308`); missing document (`:365`); wrong-size tile via the `TILE_BYTES + 1` read (`:328-333`, refused as wrong-size, not overflow); missing listed tile (`:394-401`); missing strokes (`:402-406`); extra tile (`:408-414`); `..`/absolute/colon/backslash/control/empty-segment/Windows-`".. "` names refused on read AND write (`unsafeReason`, `:514-538`); duplicates refused (`:283`); declared-vs-actual size mismatch refused with the documented `> 0` data-descriptor idiom (`:555-578`).
- Decision 3 (all-or-nothing save): tmp + `fd.sync()` + bak dance (`:124-159`); every exception path deletes `.tmp` and rethrows a single `JbArchiveException` type (`:84-88`, `:152-158`).
- Budgets are the file's own, never the archive's claims: per-entry caps + 1 GB total `ByteBudget` (`:99-111, 611-619`); allocation bounded by `limit + CHUNK` (`:556-572`). (A ~1 GB hostile archive can still OOM the phone rather than refuse cleanly — acknowledged in the KDoc's deliberate Error-not-caught contract, `:84-88`; the data model requires full in-memory contents, so this is a documented tradeoff, not filed.)
- Determinism: tiles/strokes sorted before writing (`:178, 185`) + stable `document.json` ⇒ byte-identical re-saves; `TILE_BYTES` derived from the engine's `TILE_SIZE` (`:31`), not a second literal.
- R3 loop closed for documents: read validates (`:388-391`), so the version sentence is reachable — my round-1 "decode never validates" handoff is answered at this boundary.

## Recommendation
No send-back. Three MINORs for the JB-0.08b author (recovery UX, interop strictness, strokes/paint strictness). No BLOCKER or MAJOR open.
