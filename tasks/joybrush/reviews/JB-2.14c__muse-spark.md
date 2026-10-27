# JB-2.14c — different-family cross-review (muse)

- Reviewer: `muse-spark-1.3-contributor-free`, family Muse Spark (Meta) — different family
  from writer/reviser `openrouter/stealth/space-bunny-alpha`.
- Spec: `tasks/joybrush/specs/JB-2.14c_export_psd.md`.
- Method: read-only. No gradle, no edits.

**Verdict: SEND-BACK (writer fixes M1–M4, then READY pending Lead one-word Q4 + yes/no Q5).
No BLOCKER. R38 substance applied, Q4/Q5 correctly left open, provisional honestly marked.**

## No BLOCKER (Q4/Q5 open with stop-instructions — PASS)

Status names Q4 (merged alpha) + Q5 (luni/lsct) as the only opens; D7/D13 restate; test map
(`:369`, `:374-375`) and Tests (`:399`, `:423`) gated on them; stop rule (`:481-486`) says STOP,
set Blocked, "never invent a PSD resource block, a version number, a blend key or a channel
count"; Q4 offers (a)/(b)/(c) but asks "Which is it?"; Q5 recommends but asks "Is that right?".
No silent decision.

## MAJORs

**M1 — `LIGHDER_COLOR` typo in pasted enum + test partition; would not compile if copied.**
Spec `:159` and `:404` spell `LIGHDER_COLOR`; tree `DocModel.kt:156-157` is `LIGHTER_COLOR`.
Table `:269` already correct. Fix both places.

**M2 — ~25 file:line citations stale (tree moved: DOC_VERSION 4, Paper v4, mask/clip R48,
OraExport +3). Substance true, numbers false — a Stop-rule builder would STOP.** Corrected
readings: `OraExport.write :168`→**:164**; `omittedBecause :346-355`→**:349-358**;
`opacityOf :508-514`→**:511-517**; `tileSource :358-360`→**:361-363**;
`compositeOp :472-498`+`:457-462`→**:475-501**; `DocException :8`→`DocJson.kt:15`;
`RectPx :36`→`DocModel.kt:40`; `LayerKind :92`→`:120`; `Board :69`→`:97-106`;
`BlendMode :124-131`→`:152-159`; `Layer :139-150`→`:167-186` (opacity `:173`);
`layers/active :159/:160`→`:195/:196`; `DocOps.celFor :178`→`:218`; `key :185`→`:225`;
`TILE_BYTES :176`→`:164`; `render :196(-221)`→`:184(-209)`; `k=0 :213`→`:201`;
Q4 `:207-219`→`:197-206`; paper `:426`→`:435`; tile `:290`→`:287-289`;
`OraExport :134-139/:134-141`→`:130-136`; `JbArchiveException :34`→`:47`;
`JbContents :44-51`→`:57-64`; `Entry.pixels :278-285`→`:274-288`. Table `:560-569` repeats.
Verified correct: `BlendRgb.kt:294-304` (SUBTRACT `max(b-s,0)`, DIVIDE `min(b/max(s,EPS),1)`),
`TileSource :24`, `MAX_REGION_PX :89`, `JbArchive TILE_BYTES :31`, `androidkit/build.gradle.kts:19`.

**M3 — ruling number mislabel: "R38" for PSD is R39.** R38 (`LEAD_RULINGS.md:404-444`) has no
PSD; the PSD ruling is R39's `JB-2.14c` bullet (`:460-465`). Correct everywhere.

**M4 — owner-area decision pointer wrong.** Spec `:9` cites "Decision 9" for the test-only
`PsdReaderForTest`; Decision 9 (`:334-335`) is top-first — the test reader is Decision 15
(`:354-357`). Correct to Decision 15.

## PASS notes (checked, holding)

- SUBTRACT/DIVIDE = base-minus-blend / base-over-blend clipped, keys written; 23+4=27 partition
  matches enum; other 21 spellings unverifiable in-repo and correctly PROVISIONAL with the Krita
  oracle (`EXCLUSION`/darker/lighter → null may be over-strict but safe-refuse + check covers).
- OraExport shape mirrored (same 5 params; `List<String>`-vs-`Unit` justified; omit rule verbatim
  with equality test pinning drift; Do-not forbids drift).
- Contract compiles on paper (all imports resolve; `JbContents` same-package; no `StrokeRecord`
  import correct with PsdLayer gone; `toBytes` typed; Decision 6 stream deleted; PsdLayer gone).
- Wordings exact + tested (INK reason verbatim + layer-named; ERASE_BELOW offer contains
  `flattened`; flattening explicitly not built).
- Owner area/stop/Do-not sufficient except M4; R30-item-3 respected; R11/R19/R23 covered.
- Provisional honestly marked with verify-before-trust + Krita owner check.

Fix M1–M4 → READY pending Lead Q4 + Q5.
