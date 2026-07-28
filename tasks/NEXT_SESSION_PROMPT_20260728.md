# NEXT SESSION — paste everything below the rule into a fresh conversation

---

FadCam / Joy Creator — `C:\+Projects\Screenrecorder\FadCam`, branch `joy-creator`.
Note the working directory is the PARENT (`C:\+Projects\Screenrecorder`), so task docs are at
`FadCam/tasks/…`, not `tasks/…`.

**Read `FadCam/tasks/LEDGER.md` FIRST.** It is the single record of what is fixed (with the
evidence that proved it), what is open, and what has been promised. It exists because a whole
feature — masking/chroma-key — was built, shipped as an engine, and then LOST for weeks because
no checklist item was ever left unticked. Keep it current: when something lands, move it to §1
with its proof; never delete an entry to shorten the list.

## YOUR FIRST JOB — the playhead↔clip mapping bug (LEDGER §2a)

This is the last thing making the editor feel broken, and it is fully diagnosed. Do not
re-diagnose it; verify the claims below against the code and then fix it.

**The defect:** a timeline drag can compute a clip-relative position BEYOND the clip it is
addressing. Captured twice on the Note 9, same session:
```
Seek to 5363ms (rel) / 4457ms (abs)      ← clip is trim 1406→4457, i.e. 3051ms long
Seek to 8061ms (rel) / 4457ms (abs)      ← same clip, later
```
The absolute clamps to the clip's out point, the player runs out, reaches `STATE_ENDED`, and —
because the app still has playWhenReady true — parks forever in "wants to play, will never
play". The frozen signature in `PHDIAG` is:
```
playing=false pwr=true playerPos=15661 sel=1 segAtHead=1 head=8311->8311 moved=false
```
A related sighting is `sel=3 segAtHead=2` — `selectedClipIndex` and the segment under the
playhead had DIVERGED. That divergence is very likely the same root cause: seeks are computed
against the SELECTED clip while the playhead is over a different one.

**User-visible symptoms this explains (all reported):** playback stops mid-timeline and the
transport goes unresponsive; it only recovers by scrubbing back to zero; video freezes while
audio continues, or the reverse.

**Two layers wanted:**
1. Root: a drag must never produce a position outside the clip it addresses. Look at how the
   playhead position is converted to a clip + clip-relative offset, and at what keeps
   `selectedClipIndex` in step with the segment under the playhead during a drag.
2. Net: `STATE_ENDED` with clips still ahead should ADVANCE to the next clip, not park. Nothing
   currently converts ENDED into an advance, which is why it wedges.

**How to reproduce (the user's own procedure):** play through once, scrub to the middle, press
play, then alternate dragging the playhead and pressing play/pause. It now takes real effort to
provoke — several fixes have already made it survivable rather than fatal — so budget time, and
watch `PHDIAG` rather than the screen.

## HOW TO WORK HERE — rules that were each paid for in a bug

- **Prove it, never assert it.** Every fix in LEDGER §1 has a measured before/after. A green
  build proves nothing.
- **Pair every check with a positive control.** A test that passes because the instrument is
  blind is worse than no test. This session had two instruments silently report success while
  measuring nothing.
- **Two data points minimum** before believing a pattern. One measurement plus a plausible story
  produced three confidently wrong diagnoses this session, each killed by a second measurement.
- **Check the signal is not self-manufactured.** A "reproduction" turned out to be a stale
  `tail -1` after the log had stopped.
- **Fix at the funnel, not the call site.** Patching two call sites for a leaked flag missed a
  third path; the fix belonged in the one function they all pass through.
- **`adb logcat` without `-T` replays the whole ring buffer** and looks like a live regression.
  Never `logcat -c`.
- Navigate the UI with `uiautomator dump` and match `resource-id`/`text` + bounds. Do NOT reuse
  remembered tap coordinates — the transport moves with each project's preview aspect, and the
  project list reorders every time a project is opened (opening re-saves it to the top).
- `adb` is at `C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe`.
  Gradle needs `$env:TEMP='C:\Users\JoyRaptor\gtmp'; $env:TMP=$env:TEMP`.
  Git-Bash mangles device paths — quote the whole shell string for `/sdcard/...`.
- Commit messages: `git commit -F <file>`, and write the file WITHOUT a BOM
  (`[System.IO.File]::WriteAllText($p,$m,(New-Object System.Text.UTF8Encoding($false)))`).

## DEVICE RULES — NON-NEGOTIABLE

- The Note 9 `SANDBOX_SERIAL` is the sandbox and must be the ONLY phone attached.
- **If the Note 20 `REAL_SERIAL` appears, STOP all device work.** It holds the user's real
  45-minute project. Installing force-stops whatever is running.
- Installing the app kills the user's session — tell them, they may be mid-test.

## AFTER THE MAPPING BUG — the agreed order (LEDGER §3)

1. **Lane mute icon (§3b)** — remove it entirely when the lane has no audio (absent, not greyed
   out), a real speaker glyph when it does, crossed-out when muted, and move it flush against the
   caret so the lane is not wasted.
2. **Cut smoothness, HYBRID (§3d)** — approved to build, then **measure on a LONG file** and
   report honestly what fraction of real cuts benefit. The user's words: "verify its usefulness,
   or if it's just a waste." Trim precision is non-negotiable, so keyframe-snapping is off the
   table permanently.
3. **Two-stage AI reorder (§3e)** — a reorder operates on a COMPLETE tally and can never drop a
   clip; deletion is a separate explicit stage that reports what it removed.
4. **Masking / chroma-key authoring UI (§3a)** — the big one, and the reason the ledger exists.
   The engine is built and export-proven; nothing in the app can create a spec. Fix the
   preview/export divergence and add masks to text/sprites rather than shipping an
   "incompatible" toast — the user explicitly rejected papering over it. **Bring a proposed
   interaction design to the user BEFORE building**: this feature already failed once by being
   technically complete and practically invisible.
5. **Text animation (§3g)** — one shared `(spec, t) → per-glyph transform` function called by
   BOTH preview and export, or the export will not match what the user saw.

## STILL OPEN, LOW URGENCY

The second stranded drag-latch path (§2b) self-heals, so it is invisible, but it fired 5 times
in one testing session. Every capture shows EVERY gesture flag false, which eliminates the whole
original suspect list including pinch-zoom. Keep `PHDIAG` and the `lastUp:` snapshot in the build
until it is closed.
