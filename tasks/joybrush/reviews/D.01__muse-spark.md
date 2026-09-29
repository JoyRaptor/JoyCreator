# Adversarial review — D.01 Joy Brush colour tokens

- Reviewer: muse-spark (cross-reviewer, no vision — see scope note; builder was an unrelated subagent — different family).
- Task status: 🟧 Built. Commit reviewed: `6f0d317f`.
- Spec reviewed: `tasks/joybrush/specs/D.01_joybrush_colour_tokens.md` (decisions 1–5, verification, Do-not, Questions 1–4 with orchestrator rulings).
- §5b checks: diff touches only NEW `jb_tokens.xml`, NEW `JbColors.kt`, NEW `tools/check_joybrush_tokens.py`, the ROADMAP row, and spec questions — inside the owner area; `studio_tokens.xml` and app files untouched per "Do not". Check script RUN BY ME: `py tools/check_joybrush_tokens.py` → "tokens in sync" (30 tokens: 2 owner room lines, 4 same-file references, 24 mirrors — 23 against `studio_tokens.xml`, 1 against `Studio.java`), exit 0. Watcher-green per the board Who note.
- Scope note: T2-V assumes vision; I have none. The screenshot/ripple proofs are the owner's 📱 step (as the board already reroutes them). This review covers everything checkable without eyes.
- Severity scale per §5b: BLOCKER / MAJOR / MINOR.

## Finding 1 (MINOR): "must use ONLY JbColors — never a hex literal" contradicts two landed files
Proof: `JbColors.kt:11` states the rule absolutely, but `JoyBrushActivity.kt` (committed) draws every pill/chrome from `OVERLAY_FILL`/`OVERLAY_RING`/`Color.WHITE` (`:43-45` + ~10 `setTextColor(Color.WHITE)` uses), and `PenDiagnosticsView.kt` from `PANEL_COLOR`/`TEXT_COLOR`. The 0.05 spec *mandated* the activity's values (predating the rule), and the 0.06 spec already asks the Lead the exempt-or-move question for the diag panel — but the activity's dozen literals have no open question anywhere. Either the rule needs scoping ("new chrome after JB-0.09; existing pills retrofit then") or the literals need a retrofit task. As written, the codebase violates its own stated rule on day one, which trains readers to ignore it. Needs a Lead sentence, not code.

## Verified (proof)
- Completeness: 30 XML tokens, all 30 loaded in `JbColors.load` (counted call-by-call: 2 room + 10 board + 4 state + 6 surface + 5 solid ink + 3 drawer); the script's EXPECTED set agrees (2 + 4 + 24).
- Drift-proofing by construction: canvas/character pairs are `@color/` references (nothing to drift — the script enforces reference-exactness, `check_joybrush_tokens.py:218-234`); mirrors carry `mirror:` comments the script cross-checks against its own table AND the source values, normalised across `#RGB/#ARGB/#RRGGBB` (`:88-100, :176-216`); duplicates, missing/extra tokens, non-colours, and room-tokens-with-mirror-comments all fail closed (`:164-172, :236-248`); both files' well-formedness is checked first with the `--`-in-comment diagnosis (`:149-158`).
- `jb_sunk`/`Studio.SUNK` (spec Q3): handled as the one Java-sourced mirror with a self-diagnosing lookup (`:124-140` — if the const moves, the error names the consequence).
- `Palette`-as-class (spec Q4a): a screen cannot read colours before loading (no transparent-black default to silently draw); `cached` race documented benign (identical rebuild); no `Context` retained (no leak — only Ints cached).
- `boardGradient(kind)` takes core's frozen `BoardKind` with an exhaustive `when` and no `else` (`JbColors.kt:64-70`): a sixth board kind is a compile error here, not a silent fallback — the R3-adjacent property, satisfied without engaging R3 (no enum changed, as the spec notes).
- Decisions 2/5 honoured in-file: placeholder indigo→blue values as specified; pink-red→amber explicitly rejected with the state-collision rationale (`jb_tokens.xml:31-40`); state/surface/ink mirrors as listed.
- `src/main/kotlin` placement (spec Q2): resolved — the file is at `src/main/kotlin/.../JbColors.kt`, matching the module's other source.
- Ripple mechanics (without eyes): room pair exempt from the check so it edits freely; canvas board references the room pair so it *cannot* stop following. The end-to-end red screenshot is owner's, as rerouted.

## Explicitly not filed (already owned)
- Spec Q1 (nothing on screen wears the room yet — confirmed by grep: no `JbColors` consumer outside its own file): moved to 📱/JB-0.09 per the board. Q3/Q4 above are resolved, not open.

## Recommendation
No send-back. One MINOR (scope the no-hex rule to match reality). No BLOCKER or MAJOR open; visual proof stays with the owner.
