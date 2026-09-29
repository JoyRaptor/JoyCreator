# Adversarial review — JB-4.03a Sprite sheet packer + SpriteLab sidecar

- Reviewer: muse-spark (cross-reviewer; builder was an unrelated subagent — different family).
- Task status: 🟧 Built. Commit reviewed: `3645f0c3`.
- Spec reviewed: `tasks/joybrush/specs/JB-4.03a_sprite_sheet_packer.md` (contract, sidecar keys, tests 1–5 + decided 1–6 / open 7–9).
- §5b checks: diff touches only NEW `export/SpritePacker.kt`, NEW `export/SpritePackerTest.kt`, and the spec's Questions appendix — inside the owner area; no app file touched per "Do not". Spec command run by me 2026-09-29: `SpritePackerTest` 24/24, 0 failures (full `:core:jvmTest`: 335 tests, 1 failure — the triage-added JB-5.10 perf reproducer, unrelated to this task; see closing note).
- Severity scale per §5b: BLOCKER / MAJOR / MINOR.

## Findings: none. Verified instead (proof for each attack surface)

1. **Sidecar matches the app's contract key-for-key.** Compared against `app/.../sprite/SpriteSheet.java toJson()` (`:587-698`): order `spriteSchemaVersion, id, name, sheetUri, cols, rows, fps, presets?, cellNames?` identical for the non-sequence sheets this packer emits (it never emits sequence/margin/spacing/order/bgKey/cells/weights keys, exactly as specified); preset keys `id, name, type, fps?, frames` identical (`SpriteSheet.java:639-645` vs `SpritePacker.kt:234-242`); sheet `fps` always written both sides; clip `fps` only when `> 0` both sides; `cellNames` last both sides; schema version 1 == `SPRITE_SCHEMA_VERSION` (`SpriteSheet.java:29`). Sparse-write discipline matches, so absence parses by construction on both readers.
2. **Integer-overflow discipline holds end to end.** `cellBytes`, `rows`, `width/height`, `pixels` all in Long (`SpritePacker.kt:164, 197-200`); the `pixels * 4 > Int.MAX` refusal (`:201-203`) precedes every `toInt()`; `sheetStride`/`left`/`from` offsets provably in-range after it (width·4 ≤ width·height·4 ≤ MAX for height ≥ 1; `cellW·4` bounded by the real input arrays). A hostile `cellW/cellH/cols` cannot pass the size check without the bytes to back it — an overflowed check that passes is structurally impossible here, per the code's own comment.
3. **All six decided Questions hold in code:** empty pack refused (`:157-159`); `cellNames`/clip frames address populated cells only, `0 until cells.size` (`:174-179, :189-194`); duplicate clip frames allowed, out-of-range refused (`:189-194`); empty-frames/unknown-type refused (`:181-188`); sheet fps 0 written, negative/NaN/Inf refused (`:160, :185-187`); `cellNames` ascending regardless of caller map order (`:248`).
4. **Pixel copy exact:** zero-filled transparent base, row-at-a-time `copyInto` with offsets traced in-bounds (`:208-220`); straight-RGBA8 in/out with no conversion step to get wrong (compatible with `RegionRenderer.render`'s straight output).
5. **Determinism:** sorted cell names, caller-ordered clips, stable pretty-print, omit-when-default — re-export churn impossible from this side.
6. **Open 7–9 honoured as specified:** blank names passed through (documented, reader trims); no max dimension invented (Q8 leaves it to the PNG writer); `assertEncodedSize` handoff implemented with a file-naming message, to be called before either file lands (`PackedSheet.kt:72-77`).

## Explicitly not filed
- `sheetFileName` written verbatim (a `../` name would resolve wherever the *reader* resolves it): the spec mandates verbatim, and the app's own comment (`SpriteSheet.java:583`) says the URI is the caller's to convert — reader-side concern for the writer task, not this one.
- Sheets up to ~2 GB allocate-or-die rather than refuse: spec Q8 explicitly assigns the ceiling to the PNG writer. Endorsed, not re-filed.

## Recommendation
No send-back. No BLOCKER, MAJOR, or MINOR open. Cleanest Built of the round on the overflow story.
