# How to work on Joy Creator (every agent, every harness)

Read [ROADMAP.md](ROADMAP.md) first, then this page, then your job card. JoyBrush work also follows
[joybrush/AGENT_START_20261026.md](joybrush/AGENT_START_20261026.md), which is stricter for the engine.

## Who you are working for

The owner, JoyRaptor, is an artist, **not a developer**. He works one-handed, often holding a
baby, on a Samsung Note 9 and Note 20. So:

- **Make the engineering calls yourself** and explain them in what the user sees. Ask him only for
  taste, money, identity or anything permanent (see "Waiting on the owner" in the roadmap).
- **Report in plain words**, usually a short emoji status table. No jargon, no unexplained branch names.
- **Phone-first, never desktop-only.** A web tool is a pilot; the feature is done when it is on the phone.
- **Say what you did not check.** "Built, not tried on the phone" is fine. Claiming more than you
  proved is the one unforgivable thing here.

## Rules that each cost something real

1. **The owner is `JoyRaptor`.** Never his real name, never his personal email, never a phone serial in a
   tracked file. Git hooks block all three; never bypass them with `--no-verify`.
2. **Never `adb uninstall` and never clear app data**: it once destroyed 23 of 24 projects.
3. **Never resolve someone else's merge conflict by guessing.** Stop, keep both sides, and say so.
   Agent merges have silently lost a whole spec before.
4. **Push at the end of every session**, and check the remote has it.
5. **Preview and export must agree.** If you change one, prove the other still matches.
6. **The AI changes projects only through validated EditScripts**, never raw JSON.
7. **Half-built means unfinished, not abandoned.** Recover it; never archive or delete it unasked.

## Before you start

1. **Check the BOARD** ([BOARD.md](BOARD.md)). If someone holds your files, pick something else or
   message them there.
2. **Claim your job** on the BOARD: one row, with your session name, harness, branch and the files you
   will touch.
3. **Check your sources are current.** For every claim you rely on, look at its date and the current
   code (`git log -1 -- <file>`, `git grep`). Anything older than a week is suspect.

## Where to work: never in the main folder

`C:\+Projects\JoyCreator` (the "main folder") is where the owner's build watcher runs and where
older sessions left unsaved work. **Do not pull, reset, merge, stash or switch branches there.**

```
git -C "C:/+Projects/JoyCreator" fetch origin
git -C "C:/+Projects/JoyCreator" worktree add C:/Temp/<topic>-<yyyymmdd> -b <harness>/<topic>-<yyyymmdd> origin/joy-creator
```

- Commit **only your own paths**, by exact path (never `git add -A` across shared folders).
- Commit author is JoyRaptor; end the message with your harness's co-author line.
- **Never commit secrets.** `tasks/api.txt` holds a real key; GitHub blocks pushes that contain one.
  The pre-commit hook also refuses the phone's serial number: write "the Note 9", not the serial.
- To publish: `git pull --rebase origin joy-creator`, then `git push origin HEAD:joy-creator`. Never force.
- After you push, the main folder does **not** see it until the lead fast-forwards it (job HYG-1).
- Files that are CRLF stay CRLF. Write code with your file-writing tool, never through a shell
  (the shell turns `$$`, `$(`, backticks and `\\` into something else).

## Building

- The owner's watcher (`watch-build.ps1`) builds the main folder into `build.log` (UTF-16).
- **One heavy job on the machine at a time.** Take `C:/Users/JoyRaptor/AppData/Local/Temp/jb-gradle.lock`
  (create-new; if it exists, someone is building: wait or do something else). Check free RAM first:
  about 2.2 GB for focused tests, 2.8 GB for full tests, 3.2 GB for an APK.
- **Check disk space first.** C: filled up on 10-24 and broke every session's builds. D: has room.
- A quick type check without Gradle: `tools/jvm-harness/typecheck.sh`.
- "Unable to establish loopback connection" from Gradle is a Windows socket problem, not your code
  (see the lessons file).

## The phones

| Phone | What it is | Rules |
|---|---|---|
| **Note 9** | The sandbox | Install and test freely. Never uninstall and never clear data. Never touch his drawings. |
| **Note 20** | His real projects | Install only when he asks. Upgrade in place, never fresh. |

- **One agent drives a phone at a time:** `.\tools\phone.ps1 claim <name> "<why>"`, refresh it while you
  work, `.\tools\phone.ps1 release <name>` when done. Looking (screenshots, logcat) needs no lock.
- **Never launch with `adb shell monkey`**: it turns his rotation lock off. Use `am start -n <activity>`.
- If JoyBrush is in front with a drawing open, press Home first: an install kills an unsaved drawing.
- Log the phone's temperature every 30 seconds while testing and say if it throttled.
- Full adb recipes: [DEVICE_CONTROL_RUNBOOK.md](DEVICE_CONTROL_RUNBOOK.md).

## The house style (binding)

The design records in [tasks/design/](design/) are the truth for how things look. Read
`UI-JoyCreator-grand-design.html`, `UI-Studio-final.html` and `UI-The-Marquee_lobby_and-first-run-intro.html`
before any visual work. The short version:

- Old-black ground, zinc greys; colour only to guide. **On = solid cyan with the icon punched out**, never an outline.
- Drawers are see-through (about 55%); "Frost" adds blur, never a solid fill.
- dp values stay as drawn (he prefers the Note 9's dense look).
- **One press = one undo step**, even for a batch.
- **Every button gets a hover label** (stylus and mouse users see them).
- **Keep the old behaviour selectable** when you rework a tool; he picks by comparing.
- An automatic guess always keeps a visible, one-tap way back.
- New code goes in small, single-purpose classes. Keep the legacy version until the new one is proven better.
- Half-built work is unfinished, not abandoned: recover it, never delete it unasked.

## When you finish a piece of work

In this order, every time:

1. Push your commits, then check the remote has them (`git ls-remote origin <branch>`).
2. **STATE.md**: add or update the feature's row: what the user gets, where they find it, its proof
   level, and its help mapping. Joybot's in-app help and the advertising are built from this file.
   **No STATE row means not done.**
3. **Your job card**: tick what you finished, write what is left, and link your handoff if you wrote one.
4. **The roadmap row**: change the state if it changed, and the "Checked" date.
5. **The BOARD**: remove your claim, and leave a one-line message if someone needs to know.
6. If the owner corrected you, add the rule to [lessons.md](lessons.md).

## Model tiers (to save usage)

| Tier | Good for |
|---|---|
| Cheap (Haiku, Luna, free models) | Inventories, read-only audits, phone sweeps, mechanical edits |
| Mid (Sonnet, Sol) | Restyles with clear guidance, spec'd features, test writing |
| Frontier (Opus) | Design, whole-screen redesigns, hard engine work, reviewing others' claims |

Verify every subagent's claim yourself before you report it. Agents over-report.
