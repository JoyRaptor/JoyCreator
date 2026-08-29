# OVERNIGHT QUEUE — 2026-08-29 → morning

**JoyRaptor and Fabián are asleep.** This is the work list. Take the top unclaimed item that
fits your lane, do it properly, and leave the repo green and the board honest.

We will read your reports in the morning **against the repo**, not instead of it. Two agents
have reported `BUILD SUCCESSFUL` against a red `build.log` in the last 24 hours, and one
invented PSNR figures for an unplugged phone. Assume every claim gets checked.

---

## 0. The five rules that broke something in the last 24 hours

1. **Never run gradle.** Not `assemble`, not `--rerun-tasks`, not "just to check". The
   watcher is the only builder. On 2026-08-29 an agent ran `--rerun-tasks`, corrupted the
   resource merge, and left JoyRaptor with no installable build for twenty minutes. Save the
   file, then read `build.log` (UTF-16 — `iconv -f UTF-16LE` or `tr -d '\000'`).
2. **Never a bare `git commit`.** Every lane stages continuously, so the index always holds
   someone else's half-finished work. Always `git commit -m "..." -- <your files>`.
3. **`git add` the moment you write a file**, before you verify it. This repo has silently
   destroyed unstaged work three times.
4. **Check `build.log`'s DATE as well as its time.** A twelve-hour misread happened on
   2026-08-29 (`12:54` was actually `00:54`).
5. **Read `tasks/DEVICE_CONTROL_RUNBOOK.md` before any UI navigation.** `uiautomator dump`
   returns a null root on these phones. The loop is screenshot → read pixels → tap by
   coordinate → screenshot. An agent that skipped this reported 41/41 BLOCKED because it
   could not get past the splash screen.

6. **You CAN drive the phone. Use `bash tools/phone.sh`.** Two agents skipped device work
   in the last 24 hours for avoidable reasons: `adb` is not on PATH here (so `adb devices`
   says "command not found" and reads as "no device"), and `uiautomator dump` returns a
   null root (so there is no element tree to wait for). Both are solved:

   ```
   bash tools/phone.sh devices          # is a phone attached?
   bash tools/phone.sh size             # tap against the OVERRIDE size, not the physical one
   bash tools/phone.sh install
   bash tools/phone.sh launch           # then wait ~8s
   bash tools/phone.sh shot out.png     # screenshot -> READ THE PIXELS -> compute a tap
   bash tools/phone.sh tap 628 2110     # Faditor tab, Note 9 (1080x2220)
   bash tools/phone.sh audio            # AudioTracks + allocation count
   bash tools/phone.sh log AudioLayerSync
   bash tools/phone.sh build            # last BUILD line WITH its date
   ```

   The loop is always: **screenshot → read the pixels → tap by coordinate → screenshot to
   confirm.** `tasks/DEVICE_CONTROL_RUNBOOK.md` has the full detail; the tap path into a
   project is in `SPEC_20260829_DEVICE_VERIFY_ALL` §2b. **"No device" is now only a valid
   answer if `bash tools/phone.sh devices` prints an empty list.**

**A truthful "I could not do this, here is how far I got" is worth more than a confident
report that turns out to be wrong.** The second kind costs hours to unpick.

---

## 1. The queue, in priority order

Claim on `LANES.md` before starting. If your top pick's files are held, take the next one.

### P0 — `SPEC_20260829_DEVICE_VERIFY_ALL` (rerun, §2b navigation added)
**Nothing else on this list matters as much.** Sixteen features are committed and one has
been seen working. The first run returned 41/41 BLOCKED for lack of navigation
instructions; those are now in §2b, with the exact tap path that worked by hand.
Writes no production code. **25 PASS / 10 FAIL / 6 BLOCKED is a success. 41 PASS is not
believable and will be checked line by line.**

### P1 — `SPEC_20260829_IMAGE_ANIM_PRESETS` phase 3 — **NOW UNBLOCKED**
`CAPTION_LAYERS` went IDLE at 03:00, so `FaditorEditorActivity` is free. Phases 1+2 landed
in `0be24e6f` and **the user cannot reach any of it** — there are no drawer buttons. This is
finished work sitting behind a missing door: preset chips, Fit/Fill buttons, the replace
warning, preview-drag wiring. Highest value-per-hour on the board.

### P2 — `SPEC_20260829_MEDIA_IMPORT`
Adding a video opens the *system* file explorer, and `AssetBrowserPanel` — the app's own
browser — is constructed by nothing. Make it the picker: thumbnails, durations,
multi-select, "Browse files…" escape hatch. **§2.2 (video thumbnails) is fully disjoint —
land that first** so the work is banked whatever happens with the contended file.
Do not resurrect the drag-in drawer; build a picker.

### P3 — `SPEC_20260829_WORD_SYNC`
The bulk transcript-fixing mode. **Three built, tested, idle components already exist for
this** (`PcmSidecar`, `ScrubEngine`, `OnsetDetector` with 16/16 passing tests) — consume
them, do not rebuild them. The ripple/stretch drag in §3.4 is the highest-value part.

### P4 — `SPEC_20260829_QUICK_WINS` §1
Toolbox reorder: `sticker` (Image overlay) is already top-level, just four slots out of
place. Demote image-as-clip to a long-press with a visible affordance. Small and long-owed.

---

## 2. Acceptance debts — claim one if you finish early

These are landed features whose own acceptance never ran. Each is an hour with a phone.

| Owed | From |
|---|---|
| **Preview vs export, on device** | `PREVIEW_PERF` §5.7 — see §3 below, this is NOT closed |
| Export GL frames: before/after timing + PSNR. Baseline to beat **1m38s** (46s project, 720p/Low). `am force-stop com.fadcam.beta:export` first — export runs in its own process and survives restarts | `SPEC_20260828_EXPORT_GL_FRAMES` §5 |
| Caption Fit: 30-word cue, UNIFORM vs PER_CUE | `SPEC_20260828_CAPTION_FIT` |
| Horizontal reflow on a PORTRAIT canvas — the one reflow case never seen | `HANDOFF_20260828` |
| The ~1:03 playback ceiling — reproduce or declare gone | `HANDOFF_20260828` |
| **Scrub audio on device** — VERIFIED WORKING by JoyRaptor 2026-08-29 03:00, and confirmed at the OS level (`state:started`, `CONTENT_TYPE_MUSIC`) | `9a18dc5e` — done |
| **AudioTrack churn while scrubbing a PAUSED playhead.** `bash tools/phone.sh audio` during a scrub session shows ~20 ExoPlayer AudioTracks (`CONTENT_TYPE_UNKNOWN`, `flags=0xA00`) allocated in ~6s. Cause is almost certainly `AudioLayerSync.onPlayheadScrubbed` → debounced `parkAt` → `prepare()` per scrub, each making a fresh track. NOT the scrub engine (that made exactly one). Not fatal, but it is allocation churn on every drag and the same class of bug as `7173fada`. Diagnose before fixing | found 2026-08-29 03:00 |

---

## 3. ⚠️ One report that is not what it claims

`PREVIEW_PERF` reported §5.7 (preview vs export) **closed**, citing
`PSNR average: inf dB`. That number came from comparing two files the report itself
describes as *"`ffmpeg` identical"* — a file against itself. **Infinite PSNR there proves
the harness runs; it says nothing about whether preview matches export.** The report is
otherwise good and honest, and it names the real device fixture as owed.

So: **§5.7 is still open.** Whoever picks it up runs the actual fixture — a project with an
animated overlay under a blended image, preview screencap at 5s versus the exported frame
at 5s, PSNR > 40 dB. That spec says in terms that if they disagree it must not land.

This is not a telling-off; it is the exact failure mode this project keeps hitting, and it
is worth naming precisely: **a green check that did not test the thing is worse than a red
one, because it stops anyone looking again.**

---

## 4. Not yet specced — do NOT start these tonight

Listed so nobody invents one at 4am. They need a design conversation with JoyRaptor first.

- **Project bundling / media consolidation** — copy a project's media *and* imported fonts
  into one folder so projects are portable and survive a reinstall. This is the fix for
  fonts dying on uninstall. Wanted since 2026-08-28.
- **Relink catalog repair** — JoyRaptor: *"we just have a broken media catalog relinker
  somewhere."*
- **Captions → GL** — cheaper than Canvas, not more expensive
  (`FINDING_20260829_GL_ANIMATED_GAP` §3). Was blocked on caption layers, which have now
  landed, but wants a design pass first.
- **Visualizer → GL** — the finding explicitly marks this **unmeasured inference**. Profile
  before anyone acts on it.
- **Per-word rich text in a caption cue** — needed for `WORD_SYNC`'s B/U/I buttons. If Word
  Sync finds it does not exist, grey those three out and report; do not build it inline.
- **Slide object** — parked (`0332ca43`, reverted by `8b3c1d22`). **Probably obsolete now**
  that caption layers exist. Ask JoyRaptor before resuming.

---

## 5. What "done" looks like in the morning

For each item you touch, in your lane's `LANES.md` entry:

- the commit hash, and the pathspec you used
- `build.log`'s last line **with its date and mtime**
- `adb devices` output, or a plain statement that no device was attached
- what you verified, and **what you did not**

The last line is the one that earns trust. Every serious defect found in the last 24 hours
was found by *measuring* — `dumpsys audio` showing `state:idle` while a log claimed
success, `drift desync` in logcat, counting `new player piid:` lines, listing gradle
daemons and finding two. None was found by reading code and reasoning about it.

Leave the tree green. If you cannot, say so at the top of your report in one sentence, so
whoever reads it first knows before they read anything else.
