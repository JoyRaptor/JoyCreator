# JOY BRUSH — ORCHESTRATOR LOG

Handover file. Read this first. **The orchestrator alone edits `ROADMAP.md`, commits and pushes.**
Subagents never commit, push or touch the board.

Promoted 2026-09-28. Rewritten 2026-09-29 (end of the overnight session).

---

## STATE AT HANDOVER

`:core:jvmTest` **665 tests, 1 failure** | `:androidkit:test` **90 tests, 0 failures**

Board: **22 Reviewed (xr)**, 26 Built, 38 Draft (new tonight), 11 Ready, 21 Outline.
Specs on disk: **88** (was 48).

### The one red test, and why it stays red

`ThreeFingerSwipeTest.anOverrideIsRememberedForTheBoardItWasMadeOnAndForgottenOnAnother` is **not a
code bug and must not be "fixed".** Decision 2's KDoc says an override can never be restored, and the
test checks exactly that at line 128. The same KDoc then says two boards can each keep their own
answer, and the test checks THAT at line 130 — after asserting at 128 that switching back does not
bring the override back. One object cannot satisfy both. It needs a Lead ruling: **is the override
per-board and persistent, or single and forgotten on switch?** If per-board, it becomes a
`MutableMap<String, SwipeMode>` and the test passes unchanged.

### Landed tonight (all pushed)

- **JB-1.08a** the fill pen as a brush (`engine fill`, `blend behind`, `BRUSH_VERSION` 2, shipped preset).
- **JB-2.20a** the Studio's 26 blend modes: generated 30 914-row golden table from the Studio's own
  Java, a drift check, a Python transcription of the GLSL, and 19 tests that never read the table.
  `DOC_VERSION` 2 for the nineteen appended constants.
- **JB-2.13a / JB-3.01** the export overflow guard, the honest GPU-parity claim, and the animation
  `holdFrames` / `addFrame` value / duplicate-id fixes.
- **JB-0.01 / JB-0.03b / JB-0.07 / JB-3.05a** four MAJORs: NaN stroke truncation, a `decodeChecked`
  door, GL context-loss honesty, and my own `seam + epsilon` bug.
- **JB-2.05a / JB-2.05b** two BLOCKERs from the adversarial round: `intersect` kept `this`-only
  tiles, and `Resample.over` was destination-over with a per-channel factor in a file whose own
  KDoc claimed the GPU's source-over.
- **JB-2.12a** perspective snap measured at the current point instead of the start.
- **OraExport** now REFUSES the nine modes SVG cannot express, rather than substituting a
  nearest-relative. This is what brought `:androidkit` back to green after the enum append.

### The four false claims the second review family found

All in KDocs. All corrected, and the corrections are the interesting part:

1. The **"independent GLSL cross-check" cannot see a GLSL change at all** — `glsl_model.py` never
   opens `BlendModes.java`, so editing the GPU's shader leaves the table byte-identical and every
   check exits 0. True claim now: three implementations agree, none comparing Java to GLSL directly.
   **Referred to the Lead** — closing it needs a GLSL-subset interpreter.
2. "A mode's ordinal IS the Studio's `modeCode`" — false for 22 of 27 (`ERASE_BELOW` is ours at
   ordinal 7). Nothing reads `ordinal`; the claim said the parity proof rides on it, which is
   backwards — the parity test walks by code and looks up by name.
3. "The other twenty are NAMED IN `needsWholePixelBlend`" — it is a negative list naming the seven.
4. "All twenty-seven modes are clamped in exactly one place" — `term` clamps ADD, and that clamp is
   part of the mode's definition.

---

## THE LESSONS THAT COST SOMETHING (all earned tonight)

**1. A test that keeps passing while quietly testing something else is worse than a red test.**
`BlendParityTest` wrote its subject as `BlendMode.entries.filter { it != ERASE_BELOW }` — correct for
an 8-entry enum, and silently "all 26 Studio modes" the moment nineteen were appended. Look for
subjects written as a filter or a derivation rather than an explicit list.

**2. A claim repeated in three places is one writer believing it three times.** The GLSL-cross-check
claim appeared in a shell script header, a KDoc and a spec. If one of the three is false, grep for
the sentence, not the file.

**3. Do not let a number speak for itself.** The JB-0.07 builder found `:androidkit` would not
compile, made a temporary edit to get a real test count, and REVERTED it byte-identically rather
than let "90 tests" imply a green module. That is the behaviour to reward in a report.

**4. ~~Verify a plausible finding before acting on it.~~ — **STRUCK 2026-09-29 at R36's order. I got this
one wrong, and the Lead says so in `LEAD_RULINGS.md`.** The sprite-weights finding ("a held frame exports at
the wrong speed") was **REAL**, and I dismissed it with three reasons, of which two were false and the third
was a category error. The app is **Java**: `app/…/sprite/SpriteSheet.java` exists and its `Preset` carries
`weights` (`hasWeights()`, 1..9999 via `SequenceTiming`). I wrote "there is no `SpriteSheet.kt` in this repo" —
I searched **one** module and generalised from it. The lesson was never "verify before acting"; it was
"verify before **refuting**", and I used it as permission to close something I had not actually looked for.
**A plausible finding is not a refuted finding until you have looked in the module it names.** The lesson was
also duplicated as a "NOT a defect" note on JB-4.02, which is now struck. The defect itself is fixed by
**JB-4.03c** (`Clip` gains `weights`; `SpritePacker` writes it under the app's own rule), and the bad test
entry goes with it.

**5. The rule in the file beats the prompt.** All four spec writers refused to mark their specs Ready
and cited ROADMAP §3, against my explicit instruction to mark buildable ones Ready. They were right.

**6. A builder may be right and the reviewers wrong.** The JB-0.01 builder rejected both reviewers'
`require` because the class is built on the live drawing path, where throwing kills a stroke in
progress. Verified: every other zoom consumer in the codebase falls back.

**7. Unused parameters are smells, not style.** `wholePixelTerm` took an `out` I never used. A
reviewer caught it; it is an oversight because it is one.

---

## WHAT I LEFT, AND WHY

- **JB-0.08b** has 3 MAJORs (save requests are not serialised; an explicit *Save a copy* during an
  autosave is silently discarded). It is APP-FILE work and the Lead owns the serialised order
  `D.02a → D.02 → D.02c / D.05`, one at a time. **Held deliberately.**
- **JB-2.02**'s MAJOR (mid-stroke handoff to the gesture machine is dead) needs new API and a
  T1/Lead call. **Referred.**
- **JB-5.10**'s known-red timing test now has a CAUSE: `TO_INTERSECTION` has no spatial index, so 50
  fully-overlapping lines cannot prune. **Lead call** — grid it, or narrow Decision 5's promise. I
  did not relax the bound; a timing bound loosened until it passes is a wish, not a test.
- **JB-5.02**, **JB-8.03**, **JB-1.05b** — two reviewers disagreed, or the fix changes a contract.
  Under §5b these need reproduction or a ruling, so they are recorded, not fixed.
- **Phase 7 (puppet/character) is all Draft and honestly so** — the Avatar Studio surface is
  undefined, and a rig format invented by a spec writer would be worse than no spec.

## OPERATIONAL

- `CoderAgent` returned EMPTY reports and wrote nothing on 5 of 8 dispatches. **Use `general`.**
- Subagent shells are denied; they may run only `./gradlew -p joybrush :core:jvmTest` (and
  `:androidkit:test` for androidkit work). They cannot run git. They cannot date `build.log`.
- **Never use PowerShell `Get-Content`/`Set-Content` on repo files** — it adds a BOM and mangles
  em dashes. Use the editor tool, or `[System.IO.File]::ReadAllText/WriteAllText` with an explicit
  `UTF8Encoding($false)`. Verify with a U+FFFD count afterwards.
- `-replace` in PowerShell binds tighter than `+`, so `-replace "a","b" + $c` does not do what it
  looks like. And `[regex]::Escape` on a REPLACEMENT string injects literal backslashes. Both bit me
  tonight, and the second one put 38 `\ Draft` cells on the board before I caught it.
- Astral-plane emoji need `[System.Char]::ConvertFromUtf32(0x1F7E9)`, not `[char]0x1F7E9`.
- **Check the tree before re-dispatching** — an empty report may still have written files.
- Board-file row matching: rows read `| [JB-4.02 Tap cells in order...](specs/...)`, i.e. the LINK
  wraps the whole title, not just the id. A regex expecting `](` right after the id will not match.
