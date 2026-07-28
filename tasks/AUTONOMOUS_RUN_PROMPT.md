# Autonomous polish/audit run — paste-in prompt

Launch with the loop skill so it re-wakes itself when usage resets:

```bash
/loop Read tasks/AUTONOMOUS_RUN_PROMPT.md and follow it. Continue where the progress log says you left off.
```

`/loop` with no interval self-paces: the agent calls `ScheduleWakeup` each turn to set its own
next wake, so a usage-limit stall is survived rather than ending the run. Everything below the
rule is the standing instruction.

---

FadCam / Joy Creator — `C:\+Projects\Screenrecorder\FadCam`, branch `joy-creator`.
**Autonomous polish + audit run. Work continuously; do not wait for me.**

## Orient first (every wake, in this order)

1. `tasks/HANDOFF_20260726_CONTEXT_SWITCH.md` §0z PROGRESS LOG — newest entry first.
2. `tasks/NEXT_SESSION_PROMPT_20260727.md` — the outstanding-work list.
3. `git log --oneline -20` — trust this over any prose.
4. **Append your own §0z entry before you run out of context**, newest-first, saying what you
   landed, what you proved, and what you were mid-way through. That entry is how the next wake
   knows where it is.

## What to work on, in priority order

1. **Finish what's already scoped.** `tasks/NEXT_SESSION_PROMPT_20260727.md` lists it: the
   second stranded-latch path (instrument is already in the build and prints `lastUp:`), the
   transcript dedup + `activeTranscript` index→id migration, the inter-clip seam stall.
2. **Audit for unfinished work, then VERIFY each item against the code before touching it.**
   `tasks/AUDIT_UNFINISHED_20260726.md` had entries that were already fixed and never updated
   (1.2, 1.3, 1.5) — three passes were wasted re-scoping done work. Read its **METHOD NOTE**
   first: a checklist sweep is structurally blind to architecture stated as a premise in prose,
   which is how a whole specified-but-unbuilt subsystem hid for three passes.
3. **Run the second instrument** — the one that finds what a docs audit cannot:
   ```bash
   grep -rn -i "never quite\|does not yet\|known limitation\|should really\|not actually\|for now" app/src/main/java --include=*.java
   ```
   Each hit: is it tracked anywhere? Is it mitigated? Record CLEARED ones by name so they are
   not re-investigated.
4. **Comb spec docs for intent that never became a task** — the premise/checklist gap above.
   `tasks/*.md` problem statements often describe a design the implementation never reached.
5. **Polish**: the SPEC §12/§13 items, dead code, stale comments that assert things now false.

## Device rules — NON-NEGOTIABLE

- **The Note 9 `SANDBOX_SERIAL` is the sandbox and must be the ONLY phone attached.**
- **If the Note 20 `REAL_SERIAL` appears, STOP all device work immediately.** It is the user's
  real phone holding the large 45-minute project. The build watcher auto-installs to whatever
  single device is attached, which kills the running app. Never write to its `project.json`
  without the user present.
- adb: `C:\Users\JoyRaptor\AppData\Local\Android\Sdk\platform-tools\adb.exe`. Never `logcat -c`.
- Gradle needs `$env:TEMP='C:\Users\JoyRaptor\gtmp'; $env:TMP=$env:TEMP`. Never `--rerun-tasks`.

## Adversarial pass — REQUIRED on everything you land

Do not ship anything on a single line of reasoning. For each change, before committing:

1. **State the claim** the fix rests on, then **try to refute it.** What observation would prove
   this wrong? Go look for that observation, not for confirmation.
2. **Build a positive control.** A test that passes because the instrument is blind proves
   nothing. Every assertion needs a paired check that FAILS if the instrument stops working.
3. **Two data points minimum for any inferred pattern.** A single measurement plus a plausible
   mechanism produced a confidently wrong diagnosis this session (a constant seek offset), and a
   second measurement killed it in one line (the error was a rate, not an offset). One point can
   always be fitted by more than one story.
4. **Check your signal isn't self-manufactured.** A duplicate-word detector nearly used "gap =
   0 ms" as evidence — a value the pipeline's own interpolation creates.
5. **A green test can encode the wrong requirement.** One harness here asserted that a window
   COPIES its words, passed, and was defending a design error. When a test passes first try, ask
   what it would have to see to fail.
6. **Never trust "compile-green".** `build.log` is UTF-16 (`Get-Content -Encoding Unicode`).
   Confirm the APK installed: `dumpsys package com.fadcam.beta | grep lastUpdateTime` must be
   NEWER than the newest `app/src` mtime. A build can succeed and fail to install ("No connected
   devices!") and the watcher does **not** retry — touch a source file to retrigger.
7. **Say "unverified" when it is.** Label compile-verified-only work as such in the commit.
   Distinguish what was PROVED from what was reasoned.

## Traps already paid for — do not rediscover these

- `adb logcat` without `-T` **replays the whole ring buffer**, so a freshly-armed monitor
  re-reports old lines and looks like a live regression. Use `logcat -T 1`.
- **Git Bash mangles absolute device paths** (`/data/local/tmp` → `C:/Program Files/Git/...`).
  Use PowerShell for `adb push` / `adb shell` with device paths.
- **PowerShell `>` re-encodes** a stream — `adb exec-out ... > file` corrupts exact bytes. Use
  the Bash tool for faithful pulls, and byte-compare with `cmp`.
- Before writing any user `project.json`: **re-pull and compare**, back up, write, then **read
  back and byte-compare**. The file moves while the user edits; a stale write silently deleted a
  waveform overlay.
- The editor autosaves — a write while `FaditorEditorActivity` is resumed gets overwritten.
- Two sources of truth for one string will drift: `ModelType`'s labels and the picker's
  `strings.xml` did exactly that.

## Working rules

- One commit per fix, stating **what was proved and how**. `git commit -F`, not `-m`.
- Keep the tree clean. Harness output dirs are gitignored (`tools/jvm-harness/out*/`).
- Prefer a JVM harness (`tools/jvm-harness/`) over assertion — several exist as models:
  `UndoManagerTest` (34/34), `ObjectPaletteTest` (30/30), `TranscriptMergeTest` (34/34).
- **Do not** touch the user's Note 20 project, publish anything, or make decisions flagged as
  needing the user. Where a decision is genuinely theirs, write it up in the handoff and move to
  the next item rather than guessing.
- If you finish everything, run the grep sweep again over a different phrase set and keep going.

## Self-pacing

Each turn, call `ScheduleWakeup` with the same `/loop` prompt so the run survives a usage stall.
For an idle tick with no specific signal, 1200–1800s. If you are waiting on a build, match the
delay to the build. End the run with `ScheduleWakeup(stop: true)` only if there is genuinely
nothing left, and say so in the progress log.
