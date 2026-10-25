# Select phone acceptance

Run `select_bench.sh <fresh-out-dir> [lane-name]` in Git Bash. Python 3.11+ and adb are required.
The script reads the private sandbox serial from the existing ignored `tools/devices.local.sh`.
It only accepts an awake, unlocked SM-N960U Note 9, API 29, already on Home. It never installs,
unlocks, changes rotation, clears logcat, or deletes app data. Do not wrap it in another phone claim.

Set `SELECT_BENCH_BUILD_RECEIPT` to an absolute JSON file from the successful frozen build:

```json
{
  "source40hex": "<40 lowercase hex digits>",
  "buildSuccessful": true,
  "apkPath": "C:/Temp/<preserved-candidate>.apk",
  "apkSha256": "<64 lowercase hex digits>"
}
```

The builder must establish the source-to-APK mapping from the completed successful build log.
This runner hashes that preserved APK and requires the installed APK to match before launch and
after completion. A receipt is not independent proof that its author built the stated source.
The candidate must include `jb_select_run_id` support; older APKs deliberately fail acceptance.

The Windows runner claims `jb-phone.lock` exclusively and releases only its own unchanged token and
captured file identity. Cleanup pins the file against replacement/writes, verifies it through that
open handle, and requests deletion of that object on close. If access or identity is uncertain it
leaves the claim for inspection. Other platforms deliberately do not auto-release through a racy
pathname unlink. A failed
preflight releases the claim. After an uncertain launch, timeout, or lost adb connection, it retains
the claim because a bench may still be driving the phone. The owning lane must inspect and resolve
that running state before explicitly releasing its own claim; never remove another lane's claim.

Every row, screenshot request, and terminal event carries a fresh run ID and consecutive event
number. Old logcat runs are ignored. Missing/reordered events, missing/failed steps, incomplete PNGs,
timeouts, and APK changes all return nonzero. The manifest is the exact expected step/PNG set.
New matching events after terminal evidence are rejected before a report is written or a screenshot
is captured. Identical already-seen logcat events remain harmless polling repeats. The existing offline
validator checks completeness after terminal evidence; its 29 tests are separate from the runner's
14 tests (mocked device plus actual Windows lock-file operations). Neither suite is phone evidence.

The Android bench taps real Select UI actions for Copy/Paste, Group, and Lock, checks published
records and undo counts, and captures each resulting state. It preserves the owner's process-session
clipboard on ordinary completion and Activity pause/destroy abort. Abort cancels pending synthetic
gestures with CANCEL, restores once, and emits no successful completion event; late callbacks cannot
resume the bench. These cases supplement the existing picking/gesture/action checks;
they do not yet cover all A1–A10 acceptance. A10 sheets/badges/toasts, HSB, recolour, layer actions,
and nudge ghost acceptance remain explicitly open until added and run on the matching candidate.

`before.png.evidence` is the raw preflight PNG for manual readiness review, outside the manifested
step PNG set. Review all step screenshots visually after a successful run. Synthetic window-dispatched
pen events do not establish physical S Pen tilt, palm handling, latency, or owner aesthetic acceptance.

```text
python -B -m unittest -q test_run_select_bench.py test_validate_select_bench.py
```
