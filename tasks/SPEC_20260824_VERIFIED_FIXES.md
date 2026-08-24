# SPEC — Verified fixes from the 2026-08-22 audit round

**Written:** 2026-08-24 · **Source:** four AI audits, claims verified against the repo by hand.

Every item below was **re-verified as still open on 2026-08-24 11:50**. Findings from those
audits that did NOT survive verification are listed in §5 and must not be acted on.

Scope rule: this spec is deliberately small and mechanical. If an item turns out to be bigger
than described, **stop and report** rather than expanding. An unfinished line item is fine.
A silent half-cut is not — see §4.

---

## 1. Pass one — do all five, in this order

### S1 · Duration-changing undo actions don't ripple riders

**Files:** `app/src/main/java/com/fadcam/ui/faditor/undo/EditActions.java`

- `SpeedAction` (:152) — `execute()`/`undo()` are bare `setSpeedMultiplier` calls (:162-163)
- `LoopAction` (:896) — `execute()` :913, `undo()` :918
- `AudioTrimAction` (:471) — `execute()` :485, `undo()` :489

**Problem:** all three change a clip's effective duration. `TrimAction` brackets its replay with
`beginStructuralEdit`/`endStructuralEdit` so downstream clips and anchored riders shift with it;
these three do not. The live edit shifts riders (the call site brackets it), but the undo/redo
replay doesn't — so the project drifts a little further out of alignment on every undo cycle.

**End state:** all three bracket their `execute()` and `undo()` exactly the way `TrimAction`
does. Read `TrimAction` first and copy its pattern rather than inventing one.

**Explicitly NOT in scope:** `VolumeAction` (:105) and `RotateAction` (:170) have the same bare
shape but do not change duration. Leave them alone.

**Verify:** `grep -n "class SpeedAction" -A 14 EditActions.java` shows the bracket. Same for the
other two.

---

### S2 · `ReplaceClipsAction` aliases its caller's list

**File:** `EditActions.java:743-752`

**Problem:** the constructor stores `this.replacements = replacements` with no defensive copy.
If the caller reuses or mutates that list after `recordAction` (the silence-removal pass does
exactly this), redo replays whatever the list became, not what was recorded.

**End state:** constructor takes a defensive copy — `new ArrayList<>(replacements)`.

**Residue — do this too, it's the actual point:** four other `EditAction` classes hold list
fields (`:230`, `:257`, `:557`, `:680`). Check each one. `OpacityKeyframesAction` (:230) is
believed to copy correctly already — confirm rather than assume. Fix any that don't, and
**report the status of all five**, including the ones that needed no change.

---

### S3 · Waveform cache reads are unbuffered

**File:** `app/src/main/java/com/fadcam/ui/faditor/waveform/WaveformExtractor.java:384`

**Problem:** `new DataInputStream(new FileInputStream(f))` — no buffer. Every `readFloat()` is
its own syscall. A 30-minute cache hit is ~1.4M syscalls for data that should stream.

**End state:** wrap in `BufferedInputStream`. One line.

**Residue:** the sibling band-extractor read path has the same shape. Check it; fix it if so.

---

### S4 · Three executors are never shut down

**File:** `app/src/main/java/com/fadcam/ui/faditor/FaditorEditorActivity.java`

`reverseBakeExecutor`, `slideRenderExecutor`, `transitionDecodeExecutor` — zero `shutdown` calls
anywhere in the file. Non-daemon threads keep running stale jobs after the editor exits.

**End state:** shut down in `onDestroy` (:1732), following the established pattern at :1759-1760
(`audioExecutor.shutdownNow()` / `assetImportExecutor.shutdownNow()`).

**Read before editing:** `onDestroy` contains a deliberate exception — the transcription engine
is NOT killed, with a comment explaining what broke last time. Do not "tidy" that. If any of the
three executors turns out to have a similar reason to outlive the activity, say so and skip it.

**Residue:** sweep the file for any other executor declared but never shut down. Report findings;
don't fix beyond the three named without checking in.

---

### S5 · `getSelectedClip()` crashes on an empty timeline

**File:** `FaditorEditorActivity.java:822-832`

**Problem:** when nothing is selected it falls back to `getClipCount()`-unchecked `getClip(0)`,
and `Timeline.getClip()` (`model/Timeline.java:289`) is a bare `clips.get(index)`. On an empty
timeline this throws `IndexOutOfBoundsException` rather than returning a fallback.

There is a second, larger problem here — returning clip 0 when nothing is selected means
SPEED/FILTER/CROP/VOLUME can mutate the wrong object. **That is not this task.** Fix only the
crash.

**End state:** guard the empty case. The existing comment says callers must handle `-1`
explicitly, so returning `null` on an empty timeline is consistent with the stated contract —
but that means **every caller must be checked**. If the caller count makes this bigger than a
contained fix, stop and report the caller list instead of pushing through.

---

## 2. Pass two — bigger, but unblocked. Do these AFTER pass one lands.

Both were waiting on a decision from JoyRaptor. Both decisions are now made and recorded here.

### S6 · Transcript word-strikes have no undo

`onStrikesChanged` → `syncRemovedSpansFromTranscript()` (`FaditorEditorActivity.java` ~:26792)
contains zero `recordAction` calls. "Clean fillers" is a one-tap mass content cut that Undo
cannot reverse — the only way back is un-striking each word by hand. Every other structural edit
in the editor records undo.

**Granularity — RULED, do not re-litigate.** One button press = one undo step, however many
words it touched. "Clean fillers" striking fifty words is ONE undo. A single hand-struck word is
also one, because that is also one press — so no special-casing is needed between the two cases.

**End state:** a new `EditAction` over the strike set, recorded once per user action. Follow the
existing `EditAction` conventions in `EditActions.java`, and **take a defensive copy of the
strike collection** in the constructor — see S2; this is the exact bug class that spec item is
about, so do not reproduce it in new code.

---

### S7 · Export failures show raw engine text, vanish, and offer no way back

**Where:** `exportUiOnError` (`FaditorEditorActivity.java:9174`) interpolates the raw string
into a toast via `R.string.faditor_export_error` ("Export failed: %1$s"). The string is
`error.getMessage()` off a Media3 Transformer exception, plumbed through
`ExportService.java:288-290` — i.e. text written by library authors for library authors.

Three separate defects: it is a toast (cannot hold a button, self-dismisses), there is no way to
try again, and nothing tells the user their project survived — which is the actual question in
their head at that moment.

**End state — a dialog, not a toast**, containing:

1. **One plain sentence** naming the cause in user terms, always ending with the reassurance
   that the project is safe.
2. **A Retry button — shown ONLY when the cause is plausibly transient** (see ruling below).
3. **A Close button**, always.
4. **A "Details" disclosure** revealing the raw engine text. The raw text is demoted, never
   deleted — it is what makes a bug report useful.

**Retry — RULED.** JoyRaptor's call: Retry is only worth offering when trying again could actually
work, otherwise it is just frustration. So Retry visibility is **per-cause**, decided by the
classifier, not a global on/off. We do not need to know the overall success ratio to ship this —
and §3 explains how we will learn that ratio from real data.

**Cause table.** Classify the throwable into a small enum. Starting set:

| Cause | Message | Retry? |
|---|---|---|
| Storage full | "Not enough space to save the export. Free up some room and try again." | **Yes** — the user can act, then retry |
| Source file missing/moved | "One of the clips can't be found. It may have been moved or deleted." | No |
| Codec / format failure | "This video couldn't be encoded. Try a different quality setting." | No |
| Interrupted / file locked / transient I/O | "The export was interrupted. Your project is safe." | **Yes** |
| Unrecognized (fallback) | "Export failed. Your project is safe — nothing was lost." | No |

Add causes if the Media3 exception surface clearly warrants it; report any you add. **Do not**
guess a cause from loose substring matching on a message that could mean several things — an
unrecognized error correctly falling through to the generic case is better than a confident
wrong diagnosis.

**Also required — the structured record.** See §3. This is not optional polish; it is the part
that makes the next system possible.

**Out of scope, deliberately:** the same raw-error-into-a-toast pattern exists at
`FaditorEditorActivity.java:28247` ("Reorder error:") and `:32124` ("Align failed:"). Leave both
alone. This item establishes the pattern; a later sweep applies it.

---

## 3. Direction — this is groundwork for user bug reporting

JoyRaptor's stated goal: users hit a problem, report it, and the report reaches an AI agent that can
analyze, reproduce, and help maintain the app. He cannot read code, so this pipeline is how the
app eventually maintains itself. **Nothing in this spec should foreclose it, and S7 should lay
the first stone.**

**What already exists (verified 2026-08-24 — build on it, do not reinvent):**

- `com.fadcam.Log` writes a **persistent HTML log file** on disk, with batched flushing
  (`FLUSH_INTERVAL_MS` 250ms, `FLUSH_MAX_BATCH` 400). Diagnostics already survive app restart.
- `com.fadcam.FLog` writes to both logcat and that file, and **already scrubs** IP addresses,
  auth tokens, and file paths before writing (`FLog.java:180-195`), with a hook for a custom
  redactor.

So the privacy-safe, persistent substrate is in place. What is missing is (a) structured error
records rather than freeform prose, and (b) any way for a user to send one — there is currently
**no** bug-report, feedback, or share-log path anywhere in the app.

**What S7 must therefore do:** when an export fails, alongside showing the dialog, write a
structured record through `FLog` containing at minimum:

- the classified cause enum (from the table above)
- the raw exception message and class
- app version and Android API level
- coarse project shape — clip count, total duration, whether audio/PiP/text layers are present

Keep it machine-readable (a single tagged line, consistently formatted). Do not add device
identifiers, file paths, or media contents. **Do not build any sending or upload mechanism in
this task** — that is a later spec, and it needs JoyRaptor's ruling on what leaves the device.

The point of the structured record is twofold: it is the payload a future report would carry,
and once these accumulate we can finally answer the question JoyRaptor could not answer today —
what fraction of export failures are the transient kind worth retrying.

---

## 4. Working rules

**Build:** per LANES.md rule 6 — **never run gradle/gradlew.** Save your edits and read
`build.log` for a fresh `BUILD SUCCESSFUL`. If `build.log` won't update, STOP and say so.
Before trusting it, check its mtime is newer than your last edit — a stale log claiming success
is the exact trap that cost this project a day on 2026-08-22.

**Report accurately.** State actual line counts from `git diff --numstat`, not estimates. If you
skip an item, say which and why. Do not report an item complete without pasting the grep or diff
output that proves the end state.

**No half-cuts.** If you remove or change something that other code was serving, either finish
the removal or leave it fully intact. The previous round left drag logic alive for handles
nothing drew anymore. If you can't resolve the residue, leave the item untouched and report it.

---

## 5. From the audits — verified FALSE or hazardous, do not act on

- **"Delete the software layer in `WaveformOverlayView` (:91)."** The comment above that line
  gives two reasons for it — blur compositing *and* forcing `drawBitmap` to copy immediately so
  the reused render bitmap is safe across multiple overlays. The audit addressed only the first.
  Removing it risks visual corruption on stacked overlays. **Leave it.**
- **"No `layout-land/`."** It exists. The true, narrower finding is that there is no *faditor*
  landscape variant in it — a design decision, not a bug.
- **Orphaned audio trim-drag path.** Real on 2026-08-22, **already resolved** — the file was
  rewritten and committed 2026-08-24. Nothing to do.
- **"Build watcher is dead / A1 blocked."** Was false when written. The change had already
  compiled.

Large and real, but out of scope for a mechanical pass — do not start these without a plan:
the TalkBack accessibility gap (zero accessibility nodes across the whole faditor package), the
in-session vs post-restart undo depth divergence (`UndoManager.java:646`), and the green
accent collision with the canonical AUDIO color (`ObjectPalette.java`).
