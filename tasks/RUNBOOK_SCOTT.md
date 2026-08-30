# RUNBOOK — everything JoyRaptor needs to run this without me

**Written:** 2026-08-29 · For JoyRaptor, who is not an engineer and should not have to be.

This is the one file to open. It has the four commands you need, every prompt ready to
paste, the full docket, and the prompts to hand back to Claude when an agent makes a mess.

---

## 1. The four commands

From the project folder, in PowerShell:

```powershell
.\tools\phone.ps1 build
```
Prints `build.log`'s **date and time** and the last `BUILD SUCCESSFUL` or `BUILD FAILED`.
**If the time is old, the watcher has died** — restart it, or nothing an agent claims is
real.

```powershell
.\tools\phone.ps1 install
```
Puts the current build on the phone. Do this before testing anything.

```powershell
.\tools\phone.ps1 devices
```
`device` = fine. `offline` = **unplug, replug, unlock the screen, tap Allow.** Nothing on
the computer can fix offline.

```powershell
.\tools\phone.ps1 audio
```
While something is playing: an AudioTrack at `state:started` means sound is really coming
out. Everything `idle` means it is not.

**If a build says FAILED and an agent is mid-task, that is usually normal** — they save
half-finished code and the watcher builds it. It matters when nobody is working and it is
still red.

---

## 2. The rules agents must follow

These are all on `tasks/LANES.md`, which every prompt tells them to read first. Each one is
here because breaking it cost real work in the last 48 hours.

| Rule | What it cost when broken |
|---|---|
| **Never run gradle** | corrupted the build, no installable app for 20 minutes |
| **Never a bare `git commit`** — always `git commit -m "..." -- <files>` | swept 1,000 lines of two other lanes into one commit |
| **`git add` a file the moment you write it** | this repo has silently destroyed unstaged work three times |
| **Never resolve a merge conflict — stop and hand it to Claude** | one rewrote `strings.xml` as UTF-16, one dropped a whole commit. Both silent |
| **Never report work you have not compiled** | two lanes reported features landed against a build hours stale, both left the tree red |
| **Re-derive tap coordinates from a fresh screenshot** | a sweep reported a false audio regression from stale coordinates |
| 🛑 **Never `adb uninstall` the app** | **destroyed 23 of JoyRaptor's 24 projects on 2026-08-29.** Projects live in app-private storage; uninstall wipes it with no prompt and no recovery |

---

## 3. Paste-ready prompts

Every one is self-contained. Give each agent a **fresh session**.

### Common header (already inside each prompt below)

> Read `tasks/LANES.md` first and claim your lane. Branch `joy-creator`. Never run gradle —
> the watcher builds on save; check with `.\tools\phone.ps1 build`. Never a bare `git commit`
> — always `git commit -m "..." -- <your files>`. `git add` each file as you write it. If you
> hit a merge conflict, STOP and say so — do not resolve it. Device work: `.\tools\phone.ps1`
> and `tasks/TAPMAP_NOTE9.md`. Report what you did NOT verify as well as what you did.

### A — Image presets, round two ⭐ JoyRaptor's own bug list

```
Read tasks/LANES.md, then tasks/SPEC_20260829_IMAGE_PRESETS_V2.md. Branch joy-creator.
Claim your lane. Never gradle - check builds with .\tools\phone.ps1 build. Never a bare
git commit. git add as you write. Merge conflict: STOP and say so. Device:
.\tools\phone.ps1 + tasks/TAPMAP_NOTE9.md.
Section 1 is the governing rule: applying a preset is a FULL RESET, not a modification.
The invariant in acceptance check 2 is the whole spec in one test.
Report what you did NOT verify.
```

### B — Verification sweep ⭐ highest value, no code

```
Read tasks/LANES.md, then tasks/TAPMAP_NOTE9.md, then
tasks/SPEC_20260829_DEVICE_VERIFY_ALL.md including section 2b. Branch joy-creator.
FIRST run .\tools\phone.ps1 install, THEN screenshot and RE-DERIVE every tap coordinate
from that screenshot - a previous sweep reported a false audio regression from stale
coordinates. Take the DEVICE token. Writes NO production code: if you find a defect, write
it down, do not fix it.
25 PASS / 10 FAIL / 6 BLOCKED is a success. 41 PASS is not believable and will be checked.
```

### C — Word Sync mode

```
Read tasks/LANES.md, then tasks/SPEC_20260829_WORD_SYNC.md. Branch joy-creator. Claim your
lane. Never gradle, never a bare git commit, git add as you write, merge conflict = STOP.
Sections 3.3, 3.4 and 3.5 are ALREADY BUILT and covered by 44 passing harness tests
(run-onset.sh, run-wordsync.sh). Consume PcmSidecar, ScrubEngine, OnsetDetector,
WordSyncOnsets, WordSyncRipple and TimeShuttleView - do NOT rebuild them. What remains is
the mode: toggle, lockout, gesture routing, onset ticks, formatting row.
```

### D — Media import

```
Read tasks/LANES.md, then tasks/SPEC_20260829_MEDIA_IMPORT.md. Branch joy-creator. Claim
your lane. Same rules: never gradle, never a bare git commit, git add as you write, merge
conflict = STOP. Device: .\tools\phone.ps1 + tasks/TAPMAP_NOTE9.md.
Section 2.1 landed already. Owed: multi-select in selection order, the cache cap measured
after 100 videos, and acceptance check 10 - reboot the phone, reopen the project, media
still resolves. Check 10 is the one that matters.
```

### E — Project bundling (finish it)

```
Read tasks/LANES.md, then tasks/SPEC_20260829_PROJECT_BUNDLING.md, then
tasks/VERIFY_BUNDLING_20260829.md. Branch joy-creator. Same rules.
The code landed but NOTHING was verified on a device. Do acceptance check 1 first (old
projects still open), then check 7, which is the point of the whole spec: export, uninstall
the app, reinstall, import, and confirm the imported FONT survived.
```

### F — Fade knobs (finish it)

```
Read tasks/LANES.md, then tasks/SPEC_20260829_FADE_KNOBS.md and
tasks/REPORT_20260829_FADE_KNOBS.md. Branch joy-creator. Same rules.
The code landed but was never verified - the phone was offline. Run .\tools\phone.ps1
install then acceptance 1-8c. Check 8b is the point of the feature: the knob MOVES, and
zooming re-places it on the same MOMENT, not the same pixel.
```

### G — Captions in GL (finish it)

```
Read tasks/LANES.md, then tasks/SPEC_20260829_CAPTIONS_GL.md and
tasks/REPORT_20260829_CAPTIONS_GL.md. Branch joy-creator. Same rules.
Code landed, acceptance 2-7 BLOCKED pending a device. Run .\tools\phone.ps1 install then
work through them. Acceptance 1 is a must-not-land gate: preview must match export.
Comparing a file to itself is NOT that check - that mistake was made and retracted once.
```

### H — Quick wins (finish it)

```
Read tasks/LANES.md, then tasks/SPEC_20260829_QUICK_WINS.md. Branch joy-creator. Same
rules. Section 1's toolbox reorder landed; the long-press affordance for image-as-clip and
its discoverability hint are owed, plus the four acceptance screenshots.
```

---

## 4. When an agent makes a mess — hand these to Claude

Keep these in reserve. Each is written so Claude can act without re-reading the session.

**Red build nobody is working on:**
```
The tree is red and no lane is active. Find what broke it, fix it, and tell me which lane
left it that way. Verify with the typecheck harness, not by reasoning.
```

**An agent hit a merge conflict:**
```
An agent stopped on a merge conflict and did not touch it. Resolve it. Afterwards audit for
silent damage: check strings.xml is still UTF-8 with a BOM, and check no commits were
dropped off the branch.
```

**A report you do not believe:**
```
Agent <X> reported <claim>. Verify it against the repo before I act on it. Check the commits
exist, the tree compiles, and any device claim against what is actually on the phone.
```

**Something broke that used to work:**
```
<Feature> worked and now does not. Find out what changed, using measurements on the device
rather than reading code - dumpsys audio, logcat, and the tap map. Tell me whether it is a
real regression or a test that missed.
```

**Everything feels tangled:**
```
Read tasks/LANES.md and tasks/RUNBOOK_SCOTT.md, then give me the current true state: what is
green, what is red, what is claimed, and what is actually owed. Verify against the repo.
```

---

## 5. The docket

### Working and confirmed by JoyRaptor

- **Audio playback** — no start delay, no stutter, drift-locked
- **Audio scrubbing** — drag the playhead and hear it

### Landed, compiles, NOT yet seen working

Every one of these is worth ten minutes of your eyes.

| | Where to look |
|---|---|
| Image animation presets + Fit/Fill | image drawer — JoyRaptor has tested; v2 fixes are pending |
| Fade knobs on clips and captions | select a clip, look above its top edge |
| Captions in GL | should look identical, but a blend above a caption now works |
| Project bundling / consolidate | project menu |
| Internal media picker + video thumbnails | Add → Video |
| Image button in the toolbox | bottom toolbar |
| Caption layers — up to 3 tracks | caption drawer track list |
| Keyframe shape language + white stroke | timeline keyframes |
| A/V Sync calibration | audio drawer |

### Specced, unclaimed

`IMAGE_PRESETS_V2` · `DEVICE_VERIFY_ALL` · `WORD_SYNC` · `MEDIA_IMPORT` (part) ·
`PROJECT_BUNDLING` (verify) · `FADE_KNOBS` (verify) · `CAPTIONS_GL` (verify) ·
`QUICK_WINS` (part)

### Not specced — needs a conversation with JoyRaptor first

| Item | Why it is waiting |
|---|---|
| **Per-word rich text in a caption cue** | a model change; Word Sync's B/U/I buttons need it |
| **Fade to black on the master spine** | costed at <1 day model + 2–3 days preview/export plumbing |
| **Multi-select fade/trim** | JoyRaptor has not decided; ruling drafted in `FADE_KNOBS` §3 |
| **Visualizer into GL** | the GL finding calls its cost **unmeasured inference** — profile first |
| **Media-browser pin** | untouched since the fork began |
| **Slide object** | parked, and probably obsolete now caption layers exist |

### Known and unfixed

- **AudioTrack churn** — ~34 allocated per project-open plus playback. Not audible; real.
- **Export GL timing / PSNR** — never measured. Baseline to beat: **1m38s**.
- **~1:03 playback ceiling** — never reproduced; may be gone.

---

## 6. Two things that will bite if forgotten

**`strings.xml` is UTF-8 with a BOM.** It was silently rewritten as UTF-16 and had to be
restored. It still BUILT in that state, so nothing complained. If anything touches it, check:

```powershell
file app/src/main/res/values/strings.xml
```

**Commits can vanish without an error.** One did. If a file an agent swears it wrote is
missing, it is probably still in git and just off the branch — that is a Claude job, not a
rewrite-it-from-scratch job.
