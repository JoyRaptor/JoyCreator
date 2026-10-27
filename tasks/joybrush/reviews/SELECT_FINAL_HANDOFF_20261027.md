# Select specialist final handoff — October 27

Assigned implementation, verification and disposable cleanup are complete within
the bounded Select/UI lane. Engine-dependent notice activation remains explicitly
open with the coordinator; this is not whole-product or owner creative acceptance.

## Verified checkpoints

- Exact Select/A10 APK source f7f75869a1df90957196717a4530ac5ff640c5e7:
  Android190/0/0/0; APK SHA256
  5d99f3f5fb947129c9f85eb90bcc1f2e413013f28a01ffceb667a36082635132.
- A10 run eaaea09e705240e0b3f6891d3935bd05:6/6, five ACK images personally
  reviewed and independently reviewed by UX. Host b7aa05fd6, offline60 tests.
- Same APK global20 run b52e984014e24f69b4762f65abb81d5c:20/20, eleven ACK
  images personally reviewed. Host340fb7cac is documentation-only beyond b7aa.
  All16 hashes match audits; exact installed SHA, twelve saved roots and owned
  tools/Ink preference presence/type/value unchanged. Own phone claims released.
- D23 pure helper source0c9ce44c4e3d701ce7ad3bc99d0ee94041efa4c2:
  compiled and passed11/0/0/0 in2m37s, exit0, independently counted. Log SHA256
  a79f82d402754728480a1b1daebb711fee372bfb648cbfde71019ceb6d13343e;
  XML SHA2568f435f716a91b648cc9ba9ec94db50f2b0e6ed829724df430953aa2692d5f853.
  Receipt `C:/Temp/jb-select-acceptance-20261026/d23-0c9ce44c4/receipt.json`.

## Code-only D23 integration

Base ecc2ea8e8c2756001a087be17e7f47aecf679ac9; code commits7017d2d92 and0c9ce44c4.
Only two NEW code files are needed from tested0c9ce44c4:

- `joybrush-android/src/main/kotlin/cc/joycreator/joybrush/android/select/SelectFirstUseNotices.kt`
- `joybrush-android/src/test/kotlin/cc/joycreator/joybrush/android/select/SelectFirstUseNoticesTest.kt`

No Activity/View/engine/SelectToast or persistence schema edits. Restore only these
new paths or extract their scoped diff; reconcile roadmap/review prose separately
against the current coordinator board, never replace the whole board from this base.

The future producer must validate operation identity and actual successful facts.
The renderer must confirm actual surface presentation via onShown and completion
via onFinished. All callbacks belong on the UI thread. guardedUndo must validate
generation/layer/exact history head and perform the undo atomically, including at
execution after an async queue hop. No separate check then plain undo. Persistence
failure after a real show suppresses repeat spam only in the presenter lifetime;
cross-process durability remains unproved on that failure. K26 stays separate.

## Cleanup and remaining ownership

Removed eleven disposable Python helpers and five owned Gradle init/config files
using exact literal paths, after matching preserved-copy hashes. Manifests:
`C:/Temp/jb-select-acceptance-20261026/support-scripts-manifest.json` and
`support-configs-manifest.json`. Preserved source branches/worktrees, original red
runs, raw XML/logs/APKs/PNGs and hash-verified support copies in the designated
evidence directory. No recursive deletion, owner data edit or foreign lock cleanup.

No specialist job, queue or phone claim remains. A foreign coordinator Gradle lock
was present at final inspection and was left untouched. A10 branch
`codex/select-a10-wired-20261026` is pushed at a20eaa3a0; D23 branch
`codex/select-d23-presenter-20261026` is pushed, including this docs checkpoint.
Exact final remote SHA is reported in the coordinator handback.

Next action belongs to Lead/Claude: adopt the two tested helper files if desired,
provide truthful publication/renderer/lifecycle adapters, then qualify first-use
and guarded-Undo behavior on the phone. Slice/Smudge atomic split, C3 and Phase B
remain their existing explicit engine gates. No follow-on feature was opened.
