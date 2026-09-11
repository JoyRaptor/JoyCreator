# START HERE — Joy Creator

Drop this into any new session. It is a map, not a manual: it tells you what exists and
where to look, so you do not rebuild something that already ships or rediscover a rule the
hard way.

---

## What this is

**Joy Creator** — a video studio for Android. Capture → Library → Studio. A real
compositor: layers, masks, keyframes, GL transitions, sprites, rigs, offline transcription,
and an AI assistant with 55 tools that edit the actual timeline.

Built on a fork of FadCam (GPLv3 screen recorder). Two builds from one codebase: a
sanitised **Play** build, and a full-featured **website / F-Droid** build.

**The promise that never moves:** free forever, unhobbled. No ads, no subscription, no
watermark, no export paywall. AI is bring-your-own-key at zero margin.

## Who you are working for

The owner is **JoyRaptor** — an artist, not a developer, working in fragments around
childcare. Make **engineering** calls yourself and justify them in end-user terms. Bring him
**product and UX** decisions. He cannot read code, cannot unpick a bad merge, and cannot
maintain the repo — **you maintain it on his behalf.**

He is often away while you work. Keep going rather than blocking.

---

## The map — if you need X, read Y

| You need | Read |
|---|---|
| **What exists and how proven it is** | `tasks/STATE.md` ← start here, always |
| What is next, in order | `tasks/ROADMAP.md` |
| Work started and abandoned, to be finished | `tasks/ORPHANS.md` |
| Somewhere to put a new idea | `tasks/INBOX.md` |
| What was fixed, and how it was proved | `tasks/LEDGER.md` |
| Mistakes already made once | `tasks/lessons.md` |
| Git, identity, push discipline | `tasks/GIT_PRACTICE.md` |
| File locks when other agents are working | `tasks/LANES.md` |
| Driving the phone | `tasks/RUNBOOK.md` |
| Launch, pricing, the two builds | `tasks/LAUNCH_STRATEGY.md` |
| Project file format | `docs/project-schema.md` |

`tasks/` holds 175 files. Most are history. **Do not read them looking for current truth** —
the five files above are the current truth; the rest are the story of how it got here.

---

## Rules that prevent expensive mistakes

Each of these cost something real. They are not style preferences.

1. **The owner is `JoyRaptor`. Never his real name, never his personal email, never a device
   serial.** Git hooks block all three. Do not bypass with `--no-verify`. See
   `GIT_PRACTICE.md`.
2. **Never run gradle.** A watcher builds on save. Check with `.\tools\phone.ps1 build` — if
   the timestamp is old the watcher is dead and nothing you claim is real.
3. **Never `adb uninstall`.** Projects live in app-private storage and Android wipes it
   silently. This destroyed 23 of 24 projects on 2026-08-29.
4. **Never resolve a merge conflict.** Stop and say so. Agent merges caused silent data loss
   twice in one day, including a whole spec vanishing off the branch with no error.
5. **`git add` each file as you write it.** Work has been lost to agents holding everything
   until the end.
6. **Claim a lane in `LANES.md`** before editing. Other agents are working right now.
7. **Push at the end of every session.** This project had no backup at all until
   2026-09-09.
8. **The AI only mutates projects through validated EditScripts** — never raw JSON.
9. **Preview and export must agree.** If you change one, prove the other still matches.
10. **Half-built means unfinished, not abandoned.** Recover orphans; do not archive them.
    Push back on an idea only with a substantive reason it is wrong, never because it is
    incomplete.

---

## The one epistemic rule

**A checkbox is a wish. A commit is a fact. A screenshot is proof.**

This repo's documents are confidently wrong in *both* directions. Verified: a feature with
23 unticked boxes that shipped months ago; three components listed "built, not wired" that
were wired the next day; 13 features marked complete that no human has ever looked at; and a
"critical regression" that blocked six checks for twelve days before turning out to be an
agent tapping the wrong screen coordinates.

Grade by evidence. When a document and the code disagree, the code wins. When the code and
the phone disagree, the phone wins.

---

## Driving the phone

```
.\tools\phone.ps1 build      # is the watcher alive, did it build
.\tools\phone.ps1 install    # put the build on the phone
.\tools\phone.ps1 devices    # 'device' good, 'offline' = replug and unlock
.\tools\phone.ps1 audio      # AudioTrack 'started' = sound is really coming out
```

Two phones: a **sandbox** to install onto, and the owner's **Note 20** which holds real
projects. `tools/build-install.sh` refuses to run if the Note 20 is attached. Real serials
are in `tools/devices.local.sh`, gitignored — never put them in a tracked file.

**Compile-verified is not device-verified.** Say which one you did.

---

## The wider project, so you do not assume it is only an app

- **GitHub:** `github.com/JoyRaptor/JoyCreator`, branch `joy-creator`. The owner cannot
  maintain it — agents do. See `GIT_PRACTICE.md`.
- **Domain:** `joycreator.cc`. Email `studio@joycreator.cc`.
- **Website:** not built yet. Planned — GitHub Pages, free.
- **Play Store:** not submitted. A 14-day closed test with 12 testers is required and has
  not started.
- **Desktop sprite editor:** planned as a web app on the site. Sprites built there travel
  into the app as a sheet plus its `.sprite.json` sidecar — that sidecar is already
  documented in code as *"the sharing format, the plugin contract."*
- **Packs:** a folder of transitions, LUTs, slide templates, themes that users can drop in
  and share. The app already loads dropped-in `.glsl` files — that is a working pack loader
  that has not been named yet.

---

## Currently the biggest open items

- **Projects are deleted on uninstall.** They live in app-private storage. The vault — a
  folder the user picks, survives uninstall, visible from a PC, works on an SD card — is
  planned and not built. Everything else about portability already works.
- **13 finished features nobody has ever looked at** (`ORPHANS.md` §1). Not a build
  backlog — a *looking* backlog.
- Default AI model is the **paid** router; every new user is billed on message one.

Not sure where something belongs? Put it in `INBOX.md` and say so. Never start a new
`SPEC_*.md` for an unscheduled idea — that habit is how `tasks/` reached 175 files.
