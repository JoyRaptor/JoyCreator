# GIT PRACTICE — how this repository is kept, by agents, for an owner who is not a developer

**Written 2026-09-10.** The owner cannot maintain this repository and should not have to.
Every agent working here maintains it. That means these rules are part of the job, not
housekeeping to do if there is time.

---

## 1. The identity rule

**The owner is `JoyRaptor`. The project email is `studio@joycreator.cc`.**

His real name, his personal email and his phone serial numbers were removed from every
file, every commit message and every piece of commit metadata on 2026-09-09. That required
two complete history rewrites, a repair pass across 101 documents, and a mistake that had
to be caught and undone. **It cannot be done casually a second time once the repo is
public.**

He has a stalker problem. This is a safety matter, not tidiness.

| Instead of | Write |
|---|---|
| his given name | JoyRaptor |
| his personal email | studio@joycreator.cc |
| a device serial | "the sandbox phone" / "the Note 20" |

Real serials live in `tools/devices.local.sh`, which is gitignored. `tools/build-install.sh`
reads them from there and **fails closed** if that file is missing, because guessing which
phone is attached is how a build once landed on the phone holding real projects.

### The guard

`.githooks/pre-commit` and `.githooks/commit-msg` block these strings from staged content
and from commit messages. They are wired up with `core.hooksPath = .githooks`, so they
travel with the repo — but **a fresh clone must run this once:**

```bash
git config core.hooksPath .githooks
```

**Never bypass a hook failure with `--no-verify`.** If you believe it is a false positive,
say so in your report and ask. Do not decide alone.

---

## 2. Commits

- **Commit as you go.** `git add` each file as you write it. This repo has lost work to
  agents holding everything until the end.
- **Never `git commit` bare** and never resolve a merge conflict — see `LANES.md`. Agent
  merges on 2026-08-29 caused silent data loss twice in one day, including a whole spec
  vanishing off the branch with no error.
- **Messages say what changed and why**, in plain language. The owner reads these. They are
  how he knows what happened while he was asleep.
- Every commit ends with the co-author trailer the harness gives you.

---

## 3. Pushing

```bash
git push origin joy-creator
```

`joy-creator` is the working branch. `master` is the upstream fork point and is not where
work goes.

**Push at the end of every working session.** The whole reason the remote exists is that
this project spent a year on one hard drive with no backup. A session that ends unpushed
puts it back there.

The remote is `https://github.com/JoyRaptor/JoyCreator.git`. Credentials are already
configured on the owner's machine; a push should not prompt.

---

## 4. What must never be committed

- API keys, tokens, passwords — in any file, including task notes and verification reports
- Real device serials (see §1)
- Anything under `tools/devices.local.sh`
- Large media used for testing (`*.mp4` scratch files, screen dumps)
- The `joycreator-backup-*.git` folder — it holds the **pre-scrub** history and still
  contains the owner's real name and email. **Never push it anywhere.**

---

## 5. The backup of the old history

`../joycreator-backup-20260909-2035.git` — 371 MB, sitting beside the project folder.

It is the original history, before the identity scrub. It exists so that today's rewrite
can be undone if something turns out to be wrong. It contains personal information.

**Keep it. Do not upload it. Do not delete it without asking the owner.**

---

## 6. The documents an agent must keep current

| File | When you update it |
|---|---|
| `STATE.md` | When a feature is **proved on a phone** — not when the code lands |
| `ORPHANS.md` | When you finish something on it, or find new started-and-stopped work |
| `ROADMAP.md` | When an item is done. Before-launch is a **closed list** — nothing gets added without something coming out |
| `INBOX.md` | Where new ideas go. **Never** write a new `SPEC_*.md` for an unscheduled idea |
| `LEDGER.md` | The story: what was fixed, how it was proved |

**The rule that makes `STATE.md` worth anything: a checkbox is a wish, a commit is a fact,
a screenshot is proof.** This repo's documents have been confidently wrong in both
directions — features marked undone that shipped months ago, and features marked complete
that no human has ever seen. Grade by evidence, never by paperwork.

---

## 7. If something goes wrong

Stop and say so at the top of your report. Do not improvise a fix to a git problem.

The owner is an artist, not an engineer, and cannot unpick a bad merge or a broken rewrite.
Hand it to a Claude session with the full context instead. A clear "this broke, I stopped,
here is the state" is always better than a confident repair that loses work.
