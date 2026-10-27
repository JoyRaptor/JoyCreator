# Adversarial review — JB-1.21 Phone Brush Lab hot reload (watched folder → phone in ~1 s)

- Reviewer: muse-spark (cross-reviewer, no device — builder was an unrelated subagent — different family; orchestrator applied activity-region edits).
- Task status: 🟧 Built. Commit reviewed: `7f0ba0bc` (reload class + tests + push script + activity wiring).
- Spec reviewed: `tasks/joybrush/specs/JB-1.21_phone_brush_lab.md` (decisions 1–3, verification, Do-not + Questions 1–11).
- §5b checks: diff touches only NEW `androidkit/lab/BrushHotReload.kt`, its test, activity start/stop/wiring regions, NEW `tools/brushlab_push.sh` — inside the owner area; no engine/view changes per "Do not" (the pill divergence in Q10 is correctly NOT touched — JB-1.05b's region). Suite run by me in a clean HEAD worktree: `BrushHotReloadTest` 12/12, full `:androidkit:test` 104/104, 0 failures. Device check (fat-stroke-in-1-s, broken-JSON toast) is the owner's — no adb here.
- Severity scale per §5b: BLOCKER / MAJOR / MINOR.

## Finding 1 (MAJOR): the brush pill lies after a hot reload, and its next tap destroys the lab brush — deferred to the Lead by design, recorded here as the user-visible cost
Proof: lab sets `canvas.preset` (`JoyBrushActivity.kt:710`) but `brushLabel()` reads `brushes[brushIndex]` built once from `BrushLibrary.builtIn()` (`:114,335-336`); `cycleBrush()` overwrites `canvas.preset = brushes[brushIndex]` (`:343-345`). After reloading `ink` as "Big Ink": person draws Big Ink, pill says "Brush: Ink", next pill tap throws the lab brush away. The builder correctly stopped at the JB-1.05b boundary (spec Q10) — so this is filed as the Lead's pending choice (lab override the pill shows and steps over, vs reloaded brush the pill doesn't describe), not as builder error. MAJOR because the current combination is silently destructive on the next tap.

## Finding 2 (MINOR): phone misses same-second saves; the push script fingerprints size precisely to avoid what the phone allows
Proof: phone gates on `lastModified` alone (`BrushHotReload.kt:118-119`); `%Y` is whole seconds, so two saves in one second share a stamp and the second is swallowed — while `brushlab_push.sh:77` fingerprints `'%Y %s'` with a comment explaining exactly why. The pair was designed together and disagrees on the one edge that matters (PC saves twice quickly → phone shows the first forever). Already admitted on the board row ("1 s granularity… can be missed — a spec-contract change"), so MINOR + the fix is one line (add `%s`-equivalent size to the gate). Tests hide it (saves 1000 ms apart by construction).

## Finding 3 (MINOR): `push` on a deleted `brush.json` pushes stale bytes
Proof: `brushlab_push.sh:77` fingerprints `gone` for a missing file, the loop still fires `push_now "changed"` (`:112-114`), which `wc -c`s a missing file and pushes the stale folder. Abort with "brush.json deleted" instead — three lines.

## Finding 4 (MINOR): `seen` never evicts — delete + same-second recreate is suppressed, unbounded growth over long sessions
Proof: `seen` keyed by `file.path` (`BrushHotReload.kt:62,119,123`), missing lab returns early (`:108`) without cleanup. Narrow (requires same-second recreate), structural. Evict on scan-miss.

## Verified (proof)
- 500 ms main-Looper poll (`POLL_MS`), idempotent start, `stop()` in `onPause` first line with self-guarding tick — no leak, no destroy-handler needed (pause precedes destroy), double-start guarded.
- mtime-gated `decode`-then-`validate` mirroring `BrushLibrary`'s order (the `decodeChecked` alternative would drop the version-word check — correctly rejected); first-sight-is-change (so push-then-edit verification works), name-order walk, stamp-before-bytes (the mid-scan race correctly reasoned), unreadable-marked-seen (reports once — intended, not a leak), `brush.json`-not-dir-mtime on both ends.
- Debuggable-only gate before any construction; `LAB_DIR` single-sourced on the companion; `File`-not-`Context` constructor (the testability rationale holds — 12 JVM tests, no Android needed); error path touches nothing with `first()` safe by construction.
- Push script reuses `phone.sh` serial selection wholesale (Note-20 guard inherits correctly); per-push timestamped line; adb chatter suppressed.
- Refuted suspects (checked, absent): stamp-after-bytes race, invalid-keeps-polling, pause/destroy leak, release-build leak, wrong-thread toast (main-Looper tick + `runOnUiThread`), mid-stroke preset application (immediate-by-spec-Decision-2 — conforms; atomic-at-stroke-end would contradict it).

## Recommendation
Rule on Finding 1 (Lead's pill choice — the only MAJOR, and deliberately deferred); 2–4 are small robustness fixes. The 12 tests + 104/104 module run close Q1–Q2's process questions.
